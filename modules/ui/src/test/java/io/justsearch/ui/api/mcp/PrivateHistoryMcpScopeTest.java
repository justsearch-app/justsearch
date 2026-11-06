/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.services.HeadAssembly;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class PrivateHistoryMcpScopeTest {
  @Test
  void outsideSearchAndAnswerRefuseHistoryAloneOrMixedBeforeCallingTheBackend() {
    DocumentService documents = mock(DocumentService.class);
    HeadAssembly facade = mock(HeadAssembly.class);
    McpAnswerCaptureFixture.bind(facade, documents);
    when(documents.retrieveContext(any(), any())).thenReturn(CompletableFuture.completedFuture(
        new ContextResult("private assistant history", 1, 1, 1, List.of(), "BM25", "ok", false, List.of())));
    var controller = mock(io.justsearch.ui.api.KnowledgeSearchController.class);
    var adapter = mock(io.justsearch.app.services.worker.KnowledgeHttpApiAdapter.class);
    when(controller.getAdapter()).thenReturn(adapter);
    McpSearchSessionFixture.stub(adapter, new io.justsearch.app.api.knowledge.KnowledgeSearchResponse(
        0L, 0L, 0L, List.of(), null, null, null, null, null, null, null, null, null));
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> controller, () -> facade, Clock.systemUTC());
    for (String tool : List.of("justsearch_search", "justsearch_answer")) {
      for (List<String> collections : List.of(List.of("agent-history"), List.of("personal", "agent-history"))) {
        var result = surface.callTool(tool, Map.of("query", "history", "filters", Map.of("collection", collections)),
            "s1", TestRequestContexts.mcp("s1"));
        assertEquals(Boolean.TRUE, result.get("isError"), tool + collections);
        org.junit.jupiter.api.Assertions.assertTrue(result.toString().contains("private"), result.toString());
      }
    }
    verifyNoInteractions(documents, adapter);
  }

  @Test
  void outsideBrowseRefusesHistoryScopesBeforeDispatch() {
    var dispatcher = mock(OperationDispatcher.class);
    var surface = new McpToolSurface(List.of(new io.justsearch.agent.tools.AgentToolsOperationCatalog()),
        dispatcher, () -> null, () -> null, Clock.systemUTC());
    for (Object collections : List.of("agent-history", List.of("personal", "agent-history"))) {
      for (Map<String, Object> arguments : List.of(
          Map.<String, Object>of("collection", collections),
          Map.<String, Object>of("filters", Map.of("collection", collections)))) {
        var result = surface.callTool("justsearch_browse", arguments, "s1", TestRequestContexts.mcp("s1"));
        assertEquals(Boolean.TRUE, result.get("isError"));
        org.junit.jupiter.api.Assertions.assertTrue(result.toString().contains("private"), result.toString());
      }
    }
    verifyNoInteractions(dispatcher);
  }
}
