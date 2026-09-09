/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.StartupReason;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.api.TransitionCode;
import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.OnlineAiService.AiUsage;
import io.justsearch.app.api.OnlineAiService.VisionCompletionResult;
import io.justsearch.app.api.SamplingParams;
import net.jcip.annotations.ThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton managing exclusive GPU access between Online and Indexing modes.
 *
 * <p>Enforces the rule: only one inference engine holds the GPU at any time, preventing VRAM
 * conflicts on systems with limited GPU memory (8GB).
 *
 * <p>Tempdoc 518 decomposition: this class is the thin orchestrator face. The transition
 * envelope, lock, state machine, observed-runtime view, and listener registry live in {@link
 * TransitionRunner}. Process / health / adoption lives in {@link LlamaServerOps}. Chat / vision
 * / streaming lives in {@link OnlineModeOps}. Tokenization probing lives in {@link
 * TokenEndpointOps}. {@code /props} interpretation lives in {@link ServerPropsOps}. This
 * class composes them, owns the public surface, and threads typed events through the runner.
 *
 * <h2>Modes</h2>
 *
 * <ul>
 *   <li><b>ONLINE:</b> llama-server running via HTTP — Chat, Vision, Summarization, Q&amp;A.
 *   <li><b>INDEXING:</b> FFM embedding backend — bulk embedding generation.
 *   <li><b>TRANSITIONING:</b> Switching between modes (internal; suppressed in typed events).
 *   <li><b>OFFLINE:</b> GPU features unavailable (crash recovery failed or insufficient VRAM).
 * </ul>
 *
 * @see InferenceConfig
 * @see ModeTransitionException
 * @see TransitionRunner
 */
