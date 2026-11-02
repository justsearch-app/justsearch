/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.Vocabulary;
import ai.onnxruntime.*;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.SessionHandle;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

final class SpladeWindowCoverageTest {
  private Map<String, io.justsearch.indexerworker.metrics.EncoderProfileAccumulator> encoderRegistry;
  private Map<String, io.justsearch.indexerworker.metrics.EncoderProfileAccumulator> priorEncoders;

  @org.junit.jupiter.api.BeforeEach
  @SuppressWarnings("unchecked")
  void saveEncoderRegistry() throws Exception {
    var field = io.justsearch.indexerworker.metrics.OperationalMetrics.class
        .getDeclaredField("encoderAccumulators");
    field.setAccessible(true);
    encoderRegistry = (Map<String, io.justsearch.indexerworker.metrics.EncoderProfileAccumulator>)
        field.get(io.justsearch.indexerworker.metrics.OperationalMetrics.getInstance());
    priorEncoders = new HashMap<>(encoderRegistry);
  }

  @org.junit.jupiter.api.AfterEach
  void restoreEncoderRegistry() {
    encoderRegistry.clear();
    encoderRegistry.putAll(priorEncoders);
  }

  @Test
  void headAndTailTermsSurviveSingleAndBatchWithTheirOriginalOwners() throws Exception {
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(lease.isCpu()).thenReturn(true);
    when(sessions.acquire(any())).thenReturn(lease);
    when(sessions.acquireCpu(any())).thenReturn(lease);
    when(sessions.status()).thenReturn(OrtCudaStatus.notConfigured());
    var tokenizer = mock(HuggingFaceTokenizer.class);
    Encoding dense = encoding(37, 200);
    Encoding shortText = encoding(3, 900);
    when(tokenizer.encode(anyString())).thenAnswer(call -> "short".equals(call.getArgument(0)) ? shortText : dense);
    when(tokenizer.batchEncode(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      return texts.stream().map(text -> "short".equals(text) ? shortText : dense).toArray(Encoding[]::new);
    });
    var vocabulary = mock(Vocabulary.class);
    when(vocabulary.size()).thenReturn(1000L);
    when(vocabulary.getToken(anyLong())).thenAnswer(call -> "term" + call.getArgument(0));
    when(lease.run(anyMap())).thenAnswer(call -> {
      Map<String, OnnxTensor> inputs = call.getArgument(0);
      LongBuffer ids = inputs.get("input_ids").getLongBuffer();
      assertTrue(ids.limit() <= 8, "every window fits the model");
      assertEquals(101, ids.get(0)); assertEquals(102, ids.get(ids.limit() - 1));
      long[] terms = new long[ids.limit() - 2];
      for (int i = 0; i < terms.length; i++) terms[i] = ids.get(i + 1);
      float[] values = new float[terms.length]; Arrays.fill(values, .5f);
      var result = mock(OrtSession.Result.class);
      var index = mock(OnnxTensor.class);
      var weights = mock(OnnxTensor.class);
      when(index.getLongBuffer()).thenReturn(LongBuffer.wrap(terms));
      when(weights.getFloatBuffer()).thenReturn(FloatBuffer.wrap(values));
      when(result.get("output_idx")).thenReturn(Optional.of(index));
      when(result.get("output_weights")).thenReturn(Optional.of(weights));
      return result;
    });
    try (var tensors = mockStatic(OnnxTensor.class);
        var encoder = new SpladeEncoder(sessions,
            new SpladeShape(8, false, SpladeEncoder.OutputFormat.PRESPARSE, null),
            tokenizer, vocabulary, null,
            new SpladeConfig(true, Path.of("fixture"), 8, false, 0, 0, "onnx", "log1p"))) {
      tensors.when(() -> OnnxTensor.createTensor(any(), any(LongBuffer.class), any(long[].class)))
          .thenAnswer(call -> {
            var tensor = mock(OnnxTensor.class);
            LongBuffer value = call.getArgument(1);
            when(tensor.getLongBuffer()).thenReturn(value);
            return tensor;
          });
      Map<String, Float> single = encoder.encode("dense!中😀");
      assertEquals(35, single.size());
      assertEquals(.5f, single.get("term235"));
      var batch = encoder.encodeBatch(List.of("short", "dense!中😀", "short"));
      assertEquals(single, batch.get(1));
      assertEquals(Map.of("term901", .5f), batch.get(0));
      assertEquals(batch.get(0), batch.get(2));
      assertEquals(single, encoder.encodeBatch(List.of("dense!中😀")).get(0));
    }
  }

  private static Encoding encoding(int count, int base) {
    var encoding = mock(Encoding.class);
    long[] ids = new long[count]; long[] masks = new long[count];
    for (int i = 0; i < count; i++) { ids[i] = base + i; masks[i] = 1; }
    ids[0] = 101; ids[count - 1] = 102;
    when(encoding.getIds()).thenReturn(ids);
    when(encoding.getAttentionMask()).thenReturn(masks);
    when(encoding.getTypeIds()).thenReturn(new long[count]);
    return encoding;
  }
}
