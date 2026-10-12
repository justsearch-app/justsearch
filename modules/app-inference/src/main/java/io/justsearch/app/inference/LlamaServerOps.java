/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static io.justsearch.app.inference.InferenceHttpHelpers.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.gpu.VramFlagsUtil;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.api.TransitionCode;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildRegistrationRefusedException;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.ExitClass;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import java.io.IOException;
import java.util.Objects;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process management, health monitoring, and crash recovery for llama-server.
 *
 * <p>Encapsulates: process start/stop, health probing, periodic health monitoring, and crash
 * recovery. Delegates /props interpretation and external server diagnostics to {@link
 * ServerPropsOps}. Extracted to reduce the size of {@link InferenceLifecycleManager}.
 *
 * <p>ADR-0051 decision 2: this class never adopts a llama-server it did not start in this Engine.
 * Every managed launch binds a fresh loopback port and ends with its Engine; a predecessor's server
 * is ended at boot by the child reconciler. A user's own server is used only when named by {@code
 * justsearch.inference.external_endpoint}, and is then reported and never stopped.
 */
final class LlamaServerOps {
  private static final Logger LOG = LoggerFactory.getLogger(LlamaServerOps.class);

  // ==================== Constants ====================

  // 369: Configurable via -Djustsearch.inference.health_check_timeout_ms for eval runs
  // with large models (9B+ can take >30s to load).
  // Static init runs before ConfigStore — direct sysprop read is intentional.
  // Tempdoc 374 alpha.17 R1: default raised from 30s → 120s. Sandbox round 7 showed
  // Qwen3.5-9B Q4_K_M cold-load + multimodal mmproj warmup legitimately exceeds 30s
  // on a fresh install, causing every install's smoke test to false-fail with
  // SMOKE_TEST_FAILED even though llama-server was healthy. 120s = 2× the worst-case
  // production cold-load with headroom for slower disks; eval still overrides to 180000.
  /**
   * Declared Brain recovery policy (tempdoc 627) — the single source for the crash/health-recovery
   * knobs below. The local constants derive from it so existing call sites are unchanged; the policy is
   * what {@code governance/supervision-contract.v1.json} asserts against.
   */
  private static final BrainSupervisionPolicy SUPERVISION_POLICY = BrainSupervisionPolicy.defaults();

  private static final long HEALTH_CHECK_TIMEOUT_MS =
      SUPERVISION_POLICY.healthCheckTimeoutMs(); // SYS-PROP-LEGACY-COMPAT: read in policy defaults() at static init
  private static final long HEALTH_CHECK_INTERVAL_MS = 500;
  private static final long HEALTH_CHECK_PROGRESS_LOG_INTERVAL_MS = 10_000;
  private static final String PATH_HEALTH = "/health";
  private static final String PATH_PROPS = "/props";
  // Windows process exit code for missing DLL dependencies at load time (STATUS_DLL_NOT_FOUND /
  // 0xC0000135).
  private static final int WINDOWS_STATUS_DLL_NOT_FOUND = -1073741515;

  // Policy bridge system properties
  private static final String POLICY_DISALLOW_EXTERNAL_PROP =
      "justsearch.policy.disallowExternalInferenceServers";
  private static final String ALLOW_HEALTH_ONLY_EXTERNAL_ADOPTION_PROP =
      "justsearch.inference.external.allow_health_only_adoption";

  // Periodic health monitoring
  private static final long PERIODIC_HEALTH_INTERVAL_MS = SUPERVISION_POLICY.periodicHealthIntervalMs();
  private static final int CONSECUTIVE_FAILURES_BEFORE_RESTART =
      SUPERVISION_POLICY.consecutiveFailuresBeforeRestart();

  /**
   * Generations of {@code logs/llama-server.log} kept across launches (the live file plus two
   * archived ones). A constant, not a config key: retention of a diagnostic the app writes about
   * its own child process is a product decision, and the number matched the app's other supervised
   * process log (the Worker's {@code worker.log} → {@code .1} → {@code .2}, until lane F stage A
   * item A11 deleted that process). Since A16 the Engine's own {@code engine.log} is rolled by
   * Logback (date + size), not by this policy, so llama-server.log is the only log this constant
   * governs.
   */
  private static final int RETAINED_LOG_GENERATIONS = 3;

  // Process and probe timeouts
  private static final long PROCESS_KILL_TIMEOUT_SECS = 5;
  /** A stop's two terminate waits (5 s each, Spec B §2.2) and a margin for the exit record. */
  private static final Duration STOP_WAIT = Duration.ofSeconds(12);
  /** The GPU barrier's bound: one release poll's maximum (Spec B §2.6). */
  private static final Duration GPU_RELEASE_WAIT = Duration.ofSeconds(5);
  /** Retries a survivor's stop; ChildControl keeps the survivor's first intent and reason. */
  private static final StopRequest SURVIVOR_RETRY = StopRequest.of(
      io.justsearch.app.api.runtime.child.StopReason.ROLLBACK,
      io.justsearch.app.api.runtime.child.StopMode.NOW, "chat-controller");
  private static final Duration STARTUP_HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration PROPS_PROBE_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration PERIODIC_HEALTH_TIMEOUT = Duration.ofSeconds(5);

  // ==================== Owned Fields ====================

  /**
   * The launched server (Spec B step 5): started and stopped only through {@link #childControl},
   * so every stop is a recorded intent and every exit is classified in the exit ledger.
   */
  private volatile ChildHandle child;
  private volatile CompletableFuture<?> crashMonitor;
  /** The owner a planned stop is ending; its exit, whatever its class, is not a crash. */
  private volatile ActiveServer stoppingOwner;
  private final Object ownershipMonitor = new Object();
  private final ChildControl childControl;
  /** True for a control made by a registry constructor: {@link #shutdown} closes it. */
  private final boolean ownsChildControl;
  private final LlamaServerProcessOwnership processOwnership;
  private volatile ActiveServer activeServer;
  private volatile ActiveServer failureSignalledOwner;
  /** Exact last attempted managed start, retained across ordinary failed cleanup for recovery. */
  private volatile StartRequest retainedStartRequest;

  // VRAM status (for debugging)
  private volatile List<String> lastEffectiveVramFlags = List.of();
  private volatile String vramTierDetected = "unknown";
  private volatile String vramDetectionSource = "none";
  private volatile String cudaRuntimeWarning = null;

  // Thinking capability (tempdoc 835 §5.2): launch-argument acceptance is the authoritative signal.
  private final AtomicReference<ThinkingSupport> thinkingSupport =
      new AtomicReference<>(ThinkingSupport.UNKNOWN);
  private volatile boolean reasoningBudgetRequested;
  private volatile List<String> lastLaunchCommand = List.of();
  private volatile LlamaDevicePlan launchDevicePlan;
  private volatile long lastLaunchLogOffset;
  // CH3: a lost port bind is retried once per start on a fresh port, at the same rung.
  private volatile boolean bindRetryUsed;

  // Spec A §3.3: every supervision timer reads the monotonic clock. Tests replace it by
  // reflection (ChatCrashRestartTest); production never does.
  private volatile LongSupplier monotonicNanos = System::nanoTime;

  /**
   * Spec A §7.4: counts resumes from sleep. A start waiting for health restarts its deadline in
   * full when this moves, so suspended time is never counted against it.
   */
  private final AtomicLong resumes =
      new AtomicLong();

  /**
   * T6/T27: the rung a RESOURCE step-down reached, for the configuration it was reached under. A
   * later automatic launch of the same declared configuration starts there for the rest of this
   * Engine's life instead of failing at the top rung again.
   */
  private record SteppedRung(String declaredConfigHash, int rung) {}

  private volatile SteppedRung steppedRung;

  /** When the owner became READY (runtime monitoring armed), on the monotonic clock. */
  private record ReadyMark(ActiveServer owner, long atNanos) {}

  private volatile ReadyMark readyMark;

  /**
   * An unexpected failure of a READY managed server, described for the chat crash policy (Spec A
   * §3.2 T27-T30) before the failure is signalled.
   *
   * @param start the failed server's start identity
   * @param classification the classified exit; class OTHER for a hang
   * @param hang true when the process is alive but stopped answering health (T30)
   * @param readyFor how long it had been READY, on the monotonic clock
   * @param lowerRung the next rung below the failed one, absent for an override or the last rung
   */
  record ManagedFailure(
      StartResult start,
      LlamaServerExitClassifier.Classification classification,
      boolean hang,
      Duration readyFor,
      OptionalInt lowerRung) {}

  private volatile ManagedFailure lastManagedFailure;

  // CH4: a healthy server answering on the configured port while no external endpoint is set. It
  // is not adopted; the configured port it was found on is reported (0 = none seen).
  private volatile int ignoredExternalServerPort;

  // Context window as a derived resource (tempdoc 883 decision 1). Null plan = nothing this class
  // launched: no server yet, or an adopted external one whose window we did not choose.
  private volatile ContextWindowPolicy.Plan contextPlan;
  private volatile int contextRung;
  private volatile String contextRungReason;
  private volatile int contextSlots;
  private volatile String contextKvType;

  // Health monitoring (separate from recovery so a slow health probe cannot block recovery)
  private final ScheduledExecutorService healthScheduler;
  private final java.util.concurrent.ExecutorService exitExecutor;

  // Periodic health monitoring
  private final AtomicInteger consecutiveHealthFailures = new AtomicInteger(0);
  private final AtomicLong lastPeriodicHealthOkAtMs = new AtomicLong(0);
  private final AtomicReference<String> lastPeriodicHealthError = new AtomicReference<>(null);
  private volatile ScheduledFuture<?> periodicHealthTask;

  // ==================== Injected Dependencies ====================

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  // Tempdoc 374 alpha.27: VramDetector dependency removed; all VRAM probes route through
  // GpuCapabilitiesService (NVML-first) + VramRequirements (threshold helpers).
  private final GpuCapabilitiesService gpuCapabilitiesService;
  private final Supplier<Optional<String>> pinnedCudaUuid;
  private final Supplier<Mode> currentMode;

  // Tempdoc 518 P2: LlamaServerOps now owns its own external-adoption flag. Previously this
  // was a back-channel into ILM's volatile field via injected Consumer/Supplier ports
  // (eliminated). Read by the runner when building the next view atom.
  private volatile boolean usingExternal = false;

  // Mode transition callbacks
  private final Consumer<BooleanSupplier> managedServerFailed;
  private final BiConsumer<String, BooleanSupplier> goOfflineFromExternalFailure;

  // Composition: props interpretation and external diagnostics
  private final ServerPropsOps propsOps;

  // Tempdoc 412 follow-up: typed observability events. Defaults to noop when no telemetry
  // is wired (e.g., tests, AI-disabled bootstrap). Set via the events-aware constructor.
  private final InferenceTelemetryEvents events;

  /**
   * Whether a start may use the user's own server (ADR-0051 decision 2, ruling CH4).
   *
   * <ul>
   *   <li>{@link #LEGACY_ALLOW_EXTERNAL}: Online activation. Uses the server named by {@code
   *       justsearch.inference.external_endpoint} when that is set; otherwise launches a managed
   *       server. A healthy server on the configured port is never adopted.
   *   <li>{@link #REQUIRE_MANAGED_CONFIG_WITNESS}: strict settings apply and its rollback. Needs a
   *       managed server whose declared configuration it can witness, so it refuses while an
   *       external endpoint is configured.
   *   <li>{@link #MANAGED_DETACHED_FROM_EXTERNAL}: the user detached from their server for this
   *       Engine's lifetime; always launches a managed server.
   * </ul>
   */
  enum AdoptionPolicy {
    LEGACY_ALLOW_EXTERNAL,
    REQUIRE_MANAGED_CONFIG_WITNESS,
    MANAGED_DETACHED_FROM_EXTERNAL
  }

  enum StartDisposition {
    LAUNCHED_MANAGED,
    /** The user's own server, named by {@code justsearch.inference.external_endpoint}. */
    ADOPTED_EXTERNAL
  }

  record StartRequest(LlamaServerConfigContext context, AdoptionPolicy adoptionPolicy) {
    StartRequest {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(adoptionPolicy, "adoptionPolicy");
    }

    int effectiveGpuLayers() {
      return context.devicePlan().gpuLayers();
    }
  }

  record StartResult(
      LlamaServerConfigContext context,
      AdoptionPolicy adoptionPolicy,
      StartDisposition disposition,
      String declaredConfigHash) {
    StartResult {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(adoptionPolicy, "adoptionPolicy");
      Objects.requireNonNull(disposition, "disposition");
      boolean managed = disposition != StartDisposition.ADOPTED_EXTERNAL;
      if (managed && (declaredConfigHash == null || declaredConfigHash.isBlank())) {
        throw new IllegalArgumentException("managed server requires declaredConfigHash");
      }
      if (!managed
          && (declaredConfigHash != null
              || adoptionPolicy != AdoptionPolicy.LEGACY_ALLOW_EXTERNAL)) {
        throw new IllegalArgumentException("external adoption requires legacy policy and no hash");
      }
    }
  }

  /**
   * The server a start produced. {@code port} is where it actually listens: the fresh loopback
   * port of this managed launch, or the external endpoint's port. It is kept here rather than in
   * {@link StartResult} so a same-configuration relaunch on a new port keeps its start identity.
   */
  private record ActiveServer(StartResult start, ChildHandle child, int port) {}

