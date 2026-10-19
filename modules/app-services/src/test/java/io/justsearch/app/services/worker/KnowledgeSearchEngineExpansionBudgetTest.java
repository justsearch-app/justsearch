/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.SearchTrace;
import io.justsearch.ipc.TraceDegradation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec D §1.6 at the head seam (test H1, results half): an expansion re-search that loses a
 * semantic leg the base search had does not replace the base results; the expansion is recorded
 * with the existing expansion-timeout reason. A re-search that keeps its legs is applied as before.
 */
final class KnowledgeSearchEngineExpansionBudgetTest {

  private static final String SBE = "SEMANTIC_BUDGET_EXCEEDED";

  @TempDir Path modelsDir;
  private ConfigStore previousConfig;

  @BeforeEach
  void installConfig() {
    previousConfig = ConfigStore.globalOrNull();
    // An empty models directory: no reranker, so the only worker calls are the searches.
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", modelsDir.toString(),
        "justsearch.search.query_classification.enabled", "false"))));
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @Test
  void aReSearchThatLosesASemanticLegKeepsTheBaseResults() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      SearchRequest request = invocation.getArgument(0);
      if (isExpanded(request)) {
        // The re-search's shorter budget ran out before its dense leg: keyword results only.
        return hits("/expanded-keyword-only.txt").toBuilder()
            .setSearchTrace(SearchTrace.newBuilder().setDegradation(TraceDegradation.newBuilder()
                .setHybridFallback(true).setHybridFallbackReason(SBE)))
            .build();
      }
      return hits("/base-hybrid.txt");
    });

    KnowledgeSearchResponse response =
        engineOver(client).search(expandingRequest(), TestEngineContexts.internal());

    assertEquals(List.of("/base-hybrid.txt"), ids(response),
        "the base results, which kept their semantic leg, are returned");
    assertNotEquals(SBE, response.searchTrace().degradation() == null
        ? null : response.searchTrace().degradation().hybridFallbackReason());
    assertEquals("TIMEOUT", expansionReason(response),
        "the expansion that did not fit records the existing expansion-timeout reason");
  }

  @Test
  void aReSearchThatKeepsItsSemanticLegsIsApplied() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation ->
        isExpanded(invocation.getArgument(0)) ? hits("/expanded.txt") : hits("/base.txt"));

    KnowledgeSearchResponse response =
        engineOver(client).search(expandingRequest(), TestEngineContexts.internal());

    assertEquals(List.of("/expanded.txt"), ids(response));
    assertNull(expansionReason(response), "the expansion executed");
  }

  static boolean isExpanded(SearchRequest request) {
    return request.getQuerySyntax() == SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE;
  }

  static SearchResponse hits(String id) {
    return SearchResponse.newBuilder().setTotalHits(1)
        .addResults(SearchResult.newBuilder().setId(id).setScore(1)).build();
  }

  @SuppressWarnings("unchecked")
  static KnowledgeSearchEngine engineOver(KnowledgeClient client) {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    // The expansion model answers at once with a valid variant, so the re-search always runs.
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(any(), anyInt(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture("quokkas"));
    return new KnowledgeSearchEngine(bootstrap, mock(SearchPerSourceExecutor.class), ai, null,
        ConfigStore.global());
  }

  /** Sparse plus dense, expansion on, no rerankers: a SIMPLE query that expands. */
  static KnowledgeSearchRequest expandingRequest() {
    return new KnowledgeSearchRequest("quokka", 10, null, null, null, List.of(), null, null, null,
        null, false, true, new PipelineConfig(true, true, false, "rrf", false, false, 0, true, false));
  }

  private static List<String> ids(KnowledgeSearchResponse response) {
    return response.results().stream().map(KnowledgeSearchResponse.Hit::id).toList();
  }

  private static String expansionReason(KnowledgeSearchResponse response) {
    return response.searchTrace().stages().stream()
        .filter(stage -> stage.id()
            == io.justsearch.app.api.knowledge.SearchTrace.StageId.EXPANSION)
        .findFirst().orElseThrow().reason();
  }
}
