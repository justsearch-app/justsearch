/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static io.justsearch.ort.SessionAcquireDeadlineExceededException.Phase.GATE;
import static io.justsearch.ort.SessionAcquireDeadlineExceededException.Phase.RUN;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Releases a caller at its deadline without cancelling the native run it was seated for (Spec D
 * §1.5).
 *
 * <p>An ORT native run cannot be cancelled. The caller has already been seated (it holds its lease
 * and, on the GPU, a seat of the device gate). {@link #runWithin} hands only the native call to
 * a platform run thread and waits for it until the deadline. If the run returns in time the caller
 * gets the result and closes its lease as usual. If it does not, the lease becomes
 * <em>detached</em>: the caller gets {@link SessionAcquireDeadlineExceededException.Phase#RUN},
 * its {@link SessionHandle.Lease#close()} becomes a no-op, and the run thread performs the real
 * release when the native call returns, after closing and dropping the result. The release is the
 * owner's idempotent release, so it happens exactly once whichever side performs it.
 *
 * <p>While a detached run is in flight its lease still counts as held: on the GPU it keeps its
 * device seat, retirement waits for it, and {@link NativeSessionHandle#releaseGpu()} waits for it
 * up to its bound and then retires the session to it; the session is never closed mid-run. A run
 * that never returns therefore keeps its session, its seat and its GPU memory; that is a named
 * limitation of this design (ADR-0050; Spec D §10 Q12).
 *
 * <p><b>Run threads per device.</b> Every query-encoder handle on one device shares that device's
 * {@link Device} (one process-lifetime registry entry per device); the reranker has a {@link
 * Device} of its own on each device, so the rerank runs a search abandons are bounded by the
 * reranker's run threads and never take a query leg's. At most {@link #RUN_THREADS_PER_DEVICE} run
 * threads are in flight on a device at once, whether their caller is still waiting or was released.
 * The count is taken under the device's lock before a run thread starts, so it holds under any
 * number of concurrent callers, and a detached run is always one of those run threads: a device
 * never holds more than {@link #RUN_THREADS_PER_DEVICE} detached runs. A caller that finds every
 * run thread of its device taken does not start a run:
 *
 * <ul>
 *   <li>when every run thread holds a detached run, it fails at once with {@code RUN} (the device is
 *       stuck, Spec D §1.5 item 4);
 *   <li>otherwise it waits for a run thread until its own deadline, and then fails with {@code GATE}
 *       (it was never run, so the miss is contention).
 * </ul>
 *
 * Either way the caller returns by its deadline, and a query leg that receives the failure answers
 * with its fallback.
 *
 * <p>Run threads are platform threads: a native call pins a virtual thread's carrier for its whole
 * duration. The native call runs on copies of the caller's input tensors, owned by the run thread,
 * because the caller closes its own tensors when it unwinds and the run may still be reading them.
 */
final class DetachableRun {

  private static final Logger log = LoggerFactory.getLogger(DetachableRun.class);

  /**
   * Run threads one device allows in flight, live or detached: the device's seat count (one) plus
   * one (Spec D §1.5, a run executor of {@code deviceSeats + 1}). It is also the most detached runs
   * a device can hold.
   */
  static final int RUN_THREADS_PER_DEVICE = 2;

  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  /**
   * One platform thread per run, not a pool: executors are owned by the engine's executor registry,
   * which this module does not reach. Run threads are bounded per device by {@link Device}.
   */
  private static final ThreadFactory RUN_THREADS =
      Thread.ofPlatform().daemon(true).name("ort-query-run-", 0).factory();

  /** One entry per device for the life of the process, shared by every handle on that device. */
  private static final Map<String, Device> DEVICES = new ConcurrentHashMap<>();

  private DetachableRun() {}

  /**
   * The run-thread accounting of {@code deviceKey} (for example {@code cpu}, {@code cuda:0} or the
   * reranker's own {@code reranker@cpu}).
   */
  static Device device(String deviceKey) {
    return DEVICES.computeIfAbsent(deviceKey, Device::new);
  }

  /** The run threads of one device. Counts are guarded by the device's lock. */
  static final class Device {
    private final String key;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition freed = lock.newCondition();
    /** Run threads whose caller is still waiting for them. */
    private int live;
    /** Run threads whose caller was released at its deadline and that have not returned. */
    private int detached;

    private Device(String key) {
      this.key = key;
    }

    /**
     * Takes a run thread of this device for one run, waiting no later than {@code deadlineNanos}.
     *
     * @throws SessionAcquireDeadlineExceededException {@code RUN} at once when every run thread
     *     holds a detached run; {@code GATE} when none was free by the deadline
     * @throws CancellationException when the caller is cancelled or interrupted while waiting
     */
    void enter(SessionAcquisitionRequest acquisition, long deadlineNanos) {
      lock.lock();
      try {
        checkAuthority(acquisition, deadlineNanos);
        while (live + detached >= RUN_THREADS_PER_DEVICE) {
          if (live == 0) {
            throw new SessionAcquireDeadlineExceededException(
                "every run thread for device " + key + " holds a detached native run", RUN);
          }
          if (acquisition != null && acquisition.cancellationRequested().getAsBoolean()) {
            throw new CancellationException("native run not started: caller cancelled");
          }
          long remaining = deadlineNanos - System.nanoTime();
          if (remaining <= 0) {
            throw new SessionAcquireDeadlineExceededException(
                "no run thread for device " + key + " was free before the deadline", GATE);
          }
          try {
            freed.awaitNanos(Math.min(remaining, POLL_NANOS));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancellation =
                new CancellationException("native run not started: caller interrupted");
            cancellation.initCause(interrupted);
            throw cancellation;
          }
          checkAuthority(acquisition, deadlineNanos);
        }
        live++;
      } finally {
        lock.unlock();
      }
    }

    /** Gives back a run thread taken by {@link #enter} that never started its run. */
    void leave() {
      lock.lock();
      try {
        live--;
        freed.signalAll();
      } finally {
        lock.unlock();
      }
    }
  }

  private static void checkAuthority(SessionAcquisitionRequest acquisition, long deadlineNanos) {
    if (Thread.currentThread().isInterrupted()
        || (acquisition != null && acquisition.cancellationRequested().getAsBoolean())) {
      throw new CancellationException("native run not started: caller cancelled or interrupted");
    }
    if (deadlineNanos - System.nanoTime() <= 0) {
      throw new SessionAcquireDeadlineExceededException("native run deadline already passed", GATE);
    }
  }

  /**
   * A lease's release that its run thread can take over. Until the lease is detached, {@link #run}
   * performs the owner's release; afterwards it is a no-op and the run thread releases.
   */
  static final class Release implements Runnable {
    private final Runnable ownerRelease;
    private final Device device;
    private volatile boolean detached;

    /**
     * @param ownerRelease the owner's release; must be idempotent
     * @param device run-thread accounting for the device the lease's session runs on
     */
    Release(Runnable ownerRelease, Device device) {
      this.ownerRelease = ownerRelease;
      this.device = device;
    }

    @Override
    public void run() {
      if (!detached) ownerRelease.run();
    }
  }

  /**
   * Runs {@code lease}'s native call, returning to the caller no later than {@code deadlineNanos}
   * (or the caller's cancellation). A lease whose release cannot be taken over, or inputs that
   * cannot be copied, run on the caller's thread as before.
   */
  static OrtSession.Result runWithin(
      SessionHandle.Lease lease, Map<String, OnnxTensor> inputs, long deadlineNanos)
      throws OrtException {
    return runWithin(lease, inputs, deadlineNanos, RUN_THREADS);
  }

  static OrtSession.Result runWithin(
      SessionHandle.Lease lease, Map<String, OnnxTensor> inputs, long deadlineNanos,
      ThreadFactory threads) throws OrtException {
    checkAuthority(lease.acquisition(), deadlineNanos);
    if (!(lease.release() instanceof Release release)) return lease.runOnCallerThread(inputs);
    Map<String, OnnxTensor> owned = copyOf(inputs);
    if (owned == null) return lease.runOnCallerThread(inputs);
    Device device = release.device;
    try {
      device.enter(lease.acquisition(), deadlineNanos);
    } catch (RuntimeException notStarted) {
      closeAll(owned);
      throw notStarted;
    }
    Task task = new Task(lease, release, owned, deadlineNanos);
    try {
      threads.newThread(task).start();
    } catch (RuntimeException | OutOfMemoryError noThread) {
      device.leave();
      closeAll(owned);
      var failure =
          new SessionAcquireDeadlineExceededException("no run thread for the native run", RUN);
      failure.initCause(noThread);
      throw failure;
    }
    return task.await(deadlineNanos);
  }

  private static final class Task implements Runnable {
    private static final int PENDING = -1;
    private static final int RUNNING = 0;
    private static final int COMPLETED = 1;
    private static final int ABANDONED = 2;
    private static final int CANCELLED_BEFORE_START = 3;

    private final SessionHandle.Lease lease;
    private final Release release;
    private final Device device;
    private final Map<String, OnnxTensor> inputs;
    private final long deadlineNanos;
    private final AtomicInteger state = new AtomicInteger(PENDING);
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile OrtSession.Result result;
    private volatile Throwable failure;

    Task(SessionHandle.Lease lease, Release release, Map<String, OnnxTensor> inputs, long deadlineNanos) {
      this.lease = lease;
      this.release = release;
      this.device = release.device;
      this.inputs = inputs;
      this.deadlineNanos = deadlineNanos;
    }

    @Override
    public void run() {
      device.lock.lock();
      try {
        if (!state.compareAndSet(PENDING, RUNNING)) return;
      } finally {
        device.lock.unlock();
      }
      OrtSession.Result returned = null;
      Throwable thrown = null;
      try {
        checkAuthority(lease.acquisition(), deadlineNanos);
        returned = lease.runOnCallerThread(inputs);
      } catch (Throwable t) {
        thrown = t;
      } finally {
        closeAll(inputs);
      }
      result = returned;
      failure = thrown;
      boolean completed;
      // The state change and the device count move together under the device's lock, so a run
      // is counted as live or as detached, never both and never neither.
      device.lock.lock();
      try {
        completed = state.compareAndSet(RUNNING, COMPLETED);
        if (completed) device.live--;
        else device.detached--;
        device.freed.signalAll();
      } finally {
        device.lock.unlock();
      }
      if (completed) {
        done.countDown();
        return;
      }
      // The caller was released at its deadline: drop the result and perform the real release.
      try {
        if (returned != null) returned.close();
      } finally {
        release.ownerRelease.run();
        log.info("Detached native run returned; lease released by its run thread");
      }
    }

    OrtSession.Result await(long deadlineNanos) throws OrtException {
      SessionAcquisitionRequest acquisition = lease.acquisition();
      try {
        while (true) {
          if (acquisition != null && acquisition.cancellationRequested().getAsBoolean()) {
            return abandon(new CancellationException("native run abandoned: caller cancelled"));
          }
          long remaining = deadlineNanos - System.nanoTime();
          if (remaining <= 0) {
            return abandon(
                new SessionAcquireDeadlineExceededException(
                    "native run did not return before the deadline; the caller is released and"
                        + " the run continues detached",
                    RUN));
          }
          if (done.await(Math.min(remaining, POLL_NANOS), TimeUnit.NANOSECONDS)) return outcome();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        CancellationException cancellation =
            new CancellationException("native run abandoned: caller interrupted");
        cancellation.initCause(interrupted);
        return abandon(cancellation);
      }
    }

    /** Releases the caller, unless the run has just returned, in which case its outcome wins. */
    private OrtSession.Result abandon(RuntimeException toCaller) throws OrtException {
      boolean abandoned;
      boolean unstarted;
      device.lock.lock();
      try {
        unstarted = state.compareAndSet(PENDING, CANCELLED_BEFORE_START);
        abandoned = unstarted || state.compareAndSet(RUNNING, ABANDONED);
        if (abandoned) {
          device.live--;
          if (!unstarted) device.detached++;
          release.detached = true;
          device.freed.signalAll();
        }
      } finally {
        device.lock.unlock();
      }
      if (unstarted) {
        try {
          closeAll(inputs);
        } finally {
          release.ownerRelease.run();
        }
        throw toCaller;
      }
      if (abandoned) {
        log.warn("Native run passed its caller's deadline; continuing detached: {}",
            toCaller.getMessage());
        throw toCaller;
      }
      // The run thread set its outcome before it marked the task completed.
      return outcome();
    }

    private OrtSession.Result outcome() throws OrtException {
      Throwable thrown = failure;
      if (thrown == null) return result;
      if (thrown instanceof OrtException ort) throw ort;
      if (thrown instanceof RuntimeException runtime) throw runtime;
      if (thrown instanceof Error error) throw error;
      throw new IllegalStateException("native run failed", thrown);
    }
  }

  /** Copies the inputs for the run thread to own, or returns null if a tensor type is not copied. */
  private static Map<String, OnnxTensor> copyOf(Map<String, OnnxTensor> inputs)
      throws OrtException {
    Map<String, OnnxTensor> copies = new LinkedHashMap<>();
    try {
      for (Map.Entry<String, OnnxTensor> input : inputs.entrySet()) {
        OnnxTensor copy = copyOf(input.getValue());
        if (copy == null) {
          closeAll(copies);
          return null;
        }
        copies.put(input.getKey(), copy);
      }
      return copies;
    } catch (OrtException | RuntimeException failure) {
      closeAll(copies);
      throw failure;
    }
  }

  private static OnnxTensor copyOf(OnnxTensor tensor) throws OrtException {
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    TensorInfo info = tensor.getInfo();
    long[] shape = info.getShape();
    return switch (info.type) {
      case INT64 -> OnnxTensor.createTensor(env, tensor.getLongBuffer(), shape);
      case INT32 -> OnnxTensor.createTensor(env, tensor.getIntBuffer(), shape);
      case FLOAT -> OnnxTensor.createTensor(env, tensor.getFloatBuffer(), shape);
      case DOUBLE -> OnnxTensor.createTensor(env, tensor.getDoubleBuffer(), shape);
      default -> null;
    };
  }

  private static void closeAll(Map<String, OnnxTensor> tensors) {
    for (OnnxTensor tensor : tensors.values()) {
      if (tensor != null) tensor.close();
    }
  }
}
