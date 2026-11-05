/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class PictureReadingPreferenceTest {
  private final JsonMapper json = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @Test
  void offPatchSurvivesReopenAndDoesNotChangeAnswersOrAcquisition(@TempDir Path dir) throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, dir.resolve("settings.json"));
    var base = new UiSettings();
    base.setChatEnabled(false);
    base.setDeclinedAiPackages(java.util.List.of("chat"));
    var patch = json.readValue("{\"pictureReadingEnabled\":false}", SettingsV2.class);
    var candidate = SettingsPatch.merge(base, SettingsPatch.normalize(patch), true);
    store.replacePrepared(store.prepare(candidate, new SettingsWitness(0, null)));
    var reopened = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, store.settingsPath());
    var settings = reopened.load();
    var projected = json.valueToTree(SettingsV2Projection.toSettingsV2(settings, reopened.mode()));
    assertTrue(projected.path("pictureReadingEnabled").isBoolean(), "the public API must expose the saved choice");
    assertFalse(projected.path("pictureReadingEnabled").asBoolean(true));
    assertEquals("none", ConfigStoreRebuilder.prepare(settings).ai().mmprojModel());
    assertEquals(Boolean.FALSE, settings.getChatEnabled());
    assertEquals(java.util.List.of("chat"), settings.getDeclinedAiPackages());
    var on = SettingsPatch.merge(settings, json.readValue("{\"pictureReadingEnabled\":true}", SettingsV2.class), true);
    assertNotEquals("none", ConfigStoreRebuilder.prepare(on).ai().mmprojModel());
    store.replacePrepared(store.prepare(on, new SettingsWitness(0, null)));
    assertTrue(json.valueToTree(reopened.load()).path("pictureReadingEnabled").asBoolean());
    assertEquals(9, json.readTree(Files.readString(store.settingsPath())).path("schemaVersion").asInt());
  }

  @Test
  void everyLegacyVersionKeepsAutomaticPictureReadingAndItsOtherChoices(@TempDir Path dir) throws Exception {
    for (int version = 0; version <= 8; version++) {
      var file = dir.resolve("settings-" + version + ".json");
      String settings = "{\"pictureReadingEnabled\":false,\"pictureReadingPreferenceRevision\":99,\"chatEnabled\":false,\"declinedAiPackages\":[\"chat\"]}";
      Files.writeString(file, version == 0 ? settings : "{\"schemaVersion\":" + version
          + (version >= 3 ? ",\"acceptedRevision\":0,\"lastCommittedOperationKey\":null" : "")
          + ",\"settings\":" + settings + "}");
      var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      var loaded = store.load();
      assertTrue(json.valueToTree(SettingsV2Projection.toSettingsV2(loaded, store.mode()))
          .path("pictureReadingEnabled").isNull(), "version " + version);
      assertFalse(json.valueToTree(loaded).has("pictureReadingPreferenceRevision"), "version " + version);
      assertEquals(Boolean.FALSE, loaded.getChatEnabled());
      assertEquals(java.util.List.of("chat"), loaded.getDeclinedAiPackages());
      assertTrue(store.lastRecovery().isEmpty(), "legacy input must not be quarantined");
    }
  }
}
