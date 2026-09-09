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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
          return cancelledIteration(session, sessionId, sink);
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

        session.recordCompression(compressor.compressToolMessages(session.messages()));

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
        // Tempdoc 878 §D.6 — project the prompt the next call will ACTUALLY send: messages AND the
        // tool schemas this iteration will pass. The chat template renders those schemas into the
        // prompt, so counting messages alone measured a different prompt, not an approximation of
        // this one. `tools` is the list built a few lines above and handed to the LLM call below.
        var projectedTokens = onlineAiService.countPromptTokens(session.messages(), tools, session.engineContext());
        if (projectedTokens.isPresent()) {
          int tokens = projectedTokens.get();
          int budgetSnapshot;
          boolean budgetExhausted;

          // Synchronized block ensures consistent budget reads
          synchronized (session) {
            budgetSnapshot = session.budgetRemaining();
            sink.accept(
                new AgentEvent.AgentBudgetUpdate(
                    "iteration_start",
                    tokens,
                    budgetSnapshot,
                    session.totalTokens(),
                    // Tempdoc 859 D live-defect (minor) — `promptTokens` stays 0 HONESTLY: this
                    // phase has no provider-reported prompt, and putting the PROJECTION in the field
                    // documented as "the latest LLM call's prompt size" would make the context meter
                    // read a number no call ever had. (878 §D.6 made the projection schema-aware; it
                    // is still a forecast of a prompt not yet sent, which is why it does not belong
                    // in a field that reports a measurement.) The WINDOW, though, is known
                    // here exactly as it is on `llm_response`, and shipping 0 for it made two frames
                    // of one run disagree about the model they were describing.
                    0,
                    session.contextWindow()));
            budgetExhausted = tokens >= budgetSnapshot;
          }

          // Tempdoc 859 §D §3.2(7) — a raise that landed MID-RUN (no gate held, so the raise
          // endpoint's resolveBudgetGate was a no-op) is announced here, at the first step boundary
          // that can see it, right after the numbers it explains. Without this the note fired only
          // for the gate-parked raise and the manual one stayed as silent as it has always been —
          // half of the narration D7 asks for. Drained, so it cannot also fire at the gate below.
          narrateBudgetRaise(session, sink, iteration, request.maxIterations());

          // Tempdoc 577 §2.14 Root II (#14) — the CONTEXT-pressure gate (the cognitive sibling of the
          // budget gate below). When the next prompt approaches the model's context window (n_ctx),
          // park the run as a HELD decision OFFERING compaction (summarize older turns) — the option
          // the budget gate lacks — before the hard budget wall. ASKS at most once per run, only
          // interactive runs with a known n_ctx; a watcherless/undecided gate falls back to CONTINUE
          // (no surprise park). Compaction emits a first-class ContextCompacted narratable event.
          int contextWindow = session.contextWindow();
          // Tempdoc 859 §D §2.7(c) — the TRIGGER quantity. `tokens` is countPromptTokens, which was
          // schema-blind and measured ~40% low (577) until 878 §D.6 threaded the iteration's tool
          // list through it; it now projects the prompt this call will actually send.
          //
          // The `max` STAYS, because it never was about schema-blindness. The projection is built
          // from the messages as they stand NOW, while the provider-REPORTED prompt is the honest
          // figure for the last call and is one tool-result stale. Taking the larger uses the
          // reported prompt without ever triggering later than the projection alone would have —
          // an independent correction, in the one direction threading tools cannot cover.
          int pressureTokens = Math.max(tokens, session.lastReportedPromptTokens());
          boolean underContextPressure =
              contextWindow > 0
                  && !session.isBackground()
                  && pressureTokens >= (int) (contextWindow * CONTEXT_PRESSURE_THRESHOLD);
          // Tempdoc 859 §D §2.7 — a later crossing COMPACTS instead of asking again. At the effort
          // multipliers a second crossing is reachable (the budget no longer dies first), and doing
          // nothing would let the prompt grow past n_ctx — voiding the structural bound the Thorough
          // rung is justified by (AgentBudgetPolicy). CONTINUE at the first gate means "don't stop",
          // not "never compact": the reader accepted ONE large prompt, not an overflowing one.
          boolean compactNow = false;
          boolean reappliedWithoutAsking = false;
          // Tempdoc 859 D live-defect D4 — is the next prompt already unservable? The provider
          // rejects a prompt that does not fit its window outright ("request (4172 tokens) exceeds
          // the available context size"), and it needs room for the completion on top, so "servable"
          // is not "<= n_ctx". Read by the GATE arm only: the re-apply arm below already compacts
          // unconditionally, so it has no decision left for this condition to inform. Computed up
          // here rather than inside that arm because it reads `pressureTokens`, which is the trigger
          // quantity settled above — keeping the two on the same footing.
          boolean nextPromptUnservable =
              contextWindow > 0
                  && pressureTokens >= (int) (contextWindow * CONTEXT_UNSERVABLE_THRESHOLD);
          boolean compactedToFit = false;
          if (underContextPressure && !session.contextGateFired()) {
            var contextGateFuture = session.createContextGate();
            sink.accept(
                new AgentEvent.AgentProgress(
                    "context_gate_held",
                    "Context filling up",
                    iteration + 1,
                    request.maxIterations()));
            // Tempdoc 859 D live-defect D3 — the gate SHOWS the quantity it TRIGGERED on. Shipping
            // the raw projection put a number on screen that did not justify the park: the live gate
            // rendered 2463/4096 (60%) while the reported prompt was 3361 (82%), i.e. a reader was
            // asked to act on a figure well below the 80% threshold the run had actually crossed.
            sink.accept(new AgentEvent.ContextGatePending(pressureTokens, contextWindow));
            checkpointer.checkpoint(
                sessionId, session, "WAITING_CONTEXT", "Context pressure — awaiting decision");
            AgentSession.ContextGateDecision ctxDecision;
            boolean gateWentUnanswered = false;
            try {
              ctxDecision = contextGateFuture.get(AgentTimeouts.contextGateMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException | java.util.concurrent.ExecutionException undecided) {
              ctxDecision = AgentSession.ContextGateDecision.CONTINUE; // watcherless ⇒ proceed
              gateWentUnanswered = true;
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              ctxDecision = AgentSession.ContextGateDecision.STOP;
            } finally {
              session.clearContextGate();
            }

            if (session.isCancelled()) return cancelledIteration(session, sessionId, sink);
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
            // Tempdoc 859 §D (F6 follow-up) — the run ASKED and nobody answered, so it decided for
            // itself and carried on. That is a silent continue by the same definition the budget
            // raise is one, and it was the only gate fallback narrating NOTHING — not live, not on
            // the record. Emitted here rather than in the catch so it fires only on the path that
            // actually proceeds (an INTERRUPT falls to STOP and returns above).
            if (gateWentUnanswered) {
              sink.accept(
                  new AgentEvent.AgentProgress(
                      AgentEvent.AgentProgress.PHASE_CONTEXT_GATE_UNANSWERED,
                      "Context gate unanswered — continuing",
                      iteration + 1,
                      request.maxIterations()));
            }
            compactNow = ctxDecision == AgentSession.ContextGateDecision.SUMMARIZE;
            // CONTINUE (or post-SUMMARIZE): fall through and proceed with the current prompt.
            //
            // Tempdoc 859 D live-defect D4 — EXCEPT when "the current prompt" is one the provider
            // will refuse. The live run took CONTINUE at a crossing whose next prompt was 4172 on a
            // 4096 window; the loop proceeded, llama-server 400'd three times, and the run ERRORED —
            // so the auto-compact second crossing (§2.7) never got its chance. CONTINUE means "don't
            // stop", and continuing into a guaranteed rejection is not continuing; compacting first
            // honours the decision instead of executing it into a wall. This deliberately softens
            // "CONTINUE means no compaction" for exactly the unservable case, and says so in the
            // feed rather than acting silently.
            if (!compactNow && nextPromptUnservable) {
              compactNow = true;
              compactedToFit = true;
            }
          } else if (underContextPressure) {
            // Tempdoc 859 §D §2.7 — the gate already asked once this run. RE-APPLY the remedy
            // rather than either re-parking (the thing 577 was right to avoid) or silently doing
            // nothing (which is what would let the prompt overflow).
            compactNow = true;
            reappliedWithoutAsking = true;
          }

          if (compactNow) {
            int dropped = session.compactOlderTurns(CONTEXT_COMPACT_KEEP_RECENT);
            if (dropped > 0) {
              // Bounded autonomy: the run says WHEN it acted on a decision nobody re-confirmed —
              // and only when it actually acted. A note about a compaction that dropped nothing
              // would be the run narrating an event that did not happen.
              if (reappliedWithoutAsking) {
                sink.accept(
                    new AgentEvent.AgentProgress(
                        AgentEvent.AgentProgress.PHASE_CONTEXT_GATE_REAPPLIED,
                        "Context filling up again — compacting without asking again",
                        iteration + 1,
                        request.maxIterations()));
              }
              // Tempdoc 859 D live-defect D4 — the reader chose CONTINUE and the run compacted
              // anyway; that is a departure from what they clicked, so it is NARRATED, in the same
              // note mechanism the re-apply uses. Silence here would be the run overruling a
              // decision without saying so.
              if (compactedToFit) {
                sink.accept(
                    new AgentEvent.AgentProgress(
                        AgentEvent.AgentProgress.PHASE_CONTEXT_COMPACTED_TO_FIT,
                        "Compacted to fit before continuing — the next prompt did not fit the"
                            + " model's memory",
                        iteration + 1,
                        request.maxIterations()));
              }
              sink.accept(new AgentEvent.ContextCompacted(dropped));
              sink.accept(
                  new AgentEvent.AgentProgress(
                      AgentEvent.AgentProgress.PHASE_CONTEXT_COMPACTED,
                      // Tempdoc 859 §D (F6 follow-up) — the COUNT, by the same standard the raise
                      // note is held to: "history was shortened" without saying by how much is not
                      // an accountability record. The journaled `ContextCompacted` event has carried
                      // `droppedMessages` all along; the note that outlives the run now says it too.
                      String.format(
                          java.util.Locale.ROOT,
                          "Compacted %d earlier turn%s to stay within the model's memory",
                          dropped,
                          dropped == 1 ? "" : "s"),
                      iteration + 1,
                      request.maxIterations()));
              // Proceed THIS iteration with the compacted (smaller) prompt; recompute the budget
              // check below against the now-shorter message history so it reflects reality.
              // Same tool list as the projection above (878 §D.6): a recompute that measured a
              // different prompt than the check it is correcting would be its own defect.
              var recomputed = onlineAiService.countPromptTokens(session.messages(), tools, session.engineContext());
              if (recomputed.isPresent()) {
                tokens = recomputed.get();
                synchronized (session) {
                  budgetExhausted = tokens >= session.budgetRemaining();
                }
              }
            }
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
                decision = budgetGateFuture.get(AgentTimeouts.budgetGateMs(), TimeUnit.MILLISECONDS);
              } catch (TimeoutException | java.util.concurrent.ExecutionException timedOut) {
                decision = AgentSession.BudgetGateDecision.FINALIZE; // undecided ⇒ legacy behavior
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                decision = AgentSession.BudgetGateDecision.STOP;
              } finally {
                session.clearBudgetGate();
              }
            }

            if (session.isCancelled()) return cancelledIteration(session, sessionId, sink);
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
              // Tempdoc 859 §D §3.2(7) — NARRATE the raise. The manual raise has always been silent:
              // the run simply carried on, with nothing in the feed saying more room had been
              // granted or how much. That silence is what would turn per-run auto-continue into
              // standing autonomy, so the note is the guard rail, not decoration. The `progress`
              // descriptor already exists (AgentRunShape) ⇒ no schema change.
              narrateBudgetRaise(session, sink, iteration, request.maxIterations());
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
                  sink.accept(
                      groundedDone(
                          session, finalizeResponse, TerminalDisposition.BUDGET_EDGE_FINALIZE));
                  // F1: state-first, durability-second.
                  session.markTerminated(TerminalDisposition.BUDGET_EDGE_FINALIZE, null, null);
                  checkpointer.checkpoint(
                      sessionId, session, "DONE", "Budget-edge finalize succeeded");
                  return IterationOutcome.terminated(true);
                }
              }

              String earlyTerminationMsg =
                  String.format(
                      java.util.Locale.ROOT,
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
            // Lane F PR 0b: agentBaseSampling, not the raw AGENT constant — this forced turn must
            // honour the run's sampling override or a pinned capture still drifts here.
            result = llmCaller.callLlmWithTools(
                session, tools, sink,
                AgentLlmCaller.agentBaseSampling(session)
                    .withToolChoice("required")
                    .withEnableThinking(false));
          } else {
            result = llmCaller.callLlmWithRetries(session, tools, sink);
          }
        } catch (io.justsearch.app.api.EngineWorkCancelledException cancelled) {
          sink.accept(session.cancellationEvent());
          session.markTerminated(TerminalDisposition.CANCELLED, null, session.cancellationTrigger());
          checkpointer.checkpoint(sessionId, session, "CANCELLED", cancelled.reasonCode());
          return IterationOutcome.terminated(false);
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
                      // Lane F PR 0b: the run's sampling override applies here too.
                      AgentLlmCaller.agentBaseSampling(session)
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
          // Empty response (text empty AND no tool calls) — emit an error instead of a silent
          // empty AgentDone.
          if (result.textContent() == null || result.textContent().isBlank()) {
            LOG.warn(
                "Empty response after retry — model produced no text and no tool calls"
                    + " (finish_reason={})",
                result.finishReason());
            errorEmitter.emitError(
                sink,
                emptyResponseMessage(result.finishReason()),
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
          sink.accept(
              groundedDone(session, result.textContent(), TerminalDisposition.COMPLETED));
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
          return cancelledIteration(session, sessionId, sink);
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

            // Tempdoc 878 §D.4/§D.5 — the handoff confirmation is the THIRD producer of this event,
            // and it gets the same two stamps as the other two. The measurement, because the FE must
            // never have to distinguish "not truncated" from "not measured" by guessing which seam
            // produced the event (a handoff line is far under the Layer-2 cap, so this always reports
            // "all of it" — that is the point, not a triviality). And the lineage, because the FE's
            // default is fail-open: unstamped renders exactly as a runtime value, so "classified
            // runtime" and "never classified" are indistinguishable unless every producer stamps.
            String handoffText = "Handoff to " + toId + " confirmed.";
            String handoffContent = compressor.truncate(handoffText);
            sink.accept(new AgentEvent.ToolExecutionCompleted(
                call.id(),
                OperationResult.success(handoffText)
                    .withLineage(io.justsearch.agent.api.registry.OutputLineage.RUNTIME),
                charsToModel(handoffText)));
            session.appendMessage(Map.of(
                "role", "tool",
                "tool_call_id", call.id(),
                "content", handoffContent));

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

          // Tempdoc 875 Move 3 — offering IS authorization. resolveByWireName above iterates the
          // RAW catalog, so on its own it will happily hand back an operation the emitter withheld
          // (OPERATOR audience, availability expression false, or not in the run's selection). The
          // offered set is therefore consulted here, and a tool outside it is denied rather than
          // dispatched.
          //
          // The authority set is the UNION of the run-level offered set and what the model was
          // actually offered THIS iteration. Both sides are emitter output, so both have already
          // passed audience + availability — the union can only differ on selection, and so can
          // never widen the boundary this move exists to close.
          //
          // WHY a union and not the run-level set alone (review finding S1): the per-iteration
          // lists are prompt STEERING (E0a's first-turn narrowing, a profile's toolSubset), and
          // they are built by REPLACING the run selection rather than intersecting with it —
          // buildE0aTools selects `core_ingest_files` outright. A run whose selection excludes that
          // tool would then have it offered and its use refused: the "strand a run on a steering
          // list" failure this move set out to avoid, arrived at from the other side. Offering a
          // tool and then refusing it is never right, so the fix is to honour what was offered
          // rather than to make steering authoritative.
          //
          // WHY recomputed here rather than derived from `baseTools` (the run-start snapshot): a
          // tool whose availability went false mid-run must be denied, not dispatched on the
          // strength of having been offered at minute zero.
          if (!isAuthorizedThisIteration(op, request, tools)) {
            String notOffered =
                "Tool \"" + call.toolName() + "\" is not available in this session.";
            LOG.warn(
                "AgentLoop: refusing un-offered tool '{}' (resolved to {}) — not in the offered set",
                call.toolName(),
                op.id().value());
            sink.accept(new AgentEvent.ToolCallRejected(call.id(), notOffered));
            // role:"tool" keeps the OpenAI format contract (every tool_call needs a matching tool
            // response) — same idiom as the loop guard below.
            session.appendMessage(
                Map.of(
                    "role",
                    "tool",
                    "tool_call_id",
                    call.id(),
                    "content",
                    notOffered + " Use one of the tools you were offered."));
            checkpointer.checkpoint(
                sessionId, session, "READY_FOR_LLM", "Tool not offered: " + call.toolName());
            continue;
          }

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
            toolResult = wfRunner.run(op.id(), scopedCall.arguments(), sink, session.engineContext());
          } else {
            // Tempdoc 561 P-A1: thread the agent sessionId so the dispatched call stamps it as the
            // ledger correlationId (the History join key).
            toolResult = toolDispatcher.executeOperationWithPolicy(op, scopedCall, sessionId, session.engineContext());
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

          // Tempdoc 865 §7.1 — THE MINT, at the same dispatch seam the lineage stamp above uses.
          // `recordExecution` runs the one grounding authority (`AgentSession
          // .contributeGroundingSources`) over this call's results and returns what it NEWLY
          // established, deduped against everything the run established before it. Stamping that
          // delta onto the result makes it durable on both planes before any terminal runs, which is
          // why a cancelled / errored / iteration-exhausted run no longer loses its evidence.
          //
          // Emit only on change: a call that established nothing adds NO key, so an absent key means
          // "established nothing" rather than "reported an empty set" — the same discipline
          // `toolCompletedPayload` already applies to structuredData as a whole.
          List<AgentEvent.AgentSource> grounding = session.recordExecution(call, toolResult);
          if (!grounding.isEmpty()) {
            toolResult = toolResult.withGrounding(grounding);
          }

          // Tempdoc 878 §D.4 — cut FIRST, so the event can say how much reached the model. This used
          // to happen after the emit, which is why the wire carried the untruncated output beside no
          // statement that the prompt got less.
          String modelContent = compressor.truncate(toolResult.message());
          sink.accept(
              new AgentEvent.ToolExecutionCompleted(
                  call.id(), toolResult, charsToModel(toolResult.message())));

          // Append tool result to conversation for next iteration (truncated to save context)
          session.appendMessage(Map.of(
              "role", "tool",
              "tool_call_id", call.id(),
              "content", modelContent));
          // Tempdoc 865 §7.5 — the compressor reports what it left standing, and the session holds
          // the LATEST report. Every site that compresses must record, because a stale receipt would
          // describe a prompt the answer was not written from — which is the one way this producer
          // could state a confident falsehood rather than staying silent.
          session.recordCompression(compressor.compressToolMessages(session.messages()));

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

  /**
   * Tempdoc 875 Move 3 — may the model dispatch {@code op} on this iteration? True iff the emitter
   * would offer it at run level, OR it appears in the list the model was actually handed this turn.
   * Both sides are emitter output, so audience- and availability-withheld operations are in neither
   * and stay denied; the union only forgives a steering list that named a tool the run's own
   * selection did not.
   */
  private boolean isAuthorizedThisIteration(
      Operation op, AgentRequest request, List<Map<String, Object>> offeredThisIteration) {
    String wire = OperationCatalog.toWireName(op.id());
    if (agentToolEmitter
        .offeredWireNames(operationCatalog, request.selectedToolNames())
        .contains(wire)) {
      return true;
    }
    return wireNamesOf(offeredThisIteration).contains(wire);
  }

  /**
   * Tempdoc 881 §C.3 — what to tell the reader when a turn produced nothing, from the runtime's own
   * {@code finish_reason} instead of a hypothesis.
   *
   * <p>The text this replaced said "possible reasoning token exhaustion … try increasing the token
   * budget" on every empty turn, whatever the cause. Measured (881 §A.2), the cause on the standard
   * profile was {@code finish_reason=stop} after 35–55 of 1024 completion tokens: nothing was
   * exhausted, and the remedy it named could not have worked. A wrong cause is worse than an unknown
   * one because it is actionable — 868 §D.3 acted on it, and this tempdoc's own brief inherited it.
   * So each branch says only what the finish reason licenses, and the {@code null} branch says the
   * runtime reported nothing rather than filling the gap.
   */
  static String emptyResponseMessage(String finishReason) {
    if ("length".equals(finishReason)) {
      return "The model hit its completion-token limit before producing an answer or a tool call"
          + " (finish_reason=length). Simplifying the query, or raising"
          + " justsearch.agent.max_completion_tokens, would give it room to finish.";
    }
    if (finishReason == null || finishReason.isBlank()) {
      return "The model ended its turn without producing an answer or a tool call, and the runtime"
          + " reported no finish reason. Try rephrasing the question.";
    }
    return "The model ended its turn without producing an answer or a tool call (finish_reason="
        + finishReason
        + "). That is how the model ended, not a token limit — rephrasing the question is more"
        + " likely to help than raising the token budget.";
  }

  /** The OpenAI function names in an emitted tool list; malformed entries are skipped. */
  private static Set<String> wireNamesOf(List<Map<String, Object>> tools) {
    if (tools == null || tools.isEmpty()) {
      return Set.of();
    }
    Set<String> names = new LinkedHashSet<>(tools.size());
    for (Map<String, Object> tool : tools) {
      if (tool != null && tool.get("function") instanceof Map<?, ?> function) {
        Object name = function.get("name");
        if (name != null && !name.toString().isEmpty()) {
          names.add(name.toString());
        }
      }
    }
    return names;
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
  /**
   * Tempdoc 859 §D §3.2(7) — announce any un-narrated budget grant, naming the amount.
   *
   * <p>The raise has always been silent: the run simply carried on, with nothing in the feed saying
   * more room had been granted or how much. That silence is what would turn per-run auto-continue
   * into standing autonomy, so the note is the guard rail rather than decoration. The {@code
   * progress} descriptor already exists (AgentRunShape) ⇒ no schema change.
   *
   * <p>Called from both sites that can observe a grant; the session's counter is DRAINED, so each
   * grant produces exactly one note whichever site gets there first.
   */
  private static void narrateBudgetRaise(
      AgentSession session, Consumer<AgentEvent> sink, int iteration, int maxIterations) {
    int raised = session.drainPendingRaiseNarration();
    if (raised <= 0) {
      return;
    }
    sink.accept(
        new AgentEvent.AgentProgress(
            AgentEvent.AgentProgress.PHASE_BUDGET_RAISED,
            // Locale.ROOT — the grouping separator is part of a WIRE string the FE renders verbatim,
            // so a German JVM must not turn "+7,777" into "+7.777" (which reads as 7.777 tokens).
            String.format(java.util.Locale.ROOT, "+%,d tokens — continuing", raised),
            iteration + 1,
            maxIterations));
  }

  /**
   * Tempdoc 878 §D.4 — how many characters OF THE TOOL'S OUTPUT were placed in the prompt.
   *
   * <p>ONE helper for all three emit sites (main dispatch, handoff confirmation, virtual tool),
   * because a measurement present at some seams and absent at others is worse than none: the FE
   * could not tell "this output was not truncated" from "whoever emitted this did not measure", and
   * would have to guess which — on a field whose entire purpose is to stop guessing.
   *
   * <p><b>Measured from the ORIGINAL, not from the truncated string, and that is the whole
   * correctness of it.</b> {@code AgentContextCompressor.truncate} does not return a prefix — it
   * returns the prefix PLUS a {@code [... truncated, N chars omitted]} marker, ~35 characters of
   * framing that are not output. Measuring the returned string therefore reported MORE characters
   * than the tool produced for any output just over the cap, which inverted the derived {@code
   * truncatedForModel} into "the model got all of it" on an output it demonstrably did not, and put
   * a number on the tool card larger than the output it was a fraction of. The marker is in the
   * prompt; it is not the tool's answer, and this field counts the answer.
   */
  private int charsToModel(String output) {
    return output == null ? 0 : Math.min(output.length(), compressor.toolResultCapChars());
  }

  private AgentEvent.AgentDone groundedDone(
      AgentSession session, String response, TerminalDisposition disposition) {
    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();
    // Tempdoc 859 §4 — the cites and the producer that scored them arrive together, so the emitted
    // `done` declares which scale its similarities are on. No resolver ⇒ NONE, which fails the FE's
    // producer gate closed to sources-without-marks (565 §10's documented degradation).
    AgentCitationResolver.Resolved resolved =
        citationResolver == null
            ? AgentCitationResolver.Resolved.none()
            : citationResolver.resolve(response, sources, session.engineContext());
    return new AgentEvent.AgentDone(
        response,
        session.iterationsUsed(),
        session.toolCallsExecuted(),
        session.totalTokens(),
        sources,
        resolved.cites(),
        resolved.scorer().name(),
        // Tempdoc 859 §D §2.6 — the disposition rides the SAME emission the answer does, and is
        // decided by the CALL SITE (which terminal this is), never read back from the model's text.
        // markTerminated still records it independently; these two agree because both are told, not
        // because either inspects the other.
        disposition.name());
  }

  /**
   * Tempdoc 878 §D.1 — the ITERATION-CEILING terminal: one no-tools synthesis attempt before the
   * run stops, then {@code MAX_ITERATIONS} either way.
   *
   * <p><b>The defect this closes.</b> The budget wall has always given the model one last call to
   * write down what it gathered ({@link AgentLlmCaller#attemptBudgetEdgeFinalize}, above). The step
   * ceiling did not: it emitted an empty answer and returned, throwing away everything the run had
   * already paid for. Both are INVOLUNTARY terminals — the run walked into them — and an
   * involuntary terminal owes the run a synthesis attempt. (A voluntary one does not: a cancelled
   * run is deliberately answerless, per the neutral-cancel decision of 2026-08-26, and is not
   * swept up by this.)
   *
   * <p><b>Why it lives on the runner rather than in the driver loop that detects the ceiling.</b>
   * {@code AgentGroundingSeamAuditTest} permits a grounding-carrying {@code AgentDone} to be
   * constructed in exactly two places, one of which is {@link #groundedDone}. A ceiling terminal
   * that carries the run's evidence therefore has to be emitted from here. {@code AgentLoopService}
   * keeps the state transition ({@code markTerminated} + checkpoint) it owns for every other
   * terminal and calls this for the event — which is also what retires {@code
   * AgentDone.ofDisposition}, the ungrounded factory 865 §7.1 kept only because the ceiling had no
   * route through the seam.
   *
   * <p><b>FAIL-OPEN by construction.</b> {@code attemptStepCeilingFinalize} returns {@code null} on
   * any failure and the disposition is decided here, never read back from the model's text — so the
   * worst case is byte-for-byte the behaviour this replaces: an empty answer stamped {@code
   * MAX_ITERATIONS}. What is NOT claimed: that the answer will be non-empty. 859 §7 watched a
   * compact model answer a finalize instruction with a confident, content-free non-answer. The
   * guarantee is that synthesis is ATTEMPTED and the truncation is DISCLOSED, not that the model
   * takes the offer.
   */
  AgentEvent.AgentDone finalizeAtIterationCeiling(
      AgentRequest request, AgentSession session, Consumer<AgentEvent> sink) {
    String answer = "";
    if (session.hasSuccessfulToolResult()) {
      LOG.info("Iteration ceiling reached — attempting finalize with available tool results");
      // The same narration the budget wall uses, so the user sees the run synthesizing rather than
      // a bare "Thinking" followed by silence.
      sink.accept(
          new AgentEvent.AgentProgress(
              "finalizing", "Wrapping up", session.iterationsUsed(), request.maxIterations()));
      String finalized = llmCaller.attemptStepCeilingFinalize(session, sink);
      if (finalized != null && !finalized.isBlank()) {
        answer = finalized;
      }
    }
    return groundedDone(session, answer, TerminalDisposition.MAX_ITERATIONS);
  }

  // Tempdoc 577 §2.14 Root II (#14) — the context-pressure gate fires when the projected next prompt
  // reaches this fraction of the model's context window (n_ctx). Below the hard budget wall
  // (budget = n_ctx − margin ≈ 99%), so compaction is offered PROACTIVELY before the wall.
  private static final double CONTEXT_PRESSURE_THRESHOLD = 0.8;

  /**
   * Tempdoc 859 D live-defect D4 — the fraction of {@code n_ctx} at which the next prompt is treated
   * as UNSERVABLE: the provider rejects a prompt that does not fit its window, and the call also
   * needs room for its completion, so the line sits just below the window rather than at it. It is a
   * FRACTION, not a fixed reserve, so it stays meaningful at every window size (a fixed 256 would go
   * negative on a small test/profile window and make every crossing "unservable").
   */
  private static final double CONTEXT_UNSERVABLE_THRESHOLD = 0.95;
  // How many of the most-recent messages the SUMMARIZE decision keeps as the live working set when
  // compacting older turns (the system prompt is preserved separately as an anchor).
  private static final int CONTEXT_COMPACT_KEEP_RECENT = 6;

  // Tempdoc 577 §2.14 Root I (#13) — how often the zero-observer park re-checks for a re-attached
  // watcher, and how long it waits before proceeding unsupervised (a safety net; never a deadlock).
  private static final long ZERO_OBSERVER_POLL_MS = 250L;

  private static long zeroObserverParkTimeoutSeconds() {
    return Long.getLong("justsearch.agent.zeroObserverParkTimeoutSec", 120L);
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — virtual tool-call dispatcher. The LLM has called a {@code
   * vop_*} tool name; emit the event for the FE to handle, register a future, block until the FE
   * responds (or {@link AgentTimeouts#virtualToolMs()} elapses), and append the result to the
   * conversation so the agent loop continues. On timeout, the conversation gets a synthetic
   * failure message and the loop continues without aborting the session.
   */
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
      vtr = future.get(AgentTimeouts.virtualToolMs(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException te) {
      vtr = AgentSession.VirtualToolResult.failure(
          "virtual tool '" + call.toolName() + "' timed out after "
              + TimeUnit.MILLISECONDS.toSeconds(AgentTimeouts.virtualToolMs())
              + "s (FE never responded)");
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
    // Tempdoc 878 §D.5 — the SAME text-provenance stamp the main dispatch seam applies, which this
    // seam had been skipping since 577 §2.14 Root III called it "the single authoritative stamp".
    // Unstamped, the FE's fail-open default framed a virtual tool's output as a runtime value by
    // ACCIDENT rather than by classification, and the two are indistinguishable downstream.
    //
    // Recorded limit: a `vop_*` id is not in OutputLineage.CORPUS_READERS, so this classifies
    // RUNTIME — correct for a shell/plugin command's computed output, and a real limit for a plugin
    // that returns document text. An operation-id classifier cannot see inside an FE-supplied tool;
    // naming that beats guessing at it.
    if (opResult.success()) {
      opResult =
          opResult.withLineage(
              io.justsearch.agent.api.registry.OutputLineage.forOperationId(call.toolName()));
    }
    // Tempdoc 865 §7.1 — the SAME mint-and-stamp pair as the main dispatch seam. A recorded
    // execution whose delta never reaches the wire is a silent terminal-equivalence trap: the
    // accumulator would count it into the terminal list while the deltas skipped it, so the
    // concatenation would no longer equal the terminal and every position after the gap would shift.
    // The grounding-seam audit cannot see that shape — it forbids stamping outside the seam, not
    // recording WITHOUT stamping — so the invariant is upheld here by construction and pinned by
    // `virtualToolRun_keepsTerminalEquivalence`.
    List<AgentEvent.AgentSource> grounding = session.recordExecution(call, opResult);
    if (!grounding.isEmpty()) {
      opResult = opResult.withGrounding(grounding);
    }
    // Tempdoc 878 §D.4 — cut first, then report what reached the model (see the main seam).
    String modelContent = compressor.truncate(opResult.message());
    sink.accept(
        new AgentEvent.ToolExecutionCompleted(
            call.id(), opResult, charsToModel(opResult.message())));
    session.appendMessage(
        Map.of("role", "tool", "tool_call_id", call.id(), "content", modelContent));
    session.recordCompression(compressor.compressToolMessages(session.messages()));
    checkpointer.checkpoint(sessionId, session, "AFTER_TOOL_RESULT", "Virtual tool completed: " + call.toolName());
  }
  private IterationOutcome cancelledIteration(AgentSession session, String sessionId, Consumer<AgentEvent> sink) {
    agentTelemetry.recordError(AgentErrorCode.CANCELLED, AgentErrorClass.CANCELLED);
    sink.accept(session.cancellationEvent());
    session.markTerminated(TerminalDisposition.CANCELLED, null, session.cancellationTrigger());
    checkpointer.checkpoint(sessionId, session, "CANCELLED", session.cancellationReason());
    return IterationOutcome.terminated(false);
  }

}
