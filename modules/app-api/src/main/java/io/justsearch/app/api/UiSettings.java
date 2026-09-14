/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Mutable POJO representing persisted UI settings (theme, splitter positions, window bounds).
 */
public final class UiSettings {

  /**
   * Schema version for settings migration (ADR-0008 M1). Increment when making
   * breaking structural changes to settings.json. Absent/zero in pre-schema files.
   */
  private int schemaVersion = 1;

  private int version = 1;
  private String theme = "system";
  private final SplitSettings splits = new SplitSettings();
  private final WindowSettings window = new WindowSettings();
  private String indexBasePath = "";

  // UI-only fields (previously client-side only; now canonicalized via v2 API)
  private boolean highContrast = false;
  private String density = "comfort";
  private boolean vimMode = false;
  private String defaultAction = "open";
  private int inspectorWidth = 0;
  private boolean pauseIndexingDuringAi = false;

  // v2 additions (UI market readiness)
  // - mode: progressive disclosure (simple/advanced)
  // - trustLoopNudgeSeen: one-time citations teaching moment
  // - excludePatterns: glob patterns to exclude from indexing/search
  private String mode = "simple";
  private boolean trustLoopNudgeSeen = false;
  private List<String> excludePatterns = new ArrayList<>();

  // Tempdoc 737 Phase 1 — RuntimeSpec desired-state bit: does the user want the chat engine on?
  // Nullable by design: {@code null} means "never explicitly persisted" (distinct from an explicit
  // {@code false}), which the boot autostart-seed and the reconciler's precedence logic depend on
  // (RuntimeSpecStore). Jackson round-trips it safely (whole-file POJO, additive,
  // FAIL_ON_UNKNOWN_PROPERTIES=false). Effective default is FALSE (null resolves to false in
  // RuntimeSpec), matching today's autostart-default-false — a true default would surprise-start
  // engines at boot.
  private Boolean chatEnabled = null;


  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version <= 0 ? 1 : version;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(int schemaVersion) {
    this.schemaVersion = Math.max(0, schemaVersion);
  }

  public String getTheme() {
    return theme;
  }

  public void setTheme(String theme) {
    this.theme = theme == null || theme.isBlank() ? "system" : theme;
  }

  public SplitSettings getSplits() {
    return splits;
  }

  public double getResultsDividerPosition() {
    return splits.getLeftPane();
  }

  public void setResultsDividerPosition(double position) {
    splits.setLeftPane(position);
  }

  public WindowSettings getWindow() {
    return window;
  }

  public String getIndexBasePath() {
    return indexBasePath == null ? "" : indexBasePath;
  }

  public void setIndexBasePath(String indexBasePath) {
    this.indexBasePath = indexBasePath == null ? "" : indexBasePath.trim();
  }

  public boolean isHighContrast() {
    return highContrast;
  }

  public void setHighContrast(boolean highContrast) {
    this.highContrast = highContrast;
  }

  public String getDensity() {
    return density == null || density.isBlank() ? "comfort" : density;
  }

  public void setDensity(String density) {
    this.density = density == null || density.isBlank() ? "comfort" : density;
  }

  public boolean isVimMode() {
    return vimMode;
  }

  public void setVimMode(boolean vimMode) {
    this.vimMode = vimMode;
  }

  public String getDefaultAction() {
    return defaultAction == null || defaultAction.isBlank() ? "open" : defaultAction;
  }

  public void setDefaultAction(String defaultAction) {
    this.defaultAction = defaultAction == null || defaultAction.isBlank() ? "open" : defaultAction;
  }

  public int getInspectorWidth() {
    return inspectorWidth;
  }

  public void setInspectorWidth(int inspectorWidth) {
    this.inspectorWidth = Math.max(0, inspectorWidth);
  }

  public boolean isPauseIndexingDuringAi() {
    return pauseIndexingDuringAi;
  }

  public void setPauseIndexingDuringAi(boolean pauseIndexingDuringAi) {
    this.pauseIndexingDuringAi = pauseIndexingDuringAi;
  }

  public String getMode() {
    String raw = mode == null ? "" : mode.trim();
    if (raw.isBlank()) return "simple";
    String normalized = raw.toLowerCase(Locale.ROOT);
    return "advanced".equals(normalized) ? "advanced" : "simple";
  }

  public void setMode(String mode) {
    String raw = mode == null ? "" : mode.trim();
    if (raw.isBlank()) {
      this.mode = "simple";
      return;
    }
    String normalized = raw.toLowerCase(Locale.ROOT);
    this.mode = "advanced".equals(normalized) ? "advanced" : "simple";
  }

