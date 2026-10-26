/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.extract.ExtractionDropoutPolicy.Dropout;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The threshold classifier's law (tempdoc 790 item 1). Every "legitimate short document" case below
 * is a verbatim string from {@code datasets/mixed/ohr-bench-clean/corpus.jsonl}, and every dropout
 * case is a verbatim string from {@code datasets/mixed/ohr-bench-tika-pdf/corpus.jsonl} — the two
 * arms the threshold was measured on. They are the reason the constant is 2 and not, say, 100.
 */
@DisplayName("Extraction dropout policy (measured threshold)")
final class ExtractionDropoutPolicyTest {

  @Test
  @DisplayName("empty extraction is a dropout")
  void emptyIsDropout() {
    assertEquals(Dropout.EMPTY, ExtractionDropoutPolicy.classify(null));
    assertEquals(Dropout.EMPTY, ExtractionDropoutPolicy.classify(""));
    assertEquals(Dropout.EMPTY, ExtractionDropoutPolicy.classify("   \r\n\t  "));
  }

  @Test
  @DisplayName("non-blank but wordless extraction is a trivial dropout")
  void wordlessIsTrivialDropout() {
    // ohr-bench-tika-pdf: academic/dude_72a8558a002eb1c2875c38388854b4de_p1 extracts to exactly
    // this single backslash, while its ground truth carries 24 characters of real content.
    assertEquals(Dropout.TRIVIAL, ExtractionDropoutPolicy.classify("\\"));
    assertEquals(Dropout.TRIVIAL, ExtractionDropoutPolicy.classify("---"));
    assertEquals(Dropout.TRIVIAL, ExtractionDropoutPolicy.classify("\uFFFD \uFFFD"));
    assertEquals(Dropout.TRIVIAL, ExtractionDropoutPolicy.classify("- · -"));
  }

  @Test
  @DisplayName("legitimately short ground-truth documents are NOT dropouts")
  void legitimateShortDocumentsSurvive() {
    // Verbatim from ohr-bench-clean — the shortest legitimate documents in the corpus. A
    // character-count floor would misclassify all of these.
    for (String legit :
        new String[] {
          "$f 5$",
          "Google Confidential",
          "2. RULES FOR RECORDING",
          "<smiles>C1CCCC1</smiles>",
          "ASSESSED FOR RETENTION 07/02",
          "Welcome to\nNottingham Trent University (NTU)",
        }) {
      assertEquals(Dropout.NONE, ExtractionDropoutPolicy.classify(legit), "misclassified: " + legit);
      assertFalse(ExtractionDropoutPolicy.isDropout(legit));
    }
  }

  @Test
  @DisplayName("the shortest legitimate document sits exactly at the measured boundary")
  void boundaryIsWhereTheMeasurementPutIt() {
    // "$f 5$" carries 2 alphanumerics — the corpus minimum for legitimate content, and therefore
    // the value the threshold must admit. One alphanumeric fewer is a dropout.
    assertEquals(2, ExtractionDropoutPolicy.MIN_USABLE_ALPHANUMERIC_CHARS);
    assertEquals(Dropout.NONE, ExtractionDropoutPolicy.classify("$f 5$"));
    assertEquals(Dropout.TRIVIAL, ExtractionDropoutPolicy.classify("$f$"));
  }

  @Test
  @DisplayName("healthy text never fires the dropout path")
  void healthyTextIsNotDropout() {
    assertFalse(ExtractionDropoutPolicy.isDropout("The quick brown fox jumps over the lazy dog."));
    assertFalse(ExtractionDropoutPolicy.isDropout("Readable OCR text ".repeat(200)));
  }

  @Test
  @DisplayName("the dropout policy is strictly narrower than the pre-existing quality floor")
  void narrowerThanQualityFloor() {
    // Documented relationship to the gate this work broadens: everything the dropout policy flags
    // also scores 0.0 on TextQualityAnalyzer (so the OCR tier was already reachable), but the
    // reverse does not hold — a 99-character legitimate document scores 0.0 and is NOT a dropout.
    String shortLegit = "Appendix A\n\n\nName of Investor\n\nAmount of Investment";
    assertEquals(0.0d, TextQualityAnalyzer.computeQualityScore(shortLegit));
    assertFalse(ExtractionDropoutPolicy.isDropout(shortLegit));
    assertTrue(ExtractionDropoutPolicy.isDropout(""));
    assertEquals(0.0d, TextQualityAnalyzer.computeQualityScore(""));
  }

  @org.junit.jupiter.params.ParameterizedTest(
      name = "supplementaryThresholdCountsOccurrencesNotUtf16UnitsOrDistinctValues({index})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"\uD840\uDC00", "\uD801\uDC00", "\uD835\uDFCE"})
  void supplementaryThresholdCountsOccurrencesNotUtf16UnitsOrDistinctValues(String value) {
    assertEquals(2, value.length());
    assertEquals(Dropout.TRIVIAL, ExtractionDropoutPolicy.classify(value));
    assertEquals(Dropout.NONE, ExtractionDropoutPolicy.classify(value + value));
    assertEquals(Dropout.NONE, ExtractionDropoutPolicy.classify("A" + value));
    assertEquals(Dropout.NONE, ExtractionDropoutPolicy.classify(value + "7"));
    assertFalse(ExtractionDropoutPolicy.isDropout(value + value));
  }

  @Test
  void surrogateAndCategoryBoundariesRetainTheExistingRules() {
    for (String text : new String[] {"\uDC00\uD840", "\uD840!\uDC00", "\uD840", "\uDC00",
        "\u00A0", "\u0301\u200D\uFE0F", "²Ⅷ①", "\uD83D\uDE00"}) {
      assertEquals(Dropout.TRIVIAL, ExtractionDropoutPolicy.classify(text));
    }
    assertEquals(Dropout.EMPTY, ExtractionDropoutPolicy.classify("\u2003\t\n"));
    assertEquals(Dropout.TRIVIAL, ExtractionDropoutPolicy.classify("1\uFE0F\u20E3"));
    assertEquals(Dropout.NONE, ExtractionDropoutPolicy.classify("1\uFE0F\u20E32\uFE0F\u20E3"));
    assertEquals(Dropout.NONE, ExtractionDropoutPolicy.classify("é漢가９"));
  }

  @Test
  void validPairsBesideLoneUnitsStillReachTheOccurrenceThreshold() {
    assertEquals(Dropout.TRIVIAL, ExtractionDropoutPolicy.classify("\uD840\uDC00\uD840!\uDC00"));
    assertEquals(Dropout.NONE, ExtractionDropoutPolicy.classify("A\uD840\uDC00\uD840!\uDC00"));
  }

  @Test
  void longSuffixAndMalformedInputsTerminate() {
    String suffix = "\uD840!\uDC00".repeat(300_000);
    assertEquals(Dropout.NONE, ExtractionDropoutPolicy.classify("\uD840\uDC00\uD840\uDC00" + suffix));
    assertEquals(Dropout.TRIVIAL, ExtractionDropoutPolicy.classify(suffix));
    assertEquals(Dropout.NONE, ExtractionDropoutPolicy.classify(suffix + "\uD840\uDC00\uD840\uDC00"));
  }
}
