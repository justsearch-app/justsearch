/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.BudgetExceededException;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ContentExtractorProvider;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.ExtractionMetricCatalog;
import io.justsearch.indexerworker.extract.ExtractionSandboxFactory;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor.ExtractionTimeoutException;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.liveness.LivenessWindows;
import io.justsearch.indexerworker.queue.OutcomeWriteException;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.CombinedEnrichmentBackfillOps;
import io.justsearch.indexerworker.loop.ops.EmbeddingBackfillOps;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.ops.LoopPacingPolicy;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.loop.ops.DisambiguationBackfillOps;
import io.justsearch.indexerworker.loop.ops.NerBackfillOps;
import io.justsearch.indexerworker.loop.ops.BgeM3BackfillOps;
import io.justsearch.indexerworker.loop.ops.SpladeBackfillOps;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background indexing loop that processes jobs from the queue.
 *
 * <p>The loop runs a <b>duty cycle</b> against {@link
 * IndexingPacing} (tempdoc 885 item 3): while foreground
 * search-family RPCs are in flight it yields most of each interval to keep the machine responsive,
 * but it never stops. The predecessor was "breath holding" — a full pause on a Head-written
 * wall-clock activity byte — which indexed 699 of 5184 documents in 22 minutes under a continuous
 * search loop.
 *
 * <p>Processing flow for each job:
 * <ol>
 *   <li>Poll pending job from queue</li>
 *   <li>Read file content</li>
 *   <li>Extract text (future: Tika integration)</li>
 *   <li>Generate embeddings (ONNX Runtime)</li>
 *   <li>Index document in Lucene</li>
 *   <li>Mark job as done</li>
 * </ol>
 */
