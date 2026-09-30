/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.ConsentCapsuleAuthority;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.GatedOperationExecutor;
import io.justsearch.agent.api.registry.IntentPreviewer;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationDispatchPlan;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tool-dispatch cluster for the agent loop (tempdoc 240 W5 — extracted from
 * {@code AgentLoopService}). Owns the path from an approved tool call to an
 * {@link OperationResult}: the safety/approval gate, the declaration-driven
 * retry loop + tool span, and the routed-or-direct dispatch. Coupled to
 * {@code operationExecutor}, {@code agentTelemetry}, and a {@code Supplier} for
 * the late-bound {@link BackendIntentRouter} (resolved per-call so a router
 * wired after construction via {@code HeadAssembly.setBackendIntentRouter} is
 * observed — see {@code agent-lessons.md} §standalone-capability-stays-stuck).
 *
 * <p>The loop-lifecycle-coupled {@code handleVirtualToolCall} (checkpoints,
 * compression, the per-session virtual-tool gate) stays on AgentLoopService.
 */
final class AgentToolDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(AgentToolDispatcher.class);

  private final OperationDispatcher operationExecutor;
  private final AgentTelemetry agentTelemetry;
  private final Supplier<BackendIntentRouter> routerSupplier;
  // Tempdoc 560 Phase 2: the shared gate→consent→route primitive. Routes the agent loop's approved
  // tool calls through the intent layer (mint capsule + BackendIntentRouter), the same kernel the
  // WorkflowShapeRunner uses — so the two cannot drift in how they reach the trust lattice.
  private final GatedOperationExecutor gatedExecutor;
  // Tempdoc 561 P-D1: late-bound read-only window onto the ONE intent-gate authority
  // (IntentGateEvaluator). When present, the pending-approval event carries the backend's
  // authoritative GateBehavior so the FE renders the real decision instead of re-deriving it.
  // Null in legacy/test wiring → the wire gateBehavior is absent and the FE falls back.
  private final Supplier<IntentPreviewer> intentPreviewerSupplier;

  AgentToolDispatcher(
      OperationDispatcher operationExecutor,
      AgentTelemetry agentTelemetry,
      Supplier<BackendIntentRouter> routerSupplier,
      Supplier<ConsentCapsuleAuthority> capsuleAuthoritySupplier,
      Supplier<IntentPreviewer> intentPreviewerSupplier) {
    this.operationExecutor = operationExecutor;
    this.agentTelemetry = agentTelemetry;
    this.routerSupplier = routerSupplier;
    // Tempdoc 550 C2 step 2: late-bound consent-capsule authority (mirrors routerSupplier;
    // set on AgentLoopService after construction). When present, the agent loop mints a
    // bound capsule representing the user approval obtained by handleSafetyGate, instead of
    // the legacy sentinel token. Null in legacy/test wiring → sentinel fallback (still
    // accepted by the lattice's retained legacy path until C2 step 4 removes it). Consumed
    // directly here by gatedExecutor; not retained as a field.
    this.gatedExecutor =
        new GatedOperationExecutor(
            routerSupplier, capsuleAuthoritySupplier == null ? () -> null : capsuleAuthoritySupplier);
    this.intentPreviewerSupplier = intentPreviewerSupplier;
  }

  OperationResult executeOperationWithPolicy(Operation op, ToolCallRequest call, String sessionId,
      EngineContext engineContext, OperationDispatchPlan plan) {
    Span toolSpan = GlobalOpenTelemetry.getTracer(AgentLoopService.TRACER_SCOPE).spanBuilder("execute_tool " + call.toolName())
        .setSpanKind(SpanKind.INTERNAL)
        .setAttribute("gen_ai.operation.name", "execute_tool")
        .setAttribute("gen_ai.tool.name", call.toolName())
        .setAttribute("gen_ai.tool.call.id", call.id())
        .startSpan();
    try (Scope ignored = toolSpan.makeCurrent()) {
      // Tempdoc 879: the operation's own RetryPolicy decides WHETHER and HOW OFTEN to replay; the
      // caller's AgentRetryPolicy table decides only the back-off SCHEDULE. The split is the design
      // rule: the axis declares *permission* to replay — which is why RetryPolicy's constructor
      // refuses allowAutoRetry without an idempotencyKey — while timing is a property of this
      // call-site, not of the operation. Before this, the loop hard-coded `risk == LOW`, so
      // core.search-index's declared autoRetry(2) got one attempt and core.read-document's declared
      // noRetry() was retried anyway; the declaration and the behaviour could not be made to agree.
      RetryPolicy retryPolicy = op.policy().retry();
      AgentRetryPolicy.RetryDecision backoff =
          AgentRetryPolicy.forCode(AgentErrorCode.TOOL_TRANSIENT_READ_ONLY);
      int attempt = 0;
      while (true) {
        try {
          OperationResult result = plan == null ? dispatchToolCall(op, call, sessionId, engineContext)
              : gatedExecutor.routePrepared(op, call.arguments(), plan, engineContext);
          toolSpan.setStatus(StatusCode.OK);
          return result;
        } catch (Exception e) {
          LOG.error("Tool execution failed: {} - {}", call.toolName(), e.getMessage(), e);
          boolean canRetry =
              retryPolicy.allowAutoRetry() && attempt < retryPolicy.maxRetries();
          if (canRetry) {
            attempt++;
            agentTelemetry.recordRetry(AgentErrorCode.TOOL_TRANSIENT_READ_ONLY, attempt);
            AgentRetryPolicy.sleepRetryDelay(backoff.delayMsForAttempt(attempt));
            continue;
          }
          if (retryPolicy.allowAutoRetry() && attempt > 0) {
            agentTelemetry.recordRetryExhausted(AgentErrorCode.TOOL_TRANSIENT_READ_ONLY);
          }
          toolSpan.recordException(e);
          toolSpan.setStatus(StatusCode.ERROR, "tool-execution-failed");
          return OperationResult.failure("Execution error: " + e.getMessage());
        }
      }
    } finally {
      toolSpan.end();
    }
  }

  /**
   * Slice 487 Phase 1.7 migration target — the sole tool-call dispatch site in
   * {@code AgentToolDispatcher}. When the {@link BackendIntentRouter} is wired
   * (production via {@code HeadAssembly.setBackendIntentRouter}), routes
   * the dispatch through the intent layer so the trust lattice, audit log, and
   * unified observability all fire. When the router is unwired (test paths that
   * don't construct the full intent substrate), falls back to direct
   * {@link OperationDispatcher#dispatch} — same behavior as pre-slice-487.
   *
   * <p>The confirmation token {@code "agent-loop-handleSafetyGate-approved"} is
   * the agent's signal to the dispatcher's trust lattice that user approval was
   * obtained pre-dispatch via {@link #prepareAndApprove}. Without it, the lattice
   * would throw {@link
   * io.justsearch.agent.api.registry.ConfirmationRequiredException} for any
   * UNTRUSTED × MEDIUM/HIGH dispatch — but the agent loop's own gate has
   * already approved, so the token represents that consent.
   *
   * <p><strong>Audit-test gate:</strong> the {@code operationExecutor.dispatch}
   * call in this method is the only direct dispatcher call site in
   * {@code modules/app-agent}. {@code AgentLoopServiceAuditTest} asserts this
   * invariant (slice 487 Phase 1.7 §6.1 audit-test gate). New dispatch paths
   * must route through {@code backendIntentRouter}, not the executor directly.
   */
  OperationResult dispatchToolCall(Operation op, ToolCallRequest call, String sessionId, EngineContext engineContext) {
    BackendIntentRouter router = routerSupplier.get();
    if (router != null) {
      // Tempdoc 560 Phase 2 + 561 P-A1: the "mint a bound consent capsule + route through the intent
      // layer" kernel is the shared GatedOperationExecutor (also consumed by WorkflowShapeRunner —
      // both are backend orchestrators executing approved tool calls; they cannot drift). User
      // approval for this op was already obtained pre-dispatch via handleSafetyGate (or it is a
      // LOW-risk auto-approve), so we route the approved call directly — passing the loop's sessionId
      // as the cross-domain correlationId (561 P-A1) so the ActionEvent ledger row this produces is
      // filterable to this agent session, the join key the History projection (P-B1) consumes.
      return gatedExecutor.routeApproved(
          op,
          call.arguments(),
          sessionId == null || sessionId.isBlank()
              ? Optional.empty()
              : Optional.of(sessionId), engineContext);
    }
    // Legacy fallback for test wiring without the intent router. Slice 487
    // Phase 1.7 audit-test gate ratifies this is the ONLY direct dispatcher
    // call site in modules/app-agent.
    return operationExecutor.dispatch(op, call.arguments(), engineContext);
  }

  // Tempdoc S7 — the search tool's registered Operation id (AgentToolsOperationCatalog.SEARCH_INDEX
  // in app-services, which modules/app-agent cannot import — app-agent depends only on
  // app-agent-api/app-api/configuration/telemetry). Duplicated here as the wire-stable string the
  // two modules agree on, mirroring how Operation ids already cross this boundary as plain strings
  // (e.g. call.toolName() dispatch).
  private static final String SEARCH_OPERATION_ID = "core.search-index";
  private static final ObjectMapper DOC_IDS_MAPPER = new ObjectMapper();

  /**
   * Tempdoc S7 — when this run carries a docIds scope (FE scope chips) AND the dispatched
   * operation is the search tool, merge {@code docIds} into the LLM's own tool-call arguments so
   * the search filter applies regardless of what the LLM asked for. A no-op (returns {@code call}
   * unchanged) for every other operation, and a no-op when the session carries no scope (the
   * common, unscoped case) — the LLM's own arguments then keep riding the conversation history
   * untouched.
   */
  ToolCallRequest scopeToolCall(Operation op, ToolCallRequest call, AgentSession session) {
    List<String> scope = session.docIdsScope();
    if (scope.isEmpty() || !SEARCH_OPERATION_ID.equals(op.id().value())) {
      return call;
    }
    String merged = mergeDocIdsIntoArguments(call.arguments(), scope);
    if (merged == null) {
      return call;
    }
    return new ToolCallRequest(call.id(), call.toolName(), merged);
  }

  /** Merges a {@code docIds} array into a tool call's raw JSON arguments; null on parse failure. */
  private static String mergeDocIdsIntoArguments(String argumentsJson, List<String> docIds) {
    try {
      JsonNode parsed =
          argumentsJson == null || argumentsJson.isBlank()
              ? DOC_IDS_MAPPER.createObjectNode()
              : DOC_IDS_MAPPER.readTree(argumentsJson);
      ObjectNode node =
          parsed.isObject() ? (ObjectNode) parsed : DOC_IDS_MAPPER.createObjectNode();
      var arr = DOC_IDS_MAPPER.createArrayNode();
      docIds.forEach(arr::add);
      node.set("docIds", arr);
      return DOC_IDS_MAPPER.writeValueAsString(node);
    } catch (Exception e) {
      LOG.warn("Failed to merge docIds scope into tool-call arguments; dispatching unscoped", e);
      return null;
    }
  }

  /** Stack-only continuation; null plan is reserved for workflow streaming and unwired legacy tests. */
  record ToolApproval(boolean approved, OperationDispatchPlan plan) {}

  ToolApproval prepareAndApprove(AgentSession session, ToolCallRequest call, Operation op,
      Consumer<AgentEvent> eventConsumer, boolean streamingWorkflow) {
    GateBehavior behavior = approvalBehavior(session, op);
    boolean readFastPath = op.policy().risk() == RiskTier.LOW
        && (behavior == null || behavior == GateBehavior.AUTO);
    // Preserve background refusal before preparing anything that needs a watcher.
    if (session.isBackground() && !readFastPath) {
      return new ToolApproval(awaitSafetyGate(session, call, op, eventConsumer, behavior, Optional.empty()), null);
    }
    OperationDispatchPlan plan = streamingWorkflow || routerSupplier.get() == null ? null
        : gatedExecutor.prepare(op, call.arguments(), session.engineContext(), null, !readFastPath);
    if (plan instanceof OperationDispatchPlan.Recorded) return new ToolApproval(true, plan);
    Optional<OperationApprovalPreview> preview = plan instanceof OperationDispatchPlan.Ready ready
        ? ready.approvalPreview() : Optional.empty();
    return new ToolApproval(awaitSafetyGate(session, call, op, eventConsumer, behavior, preview), plan);
  }

  private GateBehavior approvalBehavior(AgentSession session, Operation op) {
    RiskTier risk = op.policy().risk();

    // Tempdoc 561 P-D: ask the ONE intent-gate authority for the ISSUANCE verdict under the user's
    // autonomy dial (the backend issuance policy, 550 Thesis V). The FE OBEYS this GateBehavior
    // (auto-approve iff AUTO) instead of re-deriving auto-approval from risk. Absent previewer
    // (legacy/test) → null gate → FE dial fallback.
    IntentPreviewer previewer =
        intentPreviewerSupplier == null ? null : intentPreviewerSupplier.get();
    // Tempdoc 561 §19/C-4: an op is reversible iff it declares undo or a backend inverse; an
    // irreversible MEDIUM write then confirms even under AUTO (closes the hallucinated-write hazard).
    boolean reversible =
        op.policy().undoSupported() || op.policy().inverseOperationRef().isPresent();
    // Tempdoc 879: the op's declared ConfirmStrategy rides in as an absolute FLOOR — the dial can
    // tighten the verdict but can no longer approve past what the operation itself asked for.
    return
        previewer == null
            ? null
            : previewer.previewAgentGate(
                risk, session.autonomyLevel(), reversible, op.policy().confirm());

  }

  private boolean awaitSafetyGate(AgentSession session, ToolCallRequest call, Operation op,
      Consumer<AgentEvent> eventConsumer, GateBehavior gateBehavior,
      Optional<OperationApprovalPreview> preview) {
    RiskTier risk = op.policy().risk();
    // Capsule-free fast path: a base-AUTO read-only dispatch needs neither approval nor a consent
    // capsule (UNTRUSTED × LOW = AUTO in the enforcement lattice). Auto-run it. (WATCH tightens reads
    // to INLINE_CONFIRM, so this path is skipped under WATCH — the user then acknowledges the read.)
    if (risk == RiskTier.LOW && (gateBehavior == GateBehavior.AUTO || gateBehavior == null)) {
      return true;
    }

    // Tempdoc 561 P-D: a BACKGROUND (non-interactive) run is safe-by-default — anything that did not
    // take the capsule-free read fast path above has no watcher to approve it, so reject it
    // IMMEDIATELY rather than blocking for an approval that will never come.
    if (session.isBackground()) {
      eventConsumer.accept(
          new AgentEvent.ToolCallRejected(
              call.id(),
              "background run is safe-by-default: write/destructive tool calls require a watcher to approve"));
      return false;
    }

    // Tempdoc 834 §6.2 — the SAME five values ride into the gate, so a reattacher whose replay ring
    // no longer holds the frame below still gets the open gate on the state snapshot.
    //
    // REGISTERED BEFORE ANNOUNCED, deliberately: the emit is what wakes every observer, so anything
    // that happens once the frame is out — a reattach building its `state_snapshot` primer, or an
    // approve() arriving from a fast client — must find the gate already there. With the emit first,
    // a reattacher could snapshot a run that has announced a pending approval but reports
    // `pendingApprovals: []` and no `park` (i.e. "nothing to answer" for a run that is about to
    // block), and an approval that beat the registration would be dropped by `approve()`'s
    // unknown-callId path. Registration has no dependency on the emit — it is a map put.
    CompletableFuture<Boolean> gate =
        session.createApprovalGate(
            call.id(),
            new AgentEvent.PendingApproval(
                call.id(),
                call.toolName(),
                call.arguments(),
                risk == null ? null : risk.name().toLowerCase(java.util.Locale.ROOT),
                gateBehavior == null ? null : gateBehavior.name().toLowerCase(java.util.Locale.ROOT)), preview);

    // Emit the pending-approval carrying the backend's issuance verdict. The FE auto-approves (which
    // mints the consent capsule via the normal approve path enforcement requires) iff gateBehavior is
    // AUTO; otherwise it prompts the user. Either way the approval flows through one path.
    try {
      eventConsumer.accept(
          new AgentEvent.ToolCallPendingApproval(
              call.id(), call.toolName(), call.arguments(), risk, gateBehavior));
      try {
        return gate.get(AgentTimeouts.approvalGateMs(), TimeUnit.MILLISECONDS);
      } catch (Exception e) {
        LOG.warn("Approval gate timeout/error for call {}", call.id(), e);
        return false;
      }
    } finally {
      session.discardApprovalGate(call.id());
    }
  }
}
