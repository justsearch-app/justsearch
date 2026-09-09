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

/**
 * Tempdoc 821 §3-C2 — {@code justsearch_answer}'s {@code filters.collection}. The key was already
 * accepted (and forwarded) on the MCP SEARCH path; on the ANSWER path it was neither declared in
 * {@code FILTERS_SCHEMA} nor read, so an agent could not scope a question the way it could scope a
 * search.
 */
@DisplayName("McpToolSurface — justsearch_answer collection scope (821 §3-C2)")
final class McpAnswerCollectionScopeTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneId.of("UTC"));

  private static RetrieveContextParams callAnswerWith(Map<String, Object> args) {
    ContextResult result =
        new ContextResult("[From: doc-1]\nexcerpt", 1, 1, 0, List.of(), "HYBRID", "OK", false,
            List.of());
    DocumentService documents = mock(DocumentService.class);
    when(documents.retrieveContext(any(), any(EngineContext.class))).thenReturn(CompletableFuture.completedFuture(result));
    WorkerServices workers = new WorkerServices(null, documents, null, null, null);
    HeadAssembly facade = mock(HeadAssembly.class);
    when(facade.workers()).thenReturn(workers);

    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> null,
            () -> facade,
            FIXED_CLOCK);
    surface.callTool("justsearch_answer", args, "s1", TestRequestContexts.mcp("s1"));

    ArgumentCaptor<RetrieveContextParams> captor =
        ArgumentCaptor.forClass(RetrieveContextParams.class);
    verify(documents).retrieveContext(captor.capture(), any(EngineContext.class));
    return captor.getValue();
  }

  @Test
  @DisplayName("filters.collection reaches the retrieval params")
  void collectionReachesParams() {
    var params =
        callAnswerWith(
            Map.of(
                "query", "what did the agent do?",
                "filters", Map.of("collection", List.of("agent-history"))));

    assertEquals(List.of("agent-history"), params.collection());
  }

  @Test
  @DisplayName("a bare-string scope is rejected at the boundary, not silently coerced")
  void bareStringScopeIsRejectedAtTheBoundary() {
    DocumentService documents = mock(DocumentService.class);
    WorkerServices workers = new WorkerServices(null, documents, null, null, null);
    HeadAssembly facade = mock(HeadAssembly.class);
    when(facade.workers()).thenReturn(workers);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> null,
            () -> facade,
            FIXED_CLOCK);

    Map<String, Object> result =
        surface.callTool(
            "justsearch_answer",
            Map.of("query", "what?", "filters", Map.of("collection", "agent-history")),
            "s1",
            TestRequestContexts.mcp("s1"));

    // Declaring the property (655's "declared, not opaque" shape) means the boundary validator
    // catches a malformed scope before it reaches the handler's unchecked casts.
    assertEquals(Boolean.TRUE, result.get("isError"), "a non-array scope must be rejected");
    verifyNoInteractions(documents);
  }

  @Test
  @DisplayName("no filters leaves the scope empty (the default scope, unchanged)")
  void absentScopeStaysEmpty() {
    var params = callAnswerWith(Map.of("query", "what?"));

    assertEquals(List.of(), params.collection());
  }

  @Test
  @DisplayName("the shared filters schema declares collection as a lean string array")
  void schemaDeclaresCollection() {
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> null,
            () -> mock(HeadAssembly.class),
            FIXED_CLOCK);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tools =
        (List<Map<String, Object>>) surface.listTools().get("tools");
    assertEquals("justsearch_answer", tools.get(0).get("name"), "answer is position-biased first");
    @SuppressWarnings("unchecked")
    Map<String, Object> answerSchema = (Map<String, Object>) tools.get(0).get("inputSchema");
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) answerSchema.get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> filters = (Map<String, Object>) props.get("filters");
    @SuppressWarnings("unchecked")
    Map<String, Object> filterProps = (Map<String, Object>) filters.get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> collection = (Map<String, Object>) filterProps.get("collection");

    assertEquals("array", collection.get("type"));
    assertEquals(Map.of("type", "string"), collection.get("items"));
    assertEquals(
        "Restrict to these collections (omit for default)",
        collection.get("description"),
        "F-016: one enum-free string array and one sentence — schema bulk degrades small models");
  }
}
