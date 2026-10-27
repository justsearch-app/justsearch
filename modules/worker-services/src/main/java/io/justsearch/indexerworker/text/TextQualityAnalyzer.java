/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.text;

/**
 * Utility class for analyzing text quality.
 *
 * <p>Used to determine if OCR/Tika extraction produced garbage text
 * that should trigger VDU (Vision Document Understanding) processing.
 */
public final class TextQualityAnalyzer {

  /** Minimum text length to consider extraction "good". */
  public static final int MIN_GOOD_TEXT_LENGTH = 100;

  /** Minimum alphanumeric ratio to consider text "good". */
  public static final double MIN_ALPHANUMERIC_RATIO = 0.3;

  private TextQualityAnalyzer() {
    // Utility class - no instantiation
  }

  /**
   * Determines if the given text appears to be garbage extraction output.
   *
   * <p>Garbage detection heuristics:
   * <ul>
   *   <li>Null or very short text (< 100 chars) = garbage</li>
   *   <li>Low alphanumeric ratio (< 30%) = garbage</li>
   *   <li>Contains font encoding markers like "(cid:" = garbage</li>
   *   <li>Contains Unicode replacement character U+FFFD = garbage</li>
   * </ul>
   *
   * @param text the extracted text to analyze
   * @return true if the text appears to be garbage, false if it's usable
   */
  public static boolean isGarbageText(String text) {
    if (text == null || text.length() < MIN_GOOD_TEXT_LENGTH) {
      return true;  // Too short = probably failed
    }

    // Check alphanumeric ratio
    double ratio = getAlphanumericRatio(text);
    if (ratio < MIN_ALPHANUMERIC_RATIO) {
      return true;  // Too many garbage characters
    }

    // Check for common OCR failure patterns
    if (text.contains("(cid:") || text.contains("\uFFFD")) {
      return true;  // Font encoding issues
    }

    return false;
  }

  /**
   * Returns the alphanumeric ratio of the given text.
   *
   * @param text the text to analyze
   * @return ratio of letter/digit code points to all code points (0.0 to 1.0), or 0.0 for null/empty
   */
  public static double getAlphanumericRatio(String text) {
    if (text == null || text.isEmpty()) {
      return 0.0;
    }
    int alphaNum = 0;
    int total = 0;
    for (int i = 0; i < text.length(); ) {
      int codePoint = text.codePointAt(i);
      if (Character.isLetterOrDigit(codePoint)) {
        alphaNum++;
      }
      total++;
      i += Character.charCount(codePoint);
    }
    return (double) alphaNum / total;
  }

  /**
   * Compute a numeric quality score for extracted text.
   *
   * <p>Returns a value between 0.0 (garbage) and 1.0 (excellent). Used for extraction quality
   * routing and provenance tracking.
   *
   * @param text the extracted text
   * @param pageCount number of pages in the document (0 if unknown)
   * @return quality score between 0.0 and 1.0
   */
  public static double computeQualityScore(String text, int pageCount) {
    // Hard failures — same as isGarbageText()
    if (text == null || text.length() < MIN_GOOD_TEXT_LENGTH) {
      return 0.0;
    }

    double alphanum = getAlphanumericRatio(text);
    if (alphanum < MIN_ALPHANUMERIC_RATIO) {
      return 0.0;
    }
    if (text.contains("(cid:") || text.contains("\uFFFD")) {
      return 0.0;
    }

    double score = 1.0;

    // Signal: text density per page (sparse pages = likely image-only)
    if (pageCount > 0) {
      double charsPerPage = (double) text.length() / pageCount;
      if (charsPerPage < 50) {
        score -= 0.4;
      } else if (charsPerPage < 200) {
        score -= 0.2;
      }
    }

    // Signal: alphanumeric quality gradient (scale from 0.3 to 0.5)
    if (alphanum < 0.5) {
      score *= alphanum / 0.5;
    }

    return Math.max(0.0, Math.min(1.0, score));
  }

  /**
   * Convenience overload without page count information.
   *
   * @param text the extracted text
   * @return quality score between 0.0 and 1.0
   */
  public static double computeQualityScore(String text) {
    return computeQualityScore(text, 0);
  }
}
