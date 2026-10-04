package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.justsearch.indexerworker.inference.BoundedTokenizeGroups;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression for the tempdoc 686 full-corpus enrichment crash: {@code encodeBatchTokenBudget}
 * used to materialize ALL input encodings in one native {@code batchEncode} call (truncation
 * disabled → ~100k-token encodings for full-document text), exhausting the 1g worker heap and
 * killing the JVM natively inside DJL's {@code getTokenCharSpans}. The fix tokenizes in
 * character-budgeted groups (512k chars per native call) and
 * retains only the maxSeqLen-truncated arrays.
 *
 * <p>This test pins the observable contract across group boundaries: an injected small budget
 * forces multiple groups, and each position must produce the same sparse vector as alone —
 * i.e. grouping must not reorder, drop, or cross-wire results. (The memory bound itself is not
 * assertable in-JVM; the equivalence property is what breaks if the grouping logic is wrong.)
 */
@DisplayName("686: SPLADE bounded-group tokenization preserves per-text results")
final class SpladeEncoderBoundedTokenizeTest {

  private static Path modelDir;
  private static SpladeEncoder encoder;

  @BeforeAll
  static void setUp() throws Exception {
    // Tempdoc 710 Move 6: shared walker (obs:spladebatchsweeptest).
    io.justsearch.ort.testing.ModelDirTestResolver.Discovery discovery =
        io.justsearch.ort.testing.ModelDirTestResolver.discover(
            "models/splade/naver-splade-v3", null, "model.onnx", "tokenizer.json");
    assumeTrue(discovery.modelDir() != null, discovery.missDescription());
    modelDir = discovery.modelDir();

    SpladeConfig config = new SpladeConfig(true, modelDir, 512, false, 0, 0, "onnx", "log1p");
    io.justsearch.ort.SessionHandle sessions =
        io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
            "splade-bounded-test", modelDir);
    SpladeAssembly assembly = SpladeEncoder.buildAssembly(sessions, config);
    encoder =
        new SpladeEncoder(
            assembly.sessions(),
            assembly.shape(),
            assembly.tokenizer(),
            assembly.vocabulary(),
            assembly.truncationEvidencePath(),
            config);
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
    List<Map<String, Float>> batched = encoder.encodeBatch(batch, budget);
    assertEquals(batch.size(), batched.size());

    List<Map<String, Float>> singletons = new ArrayList<>();
    for (String text : batch) {
      singletons.add(encoder.encodeBatch(List.of(text)).get(0));
    }

    for (int i = 0; i < batch.size(); i++) {
      assertFalse(batched.get(i).isEmpty(), "empty sparse vector at position " + i);
      assertSameSparseVector(singletons.get(i), batched.get(i), i);
    }
  }

  /**
   * Batched inference pads sequences to the sub-batch max length, which produces tiny
   * floating-point tail differences vs singleton encoding (verified: leading tokens/weights
   * identical, divergence only in near-zero tail terms). Cross-wiring — the defect this test
   * exists to catch — would swap entire vectors, so assert dominant-token agreement with a
   * weight tolerance instead of exact map equality.
   */
  private static void assertSameSparseVector(
      Map<String, Float> expected, Map<String, Float> actual, int position) {
    List<Map.Entry<String, Float>> topExpected =
        expected.entrySet().stream()
            .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
            .limit(20)
            .toList();
    for (Map.Entry<String, Float> e : topExpected) {
      Float actualWeight = actual.get(e.getKey());
      assertFalse(
          actualWeight == null,
          "position " + position + ": dominant token '" + e.getKey() + "' missing in batched");
      assertEquals(
          e.getValue(),
          actualWeight,
          0.01f,
          "position " + position + ": weight mismatch for token '" + e.getKey() + "'");
    }
  }
}
