/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.djl.huggingface.tokenizers.jni.CharSpan;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder.PoolingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java unit coverage for {@link OnnxEmbeddingEncoder#poolSpan} and {@link
 * OnnxEmbeddingEncoder#pool} (tempdoc 691 Wave 0) — both are pure functions of arrays + {@link
 * CharSpan}[], made package-private for this test. All fixtures use small, hand-checkable numbers
 * (dim 2-3), chosen so masked-mean averages land on exact 3-4-5-triangle values (avoids float
 * epsilon noise in the L2-normalize assertions).
 *
 * <p>{@code CharSpan} has a public {@code (int, int)} constructor (verified via {@code javap} on
 * the {@code ai.djl.huggingface:tokenizers} jar), so spans are constructed directly — no
 * reflection workaround needed.
 *
 * <p>{@link OnnxEmbeddingEncoder} is instantiated with {@code null} session/tokenizer: its
 * constructor only assigns fields and logs (no I/O on either), and {@code poolSpan}/{@code pool}
 * touch neither field — {@code poolSpan} is fully static-shaped (params only), {@code pool} reads
 * only the instance's {@code poolingStrategy}.
 */
@DisplayName("OnnxEmbeddingEncoder pooling (poolSpan / pool)")
class OnnxEmbeddingEncoderPoolingTest {

  private static OnnxEmbeddingEncoder newEncoder(PoolingStrategy strategy) {
    EmbeddingShape shape = new EmbeddingShape(512, false, strategy, 0, 0);
    return new OnnxEmbeddingEncoder(null, shape, null);
  }

  // ---------------------------------------------------------------------------
  // poolSpan
  // ---------------------------------------------------------------------------

  /**
   * Shared fixture: 3 tokens, dim=2.
   *
   * <ul>
   *   <li>t0: char span [0,3), hidden={2,2}
   *   <li>t1: char span [3,6), hidden={4,6}
   *   <li>t2: char span [6,9), hidden={100,100} (outside target range — must never be pooled in)
   * </ul>
   *
   * Target span [2,6) intersects t0 and t1 only (t2's tokStart=6 >= endChar=6, excluded by the
   * half-open boundary). mean(t0,t1) = {3,4}; L2-normalized {3,4}/5 = {0.6,0.8} exactly — chosen
   * as a 3-4-5 triangle so the expected floats are exact, not epsilon-compared.
   */
  private static float[][] baseHidden() {
    return new float[][] {{2f, 2f}, {4f, 6f}, {100f, 100f}};
  }

  private static CharSpan[] baseSpans() {
    return new CharSpan[] {new CharSpan(0, 3), new CharSpan(3, 6), new CharSpan(6, 9)};
  }

  @Test
  @DisplayName("token-span intersection selects the right tokens (hand-computed masked mean)")
  void poolSpan_selectsIntersectingTokens_handComputedMean() {
    OnnxEmbeddingEncoder encoder = newEncoder(PoolingStrategy.MEAN_POOL);
    long[] mask = {1, 1, 1};

    float[] result = encoder.poolSpan(baseHidden(), mask, baseSpans(), 2, 6, 2);

    assertArrayEquals(new float[] {0.6f, 0.8f}, result, 1e-6f);
  }

  @Test
  @DisplayName("mask=0 tokens are excluded even when their span intersects the target range")
  void poolSpan_excludesMaskedOutTokens() {
    OnnxEmbeddingEncoder encoder = newEncoder(PoolingStrategy.MEAN_POOL);

    // 4 tokens: base fixture (t0,t1,t2) plus a masked-out token whose span [4,5) intersects
    // [2,6) but must be excluded because mask=0.
    float[][] hidden = {{2f, 2f}, {4f, 6f}, {100f, 100f}, {1000f, 1000f}};
    CharSpan[] spans = {
      new CharSpan(0, 3), new CharSpan(3, 6), new CharSpan(6, 9), new CharSpan(4, 5)
    };
    long[] mask = {1, 1, 1, 0};

    float[] result = encoder.poolSpan(hidden, mask, spans, 2, 6, 2);

    assertArrayEquals(
        new float[] {0.6f, 0.8f}, result, 1e-6f, "masked-out token must not shift the mean");
  }

