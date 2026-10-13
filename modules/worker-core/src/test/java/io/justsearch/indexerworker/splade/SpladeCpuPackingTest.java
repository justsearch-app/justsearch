/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.Vocabulary;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.SessionHandle;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SpladeCpuPackingTest {
  @Test void one512TokenTextReturnsCpuOutputUnderAnInfeasibleGpuCap() throws Exception {
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(lease.isCpu()).thenReturn(true);
    when(sessions.acquire(any())).thenReturn(lease);
    when(sessions.acquireCpu(any())).thenReturn(lease);
    when(sessions.environment()).thenReturn(mock(OrtEnvironment.class));
    when(sessions.status()).thenReturn(OrtCudaStatus.notConfigured());
    var facts = new GpuArenaPlan.Facts(OptionalDouble.of(12_282),
        Map.of(EncoderRole.SPLADE, 474.2), Set.of(ChatModelProfile.STANDARD), Map.of(),
        GpuArenaPlan.ExternalChat.NONE, 1);
    var plan = GpuArenaPlan.resolve(facts, new GpuArenaPlan.Settings(Optional.empty(),
        OptionalInt.empty(), OptionalInt.empty(), Map.of(EncoderRole.SPLADE, 100.0), false));
    when(sessions.arenaPlan()).thenReturn(Optional.of(plan));
    var tokenizer = mock(HuggingFaceTokenizer.class);
    var encoding = mock(Encoding.class);
    when(encoding.getIds()).thenReturn(new long[512]);
    when(encoding.getAttentionMask()).thenReturn(new long[512]);
    when(encoding.getTypeIds()).thenReturn(new long[512]);
    when(tokenizer.batchEncode(anyList())).thenReturn(new Encoding[] {encoding});
    var vocabulary = mock(Vocabulary.class);
    when(vocabulary.size()).thenReturn(201L);
    when(vocabulary.getToken(200)).thenReturn("match");
    var result = mock(OrtSession.Result.class);
    var ids = mock(OnnxTensor.class);
    var weights = mock(OnnxTensor.class);
    when(ids.getLongBuffer()).thenReturn(LongBuffer.wrap(new long[] {200}));
    when(weights.getFloatBuffer()).thenReturn(FloatBuffer.wrap(new float[] {0.5f}));
    when(result.get("output_idx")).thenReturn(Optional.of(ids));
    when(result.get("output_weights")).thenReturn(Optional.of(weights));
    when(lease.run(anyMap())).thenReturn(result);
    var config = new SpladeConfig(true, Path.of("fixture"), 512, false, 0, 0, "onnx", "log1p");
    try (var tensors = mockStatic(OnnxTensor.class);
        var encoder = new SpladeEncoder(sessions,
            new SpladeShape(512, false, SpladeEncoder.OutputFormat.PRESPARSE, null),
            tokenizer, vocabulary, null, config)) {
      tensors.when(() -> OnnxTensor.createTensor(any(OrtEnvironment.class),
          any(LongBuffer.class), any(long[].class))).thenReturn(mock(OnnxTensor.class));
      var output = assertDoesNotThrow(() -> encoder.encodeBatch(List.of("512 token fixture"), Long.MAX_VALUE),
          "CPU inference must not throw because its unused GPU cap is too small");
      assertEquals(List.of(Map.of("match", 0.5f)), output);
      verify(sessions).acquireCpu(any());
    }
  }
}
