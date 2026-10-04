/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class BoundedTokenizeGroupsTest {
  @Test
  void emptyInputHasNoGroups() {
    assertEquals(List.of(), BoundedTokenizeGroups.ranges(List.of(), 4));
  }

  @Test
  void rangesAreOrderedContiguousAndBoundedExceptForOneOversizedText() {
    List<String> texts = List.of("ab", "c", "oversized", "d", "ef", "g");
    List<BoundedTokenizeGroups.Range> ranges = BoundedTokenizeGroups.ranges(texts, 3);
    assertEquals(List.of(
        new BoundedTokenizeGroups.Range(0, 2),
        new BoundedTokenizeGroups.Range(2, 3),
        new BoundedTokenizeGroups.Range(3, 5),
        new BoundedTokenizeGroups.Range(5, 6)), ranges);
    int next = 0;
    for (var range : ranges) {
      assertEquals(next, range.startInclusive());
      next = range.endExclusive();
      long chars = texts.subList(range.startInclusive(), range.endExclusive()).stream()
          .mapToLong(String::length).sum();
      assertTrue(chars <= 3 || range.endExclusive() - range.startInclusive() == 1);
    }
    assertEquals(texts.size(), next);
  }

  @Test
  void rejectsNonpositiveBudget() {
    assertThrows(IllegalArgumentException.class, () -> BoundedTokenizeGroups.ranges(List.of("a"), 0));
  }
}
