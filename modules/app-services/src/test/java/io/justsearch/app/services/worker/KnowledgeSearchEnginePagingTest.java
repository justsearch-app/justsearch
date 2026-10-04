/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.SearchSort;
import io.justsearch.reranker.RerankerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class KnowledgeSearchEnginePagingTest {
  @TempDir Path modelsDir;
  private ConfigStore previousConfig;

  @BeforeEach
  void installMetadataOnlyReranker() throws Exception {
    previousConfig = ConfigStore.globalOrNull();
    // Match a hosted checkout: discovery accepts the manifest and tokenizer without ONNX weights.
    Path reranker = Files.createDirectories(modelsDir.resolve("onnx/reranker"));
    Files.writeString(reranker.resolve("model_manifest.json"), "{}");
    Files.writeString(reranker.resolve("tokenizer.json"), "{}");
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", modelsDir.toString(),
        "justsearch.rerank.top_k", "20",
        "justsearch.rerank.min_hits", "1",
        "justsearch.search.query_classification.enabled", "false"))));
    assertTrue(RerankerConfig.fromEnv().isReady(), "metadata triggers candidate overfetch");
    assertEquals(reranker, RerankerConfig.fromEnv().modelPath());
    assertFalse(Files.exists(reranker.resolve("model.onnx")));
  }

  @AfterEach
  void restoreConfiguration() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @ParameterizedTest
  @CsvSource({"5,path_asc", "25,path_asc", "5,path_desc", "25,path_desc"})
  void fieldSortedPagesKeepTheWorkerCursorBoundary(int documentCount, String sort) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));

    List<String> paths = new ArrayList<>(IntStream.range(0, documentCount)
        .mapToObj(i -> String.format("/cursor-%02d.txt", i)).toList());
    if (sort.equals("path_desc")) Collections.reverse(paths);
    List<SearchRequest> sent = new ArrayList<>();
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      SearchRequest request = invocation.getArgument(0);
      sent.add(request);
      // Model the worker's stateless searchAfter contract: cursor after the last returned hit,
      // present only when a lookahead hit exists. The Head must forward that exact boundary.
      int start = request.getCursor().isBlank()
          ? 0 : paths.indexOf(request.getCursor().substring("after:".length())) + 1;
      int end = Math.min(paths.size(), start + request.getLimit());
      var response = SearchResponse.newBuilder().setTotalHits(paths.size());
      for (String path : paths.subList(start, end)) {
        response.addResults(SearchResult.newBuilder().setId(path).setScore(1));
      }
      if (end < paths.size()) response.setNextCursor("after:" + paths.get(end - 1));
      return response.build();
    });

    // A loaded relevance model would reverse the requested path order if allowed to run.
    RerankerService lambdaMart = mock(RerankerService.class);
    when(lambdaMart.isLoaded()).thenReturn(true);
    when(lambdaMart.rerank(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
      int count = invocation.getArgument(3);
      return IntStream.range(0, count).map(i -> count - i - 1).boxed().toList();
    });
    when(client.rerank(any(), any(), anyLong(), any())).thenReturn(
        RerankResponse.newBuilder().setSkipped(true).setSkipReason("MODEL_NOT_LOADED").build());
    var engine = new KnowledgeSearchEngine(bootstrap, mock(SearchPerSourceExecutor.class),
        OnlineAiService.unavailable(), lambdaMart, ConfigStore.global());

    KnowledgeSearchResponse first = engine.search(request(sort, null), TestEngineContexts.internal());
    assertNotNull(first.nextCursor(), "five matches with limit two must emit a cursor");
    assertEquals("after:" + paths.get(1), first.nextCursor());
    assertEquals(paths.subList(0, 2), ids(first));
    KnowledgeSearchResponse second = engine.search(
        request(sort, first.nextCursor()), TestEngineContexts.internal());
    assertEquals(paths.subList(2, 4), ids(second), "advance in path order without skips or repeats");

    // Walk to the final page so both cursor emission and termination are checked.
    List<String> visited = new ArrayList<>(ids(first));
    KnowledgeSearchResponse page = second;
    for (int pages = 0; pages < documentCount; pages++) {
      visited.addAll(ids(page));
      if (page.nextCursor() == null) break;
      page = engine.search(request(sort, page.nextCursor()), TestEngineContexts.internal());
    }
    assertNull(page.nextCursor(), "the last page must terminate pagination");
    assertEquals(paths, visited);
    SearchSort expectedSort = sort.equals("path_asc")
        ? SearchSort.SEARCH_SORT_PATH_ASC : SearchSort.SEARCH_SORT_PATH_DESC;
    for (SearchRequest request : sent) {
      assertEquals(2, request.getLimit(), "worker page size must match the visible page size");
      assertEquals(expectedSort, request.getSort());
    }
    verify(lambdaMart, never()).rerank(any(), any(), any(), anyInt());
    verify(client, never()).rerank(any(), any(), anyLong(), any());
  }

  private static KnowledgeSearchRequest request(String sort, String cursor) {
    // Same request as HttpPagingCursorE2ETest: no explicit mode or pipeline.
    return new KnowledgeSearchRequest("CursorPaging-marker", 2, null, sort, cursor,
        List.of(), null, null, null, null, false, false, null);
  }

  private static List<String> ids(KnowledgeSearchResponse response) {
    return response.results().stream().map(KnowledgeSearchResponse.Hit::id).toList();
  }
}
