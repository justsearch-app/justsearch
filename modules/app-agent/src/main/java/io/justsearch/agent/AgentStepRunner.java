/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentErrorClass;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.RetryAction;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.registry.AgentToolEmitter;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RecoveryAction;
import io.justsearch.agent.api.registry.ResolutionRecoveryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-iteration agent-loop step (tempdoc 240 W8 — extracted from {@code AgentLoopService}).
 * {@link #executeIteration} runs one iteration and returns a typed {@link IterationOutcome}; the
 * thin {@code runAgent} driver loops until an outcome is terminal. Shared helpers the loop
 * prologue/resume paths also use ({@code checkpoint}, {@code emitError}, {@code swapSystemPrompt})
 * are supplied as functional callbacks to avoid a circular dependency on AgentLoopService.
 */
final class AgentStepRunner {

  private static final Logger LOG = LoggerFactory.getLogger(AgentStepRunner.class);

  /** Persists a session checkpoint (delegates to {@code AgentLoopService.checkpoint}). */
  @FunctionalInterface
  interface Checkpointer {
    void checkpoint(String sessionId, AgentSession session, String state, String resumeNote);
  }

  /** Emits a typed agent error (delegates to {@code AgentLoopService.emitError}). */
  @FunctionalInterface
  interface ErrorEmitter {
    void emitError(
        Consumer<AgentEvent> sink,
        String message,
        AgentErrorCode code,
        AgentErrorClass errorClass,
        RetryAction retryAction,
        Integer retryAttempt);
  }

  /** Swaps the first system message for a profile prompt (delegates to AgentLoopService). */
  @FunctionalInterface
  interface SystemPromptSwapper {
    void swap(AgentSession session, AgentProfile toProfile);
  }

  private final OnlineAiService onlineAiService;
  private final OperationCatalog operationCatalog;
  private final AgentToolEmitter agentToolEmitter;
  private final AgentTelemetry agentTelemetry;
  private final AgentRunStore runStore;
  private final AgentContextCompressor compressor;
  private final AgentLlmCaller llmCaller;
  private final AgentToolDispatcher toolDispatcher;
  private final Checkpointer checkpointer;
  private final ErrorEmitter errorEmitter;
  private final SystemPromptSwapper promptSwapper;

  // Tempdoc 560 WS5 (the one window) — the streaming workflow-as-tool seam. Late-bound (volatile +
  // setter) because the concrete bridge wraps WorkflowShapeRunner, which is constructed later in
  // LocalApiServer than this runner is in the app-services boot. Null until set ⇒ no workflow tools
  // are intercepted (the loop falls through to the normal synchronous executor for every call).
  private volatile io.justsearch.agent.api.registry.WorkflowToolRunner workflowToolRunner;
  // Tempdoc 565 §3.A — late-bound answer↔source matcher (needs a DocumentService, available only at
  // bootstrap). Null ⇒ answers cite their grounding sources without per-sentence inline marks.
  private volatile AgentCitationResolver citationResolver;

  AgentStepRunner(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      AgentToolEmitter agentToolEmitter,
      AgentTelemetry agentTelemetry,
      AgentRunStore runStore,
      AgentContextCompressor compressor,
      AgentLlmCaller llmCaller,
      AgentToolDispatcher toolDispatcher,
      Checkpointer checkpointer,
      ErrorEmitter errorEmitter,
      SystemPromptSwapper promptSwapper) {
    this.onlineAiService = onlineAiService;
    this.operationCatalog = operationCatalog;
    this.agentToolEmitter = agentToolEmitter;
    this.agentTelemetry = agentTelemetry;
    this.runStore = runStore;
    this.compressor = compressor;
    this.llmCaller = llmCaller;
    this.toolDispatcher = toolDispatcher;
    this.checkpointer = checkpointer;
    this.errorEmitter = errorEmitter;
    this.promptSwapper = promptSwapper;
  }

  /**
   * Tempdoc 560 WS5 — late-bind the streaming workflow-as-tool runner (set by {@code LocalApiServer}
   * once {@code WorkflowShapeRunner} exists). Idempotent; safe to call before the first agent run.
   */
  void setWorkflowToolRunner(io.justsearch.agent.api.registry.WorkflowToolRunner runner) {
    this.workflowToolRunner = runner;
  }

  /** Tempdoc 565 §3.A — late-bind the answer↔source matcher (idempotent; null disables inline marks). */
  void setCitationResolver(AgentCitationResolver resolver) {
    this.citationResolver = resolver;
  }

  /**
   * One iteration of the agent loop (tempdoc 240 W8 — extracted from {@code runAgent}). Returns a
   * typed {@link IterationOutcome}: {@code cont()} to continue the loop, {@code terminated(success)}
   * when a terminal disposition was reached (the call site already marked the session via
   * {@code session.markTerminated} and emitted the AgentDone/AgentError event before returning).
   */
  IterationOutcome executeIteration(
      int iteration,
      AgentRequest request,
      AgentSession session,
      String sessionId,
      List<Map<String, Object>> baseTools,
      AtomicReference<AgentEventTracing.Sequencer> traceSequencerRef,
      Consumer<AgentEvent> sink) {
        if (session.isCancelled()) {
          errorEmitter.emitError(
              sink,
              "Session cancelled",
              AgentErrorCode.CANCELLED,
              AgentErrorClass.CANCELLED,
              RetryAction.ABORT,
              null);
          // F1: state-first, durability-second.
          session.markTerminated(TerminalDisposition.CANCELLED, null, CancelTrigger.USER);
          checkpointer.checkpoint(sessionId, session, "CANCELLED", "Session cancelled");
          return IterationOutcome.terminated(false);
        }

        // Tempdoc 565 §30 — DIRECTION authority: drain a queued human STEERING directive at the
        // step boundary (exactly-once) and fold it into the next LLM call as a system steering note
        // (mirrors the handoff system-message `appendMessage` pattern). The FE captured the user's intent;
        // here it enters the agent's context so the NEXT step honors it, and a DirectiveAcknowledged
        // event lets the FE render it as a human-origin run-spine landmark.
        String steeringDirective = session.drainInterject();
        if (steeringDirective != null && !steeringDirective.isBlank()) {
          session.appendMessage(
              Map.of(
                  "role",
                  "system",
                  "content",
                  "Mid-run steering directive from the user: "
                      + steeringDirective
                      + " Adjust your plan accordingly for the remaining steps."));
          sink.accept(new AgentEvent.DirectiveAcknowledged(steeringDirective));
        }

        session.incrementIterations();

        // Escalation: terminate if too many loop-blocked calls across the session
        if (session.loopBlockCount() >= 5) {
          String loopMsg =
              "Agent stuck in tool loop after " + session.loopBlockCount() + " blocked attempts";
          errorEmitter.emitError(
              sink,
              loopMsg,
              AgentErrorCode.TOOL_LOOP,
              AgentErrorClass.TOOL_CONTRACT,
              RetryAction.ABORT,
              null);
          // F1: state-first, durability-second.
          session.markTerminated(TerminalDisposition.ERRORED, AgentErrorCode.TOOL_LOOP, null);
          checkpointer.checkpoint(sessionId, session, "ERROR", loopMsg);
          return IterationOutcome.terminated(false);
        }

        compressor.compressToolMessages(session.messages());

        // Direction E: state machine — if PRIMARY has used enough tool rounds without committing,
        // transition to DECIDING state and restrict tools to handoff-only.
        AgentState agentState = AgentTurnPolicy.resolveAgentState(session, request);
        // Per-iteration tool list: active-agent subset + handoff tools for other agents
        List<Map<String, Object>> tools;
        if (agentState == AgentState.DECIDING) {
          LOG.info(
              "Direction E: PRIMARY entering DECIDING state (iterations={}) — "
                  + "restricting to handoff tools",
              session.agentIterationsSinceHandoff());
          tools = AgentHandoff.buildHandoffTools(request.agentProfiles(), session.activeAgentId());
        } else if (AgentTurnPolicy.shouldForceToolCall(session)) {
          // E0a: Organizer first turn — restrict to ingest_files + handoff tools only so the
          // model cannot waste its first call on search_index or browse_folders.
          LOG.info("E0a: Organizer first turn — restricting tools to ingest_files + handoff");
          tools = buildE0aTools(request, session);
        } else {
          tools = buildIterationTools(request, baseTools, session.activeAgentId());
        }

        checkpointer.checkpoint(sessionId, session, "READY_FOR_LLM", "");

        // Tempdoc 577 §2.14 Root I (#13) — the zero-observer park: a WATCH run whose only observer
        // dropped (the SSE socket closed) must NOT proceed unsupervised. Wait — bounded — for a
        // re-attach to restore a watcher before the next step; on timeout, proceed (never deadlock).
        // Posture-graded: Assist/Auto and background runs never park (their gates self-arbitrate), so
        // this fires only for an interactive Watch run that lost its watcher. The narration buffers in
        // the hub, so the reattaching watcher replays "Paused — no one is watching".
        if (session.zeroObserverPolicy() == AgentSession.ZeroObserverPolicy.PARK) {
          long parkDeadline =
              System.nanoTime() + zeroObserverParkTimeoutSeconds() * 1_000_000_000L;
          boolean narrated = false;
          while (session.zeroObserverPolicy() == AgentSession.ZeroObserverPolicy.PARK
              && System.nanoTime() < parkDeadline) {
            if (!narrated) {
              sink.accept(
                  new AgentEvent.AgentProgress(
                      "run_unobserved_parked",
                      "Paused — no one is watching",
                      iteration + 1,
                      request.maxIterations()));
              narrated = true;
            }
            try {
              Thread.sleep(ZERO_OBSERVER_POLL_MS);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        }

        // Check token budget before next iteration
        var projectedTokens = onlineAiService.countPromptTokens(session.messages());
        if (projectedTokens.isPresent()) {
          int tokens = projectedTokens.get();
          int budgetSnapshot;
          boolean budgetExhausted;

          // Synchronized block ensures consistent budget reads
          synchronized (session) {
            budgetSnapshot = session.budgetRemaining();
            sink.accept(
                new AgentEvent.AgentBudgetUpdate(
                    "iteration_start", tokens, budgetSnapshot, session.totalTokens()));
            budgetExhausted = tokens >= budgetSnapshot;
          }

          // Tempdoc 577 §2.14 Root II (#14) — the CONTEXT-pressure gate (the cognitive sibling of the
          // budget gate below). When the next prompt approaches the model's context window (n_ctx),
          // park the run as a HELD decision OFFERING compaction (summarize older turns) — the option
          // the budget gate lacks — before the hard budget wall. Fires at most once per run, only
          // interactive runs with a known n_ctx; a watcherless/undecided gate falls back to CONTINUE
          // (no surprise park). Compaction emits a first-class ContextCompacted narratable event.
          int contextWindow = session.contextWindow();
          if (contextWindow > 0
              && !session.contextGateFired()
              && !session.isBackground()
              && tokens >= (int) (contextWindow * CONTEXT_PRESSURE_THRESHOLD)) {
            var contextGateFuture = session.createContextGate();
            sink.accept(
                new AgentEvent.AgentProgress(
                    "context_gate_held",
                    "Context filling up",
                    iteration + 1,
                    request.maxIterations()));
            sink.accept(new AgentEvent.ContextGatePending(tokens, contextWindow));
            checkpointer.checkpoint(
                sessionId, session, "WAITING_CONTEXT", "Context pressure — awaiting decision");
            AgentSession.ContextGateDecision ctxDecision =
                AgentSession.ContextGateDecision.CONTINUE;
            try {
              ctxDecision = contextGateFuture.get(contextGateTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException | java.util.concurrent.ExecutionException undecided) {
              ctxDecision = AgentSession.ContextGateDecision.CONTINUE; // watcherless ⇒ proceed
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              ctxDecision = AgentSession.ContextGateDecision.STOP;
            } finally {
              session.clearContextGate();
            }

            if (ctxDecision == AgentSession.ContextGateDecision.STOP) {
              errorEmitter.emitError(
                  sink,
                  "Stopped at the context gate by your decision.",
                  AgentErrorCode.CANCELLED,
                  AgentErrorClass.CANCELLED,
                  RetryAction.ABORT,
                  null);
              session.markTerminated(TerminalDisposition.CANCELLED, null, CancelTrigger.USER);
              checkpointer.checkpoint(sessionId, session, "CANCELLED", "Stopped at context gate");
              return IterationOutcome.terminated(false);
            }
            if (ctxDecision == AgentSession.ContextGateDecision.SUMMARIZE) {
              int dropped = session.compactOlderTurns(CONTEXT_COMPACT_KEEP_RECENT);
              if (dropped > 0) {
                sink.accept(new AgentEvent.ContextCompacted(dropped));
                sink.accept(
                    new AgentEvent.AgentProgress(
                        "context_compacted",
                        "Compacted earlier turns to stay within the model's memory",
                        iteration + 1,
                        request.maxIterations()));
                // Proceed THIS iteration with the compacted (smaller) prompt; recompute the budget
                // check below against the now-shorter message history so it reflects reality.
                var recomputed = onlineAiService.countPromptTokens(session.messages());
                if (recomputed.isPresent()) {
                  tokens = recomputed.get();
                  synchronized (session) {
                    budgetExhausted = tokens >= session.budgetRemaining();
                  }
                }
              }
            }
            // CONTINUE (or post-SUMMARIZE): fall through and proceed with the current prompt.
          }

          // Tempdoc 561 P-A3: this IS the preventative over-budget gate (§9) — it terminates the
          // loop (graceful finalize, else BUDGET_EXHAUSTED) the moment the projected next prompt
          // exceeds remaining budget. The forced-tool (E0a) / DECIDING bypass is deliberate and
          // tested (AgentLoopServiceTest#budgetExhausted_e0aBypassesFinalizeAndProceedsToLlmCall):
          // a handed-off agent that MUST make its required call is allowed one final call even when
          // budget is already negative, because terminating there would strand the handoff with no
          // work done. That legitimately drives `budgetRemaining` slightly negative — which is NOT
          // a bug (§9: "legitimately goes negative"); the fix for the live-audit's `Remain -383` is
          // the honest over-budget RENDER (budgetProjection.ts + §2.2 phase-totality), not a harder
          // gate that would break the E0a-must-proceed contract.
          if (budgetExhausted
              && !AgentTurnPolicy.shouldForceToolCall(session)
              && agentState != AgentState.DECIDING) {
            LOG.warn(
                "Insufficient budget: projected {} tokens, {} remaining.",
                tokens,
                budgetSnapshot);

            // Tempdoc 577 §2.12 Move 2 — the budget GATE: for an interactive run, exhaustion
            // parks the loop at this boundary as a HELD decision (the budget analogue of
            // WAITING_APPROVAL) instead of terminating. CONTINUE arrives from the raise endpoint
            // (budget already added); FINALIZE/STOP from the decision endpoint; timeout falls
            // back to the legacy finalize-else-error, so a watcherless or undecided gate behaves
            // exactly as before. Background runs never park (no watcher to decide).
            AgentSession.BudgetGateDecision decision = AgentSession.BudgetGateDecision.FINALIZE;
            if (!session.isBackground()) {
              // Create the gate BEFORE announcing it: a decision arriving the instant the event
              // lands (the FE click, or a synchronous test consumer) must find the gate to resolve.
              var budgetGateFuture = session.createBudgetGate();
              sink.accept(
                  new AgentEvent.AgentProgress(
                      "budget_gate_held",
                      "Waiting on budget",
                      iteration + 1,
                      request.maxIterations()));
              sink.accept(
                  new AgentEvent.BudgetGatePending(tokens, budgetSnapshot, session.totalTokens()));
              checkpointer.checkpoint(
                  sessionId, session, "WAITING_BUDGET", "Token budget exhausted — awaiting decision");
              try {
                decision = budgetGateFuture.get(budgetGateTimeoutSeconds(), TimeUnit.SECONDS);
              } catch (TimeoutException | java.util.concurrent.ExecutionException timedOut) {
                decision = AgentSession.BudgetGateDecision.FINALIZE; // undecided ⇒ legacy behavior
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                decision = AgentSession.BudgetGateDecision.STOP;
              } finally {
                session.clearBudgetGate();
              }
            }

            if (decision == AgentSession.BudgetGateDecision.STOP) {
              errorEmitter.emitError(
                  sink,
                  "Stopped at the budget gate by your decision.",
                  AgentErrorCode.CANCELLED,
                  AgentErrorClass.CANCELLED,
                  RetryAction.ABORT,
                  null);
              // F1: state-first, durability-second.
              session.markTerminated(TerminalDisposition.CANCELLED, null, CancelTrigger.BUDGET);
              checkpointer.checkpoint(sessionId, session, "CANCELLED", "Stopped at budget gate");
              return IterationOutcome.terminated(false);
            }

            if (decision == AgentSession.BudgetGateDecision.CONTINUE) {
              // Budget raised — resume the loop at this boundary and proceed with the iteration.
              LOG.info("Budget gate resolved CONTINUE — budget raised, resuming the loop");
              checkpointer.checkpoint(
                  sessionId, session, "READY_FOR_LLM", "Budget raised — continuing");
            } else {
              // FINALIZE (explicit, timeout, or background): the legacy graceful path.
              // Budget-edge graceful finalize: if we have tool results, give the
              // model one final chance to synthesize an answer without tools.
              // Bypassed when: (a) shouldForceToolCall is true (E0a — agent must call a tool),
              // or (b) state is DECIDING (PRIMARY must hand off, not summarize).
              if (session.hasSuccessfulToolResult()) {
                LOG.info("Attempting budget-edge finalize with available tool results");
                // Tempdoc 577 Move 4 — narrate the finalize decision ("Wrapping up") instead of a
                // bare "Thinking", so the user sees the run synthesizing its answer at the budget edge.
                sink.accept(
                    new AgentEvent.AgentProgress(
                        "finalizing", "Wrapping up", iteration + 1, request.maxIterations()));
                String finalizeResponse = llmCaller.attemptBudgetEdgeFinalize(session, sink);
                if (finalizeResponse != null && !finalizeResponse.isBlank()) {
                  sink.accept(groundedDone(session, finalizeResponse));
                  // F1: state-first, durability-second.
                  session.markTerminated(TerminalDisposition.BUDGET_EDGE_FINALIZE, null, null);
                  checkpointer.checkpoint(
                      sessionId, session, "DONE", "Budget-edge finalize succeeded");
                  return IterationOutcome.terminated(true);
                }
              }

              String earlyTerminationMsg =
                  String.format(
                      "Reached token budget limit (%d tokens consumed, %d projected for next"
                          + " iteration). Consider increasing context window or simplifying your"
                          + " query.",
                      session.totalTokens(), tokens);

              errorEmitter.emitError(
                  sink,
                  earlyTerminationMsg,
                  AgentErrorCode.BUDGET_EXHAUSTED,
                  AgentErrorClass.BUDGET,
                  RetryAction.ABORT,
                  null);
              // F1: state-first, durability-second.
              session.markTerminated(
                  TerminalDisposition.ERRORED, AgentErrorCode.BUDGET_EXHAUSTED, null);
              checkpointer.checkpoint(sessionId, session, "ERROR", earlyTerminationMsg);
              return IterationOutcome.terminated(false);
            }
          }
        }

        sink.accept(
            new AgentEvent.AgentProgress(
                "llm_call", "Calling LLM", iteration + 1, request.maxIterations()));
        checkpointer.checkpoint(sessionId, session, "LLM_STREAMING", "");

        LlmCallResult result;
        try {
          if (agentState == AgentState.DECIDING) {
            // DECIDING: forced tool_choice=required so PRIMARY cannot produce text.
            // Direction D: suppress thinking-prompt on this mechanical commit turn.
            result = llmCaller.callLlmWithTools(
                session, tools, sink,
                SamplingParams.AGENT.withToolChoice("required").withEnableThinking(false));
          } else {
            result = llmCaller.callLlmWithRetries(session, tools, sink);
          }
        } catch (Exception e) {
          errorEmitter.emitError(
              sink,
              "LLM call failed after retries: " + e.getMessage(),
              AgentErrorCode.LLM_TRANSIENT,
              AgentErrorClass.TRANSIENT,
              RetryAction.ABORT,
              null);
          // F1: state-first, durability-second.
          session.markTerminated(TerminalDisposition.ERRORED, AgentErrorCode.LLM_TRANSIENT, null);
          checkpointer.checkpoint(sessionId, session, "ERROR", "LLM call failed after retries");
          return IterationOutcome.terminated(false);
        }

        session.incrementAgentIterations();

        // Direction F: escalate PRIMARY text-only to forced handoff after tool use
        if (result.toolCalls().isEmpty()
            && result.textContent() != null
            && !result.textContent().isBlank()
            && AgentTurnPolicy.shouldEscalateToHandoff(session, request)) {
          LOG.info(
              "Escalating PRIMARY text-only response to forced handoff (iterations={})",
              session.agentIterationsSinceHandoff());
          session.appendMessage(Map.of("role", "assistant", "content", result.textContent()));
          var handoffTools =
              AgentHandoff.buildHandoffTools(request.agentProfiles(), session.activeAgentId());
          if (!handoffTools.isEmpty()) {
            try {
              var escalated =
                  llmCaller.callLlmWithTools(
                      session,
                      handoffTools,
                      sink,
                      // Direction I: grammar as belt-and-suspenders; server omits when tools present.
                      // Direction D: suppress thinking-prompt on this forced-commit turn.
                      SamplingParams.AGENT
                          .withToolChoice("required")
                          .withGrammar(AgentLoopService.TOOL_CALL_GRAMMAR)
                          .withEnableThinking(false));
              if (escalated != null && !escalated.toolCalls().isEmpty()) {
                result = escalated;
              }
            } catch (RuntimeException e) {
              LOG.warn("Handoff escalation failed, falling through to text response", e);
            }
          }
        }

        if (result.toolCalls().isEmpty()) {
          // Check for empty response (text empty AND no tool calls) — likely reasoning
          // token exhaustion or model failure. Emit error instead of silent empty AgentDone.
          if (result.textContent() == null || result.textContent().isBlank()) {
            LOG.warn("Empty response after retry — model produced no text and no tool calls");
            errorEmitter.emitError(
                sink,
                "Model failed to generate a response (possible reasoning token exhaustion)."
                    + " Try simplifying the query or increasing the token budget.",
                AgentErrorCode.EMPTY_RESPONSE,
                AgentErrorClass.TRANSIENT,
                RetryAction.ABORT,
                1);
            // F1: state-first, durability-second.
            session.markTerminated(
                TerminalDisposition.ERRORED, AgentErrorCode.EMPTY_RESPONSE, null);
            checkpointer.checkpoint(sessionId, session, "ERROR", "Empty model response");
            return IterationOutcome.terminated(false);
          }

          // Model responded with text only — done
          sink.accept(groundedDone(session, result.textContent()));
          // F1: state-first, durability-second.
          session.markTerminated(TerminalDisposition.COMPLETED, null, null);
          checkpointer.checkpoint(sessionId, session, "DONE", "");
          return IterationOutcome.terminated(true);
        }

        // Pre-scan for handoff: if a handoff call is present, trim the assistant message
        // so the new agent doesn't see cancelled tool IDs it never requested.
        List<ToolCallRequest> toolCalls = result.toolCalls();
        int handoffIndex = -1;
        for (int ci = 0; ci < toolCalls.size(); ci++) {
          if (AgentHandoff.isHandoffCall(toolCalls.get(ci))) {
            handoffIndex = ci;
            break;
          }
        }

        // Append assistant message BEFORE tool results (OpenAI format requires this order)
        if (handoffIndex >= 0) {
          session.appendMessage(AgentLlmCaller.buildAssistantToolCallMessage(
              result, toolCalls.subList(0, handoffIndex + 1)));
        } else {
          session.appendMessage(AgentLlmCaller.buildAssistantToolCallMessage(result));
        }

        // Process tool calls up to the handoff (or all if no handoff)
        int processLimit = handoffIndex >= 0 ? handoffIndex + 1 : toolCalls.size();
        // Tempdoc 550 N1: announce the whole turn's tool-call batch ONCE, before any per-call
        // gate fires — a heads-up preview of "what the agent will do this turn." Purely additive:
        // the per-call safety gate + reject/synthetic-result flow below are unchanged (fail-closed
        // preserved). A consumer renders this as a batch plan; approval still happens per call.
        // F2: exclude handoff calls — those are control flow (agent→agent), not user-approvable
        // tools, so they don't belong in the "what the agent will do" batch. Emit only if any
        // real tool calls remain.
        List<ToolCallRequest> batch =
            toolCalls.subList(0, processLimit).stream()
                .filter(c -> !AgentHandoff.isHandoffCall(c))
                .toList();
        if (!batch.isEmpty()) {
          sink.accept(new AgentEvent.ToolBatchProposed(batch));
        }
        for (int ci = 0; ci < processLimit; ci++) {
          ToolCallRequest call = toolCalls.get(ci);

          if (session.isCancelled()) {
            errorEmitter.emitError(
                sink,
                "Session cancelled",
                AgentErrorCode.CANCELLED,
                AgentErrorClass.CANCELLED,
                RetryAction.ABORT,
                null);
            // F1: state-first, durability-second.
            session.markTerminated(TerminalDisposition.CANCELLED, null, CancelTrigger.USER);
            checkpointer.checkpoint(sessionId, session, "CANCELLED", "Session cancelled");
            return IterationOutcome.terminated(false);
          }

          // Handoff detection — before regular tool resolution (handoff tools are not in
          // toolRegistry)
          if (AgentHandoff.isHandoffCall(call)) {
            String fromId = session.activeAgentId();
            String toId = call.toolName().substring("handoff_to_".length());
            AgentProfile toProfile = AgentHandoff.findProfile(request.agentProfiles(), toId);
            if (toProfile == null) {
              errorEmitter.emitError(
                  sink,
                  "Unknown agent target: " + toId,
                  AgentErrorCode.UNKNOWN_TOOL,
                  AgentErrorClass.TOOL_CONTRACT,
                  RetryAction.ABORT,
                  null);
              // F1: state-first, durability-second.
              session.markTerminated(
                  TerminalDisposition.ERRORED, AgentErrorCode.UNKNOWN_TOOL, null);
              checkpointer.checkpoint(sessionId, session, "ERROR", "Unknown agent: " + toId);
              return IterationOutcome.terminated(false);
            }
            String reason = AgentHandoff.extractReason(call.arguments());

            // Cycle detection — abort before emitting any events if the pair count is exceeded
            int maxH = request.maxHandoffs() != null
                ? request.maxHandoffs()
                : Math.max(1, request.agentProfiles().size()) * 3;
            int totalH = session.incrementTotalHandoffs();
            if (totalH > maxH) {
              errorEmitter.emitError(
                  sink,
                  "Handoff cycle detected: " + totalH + " total handoffs exceeded limit of " + maxH,
                  AgentErrorCode.HANDOFF_CYCLE_DETECTED,
                  AgentErrorClass.TOOL_CONTRACT,
                  RetryAction.ABORT,
                  null);
              // F1: state-first, durability-second.
              session.markTerminated(
                  TerminalDisposition.ERRORED, AgentErrorCode.HANDOFF_CYCLE_DETECTED, null);
              checkpointer.checkpoint(sessionId, session, "ERROR", "Handoff cycle: " + totalH + " total");
              return IterationOutcome.terminated(false);
            }

            sink.accept(new AgentEvent.ToolCallProposed(call, RiskTier.LOW));
            sink.accept(new AgentEvent.ToolCallApproved(call.id()));
            sink.accept(new AgentEvent.ToolExecutionStarted(call.id(), call.toolName()));
            sink.accept(new AgentEvent.HandoffProposed(fromId, toId, reason));

            // Approval boundary reset
            session.clearPendingApprovals();
            session.appendMessage(Map.of("role", "system", "content",
                "Handoff from " + fromId + " to " + toId + ". Reason: " + reason
                    + " Any write or destructive actions must be re-approved."));
            session.recordHandoff(fromId, toId, reason);

            // HandoffExecuted still carries fromId agentId in trace
            sink.accept(new AgentEvent.HandoffExecuted(fromId, toId));
            // Swap the trace Sequencer AFTER HandoffExecuted so it carries fromId
            traceSequencerRef.set(new AgentEventTracing.Sequencer(sessionId, toId));

            sink.accept(new AgentEvent.ToolExecutionCompleted(
                call.id(), OperationResult.success("Handoff to " + toId + " confirmed.")));
            session.appendMessage(Map.of(
                "role", "tool",
                "tool_call_id", call.id(),
                "content", "Handoff to " + toId + " confirmed."));

            AgentHandoff.pruneHandoffMessages(session);
            promptSwapper.swap(session, toProfile);
            // Merge ALL mid-conversation system messages into position 0.
            // pruneHandoffMessages may inject a research brief (role:system) and the handoff
            // itself inserts a "Handoff from X to Y" system message. Some templates (e.g.,
            // Qwen3.5 jinja) enforce "system message must be at the beginning" — any
            // mid-conversation system message causes llama-server HTTP 500. Merging into
            // position 0 produces a clean [system, user, assistant, tool] structure.
            List<Map<String, Object>> postHandoffMsgs = session.messages();
            for (int i = 1; i < postHandoffMsgs.size(); /* no increment */) {
              if ("system".equals(postHandoffMsgs.get(i).get("role"))) {
                String content = String.valueOf(postHandoffMsgs.get(i).get("content"));
                postHandoffMsgs.remove(i);
                String sysContent = String.valueOf(postHandoffMsgs.get(0).get("content"));
                postHandoffMsgs.set(0, Map.of("role", "system", "content",
                    sysContent + "\n\n" + content));
              } else {
                i++;
              }
            }

            checkpointer.checkpoint(sessionId, session, "READY_FOR_LLM", "");
            runStore.setHandoffState(
                sessionId, session.activeAgentId(), session.handoffHistory());
            break;
          }

          // Tempdoc 508 §11.5 / §13.5 Phase B — virtual operations
          // (`vop_*` prefix) are FE-projected from CommandRegistry
          // entries and don't appear in the core OperationCatalog.
          // Dispatch them through a separate FE-roundtrip path
          // before the regular resolveByWireName lookup, which would
          // otherwise treat them as unknown tools and inject a hint
          // (best case) or abort the session (worst case).
          if (call.toolName().startsWith("vop_")) {
            handleVirtualToolCall(session, sessionId, sink, call);
            continue;
          }

          // Resolution + recovery policy (tempdoc 499 §3.5 consumer pattern).
          var resolution = operationCatalog.resolveByWireName(call.toolName());
          var action = ResolutionRecoveryPolicy.<Operation>agentTool().decide(resolution);
          if (action instanceof RecoveryAction.InjectHint<Operation> hint) {
            LOG.debug("AgentLoop: unknown tool '{}', injecting correction hint", call.toolName());
            session.appendMessage(
                Map.of(
                    "role", "tool",
                    "tool_call_id", call.id(),
                    "content", hint.hintMessage()));
            continue;
          }
          if (action instanceof RecoveryAction.Abort<Operation> abort) {
            errorEmitter.emitError(
                sink,
                abort.reason(),
                AgentErrorCode.UNKNOWN_TOOL,
                AgentErrorClass.TOOL_CONTRACT,
                RetryAction.ABORT,
                null);
            session.markTerminated(
                TerminalDisposition.ERRORED, AgentErrorCode.UNKNOWN_TOOL, null);
            checkpointer.checkpoint(sessionId, session, "ERROR", abort.reason());
            return IterationOutcome.terminated(false);
          }
          var op = ((RecoveryAction.Proceed<Operation>) action).entry();
          sink.accept(
              new AgentEvent.ToolCallProposed(call, op.policy().risk()));

          // Safety gate
          if (op.policy().risk() != RiskTier.LOW) {
            checkpointer.checkpoint(sessionId, session, "WAITING_APPROVAL", "Waiting for tool approval: " + call.toolName());
          }
          boolean approved = toolDispatcher.handleSafetyGate(session, call, op, sink);
          if (!approved) {
            sink.accept(
                new AgentEvent.ToolCallRejected(call.id(), "User rejected"));
            // Append rejection to conversation and continue
            session.appendMessage(Map.of(
                "role", "tool",
                "tool_call_id", call.id(),
                "content", "Tool call rejected by user"));
            checkpointer.checkpoint(sessionId, session, "READY_FOR_LLM", "Tool call rejected: " + call.toolName());
            continue;
          }

          sink.accept(new AgentEvent.ToolCallApproved(call.id()));

          // Loop guard: check BEFORE execution to avoid wasting tokens on repeated calls
          if (session.wouldExceedLoopThreshold(call, 3)) {
            session.recordBlockedCall(call);
            agentTelemetry.recordLoopBlocked();
            LOG.warn(
                "Loop guard: blocking {} (identical args, {} consecutive)",
                call.toolName(),
                session.consecutiveIdenticalCalls());
            // Use role:"tool" to maintain OpenAI format contract (every tool_call needs a
            // matching tool response)
            session.appendMessage(
                Map.of(
                    "role",
                    "tool",
                    "tool_call_id",
                    call.id(),
                    "content",
                    "Tool call blocked: \""
                        + call.toolName()
                        + "\" was called "
                        + session.consecutiveIdenticalCalls()
                        + " times with identical arguments. Try different parameters or a"
                        + " different tool."));
            continue;
          }

          sink.accept(
              new AgentEvent.ToolExecutionStarted(call.id(), call.toolName()));
          checkpointer.checkpoint(sessionId, session, "TOOL_EXECUTING", "Executing tool: " + call.toolName());

          // Tempdoc 415 F2: emit tool_call_total once per executed tool call. POST-APPROVAL,
          // POST-LOOP-GUARD, PRE-EXECUTE emit point — handoff calls branch out earlier (around
          // line 872), rejected calls and loop-blocked calls have already `continue;`d above.
          // tool_name stays bounded to the 4 registered tools. The metric represents calls
          // that actually entered execution; tool_failure_total below uses the same denominator.
          // DO NOT move this emit upstream — that would mix in rejected/blocked calls.
          agentTelemetry.recordToolCall(call.toolName());

          // Tempdoc 560 WS5 (the one window) — a projected workflow tool streams through the
          // WorkflowToolRunner (forwarding its node-by-node progress into THIS sink as AgentProgress
          // events) instead of the synchronous executor. The safety gate + approval above already ran
          // for it like any other tool; only the execution channel differs. The vop_* branch earlier
          // is the precedent for sink-aware special tool handling.
          io.justsearch.agent.api.registry.WorkflowToolRunner wfRunner = this.workflowToolRunner;
          OperationResult toolResult;
          // Tempdoc S7 — when this run carries a docIds scope (FE scope chips), scopeToolCall
          // merges it into the search tool's own arguments; a no-op copy of `call` for any other
          // operation or an unscoped run. `call` itself (used below for loop-guard/history) stays
          // the LLM's original, unscoped arguments.
          ToolCallRequest scopedCall = toolDispatcher.scopeToolCall(op, call, session);
          if (wfRunner != null && wfRunner.handles(op.id())) {
            toolResult = wfRunner.run(op.id(), scopedCall.arguments(), sink);
          } else {
            // Tempdoc 561 P-A1: thread the agent sessionId so the dispatched call stamps it as the
            // ledger correlationId (the History join key).
            toolResult = toolDispatcher.executeOperationWithPolicy(op, scopedCall, sessionId);
          }
          // Tempdoc 415: tool_failure_total counts post-policy-retry failures of executed calls.
          if (!toolResult.success()) {
            agentTelemetry.recordToolFailure(call.toolName());
          }

          // Tempdoc 577 §2.14 Root III (#18) — stamp the authoritative text-provenance lineage onto
          // the result (the single stamp site), so the FE frames a corpus-quoted excerpt as quoted
          // and injection/citation-shaped text inside it cannot read as the agent's own claim. Only
          // successful results carry framable output; failures keep their error shape unchanged.
          if (toolResult.success()) {
            toolResult =
                toolResult.withLineage(
                    io.justsearch.agent.api.registry.OutputLineage.forOperationId(
                        op.id().value()));
          }

          session.recordExecution(call, toolResult);
          sink.accept(
              new AgentEvent.ToolExecutionCompleted(call.id(), toolResult));

          // Append tool result to conversation for next iteration (truncated to save context)
          session.appendMessage(Map.of(
              "role", "tool",
              "tool_call_id", call.id(),
              "content", AgentContextCompressor.truncate(toolResult.message())));
          compressor.compressToolMessages(session.messages());

          checkpointer.checkpoint(sessionId, session, "AFTER_TOOL_RESULT", "Tool result recorded: " + call.toolName());

          // Tempdoc 577 §2.12 Move 4 — narrate the loop's DECISION: a failed tool result feeds back
          // and the next LLM call corrects + retries. Without this the thread reads "Thinking /
          // Thinking" and the user must diff two cards to see the retry (§2.11 #6). The typed phase
          // drives the user-tier label ("Retrying with corrected input") via the Display authority.
          if (!toolResult.success()) {
            sink.accept(
                new AgentEvent.AgentProgress(
                    "retry_after_tool_failure",
                    "Retrying with corrected input",
                    iteration + 1,
                    request.maxIterations()));
          }
        }

        return IterationOutcome.cont();
  }

  /** Builds the per-iteration tool list: active-agent registered tools + handoff tools. */
  private List<Map<String, Object>> buildIterationTools(
      AgentRequest request, List<Map<String, Object>> baseTools, String activeAgentId) {
    if (request.agentProfiles().isEmpty()) {
      return baseTools;
    }
    AgentProfile active = AgentHandoff.findProfile(request.agentProfiles(), activeAgentId);
    List<String> toolFilter = (active != null && !active.toolSubset().isEmpty())
        ? active.toolSubset()
        : request.selectedToolNames();
    var merged = new ArrayList<>(agentToolEmitter.emit(operationCatalog, toolFilter));
    merged.addAll(AgentHandoff.buildHandoffTools(request.agentProfiles(), activeAgentId));
    return List.copyOf(merged);
  }

  /**
   * Builds the restricted tool list for E0a (Organizer first turn after handoff).
   *
   * <p>On E0a the Organizer must commit to its task immediately. Only {@code ingest_files} plus
   * handoff tools are offered — search and browse are not available until after the first tool
   * call. This eliminates the wrong-tool failures observed in the battery where the Organizer
   * called {@code search_index} instead of {@code ingest_files} on its first turn.
   *
   * <p>Handoff tools are included so the Organizer can return control to PRIMARY if it determines
   * ingestion is not possible for the requested paths.
   */
  private List<Map<String, Object>> buildE0aTools(AgentRequest request, AgentSession session) {
    // Per tempdoc 429 §F.21 C1: the LLM sees the wire form of OperationRef. Derive the
    // selector via toWireName so the literal stays consistent with the substrate's identity.
    var tools = new ArrayList<>(
        agentToolEmitter.emit(
            operationCatalog,
            List.of(
                OperationCatalog.toWireName(
                    new io.justsearch.agent.api.registry.OperationRef("core.ingest-files")))));
    tools.addAll(AgentHandoff.buildHandoffTools(request.agentProfiles(), session.activeAgentId()));
    return List.copyOf(tools);
  }

  /**
   * Tempdoc 565 §3.A — build the terminal {@link AgentEvent.AgentDone} with the answer's grounding
   * sources attached (the clickable local-passage citations). The per-sentence inline matches are
   * resolved by the answer↔source matcher ({@link AgentCitationResolver}) when a document service is
   * available; without one the sources stand alone (the answer still cites verifiable passages).
   */
  private AgentEvent.AgentDone groundedDone(AgentSession session, String response) {
    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();
    // Tempdoc 859 §4 — the cites and the producer that scored them arrive together, so the emitted
    // `done` declares which scale its similarities are on. No resolver ⇒ NONE, which fails the FE's
    // producer gate closed to sources-without-marks (565 §10's documented degradation).
    AgentCitationResolver.Resolved resolved =
        citationResolver == null
            ? AgentCitationResolver.Resolved.none()
            : citationResolver.resolve(response, sources);
    return new AgentEvent.AgentDone(
        response,
        session.iterationsUsed(),
        session.toolCallsExecuted(),
        session.totalTokens(),
        sources,
        resolved.cites(),
        resolved.scorer().name());
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — virtual tool-call dispatcher.
   * The LLM has called a {@code vop_*} tool name; emit the event for
   * the FE to handle, register a future, block until the FE responds
   * (or the timeout fires), and append the result to the conversation
   * so the agent loop continues.
   *
   * <p>Timeout default is 30 seconds (aligns with INLINE_CONFIRM
   * approval-gate timeout). On timeout, the conversation gets a
   * synthetic failure message and the loop continues without aborting
   * the session.
   */
  private static final long VIRTUAL_TOOL_TIMEOUT_SECONDS = 30L;

  /**
   * Tempdoc 577 §2.12 Move 2 — how long the budget gate holds an interactive run awaiting the
   * human's decision before falling back to the legacy finalize-else-error behavior. Long enough
   * to notice and click; short enough that an unattended run still completes on its own. System
   * property override ({@code justsearch.agent.budgetGateTimeoutSec}) exists for tests — 0 makes
   * the gate fall through immediately (exactly the legacy behavior).
   */
  private static long budgetGateTimeoutSeconds() {
    return Long.getLong("justsearch.agent.budgetGateTimeoutSec", 120L);
  }

  // Tempdoc 577 §2.14 Root II (#14) — the context-pressure gate fires when the projected next prompt
  // reaches this fraction of the model's context window (n_ctx). Below the hard budget wall
  // (budget = n_ctx − margin ≈ 99%), so compaction is offered PROACTIVELY before the wall.
  private static final double CONTEXT_PRESSURE_THRESHOLD = 0.8;
  // How many of the most-recent messages the SUMMARIZE decision keeps as the live working set when
  // compacting older turns (the system prompt is preserved separately as an anchor).
  private static final int CONTEXT_COMPACT_KEEP_RECENT = 6;

  /**
   * Tempdoc 577 §2.14 Root II — how long the context gate holds an interactive run awaiting the
   * human's decision before falling back to CONTINUE (proceed with the large prompt — the safe,
   * non-destructive default, so a watcherless run is never silently truncated). The
   * {@code justsearch.agent.contextGateTimeoutSec} override exists for tests (0 = fall through).
   */
  private static long contextGateTimeoutSeconds() {
    return Long.getLong("justsearch.agent.contextGateTimeoutSec", 120L);
  }

  // Tempdoc 577 §2.14 Root I (#13) — how often the zero-observer park re-checks for a re-attached
  // watcher, and how long it waits before proceeding unsupervised (a safety net; never a deadlock).
  private static final long ZERO_OBSERVER_POLL_MS = 250L;

  private static long zeroObserverParkTimeoutSeconds() {
    return Long.getLong("justsearch.agent.zeroObserverParkTimeoutSec", 120L);
  }

  private void handleVirtualToolCall(
      AgentSession session,
      String sessionId,
      Consumer<AgentEvent> sink,
      ToolCallRequest call) {
    // Register the future BEFORE emitting the event so the FE
    // doesn't race the registration.
    var future = session.registerVirtualToolGate(call.id());
    sink.accept(new AgentEvent.ToolCallVirtual(call.id(), call.toolName(), call.arguments()));
    sink.accept(new AgentEvent.ToolExecutionStarted(call.id(), call.toolName()));
    checkpointer.checkpoint(sessionId, session, "TOOL_EXECUTING", "Executing virtual tool: " + call.toolName());

    AgentSession.VirtualToolResult vtr;
    try {
      vtr = future.get(VIRTUAL_TOOL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException te) {
      vtr = AgentSession.VirtualToolResult.failure(
          "virtual tool '" + call.toolName() + "' timed out after "
              + VIRTUAL_TOOL_TIMEOUT_SECONDS + "s (FE never responded)");
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      vtr = AgentSession.VirtualToolResult.failure("interrupted");
    } catch (java.util.concurrent.ExecutionException ee) {
      vtr = AgentSession.VirtualToolResult.failure(
          "future execution failed: "
              + (ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage()));
    }

    OperationResult opResult;
    if (vtr.success()) {
      opResult = OperationResult.success(vtr.output() == null ? "" : vtr.output());
    } else {
      String detail = vtr.errorDetail() == null ? "virtual tool failed" : vtr.errorDetail();
      opResult = OperationResult.failure(detail);
    }
    session.recordExecution(call, opResult);
    sink.accept(new AgentEvent.ToolExecutionCompleted(call.id(), opResult));
    session.appendMessage(
        Map.of(
            "role",
            "tool",
            "tool_call_id",
            call.id(),
            "content",
            AgentContextCompressor.truncate(opResult.message())));
    compressor.compressToolMessages(session.messages());
    checkpointer.checkpoint(sessionId, session, "AFTER_TOOL_RESULT", "Virtual tool completed: " + call.toolName());
  }
}
