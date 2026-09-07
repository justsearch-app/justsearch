/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import io.justsearch.app.api.AiInstallService;
import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.api.AiRuntimeActivationStatus;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.gpu.VramFlagsUtil;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.inference.EncoderRuntimeView;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.observability.EncoderRuntimeCache;
import io.justsearch.app.services.observability.EncoderRuntimeExplainer;
import io.justsearch.ort.EncoderRole;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.worker.OnnxModelStatus;
import io.justsearch.app.services.worker.WorkerFeatureCache;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.ModelPathSource;
import io.justsearch.app.api.inference.RealizedChatIdentity;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedPathResolver;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.RepoRootLocator;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * v3: Runtime variant activation with bounded self-test + rollback.
 *
 * <p>Important: install ≠ activate. Runtime packs can be imported safely without changing any runtime pointers.
 */
public final class RuntimeActivationService implements io.justsearch.app.api.RuntimeActivationService {
  private static final Logger log = LoggerFactory.getLogger(RuntimeActivationService.class);

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  private static final String STATUS_FILE = "runtime-activation-state.json";

  /**
   * Model-registry package id of the chat (GGUF) model — the key {@code AiInstallService} writes
   * into the install contract's {@code models} map and the same id its {@code applySettings} looks
   * up ({@code registry.findPackage("chat")}).
   */
  private static final String CHAT_PACKAGE_ID = "chat";

  // Mirror SettingsController behavior for server exe sysprop ownership.
  private static final String SERVER_EXE_SYS_PROP = "justsearch.server.exe";
  private static final String SERVER_EXE_SOURCE_PROP = "justsearch.server.exe.source";

  /**
   * Ownership markers this service recognizes on the server-executable sysprop.
   *
   * <p>Tempdoc 842: these were two private string literals here, a third private copy in
   * {@code EffectiveConfigController}, and the writers' own literals elsewhere — four copies of one
   * vocabulary. They now name the shared {@link ModelPathSource} constants (tempdoc 374 alpha.16
   * fix A's finding was exactly that a divergent copy silently reclassified the boot-time
   * CUDA auto-select as a third-party operator lock and rejected every activation).
   *
   * <p>Deliberately NOT {@link ModelPathSource#isSystemOwned}: that predicate also admits
   * {@link ModelPathSource#PROFILE_RESOLVED}, which is a claim about the <em>model path</em> and
   * says nothing about who owns the server executable. Widening this gate for free is how an
   * ownership check quietly stops being a check.
   */
  private static final String SOURCE_UI_SETTINGS = ModelPathSource.UI_SETTINGS;

  private static final String SOURCE_AUTO_SELECTED_CUDA12 = ModelPathSource.AUTO_SELECTED_CUDA12;

  /**
   * Chat-profile selection key (tempdoc 842 §2.3). A profile activation writes it so a later
   * same-JVM {@code InferenceConfig} rebuild resolves the same (model, mmproj) pair the engine was
   * just switched to, instead of falling back to the standard pair and half-reverting the switch.
   */
  private static final String CHAT_PROFILE_SYS_PROP = EnvRegistry.CHAT_PROFILE.sysProp();

  // Tempdoc 374 alpha.17 R1: route through the same sysprop as
  // LlamaServerOps.HEALTH_CHECK_TIMEOUT_MS so the activation self-test honours operator
  // overrides too. Default raised from 30s → 120s to cover Qwen3.5-9B Q4_K_M cold-load +
  // multimodal mmproj warmup on first launch (round-7 evidence).
  private static final long HEALTH_CHECK_TIMEOUT_MS =
      Long.parseLong(
          System.getProperty("justsearch.inference.health_check_timeout_ms", "120000")); // SYS-PROP-LEGACY-COMPAT: static init before ConfigStore
  private static final long HEALTH_CHECK_INTERVAL_MS = 500;

  // Windows process exit code for missing DLL dependencies at load time (STATUS_DLL_NOT_FOUND / 0xC0000135).
  private static final int WINDOWS_STATUS_DLL_NOT_FOUND = -1073741515;

  // Self-test VRAM delta threshold (best-effort; noisy environments should produce INCONCLUSIVE).
  private static final long MIN_VRAM_DELTA_BYTES = 64L * 1024 * 1024; // 64 MiB

  private final OnlineAiService onlineAi;
  private final UiSettingsStore settingsStore;
  // Tempdoc 374 alpha.27: VramDetector dependency removed; routes through
  // GpuCapabilitiesService (NVML-first) + VramRequirements helpers.
  private final GpuCapabilitiesService gpuCapabilitiesService;
  private final EnterprisePolicyService policyService;
  private final WorkerFeatureCache workerFeatureCache; // nullable
  private final InferenceCapability inferenceCapability; // nullable — tempdoc 656 Task 2
  private final AiInstallService aiInstallService; // nullable — tempdoc 727 F-3
  // Tempdoc 737 fix pack (fix 2): the single-writer runtime authority. When present, runActivate
  // brackets the engine-online + desired-state-write window in an ACTIVATION procedure so the
  // reconciler does not drift-converge the freshly-started engine DOWN before recordUserEnabled has
  // persisted the intent — and nudges specChanged() so the persisted intent is honored
  // deterministically, not via a racy mode-drift event. Nullable for graceful degradation / tests.
  private final RuntimeReconciler runtimeReconciler;

  private final Path aiHome;
  private final Path statusPath;

  /** Memo for {@link #variantsRoot()}; re-derived when it stops naming a directory (913 H1). */
  private volatile Path variantsRoot;

  /** Memo for {@link #repoRootOrNull()}; null = not yet resolved, empty = resolved to nothing. */
  private volatile Optional<Path> repoRootMemo;

  private final Object lock = new Object();
  private final AtomicBoolean running = new AtomicBoolean(false);

  /**
   * Op-lease SPI (tempdoc 617). Activation/deactivation rewrite the GPU runtime under
   * {@code native-bin/**} and the activation status projection on background threads that outlive
   * their HTTP request, so the request-scoped mutation lease is already released while the write
   * runs. Without a lease of its own, upgrade prepare sees no blocker and the installer can launch
   * mid-swap. Defaults to no-op so existing constructors and tests are unaffected.
   */
  private volatile OperationLeaseService operationLeases = OperationLeaseService.noOp();

  /**
   * Observed per-encoder runtime state (tempdoc 805 G.3). Late-bound like {@link #operationLeases}
   * because it reads through the Worker RPC client, which is null at bootstrap. Null = "no observed
   * data", which reports as {@code executionProvider: "unknown"} — never as a positive claim.
   */
  private volatile EncoderRuntimeCache encoderRuntimeCache;

  /** Late-binds the observed per-encoder runtime view used by {@link #getStatus()}. */
  public void setEncoderRuntimeCache(EncoderRuntimeCache cache) {
    this.encoderRuntimeCache = cache;
  }

  /**
   * Realized chat identity of the RUNNING engine (tempdoc 842 §2.5). Late-bound for the same
   * reason {@link #encoderRuntimeCache} is: the authority behind it (the inference lifecycle
   * manager) is assembled after this service exists.
   *
   * <p>Defaults to "no observation", which reports as absent fields — never as a positive claim
   * about which model is loaded. A supplier that returns null (engine offline) reports the same
   * way, so an offline engine cannot leave a stale identity standing.
   */
  private volatile Supplier<RealizedChatIdentity> realizedChatIdentitySource = () -> null;

  /**
   * Late-binds the realized chat-identity projection used by {@link #getStatus()}.
   *
   * @param source supplier over the running engine, or null to clear back to "no observation"
   */
  public void setRealizedChatIdentitySource(Supplier<RealizedChatIdentity> source) {
    this.realizedChatIdentitySource = source == null ? () -> null : source;
  }

  /**
   * Test-only override for the GPU self-test (the {@code setUsingExternalServerForTest} idiom the
   * inference runtime already uses).
   *
   * <p>The real self-test spawns llama-server on an ephemeral port and gates on an NVML VRAM delta,
   * so it can only ever return {@code inconclusive} on a GPU-less runner and {@code failed} against
   * a stub executable — a verdict the activation flow (correctly) refuses to act on. Without a seam
   * the entire post-self-test half of {@link #runActivate} — the profile apply, the settings
   * writes, the rollback bracket — is untestable, which is exactly where this feature lives.
   *
   * <p>The override receives the (exe, model) pair the flow resolved, so a test can also assert
   * WHICH model the self-test was pointed at. Null (the default) runs the real self-test.
   */
  private volatile java.util.function.BiFunction<Path, Path, SelfTestResult> selfTestOverrideForTest;

  /** Installs the test-only self-test override. Production code never calls this. */
  void setSelfTestOverrideForTest(
      java.util.function.BiFunction<Path, Path, SelfTestResult> override) {
    this.selfTestOverrideForTest = override;
  }

  /** Reads the late-bound realized identity, tolerating a throwing/absent source. */
  private RealizedChatIdentity realizedChatIdentity() {
    try {
      return realizedChatIdentitySource.get();
    } catch (Exception e) {
      log.debug("Realized chat identity unavailable (best-effort): {}", e.toString());
      return null;
    }
  }

  /**
   * Late-binds the op-lease SPI. Set by {@code ServicePhase}, which creates the lease service after
   * this service is constructed.
   */
  public void setOperationLeaseService(OperationLeaseService leases) {
    this.operationLeases = leases == null ? OperationLeaseService.noOp() : leases;
  }

