/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.core.scheduling.BulkWorkGate;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bulk-work gate lives in {@code core}, which cannot see this package, so it declares its own
 * reason and profile vocabularies. The operating status block maps them by name, and reason order
 * is precedence on both sides. This pins the mirror (Spec E step E1, S20: the states the UI shows
 * are the ones tools read).
 */
@DisplayName("BulkWorkGate vocabularies mirror IndexingReason and Profile")
final class BulkWorkVocabularyMirrorTest {

  // reasons: same names, same precedence order
  @Test
  void reasons() {
    assertEquals(names(IndexingReason.values()), names(BulkWorkGate.Reason.values()));
  }

  // profiles: same names, same order
  @Test
  void profiles() {
    assertEquals(names(Profile.values()), names(BulkWorkGate.Profile.values()));
  }

  private static java.util.List<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).toList();
  }
}
