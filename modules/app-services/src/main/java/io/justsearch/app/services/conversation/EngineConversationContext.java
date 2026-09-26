/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.registry.Audience;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Engine-internal mutable {@link ConversationContext} implementation.
 *
 * <p>Per tempdoc 491 §5.1: SPIs see the context as read-only; the engine appends to it
 * between iterations (assistant responses, {@code StreamConsumer} message deltas) and
 * increments the iteration counter. This implementation exposes the mutators as
 * package-private methods so only {@link ConversationEngine} can mutate state.
 *
 * <p>The returned {@link #messages()} list is wrapped in {@link Collections#unmodifiableList}
 * so SPIs cannot mutate it through the interface; structural mutations go through
 * {@link #appendMessage} on the engine side.
 */
final class EngineConversationContext implements ConversationContext {

  private final io.justsearch.core.context.EngineContext engineContext;

  @Override
  public io.justsearch.core.context.EngineContext engineContext() { return engineContext; }

  private final List<Map<String, Object>> messages;
  private final Audience audience;
  private final String sessionId;
  private final String shapeId;
  private final Map<String, Object> requestBody;
  private final Map<String, Object> attributes;
  private int iteration;

  EngineConversationContext(
      List<Map<String, Object>> initialMessages,
      Audience audience,
      String sessionId,
      String shapeId,
      Map<String, Object> requestBody, io.justsearch.core.context.EngineContext engineContext) {
    this.engineContext = Objects.requireNonNull(engineContext, "engineContext");
    Objects.requireNonNull(initialMessages, "initialMessages");
    this.messages = new ArrayList<>(initialMessages);
    this.audience = Objects.requireNonNull(audience, "audience");
    this.sessionId = sessionId; // nullable for Ephemeral shapes
    this.shapeId = Objects.requireNonNull(shapeId, "shapeId");
    this.requestBody =
        Map.copyOf(Objects.requireNonNull(requestBody, "requestBody"));
    this.attributes = new HashMap<>();
    this.iteration = 0;
  }

  @Override
  public List<Map<String, Object>> messages() {
    return Collections.unmodifiableList(messages);
  }

  @Override
  public int iteration() {
    return iteration;
  }

  @Override
  public Audience audience() {
    return audience;
  }

  @Override
  public String sessionId() {
    return sessionId;
  }

  @Override
  public String shapeId() {
    return shapeId;
  }

  @Override
  public Map<String, Object> requestBody() {
    return requestBody;
  }

  @Override
  public Map<String, Object> attributes() {
    return attributes;
  }

  // ---- Package-private mutators (engine-only) ----

  /** Append a message to the running history. */
  void appendMessage(Map<String, Object> message) {
    Objects.requireNonNull(message, "message");
    messages.add(message);
  }

  /** Append multiple messages (e.g., a stream consumer's deltas). */
  void appendMessages(List<Map<String, Object>> messages) {
    for (Map<String, Object> m : messages) {
      appendMessage(m);
    }
  }

  /** Advance the iteration counter (called between LLM calls). */
  void incrementIteration() {
    iteration++;
  }
}
