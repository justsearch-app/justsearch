/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import io.justsearch.app.api.lifecycle.Capability;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.services.worker.RecoveryContext;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Tracks the operational health of the Worker (Knowledge Server) capability.
 *
 * <p>Structurally acquired when the Worker first becomes reachable (health transitions from
 * PENDING). Health fluctuates thereafter as the Worker fails and recovers — until lane F stage A
 * item A11 that meant a separate process crashing and being respawned; since A11 the index half
 * runs in this JVM, so the remaining arc is a failed start recovered by
 * {@code KnowledgeServerHealthMonitor}'s boot-recovery arm, or a LOST that stays lost.
 *
 * <p>The generation counter distinguishes first-connect from recovery: generation 0→1 is
 * first connect, 1→2+ is recovery after a failure. Callers can use this to decide which
 * initialization to re-run.
 */
public final class WorkerCapability implements Capability {

  private volatile CapabilityHealth health = CapabilityHealth.PENDING;
  // Tempdoc 837 S3: the reason slot holds a LifecycleReasonCode, never prose. The pre-transition
  // default is the "nothing observed yet" code, not a sentence — it is published raw on the runtime
  // manifest and the 503 body from process start until the first transition.
  private volatile String reason = LifecycleReasonCode.WORKER_NOT_CONNECTED.code();
  private volatile String detail;
  // Tempdoc 627 (N2): the most-recent recovery context, parked by the supervision bridge immediately
  // before the RECOVERING transition so the capability-health bridge can attach it as forensic
  // attributes on the recovery occurrence. Read synchronously by transition listeners.
  private volatile RecoveryContext lastRecoveryContext;
  private final AtomicLong generation = new AtomicLong(0);
  private final List<BiConsumer<CapabilityHealth, CapabilityHealth>> listeners =
      new CopyOnWriteArrayList<>();

  @Override
  public CapabilityHealth health() {
    return health;
  }

  @Override
  public String pendingReason() {
    return health == CapabilityHealth.READY ? null : reason;
  }

  @Override
  public String pendingDetail() {
    return health == CapabilityHealth.READY ? null : detail;
  }

  @Override
  public boolean required() {
    return true;
  }

  @Override
  public String name() {
    return "worker";
  }

  public long generation() {
    return generation.get();
  }

  public boolean isFirstConnect() {
    return generation.get() <= 1;
  }

  /**
   * Transition health state. Fires listeners after transition.
   * Returns the previous health state.
   */
  public CapabilityHealth transition(CapabilityHealth newHealth, String newReason) {
    return transition(newHealth, newReason, null);
  }

  /**
   * Transition health state, carrying a human {@code detail} sentence alongside the reason code.
   *
   * <p><b>Reason retention (tempdoc 837 §D.1).</b> The decision is
   * {@link ReasonRetention#retainHeld} — the ONE rule, shared with {@link InferenceCapability}. Its
   * strongest case is this capability's corrupt-index latch: {@code WorkerFatalReasonMarker
   * .readAndClear} DELETES the marker file as it reads it, so "the worker died because the index is
   * corrupt" is observable exactly once per crash: whichever caller wins the race gets it, and a
   * later overwrite would destroy it PERMANENTLY (a restart cannot re-derive it — the marker is
   * gone). So while health is non-READY, a held {@link LifecycleReasonCode#WORKER_INDEX_CORRUPT}
   * (class {@code STICKY}) is retained against any incoming reason: the supervised-restart narration
   * ({@code worker.recovering}) and the terminal give-up ({@code worker.restart_exhausted}) are
   * downstream symptoms of the corruption, not competing causes.
   *
   * <p>The rule is bounded by recovery, not by a timer: READY clears the reason outright, so no
   * stale cause can survive a worker that came back. The new health is ALWAYS applied — only the
   * reason is retained — and a transition whose reason was rejected without a health change does
   * NOT fire listeners (the tempdoc 656 reason-only widening below must not turn a rejected write
   * into a spurious manifest publish).
   */
  public CapabilityHealth transition(
      CapabilityHealth newHealth, String newReason, String newDetail) {
    CapabilityHealth prev = this.health;
    String prevReason = this.reason;
    boolean latched = ReasonRetention.retainHeld(prevReason, newReason, newHealth);
    String effectiveReason = latched ? prevReason : newReason;
    String effectiveDetail = latched ? this.detail : newDetail;
    this.reason = effectiveReason;
    this.detail = effectiveDetail;
    this.health = newHealth;
    boolean healthChanged = prev != newHealth;
    if (healthChanged) {
      if (prev == CapabilityHealth.PENDING) {
        generation.set(1);
      } else if (newHealth == CapabilityHealth.READY
          && (prev == CapabilityHealth.RECOVERING || prev == CapabilityHealth.DEGRADED)) {
        generation.incrementAndGet();
      }
    }
    // Tempdoc 656 Task 0: fire listeners on a reason-only change too, not just a health transition —
    // see InferenceCapability.transition() for the shared rationale. Generation-counter side effects
    // above stay gated on healthChanged only; this widening only affects listener notification.
    if (healthChanged || !Objects.equals(prevReason, effectiveReason)) {
      for (BiConsumer<CapabilityHealth, CapabilityHealth> listener : listeners) {
        listener.accept(prev, newHealth);
      }
    }
    return prev;
  }

  public void addListener(BiConsumer<CapabilityHealth, CapabilityHealth> listener) {
    listeners.add(listener);
  }

  /**
   * Tempdoc 627 (N2): park the most-recent recovery context. The supervision bridge calls this
   * immediately before {@link #transition} to RECOVERING so a synchronous transition listener (the
   * capability-health bridge) can read it when it emits the recovery occurrence.
   */
  public void setRecoveryContext(RecoveryContext ctx) {
    this.lastRecoveryContext = ctx;
  }

  /** The most-recent recovery context, or {@code null} if no recovery has been recorded yet. */
  public RecoveryContext lastRecoveryContext() {
    return lastRecoveryContext;
  }
}