  // ==================== Constructor ====================

  /**
   * Sole constructor. Tempdoc 518 fix F — the prior 8-param delegating constructor (which
   * filled in {@code InferenceTelemetryEvents.noop()}) was dead code: both production (ILM)
   * and tests pass the events sink explicitly. Tests use
   * {@code InferenceTelemetryEvents.noop()} when they don't care about telemetry.
   *
   * <p>Tempdoc 518 P2: the four Consumer/Supplier ports for shared mutable state
   * (usingExternal getter/setter; model-id and context-tokens getters/setters) were eliminated.
   * LlamaServerOps now owns {@code usingExternal} directly; the props observer mediates
   * model-id / context-tokens propagation to the orchestrator's view atom.
   */
  @SuppressWarnings("ParameterNumber") // NOPMD — package-private companion class with many deps
  LlamaServerOps(
      InferenceExecutorRegistrations executors,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      GpuCapabilitiesService gpuCapabilitiesService,
      Supplier<Mode> currentMode,
      PropsObserver propsObserver,
      Consumer<BooleanSupplier> managedServerFailed,
      BiConsumer<String, BooleanSupplier> goOfflineFromExternalFailure,
      InferenceTelemetryEvents events) {
    this(
        executors, httpClient, objectMapper, gpuCapabilitiesService, currentMode, propsObserver,
        managedServerFailed, goOfflineFromExternalFailure, events,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  /** A server registered in {@code childRegistry} through an unrecorded control this owns. */
  @SuppressWarnings("ParameterNumber")
  LlamaServerOps(
      InferenceExecutorRegistrations executors,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      GpuCapabilitiesService gpuCapabilitiesService,
      Supplier<Mode> currentMode,
      PropsObserver propsObserver,
      Consumer<BooleanSupplier> managedServerFailed,
      BiConsumer<String, BooleanSupplier> goOfflineFromExternalFailure,
      InferenceTelemetryEvents events,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    this(
        executors, httpClient, objectMapper, gpuCapabilitiesService, currentMode, propsObserver,
        managedServerFailed, goOfflineFromExternalFailure, events, childRegistry, Optional::empty);
  }

  /**
   * A server registered in {@code childRegistry} through an unrecorded control this owns.
   *
   * @param pinnedCudaUuid the pinned GPU's CUDA UUID (Spec E step E6), read at each launch; empty
   *     when no device is pinned
   */
  @SuppressWarnings("ParameterNumber")
  LlamaServerOps(
      InferenceExecutorRegistrations executors,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      GpuCapabilitiesService gpuCapabilitiesService,
      Supplier<Mode> currentMode,
      PropsObserver propsObserver,
      Consumer<BooleanSupplier> managedServerFailed,
      BiConsumer<String, BooleanSupplier> goOfflineFromExternalFailure,
      InferenceTelemetryEvents events,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      Supplier<Optional<String>> pinnedCudaUuid) {
    this(
        executors, httpClient, objectMapper, gpuCapabilitiesService, currentMode, propsObserver,
        managedServerFailed, goOfflineFromExternalFailure, events,
        DefaultChildControl.unrecorded(Objects.requireNonNull(childRegistry, "childRegistry")),
        true, pinnedCudaUuid);
  }

  /** The Engine's composition: {@code childControl} is the caller's and outlives this. */
  @SuppressWarnings("ParameterNumber")
  LlamaServerOps(
      InferenceExecutorRegistrations executors,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      GpuCapabilitiesService gpuCapabilitiesService,
      Supplier<Mode> currentMode,
      PropsObserver propsObserver,
      Consumer<BooleanSupplier> managedServerFailed,
      BiConsumer<String, BooleanSupplier> goOfflineFromExternalFailure,
      InferenceTelemetryEvents events,
      ChildControl childControl) {
    this(
        executors, httpClient, objectMapper, gpuCapabilitiesService, currentMode, propsObserver,
        managedServerFailed, goOfflineFromExternalFailure, events, childControl, false,
        Optional::empty);
  }

  /**
   * As above; {@code ownsChildControl} makes {@link #shutdown} close the control.
   *
   * @param pinnedCudaUuid the pinned GPU's CUDA UUID (Spec E step E6), read at each launch; empty
   *     when no device is pinned
   */
  @SuppressWarnings("ParameterNumber")
  LlamaServerOps(
      InferenceExecutorRegistrations executors,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      GpuCapabilitiesService gpuCapabilitiesService,
      Supplier<Mode> currentMode,
      PropsObserver propsObserver,
      Consumer<BooleanSupplier> managedServerFailed,
      BiConsumer<String, BooleanSupplier> goOfflineFromExternalFailure,
      InferenceTelemetryEvents events,
      ChildControl childControl,
      boolean ownsChildControl,
      Supplier<Optional<String>> pinnedCudaUuid) {
    this.pinnedCudaUuid = Objects.requireNonNull(pinnedCudaUuid, "pinnedCudaUuid");
    this.childControl = Objects.requireNonNull(childControl, "childControl");
    this.ownsChildControl = ownsChildControl;
    this.processOwnership = new LlamaServerProcessOwnership(childControl);
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.gpuCapabilitiesService = gpuCapabilitiesService;
    this.currentMode = currentMode;
    this.managedServerFailed =
        Objects.requireNonNull(managedServerFailed, "managedServerFailed");
    this.goOfflineFromExternalFailure =
        Objects.requireNonNull(goOfflineFromExternalFailure, "goOfflineFromExternalFailure");
    this.events = Objects.requireNonNull(events, "events");
    ScheduledExecutorService openedHealth = null;
    java.util.concurrent.ExecutorService openedExit = null;
    try {
      openedHealth = executors.llamaHealth.openScheduled(daemonFactory("Server-Health"));
      openedExit = executors.llamaExit.open(daemonFactory("Server-Exit"));
    } catch (RuntimeException | Error failure) {
      cancelIfOpened(openedExit);
      cancelIfOpened(openedHealth);
      throw failure;
    }
    this.healthScheduler = openedHealth;
    this.exitExecutor = openedExit;
    this.propsOps =
        new ServerPropsOps(propsObserver, this::requestedContextTokens);
  }

  // ==================== Process Lifecycle ====================

  StartResult startLlamaServer(StartRequest request)
      throws IOException, ModeTransitionException {
    Objects.requireNonNull(request, "request");
    processOwnership.requireLaunchAllowed();
    retainedStartRequest = request;
    // A server that survived its stop still holds its port and memory; never start over it.
    retrySurvivor();
    LOG.info("Starting llama-server...");

    // Nothing is launched yet, so no window has been chosen. Cleared before the external check so
    // the user's own server reports no context record rather than the previous launch's.
    clearContextWindowRecord();
    bindRetryUsed = false;

    LlamaServerConfigContext context = request.context();
    InferenceConfig cfg = context.inference();
    ResolvedConfig rc = context.resolved();
    int gpuLayers = request.effectiveGpuLayers();
    if (cfg.gpuLayers() > 0 && gpuLayers == 0) {
      LOG.warn("GPU acceleration disabled by policy; forcing -ngl 0");
    }
    String declaredConfigHash = ManagedLlamaConfigIdentity.declaredHash(cfg, rc, gpuLayers);
    StartResult external = useConfiguredExternalServer(request);
    if (external != null) return external;

    usingExternal = false;

    // Tempdoc 883 decision 1: the context window is a DERIVED resource, not a stored preference.
    // The rung comes from the ladder for this backend (or an operator's explicit value, as a
    // one-rung ladder); if the server refuses it, waitForServerHealth steps down one rung and
    // relaunches rather than leaving the engine down.
    GpuCapabilities gpuSnap = gpuLayers > 0 ? gpuCapabilitiesService.snapshot() : null;
    Long freeVramBytes =
        gpuSnap != null && gpuSnap.effective() != null ? gpuSnap.effective().freeVramBytes() : null;
    ContextWindowPolicy.Plan plan = withSteppedRung(
        planContextWindow(rc, gpuLayers > 0, freeVramBytes), declaredConfigHash);
    this.contextPlan = plan;
    this.contextRung = plan.topRung();
    this.contextRungReason = plan.reason();
    this.contextSlots = slotsFor(cfg, rc);
    this.contextKvType = rc.ai().llmKvType();
    this.lastEffectiveVramFlags = memoryPlanFlags(contextSlots, contextKvType);

    // Capture once: retries and the child environment use exactly this device decision.
    launchDevicePlan = LlamaDevicePlan.resolve(rc, gpuLayers, pinnedDeviceEnvironment(pinnedCudaUuid.get()));
    List<String> command = buildLaunchCommand(cfg, rc, launchDevicePlan, plan);
    LOG.info("Host prompt cache limit: {} MiB", cfg.promptCacheMiB());

    boolean reasoningRequested = rc.ai().useThinking() && rc.ai().reasoningBudget() != 0;
    this.reasoningBudgetRequested = reasoningRequested;
    thinkingSupport.set(reasoningRequested ? ThinkingSupport.UNKNOWN : ThinkingSupport.DISABLED);

    if (cfg.vduMode()) {
      LOG.info("VDU mode: applying vision-safe flags (--cache-ram 0, -np 1)");
    }

    // Warn if GPU layers requested but using the CPU variant (no CUDA backend).
    // The CPU variant's ggml-cuda.dll is dynamically linked and requires CUDA Toolkit.
    // The CUDA variant (under variants/cuda12/) is self-contained. See docs/how-to/test-gpu-locally.md.
    if (gpuLayers > 0 && !cfg.serverExecutable().toString().contains("variants")) {
      LOG.warn(
          "GPU layers={} requested but using CPU variant ({}). "
              + "GPU offload may not work. Activate the CUDA variant or set "
              + "-Djustsearch.server.exe to a CUDA-capable binary.",
          gpuLayers,
          cfg.serverExecutable().getFileName());
    }

    // VRAM tier + CUDA runtime diagnostics, from the NVML-first snapshot taken above (tempdoc 374
    // alpha.27: not vramDetector.getRecommendedLlamaServerFlags(), which shells out to nvidia-smi
    // and returns null on cuda12 sandbox hosts).
    if (gpuSnap != null) {
      this.vramTierDetected = VramFlagsUtil.detectVramTier(gpuSnap.effective().totalVramBytes());
      this.vramDetectionSource = gpuSnap.effective().source();
      LOG.info(
          "VRAM detection: source={}, tier={}, freeVramBytes={}, launch flags={}",
          vramDetectionSource,
          vramTierDetected,
          freeVramBytes,
          lastEffectiveVramFlags);

      // Check for CUDA runtime availability when GPU mode is requested
      if (gpuSnap.effective().cudaAvailable()) {
        this.cudaRuntimeWarning =
            CudaRuntimeDetection.detectCudaRuntimeWarning(cfg.serverExecutable());
      }
    } else {
      this.vramTierDetected = "unknown";
      this.vramDetectionSource = "none";
      this.cudaRuntimeWarning = null;
    }

    LOG.info(
        "Context window: rung={} reason={} slots={} kv={} ladder={} freeVramBytes={} (-fit off)",
        plan.topRung(),
        plan.reason(),
        contextSlots,
        contextKvType,
        plan.ladder(),
        freeVramBytes);

    StartResult launched =
        new StartResult(
            context,
            request.adoptionPolicy(),
            StartDisposition.LAUNCHED_MANAGED,
            declaredConfigHash);
    // ADR-0051 decision 2: every managed launch binds a fresh loopback port, never the configured
    // one, so nothing left on the configured port (a crashed Engine's unregistered server, or the
    // user's own) can be mistaken for this launch.
    launchOnFreshPort(command, launched);
    return launched;
  }

  /**
   * T6/T27 stickiness: once a RESOURCE exit stepped this declared configuration down, a later
   * derived plan for it starts at the rung that was reached, reported as stepped from the planned
   * top. An operator override is never adjusted (it is a one-rung ladder that fails loud).
   */
  private ContextWindowPolicy.Plan withSteppedRung(
      ContextWindowPolicy.Plan planned, String declaredConfigHash) {
    SteppedRung stepped = steppedRung;
    if (stepped == null
        || !stepped.declaredConfigHash().equals(declaredConfigHash)
        || !ContextWindowPolicy.REASON_TOP_RUNG.equals(planned.reason())
        || stepped.rung() >= planned.topRung()) {
      return planned;
    }
    List<Integer> remaining = planned.ladder().stream().filter(r -> r <= stepped.rung()).toList();
    if (remaining.isEmpty()) return planned;
    LOG.info(
        "Context window: starting at rung {} — this configuration did not fit at {} earlier in this"
            + " Engine's life",
        remaining.get(0),
        planned.topRung());
    return new ContextWindowPolicy.Plan(
        remaining, ContextWindowPolicy.steppedFrom(planned.topRung()), planned.freeVramBytes());
  }

  /** The planned top rung a window was stepped down from: the plan's own top until it steps. */
  private static int plannedTopRung(ContextWindowPolicy.Plan plan) {
    String reason = plan.reason();
    if (reason.startsWith(ContextWindowPolicy.REASON_STEPPED_FROM_PREFIX)) {
      try {
        return Integer.parseInt(
            reason.substring(ContextWindowPolicy.REASON_STEPPED_FROM_PREFIX.length()));
      } catch (NumberFormatException ignored) {
        return plan.topRung();
      }
    }
    return plan.topRung();
  }

  /** T7: why a RESOURCE exit ends the start instead of stepping down. */
  private void logNoLowerRung() {
    ContextWindowPolicy.Plan plan = contextPlan;
    if (plan != null && ContextWindowPolicy.REASON_OVERRIDE.equals(plan.reason())) {
      LOG.error(
          "llama-server did not start at the explicitly configured context size {}"
              + " (justsearch.context.size). This is an operator override, so it is NOT reduced"
              + " to a smaller window: set it lower, or set it to 0 to let the window be derived"
              + " from the backend.",
          contextRung);
    } else {
      LOG.warn(
          "llama-server ran out of memory at context rung {} and the ladder {} is exhausted;"
              + " reporting resource_exhausted rather than retrying.",
          contextRung,
          plan == null ? List.of() : plan.ladder());
    }
  }

  /** The next rung below the running one, or empty for an override or the last rung. */
  private OptionalInt lowerContextRung() {
    ContextWindowPolicy.Plan plan = contextPlan;
    if (plan == null || ContextWindowPolicy.REASON_OVERRIDE.equals(plan.reason())) {
      return OptionalInt.empty();
    }
    return plan.nextRungBelow(contextRung);
  }

  /**
   * T27: a READY server exited for lack of memory. Its configuration starts one rung lower from now
   * on. Returns false when there is no lower rung (an override or the last rung).
   */
  boolean stepDownAfterFailure(ManagedFailure failure) {
    Objects.requireNonNull(failure, "failure");
    if (failure.lowerRung().isEmpty() || failure.start().declaredConfigHash() == null) return false;
    steppedRung =
        new SteppedRung(failure.start().declaredConfigHash(), failure.lowerRung().getAsInt());
    LOG.warn(
        "llama-server ran out of memory while serving; its window steps down to {} for the rest of"
            + " this Engine's life ({})",
        failure.lowerRung().getAsInt(),
        failure.classification().evidence());
    return true;
  }

  private static int allocateLaunchPort() throws ModeTransitionException {
    try {
      return LoopbackPorts.findFree();
    } catch (IOException e) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.PORT_ALLOCATION_FAILED,
          "Failed to allocate a free loopback port for llama-server",
          e);
    }
  }

