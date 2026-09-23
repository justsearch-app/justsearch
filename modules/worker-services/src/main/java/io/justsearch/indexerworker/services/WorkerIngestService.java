/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.IngestResponses.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.SwapReason;
import io.justsearch.app.api.operations.AppliedIndexGeneration;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.ipc.logging.MdcContext;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.DeleteByCollectionRequest;
import io.justsearch.ipc.DeleteByCollectionResponse;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.QueryPendingVduResponse;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.MarkVduProcessingResponse;
import io.justsearch.ipc.MigrationCutoverRequest;
import io.justsearch.ipc.MigrationCutoverResponse;
import io.justsearch.ipc.MigrationPauseRequest;
import io.justsearch.ipc.MigrationPauseResponse;
import io.justsearch.ipc.MigrationRollbackRequest;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationResumeRequest;
import io.justsearch.ipc.MigrationResumeResponse;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import io.justsearch.ipc.FailedJob;
import io.justsearch.ipc.IndexGcRequest;
import io.justsearch.ipc.IndexGcResponse;
import io.justsearch.ipc.ListFailedJobsRequest;
import io.justsearch.ipc.ListFailedJobsResponse;
import io.justsearch.ipc.ClearFailedJobsRequest;
import io.justsearch.ipc.ClearFailedJobsResponse;
import io.justsearch.ipc.ResetIndexRequest;
import io.justsearch.ipc.ResetIndexResponse;
import io.justsearch.ipc.SettleIndexRequest;
import io.justsearch.ipc.SettleIndexResponse;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.UpdatePathsRequest;
import io.justsearch.ipc.UpdatePathsResponse;
import io.justsearch.ipc.UpgradeQuiescenceRequest;
import io.justsearch.ipc.UpgradeQuiescenceResponse;
import io.justsearch.ipc.PathMapping;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.IndexingJobChangeFeed;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.util.ParseUtils;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ort.OrtCudaStatus;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingest service implementation for batch file ingestion.
 *
 * <p>Receives file paths from the main process and enqueues them for indexing.
 * The actual indexing is performed asynchronously by the IndexingLoop.
 *
 * <p>Lane F stage A item A3: this is a plain service — it returns its response object and reports
 * failure by throwing {@link WorkerServiceException}. While the wire is still up (through A9) it is
 * reached, until item A9 deleted it, through a wire adapter that did the transport framing and
 * maps each failure back onto the identical status code.
 *
 * <p><b>Security:</b> All paths are validated and sanitized before processing.
 * <p><b>Rate Limiting:</b> Batch size is capped to prevent queue flooding.
 */
public final class WorkerIngestService {
  private static final Logger log = LoggerFactory.getLogger(WorkerIngestService.class);
  /** Maximum files allowed in a single batch request. */
  private static final int MAX_BATCH_SIZE = 10_000;

  /** Maximum queue depth before rejecting new submissions. */
  private static final long MAX_QUEUE_DEPTH = 100_000;

  private static final tools.jackson.databind.ObjectMapper COMMITTED_INPUTS_JSON =
      tools.jackson.databind.json.JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  /** Opens an MDC scope with the trace_id and request_id the caller propagated. */
  private MdcContext openRequestMdc(
      CallContext ctx) { // NOPMD - AutoCloseable for logging context side-effect
    return MdcContext.request(ctx.traceId(), ctx.requestId());
  }

  /** Dangerous path components that indicate traversal attempts. */
  private static final Set<String> DANGEROUS_COMPONENTS = Set.of("..", "..\\", "../");

  private final JobQueue jobQueue;
  private final IndexingLoop indexingLoop;
  /** Tempdoc 885 item 3: foreground-contention duty cycle for the prune / sync walks. */
  private final IndexingPacing indexingPacing;
  private final io.justsearch.adapters.lucene.runtime.RunningRuntime ingestLifecycle;
  private final IndexGenerationManager indexGenerationManager;
  private final boolean ingestIsServing;
  private final boolean hasServingRuntime;
  private final io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle;
  private final Path capturedServingPath;
  private final OperationalMetrics metrics = OperationalMetrics.getInstance();
  private final IndexStatusOps statusOps;
  private final SyncDirectoryOps syncOps;
  private final IngestSwitchBufferOps switchBufferOps;
  private WorkerMutationAdmission mutationAdmission;
  private Object mutationOwner;
  private final MigrationControlOps migrationOps;
  private final WorkerUpgradeQuiescence upgradeQuiescence;
  private final IndexSettleOps settleOps;
  private RootWatcherRegistry rootWatcherRegistry = new RootWatcherRegistry();

  // Tempdoc 419 / T5.3 (ADR-0028): scoped reverse-lookup store. Defaults to NOOP so any
  // composition that hasn't wired it sees found=false on every lookup. DefaultWorkerAppServices
  // injects the real SqlitePathResolutionStore via setPathResolutionStore at boot.
  private io.justsearch.indexerworker.path.PathResolutionStore pathResolutionStore =
      io.justsearch.indexerworker.path.PathResolutionStore.NOOP;
  private io.justsearch.indexerworker.identity.DocumentIdentityStore documentIdentityStore =
      io.justsearch.indexerworker.identity.DocumentIdentityStore.UNAVAILABLE;

  private static final String VDU_MAX_RETRIES_EXCEEDED_ERROR = "Max retries exceeded";
  private static final String VDU_MAX_RETRIES_EXCEEDED_ENRICHMENT =
      "{\"error\": \"Max retries exceeded\"}";
  private static final int RECOVER_VDU_QUERY_LIMIT = 1000;

  /**
   * Creates a new WorkerIngestService backed by the specified lifecycle managers.
   *
   * @param jobQueue The job queue for persisting ingest jobs
   * @param indexingLoop The indexing loop for commit status
   * @param signalBus The signal bus for coordination metrics
   * @param indexingPacing The foreground-contention duty cycle (tempdoc 885 item 3)
   * @param indexPath The path to the Lucene index directory (for size calculation)
   * @param ingestLifecycle The lifecycle manager for write/mutation operations
   * @param searchLifecycle The lifecycle manager for search/status reads
   */
  public WorkerIngestService(
      JobQueue jobQueue,
      IndexingLoop indexingLoop,
      WorkerSignalBus signalBus,
      IndexingPacing indexingPacing,
      Path indexBasePath,
      Path indexPath,
      io.justsearch.adapters.lucene.runtime.RunningRuntime ingestLifecycle,
      io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle,
      Supplier<MigrationProgressSnapshot> migrationProgressSupplier,
      long migrationSwitchingMaxDurationMs) {
    this.jobQueue = jobQueue;
    this.indexingLoop = indexingLoop;
    this.indexingPacing =
        java.util.Objects.requireNonNull(indexingPacing, "indexingPacing");
    this.ingestLifecycle = ingestLifecycle;
    this.ingestIsServing = ingestLifecycle != null && ingestLifecycle == searchLifecycle;
    this.hasServingRuntime = searchLifecycle != null;
    this.searchLifecycle = searchLifecycle;
    this.capturedServingPath = indexPath;
    this.indexGenerationManager = indexBasePath == null ? null : new IndexGenerationManager(indexBasePath);
    this.migrationOps = new MigrationControlOps(this.indexGenerationManager);
    this.upgradeQuiescence =
        new WorkerUpgradeQuiescence(jobQueue, indexingLoop, this.indexGenerationManager);
    this.settleOps =
        new IndexSettleOps(ingestLifecycle, this.indexGenerationManager, this.upgradeQuiescence);
    io.justsearch.adapters.lucene.runtime.IndexCountOps ingestCountOps =
        ingestLifecycle != null ? ingestLifecycle.indexCountOps() : null;
    io.justsearch.adapters.lucene.runtime.IndexCountOps searchCountOps =
        searchLifecycle != null ? searchLifecycle.indexCountOps() : null;
    this.statusOps =
        new IndexStatusOps(
            jobQueue,
            indexPath,
            ingestCountOps,
            searchCountOps,
            ingestLifecycle != null ? ingestLifecycle::configuredVectorFormat : null,
            // Everything STORED — the committed identity of the index — describes the generation the
            // user's queries reach, so it is read off the SEARCH runtime. Off the ingest runtime it
            // described Green during a migration (freshly stamped with the CURRENT shape, so the
            // compat surface reported COMPATIBLE while every query was answered from the stale-shape
            // Blue), and nothing at all in the exhausted-brake and deferred-open states, where there
            // is no write runtime and the supplier was null (so the stored fingerprint came back
            // empty — which the status path reads as BLOCKED_LEGACY, the wrong remedy). The search
            // runtime is non-null in every one of those states. Live validation, 2026-09-03, D1/D3.
            searchLifecycle != null ? searchLifecycle::storedVectorFormat : null,
            ingestLifecycle != null ? ingestLifecycle::queryVectorFormatActual : null,
            searchLifecycle != null ? searchLifecycle::openTimeCommitUserData : null,
            searchLifecycle != null ? searchLifecycle::latestCommitUserDataBestEffort : null,
            this.indexGenerationManager,
            migrationProgressSupplier,
            metrics,
            indexingLoop,
            signalBus,
            migrationSwitchingMaxDurationMs);
    this.syncOps = new SyncDirectoryOps(
        ingestLifecycle != null ? ingestLifecycle.readPathOps() : null,
        ingestLifecycle != null ? ingestLifecycle.pruneOps() : null,
        ingestLifecycle != null ? ingestLifecycle.commitOps() : null,
        jobQueue,
        this.indexingPacing,
        // Read through a supplier: the identity store is wired by setDocumentIdentityStore AFTER
        // this constructor runs, so capturing the field here would capture the UNAVAILABLE sentinel.
        new ConfirmedDeletionMarker(() -> this.documentIdentityStore));
    this.switchBufferOps =
        new IngestSwitchBufferOps(jobQueue, this.indexGenerationManager, metrics);
  }

  /** Bound once by the composing Worker owner before any request is published. */
  public void setMutationAdmission(WorkerMutationAdmission admission, Object owner) {
    if (mutationAdmission != null) throw new IllegalStateException("Mutation admission already bound");
    mutationAdmission = java.util.Objects.requireNonNull(admission, "admission");
    mutationOwner = java.util.Objects.requireNonNull(owner, "owner");
  }

  private WorkerMutationAdmission.Lease mutationLease() {
    if (mutationAdmission == null) return null; // isolated service fixtures
    try { return mutationAdmission.enter(mutationOwner); }
    catch (IllegalStateException retired) {
      throw WorkerServiceException.unavailable("Mutation producer belongs to a retired generation");
    }
  }

