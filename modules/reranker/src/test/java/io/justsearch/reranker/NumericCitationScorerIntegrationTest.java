/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.ort.testing.InferenceCompositionRootTestHelper;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** WP4.5 NG1 and F5: actual CPU model; optional locally, explicitly enabled in builder evidence. */
class NumericCitationScorerIntegrationTest {
  private record Fixture(String answer, List<String> passages) {}
  private static final List<Fixture> FIXTURES = List.of(
      new Fixture("Vespera Observatory opened in 2099.",
          List.of("Vespera Observatory opened in 1883.")),
      new Fixture("Vespera Observatory opened in 1923.",
          List.of("Vespera Observatory opened in 1883.")),
      new Fixture("Vespera Observatory opened in 1923.",
          List.of("Vespera Observatory opened in 1883.", "Vespera Observatory opened in 1923.")),
      new Fixture("The engine owns the index.",
          List.of("The engine owns the index and handles local searches.", "The kitchen is warm.")),
      new Fixture("افتتح المرصد في ١٨٨٣.", List.of("The observatory opened in 1883.")),
      new Fixture("The array is `[4]`.", List.of("The array is empty.", "The array contains 4.")));
  private static CitationScorer scorer;
  private static Path modelDir;

  @BeforeAll
  static void setUp() throws Exception {
    String configured = System.getenv("JUSTSEARCH_CITATION_SCORER_MODEL_PATH");
    assumeTrue(configured != null && !configured.isBlank(), "Set JUSTSEARCH_CITATION_SCORER_MODEL_PATH");
    modelDir = Path.of(configured);
    assumeTrue(Files.isRegularFile(modelDir.resolve("model.onnx")), "Missing model.onnx");
    var sessions = InferenceCompositionRootTestHelper.cpuSessionFor("numeric-citation-it", modelDir);
    var assembly = CitationScorer.buildAssembly(sessions, modelDir.resolve("tokenizer.json"), 512);
    scorer = new CitationScorer(assembly.sessions(), assembly.shape(), assembly.tokenizer());
  }

  @AfterAll
  static void tearDown() {
    if (scorer != null) scorer.close();
  }

  @Test
  void rejectsRealModelCounterexamplesAndKeepsCoverage() {
    for (Fixture fixture : FIXTURES.subList(0, 2)) {
      var result = score(fixture);
      assertEquals(List.of(), result.matches(), fixture.answer());
      assertEquals(0, result.sentencesMatched());
      assertEquals(1, result.sentencesScored(), "rejected still means scored");
    }
  }

  @Test
  void reportsFiveRunScorerSuiteMeasurement() throws Exception {
    for (String file : List.of("model.onnx", "tokenizer.json", "config.json")) {
      System.out.println("NUMERIC_MODEL " + file + " sha256=" + sha256(Files.readAllBytes(modelDir.resolve(file))));
    }
    System.out.println("NUMERIC_FIXTURES sha256=" + sha256(FIXTURES.toString().getBytes(StandardCharsets.UTF_8))
        + " fixtures=" + FIXTURES);
    var os = ManagementFactory.getOperatingSystemMXBean();
    System.out.println("NUMERIC_RESOURCE java=" + System.getProperty("java.version")
        + " ort=" + ai.onnxruntime.OrtEnvironment.getEnvironment().getVersion()
        + " os=" + os.getName() + " arch=" + os.getArch()
        + " processors=" + os.getAvailableProcessors() + " load=" + os.getSystemLoadAverage());
    for (int warmup = 0; warmup < 2; warmup++) {
      for (Fixture fixture : FIXTURES) score(fixture);
    }
    List<Long> suiteNanos = new ArrayList<>();
    for (int run = 0; run < 5; run++) {
      long suite = 0;
      List<Integer> scored = new ArrayList<>();
      List<Integer> matched = new ArrayList<>();
      for (Fixture fixture : FIXTURES) {
        long start = System.nanoTime();
        var result = score(fixture);
        suite += System.nanoTime() - start;
        scored.add(result.sentencesScored());
        matched.add(result.sentencesMatched());
        assertEquals(1, result.sentencesScored(), "fixture must complete under the same 2000ms deadline");
      }
      suiteNanos.add(suite);
      System.out.println("NUMERIC_RUN " + run + " suiteNanos=" + suite + " scored=" + scored
          + " matched=" + matched + " load=" + os.getSystemLoadAverage());
    }
    suiteNanos.sort(Long::compare);
    System.out.println("NUMERIC_MEDIAN suiteNanos=" + suiteNanos.get(2));
  }

  private static CitationScorer.ScoringResult score(Fixture fixture) {
    return scorer.scoreAll(List.of(fixture.answer()), fixture.passages(),
        java.util.Collections.nCopies(fixture.passages().size(), "fixture-doc"), 0.5, 2000);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
