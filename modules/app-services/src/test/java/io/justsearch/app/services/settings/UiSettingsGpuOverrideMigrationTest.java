/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class UiSettingsGpuOverrideMigrationTest {
  @TempDir Path directory;

  @Test
  void nullableOverrideRoundTripsWithoutInventingCpuIntent() {
    var mapper = JsonMapper.builder().build();
    UiSettings settings = new UiSettings();
    for (Integer override : new Integer[] {null, 0, 99}) {
      settings.setGpuLayers(override);
      String json = mapper.writeValueAsString(settings);
      var decoded = mapper.readValue(json, UiSettings.class);
      assertEquals(override, decoded.configuredGpuLayers(), json);
      assertEquals(override == null ? 0 : override, decoded.getGpuLayers());
      var builder = ResolvedConfig.builder();
      builder.contributeAutoDetected(java.util.Map.of("justsearch.gpu.layers", "99"));
      ConfigStoreRebuilder.contributeUiSettings(builder, decoded);
      assertEquals(override == null ? 99 : override, builder.build().ai().gpuLayers());
    }
  }

  @Test
  void legacyZeroMigratesToAutomaticAndPositiveOverrideSurvivesWithoutChangingWitness() throws Exception {
    for (int version : new int[] {1, 2, 3}) {
      for (int layers : new int[] {0, 30}) {
        Path file = directory.resolve("settings-" + version + "-" + layers + ".json");
        String witness = version < 3 ? "" : ",\"acceptedRevision\":0,\"lastCommittedOperationKey\":null";
        Files.writeString(file, "{\"schemaVersion\":" + version
            + ",\"settings\":{\"gpuLayers\":" + layers + "}" + witness + "}");
        var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
        var snapshot = store.inspect();
        assertEquals(layers == 0 ? null : Integer.valueOf(layers), snapshot.settings().configuredGpuLayers());
        assertEquals(0, snapshot.witness().acceptedRevision());
        store.replacePrepared(store.prepare(snapshot.settings(), snapshot.witness()));
        assertEquals(snapshot.settings().configuredGpuLayers(), store.inspect().settings().configuredGpuLayers());
      }
    }
  }

  @Test
  void schemaFourKeepsExplicitCpuAcrossReloadAndResetRestoresAutomatic() throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    UiSettings settings = new UiSettings();
    settings.setGpuLayers(0);
    store.replacePrepared(store.prepare(settings, new SettingsWitness(0, null)));
    var loaded = store.load();
    assertEquals(0, loaded.configuredGpuLayers());
    SettingsResetDefaults.applyTo(loaded);
    assertNull(loaded.configuredGpuLayers());
  }
}
