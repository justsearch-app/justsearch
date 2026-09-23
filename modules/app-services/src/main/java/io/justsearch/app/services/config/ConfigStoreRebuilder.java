/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.config;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.resolved.ConfigChangedEvent;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assembles configuration for the accepted settings owner and the pre-inference boot refresh.
 *
 * <p>Runtime producers submit candidates through SettingsService. The commit coordinator uses
 * {@link #prepare} before physical replacement, then publishes the prepared snapshot. Only
 * HeadlessApp's post-discovery boot step calls {@link #rebuild}; it writes no settings file.
 */
public final class ConfigStoreRebuilder {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  /**
   * The boot-time hardware probe, so a rebuild does not silently drop ordinal 150.
   *
   * <p>{@link #rebuild} re-derives the config from scratch, but ordinal-150 values do not come from
   * any source it can re-read: the probe runs once, at startup, in the Engine. Before tempdoc 883 the
   * only ordinal-150 values were GPU flags, and they survived a rebuild by ALSO being written as
   * system properties — which is the promotion pattern 883 deletes, and which resolves at ordinal
   * 500 and so reports as {@code jvm_arg}. The derived context window must not acquire the same
   * lie, so the probe result is remembered here and re-contributed at its own ordinal instead.
   *
   * <p>Process-wide static for the same reason {@code ConfigStore.setGlobal} is: there is one
   * hardware probe per process, and later accepted settings preparation must retain it.
   */
  private static volatile Map<String, String> autoDetected = Map.of();

  private ConfigStoreRebuilder() {}

  /**
   * Records the startup hardware probe (ordinal 150) so later rebuilds keep it.
   *
   * <p>Called once by {@code HeadlessApp} with the same map it passes to
   * {@link ResolvedConfigBuilder#contributeAutoDetected} — keeping the initial build and every
   * rebuild on one set of values by construction.
   */
  public static void rememberAutoDetected(Map<String, String> detected) {
    autoDetected = detected == null ? Map.of() : Map.copyOf(detected);
  }

  /**
   * Prepares an immutable ResolvedConfig from all sources without publishing it.
   *
   * <p>All fallible preparation, including settings serialization, occurs before a ConfigStore
   * snapshot can be swapped. Preparation failures propagate to the caller.
   *
   * <p>Re-reads env vars, system properties, YAML, and UI settings, and re-contributes the
   * remembered startup hardware probe at ordinal 150 (see {@link #rememberAutoDetected}).
   *
   * @param settings current UI settings (if null, UI settings contribution is skipped)
   * @return the prepared immutable configuration snapshot
   */
  public static ResolvedConfig prepare(UiSettings settings) {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(autoDetected);
    builder.contributeBaseSources();
    if (settings != null) {
      contributeUiSettings(builder, settings);
    }
    ResolvedConfig selected = builder.build();
    var gpuSelection = selected.resolution("justsearch.gpu.layers");
    if (autoDetected.containsKey("justsearch.context.size") && gpuSelection != null && gpuSelection.isResolved()) {
      // The hardware observation survives rebuilds; its context projection depends on the
      // currently selected GPU policy. Unknown GPU state cannot invalidate a remembered window.
      builder.contributeAutoDetected(Map.of("justsearch.context.size", String.valueOf(
          io.justsearch.app.inference.ContextWindowPolicy.autoTopRung(selected.ai().gpuLayers() > 0))));
      return builder.build();
    }
    return selected;
  }

  /**
   * Publishes the boot refresh after hardware/native-path discovery and before inference starts.
   *
   * <p>The snapshot swap and listener notification are separate so callers that need stronger
   * ownership can perform the notification outside their publication lock. This convenience
   * method runs only during boot and therefore performs both operations directly. The executable
   * settings-publication guard excludes runtime producers from this entry.
   *
   * @param store the ConfigStore to update (if null, this is a no-op)
   * @param settings current UI settings (if null, UI settings contribution is skipped)
   */
  public static void rebuild(ConfigStore store, UiSettings settings) {
    if (store == null) return;
    ResolvedConfig prepared = prepare(settings);
    ConfigChangedEvent event = store.swap(prepared);
    store.notifyListeners(event);
  }

  /**
   * Forwards UI settings to a {@link ResolvedConfigBuilder} at ordinal 300 (settings.json).
   *
   * <p>Called during initial startup and when the user changes settings at runtime. Centralizes
   * the mapping so that both paths stay in sync (fixes M9 duplication). Relocated from
   * {@code HeadlessApp.contributeUiSettings} as part of tempdoc 519 §9 Block B3.0.e.
   */
  public static void contributeUiSettings(ResolvedConfigBuilder builder, UiSettings settings) {
    builder.putSettings("justsearch.index.base_path", settings.getIndexBasePath());
    builder.putSettings("justsearch.llm.model_path", settings.getLlmModelPath());
    // ${user.home} expansion used to happen only in SettingsController's server.exe promotion, so a
    // placeholder resolved differently before and after a settings PUT. Tempdoc 883 decision 4
    // slice 2 deleted that promotion; the expansion moves here, where the value actually enters the
    // resolver, and now applies on every path (boot and PUT alike).
    builder.putSettings(
        "justsearch.server.exe",
        PlatformPaths.expandUserHomePlaceholders(settings.getServerExecutablePath()));
    // Tempdoc 374 sandbox round 4 finding D/E: forward the per-encoder model
    // paths that AiInstallService.applyOnnxSettings persists, so the worker's
    // resolved-config snapshot gets justsearch.<feature>.model_path keys and
    // OnnxModelDiscovery resolves to the installed dirs instead of returning
    // "not found at any standard location" after Install AI completes.
    // Skip blanks so an empty UiSettings field doesn't override a YAML or
    // env-var value at lower/equal ordinals.
    putSettingIfPresent(
        builder, "justsearch.embed.onnx.model_path", settings.getEmbedOnnxModelPath());
    putSettingIfPresent(builder, "justsearch.rerank.model_path", settings.getRerankerModelPath());
    putSettingIfPresent(builder, "justsearch.ner.model_path", settings.getNerModelPath());
    putSettingIfPresent(builder, "justsearch.splade.model_path", settings.getSpladeModelPath());
    putSettingIfPresent(
        builder, "justsearch.citation.scorer.model_path", settings.getCitationScorerModelPath());
    if (settings.configuredGpuLayers() != null) {
      builder.putSettings("justsearch.gpu.layers", String.valueOf(settings.getGpuLayers()));
    }
    if (settings.configuredApiPort() != null) {
      builder.putSettings("justsearch.api.port", String.valueOf(settings.configuredApiPort()));
    }
    if (settings.getContextLength() > 0) {
      builder.putSettings("justsearch.context.size", String.valueOf(settings.getContextLength()));
    }
    List<String> excludePatterns = settings.getExcludePatterns();
    if (excludePatterns != null && !excludePatterns.isEmpty()) {
      try {
        builder.putSettings(
            "justsearch.ui.exclude_patterns", JSON.writeValueAsString(excludePatterns));
      } catch (Exception failure) {
        throw new IllegalStateException("Failed to serialize UI exclude patterns", failure);
      }
    }
  }

  private static void putSettingIfPresent(
      ResolvedConfigBuilder builder, String key, String value) {
    if (value != null && !value.isBlank()) {
      builder.putSettings(key, value);
    }
  }
}
