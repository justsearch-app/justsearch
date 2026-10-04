/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 837 §2.3(i) — the read-side vocabulary closure.
 *
 * <p>The point of the design is that the mapping is TOTAL with {@code UNKNOWN} as a real member,
 * because the field is read back off disk: a generation written by an older build, by one of the six
 * system/worker test drivers, or by a hand-edited manifest will hand back a string outside any
 * vocabulary this build defines — forever. A strict write-side enum would have broken those drivers
 * and still not covered the pre-existing manifests.
 */
final class MigrationSourceTest {

  @Test
  @DisplayName("every declared member round-trips through its wire value")
  void membersRoundTrip() {
    for (MigrationSource member : MigrationSource.values()) {
      assertSame(member, MigrationSource.fromWire(member.wire()), member.name());
    }
  }

  @Test
  @DisplayName("the three system-initiated sources keep the strings already on disk")
  void wireValuesAreTheOnesAlreadyPersisted() {
    assertEquals("corrupt_index_rebuild", MigrationSource.CORRUPT_INDEX_REBUILD.wire());
    assertEquals("embedding_model_change", MigrationSource.EMBEDDING_MODEL_CHANGE.wire());
    assertEquals("schema_mismatch", MigrationSource.SCHEMA_MISMATCH.wire());
    assertEquals("installer_model_activation", MigrationSource.INSTALLER_MODEL_ACTIVATION.wire());
    assertEquals("manual", MigrationSource.MANUAL.wire());
  }

  @Test
  @DisplayName("a legacy / test-driver / hand-written label lands on UNKNOWN, not an error")
  void unrecognizedLabelsLandOnUnknown() {
    // The exact labels the existing drivers write (§2.3's table). None of them may throw, and none
    // of them may be reported as a source the UI would then word confidently.
    for (String legacy :
        new String[] {
          "system_test",
          "pause_resume_test",
          "system_test_rollback",
          "system_test_switching",
          "test-rebuild",
          "rebuild-1",
          "Operation invocation: core.rebuild-index",
          ""
        }) {
      assertSame(MigrationSource.UNKNOWN, MigrationSource.fromWire(legacy), legacy);
    }
    assertSame(MigrationSource.UNKNOWN, MigrationSource.fromWire(null));
  }

  @Test
  @DisplayName("the mapping normalizes case and surrounding whitespace (the field was caller-controlled)")
  void normalizesCallerInput() {
    assertSame(MigrationSource.MANUAL, MigrationSource.fromWire("  MANUAL "));
    assertSame(
        MigrationSource.CORRUPT_INDEX_REBUILD, MigrationSource.fromWire("Corrupt_Index_Rebuild"));
  }
}
