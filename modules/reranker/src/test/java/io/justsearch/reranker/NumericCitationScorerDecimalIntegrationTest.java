/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.ort.testing.InferenceCompositionRootTestHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** NX3: skipped claim forms preserve the raw CPU-model verdict, scores and scored coverage. */
class NumericCitationScorerDecimalIntegrationTest {
  private static CitationScorer scorer;

  @BeforeAll
  static void setUp() throws Exception {
    String configured = System.getenv("JUSTSEARCH_CITATION_SCORER_MODEL_PATH");
    assumeTrue(configured != null && !configured.isBlank(), "Set JUSTSEARCH_CITATION_SCORER_MODEL_PATH");
    Path modelDir = Path.of(configured);
    assumeTrue(Files.isRegularFile(modelDir.resolve("model.onnx")), "Missing model.onnx");
    var sessions = InferenceCompositionRootTestHelper.cpuSessionFor("decimal-citation-it", modelDir);
    var assembly = CitationScorer.buildAssembly(sessions, modelDir.resolve("tokenizer.json"), 512);
    scorer = new CitationScorer(assembly.sessions(), assembly.shape(), assembly.tokenizer());
  }

  @AfterAll
  static void tearDown() {
    if (scorer != null) scorer.close();
  }

  @Test
  void skipsFormerNx2FormsThroughRealModel() {
    String[][] pairs = {
        {"The rate is %-5.", "The rate is 5."},
        {"Oran %-5.", "Oran 5."},
        {"The value is 0x10.", "The values are 0 and 10."},
        {"Admission costs $5.", "Admission costs $5."}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var result = scorer.scoreAll(List.of(pair[0]), List.of(pair[1]), List.of("nx2"), 0.5, 2000);
      var raw = scorer.scoreClaims(List.of(new NumericConsistency.Claim(pair[0], java.util.Set.of())),
          List.of(pair[1]), List.of("nx2"), 0.5, 2000, io.justsearch.core.execution.InferenceRequest.foreground());
      assertFalse(raw.matches().isEmpty(), "fixture must clear the original model threshold");
      assertEquals(raw.matches(), result.matches(), java.util.Arrays.toString(pair));
      assertEquals(raw.sentencesMatched(), result.sentencesMatched());
      assertEquals(1, result.sentencesScored());
    }));
  }

  @Test
  void skipsLeadingDecimalsThroughRealModel() {
    assertAll(List.of(".5", "-.5", "+.5%").stream().map(value -> () -> {
      String sentence = "The voltage is " + value + ".";
      String passage = value.endsWith("%") ? "The voltage is 5%." : "The voltage is 5.";
      var result = scorer.scoreAll(List.of(sentence), List.of(passage), List.of("voltage"), 0.5, 2000);
      var raw = scorer.scoreClaims(List.of(new NumericConsistency.Claim(sentence, java.util.Set.of())),
          List.of(passage), List.of("voltage"), 0.5, 2000, io.justsearch.core.execution.InferenceRequest.foreground());
      assertFalse(raw.matches().isEmpty(), "fixture must clear the original model threshold");
      assertEquals(raw.matches(), result.matches(), sentence);
      assertEquals(raw.sentencesMatched(), result.sentencesMatched());
      assertEquals(1, result.sentencesScored(), sentence);
    }));
  }
}
