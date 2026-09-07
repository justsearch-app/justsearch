/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM-call cluster for the agent loop (tempdoc 240 W4 — extracted from
 * {@code AgentLoopService}). Owns the round-trip to {@link OnlineAiService}:
 * streaming a chat-with-tools request, accumulating text/reasoning/tool-call
 * deltas, applying the empty-response and transient-failure retry policy,
 * recovering Hermes text-format tool calls, stripping leaked {@code <think>}
 * tags, and building the assistant tool-call message. Coupled only to
 * {@code onlineAiService}, {@code agentTelemetry}, and {@code compressor}; the
 * shared {@code TRACER_SCOPE} / {@code TOOL_CALL_GRAMMAR} constants stay on
 * {@link AgentLoopService}.
 */
final class AgentLlmCaller {

  private static final Logger LOG = LoggerFactory.getLogger(AgentLlmCaller.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The per-call completion-token cap, resolved PER CALL from the live context window and config
   * (tempdoc 883 decision 3).
   *
   * <p>It used to be a {@code static final} resolved at class-init, so a window change at runtime —
   * routine now that the launch ladder derives the window per activation — never reached it. See
   * {@link AgentContextBudgets} for what the number is and why it is not simply a fraction of the
   * window.
   */
  private int completionTokens() {
    return AgentContextBudgets.forCall(onlineAiService).completionReserve();
  }

  private final OnlineAiService onlineAiService;
  private final AgentTelemetry agentTelemetry;
  private final AgentContextCompressor compressor;

  AgentLlmCaller(
      OnlineAiService onlineAiService,
      AgentTelemetry agentTelemetry,
      AgentContextCompressor compressor) {
    this.onlineAiService = onlineAiService;
    this.agentTelemetry = agentTelemetry;
    this.compressor = compressor;
  }

  /** Names the limit that fired, for the budget wall. */
  private static final String BUDGET_LIMIT_SENTENCE =
      "This run has reached its token budget and is stopping now, before you finished working.";

  /** Names the limit that fired, for the iteration ceiling (tempdoc 878 §D.1). */
  private static final String STEP_LIMIT_SENTENCE =
      "This run has reached its step limit and is stopping now, before you finished working.";

  /**
   * Everything after the limit sentence — identical for both terminals, because the OBLIGATION is
   * identical: only the reason the run stopped differs. Shared by construction so a future edit to
   * the disclosure rules cannot land on one terminal and miss the other.
   */
  private static final String FINALIZE_OBLIGATION =
      " Write your answer from what you already gathered, and follow all four rules:\n"
          + "1. Say plainly, in your first sentence, that the run was cut short before it finished"
          + " and that this answer is partial.\n"
          + "2. Give whatever partial findings you do have. A partial answer is the goal here — do"
          + " not decline, and do not withhold what you found because it is incomplete.\n"
          + "3. Name what you had gathered and what you had not gotten to yet.\n"
          + "4. Do not say you lack access to anything already present in this conversation. Tool"
          + " results above are yours to use.\n"
          + "Do not call any more tools.";

  /**
   * Tempdoc 859 §D §2.6 layer 1 — the budget-edge finalize instruction.
   *
   * <p>The instruction this replaced asked for "your best answer based on the information gathered
   * so far" and never gave the model permission — let alone an obligation — to be PARTIAL. A compact
   * model resolves that by declining: 859 §7 observed a confidently formatted, content-free
   * non-answer that also claimed no access to files already sitting in its own transcript.
   *
   * <p>So this names the situation, requires the disclosure, requires the partition into gathered vs
   * not-gathered, and forbids the specific false claim that was observed. It is BEST-EFFORT by
   * construction — a model can ignore any of it. The fail-closed guarantee is the disposition on the
   * wire ({@code AgentEvent.AgentDone#disposition}), which is written independently of this text.
   */
  static final String BUDGET_EDGE_FINALIZE_INSTRUCTION =
      BUDGET_LIMIT_SENTENCE + FINALIZE_OBLIGATION;

  /**
   * Tempdoc 878 §D.1 — the same obligation, for the OTHER involuntary terminal: the run ran out of
   * STEPS, not tokens.
   *
   * <p>The limit sentence is the only difference, and it is not cosmetic. 859 D5 recorded the cost
   * of one shared string across these two terminals on the FE: a run stopped by the step ceiling
   * with most of its budget unspent told the reader that TOKENS had stopped it, which is a specific
   * false statement made where the reader is deciding what to do next. The same argument applies to
   * the text handed to the MODEL — an instruction that opens "you have reached your token budget"
   * during a step-ceiling finalize invites the model to repeat that falsehood in its answer.
   */
  static final String STEP_CEILING_FINALIZE_INSTRUCTION =
      STEP_LIMIT_SENTENCE + FINALIZE_OBLIGATION;

  /**
   * Budget-edge finalize: the graceful terminal at the TOKEN wall. Delegates to {@link
   * #attemptFinalize} and owns the {@code agent.budget_edge_finalize.total} counter, which is named
   * for this terminal and therefore counts only this one.
   */
  String attemptBudgetEdgeFinalize(AgentSession session, Consumer<AgentEvent> sink) {
    String text = attemptFinalize(session, sink, BUDGET_EDGE_FINALIZE_INSTRUCTION);
    agentTelemetry.recordBudgetEdgeFinalize(text != null);
    return text;
  }

  /**
   * Tempdoc 878 §D.1 — the same graceful terminal at the STEP ceiling.
   *
   * <p>It deliberately does NOT increment {@code agent.budget_edge_finalize.total}: that counter is
   * named for the budget wall, and folding a second terminal into it would silently change what a
   * recorded number means — the class of defect this tempdoc exists to close, committed while
   * closing it. The ceiling's own counter is a metric-schema change and is logged as follow-up.
   */
  String attemptStepCeilingFinalize(AgentSession session, Consumer<AgentEvent> sink) {
    return attemptFinalize(session, sink, STEP_CEILING_FINALIZE_INSTRUCTION);
  }

  /**
   * Tempdoc 878 §D.7 — the run's factual inventory of documents it OPENED, appended to whichever
   * finalize instruction is being sent.
   *
   * <p>Rule 3 of that instruction requires the model to "name what you had gathered and what you had
   * not gotten to yet". A compact model at a truncating terminal, under maximal compression
   * pressure, is being asked to recall that from a transcript whose evidence has just been stripped
   * — which is how 859 §7's run came to claim no access to files sitting in its own context. This
   * hands it the list rather than asking it to remember one.
   *
   * <p>EMPTY appends nothing. A run that opened no documents has nothing to inventory, and a line
   * saying so would be noise on every search-only run — which is most of them.
   */
  private static String openedInventory(AgentSession session) {
    List<String> opened = session.openedDocumentPaths();
    if (opened.isEmpty()) {
      return "";
    }
    return "\nDocuments you opened in this run, in order: "
        + String.join(", ", opened)
        + ". You opened no others.";
  }

  /**
   * Compress history, ask the model for its best answer with no tools under {@code instruction},
   * and return that text (or {@code null} if it returns nothing or the call fails).
   *
   * <p>Returning {@code null} on every failure is what lets both callers be FAIL-OPEN: a terminal
   * that cannot synthesise lands on exactly the answerless behaviour it replaced, never worse.
   */
  private String attemptFinalize(
      AgentSession session, Consumer<AgentEvent> sink, String instruction) {
    try {
      // Compress tool messages to maximize context space for the finalize call.
      //
      // Tempdoc 865 §7.5 — and RECORD the receipt, like every other compression site. This one
      // matters most: it is the last pass before the terminal's `groundedDone`, so it builds the
      // exact prompt that terminal's answer is written from, under maximal compression pressure.
      // Compressing bare here left the terminal resolving inclusion against a one-pass-stale
      // picture — silently withholding the badge precisely where the most text had been dropped.
      session.recordCompression(compressor.compressToolMessages(session.messages()));
      session.appendMessage(Map.of("role", "user", "content", instruction + openedInventory(session)));
      // Tempdoc 878 review B2 — SAMPLING IS PASSED EXPLICITLY, never resolved from the session.
      //
      // The 3-argument overload resolves it via `resolveAgentSampling`, which returns
      // `tool_choice=required` PLUS `TOOL_CALL_GRAMMAR` whenever `shouldForceToolCall` holds — and
      // `OnlineModeOps` forwards a grammar exactly when the tools list is empty, which it always is
      // here. A finalize sampled that way is constrained to emit `<tool_call>{…}</tool_call>` with no
      // tools to call, and `recoverInlineToolCalls` cannot strip it (its name set is empty), so the
      // raw blob streams out AS THE ANSWER of a truncated run.
      //
      // The budget wall never hit this because its OUTER gate already excludes the forced-tool state
      // for its own reason (E0a must not be stranded). The iteration ceiling has no such gate and
      // must not grow one: at a hard ceiling the run is over either way, and a synthesis attempt is
      // still the right thing. Fixing it HERE makes the invariant structural — a no-tools call is
      // never tool-forced — instead of a guard every future call site has to remember.
      //
      // Lane F PR 0b: `agentBaseSampling`, not the bare constant. It applies only the run's
      // temperature / top_p / seed override and NEVER tool_choice or grammar, so B2's invariant is
      // untouched while the finalize — which produces the run's visible answer — stops being the
      // one turn a pinned capture leaves unpinned.
      LlmCallResult result =
          callLlmWithTools(session, List.of(), sink, agentBaseSampling(session));
      String text = result.textContent();
      return text != null && !text.isBlank() ? text : null;
    } catch (Exception e) {
      LOG.warn("Finalize LLM call failed", e);
      return null;
    }
  }

  LlmCallResult callLlmWithRetries(
      AgentSession session,
      List<Map<String, Object>> tools,
      Consumer<AgentEvent> eventConsumer) {
    AgentRetryPolicy.RetryDecision llmDecision =
        AgentRetryPolicy.forCode(AgentErrorCode.LLM_TRANSIENT);
    AgentRetryPolicy.RetryDecision emptyDecision =
        AgentRetryPolicy.forCode(AgentErrorCode.EMPTY_RESPONSE);
    int llmAttempt = 0;
    int emptyAttempt = 0;
    while (true) {
      try {
        // Tempdoc 881 §C.2 — the retry has to CHANGE something.
        //
        // An empty result here is not transient server state; it is a property of this prompt
        // shape (measured: the same turn returns empty 3/3 on replay, and 868 saw 2/2 end-to-end).
        // Re-issuing the identical request, which is what this loop used to do, is a 250 ms pause
        // before the same answer. Suppressing the thinking prompt is the one change that moves it:
        // 40/40 sampled turns across both chat profiles returned a structured tool call under
        // `enable_thinking:false`, at lower latency. It is applied ONLY on the retry — the first
        // attempt keeps the model's reasoning, which is why the standard profile exists.
        SamplingParams sampling = resolveAgentSampling(session);
        if (emptyAttempt > 0) {
          sampling = sampling.withEnableThinking(false);
        }
        LlmCallResult result = callLlmWithTools(session, tools, eventConsumer, sampling);
        boolean emptyResult =
            result.toolCalls().isEmpty()
                && (result.textContent() == null || result.textContent().isBlank());

        if (!emptyResult) {
          return result;
        }

        if (emptyAttempt >= emptyDecision.maxRetries()) {
          if (emptyDecision.maxRetries() > 0) {
            agentTelemetry.recordRetryExhausted(AgentErrorCode.EMPTY_RESPONSE);
          }
          return result;
        }

        emptyAttempt++;
        agentTelemetry.recordRetry(AgentErrorCode.EMPTY_RESPONSE, emptyAttempt);
        LOG.warn(
            "Empty LLM response; retrying (attempt {}/{})",
            emptyAttempt,
            emptyDecision.maxRetries());
        AgentRetryPolicy.sleepRetryDelay(emptyDecision.delayMsForAttempt(emptyAttempt));
      } catch (RuntimeException e) {
        llmAttempt++;
        if (llmAttempt > llmDecision.maxRetries()) {
          agentTelemetry.recordRetryExhausted(AgentErrorCode.LLM_TRANSIENT);
          throw e;
        }
        agentTelemetry.recordRetry(AgentErrorCode.LLM_TRANSIENT, llmAttempt);
        AgentRetryPolicy.sleepRetryDelay(llmDecision.delayMsForAttempt(llmAttempt));
      }
    }
  }

  /**
   * Resolves the sampling parameters for the current LLM call, adding {@code tool_choice}
   * when the active agent should be forced to produce a tool call.
   */
  static SamplingParams resolveAgentSampling(AgentSession session) {
    SamplingParams base = agentBaseSampling(session);
    if (!AgentTurnPolicy.shouldForceToolCall(session)) {
      return base;
    }
    // Direction I: apply grammar alongside tool_choice=required for belt-and-suspenders
    // enforcement. Grammar is only forwarded to the server when the tools list is empty
    // (OnlineModeOps guard); when tools are present, tool_choice alone is used.
    // Direction D: suppress thinking-prompt on E0a turns — Organizer acts mechanically.
    return base
        .withToolChoice("required")
        .withGrammar(AgentLoopService.TOOL_CALL_GRAMMAR)
        .withEnableThinking(false);
  }

  /**
   * The agent preset with THIS run's optional sampling override applied (lane F PR 0b) — the ONE
   * place {@code SamplingParams.AGENT} becomes a run's actual sampling.
   *
   * <p>It is a shared helper rather than three copies because the two forced-tool turns in {@code
   * AgentStepRunner} (the DECIDING commit and the PRIMARY text-only handoff escalation) build their
   * sampling inline from the same constant. A version that only threaded the override through this
   * class would pin the ordinary turns and leave those two unpinned — the run would still drift,
   * and the capture would report a pinned seed while doing it.
   *
   * <p>Each component is independently optional: an override that sets only {@code seed} keeps the
   * preset's 0.7 / 0.8. A null override returns the constant itself.
   */
  static SamplingParams agentBaseSampling(AgentSession session) {
    var override = session == null ? null : session.samplingOverride();
    if (override == null) {
      return SamplingParams.AGENT;
    }
    return new SamplingParams(
        override.temperature() != null ? override.temperature() : SamplingParams.AGENT.temperature(),
        override.topP() != null ? override.topP() : SamplingParams.AGENT.topP(),
        SamplingParams.AGENT.toolChoice(),
        SamplingParams.AGENT.grammar(),
        SamplingParams.AGENT.enableThinking(),
        SamplingParams.AGENT.responseFormat(),
        override.seed());
  }

  /**
   * The ONE entry point for a tool-bearing LLM call, and sampling is always an argument.
   *
   * <p>Tempdoc 881 §C.2 retired the sampling-resolving overload that used to sit here. 878 review B2
   * had already recorded what implicit resolution costs — a finalize call silently picked up
   * {@code tool_choice=required} plus a grammar and streamed the raw tool-call blob out as the
   * answer — and the retry now needs a DIFFERENT sampling on its second attempt than on its first,
   * which an overload that derives sampling from the session cannot express. With the overload gone
   * the invariant is structural rather than remembered: no call site can leave sampling implicit.
   */
  LlmCallResult callLlmWithTools(
      AgentSession session,
      List<Map<String, Object>> tools,
      Consumer<AgentEvent> eventConsumer,
      SamplingParams sampling) {

    Span chatSpan = GlobalOpenTelemetry.getTracer(AgentLoopService.TRACER_SCOPE).spanBuilder("chat")
        .setSpanKind(SpanKind.CLIENT)
        .setAttribute("gen_ai.operation.name", "chat")
        .startSpan();
    long chatStartNanos = System.nanoTime();

    var textBuilder = new StringBuilder();
    var reasoningBuilder = new StringBuilder();
    var parser = new ToolCallParser();
    var latch = new CountDownLatch(1);
    var errorHolder = new CompletableFuture<Throwable>();
    var finishReasonHolder = new java.util.concurrent.atomic.AtomicReference<String>();

    onlineAiService.streamChatWithTools(
        session.messages(),
        tools,
        completionTokens(),
        new OnlineAiService.StreamCallbacks(
            chunk -> {
              textBuilder.append(chunk);
              eventConsumer.accept(new AgentEvent.TextChunk(chunk));
            },
            reasoning -> {
              reasoningBuilder.append(reasoning);
              eventConsumer.accept(new AgentEvent.ReasoningChunk(reasoning));
            },
            toolCallDeltaJson -> {
              try {
                JsonNode node = MAPPER.readTree(toolCallDeltaJson);
                parser.accumulateChunk(node);
              } catch (Exception e) {
                LOG.debug("Failed to parse tool call delta", e);
              }
            },
            usage -> {
              // Track token usage from LLM response
              if (usage != null) {
                session.recordUsage(usage.promptTokens(), usage.completionTokens());

                // OTel span attributes (gen_ai semantic conventions)
                if (usage.promptTokens() != null) {
                  chatSpan.setAttribute("gen_ai.usage.input_tokens", (long) usage.promptTokens());
                  agentTelemetry.recordTokenUsage(usage.promptTokens(), "input");
                }
                if (usage.completionTokens() != null) {
                  chatSpan.setAttribute(
                      "gen_ai.usage.output_tokens", (long) usage.completionTokens());
                  agentTelemetry.recordTokenUsage(usage.completionTokens(), "output");
                }

                // Emit budget event (actual usage). totalTokensConsumed is run-cumulative (577 Ext
                // III): the per-call figure cannot reconstruct the ceiling after iteration 1.
                eventConsumer.accept(
                    new AgentEvent.AgentBudgetUpdate(
                        "llm_response",
                        usage.totalTokens() != null ? usage.totalTokens() : 0,
                        session.budgetRemaining(),
                        session.totalTokens(),
                        // Tempdoc 577 §2.14 Root II (#14) — the cognitive-headroom figures: the latest
                        // call's prompt size (current context occupancy) ÷ the model's n_ctx.
                        usage.promptTokens() != null ? usage.promptTokens() : 0,
                        session.contextWindow()));

                LOG.debug(
                    "LLM usage: prompt={}, completion={}, remaining={}",
                    usage.promptTokens(),
                    usage.completionTokens(),
                    session.budgetRemaining());
              }
            },
            fr -> {
              // Tempdoc 881 §B.3 — KEEP the runtime's terminal reason. This callback used to
              // discard it, which is why the empty-response terminal had to guess at a cause.
              finishReasonHolder.set(fr);
              latch.countDown();
            },
            error -> {
              errorHolder.complete(error);
              latch.countDown();
            }),
        sampling);

    try {
      boolean completed;
      try {
        completed = latch.await(AgentTimeouts.llmCallMs(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Agent LLM call interrupted", e);
      }
      if (!completed) {
        // Derived from AgentTimeouts.llmCallMs() so the message and the actual wait cannot disagree.
        throw new RuntimeException(
            "Agent LLM call timed out after "
                + TimeUnit.MILLISECONDS.toMinutes(AgentTimeouts.llmCallMs())
                + " minutes");
      }

      if (!reasoningBuilder.isEmpty()) {
        LOG.debug(
            "LLM reasoning ({} chars): {}...",
            reasoningBuilder.length(),
            reasoningBuilder.substring(0, Math.min(200, reasoningBuilder.length())));
      }

      if (errorHolder.getNow(null) != null) {
        throw new RuntimeException("LLM call failed", errorHolder.getNow(null));
      }

      List<ToolCallRequest> toolCalls = parser.drainCompleted();

      // Recover/clean tool-call JSON the model emitted into the TEXT channel instead of the structured
      // tool_calls channel. Local models leak this with two grammars ({"name","arguments"} and
      // {"type":"function",…,"parameters"}) and arbitrary delimiters (inline / ';'-separated), so the old
      // structured-empty + newline-split check missed it and the JSON rendered as the "answer". The
      // structured channel still takes precedence: its calls are kept, an exact (name,args) text echo of
      // one is only stripped (no double execution), and only spans naming an AVAILABLE tool are recovered
      // (legitimate JSON-looking prose is left untouched).
      String rawText = textBuilder.toString();
      ToolSchemas schemas = ToolSchemas.of(tools);
      if (!rawText.isBlank()) {
        RecoveredText rt = recoverInlineToolCalls(rawText, toolCalls, schemas);
        rawText = rt.text();
        if (!rt.recovered().isEmpty()) {
          LOG.warn(
              "Recovered {} tool call(s) the model emitted as text content (not structured tool_calls)",
              rt.recovered().size());
          var merged = new ArrayList<ToolCallRequest>(toolCalls);
          merged.addAll(rt.recovered());
          toolCalls = merged;
        }
      }

      // Tempdoc 881 §C.1 — the REASONING channel, and only when the turn is otherwise a dead end.
      //
      // Measured on Qwen3.5-9B (881 §A.3): on 40% of tool-planning turns the model emits a
      // well-formed tool call INSIDE an unterminated thinking block, in the XML grammar, and
      // stops. `--reasoning-format deepseek` routes the whole block to reasoning_content, so
      // llama-server's native parser finds no call and the loop used to discard the turn and
      // report "possible reasoning token exhaustion". Of 129 completions sampled across both
      // profiles, 42 turns came back empty and ALL 42 carried a recoverable call; not one was
      // genuinely content-free.
      //
      // The gate is deliberately stricter than the text channel's. Thinking DISCUSSES calls it
      // then decides against, so acting on a hypothetical would be worse than the bug: recovery
      // requires the model's own `<tool_call>` commit wrapper, and only runs when the alternative
      // is discarding the turn entirely (no structured calls AND no text). Nothing is stripped —
      // the reasoning has already streamed to the reader, and rewriting it would make the
      // transcript disagree with what they saw.
      if (toolCalls.isEmpty() && rawText.isBlank() && !reasoningBuilder.isEmpty()) {
        List<ToolCallRequest> fromReasoning =
            recoverCommittedToolCalls(reasoningBuilder.toString(), schemas);
        if (!fromReasoning.isEmpty()) {
          LOG.warn(
              "Recovered {} tool call(s) the model emitted inside its reasoning channel"
                  + " (not structured tool_calls); finish_reason={}",
              fromReasoning.size(),
              finishReasonHolder.get());
          toolCalls = fromReasoning;
        }
      }

      // Tempdoc 881 §C.3 — when the turn is STILL empty, record what the model actually produced.
      // The next reader of this log should not have to run a measurement campaign to find out that
      // the model wrote something the loop could not use; 868 §D.3 did, and got the wrong answer.
      if (toolCalls.isEmpty() && rawText.isBlank()) {
        LOG.warn(
            "LLM turn produced no text and no tool calls (finish_reason={}, reasoning_chars={}):"
                + " reasoning tail={}",
            finishReasonHolder.get(),
            reasoningBuilder.length(),
            reasoningBuilder.substring(Math.max(0, reasoningBuilder.length() - 400)));
      }

      // Think-tag hygiene is upstream now (tempdoc 835 §5.3): OnlineModeOps' streaming parse runs a
      // stateful, frame-straddle-safe filter over content and reroutes captured thinking to the
      // reasoning channel, so the accumulator here never sees tags. The strip that used to live at
      // this point was the second authority over the same fact.
      chatSpan.setStatus(StatusCode.OK);
      return new LlmCallResult(rawText, toolCalls, finishReasonHolder.get());
    } catch (RuntimeException e) {
      chatSpan.recordException(e);
      chatSpan.setStatus(StatusCode.ERROR, "llm-call-failed");
      throw e;
    } finally {
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - chatStartNanos);
      agentTelemetry.recordLlmDuration(durationMs, "chat");
      chatSpan.end();
    }
  }

  /** A tool call the model leaked into a prose channel, with its [start,end) char span. */
  record InlineToolCall(int start, int end, String name, String arguments) {}

  /** The assistant text with leaked tool-call JSON removed, plus the calls recovered from it. */
  record RecoveredText(String text, List<ToolCallRequest> recovered) {}

  /**
   * The LLM-facing tool payload, reduced to what the recovery layer needs: which names exist, and
   * what type each declared parameter has.
   *
   * <p>Tempdoc 881 §C.1 — the XML grammar carries argument VALUES with no types
   * ({@code <parameter=list_files>True</parameter>}), so turning one back into JSON arguments is
   * only sound against the tool's own declared schema. Building this from the same {@code tools}
   * list that was sent to the model keeps the recovery honest: nothing is coerced by guesswork
   * about the name.
   */
  record ToolSchemas(Set<String> names, Map<String, Map<String, String>> parameterTypes) {

    /** Project the OpenAI-shaped tool list the loop sends: {@code function.name} + parameter types. */
    static ToolSchemas of(List<Map<String, Object>> tools) {
      var names = new LinkedHashSet<String>();
      var types = new LinkedHashMap<String, Map<String, String>>();
      for (Map<String, Object> tool : tools) {
        if (!(tool.get("function") instanceof Map<?, ?> fn) || fn.get("name") == null) {
          continue;
        }
        String name = String.valueOf(fn.get("name"));
        names.add(name);
        var perParam = new LinkedHashMap<String, String>();
        if (fn.get("parameters") instanceof Map<?, ?> params
            && params.get("properties") instanceof Map<?, ?> props) {
          for (Map.Entry<?, ?> e : props.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> spec && spec.get("type") != null) {
              perParam.put(String.valueOf(e.getKey()), String.valueOf(spec.get("type")));
            }
          }
        }
        types.put(name, perParam);
      }
      return new ToolSchemas(Set.copyOf(names), Map.copyOf(types));
    }

    boolean has(String name) {
      return names.contains(name);
    }

    /**
     * Build a JSON arguments object from untyped XML parameter values, coercing each against its
     * declared type. An undeclared key, or a value that will not parse as its declared type, keeps
     * the raw string: the tool's own validation is the authority on whether that is acceptable, and
     * dropping the argument here would turn a recoverable call into a differently-wrong one.
     *
     * <p>Exactly what is coerced, stated so nobody has to infer it (881 review §G finding 11):
     * {@code boolean} accepts {@code true/yes/1} and {@code false/no/0} case-insensitively;
     * {@code integer} and {@code number} both go through {@code BigDecimal}, so a fractional value
     * against a declared {@code integer} arrives as the number it was written as, not truncated —
     * the tool's schema validation is what rejects it. Every other declared type, {@code array} and
     * {@code object} included, arrives as the raw string; no observed leak has carried one, and
     * inventing a parse for an unobserved shape is how a recovery layer starts guessing.
     */
    String argumentsJson(String name, LinkedHashMap<String, String> rawValues) {
      Map<String, String> declared = parameterTypes.getOrDefault(name, Map.of());
      var node = MAPPER.createObjectNode();
      for (Map.Entry<String, String> e : rawValues.entrySet()) {
        String raw = e.getValue().strip();
        String type = declared.get(e.getKey());
        if ("boolean".equals(type) && BOOLEAN_TRUE.matcher(raw).matches()) {
          node.put(e.getKey(), true);
        } else if ("boolean".equals(type) && BOOLEAN_FALSE.matcher(raw).matches()) {
          node.put(e.getKey(), false);
        } else if ("integer".equals(type) || "number".equals(type)) {
          try {
            node.put(e.getKey(), new java.math.BigDecimal(raw));
          } catch (NumberFormatException nfe) {
            node.put(e.getKey(), raw);
          }
        } else {
          node.put(e.getKey(), raw);
        }
      }
      return node.toString();
    }
  }

  private static final java.util.regex.Pattern BOOLEAN_TRUE =
      java.util.regex.Pattern.compile("(?i)true|yes|1");
  private static final java.util.regex.Pattern BOOLEAN_FALSE =
      java.util.regex.Pattern.compile("(?i)false|no|0");

  /** The model's own commit wrapper around a tool call it emitted into a prose channel. */
  private static final java.util.regex.Pattern TOOL_CALL_WRAPPER =
      java.util.regex.Pattern.compile("<tool_call>(.*?)</tool_call>", java.util.regex.Pattern.DOTALL);

  /** {@code <function=core_read_document>} — the XML grammar's call head. */
  private static final java.util.regex.Pattern XML_FUNCTION =
      java.util.regex.Pattern.compile("<function\\s*=\\s*([A-Za-z0-9_.-]+)\\s*>");

  /** {@code <parameter=path>\n…\n</parameter>} — one XML argument. */
  private static final java.util.regex.Pattern XML_PARAMETER =
      java.util.regex.Pattern.compile(
          "<parameter\\s*=\\s*([A-Za-z0-9_.-]+)\\s*>(.*?)</parameter>", java.util.regex.Pattern.DOTALL);

  /**
   * Recover tool calls the model emitted as TEXT content (instead of structured {@code tool_calls}) and
   * strip them from the text. Accepts every grammar local models leak: the two JSON ones —
   * {@code {"name":..,"arguments":..}} and {@code {"type":"function",…,"parameters":..}} / nested
   * {@code {"type":"function","function":..}}, found anywhere via a balanced-brace scan (inline,
   * {@code ';'}-separated, newline) — and (tempdoc 881) the wrapper-delimited XML one,
   * {@code <tool_call><function=NAME><parameter=K>V</parameter></function></tool_call>}. Only spans
   * naming an AVAILABLE tool are acted on (JSON-looking prose is left untouched); an exact (name,args)
   * echo of an already-present structured call is stripped but NOT re-added (no double execution). Pure.
   */
  static RecoveredText recoverInlineToolCalls(
      String text, List<ToolCallRequest> structured, ToolSchemas schemas) {
    List<InlineToolCall> spans = scanToolCallSpans(text, schemas);
    if (spans.isEmpty()) {
      return new RecoveredText(text, List.of());
    }
    Set<String> seen = new LinkedHashSet<>();
    for (ToolCallRequest tc : structured) {
      seen.add(dedupKey(tc.toolName(), tc.arguments()));
    }
    var recovered = new ArrayList<ToolCallRequest>();
    var deletions = new ArrayList<InlineToolCall>();
    for (InlineToolCall span : spans) { // forward pass → first-occurrence order + correct echo dedup
      if (!schemas.has(span.name())) {
        continue; // unknown tool → could be legitimate content; leave it in the text
      }
      deletions.add(span);
      if (seen.add(dedupKey(span.name(), span.arguments()))) {
        recovered.add(
            new ToolCallRequest(
                "text-tool-" + RECOVERED_CALL_SEQ.incrementAndGet(), span.name(), span.arguments()));
      }
    }
    var sb = new StringBuilder(text);
    for (int k = deletions.size() - 1; k >= 0; k--) { // delete back-to-front so indices stay valid
      sb.delete(deletions.get(k).start(), deletions.get(k).end());
    }
    // Tidy delimiter residue left where spans were removed (";  ;", leading/trailing ';').
    String cleaned = sb.toString()
        .replaceAll("\\s*;(\\s*;)+\\s*", "; ")
        .replaceAll("^[\\s;]+", "")
        .replaceAll("[\\s;]+$", "")
        .strip();
    return new RecoveredText(cleaned, recovered);
  }

  /**
   * Tempdoc 881 §C.1 — recover the tool calls the model COMMITTED TO inside a prose channel: only
   * spans wrapped in the model's own {@code <tool_call>…</tool_call>} marker, in either the XML or a
   * JSON body, naming an available tool. Nothing is stripped and nothing is inferred from bare prose.
   *
   * <p>This is the strict sibling of {@link #recoverInlineToolCalls}. The permissive rule is right for
   * the text channel, where an unrecovered leak renders AS the answer; it is wrong for the reasoning
   * channel, which routinely weighs calls the model then decides against. The wrapper is the only
   * signal that separates "I am calling this" from "I could call this". Pure.
   */
  static List<ToolCallRequest> recoverCommittedToolCalls(String prose, ToolSchemas schemas) {
    InlineToolCall last = null;
    java.util.regex.Matcher m = TOOL_CALL_WRAPPER.matcher(prose);
    while (m.find()) {
      InlineToolCall call = parseWrappedToolCall(m.group(1), m.start(), m.end(), schemas);
      if (call != null && schemas.has(call.name())) {
        last = call;
      }
    }
    // LAST wrapper wins, and only that one runs.
    //
    // Independent review, 881 §G finding 7: recovering every wrapper executes a call the model
    // RETRACTED — "<tool_call>A</tool_call> … wait, that needs a parent_path I don't have, instead
    // <tool_call>B</tool_call>" ran both A and B. Nothing in the samples says whether a
    // multi-wrapper thinking block is a batch or a revision, and the two are indistinguishable from
    // the text, so the choice is which error to make. Deferring a batched call costs one turn (the
    // loop simply asks again next iteration, and nothing is lost); executing a retracted one is an
    // action the model decided against. The wrapper is a commit marker only while there is one of
    // them, which is what every sample showed; the last is the intent the model settled on.
    return last == null
        ? List.of()
        : List.of(
            new ToolCallRequest(
                "reasoning-tool-" + RECOVERED_CALL_SEQ.incrementAndGet(),
                last.name(),
                last.arguments()));
  }

  /**
   * Monotonic across the whole run, because a recovered call id reaches the wire.
   *
   * <p>Independent review, 881 §G finding 10: a per-turn index restarts at 0 every iteration, so a
   * run that recovers on two turns emits two calls with the SAME id — and {@code
   * AgentSession.virtualToolFutures} keys on it and overwrites rather than rejecting, so the
   * collision would be silent. Harmless while recovery was a never-observed fallback; not harmless
   * at 40 % of turns.
   */
  private static final java.util.concurrent.atomic.AtomicLong RECOVERED_CALL_SEQ =
      new java.util.concurrent.atomic.AtomicLong();

  /**
   * Parse one {@code <tool_call>} body — XML {@code <function=…>} form first, then JSON.
   *
   * <p>Independent review, 881 §G finding 6: parameters are scoped to their OWN {@code <function>}
   * block. Scanning them across the whole wrapper let a second {@code <function=…>} donate its
   * arguments to the first while itself being dropped. A wrapper holding more than one function is
   * treated the same way a reasoning block holding more than one wrapper is (see {@link
   * #recoverCommittedToolCalls}): the last one is the intent.
   */
  private static InlineToolCall parseWrappedToolCall(
      String inner, int start, int end, ToolSchemas schemas) {
    java.util.regex.Matcher fn = XML_FUNCTION.matcher(inner);
    String name = null;
    int bodyFrom = -1;
    int bodyTo = -1;
    while (fn.find()) {
      name = fn.group(1);
      bodyFrom = fn.end();
      int close = inner.indexOf("</function>", bodyFrom);
      bodyTo = close < 0 ? inner.length() : close;
    }
    if (name != null) {
      var values = new LinkedHashMap<String, String>();
      java.util.regex.Matcher pm = XML_PARAMETER.matcher(inner.substring(bodyFrom, bodyTo));
      while (pm.find()) {
        values.put(pm.group(1), pm.group(2));
      }
      return new InlineToolCall(start, end, name, schemas.argumentsJson(name, values));
    }
    InlineToolCall json = parseToolCallObject(inner.strip(), start, end);
    return json == null
        ? null
        : new InlineToolCall(start, end, json.name(), json.arguments());
  }

  /**
   * Every tool-call span in {@code text}, in forward order and non-overlapping: the balanced-brace JSON
   * scan plus the wrapper-delimited XML/JSON spans. A tool call found ANYWHERE inside a
   * {@code <tool_call>…</tool_call>} wrapper is reported with the WRAPPER's span, so a strip removes
   * the markers with it. Pure.
   *
   * <p>Independent review, 881 §G finding 3: the earlier rule only widened to the wrapper when the
   * wrapper's ENTIRE body parsed as a call, so {@code Here.<tool_call>I will call {…}</tool_call>}
   * deleted just the JSON and left {@code Here.<tool_call>I will call </tool_call>} in the answer —
   * the husk the design said could not happen. Widening on containment rather than on a clean parse
   * closes it, and does so for every future body shape rather than for the ones observed so far.
   */
  static List<InlineToolCall> scanToolCallSpans(String text, ToolSchemas schemas) {
    // Every wrapper's char range, whether or not its body parses — the range is what a strip must
    // remove, and it is knowable even when the call inside has to come from the JSON scan.
    var wrapperRanges = new ArrayList<int[]>();
    var wrapped = new ArrayList<InlineToolCall>();
    java.util.regex.Matcher m = TOOL_CALL_WRAPPER.matcher(text);
    while (m.find()) {
      wrapperRanges.add(new int[] {m.start(), m.end()});
      InlineToolCall call = parseWrappedToolCall(m.group(1), m.start(), m.end(), schemas);
      if (call != null) {
        wrapped.add(call);
      }
    }
    var merged = new ArrayList<>(wrapped);
    for (InlineToolCall bare : scanInlineToolCallJson(text)) {
      boolean alreadyClaimed =
          wrapped.stream().anyMatch(w -> bare.start() >= w.start() && bare.end() <= w.end());
      if (alreadyClaimed) {
        continue;
      }
      int[] host =
          wrapperRanges.stream()
              .filter(r -> bare.start() >= r[0] && bare.end() <= r[1])
              .findFirst()
              .orElse(null);
      merged.add(
          host == null
              ? bare
              : new InlineToolCall(host[0], host[1], bare.name(), bare.arguments()));
    }
    merged.sort(java.util.Comparator.comparingInt(InlineToolCall::start));

    // Non-overlap is a PRECONDITION of the caller's back-to-front deletion, not a nicety: two
    // overlapping spans would delete each other's characters and corrupt the answer text. Each scan
    // is internally non-overlapping and the containment test above drops a JSON object nested in a
    // wrapper, so a survivor pair would need a JSON span that STRADDLES a wrapper boundary — which
    // needs `</tool_call>` inside a JSON string literal, and that same literal makes the wrapper's
    // own body unparseable, so no wrapper span is produced. No input was found that overlaps. This
    // filter is here so the deletion loop rests on an enforced invariant instead of that argument.
    var disjoint = new ArrayList<InlineToolCall>(merged.size());
    int consumedTo = 0;
    for (InlineToolCall span : merged) {
      if (span.start() >= consumedTo) {
        disjoint.add(span);
        consumedTo = span.end();
      }
    }
    return List.copyOf(disjoint);
  }

  /**
   * Find every JSON object span in {@code text} shaped like a tool call, regardless of delimiter (a
   * balanced-brace scan that respects string literals). Forward order, non-overlapping. Pure.
   */
  static List<InlineToolCall> scanInlineToolCallJson(String text) {
    var found = new ArrayList<InlineToolCall>();
    int i = 0;
    int len = text.length();
    while (i < len) {
      if (text.charAt(i) != '{') {
        i++;
        continue;
      }
      int end = matchBalancedBrace(text, i);
      if (end < 0) {
        break; // no closing brace → a trailing partial object; stop
      }
      InlineToolCall tc = parseToolCallObject(text.substring(i, end), i, end);
      if (tc != null) {
        found.add(tc);
        i = end;
      } else {
        i++;
      }
    }
    return found;
  }

  /** Index AFTER the brace matching the one at {@code open}, or -1 if unbalanced. Respects JSON strings. */
  private static int matchBalancedBrace(String s, int open) {
    int depth = 0;
    boolean inStr = false;
    boolean esc = false;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inStr) {
        if (esc) {
          esc = false;
        } else if (c == '\\') {
          esc = true;
        } else if (c == '"') {
          inStr = false;
        }
      } else if (c == '"') {
        inStr = true;
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return i + 1;
        }
      }
    }
    return -1;
  }

  /** Parse a JSON object span as a tool call (both grammars), or null if it is not tool-call-shaped. */
  private static InlineToolCall parseToolCallObject(String span, int start, int end) {
    try {
      JsonNode node = MAPPER.readTree(span);
      if (!node.isObject()) {
        return null;
      }
      boolean typeFunction = "function".equals(node.path("type").asText(""));
      JsonNode fn = node.get("function");
      JsonNode nameNode = (fn != null && fn.isObject()) ? fn.get("name") : node.get("name");
      JsonNode argsNode = (fn != null && fn.isObject()) ? fn.get("arguments") : null;
      if (argsNode == null) {
        argsNode = node.has("arguments") ? node.get("arguments") : node.get("parameters");
      }
      if (nameNode == null || !nameNode.isTextual()) {
        return null;
      }
      boolean hasArgs = argsNode != null && !argsNode.isNull();
      if (!typeFunction && !hasArgs) {
        return null; // e.g. {"name":"John","age":30} is ordinary content, not a tool call
      }
      String args;
      if (!hasArgs) {
        args = "{}";
      } else if (argsNode.isObject()) {
        args = MAPPER.writeValueAsString(argsNode);
      } else if (argsNode.isTextual()) {
        args = argsNode.asText(); // arguments-as-JSON-string variant
      } else {
        return null;
      }
      return new InlineToolCall(start, end, nameNode.asText(), args);
    } catch (Exception e) {
      return null;
    }
  }

  /** Canonicalised (name, args) key for de-duplicating a recovered call against the structured ones. */
  private static String dedupKey(String name, String arguments) {
    try {
      return name + ":" + MAPPER.writeValueAsString(MAPPER.readTree(arguments));
    } catch (Exception e) {
      return name + ":" + arguments;
    }
  }

  static Map<String, Object> buildAssistantToolCallMessage(LlmCallResult result) {
    return buildAssistantToolCallMessage(result, result.toolCalls());
  }

  static Map<String, Object> buildAssistantToolCallMessage(
      LlmCallResult result, List<ToolCallRequest> toolCalls) {
    List<Map<String, Object>> toolCallMaps = toolCalls.stream()
        .map(tc -> Map.<String, Object>of(
            "id", tc.id(),
            "type", "function",
            "function", Map.of("name", tc.toolName(), "arguments", tc.arguments())))
        .toList();
    var msg = new LinkedHashMap<String, Object>();
    msg.put("role", "assistant");
    if (result.textContent() != null && !result.textContent().isEmpty()) {
      msg.put("content", result.textContent());
    }
    msg.put("tool_calls", toolCallMaps);
    return msg;
  }
}
