/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.*;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.ipc.RerankRequest;
import io.justsearch.ort.*;
import io.justsearch.reranker.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Request acquisition uses the real native owner; construction and inference are CPU-safe mocks. */
final class WorkerRerankSessionCreationTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path dir;

  @org.junit.jupiter.params.ParameterizedTest(name = "requestRerankDoesNotRetryGpuOrRefreshItsFailure({0})")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void requestRerankDoesNotRetryGpuOrRefreshItsFailure(boolean creationFlag) throws Exception {
    var cpu = scoredSession();
    try (var _ = mockStatic(OrtCudaHelper.class);
        var _ = mockConstruction(OrtSession.SessionOptions.class);
        var _ = mockConstruction(ai.onnxruntime.providers.OrtCUDAProviderOptions.class);
        var cache = mockStatic(OnnxSessionCache.class);
        var handle = failedGpuHandle(cpu);
        var index = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(dir)
            .withExecutorRegistrations(testLuceneExecutors()).open()) {
      cache.when(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()))
          .thenThrow(new OrtException("controlled GPU creation failure"));
      var acquired = new AtomicReference<SessionAcquisitionRequest>();
      doAnswer(call -> { acquired.set(call.getArgument(0)); return call.callRealMethod(); })
          .when(handle).acquire(any());
      var service = new WorkerSearchService(index);
      service.setSearchReranker(new CrossEncoderReranker(handle, new RerankerShape(8, false), tokenizer()));
      var response = assertDoesNotThrow(() -> service.rerank(request(), CallContext.none()),
          "a request must use ready sessions or skip while prewarm creates them");
      if (creationFlag) assertFalse(acquired.get().mayCreateGpuSession(), "rerank request must not create sessions");
      else assertEquals(1L, field(handle, "gpuFailedAtMs"), "request must not consume the prewarm retry");
      assertFalse(response.getSkipped());
      assertEquals(List.of(0.8f), response.getScoresList());
      cache.verifyNoInteractions();
    }
  }

  @Test
  void coldRequestRerankSkipsWithLoadingWithoutCreatingAnySession() throws Exception {
    try (var _ = mockStatic(OrtCudaHelper.class);
        var _ = mockConstruction(OrtSession.SessionOptions.class);
        var _ = mockConstruction(ai.onnxruntime.providers.OrtCUDAProviderOptions.class);
        var cache = mockStatic(OnnxSessionCache.class);
        var handle = failedGpuHandle(null);
        var index = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(dir)
            .withExecutorRegistrations(testLuceneExecutors()).open()) {
      cache.when(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()))
          .thenThrow(new OrtException("controlled GPU creation failure"));
      cache.when(() -> OnnxSessionCache.createCachedSession(any(), any(), any(), any()))
          .thenThrow(new OrtException("controlled CPU creation failure"));
      var service = new WorkerSearchService(index);
      service.setSearchReranker(new CrossEncoderReranker(handle, new RerankerShape(8, false), tokenizer()));
      var response = assertDoesNotThrow(() -> service.rerank(request(), CallContext.none()),
          "a request must use ready sessions or skip while prewarm creates them");
      assertTrue(response.getSkipped());
      assertEquals("MODEL_LOADING", response.getSkipReason(), "cold sessions are loading, not failed inference or expired budgets");
      cache.verifyNoInteractions();
    }
  }

  @Test
  void askCitationAcquisitionDoesNotCreateOrRetrySessions() throws Exception {
    var cpu = scoredSession();
    try (var _ = mockStatic(OrtCudaHelper.class);
        var _ = mockConstruction(OrtSession.SessionOptions.class);
        var _ = mockConstruction(ai.onnxruntime.providers.OrtCUDAProviderOptions.class);
        var cache = mockStatic(OnnxSessionCache.class);
        var handle = failedGpuHandle(cpu);
        var index = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(dir)
            .withExecutorRegistrations(testLuceneExecutors()).open()) {
      cache.when(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()))
          .thenThrow(new OrtException("controlled GPU creation failure"));
      var acquired = new AtomicReference<SessionAcquisitionRequest>();
      doAnswer(call -> { acquired.set(call.getArgument(0)); return call.callRealMethod(); })
          .when(handle).acquire(any());
      var service = new WorkerSearchService(index);
      service.setCitationScorer(new CitationScorer(handle, new RerankerShape(8, false), tokenizer()));
      service.matchCitations(io.justsearch.ipc.MatchCitationsRequest.newBuilder()
          .setAnswerText("A sufficiently long answer sentence.").addChunkDocIds("document")
          .addChunkIndices(0).addPassageTexts("A sufficiently long answer sentence.").build(), CallContext.none());
      assertNotNull(acquired.get(), "the real citation scorer must acquire its session");
      assertFalse(acquired.get().mayCreateGpuSession(), "Ask citation matching must not create sessions");
      assertEquals(1L, field(handle, "gpuFailedAtMs"));
      cache.verifyNoInteractions();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "shortDeadlineFallbackQueriesStillCannotCreateSessions({0})")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void shortDeadlineFallbackQueriesStillCannotCreateSessions(boolean retrieval) throws Exception {
    var saved = new java.util.HashMap<String, String>();
    try (var index = WorkerSearchServiceRagBudgetTest.openIndex(saved, testLuceneExecutors(), "position", "Hello world");
        var realHandle = WorkerSearchServiceSemanticBudgetTest.gpuHandle("short-deadline")) {
      var handle = spy(realHandle);
      var acquired = new AtomicReference<SessionAcquisitionRequest>();
      doAnswer(call -> { acquired.set(call.getArgument(0)); return call.callRealMethod(); })
          .when(handle).acquire(any());
      var service = new WorkerSearchService(index,
          new WorkerSearchServiceSemanticBudgetTest.HandleBackedEmbeddings(handle));
      service.warmUpSearchPath();
      var call = WorkerSearchServiceSemanticBudgetTest.foreground(java.time.Duration.ofMillis(300));
      if (retrieval) service.retrieveContext(WorkerSearchServiceRagBudgetTest.question("Hello"), call);
      else service.search(WorkerSearchServiceSemanticBudgetTest.query(io.justsearch.ipc.SearchMode.SEARCH_MODE_HYBRID), call);
      assertNotNull(acquired.get(), "the query must exercise native session selection");
      assertFalse(acquired.get().mayCreateGpuSession(), "a short fallback-capable query must not revert to session creation");
    } finally {
      WorkerSearchServiceRagBudgetTest.restoreProperties(saved);
    }
  }

  private static RerankRequest request() {
    return RerankRequest.newBuilder().setQuery("question").addDocumentTexts("document").setDeadlineMs(30_000).build();
  }

  private static RerankerTokenizer tokenizer() {
    var tokenizer = mock(RerankerTokenizer.class);
    var batch = new RerankerTokenizer.EncodedBatch(new long[][] {{1}}, new long[][] {{1}}, new long[][] {{0}}, 1, 1, 0, 1);
    when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(batch);
    when(tokenizer.encodePairsStrict(anyString(), any(String[].class))).thenReturn(batch);
    return tokenizer;
  }

  private static OrtSession scoredSession() throws Exception {
    var session = mock(OrtSession.class);
    var result = mock(OrtSession.Result.class);
    var tensor = mock(OnnxTensor.class);
    when(tensor.getValue()).thenReturn(new float[] {0.8f});
    when(result.get(0)).thenReturn(tensor);
    when(session.run(anyMap())).thenReturn(result);
    return session;
  }

  private static NativeSessionHandle failedGpuHandle(OrtSession cpu) throws Exception {
    var model = Path.of("missing", "model.onnx");
    var policy = ModelSessionPolicy.forFallback(new GpuSessionConfig(0, 512L * 1024 * 1024), null, true, true, 60_000L);
    var handle = spy((NativeSessionHandle) OrtSessionAssembler.buildManager(EncoderRole.RERANKER.consumerName(),
        new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(model, model)), () -> true));
    set(handle, "gpuSessionAttempted", true); set(handle, "gpuAvailable", false);
    set(handle, "gpuFailedAtMs", 1L); set(handle, "cpuSession", cpu);
    return handle;
  }

  private static Object field(Object owner, String name) throws Exception {
    var field = NativeSessionHandle.class.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
  }
  private static void set(Object owner, String name, Object value) throws Exception {
    var field = NativeSessionHandle.class.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
  }
}