  /** Captures an initial attempt before manager-side asset/VRAM validation can fail. */
  void retainAttemptedStartRequest(StartRequest request) {
    retainedStartRequest = Objects.requireNonNull(request, "request");
  }

  /**
   * The FULL llama-server argv for a launch. Package-private, static and pure so the exact
   * argument list is unit-testable without spawning a process — the flags interact (see
   * {@link #memoryPlanFlags}), so asserting them individually is not the same as asserting the
   * launch line.
   *
   * <p>This overload projects a pinned launch for command checks. Managed launches supply their
   * captured device plan to the overload below, and keep its environment through every retry.
   */
  static List<String> buildLaunchCommand(
      InferenceConfig cfg, ResolvedConfig rc, int gpuLayers, ContextWindowPolicy.Plan plan) {
    return buildLaunchCommand(cfg, rc,
        LlamaDevicePlan.resolve(rc, gpuLayers, Optional.of("GPU-command-plan")), plan);
  }

  private static List<String> buildLaunchCommand(
      InferenceConfig cfg, ResolvedConfig rc, LlamaDevicePlan devices, ContextWindowPolicy.Plan plan) {
    List<String> command = new ArrayList<>();
    command.add(cfg.serverExecutable().toString());
    command.add("-m");
    command.add(cfg.modelPath().toString());

    if (cfg.mmprojPath() != null) {
      command.add("--mmproj");
      command.add(cfg.mmprojPath().toString());
      command.addAll(devices.projectorFlags());
    }

    // VDU mode: no prompt cache (it corrupts silently after ~7 pages and crashes the server).
    // The single-slot half of this rule rides the common -np below, which VDU pins to 1 —
    // multi-slot causes alternating HTTP 500 on vision requests.
    // Always name the host bound: omitting it enables llama.cpp's 8192 MiB default.
    command.add("--cache-ram");
    command.add(Integer.toString(cfg.promptCacheMiB()));

    // Enable Jinja2 template processing for tool-use chat templates (required for function calling).
    command.add("--jinja");

    // When thinking mode is enabled, emit reasoning_content as a separate SSE field
    // instead of inline <think> tags in content. Requires llama.cpp build >=b4215.
    if (rc.ai().useThinking()) {
      command.add("--reasoning-format");
      command.add("deepseek");

      // Control reasoning token generation. The default is a bounded budget (tempdoc 835): the
      // budget is shared with the answer, so 0 disables reasoning entirely and an unbounded budget
      // starves the answer — ResolvedConfigBuilder refuses the shapes that do.
      command.add("--reasoning-budget");
      command.add(String.valueOf(rc.ai().reasoningBudget()));
    }

    // Defense-in-depth: force loopback bind for owned llama-server (BYO binaries may differ).
    command.add("--host");
    command.add("127.0.0.1");

    // Tempdoc 412: enable Prometheus /metrics endpoint so OnlineRuntime can scrape queue depth,
    // active slots, kv-cache usage, tokens-predicted-total. Without this flag the endpoint 404s
    // and the inference.queue.* / inference.generation.* status gauges stay at zero.
    command.add("--metrics");

    // The configured port; a managed launch replaces it with its own fresh port (launchOnFreshPort).
    command.add("--port");
    command.add(String.valueOf(cfg.serverPort()));

    command.addAll(windowFlags(plan.topRung(), devices.gpuLayers()));
    command.addAll(devices.deviceFlags());
    command.addAll(memoryPlanFlags(slotsFor(cfg, rc), rc.ai().llmKvType()));
    return command;
  }

  /** The single-device flags of a GPU launch (Spec E section 5.3). */
  static final List<String> PINNED_DEVICE_FLAGS = LlamaDevicePlan.PINNED_DEVICE_FLAGS;

  static Map<String, String> pinnedDeviceEnvironment(Optional<String> pinnedCudaUuid) {
    return LlamaDevicePlan.pinnedEnvironment(pinnedCudaUuid);
  }

  /** Slots for this launch: VDU pins 1 (multi-slot breaks vision), otherwise the configured count. */
  static int slotsFor(InferenceConfig cfg, ResolvedConfig rc) {
    return cfg.vduMode() ? 1 : rc.ai().llmSlots();
  }

  /**
   * The window flags: {@code -c <rung> -ngl <layers>}.
   */
  static List<String> windowFlags(int rung, int gpuLayers) {
    List<String> flags = new ArrayList<>(List.of("-c", String.valueOf(rung)));
    flags.addAll(LlamaDevicePlan.layerFlags(gpuLayers));
    return List.copyOf(flags);
  }

  /**
   * The memory-plan flags (tempdoc 883 decision 2, fold [R1][R2][R4]) — slots, KV cache type,
   * flash attention, and memory fitting.
   *
   * <p>{@code -kvu} is not optional next to an explicit {@code -np}: llama-server enables
   * {@code kv_unified} only when the slot count is automatic, so passing {@code -np 2} without it
   * gives each slot HALF the window ({@code n_ctx_seq} 16384 at {@code -c 32768}) while
   * {@code /props} still reports the full {@code n_ctx} — a silent halving that reads as correct
   * from every surface that asks the server what its context is.
   *
   * <p>{@code -fa on} is explicit rather than {@code auto} because a quantized V-cache aborts the
   * launch without flash attention.
   *
   * <p>{@code -fit off} is what makes the context ladder mean anything. b8571 defaults
   * {@code --fit on} ("adjust UNSET arguments to fit in device memory", verified against the
   * bundled binary's {@code --help}), and it MAXIMIZES rather than fits: with {@code -c} omitted it
   * chose 242,944 tokens / 4 GB of KV. An explicit {@code -c} is nominally outside its remit, but
   * the ladder's whole premise is that a rung which does not fit produces a hard, detectable abort
   * — so leaving a memory-adjusting heuristic switched on next to it means the failure signal the
   * step-down reads could be silently absorbed instead. Off, the rung is either honoured or the
   * launch dies, which is the only shape the ladder can act on.
   */
  static List<String> memoryPlanFlags(int slots, String kvType) {
    return List.of(
        "-np", String.valueOf(slots), "-kvu", "-ctk", kvType, "-ctv", kvType, "-fa", "on", "-fit",
        "off");
  }

  /**
   * Forgets the launched-window record. A window describes a server this process is running; when
   * none is, reporting the last one is an intent presented as an outcome (register D-009).
   */
  private void clearContextWindowRecord() {
    this.contextPlan = null;
    this.contextRung = 0;
    this.contextRungReason = null;
    this.contextSlots = 0;
    this.contextKvType = null;
  }

  /**
   * Stops the managed server through {@link ChildControl} with the caller's reason and waits for
   * its recorded exit (Spec B §2.8). The user's external server is never stopped. Throws, keeping
   * the handle and the ownership, when the server survived both terminate calls.
   */
  void stopLlamaServer(StopRequest request) {
    Objects.requireNonNull(request, "request");
    if (!stopLlamaServerAndConfirm(request)) {
      throw new IllegalStateException("Managed llama-server survived terminal cleanup");
    }
  }

  /**
   * Release physical children without waiting for a transition or closing in-use dependencies:
   * the terminal fence, then one recorded stop of every owned server with {@code request}.
   */
  void stopServerForTerminalShutdown(StopRequest request, Duration timeout) {
    processOwnership.stopTerminal(request, timeout);
  }

  /**
   * The GPU barrier before a launch (Spec B §2.6): waits, at most 5 s, for the adapter memory of
   * the server stopped last to show as released. It replaces the fixed 2 s VRAM flush sleep.
   *
   * @return true when no release poll was still pending
   */
  boolean awaitGpuRelease() throws InterruptedException {
    return childControl.awaitGpuRelease(GPU_RELEASE_WAIT);
  }

  /**
   * Retries the stop of a server whose stop failed and that may still be alive (it survived both
   * terminate calls, or its stop outlived the wait), under its first intent: ChildControl keeps
   * the original reason. Throws when it is still not confirmed gone.
   */
  private void retrySurvivor() {
    ChildHandle held = child;
    if (held == null) return;
    ChildHandle.State state = held.state();
    if (state != ChildHandle.State.SURVIVED && state != ChildHandle.State.STOPPING) return;
    LOG.warn("llama-server PID {} survived its stop; retrying before a new start", held.pid());
    if (awaitStop(childControl.stop(held, SURVIVOR_RETRY), held) == null) {
      throw new IllegalStateException("Managed llama-server survived terminal cleanup");
    }
    if (child == held) child = null;
  }

  private boolean stopLlamaServerAndConfirm(StopRequest request) {
    ActiveServer stopping = activeServer;
    stopPeriodicHealthCheck();
    // No server of ours is running after this, so the launched-window record stops being true.
    clearContextWindowRecord();
    ChildHandle launched = child;
    if (usingExternal && launched == null) {
      LOG.info(
          "Not stopping llama-server: the external instance on port {} is the user's own"
              + " (justsearch.inference.external_endpoint) and is never stopped.",
          stopping != null ? stopping.port() : -1);
      // We're no longer in Online mode, so clear the "using external" state to avoid sticky
      // behavior (e.g., blocking config apply while offline/indexing).
      usingExternal = false;
      clearActive(stopping);
      return true;
    }
    if (launched == null) {
      clearActive(stopping);
      return true;
    }
    // The crash monitor stays armed (Spec B T21): this stop's own exit is REQUESTED and ignored,
    // while an exit the server makes on its own before the stop lands is still classified and
    // recorded, as UNEXPECTED.
    stoppingOwner = stopping;
    boolean wasAlive = alive(launched);
    if (wasAlive) {
      LOG.info("Stopping llama-server (PID: {}, reason {})...", launched.pid(), request.reason());
    }
    List<ProcessHandle> started =
        io.justsearch.app.inference.chat.StartedProcesses.of(launched);
    ExitOutcome outcome = awaitStop(childControl.stop(launched, request), launched);
    if (outcome == null) {
      LOG.error("llama-server PID {} remains alive; retaining its handle and ownership",
          launched.pid());
      return false;
    }
    // I6: what the server started (a wrapper's server) ends with it before the next launch.
    if (!io.justsearch.app.inference.chat.StartedProcesses.awaitExit(started, STOP_WAIT)) {
      LOG.warn("A process llama-server PID {} started is still running", launched.pid());
    }
    if (child == launched) child = null;
    clearActive(stopping);
    if (wasAlive) {
      LOG.info("llama-server stopped ({}, code {})", outcome.exitClass(),
          outcome.code().isPresent() ? outcome.code().getAsInt() : "unknown");
    }
    return true;
  }

