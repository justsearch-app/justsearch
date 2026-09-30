/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.util;

import java.util.List;

/**
 * Utility methods for vector operations.
 *
 * <p>Extracted from WorkerSearchService for reusability and testability.
 */
public final class VectorUtils {

  private VectorUtils() {
    // Utility class - no instantiation
  }

  /**
   * Converts a List of Float to a primitive float array.
   *
   * @param list the list of floats (must not be null)
   * @return primitive float array
   * @throws NullPointerException if list is null
   */
  public static float[] toFloatArray(List<Float> list) {
    if (list == null) {
      throw new NullPointerException("list must not be null");
    }
    float[] array = new float[list.size()];
    for (int i = 0; i < list.size(); i++) {
      array[i] = list.get(i);
    }
    return array;
  }

  /**
   * Computes cosine similarity between two vectors.
   *
   * @param a first vector
   * @param b second vector
   * @return cosine similarity in range [-1, 1], or 0.0 if vectors are invalid
   */
  public static double cosine(float[] a, float[] b) {
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
    return dot / (Math.sqrt(na) * Math.sqrt(nb));
  }
}
