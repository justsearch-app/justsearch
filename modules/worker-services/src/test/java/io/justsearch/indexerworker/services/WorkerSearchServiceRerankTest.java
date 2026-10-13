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

import io.justsearch.core.execution.InferenceRequest;
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

  @Test
  void rerankPropagatesAdmittedDeadlineUrgencyAndLiveCancellation() {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var none = CallContext.none();
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    var call = new CallContext(null, null, cancelled::get, none.engineContext(),
        none.provenance(), none.childLifetime(), deadline);
    var reranker = mock(CrossEncoderReranker.class);
    when(reranker.rerank(anyString(), anyList(), anyLong(), any())).thenAnswer(invocation -> {
      var authority = (InferenceRequest) invocation.getArgument(3);
      assertEquals(deadline, authority.deadlineNanos());
      assertEquals(InferenceRequest.Urgency.BACKGROUND,
          authority.urgency());
      assertFalse(authority.cancellationRequested().getAsBoolean());
      cancelled.set(true);
      org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,
          authority::remainingNanos);
      throw new java.util.concurrent.CancellationException("admitted rerank cancelled");
    });
    service.setSearchReranker(reranker);
    var request = RerankRequest.newBuilder().setQuery("query").addDocumentTexts("doc")
        .setDeadlineMs(30_000).build();
    org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,
        () -> service.rerank(request, call));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void nativeAcquisitionDeadlineKeepsDeadlineStatus(boolean callerCheck) {
    var reranker = mock(CrossEncoderReranker.class);
    when(reranker.rerank(anyString(), anyList(), anyLong(), any())).thenThrow(
        callerCheck
            ? new InferenceRequest.DeadlineExceededException("caller deadline")
            : new io.justsearch.ort.SessionAcquireDeadlineExceededException("native deadline"));
    service.setSearchReranker(reranker);
    var request = RerankRequest.newBuilder().setQuery("query").addDocumentTexts("doc").build();
    var failure = org.junit.jupiter.api.Assertions.assertThrows(WorkerServiceException.class,
        () -> service.rerank(request, CallContext.none()));
    assertEquals(WorkerServiceException.Status.DEADLINE_EXCEEDED, failure.status());
  }

  @Test
  void realRerankerPassesLiveCancellationAndEarlierDeadlineToNativeAcquisition() throws Exception {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var none = CallContext.none();
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    var call = new CallContext(null, null, cancelled::get, none.engineContext(),
        none.provenance(), none.childLifetime(), deadline);
    var sessions = mock(io.justsearch.ort.SessionHandle.class);
    when(sessions.environment()).thenReturn(ai.onnxruntime.OrtEnvironment.getEnvironment());
    var tokenizer = mock(io.justsearch.reranker.RerankerTokenizer.class);
    when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(
        new io.justsearch.reranker.RerankerTokenizer.EncodedBatch(
            new long[][] {{1}}, new long[][] {{1}}, new long[][] {{0}}, 1, 1, 0, 1));
    when(sessions.acquire(any())).thenAnswer(invocation -> {
      var authority = (io.justsearch.ort.SessionAcquisitionRequest) invocation.getArgument(0);
      assertTrue(authority.deadlineNanos() < deadline, "native wait reserves response time");
      assertTrue(authority.deadlineNanos() > deadline - java.util.concurrent.TimeUnit.SECONDS.toNanos(1),
          "native wait retains the caller's useful budget");
      assertEquals(io.justsearch.ort.SessionAcquisitionRequest.Urgency.BACKGROUND,
          authority.urgency());
      assertFalse(authority.cancellationRequested().getAsBoolean());
      cancelled.set(true);
      authority.remainingNanos();
      throw new AssertionError("native acquisition ignored live cancellation");
    });
    service.setSearchReranker(new CrossEncoderReranker(sessions,
        new io.justsearch.reranker.RerankerShape(8, false), tokenizer));
    var request = RerankRequest.newBuilder().setQuery("query").addDocumentTexts("doc")
        .setDeadlineMs(30_000).build();
    org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,
        () -> service.rerank(request, call));
    org.mockito.Mockito.verify(sessions).acquire(any());
  }

  @Test
  void aRunSlowerThanItsBudgetKeepsScoresInsideTheSearchDeadline() throws Exception {
    var nativeSession = mock(ai.onnxruntime.OrtSession.class);
    var output = mock(ai.onnxruntime.OnnxTensor.class);
    when(output.getValue()).thenReturn(new float[] {0.3f, 0.9f});
    var result = mock(ai.onnxruntime.OrtSession.Result.class);
    when(result.get(0)).thenReturn(output);
    when(nativeSession.run(org.mockito.ArgumentMatchers.anyMap())).thenAnswer(invocation -> {
      new java.util.concurrent.CountDownLatch(1).await(450, java.util.concurrent.TimeUnit.MILLISECONDS);
      return result;
    });
    try (var handle = WorkerSearchServiceSemanticBudgetTest.gpuHandle("slow-rerank", 0, nativeSession)) {
      service.setSearchReranker(new CrossEncoderReranker(handle,
          new io.justsearch.reranker.RerankerShape(8, false), fixedTokenizer(2)));
      var response = service.rerank(RerankRequest.newBuilder().setQuery("query")
          .addAllDocumentTexts(List.of("first", "second")).setDeadlineMs(200).build(),
          rerankCall(java.util.concurrent.TimeUnit.SECONDS.toNanos(5)));
      assertEquals("", response.getSkipReason(), "the pre-inference budget is not a native run cutoff");
      assertFalse(response.getSkipped());
      assertEquals(List.of(0.3f, 0.9f), response.getScoresList());
      assertEquals(List.of(1, 0), response.getSortedIndicesList());
    }
  }

  @Test
  void acquisitionExpiryNamesTheResponseDeadlineWhenItBoundsTheWait() throws Exception {
    var sessions = mock(io.justsearch.ort.SessionHandle.class);
    when(sessions.environment()).thenReturn(ai.onnxruntime.OrtEnvironment.getEnvironment());
    when(sessions.acquire(any())).thenAnswer(invocation -> {
      var authority = (io.justsearch.ort.SessionAcquisitionRequest) invocation.getArgument(0);
      java.util.concurrent.locks.LockSupport.parkNanos(Math.max(0, authority.deadlineNanos() - System.nanoTime())
          + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
      throw new io.justsearch.ort.SessionAcquireDeadlineExceededException("fixture acquisition expired",
          io.justsearch.ort.SessionAcquireDeadlineExceededException.Phase.GATE);
    });
    service.setSearchReranker(new CrossEncoderReranker(sessions,
        new io.justsearch.reranker.RerankerShape(8, false), fixedTokenizer(1)));
    var response = service.rerank(RerankRequest.newBuilder().setQuery("query")
        .addDocumentTexts("document").setDeadlineMs(2000).build(),
        rerankCall(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2050)));
    assertEquals("SEARCH_DEADLINE_EXCEEDED", response.getSkipReason(),
        "the response margin, rather than the rerank budget, bounded acquisition");
    assertTrue(response.getSkipped());
  }

  private CallContext rerankCall(long remainingNanos) {
    var none = CallContext.none();
    return new CallContext(null, null, () -> false, none.engineContext(),
        none.provenance(), none.childLifetime(), System.nanoTime() + remainingNanos);
  }

  @Test
  void nativeEntryAfterTheAcquireBudgetExpiresKeepsItsBudgetReason() throws Exception {
    var sessions = mock(io.justsearch.ort.SessionHandle.class);
    when(sessions.environment()).thenReturn(ai.onnxruntime.OrtEnvironment.getEnvironment());
    when(sessions.acquire(any())).thenAnswer(invocation -> {
      var authority = (io.justsearch.ort.SessionAcquisitionRequest) invocation.getArgument(0);
      java.util.concurrent.locks.LockSupport.parkNanos(Math.max(0, authority.deadlineNanos() - System.nanoTime())
          + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
      return mock(io.justsearch.ort.SessionHandle.Lease.class);
    });
    service.setSearchReranker(new CrossEncoderReranker(sessions,
        new io.justsearch.reranker.RerankerShape(8, false), fixedTokenizer(1)));
    var response = service.rerank(RerankRequest.newBuilder().setQuery("query")
        .addDocumentTexts("document").setDeadlineMs(200).build(),
        rerankCall(java.util.concurrent.TimeUnit.SECONDS.toNanos(5)));
    assertEquals("DEADLINE_EXCEEDED", response.getSkipReason(),
        "the acquisition budget expired before inference, while the search still had time");
    assertTrue(response.getSkipped());
  }

  private static io.justsearch.reranker.RerankerTokenizer fixedTokenizer(int size) {
    var tokenizer = mock(io.justsearch.reranker.RerankerTokenizer.class);
    var rows = new long[size][1];
    when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(
        new io.justsearch.reranker.RerankerTokenizer.EncodedBatch(rows, rows, rows, size, 1, 0, size));
    return tokenizer;
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
      when(mockReranker.rerank(anyString(), anyList(), anyLong(), any()))
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
      when(mockReranker.rerank(anyString(), anyList(), anyLong(), any()))
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
