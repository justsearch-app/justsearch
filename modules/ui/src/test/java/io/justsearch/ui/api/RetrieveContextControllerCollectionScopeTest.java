/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.RetrieveContextParams;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 821 §3-C2 — {@code POST /api/knowledge/retrieve-context} accepts a {@code
 * filters.collection} scope, using the same wire key as the search endpoint
 * ({@code KnowledgeSearchController#parseFilters}), so one scope name spans both surfaces.
 */
@DisplayName("RetrieveContextController — filters.collection (821 §3-C2)")
final class RetrieveContextControllerCollectionScopeTest {

  /** Captures the params the controller builds; returns a minimal successful result. */
  private static final class CapturingDocs implements DocumentService {
    final AtomicReference<RetrieveContextParams> lastParams = new AtomicReference<>();

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, EngineContext engineContext) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds, EngineContext engineContext) {
      return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question, Set<String> docIds, int topK, int maxContextTokens, EngineContext engineContext) {
      return CompletableFuture.completedFuture(
          new ContextResult("", 0, 0, 0, List.of(), "BM25", "", false, List.of()));
    }

    @Override
    public CompletionStage<ContextResult> retrieveContext(RetrieveContextParams params, EngineContext engineContext) {
      lastParams.set(params);
      return CompletableFuture.completedFuture(
          new ContextResult("ctx", 1, 1, 1, List.of(), "BM25", "ok", false, List.of()));
    }
  }

  private RetrieveContextParams invoke(Map<String, Object> body) {
    CapturingDocs docs = new CapturingDocs();
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(false);
    var controller = new RetrieveContextController(null, () -> docs, ai, () -> "");

    Context ctx = mock(Context.class);
    when(ctx.path()).thenReturn("/api/knowledge/retrieve-context");
    when(ctx.bodyAsClass(Map.class)).thenReturn(body);
    when(ctx.status(org.mockito.ArgumentMatchers.anyInt())).thenReturn(ctx);
    controller.handleRetrieveContext(ctx);

    assertNotNull(docs.lastParams.get(), "the controller must have reached retrieveContext");
    return docs.lastParams.get();
  }

  @Test
  @DisplayName("filters.collection is parsed and threaded to the retrieval params")
  void collectionIsParsed() {
    var params =
        invoke(Map.of("query", "what did the agent do?",
            "filters", Map.of("collection", List.of("agent-history"))));

    assertEquals(List.of("agent-history"), params.collection());
  }

  @Test
  @DisplayName("no filters block leaves the scope empty (the default scope, unchanged)")
  void absentFiltersLeaveScopeEmpty() {
    var params = invoke(Map.of("query", "what did the agent do?"));

    assertEquals(List.of(), params.collection());
  }

  @Test
  @DisplayName("a filters block without collection leaves the scope empty")
  void otherFiltersDoNotInventAScope() {
    var params =
        invoke(Map.of("query", "budget", "filters", Map.of("file_kind", List.of("markdown"))));

    assertEquals(List.of(), params.collection());
    assertEquals(List.of("markdown"), params.fileKind(), "unrelated filters still parse");
  }
}
