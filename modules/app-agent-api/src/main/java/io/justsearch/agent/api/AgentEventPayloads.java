/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The ONE base {@link AgentEvent} → ({@code name}, {@code payload}) mapping (tempdoc 585 §D Phase 0).
 *
 * <p>Before this, the mapping existed THREE times: the wire translator
 * ({@code AgentEventSseTranslator}, app-services), the persistence path
 * ({@code AgentRunStore.toPayload}/{@code toEventType}, app-agent), and — derived — the generated FE
 * handler types. The wire and persistence copies had silently DRIFTED: persistence omitted
 * {@code AgentProgress.severity}, the error {@code i18nKey}, and the trace envelope, and persisted a
 * gate-less batch. This is the #354 drift class, for the durable record.
 *
 * <p>This class is the single authority both the wire translator and the persistence path delegate
 * to, so the three representations cannot drift. It lives in app-agent-api — the lowest module both
 * app-agent (persistence) and app-services (wire) depend on — and uses only the {@link AgentEvent}
 * permits + {@link TraceContext} + the JDK. The ONE wire-only addition is the
 * {@code tool_batch_proposed} gate-prediction (it needs {@code IntentGateEvaluator} /
 * {@code ProposedBatchProjection}, app-services-only); {@link #base} produces the persistence-grade
 * batch ({@code [{callId, toolName}]}) and the wire translator overlays the projection on top of it.
 *
 * <p>The payload/name switches mirror the historical {@code AgentController.writeAgentEvent}
 * byte-for-byte (same names, same shapes), so neither the wire nor the FE contract changes; the
 * persisted record simply GAINS the previously-dropped fields, aligning it with the wire.
 */
public final class AgentEventPayloads {
  private AgentEventPayloads() {}

  /** The wire/persistence event NAME for an {@link AgentEvent} (identical across all three paths). */
  public static String name(AgentEvent event) {
    return switch (event) {
      case AgentEvent.TextChunk ignored -> "chunk";
      case AgentEvent.ReasoningChunk ignored -> "reasoning_chunk";
      case AgentEvent.ToolCallProposed ignored -> "tool_call_proposed";
      case AgentEvent.ToolBatchProposed ignored -> "tool_batch_proposed";
      case AgentEvent.ToolCallPendingApproval ignored -> "tool_call_pending";
      case AgentEvent.ToolCallApproved ignored -> "tool_call_approved";
      case AgentEvent.DirectiveAcknowledged ignored -> "directive_acknowledged";
      case AgentEvent.ToolExecutionStarted ignored -> "tool_exec_started";
      case AgentEvent.ToolExecutionCompleted ignored -> "tool_exec_completed";
      case AgentEvent.ToolCallRejected ignored -> "tool_call_rejected";
      case AgentEvent.ToolCallVirtual ignored -> "tool_call_virtual";
      case AgentEvent.AgentDone ignored -> "done";
      case AgentEvent.AgentError ignored -> "error";
      case AgentEvent.AgentProgress ignored -> "progress";
      case AgentEvent.AgentBudgetUpdate ignored -> "budget_update";
      case AgentEvent.BudgetGatePending ignored -> "budget_gate";
      case AgentEvent.ContextGatePending ignored -> "context_gate";
      case AgentEvent.ContextCompacted ignored -> "context_compacted";
      case AgentEvent.SessionStarted ignored -> "session_started";
      case AgentEvent.HandoffProposed ignored -> "handoff_proposed";
      case AgentEvent.HandoffExecuted ignored -> "handoff_executed";
      case AgentEvent.StateSnapshot ignored -> "state_snapshot";
    };
  }

  /**
   * The base payload for an {@link AgentEvent} — the persistence-grade shape that is also a faithful
   * subset of the wire payload. The wire translator delegates here and overlays only the
   * {@code tool_batch_proposed} gate-prediction; the persistence path uses this verbatim. Does NOT
   * include the trace envelope — call {@link #withTrace} to add it.
   */
  public static Map<String, Object> base(AgentEvent event) {
    return switch (event) {
      case AgentEvent.TextChunk e -> Map.of("text", e.text());
      case AgentEvent.ToolCallProposed e ->
          Map.of(
              "callId", e.call().id(),
              "toolName", e.call().toolName(),
              "arguments", e.call().arguments(),
              "risk", e.risk().name().toLowerCase(Locale.ROOT));
      // Persistence-grade batch (id + tool name, in order). The wire translator OVERRIDES this case
      // with the gate-prediction projection (ProposedBatchProjection, app-services-only); the durable
      // record keeps the lean form (the speculative pre-execution gate hint is wire-only — the ACTUAL
      // verdict is persisted on tool_call_pending below).
      case AgentEvent.ToolBatchProposed e ->
          Map.of(
              "calls",
              e.calls().stream()
                  .map(c -> Map.<String, Object>of("callId", c.id(), "toolName", c.toolName()))
                  .toList());
      case AgentEvent.ToolCallPendingApproval e -> {
        // Tempdoc 561 P-D1: carry the backend gate verdict (FE renders it; null → FE dial fallback).
        var m = new LinkedHashMap<String, Object>();
        m.put("callId", e.callId());
        m.put("toolName", e.toolName());
        m.put("arguments", e.arguments());
        m.put("risk", e.risk().name().toLowerCase(Locale.ROOT));
        if (e.gateBehavior() != null) {
          m.put("gateBehavior", e.gateBehavior().name().toLowerCase(Locale.ROOT));
        }
        yield m;
      }
      case AgentEvent.ToolCallApproved e -> Map.of("callId", e.callId());
      case AgentEvent.DirectiveAcknowledged e -> Map.of("directiveText", e.directiveText());
      case AgentEvent.ToolExecutionStarted e ->
          Map.of("callId", e.callId(), "toolName", e.toolName());
      case AgentEvent.ToolExecutionCompleted e -> toolCompletedPayload(e);
      case AgentEvent.ToolCallRejected e -> Map.of("callId", e.callId(), "reason", e.reason());
      case AgentEvent.ToolCallVirtual e ->
          Map.of(
              "callId", e.callId(),
              "wireName", e.wireName(),
              "arguments", e.arguments());
      case AgentEvent.AgentDone e -> {
        var donePayload = new LinkedHashMap<String, Object>();
        donePayload.put("finalResponse", e.finalResponse());
        donePayload.put("iterationsUsed", e.iterationsUsed());
        donePayload.put("toolCallsExecuted", e.toolCallsExecuted());
        donePayload.put("totalTokensUsed", e.totalTokensUsed());
        // Tempdoc 565 §3.A — the answer's grounding sources (clickable local passages) + the
        // per-sentence inline citations (may be empty when the matcher did not run/match).
        donePayload.put(
            "sources",
            e.sources().stream()
                .map(
                    s ->
                        Map.<String, Object>of(
                            "parentDocId", s.parentDocId(),
                            "chunkIndex", s.chunkIndex(),
                            "path", s.path(),
                            "title", s.title(),
                            "excerpt", s.excerpt(),
                            "startLine", s.startLine(),
                            "endLine", s.endLine(),
                            "headingText", s.headingText()))
                .toList());
        donePayload.put(
            "citations",
            e.citations().stream()
                .map(
                    c ->
                        Map.<String, Object>of(
                            "sentenceText", c.sentenceText(),
                            "sourceIndex", c.sourceIndex(),
                            "similarity", c.similarity()))
                .toList());
        // Tempdoc 859 §4 — the producer stamp rides the same payload as the similarities it
        // describes, so the FE's 836 §4 gate reads "which scale is this number on" from the event
        // that carried the number rather than assuming.
        donePayload.put("citationScorer", e.citationScorer());
        yield donePayload;
      }
      case AgentEvent.AgentError e -> {
        var errorPayload = new LinkedHashMap<String, Object>();
        errorPayload.put("error", e.error());
        errorPayload.put("errorCode", e.errorCode());
        if (e.errorClass() != null) {
          errorPayload.put("errorClass", e.errorClass());
        }
        if (e.retryAction() != null) {
          errorPayload.put("retryAction", e.retryAction());
        }
        if (e.retryAttempt() != null) {
          errorPayload.put("retryAttempt", e.retryAttempt());
        }
        // Tempdoc 431 Option A (D.2.d): emit i18nKey so the FE doesn't derive "errors." + errorCode
        // locally. Unifies the i18n-key contract across all three error surfaces (REST, summary SSE,
        // agent SSE) AND the durable record (585 §D Phase 0 — persistence gains it too).
        if (e.errorCode() != null) {
          errorPayload.put("i18nKey", "errors." + e.errorCode());
        }
        yield errorPayload;
      }
      case AgentEvent.AgentProgress e ->
          // Tempdoc 577 Ext II — severity decorates WARN/ERROR glyphs. 585 §D Phase 0: the durable
          // record gains it too (it was previously dropped on persistence, so a reloaded/replayed run
          // lost the glyph).
          Map.of(
              "phase", e.phase(),
              "message", e.message(),
              "iteration", e.iteration(),
              "maxIterations", e.maxIterations(),
              "severity", e.severity());
      case AgentEvent.AgentBudgetUpdate e ->
          // Tempdoc 577 Ext III / §2.14 Root II — totalTokensConsumed is run-cumulative;
          // promptTokens/contextWindow carry the cognitive-headroom meter (occupancy ÷ n_ctx).
          Map.of(
              "phase", e.phase(),
              "tokensConsumed", e.tokensConsumed(),
              "tokensRemaining", e.tokensRemaining(),
              "totalTokensConsumed", e.totalTokensConsumed(),
              "promptTokens", e.promptTokens(),
              "contextWindow", e.contextWindow());
      case AgentEvent.BudgetGatePending e ->
          Map.of(
              "tokensNeeded", e.tokensNeeded(),
              "tokensRemaining", e.tokensRemaining(),
              "totalTokensConsumed", e.totalTokensConsumed());
      case AgentEvent.ContextGatePending e ->
          Map.of("promptTokens", e.promptTokens(), "contextWindow", e.contextWindow());
      case AgentEvent.ContextCompacted e -> Map.of("droppedMessages", e.droppedMessages());
      case AgentEvent.SessionStarted e -> Map.of("sessionId", e.sessionId());
      case AgentEvent.HandoffProposed e ->
          Map.of(
              "fromAgentId", e.fromAgentId(),
              "toAgentId", e.toAgentId(),
              // Guarded (the persistence path always did): a null reason would NPE Map.of. Identical
              // output for a non-null reason; "" instead of a crash for the (rare) null case.
              "reason", e.reason() != null ? e.reason() : "");
      case AgentEvent.HandoffExecuted e ->
          Map.of("fromAgentId", e.fromAgentId(), "toAgentId", e.toAgentId());
      case AgentEvent.ReasoningChunk e -> Map.of("text", e.text());
      case AgentEvent.StateSnapshot e -> {
        // Tempdoc 834 §6.3.2: a LinkedHashMap, not Map.of — Map.of caps at 10 pairs and rejects
        // nulls, so `park` could not be ABSENT (its "not parked" encoding) under Map.of.
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("iteration", e.iteration());
        snapshot.put("budgetRemaining", e.budgetRemaining());
        snapshot.put("toolCallsExecuted", e.toolCallsExecuted());
        snapshot.put("messageCount", e.messageCount());
        // Guarded like HandoffProposed.reason — a null agent id would NPE Map.of.
        snapshot.put("activeAgentId", e.activeAgentId() != null ? e.activeAgentId() : "");
        // Tempdoc 834 §6.3.3: ALWAYS emitted (empty list = "none pending"). A legacy events.ndjson
        // record simply LACKS the key, and absent must read as UNKNOWN — never as "none".
        snapshot.put(
            "pendingApprovals", e.pendingApprovals().stream().map(AgentEventPayloads::approvalMap).toList());
        snapshot.put("autonomyLevel", e.autonomyLevel());
        if (e.park() != null) {
          snapshot.put("park", parkMap(e.park()));
        }
        yield snapshot;
      }
    };
  }

  /** Tempdoc 834 §6.2 — one held approval gate, in the same shape {@code tool_call_pending} uses. */
  private static Map<String, Object> approvalMap(AgentEvent.PendingApproval approval) {
    var m = new LinkedHashMap<String, Object>();
    m.put("callId", approval.callId());
    m.put("toolName", approval.toolName());
    m.put("arguments", approval.arguments());
    m.put("risk", approval.risk());
    if (approval.gateBehavior() != null) {
      m.put("gateBehavior", approval.gateBehavior());
    }
    return m;
  }

  /** Tempdoc 834 §6.2 — why the run is stopped; only emitted when the run IS parked. */
  private static Map<String, Object> parkMap(AgentEvent.ParkSnapshot park) {
    var m = new LinkedHashMap<String, Object>();
    m.put("kind", park.kind());
    m.put("sinceEpochMs", park.sinceEpochMs());
    m.put("detail", park.detail() != null ? park.detail() : "");
    return m;
  }

  /**
   * Returns {@code payload} with the {@code trace} envelope appended when the trace carries identity.
   * Same field order ({@code runId} first), same null-elision, {@code iteration} always present — the
   * shape {@code AgentSseContractTest} pins. Used by BOTH the wire translator and the persistence path
   * so the durable record carries the same trace the wire does.
   */
  public static Map<String, Object> withTrace(Map<String, Object> payload, TraceContext trace) {
    if (trace == null || !trace.hasIdentity()) {
      return payload;
    }
    var out = new LinkedHashMap<String, Object>(payload);
    out.put("trace", toTraceMap(trace));
    return out;
  }

  private static Map<String, Object> toTraceMap(TraceContext trace) {
    var out = new LinkedHashMap<String, Object>();
    if (trace.runId() != null) {
      out.put("runId", trace.runId());
    }
    if (trace.stepId() != null) {
      out.put("stepId", trace.stepId());
    }
    if (trace.spanId() != null) {
      out.put("spanId", trace.spanId());
    }
    if (trace.parentSpanId() != null) {
      out.put("parentSpanId", trace.parentSpanId());
    }
    if (trace.agentId() != null) {
      out.put("agentId", trace.agentId());
    }
    if (trace.toolCallId() != null) {
      out.put("toolCallId", trace.toolCallId());
    }
    out.put("iteration", trace.iteration());
    return out;
  }

  /**
   * Tool-completed payload, widened to carry {@code structuredData} when present (tempdoc 560 Phase 1).
   */
  private static Map<String, Object> toolCompletedPayload(AgentEvent.ToolExecutionCompleted e) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("callId", e.callId());
    payload.put("success", e.result().success());
    payload.put("output", e.result().message());
    payload.put("executionId", e.result().executionId().orElse(""));
    if (!e.result().structuredData().isEmpty()) {
      payload.put("structuredData", e.result().structuredData());
    }
    return payload;
  }
}