  public boolean isTrustLoopNudgeSeen() {
    return trustLoopNudgeSeen;
  }

  public void setTrustLoopNudgeSeen(boolean trustLoopNudgeSeen) {
    this.trustLoopNudgeSeen = trustLoopNudgeSeen;
  }

  /**
   * Nullable desired-state chat bit (tempdoc 737). {@code null} = never explicitly set;
   * {@code true}/{@code false} = explicit user/policy intent. Read via {@code RuntimeSpec}.
   */
  public Boolean getChatEnabled() {
    return chatEnabled;
  }

  public void setChatEnabled(Boolean chatEnabled) {
    this.chatEnabled = chatEnabled;
  }

  public List<String> getExcludePatterns() {
    if (excludePatterns == null) {
      excludePatterns = new ArrayList<>();
    }
    return excludePatterns;
  }

  public void setExcludePatterns(List<String> excludePatterns) {
    if (excludePatterns == null || excludePatterns.isEmpty()) {
      this.excludePatterns = new ArrayList<>();
      return;
    }

    // Clean + dedupe (preserve insertion order) and cap to avoid abusive payloads.
    LinkedHashSet<String> cleaned = new LinkedHashSet<>();
    for (String p : excludePatterns) {
      if (p == null) continue;
      String s = p.trim();
      if (s.isBlank()) continue;
      cleaned.add(s);
      if (cleaned.size() >= 512) break;
    }
    this.excludePatterns = new ArrayList<>(cleaned);
  }

  // Tempdoc 840 Phase 2 — per-component install INTENT: registry package ids the user chose not to
  // install ("I don't want the reranker"). A current PREFERENCE, not a record of what happened: it
  // must exist before any install run and survive runs that never complete, which is why it lives
  // here and not in the install contract. The contract's SkipCause.USER_DECLINED is the historical
  // counterpart; flipping this back re-opens the component as an offerable gap, which reading the
  // contract could never do.
  //
  // Advisory only. InstallPlanner honors an id here only for a package whose Necessity is
  // user-declinable, so a stale or hand-edited entry naming `embedding` or `cuda-runtime` cannot
  // turn a mandatory component off.
  private List<String> declinedAiPackages = new ArrayList<>();

  public List<String> getDeclinedAiPackages() {
    if (declinedAiPackages == null) {
      declinedAiPackages = new ArrayList<>();
    }
    return declinedAiPackages;
  }

  public void setDeclinedAiPackages(List<String> declinedAiPackages) {
    if (declinedAiPackages == null || declinedAiPackages.isEmpty()) {
      this.declinedAiPackages = new ArrayList<>();
      return;
    }

    // Clean + dedupe (preserve insertion order) and cap to avoid abusive payloads — the same shape
    // as setExcludePatterns above; the registry has 7 packages, so the cap is pure abuse defense.
    LinkedHashSet<String> cleaned = new LinkedHashSet<>();
    for (String p : declinedAiPackages) {
      if (p == null) continue;
      String s = p.trim();
      if (s.isBlank()) continue;
      cleaned.add(s);
      if (cleaned.size() >= 512) break;
    }
    this.declinedAiPackages = new ArrayList<>(cleaned);
  }

  private String llmModelPath = "";
  private String llamaLibPath = "";
  // BYO AI: explicit llama-server executable path (optional override).
  private String serverExecutablePath = "";
  // One override value: null = automatic, zero = explicit CPU, positive = GPU offload.
  private Integer gpuLayers;
  // Tempdoc 883 decision 1: 0 = auto. The context window is derived from the backend and the
  // launch ladder, not stored as a preference — this field is an override only. It is surfaced at
  // Settings → AI → Agent → Context window (wire name `llm.contextWindow`), where the readout
  // beside it comes from the running engine rather than from this value, because 0 is what almost
  // every installation stores.
  private int contextLength = 0;
  private int maxTokens = 1024;

  // ONNX feature model directories — written by Install AI per package so the
  // Head's resolveOnnxFeatures() reports installed features as active rather
  // than reason="not_found". Mirror of the per-feature env keys in EnvRegistry
  // (EMBED_ONNX_MODEL_PATH, RERANK_MODEL_PATH, NER_MODEL_PATH,
  // SPLADE_MODEL_PATH, CITATION_SCORER_MODEL_PATH).
  private String embedOnnxModelPath = "";
  private String rerankerModelPath = "";
  private String nerModelPath = "";
  private String spladeModelPath = "";
  private String citationScorerModelPath = "";

