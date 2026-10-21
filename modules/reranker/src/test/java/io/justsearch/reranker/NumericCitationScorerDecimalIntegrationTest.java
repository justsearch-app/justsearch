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

/** NX1 regression through the CPU scorer; evidence supplies the read-only model explicitly. */
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
  void rejectsLeadingDecimalCounterexamplesThroughRealModel() {
    assertAll(List.of(".5", "-.5", "+.5%").stream().map(value -> () -> {
      String sentence = "The voltage is " + value + ".";
      String passage = value.endsWith("%") ? "The voltage is 5%." : "The voltage is 5.";
      var result = scorer.scoreAll(List.of(sentence), List.of(passage), List.of("voltage"), 0.5, 2000);
      assertEquals(List.of(), result.matches(), sentence);
      assertEquals(1, result.sentencesScored(), sentence);
    }));
  }
}
