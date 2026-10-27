/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maximal Marginal Relevance (MMR) selection for diversification.
 *
 * <p>Selects a diverse subset of candidates by balancing relevance vs novelty:
 * score = lambda * relevance - (1 - lambda) * maxSimToSelected
 *
 * <p>This implementation uses cosine similarity between candidate vectors.
 */
public final class MmrSelector {

  private MmrSelector() {}

  /**
   * Selects indices into the input lists in MMR order.
   *
   * @param lambda trade-off between relevance and novelty (0..1). Higher means "more relevance".
   * @param relevance relevance scores (higher is better)
   * @param vectors embedding vectors aligned 1:1 with relevance
   * @param k number of indices to select
   * @return selected indices in selection order
   */
  public static List<Integer> select(double lambda, List<Double> relevance, List<float[]> vectors, int k) {
    if (relevance == null || vectors == null) {
      return List.of();
    }
    int n = Math.min(relevance.size(), vectors.size());
    if (n <= 0 || k <= 0) {
      return List.of();
    }

    double lam = clamp01(lambda);
    int target = Math.min(k, n);

    boolean[] chosen = new boolean[n];
    List<Integer> selected = new ArrayList<>(target);

    while (selected.size() < target) {
      int bestIdx = -1;
      double bestScore = Double.NEGATIVE_INFINITY;

      for (int i = 0; i < n; i++) {
        if (chosen[i]) continue;

        double rel = safe(relevance.get(i));
        double noveltyPenalty = 0.0;
        if (lam < 1.0 && !selected.isEmpty()) {
          float[] v = vectors.get(i);
          double maxSim = 0.0;
          for (int j : selected) {
            maxSim = Math.max(maxSim, cosine(v, vectors.get(j)));
          }
          noveltyPenalty = maxSim;
        }

        double score = lam * rel - (1.0 - lam) * noveltyPenalty;
        if (score > bestScore) {
          bestScore = score;
          bestIdx = i;
        }
      }

      if (bestIdx < 0) {
        break;
      }
      chosen[bestIdx] = true;
      selected.add(bestIdx);
    }

    return Collections.unmodifiableList(selected);
  }

  static double cosine(float[] a, float[] b) {
    if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
      return 0.0;
    }
    double dot = 0.0;
    double na = 0.0;
    double nb = 0.0;
    for (int i = 0; i < a.length; i++) {
      double x = a[i];
      double y = b[i];
      dot += x * y;
      na += x * x;
      nb += y * y;
    }
    if (na <= 0.0 || nb <= 0.0) {
      return 0.0;
    }
    return Math.max(-1.0, Math.min(1.0, dot / (Math.sqrt(na) * Math.sqrt(nb))));
  }

  private static double safe(Double v) {
    if (v == null) return 0.0;
    if (v.isNaN() || v.isInfinite()) return 0.0;
    return v;
  }

  private static double clamp01(double v) {
    if (v < 0.0) return 0.0;
    if (v > 1.0) return 1.0;
    return v;
  }
}
