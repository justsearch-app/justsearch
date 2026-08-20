/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.TraceContext;
import java.util.Locale;

/**
 * Trace decoration for agent events (tempdoc 240 W3 — extracted from
 * {@code AgentLoopService}). Stamps each emitted {@link AgentEvent} with a
 * {@link TraceContext} (run/step/span ids) so downstream consumers can
 * reconstruct the causal event tree. Pure functions + a per-run {@link Sequencer}.
 */
final class AgentEventTracing {

  private AgentEventTracing() {}

  /** Return a copy of {@code event} carrying {@code trace}. */
  static AgentEvent withTrace(AgentEvent event, TraceContext trace) {
    return switch (event) {
      case AgentEvent.TextChunk e -> new AgentEvent.TextChunk(e.text(), trace);
      case AgentEvent.ToolCallProposed e ->
          new AgentEvent.ToolCallProposed(e.call(), e.risk(), trace);
      case AgentEvent.ToolBatchProposed e ->
          new AgentEvent.ToolBatchProposed(e.calls(), trace);
      case AgentEvent.ToolCallPendingApproval e ->
          // Tempdoc 561 P-D1: preserve the backend gate verdict through trace-enrichment.
          new AgentEvent.ToolCallPendingApproval(
              e.callId(), e.toolName(), e.arguments(), e.risk(), e.gateBehavior(), trace);
      case AgentEvent.ToolCallApproved e -> new AgentEvent.ToolCallApproved(e.callId(), trace);
      case AgentEvent.DirectiveAcknowledged e ->
          new AgentEvent.DirectiveAcknowledged(e.directiveText(), trace);
      case AgentEvent.ToolExecutionStarted e ->
          new AgentEvent.ToolExecutionStarted(e.callId(), e.toolName(), trace);
      case AgentEvent.ToolExecutionCompleted e ->
          new AgentEvent.ToolExecutionCompleted(e.callId(), e.result(), trace);
      case AgentEvent.ToolCallRejected e ->
          new AgentEvent.ToolCallRejected(e.callId(), e.reason(), trace);
      case AgentEvent.ToolCallVirtual e ->
          new AgentEvent.ToolCallVirtual(e.callId(), e.wireName(), e.arguments(), trace);
      case AgentEvent.AgentDone e ->
          new AgentEvent.AgentDone(
              e.finalResponse(),
              e.iterationsUsed(),
              e.toolCallsExecuted(),
              e.totalTokensUsed(),
              e.sources(),
              e.citations(),
              e.citationScorer(),
              trace);
      case AgentEvent.AgentError e ->
          new AgentEvent.AgentError(
              e.error(),
              e.errorCode(),
              e.errorClass(),
              e.retryAction(),
              e.retryAttempt(),
              trace);
      case AgentEvent.AgentProgress e ->
          new AgentEvent.AgentProgress(
              e.phase(), e.message(), e.iteration(), e.maxIterations(), e.severity(), trace);
      case AgentEvent.AgentBudgetUpdate e ->
          new AgentEvent.AgentBudgetUpdate(
              e.phase(),
              e.tokensConsumed(),
              e.tokensRemaining(),
              e.totalTokensConsumed(),
              e.promptTokens(),
              e.contextWindow(),
              trace);
      case AgentEvent.BudgetGatePending e ->
          new AgentEvent.BudgetGatePending(
              e.tokensNeeded(), e.tokensRemaining(), e.totalTokensConsumed(), trace);
      case AgentEvent.ContextGatePending e ->
          new AgentEvent.ContextGatePending(e.promptTokens(), e.contextWindow(), trace);
      case AgentEvent.ContextCompacted e ->
          new AgentEvent.ContextCompacted(e.droppedMessages(), trace);
      case AgentEvent.SessionStarted e -> new AgentEvent.SessionStarted(e.sessionId(), trace);
      case AgentEvent.HandoffProposed e ->
          new AgentEvent.HandoffProposed(e.fromAgentId(), e.toAgentId(), e.reason(), trace);
      case AgentEvent.HandoffExecuted e ->
          new AgentEvent.HandoffExecuted(e.fromAgentId(), e.toAgentId(), trace);
      case AgentEvent.ReasoningChunk e -> new AgentEvent.ReasoningChunk(e.text(), trace);
      case AgentEvent.StateSnapshot e ->
          new AgentEvent.StateSnapshot(
              e.iteration(), e.budgetRemaining(), e.toolCallsExecuted(), e.messageCount(),
              e.activeAgentId(), e.pendingApprovals(), e.autonomyLevel(), e.park(), trace);
    };
  }

