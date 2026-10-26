/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.Vocabulary;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** K3/K12: a hybrid-style SPLADE request prepares its CPU owner and consumes real sparse output. */
final class SpladeDeferredCpuNativeTest {
  @TempDir Path temp;

  @Test void firstHybridCpuRouteProducesRealSparseOutputAndReusesSession() throws Exception {
    // Identity ids -> output_idx, Cast attention_mask -> output_weights; both [N,S].
    Path model = Files.write(temp.resolve("splade.onnx"), Base64.getDecoder().decode(
        "CAkSEWRlZmVycmVkLWNwdS10ZXN0OuoBCiEKCWlucHV0X2lkcxIKb3V0cHV0X2lkeCIISWRlbnRpdHkKMQoOYXR0ZW50aW9uX21hc2sSDm91dHB1dF93ZWlnaHRzIgRDYXN0KgkKAnRvGAGgAQISC3Rpbnktc3BsYWRlWh0KCWlucHV0X2lkcxIQCg4IBxIKCgMSAU4KAxIBU1oiCg5hdHRlbnRpb25fbWFzaxIQCg4IBxIKCgMSAU4KAxIBU2IeCgpvdXRwdXRfaWR4EhAKDggHEgoKAxIBTgoDEgFTYiIKDm91dHB1dF93ZWlnaHRzEhAKDggBEgoKAxIBTgoDEgFTQgIQEQ=="));
    var policy = ModelSessionPolicy.forFallback(new GpuSessionConfig(0, 1024 * 1024),
        null, true, true, 60_000);
    var sessions = (NativeSessionHandle) OrtSessionAssembler.buildManager("splade",
        new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(model, model)), () -> false);
    var tokenizer = mock(HuggingFaceTokenizer.class);
    var encoding = mock(Encoding.class);
    when(encoding.getIds()).thenReturn(new long[] {200, 201});
    when(encoding.getAttentionMask()).thenReturn(new long[] {1, 1});
    when(encoding.getTypeIds()).thenReturn(new long[2]);
    when(tokenizer.encode(anyString())).thenReturn(encoding);
    var vocabulary = mock(Vocabulary.class);
    when(vocabulary.size()).thenReturn(202L);
    when(vocabulary.getToken(200)).thenReturn("first");
    when(vocabulary.getToken(201)).thenReturn("second");
    var config = new SpladeConfig(true, temp, 2, true, 0, 1024 * 1024, "onnx", "log1p");
    try (var encoder = new SpladeEncoder(sessions,
        new SpladeShape(2, false, SpladeEncoder.OutputFormat.PRESPARSE, null),
        tokenizer, vocabulary, null, config)) {
      assertNull(cpuSession(sessions));
      Object first = null;
      for (int i = 0; i < 2; i++) {
        var parent = InferenceRequest.within(InferenceRequest.Urgency.FOREGROUND,
            Duration.ofSeconds(5), () -> false);
        var request = InferenceRequest.narrowed(parent, parent.deadlineNanos(),
            InferenceRequest.SemanticBudget.FALLBACK);
        assertEquals(Map.of("first", 1.0f, "second", 1.0f),
            assertDoesNotThrow(() -> encoder.encode("uncached sparse query " + request.deadlineNanos(), request)));
        if (first == null) first = cpuSession(sessions);
        else assertSame(first, cpuSession(sessions));
      }
      assertTrue(encoder.isQueryWarm());
      assertFalse(sessions.isGpuAvailable());
    }
    verify(tokenizer).close();
  }
  private static Object cpuSession(NativeSessionHandle sessions) throws Exception {
    var field = NativeSessionHandle.class.getDeclaredField("cpuSession");
    field.setAccessible(true);
    return field.get(sessions);
  }

}
