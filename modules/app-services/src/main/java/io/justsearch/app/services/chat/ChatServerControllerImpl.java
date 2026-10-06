/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.Demand;
import io.justsearch.app.api.chat.DemandKind;
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
import io.justsearch.app.inference.chat.ProcessSelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.time.Duration;
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
 * </ul>
 */
public final class ChatServerControllerImpl
    implements ChatActuationRouter, ChatHolds, ChatBootOrder, ChatResidency, AutoCloseable {

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

  /** False until the first pressure unload set a dwell (monitor). */
  private boolean dwellSet;

  /** An unload step is queued or running (monitor). */
  private boolean unloadInFlight;

  /** An unload waits for the next idle edge, a reply being in progress (monitor). */
  private boolean deferred;

  /**
   * The pressure a person loaded chat anyway under, or null (monitor). While the reported pressure
   * stays this signal nothing unloads again; a new pressure edge clears it (reconciliation v2).
   */
  private PressureSignal forcedUnder;

  /** Background demands waiting for a load they never start (monitor); null while none. */
  private CompletableFuture<Void> nextLoad;

  /** The generation-edge subscription that resumes a deferred unload; null without one. */
  private final AutoCloseable edgeSubscription;

  /** The Engine's controller: Spec A's 30 s AFTER_IDLE drain bound and 45 s boot-order gate. */
  public ChatServerControllerImpl(EngineExecutorRegistry executors, ChatActuator actuator) {
    this(executors, actuator, ChatActuationRouter.DRAIN_BOUND, new ProcessSelfTestLauncher(),
        BOOT_GATE_TIMEOUT);
  }

  ChatServerControllerImpl(
      EngineExecutorRegistry executors, ChatActuator actuator, Duration drainBound) {
    this(executors, actuator, drainBound, new ProcessSelfTestLauncher(), BOOT_GATE_TIMEOUT);
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
    Objects.requireNonNull(executors, "executors");
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
    this.edgeSubscription = activity == null ? null : activity.onEdge(this::onGenerationEdge);
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
        Pending next;
        Runnable replay = null;
        synchronized (monitor) {
          while (queue.isEmpty() && desired.terminal() == null && replay == null) {
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
          next = replay != null ? null : queue.poll();
          if (next == null && replay == null) return;
          current = next;
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
    ChatActuator.DrainHold drained = null;
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
    OpenHold hold = new OpenHold(kind, owner, java.time.Instant.now());
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
      pressure = signal;
      // A new pressure edge ends a person's "load anyway" (reconciliation v2).
      if (previous != signal && forcedUnder != null && forcedUnder != signal) forcedUnder = null;
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
    Profile previous = this.profile;
    this.profile = profile;
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
   * chat for a game (Spec E §2 UNRESOLVED row and Q8). VDU passes read {@link #bulkPressure()}.
   */
  private PressureSignal effectivePressure() {
    PressureSignal signal = pressure;
    return signal == PressureSignal.GAME && !gameUnloadsChat(profile) ? PressureSignal.NONE : signal;
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
      case GAME -> RefusalReason.GAME_MODE;
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

  private void onGenerationEdge(boolean generating, long epoch) {
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
    if (Thread.currentThread() == controllerThread) return;
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
      if (forcedUnder == signal) return;
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
   * Settles an unload step. A step refused because a lease opened meanwhile defers to the next
   * idle edge; one refused by an AUX lease (which makes no edge) is tried again on the next
   * pressure report, which Spec E's sampler repeats. Nothing polls.
   */
  private void unloadEnded(StopDetail detail, long epoch, Object result, Throwable failure) {
    synchronized (monitor) {
      unloadInFlight = false;
      if (failure != null) {
        LOG.warn("Chat residency unload failed; chat stays as it was", failure);
        return;
      }
      if (result == ChatActuator.ResidencyUnload.UNLOADED) {
        if (latchEpoch == epoch) {
          latchLocked(detail, true);
          LOG.info("Chat unloaded ({}); a demand reloads it after {} s with the pressure gone",
              detail.token(), RELOAD_DWELL.toSeconds());
        }
        return;
      }
      if (result == ChatActuator.ResidencyUnload.BUSY) deferred = true;
    }
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
      if (!gpuStart || forcedUnder == signal) return null;
      // CH7: a GPU start under pressure is refused into UNLOADED; no dwell, nothing was loaded.
      detail = unloadDetail(signal);
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
        Duration retry = dwellLeft.compareTo(PRESSURE_RETRY_AFTER) > 0 ? dwellLeft
            : PRESSURE_RETRY_AFTER;
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
      waiters = clearLatchLocked();
      deferred = false;
      // Nothing unloads again until the pressure changes (reconciliation v2).
      forcedUnder = signal == PressureSignal.NONE ? null : signal;
      reload = queueLocked(actuator.activation(
          new Activation(true, TransitionReason.RESIDENCY_RELOAD)), superseded);
    }
    for (Pending replaced : superseded) supersede(replaced, reload.version);
    return reloadStage(reload, waiters);
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
    RuntimeException stopFailure = null;
    try {
      // The fence is lock-independent: a launch the step in progress makes after it fails.
      actuator.terminalStop(budget);
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