  private static String toolCallIdFor(AgentEvent event) {
    return switch (event) {
      case AgentEvent.ToolCallProposed e -> e.call().id();
      case AgentEvent.ToolCallPendingApproval e -> e.callId();
      case AgentEvent.ToolCallApproved e -> e.callId();
      case AgentEvent.ToolExecutionStarted e -> e.callId();
      case AgentEvent.ToolExecutionCompleted e -> e.callId();
      case AgentEvent.ToolCallRejected e -> e.callId();
      case AgentEvent.ToolCallVirtual e -> e.callId();
      default -> null;
    };
  }

  private static int iterationFor(AgentEvent event, int sessionIteration) {
    return switch (event) {
      case AgentEvent.SessionStarted ignored -> 0;
      case AgentEvent.AgentProgress e -> Math.max(0, e.iteration());
      default -> Math.max(0, sessionIteration);
    };
  }

  private static String stepIdFor(AgentEvent event, int iteration) {
    String iterPrefix = "iter:" + iteration;
    return switch (event) {
      case AgentEvent.SessionStarted ignored -> "session:start";
      case AgentEvent.AgentProgress e ->
          iterPrefix + ":progress:" + normalizeStepToken(e.phase());
      case AgentEvent.TextChunk ignored -> iterPrefix + ":llm:chunk";
      case AgentEvent.AgentBudgetUpdate e ->
          iterPrefix + ":budget:" + normalizeStepToken(e.phase());
      case AgentEvent.ToolCallProposed e -> iterPrefix + ":tool:" + e.call().id() + ":proposed";
      case AgentEvent.ToolBatchProposed e -> iterPrefix + ":batch:" + e.calls().size();
      case AgentEvent.ToolCallPendingApproval e -> iterPrefix + ":tool:" + e.callId() + ":pending";
      case AgentEvent.ToolCallApproved e -> iterPrefix + ":tool:" + e.callId() + ":approved";
      case AgentEvent.DirectiveAcknowledged ignored -> iterPrefix + ":directive";
      case AgentEvent.ToolExecutionStarted e -> iterPrefix + ":tool:" + e.callId() + ":executing";
      case AgentEvent.ToolExecutionCompleted e -> iterPrefix + ":tool:" + e.callId() + ":completed";
      case AgentEvent.ToolCallRejected e -> iterPrefix + ":tool:" + e.callId() + ":rejected";
      case AgentEvent.ToolCallVirtual e -> iterPrefix + ":tool:" + e.callId() + ":virtual";
      case AgentEvent.AgentDone ignored -> iterPrefix + ":done";
      case AgentEvent.AgentError ignored -> iterPrefix + ":error";
      case AgentEvent.BudgetGatePending ignored -> iterPrefix + ":budget:gate";
      case AgentEvent.ContextGatePending ignored -> iterPrefix + ":context:gate";
      case AgentEvent.ContextCompacted ignored -> iterPrefix + ":context:compacted";
      case AgentEvent.HandoffProposed e ->
          iterPrefix + ":handoff:" + e.toAgentId() + ":proposed";
      case AgentEvent.HandoffExecuted e ->
          iterPrefix + ":handoff:" + e.toAgentId() + ":executed";
      case AgentEvent.ReasoningChunk ignored -> iterPrefix + ":llm:reasoning";
      case AgentEvent.StateSnapshot ignored -> iterPrefix + ":state:snapshot";
    };
  }

  private static String normalizeStepToken(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
  }

  /** Per-run monotonic span sequencer; produces parent-linked {@link TraceContext}s. */
  static final class Sequencer {
    private final String runId;
    private final String agentId;
    private int spanCounter;
    private String previousSpanId;

    Sequencer(String runId, String agentId) {
      this.runId = runId;
      this.agentId = agentId;
    }

    synchronized TraceContext next(AgentEvent event, int sessionIteration) {
      int iteration = iterationFor(event, sessionIteration);
      String spanId = String.format(Locale.ROOT, "span-%06d", ++spanCounter);
      String parentSpanId = previousSpanId;
      previousSpanId = spanId;
      return new TraceContext(
          runId,
          stepIdFor(event, iteration),
          spanId,
          parentSpanId,
          agentId,
          toolCallIdFor(event),
          iteration);
    }
  }
}
