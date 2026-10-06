/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.Hold;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.api.chat.SelfTestSession;
import io.justsearch.app.api.chat.VduHold;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.SelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.inference.telemetry.TransitionReason;
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
 * </ul>
 */
public final class ChatServerControllerImpl
    implements ChatActuationRouter, ChatHolds, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ChatServerControllerImpl.class);

  /** The controller thread's name (Spec A §3.5). */
  public static final String THREAD_NAME = "chat-controller";

  private static final String EXECUTOR_NAME = "head.chat-controller";
  private static final Duration CLOSE_BUDGET = Duration.ofSeconds(12);

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

  /**
   * The Engine's controller: Spec A's 30 s AFTER_IDLE drain bound, and self-test candidates
   * launched by {@code selfTestLauncher} (through ChildControl, Spec B step 6).
   */
  public ChatServerControllerImpl(
      EngineExecutorRegistry executors, ChatActuator actuator, SelfTestLauncher selfTestLauncher) {
    this(executors, actuator, ChatActuationRouter.DRAIN_BOUND, selfTestLauncher);
  }

  /** A controller with no self-test launcher: every run fails without launching anything. */
  ChatServerControllerImpl(
      EngineExecutorRegistry executors, ChatActuator actuator, Duration drainBound) {
    this(executors, actuator, drainBound,
        spec -> SelfTestRun.failed("no self-test launcher is installed"));
  }

  ChatServerControllerImpl(EngineExecutorRegistry executors, ChatActuator actuator,
      Duration drainBound, SelfTestLauncher selfTestLauncher) {
    Objects.requireNonNull(executors, "executors");
    this.actuator = Objects.requireNonNull(actuator, "actuator");
    this.drainBound = Objects.requireNonNull(drainBound, "drainBound");
    this.selfTestLauncher = Objects.requireNonNull(selfTestLauncher, "selfTestLauncher");
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
   */
  @Override
  public CompletionStage<Void> setActivation(Activation activation) {
    Objects.requireNonNull(activation, "activation");
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
      desired = new DesiredInputs(desired.version() + 1, null);
      pending = new Pending(step, desired.version());
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
    }
    for (Pending replaced : superseded) supersede(replaced, pending.version);
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
        synchronized (monitor) {
          while (queue.isEmpty() && desired.terminal() == null) monitor.wait();
          next = queue.poll();
          if (next == null) return;
          current = next;
        }
        try {
          runStep(next);
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
  record HoldState(boolean terminal, java.util.Set<Kind> open, boolean failureLatched) {
    HoldState {
      open = java.util.Set.copyOf(open);
    }
  }

  /**
   * Whether a hold of {@code requested} is admitted in {@code state}, and if not, why (reconciliation
   * F11 and F17; Spec A §2.2 precedence terminal > freeze > self-test > VDU > running hold). A
   * freeze is always admitted and never acts, and holds taken inside a freeze still act (the install
   * smoke test runs inside the acquisition freeze). The failure latch is evaluated before the VDU and
   * running holds. A self-test needs every other actuating hold closed.
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

  @Override
  public boolean vduMayStart() {
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded || !holds.isEmpty()) return false;
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
    synchronized (holdEdges) {
      boolean first;
      synchronized (monitor) {
        java.util.EnumSet<Kind> open = java.util.EnumSet.noneOf(Kind.class);
        for (OpenHold existing : holds) open.add(existing.kind());
        RefusalReason refusal = admission(kind, new HoldState(
            desired.terminal() != null || loopEnded, open, actuator.failureLatch() != null));
        if (refusal != null) {
          throw new ChatRefusal(refusal, java.util.Optional.empty(), false);
        }
        first = !open.contains(kind);
        holds.add(hold);
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

    /** True once the controller stops for good. Residency pressure joins it with Spec A step 9. */
    @Override
    public boolean interruptRequested() {
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

  // ==================== Terminal ====================

  @Override
  public void shutdown(StopReason reason, Duration budget) {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(budget, "budget");
    long deadline = System.nanoTime() + budget.toNanos();
    List<Pending> refused;
    synchronized (monitor) {
      if (desired.terminal() == null) {
        desired = new DesiredInputs(desired.version() + 1, reason);
        LOG.info("Chat controller is terminal ({})", reason);
      }
      refused = new ArrayList<>(queue);
      queue.clear();
      monitor.notifyAll();
    }
    for (Pending pending : refused) refuse(pending);
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

  /** Stops for good with {@link StopReason#QUIT} unless already terminal. */
  @Override
  public void close() {
    shutdown(StopReason.QUIT, CLOSE_BUDGET);
  }
}
