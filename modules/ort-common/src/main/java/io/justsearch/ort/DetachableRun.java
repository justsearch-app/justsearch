/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static io.justsearch.ort.SessionAcquireDeadlineExceededException.Phase.RUN;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Releases a caller at its deadline without cancelling the native run it was seated for (Spec D
 * §1.5).
 *
 * <p>An ORT native run cannot be cancelled. The caller has already been seated (it holds its lease
 * and, on the GPU, the handle's inference permit). {@link #runWithin} hands only the native call to
 * a platform run thread and waits for it until the deadline. If the run returns in time the caller
 * gets the result and closes its lease as usual. If it does not, the lease becomes
 * <em>detached</em>: the caller gets {@link SessionAcquireDeadlineExceededException.Phase#RUN},
 * its {@link SessionHandle.Lease#close()} becomes a no-op, and the run thread performs the real
 * release when the native call returns, after closing and dropping the result. The release is the
 * owner's idempotent release, so it happens exactly once whichever side performs it.
 *
 * <p>While a detached run is in flight its lease still counts as held: on the GPU it keeps the
 * inference permit, so {@link NativeSessionHandle#releaseGpu()} and retirement wait for it and the
 * session is never closed mid-run. A run that never returns therefore keeps its session and its GPU
 * memory; that is a named limitation of this design (ADR-0050).
 *
 * <p>Run threads are platform threads: a native call pins a virtual thread's carrier for its whole
 * duration. The native call runs on copies of the caller's input tensors, owned by the run thread,
 * because the caller closes its own tensors when it unwinds and the run may still be reading them.
 */
final class DetachableRun {

  private static final Logger log = LoggerFactory.getLogger(DetachableRun.class);

  /**
   * Detached runs one handle allows per device before a new {@link #runWithin} fails at once with
   * {@code RUN}: the handle's seat count on the device (one) plus one (Spec D §1.5 item 4).
   */
  static final int MAX_DETACHED_RUNS_PER_DEVICE = 2;

  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  /**
   * One platform thread per run, not a pool: executors are owned by the engine's executor registry,
   * which this module does not reach. Live runs are bounded by the handle's seats, which the caller
   * already holds, and detached runs by {@link #MAX_DETACHED_RUNS_PER_DEVICE}.
   */
  private static final ThreadFactory RUN_THREADS =
      Thread.ofPlatform().daemon(true).name("ort-query-run-", 0).factory();

  private DetachableRun() {}

  /** Detached runs of one handle on one device. */
  static final class Slots {
    private final AtomicInteger detached = new AtomicInteger();

    /** Runs that have released their caller and not yet returned. */
    int detached() {
      return detached.get();
    }
  }

  /**
   * A lease's release that its run thread can take over. Until the lease is detached, {@link #run}
   * performs the owner's release; afterwards it is a no-op and the run thread releases.
   */
  static final class Release implements Runnable {
    private final Runnable ownerRelease;
    private final Slots slots;
    private volatile boolean detached;

    /**
     * @param ownerRelease the owner's release; must be idempotent
     * @param slots detached-run accounting for the lease's handle and device
     */
    Release(Runnable ownerRelease, Slots slots) {
      this.ownerRelease = ownerRelease;
      this.slots = slots;
    }

    @Override
    public void run() {
      if (!detached) ownerRelease.run();
    }

    /** Whether the caller was released and the run thread owns the release. */
    boolean detached() {
      return detached;
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
    if (!(lease.release() instanceof Release release)) return lease.runOnCallerThread(inputs);
    if (release.slots.detached() >= MAX_DETACHED_RUNS_PER_DEVICE) {
      throw new SessionAcquireDeadlineExceededException(
          "every run thread for this device holds a detached native run", RUN);
    }
    Map<String, OnnxTensor> owned = copyOf(inputs);
    if (owned == null) return lease.runOnCallerThread(inputs);
    Task task = new Task(lease, release, owned);
    try {
      RUN_THREADS.newThread(task).start();
    } catch (RuntimeException | OutOfMemoryError noThread) {
      closeAll(owned);
      var failure =
          new SessionAcquireDeadlineExceededException("no run thread for the native run", RUN);
      failure.initCause(noThread);
      throw failure;
    }
    return task.await(deadlineNanos);
  }

  private static final class Task implements Runnable {
    private static final int RUNNING = 0;
    private static final int COMPLETED = 1;
    private static final int ABANDONED = 2;

    private final SessionHandle.Lease lease;
    private final Release release;
    private final Map<String, OnnxTensor> inputs;
    private final AtomicInteger state = new AtomicInteger(RUNNING);
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile OrtSession.Result result;
    private volatile Throwable failure;

    Task(SessionHandle.Lease lease, Release release, Map<String, OnnxTensor> inputs) {
      this.lease = lease;
      this.release = release;
      this.inputs = inputs;
    }

    @Override
    public void run() {
      OrtSession.Result returned = null;
      Throwable thrown = null;
      try {
        returned = lease.runOnCallerThread(inputs);
      } catch (Throwable t) {
        thrown = t;
      } finally {
        closeAll(inputs);
      }
      result = returned;
      failure = thrown;
      if (state.compareAndSet(RUNNING, COMPLETED)) {
        done.countDown();
        return;
      }
      // The caller was released at its deadline: drop the result and perform the real release.
      try {
        if (returned != null) returned.close();
      } finally {
        release.slots.detached.decrementAndGet();
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
      release.slots.detached.incrementAndGet();
      if (state.compareAndSet(RUNNING, ABANDONED)) {
        release.detached = true;
        log.warn("Native run passed its caller's deadline; continuing detached: {}",
            toCaller.getMessage());
        throw toCaller;
      }
      // The run thread set its outcome before it marked the task completed.
      release.slots.detached.decrementAndGet();
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
