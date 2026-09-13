/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import io.justsearch.core.context.EngineContext;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Session-agnostic <b>gate → consent → route</b> primitive for executing one {@link Operation}
 * with obtained user approval, shared by every backend orchestrator that runs tool calls
 * (tempdoc 560 Phase 2). Two such orchestrators exist: the agent loop's
 * {@code AgentToolDispatcher} and the {@code WorkflowShapeRunner}. Both need the identical
 * sequence — risk-gate the call, obtain user consent if required, mint a bound
 * {@linkplain ConsentCapsuleAuthority consent capsule}, then route through the
 * {@link BackendIntentRouter} so the trust lattice, audit log, and unified observability all
 * fire. That sequence is the single reason-to-change this class owns; it is lifted verbatim from
 * {@code AgentToolDispatcher.handleSafetyGate} + {@code dispatchToolCall}'s router branch.
 *
 * <p><b>Routes only via {@link BackendIntentRouter}</b> — never {@link OperationDispatcher}
 * directly. This is both the lattice-correct path (slice 487's "all agent-emitted actions flow
 * through the intent layer") and the reason this class does not trip
 * {@code AgentLoopServiceAuditTest} (which forbids any {@code OperationDispatcher.dispatch} call
 * outside the agent dispatcher's documented legacy fallback).
 *
 * <p>The agent loop uses {@link TransportTag#AGENT_LOOP}; workflows use {@link TransportTag#WORKFLOW}.
 * Both share the same untrusted consent policy while preserving their transport attribution.
 * Prepared callers obtain an {@link OperationDispatchPlan} before their own approval gate and
 * pass that same reference to {@link #routePrepared} after approval.
 */
public final class GatedOperationExecutor {

  /** Default wait for a pending approval before treating it as declined. Mirrors the agent loop. */
  public static final long DEFAULT_APPROVAL_TIMEOUT_SECONDS = 300;

  /** The agent loop's legacy sentinel token, accepted by the lattice when no authority is wired. */
  private static final String LEGACY_APPROVAL_SENTINEL = "agent-loop-handleSafetyGate-approved";

  /**
   * Obtains the user's approve/deny decision for a pending call. The returned future completes
   * {@code true} (approved) / {@code false} (rejected) when the user (or a timeout) decides. The
   * caller owns how the decision is delivered — an HTTP approve endpoint completing a registered
   * future, a session gate map, etc.
   */
  @FunctionalInterface
  public interface ApprovalGate {
    CompletableFuture<Boolean> request(String callId);
  }

  /**
   * Notifies the caller that a call now needs user approval, so it can surface a prompt (e.g. emit
   * a {@code tool_call_pending} SSE event). Invoked before blocking on the {@link ApprovalGate}.
   */
  @FunctionalInterface
  public interface PendingApprovalSink {
    void onPending(String callId, String toolName, String argumentsJson, RiskTier risk);
  }

  private final Supplier<BackendIntentRouter> routerSupplier;
  private final Supplier<ConsentCapsuleAuthority> capsuleAuthoritySupplier;
  private final TransportTag transport;
  private final long approvalTimeoutSeconds;

  public GatedOperationExecutor(
      Supplier<BackendIntentRouter> routerSupplier,
      Supplier<ConsentCapsuleAuthority> capsuleAuthoritySupplier) {
    this(routerSupplier, capsuleAuthoritySupplier, TransportTag.AGENT_LOOP, DEFAULT_APPROVAL_TIMEOUT_SECONDS);
  }

  /**
   * Tempdoc 560 Fix B — the orchestrator's transport, stamped on every routed call so the lattice +
   * audit ledger attribute the action correctly. The agent loop uses {@link TransportTag#AGENT_LOOP};
   * the {@code WorkflowShapeRunner} passes {@link TransportTag#WORKFLOW}. Both map to SourceTier
   * UNTRUSTED, so the gate behavior is identical — only the provenance differs.
   */
  public GatedOperationExecutor(
      Supplier<BackendIntentRouter> routerSupplier,
      Supplier<ConsentCapsuleAuthority> capsuleAuthoritySupplier,
      TransportTag transport) {
    this(routerSupplier, capsuleAuthoritySupplier, transport, DEFAULT_APPROVAL_TIMEOUT_SECONDS);
  }

  public GatedOperationExecutor(
      Supplier<BackendIntentRouter> routerSupplier,
      Supplier<ConsentCapsuleAuthority> capsuleAuthoritySupplier,
      long approvalTimeoutSeconds) {
    this(routerSupplier, capsuleAuthoritySupplier, TransportTag.AGENT_LOOP, approvalTimeoutSeconds);
  }

  public GatedOperationExecutor(
      Supplier<BackendIntentRouter> routerSupplier,
      Supplier<ConsentCapsuleAuthority> capsuleAuthoritySupplier,
      TransportTag transport,
      long approvalTimeoutSeconds) {
    this.routerSupplier = routerSupplier;
    this.capsuleAuthoritySupplier = capsuleAuthoritySupplier;
    this.transport = transport == null ? TransportTag.AGENT_LOOP : transport;
    this.approvalTimeoutSeconds = approvalTimeoutSeconds;
  }

  /** Whether {@code op} requires a user-approval gate before it may run (anything above LOW). */
  public boolean requiresApproval(Operation op) {
    return op.policy().risk() != RiskTier.LOW;
  }

  /** Freeze through the intent layer before the caller's approval policy runs; never issue consent. */
  public OperationDispatchPlan prepare(Operation op, String argumentsJson, EngineContext engineContext,
      String operationKey, boolean includeApprovalPreview) {
    validateContext(engineContext.sessionId(), engineContext);
    String args = normalizeArguments(argumentsJson);
    return router().prepare(new Intent(ShellAddress.Invocation.of(op.id(), args), transport),
        InvocationProvenance.fromEngineContext(engineContext, ExecutorTag.AGENT, Instant.now(), Optional.empty()),
        engineContext, operationKey, includeApprovalPreview);
  }

  /**
   * Route the exact plan after the caller obtains approval. The plan is an observation/reference,
   * not authority. A recorded plan queries the current receipt through the router again without
   * minting consent; a ready plan binds its nonce and key in the existing capsule authority.
   */
  public OperationResult routePrepared(Operation op, String argumentsJson, OperationDispatchPlan plan,
      EngineContext engineContext) {
    return routeApproved(op, argumentsJson, engineContext.sessionId(), engineContext,
        java.util.Objects.requireNonNull(plan, "plan"));
  }

  /**
   * Full gated execution: if {@code op} requires approval, surface a pending prompt via
   * {@code pendingSink} and block on {@code gate} until the user decides (or the timeout elapses,
   * treated as declined); then route the approved call. LOW-risk ops skip straight to routing.
   *
   * @return the operation result, or a {@link OperationResult#failure} describing a decline/timeout
   */
  public OperationResult execute(
      Operation op,
      String callId,
      String toolName,
      String argumentsJson,
      PendingApprovalSink pendingSink,
      ApprovalGate gate, EngineContext engineContext) {
    String args = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    if (requiresApproval(op)) {
      pendingSink.onPending(callId, toolName, args, op.policy().risk());
      boolean approved;
      try {
        approved = gate.request(callId).get(approvalTimeoutSeconds, TimeUnit.SECONDS);
      } catch (Exception e) {
        return OperationResult.failure(
            "Approval was not granted (timed out or interrupted): " + e.getMessage());
      }
      if (!approved) {
        return OperationResult.failure("User declined the operation");
      }
    }
    return routeApproved(op, args, engineContext);
  }

  /**
   * Shared kernel — mint a single-use consent capsule bound to {@code op} + {@code argumentsJson}
   * and dispatch through the {@link BackendIntentRouter}. The caller asserts approval was already
   * obtained (LOW risk auto-approves; higher risk passed {@link #execute}'s gate). Falls back to
   * the legacy sentinel token when no {@link ConsentCapsuleAuthority} is wired (test wiring).
   */
  public OperationResult routeApproved(Operation op, String argumentsJson, EngineContext engineContext) {
    return routeApproved(op, argumentsJson, engineContext.sessionId(), engineContext);
  }

  /**
   * Tempdoc 561 P-A1 — overload that stamps a cross-domain {@code correlationId} (the agent loop's
   * sessionId) onto the dispatched call's {@link InvocationProvenance}, so the ActionEvent ledger
   * row this dispatch produces is filterable to the originating agent session — the join key the
   * History projection (561 P-B1) consumes. The {@linkplain #routeApproved(Operation, String, EngineContext)
   * 2-arg form} delegates here with {@link Optional#empty()} (the workflow runner / no-session
   * context), so 560's shared kernel and 561's session correlation compose in one path.
   */
  public OperationResult routeApproved(
      Operation op, String argumentsJson, Optional<String> correlationId, EngineContext engineContext) {
    return routeApproved(op, argumentsJson, correlationId, engineContext, null);
  }

  private OperationResult routeApproved(Operation op, String argumentsJson, Optional<String> correlationId,
      EngineContext engineContext, OperationDispatchPlan plan) {
    validateContext(correlationId, engineContext);
    BackendIntentRouter router = router();
    String args = normalizeArguments(argumentsJson);
    Optional<String> confirmationToken = plan instanceof OperationDispatchPlan.Recorded ? Optional.empty()
        : Optional.of(mintApproval(op, args, plan));
    ShellAddress.Invocation invocation = new ShellAddress.Invocation(op.id(), args, confirmationToken);
    Intent intent = new Intent(invocation, transport);
    InvocationProvenance provenance =
        InvocationProvenance.fromEngineContext(engineContext, ExecutorTag.AGENT, Instant.now(), Optional.empty());
    IntentDispatchResult result = plan == null ? router.dispatch(intent, provenance, engineContext)
        : router.dispatch(intent, provenance, engineContext, plan.operationKey(),
            plan instanceof OperationDispatchPlan.Ready ready ? ready.preparationNonce() : null);
    if (result instanceof IntentDispatchResult.Dispatched dispatched) {
      return dispatched.result();
    }
    throw new IllegalStateException(
        "BackendIntentRouter returned Forwarded for an Invocation intent — substrate bug");
  }

  private void validateContext(Optional<String> correlationId, EngineContext engineContext) {
    if (!java.util.Objects.requireNonNull(correlationId, "correlationId").equals(engineContext.sessionId())) {
      throw new IllegalArgumentException("Correlation ID disagrees with Engine context");
    }
    if (!transport.name().equals(engineContext.transport())) {
      throw new IllegalArgumentException("Orchestrator context has the wrong transport");
    }
  }

  private BackendIntentRouter router() {
    BackendIntentRouter router = routerSupplier == null ? null : routerSupplier.get();
    if (router == null) {
      throw new IllegalStateException(
          "BackendIntentRouter is not wired; GatedOperationExecutor requires the intent layer");
    }
    return router;
  }

  private String mintApproval(Operation op, String args, OperationDispatchPlan plan) {
    ConsentCapsuleAuthority authority =
        capsuleAuthoritySupplier == null ? null : capsuleAuthoritySupplier.get();
    if (plan instanceof OperationDispatchPlan.Ready ready && ready.preparationNonce() != null) {
      if (authority == null) throw new IllegalStateException("Prepared approval requires a capsule authority");
      return authority.mintPrepared(op.id().value(), args, SourceTier.UNTRUSTED,
          ready.operationKey(), ready.preparationNonce());
    }
    return authority != null ? authority.mint(op.id().value(), args) : LEGACY_APPROVAL_SENTINEL;
  }

  private static String normalizeArguments(String argumentsJson) {
    return argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
  }
}
