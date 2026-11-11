/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static io.justsearch.app.services.worker.KnowledgeSearchEngineExpansionBudgetTest.engineOver;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class KnowledgeSearchEngineOpeningFallbackTest {
  @TempDir Path tempDir;
  private ConfigStore previousConfig;

  @BeforeEach
  void configureReranker() throws Exception {
    Path reranker = Files.createDirectories(tempDir.resolve("reranker"));
    Files.writeString(reranker.resolve("model.onnx"), "stand-in");
    Files.writeString(reranker.resolve("tokenizer.json"), "{}");
    previousConfig = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", tempDir.resolve("models").toString(),
        "justsearch.search.query_classification.enabled", "false",
        "justsearch.rerank.enabled", "true",
        "justsearch.rerank.model_path", reranker.toString(),
        "justsearch.rerank.min_hits", "1"))));
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4, 6})
  void incompleteOriginalOrOpeningScoresKeepTheWholePreRerankOrder(int scoreCount) {
    var client = client();
    when(client.rerankSearch(anyString(), anyList(), anyLong(), any())).thenReturn(
        RerankResponse.newBuilder()
            .addAllScores(List.of(0f, 1f, 2f, 3f, 4f, 5f).subList(0, scoreCount))
            .addAllSortedIndices(List.of(3, 2, 1, 0)).build());

    var response = engineOver(client).search(request(), TestEngineContexts.internal());

    assertEquals(List.of("doc0", "doc1", "doc2", "doc3"), ids(response));
    assertTrue(response.results().stream().allMatch(hit -> hit.trace() == null
        || hit.trace().stream().noneMatch(stage -> stage.id().wireId().equals("cross-encoder"))));
    assertTrue(response.results().stream().noneMatch(hit -> hit.fields().containsKey("parent_opening")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "skipped", "unavailable", "timeout"})
  void openingPassagesPreserveFallbackForUnavailableOrSkippedRerankers(String outcome) {
    var client = client();
    when(client.rerankSearch(anyString(), anyList(), anyLong(), any())).thenAnswer(call -> {
      List<String> passages = call.getArgument(1);
      assertEquals(7, passages.size());
      return switch (outcome) {
        case "null" -> null;
        case "skipped" -> RerankResponse.newBuilder().setSkipped(true)
            .setSkipReason("INFERENCE_FAILED").addScores(10f).addSortedIndices(3).build();
        case "unavailable" -> throw new io.justsearch.app.api.knowledge.KnowledgeClientException(
            io.justsearch.app.api.knowledge.KnowledgeClientException.Status.UNAVAILABLE, "absent", null);
        case "timeout" -> throw new io.justsearch.app.api.knowledge.KnowledgeClientException(
            io.justsearch.app.api.knowledge.KnowledgeClientException.Status.DEADLINE_EXCEEDED,
            "rerank budget spent", null);
        default -> throw new AssertionError(outcome);
      };
    });

    var response = engineOver(client).search(request(), TestEngineContexts.internal());

    verify(client).rerankSearch(anyString(), anyList(), anyLong(), any());
    assertEquals(List.of("doc0", "doc1", "doc2", "doc3"), ids(response));
    assertTrue(response.results().stream().noneMatch(hit -> hit.fields().containsKey("parent_opening")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"search", "authorized-search", "session", "authorized-session", "agent"})
  void leadingThreeOpeningsCompeteWithoutLeakingThroughPublicSearchExits(String exit) {
    var client = client();
    when(client.rerankSearch(anyString(), anyList(), anyLong(), any())).thenAnswer(call -> {
      List<String> passages = call.getArgument(1);
      assertEquals(List.of("T chunk0", "T chunk1", "T chunk2", "T chunk3",
          "T opening0", "T opening1", "T opening2"), passages);
      return RerankResponse.newBuilder().addAllScores(List.of(4f, 3f, 2f, 1f, 0f, 0f, 9f))
          .addAllSortedIndices(List.of(6, 0, 1, 2, 3, 4, 5)).build();
    });

    var server = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(server.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(server.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(call ->
        ((java.util.function.Function<KnowledgeClient, ?>) call.getArgument(0)).apply(client));
    when(client.search(any(SearchRequest.class), eq(true), any())).thenAnswer(call ->
        client.search((SearchRequest) call.getArgument(0), call.getArgument(2)));
    var adapter = new KnowledgeHttpApiAdapter(server, mock(SearchPerSourceExecutor.class));
    var response = switch (exit) {
      case "search" -> adapter.search(request(), TestEngineContexts.internal());
      case "authorized-search" -> adapter.search(request(), true, TestEngineContexts.internal());
      case "session", "authorized-session" -> {
        try (var session = exit.equals("authorized-session")
            ? adapter.openSearch(request(), true, TestEngineContexts.internal())
            : adapter.openSearch(request(), TestEngineContexts.internal())) {
          yield session.response();
        }
      }
      case "agent" -> {
        var captured = new java.util.concurrent.atomic.AtomicReference<KnowledgeSearchResponse>();
        var tool = new io.justsearch.agent.tools.SearchTool((req, context) -> {
          var found = adapter.search(req, context);
          captured.set(found);
          return found;
        });
        var result = tool.execute("""
            {"query":"quokka","limit":10,"pipeline":{"sparseEnabled":true,"denseEnabled":true,
              "fusionAlgorithm":"rrf","crossEncoderEnabled":true}}
            """, TestEngineContexts.internal());
        assertTrue(result.success(), result.message());
        assertFalse(result.toString().contains("parent_opening"));
        assertFalse(result.toString().contains("opening2"));
        yield captured.get();
      }
      default -> throw new AssertionError(exit);
    };

    verify(client).rerankSearch(anyString(), anyList(), anyLong(), any());
    assertEquals(List.of("doc2", "doc0", "doc1", "doc3"), ids(response));
    assertTrue(response.results().stream().noneMatch(hit -> hit.fields().containsKey("parent_opening")));
    System.out.println("Head search seam with Worker/reranker doubles (" + exit + "): "
        + response.tookMs() + " ms");
  }

  private KnowledgeClient client() {
    var client = mock(KnowledgeClient.class);
    when(client.requestDeadlineMs()).thenReturn(10_000L);
    var hits = SearchResponse.newBuilder();
    for (int i = 0; i < 4; i++) {
      hits.addResults(SearchResult.newBuilder().setId("doc" + i).setScore(4 - i)
          .putFields("title", "T").putFields("chunk_index", "1")
          .putFields("content_preview", "chunk" + i).putFields("parent_opening", "opening" + i));
    }
    when(client.search(any(SearchRequest.class), any())).thenReturn(hits.build());
    return client;
  }

  private static List<String> ids(KnowledgeSearchResponse response) {
    return response.results().stream().map(KnowledgeSearchResponse.Hit::id).toList();
  }

  private static KnowledgeSearchRequest request() {
    return new KnowledgeSearchRequest("quokka", 10, null, null, null, List.of(), null, null, null,
        null, false, true,
        new PipelineConfig(true, true, false, "rrf", false, true, 0, false, false));
  }
}
