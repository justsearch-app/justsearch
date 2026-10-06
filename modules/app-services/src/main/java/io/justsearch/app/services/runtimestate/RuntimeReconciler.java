/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.telemetry.TransitionReason;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards explicit runtime intent ({@link RuntimeSpec} ∧ policy) to the chat server's single
 * writer (tempdoc 737 §12a; Spec A step 2). One dedicated daemon thread + a level-triggered dirty
 * flag — <b>no forwarding work ever runs on a caller or listener thread.</b>
 *
 * <p>The chat controller ({@code io.justsearch.app.services.chat.ChatServerControllerImpl}) is the
 * guarded single writer. This class only publishes the desired activation to it through {@link
 * ActivationForwarder#setActivation} (Spec A §4.1/§4.2, reconciliation F6); the controller
 * serializes and coalesces that step with every other start, stop and relaunch.
 *
 * <h3>Re-entrancy contract (R2)</h3>
 *
 * {@code TransitionRunner} fires {@link ModeChangeListener}s <i>under its lock</i>, and a
 * re-entrant {@code run()} from a listener throws {@code IllegalStateException} ("Already
 * transitioning"). Therefore this reconciler's mode-change listener ONLY updates
 * {@link RuntimeStatus} + the {@link RuntimeGpuLease} mirror and marks the loop dirty.
 *
 * <h3>Forwarding scope</h3>
 *
 * Intent is forwarded on (a) boot ({@link #requestBootConvergence()}), (b) explicit spec writes
 * ({@link #specChanged()}) and (c) the last hold's end ({@link #endProcedure}), unless the physical owner
 * retains a failed activation for component recovery. An observed mode change is never forwarded:
 * a foreign flip only republishes status (Spec A step 2 deleted foreign-flip convergence and its
 * anti-flap guard, reconciliation F6), so nothing here reloads chat behind the controller's back.
 *
 * <h3>Procedures: the chat controller's holds</h3>
 *
 * A machine actor that needs the engine in a non-spec state takes a hold on the chat controller
 * (Spec A step 6: self-test session, VDU hold, running hold, convergence freeze), and the
 * controller drives the server for it. {@link #followHolds} mirrors those holds here as the
 * procedures they replaced ({@link #beginProcedure}/{@link #endProcedure}): the status overlay
 * shows them, forwarding is deferred while any is open, and when the <b>last</b> one ends the
 * reconciler forwards the then-current spec, returning the engine to it.
 */
public final class RuntimeReconciler implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RuntimeReconciler.class);

  private static final long BASE_BACKOFF_MS = 1_000L;
  private static final long MAX_BACKOFF_MS = 60_000L;

  /** Stop-primitive that may throw the same checked exception the switch primitives do. */
  @FunctionalInterface
  public interface DetachAction {
    void detach() throws ModeTransitionException;
  }

  /**
   * Reason-bearing engine switch (task 5), used only by the controller-less constructors (isolated
   * tests), which adapt it into an {@link ActivationForwarder}.
   */
  @FunctionalInterface
  public interface ReasonedSwitch {
    void switchTo(TransitionReason reason) throws ModeTransitionException;
  }

  /**
   * Where intent goes: the chat controller's {@code setActivation} (Spec A §4.1). Wired at the
   * composition root to {@code InferenceLifecycleManager.setActivation}.
   */
  @FunctionalInterface
  public interface ActivationForwarder {
    CompletionStage<Void> setActivation(ChatActuationRouter.Activation activation);
  }

  private final OnlineAiLifecycleControl control;
  private final Supplier<Mode> modeSupplier;
  private final BooleanSupplier externalAdoption;
  private final EnterprisePolicyService policy; // nullable
  private final RuntimeSpecStore specStore;
  private final RuntimeGpuLease lease;
  private final ActivationForwarder forwarder;
  private final BooleanSupplier componentRecoveryPending;

  private final AtomicReference<RuntimeStatus> status = new AtomicReference<>(RuntimeStatus.initial());
  private final AtomicLong specVersion = new AtomicLong(0);

  /**
   * Tempdoc 737 §12c Phase 2a: fired synchronously (on the calling thread, i.e. whatever thread
   * called {@link #specChanged()} — typically the settings-write thread) after every explicit
   * spec write, BEFORE convergence has necessarily run. This is the smallest correct mechanism for
   * a spec-aware projection (e.g. {@code InferenceCapabilityWiring}) that needs to re-derive on a
   * spec flip even when the observed engine mode does not change (e.g. chatEnabled flips off while
   * a VDU procedure holds the engine online for background work — no {@code ModeChangeListener}
   * fires because the mode never changes, but the derived capability must). Listeners re-derive
   * using their OWN combination of "current mode" (read live, not cached) + "freshly loaded spec"
   * (via {@link #currentSpec()}), so firing before convergence completes is correct, not stale —
   * the spec half of the derivation is authoritative the instant it is persisted.
   */
  private final List<Runnable> specChangeListeners = new CopyOnWriteArrayList<>();

  private final Object lock = new Object();
  private volatile boolean running = false;
  private boolean dirty = false; // guarded by lock
  private boolean convergePending = false; // guarded by lock — an EXPLICIT convergence is queued
  private TransitionReason pendingReason = TransitionReason.UNKNOWN; // guarded by lock
  private long retryAtMillis = 0; // guarded by lock
  private long backoffMillis = 0; // guarded by lock
  private boolean converging = false; // guarded by lock — a convergence pass is in flight
  private volatile TransitionReason lastConvergenceReason = null;

  // Procedure overlay (guarded by lock). One entry per active machine-actor procedure, keyed by
  // kind — multiple kinds (e.g. an ACTIVATION window overlapping a VDU_BATCH) may hold the engine at
  // once. Forwarding is deferred while this is non-empty; return-to-spec re-arms only when it
  // empties (tempdoc 737 §12a fix pack). Insertion-ordered so the status overlay can pick a
  // stable representative (the most-recently-begun distinct kind).
  private final java.util.Map<RuntimeStatus.ProcedureKind, RuntimeStatus.Procedure> activeProcedures =
      new java.util.LinkedHashMap<>();

  private Thread thread;
  private ModeChangeListener attachedListener;

  // The three controller-less constructors below have no production caller; only isolated tests
  // use them. They stay so earlier fail-before evidence remains reproducible on its before-states.
  // Spec A step 12 removes them.

  /**
   * @deprecated test-only adapter without a chat controller; Spec A step 12 removes it. Use the
   *     {@link ActivationForwarder} constructor.
   */
  @Deprecated
  public RuntimeReconciler(
      OnlineAiLifecycleControl control,
      Supplier<Mode> modeSupplier,
      BooleanSupplier externalAdoption,
      DetachAction detach,
      EnterprisePolicyService policy,
      RuntimeSpecStore specStore,
      RuntimeGpuLease lease) {
    this(control, modeSupplier, externalAdoption, detach, policy, specStore, lease, null, null);
  }

  /**
   * @deprecated test-only adapter without a chat controller; Spec A step 12 removes it. Use the
   *     {@link ActivationForwarder} constructor.
   */
  @Deprecated
  public RuntimeReconciler(
      OnlineAiLifecycleControl control,
      Supplier<Mode> modeSupplier,
      BooleanSupplier externalAdoption,
      DetachAction detach,
      EnterprisePolicyService policy,
      RuntimeSpecStore specStore,
      RuntimeGpuLease lease,
      ReasonedSwitch onlineSwitch,
      ReasonedSwitch indexingSwitch) {
    this(control, modeSupplier, externalAdoption, detach, policy, specStore, lease,
        onlineSwitch, indexingSwitch, () -> false);
  }

  /**
   * @deprecated test-only adapter without a chat controller; Spec A step 12 removes it. Use the
   *     {@link ActivationForwarder} constructor.
   */
  @Deprecated
  public RuntimeReconciler(
      OnlineAiLifecycleControl control,
      Supplier<Mode> modeSupplier,
      BooleanSupplier externalAdoption,
      DetachAction detach,
      EnterprisePolicyService policy,
      RuntimeSpecStore specStore,
      RuntimeGpuLease lease,
      ReasonedSwitch onlineSwitch,
      ReasonedSwitch indexingSwitch,
      BooleanSupplier componentRecoveryPending) {
    this(control, modeSupplier, externalAdoption, policy, specStore, lease,
        switchForwarder(control, externalAdoption, detach, onlineSwitch, indexingSwitch),
        componentRecoveryPending);
  }

  /**
   * The Engine's reconciler: every boot, spec and procedure request becomes one {@code
   * setActivation} on the chat controller.
   */
  public RuntimeReconciler(
      OnlineAiLifecycleControl control,
      Supplier<Mode> modeSupplier,
      BooleanSupplier externalAdoption,
      EnterprisePolicyService policy,
      RuntimeSpecStore specStore,
      RuntimeGpuLease lease,
      ActivationForwarder forwarder,
      BooleanSupplier componentRecoveryPending) {
    this.control = control;
    this.modeSupplier = modeSupplier;
    this.externalAdoption = externalAdoption;
    this.policy = policy;
    this.specStore = specStore;
    this.lease = lease;
    this.forwarder = java.util.Objects.requireNonNull(forwarder, "forwarder");
    this.componentRecoveryPending = java.util.Objects.requireNonNull(
        componentRecoveryPending, "componentRecoveryPending");
  }

  /**
   * Without a chat controller (isolated tests) an activation runs the given switch on the
   * forwarding thread: active starts, inactive detaches an adopted external server or stops.
   */
  private static ActivationForwarder switchForwarder(
      OnlineAiLifecycleControl control,
      BooleanSupplier externalAdoption,
      DetachAction detach,
      ReasonedSwitch onlineSwitch,
      ReasonedSwitch indexingSwitch) {
    return activation -> {
      try {
        if (activation.active()) {
          if (onlineSwitch != null) {
            onlineSwitch.switchTo(activation.reason());
          } else {
            control.switchToOnlineMode();
          }
        } else if (externalAdoption != null && externalAdoption.getAsBoolean()) {
          if (detach != null) {
            detach.detach();
          }
        } else if (indexingSwitch != null) {
          indexingSwitch.switchTo(activation.reason());
        } else {
          control.switchToIndexingMode();
        }
        return CompletableFuture.completedFuture(null);
      } catch (ModeTransitionException | RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    };
  }

  /**
   * Attach the mode-change listener (mirror-initial-state-then-forward — the
   * {@code standalone-capability-stays-stuck} medicine) and start the reconciler thread.
   * Idempotent-ish: calling twice is a programming error and logs a warning.
   */
  public synchronized void start() {
    if (running) {
      log.warn("RuntimeReconciler already started");
      return;
    }
    running = true;

    // Mirror initial state synchronously BEFORE forwarding transitions.
    Mode initial = safeMode();
    lease.mirrorFromMode(initial);
    status.set(RuntimeStatus.derive(initial, safeExternal(), lease.holder(), null, specVersion.get(), Instant.now()));

    if (control != null) {
      attachedListener =
          (from, to) -> {
            // Runs UNDER the runner's lock — do NOT converge here. Fast, non-reentrant only.
            lease.mirrorFromMode(to);
            markStatusDirty();
          };
      control.addModeChangeListener(attachedListener);
    }

    thread = new Thread(this::loop, "runtime-reconciler");
    thread.setDaemon(true);
    thread.start();
  }

  /** Boot-time first reconcile toward spec (AUTO_START intent). */
  public void requestBootConvergence() {
    requestConvergence(TransitionReason.AUTO_START);
  }

  /** A spec write happened — bump the observed spec version and converge (USER_SWITCH intent). */
  public void specChanged() {
    specVersion.incrementAndGet();
    requestConvergence(TransitionReason.USER_SWITCH);
    notifySpecChangeListeners();
  }

  /**
   * Register a listener notified after every {@link #specChanged()} call (tempdoc 737 §12c Phase
   * 2a — see the {@link #specChangeListeners} javadoc for the firing contract). Best-effort: a
   * throwing listener is logged and does not affect other listeners or convergence.
   */
  public void addSpecChangeListener(Runnable listener) {
    if (listener != null) {
      specChangeListeners.add(listener);
    }
  }

  private void notifySpecChangeListeners() {
    for (Runnable listener : specChangeListeners) {
      try {
        listener.run();
      } catch (RuntimeException e) {
        log.warn("RuntimeReconciler: spec-change listener threw", e);
      }
    }
  }

  private void requestConvergence(TransitionReason reason) {
    synchronized (lock) {
      retryAtMillis = 0;
      backoffMillis = 0;
      convergePending = true;
      pendingReason = reason;
      dirty = true;
      lock.notifyAll();
    }
  }

  // ==================== Procedures (the chat controller's holds, §12a) ====================

  /**
   * Begin a procedure of the given {@code kind}: the first open hold of that kind ({@link
   * #followHolds}). While ANY procedure is active, the reconciler defers spec forwarding — the
   * holder owns the engine. Idempotent per kind (a repeat begin of the same kind replaces its
   * overlay entry). Paired with {@link #endProcedure}.
   */
  public void beginProcedure(RuntimeStatus.ProcedureKind kind, String reason) {
    synchronized (lock) {
      activeProcedures.put(kind, new RuntimeStatus.Procedure(kind, Instant.now(), "starting", reason));
      retryAtMillis = 0;
      backoffMillis = 0;
      dirty = true; // republish status with the overlay
      lock.notifyAll();
    }
    log.info("RuntimeReconciler: procedure {} begun (reason={})", kind, reason);
  }

  /**
   * End a machine-actor procedure of the given {@code kind}. Removes its overlay entry; only when
   * that leaves NO procedure active does the reconciler re-arm a return-to-spec convergence
   * (AUTO_START intent). This is the §3d fix: whatever non-spec state the last procedure left the
   * engine in, the reconciler then returns it to {@code spec ∧ policy}. Ending one of several
   * overlapping procedures defers any pending spec write until the final end.
   */
  public void endProcedure(RuntimeStatus.ProcedureKind kind) {
    boolean lastEnded;
    synchronized (lock) {
      activeProcedures.remove(kind);
      lastEnded = activeProcedures.isEmpty();
      if (lastEnded) {
        retryAtMillis = 0;
        backoffMillis = 0;
        if (!convergePending) pendingReason = TransitionReason.AUTO_START;
        convergePending = true; // last procedure ended — return to spec now
      }
      dirty = true;
      lock.notifyAll();
    }
    if (lastEnded) {
      log.info("RuntimeReconciler: procedure {} ended (last active) — converging back to spec", kind);
    } else {
      log.info("RuntimeReconciler: procedure {} ended — other procedure(s) active, forwarding stays deferred", kind);
    }
  }

  /** A representative overlay procedure (most-recently-begun distinct kind), or null. Holds lock. */
  private RuntimeStatus.Procedure representativeProcedureLocked() {
    RuntimeStatus.Procedure rep = null;
    for (RuntimeStatus.Procedure p : activeProcedures.values()) {
      rep = p; // insertion order: last iterated = most recently added distinct kind
    }
    return rep;
  }

  /**
   * Follows the chat controller's holds (Spec A step 6): each open kind is shown as the procedure
   * it replaced, forwarding is deferred while any is open, and the last close returns chat to the
   * intent. The holder, not this reconciler, drives the server meanwhile.
   *
   * @return closing it stops following
   */
  public AutoCloseable followHolds(ChatHolds holds) {
    java.util.Objects.requireNonNull(holds, "holds");
    return holds.addListener(new ChatHolds.Listener() {
      @Override
      public void kindOpened(ChatHolds.OpenHold first) {
        beginProcedure(procedureKind(first.kind()), first.owner());
      }

      @Override
      public void kindClosed(ChatHolds.Kind kind, boolean noneOpen) {
        endProcedure(procedureKind(kind));
      }
    });
  }

  /** The status procedure each hold replaced. */
  static RuntimeStatus.ProcedureKind procedureKind(ChatHolds.Kind kind) {
    return switch (kind) {
      case FREEZE -> RuntimeStatus.ProcedureKind.INSTALL_ACQUISITION;
      case SELF_TEST -> RuntimeStatus.ProcedureKind.ACTIVATION;
      case VDU -> RuntimeStatus.ProcedureKind.VDU_BATCH;
      case RUNNING -> RuntimeStatus.ProcedureKind.INSTALL_SMOKE_TEST;
    };
  }

  /**
   * Publishes the activation to the chat controller and waits for its step on this (reconciler or
   * procedure worker) thread. A later activation that replaced it, or a terminal controller, settles
   * the request: the newer intent or the shutdown owns the server.
   */
  private void forward(boolean active, TransitionReason reason) throws ModeTransitionException {
    lastConvergenceReason = reason;
    try {
      forwarder.setActivation(new ChatActuationRouter.Activation(active, reason))
          .toCompletableFuture().get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ModeTransitionException(ModeTransitionException.Reason.INTERRUPTED,
          "Interrupted while forwarding the activation", interrupted);
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof ChatActuationRouter.SupersededException
          || cause instanceof ChatActuationRouter.RefusedException) {
        log.info("RuntimeReconciler: activation settled without running: {}", cause.getMessage());
        return;
      }
      if (cause instanceof ModeTransitionException transition) throw transition;
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new ModeTransitionException(active
          ? ModeTransitionException.Reason.ONLINE_START_FAILED
          : ModeTransitionException.Reason.INDEXING_START_FAILED,
          "Activation failed: " + safeMessage(cause), cause);
    }
  }

  private void markStatusDirty() {
    synchronized (lock) {
      dirty = true;
      lock.notifyAll();
    }
  }

  /** Latest observed status snapshot (for future projections). */
  public RuntimeStatus current() {
    return status.get();
  }

  /**
   * Current desired state (tempdoc 737 §12c Phase 2a) — a thin passthrough read of
   * {@link RuntimeSpecStore#load()}, exposed so projections (e.g. {@code BootstrapProjections})
   * that already hold a reference to the reconciler don't need a second constructor parameter
   * threading the spec store separately.
   */
  public RuntimeSpec currentSpec() {
    return specStore.load();
  }

  /** The TransitionReason the last reconciler-initiated transition was labeled with (may be null). */
  public TransitionReason lastConvergenceReason() {
    return lastConvergenceReason;
  }

  @Override
  public void close() {
    synchronized (lock) {
      running = false;
      lock.notifyAll();
    }
    if (control != null && attachedListener != null) {
      try {
        control.removeModeChangeListener(attachedListener);
      } catch (RuntimeException e) {
        log.debug("removeModeChangeListener failed on close: {}", e.getMessage());
      }
    }
    Thread t = thread;
    if (t != null) {
      t.interrupt();
      try {
        t.join(2_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  // ==================== Reconciler thread ====================

  private void loop() {
    while (running) {
      boolean explicit;
      TransitionReason reason;
      boolean procActive;
      boolean retryDeferred;
      synchronized (lock) {
        while (running && !dirty && !retryDue()) {
          long wait = computeWaitMillis();
          try {
            if (wait <= 0) {
              lock.wait();
            } else {
              lock.wait(wait);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!running) {
              return;
            }
          }
        }
        if (!running) {
          return;
        }
        dirty = false;
        procActive = !activeProcedures.isEmpty();
        retryDeferred = retryAtMillis > System.currentTimeMillis();
        explicit = convergePending;
        if (!procActive && !retryDeferred) convergePending = false;
        reason = pendingReason;
        converging = true; // a pass is in flight — no gap for awaitQuiescent
      }

      refreshStatus();

      // A procedure owns the engine while active — the reconciler does not forward an explicit
      // spec-write mid-procedure (that would be a second writer fighting the procedure).
      // {@link #endProcedure} re-arms an explicit forward of the then-current spec, so a spec-write
      // during a procedure is honored the moment the procedure ends, not lost. A pass woken only by
      // an observed mode change republishes status and forwards nothing (reconciliation F6).
      if (!procActive && !retryDeferred && explicit) {
        reconcileToSpec(reason);
      }

      synchronized (lock) {
        converging = false;
        lock.notifyAll();
      }
    }
  }

  /**
   * Forward an explicit boot/spec/procedure-end request: converge the observed engine toward
   * {@code spec ∧ policy} through the chat controller.
   */
  private void reconcileToSpec(TransitionReason reason) {
    Mode mode = safeMode();
    if (mode == Mode.TRANSITIONING) {
      // Mid-transition — re-arm; the settling transition's listener re-wakes the loop.
      synchronized (lock) {
        convergePending = true;
        pendingReason = reason;
      }
      return;
    }

    RuntimeSpec spec = specStore.load();
    boolean effective = spec.chatEnabled() && policyOnlineEnabled();
    boolean external = safeExternal();
    boolean healthy = mode == Mode.ONLINE;
    boolean down = mode == Mode.OFFLINE || mode == Mode.INDEXING;

    boolean needUp = effective && down && !external;
    boolean needDown = !effective && healthy;

    if (!needUp && !needDown) {
      // At spec — settle. Clear the failure backoff.
      synchronized (lock) {
        retryAtMillis = 0;
        backoffMillis = 0;
      }
      refreshStatus();
      return;
    }

    // The physical manager repeats this check under its lifetime lock before AUTO_START can
    // begin. This observation only avoids redundant passes; it cannot authorize a physical open.
    if (needUp && reason == TransitionReason.AUTO_START && componentRecoveryPending.getAsBoolean()) {
      synchronized (lock) {
        retryAtMillis = 0;
        backoffMillis = 0;
      }
      refreshStatus();
      return;
    }

    try {
      // An adopted external server is detached by the controller's inactive step.
      log.info("RuntimeReconciler: forwarding activation {} (reason={}, external={})",
          needUp ? "UP" : "DOWN", reason, external);
      forward(needUp, reason);
      synchronized (lock) {
        retryAtMillis = 0;
        backoffMillis = 0;
      }
      refreshStatus();
    } catch (ModeTransitionException e) {
      log.warn("RuntimeReconciler: transition failed; {}",
          needUp ? "component recovery owns further activation attempts" : "will retry shutdown with backoff", e);
      recordEngineFailure(e);
      synchronized (lock) {
        if (needUp) {
          retryAtMillis = 0;
          backoffMillis = 0;
        } else {
          if (!convergePending) {
            convergePending = true;
            pendingReason = reason;
          }
          backoffMillis = backoffMillis == 0 ? BASE_BACKOFF_MS : Math.min(backoffMillis * 2, MAX_BACKOFF_MS);
          retryAtMillis = System.currentTimeMillis() + backoffMillis;
        }
        lock.notifyAll();
      }
    }
  }

  /** Recompute and publish the observed status from live signals, incl. the procedure overlay. */
  private void refreshStatus() {
    Mode mode = safeMode();
    boolean external = safeExternal();
    lease.mirrorFromMode(mode);
    RuntimeStatus.Procedure proc;
    synchronized (lock) {
      proc = representativeProcedureLocked();
    }
    RuntimeStatus base =
        RuntimeStatus.derive(mode, external, lease.holder(), proc, specVersion.get(), Instant.now());
    // Soft-off legibility (§15 decision 1 / task 3): a procedure holds the engine UP while the
    // user's spec disables chat — stamp a legible ENGINE reason rather than a bare "healthy" that
    // looks like chat should be available.
    if (proc != null && mode == Mode.ONLINE) {
      RuntimeSpec spec = specStore.load();
      boolean effective = spec.chatEnabled() && policyOnlineEnabled();
      if (!effective) {
        base =
            overlayEngine(
                base,
                RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND,
                "Inference engine running for background document understanding; chat is unavailable");
      }
    }
    status.set(base);
  }

  /** Overlay a reason/message onto the ENGINE condition without inventing a new axis. */
  private RuntimeStatus overlayEngine(RuntimeStatus base, String reason, String message) {
    List<RuntimeStatus.Condition> updated = new java.util.ArrayList<>();
    for (RuntimeStatus.Condition c : base.conditions()) {
      if (c.axis() == RuntimeStatus.Axis.ENGINE) {
        updated.add(
            new RuntimeStatus.Condition(
                c.axis(), c.status(), reason, message, c.observedSpecVersion(), c.lastTransition()));
      } else {
        updated.add(c);
      }
    }
    return new RuntimeStatus(updated);
  }

  private void publishEngineOverlay(String reason, String message) {
    Mode mode = safeMode();
    RuntimeStatus.Procedure proc;
    synchronized (lock) {
      proc = representativeProcedureLocked();
    }
    RuntimeStatus base =
        RuntimeStatus.derive(mode, safeExternal(), lease.holder(), proc, specVersion.get(), Instant.now());
    status.set(overlayEngine(base, reason, message));
  }

  private void recordEngineFailure(ModeTransitionException e) {
    publishEngineOverlay("transition-failed", "Transition failed: " + safeMessage(e));
  }

  private boolean retryDue() {
    return retryAtMillis > 0 && System.currentTimeMillis() >= retryAtMillis;
  }

  private long computeWaitMillis() {
    if (retryAtMillis > 0) {
      return Math.max(1, retryAtMillis - System.currentTimeMillis());
    }
    return 0; // indefinite until notified
  }

  private boolean policyOnlineEnabled() {
    if (policy == null) {
      return true;
    }
    try {
      return policy.snapshot().onlineAiEnabled();
    } catch (RuntimeException e) {
      // Best-effort, mirroring BrainRuntimeServiceImpl: a policy read error does not deny.
      return true;
    }
  }

  private Mode safeMode() {
    if (modeSupplier == null) {
      return Mode.OFFLINE;
    }
    try {
      Mode m = modeSupplier.get();
      return m == null ? Mode.OFFLINE : m;
    } catch (RuntimeException e) {
      return Mode.OFFLINE;
    }
  }

  private boolean safeExternal() {
    if (externalAdoption == null) {
      return false;
    }
    try {
      return externalAdoption.getAsBoolean();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String safeMessage(Throwable t) {
    String m = t == null ? null : t.getMessage();
    return m == null ? "unknown" : m;
  }

  // ==================== Test support ====================

  /**
   * Blocks until the reconciler thread has completed at least one full loop iteration after this
   * call with no runnable convergence and no retry scheduled. An explicit request deferred by an
   * active procedure remains queued until that procedure ends. Test-only quiescence barrier.
   */
  public boolean awaitQuiescent(long timeoutMs) {
    long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
    synchronized (lock) {
      // {@code dirty} is included: an observed mode change marks dirty WITHOUT setting
      // convergePending, so a barrier that ignored dirty could return before the loop had a chance
      // to republish status for it.
      while (running && (dirty || (convergePending && activeProcedures.isEmpty())
          || converging || retryAtMillis > 0)) {
        long remainMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainMs <= 0) {
          return false;
        }
        try {
          lock.wait(Math.max(1, remainMs));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return running && !dirty && (!convergePending || !activeProcedures.isEmpty())
          && !converging && retryAtMillis == 0;
    }
  }
}
