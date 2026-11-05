/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import java.time.Instant;
import java.util.Objects;

/**
 * One trust-gate decision for one dispatch — tempdoc 550 Outcome face.
 *
 * <p>This is the gate-decision record the canonical action ledger was missing. It is emitted
 * at the gate-fire site for every non-AUTO outcome ({@link AuthorizationDisposition#GATED} /
 * {@link AuthorizationDisposition#DENIED} / {@link AuthorizationDisposition#APPROVED}), so the
 * action ledger (and the 538 trust-firing audit as a read-view of it) sees gate firings — not
 * only the outcomes of dispatches that completed.
 *
 * <p>Internal observability record (not a wire-governed contract). It carries the audit axes:
 * the {@code transport} (from which the ledger derives the coarse {@code originator}), the
 * {@code sourceTier} the gate evaluated, the operation's {@code riskTier}, and the computed
 * {@link GateBehavior}.
 *
 * @param operationId the gated operation
 * @param transport the dispatch transport (the ledger maps this to originator)
 * @param sourceTier the source tier the lattice evaluated
 * @param riskTier the operation's risk tier
 * @param gateBehavior the computed gate (INLINE_CONFIRM / TYPED_CONFIRM / DENY)
 * @param disposition how the gate resolved
 * @param occurredAt when the gate fired
 */
public record AuthorizationOutcomeEntry(
    String operationId,
    TransportTag transport,
    SourceTier sourceTier,
    RiskTier riskTier,
    GateBehavior gateBehavior,
    AuthorizationDisposition disposition,
    Instant occurredAt,
    String pendingId) {

  public AuthorizationOutcomeEntry(String operationId, TransportTag transport, SourceTier sourceTier,
      RiskTier riskTier, GateBehavior gateBehavior, AuthorizationDisposition disposition, Instant occurredAt) {
    this(operationId, transport, sourceTier, riskTier, gateBehavior, disposition, occurredAt, null);
  }

  public AuthorizationOutcomeEntry {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(sourceTier, "sourceTier");
    Objects.requireNonNull(riskTier, "riskTier");
    Objects.requireNonNull(gateBehavior, "gateBehavior");
    Objects.requireNonNull(disposition, "disposition");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
