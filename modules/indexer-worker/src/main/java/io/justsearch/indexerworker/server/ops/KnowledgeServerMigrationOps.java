/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.CleanShutdownMarker;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.index.MigrationProgressStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerServiceException;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;

public final class KnowledgeServerMigrationOps {
  private KnowledgeServerMigrationOps() {}

  public record CutoverContext(
      IndexGenerationManager indexGenerationManager,
      JobQueue jobQueue,
      BooleanSupplier runningSupplier,
      BooleanSupplier migrationEnumeratorDoneSupplier,
      long migrationSwitchingQueueDepthThreshold,
      long migrationSwitchingMaxDurationMs,
      int migrationCutoverMaxFailedJobs,
      Supplier<LuceneRuntime> ingestLifecycleSupplier,
      // Tempdoc 598 review Fix E: deterministically finalize the embedding rebuild (flip the ECC to
      // COMPATIBLE iff the green is fully embedded) BEFORE the COMPLETE commit, so that commit stamps
      // the embedding fingerprint deterministically rather than racing the indexing-loop thread.
      BooleanSupplier finalizeEmbeddingRebuildAction,
      BooleanSupplier verifyGreenCommitMetadataSupplier,
      Runnable drainSwitchBufferAction,
      // Tempdoc 915 (live validation D4): flush the worker metrics snapshot before the cutover
      // restart. The snapshot cadence is 60s and the cutover restarts the worker roughly 20s after
      // the migration starts, so the session that performed the cutover was discarded before any
      // snapshot was written - and commit_by_reason therefore never carried migration/cutover in a
      // live run, though both emit sites are production code.
      Runnable flushTelemetryAction,
      Runnable requestedRestartAction,
      Path dataDir,
      Logger log) {}

  public record DrainSwitchBufferContext(
      JobQueue jobQueue,
      RunningRuntime ingestLifecycle,
      WorkerSignalBus signalBus,
      // Tempdoc 885 item 3: the replayed sync/prune ops pace against foreground load like every
      // other indexing path — a cutover drain is background work too.
      IndexingPacing indexingPacing,
      Path indexBasePath,
      Path activeIndexPath,
      ObjectMapper json,
      // Tempdoc 931 §E item 8: rag.chunk_splade.enabled, read from the LIVE resolved config each
      // time a buffered VDU_UPDATE regenerates chunks — a drain can span a config change.
      BooleanSupplier chunkSpladeEnabledSupplier,
      Logger log) {}

  public record EnqueueContext(
      List<Path> roots,
      JobQueue jobQueue,
      BooleanSupplier runningSupplier,
      Supplier<IndexGenerationManager> indexGenerationManagerSupplier,
      AtomicLong migrationEnumeratorFilesSeen,
      AtomicLong migrationEnumeratorFilesEnqueued,
      AtomicLong migrationEnumeratorRootsDone,
      AtomicReference<String> migrationEnumeratorLastPath,
      Supplier<MigrationProgressStore> migrationProgressStoreSupplier,
      Supplier<MigrationProgressSnapshot> migrationProgressSnapshotSupplier,
      Consumer<MigrationProgressSnapshot> persistedSnapshotSetter,
      Logger log) {}

