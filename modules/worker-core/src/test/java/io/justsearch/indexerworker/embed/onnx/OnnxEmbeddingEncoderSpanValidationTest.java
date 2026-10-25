/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.djl.huggingface.tokenizers.jni.CharSpan;
import org.junit.jupiter.api.Test;

final class OnnxEmbeddingEncoderSpanValidationTest {
  @Test
  void emptyIntervalsSelectNoTokenIncludingInsideToken() {
    var encoder = new OnnxEmbeddingEncoder(null,
        new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 0, 0), null);
    for (int endpoint : new int[] {0, 2, 4}) {
      assertNull(encoder.poolSpan(new float[][] {{3, 4}}, new long[] {1},
          new CharSpan[] {new CharSpan(0, 4)}, endpoint, endpoint, 2));
    }
  }

  @Test
  void malformedTextIsRejectedBeforeTokenizationEvenForEmptyRows() {
    var encoder = new OnnxEmbeddingEncoder(null,
        new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 0, 0), null);
    for (char surrogate : new char[] {(char) 0xd83d, (char) 0xde00}) {
      for (String text : new String[] {surrogate + " blue", "blue " + surrogate}) {
        assertThrows(IllegalArgumentException.class,
            () -> encoder.embedWithSpans(text, new int[][] {{0, 0}}));
      }
    }
  }

  @Test
  void invalidBoundsAreRejectedBeforeTokenizationOrInference() {
    // Null dependencies deliberately make touching the tokenizer/session fail this contract.
    var encoder = new OnnxEmbeddingEncoder(null,
        new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 0, 0), null);
    String content = "a😀z";
    int[][][] invalid = {null, {null}, {{}}, {{0}}, {{0, 1, 2}}, {{-1, 1}}, {{3, 1}},
        {{0, 5}}, {{Integer.MIN_VALUE, 0}}, {{0, Integer.MAX_VALUE}}, {{2, 3}}, {{1, 2}}, {{2, 2}}};
    assertThrows(IllegalArgumentException.class, () -> encoder.embedWithSpans(null, new int[0][]));
    for (int[][] spans : invalid) {
      assertThrows(IllegalArgumentException.class, () -> encoder.embedWithSpans(content, spans));
    }
  }
}
