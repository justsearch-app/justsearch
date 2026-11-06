/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.HitStage;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.app.api.gpl.RerankerService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Owner-adopted agreement scenarios C1-C4/P1-P3, task t-20261105-sqpath. */
final class KnowledgeSearchEngineAgreementTest {
  @TempDir Path tempDir;
  private ConfigStore previousConfig;
  private KnowledgeClient client;
  private SearchPerSourceExecutor perSource;
  private OnlineAiService ai;

  @BeforeEach
  void setUp() throws Exception {
    previousConfig = ConfigStore.globalOrNull();
    Path model = Files.createDirectories(tempDir.resolve("reranker"));
    Files.writeString(model.resolve("model.onnx"), "stand-in");
    Files.writeString(model.resolve("tokenizer.json"), "{}");
    configure(Map.of());
    client = mock(KnowledgeClient.class);
    perSource = mock(SearchPerSourceExecutor.class);
    ai = OnlineAiService.unavailable();
    when(client.search(any(SearchRequest.class), any())).thenReturn(candidates(
        hit("winner", 1, 1), hit("other", 2, 2), hit("tail", 3, 3)));
    when(client.rerank(anyString(), anyList(), anyLong(), any())).thenReturn(contraryCe());
  }

  @AfterEach
  void restoreConfiguration() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @Test
  void contraryCePromotesTheSameDocumentForNormalAndDetailedDelivery() {
    for (boolean detail : List.of(false, true)) {
      var response = search(request(10, null, null, detail, pipeline(false, false)), null);
      assertEquals(List.of("winner", "tail", "other"), ids(response));
      assertEquals(SearchTrace.StageStatus.EXECUTED, ce(response).status());
      assertNull(ce(response).reason());
      assertEquals("keyword_dense_top1_promoted", ce(response).detail());
      assertEquals(List.of(11d, 33d, 22d), response.results().stream()
          .map(KnowledgeSearchResponse.Hit::score).toList());
      assertEquals(List.of(-3f, 8f, 5f), response.results().stream()
          .map(h -> h.trace().stream().filter(s -> s.id() == SearchTrace.StageId.CROSS_ENCODER)
              .findFirst().orElseThrow().score()).toList());
    }
  }

  @Test
  void winnerOutsideCeWindowAfterLambdaMartIsPromotedBeforeLimitWithoutInventingCeScore() {
    RerankerService ltr = mock(RerankerService.class);
    when(ltr.isLoaded()).thenReturn(true);
    when(ltr.rerank(any(), any(), any(), anyInt())).thenReturn(List.of(1, 2, 0));
    when(client.rerank(anyString(), anyList(), anyLong(), any())).thenReturn(
        RerankResponse.newBuilder().addAllSortedIndices(List.of(1, 0))
            .addAllScores(List.of(-4f, 6f)).build());
    var response = search(request(1, null, null, false, new PipelineConfig(true, true, false, "rrf", true, true, 2, false, false)), ltr);
    assertEquals(List.of("winner"), ids(response));
    assertEquals(11f, response.results().getFirst().score());
    assertTrue(response.results().getFirst().trace().stream()
        .noneMatch(s -> s.id() == SearchTrace.StageId.CROSS_ENCODER));
    assertEquals("keyword_dense_top1_promoted", ce(response).detail());
  }

  @Test
  void alreadyFirstEmptyAndSingletonDoNotClaimPromotion() {
    when(client.rerank(anyString(), anyList(), anyLong(), any())).thenReturn(
        RerankResponse.newBuilder().addAllSortedIndices(List.of(0, 2, 1))
            .addAllScores(List.of(8f, -3f, 5f)).build());
    var alreadyFirst = search(request(10, null, null, false, pipeline(false, false)), null);
    assertEquals(List.of("winner", "tail", "other"), ids(alreadyFirst));
    assertNull(ce(alreadyFirst).detail());
    when(client.search(any(SearchRequest.class), any())).thenReturn(candidates(hit("winner", 1, 1)));
    var singleton = search(request(1, null, null, false, pipeline(false, false)), null);
    assertEquals(List.of("winner"), ids(singleton));
    assertNull(ce(singleton).detail());
    when(client.search(any(SearchRequest.class), any())).thenReturn(candidates());
    var empty = search(request(1, null, null, false, pipeline(false, false)), null);
    assertEquals(List.of(), ids(empty));
    assertNull(ce(empty).detail());
  }

