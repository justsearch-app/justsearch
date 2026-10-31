/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ParityDiagnostics;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.adapters.lucene.runtime.VectorFormatDetector;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.loop.BackfillScheduler;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.metrics.BatchTimingKeys;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.extract.OcrRoutingConfig;
import io.justsearch.indexerworker.extract.TikaOcrRuntime;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.app.api.status.MigrationSource;
import io.justsearch.ipc.ChunkCoverage;
import io.justsearch.ipc.CompatibilityStatus;
import io.justsearch.ipc.CoreStatus;
import io.justsearch.ipc.EnrichmentCoverage;
import io.justsearch.ipc.EnumeratorProgress;
import io.justsearch.ipc.FailureStatus;
import io.justsearch.ipc.FeatureCoverage;
import io.justsearch.ipc.GpuDiagnostics;
import io.justsearch.ipc.MigrationStatus;
import io.justsearch.ipc.OrtCudaProbeResult;
import io.justsearch.ipc.PipelineBatchTiming;
import io.justsearch.ipc.QueueDbHealth;
import io.justsearch.ipc.StageCompleteness;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.TelemetryStatus;
import io.justsearch.ipc.VectorQuantization;
import io.justsearch.ipc.VisualExtractionStatus;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.telemetry.RrdMetricStore;
import io.justsearch.telemetry.RrdMetricStore.TimeSeriesResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Status reporting helper for {@link WorkerIngestService}.
 *
 * <p>Builds the {@link StatusResponse} for the {@code indexStatus} RPC, encapsulating
 * compatibility checking, fingerprint comparison, queue health aggregation, and all
 * observability fields. Extracted to reduce the size of the service class.
 *
 * <p>341: Refactored to build nested sub-messages ({@link CoreStatus}, {@link MigrationStatus},
 * etc.) instead of setting 116 flat fields on StatusResponse.
 */
final class IndexStatusOps {
  private static final Logger log = LoggerFactory.getLogger(IndexStatusOps.class);

  /**
   * The bar {@code ChunkCoverage.vectors_ready} is evaluated against (the RAG hybrid threshold).
   * Tempdoc 821 §3-C3 hoisted it out of the call site so the number published on the wire and the
   * number the boolean is computed from cannot diverge.
   */
  private static final double VECTOR_READY_PERCENT = 95.0;

  /** Presence was verified against the artifact itself, so a lying status field cannot inflate it. */
  private static final String TIER_ARTIFACT = "ARTIFACT";

  /**
   * Presence is the bookkeeping status field only — declared honestly because no artifact proxy
   * exists: the {@code splade} field is a postings/feature field with {@code docValues:false}
   * ({@code fields.v1.json}), and NER writes no countable per-document artifact.
   */
  private static final String TIER_STATUS = "STATUS";

  /**
   * Chunk embedding's completeness stage id. Unlike the other three it has no
   * {@link BatchTimingKeys} member, because it has no batch-timing stage of its own — it rides
   * {@link BatchTimingKeys#EMBED} (every writer branches {@code isChunk ? CHUNK_VECTOR : VECTOR}).
   */
  private static final String STAGE_CHUNK_EMBED = "chunk_embed";

  private final JobQueue jobQueue;
  private final Path indexPath;
  private final IndexCountOps ingestCountOps;
  private final IndexCountOps searchCountOps;
  private final Supplier<String> configuredVectorFormat;
  private final Supplier<String> storedVectorFormat;
  private final Supplier<VectorFormatDetector.Summary> queryVectorFormatActual;
  private final Supplier<Map<String, String>> openTimeCommitUserData;
  private final Supplier<Map<String, String>> latestCommitUserDataBestEffort;
  private final IndexGenerationManager indexGenerationManager;
  private final Supplier<MigrationProgressSnapshot> migrationProgressSupplier;
  private final OperationalMetrics metrics;
  private final IndexingLoop indexingLoop;
  private final WorkerSignalBus signalBus;
  private final long migrationSwitchingMaxDurationMs;
  private Supplier<String> migrationReplayFailure = () -> null;

  void setMigrationReplayFailure(Supplier<String> supplier) { migrationReplayFailure = supplier; }

  private BooleanSupplier writerTragic = () -> false;
  private Path openedSearchPath;
  private Path openedIngestPath;

  private static final long INDEX_SIZE_CACHE_TTL_MS = 30_000L;

  private volatile EmbeddingCompatibilityController embeddingCompatController;
  private volatile Supplier<OrtCudaStatus> ortCudaStatusSupplier;

  // Cached index size to avoid per-RPC Files.walk() traversal (refreshed every 30 seconds).
  private volatile long cachedIndexSizeBytes = 0L;
  private volatile long indexSizeObservedAtMs = 0L;
  private volatile Supplier<OrtCudaStatus> spladeOrtCudaStatusSupplier;
  private volatile Supplier<OrtCudaStatus> embedOrtCudaStatusSupplier;
  // Tempdoc 422: per-encoder OrtCuda probes for ner / citation / bgeM3 so the
  // /api/inference/encoders explainer covers all 6 encoders.
  private volatile Supplier<OrtCudaStatus> nerOrtCudaStatusSupplier;
  private volatile Supplier<OrtCudaStatus> citationOrtCudaStatusSupplier;
  private volatile Supplier<OrtCudaStatus> bgeM3OrtCudaStatusSupplier;
  private volatile Supplier<String> embedBackendSupplier;
  private volatile Supplier<Integer> embedGpuLayersSupplier;
  private volatile Supplier<String> spladeModelPathSupplier;
  private volatile Supplier<String> rerankerModelPathSupplier;
  private volatile Supplier<String> nerModelPathSupplier;
  private volatile Supplier<Boolean> nerGpuEnabledSupplier;
  private volatile Supplier<io.justsearch.configuration.resolved.ResolvedConfig> resolvedConfigSupplier;
  private volatile Supplier<Map<String, Object>> expectedCommitMetadataSupplier;
  private volatile Supplier<IndexGenerationManager.AutoRebuildBrake> bootRebuildHoldSupplier =
      () -> null;
  /** Tempdoc 406 — late-bound runtime gauges supplier (swap-aware). */
  private volatile Supplier<LuceneRuntimeTypes.RuntimeGaugesSnapshot> runtimeGaugesSupplier;

  /** Tempdoc 419 C3 V1 — late-bound RRD store supplier for the worker's recent-trend backfill. */
  private volatile Supplier<RrdMetricStore> rrdStoreSupplier;

  // Per-stage enabled state (tempdoc 394 follow-up). Default true — stages
  // are always enabled unless the wiring step explicitly disables them,
  // which matches backend defaults and the all-enabled production case.
  private volatile boolean embeddingEnabled = true;
  private volatile boolean spladeEnabled = true;
  private volatile boolean nerEnabled = true;

  IndexStatusOps(
      JobQueue jobQueue,
      Path indexPath,
      IndexCountOps ingestCountOps,
      IndexCountOps searchCountOps,
      Supplier<String> configuredVectorFormat,
      Supplier<String> storedVectorFormat,
      Supplier<VectorFormatDetector.Summary> queryVectorFormatActual,
      Supplier<Map<String, String>> openTimeCommitUserData,
      Supplier<Map<String, String>> latestCommitUserDataBestEffort,
      IndexGenerationManager indexGenerationManager,
      Supplier<MigrationProgressSnapshot> migrationProgressSupplier,
      OperationalMetrics metrics,
      IndexingLoop indexingLoop,
      WorkerSignalBus signalBus,
      long migrationSwitchingMaxDurationMs) {
    this.jobQueue = jobQueue;
    this.indexPath = indexPath;
    this.ingestCountOps = ingestCountOps;
    this.searchCountOps = searchCountOps;
    this.configuredVectorFormat = configuredVectorFormat;
    this.storedVectorFormat = storedVectorFormat;
    this.queryVectorFormatActual = queryVectorFormatActual;
    this.openTimeCommitUserData = openTimeCommitUserData;
    this.latestCommitUserDataBestEffort = latestCommitUserDataBestEffort;
    this.indexGenerationManager = indexGenerationManager;
    this.migrationProgressSupplier = migrationProgressSupplier;
    this.metrics = metrics;
    this.indexingLoop = indexingLoop;
    this.signalBus = signalBus;
    this.migrationSwitchingMaxDurationMs = migrationSwitchingMaxDurationMs;
  }

  void setEmbeddingCompatController(EmbeddingCompatibilityController controller) {
    this.embeddingCompatController = controller;
  }

  void setWriterTragic(BooleanSupplier writerTragic) {
    this.writerTragic = writerTragic;
  }

  /** Bind the exact runtime directories before this service is published. */
  void setOpenedRuntimePaths(Path searchPath, Path ingestPath) {
    openedSearchPath = searchPath;
    openedIngestPath = ingestPath;
  }

