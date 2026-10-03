/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.app.services.conversation.AgUiEventTranslator;
import io.justsearch.app.services.conversation.AgentRunShape;
import io.justsearch.app.services.conversation.ConversationEngine;
import java.util.Set;
import io.justsearch.telemetry.Telemetry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST controller for the agent capability's LIVE-RUN + per-run-control surface: starting/attaching/
 * resuming an agent run, the unified approval/gate dispatch, and the per-run control inputs
 * (autonomy, budget, context, steering, cancel).
 *
 * <p>Tempdoc 585 §B.5 (Hybrid C structural remedy): this class was the repo's #3 class-size offender
 * (1131 LOC). Three cohesive concerns were lifted out to halt the accretion trajectory before it
 * became a second LocalApiServer:
 * <ul>
 *   <li>the {@code AgentEvent → SSE-wire} vocabulary + the run-observer eviction seam →
 *       {@link AgentSseWriter} (the keystone — the biggest mass and an accretor in its own right);
 *   <li>the 8 pure-read session/history handlers → {@link AgentSessionController} (narrowed to the
 *       {@code AgentRunQueries} read interface tempdoc 584 segregated);
 *   <li>the tool inventory + FE virtual-operations sidecar → {@link AgentToolsController}.
 * </ul>
 * What remains here is the run/control core: it needs the full loop+control {@link AgentService} and
 * the {@link AgentSseWriter}. All endpoint contracts (paths, request/response shapes, SSE framing,
 * audience/authorization) are preserved exactly.
 *
 * <p>Tempdoc 374 alpha.25 U14-B + slice 1.1.a merge integration: the agent service is
 * resolved live via a {@link Supplier} so the controller reflects late-bound activation
 * (e.g., the "Reload Brain" path) instead of capturing a stale unavailable instance at
 * construction time.
 *
 * <p>Tempdoc 491 Phase B (2026-05-12): {@code POST /api/chat/agent} routes through the
 * {@link ConversationEngine} via the {@code AgentRunShape}. The agent loop's body
 * ({@code AgentLoopService}) is encapsulated unchanged behind a {@code ToolIteratingShapeRunner};
 * the SSE wire vocabulary is preserved exactly.
 */
