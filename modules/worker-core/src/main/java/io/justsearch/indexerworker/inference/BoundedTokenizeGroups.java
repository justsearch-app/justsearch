/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Contiguous input ranges for native tokenizer calls with bounded materialized text. */
public final class BoundedTokenizeGroups {
  /**
   * Native {@code batchEncode} materializes complete token sequences before Java truncation.
   * Tempdoc 686's full-corpus crash established this character budget for one call; a single
   * larger document must remain a group of its own.
   */
  public static final long DEFAULT_CHAR_BUDGET = 512_000L;

  private BoundedTokenizeGroups() {}

  public record Range(int startInclusive, int endExclusive) {
    public Range {
      if (startInclusive < 0 || endExclusive <= startInclusive) {
        throw new IllegalArgumentException("Tokenize group must be nonempty and ordered");
      }
    }
  }

  /** Returns ordered ranges; only a single oversized text may exceed {@code charBudget}. */
  public static List<Range> ranges(List<String> texts, long charBudget) {
    Objects.requireNonNull(texts, "texts");
    if (charBudget <= 0) throw new IllegalArgumentException("charBudget must be positive");
    List<Range> ranges = new ArrayList<>();
    int start = 0;
    while (start < texts.size()) {
      int end = start;
      long chars = 0;
      while (end < texts.size()) {
        int nextChars = Objects.requireNonNull(texts.get(end), "text").length();
        if (end > start && nextChars > charBudget - chars) break;
        chars += nextChars;
        end++;
      }
      ranges.add(new Range(start, end));
      start = end;
    }
    return List.copyOf(ranges);
  }
}
