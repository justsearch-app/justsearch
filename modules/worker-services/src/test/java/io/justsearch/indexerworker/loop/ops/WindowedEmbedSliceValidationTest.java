/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WindowedEmbedSliceValidationTest {
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"totals", "overrun", "null-list", "empty-list",
      "null-vector", "empty-vector", "mixed-dimensions", "changed-dimension", "nan", "infinity"})
  void invalidSliceRestartsWithoutBlendingAnyOfItsVectors(String fault) {
    var progress = new WindowedEmbedProgress();
    progress.record("doc", "content", 4, 0, List.of(new float[] {1, 0}));
    int from = 1;
    int total = fault.equals("totals") ? 5 : 4;
    List<float[]> vectors = switch (fault) {
      case "null-list" -> null;
      case "empty-list" -> List.of();
      case "null-vector" -> Arrays.asList(new float[] {0, 1}, null);
      case "empty-vector" -> List.of(new float[] {0, 1}, new float[0]);
      case "mixed-dimensions" -> List.of(new float[] {0, 1}, new float[] {0, 1, 0});
      case "changed-dimension" -> List.of(new float[] {0, 1, 0});
      case "overrun" -> java.util.Collections.nCopies(4, new float[] {0, 1});
      case "nan" -> List.of(new float[] {0, 1}, new float[] {Float.NaN, 0});
      case "infinity" -> List.of(new float[] {0, 1}, new float[] {0, Float.POSITIVE_INFINITY});
      default -> List.of(new float[] {0, 1});
    };
    assertDoesNotThrow(() -> progress.record("doc", "content", total, from, vectors));
    assertEquals(0, progress.nextWindow("doc", "content"), "invalid slices restart at zero");
    assertFalse(progress.isComplete("doc"));
    assertEquals(4, progress.record("doc", "content", 4, 0,
        java.util.Collections.nCopies(4, new float[] {0, 1})));
    assertArrayEquals(new float[] {0, 1}, progress.complete("doc"),
        "no rejected vector or retained sum may leak into the restarted document");
  }
}
