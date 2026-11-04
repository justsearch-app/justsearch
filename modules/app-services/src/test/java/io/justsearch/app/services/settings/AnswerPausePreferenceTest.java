/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class AnswerPausePreferenceTest {
  private final JsonMapper json = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @Test
  void pausePatchSurvivesReopenAndNeverChangesAnswerIntent(@TempDir Path dir) throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        dir.resolve("settings.json"));
    var base = new UiSettings();
    base.setChatEnabled(true);
    base.setDeclinedAiPackages(java.util.List.of("chat"));
    var input = json.readValue("{\"pauseAnswersWhileIndexing\":true}", SettingsV2.class);
    var candidate = SettingsPatch.merge(base, SettingsPatch.normalize(input), true);
    assertEquals(Boolean.TRUE, candidate.getChatEnabled());
    store.replacePrepared(store.prepare(candidate, new SettingsWitness(0, null)));
    var reopened = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, store.settingsPath());
    var projected = json.valueToTree(SettingsV2Projection.toSettingsV2(reopened.load(), reopened.mode()));
    assertTrue(projected.path("pauseAnswersWhileIndexing").asBoolean(),
        "a saved pause policy must survive a new settings owner");
    assertEquals(java.util.List.of("chat"), reopened.load().getDeclinedAiPackages());
    var off = SettingsPatch.merge(reopened.load(), json.readValue(
        "{\"ui\":{\"chatEnabled\":false}}", SettingsV2.class), true);
    var disablePolicy = SettingsPatch.merge(off, json.readValue(
        "{\"pauseAnswersWhileIndexing\":false}", SettingsV2.class), true);
    store.replacePrepared(store.prepare(disablePolicy, new SettingsWitness(0, null)));
    var after = reopened.load();
    assertEquals(Boolean.FALSE, after.getChatEnabled());
    assertFalse(json.valueToTree(after).path("pauseAnswersWhileIndexing").asBoolean(true));
  }

  @Test
  void everyLegacyVersionLoadsWithoutAnAutomaticPauseAndKeepsIntent(@TempDir Path dir)
      throws Exception {
    for (int version = 0; version <= 7; version++) {
      var file = dir.resolve("settings-" + version + ".json");
      String settings = "{\"chatEnabled\":false,\"declinedAiPackages\":[\"chat\"]}";
      Files.writeString(file, version == 0 ? settings : "{\"schemaVersion\":" + version
          + (version >= 3 ? ",\"acceptedRevision\":0,\"lastCommittedOperationKey\":null" : "")
          + ",\"settings\":" + settings + "}");
      var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      assertNull(store.load().getPauseAnswersWhileIndexing(), "version " + version);
      assertEquals(Boolean.FALSE, store.load().getChatEnabled(), "version " + version);
      assertEquals(java.util.List.of("chat"), store.load().getDeclinedAiPackages());
      assertTrue(store.lastRecovery().isEmpty(), "legacy settings are valid");
    }
  }
}
