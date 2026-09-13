/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Tempdoc 603 C2 — conversation-aware query DECONTEXTUALIZATION for the RAG-ask (Documents) shape.
 *
 * <p>A follow-up question ("why was it designed that way?") retrieves on its RAW text, so it pulls a
 * fresh — possibly contradictory — passage set versus the prior turn (593 ADD2 multi-turn
 * self-contradiction). Tempdoc 498's {@link ExternalContextInjector} threads prior messages into the LLM
 * PROMPT, but NOT into retrieval. This injector closes that gap: on a follow-up it rewrites the question
 * into a STANDALONE form (resolving coreference/ellipsis from the conversation {@code context}) and stashes
 * it in {@code ctx.attributes()} under {@link #ATTR_STANDALONE_QUESTION}; {@link RAGContext} then retrieves
 * on the standalone question if present.
 *
 * <p>Runs BEFORE {@link RAGContext} in the shape manifest. Head-only: a single deterministic llama-server
 * completion via {@link OnlineAiService#chatCompletion} — no proto / worker / {@code KnowledgeSearchEngine}
 * changes, so head-never-touches-lucene holds. Mirrors the {@code KnowledgeSearchEngine} EXPANSION stage:
 * budget-gated, deterministic, TOTAL graceful fallback. It NEVER blocks the conversation — first-turn /
 * AI-unavailable / timeout / blank / over-long all simply skip, leaving {@link RAGContext} to use the raw
 * question (only {@link RAGContext} owns terminal errors).
 */
public final class QueryRewriteInjector implements ContextInjector {

  public static final String ID = "core.query-rewrite";

  /** Cross-SPI scratch key: the decontextualized standalone question {@link RAGContext} retrieves on. */
  public static final String ATTR_STANDALONE_QUESTION = "rag.standaloneQuestion";

  static final int REWRITE_MAX_TOKENS = 96;
  static final Duration DEFAULT_BUDGET = Duration.ofMillis(1500);

  private static final String SYSTEM_PROMPT =
      "Rewrite the user's follow-up question into a single standalone question that can be understood"
          + " without the conversation. Resolve pronouns and references (it, that, this, the document)"
          + " using the prior messages. Preserve the original intent and entities. Output ONLY the"
          + " rewritten question — no preamble, no explanation, no quotes. If it is already standalone,"
          + " output it unchanged.";

  private final Supplier<OnlineAiService> ai;
  private final Duration budget;

  public QueryRewriteInjector(Supplier<OnlineAiService> ai) {
    this(ai, DEFAULT_BUDGET);
  }

  QueryRewriteInjector(Supplier<OnlineAiService> ai, Duration budget) {
    this.ai = ai;
    this.budget = budget;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public InjectorResult inject(ConversationContext ctx) {
    Map<String, Object> body = ctx.requestBody();
    if (!(body.get("question") instanceof String question) || question.isBlank()) {
      return InjectorResult.empty(); // RAGContext owns the missing-question terminal error
    }
    List<Map<String, String>> history = priorTurns(body.get("context"));
    if (history.isEmpty()) {
      return InjectorResult.empty(); // first turn — nothing to decontextualize; never pay the LLM cost
    }
    OnlineAiService svc = ai.get();
    if (svc == null || !svc.isAvailable()) {
      return InjectorResult.empty(); // AI down — fall back to the raw question
    }

    String standalone;
    try {
      List<Map<String, Object>> messages =
          List.of(
              Map.of("role", "system", "content", SYSTEM_PROMPT),
              Map.of("role", "user", "content", buildUserPrompt(history, question)));
      standalone =
          svc.chatCompletion(messages, REWRITE_MAX_TOKENS, SamplingParams.DETERMINISTIC, ctx.engineContext())
              .get(budget.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return InjectorResult.empty();
    } catch (Exception e) {
      io.justsearch.core.execution.EngineFutures.rethrowExecutorRefusal(e);
      return InjectorResult.empty(); // timeout / failure — fall back to the raw question, never block
    }

    standalone = standalone == null ? "" : standalone.strip();
    // Validation guard (mirrors mergeExpansion): reject a blank or an over-long ramble; on reject, fall back.
    if (standalone.isBlank() || standalone.length() > Math.max(240, question.length() * 4)) {
      return InjectorResult.empty();
    }
    if (standalone.equals(question)) {
      return InjectorResult.empty(); // already standalone — no rewrite to record, no event noise
    }

    ctx.attributes().put(ATTR_STANDALONE_QUESTION, standalone);
    // Tempdoc 603 PART IV transparency — surface the rewrite via a conversation-layer SSE event (no proto).
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("original", question);
    payload.put("standalone", standalone);
    return InjectorResult.of(List.of(), List.of(new SseEvent("rag.rewrite", payload)));
  }

  /** Parse the request-body {@code context} array (prior {role,content} messages) — the shape 498 reads. */
  private static List<Map<String, String>> priorTurns(Object raw) {
    List<Map<String, String>> out = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map
            && map.get("role") instanceof String role
            && map.get("content") instanceof String content
            && !content.isBlank()) {
          out.add(Map.of("role", role, "content", content));
        }
      }
    }
    return out;
  }

  private static String buildUserPrompt(List<Map<String, String>> history, String question) {
    StringBuilder sb = new StringBuilder("Conversation so far:\n");
    for (Map<String, String> m : history) {
      sb.append(m.get("role")).append(": ").append(m.get("content")).append('\n');
    }
    sb.append("\nFollow-up: ").append(question);
    return sb.toString();
  }
}