  @Test
  @DisplayName("zero-width spans (start == end) are excluded even when mask=1")
  void poolSpan_excludesZeroWidthSpans() {
    OnnxEmbeddingEncoder encoder = newEncoder(PoolingStrategy.MEAN_POOL);

    // 4 tokens: base fixture (t0,t1,t2) plus a zero-width-span special token (e.g. SEP sentinel)
    // sitting inside the target range with mask=1 — must be excluded by the zero-width check.
    float[][] hidden = {{2f, 2f}, {4f, 6f}, {100f, 100f}, {999f, 999f}};
    CharSpan[] spans = {
      new CharSpan(0, 3), new CharSpan(3, 6), new CharSpan(6, 9), new CharSpan(5, 5)
    };
    long[] mask = {1, 1, 1, 1};

    float[] result = encoder.poolSpan(hidden, mask, spans, 2, 6, 2);

    assertArrayEquals(
        new float[] {0.6f, 0.8f}, result, 1e-6f, "zero-width sentinel token must not shift the mean");
  }

  @Test
  @DisplayName("no intersecting tokens -> returns null")
  void poolSpan_noIntersectingTokens_returnsNull() {
    OnnxEmbeddingEncoder encoder = newEncoder(PoolingStrategy.MEAN_POOL);
    long[] mask = {1, 1, 1};

    // Target span [100,200) does not intersect any of t0/t1/t2's [0,9) coverage.
    float[] result = encoder.poolSpan(baseHidden(), mask, baseSpans(), 100, 200, 2);

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // pool
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("whole-content span excludes specials for both CLS and MEAN document pooling")
  void wholeContentSpanDiffersFromDocumentPooling() {
    // CLS={10,0}, content mean={3,4}, zero-width special={0,20} are non-collinear.
    float[][] hidden = {{10f, 0f}, {2f, 2f}, {4f, 6f}, {0f, 20f}};
    long[] mask = {1, 1, 1, 1};
    CharSpan[] spans = {
      new CharSpan(0, 0), new CharSpan(0, 3), new CharSpan(3, 6), new CharSpan(5, 5)
    };
    var cls = newEncoder(PoolingStrategy.CLS);
    var mean = newEncoder(PoolingStrategy.MEAN_POOL);

    assertArrayEquals(new float[] {10f, 0f}, cls.pool(hidden, mask, 2));
    // Document MEAN includes both unmasked specials: {16,28}/4 = {4,7}.
    assertArrayEquals(new float[] {4f, 7f}, mean.pool(hidden, mask, 2), 1e-6f);
    assertArrayEquals(new float[] {0.6f, 0.8f}, cls.poolSpan(hidden, mask, spans, 0, 6, 2), 1e-6f);
    assertArrayEquals(new float[] {0.6f, 0.8f}, mean.poolSpan(hidden, mask, spans, 0, 6, 2), 1e-6f);
  }

  @Test
  @DisplayName("CLS branch returns a clone of row 0 (not the same array reference)")
  void pool_clsBranch_returnsRow0Clone() {
    OnnxEmbeddingEncoder encoder = newEncoder(PoolingStrategy.CLS);
    float[][] hidden = {{1f, 2f, 3f}, {4f, 5f, 6f}};
    long[] mask = {1, 1};

    float[] result = encoder.pool(hidden, mask, 3);

    assertArrayEquals(new float[] {1f, 2f, 3f}, result);
    assertNotSame(hidden[0], result, "CLS pooling must return a clone, not the original row");
  }

  @Test
  @DisplayName("MEAN branch computes the attention-mask-weighted mean (unnormalized)")
  void pool_meanBranch_computesMaskWeightedMean() {
    OnnxEmbeddingEncoder encoder = newEncoder(PoolingStrategy.MEAN_POOL);
    // token 2 is masked out (mask=0) and has an outlier value that must not affect the mean.
    float[][] hidden = {{2f, 2f}, {4f, 6f}, {100f, 100f}};
    long[] mask = {1, 1, 0};

    float[] result = encoder.pool(hidden, mask, 2);

    // mean({2,2},{4,6}) = {3,4} — pool() does NOT L2-normalize (callers do that separately).
    assertArrayEquals(new float[] {3f, 4f}, result, 1e-6f);
  }
}