  /**
   * Waits for a stop's recorded exit: at most both terminate waits and a margin. An interrupt does
   * not end the wait, since a launch that follows must not overlap this server; it is restored.
   * Null when the server survived.
   */
  private static ExitOutcome awaitStop(
      java.util.concurrent.CompletionStage<ExitOutcome> stop, ChildHandle stopping) {
    CompletableFuture<ExitOutcome> future = stop.toCompletableFuture();
    long deadline = System.nanoTime() + STOP_WAIT.toNanos();
    boolean interrupted = false;
    try {
      while (true) {
        try {
          return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupt) {
          interrupted = true;
        } catch (java.util.concurrent.ExecutionException survived) {
          LOG.error("Stopping llama-server PID {} failed: {}", stopping.pid(),
              String.valueOf(survived.getCause()));
          return null;
        } catch (java.util.concurrent.TimeoutException survived) {
          return null;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static boolean alive(ChildHandle handle) {
    return handle != null && !handle.exit().toCompletableFuture().isDone();
  }

  /** The recorded exit code, or -1 while alive or when the OS gave none. */
  private static int exitCode(ChildHandle handle) {
    ExitOutcome outcome = handle.exit().toCompletableFuture().getNow(null);
    return outcome == null || outcome.code().isEmpty() ? -1 : outcome.code().getAsInt();
  }

  /**
   * Waits for the freshly launched server (or the user's configured external one) to report
   * healthy.
   *
   * <p>Tempdoc 835 §9c.2: a build that cannot honour {@code --reasoning-budget} exits immediately
   * with a launch-argument rejection. That must cost thinking, not inference — so the rejection is
   * detected here, the server is relaunched once without the flag, and the verdict is recorded for
   * the runtime manifest.
   *
   * <p>Every other exit is classified ({@link LlamaServerExitClassifier}) and decided by {@link
   * ChatCrashPolicy#afterStartExit}: only a RESOURCE exit steps the window down one rung (tempdoc
   * 883 decision 1, Spec A T6), and only below a derived window; a lost port bind (ruling CH3) is
   * relaunched once on a fresh port at the SAME rung; everything else fails at once with its code,
   * never retried with the same configuration (T7, T9, T10). A health-deadline timeout never steps
   * down (T11).
   *
   * <p>The loop terminates: the reasoning relaunch can fire at most once (it sets
   * {@link ThinkingSupport#UNSUPPORTED}, which its own guard then refuses), the bind relaunch at
   * most once per start, and each step-down consumes a rung from a finite ladder.
   */
  void waitForServerHealth(StartResult expected) throws ModeTransitionException {
    Objects.requireNonNull(expected, "expected");
    while (true) {
      ActiveServer active = requireActive(expected);
      try {
        awaitServerHealth(active);
        armRuntimeMonitoring(active);
        return;
      } catch (ModeTransitionException e) {
        if (relaunchWithoutReasoningBudget(active, e)) {
          continue;
        }
        ModeTransitionException reported = e;
        if (e instanceof LlamaServerExitException exit && active.child() != null) {
          ChatCrashPolicy.Decision decision = ChatCrashPolicy.afterStartExit(
              exit.classification(), lowerContextRung().isPresent(), bindRetryUsed);
          LOG.info(
              "llama-server exited during startup: class={} evidence={} -> {} ({})",
              exit.classification().crashClass(),
              exit.classification().evidence(),
              decision.action(),
              decision.rule());
          switch (decision.action()) {
            case STEP_DOWN -> {
              if (relaunchAtLowerContextRung(active, exit)) continue;
            }
            case RESTART_SAME -> {
              if (relaunchAfterLostBind(active)) continue;
            }
            case FAIL -> reported = exit.withOutcome(exit.getMessage(), decision.failedCode());
          }
          if (decision.action() == ChatCrashPolicy.Action.FAIL
              && exit.classification().crashClass() == LlamaServerExitClassifier.CrashClass.RESOURCE) {
            logNoLowerRung();
          }
        }
        retireFailedPhysicalOwner(active);
        throw reported;
      }
    }
  }

  /**
   * One recovery health wait. Unlike initial composition, this never nests a launch ladder: no
   * context step-down and no reasoning relaunch. A lost port bind is the exception (ruling CH3
   * covers every managed launch): it is a port race, not a fault of the launch, so it is retried
   * once on a fresh port at the same rung, exactly as in {@link #waitForServerHealth}.
   */
  void waitForServerHealthOnce(StartResult expected) throws ModeTransitionException {
    Objects.requireNonNull(expected, "expected");
    while (true) {
      ActiveServer active = requireActive(expected);
      try {
        awaitServerHealth(active);
        armRuntimeMonitoring(active);
        return;
      } catch (ModeTransitionException failure) {
        if (isLostBind(active, failure) && relaunchAfterLostBind(active)) {
          continue;
        }
        retireFailedPhysicalOwner(active);
        throw failure;
      }
    }
  }

  /** Clears retained failed-attempt input only after the manager verifies the accepted owner. */
  void acceptVerifiedStart(StartRequest request, StartResult result) {
    ActiveServer owner = activeServer;
    if (owner == null || owner.start() != result || retainedStartRequest != request) {
      throw new IllegalStateException("Verified llama-server ownership changed before acceptance");
    }
    retainedStartRequest = null;
  }

  /** Active serving A wins; otherwise recovery may retry the exact retained failed initial A. */
  Optional<StartRequest> recoveryStartRequest() {
    synchronized (ownershipMonitor) {
      ActiveServer owner = activeServer;
      if (owner != null) {
        if (owner.start().disposition() == StartDisposition.ADOPTED_EXTERNAL) {
          return Optional.empty();
        }
        return Optional.of(
            new StartRequest(owner.start().context(), owner.start().adoptionPolicy()));
      }
      return Optional.ofNullable(retainedStartRequest);
    }
  }

  /** Fences the exact active/retained owner and preserves its request before physical cleanup. */
  boolean reserveRecoveryStart(StartRequest request) {
    Objects.requireNonNull(request, "request");
    synchronized (ownershipMonitor) {
      ActiveServer owner = activeServer;
      if (owner != null) {
        if (owner.start().disposition() == StartDisposition.ADOPTED_EXTERNAL
            || owner.start().context() != request.context()
            || owner.start().adoptionPolicy() != request.adoptionPolicy()) {
          return false;
        }
        retainedStartRequest = request;
        return true;
      }
      return retainedStartRequest == request;
    }
  }

  /** Exact process/config owner proof used before terminal recovery publication. */
  boolean ownsRecoveryAttempt(StartRequest request) {
    synchronized (ownershipMonitor) {
      ActiveServer owner = activeServer;
      if (owner != null) {
        return owner.start().disposition() != StartDisposition.ADOPTED_EXTERNAL
            && owner.start().context() == request.context()
            && owner.start().adoptionPolicy() == request.adoptionPolicy();
      }
      return retainedStartRequest == request;
    }
  }

  /** True while autonomous activation would steal a failed physical owner's retry. */
  boolean componentRecoveryPending() {
    synchronized (ownershipMonitor) {
      return retainedStartRequest != null
          || (activeServer != null && failureSignalledOwner == activeServer);
    }
  }

  private void awaitServerHealth(ActiveServer expected) throws ModeTransitionException {
    LOG.info("Waiting for server health (timeout={}ms)...", HEALTH_CHECK_TIMEOUT_MS);
    // Spec A T11: the deadline is monotonic, so a wall-clock jump neither ends nor extends it.
    LongSupplier clock = monotonicNanos;
    long startTime = clock.getAsLong();
    long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(HEALTH_CHECK_TIMEOUT_MS);
    long progressNanos = TimeUnit.MILLISECONDS.toNanos(HEALTH_CHECK_PROGRESS_LOG_INTERVAL_MS);
    long nextProgressLog = startTime + progressNanos;
    long resumesSeen = resumes.get();

    while (true) {
      long now = clock.getAsLong();
      // Spec A §7.4: after a resume the deadline restarts in full; suspended time never counts.
      long resumed = resumes.get();
      if (resumed != resumesSeen) {
        resumesSeen = resumed;
        startTime = now;
        nextProgressLog = now + progressNanos;
        LOG.info("The machine resumed; the start's health deadline restarts in full");
      }
      if (now - startTime >= timeoutNanos) break;
      if (now - nextProgressLog >= 0) {
        long elapsedSec = TimeUnit.NANOSECONDS.toSeconds(now - startTime);
        long remainingSec = TimeUnit.NANOSECONDS.toSeconds(timeoutNanos - (now - startTime));
        LOG.info("Still waiting for model load... {}s elapsed, {}s remaining", elapsedSec, remainingSec);
        nextProgressLog = now + progressNanos;
      }
      ChildHandle expectedChild = expected.child();
      if (expectedChild != null && !alive(expectedChild)) {
        int exitCode = exitCode(expectedChild);
        LlamaServerExitClassifier.Classification classification =
            LlamaServerExitClassifier.classify(
                exitCode, readLaunchOutputBestEffort(expected.start().context()),
                expected.start().context().devicePlan().gpuLayers() > 0);
        noteStartupExit(expectedChild, exitCode, classification);
        if (exitCode == WINDOWS_STATUS_DLL_NOT_FOUND) {
          Path logFile = resolveLlamaServerLogFile(expected.start().context());
          Path executable = expected.start().context().inference().serverExecutable();
          Path exeDir = executable.getParent();
          throw new LlamaServerExitException(
              ModeTransitionException.Reason.MISSING_DLL,
              "llama-server failed to start (missing DLL dependencies, 0xC0000135). "
                  + "This usually means the llama-server folder is missing required native DLLs"
                  + " (llama.dll / ggml*.dll / mtmd.dll) "
                  + "or the Visual C++ runtime is not available. "
                  + "Try 'Repair AI' and retry Online Mode. "
                  + "llama-server dir="
                  + (exeDir != null ? exeDir : executable)
                  + ", log="
                  + logFile,
              exitCode,
              classification);
        }
        String diagnostic = diagnoseServerFailure(expected.start().context(), exitCode);
        if (classification.evidence() != null) {
          diagnostic = diagnostic + " [" + classification.crashClass() + ": "
              + classification.evidence() + "]";
        }
        throw new LlamaServerExitException(
            ModeTransitionException.Reason.PROCESS_EXITED, diagnostic, exitCode, classification);
      }
      HealthProbe probe = probeHealth(expected, STARTUP_HEALTH_PROBE_TIMEOUT);
      if (probe.ok()) {
        if (!ownedPhysicalServerAlive(expected)) {
          throw new ModeTransitionException(
              ModeTransitionException.Reason.PROCESS_EXITED,
              "Managed llama-server exited during startup health verification");
        }
        LOG.info("Server healthy");
        // The build accepted --reasoning-budget: it started and serves with the flag present.
        if (reasoningBudgetRequested) {
          thinkingSupport.compareAndSet(ThinkingSupport.UNKNOWN, ThinkingSupport.SUPPORTED);
        }
        logHealthBodyBestEffort(probe.body());
        logServerProperties(expected); // Log actual server config for debugging
        return;
      }
      if (probe.error() != null) {
        LOG.debug("Health check poll failed: {}", probe.error());
      }

      try {
        Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ModeTransitionException(
            ModeTransitionException.Reason.HEALTH_CHECK_INTERRUPTED,
            "Health check interrupted",
            e);
      }
    }

    throw new ModeTransitionException(
        ModeTransitionException.Reason.HEALTH_CHECK_TIMEOUT,
        "Server health check timeout after " + HEALTH_CHECK_TIMEOUT_MS + "ms");
  }

  /**
   * Fail-closed-on-thinking recovery: when the startup failure was llama-server refusing
   * {@code --reasoning-budget}, relaunch the same command without that flag and record the
   * {@link ThinkingSupport#UNSUPPORTED} verdict. Returns false — leaving the original failure to
   * propagate — for every other cause, and if the relaunch itself cannot be attempted.
   */
  private boolean relaunchWithoutReasoningBudget(
      ActiveServer expected, ModeTransitionException failure) {
    if (failure.reason() != ModeTransitionException.Reason.PROCESS_EXITED
        || !reasoningBudgetRequested
        || thinkingSupport.get() == ThinkingSupport.UNSUPPORTED
        || !lastLaunchCommand.contains("--reasoning-budget")) {
      return false;
    }
    if (!LlamaServerArgRejection.isReasoningBudgetRejection(
        readLaunchOutputBestEffort(expected.start().context()))) {
      return false;
    }

    LOG.warn(
        "llama-server rejected --reasoning-budget: this build cannot generate reasoning. Retrying"
            + " without the flag — inference stays available, thinking does not. Reasoning"
            + " requests will be answered without a reasoning trace.");
    thinkingSupport.set(ThinkingSupport.UNSUPPORTED);
    reasoningBudgetRequested = false;

    List<String> retry = new ArrayList<>();
    for (int i = 0; i < lastLaunchCommand.size(); i++) {
      if ("--reasoning-budget".equals(lastLaunchCommand.get(i))) {
        i++; // also drop the value
        continue;
      }
      retry.add(lastLaunchCommand.get(i));
    }
    if (crashMonitor != null) {
      crashMonitor.cancel(true);
      crashMonitor = null;
    }
    try {
      LOG.debug("Restarting server without --reasoning-budget: {}", String.join(" ", retry));
      if (!isActive(expected)) {
        return false;
      }
      retireFailedPhysicalOwner(expected);
      launchOnFreshPort(retry, expected.start());
      return true;
    } catch (IOException | ModeTransitionException e) {
      LOG.error("Relaunch without --reasoning-budget failed: {}", e.getMessage());
      return false;
    }
  }

  /** Whether this startup exit was llama-server failing to bind its HTTP port (ruling CH3). */
  private static boolean isLostBind(ActiveServer expected, ModeTransitionException failure) {
    return expected.child() != null
        && failure instanceof LlamaServerExitException exit
        && exit.classification().crashClass() == LlamaServerExitClassifier.CrashClass.PORT_LOST;
  }

  /**
   * Port-race recovery (ruling CH3): the fresh port was taken between choosing it and llama-server
   * binding it. Relaunch the same command, at the same context rung, on another fresh port. At most
   * once per start; a second lost bind is reported as the launch failure, never as a window that
   * does not fit.
   */
  private boolean relaunchAfterLostBind(ActiveServer expected) {
    if (bindRetryUsed || lastLaunchCommand.isEmpty()) {
      LOG.error(
          "llama-server again could not bind its loopback port {}; reporting the launch failure"
              + " (a lost bind is retried once, and never reduces the context window).",
          expected.port());
      return false;
    }
    bindRetryUsed = true;
    LOG.warn(
        "llama-server could not bind loopback port {}: another process took it after it was"
            + " chosen. Relaunching once on a fresh port at the same context rung {}.",
        expected.port(),
        contextRung);
    if (crashMonitor != null) {
      crashMonitor.cancel(true);
      crashMonitor = null;
    }
    try {
      if (!isActive(expected)) {
        return false;
      }
      retireFailedPhysicalOwner(expected);
      launchOnFreshPort(lastLaunchCommand, expected.start());
      return true;
    } catch (IOException | ModeTransitionException e) {
      LOG.error("Relaunch after a lost port bind failed: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Context-ladder recovery (tempdoc 883 decision 1, Spec A T6): when the startup exit was
   * classified RESOURCE — the window or the model did not fit — relaunch at the next lower rung and
   * record why. The rung reached is sticky for this declared configuration.
   *
   * <p>Gated on the RESOURCE class, not on any exit: an unknown architecture, a missing file or a
   * bare crash would fail at every rung, and walking the ladder for them only hides the cause
   * behind three more launches (Spec A §7.2). A health-check timeout is not a step-down trigger
   * either: it says the model is loading slowly, not that the window does not fit (T11).
   *
   * <p>Returns false — leaving the original failure to propagate — for the user's external server
   * (we did not choose its window), for an operator's explicit override (a one-rung ladder: honour
   * it or fail loud), and once the ladder is exhausted.
   */
  private boolean relaunchAtLowerContextRung(
      ActiveServer expected, LlamaServerExitException failure) {
    ContextWindowPolicy.Plan plan = contextPlan;
    if (failure.classification().crashClass() != LlamaServerExitClassifier.CrashClass.RESOURCE
        || plan == null
        || usingExternal
        || lastLaunchCommand.isEmpty()) {
      return false;
    }
    OptionalInt lower = plan.nextRungBelow(contextRung);
    if (lower.isEmpty() || ContextWindowPolicy.REASON_OVERRIDE.equals(plan.reason())) {
      return false;
    }

    int next = lower.getAsInt();
    List<String> retry = withFlagValue(lastLaunchCommand, "-c", String.valueOf(next));
    if (retry == null) {
      return false;
    }

    LOG.warn(
        "llama-server did not start at context rung {}; stepping down to {} (planned top rung {},"
            + " ladder {}). The window is a derived resource — a rung that does not fit costs"
            + " context, not inference.",
        contextRung,
        next,
        plannedTopRung(plan),
        plan.ladder());

    if (crashMonitor != null) {
      crashMonitor.cancel(true);
      crashMonitor = null;
    }
    try {
      if (!isActive(expected)) {
        return false;
      }
      retireFailedPhysicalOwner(expected);
      launchOnFreshPort(retry, expected.start());
    } catch (IOException | ModeTransitionException e) {
      LOG.error("Relaunch at context rung {} failed: {}", next, e.getMessage());
      return false;
    }
    this.contextRung = next;
    this.contextRungReason = ContextWindowPolicy.steppedFrom(plannedTopRung(plan));
    String declaredHash = expected.start().declaredConfigHash();
    if (declaredHash != null) steppedRung = new SteppedRung(declaredHash, next);
    return true;
  }

  /**
   * A copy of {@code command} with the value token after {@code flag} replaced, or null when the
   * flag is absent or has no value token (in which case the caller must not pretend it retried).
   */
  private static List<String> withFlagValue(List<String> command, String flag, String value) {
    int index = command.indexOf(flag);
    if (index < 0 || index + 1 >= command.size()) {
      return null;
    }
    List<String> copy = new ArrayList<>(command);
    copy.set(index + 1, value);
    return copy;
  }

  /**
   * The window plan for this launch: an operator's explicit {@code justsearch.context.size} (any
   * source above {@code ORDINAL_AUTO_DETECT} — YAML, settings.json, env or {@code -D}) is honoured
   * as a one-rung ladder; otherwise the backend's derived ladder.
   */
  private static ContextWindowPolicy.Plan planContextWindow(
      ResolvedConfig rc, boolean gpuBacked, Long freeVramBytes) {
    // Provenance AND value come from the same ConfigResolution: reading the ordinal here and the
    // number from InferenceConfig would be two reads of one fact that can disagree (the config is
    // built once at activation; the resolution is live).
    ConfigResolution resolution = rc.resolution("justsearch.context.size");
    if (resolution == null || !resolution.isResolved()) {
      return ContextWindowPolicy.auto(gpuBacked, freeVramBytes);
    }
    if (resolution.sourceOrdinal() <= ResolvedConfigBuilder.ORDINAL_AUTO_DETECT) {
      if (rc.ai() == null || rc.ai().gpuMemoryPlan().isBlank()) {
        return ContextWindowPolicy.auto(gpuBacked, freeVramBytes);
      }
      // Spec E step E7b: a planned profile's rung (ordinal 150) tops the derived ladder.
      try {
        return ContextWindowPolicy.auto(
            gpuBacked, freeVramBytes, Integer.parseInt(resolution.value().trim()));
      } catch (NumberFormatException | NullPointerException notARung) {
        return ContextWindowPolicy.auto(gpuBacked, freeVramBytes);
      }
    }
    int tokens;
    try {
      tokens = Integer.parseInt(resolution.value().trim());
    } catch (NumberFormatException e) {
      LOG.warn(
          "justsearch.context.size={} from {} is not a number; deriving the window instead.",
          resolution.value(),
          resolution.sourceName());
      return ContextWindowPolicy.auto(gpuBacked, freeVramBytes);
    }
    if (tokens <= 0) {
      return ContextWindowPolicy.auto(gpuBacked, freeVramBytes);
    }
    return ContextWindowPolicy.override(tokens, freeVramBytes);
  }

  /**
   * What window this process actually launched with, or null when it launched nothing (no server
   * yet, or an adopted external one). Published on {@code /api/inference/status}; {@code /props}
   * remains the authority for what the server reports back.
   */
  OnlineAiRuntimeIntrospection.ContextWindow contextWindow() {
    ContextWindowPolicy.Plan plan = contextPlan;
    if (plan == null || contextRung <= 0) {
      return null;
    }
    return new OnlineAiRuntimeIntrospection.ContextWindow(
        contextRung, contextRungReason, plan.freeVramBytes(), contextSlots, contextKvType);
  }

  /** The window this process requested at launch, or 0 when it launched nothing. */
  int requestedContextTokens() {
    return contextRung;
  }

  /** llama-server output written since the last launch — empty when the log is unreadable. */
  private String readLaunchOutputBestEffort(LlamaServerConfigContext context) {
    try {
      Path logFile = resolveLlamaServerLogFile(context);
      if (!Files.exists(logFile)) {
        return "";
      }
      long size = Files.size(logFile);
      long from = Math.max(lastLaunchLogOffset, size - DIAG_TAIL_BYTES);
      if (size <= from) {
        return "";
      }
      try (var channel = Files.newByteChannel(logFile)) {
        channel.position(from);
        byte[] buf = new byte[(int) Math.min(size - from, DIAG_TAIL_BYTES)];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
        int read = channel.read(bb);
        return read <= 0 ? "" : new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      LOG.debug("Failed to read llama-server launch output: {}", e.getMessage());
      return "";
    }
  }

  /**
   * Whether the running server can honour reasoning generation (tempdoc 835) — published on the
   * runtime manifest's {@code ai.thinkingSupport}.
   */
  String thinkingSupport() {
    return thinkingSupport.get().name();
  }

  // ==================== External Server (explicit endpoint only) ====================

  /**
   * ADR-0051 decision 2, ruling CH4: the user's own llama-server is used only when {@code
   * justsearch.inference.external_endpoint} names it. Returns null when this start must launch a
   * managed server instead.
   *
   * <p>Without the setting, a healthy server on the configured port is NOT adopted (that is how a
   * crashed Engine's unregistered server used to be mistaken for the user's own). It is logged once
   * and reported as {@code external_server_ignored}, so a user who relied on auto-adoption sees
   * why, and the managed launch proceeds on a fresh port.
   */
  private StartResult useConfiguredExternalServer(StartRequest request)
      throws IOException, ModeTransitionException {
    LlamaServerConfigContext context = request.context();
    Optional<ExternalInferenceEndpoint> configured;
    try {
      configured = ExternalInferenceEndpoint.parse(externalEndpointSetting(context));
    } catch (IllegalArgumentException refused) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.INVALID_CONFIG, refused.getMessage());
    }
    if (configured.isEmpty()) {
      noteServerOnConfiguredPort(context.inference().serverPort());
      return null;
    }
    ignoredExternalServerPort = 0;
    ExternalInferenceEndpoint endpoint = configured.get();
    if (request.adoptionPolicy() == AdoptionPolicy.MANAGED_DETACHED_FROM_EXTERNAL) {
      return null;
    }
    if (request.adoptionPolicy() == AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.EXTERNAL_SERVER_CONFLICT,
          ExternalInferenceEndpoint.CONFIG_KEY
              + " is set to "
              + endpoint.url()
              + "; strict configuration apply requires a managed declared-config witness. Clear"
              + " the setting to let JustSearch run its own llama-server.");
    }
    if (policyDisallowExternalInferenceServers()) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.EXTERNAL_SERVER_POLICY_BLOCKED,
          "External inference servers are disallowed by administrator policy. Clear "
              + ExternalInferenceEndpoint.CONFIG_KEY
              + " (currently "
              + endpoint.url()
              + ") and retry Online mode.");
    }
    if (!probeHealth(endpoint.port(), HEALTH_PROBE_TIMEOUT).ok()) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.ONLINE_START_FAILED,
          "The llama-server configured by "
              + ExternalInferenceEndpoint.CONFIG_KEY
              + " ("
              + endpoint.url()
              + ") is not answering /health. Start it, or clear the setting to let JustSearch run"
              + " its own.");
    }

    // Validate the external server identity before using it. This prevents treating a random
    // HTTP service that happens to return 200 for /health as a llama-server.
    PropsProbe probe = probeServerProps(endpoint.port(), PROPS_PROBE_TIMEOUT);
    if (!probe.looksLikeLlamaServer()) {
      if (!allowHealthOnlyExternalAdoption()) {
        throw new IOException(
            endpoint.url()
                + " is serving HTTP 200 on /health, but it does not look like a llama-server"
                + " (missing or invalid /props). Point "
                + ExternalInferenceEndpoint.CONFIG_KEY
                + " at a llama-server, clear it, or (dev only) set -D"
                + ALLOW_HEALTH_ONLY_EXTERNAL_ADOPTION_PROP
                + "=true to use it anyway.");
      }
      LOG.warn(
          "External server {} is healthy but /props is missing/unparseable. "
              + "Proceeding with health-only use due to {}=true.",
          endpoint.url(),
          ALLOW_HEALTH_ONLY_EXTERNAL_ADOPTION_PROP);
    }

    StartResult result =
        new StartResult(
            context,
            request.adoptionPolicy(),
            StartDisposition.ADOPTED_EXTERNAL,
            null);
    useExternalServer(probe, result, endpoint);
    return result;
  }

