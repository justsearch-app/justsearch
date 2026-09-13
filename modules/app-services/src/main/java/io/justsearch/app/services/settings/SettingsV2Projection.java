/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.LlmSettingsV2;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.UiSettingsV2;
import java.util.List;
import java.util.Locale;

/** Pure response projection shared by HTTP reads and prepared settings receipts. */
public final class SettingsV2Projection {
  private SettingsV2Projection() {}

  public static SettingsV2 toSettingsV2(UiSettings settings, UiSettingsStore.PersistenceMode persistenceMode) {
    var ui = new UiSettingsV2(
        settings.getTheme(), settings.isHighContrast(), settings.getDensity(),
        settings.isVimMode(), settings.getDefaultAction(),
        settings.getInspectorWidth() > 0 ? settings.getInspectorWidth() : null,
        settings.isPauseIndexingDuringAi(), settings.getMode(), settings.isTrustLoopNudgeSeen(),
        List.copyOf(settings.getExcludePatterns()), settings.getChatEnabled());
    var llm = new LlmSettingsV2(
        blankToNull(settings.getServerExecutablePath()), settings.getContextLength(),
        settings.getMaxTokens(), settings.getGpuLayers(),
        blankToNull(settings.getLlmModelPath()), blankToNull(settings.getLlamaLibPath()));
    String basePath = settings.getIndexBasePath();
    List<String> indexPaths = basePath == null || basePath.isBlank() ? List.of() : List.of(basePath);
    return new SettingsV2(ui, llm, indexPaths, persistenceMode.name().toLowerCase(Locale.ROOT));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
