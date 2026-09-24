/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.extract.ExtractionMetricCatalog;
import io.justsearch.indexerworker.extract.ExtractionConfiguration;
import io.justsearch.indexerworker.extract.ExtractionSandboxCommand;
import io.justsearch.indexerworker.extract.ExtractionSandboxFactory;
import io.justsearch.indexerworker.extract.ExtractionSandboxRestartTags;
import io.justsearch.indexerworker.extract.PersistentExtractionSandbox;
import io.justsearch.indexerworker.extract.OcrMetricCatalog;
import io.justsearch.indexerworker.extract.OcrRoutingConfig;
import io.justsearch.indexerworker.extract.StructuredContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.IndexingPipelineMetricCatalog;
import io.justsearch.indexerworker.loop.IngestionOutcomeMetricCatalog;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.services.WorkerHealthService;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerMutationAdmission;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder;
import io.justsearch.reranker.CitationScorerConfig;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankerConfig;
import io.justsearch.reranker.WorkerModelDiscovery;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link WorkerAppServices} that constructs and wires all
 * application-layer services from an {@link InfraContext}.
 *
 * <p>This is a construction helper — it centralizes how application objects are created and
 * cross-wired, but does not own their full lifecycle. {@link #close()} only closes the
 * indexing loop; all infrastructure resources are closed by {@code KnowledgeServer}.
 */
public final class DefaultWorkerAppServices implements WorkerAppServices {

  private static final Logger log = LoggerFactory.getLogger(DefaultWorkerAppServices.class);

  private final IndexingLoop indexingLoop;
  /** Runtime already owned by {@link #indexingLoop}; null for a deferred/read-only bundle. */
  private final RunningRuntime producerRuntime;
  /**
   * Tempdoc 885 item 3: the one pacing policy every indexing/backfill site throttles against.
   * Owned by {@code KnowledgeServer}, not by this class: the app services are reconstructed on a
   * deferred-runtime upgrade and on dev hot-reload while the server — and therefore the producer
   * that feeds the gauge, {@code ForegroundLoadGate} since item A9 and the gRPC
   * {@code ForegroundLoadInterceptor} before it — is not, so a per-instance gauge would be
   * silently orphaned from its only producer.
   */
  private final IndexingPacing indexingPacing;
  private final WorkerSearchService searchService;
  private final WorkerIngestService ingestService;
  /** Shared by A and its prepared B; final cutover drains admitted mutation effects. */
  private final WorkerMutationAdmission mutationAdmission;
  private final Object mutationOwnerToken;
  private final WorkerHealthService healthService;
  private final ResolvedConfig resolvedConfig;
  private final ExtractionConfiguration extractionConfiguration;
  private final boolean detailedTracing;
  private final RerankerConfig.ChunkRerankerConfig chunkRerankerConfig;
  private final CitationScorerConfig citationScorerConfig;
  // W7.2: shared registry held by both IndexingLoop and SearchOrchestrator.
  private final EncoderBindings encoderBindings;
  /** Green's index-time bindings when an accepted candidate differs from serving A. */
  private final EncoderBindings producerEncoderBindings;
  private final WorkerServiceConfiguration candidateConfiguration;
  // Tempdoc 418 Phase B — Worker-side filesystem watcher. Owned by appServices so its lifecycle
  // matches the service set's; closed during {@link #close()}.
  private final io.justsearch.indexerworker.services.WorkerMethvinWatcher workerWatcher;
  /**
   * The Blue/Green successor borrows the incumbent's producer until durable promotion commits.
   * Access is serialized by KnowledgeServer's runtime-swap owner lock.
   */
  private final SharedProducerOwnership producerOwnership;
  private final EmbeddingProviderTarget embeddingProviderTarget;
  private final WatcherCallbacks watcherCallbacks;
  private final WatcherCallbacks.Target preparedWatcherTarget;
  private final DefaultWorkerAppServices borrowedFrom;

  // Query/status-only model bindings that are not carried by the shared EncoderBindings.
  private SpladeIdfQueryEncoder spladeIdfQueryEncoder;
  private CrossEncoderReranker searchReranker;
  private io.justsearch.reranker.CitationScorer citationScorer;
  private GpuDiagnosticSuppliers gpuDiagnostics;
  private StageAvailability stageAvailability;
  private java.util.function.Supplier<java.util.concurrent.CountDownLatch> modelReadyLatchSupplier;
  private java.util.function.Supplier<io.justsearch.ort.PolicySnapshot> policySnapshotSupplier;

  private record StageAvailability(boolean embedding, boolean splade, boolean ner) {}

  /** Exact close owner for the loop/extractor/watcher bundle shared during Green preparation. */
  static final class SharedProducerOwnership {
    private boolean owned;
    private boolean closed;
    private Runnable modelLeaseRelease;

    SharedProducerOwnership(boolean owned) {
      this.owned = owned;
    }

    boolean ownsProducer() {
      return owned && !closed;
    }

    boolean closed() { return closed; }

    synchronized void replaceModelLease(Runnable release) {
      if (!owned || closed) throw new IllegalStateException("No live producer owns the model lease");
      Runnable previous = modelLeaseRelease;
      modelLeaseRelease = java.util.Objects.requireNonNull(release, "release");
      if (previous != null) previous.run();
    }

    synchronized void clearModelLease() {
      if (!owned || closed) throw new IllegalStateException("No live producer owns the model lease");
      Runnable previous = modelLeaseRelease;
      modelLeaseRelease = null;
      if (previous != null) previous.run();
    }

    synchronized void transferTo(SharedProducerOwnership successor) {
      if (!owned || closed || successor.closed || successor.owned
          || successor.modelLeaseRelease != null) {
        throw new IllegalStateException("Producer model lease cannot be transferred");
      }
      successor.modelLeaseRelease = modelLeaseRelease;
      modelLeaseRelease = null;
      owned = false;
      successor.owned = true;
    }

    synchronized void close(
        io.justsearch.indexerworker.services.WorkerMethvinWatcher watcher,
        IndexingLoop loop) throws IOException {
      if (closed) return;
      if (owned) {
        if (watcher != null) watcher.close();
        if (loop != null) loop.close();
      }
      if (modelLeaseRelease != null) {
        modelLeaseRelease.run();
        modelLeaseRelease = null;
      }
      closed = true;
    }
  }

  /** Stable loop listener whose selected query view changes with producer ownership. */
  private static final class EmbeddingProviderTarget
      implements java.util.function.Consumer<EmbeddingProvider> {
    private final java.util.concurrent.locks.ReentrantLock lock =
        new java.util.concurrent.locks.ReentrantLock();
    private DefaultWorkerAppServices target;

    private EmbeddingProviderTarget(DefaultWorkerAppServices target) {
      this.target = target;
    }

    @Override
    public void accept(EmbeddingProvider provider) {
      lock.lock();
      try {
        target.searchService.setEmbeddingProvider(provider);
        target.healthService.setEmbeddingProvider(provider);
      } finally {
        lock.unlock();
      }
    }
  }

  /** The existing watcher follows the selected writer service without retaining a Blue view. */
  private static final class WatcherCallbacks {
    private volatile Target target;

    private record Target(RunningRuntime runtime, WorkerIngestService ingest,
        io.justsearch.indexerworker.services.ConfirmedDeletionMarker deletionMarker) {}

    private WatcherCallbacks(Target initial) { target = initial; }

    private void upsert(String collection, Path path) {
      Target selected = target;
      if (selected.runtime() == null) return;
      try {
        selected.ingest().acceptWatcherUpsert(collection, path);
      } catch (RuntimeException stale) {
        Target successor = target;
        if (successor == selected || successor.runtime() == null) throw stale;
        successor.ingest().acceptWatcherUpsert(collection, path);
      }
    }

    private void delete(String path) {
      Target selected = target;
      if (selected.runtime() == null) return;
      try { deleteAt(selected, path); }
      catch (RuntimeException stale) {
        Target successor = target;
        if (successor == selected || successor.runtime() == null) throw stale;
        deleteAt(successor, path);
      }
    }

    private static void deleteAt(Target selected, String path) {
      selected.ingest().acceptWatcherDelete(path, () -> {
        if (!selected.runtime().isAcceptingWrites()) return;
        selected.runtime().indexingCoordinator().deleteByIdAndChunks(path);
        selected.deletionMarker().markIfAbsent(path);
      });
    }

    private void reconcile(Path root, boolean force) {
      Target selected = target;
      if (selected.runtime() == null || !selected.runtime().isAcceptingWrites()) return;
      try { selected.ingest().reconcileRootStrict(root, force); }
      catch (RuntimeException stale) {
        Target successor = target;
        if (successor == selected || successor.runtime() == null) throw stale;
        successor.ingest().reconcileRootStrict(root, force);
      }
    }
  }

  /**
   * Back-compat ctor — defaults migrationActiveSupplier + embeddingTelemetryEvents to null.
   * Production (KS) uses the 2-arg ctor below to pre-supply both values at ctor time so the
   * post-ctor wireMigrationActiveSupplier/wireEmbeddingTelemetryEvents paths can go away.
   */
  public DefaultWorkerAppServices(WorkerExecutorRegistrations executors, InfraContext ctx) {
    this(executors, ctx, null, null, IndexingPacing.unthrottled(),
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), captureResolvedConfig());
  }

  /**
   * Canonical ctor for production composition (KS). Tempdoc 516 P3 final cut: the
   * migrationActiveSupplier lambda and the EmbeddingTelemetryEvents sink are supplied here
   * so they can flow into IndexingLoopOptions at IndexingLoop ctor time — eliminating the
   * last two post-ctor setters on IndexingLoop.
   *
   * <p>The migration lambda is safe to create before any of its captured KS fields are
   * initialized (lambdas close over the outer {@code this}; fields are read at call time,
   * which happens during indexing, well after KS init completes).
   */
  public DefaultWorkerAppServices(
      WorkerExecutorRegistrations executors,
      InfraContext ctx,
      java.util.function.BooleanSupplier migrationActiveSupplier,
      io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents embeddingTelemetryEvents,
      IndexingPacing indexingPacing) {
    this(executors, ctx, migrationActiveSupplier, embeddingTelemetryEvents, indexingPacing,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), captureResolvedConfig());
  }

  public DefaultWorkerAppServices(
      WorkerExecutorRegistrations executors,
      InfraContext ctx,
      java.util.function.BooleanSupplier migrationActiveSupplier,
      io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents embeddingTelemetryEvents,
      IndexingPacing indexingPacing,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    this(
        executors,
        ctx,
        migrationActiveSupplier,
        embeddingTelemetryEvents,
        indexingPacing,
        childRegistry,
        captureResolvedConfig());
  }

  /** Canonical snapshot-bound composition used by {@code KnowledgeServer}. */
  public DefaultWorkerAppServices(
      WorkerExecutorRegistrations executors,
      InfraContext ctx,
      java.util.function.BooleanSupplier migrationActiveSupplier,
      io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents embeddingTelemetryEvents,
      IndexingPacing indexingPacing,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      ResolvedConfig resolvedConfig) {
    this(executors, ctx, migrationActiveSupplier, embeddingTelemetryEvents, indexingPacing,
        childRegistry, WorkerServiceConfiguration.capture(
            resolvedConfig, executors.pdfOcr().spec().threadCount()));
  }

  /** Reuses filesystem discovery and effective settings across deferred writer reconstruction. */
  public DefaultWorkerAppServices(
      WorkerExecutorRegistrations executors,
      InfraContext ctx,
      java.util.function.BooleanSupplier migrationActiveSupplier,
      io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents embeddingTelemetryEvents,
      IndexingPacing indexingPacing,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      WorkerServiceConfiguration configuration) {
    this(executors, ctx, migrationActiveSupplier, embeddingTelemetryEvents, indexingPacing,
        childRegistry, configuration, null);
  }

  /** A serves with its own bindings while a detached candidate drives Green's producer. */
  public DefaultWorkerAppServices(
      WorkerExecutorRegistrations executors,
      InfraContext ctx,
      java.util.function.BooleanSupplier migrationActiveSupplier,
      io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents embeddingTelemetryEvents,
      IndexingPacing indexingPacing,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      WorkerServiceConfiguration configuration,
      WorkerServiceConfiguration candidateConfiguration) {
    java.util.Objects.requireNonNull(executors, "executors");
    java.util.Objects.requireNonNull(childRegistry, "childRegistry");
    this.resolvedConfig = configuration.snapshot();
    this.candidateConfiguration = candidateConfiguration;
    this.extractionConfiguration = configuration.extraction();
    this.detailedTracing =
        !"none".equalsIgnoreCase(resolvedConfig.index().tracingLevel());
    // Tempdoc 410 §13 Slice B — publish the operator-resolved IngestionSkipPolicy before any
    // ingestion path can call it. WorkerScanOps and WorkerIngestionAuthority fire during gRPC
    // handling that always happens after this constructor returns, so installing here is safe.
    io.justsearch.indexerworker.ingest.IngestionSkipPolicy.installResolved(
        extractionConfiguration.ingestionSkipPolicy());

    // 1. Content extractor + indexing loop. Tempdoc 417 Phase 2b: catalogs are constructed
    // here against the registry (worker-core can't import worker-services' catalog types).
    // Tempdoc 410 sandbox seam threaded through buildContentExtractor; both pipelineCatalog
    // and extractionCatalog are wired into the sandbox + indexing loop.
    var pipelineCatalog = new IndexingPipelineMetricCatalog(ctx.metricRegistry());
    var extractionCatalog = new ExtractionMetricCatalog(ctx.metricRegistry());
    var ocrCatalog = new OcrMetricCatalog(ctx.metricRegistry());
    var ingestionOutcomeCatalog = new IngestionOutcomeMetricCatalog(ctx.metricRegistry());
    this.indexingPacing = java.util.Objects.requireNonNull(indexingPacing, "indexingPacing");
    // Tempdoc 406 Phase 4a: services capture the current runtime via ctx.suppliers.
    // If ingest is DeferredRuntime, construct in "deferred mode": indexingLoop and
    // ingestService are skipped (write side not available until upgrade); the
    // search side works against DeferredRuntime's read ops. KS reconstructs this
    // appServices after DeferredRuntime.prepareWriterUpgrade() and swaps the
    // DelegatingX wrappers via setDelegate (mirrors DevReloadManager flow).
    io.justsearch.adapters.lucene.runtime.LuceneRuntime ingestLifecycle =
        ctx.ingestLifecycleSupplier().get();
    RunningRuntime ingestRunning =
        ingestLifecycle instanceof RunningRuntime r ? r : null;
    this.producerRuntime = ingestRunning;
    this.borrowedFrom = null;
    this.mutationOwnerToken = new Object();
    this.mutationAdmission = new WorkerMutationAdmission(mutationOwnerToken);

    // Tempdoc 516 P3 / Slice 5 (W7.2): single shared EncoderBindings registry held by both
    // IndexingLoop and SearchOrchestrator. wire* methods below bind once on it instead of
    // fanning out across peer setters.
    this.encoderBindings = new EncoderBindings();
    this.producerEncoderBindings = candidateConfiguration == null
        ? encoderBindings : new EncoderBindings();
    // Even a deferred bundle owns its watcher; ordinary close semantics remain unchanged.
    this.producerOwnership = new SharedProducerOwnership(true);

    if (ingestRunning != null) {
      // Extraction owns an executor, a shutdown hook and (after first routed file) child-process
      // slots. Deferred/read-only service sets cannot ingest and never close an IndexingLoop, so
      // constructing an extractor for them leaks all three owners until process exit.
      var contentExtractor =
          buildContentExtractor(
              executors,
              ctx,
              extractionCatalog,
              ocrCatalog,
              childRegistry,
              extractionConfiguration);
      // Tempdoc 516 P3 / Slice 5 (W7.2 followup): the 5 startup-config setters are now
      // IndexingLoopOptions record fields. Construct the options upfront so the loop is
      // immutable post-ctor (no setDetailedTracing/setCommitMetadataSupplier/etc.).
      io.justsearch.indexerworker.loop.IndexingLoopOptions loopOptions =
          new io.justsearch.indexerworker.loop.IndexingLoopOptions(
              detailedTracing,                                         // detailedTracing
              ctx.pathResolutionStore(),                                // pathResolutionStore
              ctx.documentIdentityStore(),                              // documentIdentityStore
              migrationActiveSupplier,                                  // 516 P3 final — pre-wired at ctor
              ingestRunning::latestCommitUserDataBestEffort,            // commitMetadataSupplier
              embeddingTelemetryEvents);                                // 516 P3 final — pre-wired at ctor

      try {
        this.indexingLoop =
            new IndexingLoop(
                executors.pdfOcr(),
                executors.extractionTimebox(),
                ctx.jobQueue(),
                ingestRunning.indexingCoordinator(),
                ingestRunning.commitOps(),
                ingestRunning.documentFieldOps(),
                ingestRunning.indexCountOps(),
                ingestRunning::resolvedConfig,
                ctx.signalBus(),
                indexingPacing,
                null, // embeddingService — wired by deferred init
                pipelineCatalog,
                extractionCatalog,
                ingestionOutcomeCatalog,
                contentExtractor,
                producerEncoderBindings,
                loopOptions);
      } catch (RuntimeException | Error failure) {
        try {
          contentExtractor.close();
        } catch (RuntimeException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        throw failure;
      }
    } else {
      this.indexingLoop = null;
    }

    // 2. Search service (null embedding — wired by deferred init). Lane F item A3 converted it
    // off the generated ImplBase. Item A9 deleted the DelegatingSearchService adapter with the
    // gRPC server it was registered in; callers reach this instance through appServices().
    // Works against DeferredRuntime (read ops only) or RunningRuntime.
    // W7.2: shares the encoderBindings instance with IndexingLoop.
    var servingRuntime = ctx.searchLifecycleSupplier().get();
    this.searchService =
        new WorkerSearchService(servingRuntime, null, encoderBindings);

    // 3. Ingest service. WorkerIngestService is null-tolerant for
    // ingestLifecycle/indexingLoop — write methods report UNAVAILABLE when
    // either is null. KS reconstructs this with non-null values after
    // DeferredRuntime.prepareWriterUpgrade(); item A9 deleted the wrapper it used to
    // swap, so publishing the new appServices instance is the whole swap now.
    this.ingestService =
        new WorkerIngestService(
            ctx.jobQueue(),
            indexingLoop,
            ctx.signalBus(),
            indexingPacing,
            ctx.indexBasePath(),
            ctx.activeIndexPath(),
            ingestRunning,
            servingRuntime,
            ctx.migrationProgressSupplier(),
            ctx.migrationSwitchingMaxDurationMs());

    // Tempdoc 419 / T5.3 (ADR-0028): wire the scoped reverse-lookup store. KnowledgeServer
    // constructed it; we just inject so the LookupPathByHash gRPC handler returns real data.
    this.ingestService.setPathResolutionStore(ctx.pathResolutionStore());
    this.ingestService.setDocumentIdentityStore(ctx.documentIdentityStore());
    this.ingestService.setMutationAdmission(mutationAdmission, mutationOwnerToken);

    // Tempdoc 400 §22 Issue D / LR2-e.4 (Phase 6 / 6.7): wire the
    // active-generation supplier from the ingest service's
    // IndexGenerationManager into the search service's
    // SearchOrchestrator so search/retrieval spans carry
    // search.searcher_generation.
    this.searchService.setActiveGenerationSupplier(
        this.ingestService.activeGenerationSupplier());

    // 4. Health service (also converted off the ImplBase; its wire adapter went at item A9).
    List<WorkerModelDiscovery.DiscoveredModel> discoveredModels =
        configuration.discoveredModels();
    this.healthService =
        new WorkerHealthService(
            ctx.config().serviceVersion(),
            ctx.jobQueue(),
            ctx.searchLifecycleSupplier().get().indexCountOps(),
            null, // embeddingService — not wired post-construction (pre-existing)
            this::indexingLoopState,
            discoveredModels);

    // 5. Cross-service wiring (previously in the gRPC wiring, deleted at item A9)
    this.chunkRerankerConfig = configuration.chunkReranker();
    this.citationScorerConfig = configuration.citationScorer();
    searchService.setChunkRerankerConfig(chunkRerankerConfig);
    searchService.setCitationScorerConfig(citationScorerConfig);
    // setSignalBus removed by tempdoc 397 §14.26 T2-E1 along with the RagContextOps CPU-only
    // lazy chunkReranker fallback that was the only consumer of the signal bus in the rerank
    // path.
    ingestService.setOrtCudaStatusSupplier(searchService::getOrtCudaStatus);
    Path rerankerModelPath = chunkRerankerConfig.modelPath();
    ingestService.setRerankerModelPathSupplier(
        () -> rerankerModelPath != null ? rerankerModelPath.toString() : "");

    // Tempdoc 406 — wire swap-aware runtime gauges supplier. Reads the current ingest
    // runtime per call so the values reflect post-swap state without re-wiring.
    ingestService.setRuntimeGaugesSupplier(
        () -> {
          var rt = ctx.ingestLifecycleSupplier().get();
          if (rt instanceof RunningRuntime r) {
            return r.runtimeGaugesSnapshot();
          }
          return io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeGaugesSnapshot.EMPTY;
        });

    // 6. Wire session-active suppliers for health check model status (368 RC3)
    healthService.setModelActiveSupplier("reranker", () -> {
      var status = searchService.getOrtCudaStatus();
      return status != null && status.available();
    });
    healthService.setModelActiveSupplier("citation-scorer",
        searchService::isCitationScorerActive);

    // 7. Tempdoc 418 Phase B — Worker-side filesystem watcher. Constructed after JobQueue is
    // wired (via ctx) so events feed straight into the queue; injected into the ingest
    // service's RootWatcherRegistry so subsequent WatchRoot RPCs route real Methvin events.
    // Tempdoc 418 B-H.4 — DELETE events route to IndexingCoordinator.deleteByIdAndChunks so
    // the parent doc + chunks are removed in one Worker-side write. In deferred mode
    // (ingestRunning == null) the sink is a no-op; this is acceptable because deletions in
    // deferred mode are handled by the periodic sync once the writer upgrades.
    // Tempdoc 931 §C.6 — a confirmed deletion point: the OS reported the file removed. The marker
    // re-verifies absence, so a DELETE event that is really the first half of an atomic replace
    // (or a recreate that beat us to the check) records nothing.
    var watcherDeletionMarker =
        new io.justsearch.indexerworker.services.ConfirmedDeletionMarker(
            ctx.documentIdentityStore());
    this.preparedWatcherTarget =
        new WatcherCallbacks.Target(ingestRunning, ingestService, watcherDeletionMarker);
    this.watcherCallbacks = new WatcherCallbacks(preparedWatcherTarget);
    var workerWatcherCatalog =
        new io.justsearch.indexerworker.services.WorkerWatcherMetricCatalog(ctx.metricRegistry());
    // Tempdoc 626 §Axis-A — OVERFLOW/burst recovery is now Worker-owned (in-process reconcile),
    // so the redundant Head watcher can be retired without dropping these safety nets.
    this.workerWatcher = new io.justsearch.indexerworker.services.WorkerMethvinWatcher(
        executors.watcherReconcile(), ctx.jobQueue(), workerWatcherCatalog,
        watcherCallbacks::delete, watcherCallbacks::reconcile, watcherCallbacks::upsert,
        ignored -> mutationAdmission.markReplayUncertain());
    this.ingestService.setRootWatcherRegistry(
        new io.justsearch.indexerworker.services.RootWatcherRegistry(this.workerWatcher));
    this.embeddingProviderTarget = new EmbeddingProviderTarget(this);
  }

  /**
   * Prepares the post-promotion Green service view without opening another runtime or producer.
   *
   * <p>During a Blue/Green rebuild this bundle already owns the writer side: its indexing loop,
   * extractor, watcher, and encoder registry all target Green while its search service still
   * targets Blue. The successor therefore creates only fresh search/ingest/health views over the
   * already-open Green runtime and borrows those existing producer objects. Closing an aborted
   * successor cannot close the shared producer.
   */
  public DefaultWorkerAppServices prepareServingSuccessor(InfraContext greenContext) {
    java.util.Objects.requireNonNull(greenContext, "greenContext");
    var search = greenContext.searchLifecycleSupplier().get();
    var ingest = greenContext.ingestLifecycleSupplier().get();
    if (producerRuntime == null
        || indexingLoop == null
        || workerWatcher == null
        || !producerOwnership.ownsProducer()) {
      throw new IllegalStateException("Incumbent does not own a running producer");
    }
    if (search != producerRuntime || ingest != producerRuntime) {
      throw new IllegalArgumentException(
          "Successor context must bind search and ingest to the incumbent Green runtime");
    }
    return new DefaultWorkerAppServices(this, greenContext, producerRuntime);
  }

  /** Builds only the runtime-bound service view; all producer resources remain borrowed. */
  private DefaultWorkerAppServices(
      DefaultWorkerAppServices incumbent, InfraContext greenContext, RunningRuntime greenRuntime) {
    this.candidateConfiguration = incumbent.candidateConfiguration;
    this.resolvedConfig = candidateConfiguration == null
        ? incumbent.resolvedConfig : candidateConfiguration.snapshot();
    this.extractionConfiguration = candidateConfiguration == null
        ? incumbent.extractionConfiguration : candidateConfiguration.extraction();
    this.detailedTracing = !"none".equalsIgnoreCase(resolvedConfig.index().tracingLevel());
    this.chunkRerankerConfig = candidateConfiguration == null
        ? incumbent.chunkRerankerConfig : candidateConfiguration.chunkReranker();
    this.citationScorerConfig = candidateConfiguration == null
        ? incumbent.citationScorerConfig : candidateConfiguration.citationScorer();
    this.indexingPacing = incumbent.indexingPacing;
    this.indexingLoop = incumbent.indexingLoop;
    this.producerRuntime = greenRuntime;
    this.borrowedFrom = incumbent;
    this.mutationOwnerToken = new Object();
    this.mutationAdmission = incumbent.mutationAdmission;
    this.encoderBindings = incumbent.producerEncoderBindings;
    this.producerEncoderBindings = incumbent.producerEncoderBindings;
    this.workerWatcher = incumbent.workerWatcher;
    this.watcherCallbacks = incumbent.watcherCallbacks;
    this.producerOwnership = new SharedProducerOwnership(false);

    EmbeddingProvider provider = indexingLoop.getEmbeddingLifecycle().embeddingProvider();
    this.searchService = new WorkerSearchService(greenRuntime, provider, encoderBindings);
    this.embeddingProviderTarget = incumbent.embeddingProviderTarget;
    this.ingestService =
        new WorkerIngestService(
            greenContext.jobQueue(),
            indexingLoop,
            greenContext.signalBus(),
            indexingPacing,
            greenContext.indexBasePath(),
            greenContext.activeIndexPath(),
            greenRuntime,
            greenRuntime,
            greenContext.migrationProgressSupplier(),
            greenContext.migrationSwitchingMaxDurationMs());
    this.preparedWatcherTarget = new WatcherCallbacks.Target(
        greenRuntime, this.ingestService, incumbent.preparedWatcherTarget.deletionMarker());
    ingestService.setPathResolutionStore(greenContext.pathResolutionStore());
    ingestService.setDocumentIdentityStore(greenContext.documentIdentityStore());
    ingestService.setMutationAdmission(mutationAdmission, mutationOwnerToken);
    searchService.setActiveGenerationSupplier(ingestService.activeGenerationSupplier());

    this.healthService =
        new WorkerHealthService(
            greenContext.config().serviceVersion(),
            greenContext.jobQueue(),
            greenRuntime.indexCountOps(),
            provider,
            this::indexingLoopState,
            candidateConfiguration == null ? incumbent.healthService.discoveredModels()
                : candidateConfiguration.discoveredModels());

    searchService.setChunkRerankerConfig(chunkRerankerConfig);
    searchService.setCitationScorerConfig(citationScorerConfig);
    ingestService.setOrtCudaStatusSupplier(searchService::getOrtCudaStatus);
    Path rerankerModelPath = chunkRerankerConfig.modelPath();
    ingestService.setRerankerModelPathSupplier(
        () -> rerankerModelPath != null ? rerankerModelPath.toString() : "");
    ingestService.setRuntimeGaugesSupplier(greenRuntime::runtimeGaugesSnapshot);
    healthService.setModelActiveSupplier("reranker", () -> {
      var status = searchService.getOrtCudaStatus();
      return status != null && status.available();
    });
    healthService.setModelActiveSupplier("citation-scorer", searchService::isCitationScorerActive);
    ingestService.setRootWatcherRegistry(
        new io.justsearch.indexerworker.services.RootWatcherRegistry(workerWatcher));

    var lifecycle = indexingLoop.getEmbeddingLifecycle();
    var ecc = lifecycle.embeddingCompatController();
    if (ecc != null) {
      searchService.setEmbeddingCompatController(ecc);
      ingestService.setEmbeddingCompatController(ecc);
    }
    var bindings = encoderBindings.snapshot();
    if (bindings.bgeM3Encoder() != null) healthService.setBgeM3Encoder(bindings.bgeM3Encoder());
    if (bindings.disambiguationService() != null) {
      searchService.setClusterSnapshotSupplier(bindings.disambiguationService()::snapshot);
    }
    this.spladeIdfQueryEncoder = incumbent.spladeIdfQueryEncoder;
    if (spladeIdfQueryEncoder != null) {
      searchService.setSpladeIdfQueryEncoder(spladeIdfQueryEncoder);
    }
    this.searchReranker = incumbent.searchReranker;
    if (searchReranker != null) searchService.setSearchReranker(searchReranker);
    this.citationScorer = incumbent.citationScorer;
    if (citationScorer != null) searchService.setCitationScorer(citationScorer);
    this.gpuDiagnostics = incumbent.gpuDiagnostics;
    if (gpuDiagnostics != null) wireGpuDiagnostics(gpuDiagnostics);
    this.stageAvailability = incumbent.stageAvailability;
    if (stageAvailability != null) {
      ingestService.setStageEnabled(
          stageAvailability.embedding(), stageAvailability.splade(), stageAvailability.ner());
    }
    this.modelReadyLatchSupplier = incumbent.modelReadyLatchSupplier;
    if (modelReadyLatchSupplier != null) {
      searchService.setModelReadyLatchSupplier(modelReadyLatchSupplier);
    }
    this.policySnapshotSupplier = incumbent.policySnapshotSupplier;
    if (policySnapshotSupplier != null) {
      ingestService.setPolicySnapshotSupplier(policySnapshotSupplier);
    }
  }

  /**
   * Validates the exact successor and freezes provider notifications before durable promotion.
   * The owner closes the returned lease after publication or precommit abandonment.
   */
  public ProducerTransfer prepareProducerTransferTo(DefaultWorkerAppServices successor) {
    java.util.Objects.requireNonNull(successor, "successor");
    if (successor == this) throw new IllegalArgumentException("Successor must be distinct");
    embeddingProviderTarget.lock.lock();
    try {
      if (!producerOwnership.ownsProducer() || successor.producerOwnership.closed()
          || successor.producerOwnership.ownsProducer() || successor.borrowedFrom != this) {
        throw new IllegalStateException("Producer ownership is not transferable");
      }
      if (producerRuntime == null
          || successor.producerRuntime != producerRuntime
          || successor.indexingLoop != indexingLoop
          || successor.encoderBindings != producerEncoderBindings
          || successor.workerWatcher != workerWatcher
          || successor.watcherCallbacks != watcherCallbacks
          || successor.indexingPacing != indexingPacing
          || successor.resolvedConfig != (candidateConfiguration == null
              ? resolvedConfig : candidateConfiguration.snapshot())) {
        throw new IllegalArgumentException("Successor does not borrow this exact Green producer");
      }
      EmbeddingProvider current = indexingLoop.getEmbeddingLifecycle().embeddingProvider();
      successor.searchService.setEmbeddingProvider(current);
      successor.healthService.setEmbeddingProvider(current);
      return new ProducerTransfer(successor);
    } catch (RuntimeException | Error failure) {
      embeddingProviderTarget.lock.unlock();
      throw failure;
    }
  }

  public WorkerMutationAdmission mutationAdmission() { return mutationAdmission; }

  /** Retains the exact native model set until this producer's actual exit or ownership transfer. */
  public void replaceProducerModelLease(Runnable release) {
    producerOwnership.replaceModelLease(release);
  }

  /** The Green writer stays owned while A's native set is retired for an in-place build. */
  public void clearProducerModelLease() {
    producerOwnership.clearModelLease();
  }

  public boolean producerClosed() { return producerOwnership.closed(); }

  /** Physical producer identity shared by wrappers of this exact service view. */
  public Object mutationOwnerToken() { return mutationOwnerToken; }

  /** Parks the already-running Green writer after its current batch, without replacing it. */
  public boolean pauseProducerForCutover(long timeoutMs) {
    if (!producerOwnership.ownsProducer() || indexingLoop == null) {
      throw new IllegalStateException("Cutover requires the live Green producer");
    }
    return indexingLoop.pauseForCutover(timeoutMs);
  }

  public void resumeProducerAfterCutover() {
    if (indexingLoop != null) indexingLoop.resumeAfterCutover();
  }

  /** Exact prepared producer transfer; install is assignment-only after pointer commitment. */
  public final class ProducerTransfer implements AutoCloseable {
    private final DefaultWorkerAppServices successor;
    private final Thread ownerThread;
    private boolean installed;
    private boolean released;

    private ProducerTransfer(DefaultWorkerAppServices successor) {
      this.successor = successor;
      this.ownerThread = Thread.currentThread();
    }

    public void install() {
      requireOwnerThread();
      if (released) {
        throw new IllegalStateException("Producer transfer lease is already released");
      }
      if (installed) return;
      producerOwnership.transferTo(successor.producerOwnership);
      embeddingProviderTarget.target = successor;
      watcherCallbacks.target = successor.preparedWatcherTarget;
      if (candidateConfiguration != null) {
        indexingLoop.getEmbeddingLifecycle()
            .setEmbeddingProviderChangeListener(embeddingProviderTarget);
      }
      installed = true;
    }

    @Override public void close() {
      requireOwnerThread();
      if (released) return;
      released = true;
      embeddingProviderTarget.lock.unlock();
    }

    private void requireOwnerThread() {
      if (Thread.currentThread() != ownerThread) {
        throw new IllegalStateException("Producer transfer lease belongs to its preparing thread");
      }
    }
  }

  // ==================== Service accessors ====================

  @Override
  public WorkerSearchService searchService() {
    return searchService;
  }

  @Override
  public WorkerIngestService ingestService() {
    return ingestService;
  }

  @Override
  public WorkerHealthService healthService() {
    return healthService;
  }

  /** Snapshot from which this complete service set was composed. */
  public ResolvedConfig resolvedConfig() {
    return resolvedConfig;
  }

  /** Exact normalized extraction and admission values applied by this service set. */
  public ExtractionConfiguration extractionConfiguration() {
    return extractionConfiguration;
  }

  public boolean detailedTracing() {
    return detailedTracing;
  }

  public RerankerConfig.ChunkRerankerConfig chunkRerankerConfig() {
    return chunkRerankerConfig;
  }

  public CitationScorerConfig citationScorerConfig() {
    return citationScorerConfig;
  }

  // ==================== Indexing loop lifecycle ====================

  @Override
  public void startIndexingLoop() {
    if (indexingLoop != null) {
      indexingLoop.start();
    }
    // Else: deferred mode — KS will reconstruct appServices and start the loop
    // after DeferredRuntime.prepareWriterUpgrade().
  }

  /** Constructs B's loop thread without permitting it to claim jobs before publication. */
  public void prepareIndexingLoop() {
    if (indexingLoop != null) indexingLoop.prepareStart();
  }

  /** Opens the already-started loop after B is selected; no thread construction remains. */
  public void activatePreparedIndexingLoop() {
    if (indexingLoop != null) indexingLoop.activatePreparedStart();
  }

  @Override
  public String indexingLoopState() {
    return indexingLoop != null ? indexingLoop.getCurrentState() : "STARTING";
  }

  @Override
  public boolean recordedWriterReady() {
    return indexingLoop != null && indexingLoop.isRunning();
  }

  @Override
  public IndexingPacing indexingPacing() {
    return indexingPacing;
  }

  // ==================== Deferred model wiring ====================

  // Tempdoc 516 P3 / Slice 5 (W7.2 followup): the 4 embedding-related setters on
  // IndexingLoop were removed. DWAS now reaches the lifecycle directly via
  // loop.getEmbeddingLifecycle() — typed collaborator access instead of 4 mutation shims.

  @Override
  public void wireEmbeddingProvider(EmbeddingProvider provider) {
    if (indexingLoop != null && candidateConfiguration == null) {
      indexingLoop.getEmbeddingLifecycle().setEmbeddingProvider(provider);
    }
    embeddingProviderTarget.accept(provider);
    // 309 §33: Propagate future GPU-transition embedding reloads to SearchOrchestrator.
    if (indexingLoop != null && candidateConfiguration == null) {
      indexingLoop
          .getEmbeddingLifecycle()
          .setEmbeddingProviderChangeListener(embeddingProviderTarget);
    }
  }

  /** Publish B's complete index-time encoders without changing A's query bindings. */
  public void wireCandidateProducer(EmbeddingProvider provider, EncoderBindings.Snapshot bindings) {
    if (candidateConfiguration == null || indexingLoop == null
        || producerEncoderBindings == encoderBindings) {
      throw new IllegalStateException("No detached candidate producer is available");
    }
    producerEncoderBindings.publish(java.util.Objects.requireNonNull(bindings, "bindings"));
    indexingLoop.getEmbeddingLifecycle().setEmbeddingProvider(provider);
  }

  /**
   * A separate lexical query view over the same read runtime. Issued calls keep this service's
   * original model bindings until their serving leases leave; Green keeps its writer owner.
   */
  public WorkerAppServices prepareTextOnlyCandidateView(
      io.justsearch.adapters.lucene.runtime.LuceneRuntime activeRuntime) {
    if (candidateConfiguration == null || producerEncoderBindings == encoderBindings) {
      throw new IllegalStateException("No detached candidate producer is available");
    }
    var lexicalSearch = new WorkerSearchService(
        java.util.Objects.requireNonNull(activeRuntime, "activeRuntime"));
    lexicalSearch.setActiveGenerationSupplier(ingestService.activeGenerationSupplier());
    return new TextOnlyCandidateView(this, lexicalSearch, healthService.textOnlyView());
  }

  private record TextOnlyCandidateView(DefaultWorkerAppServices incumbent,
      WorkerSearchService lexicalSearch, WorkerHealthService lexicalHealth)
      implements WorkerAppServices {
    @Override public WorkerSearchService searchService() { return lexicalSearch; }
    @Override public WorkerIngestService ingestService() { return incumbent.ingestService(); }
    @Override public WorkerHealthService healthService() { return lexicalHealth; }
    @Override public void startIndexingLoop() { incumbent.startIndexingLoop(); }
    @Override public String indexingLoopState() { return incumbent.indexingLoopState(); }
    @Override public boolean recordedWriterReady() { return incumbent.recordedWriterReady(); }
    @Override public IndexingPacing indexingPacing() { return incumbent.indexingPacing(); }
    @Override public void wireEmbeddingProvider(EmbeddingProvider provider) {
      throw new IllegalStateException("A lexical view has no model wiring authority");
    }
    @Override public void wireEmbeddingCompatController(EmbeddingCompatibilityController ecc) {
      throw new IllegalStateException("A lexical view has no model wiring authority");
    }
    @Override public void wireNerService(NerService ns) {
      throw new IllegalStateException("A lexical view has no model wiring authority");
    }
    @Override public void wireSpladeEncoder(SpladeEncoder enc) {
      throw new IllegalStateException("A lexical view has no model wiring authority");
    }
    @Override public void wireSpladeIdfQueryEncoder(SpladeIdfQueryEncoder enc) {
      throw new IllegalStateException("A lexical view has no model wiring authority");
    }
    @Override public void wireBgeM3Encoder(BgeM3Encoder enc) {
      throw new IllegalStateException("A lexical view has no model wiring authority");
    }
    @Override public void wireDisambiguationService(DisambiguationService ds) {
      throw new IllegalStateException("A lexical view has no model wiring authority");
    }
    @Override public void wireGpuDiagnostics(GpuDiagnosticSuppliers suppliers) {
      throw new IllegalStateException("A lexical view has no model wiring authority");
    }
    @Override public void wireSearchReranker(CrossEncoderReranker reranker) {
      throw new IllegalStateException("A lexical view has no model wiring authority");
    }
    @Override public void onMainClaimedGpu() { lexicalSearch.onMainClaimedGpu(); }
    @Override public void close() throws IOException { incumbent.close(); }
  }

  /** Old A calls have drained; status must not inspect soon-to-retire native wrappers. */
  public void clearCandidateSourceStatusDiagnostics() {
    if (candidateConfiguration == null || producerEncoderBindings == encoderBindings) {
      throw new IllegalStateException("No detached candidate producer is available");
    }
    healthService.setEmbeddingProvider(null);
    healthService.setBgeM3Encoder(null);
    ingestService.setSpladeOrtCudaStatusSupplier(null);
    ingestService.setSpladeModelPathSupplier(null);
    ingestService.setEmbedOrtCudaStatusSupplier(null);
    ingestService.setEmbedBackendSupplier(null);
    ingestService.setEmbedGpuLayersSupplier(null);
    ingestService.setOrtCudaStatusSupplier(null);
    ingestService.setNerOrtCudaStatusSupplier(null);
    ingestService.setCitationOrtCudaStatusSupplier(null);
    ingestService.setBgeM3OrtCudaStatusSupplier(null);
  }

  /** Restores A's non-native query configuration after its exact model set is recomposed. */
  public void restoreCandidateSourceQueryConfiguration() {
    if (candidateConfiguration == null || producerEncoderBindings == encoderBindings) {
      throw new IllegalStateException("No detached candidate producer is available");
    }
    searchService.setChunkRerankerConfig(chunkRerankerConfig);
    searchService.setCitationScorerConfig(citationScorerConfig);
  }

  /** Publishes the recomposed A query set without touching Green's detached producer. */
  public void wireRestoredSourceEncoders(EncoderBindings.Snapshot bindings) {
    if (candidateConfiguration == null || producerEncoderBindings == encoderBindings) {
      throw new IllegalStateException("No detached candidate producer is available");
    }
    encoderBindings.publish(java.util.Objects.requireNonNull(bindings, "bindings"));
    healthService.setBgeM3Encoder(bindings.bgeM3Encoder());
    var disambiguation = bindings.disambiguationService();
    searchService.setClusterSnapshotSupplier(
        disambiguation == null ? null : disambiguation::snapshot);
  }

  /** Bind B's write-side compatibility proof without changing A's query admission. */
  public void wireCandidateEmbeddingCompatController(EmbeddingCompatibilityController candidate) {
    if (candidateConfiguration == null || indexingLoop == null
        || producerEncoderBindings == encoderBindings) {
      throw new IllegalStateException("No detached candidate producer is available");
    }
    indexingLoop.getEmbeddingLifecycle().setEmbeddingCompatController(
        java.util.Objects.requireNonNull(candidate, "candidate"));
  }

  @Override
  public void addEmbeddingProviderChangeListener(
      java.util.function.Consumer<EmbeddingProvider> listener) {
    if (indexingLoop != null && candidateConfiguration == null && listener != null) {
      indexingLoop.getEmbeddingLifecycle().addEmbeddingProviderChangeListener(listener);
    }
  }

  // 516 P3 FINAL CUT: wireEmbeddingTelemetryEvents removed — pre-wired via DWAS 2-arg ctor.

  @Override
  public void wireEmbeddingCompatController(EmbeddingCompatibilityController ecc) {
    if (indexingLoop != null && candidateConfiguration == null) {
      indexingLoop.getEmbeddingLifecycle().setEmbeddingCompatController(ecc);
    }
    searchService.setEmbeddingCompatController(ecc);
    if (candidateConfiguration == null) ingestService.setEmbeddingCompatController(ecc);
  }

  // 516 P3 FINAL CUT: wireMigrationActiveSupplier removed — pre-wired via DWAS 2-arg ctor.

  // Tempdoc 516 P3 / Slice 5 (W7.2): wireX methods now bind ONCE on the shared
  // EncoderBindings. IndexingLoop + SearchOrchestrator both read through the same
  // registry slot — no more double-dispatch to peer setters.

  @Override
  public void wireNerService(NerService ns) {
    encoderBindings.bindNerService(ns);
  }

  @Override
  public void wireSpladeEncoder(SpladeEncoder enc) {
    encoderBindings.bindSpladeEncoder(enc);
  }

  @Override
  public void wireSpladeIdfQueryEncoder(SpladeIdfQueryEncoder idfEnc) {
    this.spladeIdfQueryEncoder = idfEnc;
    // Query-side IDF helper — stays as a SearchOrchestrator-only path (no EncoderBindings
    // symmetry; the indexing-side encoder is the SPLADE one bound above).
    searchService.setSpladeIdfQueryEncoder(idfEnc);
  }

  @Override
  public void wireBgeM3Encoder(BgeM3Encoder enc) {
    encoderBindings.bindBgeM3Encoder(enc);
    healthService.setBgeM3Encoder(enc);
  }

  @Override
  public void wireDisambiguationService(DisambiguationService ds) {
    encoderBindings.bindDisambiguationService(ds);
    // The clusterSnapshotSupplier is a derived view, not the service itself —
    // SearchOrchestrator stays the binding point for the supplier side.
    searchService.setClusterSnapshotSupplier(ds::snapshot);
  }

  @Override
  public void wireGpuDiagnostics(GpuDiagnosticSuppliers suppliers) {
    this.gpuDiagnostics = suppliers;
    if (suppliers.spladeOrtCudaStatus() != null) {
      ingestService.setSpladeOrtCudaStatusSupplier(suppliers.spladeOrtCudaStatus());
    }
    if (suppliers.spladeModelPath() != null) {
      ingestService.setSpladeModelPathSupplier(suppliers.spladeModelPath());
    }
    if (suppliers.embedOrtCudaStatus() != null) {
      ingestService.setEmbedOrtCudaStatusSupplier(suppliers.embedOrtCudaStatus());
    }
    if (suppliers.embedBackend() != null) {
      ingestService.setEmbedBackendSupplier(suppliers.embedBackend());
    }
    if (suppliers.embedGpuLayers() != null) {
      ingestService.setEmbedGpuLayersSupplier(suppliers.embedGpuLayers());
    }
    // 360: override reranker status supplier when search reranker is wired via initDeferredModels
    if (suppliers.rerankerOrtCudaStatus() != null) {
      ingestService.setOrtCudaStatusSupplier(suppliers.rerankerOrtCudaStatus());
    }
    // Tempdoc 422: per-encoder runtime status for the explainer endpoint.
    if (suppliers.nerOrtCudaStatus() != null) {
      ingestService.setNerOrtCudaStatusSupplier(suppliers.nerOrtCudaStatus());
    }
    if (suppliers.citationOrtCudaStatus() != null) {
      ingestService.setCitationOrtCudaStatusSupplier(suppliers.citationOrtCudaStatus());
    }
    if (suppliers.bgeM3OrtCudaStatus() != null) {
      ingestService.setBgeM3OrtCudaStatusSupplier(suppliers.bgeM3OrtCudaStatus());
    }
  }

  @Override
  public void wireStageEnabled(boolean embedding, boolean splade, boolean ner) {
    this.stageAvailability = new StageAvailability(embedding, splade, ner);
    ingestService.setStageEnabled(embedding, splade, ner);
  }

  // ==================== 360: Search reranker ====================

  @Override
  public void wireSearchReranker(CrossEncoderReranker reranker) {
    this.searchReranker = reranker;
    searchService.setSearchReranker(reranker);
  }

  // ==================== Citation scorer (§14.26 T2-E1 eager-wire) ====================

  @Override
  public void wireCitationScorer(io.justsearch.reranker.CitationScorer scorer) {
    this.citationScorer = scorer;
    searchService.setCitationScorer(scorer);
  }

  // ==================== Models-ready gate (§14.28 U3) ====================

  @Override
  public void wireModelReadyLatch(
      java.util.function.Supplier<java.util.concurrent.CountDownLatch> latchSupplier) {
    this.modelReadyLatchSupplier = latchSupplier;
    searchService.setModelReadyLatchSupplier(latchSupplier);
  }

  // ==================== Session-policies diagnostic (§14.28 U4) ====================

  @Override
  public void wirePolicySnapshotSupplier(
      java.util.function.Supplier<io.justsearch.ort.PolicySnapshot> supplier) {
    this.policySnapshotSupplier = supplier;
    ingestService.setPolicySnapshotSupplier(supplier);
  }

  // ==================== GPU lifecycle ====================

  @Override
  public void onMainClaimedGpu() {
    searchService.onMainClaimedGpu();
  }

  // ==================== Closeable ====================

  @Override
  public void close() throws IOException {
    producerOwnership.close(workerWatcher, indexingLoop);
  }

  // ==================== Sandbox seam (tempdoc 410) ====================

  /**
   * Selects an extraction sandbox based on {@link EnvRegistry#EXTRACTION_SANDBOX_MODE}.
   *
   * <p>Tempdoc 885 item 14: the default is {@code auto} — PDF/Office/archive/image files are
   * parsed in a persistent child process, everything else in the Worker JVM. {@code in_process}
   * and {@code process} force one side. There is no longer a "process mode requires an operator
   * command" precondition: that precondition is exactly why the sandbox tempdoc 410 shipped was
   * unreachable, and the command is now built in-process by {@link ExtractionSandboxCommand}.
   * {@link EnvRegistry#EXTRACTION_SANDBOX_COMMAND} remains as an operator override.
   */
  static TimeboxedContentExtractor buildContentExtractor(
      WorkerExecutorRegistrations executors,
      @SuppressWarnings("unused") InfraContext ctx,
      ExtractionMetricCatalog catalog,
      OcrMetricCatalog ocrCatalog,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      ExtractionConfiguration configuration) {
    OcrRoutingConfig ocrConfig = configuration.ocr();
    logEffectiveOcrConfig(ocrConfig);
    TikaExtractionPolicy extractionPolicy = configuration.tikaPolicy();
    ExtractionSandboxFactory.Mode sandboxMode = configuration.sandboxMode();
    if (sandboxMode == ExtractionSandboxFactory.Mode.IN_PROCESS) {
      return ExtractionSandboxFactory.inProcessStructured(
          executors.pdfOcr(),
          executors.extractionTimebox(), catalog, ocrConfig, ocrCatalog, extractionPolicy);
    }
    List<String> command = configuration.sandboxCommand();
    ExtractionSandboxFactory.PoolSettings poolSettings = configuration.sandboxPool();
    log.info(
        "Extraction sandbox mode={} pool={} maxRequestsPerChild={} command={}",
        sandboxMode,
        poolSettings.poolSize(),
        poolSettings.maxRequestsPerChild(),
        command);

    // Spawning is lazy, so without this a broken child command would be invisible until the first
    // process-routed file. The probe makes the failure visible at boot, but it must not weaken the
    // process boundary: AUTO still serves decoder-only families in process, while routed families
    // report SANDBOX_FAILED and follow the durable retry policy.
    Optional<String> probeFailure =
        ExtractionSandboxFactory.probeChildCommand(
            executors.sandboxReaders(), command, extractionPolicy, ocrConfig,
            ExtractionSandboxFactory.PROBE_TIMEOUT,
            childRegistry);
    if (probeFailure.isPresent()) {
      log.warn(
          "Extraction sandbox child failed its startup probe ({}); process-routed extraction "
              + "will remain unavailable until the child command recovers. Command: {}",
          probeFailure.get(),
          command);
      if (catalog != null) {
        catalog.sandboxRestartTotal.increment(
            ExtractionSandboxRestartTags.of(PersistentExtractionSandbox.REASON_PROBE_FAILED));
      }
    }
    return ExtractionSandboxFactory.create(
        executors.pdfOcr(),
        executors.extractionTimebox(),
        executors.sandboxReaders(),
        sandboxMode,
        extractionPolicy,
        ocrConfig,
        TimeboxedContentExtractor.DEFAULT_TIMEOUT,
        catalog,
        ocrCatalog,
        command,
        poolSettings,
        childRegistry);
  }

  /**
   * Diagnosability fix (tempdoc 706): the effective OCR config previously appeared nowhere in the
   * worker's log, which made an unbounded-OCR incident undiagnosable without code archaeology.
   * Logged once at startup, after config-absent gaps are filled in {@link OcrRoutingConfig#from}.
   */
  private static void logEffectiveOcrConfig(OcrRoutingConfig ocrConfig) {
    log.info(
        "Effective OCR config: enabled={} budgetMs={} maxPages={} renderDpi={} workers={} "
            + "maxImageDimension={} maxImagePixels={} languages={}",
        ocrConfig.enabled(),
        ocrConfig.perFileTimeoutMs(),
        ocrConfig.maxPages(),
        ocrConfig.effectiveRenderDpi(),
        ocrConfig.effectiveOcrWorkers(),
        ocrConfig.maxImageDimension(),
        ocrConfig.maxImagePixels(),
        ocrConfig.languages());
  }

  /**
   * Tempdoc 410 §13 Slice B — builds an {@link io.justsearch.indexerworker.ingest.IngestionSkipPolicy}
   * from the {@code JUSTSEARCH_INGESTION_SKIP_*} env keys. Each unset key falls back to the
   * built-in defaults (handled inside the policy constructor); set keys replace the defaults
   * wholesale for that field. Package-private since Slice G.3 so the env-to-policy chain is
   * directly testable.
   */
  static io.justsearch.indexerworker.ingest.IngestionSkipPolicy buildSkipPolicy(
      ResolvedConfig snapshot) {
    ResolvedConfig.Extraction extraction = snapshot.extraction();
    return new io.justsearch.indexerworker.ingest.IngestionSkipPolicy(
        parseCsvSet(extraction.ingestionSkipPatterns()),
        parseCsvSet(extraction.ingestionSkipExtensions()),
        parseCsvSet(extraction.ingestionSkipDirectoryNames()));
  }

  private static ResolvedConfig captureResolvedConfig() {
    ConfigStore store = ConfigStore.globalOrNull();
    ResolvedConfig current = store == null ? null : store.get();
    if (current != null) {
      return current;
    }
    return new ResolvedConfigBuilder().contributeEnvRegistry().build();
  }

  /** Package-private since Slice G.3 so the parser is unit-testable in isolation. */
  static java.util.Set<String> parseCsvSet(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    for (String token : raw.split(",")) {
      String trimmed = token.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out.isEmpty() ? null : out;
  }
}
