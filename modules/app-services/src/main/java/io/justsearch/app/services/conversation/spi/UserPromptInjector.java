/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal {@link ContextInjector} for shapes whose user input is a single text prompt.
 *
 * <p>Per tempdoc 487 §6 step 13: consumed by {@code NavigateChatShape} (the
 * URL-emission consumer) and any future shape whose request body shape is
 * {@code {"prompt": "..."}}. Reads {@code requestBody().prompt} and emits a single
 * user message with that text.
 *
 * <p>Stateless singleton. Differs from {@code DocAccess} (which fetches doc content
 * from a {@code DocumentService} for the summarize shape) and from {@code RAGContext}
 * (which retrieves chunks via a search-then-fetch flow for the RAG shape) — this
 * injector does nothing beyond reading the prompt text out of the body.
 *
 * <p>Missing or empty {@code prompt} field: emits a {@link InjectorResult#terminalError}
 * with {@code MISSING_PROMPT} so the engine aborts before the LLM call rather than
 * sending an empty user message. Summarize accepts an absent instruction because its document
 * injector supplies the default summary task.
 */
public final class UserPromptInjector implements ContextInjector {

  /** Stable id used by {@code ConversationShape.contextInjectorIds}. */
  public static final String ID = "core.user-prompt";

  /** Singleton — stateless. */
  public static final UserPromptInjector INSTANCE = new UserPromptInjector();

  private UserPromptInjector() {}

  @Override
  public String id() {
    return ID;
  }

  @Override
  public InjectorResult inject(ConversationContext ctx) {
    Object raw = ctx.requestBody().get("prompt");
    String prompt = raw instanceof String s ? s.trim() : "";
    if (prompt.isEmpty()) {
      // Summarize already supplies its default task with the document or selection.
      if ("core.summarize".equals(ctx.shapeId())) return InjectorResult.empty();
      Map<String, Object> errorPayload = new LinkedHashMap<>();
      errorPayload.put("errorCode", "MISSING_PROMPT");
      errorPayload.put(
          "error", "Request body must include a non-empty 'prompt' string field.");
      errorPayload.put("retryable", false);
      return InjectorResult.terminalError(new SseEvent("error", errorPayload));
    }
    Map<String, Object> userMessage = new LinkedHashMap<>();
    userMessage.put("role", "user");
    userMessage.put("content", prompt);
    return InjectorResult.messagesOnly(List.of(userMessage));
  }
}
