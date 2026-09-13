/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Singleton implementation that reports the agent capability as unavailable. */
enum UnavailableAgentService implements AgentService {
  INSTANCE;

  @Override
  public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    eventConsumer.accept(
        new AgentEvent.AgentError(
            "Agent capability is not available",
            AgentErrorCode.UNAVAILABLE,
            AgentErrorClass.PERMANENT,
            RetryAction.ABORT,
            null));
  }

  @Override
  public void approveToolCall(String sessionId, String callId) {
    throw new UnsupportedOperationException("Agent capability is not available");
  }

  @Override
  public void rejectToolCall(String sessionId, String callId, String reason) {
    throw new UnsupportedOperationException("Agent capability is not available");
  }

  @Override
  public void cancelSession(String sessionId) {
    // no-op when unavailable
  }

  @Override
  public List<Operation> availableOperations() {
    return List.of();
  }

  @Override
  public List<Operation> offeredOperations() {
    return List.of();
  }

  @Override
  public OperationResult undoOperation(String toolName, String executionId, EngineContext engineContext) {
    return OperationResult.failure("Agent capability is not available");
  }

  @Override
  public Map<String, Object> lastSessionSnapshot() {
    return null;
  }

  @Override
  public void resumeLastSession(Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    eventConsumer.accept(
        new AgentEvent.AgentError(
            "Agent capability is not available",
            AgentErrorCode.UNAVAILABLE,
            AgentErrorClass.PERMANENT,
            RetryAction.ABORT,
            null));
  }

  @Override
  public List<Map<String, Object>> sessionEvents(String sessionId) {
    return List.of();
  }

  @Override
  public boolean isAvailable() {
    return false;
  }
}
