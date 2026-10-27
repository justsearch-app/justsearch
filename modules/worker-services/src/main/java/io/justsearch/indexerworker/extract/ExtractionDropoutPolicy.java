/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/**
 * Pure, total classification of an extraction attempt's text as an <em>extraction dropout</em> —
 * "a tier ran and produced nothing a searcher could ever match" (tempdoc 790).
 *
 * <p>This is the trigger side of the dropout fallback chain. {@link ExtractionOutcomeClassifier}
 * already maps blank content to {@link ExtractionStatus#SUCCESS_EMPTY} for provenance; this policy
 * answers the routing question that status does not: <em>should another extraction tier run, and
 * if none can, is the indexed document an honest hole?</em>
 *
 * <h2>Where the threshold comes from</h2>
 *
 * <p>Measured (tempdoc 790 §Threshold measurement) on the paired OHR-Bench arms shipped under
 * {@code datasets/mixed/} — 1000 single-PDF-page documents extracted by Tika ({@code
 * ohr-bench-tika-pdf}) against the same 1000 documents' ground-truth text ({@code
 * ohr-bench-clean}):
 *
 * <ul>
 *   <li>126 Tika documents (12.6%) extract to zero characters; 110 of those have real ground-truth
 *       text (median 1156 chars) and are therefore genuinely recoverable, 16 are blank pages in
 *       ground truth too.
 *   <li><b>A character-count floor does not separate the classes.</b> Legitimate ground-truth
 *       documents exist at 5, 19, 22, 24, 28, 30 … characters, so the pre-existing 100-character
 *       {@code TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH} would misclassify 19/1000 legitimate
 *       documents as dropouts.
 *   <li>Counting <em>letters and digits</em> does separate them. Sweeping the threshold: at
 *       "fewer than 2 alphanumerics" the clean arm flags 35 documents and every one of them is
 *       genuinely empty (zero false positives), while the Tika arm flags 127 — the 126 empties
 *       plus one document whose entire extracted text is a single backslash. At "fewer than 3" the
 *       first legitimate document ({@code "$f 5$"}, 2 alphanumerics) is misclassified.
 * </ul>
 *
 * <p>{@link #MIN_USABLE_ALPHANUMERIC_CHARS} is therefore set to the largest measured value with a
 * zero false-positive rate against legitimate short content. Honest limit: the measurement corpus
 * is single PDF pages from an OCR benchmark; the threshold's generalization to arbitrary user
 * documents is an assumption, not a measurement.
 */
public final class ExtractionDropoutPolicy {

  /**
   * Minimum letters-or-digits an extraction must yield to count as usable text. Content below this
   * is a dropout. Measured, not guessed — see the class Javadoc.
   */
  public static final int MIN_USABLE_ALPHANUMERIC_CHARS = 2;

  /** What an extraction attempt produced, from the fallback chain's point of view. */
  public enum Dropout {
    /** Usable text — no fallback tier is warranted. */
    NONE,
    /** Nothing at all: null, empty, or whitespace-only. */
    EMPTY,
    /** Non-blank but below {@link #MIN_USABLE_ALPHANUMERIC_CHARS} letters-or-digits. */
    TRIVIAL;

    public boolean isDropout() {
      return this != NONE;
    }
  }

  private ExtractionDropoutPolicy() {}

  public static Dropout classify(String content) {
    if (content == null || content.isBlank()) {
      return Dropout.EMPTY;
    }
    return alphanumericCount(content, MIN_USABLE_ALPHANUMERIC_CHARS) < MIN_USABLE_ALPHANUMERIC_CHARS
        ? Dropout.TRIVIAL
        : Dropout.NONE;
  }

  public static boolean isDropout(String content) {
    return classify(content).isDropout();
  }

  /** Counts letters/digits, stopping once {@code limit} is reached (documents can be huge). */
  private static int alphanumericCount(String content, int limit) {
    int count = 0;
    for (int i = 0; i < content.length() && count < limit; ) {
      int codePoint = content.codePointAt(i);
      if (Character.isLetterOrDigit(codePoint)) {
        count++;
      }
      i += Character.charCount(codePoint);
    }
    return count;
  }
}
