/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.operations.*;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ui.api.OperationHistoryController;
import io.justsearch.ui.api.TestRequestContexts;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

final class OperationOutcomeTransportTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path temp;

  @Test
  void httpAndMcpReadOneReceiptWithoutDispatchAndKeepTheHistorySnapshot() throws Exception {
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"));
        var executors = new TestEngineExecutors()) {
      String key = OperationKeys.generate(Clock.systemUTC());
      EngineContext context = TestRequestContexts.mcp("query-session");
      var accepted = operations.accept(key, OperationDescriptor.invocation(OperationKind.MEMORY,
          "core.remember", "{\"private\":\"must-not-appear\"}", false), context, null).record();
      assertTrue(operations.finish(accepted.id(), OperationState.COMPLETE,
          new OperationReceipt("SUCCESS", "receipt-id")).isPresent());
      var facade = mock(HeadAssembly.class);
      when(facade.operationOutcome(key)).thenAnswer(unused -> operations.outcome(key));
      var dispatcher = mock(OperationDispatcher.class);
      var surface = new McpToolSurface(List.of(), dispatcher, () -> null, () -> facade, Clock.systemUTC());
      var history = new OperationHistoryStore();
      var entry = new OperationHistoryEntry(new OperationRef("core.remember"), "head", Instant.EPOCH,
          Instant.EPOCH.plusSeconds(1), OperationOutcome.SUCCESS, Optional.empty(),
          InvocationProvenance.systemInternal(Instant.EPOCH), Optional.empty());
      history.append(entry);
      var changes = new OperationHistoryChangeRegistry();
      var controller = new OperationHistoryController(executors, history, changes, facade::operationOutcome);
      try {
        Context http = mock(Context.class, RETURNS_SELF);
        when(http.pathParam("operationKey")).thenReturn(key);
        controller.handleOutcome(http);
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(http).result(bytes.capture());
        verify(http).header("Cache-Control", "no-store");
        var httpJson = JSON.readTree(bytes.getValue());
        var mcp = surface.callTool("justsearch_operation_outcome", Map.of("operationKey", key),
            "query-session", context);
        assertEquals(httpJson, JSON.readTree(JSON.writeValueAsBytes(mcp.get("structuredContent"))));
        assertEquals("complete", httpJson.path("state").asText());
        assertEquals("receipt-id", httpJson.path("result").path("executionId").asText());
        assertFalse(httpJson.toString().contains("must-not-appear"));
        assertFalse(mcp.containsKey("isError"));
        verifyNoInteractions(dispatcher);
        assertEquals(accepted.id(), operations.find(key).orElseThrow().id());

        Context snapshot = mock(Context.class, RETURNS_SELF);
        controller.handleGet(snapshot);
        var snapshotBytes = ArgumentCaptor.forClass(byte[].class);
        verify(snapshot).result(snapshotBytes.capture());
        var snapshotJson = JSON.readTree(snapshotBytes.getValue());
        assertEquals(changes.currentSeq(), snapshotJson.path("catalogVersion").asLong());
        assertEquals(JSON.readTree(JSON.writeValueAsBytes(history.recent())), snapshotJson.path("entries"));
        assertEquals(1, snapshotJson.path("entries").size());
      } finally {
        controller.shutdown();
      }
    }
  }

  @Test
  void invalidKeyHasTheSameTypedFailureOnBothTransportsAndMissingInputCannotQuery() throws Exception {
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"));
        var executors = new TestEngineExecutors()) {
      var facade = mock(HeadAssembly.class);
      when(facade.operationOutcome("execution-id")).thenAnswer(unused -> operations.outcome("execution-id"));
      var dispatcher = mock(OperationDispatcher.class);
      var surface = new McpToolSurface(List.of(), dispatcher, () -> null, () -> facade, Clock.systemUTC());
      var controller = new OperationHistoryController(executors, new OperationHistoryStore(),
          new OperationHistoryChangeRegistry(), facade::operationOutcome);
      try {
        Context http = mock(Context.class, RETURNS_SELF);
        when(http.pathParam("operationKey")).thenReturn("execution-id");
        controller.handleOutcome(http);
        verify(http).status(400);
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(http).result(bytes.capture());
        String code = JSON.readTree(bytes.getValue()).path("errorCode").asText();
        assertEquals("OPERATION_KEY_INVALID", code);
        var mcp = surface.callTool("justsearch_operation_outcome", Map.of("operationKey", "execution-id"),
            "query-session", TestRequestContexts.mcp("query-session"));
        assertEquals(true, mcp.get("isError"));
        assertEquals(code, ((Map<?, ?>) mcp.get("structuredContent")).get("errorCode"));
        clearInvocations(facade);
        assertEquals(true, surface.callTool("justsearch_operation_outcome", Map.of(),
            "query-session", TestRequestContexts.mcp("query-session")).get("isError"));
        verifyNoInteractions(facade, dispatcher);
        assertTrue(operations.openRecords().isEmpty());
      } finally {
        controller.shutdown();
      }
    }
  }
}
