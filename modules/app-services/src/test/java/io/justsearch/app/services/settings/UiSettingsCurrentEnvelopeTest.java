/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Gives the unchanged nested writer/reopen oracle a selectable standalone JUnit identity. */
final class UiSettingsCurrentEnvelopeTest {
  @Test
  void currentEnvelopeAndReopen(@TempDir Path directory) {
    var original = new UiSettingsStorePersistenceModeTest().new LoadBehavior();
    original.tempDir = directory;
    assertDoesNotThrow(original::readWrite_preparedReplacementWritesVersionedEnvelope);
  }
}
