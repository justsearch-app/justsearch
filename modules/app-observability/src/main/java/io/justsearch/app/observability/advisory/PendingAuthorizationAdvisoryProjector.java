/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import io.justsearch.agent.api.registry.EmissionPolicy;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.observability.operations.PendingAuthorizationEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Projector for the "a pending MCP approval is waiting" advisory class — tempdoc 655's long-term
 * design pass. Consumes {@link PendingAuthorizationEvent} via {@code subscribeTyped} and projects
 * a gate-firing into the uniform {@link AdvisoryRecord} shape, mirroring {@link
 * HealthRecoveryProjector}'s bootstrap-subscription wiring shape (not {@code
 * OperationCompletionProjector}'s inline-callback shape), since the natural domain registry —
 * {@link io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry} — already
 * exists and is already fed by both transports (MCP and the browser 428 path).
 *
 * <p>Uses {@link EmissionPolicy#requiresAck()} — reserved by that factory's own doc comment for
 * exactly this shape ("destructive-action confirmations the user dismissed") and unused by any
 * class before this one. This is a passive, complementary signal to the direct approval-ceremony
 * dialog (already triggered separately via the same underlying event): the ceremony handles "the
 * user is looking right now," this advisory class handles "make sure it's discoverable even if
 * they weren't."
 *
 * <p><b>Filters on {@link PendingAuthorizationEvent#transport()} — MCP only</b> (tempdoc 655
 * critical-analysis fix). Both {@code McpToolSurface} and {@code OperationsController}'s browser
 * 428 path broadcast into the same shared registry this projector subscribes to — but a browser
 * gate already has the ceremony dialog synchronously driven by the caller's own in-flight request
 * (the exact overlap {@code pendingAuthorizationBridge.ts}'s own {@code handledIds} dedup already
 * defends against for the dialog). Projecting an advisory for a browser gate would show a
 * redundant "Approval requested" toast for an action the user just triggered themselves,
 * contradicting this class's own purpose (discoverability for a gate the user might have missed —
 * inapplicable to a self-triggered click). MCP has no in-page synchronous responder, so it's the
 * only transport that needs this signal.
 *
 * <p>No dedupe window: {@link #dedupKey} is the {@code pendingId} itself, which is already unique
 * per gate-firing (UUID-based), so there is nothing to collapse.
 *
 * <p>Deliberately does NOT carry {@code requestedBy} in {@code classExtras} — {@link
 * PendingAuthorizationEvent} itself withholds decision/identity content by design (its own doc
 * comment: routing information only, never argument- or identity-derived content on a broadcast
 * every subscriber receives; tempdoc 444b). A consumer that wants the requester name already has
 * it via the point-to-point {@code GET /api/authorizations/pending/{id}} fetch the ceremony dialog
 * uses.
 */
public final class PendingAuthorizationAdvisoryProjector
    implements AdvisoryProjector<PendingAuthorizationEvent> {

  public static final AdvisoryClassId CLASS_ID = AdvisoryClassId.of("authorization.pending");

  @Override
  public AdvisoryClassId classId() {
    return CLASS_ID;
  }

  @Override
  public EmissionPolicy emissionPolicy() {
    return EmissionPolicy.requiresAck();
  }

  @Override
  public Optional<AdvisoryProjection> project(PendingAuthorizationEvent event) {
    if (event.transport() != TransportTag.MCP || !"GATED".equals(event.disposition())) {
      return Optional.empty();
    }
    Map<String, Object> extras = new LinkedHashMap<>();
    extras.put("pendingId", event.pendingId());
    extras.put("operationId", event.operationId());
    extras.put("riskTier", event.riskTier().name());
    extras.put("gateBehavior", event.gateBehavior().name());
    // Tempdoc 807 item 3: when this approval request stops being valid. Routing/timing, the same
    // class as createdAt below — not argument- or identity-derived content, so it sits inside this
    // event's deliberate privacy boundary. Without it no subscriber could tell the user how long
    // they have, and no round could induce or verify expiry (sandbox round 13 F3 named the
    // expired-pending ceremony UNPERFORMABLE for exactly this reason). ISO-8601 UTC.
    extras.put("expiresAt", event.expiresAt().toString());
    return Optional.of(
        new AdvisoryProjection(
            event.createdAt(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            extras));
  }

  @Override
  public String dedupKey(PendingAuthorizationEvent event) {
    return event.pendingId();
  }
}