  private static String externalEndpointSetting(LlamaServerConfigContext context) {
    ResolvedConfig resolved = context.resolved();
    if (resolved == null || resolved.ai() == null) return null;
    return resolved.ai().inferenceExternalEndpoint();
  }

  /** CH4: a healthy server on the configured port is reported, never adopted. */
  private void noteServerOnConfiguredPort(int configuredPort) {
    if (configuredPort <= 0 || !probeHealth(configuredPort, HEALTH_PROBE_TIMEOUT).ok()) {
      ignoredExternalServerPort = 0;
      return;
    }
    if (ignoredExternalServerPort != configuredPort) {
      LOG.warn(
          "A server is answering on the configured llama-server port {}. JustSearch no longer"
              + " adopts a server it did not start (ADR-0051): it runs its own llama-server on a"
              + " fresh loopback port and leaves this one alone. To use your own server, set {}"
              + " (JUSTSEARCH_INFERENCE_EXTERNAL_ENDPOINT) to http://127.0.0.1:{}.",
          configuredPort,
          ExternalInferenceEndpoint.CONFIG_KEY,
          configuredPort);
    }
    ignoredExternalServerPort = configuredPort;
  }

  /**
   * Status note while a healthy server on the configured port is being ignored, else null. The
   * value is the wire code {@code external_server_ignored}.
   */
  String externalServerNote() {
    return ignoredExternalServerPort > 0 ? EXTERNAL_SERVER_IGNORED_NOTE : null;
  }

