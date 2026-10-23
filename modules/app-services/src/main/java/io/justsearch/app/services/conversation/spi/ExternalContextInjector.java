/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.core.util.ContextBudget;
import io.justsearch.core.util.TokenEstimation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards prior conversation messages from the FE thread into the LLM context.
 *
 * <p>Reads an optional {@code context} array from the request body — a list of
 * {@code {role, content}} message objects representing prior turns in the unified
 * chat surface. Returns empty if the key is absent, enabling zero-impact on shapes
 * that don't send it.
 *
 * <p>Token-capped: keeps the most recent messages that fit within
 * {@link ContextBudget#externalContextCap()}, truncating from oldest first to preserve recency.
 *
 * <p><b>Tempdoc 883 decision 3.</b> The cap used to be a flat 1000 tokens, documented as "~25% of a
 * conservative 8K context window" — the same fraction this still applies, but against a window that
 * was neither conservative nor real (the shipped default was 4096; the launch ladder now derives
 * 32768 on a GPU). It also dropped history in complete silence. Both are fixed here: the cap is
 * derived per request from the live window, and a drop is reported at INFO with the before/after
 * token counts, because a prompt that quietly lost the turn the user is referring to is exactly the
 * failure a reader needs to be able to see.
 */
public final class ExternalContextInjector implements ContextInjector {

  private static final Logger LOG = LoggerFactory.getLogger(ExternalContextInjector.class);

  public static final String ID = "core.external-context";

  /**
   * The live context window, read per request. Null (a caller that wired no inference handle)
   * budgets against {@link ContextBudget#FALLBACK_WINDOW_TOKENS}.
   */
  private final Supplier<OnlineAiService> onlineAi;

  /**
   * Composition-root constructor (tempdoc 883). Takes the same {@code Supplier<OnlineAiService>}
   * {@link RAGContext} gets, so both injectors on a turn budget against the same window.
   */
  public ExternalContextInjector(Supplier<OnlineAiService> onlineAi) {
    this.onlineAi = onlineAi;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public InjectorResult inject(ConversationContext ctx) {
    Object raw = ctx.requestBody().get("context");
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      return InjectorResult.empty();
    }

    List<Map<String, Object>> parsed = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        Object role = map.get("role");
        Object content = map.get("content");
        if (role instanceof String r && content instanceof String c && !c.isBlank()) {
          Map<String, Object> msg = new LinkedHashMap<>();
          msg.put("role", r);
          msg.put("content", c);
          parsed.add(msg);
        }
      }
    }

    if (parsed.isEmpty()) {
      return InjectorResult.empty();
    }

    int cap = RAGContext.budgetFor(onlineAi, ctx).externalContextCap();

    int offeredTokens = 0;
    for (Map<String, Object> msg : parsed) {
      offeredTokens += TokenEstimation.estimateTokens((String) msg.get("content"));
    }

    if ("core.extract".equals(ctx.shapeId()) && offeredTokens > cap) {
      return InjectorResult.terminalError(new io.justsearch.agent.api.conversation.SseEvent("error", Map.of(
          "error", "Conversation context exceeds the extraction budget; reduce the context before extracting.",
          "errorCode", "INPUT_TOO_LARGE")));
    }

    // Keep most recent messages that fit within the token budget.
    // Walk backwards from newest, accumulating until budget is exhausted. The walk STOPS at the
    // first message that does not fit rather than skipping it: what survives must be a contiguous
    // recent suffix of the thread, because a history with a hole in it reads as a different
    // conversation than the one the user had.
    List<Map<String, Object>> kept = new ArrayList<>();
    int tokensUsed = 0;
    for (int i = parsed.size() - 1; i >= 0; i--) {
      String content = (String) parsed.get(i).get("content");
      int msgTokens = TokenEstimation.estimateTokens(content);
      if (tokensUsed + msgTokens > cap && !kept.isEmpty()) {
        break;
      }
      kept.add(0, parsed.get(i));
      tokensUsed += msgTokens;
    }

    if (kept.size() < parsed.size()) {
      // Tempdoc 883 — say so. The old loop `break`ed here and told nobody, so a turn answered
      // without the history it was supposed to have looked identical to one that had it all.
      LOG.info(
          "ExternalContextInjector: dropped {} of {} prior messages to fit the context budget"
              + " ({} -> {} tokens, cap {})",
          parsed.size() - kept.size(),
          parsed.size(),
          offeredTokens,
          tokensUsed,
          cap);
    }

    return InjectorResult.messagesOnly(kept);
  }
}
