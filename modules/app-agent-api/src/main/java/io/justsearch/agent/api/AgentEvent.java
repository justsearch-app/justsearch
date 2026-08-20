/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.List;

/**
 * Events emitted by the agent loop during execution.
 *
 * <p>Per tempdoc 429 §A.2 + Phase 10: events carry {@link RiskTier} (the substrate
 * vocabulary) for tool-call risk; tool execution events carry {@link OperationResult}
 * (substrate result type). SSE projections emit {@code risk: "low"|"medium"|"high"}
 * directly — the substrate's vocabulary drives the wire format end-to-end. The
 * legacy {@code safetyLevel} translation shim was removed in rev 9 (compromise C2
 * resolution).
 */
public sealed interface AgentEvent {

  /** Returns the trace context for this event. */
  default TraceContext trace() {
    return TraceContext.none();
  }

  /** LLM is generating text content. */
  record TextChunk(String text, TraceContext trace) implements AgentEvent {
    public TextChunk(String text) {
      this(text, TraceContext.none());
    }
  }

  /** LLM proposed a tool call. */
  record ToolCallProposed(ToolCallRequest call, RiskTier risk, TraceContext trace)
      implements AgentEvent {
    public ToolCallProposed(ToolCallRequest call, RiskTier risk) {
      this(call, risk, TraceContext.none());
    }
  }

  /**
   * Tempdoc 550 N1: the full set of tool calls the LLM proposed THIS turn, emitted ONCE before
   * any per-call gate runs — so a consumer can preview the whole turn's plan ("the agent wants
   * to do X, Y, Z") before the first approval. Purely additive: the per-call safety gate and the
   * reject/synthetic-tool-result flow are unchanged; this is a heads-up, never a replacement for
   * the authoritative per-call gating.
   */
  record ToolBatchProposed(List<ToolCallRequest> calls, TraceContext trace)
      implements AgentEvent {
    public ToolBatchProposed(List<ToolCallRequest> calls) {
      this(List.copyOf(calls), TraceContext.none());
    }
  }

  /**
   * A tool call is waiting for user approval.
   *
   * <p>Tempdoc 561 P-D1: {@code gateBehavior} is the backend trust-lattice's authoritative verdict
   * for this dispatch ({@code IntentGateEvaluator.evaluate(risk, AGENT_LOOP)}), attached at emit
   * time via the {@code IntentPreviewer} so the FE renders the backend's actual decision instead of
   * re-deriving its own. Nullable: absent (legacy/test wiring with no previewer) means the FE falls
   * back to its dial-derived explanation.
   */
  record ToolCallPendingApproval(
      String callId,
      String toolName,
      String arguments,
      RiskTier risk,
      GateBehavior gateBehavior,
      TraceContext trace)
      implements AgentEvent {
    /** Pre-561 trace-carrying constructor — no backend gate verdict (FE falls back). */
    public ToolCallPendingApproval(
        String callId, String toolName, String arguments, RiskTier risk, TraceContext trace) {
      this(callId, toolName, arguments, risk, null, trace);
    }

    /** Tempdoc 561 P-D1: emit with the backend gate verdict; trace defaults to none. */
    public ToolCallPendingApproval(
        String callId, String toolName, String arguments, RiskTier risk, GateBehavior gateBehavior) {
      this(callId, toolName, arguments, risk, gateBehavior, TraceContext.none());
    }

    public ToolCallPendingApproval(
        String callId, String toolName, String arguments, RiskTier risk) {
      this(callId, toolName, arguments, risk, null, TraceContext.none());
    }
  }

  /** A tool call was approved (auto or by user). */
  record ToolCallApproved(String callId, TraceContext trace) implements AgentEvent {
    public ToolCallApproved(String callId) {
      this(callId, TraceContext.none());
    }
  }

  /**
   * Tempdoc 565 §30 — the agent loop acknowledged a human mid-run STEERING directive (the DIRECTION
   * authority's {@code interject}). Emitted at the step boundary when the loop drains a queued
   * directive (POST /api/chat/agent/steer) and folds its text into the next LLM call. The FE renders
   * it as a human-origin run-spine landmark ("Your direction: …"). Mirrors {@link ToolCallApproved}.
   */
  record DirectiveAcknowledged(String directiveText, TraceContext trace) implements AgentEvent {
    public DirectiveAcknowledged(String directiveText) {
      this(directiveText, TraceContext.none());
    }
  }

