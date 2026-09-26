/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.LlmSettingsV2;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.UiSettingsV2;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Pure partial-patch rules and index-path preparation, shared by identity and candidate creation. */
final class SettingsPatch {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SettingsPatch.class);
  private SettingsPatch() {}

  /** Normalize only supplied values through the same setters used for the actual mutation. */
  static SettingsV2 normalize(SettingsV2 input) {
    UiSettings normalized = merge(new UiSettings(), input, true);
    UiSettingsV2 ui = input.ui();
    if (ui != null) {
      ui = new UiSettingsV2(
          ui.theme() == null ? null : normalized.getTheme(),
          ui.highContrast(), ui.density() == null ? null : normalized.getDensity(),
          ui.vimMode(), ui.defaultAction() == null ? null : normalized.getDefaultAction(),
          ui.inspectorWidth() == null ? null : normalized.getInspectorWidth(),
          ui.pauseIndexingDuringAi(), ui.mode() == null ? null : normalized.getMode(),
          ui.hasSeenTrustLoopNudge(),
          ui.excludePatterns() == null ? null : List.copyOf(normalized.getExcludePatterns()), ui.chatEnabled());
    }
    LlmSettingsV2 llm = input.llm();
    if (llm != null) {
      llm = new LlmSettingsV2(llm.serverExecutable(),
          llm.contextWindow() == null ? null : normalized.getContextLength(),
          llm.maxTokens() == null ? null : normalized.getMaxTokens(),
          llm.gpuLayers() == null ? null : normalized.configuredGpuLayers(),
          llm.modelPath(), llm.llamaLibPath());
    }
    if (ui != null && ui.theme() == null && ui.highContrast() == null && ui.density() == null
        && ui.vimMode() == null && ui.defaultAction() == null && ui.inspectorWidth() == null
        && ui.pauseIndexingDuringAi() == null && ui.mode() == null && ui.hasSeenTrustLoopNudge() == null
        && ui.excludePatterns() == null && ui.chatEnabled() == null) ui = null;
    if (llm != null && llm.serverExecutable() == null && llm.contextWindow() == null
        && llm.maxTokens() == null && llm.gpuLayers() == null && llm.modelPath() == null
        && llm.llamaLibPath() == null) llm = null;
    List<String> paths = input.indexPaths();
    paths = paths == null || paths.isEmpty() ? null : List.of(normalized.getIndexBasePath());
    return new SettingsV2(ui, llm, paths, null, input.witness(), null, null,
        input.apiPort() == null ? null : normalized.configuredApiPort());
  }

  /** Merges an incoming {@link SettingsV2} into the existing {@link UiSettings}. */
  static UiSettings merge(UiSettings base, SettingsV2 incoming, boolean applyMode) {
    if (incoming == null) {
      return base;
    }

    if (incoming.apiPort() != null) {
      base.setApiPort(incoming.apiPort());
    }

    UiSettingsV2 ui = incoming.ui();
    if (ui != null) {
      if (ui.theme() != null) base.setTheme(ui.theme());
      if (ui.highContrast() != null) base.setHighContrast(ui.highContrast());
      if (ui.density() != null) base.setDensity(ui.density());
      if (ui.vimMode() != null) base.setVimMode(ui.vimMode());
      if (ui.defaultAction() != null) base.setDefaultAction(ui.defaultAction());
      if (ui.inspectorWidth() != null) base.setInspectorWidth(ui.inspectorWidth());
      if (ui.pauseIndexingDuringAi() != null) base.setPauseIndexingDuringAi(ui.pauseIndexingDuringAi());
      if (applyMode && ui.mode() != null) base.setMode(ui.mode());
      if (ui.hasSeenTrustLoopNudge() != null) base.setTrustLoopNudgeSeen(ui.hasSeenTrustLoopNudge());
      if (ui.excludePatterns() != null) base.setExcludePatterns(ui.excludePatterns());
      if (ui.chatEnabled() != null) base.setChatEnabled(ui.chatEnabled());
    }

    LlmSettingsV2 llm = incoming.llm();
    if (llm != null) {
      if (llm.serverExecutable() != null) base.setServerExecutablePath(llm.serverExecutable());
      if (llm.contextWindow() != null) base.setContextLength(llm.contextWindow());
      if (llm.maxTokens() != null) base.setMaxTokens(llm.maxTokens());
      if (llm.gpuLayers() != null) base.setGpuLayers(llm.gpuLayers());
      if (llm.modelPath() != null) base.setLlmModelPath(llm.modelPath());
      if (llm.llamaLibPath() != null) base.setLlamaLibPath(llm.llamaLibPath());
    }

    List<String> indexPaths = incoming.indexPaths();
    if (indexPaths != null && !indexPaths.isEmpty()) {
      base.setIndexBasePath(indexPaths.get(0));
    }

    return base;
  }

  static String validateIndexPath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return null;
    }
    try {
      Path path = Path.of(rawPath.trim());
      if (path.getParent() == null) {
        return "Index path must not be a filesystem root";
      }
      if (!Files.exists(path)) {
        return "Index path does not exist";
      }
      if (!Files.isDirectory(path)) {
        return "Index path must point to a directory";
      }
      if (!Files.isReadable(path)) {
        return "Index path is not readable";
      }
      if (!Files.isWritable(path)) {
        return "Index path is not writable";
      }

      // Allow:
      // - empty directory (new index root; Worker will initialize state.json + indices/)
      // - legacy Lucene directory (segments_N directly under the chosen path)
      // - generation-scoped JustSearch root (state.json and/or indices/ directory)
      boolean hasAnyEntries = false;
      boolean hasLuceneSegments = false;
      boolean hasStateJson = false;
      boolean hasIndicesDir = false;
      try (var stream = Files.list(path)) {
        var it = stream.iterator();
        while (it.hasNext()) {
          Path entry = it.next();
          hasAnyEntries = true;
          String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
          if (name.startsWith("segments")) {
            hasLuceneSegments = true;
            break;
          }
          if (name.equals("indices") && Files.isDirectory(entry)) {
            hasIndicesDir = true;
          }
          if (name.equals("state.json") || name.startsWith("state.json.")) {
            hasStateJson = true;
          }
        }
      }

      if (!hasAnyEntries) {
        return null;
      }
      if (hasLuceneSegments || hasStateJson || hasIndicesDir) {
        return null;
      }
      return "Index path must be empty or point to an existing JustSearch index root";
    } catch (Exception e) {
      log.warn("Index path validation failed for {}", rawPath, e);
      return "Invalid index path";
    }
  }

}
