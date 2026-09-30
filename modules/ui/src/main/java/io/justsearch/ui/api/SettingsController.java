/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.SettingsService;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.api.settings.LlmSettingsV2;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.UiSettingsV2;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP routing layer for settings endpoints. SettingsService interface is owned by
 * {@code io.justsearch.app.services.settings.SettingsServiceImpl} (tempdoc 519 §9 Step 3),
 * which delegates to {@link #resetToDefaults} via a method reference. The reset logic
 * stays here because its inputs ({@code SettingsV2}, {@code UiSettingsV2},
 * {@code LlmSettingsV2}) are a ui-internal DTO cluster outside §9's literal scope.
 */
public class SettingsController {
  private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
  static final String UI_MODE_INTENT_HEADER = "X-JustSearch-UI-Mode-Intent";
  private static final int MAX_TRACKED_UI_MODE_CLIENTS = 64;
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  private final UiSettingsStore settingsStore;
  private final Path defaultIndexBasePath;
  private final Telemetry telemetry;
  private final ConfigStore configStore;
  private final Runnable chatEnabledChanged; // nullable — tempdoc 737 Phase 1 spec-write nudge
  private final Map<String, Long> latestUiModeIntentByClient =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
          return size() > MAX_TRACKED_UI_MODE_CLIENTS;
        }
      };

  public SettingsController(
      UiSettingsStore settingsStore,
      Path defaultIndexBasePath,
      Telemetry telemetry) {
    this(settingsStore, defaultIndexBasePath, telemetry, null, null);
  }

  public SettingsController(
      UiSettingsStore settingsStore,
      Path defaultIndexBasePath,
      Telemetry telemetry,
      ConfigStore configStore) {
    this(settingsStore, defaultIndexBasePath, telemetry, configStore, null);
  }

  /**
   * Tempdoc 737 Phase 1: {@code chatEnabledChanged} fires after a persisted settings write
   * changes {@code chatEnabled} — the runtime reconciler's spec-write nudge. A persisted intent
   * must converge now, not at next boot. Nullable (tests / paths without the runtime authority).
   */
  public SettingsController(
      UiSettingsStore settingsStore,
      Path defaultIndexBasePath,
      Telemetry telemetry,
      ConfigStore configStore,
      Runnable chatEnabledChanged) {
    this.settingsStore = settingsStore;
    this.defaultIndexBasePath = defaultIndexBasePath;
    this.telemetry = telemetry;
    this.configStore = configStore;
    this.chatEnabledChanged = chatEnabledChanged;
  }

  // ==================== v2 Canonical Settings API ====================

  /**
   * GET /api/settings/v2 - Returns the canonical {@link SettingsV2} shape.
   */
  public void handleGetSettingsV2(Context ctx) {
    UiSettings settings = settingsStore.load();
    if ((settings.getIndexBasePath() == null || settings.getIndexBasePath().isBlank())
        && defaultIndexBasePath != null) {
      settings.setIndexBasePath(defaultIndexBasePath.toString());
    }
    ctx.json(toSettingsV2(settings));
  }

  /**
   * POST /api/settings/v2 - Accepts the canonical {@link SettingsV2} shape and persists it.
   */
  public void handleUpdateSettingsV2(Context ctx) {
    if (!settingsStore.mode().isWritable()) {
      ctx.status(409).json(ApiErrorHandler.toResponse(
          ApiErrorCode.SETTINGS_READ_ONLY,
          "Settings are read-only in " + settingsStore.mode().name() + " mode",
          telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    // Every request is a partial patch over a whole-document durable store. Keep load + merge +
    // save (and its runtime projection) under the store identity so concurrent requests through
    // this or another controller instance cannot each save a snapshot derived from the same old
    // document and silently clobber one another.
    synchronized (settingsStore) {
      UiSettings current = settingsStore.load();
      // Tempdoc 737: capture BEFORE mergeV2Into — it mutates `current` in place.
      Boolean chatEnabledBefore = current.getChatEnabled();
      try {
        SettingsV2 incoming = MAPPER.readValue(ctx.body(), SettingsV2.class);
        UiModeIntent modeIntent = modeIntentOf(ctx, incoming);
        Long latestSequence =
            modeIntent == null ? null : latestUiModeIntentByClient.get(modeIntent.clientId());
        boolean applyMode =
            modeIntent == null
                || latestSequence == null
                || modeIntent.sequence() > latestSequence;
        UiSettings merged = mergeV2Into(current, incoming, applyMode);

        String validationError = validateIndexPath(merged.getIndexBasePath());
        if (validationError != null) {
          ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_PATH, validationError, telemetry, ApiErrorHandler.routeOf(ctx)));
          return;
        }

        settingsStore.save(merged);
        rebuildConfigStore(merged);
        if (modeIntent != null && applyMode) {
          latestUiModeIntentByClient.put(modeIntent.clientId(), modeIntent.sequence());
        }
        if (chatEnabledChanged != null
            && !java.util.Objects.equals(chatEnabledBefore, merged.getChatEnabled())) {
          chatEnabledChanged.run();
        }
        ctx.json(toSettingsV2(merged));
        log.info("Settings updated via API v2");
      } catch (Exception e) {
        log.error("Failed to update settings (v2)", e);
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Invalid settings format", telemetry, ApiErrorHandler.routeOf(ctx)));
      }
    }
  }

  /** Maps internal {@link UiSettings} to the canonical {@link SettingsV2} DTO. */
  private SettingsV2 toSettingsV2(UiSettings s) {
    UiSettingsV2 ui = new UiSettingsV2(
        s.getTheme(),
        s.isHighContrast(),
        s.getDensity(),
        s.isVimMode(),
        s.getDefaultAction(),
        s.getInspectorWidth() > 0 ? s.getInspectorWidth() : null,
        s.isPauseIndexingDuringAi(),
        s.getMode(),
        s.isTrustLoopNudgeSeen(),
        s.getExcludePatterns(),
        s.getChatEnabled()
    );

    LlmSettingsV2 llm = new LlmSettingsV2(
        blankToNull(s.getServerExecutablePath()),
        s.getContextLength(),
        s.getMaxTokens(),
        s.getGpuLayers(),
        blankToNull(s.getLlmModelPath()),
        blankToNull(s.getLlamaLibPath())
    );

    List<String> indexPaths = new ArrayList<>();
    String basePath = s.getIndexBasePath();
    if (basePath != null && !basePath.isBlank()) {
      indexPaths.add(basePath);
    }

    String mode = settingsStore.mode().name().toLowerCase(Locale.ROOT);
    return new SettingsV2(ui, llm, indexPaths, mode);
  }

  /** Merges an incoming {@link SettingsV2} into the existing {@link UiSettings}. */
  private UiSettings mergeV2Into(UiSettings base, SettingsV2 incoming, boolean applyMode) {
    if (incoming == null) {
      return base;
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

  /**
   * Parses the frontend's per-client monotonic mode intent only when this patch actually carries a
   * mode. Older intents remain valid partial patches for their other fields, but cannot restore an
   * earlier mode after the browser has timed out and advanced its queue.
   */
  private static UiModeIntent modeIntentOf(Context ctx, SettingsV2 incoming) {
    if (incoming == null || incoming.ui() == null || incoming.ui().mode() == null) {
      return null;
    }
    String raw = ctx.header(UI_MODE_INTENT_HEADER);
    if (raw == null || raw.isBlank()) {
      return null; // Compatibility for existing API clients.
    }
    int separator = raw.lastIndexOf(':');
    if (separator < 1 || separator == raw.length() - 1) {
      throw new IllegalArgumentException("Invalid UI mode intent header");
    }
    String clientId = raw.substring(0, separator);
    if (clientId.length() > 96 || !clientId.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("Invalid UI mode intent client id");
    }
    try {
      long sequence = Long.parseLong(raw.substring(separator + 1));
      if (sequence <= 0) {
        throw new IllegalArgumentException("UI mode intent sequence must be positive");
      }
      return new UiModeIntent(clientId, sequence);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid UI mode intent sequence", e);
    }
  }

  private record UiModeIntent(String clientId, long sequence) {}

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s;
  }

  /**
   * Rebuilds the ResolvedConfig with updated settings and swaps it into the ConfigStore.
   *
   * <p>This is the ONLY way a settings write reaches configuration. Tempdoc 883 decision 4 deleted
   * the {@code maybeApply*SysProp} promotions that used to run first: writing a GUI value into a
   * system property resolved it at ordinal 500 ({@code jvm_arg}), so
   * {@code /api/debug/effective-config} reported the user's own setting as an operator override.
   * {@code ConfigStoreRebuilder.contributeUiSettings} contributes the same keys at ordinal 300
   * ({@code settings.json}), where env vars (400) and a real {@code -D} (500) still win — by the
   * ordinal chain rather than by a sysprop write and a {@code .source} marker to un-tell it.
   */
  private void rebuildConfigStore(UiSettings settings) {
    ConfigStoreRebuilder.rebuild(configStore, settings);
  }

  private String validateIndexPath(String rawPath) {
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

  // ==========================================================================
  // SettingsService impl (slice 3a-2-c continuation).
  //
  // Reset FE-controlled fields to their canonical default values while
  // preserving admin-set fields (server exe, model path, llama lib path,
  // index base path, schema/version metadata, splits, window geometry).
  // The defaults match what UiSettings's field initializers declare; we
  // explicitly enumerate the FE-controlled subset rather than copying the
  // entire object so admin-managed fields are preserved by construction.
  // ==========================================================================
  public Map<String, Object> resetToDefaults() throws Exception {
    if (!settingsStore.mode().isWritable()) {
      throw new IllegalStateException(
          "Settings are read-only in " + settingsStore.mode().name() + " mode");
    }
    UiSettings current = settingsStore.load();

    io.justsearch.app.services.settings.SettingsResetDefaults.applyTo(current);

    settingsStore.save(current);
    rebuildConfigStore(current);

    SettingsV2 v2 = toSettingsV2(current);
    @SuppressWarnings("unchecked")
    Map<String, Object> out = MAPPER.convertValue(v2, Map.class);
    return out;
  }
}
