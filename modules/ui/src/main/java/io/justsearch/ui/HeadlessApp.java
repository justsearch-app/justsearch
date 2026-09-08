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
   *       empty (no user sysprop or env var override exists). The mirror
   *       exists so the value survives
   *       {@link io.justsearch.app.services.config.ConfigStoreRebuilder#rebuild},
   *       which only re-contributes sysprops via env-registry, not the
   *       transient ord-150 autoDetected map.
   *       <p>It used to have a second reason — {@code GPU_ENABLED} and
   *       {@code ORT_NATIVE_PATH} were forwarded as {@code -D} args to the
   *       Worker child process, and {@code GPU_LAYERS} reached it through the
   *       ordinal-450 worker-config snapshot. Lane F stage A deleted the child
   *       process (item A11), the {@code -D} forwarding set and the snapshot
   *       tier (item A19). There is one JVM and one {@code ResolvedConfig}, so
   *       the rebuild survival above is now the whole reason.
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
    maybeMirrorOrtNativePath(configStore);

    Path dataDir = PlatformPaths.resolveDataDir();
    ResolvedConfig effectiveConfig = rebuildAfterPostBuildWrites(configStore, settings);

    // Lane F item A6 left this half of the ORT setup behind, and the review (B2) caught it. See
    // applyOrtNativePack: it must run here, after the rebuild, and it is the last boot step that
    // may precede an ORT class-init.
    applyOrtNativePack(effectiveConfig);

    return new ConfigPhaseResult(settingsStore, settings, effectiveConfig, configStore, dataDir);
  }

  /**
   * Rebuilds the resolved config AFTER the two boot steps that write system properties the resolver
   * has already read past — {@code maybeAutoSelectCuda12Variant} (the cuda12 {@code server.exe})
   * and {@code maybeMirrorOrtNativePath} — and returns the config the rest of boot will see.
   *
   * <p><b>Lane F item A19 took the snapshot out of this method, and deliberately not the rebuild.</b>
   * It used to do two things: rebuild, then serialise the result to
   * {@code <dataDir>/runtime/worker-config-snapshot.json} for a second process to load at config
   * ordinal 450. There is no second process, so the write and the whole ordinal-450 tier are gone.
   * The REBUILD is not part of that tier and must survive it: it is what makes the two sysprop
   * writes above visible to every later reader, and the ORT native-pack detection immediately below
   * depends on it (review B2 — reading the pre-rebuild config there finds no pack and silently
   * leaves the Engine on CPU, which is the tempdoc 883 §C.5c defect one field over). Deleting this
   * method along with the snapshot would have looked like tidy residue removal and would have
   * reintroduced that defect, which is why the name no longer says "snapshot".
   *
   * <p>Tempdoc 883 §C.5c, still the reason for the ordering: rebuilding through
   * {@link io.justsearch.app.services.config.ConfigStoreRebuilder} rather than re-reading the
   * sysprops by hand keeps ONE assembly path — the same ordinal-150 probe, ordinal-300 settings and
   * base sources the initial build used, plus whatever the two steps just wrote at 500.
   *
   * @return the rebuilt config, which is also what {@link ConfigStore#get()} now returns
   */
  static ResolvedConfig rebuildAfterPostBuildWrites(ConfigStore configStore, UiSettings settings) {
    io.justsearch.app.services.config.ConfigStoreRebuilder.rebuild(configStore, settings);
    return configStore.get();
  }

  /**
   * Points ONNX Runtime at the consent-gated CUDA native pack, if one is installed and
   * version-matched (tempdoc 772 §J item 2).
   *
   * <p><b>Why this is here and not in the Worker any more.</b> This is the second half of
   * {@link #maybeMirrorOrtNativePath(ConfigStore)}. That method resolves <em>JustSearch's</em> config key
   * {@code justsearch.onnxruntime.native_path}; this one converts it into <em>ORT's</em>
   * {@code onnxruntime.native.path} system property, which ORT reads once, at class-init, and never
   * again. In the split architecture the two halves lived in two processes: the Head mirrored the
   * config key and wrote it into the Worker's config snapshot, and the Worker's {@code main}
   * applied the ORT property in its own JVM before building the {@code KnowledgeServer}. Lane F
   * item A6 moved the index half into this JVM without moving the apply, so from A6 until the
   * review caught it the property was never set in the Engine: every ONNX encoder silently ran on
   * CPU on a machine with a complete CUDA pack installed. Nothing failed — ORT falls back — which
   * is why only a wiring test can see it.
   *
   * <p><b>Why after the rebuild.</b> {@link #maybeMirrorOrtNativePath(ConfigStore)} writes a system property at
   * config ordinal 500, and the {@code ResolvedConfig} built above it was built before that write.
   * Reading {@code paths().ortNativePath()} off the pre-rebuild config would reproduce the tempdoc
   * 883 §C.5c defect one field over — a boot-time pack detection that the reader never sees. The
   * argument is deliberately {@code snapshot.config()}, the config
   * {@link #rebuildAfterPostBuildWrites} produced, so the ordering is in the signature rather than
   * in a comment.
   *
   * <p><b>Why this is the last safe point.</b> Everything before it in {@code resolveConfig} is
   * settings, config assembly and {@code GpuAutoDetection.probe}, none of which loads ORT; every
   * ORT session in the Engine — the index half's encoders under {@code EngineRoot}, the
   * application half's reranker and capability probes — is created later, in a phase that runs
   * after this one. Moving the call later than the config phase would put it after the point where
   * an ORT class-init becomes possible.
   *
   * @param effectiveConfig the config as rebuilt by {@link #rebuildAfterPostBuildWrites}
   * @return the decision that was acted on (for tests and for the boot log)
   */
  static io.justsearch.ort.OrtCudaHelper.OrtNativePackDecision applyOrtNativePack(
      ResolvedConfig effectiveConfig) {
    try {
      return io.justsearch.ort.OrtCudaHelper.applyOrtNativePackProperty(
          effectiveConfig == null ? null : effectiveConfig.paths().ortNativePath());
    } catch (Throwable t) {
      // Same posture as the mirror half: a GPU acceleration that cannot be configured must not
      // stop the Engine from booting onto the CPU path.
      log.warn("ORT native pack apply failed (best-effort, non-fatal)", t);
      return new io.justsearch.ort.OrtCudaHelper.OrtNativePackDecision(
          io.justsearch.ort.OrtCudaHelper.OrtNativePackStatus.DIR_ABSENT, String.valueOf(t));
    }
  }

  /**
   * The ONNX Runtime native directory named by the {@code justsearch.ai.onnxruntime_variant_id}
   * override, or by the variant the active llama-server exe sits under.
   *
   * <p>Carried over from {@code WorkerSpawner} at the lane F review, unchanged in behaviour: an
   * explicit variant id wins, otherwise the id is parsed out of the llama-server exe path
   * ({@code .../native-bin/llama-server/variants/<id>/...}), and the result is only used if the
   * corresponding {@code .../native-bin/onnxruntime/variants/<id>} directory actually exists.
   *
   * @return the directory, or {@code null} if no variant is configured or the directory is absent
   */
  private static Path variantOrtNativeDir(Path dataDir, ConfigStore configStore) {
    if (dataDir == null || configStore == null) {
      return null;
    }
    ResolvedConfig rc = configStore.get();
    if (rc == null) {
      return null;
    }
    String variantId = rc.ai().onnxruntimeVariantId();
    if (variantId == null || variantId.isBlank()) {
      variantId =
          variantIdFromLlamaServerExe(
              rc.ai().serverExe() != null ? rc.ai().serverExe().toString() : null);
    }
    if (variantId == null || variantId.isBlank()) {
      return null;
    }
    Path dir =
        dataDir
            .resolve("native-bin")
            .resolve("onnxruntime")
            .resolve("variants")
            .resolve(variantId.trim());
    return Files.isDirectory(dir) ? dir : null;
  }

  /**
   * Parses the variant id out of a llama-server exe path — the segment after
   * {@code native-bin/llama-server/variants}. Returns {@code null} for anything else, including a
   * path that does not contain the marker at all.
   */
  static String variantIdFromLlamaServerExe(String exePath) {
    if (exePath == null || exePath.isBlank()) {
      return null;
    }
    try {
      Path p = Path.of(exePath).toAbsolutePath().normalize();
      int n = p.getNameCount();
      for (int i = 0; i + 2 < n; i++) {
        if ("llama-server".equalsIgnoreCase(p.getName(i).toString())
            && "variants".equalsIgnoreCase(p.getName(i + 1).toString())) {
          String variantId = p.getName(i + 2).toString();
          return variantId.isBlank() ? null : variantId;
        }
      }
      return null;
    } catch (RuntimeException expected) {
      // InvalidPathException and friends: a malformed exe path is "no variant", not a boot failure.
      return null;
    }
  }

  private static void maybeMirrorOrtNativePath(ConfigStore configStore) {
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

      // The variant-derived candidate, ahead of the cuda12 default.
      //
      // Lane F item A11 deleted this without noticing: it lived in
      // WorkerSpawner.resolveOnnxRuntimeNativePathBestEffort, which set the same key on the Worker
      // CHILD's command line under the same "only if unset" guard. Deleting the spawner deleted
      // the only reader of ResolvedConfig.ai().onnxruntimeVariantId, which is how the
      // config-surface gate found it — an operator override that resolved, was reachable, and
      // changed nothing. Re-homed rather than deleted, because a machine with a
      // native-bin/onnxruntime/variants/<id> pack silently stopped using it at A11 and would go on
      // silently not using it.
      Path variantDir = variantOrtNativeDir(home, configStore);
      if (variantDir != null) {
        SystemPropertyUtils.setSysPropIfBlank(
            "justsearch.onnxruntime.native_path", variantDir.toAbsolutePath().toString());
        log.info(
            "ORT native path set to {} (derived from the ONNX Runtime variant id)",
            variantDir.toAbsolutePath());
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
    // Install crash reporter before anything else - catches uncaught exceptions on any thread.
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, throwable) -> {
          io.justsearch.telemetry.CrashReporter.writeCrashReport(
              io.justsearch.telemetry.CrashReporter.defaultCrashDir(),
              "head",
              thread,
              throwable);
          System.exit(io.justsearch.app.engine.EngineExit.FATAL_OR_UNCAUGHT);
        });
    io.justsearch.telemetry.CrashReporter.pruneOldCrashReports(
        io.justsearch.telemetry.CrashReporter.defaultCrashDir(), 30);

    long t0 = System.nanoTime();
    long tPhase;
    long tPrev;
    log.info("Starting JustSearch HeadlessApp...");

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
        System.exit(io.justsearch.app.engine.EngineExit.DATA_DIR_LOCKED);
        return;
      }

      // Clear only the predecessor's request, while the instance lock proves no current Engine can
      // be writing one. Failure is fatal: publishing readiness with a stale live request would let
      // the watcher shut this incarnation down immediately.
      final Path runtimeDir = configPhase.dataDir().resolve("runtime");
      clearPriorShutdownRequest(runtimeDir, java.nio.file.Files::deleteIfExists);

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
      // RuntimeManifestListenerWiring.
      // Tempdoc 657: the install/runtime intent is a launch-time config value
      // (-Djustsearch.mode / JUSTSEARCH_MODE), read once here and projected onto the
      // manifest's mode.intent by the listener wiring.
      String modeIntent =
          io.justsearch.configuration.model.InstallIntent.fromConfig(
                  EnvRegistry.MODE.get().orElse(null))
              .id();
      // Lane F stage A item A11: the live-knowledge-server supplier that used to be threaded in
      // here is gone with the thing it existed for. Tempdoc 825 review F3 added it so the worker
      // listener could re-read the CURRENT bootstrap at re-attainment time and publish the gRPC
      // port a restarted Worker had just been given. There is no worker process, no restart and no
      // port now — the index half is composed in this JVM — so the only reader of that supplier
      // (RuntimeManifestListenerWiring#readGrpcPort) went with it, and passing a supplier nothing
      // reads would be residue, not caution.
      io.justsearch.ui.runtime.RuntimeManifestListenerWiring.wire(
          manifestPublisher,
          bootstrap,
          knowledgeServer,
          knowledgeServerStartError,
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
      final io.justsearch.app.api.OperationLeaseService operationLeasesRef =
          bootstrapRef.serviceOut().operationLeaseService();
      final java.util.concurrent.atomic.AtomicReference<
              io.justsearch.app.engine.ShutdownRequestWatcher>
          shutdownRequestWatcherRef = new java.util.concurrent.atomic.AtomicReference<>();
      // Item B4: the ordered close is the composition root's (design 7.3). What is bound here is
      // each step to the object it closes — those objects live in this module and app-services, so
      // they cannot move into the root without inverting the ui -> app-engine edge.
      final io.justsearch.app.engine.EngineShutdownSequence shutdownSequence =
          new io.justsearch.app.engine.EngineShutdownSequence(
              configPhase.dataDir(),
              orderedShutdownSteps(
                  apiServerRef,
                  bootstrapRef,
                  knowledgeServerHealthMonitorRef,
                  knowledgeServerRef,
                  manifestPublisherRef,
                   infraPhase.tracingBootstrap(),
                   telemetryRef,
                   appInstanceLockRef,
                   operationLeasesRef,
                   shutdownRequestWatcherRef::get),
              System::exit);
      final HeadShutdownCoordinator shutdownCoordinator =
          new HeadShutdownCoordinator(shutdownSequence);
      // Item B6 (design 7.3). commit-shutdown becomes the FRONT half of the sequence: it validates
      // the nonce, freezes admission and reports blockers, and then writes the request file rather
      // than running the ordered close itself. The watcher below is what runs it. Routing the
      // upgrade through the same file as the supervisor means there is one trigger path to reason
      // about, not two that must be kept in step.
      upgradeShutdownBridge.install(upgradeShutdownRequestWriter(runtimeDir));
      // Tempdoc 805 G.1: the shell's normal-quit request. Unchanged — it is already cooperative and
      // in-process, so routing it through the file would add a poll's latency for nothing.
      lifecycleShutdownBridge.install(shutdownCoordinator::shutdownAndExit);

      // Item B3's watcher, started here because this is after the API front is up. It consumes the
      // request the upgrade path (and, from B8/B10, the supervisor) writes.
      startShutdownRequestWatcher(
              runtimeDir,
              shutdownRequestAcceptance(operationLeasesRef),
              shutdownRequestDispatcher(shutdownSequence),
              io.justsearch.app.engine.ShutdownRequestWatcher.DEFAULT_POLL_INTERVAL_MS,
              shutdownRequestWatcherRef::set);

      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    log.info("Shutting down HeadlessApp...");
                    // The hook fires DURING an exit the endpoint or the watcher may already have
                    // started; run() is memoised, so this joins that shutdown rather than starting
                    // a second one.
                    shutdownSequence.run(io.justsearch.app.engine.ShutdownRequest.Reason.QUIT);
                    latch.countDown();
                  },
                  "justsearch-headless-shutdown"));

      latch.await();
      log.info("HeadlessApp stopped.");

    } catch (Exception e) {
      log.error("Fatal error in HeadlessApp", e);
      System.exit(io.justsearch.app.engine.EngineExit.FATAL_OR_UNCAUGHT);
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

  /** Returns the production upgrade front half that persists a nonce-bound shutdown request. */
  static io.justsearch.ui.api.UpgradeShutdownAction upgradeShutdownRequestWriter(Path runtimeDir) {
    return (preparationId, shutdownNonce) -> {
      try {
        new io.justsearch.app.engine.ShutdownRequest(
                io.justsearch.app.engine.ShutdownRequest.Reason.UPGRADE,
                System.currentTimeMillis()
                    + io.justsearch.app.engine.ShutdownRequestWatcher.UPGRADE_DEADLINE_MS,
                shutdownNonce,
                "upgrade-controller",
                preparationId)
            .writeTo(runtimeDir);
      } catch (Exception e) {
        // A missing receipt is the updater's fail-closed signal, so a failed request write must not
        // be presented as a successful shutdown.
        log.error("Could not write the upgrade shutdown request; the Engine will not stop", e);
      }
    };
  }

  /** Accepts prepared upgrades only while their exact lease preparation remains live. */
  static java.util.function.Predicate<io.justsearch.app.engine.ShutdownRequest>
      shutdownRequestAcceptance(io.justsearch.app.api.OperationLeaseService operationLeases) {
    return request -> {
      if (request.reason() != io.justsearch.app.engine.ShutdownRequest.Reason.UPGRADE
          || request.preparationId() == null) {
        return true;
      }
      io.justsearch.app.api.OperationLeaseSnapshot snapshot = operationLeases.snapshot();
      return snapshot.admissionFrozen()
          && java.util.Objects.equals(request.preparationId(), snapshot.preparationId());
    };
  }

  /** Returns the production request-file back half that selects receipt or plain shutdown. */
  static java.util.function.Consumer<io.justsearch.app.engine.ShutdownRequest>
      shutdownRequestDispatcher(io.justsearch.app.engine.EngineShutdownSequence sequence) {
    return request -> {
      if (request.reason() == io.justsearch.app.engine.ShutdownRequest.Reason.UPGRADE
          && request.preparationId() != null) {
        sequence.runAndExitWithReceipt(request.preparationId(), request.nonce());
      } else {
        sequence.runAndExit(request.reason());
      }
    };
  }

  @FunctionalInterface
  interface RequestFileDeleter {
    boolean delete(Path path) throws java.io.IOException;
  }

  /** Strictly clears a predecessor request before this incarnation publishes readiness. */
  static void clearPriorShutdownRequest(Path runtimeDir, RequestFileDeleter deleter)
      throws java.io.IOException {
    Path request = io.justsearch.app.engine.ShutdownRequest.pathIn(runtimeDir);
    deleter.delete(request);
    if (java.nio.file.Files.exists(request)) {
      throw new java.io.IOException("shutdown request still exists after boot clear: " + request);
    }
  }

  /** Builds and starts the production watcher; boot clearing has already completed. */
  static io.justsearch.app.engine.ShutdownRequestWatcher startShutdownRequestWatcher(
      Path runtimeDir,
      java.util.function.Predicate<io.justsearch.app.engine.ShutdownRequest> accepts,
      java.util.function.Consumer<io.justsearch.app.engine.ShutdownRequest> onRequest,
      long pollIntervalMs,
      java.util.function.Consumer<io.justsearch.app.engine.ShutdownRequestWatcher> beforeStart) {
    var watcher =
        new io.justsearch.app.engine.ShutdownRequestWatcher(
            runtimeDir, accepts, onRequest, pollIntervalMs);
    beforeStart.accept(watcher);
    watcher.start();
    return watcher;
  }

  private record KnowledgeServerStartResult(KnowledgeServerBootstrap bootstrap, String startError) {}

  /**
   * The concrete ordered steps of design 7.3, bound to the objects they close.
   *
   * <p>The ORDER and the error accounting belong to {@link
   * io.justsearch.app.engine.EngineShutdownSequence}; what belongs here is the binding, because
   * these eight types live in this module and in app-services and the root may not import them.
   * Each step is named for the resource it releases, since that name is what appears in the
   * receipt's {@code errors} list and in the log line a support session reads.
   */
  static List<io.justsearch.app.engine.EngineShutdownSequence.Step>
      orderedShutdownSteps(
          LocalApiServer apiServer,
          HeadAssembly bootstrap,
          KnowledgeServerHealthMonitor healthMonitor,
          KnowledgeServerBootstrap knowledgeServer,
          RuntimeManifestPublisher manifestPublisher,
           io.justsearch.telemetry.TracingBootstrap tracing,
           Telemetry telemetry,
           AppInstanceLock appInstanceLock,
           io.justsearch.app.api.OperationLeaseService operationLeases,
           java.util.function.Supplier<io.justsearch.app.engine.ShutdownRequestWatcher>
               shutdownRequestWatcher) {
    return List.of(
        new io.justsearch.app.engine.EngineShutdownSequence.Step(
            "operation-admission",
            reason -> {
              operationLeases.freezeAdmission(reason.wire());
              return null;
            }),
        new io.justsearch.app.engine.EngineShutdownSequence.Step(
            "shutdown-request-watcher",
            reason -> {
              io.justsearch.app.engine.ShutdownRequestWatcher watcher =
                  shutdownRequestWatcher.get();
              if (watcher != null) watcher.close();
              return null;
            }),
        new io.justsearch.app.engine.EngineShutdownSequence.Step(
            "runtime-manifest",
            reason -> {
              if (manifestPublisher != null) manifestPublisher.close();
              return null;
            }),
        new io.justsearch.app.engine.EngineShutdownSequence.Step(
            "local-api",
            reason -> {
              if (apiServer != null) apiServer.stop();
              return null;
            }),
        new io.justsearch.app.engine.EngineShutdownSequence.Step(
            "worker-health-monitor",
            reason -> {
              if (healthMonitor != null) healthMonitor.close();
              return null;
            }),
        new io.justsearch.app.engine.EngineShutdownSequence.Step(
            "head-assembly",
            reason -> {
              if (bootstrap != null) {
                bootstrap.setStopGenerativeBackendOnClose(reason.stopsGenerativeBackend());
                bootstrap.close();
              }
              return null;
            }),
        // The one step whose outcome the receipt reports. Named INDEX_HALF_STEP in the sequence so
        // re-ordering cannot silently change which step the updater reads.
        new io.justsearch.app.engine.EngineShutdownSequence.Step(
            io.justsearch.app.engine.EngineShutdownSequence.INDEX_HALF_STEP,
            reason ->
                knowledgeServer == null
                    ? "GRACEFUL"
                    : knowledgeServer.closeForUpgrade().name()),
        new io.justsearch.app.engine.EngineShutdownSequence.Step(
            "tracing",
            reason -> {
              if (tracing != null) tracing.close();
              return null;
            }),
        new io.justsearch.app.engine.EngineShutdownSequence.Step(
            "telemetry",
            reason -> {
              if (telemetry != null) telemetry.close();
              return null;
            }),
        new io.justsearch.app.engine.EngineShutdownSequence.Step(
            "app-instance-lock",
            reason -> {
              if (appInstanceLock != null) appInstanceLock.close();
              return null;
            }));
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
      // Lane F stage A item A11: the "To fix: <hint>" line is gone with WorkerStartFailures. Every
      // hint it produced named a process-start symptom — a missing worker JAR, a signal file that
      // never carried a port, a pid that failed validation — and none of those can occur now that
      // the index half is composed in this JVM. Nothing replaces it: the line above already logs
      // the exception's own message, and the sentence the user is shown is startErrorFor()'s,
      // which prefers the bootstrap's latched index-fatal reason over the symptom seen here.
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
