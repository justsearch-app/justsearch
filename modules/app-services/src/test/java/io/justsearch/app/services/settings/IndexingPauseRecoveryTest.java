/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** S36.11: quarantine must stop processing, including after another process restart. */
final class IndexingPauseRecoveryTest {
  @TempDir Path directory;
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void corruptSettingsAndPreservedQuarantineStartPausedUntilExplicitReset() throws Exception {
    var file = directory.resolve("settings.json");
    Files.writeString(file, "broken");
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
    var first = store.load();
    var pause = json.readTree(json.writeValueAsString(first)).get("indexingPause");
    assertNotNull(pause, "corrupt-settings recovery must establish the indexing hold");
    assertTrue(pause.get("paused").asBoolean());
    assertEquals("SETTINGS_RECOVERY", pause.get("reason").asText());
    assertFalse(Files.exists(file));
    var restarted = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
    var again = json.readTree(json.writeValueAsString(restarted.load())).get("indexingPause");
    assertEquals(pause, again, "preserved quarantine must keep the hold after restart");
    SettingsResetDefaults.applyTo(first);
    var reset = json.readTree(json.writeValueAsString(first)).get("indexingPause");
    assertTrue(reset == null || reset.isNull() || !reset.get("paused").asBoolean(),
        "explicit settings reset clears pause intent");
  }
}
