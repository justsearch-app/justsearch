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
  void rejectsLiteralIncompatiblePairs() {
    List<Pair> pairs = List.of(
        new Pair("Vespera Observatory opened in 2099.", "Vespera Observatory opened in 1883."),
        new Pair("Vespera Observatory opened in 1923.", "Vespera Observatory opened in 1883."),
        new Pair("The value is 99.", "The value is 2099."),
        new Pair("The value is 2099.", "The value is 99."),
        new Pair("The value is 1.5.", "The value is 15."),
        new Pair("The value is -15.", "The value is 15."),
        new Pair("The value is 15.", "The value is -15."),
        new Pair("The value is 15%.", "The value is 15."),
        new Pair("The value is 15.", "The value is 15%."),
        new Pair("The value is 1.50.", "The value is 1.5."),
        new Pair("The value is 12,34.", "The value is 1234."),
        new Pair("The value is 1.500.", "The value is 1500."),
        new Pair("The interval is 10-20.", "The endpoint is 10."),
        new Pair("The date is 2026-10-21.", "The parts are 21 and 2026."),
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
  void repairsLeadingDecimalsAndWithholdsUnsupportedNotation() {
    List<Pair> pairs = List.of(
        new Pair("The voltage is .5.", "The voltage is 5."),
        new Pair("The voltage is -.5.", "The voltage is 5."),
        new Pair("The voltage is +.5%.", "The voltage is 5%."),
        new Pair("The voltage is .5.", "The voltage is -.5."),
        new Pair("The voltage is .5%.", "The voltage is .5."),
        new Pair("The voltage is 1e3.", "The values are 1 and 3."),
        new Pair("The voltage is 1/2.", "The values are 1 and 2."),
        new Pair("The voltage is --5.", "The voltage is -5."),
        new Pair("The voltage is ١٫٥.", "The values are 1 and 5."));
    assertAll(pairs.stream().map(pair -> () -> {
      var result = score(pair.sentence(), List.of(pair.passage()), 0.5);
      assertEquals(List.of(), result.matches(), pair.toString());
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
