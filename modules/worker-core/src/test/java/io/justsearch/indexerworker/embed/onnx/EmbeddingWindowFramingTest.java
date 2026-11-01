/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import io.justsearch.ort.SessionHandle;
import java.nio.LongBuffer;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class EmbeddingWindowFramingTest {
  @ParameterizedTest
  @ValueSource(ints = {512, 513, 895, 896, 1024, 1703})
  void allEntryPointsFrameTheSameOrderedWindows(int count) throws Exception {
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(sessions.acquire(any())).thenReturn(lease);
    when(sessions.acquireCpu(any())).thenReturn(lease);
    var tokenizer = mock(HuggingFaceTokenizer.class);
    var encoding = mock(Encoding.class);
    long[] source = new long[count];
    long[] mask = new long[count]; Arrays.fill(mask, 1);
    for (int i = 0; i < count; i++) source[i] = 200 + i;
    source[0] = 17; source[count - 1] = 19;
    when(encoding.getIds()).thenReturn(source);
    when(encoding.getAttentionMask()).thenReturn(mask);
    when(encoding.getTypeIds()).thenReturn(new long[count]);
    when(tokenizer.encode(anyString())).thenReturn(encoding);
    when(tokenizer.batchEncode(anyList())).thenAnswer(call -> {
      List<?> texts = call.getArgument(0);
      return Collections.nCopies(texts.size(), encoding).toArray(Encoding[]::new);
    });
    var observed = new ArrayList<List<Long>>();
    when(lease.run(anyMap())).thenAnswer(call -> {
      Map<String, OnnxTensor> inputs = call.getArgument(0);
      long[][] ids = (long[][]) inputs.get("input_ids").getValue();
      long[][] masks = (long[][]) inputs.get("attention_mask").getValue();
      long[][] types = (long[][]) inputs.get("token_type_ids").getValue();
      float[][][] hidden = new float[ids.length][ids[0].length][2];
      for (int row = 0; row < ids.length; row++) {
        int real = 0;
        while (real < masks[row].length && masks[row][real] == 1) real++;
        assertTrue(real <= 512);
        assertEquals(17, ids[row][0]);
        assertEquals(19, ids[row][real - 1]);
        assertEquals(ids[row].length, types[row].length);
        var window = new ArrayList<Long>();
        for (int i = 0; i < real; i++) window.add(ids[row][i]);
        observed.add(window);
        for (float[] token : hidden[row]) { token[0] = 3; token[1] = 4; }
      }
      var result = mock(OrtSession.Result.class);
      var value = mock(OnnxTensor.class);
      when(value.getValue()).thenReturn(hidden);
      when(result.get(0)).thenReturn(value);
      return result;
    });
    try (var tensors = mockStatic(OnnxTensor.class);
        var encoder = new OnnxEmbeddingEncoder(sessions,
            new EmbeddingShape(512, true, OnnxEmbeddingEncoder.PoolingStrategy.CLS, 0, 2), tokenizer)) {
      tensors.when(() -> OnnxTensor.createTensor(any(), any(LongBuffer.class), any(long[].class)))
          .thenAnswer(call -> {
            LongBuffer flat = call.getArgument(1); long[] shape = call.getArgument(2);
            long[][] data = new long[(int) shape[0]][(int) shape[1]];
            for (long[] row : data) flat.get(row);
            var tensor = mock(OnnxTensor.class);
            when(tensor.getValue()).thenReturn(data);
            return tensor;
          });
      String text = "x".repeat(count);
      var single = encoder.embed(text);
      var ordered = List.copyOf(observed); observed.clear();
      assertEquals(ordered.size(), encoder.windowCount(text));
      assertArrayEquals(new float[] {.6f, .8f}, single.vector(), 1e-6f);
      encoder.embedBatchWithChunking(List.of(text));
      assertEquals(ordered, observed); observed.clear();
      encoder.embedBatchPooled(List.of(text));
      assertEquals(ordered, observed); observed.clear();
      for (int offset = 0; offset < ordered.size(); offset++) encoder.embedWindows(text, offset, 1);
      assertEquals(ordered, observed);
    }
  }
}
