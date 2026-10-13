/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import io.justsearch.app.api.chat.ChatDeviceLoss;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.Demand;
import io.justsearch.app.api.chat.DemandKind;
import io.justsearch.app.api.chat.DeviceLostEvidence;
import io.justsearch.app.api.chat.DeviceLostRecovery;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.chat.GenerationActivity;
import io.justsearch.app.api.chat.Hold;
import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.api.chat.SelfTestSession;
import io.justsearch.app.api.chat.VduHold;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatBootOrder;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.ChatResidency;
import io.justsearch.app.inference.chat.SelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single owner of every start, stop and relaunch of the managed chat server (Spec A step 2,
 * ADR-0050). It replaces {@code RuntimeReconciler} as the guarded single writer
 * (governance/runtime-state.v1.json).
 *
 * <p>One registered, non-daemon platform thread named {@value #THREAD_NAME} runs every actuation the
 * lifecycle manager describes, one at a time (Spec A §3.5):
 *
 * <ul>
 *   <li>Every request publishes a new immutable {@link DesiredInputs} version.
 *   <li><b>Coalescing.</b> A waiting step with a coalescing key is replaced by a later step with the
 *       same key; a step that drains first is re-evaluated after its drain, so A→B→C during a drain
 *       launches C only. Each replaced caller is told it was superseded.
 *   <li><b>No reentrancy.</b> A request made from the controller thread throws {@link
 *       IllegalStateException}. The controller holds no lock of its own while a step runs, and it
 *       drains with no lifecycle lock held.
 *   <li><b>Terminal.</b> {@link #shutdown} makes the controller terminal first: it refuses every
 *       waiting and later step, stops the server for good through the actuator's fence, interrupts
 *       the step in progress and retires the thread.
 *   <li><b>Holds</b> ({@link ChatHolds}, Spec A steps 5 and 6). A self-test session, a VDU hold, a
 *       running hold and a convergence freeze replace the reconciler's procedures. While one is
 *       open a forwarded activation is not acted on; the holder's own steps run through the same
 *       queue. A self-test candidate is launched on the controller thread with the main server
 *       stopped, so the two never hold the GPU together.
 *   <li><b>Boot order</b> ({@link ChatBootOrder}, Spec A step 8). Once armed, the first GPU
 *       activation after an Engine start waits (BOOT_WAIT) until the query encoders report ready
 *       or {@link #BOOT_GATE_TIMEOUT} passes (the controller thread watches the deadline); then the
 *       forwarder is asked to send its intent again.
 *   <li><b>Residency</b> ({@link ChatResidency}, Spec A step 9). Under external GPU pressure an idle
 *       GPU server this Engine started stops and stays UNLOADED (the latch) until a human or agent
 *       demand reloads it with the pressure gone and the {@link #RELOAD_DWELL} after the unload
 *       elapsed, or a person loads it anyway. A reply in progress is never cut: the unload waits
 *       for the next idle edge. While latched, a forwarded activation does not load.
 *   <li><b>Device loss and resume</b> ({@link ChatDeviceLoss}, Spec A step 10). A lost GPU
 *       interrupts the step in progress; within the limit the Engine's own ledger allows (at most
 *       one restart in 10 minutes and three in an hour) the controller stops for good with {@code
 *       DEVICE_LOST} and asks the Engine to restart; otherwise, or when the limit cannot be read,
 *       chat is FAILED with {@code device_lost_limit}. A resume restarts the timers in full and
 *       probes a serving server before it is trusted again.
 * </ul>
 */
public final class ChatServerControllerImpl
    implements ChatActuationRouter, ChatHolds, ChatBootOrder, ChatResidency, ChatDeviceLoss,
        AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ChatServerControllerImpl.class);

  /** The controller thread's name (Spec A §3.5). */
  public static final String THREAD_NAME = "chat-controller";

  /** Spec A §6.2: the boot-order gate never holds chat back longer than this. */
  public static final Duration BOOT_GATE_TIMEOUT = Duration.ofSeconds(45);

  private static final String EXECUTOR_NAME = "head.chat-controller";
  private static final Duration CLOSE_BUDGET = Duration.ofSeconds(12);

  /** The boot-order gate's life: never armed, closed until ready or expiry, then open for good. */
  private enum BootGate { UNARMED, CLOSED, OPEN }

  /**
   * The controller's desired-state snapshot. Immutable; every request publishes a new version.
   *
   * @param version increases by one with every accepted request and with shutdown
   * @param terminal why the controller stopped for good, or {@code null} while it accepts steps
   */
  public record DesiredInputs(long version, StopReason terminal) {}

  private static final class Pending {
    private ChatActuation<?> step;
    private final long version;
    private final CompletableFuture<Object> result = new CompletableFuture<>();

    private Pending(ChatActuation<?> step, long version) {
      this.step = step;
      this.version = version;
    }
  }

  private final ChatActuator actuator;
  private final Duration drainBound;
  private final SelfTestLauncher selfTestLauncher;
  private final EngineExecutorRegistry.Registration executorOwner;
  private final ExecutorService executor;

  private final Object monitor = new Object();
  private final ArrayDeque<Pending> queue = new ArrayDeque<>();
  private DesiredInputs desired = new DesiredInputs(0, null);
  private Pending current;
  private boolean loopEnded;
  private boolean retired;
  private volatile Thread controllerThread;

  /** Open holds and sessions (guarded by {@link #monitor}). */
  private final List<OpenHold> holds = new ArrayList<>();

  /**
   * Orders hold edges with their delivery to {@link #listeners}. Taken only by threads that open or
   * close a hold, never by the controller thread, and never while waiting for a step.
   */
  private final Object holdEdges = new Object();

  private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

  private final Duration bootGateTimeout;

  /** The boot-order gate (guarded by {@link #monitor}). */
  private BootGate bootGate = BootGate.UNARMED;

  /** When a closed gate expires, on the monotonic clock (guarded by {@link #monitor}). */
  private long bootGateDeadlineNanos;

  /** An activation is waiting behind the closed gate (guarded by {@link #monitor}). */
  private boolean bootWaiting;

  /** Sends the activation intent again once the gate opens (guarded by {@link #monitor}). */
  private Runnable bootReplay;

  // ---- residency (Spec A step 9) ----

  /** The monotonic clock of the residency dwell; tests drive it by hand. */
  private final LongSupplier nanoTime;

  /** The pressure last reported (Spec E feeds it; the dev trigger too). */
  private volatile PressureSignal pressure = PressureSignal.NONE;

  /** The machine's co-residence profile; UNRESOLVED until a resolver reports it. */
  private volatile Profile profile = Profile.UNRESOLVED;

  /** Why chat is unloaded for another GPU user, or null: the latch (guarded by {@link #monitor}). */
  private StopDetail unloadedDetail;

  /** Counts latch changes, so a stale observation never clears a newer latch (monitor). */
  private long latchEpoch;

  /** When a reload may start after a pressure unload, on {@link #nanoTime} (monitor). */
  private long dwellUntilNanos;

  /** The unloaded residency episode owns a dwell; cleared when its latch clears (monitor). */
  private boolean dwellSet;

  /** An unload step is queued or running (monitor). */
  private boolean unloadInFlight;

  /** An unload waits for the next idle edge or for the last open lease to close (monitor). */
  private boolean deferred;

  /** A one-shot "no lease open" call is registered for the deferred unload (monitor). */
  private boolean leaseWatch;

  /** A no-lease notification asks the controller loop to recheck residency (monitor). */
  private boolean residencyRecheckPending;

  /**
   * The effective pressure a person overrode, or null (monitor). It covers this level and lower
   * levels until a hysteresis-confirmed clear or a more severe effective level is reported.
   */
  private PressureSignal forcedUnder;

  /** Effective pressure behind the latest queued residency unload (monitor). */
  private PressureSignal lastUnloadPressure;

  /** Background demands waiting for a load they never start (monitor); null while none. */
  private CompletableFuture<Void> nextLoad;

  /** The generation-edge subscription that resumes a deferred unload; null without one. */
  private final AutoCloseable edgeSubscription;

  // ---- device loss and resume (Spec A step 10) ----

  /** Spec A §7.3: at most one device-lost restart in this window ... */
  static final Duration DEVICE_LOST_SHORT_WINDOW = Duration.ofMinutes(10);

  /** ... and at most {@link #DEVICE_LOST_HOURLY_LIMIT} in this one. */
  static final Duration DEVICE_LOST_LONG_WINDOW = Duration.ofHours(1);

  static final int DEVICE_LOST_HOURLY_LIMIT = 3;

  /** The ledger of earlier device-lost restarts and the Engine's restart path. */
  private final DeviceLostRecovery deviceLostRecovery;

  /** The clock the ledger's timestamps are compared with. */
  private final Clock wallClock;

  /** Device-lost evidence the controller thread has yet to act on (monitor); null while none. */
  private DeviceLostEvidence deviceLost;

  /** The controller thread is acting on a device loss (monitor). */
  private boolean deviceLostHandling;

  /** A resume probe is to run (monitor). */
  private boolean resumeProbePending;

  /**
   * The Engine's controller: Spec A's 30 s AFTER_IDLE drain bound and 45 s boot-order gate, and
   * self-test candidates launched by {@code selfTestLauncher} (through ChildControl, Spec B step 6).
   */
  public ChatServerControllerImpl(
      EngineExecutorRegistry executors, ChatActuator actuator, SelfTestLauncher selfTestLauncher) {
    this(executors, actuator, selfTestLauncher, DeviceLostRecovery.UNAVAILABLE);
  }

  /** A controller with no self-test launcher: every run fails without launching anything. */
  ChatServerControllerImpl(
      EngineExecutorRegistry executors, ChatActuator actuator, Duration drainBound) {
    this(executors, actuator, drainBound,
        spec -> SelfTestRun.failed("no self-test launcher is installed"));
  }

  ChatServerControllerImpl(EngineExecutorRegistry executors, ChatActuator actuator,
      Duration drainBound, SelfTestLauncher selfTestLauncher) {
    this(executors, actuator, drainBound, selfTestLauncher, BOOT_GATE_TIMEOUT);
  }

  ChatServerControllerImpl(EngineExecutorRegistry executors, ChatActuator actuator,
      Duration drainBound, SelfTestLauncher selfTestLauncher, Duration bootGateTimeout) {
    this(executors, actuator, drainBound, selfTestLauncher, bootGateTimeout, System::nanoTime);
  }

  ChatServerControllerImpl(EngineExecutorRegistry executors, ChatActuator actuator,
      Duration drainBound, SelfTestLauncher selfTestLauncher, Duration bootGateTimeout,
      LongSupplier nanoTime) {
    this(executors, actuator, drainBound, selfTestLauncher, bootGateTimeout, nanoTime,
        DeviceLostRecovery.UNAVAILABLE, Clock.systemUTC());
  }

  /**
   * The Engine's controller with its device-lost restart path (Spec A step 10): {@code recovery}
   * reads the Engine's earlier device-lost restarts and asks for the next.
   */
  public ChatServerControllerImpl(EngineExecutorRegistry executors, ChatActuator actuator,
      SelfTestLauncher selfTestLauncher, DeviceLostRecovery recovery) {
    this(executors, actuator, DRAIN_BOUND, selfTestLauncher, BOOT_GATE_TIMEOUT,
        System::nanoTime, recovery, Clock.systemUTC());
  }

  ChatServerControllerImpl(EngineExecutorRegistry executors, ChatActuator actuator,
      Duration drainBound, SelfTestLauncher selfTestLauncher, Duration bootGateTimeout,
      LongSupplier nanoTime, DeviceLostRecovery recovery, Clock wallClock) {
    Objects.requireNonNull(executors, "executors");
    this.deviceLostRecovery = Objects.requireNonNull(recovery, "recovery");
    this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    this.actuator = Objects.requireNonNull(actuator, "actuator");
    this.drainBound = Objects.requireNonNull(drainBound, "drainBound");
    this.selfTestLauncher = Objects.requireNonNull(selfTestLauncher, "selfTestLauncher");
    this.bootGateTimeout = Objects.requireNonNull(bootGateTimeout, "bootGateTimeout");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    var limits = executors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    this.executorOwner = executors.register(new EngineExecutorSpec(EXECUTOR_NAME,
        EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.PLATFORM,
        1, limits.maxQueue(), 1));
    try {
      this.executor = executorOwner.open(Thread.ofPlatform().daemon(false).name(THREAD_NAME).factory());
      executor.execute(this::loop);
    } catch (RuntimeException | Error failure) {
      try {
        executorOwner.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
    // A reply in progress defers an unload to the next idle edge (Spec A T22).
    GenerationActivity activity = actuator.generationActivity();
    this.edgeSubscription = activity == null ? null
        : activity.onEdge((generating, ignoredEpoch) -> onGenerationEdge(generating));
  }

  /** The current desired-state snapshot. */
  public DesiredInputs desiredInputs() {
    synchronized (monitor) {
      return desired;
    }
  }

  // ==================== Requests ====================

  @Override
  @SuppressWarnings("unchecked")
  public <T> T route(ChatActuation<T> step) throws Exception {
    Objects.requireNonNull(step, "step");
    return (T) await(enqueue(step));
  }

  /** Queues the step and returns at once; never blocks on the controller thread. */
  @Override
  @SuppressWarnings("unchecked")
  public <T> CompletionStage<T> submit(ChatActuation<T> step) {
    Objects.requireNonNull(step, "step");
    try {
      return enqueue(step).result.thenApply(value -> (T) value);
    } catch (RefusedException refused) {
      return CompletableFuture.failedFuture(refused);
    } catch (IllegalStateException fromController) {
      return CompletableFuture.failedFuture(fromController);
    }
  }

  /**
   * Publishes the activation as a coalescing step and returns at once; the stage completes when
   * the step ran, was superseded by a later activation or was refused. While a hold or self-test
   * session is open the activation is not acted on: the stage completes superseded at once, and
   * the forwarder sends the intent again when the last hold closes ({@link Listener#kindClosed}).
   *
   * <p>A GPU activation that meets the closed boot-order gate (T2) also completes superseded at
   * once (BOOT_WAIT). When the gate opens (T3) the controller asks the forwarder to send its intent
   * again, so a chat switched off meanwhile is not started.
   */
  @Override
  public CompletionStage<Void> setActivation(Activation activation) {
    Objects.requireNonNull(activation, "activation");
    if (activation.active()) {
      // Spec A §2.2: the residency latch ranks above boot order. A latched chat is not loaded by
      // a forwarded activation (reconciliation F6); a GPU start under pressure is refused into
      // UNLOADED (CH7). Only a demand or "load anyway" loads it again.
      CompletionStage<Void> residency = residencyActivation(activation);
      if (residency != null) return residency;
      if (bootGateClosed() && actuator.gpuChat()) {
        synchronized (monitor) {
          if (bootGate == BootGate.CLOSED && desired.terminal() == null && !loopEnded
              && holds.isEmpty()) {
            if (!bootWaiting) LOG.info("Chat waits for the query encoders before its first GPU start");
            bootWaiting = true;
            desired = new DesiredInputs(desired.version() + 1, null);
            monitor.notifyAll();
            return CompletableFuture.failedFuture(new SupersededException(activation
                + " waits for the query encoders (boot order); the intent is sent again when"
                + " they are ready or the gate expires"));
          }
        }
      }
    } else {
      CompletableFuture<Void> waiters;
      synchronized (monitor) {
        bootWaiting = false;
        // Chat switched off is OFF, not unloaded; nothing waits for a reload any more.
        waiters = clearLatchLocked();
        deferred = false;
        forcedUnder = null;
      }
      if (waiters != null) {
        waiters.completeExceptionally(
            new ChatRefusal(RefusalReason.OFF, java.util.Optional.empty(), false));
      }
    }
    Pending pending;
    try {
      pending = enqueue(actuator.activation(activation), true);
    } catch (RefusedException refused) {
      return CompletableFuture.failedFuture(refused);
    }
    if (pending == null) {
      return CompletableFuture.failedFuture(new SupersededException(
          activation + " was not acted on: the chat server is held (" + heldKinds() + ")"));
    }
    return pending.result.thenApply(ignored -> null);
  }

  private Pending enqueue(ChatActuation<?> step) throws RefusedException {
    return enqueue(step, false);
  }

  /** Returns {@code null} without queueing when {@code heldOut} and a hold is open. */
  private Pending enqueue(ChatActuation<?> step, boolean heldOut) throws RefusedException {
    if (Thread.currentThread() == controllerThread) {
      throw new IllegalStateException(
          "a chat actuation was requested from the chat controller thread (" + step + ")");
    }
    Pending pending;
    List<Pending> superseded = new ArrayList<>();
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded) {
        throw new RefusedException("the chat controller has stopped ("
            + (desired.terminal() == null ? "closed" : desired.terminal()) + "); " + step + " refused");
      }
      if (heldOut && !holds.isEmpty()) return null;
      pending = queueLocked(step, superseded);
    }
    for (Pending replaced : superseded) supersede(replaced, pending.version);
    return pending;
  }

  /** Publishes a new version and queues the step, replacing waiting steps with its key. */
  private Pending queueLocked(ChatActuation<?> step, List<Pending> superseded) {
    desired = new DesiredInputs(desired.version() + 1, null);
    Pending pending = new Pending(step, desired.version());
    if (step.coalesceKey() != null) {
      for (Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
        Pending waiting = it.next();
        if (step.coalesceKey().equals(waiting.step.coalesceKey())) {
          it.remove();
          pending.step = pending.step.absorb(waiting.step);
          superseded.add(waiting);
        }
      }
    }
    queue.add(pending);
    monitor.notifyAll();
    return pending;
  }

  private Object await(Pending pending) throws Exception {
    boolean interrupted = false;
    try {
      for (;;) {
        try {
          return pending.result.get();
        } catch (InterruptedException interrupt) {
          interrupted = true;
          synchronized (monitor) {
            if (queue.remove(pending)) {
              pending.result.completeExceptionally(interrupt);
              throw interrupt;
            }
            // The caller used to run the step itself; its interrupt still reaches the step.
            if (current == pending && controllerThread != null) controllerThread.interrupt();
          }
        } catch (ExecutionException failed) {
          Throwable cause = failed.getCause();
          if (cause instanceof Exception exception) throw exception;
          if (cause instanceof Error error) throw error;
          throw failed;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static void supersede(Pending replaced, long byVersion) {
    replaced.result.completeExceptionally(new SupersededException(
        replaced.step + " (version " + replaced.version + ") was superseded by version " + byVersion));
  }

  // ==================== The controller thread ====================

  private void loop() {
    controllerThread = Thread.currentThread();
    try {
      for (;;) {
        Pending next = null;
        Runnable replay = null;
        DeviceLostEvidence lost = null;
        boolean probe = false;
        boolean recheckResidency = false;
        synchronized (monitor) {
          while (queue.isEmpty() && desired.terminal() == null && replay == null
              && deviceLost == null && !resumeProbePending && !residencyRecheckPending) {
            if (bootGate != BootGate.CLOSED) {
              monitor.wait();
              continue;
            }
            long remaining = bootGateDeadlineNanos - System.nanoTime();
            if (remaining <= 0) {
              replay = openBootGateLocked("the boot-order gate expired");
              continue;
            }
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
          }
          if (replay == null && desired.terminal() == null && deviceLost != null) {
            // A lost device goes before every waiting step (Spec A §7.3).
            lost = deviceLost;
            deviceLost = null;
            deviceLostHandling = true;
          } else if (replay == null && desired.terminal() == null && resumeProbePending) {
            resumeProbePending = false;
            probe = true;
          } else if (replay == null && desired.terminal() == null && residencyRecheckPending) {
            residencyRecheckPending = false;
            recheckResidency = true;
          } else if (replay == null) {
            next = queue.poll();
            if (next == null) return;
            current = next;
          }
        }
        if (recheckResidency) {
          try {
            evaluateResidency();
          } catch (RuntimeException | Error failure) {
            survive(failure);
            LOG.warn("Chat residency recheck failed; the controller keeps serving", failure);
          }
          continue;
        }
        if (lost != null) {
          next = deviceLostStep(lost);
          if (next == null) continue;
        } else if (probe) {
          next = resumeProbeStep();
          if (next == null) continue;
        }
        if (replay != null) {
          try {
            runReplay(replay);
          } catch (RuntimeException | Error failure) {
            survive(failure);
            LOG.warn("Chat controller: the boot-gate replay failed; the controller keeps serving",
                failure);
          }
          continue;
        }
        try {
          runStep(next);
          if (next.step.kind() == ChatActuation.Kind.RESUME_PROBE) resumeProbeEnded(next);
          if (next.step.kind() == ChatActuation.Kind.DEVICE_LOST) deviceLostEnded(next);
        } catch (RuntimeException | Error failure) {
          // An unexpected failure belongs to the step that raised it, never to the controller: the
          // step's caller gets it, and the next step runs (a dead loop would refuse every later step).
          survive(failure);
          LOG.warn("Chat controller: {} failed unexpectedly; the step fails and the controller keeps"
              + " serving", next.step, failure);
          next.result.completeExceptionally(failure);
        } finally {
          synchronized (monitor) {
            current = null;
            if (next.step.kind() == ChatActuation.Kind.DEVICE_LOST) deviceLostHandling = false;
            // A forwarded caller interrupt belongs to the finished step, never to the next one.
            if (desired.terminal() == null) Thread.interrupted();
            monitor.notifyAll();
          }
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      List<Pending> refused;
      synchronized (monitor) {
        loopEnded = true;
        current = null;
        refused = new ArrayList<>(queue);
        queue.clear();
        monitor.notifyAll();
      }
      for (Pending pending : refused) refuse(pending);
    }
  }

  /** A failure the JVM cannot continue after ends the loop as before; any other is survived. */
  private static void survive(Throwable failure) {
    if (failure instanceof VirtualMachineError fatal) throw fatal;
  }

  private void runStep(Pending pending) {
    ChatActuator.DrainHold drained;
    if (pending.step.drainsFirst()) {
      try {
        drained = actuator.drain(pending.step.drainPolicy(drainBound));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        complete(pending, () -> pending.step.drainFailed(interrupted));
        return;
      } catch (IllegalStateException unavailable) {
        complete(pending, () -> pending.step.drainFailed(unavailable));
        return;
      }
      Pending runner = pending;
      Pending replaced = null;
      synchronized (monitor) {
        if (desired.terminal() != null) {
          drained.close();
          replaced = pending;
          runner = null;
        } else if (pending.step.coalesceKey() != null) {
          // Re-evaluate after the drain: a newer step with the same key replaces this one and
          // inherits the drained admission.
          for (Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
            Pending newer = it.next();
            if (pending.step.coalesceKey().equals(newer.step.coalesceKey())) {
              it.remove();
              newer.step = newer.step.absorb(pending.step);
              replaced = pending;
              runner = newer;
              current = newer;
              break;
            }
          }
        }
      }
      if (runner == null) {
        refuse(replaced);
        return;
      }
      if (replaced != null) supersede(replaced, runner.version);
      execute(runner, drained);
      return;
    }
    execute(pending, null);
  }

  private void execute(Pending pending, ChatActuator.DrainHold drained) {
    complete(pending, () -> actuator.execute(pending.step, drained));
  }

  private interface StepResult {
    Object get() throws Exception;
  }

  private static void complete(Pending pending, StepResult result) {
    try {
      pending.result.complete(result.get());
    } catch (Exception | Error failure) {
      pending.result.completeExceptionally(failure);
    }
  }

  private void refuse(Pending pending) {
    StopReason terminal;
    synchronized (monitor) {
      terminal = desired.terminal();
    }
    pending.result.completeExceptionally(new RefusedException(pending.step
        + " was refused: the chat controller has stopped ("
        + (terminal == null ? "closed" : terminal) + ")"));
  }

  // ==================== Holds and self-test sessions (Spec A steps 5 and 6) ====================

  /**
   * What the controller's holds see when one is requested: whether the controller is terminal,
   * which kinds are open and whether a chat failure is latched.
   */
  record HoldState(boolean terminal, java.util.Set<Kind> open, boolean failureLatched,
      RefusalReason pressure) {
    HoldState {
      open = java.util.Set.copyOf(open);
      if (pressure != null && pressure != RefusalReason.GPU_BUSY
          && pressure != RefusalReason.GAME_MODE) {
        throw new IllegalArgumentException("pressure is GPU_BUSY, GAME_MODE or null: " + pressure);
      }
    }

    /** No external GPU pressure. */
    HoldState(boolean terminal, java.util.Set<Kind> open, boolean failureLatched) {
      this(terminal, open, failureLatched, null);
    }
  }

  /**
   * Whether a hold of {@code requested} is admitted in {@code state}, and if not, why (reconciliation
   * F11 and F17; Spec A §2.2 precedence terminal > freeze > self-test > VDU > running hold). A
   * freeze is always admitted and never acts, and holds taken inside a freeze still act (the install
   * smoke test runs inside the acquisition freeze). The failure latch is evaluated before the VDU and
   * running holds. A self-test needs every other actuating hold closed. A VDU hold applies only
   * while there is no external GPU pressure (Spec A step 9, T40): under pressure it is refused with
   * the pressure's reason. A running hold ranks above the residency unload and is admitted.
   *
   * @return {@code null} when admitted
   */
  static RefusalReason admission(Kind requested, HoldState state) {
    Objects.requireNonNull(requested, "requested");
    if (requested == Kind.FREEZE) return null;
    if (state.terminal()) return RefusalReason.ENGINE_STOPPING;
    if (state.open().contains(Kind.SELF_TEST)) {
      return requested == Kind.SELF_TEST ? RefusalReason.BUSY : RefusalReason.SELF_TEST;
    }
    return switch (requested) {
      case SELF_TEST -> state.open().contains(Kind.VDU) || state.open().contains(Kind.RUNNING)
          ? RefusalReason.BUSY : null;
      case VDU -> state.failureLatched() ? RefusalReason.FAILED
          : state.pressure() != null ? state.pressure()
          : state.open().contains(Kind.VDU) ? RefusalReason.BUSY : null;
      case RUNNING -> state.failureLatched() ? RefusalReason.FAILED : null;
      case FREEZE -> null;
    };
  }

  @Override
  public VduHold holdVdu(String owner) throws ChatRefusal {
    OpenHold hold = open(Kind.VDU, owner);
    try {
      routeHeld(actuator.activation(new Activation(true, TransitionReason.VDU_ENTER)), true);
      routeHeld(actuator.vduEnter(), false);
    } catch (ChatRefusal refused) {
      // A failed entry may have restarted the server already; return it to its configuration.
      try {
        routeHeld(actuator.vduExit(), false, true);
      } catch (ChatRefusal cleanup) {
        refused.addSuppressed(cleanup);
      }
      release(hold);
      throw refused;
    }
    return new HeldVdu(hold);
  }

  @Override
  public Hold holdRunning(String owner) throws ChatRefusal {
    OpenHold hold = open(Kind.RUNNING, owner);
    try {
      routeHeld(actuator.activation(new Activation(true, TransitionReason.VDU_ENTER)), true);
    } catch (ChatRefusal refused) {
      release(hold);
      throw refused;
    }
    return new Held(hold);
  }

  @Override
  public Hold freezeConvergence(String owner) {
    try {
      return new Held(open(Kind.FREEZE, owner));
    } catch (ChatRefusal impossible) {
      throw new IllegalStateException("a freeze is always admitted", impossible);
    }
  }

  @Override
  public SelfTestSession<SelfTestSpec, SelfTestRun> beginSelfTest(String owner) throws ChatRefusal {
    OpenHold hold = open(Kind.SELF_TEST, owner);
    try {
      routeHeld(actuator.selfTestStop(), false);
    } catch (ChatRefusal refused) {
      try {
        routeHeld(actuator.selfTestEnd(), false, true);
      } catch (ChatRefusal cleanup) {
        refused.addSuppressed(cleanup);
      }
      release(hold);
      throw refused;
    }
    return new Session(hold);
  }

  /**
   * Also false while chat is unloaded for pressure or pressure is reported (Spec A U13): a VDU batch
   * would load the model the unload just freed.
   */
  @Override
  public boolean vduMayStart() {
    if (bulkPressure() != PressureSignal.NONE) return false;
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded || !holds.isEmpty()) return false;
      if (unloadedDetail != null || unloadInFlight) return false;
    }
    return actuator.failureLatch() == null && !actuator.serverRunning();
  }

  @Override
  public AutoCloseable addListener(Listener listener) {
    Objects.requireNonNull(listener, "listener");
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  /** The open holds, oldest first. */
  public List<OpenHold> openHolds() {
    synchronized (monitor) {
      return List.copyOf(holds);
    }
  }

  private String heldKinds() {
    synchronized (monitor) {
      return holds.stream().map(h -> h.kind() + ":" + h.owner()).toList().toString();
    }
  }

  private OpenHold open(Kind kind, String owner) throws ChatRefusal {
    Objects.requireNonNull(owner, "owner");
    if (Thread.currentThread() == controllerThread) {
      throw new IllegalStateException("a chat hold was requested from the chat controller thread");
    }
    OpenHold hold = new OpenHold(kind, owner, Instant.now());
    boolean failureLatched = actuator.failureLatch() != null;
    RefusalReason pressureReason = pressureRefusal(bulkPressure());
    CompletableFuture<Void> reloadWaiters = null;
    synchronized (holdEdges) {
      boolean first;
      synchronized (monitor) {
        java.util.EnumSet<Kind> open = java.util.EnumSet.noneOf(Kind.class);
        for (OpenHold existing : holds) open.add(existing.kind());
        RefusalReason refusal = admission(kind, new HoldState(
            desired.terminal() != null || loopEnded, open, failureLatched, pressureReason));
        if (refusal != null) {
          throw new ChatRefusal(refusal, java.util.Optional.empty(), false);
        }
        first = !open.contains(kind);
        holds.add(hold);
        // A hold that starts the server ranks above the residency latch (Spec A §2.2).
        if (kind == Kind.VDU || kind == Kind.RUNNING) {
          if (unloadedDetail != null) {
            LOG.info("Chat residency latch cleared: a {} hold starts the server", kind);
          }
          reloadWaiters = clearLatchLocked();
          deferred = false;
        }
      }
      if (reloadWaiters != null) {
        // Background demands wait for READY through the hold's own start; they ask again.
        reloadWaiters.complete(null);
      }
      LOG.info("Chat hold opened: {} by {}", kind, owner);
      if (first) {
        for (Listener listener : listeners) {
          try {
            listener.kindOpened(hold);
          } catch (RuntimeException failure) {
            LOG.warn("Chat hold listener failed on open", failure);
          }
        }
      }
    }
    return hold;
  }

  private void release(OpenHold hold) {
    synchronized (holdEdges) {
      boolean last;
      boolean noneOpen;
      synchronized (monitor) {
        if (!holds.remove(hold)) return;
        last = holds.stream().noneMatch(h -> h.kind() == hold.kind());
        noneOpen = holds.isEmpty();
      }
      LOG.info("Chat hold closed: {} by {}", hold.kind(), hold.owner());
      if (last) {
        for (Listener listener : listeners) {
          try {
            listener.kindClosed(hold.kind(), noneOpen);
          } catch (RuntimeException failure) {
            LOG.warn("Chat hold listener failed on close", failure);
          }
        }
      }
    }
    // Pressure that arrived while the hold kept the server is acted on now (Spec A T40).
    if (noneOpenAfterRelease()) evaluateResidency();
  }

  private boolean noneOpenAfterRelease() {
    synchronized (monitor) {
      return holds.isEmpty();
    }
  }

  /**
   * Runs a hold's own step, which the held-out gate lets through, and maps its failure onto a
   * refusal. {@code supersededIsDone}: a later step with the same key replaced it (another hold's
   * identical start), so the server is being brought where this step would have brought it.
   */
  private <T> T routeHeld(ChatActuation<T> step, boolean supersededIsDone) throws ChatRefusal {
    return routeHeld(step, supersededIsDone, false);
  }

  /**
   * {@code uninterruptible}: the step must run even when the caller is interrupted (a hold's exit
   * and a session's end; skipping them would leave the server held). The interrupt is restored.
   */
  @SuppressWarnings("unchecked")
  private <T> T routeHeld(ChatActuation<T> step, boolean supersededIsDone, boolean uninterruptible)
      throws ChatRefusal {
    try {
      return uninterruptible ? (T) awaitUninterruptibly(enqueue(step)) : route(step);
    } catch (SupersededException superseded) {
      if (supersededIsDone) return null;
      throw refusal(RefusalReason.BUSY, superseded);
    } catch (RefusedException refused) {
      throw refusal(RefusalReason.ENGINE_STOPPING, refused);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw refusal(RefusalReason.BUSY, interrupted);
    } catch (io.justsearch.app.api.ModeTransitionException transition) {
      throw refusal(switch (transition.reason()) {
        case ALREADY_TRANSITIONING, INTERRUPTED -> RefusalReason.BUSY;
        default -> RefusalReason.FAILED;
      }, transition);
    } catch (Exception failure) {
      throw refusal(RefusalReason.FAILED, failure);
    }
  }

  private static Object awaitUninterruptibly(Pending pending) throws Exception {
    boolean interrupted = false;
    try {
      for (;;) {
        try {
          return pending.result.get();
        } catch (InterruptedException interrupt) {
          interrupted = true;
        } catch (ExecutionException failed) {
          Throwable cause = failed.getCause();
          if (cause instanceof Exception exception) throw exception;
          if (cause instanceof Error error) throw error;
          throw failed;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static ChatRefusal refusal(RefusalReason reason, Throwable cause) {
    ChatRefusal refusal = new ChatRefusal(reason, java.util.Optional.empty(), false);
    refusal.initCause(cause);
    return refusal;
  }

  /** A running or freeze hold; {@code closing} runs once, on the first close. */
  private class Held implements Hold {
    final OpenHold hold;
    private final Runnable closing;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
        new java.util.concurrent.atomic.AtomicBoolean();

    Held(OpenHold hold) {
      this(hold, () -> release(hold));
    }

    Held(OpenHold hold, Runnable closing) {
      this.hold = hold;
      this.closing = closing;
    }

    @Override
    public String owner() {
      return hold.owner();
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) closing.run();
    }
  }

  /** Closes a VDU hold: restores the server's configuration, then releases the hold. */
  private void exitVduAndRelease(OpenHold hold) {
    try {
      routeHeld(actuator.vduExit(), false, true);
    } catch (ChatRefusal refused) {
      if (refused.reason() != RefusalReason.ENGINE_STOPPING) {
        throw new IllegalStateException(
            "Failed to exit VDU mode; the server may keep its vision-safe configuration", refused);
      }
    } finally {
      release(hold);
    }
  }

  /** A VDU hold: closing it restores the server's configuration, then releases the hold. */
  private final class HeldVdu extends Held implements VduHold {
    HeldVdu(OpenHold hold) {
      super(hold, () -> exitVduAndRelease(hold));
    }

    /**
     * True once the controller stops for good, or while external GPU pressure is reported (Spec A
     * T40): the holder ends its batch and closes the hold, and the idle server then unloads.
     */
    @Override
    public boolean interruptRequested() {
      if (bulkPressure() != PressureSignal.NONE) return true;
      synchronized (monitor) {
        return desired.terminal() != null || loopEnded;
      }
    }
  }

  /** A self-test session: the main server stays stopped until it closes. */
  private final class Session implements SelfTestSession<SelfTestSpec, SelfTestRun> {
    private final OpenHold hold;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
        new java.util.concurrent.atomic.AtomicBoolean();

    Session(OpenHold hold) {
      this.hold = hold;
    }

    /** Launches, probes and stops one candidate on the controller thread (Spec A T38). */
    @Override
    public SelfTestRun run(SelfTestSpec spec) {
      Objects.requireNonNull(spec, "spec");
      if (closed.get()) throw new IllegalStateException("the self-test session is closed");
      try {
        return routeHeld(ChatActuation.of(ChatActuation.Kind.SELF_TEST_RUN,
            drained -> selfTestLauncher.run(spec)), false);
      } catch (ChatRefusal refused) {
        return SelfTestRun.failed("self-test not run: " + refused.reason());
      }
    }

    /** Lets the main server start again, then the intent's forwarder replans (Spec A T39). */
    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      try {
        routeHeld(actuator.selfTestEnd(), false, true);
      } catch (ChatRefusal refused) {
        if (refused.reason() != RefusalReason.ENGINE_STOPPING) {
          LOG.warn("Ending the self-test session failed: {}", refused.reason(), refused);
        }
      } finally {
        release(hold);
      }
    }
  }

  // ==================== Residency (Spec A step 9) ====================

  /**
   * Spec A T21/T23: after a pressure unload, no demand reloads chat for this long, so a GPU user
   * that comes and goes does not make the model load and unload in a loop. Not persisted (CH7).
   * The reload token bucket of Spec A §4.3 is not built: reconciliation v2 cut it.
   */
  public static final Duration RELOAD_DWELL = Duration.ofMinutes(2);

  /** When an agent refused under pressure may usefully ask again (Spec A T25). */
  public static final Duration PRESSURE_RETRY_AFTER = Duration.ofSeconds(30);

  @Override
  public void reportPressure(PressureSignal signal) {
    Objects.requireNonNull(signal, "signal");
    PressureSignal previous;
    synchronized (monitor) {
      previous = pressure;
      PressureSignal previousEffective = effectivePressure();
      pressure = signal;
      reconcileOverrideLocked(previousEffective);
    }
    if (previous != signal) LOG.info("Chat GPU pressure: {} -> {}", previous, signal);
    if (signal != PressureSignal.NONE) evaluateResidency();
  }

  @Override
  public PressureSignal reportedPressure() {
    return pressure;
  }

  @Override
  public void setProfile(Profile profile) {
    Objects.requireNonNull(profile, "profile");
    Profile previous;
    synchronized (monitor) {
      previous = this.profile;
      PressureSignal previousEffective = effectivePressure();
      this.profile = profile;
      if (previous != profile) reconcileOverrideLocked(previousEffective);
    }
    if (previous != profile) {
      LOG.info("Chat co-residence profile: {} -> {}", previous, profile);
      evaluateResidency();
    }
  }

  @Override
  public java.util.Optional<StopDetail> unloaded() {
    synchronized (monitor) {
      return java.util.Optional.ofNullable(unloadedDetail);
    }
  }

  /**
   * The pressure residency acts on (unloads, demand refusals, the CH7 start refusal). Another
   * application's memory need always counts. A game alone counts only on a profile without a fit
   * margin, P12 or P8: on P16_PLUS it fits next to chat (Spec A O4, reconciliation F18/P3), and while
   * the profile is UNRESOLVED or NO_GPU the controller keeps today's behaviour, which never unloaded
   * chat for a game (Spec E §2 UNRESOLVED row and Q8). A game never hides another application's
   * memory need: with both reported, the game outranks it where a game counts and the memory need
   * acts on its own elsewhere (Spec A C8/O4). VDU passes read {@link #bulkPressure()}.
   */
  private PressureSignal effectivePressure() {
    PressureSignal signal = pressure;
    return switch (signal) {
      case GAME -> gameUnloadsChat(profile) ? PressureSignal.GAME : PressureSignal.NONE;
      case GAME_AND_EXTERNAL_MEMORY ->
          gameUnloadsChat(profile) ? PressureSignal.GAME : PressureSignal.EXTERNAL_MEMORY;
      case NONE, EXTERNAL_MEMORY -> signal;
    };
  }

  /** Both the override and residency decisions use the profile's effective pressure. */
  private void reconcileOverrideLocked(PressureSignal previous) {
    PressureSignal signal = effectivePressure();
    // A clear already observed before load-anyway cannot revoke the newly recorded cause.
    if (signal == PressureSignal.NONE ? previous != PressureSignal.NONE
        : !overrideCoversLocked(signal)) forcedUnder = null;
  }

  private boolean overrideCoversLocked(PressureSignal signal) {
    return forcedUnder != null && (forcedUnder == PressureSignal.GAME || forcedUnder == signal);
  }

  /** A game alone unloads chat only where the profile has no fit margin (Spec A O4). */
  static boolean gameUnloadsChat(Profile profile) {
    return profile == Profile.P12 || profile == Profile.P8;
  }

  /**
   * The pressure GPU bulk work reads: any reported pressure, whatever the profile, because a VDU
   * pass is bulk work that Switch A does not start during a game or under external memory use
   * (Spec A U12/U13, reconciliation v2 §2).
   */
  private PressureSignal bulkPressure() {
    return pressure;
  }

  private static RefusalReason pressureRefusal(PressureSignal signal) {
    return switch (signal) {
      case NONE -> null;
      case EXTERNAL_MEMORY -> RefusalReason.GPU_BUSY;
      case GAME, GAME_AND_EXTERNAL_MEMORY -> RefusalReason.GAME_MODE;
    };
  }

  private static StopDetail unloadDetail(PressureSignal signal) {
    return signal == PressureSignal.GAME ? StopDetail.GAME_MODE : StopDetail.GPU_BUSY;
  }

  private static RefusalReason detailRefusal(StopDetail detail) {
    return detail == StopDetail.GAME_MODE ? RefusalReason.GAME_MODE : RefusalReason.GPU_BUSY;
  }

  /** A person: only the app's own clients (reconciliation F8/CH1). */
  private static boolean isPerson(EngineContext context) {
    return context.clientKind() == EngineContext.ClientKind.WEBVIEW
        || context.clientKind() == EngineContext.ClientKind.PAIRED_DEVICE;
  }

  /**
   * Clears the latch (guarded by {@link #monitor}); an in-flight unload that ends later no longer
   * latches.
   *
   * @return the background waiters to settle outside the monitor, or null
   */
  private CompletableFuture<Void> clearLatchLocked() {
    unloadedDetail = null;
    dwellSet = false;
    latchEpoch++;
    CompletableFuture<Void> waiters = nextLoad;
    nextLoad = null;
    return waiters;
  }

  /** Latches the unload (guarded by {@link #monitor}); {@code dwell} starts the reload dwell. */
  private void latchLocked(StopDetail detail, boolean dwell) {
    unloadedDetail = detail;
    latchEpoch++;
    deferred = false;
    forcedUnder = null;
    if (dwell) {
      dwellUntilNanos = nanoTime.getAsLong() + RELOAD_DWELL.toNanos();
      dwellSet = true;
    }
  }

  /** The dwell left, or zero (guarded by {@link #monitor}). */
  private Duration dwellLeftLocked() {
    if (!dwellSet) return Duration.ZERO;
    long left = dwellUntilNanos - nanoTime.getAsLong();
    return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
  }

  /**
   * Whether the server runs. A latch that a start outside the residency path made stale (an
   * operator retry, a legacy switch) is dropped: the server it says is unloaded runs again.
   */
  private boolean observeRunning() {
    long epoch;
    synchronized (monitor) {
      epoch = latchEpoch;
    }
    boolean running = actuator.serverRunning();
    if (running) {
      CompletableFuture<Void> waiters = null;
      synchronized (monitor) {
        if (unloadedDetail != null && latchEpoch == epoch) {
          LOG.info("Chat residency latch cleared: the server was started");
          waiters = clearLatchLocked();
        }
      }
      if (waiters != null) waiters.complete(null);
    }
    return running;
  }

  private void onGenerationEdge(boolean generating) {
    if (generating) return;
    boolean resume;
    synchronized (monitor) {
      resume = deferred;
    }
    if (resume) evaluateResidency();
  }

  /**
   * Spec A T21/T22: under pressure, an idle GPU server this Engine runs is unloaded; while a reply
   * is in progress the unload waits for the next idle edge. Holds keep the server (a VDU hold sees
   * {@link VduHold#interruptRequested}); releasing the last one evaluates again.
   */
  private void evaluateResidency() {
    PressureSignal signal = effectivePressure();
    if (signal == PressureSignal.NONE) return;
    // The loop also evaluates a deferred no-lease notification here. It queues the unload as
    // usual, so every drain and stop still runs through the controller's actuation queue.
    boolean running = observeRunning();
    boolean gpu = running && actuator.gpuChat();
    GenerationActivity activity = actuator.generationActivity();
    boolean generating = activity != null && activity.generating();
    StopDetail detail = unloadDetail(signal);
    Pending pending;
    long epoch;
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded) return;
      if (unloadedDetail != null || unloadInFlight) return;
      if (overrideCoversLocked(signal)) return;
      if (!holds.isEmpty()) return;
      // CPU chat and a stopped server leave the graphics memory alone.
      if (!running || !gpu) return;
      if (generating) {
        if (!deferred) LOG.info("Chat unload for {} waits for the reply in progress", detail.token());
        deferred = true;
        return;
      }
      deferred = false;
      unloadInFlight = true;
      lastUnloadPressure = signal;
      epoch = latchEpoch;
      List<Pending> superseded = new ArrayList<>();
      pending = queueLocked(actuator.residencyUnload(detail,
          () -> effectivePressure() != PressureSignal.NONE), superseded);
      // The unload step has no coalescing key, so it supersedes nothing.
    }
    LOG.info("Chat unloads for another GPU user ({})", detail.token());
    var unused = pending.result.whenComplete(
        (result, failure) -> unloadEnded(detail, epoch, result, failure));
  }

  /**
   * Settles an unload step. A step refused because a lease was open defers until no lease is open:
   * the next idle edge, or for a tokenize, template or query-assist lease (which makes no edge) the
   * moment the last lease closes, as the generation activity reports it once. The sampler reports
   * pressure only on change, so nothing else would try again. Nothing polls.
   */
  private void unloadEnded(StopDetail detail, long epoch, Object result, Throwable failure) {
    if (settleUnload(detail, epoch, result, failure)) watchForNoLease();
  }

  /** @return true when the unload waits for the open leases to close (guarded by the monitor) */
  private boolean settleUnload(StopDetail detail, long epoch, Object result, Throwable failure) {
    synchronized (monitor) {
      unloadInFlight = false;
      if (failure != null) {
        LOG.warn("Chat residency unload failed; chat stays as it was", failure);
        return false;
      }
      if (result == ChatActuator.ResidencyUnload.UNLOADED) {
        if (latchEpoch == epoch) {
          latchLocked(detail, true);
          LOG.info("Chat unloaded ({}); a demand reloads it after {} s with the pressure gone",
              detail.token(), RELOAD_DWELL.toSeconds());
        }
        return false;
      }
      if (result != ChatActuator.ResidencyUnload.BUSY) return false;
      deferred = true;
      if (leaseWatch) return false;
      leaseWatch = true;
      return true;
    }
  }

  /** Asks the generation activity for one call once no lease is open, to resume a deferred unload. */
  private void watchForNoLease() {
    GenerationActivity activity = actuator.generationActivity();
    if (activity == null) {
      synchronized (monitor) {
        leaseWatch = false;
      }
      return;
    }
    activity.whenNoLeaseOpen(() -> {
      synchronized (monitor) {
        leaseWatch = false;
        // Delivery can fall back to the closing thread under callback queue pressure. Only
        // wake our loop here: never inspect the actuator or perform a drain on that thread.
        if (!deferred || desired.terminal() != null || loopEnded) return;
        residencyRecheckPending = true;
        monitor.notifyAll();
      }
    });
  }

  /**
   * The residency gate of an activation (see {@link #setActivation}).
   *
   * @return the superseded stage when the activation is not acted on, or null to go on
   */
  private CompletionStage<Void> residencyActivation(Activation activation) {
    PressureSignal signal = effectivePressure();
    boolean running = observeRunning();
    boolean gpuStart = !running && signal != PressureSignal.NONE && actuator.gpuChat();
    Pending pending;
    StopDetail detail;
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded || !holds.isEmpty()) return null;
      if (unloadedDetail != null || unloadInFlight) {
        return CompletableFuture.failedFuture(new SupersededException(activation
            + " was not acted on: chat is unloaded for another GPU user ("
            + (unloadedDetail == null ? "unloading" : unloadedDetail.token())
            + "); a chat request loads it again"));
      }
      if (!gpuStart || overrideCoversLocked(signal)) return null;
      // CH7: a GPU start under pressure is refused into UNLOADED; no dwell, nothing was loaded.
      detail = unloadDetail(signal);
      lastUnloadPressure = signal;
      latchLocked(detail, false);
      unloadInFlight = true;
      List<Pending> superseded = new ArrayList<>();
      pending = queueLocked(actuator.residencyUnload(detail, () -> true), superseded);
    }
    LOG.info("Chat does not load: another GPU user needs the memory ({})", detail.token());
    var unused = pending.result.whenComplete((result, failure) -> {
      synchronized (monitor) {
        unloadInFlight = false;
      }
      if (failure != null) LOG.warn("Recording chat as unloaded failed", failure);
    });
    return CompletableFuture.failedFuture(new SupersededException(activation
        + " was not acted on: another GPU user needs the memory (" + detail.token()
        + "); a chat request loads it again"));
  }

  @Override
  public CompletionStage<Void> demand(Demand demand) throws ChatRefusal {
    Objects.requireNonNull(demand, "demand");
    DemandKind kind = demand.kind();
    boolean person = isPerson(demand.context());
    // Only a person's chat may be forced; anything else that says HUMAN_CHAT is an agent (F8).
    if (kind == DemandKind.HUMAN_CHAT && !person) kind = DemandKind.AGENT_CHAT;
    boolean human = kind == DemandKind.HUMAN_CHAT;
    observeRunning();
    PressureSignal signal = effectivePressure();
    Pending reload;
    CompletableFuture<Void> waiters;
    List<Pending> superseded = new ArrayList<>();
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded) {
        throw new ChatRefusal(RefusalReason.ENGINE_STOPPING, java.util.Optional.empty(), false);
      }
      // An unload queued or running is treated as done: a reload queued now runs after it.
      if (unloadedDetail == null && !unloadInFlight) return CompletableFuture.completedFuture(null);
      Duration dwellLeft = dwellLeftLocked();
      RefusalReason pressureReason = pressureRefusal(signal);
      switch (kind) {
        case ASSIST, AUX -> throw new ChatRefusal(
            pressureReason != null ? pressureReason
                : dwellLeft.isPositive() ? RefusalReason.RELOAD_BACKOFF
                : detailRefusal(unloadedDetail),
            java.util.Optional.empty(), false);
        case BACKGROUND -> {
          if (nextLoad == null) nextLoad = new CompletableFuture<>();
          return nextLoad.copy();
        }
        default -> { }
      }
      if (pressureReason != null) {
        Duration retry = reloadRetryLocked(signal);
        throw new ChatRefusal(pressureReason, java.util.Optional.of(retry), human);
      }
      if (dwellLeft.isPositive()) {
        throw new ChatRefusal(RefusalReason.RELOAD_BACKOFF, java.util.Optional.of(dwellLeft), human);
      }
      if (!holds.isEmpty()) throw heldRefusalLocked();
      LOG.info("Chat reloads for a {} request after a residency unload", kind);
      waiters = clearLatchLocked();
      reload = queueLocked(actuator.activation(
          new Activation(true, TransitionReason.RESIDENCY_RELOAD)), superseded);
    }
    for (Pending replaced : superseded) supersede(replaced, reload.version);
    return reloadStage(reload, waiters);
  }

  @Override
  public CompletionStage<Void> loadAnyway(EngineContext human) throws ChatRefusal {
    Objects.requireNonNull(human, "human");
    if (!isPerson(human)) {
      // Only a person may override pressure (reconciliation F8/CH1).
      throw new ChatRefusal(RefusalReason.REFUSED, java.util.Optional.empty(), false);
    }
    if (actuator.failureLatch() != null) {
      throw new ChatRefusal(RefusalReason.FAILED, java.util.Optional.empty(), false);
    }
    observeRunning();
    PressureSignal signal = effectivePressure();
    Pending reload;
    CompletableFuture<Void> waiters;
    List<Pending> superseded = new ArrayList<>();
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded) {
        throw new ChatRefusal(RefusalReason.ENGINE_STOPPING, java.util.Optional.empty(), false);
      }
      if (unloadedDetail == null && !unloadInFlight) return CompletableFuture.completedFuture(null);
      if (!holds.isEmpty()) throw heldRefusalLocked();
      LOG.info("Chat loads anyway at a person's request (pressure {})", signal);
      // The unload can itself clear the sampled pressure. Remember its cause before clearing
      // the latch, so returning pressure after the reload does not dismiss the person's choice.
      forcedUnder = signal == PressureSignal.NONE ? lastUnloadPressure : signal;
      waiters = clearLatchLocked();
      deferred = false;
      reload = queueLocked(actuator.activation(
          new Activation(true, TransitionReason.RESIDENCY_RELOAD)), superseded);
    }
    for (Pending replaced : superseded) supersede(replaced, reload.version);
    return reloadStage(reload, waiters);
  }

  @Override
  public java.util.Optional<ChatResidency.ReloadStatus> reloadStatus() {
    synchronized (monitor) {
      if (unloadedDetail == null && !unloadInFlight) return java.util.Optional.empty();
      PressureSignal signal = effectivePressure();
      Duration retry = reloadRetryLocked(signal);
      return java.util.Optional.of(new ChatResidency.ReloadStatus(
          signal == PressureSignal.NONE && retry.isZero() && holds.isEmpty(),
          retry.isPositive() ? java.util.Optional.of(retry) : java.util.Optional.empty()));
    }
  }

  /** The ordinary chat refusal and status use the same dwell and pressure retry bound. */
  private Duration reloadRetryLocked(PressureSignal signal) {
    Duration dwell = dwellLeftLocked();
    return signal != PressureSignal.NONE && dwell.compareTo(PRESSURE_RETRY_AFTER) < 0
        ? PRESSURE_RETRY_AFTER : dwell;
  }

  /** A self-test keeps the server stopped; any other open hold owns the server meanwhile. */
  private ChatRefusal heldRefusalLocked() {
    boolean selfTest = holds.stream().anyMatch(h -> h.kind() == Kind.SELF_TEST);
    return new ChatRefusal(selfTest ? RefusalReason.SELF_TEST : RefusalReason.BUSY,
        java.util.Optional.empty(), false);
  }

  /** The reload's stage; background demands waiting for a load end with it. */
  private static CompletionStage<Void> reloadStage(
      Pending reload, CompletableFuture<Void> waiters) {
    CompletableFuture<Void> loaded = reload.result.thenApply(ignored -> (Void) null);
    if (waiters != null) {
      var unused = loaded.whenComplete((ignored, failure) -> {
        if (failure == null) {
          waiters.complete(null);
        } else {
          waiters.completeExceptionally(failure);
        }
      });
    }
    return loaded;
  }

  // ==================== Boot order (Spec A step 8) ====================

  @Override
  public void armBootOrderGate(Runnable replay) {
    Objects.requireNonNull(replay, "replay");
    synchronized (monitor) {
      if (bootGate != BootGate.UNARMED) return;
      bootGate = BootGate.CLOSED;
      bootReplay = replay;
      bootGateDeadlineNanos = System.nanoTime() + bootGateTimeout.toNanos();
      monitor.notifyAll();
    }
    LOG.info("Chat boot-order gate armed for {} s", bootGateTimeout.toSeconds());
  }

  @Override
  public void reportQueryEncodersReady() {
    Runnable replay;
    synchronized (monitor) {
      if (bootGate == BootGate.UNARMED) {
        bootGate = BootGate.OPEN;
        return;
      }
      replay = openBootGateLocked("the query encoders are ready");
    }
    if (replay != null) runReplay(replay);
  }

  private boolean bootGateClosed() {
    synchronized (monitor) {
      return bootGate == BootGate.CLOSED;
    }
  }

  /**
   * Opens a closed gate for good (T3).
   *
   * @return the replay to run, outside the monitor, when an activation was waiting and the
   *     controller still accepts steps; otherwise null
   */
  private Runnable openBootGateLocked(String why) {
    if (bootGate != BootGate.CLOSED) return null;
    bootGate = BootGate.OPEN;
    boolean waiting = bootWaiting;
    bootWaiting = false;
    monitor.notifyAll();
    LOG.info("Chat boot-order gate opened: {}", why);
    return waiting && desired.terminal() == null && !loopEnded ? bootReplay : null;
  }

  private static void runReplay(Runnable replay) {
    try {
      replay.run();
    } catch (RuntimeException failure) {
      LOG.warn("Sending the chat activation again after the boot-order gate failed", failure);
    }
  }

  // ==================== Device loss and resume (Spec A step 10) ====================

  @Override
  public void reportDeviceLost(DeviceLostEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded) {
        LOG.info("GPU device lost ({} {}) after the chat controller stopped; nothing to do",
            evidence.source(), evidence.code());
        return;
      }
      if (deviceLost != null || deviceLostHandling) {
        LOG.info("GPU device lost ({} {}) while a device loss is being handled; ignored",
            evidence.source(), evidence.code());
        return;
      }
      deviceLost = evidence;
      desired = new DesiredInputs(desired.version() + 1, null);
      // The step in progress may be waiting on the lost device; it ends now (Spec A T31).
      if (current != null && controllerThread != null
          && Thread.currentThread() != controllerThread) {
        controllerThread.interrupt();
      }
      monitor.notifyAll();
    }
    LOG.error("GPU device lost ({} {}); the chat controller acts on it", evidence.source(),
        evidence.code());
  }

  /**
   * Whether one more device-lost restart is within Spec A §7.3's limit: none in the last {@link
   * #DEVICE_LOST_SHORT_WINDOW} and fewer than {@link #DEVICE_LOST_HOURLY_LIMIT} in the last {@link
   * #DEVICE_LOST_LONG_WINDOW}. A record dated after {@code now} counts in both windows.
   */
  static boolean withinDeviceLostLimit(List<Instant> priorRestarts, Instant now) {
    Objects.requireNonNull(priorRestarts, "priorRestarts");
    Objects.requireNonNull(now, "now");
    Instant shortStart = now.minus(DEVICE_LOST_SHORT_WINDOW);
    Instant longStart = now.minus(DEVICE_LOST_LONG_WINDOW);
    long inShort = priorRestarts.stream().filter(at -> at.isAfter(shortStart)).count();
    long inLong = priorRestarts.stream().filter(at -> at.isAfter(longStart)).count();
    return inShort == 0 && inLong < DEVICE_LOST_HOURLY_LIMIT;
  }

  /**
   * Acts on a device loss on the controller thread (Spec A §7.3, reconciliation F10), in the
   * spec's order. The returned step stops the server now with {@code DEVICE_LOST}; only then does
   * it read the ledger ({@link #restartWithinLimit}). Within the limit the step publishes nothing
   * and the controller then stops for good with {@code DEVICE_LOST} and asks the Engine to restart
   * ({@link #deviceLostEnded}). Otherwise, and whenever the limit cannot be read (fail closed), the
   * step latches FAILED with {@code device_lost_limit} and chat stays operable for a retry.
   */
  private Pending deviceLostStep(DeviceLostEvidence evidence) {
    LOG.error("GPU device lost ({} {}): chat stops now", evidence.source(), evidence.code());
    Pending stop;
    try {
      stop = internalStep(actuator.deviceLostStop(() -> restartWithinLimit(evidence)));
    } catch (RuntimeException unavailable) {
      LOG.error("Chat cannot be stopped for the lost device", unavailable);
      stop = null;
    }
    if (stop == null) {
      synchronized (monitor) {
        deviceLostHandling = false;
      }
    }
    return stop;
  }

  /** Reads the device-lost limit after the stop; false when over it or unreadable (F10). */
  private boolean restartWithinLimit(DeviceLostEvidence evidence) {
    boolean restart;
    String why;
    try {
      List<Instant> prior = deviceLostRecovery.priorRestarts();
      restart = withinDeviceLostLimit(prior, wallClock.instant());
      why = restart ? "within the device-lost limit" : "over the device-lost limit";
    } catch (IOException | RuntimeException unreadable) {
      restart = false;
      why = "the device-lost limit cannot be read (" + unreadable.getMessage() + ")";
    }
    if (restart) {
      LOG.error("GPU device lost ({} {}): {}; the Engine restarts", evidence.source(),
          evidence.code(), why);
    } else {
      LOG.error("GPU device lost ({} {}): {}; no Engine restart, chat is FAILED"
          + " (device_lost_limit) until an operator retry", evidence.source(), evidence.code(), why);
    }
    return restart;
  }

  /**
   * After the device-lost step: when it chose the Engine restart, chat stops for good with {@code
   * DEVICE_LOST} (the server is already stopped) and the restart is requested.
   */
  private void deviceLostEnded(Pending stop) {
    if (!Boolean.TRUE.equals(stop.result.getNow(null))) return;
    try {
      shutdown(StopReason.DEVICE_LOST, CLOSE_BUDGET);
    } catch (RuntimeException stopFailure) {
      LOG.warn("Stopping chat for the lost device failed; the Engine restart stops it", stopFailure);
    }
    try {
      deviceLostRecovery.requestRestart();
    } catch (RuntimeException failure) {
      LOG.error("The Engine restart for the lost device could not be requested", failure);
    }
  }

  private Pending resumeProbeStep() {
    try {
      return internalStep(actuator.resumeProbe());
    } catch (RuntimeException unavailable) {
      LOG.warn("The resume probe could not be made", unavailable);
      return null;
    }
  }

  /** A step the controller makes itself, run like a queued one; null once terminal. */
  private Pending internalStep(ChatActuation<?> step) {
    synchronized (monitor) {
      if (desired.terminal() != null) return null;
      Pending pending = new Pending(step, desired.version());
      current = pending;
      return pending;
    }
  }

  @Override
  public void reportResume() {
    Runnable replan = null;
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded) return;
      // Spec A §7.4: suspended time is never counted; the timers restart in full.
      // A dwell belongs to the unloaded residency episode. Its deadline may have passed
      // during sleep, so elapsed time cannot decide whether resume must restart it.
      if (dwellSet) {
        dwellUntilNanos = nanoTime.getAsLong() + RELOAD_DWELL.toNanos();
      }
      if (bootGate == BootGate.CLOSED) {
        bootGateDeadlineNanos = System.nanoTime() + bootGateTimeout.toNanos();
        monitor.notifyAll();
      }
    }
    LOG.info("The machine resumed: chat timers restart in full and a serving server is probed");
    // The server's own timers reset now, even while a step (a start waiting for health) runs, and
    // before the probe can run.
    FailedCode latched = actuator.failureLatch();
    actuator.noteResume();
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded) return;
      resumeProbePending = true;
      desired = new DesiredInputs(desired.version() + 1, null);
      monitor.notifyAll();
    }
    if (latched != null) {
      // T34: the resume cleared the FAILED latch; the intent is sent again (replan).
      LOG.info("Chat FAILED ({}) is cleared by the resume", latched);
      synchronized (monitor) {
        replan = bootReplay;
      }
    }
    if (replan != null) runReplay(replan);
  }

  /** A probe that found the device lost reports it (Spec A §7.4 to §7.3). */
  private void resumeProbeEnded(Pending probe) {
    Object result = probe.result.getNow(null);
    if (result instanceof ChatActuator.ResumeProbe ended
        && ended.outcome() == ChatActuator.ResumeOutcome.DEVICE_LOST) {
      reportDeviceLost(new DeviceLostEvidence(
          DeviceLostEvidence.Source.RESUME_PROBE, ended.deviceLostCode().orElseThrow()));
    }
  }

  // ==================== Terminal ====================

  @Override
  public void shutdown(StopReason reason, Duration budget) {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(budget, "budget");
    long deadline = System.nanoTime() + budget.toNanos();
    List<Pending> refused;
    CompletableFuture<Void> waiters;
    synchronized (monitor) {
      if (desired.terminal() == null) {
        desired = new DesiredInputs(desired.version() + 1, reason);
        LOG.info("Chat controller is terminal ({})", reason);
      }
      refused = new ArrayList<>(queue);
      queue.clear();
      waiters = nextLoad;
      nextLoad = null;
      monitor.notifyAll();
    }
    for (Pending pending : refused) refuse(pending);
    if (waiters != null) {
      waiters.completeExceptionally(
          new ChatRefusal(RefusalReason.ENGINE_STOPPING, java.util.Optional.empty(), false));
    }
    closeEdgeSubscription();
    StopReason terminalReason;
    synchronized (monitor) {
      terminalReason = desired.terminal();
    }
    RuntimeException stopFailure = null;
    try {
      // The fence is lock-independent: a launch the step in progress makes after it fails. The
      // first terminal reason is the one the server's stop records.
      actuator.terminalStop(terminalReason, budget);
    } catch (RuntimeException failure) {
      stopFailure = failure;
    }
    synchronized (monitor) {
      if (current != null && controllerThread != null
          && Thread.currentThread() != controllerThread) {
        controllerThread.interrupt();
      }
    }
    retire(deadline);
    if (stopFailure != null) throw stopFailure;
  }

  private void retire(long deadline) {
    if (Thread.currentThread() == controllerThread) return;
    try {
      synchronized (monitor) {
        while (!loopEnded) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            LOG.warn("Chat controller step did not finish within the shutdown budget");
            break;
          }
          TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        }
        if (retired) return;
        retired = true;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    executor.shutdown();
    executorOwner.close();
  }

  private void closeEdgeSubscription() {
    if (edgeSubscription == null) return;
    try {
      edgeSubscription.close();
    } catch (Exception failure) {
      LOG.debug("Closing the generation-edge subscription failed", failure);
    }
  }

  /** Stops for good with {@link StopReason#QUIT} unless already terminal. */
  @Override
  public void close() {
    shutdown(StopReason.QUIT, CLOSE_BUDGET);
  }
}
