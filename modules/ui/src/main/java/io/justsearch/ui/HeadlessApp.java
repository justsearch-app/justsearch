/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerHealthMonitor;
import io.justsearch.app.util.AppInstanceLock;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.SystemPropertyUtils;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.contracts.BootContractRunner;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.api.gpl.GplStatusProvider;
import io.justsearch.ui.api.LocalApiServer;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.api.UiSettings;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Headless entry point for the JustSearch application.
 *
 * <p>Designed to be run as a sidecar process by Tauri. It initializes the backend and the Local API
 * Server, but does not start JavaFX.
 */
public class HeadlessApp {
  private static final Logger log = LoggerFactory.getLogger(HeadlessApp.class);

  // Tempdoc 502 §3.3: Typed phase outputs. Each record captures the outputs of one boot phase,
  // enabling independent testing of each phase.
  record ConfigPhaseResult(
      io.justsearch.app.services.settings.UiSettingsStore settingsStore,
      UiSettings initialSettings,
      ResolvedConfig resolvedConfig,
      ConfigStore configStore,
      Path dataDir) {}

  record InfraPhaseResult(
      ConfigPhaseResult config,
      Telemetry telemetry,
      // Tempdoc 518 Appendix G W4.2 — present when HEAD_TRACING_LEVEL is non-none.
      io.justsearch.telemetry.TracingBootstrap tracingBootstrap) {}

  record ApiPhaseResult(
      HeadAssembly bootstrap,
      LocalApiServer apiServer,
      int port,
      String sessionToken) {}

  record WorkerConnectionResult(
      KnowledgeServerBootstrap knowledgeServer,
      String startError,
      KnowledgeServerHealthMonitor healthMonitor) {}

  /**
   * Application-wide ConfigStore. Initialized during startup before downstream components are
   * created. Published globally via {@link ConfigStore#setGlobal(ConfigStore)}.
   */
  private static volatile ConfigStore configStore;

  // Tempdoc 519 §9 Block B3.0.e: contributeUiSettings moved to
  // io.justsearch.app.services.config.ConfigStoreRebuilder so it lives in
  // the same place as ConfigStoreRebuilder.rebuild (which also calls it).
  // HeadlessApp's L539 callsite now invokes
  // ConfigStoreRebuilder.contributeUiSettings(rcBuilder, settings) directly.

  /**
   * Tempdoc 374 alpha.13 follow-up Phases E + F: bridges the chasm between
   * "GPU is detected" and "GPU is used" that left default installs on CPU
   * even when {@code GpuAutoDetection.probe} correctly reported CUDA.
   *
   * <p>Two operations, both predicated on user override absence:
   *
   * <ol>
   *   <li><b>Phase E — sysprop-mirror.</b> Each entry written by the probe
   *       (e.g. {@code justsearch.gpu.enabled = "true"}) is also set as a
   *       system property, but only when {@code EnvRegistry.<key>.get()} is
   *       empty (no user sysprop or env var override exists). This makes the
   *       value (a) survive {@link io.justsearch.app.services.config.ConfigStoreRebuilder#rebuild}
   *       (which only re-contributes sysprops via env-registry, not the
   *       transient ord-150 autoDetected map), and (b) propagate to the
   *       worker subprocess via {@code WORKER_FORWARDED_PROPS} —
   *       {@code GPU_ENABLED} and {@code ORT_NATIVE_PATH} are in that list.
   *       {@code GPU_LAYERS} is in it too, but no longer arrives by this
   *       route: since tempdoc 883 it is Phase F's map-only value, and it
   *       reaches the Worker through the resolved worker-config snapshot at
   *       ordinal 450 instead (pinned by {@code WorkerSnapshotAutoDetectedTest}).
   *   <li><b>Phase F — VRAM-tier auto-populate of gpu_layers.</b> If "GPU
   *       should be used" (probe said true AND user didn't explicitly say
   *       false) AND no explicit {@code gpu.layers} is set, query NVML for
   *       total VRAM. If &ge; 7.5&nbsp;GB (matches
   *       {@link io.justsearch.configuration.model.HardwareProfile#MINIMUM_VRAM_FOR_GGUF}),
   *       put {@code justsearch.gpu.layers = "99"} into the returned map
   *       (full offload — Qwen3.5-9B Q4_K_M is ~5.5&nbsp;GB, fits
   *       comfortably). Below threshold, leave at 0; the chat model wouldn't
   *       fit anyway and partial offload is an OOM hazard. The user can still
   *       force layers via env var, settings, or {@code -D}.
   * </ol>
   *
   * <p><b>Phase F does NOT mirror to a system property, and the name says
   * "ProbeFlags" for that reason</b> (tempdoc 883 decision 4 slice 2). A
   * sysprop write lands at ordinal 500, above the user's own value at 300 —
   * which is fine for the Phase-E boolean/path FLAGS, where the loop skips any
   * key the user set, but wrong for a NUMBER the user may have chosen a
   * different value for. Phase F's contribution therefore lives only in the
   * returned map, at ordinal 150, where the ordinal chain can rank it honestly.
   *
   * <p>The augmented map is returned so the caller can pass it to
   * {@link ResolvedConfigBuilder#contributeAutoDetected} — for Phase E that
   * keeps the ord-150 contribution and the sysprop write in lockstep; for
   * Phase F the map is the only carrier.
   *
   * <p>Package-private + parameterized {@code totalVramSupplier} so tests
   * can pin behavior across the four boundary cases (autoDetected GPU + 12 GB
   * VRAM, idempotent re-run, user-disabled GPU, below-threshold VRAM)
   * without spawning NVML.
   */
  static Map<String, String> augmentGpuAutoDetectionAndMirrorProbeFlags(
      Map<String, String> autoDetected, LongSupplier totalVramSupplier) {
    if (autoDetected == null || autoDetected.isEmpty()) {
      return autoDetected == null ? Map.of() : autoDetected;
    }
    java.util.LinkedHashMap<String, String> augmented = new java.util.LinkedHashMap<>(autoDetected);

    // Phase E: sysprop-mirror autoDetected entries (gpu.enabled, ort native_path).
    for (Map.Entry<String, String> entry : autoDetected.entrySet()) {
      String key = entry.getKey();
      EnvRegistry envKey = lookupEnvRegistryBySysProp(key);
      Optional<String> userOverride = envKey != null ? envKey.get() : Optional.empty();
      if (userOverride.isPresent()) {
        // User has an explicit sysprop or env var — respect it; don't mirror.
        continue;
      }
      SystemPropertyUtils.setSysPropIfBlank(key, entry.getValue());
    }

    // Phase F: VRAM-tier auto-populate gpu_layers when GPU should be used.
    if (shouldUseGpu(augmented)) {
      // LLM_GPU_LAYERS was a dead duplicate of GPU_LAYERS (resolved, documented, read by
      // nothing) — removed in tempdoc 799 §N.2, so only the live key is consulted here.
      // Deliberately NOT also checking settings.json: contributing an auto-detected 99 at ordinal
      // 150 is harmless when the user set a value at 300, because 300 wins.
      boolean alreadySet = EnvRegistry.GPU_LAYERS.get().isPresent();
      if (!alreadySet) {
        long vramBytes = -1;
        try {
          vramBytes = totalVramSupplier.getAsLong();
        } catch (Throwable t) {
          log.debug("VRAM auto-populate: NVML probe failed (best-effort): {}", t.getMessage());
        }
        if (vramBytes
            >= io.justsearch.configuration.model.HardwareProfile.MINIMUM_VRAM_FOR_GGUF) {
          String layers = "99";
          // Tempdoc 883 decision 4 slice 2: the map ONLY — no sysprop mirror. A sysprop write puts
          // this DERIVED hardware-probe number at ordinal 500, where it outranks the user's own GPU
          // setting at 300. It was masked only because the settings→sysprop promotion ran first and
          // setSysPropIfBlank then no-opped; with that promotion deleted, mirroring here would let
          // an auto-detected 99 silently override the user's choice on exactly the hardware where
          // the choice matters. The map is contributed at ordinal 150 and kept across rebuilds by
          // ConfigStoreRebuilder.rememberAutoDetected — a probe value reported as a probe value.
          augmented.put("justsearch.gpu.layers", layers);
          // justsearch.llm.gpu_layers was a dead duplicate of the key above — resolved,
          // documented, and read by nothing. Removed in tempdoc 799 §N.2.
          log.info(
              "VRAM auto-populate: gpu.layers={} (vramBytes={}, threshold={})",
              layers,
              vramBytes,
              io.justsearch.configuration.model.HardwareProfile.MINIMUM_VRAM_FOR_GGUF);
        } else {
          log.info(
              "VRAM auto-populate: skipped — vramBytes={} below threshold {} (Qwen3.5-9B Q4_K_M"
                  + " ~5.5 GB wouldn't fit safely)",
              vramBytes,
              io.justsearch.configuration.model.HardwareProfile.MINIMUM_VRAM_FOR_GGUF);
        }
      }

      // Tempdoc 374 alpha.16 fix D (defensive backstop): per-encoder gpu.enabled
      // sysprop-mirror when shouldUseGpu and no user override exists. The round-6
      // sandbox agent observed embed/splade/ner gpuEnabled=false at the worker even
      // though master justsearch.gpu.enabled=true was in the snapshot — the
      // master-fallback chain in ResolvedConfigBuilder.resolveEmbedGpuEnabled looks
      // correct from a static read but isn't producing the expected value at the
      // worker. Sysprop-mirroring at boot makes the per-feature value explicit at
      // ordinal 500, bypassing whatever resolution path is dropping the master
      // fallback. Root-cause investigation (D1+D2 in tempdoc 374) deferred to
      // alpha.17; this is the defensive backstop (D3).
      //
      // Reranker has its own EnvRegistry default true so doesn't need mirroring.
      // BgeM3 isn't loaded in the current encoder set; skip.
      mirrorPerEncoderGpuEnabled(EnvRegistry.EMBED_GPU_ENABLED);
      mirrorPerEncoderGpuEnabled(EnvRegistry.SPLADE_GPU_ENABLED);
      mirrorPerEncoderGpuEnabled(EnvRegistry.NER_GPU_ENABLED);
    }

    return augmented;
  }