  public static IndexGenerationManager.MigrationState parseMigrationState(String raw) {
    if (raw == null || raw.isBlank()) {
      return IndexGenerationManager.MigrationState.IDLE;
    }
    try {
      return IndexGenerationManager.MigrationState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (Exception expected) {
      // Unrecognized migration state string — treat as failed
      return IndexGenerationManager.MigrationState.FAILED;
    }
  }

  public static void runMigrationCutoverLoop(CutoverContext context) {
    if (context.indexGenerationManager() == null || context.jobQueue() == null) {
      return;
    }
    while (context.runningSupplier().getAsBoolean() && !Thread.currentThread().isInterrupted()) {
      try {
        IndexGenerationManager.State state = context.indexGenerationManager().readStateBestEffort();
        IndexGenerationManager.MigrationState ms =
            parseMigrationState(state == null ? null : state.migration_state());
        if (state != null && Boolean.TRUE.equals(state.migration_paused())) {
          Thread.sleep(1_000);
          continue;
        }
        if (ms == IndexGenerationManager.MigrationState.IDLE
            || ms == IndexGenerationManager.MigrationState.FAILED) {
          return;
        }

        if (!context.migrationEnumeratorDoneSupplier().getAsBoolean()) {
          Thread.sleep(1_000);
          continue;
        }

        long depth = context.jobQueue().queueDepth();
        if (ms == IndexGenerationManager.MigrationState.MIGRATING) {
          if (depth > context.migrationSwitchingQueueDepthThreshold()) {
            Thread.sleep(1_000);
            continue;
          }
          context.log().info(
              "Migration nearing completion (queueDepth={}). Entering SWITCHING cutover fence...",
              depth);
          context
              .indexGenerationManager()
              .updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
          Thread.sleep(250);
          continue;
        }

        if (ms != IndexGenerationManager.MigrationState.SWITCHING) {
          return;
        }

        long now = System.currentTimeMillis();
        long switchingAgeMs = state == null ? 0L : Math.max(0L, now - state.updated_at_ms());
        if (switchingAgeMs > context.migrationSwitchingMaxDurationMs()) {
          context.log().warn(
              "Migration cutover failed: SWITCHING exceeded deadline (ageMs={}, queueDepth={})",
              switchingAgeMs,
              depth);
          context
              .indexGenerationManager()
              .updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
          context.drainSwitchBufferAction().run();
          return;
        }

        boolean drained = depth == 0;
        if (!drained) {
          JobQueue.JobStateCounts counts = context.jobQueue().jobStateCounts();
          drained = counts.processingCount() == 0 && counts.pendingReadyCount() == 0;
          if (drained && counts.pendingBackoffCount() > 0) {
            context.log().info(
                "Migration SWITCHING drain condition satisfied with only backoff jobs remaining (pendingBackoff={})",
                counts.pendingBackoffCount());
          }
        }
        if (!drained) {
          Thread.sleep(500);
          continue;
        }

        context.log().info(
            "Migration drain criteria met in SWITCHING (queueDepth={}). Finalizing cutover...",
            depth);

        long failedJobs = 0L;
        try {
          failedJobs = context.jobQueue().failureSummary().failedCount();
        } catch (Exception e) {
          context.log().warn(
              "Failed to query failed jobs count for cutover guardrail (proceeding with 0): {}",
              e.getMessage());
        }
        if (context.migrationCutoverMaxFailedJobs() >= 0
            && failedJobs > context.migrationCutoverMaxFailedJobs()) {
          context.log().warn(
              "Migration cutover blocked: failedJobs={} exceeds maxFailedJobs={} (keeping Blue active)",
              failedJobs,
              context.migrationCutoverMaxFailedJobs());
          context
              .indexGenerationManager()
              .updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
          context.drainSwitchBufferAction().run();
          return;
        }

        LuceneRuntime ingestLifecycle = context.ingestLifecycleSupplier().get();
        if (ingestLifecycle == null) {
          context.log().warn("Migration cutover failed: ingestLifecycle is null");
          context
              .indexGenerationManager()
              .updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
          context.drainSwitchBufferAction().run();
          return;
        }

        if (!context.finalizeEmbeddingRebuildAction().getAsBoolean()) {
          // Startup root scanning can enqueue before deferred embeddings are ready. A drained
          // primary queue therefore does not imply that Green's embedding backfill has drained.
          // Keep the existing SWITCHING deadline and verification; pending work is not failure.
          Thread.sleep(500);
          continue;
        }

        try {
          // Tempdoc 598 review Fix E: finalize the embedding rebuild on this (drained) green BEFORE
          // the COMPLETE commit. This deterministically flips the ECC to COMPATIBLE iff the green is
          // fully embedded, so the COMPLETE commit's overlay stamps embedding_model_sha256 — rather
          // than racing the indexing-loop thread that would otherwise flip rebuildCompleted. A green
          // that is genuinely not fully embedded is NOT flipped, so its commit lacks the fingerprint
          // and the verification below correctly blocks promotion (no false promote-into-BLOCKED).
          // Phase 5 (folded into Phase 2-3 Step C): commitWithBuildState replaces the
          // setBuildState + commit two-step. Updates ctx.buildState then commits, so
          // the final commit (and any subsequent timer commit) stamps build_state=COMPLETE.
          ingestLifecycle
              .commitOps()
              .commitWithBuildState(
                  LuceneRuntimeTypes.BuildState.COMPLETE, CommitReason.MIGRATION_CUTOVER);
        } catch (Exception e) {
          context.log().warn(
              "Migration cutover failed: final commit failed (keeping Blue): {}", e.getMessage());
          context
              .indexGenerationManager()
              .updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
          context.drainSwitchBufferAction().run();
          return;
        }

        if (!context.verifyGreenCommitMetadataSupplier().getAsBoolean()) {
          context.log().warn("Migration cutover failed: Green verification failed; keeping Blue active");
          context
              .indexGenerationManager()
              .updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
          context.drainSwitchBufferAction().run();
          return;
        }

        IndexGenerationManager.State promoted =
            context.indexGenerationManager().promoteBuildingGenerationToActive();
        try { Files.deleteIfExists(context.dataDir().resolve(".help-ingested-version")); }
        catch (IOException ignored) {
          // Best-effort cleanup of stale marker; failure is non-fatal to cutover.
        }
        preserveEvidenceBeforeRestart(context, promoted);
        context.log().info("Migration promoted generation {}; requesting Engine restart",
            promoted == null ? "(unknown)" : promoted.active_generation());
        context.requestedRestartAction().run();
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        context.log().warn("Migration cutover monitor failed (continuing): {}", e.getMessage());
        try {
          Thread.sleep(5_000);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  public static boolean verifyGreenCommitMetadataBestEffort(
      LuceneRuntime ingestLifecycle, Logger log) {
    return verifyGreenCommitMetadataBestEffort(ingestLifecycle, null, log);
  }

  /**
   * Verifies the green generation's commit metadata before promotion (blue/green cutover).
   *
   * <p>Tempdoc 598 R3: adds the embedding-fingerprint half. A blue/green rebuild that re-establishes
   * embedding compatibility must not promote a green that lacks a current-model embedding fingerprint
   * — otherwise the promoted generation would serve {@code BLOCKED_LEGACY} despite a "successful"
   * rebuild (the durability/observability trap from tempdoc 593 §H). The migration re-embeds inline,
   * so a completed green is fully embedded with the current model and its COMPLETE commit stamps
   * {@code embedding_model_sha256} (via the embedding overlay once the ECC's rebuild has drained).
   * When {@code expectedEmbeddingFp} is non-blank (an embedding model is resolvable) the committed
   * fingerprint must be present AND match; when it is blank (no embedding model — a legitimately
   * keyword-only rebuild) the embedding check is skipped, mirroring the schema check's
   * metadata-disabled skip. Returns true only when every present check passes.
   */
  public static boolean verifyGreenCommitMetadataBestEffort(
      LuceneRuntime ingestLifecycle, String expectedEmbeddingFp, Logger log) {
    try {
      if (ingestLifecycle == null) {
        return false;
      }
      if (!ingestLifecycle.commitMetadataEnabled()) {
        log.warn("Green verification skipped because commit metadata is disabled");
        return true;
      }
      return verifyGreenMetadata(
          ingestLifecycle.latestCommitUserDataBestEffort(), expectedEmbeddingFp, log);
    } catch (Exception e) {
      log.warn("Green verification failed (best-effort): {}", e.getMessage());
      return false;
    }
  }

  /**
   * Pure verification of a green generation's committed user-data (the IO-free core of
   * {@link #verifyGreenCommitMetadataBestEffort}). Package-private so the build-state /
   * index-fingerprint / embedding-fp rules can be unit-tested without a (sealed) {@link LuceneRuntime} double.
   */
  static boolean verifyGreenMetadata(
      Map<String, String> ud, String expectedEmbeddingFp, Logger log) {
    String buildState = ud.get("build_state");
    if (!"COMPLETE".equalsIgnoreCase(buildState)) {
      log.warn("Green verification failed: build_state={} (expected COMPLETE)", buildState);
      return false;
    }
    String committedFingerprint = ud.get(IndexFingerprint.COMMIT_META_KEY);
    Object expectedRaw = new SsotCommitMetadataSource().build().get(IndexFingerprint.COMMIT_META_KEY);
    String expectedFingerprint = expectedRaw == null ? null : String.valueOf(expectedRaw);
    if (committedFingerprint == null || committedFingerprint.isBlank()) {
      log.warn("Green verification failed: committed index_fingerprint is missing");
      return false;
    }
    if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
      // This runtime cannot compute a truthful fingerprint right now, so it cannot attest that the
      // green it just built is the shape it meant to build. Refuse the promotion rather than
      // promote on an absence of evidence; the cutover retries on the next boot.
      log.warn(
          "Green verification failed: this runtime could not compute an expected index_fingerprint"
              + " (a configured model digest is unresolvable)");
      return false;
    }
    if (!Objects.equals(committedFingerprint, expectedFingerprint)) {
      log.warn(
          "Green verification failed: index_fingerprint mismatch committed={} expected={}",
          committedFingerprint,
          expectedFingerprint);
      return false;
    }
    // 598 R3: embedding fingerprint — only enforced when an embedding model is resolvable.
    if (expectedEmbeddingFp != null && !expectedEmbeddingFp.isBlank()) {
      String committedEmbeddingFp =
          ud.get(io.justsearch.indexerworker.embed.EmbeddingCompatibilityController.COMMIT_META_KEY);
      if (committedEmbeddingFp == null || committedEmbeddingFp.isBlank()) {
        log.warn(
            "Green verification failed: embedding_model_sha256 missing on a green that should be "
                + "embedded (expected={})",
            expectedEmbeddingFp);
        return false;
      }
      if (!Objects.equals(committedEmbeddingFp, expectedEmbeddingFp)) {
        log.warn(
            "Green verification failed: embedding_model_sha256 mismatch committed={} expected={}",
            committedEmbeddingFp,
            expectedEmbeddingFp);
        return false;
      }
    }
    return true;
  }

  /**
   * Records what the restarting process would otherwise take with it. The cutover restart is the one
   * shutdown the worker performs on its own, and neither of these facts survived it (tempdoc 915,
   * live validation).
   *
   * <p><b>Clean-shutdown marker.</b> The promoted generation has just taken its {@code COMPLETE}
   * commit and been verified, so it is clean by construction at this instant. Leaving the marker to
   * {@code RuntimeSession.close()} made that contingent on the whole shutdown sequence completing
   * before the process goes away, and live runs showed the next boot logging {@code Unclean previous
   * shutdown detected} for the freshly promoted generation and paying a FULL integrity verification
   * for it. Writing it here states a fact that is true now rather than hoping a later step runs.
   *
   * <p><b>Telemetry flush.</b> Same shape: the cutover commit is counted in-process and the periodic
   * snapshot is minutes away.
   *
   * <p>Both are best-effort. A failure costs an integrity scan or a lost counter, never correctness,
   * and must not abort a cutover that has already completed.
   */
  // Package-private: the cutover loop that calls it needs a live migration to reach, and the two
  // facts it records are observable directly (a marker file, a Runnable that ran).
  static void preserveEvidenceBeforeRestart(
      CutoverContext context, IndexGenerationManager.State promoted) {
    try {
      String activeGen = promoted == null ? null : promoted.active_generation();
      if (activeGen != null && !activeGen.isBlank()) {
        CleanShutdownMarker.write(
            context.indexGenerationManager().resolveGenerationPathStrict(activeGen));
      }
    } catch (Exception e) {
      context.log().debug("Clean-shutdown marker not written before cutover restart: {}", e.getMessage());
    }
    try {
      if (context.flushTelemetryAction() != null) {
        context.flushTelemetryAction().run();
      }
    } catch (Exception e) {
      context.log().debug("Telemetry flush before cutover restart failed: {}", e.getMessage());
    }
  }

  public static void drainSwitchBufferBestEffort(DrainSwitchBufferContext context) {
    if (!(context.jobQueue() instanceof SwitchBufferCapableQueue sbq)) {
      return;
    }
    List<SwitchBufferCapableQueue.SwitchBufferOp> ops = sbq.listSwitchBufferOps();
    if (ops.isEmpty()) {
      return;
    }
    context.log().info("Draining {} buffered ops from durable switch buffer...", ops.size());

    ArrayList<io.justsearch.indexerworker.queue.SwitchBufferUpsert> toEnqueue = new ArrayList<>();
    boolean mutatedLucene = false;
    boolean allApplied = true;

    for (SwitchBufferCapableQueue.SwitchBufferOp op : ops) {
      if (op == null || op.op() == null || op.payload() == null) {
        continue;
      }
      String kind = op.op().trim().toUpperCase(Locale.ROOT);
      String payload = op.payload();
      switch (kind) {
        case "UPSERT" -> {
          if (!payload.isBlank()) {
            try {
              toEnqueue.add(io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(payload));
            } catch (Exception e) {
              allApplied = false;
              context
                  .log()
                  .warn(
                      "Failed to replay buffered UPSERT (skipping): key={} err={}",
                      op.key(),
                      e.getMessage());
            }
          }
        }
        case "DELETE" -> {
          if (context.ingestLifecycle() != null && !payload.isBlank()) {
            try {
              context.ingestLifecycle().indexingCoordinator().deleteByIdAndChunks(payload);
              mutatedLucene = true;
              context.jobQueue().deleteByExactPath(payload);
            } catch (Exception e) {
              allApplied = false;
              context
                  .log()
                  .warn(
                      "Failed to replay buffered DELETE (skipping): key={} err={}",
                      op.key(),
                      e.getMessage());
            }
          }
        }
        case "DELETE_PREFIX" -> {
          if (context.ingestLifecycle() != null && !payload.isBlank()) {
            try {
              context.ingestLifecycle().indexingCoordinator().deleteByPathPrefix(payload);
              mutatedLucene = true;
              context.jobQueue().deleteByPathPrefix(payload);
            } catch (Exception e) {
              allApplied = false;
              context
                  .log()
                  .warn(
                      "Failed to replay buffered DELETE_PREFIX (skipping): key={} err={}",
                      op.key(),
                      e.getMessage());
            }
          }
        }
        case "VDU_MARK_PROCESSING" -> {
          if (context.ingestLifecycle() != null && !payload.isBlank()) {
            try {
              var node = context.json().readTree(payload);
              String docId = node.path("doc_id").asText();
              int retryCount = node.path("retry_count").asInt();
              Map<String, Object> updates = new HashMap<>();
              updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
              updates.put(SchemaFields.VDU_RETRY_COUNT, String.valueOf(retryCount));
              boolean updated = context.ingestLifecycle().indexingCoordinator().updateDocument(docId, updates);
              if (!updated) {
                context.log().warn("Buffered VDU_MARK_PROCESSING: document not found: {}", docId);
              }
              mutatedLucene = true;
            } catch (Exception e) {
              allApplied = false;
              context
                  .log()
                  .warn(
                      "Failed to replay buffered VDU_MARK_PROCESSING: key={} err={}",
                      op.key(),
                      e.getMessage());
            }
          }
        }
        case "VDU_MARK_FAILED" -> {
          if (context.ingestLifecycle() != null && !payload.isBlank()) {
            try {
              var node = context.json().readTree(payload);
              String docId = node.path("doc_id").asText();
              Map<String, Object> updates = new HashMap<>();
              updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_FAILED);
              updates.put(SchemaFields.VDU_ENRICHMENT, "{\"error\": \"Max retries exceeded\"}");
              boolean updated = context.ingestLifecycle().indexingCoordinator().updateDocument(docId, updates);
              if (!updated) {
                context.log().warn("Buffered VDU_MARK_FAILED: document not found: {}", docId);
              }
              mutatedLucene = true;
            } catch (Exception e) {
              allApplied = false;
              context
                  .log()
                  .warn(
                      "Failed to replay buffered VDU_MARK_FAILED: key={} err={}",
                      op.key(),
                      e.getMessage());
            }
          }
        }
        case "VDU_RECOVER_PROCESSING" -> {
          if (context.ingestLifecycle() != null) {
            try {
              WorkerIngestService tmp =
                  new WorkerIngestService(
                      context.jobQueue(),
                      null,
                      context.signalBus(),
                      context.indexingPacing(),
                      context.indexBasePath(),
                      context.activeIndexPath(),
                      context.ingestLifecycle(),
                      null,
                      null,
                      0L);
              RecoverVduProcessingResponse resp;
              try {
                resp =
                    tmp.recoverVduProcessing(
                        RecoverVduProcessingRequest.getDefaultInstance(), CallContext.none());
              } catch (WorkerServiceException wse) {
                allApplied = false;
                context
                    .log()
                    .warn(
                        "Failed to replay buffered VDU_RECOVER_PROCESSING (will retry later): key={} err={}",
                        op.key(),
                        wse.getMessage());
                break;
              }
              int recovered = resp == null ? 0 : resp.getRecoveredCount();
              if (recovered > 0) {
                mutatedLucene = true;
              }
              context.log().info("Replayed buffered VDU_RECOVER_PROCESSING: recovered={}", recovered);
            } catch (Exception e) {
              allApplied = false;
              context
                  .log()
                  .warn(
                      "Failed to replay buffered VDU_RECOVER_PROCESSING: key={} err={}",
                      op.key(),
                      e.getMessage());
            }
          }
        }
        case "VDU_UPDATE" -> {
          if (context.ingestLifecycle() != null && !payload.isBlank()) {
            try {
              var node = context.json().readTree(payload);
              String docId = node.path("doc_id").asText();
              String extracted =
                  node.path("extracted_content").isNull()
                      ? null
                      : node.path("extracted_content").asText("");
              boolean hasExtracted =
                  node.path("has_extracted_content").asBoolean(extracted != null && !extracted.isBlank());
              String vduStatus = node.path("vdu_status").asText("");
              String enrichment = node.path("vdu_enrichment").asText("");
              int pageCount = node.path("page_count").asInt(0);
              int outcomeNum = node.path("outcome").asInt(0);

              io.justsearch.ipc.VduUpdateOutcome outcome =
                  io.justsearch.ipc.VduUpdateOutcome.forNumber(outcomeNum);
              if (outcome == null
                  || outcome == io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED) {
                if ("FAILED".equalsIgnoreCase(vduStatus)) {
                  outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED;
                } else if ("COMPLETED_EMPTY".equalsIgnoreCase(vduStatus)) {
                  outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY;
                } else if (hasExtracted && extracted != null && !extracted.isBlank()) {
                  outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT;
                } else {
                  outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY;
                }
              }

              Map<String, Object> updates = new HashMap<>();

              switch (outcome) {
                case VDU_UPDATE_OUTCOME_SUCCESS_TEXT -> {
                  if (extracted != null && !extracted.isBlank()) {
                    String preview =
                        io.justsearch.indexerworker.services.LanguageUtils.contentPreview(extracted, 4096);
                    updates.put(SchemaFields.CONTENT, extracted);
                    // Tempdoc 931 §C.6: the content revision moves with the content it describes.
                    updates.put(
                        SchemaFields.CONTENT_SHA256,
                        io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(extracted));
                    updates.put(SchemaFields.CONTENT_PREVIEW, preview);
                    updates.put(
                        SchemaFields.LANGUAGE,
                        io.justsearch.indexerworker.services.LanguageUtils.resolveLanguage(preview));
                    updates.put(SchemaFields.VDU_PROCESSED, "true");
                    updates.put(SchemaFields.VDU_STATUS, "COMPLETED");
                    updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
                  } else {
                    updates.put(SchemaFields.VDU_STATUS, "COMPLETED_EMPTY");
                    updates.put(SchemaFields.VDU_PROCESSED, "true");
                  }
                }
                case VDU_UPDATE_OUTCOME_SUCCESS_EMPTY -> {
                  updates.put(SchemaFields.VDU_STATUS, "COMPLETED_EMPTY");
                  updates.put(SchemaFields.VDU_PROCESSED, "true");
                }
                case VDU_UPDATE_OUTCOME_FAILED -> {
                  updates.put(SchemaFields.VDU_STATUS, "FAILED");
                  updates.put(SchemaFields.VDU_PROCESSED, "true");
                }
                default -> {
                  if (!vduStatus.isBlank()) {
                    updates.put(SchemaFields.VDU_STATUS, vduStatus);
                  }
                  updates.put(SchemaFields.VDU_PROCESSED, "true");
                }
              }

              if (!enrichment.isBlank()) {
                updates.put(SchemaFields.VDU_ENRICHMENT, enrichment);
              }
              if (pageCount > 0) {
                updates.put(SchemaFields.VDU_PAGE_COUNT, String.valueOf(pageCount));
              }

              boolean updated = context.ingestLifecycle().indexingCoordinator().updateDocument(docId, updates);
              if (!updated) {
                context.log().warn("Buffered VDU_UPDATE: document not found: {}", docId);
              } else if (outcome == io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT
                  && extracted != null
                  && !extracted.isBlank()) {
                try {
                  int chunksRegenerated =
                      ChunkDocumentWriter.regenerateChunksFromExistingParent(
                          context.ingestLifecycle().documentFieldOps(),
                          context.ingestLifecycle().indexingCoordinator(),
                          docId, extracted,
                          context.chunkSpladeEnabledSupplier().getAsBoolean());
                  if (chunksRegenerated > 0) {
                    context
                        .log()
                        .debug(
                            "Buffered VDU_UPDATE: regenerated {} chunks for {}",
                            chunksRegenerated,
                            docId);
                  }
                } catch (Exception ce) {
                  context
                      .log()
                      .warn(
                          "Buffered VDU_UPDATE: chunk regeneration failed for {}: {}",
                          docId,
                          ce.getMessage());
                }
              }
              mutatedLucene = true;
              context.log().debug("Replayed buffered VDU_UPDATE: docId={} outcome={}", docId, outcome);
            } catch (Exception e) {
              allApplied = false;
              context
                  .log()
                  .warn(
                      "Failed to replay buffered VDU_UPDATE: key={} err={}",
                      op.key(),
                      e.getMessage());
            }
          }
        }
        case "SYNC_ROOT" -> {
          if (context.ingestLifecycle() == null || payload.isBlank()) {
            allApplied = false;
          } else {
            try {
              var buffered = io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(payload);
              String rootPath = buffered.rootPath();
              boolean force = buffered.force();
              JobQueue.EnqueueProvenance provenance = buffered.provenance();

              WorkerIngestService tmp =
                  new WorkerIngestService(
                      context.jobQueue(),
                      null,
                      context.signalBus(),
                      context.indexingPacing(),
                      context.indexBasePath(),
                      context.activeIndexPath(),
                      context.ingestLifecycle(),
                      null,
                      null,
                      0L);
              SyncDirectoryRequest req =
                  SyncDirectoryRequest.newBuilder().setRootPath(rootPath).setForce(force).build();

              SyncDirectoryResponse r;
              try {
                r = tmp.syncDirectoryForReplay(req, provenance);
              } catch (WorkerServiceException wse) {
                allApplied = false;
                context
                    .log()
                    .warn(
                        "Failed to replay buffered SYNC_ROOT (will retry later): key={} err={}",
                        op.key(),
                        wse.getMessage());
                break;
              }

              if (r != null && !r.getError().isBlank()) {
                allApplied = false;
                context
                    .log()
                    .warn(
                        "Buffered SYNC_ROOT replay returned error (will retry later): key={} error={}",
                        op.key(),
                        r.getError());
              } else {
                context.log().info("Replayed buffered SYNC_ROOT: root={} force={}", rootPath, force);
              }
            } catch (Exception e) {
              allApplied = false;
              context
                  .log()
                  .warn(
                      "Failed to replay buffered SYNC_ROOT: key={} err={}",
                      op.key(),
                      e.getMessage());
            }
          }
        }
        case "PRUNE_PREFIX" -> {
          if (context.ingestLifecycle() != null && !payload.isBlank()) {
            try {
              int result = context.ingestLifecycle().pruneOps().pruneByPathPrefix(payload, () -> false, 100);
              boolean aborted = result < 0;
              int pruned = Math.max(0, result);
              if (aborted) {
                allApplied = false;
                context
                    .log()
                    .warn("Buffered PRUNE_PREFIX replay aborted unexpectedly: prefix={}", payload);
              } else {
                mutatedLucene = mutatedLucene || pruned > 0;
                context
                    .log()
                    .info("Replayed buffered PRUNE_PREFIX: prefix={} pruned={}", payload, pruned);
              }
            } catch (Exception e) {
              allApplied = false;
              context
                  .log()
                  .warn(
                      "Failed to replay buffered PRUNE_PREFIX: key={} err={}",
                      op.key(),
                      e.getMessage());
            }
          }
        }
        default -> context.log().warn("Unknown switch buffer op '{}': key={}", kind, op.key());
      }
    }

    if (!toEnqueue.isEmpty()) {
      int enqueued = 0;
      for (var upsert : toEnqueue) {
        int accepted = context.jobQueue().enqueueEntries(List.of(upsert.entry()), upsert.collection());
        enqueued += accepted;
        // A refused enqueue must leave the durable buffer available for the next replay.
        if (accepted != 1) allApplied = false;
      }
      context.log().info("Enqueued {} buffered UPSERT ops back into the job queue", enqueued);
    }

    if (mutatedLucene && context.ingestLifecycle() != null) {
      try {
        // Tempdoc 912 item 2: this used to call the low-level CommitOps.commit(), which skips the
        // per-reason counter, the pendingDocs reset, the commit telemetry and the
        // CommitCompletedListener that keeps EmbeddingCompatibilityController's fingerprint in
        // sync. The low-level primitive is package-private now, so this bypass is a compile error
        // rather than an allowlist entry.
        context
            .ingestLifecycle()
            .commitOps()
            .commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
      } catch (Exception e) {
        allApplied = false;
        context.log().warn("Failed to commit buffered DELETE ops (will retry later): {}", e.getMessage());
      }
    }

    if (allApplied) {
      int cleared = sbq.clearSwitchBuffer();
      context.log().info("Cleared {} buffered ops from durable switch buffer", cleared);
    } else {
      context.log().warn("Not clearing switch buffer because one or more buffered ops failed to replay");
    }
  }

  /** Loads watched roots from persisted file + config collections. */
  public static List<Path> loadWatchedRootsBestEffort(
      Path dataDir, List<ResolvedConfig.CollectionCfg> collections, ObjectMapper json, Logger log) {
    Set<Path> roots = new LinkedHashSet<>();
    Path rootsFile = dataDir.resolve("watched_roots.json");
    try {
      if (Files.exists(rootsFile)) {
        String content = Files.readString(rootsFile);
        if (content.trim().startsWith("{")) {
          var node = json.readTree(content);
          var rootsArray = node.get("roots");
          if (rootsArray != null && rootsArray.isArray()) {
            for (var entry : rootsArray) {
              String p = entry.has("path") ? entry.get("path").asText() : null;
              if (p != null && !p.isBlank()) {
                roots.add(Path.of(p).toAbsolutePath().normalize());
              }
            }
          }
        } else {
          List<String> paths = json.readValue(content, new TypeReference<List<String>>() {});
          for (String p : paths) {
            if (p != null && !p.isBlank()) {
              roots.add(Path.of(p).toAbsolutePath().normalize());
            }
          }
        }
      }
    } catch (Exception e) {
      log.warn("Failed to read watched_roots.json (falling back to config roots): {}", e.getMessage());
    }
    try {
      if (collections != null) {
        for (ResolvedConfig.CollectionCfg c : collections) {
          for (Path r : c.roots()) {
            if (r != null) {
              roots.add(r.toAbsolutePath().normalize());
            }
          }
        }
      }
    } catch (Exception e) {
      log.warn("Failed to enumerate config roots", e);
    }
    return roots.stream().filter(Files::isDirectory).toList();
  }

  public static int enqueueAllFilesUnderRoots(EnqueueContext context) throws IOException {
    if (context.jobQueue() == null || context.roots() == null || context.roots().isEmpty()) {
      return 0;
    }
    int total = 0;
    int batchSize = 2_000;
    ArrayList<JobQueue.EnqueueEntry> batch = new ArrayList<>(batchSize);
    long lastPersistMs = 0L;

    for (Path root : context.roots()) {
      if (Thread.currentThread().isInterrupted()) {
        break;
      }
      while (context.runningSupplier().getAsBoolean() && !Thread.currentThread().isInterrupted()) {
        IndexGenerationManager manager = context.indexGenerationManagerSupplier().get();
        IndexGenerationManager.State state = manager == null ? null : manager.readStateBestEffort();
        if (state == null || !Boolean.TRUE.equals(state.migration_paused())) {
          break;
        }
        try {
          Thread.sleep(1_000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      if (root == null || !Files.isDirectory(root)) {
        continue;
      }
      context.log().info("Migration enumerator scanning root: {}", root);
      try (Stream<Path> walk = Files.walk(root)) {
        var iterator = walk.filter(Files::isRegularFile).filter(Files::isReadable).iterator();
        while (iterator.hasNext()) {
          Path path = iterator.next();
          if (Thread.currentThread().isInterrupted()) {
            break;
          }
          context.migrationEnumeratorFilesSeen().incrementAndGet();
          try {
            context.migrationEnumeratorLastPath().set(path.toAbsolutePath().toString());
          } catch (Exception ignored) {
            // best-effort
          }

          MigrationProgressStore store = context.migrationProgressStoreSupplier().get();
          if (store != null) {
            long now = System.currentTimeMillis();
            if (now - lastPersistMs >= 1_000L) {
              lastPersistMs = now;
              persistMigrationProgressSnapshot(context, store);

              while (context.runningSupplier().getAsBoolean() && !Thread.currentThread().isInterrupted()) {
                IndexGenerationManager manager = context.indexGenerationManagerSupplier().get();
                IndexGenerationManager.State state =
                    manager == null ? null : manager.readStateBestEffort();
                if (state == null || !Boolean.TRUE.equals(state.migration_paused())) {
                  break;
                }
                try {
                  Thread.sleep(1_000);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            }
          }

          // 813 Slice B: this walk is a Stream, not a visitor, so no BasicFileAttributes are in
          // hand — stat for the size (unknown on failure).
          batch.add(JobQueue.EnqueueEntry.stat(path));
          if (batch.size() >= batchSize) {
            int enqueued = context.jobQueue().enqueueEntries(batch);
            total += enqueued;
            context.migrationEnumeratorFilesEnqueued().addAndGet(enqueued);
            batch.clear();
          }
        }
      } catch (Exception e) {
        context.log().warn("Migration enumerator failed walking {}: {}", root, e.getMessage());
      }
      if (!batch.isEmpty()) {
        int enqueued = context.jobQueue().enqueueEntries(batch);
        total += enqueued;
        context.migrationEnumeratorFilesEnqueued().addAndGet(enqueued);
        batch.clear();
      }
      context.migrationEnumeratorRootsDone().incrementAndGet();

      MigrationProgressStore store = context.migrationProgressStoreSupplier().get();
      if (store != null) {
        long now = System.currentTimeMillis();
        if (now - lastPersistMs >= 250L) {
          lastPersistMs = now;
          persistMigrationProgressSnapshot(context, store);
        }
      }
    }

    MigrationProgressStore store = context.migrationProgressStoreSupplier().get();
    if (store != null) {
      persistMigrationProgressSnapshot(context, store);
    }
    return total;
  }

  public static MigrationProgressSnapshot migrationProgressSnapshot(
      AtomicBoolean migrationEnumeratorRunning,
      boolean migrationEnumeratorDone,
      AtomicLong migrationEnumeratorRootsTotal,
      AtomicLong migrationEnumeratorRootsDone,
      AtomicLong migrationEnumeratorFilesSeen,
      AtomicLong migrationEnumeratorFilesEnqueued,
      AtomicLong migrationEnumeratorStartedAtMs,
      AtomicLong migrationEnumeratorFinishedAtMs,
      AtomicReference<String> migrationEnumeratorLastPath,
      MigrationProgressSnapshot persistedMigrationProgressSnapshot) {
    MigrationProgressSnapshot live =
        new MigrationProgressSnapshot(
            migrationEnumeratorRunning.get(),
            migrationEnumeratorDone,
            migrationEnumeratorRootsTotal.get(),
            migrationEnumeratorRootsDone.get(),
            migrationEnumeratorFilesSeen.get(),
            migrationEnumeratorFilesEnqueued.get(),
            migrationEnumeratorStartedAtMs.get(),
            migrationEnumeratorFinishedAtMs.get(),
            migrationEnumeratorLastPath.get());
    if (persistedMigrationProgressSnapshot == null) {
      return live;
    }
    return new MigrationProgressSnapshot(
        live.enumeratorRunning(),
        live.enumeratorDone(),
        Math.max(live.rootsTotal(), persistedMigrationProgressSnapshot.rootsTotal()),
        Math.max(live.rootsDone(), persistedMigrationProgressSnapshot.rootsDone()),
        Math.max(live.filesSeen(), persistedMigrationProgressSnapshot.filesSeen()),
        Math.max(live.filesEnqueued(), persistedMigrationProgressSnapshot.filesEnqueued()),
        live.startedAtMs() > 0
            ? Math.min(live.startedAtMs(), persistedMigrationProgressSnapshot.startedAtMs())
            : persistedMigrationProgressSnapshot.startedAtMs(),
        Math.max(live.finishedAtMs(), persistedMigrationProgressSnapshot.finishedAtMs()),
        !live.lastPath().isBlank() ? live.lastPath() : persistedMigrationProgressSnapshot.lastPath());
  }

  private static void persistMigrationProgressSnapshot(
      EnqueueContext context, MigrationProgressStore store) {
    MigrationProgressSnapshot snap = context.migrationProgressSnapshotSupplier().get();
    context.persistedSnapshotSetter().accept(snap);
    store.writeBestEffort(snap);
  }
}
