/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.util.regex.Pattern;
import org.apache.tika.langdetect.optimaize.OptimaizeLangDetector;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;

/** Bundled statistical detection for Latin prose, confined to the extraction owner. */
public final class LatinLanguageDetector {
  private static final int MIN_LATIN_LETTERS = 50;
  /** Word-like tokens on prose lines: a couple of sentences, more than a heading or a list. */
  private static final int MIN_PROSE_WORDS = 12;
  /** A prose line has at least this many tokens ... */
  private static final int MIN_LINE_TOKENS = 4;
  /** ... of which at least this share are words. */
  private static final double MIN_LINE_WORD_SHARE = 0.75;
  /** Below this share of the sample's letters on prose lines, data dominates the window. */
  private static final double MIN_PROSE_LETTER_SHARE = 0.25;
  /** Detector probability required for a label (Tika's own HIGH confidence starts at 0.9). */
  private static final float MIN_PROBABILITY = 0.95f;
  /** Letters (with marks) and inner apostrophes or hyphens, optionally wrapped in punctuation. */
  private static final Pattern WORD = Pattern.compile(
      "[\\p{Ps}\\p{Pi}\"'¿¡]?[\\p{L}\\p{M}]+(?:['’-][\\p{L}\\p{M}]+)*[\\p{Pe}\\p{Pf}\\p{Po}\\p{Pd}]{0,3}");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private LatinLanguageDetector() {}

  /**
   * One detector per JVM, created on first use. The language models (about 96 MiB of heap) are
   * static inside Tika and shared by every instance; the instance itself keeps a mutable text
   * buffer, so calls are serialized on it.
   */
  private static final class Shared {
    private static final LanguageDetector DETECTOR = new OptimaizeLangDetector(4096).loadModels();

    private Shared() {}

    static synchronized LanguageResult detect(CharSequence text) {
      DETECTOR.reset();
      DETECTOR.addText(text);
      return DETECTOR.detect();
    }
  }

  /** Returns a language only when prose has sufficient evidence and high confidence. */
  public static String detect(String sample) {
    // Code and structured data can look like confident English to an n-gram model. Only prose
    // lines (several tokens, mostly words) count as evidence; this is syntax filtering,
    // independent of language.
    StringBuilder prose = new StringBuilder();
    int words = 0;
    for (String line : sample.split("\\R")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      String[] tokens = WHITESPACE.split(trimmed);
      int lineWords = 0;
      for (String token : tokens) {
        if (WORD.matcher(token).matches()) {
          lineWords++;
        }
      }
      if (tokens.length >= MIN_LINE_TOKENS && lineWords >= MIN_LINE_WORD_SHARE * tokens.length) {
        prose.append(trimmed).append('\n');
        words += lineWords;
      }
    }
    long proseLetters = prose.chars().filter(Character::isLetter).count();
    long sampleLetters = sample.chars().filter(Character::isLetter).count();
    if (words < MIN_PROSE_WORDS
        || proseLetters < MIN_LATIN_LETTERS
        || proseLetters < MIN_PROSE_LETTER_SHARE * sampleLetters) {
      return null;
    }
    LanguageResult result = Shared.detect(prose);
    return result.getRawScore() >= MIN_PROBABILITY && !result.getLanguage().isEmpty()
        ? result.getLanguage() : null;
  }
}