  /** Tool execution has started. */
  record ToolExecutionStarted(String callId, String toolName, TraceContext trace)
      implements AgentEvent {
    public ToolExecutionStarted(String callId, String toolName) {
      this(callId, toolName, TraceContext.none());
    }
  }

  /** Tool execution completed. */
  record ToolExecutionCompleted(String callId, OperationResult result, TraceContext trace)
      implements AgentEvent {
    public ToolExecutionCompleted(String callId, OperationResult result) {
      this(callId, result, TraceContext.none());
    }
  }

  /** User rejected a tool call. */
  record ToolCallRejected(String callId, String reason, TraceContext trace)
      implements AgentEvent {
    public ToolCallRejected(String callId, String reason) {
      this(callId, reason, TraceContext.none());
    }
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — emitted when the LLM invokes a
   * {@code vop_*}-prefixed virtual tool. The FE listens for this event,
   * resolves the wireName via {@code VirtualOperationCatalog.resolveAgentToolCall},
   * invokes the corresponding shell/plugin command via {@code CommandRegistry},
   * and POSTs the result back via {@code POST /api/chat/agent/tool-result}.
   * The agent loop blocks on a {@link java.util.concurrent.CompletableFuture}
   * registered on the session keyed by callId until the FE responds or the
   * 30s timeout fires.
   */
  record ToolCallVirtual(String callId, String wireName, String arguments, TraceContext trace)
      implements AgentEvent {
    public ToolCallVirtual(String callId, String wireName, String arguments) {
      this(callId, wireName, arguments, TraceContext.none());
    }
  }

  /**
   * Tempdoc 565 §3.A — one grounding source behind the agent's answer: a chunk-identified local
   * passage. {@code parentDocId}+{@code chunkIndex} key the source for answer↔source matching;
   * {@code path}+{@code startLine}/{@code endLine} let the FE deep-link to the exact local lines
   * (the differentiator web-grounded tools cannot do). Carries NO ranking score (uncalibrated; 559).
   */
  record AgentSource(
      String parentDocId,
      int chunkIndex,
      String path,
      String title,
      String excerpt,
      int startLine,
      int endLine,
      String headingText) {}

  /**
   * Tempdoc 565 §3.A — one answer sentence matched to a grounding source (the inline-citation link).
   * {@code sourceIndex} indexes into the {@link AgentDone#sources()} list. Present only when the
   * authoritative answer↔source matcher ran and matched; the sources stand alone without it.
   */
  record AgentSentenceCite(String sentenceText, int sourceIndex, double similarity) {}

  /** Agent loop finished successfully. */
  record AgentDone(
      String finalResponse,
      int iterationsUsed,
      int toolCallsExecuted,
      int totalTokensUsed,
      /** Tempdoc 565 §3.A — the grounding sources behind this answer (clickable local passages). */
      List<AgentSource> sources,
      /** Tempdoc 565 §3.A — the per-sentence source matches (inline citations); may be empty. */
      List<AgentSentenceCite> citations,
      /**
       * Tempdoc 859 §4 — WHICH producer wrote the {@code similarity} on every {@link
       * AgentSentenceCite} above: the wire name of {@code DocumentService.ScorerKind}. The 836 §4
       * producer gate is implemented at the FE read site, so a producer that drops this field
       * defeats it (859 Reach 2) — the agent plane did exactly that. Carried here so the gate has
       * its input on the agent plane too.
       *
       * <p>Never null: an emitter that ran no matcher stamps {@link #SCORER_NONE}, which fails the
       * gate CLOSED (sources without inline marks — {@code AgentCitationResolver}'s documented
       * degradation, 565 §10). Omission is reserved for the wire, where an ABSENT key means "a
       * record persisted before this field existed" and keeps the pre-stamp allowance.
       */
      String citationScorer,
      TraceContext trace)
      implements AgentEvent {
    /**
     * The wire name of {@code DocumentService.ScorerKind.NONE} — "nothing scored these".
     *
     * <p>A literal rather than the enum because {@code app-agent-api} is annotation-light and does
     * not depend on {@code app-api}; every emitter that CAN see the enum stamps {@code
     * ScorerKind…name()} instead, and {@code DocumentService.ScorerKind.fromWire} maps anything
     * unrecognized back to {@code NONE}, so the two cannot disagree in a way that admits a mark.
     */
    public static final String SCORER_NONE = "NONE";

    public AgentDone {
      sources = sources == null ? List.of() : List.copyOf(sources);
      citations = citations == null ? List.of() : List.copyOf(citations);
      citationScorer =
          citationScorer == null || citationScorer.isBlank() ? SCORER_NONE : citationScorer;
    }

    public AgentDone(
        String finalResponse, int iterationsUsed, int toolCallsExecuted, int totalTokensUsed) {
      this(finalResponse, iterationsUsed, toolCallsExecuted, totalTokensUsed, List.of(), List.of(), SCORER_NONE, TraceContext.none());
    }

    public AgentDone(
        String finalResponse,
        int iterationsUsed,
        int toolCallsExecuted,
        int totalTokensUsed,
        TraceContext trace) {
      this(finalResponse, iterationsUsed, toolCallsExecuted, totalTokensUsed, List.of(), List.of(), SCORER_NONE, trace);
    }

    /** Tempdoc 565 §3.A — finished with grounding evidence attached. */
    public AgentDone(
        String finalResponse,
        int iterationsUsed,
        int toolCallsExecuted,
        int totalTokensUsed,
        List<AgentSource> sources,
        List<AgentSentenceCite> citations,
        String citationScorer) {
      this(finalResponse, iterationsUsed, toolCallsExecuted, totalTokensUsed, sources, citations, citationScorer, TraceContext.none());
    }
  }

  /** Agent encountered an error. */
  record AgentError(
      String error,
      String errorCode,
      String errorClass,
      String retryAction,
      Integer retryAttempt,
      TraceContext trace)
      implements AgentEvent {
    public AgentError(String error, String errorCode) {
      this(error, errorCode, null, null, null, TraceContext.none());
    }

    public AgentError(
        String error,
        AgentErrorCode errorCode,
        AgentErrorClass errorClass,
        RetryAction retryAction,
        Integer retryAttempt) {
      this(
          error,
          errorCode != null ? errorCode.name() : null,
          errorClass != null ? errorClass.name() : null,
          retryAction != null ? retryAction.name() : null,
          retryAttempt,
          TraceContext.none());
    }
  }

  /**
   * Progress update from the agent loop.
   *
   * <p>Tempdoc 561 #5/§19: {@code severity} ({@link #INFO}/{@link #WARN}/{@link #ERROR}) lets the
   * renderer decorate a progress line by intent rather than tagging every routine phase with a
   * warning glyph. Routine phases (session start, "Calling LLM") are {@link #INFO} (no glyph); only a
   * genuine warning/failure carries {@link #WARN}/{@link #ERROR}.
   */
  record AgentProgress(
      String phase,
      String message,
      int iteration,
      int maxIterations,
      String severity,
      TraceContext trace)
      implements AgentEvent {

    /** Routine progress — the renderer shows no warning glyph. */
    public static final String INFO = "info";
    /** A non-fatal warning the renderer may decorate. */
    public static final String WARN = "warn";
    /** A failure phase the renderer decorates as an error. */
    public static final String ERROR = "error";

    /** Routine progress (default {@link #INFO} severity, no trace). */
    public AgentProgress(String phase, String message, int iteration, int maxIterations) {
      this(phase, message, iteration, maxIterations, INFO, TraceContext.none());
    }

    /** Progress with an explicit severity, no trace. */
    public AgentProgress(
        String phase, String message, int iteration, int maxIterations, String severity) {
      this(phase, message, iteration, maxIterations, severity, TraceContext.none());
    }

    /** Routine progress carrying a trace context (default {@link #INFO} severity). */
    public AgentProgress(
        String phase, String message, int iteration, int maxIterations, TraceContext trace) {
      this(phase, message, iteration, maxIterations, INFO, trace);
    }
  }

  /**
   * Token budget update during agent execution.
   *
   * @param phase "iteration_start" (projected tokens) or "llm_response" (actual usage)
   * @param tokensConsumed tokens used in this phase
   * @param tokensRemaining remaining token budget
   * @param totalTokensConsumed run-cumulative tokens consumed (tempdoc 577 Ext III — the per-phase
   *     {@code tokensConsumed} cannot reconstruct the budget ceiling after iteration 1; 577 §2.9 V4).
   *     {@code 0} on legacy/compat construction ⇒ consumers fall back to the per-phase derivation.
   * @param promptTokens tempdoc 577 §2.14 Root II (#14) — the latest LLM call's prompt size, i.e.
   *     the CURRENT context occupancy (the cognitive-headroom numerator). {@code 0} when unknown.
   * @param contextWindow tempdoc 577 §2.14 Root II (#14) — the model's context window (n_ctx), the
   *     headroom denominator. {@code 0} when unknown ⇒ the FE shows no horizon ratio. Distinguishes
   *     "ran out of money" (budget) from "ran out of memory" (context) — the resource-family meter.
   */
  record AgentBudgetUpdate(
      String phase,
      int tokensConsumed,
      int tokensRemaining,
      int totalTokensConsumed,
      int promptTokens,
      int contextWindow,
      TraceContext trace)
      implements AgentEvent {
    public AgentBudgetUpdate(String phase, int tokensConsumed, int tokensRemaining) {
      this(phase, tokensConsumed, tokensRemaining, 0, 0, 0, TraceContext.none());
    }

    public AgentBudgetUpdate(
        String phase, int tokensConsumed, int tokensRemaining, int totalTokensConsumed) {
      this(phase, tokensConsumed, tokensRemaining, totalTokensConsumed, 0, 0, TraceContext.none());
    }

    /** Tempdoc 577 §2.14 Root II — the full economic + cognitive figures (no trace). */
    public AgentBudgetUpdate(
        String phase,
        int tokensConsumed,
        int tokensRemaining,
        int totalTokensConsumed,
        int promptTokens,
        int contextWindow) {
      this(
          phase,
          tokensConsumed,
          tokensRemaining,
          totalTokensConsumed,
          promptTokens,
          contextWindow,
          TraceContext.none());
    }
  }

  /**
   * Tempdoc 577 §2.12 Move 2 — the run is PARKED at the budget boundary as a held decision (the
   * budget analogue of {@link ToolCallPendingApproval}). The FE renders the decision point
   * (continue +N via the raise endpoint / finalize / stop via the decision endpoint); the loop
   * blocks until a decision arrives or its timeout falls back to the legacy finalize-else-error.
   *
   * @param tokensNeeded projected tokens for the next iteration (what the gate could not afford)
   * @param tokensRemaining remaining budget at the boundary (may be ≤ 0)
   * @param totalTokensConsumed run-cumulative consumption (the ceiling derivation input)
   */
  record BudgetGatePending(
      int tokensNeeded, int tokensRemaining, int totalTokensConsumed, TraceContext trace)
      implements AgentEvent {
    public BudgetGatePending(int tokensNeeded, int tokensRemaining, int totalTokensConsumed) {
      this(tokensNeeded, tokensRemaining, totalTokensConsumed, TraceContext.none());
    }
  }

  /**
   * Tempdoc 577 §2.14 Root II (#14) — the run is PARKED at the context-pressure boundary as a held
   * decision (the COGNITIVE sibling of {@link BudgetGatePending}). The next prompt is approaching the
   * model's context window (n_ctx); the FE renders the decision point (continue anyway / compact
   * older turns / stop via the context-decision endpoint). The loop blocks until a decision arrives
   * or its timeout falls back to CONTINUE (a watcherless gate behaves as before — no surprise park).
   *
   * @param promptTokens the projected next-prompt occupancy approaching the window
   * @param contextWindow the model's context window (n_ctx), the headroom denominator
   */
  record ContextGatePending(int promptTokens, int contextWindow, TraceContext trace)
      implements AgentEvent {
    public ContextGatePending(int promptTokens, int contextWindow) {
      this(promptTokens, contextWindow, TraceContext.none());
    }
  }

  /**
   * Tempdoc 577 §2.14 Root II (#14) — a first-class, narratable compaction event: the run compacted
   * older turns to stay within the model's memory. Surfaced so context management is HONEST (the
   * user sees that earlier turns were dropped from the working set), not silent server-side shifting.
   *
   * @param droppedMessages how many older messages were compacted out of the working set
   */
  record ContextCompacted(int droppedMessages, TraceContext trace) implements AgentEvent {
    public ContextCompacted(int droppedMessages) {
      this(droppedMessages, TraceContext.none());
    }
  }

  /** Session started — sent once at the beginning with the session ID. */
  record SessionStarted(String sessionId, TraceContext trace) implements AgentEvent {
    public SessionStarted(String sessionId) {
      this(sessionId, TraceContext.none());
    }
  }

  /**
   * Active agent proposed handing off to another agent role.
   *
   * <p>Emitted when the LLM calls a {@code handoff_to_<agentId>} tool. The handoff is not yet
   * committed at this point — {@link HandoffExecuted} follows immediately if the target is valid.
   */
  record HandoffProposed(String fromAgentId, String toAgentId, String reason, TraceContext trace)
      implements AgentEvent {
    public HandoffProposed(String fromAgentId, String toAgentId, String reason) {
      this(fromAgentId, toAgentId, reason, TraceContext.none());
    }
  }

  /**
   * Handoff committed — the new agent is now active.
   *
   * <p>Approval gates from the previous agent have been cleared. Any write or destructive actions
   * must be re-approved under the new agent role.
   */
  record HandoffExecuted(String fromAgentId, String toAgentId, TraceContext trace)
      implements AgentEvent {
    public HandoffExecuted(String fromAgentId, String toAgentId) {
      this(fromAgentId, toAgentId, TraceContext.none());
    }
  }

  /** LLM is generating reasoning/thinking content (separate channel from text). */
  record ReasoningChunk(String text, TraceContext trace) implements AgentEvent {
    public ReasoningChunk(String text) {
      this(text, TraceContext.none());
    }
  }

  /**
   * Tempdoc 585 §D Phase 2 (C4, AG-UI-inspired) — a one-shot snapshot of the run's CURRENT state,
   * emitted to a (re)attaching observer BEFORE the buffered-event replay so a late attacher
   * reconstructs "where the run stands" (iteration, budget, active agent, message count) without
   * having to replay the full event history. Pairs with B1: a precise {@code Last-Event-ID} reconnect
   * receives the snapshot as a state primer, then only the events it missed. The AG-UI {@code
   * STATE_SNAPSHOT} analogue the §D.2 grounding noted we lacked.
   */
  record StateSnapshot(
      int iteration,
      int budgetRemaining,
      int toolCallsExecuted,
      int messageCount,
      String activeAgentId,
      List<PendingApproval> pendingApprovals,
      String autonomyLevel,
      ParkSnapshot park,
      TraceContext trace)
      implements AgentEvent {

    /**
     * Tempdoc 834 §6.1 — the recovery law: every fact required to ACT on a run lives in the
     * snapshot; the ring carries narrative only. {@code pendingApprovals} is empty-never-null (an
     * empty list means "none pending"; an ABSENT key on a legacy persisted record means "unknown"),
     * {@code autonomyLevel} is never null, and {@code park} is nullable — absent means not parked.
     */
    public StateSnapshot {
      pendingApprovals = pendingApprovals == null ? List.of() : List.copyOf(pendingApprovals);
      autonomyLevel =
          autonomyLevel == null
              ? io.justsearch.agent.api.registry.AutonomyLevel.DEFAULT.name()
              : autonomyLevel;
    }

    /** Pre-834 convenience overload — no approvals, the default dial, not parked. */
    public StateSnapshot(
        int iteration,
        int budgetRemaining,
        int toolCallsExecuted,
        int messageCount,
        String activeAgentId) {
      this(iteration, budgetRemaining, toolCallsExecuted, messageCount, activeAgentId,
          List.of(), io.justsearch.agent.api.registry.AutonomyLevel.DEFAULT.name(), null,
          TraceContext.none());
    }

    /** Pre-834 trace-carrying overload — no approvals, the default dial, not parked. */
    public StateSnapshot(
        int iteration,
        int budgetRemaining,
        int toolCallsExecuted,
        int messageCount,
        String activeAgentId,
        TraceContext trace) {
      this(iteration, budgetRemaining, toolCallsExecuted, messageCount, activeAgentId,
          List.of(), io.justsearch.agent.api.registry.AutonomyLevel.DEFAULT.name(), null, trace);
    }
  }

  /**
   * Tempdoc 834 §6.2 — one tool call held at an approval gate, carried ON the snapshot so a
   * reattacher can render and ANSWER the gate even after the {@code tool_call_pending} frame that
   * announced it has been evicted from the replay ring. The five fields are exactly what
   * {@code AgentToolDispatcher} already emits on {@link ToolCallPendingApproval} one statement
   * before it opens the gate; {@code risk} / {@code gateBehavior} carry the lowercase wire tokens
   * that event's payload uses ({@code gateBehavior} is null when no evaluator was available).
   */
  record PendingApproval(
      String callId, String toolName, String arguments, String risk, String gateBehavior) {}

  /**
   * Tempdoc 834 §1.5 / §6.2 — why a run is currently stopped. {@code kind} is one of
   * {@code approval} / {@code budget} / {@code context} / {@code unobserved};
   * {@code sinceEpochMs} is when the park began, or {@code 0} when the park has no recorded start
   * (the zero-observer park is derived from an observer count, not from a transition).
   */
  record ParkSnapshot(String kind, long sinceEpochMs, String detail) {}
}
