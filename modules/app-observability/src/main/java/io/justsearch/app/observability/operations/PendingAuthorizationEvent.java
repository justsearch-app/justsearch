/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import java.time.Instant;

/**
 * Wire-shaped announcement that a {@code PendingAuthorization} was just created by a gate firing
 * — tempdoc 655. Lets a caller surface other than the live HTTP request that triggered the gate
 * (concretely: an MCP tool call) notify the always-on frontend shell that a human approval is now
 * waiting, without the frontend having to have made the original request.
 *
 * <p><b>Deliberately does NOT carry {@code argsSummary} or {@code rationale}</b> (tempdoc 655
 * fix pass). Those are decision content — what the human is being asked to approve — and the
 * existing privacy posture (tempdoc 444b: never dump argument-derived content into a broadcast;
 * tempdoc 550 F3's {@code argsSummary} is a NAMED, deliberate exception scoped to the
 * point-to-point 428 response to the one human deciding that one action, not to a channel every
 * local subscriber receives). This event is routing/identifying information only — enough for a
 * subscriber to know "something needs approval" and fetch the decision content itself, by id, via
 * {@code GET /api/authorizations/pending/{id}} (point-to-point, mirrors the 428's own shape).
 *
 * <p><b>{@code transport} is a routing fact, not decision or identity content</b> — unlike
 * {@code requestedBy} (a display-only field on {@link
 * io.justsearch.app.services.intent.PendingAuthorization}, deliberately kept off this broadcast
 * event), it's safe here and is exactly what a subscriber like
 * {@code PendingAuthorizationAdvisoryProjector} needs to distinguish a gate with no in-page
 * synchronous responder (MCP) from a browser 428 the caller's own request is already driving a
 * ceremony dialog for.
 */
public record PendingAuthorizationEvent(
    String pendingId,
    String operationId,
    SourceTier sourceTier,
    RiskTier riskTier,
    GateBehavior gateBehavior,
    Instant createdAt,
    Instant expiresAt,
    TransportTag transport,
    String disposition) {
  public PendingAuthorizationEvent(String pendingId, String operationId, SourceTier sourceTier,
      RiskTier riskTier, GateBehavior gateBehavior, Instant createdAt, Instant expiresAt,
      TransportTag transport) {
    this(pendingId, operationId, sourceTier, riskTier, gateBehavior, createdAt, expiresAt, transport, "GATED");
  }
}
