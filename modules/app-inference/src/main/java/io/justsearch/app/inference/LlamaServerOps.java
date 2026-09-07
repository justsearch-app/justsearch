/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static io.justsearch.app.inference.InferenceHttpHelpers.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ConfigStore;
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
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process management, health monitoring, and crash recovery for llama-server.
 *
 * <p>Encapsulates: process start/stop/adopt, health probing, periodic health monitoring, and crash
 * recovery. Delegates /props interpretation and external server diagnostics to {@link
 * ServerPropsOps}. Extracted to reduce the size of {@link InferenceLifecycleManager}.
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
  private static final String POLICY_GPU_ACCEL_PROP = "policy.gpu_acceleration_enabled";
  private static final String POLICY_DISALLOW_EXTERNAL_PROP =
      "justsearch.policy.disallowExternalInferenceServers";
  private static final String ALLOW_HEALTH_ONLY_EXTERNAL_ADOPTION_PROP =
      "justsearch.inference.external.allow_health_only_adoption";

  // Crash recovery (values declared by SUPERVISION_POLICY / supervision-contract.v1.json, tempdoc 627)
  private static final int MAX_CRASHES = SUPERVISION_POLICY.maxCrashes();

  // Periodic health monitoring
  private static final long PERIODIC_HEALTH_INTERVAL_MS = SUPERVISION_POLICY.periodicHealthIntervalMs();
  private static final int CONSECUTIVE_FAILURES_BEFORE_RESTART =
      SUPERVISION_POLICY.consecutiveFailuresBeforeRestart();

  /**
   * Generations of {@code logs/llama-server.log} kept across launches (the live file plus two
   * archived ones). A constant, not a config key: retention of a diagnostic the app writes about
   * its own child process is a product decision, and the number matches the app's other supervised
   * process log ({@code WorkerSpawner}'s {@code worker.log} → {@code .1} → {@code .2}).
   */
  private static final int RETAINED_LOG_GENERATIONS = 3;

  // Process and probe timeouts
  private static final long PROCESS_KILL_TIMEOUT_SECS = 5;
  private static final Duration ADOPTION_HEALTH_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(1);
  /** Startup adoption retries a TIMED-OUT probe this many times (see isExistingServerHealthy). */
  private static final int ADOPTION_PROBE_ATTEMPTS = 3;
  private static final Duration PROPS_PROBE_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration PERIODIC_HEALTH_TIMEOUT = Duration.ofSeconds(5);
  private static final long CRASH_RECOVERY_DELAY_MS = SUPERVISION_POLICY.crashRecoveryDelayMs();

  // ==================== Owned Fields ====================

  private volatile Process process;
  private volatile CompletableFuture<?> crashMonitor;

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
  private volatile long lastLaunchLogOffset;

  // Context window as a derived resource (tempdoc 883 decision 1). Null plan = nothing this class
  // launched: no server yet, or an adopted external one whose window we did not choose.
  private volatile ContextWindowPolicy.Plan contextPlan;
  private volatile int contextRung;
  private volatile String contextRungReason;
  private volatile int contextSlots;
  private volatile String contextKvType;

  // Crash recovery
  private final AtomicInteger crashCount = new AtomicInteger(0);
  private final ScheduledExecutorService recoveryScheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "Server-Recovery");
            t.setDaemon(true);
            return t;
          });

  // Health monitoring (separate from recovery so a slow health probe cannot block recovery)
  private final ScheduledExecutorService healthScheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "Server-Health");
            t.setDaemon(true);
            return t;
          });

  // Periodic health monitoring
  private final AtomicInteger consecutiveHealthFailures = new AtomicInteger(0);
  private final AtomicLong lastPeriodicHealthOkAtMs = new AtomicLong(0);
  private final AtomicReference<String> lastPeriodicHealthError = new AtomicReference<>(null);
  private volatile ScheduledFuture<?> periodicHealthTask;

  // ==================== Injected Dependencies ====================

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Supplier<InferenceConfig> config;
  // Tempdoc 374 alpha.27: VramDetector dependency removed; all VRAM probes route through
  // GpuCapabilitiesService (NVML-first) + VramRequirements (threshold helpers).
  private final GpuCapabilitiesService gpuCapabilitiesService;
  private final Supplier<Mode> currentMode;

  // Tempdoc 518 P2: LlamaServerOps now owns its own external-adoption flag. Previously this
  // was a back-channel into ILM's volatile field via injected Consumer/Supplier ports
  // (eliminated). Read by the runner when building the next view atom.
  private volatile boolean usingExternal = false;

  // Mode transition callbacks
  private final Runnable goOfflineFromMaxCrashes;
  private final Consumer<String> goOfflineFromExternalFailure;

  // Composition: props interpretation and external diagnostics
  private final ServerPropsOps propsOps;

  // Tempdoc 412 follow-up: typed observability events. Defaults to noop when no telemetry
  // is wired (e.g., tests, AI-disabled bootstrap). Set via the events-aware constructor.
  private final InferenceTelemetryEvents events;

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
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Supplier<InferenceConfig> config,
      GpuCapabilitiesService gpuCapabilitiesService,
      Supplier<Mode> currentMode,
      PropsObserver propsObserver,
      Runnable goOfflineFromMaxCrashes,
      Consumer<String> goOfflineFromExternalFailure,
      InferenceTelemetryEvents events) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.config = Objects.requireNonNull(config, "config");
    this.gpuCapabilitiesService = gpuCapabilitiesService;
    this.currentMode = currentMode;
    this.goOfflineFromMaxCrashes =
        Objects.requireNonNull(goOfflineFromMaxCrashes, "goOfflineFromMaxCrashes");
    this.goOfflineFromExternalFailure =
        Objects.requireNonNull(goOfflineFromExternalFailure, "goOfflineFromExternalFailure");
    this.events = Objects.requireNonNull(events, "events");
    this.propsOps =
        new ServerPropsOps(
            config, this::isExternalServerActive, propsObserver, this::requestedContextTokens);
  }

  // ==================== Process Lifecycle ====================

  void startLlamaServer() throws IOException, ModeTransitionException {
    LOG.info("Starting llama-server...");

    // Nothing is launched yet, so no window has been chosen. Cleared before the adoption check so
    // an adopted external server reports no context record rather than the previous launch's.
    clearContextWindowRecord();

    if (adoptExistingServerIfPresent()) {
      return;
    }

    usingExternal = false;

    InferenceConfig cfg = config.get();
    ResolvedConfig rc = ConfigStore.global().get();

    int gpuLayers = cfg.gpuLayers();
    if (gpuLayers > 0 && !policyGpuAccelerationEnabled()) {
      LOG.warn("GPU acceleration disabled by policy; forcing -ngl 0");
      gpuLayers = 0;
    }

    // Tempdoc 883 decision 1: the context window is a DERIVED resource, not a stored preference.
    // The rung comes from the ladder for this backend (or an operator's explicit value, as a
    // one-rung ladder); if the server refuses it, waitForServerHealth steps down one rung and
    // relaunches rather than leaving the engine down.
    GpuCapabilities gpuSnap = gpuLayers > 0 ? gpuCapabilitiesService.snapshot() : null;
    Long freeVramBytes =
        gpuSnap != null && gpuSnap.effective() != null ? gpuSnap.effective().freeVramBytes() : null;
    ContextWindowPolicy.Plan plan = planContextWindow(rc, gpuLayers > 0, freeVramBytes);
    this.contextPlan = plan;
    this.contextRung = plan.topRung();
    this.contextRungReason = plan.reason();
    this.contextSlots = slotsFor(cfg, rc);
    this.contextKvType = rc.ai().llmKvType();
    this.lastEffectiveVramFlags = memoryPlanFlags(contextSlots, contextKvType);

    List<String> command = buildLaunchCommand(cfg, rc, gpuLayers, plan);

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

    LOG.debug("Starting server with command: {}", String.join(" ", command));
    launchManagedLlamaServer(command);
  }

  /**
   * The FULL llama-server argv for a launch. Package-private, static and pure so the exact
   * argument list is unit-testable without spawning a process — the flags interact (see
   * {@link #memoryPlanFlags}), so asserting them individually is not the same as asserting the
   * launch line.
   *
   * <p>Every launch-shaping decision this class makes is visible here; the caller keeps only the
   * side effects (recording the plan, the thinking verdict, and the VRAM diagnostics).
   */
  static List<String> buildLaunchCommand(
      InferenceConfig cfg, ResolvedConfig rc, int gpuLayers, ContextWindowPolicy.Plan plan) {
    List<String> command = new ArrayList<>();
    command.add(cfg.serverExecutable().toString());
    command.add("-m");
    command.add(cfg.modelPath().toString());

    if (cfg.mmprojPath() != null) {
      command.add("--mmproj");
      command.add(cfg.mmprojPath().toString());
    }

    // VDU mode: no prompt cache (it corrupts silently after ~7 pages and crashes the server).
    // The single-slot half of this rule rides the common -np below, which VDU pins to 1 —
    // multi-slot causes alternating HTTP 500 on vision requests.
    if (cfg.vduMode()) {
      command.add("--cache-ram");
      command.add("0");
    }

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

    command.add("--port");
    command.add(String.valueOf(cfg.serverPort()));

    command.addAll(windowFlags(plan.topRung(), gpuLayers));
    command.addAll(memoryPlanFlags(slotsFor(cfg, rc), rc.ai().llmKvType()));
    return command;
  }

  /** Slots for this launch: VDU pins 1 (multi-slot breaks vision), otherwise the configured count. */
  static int slotsFor(InferenceConfig cfg, ResolvedConfig rc) {
    return cfg.vduMode() ? 1 : rc.ai().llmSlots();
  }

  /**
   * The window flags: {@code -c <rung> -ngl <layers>}.
   */
  static List<String> windowFlags(int rung, int gpuLayers) {
    return List.of("-c", String.valueOf(rung), "-ngl", String.valueOf(gpuLayers));
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

  void stopLlamaServer() {
    stopPeriodicHealthCheck();
    // No server of ours is running after this, so the launched-window record stops being true.
    clearContextWindowRecord();
    // Cancel crash monitor before killing process to prevent spurious recovery attempts (H4).
    if (crashMonitor != null) {
      crashMonitor.cancel(true);
      crashMonitor = null;
    }
    if (usingExternal && process == null) {
      LOG.info(
          "Not stopping llama-server: using existing external instance on port {} (no process"
              + " handle).",
          config.get().serverPort());
      // We're no longer in Online mode, so clear the "using external" state to avoid sticky
      // behavior (e.g., blocking config apply while offline/indexing).
      usingExternal = false;
      return;
    }
    if (process != null && process.isAlive()) {
      long pid = process.pid();
      LOG.info("Stopping llama-server (PID: {})...", pid);

      process.destroy();

      try {
        boolean exited = process.waitFor(PROCESS_KILL_TIMEOUT_SECS, TimeUnit.SECONDS);
        if (!exited) {
          LOG.warn("Server hung. Executing taskkill...");
          // Windows-specific hard kill that forces VRAM release
          new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid))
              .start()
              .waitFor(PROCESS_KILL_TIMEOUT_SECS, TimeUnit.SECONDS);
        }
      } catch (Exception e) {
        LOG.error("Error killing server", e);
      }

      process = null;
      LOG.info("llama-server stopped");
    }
  }

  /**
   * Waits for the freshly launched (or adopted) server to report healthy.
   *
   * <p>Tempdoc 835 §9c.2: a build that cannot honour {@code --reasoning-budget} exits immediately
   * with a launch-argument rejection. That must cost thinking, not inference — so the rejection is
   * detected here, the server is relaunched once without the flag, and the verdict is recorded for
   * the runtime manifest.
   *
   * <p>Tempdoc 883 decision 1: the same seam carries the context ladder. A rung the machine cannot
   * fit is also a fast hard abort, so the window steps down one rung and relaunches instead of
   * leaving the engine down. Any other startup failure propagates unchanged.
   *
   * <p>The loop terminates: the reasoning relaunch can fire at most once (it sets
   * {@link ThinkingSupport#UNSUPPORTED}, which its own guard then refuses), and each step-down
   * consumes a rung from a finite ladder.
   */
  void waitForServerHealth() throws ModeTransitionException {
    while (true) {
      try {
        awaitServerHealth();
        return;
      } catch (ModeTransitionException e) {
        if (relaunchWithoutReasoningBudget(e)) {
          continue;
        }
        if (relaunchAtLowerContextRung(e)) {
          continue;
        }
        throw e;
      }
    }
  }

  private void awaitServerHealth() throws ModeTransitionException {
    LOG.info("Waiting for server health (timeout={}ms)...", HEALTH_CHECK_TIMEOUT_MS);
    long startTime = System.currentTimeMillis();
    long deadline = startTime + HEALTH_CHECK_TIMEOUT_MS;
    long nextProgressLog = startTime + HEALTH_CHECK_PROGRESS_LOG_INTERVAL_MS;

    while (System.currentTimeMillis() < deadline) {
      long now = System.currentTimeMillis();
      if (now >= nextProgressLog) {
        long elapsedSec = (now - startTime) / 1000;
        long remainingSec = (deadline - now) / 1000;
        LOG.info("Still waiting for model load... {}s elapsed, {}s remaining", elapsedSec, remainingSec);
        nextProgressLog = now + HEALTH_CHECK_PROGRESS_LOG_INTERVAL_MS;
      }
      if (process != null && !process.isAlive()) {
        int exitCode;
        try {
          exitCode = process.exitValue();
        } catch (Exception e) {
          LOG.debug("Failed to get llama-server exit code: {}", e.getMessage());
          exitCode = -1;
        }
        if (exitCode == WINDOWS_STATUS_DLL_NOT_FOUND) {
          Path logFile = resolveLlamaServerLogFile();
          Path exeDir = config.get().serverExecutable().getParent();
          throw new ModeTransitionException(
              ModeTransitionException.Reason.MISSING_DLL,
              "llama-server failed to start (missing DLL dependencies, 0xC0000135). "
                  + "This usually means the llama-server folder is missing required native DLLs"
                  + " (llama.dll / ggml*.dll / mtmd.dll) "
                  + "or the Visual C++ runtime is not available. "
                  + "Try 'Repair AI' and retry Online Mode. "
                  + "llama-server dir="
                  + (exeDir != null ? exeDir : config.get().serverExecutable())
                  + ", log="
                  + logFile);
        }
        String diagnostic = diagnoseServerFailure(exitCode);
        throw new ModeTransitionException(
            ModeTransitionException.Reason.PROCESS_EXITED,
            diagnostic);
      }
      HealthProbe probe = probeHealth(ADOPTION_HEALTH_TIMEOUT);
      if (probe.ok()) {
        LOG.info("Server healthy");
        // The build accepted --reasoning-budget: it started and serves with the flag present.
        if (reasoningBudgetRequested) {
          thinkingSupport.compareAndSet(ThinkingSupport.UNKNOWN, ThinkingSupport.SUPPORTED);
        }
        logHealthBodyBestEffort(probe.body());
        logServerProperties(); // Log actual server config for debugging
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
  private boolean relaunchWithoutReasoningBudget(ModeTransitionException failure) {
    if (failure.reason() != ModeTransitionException.Reason.PROCESS_EXITED
        || !reasoningBudgetRequested
        || thinkingSupport.get() == ThinkingSupport.UNSUPPORTED
        || !lastLaunchCommand.contains("--reasoning-budget")) {
      return false;
    }
    if (!LlamaServerArgRejection.isReasoningBudgetRejection(readLaunchOutputBestEffort())) {
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
      launchManagedLlamaServer(retry);
      return true;
    } catch (IOException | ModeTransitionException e) {
      LOG.error("Relaunch without --reasoning-budget failed: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Context-ladder recovery (tempdoc 883 decision 1): when the startup failure was the server
   * refusing the requested window, relaunch at the next lower rung and record why.
   *
   * <p>Gated on {@code PROCESS_EXITED} because that is the shape an unfittable {@code -c} takes on
   * the bundled b8571 build — a fast hard abort, not a slow degradation. A health-check timeout is
   * deliberately NOT a step-down trigger: it says the model is loading slowly, not that the window
   * does not fit, and stepping down on it would silently shrink the window for an unrelated cause.
   *
   * <p>Returns false — leaving the original failure to propagate — for an adopted external server
   * (we did not choose its window), for an operator's explicit override (a one-rung ladder: honour
   * it or fail loud), and once the ladder is exhausted.
   */
  private boolean relaunchAtLowerContextRung(ModeTransitionException failure) {
    ContextWindowPolicy.Plan plan = contextPlan;
    if (failure.reason() != ModeTransitionException.Reason.PROCESS_EXITED
        || plan == null
        || usingExternal
        || lastLaunchCommand.isEmpty()) {
      return false;
    }
    OptionalInt lower = plan.nextRungBelow(contextRung);
    if (lower.isEmpty()) {
      if (ContextWindowPolicy.REASON_OVERRIDE.equals(plan.reason())) {
        LOG.error(
            "llama-server did not start at the explicitly configured context size {}"
                + " (justsearch.context.size). This is an operator override, so it is NOT reduced"
                + " to a smaller window: set it lower, or set it to 0 to let the window be derived"
                + " from the backend.",
            contextRung);
      } else {
        LOG.warn(
            "llama-server exited at context rung {} and the ladder {} is exhausted; reporting the"
                + " launch failure rather than retrying.",
            contextRung,
            plan.ladder());
      }
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
        plan.topRung(),
        plan.ladder());

    if (crashMonitor != null) {
      crashMonitor.cancel(true);
      crashMonitor = null;
    }
    try {
      launchManagedLlamaServer(retry);
    } catch (IOException | ModeTransitionException e) {
      LOG.error("Relaunch at context rung {} failed: {}", next, e.getMessage());
      return false;
    }
    this.contextRung = next;
    this.contextRungReason = ContextWindowPolicy.steppedFrom(plan.topRung());
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
    if (resolution == null
        || !resolution.isResolved()
        || resolution.sourceOrdinal() <= ResolvedConfigBuilder.ORDINAL_AUTO_DETECT) {
      return ContextWindowPolicy.auto(gpuBacked, freeVramBytes);
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
  private String readLaunchOutputBestEffort() {
    try {
      Path logFile = resolveLlamaServerLogFile();
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

  // ==================== External Server Adoption ====================

  private boolean adoptExistingServerIfPresent() throws IOException, ModeTransitionException {
    if (!isExistingServerHealthy()) {
      return false;
    }
    if (policyDisallowExternalInferenceServers()) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.EXTERNAL_SERVER_POLICY_BLOCKED,
          "External inference servers are disallowed by administrator policy. "
              + "Stop the existing llama-server on port "
              + config.get().serverPort()
              + " and retry Online mode.");
    }

    // Validate the external server identity before adopting. This prevents accidentally adopting a
    // random HTTP service that happens to return 200 for /health.
    PropsProbe probe = probeServerProps(PROPS_PROBE_TIMEOUT);
    if (!probe.looksLikeLlamaServer()) {
      if (!allowHealthOnlyExternalAdoption()) {
        throw new IOException(
            "Port "
                + config.get().serverPort()
                + " is already serving HTTP 200 on /health, but it does not look like a"
                + " llama-server (missing or invalid /props). Stop the process using that port,"
                + " change JUSTSEARCH_SERVER_PORT, or (dev only) set -D"
                + ALLOW_HEALTH_ONLY_EXTERNAL_ADOPTION_PROP
                + "=true to force adoption.");
      }
      LOG.warn(
          "External server on port {} is healthy but /props is missing/unparseable. "
              + "Proceeding with health-only adoption due to {}=true.",
          config.get().serverPort(),
          ALLOW_HEALTH_ONLY_EXTERNAL_ADOPTION_PROP);
    }

    adoptExternalServer(probe);
    return true;
  }

  private void adoptExternalServer(PropsProbe probe) {
    usingExternal = true;
    process = null;
    // We launched nothing, so launch-argument acceptance says nothing about this server.
    reasoningBudgetRequested = false;
    thinkingSupport.set(ThinkingSupport.UNKNOWN);
    propsOps.resetExternalAdoptionState(
        probe.looksLikeLlamaServer(), probe.looksLikeLlamaServer() ? null : probe.error());
    // Monitor adopted servers too; recovery behavior differs (we can't restart without a handle).
    schedulePeriodicHealthCheck(); // also resets consecutiveHealthFailures via stopPeriodicHealthCheck
    LOG.warn(
        "llama-server already responding on port {}. Using existing instance (no process handle). "
            + "If you want JustSearch to fully manage llama-server, stop the existing process"
            + " first.",
        config.get().serverPort());
    try {
      // Best-effort: capture model/context details for diagnostics and request routing.
      if (probe.propsRoot() != null) {
        propsOps.updateFromPropsBestEffort(probe.propsRoot());
      } else {
        logServerProperties();
      }
    } catch (Exception e) {
      LOG.debug(
          "Failed to fetch llama-server properties from external instance: {}", e.getMessage());
    }
  }

  // ==================== Process Launch Helpers ====================

  private void launchManagedLlamaServer(List<String> command)
      throws IOException, ModeTransitionException {
    // Tempdoc 656 Task 3: a missing llama-server.exe previously failed inside ProcessBuilder.start()
    // below with a raw IOException (Windows error 2), which propagates past every typed handler and
    // is caught generically by InferenceLifecycleManager, collapsing into the coarse
    // TransitionCode.ONLINE_START_FAILED with the real cause preserved only as free text. This
    // pre-launch check mirrors the existing MISSING_DLL pattern (detect a specific missing-artifact
    // condition, throw a typed reason) but at an earlier failure point — before a process exists,
    // rather than by inspecting an already-launched process's exit code.
    Path exe = config.get().serverExecutable();
    if (exe == null || !Files.isRegularFile(exe)) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.EXECUTABLE_NOT_FOUND,
          "llama-server executable not found at "
              + exe
              + ". Try 'Repair AI' or reinstall the AI runtime, then retry Online Mode.");
    }
    lastLaunchCommand = List.copyOf(command);
    ProcessBuilder pb = new ProcessBuilder(command);
    // Ensure the DLL search path includes the llama-server directory and the bundled runtime/bin
    // directory (which often contains the VC++ runtime DLLs inside our packaged headless runtime
    // image).
    Path serverExeDir = config.get().serverExecutable().getParent();
    configureProcessWorkingDirectory(pb, serverExeDir);
    adjustPathForRuntimeDlls(pb, serverExeDir);
    Path logFile = configureServerLogRedirection(pb, resolveLlamaServerLogFile());
    startManagedProcessAndMonitor(pb, logFile);
  }

  private void configureProcessWorkingDirectory(ProcessBuilder pb, Path serverExeDir) {
    if (serverExeDir != null && Files.isDirectory(serverExeDir)) {
      pb.directory(serverExeDir.toFile());
    }
  }

  private void adjustPathForRuntimeDlls(ProcessBuilder pb, Path serverExeDir) {
    try {
      Map<String, String> env = pb.environment();
      String pathKey =
          env.containsKey("Path") ? "Path" : (env.containsKey("PATH") ? "PATH" : "Path");
      String existingPath = env.getOrDefault(pathKey, "");

      List<String> prefixes = new ArrayList<>();
      if (serverExeDir != null) {
        prefixes.add(serverExeDir.toAbsolutePath().normalize().toString());
      }
      Path runtimeBin = resolveBundledRuntimeBinDirBestEffort();
      if (runtimeBin != null) {
        prefixes.add(runtimeBin.toAbsolutePath().normalize().toString());
      }

      if (!prefixes.isEmpty()) {
        String prepend = String.join(";", prefixes);
        String next =
            existingPath == null || existingPath.isBlank()
                ? prepend
                : (prepend + ";" + existingPath);
        env.put(pathKey, next);
      }
    } catch (Exception e) {
      // Best-effort: do not fail startup on PATH adjustments.
      LOG.debug("Failed to adjust PATH for llama-server: {}", e.getMessage());
    }
  }

  /**
   * Persist llama-server output to {@code <home|dataDir>/logs/llama-server.log}, retaining a bounded
   * number of generations. Package-private (and taking the resolved path) so the retention policy is
   * testable through the SAME method the launch path calls, rather than through a helper a future
   * edit could stop calling.
   */
  Path configureServerLogRedirection(ProcessBuilder pb, Path logFile) throws IOException {
    Files.createDirectories(logFile.getParent());
    rotateServerLogGenerations(logFile);
    // Remember where this launch starts writing, so launch-argument rejections are read from THIS
    // run's output rather than a previous run's tail. Read AFTER rotation: rotation moves the
    // previous log aside, so this launch starts at offset 0 of a fresh file.
    lastLaunchLogOffset = Files.exists(logFile) ? Files.size(logFile) : 0L;
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    return logFile;
  }

  /**
   * Rotate {@code llama-server.log} → {@code .1} → {@code .2} on each launch, deleting what falls
   * off the end. Before this the file was appended to across every restart of the process's life
   * with nothing pruning it — unbounded growth of a file whose verbosity belongs to llama-server,
   * not to us, and which can therefore carry prompt-shaped diagnostics.
   *
   * <p>Rotate-on-start (rather than a size cap) mirrors the policy the app already runs for its
   * other supervised process log — {@code WorkerSpawner}'s {@code worker.log} → {@code .1} →
   * {@code .2}, itself mirroring the Shell's {@code lib.rs} rotation — so the app has ONE retention
   * story for process logs instead of two. It also keeps the useful property for free: the previous
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

  private void startManagedProcessAndMonitor(ProcessBuilder pb, Path logFile) throws IOException {
    process = pb.start();
    Process started = process; // capture for crash monitor lambda (H2: avoid stale this.process)

    LOG.info("llama-server logs: {}", logFile);
    LOG.info("llama-server started, PID: {}", started.pid());
    schedulePeriodicHealthCheck(); // Fix #6: Monitor for hung processes

    // Monitor for crashes (fire-and-forget monitoring task)
    crashMonitor =
        CompletableFuture.runAsync(
            () -> {
              try {
                int exitCode = started.waitFor();
                if (exitCode != 0 && currentMode.get() == Mode.ONLINE) {
                  LOG.error("llama-server crashed with exit code {}", exitCode);
                  handleServerCrash();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // restore interrupt flag
              }
            });
  }

  // ==================== Health Probing ====================

  record PropsProbe(boolean ok, JsonNode propsRoot, String error) {
    boolean looksLikeLlamaServer() {
      if (!ok || propsRoot == null) return false;
      return ServerPropsOps.looksLikeLlamaServerProps(propsRoot);
    }
  }

  record HealthProbe(boolean ok, int statusCode, String error, String body) {}

  HealthProbe probeHealth(Duration timeout) {
    try {
      HttpRequest request = buildGetRequest(config.get().serverPort(), PATH_HEALTH, timeout);
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

  /**
   * Startup-adoption health probe: retries a probe that TIMED OUT (a listener exists but answered
   * slowly) up to {@link #ADOPTION_PROBE_ATTEMPTS} times; a connection refusal (nobody listening)
   * or any non-timeout error returns false on the first attempt, so the no-server path stays fast.
   *
   * <p>Tempdoc 932 item 3: a single 1 s probe against a real-but-busy server concluded "no server"
   * and fell through to spawning a second llama-server on the same port — the restart-loop failure
   * this class exists to avoid — and in tests surfaced as a misleading downstream exception.
   */
  boolean isExistingServerHealthy() {
    for (int attempt = 1; attempt <= ADOPTION_PROBE_ATTEMPTS; attempt++) {
      HealthProbe probe = probeHealth(HEALTH_PROBE_TIMEOUT);
      if (probe.ok()) {
        return true;
      }
      boolean timedOut = probe.error() != null && probe.error().startsWith("HttpTimeoutException");
      if (!timedOut) {
        return false;
      }
      LOG.debug(
          "Startup health probe timed out (attempt {}/{}); retrying",
          attempt,
          ADOPTION_PROBE_ATTEMPTS);
    }
    return false;
  }

  PropsProbe probeServerProps(Duration timeout) {
    try {
      HttpRequest request = buildGetRequest(config.get().serverPort(), PATH_PROPS, timeout);
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
  private void logServerProperties() {
    PropsProbe probe = probeServerProps(PROPS_PROBE_TIMEOUT);
    if (probe.ok()) {
      propsOps.updateFromPropsBestEffort(probe.propsRoot());
    } else {
      LOG.debug("Could not fetch server properties: {}", probe.error());
    }
  }

  void updateFromPropsBestEffort(JsonNode root) {
    propsOps.updateFromPropsBestEffort(root);
  }

  // ==================== Periodic Health Monitoring (Fix #6) ====================

  /**
   * Schedules periodic health checks for the running llama-server.
   *
   * <p>If 3 consecutive health checks fail, treats the server as crashed and triggers the crash
   * recovery flow. This detects hung processes that are still alive but not responding to HTTP
   * requests.
   */
  void schedulePeriodicHealthCheck() {
    stopPeriodicHealthCheck();
    consecutiveHealthFailures.set(0);

    periodicHealthTask =
        healthScheduler.scheduleAtFixedRate(
            () -> {
              boolean external = usingExternal && process == null;
              if (currentMode.get() != Mode.ONLINE) {
                return; // Not in ONLINE mode
              }
              if (!external) {
                Process p = process;
                if (p == null || !p.isAlive()) {
                  return; // Not in ONLINE mode or process already dead
                }
              }

              HealthProbe probe = probeHealth(PERIODIC_HEALTH_TIMEOUT);
              if (probe.ok()) {
                int prev = consecutiveHealthFailures.getAndSet(0);
                lastPeriodicHealthOkAtMs.set(System.currentTimeMillis());
                lastPeriodicHealthError.set(null);
                if (prev > 0) {
                  LOG.info("llama-server health recovered after {} failures", prev);
                  // Tempdoc 412 follow-up: emit recovery event after probe successes following
                  // a non-zero failure streak.
                  try {
                    events.onHealthRecovered(prev);
                  } catch (RuntimeException ex) {
                    LOG.warn("Telemetry events.onHealthRecovered threw: {}", ex.getMessage());
                  }
                }
              } else {
                String reason =
                    probe.error() != null
                        ? probe.error()
                        : (probe.statusCode() > 0
                            ? "HTTP " + probe.statusCode()
                            : "health probe failed");
                lastPeriodicHealthError.set(reason);
                handlePeriodicHealthFailure(reason, external);
              }
            },
            PERIODIC_HEALTH_INTERVAL_MS,
            PERIODIC_HEALTH_INTERVAL_MS,
            TimeUnit.MILLISECONDS);

    LOG.debug("Scheduled periodic health checks every {}ms", PERIODIC_HEALTH_INTERVAL_MS);
  }

  void handlePeriodicHealthFailure(String reason, boolean external) {
    int failures = consecutiveHealthFailures.incrementAndGet();
    LOG.warn(
        "llama-server periodic health check failed ({}/{}): {}",
        failures,
        CONSECUTIVE_FAILURES_BEFORE_RESTART,
        reason);

    boolean restartTriggered = failures >= CONSECUTIVE_FAILURES_BEFORE_RESTART;

    // Tempdoc 412 follow-up: emit a typed health-failure event with severity. The HealthCode
    // is derived from the reason text — a small heuristic since the periodic health probe
    // surfaces a free-form string today.
    try {
      InferenceFailure.HealthFailure failure =
          new InferenceFailure.HealthFailure(classifyHealthFailure(reason), reason, null);
      events.onHealthFailure(failure, failures, restartTriggered);
    } catch (RuntimeException ex) {
      LOG.warn("Telemetry events.onHealthFailure threw: {}", ex.getMessage());
    }

    if (restartTriggered) {
      LOG.error("Too many consecutive health failures, treating as crash");
      stopPeriodicHealthCheck();
      if (external) {
        goOfflineFromExternalFailure.accept(reason);
      } else {
        handleServerCrash();
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
    if (periodicHealthTask != null) {
      periodicHealthTask.cancel(false);
      periodicHealthTask = null;
    }
    consecutiveHealthFailures.set(0);
  }

  // ==================== Crash Recovery ====================

  // Package-private for direct invocation by Bug F regression test
  // (LlamaServerOpsCrashTelemetryTest); the production caller is the crashMonitor future
  // inside startManagedProcessAndMonitor.
  void handleServerCrash() {
    int crashes = crashCount.incrementAndGet();
    LOG.warn("Server crash #{}", crashes);

    // Tempdoc 412 follow-up Bug F: process-death scenarios reach this method via the
    // crashMonitor future (Process.waitFor returning non-zero exit), bypassing
    // handlePeriodicHealthFailure entirely (its early-return at the process-dead check skips
    // probing). Without an explicit emit here, inference.health.failure_total never fires for
    // taskkill / SIGKILL / process-crash scenarios — leaving the most operationally important
    // health failure invisible to the metric stream. Emit a typed PROCESS_DIED HealthFailure
    // before the recovery decision so operators can see the event.
    try {
      InferenceFailure.HealthFailure failure =
          new InferenceFailure.HealthFailure(
              HealthCode.PROCESS_DIED, "llama-server process exited unexpectedly", null);
      events.onHealthFailure(failure, crashes, /* restartTriggered */ true);
    } catch (RuntimeException ex) {
      LOG.warn("Telemetry events.onHealthFailure (process-died) threw: {}", ex.getMessage());
    }

    if (crashes >= MAX_CRASHES) {
      LOG.error("Max crashes exceeded, entering OFFLINE mode");
      goOfflineFromMaxCrashes.run();
      return;
    }

    // Crash recovery delay: immediate for first crash, 5s thereafter (fire-and-forget)
    long delay = crashes == 1 ? 0 : CRASH_RECOVERY_DELAY_MS;
    scheduleRecoveryTask(delay);
  }

  @SuppressWarnings("FutureReturnValueIgnored") // fire-and-forget recovery task
  private void scheduleRecoveryTask(long delay) {
    recoveryScheduler.schedule(
        () -> {
          try {
            LOG.info("Attempting server restart...");
            // Ensure any owned process is stopped before attempting restart (prevents port
            // conflicts).
            stopLlamaServer();
            startLlamaServer();
            waitForServerHealth();
            crashCount.set(0);
            LOG.info("Server recovered");
          } catch (Exception e) {
            LOG.error("Recovery failed", e);
            handleServerCrash();
          }
        },
        delay,
        TimeUnit.MILLISECONDS);
  }

  // ==================== State Accessors ====================

  boolean isExternalServerActive() {
    return usingExternal && process == null;
  }

  /**
   * Raw {@code usingExternal} field value (no {@code process == null} guard). Used by ILM /
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

  void resetCrashCounters() {
    crashCount.set(0);
    consecutiveHealthFailures.set(0);
  }

  InferenceLifecycleManager.ExternalServerDiagnostics buildExternalDiagnostics(
      boolean usingExternal) {
    return propsOps.buildExternalDiagnostics(
        usingExternal,
        lastPeriodicHealthOkAtMs.get(),
        lastPeriodicHealthError.get(),
        consecutiveHealthFailures.get());
  }

  /** Returns true if the last /props response indicated vision support (runtime signal). */
  boolean hasVisionCapabilityFromProps() {
    return propsOps.hasVisionCapability();
  }

  /** Tempdoc 682 Item 2: expected build tag from the staging pin marker; null = unknown. */
  String expectedServerBuild() {
    return propsOps.expectedServerBuild();
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
    recoveryScheduler.shutdownNow();
    try {
      healthScheduler.awaitTermination(PROCESS_KILL_TIMEOUT_SECS, TimeUnit.SECONDS);
      recoveryScheduler.awaitTermination(PROCESS_KILL_TIMEOUT_SECS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ==================== Policy Helpers ====================

  private static boolean policyGpuAccelerationEnabled() {
    return Boolean.parseBoolean(System.getProperty(POLICY_GPU_ACCEL_PROP, "true"));
  }

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

  private String diagnoseServerFailure(int exitCode) {
    String base = "llama-server process exited before becoming healthy (exit code " + exitCode + ")";
    Path logFile = resolveLlamaServerLogFile();
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
      if (logTail.contains("address already in use")) {
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

  private static Path resolveLlamaServerLogFile() {
    String home = System.getenv("JUSTSEARCH_HOME");
    if (home != null && !home.isBlank()) {
      return Path.of(home).resolve("logs").resolve("llama-server.log");
    }
    ConfigStore cs = ConfigStore.globalOrNull();
    Path dataDir = cs != null ? cs.get().paths().dataDir() : null;
    if (dataDir != null) {
      return dataDir.resolve("logs").resolve("llama-server.log");
    }
    return Path.of(System.getProperty("user.dir")).resolve("logs").resolve("llama-server.log");
  }

  private static Path resolveBundledRuntimeBinDirBestEffort() {
    try {
      ConfigStore cs = ConfigStore.globalOrNull();
      Path repoRoot = cs != null ? cs.get().paths().repoRoot() : null;
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
