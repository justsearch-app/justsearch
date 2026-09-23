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
import io.justsearch.configuration.resolved.ResolvedConfig;
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
  private final GenerativeRequestGate requestGate = new GenerativeRequestGate();

  /** A committed settings target could not be reconstructed; no old model may serve as it. */
  public void fenceForSettingsRecovery() {
    requestGate.fenceForRecovery();
  }

  /** One immutable configured snapshot; publication is serialized by the transition lock. */
  private volatile ConfiguredInference configured;

  /**
   * Owner reservation for an in-place candidate composed before the outer settings commit.
   * While set, B is physically active but remains unavailable through the component owner and
   * must not replace A in this manager's serving/configuration projections.
   */
  private volatile PreparedConfigApply preparedConfigApply;
  private volatile boolean precommitComposition;

  /** A null resolved snapshot exists only for the legacy constructor before context capture. */
  private record ConfiguredInference(
      InferenceConfig inference, ResolvedConfig resolved, LlamaServerOps.AdoptionPolicy policy) {
    private ConfiguredInference {
      Objects.requireNonNull(inference, "inference");
      Objects.requireNonNull(policy, "policy");
    }
  }

  private InferenceConfig configuredInference() {
    return configured.inference();
  }

  private LlamaServerOps.StartRequest configuredStartRequest() {
    ConfiguredInference snapshot = configured;
    if (snapshot.resolved() == null) {
      snapshot = new ConfiguredInference(snapshot.inference(), ConfigStore.global().get(), snapshot.policy());
      configured = snapshot;
    }
    return new LlamaServerOps.StartRequest(
        new LlamaServerConfigContext(snapshot.inference(), snapshot.resolved()), snapshot.policy());
  }

  private InferenceConfig servingInference() {
    if (precommitComposition) return configuredInference();
    LlamaServerOps server = serverOps;
    return server == null ? configuredInference()
        : server.activeStartResult().map(start -> start.context().inference()).orElseGet(this::configuredInference);
  }

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
  private volatile ConfiguredInference preVduConfig;

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
    this(executorRegistry, config, events, childRegistry, null);
  }

  public InferenceLifecycleManager(
      io.justsearch.core.execution.EngineExecutorRegistry executorRegistry,
      InferenceConfig config,
      InferenceTelemetryEvents events,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      ResolvedConfig resolvedConfig) {
    this.executorRegistrations = new InferenceExecutorRegistrations(executorRegistry);
    this.events = Objects.requireNonNull(events, "events");
    this.configured = new ConfiguredInference(
        config, resolvedConfig, LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL);
    this.gpuCapabilitiesService = new GpuCapabilitiesService();
    Object lock = new Object();
    ModeStateMachine modeState = new ModeStateMachine();
    this.runner = new TransitionRunner(lock, modeState, events);

    PropsObserver propsObserver =
        new PropsObserver() {
          @Override
          public void onModelIdObserved(String modelId, LlamaServerConfigContext context) {
            if (precommitComposition) return;
            onModelIdUpdatedInternal(modelId, context);
          }

          @Override
          public void onContextTokensObserved(int contextTokens) {
            if (precommitComposition) return;
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
              () -> servingInference().serverPort());
      openedOnline =
          new OnlineModeOps(
              executorRegistrations,
              createdHttpClient,
              createdObjectMapper,
              runner::currentMode,
              () -> servingInference().serverPort(),
              () -> runner.view().lastKnownModelId(),
              () -> servingInference().modelPath().getFileName().toString(),
              this.events,
              requestGate);
      openedServer =
          new LlamaServerOps(
              executorRegistrations,
              createdHttpClient,
              createdObjectMapper,
              gpuCapabilitiesService,
              runner::currentMode,
              propsObserver,
              this::recoverManagedServer,
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
  private void handleMaxCrashOffline(java.util.function.BooleanSupplier stillOwned) {
    synchronized (runner.lock()) {
      if (precommitComposition) {
        invalidatePreparedCandidate(stillOwned);
        return;
      }
      if (!stillOwned.getAsBoolean() || runner.currentMode() != Mode.ONLINE) return;
      tokenOps.clearCaches();
      InferenceFailure cleanupFailure = null;
      try {
        serverOps.stopLlamaServer();
      } catch (RuntimeException failure) {
        cleanupFailure = new InferenceFailure.TransitionFailure(
            TransitionCode.ONLINE_START_FAILED,
            "Recovery exhausted and child cleanup failed: " + safeMessage(failure), failure);
      }
      runner.runForceOffline(TransitionReason.CRASH_RECOVERY, cleanupFailure);
    }
  }

  /** Serializes a captured physical server's recovery with apply, detach and close. */
  private void recoverManagedServer(java.util.function.BooleanSupplier stillOwned) {
    synchronized (runner.lock()) {
      if (precommitComposition) {
        invalidatePreparedCandidate(stillOwned);
        return;
      }
      if (!stillOwned.getAsBoolean() || runner.currentMode() != Mode.ONLINE) return;
      serverOps.recoverActiveServer();
    }
  }

  private void invalidatePreparedCandidate(java.util.function.BooleanSupplier stillOwned) {
    PreparedConfigApply prepared = preparedConfigApply;
    if (prepared != null && prepared.enabled && stillOwned.getAsBoolean()) {
      prepared.invalidated = true;
    }
  }

  /** External-server-unhealthy callback installed on {@link LlamaServerOps}. Tempdoc 518 P1. */
  private void handleExternalFailureOffline(
      String reason, java.util.function.BooleanSupplier stillOwned) {
    synchronized (runner.lock()) {
      if (precommitComposition || !stillOwned.getAsBoolean()
          || !serverOps.isUsingExternalRaw() || runner.currentMode() != Mode.ONLINE) {
        return;
      }
      LOG.error(
          "External llama-server on port {} became unhealthy: {}. Switching to OFFLINE.",
          servingInference().serverPort(),
          reason);
      serverOps.setUsingExternal(false);
      tokenOps.clearCaches();
      runner.runForceOffline(TransitionReason.CRASH_RECOVERY, null);
    }
  }

  // ==================== Mode Queries ====================

  public InferenceConfig currentConfig() {
    return configuredInference();
  }

  public boolean hasVisionCapability() {
    InferenceConfig cfg = servingInference();
    boolean configHasVision = cfg != null && cfg.mmprojPath() != null;
    if (precommitComposition) return configHasVision;
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
    return LlamaServerBuildCheck.readExpectedNextTo(servingInference().serverExecutable());
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
            configuredInference().validate();
          } catch (Exception e) {
            return TransitionOutcome.failure(
                new InferenceFailure.ConfigFailure(
                    ConfigCode.INVALID_CONFIG,
                    "Invalid inference configuration: " + safeMessage(e)),
                priorView);
          }

          var startRequest = configuredStartRequest();
          // VRAM precondition uses the same captured policy as the launch.
          if (startRequest.effectiveGpuLayers() > 0) {
            gpuCapabilitiesService.invalidateNvidiaSmiCache();
            Long totalVramBytes = readTotalVramBytes();
            if (totalVramBytes == null) {
              LOG.warn(
                  "VRAM detection unavailable (NVML and nvidia-smi both returned no value). "
                      + "Proceeding with GPU Online Mode because gpuLayers={} was explicitly requested.",
                  startRequest.effectiveGpuLayers());
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
            events.onStartupAttempt(configuredInference(), StartupReason.COLD_START, TargetPhase.ONLINE);
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
            serverOps.resetCrashCounters();
            var started = serverOps.startLlamaServer(startRequest);
            serverOps.waitForServerHealth(started);
            verifyAppliedServer(startRequest, started);
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
                  configuredInference(),
                  Duration.ofMillis(elapsed),
                  RuntimeIdentity.nonProcess(runner.generation() + 1),
                  TargetPhase.ONLINE);
            } catch (RuntimeException ex) {
              LOG.warn("Telemetry events.onStartupComplete threw: {}", ex.getMessage());
            }
            return TransitionOutcome.success(Mode.ONLINE, nextView);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedOnlineStartup(
                new InferenceFailure.TransitionFailure(
                    TransitionCode.INTERRUPTED, "Transition interrupted", e),
                priorView, e);
          } catch (ModeTransitionException mte) {
            return failedOnlineStartup(TransitionRunner.mapExceptionToFailure(mte), priorView, mte);
          } catch (Exception e) {
            return failedOnlineStartup(
                new InferenceFailure.TransitionFailure(
                    TransitionCode.ONLINE_START_FAILED,
                    "Failed to switch to Online Mode: " + safeMessage(e),
                    e),
                priorView, e);
          }
        });
  }

  private TransitionOutcome failedOnlineStartup(
      InferenceFailure failure, InferenceRuntimeView priorView, Throwable startupFailure) {
    try {
      serverOps.stopLlamaServer();
      return TransitionOutcome.failure(failure, priorView.withExternal(false));
    } catch (RuntimeException cleanupFailure) {
      var combined = new IllegalStateException(
          "Startup failed: " + failure.detail() + "; cleanup failed: " + safeMessage(cleanupFailure),
          cleanupFailure);
      combined.addSuppressed(startupFailure);
      return TransitionOutcome.failureOffline(
          new InferenceFailure.TransitionFailure(
              TransitionCode.ONLINE_START_FAILED, combined.getMessage(), combined),
          priorView.withExternal(false));
    }
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
            events.onStartupAttempt(configuredInference(), StartupReason.COLD_START, TargetPhase.INDEXING);
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
                  configuredInference(),
                  Duration.ofMillis(System.currentTimeMillis() - startupStart),
                  RuntimeIdentity.nonProcess(runner.generation() + 1),
                  TargetPhase.INDEXING);
            } catch (RuntimeException ex) {
              LOG.warn("Telemetry events.onStartupComplete threw: {}", ex.getMessage());
            }
            return TransitionOutcome.success(Mode.INDEXING, nextView);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TransitionOutcome.failureOffline(
                new InferenceFailure.TransitionFailure(
                    TransitionCode.INTERRUPTED, "Transition interrupted", e),
                buildIndexingFailureRollback(priorView));
          } catch (Exception e) {
            return TransitionOutcome.failureOffline(
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
      if (configuredInference().vduMode()) {
        LOG.debug("Already in VDU mode");
        return;
      }
      LOG.info("Entering VDU mode (restarting server with vision-safe flags)...");
      ConfiguredInference previous = configured;
      ConfigApplyResult result = applyConfiguration(previous.inference().withVduMode(true),
          previous.resolved(), RestartPolicy.RESTART_ALWAYS, TransitionReason.VDU_ENTER, previous.policy());
      if (result.failure() != null) throw result.failure();
      preVduConfig = previous;
    }
  }

  public void exitVduMode() throws ModeTransitionException {
    synchronized (runner.lock()) {
      ConfiguredInference saved = preVduConfig;
      if (saved == null || !configuredInference().vduMode()) {
        LOG.debug("Not in VDU mode; exitVduMode is a no-op");
        return;
      }
      LOG.info("Exiting VDU mode (restoring normal server configuration)...");
      ConfigApplyResult result = applyConfiguration(saved.inference(), saved.resolved(),
          RestartPolicy.RESTART_ALWAYS, TransitionReason.VDU_EXIT, saved.policy());
      if (result.failure() != null) throw result.failure();
      preVduConfig = null;
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

  public enum ConfigApplyDisposition {
    APPLIED,
    CONFIGURED,
    UNCHANGED,
    ROLLED_BACK_TO_A,
    LEFT_OFFLINE
  }

  /** Configuration is the retained desired snapshot; a failed result never certifies it as serving. */
  public record ConfigApplyResult(
      ConfigApplyDisposition disposition,
      InferenceConfig configuration,
      String declaredConfigHash,
      ModeTransitionException failure) {
    public ConfigApplyResult {
      Objects.requireNonNull(disposition, "disposition");
      Objects.requireNonNull(configuration, "configuration");
    }
  }

  private record ApplyExecution(
      ConfigApplyDisposition disposition, String declaredConfigHash, TransitionOutcome transition) {}

  /**
   * Opaque, manager-owned result of composing a strict generative candidate before settings
   * persistence. The outer coordinator validates this value before replacing the settings file,
   * then invokes {@link #installAfterSettingsCommit()} as the publication-lock-held assignment.
   * If commitment does not occur, {@link #abort()} stops B and restores the captured serving A.
   */
  public static final class PreparedConfigApply {
    private enum State {
      PREPARED,
      INSTALLED,
      ABORTED
    }

    private final InferenceLifecycleManager owner;
    private final ConfiguredInference incumbentConfiguration;
    private final LlamaServerOps.StartResult incumbent;
    private final ConfiguredInference candidateConfiguration;
    private final LlamaServerOps.StartResult candidate;
    private final Mode priorMode;
    private final boolean enabled;
    private final GenerativeRequestGate.Hold admissionHold;
    private TransitionRunner.PreparedPublication logicalPublication;
    private volatile State state = State.PREPARED;
    private volatile boolean invalidated;

    private PreparedConfigApply(
        InferenceLifecycleManager owner,
        ConfiguredInference incumbentConfiguration,
        LlamaServerOps.StartResult incumbent,
        ConfiguredInference candidateConfiguration,
        LlamaServerOps.StartResult candidate,
        Mode priorMode,
        boolean enabled,
        GenerativeRequestGate.Hold admissionHold) {
      this.owner = owner;
      this.incumbentConfiguration = incumbentConfiguration;
      this.incumbent = incumbent;
      this.candidateConfiguration = candidateConfiguration;
      this.candidate = candidate;
      this.priorMode = priorMode;
      this.enabled = enabled;
      this.admissionHold = admissionHold;
    }

    /**
     * Revalidates the prepared owner and physical candidate before the settings replacement.
     * This method performs no mutation and must run before the durable commit point.
     */
    public void validateForCommit() {
      if (state != State.PREPARED
          || owner.preparedConfigApply != this
          || owner.serverOps.activeStartResult().orElse(null)
              != (enabled ? candidate : incumbent)
          || invalidated
          || (enabled && !owner.serverOps.activeManagedCandidateAlive(candidate))) {
        throw new IllegalStateException("Prepared inference candidate is no longer current");
      }
      if (logicalPublication == null) {
        throw new IllegalStateException("Prepared inference mode publication is not built");
      }
      logicalPublication.validate();
    }

    /**
     * Runs the outer publication critical section while holding the manager lifecycle monitor.
     * The caller must invoke this before it acquires the shared publication write lock; the
     * callback then acquires that lock, validates all owners, commits and installs assignments.
     */
    public void withLifecycleLock(Runnable publication) {
      Objects.requireNonNull(publication, "publication");
      synchronized (owner.runner.lock()) {
        if (logicalPublication == null) {
          logicalPublication = owner.prepareLogicalPublication(enabled, priorMode);
        }
        validateForCommit();
        publication.run();
      }
    }

    /**
     * Installs the already validated B references after the settings file commit. This deliberately
     * contains assignments only: no manager lock, callback, allocation, I/O or health work belongs
     * after the outer commit point.
     */
    public void installAfterSettingsCommit() {
      owner.configured = candidateConfiguration;
      logicalPublication.install();
      state = State.INSTALLED;
    }

    /** Delivers the prebuilt mode notifications/history after publication locks are released. */
    public void notifyAfterSettingsCommit() {
      logicalPublication.notifyAfterInstall();
    }

    /** Finishes committed mode publication and old-resource retirement outside publication locks. */
    public void retireAfterSettingsCommit() throws ModeTransitionException {
      owner.retirePreparedConfig(this);
    }

    /** Stops the private candidate and restores the captured incumbent when commit is abandoned. */
    public void abort() throws ModeTransitionException {
      owner.abortPreparedConfig(this);
    }

    /** Managed configuration witness established before return, or {@code null} for disable. */
    public String declaredConfigHash() {
      return candidate == null ? null : candidate.declaredConfigHash();
    }
  }

  /**
   * Composes a strict managed candidate without publishing it as the configured or observed
   * serving runtime. The caller must either install or abort the returned owner value.
   */
  public PreparedConfigApply prepareResolvedConfig(
      InferenceConfig candidate, ResolvedConfig candidateResolved) throws ModeTransitionException {
    return prepareResolvedConfig(candidate, candidateResolved, true);
  }

  /**
   * Prepares the desired managed generative state. Enabled candidates are started and health
   * checked privately; disabled candidates retain A until the outer commit and retire it after.
   */
  public PreparedConfigApply prepareResolvedConfig(
      InferenceConfig candidate, ResolvedConfig candidateResolved, boolean enabled)
      throws ModeTransitionException {
    Objects.requireNonNull(candidateResolved, "candidateResolved");
    if (candidate == null) {
      throw modeTransition(ModeTransitionException.Reason.CONFIG_REQUIRED, "Config is required");
    }
    final GenerativeRequestGate.Hold admissionHold;
    try {
      admissionHold = requestGate.closeAndDrain(Duration.ofSeconds(30));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw modeTransition(ModeTransitionException.Reason.INTERRUPTED,
          "Interrupted while draining generative requests", interrupted);
    } catch (IllegalStateException unavailable) {
      throw modeTransition(ModeTransitionException.Reason.ALREADY_TRANSITIONING,
          unavailable.getMessage(), unavailable);
    }
    boolean transferred = false;
    try {
    synchronized (runner.lock()) {
      if (precommitComposition || preparedConfigApply != null) {
        throw modeTransition(
            ModeTransitionException.Reason.ALREADY_TRANSITIONING,
            "An inference candidate is already prepared");
      }
      if (runner.currentMode() == Mode.TRANSITIONING) {
        throw modeTransition(
            ModeTransitionException.Reason.ALREADY_TRANSITIONING,
            "Inference runtime is transitioning; try again shortly");
      }
      if (enabled && runner.currentMode() == Mode.INDEXING) {
        throw modeTransition(
            ModeTransitionException.Reason.ALREADY_TRANSITIONING,
            "Managed inference cannot be enabled while indexing is active");
      }
      if (serverOps.isExternalServerActive()) {
        throw modeTransition(
            ModeTransitionException.Reason.EXTERNAL_SERVER_CONFLICT,
            "Cannot prepare managed inference while using an external llama-server instance");
      }

      var context = new LlamaServerConfigContext(candidate, candidateResolved);
      var request = new LlamaServerOps.StartRequest(
          context, LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
      if (enabled) {
        validatePrecommitCandidate(request);
      } else {
        try {
          candidate.validate();
        } catch (Exception invalid) {
          throw modeTransition(
              ModeTransitionException.Reason.INVALID_CONFIG,
              "Invalid inference configuration: " + safeMessage(invalid),
              invalid);
        }
      }

      ConfiguredInference incumbentConfiguration = configured;
      Mode priorMode = runner.currentMode();
      LlamaServerOps.StartResult incumbent = serverOps.activeStartResult().orElse(null);
      if (priorMode == Mode.ONLINE && incumbent == null) {
        throw modeTransition(
            ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
            "Online inference has no captured server ownership");
      }

      precommitComposition = true;
      if (!enabled) {
        ConfiguredInference candidateConfiguration = new ConfiguredInference(
            candidate, candidateResolved,
            LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
        PreparedConfigApply prepared = new PreparedConfigApply(
            this,
            incumbentConfiguration,
            incumbent,
            candidateConfiguration,
            null,
            priorMode,
            false,
            admissionHold);
        preparedConfigApply = prepared;
        transferred = true;
        return prepared;
      }
      try {
        tokenOps.clearCaches();
        serverOps.stopLlamaServer();
      } catch (RuntimeException stopFailure) {
        precommitComposition = false;
        throw modeTransition(
            ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
            "Incumbent server stop failed: " + safeMessage(stopFailure),
            stopFailure);
      }
      try {
        serverOps.resetCrashCounters();
        LlamaServerOps.StartResult started = serverOps.startLlamaServer(request);
        serverOps.waitForServerHealth(started);
        verifyAppliedServer(request, started);
        ConfiguredInference candidateConfiguration = new ConfiguredInference(
            candidate,
            candidateResolved,
            LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
        PreparedConfigApply prepared = new PreparedConfigApply(
            this,
            incumbentConfiguration,
            incumbent,
            candidateConfiguration,
            started,
            priorMode,
            true,
            admissionHold);
        preparedConfigApply = prepared;
        transferred = true;
        return prepared;
      } catch (Exception candidateFailure) {
        throw rollbackPrecommitFailure(
            incumbent, incumbentConfiguration, priorMode, candidateFailure);
      }
    }
    } finally {
      if (!transferred) admissionHold.close();
    }
  }

  private void validatePrecommitCandidate(LlamaServerOps.StartRequest request)
      throws ModeTransitionException {
    try {
      request.context().inference().validate();
      if (request.effectiveGpuLayers() <= 0) return;
      gpuCapabilitiesService.invalidateNvidiaSmiCache();
      Long totalVramBytes = readTotalVramBytes();
      if (totalVramBytes == null) {
        LOG.warn(
            "VRAM detection unavailable; proceeding with explicitly requested GPU layers={}",
            request.effectiveGpuLayers());
      } else if (totalVramBytes < HardwareProfile.MINIMUM_VRAM_FOR_GGUF) {
        throw modeTransition(
            ModeTransitionException.Reason.INSUFFICIENT_VRAM,
            "Insufficient VRAM for GPU Online Mode: " + formatVramDescription(totalVramBytes)
                + " (set GPU layers to 0 for CPU mode)");
      }
    } catch (ModeTransitionException failure) {
      throw failure;
    } catch (Exception invalid) {
      throw modeTransition(
          ModeTransitionException.Reason.INVALID_CONFIG,
          "Invalid inference configuration: " + safeMessage(invalid),
          invalid);
    }
  }

  private TransitionRunner.PreparedPublication prepareLogicalPublication(
      boolean enabled, Mode priorMode) {
    Mode target = enabled ? Mode.ONLINE : priorMode == Mode.ONLINE ? Mode.OFFLINE : priorMode;
    InferenceRuntimeView next = runner.view().withPhase(target);
    if (!enabled) next = next.withExternal(false);
    return runner.preparePublication(target, next, TransitionReason.CONFIG_APPLY);
  }

  private ModeTransitionException rollbackPrecommitFailure(
      LlamaServerOps.StartResult incumbent,
      ConfiguredInference incumbentConfiguration,
      Mode priorMode,
      Exception candidateFailure) {
    ModeTransitionException candidate = asModeTransition(
        candidateFailure,
        ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
        "Failed to prepare inference config: ");
    try {
      serverOps.stopLlamaServer();
      if (priorMode == Mode.ONLINE) {
        var restore = new LlamaServerOps.StartRequest(
            incumbent.context(), LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
        serverOps.resetCrashCounters();
        var restored = serverOps.startLlamaServer(restore);
        serverOps.waitForServerHealth(restored);
        verifyAppliedServer(restore, restored);
      }
      configured = incumbentConfiguration;
      preparedConfigApply = null;
      precommitComposition = false;
      return candidate;
    } catch (Exception rollbackFailure) {
      var combined = new IllegalStateException(
          "Rollback failed: " + safeMessage(rollbackFailure), rollbackFailure);
      combined.addSuppressed(candidate);
      preparedConfigApply = null;
      precommitComposition = false;
      runner.runForceOffline(
          TransitionReason.CONFIG_APPLY,
          new InferenceFailure.TransitionFailure(
              TransitionCode.CONFIG_APPLY_FAILED, combined.getMessage(), combined));
      return modeTransition(
          ModeTransitionException.Reason.CONFIG_APPLY_FAILED, combined.getMessage(), combined);
    }
  }

  private void abortPreparedConfig(PreparedConfigApply prepared) throws ModeTransitionException {
    synchronized (runner.lock()) {
      if (prepared.state == PreparedConfigApply.State.INSTALLED
          || prepared.state == PreparedConfigApply.State.ABORTED) {
        return;
      }
      if (preparedConfigApply != prepared) {
        throw new IllegalStateException("Prepared inference candidate is no longer current");
      }
      if (!prepared.enabled) {
        preparedConfigApply = null;
        precommitComposition = false;
        prepared.state = PreparedConfigApply.State.ABORTED;
        prepared.admissionHold.close();
        return;
      }
      var aborted = modeTransition(
          ModeTransitionException.Reason.INTERRUPTED, "Prepared inference candidate was aborted");
      ModeTransitionException rollback = rollbackPrecommitFailure(
          prepared.incumbent,
          prepared.incumbentConfiguration,
          prepared.priorMode,
          aborted);
      if (rollback != aborted) throw rollback;
      prepared.state = PreparedConfigApply.State.ABORTED;
      prepared.admissionHold.close();
    }
  }

  private void retirePreparedConfig(PreparedConfigApply prepared) throws ModeTransitionException {
    synchronized (runner.lock()) {
      if (prepared.state != PreparedConfigApply.State.INSTALLED
          || preparedConfigApply != prepared) {
        throw new IllegalStateException("Prepared inference candidate is not installed");
      }
      boolean retired = false;
      try {
        prepared.logicalPublication.notifyAfterInstall();
        if (prepared.enabled
            && serverOps.activeStartResult().orElse(null) != prepared.candidate) {
          var lost = modeTransition(
              ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
              "Committed inference candidate lost physical ownership before retirement");
          runner.runForceOffline(
              TransitionReason.CONFIG_APPLY,
              new InferenceFailure.TransitionFailure(
                  TransitionCode.CONFIG_APPLY_FAILED, lost.getMessage(), lost));
          throw lost;
        }
        if (!prepared.enabled && prepared.priorMode == Mode.ONLINE) {
          tokenOps.clearCaches();
          try {
            serverOps.stopLlamaServer();
          } catch (RuntimeException retirementFailure) {
            var failure = new InferenceFailure.TransitionFailure(
                TransitionCode.CONFIG_APPLY_FAILED,
                "Committed inference disable could not retire the incumbent: "
                    + safeMessage(retirementFailure),
                retirementFailure);
            runner.recordFailureOutsideTransition(failure);
            throw modeTransition(
                ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
                failure.detail(),
                retirementFailure);
          }
        }
        retired = true;
      } finally {
        preparedConfigApply = null;
        precommitComposition = false;
        if (retired) prepared.admissionHold.close();
      }
    }
  }

  public void applyConfig(InferenceConfig newConfig, RestartPolicy policy)
      throws ModeTransitionException {
    applyConfig(newConfig, policy, TransitionReason.CONFIG_APPLY);
  }

  public void applyConfig(
      InferenceConfig newConfig, RestartPolicy policy, TransitionReason transitionReason)
      throws ModeTransitionException {
    ConfigApplyResult result = applyConfiguration(
        newConfig, null, policy, transitionReason,
        LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL);
    if (result.failure() != null) throw result.failure();
  }

  /** Prepares the exact resolved candidate with managed configuration proof. */
  public ConfigApplyResult applyResolvedConfig(
      InferenceConfig candidate, ResolvedConfig candidateResolved, RestartPolicy policy)
      throws ModeTransitionException {
    Objects.requireNonNull(candidateResolved, "candidateResolved");
    return applyConfiguration(candidate, candidateResolved, policy, TransitionReason.CONFIG_APPLY,
        LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
  }

  private ConfigApplyResult applyConfiguration(
      InferenceConfig newConfig, ResolvedConfig suppliedResolved, RestartPolicy policy,
      TransitionReason transitionReason, LlamaServerOps.AdoptionPolicy adoptionPolicy)
      throws ModeTransitionException {
    if (newConfig == null) {
      InferenceFailure failure =
          new InferenceFailure.ConfigFailure(ConfigCode.CONFIG_REQUIRED, "Config is required");
      recordApplyRefusal(failure);
      throw modeTransition(ModeTransitionException.Reason.CONFIG_REQUIRED, "Config is required");
    }
    RestartPolicy effective = policy == null ? RestartPolicy.RESTART_IF_ONLINE : policy;
    synchronized (runner.lock()) {
      if (precommitComposition) {
        String message = "An inference candidate is awaiting settings commit";
        recordApplyRefusal(
            new InferenceFailure.ConfigFailure(ConfigCode.ALREADY_TRANSITIONING, message));
        throw modeTransition(ModeTransitionException.Reason.ALREADY_TRANSITIONING, message);
      }
      if (runner.currentMode() == Mode.TRANSITIONING) {
        String message = "Inference runtime is transitioning; try again shortly";
        recordApplyRefusal(new InferenceFailure.ConfigFailure(ConfigCode.ALREADY_TRANSITIONING, message));
        throw modeTransition(ModeTransitionException.Reason.ALREADY_TRANSITIONING, message);
      }
      ConfiguredInference incumbentConfiguration = configured;
      InferenceConfig oldConfig = incumbentConfiguration.inference();
      boolean shouldRestart = switch (effective) {
        case RESTART_ALWAYS -> true;
        case RESTART_IF_ONLINE -> runner.currentMode() == Mode.ONLINE;
        case APPLY_ONLY -> false;
      };
      long applyStartNanos = System.nanoTime();
      try {
        events.onConfigApplyAttempt(oldConfig, newConfig, shouldRestart);
      } catch (RuntimeException failure) {
        LOG.warn("Telemetry events.onConfigApplyAttempt threw: {}", failure.getMessage());
      }
      if (shouldRestart && serverOps.isExternalServerActive()) {
        String message = "Cannot restart/apply config while using an external llama-server instance on port "
            + oldConfig.serverPort() + ". Stop the external process first or detach to a managed server.";
        recordApplyRefusal(new InferenceFailure.ConfigFailure(ConfigCode.EXTERNAL_SERVER_CONFLICT, message));
        throw modeTransition(ModeTransitionException.Reason.EXTERNAL_SERVER_CONFLICT, message);
      }
      ResolvedConfig resolved = suppliedResolved != null ? suppliedResolved : ConfigStore.global().get();
      var candidate = new LlamaServerConfigContext(newConfig, resolved);
      if (!shouldRestart) {
        try {
          newConfig.validate();
        } catch (Exception invalid) {
          String message = "Invalid inference configuration: " + safeMessage(invalid);
          recordApplyRefusal(new InferenceFailure.ConfigFailure(ConfigCode.INVALID_CONFIG, message));
          throw modeTransition(ModeTransitionException.Reason.INVALID_CONFIG, message);
        }
        configured = new ConfiguredInference(newConfig, resolved, adoptionPolicy);
        emitApplyComplete(applyStartNanos);
        return new ConfigApplyResult(ConfigApplyDisposition.CONFIGURED, newConfig, null, null);
      }
      Mode priorMode = runner.currentMode();
      // This is an attempt-local return slot, never a published configuration authority.
      ApplyExecution[] execution = new ApplyExecution[1];
      try {
        runner.run(transitionReason, events::onConfigApplyFailure, priorView -> {
          execution[0] = composeCandidate(candidate, adoptionPolicy, incumbentConfiguration,
              priorMode, priorView, applyStartNanos);
          return execution[0].transition();
        });
      } catch (ModeTransitionException failure) {
        if (execution[0] == null) throw failure;
        return new ConfigApplyResult(execution[0].disposition(), configuredInference(),
            execution[0].declaredConfigHash(), failure);
      }
      return new ConfigApplyResult(execution[0].disposition(), configuredInference(),
          execution[0].declaredConfigHash(), null);
    }
  }

  private void recordApplyRefusal(InferenceFailure failure) {
    runner.recordFailureOutsideTransition(failure);
    try {
      events.onConfigApplyFailure(failure);
    } catch (RuntimeException telemetryFailure) {
      LOG.warn("Telemetry events.onConfigApplyFailure threw: {}", telemetryFailure.getMessage());
    }
  }

  private void emitApplyComplete(long started) {
    try {
      events.onConfigApplyComplete(Duration.ofNanos(System.nanoTime() - started));
    } catch (RuntimeException failure) {
      LOG.warn("Telemetry events.onConfigApplyComplete threw: {}", failure.getMessage());
    }
  }

  private ApplyExecution composeCandidate(
      LlamaServerConfigContext candidate, LlamaServerOps.AdoptionPolicy policy,
      ConfiguredInference incumbentConfiguration, Mode priorMode,
      InferenceRuntimeView priorView, long applyStarted) {
    var request = new LlamaServerOps.StartRequest(candidate, policy);
    LlamaServerOps.StartResult incumbent;
    try {
      candidate.inference().validate();
    } catch (Exception invalid) {
      return failedApply(ConfigApplyDisposition.UNCHANGED,
          new InferenceFailure.ConfigFailure(ConfigCode.INVALID_CONFIG,
              "Invalid inference configuration: " + safeMessage(invalid)), priorView);
    }
    try {
      if (request.effectiveGpuLayers() > 0) {
        gpuCapabilitiesService.invalidateNvidiaSmiCache();
        Long totalVramBytes = readTotalVramBytes();
        if (totalVramBytes == null) {
          LOG.warn("VRAM detection unavailable; proceeding with explicitly requested GPU layers={}",
              request.effectiveGpuLayers());
        } else if (totalVramBytes < HardwareProfile.MINIMUM_VRAM_FOR_GGUF) {
          return failedApply(ConfigApplyDisposition.UNCHANGED,
              new InferenceFailure.StartupFailure(StartupCode.INSUFFICIENT_VRAM,
                  "Insufficient VRAM for GPU Online Mode: " + formatVramDescription(totalVramBytes)
                      + " (set GPU layers to 0 for CPU mode)", null), priorView);
        }
      }
      incumbent = serverOps.activeStartResult().orElse(null);
      if (priorMode == Mode.ONLINE && incumbent == null) {
        return failedApply(ConfigApplyDisposition.LEFT_OFFLINE,
            new InferenceFailure.TransitionFailure(TransitionCode.CONFIG_APPLY_FAILED,
                "Online inference has no captured server ownership", null), priorView);
      }
    } catch (RuntimeException preflightFailure) {
      return failedApply(ConfigApplyDisposition.UNCHANGED,
          new InferenceFailure.TransitionFailure(TransitionCode.CONFIG_APPLY_FAILED,
              "Inference preflight failed: " + safeMessage(preflightFailure), preflightFailure), priorView);
    }

    tokenOps.clearCaches();
    try {
      serverOps.stopLlamaServer();
    } catch (RuntimeException stopFailure) {
      return failedApply(ConfigApplyDisposition.LEFT_OFFLINE,
          new InferenceFailure.TransitionFailure(TransitionCode.CONFIG_APPLY_FAILED,
              "Incumbent server stop failed: " + safeMessage(stopFailure), stopFailure), priorView);
    }
    try {
      serverOps.resetCrashCounters();
      runner.clearProps();
      long startupStarted = System.currentTimeMillis();
      LlamaServerOps.StartResult started = serverOps.startLlamaServer(request);
      serverOps.waitForServerHealth(started);
      verifyAppliedServer(request, started);
      configured = new ConfiguredInference(candidate.inference(), candidate.resolved(), policy);
      emitApplyComplete(applyStarted);
      InferenceRuntimeView next = runner.view().withPhase(Mode.ONLINE)
          .withExternal(serverOps.isUsingExternalRaw())
          .withStartupDuration(System.currentTimeMillis() - startupStarted);
      return new ApplyExecution(ConfigApplyDisposition.APPLIED, started.declaredConfigHash(),
          TransitionOutcome.success(Mode.ONLINE, next));
    } catch (Exception candidateFailure) {
      return restoreIncumbent(incumbent, incumbentConfiguration, priorMode, priorView,
          policy, candidateFailure);
    }
  }

  private void verifyAppliedServer(
      LlamaServerOps.StartRequest request, LlamaServerOps.StartResult started) {
    if (started == null || !request.context().equals(started.context())
        || request.adoptionPolicy() != started.adoptionPolicy()
        || serverOps.activeStartResult().orElse(null) != started) {
      throw new IllegalStateException("Server ownership changed during configuration apply");
    }
    if (started.disposition() == LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL) {
      if (request.adoptionPolicy() != LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL) {
        throw new IllegalStateException("Managed configuration witness is required");
      }
      return;
    }
    String expected = ManagedLlamaConfigIdentity.declaredHash(request.context().inference(),
        request.context().resolved(), request.effectiveGpuLayers());
    if (!expected.equals(started.declaredConfigHash())) {
      throw new IllegalStateException("Managed server configuration witness does not match candidate");
    }
  }

  private ApplyExecution restoreIncumbent(
      LlamaServerOps.StartResult incumbent, ConfiguredInference incumbentConfiguration,
      Mode priorMode, InferenceRuntimeView priorView, LlamaServerOps.AdoptionPolicy policy,
      Exception candidateFailure) {
    ModeTransitionException candidate = asModeTransition(candidateFailure,
        ModeTransitionException.Reason.CONFIG_APPLY_FAILED, "Failed to apply inference config: ");
    configured = incumbentConfiguration;
    try {
      serverOps.stopLlamaServer();
      if (priorMode != Mode.ONLINE) {
        return failedApply(ConfigApplyDisposition.LEFT_OFFLINE,
            TransitionRunner.mapExceptionToFailure(candidate), priorView);
      }
      var restore = new LlamaServerOps.StartRequest(incumbent.context(), policy);
      serverOps.resetCrashCounters();
      runner.clearProps();
      var restored = serverOps.startLlamaServer(restore);
      serverOps.waitForServerHealth(restored);
      verifyAppliedServer(restore, restored);
      configured = new ConfiguredInference(
          incumbent.context().inference(), incumbent.context().resolved(), policy);
      return new ApplyExecution(ConfigApplyDisposition.ROLLED_BACK_TO_A,
          restored.declaredConfigHash(), TransitionOutcome.failure(
              TransitionRunner.mapExceptionToFailure(candidate),
              runner.view().withPhase(Mode.ONLINE).withExternal(serverOps.isUsingExternalRaw())));
    } catch (Exception rollbackFailure) {
      var combined = new IllegalStateException("Rollback failed: " + safeMessage(rollbackFailure),
          rollbackFailure);
      combined.addSuppressed(candidate);
      return failedApply(ConfigApplyDisposition.LEFT_OFFLINE,
          new InferenceFailure.TransitionFailure(TransitionCode.CONFIG_APPLY_FAILED,
              combined.getMessage(), combined), priorView);
    }
  }

  private static ApplyExecution failedApply(
      ConfigApplyDisposition disposition, InferenceFailure failure, InferenceRuntimeView view) {
    return new ApplyExecution(disposition, null,
        disposition == ConfigApplyDisposition.LEFT_OFFLINE
            ? TransitionOutcome.failureOffline(failure, view)
            : TransitionOutcome.failure(failure, view));
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
        int port = configuredInference().serverPort();
        return new DetachExternalServerResult(false, port, port);
      }

      final LlamaServerOps.StartResult incumbent = serverOps.activeStartResult().orElseThrow(
          () -> new IllegalStateException("External server has no captured ownership"));
      final ConfiguredInference previousConfiguration = configured;
      final InferenceConfig oldConfig = incumbent.context().inference();
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

              serverOps.stopLlamaServer();
              serverOps.resetCrashCounters();
              tokenOps.clearCaches();

              // Tempdoc 518 fix A: wipe stale /props from external server before starting
              // the new managed server. The managed server's /props observation (if it
              // fires) repopulates via runner.mergeProps.
              runner.clearProps();
              var request = new LlamaServerOps.StartRequest(
                  new LlamaServerConfigContext(next, incumbent.context().resolved()),
                  LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
              var started = serverOps.startLlamaServer(request);
              serverOps.waitForServerHealth(started);
              verifyAppliedServer(request, started);
              configured = new ConfiguredInference(next, incumbent.context().resolved(),
                  LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);

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
              return restoreIncumbent(incumbent, previousConfiguration, Mode.ONLINE,
                  priorView, incumbent.adoptionPolicy(), e).transition();
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

  /** Returns the active launch context size (-c), or the retained configuration while stopped. */
  public int configuredContextTokens() {
    return servingInference().contextSize();
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

  private <T> T withGenerativeLease(java.util.function.Supplier<T> operation) {
    var lease = requestGate.acquire();
    try {
      return operation.get();
    } finally {
      lease.close();
    }
  }

  public Optional<Integer> countTokens(String text) {
    return withGenerativeLease(() -> tokenOps.countTokens(text));
  }

  public Optional<Integer> countTokens(
      String text, io.justsearch.app.api.EngineWorkHandle work) {
    return onlineOps.callOwned(work, () -> withGenerativeLease(() -> tokenOps.countTokens(text)));
  }

  public Optional<String> applyTemplate(List<Map<String, Object>> messages) {
    return withGenerativeLease(() -> tokenOps.applyTemplate(messages));
  }

  public Optional<String> applyTemplate(
      List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
    return withGenerativeLease(() -> tokenOps.applyTemplate(messages, tools));
  }

  public Optional<Integer> countPromptTokens(List<Map<String, Object>> messages) {
    return withGenerativeLease(() -> tokenOps.countPromptTokens(messages));
  }

  public Optional<Integer> countPromptTokens(
      List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
    return withGenerativeLease(() -> tokenOps.countPromptTokens(messages, tools));
  }

  public Optional<Integer> countPromptTokens(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      io.justsearch.app.api.EngineWorkHandle work) {
    return onlineOps.callOwned(work,
        () -> withGenerativeLease(() -> tokenOps.countPromptTokens(messages, tools)));
  }

  public boolean supportsTokenize() {
    return withGenerativeLease(tokenOps::supportsTokenize);
  }

  public boolean supportsApplyTemplate() {
    return withGenerativeLease(tokenOps::supportsApplyTemplate);
  }

  // ==================== Process Management — delegated to LlamaServerOps ====================

  // Package-private delegation stubs for testing.

  @SuppressWarnings("unused") // Called from InferenceLifecycleManagerExternalServerTest
  void startLlamaServer() throws IOException, ModeTransitionException {
    synchronized (runner.lock()) {
      serverOps.startLlamaServer(configuredStartRequest());
    }
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
  private void onModelIdUpdatedInternal(String modelId, LlamaServerConfigContext context) {
    if (modelId == null || modelId.isBlank()) {
      return;
    }
    runner.mergeProps(modelId, null);
    Path statePath = context.resolved().paths().dataDir().resolve(MODEL_STATE_FILE);
    String persisted = loadPersistedModelId(statePath);
    if (persisted != null && !persisted.equals(modelId)) {
      LOG.warn(
          "Model swap detected: persisted='{}', current='{}'. "
              + "If intentional, this is informational. If unexpected, check your model path.",
          persisted,
          modelId);
    }
    persistModelId(modelId, statePath);
  }

  private String loadPersistedModelId(Path statePath) {
    try {
      if (Files.exists(statePath)) {
        String content = Files.readString(statePath).trim();
        return content.isBlank() ? null : content;
      }
    } catch (IOException e) {
      LOG.debug("Failed to read persisted model ID: {}", e.getMessage());
    }
    return null;
  }

  private void persistModelId(String modelId, Path statePath) {
    try {
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
