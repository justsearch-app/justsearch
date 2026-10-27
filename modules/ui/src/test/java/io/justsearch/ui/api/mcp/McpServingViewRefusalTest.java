/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ui.api.KnowledgeSearchController;
import io.justsearch.ui.api.TestRequestContexts;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MCP search and answer called while the index has no serving view report a retryable
 * SERVICE_UNAVAILABLE tool error, not INVALID_STATE. The refusal comes from a real Knowledge
 * Server that has not published a serving view.
 */
final class McpServingViewRefusalTest {
  @TempDir Path directory;
  private ConfigStore previousConfigStore;
  private ConfigStore store;
  private IllegalStateException refusal;

  @BeforeEach
  void configure() throws Exception {
    previousConfigStore = ConfigStore.globalOrNull();
    store = TestResolvedConfigHelper.storeWithDefaults();
    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(executors,
          new WorkerConfig(directory, 60_000L, "0.0.0-test", Map.of(), "test", 500L));
      try {
        refusal = assertThrows(IllegalStateException.class, server::captureServingView);
      } finally {
        server.close();
      }
    }
  }

  @AfterEach
  void restore() {
    TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
  }

  @Test
  void searchDuringTheWindowIsARetryableUnavailableToolError() {
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.publicationLock()).thenReturn(store.publicationLock());
    when(bootstrap.acquireClientLease()).thenThrow(refusal);
    var controller = new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class),
        null, OnlineAiService.unavailable(), null, null, store);
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> controller, () -> null, Clock.systemUTC());
    assertUnavailableToolError(surface.callTool("justsearch_search",
        Map.of("query", "quarterly report"), "s1", TestRequestContexts.mcp("s1")));
  }

  @Test
  void answerDuringTheWindowIsARetryableUnavailableToolError() {
    var facade = mock(HeadAssembly.class);
    when(facade.captureServingView()).thenThrow(refusal);
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> null, () -> facade, Clock.systemUTC());
    assertUnavailableToolError(surface.callTool("justsearch_answer",
        Map.of("query", "quarterly report"), "s1", TestRequestContexts.mcp("s1")));
  }

  @SuppressWarnings("unchecked")
  private static void assertUnavailableToolError(Map<String, Object> result) {
    assertEquals(true, result.get("isError"), result.toString());
    var structured = (Map<String, Object>) result.get("structuredContent");
    assertEquals("SERVICE_UNAVAILABLE", structured.get("errorCode"), structured.toString());
    assertEquals("TRANSIENT", structured.get("errorClass"));
    assertEquals(true, structured.get("retryable"));
    var text = (String) ((List<Map<String, Object>>) result.get("content")).get(0).get("text");
    assertTrue(text.contains("Index serving view is unavailable"), text);
    assertTrue(text.contains("Error code: SERVICE_UNAVAILABLE."), text);
    assertTrue(text.contains("Retryable: true."), text);
  }
}
