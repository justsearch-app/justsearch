/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.search.SearchServiceImpl;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Preservation checks: these selected-field projections already exclude the rerank input. */
final class WorkerSearchOutputProjectionTest {
  private ConfigStore previousConfig;

  @BeforeEach
  void installConfig() {
    previousConfig = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  private static SearchResponse wire() {
    return SearchResponse.newBuilder().setTotalHits(1)
        .addResults(SearchResult.newBuilder().setId("doc").setScore(1)
            .putFields("path", "doc").putFields("title", "T")
            .putFields("content_preview", "chunk")
            .putFields("parent_opening", "private-opening"))
        .putFacets("mime", io.justsearch.ipc.FacetCounts.newBuilder().putCounts("pdf", 1).build())
        .build();
  }

  @Test
  void searchPortAndStableSearchServiceReturnOnlyTypedResultValues() {
    var calls = mock(SearchServiceCalls.class);
    when(calls.search(any())).thenReturn(wire());
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var client = new TestKnowledgeClient(executors, calls)) {
      var service = new SearchServiceImpl(() -> client);
      var response = service.search(new io.justsearch.app.api.SearchRequest(
          10, 0, false, null, null, List.of(
              new io.justsearch.app.api.SearchRequest.Clause("text", null, "quokka", List.of())),
          null), TestEngineContexts.internal());

      assertEquals("doc", response.hits().getFirst().doc_id());
      assertPublic(response);
      verify(calls).search(any());
    }
  }

  @Test
  void openRagRetrievalForwardsOnlyDiscoveredPathsToTheAnswerRetrieval() throws Exception {
    var client = mock(KnowledgeClient.class);
    when(client.requestDeadlineMs()).thenReturn(10_000L);
    when(client.search(any(SearchRequest.class), any())).thenReturn(wire());
    when(client.retrieveContext(any(RetrieveContextParams.class), any())).thenAnswer(call -> {
      RetrieveContextParams params = call.getArgument(0);
      assertEquals(List.of("doc"), List.copyOf(params.docIds()));
      assertPublic(params);
      return RetrieveContextResponse.newBuilder().setContext("retrieved evidence").build();
    });
    var service = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);

    var response = service.retrieveContext(RetrieveContextParams.of("quokka", 5, 4096),
        TestEngineContexts.internal()).toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertEquals("retrieved evidence", response.context());
    assertPublic(response);
    verify(client).search(any(SearchRequest.class), any());
    verify(client).retrieveContext(any(RetrieveContextParams.class), any());
  }

  @Test
  void retrieverAndRerankerTelemetryExportOnlyTitleAndWinningPassage() {
    var hits = wire().getResultsList();
    var reranked = RerankResponse.newBuilder().addScores(2f).addSortedIndices(0).build();
    var retriever = SearchTraceMapper.oiDocsOf(hits);
    var crossEncoder = SearchTraceMapper.ceOutputDocs(hits, reranked);

    assertEquals("T chunk", retriever.getFirst().content());
    assertPublic(io.justsearch.telemetry.OpenInferenceSpans.retriever(retriever));
    assertPublic(io.justsearch.telemetry.OpenInferenceSpans.reranker("cross-encoder", 1, crossEncoder));
    assertPublic(SearchTraceMapper.mapSearchTrace(wire(), List.of()));
  }

  @Test
  void publicFacetSnapshotUsesOnlyFacetCountsAndSourceVocabulary() {
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any())).thenReturn(wire());
    when(client.getStatus(any())).thenReturn(io.justsearch.ipc.StatusResponse.getDefaultInstance());
    var server = mock(KnowledgeServerBootstrap.class);
    when(server.isReady()).thenReturn(true);
    when(server.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(server.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(call ->
        ((java.util.function.Function<KnowledgeClient, ?>) call.getArgument(0)).apply(client));
    var adapter = new KnowledgeHttpApiAdapter(server, mock(SearchPerSourceExecutor.class));
    adapter.search(new io.justsearch.app.api.knowledge.KnowledgeSearchRequest("quokka", 10,
        null, null, null, List.of(), null, null, null, null, false, false,
        new io.justsearch.app.api.knowledge.PipelineConfig(
            true, false, false, "none", false, false, 0, false, false)),
        TestEngineContexts.internal());

    String snapshot = adapter.getCachedFacetSnapshot();
    assertTrue(snapshot.contains("pdf"), snapshot);
    assertPublic(snapshot);
    verify(client, times(2)).search(any(SearchRequest.class), any());
  }

  @Test
  void gplMimeFacetProbeReturnsOnlyFacetCounts() {
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any())).thenReturn(wire());

    var facets = io.justsearch.app.services.bootstrap.phases.GplOrchestration.fetchMimeFacets(client);

    assertEquals(Map.of("pdf", 1L), facets);
    assertPublic(facets);
  }

  private static void assertPublic(Object output) {
    assertFalse(output.toString().contains("parent_opening"), output.toString());
    assertFalse(output.toString().contains("private-opening"), output.toString());
  }
}
