package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.ipc.RerankRequest;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankSkipCause;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for the Rerank RPC (360: migrated to Worker).
 *
 * <p>Verifies the service method and the MODEL_NOT_LOADED fallback when no reranker is wired.
 * (The gRPC adapter's own framing/status mapping was covered by {@code WorkerServiceCallsTest},
 * deleted with the adapter at item A9;
 * every case here drives {@link WorkerSearchService} directly, as it always did.)
 */
@DisplayName("WorkerSearchService Rerank RPC")
class WorkerSearchServiceRerankTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private WorkerSearchService service;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
    service = new WorkerSearchService(lifecycle);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  private RerankResponse callRerank(
      WorkerSearchService target, String query, List<String> docs, long deadlineMs) {
    RerankRequest request =
        RerankRequest.newBuilder()
            .setQuery(query)
            .addAllDocumentTexts(docs)
            .setDeadlineMs(deadlineMs)
            .build();
    RerankResponse result;
    try {
      result = target.rerank(request, CallContext.none());
    } catch (WorkerServiceException e) {
      return fail("rerank returned error: " + e.getMessage());
    }
    assertNotNull(result, "rerank should return a response");
    return result;
  }

  @Nested
  @DisplayName("without reranker wired")
  class WithoutReranker {

    @Test
    @DisplayName("returns skipped=true with MODEL_NOT_LOADED")
    void returnsModelNotLoaded() {
      RerankResponse resp =
          callRerank(service, "test query", List.of("doc1", "doc2"), 200);

      assertTrue(resp.getSkipped());
      assertEquals("MODEL_NOT_LOADED", resp.getSkipReason());
      assertEquals(0, resp.getSortedIndicesCount());
      assertEquals(0, resp.getScoresCount());
    }
  }

  @Nested
  @DisplayName("with mock reranker wired")
  class WithReranker {

    @BeforeEach
    void wireReranker() {
      CrossEncoderReranker mockReranker = mock(CrossEncoderReranker.class);
      when(mockReranker.rerank(anyString(), anyList(), anyLong()))
          .thenReturn(
              new CrossEncoderReranker.RerankedResult(
                  List.of(1, 0, 2), List.of(0.3f, 0.9f, 0.1f), RerankSkipCause.NONE, 42));
      service.setSearchReranker(mockReranker);
    }

    @Test
    @DisplayName("returns sorted indices and scores from reranker")
    void returnsSortedIndicesAndScores() {
      RerankResponse resp =
          callRerank(service, "test", List.of("doc A", "doc B", "doc C"), 5000);

      assertFalse(resp.getSkipped());
      assertEquals(42, resp.getElapsedMs());
      assertEquals(List.of(1, 0, 2), resp.getSortedIndicesList());
      assertEquals(3, resp.getScoresCount());
      assertEquals(0.3f, resp.getScores(0), 0.001f);
      assertEquals(0.9f, resp.getScores(1), 0.001f);
      assertEquals(0.1f, resp.getScores(2), 0.001f);
    }

  }

  @Nested
  @DisplayName("with skipping reranker")
  class WithSkippingReranker {

    private void wireSkip(RerankSkipCause cause) {
      CrossEncoderReranker mockReranker = mock(CrossEncoderReranker.class);
      when(mockReranker.rerank(anyString(), anyList(), anyLong()))
          .thenReturn(
              new CrossEncoderReranker.RerankedResult(List.of(0, 1), List.of(), cause, 150));
      service.setSearchReranker(mockReranker);
    }

    @Test
    @DisplayName("a tokenize-budget pre-check is DEADLINE_EXCEEDED")
    void tokenizeBudgetIsDeadlineExceeded() {
      wireSkip(RerankSkipCause.TOKENIZE_BUDGET_EXCEEDED);
      RerankResponse resp = callRerank(service, "test", List.of("doc A", "doc B"), 200);

      assertTrue(resp.getSkipped());
      assertEquals("DEADLINE_EXCEEDED", resp.getSkipReason());
      assertEquals(150, resp.getElapsedMs());
      assertEquals(0, resp.getSortedIndicesCount());
    }

    @Test
    @DisplayName("a prep-budget pre-check is DEADLINE_EXCEEDED")
    void prepBudgetIsDeadlineExceeded() {
      wireSkip(RerankSkipCause.PREP_BUDGET_EXCEEDED);
      assertEquals(
          "DEADLINE_EXCEEDED",
          callRerank(service, "test", List.of("doc A", "doc B"), 200).getSkipReason());
    }

    // Register F-054, the defect this splits: the handler stamped DEADLINE_EXCEEDED on ANY
    // reranker skip, so an ONNX Runtime failure (measured: 199/200 of a campaign's "deadline
    // misses" were BFCArena OOM) told every reader to raise a deadline that cannot fix it.
    @Test
    @DisplayName("an inference failure is INFERENCE_FAILED, NOT DEADLINE_EXCEEDED")
    void inferenceFailureIsNotDeadlineExceeded() {
      wireSkip(RerankSkipCause.INFERENCE_FAILED);
      RerankResponse resp = callRerank(service, "test", List.of("doc A", "doc B"), 200);

      assertTrue(resp.getSkipped());
      assertEquals("INFERENCE_FAILED", resp.getSkipReason());
      assertEquals(150, resp.getElapsedMs());
      assertEquals(0, resp.getSortedIndicesCount());
    }
  }
}