public class IndexingLoop implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(IndexingLoop.class);

  private static final long ERROR_BACKOFF_MS = 1000; // back-off after a recovered error (tempdoc 588)

  /**
   * Staleness bound for the pending-ingest probe published to the signal bus (tempdoc 798).
   *
   * <p>{@link BackfillScheduler}'s tight loop reads {@code signalBus.hasPendingIngest()} once per
   * batch, and the idle branch iterates at ~64 Hz, so an unmemoized SQLite aggregate would be a
   * per-iteration round trip. A wall-clock TTL (rather than an every-N-iterations counter) bounds
   * staleness in the unit that matters — how long a queued ingest job can wait — and stays correct
   * if the loop's iteration rate ever changes. 250 ms caps the probe at ~4 queries/second while
   * keeping the worst-case ingest delay well under a single idle sleep.
   */
  private static final long PENDING_INGEST_PROBE_TTL_MS = 250L;

  // Tempdoc 516 Slice 4d (W6): backfill batch-size constants moved to BackfillScheduler.

  private final JobQueue jobQueue;
  private final CommitOps commitOps;
  // Tempdoc 516 Slice 4d (W6): indexingCoordinator / documentFieldOps / indexCountOps are
  // consumed only by the extracted collaborators (writer, extractor, backfillScheduler,
  // embeddingLifecycle). Local ctor params pass them through directly — no IndexingLoop field
  // needed for those. resolvedConfigSupplier IS kept as a field (tempdoc 710 Wave-1.5 Move 4):
  // this loop's own poll/commit pacing (pollBatchSize, commitIntervalMs, maxDocsBeforeCommit)
  // now reads live from it, the same way BackfillScheduler already reads chunkVectorsEnabled /
  // lateChunkingEnabled from its copy.
  private final Supplier<ResolvedConfig> resolvedConfigSupplier;
  private final WorkerSignalBus signalBus;
  /** Tempdoc 885 item 3: the duty-cycle policy that replaced the breath-hold pause. */
  private final IndexingPacing indexingPacing;
  private final TimeboxedContentExtractor contentExtractor;
  // Tempdoc 516 Slice 4c: embeddingProvider / embeddingServiceForLifecycle / embeddingEvents
  // / embeddingCompatController are now owned by EmbeddingProviderLifecycle. The lifecycle's
  // own field declarations preserve the trust + JMM semantics (volatile + the lifecycle lock).
  // Tempdoc 516 P3 / Slice 5 (W7.2): nerService / spladeEncoder / bgeM3Encoder /
  // disambiguationService moved to EncoderBindings (shared with SearchOrchestrator).
  private final io.justsearch.indexerworker.server.EncoderBindings encoderBindings;
  private final IndexingPipelineMetricCatalog pipelineCatalog;
  private final OperationalMetrics metrics = OperationalMetrics.getInstance();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final WorkerIngestionAuthority ingestionAuthority = new WorkerIngestionAuthority();
  // Tempdoc 410 review fix #1: counts outcome-aware queue write failures (rollback). Persistent
  // increments here mean recoverStuckJobs is bearing more load than usual; surface the signal
  // beyond log lines. Tempdoc 417 merge: migrated from legacy Telemetry.Counter to a typed
  // CounterMetric on IngestionOutcomeMetricCatalog.
  private final io.justsearch.telemetry.catalog.CounterMetric<IngestionOutcomeTags>
      outcomeWriteFailureCounter;

  // Tempdoc 419 / T5.2 (ADR-0028): records (pathHash, normalizedPath) for the scoped
  // reverse-lookup resolver. Defaults to NOOP so tests and any composition that omits explicit
  // wiring continue to work. DefaultWorkerAppServices binds the real PathResolutionStore via
  // setPathResolutionStore() at boot.
  private volatile io.justsearch.indexerworker.path.PathResolutionStore pathResolutionStore =
      io.justsearch.indexerworker.path.PathResolutionStore.NOOP;
  private final io.justsearch.indexerworker.identity.DocumentIdentityStore documentIdentityStore;

  // Pipeline tracing (312 Phase 0) — zero-cost when detailedTracing is false.
  private final Tracer tracer = GlobalOpenTelemetry.getTracer("indexing");
  private volatile boolean detailedTracing;

  // 312 Phase 4: when true, inline embedding is enabled during migration rebuilds.
  private volatile java.util.function.BooleanSupplier migrationActiveSupplier = () -> false;

  // Tempdoc 400 LR2-d.2: supplies the current commit user-data map for
  // attaching commit.* identity attributes to indexing.batch spans. Default
  // returns an empty map so spans render without commit attrs when unwired.
  private volatile Supplier<Map<String, String>> commitMetadataSupplier = Map::of;

  // Tempdoc 516 Slice 4c: GPU lifecycle state (lastMainGpuActiveState, embeddingLifecycleLock,
  // primary + additional change-listener slots) all moved into EmbeddingProviderLifecycle.
  // 309 §33 multi-listener semantics preserved by the lifecycle's CopyOnWriteArrayList +
  // single primary slot.
  private final EmbeddingProviderLifecycle embeddingLifecycle;

  /**
   * Loop state, exposed for system test observability and the worker-state wire emitter
   * ({@code WorkerHealthService.workerStateSupplier} via
   * {@code DefaultWorkerAppServices.indexingLoopState()}). Wire-string identity is
   * preserved through {@link Enum#name()} — every consumer that pinned the literal
   * {@code "IDLE"}/{@code "RUNNING"}/{@code "PAUSED"} (notably {@code EngineForegroundPacingTest} and
   * {@code KnowledgeServer}'s queue-depth gauge at L1127) keeps working unchanged.
   *
   * <p>Tempdoc 516 P2: previously a stringly-typed FSM (the {@code STATE_*} String
   * constants this enum replaced) made the state graph invisible to {@code javac}; per
   * Appendix A.2 + the {@code audit-without-test} reference case, the typed FSM removes
   * the conditions that let tempdoc 403 Tier C's static audit miss a real restart blocker.
   */
  public enum LoopState {
    IDLE,
    RUNNING,
    PAUSED,
    FAILED
  }

  private volatile LoopState currentState = LoopState.IDLE;
  private Thread loopThread;
  private long indexedSinceCommit = 0;
  private long lastCommitTime = System.currentTimeMillis();
  // Tempdoc 516 Slice 4a.1: pendingMarkDone moved into IngestionOutcomeJournal, encapsulated.
  private final IngestionOutcomeJournal journal;
  // Tempdoc 516 Slice 4d (W6): SPLADE retry-backoff state + disambiguation latch moved to
  // BackfillScheduler.

  // Batch statistics for summary logging. Tempdoc 516 Slice 3: now encapsulated in BatchStats
  // so the upcoming Slice 4a Extractor + Writer extractions can mutate the counters through a
  // typed API instead of writing to per-IndexingLoop fields across the seam.
  private final BatchStats batchStats = new BatchStats();

  // Tempdoc 516 Slice 3: shared helper for the stale-source delete path. Stateless except for
  // the IndexingCoordinator handle, lazily constructed because the coordinator is final.
  private final StaleSourceHandler staleSourceHandler;
  // Tempdoc 516 Slice 4a.2 (W5.1): stale-snapshot resolver + JobBatchWriter.
  // Slice 4a.3 (W5.2) adds the JobBatchExtractor; it owns the per-batch indexEmptyForBatch
  // cache and reads/clears the shared forcedPaths set.
  private final StaleSnapshotResolver staleResolver;
  private final JobBatchWriter writer;
  private final JobBatchExtractor extractor;
  // Slice 4d (W6) — owns idle-branch backfill orchestration + interleaved-SPLADE timing.
  private final BackfillScheduler backfillScheduler;

  // Force reindex tracking - paths that should bypass "unchanged" check.
  // Unbounded by construction: entries are removed only when the extractor reaches that exact
  // path (JobBatchExtractor#extractJob) or on a profiling reset. A forced path the queue never
  // delivers — dropped enqueue, deleted file, cancelled scan — is retained for the process
  // lifetime. Tempdoc 821 §3-C3 made whole-root force-reindex scans a routine producer here
  // (previously only explicit submitBatch calls were), so the residue now scales with root size.
  private final Set<String> forcedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet();

  // Tempdoc 798: memoization for the pending-ingest probe (see PENDING_INGEST_PROBE_TTL_MS).
  private volatile long pendingIngestProbedAtMs = 0L;
  private volatile boolean pendingIngestCached = false;

  /**
   * Creates a new IndexingLoop with default content extractor and no embedding or telemetry.
   *
   * @param jobQueue The job queue to poll for pending work
   * @param indexingCoordinator The coordinator for index write operations
   * @param commitOps The commit operations
   * @param documentFieldOps The document field operations for isUnmodified checks
   * @param indexCountOps The index count operations
   * @param resolvedConfigSupplier Supplier for the resolved configuration
   * @param signalBus The signal bus for coordination
   */
  public IndexingLoop(
      io.justsearch.core.execution.EngineExecutorRegistry.Registration ocrRegistration,
      io.justsearch.core.execution.EngineExecutorRegistry.Registration timeboxRegistration,
      JobQueue jobQueue,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      DocumentFieldOps documentFieldOps,
      IndexCountOps indexCountOps,
      Supplier<ResolvedConfig> resolvedConfigSupplier,
      WorkerSignalBus signalBus) {
    this(ocrRegistration, timeboxRegistration, jobQueue, indexingCoordinator, commitOps, documentFieldOps, indexCountOps,
        resolvedConfigSupplier, signalBus,
        IndexingPacing.unthrottled(),
        null, null, null, null, null,
        new io.justsearch.indexerworker.server.EncoderBindings(), null);
  }

  /**
   * Creates a new IndexingLoop with optional embedding service.
   *
   * @param jobQueue The job queue to poll for pending work
   * @param indexingCoordinator The coordinator for index write operations
   * @param commitOps The commit operations
   * @param documentFieldOps The document field operations for isUnmodified checks
   * @param indexCountOps The index count operations
   * @param resolvedConfigSupplier Supplier for the resolved configuration
   * @param signalBus The signal bus for coordination
   * @param embeddingService The embedding service for vector generation (may be null)
   */
  public IndexingLoop(
      io.justsearch.core.execution.EngineExecutorRegistry.Registration ocrRegistration,
      io.justsearch.core.execution.EngineExecutorRegistry.Registration timeboxRegistration,
      JobQueue jobQueue,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      DocumentFieldOps documentFieldOps,
      IndexCountOps indexCountOps,
      Supplier<ResolvedConfig> resolvedConfigSupplier,
      WorkerSignalBus signalBus,
      EmbeddingService embeddingService) {
    this(ocrRegistration, timeboxRegistration, jobQueue, indexingCoordinator, commitOps, documentFieldOps, indexCountOps,
        resolvedConfigSupplier, signalBus,
        IndexingPacing.unthrottled(),
        embeddingService, null, null, null, null,
        new io.justsearch.indexerworker.server.EncoderBindings(), null);
  }

  /**
   * Creates a new IndexingLoop with optional embedding service and pipeline catalog.
   *
   * <p>If a pipeline catalog is provided, the loop records stage timing metrics via the catalog's
   * typed histogram.
   */
  public IndexingLoop(
      io.justsearch.core.execution.EngineExecutorRegistry.Registration ocrRegistration,
      io.justsearch.core.execution.EngineExecutorRegistry.Registration timeboxRegistration,
      JobQueue jobQueue,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      DocumentFieldOps documentFieldOps,
      IndexCountOps indexCountOps,
      Supplier<ResolvedConfig> resolvedConfigSupplier,
      WorkerSignalBus signalBus,
      EmbeddingService embeddingService,
      IndexingPipelineMetricCatalog pipelineCatalog,
      ExtractionMetricCatalog extractionCatalog) {
    this(ocrRegistration, timeboxRegistration, jobQueue, indexingCoordinator, commitOps, documentFieldOps, indexCountOps,
        resolvedConfigSupplier, signalBus,
        IndexingPacing.unthrottled(),
        embeddingService, pipelineCatalog, extractionCatalog,
        null, null, new io.justsearch.indexerworker.server.EncoderBindings(), null);
  }

  /**
   * Creates a new IndexingLoop with all dependencies explicitly provided.
   *
   * @param jobQueue The job queue to poll for pending work
   * @param indexingCoordinator The coordinator for index write operations
   * @param commitOps The commit operations
   * @param documentFieldOps The document field operations for isUnmodified checks
   * @param indexCountOps The index count operations
   * @param resolvedConfigSupplier Supplier for the resolved configuration
   * @param signalBus The signal bus for coordination
   * @param indexingPacing The foreground-contention duty cycle (tempdoc 885 item 3); required —
   *     {@code IndexingPacing.unthrottled()} is the explicit "no throttling" value, so a missing
   *     policy is a construction error rather than a silently un-paced loop
   * @param embeddingService The embedding service for vector generation (may be null)
   * @param pipelineCatalog Catalog for {@code pipeline.*} metrics (may be null)
   * @param extractionCatalog Catalog passed to a default {@link TimeboxedContentExtractor}
   *     when {@code contentExtractor} is null (may be null)
   * @param contentExtractor The timeboxed content extractor (if null, a default Tika-based
   *     extractor is created)
   */
  public IndexingLoop(
      io.justsearch.core.execution.EngineExecutorRegistry.Registration ocrRegistration,
      io.justsearch.core.execution.EngineExecutorRegistry.Registration timeboxRegistration,
      JobQueue jobQueue,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      DocumentFieldOps documentFieldOps,
      IndexCountOps indexCountOps,
      Supplier<ResolvedConfig> resolvedConfigSupplier,
      WorkerSignalBus signalBus,
      IndexingPacing indexingPacing,
      EmbeddingService embeddingService,
      IndexingPipelineMetricCatalog pipelineCatalog,
      ExtractionMetricCatalog extractionCatalog,
      IngestionOutcomeMetricCatalog ingestionOutcomeCatalog,
      TimeboxedContentExtractor contentExtractor,
      io.justsearch.indexerworker.server.EncoderBindings encoderBindings,
      IndexingLoopOptions options) {
    this.jobQueue = jobQueue;
    this.commitOps = commitOps;
    this.resolvedConfigSupplier = resolvedConfigSupplier;
    this.signalBus = signalBus;
    this.indexingPacing = java.util.Objects.requireNonNull(indexingPacing, "indexingPacing");
    this.encoderBindings =
        encoderBindings != null
            ? encoderBindings
            : new io.justsearch.indexerworker.server.EncoderBindings();
    IndexingLoopOptions opts = options != null ? options : IndexingLoopOptions.withDefaults();
    this.detailedTracing = opts.detailedTracing();
    this.pathResolutionStore = opts.pathResolutionStore();
    this.documentIdentityStore = opts.documentIdentityStore();
    this.migrationActiveSupplier = opts.migrationActiveSupplier();
    this.commitMetadataSupplier = opts.commitMetadataSupplier();
    if (opts.detailedTracing()) {
      log.info("Indexing pipeline tracing: enabled");
    }
    this.contentExtractor = contentExtractor != null
        ? contentExtractor
        : ExtractionSandboxFactory.inProcessStructured(ocrRegistration, timeboxRegistration, extractionCatalog);
    this.pipelineCatalog = pipelineCatalog;
    this.outcomeWriteFailureCounter =
        ingestionOutcomeCatalog == null
            ? IngestionOutcomeMetricCatalog.noop().outcomeWriteFailuresTotal
            : ingestionOutcomeCatalog.outcomeWriteFailuresTotal;
    this.staleSourceHandler = new StaleSourceHandler(indexingCoordinator, this.documentIdentityStore);
    this.journal =
        new IngestionOutcomeJournal(
            jobQueue, metrics, outcomeWriteFailureCounter, () -> detailedTracing);
    this.embeddingLifecycle =
        new EmbeddingProviderLifecycle(signalBus, jobQueue, indexCountOps, commitOps);
    if (embeddingService != null) {
      embeddingLifecycle.setEmbeddingProvider(embeddingService);
    }
    // 516 P3 final: telemetry events sink flows in via IndexingLoopOptions at ctor time.
    // Replaces the prior post-ctor setEmbeddingTelemetryEvents shim.
    if (opts.embeddingTelemetryEvents() != null) {
      embeddingLifecycle.setEmbeddingTelemetryEvents(opts.embeddingTelemetryEvents());
    }
    // Tempdoc 516 Slice 4c (Appendix A.6 #12): register the lifecycle as the single
    // post-commit listener. Replaces the three previously-inline refreshEccStoredFingerprint
    // calls at the idle / time-buffer / shutdown commit sites with one subscription.
    // Guard for the test path where StubIndexingLoop passes null commitOps.
    if (commitOps != null) {
      commitOps.setCommitCompletedListener(embeddingLifecycle::refreshStoredFingerprintAfterCommit);
    }
    // Tempdoc 516 Slice 4a.2 (W5.1): construct the stale resolver + writer. The indexedDelta
    // callback feeds the cross-seam indexedSinceCommit counter on the loop residue.
    this.staleResolver =
        new StaleSnapshotResolver(
            ingestionAuthority,
            staleSourceHandler,
            journal,
            jobQueue,
            this.contentExtractor,
            (long delta) -> indexedSinceCommit += delta);
    this.writer =
        new JobBatchWriter(
            indexingCoordinator,
            documentFieldOps,
            signalBus,
            embeddingLifecycle,
            this.encoderBindings::spladeEncoder,
            journal,
            jobQueue,
            this.contentExtractor,
            metrics,
            batchStats,
            staleResolver,
            (long delta) -> indexedSinceCommit += delta,
            this::recordStageMs,
            () -> detailedTracing,
            this::chunkSpladeEnabled);
    // Tempdoc 516 Slice 4a.3 (W5.2): construct the extractor. Holds its own per-batch
    // indexEmptyForBatch cache, the forcedPaths set (shared with the markForced public API),
    // and the running/signalBus pair so it can self-decide when to stop the per-job loop.
    this.extractor =
        new JobBatchExtractor(
            ingestionAuthority,
            journal,
            jobQueue,
            this.contentExtractor,
            documentFieldOps,
            indexCountOps,
            batchStats,
            staleResolver,
            staleSourceHandler,
            this.indexingPacing,
            running,
            forcedPaths,
            () -> pathResolutionStore,
            () -> documentIdentityStore,
            this::recordStageMs,
            () -> detailedTracing,
            (long delta) -> indexedSinceCommit += delta);
    // Tempdoc 516 Slice 4d (W6): construct the scheduler. Encoder/service suppliers read
    // IndexingLoop's volatile fields so the async-load swap-on-the-fly semantics persist.
    this.backfillScheduler =
        new BackfillScheduler(
            documentFieldOps,
            indexingCoordinator,
            indexCountOps,
            commitOps,
            signalBus,
            this.indexingPacing,
            embeddingLifecycle,
            running,
            resolvedConfigSupplier,
            this.encoderBindings::spladeEncoder,
            this.encoderBindings::bgeM3Encoder,
            this.encoderBindings::nerService,
            this.encoderBindings::disambiguationService);
  }

  /**
   * Resolves the current enrichment-backfill pacing snapshot (tempdoc 710 Wave-1.5 Move 4).
   * Falls back to {@link ResolvedConfig.Ai.BackfillPacing#DEFAULTS} — byte-identical to the
   * pre-Move-4 hardcoded literals — when no config is available, e.g. a test double supplying
   * {@code () -> null} for {@code resolvedConfigSupplier}.
   */
  private ResolvedConfig.Ai.BackfillPacing pacing() {
    ResolvedConfig config = resolvedConfigSupplier.get();
    return config != null ? config.ai().backfillPacing() : ResolvedConfig.Ai.BackfillPacing.DEFAULTS;
  }

  /**
   * {@code rag.chunk_splade.enabled} (tempdoc 931 §E item 8) — read from the live resolved config on
   * every chunk write, matching {@code BackfillScheduler#chunkSpladeEnabled}. Absent config reads as
   * the flag's own default (false), never as "on".
   */
  private boolean chunkSpladeEnabled() {
    ResolvedConfig config = resolvedConfigSupplier.get();
    return config != null && config.rag() != null && config.rag().chunkSpladeEnabled();
  }

  /** Returns a real span when tracing is enabled, or a no-op singleton when disabled. */
  private Span maybeSpan(String name) {
    if (!detailedTracing) return Span.getInvalid();
    return tracer.spanBuilder(name).startSpan();
  }

  private void recordStageMs(String stageId, long durationMs, String reasonCode) {
    if (pipelineCatalog == null) return;
    if (stageId == null || stageId.isBlank()) return;
    long ms = Math.max(0, durationMs);
    pipelineCatalog.stageMs.record(ms, PipelineStageTags.of(stageId, reasonCode));
  }

  // Tempdoc 516 P3 / Slice 5 (W7.2 followup): 11 of 13 IndexingLoop setters are gone.
  //
  // - The 4 async-load encoders (Splade/BgeM3/Ner/Disambiguation) moved to EncoderBindings.
  //   Callers bind on the shared registry with encoderBindings.bindX(...).
  // - The 3 startup-config setters (detailedTracing / pathResolutionStore /
  //   commitMetadataSupplier) became IndexingLoopOptions record fields passed at ctor time.
  // - The 4 EmbeddingProviderLifecycle delegates (setEmbeddingProvider,
  //   setEmbeddingProviderChangeListener, addEmbeddingProviderChangeListener,
  //   setEmbeddingCompatController) are dropped — callers reach the lifecycle directly
  //   via getEmbeddingLifecycle() (now public, used by DWAS).
  //
  // 516 P3 FINAL CUT (W7.2 post-followup): the last 2 setters are gone.
  //
  // setMigrationActiveSupplier — the lambda was a `() -> KS.this.X != null && ...`. It only
  // needs KS.this to exist (which is true the moment KS allocation begins, before any KS
  // ctor body runs). It reads its captured fields at CALL time, not creation time. So KS
  // can create the lambda before invoking `new DefaultWorkerAppServices(...)` and pass it
  // through IndexingLoopOptions. Done.
  //
  // setEmbeddingTelemetryEvents — KS sets `this.embeddingTelemetry` at KS:331, well before
  // the DWAS ctor call at KS:594. So it can simply flow through IndexingLoopOptions. Done.
  //
  // The prior commit's "truly post-ctor" framing was an honest mistake — neither value
  // actually required a post-ctor setter, just slightly earlier wiring on the KS side.

  // Tempdoc 516 Slice 4c: allowEmbeddingWrites, maybeFinalizeEmbeddingRebuildIfNeeded,
  // refreshEccStoredFingerprint moved to EmbeddingProviderLifecycle. Callers go through
  // `embeddingLifecycle.*`. The 3 inline refreshEccStoredFingerprint call sites in runLoop
  // collapse into a single CommitCompletedListener subscription registered in the ctor.

  /**
   * Wraps {@link EmbeddingProviderLifecycle#tryFinalizeRebuild()} with the commit-driver
   * counter reset the lifecycle deliberately cannot do (those fields belong to the loop
   * residue). When the lifecycle issued the rebuild-stamp commit, reset
   * {@code lastCommitTime} + {@code indexedSinceCommit} and record the commit metric.
   */
  private void tryFinalizeEmbeddingRebuild() {
    if (embeddingLifecycle.tryFinalizeRebuild()) {
      metrics.recordCommit();
      lastCommitTime = System.currentTimeMillis();
      indexedSinceCommit = 0;
    }
  }

  /**
   * Shutdown-path variant of {@link #tryFinalizeEmbeddingRebuild()}, using the lifecycle's one-shot
   * finalize: the shutdown block gets a single call, so the loop-path's two-consecutive-reads
   * debounce would decline a genuinely-complete rebuild and drop the stamp. See {@link
   * EmbeddingProviderLifecycle#tryFinalizeRebuildAtShutdown()}.
   */
  private void tryFinalizeEmbeddingRebuildAtShutdown() {
    if (embeddingLifecycle.tryFinalizeRebuildAtShutdown()) {
      metrics.recordCommit();
      lastCommitTime = System.currentTimeMillis();
      indexedSinceCommit = 0;
    }
  }

  /**
   * Tempdoc 730 A3: wraps {@link EmbeddingProviderLifecycle#tryFinalizeFreshCompatibleStamp()}
   * with the same commit-driver counter reset {@link #tryFinalizeEmbeddingRebuild()} performs.
   * Belt-and-suspenders alongside A1 (the unconditional stamp supplier) — closes the window
   * where the fresh-index -> COMPATIBLE path's finalizing commit landed before the embedding
   * model was producing a fingerprint.
   */
  private void tryFinalizeFreshCompatibleEmbeddingStamp() {
    if (embeddingLifecycle.tryFinalizeFreshCompatibleStamp()) {
      metrics.recordCommit();
      lastCommitTime = System.currentTimeMillis();
      indexedSinceCommit = 0;
    }
  }

  // W2.1: dropped private wrappers `embeddingProvider()` and `allowEmbeddingWrites()`;
  // callers go through `embeddingLifecycle.X()` directly. Removes one method-dispatch
  // hop + visual indirection.

  public long getLastCommitTime() {
    return lastCommitTime;
  }

  /**
   * Starts the background indexing loop.
   */
  public void start() {
    if (running.compareAndSet(false, true)) {
      currentState = LoopState.IDLE;
      // Tempdoc 798: publish the third backfill-yield signal. This loop owns the job queue, so it
      // is the only component that can answer "is primary indexing work waiting"; BackfillScheduler
      // reads it through the signal bus alongside shouldYieldGpuBackfill().
      signalBus.setPendingIngestProbe(this::hasPendingIngestWork);
      loopThread = new Thread(this::runLoop, "indexing-loop");
      loopThread.setDaemon(true);
      // Tempdoc 588 F-1 defense-in-depth: if the loop thread ever dies uncaught, flip `running`
      // so isRunning() (which ANDs loopThread.isAlive()) doesn't keep advertising a dead loop.
      loopThread.setUncaughtExceptionHandler(
          (thread, ex) -> {
            // Publish before logging: a heap failure can prevent the logger from allocating.
            currentState = LoopState.FAILED;
            running.set(false);
            log.error("Indexing loop thread '{}' died uncaught — indexing has halted", thread.getName(), ex);
          });
      loopThread.start();
      log.info("IndexingLoop started");
    }
  }

  /**
   * Answers "is primary ingest work waiting in the queue right now" for {@code
   * WorkerSignalBus.hasPendingIngest()} (tempdoc 798), memoized for {@link
   * #PENDING_INGEST_PROBE_TTL_MS}.
   *
   * <p>Two tiers, cheapest first. {@link JobQueue#queueDepth()} is a {@code COUNT(*) WHERE state
   * IN ('PENDING','PROCESSING')} served by {@code idx_jobs_state}, so the steady state — an empty
   * ingest lane while backfill drains — costs one indexed count. Only when that is non-zero is
   * {@link JobQueue#jobStateCounts()} consulted, which is the precise answer (PENDING-ready only:
   * a job still in retry backoff cannot be claimed, so yielding backfill for it would buy
   * nothing) but a full scan of {@code jobs} including DONE/FAILED rows.
   */
  private boolean hasPendingIngestWork() {
    long now = System.currentTimeMillis();
    if (now - pendingIngestProbedAtMs < PENDING_INGEST_PROBE_TTL_MS) {
      return pendingIngestCached;
    }
    boolean pending =
        jobQueue.queueDepth() > 0 && jobQueue.jobStateCounts().pendingReadyCount() > 0;
    pendingIngestCached = pending;
    pendingIngestProbedAtMs = now;
    return pending;
  }

  /**
   * Marks paths for force reindex - bypasses the "file unchanged" optimization.
   *
   * <p>Use this when schema changes require re-indexing existing documents,
   * even if file content hasn't changed.
   *
   * @param normalizedPaths paths to force reindex (must be normalized via PathNormalizer)
   */
  public void markForced(java.util.Collection<String> normalizedPaths) {
    if (normalizedPaths != null && !normalizedPaths.isEmpty()) {
      forcedPaths.addAll(normalizedPaths);
      log.debug("Marked {} paths for force reindex", normalizedPaths.size());
    }
  }

  private void runLoop() {
    log.info("Indexing loop running");

    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        // CRITICAL: Handle GPU state transitions for Hybrid Inference
        // Must unload embedding model when Main claims GPU, reload when released
        embeddingLifecycle.handleGpuStateTransition();

        // Poll for pending jobs
        List<JobQueue.IndexJob> jobs = jobQueue.pollPending(pacing().pollBatchSize());

        if (jobs.isEmpty()) {
          // No work to do - log batch summary if we just finished one
          if (currentState == LoopState.RUNNING && batchStats.hasWork()) {
            long elapsed = batchStats.elapsedMillis(System.currentTimeMillis());
            log.info("Indexing batch complete: {} indexed, {} skipped, {} failed, elapsed={}ms",
                batchStats.indexed(), batchStats.skipped(), batchStats.failed(), elapsed);
            batchStats.reset();
          }

          // IMPORTANT: Commit when we transition to idle with uncommitted changes.
          //
          // The time-based commit strategy below runs only after processing jobs. If the queue becomes empty
          // and we go idle, we would otherwise never reach the commit check again, leaving the index
          // uncommitted (no segments_*), which makes the main process report indexAvailable=false.
          if (indexedSinceCommit > 0) {
            try {
              commitOps.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
              metrics.recordCommit();
              log.debug("Committed index: {} docs, reason=batch idle", indexedSinceCommit);
              indexedSinceCommit = 0;
              lastCommitTime = System.currentTimeMillis();
              journal.drainPending();
            } catch (RuntimeException e) {
              log.error("Failed to commit index on idle", e);
            }
          }

          // Tempdoc 516 Slice 4d (W6): BackfillScheduler owns the per-cycle backfill
          // orchestration (combined enrichment tight loop + per-stage fallback + disambiguation
          // gating). Sleep duration below picks active vs. truly-idle based on the return.
          boolean backfillDidWork = backfillScheduler.runIdleCycle();

          // If a forced reindex is in progress, check whether rebuild has completed.
          tryFinalizeEmbeddingRebuild();
          // Tempdoc 730 A3: fresh-index -> COMPATIBLE path has no REBUILDING to complete; check
          // whether it still needs its stamp-persisting commit.
          tryFinalizeFreshCompatibleEmbeddingStamp();

          boolean wasRunning = currentState == LoopState.RUNNING;
          transitionToIdle();
          // Use shorter sleep when backfill is active to maintain throughput.
          // Full 1000ms sleep only when truly idle (no backfill work this iteration).
          Thread.sleep(
              (wasRunning || backfillDidWork)
                  ? LoopPacingPolicy.activeIdleSleepMs()
                  : LoopPacingPolicy.idleSleepMs());
          continue;
        }

        // Start new batch if transitioning from idle
        if (currentState != LoopState.RUNNING) {
          batchStats.start(System.currentTimeMillis());
        }
        transitionToRunning();

        // Batch processing: extract all → batch embed → build+write
        processBatch(jobs);

        // Time-based commit strategy (every 10s or when buffer is full)
        long now = System.currentTimeMillis();
        long timeSinceCommit = now - lastCommitTime;
        ResolvedConfig.Ai.BackfillPacing pacing = pacing();
        boolean timeTriggered =
            LoopPacingPolicy.isTimeCommitTriggered(
                timeSinceCommit, indexedSinceCommit, pacing.commitIntervalMs());
        boolean bufferTriggered =
            LoopPacingPolicy.isBufferCommitTriggered(
                indexedSinceCommit, pacing.maxDocsBeforeCommit());

        if (timeTriggered || bufferTriggered) {
          try {
            long commitStart = System.currentTimeMillis();
            CommitReason reason =
                bufferTriggered ? CommitReason.INDEXING_LOOP_BUFFER : CommitReason.INDEXING_LOOP_TIME;
            commitOps.commitAndTrack(reason);
            metrics.recordCommit();
            log.debug(
                "Committed index: {} docs, reason={}, elapsed={}ms",
                indexedSinceCommit,
                reason.wireValue(),
                timeSinceCommit);
            recordStageMs("post_commit", System.currentTimeMillis() - commitStart, bufferTriggered ? "buffer_full" : "time_interval");
            indexedSinceCommit = 0;
            lastCommitTime = now;
            journal.drainPending();
          } catch (RuntimeException e) {
            log.error("Failed to commit index", e);
          }
        }

        // Tempdoc 516 Slice 4d (W6): time-gated interleaved SPLADE/BGE-M3 backfill is owned
        // by the scheduler (tempdoc 278 item 4a). Limits primary-indexing overhead (~13%).
        backfillScheduler.runInterleavedSplade(now);

        // If a forced reindex is in progress, check whether rebuild has completed.
        tryFinalizeEmbeddingRebuild();
        // Tempdoc 730 A3: fresh-index -> COMPATIBLE path has no REBUILDING to complete; check
        // whether it still needs its stamp-persisting commit.
        tryFinalizeFreshCompatibleEmbeddingStamp();

        // Tempdoc 885 item 3: the duty cycle. While foreground search-family RPCs are in flight,
        // yield most of the interval that follows this batch — but never all of it. PAUSED is
        // reported only for the duration of the yield, so `worker.indexing.paused` and the health
        // RPC's worker_state stay honest about a loop that is throttled, not stopped.
        if (indexingPacing.foregroundBusy()) {
          transitionToPaused();
        }
        indexingPacing.pace();

      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        log.error("Unexpected error in indexing loop", e);
        if (!sleepBrieflyAfterError()) {
          break; // interrupted during back-off
        }
      } catch (VirtualMachineError e) {
        // Tempdoc 588 F-1: OOM / StackOverflow / InternalError — the JVM is unsafe to continue, so
        // stop, but observably: flip `running` (so isRunning() is honest) and re-throw (the uncaught
        // handler is the backstop). A fatal loop death must be visible, not silent.
        currentState = LoopState.FAILED;
        running.set(false);
        log.error("Fatal VM error in indexing loop — stopping the loop", e);
        throw e;
      } catch (Throwable t) {
        // Tempdoc 588 F-1: a non-VM Error escaped one batch (e.g. a plugin LinkageError, an
        // AssertionError, an IOError on a document). The only handler here used to be `catch
        // (Exception)`, so `Error` propagated out of runLoop and the loop thread died *silently*
        // while loopState() kept reporting RUNNING. Recover like Exception — log and continue to the
        // next batch — so one bad document can't permanently halt indexing. (Surfaces the failure;
        // it is not the "broaden a catch to silence a failure" anti-pattern.)
        log.error("Unexpected Error in indexing loop (recovered, continuing)", t);
        if (!sleepBrieflyAfterError()) {
          break; // interrupted during back-off
        }
      }
    }

    finalizeShutdownCommit();

    log.info("Indexing loop stopped");
  }

  /**
   * Tempdoc 730 review item 2 (the "no subsequent commit" ratchet hole): a rebuild-completion or
   * fresh-compatible fingerprint stamp that becomes due right as the loop is stopping previously
   * had exactly one retry path — the next idle/batch iteration's {@link
   * #tryFinalizeEmbeddingRebuild()} / {@link #tryFinalizeFreshCompatibleEmbeddingStamp()} calls.
   * If the loop never reaches that next iteration (a plain worker restart stops the loop right
   * after completion), the old shutdown commit below — gated on {@code indexedSinceCommit > 0} —
   * skipped entirely, so the fingerprint was never persisted; the next boot's {@code refresh()}
   * then saw {@code storedFp == null} with {@code docCount > 0} and re-flagged BLOCKED_LEGACY,
   * even though the rebuild had genuinely completed in memory. Calling the two finalizers here,
   * unconditionally, closes that window: both self-gate on ECC state (REBUILDING-completed /
   * COMPATIBLE-with-docs-and-no-stored-fp respectively) and issue their own commit independent of
   * {@code indexedSinceCommit}, so invoking them is a no-op when there is nothing to stamp and the
   * guaranteed persisting commit otherwise when there is.
   *
   * <p>The rebuild half calls the lifecycle's ONE-SHOT finalize rather than the loop-path one: the
   * loop path requires two consecutive {@code pending == 0} reads (tempdoc 726 F1's mid-flush-race
   * guard), which this block — having exactly one call — could never satisfy on a worker that stops
   * right after rebuild completion. That is the precise window this method exists to close, so the
   * debounce is bypassed here on the grounds that a stopped loop writes no further documents. See
   * {@link EmbeddingProviderLifecycle#tryFinalizeRebuildAtShutdown()}.
   */
  private void finalizeShutdownCommit() {
    tryFinalizeEmbeddingRebuildAtShutdown();
    tryFinalizeFreshCompatibleEmbeddingStamp();

    try {
      if (indexedSinceCommit > 0) {
        long commitStart = System.currentTimeMillis();
        commitOps.commitAndTrack(CommitReason.INDEXING_LOOP_SHUTDOWN);
        metrics.recordCommit();
        log.info("Final commit: {} documents", indexedSinceCommit);
        recordStageMs("post_commit", System.currentTimeMillis() - commitStart, "shutdown");
        journal.drainPending();
      }
    } catch (RuntimeException e) {
      log.error("Failed final commit", e);
    }
  }

  /**
   * Back-off after a recovered error to avoid a tight failure loop (tempdoc 588; shared by the
   * Exception and Error recovery paths). Returns {@code false} if interrupted (caller should break).
   */
  private boolean sleepBrieflyAfterError() {
    try {
      Thread.sleep(ERROR_BACKOFF_MS);
      return true;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Processes a batch of jobs using the extract-all → batch-embed → build+write pattern.
   * Falls back to per-doc embedding when batch embedding is unavailable.
   */
  /**
   * Liveness heartbeat cadence (tempdoc 575 §4.3b / 550 Thesis II): refresh the PROCESSING rows'
   * last_updated at least this often during a long batch. The age-bounded reaper's tight window then
   * distinguishes a live owner (still heartbeating) from a genuine orphan (loop died → beats stopped),
   * instead of the coarse "claimed long ago" guess a no-heartbeat queue forces.
   */
  // Generated from the register (tempdoc 575 §17 Face A) — single source, cannot drift.
  private static final long HEARTBEAT_INTERVAL_MS = LivenessWindows.HEARTBEAT_INTERVAL_MS;

  private long lastHeartbeatMs = 0L;

  /** Beat now: signal this loop still owns its PROCESSING rows (best-effort, never throws). */
  private void heartbeat() {
    jobQueue.heartbeatProcessing();
    lastHeartbeatMs = System.currentTimeMillis();
  }

  /** Beat only if the cadence has elapsed — time-gated for the per-doc write loop's hot path. */
  private void heartbeatIfDue() {
    if (System.currentTimeMillis() - lastHeartbeatMs > HEARTBEAT_INTERVAL_MS) {
      heartbeat();
    }
  }

  private void processBatch(List<JobQueue.IndexJob> jobs) {
    Span batchSpan = maybeSpan("indexing.batch");
    batchSpan.setAttribute("batch.polled", (long) jobs.size());
    // Tempdoc 400 LR2-d.2: attach commit.* identity attrs best-effort.
    try {
      io.justsearch.indexerworker.services.CommitMetadataSpanAttrs.applyTo(
          batchSpan, commitMetadataSupplier.get());
    } catch (RuntimeException e) {
      log.debug("commit metadata supplier failed (best-effort)", e);
    }
    try (Scope ignored = batchSpan.makeCurrent()) {
      processBatchInner(jobs, batchSpan);
    } finally {
      batchSpan.end();
    }
  }

  private void processBatchInner(List<JobQueue.IndexJob> jobs, Span batchSpan) {
    // Phase 1: Extract all — pre-checks and Tika extraction per job.
    // Slice 4a.3 (W5.2): JobBatchExtractor owns the per-job loop, the indexEmptyForBatch
    // cache, and the per-outcome ledger/batchStats bookkeeping.
    List<ExtractedJob> extracted = extractor.extractAll(jobs);

    if (extracted.isEmpty()) {
      batchSpan.setAttribute("batch.extracted", 0L);
      return;
    }
    batchSpan.setAttribute("batch.extracted", (long) extracted.size());
    // Liveness beat: extraction (Phase 1) can be long for large/many files — signal we still own these.
    heartbeat();

    // Phase 2: Batch embed all extracted texts.
    // Embedding is deferred to backfill during normal primary indexing (312 Phase 1, 136 docs/sec).
    // Exception: during blue-green migration, inline embedding is enabled (312 Phase 4) because
    // Blue serves search and Green should optimize for total time including vectors (~8.6 docs/sec
    // inline > 7 docs/sec RMW backfill). precomputedEmbedding in buildDocument() takes priority
    // over allowEmbeddingWrites, so batch-computed vectors are written regardless.
    EmbeddingProvider provider = embeddingLifecycle.embeddingProvider();
    boolean canBatchEmbed =
        migrationActiveSupplier.getAsBoolean() && provider.isAvailable();
    float[][] embeddings = null;

    if (canBatchEmbed) {
      Span embedSpan = maybeSpan("indexing.embed_batch");
      embedSpan.setAttribute("embed.batch_size", (long) extracted.size());
      embedSpan.setAttribute("embed.gpu", provider.isUsingGpu());
      try {
        List<String> texts = new ArrayList<>(extracted.size());
        for (ExtractedJob ex : extracted) {
          texts.add(ex.artifact().result().content());
        }
        long embedStart = System.currentTimeMillis();
        List<float[]> vectors = provider.embedDocumentBatch(texts);
        long embedMs = System.currentTimeMillis() - embedStart;
        recordStageMs("analyze_batch", embedMs, "batch_size=" + extracted.size());

        if (vectors != null && vectors.size() == extracted.size()) {
          embeddings = vectors.toArray(new float[0][]);
          embedSpan.setAttribute("embed.success", true);
        } else {
          embedSpan.setAttribute("embed.success", false);
        }
      } catch (RuntimeException e) {
        log.debug("Batch embedding failed, falling back to per-doc: {}", e.getMessage());
        embedSpan.setAttribute("embed.success", false);
        embedSpan.setAttribute("embed.error", e.getMessage());
      } finally {
        embedSpan.end();
      }
    }

    // Liveness beat: batch embedding (Phase 2) can be long on GPU — signal we still own these.
    heartbeat();

    // Phase 3: Build and write all docs
    for (int i = 0; i < extracted.size(); i++) {
      if (!running.get()) break;

      ExtractedJob ex = extracted.get(i);
      float[] precomputed = embeddings != null ? embeddings[i] : null;
      writer.write(ex, precomputed);
      // Time-gated liveness beat: a very large batch's write phase stays fresh without per-doc DB churn.
      heartbeatIfDue();
    }

    // Update queue depth gauge once per batch instead of per doc (312 item 9).
    metrics.setQueueDepth(jobQueue.queueDepth());
  }

  // Tempdoc 516 Slice 4a.3 (W5.2): extractJob (per-job admission + extraction + stale recheck +
  // outcome bookkeeping) moved to JobBatchExtractor. The bestEffortDeleteMissingSource helper
  // moved with it. Both ledgerEntry overloads moved (no remaining IndexingLoop callers).

  /**
   * Test-only accessor for the journal. Package-private — same-package tests
   * ({@code indexerworker.loop}) call directly; cross-package tests
   * ({@code indexerworker.extract}) use reflection with {@code setAccessible(true)}.
   */
  IngestionOutcomeJournal getJournal() {
    return journal;
  }

  /**
   * Accessor for the embedding lifecycle. Production composition
   * ({@link DefaultWorkerAppServices}) uses it to wire async-loaded encoders +
   * change-listeners post-ctor; tests use it directly. W7.2 followup: promoted from
   * package-private (test-only) to public after the 4 lifecycle-delegate setters were
   * removed from IndexingLoop's surface.
   */
  public EmbeddingProviderLifecycle getEmbeddingLifecycle() {
    return embeddingLifecycle;
  }

  /** Test-only accessor for the writer extracted in W5.1. */
  JobBatchWriter getWriter() {
    return writer;
  }

  /** Test-only accessor for the extractor extracted in W5.2. */
  JobBatchExtractor getExtractor() {
    return extractor;
  }

  // Tempdoc 516 Slice 4d (W6): recordSpladeBackfillResult moved to BackfillScheduler.

  // Tempdoc 516 Slice 4a.2 (W5.1): indexChunks delegate inlined into JobBatchWriter (single
  // caller).

  /**
   * Checks if the indexing loop is running.
   *
   * @return true if the loop is running
   */
  public boolean isRunning() {
    return running.get() && loopThread != null && loopThread.isAlive();
  }

  /** True only after a fatal loop event; an intentional stop or deferred startup is not failure. */
  public boolean hasFailed() {
    return currentState == LoopState.FAILED;
  }

  /**
   * Stop intake processing at a batch boundary and wait for the loop's existing shutdown commit.
   * Unlike {@link #close()}, this preserves owned services and can be reversed by
   * {@link #resumeAfterUpgradePreparation()}.
   */
  public synchronized boolean quiesceForUpgrade(long timeoutMs) {
    if (!isRunning()) {
      return true;
    }
    running.set(false);
    Thread thread = loopThread;
    if (thread != null) {
      try {
        thread.join(Math.max(1L, timeoutMs));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
      if (thread.isAlive()) {
        // Preparation is reversible. Let the current loop continue when its in-flight batch
        // returns rather than leaving the Worker half-stopped.
        running.set(true);
        return false;
      }
    }
    return true;
  }

  /** Resume a loop stopped by {@link #quiesceForUpgrade(long)}. Idempotent. */
  public synchronized void resumeAfterUpgradePreparation() {
    if (!isRunning()) {
      start();
    }
  }

  /**
   * Returns the current state of the indexing loop as a wire-stable string
   * ({@code "IDLE"} / {@code "RUNNING"} / {@code "PAUSED"} / {@code "FAILED"}, produced via
   * {@link Enum#name()}). FAILED denotes a fatal loop event, not an intentional stop.
   *
   * <p>Backed by {@link LoopState}; new callers should prefer {@link #loopState()} for
   * type safety. This String accessor is retained for the worker-state wire emission
   * path ({@code EngineForegroundPacingTest}, {@code WorkerAppServices.indexingLoopState()},
   * existing Mockito stubs in the {@code WorkerIngestService*} test family) where the
   * String form crosses a process or test-mock boundary.
   *
   * @deprecated since tempdoc 516 Slice 2 — prefer {@link #loopState()} for typed
   *     comparisons. Not scheduled for removal; the wire path still needs a String.
   */
  @Deprecated(since = "tempdoc 516 Slice 2", forRemoval = false)
  public String getCurrentState() {
    return currentState.name();
  }

  /** Typed accessor for the loop's state. New callers should prefer this over {@link #getCurrentState()}. */
  public LoopState loopState() {
    return currentState;
  }

  private void setCurrentState(LoopState state) {
    if (state != currentState) {
      log.debug("IndexingLoop state: {} -> {}", currentState, state);
      currentState = state;
    }
  }

  // Tempdoc 516 P2 (W7.1): named transitions — state graph is now `grep transitionTo`-able.
  private void transitionToRunning() { setCurrentState(LoopState.RUNNING); }
  private void transitionToIdle() { setCurrentState(LoopState.IDLE); }
  private void transitionToPaused() { setCurrentState(LoopState.PAUSED); }

  // ==================== GPU Lifecycle Management ====================

  /**
   * Handles GPU state transitions for Hybrid Inference architecture.
   *
   * <p>When the Main process claims the GPU (Online Mode), this method unloads
   * the embedding model to free VRAM. When Main releases the GPU (Indexing Mode),
   * this method reloads the embedding model.
   *
   * <p>This is critical for 8GB VRAM systems where we cannot fit both the
   * GGUF chat model and the embedding model in memory simultaneously.
   */
  // Tempdoc 516 Slice 4c: handleGpuStateTransition, unloadEmbeddingService,
  // notifyEmbeddingProviderChange moved to EmbeddingProviderLifecycle.

  @Override
  public void close() throws IOException {
    log.info("Stopping IndexingLoop...");
    running.set(false);
    // Tempdoc 798: drop the probe so a signal bus that outlives this loop can't answer from a
    // dead queue reference.
    signalBus.setPendingIngestProbe(null);

    if (loopThread != null) {
      loopThread.interrupt();
      try {
        loopThread.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Close NER service — IndexingLoop is the sole closer (KnowledgeServer does not
    // retain a reference). Other borrowed services (embeddingService, spladeEncoder,
    // disambiguationService) are closed by KnowledgeServer after this method returns.
    var ner = encoderBindings.nerService();
    if (ner != null) {
      try {
        ner.close();
      } catch (Exception e) {
        log.warn("Error closing NER service: {}", e.getMessage());
      }
    }

    // Close the timeboxed content extractor (owned by IndexingLoop)
    if (contentExtractor != null) {
      try {
        contentExtractor.close();
      } catch (Exception e) {
        log.warn("Error closing content extractor: {}", e.getMessage());
      }
    }

    log.info("IndexingLoop stopped (extraction timeouts: {})",
        contentExtractor != null ? contentExtractor.getTimeoutCount() : 0);
  }

  /** Returns the disambiguation service (may be null if not yet wired). */
  public io.justsearch.indexerworker.disambiguation.DisambiguationService getDisambiguationService() {
    return encoderBindings.disambiguationService();
  }

  /**
   * Stops the loop, runs external cleanup while stopped, clears internal bookkeeping, and restarts.
   *
   * <p>Must NOT be confused with {@link #close()} — close destroys owned resources (nerService,
   * contentExtractor) making the loop unrestartable. This method preserves all resources.
   *
   * <p>The loop is always restarted, even if {@code externalCleanup} throws. A dead loop is worse
   * than a partially-reset loop — the caller can retry or escalate, but a permanently stopped loop
   * is unrecoverable without a full process restart.
   *
   * @param externalCleanup runs while the loop is stopped (Lucene wipe, queue clear, etc.)
   * @throws IllegalStateException if the loop thread does not stop within 5 seconds
   */
  public void resetForProfiling(Runnable externalCleanup) throws InterruptedException {
    log.info("resetForProfiling: stopping loop...");

    // 1. Stop the loop thread by setting running=false and waiting for it to exit.
    // DO NOT call loopThread.interrupt() — Lucene uses NIO channels internally, and
    // Thread.interrupt() causes ClosedByInterruptException which permanently closes
    // the IndexWriter. Instead, rely on the loop's natural running-flag check (every
    // iteration, including idle sleeps of 100ms-1s).
    // The loop thread may complete its current iteration (up to one batch) before it
    // observes running=false and exits. This is safe because the externalCleanup
    // callback calls deleteAll() which wipes the index unconditionally.
    running.set(false);
    if (loopThread != null) {
      loopThread.join(10_000);
      if (loopThread.isAlive()) {
        // Thread stuck in long-running work (e.g. native ORT call). Unsafe to proceed
        // with cleanup — concurrent Lucene writes would corrupt the index.
        running.set(true); // allow the thread to keep running when it unblocks
        throw new IllegalStateException(
            "IndexingLoop thread did not stop within 10s — reset aborted");
      }
    }

    // 2. External cleanup + 3. Clear bookkeeping + 4. Restart — always restart even on failure
    try {
      // External cleanup — runs while the loop is stopped
      externalCleanup.run();

      // Clear internal bookkeeping.
      // JMM: these non-volatile fields are written here (gRPC thread) and read by the new
      // loop thread created in start(). Thread.start() provides the happens-before guarantee
      // that makes all prior writes visible to the new thread. If start() is ever changed to
      // reuse an existing thread or executor, these fields must become volatile or be guarded.
      journal.clearPending();
      forcedPaths.clear();
      indexedSinceCommit = 0;
      lastCommitTime = System.currentTimeMillis();
      backfillScheduler.resetState();
      batchStats.reset();
      extractor.resetPerBatchCache();
      transitionToIdle();
    } finally {
      // Always restart — a dead loop is unrecoverable without process restart
      start();
      log.info("resetForProfiling: loop restarted");
    }
  }

  // Tempdoc 516 Slice 4d (W6): all process*Backfill methods + section headers
  // (Combined / Phase 6 Chunk / NER / SPLADE / Disambiguation) moved to BackfillScheduler.
}
