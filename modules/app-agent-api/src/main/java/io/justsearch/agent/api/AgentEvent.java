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

  /**
   * Tool execution completed.
   *
   * <p>Tempdoc 878 §D.4 — {@code outputCharsToModel} is how much of {@link OperationResult#message}
   * was actually placed in the prompt. The wire has always carried the tool's WHOLE output while the
   * loop appended a Layer-2-truncated copy to the conversation, so the reader was shown more than
   * the model ever saw, with nothing on the event saying so.
   *
   * <p>ONE component, not a count plus a boolean: "truncated for the model" is DERIVED from the
   * count ({@link #truncatedForModel}) so the two can never contradict each other. {@link
   * #CHARS_TO_MODEL_UNMEASURED} is a real third state — an emitter that did not measure says
   * nothing, exactly as an absent {@code contextInclusion} does, rather than claiming "not
   * truncated".
   */
  record ToolExecutionCompleted(
      String callId, OperationResult result, int outputCharsToModel, TraceContext trace)
      implements AgentEvent {

    /** No emitter measured what reached the model. A consumer told nothing must say nothing. */
    public static final int CHARS_TO_MODEL_UNMEASURED = -1;

    public ToolExecutionCompleted(String callId, OperationResult result) {
      this(callId, result, CHARS_TO_MODEL_UNMEASURED, TraceContext.none());
    }

    public ToolExecutionCompleted(String callId, OperationResult result, int outputCharsToModel) {
      this(callId, result, outputCharsToModel, TraceContext.none());
    }

    public ToolExecutionCompleted {
      outputCharsToModel = Math.max(CHARS_TO_MODEL_UNMEASURED, outputCharsToModel);
    }

    /**
     * Did the model receive less than this tool returned? {@code false} when nothing was measured —
     * which is why every consumer must read {@link #outputCharsToModel} to tell "no" from "unknown".
     */
    public boolean truncatedForModel() {
      return outputCharsToModel > CHARS_TO_MODEL_UNMEASURED
          && result != null
          && result.message() != null
          && outputCharsToModel < result.message().length();
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
      String headingText,
      /**
       * Tempdoc 865 §7.5 — whether the passage this source names was still in the prompt the answer
       * was written from: the wire name of {@code DocumentService.ContextInclusion.State}, or {@link
       * #INCLUSION_ABSENT} when nothing resolved it.
       *
       * <p>Carried as a {@code String} for the same reason {@code citationScorer} and {@code
       * disposition} are: {@code app-agent-api} is annotation-light and does not depend on {@code
       * app-api}, so the enum crosses the module boundary as its wire name. That makes this a
       * PROJECTION of the one 849 authority, not a parallel vocabulary free to drift from it.
       *
       * <p>ABSENT is the state a source is MINTED in and it is not a placeholder for "included":
       * inclusion is decided by the prompt the terminal actually built, which does not exist when a
       * tool call establishes a source. A reader told nothing must say nothing (849's own rule).
       */
      String contextInclusion,
      /** Characters of the passage that reached the model; {@link #INCLUDED_CHARS_UNKNOWN} when
       *  {@link #contextInclusion} is absent. */
      int contextIncludedChars,
      /**
       * Tempdoc 865 §7.6 / 868 §B.3 — HOW this source came to be in front of the model: {@link
       * #ACQUISITION_RETRIEVED} (a search matched it) or {@link #ACQUISITION_OPENED} (the agent
       * named it and read it). 865 designed the axis and deferred it for want of a second producer;
       * {@code core.read-document} is that producer, so it lands here. The vocabulary's authority is
       * {@link SourceAcquisition} (878 §D.9); these constants project it, exactly as the three
       * sibling String fields on this record project their own enums.
       *
       * <p>The recorded invariant is directional and must not be read the other way: an
       * opened-by-name document has LESS relevance evidence than a retrieved one, never more —
       * nothing ranked it, the agent simply asked for it. {@code
       * AgentSession.documentGroundingKeys} preserves that across the two producers: a document
       * already established by EITHER search arm keeps its {@code retrieved} identity when a later
       * read returns it — including when the search keyed it by {@code parentDocId#chunkIndex} and
       * the read keys it by path, which is the normal case, since the path the model reads with is
       * usually one a search result handed it.
       *
       * <p>Unlike {@link #contextInclusion} this is an IDENTITY component, fixed at the mint: how a
       * source was acquired is knowable exactly when it is established, and never changes.
       */
      String acquisition) {

    /** No producer resolved inclusion for this source. */
    public static final String INCLUSION_ABSENT = "";

    /** No character count is knowable, because no inclusion state was resolved. */
    public static final int INCLUDED_CHARS_UNKNOWN = -1;

    /**
     * The source was found by a search: something ranked it against the query.
     *
     * <p>Tempdoc 878 §D.9 — a PROJECTION of {@link SourceAcquisition}, not a free-standing literal,
     * which is what its three sibling vocabularies on this record have always been.
     */
    public static final String ACQUISITION_RETRIEVED = SourceAcquisition.RETRIEVED.wireToken();

    /** The source was opened by name and read: nothing ranked it (868 §B.3). */
    public static final String ACQUISITION_OPENED = SourceAcquisition.OPENED.wireToken();

    public AgentSource {
      contextInclusion = contextInclusion == null ? INCLUSION_ABSENT : contextInclusion;
      contextIncludedChars =
          contextInclusion.isEmpty() ? INCLUDED_CHARS_UNKNOWN : Math.max(0, contextIncludedChars);
      // Tempdoc 878 §D.9 — normalise THROUGH the authority, so an unrecognised token resolves the
      // one way the enum documents instead of being carried forward as a value nothing can read.
      acquisition = SourceAcquisition.fromWireToken(acquisition).wireToken();
    }

    /**
     * THE MINT constructor — a source is established ABSENT (tempdoc 865 §7.5, mirroring {@code
     * DocumentService.ContextCitation}'s "constructed absent, resolved at the cut"). Inclusion is
     * not knowable where a source is minted, because the prompt it would be a fact about has not
     * been built yet.
     */
    public AgentSource(
        String parentDocId,
        int chunkIndex,
        String path,
        String title,
        String excerpt,
        int startLine,
        int endLine,
        String headingText) {
      this(
          parentDocId, chunkIndex, path, title, excerpt, startLine, endLine, headingText,
          ACQUISITION_RETRIEVED);
    }

    /**
     * THE MINT constructor for a source whose acquisition is not the default — today only {@code
     * core.read-document}'s {@link #ACQUISITION_OPENED}. Same absent-inclusion rule as the arity-8
     * mint above: a source is established ABSENT and inclusion is resolved at the cut.
     */
    public AgentSource(
        String parentDocId,
        int chunkIndex,
        String path,
        String title,
        String excerpt,
        int startLine,
        int endLine,
        String headingText,
        String acquisition) {
      this(
          parentDocId, chunkIndex, path, title, excerpt, startLine, endLine, headingText,
          INCLUSION_ABSENT, INCLUDED_CHARS_UNKNOWN, acquisition);
    }

    /**
     * Inclusion-carrying construction without an explicit acquisition — defaults to {@link
     * #ACQUISITION_RETRIEVED}, which is what every pre-868 producer meant.
     */
    public AgentSource(
        String parentDocId,
        int chunkIndex,
        String path,
        String title,
        String excerpt,
        int startLine,
        int endLine,
        String headingText,
        String contextInclusion,
        int contextIncludedChars) {
      this(
          parentDocId, chunkIndex, path, title, excerpt, startLine, endLine, headingText,
          contextInclusion, contextIncludedChars, ACQUISITION_RETRIEVED);
    }

    /**
     * Returns a copy carrying the inclusion state resolved against the FINAL prompt — the one
     * transformation that may set it, exactly as {@code ContextCitation.withInclusion} is on the RAG
     * plane. Everything upstream leaves it absent, so "nothing resolved this" and "the passage
     * reached the model" stay distinguishable on the record itself.
     */
    public AgentSource withInclusion(String state, int includedChars) {
      return new AgentSource(
          parentDocId, chunkIndex, path, title, excerpt, startLine, endLine, headingText,
          state, includedChars, acquisition);
    }
  }

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
      /**
       * Tempdoc 859 §D §2.6 — the wire name of the run's {@code TerminalDisposition}: the
       * STRUCTURAL half of the cut-short honesty fix.
       *
       * <p>The disposition is written by the loop AFTER and INDEPENDENTLY of the finalize text, so a
       * model that writes a confident, complete-sounding answer at the budget edge cannot suppress
       * it. Before this field it was set, persisted, observed as a metric tag — and then dropped at
       * the wire, which left the run's honesty depending entirely on the model choosing to disclose.
       * That is the exact dependency an honesty fix has to remove.
       *
       * <p>Carried as a {@code String}, not the enum: {@code TerminalDisposition} is package-private
       * in {@code app-agent} while this record lives in {@code app-agent-api}, so it crosses the
       * module boundary as {@code .name()}. That makes it a PROJECTION of the one existing
       * authority, not a parallel enum free to drift from it.
       *
       * <p>Null/absent is legitimate and means "this emitter did not say" — the ungrounded legacy
       * overloads, and records persisted before the field existed.
       */
      String disposition,
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
      this(finalResponse, iterationsUsed, toolCallsExecuted, totalTokensUsed, List.of(), List.of(), SCORER_NONE, null, TraceContext.none());
    }

    public AgentDone(
        String finalResponse,
        int iterationsUsed,
        int toolCallsExecuted,
        int totalTokensUsed,
        TraceContext trace) {
      this(finalResponse, iterationsUsed, toolCallsExecuted, totalTokensUsed, List.of(), List.of(), SCORER_NONE, null, trace);
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
      this(finalResponse, iterationsUsed, toolCallsExecuted, totalTokensUsed, sources, citations, citationScorer, null, TraceContext.none());
    }

    /** Tempdoc 859 §D §2.6 — grounded, and declaring the disposition it terminated under. */
    public AgentDone(
        String finalResponse,
        int iterationsUsed,
        int toolCallsExecuted,
        int totalTokensUsed,
        List<AgentSource> sources,
        List<AgentSentenceCite> citations,
        String citationScorer,
        String disposition) {
      this(finalResponse, iterationsUsed, toolCallsExecuted, totalTokensUsed, sources, citations, citationScorer, disposition, TraceContext.none());
    }

    // Tempdoc 878 §D.1 — `ofDisposition`, the UNGROUNDED terminal factory 859 §D §2.6 added for the
    // max-iterations ceiling, is deleted here with its only caller. It existed because the ceiling
    // had no route to `AgentStepRunner.groundedDone`, so a terminal that carried the run's evidence
    // would have tripped the grounding-seam audit's `java.util.List` signature discriminator. The
    // ceiling now finalizes THROUGH that seam (`AgentStepRunner.finalizeAtIterationCeiling`), so the
    // exemption has no subject left — and terminal equivalence covers three dispositions instead of
    // two. Left standing it would be a second, ungrounded way to emit a terminal.
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

    /**
     * Tempdoc 859 §D (F6 follow-up) — the ACCOUNTABILITY phases: a decision the loop took ON THE
     * READER'S BEHALF, or a change it made to the run's material inputs. They are named as constants
     * because TWO sites must agree on the token — the emitter, and the read-time projection that
     * makes the note durable ({@code AgentInteractionMapper}, which is what keeps the narration alive
     * across a reload). A routine liveness phase ({@code llm_call}, {@code init}, {@code finalizing},
     * the gate-held pair, {@code workflow:*}) has one producer and no second reader that must match,
     * so it stays a literal at its emit site.
     */
    public static final String PHASE_BUDGET_RAISED = "budget_raised";

    /** @see #PHASE_BUDGET_RAISED */
    public static final String PHASE_CONTEXT_GATE_REAPPLIED = "context_gate_reapplied";

    /**
     * Tempdoc 859 §D (F6 follow-up) — the context gate ASKED and nobody answered, so the run
     * proceeded on its own. The other half of the same guard rail as {@link #PHASE_BUDGET_RAISED}:
     * a watcherless gate falling back to CONTINUE is a silent continue by definition.
     *
     * @see #PHASE_BUDGET_RAISED
     */
    public static final String PHASE_CONTEXT_GATE_UNANSWERED = "context_gate_unanswered";

    /**
     * NAME COLLISION, deliberate and load-bearing to know about: this phase token is spelled exactly
     * like the {@code context_compacted} journal EVENT TYPE ({@code AgentEventPayloads.name} for
     * {@link ContextCompacted}). They are different things on different axes — one is a
     * {@code progress} record's {@code phase} field, the other is a record's {@code eventType} — and
     * a reader that greps the string finds both. Named here so 865's teardown does not assume one
     * hit is the other.
     *
     * @see #PHASE_BUDGET_RAISED
     */
    public static final String PHASE_CONTEXT_COMPACTED = "context_compacted";

    /**
     * Tempdoc 859 D live-defect D4 — the run compacted DESPITE the reader answering CONTINUE,
     * because the next prompt would not have fit the model's window.
     *
     * <p>This is the sharpest accountability phase of the set: the others narrate a decision nobody
     * made, while this one narrates the loop DEPARTING from a decision the reader did make. It sits
     * beside {@link #PHASE_CONTEXT_COMPACTED} in the same emit block, so leaving it a literal would
     * have made it the one note that vanishes on reload while its neighbour survives — the reader
     * would come back to a compaction with its justification silently removed.
     *
     * @see #PHASE_BUDGET_RAISED
     */
    public static final String PHASE_CONTEXT_COMPACTED_TO_FIT = "context_compacted_to_fit";

    /**
     * Owner decision 2026-08-26 (late cancel) — the READER pressed Stop on this run.
     *
     * <p>The four phases above narrate a decision the loop took on the reader's behalf; this one
     * narrates the reader's OWN decision, which is the same accountability axis read from the other
     * end. It is durable for the reason the others are and one more: a stop that lands in the same
     * instant the run reaches its terminal changes nothing about the run, so the only place that act
     * can survive is the record. Without it a reader who pressed Stop comes back to a turn reading
     * "finished" — the window telling them the thing they did never happened.
     *
     * @see #PHASE_BUDGET_RAISED
     */
    public static final String PHASE_STOP_REQUESTED = "stop_requested";

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

  /**
   * Session started — sent once at the beginning with the session ID.
   *
   * <p>It also carries the sampling the run will ACTUALLY use (lane F PR 0b): the agent preset with
   * the request's optional {@code sampling} override applied, as
   * {@code AgentLlmCaller.agentBaseSampling} resolves it. Without this a caller can only observe
   * what it REQUESTED — a capture taken against a build that predates the override would record a
   * pin it silently never applied and read as pinned. All three are nullable and independently
   * optional: {@code samplingSeed} is null on every run that pinned no seed, which is every run
   * today, and an absent field on the wire is what a pre-PR-0b build produces.
   *
   * @param sessionId the run's id
   * @param samplingTemperature the temperature every LLM call in this run will use
   * @param samplingTopP the nucleus mass every LLM call in this run will use
   * @param samplingSeed the RNG seed sent to llama-server, or null when the run pinned none
   * @param trace the shared trace envelope
   */
  record SessionStarted(
      String sessionId,
      Double samplingTemperature,
      Double samplingTopP,
      Long samplingSeed,
      TraceContext trace)
      implements AgentEvent {

    /** Back-compat constructor (pre-lane-F-PR-0b): no applied-sampling echo. */
    public SessionStarted(String sessionId, TraceContext trace) {
      this(sessionId, null, null, null, trace);
    }

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
