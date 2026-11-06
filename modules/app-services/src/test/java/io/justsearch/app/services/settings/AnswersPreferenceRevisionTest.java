/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The saved answers choice revision: absent in existing settings (loads as today), stamped by the
 * settings owner for a changed answers choice and for the answers switch repeating a choice, not
 * for an ordinary save, and a revision ahead of the settings revision is quarantined as corrupt.
 */
final class AnswersPreferenceRevisionTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void existingSettingsOfEveryVersionLoadWithoutARevisionAndAreNotQuarantined(@TempDir Path dir)
      throws Exception {
    for (int version = 0; version <= UiSettingsStore.CURRENT_SCHEMA_VERSION; version++) {
      var file = dir.resolve("settings-" + version + ".json");
      String settings = "{\"chatEnabled\":true,\"declinedAiPackages\":[\"reranker\"]}";
      Files.writeString(file, version == 0 ? settings : "{\"schemaVersion\":" + version
          + (version >= 3 ? ",\"acceptedRevision\":0,\"lastCommittedOperationKey\":null" : "")
          + ",\"settings\":" + settings + "}");
      var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      var loaded = store.load();
      assertTrue(store.lastRecovery().isEmpty(), "version " + version + " must not be quarantined: "
          + store.lastRecovery().map(Object::toString).orElse(""));
      assertEquals(0, loaded.getChatPreferenceRevision(), "version " + version);
      assertEquals(Boolean.TRUE, loaded.getChatEnabled(), "version " + version);
      assertEquals(java.util.List.of("reranker"), loaded.getDeclinedAiPackages(), "version " + version);
    }
  }

  @Test
  void aRevisionAheadOfTheSettingsRevisionIsQuarantined(@TempDir Path dir) throws Exception {
    // Written by the real owner, so only the answers revision differs between the two files.
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, dir.resolve("settings.json"));
    var saved = new UiSettings();
    saved.setChatEnabled(false);
    store.replacePrepared(store.prepare(saved,
        new SettingsWitness(2, OperationKeys.generate(Clock.systemUTC()))));
    var root = (tools.jackson.databind.node.ObjectNode) JSON.readTree(Files.readString(store.settingsPath()));
    long revision = root.path("acceptedRevision").asLong();
    assertTrue(revision > 0);

    ((tools.jackson.databind.node.ObjectNode) root.get("settings")).put("chatPreferenceRevision", revision);
    var atRevision = dir.resolve("at.json");
    Files.writeString(atRevision, JSON.writeValueAsString(root));
    var accepted = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, atRevision);
    assertEquals(revision, accepted.load().getChatPreferenceRevision());
    assertTrue(accepted.lastRecovery().isEmpty(), "a revision at the settings revision is valid");

    ((tools.jackson.databind.node.ObjectNode) root.get("settings")).put("chatPreferenceRevision", revision + 1);
    var ahead = dir.resolve("ahead.json");
    Files.writeString(ahead, JSON.writeValueAsString(root));
    var quarantined = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, ahead);
    assertEquals(0, quarantined.load().getChatPreferenceRevision());
    assertTrue(quarantined.lastRecovery().isPresent(), "an impossible revision must be quarantined");
  }

  @Test
  void theOwnerStampsChangedAndRepeatedAnswersChoicesButNotOrdinarySaves(@TempDir Path dir)
      throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, dir.resolve("settings.json"));
    var initial = new UiSettings();
    initial.setChatEnabled(false);
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
    var prior = ConfigStore.globalOrNull();
    var published = new ConfigStore(ConfigStoreRebuilder.prepare(initial));
    ConfigStore.setGlobal(published);
    try (var fixture = new RuntimeIntentTestFixture(dir.resolve("intent"), store, published)) {
      var settings = new SettingsServiceImpl(store, fixture.runner());

      save(settings, store, "\"ui\":{\"theme\":\"dark\",\"chatEnabled\":false}");
      assertEquals(0, store.inspect().settings().getChatPreferenceRevision(),
          "an ordinary save echoing the answers value is not an answers choice");

      save(settings, store, "\"ui\":{\"chatEnabled\":true}");
      var on = store.inspect();
      assertEquals(on.witness().acceptedRevision(), on.settings().getChatPreferenceRevision(),
          "a changed answers value is stamped");

      fixture.spec().setChatEnabled(true);
      var repeated = store.inspect();
      assertTrue(repeated.witness().acceptedRevision() > on.witness().acceptedRevision());
      assertEquals(repeated.witness().acceptedRevision(), repeated.settings().getChatPreferenceRevision(),
          "the answers switch repeating the saved value is stamped");

      save(settings, store, "\"ui\":{\"theme\":\"light\"}");
      assertEquals(repeated.witness().acceptedRevision(), store.inspect().settings().getChatPreferenceRevision(),
          "a later unrelated save keeps the last answers choice revision");
      var reopened = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, store.settingsPath());
      assertEquals(repeated.witness().acceptedRevision(), reopened.load().getChatPreferenceRevision());
      assertTrue(reopened.lastRecovery().isEmpty());
    } finally {
      ConfigStore.restoreGlobal(published, prior);
    }
  }

  private static void save(SettingsServiceImpl settings, UiSettingsStore store, String fields) {
    var patch = JSON.readValue("{" + fields + ",\"witness\":"
        + JSON.writeValueAsString(store.inspect().witness()) + ",\"operationKey\":\""
        + OperationKeys.generate(Clock.systemUTC()) + "\"}", SettingsV2.class);
    var result = settings.applyPublic(patch, null, TestEngineContexts.internal());
    assertTrue(result.response().success(), result.response().toString());
  }
}
