/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static io.justsearch.app.services.worker.KnowledgeSearchEngineExpansionBudgetTest.engineOver;
import static io.justsearch.app.services.worker.KnowledgeSearchEngineExpansionBudgetTest.hits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.SearchRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec D §1.2 at the head seam, rerank stage: the rerank runs inside the request's deadline
 * {@code D}, ending a respond margin before it. When what is left cannot cover a rerank the head
 * does not ask for one; when the rerank does not return in time the head answers without it. Both
 * keep the fused order and name {@code SEARCH_DEADLINE_EXCEEDED}; a rerank that fails while time
 * is left stays {@code RPC_FAILED}.
 */
final class KnowledgeSearchEngineRerankDeadlineTest {

  /** The head's respond margin after the rerank stage ({@code RERANK_MARGIN_MS}). */
  private static final long MARGIN_MS = 500L;

  @TempDir Path tempDir;
  private ConfigStore previousConfig;

  @BeforeEach
  void installConfig() throws Exception {
    Path reranker = Files.createDirectories(tempDir.resolve("reranker"));
    Files.writeString(reranker.resolve("model.onnx"), "stand-in");
    Files.writeString(reranker.resolve("tokenizer.json"), "{}");
    previousConfig = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", Files.createDirectories(tempDir.resolve("models")).toString(),
        "justsearch.search.query_classification.enabled", "false",
        "justsearch.rerank.enabled", "true",
        "justsearch.rerank.model_path", reranker.toString(),
        "justsearch.rerank.min_hits", "1"))));
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @Test
  void aRerankRunsInsideTheSearchDeadlineLessTheRespondMargin() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.requestDeadlineMs()).thenReturn(4_000L);
    when(client.search(any(SearchRequest.class), any())).thenReturn(hits("/base.txt"));
    long[] rerankBound = new long[1];
    when(client.rerank(anyString(), anyList(), anyLong(), any())).thenAnswer(invocation -> {
      rerankBound[0] = HeadSearchDeadline.current().orElse(0);
      return io.justsearch.ipc.RerankResponse.newBuilder()
          .addSortedIndices(0).addScores(1f).build();
    });

    long before = System.nanoTime();
    KnowledgeSearchResponse response =
        engineOver(client).search(rerankingRequest(), TestEngineContexts.internal());
    long after = System.nanoTime();

    long d0 = before + TimeUnit.MILLISECONDS.toNanos(4_000 - MARGIN_MS);
    long d1 = after + TimeUnit.MILLISECONDS.toNanos(4_000 - MARGIN_MS);
    assertTrue(rerankBound[0] - d0 >= 0 && d1 - rerankBound[0] >= 0,
        "the rerank RPC runs inside D - " + MARGIN_MS + " ms");
    assertEquals(SearchTrace.StageStatus.EXECUTED, crossEncoder(response).status());
  }

  @Test
  void tooLittleOfTheDeadlineLeftSkipsTheRerankWithTheFusedOrder() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.requestDeadlineMs()).thenReturn(1_000L);
    // The base search leaves 1000 - 400 - 500 = 100 ms before the rerank deadline: under the
    // 200 ms a rerank needs.
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      Thread.sleep(400);
      return hits("/base.txt");
    });

    KnowledgeSearchResponse response =
        engineOver(client).search(rerankingRequest(), TestEngineContexts.internal());

    verify(client, never()).rerank(anyString(), anyList(), anyLong(), any());
    assertEquals(List.of("/base.txt"),
        response.results().stream().map(KnowledgeSearchResponse.Hit::id).toList());
    assertEquals(SearchTrace.StageStatus.SKIPPED, crossEncoder(response).status());
    assertEquals("SEARCH_DEADLINE_EXCEEDED", crossEncoder(response).reason());
  }

  @Test
  void aRerankThatOverrunsItsDeadlineIsASearchDeadlineDropNotAnRpcFailure() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.requestDeadlineMs()).thenReturn(1_500L);
    when(client.search(any(SearchRequest.class), any())).thenReturn(hits("/base.txt"));
    // The client releases the caller at the rerank deadline, as the in-process budget does.
    when(client.rerank(anyString(), anyList(), anyLong(), any())).thenAnswer(invocation -> {
      OptionalLong bound = HeadSearchDeadline.current();
      while (System.nanoTime() - bound.getAsLong() < 0) Thread.sleep(5);
      throw new KnowledgeClientException(
          KnowledgeClientException.Status.DEADLINE_EXCEEDED, "rerank budget spent", null);
    });

    long start = System.nanoTime();
    KnowledgeSearchResponse response =
        engineOver(client).search(rerankingRequest(), TestEngineContexts.internal());
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertTrue(elapsedMs <= 1_500L, "answered within D; took " + elapsedMs + " ms");
    assertEquals(List.of("/base.txt"),
        response.results().stream().map(KnowledgeSearchResponse.Hit::id).toList());
    assertEquals("SEARCH_DEADLINE_EXCEEDED", crossEncoder(response).reason());
  }

  @Test
  void aRerankThatFailsWithTimeLeftStaysAnRpcFailure() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.requestDeadlineMs()).thenReturn(10_000L);
    when(client.search(any(SearchRequest.class), any())).thenReturn(hits("/base.txt"));
    when(client.rerank(anyString(), anyList(), anyLong(), any())).thenThrow(
        new KnowledgeClientException(
            KnowledgeClientException.Status.UNAVAILABLE, "worker gone", null));

    KnowledgeSearchResponse response =
        engineOver(client).search(rerankingRequest(), TestEngineContexts.internal());

    assertEquals("RPC_FAILED", crossEncoder(response).reason());
  }

  private static SearchTrace.TraceStage crossEncoder(KnowledgeSearchResponse response) {
    return response.searchTrace().stages().stream()
        .filter(stage -> "cross-encoder".equals(stage.id().wireId()))
        .findFirst().orElseThrow();
  }

  /** Sparse plus dense with the cross-encoder, no expansion. */
  private static KnowledgeSearchRequest rerankingRequest() {
    return new KnowledgeSearchRequest("quokka", 10, null, null, null, List.of(), null, null, null,
        null, false, true,
        new PipelineConfig(true, true, false, "rrf", false, true, 0, false, false));
  }
}
