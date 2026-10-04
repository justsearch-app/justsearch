package io.justsearch.agent.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentServiceUnavailableTest {

  private final AgentService service = AgentService.unavailable();

  @Test
  void unavailableReturnsSingleton() {
    assertSame(AgentService.unavailable(), AgentService.unavailable());
  }

  @Test
  void isAvailableReturnsFalse() {
    assertFalse(service.isAvailable());
  }

  @Test
  void availableOperationsReturnsEmpty() {
    assertTrue(service.availableOperations().isEmpty());
  }

  @Test
  void runAgentEmitsError() {
    var events = new ArrayList<AgentEvent>();
    var request = new AgentRequest(List.of(Map.of("role", "user", "content", "test")), List.of(), 1);
    service.runAgent(request, events::add, TestEngineContexts.agentLoop());
    assertEquals(1, events.size());
    assertInstanceOf(AgentEvent.AgentError.class, events.getFirst());
    var error = (AgentEvent.AgentError) events.getFirst();
    assertEquals("UNAVAILABLE", error.errorCode());
  }

  @Test
  void approveToolCallThrows() {
    assertThrows(UnsupportedOperationException.class,
        () -> service.approveToolCall("s", "c"));
  }

  @Test
  void rejectToolCallThrows() {
    assertThrows(UnsupportedOperationException.class,
        () -> service.rejectToolCall("s", "c", "r"));
  }

  @Test
  void cancelSessionIsNoOp() {
    assertDoesNotThrow(() -> service.cancelSession("s"));
  }
}