  static final String EXTERNAL_SERVER_IGNORED_NOTE = "external_server_ignored";

  private void useExternalServer(
      PropsProbe probe, StartResult result, ExternalInferenceEndpoint endpoint) throws IOException {
    processOwnership.requireLaunchAllowed();
    usingExternal = true;
    child = null;
    ActiveServer owner = installActive(result, null, endpoint.port());
    // We launched nothing, so launch-argument acceptance says nothing about this server.
    reasoningBudgetRequested = false;
    thinkingSupport.set(ThinkingSupport.UNKNOWN);
    propsOps.resetExternalAdoptionState(
        probe.looksLikeLlamaServer(), probe.looksLikeLlamaServer() ? null : probe.error());
    LOG.info(
        "Using the user's own llama-server at {} ({}). JustSearch reports it and never stops it.",
        endpoint.url(),
        ExternalInferenceEndpoint.CONFIG_KEY);
    try {
      // Best-effort: capture model/context details for diagnostics and request routing.
      if (probe.propsRoot() != null) {
        publishProps(owner, probe.propsRoot());
      } else {
        logServerProperties(owner);
      }
    } catch (Exception e) {
      LOG.debug(
          "Failed to fetch llama-server properties from external instance: {}", e.getMessage());
    }
  }

  // ==================== Process Launch Helpers ====================

  /**
   * Relaunches {@code command} on a fresh loopback port (ADR-0051 decision 2): every managed
   * launch, including a same-configuration retry, gets its own port.
   */
  private void launchOnFreshPort(List<String> command, StartResult result)
      throws IOException, ModeTransitionException {
    int port = allocateLaunchPort();
    List<String> argv = withFlagValue(command, "--port", String.valueOf(port));
    if (argv == null) {
      throw new IOException("llama-server launch command has no --port value to replace");
    }
    launchManagedLlamaServer(argv, result, port);
  }

