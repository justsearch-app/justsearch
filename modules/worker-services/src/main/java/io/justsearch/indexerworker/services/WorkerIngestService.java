/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.IngestResponses.*;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.SwapReason;
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
import io.justsearch.ipc.VduUpdateOutcome;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
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
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.util.ParseUtils;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ort.OrtCudaStatus;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
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
  /** Maximum chars stored in `content_preview` (result list snippet field). */
  private static final int CONTENT_PREVIEW_MAX_CHARS =
      ChunkDocumentWriter.CONTENT_PREVIEW_MAX_CHARS;

  /** Maximum files allowed in a single batch request. */
  private static final int MAX_BATCH_SIZE = 10_000;

  /** Maximum queue depth before rejecting new submissions. */
  private static final long MAX_QUEUE_DEPTH = 100_000;

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
  private final OperationalMetrics metrics = OperationalMetrics.getInstance();
  private final IndexStatusOps statusOps;
  private final SyncDirectoryOps syncOps;
  private final IngestSwitchBufferOps switchBufferOps;
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
      long migrationSwitchingMaxDurationMs,
      Runnable restartWorkerCallback) {
    this.jobQueue = jobQueue;
    this.indexingLoop = indexingLoop;
    this.indexingPacing =
        java.util.Objects.requireNonNull(indexingPacing, "indexingPacing");
    this.ingestLifecycle = ingestLifecycle;
    this.indexGenerationManager = indexBasePath == null ? null : new IndexGenerationManager(indexBasePath);
    this.migrationOps = new MigrationControlOps(this.indexGenerationManager, restartWorkerCallback);
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
    try (var ignored = openRequestMdc(ctx)) {
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

        // Embedding/index safety: a forced reindex is an explicit "rebuild vectors" intent.
        // Signal the compatibility controller so it can enter REBUILDING and allow embedding writes.
        if (request.getForceReindex()) {
          var controller = statusOps.embeddingCompatController();
          if (controller != null) {
            controller.onForcedReindexRequested();
          }
        }

        // During cutover (SWITCHING), accept the request but buffer it durably instead of
        // mutating the job queue/index directly. This avoids dropping updates while the Worker
        // restarts.
        if (switchBufferOps.isSwitching()) {
          if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
            return switchBufferOps.bufferSubmitBatchDuringSwitching(
                sbq, validPaths, filePaths.size(), rejected);
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
                validPaths.stream().map(JobQueue.EnqueueEntry::stat).toList(), collection);

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

  public UpdateVduResultResponse updateVduResult(
      UpdateVduResultRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    String docId = request.getDocId();
    log.info("updateVduResult RPC called for doc: {}", docId);

    // Proto getters return empty string for unset values, not null
    UpdateVduResultResponse blank =
        blankReply(docId, updateVduErrorResponse("doc_id is required"));
    if (blank != null) {
      return blank;
    }

    UpdateVduResultResponse unavailable =
        indexRuntimeUnavailableReply(
            "updateVduResult", updateVduErrorResponse("Index runtime not available"));
    if (unavailable != null) {
      return unavailable;
    }

    if (switchBufferOps.isSwitching()) {
      // During cutover, accept and durably buffer VDU updates so Main does not misclassify the event as a hard failure.
      return switchBufferOps.bufferDuringSwitchingOrThrow(
          "updateVduResult",
          sbq -> switchBufferOps.bufferUpdateVduResultDuringSwitching(sbq, request, docId));
    }

    try {
      // Build the update map
      Map<String, Object> updates = new HashMap<>();

      // Determine effective outcome: prefer new 'outcome' field, fall back to legacy 'vdu_status' parsing
      VduUpdateOutcome outcome = request.getOutcome();
      String legacyStatus = request.getVduStatus();
      VduUpdateOutcome effectiveOutcome = computeEffectiveOutcome(outcome, legacyStatus);

      // Get extracted content (proto3 optional: hasExtractedContent() for presence check)
      boolean hasExtractedContent = request.hasExtractedContent();
      String extractedContent = hasExtractedContent ? request.getExtractedContent() : "";

      // Validate invariants based on outcome
      if (effectiveOutcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT) {
        if (!hasExtractedContent || extractedContent.isBlank()) {
          log.warn("updateVduResult: SUCCESS_TEXT requires non-blank extracted_content, got blank for doc: {}", docId);
          return updateVduErrorResponse("SUCCESS_TEXT requires non-blank extracted_content");
        }
      }

      // Apply updates based on effective outcome
      switch (effectiveOutcome) {
        case VDU_UPDATE_OUTCOME_SUCCESS_TEXT -> {
          // Overwrite content, language, embedding status; regenerate chunks
        String preview = contentPreview(extractedContent);
        updates.put(SchemaFields.CONTENT, extractedContent);
        // Tempdoc 931 §C.6: the content revision moves with the content it describes.
        updates.put(
            SchemaFields.CONTENT_SHA256,
            io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(extractedContent));
        updates.put(SchemaFields.CONTENT_PREVIEW, preview);
        updates.put(SchemaFields.LANGUAGE, resolveLanguage(preview));
        updates.put(SchemaFields.VDU_PROCESSED, "true");
          updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_COMPLETED);
          updates.put(SchemaFields.EXTRACTION_METHOD, SchemaFields.EXTRACTION_METHOD_VDU);
        // CRITICAL: Trigger re-embedding with new VDU-extracted content
        updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      }
        case VDU_UPDATE_OUTCOME_SUCCESS_EMPTY -> {
          // VDU succeeded but extracted no text (e.g., blank image)
          // Do NOT overwrite content/language, do NOT trigger re-embedding, do NOT regenerate chunks
          updates.put(SchemaFields.VDU_PROCESSED, "true");
          updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_COMPLETED_EMPTY);
          markExtractionDropoutUnrecovered(docId, updates);
          log.info("updateVduResult: VDU succeeded with no extractable text for doc: {}", docId);
        }
        case VDU_UPDATE_OUTCOME_FAILED -> {
          // VDU processing failed
          // Do NOT overwrite content/language, do NOT regenerate chunks
          updates.put(SchemaFields.VDU_PROCESSED, "true");
          updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_FAILED);
          markExtractionDropoutUnrecovered(docId, updates);
          log.info("updateVduResult: VDU failed for doc: {}", docId);
        }
        case VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT -> {
          // Tempdoc 677: the abstention gate judged the extraction untrustworthy — either the
          // model's non-empty output failed a post-call confidence check (suspected
          // confabulation), or the input-legibility gate skipped the model call entirely (no
          // page carried any textual signal). RETAIN the baseline content — no content/language
          // overwrite, no re-embedding, no chunk regeneration — and record the honest terminal
          // state (no re-queue) either way.
          updates.put(SchemaFields.VDU_PROCESSED, "true");
          updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_REJECTED);
          markExtractionDropoutUnrecovered(docId, updates);
          log.info(
              "updateVduResult: VDU output rejected by abstention gate, baseline retained for doc: {}",
              docId);
        }
        default -> {
          // UNSPECIFIED with no legacy status - treat as no-op but mark processed
          updates.put(SchemaFields.VDU_PROCESSED, "true");
          if (!legacyStatus.isBlank()) {
            updates.put(SchemaFields.VDU_STATUS, legacyStatus);
          }
          // Legacy behavior: overwrite content if non-blank
          if (!extractedContent.isBlank()) {
            String preview = contentPreview(extractedContent);
            updates.put(SchemaFields.CONTENT, extractedContent);
            // Tempdoc 931 §C.6: the content revision moves with the content it describes.
            updates.put(
                SchemaFields.CONTENT_SHA256,
                io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(extractedContent));
            updates.put(SchemaFields.CONTENT_PREVIEW, preview);
            updates.put(SchemaFields.LANGUAGE, resolveLanguage(preview));
            updates.put(SchemaFields.EXTRACTION_METHOD, SchemaFields.EXTRACTION_METHOD_VDU);
            updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
          }
        }
      }

      // VDU enrichment (JSON) - always apply if present
      String enrichment = request.getVduEnrichment();
      if (!enrichment.isBlank()) {
        updates.put(SchemaFields.VDU_ENRICHMENT, enrichment);
      }

      // Page count - always apply if positive
      int pageCount = request.getPageCount();
      if (pageCount > 0) {
        updates.put(SchemaFields.VDU_PAGE_COUNT, String.valueOf(pageCount));
      }

      // Perform the update
      boolean updated = ingestLifecycle.indexingCoordinator().updateDocument(docId, updates);

      if (updated) {
        // Regenerate chunk documents ONLY for SUCCESS_TEXT (or legacy non-blank content)
        boolean shouldRegenerateChunks =
            effectiveOutcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT ||
            (effectiveOutcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED && !extractedContent.isBlank());

        if (shouldRegenerateChunks) {
          int chunksIndexed = regenerateChunks(docId, extractedContent);
          if (chunksIndexed > 0) {
            log.info("updateVduResult: regenerated {} chunks for doc: {}", chunksIndexed, docId);
          }
        }

        // Ensure the updated doc is visible to immediate read-after-write callers
        // without relying on the async NRT refresh thread timing.
        // Commit is deferred to the periodic commit timer (DC7) / IndexingLoop cycle.
        ingestLifecycle.commitOps().maybeRefreshBlocking();
        log.info("updateVduResult success for doc: {} (outcome={})", docId, effectiveOutcome);
        return updateVduSuccessResponse();
      }
      log.warn("updateVduResult: document not found: {}", docId);
      return updateVduErrorResponse("Document not found: " + docId);

    } catch (Exception e) {
      log.error("updateVduResult failed for doc: {}", docId, e);
      return updateVduErrorResponse(e.getMessage());
    }
    }
  }

  /**
   * Computes the effective VDU outcome from the explicit outcome field or legacy status string.
   *
   * <p>Compatibility rule: if {@code outcome != UNSPECIFIED}, use it directly.
   * Otherwise, infer from the legacy {@code vdu_status} string.
   */
  // PERMANENT COMPAT - DO NOT REMOVE (bridges legacy vdu_status string to typed outcome enum)
  private VduUpdateOutcome computeEffectiveOutcome(VduUpdateOutcome outcome, String legacyStatus) {
    if (outcome != null && outcome != VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED) {
      return outcome;
    }
    // Fall back to legacy status parsing
    if (legacyStatus == null || legacyStatus.isBlank()) {
      return VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED;
    }
    return switch (legacyStatus.toUpperCase(Locale.ROOT)) {
      case "COMPLETED" -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT; // Assume text if legacy COMPLETED
      case "COMPLETED_EMPTY" -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY;
      case "FAILED" -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED;
      default -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED;
    };
  }

  /**
   * Regenerates chunk documents for a parent document after VDU processing.
   *
   * <p>Deletes existing chunks (using field-based deletion) and creates new ones
   * from the VDU-extracted content. This ensures RAG retrieval uses the improved
   * VDU text rather than stale Tika extraction.
   *
   * @param parentDocId the parent document ID (normalized path)
   * @param content the VDU-extracted content to chunk
   * @return number of chunks indexed
   */
  private int regenerateChunks(String parentDocId, String content) {
    if (ingestLifecycle == null) {
      log.warn("regenerateChunks: ingestLifecycle is null");
      return 0;
    }
    return ChunkDocumentWriter.regenerateChunksFromExistingParent(
        ingestLifecycle.documentFieldOps(), ingestLifecycle.indexingCoordinator(), parentDocId,
        content, chunkSpladeEnabled());
  }

  public DeleteByPathResponse deleteByPath(DeleteByPathRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
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
    try (var ignored = openRequestMdc(ctx)) {
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

  // ==================== Canonical metadata helpers (UX-oriented) ====================

  private static String contentPreview(String content) {
    return LanguageUtils.contentPreview(content, CONTENT_PREVIEW_MAX_CHARS);
  }

  private static String resolveLanguage(String preview) {
    return LanguageUtils.resolveLanguage(preview);
  }

  public DeleteByIdResponse deleteById(DeleteByIdRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
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
    try (var ignored = openRequestMdc(ctx)) {
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
    try (var ignored = openRequestMdc(ctx)) {
      String rootPath = request.getRootPath();
      boolean force = request.getForce();
      log.info("syncDirectory RPC called for root: {} (force={})", rootPath, force);

      // Validate input (protobuf returns empty string, not null)
      SyncDirectoryResponse blank =
          blankReply(rootPath, syncDirectoryErrorResponse("root_path is required"));
      if (blank != null) {
        return blank;
      }

      if (switchBufferOps.isSwitching()) {
        // During cutover, accept and durably buffer sync requests so OVERFLOW/burst events don't get lost.
        return switchBufferOps.bufferDuringSwitchingOrThrow(
            "syncDirectory",
            sbq -> switchBufferOps.bufferSyncDirectoryDuringSwitching(sbq, rootPath, force));
      }

      SyncDirectoryResponse unavailable =
          indexRuntimeUnavailableReply(
              "syncDirectory", syncDirectoryErrorResponse("Index runtime not available"));
      if (unavailable != null) {
        return unavailable;
      }

      return syncOps.execute(rootPath, force);
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
    if (root == null) {
      return;
    }
    SyncDirectoryRequest request =
        SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).setForce(force).build();
    try {
      SyncDirectoryResponse value = syncDirectory(request, CallContext.none());
      log.debug(
          "In-process reconcile for {} (force={}): {} added, {} deleted, skipped={}",
          root.toString(),
          force,
          value.getFilesAdded(),
          value.getFilesDeleted(),
          value.getSkipped());
    } catch (RuntimeException e) {
      // Best-effort by contract: a reconcile failure is logged and dropped, never propagated to
      // the watcher thread that called us.
      log.warn(
          "In-process reconcile failed for {} (force={}): {}", root.toString(), force,
          e.getMessage());
    }
  }

  public QueryPendingVduResponse queryPendingVdu(
      QueryPendingVduRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    int limit = request.getLimit();
    if (limit <= 0) {
      limit = 100;  // Default limit
    }

    log.debug("queryPendingVdu RPC called with limit: {}", limit);

    QueryPendingVduResponse unavailable =
        indexRuntimeUnavailableReply(
            "queryPendingVdu", QueryPendingVduResponse.newBuilder().setTotalCount(0).build());
    if (unavailable != null) {
      return unavailable;
    }

    try {
      // Query documents with vdu_status=PENDING
      List<String> docIds = ingestLifecycle.documentFieldOps().queryDocIdsByField(
          SchemaFields.VDU_STATUS,
          SchemaFields.VDU_STATUS_PENDING,
          limit);

      // Get total count (may be more than returned)
      int totalCount = ingestLifecycle.indexCountOps().countByField(
          SchemaFields.VDU_STATUS,
          SchemaFields.VDU_STATUS_PENDING);

      log.info("queryPendingVdu: returning {} of {} pending docs", docIds.size(), totalCount);

      return QueryPendingVduResponse.newBuilder()
          .addAllDocIds(docIds)
          .setTotalCount(totalCount)
          .build();

    } catch (Exception e) {
      log.error("queryPendingVdu failed", e);
      return QueryPendingVduResponse.newBuilder()
          .setTotalCount(0)
          .build();
    }
    }
  }

  public MarkVduProcessingResponse markVduProcessing(
      MarkVduProcessingRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    String docId = request.getDocId();
    int maxRetries = resolveMaxRetries(request.getMaxRetries());

    log.debug("markVduProcessing RPC called for doc: {}, maxRetries: {}", docId, maxRetries);

    MarkVduProcessingResponse blank =
        blankReply(docId, markVduErrorResponse("doc_id is required"));
    if (blank != null) {
      return blank;
    }

    MarkVduProcessingResponse unavailable =
        indexRuntimeUnavailableReply(
            "markVduProcessing", markVduErrorResponse("Index runtime not available"));
    if (unavailable != null) {
      return unavailable;
    }

    if (switchBufferOps.isSwitching()) {
      return switchBufferOps.bufferDuringSwitchingOrThrow(
          "markVduProcessing",
          sbq -> {
            String normalizedId = normalizeDocIdForMutation(docId);
            int currentCount = readVduRetryCountBestEffort(normalizedId);
            return switchBufferOps.bufferMarkVduDuringSwitching(
                sbq, normalizedId, currentCount, maxRetries);
          });
    }

    try {
      int currentCount = readVduRetryCountBestEffort(docId);
      return applyMarkVduProcessing(docId, currentCount, maxRetries);

    } catch (Exception e) {
      log.error("markVduProcessing failed for doc: {}", docId, e);
      return markVduErrorResponse(e.getMessage());
    }
    }
  }

  private static int resolveMaxRetries(int requestedMaxRetries) {
    return requestedMaxRetries <= 0 ? SchemaFields.VDU_MAX_RETRIES : requestedMaxRetries;
  }

  /**
   * Tempdoc 790 item 3 — close the dropout fallback chain at its last tier. A document indexed with
   * {@code EXTRACTION_DROPOUT_PENDING_FALLBACK} was queued for VDU precisely because no earlier
   * tier produced usable text; when VDU terminates without text (empty, failed, or rejected by the
   * abstention gate — none of which are re-queued: only PROCESSING is recovered to PENDING) the
   * document's honest terminal state is "no tier could read this," not a silently empty success.
   */
  private void markExtractionDropoutUnrecovered(String docId, Map<String, Object> updates) {
    String reasonCode =
        ingestLifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.EXTRACTION_REASON_CODE);
    if (!IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK.equals(reasonCode)) {
      return;
    }
    updates.put(SchemaFields.EXTRACTION_METHOD, SchemaFields.EXTRACTION_METHOD_NONE);
    updates.put(
        SchemaFields.EXTRACTION_REASON_CODE, IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED);
  }

  private int readVduRetryCountBestEffort(String docId) {
    String currentCountStr = ingestLifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_RETRY_COUNT);
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
      ingestLifecycle.indexingCoordinator().updateDocument(docId, updates);
      // Commit deferred to periodic commit timer (DC7) / IndexingLoop cycle.
      return markVduErrorResponse(VDU_MAX_RETRIES_EXCEEDED_ERROR);
    }

    // Increment retry count and mark as PROCESSING.
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
    updates.put(SchemaFields.VDU_RETRY_COUNT, String.valueOf(decision.retryCount()));

    boolean updated = ingestLifecycle.indexingCoordinator().updateDocument(docId, updates);
    if (updated) {
      // Commit deferred to periodic commit timer (DC7) / IndexingLoop cycle.
      log.debug(
          "markVduProcessing: doc {} marked PROCESSING, retry {}/{}",
          docId,
          decision.retryCount(),
          maxRetries);
      return markVduSuccessResponse(decision.retryCount());
    }
    return markVduErrorResponse("Document not found: " + docId);
  }

  private List<String> processingDocIdsForRecovery() {
    return ingestLifecycle.documentFieldOps().queryDocIdsByField(
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

  static int recoverProcessingDocsWithResetOp(
      List<String> processingDocIds, VduProcessingResetOp resetOp) {
    int recovered = 0;
    for (String docId : processingDocIds) {
      try {
        // Reset to PENDING (retry count already incremented, so won't loop forever).
        if (resetOp.resetToPending(docId)) {
          recovered++;
        }
      } catch (Exception e) {
        log.warn("Failed to recover doc: {}", docId, e);
      }
    }
    return recovered;
  }

  private int recoverProcessingDocs(List<String> processingDocIds) {
    return recoverProcessingDocsWithResetOp(processingDocIds, this::resetVduStatusToPending);
  }

  public RecoverVduProcessingResponse recoverVduProcessing(
      RecoverVduProcessingRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    log.info("recoverVduProcessing RPC called");

    RecoverVduProcessingResponse unavailable =
        indexRuntimeUnavailableReply("recoverVduProcessing", recoverVduCountResponse(0));
    if (unavailable != null) {
      return unavailable;
    }

    if (switchBufferOps.isSwitching()) {
      // During cutover, accept and durably buffer recovery so clients don't depend on retries.
      return switchBufferOps.bufferDuringSwitchingOrThrow(
          "recoverVduProcessing",
          switchBufferOps::bufferRecoverVduProcessingDuringSwitching);
    }

    try {
      List<String> processingDocIds = processingDocIdsForRecovery();

      if (processingDocIds.isEmpty()) {
        log.info("recoverVduProcessing: no stuck documents found");
        return recoverVduCountResponse(0);
      }

      log.info("recoverVduProcessing: found {} stuck documents", processingDocIds.size());

      int recovered = recoverProcessingDocs(processingDocIds);

      // Commit deferred to periodic commit timer (DC7) / IndexingLoop cycle.

      log.info("recoverVduProcessing: recovered {} of {} documents", recovered, processingDocIds.size());

      return recoverVduCountResponse(recovered);

    } catch (Exception e) {
      log.error("recoverVduProcessing failed", e);
      return recoverVduCountResponse(0);
    }
    }
  }

  public UpdatePathsResponse updateDocumentPaths(
      UpdatePathsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
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
    try (var ignored = openRequestMdc(ctx)) {
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
    // Tempdoc 419 / T2 — Allocate the scanId at RPC entry. The same value is stamped on every
    // emitted ScanRootProgress event so SSE consumers (T4) can subscribe by scanId, and so
    // log entries from this scan correlate via MDC.
    String scanId = java.util.UUID.randomUUID().toString();
    try (var ignored = openRequestMdc(ctx);
        var ignored2 = MdcContext.scan(scanId)) {
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
              root, request.getCollection(), mode, request.getExcludeGlobsList(), scanId);
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
        new WorkerScanOps(jobQueue, queueDepth, isCancelled, paths -> indexingLoop.markForced(paths))
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
    try (var ignored = openRequestMdc(ctx)) {
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
    try (var ignored = openRequestMdc(ctx)) {
      boolean removed = rootWatcherRegistry.unwatch(request.getRootPath());
      return io.justsearch.ipc.UnwatchRootResponse.newBuilder().setUnwatched(removed).build();
    }
  }

  public ResetIndexResponse resetIndex(ResetIndexRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
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
    java.util.concurrent.atomic.AtomicReference<IndexingJobChangeFeed.Subscription> subRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicLong frameSeq =
        new java.util.concurrent.atomic.AtomicLong(0L);

    java.util.function.Consumer<IndexingJobChangeFeed.Delta> consumer =
        delta -> {
          if (ctx.cancelled()) return;
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
              IndexingJobChangeFeed.Subscription s = subRef.get();
              if (s != null) s.close();
            }
          }
        };

    ctx.onCancel(
        () -> {
          IndexingJobChangeFeed.Subscription s = subRef.get();
          if (s != null) s.close();
        });

    try {
      var snap = feed.subscribeWithSnapshot(consumer);
      subRef.set(snap.subscription());

      io.justsearch.ipc.IndexingJobsSnapshot.Builder snapBuilder =
          io.justsearch.ipc.IndexingJobsSnapshot.newBuilder();
      for (IndexingJobChangeFeed.JobRow row : snap.items()) {
        snapBuilder.addItems(toJobView(row));
      }
      synchronized (emitLock) {
        sink.accept(
            io.justsearch.ipc.IndexingJobsFrame.newBuilder()
                .setSnapshot(snapBuilder.build())
                .setSeq(frameSeq.incrementAndGet())
                .build());
      }
    } catch (java.sql.SQLException e) {
      log.error("subscribeIndexingJobs: snapshot read failed", e);
      throw WorkerServiceException.internal("snapshot read failed: " + e.getMessage());
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
    try (var ignored = openRequestMdc(ctx)) {
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
    try (var ignored = openRequestMdc(ctx)) {
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
