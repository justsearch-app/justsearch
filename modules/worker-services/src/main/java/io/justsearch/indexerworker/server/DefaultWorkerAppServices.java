/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.extract.ExtractionMetricCatalog;
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
  /**
   * Tempdoc 885 item 3: the one pacing policy every indexing/backfill site throttles against.
   * Owned by {@code KnowledgeServer}, not by this class: the app services are reconstructed on a
   * deferred-runtime upgrade and on dev hot-reload while the gRPC server (and therefore the
   * interceptor that feeds the gauge) is not, so a per-instance gauge would be silently orphaned
   * from its only producer.
   */
  private final IndexingPacing indexingPacing;
  private final WorkerSearchService searchService;
  private final WorkerIngestService ingestService;
  private final WorkerHealthService healthService;
  // W7.2: shared registry held by both IndexingLoop and SearchOrchestrator.
  private final EncoderBindings encoderBindings;
  // Tempdoc 418 Phase B — Worker-side filesystem watcher. Owned by appServices so its lifecycle
  // matches the gRPC service set; closed during {@link #close()}.
  private final io.justsearch.indexerworker.services.WorkerMethvinWatcher workerWatcher;

  /**
   * Back-compat ctor — defaults migrationActiveSupplier + embeddingTelemetryEvents to null.
   * Production (KS) uses the 2-arg ctor below to pre-supply both values at ctor time so the
   * post-ctor wireMigrationActiveSupplier/wireEmbeddingTelemetryEvents paths can go away.
   */
  public DefaultWorkerAppServices(InfraContext ctx) {
    this(ctx, null, null, IndexingPacing.unthrottled());
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
      InfraContext ctx,
      java.util.function.BooleanSupplier migrationActiveSupplier,
      io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents embeddingTelemetryEvents,
      IndexingPacing indexingPacing) {
    // Tempdoc 410 §13 Slice B — publish the operator-resolved IngestionSkipPolicy before any
    // ingestion path can call it. WorkerScanOps and WorkerIngestionAuthority fire during gRPC
    // handling that always happens after this constructor returns, so installing here is safe.
    io.justsearch.indexerworker.ingest.IngestionSkipPolicy.installResolved(buildSkipPolicy());

    // 1. Content extractor + indexing loop. Tempdoc 417 Phase 2b: catalogs are constructed
    // here against the registry (worker-core can't import worker-services' catalog types).
    // Tempdoc 410 sandbox seam threaded through buildContentExtractor; both pipelineCatalog
    // and extractionCatalog are wired into the sandbox + indexing loop.
    var pipelineCatalog = new IndexingPipelineMetricCatalog(ctx.metricRegistry());
    var extractionCatalog = new ExtractionMetricCatalog(ctx.metricRegistry());
    var ocrCatalog = new OcrMetricCatalog(ctx.metricRegistry());
    var ingestionOutcomeCatalog = new IngestionOutcomeMetricCatalog(ctx.metricRegistry());
    var contentExtractor = buildContentExtractor(ctx, extractionCatalog, ocrCatalog);

    this.indexingPacing = java.util.Objects.requireNonNull(indexingPacing, "indexingPacing");
    // Tempdoc 406 Phase 4a: services capture the current runtime via ctx.suppliers.
    // If ingest is DeferredRuntime, construct in "deferred mode": indexingLoop and
    // ingestService are skipped (write side not available until upgrade); the
    // search side works against DeferredRuntime's read ops. KS reconstructs this
    // appServices after DeferredRuntime.upgradeWriter() and swaps the
    // DelegatingX wrappers via setDelegate (mirrors DevReloadManager flow).
    io.justsearch.adapters.lucene.runtime.LuceneRuntime ingestLifecycle =
        ctx.ingestLifecycleSupplier().get();
    io.justsearch.adapters.lucene.runtime.RunningRuntime ingestRunning =
        ingestLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime r ? r : null;

    // Tempdoc 516 P3 / Slice 5 (W7.2): single shared EncoderBindings registry held by both
    // IndexingLoop and SearchOrchestrator. wire* methods below bind once on it instead of
    // fanning out across peer setters.
    this.encoderBindings = new EncoderBindings();

    if (ingestRunning != null) {
      // Tempdoc 516 P3 / Slice 5 (W7.2 followup): the 5 startup-config setters are now
      // IndexingLoopOptions record fields. Construct the options upfront so the loop is
      // immutable post-ctor (no setDetailedTracing/setCommitMetadataSupplier/etc.).
      String tracingLevel = EnvRegistry.INDEX_TRACING_LEVEL
          .getString("none");
      io.justsearch.indexerworker.loop.IndexingLoopOptions loopOptions =
          new io.justsearch.indexerworker.loop.IndexingLoopOptions(
              !"none".equalsIgnoreCase(tracingLevel),                  // detailedTracing
              ctx.pathResolutionStore(),                                // pathResolutionStore
              ctx.documentIdentityStore(),                              // documentIdentityStore
              migrationActiveSupplier,                                  // 516 P3 final — pre-wired at ctor
              ingestRunning::latestCommitUserDataBestEffort,            // commitMetadataSupplier
              embeddingTelemetryEvents);                                // 516 P3 final — pre-wired at ctor

      this.indexingLoop =
          new IndexingLoop(
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
              encoderBindings,
              loopOptions);
    } else {
      this.indexingLoop = null;
    }

    // 2. Search service (null embedding — wired by deferred init). Lane F item A3 converted it
    // off the generated ImplBase. Item A9 deleted the DelegatingSearchService adapter with the
    // gRPC server it was registered in; callers reach this instance through appServices().
    // Works against DeferredRuntime (read ops only) or RunningRuntime.
    // W7.2: shares the encoderBindings instance with IndexingLoop.
    this.searchService =
        new WorkerSearchService(ctx.searchLifecycleSupplier().get(), null, encoderBindings);

    // 3. gRPC ingest service. WorkerIngestService is null-tolerant for
    // ingestLifecycle/indexingLoop — write methods return UNAVAILABLE when
    // either is null. KS reconstructs this with non-null values after
    // DeferredRuntime.upgradeWriter() and swaps the wrapper.
    this.ingestService =
        new WorkerIngestService(
            ctx.jobQueue(),
            indexingLoop,
            ctx.signalBus(),
            indexingPacing,
            ctx.indexBasePath(),
            ctx.activeIndexPath(),
            ingestRunning,
            ctx.searchLifecycleSupplier().get(),
            ctx.migrationProgressSupplier(),
            ctx.migrationSwitchingMaxDurationMs(),
            ctx.initiateShutdownAction());

    // Tempdoc 419 / T5.3 (ADR-0028): wire the scoped reverse-lookup store. KnowledgeServer
    // constructed it; we just inject so the LookupPathByHash gRPC handler returns real data.
    this.ingestService.setPathResolutionStore(ctx.pathResolutionStore());
    this.ingestService.setDocumentIdentityStore(ctx.documentIdentityStore());

    // Tempdoc 400 §22 Issue D / LR2-e.4 (Phase 6 / 6.7): wire the
    // active-generation supplier from the ingest service's
    // IndexGenerationManager into the search service's
    // SearchOrchestrator so search/retrieval spans carry
    // search.searcher_generation.
    this.searchService.setActiveGenerationSupplier(
        this.ingestService.activeGenerationSupplier());

    // 4. Health service (also converted off the ImplBase; its wire adapter went at item A9).
    List<WorkerModelDiscovery.DiscoveredModel> discoveredModels =
        WorkerModelDiscovery.discoverAll();
    this.healthService =
        new WorkerHealthService(
            ctx.config().serviceVersion(),
            ctx.jobQueue(),
            ctx.searchLifecycleSupplier().get().indexCountOps(),
            null, // embeddingService — not wired post-construction (pre-existing)
            this::indexingLoopState,
            discoveredModels);

    // 5. Cross-service wiring (previously in the gRPC wiring, deleted at item A9)
    RerankerConfig.ChunkRerankerConfig chunkRerankerConfig =
        RerankerConfig.ChunkRerankerConfig.fromEnv();
    searchService.setChunkRerankerConfig(chunkRerankerConfig);
    searchService.setCitationScorerConfig(CitationScorerConfig.fromEnv());
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
          if (rt instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime r) {
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
    java.util.function.Consumer<String> deletePathSink =
        ingestRunning != null
            ? path -> {
              ingestRunning.indexingCoordinator().deleteByIdAndChunks(path);
              watcherDeletionMarker.markIfAbsent(path);
            }
            : path -> {};
    var workerWatcherCatalog =
        new io.justsearch.indexerworker.services.WorkerWatcherMetricCatalog(ctx.metricRegistry());
    // Tempdoc 626 §Axis-A — OVERFLOW/burst recovery is now Worker-owned (in-process reconcile),
    // so the redundant Head watcher can be retired without dropping these safety nets.
    java.util.function.BiConsumer<Path, Boolean> reconcileSink =
        this.ingestService::reconcileRoot;
    this.workerWatcher = new io.justsearch.indexerworker.services.WorkerMethvinWatcher(
        ctx.jobQueue(), workerWatcherCatalog, deletePathSink, reconcileSink);
    this.ingestService.setRootWatcherRegistry(
        new io.justsearch.indexerworker.services.RootWatcherRegistry(this.workerWatcher));
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

  // ==================== Indexing loop lifecycle ====================

  @Override
  public void startIndexingLoop() {
    if (indexingLoop != null) {
      indexingLoop.start();
    }
    // Else: deferred mode — KS will reconstruct appServices and start the loop
    // after DeferredRuntime.upgradeWriter().
  }

  @Override
  public String indexingLoopState() {
    return indexingLoop != null ? indexingLoop.getCurrentState() : "STARTING";
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
    if (indexingLoop != null) {
      indexingLoop.getEmbeddingLifecycle().setEmbeddingProvider(provider);
    }
    searchService.setEmbeddingProvider(provider);
    healthService.setEmbeddingProvider(provider);
    // 309 §33: Propagate future GPU-transition embedding reloads to SearchOrchestrator.
    if (indexingLoop != null) {
      indexingLoop
          .getEmbeddingLifecycle()
          .setEmbeddingProviderChangeListener(searchService::setEmbeddingProvider);
    }
  }

  @Override
  public void addEmbeddingProviderChangeListener(
      java.util.function.Consumer<EmbeddingProvider> listener) {
    if (indexingLoop != null && listener != null) {
      indexingLoop.getEmbeddingLifecycle().addEmbeddingProviderChangeListener(listener);
    }
  }

  // 516 P3 FINAL CUT: wireEmbeddingTelemetryEvents removed — pre-wired via DWAS 2-arg ctor.

  @Override
  public void wireEmbeddingCompatController(EmbeddingCompatibilityController ecc) {
    if (indexingLoop != null) {
      indexingLoop.getEmbeddingLifecycle().setEmbeddingCompatController(ecc);
    }
    searchService.setEmbeddingCompatController(ecc);
    ingestService.setEmbeddingCompatController(ecc);
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
    ingestService.setStageEnabled(embedding, splade, ner);
  }

  // ==================== 360: Search reranker ====================

  @Override
  public void wireSearchReranker(CrossEncoderReranker reranker) {
    searchService.setSearchReranker(reranker);
  }

  // ==================== Citation scorer (§14.26 T2-E1 eager-wire) ====================

  @Override
  public void wireCitationScorer(io.justsearch.reranker.CitationScorer scorer) {
    searchService.setCitationScorer(scorer);
  }

  // ==================== Models-ready gate (§14.28 U3) ====================

  @Override
  public void wireModelReadyLatch(
      java.util.function.Supplier<java.util.concurrent.CountDownLatch> latchSupplier) {
    searchService.setModelReadyLatchSupplier(latchSupplier);
  }

  // ==================== Session-policies diagnostic (§14.28 U4) ====================

  @Override
  public void wirePolicySnapshotSupplier(
      java.util.function.Supplier<io.justsearch.ort.PolicySnapshot> supplier) {
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
    if (workerWatcher != null) {
      workerWatcher.close();
    }
    if (indexingLoop != null) {
      indexingLoop.close();
    }
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
  // Package-private for DefaultWorkerAppServicesSandboxProbeTest, like parseCsvSet above: the
  // env-to-extractor chain has no other seam, and the probe-failure branch decides whether a
  // session extracts at all.
  static TimeboxedContentExtractor buildContentExtractor(
      @SuppressWarnings("unused") InfraContext ctx,
      ExtractionMetricCatalog catalog,
      OcrMetricCatalog ocrCatalog) {
    String mode = EnvRegistry.EXTRACTION_SANDBOX_MODE.getString("auto").trim();
    OcrRoutingConfig ocrConfig = resolvedOcrConfig();
    logEffectiveOcrConfig(ocrConfig);
    TikaExtractionPolicy extractionPolicy = resolvedExtractionPolicy();
    ExtractionSandboxFactory.Mode sandboxMode = parseSandboxMode(mode);
    if (sandboxMode == ExtractionSandboxFactory.Mode.IN_PROCESS) {
      return ExtractionSandboxFactory.inProcessStructured(
          catalog, ocrConfig, ocrCatalog, extractionPolicy);
    }
    String rawCommand = EnvRegistry.EXTRACTION_SANDBOX_COMMAND.getString("");
    List<String> command =
        rawCommand == null || rawCommand.isBlank()
            ? ExtractionSandboxCommand.defaultCommand(
                extractionPolicy, EnvRegistry.EXTRACTION_SANDBOX_HEAP.getString(""))
            : ExtractionSandboxCommand.tokenize(rawCommand);
    ExtractionSandboxFactory.PoolSettings poolSettings =
        new ExtractionSandboxFactory.PoolSettings(
            EnvRegistry.EXTRACTION_SANDBOX_POOL.getInt(1),
            EnvRegistry.EXTRACTION_SANDBOX_MAX_REQUESTS.getInt(
                ExtractionSandboxFactory.PoolSettings.defaults().maxRequestsPerChild()));
    log.info(
        "Extraction sandbox mode={} pool={} maxRequestsPerChild={} command={}",
        sandboxMode,
        poolSettings.poolSize(),
        poolSettings.maxRequestsPerChild(),
        command);

    // Spawning is lazy, so without this a broken child command would be invisible until the first
    // file and would then fail EVERY file. One bounded check here converts that into a degraded
    // but working session.
    Optional<String> probeFailure =
        ExtractionSandboxFactory.probeChildCommand(
            command, extractionPolicy, ocrConfig, ExtractionSandboxFactory.PROBE_TIMEOUT);
    if (probeFailure.isPresent()) {
      log.warn(
          "Extraction sandbox child failed its startup probe ({}); falling back to in_process "
              + "extraction for this session. Command: {}",
          probeFailure.get(),
          command);
      if (catalog != null) {
        catalog.sandboxRestartTotal.increment(
            ExtractionSandboxRestartTags.of(PersistentExtractionSandbox.REASON_PROBE_FAILED));
      }
      return ExtractionSandboxFactory.inProcessStructured(
          catalog, ocrConfig, ocrCatalog, extractionPolicy);
    }
    return ExtractionSandboxFactory.create(
        sandboxMode,
        extractionPolicy,
        ocrConfig,
        TimeboxedContentExtractor.DEFAULT_TIMEOUT,
        catalog,
        ocrCatalog,
        command,
        poolSettings);
  }

  private static ExtractionSandboxFactory.Mode parseSandboxMode(String mode) {
    if (mode.isEmpty() || "auto".equalsIgnoreCase(mode)) {
      return ExtractionSandboxFactory.Mode.AUTO;
    }
    if ("in_process".equalsIgnoreCase(mode)) {
      return ExtractionSandboxFactory.Mode.IN_PROCESS;
    }
    if ("process".equalsIgnoreCase(mode)) {
      return ExtractionSandboxFactory.Mode.PROCESS;
    }
    throw new IllegalStateException(
        "Unknown JUSTSEARCH_EXTRACTION_SANDBOX_MODE='"
            + mode
            + "': expected 'auto', 'in_process' or 'process'");
  }

  /**
   * Extraction policy honouring {@code worker.limits.max_content_length} / {@code max_file_size}
   * (tempdoc 799 §N.2). Mirrors {@link #resolvedOcrConfig()}. Returns the deterministic defaults
   * when no ConfigStore is installed or the limits match the defaults, so an unconfigured install
   * keeps the {@code tika-default-v1} identity in the extraction ledger.
   */
  private static TikaExtractionPolicy resolvedExtractionPolicy() {
    ConfigStore store = ConfigStore.globalOrNull();
    if (store == null || store.get() == null) {
      return TikaExtractionPolicy.defaults();
    }
    return TikaExtractionPolicy.fromWorkerLimits(store.get().worker());
  }

  private static OcrRoutingConfig resolvedOcrConfig() {
    ConfigStore store = ConfigStore.globalOrNull();
    if (store == null || store.get() == null) {
      return OcrRoutingConfig.defaults();
    }
    return OcrRoutingConfig.from(store.get().ocr());
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
  static io.justsearch.indexerworker.ingest.IngestionSkipPolicy buildSkipPolicy() {
    return new io.justsearch.indexerworker.ingest.IngestionSkipPolicy(
        parseCsvSet(EnvRegistry.INGESTION_SKIP_PATTERNS.getString(null)),
        parseCsvSet(EnvRegistry.INGESTION_SKIP_EXTENSIONS.getString(null)),
        parseCsvSet(EnvRegistry.INGESTION_SKIP_DIRECTORY_NAMES.getString(null)));
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
