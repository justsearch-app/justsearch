/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 836 §1.3 — one sentence's sweep over many passages must be interruptible, and the winner
 * must be reported in the CALLER's index space, not the sub-batch's.
 *
 * <p>Measured motivation: with the whole sweep in one uninterruptible ONNX call, 400 windows under
 * a 2000 ms budget ran 12,763 ms, and 600 windows produced a single 49,770 ms call — long after the
 * Head's 5 s cap had abandoned the result.
 */
@DisplayName("CitationScorer — deadline-bounded sub-batching")
class CitationScorerSubBatchTest {

  /** Records the sub-batches it is handed and scores by position in the FULL passage list. */
  private static final class RecordingScorer implements CitationScorer.BatchScorer {
    private final List<Integer> subBatchSizes = new ArrayList<>();
    private final String winner;
    private int scored;

    RecordingScorer(String winner) {
      this.winner = winner;
    }

    @Override
    public List<Float> score(String[] subBatch) {
      subBatchSizes.add(subBatch.length);
      scored += subBatch.length;
      List<Float> out = new ArrayList<>(subBatch.length);
      for (String passage : subBatch) {
        out.add(winner.equals(passage) ? 0.99f : 0.10f);
      }
      return out;
    }
  }

  private static String[] passages(int count, int winnerAt) {
    String[] out = new String[count];
    for (int i = 0; i < count; i++) {
      out[i] = i == winnerAt ? "the winning passage" : "filler passage " + i;
    }
    return out;
  }

  @Test
  @DisplayName("the sweep is split into sub-batches rather than one call over every passage")
  void sweepIsSubBatched() throws Exception {
    RecordingScorer scorer = new RecordingScorer("the winning passage");

    CitationScorer.scoreSentence(passages(100, 3), Long.MAX_VALUE, _ -> true, scorer);

    assertTrue(
        scorer.subBatchSizes.size() > 1,
        "100 passages in a single call is exactly the uninterruptible case this prevents");
    for (int size : scorer.subBatchSizes) {
      assertTrue(
          size <= CitationScorer.SCORING_SUB_BATCH,
          "sub-batch of " + size + " exceeds the deadline-check granularity");
    }
    assertEquals(100, scorer.scored, "every passage is still scored when the budget allows");
  }

  @Test
  @DisplayName("the winner is reported in the caller's index space, not the sub-batch's")
  void winnerIndexIsGlobal() throws Exception {
    // A winner in the LAST sub-batch: a sub-batch-local index would report a small number here.
    RecordingScorer scorer = new RecordingScorer("the winning passage");

    CitationScorer.BestOf best =
        CitationScorer.scoreSentence(passages(100, 97), Long.MAX_VALUE, _ -> true, scorer);

    assertEquals(97, best.index(), "the offset must be added back to the sub-batch position");
    assertEquals(0.99, best.score(), 1e-6);
    assertTrue(best.complete());
  }

  @Test
  @DisplayName("an exhausted deadline stops the sweep and marks it incomplete")
  void deadlineStopsTheSweep() throws Exception {
    RecordingScorer scorer = new RecordingScorer("the winning passage");
    long alreadyPast = System.nanoTime() - 1;

    CitationScorer.BestOf best =
        CitationScorer.scoreSentence(passages(100, 97), alreadyPast, _ -> true, scorer);

    assertFalse(best.complete(), "a cut-short sweep must say so rather than report a best-of-prefix");
    assertEquals(
        CitationScorer.SCORING_SUB_BATCH,
        scorer.scored,
        "exactly one sub-batch runs before the deadline is first checked");
  }

  @Test
  @DisplayName("blank passages are skipped, never reported as the best match")
  void blankPassagesAreSkipped() throws Exception {
    String[] chunks = {"", "   ", ""};
    CitationScorer.BatchScorer allHigh =
        subBatch -> {
          List<Float> out = new ArrayList<>();
          for (int i = 0; i < subBatch.length; i++) {
            out.add(0.99f);
          }
          return out;
        };

    CitationScorer.BestOf best = CitationScorer.scoreSentence(chunks, Long.MAX_VALUE, _ -> true, allHigh);

    assertEquals(-1, best.index(), "a blank passage is unverifiable, not a match");
    assertTrue(best.complete());
  }
}