final class AgentController {
  private static final Logger LOG = LoggerFactory.getLogger(AgentController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String AUDIENCE_HEADER = "X-JustSearch-Audience";

  /**
   * Tempdoc 508-followup §β1 — whitelist of shape ids dispatchable through {@code
   * /api/chat/agent}. The endpoint historically ran exactly the agent shape and ignored
   * {@code body.shapeId}; honoring the field would silently broaden what callers can invoke.
   * The whitelist is a forward-compat seam — a new shape that wants to share this endpoint
   * (vs. its own controller) registers here. The engine's catalog also validates, but the
   * controller gate produces a clearer 400 with the offending value.
   */
  private static final Set<String> ALLOWED_SHAPE_IDS =
      Set.of(
          AgentRunShape.ID.value(),
          // Tempdoc 560 Phase 2 — the workflow shape shares this endpoint (engine.run dispatches
          // by shapeId); its tool-call/approval wire vocabulary matches the agent shape's.
          io.justsearch.app.services.conversation.WorkflowRunShape.ID.value());

  private final Supplier<AgentService> agentServiceSupplier;
  private final ConversationEngine engine;
  private final AgentSseWriter sseWriter;
  private final Telemetry telemetry;

  /**
   * Tempdoc 604 — the agent SSE streams (live run / attach / resume / fork) are event-only and emit
   * NOTHING while a run is parked at an approval gate, so the FE cannot tell a parked-alive run from
   * a transport-hung one. Beats an out-of-band {@code heartbeat} frame on every open agent stream at
   * the generated cadence, the positive-liveness signal the FE watchdog resets on. The mechanism
   * lives in {@link SseHeartbeat} because {@code POST /api/chat/dispatch} needs the same one (859 D
   * live-defect D2).
   */
  private final SseHeartbeat heartbeat;

  // Tempdoc 560 Phase 2: pending-gate registry for workflow runs (nullable; set post-construction
  // once the WorkflowShapeRunner is wired). The workflow approve/reject endpoints complete its
  // futures out-of-band, unblocking the runner thread.
  private io.justsearch.app.services.conversation.WorkflowGateRegistry workflowGateRegistry;

  AgentController(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      Supplier<AgentService> agentServiceSupplier,
      ConversationEngine engine,
      AgentSseWriter sseWriter,
      Telemetry telemetry) {
    this.agentServiceSupplier = agentServiceSupplier;
    this.engine = engine;
    this.sseWriter = sseWriter;
    this.telemetry = telemetry;
    // A LAMBDA, not `sseWriter::writeEvent`: a method reference binds (and null-checks) its receiver
    // at construction, and this controller is legitimately constructed with a null writer in the
    // lifecycle tests that only exercise shutdown. Deferring the dereference to the beat itself
    // keeps construction as total as it was before the heartbeat moved out of this class.
    this.heartbeat =
        new SseHeartbeat(
            executors,
            (ctx, event, payload) -> sseWriter.writeEvent(ctx, event, payload),
            "agent-stream-heartbeat");
  }

  /** Resolves the live agent service. Always re-fetches so late-bound updates surface. */
  private AgentService agentService() {
    return agentServiceSupplier.get();
  }

  /** Tempdoc 604 — run a blocking agent stream with an out-of-band liveness heartbeat. */
  private void withHeartbeat(Context ctx, SseHeartbeat.StreamBody body) throws Exception {
    heartbeat.around(ctx, body);
  }

  /** Stops the heartbeat scheduler. Call on shutdown. */
  void shutdown() {
    heartbeat.shutdown();
  }

  /** Test-only (tempdoc 638 PE): whether {@link #shutdown()} has stopped the heartbeat scheduler. */
  boolean isHeartbeatSchedulerShutdown() {
    return heartbeat.isShutdown();
  }

  /**
   * POST /api/chat/agent — Start an agent session and stream events.
   *
   * <p>Tempdoc 491 Phase B: this handler is now a thin shape-binding around {@link
   * ConversationEngine#run}. It performs the same pre-flight availability check (HTTP 503
   * with structured JSON error preserved for FE compatibility), parses the body as an
   * opaque Map, reads the audience header, and delegates to the engine via the
   * {@code AgentRunShape}. The agent-loop's existing event vocabulary is preserved exactly
   * by {@code ToolIteratingShapeRunner.translate}.
   */
  void handleRunStream(Context ctx) {
    if (!agentService().isAvailable()) {
      ctx.status(503).json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Agent capability is not available", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    // Tempdoc 863 §4.A.5 (F3) — the locked-store refusal, asked BEFORE any SSE status is committed,
    // exactly as the two sibling dispatch routes ask it (ChatController's `/api/chat/dispatch` and
    // RunStreamController's `POST /api/chat/runs`). This route could skip it while a delegate run had
    // no conversation-store write key; declaring `core.agent-run` recordsToThread gives it one, so
    // without this the engine's user-turn append would throw INTO the already-committed stream and the
    // reader would get a generic BAD_REQUEST SSE error instead of the typed, actionable locked answer.
    if (refuseWhileLocked(ctx)) {
      return;
    }

    sseWriter.initSseHeaders(ctx, "/api/chat/agent");

    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = MAPPER.readValue(ctx.body(), Map.class);
      Audience audience = readAudience(ctx);
      ConversationShapeRef shapeRef = resolveShapeRef(body);
      withHeartbeat(
          ctx,
          () ->
              engine.run(
                  shapeRef,
                  body,
                  audience,
                  // Tempdoc 577 §2.14 Root I (#13) — the initiating run observer EVICTS on
                  // disconnect (it throws, so the run drops it), the precondition the
                  // zero-observer park needs.
                  sseEvent -> sseWriter.writeOrEvict(ctx, sseEvent.name(), sseEvent.payload()), RequestEngineContext.agent(ctx)));
    } catch (UnknownShapeException unknown) {
      LOG.warn("Rejecting agent run for unknown shapeId: {}", unknown.getMessage());
      Map<String, Object> errPayload = new LinkedHashMap<>();
      errPayload.put("error", unknown.getMessage());
      errPayload.put("errorCode", ApiErrorCode.BAD_REQUEST.name());
      errPayload.put("errorClass", ApiErrorCode.BAD_REQUEST.errorClass().name());
      errPayload.put("retryable", false);
      sseWriter.writeEvent(ctx, "error", errPayload);
    } catch (Exception e) {
      LOG.error("Failed to start agent session", e);
      Map<String, Object> errPayload = new LinkedHashMap<>();
      errPayload.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
      errPayload.put("errorCode", ApiErrorCode.BAD_REQUEST.name());
      errPayload.put("errorClass", ApiErrorCode.BAD_REQUEST.errorClass().name());
      errPayload.put("retryable", ApiErrorCode.BAD_REQUEST.isRetryable());
      sseWriter.writeEvent(ctx, "error", errPayload);
    }
  }

  /**
   * Tempdoc 508-followup §β1 — resolve the shape ref from {@code body.shapeId}. Missing /
   * blank / non-string values default to {@link AgentRunShape#ID} for back-compat with
   * pre-§β1 clients (and with the historical hardcoded behavior). Any other value must
   * appear in {@link #ALLOWED_SHAPE_IDS} or this throws {@link UnknownShapeException},
   * which the caller translates into a structured 400 error SSE event.
   *
   * <p>The plan flagged this fix as "host.ai shape-dispatch honesty": prior to this change
   * the controller silently ignored {@code body.shapeId} and always ran {@code AgentRunShape},
   * so any plugin passing a different id was lied to about which shape ran.
   */
  static ConversationShapeRef resolveShapeRef(Map<String, Object> body) {
    Object raw = body == null ? null : body.get("shapeId");
    if (raw == null) {
      return AgentRunShape.ID;
    }
    String value = raw.toString().trim();
    if (value.isEmpty()) {
      return AgentRunShape.ID;
    }
    if (!ALLOWED_SHAPE_IDS.contains(value)) {
      throw new UnknownShapeException(
          "unknown shapeId: '" + value + "'. Allowed: " + ALLOWED_SHAPE_IDS);
    }
    return new ConversationShapeRef(value);
  }

  /**
   * Tempdoc 863 §4.A.5 (F3) — answer {@code 423 Locked} when this dispatch WOULD record turns to a
   * conversation store that is encrypted and locked, so every append throws and the turn is
   * accepted-and-dropped. Same authority as the sibling routes ({@code ConversationEngine
   * .wouldDiscardWhileLocked}), asked from the one point on this route where a status can still be
   * set — before {@code initSseHeaders} commits the 200.
   *
   * <p>Returns {@code false} for a body this method cannot read. A malformed body is not a lock
   * condition, and the existing handler already answers it with its structured error event; failing
   * open here keeps that path exactly as it was rather than converting a parse error into a lock one.
   */
  private boolean refuseWhileLocked(Context ctx) {
    final Map<String, Object> body;
    final ConversationShapeRef shapeRef;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = MAPPER.readValue(ctx.body(), Map.class);
      body = parsed;
      shapeRef = resolveShapeRef(body);
    } catch (Exception ignored) {
      return false;
    }
    if (!engine.wouldDiscardWhileLocked(shapeRef, body)) {
      return false;
    }
    LOG.info("Refusing agent run of shape {}: conversation store is locked", shapeRef.value());
    Map<String, Object> locked =
        ApiErrorHandler.toResponse(
            ApiErrorCode.STORE_LOCKED,
            "Your chat history is encrypted and locked - unlock it to send a message.");
    locked.put("locked", true);
    ctx.status(423).json(locked);
    return true;
  }

