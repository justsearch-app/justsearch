/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.util.regex.Pattern;

/** PDF text repair, applied only within a completed text element or OCR page. */
final class PdfTextNormalization {
  private static final Pattern LINE_END_HYPHEN =
      Pattern.compile("(?<=\\p{L})[-\u2010][ \\t]*(?:\\r\\n|\\r|\\n)[ \\t]*(?=\\p{Ll})");

  private PdfTextNormalization() {}

  static String joinLineEndHyphens(String text) {
    return LINE_END_HYPHEN.matcher(text).replaceAll("");
  }
}