  /**
   * Starts a daemon thread that holds an op-lease for its entire lifetime.
   *
   * <p>The lease is registered on the CALLING thread, before {@code start()}: registering inside
   * the thread leaves a window in which upgrade prepare observes no blocker while the work is about
   * to write. Same race-window closure as {@code BulkReindexHandler}.
   */
  private void startLeasedThread(String opClass, String threadName, Runnable body) {
    OperationLeaseHandle lease =
        operationLeases.register(
            opClass, OpCriticality.INTERRUPTIBLE_WITH_LOSS, 600L, Map.of("source", opClass));
    Thread t =
        new Thread(
            () -> {
              boolean ok = false;
              try {
                body.run();
                ok = true;
              } finally {
                running.set(false);
                lease.release(ok ? OpLeaseOutcome.SUCCESS : OpLeaseOutcome.FAILURE);
              }
            },
            threadName);
    t.setDaemon(true);
    try {
      t.start();
    } catch (RuntimeException e) {
      // The thread never ran, so its finally block will not release the lease.
      running.set(false);
      lease.release(OpLeaseOutcome.FAILURE);
      throw e;
    }
  }
  private final AiRuntimeActivationStatus status = new AiRuntimeActivationStatus();

  // Tempdoc 727 F-3: dedup for the "leftover variant directory" WARN below — listInstalledVariants()
  // runs on every GET /api/ai/runtime/status poll (~1/sec from the FE while activation/install is in
  // progress), so an un-deduped WARN spams once per second for as long as the condition holds, even
  // for a genuine leftover directory. Logged at most once per directory per process lifetime.
  private final Set<String> warnedLeftoverVariantDirs = ConcurrentHashMap.newKeySet();

  // Effective VRAM flags from last self-test (for status exposure)
  private volatile List<String> lastSelfTestEffectiveFlags = List.of();

