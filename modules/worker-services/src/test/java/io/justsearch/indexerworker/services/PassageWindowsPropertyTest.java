/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property test for the window→source back-map (tempdoc 836 §5.4).
 *
 * <p>Windowing introduces a SECOND ordinal space beside the one the wire reports, which is the
 * exact shape of F-049. The law: a window is scored, a SOURCE is reported — so for every prepared
 * request, every window ordinal maps to a position that exists in the request arrays, and the doc
 * id at that position is the one that window's text came from. Randomized over source counts,
 * passage lengths, supplied/looked-up mixes, and budgets tight enough to trigger admission control.
 */
@DisplayName("PassageWindows — back-map property (F-049 numbering contract)")
class PassageWindowsPropertyTest {

  private static final int TRIALS = 300;

  @ParameterizedTest(name = "seed={0}")
  @ValueSource(longs = {1L, 7L, 42L, 1337L, 20260814L})
  @DisplayName("every returned window maps to a real source, and to the one its text came from")
  void backMapIsTotalAndCorrect(long seed) {
    Random rnd = new Random(seed);

    for (int trial = 0; trial < TRIALS; trial++) {
      int sourceCount = 1 + rnd.nextInt(6);
      List<String> docIds = new ArrayList<>(sourceCount);
      List<Integer> chunkIndices = new ArrayList<>(sourceCount);
      List<String> passageTexts = new ArrayList<>(sourceCount);
      List<String> lookupTexts = new ArrayList<>(sourceCount);

      for (int i = 0; i < sourceCount; i++) {
        docIds.add("doc-" + i);
        chunkIndices.add(rnd.nextInt(50));
        // A per-source marker that survives windowing, so a window's text identifies its source.
        String marker = "src" + i + "marker";
        String body = marker + " " + filler(rnd.nextInt(6000), marker, rnd);
        boolean supplies = rnd.nextBoolean();
        boolean lookupFails = !supplies && rnd.nextInt(4) == 0;
        passageTexts.add(supplies ? body : "");
        lookupTexts.add(supplies ? "" : (lookupFails ? null : body));
      }

      int sentenceCount = 1 + rnd.nextInt(40);
      long deadlineMs = rnd.nextBoolean() ? 0 : 200L * (1 + rnd.nextInt(15));

      var prepared =
          PassageWindows.prepare(
              docIds,
              chunkIndices,
              passageTexts,
              i -> lookupTexts.get(i),
              sentenceCount,
              deadlineMs,
              "an answer sentence about src0marker and pagination");

      assertEquals(
          prepared.windowTexts().size(),
          prepared.windowDocIds().size(),
          "texts and doc ids stay parallel");

      for (int w = 0; w < prepared.windowTexts().size(); w++) {
        int source = prepared.sourceOf(w);

        assertTrue(
            source >= 0 && source < sourceCount,
            "window " + w + " reported source " + source + " outside 0.." + (sourceCount - 1));

        assertEquals(
            docIds.get(source),
            prepared.windowDocIds().get(w),
            "parent_doc_id must equal chunk_doc_ids[source_index]");

        assertTrue(
            prepared.windowTexts().get(w).contains("src" + source + "marker")
                || !prepared.windowTexts().get(w).contains("marker"),
            "window " + w + " carries text from a source other than the one it reports");

        assertEquals(
            passageTexts.get(source) != null && !passageTexts.get(source).isBlank(),
            prepared.suppliedAt(source),
            "text_source provenance must follow the source that actually supplied text");
      }

      if (deadlineMs > 0) {
        assertTrue(
            prepared.windowTexts().size()
                <= PassageWindows.admissionCap(sentenceCount, deadlineMs),
            "admission control must not exceed the budget it computed");
      }
      assertTrue(
          prepared.windowTexts().size() <= prepared.windowsConsidered(),
          "admission can only drop windows, never invent them");
      int consideredBySource = 0;
      for (int source = 0; source < sourceCount; source++) {
        int count = prepared.windowsConsideredAt(source);
        assertTrue(count >= 0, "each source's considered-window count must be nonnegative");
        consideredBySource += count;
      }
      assertEquals(
          prepared.windowsConsidered(),
          consideredBySource,
          "per-source counts must conserve all windows considered before admission");
    }
  }

  /**
   * Builds body text of roughly {@code chars} characters, sprinkling the source's marker so any
   * window cut from it still identifies its source.
   */
  private static String filler(int chars, String marker, Random rnd) {
    String[] words = {"pagination", "cursor", "analyzer", "segment", "budget", "window", marker};
    StringBuilder sb = new StringBuilder(chars + 32);
    while (sb.length() < chars) {
      sb.append(words[rnd.nextInt(words.length)]).append(' ');
    }
    return sb.toString();
  }
}
