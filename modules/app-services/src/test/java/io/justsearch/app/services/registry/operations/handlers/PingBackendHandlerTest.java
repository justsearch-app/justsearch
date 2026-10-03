package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import org.junit.jupiter.api.Test;

/** Health-check behavior for {@link PingBackendHandler} per tempdoc 429 §"Initial entries". */
final class PingBackendHandlerTest {

  private final PingBackendHandler handler = new PingBackendHandler();

  @Test
  void executeReturnsSuccess() {
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    assertEquals("Backend reachable", result.message());
  }

  @Test
  void executeIncludesTimestampInStructuredData() {
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertNotNull(result.structuredData());
    assertTrue(result.structuredData().containsKey("timestamp"));
    assertEquals("ok", result.structuredData().get("status"));
    Object timestamp = result.structuredData().get("timestamp");
    assertTrue(timestamp instanceof Long, "timestamp should be a Long; got " + timestamp);
  }

  @Test
  void executeHasNoExecutionId() {
    // Ping is not undoable
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.executionId().isEmpty());
  }
}