  public String getLlmModelPath() {
    return llmModelPath == null ? "" : llmModelPath;
  }

  public void setLlmModelPath(String path) {
    this.llmModelPath = path == null ? "" : path;
  }

  public String getLlamaLibPath() {
    return llamaLibPath == null ? "" : llamaLibPath;
  }

  public void setLlamaLibPath(String path) {
    this.llamaLibPath = path == null ? "" : path;
  }

  public String getServerExecutablePath() {
    return serverExecutablePath == null ? "" : serverExecutablePath;
  }

  public void setServerExecutablePath(String path) {
    this.serverExecutablePath = path == null ? "" : path;
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  public int getGpuLayers() {
    return gpuLayers == null ? 0 : gpuLayers;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("gpuLayers")
  public Integer configuredGpuLayers() {
    return gpuLayers;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("gpuLayers")
  public void setGpuLayers(Integer gpuLayers) {
    this.gpuLayers = gpuLayers == null ? null : Math.max(0, gpuLayers);
  }

  /** The context-window override in tokens, or {@code 0} meaning "auto" (tempdoc 883). */
  public int getContextLength() {
    return contextLength;
  }

  /**
   * Sets the context-window override. {@code 0} (and any non-positive value) means <b>auto</b> and
   * survives the round trip unchanged; a positive value is a real override and is floored at 512.
   *
   * <p>The old unconditional {@code Math.max(512, ...)} made "auto" unrepresentable: Jackson
   * deserializes through this setter, so a stored {@code 0} became a real 512-token override that
   * then outranked the derived window at ordinal 300 (tempdoc 883 fold [R5]).
   */
  public void setContextLength(int contextLength) {
    this.contextLength = contextLength <= 0 ? 0 : Math.max(512, contextLength);
  }

  public int getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(int maxTokens) {
    // Guard rails: keep the budget sane and non-zero (caller can still override via env/sysprop if needed).
    this.maxTokens = Math.max(16, Math.min(16_384, maxTokens));
  }

  public String getEmbedOnnxModelPath() {
    return embedOnnxModelPath == null ? "" : embedOnnxModelPath;
  }

  public void setEmbedOnnxModelPath(String path) {
    this.embedOnnxModelPath = path == null ? "" : path;
  }

  public String getRerankerModelPath() {
    return rerankerModelPath == null ? "" : rerankerModelPath;
  }

  public void setRerankerModelPath(String path) {
    this.rerankerModelPath = path == null ? "" : path;
  }

  public String getNerModelPath() {
    return nerModelPath == null ? "" : nerModelPath;
  }

  public void setNerModelPath(String path) {
    this.nerModelPath = path == null ? "" : path;
  }

  public String getSpladeModelPath() {
    return spladeModelPath == null ? "" : spladeModelPath;
  }

  public void setSpladeModelPath(String path) {
    this.spladeModelPath = path == null ? "" : path;
  }

  public String getCitationScorerModelPath() {
    return citationScorerModelPath == null ? "" : citationScorerModelPath;
  }

  public void setCitationScorerModelPath(String path) {
    this.citationScorerModelPath = path == null ? "" : path;
  }

  /** Settings for panel splits (results vs summary). */
  public static final class SplitSettings {
    private double leftPane = 0.5;
    private double rightPane = 0.5;

    public double getLeftPane() {
      return clamp(leftPane);
    }

    public void setLeftPane(double value) {
      this.leftPane = clamp(value);
      this.rightPane = 1.0 - this.leftPane;
    }

    public double getRightPane() {
      return clamp(rightPane);
    }

    public void setRightPane(double value) {
      this.rightPane = clamp(value);
      this.leftPane = 1.0 - this.rightPane;
    }

    private static double clamp(double value) {
      if (Double.isNaN(value) || Double.isInfinite(value)) {
        return 0.5;
      }
      return Math.min(1.0, Math.max(0.0, value));
    }
  }

  public static final class WindowSettings {
    private double width = 1280;
    private double height = 800;
    private boolean maximized = true;
    private String lastShownAt = "";

    public double getWidth() {
      return width;
    }

    public void setWidth(double width) {
      this.width = Math.max(1024, width);
    }

    public double getHeight() {
      return height;
    }

    public void setHeight(double height) {
      this.height = Math.max(700, height);
    }

    public boolean isMaximized() {
      return maximized;
    }

    public void setMaximized(boolean maximized) {
      this.maximized = maximized;
    }

    public String getLastShownAt() {
      return lastShownAt;
    }

    public void stampLastShown() {
      this.lastShownAt = Instant.now().toString();
    }
  }
}