  @Test
  void missingZeroDifferentAndAmbiguousRanksDoNotEstablishAgreement() {
    for (SearchResponse input : List.of(
        candidates(hit("winner", null, 1), hit("other", 2, 2), hit("tail", 3, 3)),
        candidates(hit("winner", 1, null), hit("other", 2, 2), hit("tail", 3, 3)),
        candidates(hit("winner", 0, 1), hit("other", 2, 2), hit("tail", 3, 3)),
        candidates(hit("winner", 1, 0), hit("other", 2, 2), hit("tail", 3, 3)),
        candidates(hit("winner", 1, 2), hit("other", 2, 1), hit("tail", 3, 3)),
        candidates(hit("winner", 1, 1), hit("other", 1, 1), hit("tail", 3, 3)),
        candidates(hit("winner", 1, 1), hit("other", 1, 2), hit("tail", 3, 3)),
        candidates(hit("winner", 1, 1), hit("other", 2, 1), hit("tail", 3, 3)),
        candidates(hit("winner", null, 1).toBuilder().addTrace(
            HitStage.newBuilder().setId("sparse-retrieval").setScore(7f)).build(),
            hit("other", 2, 2), hit("tail", 3, 3)),
        candidates(hit("winner", null, null), hit("other", null, null), hit("tail", null, null)))) {
      when(client.search(any(SearchRequest.class), any())).thenReturn(input);
      var response = search(request(10, null, null, false, pipeline(false, false)), null);
      assertEquals(List.of("tail", "other", "winner"), ids(response));
      assertNull(ce(response).detail());
    }
  }

  @Test
  void spladeAndChunkSignalsDoNotReplaceDocumentSparseDenseRanks() {
    var chunkOnly = hit("winner", null, null).toBuilder()
        .addTrace(HitStage.newBuilder().setId("chunk-merge").setRank(1)).build();
    when(client.search(any(SearchRequest.class), any())).thenReturn(candidates(
        chunkOnly, hit("other", 2, 2), hit("tail", 3, 3)));
    assertEquals(List.of("tail", "other", "winner"),
        ids(search(request(10, null, null, false, pipeline(false, false)), null)));
    when(client.search(any(SearchRequest.class), any())).thenReturn(candidates(
        hit("winner", 1, 1).toBuilder().addTrace(
            HitStage.newBuilder().setId("splade-retrieval").setRank(3)).build(),
        hit("other", 2, 2), hit("tail", 3, 3).toBuilder().addTrace(
            HitStage.newBuilder().setId("splade-retrieval").setRank(1)).build()));
    assertEquals(List.of("winner", "tail", "other"),
        ids(search(request(10, null, null, false, pipeline(false, false)), null)));
  }

  @Test
  void cursorAndSingleLegKeepCeOrderWhileFieldSortKeepsWorkerOrder() {
    var cursor = search(request(10, "page-two", null, false, pipeline(false, false)), null);
    assertEquals(List.of("tail", "other", "winner"), ids(cursor));
    assertNull(ce(cursor).detail());
    for (PipelineConfig single : List.of(
        new PipelineConfig(true, false, false, "none", false, true, 3, false, false),
        new PipelineConfig(false, true, false, "none", false, true, 3, false, false))) {
      var response = search(request(10, null, null, false, single), null);
      assertEquals(List.of("tail", "other", "winner"), ids(response));
      assertNull(ce(response).detail());
    }
    var sorted = search(request(10, null, "modified_desc", false, pipeline(false, false)), null);
    assertEquals(List.of("winner", "other", "tail"), ids(sorted));
    assertNull(ce(sorted).detail());
  }

  @Test
  void perSourceLocalRanksNeverEstablishGlobalAgreementEvenWithOneWinner() {
    when(client.search(any(SearchRequest.class), any())).thenReturn(SearchResponse.newBuilder()
        .putFacets("meta_source", io.justsearch.ipc.FacetCounts.newBuilder()
            .putCounts("financial times", 3).putCounts("sky news", 2).build()).build());
    when(perSource.execute(any(), any(), any(), anyInt(), any())).thenReturn(candidates(
        hit("winner", 1, 1), hit("other", 2, 2), hit("tail", 3, 3)));
    var request = new KnowledgeSearchRequest("compare financial times sky news", 10, null,
        null, null, List.of(), null, null, null, null, false, false, pipeline(false, true));
    var response = search(request, null);
    verify(perSource).execute(any(), any(), any(), anyInt(), any());
    assertEquals(List.of("tail", "other", "winner"), ids(response));
    assertNull(ce(response).detail());
  }

