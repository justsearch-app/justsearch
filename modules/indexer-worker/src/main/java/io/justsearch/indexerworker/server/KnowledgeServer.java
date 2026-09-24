/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.adapters.lucene.runtime.IndexRecoveryMarker;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.DeferredRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeBuilder;
import io.justsearch.adapters.lucene.runtime.ReadOnlyRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.indexerworker.embed.EmbeddingFingerprint;
import io.justsearch.indexerworker.splade.SpladeFingerprint;
import io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard;
import io.justsearch.adapters.lucene.runtime.ParityDiagnostics;
import io.justsearch.indexerworker.embed.EmbeddingMetadataOverlay;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.recovery.IndexRecoveryPolicy;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.index.MigrationProgressStore;
import io.justsearch.app.api.status.MigrationSource;
import io.justsearch.indexerworker.liveness.LivenessWindows;
import io.justsearch.indexerworker.util.IndexRootLock;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.server.ops.KnowledgeServerSafeMetrics;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.model.VariantSelector;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.RepoRootLocator;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.telemetry.JvmRuntimeGauges;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Knowledge Server owns the index half's infrastructure — Lucene runtimes, the job queue, the
 * signal bus — and the application services built on top of it:
 * <ul>
 *   <li>Search queries</li>
 *   <li>Batch ingestion of file paths</li>
 *   <li>Background indexing loop</li>
 *   <li>The signal bus: the GPU-scheduling gauge it shares with the application half, and the
 *       dev-only hot-reload request file</li>
 * </ul>
 *
 * <p><b>It is not a server in the socket sense any more.</b> Until lane F stage A it hosted gRPC
 * services for a second JVM: item A9 deleted the gRPC server, its interceptors and the ephemeral
 * port it published to the signal bus, and item A11 deleted the worker process itself. Since item
 * A6 this class is constructed and started in the Head JVM by
 * {@code io.justsearch.app.engine.EngineRoot}, and callers reach its services as direct calls
 * through {@link #appServices()}. The name is kept because every log line, metric and doc uses it.
 */
public final class KnowledgeServer implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeServer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final long MIGRATION_SWITCHING_QUEUE_DEPTH_THRESHOLD = 1_000L;
  private static final long MIGRATION_SWITCHING_MAX_DURATION_MS = 30L * 60_000L;

  private final WorkerConfig config;
  // Package-private: accessed by DevReloadManager for build-stamp update (371)
  final Path dataDir;
  private Telemetry telemetry;
  // Tempdoc 417 Phase 1: typed catalog of index.runtime.* metrics, populated at boot.
  private io.justsearch.indexerworker.services.IndexRuntimeMetricCatalog indexRuntimeCatalog;
  // Tempdoc 414: ORT session lifecycle events recorder, populated at boot. Threaded into
  // InferenceCompositionRoot.compose so OrtSessionAssembler.buildManager picks it up.
  private io.justsearch.ort.telemetry.OrtSessionTelemetryEvents ortSessionEvents;
  // Tempdoc 413: typed catalog of embedding.runtime.* metrics + façade. Catalog constructed at
  // boot with a deferred cache-size supplier that tolerates `embeddingService==null` until
  // initDeferredModels wires the service. Façade is passed into EmbeddingService and IndexingLoop.
  // Package-private: DevReloadManager re-wires the events sink after hot-reload reconstruction.
  io.justsearch.indexerworker.embed.EmbeddingMetricCatalog embeddingMetricCatalog;
  io.justsearch.indexerworker.embed.EmbeddingTelemetry embeddingTelemetry;

  private Path indexBasePath;
  private Path activeIndexPath;
  private Path buildingIndexPath;
  private IndexGenerationManager indexGenerationManager;
  private IndexGenerationManager.BootOwnership generationBootOwnership;
  private IndexGenerationManager.BootDisposition generationBootDisposition;
  private volatile boolean promotedReplaySettled;
  private IndexRootLock indexRootLock;
  private boolean closePrepared;
  private WorkerAppServices pendingAppServices;
  private DeferredRuntime.PreparedUpgrade pendingDeferredUpgrade;
  private final Object closeLock = new Object();
  private final ReentrantLock runtimeSwapLock = new ReentrantLock();

  /** Serializes dev service replacement with admin runtime replacement and shutdown. */
  final class DevReplacementLease implements AutoCloseable {
    private boolean released;

    void assertOwned() {
      if (released || !runtimeSwapLock.isHeldByCurrentThread()) {
        throw new IllegalStateException("Dev replacement owner lock is not held");
      }
    }

    @Override public void close() {
      if (released) return;
      released = true;
      runtimeSwapLock.unlock();
    }
  }

  DevReplacementLease beginDevReplacement() {
    if (closeStarted) throw new IllegalStateException("Dev reload refused during server close");
    runtimeSwapLock.lock();
    if (closeStarted) {
      runtimeSwapLock.unlock();
      throw new IllegalStateException("Dev reload refused during server close");
    }
    return new DevReplacementLease();
  }
  private final ReentrantReadWriteLock publicationLock;
  private final Object servingViewMonitor = new Object();
  private ServingView servingView;
  private final List<ServingView> retiredServingViews = new ArrayList<>();

  private static final class ServingView {
    private final WorkerAppServices services;
    private final LuceneRuntime searchRuntime;
    private final LuceneRuntime ingestRuntime;
    private final Path activeGenerationPath;
    private volatile EncoderSet encoderSet;
    private EncoderSet.Lease encoderLease;
    private int holders;
    private final List<Runnable> retirementListeners = new ArrayList<>();
    private boolean retiring;
    private boolean cleanupRunning;
    private Runnable retireCleanup;

    private ServingView(WorkerAppServices services, LuceneRuntime searchRuntime,
        LuceneRuntime ingestRuntime, Path activeGenerationPath) {
      this.services = Objects.requireNonNull(services, "services");
      this.searchRuntime = searchRuntime;
      this.ingestRuntime = ingestRuntime;
      this.activeGenerationPath = activeGenerationPath;
    }

    private void attachEncoderSet(EncoderSet owner) {
      if (encoderSet != null) throw new IllegalStateException("Serving encoder set already installed");
      encoderLease = Objects.requireNonNull(owner, "owner").acquire();
      encoderSet = owner;
    }

    private void releaseEncoderSet() {
      if (encoderLease != null) encoderLease.close();
    }
  }

  /** Retains the exact application services and runtimes selected at capture. */
  public final class ServingLease implements AutoCloseable {
    private final ServingView captured;
    private final AtomicBoolean released = new AtomicBoolean();

    private ServingLease(ServingView captured) { this.captured = captured; }

    public WorkerAppServices services() { return captured.services; }

    public LuceneRuntime searchRuntime() { return captured.searchRuntime; }

    public LuceneRuntime ingestRuntime() { return captured.ingestRuntime; }

    public Path activeGenerationPath() { return captured.activeGenerationPath; }

    /** The exact model owner retained by this serving view, once deferred wiring has completed. */
    public EncoderSet encoderSet() { return captured.encoderSet; }

    /** Child work may retain its already-issued view after ordinary acquisitions stop. */
    public ServingLease fork() {
      synchronized (servingViewMonitor) {
        if (released.get()) throw new IllegalStateException("Serving lease already released");
        captured.holders++;
        return new ServingLease(captured);
      }
    }

    /** Long-lived calls can end and reconnect when this physical view stops serving. */
    public Runnable onRetirement(Runnable listener) {
      Objects.requireNonNull(listener, "listener");
      boolean alreadyRetiring;
      synchronized (servingViewMonitor) {
        alreadyRetiring = captured.retiring;
        if (!alreadyRetiring) captured.retirementListeners.add(listener);
      }
      if (alreadyRetiring) listener.run();
      return () -> {
        synchronized (servingViewMonitor) { captured.retirementListeners.remove(listener); }
      };
    }

    @Override public void close() {
      if (!released.compareAndSet(false, true)) return;
      synchronized (servingViewMonitor) {
        captured.holders--;
        servingViewMonitor.notifyAll();
      }
      cleanRetiredServingView(captured);
    }
  }
  private volatile boolean closeStarted;
  private volatile boolean migrationEnumeratorDone;
  private volatile Throwable migrationEnumeratorFailure;

  // Package-private: accessed by DevReloadManager for hot-reload (tempdoc 305 Phase 2)
  WorkerSignalBus signalBus;
  private final WorkerSignalBus injectedSignalBus;
  private final io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry;
  private JobQueue jobQueue;

  // Tempdoc 550 Thesis II / 575 §4.3b (liveness): periodic reaper re-queues PROCESSING rows orphaned by a
  // dead worker. The loop now heartbeats its rows (JobQueue.heartbeatProcessing), so a fresh last_updated
  // means a LIVE OWNER — letting the window tighten from 15 min to a true 5-min liveness window.
  // Generated from the register (tempdoc 575 §17 Face A) — single source, cannot drift.
  private static final long STALE_PROCESSING_MS = LivenessWindows.REAPER_STALE_MS; // 5 min liveness window
  private static final long REAP_INTERVAL_MS = 2 * 60_000L; // check every 2 min (poll cadence, not a window)
  private java.util.concurrent.ScheduledExecutorService stuckJobReaper;
  private java.util.concurrent.ScheduledFuture<?> stuckJobReapTask;
  private java.util.concurrent.ExecutorService deferredModelExecutor;
  // Tempdoc 419 / T5.1 (ADR-0028): scoped reverse-lookup store. Constructed in init() against
  // the same jobs.db as JobQueue; closed in shutdown alongside JobQueue.
  private io.justsearch.indexerworker.queue.SqlitePathResolutionStore pathResolutionStore;
  private io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore documentIdentityStore;
  private volatile LuceneRuntime searchLifecycle;
  private volatile LuceneRuntime ingestLifecycle;
  EmbeddingService embeddingService;
  EmbeddingCompatibilityController embeddingCompatController;
  volatile WorkerAppServices appServices;
  private WorkerServiceConfiguration serviceConfiguration;

  /**
   * Tempdoc 885 item 3: the foreground-load gauge and the duty-cycle policy that reads it. Both are
   * process-scoped and owned here rather than by {@code appServices}, because the app services are
   * reconstructed (deferred-runtime upgrade, dev hot-reload) while this server — and therefore the
   * single producer that feeds the gauge ({@code ForegroundLoadGate} since item A9, the gRPC
   * {@code ForegroundLoadInterceptor} before it) — is not. A per-appServices gauge would be orphaned
   * from its only producer on the first reconstruction and silently stop throttling.
   */
  private final ForegroundLoad foregroundLoad =
      new ForegroundLoad();

  private volatile IndexingPacing indexingPacing =
      IndexingPacing.unthrottled();
  io.justsearch.indexerworker.disambiguation.DisambiguationService disambiguationService;
  io.justsearch.indexerworker.ner.NerService nerServiceInstance;
  io.justsearch.indexerworker.splade.SpladeEncoder spladeEncoderInstance;
  io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder spladeIdfQueryEncoder;
  io.justsearch.indexerworker.bgem3.BgeM3Encoder bgeM3EncoderInstance;
  io.justsearch.reranker.CrossEncoderReranker searchRerankerInstance;
  io.justsearch.reranker.CitationScorer citationScorerInstance;
  // Tempdoc 397 §14.26 T2-C1/C2: surface returned by InferenceCompositionRoot.compose;
  // owns SessionHandle lifetimes closed on shutdown.
  volatile InferenceSurface inferenceSurface;
  private volatile EncoderSet initialEncoderSet;
  // Phase 3c: WorkerOpsMetricCatalog replaces all worker.* gauge / observable-counter fields.
  // Catalog instance is retained for lifetime; OTel async callbacks fire at flush time.
  @SuppressWarnings("unused")
  private io.justsearch.indexerworker.services.WorkerOpsMetricCatalog workerOpsCatalog;
  /**
   * Released only after {@link #close()} reaches its final resource-release step. EngineRoot
   * checks {@link #awaitClosed(long)} to distinguish completed teardown from an interrupted close.
   */
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  InfraContext infraCtx; // package-private: DevReloadManager
  volatile CompletableFuture<ModelContext> deferredModelInit; // package-private: DevReloadManager
  private DevReloadManager devReloadManager;
  private io.justsearch.telemetry.TracingBootstrap tracingBootstrap;
  private Thread sentinelThread;
  private Thread migrationEnumeratorThread;
  private Thread migrationCutoverThread;
  private volatile boolean running;
  private volatile Consumer<Throwable> terminalWriterFaultHandler =
      failure -> log.error("Terminal writer failure has no Engine fault handler", failure);
  private volatile Runnable migrationRestartAction =
      () -> log.warn("Promoted generation requires an Engine restart; no process owner is installed");
  private int migrationCutoverMaxFailedJobs = -1;
  /**
   * Tempdoc 819: set when this boot started a corruption-recovery rebuild (the active generation
   * was recovered to EMPTY and a green is being rebuilt from source). Read once, when the embedding
   * compatibility controller is constructed, to waive its zero-evidence stamp refusal — see
   * {@code EmbeddingCompatibilityController.permitStampWithoutEmbeddingEvidence}.
   */
  private boolean corruptionRecoveryRebuildStarted;

  /**
   * Set when this boot found a schema mismatch it has already tried to rebuild away
   * {@link IndexGenerationManager#MAX_AUTO_REBUILD_ATTEMPTS} times. The Worker then serves the
   * existing index READ-ONLY and does not ingest: it must still finish starting, because a
   * {@code start()} that returns early never publishes its services and leaves the caller with no
   * explanation — the same silent dead-end the old FAIL_CLOSED default produced, which is the
   * whole thing this brake exists to avoid (tempdoc 915 §C.8). Before item A9 the same dead-end
   * read as "never binds gRPC, never writes its port".
   *
   * <p>The durable form of this state is {@code auto_rebuild_*} in {@code state.json}; this field is
   * only the in-process consequence for the remainder of {@code start()}.
   */
  private volatile boolean rebuildBrakeExhausted;

  /**
   * Whether this boot took the exhausted-brake path. A test seam, and a necessary one: every
   * externally visible consequence of that path (a bound port, a served search, a status state
   * read out of state.json) is ALSO true of an ordinary boot, so a test asserting only those
   * passes whether or not the branch ran — which is exactly how the first version of
   * BrakeExhaustedWorkerServesReadOnlyTest passed against a restored early return.
   */
  boolean rebuildBrakeExhaustedForTest() {
    return rebuildBrakeExhausted;
  }

  /** Read-only runtimes constructed so far. See {@link #readOnlyOpensForTest()}. */
  private final AtomicInteger readOnlyOpens = new AtomicInteger();

  /**
   * How many read-only runtimes this boot constructed. A test seam for a defect with no other
   * observable: opening Blue a second time and overwriting the field leaks a {@code Directory} +
   * {@code SearcherManager} that nothing will ever close, and a leaked handle is invisible to every
   * assertion a passing boot can make (tempdoc 915 B5 ride-along).
   */
  int readOnlyOpensForTest() {
    return readOnlyOpens.get();
  }

  // Migration enumerator progress (best-effort observability)
  private final AtomicBoolean migrationEnumeratorRunning = new AtomicBoolean(false);
  private final AtomicLong migrationEnumeratorRootsTotal = new AtomicLong(0L);
  private final AtomicLong migrationEnumeratorRootsDone = new AtomicLong(0L);
  private final AtomicLong migrationEnumeratorFilesSeen = new AtomicLong(0L);
  private final AtomicLong migrationEnumeratorFilesEnqueued = new AtomicLong(0L);
  private final AtomicLong migrationEnumeratorStartedAtMs = new AtomicLong(0L);
  private final AtomicLong migrationEnumeratorFinishedAtMs = new AtomicLong(0L);
  private final AtomicReference<String> migrationEnumeratorLastPath = new AtomicReference<>("");
  private MigrationProgressStore migrationProgressStore;
  private volatile MigrationProgressSnapshot persistedMigrationProgressSnapshot;

  /**
   * Gate released by {@link #initDeferredModels()} after all models are wired
   * (embedding + ECC + SPLADE + BGE-M3 + NER + reranker + citation scorer). The
   * {@code finally} block ensures the latch releases even if init fails partway through.
   *
   * <p><strong>Shared by two independent consumers</strong> — changes to the release
   * sequence must consider both:
   *
   * <ul>
   *   <li><b>Migration enumerator</b> (tempdoc 332) — the background thread at
   *       {@code migrationEnumeratorThread} awaits this latch before enqueuing files so
   *       the {@code IndexingLoop} does not process docs before SPLADE/embedding exist
   *       (would produce text-only docs needing slow RMW backfill post-cutover).</li>
   *   <li><b>Query handlers</b> (tempdoc 397 §14.28 U3) — wired via
   *       {@link io.justsearch.indexerworker.server.WorkerAppServices#wireModelReadyLatch}
   *       in {@link #initDeferredModels()}, consumed by
   *       {@code WorkerSearchService.awaitModelsReady(...)} on entry of
   *       {@code search}/{@code retrieveContext}/{@code rerank}/{@code matchCitations}.
   *       Closes a boot-race regression where queries arriving before init completed
   *       silently missed reranker + citation wiring.</li>
   * </ul>
   *
   * <p>Both consumers fall through to a degraded path on timeout (120 s). Splitting the
   * latch is possible but not currently warranted — the release point (all models wired)
   * is identical for both.
   */
  private final CountDownLatch modelReadyLatch = new CountDownLatch(1);

  // Late-binding fingerprint supplier for commit metadata overlay.
  // Set after EmbeddingCompatibilityController is created.
  private final AtomicReference<java.util.function.Supplier<java.util.Optional<String>>> embeddingFingerprintSupplier =
      new AtomicReference<>(java.util.Optional::empty);

  /**
   * Creates a new KnowledgeServer with the specified configuration and its own signal bus.
   *
   * <p>The bus it builds reads a gauge no other component writes, so nothing claims the GPU and
   * nothing reports an energy signal. That is the right answer for an index half composed on its
   * own; the Engine's composition root uses the two-argument constructor instead, because the
   * point of the gauge is that the Head-side writers and this reader share ONE instance.
   *
   * @param config Worker configuration
   */
  public KnowledgeServer(io.justsearch.core.execution.EngineExecutorRegistry executors, WorkerConfig config) {
    this(executors, config, null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  /**
   * Creates a new KnowledgeServer with an externally-supplied signal bus (lane F stage A item A6).
   *
   * <p>When the index half runs inside the Engine JVM there is no second process, so a
   * memory-mapped bus would have nothing to carry: the composition root passes an
   * {@code InProcessWorkerSignalBus} over the shared {@code GpuSchedulingGauge} instead. Item A10
   * deleted the memory-mapped implementation and the suicide pact with it, so this parameter is now
   * about gauge identity rather than transport: a bus built here would read a gauge the Head-side
   * writers never touch, and the index half would never yield the GPU.
   *
   * @param config Worker configuration
   * @param signalBus the bus to use, or {@code null} to build one over a private gauge
   */
  public KnowledgeServer(io.justsearch.core.execution.EngineExecutorRegistry executors, WorkerConfig config, WorkerSignalBus signalBus) {
    this(executors, config, signalBus, io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  private final io.justsearch.core.execution.EngineExecutorRegistry executors;
  private final WorkerExecutorRegistrations workerExecutors;
  private final RecordedIngestionLifecycle recordedIngestionLifecycle;
  private final io.justsearch.core.component.ComponentHandle indexComponent;
  private final io.justsearch.core.component.ComponentHandle encoderComponent;
  private RecordedIngestionLifecycle.Attachment recordedIngestionAttachment;
  private volatile ResolvedConfig startupConfiguration;
  /** Accepted B configuration, independent of the A snapshot while Green is built. */
  private volatile RecordedIngestionLifecycle.RecordedCandidate recordedCandidate;
  private volatile io.justsearch.indexerworker.services.CandidateIndexTargetCapture.CaptureResult
      recordedCandidateFingerprint;
  private WorkerServiceConfiguration candidateServiceConfiguration;
  private volatile CandidateModels candidateModels;
  private volatile EmbeddingCompatibilityController candidateEmbeddingCompatController;

  /** B's native/model view, independent of the model wrappers serving A during its build. */
  private record CandidateModels(
      EncoderSet owner,
      EmbeddingService embedding,
      EncoderBindings.Snapshot bindings,
      io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder spladeIdf,
      io.justsearch.reranker.CrossEncoderReranker reranker,
      io.justsearch.reranker.CitationScorer citation,
      GpuDiagnosticSuppliers diagnostics) {}
  private final java.util.function.Supplier<ResolvedConfig> liveConfiguration;
  private final io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations luceneExecutors;

  public KnowledgeServer(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      WorkerConfig config,
      WorkerSignalBus signalBus,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    this(executors, config, signalBus, childRegistry, RecordedIngestionLifecycle.denied());
  }

  public KnowledgeServer(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      WorkerConfig config,
      WorkerSignalBus signalBus,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      RecordedIngestionLifecycle recordedIngestionLifecycle) {
    this(executors, config, signalBus, childRegistry, recordedIngestionLifecycle, null, null);
  }

  /** Process composition supplies stable handles retained across physical index replacement. */
  public KnowledgeServer(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      WorkerConfig config,
      WorkerSignalBus signalBus,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      RecordedIngestionLifecycle recordedIngestionLifecycle,
      io.justsearch.core.component.ComponentHandle indexComponent,
      io.justsearch.core.component.ComponentHandle encoderComponent) {
    this(executors, config, signalBus, childRegistry, recordedIngestionLifecycle,
        indexComponent, encoderComponent, null);
  }

  public KnowledgeServer(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      WorkerConfig config,
      WorkerSignalBus signalBus,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      RecordedIngestionLifecycle recordedIngestionLifecycle,
      io.justsearch.core.component.ComponentHandle indexComponent,
      io.justsearch.core.component.ComponentHandle encoderComponent,
      ResolvedConfig startupConfiguration) {
    this(executors, config, signalBus, childRegistry, recordedIngestionLifecycle,
        indexComponent, encoderComponent, startupConfiguration, null);
  }

  /** Hot auxiliary settings read the same authority captured by the process composition. */
  public KnowledgeServer(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      WorkerConfig config,
      WorkerSignalBus signalBus,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      RecordedIngestionLifecycle recordedIngestionLifecycle,
      io.justsearch.core.component.ComponentHandle indexComponent,
      io.justsearch.core.component.ComponentHandle encoderComponent,
      ResolvedConfig startupConfiguration,
      java.util.function.Supplier<ResolvedConfig> liveConfiguration) {
    this(executors, config, signalBus, childRegistry, recordedIngestionLifecycle,
        indexComponent, encoderComponent, startupConfiguration, liveConfiguration,
        new ReentrantReadWriteLock());
  }

  /** Process composition shares the exact configuration/component publication lock. */
  public KnowledgeServer(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      WorkerConfig config,
      WorkerSignalBus signalBus,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      RecordedIngestionLifecycle recordedIngestionLifecycle,
      io.justsearch.core.component.ComponentHandle indexComponent,
      io.justsearch.core.component.ComponentHandle encoderComponent,
      ResolvedConfig startupConfiguration,
      java.util.function.Supplier<ResolvedConfig> liveConfiguration,
      ReentrantReadWriteLock publicationLock) {
    this.publicationLock = Objects.requireNonNull(publicationLock, "publicationLock");
    this.startupConfiguration = startupConfiguration;
    this.liveConfiguration = liveConfiguration;
    this.indexComponent = indexComponent;
    this.encoderComponent = encoderComponent;
    this.recordedIngestionLifecycle = Objects.requireNonNull(recordedIngestionLifecycle,
        "recordedIngestionLifecycle");
    this.executors = Objects.requireNonNull(executors, "executors");
    this.config = config;
    this.dataDir = config.dataDir();
    this.injectedSignalBus = signalBus;
    this.childRegistry = Objects.requireNonNull(childRegistry, "childRegistry");
    this.luceneExecutors = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors);
    try { this.workerExecutors = new WorkerExecutorRegistrations(executors); }
    catch (RuntimeException | Error failure) {
      try { luceneExecutors.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  /** Installs the whole-Engine owner for an irrecoverably closed active Lucene writer. */
  public void onTerminalWriterFailure(Consumer<Throwable> handler) {
    terminalWriterFaultHandler = Objects.requireNonNull(handler, "handler");
  }

  /** Bind before start: a resumed migration may promote during startup. */
  public void onMigrationRestart(Runnable action) {
    migrationRestartAction = Objects.requireNonNull(action, "action");
  }

  private void bindTerminalWriterFaultSource(RunningRuntime source) {
    source.onTerminalWriterFailure(failure -> terminalWriterFaultHandler.accept(failure));
  }

  /** Publishes an ingest runtime only after its terminal-writer owner is installed. */
  void publishIngestLifecycle(LuceneRuntime runtime) {
    if (runtime instanceof RunningRuntime runningRuntime) {
      bindTerminalWriterFaultSource(runningRuntime);
    }
    this.ingestLifecycle = runtime;
  }

  /**
   * Seeds the jobs.db identity table from the serving index before indexing starts.
   *
   * <p>The scan runs only when the store carries no identity at all, or when
   * {@code document_identity_import} has no row for the serving generation. After the first import
   * every parent written through admission resolves its uid through the store, and a Green built by
   * migration re-ingests through the store rather than copying Blue's stored fields — so the scan
   * only ever seeds an index predating the store or a wiped/restored {@code jobs.db}. Existing
   * store rows stay authoritative in every case. Full rationale and streaming/skip semantics:
   * {@link DocumentIdentityBootImport}.
   */
  private void importDocumentIdentitiesFromActiveIndex(String activeGenerationId) {
    LuceneRuntime authority = searchLifecycle;
    if (authority == null || documentIdentityStore == null || activeGenerationId == null) {
      return;
    }
    DocumentIdentityBootImport.run(
        authority.documentFieldOps()::scanParentDocumentIdentities,
        documentIdentityStore,
        activeGenerationId,
        System.currentTimeMillis());
  }

  /**
   * Starts the Knowledge Server and all its components.
   *
   * <p>Initialization order:
   * <ol>
   *   <li>Open the signal bus (the in-process one under the Engine; the memory-mapped one on the
   *       standalone path, until item A10 deletes it)</li>
   *   <li>Open job queue (SQLite)</li>
   *   <li>Initialize Lucene runtime</li>
   *   <li>Resolve the embedding compatibility controller — must precede any commit (tempdoc 819)</li>
   *   <li>Start indexing loop</li>
   *   <li>Start sentinel thread (liveness monitor)</li>
   * </ol>
   *
   * @throws IOException if initialization fails
   */
  public void start() throws IOException {
    log.info("Starting KnowledgeServer...");
    if (indexComponent != null) {
      indexComponent.transition(io.justsearch.core.component.ComponentState.STARTING,
          io.justsearch.app.api.lifecycle.LifecycleReasonCode.WORKER_STARTING.code(), null);
    }
    running = true;
    long t0 = System.nanoTime();
    long tPrev = t0;
    long tPhase;

    try {
      // 0. Initialize Worker-owned telemetry (must not write to the Head metrics file).
      if (startupConfiguration == null) startupConfiguration = ConfigStore.global().get();
      // Tempdoc 417 Phase 1: register IndexRuntimeMetricCatalog.DEFINITIONS so the SDK builds
      // per-metric Views (tag schemas + bucket bounds + exemplar policies) before the
      // SdkMeterProvider is built. F2 fix: catalog has only a registry-arg constructor (final
      // fields), so we wrap the static DEFINITIONS list as a definitions-only catalog and
      // construct the typed catalog after LocalTelemetry exists.
      LocalTelemetry workerTelemetry =
          new LocalTelemetry(
              executors, dataDir,
              config.telemetryFlushMs(),
              "justsearch-worker",
              config.serviceVersion(),
              "metrics-worker.ndjson",
              List.of(
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.services.IndexRuntimeMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.services.IndexRuntimeMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.loop.IndexingPipelineMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.loop.IndexingPipelineMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.extract.ExtractionMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.extract.ExtractionMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.extract.OcrMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.extract.OcrMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.services.WorkerOpsMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.services.WorkerOpsMetricCatalog.DEFINITIONS),
                  // Tempdoc 417 → 410 merge: ingestion.outcome_write_failures_total
                  // (introduced by 410 Slice A2) routes through IngestionOutcomeMetricCatalog.
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.loop.IngestionOutcomeMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.loop.IngestionOutcomeMetricCatalog.DEFINITIONS),
                  // Tempdoc 417 → 418 merge: WorkerMethvinWatcher emits index.watcher.events_total
                  // with a worker-specific tag schema (component + kind) via
                  // WorkerWatcherMetricCatalog. The Head-side WatcherMetricCatalog in app-indexing
                  // remains for the (now-deprecated) head-side watcher path; the metric name is
                  // shared but per-process tag schemas differ.
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.services.WorkerWatcherMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.services.WorkerWatcherMetricCatalog.DEFINITIONS),
                  // Tempdoc 414: ort.session.* lifecycle metrics for every NativeSessionHandle
                  // instance (one per encoder). Adapter constructed below; threaded into
                  // InferenceCompositionRoot.compose so OrtSessionAssembler.buildManager picks it up.
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.observability.OrtSessionMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.observability.OrtSessionMetricCatalog.DEFINITIONS),
                  // Tempdoc 413: embedding.runtime.* metrics for EmbeddingService lifecycle
                  // (cache hit/miss/size, invoke failures, hot-unload, chunked branch).
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.embed.EmbeddingMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.embed.EmbeddingMetricCatalog.DEFINITIONS),
                  // Phase 3d: JVM gauges flow through JvmMetricCatalog. Namespace is the prefix
                  // baked into the metric names ("worker"), so the per-View archive declarations
                  // and tag schemas are wired at SDK boot.
                  io.justsearch.telemetry.JvmMetricCatalog.catalogFor("worker")));
      // Phase 3b: status gauges read from RunningRuntime when available; supplier is invoked
      // at every flush, so it tolerates `ingestLifecycle` being null/DeferredRuntime at
      // bootstrap and switches to the live snapshot once RunningRuntime is constructed.
      this.indexRuntimeCatalog =
          new io.justsearch.indexerworker.services.IndexRuntimeMetricCatalog(
              workerTelemetry.registry(),
              () -> {
                LuceneRuntime r = this.ingestLifecycle;
                if (r instanceof RunningRuntime running) {
                  return running.runtimeGaugesSnapshot();
                }
                return LuceneRuntimeTypes
                    .RuntimeGaugesSnapshot.EMPTY;
              });
      // Tempdoc 414: typed events recorder for ORT session lifecycle. Cache pre-populated
      // for every EncoderRole.values() consumer name so onSemaphoreWait never allocates on the
      // hot path. forAllRoles() factory derives the consumer set from EncoderRole — single
      // source of truth (tempdoc 414 v2 C1 fix).
      var ortSessionCatalog =
          new io.justsearch.indexerworker.observability.OrtSessionMetricCatalog(
              workerTelemetry.registry());
      this.ortSessionEvents =
          io.justsearch.indexerworker.observability.OrtSessionTelemetryAdapter.forAllRoles(
              ortSessionCatalog);
      // Tempdoc 413: cache-size supplier reads through the deferred this.embeddingService
      // field — null until initDeferredModels constructs the service. The supplier is invoked at
      // every OTel flush, so it tolerates the boot-time null case (returns 0L).
      this.embeddingMetricCatalog =
          new io.justsearch.indexerworker.embed.EmbeddingMetricCatalog(
              workerTelemetry.registry(),
              () -> {
                EmbeddingService es = this.embeddingService;
                return es != null ? (long) es.cacheSize() : 0L;
              });
      this.embeddingTelemetry =
          new io.justsearch.indexerworker.embed.EmbeddingTelemetry(this.embeddingMetricCatalog);
      telemetry = workerTelemetry;

      // 0b. Initialize tracing (must happen before service class loading at step 3b).
      // Use the same resolved snapshot captured for this physical index start.
      //
      // Lane F review S13: there is ONE GlobalOpenTelemetry per JVM, and since item A6 the Head and
      // the index half share one. Whichever bootstrap runs first wins, and the Head's runs first
      // (its API phase precedes this start()). So in the Engine, JUSTSEARCH_INDEX_TRACING_LEVEL
      // does not decide anything on its own — it decides only when the Head declined to register,
      // which happens when JUSTSEARCH_HEAD_TRACING_LEVEL is 'none'. That coupling is real and
      // stage A does not resolve it (the two levels become one key when the manifest's worker
      // projection is re-cut in stage B); what it must not do is happen silently. The catch below
      // used to log at DEBUG under the Worker's INFO threshold, so an operator who set the index
      // level and saw no indexing spans had nothing to read.
      String tracingLevel = startupConfiguration.index().tracingLevel();
      if (!"none".equals(tracingLevel)) {
        try {
          tracingBootstrap = io.justsearch.telemetry.TracingBootstrap.forIndexing(
              dataDir, ((LocalTelemetry) telemetry).getHealthState(), tracingLevel);
          log.info("Worker tracing initialized: level={}", tracingLevel);
        } catch (IllegalStateException e) {
          log.info(
              "Index tracing level '{}' is not in effect: OpenTelemetry is already registered in"
                  + " this JVM by the Head (JUSTSEARCH_HEAD_TRACING_LEVEL governs both halves of"
                  + " the Engine). Indexing spans are emitted at the Head's level, or not at all if"
                  + " that level is 'none'. ({})",
              tracingLevel,
              e.getMessage());
        }
      }

      tPhase = System.nanoTime();
      long telemetryMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 1. Initialize signal bus.
      // Lane F item A6: the Engine composition root supplies the in-process bus over the gauge it
      // shares with the Head-side writers. Item A10 deleted the memory-mapped alternative, so the
      // fallback below is the same implementation over a gauge nobody else writes — the honest
      // reading for an index half composed on its own (tests, a standalone boot): no GPU claim, no
      // energy signal, and therefore no yielding.
      if (injectedSignalBus != null) {
        signalBus = injectedSignalBus;
      } else {
        signalBus = new InProcessWorkerSignalBus(new GpuSchedulingGauge());
      }
      signalBus.open();

      tPhase = System.nanoTime();
      long signalBusMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 2. Initialize job queue (with corruption triage). Tempdoc 417 Phase 3c: write-failure
      // callback late-binds to WorkerOpsMetricCatalog (constructed later in
      // registerTelemetryGauges); the lambda silently no-ops until the catalog is wired.
      Path dbPath = dataDir.resolve("jobs.db");
      Runnable onSwitchBufferWriteFailure = () -> {
        var c = this.workerOpsCatalog;
        if (c != null) {
          c.switchBufferWriteFailures.increment(io.justsearch.telemetry.catalog.EmptyTags.INSTANCE);
        }
      };
      // Tempdoc 885 item 21d: the cap lives in ONE place. This was a bare literal `3` that agreed
      // with SqliteJobQueue.DEFAULT_MAX_ATTEMPTS only by coincidence.
      SqliteJobQueue sqliteQueue =
          new SqliteJobQueue(dbPath, recordedIngestionLifecycle::recordedClaimDecision,
              SqliteJobQueue.DEFAULT_MAX_ATTEMPTS, onSwitchBufferWriteFailure);
      // Tempdoc 885 item 21e: per-outcome counters. Late-bound like the write-failure callback
      // above — the catalog does not exist until registerTelemetryGauges runs.
      sqliteQueue.setOutcomeObserver(
          outcomeClass -> {
            var c = this.workerOpsCatalog;
            if (c != null) {
              c.jobQueueOutcomeTotal.increment(
                  new io.justsearch.indexerworker.services.QueueOutcomeTags(outcomeClass));
            }
          });
      jobQueue = sqliteQueue;
      try {
        jobQueue.open();
      } catch (SQLException e) {
        // Check for database corruption (SQLite error code 11 = SQLITE_CORRUPT)
        if (e.getErrorCode() == 11) {
          log.error("Database corruption detected in jobs.db. Initiating triage...", e);
          try {
            jobQueue.close();
          } catch (IOException closeEx) {
            log.warn("Failed to close corrupt database handle", closeEx);
          }
          handleCorruptDatabase(dbPath);
        } else {
          throw e;
        }
      }
      tPhase = System.nanoTime();
      long jobQueueMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 3. Resolve generation-scoped index path BEFORE opening Lucene.
      // Production root captured this once; legacy fixture constructors capture at first start.
      ResolvedConfig rc = startupConfiguration;
      if (rc == null) {
        throw new IllegalStateException("ConfigStore not initialized — cannot start KnowledgeServer");
      }

      this.migrationCutoverMaxFailedJobs = rc.index().migrationCutoverMaxFailedJobs();

      Path effectiveIndexBasePath = rc.paths().indexBasePath();

      // Acquire a lock for the effective index root to prevent two Workers from mutating the same
      // indexBasePath (important when justsearch.index.base_path is overridden).
      this.indexRootLock = new IndexRootLock(effectiveIndexBasePath);
      this.indexRootLock.acquire();

      var bootOwnership = recordedIngestionLifecycle.bootOwnership(jobQueue);
      var candidate = bootOwnership instanceof IndexGenerationManager.BootOwnership.Recorded recorded
          ? recordedIngestionLifecycle.recordedCandidate(recorded.operationKey())
          : java.util.Optional.<RecordedIngestionLifecycle.RecordedCandidate>empty();
      this.recordedCandidateFingerprint = null;
      if (candidate.isPresent()) {
        var exact = candidate.orElseThrow();
        var recorded = (IndexGenerationManager.BootOwnership.Recorded) bootOwnership;
        var captured = io.justsearch.indexerworker.services.CandidateIndexTargetCapture
            .captureWithRuntimeInputs(exact.configuration());
        if (!exact.target().equals(captured.target())
            || !exact.target().fingerprint().equals(recorded.targetFingerprint())
            || !exact.configuration().paths().indexBasePath().equals(effectiveIndexBasePath)) {
          throw new IOException("Recorded candidate differs from its generation owner");
        }
        this.recordedCandidateFingerprint = captured;
      }
      this.recordedCandidate = candidate.orElse(null);

      // The embedding and SPLADE model digests are index_fingerprint inputs, but they are only
      // knowable in the Worker's model modules. Install them process-wide BEFORE the first commit
      // metadata is built, so the commit path, the parity guard's expected snapshot and the
      // green-cutover verification all compute the same fingerprint (tempdoc 915 §C). Tri-state:
      // a resolvable model file with an unreadable digest is INDETERMINATE, not absent.
      IndexFingerprint.installModelFingerprintProviders(
          () ->
              IndexFingerprint.ModelFingerprint.of(
                  EmbeddingFingerprint.modelPath().isPresent(), EmbeddingFingerprint.get()),
          () ->
              IndexFingerprint.ModelFingerprint.of(
                  SpladeFingerprint.modelPath().isPresent(), SpladeFingerprint.get()),
          () ->
              IndexFingerprint.ModelFingerprint.of(
                  io.justsearch.indexerworker.ner.NerFingerprint.modelPath().isPresent(),
                  io.justsearch.indexerworker.ner.NerFingerprint.get()));

      // The effective vector dimension is the other input only this process knows, and it has to be
      // installed HERE rather than inside buildIndexRuntime: pre-open detection (below) computes the
      // expected fingerprint before any runtime is built, and a dimension installed later would make
      // the boot-time comparison disagree with every later one under BGE-M3.
      IndexFingerprint.installEffectiveVectorDimension(effectiveVectorDimensionSupplier());

      IndexGenerationManager genManager = new IndexGenerationManager(effectiveIndexBasePath);
      var boot = genManager.initializeForBoot(bootOwnership,
          candidate.map(value -> value.target().fingerprint()).orElseGet(this::expectedIndexFingerprintOrNull));
      if (candidate.isPresent()
          && (boot.disposition() == IndexGenerationManager.BootDisposition.BUILDING
              || boot.disposition() == IndexGenerationManager.BootDisposition.PROMOTED)) {
        var recorded = (IndexGenerationManager.BootOwnership.Recorded) bootOwnership;
        genManager.bindRecordedModels(recorded.operationKey(), recorded.source(),
            recorded.targetFingerprint(), candidate.orElseThrow().models());
      }
      IndexGenerationManager.IndexLayout layout = boot.layout();
      this.generationBootOwnership = bootOwnership;
      this.generationBootDisposition = boot.disposition();
      this.indexGenerationManager = genManager;
      this.indexBasePath = layout.basePath();
      this.activeIndexPath = layout.activeGenerationPath();
      this.migrationProgressStore = new MigrationProgressStore(this.indexBasePath);
      this.persistedMigrationProgressSnapshot = this.migrationProgressStore.readBestEffort();
      IndexGenerationManager.State state = layout.state();
      this.buildingIndexPath = null;
      this.migrationEnumeratorDone = false;
      this.migrationEnumeratorFailure = null;

      logConfiguration();


      // 4. Initialize Lucene runtimes.
      // - searchLifecycle serves queries (Blue during migration)
      // - ingestLifecycle performs all writes (Green during migration; Active when not migrating)
      //
      // If a migration is already in progress, honor state.json and wire Blue/Green accordingly.
      IndexGenerationManager.MigrationState ms =
          parseMigrationState(state == null ? null : state.migration_state());
      String buildingGenId = state == null ? null : state.building_generation();
      boolean inProgress =
          (ms == IndexGenerationManager.MigrationState.MIGRATING
              || ms == IndexGenerationManager.MigrationState.SWITCHING
              || ms == IndexGenerationManager.MigrationState.FAILED)
              && buildingGenId != null
              && !buildingGenId.isBlank();

      // Create a late-binding supplier for the fingerprint overlay
      java.util.function.Supplier<java.util.Optional<String>> fpSupplier =
          () -> embeddingFingerprintSupplier.get().get();

      // The schema-mismatch handling below covers BOTH branches. It used to wrap only the normal
      // one, so a resumed migration whose Green was itself mismatched threw straight out of start()
      // — no policy branch, no brake, no read-only fall-through — which is the repeat-failure shape
      // the brake exists to bound (tempdoc 915 §C.8). Wrapping it was necessary and not sufficient:
      // the handler ABANDONS a mismatched Green before retrying, because startMigration() no-ops on
      // an in-flight migration and would otherwise hand back the same broken generation (§C.14, B5).
      // Tempdoc 915 §C.12 (open item O7). Decide whether this index has the shape this runtime
      // writes BEFORE choosing how to open it. The check used to live inside the open, where a
      // deferred open arrives as read-only and ComponentsFactory only logs the mismatch — so on the
      // boot path most installs take (an existing index WITH segments) the automatic migration was
      // never triggered at all. Reading the last commit's user data needs no writer and no
      // RuntimeSession, so it can happen here, where the policy handler below can act on it.
      //
      // Same predicate, same call: ParityDiagnostics via the guard's own inspection helper. The
      // legacy-blank rule, the empty-index exclusion and the model tri-state therefore apply
      // identically at both sites, by construction rather than by agreement.
      boolean preOpenMismatch = false;
      if (generationBootDisposition == IndexGenerationManager.BootDisposition.NATIVE && !inProgress) {
        var preOpenDiffs =
            IndexMetadataParityGuard.inspectCommittedParity(
                activeIndexPath, () -> expectedCommitMetadata(fpSupplier));
        preOpenMismatch = ParityDiagnostics.requiresRebuild(preOpenDiffs);
        if (preOpenMismatch) {
          for (var diff : preOpenDiffs) {
            log.warn("PRE-OPEN {}", diff.marker());
          }
        }
      }

      // Blue, once it is open read-only. Held so the schema-mismatch handler can REUSE it instead
      // of opening a second runtime on the same directory: the resumed-migration branch below opens
      // Blue before it touches Green, and overwriting that field without closing it leaked a
      // Directory + SearcherManager holding Windows file handles on Blue for the Worker's whole
      // lifetime — in the exhausted-brake state, which is precisely the state that keeps running
      // (tempdoc 915 B5 ride-along).
      LuceneRuntime blueReadOnly = null;
      if (generationBootDisposition != IndexGenerationManager.BootDisposition.NATIVE) {
        if (generationBootDisposition == IndexGenerationManager.BootDisposition.PROMOTED) {
          publishIngestLifecycle(buildIndexRuntime(activeIndexPath, fpSupplier).withoutRecovery()
              .withBuildState(LuceneRuntimeTypes.BuildState.COMPLETE).open());
          this.searchLifecycle = this.ingestLifecycle;
        } else {
          this.searchLifecycle = buildReadOnlyRuntime(activeIndexPath).withoutRecovery().openReadOnly();
          if (generationBootDisposition == IndexGenerationManager.BootDisposition.BUILDING) {
            this.buildingIndexPath = genManager.resolveGenerationPathStrict(buildingGenId);
            publishIngestLifecycle(buildIndexRuntime(buildingIndexPath, fpSupplier).withoutRecovery()
                .withBuildState(LuceneRuntimeTypes.BuildState.BUILDING).open());
            this.migrationEnumeratorDone = true;
          } else {
            publishIngestLifecycle(this.searchLifecycle);
          }
        }
      } else {
      try {
      if (inProgress) {
        // Serve search from active generation (Blue) while writing to building generation (Green).
        blueReadOnly = buildReadOnlyRuntime(activeIndexPath).openReadOnly();
        this.searchLifecycle = blueReadOnly;
        this.buildingIndexPath = genManager.resolveGenerationPathStrict(buildingGenId);
        publishIngestLifecycle(
            buildIndexRuntime(buildingIndexPath, fpSupplier)
                .withBuildState(LuceneRuntimeTypes.BuildState.BUILDING)
                .open());
      } else {
        // Normal operation: single runtime against active generation.
        //
        // Tempdoc 406 Phase 4a: deferred-writer mode is re-enabled. When the index
        // has existing segments, openDeferred() opens read-only first (fast); the
        // background initDeferredModels later calls DeferredRuntime.prepareWriterUpgrade()
        // which returns a fresh RunningRuntime. KS reconstructs and republishes
        // appServices via reconstructAppServicesAfterDeferredUpgrade() so write
        // methods become available without restarting this server.
        // A detected mismatch is raised here for the two policies whose handling lives in the
        // catch below. REBUILD_BACKUP_FIRST is deliberately NOT raised: its backup-then-rebuild
        // recovery lives inside RuntimeSession.openComponentsWithRecovery and is the one
        // implementation of that policy — but it only runs on a WRITABLE open, so the pre-open
        // detection's job there is to force one rather than to duplicate the recovery.
        String policy = rc.index().schemaMismatchPolicy();
        boolean policyHandledInCatch =
            "blue_green_migrate".equalsIgnoreCase(policy) || "fail_closed".equalsIgnoreCase(policy);
        if (preOpenMismatch && policyHandledInCatch) {
          throw IndexMetadataParityGuard.schemaMismatch();
        }
        boolean useDeferredWriter = hasLuceneSegments(activeIndexPath) && !preOpenMismatch;
        {
          LuceneRuntimeBuilder builder =
              buildIndexRuntime(activeIndexPath, fpSupplier)
                  .withBuildState(LuceneRuntimeTypes.BuildState.COMPLETE);
          publishIngestLifecycle(useDeferredWriter ? builder.openDeferred() : builder.open());
          this.searchLifecycle = this.ingestLifecycle;

          // tempdoc 628 Stage B (G3): if the adapter recovered this index to empty on open it dropped
          // a rebuild-pending marker. Rebuild from the source files still on disk (blue/green) rather
          // than serving a silently-empty index — unless policy is BACKUP_ONLY/FAIL_CLOSED.
          if (IndexRecoveryMarker.exists(activeIndexPath)
              && IndexRecoveryPolicy.shouldRebuildFromSource(rc.index())) {
            log.warn(
                "Index at {} was recovered to empty (reason={}). Rebuilding from source via blue/green...",
                activeIndexPath,
                IndexRecoveryMarker.readReason(activeIndexPath));
            this.searchLifecycle = buildReadOnlyRuntime(activeIndexPath).openReadOnly();
            IndexGenerationManager.State migrated = genManager.startMigration(MigrationSource.CORRUPT_INDEX_REBUILD.wire());
            String greenGenId = migrated == null ? null : migrated.building_generation();
            if (greenGenId == null || greenGenId.isBlank()) {
              throw new IOException(
                  "Failed to start corruption-recovery rebuild: building_generation missing");
            }
            this.ingestLifecycle.close();
            this.buildingIndexPath = genManager.resolveGenerationPathStrict(greenGenId);
            publishIngestLifecycle(
                buildIndexRuntime(buildingIndexPath, fpSupplier)
                    .withBuildState(LuceneRuntimeTypes.BuildState.BUILDING)
                    .open());
            startMigrationEnumeratorBestEffort(rc);
            IndexRecoveryMarker.clear(activeIndexPath);
            // Tempdoc 819: remember that BLUE is an index recovered to EMPTY. The green being built
            // here is rebuilt from source, so its vectors are current-model by construction — the
            // embedding fingerprint attestation is sound even if zero embeddings succeed. Without
            // this waiver the zero-evidence refusal would block the cutover, and since blue holds
            // nothing the user would be left with an empty index (unlike the normal cutover, where
            // keeping blue costs nothing). The marker was just cleared, so the recovery branch will
            // not re-fire; the FAILED migration does resume next boot (see the `inProgress` gate
            // above), but it resumes into the same refusal until the embedding runtime is fixed.
            this.corruptionRecoveryRebuildStarted = true;
          }

          // 312 Phase 4: Check for embedding model fingerprint mismatch.
          // If the on-disk model changed since the index was last committed, and
          // blue_green_migrate policy is enabled, start a migration to rebuild the
          // index with the new embedding model. This avoids the slow per-doc RMW
          // backfill path and instead re-ingests all files with inline embedding.
          String schemaMismatchPolicy = rc.index().schemaMismatchPolicy();
          // Use latestCommitUserDataBestEffort (not openTimeCommitUserData) because
          // openTimeCommitUserData captures from the reader at open time — which may not
          // include the fingerprint if the last commit before shutdown didn't stamp it.
          Map<String, String> commitMeta =
              this.ingestLifecycle.latestCommitUserDataBestEffort();
          String storedFp = commitMeta.get("embedding_model_sha256");
          if (buildingIndexPath == null
              && "blue_green_migrate".equalsIgnoreCase(schemaMismatchPolicy)) {
            if (storedFp != null && !storedFp.isBlank()) {
              java.util.Optional<String> currentFp = EmbeddingFingerprint.get();
              if (currentFp.isPresent() && !storedFp.equals(currentFp.get())) {
                log.warn(
                    "Embedding model fingerprint mismatch on active generation {}. "
                        + "Stored: {}..., Current: {}... Starting Blue/Green migration...",
                    activeIndexPath,
                    storedFp.substring(0, Math.min(16, storedFp.length())),
                    currentFp.get().substring(0, Math.min(16, currentFp.get().length())));

                // Reopen the active index read-only (Blue) for serving search.
                this.searchLifecycle = buildReadOnlyRuntime(activeIndexPath).openReadOnly();

                // Green: create a new generation and start a writable runtime.
                IndexGenerationManager.State migrated =
                    genManager.startMigration(MigrationSource.EMBEDDING_MODEL_CHANGE.wire());
                String greenGenId =
                    migrated == null ? null : migrated.building_generation();
                if (greenGenId == null || greenGenId.isBlank()) {
                  throw new IOException(
                      "Failed to start embedding migration: building_generation missing");
                }
                // Close the writable runtime on the old generation before opening Green.
                this.ingestLifecycle.close();
                this.buildingIndexPath = genManager.resolveGenerationPathStrict(greenGenId);
                publishIngestLifecycle(
                    buildIndexRuntime(buildingIndexPath, fpSupplier)
                        .withBuildState(LuceneRuntimeTypes.BuildState.BUILDING)
                        .open());

                startMigrationEnumeratorBestEffort(rc);
              }
            }
          }
        }
      }
      } catch (IndexRuntimeIOException e) {
        String schemaMismatchPolicy = rc.index().schemaMismatchPolicy();
          if (e.reason() == IndexRuntimeIOException.Reason.SCHEMA_MISMATCH
              && "blue_green_migrate".equalsIgnoreCase(schemaMismatchPolicy)) {
            // Repeat-rebuild brake. A green that never finishes leaves the same mismatch on the
            // next boot, so an unbounded auto-start rebuilds forever. The budget is per target
            // fingerprint, so a later, different upgrade is not refused for an earlier one's
            // failures. Recorded BEFORE the rebuild starts: a build that crashes the process must
            // still spend its attempt (tempdoc 915 §C).
            // A fingerprint we cannot compute must not be charged to a shared budget: without a
            // distinct sentinel every indeterminate boot would spend an attempt against the literal
            // string "null" and exhaust the brake for an unrelated real target.
            if (inProgress) {
              // The mismatch was raised opening GREEN, not Blue — a read-only open of Blue cannot
              // raise it, ComponentsFactory logs guard failures on read-only opens. So this boot
              // resumed a migration whose building generation carries a shape this runtime does not
              // write, and startMigration() no-ops while a migration is in flight. Without
              // discarding that Green first, the branch below re-resolved the SAME generation and
              // re-opened it: the second SCHEMA_MISMATCH was raised inside this catch, uncaught, and
              // killed start() on attempts 1-3 — three dead Workers before the brake could even
              // report anything (tempdoc 915 B5). Discard it, spend an attempt, build a fresh one.
              genManager.abandonBuildingGeneration(MigrationSource.SCHEMA_MISMATCH.wire());
              this.buildingIndexPath = null;
            }
            String targetFingerprint = expectedIndexFingerprintOrNull();
            int attempt = recordAutoRebuildAttemptOrSkip(genManager, targetFingerprint);
            LuceneRuntime blue =
                blueReadOnly != null
                    ? blueReadOnly
                    : buildReadOnlyRuntime(activeIndexPath).openReadOnly();
            if (attempt > IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS) {
              // Do NOT rethrow. Rethrowing fails start(), which is the same dead-end the old
              // FAIL_CLOSED default produced, three boots later and with no explanation. Open the
              // existing index read-only instead: search keeps working on what is already there,
              // and the status surface carries index.rebuild_brake_exhausted so a user is told why
              // ingestion has stopped (tempdoc 915 §C).
              log.error(
                  "Schema mismatch on active generation {}, but {} automatic rebuilds for the same"
                      + " target fingerprint have already been attempted. Serving the existing index"
                      + " read-only and STOPPING ingestion instead of rebuilding again. The Worker"
                      + " still starts and search keeps working; status reports"
                      + " index.rebuild_brake_exhausted. RECOVERY: run Rebuild index from the UI"
                      + " (core.rebuild-index) - a successful rebuild promotes the new generation"
                      + " and clears the brake. Clearing the auto_rebuild_* fields in state.json"
                      + " grants a fresh automatic budget without rebuilding.",
                  activeIndexPath,
                  IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS,
                  e);
              this.searchLifecycle = blue;
              publishIngestLifecycle(this.searchLifecycle);
              this.buildingIndexPath = null;
              this.rebuildBrakeExhausted = true;
            } else {
              // Auto-start Blue/Green migration on schema mismatch when enabled.
              log.warn(
                  "Schema mismatch detected on active generation {}. Starting Blue/Green migration"
                      + " (policy={}, attempt {} of {})...",
                  activeIndexPath,
                  schemaMismatchPolicy,
                  attempt,
                  IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS,
                  e);

              // Blue: serve search from the existing index, read-only.
              this.searchLifecycle = blue;

              // Green: create a new generation and start a writable runtime.
              IndexGenerationManager.State migrated = genManager.startMigration(MigrationSource.SCHEMA_MISMATCH.wire());
              String greenGenId =
                  migrated == null ? null : migrated.building_generation();
              if (greenGenId == null || greenGenId.isBlank()) {
                throw new IOException(
                    "Failed to start migration: building_generation missing in state.json");
              }
              this.buildingIndexPath = genManager.resolveGenerationPathStrict(greenGenId);
              publishIngestLifecycle(
                  buildIndexRuntime(buildingIndexPath, fpSupplier)
                      .withBuildState(LuceneRuntimeTypes.BuildState.BUILDING)
                      .open());

              // Kick off background enumeration to populate Green.
              startMigrationEnumeratorBestEffort(rc);
            }
        } else {
          throw e;
        }
      }

      }

      // If a migration is in progress (Blue/Green), ensure the enumerator + cutover monitor are running.
      if (buildingIndexPath != null && searchLifecycle != null && ingestLifecycle != null && searchLifecycle != ingestLifecycle) {
        if (generationBootDisposition == IndexGenerationManager.BootDisposition.NATIVE) {
          startMigrationEnumeratorBestEffort(rc);
        }
        if (generationBootDisposition == IndexGenerationManager.BootDisposition.NATIVE) {
          startMigrationCutoverMonitorBestEffort();
        }
      }

      // Schema validation: ensure all indexable fields exist in catalog (via ingest schema).
      if (ingestLifecycle != null) {
        ingestLifecycle.schema().validateIndexableFields(SchemaFields.INDEXABLE_FIELDS);
      }

      // Identity authority must exist before any queued or switch-buffered mutation can write.
      // Import from the serving generation (Blue during migration) before the first possible
      // drain, so replay, enumeration, and ordinary indexing all resolve through one authority.
      this.documentIdentityStore =
          new io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore(
              dbPath, rc.index().identityDeletionGraceMs());
      importDocumentIdentitiesFromActiveIndex(layout.activeGenerationId());

      // Apply any durable SWITCHING buffer ops. In deferred-writer mode, this is deferred
      // to the background task (after IndexWriter opens). In migration mode, run synchronously.
      // Skipped when the rebuild brake is exhausted: ingest is the READ-ONLY Blue runtime then, so
      // there is no writer to drain into.
      if (generationBootDisposition == IndexGenerationManager.BootDisposition.PROMOTED) {
        if (!(ingestLifecycle instanceof RunningRuntime)
            || !KnowledgeServerMigrationOps.finishCommittedBootSwitchReplay(jobQueue)) {
          throw new IOException("Promoted generation switch replay remains unresolved");
        }
        promotedReplaySettled = true;
      } else if (generationBootDisposition == IndexGenerationManager.BootDisposition.NATIVE
          && !rebuildBrakeExhausted && ingestLifecycle instanceof RunningRuntime) {
        drainSwitchBufferBestEffort();
      }

      tPhase = System.nanoTime();
      long luceneMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 3.5 Construct application services via registry (models wired later via deferred init)
      // Tempdoc 419 / T5.1 (ADR-0028): construct PathResolutionStore against the same jobs.db
      // already migrated by SqliteJobQueue. Threaded through InfraContext so the ingest/search
      // services and IndexingLoop can both consume it without violating module dependency direction.
      this.pathResolutionStore =
          new io.justsearch.indexerworker.queue.SqlitePathResolutionStore(dbPath);
      this.infraCtx =
          new InfraContext(
              config,
              jobQueue,
              () -> this.searchLifecycle,
              () -> this.ingestLifecycle,
              signalBus,
              telemetry,
              ((LocalTelemetry) telemetry).registry(),
              indexBasePath,
              activeIndexPath,
              this::migrationProgressSnapshot,
              MIGRATION_SWITCHING_MAX_DURATION_MS,
              pathResolutionStore,
              documentIdentityStore);
      // Tempdoc 885 item 3: build the duty-cycle policy from resolved config before the app
      // services that consume it. The duty/cooldown come from the one resolved config in this JVM.
      // They arrived through the ordinal-450 worker snapshot until item A19 deleted that tier; the
      // [R1] defect it removed (a key the Worker could not see) needed two processes to exist.
      this.indexingPacing = buildIndexingPacing();
      log.info(
          "Indexing pacing: foreground duty {}%, cooldown {} ms",
          indexingPacing.dutyPct(), indexingPacing.cooldownMs());

      // 516 P3 FINAL CUT: see newAppServices() — DWAS now pre-wires the migration
      // supplier + embedding telemetry at ctor time (last 2 setters eliminated).
      appServices = newAppServices();
      wireAppServicesPostConstruction(appServices);

      // 3.6 Embedding compatibility — MUST resolve BEFORE anything can commit (tempdoc 819 A).
      // This used to live in initDeferredModels() (async, behind multi-second ONNX composition),
      // while startIndexingLoop() below runs synchronously. On a fresh profile the Head's bundled
      // help batch was therefore ingested AND COMMITTED before refresh() ever ran, so refresh()
      // saw docCount>0 with no stored fingerprint and took BLOCKED_LEGACY — making the empty-index
      // fast path structurally unreachable on every first launch. refresh() needs only
      // EmbeddingFingerprint.get() (a cached file SHA, no loaded model — EmbeddingFingerprint.java:37-43),
      // so nothing from initDeferredModels is required here. The AUTO-RESCUE deliberately stays
      // late: it needs a RunningRuntime for the re-mark and the runtime may still be deferred here.
      initEmbeddingCompatibilityController();

      // Recorded recovery cannot run until the actual serving generation and owner are attached.
      attachRecordedIngestion();
      if (generationBootDisposition == IndexGenerationManager.BootDisposition.BUILDING) {
        startMigrationCutoverMonitorBestEffort();
      }
      recoverStuckJobsAdmitted(jobQueue, null);
      startStuckJobReaper(jobQueue);


      // Dev hot-reload manager (Phase 2, tempdoc 305)
      if (startupConfiguration.ai().devHotReload()) {
        devReloadManager = new DevReloadManager(this);
        log.info("Dev hot-reload enabled (justsearch.dev.hotreload=true)");
      }

      registerTelemetryGauges();

      tPhase = System.nanoTime();
      long initMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 4-5. The gRPC server, its three interceptors and the port publication used to be here.
      // Item A9 deleted all of it: the ports are direct calls (item A6), so there is nothing to
      // bind, no trace/request-id header to extract (the caller's OTel context and MDC are already
      // current on this thread) and no port for a second process to discover. The foreground-load
      // gauge kept its producer — ForegroundLoadGate in the composition root, wired at A6 —
      // which is why the interceptor could go without the gauge going with it.

      // 6. Start indexing loop (runs immediately; null-gates embedding/SPLADE until wired)
      // ...unless the rebuild brake is exhausted. The loop's whole job is to write into
      // ingestLifecycle, which is the READ-ONLY Blue runtime in that state, so starting it would
      // spend the machine turning every queued job into an exception. Search still serves, and the
      // status surface says why ingestion stopped (BLOCKED_REBUILD_BRAKE ->
      // index.rebuild_brake_exhausted). Recovery is an operator-initiated rebuild, which clears the
      // brake at promotion (IndexGenerationManager.promoteBuildingGenerationToActive).
      if (generationBootDisposition == IndexGenerationManager.BootDisposition.CAPTURING
          || generationBootDisposition == IndexGenerationManager.BootDisposition.FENCED) {
        log.info("Ingestion awaits recorded generation authority; current active index remains read-only");
      } else if (recordedCandidate != null
          && generationBootDisposition == IndexGenerationManager.BootDisposition.BUILDING) {
        log.info("Recorded Green ingestion awaits its exact candidate model set");
      } else if (rebuildBrakeExhausted) {
        log.error(
            "Ingestion is STOPPED: the automatic-rebuild budget for this index shape is spent."
                + " Search continues to serve the existing index read-only. To recover, run"
                + " Rebuild index from the UI, which starts a fresh migration and restores the"
                + " budget when it completes.");
      } else {
        appServices.startIndexingLoop();
      }

      publishServingView(appServices);

      notifyRecordedServicesPublished();
      retryCommittedGenerationRetirement();

      // 7. Start sentinel thread for liveness monitoring
      startSentinelThread();

      long loopMs = (System.nanoTime() - tPrev) / 1_000_000;
      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      log.info(
          "Startup phases (ms): telemetry={}, signalBus={}, jobQueue={}, lucene={}, init={}, loop={}, total={} [models loading in background]",
          telemetryMs, signalBusMs, jobQueueMs, luceneMs, initMs, loopMs, totalMs);

      log.info("KnowledgeServer started successfully (in-process; no port)");
      publishIndexConfiguration();

      // --- Deferred model initialization (background) ---
      // Models load in a background thread while the ports are already answering. Callers
      // are null-safe: search degrades to BM25, IndexingLoop skips embedding/SPLADE,
      // ingest queues jobs normally. Models become available via volatile setters.
      startDeferredModelInitialization(this::initDeferredModels);

    } catch (Exception | Error e) {
      if (indexComponent != null) {
        indexComponent.transition(io.justsearch.core.component.ComponentState.FAILED,
            io.justsearch.app.api.lifecycle.LifecycleReasonCode.WORKER_SPAWN_FAILED.code(), null);
      }
      log.error("Failed to start KnowledgeServer", e);
      // tempdoc 628 Stage D-part2: if startup failed because the index is corrupt and could not be
      // auto-recovered (FAIL_CLOSED / recovery-failed), stamp a fatal-reason marker so the Head can
      // offer a "Rebuild index" affordance instead of blind-restarting. This is a controlled exit (the
      // throw below → the Engine's boot failure path in HeadlessApp), so the write is reliable.
      //
      // A FAIL_CLOSED schema mismatch is the same kind of fact and was missing (tempdoc 915, live
      // validation): the refusal reached the Head only as "Worker process crashed (exit code 1)",
      // with the actual cause visible nowhere but the (then separate) worker.log. It is a
      // deliberate refusal, not a
      // crash, and it has its own remedy. Other fatal causes stay generic.
      if (isCorruptIndexCause(e)) {
        io.justsearch.ipc.WorkerFatalReasonMarker.write(
            dataDir, io.justsearch.ipc.WorkerFatalReasonMarker.INDEX_CORRUPT);
      } else if (isSchemaMismatch(e)) {
        io.justsearch.ipc.WorkerFatalReasonMarker.write(
            dataDir, io.justsearch.ipc.WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH);
      }
      try {
        close();
      } catch (Exception | Error cleanupFailure) {
        if (cleanupFailure != e) e.addSuppressed(cleanupFailure);
        log.warn("Failed startup cleanup remains incomplete", cleanupFailure);
      }
      if (e instanceof Error fatal) throw fatal;
      throw new IOException("Failed to start KnowledgeServer", e);
    }
  }

  /** Publish ownership before any operation that can fail after attachment returns. */
  void attachRecordedIngestion() throws IOException {
    // Validate state even when an intentionally denying attachment does not read its source.
    currentRecordedServingGeneration();
    recordedIngestionAttachment = Objects.requireNonNull(recordedIngestionLifecycle.attach(
        jobQueue, this::currentRecordedServingGeneration, this::recordedWorkerOnline,
        this::currentRecordedBulkRuntime),
        "recorded ingestion attachment");
  }

  java.util.Optional<String> currentRecordedServingGeneration() throws IOException {
    if (indexGenerationManager == null || activeIndexPath == null) {
      throw new IOException("Recorded serving generation has not been initialized");
    }
    WorkerAppServices initializedServices = appServices;
    LuceneRuntime ingest = ingestLifecycle;
    LuceneRuntime search = searchLifecycle;
    java.util.Optional<String> generation;
    try {
      generation = indexGenerationManager.idleActiveGeneration(activeIndexPath);
    } catch (tools.jackson.core.JacksonException malformed) {
      throw new IOException("Malformed authoritative index state", malformed);
    }
    if (runtimeSwapLock.isLocked() || initializedServices == null
        || !initializedServices.recordedWriterReady() || !(ingest instanceof RunningRuntime running)
        || !running.isAcceptingWrites() || ingest != search
        || rebuildBrakeExhausted) {
      return java.util.Optional.empty();
    }
    return generation;
  }

  private java.util.Optional<RecordedIngestionLifecycle.BulkRuntime> currentRecordedBulkRuntime() throws IOException {
    if (generationBootOwnership instanceof IndexGenerationManager.BootOwnership.Native
        || appServices == null) {
      return java.util.Optional.empty();
    }
    var current = indexGenerationManager.initializeForBoot(generationBootOwnership,
        recordedCandidate == null ? expectedIndexFingerprintOrNull()
            : recordedCandidate.target().fingerprint());
    String writable = null;
    if (ingestLifecycle instanceof RunningRuntime) {
      if (current.disposition() == IndexGenerationManager.BootDisposition.BUILDING
          && current.layout().activeGenerationPath().equals(activeIndexPath)
          && current.layout().indicesDir().resolve(current.layout().state().building_generation())
              .equals(buildingIndexPath) && ingestLifecycle != searchLifecycle) {
        writable = current.layout().state().building_generation();
      } else if (current.disposition() == IndexGenerationManager.BootDisposition.PROMOTED
          && current.layout().activeGenerationPath().equals(activeIndexPath)
          && ingestLifecycle == searchLifecycle) {
        writable = current.layout().activeGenerationId();
      }
    }
    return java.util.Optional.of(new RecordedIngestionLifecycle.BulkRuntime(current.disposition(),
        current.layout().activeGenerationId(), current.layout().state().building_generation(),
        current.layout().state().migration_state(), writable,
        generationBootDisposition == IndexGenerationManager.BootDisposition.PROMOTED,
        promotedReplaySettled));
  }

  /** Index runtime presence only; no queue query, model call or Head-side readiness dependency. */
  boolean recordedWorkerOnline() {
    return appServices != null && searchLifecycle != null && ingestLifecycle != null;
  }

  /** Opens the server-owned periodic queue producer on its registered background scheduler. */
  void startStuckJobReaper(JobQueue reaperQueue) {
    Objects.requireNonNull(reaperQueue, "reaperQueue");
    stuckJobReaper = workerExecutors.stuckJobReaper().openScheduled(r -> {
      Thread thread = new Thread(r, "stuck-job-reaper");
      thread.setDaemon(true);
      return thread;
    });
    stuckJobReapTask = stuckJobReaper.scheduleWithFixedDelay(() -> {
      try {
        recoverStuckJobsAdmitted(reaperQueue, STALE_PROCESSING_MS);
      } catch (RuntimeException failure) {
        log.warn("stuck-job reaper tick failed (will retry): {}", failure.toString());
      }
      retryRetiredServingViews();
      retryCommittedGenerationRetirement();
    }, REAP_INTERVAL_MS, REAP_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  /** The periodic queue producer shares the same final fence as request and watcher intake. */
  private void recoverStuckJobsAdmitted(JobQueue queue, Long olderThanMs) {
    if (!(appServices instanceof DefaultWorkerAppServices selected)) {
      throw new IllegalStateException("Stuck-job recovery has no mutation owner");
    }
    try (var ignoredMutation = selected.mutationAdmission().enter(selected.mutationOwnerToken())) {
      if (olderThanMs == null) queue.recoverStuckJobs();
      else queue.recoverStuckJobs(olderThanMs);
    }
  }

  /** The executor remains an actual-exit owner even if its exposed completion is canceled. */
  void startDeferredModelInitialization(java.util.function.Supplier<ModelContext> initializer) {
    Objects.requireNonNull(initializer, "initializer");
    deferredModelExecutor = workerExecutors.deferredModelInit().open(r -> {
      Thread thread = new Thread(r, "deferred-model-init");
      thread.setDaemon(true);
      return thread;
    });
    deferredModelInit = io.justsearch.core.execution.EngineFutures.supplyAsync(initializer, deferredModelExecutor);
  }

  /**
   * How a background-model-init failure is reported. Extracted so the one decision it makes is
   * testable: a {@code SCHEMA_MISMATCH} arriving from {@code DeferredRuntime.prepareWriterUpgrade()} means
   * ingestion has STOPPED — the index cannot accept writes under this runtime's shape — and filing
   * that under the generic "non-fatal" background-init line is how it stayed invisible while the
   * automatic migration silently never ran (tempdoc 915 §C.12, open item O7).
   *
   * <p>It is reported loudly rather than propagated: this runs on a background
   * {@code CompletableFuture} with no caller left to receive it, {@code start()} having long
   * returned. The durable handling is the NEXT boot's pre-open detection, which sees the same
   * mismatch before it chooses an open mode and routes it into the policy handler; meanwhile the
   * status surface already reports {@code BLOCKED_MISMATCH} with {@code reindex_required} from the
   * same fingerprint comparison, so the user is not left waiting for a restart to be told.
   */
  static void logBackgroundInitFailure(Exception e) {
    if (isSchemaMismatch(e)) {
      log.error(
          "Ingestion is STOPPED: the deferred writer upgrade found a schema mismatch"
              + " (index_fingerprint). Search continues on the read-only runtime. This is NOT a"
              + " degraded-capability warning - restart the Worker to run the schema-mismatch"
              + " policy (index.schema_mismatch.policy), or run Rebuild index from the UI.",
          e);
      return;
    }
    log.error("Background model initialization failed (non-fatal)", e);
  }

  /**
   * True if {@code t} or any cause in its chain is a {@code SCHEMA_MISMATCH}. Walks the chain
   * because the deferred upgrade wraps: the guard's exception arrives inside whatever
   * {@code prepareWriterUpgrade()} threw.
   */
  static boolean isSchemaMismatch(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof IndexRuntimeIOException ire
          && ire.reason()
              == IndexRuntimeIOException.Reason
                  .SCHEMA_MISMATCH) {
        return true;
      }
      if (c == c.getCause()) {
        break;
      }
    }
    return false;
  }

  /** True if a startup failure was caused by an unrecoverable corrupt Lucene index (628 Stage D-part2). */
  private static boolean isCorruptIndexCause(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof IndexRuntimeIOException ire
          && ire.reason() == IndexRuntimeIOException.Reason.CORRUPT_INDEX) {
        return true;
      }
    }
    return false;
  }

  /** Declared configuration inputs consumed by this physical index composition. */
  public static Set<String> componentDependencies() {
    return IndexConfigurationProjection.dependencies();
  }

  private void publishIndexConfiguration() {
    if (indexComponent == null) return;
    if (!(appServices instanceof DefaultWorkerAppServices services)) {
      throw new IllegalStateException("Index configuration requires the composed service owners");
    }
    String version = IndexConfigurationProjection.digest(
        startupConfiguration, config, indexBasePath, searchLifecycle, services,
        indexingPacing, documentIdentityStore.deletionGraceMs(),
        tracingBootstrap == null ? null : tracingBootstrap.samplerDescription());
    indexComponent.setDesiredVersion(version);
    indexComponent.setAppliedVersion(version);
  }

  /**
   * Constructs a {@link DefaultWorkerAppServices} with the 2 KS-owned pre-wired values
   * (migration-active supplier + embedding telemetry) already supplied at ctor time.
   * Tempdoc 516 P3 final cut — eliminates the last 2 post-ctor setters on IndexingLoop.
   *
   * <p>The migration lambda captures {@code KS.this} and reads {@code buildingIndexPath},
   * {@code searchLifecycle}, {@code ingestLifecycle} at call time (during indexing — well
   * after KS init completes). Safe to create whenever {@code KS.this} exists.
   *
   * <p>Shared by the boot-time construction, the post-deferred-upgrade reconstruction, and
   * {@link DevReloadManager}'s hot-reload path so all three observe the same wiring.
   */
  DefaultWorkerAppServices newAppServices() {
    return newAppServices(infraCtx, ingestLifecycle);
  }

  private DefaultWorkerAppServices newAppServicesForRuntime(RunningRuntime runtime) {
    return newAppServices(fixedRuntimeContext(runtime, activeIndexPath), runtime);
  }

  private InfraContext fixedRuntimeContext(RunningRuntime runtime, Path servingPath) {
    InfraContext base = Objects.requireNonNull(infraCtx, "infraCtx");
    return new InfraContext(base.config(), base.jobQueue(), () -> runtime,
        () -> runtime, base.signalBus(), base.telemetry(), base.metricRegistry(),
        base.indexBasePath(), servingPath, base.migrationProgressSupplier(),
        base.migrationSwitchingMaxDurationMs(), base.pathResolutionStore(),
        base.documentIdentityStore());
  }

  /** Prepares the already-running Green producer's serving services before pointer commitment. */
  DefaultWorkerAppServices prepareServingSuccessor(RunningRuntime greenRuntime) {
    if (!(appServices instanceof DefaultWorkerAppServices incumbent)) {
      throw new IllegalStateException("Green producer services are unavailable");
    }
    if (greenRuntime != ingestLifecycle || greenRuntime == searchLifecycle
        || buildingIndexPath == null) {
      throw new IllegalStateException("Green runtime is not the open migration writer");
    }
    DefaultWorkerAppServices successor =
        incumbent.prepareServingSuccessor(fixedRuntimeContext(greenRuntime, buildingIndexPath));
    try {
      wireAppServicesPostConstruction(successor);
      if (recordedCandidate != null) {
        CandidateModels selected = Objects.requireNonNull(candidateModels,
            "Recorded Green candidate models are not READY");
        successor.wireEmbeddingCompatController(
            Objects.requireNonNull(candidateEmbeddingCompatController,
                "Recorded Green embedding compatibility is not READY"));
        successor.wireModelReadyLatch(selected.owner()::modelReadyLatch);
        successor.wireSpladeIdfQueryEncoder(selected.spladeIdf());
        successor.wireSearchReranker(selected.reranker());
        successor.wireCitationScorer(selected.citation());
        successor.wireGpuDiagnostics(selected.diagnostics());
        successor.wireStageEnabled(selected.embedding() != null,
            selected.bindings().spladeEncoder() != null,
            selected.bindings().nerService() != null);
        successor.wirePolicySnapshotSupplier(() -> {
          try (var lease = selected.owner().acquire()) {
            return lease.surface().policies();
          }
        });
      }
      return successor;
    } catch (RuntimeException | Error failure) {
      try { successor.close(); }
      catch (IOException | RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  private DefaultWorkerAppServices newAppServices(InfraContext selected,
      LuceneRuntime selectedIngest) {
    if (serviceConfiguration == null) {
      serviceConfiguration = WorkerServiceConfiguration.capture(
          startupConfiguration, workerExecutors.pdfOcr().spec().threadCount());
    }
    if (recordedCandidate != null
        && generationBootDisposition != IndexGenerationManager.BootDisposition.PROMOTED
        && candidateServiceConfiguration == null) {
      candidateServiceConfiguration = WorkerServiceConfiguration.capture(
          recordedCandidate.configuration(), workerExecutors.pdfOcr().spec().threadCount());
    }
    if (selectedIngest instanceof RunningRuntime runningRuntime) {
      bindTerminalWriterFaultSource(runningRuntime);
    }
    return new DefaultWorkerAppServices(
        workerExecutors,
        selected,
        () -> buildingIndexPath != null && searchLifecycle != ingestLifecycle,
        embeddingTelemetry,
        indexingPacing,
        childRegistry,
        serviceConfiguration,
        candidateServiceConfiguration);
  }

  /** Tempdoc 885 item 3: the process-scoped duty-cycle policy, built from resolved config. */
  private IndexingPacing buildIndexingPacing() {
    ResolvedConfig.Ai.BackfillPacing pacing =
        startupConfiguration == null
            ? ResolvedConfig.Ai.BackfillPacing.DEFAULTS
            : startupConfiguration.ai().backfillPacing();
    return new IndexingPacing(
        foregroundLoad, pacing.foregroundDutyPct(), pacing.foregroundCooldownMs());
  }

  /**
   * Apply post-construction wiring to {@code appServices}. Called after the initial
   * boot-time construction and again after any reconstruction (e.g., when
   * {@link DeferredRuntime#prepareWriterUpgrade()} swaps the runtime and we need a fresh
   * {@link DefaultWorkerAppServices} with non-null indexingLoop / ingestService).
   */
  private void wireAppServicesPostConstruction(WorkerAppServices svc) {
    // 343: Wire resolved config supplier for search config status reporting.
    svc.ingestService()
        .setResolvedConfigSupplier(() -> ConfigStore.global().get());

    // 516 P3 FINAL CUT: wireMigrationActiveSupplier removed — pre-wired via DWAS 2-arg ctor.

    // Tempdoc 397 §14.28 U3: wire the modelReadyLatch so WorkerSearchService's query handlers
    // can await encoder wiring before first use.
    svc.wireModelReadyLatch(() -> modelReadyLatch);

    // Tempdoc 397 §14.28 U4: wire the PolicySnapshot supplier so the getSessionPolicies
    // port can return the index half's authoritative snapshot.
    svc.wirePolicySnapshotSupplier(
        () -> inferenceSurface != null ? inferenceSurface.policies() : null);

    // Tempdoc 406 — wire the runtime reload trigger so POST /api/admin/runtime/reload
    // can drive a holder swap on the active ingest runtime. Captures the active
    // index path lazily at trigger time so post-cutover paths swap correctly.
    svc.ingestService()
        .setRuntimeReloadTrigger(
            reason ->
                swapRuntime(
                    () -> buildIndexRuntime(activeIndexPath,
                            () -> java.util.Optional.empty()).open(),
                    java.time.Duration.ofSeconds(30),
                    reason));

    // 516 P3 FINAL CUT: wireEmbeddingTelemetryEvents removed — pre-wired via DWAS 2-arg ctor.

    // Tempdoc 419 C3 V1 — wire the worker's RRD store so the indexStatus RPC can backfill the
    // recent-job-queue-depth trend. Late-bound supplier handles the LocalTelemetry-pre-init
    // path safely (returns null → empty array on the receiver side).
    if (telemetry instanceof LocalTelemetry lt) {
      svc.ingestService().setRrdStoreSupplier(lt::getRrdStore);
    }

    // Tempdoc 819: the ECC is now resolved BEFORE the first appServices reconstruction
    // (DeferredRuntime.prepareWriterUpgrade -> reconstructAppServicesAfterDeferredUpgrade), which
    // previously ran ahead of the ECC's only wire site. Re-wire it on every reconstruction so a
    // fresh DefaultWorkerAppServices does not start its indexing loop with a null controller.
    // Null on the boot-time call (the ECC is constructed just after) — the boot path wires it
    // explicitly in initEmbeddingCompatibilityController().
    var ecc = embeddingCompatController;
    if (ecc != null) {
      svc.wireEmbeddingCompatController(ecc);
    }
  }

  /**
   * Tempdoc 406 Phase 4a: after {@link DeferredRuntime#prepareWriterUpgrade()} swaps the
   * runtime, the existing {@code appServices} captured ops from the now-closed
   * deferred runtime. Reconstruct from the current {@code infraCtx} (which sees
   * the post-upgrade {@code RunningRuntime} via supplier re-read), re-apply
   * post-construction wiring, publish the new instance and start its indexing loop.
   *
   * <p>Item A9: the three {@code Delegating*Service} wrappers this used to re-point are gone with
   * the gRPC registration they existed for. Publishing {@code appServices} IS the swap now, because
   * every caller reaches the services through {@link #appServices()} per call rather than through a
   * registered wrapper — see {@code EngineKnowledgeClient}, which holds a supplier for exactly this
   * reason. Mirrors {@code DevReloadManager.performReload}'s swap.
   */
  private void reconstructAppServicesAfterDeferredUpgrade() {
    log.info("Reconstructing appServices after DeferredRuntime.prepareWriterUpgrade()");
    closePendingAppServices();
    WorkerAppServices oldServices = appServices;
    WorkerAppServices newServices = newAppServices();
    pendingAppServices = newServices;
    try {
      wireAppServicesPostConstruction(newServices);
      if (newServices instanceof DefaultWorkerAppServices producer
          && initialEncoderSet != null) {
        retainProducerModels(producer, initialEncoderSet);
      }
      if (oldServices != null) oldServices.close();
      this.appServices = null; // The incumbent has actually closed; callers see unavailability.
      newServices.startIndexingLoop();
      publishServingView(newServices);
      pendingAppServices = null;
    } catch (Exception | Error failure) {
      try { closePendingAppServices(); }
      catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      if (failure instanceof Error fatal) throw fatal;
      throw new IllegalStateException("Application-service replacement failed; owners retained", failure);
    }
  }

  /** Called only after the replacement service has started and is published to Engine callers. */
  void notifyRecordedServicesPublished() {
    // Every caller has released its own transition lock. Wait for a competing transition
    // instead of silently losing the only notification that can resume a recorded owner.
    RecordedIngestionLifecycle.Attachment attachment;
    runtimeSwapLock.lock();
    try {
      if (running && appServices instanceof DefaultWorkerAppServices) publishIndexConfiguration();
      attachment = recordedIngestionAttachment;
    } finally {
      runtimeSwapLock.unlock();
    }
    // The callback can drive an operation that re-enters Worker control. It runs outside the
    // physical runtime lock and rechecks its own attachment identity before doing any work.
    if (attachment != null) attachment.servicesPublished();
  }

  private void closePendingAppServices() {
    if (pendingAppServices != null) {
      try {
        pendingAppServices.close();
        pendingAppServices = null;
      } catch (IOException failure) {
        throw new IllegalStateException("Unpublished application services still own resources", failure);
      }
    }
    if (pendingDeferredUpgrade != null) {
      pendingDeferredUpgrade.close();
      pendingDeferredUpgrade = null;
    }
  }

  /** Opens and composes the deferred writer successor before changing reader admission. */
  private void upgradeDeferredServing(DeferredRuntime deferred) {
    ServingView retired = null;
    runtimeSwapLock.lock();
    try {
      if (closeStarted || ingestLifecycle != deferred || searchLifecycle != deferred) {
        throw new IllegalStateException("Deferred upgrade lost its runtime owner");
      }
      closePendingAppServices();
      var preparation = deferred.prepareWriterUpgrade();
      pendingDeferredUpgrade = preparation;
      try {
        RunningRuntime upgraded = preparation.runtime();
        WorkerAppServices oldServices = Objects.requireNonNull(appServices, "appServices");
        DefaultWorkerAppServices candidate = newAppServicesForRuntime(upgraded);
        pendingAppServices = candidate;
        wireAppServicesPostConstruction(candidate);
        if (initialEncoderSet != null) retainProducerModels(candidate, initialEncoderSet);
        candidate.prepareIndexingLoop();
        retired = publishDeferredSuccessor(candidate, oldServices, upgraded, preparation);
        pendingAppServices = null;
        pendingDeferredUpgrade = null;
        // Only a no-throw gate opens after B is publicly selected; no jobs were claimed before it.
        candidate.activatePreparedIndexingLoop();
      } catch (RuntimeException | Error failure) {
        try { closePendingAppServices(); }
        catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        throw failure;
      }
    } finally {
      runtimeSwapLock.unlock();
      if (retired != null) cleanRetiredServingView(retired);
    }
    notifyRecordedServicesPublished();
  }

  void retainPendingAppServices(WorkerAppServices candidate) {
    if (pendingAppServices != null) {
      throw new IllegalStateException("Prior pending application services still own resources");
    }
    pendingAppServices = Objects.requireNonNull(candidate, "candidate");
  }

  void releasePendingAppServices(WorkerAppServices published) {
    if (pendingAppServices != published) {
      throw new IllegalStateException("Published application services do not match pending owner");
    }
    pendingAppServices = null;
  }

  void closeFailedPendingAppServices() {
    closePendingAppServices();
  }

  /**
   * Tempdoc 406 swap helper. Drains the current ingest runtime, opens a fresh one
   * via {@code opener}, atomically replaces the holder fields, and reconstructs
   * the application services so downstream consumers see the new runtime via
   * supplier re-read. Returns the swap duration in milliseconds. Before item A9 the
   * reconstruction step also re-registered the gRPC service wrappers.
   *
   * <p>Serialized with other reloads and shutdown. Errors during open
   * leave the old runtime in place and re-throw — callers see a hard failure
   * rather than a half-swapped state.
   *
   * @param opener supplies the fresh runtime; called after the old one drains
   * @param drainTimeout maximum time to wait for in-flight writes to complete
   * @param reason low-cardinality tag for telemetry ("admin_triggered" / etc.)
   * @return total swap duration (ms) including drain + open
   */
  public long swapRuntime(
      java.util.function.Supplier<RunningRuntime> opener,
      java.time.Duration drainTimeout,
      io.justsearch.adapters.lucene.runtime.SwapReason reason) {
    Objects.requireNonNull(opener, "opener");
    Objects.requireNonNull(drainTimeout, "drainTimeout");
    Objects.requireNonNull(reason, "reason");
    if (closeStarted) throw new IllegalStateException("Runtime reload refused during server close");
    final long elapsed;
    runtimeSwapLock.lock();
    try {
      if (closeStarted) throw new IllegalStateException("Runtime reload refused during server close");
      try {
        retireServingView();
      } catch (IOException refusal) {
        throw new IllegalStateException("Runtime reload retains active serving holders", refusal);
      }
      long startNanos = System.nanoTime();
      LuceneRuntime old = this.ingestLifecycle;
      if (old instanceof RunningRuntime running) {
        running.drainAndClose(drainTimeout, reason);
      } else if (old != null) {
        try {
          old.close();
        } catch (Exception e) {
          log.warn("swapRuntime: best-effort close of non-RunningRuntime old: {}", e.getMessage());
        }
      }
      RunningRuntime fresh = opener.get();
      publishIngestLifecycle(fresh);
      this.searchLifecycle = fresh;
      reconstructAppServicesAfterDeferredUpgrade();
      elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
    } finally {
      runtimeSwapLock.unlock();
    }
    notifyRecordedServicesPublished();
    return elapsed;
  }

  /** Assign the deferred A surface to every still-issued A view before model wiring is released. */
  private void attachInitialEncoderSet(InferenceSurface surface, ResolvedConfig configuration) {
    int dimension = "bge-m3".equalsIgnoreCase(configuration.ai().sparseModel())
        ? 1024 : new io.justsearch.configuration.JustSearchConfigurationLoader()
            .loadFieldCatalog().vectorDimension();
    var owner = new EncoderSet(surface, new EncoderSet.ModelIdentity(
        IndexFingerprint.ModelFingerprint.of(
            EmbeddingFingerprint.modelPath().isPresent(), EmbeddingFingerprint.get()),
        IndexFingerprint.ModelFingerprint.of(
            SpladeFingerprint.modelPath().isPresent(), SpladeFingerprint.get()),
        IndexFingerprint.ModelFingerprint.of(
            io.justsearch.indexerworker.ner.NerFingerprint.modelPath().isPresent(),
            io.justsearch.indexerworker.ner.NerFingerprint.get()),
        "bge-m3".equalsIgnoreCase(configuration.ai().sparseModel()), dimension));
    publicationLock.writeLock().lock();
    try {
      synchronized (servingViewMonitor) {
        if (initialEncoderSet != null || servingView == null || servingView.retiring) {
          throw new IllegalStateException("Initial encoder owner lost its serving view");
        }
        initialEncoderSet = owner;
        servingView.attachEncoderSet(owner);
        if (appServices instanceof DefaultWorkerAppServices producer) {
          retainProducerModels(producer, owner);
        }
        for (ServingView old : retiredServingViews) {
          if (old.holders > 0 && !old.cleanupRunning && old.encoderSet == null) {
            old.attachEncoderSet(owner);
          }
        }
      }
    } finally {
      publicationLock.writeLock().unlock();
    }
  }

  private static void retainProducerModels(DefaultWorkerAppServices producer, EncoderSet owner) {
    EncoderSet.Lease lease = owner.acquire();
    try {
      producer.replaceProducerModelLease(lease::close);
    } catch (RuntimeException | Error failure) {
      lease.close();
      throw failure;
    }
  }

  /** Compose B without publishing any of its model wrappers into A's serving service view. */
  private CandidateModels composeRecordedCandidateModels() throws IOException {
    var accepted = Objects.requireNonNull(recordedCandidate, "Recorded candidate");
    var captured = Objects.requireNonNull(recordedCandidateFingerprint,
        "Recorded candidate fingerprints");
    ResolvedConfig configuration = accepted.configuration();
    var inputs = captured.runtimeFingerprintInputs();
    Integer declaredDimension = inputs.effectiveVectorDimension();
    int dimension = declaredDimension == null
        ? Objects.requireNonNull(new io.justsearch.configuration.JustSearchConfigurationLoader()
            .loadFieldCatalog().vectorDimension(), "Index vector dimension")
        : declaredDimension;
    Path aiHome = configuration.paths().dataDir();
    InstallContract contract = aiHome == null ? null : InstallContractIO.read(aiHome);
    Path modelsDir = resolveModelsDir(contract, aiHome, configuration);
    HardwareProfile hardware = contract != null && contract.hardwareProfile() != null
        ? contract.hardwareProfile()
        : configuration.ai().masterGpuEnabled() ? HardwareProfile.gpuFull(0)
            : HardwareProfile.cpuOnly();
    var encoderConfiguration = EncoderConfigurationProjection.from(configuration);
    InferenceSurface surface = InferenceCompositionRoot.compose(encoderConfiguration, hardware,
        contract, modelsDir, () -> !signalBus.isMainGpuActive(), ortSessionEvents);
    var owner = new EncoderSet(surface, new EncoderSet.ModelIdentity(
        inputs.embeddingModel(), inputs.spladeModel(), inputs.nerModel(),
        "bge-m3".equalsIgnoreCase(configuration.ai().sparseModel()), dimension));
    try {
      EmbeddingService embedding = null;
      if (surface.embedding().isPresent()) {
        var assembly = surface.embedding().orElseThrow();
        var config = encoderConfiguration.embedding();
        var encoder = new io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder(
            assembly.sessions(), assembly.shape(), assembly.tokenizer());
        var backend = new io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend(
            encoder, config.gpuEnabled() ? 1 : 0, config.contextLength());
        embedding = EmbeddingService.createWithBackend(backend, config, embeddingTelemetry,
            assembly.capabilities().documentPrefix(), assembly.capabilities().queryPrefix());
        if (!embedding.isAvailable() || embedding.dimension() != dimension) {
          throw new IOException("Recorded candidate embedding does not match its index dimension");
        }
      }
      io.justsearch.indexerworker.ner.NerService ner = surface.ner().isPresent()
          ? new io.justsearch.indexerworker.ner.NerService(
              surface.ner().orElseThrow(), encoderConfiguration.ner()) : null;
      io.justsearch.indexerworker.bgem3.BgeM3Encoder bge = null;
      if (surface.bgeM3().isPresent()) {
        var assembly = surface.bgeM3().orElseThrow();
        bge = new io.justsearch.indexerworker.bgem3.BgeM3Encoder(
            assembly.sessions(), assembly.shape(), assembly.tokenizer(),
            encoderConfiguration.bgeM3());
      }
      io.justsearch.indexerworker.splade.SpladeEncoder splade = null;
      io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder idf = null;
      if (surface.splade().isPresent()) {
        var assembly = surface.splade().orElseThrow();
        var config = encoderConfiguration.splade();
        splade = new io.justsearch.indexerworker.splade.SpladeEncoder(
            assembly.sessions(), assembly.shape(), assembly.tokenizer(), assembly.vocabulary(),
            assembly.truncationEvidencePath(), config);
        if (config.isIdfQueryMode()) {
          Path table = config.modelPath().resolve("idf.json");
          if (Files.isRegularFile(table)) {
            idf = new io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder(
                table, splade.tokenizer(), splade.vocabulary());
          }
        }
      }
      if ((inputs.embeddingModel().state()
              == IndexFingerprint.ModelState.PRESENT && embedding == null)
          || (inputs.spladeModel().state()
              == IndexFingerprint.ModelState.PRESENT && splade == null)
          || (inputs.nerModel().state()
              == IndexFingerprint.ModelState.PRESENT && ner == null)
          || ("bge-m3".equalsIgnoreCase(configuration.ai().sparseModel()) && bge == null)) {
        throw new IOException("Recorded candidate model could not become READY");
      }
      io.justsearch.reranker.CrossEncoderReranker reranker = surface.reranker().isPresent()
          ? new io.justsearch.reranker.CrossEncoderReranker(
              surface.reranker().orElseThrow().sessions(),
              surface.reranker().orElseThrow().shape(),
              surface.reranker().orElseThrow().tokenizer()) : null;
      io.justsearch.reranker.CitationScorer citation = surface.citation().isPresent()
          ? new io.justsearch.reranker.CitationScorer(
              surface.citation().orElseThrow().sessions(),
              surface.citation().orElseThrow().shape(),
              surface.citation().orElseThrow().tokenizer()) : null;
      var diagnostics = new GpuDiagnosticSuppliers(
          splade == null ? null : splade::getOrtCudaStatus,
          splade == null ? null : splade::resolvedModelPath,
          embedding == null ? null : embedding::getOrtCudaStatus,
          embedding == null ? null : embedding::resolvedBackendId,
          embedding == null ? () -> 0 : embedding::gpuLayers,
          reranker == null ? null : reranker::getOrtCudaStatus,
          ner == null ? null : ner::getOrtCudaStatus,
          citation == null ? null : citation::getOrtCudaStatus,
          bge == null ? null : bge::getOrtCudaStatus);
      var bindings = new EncoderBindings.Snapshot(splade, bge, ner, null);
      owner.releaseModelReady();
      return new CandidateModels(owner, embedding, bindings, idf, reranker, citation, diagnostics);
    } catch (IOException | RuntimeException | Error failure) {
      try { owner.close(); }
      catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  /**
   * Background model initialization — runs in a separate thread once the services are published
   * and answering. Loads embedding, NER, SPLADE/BGE-M3, and disambiguation models. Opens deferred
   * IndexWriter if applicable. Non-fatal: failures degrade capabilities but don't crash the server.
   */
  @SuppressWarnings("PMD.CognitiveComplexity")
  private ModelContext initDeferredModels() {
    long bgStart = System.nanoTime();
    List<ServingLease> modelWiringLeases = new ArrayList<>();
    if (encoderComponent != null) {
      encoderComponent.transition(io.justsearch.core.component.ComponentState.STARTING, null, null);
    }
    try {
      // Open IndexWriter (deferred from sync path so reads are answerable sooner).
      // Phase types: DeferredRuntime.prepareWriterUpgrade() returns a fresh RunningRuntime;
      // swap the holder fields and reconstruct appServices (which captured ops from
      // the now-closed deferred session), then republish it. After this:
      //   - search continues seamlessly via the upgraded runtime
      //   - write methods stop returning UNAVAILABLE; the indexing loop starts
      if (ingestLifecycle instanceof DeferredRuntime deferred) {
        upgradeDeferredServing(deferred);
        drainSwitchBufferBestEffort();
      }
      modelWiringLeases.addAll(captureModelWiringLeases());

      // --- Composition root: resolve install contract + hardware profile ---
      var compositionConfig = startupConfiguration;
      Path aiHome = compositionConfig.paths().dataDir();
      InstallContract contract = aiHome != null ? InstallContractIO.read(aiHome) : null;
      // Tempdoc 374 alpha.18 Bug H + alpha.20 Bug M: honor JUSTSEARCH_MODELS_DIR.
      // alpha.20 prefers contract.modelsDir() (recorded at install time, survives
      // cold restart) over runtime env-var resolution (which doesn't inherit across
      // GUI launches). See resolveModelsDir Javadoc for the three-tier fallback.
      Path modelsDir = resolveModelsDir(contract, aiHome, compositionConfig);
      boolean gpuEnabled = compositionConfig.ai().masterGpuEnabled();
      HardwareProfile hardware =
          (contract != null && contract.hardwareProfile() != null)
              ? contract.hardwareProfile()
              : (gpuEnabled ? HardwareProfile.gpuFull(0) : HardwareProfile.cpuOnly());
      if (contract != null) {
        log.info(
            "Composition root: install contract loaded (profile={}, models={})",
            contract.downloadProfile(),
            contract.models().size());
      } else {
        log.info("Composition root: no install contract — using dev mode discovery");
      }

      // Tempdoc 397 §14.26 T2-C1/C2: single-entry compose returns a typed surface. Per-encoder
      // wiring below destructures the surface; graceful degradation is preserved via
      // Optional<> on each role.
      var encoderConfiguration = EncoderConfigurationProjection.from(compositionConfig);
      InferenceSurface surface =
          InferenceCompositionRoot.compose(
              encoderConfiguration,
              hardware,
              contract,
              modelsDir,
              () -> !signalBus.isMainGpuActive(),
              ortSessionEvents);
      this.inferenceSurface = surface;
      attachInitialEncoderSet(surface, compositionConfig);

      // Embedding — skip when BGE-M3 is active (surface.embedding() is already empty in that case).
      var embeddingConfig = encoderConfiguration.embedding();
      if (surface.embedding().isPresent()) {
        var embedAssembly = surface.embedding().get();
        var encoder =
            new io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder(
                embedAssembly.sessions(), embedAssembly.shape(), embedAssembly.tokenizer());
        var backend =
            new io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend(
                encoder, embeddingConfig.gpuEnabled() ? 1 : 0, embeddingConfig.contextLength());
        // Tempdoc 413: pass embeddingTelemetry so the service emits invoke_failure / cache /
        // chunked events into the worker LocalTelemetry's metrics-worker.ndjson.
        // Tempdoc 710 Wave 2 Move 1: prefixes come from the capability contract resolved once at
        // composition time — EmbeddingService no longer reads prefix_config.json itself.
        EmbeddingService es =
            EmbeddingService.createWithBackend(
                backend,
                embeddingConfig,
                embeddingTelemetry,
                embedAssembly.capabilities().documentPrefix(),
                embedAssembly.capabilities().queryPrefix());
        if (es.isAvailable()) {
          embeddingService = es;
          validateEmbeddingDimension();
          wireModelServices(modelWiringLeases, svc -> svc.wireEmbeddingProvider(es));
          // observations.md fix: null `embeddingService` on GPU-handoff unload
          // so `GpuDiagnosticSuppliers` lambdas (rebound to re-read the field)
          // stop returning data from the closed instance. The provider becomes
          // NoOpEmbeddingProvider.INSTANCE on unload (IndexingLoop:1581);
          // `instanceof` is rename-safe in a way the prior class-name string
          // match wasn't.
          wireModelServices(modelWiringLeases, svc -> svc.addEmbeddingProviderChangeListener(
              provider -> {
                if (provider == null
                    || provider instanceof io.justsearch.indexerworker.embed.NoOpEmbeddingProvider) {
                  this.embeddingService = null;
                }
              }));
          log.info("Embedding service ready (dimension={})", es.dimension());
        }
      } else if (surface.bgeM3().isEmpty()) {
        log.info("No embedding model found - vector search disabled");
      }

      // EmbeddingCompatibilityController is constructed + refreshed + wired SYNCHRONOUSLY in
      // start(), before startIndexingLoop() (tempdoc 819 A — see initEmbeddingCompatibilityController).
      // Only the auto-rescue stays here: it needs a RunningRuntime for the re-mark
      // (EmbeddingRecoveryOps.java:90-97) and returns SKIPPED without one, which the early site
      // cannot guarantee (the runtime may still be a DeferredRuntime there). The early refresh sets
      // the correct STATE; this late rescue keeps its writer.
      maybeAutoStartEmbeddingRebuildForBlockedLegacyBestEffort();

      // NER — surface-provided assembly wraps in NerService.
      var nerConfig = encoderConfiguration.ner();
      if (surface.ner().isPresent()) {
        var nerService = new io.justsearch.indexerworker.ner.NerService(
            surface.ner().get(), nerConfig);
        nerServiceInstance = nerService;
        wireModelServices(modelWiringLeases, svc -> svc.wireNerService(nerService));
        var nerModelPath = nerConfig.modelPath().toString();
        var nerGpuEnabled = nerConfig.gpuEnabled();
        wireModelServices(modelWiringLeases,
            svc -> svc.ingestService().setNerModelPathSupplier(() -> nerModelPath));
        wireModelServices(modelWiringLeases,
            svc -> svc.ingestService().setNerGpuEnabledSupplier(() -> nerGpuEnabled));
      } else if (nerConfig.isReady()) {
        log.info("NER: surface returned no assembly; NER will be unavailable.");
      }

      // BGE-M3 unified dense+sparse encoder (when selected + surface has it).
      if (surface.bgeM3().isPresent()) {
        var bgeAssembly = surface.bgeM3().get();
        var bgeConfig = encoderConfiguration.bgeM3();
        var bgeEncoder =
            new io.justsearch.indexerworker.bgem3.BgeM3Encoder(
                bgeAssembly.sessions(),
                bgeAssembly.shape(),
                bgeAssembly.tokenizer(),
                bgeConfig);
        bgeM3EncoderInstance = bgeEncoder;
        wireModelServices(modelWiringLeases, svc -> svc.wireBgeM3Encoder(bgeEncoder));
        log.info(
            "BGE-M3 encoder ready (replaces SPLADE + EmbeddingService): model={}",
            bgeConfig.modelPath());
      }

      // SPLADE (default, or fallback if BGE-M3 was selected but unavailable).
      var spladeConfig = encoderConfiguration.splade();
      if (surface.splade().isPresent()) {
        var spladeAssembly = surface.splade().get();
        var spladeEncoder =
            new io.justsearch.indexerworker.splade.SpladeEncoder(
                spladeAssembly.sessions(),
                spladeAssembly.shape(),
                spladeAssembly.tokenizer(),
                spladeAssembly.vocabulary(),
                spladeAssembly.truncationEvidencePath(),
                spladeConfig);
        spladeEncoderInstance = spladeEncoder;
        wireModelServices(modelWiringLeases, svc -> svc.wireSpladeEncoder(spladeEncoder));
        log.info("SPLADE encoder ready: model={}", spladeConfig.modelPath());

        if (spladeConfig.isIdfQueryMode()) {
          Path idfPath = spladeConfig.modelPath().resolve("idf.json");
          if (Files.isRegularFile(idfPath)) {
            try {
              var idfEncoder =
                  new io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder(
                      idfPath, spladeEncoder.tokenizer(), spladeEncoder.vocabulary());
              spladeIdfQueryEncoder = idfEncoder;
              wireModelServices(modelWiringLeases,
                  svc -> svc.wireSpladeIdfQueryEncoder(idfEncoder));
              log.info("SPLADE IDF query encoder ready: {}", idfPath);
            } catch (Exception e) {
              log.warn("Failed to load IDF table (falling back to ONNX): {}", e.getMessage());
              log.debug("Failed to load IDF table (stack trace)", e);
            }
          } else {
            log.warn("IDF mode requested but idf.json not found at {}", idfPath);
          }
        }
      }

      // Disambiguation
      try {
        var ds = new io.justsearch.indexerworker.disambiguation.DisambiguationService(dataDir);
        ds.open();
        disambiguationService = ds;
        wireModelServices(modelWiringLeases, svc -> svc.wireDisambiguationService(ds));
      } catch (Exception e) {
        log.warn("Failed to initialize disambiguation service (non-fatal): {}", e.getMessage());
        log.debug("Failed to initialize disambiguation service (stack trace)", e);
      }

      // 360: Search reranker (GPU-capable, in Worker process).
      var searchRerankConfig = encoderConfiguration.reranker();
      if (surface.reranker().isPresent()) {
        var rerankAssembly = surface.reranker().get();
        searchRerankerInstance =
            new io.justsearch.reranker.CrossEncoderReranker(
                rerankAssembly.sessions(), rerankAssembly.shape(), rerankAssembly.tokenizer());
        wireModelServices(modelWiringLeases,
            svc -> svc.wireSearchReranker(searchRerankerInstance));
        // F5: Warm up ORT session at startup instead of paying 5-10s on the first user query.
        try {
          long warmStart = System.nanoTime();
          searchRerankerInstance.rerank("warmup", List.of("warmup"), 30_000);
          long warmMs = (System.nanoTime() - warmStart) / 1_000_000;
          log.info("Search reranker ready (gpu={}, warm-up={}ms): model={}",
              searchRerankConfig.gpuEnabled(), warmMs, searchRerankConfig.modelPath());
        } catch (Exception warmE) {
          log.info("Search reranker ready (gpu={}, warm-up failed: {}): model={}",
              searchRerankConfig.gpuEnabled(), warmE.getMessage(), searchRerankConfig.modelPath());
        }
      }

      // Tempdoc 687 R3d: warm the SEARCH path itself (ICU analyzer, Lucene query builder, QPP
      // term-stats, IndexSearcher) so the first real user query after boot doesn't pay the
      // Lucene/ICU JIT + class-load cold-start penalty (measured ~870ms cold vs ~12ms warm).
      // Runs after all encoders above are wired, so the synthetic pass exercises the same
      // production search stack a real query would. Calls WorkerSearchService.warmUpSearchPath()
      // directly, below the port boundary — see its Javadoc + SearchOrchestrator
      // .warmUp()'s Javadoc for why this can't leak into /api/status search telemetry or the
      // Head's app-services feedback layer (feature snapshots / dispositions / GPL triples).
      try {
        long searchWarmStart = System.nanoTime();
        boolean searchWarmed = modelWiringLeases.getFirst().services().searchService()
            .warmUpSearchPath();
        long searchWarmMs = (System.nanoTime() - searchWarmStart) / 1_000_000;
        if (searchWarmed) {
          log.info("Search path ready (warm-up={}ms)", searchWarmMs);
        } else {
          log.info("Search path warm-up skipped (empty index)");
        }
      } catch (Exception searchWarmE) {
        log.info("Search path warm-up failed: {}", searchWarmE.getMessage());
      }

      // Citation scorer (CPU-only). Tempdoc 397 §14.26 T2-E1: eager-wire — construct the full
      // CitationScorer from the surface assembly and pass it to appServices. CitationMatchOps is
      // now a pure consumer with no lazy construction path.
      if (surface.citation().isPresent()) {
        var citationAssembly = surface.citation().get();
        citationScorerInstance =
            new io.justsearch.reranker.CitationScorer(
                citationAssembly.sessions(),
                citationAssembly.shape(),
                citationAssembly.tokenizer());
        wireModelServices(modelWiringLeases,
            svc -> svc.wireCitationScorer(citationScorerInstance));
      }

      // GPU diagnostics suppliers (post-model wiring)
      // observations.md fix: spladeOrtCudaStatus / spladeModelPath are
      // SPLADE-specific slots — they must NOT coalesce with bgeM3 when
      // bgeM3 is active. bgeM3 has its own slot (`bgeM3OrtCudaStatus`,
      // last argument below). The /api/inference/encoders explainer
      // previously dodged the misleading coalesce via policy iteration;
      // the fix makes the diagnostic shape honest.
      java.util.function.Supplier<io.justsearch.ort.OrtCudaStatus> sparseStatusSupplier =
          spladeEncoderInstance != null ? spladeEncoderInstance::getOrtCudaStatus : null;
      java.util.function.Supplier<String> sparseModelPathSupplier =
          spladeEncoderInstance != null ? spladeEncoderInstance::resolvedModelPath : null;
      // observations.md fix: re-read `this.embeddingService` at supplier-call
      // time so post-unload nulls (set by the addEmbeddingProviderChangeListener
      // above) propagate to /api/status. Method-references like
      // `embeddingService::getOrtCudaStatus` would have bound the instance at
      // lambda-creation time and continued returning stale data after close.
      GpuDiagnosticSuppliers diagnostics = new GpuDiagnosticSuppliers(
              sparseStatusSupplier,
              sparseModelPathSupplier,
              () -> {
                var es = this.embeddingService;
                return es != null ? es.getOrtCudaStatus() : null;
              },
              () -> {
                var es = this.embeddingService;
                return es != null ? es.resolvedBackendId() : null;
              },
              () -> {
                // gpuLayers returns int (primitive). The consumer
                // (`IndexStatusOps.buildGpu`) auto-unboxes the Integer
                // result via `setEmbedGpuLayers(int)`. Returning null
                // here would NPE the consumer (regression caught by
                // SchemaMismatchStatusContractTest 2026-05-09 — fixed
                // below by returning 0 when no embedding service is
                // loaded, matching the "no GPU layers" contract).
                var es = this.embeddingService;
                return es != null ? es.gpuLayers() : 0;
              },
              searchRerankerInstance != null ? searchRerankerInstance::getOrtCudaStatus : null,
              nerServiceInstance != null ? nerServiceInstance::getOrtCudaStatus : null,
              citationScorerInstance != null ? citationScorerInstance::getOrtCudaStatus : null,
              bgeM3EncoderInstance != null ? bgeM3EncoderInstance::getOrtCudaStatus : null);
      wireModelServices(modelWiringLeases, svc -> svc.wireGpuDiagnostics(diagnostics));

      // Tempdoc 394 follow-up: publish per-stage enabled state on /api/status.
      // "Enabled" here means the service is usable — config-enabled AND
      // initialization succeeded. A non-null instance satisfies both.
      wireModelServices(modelWiringLeases,
          svc -> svc.wireStageEnabled(embeddingService != null,
              spladeEncoderInstance != null, nerServiceInstance != null));

      publishEncoderComposition();

      // 332 + 397 §14.28 U3: release the shared modelReadyLatch after ALL models are
      // wired (embedding + ECC + SPLADE + BGE-M3 + disambiguation + NER + reranker +
      // citation). This closes both (a) the SPLADE timing gap from 312 — migration
      // enumerator now waits until sparse vectors are available — and (b) the query-
      // handler boot-race — WorkerSearchService.awaitModelsReady unblocks here. See the
      // modelReadyLatch field Javadoc for the full consumer list before changing the
      // release point.
      modelReadyLatch.countDown();

      long bgMs = (System.nanoTime() - bgStart) / 1_000_000;
      log.info("Background model init complete ({}ms)", bgMs);
      return new ModelContext(
          embeddingService,
          embeddingCompatController,
          nerServiceInstance,
          spladeEncoderInstance,
          spladeIdfQueryEncoder,
          bgeM3EncoderInstance,
          disambiguationService);

    } catch (Exception e) {
      if (encoderComponent != null) {
        encoderComponent.transition(io.justsearch.core.component.ComponentState.FAILED, null,
            "encoder_service_wiring_failed");
      }
      logBackgroundInitFailure(e);
      long bgMs = (System.nanoTime() - bgStart) / 1_000_000;
      log.info("Background model init failed after ({}ms)", bgMs);
      return new ModelContext(
          embeddingService,
          embeddingCompatController,
          nerServiceInstance,
          spladeEncoderInstance,
          spladeIdfQueryEncoder,
          bgeM3EncoderInstance,
          disambiguationService);
    } finally {
      // Ensure enumerator is unblocked even if init failed partway through.
      modelReadyLatch.countDown();
      EncoderSet initial = initialEncoderSet;
      if (initial != null) initial.releaseModelReady();
      if (recordedCandidate != null
          && generationBootDisposition == IndexGenerationManager.BootDisposition.BUILDING
          && !closeStarted) {
        try {
          CandidateModels selected = composeRecordedCandidateModels();
          candidateModels = selected;
          if (!(appServices instanceof DefaultWorkerAppServices current)) {
            throw new IllegalStateException("Recorded Green has no candidate producer services");
          }
          var candidateFingerprint = recordedCandidateFingerprint.runtimeFingerprintInputs()
              .embeddingModel();
          var candidateEcc = new EmbeddingCompatibilityController(
              () -> ingestLifecycle.latestCommitUserDataBestEffort(),
              this::trustworthyDocCountOrThrow,
              this::trustworthyCompletedEmbeddingCountOrThrow,
              () -> java.util.Optional.ofNullable(candidateFingerprint.sha()));
          candidateEcc.refresh();
          candidateEmbeddingCompatController = candidateEcc;
          retainProducerModels(current, selected.owner());
          current.wireCandidateProducer(selected.embedding(), selected.bindings());
          current.wireCandidateEmbeddingCompatController(candidateEcc);
          current.startIndexingLoop();
          log.info("Recorded Green candidate model set ready for indexing");
        } catch (Exception failure) {
          migrationEnumeratorFailure = failure;
          log.error("Recorded Green candidate models refused; A remains serving", failure);
        }
      }
      for (ServingLease lease : modelWiringLeases) lease.close();
    }
  }

  /** Latch release also happens on failure; only the actual composed-and-wired surface certifies readiness. */
  void publishEncoderComposition() {
    if (encoderComponent == null || inferenceSurface == null) return;
    var observation = inferenceSurface.componentObservation();
    var missing = java.util.EnumSet.noneOf(io.justsearch.ort.EncoderRole.class);
    missing.addAll(observation.missingRoles());
    for (var role : observation.requestedRoles()) {
      boolean wired = switch (role) {
        case EMBEDDING -> embeddingService != null && embeddingService.isAvailable();
        case BGE_M3 -> bgeM3EncoderInstance != null;
        case SPLADE -> spladeEncoderInstance != null;
        case NER -> nerServiceInstance != null;
        case RERANKER -> searchRerankerInstance != null;
        case CITATION -> citationScorerInstance != null;
      };
      if (!wired) missing.add(role);
    }
    observation.configurationDigest().ifPresent(encoderComponent::setDesiredVersion);
    if (observation.configurationDigest().isPresent() && missing.isEmpty()) {
      encoderComponent.setAppliedVersion(observation.configurationDigest().orElseThrow());
    }
    boolean ready = observation.configurationDigest().isPresent()
        && observation.hasRequestedRoles() && missing.isEmpty();
    boolean intentionallyAbsent = observation.configurationDigest().isPresent()
        && !observation.hasRequestedRoles();
    String evidence = observation.configurationDigest().isEmpty() ? "encoder_observation_unknown"
        : missing.isEmpty()
        ? (observation.hasRequestedRoles() ? null : "no_encoder_roles_requested")
        : "missing_roles=" + missing.stream()
            .map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
    encoderComponent.transition(ready ? io.justsearch.core.component.ComponentState.READY
        : intentionallyAbsent ? io.justsearch.core.component.ComponentState.ABSENT
        : io.justsearch.core.component.ComponentState.UNAVAILABLE, null, evidence);
  }


  /**
   * Wires the WorkerOpsMetricCatalog (Phase 3c). Replaces the legacy
   * {@code registerOtelObservableCallbacks} (which used the now-retired
   * {@code Telemetry.meter(scope)}) and the per-gauge {@code Telemetry.gauge(...)} calls.
   */
  /** Tempdoc 885 item 21e — reads one queue meter, or 0 when the queue is not the SQLite one. */
  private long queueMeter(
      java.util.function.ToLongFunction<
              io.justsearch.indexerworker.queue.QueueThroughputMeters>
          reader) {
    return jobQueue instanceof SqliteJobQueue q ? reader.applyAsLong(q.throughputMeters()) : 0L;
  }

  private void registerTelemetryGauges() {
    if (telemetry == null) return;
    if (!(telemetry instanceof LocalTelemetry lt)) return;
    var sources =
        new io.justsearch.indexerworker.services.WorkerOpsMetricCatalog.Sources(
            this::safeJobQueueDepth,
            this::safePendingJobs,
            this::safeProcessingJobs,
            this::safePendingReadyJobs,
            this::safePendingBackoffJobs,
            this::safeSwitchBufferDepth,
            () -> {
              String st = appServices == null ? "" : appServices.indexingLoopState();
              return io.justsearch.indexerworker.loop.IndexingLoop.LoopState.PAUSED.name().equals(st) ? 1L : 0L;
            },
            this::safePendingEmbeddings,
            this::safePendingVdu,
            // Tempdoc 885 item 3: pacing attribution. Without these the duty cycle is
            // unobservable in the field — the same gap that made the item's own baseline unable
            // to count a single breath-hold (§B.2a).
            () -> indexingPacing.pacedIntervalsTotal(),
            () -> indexingPacing.observedDutyPct(),
            () -> (long) foregroundLoad.inFlight(),
            // Tempdoc 885 item 21e: RISK-002's instrument. Read straight off the queue's own
            // meters, which are the only thing that sees a row admitted or claimed. The pattern
            // match rather than a JobQueue interface method is deliberate: the meters type lives
            // beside the SQLite implementation, and RISK-002 is specifically about THAT
            // implementation's single connection + single lock. A non-SQLite queue reports zero
            // because the risk this measures would not be its risk.
            () -> queueMeter(m -> m.enqueueRatePerMinute()),
            () -> queueMeter(m -> m.dequeueRatePerMinute()),
            () -> queueMeter(m -> m.lockWaitMaxMs()),
            () -> queueMeter(m -> m.lockWaitAvgMs()));
    this.workerOpsCatalog =
        new io.justsearch.indexerworker.services.WorkerOpsMetricCatalog(
            lt.registry(), OperationalMetrics.getInstance(),
            sources);
    JvmRuntimeGauges.register(telemetry, "worker");
  }

  private void maybeAutoStartEmbeddingRebuildForBlockedLegacyBestEffort() {
    if (generationBootDisposition != IndexGenerationManager.BootDisposition.NATIVE) return;
    io.justsearch.indexerworker.loop.ops.EmbeddingRecoveryOps.rescueBlockedLegacyIndex(
        embeddingCompatController, ingestLifecycle, 1000, log);
  }

  /**
   * Constructs, refreshes and wires the {@link EmbeddingCompatibilityController} (tempdoc 819 A).
   *
   * <p>Called synchronously from {@link #start()} BEFORE {@code startIndexingLoop()}, because a
   * commit that lands before the controller exists cannot carry the embedding fingerprint, and a
   * {@code refresh()} that runs after such a commit sees {@code docCount > 0} with no stored
   * fingerprint and resolves BLOCKED_LEGACY. On a fresh profile the Head's bundled help batch is
   * exactly such a commit, which made the empty-index fast path unreachable on every first launch.
   *
   * <p>Both index-reading suppliers RE-READ {@code ingestLifecycle} on every call rather than
   * binding the runtime's ops at construction time: {@code DeferredRuntime.prepareWriterUpgrade()}
   * (DeferredRuntime.java:70-93) builds a NEW {@code RuntimeSession} and closes the old one, so a
   * bound method reference captured here would later read a closed session.
   *
   * <p>Both count suppliers use the {@code …OrThrow} variants: the plain {@code IndexCountOps}
   * accessors swallow {@code IOException} to 0, and 0 is exactly the value that reads as "empty
   * index — safe to stamp" / "no successful embedding". They must fail closed, not fail empty.
   */
  private void initEmbeddingCompatibilityController() {
    var ecc =
        new EmbeddingCompatibilityController(
            () -> {
              LuceneRuntime rt = this.ingestLifecycle;
              return rt == null ? Map.of() : rt.latestCommitUserDataBestEffort();
            },
            this::trustworthyDocCountOrThrow,
            this::trustworthyCompletedEmbeddingCountOrThrow);
    ecc.refresh();
    embeddingCompatController = ecc;
    if (corruptionRecoveryRebuildStarted || isResumedEmptyCorruptionRecovery()) {
      ecc.permitStampWithoutEmbeddingEvidence("corrupt_index_rebuild");
    }
    // Tempdoc 730 A1 REVERTED (post-review): the unconditional EmbeddingFingerprint::get
    // supplier was refuted by adversarial review. A forced reindex is in-place/incremental
    // (JobBatchExtractor.java:193-212 — no wipe), so an interrupted BLOCKED_MISMATCH/
    // BLOCKED_LEGACY -> REBUILDING run can hold a MIXED index (old-model vectors alongside
    // new-model vectors). Stamping unconditionally on model availability means an ordinary
    // commit mid-rebuild persists the NEW model's fingerprint over that mixed index; restart
    // then resolves COMPATIBLE and silently serves the mixture. `ecc::fingerprintToStamp`
    // (gated on state() == COMPATIBLE or (REBUILDING && rebuildCompleted), plus the tempdoc 819
    // stamp-evidence gate) is restored: the *serving* decision
    // (allowEmbeddingWrites/allowQueryEmbeddings) is unaffected either way — it was always
    // ECC-state-driven — but the *stamp* must also stay withheld while mixed, i.e.
    // BLOCKED_MISMATCH is the correct outcome for that case, not a silent COMPATIBLE.
    // The real gap the original A1 was reacting to (a completed rebuild, or a fresh-COMPATIBLE
    // index, whose fingerprint stamp never gets a chance to persist because the worker stops
    // before any further commit) is closed at the completion-guarantee call sites instead:
    // EmbeddingProviderLifecycle.tryFinalizeRebuild() and IndexingLoop.finalizeShutdownCommit()
    // (tempdoc 730 review item 2). See docs/tempdocs/730-worker-lifecycle-integrity.md's dated
    // post-review note for the full review finding.
    //
    // Tempdoc 819: the supplier moves here WITH the controller. Leaving it in initDeferredModels
    // would reopen the same hole from the other side — every commit between the loop starting and
    // the models finishing (the help batch again) would omit the fingerprint, so the index the
    // early refresh just certified COMPATIBLE would still persist unstamped.
    // Preserve a known OLD fingerprint while mismatch blocks embedding writes. This cannot
    // certify the current model; mixed REBUILDING still withholds both fingerprints.
    embeddingFingerprintSupplier.set(ecc::fingerprintForCommit);
    appServices.wireEmbeddingCompatController(ecc);
  }

  /** Re-derive only the empty-Green corruption waiver; source alone cannot attest old vectors. */
  private boolean isResumedEmptyCorruptionRecovery() {
    if (indexGenerationManager == null || buildingIndexPath == null) return false;
    try {
      var state = indexGenerationManager.readStateBestEffort();
      if (state == null || state.building_generation() == null
          || !("MIGRATING".equals(state.migration_state()) || "SWITCHING".equals(state.migration_state()))) {
        return false;
      }
      String generation = state.building_generation();
      if (!indexGenerationManager.resolveGenerationPathStrict(generation).equals(buildingIndexPath)
          || !MigrationSource.CORRUPT_INDEX_REBUILD.wire().equals(
              indexGenerationManager.readGenerationSourceBestEffort(generation))) {
        return false;
      }
      return trustworthyDocCountOrThrow() == 0;
    } catch (IOException | RuntimeException unavailable) {
      log.warn("Cannot establish empty corruption-recovery Green: {}", unavailable.getMessage());
      return false;
    }
  }

  /**
   * Doc count that PROPAGATES a reader failure. See
   * {@link #initEmbeddingCompatibilityController()} for why swallowing to 0 is unsafe here.
   */
  private long trustworthyDocCountOrThrow() {
    LuceneRuntime rt = this.ingestLifecycle;
    if (rt == null) {
      throw new IllegalStateException("ingest runtime unavailable; doc count unreadable");
    }
    try {
      return rt.indexCountOps().docCountOrThrow();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException("doc count read failed", e);
    }
  }

  /** Completed-embedding count that PROPAGATES a reader failure (tempdoc 819 defect B). */
  private int trustworthyCompletedEmbeddingCountOrThrow() {
    LuceneRuntime rt = this.ingestLifecycle;
    if (rt == null) {
      throw new IllegalStateException("ingest runtime unavailable; embedding count unreadable");
    }
    try {
      return rt.indexCountOps()
          .countByFieldOrThrow(
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException("completed-embedding count read failed", e);
    }
  }

  private long safeJobQueueDepth() {
    return KnowledgeServerSafeMetrics.safeJobQueueDepth(jobQueue);
  }

  private long safePendingJobs() {
    return KnowledgeServerSafeMetrics.safePendingJobs(jobQueue, safeJobQueueDepth());
  }

  private long safeProcessingJobs() {
    return KnowledgeServerSafeMetrics.safeProcessingJobs(jobQueue);
  }

  private long safePendingReadyJobs() {
    return KnowledgeServerSafeMetrics.safePendingReadyJobs(jobQueue);
  }

  private long safePendingBackoffJobs() {
    return KnowledgeServerSafeMetrics.safePendingBackoffJobs(jobQueue);
  }

  private long safeSwitchBufferDepth() {
    return KnowledgeServerSafeMetrics.safeSwitchBufferDepth(jobQueue);
  }

  private int safePendingEmbeddings() {
    return KnowledgeServerSafeMetrics.safePendingEmbeddings(
        ingestLifecycle != null ? ingestLifecycle.indexCountOps() : null);
  }

  private int safePendingVdu() {
    return KnowledgeServerSafeMetrics.safePendingVdu(
        ingestLifecycle != null ? ingestLifecycle.indexCountOps() : null);
  }

  private LuceneRuntimeBuilder buildIndexRuntime(
      Path indexPath,
      java.util.function.Supplier<java.util.Optional<String>> fingerprintSupplier) {
    boolean candidateRuntime = recordedCandidate != null
        && (indexPath.equals(buildingIndexPath)
            || (generationBootDisposition == IndexGenerationManager.BootDisposition.PROMOTED
                && indexPath.equals(activeIndexPath)));
    ResolvedConfig runtimeConfig = candidateRuntime
        ? recordedCandidate.configuration() : startupConfiguration;
    var runtimeFingerprints = candidateRuntime
        ? Objects.requireNonNull(recordedCandidateFingerprint,
            "Recorded candidate fingerprint inputs")
        : null;
    // Load field catalog via centralized configuration loader
    io.justsearch.configuration.JustSearchConfigurationLoader loader =
        new io.justsearch.configuration.JustSearchConfigurationLoader();
    io.justsearch.configuration.FieldCatalogDef catalog = loader.loadFieldCatalog();

    // Apply vector dimension override for BGE-M3 (1024-dim vs nomic-embed's 768-dim)
    String sparseModel = runtimeConfig.ai().sparseModel();
    if ("bge-m3".equalsIgnoreCase(sparseModel)) {
      catalog = catalog.withVectorDimension(1024);
      log.info("Field catalog: vector dimension overridden to 1024 (BGE-M3 active)");
    }

    // Create runtime with embedding + SPLADE fingerprint overlays
    java.util.function.Supplier<io.justsearch.indexing.runtime.CommitMetadataSource>
        metadataSupplier = runtimeFingerprints == null
            ? () -> new EmbeddingMetadataOverlay(
                new SsotCommitMetadataSource(runtimeConfig), fingerprintSupplier,
                SpladeFingerprint::get)
            : () -> {
              var inputs = runtimeFingerprints.runtimeFingerprintInputs();
              return new EmbeddingMetadataOverlay(
                  new SsotCommitMetadataSource(runtimeConfig, inputs),
                  () -> {
                    var candidateEcc = candidateEmbeddingCompatController;
                    return candidateEcc == null ? java.util.Optional.empty()
                        : candidateEcc.fingerprintForCommit();
                  },
                  () -> java.util.Optional.ofNullable(inputs.spladeModel().sha()));
            };

    IndexSchema schema =
        new IndexSchema(
            new io.justsearch.adapters.lucene.runtime.FieldMapper(catalog),
            new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
            metadataSupplier,
            new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
            null);
    LuceneRuntimeBuilder builder = schema.atPath(indexPath)
        .withConfig(runtimeConfig).withExecutorRegistrations(luceneExecutors);
    // Tempdoc 406 observability: wire WorkerLuceneTelemetryAdapter so commit /
    // backpressure / drain / swap / lock-contention events flow into
    // metrics-worker.ndjson under the index.runtime.* namespace.
    if (telemetry != null) {
      builder.withTelemetry(
          new io.justsearch.indexerworker.services.WorkerLuceneTelemetryAdapter(
              indexRuntimeCatalog));
    }
    // Tempdoc 885 item 19: the reopen-on-demand seam must fire for user-facing reads only.
    // ForegroundLoad is the one component that knows a search-family call is in flight (item 3's
    // gauge, fed by ForegroundLoadGate since item A9); adapters-lucene cannot see it, so it arrives as
    // a predicate. Without this, enrichment-backfill document fetches reopened the searcher.
    builder.withForegroundActive(() -> foregroundLoad.inFlight() > 0);
    return builder;
  }

  /**
   * The vector dimension the {@code FieldMapper} actually builds vector fields with, published
   * process-wide so {@code index_fingerprint} records the dimension in force rather than the
   * catalog's declared 768 (tempdoc 915 §C). It used to be an instance setter on one
   * {@code SsotCommitMetadataSource}, so the status surface's own fresh instance computed a
   * different fingerprint than the commit path did.
   */
  private java.util.function.Supplier<Integer> effectiveVectorDimensionSupplier() {
    boolean bgeM3 = "bge-m3".equalsIgnoreCase(startupConfiguration.ai().sparseModel());
    return () -> bgeM3 ? 1024 : null;
  }

  /**
   * The metadata this runtime would commit — the "expected" side of every parity comparison. One
   * builder, so the pre-open check, the open-time guard and the commit itself cannot disagree.
   */
  private Map<String, Object> expectedCommitMetadata(
      java.util.function.Supplier<java.util.Optional<String>> fingerprintSupplier) {
    return new EmbeddingMetadataOverlay(
            new SsotCommitMetadataSource(startupConfiguration),
            fingerprintSupplier,
            SpladeFingerprint::get)
        .build();
  }

  private LuceneRuntimeBuilder buildReadOnlyRuntime(Path indexPath) {
    readOnlyOpens.incrementAndGet();
    io.justsearch.configuration.JustSearchConfigurationLoader loader =
        new io.justsearch.configuration.JustSearchConfigurationLoader();
    io.justsearch.configuration.FieldCatalogDef catalog = loader.loadFieldCatalog();
    String sparseModel = startupConfiguration.ai().sparseModel();
    if ("bge-m3".equalsIgnoreCase(sparseModel)) {
      catalog = catalog.withVectorDimension(1024);
    }
    LuceneRuntimeBuilder builder = IndexSchema.fromCatalog(catalog).atPath(indexPath)
        .withConfig(startupConfiguration).withExecutorRegistrations(luceneExecutors);
    if (telemetry != null) {
      builder.withTelemetry(
          new io.justsearch.indexerworker.services.WorkerLuceneTelemetryAdapter(
              indexRuntimeCatalog));
    }
    return builder;
  }

  /**
   * Validates that the embedding model's output dimension matches the index schema.
   *
   * <p>This fail-fast check prevents a common misconfiguration where the embedding model
   * produces vectors of a different dimension than the index expects. Without this check,
   * the mismatch would only surface at indexing time, potentially corrupting the index
   * or causing silent retrieval failures.
   *
   * @throws IOException if dimensions don't match (fail-fast)
   */
  private void validateEmbeddingDimension() throws IOException {
    if (embeddingService == null || ingestLifecycle == null) {
      return;
    }

    int modelDimension = embeddingService.dimension();
    Integer schemaDimension = ingestLifecycle.schema().ssotVectorDimension();

    if (schemaDimension == null) {
      // Schema doesn't define a vector field - vectors will be ignored
      log.warn("Embedding model produces dimension={} but index schema has no vector field defined. "
          + "Vectors will NOT be indexed. To enable vector search, add a vector field to SSOT/catalogs/fields.v1.json",
          modelDimension);
      return;
    }

    if (modelDimension == 0) {
      // Model dimension not known yet (will be detected on first embedding)
      log.info("Model dimension not yet known; will validate on first embedding");
      return;
    }

    if (modelDimension != schemaDimension) {
      String message = String.format(
          "SCHEMA MISMATCH: Embedding model produces dimension=%d but index schema expects dimension=%d. "
              + "Either use a different embedding model or update SSOT/catalogs/fields.v1.json to match.",
          modelDimension, schemaDimension);
      log.error(message);
      throw new IOException(message);
    }

    log.info("Schema validation passed: model dimension={} matches schema dimension={}",
        modelDimension, schemaDimension);
  }

  /**
   * Logs the configuration at startup for debugging and observability.
   */
  private void logConfiguration() {
    log.info("╔══════════════════════════════════════════════════════════════╗");
    log.info("║              JustSearch Worker Configuration                 ║");
    log.info("╠══════════════════════════════════════════════════════════════╣");
    log.info("║ Data directory:   {}", padRight(dataDir.toString(), 44) + "║");
    log.info("║ Index base path:  {}", padRight(String.valueOf(indexBasePath), 44) + "║");
    log.info("║ Active index dir: {}", padRight(String.valueOf(activeIndexPath), 44) + "║");
    log.info("║ Build index dir:  {}", padRight(String.valueOf(buildingIndexPath), 44) + "║");
    log.info("║ Jobs DB path:     {}", padRight(dataDir.resolve("jobs.db").toString(), 44) + "║");

    // SSOT paths
    ResolvedConfig captured = startupConfiguration;
    String ssotPath = captured != null && captured.paths().ssotPath() != null
        ? captured.paths().ssotPath().toString() : null;
    Path effectiveRepoRoot = RepoRootLocator.findRepoRootOrNull();
    String repoRoot = effectiveRepoRoot != null ? effectiveRepoRoot.toString() : "auto-detect";
    if (ssotPath == null || ssotPath.isBlank()) {
      ssotPath = effectiveRepoRoot != null ? effectiveRepoRoot.resolve("SSOT").toString() : "auto-detect";
    }
    log.info("║ SSOT path:        {}", padRight(ssotPath, 44) + "║");
    log.info("║ Repo root:        {}", padRight(repoRoot, 44) + "║");

    // Search pipeline configuration
    if (captured != null && captured.hybridSearch() != null) {
      ResolvedConfig.HybridSearch hs = captured.hybridSearch();
      log.info("╠══════════════════════════════════════════════════════════════╣");
      log.info("║ Fusion strategy:  {}", padRight(hs.fusionStrategy(), 44) + "║");
      if ("cc".equals(hs.fusionStrategy())) {
        log.info("║   CC weights:     {}", padRight(
            String.format("sparse=%.2f dense=%.2f splade=%.2f",
                hs.ccWeightSparse(), hs.ccWeightDense(), hs.ccWeightSplade()), 44) + "║");
      }
      log.info("║ Branch fusion:    {}", padRight(hs.branchFusionStrategy(), 44) + "║");
      ConfigResolution chunkAwareRes = captured.resolutions().get("search.chunk_aware.enabled");
      String chunkAware = chunkAwareRes != null && chunkAwareRes.value() != null
          ? chunkAwareRes.value() : "true";
      log.info("║ Chunk-aware merge:{}", padRight(chunkAware, 44) + "║");
    }

    log.info("╚══════════════════════════════════════════════════════════════╝");
  }

  private static String padRight(String s, int n) {
    if (s == null) s = "null";
    if (s.length() > n) {
      return "..." + s.substring(s.length() - (n - 3));
    }
    return String.format("%-" + n + "s", s);
  }

  private static final long CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L; // daily
  private static final int CLEANUP_RETENTION_DAYS = 30;
  // Tempdoc 410 §8 / review fix #6 — ingestion ledger outlives queue rows so "why is this file
  // missing from search?" questions remain answerable past the queue retention window.
  private static final int LEDGER_RETENTION_DAYS = 180;

  /** Same daily cleanup operation as the sentinel, exposed in-package for deterministic proof. */
  void runPeriodicCleanup(long now) {
    try {
      int deleted = jobQueue.cleanupOldJobs(CLEANUP_RETENTION_DAYS);
      if (deleted > 0) {
        log.info("Periodic cleanup: removed {} old jobs (>{} days)", deleted, CLEANUP_RETENTION_DAYS);
      }
    } catch (Exception e) {
      log.warn("Periodic job cleanup failed (non-fatal)", e);
    }
    try {
      int deletedLedger = jobQueue.cleanupOldLedgerEvents(LEDGER_RETENTION_DAYS);
      if (deletedLedger > 0) {
        log.info("Periodic cleanup: removed {} old ledger events (>{} days)",
            deletedLedger, LEDGER_RETENTION_DAYS);
      }
    } catch (Exception e) {
      log.warn("Periodic ledger cleanup failed (non-fatal)", e);
    }
    try {
      if (pathResolutionStore != null) {
        ResolvedConfig current = liveConfiguration == null ? startupConfiguration : liveConfiguration.get();
        int retentionDays = current.paths().pathResolutionRetentionDays();
        int deletedPaths = pathResolutionStore.pruneOldRemoved(now - TimeUnit.DAYS.toMillis(retentionDays));
        if (deletedPaths > 0) {
          log.info("Periodic cleanup: removed {} old path resolutions (>{} days)", deletedPaths, retentionDays);
        }
      }
    } catch (Exception e) {
      log.warn("Periodic path-resolution cleanup failed (non-fatal)", e);
    }
  }

  private void startSentinelThread() {
    sentinelThread = new Thread(() -> {
      log.info("Sentinel thread started");
      boolean lastMainGpuActive = true; // Assume Main has GPU initially
      long lastCleanupMs = System.currentTimeMillis();
      while (running && !Thread.currentThread().isInterrupted()) {
        try {
          Thread.sleep(1000); // Check every second

          // Lane F item A10: the suicide-pact arm is gone with the memory-mapped bus that fed it.
          // In one JVM there is no heartbeat to miss, so self-termination on a stale beat could
          // only ever be a false positive. Shutdown is now exclusively the ordered in-process
          // sequence the composition root drives.

          // Dev hot-reload: check for reload signal from Gradle continuous build
          if (devReloadManager != null && devReloadManager.isReloadRequested()) {
            log.info("Sentinel detected reload signal");
            devReloadManager.performReload();
          }

          // GPU lifecycle monitoring: release reranker VRAM when Main claims GPU
          boolean currentMainGpuActive = signalBus.isMainGpuActive();
          if (currentMainGpuActive && !lastMainGpuActive) {
            log.info("GPU lifecycle: Main claimed GPU, releasing reranker VRAM");
            if (appServices != null) {
              appServices.onMainClaimedGpu();
            }
          }
          lastMainGpuActive = currentMainGpuActive;

          // Periodic job queue cleanup: remove old DONE/FAILED rows
          long now = System.currentTimeMillis();
          if (jobQueue != null && now - lastCleanupMs > CLEANUP_INTERVAL_MS) {
            runPeriodicCleanup(now);
            lastCleanupMs = now;
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      log.info("Sentinel thread exiting");
    }, "knowledge-server-sentinel");

    sentinelThread.setDaemon(true);
    sentinelThread.start();
  }

  /**
   * The {@code index_fingerprint} this runtime would stamp, or null when it cannot be computed.
   *
   * <p>Guarded: {@code SsotCommitMetadataSource.build()} reads SSOT artifacts off disk and throws
   * {@code IllegalStateException} when any is unreadable. Letting that escape from inside the
   * schema-mismatch catch would replace the real, actionable mismatch with an unrelated IO failure.
   */
  /**
   * Charges one rebuild attempt to {@code targetFingerprint}, or charges nothing when there is no
   * target to charge it to.
   *
   * <p>An uncomputable fingerprint is not a target. Folding those boots into a shared bucket (the
   * literal string {@code "null"}, which is what {@code String.valueOf} produces) would let three
   * boots with an unreadable model file exhaust the budget for a completely unrelated real shape,
   * so the index that genuinely needed a rebuild would never get one. Package-private so the
   * distinction is testable without a running Worker.
   */
  static int recordAutoRebuildAttemptOrSkip(
      IndexGenerationManager genManager, String targetFingerprint) throws IOException {
    if (targetFingerprint == null) {
      return 1;
    }
    return genManager.recordAutoRebuildAttempt(targetFingerprint);
  }

  private String expectedIndexFingerprintOrNull() {
    try {
      Object fp = new SsotCommitMetadataSource(startupConfiguration).build().get(IndexFingerprint.COMMIT_META_KEY);
      String s = fp == null ? null : String.valueOf(fp);
      return s == null || s.isBlank() ? null : s;
    } catch (RuntimeException ex) {
      log.warn(
          "Could not compute the target index_fingerprint for the rebuild brake: {}",
          ex.getMessage());
      return null;
    }
  }

  private static boolean hasLuceneSegments(Path indexPath) {
    if (!Files.exists(indexPath)) {
      return false;
    }
    try (var entries = Files.list(indexPath)) {
      return entries.anyMatch(p -> p.getFileName().toString().startsWith("segments"));
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * The composed application services (lane F stage A item A6).
   *
   * <p>Public so the Engine composition root can bind the ports over the same instance the
   * indexing loop uses. Null before {@link #start()}, and REPLACED on
   * a deferred-runtime upgrade or a dev hot-reload — callers must re-read it rather than cache it.
   */
  public WorkerAppServices appServices() {
    return appServices;
  }

  /** Captures one published physical view under the process configuration publication guard. */
  public ServingLease captureServingView() {
    publicationLock.readLock().lock();
    try {
      synchronized (servingViewMonitor) {
        ServingView current = servingView;
        if (closeStarted || current == null || current.retiring) {
          throw new IllegalStateException("Index serving view is unavailable");
        }
        current.holders++;
        return new ServingLease(current);
      }
    } finally {
      publicationLock.readLock().unlock();
    }
  }

  /** Installs a fully composed owner view; called only after fallible preparation succeeds. */
  void publishServingView(WorkerAppServices preparedServices) {
    publicationLock.writeLock().lock();
    try {
      synchronized (servingViewMonitor) {
        if (servingView != null && (!servingView.retiring || servingView.holders != 0)) {
          throw new IllegalStateException("Prior index serving view is still active");
        }
        ServingView previous = servingView;
        ServingView successor = new ServingView(
            Objects.requireNonNull(preparedServices, "preparedServices"),
            searchLifecycle, ingestLifecycle, activeIndexPath);
        if (previous != null && previous.encoderSet != null) {
          successor.attachEncoderSet(previous.encoderSet);
        }
        appServices = preparedServices;
        servingView = successor;
        if (previous != null) previous.releaseEncoderSet();
      }
    } finally {
      publicationLock.writeLock().unlock();
    }
  }

  /** Installs a prepared writer successor while already-issued read-only A leases continue. */
  private ServingView publishDeferredSuccessor(WorkerAppServices preparedServices,
      WorkerAppServices oldServices, RunningRuntime upgraded,
      DeferredRuntime.PreparedUpgrade preparation) {
    ServingView successor = new ServingView(preparedServices, upgraded, upgraded, activeIndexPath);
    ServingView old;
    publicationLock.writeLock().lock();
    try {
      synchronized (servingViewMonitor) {
        old = servingView;
        if (closeStarted || old == null || old.retiring || old.services != oldServices) {
          throw new IllegalStateException("Deferred successor lost its serving predecessor");
        }
        Runnable retireCleanup = () -> {
          try {
            oldServices.close();
          } catch (IOException failure) {
            throw new IllegalStateException("Old application services still own resources", failure);
          }
          preparation.retireReader();
          old.releaseEncoderSet();
        };
        try {
          if (old.encoderSet != null) successor.attachEncoderSet(old.encoderSet);
          // Reserve the fallible retirement slot before the upgrade becomes published.
          retiredServingViews.add(old);
          preparation.markPublished();
        } catch (RuntimeException | Error failure) {
          retiredServingViews.remove(old);
          successor.releaseEncoderSet();
          throw failure;
        }
        old.retiring = true;
        old.retireCleanup = retireCleanup;
        searchLifecycle = upgraded;
        ingestLifecycle = upgraded; // writer fault source was bound during candidate construction
        appServices = preparedServices;
        servingView = successor;
        servingViewMonitor.notifyAll();
      }
    } finally {
      publicationLock.writeLock().unlock();
    }
    return old;
  }

  /** Cleanup starts only after A's actual issued work exits, outside owner/publication monitors. */
  private void cleanRetiredServingView(ServingView retired) {
    Runnable cleanup;
    synchronized (servingViewMonitor) {
      if (retired.holders != 0 || retired.retireCleanup == null || retired.cleanupRunning) return;
      retired.cleanupRunning = true;
      cleanup = retired.retireCleanup;
    }
    try {
      cleanup.run();
      synchronized (servingViewMonitor) {
        retired.retireCleanup = null;
        retiredServingViews.remove(retired);
        servingViewMonitor.notifyAll();
      }
      retryCommittedGenerationRetirement();
    } catch (RuntimeException | Error failure) {
      synchronized (servingViewMonitor) {
        retired.cleanupRunning = false;
        servingViewMonitor.notifyAll();
      }
      log.error("Retired index serving view still owns resources", failure);
    }
  }

  /** Reattempt transient close refusals on the existing server-owned maintenance tick. */
  private void retryRetiredServingViews() {
    List<ServingView> snapshot;
    synchronized (servingViewMonitor) { snapshot = List.copyOf(retiredServingViews); }
    for (ServingView retired : snapshot) cleanRetiredServingView(retired);
  }

  /** Notify outside publication/runtime locks: listeners may close their issued work and reconnect. */
  private void notifyServingViewRetirement(ServingView retired) {
    if (retired == null) return;
    List<Runnable> listeners;
    synchronized (servingViewMonitor) {
      listeners = List.copyOf(retired.retirementListeners);
      retired.retirementListeners.clear();
    }
    for (Runnable listener : listeners) {
      try { listener.run(); }
      catch (RuntimeException failure) {
        log.warn("Retiring index serving view could not notify an issued stream", failure);
      }
    }
  }

  /** Reclaim a committed predecessor only after replay and every old view have settled. */
  private void retryCommittedGenerationRetirement() {
    if (indexGenerationManager == null || closeStarted) return;
    IndexGenerationManager.State state;
    try { state = indexGenerationManager.readStateBestEffort(); }
    catch (RuntimeException unavailable) {
      log.warn("Committed generation retirement awaits readable state", unavailable);
      return;
    }
    if (state == null || state.previous_generation() == null || state.previous_generation().isBlank()
        || state.active_generation() == null || !state.active_generation().startsWith("g-")) return;
    String active = state.active_generation();
    String previous = state.previous_generation();
    // A failed precommit can retain the original pointer in both fields. There is no
    // committed operation to retire in that state, and older boot generations need not
    // have an operation-derived UUIDv7 name.
    if (previous.equals(active)) return;
    synchronized (servingViewMonitor) {
      if (servingView == null || servingView.retiring || !retiredServingViews.isEmpty()
          || activeIndexPath == null || !active.equals(activeIndexPath.getFileName().toString())) {
        log.debug("Predecessor retirement waits for serving view: present={}, retiring={}, "
                + "retiredViews={}, activePath={}, expectedActive={}",
            servingView != null, servingView != null && servingView.retiring,
            retiredServingViews.stream().map(view -> view.activeGenerationPath + ":holders="
                + view.holders + ":cleanupRunning=" + view.cleanupRunning).toList(),
            activeIndexPath, active);
        return;
      }
    }
    try {
      if (IndexGenerationManager.isRecordedGenerationIdentity(active)) {
        if (recordedIngestionLifecycle == null
            || !recordedIngestionLifecycle.committedBulkTerminal(active.substring(2))) {
          log.debug("Predecessor retirement waits for recorded terminal receipt: {}", active);
          return;
        }
      } else if (!(generationBootOwnership instanceof IndexGenerationManager.BootOwnership.Native)
          || !nativePredecessorReplaySettled()) {
        return;
      }
      indexGenerationManager.retirePreviousGeneration(active, previous);
    } catch (IOException | RuntimeException unavailable) {
      log.warn("Committed generation predecessor {} still owns capacity", previous, unavailable);
    }
  }

  /** Native boot can finish replay after a pointer-before-publication crash without a new journal. */
  private boolean nativePredecessorReplaySettled() {
    if (promotedReplaySettled) return true;
    if (generationBootDisposition != IndexGenerationManager.BootDisposition.NATIVE
        || jobQueue == null || !KnowledgeServerMigrationOps.switchBufferEmptyStrict(jobQueue)) {
      return false;
    }
    try {
      var counts = jobQueue.jobStateCountsStrict();
      return counts.pendingCount() == 0 && counts.processingCount() == 0;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /** Keep every still-issued view alive until its model dependencies are wired and released. */
  private List<ServingLease> captureModelWiringLeases() {
    List<ServingLease> leases = new ArrayList<>();
    runtimeSwapLock.lock();
    try {
      publicationLock.readLock().lock();
      try {
        synchronized (servingViewMonitor) {
          if (servingView == null || servingView.retiring) {
            throw new IllegalStateException("No active serving view for model wiring");
          }
          servingView.holders++;
          leases.add(new ServingLease(servingView));
          for (ServingView retired : retiredServingViews) {
            if (retired.holders > 0 && !retired.cleanupRunning) {
              retired.holders++;
              leases.add(new ServingLease(retired));
            }
          }
        }
      } finally {
        publicationLock.readLock().unlock();
      }
    } finally {
      runtimeSwapLock.unlock();
    }
    return leases;
  }

  private static void wireModelServices(List<ServingLease> leases,
      Consumer<WorkerAppServices> wiring) {
    for (ServingLease lease : leases) wiring.accept(lease.services());
  }

  /** Shutdown cannot release shared index owners while an older published view still survives. */
  private void closeRetiredServingViews() throws IOException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      ServingView retired;
      synchronized (servingViewMonitor) {
        if (retiredServingViews.isEmpty()) return;
        retired = retiredServingViews.getFirst();
        if (retired.holders != 0 || retired.cleanupRunning) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) throw new IOException("Retired index serving view still owns resources");
          try {
            TimeUnit.NANOSECONDS.timedWait(servingViewMonitor, remaining);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for retired index serving view", interrupted);
          }
          continue;
        }
      }
      cleanRetiredServingView(retired);
      synchronized (servingViewMonitor) {
        if (retiredServingViews.contains(retired)) {
          throw new IOException("Retired index serving view cleanup refused; owners retained");
        }
      }
    }
  }

  /** Stops new captures, then waits outside publication for actual users to leave. */
  void retireServingView() throws IOException {
    runtimeSwapLock.lock();
    try {
      retireServingViewOwned();
    } finally {
      runtimeSwapLock.unlock();
    }
  }

  private void retireServingViewOwned() throws IOException {
    ServingView retiring;
    publicationLock.writeLock().lock();
    try {
      synchronized (servingViewMonitor) {
        retiring = servingView;
        if (retiring == null) return;
        retiring.retiring = true;
      }
    } finally {
      publicationLock.writeLock().unlock();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    IOException refusal = null;
    synchronized (servingViewMonitor) {
      while (retiring.holders != 0) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          refusal = new IOException("Index serving view still has active holders; owner retained");
          break;
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(servingViewMonitor, remaining);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          refusal = new IOException("Interrupted waiting for index serving holders", interrupted);
          break;
        }
      }
    }
    if (refusal != null) {
      restoreServingViewAfterRefusedRetirement(retiring);
      throw refusal;
    }
  }

  /** No destructive step has started when the holder drain refuses; A remains usable. */
  private void restoreServingViewAfterRefusedRetirement(ServingView retiring) {
    publicationLock.writeLock().lock();
    try {
      synchronized (servingViewMonitor) {
        if (!closeStarted && servingView == retiring) retiring.retiring = false;
      }
    } finally {
      publicationLock.writeLock().unlock();
    }
  }

  /**
   * The process-scoped foreground-load gauge (tempdoc 885 item 3).
   *
   * <p>Public since lane F stage A item A6: {@code ForegroundLoadGate} in the composition root was
   * the second producer alongside the wire interceptor, and since item A9 deleted that interceptor
   * it is the only one. Either way it must feed THIS instance.
   * Reading the gauge off {@code indexingPacing().foregroundLoad()} instead would be wrong before
   * {@link #start()} has run — the field starts as {@code IndexingPacing.unthrottled()}, which
   * constructs a gauge of its own that nothing paces off.
   */
  public ForegroundLoad foregroundLoad() {
    return foregroundLoad;
  }

  /**
   * Returns the embedding compatibility controller.
   *
   * @return the controller, or null if not initialized
   */
  public EmbeddingCompatibilityController embeddingCompatController() {
    return embeddingCompatController;
  }

  @Override
  public void close() throws IOException {
    synchronized (closeLock) {
      if (shutdownLatch.getCount() == 0) return;
      closeStarted = true;
      // The initializer may be waiting to enter a runtime replacement. Join it before taking
      // runtimeSwapLock; otherwise close owns the lock while waiting for its prospective owner.
      if (!closePrepared) {
        if (deferredModelExecutor != null) deferredModelExecutor.close();
        if (deferredModelInit != null) {
          try {
            deferredModelInit.join();
          } catch (java.util.concurrent.CompletionException
              | java.util.concurrent.CancellationException e) {
            log.warn("Deferred model init completed exceptionally before shutdown: {}", e.toString());
          }
        }
      }
      boolean acquired;
      try {
        acquired = runtimeSwapLock.tryLock(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted waiting for active runtime replacement; server retained", interrupted);
      }
      if (!acquired) {
        log.warn("Server close deadline exceeded; active runtime replacement still owns the server");
        throw new IOException("Active runtime replacement prevented close; server retained for retry");
      }
      try {
        if (!closePrepared) {
          LuceneRuntime currentIngest = ingestLifecycle;
          if (currentIngest instanceof RunningRuntime runningRuntime) {
            runningRuntime.retireTerminalWriterFailureNotifications();
          }
          log.info("Shutting down KnowledgeServer...");
          running = false;

          // Tempdoc 550 Thesis II: stop the periodic stuck-job reaper.
          if (stuckJobReapTask != null) stuckJobReapTask.cancel(true);
          if (stuckJobReaper != null) {
            stuckJobReaper.shutdownNow();
            stuckJobReaper.close(); // Queue closure cannot race a still-running reaper callback.
          }

          retireServingView();
          closeRetiredServingViews();

          // Producer exit precedes native model retirement. The indexing loop may hold an
          // already-selected A or B wrapper after request admission has stopped.
          Throwable serviceCloseFailure = null;
          try { closePendingAppServices(); }
          catch (RuntimeException | Error failure) { serviceCloseFailure = failure; }
          if (appServices != null) {
            try {
              appServices.close();
              appServices = null;
            } catch (Exception | Error failure) {
              if (serviceCloseFailure == null) serviceCloseFailure = failure;
              else if (serviceCloseFailure != failure) serviceCloseFailure.addSuppressed(failure);
            }
          }
          if (serviceCloseFailure instanceof Error fatal) throw fatal;
          if (serviceCloseFailure != null) {
            throw new IOException("Application services still own resources", serviceCloseFailure);
          }

          // The active view is no longer issuing work. Its model hold must leave before the
          // exact owner can enforce the bounded native retirement deadline below.
          synchronized (servingViewMonitor) {
            if (servingView != null) servingView.releaseEncoderSet();
          }

          // Native retirement follows producer exit and precedes tokenizer/resource teardown. A
          // timeout retains the exact sessions, their owners and the index root lock for retry.
          // SessionHandle.close() may report REFUSED without throwing; InferenceSurface checks
          // every handle's typed disposition after attempting the whole set.
          CandidateModels selectedCandidate = candidateModels;
          if (selectedCandidate != null) {
            try {
              selectedCandidate.owner().close();
              candidateModels = null;
            } catch (RuntimeException refusal) {
              throw new IOException("Candidate native inference retirement incomplete; server retained for retry",
                  refusal);
            }
          }
          EncoderSet initialOwner = initialEncoderSet;
          if (initialOwner != null) {
            try {
              initialOwner.close();
            } catch (RuntimeException refusal) {
              throw new IOException("Native inference retirement incomplete; server retained for retry",
                  refusal);
            }
          } else if (inferenceSurface != null) {
            try { inferenceSurface.close(); }
            catch (RuntimeException refusal) {
              throw new IOException("Native inference retirement incomplete; server retained for retry",
                  refusal);
            }
          }

          // Tempdoc 413: emit unload_total{reason=SHUTDOWN} and explicitly flush *before* any close-
          // time shutdown begins. The close-time meterProvider.forceFlush().join(2s) at the tail of
          // LocalTelemetry.close() races the file write — same shutdown gap that affects every other
          // counter in the system (e.g., worker.documents.indexed.total's last value never reaches
          // NDJSON either). Calling LocalTelemetry.flush() here (5s join, SDK fully alive) guarantees
          // the metric lands in metrics-worker.ndjson before any close-time race conditions begin.
          // Counterpart to GPU_HANDOFF emitted from IndexingLoop.unloadEmbeddingService on hybrid-
          // inference VRAM handoff. The actual embeddingService.close() runs later in the close
          // sequence — this emit reflects intent regardless of whether close() succeeds.
          if (embeddingService != null && embeddingTelemetry != null) {
            embeddingTelemetry.onUnload(
                io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.UnloadReason.SHUTDOWN);
            if (telemetry instanceof LocalTelemetry lt) {
              lt.flush();
            }
          }

          // Stop sentinel thread
          if (sentinelThread != null) {
            sentinelThread.interrupt();
            try {
              sentinelThread.join(5_000);  // Allow 5s for sentinel cleanup
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }

          // Close disambiguation service (after indexing loop which uses it)
          if (disambiguationService != null) {
            try {
              disambiguationService.close();
            } catch (Exception e) {
              log.warn("Error closing disambiguation service", e);
            }
          }

          // Close SPLADE encoder (after indexing loop which uses it)
          if (spladeEncoderInstance != null) {
            try {
              spladeEncoderInstance.close();
            } catch (Exception e) {
              log.warn("Error closing SPLADE encoder", e);
            }
          }

          // Close BGE-M3 encoder (after indexing loop which uses it)
          if (bgeM3EncoderInstance != null) {
            try {
              bgeM3EncoderInstance.close();
            } catch (Exception e) {
              log.warn("Error closing BGE-M3 encoder", e);
            }
          }

          // 360: Close search reranker (ORT session + tokenizer)
          if (searchRerankerInstance != null) {
            try {
              searchRerankerInstance.close();
            } catch (Exception e) {
              log.warn("Error closing search reranker", e);
            }
          }

          // Phase 3c: OTel callback handles are managed by LocalTelemetry's gaugeHandles list
          // (each catalog gauge/observable-counter goes through registry.buildGauge/buildObservableCounter
          // which adds the handle there). LocalTelemetry.close() drains them on shutdown.

          // Close tracing (flush spans) before telemetry shuts down.
          if (tracingBootstrap != null) {
            try {
              tracingBootstrap.close();
            } catch (Exception e) {
              log.warn("Error closing tracing", e);
            }
          }

          // Close telemetry (flush best-effort) after loop shutdown so the last stage/commit timings are captured.
          if (telemetry != null) {
            try {
              telemetry.close();
            } catch (Exception e) {
              log.warn("Error closing telemetry", e);
            } finally {
              telemetry = null;
            }
          }

          // Stop migration enumerator thread (best-effort)
          if (migrationEnumeratorThread != null) {
            migrationEnumeratorThread.interrupt();
            try {
              migrationEnumeratorThread.join(10_000);  // Allow 10s for large directory walks
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }

          // Stop migration cutover monitor thread (best-effort)
          if (migrationCutoverThread != null) {
            migrationCutoverThread.interrupt();
            try {
              migrationCutoverThread.join(10_000);  // Allow 10s for cutover cleanup
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }

          closePrepared = true;
        }

        // A timed-out runtime still owns live Lucene children. Attempt both runtimes, but preserve
        // the enclosing stores, executor registrations and root lock if either needs a close retry.
        IOException runtimeCloseFailure = null;
        if (ingestLifecycle != null && ingestLifecycle != searchLifecycle) {
          try {
            ingestLifecycle.close();
          } catch (Exception e) {
            log.warn("Error closing ingest runtime", e);
            runtimeCloseFailure = new IOException("Ingest runtime close incomplete", e);
          }
        }
        if (searchLifecycle != null) {
          try {
            searchLifecycle.close();
          } catch (Exception e) {
            log.warn("Error closing search runtime", e);
            if (runtimeCloseFailure == null) {
              runtimeCloseFailure = new IOException("Search runtime close incomplete", e);
            } else {
              runtimeCloseFailure.addSuppressed(e);
            }
          }
        }
        if (runtimeCloseFailure != null) throw runtimeCloseFailure;

        // Close embedding service. unload_total{reason=SHUTDOWN} was emitted earlier (before
        // telemetry shutdown) so the metric lands in metrics-worker.ndjson regardless of close()'s
        // outcome.
        if (embeddingService != null) {
          try {
            embeddingService.close();
          } catch (Exception e) {
            log.warn("Error closing embedding service", e);
          }
        }

        // Close auxiliary jobs.db stores before the queue connection.
        if (documentIdentityStore != null) {
          try {
            documentIdentityStore.close();
          } catch (Exception e) {
            log.warn("Error closing document-identity store", e);
          }
        }

        if (pathResolutionStore != null) {
          try {
            pathResolutionStore.close();
          } catch (Exception e) {
            log.warn("Error closing path-resolution store", e);
          }
        }

        // Final recorded receipts observe drained indexing. Keep both owners on failure.
        if (recordedIngestionAttachment != null) {
          recordedIngestionAttachment.close();
          recordedIngestionAttachment = null;
        }

        // Retain the queue and index exclusion if native connection cleanup needs retry.
        // EngineRoot must not observe completed shutdown while this mutable owner remains live.
        if (jobQueue != null) {
          jobQueue.close();
        }

        // Close signal bus
        if (signalBus != null) {
          try {
            signalBus.close();
          } catch (Exception e) {
            log.warn("Error closing signal bus", e);
          }
        }

        try { workerExecutors.close(); }
        finally { luceneExecutors.close(); }

        if (indexRootLock != null) {
          // Keep the failed owner available for retry and never signal completed shutdown
          // while native lock cleanup remains unconfirmed.
          indexRootLock.close();
          indexRootLock = null;
        }

        // Stage-A checkpoint (re-review). This countdown used to sit ~90 lines earlier, where the gRPC
        // server's termination used to be, and the comment there called it "releasing the shutdown
        // latch". It marked a point in the MIDDLE of close(): the Lucene runtimes, the embedding
        // service, the migration threads and the index root lock were all still to come. So the latch
        // answered "close() got past step N", which is not a fact anyone wants.
        //
        // It is the last statement of close() now, so it means exactly one thing: this server ran its
        // shutdown to completion. That is what EngineRoot.close() consults.
        if (indexComponent != null) {
          indexComponent.transition(io.justsearch.core.component.ComponentState.ABSENT,
              io.justsearch.app.api.lifecycle.LifecycleReasonCode.WORKER_SHUT_DOWN.code(), null);
          indexComponent.setAppliedVersion(null);
        }
        if (encoderComponent != null) {
          encoderComponent.transition(io.justsearch.core.component.ComponentState.ABSENT, null, null);
          encoderComponent.setAppliedVersion(null);
        }
        shutdownLatch.countDown();
        log.info("KnowledgeServer shutdown complete");
      } finally {
        runtimeSwapLock.unlock();
      }
    }
  }

  /** Native owner disposition used by the process exit authority after ordered close. */
  public io.justsearch.app.api.NativeQuiescence nativeQuiescence() {
    CompletableFuture<ModelContext> initialization = deferredModelInit;
    if (initialization != null && !initialization.isDone()) {
      return io.justsearch.app.api.NativeQuiescence.UNQUIESCED;
    }
    CandidateModels selectedCandidate = candidateModels;
    if (selectedCandidate != null && !selectedCandidate.owner().isClosed()) {
      return io.justsearch.app.api.NativeQuiescence.UNQUIESCED;
    }
    EncoderSet initialOwner = initialEncoderSet;
    if (initialOwner != null && !initialOwner.isClosed()) {
      return io.justsearch.app.api.NativeQuiescence.UNQUIESCED;
    }
    InferenceSurface surface = inferenceSurface;
    // A started initializer with no published surface may have failed after opening a native
    // candidate. No owner can prove its retirement from a null field, so exit conservatively.
    if (surface == null) {
      return initialization == null ? io.justsearch.app.api.NativeQuiescence.QUIESCED
          : io.justsearch.app.api.NativeQuiescence.UNQUIESCED;
    }
    if (surface.retirementStatus()
        == io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED) {
      return io.justsearch.app.api.NativeQuiescence.QUIESCED;
    }
    return io.justsearch.app.api.NativeQuiescence.UNQUIESCED;
  }

  /**
   * Blocks until {@link #close()} has run to completion, or the timeout elapses.
   *
   * <p>{@code true} means close() reached its final statement — every runtime closed, every thread
   * joined or abandoned on its own timeout, the index root lock released. {@code false} means it
   * did not: either close() was never called, or it threw partway and left resources open. A caller
   * that then re-opens the same data directory is the one who finds out, via a held index lock.
   *
   * <p>This exists because the obvious predicate does not work. {@code isRunning()} is
   * {@code running && latch > 0} and {@code close()} sets {@code running = false} in its FIRST
   * statement, so reading {@code isRunning()} after {@code close()} returns is constant-false and
   * can never report a problem.
   */
  public boolean awaitClosed(long timeoutMs) throws InterruptedException {
    return shutdownLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
  }

  // Tempdoc 417 Phase 3c: registerOtelObservableCallbacks() removed — its 25 metrics now flow
  // through WorkerOpsMetricCatalog (constructed in registerTelemetryGauges). Telemetry.meter()
  // retired with this change.

  // Package-private accessors for testing
  JobQueue jobQueueForTests() {
    return jobQueue;
  }

  WorkerSignalBus signalBusForTests() {
    return signalBus;
  }

  LuceneRuntime lifecycleManagerForTests() {
    return ingestLifecycle;
  }

  IndexGenerationManager indexGenerationManagerForTests() {
    return indexGenerationManager;
  }

  void releaseModelReadyLatchForTests() {
    modelReadyLatch.countDown();
  }

  private static IndexGenerationManager.MigrationState parseMigrationState(String raw) {
    return KnowledgeServerMigrationOps.parseMigrationState(raw);
  }

  private void startMigrationCutoverMonitorBestEffort() {
    if (migrationCutoverThread != null) {
      return;
    }
    migrationCutoverThread =
        new Thread(
            () ->
                KnowledgeServerMigrationOps.runMigrationCutoverLoop(
                    new KnowledgeServerMigrationOps.CutoverContext(
                        indexGenerationManager,
                        jobQueue,
                        () -> running,
                        () -> migrationEnumeratorDone
                            && (!(generationBootOwnership instanceof IndexGenerationManager.BootOwnership.Recorded recorded)
                                || recordedIngestionLifecycle.recordedCutoverReady(recorded.operationKey())),
                        () -> migrationEnumeratorFailure,
                        MIGRATION_SWITCHING_QUEUE_DEPTH_THRESHOLD,
                        MIGRATION_SWITCHING_MAX_DURATION_MS,
                        migrationCutoverMaxFailedJobs,
                        () -> ingestLifecycle,
                        this::finalizeEmbeddingRebuildBeforeCutover,
                        this::verifyGreenCommitMetadataBestEffort,
                        this::drainSwitchBufferBestEffort,
                        this::flushTelemetryBestEffort,
                        () -> migrationRestartAction.run(),
                        dataDir,
                        log,
                         () -> {
                           throw new IOException("Live cutover requires its prepared serving successor");
                         },
                         this::enterSwitchingWithMutationAdmission,
                         generationBootOwnership instanceof IndexGenerationManager.BootOwnership.Recorded recorded
                             ? () -> promoteServingSuccessor(recorded)
                             : () -> promoteServingSuccessor(null))),
            "migration-cutover");
    migrationCutoverThread.setDaemon(true);
    migrationCutoverThread.start();
  }

  /** State transition and producer admission use one lock order: runtime, mutation, generation. */
  private void enterSwitchingWithMutationAdmission() throws IOException, InterruptedException {
    runtimeSwapLock.lock();
    try {
      if (closeStarted || !(appServices instanceof DefaultWorkerAppServices services)) {
        throw new IllegalStateException("Migration has no live producer owner");
      }
      try (var ignoredFence = services.mutationAdmission().beginFinalFence(
          services.mutationOwnerToken(), 10_000)) {
        if (ignoredFence == null) {
          throw new IllegalStateException("Migration mutation admission did not drain");
        }
        if (!services.mutationAdmission().replayCertain()) {
          indexGenerationManager.updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
          throw new IOException("Migration has an unrecorded watcher mutation; rescan before retry");
        }
        indexGenerationManager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
      }
    } finally {
      runtimeSwapLock.unlock();
    }
  }

  /** Final Flow A fence: replay, certify Green, commit the pointer and install its view. */
  private IndexGenerationManager.State promoteServingSuccessor(
      IndexGenerationManager.BootOwnership.Recorded recorded) throws IOException, InterruptedException {
    runtimeSwapLock.lockInterruptibly();
    DefaultWorkerAppServices successor = null;
    DefaultWorkerAppServices incumbent = null;
    ServingLease sourceLease = null;
    RecordedIngestionLifecycle.PreparedCompositeProjection preparedProjection = null;
    AtomicBoolean published = new AtomicBoolean();
    AtomicBoolean pointerUncertain = new AtomicBoolean();
    AtomicReference<ServingView> preparedView = new AtomicReference<>();
    AtomicReference<ServingView> retiredForNotification = new AtomicReference<>();
    AtomicReference<RecordedIngestionLifecycle.CommittedProjection>
        committedProjection = new AtomicReference<>();
    boolean requestRecovery = false;
    boolean paused = false;
    try {
      if (closeStarted || !(appServices instanceof DefaultWorkerAppServices current)
          || !(ingestLifecycle instanceof RunningRuntime green) || green == searchLifecycle
          || buildingIndexPath == null) {
        throw new IOException("Green no longer has a live source and writer");
      }
      incumbent = current;
      successor = prepareServingSuccessor(green);
      var switching = indexGenerationManager.readStateBestEffort();
      if (switching == null || !"SWITCHING".equals(switching.migration_state())) return null;
      String sourceGeneration = switching.active_generation();
      String buildingGeneration = switching.building_generation();
      if (sourceGeneration == null || buildingGeneration == null) return null;
      if (activeIndexPath == null
          || !indexGenerationManager.resolveGenerationPathStrict(sourceGeneration).equals(activeIndexPath)
          || !indexGenerationManager.resolveGenerationPathStrict(buildingGeneration).equals(buildingIndexPath)) {
        throw new IOException("Serving runtimes do not match the migration generation pointer");
      }
      long remainingMs = MIGRATION_SWITCHING_MAX_DURATION_MS
          - Math.max(0L, System.currentTimeMillis() - switching.updated_at_ms());
      if (remainingMs <= 0L) return null;
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMs);
      try (var fence = current.mutationAdmission().beginFinalFence(
          current.mutationOwnerToken(), 10_000)) {
        if (fence == null) return null;
        if (!current.mutationAdmission().replayCertain()) {
          throw new IOException("Unrecorded watcher mutation prevents certified promotion");
        }
        var replay = KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
            new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
                jobQueue, green, signalBus, indexingPacing, indexBasePath, buildingIndexPath,
                JSON, KnowledgeServer::chunkSpladeEnabled, () -> true, log, deadline));
        if (replay.isEmpty()) return null;

        // Replay can enqueue file work. The already-running Green writer drains it while new
        // producer effects wait at the fence; the pause acknowledges its final batch boundary.
        while (true) {
          var counts = jobQueue.jobStateCountsStrict();
          if (counts.processingCount() == 0 && counts.pendingReadyCount() == 0
              && counts.pendingBackoffCount() == 0) break;
          if (System.nanoTime() >= deadline || closeStarted) return null;
          Thread.sleep(100);
        }
        if (!current.pauseProducerForCutover(10_000)) return null;
        paused = true;
        var settled = jobQueue.jobStateCountsStrict();
        if (settled.processingCount() != 0 || settled.pendingReadyCount() != 0
            || settled.pendingBackoffCount() != 0 || !current.mutationAdmission().replayCertain()
            || (migrationCutoverMaxFailedJobs >= 0
                && settled.failedCount() > migrationCutoverMaxFailedJobs)
            || !finalizeEmbeddingRebuildBeforeCutover()) return null;
        green.commitOps().commitWithBuildState(
            LuceneRuntimeTypes.BuildState.COMPLETE,
            io.justsearch.adapters.lucene.runtime.CommitReason.MIGRATION_CUTOVER);
        if (!verifyGreenCommitMetadataBestEffort()) return null;

        Path successorPath = buildingIndexPath;
        ServingLease heldSource = captureServingView();
        sourceLease = heldSource;
        // Component/settings preparation can compose native resources. Leave the physical runtime
        // lock while the mutation fence and exact A lease hold the cutover source stable.
        runtimeSwapLock.unlock();
        IndexGenerationManager.State result;
        try {
          var prepared = recorded == null ? null
              : recordedIngestionLifecycle.prepareRecordedGenerationProjection(
                  recorded.operationKey(), jobQueue);
          preparedProjection = prepared;
          var callbacks = prepared == null ? null : prepared.callbacks();
          DefaultWorkerAppServices nextServices = successor;
          RecordedIngestionLifecycle.CheckedPromotion publish = () -> {
            runtimeSwapLock.lock();
            try {
              if (closeStarted || heldSource.services() != current
                  || heldSource.searchRuntime() != searchLifecycle
                  || heldSource.ingestRuntime() != green || appServices != current
                  || ingestLifecycle != green || buildingIndexPath == null
                  || !buildingIndexPath.equals(successorPath)
                  || !current.mutationAdmission().replayCertain()
                  || System.nanoTime() >= deadline) {
                throw new IOException("Serving source changed during cutover preparation");
              }
              var counts = jobQueue.jobStateCountsStrict();
              if (counts.processingCount() != 0 || counts.pendingReadyCount() != 0
                  || counts.pendingBackoffCount() != 0
                  || (migrationCutoverMaxFailedJobs >= 0
                      && counts.failedCount() > migrationCutoverMaxFailedJobs)) {
                throw new IOException("Green changed during cutover preparation");
              }
              try (var transfer = current.prepareProducerTransferTo(nextServices)) {
          DefaultWorkerAppServices preparedServices = nextServices;
          RecordedIngestionLifecycle.CheckedPromotion commit = () -> {
                var projection = callbacks;
                try (var recordedPromotion = recorded == null ? null
                         : indexGenerationManager.beginRecordedPromotion(
                             recorded.operationKey(), recorded.source(), recorded.targetFingerprint(),
                             recorded.sourceGeneration());
                     var nativePromotion = recorded == null
                         ? indexGenerationManager.beginNativePromotion(sourceGeneration, buildingGeneration)
                         : null) {
                  publicationLock.writeLock().lock();
                  try {
                    ServingView old;
                    synchronized (servingViewMonitor) {
                      old = servingView;
                      if (closeStarted || old == null || old.retiring || old.services != current
                          || old.searchRuntime != searchLifecycle || old.ingestRuntime != green) {
                        throw new IOException("Serving source changed before pointer commitment");
                      }
                    }
                    ServingView next = new ServingView(preparedServices, green, green, successorPath);
                    EncoderSet successorEncoder = recordedCandidate == null ? old.encoderSet
                        : Objects.requireNonNull(candidateModels, "Recorded B encoder owner").owner();
                    if (successorEncoder != null) next.attachEncoderSet(successorEncoder);
                    preparedView.set(next);
                    Runnable cleanup = () -> closeRetiredSource(old, successorEncoder);
                    IndexGenerationManager.State promoted;
                    try {
                      if (projection != null) projection.admitBeforePointer();
                      promoted = recordedPromotion == null
                          ? nativePromotion.promote() : recordedPromotion.promote();
                      // Settings are the roll-forward projection of this committed pointer.
                      // Keep capture excluded until the accepted candidate is durably installed.
                      if (projection != null) projection.afterPointerCommitted();
                    } catch (IOException | RuntimeException | Error ambiguous) {
                      // The held state guard lets us distinguish a precommit refusal from a
                      // post-move failure. Only a committed or unreadable pointer closes A.
                      var witness = recordedPromotion == null
                          ? nativePromotion.inspectCommitWitness()
                          : recordedPromotion.inspectCommitWitness();
                      if (witness
                          != IndexGenerationManager.RecordedPromotion.CommitWitness.UNCHANGED) {
                        pointerUncertain.set(true);
                        fence.install(preparedServices.mutationOwnerToken());
                        // The pointer may already name B. Keep its borrowed producer under the
                        // successor owner until ordered restart, even when settings publication
                        // or an atomic move reported an error before the view swap.
                        try { transfer.install(); }
                        catch (RuntimeException | Error ownershipFailure) {
                          ambiguous.addSuppressed(ownershipFailure);
                        }
                      }
                      throw ambiguous;
                    }
                    pointerUncertain.set(true);
                    if (promoted == null) {
                      fence.install(preparedServices.mutationOwnerToken());
                      throw new IOException("Live promotion returned no generation");
                    }
                    fence.install(preparedServices.mutationOwnerToken());
                    transfer.install();
                    synchronized (servingViewMonitor) {
                      old.retiring = true;
                      old.retireCleanup = cleanup;
                      retiredServingViews.add(old);
                      retiredForNotification.set(old);
                      activeIndexPath = successorPath;
                      buildingIndexPath = null;
                      searchLifecycle = green;
                      generationBootDisposition = IndexGenerationManager.BootDisposition.PROMOTED;
                      appServices = preparedServices;
                      if (recordedCandidate != null) {
                        inferenceSurface = successorEncoder.surfaceForOwner();
                      }
                      servingView = next;
                      servingViewMonitor.notifyAll();
                    }
                    committedProjection.set(projection);
                    published.set(true);
                    return promoted;
                  } finally {
                    publicationLock.writeLock().unlock();
                  }
                }
               };
          return recorded == null ? commit.promote()
              : recordedIngestionLifecycle.promoteRecordedGeneration(
                  recorded.operationKey(), jobQueue, commit);
              }
            } finally {
              runtimeSwapLock.unlock();
            }
          };
          result = prepared == null ? publish.promote() : prepared.withOwnerLocks(publish);
        } finally {
          runtimeSwapLock.lock();
        }
        if (result == null) return null;
          if (!KnowledgeServerMigrationOps.finishPromotedSwitchReplay(jobQueue, replay.orElseThrow())
              || !KnowledgeServerMigrationOps.switchBufferEmptyStrict(jobQueue)) {
            log.warn("Promoted Green retains switch-buffer versions; ordered recovery will retry exact replay");
            requestRecovery = true;
          } else {
            fence.certifySuccessor();
            promotedReplaySettled = true;
          }
        return result;
      }
    } catch (IOException | RuntimeException | Error failure) {
      if (pointerUncertain.get()) requestRecovery = true;
      if (pointerUncertain.get() && !published.get()) {
        publicationLock.writeLock().lock();
        try {
          synchronized (servingViewMonitor) {
            if (servingView != null) servingView.retiring = true;
          }
        } finally {
          publicationLock.writeLock().unlock();
        }
      }
      throw failure;
    } finally {
      if (paused && incumbent != null && (!pointerUncertain.get() || published.get())) {
        incumbent.resumeProducerAfterCutover();
      }
      if (!published.get() && !pointerUncertain.get() && successor != null) successor.close();
      if (!published.get() && preparedView.get() != null) preparedView.get().releaseEncoderSet();
      runtimeSwapLock.unlock();
      if (published.get()) notifyServingViewRetirement(retiredForNotification.get());
      if (!published.get() && !pointerUncertain.get() && preparedProjection != null) {
        try { preparedProjection.abortBeforePointer(); }
        catch (RuntimeException | Error cleanupFailure) {
          log.error("Recorded projection could not retire its precommit candidate", cleanupFailure);
          requestRecovery = true;
        }
      }
      if (sourceLease != null) sourceLease.close();
      if (published.get() && !requestRecovery) {
        try {
          var projection = committedProjection.get();
          if (projection != null) projection.afterRuntimePublished();
          // The notification can reconcile and terminate the recorded row. Its projection
          // callback must still hold the runner's live capability until this step completes.
          notifyRecordedServicesPublished();
        } catch (RuntimeException notificationFailure) {
          log.error("Recorded Green publication requires ordered recovery", notificationFailure);
          requestRecovery = true;
        }
      }
      if (requestRecovery) migrationRestartAction.run();
    }
  }

  private void closeRetiredSource(ServingView old, EncoderSet successorEncoder) {
    try {
      old.services.close();
      old.releaseEncoderSet();
      if (old.encoderSet != null && old.encoderSet != successorEncoder) old.encoderSet.close();
      if (old.searchRuntime != null && old.searchRuntime != ingestLifecycle) old.searchRuntime.close();
    } catch (IOException failure) {
      throw new IllegalStateException("Retired generation still owns resources", failure);
    }
  }

  /**
   * Writes the pending worker metrics snapshot now rather than at the next 60s tick. Called before
   * the cutover restart, which otherwise discards the counters the cutover itself produced.
   */
  private void flushTelemetryBestEffort() {
    if (telemetry instanceof LocalTelemetry lt) {
      lt.flush();
    }
  }

  /**
   * Tempdoc 598 review Fix E: deterministically finalize the embedding rebuild on the drained green
   * immediately before the cutover COMPLETE commit. Flips the ECC to COMPATIBLE iff the green is fully
   * embedded (job queue + pending-embeddings both 0), so the COMPLETE commit's overlay stamps the
   * embedding fingerprint — instead of racing the indexing-loop thread that would otherwise call
   * {@code checkRebuildCompletion}. Pending work or an unreadable pending count defers the cutover
   * under its existing switching deadline. Metadata verification still guards promotion after
   * certification and the final commit. A fresh green may already be COMPATIBLE before backfill
   * earns its first stamp; reconcile that evidence here too, without waiting for an idle-loop tick.
   */
  private boolean finalizeEmbeddingRebuildBeforeCutover() {
    var ecc = recordedCandidate == null ? embeddingCompatController
        : candidateEmbeddingCompatController;
    String expectedFingerprint = ecc == null ? null : ecc.currentFingerprint();
    if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
      return true; // No resolvable embedding model: a legitimate keyword-only rebuild.
    }
    if (ingestLifecycle == null) return false;
    try {
      long queueDepth = jobQueue.queueDepth();
      int pendingEmbeddings =
          ingestLifecycle
              .indexCountOps()
              .countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      if (pendingEmbeddings > 0) return false;
      ecc.checkRebuildCompletion(queueDepth, pendingEmbeddings);
      return ecc.reconcileStampEvidence()
          && ecc.fingerprintToStamp().filter(expectedFingerprint::equals).isPresent();
    } catch (IOException | RuntimeException e) {
      log.warn("Cannot establish embedding completion before cutover: {}", e.getMessage());
      return false;
    }
  }

  private boolean verifyGreenCommitMetadataBestEffort() {
    // Tempdoc 598 R3: also verify the green carries a current-model embedding fingerprint before
    // promotion, so a blue/green rebuild cannot promote a generation that would still serve
    // BLOCKED_LEGACY. Null when no embedding model is resolvable (keyword-only rebuild → skipped).
    var ecc = recordedCandidate == null ? embeddingCompatController
        : candidateEmbeddingCompatController;
    String expectedEmbeddingFp = ecc == null ? null : ecc.currentFingerprint();
    String expectedIndexFp = recordedCandidate == null ? null : recordedCandidate.target().fingerprint();
    return KnowledgeServerMigrationOps.verifyGreenCommitMetadataBestEffort(
        ingestLifecycle, expectedIndexFp, expectedEmbeddingFp, log);
  }

  private void drainSwitchBufferBestEffort() {
    // Drain only valid post-deferred-upgrade (writer is open). The boot path that
    // calls drain pre-upgrade does so only when ingest is not deferred (see boot logic).
    if (!(ingestLifecycle instanceof RunningRuntime running)) {
      log.warn("drainSwitchBufferBestEffort: ingest is not RunningRuntime ({}), skipping",
          ingestLifecycle == null ? "null" : ingestLifecycle.getClass().getSimpleName());
      return;
    }
    LuceneRuntime capturedServing = searchLifecycle;
    Path capturedPath = activeIndexPath;
    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
        new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
            jobQueue,
            running,
            signalBus,
            indexingPacing,
            indexBasePath,
            capturedPath,
            JSON,
            KnowledgeServer::chunkSpladeEnabled,
            () -> vduReplayAllowed(running, capturedServing, capturedPath),
            log));
  }


  private boolean vduReplayAllowed(RunningRuntime target, LuceneRuntime serving, Path targetPath) {
    if (target != serving || indexGenerationManager == null) return false;
    try {
      return indexGenerationManager.isIdleActiveGeneration(targetPath);
    } catch (IOException | RuntimeException unavailable) {
      log.warn("VDU replay awaits readable active generation state: {}", unavailable.getMessage());
      return false;
    }
  }

  /**
   * {@code rag.chunk_splade.enabled} (tempdoc 931 §E item 8), read from the LIVE {@link ConfigStore}
   * rather than a boot-time copy. Absent config reads as the flag's own default (false).
   */
  private static boolean chunkSpladeEnabled() {
    ConfigStore cs = ConfigStore.globalOrNull();
    ResolvedConfig rc = cs == null ? null : cs.get();
    return rc != null && rc.rag() != null && rc.rag().chunkSpladeEnabled();
  }

  private void startMigrationEnumeratorBestEffort(ResolvedConfig rc) {
    if (migrationEnumeratorThread != null) {
      return;
    }
    migrationEnumeratorRunning.set(true);
    migrationEnumeratorStartedAtMs.set(System.currentTimeMillis());
    migrationEnumeratorFinishedAtMs.set(0L);
    migrationEnumeratorRootsTotal.set(0L);
    migrationEnumeratorRootsDone.set(0L);
    migrationEnumeratorFilesSeen.set(0L);
    migrationEnumeratorFilesEnqueued.set(0L);
    migrationEnumeratorLastPath.set("");
    // Persist initial progress snapshot (best-effort) so operators can see that migration started.
    {
      MigrationProgressSnapshot snap = migrationProgressSnapshot();
      persistedMigrationProgressSnapshot = snap;
      if (migrationProgressStore != null) {
        migrationProgressStore.writeBestEffort(snap);
      }
    }
    migrationEnumeratorThread =
        new Thread(
            () -> {
              try {
                // 332: Wait for all models (embedding + ECC + SPLADE) before enqueuing files.
                // Without this gate, the IndexingLoop processes migration jobs before
                // initDeferredModels() finishes loading models (~15-20s), producing
                // text-only docs without sparse vectors that need slow RMW backfill post-cutover.
                if (!modelReadyLatch.await(120, TimeUnit.SECONDS)) {
                  log.warn(
                      "Migration enumerator: models not ready after 120s, "
                          + "proceeding without inline embedding/SPLADE");
                }
                List<Path> roots = loadMigrationRoots(rc);
                migrationEnumeratorRootsTotal.set(roots.size());
                int totalEnqueued = enqueueAllFilesUnderRoots(roots);
                log.info(
                    "Migration enumerator finished. roots={} enqueuedFiles={}",
                    roots.size(),
                    totalEnqueued);
                migrationEnumeratorDone = true;
              } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                migrationEnumeratorFailure = e;
                log.warn("Migration enumeration incomplete; cutover will preserve Blue", e);
              } finally {
                migrationEnumeratorRunning.set(false);
                migrationEnumeratorFinishedAtMs.set(System.currentTimeMillis());
                // Persist terminal snapshot (best-effort) for restart visibility.
                MigrationProgressSnapshot snap = migrationProgressSnapshot();
                persistedMigrationProgressSnapshot = snap;
                if (migrationProgressStore != null) {
                  migrationProgressStore.writeBestEffort(snap);
                }
              }
            },
            "migration-enumerator");
    migrationEnumeratorThread.setDaemon(true);
    migrationEnumeratorThread.start();
  }

  private List<Path> loadMigrationRoots(ResolvedConfig rc) throws IOException {
    return KnowledgeServerMigrationOps.loadMigrationRoots(
        dataDir, rc.collections().items(), JSON);
  }

  private int enqueueAllFilesUnderRoots(List<Path> roots) throws IOException {
    return KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        new KnowledgeServerMigrationOps.EnqueueContext(
            roots,
            jobQueue,
            () -> running,
            () -> indexGenerationManager,
            migrationEnumeratorFilesSeen,
            migrationEnumeratorFilesEnqueued,
            migrationEnumeratorRootsDone,
            migrationEnumeratorLastPath,
            () -> migrationProgressStore,
            this::migrationProgressSnapshot,
            snap -> persistedMigrationProgressSnapshot = snap,
            log));
  }

  private MigrationProgressSnapshot migrationProgressSnapshot() {
    return KnowledgeServerMigrationOps.migrationProgressSnapshot(
        migrationEnumeratorRunning,
        migrationEnumeratorDone,
        migrationEnumeratorRootsTotal,
        migrationEnumeratorRootsDone,
        migrationEnumeratorFilesSeen,
        migrationEnumeratorFilesEnqueued,
        migrationEnumeratorStartedAtMs,
        migrationEnumeratorFinishedAtMs,
        migrationEnumeratorLastPath,
        persistedMigrationProgressSnapshot);
  }

  // ==================== Database Corruption Triage ====================

  /**
   * Handles a corrupt jobs.db by quarantining the corrupt file and restoring from backup.
   *
   * <p>Triage process:
   * <ol>
   *   <li>Quarantine the corrupt database (and WAL sidecar files) by renaming to .corrupt</li>
   *   <li>Attempt to restore from jobs.db.bak if it exists</li>
   *   <li>Re-open the queue (creates fresh tables if no backup existed)</li>
   * </ol>
   *
   * <p>WAL mode creates sidecar files (-wal, -shm) that must be handled together with the
   * main database file to avoid inconsistent state.
   *
   * @param dbPath Path to the corrupt jobs.db file
   * @throws IOException if quarantine or restore operations fail
   * @throws SQLException if re-opening the queue fails
   */
  private void handleCorruptDatabase(Path dbPath) throws IOException, SQLException {
    Path corruptPath = dbPath.resolveSibling(dbPath.getFileName() + ".corrupt");
    Path backupPath = dbPath.resolveSibling(dbPath.getFileName() + ".bak");

    // 1. Quarantine the corrupted file for forensics
    if (Files.exists(dbPath)) {
      Files.move(dbPath, corruptPath, StandardCopyOption.REPLACE_EXISTING);
      log.error("Quarantined corrupt database to: {}", corruptPath);
    }

    // WAL sidecar files must be handled together with main DB
    Path walPath = dbPath.resolveSibling(dbPath.getFileName() + "-wal");
    Path shmPath = dbPath.resolveSibling(dbPath.getFileName() + "-shm");

    if (Files.exists(walPath)) {
      Path corruptWalPath = corruptPath.resolveSibling(dbPath.getFileName() + "-wal.corrupt");
      Files.move(walPath, corruptWalPath, StandardCopyOption.REPLACE_EXISTING);
      log.warn("Quarantined WAL file to: {}", corruptWalPath);
    }

    if (Files.exists(shmPath)) {
      // SHM is a shared-memory file, safe to delete (will be recreated)
      Files.deleteIfExists(shmPath);
      log.debug("Deleted SHM sidecar file");
    }

    // 2. Attempt recovery from backup
    if (Files.exists(backupPath)) {
      Files.copy(backupPath, dbPath, StandardCopyOption.REPLACE_EXISTING);
      log.warn("Restored database from backup: {}", backupPath);
    } else {
      log.warn("No backup found at {}. A fresh database will be created.", backupPath);
    }

    // 3. Re-open the queue with forced integrity check to validate the restored backup.
    // SqliteJobQueue.open() is designed to be re-callable after close().
    jobQueue.openWithIntegrityCheck();
    log.info("Job queue re-opened after triage (integrity validated)");
  }

  /**
   * Tempdoc 374 alpha.18 Bug H + alpha.20 Bug M: resolve the models directory.
   *
   * <p>Three-tier fallback:
   *
   * <ol>
   *   <li>{@code contract.modelsDir()} — alpha.20: the install contract records the absolute
   *       path at install time. Survives cold restart because the contract is persisted to
   *       disk; doesn't depend on env-var inheritance across GUI launches. This is the
   *       primary source for users who pre-stage models via {@code JUSTSEARCH_MODELS_DIR}.
   *   <li>{@code config.paths().modelsDir()} — alpha.18: the supplied boot snapshot bridges
   *       {@code JUSTSEARCH_MODELS_DIR} via {@code EnvRegistry.MODELS_DIR ↔
   *       justsearch.models.dir}. Works at first launch when the env var is set in the launching
   *       shell, and pre-alpha.20 contracts that don't have the field.
   *   <li>{@code aiHome.resolve("models")} — the default-flow fallback when neither
   *       contract nor env var is set (Install AI downloaded to {@code %APPDATA%\models\}).
   * </ol>
   *
   * <p>Without this resolution, {@code VariantSelector.select} resolves contract paths
   * against the wrong directory and the worker reports
   * {@code "Model file missing from disk: ..."} for every installed package after a cold
   * restart (round-10 sandbox finding).
   *
   * <p>Package-private so {@code KnowledgeServerModelsDirTest} can exercise the captured-config
   * resolution without spinning up a real {@code KnowledgeServer}.
   */
  static Path resolveModelsDir(InstallContract contract, Path aiHome, ResolvedConfig config) {
    if (contract != null && contract.modelsDir() != null) {
      return contract.modelsDir();
    }
    Path configured = config.paths().modelsDir();
    if (configured != null) return configured;
    return aiHome != null ? aiHome.resolve("models") : null;
  }
}
