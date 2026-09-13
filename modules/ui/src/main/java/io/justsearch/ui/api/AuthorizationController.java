/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.ConsentCapsuleAuthority;
import io.justsearch.agent.api.registry.SourceTier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mints {@link io.justsearch.app.services.intent.ConsentCapsuleService consent capsules}
 * on an explicit user-approval gesture — tempdoc 550 Slice A1 (Authorize face).
 *
 * <p>This is the resolution half of the gated-action recovery the spine introduces. When
 * an UNTRUSTED-source Invocation hits a non-AUTO trust gate, the dispatcher refuses with
 * {@code CONFIRMATION_REQUIRED} (HTTP 428) and the action is — pre-A1 — a dead end. With
 * A1, the user reviews the pending action and approves it; the FE calls this endpoint,
 * receives a capsule bound to exactly that {@code (operationId, args)}, and re-dispatches
 * the SAME Invocation with the capsule in its {@code confirmationToken}. The lattice then
 * verifies the capsule and permits the action — for the right reason (proof of user
 * approval), not a fabricated placeholder.
 *
 * <p>Wire: {@code POST /api/authorizations/approve} with body
 * {@code { "operationId": "...", "args": { ... } }} → {@code { "capsule": "<token>" }}.
 * The {@code args} must be byte-identical to what the re-dispatch will send (the capsule
 * binds to a hash of the serialized args).
 *
 * <p><b>FLAGGED for review (550 §Review-package C2):</b> in a loopback single-user app
 * this endpoint <i>is</i> the user-gesture boundary — it trusts that its caller is the
 * real FE acting on a human click. Hardening it against a prompt-injected agent calling
 * it to self-approve (e.g. a same-tab nonce / origin check / human-interaction proof) is
 * an open design point flagged for review, not resolved in this additive slice.
 *
 * <p><b>Tempdoc 655 addendum to the above:</b> {@code execute: true} (see {@link #handleApprove})
 * narrows this further — previously, completing a mutation from a bare {@code pendingId} still
 * required the caller to separately know/supply the byte-identical original args (a second,
 * independent piece of knowledge); with {@code execute: true} a {@code pendingId} alone is
 * sufficient, since the server dispatches with its OWN stored args. Accepted as bounded residual
 * risk for the same reason the note above already accepts this endpoint's trust model:
 * {@code pendingId}s are unguessable (128-bit UUID) and, as of the same fix pass, are no longer
 * broadcast with any decision content attached ({@link
 * io.justsearch.app.observability.operations.PendingAuthorizationEvent} carries routing info
 * only — {@link #handlePeekPending} is the point-to-point fetch for the rest). Not resolved
 * beyond that; the same hardening options named above would close this too, if ever done.
 */
public final class AuthorizationController {

  private static final Logger log = LoggerFactory.getLogger(AuthorizationController.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final ConsentCapsuleAuthority capsuleService;

  /**
   * Tempdoc 550 C3 — nullable. The hardened approve path: the caller presents a
   * {@code pendingId} the backend issued when it gated a dispatch; this controller consumes
   * it and mints a capsule bound to the STORED {@code (operationId, argsJson)}. Because only
   * a real backend gate creates a pending, the approve gesture cannot mint a capsule for an
   * op the user never saw (WA-5). Null in legacy/test wiring → only the deprecated
   * arbitrary-{@code (operationId, args)} path is available.
   */
  private final io.justsearch.app.services.intent.PendingAuthorizationStore pendingStore;

  /**
   * Tempdoc 550 thesis IV — nullable durable allow-always grant store. When the user approves with
   * {@code allowAlways}, the (operationId, sourceTier) is recorded here so future invocations
   * auto-approve at the gate without re-prompting. Null in legacy/test wiring.
   */
  private final io.justsearch.app.services.intent.DurableGrantStore durableGrantStore;

  /**
   * Tempdoc 655 — nullable. Lets {@code execute: true} on an approval complete the operation
   * server-side, immediately, using the pending record's OWN stored args — for approvals whose
   * origin (an MCP tool call) never gave the browser the full arguments to replay itself. Null
   * in legacy/test wiring, or when {@code execute} is omitted/false: unchanged behavior (mint
   * the capsule, return it, let the caller re-invoke).
   */
  private final io.justsearch.agent.api.registry.OperationDispatcher dispatcher;

  private final List<io.justsearch.agent.api.registry.OperationCatalog> catalogs;
  private final io.justsearch.app.api.EngineAdmissionService admission;

  public AuthorizationController(ConsentCapsuleAuthority capsuleService) {
    this(capsuleService, null, null);
  }

  public AuthorizationController(
      ConsentCapsuleAuthority capsuleService,
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingStore) {
    this(capsuleService, pendingStore, null);
  }

  /** Tempdoc 550 C3 + thesis IV constructor: pending registry + durable grant store, no server-side execute. */
  public AuthorizationController(
      ConsentCapsuleAuthority capsuleService,
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingStore,
      io.justsearch.app.services.intent.DurableGrantStore durableGrantStore) {
    this(capsuleService, pendingStore, durableGrantStore, null, List.of());
  }

  /** Canonical constructor (tempdoc 655): also wires server-side {@code execute: true} completion. */
  public AuthorizationController(
      ConsentCapsuleAuthority capsuleService,
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingStore,
      io.justsearch.app.services.intent.DurableGrantStore durableGrantStore,
      io.justsearch.agent.api.registry.OperationDispatcher dispatcher,
      List<io.justsearch.agent.api.registry.OperationCatalog> catalogs) {
    this(capsuleService, pendingStore, durableGrantStore, dispatcher, catalogs, null);
  }

  public AuthorizationController(ConsentCapsuleAuthority capsuleService,
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingStore,
      io.justsearch.app.services.intent.DurableGrantStore durableGrantStore,
      io.justsearch.agent.api.registry.OperationDispatcher dispatcher,
      List<io.justsearch.agent.api.registry.OperationCatalog> catalogs,
      io.justsearch.app.api.EngineAdmissionService admission) {
    this.capsuleService = Objects.requireNonNull(capsuleService, "capsuleService");
    this.pendingStore = pendingStore;
    this.durableGrantStore = durableGrantStore;
    this.dispatcher = dispatcher;
    this.catalogs = catalogs == null ? List.of() : List.copyOf(catalogs);
    this.admission = admission;
  }

  /**
   * Handles {@code POST /api/authorizations/approve}.
   *
   * <p>Body: {@code {"pendingId": "..."}}. Consume the backend-created
   * {@link io.justsearch.app.services.intent.PendingAuthorization} and mint a capsule bound
   * to ITS stored {@code (operationId, argsJson)} — the approve caller cannot substitute the
   * op or args. Unknown / expired / already-consumed id → 410 Gone (fail closed).
   *
   * <p>Tempdoc 550 C3/WA-5: there is no arbitrary-{@code (operationId, args)} mint path. A
   * capsule can only be produced by approving a pending the backend created when it actually
   * gated a dispatch, so an in-process agent / prompt-injection cannot self-approve an op the
   * user never saw.
   */
  public void handleApprove(Context ctx) {
    io.justsearch.app.api.EngineWorkHandle executionWork = null;
    try {
      if (pendingStore == null) {
        ctx.status(400)
            .contentType("application/json")
            .result("{\"error\":\"pendingId approval not available\"}");
        return;
      }
      JsonNode body = MAPPER.readTree(ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body());
      JsonNode pendingNode = body.get("pendingId");
      if (pendingNode == null || !pendingNode.isTextual() || pendingNode.asText().isBlank()) {
        ctx.status(400).contentType("application/json").result("{\"error\":\"missing pendingId\"}");
        return;
      }
      boolean execute = body.has("execute") && body.get("execute").asBoolean(false);
      // Reserve the child before consuming the single-use approval. Capacity refusal must leave
      // the pending action available for retry, and the child's attribution is the origin's.
      if (execute && admission != null) {
        var candidate = pendingStore.peek(pendingNode.asText());
        if (candidate.isPresent()) {
          try {
            executionWork = admission.admit(childContext(candidate.get()), false);
          } catch (io.justsearch.app.api.EngineAdmissionException refused) {
            ctx.attribute(RequestEngineWork.REFUSAL_ATTRIBUTE, refused);
            RequestEngineWork.writeRefusal(ctx, refused);
            return;
          }
        }
      }
      var pending = pendingStore.consume(pendingNode.asText());
      if (pending.isEmpty()) {
        // Unknown, already-consumed, or expired — fail closed. 410 Gone distinguishes
        // "the thing you're approving is no longer pending" from a malformed request.
        ctx.status(410)
            .contentType("application/json")
            .result("{\"error\":\"pending authorization not found or expired\"}");
        return;
      }
      // Tempdoc 550 F3: record the authorized action's source tier so an emergency Global Hard
      // Stop revokes only non-user grants — a user's own TRUSTED approval is not cancelled.
      var approved = pending.get();
      String capsule = approved.preparationNonce() == null
          ? capsuleService.mint(approved.operationId(), approved.argsJson(), approved.sourceTier())
          : capsuleService.mintPrepared(approved.operationId(), approved.argsJson(), approved.sourceTier(),
              approved.operationKey(), approved.preparationNonce());
      // Tempdoc 550 thesis IV: an explicit "allow always" gesture records a durable grant for this
      // (operation, sourceTier), so future invocations auto-approve at the gate without re-prompting.
      // Tempdoc 875 C.2: NOT for a HIGH-risk operation — the gate's risk ceiling would ignore such a
      // grant, so minting one would leave an active-looking row that never fires. The FE already
      // hides the checkbox for a HIGH prompt; this is the backend saying the same thing, because the
      // backend is the authority and a direct API caller can still ask.
      boolean allowAlwaysRequested = body.has("allowAlways") && body.get("allowAlways").asBoolean(false);
      boolean allowAlways = allowAlwaysRequested && !isHighRisk(pending.get().operationId());
      if (allowAlways && durableGrantStore != null) {
        durableGrantStore.grantAllowAlways(pending.get().operationId(), pending.get().sourceTier());
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("capsule", capsule);
      payload.put("allowAlways", allowAlways);
      if (pending.get().operationKey() != null) payload.put("operationKey", pending.get().operationKey());
      if (pending.get().preparationNonce() != null) payload.put("preparationNonce", pending.get().preparationNonce().toString());
      // Tempdoc 655: an approval whose origin has no client-side copy of the args to replay
      // (concretely: an MCP-originated pending — the browser was never the caller) asks the
      // server to complete the dispatch itself, right now, using the SAME argsJson the capsule
      // is bound to. Everything else about the gate is unchanged — this re-dispatch goes
      // through enforceTrustLattice exactly like any other, and only proceeds because the
      // capsule just minted from a real approval gesture satisfies it.
      if (execute) {
        executeApprovedPending(payload, pending.get(), capsule,
            executionWork == null ? childContext(pending.get()) : executionWork.context());
      }
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(payload));
    } catch (io.justsearch.app.api.EngineAdmissionException refused) {
      RequestEngineWork.writeRefusal(ctx, refused);
    } catch (Exception e) {
      log.error("Failed to mint consent capsule", e);
      ctx.status(400).contentType("application/json").result("{\"error\":\"bad request\"}");
    } finally {
      if (executionWork != null) executionWork.close();
    }
  }

  private static io.justsearch.core.context.EngineContext childContext(
      io.justsearch.app.services.intent.PendingAuthorization pending) {
    var origin = pending.engineContext();
    return new io.justsearch.core.context.EngineContext(origin.clientKind(), origin.clientId(),
        origin.sessionId(), origin.grantReference(), origin.sourceTier(), origin.transport(),
        origin.survival(), origin.urgency());
  }

  /**
   * Handles {@code GET /api/authorizations/pending/{id}} — tempdoc 655 fix pass.
   *
   * <p>Point-to-point fetch of a pending's decision content (operation id, args summary, risk,
   * gate behavior, rationale) by id, for a caller that learned the id exists via the
   * pending-authorization SSE broadcast (which deliberately carries none of this — see {@link
   * io.justsearch.app.observability.operations.PendingAuthorizationEvent}) rather than a live
   * 428 response body (which already carries it inline). Mirrors the SAME privacy scoping the
   * 428 path already has — content is exposed only to a caller that already holds the specific
   * id, one at a time — just reachable out-of-band instead of embedded in a response the caller
   * happened to be waiting on.
   *
   * <p>Non-mutating: uses {@link io.justsearch.app.services.intent.PendingAuthorizationStore#peek}
   * (not {@code consume}) — looking up a pending's content does not approve it.
   */
  public void handlePeekPending(Context ctx) {
    if (pendingStore == null) {
      ctx.status(404).contentType("application/json").result("{\"error\":\"pending authorization not found\"}");
      return;
    }
    String id = ctx.pathParam("id");
    var pending = pendingStore.peek(id);
    if (pending.isEmpty()) {
      // Unknown, expired, or already consumed — 404, matching handleApprove's fail-closed shape
      // for an id that no longer resolves to anything (410 there is "was valid, now isn't"; 404
      // here also covers "never existed" for a GET, so 404 rather than 410 is the better fit).
      ctx.status(404).contentType("application/json").result("{\"error\":\"pending authorization not found or expired\"}");
      return;
    }
    var p = pending.get();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("pendingId", p.id());
    payload.put("operationId", p.operationId());
    payload.put("argsSummary", ArgsSummary.summarize(p.argsJson()));
    payload.put("sourceTier", p.sourceTier().name());
    payload.put("riskTier", p.riskTier().name());
    payload.put("gateBehavior", p.gateBehavior().name());
    payload.put("rationale", p.rationale());
    // Tempdoc 807 item 3: pendings DO expire (PendingAuthorizationStore.DEFAULT_TTL, 5 min) and
    // {@link PendingAuthorization} has carried expiresAt since it was written — but no surface put
    // it on the wire, so no client could say how long an approval request is valid and no round
    // could deterministically induce or verify expiry (sandbox round 13 F3: the
    // expired-pending-approval ceremony was UNPERFORMABLE as written). Additive, ISO-8601 UTC.
    payload.put("expiresAt", p.expiresAt().toString());
    // Tempdoc 655: display-only — omitted (not present as a key) when absent, so the FE's
    // optional-field pattern (undefined ⇒ don't render the line) works without a null-vs-missing
    // distinction on the wire.
    if (p.requestedBy() != null) {
      payload.put("requestedBy", p.requestedBy());
    }
    try {
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      log.error("Failed to serialize pending authorization {}", id, e);
      ctx.status(500).contentType("application/json").result("{\"error\":\"serialization error\"}");
    }
  }

  /**
   * Tempdoc 655: complete the just-approved pending's dispatch server-side and record the
   * outcome onto {@code payload}. Best-effort — a failure here doesn't undo the approval or
   * the minted capsule; it's surfaced as {@code executed: false} with an explanatory message so
   * the caller (the SSE-triggered approval flow) can tell the user, rather than silently
   * discarding the capsule and leaving the action unexplained-incomplete.
   */
  private void executeApprovedPending(
      Map<String, Object> payload,
      io.justsearch.app.services.intent.PendingAuthorization pending,
      String capsule, io.justsearch.core.context.EngineContext executionContext) {
    if (dispatcher == null) {
      payload.put("executed", false);
      payload.put("executeMessage", "Server-side execution is not available in this deployment.");
      return;
    }
    io.justsearch.agent.api.registry.Operation op = resolveOperation(pending.operationId()).orElse(null);
    if (op == null) {
      payload.put("executed", false);
      payload.put("executeMessage", "Operation not available: " + pending.operationId());
      return;
    }
    try {
      // Tempdoc 655: reuse the ORIGIN provenance shape (MCP), not the browser's own — the
      // re-dispatch must still pass through the same UNTRUSTED-tier gate the original call did;
      // the capsule (proof of a real approval gesture) is what satisfies it, not a transport
      // upgrade. Matches the FE's own invokeWithConsent, which re-invokes with the SAME
      // request shape it originally used, capsule added.
      io.justsearch.agent.api.registry.InvocationProvenance provenance =
          pending.provenance();
      io.justsearch.agent.api.registry.OperationResult result;
      if (pending.undo()) {
        String executionId = MAPPER.readTree(pending.argsJson()).path("executionId").asText();
        result = pending.preparationNonce() != null
            ? dispatcher.undo(op, executionId, provenance, java.util.Optional.of(capsule),
                executionContext, pending.operationKey(), pending.preparationNonce())
            : pending.operationKey() == null
            ? dispatcher.undo(op, executionId, provenance, java.util.Optional.of(capsule), executionContext)
            : dispatcher.undo(op, executionId, provenance, java.util.Optional.of(capsule),
                executionContext, pending.operationKey());
      } else {
        result = pending.preparationNonce() != null
            ? dispatcher.dispatch(op, pending.argsJson(), provenance, java.util.Optional.of(capsule),
                executionContext, pending.operationKey(), pending.preparationNonce())
            : pending.operationKey() == null
            ? dispatcher.dispatch(op, pending.argsJson(), provenance, java.util.Optional.of(capsule), executionContext)
            : dispatcher.dispatch(op, pending.argsJson(), provenance, java.util.Optional.of(capsule),
                executionContext, pending.operationKey());
      }
      payload.put("executed", true);
      payload.put("executeSuccess", result.success());
      payload.put("executeMessage", result.message());
      if (result.structuredData().get("operationKey") instanceof String key) {
        payload.put("operationKey", key);
        payload.put("operationRecordId", result.structuredData().get("operationRecordId"));
      }
    } catch (io.justsearch.agent.api.encryption.KeyLockedException failure) {
      var response = io.justsearch.app.api.registry.OperationInvocationResponse.fromLockedStore();
      payload.put("executed", false);
      payload.put("executeSuccess", false);
      payload.put("executeMessage", response.message());
      payload.put("executeErrorClass", response.errorClass());
      payload.put("executeErrorCode", response.errorCode());
      payload.put("executeRetryable", response.retryable());
    } catch (io.justsearch.app.api.operations.OperationStoreException failure) {
      var response = io.justsearch.app.api.registry.OperationInvocationResponse.fromStoreFailure(failure);
      payload.put("executed", false);
      payload.put("executeSuccess", false);
      payload.put("executeMessage", response.message());
      payload.put("executeErrorClass", response.errorClass());
      payload.put("executeErrorCode", response.errorCode());
      payload.put("executeRetryable", response.retryable());
    } catch (Exception e) {
      log.warn("Tempdoc 655: server-side execution of approved pending {} failed", pending.id(), e);
      payload.put("executed", false);
      payload.put(
          "executeMessage",
          "Approved, but execution failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
  }

  // ── Durable-grant management surface (tempdoc 560 §28 / 4d) ────────────────────────────────────────
  // GET /api/authorizations/grants — list; POST — grant (operation|family); DELETE — revoke. The
  // loopback-only API makes these inherently operator-local; they are the management surface for the
  // durable allow-always grants the gate honors (the per-op and the wider CapabilityFamily position).

  /** GET /api/authorizations/grants — the operator's current durable grants (operation + family). */
  public void handleListGrants(Context ctx) {
    if (durableGrantStore == null) {
      ctx.json(Map.of("grants", List.of()));
      return;
    }
    List<Map<String, Object>> grants =
        durableGrantStore.snapshot().stream()
            .map(
                g -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("kind", g.kind().name());
                  m.put("target", g.target());
                  m.put("sourceTier", g.sourceTier().name());
                  return m;
                })
            .toList();
    ctx.json(Map.of("grants", grants));
  }

  /** POST /api/authorizations/grants {kind, target, sourceTier} — record a durable allow-always grant. */
  public void handleGrant(Context ctx) {
    GrantRequest req = parseGrantRequest(ctx);
    if (req == null) return; // parseGrantRequest already wrote the error
    // Tempdoc 875 C.2: an operation grant on a HIGH-risk operation is inert at the gate (the risk
    // ceiling refuses it), so recording one would list an active-looking grant that never fires —
    // exactly the lying control this tempdoc removed from the approve dialog. Refuse it here instead.
    // A FAMILY grant is still accepted even when the family contains a HIGH member: it keeps working
    // for the family's MEDIUM members, which is what 560 §28's axis is for.
    if (!req.family() && isHighRisk(req.target())) {
      ctx.status(400)
          .json(
              Map.of(
                  "error",
                  "\""
                      + req.target()
                      + "\" is a HIGH-risk operation; a durable allow-always grant never covers one."
                      + " Destructive operations require a fresh confirmation each time."));
      return;
    }
    if (req.family()) {
      durableGrantStore.grantFamilyAllowAlways(req.target(), req.tier());
    } else {
      durableGrantStore.grantAllowAlways(req.target(), req.tier());
    }
    ctx.json(Map.of("granted", true));
  }

  /** DELETE /api/authorizations/grants {kind, target, sourceTier} — revoke a durable grant. */
  public void handleRevokeGrant(Context ctx) {
    GrantRequest req = parseGrantRequest(ctx);
    if (req == null) return;
    if (req.family()) {
      durableGrantStore.revokeFamily(req.target(), req.tier());
    } else {
      durableGrantStore.revoke(req.target(), req.tier());
    }
    ctx.json(Map.of("revoked", true));
  }

  private record GrantRequest(boolean family, String target, SourceTier tier) {}

  /** Parses + validates {kind, target, sourceTier}; writes a 400 + returns null on any failure. */
  private GrantRequest parseGrantRequest(Context ctx) {
    if (durableGrantStore == null) {
      ctx.status(400).json(Map.of("error", "durable grants unavailable"));
      return null;
    }
    try {
      String raw = ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body();
      var body = MAPPER.readTree(raw);
      String kind = textField(body, "kind");
      String target = textField(body, "target");
      SourceTier tier = parseTier(textField(body, "sourceTier"));
      boolean family = "FAMILY".equalsIgnoreCase(kind);
      if (kind == null || target == null || target.isBlank() || tier == null) {
        ctx.status(400)
            .json(
                Map.of(
                    "error",
                    "kind ('OPERATION'|'FAMILY'), target, and sourceTier"
                        + " ('TRUSTED'|'MEDIUM'|'UNTRUSTED') are required"));
        return null;
      }
      return new GrantRequest(family, target, tier);
    } catch (Exception e) {
      ctx.status(400).json(Map.of("error", "malformed JSON body"));
      return null;
    }
  }

  private static String textField(JsonNode node, String field) {
    var v = node.get(field);
    return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
  }

  /**
   * Resolve an operation by wire name or dotted id across the injected catalogs. {@code
   * findByWireName} already falls back to {@code findByIdValue}, so both forms work.
   */
  private java.util.Optional<io.justsearch.agent.api.registry.Operation> resolveOperation(
      String idOrWireName) {
    if (idOrWireName == null || idOrWireName.isBlank()) {
      return java.util.Optional.empty();
    }
    for (var catalog : catalogs) {
      var hit = catalog.findByWireName(idOrWireName);
      if (hit.isPresent()) {
        return hit;
      }
    }
    return java.util.Optional.empty();
  }

  /**
   * Tempdoc 875 C.2 — is this operation one a durable grant can never cover? Unresolvable is NOT
   * treated as HIGH: a grant may legitimately be recorded for an operation this process cannot see
   * yet (a late-registered handler, an MCP contribution), and the gate applies the real ceiling from
   * the live {@code OperationPolicy} at dispatch time regardless of what was recorded here. So this
   * check removes the lying control where the risk IS known; the enforcement itself is the gate's.
   */
  private boolean isHighRisk(String idOrWireName) {
    return resolveOperation(idOrWireName)
        .map(op -> op.policy().risk() == io.justsearch.agent.api.registry.RiskTier.HIGH)
        .orElse(false);
  }

  private static SourceTier parseTier(String raw) {
    if (raw == null) return null;
    try {
      return SourceTier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
