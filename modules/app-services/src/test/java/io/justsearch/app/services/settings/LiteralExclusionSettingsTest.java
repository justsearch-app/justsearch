/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class LiteralExclusionSettingsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "0194f72c-0000-7000-8000-000000000001";
  private static final String TOKEN = "00000000-0000-4000-8000-000000000001";
  @TempDir Path directory;

  private String literal(String token) {
    return "{\"literal\":{\"version\":1,\"id\":\"" + token + "\",\"path\":"
        + JSON.writeValueAsString(directory.resolve("report\\[1]*.txt ").toString())
        + ",\"scope\":\"FILE\"}}";
  }

  private UiSettings settings() {
    return assertDoesNotThrow(() -> JSON.readValue(
        "{\"excludePatterns\":[\"[legacy\",\"*.log\"," + literal(TOKEN) + "]}", UiSettings.class),
        "saved exact policy must retain valid literal objects beside unchanged legacy strings");
  }

  private SettingsV2 patch(String entries) {
    return assertDoesNotThrow(() -> JSON.readValue("{\"ui\":{\"excludePatterns\":" + entries + "}}", SettingsV2.class));
  }

  @Test
  void RF55V6ExactEntriesSurviveSaveReloadWithUnrelatedFieldsAndLegacyFallbacks() throws Exception {
    Path path = directory.resolve("settings.json");
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
    var candidate = settings();
    candidate.setTheme("dark"); candidate.setMaxTokens(3072);
    store.replacePrepared(store.prepareExact(candidate, new SettingsWitness(1, KEY)));
    for (int restart = 0; restart < 2; restart++) {
      var reloaded = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path).inspect();
      assertEquals(JSON.valueToTree(candidate.getExcludePatterns()),
          JSON.valueToTree(reloaded.settings().getExcludePatterns()));
      assertEquals("dark", reloaded.settings().getTheme());
      assertEquals(3072, reloaded.settings().getMaxTokens());
      assertEquals(new SettingsWitness(1, KEY), reloaded.witness());
    }
    assertEquals(6, JSON.readTree(Files.readString(path)).path("schemaVersion").asInt());
    assertThrows(UnsupportedStoreVersionException.class,
        () -> StoreFormatVersions.requireReadable("ui-settings", 6, 5, 0, 0, 1, 2, 3, 4));
  }

  @Test
  void RF55PublicSaveCanRetainOrRemoveExactTokensButCannotCreateOrModifyThem() {
    var base = settings();
    var retained = patch("[\"[legacy\",\"*.tmp\"," + literal(TOKEN) + "]");
    assertDoesNotThrow(() -> SettingsPatch.merge(base, SettingsPatch.normalize(retained), true));
    assertEquals(3, base.getExcludePatterns().size());
    String before = JSON.writeValueAsString(base);
    var foreign = patch("[\"[legacy\"," + literal("aa10bcec-4102-47f8-a26d-d02346c8a211") + "]");
    assertThrows(IllegalArgumentException.class,
        () -> SettingsPatch.merge(base, SettingsPatch.normalize(foreign), true));
    assertEquals(before, JSON.writeValueAsString(base), "refused tokens cannot replace the saved policy");
    assertDoesNotThrow(() -> SettingsPatch.merge(base, patch("[\"[legacy\",\"*.tmp\"]"), true));
    assertEquals(List.of("[legacy", "*.tmp"), base.getExcludePatterns());
  }

  @Test
  void RF55ResetClearsLegacyAndPrecisePolicyTogether() {
    var base = settings();
    SettingsResetDefaults.applyTo(base);
    assertTrue(base.getExcludePatterns().isEmpty());
  }

  @Test
  void RF55EveryLegacyVersionKeepsItsStringMeaningAndCannotCarryTypedState() throws Exception {
    Path path = directory.resolve("legacy.json");
    for (int version = 0; version <= 5; version++) {
      String settings = "{\"excludePatterns\":[\"[legacy\",\"literal:report\",\"*.log\"]}";
      String raw = version == 0 ? settings : "{\"schemaVersion\":" + version + ",\"settings\":" + settings
          + (version >= 3 ? ",\"acceptedRevision\":0,\"lastCommittedOperationKey\":null" : "") + "}";
      Files.writeString(path, raw);
      var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
      assertEquals(List.of("[legacy", "literal:report", "*.log"), store.inspect().settings().getExcludePatterns(),
          "legacy version " + version);
      assertEquals(raw, Files.readString(path), "inspection never rewrites the legacy source");
      String typed = raw.replace("\"*.log\"", literal(TOKEN));
      Files.writeString(path, typed);
      assertThrows(CorruptDurableStoreException.class, store::inspect,
          "typed objects cannot claim legacy schema " + version);
      assertEquals(typed, Files.readString(path), "refused state remains available for recovery");
    }
  }
}