@ThreadSafe
public class InferenceLifecycleManager
    implements Closeable, io.justsearch.app.api.OnlineAiLifecycleControl {

  private static final Logger LOG = LoggerFactory.getLogger(InferenceLifecycleManager.class);

  // Tempdoc 518 P4: Mode enum + ModeChangeListener interface promoted to app-api so the
  // role-typed interfaces (OnlineAiService / OnlineAiRuntimeControl /
  // OnlineAiRuntimeIntrospection / OnlineAiLifecycleControl) can reference them without
  // importing the implementation package. The nested types previously declared here
  // (io.justsearch.app.api.Mode, io.justsearch.app.api.ModeChangeListener) are gone;
  // imports above resolve to io.justsearch.app.api.Mode and io.justsearch.app.api.ModeChangeListener.

  /** Restart/apply policy for runtime config changes. */
  public enum RestartPolicy {
    APPLY_ONLY,
    RESTART_IF_ONLINE,
    RESTART_ALWAYS
  }

  // ==================== State ====================

  /** Runtime view + lock + state-machine + listeners + telemetry envelope live in the runner. */
  private final TransitionRunner runner;

  /** Mutable inference config; updated under {@link TransitionRunner#lock} by applyConfig. */
  private volatile InferenceConfig config;

  /** GPU/VRAM capability probe. */
  private final GpuCapabilitiesService gpuCapabilitiesService;

  // Collaborators (composed)
  private final HttpClient httpClient;
  private final java.util.concurrent.ExecutorService httpExecutor;
  private final InferenceExecutorRegistrations executorRegistrations;
  private final ObjectMapper objectMapper;
  private final TokenEndpointOps tokenOps;
  private final OnlineModeOps onlineOps;
  private final LlamaServerOps serverOps;

  // Typed observability events. Mirrors the runner's events for the body's own emissions.
  private final InferenceTelemetryEvents events;

  // Procedure-scoped config stash (VDU procedure). Tempdoc 737 task 6 judgment: §12d listed this as
  // "subsumed by spec-return", but that conflated two levels. The reconciler's return-to-spec is
  // MODE-level (ONLINE/INDEXING/OFFLINE via switchTo*); this stash restores INFERENCE CONFIG —
  // context length, vision-safe flags (-np 1, --cache-ram 0) — which the mode-level reconciler does
  // not model. The two are genuinely distinct, so the stash STAYS as a procedure-scoped
  // enter/exit config restore. The applyConfig(VDU config) call is real behavior (single slot, no
  // cache) and also stays. Only the *mode* return-to-spec moved to the reconciler.
  private volatile InferenceConfig preVduConfig;

  // Configuration constants
  private static final long VRAM_FLUSH_DELAY_MS = 2000;
  private static final Duration HTTP_CLIENT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

  // ==================== Constructors ====================

  public InferenceLifecycleManager(
      io.justsearch.core.execution.EngineExecutorRegistry executorRegistry,
      InferenceConfig config) {
    this(executorRegistry, config, NoopInferenceTelemetryEvents.INSTANCE,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  /**
   * Tempdoc 518 Appendix G W4.B.1 — install the persistent transition log. Called once by
   * the composition root after ILM construction (when the data directory is known).
   * Subsequent transitions are recorded to the sidecar in addition to the in-memory ring.
   */
  public void installTransitionLog(InferenceTransitionLog log) {
    runner.setTransitionLog(log);
  }

  public InferenceLifecycleManager(
      io.justsearch.core.execution.EngineExecutorRegistry executorRegistry,
      InferenceConfig config,
      InferenceTelemetryEvents events) {
    this(executorRegistry, config, events, io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  public InferenceLifecycleManager(
      io.justsearch.core.execution.EngineExecutorRegistry executorRegistry,
      InferenceConfig config,
      InferenceTelemetryEvents events,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    this.executorRegistrations = new InferenceExecutorRegistrations(executorRegistry);
    this.events = Objects.requireNonNull(events, "events");
    this.config = config;
    this.gpuCapabilitiesService = new GpuCapabilitiesService();
    Object lock = new Object();
    ModeStateMachine modeState = new ModeStateMachine();
    this.runner = new TransitionRunner(lock, modeState, events);

    PropsObserver propsObserver =
        new PropsObserver() {
          @Override
          public void onModelIdObserved(String modelId) {
            onModelIdUpdatedInternal(modelId);
          }

          @Override
          public void onContextTokensObserved(int contextTokens) {
            runner.mergeProps(null, contextTokens);
          }

          @Override
          public String observedModelId() {
            return runner.view().lastKnownModelId();
          }

          @Override
          public Integer observedContextTokens() {
            return runner.view().lastKnownContextTokens();
          }
        };

    java.util.concurrent.ExecutorService openedHttp = null;
    OnlineModeOps openedOnline = null;
    LlamaServerOps openedServer = null;
    try {
      openedHttp = executorRegistrations.http.open(namedDaemonFactory("inference-http"));
      HttpClient createdHttpClient =
          HttpClient.newBuilder()
              .executor(openedHttp)
              .connectTimeout(HTTP_CLIENT_CONNECT_TIMEOUT)
              .build();
      ObjectMapper createdObjectMapper = new ObjectMapper();
      TokenEndpointOps createdTokenOps =
          new TokenEndpointOps(
              createdHttpClient,
              createdObjectMapper,
              runner::currentMode,
              () -> this.config.serverPort());
      openedOnline =
          new OnlineModeOps(
              executorRegistrations,
              createdHttpClient,
              createdObjectMapper,
              runner::currentMode,
              () -> this.config.serverPort(),
              () -> runner.view().lastKnownModelId(),
              () -> this.config.modelPath().getFileName().toString(),
              this.events);
      openedServer =
          new LlamaServerOps(
              executorRegistrations,
              createdHttpClient,
              createdObjectMapper,
              () -> this.config,
              gpuCapabilitiesService,
              runner::currentMode,
              propsObserver,
              this::handleMaxCrashOffline,
              this::handleExternalFailureOffline,
              this.events,
              childRegistry);
      this.httpExecutor = openedHttp;
      this.httpClient = createdHttpClient;
      this.objectMapper = createdObjectMapper;
      this.tokenOps = createdTokenOps;
      this.onlineOps = openedOnline;
      this.serverOps = openedServer;
    } catch (RuntimeException | Error failure) {
      closeAfterConstructionFailure(openedServer, openedOnline, openedHttp, failure);
      try {
        executorRegistrations.close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }

    LOG.info(
        "InferenceLifecycleManager created with config: serverPort={}, contextSize={}, gpuLayers={}",
        config.serverPort(),
        config.contextSize(),
        config.gpuLayers());
  }

  /** Crash-recovery callback installed on {@link LlamaServerOps}. Tempdoc 518 P1. */
  private void handleMaxCrashOffline() {
    synchronized (runner.lock()) {
      tokenOps.clearCaches();
      runner.runForceOffline(TransitionReason.CRASH_RECOVERY, null);
    }
  }

  /** External-server-unhealthy callback installed on {@link LlamaServerOps}. Tempdoc 518 P1. */
  private void handleExternalFailureOffline(String reason) {
    synchronized (runner.lock()) {
      if (!serverOps.isUsingExternalRaw() || runner.currentMode() != Mode.ONLINE) {
        return;
      }
      LOG.error(
          "External llama-server on port {} became unhealthy: {}. Switching to OFFLINE.",
          this.config.serverPort(),
          reason);
      serverOps.setUsingExternal(false);
      tokenOps.clearCaches();
      runner.runForceOffline(TransitionReason.CRASH_RECOVERY, null);
    }
  }

  // ==================== Mode Queries ====================

  public InferenceConfig currentConfig() {
    return config;
  }

  public boolean hasVisionCapability() {
    InferenceConfig cfg = config;
    boolean configHasVision = cfg != null && cfg.mmprojPath() != null;
    return configHasVision || serverOps.hasVisionCapabilityFromProps();
  }

  public boolean isUsingExternalLlamaServer() {
    return serverOps.isExternalServerActive();
  }

  /**
   * Tempdoc 682 Item 2: expected llama-server build tag ({@code bNNNN}) from the staging pin
   * marker ({@code runtime-version.txt} next to the configured executable); null = unknown (a
   * supported state — externally-staged binaries carry no pin).
   */
  public String expectedLlamaServerBuild() {
    return serverOps.expectedServerBuild();
  }

  /**
   * Tempdoc 682 Item 2: actually-running llama-server build tag observed from {@code /props}
   * ({@code build_info}); null until a server has been started or adopted and reported one.
   */
  public String actualLlamaServerBuild() {
    return serverOps.actualServerBuild();
  }

  /**
   * Tempdoc 835 §9c.2: whether the running llama-server can honour reasoning generation —
   * {@code SUPPORTED} / {@code UNSUPPORTED} / {@code DISABLED} / {@code UNKNOWN}, decided by
   * launch-argument acceptance. Published on the runtime manifest's {@code ai.thinkingSupport}.
   */
  public String llamaServerThinkingSupport() {
    return serverOps.thinkingSupport();
  }

  /**
   * Tempdoc 883 decision 1: the context window this process launched llama-server with, and why —
   * null when it launched none. Published on {@code /api/inference/status}.
   */
  public io.justsearch.app.api.OnlineAiRuntimeIntrospection.ContextWindow launchedContextWindow() {
    return serverOps.contextWindow();
  }

  public long getLastStartupDurationMs() {
    return runner.view().lastStartupDurationMs();
  }

  /** Tempdoc 518 Appendix F W2.1 — failure-history snapshot. */
  public List<io.justsearch.app.api.OnlineAiRuntimeIntrospection.FailureRecord>
      recentFailures(int limit) {
    return runner.recentFailures(limit);
  }

  /** Tempdoc 518 Appendix F W3.3 — monotonic generation counter. */
  public long currentGeneration() {
    return runner.generation();
  }

  /** Tempdoc 518 Appendix F W3.2 — mode-transition history snapshot. */
  public List<io.justsearch.app.api.OnlineAiRuntimeIntrospection.TransitionRecord>
      recentTransitions(int limit) {
    return runner.recentTransitions(limit);
  }

  public Mode getCurrentMode() {
    return runner.currentMode();
  }

  /** Snapshot of the observed runtime state. Tempdoc 518 P2. */
  public InferenceRuntimeView view() {
    return runner.view();
  }

  public Optional<RuntimeIdentity> identity() {
    return runner.view().identityOptional();
  }

  public Optional<InferenceFailure> lastFailure() {
    return runner.view().lastFailureOptional();
  }

  public boolean isOnline() {
    return runner.currentMode() == Mode.ONLINE;
  }

  public boolean isIndexing() {
    return runner.currentMode() == Mode.INDEXING;
  }

  public boolean isOffline() {
    return runner.currentMode() == Mode.OFFLINE;
  }

  // ==================== VRAM Status (for debugging) ====================

  public List<String> getEffectiveVramFlags() {
    return serverOps.getEffectiveVramFlags();
  }

  public String getVramTierDetected() {
    return serverOps.getVramTierDetected();
  }

  public String getVramDetectionSource() {
    return serverOps.getVramDetectionSource();
  }

  public String getCudaRuntimeWarning() {
    return serverOps.getCudaRuntimeWarning();
  }

  // ==================== Mode Transitions ====================

  @Override
  public void switchToOnlineMode() throws ModeTransitionException {
    switchToOnlineMode(TransitionReason.USER_SWITCH);
  }

  /**
   * Tempdoc 737 (task 5): reason-bearing overload so reconciler-/procedure-initiated transitions
   * carry an appropriate {@link TransitionReason} into {@link TransitionRunner#run} (telemetry +
   * ndjson forensic log) instead of the hard-coded {@code USER_SWITCH}. The no-arg method delegates
   * here with {@code USER_SWITCH} for source-compatibility.
   */
  public void switchToOnlineMode(TransitionReason reason) throws ModeTransitionException {
    runner.run(
        reason,
        events::onStartupFailure,
        priorView -> {
          if (priorView.phase() == Mode.ONLINE) {
            LOG.debug("Already in Online Mode");
            return TransitionOutcome.success(Mode.ONLINE, priorView);
          }

          // Validate BYO assets before attempting to start.
          try {
            config.validate();
          } catch (Exception e) {
            return TransitionOutcome.failure(
                new InferenceFailure.ConfigFailure(
                    ConfigCode.INVALID_CONFIG,
                    "Invalid inference configuration: " + safeMessage(e)),
                priorView);
          }

          // VRAM precondition when GPU offload is requested.
          if (config.gpuLayers() > 0) {
            gpuCapabilitiesService.invalidateNvidiaSmiCache();
            Long totalVramBytes = readTotalVramBytes();
            if (totalVramBytes == null) {
              LOG.warn(
                  "VRAM detection unavailable (NVML and nvidia-smi both returned no value). "
                      + "Proceeding with GPU Online Mode because gpuLayers={} was explicitly requested.",
                  config.gpuLayers());
            } else if (totalVramBytes < HardwareProfile.MINIMUM_VRAM_FOR_GGUF) {
              return TransitionOutcome.failure(
                  new InferenceFailure.StartupFailure(
                      StartupCode.INSUFFICIENT_VRAM,
                      "Insufficient VRAM for GPU Online Mode: "
                          + formatVramDescription(totalVramBytes)
                          + " (set GPU layers to 0 for CPU mode)",
                      null),
                  priorView);
            }
          }

          LOG.info("Transitioning to Online Mode...");
          tokenOps.clearCaches();

          try {
            events.onStartupAttempt(config, StartupReason.COLD_START, TargetPhase.ONLINE);
          } catch (RuntimeException ex) {
            LOG.warn("Telemetry events.onStartupAttempt threw: {}", ex.getMessage());
          }

          long startupStart = System.currentTimeMillis();
          try {
            if (priorView.phase() != Mode.OFFLINE) {
              LOG.debug("Waiting {}ms for VRAM flush", VRAM_FLUSH_DELAY_MS);
              Thread.sleep(VRAM_FLUSH_DELAY_MS);
            }
            // Tempdoc 518 fix A: wipe any stale /props observations from the prior server
            // instance before starting a new one. The new server's /props observation (if it
            // fires) will repopulate via runner.mergeProps; if /props doesn't fire, the view
            // remains correctly null rather than carrying the prior server's stale data.
            runner.clearProps();
            serverOps.startLlamaServer();
            serverOps.waitForServerHealth();
            long elapsed = System.currentTimeMillis() - startupStart;
            LOG.info("Inference startup completed in {}ms", elapsed);

            // Tempdoc 518 fix A: read the LATEST view (not priorView) so /props
            // observations recorded via runner.mergeProps() during startLlamaServer
            // (model-id, context-tokens) are preserved on the installed view.
            // priorView was a stale snapshot taken at body entry, before any IO.
            InferenceRuntimeView nextView =
                runner
                    .view()
                    .withPhase(Mode.ONLINE)
                    .withExternal(serverOps.isUsingExternalRaw())
                    .withStartupDuration(elapsed);

            try {
              events.onStartupComplete(
                  config,
                  Duration.ofMillis(elapsed),
                  RuntimeIdentity.nonProcess(runner.generation() + 1),
                  TargetPhase.ONLINE);
            } catch (RuntimeException ex) {
              LOG.warn("Telemetry events.onStartupComplete threw: {}", ex.getMessage());
            }
            return TransitionOutcome.success(Mode.ONLINE, nextView);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TransitionOutcome.failure(
                new InferenceFailure.TransitionFailure(
                    TransitionCode.INTERRUPTED, "Transition interrupted", e),
                priorView);
          } catch (ModeTransitionException mte) {
            serverOps.stopPeriodicHealthCheck();
            InferenceRuntimeView rollbackView = priorView;
            if (serverOps.isExternalServerActive()) {
              serverOps.setUsingExternal(false);
              rollbackView = priorView.withExternal(false);
            }
            return TransitionOutcome.failure(
                TransitionRunner.mapExceptionToFailure(mte), rollbackView);
          } catch (Exception e) {
            serverOps.stopPeriodicHealthCheck();
            InferenceRuntimeView rollbackView = priorView;
            if (serverOps.isExternalServerActive()) {
              serverOps.setUsingExternal(false);
              rollbackView = priorView.withExternal(false);
            }
            return TransitionOutcome.failure(
                new InferenceFailure.TransitionFailure(
                    TransitionCode.ONLINE_START_FAILED,
                    "Failed to switch to Online Mode: " + safeMessage(e),
                    e),
                rollbackView);
          }
        });
  }

  @Override
  public void switchToIndexingMode() throws ModeTransitionException {
    switchToIndexingMode(TransitionReason.USER_SWITCH);
  }

  /** Tempdoc 737 (task 5): reason-bearing overload — see {@link #switchToOnlineMode(TransitionReason)}. */
  public void switchToIndexingMode(TransitionReason reason) throws ModeTransitionException {
    runner.run(
        reason,
        events::onStartupFailure,
        priorView -> {
          if (priorView.phase() == Mode.INDEXING) {
            LOG.debug("Already in Indexing Mode");
            return TransitionOutcome.success(Mode.INDEXING, priorView);
          }

          LOG.info("Transitioning to Indexing Mode...");
          tokenOps.clearCaches();

          try {
            events.onStartupAttempt(config, StartupReason.COLD_START, TargetPhase.INDEXING);
          } catch (RuntimeException ex) {
            LOG.warn("Telemetry events.onStartupAttempt threw: {}", ex.getMessage());
          }

          long startupStart = System.currentTimeMillis();
          try {
            serverOps.stopLlamaServer();
            LOG.debug("Waiting {}ms for VRAM flush", VRAM_FLUSH_DELAY_MS);
            Thread.sleep(VRAM_FLUSH_DELAY_MS);

            // Tempdoc 518 P1 — uniform cleanup: indexing-mode startup transitioning from
            // ONLINE must clear external-adoption state. Pre-decomposition this branch was
            // missing (observations.md log entry 2026-05-18); the bug dissolves under the
            // envelope's uniform cleanup contract.
            InferenceRuntimeView nextView =
                priorView
                    .withPhase(Mode.INDEXING)
                    .withExternal(false)
                    .withContextTokens(null)
                    .withModelId(null);

            try {
              events.onStartupComplete(
                  config,
                  Duration.ofMillis(System.currentTimeMillis() - startupStart),
                  RuntimeIdentity.nonProcess(runner.generation() + 1),
                  TargetPhase.INDEXING);
            } catch (RuntimeException ex) {
              LOG.warn("Telemetry events.onStartupComplete threw: {}", ex.getMessage());
            }
            return TransitionOutcome.success(Mode.INDEXING, nextView);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TransitionOutcome.failure(
                new InferenceFailure.TransitionFailure(
                    TransitionCode.INTERRUPTED, "Transition interrupted", e),
                buildIndexingFailureRollback(priorView));
          } catch (Exception e) {
            return TransitionOutcome.failure(
                new InferenceFailure.TransitionFailure(
                    TransitionCode.INDEXING_START_FAILED,
                    "Failed to switch to Indexing Mode: " + safeMessage(e),
                    e),
                buildIndexingFailureRollback(priorView));
          }
        });
  }

  /**
   * Uniform cleanup contract for switchToIndexingMode failure paths. Tempdoc 518 P1 — closes
   * the pre-existing bug (observations.md 2026-05-18) where the legacy switchToIndexingMode
   * catch blocks did not clear external-adoption state + periodic health check, leaking
   * sticky external state when indexing-mode startup failed after transitioning from ONLINE.
   */
  private InferenceRuntimeView buildIndexingFailureRollback(InferenceRuntimeView priorView) {
    if (serverOps.isExternalServerActive() || serverOps.isUsingExternalRaw()) {
      serverOps.stopPeriodicHealthCheck();
      serverOps.setUsingExternal(false);
      return priorView.withExternal(false);
    }
    return priorView;
  }

  // ==================== VDU Mode ====================

  public void enterVduMode() throws ModeTransitionException {
    synchronized (runner.lock()) {
      if (runner.currentMode() != Mode.ONLINE) {
        throw modeTransition(
            ModeTransitionException.Reason.ONLINE_START_FAILED,
            "VDU mode requires ONLINE mode (current: " + runner.currentMode() + ")");
      }
      if (config.vduMode()) {
        LOG.debug("Already in VDU mode");
        return;
      }
      LOG.info("Entering VDU mode (restarting server with vision-safe flags)...");
      preVduConfig = config;
      applyConfig(
          config.withVduMode(true), RestartPolicy.RESTART_ALWAYS, TransitionReason.VDU_ENTER);
    }
  }

  public void exitVduMode() throws ModeTransitionException {
    synchronized (runner.lock()) {
      InferenceConfig saved = preVduConfig;
      if (saved == null || !config.vduMode()) {
        LOG.debug("Not in VDU mode; exitVduMode is a no-op");
        return;
      }
      LOG.info("Exiting VDU mode (restoring normal server configuration)...");
      preVduConfig = null;
      applyConfig(saved, RestartPolicy.RESTART_ALWAYS, TransitionReason.VDU_EXIT);
    }
  }

  // ==================== ModeTransitionException helpers (legacy shape, removed in Slice 2) ====================

  static String safeMessage(Throwable t) {
    if (t == null) return "unknown";
    String m = t.getMessage();
    if (m != null && !m.isBlank()) {
      return m;
    }
    return t.getClass().getSimpleName();
  }

  static ModeTransitionException modeTransition(
      ModeTransitionException.Reason reason, String message) {
    return new ModeTransitionException(reason, message);
  }

  static ModeTransitionException modeTransition(
      ModeTransitionException.Reason reason, String message, Throwable cause) {
    return new ModeTransitionException(reason, message, cause);
  }

  static ModeTransitionException modeTransitionWithCauseMessage(
      ModeTransitionException.Reason reason, String messagePrefix, Throwable cause) {
    return modeTransition(reason, messagePrefix + safeMessage(cause), cause);
  }

  static ModeTransitionException asModeTransition(
      Throwable throwable,
      ModeTransitionException.Reason fallbackReason,
      String fallbackMessagePrefix) {
    if (throwable instanceof ModeTransitionException mte) {
      return mte;
    }
    return modeTransitionWithCauseMessage(fallbackReason, fallbackMessagePrefix, throwable);
  }

  // ==================== Runtime Config Apply ====================

  public void applyConfig(InferenceConfig newConfig, RestartPolicy policy)
      throws ModeTransitionException {
    applyConfig(newConfig, policy, TransitionReason.CONFIG_APPLY);
  }

  public void applyConfig(
      InferenceConfig newConfig, RestartPolicy policy, TransitionReason transitionReason)
      throws ModeTransitionException {
    if (newConfig == null) {
      InferenceFailure failure =
          new InferenceFailure.ConfigFailure(ConfigCode.CONFIG_REQUIRED, "Config is required");
      runner.recordFailureOutsideTransition(failure);
      try {
        events.onConfigApplyFailure(failure);
      } catch (RuntimeException ex) {
        LOG.warn("Telemetry events.onConfigApplyFailure threw: {}", ex.getMessage());
      }
      throw modeTransition(ModeTransitionException.Reason.CONFIG_REQUIRED, "Config is required");
    }
    RestartPolicy effective = policy == null ? RestartPolicy.RESTART_IF_ONLINE : policy;

    synchronized (runner.lock()) {
      if (runner.currentMode() == Mode.TRANSITIONING) {
        InferenceFailure failure =
            new InferenceFailure.ConfigFailure(
                ConfigCode.ALREADY_TRANSITIONING,
                "Inference runtime is transitioning; try again shortly");
        runner.recordFailureOutsideTransition(failure);
        try {
          events.onConfigApplyFailure(failure);
        } catch (RuntimeException ex) {
          LOG.warn("Telemetry events.onConfigApplyFailure threw: {}", ex.getMessage());
        }
        throw modeTransition(
            ModeTransitionException.Reason.ALREADY_TRANSITIONING,
            "Inference runtime is transitioning; try again shortly");
      }

      final InferenceConfig oldConfig = this.config;
      final boolean shouldRestart =
          switch (effective) {
            case RESTART_ALWAYS -> true;
            case RESTART_IF_ONLINE -> runner.currentMode() == Mode.ONLINE;
            case APPLY_ONLY -> false;
          };

      long applyStartNanos = System.nanoTime();
      try {
        events.onConfigApplyAttempt(oldConfig, newConfig, shouldRestart);
      } catch (RuntimeException ex) {
        LOG.warn("Telemetry events.onConfigApplyAttempt threw: {}", ex.getMessage());
      }

      if (shouldRestart && serverOps.isExternalServerActive()) {
        String msg =
            "Cannot restart/apply config while using an external llama-server instance on port "
                + config.serverPort()
                + ". Stop the external process first or detach to a managed server.";
        InferenceFailure failure =
            new InferenceFailure.ConfigFailure(ConfigCode.EXTERNAL_SERVER_CONFLICT, msg);
        runner.recordFailureOutsideTransition(failure);
        try {
          events.onConfigApplyFailure(failure);
        } catch (RuntimeException ex) {
          LOG.warn("Telemetry events.onConfigApplyFailure threw: {}", ex.getMessage());
        }
        throw modeTransition(
            ModeTransitionException.Reason.EXTERNAL_SERVER_CONFLICT, msg);
      }

      if (!shouldRestart) {
        this.config = newConfig;
        LOG.info(
            "Applied inference config (no restart): contextSize={}, gpuLayers={}, modelPath={}",
            newConfig.contextSize(),
            newConfig.gpuLayers(),
            newConfig.modelPath());
        try {
          events.onConfigApplyComplete(Duration.ofNanos(System.nanoTime() - applyStartNanos));
        } catch (RuntimeException ex) {
          LOG.warn("Telemetry events.onConfigApplyComplete threw: {}", ex.getMessage());
        }
        return;
      }

      // Restart path goes through the envelope.
      final Mode preApplyMode = runner.currentMode();

      try {
        runner.run(
            transitionReason,
            events::onConfigApplyFailure,
            priorView -> {
              try {
                newConfig.validate();
              } catch (Exception e) {
                return TransitionOutcome.failure(
                    new InferenceFailure.ConfigFailure(
                        ConfigCode.INVALID_CONFIG,
                        "Invalid inference configuration: " + safeMessage(e)),
                    priorView);
              }

              tokenOps.clearCaches();
              try {
                serverOps.stopLlamaServer();
              } catch (Exception ignore) {
                // best-effort stop; continue to apply.
              }

              InferenceLifecycleManager.this.config = newConfig;
              serverOps.resetCrashCounters();

              if (newConfig.gpuLayers() > 0) {
                gpuCapabilitiesService.invalidateNvidiaSmiCache();
                Long totalVramBytes = readTotalVramBytes();
                if (totalVramBytes == null) {
                  LOG.warn(
                      "VRAM detection unavailable (NVML and nvidia-smi both returned no value). "
                          + "Proceeding with GPU Online Mode because gpuLayers={} was explicitly requested.",
                      newConfig.gpuLayers());
                } else if (totalVramBytes < HardwareProfile.MINIMUM_VRAM_FOR_GGUF) {
                  return TransitionOutcome.failure(
                      new InferenceFailure.StartupFailure(
                          StartupCode.INSUFFICIENT_VRAM,
                          "Insufficient VRAM for GPU Online Mode: "
                              + formatVramDescription(totalVramBytes)
                              + " (set GPU layers to 0 for CPU mode)",
                          null),
                      priorView);
                }
              }

              try {
                // Tempdoc 518 fix A: wipe stale /props from prior server before restart.
                runner.clearProps();
                // Tempdoc 601: time the startup window (as switchToOnlineMode does) so
                // lastStartupDurationMs is surfaced after the activate + reload paths, which
                // reach Online via applyConfig — not switchToOnlineMode.
                long startupStart = System.currentTimeMillis();
                serverOps.startLlamaServer();
                serverOps.waitForServerHealth();
                long elapsed = System.currentTimeMillis() - startupStart;
                LOG.info("Inference config applied and llama-server restarted in {}ms", elapsed);
                try {
                  events.onConfigApplyComplete(
                      Duration.ofNanos(System.nanoTime() - applyStartNanos));
                } catch (RuntimeException ex) {
                  LOG.warn("Telemetry events.onConfigApplyComplete threw: {}", ex.getMessage());
                }
                // Tempdoc 518 fix A: build from runner.view() (new server's /props), not
                // priorView (stale). Tempdoc 601: record the measured startup duration.
                InferenceRuntimeView nextView =
                    runner
                        .view()
                        .withPhase(Mode.ONLINE)
                        .withExternal(serverOps.isUsingExternalRaw())
                        .withStartupDuration(elapsed);
                return TransitionOutcome.success(Mode.ONLINE, nextView);
              } catch (ModeTransitionException e) {
                return applyConfigRollback(oldConfig, preApplyMode, e, priorView);
              } catch (Exception e) {
                ModeTransitionException wrapped =
                    asModeTransition(
                        e,
                        ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
                        "Failed to apply inference config: ");
                return applyConfigRollback(oldConfig, preApplyMode, wrapped, priorView);
              }
            });
      } catch (ModeTransitionException mte) {
        try {
          events.onConfigApplyFailure(TransitionRunner.mapExceptionToFailure(mte));
        } catch (RuntimeException ex) {
          LOG.warn("Telemetry events.onConfigApplyFailure threw: {}", ex.getMessage());
        }
        throw mte;
      }
    }
  }

  /**
   * Helper for the apply-restart failure path. Restores prior config, attempts to re-start the
   * previously-online server if any. Returns a {@link TransitionOutcome.Failure} carrying the
   * original cause and the rollback view.
   */
  private TransitionOutcome applyConfigRollback(
      InferenceConfig oldConfig,
      Mode previousMode,
      ModeTransitionException cause,
      InferenceRuntimeView priorView) {
    LOG.error("Failed to apply inference config; attempting rollback", cause);
    try {
      try {
        serverOps.stopLlamaServer();
      } catch (Exception ignore) {
        // best-effort stop
      }
      this.config = oldConfig;
      serverOps.resetCrashCounters();

      if (previousMode == Mode.ONLINE) {
        if (oldConfig.gpuLayers() > 0) {
          gpuCapabilitiesService.invalidateNvidiaSmiCache();
          Long totalVramBytes = readTotalVramBytes();
          if (totalVramBytes != null && totalVramBytes < HardwareProfile.MINIMUM_VRAM_FOR_GGUF) {
            return TransitionOutcome.failure(
                new InferenceFailure.StartupFailure(
                    StartupCode.INSUFFICIENT_VRAM,
                    "Rollback failed (VRAM): "
                        + formatVramDescription(totalVramBytes)
                        + " (set GPU layers to 0 for CPU mode)",
                    cause),
                priorView);
          }
        }
        // Tempdoc 518 fix A: wipe stale /props before rollback restart.
        runner.clearProps();
        serverOps.startLlamaServer();
        serverOps.waitForServerHealth();
        // Tempdoc 518 fix A: build from runner.view() (which has the restored server's
        // /props observation) rather than priorView.
        InferenceRuntimeView rollbackView =
            runner.view().withPhase(Mode.ONLINE).withExternal(serverOps.isUsingExternalRaw());
        // Rollback succeeded but the apply failed — surface the original cause.
        return TransitionOutcome.failure(
            TransitionRunner.mapExceptionToFailure(cause), rollbackView);
      } else {
        return TransitionOutcome.failure(
            TransitionRunner.mapExceptionToFailure(cause), priorView);
      }
    } catch (Exception rollback) {
      LOG.error("Rollback failed; entering OFFLINE mode", rollback);
      return TransitionOutcome.failure(
          new InferenceFailure.TransitionFailure(
              TransitionCode.CONFIG_APPLY_FAILED,
              "Rollback failed: " + safeMessage(rollback),
              rollback),
          priorView.withPhase(Mode.OFFLINE));
    }
  }

  // ==================== Detach External ====================

  public record DetachExternalServerResult(boolean detached, int previousPort, int newPort) {}

  public DetachExternalServerResult detachExternalServer() throws ModeTransitionException {
    synchronized (runner.lock()) {
      if (runner.currentMode() == Mode.TRANSITIONING) {
        throw modeTransition(
            ModeTransitionException.Reason.ALREADY_TRANSITIONING,
            "Inference runtime is transitioning; try again shortly");
      }
      if (!(serverOps.isExternalServerActive() && runner.currentMode() == Mode.ONLINE)) {
        int port = config.serverPort();
        return new DetachExternalServerResult(false, port, port);
      }

      final InferenceConfig oldConfig = this.config;
      final int previousPort = oldConfig.serverPort();
      final int newPort;
      try {
        newPort = findFreeLoopbackPort();
      } catch (Exception e) {
        throw modeTransition(
            ModeTransitionException.Reason.PORT_ALLOCATION_FAILED,
            "Failed to allocate a free port for llama-server",
            e);
      }

      final boolean[] succeeded = {false};
      runner.run(
          TransitionReason.EXTERNAL_DETACH,
          events::onStartupFailure,
          priorView -> {
            tokenOps.clearCaches();
            try {
              serverOps.stopPeriodicHealthCheck();
              InferenceConfig next =
                  new InferenceConfig(
                      oldConfig.serverExecutable(),
                      oldConfig.modelPath(),
                      oldConfig.mmprojPath(),
                      newPort,
                      oldConfig.contextSize(),
                      oldConfig.gpuLayers(),
                      oldConfig.vduMode(),
                      // Port-only change: the (model, mmproj) pair is identical, so the profile
                      // claim carries. Dropping it here would make a detach look like a model swap.
                      oldConfig.chatProfileId());
              next.validate();

              serverOps.setUsingExternal(false);
              InferenceLifecycleManager.this.config = next;
              serverOps.resetCrashCounters();
              tokenOps.clearCaches();

              // Tempdoc 518 fix A: wipe stale /props from external server before starting
              // the new managed server. The managed server's /props observation (if it
              // fires) repopulates via runner.mergeProps.
              runner.clearProps();
              serverOps.startLlamaServer();
              serverOps.waitForServerHealth();

              LOG.info(
                  "Detached from external llama-server on port {} and started managed on port {}",
                  previousPort,
                  newPort);
              succeeded[0] = true;
              // Tempdoc 518 fix A: build from runner.view() (with new managed server's
              // /props observation) rather than priorView.
              InferenceRuntimeView nextView =
                  runner.view().withPhase(Mode.ONLINE).withExternal(false);
              return TransitionOutcome.success(Mode.ONLINE, nextView);
            } catch (Exception e) {
              LOG.error(
                  "Detach failed; reverting to external llama-server on port {} (best-effort)",
                  previousPort,
                  e);
              try {
                serverOps.stopLlamaServer();
              } catch (Exception stopEx) {
                LOG.debug("stop during detach rollback: {}", stopEx.getMessage(), stopEx);
              }
              InferenceLifecycleManager.this.config = oldConfig;
              serverOps.setUsingExternal(true);
              serverOps.schedulePeriodicHealthCheck();
              return TransitionOutcome.failure(
                  new InferenceFailure.TransitionFailure(
                      TransitionCode.ONLINE_START_FAILED,
                      "Failed to detach external llama-server: " + safeMessage(e),
                      e),
                  priorView.withExternal(true));
            }
          });
      return new DetachExternalServerResult(succeeded[0], previousPort, newPort);
    }
  }

  private static int findFreeLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  // ==================== Online Mode Operations — delegated to OnlineModeOps ====================

  public CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages, int maxTokens) {
    return onlineOps.chatCompletion(messages, maxTokens);
  }

  public CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages, int maxTokens, SamplingParams sampling) {
    return onlineOps.chatCompletion(messages, maxTokens, sampling);
  }

  public CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages,
      int maxTokens,
      SamplingParams sampling,
      io.justsearch.app.api.EngineWorkHandle work) {
    return onlineOps.chatCompletion(messages, maxTokens, sampling, work);
  }

  public CompletableFuture<String> visionCompletion(
      String prompt, byte[] imageBytes, int maxTokens) {
    return onlineOps.visionCompletion(prompt, imageBytes, maxTokens);
  }

  public CompletableFuture<VisionCompletionResult> visionCompletionDetailed(
      String prompt, byte[] imageBytes, int maxTokens) {
    return onlineOps.visionCompletionDetailed(prompt, imageBytes, maxTokens);
  }

  /** Tempdoc 677 Stage 2: sampling/seed-override overload — see {@link OnlineModeOps}. */
  public CompletableFuture<VisionCompletionResult> visionCompletionDetailed(
      String prompt, byte[] imageBytes, int maxTokens, SamplingParams sampling, Long seed) {
    return onlineOps.visionCompletionDetailed(prompt, imageBytes, maxTokens, sampling, seed);
  }

  public CompletableFuture<VisionCompletionResult> visionCompletionDetailed(
      String prompt,
      byte[] imageBytes,
      int maxTokens,
      SamplingParams sampling,
      Long seed,
      io.justsearch.app.api.EngineWorkHandle work) {
    return onlineOps.visionCompletionDetailed(prompt, imageBytes, maxTokens, sampling, seed, work);
  }

  public CompletableFuture<String> summarize(String content, int maxTokens) {
    return onlineOps.summarize(content, maxTokens);
  }

  public CompletableFuture<String> summarize(
      String content, int maxTokens, io.justsearch.app.api.EngineWorkHandle work) {
    return onlineOps.summarize(content, maxTokens, work);
  }

  public CompletableFuture<String> askQuestion(String context, String question, int maxTokens) {
    return onlineOps.askQuestion(context, question, maxTokens);
  }

  public CompletableFuture<String> askQuestion(
      String context,
      String question,
      int maxTokens,
      io.justsearch.app.api.EngineWorkHandle work) {
    return onlineOps.askQuestion(context, question, maxTokens, work);
  }

  public void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<String> onComplete,
      Consumer<Throwable> onError) {
    onlineOps.streamChat(messages, maxTokens, onChunk, onComplete, onError);
  }

  public void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError) {
    onlineOps.streamChat(messages, maxTokens, onChunk, onUsage, onComplete, onError);
  }

  public void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling) {
    onlineOps.streamChat(messages, maxTokens, onChunk, onUsage, onComplete, onError, sampling);
  }

  public void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling,
      boolean requireSentinel) {
    onlineOps.streamChat(
        messages, maxTokens, onChunk, onUsage, onComplete, onError, sampling, requireSentinel);
  }

  public void streamChatWithTools(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<String> onReasoningChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError) {
    onlineOps.streamChatWithTools(
        messages, tools, maxTokens, onChunk, onToolCallDelta, onReasoningChunk,
        onUsage, onComplete, onError);
  }

  public void streamChatWithTools(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<String> onReasoningChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling) {
    onlineOps.streamChatWithTools(
        messages, tools, maxTokens, onChunk, onToolCallDelta, onReasoningChunk,
        onUsage, onComplete, onError, sampling);
  }

  public void stream(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onContent,
      Consumer<String> onReasoning,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling,
      boolean requireSentinel) {
    stream(messages, tools, maxTokens, onContent, onReasoning, onToolCallDelta,
        onUsage, onComplete, onError, sampling, requireSentinel, null);
  }

  public void stream(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onContent,
      Consumer<String> onReasoning,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling,
      boolean requireSentinel,
      io.justsearch.app.api.EngineWorkHandle work) {
    onlineOps.stream(messages, tools, maxTokens, onContent, onReasoning, onToolCallDelta,
        onUsage, onComplete, onError, sampling, requireSentinel, work);
  }

  /** Returns the last observed llama-server context size (n_ctx), or null if unknown. */
  public Integer lastKnownContextTokens() {
    return runner.view().lastKnownContextTokens();
  }

  /** Returns the configured llama-server context size (-c), even if actual differs. */
  public int configuredContextTokens() {
    return config.contextSize();
  }

  /** Returns the best-effort model ID observed from {@code /props} or config, or {@code null}. */
  public String lastKnownModelId() {
    return runner.view().lastKnownModelId();
  }

  // ==================== External Server Diagnostics ====================

  /** Safe, low-cardinality diagnostics for external server adoption. */
  public record ExternalServerDiagnostics(
      boolean usingExternalLlamaServer,
      boolean verified,
      String verificationError,
      String modelId,
      Integer contextTokens,
      boolean modelMismatch,
      boolean contextTooSmall,
      long adoptedAtMs,
      long lastHealthOkAtMs,
      String lastHealthError,
      int consecutiveHealthFailures) {}

  public ExternalServerDiagnostics externalServerDiagnostics() {
    boolean usingExternal = serverOps.isExternalServerActive();
    return serverOps.buildExternalDiagnostics(usingExternal);
  }

  // ==================== Static helpers preserved for tests ====================

  /**
   * The online path's passage re-tagging, exposed for the ASSEMBLING side (tempdoc 849).
   *
   * <p>{@code RAGContext}'s section-aware cut exists partly so these headers survive a budget cut —
   * the old structure-blind trim flattened every newline, so every header failed to parse and the
   * fallback counter silently diverged from the {@code sources[n - 1]} the FE resolves. Asserting
   * that against a re-implementation of the parser would prove nothing about the real one, so the
   * head-side test round-trips through this delegate.
   */
  @SuppressWarnings("unused") // Called from RAGContextTest (ArchUnit excludes test sources)
  public static String formatContextAsNumberedPassages(String rawContext) {
    return OnlineModeOps.formatContextAsNumberedPassages(rawContext);
  }

  @SuppressWarnings("unused") // Called from LlamaServerUsageParsingTest, OnlineModeOpsTest
  static AiUsage extractUsageFromChatChunk(JsonNode root) {
    return OnlineModeOps.extractUsageFromChatChunk(root);
  }

  @SuppressWarnings("unused") // Package-private delegation for test access (UtilsTest)
  static Integer asIntOrNull(JsonNode node) {
    return InferenceHttpHelpers.asIntOrNull(node);
  }

  // ==================== Token Counting (Phase 2 RAG) — delegated to TokenEndpointOps ====================

  public Optional<Integer> countTokens(String text) {
    return tokenOps.countTokens(text);
  }

  public Optional<Integer> countTokens(
      String text, io.justsearch.app.api.EngineWorkHandle work) {
    return onlineOps.callOwned(work, () -> tokenOps.countTokens(text));
  }

  public Optional<String> applyTemplate(List<Map<String, Object>> messages) {
    return tokenOps.applyTemplate(messages);
  }

  public Optional<String> applyTemplate(
      List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
    return tokenOps.applyTemplate(messages, tools);
  }

  public Optional<Integer> countPromptTokens(List<Map<String, Object>> messages) {
    return tokenOps.countPromptTokens(messages);
  }

  public Optional<Integer> countPromptTokens(
      List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
    return tokenOps.countPromptTokens(messages, tools);
  }

  public Optional<Integer> countPromptTokens(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      io.justsearch.app.api.EngineWorkHandle work) {
    return onlineOps.callOwned(work, () -> tokenOps.countPromptTokens(messages, tools));
  }

  public boolean supportsTokenize() {
    return tokenOps.supportsTokenize();
  }

  public boolean supportsApplyTemplate() {
    return tokenOps.supportsApplyTemplate();
  }

  // ==================== Process Management — delegated to LlamaServerOps ====================

  // Package-private delegation stubs for testing.

  @SuppressWarnings("unused") // Called from InferenceLifecycleManagerExternalServerTest
  void startLlamaServer() throws IOException, ModeTransitionException {
    serverOps.startLlamaServer();
  }

  @SuppressWarnings("unused") // Called from InferenceLifecycleManagerExternalServerTest
  void handlePeriodicHealthFailure(String reason, boolean external) {
    serverOps.handlePeriodicHealthFailure(reason, external);
  }

  @SuppressWarnings("unused") // Called from ServerPropsOpsTest, PropsInsightsTest
  void updateFromPropsBestEffort(JsonNode root) {
    serverOps.updateFromPropsBestEffort(root);
  }

  @SuppressWarnings("unused") // Called from LlamaServerPropsParsingTest
  static Integer extractContextTokensFromProps(JsonNode root) {
    return ServerPropsOps.extractContextTokensFromProps(root);
  }

  @SuppressWarnings("unused") // Called from InferenceLifecycleManagerUtilsTest
  static Integer asPositiveInt(JsonNode node) {
    return ServerPropsOps.asPositiveInt(node);
  }

  /**
   * Returns the raw external-server adoption flag, without the {@code process == null} guard
   * applied by the public {@link #isUsingExternalLlamaServer()} accessor. Package-private for
   * tests; reads through to {@link LlamaServerOps#isUsingExternalRaw}.
   */
  boolean isUsingExternalServer() {
    return serverOps.isUsingExternalRaw();
  }

  /**
   * Test-only setter. Tempdoc 518 P2 routes through {@link LlamaServerOps#setUsingExternal} —
   * the prior ILM-side volatile field no longer exists. The wrapper keeps the test-only
   * naming convention on ILM's package-private surface.
   */
  void setUsingExternalServerForTest(boolean value) {
    serverOps.setUsingExternal(value);
  }

  // ==================== Listeners ====================

  /** Add a listener to be notified of mode changes. */
  public void addModeChangeListener(ModeChangeListener listener) {
    runner.addListener(listener);
  }

  /** Remove a previously added listener. */
  public void removeModeChangeListener(ModeChangeListener listener) {
    runner.removeListener(listener);
  }

  /**
   * Tempdoc 837 §D.2 — add a listener that also receives the {@link
   * io.justsearch.app.inference.telemetry.TransitionReason} behind the change. Consumers that must
   * tell crash recovery apart from a deactivation subscribe here; everything else keeps using
   * {@link #addModeChangeListener}.
   */
  public void addModeTransitionListener(ModeTransitionListener listener) {
    runner.addReasonListener(listener);
  }

  /** Remove a previously added reason-bearing listener. */
  public void removeModeTransitionListener(ModeTransitionListener listener) {
    runner.removeReasonListener(listener);
  }

  // Tempdoc 518 P4: ModeChangeListener moved to io.justsearch.app.api.ModeChangeListener.

  // ==================== Model Swap Detection ====================

  private static final String MODEL_STATE_FILE = "inference-model-id.txt";

  /**
   * Called from the {@link PropsObserver} when llama-server's {@code /props} reports a model
   * ID. Performs cross-restart model-swap detection against persisted state and merges the
   * observed model id into the view atom.
   */
  private void onModelIdUpdatedInternal(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      return;
    }
    runner.mergeProps(modelId, null);
    String persisted = loadPersistedModelId();
    if (persisted != null && !persisted.equals(modelId)) {
      LOG.warn(
          "Model swap detected: persisted='{}', current='{}'. "
              + "If intentional, this is informational. If unexpected, check your model path.",
          persisted,
          modelId);
    }
    persistModelId(modelId);
  }

  private Path resolveModelStatePath() {
    ConfigStore cs = ConfigStore.globalOrNull();
    Path dataDir = cs != null ? cs.get().paths().dataDir() : null;
    if (dataDir != null) {
      return dataDir.resolve(MODEL_STATE_FILE);
    }
    return Path.of(System.getProperty("user.dir")).resolve(MODEL_STATE_FILE);
  }

  private String loadPersistedModelId() {
    try {
      Path statePath = resolveModelStatePath();
      if (Files.exists(statePath)) {
        String content = Files.readString(statePath).trim();
        return content.isBlank() ? null : content;
      }
    } catch (IOException e) {
      LOG.debug("Failed to read persisted model ID: {}", e.getMessage());
    }
    return null;
  }

  private void persistModelId(String modelId) {
    try {
      Path statePath = resolveModelStatePath();
      Files.createDirectories(statePath.getParent());
      Files.writeString(statePath, modelId);
    } catch (IOException e) {
      LOG.debug("Failed to persist model ID: {}", e.getMessage());
    }
  }

  // ==================== Lifecycle ====================

  /**
   * Whether {@link #close()} stops llama-server. Design 7.3 step 6, stage B item B5.
   *
   * <p>Default {@code true}, which is exactly what close() did unconditionally before B5 — so every
   * caller that does not set this keeps today's behaviour.
   *
   * <p>The reason it is a directive set before the close rather than an argument to it: this
   * manager is closed through an {@code AutoCloseable} loop over a dozen handles
   * ({@code OrchestrationHandles.close()}), so there is no signature on the path from the ordered
   * shutdown down to here that could carry a reason without changing every handle's contract.
   */
  private volatile boolean stopServerOnClose = true;

  /**
   * Sets whether the generative backend is stopped when this manager closes.
   *
   * <p>{@code false} leaves llama-server RUNNING for the next Engine to adopt (design 7.2). That is
   * what {@code restart} and {@code hang} want: the model is loaded, the VRAM is warm, and a
   * restarted Engine that has to reload it pays ~40s of encoder load for nothing.
   * {@code quit} and {@code upgrade} want the opposite — the installer must be able to overwrite
   * the binary, and nothing may hold VRAM behind a closed product.
   */
  public void setStopServerOnClose(boolean stopServerOnClose) {
    this.stopServerOnClose = stopServerOnClose;
  }

  /** Whether {@link #close()} will stop llama-server. */
  public boolean stopsServerOnClose() {
    return stopServerOnClose;
  }

  /** Closes this manager and releases all resources. */
  @Override
  public void close() {
    synchronized (runner.lock()) {
      LOG.info("Closing InferenceLifecycleManager (stopServer={})...", stopServerOnClose);
      RuntimeException terminationFailure = null;
      try {
        serverOps.closeUnregisteredChild();
        if (stopServerOnClose) {
          serverOps.stopLlamaServer();
        } else {
          LOG.info(
              "Leaving llama-server running for adoption by the next Engine (design 7.2). It holds"
                  + " its port and its VRAM; a restarted Engine reconciles it from the child"
                  + " registry rather than reloading the model.");
        }
      } catch (RuntimeException failure) {
        terminationFailure = failure;
      }
      onlineOps.shutdown();
      serverOps.shutdown();
      httpClient.close();
      cancelQueued(httpExecutor);
      executorRegistrations.close();
      runner.runForceOffline(TransitionReason.SHUTDOWN, null);
      if (terminationFailure != null) throw terminationFailure;
      LOG.info("InferenceLifecycleManager closed");
    }
  }

  private static java.util.concurrent.ThreadFactory namedDaemonFactory(String name) {
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

  private static void closeAfterConstructionFailure(
      LlamaServerOps server,
      OnlineModeOps online,
      java.util.concurrent.ExecutorService http,
      Throwable primary) {
    try {
      if (server != null) server.shutdown();
    } catch (RuntimeException failure) {
      primary.addSuppressed(failure);
    }
    try {
      if (online != null) online.shutdown();
    } catch (RuntimeException failure) {
      primary.addSuppressed(failure);
    }
    try {
      if (http != null) cancelQueued(http);
    } catch (RuntimeException failure) {
      primary.addSuppressed(failure);
    }
  }

  /**
   * Tempdoc 374 alpha.25 U14-C: read total VRAM via the unified NVML-first snapshot. Returns
   * {@code null} if both NVML and the nvidia-smi fallback failed.
   */
  private Long readTotalVramBytes() {
    GpuCapabilities.Effective effective = gpuCapabilitiesService.snapshot().effective();
    Long totalBytes = effective.totalVramBytes();
    return (totalBytes == null || totalBytes < 0) ? null : totalBytes;
  }

  /** Formats a VRAM byte count as a human-readable "X.X GB" string. */
  private static String formatVramDescription(long bytes) {
    return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
  }
}
