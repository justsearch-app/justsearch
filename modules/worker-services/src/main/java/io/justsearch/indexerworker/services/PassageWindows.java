/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Prepares the passages a citation-matching pass will score: resolves each source's text (supplied
 * literal text, or a chunk looked up by {@code (parentDocId, chunkIndex)}), splits it into windows
 * the cross-encoder can actually ingest, and admits only as many windows as the deadline can pay
 * for. Tempdoc 836 §1.3 / §3.5.
 *
 * <p>This is the ONE preparation both producer paths in {@link CitationMatchOps} consume — the
 * cross-encoder branch and the embedding-cosine branch. That is deliberate: a fix applied to only
 * one branch is what leaves a path silently reverting to the wrong text (tempdoc 836 §1.1), and
 * with a single preparation site that half-fix is not constructible.
 *
 * <p><b>The index-space invariant.</b> A window is scored; a SOURCE is reported. {@link
 * Prepared#windowToSource()} is the back-map, and every reported {@code sourceIndex} must come
 * through it — never a window ordinal. This is the F-049 numbering contract applied to the new
 * ordinal space windowing introduces.
 */
final class PassageWindows {

  /**
   * Window size in characters. Derived from tempdoc 836 §9 P1c: on real prose the passage budget
   * that still fits the 512-token cross-encoder pair is 1712-1934 chars depending on the answer
   * sentence's length, so 1500 sits below the worst measured budget with headroom.
   */
  static final int WINDOW_CHARS = 1500;

  /**
   * Backtrack distance when looking for a whitespace boundary to cut on, so a window boundary
   * lands between words rather than mid-token where the text allows it.
   */
  private static final int BOUNDARY_BACKTRACK_CHARS = 200;

  /**
   * Measured unit cost of one (sentence x window) cross-encoder pair, in milliseconds (tempdoc 836
   * §9 P2a: 21.0 ms/pair at batch 1 rising to 26.3 ms/pair at batch 128 — essentially linear, so
   * batching buys nothing and a pair count is a time estimate).
   */
  static final double UNIT_COST_MS = 25.0;

  /** Minimum windows admitted, so a request is never reduced to scoring nothing at all. */
  private static final int MIN_ADMITTED_WINDOWS = 1;

  private PassageWindows() {}

  /**
   * The prepared scoring inputs.
   *
   * @param windowTexts flat list of window texts, in admission order
   * @param windowToSource back-map: {@code windowToSource[w]} is the position in the REQUEST
   *     arrays of the source window {@code w} came from
   * @param windowDocIds parent doc id of each window's source, parallel to {@code windowTexts}
   * @param suppliedBySource per source: true when its text came from the caller rather than a
   *     chunk lookup (drives the per-match {@code text_source} provenance field)
   * @param sourceCount number of sources in the request
   * @param windowsConsidered how many windows existed before admission control
   * @param windowsConsideredBySource per source: how many windows its text produced BEFORE
   *     admission control. The scored-per-source count is NOT stored beside it — it is derived
   *     from {@code windowToSource}, which is the admitted set, so the two counts cannot drift
   *     (tempdoc 836 S2S3-A.1: one fact, one authority)
   */
  record Prepared(
      List<String> windowTexts,
      int[] windowToSource,
      List<String> windowDocIds,
      boolean[] suppliedBySource,
      int sourceCount,
      int windowsConsidered,
      int[] windowsConsideredBySource) {

    /** True when admission control dropped windows to fit the budget. */
    boolean admissionTruncated() {
      return windowsConsidered > windowTexts.size();
    }

    /**
     * Maps a window ordinal back to the source position it must be reported as. Throws rather than
     * clamping: an out-of-range window ordinal means the caller lost the back-map, and reporting a
     * plausible-looking source index for it is exactly the F-049 failure this map exists to
     * prevent.
     */
    int sourceOf(int windowOrdinal) {
      if (windowOrdinal < 0 || windowOrdinal >= windowToSource.length) {
        throw new IndexOutOfBoundsException(
            "window ordinal " + windowOrdinal + " outside back-map of " + windowToSource.length);
      }
      return windowToSource[windowOrdinal];
    }

    /**
     * Windows source {@code i}'s text produced before admission control. Zero means the source had
     * no text at all (blank supply and a failed lookup) — which is NOT the same fact as "text
     * existed but the budget gave it no window" (tempdoc 836 S2S3-A.1).
     */
    int windowsConsideredAt(int sourceIndex) {
      return sourceIndex >= 0 && sourceIndex < windowsConsideredBySource.length
          ? windowsConsideredBySource[sourceIndex]
          : 0;
    }

    /**
     * Windows of source {@code i} that survived admission — derived from the back-map rather than
     * counted separately, so "which windows are scored" has exactly one authority.
     */
    int windowsScoredAt(int sourceIndex) {
      int n = 0;
      for (int s : windowToSource) {
        if (s == sourceIndex) {
          n++;
        }
      }
      return n;
    }

    /** True when source {@code i}'s text was supplied by the caller. */
    boolean suppliedAt(int sourceIndex) {
      return sourceIndex >= 0
          && sourceIndex < suppliedBySource.length
          && suppliedBySource[sourceIndex];
    }
  }

  /**
   * Resolves, windows, and admits.
   *
   * @param chunkDocIds request array: parent doc id per source
   * @param chunkIndices request array: chunk ordinal per source (the fallback lookup key)
   * @param passageTexts request array: literal text per source — either empty, or exactly as long
   *     as {@code chunkDocIds} (the length contract is enforced at the in-process port boundary, before this
   *     is called); a blank entry means "look this one up"
   * @param lookup resolves source position -> chunk text, or null when the lookup fails. Called
   *     ONLY for sources that supply no text — a supplied source must cost zero index reads
   * @param sentenceCount number of answer sentences that will be scored against these windows
   * @param deadlineMs the scoring budget the windows must fit inside (0 = unbounded)
   * @param answerText the answer being verified, used only as the lexical prefilter's query when
   *     admission control has to choose which windows to keep
   */
  static Prepared prepare(
      List<String> chunkDocIds,
      List<Integer> chunkIndices,
      List<String> passageTexts,
      IntFunction<String> lookup,
      int sentenceCount,
      long deadlineMs,
      String answerText) {

    int sourceCount = Math.min(chunkDocIds.size(), chunkIndices.size());
    boolean[] supplied = new boolean[sourceCount];
    List<String> sourceTexts = new ArrayList<>(sourceCount);

    for (int i = 0; i < sourceCount; i++) {
      String literal = i < passageTexts.size() ? passageTexts.get(i) : "";
      if (literal != null && !literal.isBlank()) {
        supplied[i] = true;
        sourceTexts.add(literal);
      } else {
        String looked = lookup.apply(i);
        sourceTexts.add(looked == null ? "" : looked);
      }
    }

    List<String> windowTexts = new ArrayList<>();
    List<Integer> backMap = new ArrayList<>();
    List<String> windowDocIds = new ArrayList<>();
    int[] consideredBySource = new int[sourceCount];
    for (int i = 0; i < sourceCount; i++) {
      String text = sourceTexts.get(i);
      if (text.isBlank()) {
        // A source with no text still occupies its request position; it simply contributes no
        // window. It is reported as unverifiable (no match), never re-pointed at another source.
        continue;
      }
      for (String window : split(text)) {
        windowTexts.add(window);
        backMap.add(i);
        windowDocIds.add(chunkDocIds.get(i));
        consideredBySource[i]++;
      }
    }

    int considered = windowTexts.size();
    int cap = admissionCap(sentenceCount, deadlineMs);
    if (considered > cap) {
      int[] keep = selectWindows(windowTexts, backMap, answerText, cap);
      List<String> keptTexts = new ArrayList<>(keep.length);
      List<Integer> keptMap = new ArrayList<>(keep.length);
      List<String> keptDocIds = new ArrayList<>(keep.length);
      for (int w : keep) {
        keptTexts.add(windowTexts.get(w));
        keptMap.add(backMap.get(w));
        keptDocIds.add(windowDocIds.get(w));
      }
      windowTexts = keptTexts;
      backMap = keptMap;
      windowDocIds = keptDocIds;
    }

    int[] backMapArray = new int[backMap.size()];
    for (int w = 0; w < backMap.size(); w++) {
      backMapArray[w] = backMap.get(w);
    }
    return new Prepared(
        List.copyOf(windowTexts),
        backMapArray,
        List.copyOf(windowDocIds),
        supplied,
        sourceCount,
        considered,
        consideredBySource);
  }

  /**
   * How many windows the deadline can pay for, given the sentence count. Tempdoc 836 §3.4: cells
   * above the budget are completed by the Worker and DISCARDED by the Head (its cap is 5 s), so
   * the check has to happen before scoring starts, not only as a deadline during it.
   */
  static int admissionCap(int sentenceCount, long deadlineMs) {
    if (deadlineMs <= 0) {
      return Integer.MAX_VALUE;
    }
    int affordablePairs = (int) Math.max(1, Math.floor(deadlineMs / UNIT_COST_MS));
    int sentences = Math.max(1, sentenceCount);
    return Math.max(MIN_ADMITTED_WINDOWS, affordablePairs / sentences);
  }

  /**
   * Splits a passage into windows of at most {@link #WINDOW_CHARS}, preferring a whitespace
   * boundary near the cut. Windows do not overlap: they tile the passage, so every character —
   * including the tail — appears in exactly one window.
   */
  static List<String> split(String text) {
    List<String> windows = new ArrayList<>();
    int pos = 0;
    int len = text.length();
    while (pos < len) {
      int end = Math.min(len, pos + WINDOW_CHARS);
      if (end < len) {
        int boundary = end;
        int floor = Math.max(pos + 1, end - BOUNDARY_BACKTRACK_CHARS);
        while (boundary > floor && !Character.isWhitespace(text.charAt(boundary))) {
          boundary--;
        }
        if (boundary > floor) {
          end = boundary;
        }
      }
      String window = text.substring(pos, end);
      if (!window.isBlank()) {
        windows.add(window);
      }
      pos = end;
    }
    return windows;
  }

  /**
   * Chooses which windows to score when there are more than the budget allows.
   *
   * <p>Windows are ranked by cheap lexical overlap with the answer, then taken round-robin across
   * sources so every source that has any text keeps at least one window while slots remain — a
   * source silently losing all representation cannot be cited at all, which would read as "not
   * grounded" rather than "not scored".
   *
   * <p><b>Documented duplicate (tempdoc 836 §3.5).</b> The Head has its own lexical matcher
   * ({@code StreamingCitationMatcher.matchSentenceLexical}) and {@code worker-services} cannot
   * depend on it. This is a deliberate, named second implementation rather than a silent one. It
   * is safe as a fork because nothing lexical is ever REPORTED: this score is a candidate selector
   * that never leaves the Worker, so F-048's "one quantity, two producers" law is untouched — the
   * similarity a caller sees always comes from the cross-encoder or the cosine fallback.
   *
   * @return window ordinals to keep, ascending
   */
  private static int[] selectWindows(
      List<String> windowTexts, List<Integer> backMap, String answerText, int cap) {

    Set<String> queryTerms = tokenize(answerText);
    int windowCount = windowTexts.size();

    Integer[] ranked = new Integer[windowCount];
    double[] score = new double[windowCount];
    for (int w = 0; w < windowCount; w++) {
      ranked[w] = w;
      score[w] = lexicalOverlap(queryTerms, windowTexts.get(w));
    }
    // Ties resolve by window ordinal, so selection is deterministic for a given input.
    Arrays.sort(ranked, Comparator.<Integer>comparingDouble(w -> -score[w]).thenComparingInt(w -> w));

    // Group the ranked ordinals by source, preserving rank order within each source.
    int sourceCount = 0;
    for (int s : backMap) {
      sourceCount = Math.max(sourceCount, s + 1);
    }
    List<List<Integer>> perSource = new ArrayList<>(sourceCount);
    for (int s = 0; s < sourceCount; s++) {
      perSource.add(new ArrayList<>());
    }
    for (int w : ranked) {
      perSource.get(backMap.get(w)).add(w);
    }

    List<Integer> kept = new ArrayList<>(cap);
    int round = 0;
    while (kept.size() < cap) {
      boolean progressed = false;
      for (List<Integer> windowsOfSource : perSource) {
        if (round < windowsOfSource.size()) {
          progressed = true;
          kept.add(windowsOfSource.get(round));
          if (kept.size() == cap) {
            break;
          }
        }
      }
      if (!progressed) {
        break;
      }
      round++;
    }

    int[] result = kept.stream().mapToInt(Integer::intValue).sorted().toArray();
    return result;
  }

  /** Fraction of the window's terms that also occur in the answer. */
  private static double lexicalOverlap(Set<String> queryTerms, String window) {
    if (queryTerms.isEmpty()) {
      return 0.0;
    }
    Set<String> windowTerms = tokenize(window);
    if (windowTerms.isEmpty()) {
      return 0.0;
    }
    int hits = 0;
    for (String term : windowTerms) {
      if (queryTerms.contains(term)) {
        hits++;
      }
    }
    return (double) hits / windowTerms.size();
  }

  /**
   * Lowercased alphanumeric terms of length >= 3. Locale-invariant by construction (Hard Invariant
   * 6): no stopword list, no stemming, no per-language branch.
   */
  private static Set<String> tokenize(String text) {
    Set<String> terms = new HashSet<>();
    if (text == null || text.isBlank()) {
      return terms;
    }
    StringBuilder current = new StringBuilder();
    String lowered = text.toLowerCase(Locale.ROOT);
    for (int i = 0; i < lowered.length(); i++) {
      char c = lowered.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        current.append(c);
      } else {
        if (current.length() >= 3) {
          terms.add(current.toString());
        }
        current.setLength(0);
      }
    }
    if (current.length() >= 3) {
      terms.add(current.toString());
    }
    return terms;
  }
}