  private void launchManagedLlamaServer(List<String> command, StartResult result, int port)
      throws IOException, ModeTransitionException {
    // Tempdoc 656 Task 3: a missing llama-server.exe previously failed inside ProcessBuilder.start()
    // below with a raw IOException (Windows error 2), which propagates past every typed handler and
    // is caught generically by InferenceLifecycleManager, collapsing into the coarse
    // TransitionCode.ONLINE_START_FAILED with the real cause preserved only as free text. This
    // pre-launch check mirrors the existing MISSING_DLL pattern (detect a specific missing-artifact
    // condition, throw a typed reason) but at an earlier failure point — before a process exists,
    // rather than by inspecting an already-launched process's exit code.
    LlamaServerConfigContext context = result.context();
    Path exe = context.inference().serverExecutable();
    if (exe == null || !Files.isRegularFile(exe)) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.EXECUTABLE_NOT_FOUND,
          "llama-server executable not found at "
              + exe
              + ". Try 'Repair AI' or reinstall the AI runtime, then retry Online Mode.");
    }
    lastLaunchCommand = List.copyOf(command);
    // Ensure the DLL search path includes the llama-server directory and the bundled runtime/bin
    // directory (which often contains the VC++ runtime DLLs inside our packaged headless runtime
    // image).
    Path serverExeDir = context.inference().serverExecutable().getParent();
    Path logFile = prepareServerLog(resolveLlamaServerLogFile(context));
    ChildSpec spec = new ChildSpec(
        ChildKind.LLAMA_SERVER,
        command,
        serverExeDir != null && Files.isDirectory(serverExeDir) ? serverExeDir : null,
        launchEnvironment(),
        new ChildSpec.Stdio.AppendToLog(logFile),
        gpuLayersOf(command) > 0,
        java.util.OptionalLong.empty(),
        Optional.empty(),
        ChildSpec.CooperativeStop.NONE,
        "llama-server",
        runtimeDllPath(serverExeDir, context));
    LOG.debug("Starting server with command: {}", String.join(" ", command));
    startManagedChild(spec, logFile, result, port);
  }

  /** The {@code -ngl} value of a launch command; 0 when absent or unreadable. */
  static int gpuLayersOf(List<String> command) {
    int index = command.indexOf("-ngl");
    if (index < 0 || index + 1 >= command.size()) return 0;
    try {
      return Integer.parseInt(command.get(index + 1).trim());
    } catch (NumberFormatException unreadable) {
      return 0;
    }
  }

  /**
   * The child's environment changes: the captured device plan. Spec E step E6: a pinned GPU
   * launch sees only the pinned device; CPU launches override inherited CUDA visibility; retries
   * retain the initial device namespace. The DLL search path is the spec's PATH prefix ({@link
   * #runtimeDllPath}), which child control joins to the inherited value.
   */
  private Map<String, String> launchEnvironment() {
    return Objects.requireNonNull(launchDevicePlan, "launch device plan").environment();
  }

  /**
   * The DLL directories a launch puts in front of the child's inherited {@code PATH}: the server's
   * own directory, then the bundled runtime {@code bin} (VC++ runtime DLLs in the packaged image).
   * Child control joins them to the inherited value, so this reads no process environment.
   */
  private List<Path> runtimeDllPath(Path serverExeDir, LlamaServerConfigContext context) {
    try {
      List<Path> prefixes = new ArrayList<>();
      if (serverExeDir != null) prefixes.add(serverExeDir.toAbsolutePath().normalize());
      Path runtimeBin = resolveBundledRuntimeBinDirBestEffort(context);
      if (runtimeBin != null) prefixes.add(runtimeBin.toAbsolutePath().normalize());
      return List.copyOf(prefixes);
    } catch (Exception e) {
      // Best-effort: do not fail startup on PATH adjustments.
      LOG.debug("Failed to adjust PATH for llama-server: {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * Prepares {@code <home|dataDir>/logs/llama-server.log}, which the launch appends both output
   * streams to, retaining a bounded number of generations. Package-private (and taking the
   * resolved path) so the retention policy is testable through the SAME method the launch path
   * calls, rather than through a helper a future edit could stop calling.
   */
  Path prepareServerLog(Path logFile) throws IOException {
    Files.createDirectories(logFile.getParent());
    rotateServerLogGenerations(logFile);
    // Remember where this launch starts writing, so launch-argument rejections are read from THIS
    // run's output rather than a previous run's tail. Read AFTER rotation: rotation moves the
    // previous log aside, so this launch starts at offset 0 of a fresh file.
    lastLaunchLogOffset = Files.exists(logFile) ? Files.size(logFile) : 0L;
    return logFile;
  }

  /**
   * Rotate {@code llama-server.log} → {@code .1} → {@code .2} on each launch, deleting what falls
   * off the end. Before this the file was appended to across every restart of the process's life
   * with nothing pruning it — unbounded growth of a file whose verbosity belongs to llama-server,
   * not to us, and which can therefore carry prompt-shaped diagnostics.
   *
   * <p>Rotate-on-start (rather than a size cap) mirrors the policy the app ran for its other
   * supervised process log — the Worker's {@code worker.log} → {@code .1} → {@code .2} (deleted
   * with that process at lane F stage A item A11), itself mirroring the Shell's {@code lib.rs}
   * rotation of {@code engine.log} (still live, still the same shape). It also keeps the useful property for free: the previous
   * launch's output survives exactly one restart, which is what post-mortem reading needs.
   * Best-effort: a rotation that fails must never stop the server from starting.
   *
   * <p><b>The live file moves FIRST, and nothing is pruned until it has.</b> On Windows a rename
   * fails while a handle is still open — a llama-server that outlived the 5s kill timeout, an
   * orphan, or a handle the OS has not released yet — and that is exactly the crash-restart loop
   * where retention matters most. Pruning first would then have already deleted the oldest
   * generation and shifted the rest, leaving FEWER retained logs and a live file still growing:
   * the failure would silently degrade the very guarantee it was meant to keep. So the live file is
   * renamed aside to {@code .rotating} before anything is deleted; if that rename throws, every
   * existing generation is untouched and the only cost is that this launch appends to the previous
   * log. A {@code .rotating} left behind by a failure between the two renames is bounded — the next
   * launch replaces it — and is covered by the row's {@code logs/llama-server.log.*} glob.
   *
   * <p>The partial case, traced rather than assumed: if the {@code .1 → .2} shift fails AFTER
   * {@code .2} was pruned, the just-rotated log is stranded in {@code .rotating} and the next launch
   * replaces it, so that one generation is lost — while {@code .1} still holds the older log and the
   * live file is fresh. Bounded and best-effort by design: this is a diagnostic artifact whose row
   * declares {@code ROTATE_OR_PRUNE_DIAGNOSTIC_ARTIFACT}, so losing a generation to a filesystem
   * error costs post-mortem depth, never user state — and never the server's start.
   */
  private static void rotateServerLogGenerations(Path logFile) {
    if (!Files.exists(logFile)) {
      return;
    }
    Path rotating = logFile.resolveSibling(logFile.getFileName() + ".rotating");
    try {
      // The one operation that can fail on a live handle. Nothing is destroyed before it succeeds.
      Files.move(logFile, rotating, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      LOG.debug(
          "llama-server.log could not be rotated away ({}); retained generations left intact",
          e.getMessage());
      return;
    }
    try {
      Files.deleteIfExists(logGeneration(logFile, RETAINED_LOG_GENERATIONS - 1));
      for (int generation = RETAINED_LOG_GENERATIONS - 2; generation >= 1; generation--) {
        Path older = logGeneration(logFile, generation);
        if (Files.exists(older)) {
          Files.move(
              older,
              logGeneration(logFile, generation + 1),
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      }
      Files.move(
          rotating, logGeneration(logFile, 1), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      LOG.debug("llama-server.log rotation failed (best-effort): {}", e.getMessage());
    }
  }

  /** {@code llama-server.log.<n>} — generation 1 is the previous launch. */
  private static Path logGeneration(Path logFile, int generation) {
    return logFile.resolveSibling(logFile.getFileName() + "." + generation);
  }

  /**
   * Starts the server through {@link ChildControl}, which records the spawn, registers the
   * manifest entry and, when registration is refused, stops the child ({@code ROLLBACK}) before
   * throwing. Nothing here kills a process. Every launch (first start and relaunch alike) first
   * retries a server whose stop failed, and refuses while it may still be alive. A rolled-back
   * server whose stop did not confirm its exit stays held, as one that survived a stop does: the
   * next stop retries it and fails while it lives, and no start launches beside it.
   */
  private void startManagedChild(ChildSpec spec, Path logFile, StartResult result, int port)
      throws IOException {
    retrySurvivor();
    InferenceConfig cfg = result.context().inference();
    ChildControl.ManifestEntry entry = new ChildControl.ManifestEntry(
        "http://127.0.0.1:" + port, cfg.modelPath().toString(), result.declaredConfigHash());
    ChildHandle started;
    try {
      started = processOwnership.launch(() -> childControl.start(spec, entry));
    } catch (ChildRegistrationRefusedException refused) {
      refused.survivor().ifPresent(survivor -> {
        child = survivor;
        LOG.error("Unregistered llama-server PID {} survived registration rollback; retaining"
            + " its handle", survivor.pid());
      });
      throw refused;
    }
    child = started;
    LOG.info("llama-server logs: {}", logFile);
    LOG.info("llama-server started, PID: {}, port: {}", started.pid(), port);
    installActive(result, started, port);
  }

  private void armRuntimeMonitoring(ActiveServer owner)
      throws ModeTransitionException {
    synchronized (ownershipMonitor) {
      if (!isActive(owner) || !ownedPhysicalServerAlive(owner)) {
        throw new ModeTransitionException(
            ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
            "llama-server ownership changed before runtime monitoring was armed");
      }
    }
    stopPeriodicHealthCheck();
    if (crashMonitor != null) {
      crashMonitor.cancel(true);
      crashMonitor = null;
    }
    readyMark = new ReadyMark(owner, monotonicNanos.getAsLong());
    schedulePeriodicHealthCheck(owner);
    ChildHandle started = owner.child();
    if (started != null) {
      monitorLaunchedManagedChild(started, owner);
    }
  }

  private static boolean ownedPhysicalServerAlive(ActiveServer owner) {
    if (owner.start().disposition() == StartDisposition.ADOPTED_EXTERNAL) return true;
    return alive(owner.child());
  }

  /**
   * The one exit watcher (Spec B §2.2, C3): it sees every exit with its recorded class. A
   * REQUESTED exit is a stop someone asked for and is never a crash. An exit of the owner a
   * planned stop is ending is the stop's business, whatever its class. Anything else while the
   * owner is active is a crash.
   */
  private void monitorLaunchedManagedChild(ChildHandle started, ActiveServer owner) {
    crashMonitor =
        started.exit().toCompletableFuture().whenCompleteAsync(
            (outcome, failure) -> {
              if (outcome == null || outcome.exitClass() == ExitClass.REQUESTED) return;
              int code = outcome.code().isPresent() ? outcome.code().getAsInt() : -1;
              if (stoppingOwner == owner || !isActive(owner)) {
                LOG.warn("llama-server PID {} exited on its own ({}, code {}) while being stopped",
                    started.pid(), outcome.exitClass(), code);
                return;
              }
              LOG.error("llama-server exited unexpectedly with code {} ({})", code,
                  outcome.exitClass());
              handleManagedCrash(owner);
            }, exitExecutor);
  }

  private void retireFailedPhysicalOwner(ActiveServer expected) {
    if (!isActive(expected) || ownedPhysicalServerAlive(expected)) return;
    if (child == expected.child()) child = null;
  }

  /** The owner's diagnosis of a startup exit, as a bounded {@code child-note} token. */
  private void noteStartupExit(
      ChildHandle exited, int exitCode, LlamaServerExitClassifier.Classification classification) {
    String token;
    if (exitCode == WINDOWS_STATUS_DLL_NOT_FOUND) {
      token = "dll_not_found";
    } else if (classification.crashClass() == LlamaServerExitClassifier.CrashClass.RESOURCE) {
      token = "oom";
    } else {
      token = "startup_failed";
    }
    try {
      childControl.note(exited, token);
    } catch (RuntimeException unrecorded) {
      LOG.debug("Could not record the llama-server startup note: {}", unrecorded.getMessage());
    }
  }

  // ==================== Health Probing ====================

  record PropsProbe(boolean ok, JsonNode propsRoot, String error) {
    boolean looksLikeLlamaServer() {
      if (!ok || propsRoot == null) return false;
      return ServerPropsOps.looksLikeLlamaServerProps(propsRoot);
    }
  }

  record HealthProbe(boolean ok, int statusCode, String error, String body) {}

  private HealthProbe probeHealth(ActiveServer expected, Duration timeout) {
    if (!isActive(expected)) return staleHealthProbe();
    HealthProbe probe = probeHealth(expected.port(), timeout);
    return isActive(expected) ? probe : staleHealthProbe();
  }

  private HealthProbe probeHealth(int port, Duration timeout) {
    try {
      HttpRequest request = buildGetRequest(port, PATH_HEALTH, timeout);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      boolean ok = response.statusCode() == 200;
      return new HealthProbe(
          ok, response.statusCode(), ok ? null : "HTTP " + response.statusCode(), response.body());
    } catch (Exception e) {
      return new HealthProbe(
          false, -1, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
    }
  }

  private static HealthProbe staleHealthProbe() {
    return new HealthProbe(false, -1, "stale llama-server owner", null);
  }

  private PropsProbe probeServerProps(ActiveServer expected, Duration timeout) {
    if (!isActive(expected)) return stalePropsProbe();
    PropsProbe probe = probeServerProps(expected.port(), timeout);
    return isActive(expected) ? probe : stalePropsProbe();
  }

  private PropsProbe probeServerProps(int port, Duration timeout) {
    try {
      HttpRequest request = buildGetRequest(port, PATH_PROPS, timeout);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return new PropsProbe(false, null, "HTTP " + response.statusCode());
      }
      JsonNode root = objectMapper.readTree(response.body());
      return new PropsProbe(true, root, null);
    } catch (Exception e) {
      return new PropsProbe(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static PropsProbe stalePropsProbe() {
    return new PropsProbe(false, null, "stale llama-server owner");
  }

  // ==================== Props Interpretation (delegated to ServerPropsOps) ====================

  /**
   * Best-effort parse of the /health response body for richer diagnostics.
   * Extracts status, slots_idle, slots_processing when available.
   */
  private void logHealthBodyBestEffort(String body) {
    if (body == null || body.isBlank()) return;
    try {
      JsonNode root = objectMapper.readTree(body);
      String status = root.has("status") ? root.get("status").asText() : null;
      int slotsIdle = root.has("slots_idle") ? root.get("slots_idle").asInt(-1) : -1;
      int slotsProcessing =
          root.has("slots_processing") ? root.get("slots_processing").asInt(-1) : -1;
      LOG.debug(
          "llama-server /health: status={}, slots_idle={}, slots_processing={}",
          status,
          slotsIdle,
          slotsProcessing);
    } catch (Exception e) {
      LOG.debug("Failed to parse /health body (best-effort): {}", e.getMessage());
    }
  }

  /**
   * Logs llama-server properties for debugging context size issues. Queries /props endpoint to get
   * actual server configuration.
   */
  private void logServerProperties(ActiveServer expected) {
    PropsProbe probe = probeServerProps(expected, PROPS_PROBE_TIMEOUT);
    if (probe.ok()) {
      publishProps(expected, probe.propsRoot());
    } else {
      LOG.debug("Could not fetch server properties: {}", probe.error());
    }
  }

  void updateFromPropsBestEffort(JsonNode root) {
    ActiveServer owner = activeServer;
    if (owner != null) publishProps(owner, root);
  }

  // ==================== Periodic Health Monitoring (Fix #6) ====================

  /**
   * Schedules periodic health checks for the running llama-server.
   *
   * <p>If 3 consecutive health checks fail, treats the server as hung (Spec A T30): the failure is
   * signalled and the chat crash policy decides, a hang counting as an OTHER exit. This detects
   * processes that are still alive but not responding to HTTP requests.
   *
   * <p>Fixed delay, not fixed rate (Spec A §3.3): after a sleep or a stalled probe the next probe
   * waits a full interval instead of firing the overdue ones back to back, which would count three
   * failures in a burst against a server that is merely waking up.
   */
  private void schedulePeriodicHealthCheck(ActiveServer owner) {
    synchronized (ownershipMonitor) {
      if (activeServer != owner) return;
      stopPeriodicHealthCheckLocked();
      resetHealthDiagnosticsLocked();
      periodicHealthTask =
          healthScheduler.scheduleWithFixedDelay(
              () -> runPeriodicHealthCheck(owner),
              PERIODIC_HEALTH_INTERVAL_MS,
              PERIODIC_HEALTH_INTERVAL_MS,
              TimeUnit.MILLISECONDS);
    }

    LOG.debug("Scheduled periodic health checks every {}ms", PERIODIC_HEALTH_INTERVAL_MS);
  }

  private void runPeriodicHealthCheck(ActiveServer owner) {
    boolean external = owner.start().disposition() == StartDisposition.ADOPTED_EXTERNAL;
    if (currentMode.get() != Mode.ONLINE) return;
    if (!external && !hasLiveManagedProcess()) return;

    HealthProbe probe = probeHealth(owner, PERIODIC_HEALTH_TIMEOUT);
    if (probe.ok()) {
      int prev;
      synchronized (ownershipMonitor) {
        if (activeServer != owner) return;
        prev = consecutiveHealthFailures.getAndSet(0);
        lastPeriodicHealthOkAtMs.set(System.currentTimeMillis());
        lastPeriodicHealthError.set(null);
      }
      if (prev > 0) {
        LOG.info("llama-server health recovered after {} failures", prev);
        try {
          events.onHealthRecovered(prev);
        } catch (RuntimeException ex) {
          LOG.warn("Telemetry events.onHealthRecovered threw: {}", ex.getMessage());
        }
      }
      return;
    }
    String reason =
        probe.error() != null
            ? probe.error()
            : (probe.statusCode() > 0 ? "HTTP " + probe.statusCode() : "health probe failed");
    handlePeriodicHealthFailure(reason, external, owner);
  }

  boolean hasLiveManagedProcess() {
    return alive(child);
  }

  void handlePeriodicHealthFailure(String reason, boolean external) {
    ActiveServer owner = activeServer;
    if (owner != null) handlePeriodicHealthFailure(reason, external, owner);
  }

  private void handlePeriodicHealthFailure(
      String reason, boolean external, ActiveServer owner) {
    int failures;
    boolean failureThresholdReached;
    synchronized (ownershipMonitor) {
      if (activeServer != owner) return;
      lastPeriodicHealthError.set(reason);
      failures = consecutiveHealthFailures.incrementAndGet();
      failureThresholdReached = failures >= CONSECUTIVE_FAILURES_BEFORE_RESTART;
      if (failureThresholdReached) stopPeriodicHealthCheckLocked();
    }
    LOG.warn(
        "llama-server periodic health check failed ({}/{}): {}",
        failures,
        CONSECUTIVE_FAILURES_BEFORE_RESTART,
        reason);

    // Tempdoc 412 follow-up: emit a typed health-failure event with severity. The HealthCode
    // is derived from the reason text — a small heuristic since the periodic health probe
    // surfaces a free-form string today.
    try {
      InferenceFailure.HealthFailure failure =
          new InferenceFailure.HealthFailure(classifyHealthFailure(reason), reason, null);
      events.onHealthFailure(failure, failures, false);
    } catch (RuntimeException ex) {
      LOG.warn("Telemetry events.onHealthFailure threw: {}", ex.getMessage());
    }

    if (failureThresholdReached) {
      LOG.error("Too many consecutive health failures, treating as crash");
      if (external) {
        goOfflineFromExternalFailure.accept(reason, () -> isActive(owner));
      } else {
        signalManagedFailure(owner, false);
      }
    }
  }

  /**
   * Classify a periodic-health probe error string into a {@link HealthCode}. Heuristic — the
   * probe surfaces free-form messages today; this maps the common shapes to typed codes for
   * the {@code inference.health.failure_total} metric tag.
   */
  private static HealthCode classifyHealthFailure(String reason) {
    if (reason == null || reason.isBlank()) return HealthCode.UNKNOWN;
    String r = reason.toLowerCase(java.util.Locale.ROOT);
    if (r.contains("timeout") || r.contains("timed out")) return HealthCode.HEALTH_TIMEOUT;
    if (r.contains("interrupt")) return HealthCode.HEALTH_INTERRUPTED;
    if (r.contains("connection refused") || r.contains("connect")) return HealthCode.CONNECTION_REFUSED;
    if (r.contains("died") || r.contains("exit")) return HealthCode.PROCESS_DIED;
    return HealthCode.UNKNOWN;
  }

  void stopPeriodicHealthCheck() {
    synchronized (ownershipMonitor) {
      stopPeriodicHealthCheckLocked();
      consecutiveHealthFailures.set(0);
    }
  }

  private void stopPeriodicHealthCheckLocked() {
    if (periodicHealthTask != null) {
      periodicHealthTask.cancel(false);
      periodicHealthTask = null;
    }
  }

  private void resetHealthDiagnosticsLocked() {
    consecutiveHealthFailures.set(0);
    lastPeriodicHealthOkAtMs.set(0);
    lastPeriodicHealthError.set(null);
  }

  // ==================== Failure signalling ====================

  private void handleManagedCrash(ActiveServer owner) {
    if (!isActive(owner)) return;
    handleServerCrash(owner);
  }

  private void handleServerCrash(ActiveServer owner) {
    if (!isActive(owner)) return;
    signalManagedFailure(owner, true);
  }

  private void signalManagedFailure(ActiveServer owner, boolean processExited) {
    synchronized (ownershipMonitor) {
      if (activeServer != owner || failureSignalledOwner == owner) return;
      failureSignalledOwner = owner;
    }
    ManagedFailure described = describeFailure(owner, processExited);
    lastManagedFailure = described;
    LOG.error(
        "llama-server failed while serving: class={} evidence={} readyFor={}s",
        described.classification().crashClass(),
        described.classification().evidence(),
        described.readyFor().toSeconds());
    if (!processExited) {
      managedServerFailed.accept(() -> isActive(owner));
      return;
    }
    // Tempdoc 412 follow-up Bug F: process-death scenarios reach this method via the
    // crashMonitor future (Process.waitFor returning without cancellation), bypassing
    // handlePeriodicHealthFailure entirely (its early-return at the process-dead check skips
    // probing). Without an explicit emit here, inference.health.failure_total never fires for
    // taskkill / SIGKILL / process-crash scenarios — leaving the most operationally important
    // health failure invisible to the metric stream. Emit a typed PROCESS_DIED HealthFailure
    // before the recovery decision so operators can see the event.
    try {
      InferenceFailure.HealthFailure failure =
          new InferenceFailure.HealthFailure(
              HealthCode.PROCESS_DIED, "llama-server process exited unexpectedly", null);
      events.onHealthFailure(failure, 1, /* restartTriggered */ false);
    } catch (RuntimeException ex) {
      LOG.warn("Telemetry events.onHealthFailure (process-died) threw: {}", ex.getMessage());
    }

    managedServerFailed.accept(() -> isActive(owner));
  }

  /** Classifies a READY server's failure and measures how long it served (Spec A T27-T30). */
  private ManagedFailure describeFailure(ActiveServer owner, boolean processExited) {
    LlamaServerExitClassifier.Classification classification;
    if (processExited) {
      ChildHandle exited = owner.child();
      int exitCode = exited != null && !alive(exited) ? exitCode(exited) : -1;
      classification = LlamaServerExitClassifier.classify(
          exitCode, readLaunchOutputBestEffort(owner.start().context()),
          owner.start().context().devicePlan().gpuLayers() > 0);
    } else {
      classification = new LlamaServerExitClassifier.Classification(
          LlamaServerExitClassifier.CrashClass.OTHER,
          io.justsearch.app.api.chat.FailedCode.CRASH_LOOP,
          "no /health answer in " + CONSECUTIVE_FAILURES_BEFORE_RESTART + " consecutive probes");
    }
    ReadyMark ready = readyMark;
    Duration readyFor = ready != null && ready.owner() == owner
        ? Duration.ofNanos(Math.max(0L, monotonicNanos.getAsLong() - ready.atNanos()))
        : Duration.ZERO;
    return new ManagedFailure(
        owner.start(), classification, !processExited, readyFor, lowerContextRung());
  }

  /** The last READY failure signalled for {@code start}, if it is the failure of that start. */
  Optional<ManagedFailure> managedFailureOf(StartResult start) {
    ManagedFailure failure = lastManagedFailure;
    return failure != null && failure.start() == start
        ? Optional.of(failure)
        : Optional.empty();
  }

  // ==================== State Accessors ====================

  Optional<StartResult> activeStartResult() {
    ActiveServer owner = activeServer;
    return owner == null ? Optional.empty() : Optional.of(owner.start());
  }

  boolean activeManagedCandidateAlive(StartResult expected) {
    ActiveServer owner = activeServer;
    return owner != null && owner.start() == expected && ownedPhysicalServerAlive(owner);
  }

  /** Where the active server listens: this launch's fresh port, or the external endpoint's. */
  OptionalInt activePort() {
    ActiveServer owner = activeServer;
    return owner == null ? OptionalInt.empty() : OptionalInt.of(owner.port());
  }

  private ActiveServer installActive(StartResult start, ChildHandle launched, int port) {
    ActiveServer owner = new ActiveServer(start, launched, port);
    synchronized (ownershipMonitor) {
      activeServer = owner;
      failureSignalledOwner = null;
      resetHealthDiagnosticsLocked();
    }
    return owner;
  }

  private void clearActive(ActiveServer expected) {
    if (expected == null) return;
    synchronized (ownershipMonitor) {
      if (activeServer == expected) activeServer = null;
    }
  }

  private boolean isActive(ActiveServer expected) {
    return activeServer == expected;
  }

  private ActiveServer requireActive(StartResult expected) throws ModeTransitionException {
    ActiveServer owner = activeServer;
    if (owner == null || owner.start() != expected) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
          "llama-server ownership changed during startup");
    }
    return owner;
  }

  private void publishProps(ActiveServer expected, JsonNode root) {
    synchronized (ownershipMonitor) {
      if (activeServer != expected) return;
      propsOps.updateFromPropsBestEffort(root, expected.start());
      if (activeServer != expected) {
        throw new IllegalStateException("llama-server ownership changed during props publication");
      }
    }
  }

  boolean isExternalServerActive() {
    return usingExternal && child == null;
  }

  /**
   * Raw {@code usingExternal} field value (no {@code child == null} guard). Used by ILM /
   * the runner to learn whether adoption happened during the just-completed transition body,
   * even before the field is paired with the runtime invariant that an adopted server has no
   * owned process. Tempdoc 518 P2.
   */
  boolean isUsingExternalRaw() {
    return usingExternal;
  }

  /**
   * Set the {@code usingExternal} flag. Tempdoc 518 fix E — renamed from
   * {@code setUsingExternalForTest} because the orchestrator (ILM) calls this on legitimate
   * failure-recovery paths (e.g., switchToOnlineMode catch block clearing sticky external
   * state, detachExternalServer rollback restoring the flag, switchToIndexingMode failure
   * cleanup). The prior {@code -ForTest} suffix misrepresented the contract: this is the
   * orchestrator's mutation point for the flag, not a test-only affordance. Tests still use
   * it for scenario setup; that's a legitimate caller too. The flag is package-private and
   * not exposed beyond {@code app-inference}.
   */
  void setUsingExternal(boolean value) {
    this.usingExternal = value;
    if (!value) {
      ActiveServer owner = activeServer;
      if (owner != null
          && owner.start().disposition() == StartDisposition.ADOPTED_EXTERNAL) {
        clearActive(owner);
      }
    }
  }

  List<String> getEffectiveVramFlags() {
    return lastEffectiveVramFlags;
  }

  String getVramTierDetected() {
    return vramTierDetected;
  }

  String getVramDetectionSource() {
    return vramDetectionSource;
  }

  String getCudaRuntimeWarning() {
    return cudaRuntimeWarning;
  }

  void resetHealthFailures() {
    synchronized (ownershipMonitor) {
      consecutiveHealthFailures.set(0);
    }
  }

  // ==================== Resume (Spec A §7.4) ====================

  /** How long the resume probe waits for {@code /health} and for one token. */
  static final Duration RESUME_HEALTH_TIMEOUT = Duration.ofSeconds(5);

  static final Duration RESUME_TOKEN_TIMEOUT = Duration.ofSeconds(10);

  /**
   * The machine resumed: the health streak restarts at zero, the periodic health check is
   * scheduled again a full fixed delay from now, and a start waiting for health restarts its
   * deadline in full. Lock-free for the caller apart from the ownership monitor; it never waits
   * for a step in progress.
   */
  void noteResume() {
    resumes.incrementAndGet();
    ActiveServer owner;
    boolean scheduled;
    synchronized (ownershipMonitor) {
      consecutiveHealthFailures.set(0);
      owner = activeServer;
      scheduled = periodicHealthTask != null;
    }
    if (owner != null && scheduled) schedulePeriodicHealthCheck(owner);
  }

  /** How a resume probe of the active server ended, before any action on it. */
  enum ResumeProbeResult { NOT_SERVING, PASSED, EXITED, DEVICE_LOST, FAILED }

  /**
   * A resume probe's finding.
   *
   * @param deviceLostCode the code the server's output names, for {@link
   *     ResumeProbeResult#DEVICE_LOST}
   * @param reason what failed, for the log
   */
  record ResumeProbeFinding(
      ResumeProbeResult result, StartResult probed, String deviceLostCode, String reason) {}

  /**
   * Probes the active server this Engine started after a resume (Spec A §7.4): {@code /health}
   * within 5 s, then one token within 10 s through {@code oneToken}. A failed probe with the
   * process gone is EXITED (its crash path acts); with device-lost text in this launch's output it
   * is DEVICE_LOST; otherwise FAILED. An external server is never probed here.
   */
  ResumeProbeFinding probeAfterResume(java.util.function.Predicate<Duration> oneToken) {
    ActiveServer owner = activeServer;
    if (owner == null || owner.start().disposition() == StartDisposition.ADOPTED_EXTERNAL
        || owner.child() == null) {
      return new ResumeProbeFinding(ResumeProbeResult.NOT_SERVING, null, null, null);
    }
    if (!alive(owner.child())) {
      return new ResumeProbeFinding(ResumeProbeResult.EXITED, owner.start(), null, "exited");
    }
    HealthProbe health = probeHealth(owner, RESUME_HEALTH_TIMEOUT);
    String failure;
    if (!health.ok()) {
      failure = "/health: " + (health.error() != null ? health.error() : "failed");
    } else if (!oneToken.test(RESUME_TOKEN_TIMEOUT)) {
      failure = "one-token completion failed";
    } else {
      return new ResumeProbeFinding(ResumeProbeResult.PASSED, owner.start(), null, null);
    }
    if (!isActive(owner)) {
      return new ResumeProbeFinding(ResumeProbeResult.NOT_SERVING, null, null, failure);
    }
    if (!alive(owner.child())) {
      return new ResumeProbeFinding(ResumeProbeResult.EXITED, owner.start(), null, failure);
    }
    var code = LlamaServerExitClassifier.deviceLostCode(
        readLaunchOutputBestEffort(owner.start().context()));
    if (code.isPresent()) {
      return new ResumeProbeFinding(
          ResumeProbeResult.DEVICE_LOST, owner.start(), code.get(), failure);
    }
    return new ResumeProbeFinding(ResumeProbeResult.FAILED, owner.start(), null, failure);
  }

  InferenceLifecycleManager.ExternalServerDiagnostics buildExternalDiagnostics(
      boolean usingExternal) {
    return propsOps
        .buildExternalDiagnostics(
            usingExternal,
            lastPeriodicHealthOkAtMs.get(),
            lastPeriodicHealthError.get(),
            consecutiveHealthFailures.get())
        .withNote(externalServerNote());
  }

  /** Returns true if the last /props response indicated vision support (runtime signal). */
  boolean hasVisionCapabilityFromProps() {
    return propsOps.hasVisionCapability();
  }

  /** Tempdoc 682 Item 2: actually-running build tag observed via /props; null = unknown. */
  String actualServerBuild() {
    return propsOps.actualServerBuild();
  }

  // ==================== Lifecycle ====================

  void shutdown() {
    stopPeriodicHealthCheck();
    if (crashMonitor != null) {
      crashMonitor.cancel(true);
      crashMonitor = null;
    }
    healthScheduler.shutdownNow();
    cancelQueued(exitExecutor);
    retainedStartRequest = null;
    try {
      healthScheduler.awaitTermination(PROCESS_KILL_TIMEOUT_SECS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    // A control this made for itself ends with it; it stops what it still holds (QUIT).
    if (ownsChildControl) childControl.close();
  }

  private static java.util.concurrent.ThreadFactory daemonFactory(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }

  private static void cancelQueued(java.util.concurrent.ExecutorService executor) {
    for (Runnable queued : executor.shutdownNow()) {
      if (queued instanceof java.util.concurrent.Future<?> future) future.cancel(false);
    }
  }

  private static void cancelIfOpened(java.util.concurrent.ExecutorService executor) {
    if (executor != null) cancelQueued(executor);
  }

  // ==================== Policy Helpers ====================

  private static boolean policyDisallowExternalInferenceServers() {
    return Boolean.parseBoolean(System.getProperty(POLICY_DISALLOW_EXTERNAL_PROP, "false"));
  }

  private static boolean allowHealthOnlyExternalAdoption() {
    return Boolean.parseBoolean(
        System.getProperty(ALLOW_HEALTH_ONLY_EXTERNAL_ADOPTION_PROP, "false"));
  }

  // ==================== Crash Diagnostics ====================

  /**
   * Reads the llama-server log file and extracts a human-readable diagnostic message for a startup
   * failure. Recognizes known failure patterns (model architecture mismatch, CUDA errors, etc.).
   */
  /** Max bytes to read from the end of the log file for diagnostics. */
  private static final int DIAG_TAIL_BYTES = 16_384;

  private String diagnoseServerFailure(LlamaServerConfigContext context, int exitCode) {
    String base = "llama-server process exited before becoming healthy (exit code " + exitCode + ")";
    Path logFile = resolveLlamaServerLogFile(context);
    if (!Files.exists(logFile)) {
      return base;
    }
    try {
      // Read only the tail of the log — avoid loading potentially large files into memory.
      String logTail = readTail(logFile, DIAG_TAIL_BYTES);

      // Pattern: "unknown model architecture: '<name>'" — binary too old for model
      for (String line : logTail.split("\n")) {
        if (line.contains("unknown model architecture")) {
          return base + ". The model uses an architecture not supported by this llama-server build. "
              + "Update llama-server to a newer build or use a compatible model. "
              + "Detail: " + line.strip();
        }
      }

      // Pattern: "error loading model" or "failed to load model" — corrupt/incompatible file
      if (logTail.contains("error loading model") || logTail.contains("failed to load model")) {
        return base + ". Failed to load model file — the file may be corrupted, "
            + "incompatible, or too large for available memory. "
            + "Check " + logFile + " for details.";
      }

      // Pattern: "CUDA error" (exact phrase from llama.cpp CUDA backend)
      if (logTail.contains("CUDA error") || logTail.contains("cudaMalloc failed")) {
        return base + ". CUDA error during startup — GPU may be unavailable or "
            + "drivers may be outdated. Try reducing GPU layers or switching to CPU mode. "
            + "Check " + logFile + " for details.";
      }

      // Pattern: port conflict
      if (logTail.contains("address already in use")
          || LlamaServerBindFailure.isBindFailure(logTail)) {
        return base + ". Server port is already in use — another llama-server or "
            + "application may be occupying the port. "
            + "Check " + logFile + " for details.";
      }

      return base + ". Check " + logFile + " for details.";
    } catch (IOException e) {
      LOG.debug("Failed to read llama-server log for diagnostics: {}", e.getMessage());
      return base;
    }
  }

  /** Reads the last {@code maxBytes} of a file as a UTF-8 string. */
  private static String readTail(Path file, int maxBytes) throws IOException {
    long size = Files.size(file);
    if (size <= maxBytes) {
      return Files.readString(file);
    }
    try (var channel = Files.newByteChannel(file)) {
      channel.position(size - maxBytes);
      byte[] buf = new byte[maxBytes];
      java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
      int read = channel.read(bb);
      return new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  // ==================== Path Resolution ====================

  private static Path resolveLlamaServerLogFile(LlamaServerConfigContext context) {
    String home = System.getenv("JUSTSEARCH_HOME");
    if (home != null && !home.isBlank()) {
      return Path.of(home).resolve("logs").resolve("llama-server.log");
    }
    Path dataDir = context.resolved().paths().dataDir();
    if (dataDir != null) {
      return dataDir.resolve("logs").resolve("llama-server.log");
    }
    return Path.of(System.getProperty("user.dir")).resolve("logs").resolve("llama-server.log");
  }

  private static Path resolveBundledRuntimeBinDirBestEffort(
      LlamaServerConfigContext context) {
    try {
      Path repoRoot = context.resolved().paths().repoRoot();
      Path headlessDir =
          repoRoot != null
              ? repoRoot
              : Path.of(System.getProperty("user.dir"));
      Path bin = headlessDir.resolve("runtime").resolve("bin");
      if (Files.isDirectory(bin)) {
        return bin;
      }
    } catch (Exception e) {
      LOG.debug("resolveBundledRuntimeBinDir: {}", e.getMessage());
    }
    return null;
  }
}
