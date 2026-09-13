/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.AgentRunStore;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.gpl.GplEvalData;
import io.justsearch.app.api.gpl.GplStatusProvider;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.app.config.ConfigSnapshot;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.BootstrapCapabilitiesFactory;
import io.justsearch.app.services.gpl.GplEvalSnapshot;
import io.justsearch.app.services.gpl.GplJobCoordinator;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.WorkerFeatureCache;
import io.justsearch.app.observability.CapabilitiesController;
import io.justsearch.app.observability.CapabilitiesService;
import io.justsearch.app.observability.InfraDiagnosticsService;
import io.justsearch.app.observability.InfraHealthBootstrap;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.search.SearchPort;
import io.justsearch.telemetry.Telemetry;
import com.sun.net.httpserver.HttpHandler;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §10 head-composition bootstrap. Sequences phase functions, holds the typed phase
 * records ({@link io.justsearch.app.services.bootstrap.CapabilityGraph}, {@link
 * io.justsearch.app.api.ServiceGraph}, {@link io.justsearch.app.services.bootstrap.SubstrateGraph},
 * {@link io.justsearch.app.services.bootstrap.OrchestrationHandles}), and exposes them via
 * {@link #core()} / {@link #workers()} / {@link #inference()} / {@link #capabilities()} /
 * {@link #substrate()}. Lifecycle hooks: {@link #connectKnowledgeServer},
 * {@link #close}. §31 Phase 3 dissolved the registerLateBoundHandlers hook.
 */
public final class HeadAssembly implements AutoCloseable {
  private final io.justsearch.app.api.operations.OperationStore operations;
  private final io.justsearch.app.api.operations.OperationAttemptRunner attempts;
  public io.justsearch.app.api.operations.OperationAttemptRunner operationAttempts() { return attempts; }

  /** Borrowed process-lifetime store; this assembly never closes it. */
  public io.justsearch.app.api.operations.OperationStore operations() { return operations; }

  private static final Logger log = LoggerFactory.getLogger(HeadAssembly.class);

  // §10 endpoint: bootstrap holds typed phase records (capabilities/services/substrateGraph/
  // orchestration) + 4 substrate Outputs (healthOut/operationOut/resourceOut/metricsOut) + a
  // few cross-phase intermediates (Worker connection state, inference handles, pre-substrate
  // operation registry, GPL/LambdaMART, indexing-jobs bridge).
  private volatile SearchPort searchPort;
  private volatile KnowledgeClient knowledgeClient;
  private volatile KnowledgeServerBootstrap knowledgeServerBootstrap;
  // §31 Phase 3: LateBoundServices DELETED. The 7 controller-services are constructed by
  // ServicePhase and held via this.serviceOut. The 3 controller back-refs (settings reset
  // callback, DebugStateProvider, StatusSnapshotProvider) flow through this.lateBindings.
  private volatile io.justsearch.app.api.ServiceGraph services;
  private final io.justsearch.app.services.bootstrap.CapabilityGraph capabilities;
  private io.justsearch.app.services.bootstrap.SubstrateGraph substrateGraph;
  private final InferenceLifecycleManager inferenceManager;
  // Tempdoc 518 Wave A-E defect Fix-3 (ported from main commits 17545ad2a + 3a5355216) —
  // async transition-log decorator. Kept off the TransitionRunner's transition lock so
  // sidecar I/O does not add user-visible transition latency. Closed during teardown.
  private io.justsearch.app.inference.AsyncInferenceTransitionLog asyncTransitionLog;
  private final io.justsearch.app.api.ModeChangeListener gpuBroadcastListener;
  // Tempdoc 737 Phase 1 — the single-writer runtime authority; closed on teardown with the manager.
  private final io.justsearch.app.services.runtimestate.RuntimeReconciler runtimeReconciler;
  // §10 Phase A: InfraPhase.Output holds the capabilities handler. Item A14 removed the second
  // Netty server it used to hold alongside it (the infra-health gRPC endpoint).
  private io.justsearch.app.services.bootstrap.phases.InfraPhase.Output infraOut;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final List<io.justsearch.app.services.encryption.UnlockDeferredScan> unlockScans =
      new java.util.ArrayList<>();
  private final io.justsearch.app.services.vdu.OfflineCoordinator offlineCoordinator;
  private final Telemetry telemetry;
  private final io.justsearch.core.execution.EngineExecutorRegistry executors;
  private final io.justsearch.core.execution.EngineExecutorRegistry.Registration foregroundDocumentOwner;
  private final io.justsearch.core.execution.EngineExecutorRegistry.Registration backgroundDocumentOwner;
  private final java.util.concurrent.ExecutorService foregroundDocuments;
  private final java.util.concurrent.ExecutorService backgroundDocuments;
  private final AutoCloseable operationsRetentionTimer;


  public io.justsearch.core.execution.EngineExecutorRegistry executors() { return executors; }
  private final io.justsearch.app.services.worker.SearchPerSourceExecutor perSourceSearch;
  public io.justsearch.app.services.worker.SearchPerSourceExecutor perSourceSearch() {
    return perSourceSearch; // Absent only in the search-port-only fixture.
  }
  private final KnowledgeHttpApiAdapter agentSearchAdapter;
  // Tempdoc 913 D5: the ONE file-operation journal for the process. Held for the same reason
  // agentSearchAdapter is — connectKnowledgeServer hands it to AgentToolHandlers.registerLateBound
  // so the write side (FileOperationsTool) and the read side (AgentRunQueryService.operationHistory
  // → GET /api/chat/agent/history) are the same instance over the same directory.
  private final FileOperationLog fileOperationLog;
  // Tempdoc 832 (lane D): published by LocalApiServer (its owner) before connectKnowledgeServer, so
  // the late-bound agent ingest adapter can be bound to the same scan-progress stream.
  private volatile io.justsearch.app.services.worker.ScanProgressRegistry scanProgressRegistry;
  // §31 Step 1.1: ExcludesService constructed by ServicePhase (first dissolution of LateBoundServices).
  private final io.justsearch.app.api.ExcludesService excludes;
  // §31 Phase 3: ServicePhase output held to feed assembleServiceGraph + expose helpers to
  // LocalApiServer (which now consumes services + helpers from the bootstrap instead of
  // constructing them itself).
  private final io.justsearch.app.services.bootstrap.phases.ServicePhase.Output serviceOut;
  private final java.util.function.Function<String, String> operationMessageResolver;
  // §10 Phase B: SubstratePhase.Output holds operationHandlers + 2 catalogs + 4 substrate
  // Output records + indexing-jobs bridge + ruleRunner. Replaces 10 prior flat fields.
  private io.justsearch.app.services.bootstrap.phases.SubstratePhase.Output substrateOut;
  private volatile io.justsearch.app.services.bootstrap.OrchestrationHandles orchestration;
  private final LambdaMartReranker lambdaMartReranker = new LambdaMartReranker();
  private final Path lambdaMartModelFile;
  private final GplJobCoordinator gplJobCoordinator;
  private final Path gplSnapshotFile;
  private Path dataDir;
  // Tempdoc 561 P-E: the single-authority learned-memory record, shared by the core_remember agent
  // tool and the /api/memory surface.
  private final io.justsearch.agent.api.memory.MemoryStore memoryStore;
  // Tempdoc 565 §15.C — the durable agent run store, exposed so the workflow runner shares its
  // shape-agnostic RunEventStore (one run-event space → the unified thread projects workflow runs too).
  private AgentRunStore agentRunStore;
  // Tempdoc 629 (#E faithful import): held so the agent-run BackupSink can re-index a RESTORED run into
  // the searchable agent-history collection (import doesn't fire the live listener).
  private io.justsearch.app.services.agenthistory.AgentHistoryIndexer agentHistoryIndexer;

  /**
   * Tempdoc 909 item 1 — the number of persisted runs one agent-history reconciliation pass
   * considers. Matches {@code AgentRunReconciler.SCAN_LIMIT}: far above any plausible run count,
   * and {@code listSessions} clamps below it. Note this is a LISTING bound, not a work bound —
   * the per-run cost of a pass is recorded on {@code AgentHistoryIndexer.MAX_REBUILDS_PER_PASS}.
   */
  private static final int AGENT_HISTORY_SCAN_LIMIT = 100_000;
  // Tempdoc 778 — the default-on local feedback-capture flag, shared by every capture site + the
  // /api/feedback/capture surface.
  private io.justsearch.app.services.feedback.FeedbackCaptureSettings feedbackCaptureSettings;

  /** Tempdoc 629 (LAYER): the data-at-rest key manager (owns the DEK lifecycle for AUTHORED stores). */
  private final io.justsearch.app.services.encryption.DataKeyManager dataKeyManager;
  private volatile AutoCloseable gplAutoTrigger;

  // §31 Phase 2 — late-bindings holder for SettingsController::resetToDefaults +
  // DebugStateProvider SPI + StatusSnapshotProvider SPI. Set by LocalApiServer after it
  // constructs the relevant controllers.
  private final io.justsearch.app.services.bootstrap.BootstrapLateBindings lateBindings =
      new io.justsearch.app.services.bootstrap.BootstrapLateBindings();

  // Tempdoc 541 §4.2: immutable boot-trace snapshot. Accumulated during construction by
  // BootTrace.Builder; sealed once at exit. The held volatile reference flips from null →
  // builder-snapshot → sealed; readers see whichever is current.
  private final io.justsearch.app.services.bootstrap.BootTrace.Builder bootTraceBuilder =
      new io.justsearch.app.services.bootstrap.BootTrace.Builder(
          io.justsearch.app.services.bootstrap.BootTrace.HEAD);

  // Tempdoc 541 §4.2: OTel tracer for composition.boot (parent) + composition.phase.<name>
  // (children) spans. Active when HEAD_TRACING_LEVEL is on; no-op otherwise.
  private static final io.opentelemetry.api.trace.Tracer COMPOSITION_TRACER =
      io.opentelemetry.api.GlobalOpenTelemetry.getTracer("io.justsearch.head.composition");

  /**
   * Tempdoc 541 §4.2 + fix-pass D.1: PhaseOutcome-aware unified phase wrapper.
   *
   * <p>Wraps a phase invocation in (timing, OTel span, BootTrace.record) and routes the
   * sealed-sum outcome into the appropriate {@link io.justsearch.app.services.bootstrap.PhaseRecord}
   * factory:
   *
   * <ul>
   *   <li>{@code Ready} → {@code PhaseRecord.ready(...)}.
   *   <li>{@code Degraded} → {@code PhaseRecord.degraded(..., comma-joined-reasons, ...)}.
   *   <li>{@code Failed} → {@code PhaseRecord.failed(..., comma-joined-reasonCodes, ...)}.
   *   <li>An uncaught RuntimeException from the body itself → {@code PhaseRecord.failed(...)}
   *       with the exception class simple-name as the reason code, then re-thrown.
   * </ul>
   *
   * <p>The span ID is captured into every PhaseRecord so the FE consumer / 539 cold-start
   * profiler can correlate the trace entry with the OTel span. Best-effort: tracing
   * infrastructure failure does not propagate.
   */
  private <T> io.justsearch.app.services.bootstrap.PhaseOutcome<T> tracedPhase(
      String phaseName,
      Supplier<io.justsearch.app.services.bootstrap.PhaseOutcome<T>> body) {
    io.opentelemetry.api.trace.Span span =
        COMPOSITION_TRACER.spanBuilder("composition.phase." + phaseName).startSpan();
    String spanId = span.getSpanContext().getSpanId();
    long t0 = System.currentTimeMillis();
    try (io.opentelemetry.context.Scope scope = span.makeCurrent()) { // NOPMD - scope used for auto-close
      io.justsearch.app.services.bootstrap.PhaseOutcome<T> outcome = body.get();
      long t1 = System.currentTimeMillis();
      io.justsearch.app.services.bootstrap.PhaseRecord record =
          switch (outcome) {
            case io.justsearch.app.services.bootstrap.PhaseOutcome.Ready<T> r ->
                io.justsearch.app.services.bootstrap.PhaseRecord.ready(phaseName, t0, t1, spanId);
            case io.justsearch.app.services.bootstrap.PhaseOutcome.Degraded<T> d ->
                io.justsearch.app.services.bootstrap.PhaseRecord.degraded(
                    phaseName, t0, t1, String.join(",", d.reasons()), spanId);
            case io.justsearch.app.services.bootstrap.PhaseOutcome.Failed<T> f -> {
              span.recordException(f.cause());
              yield io.justsearch.app.services.bootstrap.PhaseRecord.failed(
                  phaseName,
                  t0,
                  t1,
                  f.reasonCodes().isEmpty()
                      ? f.cause().getClass().getSimpleName()
                      : String.join(",", f.reasonCodes()),
                  spanId);
            }
          };
      bootTraceBuilder.record(record);
      return outcome;
    } catch (RuntimeException e) {
      span.recordException(e);
      bootTraceBuilder.record(
          io.justsearch.app.services.bootstrap.PhaseRecord.failed(
              phaseName,
              t0,
              System.currentTimeMillis(),
              e.getClass().getSimpleName(),
              spanId));
      throw e;
    } finally {
      span.end();
    }
  }

  // Fix-pass Tier 5: tracedPhaseEager removed — all 5 phases now ship runWithOutcome and
  // call sites use tracedPhase directly. The adapter was a transitional shim per D.1.

  /** §31 Phase 2 — LocalApiServer publishes controller-derived refs into here. */
  public io.justsearch.app.services.bootstrap.BootstrapLateBindings lateBindings() {
    return lateBindings;
  }

  /**
   * Tempdoc 541 §4.2: returns the current boot trace (sealed-or-in-progress). Immutable
   * snapshot — safe to expose to the HTTP layer without copy.
   */
  public io.justsearch.app.services.bootstrap.BootTrace bootTrace() {
    return bootTraceBuilder.snapshot();
  }

  // Tempdoc 541 §5.1 — Brain composition root co-resident with Head. Holds the
  // ILM-wrapping BrainAssembly that exposes a process=brain BootTrace view of ILM
  // construction window. Fix-pass E.2: final (was volatile); assigned once in each
  // constructor. The primary constructor projects from the service-phase window; the
  // test-only secondary constructor (bootForSearchPortOnly) uses an empty projection.
  private final BrainAssembly brainAssembly;

  // Tempdoc 541 §5.2 + fix-pass A.1: Memoized agent-tool late-bind registration. The body
  // invokes AgentToolHandlers.registerLateBound at first .get(); idempotent thereafter.
  // Triggered by connectKnowledgeServer when Worker becomes available. Set in the primary
  // constructor; the secondary test constructor uses a no-op resolved instance.
  private final io.justsearch.app.services.bootstrap.Memoized<Boolean> agentToolsRegistration;

  /**
   * Tempdoc 541 fix-pass A.1: returns the agent-tool late-bind Memoized. Callers use {@code
   * .isResolved()} to check whether registration has fired, and {@code .get()} to force
   * resolution (idempotent thereafter). BootRoutes inspects this to render the
   * agent-tools-registration entry's current state in the /api/boot/phases envelope.
   */
  public io.justsearch.app.services.bootstrap.Memoized<Boolean> agentToolsRegistration() {
    return agentToolsRegistration;
  }

  /**
   * Tempdoc 541 fix-pass Tier 4 (C-revised): bounded ring buffer of rebuild events recorded
   * after the initial sealed BootTrace. Each {@code connectKnowledgeServer} invocation adds
   * a record with timing + outcome. BootRoutes exposes the snapshot via the {@code rebuilds}
   * field of the {@code /api/boot/phases} envelope.
   */
  private final io.justsearch.app.services.bootstrap.RebuildHistory rebuildHistory =
      new io.justsearch.app.services.bootstrap.RebuildHistory();

  /** Returns the RebuildHistory ring buffer; defensive snapshot via {@code .snapshot()}. */
  public io.justsearch.app.services.bootstrap.RebuildHistory rebuildHistory() {
    return rebuildHistory;
  }

  /**
   * Tempdoc 541 §5.1: returns the Brain composition root (wrap-not-decompose variant per
   * §9.1 C2). Available after ServicePhase completes; null before then.
   */
  public BrainAssembly brainAssembly() {
    return brainAssembly;
  }

  /**
   * §31 Phase 4: expose the held ServicePhase.Output so LocalApiServer can read helpers
   * (AiInstallService, AiPackImportService, RuntimeActivationService, EnterprisePolicyService,
   * GpuCapabilitiesService, PackAllowlistService) without constructing its own duplicates.
   * The 7 wrapper services are still accessible via {@code workers()/core()/inference()}.
   */
  public io.justsearch.app.services.bootstrap.phases.ServicePhase.Output serviceOut() {
    return serviceOut;
  }

  /**
   * Configures whether closing this assembly also stops its managed generative backend.
   *
   * <p>The ordered Engine shutdown calls this immediately before {@link #close()}. Restart and
   * hang shutdowns leave llama-server alive for adoption; quit and upgrade shutdowns stop it.
   */
  public void setStopGenerativeBackendOnClose(boolean stop) {
    if (inferenceManager != null) {
      inferenceManager.setStopServerOnClose(stop);
    }
  }

  /**
   * Constructs the head with the one Engine admission owner shared by the API and agent paths.
   * The separate parameter keeps the existing operation-lease compatibility seam while making
   * the admission dependency explicit at the composition root.
   */
  public HeadAssembly(
      io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      Telemetry telemetry,
      ConfigManagerBootstrap configManager,
      KnowledgeServerBootstrap knowledgeServer,
      io.justsearch.app.services.settings.UiSettingsStore settingsStore,
      io.justsearch.app.services.lifecycle.WorkerCapability sharedWorkerCapability,
      io.justsearch.app.api.runtime.ManagedChildRegistry managedChildRegistry,
      io.justsearch.app.api.OperationLeaseService operationLeases,
      io.justsearch.app.api.EngineAdmissionService engineAdmission) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    Objects.requireNonNull(telemetry, "telemetry");
    Objects.requireNonNull(engineAdmission, "engineAdmission");
    List<AutoCloseable> acquiredOwners = new java.util.ArrayList<>();
    try {
    this.perSourceSearch = new io.justsearch.app.services.worker.SearchPerSourceExecutor(executors, engineAdmission);
    acquiredOwners.add(perSourceSearch);
    this.executors = Objects.requireNonNull(executors, "executors");
    this.foregroundDocumentOwner = documentExecutorOwner(executors,
        io.justsearch.core.execution.EngineExecutorSpec.Kind.FOREGROUND, "foreground");
    acquiredOwners.add(foregroundDocumentOwner);
    this.backgroundDocumentOwner = documentExecutorOwner(executors,
        io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND, "background");
    acquiredOwners.add(backgroundDocumentOwner);
    this.foregroundDocuments = foregroundDocumentOwner.open(
        Thread.ofPlatform().daemon().name("head-documents-foreground-", 0).factory());
    this.backgroundDocuments = backgroundDocumentOwner.open(
        Thread.ofPlatform().daemon().name("head-documents-background-", 0).factory());
    this.operationsRetentionTimer = startOperationsRetentionTimer(this.operations, this.executors);
    acquiredOwners.add(this.operationsRetentionTimer);
    this.telemetry = telemetry;
    Objects.requireNonNull(managedChildRegistry, "managedChildRegistry");
    Objects.requireNonNull(configManager, "configManager");
    ConfigSnapshot snapshot = configManager.currentSnapshot();
    ResolvedConfig rc = ConfigStore.global().get();
    Path dataDir = rc.paths().dataDir() != null
        ? rc.paths().dataDir() : PlatformPaths.resolveDataDir();
    this.dataDir = dataDir;
    // Tempdoc 629 (LAYER): the at-rest key manager, backed by <dataDir>/encryption/keystore.json.
    // Starts NOT_CONFIGURED (no keystore) or LOCKED (keystore present) — never auto-unlocks.
    this.dataKeyManager =
        new io.justsearch.app.services.encryption.DataKeyManager(
            new io.justsearch.app.services.encryption.EncryptionKeystore(dataDir));
    // Tempdoc 541 §4.2: capture per-phase boundaries via tracedPhase helper — OTel span +
    // BootTrace record. Spans appear in traces.ndjson when HEAD_TRACING_LEVEL is on;
    // no-op otherwise.
    this.infraOut =
        tracedPhase(
                "infra",
                () ->
                    io.justsearch.app.services.bootstrap.phases.InfraPhase.runWithOutcome(
                        rc,
                        snapshot,
                        configManager,
                        () ->
                            this.substrateOut == null
                                ? 0L
                                : this.substrateOut
                                    .operationOut()
                                    .capabilitiesChangeRegistry()
                                    .currentSeq()))
            .orThrow();

    if (knowledgeServer != null) {
      this.knowledgeClient = knowledgeServer.client();
      this.knowledgeServerBootstrap = knowledgeServer;
    } else {
      this.knowledgeClient = null;
    }
    IndexingService indexingService = this.knowledgeClient;
    DocumentService documentService =
        io.justsearch.app.services.bootstrap.phases.BootstrapDocumentService.create(
            foregroundDocuments, backgroundDocuments, () -> this.knowledgeClient, telemetry);
    this.searchPort =
        this.knowledgeClient != null
            ? this.knowledgeClient
            : new io.justsearch.app.services.bootstrap.phases.NoopSearchPort();

    // §4 Phase 2 — CapabilityPhase first (F3 reorder); mode-change listener attached in
    // ServicePhase after the manager exists.
    boolean inferenceConfigured =
        io.justsearch.app.services.bootstrap.phases.InferenceDecision.decideInferenceConfigured();
    // Tempdoc 541 §5.3 + fix-pass D.1 — CapabilityPhase uses the unified PhaseOutcome-aware
    // tracedPhase helper; Ready/Degraded/Failed mapping happens inside the helper so the
    // call site stays concise.
    final KnowledgeServerBootstrap knowledgeServerForCapability = knowledgeServer;
    final boolean inferenceConfiguredFinal = inferenceConfigured;
    final io.justsearch.app.services.lifecycle.WorkerCapability sharedWorkerCapabilityFinal =
        sharedWorkerCapability;
    this.capabilities =
        tracedPhase(
                "capability",
                () ->
                    io.justsearch.app.services.bootstrap.phases.CapabilityPhase.runWithOutcome(
                        knowledgeServerForCapability,
                        inferenceConfiguredFinal,
                        sharedWorkerCapabilityFinal))
            .orThrow();

    // §4 Phase 3 — ServicePhase.
    InferenceLifecycleManager manager =
        inferenceConfigured
            ? io.justsearch.app.services.bootstrap.phases.InferenceDecision.createInferenceManager(
                executors, telemetry, managedChildRegistry)
            : null;
    this.inferenceManager = manager;
    // Tempdoc 518 Wave B + Slice 2 (ported from main 17545ad2a + 3a5355216) — install the
    // persistent transition sidecar at the composition root. Wrap NdjsonInferenceTransitionLog
    // in AsyncInferenceTransitionLog so disk I/O does NOT happen under TransitionRunner's
    // transition lock. Best-effort: a sidecar failure does not block ILM construction.
    if (manager != null) {
      try {
        this.asyncTransitionLog =
            new io.justsearch.app.inference.AsyncInferenceTransitionLog(
                executors,
                new io.justsearch.app.inference.NdjsonInferenceTransitionLog(getJustSearchHome()));
        manager.installTransitionLog(this.asyncTransitionLog);
      } catch (Exception e) {
        log.warn("Failed to install InferenceTransitionLog (best-effort): {}", e.getMessage());
      }
    }
    final KnowledgeServerBootstrap knowledgeServerForService = knowledgeServer;
    final IndexingService indexingServiceFinal = indexingService;
    final DocumentService documentServiceFinal = documentService;
    final InferenceLifecycleManager managerFinal = manager;
    final io.justsearch.app.services.settings.UiSettingsStore settingsStoreFinal = settingsStore;
    // Tempdoc 541 §5.1: capture the service-phase window so BrainAssembly can project
    // ILM-construction timestamps without re-running anything.
    long t_service_0 = System.currentTimeMillis();
    var serviceOut =
        tracedPhase(
                "service",
                () ->
                    io.justsearch.app.services.bootstrap.phases.ServicePhase.runWithOutcome(
                        new io.justsearch.app.services.bootstrap.phases.ServicePhase.Input(
                            executors,
                            engineAdmission,
                            this.perSourceSearch,
                            knowledgeServerForService,
                            this.knowledgeClient,
                            indexingServiceFinal,
                            () -> this.knowledgeClient,
                            documentServiceFinal,
                            this.lambdaMartReranker,
                            telemetry,
                            dataDir,
                            managerFinal,
                            this.capabilities.inference(),
                            settingsStoreFinal,
                            this.lateBindings,
                            () -> this.knowledgeClient,
                            this::currentKnowledgeServer, operationLeases)))
            .orThrow();
    long t_service_1 = System.currentTimeMillis();
    this.serviceOut = serviceOut;
    if (serviceOut.offlineCoordinator() != null) acquiredOwners.add(serviceOut.offlineCoordinator());
    // Tempdoc 541 §5.1 — project Brain composition root from the service phase window. ILM
    // is constructed earlier (just above, into `manager`); BrainAssembly wraps it as the
    // single Phase Output of process=brain.
    this.brainAssembly =
        BrainAssembly.project(this.inferenceManager, t_service_0, t_service_1);
    OnlineAiService onlineAiService = serviceOut.onlineAiService();
    this.gpuBroadcastListener = serviceOut.inferenceRuntimeHandles().gpuBroadcastListener();
    this.runtimeReconciler = serviceOut.inferenceRuntimeHandles().runtimeReconciler();
    this.offlineCoordinator = serviceOut.offlineCoordinator();
    this.agentSearchAdapter = serviceOut.agentTools().agentSearchAdapter();
    this.excludes = serviceOut.excludes();
    this.fileOperationLog = serviceOut.agentTools().fileOperationLog();

    // Tempdoc 629 (LAYER): seal agent-run meta.json + events.ndjson with the data key (lazy reads → no
    // reload-listener needed; while locked the ledger is empty until unlock).
    Path agentRunsPath = dataDir.resolve("agent-runs");
    AgentRunStore agentRunStore =
        new AgentRunStore(
            agentRunsPath,
            // 629 (#1): the cipher is a projection of the catalog's class, not a hardcoded AUTHORED literal.
            storeCipher(io.justsearch.agent.api.encryption.StoreCatalog.AGENT_RUNS.recoverability()));
    this.agentRunStore = agentRunStore;
    // 629 (#1): register the AUTHORED descriptor — its read-side enumerates each run's meta + event ledger.
    registerAuthoredStore(
        new io.justsearch.agent.api.encryption.StoreDescriptor(
            io.justsearch.agent.api.encryption.StoreCatalog.AGENT_RUNS,
            agentRunsPath,
            () -> {
              var out = new java.util.ArrayList<Map<String, Object>>();
              for (var summary : agentRunStore.listSessions(100_000)) {
                String sid = (String) summary.get("sessionId");
                if (sid == null) {
                  continue;
                }
                var run = new java.util.LinkedHashMap<String, Object>();
                run.put("sessionId", sid);
                run.put("snapshot", agentRunStore.readSnapshot(sid));
                run.put("events", agentRunStore.runEvents().readEvents(sid));
                out.add(run);
              }
              return out;
            },
            // Import sink (629 #E faithful): restore each run's META (so it re-appears in the Sessions
            // list) AND replay its FULL event ledger verbatim via appendRawEvents (preserving original
            // timestamps) — no longer meta-only. Skip-existing by session, so a re-import never
            // double-appends.
            entries -> {
              var existing = new java.util.HashSet<String>();
              for (var s : agentRunStore.listSessions(100_000)) {
                Object id = s.get("sessionId");
                if (id != null) {
                  existing.add(id.toString());
                }
              }
              int restored = 0;
              for (var run : entries) {
                Object sid = run.get("sessionId");
                if (sid == null || existing.contains(sid.toString())) {
                  continue;
                }
                var meta = new java.util.LinkedHashMap<String, Object>();
                if (run.get("snapshot") instanceof Map<?, ?> raw) {
                  for (var e : raw.entrySet()) {
                    meta.put(String.valueOf(e.getKey()), e.getValue());
                  }
                }
                var events =
                    run.get("events") instanceof List<?> list ? list : List.of();
                if (!meta.isEmpty()) {
                  agentRunStore.runEvents().writeRunMeta(sid.toString(), meta);
                }
                if (!events.isEmpty()) {
                  agentRunStore.runEvents().appendRawEvents(sid.toString(), events);
                  // 629 (#E): make the restored run SEARCHABLE — replay its terminal event into the
                  // agent-history index (import doesn't fire the live listener). Fail-soft, off-hot-path.
                  if (agentHistoryIndexer != null) {
                    agentHistoryIndexer.reindexRestoredRun(sid.toString(), events);
                  }
                }
                if (!meta.isEmpty() || !events.isEmpty()) {
                  restored++;
                }
              }
              return restored;
            }));

    // Tempdoc 834 §5.2 — a run the previous process left mid-flight still reads as live forever
    // (nothing recomputes `resumable` across a restart). Stamp those runs now...
    var agentRunReconciler = new io.justsearch.agent.AgentRunReconciler(agentRunStore);
    agentRunReconciler.reconcile();
    // ...and AGAIN on unlock, because with at-rest encryption on and the store locked, readMeta
    // returns null and the boot pass above is a silent no-op on exactly the encrypted installs
    // (834 R5). UnlockDeferredScan owns the two DataKeyManager constraints — listeners run under
    // the key monitor, and `fire` swallows their throws — so the scan lands off-monitor and cannot
    // vanish. The pass is idempotent, so boot + unlock + re-unlock are all safe.
    unlockScans.add(new io.justsearch.app.services.encryption.UnlockDeferredScan(
            executors, "agent-run-reconciler", agentRunReconciler::reconcile)
        .attachTo(this.dataKeyManager));

    // §4 Phase 4 — SubstratePhase: composes operation registry + catalogs + resource/metric/
    // operation/health substrate init + indexing-jobs bridge + rule runner.
    final io.justsearch.app.services.bootstrap.phases.AgentToolFactory.Output agentToolsFinal =
        serviceOut.agentTools();
    this.substrateOut =
        tracedPhase(
                "substrate",
                () ->
                    io.justsearch.app.services.bootstrap.phases.SubstratePhase.runWithOutcome(
                        attempts, engineAdmission, executors,
            telemetry,
            () -> this.knowledgeServerBootstrap,
            () -> this.knowledgeClient,
            () -> this.services.worker().indexing(),
            () -> this.excludes,
            () -> this.serviceOut.settings(),
            () -> this.serviceOut.diagnostics(),
            () -> this.serviceOut.brainRuntime(),
            () -> this.serviceOut.runtimeVariant(),
            () -> this.serviceOut.packImport(),
            () -> this.serviceOut.brainInstall(),
            () -> this.serviceOut.policy(),
            // Tempdoc 737 §12b: runtime-authority handles for the chat-enabled intent write.
            () ->
                this.serviceOut.inferenceRuntimeHandles() == null
                    ? null
                    : this.serviceOut.inferenceRuntimeHandles().runtimeSpecStore(),
            () -> this.runtimeReconciler,
                    agentToolsFinal,
                    buildCapabilityResolver(),
                    this.serviceOut.operationLeaseService(),
                    // MCP-host servers (tempdoc 560 §6): resolved by the config authority here at
                    // the allowlisted entrypoint, parsed downstream (app-services may not read env).
                    io.justsearch.app.services.mcphost.McpHostConfig.fromPath(
                        io.justsearch.configuration.EnvRegistry.MCP_HOST_CONFIG.getPath()),
                    new io.justsearch.agent.api.encryption.StoreCipher(this.dataKeyManager)))
            .orThrow();

    // Tempdoc 560 Phase 1 — wire the host LLM as the MCP sampling answerer (an external MCP server
    // may ask the host to run a completion). Set post-substrate now that OnlineAiService is in hand.
    if (this.substrateOut.mcpHostService() != null) {
      final OnlineAiService mcpSamplerAi = onlineAiService;
      this.substrateOut
          .mcpHostService()
          .setSampler(io.justsearch.app.services.mcphost.McpSamplingAdapter.of(() -> mcpSamplerAi));
    }

    java.util.Properties registryMessages =
        io.justsearch.app.services.bootstrap.phases.BootstrapHelpers.loadRegistryMessages();
    this.operationMessageResolver = key -> registryMessages.getProperty(key, key);

    java.util.function.Function<EngineContext, List<String>> agentRootPaths =
        this.knowledgeClient != null
            ? engineContext ->
                this.services.worker().indexing().getWatchedRoots(engineContext).stream()
                    .map(r -> r.path().toAbsolutePath().normalize().toString())
                    .toList()
            : null;

    // Tempdoc 561 P-A/P-B: the agent loop's tool activity projects into the ONE action ledger from
    // the durable AgentRunStore record (the unified thread's source) — NOT a parallel operation-path
    // write (that fan-in is suppressed for AGENT_LOOP in OperationSubstrateInit). So the thread, agent
    // History, and workspace Timeline all derive from AgentRunStore and cannot structurally disagree.
    if (this.substrateOut != null && this.substrateOut.operationOut() != null) {
      var agentRunLedgerProjector =
          new io.justsearch.app.observability.ledger.AgentRunLedgerProjector(
              this.substrateOut.operationOut().actionLedgerChangeRegistry());
      agentRunStore.addEventListener(agentRunLedgerProjector::onEvent);
    }

    // Tempdoc 778 — the AUTHORED feedback-store cipher (a projection of the catalog class, not a
    // hardcoded literal). ONE key shared by every feedback writer/reader (agent contributor, search
    // controller, the LambdaMART label rebuild) so the F-021 join survives at-rest encryption.
    io.justsearch.agent.api.encryption.StoreCipher feedbackCipher =
        storeCipher(io.justsearch.agent.api.encryption.StoreCatalog.FEEDBACK.recoverability());
    Path feedbackDir =
        PlatformPaths.resolveDataDir().resolve("feedback");
    // Tempdoc 778 — the default-on local capture flag (loopback-privacy: nothing captured leaves the
    // machine, and the user can turn even local capture off).
    this.feedbackCaptureSettings =
        new io.justsearch.app.services.feedback.FeedbackCaptureSettings(
            PlatformPaths.resolveDataDir());

    // Tempdoc 580 §17 P4 — the agent-citation contributor: each agent answer's grounding sources +
    // citations project to the ONE canonical disposition stream (CITED/SHOWN). Best-effort.
    if (agentRunStore != null) {
      io.justsearch.app.services.feedback.AgentDispositionWiring.register(
          agentRunStore::addEventListener,
          PlatformPaths.resolveDataDir(),
          feedbackCipher,
          this.feedbackCaptureSettings);
    }

    // Tempdoc 778 — enroll the AUTHORED feedback store in the encrypted backup/restore path. The
    // BackupSource reads the two captured ndjson streams (sealed, decrypted while unlocked); the sink
    // restores them verbatim. The derived real-feedback-triples.ndjson is NOT enrolled — it rebuilds
    // from these two inputs (FeedbackLabels.rebuild), so it is recoverable without a backup copy.
    registerAuthoredStore(
        new io.justsearch.agent.api.encryption.StoreDescriptor(
            io.justsearch.agent.api.encryption.StoreCatalog.FEEDBACK,
            feedbackDir,
            () -> {
              var out = new java.util.ArrayList<Map<String, Object>>();
              readFeedbackFile(
                  feedbackDir.resolve("result-dispositions.ndjson"),
                  io.justsearch.app.services.feedback.ResultDisposition.class,
                  feedbackCipher,
                  "result-dispositions.ndjson",
                  out);
              readFeedbackFile(
                  feedbackDir.resolve("feature-snapshots.ndjson"),
                  io.justsearch.app.services.feedback.FeatureSnapshot.class,
                  feedbackCipher,
                  "feature-snapshots.ndjson",
                  out);
              return out;
            },
            entries -> restoreFeedbackEntries(feedbackDir, feedbackCipher, entries)));

    // Tempdoc 585 §D Phase 4 (D4a) — index each finished run's transcript into the dedicated
    // agent-history collection (off the hot path + fail-soft), so the user can later search it.
    if (agentRunStore != null) {
      this.agentHistoryIndexer =
          io.justsearch.app.services.agenthistory.AgentHistoryIndexer.register(
              executors,
              agentRunStore::addEventListener,
              dataDir.resolve("agent-history"),
              () -> this.knowledgeClient);
      // Tempdoc 909 item 1 — a transcript is a DERIVED projection of the run's terminal event, but
      // nothing re-derived one outside faithful backup import: a transcript lost to a torn write or
      // a partial restore stayed un-searchable forever. Re-derive the missing/unreadable ones at
      // boot, and AGAIN on unlock, because a locked store lists no sessions at all (the same 834 R5
      // trap AgentRunReconciler names just above). The pass is idempotent and runs off the boot
      // thread.
      final AgentRunStore runStoreForHistory = agentRunStore;
      final io.justsearch.app.services.agenthistory.AgentHistoryIndexer historyIndexer =
          this.agentHistoryIndexer;
      Runnable reindexMissingTranscripts =
          () ->
              historyIndexer.reconcile(
                  () ->
                      runStoreForHistory.listSessions(AGENT_HISTORY_SCAN_LIMIT).stream()
                          .map(summary -> summary.get("sessionId"))
                          .filter(String.class::isInstance)
                          .map(String.class::cast)
                          .toList(),
                  sessionId -> runStoreForHistory.runEvents().readEvents(sessionId));
      reindexMissingTranscripts.run();
      unlockScans.add(new io.justsearch.app.services.encryption.UnlockDeferredScan(
              executors, "agent-history-reconciler", reindexMissingTranscripts)
          .attachTo(this.dataKeyManager));
    }

    // §4 Phase 5 — OrchestrationPhase: composes CapabilityHealthBridge + AgentLoopWiring +
    // initial ServiceGraph + GPL orchestration + LambdaMART load + OrchestrationHandles.
    final OnlineAiService onlineAiServiceFinal = onlineAiService;
    final IndexingService indexingForOrchestration = indexingService;
    final DocumentService documentForOrchestration = documentService;
    final FileOperationLog fileOperationLogFinal = this.fileOperationLog;
    final java.util.function.Function<EngineContext, List<String>> agentRootPathsFinal = agentRootPaths;
    var orchestrationOut =
        tracedPhase(
                "orchestration",
                () ->
                    io.justsearch.app.services.bootstrap.phases.OrchestrationPhase.runWithOutcome(
                    new io.justsearch.app.services.bootstrap.phases.OrchestrationPhase.Input(
                        executors,
                        dataDir,
                        telemetry,
                        () -> this.searchPort,
                        this.inferenceManager,
                        onlineAiServiceFinal,
                        () -> this.knowledgeClient,
                        this.agentSearchAdapter,
                        this.lambdaMartReranker,
                        indexingForOrchestration,
                        documentForOrchestration,
                        this.gpuBroadcastListener,
                        this.substrateOut,
                        this.capabilities,
                        this.operationMessageResolver,
                        engineAdmission,
                        fileOperationLogFinal,
                        agentRunStore,
                        agentRootPathsFinal,
                        this::startLambdaMartTrainingAsync,
                        this.excludes,
                        serviceOut.settings(),
                        serviceOut.policy(),
                        serviceOut.diagnostics(),
                        serviceOut.brainRuntime(),
                        serviceOut.runtimeVariant(),
                        serviceOut.packImport(),
                        serviceOut.brainInstall(),
                        serviceOut.inferenceRuntimeHandles().runtimeReconciler(),
                        feedbackCipher)))
            .orThrow();
    this.services = orchestrationOut.initialServices();
    this.orchestration =
        orchestrationOut.orchestrationHandles().withOperationsRetention(this.operationsRetentionTimer);
    this.gplJobCoordinator = orchestrationOut.gplJobCoordinator();
    this.gplAutoTrigger = orchestrationOut.gplAutoTrigger();
    this.gplSnapshotFile = orchestrationOut.gplSnapshotFile();
    this.lambdaMartModelFile = orchestrationOut.lambdaMartModelFile();
    this.substrateGraph = assembleSubstrateGraph();
    io.justsearch.app.services.operations.OperationRecoveryNotice.observePersistenceFailures(
        attempts, this.substrateGraph.health().conditionStore(), this.substrateGraph.health().changes(),
        this.substrateGraph.health().headSource(), java.time.Clock.systemUTC());
    operations.recovery().ifPresent(recovery -> {
      var health = this.substrateGraph.health();
      io.justsearch.app.services.operations.OperationRecoveryNotice.publish(
          health.conditionStore(), health.changes(), recovery, health.headSource(),
          java.time.Clock.systemUTC());
    });
    // Tempdoc 541 §5.2 + fix-pass A.1: wrap the agent-tool late-bind registration in a
    // Memoized<Boolean>. Body invokes AgentToolHandlers.registerLateBound at first resolve;
    // result is whether the prerequisites were met (true — including the case where every ref
    // was already registered, 876 B.5) or not (false — missing worker capability, knowledge
    // server, or data dir)
    // missing prerequisites). connectKnowledgeServer triggers the resolve when the Worker
    // becomes available; the original symbolic lambdamart-load LAZY entry is replaced by a
    // real "agent-tools-registration" LAZY/PENDING entry that flips to READY via Tier 4's
    // RebuildHistory ring buffer.
    // Tempdoc 561 P-E: the ONE learned-memory record, constructed here and shared by both producers
    // (the core_remember agent tool, via registerLateBound) and the /api/memory surface (LocalApiServer
    // reads memoryStore() rather than constructing its own instance) — a single authority, no drift.
    // Tempdoc 629 (LAYER): seal memory.json with the data key. Because FileMemoryStore eager-loads at
    // construction, a locked-at-launch start skips the read; reload on unlock / clear on lock via the
    // DataKeyManager listener so memory is never stuck-empty after the user unlocks.
    Path memoriesPath = this.dataDir.resolve("memories");
    io.justsearch.agent.FileMemoryStore memStore =
        new io.justsearch.agent.FileMemoryStore(
            memoriesPath,
            // 629 (#1): cipher reads the catalog's class, not a hardcoded literal.
            storeCipher(io.justsearch.agent.api.encryption.StoreCatalog.MEMORIES.recoverability()));
    registerAuthoredStore(
        new io.justsearch.agent.api.encryption.StoreDescriptor(
            io.justsearch.agent.api.encryption.StoreCatalog.MEMORIES,
            memoriesPath,
            () -> {
              var out = new java.util.ArrayList<Map<String, Object>>();
              for (var r : memStore.whatItKnows()) {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("id", r.id());
                m.put("kind", r.kind());
                m.put("content", r.content());
                m.put("sourceConversationId", r.sourceConversationId());
                m.put("actor", r.actor());
                m.put("createdAt", r.createdAt() == null ? null : r.createdAt().toString());
                out.add(m);
              }
              return out;
            },
            // Import sink (629 #E): restore each memory (skip-existing by id; remember re-seals locally).
            entries -> {
              var existing = new java.util.HashSet<String>();
              for (var r : memStore.whatItKnows()) {
                existing.add(r.id());
              }
              int restored = 0;
              for (var m : entries) {
                Object id = m.get("id");
                if (id == null || existing.contains(id.toString())) {
                  continue;
                }
                java.time.Instant createdAt = null;
                Object ca = m.get("createdAt");
                if (ca != null) {
                  try {
                    createdAt = java.time.Instant.parse(ca.toString());
                  } catch (RuntimeException ignore) {
                    // leave null on an unparseable timestamp
                  }
                }
                memStore.remember(
                    new io.justsearch.agent.api.memory.MemoryRecord(
                        id.toString(),
                        nullableStr(m.get("kind")),
                        nullableStr(m.get("content")),
                        nullableStr(m.get("sourceConversationId")),
                        nullableStr(m.get("actor")),
                        createdAt));
                restored++;
              }
              return restored;
            }));
    this.dataKeyManager.addListener(
        (from, to) -> {
          if (to == io.justsearch.app.services.encryption.DataKeyManager.State.UNLOCKED) {
            memStore.onKeyUnlocked();
          } else if (to == io.justsearch.app.services.encryption.DataKeyManager.State.LOCKED) {
            memStore.onKeyLocked();
          }
        });
    this.memoryStore = memStore;
    this.agentToolsRegistration =
        io.justsearch.app.services.bootstrap.Memoized.of(
            () -> {
              // Tempdoc 868 §B.2: read at RESOLVE time — the memo body runs after
              // connectKnowledgeServer reassembles the graph with the fresh Worker-backed services,
              // so capturing a value at composition time would bind the pre-connect (index-less)
              // IndexingService / DocumentService.
              var indexing = this.services.worker().indexing();
              // Tempdoc 875 C.3: bind the durable-grant argument scope to the LIVE indexed roots,
              // here — the same resolve-time point that owns the Worker-backed IndexingService, and
              // the same projection AgentToolFactory.assemble uses for the tools' own roots supplier.
              // WHY here and not at substrate init: the scope is constructed before the Worker exists.
              // It fails CLOSED until bound (unbound roots ⇒ containment unprovable ⇒ the gate falls
              // through to a confirm), so a wiring regression costs a prompt, never a silent grant.
              var operationOut = this.substrateOut.operationOut();
              if (operationOut != null && operationOut.durableGrantScope() != null) {
                operationOut
                    .durableGrantScope()
                    .bindIndexedRoots(
                        engineContext ->
                            indexing.getWatchedRoots(engineContext).stream()
                                .filter(r -> r != null && r.path() != null)
                                .map(r -> r.path().toAbsolutePath().normalize())
                                .toList());
              }
              return io.justsearch.app.services.bootstrap.phases.AgentToolHandlers.registerLateBound(
                  this.perSourceSearch,
                  this.substrateOut.operationHandlers(),
                  this.knowledgeServerBootstrap,
                  this.knowledgeClient,
                  this.capabilities.worker(),
                  this.dataDir,
                  indexing,
                  this.services.inference().onlineAi(),
                  this.lambdaMartReranker,
                  this.agentSearchAdapter,
                  this.fileOperationLog,
                  this.memoryStore,
                  this.scanProgressRegistry,
                  scanRollupLedgerOrNull(),
                  this.services.worker().documents());
            });
    bootTraceBuilder.record(
        io.justsearch.app.services.bootstrap.PhaseRecord.lazyPending(
            "agent-tools-registration",
            "deferred until connectKnowledgeServer; resolves once Worker is available"));
    // Tempdoc 541 §4.2: seal the boot trace at composition-root completion. Post-seal reads
    // see the final immutable snapshot.
    var sealedTrace = bootTraceBuilder.seal();
    log.info(
        "Composition boot trace sealed: {} phases in {}ms",
        sealedTrace.phases().size(),
        sealedTrace.totalDurationMs().orElse(0L));
    io.justsearch.app.services.bootstrap.phases.BootstrapHelpers.logAiServicesConfiguration(
        onlineAiService, inferenceManager, knowledgeClient, orchestrationOut.agentService());
    } catch (RuntimeException | Error failure) {
      closeFailedOwners(acquiredOwners, failure);
      throw failure;
    }
  }

  /** F5 Phase 4: delegates to OrchestrationAssembly.build. */
  private io.justsearch.app.services.bootstrap.OrchestrationHandles buildOrchestrationHandles(
      AutoCloseable indexingJobsBridge, AutoCloseable agentToolHandlers) {
    return io.justsearch.app.services.bootstrap.phases.OrchestrationAssembly.build(
        this.gplAutoTrigger,
        this.lambdaMartReranker,
        this.substrateOut.metricsOut() == null ? null : this.substrateOut.metricsOut().jobQueueDepthMetricProducer(),
        this.substrateOut.metricsOut() == null ? null : this.substrateOut.metricsOut().documentsIndexedRateMetricProducer(),
        this.substrateOut.metricsOut() == null ? null : this.substrateOut.metricsOut().gpuUtilizationMetricProducer(),
        this.substrateOut.metricsOut() == null ? null : this.substrateOut.metricsOut().gpuMemoryUtilizationMetricProducer(),
        this.inferenceManager,
        this.gpuBroadcastListener,
        this.runtimeReconciler,
        this.services == null ? null : this.services.worker().indexing(),
        this.services == null ? null : this.services.worker().documents(),
        this.substrateOut.resourceOut() == null ? null : this.substrateOut.resourceOut().diagnosticChannelAppenderInstaller(),
        this.substrateOut.indexingJobsBridgeRegistrySubscription(),
        this.agentSearchAdapter,
        indexingJobsBridge,
        agentToolHandlers);
  }


  // §31 Phase 3: registerLateBoundHandlers DELETED. ServicePhase constructs all 7 services
  // at boot; SubstratePhase registers their operation handlers at boot too (via the supplier
  // pattern reading from the held ServiceGraph). LocalApiServer publishes 3 controller back-refs
  // into bootstrap.lateBindings() after constructing controllers — that's the only remaining
  // late-bind, and it doesn't trigger any rebuild.

  /**
   * §31 Phase 5 polish: search-port-only test seam exposed as a static factory rather than a
   * public secondary constructor. Same behavior; the name documents intent (test-only,
   * narrow search path) and keeps the surface symmetric with the primary boot path.
   */
  public static HeadAssembly bootForSearchPortOnly(
      io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      SearchPort searchPort, Telemetry telemetry, io.justsearch.app.api.EngineAdmissionService engineAdmission) {
    return new HeadAssembly(operations, attempts, executors, searchPort, telemetry, engineAdmission);
  }

  /**
   * Internal constructor for {@link #bootForSearchPortOnly}. Not called directly outside this
   * class — the static factory is the public surface.
   */
  private HeadAssembly(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      SearchPort searchPort, Telemetry telemetry, io.justsearch.app.api.EngineAdmissionService engineAdmission) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    Objects.requireNonNull(searchPort, "searchPort");
    List<AutoCloseable> acquiredOwners = new java.util.ArrayList<>();
    try {
    this.perSourceSearch = null;
    this.executors = Objects.requireNonNull(executors, "executors");
    this.foregroundDocumentOwner = documentExecutorOwner(executors,
        io.justsearch.core.execution.EngineExecutorSpec.Kind.FOREGROUND, "foreground");
    acquiredOwners.add(foregroundDocumentOwner);
    this.backgroundDocumentOwner = documentExecutorOwner(executors,
        io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND, "background");
    acquiredOwners.add(backgroundDocumentOwner);
    this.foregroundDocuments = foregroundDocumentOwner.open(
        Thread.ofPlatform().daemon().name("head-documents-foreground-", 0).factory());
    this.backgroundDocuments = backgroundDocumentOwner.open(
        Thread.ofPlatform().daemon().name("head-documents-background-", 0).factory());
    this.operationsRetentionTimer = startOperationsRetentionTimer(this.operations, this.executors);
    acquiredOwners.add(this.operationsRetentionTimer);
    this.telemetry = telemetry;
    this.searchPort = searchPort;
    this.knowledgeClient = null;
    this.inferenceManager = null;
    this.gpuBroadcastListener = null;
    this.runtimeReconciler = null;
    this.offlineCoordinator = null;
    this.agentSearchAdapter = null;
    this.fileOperationLog = null;
    this.excludes = null;
    this.serviceOut = null;
    this.gplJobCoordinator = null;
    this.gplSnapshotFile = null;
    this.lambdaMartModelFile = null;
    this.operationMessageResolver = key -> key;
    // Tempdoc 561 P-E: the minimal test path has no data dir — a no-op memory store keeps the
    // single-authority contract (the field is final) without touching the filesystem.
    this.memoryStore = io.justsearch.agent.api.memory.MemoryStore.noop();
    // Tempdoc 629 (LAYER): minimal/test boot has no data dir → encryption permanently unavailable.
    this.dataKeyManager = io.justsearch.app.services.encryption.DataKeyManager.disabled();
    // Minimal substrate for tests: empty catalogs + no handler registration. Reuses the same
    // substrate-init helpers as production but skips Worker/agent-tool handler hooks.
    var handlers = new io.justsearch.agent.api.registry.HandlerRegistry();
    var operationCatalog = io.justsearch.agent.api.registry.OperationCatalog.of("core", List.of());
    var agentToolsCatalog = io.justsearch.agent.api.registry.OperationCatalog.of("core", List.of());
    var resourceOut =
        io.justsearch.app.services.bootstrap.phases.ResourceSubstrateInit.run(
            io.justsearch.app.services.bootstrap.phases.BootstrapHelpers.initialRuntimeContext());
    var metricsOut = io.justsearch.app.services.bootstrap.phases.MetricSubstrateInit.run(executors, telemetry);
    var operationOut =
        io.justsearch.app.services.bootstrap.phases.OperationSubstrateInit.run(attempts, engineAdmission,
            executors,
            handlers,
            operationCatalog,
            agentToolsCatalog,
            buildCapabilityResolver(),
            resourceOut.coreSurfaceCatalog(), io.justsearch.agent.api.encryption.StoreCipher.disabled());
    // Bridge wired after operationOut so its ActionLedgerChangeRegistry feeds the terminal-outcome
    // translator (tempdoc 550 thesis I); neither phase depends on the other.
    var bridgeOut =
        io.justsearch.app.services.bootstrap.phases.IndexingJobsBridgeWiring.wire(
            executors,
            () -> this.knowledgeClient,
            resourceOut.indexingJobsChangeRegistry(),
            operationOut.actionLedgerChangeRegistry());
    var healthOut =
        io.justsearch.app.services.bootstrap.phases.HealthSubstrateInit.run(
            executors,
            io.justsearch.app.services.bootstrap.phases.BootstrapHelpers.resolveOccurrenceBufferSize(),
            operationOut.healthRecoveryProjector(),
            operationOut.advisoryChangeRegistry(),
            operationOut.advisoryLogs(),
            resourceOut.conditionRecoveryIndexChangeRegistry());
    this.substrateOut =
        new io.justsearch.app.services.bootstrap.phases.SubstratePhase.Output(
            handlers, operationCatalog, agentToolsCatalog, resourceOut, metricsOut,
            operationOut, healthOut, bridgeOut.bridge(), bridgeOut.subscription(), null, null,
            // Tempdoc 560 §10.4 / §28 Phase 2: no plugin contributions in this fallback path
            // (channels / surfaces / shapes / resources / prompts).
            List.of(), List.of(), List.of(), List.of(), List.of());
    this.capabilities = io.justsearch.app.services.bootstrap.CapabilityGraph.unavailable();
    this.services =
        assembleServiceGraph(
            AgentService.unavailable(),
            OnlineAiService.unavailable(),
            new io.justsearch.app.services.search.SearchServiceImpl(() -> searchPort),
            null,
            null);
    this.substrateGraph = assembleSubstrateGraph();
    // Tempdoc 541 fix-pass E.2: test-only constructor; no service phase ran, so BrainAssembly
    // projects with ILM=null. The Degraded entry surfaces "inference.not_configured" in the
    // resulting BootTrace for downstream observability consistency.
    long t_brain = System.currentTimeMillis();
    this.brainAssembly = BrainAssembly.project(null, t_brain, t_brain);
    // Fix-pass A.1: secondary constructor doesn't run the full substrate, so agent-tools
    // late-bind isn't applicable. A pre-resolved Memoized(false) keeps the field final and
    // surfaces "skipped: no worker" if any caller queries it.
    this.agentToolsRegistration =
        io.justsearch.app.services.bootstrap.Memoized.of(() -> Boolean.FALSE);
    this.orchestration =
        io.justsearch.app.services.bootstrap.OrchestrationHandles.empty()
            .withOperationsRetention(this.operationsRetentionTimer);
    } catch (RuntimeException | Error failure) {
      closeFailedOwners(acquiredOwners, failure);
      throw failure;
    }
  }

  /** §5/F1 snapshot accessor — projects the live inference manager into a status record. */
  public InferenceRuntimeView inferenceSnapshot() {
    return io.justsearch.app.services.bootstrap.phases.BootstrapProjections
        .projectInferenceSnapshot(this.inferenceManager, this.runtimeReconciler);
  }

  /**
   * Tempdoc 682 Item 2: expected llama-server build tag from the staging pin marker; null =
   * unknown (no inference manager, no config, or no marker — all supported states).
   */
  public String expectedLlamaServerBuild() {
    return this.inferenceManager == null ? null : this.inferenceManager.expectedLlamaServerBuild();
  }

  /**
   * Tempdoc 682 Item 2: actually-running llama-server build tag observed from {@code /props};
   * null until a server has been started or adopted.
   */
  public String actualLlamaServerBuild() {
    return this.inferenceManager == null ? null : this.inferenceManager.actualLlamaServerBuild();
  }

  /**
   * Tempdoc 835 §9c.2: the running llama-server's thinking-capability verdict; null when there is
   * no inference manager (thinking-capability is then simply unknown).
   */
  public String llamaServerThinkingSupport() {
    return this.inferenceManager == null ? null : this.inferenceManager.llamaServerThinkingSupport();
  }

  /**
   * Tempdoc 883 decision 1: the derived context window this engine was launched with and why;
   * null when there is no inference manager, or when it launched nothing (adopted server).
   */
  public io.justsearch.app.api.OnlineAiRuntimeIntrospection.ContextWindow launchedContextWindow() {
    return this.inferenceManager == null ? null : this.inferenceManager.launchedContextWindow();
  }

  /**
   * Tempdoc 842 §2.5: the chat identity the engine is <em>actually</em> running — profile claim,
   * loaded model file, projector attached — or {@code null} when no engine is up.
   *
   * <p>Same projection the runtime-status endpoint publishes ({@code
   * BootstrapProjections.projectRealizedChatIdentity}), so the manifest and {@code
   * /api/ai/runtime/status} cannot disagree about which model this stack is talking to.
   */
  public io.justsearch.app.api.inference.RealizedChatIdentity realizedChatIdentity() {
    return io.justsearch.app.services.bootstrap.phases.BootstrapProjections
        .projectRealizedChatIdentity(this.inferenceManager);
  }

  /** F6: rebuild the held ServiceGraph. Reads prior services from the existing held graph. */
  private io.justsearch.app.api.ServiceGraph rebuildServiceGraph() {
    return assembleServiceGraph(
        this.services.core().agent(),
        this.services.inference().onlineAi(),
        this.services.worker().search(),
        this.services.worker().indexing(),
        this.services.worker().documents());
  }

  private io.justsearch.app.api.ServiceGraph assembleServiceGraph(
      AgentService agent,
      OnlineAiService onlineAi,
      io.justsearch.app.api.SearchService search,
      IndexingService indexing,
      DocumentService documents) {
    return io.justsearch.app.services.bootstrap.phases.ServiceGraphAssembler.assemble(
        agent,
        onlineAi,
        search,
        indexing,
        documents,
        this.excludes,
        serviceOut == null ? null : serviceOut.settings(),
        serviceOut == null ? null : serviceOut.policy(),
        serviceOut == null ? null : serviceOut.diagnostics(),
        serviceOut == null ? null : serviceOut.brainRuntime(),
        serviceOut == null ? null : serviceOut.runtimeVariant(),
        serviceOut == null ? null : serviceOut.packImport(),
        serviceOut == null ? null : serviceOut.brainInstall());
  }

  /**
   * Tempdoc 561 P-E — the single-authority learned-memory record. Shared by the {@code core_remember}
   * agent tool (the learning producer) and the {@code /api/memory} surface so what the assistant
   * learns is exactly what the user inspects + can forget (no second authority, no drift).
   */
  public io.justsearch.agent.api.memory.MemoryStore memoryStore() {
    return memoryStore;
  }

  /**
   * Tempdoc 565 §15.C — the durable agent run store. Exposed so the workflow runner persists through
   * the SAME shape-agnostic {@code RunEventStore} ({@code agentRunStore.runEvents()}), landing workflow
   * runs in the one run-event space the unified thread projects. May be null on the test-only path.
   */
  public AgentRunStore agentRunStore() {
    return agentRunStore;
  }

  /**
   * Tempdoc 778 — the default-on local feedback-capture flag. Exposed so the {@code
   * /api/feedback/capture} surface reads/writes the ONE authority the capture sites consult, and the
   * search controller gates its disposition writes on it. May be null on a test-only path.
   */
  public io.justsearch.app.services.feedback.FeedbackCaptureSettings feedbackCaptureSettings() {
    return feedbackCaptureSettings;
  }

  /** Tempdoc 629 (LAYER): the data-at-rest key manager (one per Head; consumed by stores + API + status). */
  public io.justsearch.app.services.encryption.DataKeyManager dataKeyManager() {
    return dataKeyManager;
  }

  /**
   * Tempdoc 629 (#1, the spine made load-bearing): the at-rest cipher is a <i>projection</i> of a
   * store's {@link io.justsearch.agent.api.encryption.StoreRecoverability recoverability class}, not a
   * per-store decision — AUTHORED stores are sealed with the data key; DERIVED stores are never
   * app-encrypted (FDE covers them; total key loss is non-catastrophic, tempdoc 628). Every store's
   * cipher injection reads this, so the class — not a hardcoded list — decides what gets encrypted.
   */
  public io.justsearch.agent.api.encryption.StoreCipher storeCipher(
      io.justsearch.agent.api.encryption.StoreRecoverability recoverability) {
    return recoverability == io.justsearch.agent.api.encryption.StoreRecoverability.AUTHORED
        ? new io.justsearch.agent.api.encryption.StoreCipher(this.dataKeyManager)
        : io.justsearch.agent.api.encryption.StoreCipher.disabled();
  }

  /**
   * Tempdoc 629 (#1): the one authoritative list of AUTHORED stores. Each store registers its descriptor
   * (class + path + a read-side lambda) here as it is constructed — memory + agent-runs from this
   * assembly, conversations from {@code ConversationApiAssembly}. The encrypted-backup export reads this
   * list instead of re-enumerating the stores, so "which stores are AUTHORED" is declared once.
   */
  private final List<io.justsearch.agent.api.encryption.StoreDescriptor> authoredStores =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  /**
   * Register an AUTHORED store descriptor (called at each store's construction; assembly-time only).
   *
   * <p>Tempdoc 879: the registration list and {@link
   * io.justsearch.agent.api.encryption.StoreCatalog#isAuthored()} were two independent answers to the
   * same question — the catalog named the authority, the list did the enumerating, and nothing made
   * the list ask. It asks here, so a DERIVED store enrolled in the encrypted backup fails at assembly
   * instead of silently widening what the backup claims to protect.
   */
  public void registerAuthoredStore(io.justsearch.agent.api.encryption.StoreDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (!descriptor.store().isAuthored()) {
      throw new IllegalArgumentException(
          "StoreCatalog."
              + descriptor.store().name()
              + " is "
              + descriptor.store().recoverability()
              + ", not AUTHORED; it cannot be registered as an authored store.");
    }
    this.authoredStores.add(descriptor);
  }

  /** The aggregated AUTHORED store list, read by the backup export/import. */
  public List<io.justsearch.agent.api.encryption.StoreDescriptor> authoredStores() {
    return List.copyOf(this.authoredStores);
  }

  /** Null-safe {@code toString} for import-sink field coercion from JSON-able maps. */
  private static String nullableStr(Object o) {
    return o == null ? null : o.toString();
  }

  /**
   * Tempdoc 778 — read one sealed feedback ndjson into {@code out} as {@code {"file", "record"}}
   * envelopes for the encrypted-backup source. Best-effort: a read failure logs and skips.
   */
  private static <T> void readFeedbackFile(
      Path file,
      Class<T> type,
      io.justsearch.agent.api.encryption.StoreCipher cipher,
      String label,
      List<Map<String, Object>> out) {
    try {
      var store = new io.justsearch.app.services.feedback.NdjsonAppendStore<>(file, type, cipher);
      var mapper = new tools.jackson.databind.ObjectMapper();
      for (T rec : store.readAll()) {
        var entry = new java.util.LinkedHashMap<String, Object>();
        entry.put("file", label);
        entry.put("record", mapper.convertValue(rec, Map.class));
        out.add(entry);
      }
    } catch (Exception e) {
      log.warn("feedback backup read failed for {}: {}", label, e.toString());
    }
  }

  /**
   * Tempdoc 778 — restore feedback {@code {"file","record"}} envelopes into the two AUTHORED streams
   * (skip-existing by serialized-record identity, so a re-import never double-appends). Returns the
   * count actually written.
   */
  private static int restoreFeedbackEntries(
      Path feedbackDir,
      io.justsearch.agent.api.encryption.StoreCipher cipher,
      List<Map<String, Object>> entries) {
    if (entries == null || entries.isEmpty()) {
      return 0;
    }
    var mapper = new tools.jackson.databind.ObjectMapper();
    var dispositions =
        new io.justsearch.app.services.feedback.NdjsonAppendStore<>(
            feedbackDir.resolve("result-dispositions.ndjson"),
            io.justsearch.app.services.feedback.ResultDisposition.class,
            cipher);
    var snapshots =
        new io.justsearch.app.services.feedback.NdjsonAppendStore<>(
            feedbackDir.resolve("feature-snapshots.ndjson"),
            io.justsearch.app.services.feedback.FeatureSnapshot.class,
            cipher);
    int restored = 0;
    try {
      var existingDisp = toJsonSet(dispositions.readAll(), mapper);
      var existingSnap = toJsonSet(snapshots.readAll(), mapper);
      for (var entry : entries) {
        Object file = entry.get("file");
        if (!(entry.get("record") instanceof Map<?, ?> recordMap)) {
          continue;
        }
        if ("result-dispositions.ndjson".equals(file)) {
          var rec =
              mapper.convertValue(
                  recordMap, io.justsearch.app.services.feedback.ResultDisposition.class);
          if (existingDisp.add(mapper.writeValueAsString(rec))) {
            dispositions.append(rec);
            restored++;
          }
        } else if ("feature-snapshots.ndjson".equals(file)) {
          var rec =
              mapper.convertValue(
                  recordMap, io.justsearch.app.services.feedback.FeatureSnapshot.class);
          if (existingSnap.add(mapper.writeValueAsString(rec))) {
            snapshots.append(rec);
            restored++;
          }
        }
      }
    } catch (Exception e) {
      log.warn("feedback backup restore failed: {}", e.toString());
    }
    return restored;
  }

  /** Serialized-record identity set for the feedback restore's skip-existing check. */
  private static <T> java.util.Set<String> toJsonSet(
      List<T> records, tools.jackson.databind.ObjectMapper mapper) {
    var set = new java.util.HashSet<String>();
    for (T r : records) {
      try {
        set.add(mapper.writeValueAsString(r));
      } catch (Exception ignored) {
        // best-effort dedup key
      }
    }
    return set;
  }

  /** Returns the always-available core services (settings, policy, diagnostics, agent). */
  public io.justsearch.app.api.CoreServices core() {
    io.justsearch.app.api.ServiceGraph sg = this.services;
    return sg != null ? sg.core() : rebuildServiceGraph().core();
  }

  /** Returns the Worker-dependent services. Fields are null until Worker connects. */
  public io.justsearch.app.api.WorkerServices workers() {
    return this.services.worker();
  }

  /** Returns the inference-related services. */
  public io.justsearch.app.api.InferenceServices inference() {
    io.justsearch.app.api.ServiceGraph sg = this.services;
    return sg != null ? sg.inference() : rebuildServiceGraph().inference();
  }

  /** Tempdoc 501 Phase 33 (§13.4.3): live accessor; volatile field re-assigned on every worker (re)connect. */
  public KnowledgeServerBootstrap currentKnowledgeServer() {
    return knowledgeServerBootstrap;
  }

  /** Late-bind the Knowledge Server after async Worker startup; rebuilds the held graph. */
  public void connectKnowledgeServer(KnowledgeServerBootstrap ks) {
    if (ks == null) return;
    // Fix-pass Tier 4 (C-revised): record the rebuild event into RebuildHistory. Captures
    // the connect window + outcome reasonCode so downstream consumers (FE panel, observability
    // exporters) can see post-boot substrate mutations that the sealed BootTrace can't.
    long t_rebuild_0 = System.currentTimeMillis();
    KnowledgeClient client = ks.client();
    this.knowledgeClient = client;
    this.knowledgeServerBootstrap = ks;
    io.justsearch.app.services.bootstrap.phases.InferenceWiring.refreshGpuStatus(
        this.inferenceManager, ks);
    this.searchPort = client;
    IndexingService newIndexing = client;
    DocumentService newDocuments =
        io.justsearch.app.services.bootstrap.phases.BootstrapDocumentService.create(
            foregroundDocuments, backgroundDocuments, () -> this.knowledgeClient, telemetry);
    if (this.substrateOut.indexingJobsBridge() != null) {
      try {
        this.substrateOut.indexingJobsBridge().start();
      } catch (RuntimeException e) {
        log.warn("RemoteIndexingJobsBridge.start failed at connectKnowledgeServer", e);
      }
    }
    // Tempdoc 627 Deliverable 10: the Worker capability is now ONE shared instance — created before
    // the async worker-start fork (HeadlessApp) and held by BOTH the KS bootstrap (the supervisor's
    // writer) and this CapabilityGraph (the surfaces' reader). So the KS-capability ↔ HeadAssembly-
    // capability mirror (tempdoc 521 T2.5) is GONE: the surfaces read the same instance the supervisor
    // writes, eliminating the silent-drift bug class (Bug A) at its root. CapabilityHealthBridge.
    // wireListeners seeds its first condition directly (replay-on-wire), replacing the mirror's
    // synchronous initial-copy. (Non-shared construction — e.g. a test that mocks a separate KS
    // capability — must pass that instance as the HeadAssembly sharedWorkerCapability so localCap
    // == ks.workerCapability().)
    var localCap = this.capabilities.worker();
    AutoCloseable bridgeHandle =
        this.substrateOut.indexingJobsBridge() == null ? null : (AutoCloseable) this.substrateOut.indexingJobsBridge()::stop;
    this.orchestration =
        buildOrchestrationHandles(bridgeHandle, null)
            .withOperationsRetention(this.operationsRetentionTimer);
    io.justsearch.app.api.SearchService newSearch =
        new io.justsearch.app.services.search.SearchServiceImpl(() -> this.searchPort);
    this.services =
        assembleServiceGraph(
            this.services.core().agent(),
            this.services.inference().onlineAi(),
            newSearch,
            newIndexing,
            newDocuments);
    // 543-fwd root-cause fix (v2): trigger the Memoized agent-tool registration AFTER both
    // (a) the capability bridge above transitioned localCap to the Worker's real state, AND
    // (b) this.services was reassembled (just above) with the fresh worker services
    // (newIndexing). The Memoized body reads this.services.worker().indexing() at resolve
    // time; resolving it BEFORE the reassignment passed a NULL indexingService into
    // registerLateBound, which NPE'd at boot (AgentToolHandlers builds FileOperationsTool from
    // indexingService::getWatchedPaths) and crashed HeadlessApp. The ORIGINAL pre-fix code
    // resolved the memo at the TOP of connectKnowledgeServer where registerLateBound SKIPPED
    // (worker not yet available), caching a premature false so the agent's server-side tools
    // were NEVER registered ("No handler registered for binding core.search-index"). Resolve
    // only when the worker is available (caching success, never a premature false) and forward
    // later transitions. registerLateBound is idempotent PER REF (tempdoc 876 §B.5): a ref the
    // eager path already registered is left alone, so the two paths compose. It no longer
    // short-circuits the whole call on SEARCH_INDEX standing proxy for the rest.
    if (localCap != null && localCap.available()) {
      this.agentToolsRegistration.get();
    } else if (localCap != null) {
      localCap.addListener(
          (prev, next) -> {
            if (localCap.available()) {
              this.agentToolsRegistration.get();
            }
          });
    }
    log.info(
        "Knowledge Server late-bound into HeadAssembly (capability health: {})",
        this.capabilities.worker().health());
    // Fix-pass Tier 4 (C-revised): record the rebuild event. Single record per invocation;
    // reasonCode reflects the post-bind capability state. CapabilityHealth.READY is the
    // healthy arm; anything else (PENDING/DEGRADED/RECOVERING/OFFLINE/null) records as a
    // Degraded outcome carrying the actual health name in the reason code.
    long t_rebuild_1 = System.currentTimeMillis();
    var workerHealth = this.capabilities.worker().health();
    if (workerHealth == io.justsearch.app.api.lifecycle.CapabilityHealth.READY) {
      this.rebuildHistory.record(
          io.justsearch.app.services.bootstrap.PhaseRecord.ready(
              "worker-connect", t_rebuild_0, t_rebuild_1, null));
    } else {
      this.rebuildHistory.record(
          io.justsearch.app.services.bootstrap.PhaseRecord.degraded(
              "worker-connect",
              t_rebuild_0,
              t_rebuild_1,
              "worker.connected." + (workerHealth == null ? "null" : workerHealth.name().toLowerCase()),
              null));
    }
  }

  // §12.D: registerAgentToolHandlers helper deleted — superseded by the Memoized<Boolean>
  // agentToolsRegistration field which inlines the AgentToolHandlers.registerLateBound call
  // (see field declaration ~line 220 + connectKnowledgeServer's .get() trigger). The
  // private helper had no remaining callers; UnreferencedCodeTest flagged it as dead.
  // Slice 491 defect 3 (2026-05-12) historical context is preserved in the Memoized
  // field's javadoc.

  /**
   * Returns a cache of Worker-reported ONNX model discovery status (D-4, tempdoc 215).
   *
   * <p>Updated on each successful health check. Returns a no-op cache (empty list) when no
   * knowledge server is configured.
   */
  public WorkerFeatureCache workerFeatureCache() {
    if (knowledgeClient == null) {
      return List::of;
    }
    return knowledgeClient::getLastKnownOnnxModels;
  }

  /** Exposes the capabilities HTTP handler so launchers can register `/infra/capabilities`. */
  public HttpHandler capabilitiesHandler() {
    return infraOut == null ? null : infraOut.capabilitiesHandler();
  }

  /**
   * Tempdoc 521 §16.8 (ported from main commit 8c0184f47) — sequence-number source for the
   * {@code /infra/capabilities} HTTP route. Returns null when the substrate isn't built yet.
   */
  public io.justsearch.app.observability.CapabilitiesChangeRegistry capabilitiesChangeRegistry() {
    return substrateOut == null ? null : substrateOut.operationOut().capabilitiesChangeRegistry();
  }

  /** Whether the drain barrier passed and dependency teardown began, even if cleanup then failed. */
  public boolean isDependencyTeardownStarted() {
    return closed.get();
  }

  /** §4 F4 LIFO teardown via the typed OrchestrationHandles record. */
  @Override
  public void close() {
    // Repeatable termination barrier: an unfinished procedure must leave a later close able to
    // finish dependency teardown. Never claim the one-shot closed state before this succeeds.
    if (offlineCoordinator != null) offlineCoordinator.close();
    io.justsearch.app.services.bootstrap.OrchestrationHandles handles = this.orchestration;
    if (!closed.get() && handles != null && handles.operationsRetention() != null) {
      try { handles.operationsRetention().close(); }
      catch (RuntimeException failure) { throw failure; }
      catch (Exception failure) { throw new IllegalStateException("Operations retention did not drain", failure); }
    }
    if (!closed.compareAndSet(false, true)) return;
    if (serviceOut != null) {
      try {
        serviceOut.runtimeActivationHelper().close();
      } catch (RuntimeException e) {
        log.warn("Failed to close RuntimeActivationService", e);
      }
    }
    // Tempdoc 812 D2: stop the scan-rollup quiescence sweeper (symmetric with its construction in
    // OperationSubstrateInit — a substrate-owned thread is torn down with the substrate).
    if (substrateOut != null && substrateOut.operationOut() != null) {
      try {
        substrateOut.operationOut().scanRollupLedger().close();
      } catch (RuntimeException e) {
        log.warn("Failed to close ScanRollupLedger", e);
      }
    }
    // Tempdoc 876 §B.2a: stop the readiness-reconciliation daemon thread (same precedent as the
    // sweeper above — a substrate-owned thread is torn down with the substrate).
    if (substrateOut != null && substrateOut.healthOut() != null) {
      try {
        substrateOut.healthOut().readinessReconciliationTrigger().close();
      } catch (RuntimeException e) {
        log.warn("Failed to close ReadinessReconciliationTrigger", e);
      }
    }
    try {
      RuntimeException failure = null;
      for (var scan : unlockScans) {
        try { scan.close(); }
        catch (RuntimeException closeFailure) {
          if (failure == null) failure = closeFailure;
          else failure.addSuppressed(closeFailure);
        }
      }
      try { if (handles != null) handles.withOperationsRetention(null).close(); }
      catch (RuntimeException closeFailure) {
        if (failure == null) failure = closeFailure;
        else failure.addSuppressed(closeFailure);
      }
      if (failure != null) throw failure;
    } finally {
      try {
        try {
          if (agentHistoryIndexer != null) agentHistoryIndexer.close();
        } finally {
          // The manager emits its final SHUTDOWN transition while closing above. Drain afterwards.
          if (asyncTransitionLog != null) asyncTransitionLog.close();
        }
      } finally {
        try {
          if (perSourceSearch != null) perSourceSearch.close();
        } finally {
          try { foregroundDocumentOwner.close(); } finally { backgroundDocumentOwner.close(); }
        }
      }
    }
  }

  private static void closeFailedOwners(List<AutoCloseable> owners, Throwable failure) {
    for (int i = owners.size() - 1; i >= 0; i--) {
      try { owners.get(i).close(); }
      catch (Exception | Error cleanup) {
        if (cleanup != failure) failure.addSuppressed(cleanup);
      }
    }
  }

  private static io.justsearch.core.execution.EngineExecutorRegistry.Registration documentExecutorOwner(
      io.justsearch.core.execution.EngineExecutorRegistry registry,
      io.justsearch.core.execution.EngineExecutorSpec.Kind kind, String suffix) {
    var limits = registry.limits(kind);
    return registry.register(new io.justsearch.core.execution.EngineExecutorSpec(
        "head.documents." + suffix, kind,
        io.justsearch.core.execution.EngineExecutorSpec.Mode.PLATFORM,
        limits.maxThreads(), limits.maxQueue(), 1));
  }

  static AutoCloseable startOperationsRetentionTimer(
      io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.core.execution.EngineExecutorRegistry executors) {
    Objects.requireNonNull(operations, "operations");
    Objects.requireNonNull(executors, "executors");
    var limits = executors.limits(
        io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND);
    var registration = executors.register(new io.justsearch.core.execution.EngineExecutorSpec(
        "head.operations-retention",
        io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND,
        io.justsearch.core.execution.EngineExecutorSpec.Mode.SCHEDULED,
        1,
        limits.maxQueue(),
        1));
    java.util.concurrent.ScheduledExecutorService scheduler;
    try {
      scheduler = registration.openScheduled(
          Thread.ofPlatform().daemon().name("operations-retention-", 0).factory());
      var task = scheduler.scheduleWithFixedDelay(
          () -> pruneOperationsHistory(operations), 1, 1, java.util.concurrent.TimeUnit.HOURS);
      return new OperationsRetentionHandle(registration, scheduler, task);
    } catch (RuntimeException | Error failure) {
      try {
        registration.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  static void pruneOperationsHistory(io.justsearch.app.api.operations.OperationStore operations) {
    try {
      operations.pruneHistory();
    } catch (RuntimeException failure) {
      log.error("Operations history retention failed; will retry on the next hourly tick", failure);
    }
  }

  private static final class OperationsRetentionHandle implements AutoCloseable {
    private final io.justsearch.core.execution.EngineExecutorRegistry.Registration registration;
    private final java.util.concurrent.ScheduledExecutorService scheduler;
    private final java.util.concurrent.ScheduledFuture<?> task;
    private boolean closed;

    private OperationsRetentionHandle(
        io.justsearch.core.execution.EngineExecutorRegistry.Registration registration,
        java.util.concurrent.ScheduledExecutorService scheduler,
        java.util.concurrent.ScheduledFuture<?> task) {
      this.registration = registration;
      this.scheduler = scheduler;
      this.task = task;
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }
      try {
        task.cancel(true);
        scheduler.shutdownNow();
        if (!scheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
          throw new IllegalStateException(
              "Operations retention scheduler did not terminate within 5s");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while waiting for operations retention scheduler termination",
            interrupted);
      }
      // Keep the registration live after a failed termination wait so a later close can finish
      // the same owner. It is released only after the scheduler has actually terminated.
      registration.close();
      closed = true;
    }
  }

  /** Helper for resolving the JustSearch home directory. */
  private static Path getJustSearchHome() {
    return io.justsearch.app.services.bootstrap.BootstrapInferenceFactory.getJustSearchHome(
        io.justsearch.app.services.bootstrap.phases.BootstrapHelpers.currentResolvedConfig(),
        System.getProperty("user.dir"));
  }

  // Reflection compatibility shim retained for existing tests.
  @SuppressWarnings("unused")
  private static String chooseFirstNonBlank(String... values) {
    return io.justsearch.app.services.bootstrap.BootstrapFlagResolver.chooseFirstNonBlank(values);
  }

  /** §10 Phase D: 4-component head-infra bundle (offline + GPL + LambdaMART + snapshot). */
  public io.justsearch.app.services.bootstrap.HeadInfraRegistry headInfraRegistry() {
    Supplier<GplEvalData> snapshot =
        gplSnapshotFile == null ? () -> null : () -> GplEvalSnapshot.load(gplSnapshotFile);
    return new io.justsearch.app.services.bootstrap.HeadInfraRegistry(
        offlineCoordinator, gplJobCoordinator, lambdaMartReranker, snapshot);
  }

  private void startLambdaMartTrainingAsync() {
    io.justsearch.app.services.bootstrap.phases.LambdaMartTraining.startAsync(
        gplJobCoordinator, lambdaMartReranker, lambdaMartModelFile);
  }

  /** §4 Phase 2 typed output. */
  public io.justsearch.app.services.bootstrap.CapabilityGraph capabilities() {
    return this.capabilities;
  }

  /**
   * Tempdoc 832 (lane D) — publishes the process-wide scan-progress registry (owned by
   * {@code LocalApiServer}, which constructs it) so the agent-owned ingest adapter gets the same
   * scan observability the controller-owned one has: without it, an agent- or MCP-driven directory
   * ingest emitted no scan-progress SSE and left no rollup ledger row.
   *
   * <p>Called before {@link #connectKnowledgeServer}, which is where the late-bound registration
   * builds the adapter that {@code IngestTool} actually drives on the normal (async-Worker) boot.
   * The eager-path adapter, when one exists, is bound here directly.
   */
  public void setScanProgressRegistry(
      io.justsearch.app.services.worker.ScanProgressRegistry registry) {
    this.scanProgressRegistry = registry;
    io.justsearch.app.services.bootstrap.phases.AgentToolFactory.bindScanObservability(
        this.agentSearchAdapter, registry, scanRollupLedgerOrNull());
  }

  private io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedgerOrNull() {
    return this.substrateOut == null ? null : this.substrateOut.operationOut().scanRollupLedger();
  }

  /** §6 typed substrate graph built once at end of constructor. */
  public io.justsearch.app.services.bootstrap.SubstrateGraph substrate() {
    return this.substrateGraph != null ? this.substrateGraph : assembleSubstrateGraph();
  }

  /**
   * Tempdoc 560 §28 Phase 3 — the live composed {@link io.justsearch.agent.api.registry.ContributionRegistry}
   * (the run-tier witness source). Unlike the static build-time snapshot, this reflects EVERY install —
   * core, agent-tools, workflows, MCP, and plugins — so runtime-only contributions are observable.
   * {@code null} in the test-only fallback constructor (no MCP host service ran).
   */
  public io.justsearch.agent.api.registry.ContributionRegistry liveRegistry() {
    return substrateOut.mcpHostService() != null
        ? substrateOut.mcpHostService().contributionRegistry()
        : null;
  }

  private io.justsearch.app.services.bootstrap.SubstrateGraph assembleSubstrateGraph() {
    return io.justsearch.app.services.bootstrap.phases.SubstrateGraphAssembler.assemble(
        substrateOut.operationCatalog(),
        substrateOut.agentToolsCatalog(),
        substrateOut.operationHandlers(),
        operationMessageResolver,
        substrateOut.operationOut(),
        substrateOut.resourceOut(),
        substrateOut.metricsOut(),
        substrateOut.healthOut(),
        substrateOut.ruleRunner(),
        substrateOut.pluginDiagnosticChannels(),
        substrateOut.pluginSurfaces(),
        substrateOut.pluginConversationShapes(),
        substrateOut.pluginResources(),
        substrateOut.pluginPrompts());
  }

  /** Held MetricSubstrateInit.Output — LocalApiServer reads metric producers via this. */
  public io.justsearch.app.services.bootstrap.phases.MetricSubstrateInit.Output metricsOut() {
    return substrateOut.metricsOut();
  }

  /** Head-side bridge owning SubscribeIndexingJobs gRPC stream. Null until connect. */
  public io.justsearch.app.services.worker.RemoteIndexingJobsBridge indexingJobsBridge() {
    return substrateOut.indexingJobsBridge();
  }

  /**
   * Tempdoc 737 Phase 1: the runtime authority's reconciler (null in test paths without the
   * service graph). Exposed so the settings write path can nudge {@code specChanged()} — a spec
   * write must converge now, not at next boot.
   */
  public io.justsearch.app.services.runtimestate.RuntimeReconciler runtimeReconciler() {
    return this.runtimeReconciler;
  }

  private java.util.function.Function<
          io.justsearch.agent.api.registry.RequiredCapability, Boolean>
      buildCapabilityResolver() {
    return req ->
        switch (req) {
          case io.justsearch.agent.api.registry.RequiredCapability.WorkerOnline w ->
              this.capabilities.worker().available();
          case io.justsearch.agent.api.registry.RequiredCapability.InferenceOnline i ->
              this.capabilities.inference().available();
        };
  }
}