  /**
   * Tempdoc 883 decision 1: contributes the DERIVED llama-server context window at
   * {@code ORDINAL_AUTO_DETECT} (150, source {@code auto_detected} / detail {@code hardware_probe}),
   * so {@code /api/debug/effective-config} explains the window with the mechanism that already
   * explains GPU detection instead of a promotion that reported a GUI value as {@code jvm_arg}.
   *
   * <p>Runs AFTER {@link #augmentGpuAutoDetectionAndMirrorProbeFlags} on purpose: the top rung depends on
   * whether layers ended up on the GPU, which that pass is what decides (Phase F) — reading
   * {@code gpu.layers} before it would derive the CPU rung on every GPU machine.
   *
   * <p>Unconditional by design, including when the GPU probe returned nothing: a fresh data dir
   * with no GPU must still get a window with a legible provenance. Every higher ordinal — YAML 200,
   * {@code settings.json} 300, env 400, {@code -D} 500 — still wins by the ordinal chain, so the
   * headless-eval {@code JUSTSEARCH_CONTEXT_SIZE} path is unaffected.
   *
   * @param settingsGpuLayers the user's {@code UiSettings.gpuLayers} (ordinal 300), {@code 0} when
   *     unset — see {@link #gpuLayersAfterAutoDetect} for why it has to be passed in
   */
  static Map<String, String> augmentDerivedContextWindow(
      Map<String, String> autoDetected, int settingsGpuLayers) {
    java.util.LinkedHashMap<String, String> augmented =
        new java.util.LinkedHashMap<>(autoDetected == null ? Map.of() : autoDetected);
    int gpuLayers = gpuLayersAfterAutoDetect(augmented, settingsGpuLayers);
    int rung = io.justsearch.app.inference.ContextWindowPolicy.autoTopRung(gpuLayers > 0);
    augmented.put("justsearch.context.size", String.valueOf(rung));
    log.info(
        "Context window derived: {} tokens (gpuLayers={}, ordinal=150 auto_detected/hardware_probe);"
            + " the launch ladder steps down from here if the server refuses it",
        rung,
        gpuLayers);
    return augmented;
  }