  /** Watcher events share RPC mutation admission and the existing durable switch buffer. */
  public void acceptWatcherUpsert(String collection, Path path) {
    try (var ignoredMutation = mutationLease()) {
      if (switchBufferOps.isSwitching()) {
        if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
          switchBufferOps.bufferSubmitBatchDuringSwitching(sbq, List.of(path), 1, 0,
              collection, CallContext.none().provenance());
          return;
        }
        throw IngestSwitchBufferOps.switchingUnavailable();
      }
      jobQueue.enqueueEntries(List.of(WorkerMethvinWatcher.entryForLiveEvent(path)), collection);
    }
  }

  /** The watcher owns its deletion marker; this method owns its routing and effect fence. */
  public void acceptWatcherDelete(String normalizedPath, Runnable directEffect) {
    java.util.Objects.requireNonNull(directEffect, "directEffect");
    try (var ignoredMutation = mutationLease()) {
      if (switchBufferOps.isSwitching()) {
        if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
          switchBufferOps.bufferDeleteByIdDuringSwitching(sbq, normalizedPath);
          return;
        }
        throw IngestSwitchBufferOps.switchingUnavailable();
      }
      directEffect.run();
    }
  }

  public UpgradeQuiescenceResponse prepareUpgrade(
      UpgradeQuiescenceRequest request, CallContext ctx) {
    return respondUpgrade(
        () -> upgradeQuiescence.prepare(request == null ? "" : request.getPreparationId()));
  }

  public UpgradeQuiescenceResponse upgradeStatus(
      UpgradeQuiescenceRequest request, CallContext ctx) {
    return respondUpgrade(
        () -> upgradeQuiescence.status(request == null ? "" : request.getPreparationId()));
  }

  public UpgradeQuiescenceResponse cancelUpgrade(
      UpgradeQuiescenceRequest request, CallContext ctx) {
    return respondUpgrade(
        () -> upgradeQuiescence.cancel(request == null ? "" : request.getPreparationId()));
  }

  private static UpgradeQuiescenceResponse respondUpgrade(
      Supplier<UpgradeQuiescenceResponse> action) {
    try {
      return action.get();
    } catch (RuntimeException e) {
      throw WorkerServiceException.failedPrecondition(e.getMessage());
    }
  }

  /**
   * Tempdoc 400 §22 Issue D / LR2-e.4 (Phase 6 / 6.7): expose a best-
   * effort supplier for the active Lucene IndexSearcher generation so
   * {@link SearchOrchestrator} can stamp {@code search.searcher_generation}
   * on every {@code search/retrieval} span. Null-safe — returns null
   * when the generation manager is unwired (tests, early-boot).
   */
  public Supplier<String> activeGenerationSupplier() {
    return () -> {
      if (indexGenerationManager == null) {
        return null;
      }
      IndexGenerationManager.State state = indexGenerationManager.readStateBestEffort();
      return state == null ? null : state.active_generation();
    };
  }

  /**
   * Sets the embedding compatibility controller for status reporting.
   *
   * @param controller the embedding compatibility controller
   */
  public void setEmbeddingCompatController(
      io.justsearch.indexerworker.embed.EmbeddingCompatibilityController controller) {
    statusOps.setEmbeddingCompatController(controller);
  }

  /**
   * F1: Sets the ORT CUDA status supplier for observability.
   *
   * @param supplier supplier that returns the current ORT CUDA status
   */
  public void setOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setOrtCudaStatusSupplier(supplier);
  }

  /** Sets the SPLADE encoder ORT CUDA status supplier. */
  public void setSpladeOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setSpladeOrtCudaStatusSupplier(supplier);
  }

  /** Sets the embedding encoder ORT CUDA status supplier. */
  public void setEmbedOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setEmbedOrtCudaStatusSupplier(supplier);
  }

  /** Tempdoc 422 — sets the NER encoder ORT CUDA status supplier. */
  public void setNerOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setNerOrtCudaStatusSupplier(supplier);
  }

  /** Tempdoc 422 — sets the citation-scorer encoder ORT CUDA status supplier. */
  public void setCitationOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setCitationOrtCudaStatusSupplier(supplier);
  }

  /** Tempdoc 422 — sets the BGE-M3 encoder ORT CUDA status supplier. */
  public void setBgeM3OrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setBgeM3OrtCudaStatusSupplier(supplier);
  }

  /** Sets the embedding backend ID supplier (e.g. "onnx"). */
  public void setEmbedBackendSupplier(Supplier<String> supplier) {
    statusOps.setEmbedBackendSupplier(supplier);
  }

  /** Tempdoc 406 — wires the swap-aware runtime gauges supplier for /api/status. */
  public void setRuntimeGaugesSupplier(
      Supplier<io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeGaugesSnapshot>
          supplier) {
    statusOps.setRuntimeGaugesSupplier(supplier);
  }

  /**
   * Tempdoc 419 C3 V1 — wires the worker's RRD store so {@code IndexStatusOps.buildCore} can
   * backfill the recent-job-queue-depth trend.
   */
  public void setRrdStoreSupplier(Supplier<io.justsearch.telemetry.RrdMetricStore> supplier) {
    statusOps.setRrdStoreSupplier(supplier);
  }

  /**
   * Tempdoc 406 — wires the runtime reload trigger. Invoked by the {@code ReloadRuntime}
   * RPC; returns swap duration in ms. {@code null} (default) means reload is unavailable
   * and the RPC will return {@code FAILED_PRECONDITION}.
   */
  private volatile java.util.function.Function<SwapReason, Long> runtimeReloadTrigger;

  public void setRuntimeReloadTrigger(java.util.function.Function<SwapReason, Long> trigger) {
    this.runtimeReloadTrigger = trigger;
  }

  /**
   * Sets which enrichment stages are enabled. Called once at wire time from
   * {@code DefaultWorkerAppServices}. Flows through to the status endpoint so
   * consumers (e.g. jseval readiness polling) can skip coverage checks for
   * disabled stages. See tempdoc 394 / commit {@code c4e677f63}.
   */
  public void setStageEnabled(boolean embedding, boolean splade, boolean ner) {
    statusOps.setStageEnabled(embedding, splade, ner);
  }

  /** Sets the embedding GPU layers supplier. */
  public void setEmbedGpuLayersSupplier(Supplier<Integer> supplier) {
    statusOps.setEmbedGpuLayersSupplier(supplier);
  }

  /** Sets the SPLADE model path supplier. */
  public void setSpladeModelPathSupplier(Supplier<String> supplier) {
    statusOps.setSpladeModelPathSupplier(supplier);
  }

  /** Sets the reranker model path supplier. */
  public void setRerankerModelPathSupplier(Supplier<String> supplier) {
    statusOps.setRerankerModelPathSupplier(supplier);
  }

  /** Sets the NER model path supplier. */
  public void setNerModelPathSupplier(Supplier<String> supplier) {
    statusOps.setNerModelPathSupplier(supplier);
  }

  /** Sets the NER GPU enabled supplier. */
  public void setNerGpuEnabledSupplier(Supplier<Boolean> supplier) {
    statusOps.setNerGpuEnabledSupplier(supplier);
  }

  /**
   * Sets the {@link io.justsearch.ort.PolicySnapshot} supplier for the
   * {@code GetSessionPolicies} rpc (tempdoc 397 §14.28 U4). Returns Worker's authoritative
   * snapshot; Head's re-resolve path in {@code SessionPoliciesController} is deleted in favour
   * of reading this via gRPC.
   */
  public void setPolicySnapshotSupplier(Supplier<io.justsearch.ort.PolicySnapshot> supplier) {
    this.policySnapshotSupplier = supplier;
  }

  private volatile Supplier<io.justsearch.ort.PolicySnapshot> policySnapshotSupplier;

  /** Sets the resolved config supplier for search config status reporting (343). */
  public void setResolvedConfigSupplier(
      Supplier<io.justsearch.configuration.resolved.ResolvedConfig> supplier) {
    statusOps.setResolvedConfigSupplier(supplier);
    this.resolvedConfigSupplier = supplier;
  }

  // Tempdoc 931 §E item 8: kept here too (not only forwarded to statusOps) so the VDU chunk
  // regeneration path can read rag.chunk_splade.enabled from the LIVE config on every write.
  private volatile Supplier<io.justsearch.configuration.resolved.ResolvedConfig>
      resolvedConfigSupplier;

  /** {@code rag.chunk_splade.enabled}; absent config reads as the flag's own default (false). */
  private boolean chunkSpladeEnabled() {
    Supplier<io.justsearch.configuration.resolved.ResolvedConfig> supplier = resolvedConfigSupplier;
    io.justsearch.configuration.resolved.ResolvedConfig config =
        supplier == null ? null : supplier.get();
    return config != null && config.rag() != null && config.rag().chunkSpladeEnabled();
  }

  /**
   * The "index runtime not available" response for {@code context}, or {@code null} when the
   * runtime IS available and the caller should carry on.
   */
  private <T> T indexRuntimeUnavailableReply(String context, T unavailableResponse) {
    if (ingestLifecycle != null) {
      return null;
    }
    log.error("{} failed: ingestLifecycle is null", context);
    return unavailableResponse;
  }

  /** The validation response when {@code value} is blank, else {@code null}. */
  private static <T> T blankReply(String value, T validationResponse) {
    if (value != null && !value.isBlank()) {
      return null;
    }
    return validationResponse;
  }

  public MigrationStartResponse startMigration(MigrationStartRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.startMigration(request);
    }
  }

  public MigrationCutoverResponse requestCutover(
      MigrationCutoverRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.requestCutover(request);
    }
  }

  public MigrationPauseResponse pauseMigration(MigrationPauseRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.pauseMigration(request);
    }
  }

  public MigrationResumeResponse resumeMigration(MigrationResumeRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.resumeMigration(request);
    }
  }

  public MigrationRollbackResponse rollbackMigration(
      MigrationRollbackRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.rollbackMigration(request);
    }
  }

  public IndexGcResponse runIndexGc(IndexGcRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.runIndexGc(request);
    }
  }

  public SettleIndexResponse settleIndex(SettleIndexRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return settleOps.settleIndex(request);
    }
  }

  public BatchResponse submitBatch(BatchRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      List<String> filePaths = request.getFilePathsList();

      // Validate: empty batch
      if (filePaths.isEmpty()) {
        log.debug("Received empty batch request");
        return batchSuccessResponse(0);
      }

      // Rate limit: batch size
      if (filePaths.size() > MAX_BATCH_SIZE) {
        metrics.recordBatchRejected();
        log.warn("Batch too large: {} files (max {})", filePaths.size(), MAX_BATCH_SIZE);
        throw WorkerServiceException.invalidArgument(
            "Batch size " + filePaths.size() + " exceeds maximum " + MAX_BATCH_SIZE);
      }

      // Backpressure: check queue depth
      long currentDepth = jobQueue.queueDepth();
      if (currentDepth >= MAX_QUEUE_DEPTH) {
        metrics.recordBatchRejected();
        log.warn("Queue full: depth {} (max {})", currentDepth, MAX_QUEUE_DEPTH);
        throw WorkerServiceException.resourceExhausted(
            "Queue depth " + currentDepth + " exceeds maximum " + MAX_QUEUE_DEPTH);
      }

      log.debug("Received batch request with {} files", filePaths.size());

      try {
        // Sanitize and validate paths
        List<Path> validPaths = new ArrayList<>(filePaths.size());
        int rejected = 0;

        for (String pathStr : filePaths) {
          Path sanitized = sanitizePath(pathStr);
          if (sanitized != null) {
            validPaths.add(sanitized);
          } else {
            rejected++;
          }
        }

        if (rejected > 0) {
          log.warn("Rejected {} invalid/unsafe paths", rejected);
        }

        if (validPaths.isEmpty()) {
          return batchErrorResponse("All paths were invalid or unsafe");
        }

        // Force bypasses unchanged extraction for selected files. It cannot attest to untouched
        // vectors: whole-index legacy recovery or generation migration owns compatibility.

        // During cutover (SWITCHING), accept the request but buffer it durably instead of
        // mutating the job queue/index directly. This avoids dropping updates while the Worker
        // restarts.
        if (switchBufferOps.isSwitching()) {
          if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
            return switchBufferOps.bufferSubmitBatchDuringSwitching(
                sbq, validPaths, filePaths.size(), rejected, request.getTargetCollection(), ctx.provenance());
          }
          throw IngestSwitchBufferOps.switchingUnavailable();
        }

        String collection = request.getTargetCollection();
        if (collection != null && collection.isBlank()) {
          collection = null;
        }
        // 813 Slice B: stat each admitted path for its byte size. A stat failure degrades that
        // entry to unknown size (NULL) — it never rejects the enqueue.
        int accepted =
            jobQueue.enqueueEntries(
                validPaths.stream().map(path -> JobQueue.EnqueueEntry.stat(path, ctx.provenance())).toList(), collection);

        // Mark paths for force reindex if requested (bypasses "unchanged" check).
        // The key must be the one JobBatchExtractor looks the forced set up by — the envelope's —
        // so it is derived through the shared PathNormalizer#normalizeKey rather than re-spelled
        // here. sanitizePath currently keeps a `..` or relative shape from ever reaching this line,
        // which is what made the old hand-rolled derivation agree by accident; that agreement is now
        // structural and survives a change to either side (tempdoc 821 §P/P3).
        if (request.getForceReindex() && accepted > 0) {
          List<String> normalizedPaths =
              validPaths.stream().map(PathNormalizer::normalizeKey).toList();
          indexingLoop.markForced(normalizedPaths);
          log.info("Marked {} paths for force reindex", normalizedPaths.size());
        }

        // Record metrics
        metrics.recordBatchSubmitted(accepted);
        metrics.setQueueDepth(jobQueue.queueDepth());

        log.info("Accepted {} of {} files for indexing (rejected {})",
            accepted, filePaths.size(), rejected);

        return batchSuccessResponse(accepted);

      } catch (WorkerServiceException e) {
        // Must precede the IllegalStateException / RuntimeException catches below: the SWITCHING
        // branch above throws UNAVAILABLE ("Switch buffer write failed..." / "Migration is
        // switching...") from inside this try, and the catch-all would silently degrade it to
        // INTERNAL "Batch processing failed: ..." — a different wire status AND a different
        // message for the Head's retry logic.
        throw e;
      } catch (IllegalStateException e) {
        log.error("Queue error", e);
        throw WorkerServiceException.internal("Queue error: " + e.getMessage());
      } catch (InvalidPathException e) {
        log.error("Invalid path", e);
        throw WorkerServiceException.invalidArgument("Invalid path: " + e.getMessage());
      } catch (RuntimeException e) {
        log.error("Failed to process batch request", e);
        throw WorkerServiceException.internal("Batch processing failed: " + e.getMessage());
      }
    }
  }

  /**
   * Sanitizes and validates a file path.
   *
   * <p>Security checks:
   * <ul>
   *   <li>Rejects null/blank paths</li>
   *   <li>Rejects paths containing ".." (traversal attempts)</li>
   *   <li>Normalizes the path to resolve any symbolic links</li>
   *   <li>Requires the path to be absolute</li>
   *   <li>Requires the file to exist (prevents indexing non-existent files)</li>
   * </ul>
   *
   * @param pathStr the raw path string from the client
   * @return sanitized Path, or null if invalid/unsafe
   */
  private Path sanitizePath(String pathStr) {
    if (pathStr == null || pathStr.isBlank()) {
      return null;
    }

    // Check for traversal attempts in raw string
    for (String dangerous : DANGEROUS_COMPONENTS) {
      if (pathStr.contains(dangerous)) {
        log.warn("Rejected path with traversal attempt: {}", pathStr);
        return null;
      }
    }

    try {
      Path path = Path.of(pathStr);

      // Normalize to resolve any remaining oddities
      Path normalized = path.normalize();

      // After normalization, double-check for traversal
      if (normalized.toString().contains("..")) {
        log.warn("Rejected normalized path with traversal: {}", normalized);
        return null;
      }

      // Must be absolute path
      if (!normalized.isAbsolute()) {
        log.debug("Rejected relative path: {}", pathStr);
        return null;
      }

      // File must exist
      if (!Files.exists(normalized)) {
        log.debug("File does not exist: {}", normalized);
        return null;
      }

      // File must be readable
      if (!Files.isReadable(normalized)) {
        log.debug("File not readable: {}", normalized);
        return null;
      }

      return normalized;

    } catch (InvalidPathException e) {
      log.warn("Invalid path syntax: {}", pathStr);
      return null;
    }
  }

  public StatusResponse indexStatus(StatusRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      try {
        return statusOps.buildStatusResponse();
      } catch (RuntimeException e) {
        log.error("Failed to get index status", e);
        return StatusResponse.newBuilder()
            .setCore(
                io.justsearch.ipc.CoreStatus.newBuilder()
                    .setIsHealthy(false)
                    .setState("ERROR")
                    .build())
            .build();
      }
    }
  }

  private void requireServingVduTarget() {
    try {
      if (ingestIsServing && (indexGenerationManager == null
          || indexGenerationManager.isIdleActiveGeneration(capturedServingPath))) return;
    } catch (java.io.IOException | RuntimeException failure) {
      throw new WorkerServiceException(WorkerServiceException.Status.UNAVAILABLE,
          "VDU target state is unavailable; retry after the index is ready", failure);
    }
    throw WorkerServiceException.unavailable(
        "VDU requires the active serving generation; retry after the index transition");
  }

  /** Strict read for recorded ingestion preparation; this observation is not a generation lease. */
  public String captureServingGeneration(CallContext ctx) {
    return captureActiveGeneration(ctx, true);
  }

  /** Read-only active witness for the recovery rebuild; this never grants ordinary ingestion. */
  public String captureRebuildGeneration(CallContext ctx) {
    return captureActiveGeneration(ctx, false);
  }

  /**
   * Strictly observes the committed fingerprint/input pair served by the captured search runtime.
   * The authoritative active pointer is fenced before and after the commit read; a build may be in
   * progress because Blue remains the applied generation until promotion.
   */
  public AppliedIndexGeneration captureAppliedGeneration(CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      requireAppliedGenerationNotCancelled(ctx);
      if (!hasServingRuntime || searchLifecycle == null || indexGenerationManager == null
          || capturedServingPath == null) {
        throw WorkerServiceException.unavailable("Applied generation authority is unavailable");
      }

      String generationBefore = requireCapturedActiveGeneration(false);
      Map<String, String> metadata;
      try {
        metadata = searchLifecycle.latestCommitUserDataBestEffort();
      } catch (RuntimeException failure) {
        throw new WorkerServiceException(
            WorkerServiceException.Status.UNAVAILABLE,
            "Committed index metadata could not be read",
            failure);
      }
      requireAppliedGenerationNotCancelled(ctx);
      AppliedIndexGeneration observation = appliedGeneration(generationBefore, metadata);
      String generationAfter = requireCapturedActiveGeneration(true);
      if (!generationBefore.equals(generationAfter)) {
        throw WorkerServiceException.aborted("Active generation moved during applied observation");
      }
      requireAppliedGenerationNotCancelled(ctx);
      return observation;
    }
  }

  private String requireCapturedActiveGeneration(boolean observationStarted) {
    final java.util.Optional<String> generation;
    try {
      generation = indexGenerationManager.activeGeneration(capturedServingPath);
    } catch (java.io.IOException | RuntimeException failure) {
      throw new WorkerServiceException(
          WorkerServiceException.Status.UNAVAILABLE,
          "Active generation state could not be established",
          failure);
    }
    return generation.orElseThrow(() -> observationStarted
        ? WorkerServiceException.aborted("Captured serving generation is no longer active")
        : WorkerServiceException.unavailable("Captured serving generation is not active"));
  }

  private static AppliedIndexGeneration appliedGeneration(
      String generationId, Map<String, String> metadata) {
    if (metadata == null) {
      throw WorkerServiceException.unavailable("Committed index metadata is unavailable");
    }
    String fingerprint = metadata.get(IndexFingerprint.COMMIT_META_KEY);
    String inputs = metadata.get(IndexFingerprint.COMMIT_META_INPUTS_KEY);
    if (fingerprint == null || inputs == null) {
      throw WorkerServiceException.unavailable(
          "Committed index fingerprint evidence is incomplete");
    }
    try {
      IndexTargetSnapshot target = new IndexTargetSnapshot(fingerprint, inputs);
      tools.jackson.databind.JsonNode parsed = COMMITTED_INPUTS_JSON.readTree(inputs);
      if (parsed == null || !parsed.isObject()) {
        throw new IllegalArgumentException("Canonical index inputs must be a JSON object");
      }
      return new AppliedIndexGeneration(generationId, target);
    } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
      throw new WorkerServiceException(
          WorkerServiceException.Status.UNAVAILABLE,
          "Committed index fingerprint evidence is invalid",
          invalid);
    }
  }

  private static void requireAppliedGenerationNotCancelled(CallContext ctx) {
    if (ctx.cancelled()) {
      throw WorkerServiceException.cancelled("Applied generation capture cancelled");
    }
  }

  private String captureActiveGeneration(CallContext ctx, boolean requireWriter) {
    try (var ignored = openRequestMdc(ctx)) {
      if (ctx.cancelled()) {
        throw new WorkerServiceException(WorkerServiceException.Status.CANCELLED, "Generation capture cancelled");
      }
      if ((requireWriter ? !ingestIsServing || ingestLifecycle == null : !hasServingRuntime)
          || indexGenerationManager == null || capturedServingPath == null) {
        throw WorkerServiceException.unavailable("Serving generation authority is unavailable");
      }
      java.util.Optional<String> generation;
      try {
        generation = indexGenerationManager.idleActiveGeneration(capturedServingPath);
      } catch (java.io.IOException | RuntimeException failure) {
        throw new WorkerServiceException(WorkerServiceException.Status.UNAVAILABLE,
            "Serving generation state could not be established", failure);
      }
      if (ctx.cancelled()) {
        throw new WorkerServiceException(WorkerServiceException.Status.CANCELLED, "Generation capture cancelled");
      }
      return generation.orElseThrow(() -> WorkerServiceException.unavailable(
          "Recorded ingestion requires the current idle serving generation"));
    }
  }

  /** Captures the Worker-computed physical index target, independently of Blue/Green serving state. */
  public IndexTargetSnapshot captureIndexTarget(CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      if (ctx.cancelled()) {
        throw WorkerServiceException.cancelled("Index target capture cancelled");
      }
      if (!hasServingRuntime || indexGenerationManager == null || capturedServingPath == null) {
        throw WorkerServiceException.unavailable("Index target authority is unavailable");
      }

      Map<String, Object> metadata = null;
      RuntimeException metadataFailure = null;
      try {
        metadata = new SsotCommitMetadataSource().build();
      } catch (RuntimeException failure) {
        metadataFailure = failure;
      }
      if (ctx.cancelled()) {
        throw WorkerServiceException.cancelled("Index target capture cancelled");
      }
      if (metadataFailure != null) {
        throw new WorkerServiceException(
            WorkerServiceException.Status.UNAVAILABLE,
            "Physical index target could not be established",
            metadataFailure);
      }
      if (metadata == null) {
        throw WorkerServiceException.unavailable("Physical index target metadata is unavailable");
      }

      Object fingerprint = metadata.get(IndexFingerprint.COMMIT_META_KEY);
      Object inputs = metadata.get(IndexFingerprint.COMMIT_META_INPUTS_KEY);
      if (!(fingerprint instanceof String fingerprintText)
          || !(inputs instanceof String inputsJson)) {
        throw WorkerServiceException.unavailable("Physical index target is indeterminate");
      }
      final IndexTargetSnapshot snapshot;
      try {
        snapshot = new IndexTargetSnapshot(fingerprintText, inputsJson);
      } catch (IllegalArgumentException invalid) {
        throw new WorkerServiceException(
            WorkerServiceException.Status.UNAVAILABLE,
            "Physical index target evidence is invalid",
            invalid);
      }
      if (ctx.cancelled()) {
        throw WorkerServiceException.cancelled("Index target capture cancelled");
      }
      return snapshot;
    }
  }

  public UpdateVduResultResponse updateVduResult(
      UpdateVduResultRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      String docId = request.getDocId();
      try {
        VduResultWriter.validate(request);
      } catch (IllegalArgumentException e) {
        return updateVduErrorResponse(e.getMessage());
      }
      requireServingVduTarget();
      try {
        if (!VduResultWriter.apply(ingestLifecycle, request, chunkSpladeEnabled())) {
          return updateVduErrorResponse("Document not found: " + docId);
        }
        ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_UPDATE);
        ingestLifecycle.commitOps().maybeRefreshBlocking();
        requireServingVduTarget();
        return updateVduSuccessResponse();
      } catch (WorkerServiceException failure) {
        throw failure;
      } catch (Exception e) {
        log.error("updateVduResult failed for doc: {}", docId, e);
        return updateVduErrorResponse(e.getMessage());
      }
    }
  }

  public DeleteByPathResponse deleteByPath(DeleteByPathRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
    String pathPrefix = request.getPath();
    log.info("deleteByPath RPC called for prefix: {}", pathPrefix);

    DeleteByPathResponse blank =
        blankReply(pathPrefix, deleteByPathResponse(0, "Path prefix is required"));
    if (blank != null) {
      return blank;
    }

    DeleteByPathResponse unavailable =
        indexRuntimeUnavailableReply(
            "deleteByPath", deleteByPathResponse(-1, "Index runtime not available"));
    if (unavailable != null) {
      return unavailable;
    }

    try {
      if (switchBufferOps.isSwitching()) {
        if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
          return switchBufferOps.bufferDeleteByPathDuringSwitching(sbq, pathPrefix);
        }
        throw IngestSwitchBufferOps.switchingUnavailable();
      }

      // 1. Delete from Lucene FIRST (user-facing impact)
      ingestLifecycle.indexingCoordinator().deleteByPathPrefix(pathPrefix);

      // 2. Delete from job queue (count tracking)
      int jobsDeleted = jobQueue.deleteByPathPrefix(pathPrefix);

      // 3. Commit Lucene changes
      ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_PATH);

      log.info("deleteByPath complete: {} jobs deleted for prefix: {}", jobsDeleted, pathPrefix);

      return deleteByPathResponse(jobsDeleted, "");

    } catch (WorkerServiceException e) {
      // Must precede the catch-all: the SWITCHING branch above throws UNAVAILABLE from inside
      // this try. Swallowed, it would come back as a successful-looking response carrying
      // error=<message> and deleted=-1 instead of the UNAVAILABLE status the caller retries on.
      throw e;
    } catch (Exception e) {
      log.error("deleteByPath failed for prefix: {}", pathPrefix, e);
      return deleteByPathResponse(-1, e.getMessage());
    }
    }
  }

  /**
   * Tempdoc 811 (C-2a) — the removal route for collection-tagged ad-hoc ingests. {@link
   * #deleteByPath} is watched-root-prefix driven and can never reach a document ingested from a path
   * under no watched root; this deletes by the {@code collection} term instead.
   *
   * <p>WHICH collections are deletable is decided by ONE Head-side authority ({@code
   * IngestCollectionPolicy#isDeletable} — refuses the reserved app-internal corpora and the untagged
   * default bucket). The worker deliberately does not fork that list; it only refuses a blank value.
   */
  public DeleteByCollectionResponse deleteByCollection(
      DeleteByCollectionRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      String collection = request.getCollection();
      log.info("deleteByCollection RPC called for collection: {}", collection);

      DeleteByCollectionResponse blank =
          blankReply(collection, deleteByCollectionResponse(-1, "Collection is required"));
      if (blank != null) {
        return blank;
      }
      DeleteByCollectionResponse unavailable =
          indexRuntimeUnavailableReply(
              "deleteByCollection",
              deleteByCollectionResponse(-1, "Index runtime not available"));
      if (unavailable != null) {
        return unavailable;
      }

      try {
        if (switchBufferOps.isSwitching()) {
          // Fail closed rather than buffer: a collection-scoped bulk delete replayed against a
          // freshly-switched index could race the migration's own document set.
          throw IngestSwitchBufferOps.switchingUnavailable();
        }

        int deleted = ingestLifecycle.indexingCoordinator().deleteByCollection(collection);
        ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_COLLECTION);

        log.info("deleteByCollection complete: {} documents deleted for {}", deleted, collection);
        return deleteByCollectionResponse(deleted, "");
      } catch (WorkerServiceException e) {
        // Must precede the catch-all: this endpoint deliberately FAILS CLOSED during SWITCHING,
        // and the catch-all would turn that refusal into a deleted=-1 response, hiding the
        // UNAVAILABLE the caller is meant to retry on.
        throw e;
      } catch (Exception e) {
        log.error("deleteByCollection failed for collection: {}", collection, e);
        return deleteByCollectionResponse(-1, e.getMessage());
      }
    }
  }

  public DeleteByIdResponse deleteById(DeleteByIdRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
    String docId = request.getDocId();
    log.info("deleteById RPC called for doc_id: {}", docId);

    DeleteByIdResponse blank = blankReply(docId, deleteByIdResponse(false, "doc_id is required"));
    if (blank != null) {
      return blank;
    }

    DeleteByIdResponse unavailable =
        indexRuntimeUnavailableReply(
            "deleteById", deleteByIdResponse(false, "Index runtime not available"));
    if (unavailable != null) {
      return unavailable;
    }

    try {
      // Normalize the path (lowercase on Windows)
      String normalizedId = normalizeDocIdForMutation(docId);

      if (switchBufferOps.isSwitching()) {
        if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
          return switchBufferOps.bufferDeleteByIdDuringSwitching(sbq, normalizedId);
        }
        throw IngestSwitchBufferOps.switchingUnavailable();
      }

      // 1. Delete from Lucene (exact match)
      ingestLifecycle.indexingCoordinator().deleteByIdAndChunks(normalizedId);

      // 2. Delete from job queue (exact match)
      jobQueue.deleteByExactPath(normalizedId);

      // 3. Commit Lucene changes
      ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_ID);

      log.info("deleteById complete for doc_id: {}", normalizedId);

      return deleteByIdResponse(true, "");

    } catch (WorkerServiceException e) {
      // Must precede the catch-all: the SWITCHING branch above throws UNAVAILABLE from inside
      // this try, and swallowing it would report success=false with the message inline instead
      // of the UNAVAILABLE status.
      throw e;
    } catch (Exception e) {
      log.error("deleteById failed for doc_id: {}", docId, e);
      return deleteByIdResponse(false, e.getMessage());
    }
    }
  }

  public PruneResponse pruneMissing(PruneRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
    String pathPrefix = request.getPathPrefix();
    log.info("pruneMissing RPC called for prefix: {}", pathPrefix);

    PruneResponse blank = blankReply(pathPrefix, pruneErrorResponse("path_prefix is required"));
    if (blank != null) {
      return blank;
    }

    PruneResponse unavailable =
        indexRuntimeUnavailableReply(
            "pruneMissing", pruneErrorResponse("Index runtime not available"));
    if (unavailable != null) {
      return unavailable;
    }

    try {
      if (switchBufferOps.isSwitching()) {
        // Keep the cutover fence: prune is a mutation, so durably buffer it during SWITCHING.
        if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
          return switchBufferOps.bufferPruneMissingDuringSwitching(sbq, pathPrefix);
        }
        throw IngestSwitchBufferOps.switchingUnavailable();
      }

      // Prune orphan documents - abort if user becomes active
      // Tempdoc 885 item 3: the throttle callback is the pacing tick and never aborts — a prune
      // that stops half-way on user activity leaves orphans behind, which is what the breath-hold
      // did. `aborted` therefore stays false on this path; the response field remains for the
      // force/abort contract PruneOps still exposes.
      int result = ingestLifecycle.pruneOps().pruneByPathPrefix(
          pathPrefix,
          indexingPacing::paceAndContinue,
          100  // Throttle batch size
      );

      boolean aborted = result < 0;
      int prunedCount = Math.max(0, result);

      if (aborted) {
        log.info("pruneMissing aborted for prefix: {} (user activity)", pathPrefix);
      } else {
        log.info("pruneMissing complete: {} orphans pruned for prefix: {}", prunedCount, pathPrefix);
      }

      return pruneResultResponse(prunedCount, aborted);

    } catch (WorkerServiceException e) {
      // Must precede the catch-all: the SWITCHING branch above throws UNAVAILABLE from inside
      // this try, and swallowing it would answer with an ordinary prune error response instead
      // of the UNAVAILABLE status.
      throw e;
    } catch (Exception e) {
      log.error("pruneMissing failed for prefix: {}", pathPrefix, e);
      return pruneErrorResponse(e.getMessage());
    }
    }
  }

  /**
   * Bidirectional sync: delete orphaned documents + add missing files.
   *
   * <p>This replaces the one-directional PruneMissing with full reconciliation:
   * <ol>
   *   <li>Delete documents for files that no longer exist on disk</li>
   *   <li>Enqueue files that exist on disk but are not in the index</li>
   * </ol>
   *
   * <p>Used for: OVERFLOW events, periodic maintenance, Windows DELETE workaround.
   *
   * <p><b>Throttling:</b> Checks user activity every 100 files, sleeps 1ms every 100 files.
   */
  public SyncDirectoryResponse syncDirectory(SyncDirectoryRequest request, CallContext ctx) {
    JobQueue.EnqueueProvenance provenance =
        ctx.engineContext().clientKind() == io.justsearch.core.context.EngineContext.ClientKind.INTERNAL
            && "SYSTEM_INTERNAL".equals(ctx.provenance().transport()) ? null : ctx.provenance();
    return syncDirectoryCommon(request, ctx, provenance, false);
  }

  /** Replays a durable sync with its persisted descriptive attribution. */
  public SyncDirectoryResponse syncDirectoryForReplay(
      SyncDirectoryRequest request, JobQueue.EnqueueProvenance provenance) {
    return syncDirectoryCommon(request, CallContext.none(), provenance, false);
  }

  /** The final cutover fence owns SWITCHING and replays this version directly onto Green. */
  public SyncDirectoryResponse syncDirectoryForFinalCutoverReplay(
      SyncDirectoryRequest request, JobQueue.EnqueueProvenance provenance) {
    return syncDirectoryCommon(request, CallContext.none(), provenance, true);
  }

  private SyncDirectoryResponse syncDirectoryCommon(
      SyncDirectoryRequest request, CallContext ctx, JobQueue.EnqueueProvenance provenance,
      boolean finalCutoverReplay) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      String rootPath = request.getRootPath();
      boolean force = request.getForce();
      log.info("syncDirectory RPC called for root: {} (force={})", rootPath, force);

      // Validate input (protobuf returns empty string, not null)
      SyncDirectoryResponse blank =
          blankReply(rootPath, syncDirectoryErrorResponse("root_path is required"));
      if (blank != null) {
        return blank;
      }

      if (!finalCutoverReplay && switchBufferOps.isSwitching()) {
        // During cutover, accept and durably buffer sync requests so OVERFLOW/burst events don't get lost.
        return switchBufferOps.bufferDuringSwitchingOrThrow(
            "syncDirectory",
            sbq ->
                switchBufferOps.bufferSyncDirectoryDuringSwitching(
                    sbq, rootPath, force, provenance));
      }

      SyncDirectoryResponse unavailable =
          indexRuntimeUnavailableReply(
              "syncDirectory", syncDirectoryErrorResponse("Index runtime not available"));
      if (unavailable != null) {
        return unavailable;
      }

      return syncOps.execute(rootPath, force, provenance);
    }
  }

  /**
   * Tempdoc 626 §Axis-A — in-process reconcile entry point for the Worker-side watcher's
   * OVERFLOW/burst recovery. Routes through the full {@link #syncDirectory} pipeline (switch-buffer,
   * user-activity, and index-runtime guards), so the Worker watcher no longer needs the retired
   * Head watcher's cross-process {@code syncDirectory} RPC. Best-effort: outcomes are logged and
   * dropped; the periodic sync remains the backstop.
   */
  public void reconcileRoot(Path root, boolean force) {
    try { reconcileRootStrict(root, force); }
    catch (RuntimeException e) {
      log.warn("In-process reconcile failed for {} (force={}): {}", root, force,
          e.getMessage());
    }
  }

  /** Watcher-owned route: a swallowed failure would make final cutover miss this root. */
  public void reconcileRootStrict(Path root, boolean force) {
    if (root == null) {
      return;
    }
    SyncDirectoryRequest request =
        SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).setForce(force).build();
    SyncDirectoryResponse value = syncDirectory(request, CallContext.none());
    if (!value.getError().isBlank()) {
      throw WorkerServiceException.unavailable("In-process reconcile was not accepted: " + value.getError());
    }
    log.debug(
        "In-process reconcile for {} (force={}): {} added, {} deleted, skipped={}",
        root.toString(), force, value.getFilesAdded(), value.getFilesDeleted(), value.getSkipped());
  }

  public QueryPendingVduResponse queryPendingVdu(
      QueryPendingVduRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    int limit = request.getLimit();
    if (limit <= 0) {
      limit = 100;  // Default limit
    }

    log.debug("queryPendingVdu RPC called with limit: {}", limit);

    requireEnrichmentReader(ctx);

    try {
      // Query documents with vdu_status=PENDING
      List<String> docIds = ingestLifecycle.documentFieldOps().queryDocIdsByFieldOrThrow(
          SchemaFields.VDU_STATUS,
          SchemaFields.VDU_STATUS_PENDING,
          limit);

      // Get total count (may be more than returned)
      int totalCount = ingestLifecycle.indexCountOps().countByFieldOrThrow(
          SchemaFields.VDU_STATUS,
          SchemaFields.VDU_STATUS_PENDING);

      log.info("queryPendingVdu: returning {} of {} pending docs", docIds.size(), totalCount);
      requireEnrichmentReader(ctx);

      return QueryPendingVduResponse.newBuilder()
          .addAllDocIds(docIds)
          .setTotalCount(totalCount)
          .build();

    } catch (java.io.IOException e) {
      throw new WorkerServiceException(WorkerServiceException.Status.INTERNAL,
          "Pending VDU could not be read", e);
    }
    }
  }

  /** Strict procedure control read; status metrics remain a separate best-effort projection. */
  public int countPendingEmbeddings(CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      requireEnrichmentReader(ctx);
      try {
        int count = ingestLifecycle.indexCountOps().countByFieldOrThrow(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
        requireEnrichmentReader(ctx);
        return count;
      } catch (java.io.IOException e) {
        throw new WorkerServiceException(WorkerServiceException.Status.INTERNAL,
            "Pending embeddings could not be read", e);
      }
    }
  }

  private void requireEnrichmentReader(CallContext ctx) {
    if (ctx.cancelled()) {
      throw new WorkerServiceException(WorkerServiceException.Status.CANCELLED,
          "Enrichment read cancelled");
    }
    if (ingestLifecycle == null) {
      throw WorkerServiceException.unavailable("Index runtime not available");
    }
  }

  public MarkVduProcessingResponse markVduProcessing(
      MarkVduProcessingRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
    String docId = request.getDocId();
    int maxRetries = resolveMaxRetries(request.getMaxRetries());

    log.debug("markVduProcessing RPC called for doc: {}, maxRetries: {}", docId, maxRetries);

    MarkVduProcessingResponse blank =
        blankReply(docId, markVduErrorResponse("doc_id is required"));
    if (blank != null) {
      return blank;
    }

    requireServingVduTarget();

    try {
      int currentCount = readVduRetryCount(docId);
      return applyMarkVduProcessing(docId, currentCount, maxRetries);

    } catch (WorkerServiceException failure) {
      throw failure;
    } catch (Exception e) {
      log.error("markVduProcessing failed for doc: {}", docId, e);
      return markVduErrorResponse(e.getMessage());
    }
    }
  }

  private static int resolveMaxRetries(int requestedMaxRetries) {
    return requestedMaxRetries <= 0 ? SchemaFields.VDU_MAX_RETRIES : requestedMaxRetries;
  }

  private int readVduRetryCount(String docId) throws java.io.IOException {
    String currentCountStr = ingestLifecycle.documentFieldOps().getDocumentFieldOrThrow(docId, SchemaFields.VDU_RETRY_COUNT);
    return ParseUtils.parseIntSafe(currentCountStr, 0);
  }

  private MarkVduProcessingResponse applyMarkVduProcessing(
      String docId, int currentCount, int maxRetries) throws Exception {
    MarkVduRetryDecision decision = decideMarkVduRetry(currentCount, maxRetries);

    // Check if max retries exceeded.
    if (decision.maxRetriesExceeded()) {
      log.warn("markVduProcessing: max retries ({}) exceeded for doc: {}", maxRetries, docId);

      // Mark as FAILED due to max retries.
      Map<String, Object> updates = new HashMap<>();
      updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_FAILED);
      updates.put(SchemaFields.VDU_ENRICHMENT, VDU_MAX_RETRIES_EXCEEDED_ENRICHMENT);
      if (!ingestLifecycle.indexingCoordinator().updateDocument(docId, updates)) {
        return markVduErrorResponse("Document not found: " + docId);
      }
      ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_MARK_PROCESSING);
      ingestLifecycle.commitOps().maybeRefreshBlocking();
      requireServingVduTarget();
      return markVduErrorResponse(VDU_MAX_RETRIES_EXCEEDED_ERROR);
    }

    // Increment retry count and mark as PROCESSING.
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
    updates.put(SchemaFields.VDU_RETRY_COUNT, String.valueOf(decision.retryCount()));

    boolean updated = ingestLifecycle.indexingCoordinator().updateDocument(docId, updates);
    if (updated) {
      ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_MARK_PROCESSING);
      ingestLifecycle.commitOps().maybeRefreshBlocking();
      requireServingVduTarget();
      log.debug(
          "markVduProcessing: doc {} marked PROCESSING, retry {}/{}",
          docId,
          decision.retryCount(),
          maxRetries);
      return markVduSuccessResponse(decision.retryCount());
    }
    return markVduErrorResponse("Document not found: " + docId);
  }

  private List<String> processingDocIdsForRecovery() throws java.io.IOException {
    return ingestLifecycle.documentFieldOps().queryDocIdsByFieldOrThrow(
        SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING, RECOVER_VDU_QUERY_LIMIT);
  }

  private boolean resetVduStatusToPending(String docId) throws Exception {
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING);
    return ingestLifecycle.indexingCoordinator().updateDocument(docId, updates);
  }

  @FunctionalInterface
  interface VduProcessingResetOp {
    boolean resetToPending(String docId) throws Exception;
  }

  record VduRecoveryResult(int recovered, Exception failure) {}

  static VduRecoveryResult recoverProcessingDocsWithResetOp(
      List<String> processingDocIds, VduProcessingResetOp resetOp) {
    int recovered = 0;
    Exception firstFailure = null;
    for (String docId : processingDocIds) {
      try {
        // Reset to PENDING (retry count already incremented, so won't loop forever).
        if (resetOp.resetToPending(docId)) {
          recovered++;
        } else if (firstFailure == null) {
          firstFailure = new IllegalStateException("Selected VDU document was not recovered: " + docId);
        }
      } catch (Exception e) {
        log.warn("Failed to recover doc: {}", docId, e);
        if (firstFailure == null) firstFailure = e;
      }
    }
    return new VduRecoveryResult(recovered, firstFailure);
  }

  private VduRecoveryResult recoverProcessingDocs(List<String> processingDocIds) {
    return recoverProcessingDocsWithResetOp(processingDocIds, this::resetVduStatusToPending);
  }

  public RecoverVduProcessingResponse recoverVduProcessing(
      RecoverVduProcessingRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
    log.info("recoverVduProcessing RPC called");

    requireEnrichmentReader(ctx);

    requireServingVduTarget();

    try {
      List<String> processingDocIds = processingDocIdsForRecovery();
      requireServingVduTarget();

      if (processingDocIds.isEmpty()) {
        log.info("recoverVduProcessing: no stuck documents found");
        return recoverVduCountResponse(0);
      }

      log.info("recoverVduProcessing: found {} stuck documents", processingDocIds.size());

      VduRecoveryResult result = recoverProcessingDocs(processingDocIds);
      int recovered = result.recovered();

      if (recovered > 0) {
        ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_RECOVERY);
        ingestLifecycle.commitOps().maybeRefreshBlocking();
      }

      log.info("recoverVduProcessing: recovered {} of {} documents", recovered, processingDocIds.size());

      requireServingVduTarget();
      if (result.failure() != null) {
        throw new IllegalStateException("Selected VDU recovery is incomplete", result.failure());
      }
      return recoverVduCountResponse(recovered);

    } catch (WorkerServiceException failure) {
      throw failure;
    } catch (Exception e) {
      log.error("recoverVduProcessing failed", e);
      throw new WorkerServiceException(
          WorkerServiceException.Status.INTERNAL, "VDU recovery failed", e);
    }
    }
  }

  public UpdatePathsResponse updateDocumentPaths(
      UpdatePathsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
    log.info("updateDocumentPaths RPC called with {} mappings", request.getMappingsCount());

    if (request.getMappingsCount() == 0) {
      return UpdatePathsResponse.newBuilder().setUpdatedCount(0).build();
    }

    UpdatePathsResponse unavailable =
        indexRuntimeUnavailableReply(
            "updateDocumentPaths", UpdatePathsResponse.newBuilder().setUpdatedCount(0).build());
    if (unavailable != null) {
      return unavailable;
    }

    // Rename is a two-authority mutation (identity store plus Lucene). Unlike simple path
    // upserts/deletes, the existing switch buffer cannot replay both halves atomically. Refuse
    // during the narrow cutover fence before touching either authority and let the caller retry.
    if (switchBufferOps.isSwitching()) {
      throw IngestSwitchBufferOps.switchingUnavailable();
    }

    try {
      int updatedCount = 0;
      List<String> failedPaths = new ArrayList<>();

      for (PathMapping mapping : request.getMappingsList()) {
        String rawOldPath = mapping.getOldPath();
        String rawNewPath = mapping.getNewPath();
        if (rawOldPath.isBlank() || rawNewPath.isBlank()) {
          failedPaths.add(rawOldPath);
          continue;
        }
        String oldPath = PathNormalizer.normalizeKey(Path.of(rawOldPath));
        String newPath = PathNormalizer.normalizeKey(Path.of(rawNewPath));

        String oldPathHash =
            io.justsearch.indexerworker.identity.DocumentIdentityStore.pathHash(oldPath);
        String newPathHash =
            io.justsearch.indexerworker.identity.DocumentIdentityStore.pathHash(newPath);
        var identityRekey =
            documentIdentityStore.rekey(oldPathHash, newPathHash, System.currentTimeMillis());

        int count = ingestLifecycle.indexingCoordinator().updateDocumentPaths(oldPath, newPath);
        // Green may not contain the document yet while Blue still serves it. Moving the durable
        // identity is therefore a successful rename even when this generation has no Lucene row.
        // Store-first is deliberate: if Lucene throws after the move, the RPC fails and retry or
        // boot reconciliation converges from the store-authoritative uid without ever re-minting.
        if (identityRekey
                != io.justsearch.indexerworker.identity.DocumentIdentityStore.RekeyResult.NOT_FOUND
            || count > 0) {
          updatedCount++;
        } else {
          failedPaths.add(mapping.getOldPath());
        }
      }

      ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_UPDATE_PATHS);

      log.info(
          "updateDocumentPaths: updated {} paths, {} failed",
          updatedCount,
          failedPaths.size());

      return UpdatePathsResponse.newBuilder()
          .setUpdatedCount(updatedCount)
          .addAllFailedPaths(failedPaths)
          .build();

    } catch (Exception e) {
      log.error("updateDocumentPaths failed", e);
      throw WorkerServiceException.internal(
          "Failed to update document paths: " + e.getMessage());
    }
    }
  }

  public ListFailedJobsResponse listFailedJobs(
      ListFailedJobsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      int limit = request.getLimit();
      List<JobQueue.FailedJobInfo> jobs = jobQueue.listFailedJobs(limit);
      long totalCount = jobQueue.failureSummary().failedCount();

      ListFailedJobsResponse.Builder resp =
          ListFailedJobsResponse.newBuilder().setTotalCount(totalCount);
      for (JobQueue.FailedJobInfo job : jobs) {
        resp.addJobs(
            FailedJob.newBuilder()
                .setPath(job.path() != null ? job.path() : "")
                .setErrorMessage(job.errorMessage() != null ? job.errorMessage() : "")
                .setAttempts(job.attempts())
                .setLastUpdatedMs(job.lastUpdatedMs())
                .setCollection(job.collection() != null ? job.collection() : "")
                .setState(job.state() != null ? job.state() : "")
                .setScanId(job.scanId() != null ? job.scanId() : "")
                .build());
      }
      return resp.build();
    }
  }

  public io.justsearch.ipc.CountJobsByPathPrefixResponse countJobsByPathPrefix(
      io.justsearch.ipc.CountJobsByPathPrefixRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      JobQueue.JobStateCounts counts = jobQueue.countByPathPrefix(request.getPathPrefix());
      io.justsearch.ipc.IndexingJobCounts wire =
          io.justsearch.ipc.IndexingJobCounts.newBuilder()
              .setPendingCount(counts.pendingCount())
              .setProcessingCount(counts.processingCount())
              .setFailedCount(counts.failedCount())
              .build();
      return io.justsearch.ipc.CountJobsByPathPrefixResponse.newBuilder()
          .setCounts(wire)
          .setCoverage(rootCoverage(request.getPathPrefix()))
          .build();
    }
  }

  /**
   * Per-root enrichment coverage for {@link #countJobsByPathPrefix} (tempdoc 813 §1c). The queue
   * counts above come from SQLite and are always available; these come from the Lucene index, so
   * an absent index runtime degrades this leg to all-zero rather than failing the whole response —
   * the Library row still gets its truthful in-flight/failed numbers.
   */
  private io.justsearch.ipc.RootCoverageCounts rootCoverage(String pathPrefix) {
    io.justsearch.adapters.lucene.runtime.IndexCountOps countOps =
        ingestLifecycle == null ? null : ingestLifecycle.indexCountOps();
    if (countOps == null) {
      return io.justsearch.ipc.RootCoverageCounts.getDefaultInstance();
    }
    try {
      io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RootCoverageCounts c =
          countOps.queryRootCoverageCounts(pathPrefix);
      return io.justsearch.ipc.RootCoverageCounts.newBuilder()
          .setParentDocsTotalEmbedding(c.parentDocsTotalEmbedding())
          .setParentDocsSettledEmbedding(c.parentDocsSettledEmbedding())
          .setParentDocsTotalSplade(c.parentDocsTotalSplade())
          .setParentDocsSettledSplade(c.parentDocsSettledSplade())
          .setParentDocsTotalNer(c.parentDocsTotalNer())
          .setParentDocsSettledNer(c.parentDocsSettledNer())
          .setChunkDocsTotal(c.chunkDocsTotal())
          .setChunkDocsSettled(c.chunkDocsSettled())
          .build();
    } catch (RuntimeException e) {
      log.warn("countJobsByPathPrefix: root coverage unavailable: {}", e.getMessage());
      return io.justsearch.ipc.RootCoverageCounts.getDefaultInstance();
    }
  }

  public ListFailedJobsResponse listFailedJobsByPathPrefix(
      io.justsearch.ipc.ListFailedJobsByPathPrefixRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      List<JobQueue.FailedJobInfo> jobs =
          jobQueue.listFailedJobsByPathPrefix(request.getPathPrefix(), request.getLimit());
      ListFailedJobsResponse.Builder resp =
          ListFailedJobsResponse.newBuilder().setTotalCount(jobs.size());
      for (JobQueue.FailedJobInfo job : jobs) {
        resp.addJobs(
            FailedJob.newBuilder()
                .setPath(job.path() != null ? job.path() : "")
                .setErrorMessage(job.errorMessage() != null ? job.errorMessage() : "")
                .setAttempts(job.attempts())
                .setLastUpdatedMs(job.lastUpdatedMs())
                .setCollection(job.collection() != null ? job.collection() : "")
                .setState(job.state() != null ? job.state() : "")
                .setScanId(job.scanId() != null ? job.scanId() : "")
                .build());
      }
      return resp.build();
    }
  }

  public ClearFailedJobsResponse clearFailedJobs(
      ClearFailedJobsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      int deleted = jobQueue.clearFailedJobs();
      return ClearFailedJobsResponse.newBuilder().setDeletedCount(deleted).build();
    }
  }

  public io.justsearch.ipc.SessionPoliciesResponse getSessionPolicies(
      io.justsearch.ipc.SessionPoliciesRequest request, CallContext ctx) {
    io.justsearch.ipc.SessionPoliciesResponse.Builder resp =
        io.justsearch.ipc.SessionPoliciesResponse.newBuilder();
    Supplier<io.justsearch.ort.PolicySnapshot> supplier = policySnapshotSupplier;
    io.justsearch.ort.PolicySnapshot snap = supplier != null ? supplier.get() : null;
    if (snap == null) {
      resp.setConfigStatus("surface-unavailable");
      return resp.build();
    }
    try {
      tools.jackson.databind.ObjectMapper mapper =
          new tools.jackson.databind.json.JsonMapper();
      resp.setConfigStatus("ok");
      resp.setRuntimePolicyJson(mapper.writeValueAsString(snap.runtime()));
      for (var entry : snap.models().entrySet()) {
        resp.putModelPoliciesJson(entry.getKey().name(), mapper.writeValueAsString(entry.getValue()));
      }
      return resp.build();
    } catch (RuntimeException e) {
      log.warn("getSessionPolicies: JSON serialization failed", e);
      throw WorkerServiceException.internal(
          "policy serialization failed: " + e.getMessage());
    }
  }

  /**
   * Tempdoc 406 admin endpoint — triggers a holder swap on the ingest runtime.
   * Calls {@code KnowledgeServer.swapRuntime(...)} via the wired
   * {@code runtimeReloadTrigger}; returns the swap duration in ms.
   */
  public io.justsearch.ipc.ReloadRuntimeResponse reloadRuntime(
      io.justsearch.ipc.ReloadRuntimeRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      java.util.function.Function<SwapReason, Long> trigger = this.runtimeReloadTrigger;
      if (trigger == null) {
        log.warn("reloadRuntime: trigger not wired; rejecting with FAILED_PRECONDITION");
        throw WorkerServiceException.failedPrecondition(
            "Runtime reload trigger not wired on this Worker");
      }
      String wire = request.getReason();
      SwapReason reason =
          wire.isBlank() ? SwapReason.ADMIN_TRIGGERED : SwapReason.fromWire(wire);
      log.info("reloadRuntime: starting swap (reason={})", reason.wireValue());
      long durationMs = trigger.apply(reason);
      log.info(
          "reloadRuntime: swap complete in {}ms (reason={})", durationMs, reason.wireValue());
      return io.justsearch.ipc.ReloadRuntimeResponse.newBuilder()
          .setSwapDurationMs(durationMs)
          .build();
    } catch (WorkerServiceException e) {
      // Must precede the catch-all: the unwired-trigger refusal above throws FAILED_PRECONDITION
      // from inside this try-with-resources, and the catch-all would relabel it INTERNAL
      // "Reload failed: ..." — the Head distinguishes "reload unavailable here" from "reload
      // broke" by exactly that code.
      throw e;
    } catch (Exception e) {
      log.error("reloadRuntime failed", e);
      throw WorkerServiceException.internal("Reload failed: " + e.getMessage());
    }
  }

  public io.justsearch.ipc.RecentIngestionEventsResponse recentIngestionEvents(
      io.justsearch.ipc.RecentIngestionEventsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      int limit = request.getLimit();
      List<JobQueue.IngestionEventView> events = jobQueue.recentIngestionEvents(limit);
      io.justsearch.ipc.RecentIngestionEventsResponse.Builder resp =
          io.justsearch.ipc.RecentIngestionEventsResponse.newBuilder();
      for (JobQueue.IngestionEventView event : events) {
        io.justsearch.ipc.IngestionEvent.Builder b =
            io.justsearch.ipc.IngestionEvent.newBuilder()
                .setId(event.id())
                .setPathHash(event.pathHash() != null ? event.pathHash() : "")
                .setCollection(event.collection() != null ? event.collection() : "")
                .setOutcomeClass(event.outcomeClass() != null ? event.outcomeClass() : "")
                .setReasonCode(event.reasonCode() != null ? event.reasonCode() : "")
                .setRetryPolicy(event.retryPolicy() != null ? event.retryPolicy() : "")
                .setDiagnosticSummary(
                    event.diagnosticSummary() != null ? event.diagnosticSummary() : "")
                .setObservedAtMs(event.observedAtMs())
                .setSourceKind(event.sourceKind() != null ? event.sourceKind() : "")
                .setArtifactStatus(event.artifactStatus() != null ? event.artifactStatus() : "")
                .setPolicyId(event.policyId() != null ? event.policyId() : "")
                .setParserId(event.parserId() != null ? event.parserId() : "");
        if (event.sourceSizeBytes() != null) b.setSourceSizeBytes(event.sourceSizeBytes());
        if (event.sourceModifiedAtMs() != null) b.setSourceModifiedAtMs(event.sourceModifiedAtMs());
        resp.addEvents(b.build());
      }
      return resp.build();
    }
  }

  public io.justsearch.ipc.IngestionOutcomeSummaryResponse ingestionOutcomeSummary(
      io.justsearch.ipc.IngestionOutcomeSummaryRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      List<JobQueue.IngestionOutcomeSummary> rollups =
          jobQueue.ingestionOutcomeSummary(request.getSinceMs());
      io.justsearch.ipc.IngestionOutcomeSummaryResponse.Builder resp =
          io.justsearch.ipc.IngestionOutcomeSummaryResponse.newBuilder();
      for (JobQueue.IngestionOutcomeSummary rollup : rollups) {
        resp.addRollups(
            io.justsearch.ipc.IngestionOutcomeRollup.newBuilder()
                .setOutcomeClass(rollup.outcomeClass() != null ? rollup.outcomeClass() : "")
                .setReasonCode(rollup.reasonCode() != null ? rollup.reasonCode() : "")
                .setRetryPolicy(rollup.retryPolicy() != null ? rollup.retryPolicy() : "")
                .setCount(rollup.count())
                .setLastObservedAtMs(rollup.lastObservedAtMs())
                .build());
      }
      return resp.build();
    }
  }

  // ==================== Tempdoc 418 Phase A/B: Worker-owned filesystem traversal ====================

  /**
   * Phase B — replaces the registry-only Phase A scaffolding with one backed by a
   * {@link WorkerMethvinWatcher}. Called by {@code DefaultWorkerAppServices} after the
   * watcher is constructed so all subsequent {@code WatchRoot}/{@code UnwatchRoot} RPCs
   * route real filesystem events into {@link JobQueue}. Must be invoked before any client
   * issues a {@code WatchRoot}.
   */
  public void setRootWatcherRegistry(RootWatcherRegistry registry) {
    this.rootWatcherRegistry = java.util.Objects.requireNonNull(registry, "registry");
  }

  /**
   * Tempdoc 419 / T5.3 (ADR-0028) — wires the scoped reverse-lookup store. Production
   * composition (DefaultWorkerAppServices) calls this with the SqlitePathResolutionStore
   * from InfraContext. Defaults to {@link io.justsearch.indexerworker.path.PathResolutionStore#NOOP}.
   */
  public void setPathResolutionStore(io.justsearch.indexerworker.path.PathResolutionStore store) {
    this.pathResolutionStore =
        store == null ? io.justsearch.indexerworker.path.PathResolutionStore.NOOP : store;
  }

  /** Wires the durable authority that preserves document identity across path renames. */
  public void setDocumentIdentityStore(
      io.justsearch.indexerworker.identity.DocumentIdentityStore store) {
    this.documentIdentityStore =
        store == null
            ? io.justsearch.indexerworker.identity.DocumentIdentityStore.UNAVAILABLE
            : store;
  }

  public io.justsearch.ipc.LookupPathByHashResponse lookupPathByHash(
      io.justsearch.ipc.LookupPathByHashRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      String pathHash = request.getPathHash();
      if (pathHash.isBlank()) {
        throw WorkerServiceException.invalidArgument(
            "LookupPathByHashRequest.path_hash is required");
      }
      io.justsearch.ipc.LookupPathByHashResponse.Builder resp =
          io.justsearch.ipc.LookupPathByHashResponse.newBuilder();
      pathResolutionStore
          .lookup(pathHash)
          .ifPresentOrElse(
              r ->
                  resp.setFound(true)
                      .setPath(r.normalizedPath() != null ? r.normalizedPath() : "")
                      .setLastSeenAtMs(r.lastSeenAtMs())
                      .setRemovedAtMs(r.removedAtMs() != null ? r.removedAtMs() : 0L),
              () -> resp.setFound(false));
      return resp.build();
    }
  }

  /**
   * Server-streaming: emits every {@code ScanRootProgress} frame through {@code sink} and returns
   * when the scan is over. The caller (the transport adapter while the wire is up) completes the
   * stream — this method never does.
   */
  public void scanRoot(
      io.justsearch.ipc.ScanRootRequest request,
      java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> sink,
      CallContext ctx) {
    scanRoot(request, sink, ctx, null);
  }

  /** Captured bulk records all source identities before any member may be claimed. */
  public enum RecordedScanMode { STREAMING, CAPTURED }

  /** Java-only projection of one accepted root; the wire schema never carries operation identity. */
  public record RecordedRootScan(io.justsearch.ipc.ScanRootRequest request,
      String operationKey, long epoch, String expectedGeneration, boolean singleFile, List<Path> excludedSubtrees,
      RecordedScanMode mode) {
    public RecordedRootScan {
      java.util.Objects.requireNonNull(request, "request");
      java.util.Objects.requireNonNull(mode, "mode");
      if (mode == RecordedScanMode.CAPTURED
          && (singleFile || request.getMode() != io.justsearch.ipc.ScanMode.SCAN_MODE_FORCE_REINDEX)) {
        throw new IllegalArgumentException("Captured bulk requires forced directory traversal");
      }
      if (operationKey == null || operationKey.isBlank() || operationKey.length() > 256 || epoch < 1) {
        throw new IllegalArgumentException("Invalid recorded scan membership");
      }
      if (expectedGeneration == null || expectedGeneration.isBlank() || expectedGeneration.length() > 128) {
        throw new IllegalArgumentException("Invalid recorded generation");
      }
      Path root = Path.of(request.getRootPath());
      if (!root.isAbsolute() || !root.normalize().equals(root) || root.toString().length() > 32768) {
        throw new IllegalArgumentException("Recorded root must be absolute and normalized");
      }
      excludedSubtrees = List.copyOf(excludedSubtrees);
      if (excludedSubtrees.size() > 1024 || request.getExcludeGlobsCount() > 1024) {
        throw new IllegalArgumentException("Recorded root policy exceeds its bound");
      }
      for (Path subtree : excludedSubtrees) {
        if (!subtree.isAbsolute() || !subtree.normalize().equals(subtree)
            || subtree.toString().length() > 32768 || subtree.equals(root) || !subtree.startsWith(root)) {
          throw new IllegalArgumentException("Excluded subtree must be a normalized descendant");
        }
      }
      for (String glob : request.getExcludeGlobsList()) {
        if (glob.isBlank() || glob.length() > 4096) throw new IllegalArgumentException("Invalid recorded root glob");
      }
    }

    public RecordedRootScan(io.justsearch.ipc.ScanRootRequest request, String operationKey, long epoch,
        String expectedGeneration, boolean singleFile, List<Path> excludedSubtrees) {
      this(request, operationKey, epoch, expectedGeneration, singleFile, excludedSubtrees, RecordedScanMode.STREAMING);
    }
  }

  /** Synchronous actual enumeration; the Engine supplies the admitted owner and bounded executor. */
  public void scanRecordedRoot(RecordedRootScan recorded,
      java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> sink, CallContext ctx) {
    java.util.Objects.requireNonNull(recorded, "recorded");
    if (ctx.cancelled()) {
      throw new WorkerServiceException(WorkerServiceException.Status.CANCELLED, "Generation capture cancelled");
    }
    if (!awaitRecordedGenerationBeforeTraversal(recorded, ctx)) return;
    scanRoot(recorded.request(), sink, ctx, recorded);
  }

  private boolean awaitRecordedGenerationBeforeTraversal(RecordedRootScan recorded, CallContext ctx) {
    // The existing Engine root producer owns this wait, its deadline and actual-exit completion.
    // No traversal or queue admission has begun. Never replay scanRoot or a completed batch here.
    while (!ctx.cancelled()) {
      try {
        validateRecordedGeneration(recorded, ctx);
        return true;
      } catch (WorkerServiceException unavailable) {
        if (ctx.cancelled()) return false;
        if (unavailable.status() != WorkerServiceException.Status.UNAVAILABLE) throw unavailable;
        Throwable cause = unavailable.getCause();
        if (cause instanceof io.justsearch.configuration.persistence.ContendedFileReads.FileReadContendedException) {
          // The strict read already spent its bounded, interruptible lock wait.
          continue;
        }
        if (!(cause instanceof java.nio.file.NoSuchFileException)) throw unavailable;
        // The generation owner rotates current to .prev before publishing its completed temp.
        // Reread only current; never authorize from .prev, and do not spin if absence persists.
        try {
          Thread.sleep(10);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          if (ctx.cancelled()) return false;
          throw new WorkerServiceException(WorkerServiceException.Status.CANCELLED,
              "Generation preflight interrupted", interrupted);
        }
      }
    }
    return false;
  }

  private void validateRecordedGeneration(RecordedRootScan recorded, CallContext ctx) {
    String source = recorded.mode() == RecordedScanMode.CAPTURED
        ? captureRebuildGeneration(ctx) : captureServingGeneration(ctx);
    if (!recorded.expectedGeneration().equals(source)) {
      throw WorkerServiceException.unavailable("RECORDED_GENERATION_CHANGED");
    }
  }

  private void scanRoot(io.justsearch.ipc.ScanRootRequest request,
      java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> sink,
      CallContext ctx, RecordedRootScan recorded) {
    // Tempdoc 419 / T2 — Allocate the scanId at RPC entry. The same value is stamped on every
    // emitted ScanRootProgress event so SSE consumers (T4) can subscribe by scanId, and so
    // log entries from this scan correlate via MDC.
    String scanId = recorded == null ? java.util.UUID.randomUUID().toString() : recorded.operationKey();
    try (var ignored = openRequestMdc(ctx);
        var ignored2 = MdcContext.scan(scanId);
        var ignoredMutation = mutationLease()) {
      String rootPath = request.getRootPath();
      if (rootPath.isBlank()) {
        throw WorkerServiceException.invalidArgument("ScanRootRequest.root_path is required");
      }
      Path root;
      try {
        root = Path.of(rootPath).toAbsolutePath().normalize();
      } catch (InvalidPathException e) {
        throw WorkerServiceException.invalidArgument(
            "ScanRootRequest.root_path is not a valid path: " + e.getMessage());
      }
      WorkerScanOps.ScanMode mode =
          switch (request.getMode()) {
            case SCAN_MODE_RESCAN -> WorkerScanOps.ScanMode.RESCAN;
            case SCAN_MODE_FORCE_REINDEX -> WorkerScanOps.ScanMode.FORCE_REINDEX;
            default -> WorkerScanOps.ScanMode.INITIAL;
          };
      WorkerScanOps.ScanRequest scanRequest =
          new WorkerScanOps.ScanRequest(
              root, request.getCollection(), mode, request.getExcludeGlobsList(), scanId, ctx.provenance(),
              recorded == null ? null : recorded.epoch(),
              recorded == null ? List.of() : recorded.excludedSubtrees(), recorded != null && recorded.singleFile(),
              recorded == null ? RecordedScanMode.STREAMING : recorded.mode());
      // Tempdoc 418 B-H.3 — Worker owns backpressure + cancellation. The call's cancellation
      // signal lets WorkerScanOps stop walking when the caller drops the stream (e.g.,
      // RootLifecycleOps removes the watched root mid-scan).
      java.util.function.LongSupplier queueDepth = jobQueue::queueDepth;
      java.util.function.BooleanSupplier isCancelled = ctx::cancelled;
      try {
        // Tempdoc 821 §3-C3 — a FORCE_REINDEX scan marks its admitted paths through the SAME
        // forced-path set submitBatch's force_reindex flag feeds (see #submitBatch above).
        // A lambda, not `indexingLoop::markForced`: a method reference dereferences its receiver
        // when the sink is CREATED, which would make every ordinary scan depend on a field only
        // the forced branch actually uses.
        new WorkerScanOps(jobQueue, queueDepth, isCancelled, paths -> indexingLoop.markForced(paths),
            () -> validateRecordedGeneration(java.util.Objects.requireNonNull(recorded), ctx))
            .scan(scanRequest, sink);
      } catch (java.io.IOException e) {
        log.warn("ScanRoot walk failed for {}: {}", rootPath, e.getMessage());
        sink.accept(
            io.justsearch.ipc.ScanRootProgress.newBuilder()
                .setComplete(true)
                .setTerminalReasonCode("IO_ERROR")
                .setScanId(scanId)
                .build());
      }
    }
  }

  public io.justsearch.ipc.WatchRootResponse watchRoot(
      io.justsearch.ipc.WatchRootRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      String rootPath = request.getRootPath();
      if (rootPath.isBlank()) {
        throw WorkerServiceException.invalidArgument("WatchRootRequest.root_path is required");
      }
      RootWatcherRegistry.WatchResult result =
          rootWatcherRegistry.watch(rootPath, request.getCollection());
      return io.justsearch.ipc.WatchRootResponse.newBuilder()
          .setWatching(result.watching())
          .setErrorMessage(result.errorMessage() == null ? "" : result.errorMessage())
          .build();
    }
  }

  public io.justsearch.ipc.UnwatchRootResponse unwatchRoot(
      io.justsearch.ipc.UnwatchRootRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      boolean removed = rootWatcherRegistry.unwatch(request.getRootPath());
      return io.justsearch.ipc.UnwatchRootResponse.newBuilder().setUnwatched(removed).build();
    }
  }

  public ResetIndexResponse resetIndex(ResetIndexRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      log.info("resetIndex: starting profiling reset");

      indexingLoop.resetForProfiling(
          () -> {
            try {
              // External cleanup — runs while the loop is stopped
              jobQueue.clearAll();
              ingestLifecycle.indexingCoordinator().deleteAll();
              ingestLifecycle.commitOps().commitAndTrack(CommitReason.RESET);
              // Force searcher refresh so docCount()/isUnmodified() see the empty index.
              // Without this, the stale SearcherManager shows old docs and the loop skips
              // re-submitted files as "unchanged".
              ingestLifecycle.commitOps().maybeRefreshBlocking();
              var ds = indexingLoop.getDisambiguationService();
              if (ds != null) {
                ds.reset();
              }
              OperationalMetrics.getInstance().resetAll();
            } catch (Exception e) {
              throw new RuntimeException("Reset cleanup failed", e);
            }
          });

      log.info("resetIndex: profiling reset complete");
      return ResetIndexResponse.newBuilder().setSuccess(true).build();
    } catch (Exception e) {
      log.error("resetIndex failed", e);
      return ResetIndexResponse.newBuilder().setSuccess(false).build();
    }
  }

  // ==================== Slice 445 — Job-queue TABULAR Resource ====================

  /**
   * Streams the indexing-jobs collection: one snapshot frame followed by
   * per-row delta frames as the SqliteJobQueue mutates. Backs the
   * {@code core.indexing-jobs} TABULAR Resource (Category × SSE_STREAM ×
   * Privacy.HASHED_REQUIRES_RESOLVER).
   *
   * <p>Wire shape (per indexing.proto §1184): each frame is a oneof
   * {snapshot, delta} plus a monotonic seq. Snapshot is one frame; subsequent
   * frames are deltas.
   *
   * <p>Privacy: every row carries SHA-256 hex {@code path_hash}, never raw
   * paths. Path resolution is the separate {@code core.resolve-path-hash}
   * Operation pinned by ADR-0028 / {@code LibraryResolveHashOnlyCallerPin}.
   *
   * <p>Server-streaming, and this method RETURNS WHILE THE STREAM IS STILL OPEN: it registers a
   * change-feed subscription whose deltas are emitted from the feed's own threads. The caller must
   * NOT complete the stream when this returns — cancellation does that.
   */
  public void subscribeIndexingJobs(
      io.justsearch.ipc.SubscribeIndexingJobsRequest request,
      java.util.function.Consumer<io.justsearch.ipc.IndexingJobsFrame> sink,
      CallContext ctx) {
    var feedOpt = jobQueue.indexingJobChangeFeed();
    if (feedOpt.isEmpty()) {
      throw WorkerServiceException.unimplemented(
          "Job queue does not support change-feed (non-SQLite implementation)");
    }
    IndexingJobChangeFeed feed = feedOpt.get();

    // The snapshot is emitted on this (RPC) thread while deltas arrive on the change-feed's
    // threads; both go through the same sink, so they need one monitor between them. Before the
    // conversion that monitor was the ServerCallStreamObserver itself — nothing else ever
    // synchronized on it, so a private lock is the same mutual exclusion without the transport.
    final Object emitLock = new Object();
    // This brief handoff covers subscribe-return through snapshot emission. It is separate from
    // the port's steady-state delivery queue and must never block the jobs-table writer.
    final int pendingSnapshotLimit = 256;
    java.util.ArrayDeque<IndexingJobChangeFeed.Delta> pendingSnapshot = new java.util.ArrayDeque<>();
    java.util.concurrent.atomic.AtomicBoolean snapshotSent = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicBoolean snapshotOverflow = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<IndexingJobChangeFeed.Subscription> subRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean closeRequested = new java.util.concurrent.atomic.AtomicBoolean();
    Runnable closeSubscription = () -> {
      closeRequested.set(true);
      IndexingJobChangeFeed.Subscription subscription = subRef.getAndSet(null);
      if (subscription != null) subscription.close();
    };
    java.util.concurrent.atomic.AtomicLong frameSeq =
        new java.util.concurrent.atomic.AtomicLong(0L);

    java.util.function.Consumer<IndexingJobChangeFeed.Delta> emitDelta =
        delta -> {
          if (ctx.cancelled() || closeRequested.get()) return;
          io.justsearch.ipc.IndexingJobsDelta.Builder deltaBuilder =
              io.justsearch.ipc.IndexingJobsDelta.newBuilder();
          switch (delta) {
            case IndexingJobChangeFeed.Delta.Insert ins ->
                deltaBuilder.setInsert(toJobView(ins.row()));
            case IndexingJobChangeFeed.Delta.Update upd ->
                deltaBuilder.setUpdate(toJobView(upd.row()));
            case IndexingJobChangeFeed.Delta.Delete del ->
                deltaBuilder.setDeletePathHash(del.pathHash());
          }
          synchronized (emitLock) {
            try {
              sink.accept(
                  io.justsearch.ipc.IndexingJobsFrame.newBuilder()
                      .setDelta(deltaBuilder.build())
                      .setSeq(frameSeq.incrementAndGet())
                      .build());
            } catch (RuntimeException e) {
              log.warn("subscribeIndexingJobs: delta delivery failed; closing subscription", e);
              closeSubscription.run();
            }
          }
        };

    java.util.function.Consumer<IndexingJobChangeFeed.Delta> consumer = delta -> {
      synchronized (emitLock) {
        if (ctx.cancelled() || closeRequested.get()) return;
        if (snapshotSent.get()) {
          emitDelta.accept(delta);
        } else if (pendingSnapshot.size() < pendingSnapshotLimit) {
          pendingSnapshot.addLast(delta);
        } else {
          snapshotOverflow.set(true);
          pendingSnapshot.clear();
          closeSubscription.run();
        }
      }
    };

    ctx.onCancel(closeSubscription);

    try {
      var snap = feed.subscribeWithSnapshot(consumer);
      subRef.set(snap.subscription());
      if (snapshotOverflow.get()) {
        throw WorkerServiceException.unavailable("indexing-jobs snapshot handoff overflow; resubscribe");
      }
      if (closeRequested.get() || ctx.cancelled()) {
        closeSubscription.run();
        return;
      }

      io.justsearch.ipc.IndexingJobsSnapshot.Builder snapBuilder =
          io.justsearch.ipc.IndexingJobsSnapshot.newBuilder();
      for (IndexingJobChangeFeed.JobRow row : snap.items()) {
        snapBuilder.addItems(toJobView(row));
      }
      synchronized (emitLock) {
        if (snapshotOverflow.get()) {
          throw WorkerServiceException.unavailable("indexing-jobs snapshot handoff overflow; resubscribe");
        }
        if (closeRequested.get() || ctx.cancelled()) {
          closeSubscription.run();
          return;
        }
        sink.accept(
            io.justsearch.ipc.IndexingJobsFrame.newBuilder()
                .setSnapshot(snapBuilder.build())
                .setSeq(frameSeq.incrementAndGet())
                .build());
        // Reentrant sink writes also join this queue, behind already buffered deltas.
        while (!pendingSnapshot.isEmpty() && !closeRequested.get() && !ctx.cancelled()) {
          emitDelta.accept(pendingSnapshot.removeFirst());
        }
        pendingSnapshot.clear();
        if (snapshotOverflow.get()) {
          throw WorkerServiceException.unavailable("indexing-jobs snapshot handoff overflow; resubscribe");
        }
        snapshotSent.set(true);
      }
    } catch (java.sql.SQLException e) {
      closeSubscription.run();
      log.error("subscribeIndexingJobs: snapshot read failed", e);
      throw WorkerServiceException.internal("snapshot read failed: " + e.getMessage());
    } catch (RuntimeException | Error failure) {
      closeSubscription.run();
      throw failure;
    }
  }

  /**
   * Slice 445 §A.9: cancel an indexing job by path_hash. Resolves the hash
   * via {@code pathResolutionStore} (ADR-0028 reverse-lookup), then marks the
   * row terminal via {@code markDone} with a CANCELLED outcome. The
   * change-feed emits an UPDATE delta to subscribers.
   *
   * <p>Returns {@code cancelled=false} if the path_hash is unknown to the
   * resolution store; the previous_state is reported diagnostically.
   */
  public io.justsearch.ipc.CancelIndexingJobResponse cancelIndexingJob(
      io.justsearch.ipc.CancelIndexingJobRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      String pathHash = request.getPathHash();
      if (pathHash.isBlank()) {
        throw WorkerServiceException.invalidArgument(
            "CancelIndexingJobRequest.path_hash is required");
      }
      var resolved = pathResolutionStore.lookup(pathHash);
      if (resolved.isEmpty()) {
        return io.justsearch.ipc.CancelIndexingJobResponse.newBuilder()
            .setCancelled(false)
            .setPreviousState("UNKNOWN")
            .build();
      }
      String pathStr = resolved.get().normalizedPath();
      if (pathStr == null || pathStr.isBlank()) {
        return io.justsearch.ipc.CancelIndexingJobResponse.newBuilder()
            .setCancelled(false)
            .setPreviousState("REMOVED")
            .build();
      }
      Path path = Path.of(pathStr);
      var outcome =
          io.justsearch.indexerworker.ingest.IngestionOutcome.of(
              io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_PARTIAL,
              "CANCELLED",
              io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE,
              "cancelled via core.cancel-indexing-job");
      jobQueue.markDone(path, outcome);
      return io.justsearch.ipc.CancelIndexingJobResponse.newBuilder()
          .setCancelled(true)
          .setPreviousState("PENDING_OR_PROCESSING")
          .build();
    } catch (WorkerServiceException e) {
      // Must precede the RuntimeException catch-all: the blank-path_hash check above throws
      // INVALID_ARGUMENT from inside this try, and the catch-all would degrade it to INTERNAL
      // "cancel failed: ..." — a malformed request would read as a server fault.
      throw e;
    } catch (RuntimeException e) {
      log.error("cancelIndexingJob failed", e);
      throw WorkerServiceException.internal("cancel failed: " + e.getMessage());
    }
  }

  /**
   * Slice 445 §A.9: retry a terminal or backing-off indexing job by path_hash. Resolves the hash,
   * then re-enqueues. SqliteJobQueue overwrites the existing row with a fresh PENDING entry (path is
   * the primary key in the jobs table), reporting the state it replaced — read in the same
   * transaction, because the overwrite destroys it (tempdoc 885 §UD open item 1).
   */
  public io.justsearch.ipc.RetryIndexingJobResponse retryIndexingJob(
      io.justsearch.ipc.RetryIndexingJobRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      String pathHash = request.getPathHash();
      if (pathHash.isBlank()) {
        throw WorkerServiceException.invalidArgument(
            "RetryIndexingJobRequest.path_hash is required");
      }
      var resolved = pathResolutionStore.lookup(pathHash);
      if (resolved.isEmpty()) {
        return io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
            .setRetried(false)
            .setPreviousState("UNKNOWN")
            .build();
      }
      String pathStr = resolved.get().normalizedPath();
      if (pathStr == null || pathStr.isBlank()) {
        return io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
            .setRetried(false)
            .setPreviousState("REMOVED")
            .build();
      }
      Path path = Path.of(pathStr);
      JobQueue.ReenqueueResult result = jobQueue.reenqueue(JobQueue.EnqueueEntry.stat(path));
      if (result.accepted() == 0) {
        return io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
            .setRetried(false)
            .setPreviousState("NOT_RETRYABLE")
            .build();
      }
      // Tempdoc 885 §UD open item 1: the state the row ACTUALLY held, read in the same
      // transaction that overwrote it. This used to be the literal "FAILED", which was already
      // wrong for a PENDING-in-backoff job and became wrong for RETRY_EXHAUSTED too. A queue with
      // no row-state authority reports null, which surfaces as UNKNOWN rather than as a guess.
      String previousState = result.previousState();
      return io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
          .setRetried(true)
          .setPreviousState(
              previousState == null || previousState.isBlank() ? "UNKNOWN" : previousState)
          .build();
    } catch (WorkerServiceException e) {
      // Must precede the RuntimeException catch-all: the blank-path_hash check above throws
      // INVALID_ARGUMENT from inside this try, and the catch-all would degrade it to INTERNAL
      // "retry failed: ..." — a malformed request would read as a server fault.
      throw e;
    } catch (RuntimeException e) {
      log.error("retryIndexingJob failed", e);
      throw WorkerServiceException.internal("retry failed: " + e.getMessage());
    }
  }

  private static io.justsearch.ipc.IndexingJobView toJobView(IndexingJobChangeFeed.JobRow row) {
    return io.justsearch.ipc.IndexingJobView.newBuilder()
        .setPathHash(row.pathHash())
        .setState(row.state())
        .setAttempts(row.attempts())
        .setLastUpdatedMs(row.lastUpdatedMs())
        .setErrorMessage(row.errorMessage() == null ? "" : row.errorMessage())
        .setRetryAfterMs(row.retryAfterMs())
        .setCollection(row.collection())
        // Tempdoc 812 D2 — the rollup key: which directory scan enqueued this job (empty when none).
        .setScanId(row.scanId())
        .build();
  }

}
