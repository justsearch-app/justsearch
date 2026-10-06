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
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.RetentionClass;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatBootOrder;
import io.justsearch.app.inference.chat.ChatDrainPolicy;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.chat.GenerationActivity;
import io.justsearch.app.api.chat.GenerationLease;
import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import net.jcip.annotations.ThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
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

  /**
   * Spec A §5.2: every llama-server exchange holds a lease from here, and every drain before a
   * stop or relaunch closes admission here. It replaces the generative request gate.
   */
  private final GenerationTracker generation;

  /**
   * Spec A step 2: every start, stop and relaunch is a {@link ChatActuation} sent to the router.
   * The Engine installs the chat controller ({@link #installActuationRouter}); until then, and in
   * isolated component tests, each step runs on the calling thread.
   */
  private final ChatActuator chatActuator = new ManagerChatActuator();
  private volatile ChatActuationRouter actuationRouter = ChatActuationRouter.inline(chatActuator);
  private volatile boolean actuationRouterInstalled;

  /** A committed settings target could not be reconstructed; no old model may serve as it. */
  public void fenceForSettingsRecovery() {
    generation.fenceForRecovery();
  }

  /** The generation-in-flight signal (Spec A §5): GENERATE leases, queue, edges. */
  public GenerationActivity generationActivity() {
    return generation;
  }

  /**
   * Admits one llama-server exchange that does not run through this manager (the {@code /v1}
   * proxy). Closing the lease ends the exchange.
   *
   * @param work the request's work handle, or {@code null}; a GENERATE lease with one paces
   *     indexing for the exchange's lifetime
   * @throws IllegalStateException when admission is closed (draining, relaunching or fenced)
   */
  public GenerationLease admitExchange(GenerationTracker.Kind kind, EngineWorkHandle work) {
    return generation.admit(kind, work);
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
  private volatile boolean closed;
  // Exact accepted Online activation, retained until verification/publication. This is distinct
  // from an established applied runtime and never points at a private settings candidate.
  // Volatile: the Online transition body writes it without the lifecycle lock.
  private volatile LlamaServerOps.StartRequest pendingAcceptedActivation;

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

  /**
   * Where chat requests are sent: the active server's own port (a managed launch's fresh loopback
   * port, or the user's external endpoint), else the configured port while nothing is running.
   * Managed launches never bind the configured port (ADR-0051 decision 2).
   */
  public int servingPort() {
    LlamaServerOps server = serverOps;
    if (server != null) {
      java.util.OptionalInt active = server.activePort();
      if (active.isPresent()) return active.getAsInt();
    }
    return configuredInference().serverPort();
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

  /**
   * Spec A T28: the declared configuration that already used its one same-configuration restart
   * after a crash. Cleared by an explicit activation or an operator retry (T34, O6).
   */
  private volatile String sameConfigRestartSpentFor;

  /**
   * The FAILED code of the chat server's last failure, or null (Spec A §3.1). A newly published
   * configuration clears it (Spec A §2.2).
   */
  private volatile FailedCode lastChatFailedCode;

  /**
   * Counts activation attempts. A latched failure belongs to the attempt it was latched in ({@link
   * #chatFailureAttempt}): a later attempt that is refused before it starts a server keeps the
   * latch (it still holds VDU and running holds refused) but did not fail with that code.
   */
  private final java.util.concurrent.atomic.AtomicLong chatAttempt =
      new java.util.concurrent.atomic.AtomicLong();

  /** The {@link #chatAttempt} the latched failure was latched in. Written before the latch. */
  private volatile long chatFailureAttempt;

  /**
   * True while a self-test session keeps the main server stopped (Spec A T37-T39). Every start of
   * the main server is refused meanwhile, and a prepared candidate is configured without starting,
   * so the candidate never shares the GPU with it and the commit loads once, after the session.
   */
  private volatile boolean selfTestHeld;

  /**
   * Why the chat controller unloaded the idle server for another GPU user ({@code gpu_busy} or
   * {@code game_mode}), or null (Spec A T21). The status projection reads it; the next start or a
   * deactivation clears it.
   */
  private volatile io.justsearch.app.api.runtime.child.StopDetail residencyUnloadDetail;

  /** Starts and stops every llama-server child, the self-test candidate included (Spec B §2.8). */
  private final ChildControl childControl;

  /**
   * The terminal stop's request, recorded by the Engine's shutdown before it closes Head: its
   * reason (QUIT, ENGINE_RESTART, UPGRADE) is what the ledger records for the server. Absent, a
   * close is a QUIT.
   */
  private final java.util.concurrent.atomic.AtomicReference<StopRequest> terminalRequest =
      new java.util.concurrent.atomic.AtomicReference<>();

  /** The ledger token of every stop the chat controller's steps make. */
  static final String CHAT_CONTROLLER = "chat-controller";
  /** The ledger token of the Engine's terminal stop. */
  static final String ENGINE_SHUTDOWN = "engine-shutdown";

  // Configuration constants
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
    this(executorRegistry, config, events, childRegistry, resolvedConfig, ignored -> () -> {});
  }

  public InferenceLifecycleManager(
      io.justsearch.core.execution.EngineExecutorRegistry executorRegistry,
      InferenceConfig config,
      InferenceTelemetryEvents events,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      ResolvedConfig resolvedConfig,
      java.util.function.Function<io.justsearch.app.api.EngineWorkHandle, Runnable> generationLifetime) {
    this(executorRegistry, config, events,
        DefaultChildControl.unrecorded(Objects.requireNonNull(childRegistry, "childRegistry")),
        true, resolvedConfig, generationLifetime);
  }

  /**
   * The Engine's composition: every llama-server child is started and stopped through {@code
   * childControl}, which the caller owns and closes after this.
   */
  public InferenceLifecycleManager(
      io.justsearch.core.execution.EngineExecutorRegistry executorRegistry,
      InferenceConfig config,
      InferenceTelemetryEvents events,
      ChildControl childControl,
      ResolvedConfig resolvedConfig,
      java.util.function.Function<io.justsearch.app.api.EngineWorkHandle, Runnable> generationLifetime) {
    this(executorRegistry, config, events, childControl, false, resolvedConfig,
        generationLifetime);
  }

  private InferenceLifecycleManager(
      io.justsearch.core.execution.EngineExecutorRegistry executorRegistry,
      InferenceConfig config,
      InferenceTelemetryEvents events,
      ChildControl childControl,
      boolean ownsChildControl,
      ResolvedConfig resolvedConfig,
      java.util.function.Function<io.justsearch.app.api.EngineWorkHandle, Runnable> generationLifetime) {
    this.childControl = Objects.requireNonNull(childControl, "childControl");
    Objects.requireNonNull(generationLifetime, "generationLifetime");
    // The tracker owns the indexing-pacing lifetime (ER beginGeneration) for every GENERATE lease.
    this.generation = new GenerationTracker(generationLifetime, this::deliverGenerationEdges);
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
              this::servingPort,
              () -> generation.admit(GenerationTracker.Kind.AUX, null));
      openedOnline =
          new OnlineModeOps(
              executorRegistrations,
              createdHttpClient,
              createdObjectMapper,
              runner::currentMode,
              this::servingPort,
              () -> runner.view().lastKnownModelId(),
              () -> servingInference().modelPath().getFileName().toString(),
              this.events,
              generation);
      openedServer =
          new LlamaServerOps(
              executorRegistrations,
              createdHttpClient,
              createdObjectMapper,
              gpuCapabilitiesService,
              runner::currentMode,
              propsObserver,
              this::handleManagedServerFailure,
              this::handleExternalFailureOffline,
              this.events,
              childControl,
              ownsChildControl);
      this.httpExecutor = openedHttp;
      this.httpClient = createdHttpClient;
      this.objectMapper = createdObjectMapper;
      this.tokenOps = createdTokenOps;
      this.onlineOps = openedOnline;
      this.serverOps = openedServer;
    } catch (RuntimeException | Error failure) {
      closeAfterConstructionFailure(openedServer, openedOnline, openedHttp, failure);
      if (openedServer == null && ownsChildControl) {
        try {
          childControl.close();
        } catch (RuntimeException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
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

  /**
   * One native failure signal of a READY server. The server goes FAILED at once; the chat crash
   * policy (Spec A T27-T30) then decides from the classified exit whether the chat controller
   * relaunches it — one rung lower after a RESOURCE exit, or once with the same configuration
   * after a crash that followed a stable READY window — or whether it stays FAILED until an
   * operator retry or a change. The component monitor no longer retries generative failures.
   */
  private void handleManagedServerFailure(java.util.function.BooleanSupplier stillOwned) {
    CrashFollowUp followUp;
    synchronized (runner.lock()) {
      awaitTransitionSettledLocked();
      if (precommitComposition) {
        invalidatePreparedCandidate(stillOwned);
        return;
      }
      if (!stillOwned.getAsBoolean() || runner.currentMode() != Mode.ONLINE) return;
      LlamaServerOps.StartResult failed = serverOps.activeStartResult().orElse(null);
      LlamaServerOps.StartRequest same = serverOps.recoveryStartRequest().orElse(null);
      LlamaServerOps.ManagedFailure failure =
          failed == null ? null : serverOps.managedFailureOf(failed).orElse(null);
      tokenOps.clearCaches();
      // Spec A step 7: the failure is classified and latched before the OFFLINE landing is
      // published, so the projection of that landing names the classified failure.
      ChatCrashPolicy.Decision decision = decideCrash(failed, same, failure);
      runner.runForceOffline(TransitionReason.CRASH_RECOVERY, null);
      followUp = decision == null ? null : crashFollowUp(failed, same, failure, decision);
    }
    if (followUp != null) submitCrashFollowUp(followUp);
  }

  /**
   * The relaunch or cleanup a READY failure leaves for the chat controller.
   *
   * @param failed the failed server's start identity
   * @param request the exact request that started it, relaunched as is (or one rung lower)
   * @param failure the classified failure
   * @param decision what the crash policy decided
   * @param generation the runner generation the failure was handled at; any later transition
   *     supersedes this follow-up
   */
  private record CrashFollowUp(
      LlamaServerOps.StartResult failed,
      LlamaServerOps.StartRequest request,
      LlamaServerOps.ManagedFailure failure,
      ChatCrashPolicy.Decision decision,
      long generation) {}

  /** Classifies a READY failure and latches its FAILED code; null when nothing can relaunch it. */
  private ChatCrashPolicy.Decision decideCrash(
      LlamaServerOps.StartResult failed,
      LlamaServerOps.StartRequest request,
      LlamaServerOps.ManagedFailure failure) {
    if (failed == null || request == null || failure == null
        || failed.disposition() == LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL) {
      latchChatFailure(FailedCode.CRASH_LOOP);
      return null;
    }
    boolean restartSpent = Objects.equals(sameConfigRestartSpentFor, failed.declaredConfigHash());
    ChatCrashPolicy.Decision decision = ChatCrashPolicy.afterReadyFailure(
        failure.classification(), failure.readyFor(), restartSpent,
        failure.lowerRung().isPresent());
    LOG.error(
        "Chat server failed while READY: class={} readyFor={}s hang={} -> {} ({})",
        failure.classification().crashClass(),
        failure.readyFor().toSeconds(),
        failure.hang(),
        decision.action(),
        decision.rule());
    latchChatFailure(decision.failedCode());
    if (decision.action() == ChatCrashPolicy.Action.RESTART_SAME) {
      sameConfigRestartSpentFor = failed.declaredConfigHash();
    }
    return decision;
  }

  /** The follow-up of a decided READY failure, at the generation of its OFFLINE landing. */
  private CrashFollowUp crashFollowUp(
      LlamaServerOps.StartResult failed,
      LlamaServerOps.StartRequest request,
      LlamaServerOps.ManagedFailure failure,
      ChatCrashPolicy.Decision decision) {
    if (decision.action() == ChatCrashPolicy.Action.FAIL && !failure.hang()) return null;
    return new CrashFollowUp(failed, request, failure, decision, runner.generation());
  }

  /** Hands the follow-up to the chat controller; inline routers run it on this thread. */
  private void submitCrashFollowUp(CrashFollowUp followUp) {
    ChatActuation<Void> step = ChatActuation.of(ChatActuation.Kind.RECOVER, drained -> {
      runCrashFollowUp(followUp);
      return null;
    });
    try {
      actuationRouter.submit(step).whenComplete((ignored, failure) -> {
        if (failure != null) LOG.warn("Chat crash follow-up did not run: {}", safeMessage(failure));
      });
    } catch (RuntimeException refused) {
      LOG.warn("Chat crash follow-up could not be submitted: {}", safeMessage(refused));
    }
  }

  /**
   * Runs on the chat controller. A hung server is stopped now (T30). Then, unless a later
   * transition superseded the failure, the server is relaunched: one rung lower (T27) or with the
   * same configuration (T28). A relaunch that fails leaves chat FAILED with its classified code.
   */
  private void runCrashFollowUp(CrashFollowUp followUp) throws ModeTransitionException {
    synchronized (runner.lock()) {
      if (closed || runner.generation() != followUp.generation()
          || runner.currentMode() != Mode.OFFLINE
          || serverOps.activeStartResult().orElse(null) != followUp.failed()) {
        LOG.info("Chat crash follow-up superseded by a later transition");
        return;
      }
      // Keep the exact request for an operator retry once the failed owner is gone.
      serverOps.reserveRecoveryStart(followUp.request());
      if (followUp.decision().action() == ChatCrashPolicy.Action.STEP_DOWN) {
        serverOps.stepDownAfterFailure(followUp.failure());
      }
    }
    tokenOps.clearCaches();
    // A hung server is ended by us (TIMEOUT, hang); an exited one is already gone.
    serverOps.stopLlamaServer(followUp.failure().hang()
        ? chatStop(StopReason.TIMEOUT).withDetail(StopDetail.HANG)
        : chatStop(StopReason.CRASH_RECOVERY));
    if (followUp.decision().action() == ChatCrashPolicy.Action.FAIL || closed) return;
    LlamaServerOps.StartRequest request = followUp.request();
    runner.run(
        TransitionReason.CRASH_RECOVERY,
        events::onStartupFailure,
        priorView -> {
          try {
            runner.clearProps();
            serverOps.resetHealthFailures();
            var started = serverOps.startLlamaServer(request);
            serverOps.waitForServerHealth(started);
            verifyAppliedServer(request, started);
            lastChatFailedCode = null;
            LOG.info("Chat server relaunched after a failure while READY ({})",
                followUp.decision().rule());
            return TransitionOutcome.success(
                Mode.ONLINE, runner.view().withPhase(Mode.ONLINE).withExternal(false));
          } catch (ModeTransitionException mte) {
            latchChatFailure(LlamaServerExitException.failedCodeOf(mte));
            return failedOnlineStartup(TransitionRunner.mapExceptionToFailure(mte), priorView, mte);
          } catch (Exception e) {
            latchChatFailure(FailedCode.START_FAILED);
            return failedOnlineStartup(
                new InferenceFailure.TransitionFailure(
                    TransitionCode.ONLINE_START_FAILED,
                    "Crash relaunch failed: " + safeMessage(e),
                    e),
                priorView, e);
          }
        });
  }

  /**
   * The FAILED code the latest chat attempt failed with (Spec A §3.1), or null when none is latched
   * or the latch belongs to an earlier attempt. The status projection (Spec A step 7) reads this;
   * a refused activation therefore never shows an earlier failure as its own.
   */
  public FailedCode lastChatFailedCode() {
    FailedCode latched = lastChatFailedCode;
    return latched != null && chatFailureAttempt == chatAttempt.get() ? latched : null;
  }

  /**
   * Why chat is unloaded for another GPU user ({@code gpu_busy} or {@code game_mode}), or null
   * while it is not (Spec A §3.1 UNLOADED RESIDENCY_PRESSURE). The status projection reads this.
   */
  public io.justsearch.app.api.runtime.child.StopDetail residencyUnloadDetail() {
    return residencyUnloadDetail;
  }

  /** Spec A T21: the idle server stops for another GPU user; see {@link ChatActuator}. */
  private ChatActuation<ChatActuator.ResidencyUnload> residencyUnloadStep(
      io.justsearch.app.api.runtime.child.StopDetail detail,
      java.util.function.BooleanSupplier stillWanted) {
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(stillWanted, "stillWanted");
    return ChatActuation.<ChatActuator.ResidencyUnload>draining(ChatActuation.Kind.RESIDENCY_UNLOAD,
        // A lease is open (or admission is fenced): a reply is never cut; nothing changes.
        notIdle -> ChatActuator.ResidencyUnload.BUSY,
        drained -> {
          if (!stillWanted.getAsBoolean()) return ChatActuator.ResidencyUnload.NOT_WANTED;
          synchronized (runner.lock()) {
            // A server this Engine did not start is reported, never unloaded.
            if (serverOps.isExternalServerActive() && runner.currentMode() == Mode.ONLINE) {
              return ChatActuator.ResidencyUnload.EXTERNAL;
            }
          }
          // Recorded before the stop's transition is published, so the projection says why.
          residencyUnloadDetail = detail;
          try {
            switchToIndexingModeStep(TransitionReason.RESIDENCY_UNLOAD);
          } catch (ModeTransitionException | RuntimeException failure) {
            residencyUnloadDetail = null;
            throw failure;
          }
          return ChatActuator.ResidencyUnload.UNLOADED;
        });
  }

  /** Latches the failure of the current attempt. */
  private void latchChatFailure(FailedCode code) {
    chatFailureAttempt = chatAttempt.get();
    lastChatFailedCode = code;
  }

  /**
   * A lifecycle step runs without the lifecycle lock (Spec A §3.5 item 5). A failure signal that
   * arrives meanwhile waits, lock released, for the step to end and then acts on the settled
   * state, as it did when the step held the lock throughout.
   */
  private void awaitTransitionSettledLocked() {
    try {
      runner.awaitSettledLocked(() -> !closed);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Retries an established applied runtime or the exact failed activation of an accepted target. */
  public ComponentRecoveryAction.Result recoverComponent(
      ComponentRecoveryAction.Request request,
      BiFunction<InferenceConfig, ResolvedConfig, String> appliedVersionOf) throws Exception {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(appliedVersionOf, "appliedVersionOf");
    try {
      RecoveryAttempt attempt = actuateAny(ChatActuation.of(ChatActuation.Kind.RECOVER,
          drained -> beginRecovery(request, appliedVersionOf)));
      if (attempt.decided() != null) return attempt.decided();
      // The controller drains in-flight replies between the two steps, with no step running, so
      // an explicit stop that arrives during the drain still supersedes this attempt.
      return actuateAny(ChatActuation.draining(ChatActuation.Kind.RECOVER,
          failure -> completeRecoveryDrainFailure(request, attempt.admitted(), attempt.retry(),
              attempt.physicalOwner(), attempt.generation(), failure,
              failure instanceof InterruptedException
                  ? "generative request drain interrupted"
                  : "generative request drain failed"),
          drained -> relaunchForRecovery(request, attempt, drained)));
    } catch (ModeTransitionException refused) {
      // A terminal controller refuses the attempt, as a closed manager supersedes it.
      if (refused.getCause() instanceof ChatActuationRouter.RefusedException) {
        return ComponentRecoveryAction.Result.SUPERSEDED;
      }
      throw refused;
    }
  }

  /** A begun recovery attempt, or the result already decided before any drain. */
  private record RecoveryAttempt(
      ComponentRecoveryAction.Result decided,
      LlamaServerOps.StartRequest retry,
      LlamaServerOps.StartResult physicalOwner,
      long generation,
      EngineComponentSnapshot.Component admitted,
      String retryVersion) {
    static RecoveryAttempt decided(ComponentRecoveryAction.Result result) {
      return new RecoveryAttempt(result, null, null, 0L, null, null);
    }
  }

  private RecoveryAttempt beginRecovery(
      ComponentRecoveryAction.Request request,
      BiFunction<InferenceConfig, ResolvedConfig, String> appliedVersionOf) {
    synchronized (runner.lock()) {
      EngineComponentSnapshot.Component expected = request.expected();
      if (closed || request.cancelled()) {
        return RecoveryAttempt.decided(ComponentRecoveryAction.Result.SUPERSEDED);
      }
      if (expected.state() != ComponentState.FAILED
          || precommitComposition || preparedConfigApply != null
          || !sameRecoveryLineage(expected, request.current(), expected.recoveryAttempts())) {
        return RecoveryAttempt.decided(ComponentRecoveryAction.Result.REFUSED);
      }
      LlamaServerOps.StartRequest retry = serverOps.recoveryStartRequest().orElse(null);
      LlamaServerOps.StartResult physicalOwner = serverOps.activeStartResult().orElse(null);
      long recoveryGeneration = runner.generation();
      if (retry == null) return RecoveryAttempt.decided(ComponentRecoveryAction.Result.REFUSED);
      String retryVersion =
          appliedVersionOf.apply(retry.context().inference(), retry.context().resolved());
      boolean established = Objects.equals(expected.appliedVersion(), retryVersion);
      boolean acceptedActivation = matchesAcceptedActivation(retry)
          && Objects.equals(expected.desiredVersion(), retryVersion);
      if ((!established && !acceptedActivation)
          || !request.begin()) {
        return RecoveryAttempt.decided(ComponentRecoveryAction.Result.REFUSED);
      }
      EngineComponentSnapshot.Component admitted = request.admitted().orElse(null);
      if (admitted == null
          || !sameRecoveryLineage(expected, admitted, expected.recoveryAttempts() + 1)) {
        return RecoveryAttempt.decided(ComponentRecoveryAction.Result.SUPERSEDED);
      }
      if (request.cancelled() || !serverOps.reserveRecoveryStart(retry)) {
        return RecoveryAttempt.decided(ComponentRecoveryAction.Result.SUPERSEDED);
      }
      return new RecoveryAttempt(
          null, retry, physicalOwner, recoveryGeneration, admitted, retryVersion);
    }
  }

  private ComponentRecoveryAction.Result relaunchForRecovery(
      ComponentRecoveryAction.Request request,
      RecoveryAttempt attempt,
      ChatActuator.DrainHold admissionHold) throws Exception {
    final LlamaServerOps.StartRequest retry = attempt.retry();
    final EngineComponentSnapshot.Component admitted = attempt.admitted();
    try {
      synchronized (runner.lock()) {
        if (currentRecoveryOwner(request, admitted, retry) == null
            || !samePhysicalRecoveryOwner(retry, attempt.physicalOwner(), attempt.generation())) {
          return ComponentRecoveryAction.Result.SUPERSEDED;
        }
      }
      // Spec A §3.5 item 5: the stop, the start and the health wait run without the lock.
      final LlamaServerOps.StartResult started;
      try {
        tokenOps.clearCaches();
        serverOps.stopLlamaServer(chatStop(StopReason.CRASH_RECOVERY));
        if (closed || request.cancelled()) return ComponentRecoveryAction.Result.SUPERSEDED;

        runner.clearProps();
        serverOps.resetHealthFailures();
        started = serverOps.startLlamaServer(retry);
        serverOps.waitForServerHealthOnce(started);
      } catch (Exception failure) {
        LOG.error("Generative component recovery failed", failure);
        return completeRecoveryFailure(
            request, admitted, retry, failure, "same-configuration recovery failed");
      }
      synchronized (runner.lock()) {
        try {
          verifyAppliedServer(retry, started);

          if (currentRecoveryOwner(request, admitted, retry) == null) {
            return ComponentRecoveryAction.Result.SUPERSEDED;
          }
          runner.run(
              TransitionReason.CRASH_RECOVERY,
              ignored -> {},
              prior -> TransitionOutcome.success(
                  Mode.ONLINE,
                  runner.view().withPhase(Mode.ONLINE).withExternal(false)));
          EngineComponentSnapshot.Component terminal = currentRecoveryOwnerAfterVerification(
              request, admitted, retry, attempt.retryVersion());
          if (terminal == null) return ComponentRecoveryAction.Result.SUPERSEDED;
          var result = request.complete(terminal, ComponentState.READY, null,
                  "accepted generative recovery verified healthy")
              .map(ComponentRecoveryAction.Result::recovered)
              .orElse(ComponentRecoveryAction.Result.SUPERSEDED);
          if (result.outcome() == ComponentRecoveryAction.Outcome.RECOVERED) {
            pendingAcceptedActivation = null;
            // O6/T34: an operator retry clears the FAILED latch and the spent T28 restart.
            sameConfigRestartSpentFor = null;
            lastChatFailedCode = null;
          }
          return result;
        } catch (Exception failure) {
          LOG.error("Generative component recovery failed", failure);
          return completeRecoveryFailure(
              request, admitted, retry, failure, "same-configuration recovery failed");
        }
      }
    } finally {
      admissionHold.close();
    }
  }

  private boolean matchesAcceptedActivation(LlamaServerOps.StartRequest retry) {
    var pending = pendingAcceptedActivation;
    var target = configured;
    return pending != null && pending.context() == retry.context()
        && pending.adoptionPolicy() == retry.adoptionPolicy()
        && target.inference() == retry.context().inference()
        && target.resolved() == retry.context().resolved()
        && target.policy() == retry.adoptionPolicy();
  }

  private EngineComponentSnapshot.Component currentRecoveryOwnerAfterVerification(
      ComponentRecoveryAction.Request request, EngineComponentSnapshot.Component admitted,
      LlamaServerOps.StartRequest retry, String verifiedVersion) {
    var current = request.current();
    // The Online listener publishes the verified launch identity with READY. Permit that exact
    // A -> B promotion only here, after verification; all pre-verification fences still require A.
    if (closed || request.cancelled() || !serverOps.ownsRecoveryAttempt(retry) || current == null
        || !admitted.spec().equals(current.spec())
        || !Objects.equals(admitted.desiredVersion(), current.desiredVersion())
        || !Objects.equals(verifiedVersion, current.appliedVersion())
        || admitted.recoveryAttempts() != current.recoveryAttempts()) return null;
    return current;
  }

  private boolean samePhysicalRecoveryOwner(
      LlamaServerOps.StartRequest retry,
      LlamaServerOps.StartResult expectedActive,
      long expectedGeneration) {
    if (runner.generation() != expectedGeneration) return false;
    LlamaServerOps.StartResult currentActive = serverOps.activeStartResult().orElse(null);
    if (expectedActive != null) return currentActive == expectedActive;
    return currentActive == null && serverOps.recoveryStartRequest().orElse(null) == retry;
  }

  private ComponentRecoveryAction.Result completeRecoveryDrainFailure(
      ComponentRecoveryAction.Request request,
      EngineComponentSnapshot.Component admitted,
      LlamaServerOps.StartRequest retry,
      LlamaServerOps.StartResult physicalOwner,
      long recoveryGeneration,
      Exception failure,
      String evidencePrefix) {
    synchronized (runner.lock()) {
      if (!samePhysicalRecoveryOwner(retry, physicalOwner, recoveryGeneration)) {
        return ComponentRecoveryAction.Result.SUPERSEDED;
      }
      return completeRecoveryFailure(request, admitted, retry, failure, evidencePrefix);
    }
  }

  private ComponentRecoveryAction.Result completeRecoveryFailure(
      ComponentRecoveryAction.Request request,
      EngineComponentSnapshot.Component admitted,
      LlamaServerOps.StartRequest retry,
      Exception failure,
      String evidencePrefix) {
    synchronized (runner.lock()) {
      EngineComponentSnapshot.Component terminal = currentRecoveryOwner(request, admitted, retry);
      if (terminal == null) return ComponentRecoveryAction.Result.SUPERSEDED;
      String reasonCode = retainedLifecycleCause(terminal.reasonCode());
      if (reasonCode == null) {
        reasonCode = retainedLifecycleCause(request.expected().reasonCode());
      }
      if (reasonCode == null) {
        reasonCode = LifecycleReasonCode.INFERENCE_ACTIVATION_FAILED.code();
      }
      String typedFailure = failure instanceof ModeTransitionException transition
          ? transition.failure().wireCode() : failure.getClass().getSimpleName();
      return request.complete(
              terminal,
              ComponentState.FAILED,
              reasonCode,
              evidencePrefix + " [" + typedFailure + "]: " + safeMessage(failure))
          .map(ComponentRecoveryAction.Result::failed)
          .orElse(ComponentRecoveryAction.Result.SUPERSEDED);
    }
  }

  private EngineComponentSnapshot.Component currentRecoveryOwner(
      ComponentRecoveryAction.Request request,
      EngineComponentSnapshot.Component admitted,
      LlamaServerOps.StartRequest retry) {
    EngineComponentSnapshot.Component current = request.current();
    if (closed || request.cancelled() || !serverOps.ownsRecoveryAttempt(retry)
        || !sameRecoveryLineage(admitted, current, admitted.recoveryAttempts())) {
      return null;
    }
    return current;
  }

  private static boolean sameRecoveryLineage(
      EngineComponentSnapshot.Component baseline,
      EngineComponentSnapshot.Component current,
      int expectedAttempts) {
    return current != null
        && baseline.spec().equals(current.spec())
        && Objects.equals(baseline.appliedVersion(), current.appliedVersion())
        && Objects.equals(baseline.desiredVersion(), current.desiredVersion())
        && current.recoveryAttempts() == expectedAttempts;
  }

  private static String retainedLifecycleCause(String reasonCode) {
    RetentionClass retention = LifecycleReasonCode.retentionClassOf(reasonCode);
    return retention == RetentionClass.FAULT || retention == RetentionClass.STICKY
        ? reasonCode : null;
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
      awaitTransitionSettledLocked();
      if (precommitComposition || !stillOwned.getAsBoolean()
          || !serverOps.isUsingExternalRaw() || runner.currentMode() != Mode.ONLINE) {
        return;
      }
      LOG.error(
          "External llama-server on port {} became unhealthy: {}. Switching to OFFLINE.",
          servingPort(),
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

  /**
   * Publishes from the verified managed launch while its lifecycle owner cannot change. A desired
   * Offline configuration, an external server, or a private precommit candidate is not serving proof.
   * Callers acquire the shared publication lock only inside this callback.
   */
  public boolean withVerifiedServingConfiguration(
      BiConsumer<InferenceConfig, ResolvedConfig> publication) {
    Objects.requireNonNull(publication, "publication");
    synchronized (runner.lock()) {
      if (closed || precommitComposition || preparedConfigApply != null
          || runner.currentMode() != Mode.ONLINE) return false;
      var started = serverOps.activeStartResult().orElse(null);
      if (started == null
          || started.disposition() == LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL) return false;
      publication.accept(started.context().inference(), started.context().resolved());
      return true;
    }
  }

  /** The managed server's profile, or retained configuration while Offline. */
  public String servingChatProfileId() {
    return servingInference().chatProfileId();
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

  /** Whether the component monitor, rather than autonomous convergence, owns the failed attempt. */
  public boolean componentRecoveryPending() {
    synchronized (runner.lock()) {
      return serverOps.componentRecoveryPending();
    }
  }

  // ==================== Chat actuation (Spec A step 2) ====================

  /**
   * Installs the chat controller as the single owner of every actuation. Called once, by the
   * composition root, right after construction.
   */
  public void installActuationRouter(ChatActuationRouter router) {
    Objects.requireNonNull(router, "router");
    synchronized (runner.lock()) {
      if (actuationRouterInstalled) {
        throw new IllegalStateException("A chat actuation router is already installed");
      }
      actuationRouterInstalled = true;
      actuationRouter = router;
    }
  }

  /** The actuation primitives the chat controller calls. Only the controller uses them. */
  public ChatActuator chatActuator() {
    return chatActuator;
  }

  /**
   * The installed chat controller's holds and self-test sessions, or {@code null} when no
   * controller is installed (isolated component tests).
   */
  public ChatHolds chatHolds() {
    return actuationRouter instanceof ChatHolds holds ? holds : null;
  }

  /**
   * The installed chat controller's boot-order gate (Spec A §6.2), or {@code null} when no
   * controller is installed.
   */
  public ChatBootOrder chatBootOrder() {
    return actuationRouter instanceof ChatBootOrder gate ? gate : null;
  }

  /**
   * The installed chat controller's residency surface (Spec A step 9), or {@code null} when no
   * controller is installed.
   */
  public io.justsearch.app.inference.chat.ChatResidency chatResidency() {
    return actuationRouter instanceof io.justsearch.app.inference.chat.ChatResidency residency
        ? residency : null;
  }

  /** Generation edges run on the background callback executor, off the lease holder's thread. */
  private void deliverGenerationEdges(Runnable edges) {
    OnlineModeOps ops = onlineOps;
    if (ops == null) throw new java.util.concurrent.RejectedExecutionException("not constructed");
    ops.edgeExecutor().execute(edges);
  }

  private final class ManagerChatActuator implements ChatActuator {
    @Override
    public DrainHold drain(Duration bound) throws InterruptedException {
      return drain(new ChatDrainPolicy(StopReason.RECONFIGURE, ChatDrainPolicy.Waiting.AFTER_IDLE,
          bound, ChatDrainPolicy.Expiry.REFUSE_BUSY, false));
    }

    @Override
    public DrainHold drain(ChatDrainPolicy policy) throws InterruptedException {
      // A stop of the serving server waits for replies only while one serves: with no server
      // serving, nothing can be in flight against it, so admission just closes.
      if (policy.onlyWhileServing() && runner.currentMode() != Mode.ONLINE) {
        return generation.closeNow();
      }
      return generation.drain(policy);
    }

    @Override
    public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
      return activationStep(activation);
    }

    @Override
    public ChatActuation<Void> vduEnter() {
      return vduEnterStep();
    }

    @Override
    public ChatActuation<Void> vduExit() {
      return vduExitStep();
    }

    @Override
    public ChatActuation<Void> selfTestStop() {
      return ChatActuation.<Void>draining(ChatActuation.Kind.SELF_TEST, InferenceLifecycleManager.this::drainRefused,
          drained -> {
            selfTestHeld = true;
            boolean adoptedExternal;
            synchronized (runner.lock()) {
              adoptedExternal =
                  serverOps.isExternalServerActive() && runner.currentMode() == Mode.ONLINE;
            }
            // A server this Engine did not start is not stopped for a self-test.
            if (!adoptedExternal) switchToIndexingModeStep(TransitionReason.SELF_TEST);
            return null;
          });
    }

    @Override
    public ChatActuation<Void> selfTestEnd() {
      return ChatActuation.<Void>of(ChatActuation.Kind.SELF_TEST_END, drained -> {
        selfTestHeld = false;
        return null;
      });
    }

    @Override
    public ChatActuation<ResidencyUnload> residencyUnload(
        io.justsearch.app.api.runtime.child.StopDetail detail,
        java.util.function.BooleanSupplier stillWanted) {
      return residencyUnloadStep(detail, stillWanted);
    }

    @Override
    public GenerationActivity generationActivity() {
      return generation;
    }

    @Override
    public FailedCode failureLatch() {
      return lastChatFailedCode;
    }

    @Override
    public boolean serverRunning() {
      Mode mode = runner.currentMode();
      return mode == Mode.ONLINE || mode == Mode.TRANSITIONING;
    }

    @Override
    public boolean gpuChat() {
      try {
        if (configuredStartRequest().effectiveGpuLayers() <= 0) return false;
        GpuCapabilities.Effective effective = gpuCapabilitiesService.snapshot().effective();
        return effective != null && effective.cudaAvailable();
      } catch (RuntimeException unknown) {
        // An unknown device is no reason to hold chat back: the gate only orders a GPU start.
        LOG.debug("GPU chat detection failed; the boot-order gate is skipped: {}",
            safeMessage(unknown));
        return false;
      }
    }

    @Override
    public <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception {
      try {
        return runner.exclusiveStep(() -> step.run(drained));
      } finally {
        // A prepared candidate keeps its drained admission until commit or abort; every other step
        // reopens admission when it ends, including one that inherited a coalesced step's drain.
        if (drained != null && step.kind() != ChatActuation.Kind.PREPARE) drained.close();
      }
    }

    @Override
    public void terminalStop(StopReason reason, Duration timeout) {
      closed = true;
      serverOps.stopServerForTerminalShutdown(terminalStopFor(reason), timeout);
    }
  }

  /**
   * Records how the Engine is ending, before its shutdown reaches this manager: the terminal stop
   * then records that reason (and detail) for each server, instead of a plain QUIT. The first
   * record wins.
   */
  public void recordTerminalStop(StopRequest request) {
    Objects.requireNonNull(request, "request");
    terminalRequest.compareAndSet(null, request);
  }

  /** The terminal stop for {@code reason}: the recorded request when it agrees, else a plain one. */
  private StopRequest terminalStopFor(StopReason reason) {
    StopRequest recorded = terminalRequest.get();
    if (reason == null) {
      return recorded != null ? recorded : StopRequest.of(StopReason.QUIT, StopMode.NOW,
          ENGINE_SHUTDOWN);
    }
    return recorded != null && recorded.reason() == reason
        ? recorded : StopRequest.of(reason, StopMode.NOW, ENGINE_SHUTDOWN);
  }

  /** A stop one of the chat controller's steps makes; the controller drained replies first. */
  static StopRequest chatStop(StopReason reason) {
    return StopRequest.of(reason, StopMode.NOW, CHAT_CONTROLLER);
  }

  /**
   * The interim mapping from a transition's reason to the server stop's (Spec B §2.8). {@link
   * TransitionReason#SHUTDOWN} has no fixed mapping (the Engine's terminal reason, see {@link
   * #stopFor}); {@link TransitionReason#UNKNOWN} is never a stop's reason and is refused.
   */
  static StopReason stopReasonFor(TransitionReason reason) {
    Objects.requireNonNull(reason, "reason");
    return switch (reason) {
      case USER_SWITCH, AUTO_START, RESIDENCY_RELOAD -> StopReason.DEACTIVATE;
      case RESIDENCY_UNLOAD -> StopReason.RESIDENCY_PRESSURE;
      case SELF_TEST -> StopReason.SELF_TEST;
      case VDU_ENTER -> StopReason.VDU_ENTER;
      case VDU_EXIT -> StopReason.VDU_EXIT;
      case CRASH_RECOVERY -> StopReason.CRASH_RECOVERY;
      case CONFIG_APPLY, EXTERNAL_DETACH, ADMIN_TRIGGERED -> StopReason.RECONFIGURE;
      case SHUTDOWN -> StopReason.QUIT;
      case UNKNOWN -> throw new IllegalArgumentException(
          "a llama-server stop needs a known reason, not " + reason);
    };
  }

  /** The stop a transition with {@code reason} makes; a SHUTDOWN is the Engine's terminal stop. */
  private StopRequest stopFor(TransitionReason reason) {
    if (reason == TransitionReason.SHUTDOWN) return terminalStopFor(null);
    StopRequest stop = chatStop(stopReasonFor(reason));
    // A residency unload records why (gpu_busy or game_mode); the step set it before the stop.
    StopDetail unloadDetail = residencyUnloadDetail;
    if (reason == TransitionReason.RESIDENCY_UNLOAD && unloadDetail != null) {
      return stop.withDetail(unloadDetail);
    }
    return stop;
  }

  /**
   * A reconfiguring stop: {@link StopReason#MODEL_SWITCH} when the model file changes, else the
   * reason's own mapping.
   */
  private StopRequest reconfigureStop(TransitionReason reason, InferenceConfig from,
      InferenceConfig to) {
    StopRequest mapped = stopFor(reason);
    if (mapped.reason() == StopReason.RECONFIGURE && from != null && to != null
        && !Objects.equals(from.modelPath(), to.modelPath())) {
      return chatStop(StopReason.MODEL_SWITCH);
    }
    return mapped;
  }

  /**
   * The self-test seam's launcher (Spec B step 6): each candidate is a {@code LLAMA_SELF_TEST}
   * child of this manager's control, stopped with {@code SELF_TEST} before the main server starts.
   */
  public io.justsearch.app.inference.chat.SelfTestLauncher selfTestLauncher() {
    return new io.justsearch.app.inference.chat.ChildSelfTestLauncher(childControl);
  }

  /** Sends one step to the router and maps the router's verdicts onto the legacy exception. */
  private <T> T actuate(ChatActuation<T> step) throws ModeTransitionException {
    try {
      return actuateAny(step);
    } catch (ModeTransitionException | RuntimeException failure) {
      throw failure;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw modeTransition(ModeTransitionException.Reason.INTERRUPTED,
          "Interrupted while waiting for the chat controller", interrupted);
    } catch (Exception other) {
      throw modeTransitionWithCauseMessage(
          ModeTransitionException.Reason.ONLINE_START_FAILED, "Chat actuation failed: ", other);
    }
  }

  private <T> T actuateAny(ChatActuation<T> step) throws Exception {
    // A caller holding the lifecycle monitor would deadlock with the controller thread, which
    // takes that monitor inside the step.
    if (actuationRouterInstalled && Thread.holdsLock(runner.lock())) {
      throw new IllegalStateException(
          "a chat actuation was requested while holding the lifecycle lock (" + step + ")");
    }
    try {
      return actuationRouter.route(step);
    } catch (ChatActuationRouter.SupersededException superseded) {
      throw modeTransition(ModeTransitionException.Reason.ALREADY_TRANSITIONING,
          "Superseded by a newer request: " + superseded.getMessage(), superseded);
    } catch (ChatActuationRouter.RefusedException refused) {
      throw modeTransition(ModeTransitionException.Reason.ALREADY_TRANSITIONING,
          "Inference runtime is shutting down: " + refused.getMessage(), refused);
    }
  }

  /**
   * Spec A §4.1 {@code setActivation}: publishes whether chat should run ({@code spec ∧ policy},
   * or a procedure's requirement) to the chat controller and returns at once. The runtime
   * reconciler forwards boot, spec writes and procedure engine control through here only.
   */
  public CompletionStage<Void> setActivation(ChatActuationRouter.Activation activation) {
    Objects.requireNonNull(activation, "activation");
    if (actuationRouterInstalled && Thread.holdsLock(runner.lock())) {
      throw new IllegalStateException(
          "an activation was requested while holding the lifecycle lock (" + activation + ")");
    }
    return actuationRouter.setActivation(activation);
  }

  /**
   * Activations share the mode switches' key: a waiting one is replaced by the latest. A
   * deactivation drains first (Spec A §3.4 DEACTIVATE: 30 s, then stop anyway).
   */
  private ChatActuation<Void> activationStep(ChatActuationRouter.Activation activation) {
    if (activation.active()) {
      return ChatActuation.<Void>coalescing(ChatActuation.Kind.ACTIVATE, MODE_KEY, null, null,
          drained -> {
            switchToOnlineModeStep(activation.reason());
            return null;
          });
    }
    return ChatActuation.<Void>coalescingAfterDrain(ChatActuation.Kind.DEACTIVATE, MODE_KEY,
        null, null, this::drainRefused,
        drained -> {
          // Chat switched off is OFF, not unloaded.
          residencyUnloadDetail = null;
          boolean adoptedExternal;
          synchronized (runner.lock()) {
            adoptedExternal =
                serverOps.isExternalServerActive() && runner.currentMode() == Mode.ONLINE;
          }
          if (adoptedExternal) {
            detachExternalServerStep();
          } else {
            switchToIndexingModeStep(activation.reason());
          }
          return null;
        });
  }

  /** What a caller sees when the drain before its stop or relaunch failed; nothing changed. */
  private <T> T drainRefused(Exception failure) throws ModeTransitionException {
    if (failure instanceof InterruptedException interrupted) {
      throw modeTransition(ModeTransitionException.Reason.INTERRUPTED,
          "Interrupted while draining generative requests", interrupted);
    }
    throw modeTransition(ModeTransitionException.Reason.ALREADY_TRANSITIONING,
        "Chat is busy: " + failure.getMessage(), failure);
  }

  /** Mode switches share one key: a waiting switch is replaced by the latest one. */
  private static final String MODE_KEY = "mode";

  /** Legacy config applies share one key; the merged step keeps the strongest restart policy. */
  private static final String APPLY_KEY = "apply";

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
    Objects.requireNonNull(reason, "reason");
    actuate(ChatActuation.<Void>coalescing(ChatActuation.Kind.ACTIVATE, MODE_KEY, null, null,
        drained -> {
          switchToOnlineModeStep(reason);
          return null;
        }));
  }

  private void switchToOnlineModeStep(TransitionReason reason) throws ModeTransitionException {
    if (selfTestHeld) {
      throw modeTransition(ModeTransitionException.Reason.ALREADY_TRANSITIONING,
          "The chat server stays stopped while an activation self-test runs");
    }
    synchronized (runner.lock()) {
      if ((reason == TransitionReason.AUTO_START || reason == TransitionReason.VDU_ENTER)
          && serverOps.componentRecoveryPending()) {
        throw modeTransition(
            ModeTransitionException.Reason.ONLINE_START_FAILED,
            "Generative recovery owns the failed applied configuration");
      }
    }
    // Spec A §3.5 item 5: the start and its health wait run in the transition body, without the
    // lifecycle lock.
    runner.run(
      reason,
      events::onStartupFailure,
      priorView -> {
        if (priorView.phase() == Mode.ONLINE) {
          LOG.debug("Already in Online Mode");
          return TransitionOutcome.success(Mode.ONLINE, priorView);
        }

        // A new attempt: an earlier latched failure is no longer this attempt's failure.
        chatAttempt.incrementAndGet();
        // A start ends a residency unload (Spec A T23).
        residencyUnloadDetail = null;
        var startRequest = configuredStartRequest();
        pendingAcceptedActivation = startRequest;
        serverOps.retainAttemptedStartRequest(startRequest);
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
          // The GPU barrier (Spec B §2.6) replaces the fixed 2 s VRAM flush sleep: it waits only
          // while a stopped server's (or self-test candidate's) adapter memory is still pending
          // release, at most 5 s, and returns at once otherwise.
          if (!serverOps.awaitGpuRelease()) {
            LOG.debug("GPU release of the previous llama-server still pending; starting anyway");
          }
          // Tempdoc 518 fix A: wipe any stale /props observations from the prior server
          // instance before starting a new one. The new server's /props observation (if it
          // fires) will repopulate via runner.mergeProps; if /props doesn't fire, the view
          // remains correctly null rather than carrying the prior server's stale data.
          runner.clearProps();
          serverOps.resetHealthFailures();
          var started = serverOps.startLlamaServer(startRequest);
          serverOps.waitForServerHealth(started);
          verifyAppliedServer(startRequest, started);
          pendingAcceptedActivation = null;
          // T34: an activation clears the FAILED latch and the spent T28 restart.
          sameConfigRestartSpentFor = null;
          lastChatFailedCode = null;
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
          latchChatFailure(LlamaServerExitException.failedCodeOf(mte));
          return failedOnlineStartup(TransitionRunner.mapExceptionToFailure(mte), priorView, mte);
        } catch (Exception e) {
          latchChatFailure(FailedCode.START_FAILED);
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
      serverOps.stopLlamaServer(chatStop(StopReason.ROLLBACK));
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
    Objects.requireNonNull(reason, "reason");
    actuate(ChatActuation.<Void>coalescingAfterDrain(ChatActuation.Kind.DEACTIVATE, MODE_KEY,
        null, null, this::drainRefused,
        drained -> {
          switchToIndexingModeStep(reason);
          return null;
        }));
  }

  private void switchToIndexingModeStep(TransitionReason reason) throws ModeTransitionException {
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
            serverOps.stopLlamaServer(stopFor(reason));
            // The GPU barrier replaces the fixed 2 s VRAM flush sleep (Spec B §2.6).
            if (!serverOps.awaitGpuRelease()) {
              LOG.debug("GPU release of the stopped llama-server still pending after 5 s");
            }

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
    actuate(vduEnterStep());
  }

  /** Spec A §3.4 VDU_ENTER: replies finish first (30 s), else the entry is refused as busy. */
  private ChatActuation<Void> vduEnterStep() {
    return ChatActuation.<Void>draining(ChatActuation.Kind.VDU_ENTER, this::drainRefused,
        drained -> {
          enterVduModeStep();
          return null;
        });
  }

  private void enterVduModeStep() throws ModeTransitionException {
    final ConfiguredInference previous;
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
      previous = configured;
    }
    // The restart and its health wait run without the lifecycle lock (Spec A §3.5 item 5); the
    // controller serializes this step with every other actuation.
    LOG.info("Entering VDU mode (restarting server with vision-safe flags)...");
    ConfigApplyResult result = applyConfiguration(previous.inference().withVduMode(true),
        previous.resolved(), RestartPolicy.RESTART_ALWAYS, TransitionReason.VDU_ENTER, previous.policy());
    if (result.failure() != null) throw result.failure();
    preVduConfig = previous;
  }

  public void exitVduMode() throws ModeTransitionException {
    actuate(vduExitStep());
  }

  /** Spec A §3.4 VDU_EXIT: the restore waits, unbounded, for replies to finish. */
  private ChatActuation<Void> vduExitStep() {
    return ChatActuation.<Void>draining(ChatActuation.Kind.VDU_EXIT, this::drainRefused,
        drained -> {
          exitVduModeStep();
          return null;
        });
  }

  private void exitVduModeStep() throws ModeTransitionException {
    final ConfiguredInference saved;
    synchronized (runner.lock()) {
      saved = preVduConfig;
      if (saved == null || !configuredInference().vduMode()) {
        LOG.debug("Not in VDU mode; exitVduMode is a no-op");
        return;
      }
    }
    LOG.info("Exiting VDU mode (restoring normal server configuration)...");
    ConfigApplyResult result = applyConfiguration(saved.inference(), saved.resolved(),
        RestartPolicy.RESTART_ALWAYS, TransitionReason.VDU_EXIT, saved.policy());
    if (result.failure() != null) throw result.failure();
    preVduConfig = null;
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
    private final ChatActuator.DrainHold admissionHold;
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
        ChatActuator.DrainHold admissionHold) {
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
          logicalPublication = owner.prepareLogicalPublication(this);
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
      // A newly published configuration clears the failure latch (Spec A §2.2).
      if (!Objects.equals(candidateConfiguration.inference(), incumbentConfiguration.inference())) {
        owner.lastChatFailedCode = null;
      }
      logicalPublication.install();
      state = State.INSTALLED;
    }

    /** Delivers the prebuilt mode notifications/history after publication locks are released. */
    public void notifyAfterSettingsCommit() {
      logicalPublication.notifyAfterInstall();
    }

    /** Finishes committed mode publication and old-resource retirement outside publication locks. */
    public void retireAfterSettingsCommit() throws ModeTransitionException {
      owner.actuate(ChatActuation.<Void>of(ChatActuation.Kind.PREPARED_RETIRE, drained -> {
        owner.retirePreparedConfig(this);
        return null;
      }));
    }

    /** Stops the private candidate and restores the captured incumbent when commit is abandoned. */
    public void abort() throws ModeTransitionException {
      owner.actuate(ChatActuation.<Void>of(ChatActuation.Kind.PREPARED_ABORT, drained -> {
        owner.abortPreparedConfig(this);
        return null;
      }));
    }

    /** Managed configuration witness established before return, or {@code null} for disable. */
    public String declaredConfigHash() {
      return candidate == null ? null : candidate.declaredConfigHash();
    }

    /** Whether preparation verified a managed server for Online publication. */
    public boolean targetsOnline() {
      return enabled;
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
    return prepareResolvedConfig(candidate, candidateResolved, enabled, false);
  }

  /** For refresh, resolve the Online-only restart under the lifecycle lock. */
  public PreparedConfigApply prepareResolvedConfig(
      InferenceConfig candidate, ResolvedConfig candidateResolved, boolean enabled,
      boolean restartIfOnline) throws ModeTransitionException {
    Objects.requireNonNull(candidateResolved, "candidateResolved");
    if (candidate == null) {
      throw modeTransition(ModeTransitionException.Reason.CONFIG_REQUIRED, "Config is required");
    }
    // The controller drains in-flight replies (Spec A §3.4, 30 s) with no lifecycle lock held,
    // then runs the private candidate composition under the lock.
    return actuate(ChatActuation.draining(ChatActuation.Kind.PREPARE,
        failure -> {
          if (failure instanceof InterruptedException interrupted) {
            throw modeTransition(ModeTransitionException.Reason.INTERRUPTED,
                "Interrupted while draining generative requests", interrupted);
          }
          throw modeTransition(ModeTransitionException.Reason.ALREADY_TRANSITIONING,
              failure.getMessage(), failure);
        },
        drained -> prepareResolvedConfigStep(
            candidate, candidateResolved, enabled, restartIfOnline, drained)));
  }

  private PreparedConfigApply prepareResolvedConfigStep(
      InferenceConfig candidate, ResolvedConfig candidateResolved, boolean enabled,
      boolean restartIfOnline, ChatActuator.DrainHold admissionHold)
      throws ModeTransitionException {
    boolean transferred = false;
    try {
    final ConfiguredInference incumbentConfiguration;
    final Mode priorMode;
    final LlamaServerOps.StartResult incumbent;
    final LlamaServerOps.StartRequest request;
    synchronized (runner.lock()) {
      // While a self-test session holds the main server stopped, an enabled candidate is
      // configured only; the session's end starts it once (Spec A U11).
      boolean startManaged = enabled && !selfTestHeld
          && (!restartIfOnline || runner.currentMode() == Mode.ONLINE);
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
      if (startManaged && runner.currentMode() == Mode.INDEXING) {
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
      request = new LlamaServerOps.StartRequest(
          context, LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
      if (startManaged) {
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

      incumbentConfiguration = configured;
      priorMode = runner.currentMode();
      incumbent = serverOps.activeStartResult().orElse(null);
      if (priorMode == Mode.ONLINE && incumbent == null) {
        throw modeTransition(
            ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
            "Online inference has no captured server ownership");
      }

      precommitComposition = true;
      if (!startManaged) {
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
    }
    // Spec A §3.5 item 5: the stop, the private start and its health wait run without the
    // lifecycle lock. precommitComposition keeps every other publication out meanwhile.
    try {
      tokenOps.clearCaches();
      serverOps.stopLlamaServer(reconfigureStop(TransitionReason.CONFIG_APPLY,
          incumbent != null ? incumbent.context().inference() : incumbentConfiguration.inference(),
          candidate));
    } catch (RuntimeException stopFailure) {
      synchronized (runner.lock()) {
        precommitComposition = false;
      }
      throw modeTransition(
          ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
          "Incumbent server stop failed: " + safeMessage(stopFailure),
          stopFailure);
    }
    try {
      serverOps.resetHealthFailures();
      LlamaServerOps.StartResult started = serverOps.startLlamaServer(request);
      serverOps.waitForServerHealth(started);
      synchronized (runner.lock()) {
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
      }
    } catch (Exception candidateFailure) {
      throw rollbackPrecommitFailure(
          incumbent, incumbentConfiguration, priorMode, candidateFailure);
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
      PreparedConfigApply prepared) {
    boolean enabled = prepared.enabled;
    Mode priorMode = prepared.priorMode;
    Mode target = enabled ? Mode.ONLINE : priorMode == Mode.ONLINE ? Mode.OFFLINE : priorMode;
    InferenceRuntimeView next = runner.view().withPhase(target);
    // The previous /props model id can outlive a private B start when the new server reports
    // no model alias. Its managed launch witness gives the exact model installed at commit.
    if (enabled) {
      next = next.withModelId(
          prepared.candidate.context().inference().modelPath().getFileName().toString());
    }
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
      serverOps.stopLlamaServer(chatStop(StopReason.ROLLBACK));
      if (priorMode == Mode.ONLINE) {
        var restore = new LlamaServerOps.StartRequest(
            incumbent.context(), LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
        serverOps.resetHealthFailures();
        var restored = serverOps.startLlamaServer(restore);
        serverOps.waitForServerHealth(restored);
        verifyAppliedServer(restore, restored);
      }
      synchronized (runner.lock()) {
        configured = incumbentConfiguration;
        preparedConfigApply = null;
        precommitComposition = false;
      }
      return candidate;
    } catch (Exception rollbackFailure) {
      var combined = new IllegalStateException(
          "Rollback failed: " + safeMessage(rollbackFailure), rollbackFailure);
      combined.addSuppressed(candidate);
      synchronized (runner.lock()) {
        preparedConfigApply = null;
        precommitComposition = false;
      }
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
    }
    // The restart of A and its health wait run without the lifecycle lock (Spec A §3.5 item 5).
    var aborted = modeTransition(
        ModeTransitionException.Reason.INTERRUPTED, "Prepared inference candidate was aborted");
    ModeTransitionException rollback = rollbackPrecommitFailure(
        prepared.incumbent,
        prepared.incumbentConfiguration,
        prepared.priorMode,
        aborted);
    if (rollback != aborted) throw rollback;
    synchronized (runner.lock()) {
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
            // A committed disable: the user turned chat off.
            serverOps.stopLlamaServer(chatStop(StopReason.DEACTIVATE));
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
    ConfigApplyResult result = actuate(applyStep(newConfig, policy, transitionReason));
    if (result.failure() != null) throw result.failure();
  }

  /**
   * One legacy apply. A waiting apply is replaced by a later one (the later configuration is
   * complete, not a delta); the merged step restarts if either asked to.
   */
  private ChatActuation<ConfigApplyResult> applyStep(
      InferenceConfig newConfig, RestartPolicy policy, TransitionReason transitionReason) {
    java.util.function.BinaryOperator<ChatActuation<?>> merge =
        (superseded, latest) -> applyStep(newConfig,
            strongerRestart((RestartPolicy) superseded.coalesceState(), policy), transitionReason);
    ChatActuation.Body<ConfigApplyResult> body =
        drained -> applyConfiguration(newConfig, null, policy, transitionReason,
            LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL);
    if (policy == RestartPolicy.APPLY_ONLY) {
      return ChatActuation.coalescing(ChatActuation.Kind.APPLY, APPLY_KEY, policy, merge, body);
    }
    // Spec A §3.4 RECONFIGURE: a relaunch of the serving server lets replies finish (30 s), else
    // the apply is refused as busy and nothing changes.
    return ChatActuation.coalescingAfterDrain(ChatActuation.Kind.APPLY, APPLY_KEY, policy, merge,
        this::drainRefused, body);
  }

  private static RestartPolicy strongerRestart(RestartPolicy first, RestartPolicy second) {
    RestartPolicy a = first == null ? RestartPolicy.RESTART_IF_ONLINE : first;
    RestartPolicy b = second == null ? RestartPolicy.RESTART_IF_ONLINE : second;
    return restartStrength(a) >= restartStrength(b) ? a : b;
  }

  private static int restartStrength(RestartPolicy policy) {
    return switch (policy) {
      case APPLY_ONLY -> 0;
      case RESTART_IF_ONLINE -> 1;
      case RESTART_ALWAYS -> 2;
    };
  }

  /** Prepares the exact resolved candidate with managed configuration proof. */
  public ConfigApplyResult applyResolvedConfig(
      InferenceConfig candidate, ResolvedConfig candidateResolved, RestartPolicy policy)
      throws ModeTransitionException {
    Objects.requireNonNull(candidateResolved, "candidateResolved");
    return actuate(ChatActuation.of(ChatActuation.Kind.APPLY,
        drained -> applyConfiguration(candidate, candidateResolved, policy,
            TransitionReason.CONFIG_APPLY,
            LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS)));
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
    final ConfiguredInference incumbentConfiguration;
    final LlamaServerConfigContext candidate;
    final Mode priorMode;
    final long applyStartNanos;
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
      incumbentConfiguration = configured;
      InferenceConfig oldConfig = incumbentConfiguration.inference();
      boolean shouldRestart = switch (effective) {
        case RESTART_ALWAYS -> true;
        case RESTART_IF_ONLINE -> runner.currentMode() == Mode.ONLINE;
        case APPLY_ONLY -> false;
      };
      applyStartNanos = System.nanoTime();
      try {
        events.onConfigApplyAttempt(oldConfig, newConfig, shouldRestart);
      } catch (RuntimeException failure) {
        LOG.warn("Telemetry events.onConfigApplyAttempt threw: {}", failure.getMessage());
      }
      if (shouldRestart && selfTestHeld) {
        String message = "The chat server stays stopped while an activation self-test runs";
        recordApplyRefusal(
            new InferenceFailure.ConfigFailure(ConfigCode.ALREADY_TRANSITIONING, message));
        throw modeTransition(ModeTransitionException.Reason.ALREADY_TRANSITIONING, message);
      }
      if (shouldRestart && serverOps.isExternalServerActive()) {
        String message = "Cannot restart/apply config while using an external llama-server instance on port "
            + servingPort() + ". Stop the external process first or detach to a managed server.";
        recordApplyRefusal(new InferenceFailure.ConfigFailure(ConfigCode.EXTERNAL_SERVER_CONFLICT, message));
        throw modeTransition(ModeTransitionException.Reason.EXTERNAL_SERVER_CONFLICT, message);
      }
      ResolvedConfig resolved = suppliedResolved != null ? suppliedResolved : ConfigStore.global().get();
      candidate = new LlamaServerConfigContext(newConfig, resolved);
      if (!shouldRestart) {
        try {
          newConfig.validate();
        } catch (Exception invalid) {
          String message = "Invalid inference configuration: " + safeMessage(invalid);
          recordApplyRefusal(new InferenceFailure.ConfigFailure(ConfigCode.INVALID_CONFIG, message));
          throw modeTransition(ModeTransitionException.Reason.INVALID_CONFIG, message);
        }
        configured = new ConfiguredInference(newConfig, resolved, adoptionPolicy);
        if (!Objects.equals(newConfig, oldConfig)) lastChatFailedCode = null;
        emitApplyComplete(applyStartNanos);
        return new ConfigApplyResult(ConfigApplyDisposition.CONFIGURED, newConfig, null, null);
      }
      priorMode = runner.currentMode();
    }
    // Spec A §3.5 item 5: the stop, the start and the health wait run in the transition body,
    // without the lifecycle lock; the controller serializes this step with every other actuation.
    // This is an attempt-local return slot, never a published configuration authority.
    ApplyExecution[] execution = new ApplyExecution[1];
    try {
      runner.run(transitionReason, events::onConfigApplyFailure, priorView -> {
        execution[0] = composeCandidate(candidate, adoptionPolicy, incumbentConfiguration,
            priorMode, priorView, applyStartNanos, transitionReason);
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
      InferenceRuntimeView priorView, long applyStarted, TransitionReason transitionReason) {
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
      serverOps.stopLlamaServer(reconfigureStop(transitionReason,
          incumbent != null ? incumbent.context().inference() : incumbentConfiguration.inference(),
          candidate.inference()));
    } catch (RuntimeException stopFailure) {
      return failedApply(ConfigApplyDisposition.LEFT_OFFLINE,
          new InferenceFailure.TransitionFailure(TransitionCode.CONFIG_APPLY_FAILED,
              "Incumbent server stop failed: " + safeMessage(stopFailure), stopFailure), priorView);
    }
    try {
      serverOps.resetHealthFailures();
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
    // The health wait ran without the lifecycle lock; a crash signal in that window found no
    // settled mode to act on, so the exit is caught here instead.
    if (!serverOps.activeManagedCandidateAlive(started)) {
      throw new IllegalStateException("llama-server exited after its health check");
    }
    if (started.disposition() == LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL) {
      if (request.adoptionPolicy() != LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL) {
        throw new IllegalStateException("Managed configuration witness is required");
      }
      serverOps.acceptVerifiedStart(request, started);
      return;
    }
    String expected = ManagedLlamaConfigIdentity.declaredHash(request.context().inference(),
        request.context().resolved(), request.effectiveGpuLayers());
    if (!expected.equals(started.declaredConfigHash())) {
      throw new IllegalStateException("Managed server configuration witness does not match candidate");
    }
    serverOps.acceptVerifiedStart(request, started);
  }

  private ApplyExecution restoreIncumbent(
      LlamaServerOps.StartResult incumbent, ConfiguredInference incumbentConfiguration,
      Mode priorMode, InferenceRuntimeView priorView, LlamaServerOps.AdoptionPolicy policy,
      Exception candidateFailure) {
    ModeTransitionException candidate = asModeTransition(candidateFailure,
        ModeTransitionException.Reason.CONFIG_APPLY_FAILED, "Failed to apply inference config: ");
    configured = incumbentConfiguration;
    try {
      serverOps.stopLlamaServer(chatStop(StopReason.ROLLBACK));
      if (priorMode != Mode.ONLINE) {
        return failedApply(ConfigApplyDisposition.LEFT_OFFLINE,
            TransitionRunner.mapExceptionToFailure(candidate), priorView);
      }
      var restore = new LlamaServerOps.StartRequest(incumbent.context(), policy);
      serverOps.resetHealthFailures();
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
    return actuate(ChatActuation.of(ChatActuation.Kind.DETACH,
        drained -> detachExternalServerStep()));
  }

  private DetachExternalServerResult detachExternalServerStep() throws ModeTransitionException {
    final LlamaServerOps.StartResult incumbent;
    final ConfiguredInference previousConfiguration;
    final InferenceConfig oldConfig;
    final int previousPort;
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

      incumbent = serverOps.activeStartResult().orElseThrow(
          () -> new IllegalStateException("External server has no captured ownership"));
      previousConfiguration = configured;
      oldConfig = incumbent.context().inference();
      previousPort = servingPort();
    }
    // Spec A §3.5 item 5: the managed start and its health wait run without the lifecycle lock.
    final boolean[] succeeded = {false};
    runner.run(
        TransitionReason.EXTERNAL_DETACH,
        events::onStartupFailure,
        priorView -> {
          tokenOps.clearCaches();
          try {
            // The managed server binds its own fresh loopback port (ADR-0051 decision 2), so the
            // configuration is unchanged; only the policy records that the user detached.
            InferenceConfig next = oldConfig;
            next.validate();

            serverOps.stopLlamaServer(stopFor(TransitionReason.EXTERNAL_DETACH));
            serverOps.resetHealthFailures();
            tokenOps.clearCaches();

            // Tempdoc 518 fix A: wipe stale /props from external server before starting
            // the new managed server. The managed server's /props observation (if it
            // fires) repopulates via runner.mergeProps.
            runner.clearProps();
            var request = new LlamaServerOps.StartRequest(
                new LlamaServerConfigContext(next, incumbent.context().resolved()),
                LlamaServerOps.AdoptionPolicy.MANAGED_DETACHED_FROM_EXTERNAL);
            var started = serverOps.startLlamaServer(request);
            serverOps.waitForServerHealth(started);
            verifyAppliedServer(request, started);
            configured = new ConfiguredInference(next, incumbent.context().resolved(),
                LlamaServerOps.AdoptionPolicy.MANAGED_DETACHED_FROM_EXTERNAL);

            LOG.info(
                "Detached from external llama-server on port {} and started managed on port {}",
                previousPort,
                servingPort());
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
    return new DetachExternalServerResult(succeeded[0], previousPort, servingPort());
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

  /** Spec E step E2: a completion under an explicit lease kind (GENERATE, ASSIST or VDU). */
  public CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages,
      int maxTokens,
      SamplingParams sampling,
      io.justsearch.app.api.EngineWorkHandle work,
      GenerationTracker.Kind kind) {
    return onlineOps.chatCompletion(messages, maxTokens, sampling, work, kind);
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

  /** Spec E step E2: a vision completion under an explicit lease kind (GENERATE, ASSIST, VDU). */
  public CompletableFuture<VisionCompletionResult> visionCompletionDetailed(
      String prompt,
      byte[] imageBytes,
      int maxTokens,
      SamplingParams sampling,
      Long seed,
      io.justsearch.app.api.EngineWorkHandle work,
      GenerationTracker.Kind kind) {
    return onlineOps.visionCompletionDetailed(
        prompt, imageBytes, maxTokens, sampling, seed, work, kind);
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

  /**
   * Safe, low-cardinality diagnostics for the user's external server. {@code note} is {@code
   * external_server_ignored} while a healthy server on the configured port is left alone because
   * no external endpoint is set (ADR-0051 decision 2), else null.
   */
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
      int consecutiveHealthFailures,
      String note) {

    /** This snapshot with {@code note} (null = none). */
    public ExternalServerDiagnostics withNote(String note) {
      return new ExternalServerDiagnostics(
          usingExternalLlamaServer, verified, verificationError, modelId, contextTokens,
          modelMismatch, contextTooSmall, adoptedAtMs, lastHealthOkAtMs, lastHealthError,
          consecutiveHealthFailures, note);
    }
  }

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

  // Each token operation holds one AUX lease from the tracker for its whole exchange
  // (TokenEndpointOps takes it before any request).

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
   * No production caller. ADR-0051 decision 2: {@link #close()} always stops the managed
   * llama-server this manager started, for every shutdown reason, because no successor Engine
   * adopts it. A request to leave it running is refused with a WARN and has no effect. Kept so
   * tests can state that refusal (InferenceLifecycleManagerShutdownTest, ApplyConfigTest,
   * PreservedChildTest) and because {@code HeadlessAppFailedDrainBackendTest} still calls it.
   */
  @Deprecated(forRemoval = true)
  public void setStopServerOnClose(boolean stopServerOnClose) {
    if (!stopServerOnClose) {
      LOG.warn(
          "Refusing to leave llama-server running on close: no successor Engine adopts it"
              + " (ADR-0051 decision 2), so close always stops the managed server.");
    }
  }

  /**
   * Always true: close stops the managed server (ADR-0051 decision 2). {@code HeadAssemblyTest}
   * states the rule through it.
   */
  public boolean stopsServerOnClose() {
    return true;
  }

  /**
   * Terminal shutdown may retain Head's dependencies after a refused work drain. Stop only the
   * owned child in that case; do not close HTTP clients, executors or native inference owners still
   * in use. Physical ownership fences further launches and stops every launched child through
   * ChildControl under one deadline, independently of the transition lock, with the reason {@link
   * #recordTerminalStop} recorded (QUIT when none was). The user's external server has no owned
   * handle and retains the same exemption as ordinary close.
   */
  public void stopServerForTerminalShutdown(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Terminal server stop requires a positive timeout");
    }
    // The controller's shutdown is the only path that stops the server at quit: it goes terminal
    // first, refuses later steps and relaunches, then stops through the fence.
    actuationRouter.shutdown(terminalStopFor(null).reason(), timeout);
  }

  /**
   * Closes this manager and releases all resources. Always stops the managed llama-server it
   * started, whatever the shutdown reason (ADR-0051 decision 2): no successor Engine adopts it.
   */
  @Override
  public void close() {
    // The lock-independent physical stop must not skip the ordinary cleanup below: a failed
    // pre-stop (survivor past the deadline, failed exit observation) is carried into the same
    // aggregation as a failed in-lock stop and rethrown only after every resource is closed.
    RuntimeException preStopFailure = null;
    try {
      stopServerForTerminalShutdown(Duration.ofSeconds(12));
    } catch (RuntimeException failure) {
      preStopFailure = failure;
    }
    synchronized (runner.lock()) {
      // A transition body runs without the lock; after the terminal stop it ends quickly. Let it
      // finish before the resources it uses close, as waiting for the lock used to.
      long settleDeadline =
          System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
      try {
        runner.awaitSettledLocked(() -> System.nanoTime() < settleDeadline);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      closed = true;
      LOG.info("Closing InferenceLifecycleManager...");
      RuntimeException terminationFailure = preStopFailure;
      try {
        serverOps.stopLlamaServer(terminalStopFor(null));
      } catch (RuntimeException failure) {
        if (terminationFailure == null) terminationFailure = failure;
        else terminationFailure.addSuppressed(failure);
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