  private String openedGenerationId(Path path) {
    if (path == null || indexGenerationManager == null) return "";
    try {
      return indexGenerationManager.generationIdForOpenedPath(path);
    } catch (IOException invalid) {
      log.debug("Opened runtime generation identity is unavailable: {}", invalid.getMessage());
      return "";
    }
  }

  void setOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    this.ortCudaStatusSupplier = supplier;
  }

  void setSpladeOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    this.spladeOrtCudaStatusSupplier = supplier;
  }

  void setEmbedOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    this.embedOrtCudaStatusSupplier = supplier;
  }

  /** Tempdoc 422 — per-encoder runtime status for the explainer endpoint. */
  void setNerOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    this.nerOrtCudaStatusSupplier = supplier;
  }

  /** Tempdoc 422 — per-encoder runtime status for the explainer endpoint. */
  void setCitationOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    this.citationOrtCudaStatusSupplier = supplier;
  }

  /** Tempdoc 422 — per-encoder runtime status for the explainer endpoint. */
  void setBgeM3OrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    this.bgeM3OrtCudaStatusSupplier = supplier;
  }

  void setEmbedBackendSupplier(Supplier<String> supplier) {
    this.embedBackendSupplier = supplier;
  }

  /** Tempdoc 406 — wires a swap-aware runtime gauge supplier; null safe (falls back to EMPTY). */
  public void setRuntimeGaugesSupplier(Supplier<LuceneRuntimeTypes.RuntimeGaugesSnapshot> supplier) {
    this.runtimeGaugesSupplier = supplier;
  }

  /**
   * Tempdoc 419 C3 V1 — wires the worker's RRD metric store so {@link #buildCore} can backfill
   * the {@code recent_job_queue_depth} trend. Null-safe: when unset (tests / startup) the trend
   * is left empty.
   */
  public void setRrdStoreSupplier(Supplier<RrdMetricStore> supplier) {
    this.rrdStoreSupplier = supplier;
  }

  /**
   * Sets per-stage enabled flags for the /api/status enrichment view. Called once at
   * wire-time from {@code DefaultWorkerAppServices.wireStageEnabled}. Default is all
   * enabled — if the wiring step is skipped, stages report as enabled (matching the
   * production case where every stage runs).
   */
  void setStageEnabled(boolean embedding, boolean splade, boolean ner) {
    this.embeddingEnabled = embedding;
    this.spladeEnabled = splade;
    this.nerEnabled = ner;
  }

  void setEmbedGpuLayersSupplier(Supplier<Integer> supplier) {
    this.embedGpuLayersSupplier = supplier;
  }

  void setSpladeModelPathSupplier(Supplier<String> supplier) {
    this.spladeModelPathSupplier = supplier;
  }

  void setRerankerModelPathSupplier(Supplier<String> supplier) {
    this.rerankerModelPathSupplier = supplier;
  }

  void setNerModelPathSupplier(Supplier<String> supplier) {
    this.nerModelPathSupplier = supplier;
  }

  void setNerGpuEnabledSupplier(Supplier<Boolean> supplier) {
    this.nerGpuEnabledSupplier = supplier;
  }

  void setResolvedConfigSupplier(
      Supplier<io.justsearch.configuration.resolved.ResolvedConfig> supplier) {
    this.resolvedConfigSupplier = supplier;
  }

  void setExpectedCommitMetadataSupplier(Supplier<Map<String, Object>> supplier) {
    this.expectedCommitMetadataSupplier = supplier;
  }

  /**
   * #758: what the Worker's boot held back, or {@code null} when it held
   * nothing. A boot that stopped an automatic rebuild keeps ingestion stopped for the rest of that
   * boot, so the status must keep saying so even after the brake record reads differently.
   */
  void setBootRebuildHoldSupplier(Supplier<IndexGenerationManager.AutoRebuildBrake> supplier) {
    this.bootRebuildHoldSupplier = supplier == null ? () -> null : supplier;
  }

  private IndexGenerationManager.AutoRebuildBrake bootRebuildHold() {
    try {
      IndexGenerationManager.AutoRebuildBrake held = bootRebuildHoldSupplier.get();
      return held != null && held.holds() ? held : null;
    } catch (RuntimeException e) {
      log.debug("Failed to read the boot's rebuild hold: {}", e.getMessage());
      return null;
    }
  }

  // ==================== StatusResponse builder (341: nested sub-messages) ====================

  /** Builds the full {@link StatusResponse} for the indexStatus RPC. */
  StatusResponse buildStatusResponse() {
    // --- Pre-computation (shared across sub-messages) ---
    JobQueue.JobStateCounts counts = jobQueue.jobStateCounts();
    // One queue observation owns both the state and all backlog slices.
    long queueDepth = counts.pendingCount() + counts.processingCount();
    JobQueue.FailureSummary failures = jobQueue.failureSummary();

    // #758: a count that has never been read successfully is unknown, not 0. The
    // numeric fields keep carrying 0 there for readers that predate doc_count_unknown, and the flag
    // tells every newer reader that the 0 is not a count.
    Map<String, io.justsearch.ipc.CountObservation> countObservations = new java.util.HashMap<>();
    long activeDocCount = 0;
    boolean activeDocCountUnknown = false;
    IndexCountOps activeCountOps = searchCountOps != null ? searchCountOps : ingestCountOps;
    if (activeCountOps != null) {
      java.util.OptionalLong active =
          parentDocCountOrLastKnown(activeCountOps, lastKnownActiveDocCount, countObservations, "active");
      activeDocCountUnknown = active.isEmpty();
      activeDocCount = active.orElse(0L);
    }

    // The user count describes the serving generation, never DONE jobs or a half-built index.
    long docCount = activeDocCount;
    boolean docCountUnknown = activeCountOps == null || activeDocCountUnknown;

    // searchableDocCount answers "how many documents can a DEFAULT-scope search return", so it must
    // be counted on the reader that SERVES search — the same one activeDocCount reads. During a
    // rebuild the ingest reader is the half-built NEW generation while search still serves the old
    // one, so counting there described a generation no query could reach.
    java.util.OptionalLong searchable;
    if (activeCountOps != null) {
      searchable =
          countDefaultScopeDocs(
              activeCountOps,
              activeDocCountUnknown
                  ? java.util.OptionalLong.empty()
                  : java.util.OptionalLong.of(activeDocCount), countObservations);
    } else {
      searchable =
          docCountUnknown ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(docCount);
    }
    long searchableDocCount = searchable.orElse(0L);
    boolean countsUnknown = docCountUnknown || searchable.isEmpty();

    long buildingDocCount = 0;
    if (ingestCountOps != null && ingestCountOps != activeCountOps) {
      buildingDocCount = parentDocCountOrLastKnown(ingestCountOps, lastKnownWrittenDocCount, countObservations, "building").orElse(0L);
    }

    for (String key : java.util.List.of("active", "building", "searchable")) {
      countObservations.putIfAbsent(key, io.justsearch.ipc.CountObservation.newBuilder().setState("unknown").build());
    }
    boolean loopFailed = indexingLoop != null && indexingLoop.hasFailed();
    long outagePauseUntilMs = indexingLoop == null ? 0L : indexingLoop.serviceOutagePausedUntilMs();
    boolean tragicWriter = writerTragic.getAsBoolean();
    String state;
    if (tragicWriter) {
      state = "ERROR";
    } else if (loopFailed) {
      state = IndexingLoop.LoopState.FAILED.name();
    } else if (outagePauseUntilMs > 0) {
      state = "WAITING";
    } else if (counts.processingCount() > 0 || counts.pendingReadyCount() > 0) {
      state = "INDEXING";
    } else if (queueDepth > 0) {
      state = "WAITING";
    } else if (failures != null && failures.failedCount() > 0) {
      state = "ERROR";
    } else {
      state = "IDLE";
    }

    boolean healthy = !tragicWriter && !loopFailed && (failures == null || failures.failedCount() == 0);

    IndexGenerationManager.State stateSnapshot =
        indexGenerationManager == null ? null : indexGenerationManager.readStateBestEffort();

    long processingJobsCount = counts.processingCount();

    // --- Build sub-messages ---
    return StatusResponse.newBuilder()
        .putAllCountObservations(countObservations)
        .setCore(
            buildCore(
                queueDepth,
                docCount,
                searchableDocCount,
                countsUnknown,
                healthy,
                state,
                jobQueue.pendingBytes()))
        .setFailure(buildFailure(failures, outagePauseUntilMs))
        .setMigration(
            buildMigration(
                stateSnapshot, activeDocCount, buildingDocCount, counts, processingJobsCount))
        .setCompatibility(buildCompatibility())
        .setQueueDb(buildQueueDb())
        .setEnrichment(buildEnrichment())
        .setGpu(buildGpu())
        .setVectorQuantization(buildVectorQuantization())
        .setTelemetry(buildTelemetry(processingJobsCount))
        .setSearchConfig(buildSearchConfig())
        .setVisualExtraction(buildVisualExtraction())
        .putAllCommitUserData(safeCommitUserData())
        .setBuildStamp(buildStamp())
        .build();
  }

  private VisualExtractionStatus buildVisualExtraction() {
    OcrRoutingConfig ocrConfig = currentOcrConfig();
    String blockedReason = TikaOcrRuntime.blockedReason(ocrConfig);
    int visualEnrichmentNeededCount =
        countPendingVduDemand(SchemaFields.VDU_DEMAND_KIND_VISUAL_ENRICHMENT);
    int visualTextNeededCount = countPendingBaselineVduDemand(visualEnrichmentNeededCount);
    VisualExtractionStatus.Builder builder =
        VisualExtractionStatus.newBuilder()
            .setOcrEnabled(ocrConfig.enabled())
            .setOcrEngineAvailable(TikaOcrRuntime.isEngineAvailable())
            .setOcrEngine(TikaOcrRuntime.engineStatus())
            .setVisualTextNeededCount(visualTextNeededCount)
            .setVisualEnrichmentNeededCount(visualEnrichmentNeededCount);
    if (!blockedReason.isBlank()) {
      builder.setOcrBlockedReason(blockedReason);
    }
    return builder.build();
  }

  private OcrRoutingConfig currentOcrConfig() {
    Supplier<io.justsearch.configuration.resolved.ResolvedConfig> supplier = resolvedConfigSupplier;
    if (supplier == null) {
      return OcrRoutingConfig.defaults();
    }
    try {
      io.justsearch.configuration.resolved.ResolvedConfig config = supplier.get();
      return config == null ? OcrRoutingConfig.defaults() : OcrRoutingConfig.from(config.ocr());
    } catch (RuntimeException e) {
      return OcrRoutingConfig.defaults();
    }
  }

  /**
   * Tempdoc 811 C-4 — the population a DEFAULT-scope search can actually return: non-chunk
   * documents minus the collections the default scope excludes (today exactly {@code
   * agent-history}). The exclusion set is NOT re-listed here: {@link
   * QueryFilterBuilder#buildFilterQueryOnly} with {@code null} filters IS the production
   * default-scope filter (chunk {@code MUST_NOT} + the reserved-collection {@code MUST_NOT} + the
   * {@code MatchAllDocs} anchor), so this count is a projection of the one search authority and
   * cannot drift from what search does. Help docs and {@code mcp-ingest} documents are IN the
   * default scope and therefore counted — that is the honest number; a per-collection breakdown is
   * deferred (811 §After the decisions).
   *
   * <p>Honest limit: the collection {@code MUST_NOT} only matches documents that CARRY the
   * collection tag, and chunk documents written before #379 carry none. That caveat cannot bite
   * here because this counts NON-chunk documents, whose {@code collection} has always been written.
   *
   * @param fallback returned when the builder yields no filter at all (it cannot today — the
   *     default scope always contributes the two exclusions — but a zero would be a silent lie if
   *     that ever changed)
   */
  private java.util.OptionalLong countDefaultScopeDocs(
      IndexCountOps countOps, java.util.OptionalLong fallback,
      Map<String, io.justsearch.ipc.CountObservation> observations) {
    Query defaultScope = QueryFilterBuilder.buildFilterQueryOnly(null);
    if (defaultScope == null) {
      observations.put("searchable", observations.get("active"));
      return fallback;
    }
    try {
      long count = countOps.countQueryOrThrow(defaultScope);
      CountReading reading = new CountReading(countOps, count, System.currentTimeMillis());
      lastKnownSearchableDocCount.set(reading);
      observations.put("searchable", observation("fresh", reading));
      return java.util.OptionalLong.of(count);
    } catch (IOException | RuntimeException e) {
      CountReading previous = lastKnownSearchableDocCount.get();
      boolean sameGeneration = previous != null && previous.ops() == countOps;
      observations.put("searchable", observation(sameGeneration ? "stale" : "unknown", sameGeneration ? previous : null));
      return sameGeneration ? java.util.OptionalLong.of(previous.count()) : java.util.OptionalLong.empty();
    }
  }

  private CoreStatus buildCore(
      long queueDepth,
      long docCount,
      long searchableDocCount,
      boolean docCountUnknown,
      boolean healthy,
      String state,
      JobQueue.PendingBytes pendingBytes) {
    Supplier<LuceneRuntimeTypes.RuntimeGaugesSnapshot> rgs = runtimeGaugesSupplier;
    LuceneRuntimeTypes.RuntimeGaugesSnapshot gauges =
        rgs != null ? rgs.get() : LuceneRuntimeTypes.RuntimeGaugesSnapshot.EMPTY;
    if (gauges == null) gauges = LuceneRuntimeTypes.RuntimeGaugesSnapshot.EMPTY;
    CoreStatus.Builder b =
        CoreStatus.newBuilder()
            .setQueueDepth(queueDepth)
            .setDocCount(docCount)
            .setSearchableDocCount(searchableDocCount)
            .setDocCountUnknown(docCountUnknown)
            .setIsHealthy(healthy)
            .setState(state)
            // Null-tolerant on purpose: a Worker can legitimately have no indexing loop.
            // Deferred-writer mode leaves it null until the writer upgrade reconstructs
            // appServices (DefaultWorkerAppServices.startIndexingLoop already guards for
            // that), and an exhausted rebuild brake never starts one at all. Dereferencing
            // it here threw the whole status response into its ERROR fallback, which is how
            // the reason code for the brake became unreachable (tempdoc 915 §C.8).
            .setLastCommitTimestamp(indexingLoop == null ? 0L : indexingLoop.getLastCommitTime())
            // Tempdoc 885 item 3: signal_bus_activity_ts is no longer populated. The Worker no
            // longer reads the Head-written activity byte at all (foreground load is observed
            // in-process), so reporting it would be reporting a value nothing acts on.
            //
            // Lane F item A10: signal_bus_heartbeat_ts joins it, for the stronger reason that the
            // heartbeat no longer exists. It was the Head process writing "I am still alive" into
            // the memory-mapped region; inside one JVM there is no second process to have written
            // it, and reporting System.currentTimeMillis() here would have been a liveness claim
            // manufactured by its own reader. Both proto fields stay declared (they are the other
            // half of a message this JVM now passes to itself) and both stay at their zero default.
            //
            // Lane F item A16 removed their last PROJECTION: WorkerStatusMapper used to copy both
            // into a SignalBusView on /api/debug/state, so the endpoint published two permanent
            // zeros as if they were readings. The sub-object is gone from the response, the record
            // and its schema; the fields end here.
            .setUptimeMs(System.currentTimeMillis() - signalBus.startupTime())
            .setIndexSizeBytes(cachedIndexSizeIfFreshOrRefresh())
            .setPendingEmbeddingCount(
                countPendingByStatus(
                    SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING))
            .setPendingVduCount(
                countPendingByStatus(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING))
            .setWriterQueueDepth(gauges.writerQueueDepth())
            .setWriterPendingDocs(gauges.writerPendingDocs())
            .setCommitCount(gauges.commitCount())
            .setRefreshLagMs(gauges.refreshLagMs())
            .setPendingBytes(pendingBytes == null ? 0L : pendingBytes.knownBytes())
            .setPendingUnknownSizeJobs(pendingBytes == null ? 0L : pendingBytes.unknownSizeJobs());
    // Tempdoc 931 §E item 8 — merge state of the reader that SERVES search (the same reader
    // activeDocCount is counted on), so maxDoc - numDocs is the deleted-but-unmerged backlog of the
    // generation a query can actually reach, not of a half-built rebuild generation.
    IndexCountOps activeCountOps = searchCountOps != null ? searchCountOps : ingestCountOps;
    if (activeCountOps != null) {
      try {
        var mergeCounts = activeCountOps.mergeCountsOrThrow();
        b.setIndexMaxDoc(mergeCounts.maxDoc()).setIndexNumDocs(mergeCounts.numDocs());
      } catch (IOException | RuntimeException unreadable) {
        b.setIndexMaxDoc(-1L).setIndexNumDocs(-1L);
        log.debug("Merge counters unreadable: {}", unreadable.getMessage());
      }
    }
    for (long v : recentJobQueueDepthTrend()) {
      b.addRecentJobQueueDepth(v);
    }
    for (double v : recentDocsPerSecTrend()) {
      b.addRecentDocsPerSec(v);
    }
    return b.build();
  }

  /**
   * Tempdoc 419 C3 V1: 30-min trend of {@code worker.job_queue.depth} backed by the worker's
   * RRD store. Returns an empty array when the store isn't wired or hasn't accumulated data
   * yet (e.g., before the first telemetry flush).
   */
  private long[] recentJobQueueDepthTrend() {
    Supplier<RrdMetricStore> supp = rrdStoreSupplier;
    if (supp == null) return new long[0];
    RrdMetricStore store;
    try {
      store = supp.get();
    } catch (RuntimeException e) {
      return new long[0];
    }
    if (store == null) return new long[0];
    long nowSec = Instant.now().getEpochSecond();
    TimeSeriesResult result;
    try {
      result = store.query(WorkerOpsMetricCatalog.JOB_QUEUE_DEPTH, nowSec - 1800, nowSec);
    } catch (RuntimeException e) {
      log.debug("RRD query for {} failed: {}", WorkerOpsMetricCatalog.JOB_QUEUE_DEPTH, e.getMessage());
      return new long[0];
    }
    if (result == null) return new long[0];
    double[] values = result.values();
    if (values == null || values.length == 0) return new long[0];
    long[] outQueue = new long[values.length];
    for (int i = 0; i < values.length; i++) {
      double d = values[i];
      outQueue[i] = Double.isFinite(d) ? Math.round(d) : 0L;
    }
    return outQueue;
  }

  /**
   * Tempdoc 419 C3 V2 P2: 30-min trend of {@code worker.documents.indexed.rate_per_sec}. Same
   * shape as {@link #recentJobQueueDepthTrend} but doubles. Empty array when the store isn't
   * wired or has no data in the window yet.
   */
  private double[] recentDocsPerSecTrend() {
    Supplier<RrdMetricStore> supp = rrdStoreSupplier;
    if (supp == null) return new double[0];
    RrdMetricStore store;
    try {
      store = supp.get();
    } catch (RuntimeException e) {
      return new double[0];
    }
    if (store == null) return new double[0];
    long nowSec = Instant.now().getEpochSecond();
    TimeSeriesResult result;
    try {
      result = store.query(WorkerOpsMetricCatalog.INDEX_DOCS_PER_SEC, nowSec - 1800, nowSec);
    } catch (RuntimeException e) {
      log.debug("RRD query for {} failed: {}", WorkerOpsMetricCatalog.INDEX_DOCS_PER_SEC, e.getMessage());
      return new double[0];
    }
    if (result == null) return new double[0];
    double[] values = result.values();
    if (values == null || values.length == 0) return new double[0];
    // Replace NaN/Inf with 0 so downstream JSON serialization stays valid.
    double[] out = new double[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = Double.isFinite(values[i]) ? values[i] : 0.0;
    }
    return out;
  }

  private FailureStatus buildFailure(JobQueue.FailureSummary failures, long outagePauseUntilMs) {
    return FailureStatus.newBuilder()
        .setFailedCount(failures == null ? 0L : failures.failedCount())
        .setLastFailedPath(
            failures == null || failures.lastFailedPath() == null
                ? ""
                : failures.lastFailedPath())
        .setLastFailedErrorMessage(
            failures == null || failures.lastFailedErrorMessage() == null
                ? ""
                : failures.lastFailedErrorMessage())
        .setLastFailedAtMs(
            failures == null || failures.lastFailedAtMs() == null
                ? 0L
                : failures.lastFailedAtMs())
        .setNextRetryAtMs(
            outagePauseUntilMs > 0 ? outagePauseUntilMs
                : failures == null || failures.nextRetryAtMs() == null ? 0L : failures.nextRetryAtMs())
        .setSearchesZeroResultCount(metrics.getSearchesZeroResultCount())
        .putAllFailedByFileKind(metrics.getFailedByFileKind())
        .build();
  }

  private MigrationStatus buildMigration(
      IndexGenerationManager.State stateSnapshot,
      long activeDocCount,
      long buildingDocCount,
      JobQueue.JobStateCounts counts,
      long processingJobsCount) {

    MigrationProgressSnapshot mp =
        migrationProgressSupplier == null ? null : migrationProgressSupplier.get();
    String replayFailure = migrationReplayFailure.get();

    long switchBufferDepth =
        jobQueue instanceof SwitchBufferCapableQueue sbq ? sbq.switchBufferDepth() : 0L;

    long migrationSwitchingAgeMs = 0L;
    if (stateSnapshot != null
        && "SWITCHING"
            .equalsIgnoreCase(
                stateSnapshot.migration_state() == null
                    ? ""
                    : stateSnapshot.migration_state().trim())) {
      long enteredAtMs = stateSnapshot.updated_at_ms();
      if (enteredAtMs > 0) {
        migrationSwitchingAgeMs = Math.max(0L, System.currentTimeMillis() - enteredAtMs);
      }
    }

    String servingSearchGenerationId = openedGenerationId(openedSearchPath);
    String servingIngestGenerationId = openedGenerationId(openedIngestPath);

    // tempdoc 628 Stage C: surface WHY a rebuild is running (the building generation's manifest source,
    // e.g. "corrupt_index_rebuild") so the Head can word the transition.
    //
    // Tempdoc 837 §2.3(i) — THIS is where the vocabulary lives: a TOTAL read-side mapping applied the
    // moment the persisted string leaves the disk, with UNKNOWN as a first-class member. The field was
    // caller-controlled (POST /api/indexing/migration/start forwarded an arbitrary body string into
    // the manifest) and every pre-existing generation, every test driver's label, and every
    // hand-written manifest can hand back a string outside any vocabulary we define — forever. So the
    // closure is on read, not a strict write-side enum. The empty string is preserved as "no facet":
    // it means "nothing is being rebuilt", which is a different fact from "we do not know why".
    String migrationSource = "";
    if (stateSnapshot != null
        && stateSnapshot.building_generation() != null
        && !stateSnapshot.building_generation().isBlank()
        && indexGenerationManager != null) {
      migrationSource =
          MigrationSource.fromWire(
                  indexGenerationManager.readGenerationSourceBestEffort(
                      stateSnapshot.building_generation()))
              .wire();
    }

    EnumeratorProgress enumerator =
        EnumeratorProgress.newBuilder()
            .setRunning(mp != null && mp.enumeratorRunning())
            .setDone(mp != null && mp.enumeratorDone())
            .setRootsTotal(mp == null ? 0L : mp.rootsTotal())
            .setRootsDone(mp == null ? 0L : mp.rootsDone())
            .setFilesSeen(mp == null ? 0L : mp.filesSeen())
            .setFilesEnqueued(mp == null ? 0L : mp.filesEnqueued())
            .setStartedAtMs(mp == null ? 0L : mp.startedAtMs())
            .setFinishedAtMs(mp == null ? 0L : mp.finishedAtMs())
            .setLastPath(mp == null || mp.lastPath() == null ? "" : mp.lastPath())
            .build();

    return MigrationStatus.newBuilder()
        .setActiveGenerationId(
            stateSnapshot == null || stateSnapshot.active_generation() == null
                ? ""
                : stateSnapshot.active_generation())
        .setMigrationState(
            stateSnapshot == null || stateSnapshot.migration_state() == null
                ? ""
                : stateSnapshot.migration_state())
        .setBuildingGenerationId(
            stateSnapshot == null || stateSnapshot.building_generation() == null
                ? ""
                : stateSnapshot.building_generation())
        .setPreviousGenerationId(
            stateSnapshot == null || stateSnapshot.previous_generation() == null
                ? ""
                : stateSnapshot.previous_generation())
        .setActiveDocCount(activeDocCount)
        .setBuildingDocCount(buildingDocCount)
        .setServingSearchGenerationId(servingSearchGenerationId)
        .setServingIngestGenerationId(servingIngestGenerationId)
        .setEnumerator(enumerator)
        .setSwitchBufferDepth(switchBufferDepth)
        .setPendingJobsCount(counts.pendingCount())
        .setProcessingJobsCount(processingJobsCount)
        .setPendingReadyJobsCount(counts.pendingReadyCount())
        .setPendingBackoffJobsCount(counts.pendingBackoffCount())
        .setSwitchingAgeMs(migrationSwitchingAgeMs)
        .setSwitchingMaxDurationMs(migrationSwitchingMaxDurationMs)
        .setPaused(
            stateSnapshot != null && Boolean.TRUE.equals(stateSnapshot.migration_paused()))
        .setPauseReason(
            stateSnapshot != null && stateSnapshot.pause_reason() != null
                && !stateSnapshot.pause_reason().isBlank() ? stateSnapshot.pause_reason()
                : replayFailure == null ? "" : "Cutover fenced: " + replayFailure)
        .setPausedAtMs(
            stateSnapshot == null || stateSnapshot.paused_at_ms() == null
                ? 0L
                : stateSnapshot.paused_at_ms())
        .setMigrationSource(migrationSource)
        .build();
  }

  private CompatibilityStatus buildCompatibility() {
    return CompatibilityStatus.newBuilder()
        .setEmbeddingFingerprintCurrent(safeEmbeddingFingerprintCurrent())
        .setEmbeddingFingerprintStored(safeEmbeddingFingerprintStored())
        .setEmbeddingCompatState(safeEmbeddingCompatState())
        .setEmbeddingCompatReason(safeEmbeddingCompatReason())
        .setSchemaFpCurrent(safeSchemaFingerprintCurrent())
        .setSchemaFpStored(safeSchemaFingerprintStored())
        .setSchemaCompatState(safeSchemaCompatState())
        .setReindexRequired(isReindexRequired())
        .setReindexRequiredReason(reindexRequiredReason())
        .build();
  }

  private QueueDbHealth buildQueueDb() {
    JobQueue.QueueDbHealthSnapshot h = jobQueue.queueDbHealthSnapshot();
    return QueueDbHealth.newBuilder()
        .setHealthy(h == null || h.healthy())
        .setLastBackupAtMs(h == null ? 0L : h.lastBackupAtMs())
        .setLastQuickCheckAtMs(h == null ? 0L : h.lastQuickCheckAtMs())
        .setLastQuickCheckOk(h == null || h.lastQuickCheckOk())
        .setLastErrorAtMs(h == null ? 0L : h.lastDbErrorAtMs())
        .build();
  }

  private EnrichmentCoverage buildEnrichment() {
    IndexCountOps countOps = ingestCountOps != null ? ingestCountOps : searchCountOps;
    LuceneRuntimeTypes.EmbeddingCounts emb =
        countOps != null ? countOps.queryEmbeddingCounts() : null;
    LuceneRuntimeTypes.SpladeFeatureCounts spl =
        countOps != null ? countOps.querySpladeFeatureCounts() : null;
    LuceneRuntimeTypes.ChunkEmbeddingCounts chk =
        countOps != null ? countOps.queryChunkEmbeddingCounts() : null;
    // Tempdoc 717: coverage/readiness must verify the actual chunk_vector artifact, not the
    // chunk_embedding_status bookkeeping field (which can read COMPLETED while the vector is
    // absent — the F-032 "status lies" class). completed/pending/failed below stay status-derived
    // as enrichment-progress telemetry; the divergence between "completed" and "coverage" is the
    // diagnostic signal.
    LuceneRuntimeTypes.ChunkVectorPresence chkVec =
        countOps != null ? countOps.queryChunkVectorPresenceCount() : null;
    // Tempdoc 931 §E item 8: the chunk-SPLADE stage, over CHUNK documents only. The `splade`
    // FeatureCoverage below counts parents, so this is the only number that describes what the
    // chunk-SPLADE retrieval leg has to score against.
    LuceneRuntimeTypes.SpladeFeatureCounts chkSplade =
        countOps != null ? countOps.queryChunkSpladeCounts() : null;
    boolean chunkSpladeEnabled = chunkSpladeEnabled();
    // Tempdoc 821 §3-C3: the auditor projection. `expected` is the field-carrying denominator
    // (an absent status field means the stage does not apply, post-798), so a stage that lost a
    // sub-population reads `missing > 0` instead of hiding inside a coverage percentage that
    // divides by every document.
    LuceneRuntimeTypes.StageCompletenessCounts queried =
        countOps == null ? null : countOps.queryStageCompletenessCounts();
    // Absence is the zero shape, never a NullPointerException on the status path — the same
    // null-tolerance every count above already applies, so a count source that yields nothing
    // degrades this one surface instead of failing the whole StatusResponse.
    LuceneRuntimeTypes.StageCompletenessCounts stages =
        queried == null ? LuceneRuntimeTypes.StageCompletenessCounts.EMPTY : queried;
    // Spec E step E0b: the S5 unit, one joint count (never derived from the per-stage counters).
    long embeddedAndSparse =
        countOps != null ? countOps.queryEmbeddedAndSparseCount() : 0L;
    // Spec E step E1 (§1.7): live per-stage availability, read from the loop's own checks. No loop
    // (a deferred or read-only service set) means no stage can drain anything.
    BackfillScheduler.StageAvailability available =
        indexingLoop == null ? null : indexingLoop.enrichmentStageAvailability();
    if (available == null) {
      available = BackfillScheduler.StageAvailability.NONE;
    }

    return EnrichmentCoverage.newBuilder()
        .setEmbedding(
            FeatureCoverage.newBuilder()
                .setDocCount(emb == null ? (countOps == null ? 0L : -1L) : emb.total())
                .setCompletedCount(emb == null ? (countOps == null ? 0L : -1L) : emb.completed())
                .setPendingCount(emb == null ? (countOps == null ? 0L : -1L) : emb.pending())
                .setFailedCount(emb == null ? (countOps == null ? 0L : -1L) : emb.failed())
                .setCoveragePercent(emb == null ? (countOps == null ? 0.0 : -1.0) : emb.coveragePercent())
                .build())
        .setSplade(
            FeatureCoverage.newBuilder()
                .setDocCount(spl == null ? (countOps == null ? 0L : -1L) : spl.total())
                .setCompletedCount(spl == null ? (countOps == null ? 0L : -1L) : spl.completed())
                .setPendingCount(spl == null ? (countOps == null ? 0L : -1L) : spl.pending())
                .setFailedCount(spl == null ? (countOps == null ? 0L : -1L) : spl.failed())
                .setCoveragePercent(spl == null ? (countOps == null ? 0.0 : -1.0) : spl.coveragePercent())
                .build())
        .setChunk(
            ChunkCoverage.newBuilder()
                .setDocCount(chk == null ? (countOps == null ? 0L : -1L) : chk.total())
                .setCompletedCount(chk == null ? (countOps == null ? 0L : -1L) : chk.completed())
                .setPendingCount(chk == null ? (countOps == null ? 0L : -1L) : chk.pending())
                .setFailedCount(chk == null ? (countOps == null ? 0L : -1L) : chk.failed())
                // Presence-truthful coverage/readiness (tempdoc 717), not status-derived.
                .setCoveragePercent(chkVec == null ? (countOps == null ? 0.0 : -1.0) : chkVec.coveragePercent())
                .setVectorsReady(chkVec != null && chkVec.isReady(VECTOR_READY_PERCENT))
                .setChunkingNotRequired(chkVec != null && chkVec.chunkingNotRequired())
                .setSpladeEnabled(chunkSpladeEnabled)
                .setSpladeDocCount(chkSplade == null ? (countOps == null ? 0L : -1L) : chkSplade.total())
                .setSpladeFailedCount(chkSplade == null ? (countOps == null ? 0L : -1L) : chkSplade.failed())
                .setSpladeCompletedCount(chkSplade == null ? (countOps == null ? 0L : -1L) : chkSplade.completed())
                .setSpladePendingCount(chkSplade == null ? (countOps == null ? 0L : -1L) : chkSplade.pending())
                .setSpladeCoveragePercent(chkSplade == null ? (countOps == null ? 0.0 : -1.0) : chkSplade.coveragePercent())
                .build())
        // Tempdoc 821 §3-C3 — the two thresholds the auditor owns, published so off-process
        // oracles read them instead of mirroring the Java constants.
        .setChunkMinChars(ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS)
        .setVectorReadyPercent(VECTOR_READY_PERCENT)
        // ARTIFACT tier: `present` is the artifact count, taken over the SAME status-carrying
        // population as `expected` and with FAILED excluded, so the three buckets partition.
        .setCompletenessKnown(queried != null)
        .addAllCompleteness(queried == null ? java.util.List.of() : java.util.List.of(
            stageCompleteness(BatchTimingKeys.EMBED, TIER_ARTIFACT, stages.embedding()),
            stageCompleteness(STAGE_CHUNK_EMBED, TIER_ARTIFACT, stages.chunkEmbedding()),
            stageCompleteness(BatchTimingKeys.SPLADE, TIER_STATUS, stages.splade()),
            stageCompleteness(BatchTimingKeys.NER, TIER_STATUS, stages.ner())))
        .setPendingNerCount(queried == null ? -1 : stages.ner().expected()
            - stages.ner().settledSuccess() - stages.ner().failed())
        .setCompletedNerCount(queried == null ? -1 : stages.ner().settledSuccess())
        .setFailedNerCount(queried == null ? -1 : stages.ner().failed())
        .setEmbeddingEnabled(embeddingEnabled)
        .setSpladeEnabled(spladeEnabled)
        .setNerEnabled(nerEnabled)
        .setEmbeddedAndSparseCount(embeddedAndSparse)
        .setEmbeddingAvailable(available.embedding())
        .setSpladeAvailable(available.sparse())
        .setNerAvailable(available.ner())
        .putAllEnrichmentCompleted(metrics.getEnrichmentCompleted())
        .setBatchTiming(
            PipelineBatchTiming.newBuilder()
                .putAllBatchCount(metrics.getBatchTimingCount())
                .putAllTotalMs(metrics.getBatchTimingMs())
                .build())
        .putAllEncoderProfiles(buildEncoderProfilesProto())
        // Tempdoc 710 Move 2 item 4: which backfill pass ran last idle cycle.
        .setBackfillMode(metrics.getBackfillMode())
        .build();
  }

  /**
   * Builds one {@link StageCompleteness} entry (tempdoc 821 §3-C3). {@code missing} is derived,
   * never independently counted — it is exactly what {@code present} and {@code failed} do not
   * account for.
   *
   * <p>Deliberately NOT floored at 0. {@link LuceneRuntimeTypes.StageCounts} guarantees the three
   * buckets partition {@code expected} (one reader snapshot, one status-carrying population,
   * single-valued status fields, FAILED excluded from the artifact count), so a floor could only
   * ever hide a broken invariant — which is the class this whole surface exists to stop.
   */
  private static StageCompleteness stageCompleteness(
      String stageId, String tier, LuceneRuntimeTypes.StageCounts counts) {
    long present =
        TIER_ARTIFACT.equals(tier) ? counts.artifactPresent() : counts.settledSuccess();
    return StageCompleteness.newBuilder()
        .setStageId(stageId)
        .setTier(tier)
        .setExpected(counts.expected())
        .setPresent(present)
        .setMissing(counts.expected() - present - counts.failed())
        .setFailed(counts.failed())
        .build();
  }

  private Map<String, io.justsearch.ipc.EncoderProfile> buildEncoderProfilesProto() {
    var result = new java.util.LinkedHashMap<String, io.justsearch.ipc.EncoderProfile>();
    metrics.getEncoderProfiles().forEach((name, snap) ->
        result.put(name, io.justsearch.ipc.EncoderProfile.newBuilder()
            .setCalls(snap.calls())
            .putAllPhaseTotalUs(snap.phaseTotalUs())
            .setOrtMinUs(snap.ortMinUs())
            .setOrtMaxUs(snap.ortMaxUs())
            .setOrtP50Us(snap.ortP50Us())
            .setOrtP95Us(snap.ortP95Us())
            .setOrtP99Us(snap.ortP99Us())
            .build()));
    return result;
  }

  /**
   * Tempdoc 422 follow-up: package-private (was private) so {@code IndexStatusOpsGpuDiagnosticsTest}
   * can exercise the supplier→proto wireup directly without instantiating the full StatusResponse
   * builder chain. Production callers go through {@link #buildStatusResponse} only.
   */
  GpuDiagnostics buildGpu() {
    GpuDiagnostics.Builder gpu = GpuDiagnostics.newBuilder();

    OrtCudaStatus rerankerOrt =
        ortCudaStatusSupplier != null ? ortCudaStatusSupplier.get() : null;
    if (rerankerOrt != null) {
      gpu.setRerankerOrtCuda(buildOrtCudaProbe(rerankerOrt));
    }

    OrtCudaStatus spladeOrt =
        spladeOrtCudaStatusSupplier != null ? spladeOrtCudaStatusSupplier.get() : null;
    if (spladeOrt != null) {
      gpu.setSpladeOrtCuda(buildOrtCudaProbe(spladeOrt));
    }

    OrtCudaStatus embedOrt =
        embedOrtCudaStatusSupplier != null ? embedOrtCudaStatusSupplier.get() : null;
    if (embedOrt != null) {
      gpu.setEmbedOrtCuda(buildOrtCudaProbe(embedOrt));
    }

    // Tempdoc 422: ner / citation / bgeM3 OrtCuda probes — closes the 3-of-6 gap
    // so /api/inference/encoders can produce a runtime explanation per encoder.
    OrtCudaStatus nerOrt =
        nerOrtCudaStatusSupplier != null ? nerOrtCudaStatusSupplier.get() : null;
    if (nerOrt != null) {
      gpu.setNerOrtCuda(buildOrtCudaProbe(nerOrt));
    }
    OrtCudaStatus citationOrt =
        citationOrtCudaStatusSupplier != null ? citationOrtCudaStatusSupplier.get() : null;
    if (citationOrt != null) {
      gpu.setCitationOrtCuda(buildOrtCudaProbe(citationOrt));
    }
    OrtCudaStatus bgeM3Ort =
        bgeM3OrtCudaStatusSupplier != null ? bgeM3OrtCudaStatusSupplier.get() : null;
    if (bgeM3Ort != null) {
      gpu.setBgeM3OrtCuda(buildOrtCudaProbe(bgeM3Ort));
    }

    if (embedBackendSupplier != null) {
      String eb = embedBackendSupplier.get();
      gpu.setEmbedBackend(eb != null ? eb : "");
    }
    if (embedGpuLayersSupplier != null) {
      gpu.setEmbedGpuLayers(embedGpuLayersSupplier.get());
    }
    if (spladeModelPathSupplier != null) {
      String smp = spladeModelPathSupplier.get();
      gpu.setSpladeModelPath(smp != null ? smp : "");
    }
    if (rerankerModelPathSupplier != null) {
      String rmp = rerankerModelPathSupplier.get();
      gpu.setRerankerModelPath(rmp != null ? rmp : "");
    }
    if (nerModelPathSupplier != null) {
      String nmp = nerModelPathSupplier.get();
      gpu.setNerModelPath(nmp != null ? nmp : "");
    }
    if (nerGpuEnabledSupplier != null) {
      gpu.setNerGpuEnabled(nerGpuEnabledSupplier.get());
    }

    return gpu.build();
  }

  /**
   * {@code rag.chunk_splade.enabled} (tempdoc 931 §E item 8) — published on the status snapshot so
   * a consumer can tell "chunk SPLADE has no coverage because it is off" from "it is on and behind".
   * Absent config reads as the flag's own default (false).
   */
  private boolean chunkSpladeEnabled() {
    Supplier<io.justsearch.configuration.resolved.ResolvedConfig> supplier = resolvedConfigSupplier;
    io.justsearch.configuration.resolved.ResolvedConfig config =
        supplier == null ? null : supplier.get();
    return config != null && config.rag() != null && config.rag().chunkSpladeEnabled();
  }

  io.justsearch.ipc.SearchConfig buildSearchConfig() {
    io.justsearch.ipc.SearchConfig.Builder sc = io.justsearch.ipc.SearchConfig.newBuilder();
    if (resolvedConfigSupplier != null) {
      var config = resolvedConfigSupplier.get();
      if (config != null) {
        var search = config.search();
        var hybrid = config.hybridSearch();
        sc.setChunkAwareEnabled(search.chunkAwareEnabled())
            .setCcWeightSparse(hybrid.ccWeightSparse())
            .setCcWeightDense(hybrid.ccWeightDense())
            .setCcWeightSplade(hybrid.ccWeightSplade())
            .setBranchCcWeightWhole(hybrid.branchCcWeightWhole())
            .setBranchCcWeightChunk(hybrid.branchCcWeightChunk())
            .setBranchChunkMinWeightMultiplier(hybrid.branchChunkMinWeightMultiplier())
            .setTitleBoost(search.titleBoost())
            // Compatibility tombstone: field 9 remains on the wire but the retired query boost is
            // never active.
            .setEntityBoost(0.0)
            .setQueryClassificationEnabled(search.queryClassificationEnabled());
      }
    }
    return sc.build();
  }

  private static OrtCudaProbeResult buildOrtCudaProbe(OrtCudaStatus status) {
    OrtCudaProbeResult.Builder b =
        OrtCudaProbeResult.newBuilder()
            .setConfigured(status.configured())
            .setAttempted(status.attempted())
            .setAvailable(status.available())
            .setVariantId(status.variantId() != null ? status.variantId() : "")
            .setNativePath(
                status.nativePath() != null ? status.nativePath().toString() : "")
            .setFailureReason(
                status.failureReason() != null ? status.failureReason() : "");
    b.addAllMissingDlls(status.missingDlls());
    return b.build();
  }

  private VectorQuantization buildVectorQuantization() {
    VectorQuantization.Builder vq = VectorQuantization.newBuilder();
    if (configuredVectorFormat != null) {
      vq.setFormatConfig(configuredVectorFormat.get());
      vq.setFormatStored(storedVectorFormat.get());
      VectorFormatDetector.Summary vfSummary = queryVectorFormatActual.get();
      if (vfSummary != null) {
        vq.setFormatActual(vfSummary.overallState());
        vq.setSegmentsFloat32(vfSummary.float32Count());
        vq.setSegmentsQuantized(vfSummary.quantizedCount());
      }
    }
    return vq.build();
  }

  private TelemetryStatus buildTelemetry(long processingJobsCount) {
    metrics.throughputMonitor().recordSample(metrics.getDocumentsIndexed());
    OperationalMetrics.ThroughputMonitor.ThroughputResult throughput =
        metrics.throughputMonitor().compute(processingJobsCount);
    return TelemetryStatus.newBuilder()
        .setContentLengthAvgChars((long) metrics.getAverageContentLengthChars())
        .setContentLengthMinChars(metrics.getContentLengthMinChars())
        .setContentLengthMaxChars(metrics.getContentLengthMaxChars())
        .setThroughputDocsPerSec(throughput.docsPerSec())
        .setThroughputWindowState(throughput.state())
        .build();
  }

  private Map<String, String> safeCommitUserData() {
    if (latestCommitUserDataBestEffort == null) {
      return Map.of();
    }
    try {
      Map<String, String> ud = latestCommitUserDataBestEffort.get();
      return ud != null ? ud : Map.of();
    } catch (Exception e) {
      log.debug("Failed to get commit user data: {}", e.getMessage());
      return Map.of();
    }
  }

  /** 371: Returns the build stamp passed via system property at Worker launch. */
  private static String buildStamp() {
    String stamp = System.getProperty(
        io.justsearch.configuration.EnvRegistry.BUILD_STAMP.sysProp());
    return stamp != null ? stamp : "";
  }

  // ==================== Private helpers ====================

  /**
   * Returns the cached index size if the cache is fresh (within 30 seconds); otherwise triggers a
   * refresh and returns the newly computed value.
   */
  private long cachedIndexSizeIfFreshOrRefresh() {
    long now = System.currentTimeMillis();
    if (now - indexSizeObservedAtMs < INDEX_SIZE_CACHE_TTL_MS && indexSizeObservedAtMs > 0) {
      return cachedIndexSizeBytes;
    }
    refreshIndexSizeBestEffort();
    return cachedIndexSizeBytes;
  }

  /**
   * Refreshes the cached index size by walking the index directory.
   *
   * <p>Updates {@link #cachedIndexSizeBytes} and {@link #indexSizeObservedAtMs}. Called on the
   * first status RPC and subsequently whenever the cache is older than 30 seconds.
   */
  void refreshIndexSizeBestEffort() {
    if (indexPath == null || !Files.exists(indexPath)) {
      cachedIndexSizeBytes = 0L;
      indexSizeObservedAtMs = System.currentTimeMillis();
      return;
    }
    try {
      long size;
      try (Stream<Path> stream = Files.walk(indexPath)) {
        size =
          stream
              .filter(Files::isRegularFile)
              .mapToLong(
                  p -> {
                    try {
                      return Files.size(p);
                    } catch (IOException e) {
                      throw new java.io.UncheckedIOException(e);
                    }
                  })
              .sum();
      }
      cachedIndexSizeBytes = size;
      indexSizeObservedAtMs = System.currentTimeMillis();
    } catch (IOException | java.io.UncheckedIOException | java.nio.file.DirectoryIteratorException e) {
      log.debug("Failed to calculate index size: {}", e.getMessage());
      // Don't update indexSizeObservedAtMs on failure so the next call retries sooner.
    }
  }

  private int countPendingByStatus(String field, String value) {
    if (ingestCountOps == null) {
      return 0;
    }
    try {
      return ingestCountOps.countByField(field, value);
    } catch (Exception e) {
      log.debug("Failed to count {} = {}: {}", field, value, e.getMessage());
      return 0;
    }
  }

  private String safeEmbeddingFingerprintCurrent() {
    var controller = embeddingCompatController;
    if (controller == null) {
      return "";
    }
    String fp = controller.currentFingerprint();
    return fp == null ? "" : fp;
  }

  private String safeEmbeddingFingerprintStored() {
    var controller = embeddingCompatController;
    if (controller == null) {
      return "";
    }
    String fp = controller.storedFingerprint();
    return fp == null ? "" : fp;
  }

  /**
   * Returns the worker's embedding compatibility state, or {@code "INITIALIZING"} during the
   * boot window before {@code embeddingCompatController} is wired.
   *
   * <p>{@code INITIALIZING} is a deliberate sentinel, not an {@link
   * io.justsearch.indexerworker.embed.EmbeddingCompatibilityController.State} name — it means
   * "worker booting, controller not wired yet," distinct from the controller's own real states
   * (e.g. {@code UNAVAILABLE}, which means the controller ran and found no model). Previously this
   * returned {@code ""} for the null-controller case, which the 0.2.0 sandbox round observed and
   * misread as a broken/unknown state; the sentinel makes the boot window legible. Any consumer
   * that maps this state string by value (e.g. {@code WorkerSnapshotTap}) must carry a matching
   * {@code INITIALIZING} mapping so the boot window doesn't hit an unmapped-WARN branch.
   */
  private String safeEmbeddingCompatState() {
    var controller = embeddingCompatController;
    if (controller == null) {
      return "INITIALIZING";
    }
    var state = controller.state();
    return state == null ? "" : state.name();
  }

  /**
   * Returns the worker's embedding compatibility reason code, or {@code "CONTROLLER_NOT_WIRED"}
   * during the boot window before {@code embeddingCompatController} is wired — the reason-code
   * sibling of {@link #safeEmbeddingCompatState()}'s {@code INITIALIZING} sentinel.
   */
  private String safeEmbeddingCompatReason() {
    var controller = embeddingCompatController;
    if (controller == null) {
      return "CONTROLLER_NOT_WIRED";
    }
    String reason = controller.reasonCode();
    return reason == null ? "" : reason;
  }

  // ==================== Schema Compatibility Helpers (U21-MIG-001) ====================

  private String safeSchemaFingerprintCurrent() {
    Object fp = expectedCommitMetadataBestEffort().get(IndexFingerprint.COMMIT_META_KEY);
    return fp == null ? "" : String.valueOf(fp);
  }

  /** The metadata this runtime would commit, or an empty map if it cannot be built. */
  private Map<String, Object> expectedCommitMetadataBestEffort() {
    try {
      Supplier<Map<String, Object>> supplier = expectedCommitMetadataSupplier;
      return supplier == null ? new SsotCommitMetadataSource().build() : supplier.get();
    } catch (Exception e) {
      log.debug("Failed to build expected commit metadata: {}", e.getMessage());
      return Map.of();
    }
  }

  /**
   * Whole-document count (documents minus chunks) of {@code ops}. #758: a read
   * failure used to be counted as 0 documents — a status that told the user their index was empty
   * because a reader was briefly unavailable. A failed read now reports the last count this
   * process read successfully from the same reader. Before any successful read there is no count to
   * report and the answer is empty (unknown): the status then says so through {@code
   * doc_count_unknown} instead of a 0 that reads as an empty index.
   */
  private static java.util.OptionalLong parentDocCountOrLastKnown(
      IndexCountOps ops, java.util.concurrent.atomic.AtomicReference<CountReading> lastKnown,
      Map<String, io.justsearch.ipc.CountObservation> observations, String key) {
    try {
      long count =
          ops.parentDocCountOrThrow();
      CountReading reading = new CountReading(ops, count, System.currentTimeMillis());
      lastKnown.set(reading);
      observations.put(key, observation("fresh", reading));
      return java.util.OptionalLong.of(count);
    } catch (IOException | RuntimeException e) {
      CountReading previous = lastKnown.get();
      log.debug("Document count unreadable, reporting the last known count: {}", e.getMessage());
      boolean sameGeneration = previous != null && previous.ops() == ops;
      observations.put(key, observation(sameGeneration ? "stale" : "unknown", sameGeneration ? previous : null));
      return sameGeneration
          ? java.util.OptionalLong.of(previous.count())
          : java.util.OptionalLong.empty();
    }
  }

  /** A successful count, tied to the reader that produced it. */
  private record CountReading(IndexCountOps ops, long count, long observedAtMs) {}

  private static io.justsearch.ipc.CountObservation observation(String state, CountReading reading) {
    return io.justsearch.ipc.CountObservation.newBuilder().setState(state)
        .setObservedAtMs(reading == null ? 0 : reading.observedAtMs()).build();
  }

  private final java.util.concurrent.atomic.AtomicReference<CountReading> lastKnownSearchableDocCount =
      new java.util.concurrent.atomic.AtomicReference<>();

  private final java.util.concurrent.atomic.AtomicReference<CountReading> lastKnownActiveDocCount =
      new java.util.concurrent.atomic.AtomicReference<>();
  private final java.util.concurrent.atomic.AtomicReference<CountReading> lastKnownWrittenDocCount =
      new java.util.concurrent.atomic.AtomicReference<>();

  private String safeSchemaFingerprintStored() {
    String fp = storedCommitUserData().get(IndexFingerprint.COMMIT_META_KEY);
    return fp == null ? "" : fp;
  }

  /**
   * The commit user data the compat state is judged against, never null.
   *
   * <p>The open-time snapshot, because commits during the runtime's lifetime (NER backfill,
   * embedding rebuild) overwrite the stored fingerprint with the current one and would mask a
   * mismatch. Extracted so the {@code index_fingerprint_inputs} fallback reads the SAME snapshot
   * the digest does — two different reads of "what this index recorded" is how the guard and the
   * status surface end up describing different indexes.
   */
  private Map<String, String> storedCommitUserData() {
    return storedCommitUserDataOrUnknown().orElse(Map.of());
  }

  /**
   * As {@link #storedCommitUserData()}, but empty when the read FAILED (#758).
   * "The index recorded nothing" and "the record could not be read" get different answers: the
   * first may be a legacy index, the second is unknown.
   */
  private java.util.Optional<Map<String, String>> storedCommitUserDataOrUnknown() {
    if (openTimeCommitUserData == null) {
      return java.util.Optional.of(Map.of());
    }
    try {
      Map<String, String> ud = openTimeCommitUserData.get();
      if (ud == null || ud.isEmpty()) {
        ud = latestCommitUserDataBestEffort.get();
      }
      return java.util.Optional.of(ud == null ? Map.of() : ud);
    } catch (Exception e) {
      log.debug("Failed to read stored commit metadata: {}", e.getMessage());
      return java.util.Optional.empty();
    }
  }

  /**
   * The live document count, or empty when it cannot be read. Empty is "unknown", and the caller
   * answers unknown for it: it never reaches the {@code docCount <= 0} emptiness predicate, which
   * would read it as an index holding nothing to migrate.
   */
  private java.util.OptionalLong docCountOrUnknown() {
    // Deferred opens have no writer count; their reader still establishes whether content exists.
    // Keep the existing writer count source once a writer is present, including during migration.
    IndexCountOps counts = ingestCountOps != null ? ingestCountOps : searchCountOps;
    if (counts == null) {
      return java.util.OptionalLong.empty();
    }
    try {
      return java.util.OptionalLong.of(counts.docCountOrThrow());
    } catch (IOException | RuntimeException e) {
      log.debug("Failed to read the document count: {}", e.getMessage());
      return java.util.OptionalLong.empty();
    }
  }

  /**
   * The schema compatibility state the status surface reports.
   *
   * <p>#758: every answer below is one the inputs can support. When the stored
   * commit metadata cannot be read, when what it recorded cannot be parsed, or when the document
   * count cannot be read and the answer depends on it, the state is {@code UNAVAILABLE} (unknown):
   * never {@code COMPATIBLE}, and never a {@code BLOCKED_*} state that tells the user to rebuild.
   * The count used to come from {@code docCount()}, which reads an I/O failure as 0 — and 0 is the
   * "holds nothing to migrate" answer, so a mismatched index with documents read as compatible.
   */
  private String safeSchemaCompatState() {
    if (bootRebuildHold() != null) {
      // #758: this boot stopped an automatic rebuild, and with it
      // ingestion, for the rest of the boot. Reported first and for the whole boot, so the status
      // agrees with what the Worker does even when the brake record or the index later reads as
      // if nothing were held (a state.json that becomes readable, a compatible Blue under a held
      // Green, a target that cannot be computed).
      return "BLOCKED_REBUILD_BRAKE";
    }
    String current = safeSchemaFingerprintCurrent();

    if (current.isEmpty()) {
      // This runtime could not compute a truthful fingerprint (a configured model's digest was
      // unresolvable). UNAVAILABLE, never COMPATIBLE — an absent answer is not a clean bill.
      return "UNAVAILABLE";
    }
    IndexGenerationManager.AutoRebuildBrake brake = autoRebuildBrake(current);
    if (brake == IndexGenerationManager.AutoRebuildBrake.EXHAUSTED
        || brake == IndexGenerationManager.AutoRebuildBrake.UNREADABLE) {
      // The Worker has stopped rebuilding this shape by itself. Reported ahead of the plain
      // mismatch because the remedy is different: waiting will not fix it. #758:
      // a brake record for this shape whose count cannot be read holds exactly like a spent one.
      return "BLOCKED_REBUILD_BRAKE";
    }
    String state = schemaCompatStateIgnoringBrake(current);
    if ((brake == IndexGenerationManager.AutoRebuildBrake.STATE_UNREADABLE
            || brake == IndexGenerationManager.AutoRebuildBrake.STATE_LOST)
        && ("BLOCKED_MISMATCH".equals(state) || "BLOCKED_LEGACY".equals(state))) {
      // #758: the state record holding the brake cannot be read, and a brake that
      // cannot be read holds, so an index that needs a rebuild is not being rebuilt automatically.
      // Said only where a rebuild is called for: nothing records one for a compatible or unknown index.
      return "BLOCKED_REBUILD_BRAKE";
    }
    return state;
  }

  /** {@link #safeSchemaCompatState()} before the repeat-rebuild brake is consulted. */
  private String schemaCompatStateIgnoringBrake(String current) {
    java.util.Optional<Map<String, String>> recorded = storedCommitUserDataOrUnknown();
    if (recorded.isEmpty()) {
      return "UNAVAILABLE";
    }
    Map<String, String> ud = recorded.get();
    String storedRaw = ud.get(IndexFingerprint.COMMIT_META_KEY);
    String stored = storedRaw == null ? "" : storedRaw;
    if (!stored.isEmpty() && current.equals(stored)) {
      return "COMPATIBLE";
    }
    Map<String, Object> expected = expectedCommitMetadataBestEffort();
    if (ParityDiagnostics.storedShapeUnreadable(ud, expected)) {
      return "UNAVAILABLE";
    }
    java.util.OptionalLong readCount = docCountOrUnknown();
    if (readCount.isEmpty()) {
      return "UNAVAILABLE";
    }
    long docCount = readCount.getAsLong();
    if (stored.isEmpty()) {
      // No recorded DIGEST. Two different indexes look like this and they get different answers
      // (tempdoc 931 §C.5): one that predates index_fingerprint_inputs recorded no shape at all and
      // is migrated once; one committed while a model digest was unresolvable recorded its shape as
      // inputs, and the guard compares those rather than charging it a rebuild. The same predicate
      // and the same diff the open-time guard runs decide both here — one rule, two consumers, so
      // the banner cannot demand a reindex the guard is not performing.
      if (ParityDiagnostics.isIndexWithoutRecordedFingerprint(
          stored, ud.get(IndexFingerprint.COMMIT_META_INPUTS_KEY), docCount)) {
        return "BLOCKED_LEGACY";
      }
    }
    // The guard also proves explicitly compatible additions from the recorded inputs. A raw
    // digest comparison here would tell users to rebuild the very release index it opens writable.
    return ParityDiagnostics.requiresRebuild(ParityDiagnostics.diff(ud, expected, docCount))
        ? "BLOCKED_MISMATCH" : "COMPATIBLE";
  }

  /**
   * The repeat-rebuild brake for the current target shape. #758: a failed read is
   * {@code UNREADABLE}, never "no brake" — the answer used to be {@code false} there, so the status
   * described an index the Worker would not rebuild as one that resolves itself.
   */
  private IndexGenerationManager.AutoRebuildBrake autoRebuildBrake(String currentFingerprint) {
    if (indexGenerationManager == null || currentFingerprint.isEmpty()) {
      return IndexGenerationManager.AutoRebuildBrake.RELEASED;
    }
    try {
      return indexGenerationManager.autoRebuildBrakeFor(currentFingerprint);
    } catch (RuntimeException e) {
      log.debug("Failed to read the auto-rebuild brake: {}", e.getMessage());
      return IndexGenerationManager.AutoRebuildBrake.STATE_UNREADABLE;
    }
  }

  private boolean isReindexRequired() {
    String schemaState = safeSchemaCompatState();
    String embeddingState = safeEmbeddingCompatState();
    return "BLOCKED_MISMATCH".equals(schemaState)
        || "BLOCKED_REBUILD_BRAKE".equals(schemaState)
        || "BLOCKED_LEGACY".equals(schemaState)
        || "BLOCKED_MISMATCH".equals(embeddingState)
        || "BLOCKED_LEGACY".equals(embeddingState);
  }

  private String reindexRequiredReason() {
    String schemaState = safeSchemaCompatState();
    if ("BLOCKED_REBUILD_BRAKE".equals(schemaState)) {
      IndexGenerationManager.AutoRebuildBrake held = bootRebuildHold();
      return brakeReason(held != null ? held : autoRebuildBrake(safeSchemaFingerprintCurrent()));
    }
    if ("BLOCKED_MISMATCH".equals(schemaState)) {
      return "schema_mismatch";
    }
    if ("BLOCKED_LEGACY".equals(schemaState)) {
      return "legacy_index";
    }
    String embeddingState = safeEmbeddingCompatState();
    if ("BLOCKED_MISMATCH".equals(embeddingState)) {
      return "embedding_mismatch";
    }
    if ("BLOCKED_LEGACY".equals(embeddingState)) {
      return "embedding_legacy";
    }
    return "";
  }

  /**
   * The wire reason for a holding brake. A lost state record is still an unreadable brake (the Head
   * shows {@code index.rebuild_brake_unreadable}) but carries its own detail, so a person can tell
   * "the record could not be read" from "the record was gone and had to be re-established".
   */
  private static String brakeReason(IndexGenerationManager.AutoRebuildBrake brake) {
    if (brake == IndexGenerationManager.AutoRebuildBrake.STATE_LOST) {
      return "rebuild_brake_state_lost";
    }
    return brake.unreadable() ? "rebuild_brake_unreadable" : "rebuild_brake_exhausted";
  }

  private int countPendingVduDemand(String demandKind) {
    try {
      if (ingestCountOps == null) return 0;
      return ingestCountOps.countByFields(
          Map.of(
              SchemaFields.VDU_STATUS,
              SchemaFields.VDU_STATUS_PENDING,
              SchemaFields.VDU_DEMAND_KIND,
              demandKind));
    } catch (Exception e) {
      return 0;
    }
  }

  private int countPendingBaselineVduDemand(int visualEnrichmentNeededCount) {
    int totalPending = countPendingByStatus(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING);
    return Math.max(0, totalPending - Math.max(0, visualEnrichmentNeededCount));
  }
}
