/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import java.time.Instant;
import java.util.Objects;

/**
 * A backend-created record of an action that hit a non-AUTO trust gate and is awaiting a
 * human authorization decision — tempdoc 550 Slice C3 (the unified Authorize ceremony) +
 * WA-5 (Tier-0 hardening).
 *
 * <p><strong>Why this exists (the security property).</strong> Before C3, the approval
 * endpoint minted a {@link ConsentCapsuleService consent capsule} for an arbitrary
 * {@code (operationId, args)} presented by the caller. That trusted the caller to only
 * ask for what a human approved — which an in-process agent / prompt-injection could
 * abuse to self-approve an action the user never saw. With C3, a capsule can only be
 * minted against a PendingAuthorization that the <em>backend itself</em> created when it
 * gated a dispatch. You cannot approve what was never gated: the agent cannot conjure a
 * pending entry for an un-gated op, so it cannot forge an approval. The approval gesture
 * is reduced to "consume pending {@code id}", and the capsule binds to the
 * <em>stored</em> {@code (operationId, argsJson)} — not to anything the approve caller
 * supplies.
 *
 * <p>This is the one record the six scattered gate paths (the HTTP 428 path, the
 * agent-loop {@code CompletableFuture} gate, {@code PendingEffect}, capability consent,
 * the 499 resolution, and gated Navigation per WA-4) converge on as producers.
 *
 * @param id opaque server-assigned id ({@code pa-<uuid>}); the only thing the approve
 *     gesture references.
 * @param operationId the gated operation (or, for a gated Navigation, the surface target).
 * @param argsJson public args captured at gate time, retained separately for display and dispatch.
 *     Prepared consent additionally binds operationKey and preparationNonce in its signed scope.
 * @param sourceTier the source tier the gate evaluated (audit / trust-aware copy).
 * @param riskTier the operation's risk tier.
 * @param gateBehavior the computed gate (INLINE_CONFIRM / TYPED_CONFIRM).
 * @param rationale human-readable why-gated seed (e.g. the dispatcher's message).
 * @param createdAt when the gate fired.
 * @param expiresAt when this pending becomes unusable (stale approvals are refused).
 * @param requestedBy tempdoc 655 — the calling MCP client's self-reported name (from its
 *     {@code initialize} handshake's {@code clientInfo}), when the gate fired via MCP; {@code
 *     null} for a browser-originated gate or an MCP client that omitted {@code clientInfo}.
 *     Display-only (surfaced in the approval ceremony and the advisory inbox) — never a trust
 *     input; unlike {@code operationId}/{@code argsJson}, nothing security-relevant reads it.
 * @param transport tempdoc 655 critical-analysis fix — which transport's gate produced this
 *     pending ({@link TransportTag#MCP} vs. a browser transport). Distinguishes a gate with no
 *     in-page synchronous responder (MCP) from one where the caller's own request is already
 *     driving the ceremony dialog (a browser 428) — see {@code
 *     PendingAuthorizationAdvisoryProjector}, which uses this to avoid a redundant advisory for
 *     an action the user just triggered themselves.
 */
public record PendingAuthorization(
    String id,
    String operationId,
    String argsJson,
    SourceTier sourceTier,
    RiskTier riskTier,
    GateBehavior gateBehavior,
    String rationale,
    Instant createdAt,
    Instant expiresAt,
    String requestedBy,
    TransportTag transport,
    io.justsearch.core.context.EngineContext engineContext,
    io.justsearch.agent.api.registry.InvocationProvenance provenance,
    String operationKey,
    boolean undo,
    java.util.UUID preparationNonce,
    io.justsearch.agent.api.registry.OperationApprovalPreview approvalPreview) {

  public PendingAuthorization {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(argsJson, "argsJson");
    Objects.requireNonNull(sourceTier, "sourceTier");
    Objects.requireNonNull(riskTier, "riskTier");
    Objects.requireNonNull(gateBehavior, "gateBehavior");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(engineContext, "engineContext");
    Objects.requireNonNull(provenance, "provenance");
    if (EngineProvenance.sourceTier(engineContext) != sourceTier
        || !transport.name().equals(engineContext.transport())
        || !EngineProvenance.invocation(engineContext, provenance.executor(),
            provenance.occurredAt(), provenance.signedIntentToken()).equals(provenance)) {
      throw new IllegalArgumentException("Pending authorization attribution disagrees");
    }
    if (preparationNonce != null) Objects.requireNonNull(operationKey, "operationKey");
    if (approvalPreview != null) Objects.requireNonNull(preparationNonce, "preparationNonce");
    rationale = rationale == null ? "" : rationale;
    requestedBy = requestedBy == null || requestedBy.isBlank() ? null : requestedBy;
  }

  /** True when {@code now} is at or past {@link #expiresAt}. */
  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
