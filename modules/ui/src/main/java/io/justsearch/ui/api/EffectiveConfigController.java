/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.status.EffectiveConfigEntry;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.SystemAccess;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.SourceCandidate;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Implements GET /api/debug/effective-config (runtime grounding snapshot). */
public final class EffectiveConfigController {
  private static final int SCHEMA_VERSION = 1;
  // Tempdoc 883 decision 4 + its §C.5c residue: this report reads NO `*.source` marker sysprop any
  // more. It used to hold a private copy of the `ui_settings` marker literal (tempdoc 842's fourth
  // copy) to un-tell the precedence lie the boot-time settings promotions created; with the last
  // two promotions (index.base_path, llm.model_path) retired, every row is sourced from the
  // resolver's own ordinal chain and the copy is gone with the reason for it. The marker itself
  // still exists for `llm.model_path` — `ModelPathSource` and `InferenceConfig` own that vocabulary.

  private final Supplier<Integer> apiPortSupplier;
  @SuppressWarnings("unused")
  private final UiSettingsStore settingsStore; // best-effort
  private final EnterprisePolicyService policyService; // best-effort
  private final OnlineAiService onlineAiService; // best-effort (for inference runtime introspection)
  private final Path indexBasePath; // best-effort (resolved runtime value)
  private final ConfigStore configStore; // nullable (ordinal-chain resolution)
  private final io.justsearch.app.api.EngineAdmissionService engineAdmission;

  /** Backward-compatible constructor (no ConfigStore). */
  public EffectiveConfigController(
      Supplier<Integer> apiPortSupplier,
      UiSettingsStore settingsStore,
      EnterprisePolicyService policyService,
      OnlineAiService onlineAiService,
      Path indexBasePath) {
    this(apiPortSupplier, settingsStore, policyService, onlineAiService, indexBasePath, null);
  }

  public EffectiveConfigController(
      Supplier<Integer> apiPortSupplier,
      UiSettingsStore settingsStore,
      EnterprisePolicyService policyService,
      OnlineAiService onlineAiService,
      Path indexBasePath,
      ConfigStore configStore) {
    this(apiPortSupplier, settingsStore, policyService, onlineAiService, indexBasePath, configStore, null);
  }

  public EffectiveConfigController(
      Supplier<Integer> apiPortSupplier,
      UiSettingsStore settingsStore,
      EnterprisePolicyService policyService,
      OnlineAiService onlineAiService,
      Path indexBasePath,
      ConfigStore configStore,
      io.justsearch.app.api.EngineAdmissionService engineAdmission) {
    this.apiPortSupplier = apiPortSupplier;
    this.settingsStore = settingsStore;
    this.policyService = policyService;
    this.onlineAiService = onlineAiService;
    this.indexBasePath = indexBasePath;
    this.configStore = configStore;
    this.engineAdmission = engineAdmission;
  }

  public void handleGetEffectiveConfig(Context ctx) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", SCHEMA_VERSION);
    root.put("capturedAt", Instant.now().toString());

    Map<String, Object> process = new LinkedHashMap<>();
    process.put("pid", ProcessHandle.current().pid());
    Integer apiPort = safeGet(apiPortSupplier);
    if (apiPort != null && apiPort > 0) {
      process.put("apiPort", apiPort);
    }
    root.put("process", process);
    if (engineAdmission != null) {
      var limits = engineAdmission.limits();
      // The front still owns this request until its after-filter. Exclude exactly that work so
      // a quiescent diagnostic reads the background baseline rather than counting itself.
      int inspectingWork = RequestEngineWork.get(ctx) == null ? 0 : 1;
      int activeWorkCount = engineAdmission.activeWorkCount() - inspectingWork;
      if (activeWorkCount < 0) throw new IllegalStateException("Engine admission owner mismatch");
      root.put("engineAdmission", Map.of(
          "perContextLimit", limits.perContextLimit(),
          "aggregateLimit", limits.aggregateLimit(),
          "retryAfterSeconds", limits.retryAfterSeconds(),
          "activeWorkCount", activeWorkCount));
    }

