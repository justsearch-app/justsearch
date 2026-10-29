/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.core.scheduling.IndexingHold;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** D1/D4/S38.2/6: rules are validated before coercion or duplicate loss in the real store. */
final class IndexingRulesPersistenceTest {
  @TempDir Path directory;
  private final JsonMapper json = JsonMapper.builder().build();
  private static final String HOURS = "{\"enabled\":true,\"days\":[1,7],\"startMinute\":1320,\"endMinute\":360}";

  private UiSettingsStore store(String name, String policy) throws Exception {
    Path file = directory.resolve(name).resolve("settings.json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "{\"schemaVersion\":6,\"acceptedRevision\":0,"
        + "\"lastCommittedOperationKey\":null,\"settings\":{\"indexingPause\":" + policy + "}}");
    return new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
  }

  @Test
  void malformedSavedRulesQuarantineAndRemainInRecoveryOnSecondRestart() throws Exception {
    var invalid = List.of(
        "{\"schedule\":42}", "{\"pauseOnBattery\":null}", "{\"pauseOnBattery\":\"true\"}",
        "{\"pauseOnBattery\":1}", "{\"schedule\":{}}", "{\"unknownRule\":true}",
        "{\"pauseOnBattery\":true,\"pauseOnBattery\":false}",
        "{\"schedule\":" + HOURS + ",\"schedule\":null}",
        "{\"schedule\":" + HOURS.replace("true", "\"true\"") + "}",
        "{\"schedule\":" + HOURS.replace("[1,7]", "[1,1]") + "}",
        "{\"schedule\":" + HOURS.replace("[1,7]", "[0,8]") + "}",
        "{\"schedule\":" + HOURS.replace("[1,7]", "[1.0]") + "}",
        "{\"schedule\":" + HOURS.replace("[1,7]", "[]") + "}",
        "{\"schedule\":" + HOURS.replace("[1,7]", "true") + "}",
        "{\"schedule\":" + HOURS.replace("1320", "360") + "}",
        "{\"schedule\":" + HOURS.replace("1320", "1440") + "}",
        "{\"schedule\":" + HOURS.replace("1320", "1.5") + "}",
        "{\"schedule\":" + HOURS.replace("1320", "\"1320\"") + "}",
        "{\"schedule\":" + HOURS.replace("true", "false").replace("360", "-1") + "}",
        "{\"schedule\":" + HOURS.replace("\"enabled\":true", "\"enabled\":true,\"enabled\":false") + "}",
        "{\"schedule\":" + HOURS.replace("\"days\"", "\"extra\":1,\"days\"") + "}");
    assertAll("all malformed saved rule classes", java.util.stream.IntStream.range(0, invalid.size())
        .mapToObj(i -> (org.junit.jupiter.api.function.Executable) () -> {
      var store = store("invalid-" + i, "{\"paused\":false," + invalid.get(i).substring(1));
      var hold = new IndexingHold();
      store.bindIndexingHold(hold);
      assertEquals("SETTINGS_RECOVERY", hold.snapshot().reason(), invalid.get(i));
      assertTrue(store.lastRecovery().isPresent(), invalid.get(i));
      assertFalse(Files.exists(store.settingsPath()));
      var again = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, store.settingsPath());
      again.bindIndexingHold(hold);
      assertEquals("SETTINGS_RECOVERY", hold.snapshot().reason(), "second restart: " + invalid.get(i));
    }));
  }

  @Test
  void futureVersionWithDuplicateRuleTokensFailsLoudWithoutQuarantine() throws Exception {
    var store = store("future", "{\"paused\":false,\"pauseOnBattery\":true,\"pauseOnBattery\":false}");
    String future = Files.readString(store.settingsPath()).replace("\"schemaVersion\":6", "\"schemaVersion\":7");
    Files.writeString(store.settingsPath(), future);
    assertThrows(io.justsearch.configuration.persistence.UnsupportedStoreVersionException.class, store::load);
    assertEquals(future, Files.readString(store.settingsPath()), "future settings bytes remain untouched");
    assertTrue(store.lastRecovery().isEmpty(), "future versions are not corruption recovery");
  }

  @Test
  void oldPoliciesKeepManualFieldsAndHaveNoRules() throws Exception {
    for (String policy : List.of("null", "{\"paused\":false,\"untilEpochMs\":null,\"reason\":null}",
        "{\"paused\":true,\"untilEpochMs\":1900000000000,\"reason\":\"USER_PAUSED\"}")) {
      var store = store("old-" + policy.hashCode(), policy);
      var saved = json.valueToTree(store.load()).get("indexingPause");
      if (!policy.equals("null")) assertEquals(json.readTree(policy), saved);
      assertTrue(store.lastRecovery().isEmpty());
    }
    var absent = store("absent", "null");
    Files.writeString(absent.settingsPath(), "{\"theme\":\"dark\"}");
    var hold = new IndexingHold();
    absent.bindIndexingHold(hold);
    assertFalse(hold.held());
  }

  @Test
  void disabledHoursAndBatteryPreferenceSurviveAcceptedSerializationAndResetClearsThem() throws Exception {
    var store = store("valid", "{\"paused\":false,\"schedule\":"
        + HOURS.replace("true", "false").replace("[1,7]", "[]") + ",\"pauseOnBattery\":true}");
    var initial = store.load();
    assertTrue(store.lastRecovery().isEmpty());
    var tree = json.valueToTree(initial).get("indexingPause");
    assertNotNull(tree.get("schedule"), "disabled hours remain configured");
    assertTrue(tree.get("pauseOnBattery").booleanValue());
    store.replacePrepared(store.prepare(initial, new SettingsWitness(1, io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC()))));
    assertEquals(tree, json.valueToTree(store.load()).get("indexingPause"));
    SettingsResetDefaults.applyTo(initial);
    assertNull(initial.getIndexingPause());
  }
}
