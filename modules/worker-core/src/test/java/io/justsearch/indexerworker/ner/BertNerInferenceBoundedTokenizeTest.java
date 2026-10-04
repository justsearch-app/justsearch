/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import io.justsearch.indexerworker.inference.BoundedTokenizeGroups;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression for the tempdoc 710 Move 3 port of the tempdoc 686 SPLADE crash fix to the NER lane:
 * {@code inferBatch} used to tokenize every caller text upfront in a single unbounded scan before
 * any inference sub-batching. This model's {@code tokenizer.json} declares {@code "truncation":
 * null} (no tokenizer-level cap), so one oversized text (or an unbounded caller list) held the
 * same class of landmine {@code SpladeEncoderBoundedTokenizeTest} exercises for SPLADE. The fix
 * groups the tokenize phase into character-budgeted {@code batchEncode} calls.
 *
 * <p>Like the SPLADE test, the memory bound itself is not assertable in-JVM; this test pins the
 * OBSERVABLE contract instead — grouping must not reorder, drop, or cross-wire results relative
 * to running inference on each text alone. NER truncates every text to {@code
 * maxSequenceLength} (no chunking, unlike the embed lane), so per-text compute is bounded
 * regardless of raw text length. A small injected budget exercises multiple groups cheaply.
 */
@DisplayName("710: BertNerInference bounded-group tokenization preserves per-text results")
final class BertNerInferenceBoundedTokenizeTest {

  private static Path modelDir;
  private static BertNerInference encoder;

  @BeforeAll
  static void setUp() throws Exception {
    // Tempdoc 710 Move 6: shared walker (obs:spladebatchsweeptest).
    io.justsearch.ort.testing.ModelDirTestResolver.Discovery discovery =
        io.justsearch.ort.testing.ModelDirTestResolver.discover(
            "models/onnx/ner", null, "model.onnx", "tokenizer.json");
    assumeTrue(discovery.modelDir() != null, discovery.missDescription());
    modelDir = discovery.modelDir();

    io.justsearch.ort.SessionHandle sessions =
        io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
            "ner-bounded-test", modelDir);
    NerAssembly assembly = BertNerInference.buildAssembly(sessions, modelDir, 512, false);
    encoder = new BertNerInference(assembly.sessions(), assembly.shape(), assembly.tokenizer());
  }

  @AfterAll
  static void tearDown() {
    if (encoder != null) {
      encoder.close();
    }
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  @DisplayName("multi-group batch == per-text singleton results, order preserved")
  void groupBoundariesPreserveResults() throws Exception {
    List<String> batch =
        List.of(
            "short one about cystic fibrosis",
            "alpha beam corpus ".repeat(48),
            "short two about gene editing",
            "delta ocean canyon ".repeat(52),
            "short three about microbiomes",
            "short four closes the batch");
    long budget = 64;
    assertTrue(BoundedTokenizeGroups.ranges(batch, budget).size() >= 3);
    List<BertNerInference.InferenceOutput> batched = encoder.inferBatch(batch, budget);
    assertEquals(batch.size(), batched.size());

    List<BertNerInference.InferenceOutput> singletons = new ArrayList<>();
    for (String text : batch) {
      singletons.add(encoder.inferBatch(List.of(text)).get(0));
    }

    for (int i = 0; i < batch.size(); i++) {
      assertSameInferenceOutput(singletons.get(i), batched.get(i), i);
    }
  }

  /**
   * Batched inference pads sequences to the sub-batch's bucket boundary, which can produce tiny
   * floating-point differences vs singleton inference at the tail (same rationale as SPLADE's
   * {@code assertSameSparseVector}) — but the argmax label per real (non-padding) token position
   * must agree exactly, since that's the observable contract BioTagDecoder depends on. Tokens and
   * word IDs (pure tokenizer output, independent of ORT padding) must match exactly.
   */
  private static void assertSameInferenceOutput(
      BertNerInference.InferenceOutput expected,
      BertNerInference.InferenceOutput actual,
      int position) {
    assertArrayEquals(
        expected.tokens(), actual.tokens(), "position " + position + ": token mismatch");
    assertArrayEquals(
        expected.wordIds(), actual.wordIds(), "position " + position + ": wordId mismatch");
    assertEquals(
        expected.logits().length,
        actual.logits().length,
        "position " + position + ": token-count mismatch");
    for (int t = 0; t < expected.logits().length; t++) {
      int expectedArgmax = argmax(expected.logits()[t]);
      int actualArgmax = argmax(actual.logits()[t]);
      assertEquals(
          expectedArgmax,
          actualArgmax,
          "position "
              + position
              + ", token "
              + t
              + " ('"
              + expected.tokens()[t]
              + "'): dominant-label mismatch between singleton and batched inference");
    }
  }

  private static int argmax(float[] logits) {
    int best = 0;
    for (int i = 1; i < logits.length; i++) {
      if (logits[i] > logits[best]) {
        best = i;
      }
    }
    return best;
  }
}
