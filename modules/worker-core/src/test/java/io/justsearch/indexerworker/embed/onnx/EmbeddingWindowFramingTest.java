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

  @ParameterizedTest
  @ValueSource(ints = {512, 513, 895, 896, 1024, 1703})
  void allEntryPointsFrameTheSameOrderedWindows(int count) throws Exception {
    checkEntryPoints(count, OnnxEmbeddingEncoder.PoolingStrategy.CLS, null);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "4,CLS,1062591973,-1089686613", "37,CLS,1061768233,-1088542322", "512,CLS,1061814863,-1088601946",
      "4,MEAN_POOL,1062018539,-1088869067", "37,MEAN_POOL,1062301069,-1089258945", "512,MEAN_POOL,1063657129,-1092594618"})
  void shortAndSingleWindowVectorsMatchBaselineBits(
      int count, OnnxEmbeddingEncoder.PoolingStrategy pooling, int first, int second) throws Exception {
    checkEntryPoints(count, pooling, new int[] {first, second});
  }

  private static void checkEntryPoints(int count, OnnxEmbeddingEncoder.PoolingStrategy pooling,
      int[] expectedBits) throws Exception {
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
        long signature = 0;
        for (int i = 0; i < real; i++) signature += ids[row][i] * (i + 1);
        signature %= 113;
        for (int i = 0; i < ids[row].length; i++) {
          hidden[row][i][0] = (ids[row][i] % 97) + i * .125f + signature * .25f;
          hidden[row][i][1] = (ids[row][i] % 31) - i * .0625f - signature * .5f;
        }
      }
      var result = mock(OrtSession.Result.class);
      var value = mock(OnnxTensor.class);
      when(value.getValue()).thenReturn(hidden);
      when(result.get(0)).thenReturn(value);
      return result;
    });
    try (var tensors = mockStatic(OnnxTensor.class);
        var encoder = new OnnxEmbeddingEncoder(sessions,
            new EmbeddingShape(512, true, pooling, 0, 2), tokenizer)) {
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
      if (expectedBits != null) {
        int[] actualBits = bits(single.vector());
        assertArrayEquals(expectedBits, actualBits);
      }
      var retained = encoder.embedBatchWithChunking(List.of(text));
      assertArrayEquals(bits(single.vector()), bits(retained.get(0).vector()));
      assertEquals(ordered, observed); observed.clear();
      var pooled = encoder.embedBatchPooled(List.of(text));
      assertArrayEquals(bits(single.vector()), bits(pooled.get(0).vector()));
      assertEquals(ordered, observed); observed.clear();
      for (int offset = 0; offset < ordered.size(); offset++) {
        var slice = encoder.embedWindows(text, offset, 1);
        float[] expected = ordered.size() == 1 ? single.vector() : single.chunkVectors().get(offset);
        assertArrayEquals(bits(expected), bits(slice.vectors().get(0)));
      }
      assertEquals(ordered, observed);
    }
  }
  private static int[] bits(float[] vector) {
    int[] bits = new int[vector.length];
    for (int i = 0; i < vector.length; i++) bits[i] = Float.floatToRawIntBits(vector[i]);
    return bits;
  }
}
