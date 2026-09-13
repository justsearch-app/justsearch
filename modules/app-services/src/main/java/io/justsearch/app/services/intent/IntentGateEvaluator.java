/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.agent.api.registry.AutonomyLevel;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.IntentSource;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.api.registry.TrustEvaluator;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Tempdoc 550 thesis III — the ONE intent-gate computation.
 *
 * <p>Composes the three structural facets of "may this actor act" into a single verdict: the
 * derived {@link SourceTier} (transport→tier, strictest {@code UNTRUSTED} when the transport is
 * unregistered), the {@code (SourceTier × RiskTier)} lattice gate ({@link TrustEvaluator}), and the
 * Global Hard Stop override (engaged → DENY every {@code UNTRUSTED} dispatch). Both the enforcement
 * chokepoint ({@code OperationExecutorImpl#enforceTrustLattice}) and the Preview endpoint
 * ({@code OperationPreviewController}) read THIS — neither recomputes — so a preview can never
 * disagree with what enforcement does (the F1 drift class, where the preview predicted a permissive
 * gate while dispatch DENIED under the engaged hard stop, becomes structurally impossible).
 *
 * <p><b>Scope (the probe correction folded into the design):</b> this is the <em>structural</em>
 * verdict only. The args-bound consent-capsule check is NOT here — it needs the arguments + token
 * the Preview does not have, so it stays enforcement-only. Preview reads the structural verdict (a
 * prediction); enforcement reads the same verdict and additionally verifies the capsule on a
 * non-AUTO gate.
 *
 * <p>The hard-stop signal is supplied as a {@link BooleanSupplier} rather than a direct dependency
 * on {@code GlobalHardStop} so this {@code intent}-package type does not import the {@code executor}
 * package (no cross-package cycle); the executor forwards {@code globalHardStop::isEngaged}.
 */
public final class IntentGateEvaluator {

  private final TrustEvaluator trustEvaluator;
  private final IntentSourceCatalog intentSourceCatalog;
  // Late-bound (like the executor's hard stop). Default: not engaged (legacy/test paths).
  private volatile BooleanSupplier hardStopEngaged = () -> false;

  public IntentGateEvaluator(
      TrustEvaluator trustEvaluator, IntentSourceCatalog intentSourceCatalog) {
    this.trustEvaluator = Objects.requireNonNull(trustEvaluator, "trustEvaluator");
    this.intentSourceCatalog = Objects.requireNonNull(intentSourceCatalog, "intentSourceCatalog");
  }

  /** Wire the process-wide emergency stop the verdict reflects (tempdoc 550 E2/F1). */
  public void setHardStopSignal(BooleanSupplier engaged) {
    this.hardStopEngaged = engaged != null ? engaged : () -> false;
  }

  /** Derive the source tier for a transport: strictest ({@code UNTRUSTED}) when unregistered. */
  public SourceTier sourceTierFor(TransportTag transport) {
    return sourceTierFor(intentSourceCatalog, transport);
  }

  /** Shared resolution for port attribution and the dispatch verdict. */
  static SourceTier sourceTierFor(IntentSourceCatalog catalog, TransportTag transport) {
    return catalog
        .findByTransport(transport)
        .map(IntentSource::sourceTier)
        .orElse(SourceTier.UNTRUSTED);
  }

  /**
   * The one structural verdict: {@link SourceTier} + (hard-stop-adjusted) {@link GateBehavior} +
   * whether the hard stop is engaged. Does NOT consult args/token (capsule verification is
   * enforcement-only).
   */
  public IntentVerdict evaluate(RiskTier risk, TransportTag transport) {
    Objects.requireNonNull(risk, "risk");
    Objects.requireNonNull(transport, "transport");
    SourceTier sourceTier = sourceTierFor(transport);
    GateBehavior gate = trustEvaluator.evaluate(sourceTier, risk);
    boolean engaged = hardStopEngaged.getAsBoolean();
    // E2: an engaged hard stop DENIES every UNTRUSTED (agent / MCP / plugin / emission) dispatch,
    // before the per-op lattice gate matters. User-driven (TRUSTED / MEDIUM) dispatch is unaffected.
    if (engaged && sourceTier == SourceTier.UNTRUSTED) {
      gate = GateBehavior.DENY;
    }
    return new IntentVerdict(sourceTier, gate, engaged);
  }

  /**
   * Tempdoc 561 P-D — the ONE agent-loop ISSUANCE verdict: the {@link GateBehavior} the FE should
   * apply (prompt vs auto-approve) to an {@code AGENT_LOOP} dispatch of {@code risk} under the user's
   * {@code autonomyLevel}. This is the backend issuance policy (550 Thesis V) that replaces the FE's
   * {@code agentToolAutoApprove} re-derivation.
   *
   * <p>Relationship to enforcement (no drift): enforcement still verifies the consent capsule on the
   * base {@code (UNTRUSTED × risk)} lattice gate, and the capsule is minted on every approval —
   * auto-approval included. So when this method returns {@code AUTO} for a MEDIUM dispatch (the
   * {@code AUTO} dial trusting the agent), the FE auto-approval still mints the capsule the base
   * lattice's {@code TYPED_CONFIRM} enforcement requires. This method decides <em>whether the user is
   * prompted</em>; enforcement decides <em>whether a capsule is present</em> — distinct questions that
   * agree in outcome.
   *
   * <p>Absolute floors, independent of the dial: an engaged hard stop {@code DENY}s; HIGH/destructive
   * never returns {@code AUTO}; and (tempdoc 561 §19/C-4) an <em>irreversible</em> MEDIUM write
   * confirms even under {@code AUTO} — only reads and reversible writes auto-fire. An op is reversible
   * iff its handler declares undo or a backend inverse ({@code undoSupported || inverseOperationRef});
   * unknown ⇒ irreversible (the safe side).
   */
  public GateBehavior agentGate(RiskTier risk, AutonomyLevel autonomyLevel) {
    // Back-compat overload (no reversibility signal) → safe side: treat the op as irreversible, so a
    // MEDIUM write confirms even under AUTO. Production routes the 3-arg form with the real signal.
    return agentGate(risk, autonomyLevel, false);
  }

  /**
   * Tempdoc 561 §19/C-4 — the issuance verdict refined by reversibility. Identical to the 2-arg form
   * except the {@code AUTO} dial no longer blanket-approves MEDIUM: an <em>irreversible</em> MEDIUM
   * write (no declared undo / inverse) returns {@code TYPED_CONFIRM} even under {@code AUTO}, closing
   * the hallucinated-path auto-write hazard the live audit caught (Appendix A). Reads (LOW) and
   * reversible MEDIUM writes still auto-fire under {@code AUTO}; WATCH/ASSIST and the HIGH/hard-stop
   * floors are unchanged.
   *
   * @param reversible whether the op declares undo or a backend inverse; callers pass {@code false}
   *     when the signal is unknown (the safe side).
   */
  public GateBehavior agentGate(RiskTier risk, AutonomyLevel autonomyLevel, boolean reversible) {
    // No declared confirm strategy in scope → no floor; the dial verdict stands unchanged.
    return agentGate(risk, autonomyLevel, reversible, ConfirmStrategy.None.INSTANCE);
  }

  /**
   * Tempdoc 879 — the issuance verdict with the operation's declared {@link ConfirmStrategy} as an
   * absolute FLOOR: the returned gate is never weaker than what the operation itself asked for
   * ({@code None} → no floor, {@code Inline} → at least {@code INLINE_CONFIRM}, {@code Typed} → at
   * least {@code TYPED_CONFIRM}).
   *
   * <p>WHY: the lattice/strategy split described on {@link GateBehavior} was TOTAL on the agent path
   * — the declared strategy decided nothing and never reached the dispatcher, while {@code
   * ConfirmValidator} explicitly permits any strategy at MEDIUM. So a <em>reversible</em> MEDIUM
   * operation declaring {@code Inline} returned {@code AUTO} under the {@code AUTO} dial: the loop
   * auto-approved something whose own declaration asked for a confirmation. Making the declaration a
   * floor closes that hole the same way the HIGH floor above closes the destructive one. The dial may
   * still TIGHTEN (WATCH raising a read to {@code INLINE_CONFIRM} is unchanged); it may no longer
   * loosen below the declaration. A hard-stop {@code DENY} short-circuits before the floor applies —
   * a floor must never weaken a denial.
   *
   * @param confirm the operation's declared confirmation mechanism; {@code null} is treated as {@code
   *     None} (no floor).
   */
  public GateBehavior agentGate(
      RiskTier risk, AutonomyLevel autonomyLevel, boolean reversible, ConfirmStrategy confirm) {
    Objects.requireNonNull(risk, "risk");
    AutonomyLevel level = autonomyLevel == null ? AutonomyLevel.DEFAULT : autonomyLevel;
    // The base (UNTRUSTED × risk) lattice + hard-stop. An engaged hard stop already produced DENY.
    GateBehavior base = evaluate(risk, TransportTag.AGENT_LOOP).gateBehavior();
    if (base == GateBehavior.DENY) {
      return GateBehavior.DENY;
    }
    if (risk == RiskTier.HIGH) {
      return GateBehavior.TYPED_CONFIRM; // safety floor: destructive never auto-fires
    }
    GateBehavior dialVerdict =
        switch (level) {
          // WATCH — review everything; even a read-only call surfaces a lightweight acknowledgement.
          case WATCH ->
              risk == RiskTier.LOW ? GateBehavior.INLINE_CONFIRM : GateBehavior.TYPED_CONFIRM;
          // ASSIST — the safe default: reads auto-run; writes require typed confirmation (base gate).
          case ASSIST -> base;
          // AUTO — trust the agent for reads and REVERSIBLE writes; an IRREVERSIBLE MEDIUM write
          // still confirms even in AUTO (C-4). HIGH already handled above.
          case AUTO ->
              (risk == RiskTier.MEDIUM && !reversible)
                  ? GateBehavior.TYPED_CONFIRM
                  : GateBehavior.AUTO;
        };
    return strictest(dialVerdict, declaredFloor(confirm));
  }

  /** The weakest gate the operation's own declaration permits (AUTO ⇒ no floor at all). */
  private static GateBehavior declaredFloor(ConfirmStrategy confirm) {
    if (confirm == null) {
      return GateBehavior.AUTO;
    }
    return switch (confirm) {
      case ConfirmStrategy.None ignored -> GateBehavior.AUTO;
      case ConfirmStrategy.Inline ignored -> GateBehavior.INLINE_CONFIRM;
      case ConfirmStrategy.Typed ignored -> GateBehavior.TYPED_CONFIRM;
    };
  }

  /** Declaration order is the strictness order: AUTO &lt; INLINE_CONFIRM &lt; TYPED_CONFIRM &lt; DENY. */
  private static GateBehavior strictest(GateBehavior left, GateBehavior right) {
    return left.ordinal() >= right.ordinal() ? left : right;
  }

  /** The structural facets of one intent evaluation (tempdoc 550 thesis III). */
  public record IntentVerdict(
      SourceTier sourceTier, GateBehavior gateBehavior, boolean hardStopEngaged) {}
}
