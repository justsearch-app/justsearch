package io.justsearch.indexing.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("MMR selector")
final class MmrSelectorTest {

  @Test
  @DisplayName("select() prefers diverse second pick when candidates are redundant")
  void prefersDiversityWhenRedundant() {
    List<Double> rel = List.of(1.0, 0.95, 0.8);
    List<float[]> vecs =
        List.of(
            new float[] {1.0f, 0.0f},
            new float[] {0.9f, 0.1f},
            new float[] {0.0f, 1.0f});

    List<Integer> selected = MmrSelector.select(0.5, rel, vecs, 3);
    assertEquals(List.of(0, 2, 1), selected);
  }

  @Test
  @DisplayName("select() handles mismatched lengths safely")
  void mismatchedLengthsSafe() {
    List<Integer> selected = MmrSelector.select(0.5, List.of(1.0), List.of(), 5);
    assertTrue(selected.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(doubles = {1.0, 0.999, 0.9989, 2.0})
  void relevanceEndpointRanksUnsortedInputs(double lambda) {
    assertEquals(
        List.of(1, 2),
        MmrSelector.select(lambda, List.of(0.1, 0.9, 0.8),
            List.of(new float[] {1, 0}, new float[] {1, 0}, new float[] {1, 0}), 2));
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.999, 0.9989, 0.999999})
  void belowOneRetainsNoveltyEvenNearEndpoint(double lambda) {
    var vectors = List.of(new float[] {1, 0}, new float[] {1, 0}, new float[] {0, 1});
    assertEquals(List.of(0, 2), MmrSelector.select(lambda, List.of(1.0, 0.5, 0.5), vectors, 2));
    assertEquals(List.of(0, 1), MmrSelector.select(1.0, List.of(1.0, 0.5, 0.5), vectors, 2));
  }

  @Test
  void sanitizesRelevanceAndKeepsOriginalIndexForTies() {
    var relevance = Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1.0);
    var vectors = List.of(new float[] {1}, new float[] {1}, new float[] {1},
        new float[] {1}, new float[] {1});
    assertEquals(List.of(4, 0, 1, 2, 3), MmrSelector.select(1.0, relevance, vectors, 10));
    assertEquals(List.of(0, 1, 2, 3, 4), MmrSelector.select(0.0, relevance, vectors, 10));
  }

  @Test
  void negativeCosineDoesNotBecomeANoveltyReward() {
    var vectors = List.of(new float[] {1, 0}, new float[] {0, 1}, new float[] {-1, 0});
    assertEquals(List.of(0, 1, 2), MmrSelector.select(0.0, List.of(0.0, 0.0, 0.0), vectors, 3));
    assertEquals(List.of(0, 1, 2), MmrSelector.select(-1.0, List.of(0.0, 0.0, 0.0), vectors, 3));
    assertEquals(List.of(2, 0, 1),
        MmrSelector.select(1e-6, List.of(0.5, 0.25, 1.0), vectors, 3));
  }

  @Test
  void preservesInputShapeContractsAndDoesNotMutateInputs() {
    var relevance = List.of(0.1, 0.9, 0.8);
    var vectors = List.of(new float[] {1, 0}, new float[] {1, 0}, new float[] {0, 1});
    assertEquals(List.of(), MmrSelector.select(0.5, null, vectors, 2));
    assertEquals(List.of(), MmrSelector.select(0.5, relevance, null, 2));
    assertEquals(List.of(), MmrSelector.select(0.5, List.of(), vectors, 2));
    assertEquals(List.of(), MmrSelector.select(0.5, relevance, vectors, 0));
    assertEquals(List.of(), MmrSelector.select(0.5, relevance, vectors, -1));
    assertEquals(List.of(1, 0), MmrSelector.select(1.0, relevance, vectors.subList(0, 2), 10));
    for (int iteration = 0; iteration < 5; iteration++) {
      var selected = MmrSelector.select(0.5, relevance, vectors, 10);
      assertEquals(List.of(1, 2, 0), selected);
      assertEquals(selected.size(), new HashSet<>(selected).size());
      assertThrows(UnsupportedOperationException.class, () -> selected.add(0));
    }
    assertEquals(List.of(0.1, 0.9, 0.8), relevance);
    assertArrayEquals(new float[] {1, 0}, vectors.get(0));
    assertArrayEquals(new float[] {1, 0}, vectors.get(1));
    assertArrayEquals(new float[] {0, 1}, vectors.get(2));
  }

  @Test
  void validCosinesStayInRangeAtFloatExtremes() {
    var large = new float[] {Float.MAX_VALUE, Float.MAX_VALUE};
    var small = new float[] {Float.MIN_VALUE, 0};
    assertEquals(1.0, MmrSelector.cosine(large, large), 1e-15);
    assertEquals(1.0, MmrSelector.cosine(small, small), 1e-15);
    assertEquals(-1.0, MmrSelector.cosine(large,
        new float[] {-Float.MAX_VALUE, -Float.MAX_VALUE}), 1e-15);
  }
}