    EffectivePolicy policy = null;
    try {
      if (policyService != null) {
        policy = policyService.snapshot();
      }
    } catch (Exception ignored) {
      policy = null;
    }

    OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo = null;
    try {
      if (onlineAiService instanceof OnlineAiRuntimeIntrospection introspection) {
        runtimeInfo = introspection.runtimeInfo();
      }
    } catch (Exception ignored) {
      runtimeInfo = null;
    }

    // Base dir for derived AI paths:
    // sysprop justsearch.home -> env JUSTSEARCH_HOME -> derived (user.dir)
    Path baseDir = resolveBaseDir();

    // Baseline inference config (env/sysprop + derived defaults). RuntimeInfo may override.
    InferenceConfig envInference;
    try {
      envInference = InferenceConfig.fromEnvironment(baseDir);
    } catch (Exception ignored) {
      envInference = null;
    }

    List<Map<String, Object>> keys = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Ports
    // -------------------------------------------------------------------------
    keys.add(keyJustsearchApiPortConfigured());
    keys.add(keyProcessApiPort(apiPort));
    keys.add(keyServerPort(runtimeInfo, envInference));

    // -------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------
    keys.add(keyJustsearchHome(baseDir));
    keys.add(keyJustsearchDataDir());
    keys.add(keyIndexBasePath());
    keys.add(keyModelsDir(baseDir));
    keys.add(keyServerExe(runtimeInfo, envInference));
    keys.add(keyLlmModelPath(baseDir, runtimeInfo, envInference));

    // -------------------------------------------------------------------------
    // AI model selection (filenames)
    // -------------------------------------------------------------------------
    keys.add(keySimpleString(
        "justsearch.vlm.model",
        EnvRegistry.VLM_MODEL.sysProp(),
        EnvRegistry.VLM_MODEL.envVar(),
        EnvRegistry.VLM_MODEL.getString("Qwen_Qwen3.5-9B-Q4_K_M.gguf"),
        "default"));
    keys.add(keySimpleString(
        "justsearch.mmproj.model",
        EnvRegistry.MMPROJ_MODEL.sysProp(),
        EnvRegistry.MMPROJ_MODEL.envVar(),
        EnvRegistry.MMPROJ_MODEL.getString("mmproj-F16.gguf"),
        "default"));
    // -------------------------------------------------------------------------
    // AI knobs
    // -------------------------------------------------------------------------
    keys.add(keyContextSize(envInference));
    keys.add(keyGpuLayers(runtimeInfo, envInference, policy));
    keys.add(keyAiDisabled());

    // -------------------------------------------------------------------------
    // Policy bridge keys (source of truth is effective policy)
    // -------------------------------------------------------------------------
    keys.add(keyPolicyBool("policy.gpu_acceleration_enabled", policy == null ? null : policy.gpuAccelerationEnabled()));
    keys.add(keyPolicyBool(
        "justsearch.policy.disallowExternalInferenceServers",
        policy == null ? null : policy.disallowExternalInferenceServers()));

    root.put("keys", keys);

    // -------------------------------------------------------------------------
    // Ordinal-chain resolution trace (from ConfigStore / ResolvedConfig)
    // -------------------------------------------------------------------------
    if (configStore != null) {
      root.put("resolvedConfig", buildResolvedConfigEntries());
    }