  /** Internal signal: client requested a shapeId outside the whitelist. */
  static final class UnknownShapeException extends RuntimeException {
    UnknownShapeException(String message) {
      super(message);
    }
  }

  /**
   * Reads the {@code X-JustSearch-Audience} request header per tempdoc 491 §5.4. Default
   * {@link Audience#USER} when missing or unparseable. The header value is uppercased before
   * matching the enum; unknown values fall back to USER (forward-compat — a future audience
   * value should not break old clients).
   */
  private static Audience readAudience(Context ctx) {
    String raw = ctx.header(AUDIENCE_HEADER);
    if (raw == null || raw.isBlank()) {
      return Audience.USER;
    }
    try {
      return Audience.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      LOG.debug("Unrecognized {} header value {}; defaulting to USER", AUDIENCE_HEADER, raw);
      return Audience.USER;
    }
  }

  /**
   * POST /api/chat/approve — Tempdoc 565 §15.C enforcement: the ONE approval endpoint. "A run is a
   * run" all the way down — the shared {@code <jf-authorization-host>} ceremony routes here for BOTH
   * an agent tool-call gate and a workflow GateStep/ToolStep gate. The dispatch tries the agent gate
   * (keyed by {@code sessionId}+{@code callId}) first, then the session-agnostic
   * {@link io.justsearch.app.services.conversation.WorkflowGateRegistry} (keyed by {@code callId}),
   * else 404 — so the FE no longer branches the URL by run shape.
   */
  void handleApprove(Context ctx) {
    dispatchGate(ctx, true);
  }

