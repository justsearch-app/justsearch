/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.ort.Composition;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** K3/K12: the real admitted reranker path can prepare and run a deferred CPU backup. */
final class RerankerDeferredCpuNativeTest {
  @TempDir Path temp;

  @Test void admittedFirstCpuRouteProducesRealScoresAndReusesSession() throws Exception {
    // Cast input_ids to float logits [N,S]; column one is the relevance score.
    Path model = Files.write(temp.resolve("reranker.onnx"), Base64.getDecoder().decode(
        "CAkSEWRlZmVycmVkLWNwdS10ZXN0OpQBCiQKCWlucHV0X2lkcxIGbG9naXRzIgRDYXN0KgkKAnRvGAGgAQISDXRpbnktcmVyYW5rZXJaHQoJaW5wdXRfaWRzEhAKDggHEgoKAxIBTgoDEgFTWiIKDmF0dGVudGlvbl9tYXNrEhAKDggHEgoKAxIBTgoDEgFTYhoKBmxvZ2l0cxIQCg4IARIKCgMSAU4KAxIBU0ICEBE="));
    var policy = ModelSessionPolicy.forFallback(new GpuSessionConfig(0, 1024 * 1024),
        null, true, true, 60_000);
    var sessions = (NativeSessionHandle) OrtSessionAssembler.buildManager("reranker",
        new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(model, model)), () -> false);
    var tokenizer = mock(RerankerTokenizer.class);
    when(tokenizer.encodePairs(anyString(), any(String[].class))).thenAnswer(call -> {
      int count = ((String[]) call.getArgument(1)).length;
      long[][] ids = new long[count][2];
      long[][] mask = new long[count][2];
      for (int i = 0; i < count; i++) { ids[i][1] = 3 + 2L * i; mask[i] = new long[] {1, 1}; }
      return new RerankerTokenizer.EncodedBatch(ids, mask, new long[count][2], count, 2, 0, 2);
    });
    try (var reranker = new CrossEncoderReranker(sessions, new RerankerShape(2, false), tokenizer)) {
      assertNull(cpuSession(sessions));
      Object first = null;
      for (int i = 0; i < 2; i++) {
        var request = InferenceRequest.within(InferenceRequest.Urgency.FOREGROUND,
            Duration.ofSeconds(5), () -> false).withoutSessionCreation();
        var result = assertDoesNotThrow(() -> reranker.rerank("query " + request.deadlineNanos(),
            List.of("document one", "document two"), 5_000, request));
        assertFalse(result.skipped(), () -> "real CPU rerank skipped: " + result.skipCause());
        assertEquals(List.of(3.0f, 5.0f), result.scores());
        assertEquals(List.of(1, 0), result.sortedIndices());
        if (first == null) first = cpuSession(sessions);
        else assertSame(first, cpuSession(sessions));
      }
      assertTrue(reranker.isQueryWarm());
      assertFalse(sessions.isGpuAvailable());
    }
    verify(tokenizer).close();
  }
  @Test void ordinaryGpuContentionSkipsWithoutPreparingCpuRecourse() throws Exception {
    Path model = temp.resolve("must-not-load.onnx");
    var policy = ModelSessionPolicy.forFallback(new GpuSessionConfig(131, 1024 * 1024),
        null, true, true, 60_000);
    var sessions = (NativeSessionHandle) OrtSessionAssembler.buildManager("reranker",
        new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(model, model)), () -> true);
    var gpu = mock(ai.onnxruntime.OrtSession.class);
    for (String fieldName : new String[] {"gpuSession", "gpuSessionAttempted", "gpuAvailable",
        "gpuGeneration", "gpuWarmGeneration"}) {
      var field = NativeSessionHandle.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(sessions, fieldName.equals("gpuSession") ? gpu
          : fieldName.contains("Generation") ? 1L : Boolean.TRUE);
    }
    var tokenizer = mock(RerankerTokenizer.class);
    when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(
        new RerankerTokenizer.EncodedBatch(new long[][] {{0, 3}},
            new long[][] {{1, 1}}, new long[1][2], 1, 2, 0, 2));
    try (var reranker = new CrossEncoderReranker(sessions, new RerankerShape(2, false), tokenizer);
        var occupied = sessions.acquire(io.justsearch.ort.SessionAcquisitionRequest.within(
            io.justsearch.ort.SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofSeconds(5)))) {
      assertFalse(occupied.isCpu());
      var parent = InferenceRequest.within(InferenceRequest.Urgency.FOREGROUND, Duration.ofSeconds(5), () -> false);
      var admitted = InferenceRequest.narrowed(parent, parent.deadlineNanos(), InferenceRequest.SemanticBudget.REQUIRED);
      var result = reranker.rerank("query", List.of("document"), 500, admitted);
      assertEquals(RerankSkipCause.ACQUIRE_BUDGET_EXCEEDED, result.skipCause());
      assertNull(cpuSession(sessions), "reranker contention must not turn into semantic CPU recourse");
    }
    verify(gpu).close();
    verify(tokenizer).close();
  }

  private static Object cpuSession(NativeSessionHandle sessions) throws Exception {
    var field = NativeSessionHandle.class.getDeclaredField("cpuSession");
    field.setAccessible(true);
    return field.get(sessions);
  }

}
