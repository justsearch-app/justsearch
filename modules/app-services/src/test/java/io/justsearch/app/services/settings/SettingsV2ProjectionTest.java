/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.LlmSettingsV2;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.UiSettingsV2;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsV2ProjectionTest {
  @Test
  void projectsEveryStoredFieldWithoutTrimmingPaths() {
    var settings = new UiSettings();
    settings.setTheme("dark");
    settings.setHighContrast(true);
    settings.setDensity("compact");
    settings.setVimMode(true);
    settings.setDefaultAction("reveal");
    settings.setInspectorWidth(420);
    settings.setPauseIndexingDuringAi(true);
    settings.setMode("advanced");
    settings.setTrustLoopNudgeSeen(true);
    settings.setExcludePatterns(List.of("*.tmp"));
    settings.setChatEnabled(true);
    settings.setServerExecutablePath(" server.exe ");
    settings.setContextLength(8192);
    settings.setMaxTokens(2048);
    settings.setGpuLayers(35);
    settings.setLlmModelPath(" model.gguf ");
    settings.setLlamaLibPath(" native-dir ");
    settings.setIndexBasePath(" index-dir ");

    var expected = new SettingsV2(
        new UiSettingsV2("dark", true, "compact", true, "reveal", 420,
            true, "advanced", true, List.of("*.tmp"), true),
        new LlmSettingsV2(" server.exe ", 8192, 2048, 35, " model.gguf ", " native-dir "),
        List.of("index-dir"), "read_write");
    assertEquals(expected, SettingsV2Projection.toSettingsV2(settings, UiSettingsStore.PersistenceMode.READ_WRITE));
    assertEquals("in_memory",
        SettingsV2Projection.toSettingsV2(settings, UiSettingsStore.PersistenceMode.IN_MEMORY).settingsMode());
  }

  @Test
  void preservesUnsetAndAutomaticValuesWithoutInventingAnIndexPath() {
    var settings = new UiSettings();
    settings.setInspectorWidth(-1);
    settings.setChatEnabled(null);
    settings.setContextLength(0);
    settings.setServerExecutablePath(" \t");
    settings.setLlmModelPath("");
    settings.setLlamaLibPath(null);
    settings.setIndexBasePath("  ");

    var projected = SettingsV2Projection.toSettingsV2(settings, UiSettingsStore.PersistenceMode.READ_WRITE);
    assertNull(projected.ui().inspectorWidth());
    assertNull(projected.ui().chatEnabled());
    assertNull(projected.llm().serverExecutable());
    assertNull(projected.llm().modelPath());
    assertNull(projected.llm().llamaLibPath());
    assertEquals(0, projected.llm().contextWindow());
    assertNull(projected.llm().gpuLayers(), "a full-document round trip must not invent CPU intent");
    assertEquals(List.of(), projected.indexPaths());
    assertEquals("", settings.getIndexBasePath());
  }

  @Test
  void explicitCpuRemainsDistinctFromAutomaticOnTheCanonicalWire() {
    var settings = new UiSettings();
    settings.setGpuLayers(0);
    var projected = SettingsV2Projection.toSettingsV2(settings, UiSettingsStore.PersistenceMode.READ_WRITE);
    assertEquals(0, projected.llm().gpuLayers());
    assertNull(LlmSettingsV2.defaults().gpuLayers());
  }

  @Test
  void responseListsDoNotAliasTheMutableSettingsCandidate() {
    var settings = new UiSettings();
    settings.setExcludePatterns(List.of("*.tmp"));
    settings.setIndexBasePath("index-dir");
    var projected = SettingsV2Projection.toSettingsV2(settings, UiSettingsStore.PersistenceMode.READ_WRITE);
    settings.getExcludePatterns().add("later/**");
    settings.setIndexBasePath("later-index");

    assertEquals(List.of("*.tmp"), projected.ui().excludePatterns());
    assertEquals(List.of("index-dir"), projected.indexPaths());
    assertThrows(UnsupportedOperationException.class, () -> projected.ui().excludePatterns().add("foreign"));
    assertThrows(UnsupportedOperationException.class, () -> projected.indexPaths().add("foreign"));
  }
}