  /** POST /api/chat/reject — reject sibling of {@link #handleApprove} (same unified dispatch). */
  void handleReject(Context ctx) {
    dispatchGate(ctx, false);
  }

  /** GET /api/chat/approval — private display from the live owner, never a durable event projection. */
  void handleApproval(Context ctx) {
    ctx.header("Cache-Control", "no-store");
    String callId = ctx.queryParam("callId");
    if (callId == null || callId.isBlank()) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST,
          "callId is required", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    var approval = agentService().pendingToolApproval(ctx.queryParam("sessionId"), callId);
    if (approval.isEmpty() && workflowGateRegistry != null) {
      approval = workflowGateRegistry.pendingToolApproval(callId);
    }
    if (approval.isEmpty()) {
      ctx.status(404).json(ApiErrorHandler.toResponse(ApiErrorCode.NOT_FOUND,
          "No pending approval gate", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    var pending = approval.orElseThrow();
    var detail = pending.detail();
    var response = new LinkedHashMap<String, Object>();
    response.put("callId", detail.callId());
    response.put("operationId", detail.toolName());
    response.put("gateBehavior", detail.gateBehavior() == null ? null
        : detail.gateBehavior().toUpperCase(Locale.ROOT));
    response.put("riskTier", detail.risk() == null ? null : detail.risk().toUpperCase(Locale.ROOT));
    response.put("argsSummary", pending.preview().map(io.justsearch.agent.api.registry.OperationApprovalPreview::summary)
        .orElseGet(() -> ArgsSummary.summarize(detail.arguments())));
    ctx.json(response);
  }

  /**
   * The unified gate-dispatch DECISION (Context-free, unit-testable): try the agent gate
   * ({@code sessionId}+{@code callId}) first, then the session-agnostic workflow gate registry
   * ({@code callId}). Returns whether SOME gate with that callId was found and completed — the HTTP
   * mapping (200 vs 404) lives in {@link #dispatchGate}. This is the single backend authority the two
   * forked endpoints collapsed into (565 §15.C).
   */
  boolean resolveApprovalGate(String sessionId, String callId, boolean approved, String reason) {
    // 1) Agent gate (within the session). Returns whether a gate with that callId actually existed.
    boolean handled =
        approved
            ? agentService().tryApproveToolCall(sessionId, callId)
            : agentService().tryRejectToolCall(sessionId, callId, reason);
    // 2) Else the session-agnostic workflow gate registry (keyed by callId).
    if (!handled && workflowGateRegistry != null) {
      handled = workflowGateRegistry.complete(callId, approved);
    }
    return handled;
  }

  private void dispatchGate(Context ctx, boolean approved) {
    try {
      var body = MAPPER.readTree(ctx.body());
      String callId = body.path("callId").asText();
      String sessionId = body.path("sessionId").asText(null);
      String reason = body.path("reason").asText("User rejected");
      boolean handled = resolveApprovalGate(sessionId, callId, approved, reason);
      if (!handled) {
        ctx.status(404)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.NOT_FOUND,
                    "No pending approval gate for callId " + callId,
                    telemetry,
                    ApiErrorHandler.routeOf(ctx)));
        return;
      }
      ctx.json(Map.of("status", approved ? "approved" : "rejected"));
    } catch (Exception e) {
      LOG.error("Failed to resolve approval gate", e);
      ctx.status(400)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INVALID_REQUEST, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Tempdoc 560 Phase 2 — late-bind the workflow pending-gate registry (nullable). Consumed by the
   * unified {@link #dispatchGate} as the second gate authority (after the agent gate).
   */
  void setWorkflowGateRegistry(
      io.justsearch.app.services.conversation.WorkflowGateRegistry registry) {
    this.workflowGateRegistry = registry;
  }

  /**
   * POST /api/chat/agent/autonomy — Tempdoc 561 P-D: update the live autonomy dial for a running
   * session. Body: {@code {"sessionId": "...", "level": "watch|assist|auto"}}. The backend issuance
   * policy reads the session's dial on the next gated tool call; the FE no longer re-derives.
   */
  void handleAutonomy(Context ctx) {
    handleControl(ctx, "Failed to set session autonomy", body -> {
      agentService().setSessionAutonomy(body.path("sessionId").asText(), body.path("level").asText());
      ctx.json(Map.of("status", "ok"));
    });
  }

  /**
   * Tempdoc 585 §D Phase 2 (A2) — the shared per-run control envelope: parse the JSON body, run the
   * endpoint's {@code action}, and map a thrown exception to the structured 400 every control handler
   * used to repeat. The action writes its own success / pre-validation-400 / 404 response (the part
   * that genuinely differs per endpoint). DRYs handleAutonomy/RaiseBudget/Steering/Budget-/Context-
   * Decision on the now-small post-585 controller.
   */
  private void handleControl(Context ctx, String failureLog, ThrowingConsumer<JsonNode> action) {
    try {
      action.accept(MAPPER.readTree(ctx.body()));
    } catch (Exception e) {
      LOG.error(failureLog, e);
      ctx.status(400)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INVALID_REQUEST, e.getMessage(), telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Tempdoc 585 §D Phase 2 (A2) — the shared gate→response map the 4 boolean-returning control
   * handlers repeat: HTTP 200 {@code {status: okStatus}} when the delegate accepted, else 404
   * {@code {error: notFound}} (the run is not live / not parked at the gate).
   */
  private static void okOr404(Context ctx, boolean ok, String okStatus, String notFound) {
    if (ok) {
      ctx.json(Map.of("status", okStatus));
    } else {
      ctx.status(404).json(Map.of("error", notFound));
    }
  }

  /** A body-consuming control action that may throw (mapped to a 400 by {@link #handleControl}). */
  @FunctionalInterface
  private interface ThrowingConsumer<T> {
    void accept(T t) throws Exception;
  }

  /**
   * POST /api/chat/agent/budget — Tempdoc 577 Ext III (the over-budget remedy): grant a running
   * session additional tokens. Body: {@code {"sessionId": "...", "addTokens": N}}. The loop's
   * between-step budget check and the next {@code budget_update} reflect the raise. Mirrors
   * {@link #handleAutonomy}/{@link #handleSteeringDirective} (per-run control inputs). 404 if the
   * session is unknown/finished.
   */
  void handleRaiseBudget(Context ctx) {
    handleControl(ctx, "Failed to raise session budget", body -> {
      String sessionId = body.path("sessionId").asText();
      int addTokens = body.path("addTokens").asInt(0);
      if (sessionId.isBlank() || addTokens <= 0) {
        ctx.status(400).json(Map.of("error", "sessionId and a positive addTokens are required"));
        return;
      }
      okOr404(
          ctx,
          agentService().raiseSessionBudget(sessionId, addTokens),
          "ok",
          "Session not found or not running: " + sessionId);
    });
  }

  /**
   * POST /api/chat/agent/budget-decision — Tempdoc 577 §2.12 Move 2: resolve a HELD budget gate.
   * Body: {@code {"sessionId": "...", "decision": "finalize"|"stop"}}. CONTINUE arrives via the
   * raise endpoint (raising the budget IS the continue decision). Mirrors approve/reject: 404 when
   * the run is not parked at a gate.
   */
  void handleBudgetDecision(Context ctx) {
    handleControl(ctx, "Failed to resolve budget gate", body -> {
      String sessionId = body.path("sessionId").asText();
      String decision = body.path("decision").asText();
      if (sessionId.isBlank() || decision.isBlank()) {
        ctx.status(400).json(Map.of("error", "sessionId and decision are required"));
        return;
      }
      okOr404(
          ctx,
          agentService().resolveBudgetGate(sessionId, decision),
          "ok",
          "No held budget gate for session: " + sessionId);
    });
  }

  /**
   * POST /api/chat/agent/context-decision — Tempdoc 577 §2.14 Root II (#14): resolve a HELD
   * context-pressure gate. Body: {@code {"sessionId": "...", "decision": "continue"|"summarize"|"stop"}}.
   * Mirrors the budget-decision endpoint: 404 when the run is not parked at a context gate.
   */
  void handleContextDecision(Context ctx) {
    handleControl(ctx, "Failed to resolve context gate", body -> {
      String sessionId = body.path("sessionId").asText();
      String decision = body.path("decision").asText();
      if (sessionId.isBlank() || decision.isBlank()) {
        ctx.status(400).json(Map.of("error", "sessionId and decision are required"));
        return;
      }
      okOr404(
          ctx,
          agentService().resolveContextGate(sessionId, decision),
          "ok",
          "No held context gate for session: " + sessionId);
    });
  }

  /**
   * GET /api/chat/agent/{sessionId}/attach — Tempdoc 577 §2.14 Root I (#13): ATTACH a new SSE
   * observer to a LIVE run. The run is a backend-owned entity (its loop survives the initiating
   * socket closing), so a reattaching tab — or a second observer — subscribes to the run's event
   * observation channel: it receives the act-on-the-run primer, then the replay, then ongoing frames in the SAME wire
   * vocabulary the initiating run stream uses (via {@link AgentSseWriter#writeAgentEvent}). Blocks
   * until the run terminates. When the session is not live (terminal/evicted), emits a
   * {@code not_live} signal so the FE falls back to the persisted thread record instead.
   */
  void handleAttachStream(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    sseWriter.initSseHeaders(ctx, "/api/chat/agent/attach");
    try {
      // Tempdoc 834 §1.6 — the resume cursor is the CHANNEL sequence, read from ?sinceSeq=. The
      // pre-834 Last-Event-ID cursor is retired: run streams do not emit `id:` at all, so it cannot
      // become a second, unvalidated resume channel beside the query parameter. Absent ⇒ 0 ⇒ replay
      // everything, which is the guaranteed path (the window rule rejects only a cursor ahead of the
      // stream or behind the retained window).
      long sinceSeq = parseSinceSeq(ctx.queryParam(RunStreamWriter.CURSOR_PARAM));
      withHeartbeat(
          ctx,
          () -> {
            boolean attached =
                agentService()
                    .attachToRun(sessionId, sinceSeq, frame -> sseWriter.writeWireFrame(ctx, frame));
            if (!attached) {
              // Not an in-flight run — signal the FE to read the persisted record
              // (events.ndjson / thread).
              Map<String, Object> notLive = new LinkedHashMap<>();
              notLive.put("sessionId", sessionId);
              notLive.put("reason", "not-live");
              sseWriter.writeEvent(ctx, "attach_not_live", notLive);
            }
          });
    } catch (Exception e) {
      LOG.error("Attach stream failed for session {}", sessionId, e);
      Map<String, Object> errPayload = new LinkedHashMap<>();
      errPayload.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
      errPayload.put("errorCode", ApiErrorCode.BAD_REQUEST.name());
      errPayload.put("retryable", false);
      sseWriter.writeEvent(ctx, "error", errPayload);
    }
  }

  /**
   * POST /api/chat/agent/{sessionId}/ag-ui — Tempdoc 585 §D Phase 3 (C3): attach an AG-UI-protocol
   * observer to a LIVE run. Identical attach mechanics to {@link #handleAttachStream} (the run's channel,
   * the Last-Event-ID cursor, the disconnect-eviction), but each event is projected through the
   * sibling {@link AgUiEventTranslator} instead of the native one — so the SAME run streams as AG-UI
   * events to an AG-UI client (CopilotKit, etc.). Stays under {@code /api/chat/*}: the removed
   * {@code /api/agent/*} namespace is NOT resurrected (Hard Invariant #3).
   */
  void handleAgUiAttachStream(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    sseWriter.initSseHeaders(ctx, "/api/chat/agent/ag-ui");
    try {
      long sinceSeq = parseSinceSeq(ctx.queryParam(RunStreamWriter.CURSOR_PARAM));
      boolean attached =
          agentService()
              .attachToRun(
                  sessionId,
                  sinceSeq,
                  frame -> {
                    // Tempdoc 834 §6.5 — the journal carries the projected pair, so the AG-UI
                    // projection re-derives from the payload keys. That second hand-written switch
                    // is real drift surface; the equivalence gate in
                    // AgUiEventTranslatorConformanceTest is its whole mitigation.
                    var agui = AgUiEventTranslator.translateFromMap(frame.name(), frame.payload());
                    sseWriter.writeOrEvict(ctx, agui.name(), agui.payload());
                  });
      if (!attached) {
        // AG-UI signals a non-live run with a RUN_ERROR (its terminal-error event).
        Map<String, Object> notLive = new LinkedHashMap<>();
        notLive.put("type", "RUN_ERROR");
        notLive.put("message", "not a live run");
        sseWriter.writeEvent(ctx, "RUN_ERROR", notLive);
      }
    } catch (Exception e) {
      LOG.error("AG-UI attach stream failed for session {}", sessionId, e);
      Map<String, Object> errPayload = new LinkedHashMap<>();
      errPayload.put("type", "RUN_ERROR");
      errPayload.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
      sseWriter.writeEvent(ctx, "RUN_ERROR", errPayload);
    }
  }

  /**
   * Tempdoc 834 §1.6 — parse the {@code ?sinceSeq=} channel cursor. Absent, negative or unparseable
   * yields 0, which replays everything and always succeeds.
   *
   * <p>This REPLACES the pre-834 {@code Last-Event-ID} parse. Probe P7 confirmed nothing produced
   * that header — {@code consumeShapeStream} sends only the content type and the session token, and
   * the FE attach passes no headers — so it was an unvalidated resume input with no producer. §1.6
   * closes the door by not emitting {@code id:} at all: one resume input, one grammar.
   */
  private static long parseSinceSeq(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0L;
    }
    try {
      return Math.max(0L, Long.parseLong(raw.trim()));
    } catch (NumberFormatException notNumeric) {
      return 0L;
    }
  }

  /**
   * POST /api/chat/agent/steer — Tempdoc 565 §30 (the DIRECTION authority's {@code interject}):
   * queue a free-form human steering directive for a running session. Body:
   * {@code {"sessionId": "...", "text": "..."}}. The loop drains it at the next step boundary and
   * folds it into the next LLM call; a {@code directive_acknowledged} SSE event echoes back. Mirrors
   * {@link #handleAutonomy} (the {@code set-posture} sibling). 404 if the session is unknown/finished.
   */
  void handleSteeringDirective(Context ctx) {
    handleControl(ctx, "Failed to inject steering directive", body -> {
      String sessionId = body.path("sessionId").asText();
      String text = body.path("text").asText();
      if (sessionId.isBlank() || text.isBlank()) {
        ctx.status(400).json(Map.of("error", "sessionId and text are required"));
        return;
      }
      okOr404(
          ctx,
          agentService().injectSteeringDirective(sessionId, text),
          "injected",
          "session not found or finished");
    });
  }

  /**
   * POST /api/chat/sessions/resume-last - Resume last persisted agent session. The
   * sibling read-only session/history endpoints live in {@link AgentSessionController} (tempdoc
   * 585 §B.5); the resume streams stay here because they call {@code isAvailable()} and stream
   * agent events through the {@link AgentSseWriter}.
   */
  void handleResumeLastStream(Context ctx) {
    if (!agentService().isAvailable()) {
      ctx.status(503).json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Agent capability is not available", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    sseWriter.initSseHeaders(ctx, "/api/chat/sessions/resume-last");
    try {
      withHeartbeat(
          ctx, () -> agentService().resumeLastSession(event -> sseWriter.writeAgentEvent(ctx, event), RequestEngineContext.agent(ctx)));
    } catch (Exception e) {
      LOG.error("Failed to resume last agent session", e);
      Map<String, Object> resumeErr = new LinkedHashMap<>();
      resumeErr.put("error", e.getMessage());
      resumeErr.put("errorCode", ApiErrorCode.RESUME_FAILED.name());
      resumeErr.put("errorClass", ApiErrorCode.RESUME_FAILED.errorClass().name());
      resumeErr.put("retryable", ApiErrorCode.RESUME_FAILED.isRetryable());
      sseWriter.writeEvent(ctx, "error", resumeErr);
    }
  }

  /**
   * POST /api/chat/sessions/{id}/resume - Resume a specific persisted session.
   * Tempdoc 415 follow-up (C20). Mirrors {@link #handleResumeLastStream(Context)} but
   * addressed by id.
   */
  void handleResumeSessionStream(Context ctx) {
    if (!agentService().isAvailable()) {
      ctx.status(503)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.SERVICE_UNAVAILABLE,
                  "Agent capability is not available",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }
    String sessionId = ctx.pathParam("id");
    sseWriter.initSseHeaders(ctx, "/api/chat/sessions/{id}/resume");
    try {
      withHeartbeat(
          ctx,
          () -> agentService().resumeSession(sessionId, event -> sseWriter.writeAgentEvent(ctx, event), RequestEngineContext.agent(ctx)));
    } catch (Exception e) {
      LOG.error("Failed to resume agent session {}", sessionId, e);
      Map<String, Object> resumeErr = new LinkedHashMap<>();
      resumeErr.put("error", e.getMessage());
      resumeErr.put("errorCode", ApiErrorCode.RESUME_FAILED.name());
      resumeErr.put("errorClass", ApiErrorCode.RESUME_FAILED.errorClass().name());
      resumeErr.put("retryable", ApiErrorCode.RESUME_FAILED.isRetryable());
      sseWriter.writeEvent(ctx, "error", resumeErr);
    }
  }

  /**
   * POST /api/chat/sessions/{id}/fork — Tempdoc 585 §D Phase 3 (C2): time-travel fork. Branch a NEW
   * run from a finished one, rewinding to its last user turn and optionally editing that question
   * (body {@code {"editedMessage": "..."}}; blank/absent re-runs the original). Mirrors resume's
   * streaming shape but has no resume-state gate.
   */
  void handleForkSessionStream(Context ctx) {
    if (!agentService().isAvailable()) {
      ctx.status(503)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.SERVICE_UNAVAILABLE,
                  "Agent capability is not available",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }
    String sessionId = ctx.pathParam("id");
    sseWriter.initSseHeaders(ctx, "/api/chat/sessions/{id}/fork");
    String editedMessage = "";
    try {
      if (ctx.body() != null && !ctx.body().isBlank()) {
        editedMessage = MAPPER.readTree(ctx.body()).path("editedMessage").asText("");
      }
    } catch (Exception parseIgnored) {
      // Malformed/empty body ⇒ a plain re-roll of the original last question.
    }
    try {
      String forkMessage = editedMessage;
      withHeartbeat(
          ctx,
          () ->
              agentService()
                  .forkSession(sessionId, forkMessage, event -> sseWriter.writeAgentEvent(ctx, event), RequestEngineContext.agent(ctx)));
    } catch (Exception e) {
      LOG.error("Failed to fork agent session {}", sessionId, e);
      Map<String, Object> forkErr = new LinkedHashMap<>();
      forkErr.put("error", e.getMessage());
      forkErr.put("errorCode", ApiErrorCode.RESUME_FAILED.name());
      forkErr.put("retryable", false);
      sseWriter.writeEvent(ctx, "error", forkErr);
    }
  }

  /** DELETE /api/chat/sessions/{id} - Cancel an agent session. */
  void handleCancelSession(Context ctx) {
    String sessionId = ctx.pathParam("id");
    agentService().cancelSession(sessionId);
    ctx.json(Map.of("status", "cancelled"));
  }
}