  /**
   * GPU layers as they stand after auto-detection — the resolver's ordinal chain in miniature.
   *
   * <p>Walks {@code -D} / env (500 / 400) → {@code settings.json} (300) → the auto-detected probe
   * map (150) → 0, which is the order {@link ResolvedConfigBuilder} will apply to the same key a few
   * lines later in {@code resolveConfig}. Tempdoc 883 decision 4 slice 2 is what makes the middle
   * rung explicit: the settings value used to arrive here inside {@code EnvRegistry.GPU_LAYERS}
   * because a promotion mirrored it into the sysprop, so this method could not tell a GUI setting
   * from an operator's {@code -D}. With that promotion deleted the settings value has to be passed
   * in, or a user who set 20 layers would get the CPU rung derived on a GPU box.
   *
   * @param settingsGpuLayers the user's {@code UiSettings.gpuLayers}; consulted only when
   *     {@code > 0}, matching {@code ConfigStoreRebuilder.contributeUiSettings} where {@code 0}
   *     means "unset" and is not contributed at all
   */
  private static int gpuLayersAfterAutoDetect(
      Map<String, String> autoDetected, int settingsGpuLayers) {
    String raw = EnvRegistry.GPU_LAYERS.get().orElse(null);
    if ((raw == null || raw.isBlank()) && settingsGpuLayers > 0) {
      raw = String.valueOf(settingsGpuLayers);
    }
    if (raw == null || raw.isBlank()) {
      raw = autoDetected.get("justsearch.gpu.layers");
    }
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(raw.trim()));
    } catch (NumberFormatException e) {
      log.debug("Unparseable gpu.layers '{}' while deriving the context window; treating as 0", raw);
      return 0;
    }
  }

  /**
   * Tempdoc 374 alpha.16 fix D (defensive backstop): if no user override is set
   * for the given per-encoder GPU enable key, mirror {@code "true"} as a sysprop
   * so the worker resolves the explicit value rather than relying on the master
   * fallback (which empirical evidence shows isn't producing the expected result).
   */
  private static void mirrorPerEncoderGpuEnabled(EnvRegistry key) {
    if (key.get().isPresent()) {
      // User has an explicit value at sysprop or env var — respect it.
      return;
    }
    SystemPropertyUtils.setSysPropIfBlank(key.sysProp(), "true");
    log.debug("alpha.16 fix D: mirrored {} = true (per-encoder GPU defensive backstop)",
        key.sysProp());
  }

  /**
   * Returns true iff {@code shouldUseGpu = userOverride.isPresent()
   *   ? Boolean.parseBoolean(userOverride.get()) : autoDetectedSaysTrue}.
   * Captures the rule that an explicit user disable (env / sysprop
   * {@code JUSTSEARCH_GPU_ENABLED=false}) wins over the auto-detect.
   */
  private static boolean shouldUseGpu(Map<String, String> autoDetected) {
    Optional<String> userOverride = EnvRegistry.GPU_ENABLED.get();
    if (userOverride.isPresent()) {
      return Boolean.parseBoolean(userOverride.get().trim());
    }
    return "true".equalsIgnoreCase(autoDetected.getOrDefault("justsearch.gpu.enabled", ""));
  }

  /**
   * Reverse-lookup an EnvRegistry entry by its sysprop key. Returns null if no
   * matching entry exists (e.g. a probe key that isn't an EnvRegistry-managed
   * config — currently {@code justsearch.gpu.enabled} and
   * {@code justsearch.onnxruntime.native_path} are managed; future probe keys
   * may not be).
   */
  private static EnvRegistry lookupEnvRegistryBySysProp(String sysPropKey) {
    for (EnvRegistry reg : EnvRegistry.values()) {
      if (reg.sysProp().equals(sysPropKey)) {
        return reg;
      }
    }
    return null;
  }

  /**
   * Tempdoc 374 alpha.16 fix B: at boot, if the cuda12 variant dir exists with the CUDA
   * runtime DLLs and the user hasn't set {@code justsearch.onnxruntime.native_path}
   * explicitly, set the sysprop pointing at that dir. Mirrors the in-cycle write in
   * {@link io.justsearch.ui.ai.install.AiInstallService#applyOrtNativePath} (alpha.14
   * fix B), but at boot time so the value survives restarts.
   *
   * <p>Without this, after a user restarts JustSearch following a successful Install AI:
   * the chat path picks up via {@code maybeAutoSelectCuda12Variant} but the worker spawns
   * with no ORT native_path, ORT can't find cuBLASLt + cuFFT + cuDNN at LoadLibrary time,
   * and all 4 ONNX encoders fall back to CPU even though the runtime DLLs are right
   * there in {@code <homeDir>/native-bin/llama-server/variants/cuda12/}.
   *
   * <p>The home directory is resolved via {@link PlatformPaths#resolveDataDir()} —
   * matches the same source {@code AiInstallService.resolveHomeDir} uses to write
   * the cuda12 dir during Install AI, so this read paired with that write produces a
   * matching path. (An earlier draft used {@code cs.get().paths().home()}, but that
   * resolves the {@code justsearch.home} sysprop which is null in production unless
   * the user explicitly set it.)
   */

  private static InfraPhaseResult setupInfra(ConfigPhaseResult configPhase) {
    System.setProperty("justsearch.infra.health.grpc.disable", "true");
    System.setProperty("justsearch.infra.health.port", "0");

    Path dataDir = configPhase.dataDir();
    harmonizeDataDirProperties(dataDir);
    log.info("Using data directory: {}", dataDir);

    Telemetry telemetry = new LocalTelemetry(
        dataDir, 5_000, "justsearch-headless", "phase3", "metrics.ndjson",
        List.of(
            // Tempdoc 626 §Axis-A — the Head-side file watcher was removed; the `index.watcher.*`
            // metric is emitted only by the Worker (WorkerWatcherMetricCatalog), so the Head no
            // longer registers it.
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.app.services.observability.HeadApiMetricCatalog.NAMESPACE,
                io.justsearch.app.services.observability.HeadApiMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.app.services.observability.HeadHttpInflightMetricCatalog.NAMESPACE,
                io.justsearch.app.services.observability.HeadHttpInflightMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.app.services.observability.HeadGpuMetricCatalog.NAMESPACE,
                io.justsearch.app.services.observability.HeadGpuMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.app.services.worker.IpcMetricCatalog.NAMESPACE,
                io.justsearch.app.services.worker.IpcMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.app.services.worker.RagMetricCatalog.NAMESPACE,
                io.justsearch.app.services.worker.RagMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.app.services.vdu.VduMetricCatalog.NAMESPACE,
                io.justsearch.app.services.vdu.VduMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.agent.AgentMetricCatalog.NAMESPACE,
                io.justsearch.agent.AgentMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.agent.GenAiMetricCatalog.NAMESPACE,
                io.justsearch.agent.GenAiMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.app.services.inference.InferenceMetricCatalog.NAMESPACE,
                io.justsearch.app.services.inference.InferenceMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.JvmMetricCatalog.catalogFor("head")));

    try {
      new EnterprisePolicyServiceImpl().snapshot();
    } catch (Exception ignored) {
      // best-effort
    }

    // Tempdoc 518 Appendix G W4.2 — initialize head-side OTel tracing. Mirrors the worker
    // pattern at KnowledgeServer.java:335-347. Gated on HEAD_TRACING_LEVEL; default "none"
    // means GlobalOpenTelemetry stays no-op and the existing head-side span-authoring sites
    // (AgentLoopService, KnowledgeHttpApiAdapter) emit into the void as before. When
    // non-none, the spans get exported AND carry the justsearch.inference.generation
    // attribute via W2.2's InferenceGenerationSpanProcessor.
    io.justsearch.telemetry.TracingBootstrap tracingBootstrap = null;
    String headTracingLevel = EnvRegistry.HEAD_TRACING_LEVEL
        .getString("none").toLowerCase(java.util.Locale.ROOT);
    if (!"none".equals(headTracingLevel)) {
      try {
        tracingBootstrap = io.justsearch.telemetry.TracingBootstrap.forHead(
            dataDir,
            telemetry instanceof LocalTelemetry lt ? lt.getHealthState() : null,
            headTracingLevel);
        log.info("Head tracing initialized: level={}", headTracingLevel);
      } catch (IllegalStateException e) {
        log.debug("GlobalOpenTelemetry already set, skipping head TracingBootstrap: {}", e.getMessage());
      }
    }

    return new InfraPhaseResult(configPhase, telemetry, tracingBootstrap);
  }

  @SuppressWarnings("PMD.SystemPrintln")
  private static ApiPhaseResult buildApi(
      InfraPhaseResult infraPhase,
      io.justsearch.app.services.settings.UiSettingsStore settingsStore,
      RuntimeManifestPublisher manifestPublisher,
      io.justsearch.app.services.lifecycle.WorkerCapability sharedWorkerCapability,
      io.justsearch.ui.api.UpgradeShutdownBridge upgradeShutdownBridge,
      io.justsearch.ui.api.LifecycleShutdownBridge lifecycleShutdownBridge)
      throws Exception {
    Telemetry telemetry = infraPhase.telemetry();
    ResolvedConfig resolvedConfig = infraPhase.config().resolvedConfig();

    HeadAssembly bootstrap =
        new HeadAssembly(
            telemetry, new ConfigManagerBootstrap(), null, settingsStore, sharedWorkerCapability);
    log.info("HeadAssembly started (degraded — Worker connecting in background).");

    var headInfra = bootstrap.headInfraRegistry();
    GplStatusProvider gplCoordinator = headInfra.gplJobCoordinator();
    tools.jackson.databind.JsonNode configRoot =
        io.justsearch.configuration.JustSearchConfigurationLoader.loadYamlRoot().orElse(null);
    Path indexBasePath = resolvedConfig.paths().indexBasePath();

    BootContractRunner.validateAll();

    boolean prodMode = configStore.get().policy().prodMode();
    String sessionToken = prodMode ? LocalApiServer.generateSessionToken() : null;

    Path userHome = Path.of(System.getProperty("user.home", ""));
    LocalApiServer apiServer =
        LocalApiServer.builder(settingsStore, indexBasePath)
            .HeadAssembly(bootstrap)
            .knowledgeServer(null)
            .configRoot(configRoot)
            .knowledgeServerStartError(null)
            .telemetry(telemetry)
            .sessionToken(sessionToken)
            .userHome(userHome.toString().isEmpty() ? null : userHome)
            .workerFeatureCache(bootstrap.workerFeatureCache())
            .gplJobCoordinator(gplCoordinator)
            .lambdaMartReranker(headInfra.lambdaMartReranker())
            .gplEvalSnapshotSupplier(headInfra.gplEvalSnapshotSupplier())
            .HeadAssembly(bootstrap)
            .runtimeManifestPublisher(manifestPublisher)
            .upgradeShutdownAction(upgradeShutdownBridge)
            .lifecycleShutdownAction(lifecycleShutdownBridge)
            .upgradeReconciliation(
                resolvedConfig.paths().dataDir(),
                () -> EnvRegistry.APP_VERSION.get().orElse(""),
                () -> true,
                null)
            .build();
    int port = apiServer.getPort();

    emitPortSignals(port, sessionToken, prodMode);

    return new ApiPhaseResult(bootstrap, apiServer, port, sessionToken);
  }

  @SuppressWarnings("PMD.SystemPrintln")
  private static void emitPortSignals(
      int port, String sessionToken, boolean prodMode) {
    // Tempdoc 501 Phase 18: the api-port.txt mirror is gone. The full runtime
    // manifest at <dataDir>/runtime/manifest.json (written by RuntimeManifestPublisher)
    // is now the canonical filesystem transport. Every known consumer
    // (Vite proxy, dev-runner, prod MCP, IsolatedBackendFixture integration
    // tests, ui module's sidecar smoke) reads the manifest directly.
    //
    // The stdout JUSTSEARCH_API_PORT=<port> line below remains as human-readable
    // log output; no tool parses it for discovery (Phase 8 removed the parse-to-
    // state paths from the Tauri shell and dev-runner).
    log.info(
        "Preparing stdout signals: prodMode={}, tokenGenerated={}", prodMode, sessionToken != null);
    if (sessionToken != null) {
      System.out.println("JUSTSEARCH_SESSION_TOKEN=" + sessionToken);
      System.out.flush();
      log.debug("Session token printed to stdout (length={})", sessionToken.length());
    } else {
      // Dev mode only: prod mode with no token is refused at ApiSecurityFilters construction
      // (tempdoc 884 item 23), so the Head never reaches this line with prodMode=true.
      log.debug("No session token (dev mode); token enforcement is not installed.");
    }
    System.out.println("JUSTSEARCH_API_PORT=" + port);
    System.out.flush();
  }

  private static WorkerConnectionResult connectWorker(
      ApiPhaseResult apiPhase,
      java.util.concurrent.CompletableFuture<KnowledgeServerStartResult> workerFuture) {
    KnowledgeServerStartResult ksStart = workerFuture.join();
    KnowledgeServerBootstrap knowledgeServer = ksStart.bootstrap();
    String knowledgeServerStartError = ksStart.startError();
    HeadAssembly bootstrap = apiPhase.bootstrap();
    LocalApiServer apiServer = apiPhase.apiServer();
    KnowledgeServerHealthMonitor healthMonitor = null;

    if (knowledgeServer != null && knowledgeServer.hasClient()) {
      connectAndBind(bootstrap, apiServer, knowledgeServer, knowledgeServerStartError);
      healthMonitor = startHealthMonitor(bootstrap, apiServer, knowledgeServer);
      if (bootstrap.capabilities().worker().available()) {
        log.info("Knowledge Server connected — search and indexing now available");
      } else {
        log.info(
            "Knowledge Server connected (health: {}); search and indexing will be available once"
                + " worker reaches READY",
            bootstrap.capabilities().worker().health());
      }
    } else if (knowledgeServer != null) {
      // Tempdoc 825 (Option B): the bootstrap failed to start, but it is provably restartable
      // (KnowledgeServerBootstrapRestartabilityTest), so it is no longer discarded. The surfaces
      // late-bind with null as before — there is no client to give them yet — and the SAME health
      // monitor that polls a live worker takes the boot-recovery arm instead, re-attempting the
      // bootstrap under a bounded budget and performing the handover if it comes up. Before this,
      // this branch started no monitor at all: /api/health served 503 for the life of the process.
      apiServer.lateBindKnowledgeServer(null, knowledgeServerStartError);
      // Deliberately NO transition here. The bootstrap that just failed is the producer of this
      // verdict and has already narrated it exactly once (startWithRetry's final catch), with the
      // code it actually knows to be true — worker.spawn.failed, either fatal index code
      // (worker.index_corrupt / worker.index_schema_mismatch), or supervision's terminal
      // worker.restart_exhausted, which that catch now explicitly refuses to overwrite (review F1:
      // both are FAULT, so ReasonRetention lets an incoming spawn-failed win, and before the guard the
      // restart_exhausted case could never survive a real boot). Tempdoc 915 R1 added the
      // schema-mismatch code and the latch that carries either fatal index cause across the three
      // SUPPRESSED start attempts that each consumed the one-shot marker — without it this branch
      // logged, and /api/health served, the generic spawn failure for a deliberate refusal.
      // Re-stamping the generic code here would destroy the specific one all over again — and the
      // boot-recovery veto reads exactly that slot to decide whether supervision's verdict stands.
      healthMonitor = startHealthMonitor(bootstrap, apiServer, knowledgeServer);
      log.warn(
          "Knowledge Server failed to start: {} (worker reason: {}) — boot recovery armed",
          knowledgeServerStartError,
          bootstrap.capabilities().worker().pendingReason());
    } else if (knowledgeServerStartError != null) {
      // No bootstrap instance at all: the failure was fatal before/at construction (or the data
      // directory is locked), so there is nothing to re-attempt.
      apiServer.lateBindKnowledgeServer(null, knowledgeServerStartError);
      bootstrap.capabilities().worker()
          .transition(
              io.justsearch.app.api.lifecycle.CapabilityHealth.DEGRADED,
              io.justsearch.app.api.lifecycle.LifecycleReasonCode.WORKER_SPAWN_FAILED.code(),
              "Worker spawn failed: " + knowledgeServerStartError);
      log.warn("Knowledge Server failed to start: {}", knowledgeServerStartError);
    } else {
      bootstrap.capabilities().worker()
          .transition(
              io.justsearch.app.api.lifecycle.CapabilityHealth.OFFLINE,
              io.justsearch.app.api.lifecycle.LifecycleReasonCode.WORKER_NOT_CONFIGURED.code(),
              "Worker not configured");
    }

    return new WorkerConnectionResult(knowledgeServer, knowledgeServerStartError, healthMonitor);
  }

  /**
   * The worker handover: the two late-binding seams the API surfaces need, in the order
   * {@code LocalApiServer.lateBindKnowledgeServer} documents (HeadAssembly is the single owner of the
   * reference and is connected first). Tempdoc 825 makes this callable twice — once at boot when the
   * bootstrap came up, and once from the monitor's boot-recovery arm when a re-attempt succeeded.
   */
  private static void connectAndBind(
      HeadAssembly bootstrap,
      LocalApiServer apiServer,
      KnowledgeServerBootstrap knowledgeServer,
      String startError) {
    bootstrap.connectKnowledgeServer(knowledgeServer);
    apiServer.lateBindKnowledgeServer(knowledgeServer, startError);
  }

  /**
   * Tempdoc 825: ONE monitor authority, constructed regardless of the bootstrap outcome. It is also
   * the {@code WorkerRecoveryAuthority} behind {@code POST /api/worker/restart}, so the operator's
   * manual path and the automatic loop share one budget and one set of vetoes.
   */
  private static KnowledgeServerHealthMonitor startHealthMonitor(
      HeadAssembly bootstrap, LocalApiServer apiServer, KnowledgeServerBootstrap knowledgeServer) {
    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(knowledgeServer);
    monitor.onRecoveryConnected(recovered -> connectAndBind(bootstrap, apiServer, recovered, null));
    // Tempdoc 876 §C.8: reconcile the readiness snapshot on the poll the head already runs, so a
    // dimension that settles WITHOUT a capability transition (INDEX_SERVING → DEGRADED /
    // index.dense_unavailable) still reaches the ConditionStore for a client that never calls
    // GET /api/status. Without this the trigger's capability-transition arm can leave a boot-time
    // index.unavailable standing, and core.search-index — gated on Not(index.unavailable) — stays
    // hidden from the model for the life of the process.
    if (bootstrap != null && bootstrap.substrate() != null && bootstrap.substrate().health() != null) {
      var readinessTrigger = bootstrap.substrate().health().readinessReconciliationTrigger();
      if (readinessTrigger != null) {
        monitor.onTick(readinessTrigger::request);
        // Tempdoc 885 item 6: the trigger's thunk is now the Worker-status sampler, so the
        // monitor's tick IS the sampling schedule. Let the sampler choose the next interval
        // (2 s while indexing/backfill/AI activation is in flight, 10 s idle) rather than adding
        // a second executor for it.
        monitor.tickIntervalSupplier(apiServer::statusSamplingPeriodMs);
      }
    }
    apiServer.bindWorkerRecovery(monitor);
    monitor.start();
    return monitor;
  }

  private static ConfigPhaseResult resolveConfig() throws Exception {
    var mode = io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.resolveMode();
    var settingsStore = new io.justsearch.app.services.settings.UiSettingsStore(mode);
    UiSettings settings = settingsStore.load();

    if (settings.getLlamaLibPath() != null && !settings.getLlamaLibPath().isBlank()) {
      // The last remaining settings→sysprop promotion, and a different shape from the ones retired:
      // `llama.lib.path` is not a JustSearch config key at all (no EnvRegistry entry, no resolver
      // key, no `.source` marker) — it is read by the llama.cpp JNI loader out of the raw system
      // properties, so there is no ResolvedConfig for it to ride. Retiring it means giving it a
      // config key first, which is a different change from this one.
      SystemPropertyUtils.setSysPropIfBlank("llama.lib.path", settings.getLlamaLibPath());
    }
    // Tempdoc 883 decision 4 + its §C.5c residue: there is no settings→sysprop promotion left here
    // for a resolver-backed key — not context-size (slice 1), not server.exe / exclude-patterns /
    // gpu.layers (slice 2), and no longer index.base_path or llm.model_path. Every one of those
    // rides settings.json at ordinal 300 via ConfigStoreRebuilder.contributeUiSettings; the derived
    // window rides auto_detected at 150. An operator's -D / env var still wins at 500 / 400 — by
    // the ordinal chain, not by a sysprop write that made a GUI value report as `jvm_arg` and then
    // needed a `.source` marker to un-tell it.

    ResolvedConfigBuilder rcBuilder = ResolvedConfig.builder();
    Path detectionRoot = io.justsearch.configuration.RepoRootLocator.findRepoRootOrNull();
    Map<String, String> autoDetected = io.justsearch.ort.GpuAutoDetection.probe(detectionRoot);
    autoDetected = augmentGpuAutoDetectionAndMirrorProbeFlags(autoDetected, HeadlessApp::queryNvmlTotalVramBytes);
    autoDetected = augmentDerivedContextWindow(autoDetected, settings.getGpuLayers());
    // Remembered so a later ConfigStore rebuild (settings PUT, AI install, activation) does not
    // silently drop ordinal 150 and leave the derived window with no provenance.
    io.justsearch.app.services.config.ConfigStoreRebuilder.rememberAutoDetected(autoDetected);
    rcBuilder.contributeAutoDetected(autoDetected);
    rcBuilder.contributeBaseSources();
    io.justsearch.app.services.config.ConfigStoreRebuilder.contributeUiSettings(rcBuilder, settings);
    ResolvedConfig resolvedConfig = rcBuilder.build();
    var configStore = new ConfigStore(resolvedConfig);
    ConfigStore.setGlobal(configStore);

    maybeAutoSelectCuda12Variant(settings, configStore);
    maybeMirrorOrtNativePath();

    Path dataDir = PlatformPaths.resolveDataDir();
    SnapshotResult snapshot =
        snapshotAfterPostBuildWrites(
            configStore, settings, dataDir.resolve("runtime").resolve("worker-config-snapshot.json"));
    if (snapshot.writtenSnapshot() != null) {
      System.setProperty("justsearch.worker.config_snapshot", snapshot.writtenSnapshot().toString());
    }

    return new ConfigPhaseResult(settingsStore, settings, snapshot.config(), configStore, dataDir);
  }

  /**
   * The config the Worker snapshot was written from, and the snapshot path when the write actually
   * succeeded ({@code null} otherwise — the sysprop must not name a file that is not there).
   */
  record SnapshotResult(ResolvedConfig config, Path writtenSnapshot) {}

  /**
   * Writes the Worker's config snapshot AFTER the two boot steps that write system properties the
   * resolver has already read past — {@code maybeAutoSelectCuda12Variant} (the cuda12
   * {@code server.exe}) and {@code maybeMirrorOrtNativePath}.
   *
   * <p>Tempdoc 883 §C.5c residue: the snapshot used to be written from the {@code ResolvedConfig}
   * built BEFORE those writes, so a boot-time cuda12 auto-select never reached the Worker — the
   * Head switched variants and the Worker's snapshot still named the old exe. Rebuilding through
   * {@link io.justsearch.app.services.config.ConfigStoreRebuilder} rather than re-reading the
   * sysprops by hand keeps ONE assembly path: the same ordinal-150 probe, ordinal-300 settings and
   * base sources the initial build used, plus whatever the two steps just wrote at 500. The
   * rebuilt config is also what the rest of boot sees, so the Head and the Worker cannot disagree
   * about the exe the Head just selected.
   */
  static SnapshotResult snapshotAfterPostBuildWrites(
      ConfigStore configStore, UiSettings settings, Path snapshotPath) {
    io.justsearch.app.services.config.ConfigStoreRebuilder.rebuild(configStore, settings);
    ResolvedConfig effectiveConfig = configStore.get();
    try {
      effectiveConfig.toWorkerSnapshot(snapshotPath);
      return new SnapshotResult(effectiveConfig, snapshotPath);
    } catch (Exception e) {
      log.debug("Failed to write worker config snapshot (best-effort)", e);
      return new SnapshotResult(effectiveConfig, null);
    }
  }

  private static void maybeMirrorOrtNativePath() {
    try {
      Path home = PlatformPaths.resolveDataDir();
      if (home == null) {
        log.debug("ORT native_path mirror: data dir unresolved; skipping");
        return;
      }
      Path cuda12Dir = home.resolve("native-bin/llama-server/variants/cuda12");
      if (!Files.isDirectory(cuda12Dir)) {
        log.debug(
            "ORT native_path mirror: cuda12 dir not at {}; skipping (Install AI not run yet"
                + " or CPU profile)",
            cuda12Dir);
        return;
      }
      var missing = io.justsearch.ort.OrtCudaHelper.checkMissingCudaRuntimeDlls(cuda12Dir);
      if (!missing.isEmpty()) {
        log.warn(
            "ORT native_path mirror: cuda12 dir {} is missing runtime DLLs {} —"
                + " not setting sysprop; user can re-run Install AI to repair",
            cuda12Dir,
            missing);
        return;
      }
      // Respect explicit user override at any source.
      if (EnvRegistry.ORT_NATIVE_PATH.get().isPresent()) {
        log.debug(
            "ORT native_path mirror: justsearch.onnxruntime.native_path already set"
                + " (source: env or sysprop); respecting user override");
        return;
      }
      String absPath = cuda12Dir.toAbsolutePath().toString();
      SystemPropertyUtils.setSysPropIfBlank("justsearch.onnxruntime.native_path", absPath);
      log.info("alpha.16 fix B: ORT native path set to {} (boot-time mirror)", absPath);
    } catch (Throwable t) {
      log.warn("ORT native_path mirror failed (best-effort, non-fatal)", t);
    }
  }

  /**
   * Production query for total VRAM. Returns -1 on failure. Reads the merged effective view from
   * the one GPU service (NVML-first, with nvidia-smi fallback) rather than the raw NVML probe, so
   * the boot-time VRAM gate sees the same single-authority answer as the rest of the system
   * (tempdoc 587; the {@code GpuProbeAccessTest} foreclosure). Safe to call once at boot.
   */
  private static long queryNvmlTotalVramBytes() {
    try {
      Long total = new GpuCapabilitiesService().snapshot().effective().totalVramBytes();
      return total != null ? total : -1L;
    } catch (Throwable t) {
      log.debug("Total-VRAM query failed (best-effort): {}", t.getMessage());
      return -1L;
    }
  }

  static {
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    SLF4JBridgeHandler.install();
  }

  @SuppressWarnings("PMD.SystemPrintln")
  public static void main(String[] args) {
    // Install crash reporter before anything else â€” catches uncaught exceptions on any thread.
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, throwable) -> {
          io.justsearch.telemetry.CrashReporter.writeCrashReport(
              io.justsearch.telemetry.CrashReporter.defaultCrashDir(),
              "head",
              thread,
              throwable);
          System.exit(1);
        });
    io.justsearch.telemetry.CrashReporter.pruneOldCrashReports(
        io.justsearch.telemetry.CrashReporter.defaultCrashDir(), 30);

    long t0 = System.nanoTime();
    long tPhase;
    long tPrev;
    log.info("Starting JustSearch HeadlessApp...");

    // Avoid infra health port conflicts; allow ephemeral bind.
    System.setProperty("justsearch.infra.health.port", "0");
    System.setProperty("justsearch.infra.health.host", "127.0.0.1");
    Telemetry telemetry = null;
    HeadAssembly bootstrap = null;
    LocalApiServer apiServer = null;
    io.justsearch.app.services.settings.UiSettingsStore settingsStore = null; // NOPMD - defensive init
    KnowledgeServerBootstrap knowledgeServer = null;
    String knowledgeServerStartError = null; // NOPMD - defensive init
    RuntimeManifestPublisher manifestPublisher = null;
    AppInstanceLock appInstanceLock = null;
    CountDownLatch latch = new CountDownLatch(1);
    io.justsearch.ui.api.UpgradeShutdownBridge upgradeShutdownBridge =
        new io.justsearch.ui.api.UpgradeShutdownBridge();
    io.justsearch.ui.api.LifecycleShutdownBridge lifecycleShutdownBridge =
        new io.justsearch.ui.api.LifecycleShutdownBridge();

    try {
      // Phase 0: resolve config (tempdoc 502 §3.3)
      ConfigPhaseResult configPhase = resolveConfig();
      settingsStore = configPhase.settingsStore();
      configStore = configPhase.configStore();
      // Tempdoc 882 item 24: snapshot the quarantine record NOW. buildApi() can save settings
      // before the condition substrate exists (the AI autostart seed writes chatEnabled on a fresh
      // profile), and a successful save clears the store's own record; the user was still reset
      // and must still be told.
      final Optional<
              io.justsearch.app.services.settings.UiSettingsStore.RecoveredFromCorrupt>
          settingsRecovery = settingsStore.lastRecovery();

      // Tempdoc 501 Phase 3: acquire AppInstanceLock at the Head BEFORE binding HTTP or
      // spawning the Worker. The lock is OS-level (FileChannel.tryLock) with PID+startedAt
      // metadata and stale recovery via ProcessHandle.of() — see AppInstanceLock.java.
      // Acquiring here lifts the invariant from the Worker-only path into the producer,
      // catching duplicate launches regardless of who started them (dev-runner, bare
      // gradle run, manual java -cp, production launcher). KnowledgeServerBootstrap
      // continues to call AppInstanceLock for the standalone-test paths but skips the
      // acquire when this system property is set.
      try {
        appInstanceLock = new AppInstanceLock(configPhase.dataDir());
        appInstanceLock.acquire();
      } catch (AppInstanceLock.AppInstanceLockException e) {
        log.error("=== DATA DIRECTORY LOCKED ===");
        log.error("Another JustSearch instance is already running for dataDir={}",
            configPhase.dataDir());
        log.error("Refusing to start. Stop the other instance first.");
        log.error("Lock file: {}/app.lock", configPhase.dataDir());
        System.exit(2);
        return;
      }

      // Tempdoc 501 Phase 1: instantiate the runtime manifest publisher as soon as the dataDir
      // is known. The first manifest write happens after the API server binds (Phase 2 below);
      // the worker fields are filled in after Phase 3 (Worker connect). The publisher cleans
      // up its files in the shutdown finally block.
      manifestPublisher = new RuntimeManifestPublisher(configPhase.dataDir());

      tPhase = System.nanoTime();
      long settingsMs = (tPhase - t0) / 1_000_000;
      tPrev = tPhase;

      // Phase 1: infrastructure (telemetry, policy)
      InfraPhaseResult infraPhase = setupInfra(configPhase);
      telemetry = infraPhase.telemetry();

      tPhase = System.nanoTime();
      long telemetryMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // Tempdoc 627 Deliverable 10: create ONE WorkerCapability before the async worker-start fork
      // and inject it into BOTH the worker bootstrap (the supervisor's writer) and the HeadAssembly
      // CapabilityGraph (the surfaces' reader). One instance => no mirror, no silent state-drift.
      io.justsearch.app.services.lifecycle.WorkerCapability sharedWorkerCapability =
          new io.justsearch.app.services.lifecycle.WorkerCapability();

      // Start Knowledge Server asynchronously — spawn runs in parallel with API construction.
      java.util.concurrent.CompletableFuture<KnowledgeServerStartResult> workerFuture =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> tryStartKnowledgeServer(sharedWorkerCapability));

      // Phase 2: Build API server (degraded mode — no Worker yet)
      ApiPhaseResult apiPhase =
          buildApi(
              infraPhase,
              settingsStore,
              manifestPublisher,
              sharedWorkerCapability,
              upgradeShutdownBridge,
              lifecycleShutdownBridge);
      bootstrap = apiPhase.bootstrap();
      apiServer = apiPhase.apiServer();

      // Tempdoc 627 (N1): if the previous app session ended uncleanly (a leftover runtime manifest
      // with a dead PID — the Head cannot observe its own crash in-life), narrate it now as a calm
      // occurrence on the existing RECENT EVENTS substrate. The substrate is up (buildApi above);
      // the publisher classified the leftover at construction, before publishHead overwrites it.
      // Best-effort — never blocks boot.
      if (manifestPublisher.detectedUncleanPreviousShutdown()) {
        try {
          var health = bootstrap.substrate().health();
          io.justsearch.app.services.observability.health.BootRecoveryEmitter
              .emitUncleanShutdownRecovered(
                  health.occurrenceLog(),
                  health.changes(),
                  health.headSource(),
                  java.time.Clock.systemUTC(),
                  manifestPublisher.previousInstancePid());
        } catch (Exception e) {
          log.warn("Unclean-shutdown-recovered narration failed (non-fatal)", e);
        }
      }

      // Tempdoc 882 item 24: resolveConfig() found an unreadable ui/settings.json, preserved it and
      // loaded defaults instead of killing the boot (ADR-0008). Tell the user now that the
      // condition substrate exists, and take the notice back down when they re-author settings.
      if (bootstrap != null && bootstrap.substrate() != null && bootstrap.substrate().health() != null) {
        var settingsHealth = bootstrap.substrate().health();
        settingsRecovery.ifPresent(
                recovery ->
                    io.justsearch.app.services.settings.SettingsRecoveryNotice.publish(
                        settingsHealth.conditionStore(),
                        settingsHealth.changes(),
                        recovery,
                        settingsHealth.headSource(),
                        java.time.Clock.systemUTC()));
        settingsStore.setOnRecoveryCleared(
            () ->
                io.justsearch.app.services.settings.SettingsRecoveryNotice.clear(
                    settingsHealth.conditionStore(), settingsHealth.changes()));
      }

      // Tempdoc 501 Phase 1: first manifest write — head-only readiness. The lock file
      // is acquired here. Worker fields populated after Phase 3 below.
      try {
        manifestPublisher.publishHead(apiPhase.port(), apiPhase.sessionToken());
      } catch (Exception e) {
        log.warn("Runtime manifest publishHead failed (non-fatal)", e);
      }

      tPhase = System.nanoTime();
      long apiMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // Phase 3: Wait for Worker and connect
      WorkerConnectionResult workerResult = connectWorker(apiPhase, workerFuture);
      knowledgeServer = workerResult.knowledgeServer();
      knowledgeServerStartError = workerResult.startError();

      // Tempdoc 501 Phase 29 + Phase 33: manifest-listener wiring extracted into
      // RuntimeManifestListenerWiring. The live-worker supplier reads
      // bootstrap.currentKnowledgeServer() so health-monitor-driven worker
      // restarts are reflected in the worker.grpcPort projection.
      // Tempdoc 657: the install/runtime intent is a launch-time config value
      // (-Djustsearch.mode / JUSTSEARCH_MODE), read once here and projected onto the
      // manifest's mode.intent by the listener wiring.
      String modeIntent =
          io.justsearch.configuration.model.InstallIntent.fromConfig(
                  EnvRegistry.MODE.get().orElse(null))
              .id();
      // Tempdoc 825 review F3: the supplier falls back to the boot-time instance. The worker
      // listener fires on the READY transition, which happens INSIDE a recovery attempt — before
      // the handover has populated HeadAssembly's reference — so a currentKnowledgeServer()-only
      // supplier published worker.state=ready with a null gRPC port after every boot recovery. The
      // fallback is the same object the monitor is recovering, and by the time READY fires its
      // signal bus is live and carries the real port, so the manifest is correct AT the event
      // rather than corrected after it.
      final KnowledgeServerBootstrap bootTimeKnowledgeServer = knowledgeServer;
      final HeadAssembly assemblyForManifest = bootstrap;
      java.util.function.Supplier<KnowledgeServerBootstrap> liveKnowledgeServer =
          () -> {
            KnowledgeServerBootstrap connected = assemblyForManifest.currentKnowledgeServer();
            return connected != null ? connected : bootTimeKnowledgeServer;
          };
      io.justsearch.ui.runtime.RuntimeManifestListenerWiring.wire(
          manifestPublisher,
          bootstrap,
          knowledgeServer,
          knowledgeServerStartError,
          liveKnowledgeServer,
          () -> configStore.get().paths().indexBasePath(),
          modeIntent);

      long workerMs = (System.nanoTime() - tPrev) / 1_000_000;
      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      log.info("Startup phases (ms): settings={}, telemetry={}, api={}, worker={}, total={}",
          settingsMs, telemetryMs, apiMs, workerMs, totalMs);
      log.info("Local API Server started on port {}", apiPhase.port());

      // Boot contract validation moved to before API server construction (tempdoc 502 §6).

      final LocalApiServer apiServerRef = apiServer;
      final HeadAssembly bootstrapRef = bootstrap;
      final Telemetry telemetryRef = telemetry;
      final KnowledgeServerBootstrap knowledgeServerRef = knowledgeServer;
      final KnowledgeServerHealthMonitor knowledgeServerHealthMonitorRef = workerResult.healthMonitor();
      final RuntimeManifestPublisher manifestPublisherRef = manifestPublisher;
      final AppInstanceLock appInstanceLockRef = appInstanceLock;
      final HeadShutdownCoordinator shutdownCoordinator =
          new HeadShutdownCoordinator(
              configPhase.dataDir(),
              () ->
                  performOrderedShutdown(
                      apiServerRef,
                      bootstrapRef,
                      knowledgeServerHealthMonitorRef,
                      knowledgeServerRef,
                      manifestPublisherRef,
                      infraPhase.tracingBootstrap(),
                      telemetryRef,
                      appInstanceLockRef),
              System::exit);
      upgradeShutdownBridge.install(shutdownCoordinator);
      // Tempdoc 805 G.1: the same coordinator answers the shell's normal-quit request — one
      // ordered-shutdown routine, two callers.
      lifecycleShutdownBridge.install(shutdownCoordinator::shutdownAndExit);

      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    log.info("Shutting down HeadlessApp...");
                    shutdownCoordinator.shutdownNormally();
                    latch.countDown();
                  },
                  "justsearch-headless-shutdown"));

      latch.await();
      log.info("HeadlessApp stopped.");

    } catch (Exception e) {
      log.error("Fatal error in HeadlessApp", e);
      System.exit(1);
    } finally {
      try {
        if (apiServer != null) {
          apiServer.stop();
        }
      } catch (Exception ignored) {
        // best effort
      }
      try {
        if (bootstrap != null) {
          bootstrap.close();
        }
      } catch (Exception ignored) {
        // best effort
      }
      try {
        if (knowledgeServer != null) {
          knowledgeServer.close();
        }
      } catch (Exception ignored) {
        // best effort
      }
      try {
        if (telemetry != null) {
          telemetry.close();
        }
      } catch (Exception ignored) {
        // best effort
      }
      // Tempdoc 501 Phase 1: idempotent manifest cleanup. The shutdown hook above already
      // closed the publisher under SIGTERM/clean-exit; this finally block covers the path
      // where main returns from `latch.await()` after the hook fired. Calling close() twice
      // is safe.
      try {
        if (manifestPublisher != null) {
          manifestPublisher.close();
        }
      } catch (Exception e) {
        log.debug("Manifest publisher close failed in finally (non-fatal)", e);
      }
      // Tempdoc 501 Phase 3: release the app instance lock if we acquired it. Idempotent
      // (AppInstanceLock.close() returns silently if already closed).
      try {
        if (appInstanceLock != null) {
          appInstanceLock.close();
        }
      } catch (Exception e) {
        log.debug("AppInstanceLock close failed in finally (non-fatal)", e);
      }
      // Tempdoc 501 Phase 18: api-port.txt is gone, the manifest publisher's
      // close() (above) handles its own file cleanup.
    }
  }

  private record KnowledgeServerStartResult(KnowledgeServerBootstrap bootstrap, String startError) {}

  private static HeadShutdownCoordinator.ShutdownResult performOrderedShutdown(
      LocalApiServer apiServer,
      HeadAssembly bootstrap,
      KnowledgeServerHealthMonitor healthMonitor,
      KnowledgeServerBootstrap knowledgeServer,
      RuntimeManifestPublisher manifestPublisher,
      io.justsearch.telemetry.TracingBootstrap tracing,
      Telemetry telemetry,
      AppInstanceLock appInstanceLock) {
    List<String> errors = new java.util.ArrayList<>();
    String workerOutcome = "GRACEFUL";
    try {
      if (manifestPublisher != null) manifestPublisher.close();
    } catch (Exception e) {
      errors.add("runtime-manifest");
    }
    try {
      if (apiServer != null) apiServer.stop();
    } catch (Exception e) {
      errors.add("local-api");
    }
    try {
      if (healthMonitor != null) healthMonitor.close();
    } catch (Exception e) {
      errors.add("worker-health-monitor");
    }
    try {
      if (bootstrap != null) bootstrap.close();
    } catch (Exception e) {
      errors.add("head-assembly");
    }
    try {
      if (knowledgeServer != null) {
        workerOutcome = knowledgeServer.closeForUpgrade().name();
        if (!"GRACEFUL".equals(workerOutcome)) {
          errors.add("worker-" + workerOutcome.toLowerCase(java.util.Locale.ROOT));
        }
      }
    } catch (Exception e) {
      workerOutcome = "FAILED";
      errors.add("worker");
    }
    try {
      if (tracing != null) tracing.close();
    } catch (Exception e) {
      errors.add("tracing");
    }
    try {
      if (telemetry != null) telemetry.close();
    } catch (Exception e) {
      errors.add("telemetry");
    }
    try {
      if (appInstanceLock != null) appInstanceLock.close();
    } catch (Exception e) {
      errors.add("app-instance-lock");
    }
    return new HeadShutdownCoordinator.ShutdownResult(
        errors.isEmpty(), workerOutcome, errors);
  }

  private static KnowledgeServerStartResult tryStartKnowledgeServer(
      io.justsearch.app.services.lifecycle.WorkerCapability sharedWorkerCapability) {
    // Tempdoc 825: held outside the try so a failed start still RETURNS the instance. The pre-825
    // code manufactured the null that connectWorker then turned into a permanent DEGRADED pin with
    // no monitor — the "boot brick" of 821 §O.4. The instance is restartable by construction
    // (close() resets the started guard), which is what makes the recovery arm possible at all.
    KnowledgeServerBootstrap bootstrap = null;
    try {
      log.info("Attempting to start Knowledge Server...");
      // Lane F stage A item A6: the index half is composed INSIDE this JVM. EngineRoot is the
      // composition root (design 3.2) and the only module allowed to bind both halves; handing it
      // to the bootstrap as the WorkerHost is what replaces "spawn a process, discover its port,
      // open a channel". This is the single site that decides where the index lives.
      io.justsearch.app.services.worker.KnowledgeServerConfig ksConfig =
          io.justsearch.app.services.worker.KnowledgeServerConfig.load();
      bootstrap =
          new KnowledgeServerBootstrap(
              ksConfig,
              null,
              sharedWorkerCapability,
              new io.justsearch.app.engine.EngineRoot(
                  ksConfig.deadlineMs(), ksConfig.batchSize()));
      // Retry transient boot-time timing failures. A single failed start used to be terminal: the
      // catch below returned a null bootstrap, connectWorker() then pinned the worker capability
      // DEGRADED and started no health monitor, so nothing recovered for the life of the process.
      bootstrap.startWithRetry();
      // Tempdoc 374 alpha.23 R13-A defect #4: don't log "started successfully" if the
      // bootstrap landed in ERROR (round 13 cycle 2 evidence). The background health
      // monitor will attempt recovery and log when the worker reaches READY.
      if (bootstrap.workerCapability().available()) {
        log.info("Knowledge Server started successfully, health: READY");
      } else {
        log.warn("Knowledge Server start did not reach READY (health: {}); background health monitor will retry",
            bootstrap.workerCapability().health());
      }
      return new KnowledgeServerStartResult(bootstrap, null);
    } catch (AppInstanceLock.AppInstanceLockException e) {
      // This should be fatal: running two instances against the same dataDir is unsafe.
      log.error("=== DATA DIRECTORY LOCKED ===");
      log.error("Another JustSearch instance is already using this data directory.");
      log.error("Details:", e);
      log.error("Fix: Close the other instance, or launch with a different data dir via -Djustsearch.data.dir=<path>.");
      throw new RuntimeException(e);
    } catch (Exception e) {
      // Elevated to ERROR - this is a critical failure that affects core functionality
      log.error("=== KNOWLEDGE SERVER FAILED TO START ===");
      log.error("Indexing and search features will be UNAVAILABLE.");
      log.error("Cause:", e);
      log.error(
          "To fix: {}",
          io.justsearch.app.services.worker.WorkerStartFailures.operatorHint(e));
      log.error("Stack trace:", e);
      return new KnowledgeServerStartResult(bootstrap, startErrorFor(bootstrap, e));
    }
  }

  /**
   * Tempdoc 915 R1: when the worker refused deterministically it wrote a fatal index reason before
   * exiting, and the bootstrap latched it. That sentence — not the spawn symptom the Head happened to
   * observe — is what {@code knowledgeServerStartError} must carry, because the string is rendered
   * verbatim to the user. Live arm 2 showed the alternative: "Worker process crashed (exit code 1)
   * before writing port to signal file" for an index the worker had deliberately left untouched. The
   * exception itself is still logged above at ERROR with its stack, so nothing is lost.
   */
  static String startErrorFor(KnowledgeServerBootstrap bootstrap, Exception e) {
    String indexFatal = bootstrap == null ? null : bootstrap.indexFatalDetail();
    return indexFatal != null ? indexFatal : summarizeStartError(e);
  }

  private static String summarizeStartError(Exception e) {
    if (e == null) return "";
    String msg = e.getMessage();
    if (msg == null || msg.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return msg;
  }

  /**
   * Auto-selects the cuda12 variant if GPU acceleration is requested but CUDA runtime is missing.
   *
   * <p>This handles the common case where users have NVIDIA GPUs but don't have the CUDA Toolkit
   * installed. The cuda12 variant includes a statically-linked CUDA runtime that works standalone.
   *
   * <p>Conditions for auto-selection:
   * <ol>
   *   <li>GPU layers > 0 (user wants GPU acceleration)</li>
   *   <li>Server executable not explicitly set via environment variable</li>
   *   <li>Current/default server uses dynamically-linked CUDA DLL</li>
   *   <li>CUDA runtime (cudart64_*.dll) is not available</li>
   *   <li>cuda12 variant exists</li>
   * </ol>
   */
  private static void maybeAutoSelectCuda12Variant(UiSettings settings, ConfigStore activeConfigStore) {
    try {
      // Check if GPU acceleration is requested
      int gpuLayers = settings.getGpuLayers();
      ConfigStore cs = activeConfigStore != null ? activeConfigStore : ConfigStore.globalOrNull();
      if (cs != null && cs.get().ai().gpuLayers() != 0) {
        gpuLayers = cs.get().ai().gpuLayers();
      }
      if (gpuLayers <= 0) {
        log.info("GPU auto-selection: SKIPPED (gpu_layers={})", gpuLayers);
        return;
      }

      // Check if user explicitly set server exe via environment variable (respect their choice)
      String serverExeSource = cs != null ? cs.get().ai().serverExeSource() : "";
      String serverExeEnv = System.getenv("JUSTSEARCH_SERVER_EXE");
      if ("environment_variable".equals(serverExeSource)
          || "operator".equals(serverExeSource)
          || (serverExeEnv != null && !serverExeEnv.isBlank())) {
        log.info(
            "GPU auto-selection: SKIPPED (server explicitly set via {})",
            serverExeSource.isBlank() ? "env var" : serverExeSource);
        return;
      }

      // Find the current/default server executable
      Path serverExe = resolveDefaultServerExecutable();
      if (serverExe == null || !Files.isRegularFile(serverExe)) {
        log.info("GPU auto-selection: SKIPPED (default server not found)");
        return;
      }

      // Check if server already has statically-linked CUDA (no switch needed)
      if (hasStaticCuda(serverExe)) {
        log.info("GPU auto-selection: SKIPPED (server has static CUDA)");
        return;
      }

      // Check if server has dynamically-linked CUDA with runtime available (no switch needed)
      if (hasDynamicCudaWithRuntime(serverExe)) {
        log.info("GPU auto-selection: SKIPPED (server has CUDA with runtime)");
        return;
      }

      // At this point: server is CPU-only OR has dynamically-linked CUDA without runtime
      // Both cases benefit from switching to cuda12 variant

      // Find cuda12 variant
      Path cuda12Exe = findCuda12Variant(serverExe);
      if (cuda12Exe == null || !Files.isRegularFile(cuda12Exe)) {
        Path expectedPath =
            serverExe
                .getParent()
                .resolve("variants")
                .resolve("cuda12")
                .resolve("llama-server.exe");
        log.warn("========================================");
        log.warn("GPU ACCELERATION UNAVAILABLE");
        log.warn("GPU requested (gpu_layers={}) but cuda12 variant not found", gpuLayers);
        log.warn("Expected: {}", expectedPath);
        log.warn("Check /api/ai/runtime/status for diagnostics");
        log.warn("========================================");
        return;
      }

      // Verify required CUDA DLLs exist in cuda12 variant directory
      Path cuda12Dir = cuda12Exe.getParent();
      String[] requiredDlls = {"ggml-cuda.dll", "cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll"};
      List<String> missingDlls = new java.util.ArrayList<>();
      for (String dll : requiredDlls) {
        if (!Files.isRegularFile(cuda12Dir.resolve(dll))) {
          missingDlls.add(dll);
        }
      }
      if (!missingDlls.isEmpty()) {
        log.warn("========================================");
        log.warn("GPU ACCELERATION UNAVAILABLE");
        log.warn("cuda12 variant found but missing DLLs: {}", String.join(", ", missingDlls));
        log.warn("Directory: {}", cuda12Dir);
        log.warn("========================================");
        return;
      }

      // Auto-select cuda12 variant
      log.info("=== AUTO-SELECTING CUDA12 VARIANT ===");
      log.info("GPU acceleration requested. Current server is CPU-only or missing CUDA runtime.");
      log.info("Switching to cuda12 variant for GPU acceleration.");
      log.info("  From: {}", serverExe);
      log.info("  To:   {}", cuda12Exe);

      System.setProperty("justsearch.server.exe", cuda12Exe.toAbsolutePath().toString());
      System.setProperty("justsearch.server.exe.source", "auto_selected_cuda12");

    } catch (Exception e) {
      log.warn("GPU auto-selection failed (continuing with default)", e);
    }
  }

  /** Resolves the default server executable path (same logic as InferenceConfig). */
  private static Path resolveDefaultServerExecutable() {
    // Check if already set via sysprop
    Path serverExePath = ConfigStore.global().get().ai().serverExe();
    String serverExeProp = serverExePath != null ? serverExePath.toString() : null;
    if (serverExeProp != null && !serverExeProp.isBlank()) {
      return Path.of(serverExeProp);
    }

    // Check standard locations
    try {
      Path home = ConfigStore.global().get().paths().home();
      if (home == null) {
        home = PlatformPaths.resolveDataDir();
      }
      if (home != null) {
        Path exe = home.resolve("native-bin").resolve("llama-server").resolve("llama-server.exe");
        if (Files.isRegularFile(exe)) {
          return exe;
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }

    // Check repo root for dev mode
    try {
      Path repoRoot = io.justsearch.configuration.RepoRootLocator.findRepoRootOrNull();
      if (repoRoot != null) {
        Path exe = repoRoot.resolve("native-bin").resolve("llama-server").resolve("llama-server.exe");
        if (Files.isRegularFile(exe)) {
          return exe;
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }

    return null;
  }

  /**
   * Checks if the server already has statically-linked CUDA (bundled runtime, no switch needed).
   * Statically-linked ggml-cuda.dll is ~437MB, dynamically-linked is ~80MB.
   */
  private static boolean hasStaticCuda(Path serverExe) {
    if (serverExe == null) return false;
    Path serverDir = serverExe.getParent();
    if (serverDir == null) return false;

    Path ggmlCuda = serverDir.resolve("ggml-cuda.dll");
    if (!Files.exists(ggmlCuda)) {
      return false; // No CUDA DLL
    }

    try {
      long size = Files.size(ggmlCuda);
      // Statically-linked is ~437MB, use 400MB as threshold
      return size > 400_000_000L;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Checks if the server has dynamically-linked CUDA AND the runtime is available.
   * In this case, GPU will work with the current server (no switch needed).
   */
  private static boolean hasDynamicCudaWithRuntime(Path serverExe) {
    if (serverExe == null) return false;
    Path serverDir = serverExe.getParent();
    if (serverDir == null) return false;

    Path ggmlCuda = serverDir.resolve("ggml-cuda.dll");
    if (!Files.exists(ggmlCuda)) {
      return false; // No CUDA DLL - CPU-only, needs switch
    }

    try {
      long size = Files.size(ggmlCuda);
      // Dynamically-linked is ~80MB
      if (size >= 200_000_000L) {
        return false; // Not dynamically-linked (probably static)
      }
    } catch (Exception e) {
      return false;
    }

    // Has dynamically-linked CUDA, check if runtime is available
    return hasCudaRuntime(serverDir);
  }

  /** Checks if CUDA runtime (cudart64_*.dll) is available. */
  private static boolean hasCudaRuntime(Path serverDir) {
    // Check server directory
    if (serverDir != null && hasCudaRuntimeInDir(serverDir)) {
      return true;
    }

    // Check System32
    String systemRoot = System.getenv("SystemRoot");
    if (systemRoot != null) {
      Path system32 = Path.of(systemRoot, "System32");
      if (hasCudaRuntimeInDir(system32)) {
        return true;
      }
    }

    return false;
  }

  private static boolean hasCudaRuntimeInDir(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) return false;
    try (var stream = Files.list(dir)) {
      return stream.anyMatch(p -> {
        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("cudart64_") && name.endsWith(".dll");
      });
    } catch (Exception e) {
      return false;
    }
  }

  /** Finds the cuda12 variant executable. */
  private static Path findCuda12Variant(Path currentServerExe) {
    if (currentServerExe == null) return null;

    // Check relative to current server: ../variants/cuda12/llama-server.exe
    Path serverDir = currentServerExe.getParent();
    if (serverDir != null) {
      Path cuda12 = serverDir.resolve("variants").resolve("cuda12").resolve("llama-server.exe");
      if (Files.isRegularFile(cuda12)) {
        return cuda12;
      }
    }

    // Check repo root for dev mode
    try {
      Path repoRoot = io.justsearch.configuration.RepoRootLocator.findRepoRootOrNull();
      if (repoRoot != null) {
        Path cuda12 = repoRoot
            .resolve("modules").resolve("ui").resolve("native-bin")
            .resolve("llama-server").resolve("variants").resolve("cuda12")
            .resolve("llama-server.exe");
        if (Files.isRegularFile(cuda12)) {
          return cuda12;
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }

    return null;
  }

  /**
   * Best-effort: ensure the canonical data-dir system property is set for this JVM process.
   */
  private static void harmonizeDataDirProperties(Path resolvedDataDir) {
    if (resolvedDataDir == null) {
      return;
    }
    String resolved = resolvedDataDir.toAbsolutePath().normalize().toString();

    // Canonical property used by EnvRegistry/PlatformPaths
    String canonical = System.getProperty(EnvRegistry.DATA_DIR.sysProp());
    if (canonical == null || canonical.isBlank()) {
      System.setProperty("justsearch.data.dir", resolved);
    }
  }
}