  @Test
  void expansionUsesOnlyTheSelectedResponsesAgreementAndFailureKeepsBaseAgreement() {
    ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(any(), anyInt(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture("quokkas"));
    var expanded = candidates(hit("winner", 2, 2), hit("other", 1, 1), hit("tail", 3, 3));
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      SearchRequest request = invocation.getArgument(0);
      return request.getQuerySyntax() == SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE
          ? expanded : candidates(hit("winner", 1, 1), hit("other", 2, 2), hit("tail", 3, 3));
    });
    var request = request(10, null, null, false, pipeline(false, true));
    assertEquals(List.of("other", "tail", "winner"), ids(search(request, null)));
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      SearchRequest wire = invocation.getArgument(0);
      if (wire.getQuerySyntax() == SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE) {
        throw new IllegalArgumentException("expanded search failed");
      }
      return candidates(hit("winner", 1, 1), hit("other", 2, 2), hit("tail", 3, 3));
    });
    assertEquals(List.of("winner", "tail", "other"), ids(search(request, null)));
  }

  @Test
  void ceSkippedAndRpcFailureLeaveLambdaMartOrderAndNoMarker() {
    RerankerService ltr = mock(RerankerService.class);
    when(ltr.isLoaded()).thenReturn(true);
    when(ltr.rerank(any(), any(), any(), anyInt())).thenReturn(List.of(1, 2, 0));
    when(client.rerank(anyString(), anyList(), anyLong(), any())).thenReturn(
        RerankResponse.newBuilder().setSkipped(true).setSkipReason("MODEL_NOT_LOADED").build());
    var request = request(10, null, null, false, pipeline(true, false));
    var skipped = search(request, ltr);
    assertEquals(List.of("other", "tail", "winner"), ids(skipped));
    assertEquals(SearchTrace.StageStatus.SKIPPED, ce(skipped).status());
    assertNull(ce(skipped).detail());
    when(client.rerank(anyString(), anyList(), anyLong(), any()))
        .thenThrow(new IllegalStateException("rpc unavailable"));
    var failure = search(request, ltr);
    assertEquals(List.of("other", "tail", "winner"), ids(failure));
    assertEquals("RPC_FAILED", ce(failure).reason());
    assertNull(ce(failure).detail());
    when(client.requestDeadlineMs()).thenReturn(100L);
    var deadline = search(request, ltr);
    assertEquals(List.of("other", "tail", "winner"), ids(deadline));
    assertEquals("SEARCH_DEADLINE_EXCEEDED", ce(deadline).reason());
    assertNull(ce(deadline).detail());
  }

  @Test
  void disabledAndArbitrationSkippedCeDoNotProtectAgreementBelowFirst() {
    when(client.search(any(SearchRequest.class), any())).thenReturn(candidates(
        hit("other", 2, 2), hit("winner", 1, 1), hit("tail", 3, 3)));
    configure(Map.of("justsearch.rerank.enabled", "false"));
    var disabled = search(request(10, null, null, false, pipeline(false, false)), null);
    assertEquals(List.of("other", "winner", "tail"), ids(disabled));
    assertNull(ce(disabled).detail());
    configure(Map.of("justsearch.rerank.judge_arbitration_enabled", "true",
        "justsearch.rerank.judge_arbitration_skip_enabled", "true"));
    var skipped = search(request(10, null, null, false, pipeline(false, false)), null);
    assertEquals(List.of("other", "winner", "tail"), ids(skipped));
    assertEquals("FUSION_CONFIDENT", ce(skipped).reason());
    assertNull(ce(skipped).detail());
    verify(client, never()).rerank(anyString(), anyList(), anyLong(), any());
  }

  @Test
  void defaultAutoAndBlankCursorUseAgreementWithoutAnExtraFlag() {
    for (String cursor : List.of("", "   ")) {
      var response = search(request(10, cursor, null, false, null), null);
      assertEquals(List.of("winner", "tail", "other"), ids(response));
      assertEquals("keyword_dense_top1_promoted", ce(response).detail());
    }
  }

  @Test
  void executedCeWithExistingJudgeFlagsStillPreservesAgreement() {
    configure(Map.of("justsearch.rerank.judge_blend_enabled", "true",
        "justsearch.rerank.judge_blend_alpha", "0",
        "justsearch.rerank.judge_arbitration_enabled", "true"));
    var response = search(request(10, null, null, false, pipeline(false, false)), null);
    assertEquals(List.of("winner", "tail", "other"), ids(response));
    assertEquals(SearchTrace.StageStatus.EXECUTED, ce(response).status());
    assertEquals("keyword_dense_top1_promoted", ce(response).detail());
  }

  @Test
  void degradedExpansionRetainsBaseAgreementRatherThanTheDiscardedResponse() {
    ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(any(), anyInt(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture("quokkas"));
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      SearchRequest request = invocation.getArgument(0);
      if (request.getQuerySyntax() == SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE) {
        return candidates(hit("winner", 2, 2), hit("other", 1, 1), hit("tail", 3, 3))
            .toBuilder().setSearchTrace(io.justsearch.ipc.SearchTrace.newBuilder()
                .setDegradation(io.justsearch.ipc.TraceDegradation.newBuilder()
                    .setHybridFallbackReason("SEMANTIC_BUDGET_EXCEEDED"))).build();
      }
      return candidates(hit("winner", 1, 1), hit("other", 2, 2), hit("tail", 3, 3));
    });
    var response = search(request(10, null, null, false, pipeline(false, true)), null);
    assertEquals(List.of("winner", "tail", "other"), ids(response));
    assertEquals("TIMEOUT", response.searchTrace().stages().stream()
        .filter(stage -> stage.id() == SearchTrace.StageId.EXPANSION)
        .findFirst().orElseThrow().reason());
  }

  private void configure(Map<String, String> overrides) {
    Map<String, String> entries = new HashMap<>(Map.of(
        "justsearch.models.dir", tempDir.resolve("models").toString(),
        "justsearch.search.query_classification.enabled", "false",
        "justsearch.rerank.enabled", "true",
        "justsearch.rerank.model_path", tempDir.resolve("reranker").toString(),
        "justsearch.rerank.min_hits", "1"));
    entries.putAll(overrides);
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(entries)));
  }

  @SuppressWarnings("unchecked")
  private KnowledgeSearchResponse search(KnowledgeSearchRequest request, RerankerService ltr) {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.isReady()).thenReturn(true);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    return new KnowledgeSearchEngine(bootstrap, perSource, ai, ltr, ConfigStore.global())
        .search(request, TestEngineContexts.internal());
  }

  private static PipelineConfig pipeline(boolean ltr, boolean expansion) {
    return new PipelineConfig(true, true, false, "rrf", ltr, true, 3, expansion, false);
  }

  private static KnowledgeSearchRequest request(
      int limit, String cursor, String sort, boolean detail, PipelineConfig pipeline) {
    return new KnowledgeSearchRequest("quokka", limit, null, sort, cursor, List.of(),
        null, null, null, null, false, detail, pipeline);
  }

  private static SearchResult hit(String id, Integer sparseRank, Integer denseRank) {
    var result = SearchResult.newBuilder().setId(id)
        .setScore("winner".equals(id) ? 11f : "other".equals(id) ? 22f : 33f);
    if (sparseRank != null) result.addTrace(HitStage.newBuilder()
        .setId("sparse-retrieval").setRank(sparseRank).setScore(7f));
    if (denseRank != null) result.addTrace(HitStage.newBuilder()
        .setId("dense-retrieval").setRank(denseRank).setScore(0.4f));
    return result.build();
  }

  private static SearchResponse candidates(SearchResult... hits) {
    return SearchResponse.newBuilder().addAllResults(List.of(hits)).setTotalHits(hits.length).build();
  }

  private static RerankResponse contraryCe() {
    return RerankResponse.newBuilder().addAllSortedIndices(List.of(2, 1, 0))
        .addAllScores(List.of(-3f, 5f, 8f)).build();
  }

  private static List<String> ids(KnowledgeSearchResponse response) {
    return response.results().stream().map(KnowledgeSearchResponse.Hit::id).toList();
  }

  private static SearchTrace.TraceStage ce(KnowledgeSearchResponse response) {
    return response.searchTrace().stages().stream()
        .filter(s -> s.id() == SearchTrace.StageId.CROSS_ENCODER).findFirst().orElseThrow();
  }
}
