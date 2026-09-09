/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tempdoc 561 P-E — the agent's learning producer. Handles {@code core.remember}: persists a durable
 * fact / user preference the model learned during a run into the single-authority {@link MemoryStore}
 * (the same instance the {@code /api/memory} surface reads, so the user immediately sees + can forget
 * what the assistant learned). The conversation provenance is captured so each memory carries where it
 * was learned (the P-E provenance seed).
 */
public final class RememberFactHandler implements OperationHandler {

  private final MemoryStore memoryStore;

  public RememberFactHandler(MemoryStore memoryStore) {
    this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    return remember(argumentsJson, null);
  }

  /**
   * Context-aware overload: captures {@code provenance.correlationId()} (the chat/agent
   * conversationId) as the memory's source so the user can see which conversation taught it.
   */
  @Override
  public OperationResult execute(String argumentsJson, InvocationProvenance provenance, EngineContext engineContext) {
    String sourceConversationId =
        provenance == null ? null : provenance.correlationId().orElse(null);
    return remember(argumentsJson, sourceConversationId);
  }

  private OperationResult remember(String argumentsJson, String sourceConversationId) {
    try {
      Map<String, Object> args =
          argumentsJson == null || argumentsJson.isBlank()
              ? Map.of()
              : HandlerJson.MAPPER.readValue(argumentsJson, Map.class);
      Object contentObj = args.get("content");
      String content = contentObj instanceof String s ? s.trim() : "";
      if (content.isEmpty()) {
        return OperationResult.failure("remember requires a non-empty 'content'");
      }
      Object kindObj = args.get("kind");
      String kind = kindObj instanceof String s && !s.isBlank() ? s.trim() : "fact";
      MemoryRecord record =
          new MemoryRecord(
              UUID.randomUUID().toString(),
              kind,
              content,
              sourceConversationId,
              "agent",
              Instant.now());
      memoryStore.remember(record);
      return OperationResult.success("Remembered.", Map.of("id", record.id(), "kind", kind));
    } catch (Exception e) {
      return OperationResult.failure("Failed to remember: " + e.getMessage());
    }
  }
}
