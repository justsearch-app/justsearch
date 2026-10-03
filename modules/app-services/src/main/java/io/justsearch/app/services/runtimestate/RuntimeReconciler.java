/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.inference.telemetry.TransitionReason;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converges explicit runtime intent toward {@link RuntimeSpec} ∧ policy
 * (tempdoc 737 §12a). One dedicated daemon thread + a level-triggered dirty flag — <b>no reconcile
 * work ever runs on a caller or listener thread.</b>
 *
 * <h3>Re-entrancy contract (R2)</h3>
 *
 * {@code TransitionRunner} fires {@link ModeChangeListener}s <i>under its lock</i>, and a
 * re-entrant {@code run()} from a listener throws {@code IllegalStateException} ("Already
 * transitioning"). Therefore this reconciler's mode-change listener ONLY updates
 * {@link RuntimeStatus} + the {@link RuntimeGpuLease} mirror and marks the loop dirty;
 * convergence (the {@code switchTo*} calls) runs later on the reconciler thread.
 *
 * <h3>Convergence scope (Phase 2 — continuous return-to-spec)</h3>
 *
 * Convergence runs on (a) boot ({@link #requestBootConvergence()}), (b) explicit spec writes
 * ({@link #specChanged()}), (c) procedure end ({@link #endProcedure}), and (d) any observed mode
 * change (foreign flip) while <b>no procedure is active</b> and the observed engine state differs
 * from the spec target, unless the physical owner retains a failed activation for component
 * recovery. Failed activation retries belong to the common component monitor. With a procedure
 * active, foreign states are tolerated — that is the
 * procedure's business (§12a); at {@link #endProcedure} the reconciler returns the engine to spec.
 * This is what makes §3d's never-switch-back <i>inexpressible</i>.
 *
 * <h3>Procedures (the ONLY sanctioned non-spec hold)</h3>
 *
 * {@link #beginProcedure}/{@link #endProcedure} bracket a machine-actor procedure (VDU batch,
 * runtime-variant activation, install smoke test);
 * {@link #procedureRequireEngine(boolean)} is procedure-scoped engine control. A procedure is the
 * only sanctioned way for a machine actor to hold the engine in a non-spec state. Procedures of
 * distinct kinds may overlap — drift convergence stays suppressed until the <b>last</b> one ends,
 * at which point the reconciler converges the engine back to the then-current spec.
 *
 * <h3>Anti-flap (item 2)</h3>
 *
 * The existing backoff covers failed DOWN intent enforcement. A separate guard covers "transition
 * succeeded but something flipped it back": if the same foreign flip recurs more than
 * {@link #FLAP_MAX} times inside {@link #FLAP_WINDOW_MS}, the reconciler stops fighting, logs WARN,
 * and stamps the ENGINE condition {@link RuntimeStatus#REASON_CONVERGENCE_HELD_FLAP} until a spec /
 * procedure / policy input changes.
 */
public final class RuntimeReconciler implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RuntimeReconciler.class);

  private static final long BASE_BACKOFF_MS = 1_000L;
  private static final long MAX_BACKOFF_MS = 60_000L;

  /** Anti-flap window and cap (item 2): > FLAP_MAX foreign flips inside the window → hold. */
  static final long FLAP_WINDOW_MS = 5 * 60 * 1000L;
  static final int FLAP_MAX = 3;

  /** Stop-primitive that may throw the same checked exception the switch primitives do. */
  @FunctionalInterface
  public interface DetachAction {
    void detach() throws ModeTransitionException;
  }

  /**
   * Reason-bearing engine switch (task 5). Wired at the composition root to the concrete manager's
   * {@code switchToOnlineMode(TransitionReason)} / {@code switchToIndexingMode(TransitionReason)}
   * overloads. Nullable — when absent the reconciler falls back to the reason-free
   * {@link OnlineAiLifecycleControl} methods (the {@code app-api} interface cannot carry the
   * {@code app-inference} {@code TransitionReason} type, since {@code app-inference} depends on
   * {@code app-api}, not the reverse).
   */
  @FunctionalInterface
  public interface ReasonedSwitch {
    void switchTo(TransitionReason reason) throws ModeTransitionException;
  }

  private final OnlineAiLifecycleControl control;
  private final Supplier<Mode> modeSupplier;
  private final BooleanSupplier externalAdoption;
  private final DetachAction detach; // nullable
  private final EnterprisePolicyService policy; // nullable
  private final RuntimeSpecStore specStore;
  private final RuntimeGpuLease lease;
  private final ReasonedSwitch onlineSwitch; // nullable — see ReasonedSwitch
  private final ReasonedSwitch indexingSwitch; // nullable
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
  // once. Drift convergence is suppressed while this is non-empty; return-to-spec re-arms only when
  // it empties (tempdoc 737 §12a fix pack). Insertion-ordered so the status overlay can pick a
  // stable representative (the most-recently-begun distinct kind).
  private final java.util.Map<RuntimeStatus.ProcedureKind, RuntimeStatus.Procedure> activeProcedures =
      new java.util.LinkedHashMap<>();

  // Anti-flap tracking (guarded by lock).
  private boolean flapHold = false;
  private int flapCount = 0;
  private long flapWindowStartMillis = 0;
  private String flapDirection = null;

  private Thread thread;
  private ModeChangeListener attachedListener;

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
    this.control = control;
    this.modeSupplier = modeSupplier;
    this.externalAdoption = externalAdoption;
    this.detach = detach;
    this.policy = policy;
    this.specStore = specStore;
    this.lease = lease;
    this.onlineSwitch = onlineSwitch;
    this.indexingSwitch = indexingSwitch;
    this.componentRecoveryPending = java.util.Objects.requireNonNull(
        componentRecoveryPending, "componentRecoveryPending");
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
    synchronized (lock) {
      resetFlapLocked(); // spec input changed — release any flap hold
    }
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

  // ==================== Procedures (§12a — the only sanctioned non-spec engine hold) ====================

  /**
   * Begin a machine-actor procedure of the given {@code kind}. While ANY procedure is active, the
   * reconciler tolerates foreign engine states (no drift convergence) — the procedure owns the
   * engine. Resets any flap hold (procedure input changed). Idempotent per kind (a repeat begin of
   * the same kind replaces its overlay entry). Must be paired with {@link #endProcedure} in a
   * {@code finally}.
   */
  public void beginProcedure(RuntimeStatus.ProcedureKind kind, String reason) {
    synchronized (lock) {
      activeProcedures.put(kind, new RuntimeStatus.Procedure(kind, Instant.now(), "starting", reason));
      retryAtMillis = 0;
      backoffMillis = 0;
      resetFlapLocked();
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
   * overlapping procedures keeps drift suppressed for the survivors and defers any pending spec
   * write until the final end.
   */
  public void endProcedure(RuntimeStatus.ProcedureKind kind) {
    boolean lastEnded;
    synchronized (lock) {
      activeProcedures.remove(kind);
      resetFlapLocked();
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
      log.info("RuntimeReconciler: procedure {} ended — other procedure(s) active, drift stays suppressed", kind);
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
   * Procedure-scoped engine control: bring the engine up ({@code up=true}) or park it into the
   * indexing/down state ({@code up=false}). Executed <b>synchronously on the caller's (procedure
   * worker) thread</b> — that thread is never a {@link ModeChangeListener} callback, so the R2
   * re-entrancy hazard (calling {@code switchTo*} under {@code TransitionRunner}'s lock) does not
   * apply; and because the reconciler suppresses its own drift convergence while a procedure is
   * active, there is a single writer at any instant (the procedure thread during the procedure, the
   * reconciler thread otherwise). The {@code switchTo*} call physically resides on this class, so
   * the single-writer ArchUnit guard is satisfied.
   */
  public void procedureRequireEngine(boolean up) throws ModeTransitionException {
    updateProcedurePhase(up ? "engine-up" : "engine-down");
    if (up) {
      if (safeMode() != Mode.ONLINE) {
        doSwitchOnline(TransitionReason.VDU_ENTER);
      }
    } else {
      if (safeMode() != Mode.INDEXING) {
        doSwitchIndexing(TransitionReason.VDU_EXIT);
      }
    }
    refreshStatus();
  }

  private void updateProcedurePhase(String phase) {
    synchronized (lock) {
      RuntimeStatus.Procedure rep = representativeProcedureLocked();
      if (rep != null) {
        activeProcedures.put(
            rep.kind(),
            new RuntimeStatus.Procedure(rep.kind(), rep.startedAt(), phase, rep.reason()));
      }
    }
  }

  /** Reset the anti-flap tracking. Caller MUST hold {@link #lock}. */
  private void resetFlapLocked() {
    flapHold = false;
    flapCount = 0;
    flapWindowStartMillis = 0;
    flapDirection = null;
  }

  private void doSwitchOnline(TransitionReason reason) throws ModeTransitionException {
    lastConvergenceReason = reason;
    if (onlineSwitch != null) {
      onlineSwitch.switchTo(reason);
    } else {
      control.switchToOnlineMode();
    }
  }

  private void doSwitchIndexing(TransitionReason reason) throws ModeTransitionException {
    lastConvergenceReason = reason;
    if (indexingSwitch != null) {
      indexingSwitch.switchTo(reason);
    } else {
      control.switchToIndexingMode();
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

      // A procedure owns the engine while active — the reconciler neither converges on drift NOR on
      // an explicit spec-write mid-procedure (that would be a second writer fighting the procedure).
      // {@link #endProcedure} re-arms an explicit convergence toward the then-current spec, so a
      // spec-write during a procedure is honored the moment the procedure ends, not lost.
      if (!procActive && !retryDeferred) {
        if (explicit) {
          // Boot / spec-write / procedure-end: always converge toward spec, bypassing the flap hold.
          reconcileToSpec(reason, false);
        } else {
          // Foreign mode change with no procedure active: continuous return-to-spec (anti-flap gated).
          reconcileToSpec(TransitionReason.AUTO_START, true);
        }
      }

      synchronized (lock) {
        converging = false;
        lock.notifyAll();
      }
    }
  }

  /**
   * Converge the observed engine toward {@code spec ∧ policy}. {@code driftTriggered} distinguishes
   * a foreign-flip convergence (anti-flap gated, item 2) from an explicit boot/spec/procedure-end
   * request (always attempted).
   */
  private void reconcileToSpec(TransitionReason reason, boolean driftTriggered) {
    Mode mode = safeMode();
    if (mode == Mode.TRANSITIONING) {
      // Mid-transition — cannot switch (would throw "Already transitioning"). Defer: an explicit
      // request re-arms; a drift-triggered one relies on the settling transition's listener to
      // re-wake the loop and re-evaluate.
      if (!driftTriggered) {
        synchronized (lock) {
          convergePending = true;
          pendingReason = reason;
        }
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
      // At spec — settle. Clear the failure backoff; flap tracking ages out of its window or
      // resets on the next spec/procedure/policy input change.
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

    // Drift from spec. Anti-flap only applies to drift-triggered (autonomous) convergence — an
    // explicit user/boot/procedure-end request is always honored.
    if (driftTriggered && !passesFlapGate(needUp)) {
      return;
    }

    try {
      if (needUp) {
        log.info("RuntimeReconciler: converging engine UP (reason={}, drift={})", reason, driftTriggered);
        doSwitchOnline(reason);
      } else { // needDown
        if (external) {
          log.info("RuntimeReconciler: chat disabled + external adoption -> detach (reason={})", reason);
          if (detach != null) {
            detach.detach();
          }
        } else {
          log.info("RuntimeReconciler: converging engine DOWN (reason={}, drift={})", reason, driftTriggered);
          doSwitchIndexing(reason);
        }
      }
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

  /**
   * Anti-flap gate (item 2). Counts each foreign flip inside {@link #FLAP_WINDOW_MS}; once the same
   * direction recurs past {@link #FLAP_MAX}, sets the hold, WARNs once, stamps the ENGINE condition,
   * and returns {@code false} (do not attempt). Returns {@code true} when convergence may proceed.
   */
  private boolean passesFlapGate(boolean needUp) {
    boolean held;
    boolean warn = false;
    synchronized (lock) {
      if (flapHold) {
        held = true;
      } else {
        long now = System.currentTimeMillis();
        if (flapWindowStartMillis == 0 || now - flapWindowStartMillis > FLAP_WINDOW_MS) {
          flapWindowStartMillis = now;
          flapCount = 0;
        }
        flapCount++;
        flapDirection = needUp ? "UP" : "DOWN";
        if (flapCount > FLAP_MAX) {
          flapHold = true;
          held = true;
          warn = true;
        } else {
          held = false;
        }
      }
    }
    if (held) {
      if (warn) {
        log.warn(
            "RuntimeReconciler: convergence held ({}): foreign flip recurred > {} times within {}ms;"
                + " holding until spec/procedure/policy input changes",
            flapDirection,
            FLAP_MAX,
            FLAP_WINDOW_MS);
      }
      publishEngineOverlay(
          RuntimeStatus.REASON_CONVERGENCE_HELD_FLAP,
          "Convergence held: suspected engine flapping (foreign state repeatedly reverted)");
      return false;
    }
    return true;
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
      // {@code dirty} is included: a foreign-flip (drift-triggered) convergence marks dirty WITHOUT
      // setting convergePending, so a barrier that ignored dirty could return before the loop had a
      // chance to process the drift.
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
