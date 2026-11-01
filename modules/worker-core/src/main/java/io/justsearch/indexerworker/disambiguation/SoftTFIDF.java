/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

/**
 * SoftTFIDF string similarity for entity disambiguation.
 *
 * <p>Based on Cohen, Ravikumar &amp; Fienberg (KDD 2003): combines TF-IDF token weighting
 * with soft Jaro-Winkler token matching. Tokens that are near-matches (JW &ge; theta)
 * contribute weighted scores rather than requiring exact equality.
 *
 * <p>Lifecycle: construct with a corpus of normalized mentions to build IDF weights, then
 * call {@link #score} for pairwise comparison.
 */
public final class SoftTFIDF {
  private final double theta;
  private final Map<String, Integer> documentFrequency = new HashMap<>();
  private int corpusSize;
  private static final JaroWinklerSimilarity JW = new JaroWinklerSimilarity();

  /**
   * Trains IDF dictionary from all known normalized entity mentions.
   *
   * @param normalizedMentions each mention is one "document" in the IDF corpus
   * @param theta inner Jaro-Winkler threshold (tokens with JW &lt; theta are not soft-matched)
   */
  public SoftTFIDF(Collection<String> normalizedMentions, double theta) {
    this.theta = theta;
    for (String mention : normalizedMentions) addMention(mention);
  }

  /** Extends the training corpus between batches without rereading earlier mentions. */
  void addMention(String mention) {
    corpusSize++;
    for (String token : new java.util.HashSet<>(EntityNormalizer.tokenize(mention))) {
      documentFrequency.merge(token, 1, Integer::sum);
    }
  }

  /**
   * Computes SoftTFIDF similarity between two normalized entity forms.
   *
   * @param a first normalized entity form
   * @param b second normalized entity form
   * @return similarity score in [0.0, 1.0]
   */
  public double score(String a, String b) {
    if (a == null || b == null || a.isBlank() || b.isBlank()) {
      return 0.0;
    }

    List<String> tokensA = EntityNormalizer.tokenize(a);
    List<String> tokensB = EntityNormalizer.tokenize(b);
    if (tokensA.isEmpty() || tokensB.isEmpty()) {
      return 0.0;
    }

    // Compute TF-IDF weight vectors
    double[] weightsA = computeWeights(tokensA);
    double[] weightsB = computeWeights(tokensB);

    // L2-normalize
    l2Normalize(weightsA);
    l2Normalize(weightsB);

    // Build soft-match candidates: all (i, j) pairs where JW(tokA[i], tokB[j]) >= theta
    List<SoftMatch> candidates = new ArrayList<>();
    for (int i = 0; i < tokensA.size(); i++) {
      for (int j = 0; j < tokensB.size(); j++) {
        double jw = JW.apply(tokensA.get(i), tokensB.get(j));
        if (jw >= theta) {
          candidates.add(new SoftMatch(i, j, weightsA[i] * weightsB[j] * jw));
        }
      }
    }

    // Greedy best-first assignment (prevent token reuse)
    candidates.sort((x, y) -> Double.compare(y.score, x.score));
    boolean[] usedA = new boolean[tokensA.size()];
    boolean[] usedB = new boolean[tokensB.size()];
    double total = 0.0;

    for (SoftMatch m : candidates) {
      if (!usedA[m.i] && !usedB[m.j]) {
        usedA[m.i] = true;
        usedB[m.j] = true;
        total += m.score;
      }
    }

    return Math.min(total, 1.0);
  }

  /** IDF for a token. Unknown tokens get max IDF (rare = strong signal). */
  private double idf(String token) {
    // +1 ensures non-zero IDF even when corpusSize=1 (empty corpus edge case)
    int size = Math.max(corpusSize, 1);
    Integer frequency = documentFrequency.get(token);
    return frequency == null ? Math.log((double) size + 1) : Math.log(1.0 + (double) size / frequency);
  }

  /** Compute TF-IDF weights for a token list. TF = log(tf + 1), sublinear. */
  private double[] computeWeights(List<String> tokens) {
    // Count term frequencies within this mention
    Map<String, Integer> tf = new HashMap<>();
    for (String t : tokens) {
      tf.merge(t, 1, Integer::sum);
    }

    double[] weights = new double[tokens.size()];
    for (int i = 0; i < tokens.size(); i++) {
      weights[i] = Math.log(tf.get(tokens.get(i)) + 1.0) * idf(tokens.get(i));
    }
    return weights;
  }

  /** L2-normalize a weight vector in-place. */
  private static void l2Normalize(double[] v) {
    double norm = 0.0;
    for (double w : v) {
      norm += w * w;
    }
    if (norm > 0.0) {
      norm = Math.sqrt(norm);
      for (int i = 0; i < v.length; i++) {
        v[i] /= norm;
      }
    }
  }

  private record SoftMatch(int i, int j, double score) {}
}