  public RuntimeActivationService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      GpuCapabilitiesService gpuCapabilitiesService,
      EnterprisePolicyService policyService) {
    this(onlineAi, settingsStore, gpuCapabilitiesService, policyService, null, null);
  }

  public RuntimeActivationService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      GpuCapabilitiesService gpuCapabilitiesService,
      EnterprisePolicyService policyService,
      WorkerFeatureCache workerFeatureCache) {
    this(onlineAi, settingsStore, gpuCapabilitiesService, policyService, workerFeatureCache, null);
  }

  /**
   * Tempdoc 656 Task 2: {@code inferenceCapability} lets this service's already-precise failure
   * detection (see {@link #fail}) reach {@link InferenceCapability#pendingReason()} — and therefore
   * the runtime manifest's {@code ai.pendingReason} — instead of staying scoped to the immediate
   * {@code ai_activate} RPC response. Nullable for graceful degradation and existing test
   * compatibility, matching {@code workerFeatureCache}.
   */
  public RuntimeActivationService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      GpuCapabilitiesService gpuCapabilitiesService,
      EnterprisePolicyService policyService,
      WorkerFeatureCache workerFeatureCache,
      InferenceCapability inferenceCapability) {
    this(
        onlineAi,
        settingsStore,
        gpuCapabilitiesService,
        policyService,
        workerFeatureCache,
        inferenceCapability,
        null);
  }

  /**
   * Tempdoc 727 F-3: {@code aiInstallService} lets {@link #listInstalledVariants} distinguish a
   * variant directory that is a genuine leftover from a prior build from one that is the
   * currently-running Install AI flow's own in-flight download (the flow extracts the cuda-runtime
   * package into {@code variants/cuda12} before {@code llama-server.exe} is fully staged). Nullable
   * for graceful degradation and existing test compatibility, matching {@code workerFeatureCache}.
   */
  public RuntimeActivationService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      GpuCapabilitiesService gpuCapabilitiesService,
      EnterprisePolicyService policyService,
      WorkerFeatureCache workerFeatureCache,
      InferenceCapability inferenceCapability,
      AiInstallService aiInstallService) {
    this(
        onlineAi,
        settingsStore,
        gpuCapabilitiesService,
        policyService,
        workerFeatureCache,
        inferenceCapability,
        aiInstallService,
        null);
  }

  /**
   * Tempdoc 737 fix pack (fix 2): adds the nullable {@link RuntimeReconciler} so {@link
   * #runActivate} can bracket the engine-online + intent-write window in an {@code ACTIVATION}
   * procedure and nudge {@code specChanged()}. Nullable for graceful degradation / existing test
   * compatibility, matching {@code workerFeatureCache}/{@code inferenceCapability}.
   */
  public RuntimeActivationService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      GpuCapabilitiesService gpuCapabilitiesService,
      EnterprisePolicyService policyService,
      WorkerFeatureCache workerFeatureCache,
      InferenceCapability inferenceCapability,
      AiInstallService aiInstallService,
      RuntimeReconciler runtimeReconciler) {
    this.onlineAi = Objects.requireNonNull(onlineAi, "onlineAi");
    this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
    this.gpuCapabilitiesService = gpuCapabilitiesService == null ? new GpuCapabilitiesService() : gpuCapabilitiesService;
    this.policyService = policyService; // may be null (best-effort)
    this.workerFeatureCache = workerFeatureCache; // may be null (graceful degradation)
    this.inferenceCapability = inferenceCapability; // may be null (graceful degradation)
    this.aiInstallService = aiInstallService; // may be null (graceful degradation)
    this.runtimeReconciler = runtimeReconciler; // may be null (graceful degradation)
    this.aiHome = resolveAiHome();
    this.statusPath = aiHome.resolve("ai").resolve(STATUS_FILE);
    loadStatusBestEffort();
  }

  public AiRuntimeActivationStatus getActivationStatus() {
    synchronized (lock) {
      return copyStatus(status);
    }
  }

  public AiRuntimeStatusResponse getStatus() {
    AiRuntimeActivationStatus activation = getActivationStatus();
    List<AiRuntimeStatusResponse.InstalledVariant> installed = listInstalledVariants();

    UiSettings s = settingsStore.load();
    String activeExe = System.getProperty(SERVER_EXE_SYS_PROP, "");
    if (activeExe == null || activeExe.isBlank()) {
      activeExe = s.getServerExecutablePath();
    }
    String activeVariantId = resolveVariantIdFromExePath(activeExe);
    // Tempdoc 374 alpha.14 fix P1-B: read gpu_layers from the resolved config
    // (which integrates auto-populate at ord 150 + env vars at 400 + sysprops
    // at 500) rather than UiSettings (which defaults 0 and only reflects
    // explicit user input). Pre-alpha.14 this endpoint reported `gpuLayers: 0`
    // even when llama-server was actually launched with `-ngl 99` from the
    // resolved config — the UiSettings field is the "explicit override slot",
    // not the running value. Falls back to UiSettings only when ConfigStore
    // is absent (shouldn't happen post-boot, but defensive).
    Integer gpuLayers;
    var configStore = ConfigStore.globalOrNull();
    var resolvedConfig = configStore != null ? configStore.get() : null;
    if (resolvedConfig != null && resolvedConfig.ai() != null) {
      gpuLayers = resolvedConfig.ai().gpuLayers();
    } else {
      gpuLayers = s.getGpuLayers();
    }

    // Capture VRAM detection info for debugging
    GpuCapabilities gpuSnap = gpuCapabilitiesService.snapshot();
    String vramSource = gpuSnap != null ? gpuSnap.effective().source() : "none";
    String vramTier = VramFlagsUtil.detectVramTier(gpuSnap != null ? gpuSnap.effective().totalVramBytes() : null);
    Long vramTotal = gpuSnap != null ? gpuSnap.effective().totalVramBytes() : null;
    Long vramFree = gpuSnap != null ? gpuSnap.effective().freeVramBytes() : null;
    List<String> effectiveFlags = lastSelfTestEffectiveFlags;

    // Tempdoc 842 §2.5: the realized axis beside the requested one. Read once so the three fields
    // describe ONE engine snapshot — reading per-field could report a profile from before a swap
    // next to a model path from after it.
    RealizedChatIdentity realizedChat = realizedChatIdentity();

    return new AiRuntimeStatusResponse(
        activation,
        installed,
        new AiRuntimeStatusResponse.ActiveRuntime(
            activeExe == null ? "" : activeExe,
            activeVariantId,
            gpuLayers,
            vramSource,
            vramTier,
            effectiveFlags,
            vramTotal,
            vramFree,
            realizedChat == null ? null : realizedChat.profileId(),
            realizedChat == null ? null : realizedChat.modelPath(),
            realizedChat == null ? null : Boolean.valueOf(realizedChat.mmprojActive())),
        resolveOnnxFeatures());
  }

  // --------------- ONNX feature status ---------------

  /**
   * What the runtime observes about each registry package's capability, keyed by the install
   * package id — {@code "active"} | {@code "inactive"} | {@code "unknown"} (tempdoc 824 §3.3c).
   *
   * <p>A PROJECTION of {@link #resolveOnnxFeatureRows()}, the same derivation {@code GET
   * /api/ai/runtime/status} publishes, so {@code GET /api/ai/install/status} cannot disagree with it
   * about whether SPLADE is running. The package id comes off {@link EncoderRole#packageId()}
   * rather than a second id→package table, so adding an encoder cannot leave the two out of step.
   *
   * <p>Tri-state on purpose: a feature the Worker has not answered for is {@code "unknown"}, never
   * {@code "inactive"}. The install surface treats anything but {@code "active"} as "no observation
   * softens the missing-file verdict", so an unknown fails CLOSED.
   */
  public Map<String, String> functionalStatusByPackage() {
    Map<String, String> byPackage = new LinkedHashMap<>();
    for (FeatureRow row : resolveOnnxFeatureRows()) {
      String packageId = row.role().packageId();
      String verdict = functionalVerdict(row.status());
      // Two roles can share a package (EMBEDDING / BGE_M3): an active one wins, because the
      // capability IS observably running whichever role delivered it.
      if (!"active".equals(byPackage.get(packageId))) {
        byPackage.put(packageId, verdict);
      }
    }
    return byPackage;
  }

  private static String functionalVerdict(AiRuntimeStatusResponse.OnnxFeatureStatus f) {
    if ("unknown".equals(f.status())) {
      return "unknown";
    }
    return "active".equals(f.status()) && f.modelActive() ? "active" : "inactive";
  }

  /** One resolved ONNX feature together with the encoder role that produced it. */
  private record FeatureRow(EncoderRole role, AiRuntimeStatusResponse.OnnxFeatureStatus status) {}

  private List<AiRuntimeStatusResponse.OnnxFeatureStatus> resolveOnnxFeatures() {
    return resolveOnnxFeatureRows().stream().map(FeatureRow::status).toList();
  }

  private List<FeatureRow> resolveOnnxFeatureRows() {
    return List.of(
        new FeatureRow(
            EncoderRole.RERANKER,
            resolveOneOnnxFeature(
                "reranker",
                "Search reranking",
                EnvRegistry.RERANK_ENABLED.envVar(),
                EnvRegistry.RERANK_ENABLED.sysProp(),
                EnvRegistry.RERANK_MODEL_PATH.envVar(),
                EnvRegistry.RERANK_MODEL_PATH.sysProp(),
                EncoderRole.RERANKER)),
        new FeatureRow(
            EncoderRole.CITATION,
            resolveOneOnnxFeature(
                "citation_scorer",
                "Citation scoring",
                EnvRegistry.CITATION_SCORER_ENABLED.envVar(),
                EnvRegistry.CITATION_SCORER_ENABLED.sysProp(),
                EnvRegistry.CITATION_SCORER_MODEL_PATH.envVar(),
                EnvRegistry.CITATION_SCORER_MODEL_PATH.sysProp(),
                EncoderRole.CITATION)),
        new FeatureRow(
            EncoderRole.EMBEDDING,
            resolveWorkerEncoderFeature("embed", "Semantic embedding", EncoderRole.EMBEDDING)),
        new FeatureRow(
            EncoderRole.SPLADE,
            resolveWorkerEncoderFeature("splade", "Sparse expansion (SPLADE)", EncoderRole.SPLADE)));
  }

  /**
   * Tempdoc 806 B.2: the observed-EP row for a Worker-owned always-on encoder (embedding, SPLADE).
   *
   * <p>These two fell back to CPU in round 11 exactly like the reranker did, but they could not be
   * reported: {@link #resolveOneOnnxFeature} derives its INTENT axis from two sources neither of
   * them has. There is no Head-side enabled/path env pair for them (the SPLADE levers in {@code
   * EnvRegistry} are resolved in the Worker process, not here), and {@code
   * WorkerModelDiscovery.discoverAll()} enumerates only {@code reranker} and {@code
   * citation-scorer}, so {@code workerFeatureCache} is structurally blind to them — a
   * discovery-derived row would report a permanent {@code not_found}.
   *
   * <p>So intent and observation come from the SAME authority here: the Worker's policy snapshot as
   * derived by {@link EncoderRuntimeExplainer}. A role the snapshot names is active in the running
   * configuration; a role it names as {@code unavailable} is not; no snapshot at all is {@code
   * unknown} — never a positive claim in either direction.
   */
  private AiRuntimeStatusResponse.OnnxFeatureStatus resolveWorkerEncoderFeature(
      String id, String label, EncoderRole role) {
    EncoderRuntimeView view = lookupEncoderRuntime(role);
    EncoderRuntimeExplainer.ObservedExecutionProvider observed =
        EncoderRuntimeExplainer.observed(view);
    if (view == null) {
      return onnxFeature(id, label, "unknown", "worker_not_answered", null, false, observed);
    }
    if (EncoderRuntimeExplainer.ACCEL_UNAVAILABLE.equals(view.currentAccelerator())) {
      return onnxFeature(id, label, "inactive", "not_configured", null, false, observed);
    }
    return onnxFeature(id, label, "active", "worker_policy_snapshot", null, true, observed);
  }

  private AiRuntimeStatusResponse.OnnxFeatureStatus resolveOneOnnxFeature(
      String id,
      String label,
      String enabledEnv,
      String enabledProp,
      String pathEnv,
      String pathProp,
      EncoderRole role) {
    // The Worker's model name for this feature IS the registry package id carried by the role —
    // one identity, not a second hardcoded pair (EncoderRole.packageId).
    String modelName = role.packageId();
    // Look up runtime session state from Worker's health check cache (368 RC3).
    // This is the canonical source of truth for "is this model actually working."
    boolean sessionActive = resolveSessionActive(modelName);
    // Tempdoc 805 G.3: what the ORT session actually runs on. `sessionActive` is TRUE for a
    // CPU-fallback session, so it cannot express the round-11 outcome by itself.
    EncoderRuntimeExplainer.ObservedExecutionProvider observed = resolveObservedEp(role);

    // 1. Check if explicitly disabled (Head-owned: uses Head-side env vars)
    String enabledStr = resolveEnvOrProp(enabledEnv, enabledProp);
    if ("false".equalsIgnoreCase(enabledStr)) {
      return onnxFeature(id, label, "inactive", "disabled", null, sessionActive, observed);
    }

    // 2. Explicit model path (Head-owned: uses Head-side env vars)
    String explicitPath = resolveEnvOrProp(pathEnv, pathProp);
    if (explicitPath != null && !explicitPath.isBlank()) {
      return onnxFeature(id, label, "active", "explicit_path", explicitPath, sessionActive, observed);
    }

    // 3. Worker-reported discovery (includes both auto-discovery and explicit-path results)
    if (workerFeatureCache != null) {
      for (OnnxModelStatus status : workerFeatureCache.getOnnxModels()) {
        if (modelName.equals(status.modelName()) && status.found()) {
          return onnxFeature(
              id, label, "active", "auto_discovered", status.path(), sessionActive, observed);
        }
      }
    }

    // 4. Not found
    return onnxFeature(id, label, "inactive", "not_found", null, sessionActive, observed);
  }

  private static AiRuntimeStatusResponse.OnnxFeatureStatus onnxFeature(
      String id,
      String label,
      String status,
      String reason,
      String modelPath,
      boolean sessionActive,
      EncoderRuntimeExplainer.ObservedExecutionProvider observed) {
    return new AiRuntimeStatusResponse.OnnxFeatureStatus(
        id,
        label,
        status,
        reason,
        modelPath,
        sessionActive,
        observed.executionProvider(),
        observed.gpuFallback(),
        observed.fallbackReason());
  }

  /**
   * The observed execution provider for one encoder role, projected from {@link
   * EncoderRuntimeExplainer} — the same policy-snapshot × OrtCuda-probe derivation that backs {@code
   * GET /api/inference/encoders}. Degrades to {@code unknown} (never to a positive claim) when the
   * Worker has not answered yet.
   */
  private EncoderRuntimeExplainer.ObservedExecutionProvider resolveObservedEp(EncoderRole role) {
    return EncoderRuntimeExplainer.observed(lookupEncoderRuntime(role));
  }

  /** Last-known runtime view for one role; {@code null} when the Worker has not answered. */
  private EncoderRuntimeView lookupEncoderRuntime(EncoderRole role) {
    EncoderRuntimeCache cache = this.encoderRuntimeCache;
    if (cache == null) {
      return null;
    }
    try {
      return cache.encoderRuntime().get(role);
    } catch (RuntimeException e) {
      log.debug("Observed EP resolve failed for {} (best-effort): {}", role, e.toString());
      return null;
    }
  }

  /** Returns true if the Worker reports an active ORT session for this model. */
  private boolean resolveSessionActive(String modelName) {
    if (workerFeatureCache == null) {
      return false;
    }
    for (OnnxModelStatus status : workerFeatureCache.getOnnxModels()) {
      if (modelName.equals(status.modelName())) {
        return status.sessionActive();
      }
    }
    return false;
  }

  /** Resolves a value from system property first, then environment variable. */
  private static String resolveEnvOrProp(String envVar, String sysProp) {
    String val = System.getProperty(sysProp);
    if (val != null && !val.isBlank()) {
      return val;
    }
    val = System.getenv(envVar);
    if (val != null && !val.isBlank()) {
      return val;
    }
    return null;
  }

  @Override
  public void startActivate(String variantId) {
    startActivate(variantId, null);
  }

  /**
   * Tempdoc 842 §2.4: activation is when llama-server spawns, so it is the natural switch point for
   * the chat-model profile.
   *
   * @param variantId the GPU runtime variant to activate (unchanged semantics)
   * @param chatProfile optional {@code ChatModelProfile} id ({@code "standard"} | {@code
   *     "compact"} | ...). A null/blank value means "do not touch the chat model" and the flow is
   *     byte-for-byte the pre-842 one — every existing caller keeps its exact behavior.
   */
  public void startActivate(String variantId, String chatProfile) {
    String v = variantId == null ? "" : variantId.trim();
    if (v.isBlank()) {
      throw new IllegalArgumentException("variantId is required");
    }
    String profileRaw = chatProfile == null || chatProfile.isBlank() ? null : chatProfile.trim();
    synchronized (lock) {
      if (running.get()) {
        throw new IllegalStateException("Runtime activation already running");
      }
      running.set(true);
      status.startedAtEpochMs = System.currentTimeMillis();
      updateState("running", "validate", "Starting runtime activation…", null);
      status.variantId = v;
      status.result = "";
      status.vramUsedBeforeBytes = null;
      status.vramUsedAfterBytes = null;
      status.vramUsedDeltaBytes = null;
      status.selfTestPort = null;
      touch();
    }
    startLeasedThread(
        "ai.runtime-activate", "ai-runtime-activate", () -> runActivate(v, profileRaw));
  }

  public void startDeactivate() {
    synchronized (lock) {
      if (running.get()) {
        throw new IllegalStateException("Runtime activation already running");
      }
      running.set(true);
      status.startedAtEpochMs = System.currentTimeMillis();
      updateState("running", "apply", "Deactivating GPU runtime…", null);
      status.variantId = "";
      status.result = "";
      touch();
    }
    startLeasedThread("ai.runtime-deactivate", "ai-runtime-deactivate", this::runDeactivate);
  }

  // -------------------- Implementation --------------------

  /**
   * Tempdoc 737 (task 3): the ONE authoritative admin-policy check for runtime activation.
   * {@link #runActivate} (this class's async path), {@code AiRuntimeController.handleActivate}
   * (HTTP fast-fail), and {@code RuntimeVariantServiceImpl.activate} (operation-handler
   * fast-fail) all call this — no independent copies of the {@code onlineAiEnabled} / {@code
   * gpuAccelerationEnabled} predicate exist elsewhere. A no-op when no policy is configured.
   *
   * @throws IllegalStateException with the canonical policy-denial message when blocked; callers
   *     needing a machine code derive it from the message text (the convention already used by
   *     {@code ActivateRuntimeVariantHandler}).
   */
  @Override
  public void enforceActivationPolicy() {
    EffectivePolicy effective;
    try {
      effective = policyService != null ? policyService.snapshot() : null;
    } catch (Exception ignored) {
      effective = null;
    }
    if (effective == null) {
      return;
    }
    // v3 enforcement: block activation when Online AI or GPU acceleration is disabled by policy.
    if (!effective.onlineAiEnabled()) {
      throw new IllegalStateException("Online AI is disabled by administrator policy.");
    }
    if (!effective.gpuAccelerationEnabled()) {
      throw new IllegalStateException("GPU acceleration is disabled by administrator policy.");
    }
    // snapshot() already bridged policy sysprops to app-services enforcement points.
  }

  private void runActivate(String variantId, String chatProfileRaw) {
    try {
      enforceActivationPolicy();
    } catch (IllegalStateException e) {
      String msg = e.getMessage() == null ? "" : e.getMessage();
      String code = msg.contains("GPU acceleration") ? "POLICY_GPU_DISABLED" : "POLICY_ONLINE_AI_DISABLED";
      fail(code, msg, null);
      return;
    }

    Path root = variantsRoot();
    Path exe = root.resolve(variantId).resolve("llama-server.exe");
    if (!Files.isRegularFile(exe)) {
      // G17: "default" variant may be the baseline exe flat in native-bin/llama-server/
      // (not under variants/). Fall back to it so fresh installs can activate.
      if ("default".equals(variantId)) {
        Path baseline = root.getParent().resolve("llama-server.exe");
        if (Files.isRegularFile(baseline)) {
          log.info("Using baseline exe as default variant: {}", baseline);
          exe = baseline;
        }
      }
      if (!Files.isRegularFile(exe)) {
        fail("RUNTIME_VARIANT_NOT_INSTALLED", "Variant not installed: " + variantId, null);
        return;
      }
    }

    UiSettings current = settingsStore.load();

    // Tempdoc 842 §2.4: a named profile selects the (model, mmproj) pair as one unit. It is
    // resolved BEFORE the settings/contract chain and short-circuits it — a stored llmModelPath is
    // a system-owned, re-derivable copy of the standard model on every installed and dev data dir,
    // so consulting it here would make every profile switch silently inert (§2.3 precedence rule).
    ChatModelProfile profile = chatProfileRaw == null ? null : ChatModelProfile.resolve(chatProfileRaw);
    Path model;
    boolean modelPathFromContract = false;
    if (profile != null) {
      Path resolved = resolveProfileModelPath(profile);
      if (!Files.isRegularFile(resolved)) {
        fail("MODEL_NOT_FOUND", missingProfileModelMessage(profile, resolved), null);
        return;
      }
      model = resolved;
    } else {
      String modelPath = current.getLlmModelPath();
      if (modelPath == null || modelPath.isBlank()) {
        modelPath = resolveChatModelFromInstallContract();
        modelPathFromContract = modelPath != null && !modelPath.isBlank();
      }
      if (modelPath == null || modelPath.isBlank()) {
        fail(
            "MODEL_PATH_REQUIRED",
            "No chat model configured. Run Install AI to download one, or import a models pack.",
            null);
        return;
      }
      model = Path.of(modelPath.trim());
      if (!Files.isRegularFile(model)) {
        fail("MODEL_NOT_FOUND", "Configured model does not exist: " + model, null);
        return;
      }
    }

    updateState("running", "self_test", "Running GPU self-test…", null);
    SelfTestResult selfTest = runSelfTest(exe, model, current);
    if (selfTest == null) {
      fail("SELF_TEST_FAILED", "Self-test failed.", null);
      return;
    }

    synchronized (lock) {
      status.selfTestPort = selfTest.port == null ? null : selfTest.port.longValue();
      status.vramUsedBeforeBytes = selfTest.vramBefore;
      status.vramUsedAfterBytes = selfTest.vramAfter;
      status.vramUsedDeltaBytes = selfTest.delta;
      status.result = selfTest.result;
      touch();
    }

    if (!"passed".equalsIgnoreCase(selfTest.result)) {
      // Do not activate on failed/inconclusive.
      String msg =
          "inconclusive".equalsIgnoreCase(selfTest.result)
              ? "GPU self-test inconclusive; runtime pack installed but NOT activated."
              : "GPU self-test failed; runtime pack installed but NOT activated.";
      updateState("completed", "done", msg, null);
      return;
    }

    updateState("running", "apply", "Activating runtime variant…", null);

    // Capture previous state for rollback.
    UiSettings prevSettings = settingsStore.load();
    String prevSys = System.getProperty(SERVER_EXE_SYS_PROP, "");
    String prevSysSource = System.getProperty(SERVER_EXE_SOURCE_PROP, "");
    // Tempdoc 842: the profile sysprop joins the rollback bracket. It is captured as null-vs-value
    // (not "" for absent) because clearing it and setting it to "" are different states to
    // InferenceConfig: absent falls back to the STANDARD default, blank would too, but a rollback
    // must restore *absence* rather than invent a blank claim.
    String prevChatProfileProp = System.getProperty(CHAT_PROFILE_SYS_PROP);

    try {
      // Persist settings (so activation survives restart) AND apply sysprop (so reload works immediately).
      UiSettings next = settingsStore.load();
      next.setServerExecutablePath(exe.toAbsolutePath().toString());
      if (next.getGpuLayers() <= 0) {
        next.setGpuLayers(99);
      }
      // The engine is started from settings (applyRuntimeOverridesBestEffort reads
      // next.getLlmModelPath()), so a model path recovered from the install contract has to land in
      // settings or the activation would bring the engine up with no model.
      //
      // Tempdoc 842 §2.3: a PROFILE activation deliberately does NOT write llmModelPath. A profile
      // choice is a claim about which bundle to resolve, not a stored user path; persisting the
      // resolved file here would turn one session's dev profile into a permanent operator-looking
      // setting that outlives it. Boot-time resolution (InferenceConfig + the chat-profile key)
      // owns persistence semantics for profiles.
      if (modelPathFromContract) {
        next.setLlmModelPath(model.toAbsolutePath().toString());
      }
      settingsStore.save(next);

      if (!applyServerExeSysProp(exe.toAbsolutePath().toString())) {
        throw new IllegalStateException("Server executable override is locked by operator config");
      }

      // Tempdoc 737 fix pack (fix 2): bracket the engine-online + intent-write window in an
      // ACTIVATION procedure. applyRuntimeOverrides(RESTART_ALWAYS) brings the engine ONLINE (its
      // mode listener fires) BEFORE recordUserEnabled persists the intent; without the bracket the
      // reconciler would see mode-up with spec still false and drift-converge the engine straight
      // back DOWN. The procedure suppresses that drift; recordUserEnabled writes the intent;
      // specChanged() nudges; endProcedure returns to the now-true spec — deterministically online,
      // no spurious down/up flicker.
      boolean activationProcedureBegun = false;
      if (runtimeReconciler != null) {
        runtimeReconciler.beginProcedure(
            RuntimeStatus.ProcedureKind.ACTIVATION, "runtime-variant-activation");
        activationProcedureBegun = true;
      }
      try {
        if (profile != null) {
          // Publish the selection BEFORE the apply: applyChatProfile restarts the engine, and any
          // config rebuild racing that restart in this JVM must already agree on the profile.
          System.setProperty(CHAT_PROFILE_SYS_PROP, profile.id());
          applyChatProfileOrThrow(profile);
        } else {
          applyRuntimeOverridesBestEffort(next);
        }

        // Rebuild ConfigStore so readers see updated server EXE / GPU layers.
        ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), next);

        // Tempdoc 737 Phase 1: a user who successfully activated a GPU runtime wants AI on across
        // restarts — persist the desired-state so the reconciler brings it back at boot (fixes the
        // documented "AI offline after reopen" confusion). Null-safe; idempotent.
        if (settingsStore != null) {
          new RuntimeSpecStore(settingsStore).recordUserEnabled();
        }
        // Nudge the reconciler so the persisted intent is honored via specChanged (an explicit
        // convergence), not only via the racy mode-drift event. Deferred while the procedure is
        // active; applied at endProcedure below.
        if (runtimeReconciler != null) {
          runtimeReconciler.specChanged();
        }
      } finally {
        if (activationProcedureBegun) {
          runtimeReconciler.endProcedure(RuntimeStatus.ProcedureKind.ACTIVATION);
        }
      }

      updateState("completed", "done", "GPU runtime activated.", null);
    } catch (Exception e) {
      log.warn("Runtime activation failed; attempting rollback", e);
      updateState("running", "rollback", "Activation failed; rolling back…", null);
      restoreChatProfileProp(prevChatProfileProp);
      boolean rolledBack = rollback(prevSettings, prevSys, prevSysSource);
      if (!rolledBack) {
        fail("RUNTIME_ROLLBACK_FAILED", "Rollback failed after activation error: " + safeMsg(e), e);
        return;
      }
      fail("RUNTIME_ACTIVATION_FAILED", "Activation failed: " + safeMsg(e), e);
    }
  }

  private void runDeactivate() {
    // Best-effort: choose CPU baseline from native-bin/llama-server (excluding variants/).
    Path baselineExe = resolveCpuBaselineExe(aiHome);
    if (baselineExe == null || !Files.isRegularFile(baselineExe)) {
      fail("RUNTIME_BASELINE_NOT_FOUND", "CPU baseline llama-server.exe not found.", null);
      return;
    }

    UiSettings prevSettings = settingsStore.load();
    String prevSys = System.getProperty(SERVER_EXE_SYS_PROP, "");
    String prevSysSource = System.getProperty(SERVER_EXE_SOURCE_PROP, "");

    try {
      UiSettings next = settingsStore.load();
      next.setServerExecutablePath(""); // revert to default discovery on restart
      next.setGpuLayers(0);
      settingsStore.save(next);

      // Force immediate switch to baseline for this process.
      forceServerExeSysProp(baselineExe.toAbsolutePath().toString());
      applyRuntimeOverridesBestEffort(next);

      // Rebuild ConfigStore so readers see reverted server EXE / GPU layers.
      ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), next);

      updateState("completed", "done", "GPU runtime deactivated (CPU baseline).", null);
    } catch (Exception e) {
      log.warn("Runtime deactivation failed; attempting rollback", e);
      updateState("running", "rollback", "Deactivation failed; rolling back…", null);
      boolean rolledBack = rollback(prevSettings, prevSys, prevSysSource);
      if (!rolledBack) {
        fail("RUNTIME_ROLLBACK_FAILED", "Rollback failed after deactivation error: " + safeMsg(e), e);
        return;
      }
      fail("RUNTIME_DEACTIVATION_FAILED", "Deactivation failed: " + safeMsg(e), e);
    }
  }

  private boolean rollback(UiSettings prevSettings, String prevSys, String prevSysSource) {
    try {
      if (prevSettings != null) {
        settingsStore.save(prevSettings);
      }

      // Restore sysprop if it was previously set; otherwise force baseline.
      if (prevSys != null && !prevSys.isBlank()) {
        System.setProperty(SERVER_EXE_SYS_PROP, prevSys);
        if (prevSysSource != null && !prevSysSource.isBlank()) {
          System.setProperty(SERVER_EXE_SOURCE_PROP, prevSysSource);
        } else {
          System.clearProperty(SERVER_EXE_SOURCE_PROP);
        }
      } else {
        Path baselineExe = resolveCpuBaselineExe(aiHome);
        if (baselineExe != null && Files.isRegularFile(baselineExe)) {
          forceServerExeSysProp(baselineExe.toAbsolutePath().toString());
        } else {
          System.clearProperty(SERVER_EXE_SYS_PROP);
          System.clearProperty(SERVER_EXE_SOURCE_PROP);
        }
      }

      applyRuntimeOverridesBestEffort(prevSettings);

      // Rebuild ConfigStore so readers see restored sysprops.
      ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), prevSettings);

      return true;
    } catch (Exception e) {
      log.warn("Rollback failed", e);
      return false;
    }
  }

  /**
   * Resolves a profile's model file against the CONFIGURED models directory (tempdoc 842 §2.3).
   *
   * <p>Mirrors {@code OnlineAiServiceImpl.applyChatProfile} deliberately: a profile's
   * {@code modelFile()} is a registry {@code targetDir}-relative name, meaningful only under the
   * models root. Resolving it against the currently-running model's parent (an arbitrary directory
   * that happens to hold one GGUF when an operator named a path) would point the existence check at
   * a file that was never installed there — and the self-test would then validate a different file
   * than the engine loads.
   */
  private static Path resolveProfileModelPath(ChatModelProfile profile) {
    return resolveModelsDir().resolve(profile.modelFile());
  }

  /** The configured models directory, or the process working dir when no config store exists. */
  private static Path resolveModelsDir() {
    ConfigStore cs = ConfigStore.globalOrNull();
    if (cs == null) {
      return Path.of(".");
    }
    var rc = cs.get();
    Path baseDir = ResolvedPathResolver.resolveBaseDir(rc, System.getProperty("user.dir"));
    return ResolvedPathResolver.resolveModelsDir(rc, baseDir);
  }

  /**
   * Failure text for a profile whose model file is not on disk.
   *
   * <p>Names a remedy that exists (the tempdoc 804 §B2 rule): the compact bundle is a dev-only
   * package excluded from user install plans, so "Run Install AI" would send the reader somewhere
   * that will never fetch it. The fetch script is the only thing that puts that file on disk.
   */
  private static String missingProfileModelMessage(ChatModelProfile profile, Path resolved) {
    String remedy =
        profile == ChatModelProfile.COMPACT
            ? " Run `node scripts/dev/fetch-compact-model.mjs` to download it."
            : " Run Install AI to download it.";
    return "Chat profile '" + profile.id() + "' model does not exist: " + resolved + "." + remedy;
  }

  /**
   * Applies a profile as one atomic (model, mmproj, profile-id) unit.
   *
   * <p>Deliberately NOT {@link #applyRuntimeOverridesBestEffort}: that routes through the bare-path
   * {@code applyRuntimeOverrides}, which clears the profile claim and nulls the projector — the
   * exact defect (dev stacks running silently text-only) tempdoc 842 §2.3 exists to fix. A control
   * surface that cannot apply pairs throws {@link UnsupportedOperationException} rather than
   * half-applying, and that propagates into the rollback bracket.
   */
  private void applyChatProfileOrThrow(ChatModelProfile profile) {
    OnlineAiService onlineAi = this.onlineAi;
    if (!(onlineAi instanceof OnlineAiRuntimeControl control)) {
      // Same graceful degradation as applyRuntimeOverridesBestEffort: no control surface means
      // there is no engine to switch, and activation of the GPU variant still succeeded.
      return;
    }
    try {
      control.applyChatProfile(profile, OnlineAiRuntimeControl.RestartPolicy.RESTART_ALWAYS);
    } catch (Exception e) {
      throw new RuntimeException("Failed to apply chat profile '" + profile.id() + "'", e);
    }
  }

  /**
   * Restores the chat-profile sysprop to its pre-activation state. Absent stays absent: a rollback
   * that wrote "" would leave a blank claim behind that no writer ever creates.
   */
  private static void restoreChatProfileProp(String previous) {
    if (previous == null) {
      System.clearProperty(CHAT_PROFILE_SYS_PROP);
    } else {
      System.setProperty(CHAT_PROFILE_SYS_PROP, previous);
    }
  }

  private void applyRuntimeOverridesBestEffort(UiSettings settings) {
    OnlineAiService onlineAi = this.onlineAi;
    if (!(onlineAi instanceof OnlineAiRuntimeControl control)) {
      return;
    }
    try {
      control.applyRuntimeOverrides(
          settings == null ? null : settings.getLlmModelPath(),
          settings == null ? null : settings.getContextLength(),
          settings == null ? null : settings.getGpuLayers(),
          OnlineAiRuntimeControl.RestartPolicy.RESTART_ALWAYS);
    } catch (Exception e) {
      throw new RuntimeException("Failed to apply runtime overrides", e);
    }
  }

  private boolean applyServerExeSysProp(String exePath) {
    String source = System.getProperty(SERVER_EXE_SOURCE_PROP, "");
    String existing = System.getProperty(SERVER_EXE_SYS_PROP, "");
    // Tempdoc 374 alpha.16 fix A: treat both ui_settings and auto_selected_cuda12 as
    // system-owned so the activation flow can overwrite them. The pre-alpha.16 check only
    // matched ui_settings, so HeadlessApp's boot-time auto-select (and AiInstallService's
    // applyCudaServerExe follow-up) registered as third-party operator locks and rejected
    // every POST /api/ai/runtime/activate even when the self-test passed.
    boolean owned =
        SOURCE_UI_SETTINGS.equalsIgnoreCase(source)
            || SOURCE_AUTO_SELECTED_CUDA12.equalsIgnoreCase(source);
    boolean unset = existing == null || existing.isBlank();
    if (!owned && !unset) {
      // Respect explicit operator overrides.
      return false;
    }
    forceServerExeSysProp(exePath);
    return true;
  }

  private static void forceServerExeSysProp(String exePath) {
    if (exePath == null || exePath.isBlank()) {
      System.clearProperty(SERVER_EXE_SYS_PROP);
      System.clearProperty(SERVER_EXE_SOURCE_PROP);
      return;
    }
    System.setProperty(SERVER_EXE_SYS_PROP, exePath.trim());
    System.setProperty(SERVER_EXE_SOURCE_PROP, SOURCE_UI_SETTINGS);
  }

  private SelfTestResult runSelfTest(Path exe, Path model, UiSettings settings) {
    var override = this.selfTestOverrideForTest;
    if (override != null) {
      return override.apply(exe, model);
    }
    // Require NVML for self-test gating (v3 safety posture).
    GpuCapabilities snap = gpuCapabilitiesService.snapshot();
    if (snap == null || snap.nvml() == null || !snap.nvml().available()) {
      return new SelfTestResult("inconclusive", null, null, null, null, List.of(), "unknown", "none");
    }

    // Capture VRAM tier and source for status exposure
    String vramTier = VramFlagsUtil.detectVramTier(snap.effective().totalVramBytes());
    String vramSource = snap.effective().source();

    Long beforeUsed = snap.nvml().usedVramBytes();

    int port = pickEphemeralPort();
    synchronized (lock) {
      status.selfTestPort = (long) port;
      touch();
    }

    Process proc = null;
    try {
      proc = startSelfTestServer(exe, model, port, settings);
      waitForHealth(proc, port);
      sendTinyChatRequest(port, model.getFileName().toString());

      // Give the runtime a moment to allocate any GPU buffers.
      try {
        Thread.sleep(250);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }

      GpuCapabilities afterSnap = gpuCapabilitiesService.snapshot();
      Long afterUsed = afterSnap != null && afterSnap.nvml() != null ? afterSnap.nvml().usedVramBytes() : null;
      Long delta = (beforeUsed != null && afterUsed != null) ? (afterUsed - beforeUsed) : null;

      // Capture effective flags from self-test server startup
      List<String> effectiveFlags = lastSelfTestEffectiveFlags;

      if (delta != null && delta >= MIN_VRAM_DELTA_BYTES) {
        return new SelfTestResult("passed", port, beforeUsed, afterUsed, delta, effectiveFlags, vramTier, vramSource);
      }
      return new SelfTestResult("inconclusive", port, beforeUsed, afterUsed, delta, effectiveFlags, vramTier, vramSource);
    } catch (SelfTestException e) {
      log.warn("Self-test failed", e);
      return new SelfTestResult("failed", port, beforeUsed, null, null, lastSelfTestEffectiveFlags, vramTier, vramSource);
    } catch (Exception e) {
      log.warn("Self-test failed", e);
      return new SelfTestResult("failed", port, beforeUsed, null, null, lastSelfTestEffectiveFlags, vramTier, vramSource);
    } finally {
      stopSelfTestServer(proc);
    }
  }

  private Process startSelfTestServer(Path exe, Path model, int port, UiSettings settings) throws IOException {
    List<String> command = new ArrayList<>();
    command.add(exe.toAbsolutePath().toString());
    command.add("-m");
    command.add(model.toAbsolutePath().toString());
    command.add("--host");
    command.add("127.0.0.1");
    command.add("--port");
    command.add(String.valueOf(port));

    // Tempdoc 883: this 4096 is a PROBE parameter, not the runtime window. The self-test measures a
    // VRAM delta across a model load; raising it to the derived ladder rung would change what is
    // measured and could fail the probe on small GPUs. It is also unchanged in behaviour by 883 —
    // before, an unset contextLength defaulted to 4096 in UiSettings; now it defaults to 0 = auto
    // and lands on this same fallback. The runtime window lives in ContextWindowPolicy.
    int ctx = (settings != null && settings.getContextLength() > 0) ? settings.getContextLength() : 4096;
    command.add("-c");
    command.add(String.valueOf(ctx));

    int gpuLayers = (settings != null && settings.getGpuLayers() > 0) ? settings.getGpuLayers() : 99;
    command.add("-ngl");
    command.add(String.valueOf(gpuLayers));

    // Add VRAM-based llama-server flags (e.g., KV cache quantization) only when GPU mode is requested.
    // Use VramFlagsUtil for shared flag merging logic.
    if (gpuLayers > 0) {
      // Tempdoc 374 alpha.27: NVML-first VRAM probe + threshold helpers in VramRequirements.
      // Pre-fix vramDetector.getRecommendedLlamaServerFlags() shelled out to nvidia-smi
      // (returning null on cuda12 sandbox hosts), making the self-test launch llama-server
      // without KV-cache flags even on 8GB cards that need them.
      gpuCapabilitiesService.invalidateNvidiaSmiCache();
      Long totalVramBytes =
          gpuCapabilitiesService.snapshot().effective().totalVramBytes();
      String[] recommendedFlags =
          io.justsearch.gpu.VramRequirements.recommendedLlamaServerFlags(totalVramBytes);
      List<String> addedFlags = VramFlagsUtil.mergeRecommendedFlags(command, recommendedFlags);
      // Store for self-test result (best-effort)
      this.lastSelfTestEffectiveFlags = List.copyOf(addedFlags);
    } else {
      this.lastSelfTestEffectiveFlags = List.of();
    }

    ProcessBuilder pb = new ProcessBuilder(command);
    Path exeDir = exe.getParent();
    if (exeDir != null && Files.isDirectory(exeDir)) {
      pb.directory(exeDir.toFile());
    }

    // Best-effort PATH adjustments (mirrors InferenceLifecycleManager behavior).
    try {
      Map<String, String> env = pb.environment();
      String pathKey = env.containsKey("Path") ? "Path" : (env.containsKey("PATH") ? "PATH" : "Path");
      String existingPath = env.getOrDefault(pathKey, "");
      List<String> prefixes = new ArrayList<>();
      if (exeDir != null) prefixes.add(exeDir.toAbsolutePath().normalize().toString());
      Path runtimeBin = resolveBundledRuntimeBinDirBestEffort();
      if (runtimeBin != null) prefixes.add(runtimeBin.toAbsolutePath().normalize().toString());
      if (!prefixes.isEmpty()) {
        String prepend = String.join(";", prefixes);
        String next =
            existingPath == null || existingPath.isBlank() ? prepend : (prepend + ";" + existingPath);
        env.put(pathKey, next);
      }
    } catch (Exception ignored) {
      // best-effort
    }

    // Persist logs for debugging failures.
    Path logFile = aiHome.resolve("logs").resolve("llama-server-selftest.log");
    try {
      Files.createDirectories(logFile.getParent());
    } catch (IOException e) {
      log.warn("Could not create log directory {}", logFile.getParent(), e);
    }
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

    log.info("Self-test starting llama-server: {}", String.join(" ", command));
    return pb.start();
  }

  private void waitForHealth(Process proc, int port) throws SelfTestException {
    long deadline = System.currentTimeMillis() + HEALTH_CHECK_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (proc != null && !proc.isAlive()) {
        int exitCode = -1;
        try {
          exitCode = proc.exitValue();
        } catch (Exception e) {
          log.debug("Failed to retrieve llama-server process exit code", e);
        }
        if (exitCode == WINDOWS_STATUS_DLL_NOT_FOUND) {
          throw new SelfTestException(
              "llama-server failed to start (missing DLL dependencies, 0xC0000135). See logs/llama-server-selftest.log");
        }
        throw new SelfTestException("llama-server exited before healthy (exit code " + exitCode + ")");
      }
      if (isHealthy(port)) {
        return;
      }
      try {
        Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SelfTestException("Health check interrupted");
      }
    }
    throw new SelfTestException("Server health check timeout after " + HEALTH_CHECK_TIMEOUT_MS + "ms");
  }

  private boolean isHealthy(int port) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/health"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();
      HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      return resp.statusCode() == 200;
    } catch (Exception e) {
      return false;
    }
  }

  private void sendTinyChatRequest(int port, String modelId) throws SelfTestException {
    try {
      List<Map<String, Object>> messages =
          List.of(Map.of("role", "user", "content", "Reply with the single word: ok"));
      Map<String, Object> body = new HashMap<>();
      body.put("model", modelId == null ? "" : modelId);
      body.put("messages", messages);
      body.put("max_tokens", 8);
      String json = MAPPER.writeValueAsString(body);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/v1/chat/completions"))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(20))
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();
      HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        throw new SelfTestException("Chat request failed: status=" + resp.statusCode());
      }
    } catch (SelfTestException e) {
      throw e;
    } catch (Exception e) {
      throw new SelfTestException("Chat request failed: " + safeMsg(e));
    }
  }

  private void stopSelfTestServer(Process proc) {
    if (proc == null) return;
    try {
      if (!proc.isAlive()) return;
      long pid = proc.pid();
      proc.destroy();
      boolean exited = false;
      try {
        exited = proc.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (!exited && isWindows()) {
        try {
          new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid)).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
          proc.destroyForcibly();
        }
      } else if (!exited) {
        proc.destroyForcibly();
      }
    } catch (Exception ignored) {
      // best-effort
    }
  }

  // -------------------- Status persistence --------------------

  private void updateState(String state, String phase, String message, String errorCode) {
    synchronized (lock) {
      status.state = safe(state);
      status.phase = safe(phase);
      status.message = safe(message);
      status.errorCode = safe(errorCode);
      if ("running".equalsIgnoreCase(state)) {
        if (status.startedAtEpochMs == 0) status.startedAtEpochMs = System.currentTimeMillis();
      }
      touch();
    }
  }

  private void fail(String errorCode, String message, Exception e) {
    if (e != null) {
      log.warn("Runtime activation failed: {} {}", errorCode, message, e);
    } else {
      log.warn("Runtime activation failed: {} {}", errorCode, message);
    }
    synchronized (lock) {
      status.state = "failed";
      status.phase = "done";
      status.message = safe(message);
      status.errorCode = safe(errorCode);
      status.updatedAtEpochMs = System.currentTimeMillis();
      touch();
    }
    reportToCapability(errorCode);
  }

  /**
   * Tempdoc 656 Task 2: project this failure onto {@link InferenceCapability} so the runtime
   * manifest's {@code ai.pendingReason} carries the same precise cause this class already computed,
   * instead of the generic default reason. Deliberately does NOT force the capability OFFLINE when
   * it is currently READY — {@code runActivate}/{@code runDeactivate} can be invoked while a
   * *different*, already-working variant is online (e.g. an attempted variant switch), and a naive
   * wire-through would incorrectly regress a working capability's reported state for an unrelated
   * attempt's failure.
   */
  private void reportToCapability(String errorCode) {
    if (inferenceCapability == null) {
      return;
    }
    if (inferenceCapability.health() == CapabilityHealth.READY) {
      return;
    }
    LifecycleReasonCode reason = mapToLifecycleReason(errorCode);
    inferenceCapability.transition(CapabilityHealth.OFFLINE, reason.code());
  }

  /** Tempdoc 656 Task 2: maps this service's existing activation error codes onto the closed,
   * cross-consumer {@link LifecycleReasonCode} taxonomy. Local to this one producer — the mapping
   * is not part of the public activation-status wire contract, which keeps using {@code errorCode}
   * unchanged. */
  private static LifecycleReasonCode mapToLifecycleReason(String errorCode) {
    return switch (errorCode) {
      case "MODEL_PATH_REQUIRED" -> LifecycleReasonCode.INFERENCE_MODEL_NOT_CONFIGURED;
      case "MODEL_NOT_FOUND" -> LifecycleReasonCode.INFERENCE_MODEL_NOT_FOUND;
      case "RUNTIME_VARIANT_NOT_INSTALLED", "RUNTIME_BASELINE_NOT_FOUND" ->
          LifecycleReasonCode.INFERENCE_RUNTIME_NOT_INSTALLED;
      case "POLICY_ONLINE_AI_DISABLED" -> LifecycleReasonCode.INFERENCE_POLICY_ONLINE_AI_DISABLED;
      case "POLICY_GPU_DISABLED" -> LifecycleReasonCode.INFERENCE_POLICY_GPU_DISABLED;
      default -> LifecycleReasonCode.INFERENCE_ACTIVATION_FAILED;
    };
  }

  private void touch() {
    status.updatedAtEpochMs = System.currentTimeMillis();
    saveStatusBestEffort();
  }

  private void saveStatusBestEffort() {
    try {
      AtomicFileWrites.replace(statusPath, MAPPER.writeValueAsBytes(status));
    } catch (Exception ignored) {
      // best-effort
    }
  }

  private void loadStatusBestEffort() {
    try {
      if (!Files.exists(statusPath)) return;
      AiRuntimeActivationStatus loaded = MAPPER.readValue(statusPath.toFile(), AiRuntimeActivationStatus.class);
      if (loaded == null) return;
      synchronized (lock) {
        status.state = safe(loaded.state);
        status.phase = safe(loaded.phase);
        status.message = safe(loaded.message);
        status.errorCode = safe(loaded.errorCode);
        status.variantId = safe(loaded.variantId);
        status.result = safe(loaded.result);
        status.vramUsedBeforeBytes = loaded.vramUsedBeforeBytes;
        status.vramUsedAfterBytes = loaded.vramUsedAfterBytes;
        status.vramUsedDeltaBytes = loaded.vramUsedDeltaBytes;
        status.selfTestPort = loaded.selfTestPort;
        status.startedAtEpochMs = loaded.startedAtEpochMs;
        status.updatedAtEpochMs = loaded.updatedAtEpochMs;

        // After a JVM restart, terminal activation states are stale — the self-test
        // server (ephemeral port) is dead and the apply-config was for the previous
        // lifecycle. Reset to idle so the next activate() re-runs the full flow.
        if (!status.state.isBlank() && !"idle".equalsIgnoreCase(status.state)) {
          status.state = "idle";
          status.phase = "";
          status.message = "";
          status.errorCode = "";
          status.selfTestPort = 0L;
          status.startedAtEpochMs = 0;
          status.updatedAtEpochMs = System.currentTimeMillis();
          saveStatusBestEffort();
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }
  }

  private static AiRuntimeActivationStatus copyStatus(AiRuntimeActivationStatus s) {
    AiRuntimeActivationStatus c = new AiRuntimeActivationStatus();
    c.state = safe(s.state);
    c.phase = safe(s.phase);
    c.message = safe(s.message);
    c.errorCode = safe(s.errorCode);
    c.variantId = safe(s.variantId);
    c.result = safe(s.result);
    c.vramUsedBeforeBytes = s.vramUsedBeforeBytes;
    c.vramUsedAfterBytes = s.vramUsedAfterBytes;
    c.vramUsedDeltaBytes = s.vramUsedDeltaBytes;
    c.selfTestPort = s.selfTestPort;
    c.startedAtEpochMs = s.startedAtEpochMs;
    c.updatedAtEpochMs = s.updatedAtEpochMs;
    return c;
  }

  // -------------------- Status helpers --------------------

  private List<AiRuntimeStatusResponse.InstalledVariant> listInstalledVariants() {
    List<AiRuntimeStatusResponse.InstalledVariant> out = new ArrayList<>();

    // G17: Include the baseline exe as a synthetic "default" variant if it exists
    // flat in native-bin/llama-server/ (not under variants/).
    Path root = variantsRoot();
    Path baselineExe = root.getParent().resolve("llama-server.exe");
    boolean hasDefaultVariantDir = Files.isDirectory(root.resolve("default"));
    if (Files.isRegularFile(baselineExe) && !hasDefaultVariantDir) {
      out.add(
          new AiRuntimeStatusResponse.InstalledVariant(
              "default", baselineExe.toAbsolutePath().toString()));
    }

    if (Files.isDirectory(root)) {
      try (var stream = Files.list(root)) {
        stream
            .filter(Files::isDirectory)
            .sorted(
                Comparator.comparing(
                    p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
            .forEach(
                dir -> {
                  Path exe = dir.resolve("llama-server.exe");
                  if (Files.isRegularFile(exe)) {
                    out.add(
                        new AiRuntimeStatusResponse.InstalledVariant(
                            dir.getFileName().toString(),
                            exe.toAbsolutePath().toString()));
                  } else if (!isLikelyInFlightInstall(dir)) {
                    // Tempdoc 374 sandbox round 2 finding #4.5: a variant dir
                    // without llama-server.exe means a prior install with the
                    // CUDA variant left DLLs behind, but the current build
                    // skipped staging the exe (e.g., -PincludeCuda=false).
                    // Log so drift is visible without polluting the API
                    // response with a non-executable path.
                    //
                    // Tempdoc 727 F-3: only log once per directory per process
                    // lifetime (see warnedLeftoverVariantDirs) — this method runs
                    // on every status poll (~1/sec from the FE), so an un-deduped
                    // WARN spams even for a genuine leftover directory.
                    if (warnedLeftoverVariantDirs.add(dir.toString())) {
                      log.warn(
                          "Variant directory present without llama-server.exe: {} (likely leftover from a previous build)",
                          dir);
                    }
                  }
                });
      } catch (Exception ignored) {
        // best-effort
      }
    }
    return out;
  }

  /**
   * Tempdoc 727 F-3: a variant directory that exists without {@code llama-server.exe} yet is not
   * necessarily stale — the Install AI flow creates {@code variants/&lt;id&gt;} and extracts
   * package contents into it (e.g. the {@code cuda-runtime} package into {@code variants/cuda12})
   * before the final executable is staged, so a fresh install's own in-flight download briefly
   * looks identical to a "leftover from a previous build" on disk.
   *
   * <p>Two independent signals suppress the false positive, either sufficient on its own:
   *
   * <ol>
   *   <li>The authoritative signal: {@link AiInstallService#getStatus()} reports the install run
   *       is still {@code "running"} (covers the whole preflight → download → apply lifecycle, not
   *       just the download phase, since extraction can start before a package flips to
   *       "downloading").
   *   <li>A filesystem fallback: a {@code *.tmp} file is present directly in the directory (e.g. a
   *       Windows BITS in-progress transfer temp file). Covers the case where {@code
   *       aiInstallService} is unavailable (older constructor overloads, tests) or a race just
   *       outside the "running" window.
   * </ol>
   */
  private boolean isLikelyInFlightInstall(Path dir) {
    if (aiInstallService != null) {
      try {
        // Tempdoc 824 §3.3c: the cheap field read, not the full status. getStatus() now also
        // projects the runtime observation this very class derives — reading it here would re-enter
        // RuntimeActivationService and put its Worker RPCs behind a per-directory filesystem probe.
        if (aiInstallService.isInstallRunning()) {
          return true;
        }
      } catch (Exception ignored) {
        // best-effort
      }
    }
    try (var files = Files.list(dir)) {
      return files.anyMatch(
          p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tmp"));
    } catch (Exception ignored) {
      return false;
    }
  }

  private static String resolveVariantIdFromExePath(String exePath) {
    if (exePath == null || exePath.isBlank()) return null;
    try {
      Path p = Path.of(exePath.trim());
      int n = p.getNameCount();
      for (int i = 0; i < n; i++) {
        String seg = p.getName(i).toString();
        if ("variants".equalsIgnoreCase(seg) && i + 1 < n) {
          return p.getName(i + 1).toString();
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }
    return null;
  }

  private static int pickEphemeralPort() {
    try (ServerSocket sock = new ServerSocket(0)) {
      sock.setReuseAddress(true);
      return sock.getLocalPort();
    } catch (IOException e) {
      // Fallback to a common local-only port; caller will fail cleanly if in use.
      return 18080;
    }
  }

  private static Path resolveAiHome() {
    return PlatformPaths.resolveAiHome();
  }

  /**
   * Tempdoc 804 §B2: second link of the chat-model resolution chain — the install contract, the
   * durable record of what Install AI actually placed on disk. User settings stay first (an
   * explicit choice wins); this is the fallback for a settings file that was never written, was
   * reset, or was discarded by a session-only persistence mode.
   *
   * <p>Mirrors the Worker's contract-first resolution ({@code KnowledgeServer#initDeferredModels} /
   * {@code resolveModelsDir}) rather than inventing a second resolution rule — which is exactly why
   * the Worker's ONNX features survived an upgrade that left chat dead.
   *
   * @return the absolute chat-model path recorded by install, or null when no contract exists, the
   *     contract has no (non-skipped) chat entry, or the contract cannot be read. Existence of the
   *     file is NOT checked here — {@code MODEL_NOT_FOUND} on the resolved path is the more useful
   *     failure than a generic "not configured", and it stays the one existence check.
   */
  private String resolveChatModelFromInstallContract() {
    try {
      InstallContract contract = InstallContractIO.read(aiHome);
      if (contract == null) {
        return null;
      }
      Path models = resolveContractModelsDir(contract);
      if (models == null) {
        return null;
      }
      Path chat = contract.resolveModelPath(CHAT_PACKAGE_ID, models);
      if (chat == null) {
        return null;
      }
      log.info("No chat model in settings; resolved {} from the install contract", chat);
      return chat.toAbsolutePath().toString();
    } catch (Exception e) {
      log.warn("Failed to read the install contract for chat-model fallback", e);
      return null;
    }
  }

  /** Contract-recorded models dir → resolved config → {@code aiHome/models} (Worker precedence). */
  private Path resolveContractModelsDir(InstallContract contract) {
    if (contract != null && contract.modelsDir() != null) {
      return contract.modelsDir();
    }
    ConfigStore cs = ConfigStore.globalOrNull();
    Path configured = cs != null ? cs.get().paths().modelsDir() : null;
    if (configured != null) {
      return configured;
    }
    return aiHome == null ? null : aiHome.resolve("models");
  }

  /**
   * The variants root this service activates out of, re-derived whenever the memo no longer names a
   * directory (tempdoc 913 H1).
   *
   * <p>It was a {@code final} field computed once in the constructor. That froze the answer before
   * the two things that can change it have happened: on a fresh profile nothing is on disk yet and
   * {@code AiInstallService} installs later, and a {@code ConfigStore} rebuild can re-point the
   * resolved server exe. A memo that is only trusted while it still resolves to a real directory
   * keeps the common path allocation-free without pinning a wrong answer for the process lifetime.
   */
  private Path variantsRoot() {
    Path memo = variantsRoot;
    if (memo != null && Files.isDirectory(memo)) {
      return memo;
    }
    Path fresh = resolveVariantsRoot(aiHome, repoRootOrNull(), resolvedServerExeOrNull());
    variantsRoot = fresh;
    return fresh;
  }

  /**
   * The repo root, resolved at most once per process.
   *
   * <p>Memoized because {@link #variantsRoot()} re-derives on every call while no variants
   * directory exists yet -- a fresh profile before Install AI runs -- and {@code getStatus()} is
   * polled about once a second by the FE during install/activation. Everything else in the
   * re-derivation is two {@code isDirectory} checks and a field read; this was the one filesystem
   * walk in it. Caching the ABSENCE is safe here in a way caching the variants root is not: a repo
   * checkout cannot appear around a running process, whereas an install completing underneath one
   * is the normal case that H1's laziness exists to serve.
   */
  private Path repoRootOrNull() {
    Optional<Path> memo = repoRootMemo;
    if (memo != null) {
      return memo.orElse(null);
    }
    Path resolved;
    try {
      resolved = RepoRootLocator.findRepoRootOrNull();
    } catch (Exception ignored) {
      resolved = null;
    }
    repoRootMemo = Optional.ofNullable(resolved);
    return resolved;
  }

  /**
   * The server exe the config layer resolved, or null before {@code ConfigStore.setGlobal} has run
   * (unit tests, very early boot). Read through the store rather than {@code System.getenv} — a
   * direct env read of {@code JUSTSEARCH_SERVER_EXE} is what {@code EnvRegistryDirectReadTest}
   * forbids, and the store is also where a JVM-arg override outranks the env. (There was a
   * worker-snapshot tier at ordinal 450 between the two until lane F item A19; it existed to carry
   * the Head's resolved config across a process boundary that no longer exists.)
   */
  private static Path resolvedServerExeOrNull() {
    try {
      ConfigStore cs = ConfigStore.globalOrNull();
      return cs == null ? null : cs.get().ai().serverExe();
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * Resolves the variants root directory.
   *
   * <p>In production, variants are at {@code {aiHome}/native-bin/llama-server/variants/}. In dev
   * mode, aiHome typically points to {@code .dev-data} but variants are at
   * {@code modules/ui/native-bin/llama-server/variants/}.
   *
   * <p>Tempdoc 913 H1 — the resolved server exe is consulted between those two. In an agent
   * worktree the first two both miss: {@code JUSTSEARCH_HOME} is the worktree's {@code .dev-data}
   * (no {@code native-bin} under it) and {@code RepoRootLocator} finds the WORKTREE root, which has
   * no {@code modules/ui/native-bin} either — worktrees share the main checkout's 10 MB cuda12
   * build rather than copying it. So {@code listInstalledVariants()} returned {@code []} and every
   * activation failed {@code RUNTIME_VARIANT_NOT_INSTALLED}, while the dev-runner had already
   * resolved the shared exe and put it in {@code JUSTSEARCH_SERVER_EXE}
   * ({@code scripts/dev/dev-runner.cjs:493-511}) — the answer was in the config the whole time.
   * Deriving the root from that exe is what makes the worktree docs' promise ("the shared cuda12
   * llama-server resolves from the main checkout automatically") true without junctions.
   *
   * <p>Order matters and aiHome stays FIRST: this class itself writes {@code justsearch.server.exe}
   * on activate, so an exe-first order would let one activation re-root a real install at whatever
   * it last launched. Each exe-derived candidate must still be a directory, so a BYO exe outside a
   * {@code variants/} tree contributes nothing.
   *
   * @param aiHome the resolved AI home; null yields null (no root can be named)
   * @param repoRoot the dev-mode repo root, or null when not resolvable
   * @param resolvedServerExe the config-resolved llama-server exe, or null when unknown
   */
  static Path resolveVariantsRoot(Path aiHome, Path repoRoot, Path resolvedServerExe) {
    if (aiHome == null) {
      return variantsRootOfExe(resolvedServerExe);
    }
    Path standard = aiHome.resolve("native-bin").resolve("llama-server").resolve("variants");
    if (Files.isDirectory(standard)) {
      return standard;
    }

    Path fromExe = variantsRootOfExe(resolvedServerExe);
    if (fromExe != null) {
      log.debug("Using variants path derived from the resolved server exe: {}", fromExe);
      return fromExe;
    }

    // Dev mode fallback: the repo checkout this process resolved to.
    if (repoRoot != null) {
      Path devVariants =
          repoRoot
              .resolve("modules")
              .resolve("ui")
              .resolve("native-bin")
              .resolve("llama-server")
              .resolve("variants");
      if (Files.isDirectory(devVariants)) {
        log.debug("Using dev mode variants path: {}", devVariants);
        return devVariants;
      }
    }

    // Return standard path even if it doesn't exist (for consistent error messages)
    return standard;
  }

  /**
   * The {@code .../variants} prefix of an exe that lives under {@code .../variants/<id>/}, or null.
   * The segment walk mirrors {@link #resolveVariantIdFromExePath}, which answers the sibling
   * question (the id) over the same shape — one spelling of "a variants-laid-out exe", two reads.
   */
  private static Path variantsRootOfExe(Path exe) {
    if (exe == null) {
      return null;
    }
    try {
      Path p = exe.toAbsolutePath().normalize();
      int n = p.getNameCount();
      for (int i = n - 1; i >= 0; i--) {
        if (!"variants".equalsIgnoreCase(p.getName(i).toString())) {
          continue;
        }
        Path root = p.getRoot() == null ? p.subpath(0, i + 1) : p.getRoot().resolve(p.subpath(0, i + 1));
        return Files.isDirectory(root) ? root : null;
      }
    } catch (Exception ignored) {
      // best-effort
    }
    return null;
  }

  private static Path resolveBundledRuntimeBinDirBestEffort() {
    try {
      ConfigStore cs = ConfigStore.globalOrNull();
      Path repoRootPath = cs != null ? cs.get().paths().repoRoot() : null;
      Path headlessDir = repoRootPath != null ? repoRootPath : Path.of(System.getProperty("user.dir"));
      Path bin = headlessDir.resolve("runtime").resolve("bin");
      if (Files.isDirectory(bin)) {
        return bin;
      }
    } catch (Exception ignored) {
      // best-effort
    }
    return null;
  }

  private static Path resolveCpuBaselineExe(Path aiHome) {
    if (aiHome == null) return null;
    try {
      Path nativeBin = aiHome.resolve("native-bin").resolve("llama-server");
      if (!Files.isDirectory(nativeBin)) return null;

      // 1. Check canonical baseline path FIRST (deterministic, preferred)
      Path canonical = nativeBin.resolve("llama-server.exe");
      if (Files.isRegularFile(canonical)) {
        return canonical;
      }

      // 2. Scan subdirectories (SORTED for determinism, skip variants/)
      try (var dirs = Files.list(nativeBin)) {
        return dirs
            .filter(Files::isDirectory)
            .filter(d -> !"variants".equalsIgnoreCase(d.getFileName().toString()))
            .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
            .map(d -> d.resolve("llama-server.exe"))
            .filter(Files::isRegularFile)
            .findFirst()
            .orElse(null);
      }
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean isWindows() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("win");
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static String safeMsg(Throwable t) {
    if (t == null) return "";
    String m = t.getMessage();
    if (m == null || m.isBlank()) {
      return t.getClass().getSimpleName();
    }
    return m;
  }

  record SelfTestResult(
      String result,
      Integer port,
      Long vramBefore,
      Long vramAfter,
      Long delta,
      List<String> effectiveFlags,
      String vramTier,
      String vramSource
  ) {}

  private static final class SelfTestException extends Exception {
    SelfTestException(String message) {
      super(message);
    }
  }
}
