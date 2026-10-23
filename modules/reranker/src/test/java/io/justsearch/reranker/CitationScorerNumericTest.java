/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import io.justsearch.ort.SessionHandle;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** WP4.5: literal D8/F1/F3 table, exercised through the real cross-encoder selection loop. */
class CitationScorerNumericTest {
  private record Pair(String sentence, String passage) {}
  private CitationScorer scorer;
  private final List<String> scoredPassages = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(sessions.acquire(any())).thenReturn(lease);
    var tokenizer = mock(RerankerTokenizer.class);
    when(tokenizer.encodePairsStrict(anyString(), any(String[].class))).thenAnswer(call -> {
      String[] passages = call.getArgument(1);
      scoredPassages.clear();
      scoredPassages.addAll(List.of(passages));
      return new RerankerTokenizer.EncodedBatch(new long[passages.length][2],
          new long[passages.length][2], new long[passages.length][2], passages.length, 2, 0, 2);
    });
    when(lease.run(anyMap())).thenAnswer(call -> {
      float[] scores = new float[scoredPassages.size()];
      for (int i = 0; i < scores.length; i++) {
        scores[i] = scoredPassages.get(i).startsWith("alternative") ? 0.8f : 0.976f;
      }
      var tensor = mock(OnnxTensor.class);
      when(tensor.getValue()).thenReturn(scores);
      var result = mock(OrtSession.Result.class);
      when(result.get(0)).thenReturn(tensor);
      return result;
    });
    scorer = new CitationScorer(sessions, new RerankerShape(2, false), tokenizer);
  }

  @Test
  void appliesNx3aNormalizationAndGroupedBoundaries() {
    // NX3a: normalize both sides before applying the unchanged plain-number rules.
    String[][] pairs = {
        {"The voltage measured 5 volts.", "The voltage measured 1٫5 volts.", "false"},
        {"The voltage measured 1.5 volts.", "The voltage measured 1٫5 volts.", "true"},
        {"The voltage measured ١٫٥ volts.", "The voltage measured 1.5 volts.", "true"},
        {"The voltage measured ١٫٥ volts.", "The voltage measured 5 volts.", "false"},
        {"The voltage measured 5 volts.", "The voltage measured ١٫٥ volts.", "false"},
        {"The voltage measured 5 volts.", "The voltage measured ٫5 volts.", "false"},
        {"The voltage measured 5 volts.", "The voltage measured １．５ volts.", "false"},
        {"The voltage measured １．５ volts.", "The voltage measured 1.5 volts.", "true"},
        {"The voltage measured １．５ volts.", "The voltage measured 5 volts.", "false"},
        {"The voltage measured 1.5 volts.", "The voltage measured １．５ volts.", "true"},
        {"The meeting starts at 1：30.", "No number here.", "true"},
        {"The meeting starts at １：３０.", "No number here.", "true"},
        {"The shipment contains 5，000 bags.", "The shipment contains 5000 bags.", "true"},
        {"The shipment contains 5，000 bags.", "No number here.", "false"},
        {"The shipment contains ٥٬٠٠٠ bags.", "The shipment contains 5000 bags.", "true"},
        {"The shipment contains 5000 bags.", "The shipment contains ٥٬٠٠٠ bags.", "true"},
        {"The shipment contains 5000 bags.", "The shipment contains 5，000 bags.", "true"},
        {"The shipment contains 200 bags.", "The shipment contains 1٬200 bags.", "false"},
        {"The shipment contains 1200 bags.", "The shipment contains 1,200 5kg bags.", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1,200 50kg bags.", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1,200 500kg bags.", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1 200 5kg bags.", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1 200 5kg bags.", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1 200 5kg bags.", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1٬200 5kg bags.", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains １，２００ 5kg bags.", "true"},
        {"The shipment contains 1,200 5kg bags.", "The shipment contains 1200 bags.", "true"},
        {"The shipment contains 1,200 5kg bags.", "No number here.", "false"},
        {"The shipment contains 200 bags.", "The shipment contains 1,200 5kg bags.", "false"},
        {"The shipment contains 1200 bags.", "The shipment contains 1,200.5 5kg bags.", "false"},
        {"The shipment contains 1200.5 bags.", "The shipment contains 1,200.5 5kg bags.", "true"},
        {"The value is 5².", "The value is 52.", "true"},
        {"The value is 5².", "The value is 5.", "false"},
        {"The value is ١٬٥٠٠.", "The value is 1500.", "true"},
        {"The value is 1 500.", "The value is 1500.", "true"},
        {"The value is 1 500.", "The value is 1500.", "true"},
        {"The value is 1․5.", "The value is 1.5.", "true"},
        {"The voltage is ١٫٥.", "The values are 1 and 5.", "false"},
        {"القيمة ١٫٥.", "Values are 1, 2, 3, 5 and -5 and 5%.", "false"}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var result = score(pair[0], List.of(pair[1]), 0.5);
      var expected = Boolean.parseBoolean(pair[2])
          ? List.of(new CitationScorer.ScoredMatch(0, pair[0], 0, "doc0", 0.976f))
          : List.of();
      assertEquals(expected, result.matches(), java.util.Arrays.toString(pair));
      assertEquals(1, result.sentencesScored());
      assertEquals(List.of(), score(pair[0], List.of(pair[1]), 0.99).matches());
    }));
  }

  @Test
  void appliesNx3VerifierCounterexamples() {
    // NX3: "The guard checks plain numbers only." Touching forms are skipped on the claim side.
    String[][] pairs = {
        {"The rate is %-5.", "The rate is 5.", "true"},
        {"Oran %-5.", "Oran 5.", "true"},
        {"The rate is % -5.", "The rate is -5.", "true"},
        {"The value is 0x10.", "The values are 0 and 10.", "true"},
        {"The opening date is 21/10/2026.", "The opening date is 21/10/2026.", "true"},
        {"The accepted range is 5kg-10kg.", "The accepted range is 5kg-10kg.", "true"},
        {"The area is measured in m².", "The area is measured in m².", "true"},
        {"Vespera Observatory opened in 1883.", "Vespera Observatory opened in 1883. Admission costs $5.", "true"},
        {"Admission costs $5.", "Admission costs $5.", "true"},
        {"The meeting starts at 1:30.", "The meeting starts at 1:30.", "true"},
        {"The installed version is v1.2.3.", "The installed version is v1.2.3.", "true"},
        {"The accepted range is -10 kg.", "The accepted range is 5 kg -10 kg.", "true"},
        {"The accepted range is 5 kg - 10 kg.", "The accepted range is 5 kg - 10 kg.", "true"},
        {"The accepted range is 5 kg - 10 kg.", "The accepted range is 5 and 10 kg.", "true"},
        {"The price is 15.", "The price is 1$5.", "false"},
        {"The value is 1.", "The value is 1.2.3e4.", "false"},
        {"The value is 1.", "The value is 1:30e4.", "true"},
        {"The value is 5kg%.", "The value is 5.", "true"},
        {"The value is 5.", "The value is 5kg%.", "true"}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var result = score(pair[0], List.of(pair[1]), 0.5);
      var expected = Boolean.parseBoolean(pair[2])
          ? List.of(new CitationScorer.ScoredMatch(0, pair[0], 0, "doc0", 0.976f))
          : List.of();
      assertEquals(expected, result.matches(), java.util.Arrays.toString(pair));
      assertEquals(1, result.sentencesScored());
      assertEquals(List.of(), score(pair[0], List.of(pair[1]), 0.99).matches());
    }));
  }

  @Test
  void preservesSpacedPercentRanges() {
    String[][] pairs = {
        {"The interval is 5 %-10%.", "The values are 5% and 10%."},
        {"The interval is 5 %--10%.", "The values are 5% and -10%."}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var result = score(pair[0], List.of(pair[1]), 0.5);
      assertEquals(List.of(new CitationScorer.ScoredMatch(0, pair[0], 0, "doc0", 0.976f)),
          result.matches(), java.util.Arrays.toString(pair));
      assertEquals(1, result.sentencesScored());
    }));
  }

  @Test
  void checksFormerNx2RejectionsUnderNx3() {
    // NX3: "The guard checks plain numbers only." Nonplain claims never withhold.
    String[][] pairs = {
        {"The rate is %-5.", "The rate is 5.", "true"},
        {"Oran %-5.", "Oran 5.", "true"},
        {"The rate is % -5.", "The rate is -5.", "true"},
        {"The rate is %5.", "The rate is 5.", "true"},
        {"The rate is %-5.", "The rate is %-5.", "true"},
        {"The rate is % -5.", "The rate is % -5.", "true"},
        {"The value is 0x10.", "The values are 0 and 10.", "true"},
        {"The value is 0x10.", "The value is 0x10.", "true"},
        {"The value is 0.", "The value is 0x10.", "true"},
        {"The value is 10.", "The value is 0x10.", "true"},
        {"The value is 1883 and 0x10.", "The values are 1883, 0 and 10.", "true"},
        {"The date is 21/10/2026.", "The parts are 21 and 2026.", "true"},
        {"The date is 21.10.2026.", "The parts are 21 and 2026.", "true"},
        {"The fraction is 3/4.", "The parts are 3 and 4.", "true"},
        {"The interval is 5kg-10kg.", "The value is 5 kg.", "true"},
        {"The price is $5.", "The price is 15.", "true"},
        {"The price is $-5.", "The price is 5.", "true"},
        {"The time is 1:30.", "The value is 1.", "true"},
        {"The version is v1.2.3.", "The values are 1 and 2.", "true"},
        {"The value is 1.5.", "The values are 1 and 5.", "false"},
        {"Opened in 1883.", "Opened in 18830; the value is 0x10.", "false"},
        {"The area is 5m².", "The area is 50 m².", "true"},
        {"The value is 0b101.", "The values are 0 and 101.", "true"},
        {"The value is 101.", "The value is 0b101.", "true"},
        {"The rate is ％-٥.", "The rate is 5.", "true"},
        {"The value is 1.", "The value is 1e3.", "true"},
        {"The value is 3.", "The value is 1e3.", "true"},
        {"The value is 1.", "The value is 1/2.", "true"},
        {"The value is 2.", "The value is 1/2.", "true"}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var result = score(pair[0], List.of(pair[1]), 0.5);
      var expected = Boolean.parseBoolean(pair[2])
          ? List.of(new CitationScorer.ScoredMatch(0, pair[0], 0, "doc0", 0.976f)) : List.of();
      assertEquals(expected, result.matches(), java.util.Arrays.toString(pair));
      assertEquals(1, result.sentencesScored());
    }));
  }

  @Test
  void retainsNx2DecidedFormsAndUnrelatedPassageNotation() {
    String[][] pairs = {
        {"The date is 21/10/2026.", "The date is 21/10/2026."},
        {"The date is 21/10/2026.", "The parts are 21, 10 and 2026."},
        {"The date is 2026-10-21.", "The parts are 2026, 10 and 21."},
        {"The date is 21.10.2026.", "The parts are 21, 10 and 2026."},
        {"The date is ٢٠٢٦/١٠/٢١.", "The parts are 2026, 10 and 21."},
        {"The interval is 5kg-10kg.", "The interval is 5kg-10kg."},
        {"The interval is 5kg-10kg.", "The values are 5 and 10."},
        {"The interval is 5–10 kg.", "The values are 5 and 10."},
        {"The interval is 5°-10°.", "The values are 5 and 10."},
        {"The price is $5.", "The price is $5."},
        {"The price is $5.", "The value is 5."},
        {"The price is 5 €.", "The value is 5."},
        {"The price is € 5.", "The value is 5."},
        {"The price is -$5.", "The value is -5."},
        {"The price is $-5.", "The value is -5."},
        {"The time is 1:30.", "The time is 1:30."},
        {"The time is 1:30.", "The parts are 1 and 30."},
        {"The version is v1.2.3.", "The version is v1.2.3."},
        {"The version is v1.2.3.", "The parts are 1, 2 and 3."},
        {"The value is 1.2.3.", "The parts are 1, 2 and 3."},
        {"The area is 5m².", "The value is 5."},
        {"Opened in 1883.", "Opened in 1883; admission costs $5."},
        {"Opened in 1883.", "Opened in 1883; the value is 0x10."},
        {"Opened in 1883.", "The value is 0x10; opened in 1883."},
        {"Opened in 1883.", "Opened in 1883; the fraction is 3/4."},
        {"Opened in 1883.", "Opened in 1883; the rate is %-5."},
        {"Opened in 1883.", "Opened in 1883; the value is 1e3."},
        {"Opened in 1883.", "Opened in 1883; the value is --5."},
        {"The value is 5.", "The value is 0x10; a separate value is 5."},
        {"The value is 5.", "The rate is %-5; a separate value is 5."},
        {"The interval is 5 kg–10 kg.", "The values are 5 and 10."},
        {"The interval is 10 - 20.", "The values are 10 and 20."},
        {"The interval is 10– 20.", "The values are 10 and 20."},
        {"The interval is -5 - -3.", "The values are -5 and -3."},
        {"Opened in 1883.", "The value is 0x10,1883."},
        {"Opened in 1883.", "The value is 1883,0x10."},
        {"Opened in 1883.", "The value is 0x10 1883."},
        {"Opened in 1883.", "The value is 1883 0x10."},
        {"The date is 1/234/567.", "The parts are 1, 234 and 567."}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var result = score(pair[0], List.of(pair[1]), 0.5);
      assertEquals(List.of(new CitationScorer.ScoredMatch(0, pair[0], 0, "doc0", 0.976f)),
          result.matches(), java.util.Arrays.toString(pair));
      assertEquals(List.of(), score(pair[0], List.of(pair[1]), 0.99).matches());
      assertEquals(1, result.sentencesScored());
    }));
  }

  @Test
  void retainsNx2UnitOnlyExactMatches() {
    String[][] pairs = {
        {"The area is measured in m².", "Some passage."},
        {"Volume is measured in cm³.", "Some passage."}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var result = score(pair[0], List.of(pair[1]), 0.5);
      assertEquals(List.of(new CitationScorer.ScoredMatch(0, pair[0], 0, "doc0", 0.976f)),
          result.matches(), java.util.Arrays.toString(pair));
      assertEquals(List.of(), score(pair[0], List.of(pair[1]), 0.99).matches());
      assertEquals(1, result.sentencesScored());
    }));
  }

  @Test
  void rejectsLiteralIncompatiblePairs() {
    List<Pair> pairs = List.of(
        new Pair("Vespera Observatory opened in 2099.", "Vespera Observatory opened in 1883."),
        new Pair("Vespera Observatory opened in 1923.", "Vespera Observatory opened in 1883."),
        new Pair("The value is 99.", "The value is 2099."),
        new Pair("The value is 2099.", "The value is 99."),
        new Pair("The value is 1.5.", "The value is 15."),
        new Pair("The value is 1.50.", "The value is 1.5."),
        new Pair("The value is 1.500.", "The value is 1500."),
        new Pair("افتتح المرصد في ٢٠٩٩.", "Opened in 1883."),
        new Pair("The array is `[4]`.", "The array is empty."),
        new Pair("The array is [4, 7].", "The array contains 4."),
        new Pair("The equation is x = [4].", "The equation is unspecified."),
        new Pair("The constant is `2099`.", "The constant is 1883."),
        new Pair("Value is 2099 (a table cell).", "Value is 1883."));
    assertAll(pairs.stream().map(pair -> () -> {
      var result = score(pair.sentence(), List.of(pair.passage()), 0.5);
      assertEquals(List.of(), result.matches(), pair.toString());
      assertEquals(0, result.sentencesMatched(), pair.toString());
      assertEquals(1, result.sentencesScored(), "rejection is still a scored verdict: " + pair);
    }));
  }

  @Test
  void preservesLiteralCompatiblePairs() {
    List<Pair> pairs = List.of(
        new Pair("The value is -15.", "The value is 15."),
        new Pair("The value is 15.", "The value is -15."),
        new Pair("The value is 15%.", "The value is 15."),
        new Pair("The value is 15.", "The value is 15%."),
        new Pair("The value is 12,34.", "The value is 1234."),
        new Pair("The interval is 10-20.", "The endpoint is 10."),
        new Pair("The date is 2026-10-21.", "The parts are 21 and 2026."),
        new Pair("The value is −15.", "The value is -15."),
        new Pair("The value is 15 %.", "The value is 15%."),
        new Pair("The value is 0015 kg.", "The value is 15 m."),
        new Pair("The value is 1,500.", "The value is 1500."),
        new Pair("The value is 1 500.", "The value is 1500."),
        new Pair("The value is 1 500.", "The value is 1500."),
        new Pair("The value is 1 500.", "The value is 1500."),
        new Pair("The value is 1,500,000.", "The value is 1500000."),
        new Pair("The value is 12,34.", "The values are 12 and 34."),
        new Pair("The interval is 10-20.", "The endpoints are 10 and 20."),
        new Pair("The interval is 10–20.", "The endpoints are 10 and 20."),
        new Pair("The interval is -20 to -10.", "The values are -10 and -20."),
        new Pair("The date is 2026-10-21.", "The parts are 21, 10 and 2026."),
        new Pair("افتتح المرصد في ١٨٨٣.", "Opened in 1883."),
        new Pair("値は１５です。", "The value is 15."),
        new Pair("The engine owns the index [4].", "The engine owns the index."),
        new Pair("The engine owns the index [4][7].", "The engine owns the index."),
        new Pair("4. The engine owns the index.", "The engine owns the index."),
        new Pair("The array is `[4]`.", "The array contains 4."),
        new Pair("The equation is x = [4].", "The value is 4."));
    assertAll(pairs.stream().map(pair -> () -> {
      var result = score(pair.sentence(), List.of(pair.passage()), 0.5);
      assertEquals(1, result.matches().size(), pair.toString());
      assertEquals(0.976f, result.matches().getFirst().score(), 0.0, pair.toString());
      assertEquals(1, result.sentencesScored());
    }));
  }

  @Test
  void skipsFormerNx1ClaimsWithoutChangingScores() {
    List<Pair> pairs = List.of(
        new Pair("The voltage is .5.", "The voltage is 5."),
        new Pair("The voltage is -.5.", "The voltage is 5."),
        new Pair("The voltage is +.5%.", "The voltage is 5%."),
        new Pair("The voltage is .5.", "The voltage is -.5."),
        new Pair("The voltage is .5%.", "The voltage is .5."),
        new Pair("The voltage is 1e3.", "The values are 1 and 3."),
        new Pair("The voltage is 1/2.", "The values are 1 and 2."),
        new Pair("The voltage is --5.", "The voltage is -5."));
    assertAll(pairs.stream().map(pair -> () -> {
      var result = score(pair.sentence(), List.of(pair.passage()), 0.5);
      assertEquals(List.of(new CitationScorer.ScoredMatch(0, pair.sentence(), 0, "doc0", 0.976f)),
          result.matches(), pair.toString());
      assertEquals(1, result.sentencesScored(), pair.toString());
    }));
  }

  @Test
  void repairsExplicitNx1EquivalencesWithoutChangingScoresOrThresholds() {
    for (Pair pair : List.of(new Pair("The voltage is .5.", "The voltage is 0.5."),
        new Pair("The voltage is +.5%.", "The voltage is 0.5%."),
        new Pair("The voltage is +15.", "The voltage is 15."))) {
      var result = score(pair.sentence(), List.of(pair.passage()), 0.5);
      assertEquals(1, result.matches().size(), pair.toString());
      assertEquals(0.976f, result.matches().getFirst().score(), 0.0);
      assertEquals(List.of(), score(pair.sentence(), List.of(pair.passage()), 0.99).matches());
    }
  }

  @Test
  void reselectsCompatibleWindowAcrossSubBatches() {
    List<String> passages = new ArrayList<>();
    for (int i = 0; i < 20; i++) passages.add("Opened in 1883.");
    passages.add("alternative: Opened in 1923.");
    var result = score("Vespera Observatory opened in 1923.", passages, 0.7);
    assertEquals(1, result.matches().size());
    assertEquals(20, result.matches().getFirst().chunkIndex(), "choose the compatible window");
    assertEquals("doc20", result.matches().getFirst().parentDocId());
    assertEquals(0.8f, result.matches().getFirst().score(), 0.0);
    assertEquals(1, result.sentencesScored());
  }

  @Test
  void neverPromotesAnAlternativeBelowThreshold() {
    var result = score("Opened in 1923.",
        List.of("Opened in 1883.", "alternative: Opened in 1923."), 0.9);
    assertEquals(List.of(), result.matches(), "containment cannot grant a verdict");
    assertEquals(1, result.sentencesScored());
  }

  @Test
  void preservesNumberFreeCorpusAndThreshold() {
    for (String sentence : List.of("The engine owns the index.", "索引归引擎所有。",
        "索引归引擎所有 [4]。", "索引归引擎所有。 [4]",
        "Die Suche bleibt lokal.", "The constant is `value`.")) {
      var result = score(sentence, List.of("Some passage.", "alternative passage."), 0.9);
      assertEquals(List.of(new CitationScorer.ScoredMatch(0, sentence, 0, "doc0", 0.976f)),
          result.matches());
      assertEquals(1, result.sentencesMatched());
      assertEquals(1, result.sentencesScored());
      assertEquals(List.of(), score(sentence, List.of("alternative passage."), 0.9).matches());
    }
  }

  private CitationScorer.ScoringResult score(String sentence, List<String> passages, double threshold) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < passages.size(); i++) ids.add("doc" + i);
    return scorer.scoreAll(List.of(sentence), passages, ids, threshold, 0);
  }
}