    ctx.json(root);
  }

  private List<EffectiveConfigEntry> buildResolvedConfigEntries() {
    ResolvedConfig config = configStore.get();
    Map<String, ConfigResolution> resolutions = config.resolutions();
    List<EffectiveConfigEntry> entries = new ArrayList<>(resolutions.size());

    for (ConfigResolution res : resolutions.values()) {
      List<EffectiveConfigEntry.CandidateEntry> candidates = new ArrayList<>(res.considered().size());
      for (SourceCandidate sc : res.considered()) {
        candidates.add(new EffectiveConfigEntry.CandidateEntry(sc.sourceName(), sc.ordinal(), sc.rawValue()));
      }
      entries.add(new EffectiveConfigEntry(
          res.key(), res.value(), res.sourceName(), res.sourceOrdinal(), res.sourceDetail(), candidates));
    }
    return entries;
  }

  // ---------------------------------------------------------------------------
  // Key builders
  // ---------------------------------------------------------------------------

  private Map<String, Object> keyJustsearchApiPortConfigured() {
    String sys = sysProp(EnvRegistry.API_PORT.sysProp());
    String env = envVar(EnvRegistry.API_PORT.envVar());
    Integer parsed = parseInt(sys != null ? sys : env);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.API_PORT.sysProp());
    details.put("envVar", EnvRegistry.API_PORT.envVar());
    if (sys != null) details.put("syspropValue", sys);
    if (env != null) details.put("envValue", env);

    String source;
    if (sys != null) source = "system_property";
    else if (env != null) source = "environment_variable";
    else source = "default";

    return key("justsearch.api.port", parsed, source, details);
  }

  private Map<String, Object> keyProcessApiPort(Integer apiPort) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("note", "Actual bound port of the running Local API server.");
    return key("process.apiPort", apiPort, "runtime", details);
  }

  private Map<String, Object> keyServerPort(OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo, InferenceConfig envInference) {
    Integer effective = null;
    if (runtimeInfo != null) {
      effective = runtimeInfo.serverPort();
    } else if (envInference != null) {
      effective = envInference.serverPort();
    }

    String sys = sysProp(EnvRegistry.SERVER_PORT.sysProp());
    String env = envVar(EnvRegistry.SERVER_PORT.envVar());
    Integer baseline = parseInt(sys != null ? sys : env);
    if (baseline == null) baseline = 8080;

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.SERVER_PORT.sysProp());
    details.put("envVar", EnvRegistry.SERVER_PORT.envVar());
    details.put("baseline", baseline);
    if (runtimeInfo != null) details.put("runtime", runtimeInfo.serverPort());

    String source;
    if (sys != null) source = "system_property";
    else if (env != null) source = "environment_variable";
    else source = "default";

    if (effective != null && !effective.equals(baseline)) {
      // Runtime config differs from baseline; surface that honestly.
      source = "runtime";
      List<Map<String, Object>> conflicts = new ArrayList<>();
      conflicts.add(Map.of("source", sourceForBaseline(sys, env), "value", baseline));
      details.put("conflicts", conflicts);
    }

    return key("justsearch.server.port", effective != null ? effective : baseline, source, details);
  }

  private Map<String, Object> keyJustsearchHome(Path baseDir) {
    String sys = sysProp(EnvRegistry.HOME.sysProp());
    String env = envVar(EnvRegistry.HOME.envVar());
    String derived = Path.of("").toAbsolutePath().toString();
    String value = sys != null ? sys : (env != null ? env : derived);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.HOME.sysProp());
    details.put("envVar", EnvRegistry.HOME.envVar());
    details.put("derived", derived);

    String source;
    if (sys != null) source = "system_property";
    else if (env != null) source = "environment_variable";
    else source = "derived";

    // Normalize the value to the Path we used for other derived computations.
    return key("justsearch.home", baseDir == null ? value : baseDir.toString(), source, details);
  }

  private Map<String, Object> keyJustsearchDataDir() {
    String canonical = sysProp(EnvRegistry.DATA_DIR.sysProp());
    String env = envVar(EnvRegistry.DATA_DIR.envVar());
    Path platformDefault = PlatformPaths.getPlatformDefault();

    String chosenRaw;
    String chosenSource;
    String chosenKey;

    if (canonical != null) {
      chosenRaw = canonical;
      chosenSource = "system_property";
      chosenKey = EnvRegistry.DATA_DIR.sysProp();
    } else if (env != null) {
      chosenRaw = env;
      chosenSource = "environment_variable";
      chosenKey = EnvRegistry.DATA_DIR.envVar();
    } else {
      chosenRaw = platformDefault == null ? Path.of("").toAbsolutePath().toString() : platformDefault.toString();
      chosenSource = "derived";
      chosenKey = "platform_default";
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.DATA_DIR.sysProp());
    details.put("envVar", EnvRegistry.DATA_DIR.envVar());
    details.put("winnerKey", chosenKey);

    List<Map<String, Object>> conflicts = new ArrayList<>();
    addConflictIfDifferent(conflicts, "system_property", canonical, chosenRaw);
    addConflictIfDifferent(conflicts, "environment_variable", env, chosenRaw);
    if (!conflicts.isEmpty()) {
      details.put("conflicts", conflicts);
    }

    return key("justsearch.data.dir", chosenRaw, chosenSource, details);
  }

  /**
   * Tempdoc 883 §C.5c residue — sourced from the RESOLVER's own provenance, not from a
   * {@code justsearch.index.base_path.source} marker sysprop.
   *
   * <p>The marker existed only because {@code settings.json} was promoted to a system property at
   * boot, which made a GUI-chosen index root report as {@code jvm_arg}; the row then read a second
   * sysprop to un-tell that. Both are gone: the value rides settings.json at ordinal 300 through
   * {@code ConfigStoreRebuilder.contributeUiSettings}, so the ordinal chain already knows who won.
   * Source names are the resolver's own strings, for the same reason the context-size row uses
   * them — a second vocabulary here would drift from the {@code resolvedConfig} block's.
   */
  private Map<String, Object> keyIndexBasePath() {
    final String sysprop = EnvRegistry.INDEX_BASE_PATH.sysProp();
    final String envVar = EnvRegistry.INDEX_BASE_PATH.envVar();
    ConfigResolution resolution =
        configStore != null ? configStore.get().resolution(sysprop) : null;
    boolean resolved = resolution != null && resolution.isResolved();
    String sys = sysProp(sysprop);
    String env = envVar(envVar);

    String value;
    if (indexBasePath != null) {
      value = indexBasePath.toString();
    } else if (resolved) {
      value = resolution.value();
    } else {
      // Best-effort default: <dataDir>/index/default
      try {
        value = PlatformPaths.resolveIndexPath("default").toString();
      } catch (Exception e) {
        value = "";
      }
    }

    // Nothing configured at any ordinal means the effective root was DERIVED from the data dir.
    String source = resolved ? resolution.sourceName() : "derived";

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", sysprop);
    details.put("envVar", envVar);
    if (indexBasePath != null) details.put("resolved", indexBasePath.toString());
    if (resolved) {
      details.put("resolvedValue", resolution.value());
      details.put("sourceOrdinal", resolution.sourceOrdinal());
      if (resolution.sourceDetail() != null) {
        details.put("sourceDetail", resolution.sourceDetail());
      }
    }
    if (sys != null) details.put("syspropValue", sys);
    if (env != null) details.put("envValue", env);

    List<Map<String, Object>> conflicts = new ArrayList<>();
    addConflictIfDifferent(conflicts, "system_property", sys, value);
    addConflictIfDifferent(conflicts, "environment_variable", env, value);
    if (!conflicts.isEmpty()) details.put("conflicts", conflicts);

    return key(sysprop, value, source, details);
  }

  private Map<String, Object> keyModelsDir(Path baseDir) {
    String sys = sysProp(EnvRegistry.MODELS_DIR.sysProp());
    String env = envVar(EnvRegistry.MODELS_DIR.envVar());

    Path resolved = baseDir;
    try {
      String raw = EnvRegistry.MODELS_DIR.getString("models");
      resolved = baseDir == null ? Path.of(raw) : baseDir.resolve(raw);
    } catch (Exception ignored) {
      // best-effort
    }

    String source;
    if (sys != null) source = "system_property";
    else if (env != null) source = "environment_variable";
    else source = "derived";

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.MODELS_DIR.sysProp());
    details.put("envVar", EnvRegistry.MODELS_DIR.envVar());
    if (baseDir != null) details.put("baseDir", baseDir.toString());

    return key("justsearch.models.dir", resolved == null ? null : resolved.toString(), source, details);
  }

  /**
   * The {@code justsearch.server.exe} row, sourced from the RESOLVER's provenance (tempdoc 883
   * decision 4 slice 2) exactly as {@link #keyContextSize} is.
   *
   * <p>The {@code justsearch.server.exe.source} marker is NOT read here any more. The marker still
   * exists — {@code RuntimeActivationService} and {@code HeadlessApp.maybeAutoSelectCuda12Variant}
   * write it as a genuine ownership token for a runtime GPU-variant switch — but the settings
   * promotion it used to disambiguate is deleted, so a GUI-chosen exe now resolves as
   * {@code settings.json} at ordinal 300 without needing a marker to say so.
   *
   * <p>The observed runtime value still wins {@code value} when a server is actually running: after
   * a variant switch the resolver holds the CONFIGURED exe and only the live runtime knows which
   * binary is serving.
   */
  private Map<String, Object> keyServerExe(
      OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo,
      InferenceConfig envInference) {
    ConfigResolution resolution =
        configStore != null ? configStore.get().resolution("justsearch.server.exe") : null;
    String sys = sysProp(EnvRegistry.SERVER_EXE.sysProp());
    String env = envVar(EnvRegistry.SERVER_EXE.envVar());

    String effective = runtimeInfo != null ? runtimeInfo.serverExecutable()
        : (envInference != null && envInference.serverExecutable() != null ? envInference.serverExecutable().toString() : null);

    String source;
    if (resolution != null && resolution.isResolved() && valuesMatchPath(resolution.value(), effective)) {
      source = resolution.sourceName();
    } else if (effective != null && !effective.isBlank()) {
      source = "derived";
    } else if (resolution != null && resolution.isResolved()) {
      source = resolution.sourceName();
    } else {
      source = "default";
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.SERVER_EXE.sysProp());
    details.put("envVar", EnvRegistry.SERVER_EXE.envVar());
    if (resolution != null && resolution.isResolved()) {
      details.put("resolvedValue", resolution.value());
      details.put("sourceOrdinal", resolution.sourceOrdinal());
      if (resolution.sourceDetail() != null) {
        details.put("sourceDetail", resolution.sourceDetail());
      }
    }
    if (sys != null) details.put("syspropValue", sys);
    if (env != null) details.put("envValue", env);
    if (runtimeInfo != null) details.put("usingExternalLlamaServer", runtimeInfo.usingExternalLlamaServer());

    List<Map<String, Object>> conflicts = new ArrayList<>();
    addConflictIfDifferent(conflicts, "system_property", sys, effective);
    addConflictIfDifferent(conflicts, "environment_variable", env, effective);
    if (!conflicts.isEmpty()) details.put("conflicts", conflicts);

    return key("justsearch.server.exe", effective, source, details);
  }

  /**
   * Tempdoc 883 §C.5c residue — the same migration the {@code server.exe} row already made.
   *
   * <p>The {@code justsearch.llm.model_path.source} marker is NOT deleted: {@code AiInstallService}
   * and {@code AiPackImportService} still write it as a genuine ownership token, and
   * {@code InferenceConfig.classifyModelPathOwner} reads it to tell an installer-written path from
   * an operator lock. What is deleted is this row RE-TELLING it — the marker only had to
   * disambiguate because the boot-time settings promotion made a GUI value report as
   * {@code jvm_arg}, and that promotion is gone. A GUI-chosen model now resolves as
   * {@code settings.json} at ordinal 300 without a marker to say so.
   */
  private Map<String, Object> keyLlmModelPath(
      Path baseDir,
      OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo,
      InferenceConfig envInference) {
    ConfigResolution resolution =
        configStore != null
            ? configStore.get().resolution(EnvRegistry.LLM_MODEL_PATH.configKey())
            : null;
    boolean resolved = resolution != null && resolution.isResolved();
    String sys = sysProp(EnvRegistry.LLM_MODEL_PATH.sysProp());
    String env = envVar(EnvRegistry.LLM_MODEL_PATH.envVar());

    String effective = runtimeInfo != null ? runtimeInfo.modelPath()
        : (envInference != null && envInference.modelPath() != null ? envInference.modelPath().toString() : null);

    String source;
    if (resolved && valuesMatchPath(resolution.value(), effective)) {
      source = resolution.sourceName();
    } else if (effective != null && !effective.isBlank()) {
      // The live runtime is serving a model the resolver does not name (a profile-resolved pair, an
      // externally adopted server): the value is observed, not configured.
      source = "derived";
    } else if (resolved) {
      source = resolution.sourceName();
    } else {
      source = "default";
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.LLM_MODEL_PATH.sysProp());
    details.put("envVar", EnvRegistry.LLM_MODEL_PATH.envVar());
    if (baseDir != null) details.put("baseDir", baseDir.toString());
    if (runtimeInfo != null) details.put("usingExternalLlamaServer", runtimeInfo.usingExternalLlamaServer());
    if (resolved) {
      details.put("resolvedValue", resolution.value());
      details.put("sourceOrdinal", resolution.sourceOrdinal());
      if (resolution.sourceDetail() != null) {
        details.put("sourceDetail", resolution.sourceDetail());
      }
    }
    if (sys != null) details.put("syspropValue", sys);
    if (env != null) details.put("envValue", env);

    // If the runtime effective model path differs from the configured override, surface conflicts.
    List<Map<String, Object>> conflicts = new ArrayList<>();
    addConflictIfDifferent(conflicts, "system_property", sys, effective);
    addConflictIfDifferent(conflicts, "environment_variable", env, effective);
    if (!conflicts.isEmpty()) details.put("conflicts", conflicts);

    String value =
        effective != null ? effective : (resolved ? resolution.value() : (sys != null ? sys : env));
    return key("justsearch.llm.model_path", value, source, details);
  }

  /**
   * Tempdoc 883 decision 4: the context-size row is sourced from the RESOLVER's own provenance, not
   * from a {@code justsearch.context.size.source} marker sysprop.
   *
   * <p>That marker existed because {@code settings.json} used to be promoted to a system property,
   * which made a GUI value report as {@code jvm_arg} — a precedence lie the row then had to un-tell
   * by reading a second sysprop. Both are deleted: {@code settings.json} contributes at ordinal 300
   * and the derived window at 150, so the ordinal chain already knows who won, with what value,
   * from which detail.
   *
   * <p>Source names are the resolver's own strings ({@code auto_detected}, {@code settings.json},
   * {@code env_var}, {@code jvm_arg}, {@code yaml}, {@code default}) — re-spelling them here would
   * be a second vocabulary that drifts from the one the {@code resolvedConfig} block reports.
   *
   * <p>The observed runtime window still wins the {@code value} when it differs, reported as
   * {@code source: "runtime"} with the resolved value as a conflict: after a ladder step-down the
   * resolver holds the PLANNED rung and only {@code /props} knows the real one.
   */
  private Map<String, Object> keyContextSize(InferenceConfig envInference) {
    ConfigResolution resolution =
        configStore != null ? configStore.get().resolution("justsearch.context.size") : null;
    Integer resolved =
        resolution != null && resolution.isResolved() ? parseInt(resolution.value()) : null;

    // Fallback for callers built without a ConfigStore (the legacy constructor): the inference
    // layer's own resolution of the same key.
    int baseline =
        resolved != null
            ? resolved
            : (envInference != null ? envInference.contextSize() : 0);
    String baselineSource =
        resolution != null && resolution.isResolved() ? resolution.sourceName() : "unknown";

    // The OBSERVED window, from the /props readback - NOT runtimeInfo.contextSize(), which is
    // InferenceConfig's CONFIGURED value and is rebuilt on its own schedule. Measured live
    // (tempdoc 883): with the server running at 16384 after an override, runtimeInfo still said
    // 32768, so this row reported a "runtime" number no server ever had. After a ladder step-down
    // it would report the PLANNED rung as the runtime value - the exact case this row exists to
    // disambiguate.
    Integer runtime = observedContextTokens();
    int value = runtime != null ? runtime : baseline;

    String source = baselineSource;
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.CONTEXT_SIZE.sysProp());
    details.put("envVar", EnvRegistry.CONTEXT_SIZE.envVar());
    details.put("baseline", baseline);
    if (resolution != null && resolution.isResolved()) {
      details.put("sourceOrdinal", resolution.sourceOrdinal());
      if (resolution.sourceDetail() != null) {
        details.put("sourceDetail", resolution.sourceDetail());
      }
    }
    if (runtime != null) details.put("runtime", runtime);

    if (runtime != null && runtime != baseline) {
      source = "runtime";
      List<Map<String, Object>> conflicts = new ArrayList<>();
      conflicts.add(Map.of("source", baselineSource, "value", baseline));
      details.put("conflicts", conflicts);
    }

    return key("justsearch.context.size", value, source, details);
  }

  /**
   * The context window the running llama-server reports via {@code /props}, or null when none has
   * been observed. Same authority {@code /api/inference/status.llmContextTokens} publishes.
   */
  private Integer observedContextTokens() {
    try {
      return onlineAiService == null ? null : onlineAiService.llmContextTokens();
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * The {@code justsearch.gpu.layers} row, sourced from the RESOLVER's provenance (tempdoc 883
   * decision 4 slice 2) exactly as {@link #keyContextSize} is.
   *
   * <p>It used to report a {@code justsearch.gpu.layers.source} marker sysprop, which existed only
   * to un-tell the lie told by promoting {@code settings.json} into a system property: a GUI value
   * resolved at ordinal 500 and the row said {@code system_property}. Promotion and marker are both
   * deleted, so the row says {@code settings.json} for a GUI value, {@code auto_detected} for the
   * VRAM-tier probe, and {@code jvm_arg} only for a real {@code -D}. Source names are the resolver's
   * own strings — re-spelling them here would be a second vocabulary that drifts.
   *
   * <p>The policy veto below is a separate axis and is unchanged: {@code requested} is what the
   * chain resolved, {@code applied} is what {@code InferenceLifecycleManager} will actually spawn
   * with once {@code policy.gpu_acceleration_enabled=false} forces {@code -ngl 0}.
   */
  private Map<String, Object> keyGpuLayers(
      OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo,
      InferenceConfig envInference,
      EffectivePolicy policy) {
    ConfigResolution resolution =
        configStore != null ? configStore.get().resolution("justsearch.gpu.layers") : null;
    String sys = sysProp(EnvRegistry.GPU_LAYERS.sysProp());
    String env = envVar(EnvRegistry.GPU_LAYERS.envVar());

    int baseline = envInference != null ? envInference.gpuLayers() : 0;
    Integer requested = runtimeInfo != null ? runtimeInfo.gpuLayers() : null;
    if (requested == null) requested = baseline;

    boolean policyGpuEnabled = policy == null || policy.gpuAccelerationEnabled();
    boolean usingExternal = runtimeInfo != null && runtimeInfo.usingExternalLlamaServer();

    Integer applied;
    boolean appliedKnown = true;
    if (usingExternal) {
      appliedKnown = false;
      applied = null;
    } else {
      applied = (requested > 0 && !policyGpuEnabled) ? 0 : requested;
    }

    String baselineSource =
        resolution != null && resolution.isResolved() ? resolution.sourceName() : "unknown";
    String source = baselineSource;
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.GPU_LAYERS.sysProp());
    details.put("envVar", EnvRegistry.GPU_LAYERS.envVar());
    details.put("requested", requested);
    details.put("applied", applied);
    details.put("appliedValueKnown", appliedKnown);
    details.put("policyGpuAccelerationEnabled", policyGpuEnabled);
    if (runtimeInfo != null) details.put("usingExternalLlamaServer", usingExternal);
    if (resolution != null && resolution.isResolved()) {
      details.put("sourceOrdinal", resolution.sourceOrdinal());
      if (resolution.sourceDetail() != null) {
        details.put("sourceDetail", resolution.sourceDetail());
      }
    }
    if (sys != null) details.put("syspropValue", sys);
    if (env != null) details.put("envValue", env);

    if (runtimeInfo != null && requested != baseline) {
      source = "runtime";
      List<Map<String, Object>> conflicts = new ArrayList<>();
      conflicts.add(Map.of("source", baselineSource, "value", baseline));
      details.put("conflicts", conflicts);
    }

    Object value = appliedKnown ? applied : requested;
    return key("justsearch.gpu.layers", value, source, details);
  }

  private Map<String, Object> keyAiDisabled() {
    String sys = sysProp(EnvRegistry.AI_DISABLED.sysProp());
    String env = envVar(EnvRegistry.AI_DISABLED.envVar());
    ConfigStore cs = ConfigStore.globalOrNull();
    ResolvedConfig rc = cs != null ? cs.get() : null;
    boolean value = rc != null ? rc.ai().disabled() : EnvRegistry.AI_DISABLED.getBoolean(false);
    String source = sourceForBaseline(sys, env);
    if ("default".equals(source)) source = "default";

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.AI_DISABLED.sysProp());
    details.put("envVar", EnvRegistry.AI_DISABLED.envVar());
    return key("justsearch.ai.disabled", value, source, details);
  }

  private Map<String, Object> keyPolicyBool(String key, Boolean value) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("note", "Source of truth is EnterprisePolicyService.snapshot() / GET /api/policy/effective.");
    return key(key, value, "policy_effective", details);
  }

  private Map<String, Object> keySimpleString(
      String key,
      String sysprop,
      String envVar,
      String value,
      String defaultSource) {
    String sys = sysProp(sysprop);
    String env = envVar(envVar);
    String source;
    if (sys != null) source = "system_property";
    else if (env != null) source = "environment_variable";
    else source = defaultSource;
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", sysprop);
    details.put("envVar", envVar);
    return key(key, value, source, details);
  }

  private static Map<String, Object> key(String key, Object value, String source, Map<String, Object> details) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("key", key);
    out.put("value", value);
    out.put("source", source);
    if (details != null && !details.isEmpty()) {
      out.put("details", details);
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Integer safeGet(Supplier<Integer> supplier) {
    try {
      return supplier == null ? null : supplier.get();
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String sysProp(String key) {
    try {
      String v = SystemAccess.sysProp(key);
      if (v == null) return null;
      String t = v.trim();
      return t.isBlank() ? null : t;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String envVar(String key) {
    try {
      String v = SystemAccess.envVar(key);
      if (v == null) return null;
      String t = v.trim();
      return t.isBlank() ? null : t;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Integer parseInt(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String sourceForBaseline(String sysVal, String envVal) {
    if (sysVal != null) return "system_property";
    if (envVal != null) return "environment_variable";
    return "default";
  }

  private static void addConflictIfDifferent(List<Map<String, Object>> conflicts, String source, String candidate, String winner) {
    if (candidate == null) return;
    String c = candidate.trim();
    String w = winner == null ? "" : winner.trim();
    if (!c.equals(w)) {
      conflicts.add(Map.of("source", source, "value", c));
    }
  }

  private static boolean valuesMatchPath(String candidate, String effective) {
    if (candidate == null || effective == null) return false;
    String a = candidate.trim();
    String b = effective.trim();
    if (a.equals(b)) return true;
    try {
      Path pa = Path.of(a).toAbsolutePath().normalize();
      Path pb = Path.of(b).toAbsolutePath().normalize();
      return pa.equals(pb);
    } catch (Exception ignored) {
      return false;
    }
  }

  private static Path resolveBaseDir() {
    return PlatformPaths.resolveAiHome();
  }
}
