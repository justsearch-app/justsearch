/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OrtSession;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.ipc.RerankRequest;
import io.justsearch.ort.*;
import io.justsearch.reranker.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** K3/COLD2 delegated choice: a public admitted rerank may prepare its deferred CPU backup. */
final class WorkerRerankDeferredCpuTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path dir;

  @Test
  void admittedDeferredCpuDemandCreatesOnceAndReusesRealSessionWithoutGpuRetry() throws Exception {
    // Fixed ONNX Cast: INT64 input_ids [batch,1] -> FLOAT logits [batch,1], plus attention_mask input.
    // No provider download, production weights, static constructor mocks or global state mutation.
    var model = Files.write(dir.resolve("reranker.onnx"), Base64.getDecoder().decode(
        "CAk6nQEKJAoJaW5wdXRfaWRzEgZsb2dpdHMiBENhc3QqCQoCdG8YAaABAhINdGlueS1yZXJhbmtlclogCglpbnB1dF9pZHMSEwoRCAcSDQoHEgViYXRjaAoCCAFaJQoOYXR0ZW50aW9uX21hc2sSEwoRCAcSDQoHEgViYXRjaAoCCAFiHQoGbG9naXRzEhMKEQgBEg0KBxIFYmF0Y2gKAggBQgIQEQ=="));
    var policy = ModelSessionPolicy.forFallback(new GpuSessionConfig(0, 512L * 1024 * 1024),
        null, true, true, 60_000L);
    try (var handle = spy((NativeSessionHandle) OrtSessionAssembler.buildManager(
            EncoderRole.RERANKER.consumerName(),
            new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(model, model)), () -> true));
        var index = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(dir.resolve("index"))
            .withExecutorRegistrations(testLuceneExecutors()).open()) {
      set(handle, "gpuSessionAttempted", true);
      set(handle, "gpuAvailable", false);
      set(handle, "gpuFailedAtMs", 1L);
      assertNull(field(handle, "cpuSession"));
      assertFalse(handle.isQueryWarm());
      var generations = new AtomicInteger();
      handle.addGenerationListener(generations::incrementAndGet);
      var acquired = new AtomicReference<SessionAcquisitionRequest>();
      doAnswer(call -> { acquired.set(call.getArgument(0)); return call.callRealMethod(); })
          .when(handle).acquire(any());
      var tokenizer = mock(RerankerTokenizer.class);
      var batch = new RerankerTokenizer.EncodedBatch(new long[][] {{1}}, new long[][] {{1}}, new long[][] {{0}}, 1, 1, 0, 1);
      when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(batch);
      when(tokenizer.encodePairsStrict(anyString(), any(String[].class))).thenReturn(batch);
      var service = new WorkerSearchService(index);
      service.setSearchReranker(new CrossEncoderReranker(handle, new RerankerShape(8, false), tokenizer));
      var request = RerankRequest.newBuilder().setQuery("question").addDocumentTexts("document")
          .setDeadlineMs(30_000).build();
      OrtSession first = null;
      for (int i = 0; i < 2; i++) {
        var response = service.rerank(request, CallContext.none());
        assertFalse(response.getSkipped(), "deferred CPU demand must return actual scores: " + response.getSkipReason());
        assertEquals(java.util.List.of(0), response.getSortedIndicesList());
        assertEquals(1, response.getScoresCount());
        assertTrue(Float.isFinite(response.getScores(0)));
        assertEquals(1.0f, response.getScores(0));
        assertNotNull(acquired.get());
        assertFalse(acquired.get().mayCreateGpuSession());
        var cpu = (OrtSession) field(handle, "cpuSession");
        assertNotNull(cpu);
        if (first == null) first = cpu;
        else assertSame(first, cpu);
        assertEquals(1, generations.get(), "only one CPU session generation may be created");
        assertEquals(0, field(handle, "nativeActionsInProgress"));
        assertNull(field(handle, "gpuSession"));
        assertEquals(1L, field(handle, "gpuFailedAtMs"), "request cannot retry or refresh GPU failure");
      }
      assertTrue(handle.isQueryWarm());
    }
  }

  private static Object field(NativeSessionHandle owner, String name) throws Exception {
    var field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void set(NativeSessionHandle owner, String name, Object value) throws Exception {
    var field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }
}
