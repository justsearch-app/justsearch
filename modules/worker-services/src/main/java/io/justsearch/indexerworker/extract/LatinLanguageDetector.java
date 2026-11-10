/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.util.regex.Pattern;
import org.apache.tika.langdetect.optimaize.OptimaizeLangDetector;
import org.apache.tika.language.detect.LanguageConfidence;

/** Bundled statistical detection for Latin prose, confined to the extraction owner. */
public final class LatinLanguageDetector {
  private static final int MIN_LATIN_LETTERS = 50;
  private static final int MIN_PROSE_WORDS = 8;
  private static final Pattern PROSE_WORD = Pattern.compile("\\p{L}{2,}");

  private LatinLanguageDetector() {}

  /** Returns a language only when prose has sufficient evidence and high confidence. */
  public static String detect(String sample) {
    // Code and structured data can look like confident English to an n-gram model. Only
    // prose lines contribute evidence; this is syntax filtering, independent of language.
    StringBuilder prose = new StringBuilder();
    for (String line : sample.split("\\R")) {
      long syntax = line.chars().filter(ch -> "{}[]=;<>".indexOf(ch) >= 0).count();
      if (syntax < 2) {
        prose.append(line).append(' ');
      }
    }
    String text = prose.toString();
    long letters = text.chars().filter(Character::isLetter).count();
    long words = PROSE_WORD.matcher(text).results().count();
    if (letters < MIN_LATIN_LETTERS || words < MIN_PROSE_WORDS) {
      return null;
    }
    // Tika shares immutable models internally. Each call owns its mutable input buffer,
    // so indexing, VDU and replay can detect concurrently without retaining worker threads.
    var detector = new OptimaizeLangDetector(4096).loadModels();
    var result = detector.detect(text);
    return result.getConfidence() == LanguageConfidence.HIGH ? result.getLanguage() : null;
  }

}
