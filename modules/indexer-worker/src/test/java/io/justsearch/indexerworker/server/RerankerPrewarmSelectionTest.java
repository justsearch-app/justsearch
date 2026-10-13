/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.*;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.ort.*;
import io.justsearch.reranker.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class RerankerPrewarmSelectionTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @Test
  void prewarmRetriesTheServingRerankerAndTheNextRequestUsesItsGpu() throws Exception {
    var gpu = mock(OrtSession.class);
    var result = mock(OrtSession.Result.class);
    var output = mock(OnnxTensor.class);
    when(output.getValue()).thenReturn(new float[] {0.9f}); when(result.get(0)).thenReturn(output);
    when(gpu.run(anyMap())).thenReturn(result);
    when(gpu.run(anyMap(), any(OrtSession.RunOptions.class))).thenReturn(result);
    var tokenizer = mock(RerankerTokenizer.class);
    when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(
        new RerankerTokenizer.EncodedBatch(new long[][] {{1}}, new long[][] {{1}}, new long[][] {{0}}, 1, 1, 0, 1));
    var model = Path.of("missing", "model.onnx");
    var policy = ModelSessionPolicy.forFallback(new GpuSessionConfig(0, 512L * 1024 * 1024), null, true, true, 60_000L);
    try (var _ = mockStatic(OrtCudaHelper.class);
        var _ = mockConstruction(OrtSession.SessionOptions.class);
        var _ = mockConstruction(ai.onnxruntime.providers.OrtCUDAProviderOptions.class);
        var cache = mockStatic(OnnxSessionCache.class);
        var handle = OrtSessionAssembler.buildManager(EncoderRole.RERANKER.consumerName(),
            new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(model, model)), () -> true);
        var executors = new TestEngineExecutors()) {
      cache.when(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any())).thenReturn(gpu);
      cache.when(() -> OnnxSessionCache.createCachedSession(any(), any(), any(), any())).thenReturn(mock(OrtSession.class));
      set(handle, "gpuSessionAttempted", true); set(handle, "gpuAvailable", false); set(handle, "gpuFailedAtMs", 1L);
      var reranker = new CrossEncoderReranker(handle, new RerankerShape(8, false), tokenizer);
      var surface = new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), null, List.of());
      try (var query = new QueryRoleSet(surface)) {
        query.bindReranker(query.own(reranker));
        var config = TestResolvedConfigHelper.withDefaults();
        var server = new KnowledgeServer(executors, WorkerConfig.load(config), null, ManagedChildRegistry.noop(),
            RecordedIngestionLifecycle.denied(), null, null, config);
        set(server, "initialQueryRoleSet", query);
        var selectionMethod = KnowledgeServer.class.getDeclaredMethod("queryPrewarmSelection"); selectionMethod.setAccessible(true);
        java.util.function.Supplier<QueryEncoderPrewarm.Selection> selection = () -> {
          try { return (QueryEncoderPrewarm.Selection) selectionMethod.invoke(server); }
          catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        };
        try (var held = selection.get()) {
          assertTrue(held.targets().stream().anyMatch(t -> t.role() == EncoderRole.RERANKER),
              "prewarm must include the independently owned serving reranker, even without index encoders");
        }
        var scheduler = mock(java.util.concurrent.ScheduledExecutorService.class);
        when(scheduler.awaitTermination(anyLong(), any())).thenReturn(true);
        try (var runner = new QueryEncoderPrewarm(selection, Duration.ofSeconds(5), scheduler)) {
          var pass = QueryEncoderPrewarm.class.getDeclaredMethod("runPass"); pass.setAccessible(true); pass.invoke(runner);
          assertTrue(handle.isGpuAvailable(), "prewarm must retry GPU creation");
          assertEquals(QueryDevice.GPU, handle.queryDevice(), "the GPU session must be warm before query use");
          try (var index = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
              io.justsearch.configuration.FieldCatalogDef.forChunkTesting(0)).ephemeral()
              .withExecutorRegistrations(testLuceneExecutors()).open()) {
            var service = new io.justsearch.indexerworker.services.WorkerSearchService(index);
            service.setSearchReranker(reranker);
            var scored = service.rerank(io.justsearch.ipc.RerankRequest.newBuilder().setQuery("question")
                .addDocumentTexts("document").setDeadlineMs(30_000).build(),
                io.justsearch.indexerworker.services.CallContext.none());
            assertFalse(scored.getSkipped()); assertEquals("", scored.getSkipReason());
            assertEquals(List.of(0.9f), scored.getScoresList());
          }
          verify(gpu, times(2)).run(anyMap(), any(OrtSession.RunOptions.class));
          cache.verify(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()), times(1));
        }
      }
    }
  }
  @Test
  void prewarmIncludesAnIndependentlyOwnedColdCitationScorer() throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var config = TestResolvedConfigHelper.withDefaults();
      var server = new KnowledgeServer(executors, WorkerConfig.load(config), null, ManagedChildRegistry.noop(),
          RecordedIngestionLifecycle.denied(), null, null, config);
      var surface = new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), null, List.of());
      try (var query = new QueryRoleSet(surface)) {
        query.bindCitation(query.own(mock(CitationScorer.class)));
        set(server, "initialQueryRoleSet", query);
        var selectionMethod = KnowledgeServer.class.getDeclaredMethod("queryPrewarmSelection"); selectionMethod.setAccessible(true);
        try (var selection = (QueryEncoderPrewarm.Selection) selectionMethod.invoke(server)) {
          assertTrue(selection.targets().stream().anyMatch(t -> t.role() == EncoderRole.CITATION),
              "prewarm must create cold citation sessions, rather than Ask requests");
        }
      }
    }
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
  }
}
