/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import io.justsearch.adapters.lucene.runtime.PathPrefixScope;

import tools.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;
import io.justsearch.configuration.persistence.WatchedRootsFormat;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.index.MigrationProgressStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.Release030RecoveryState;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
import io.justsearch.indexerworker.queue.WatcherRootReconciliation;
import io.justsearch.indexerworker.services.WatcherReplay;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.indexerworker.services.ProjectionDocumentMapper;
import io.justsearch.indexerworker.server.RecordedIngestionLifecycle;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
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

  @FunctionalInterface
  public interface CheckedSwitchingTransition {
    void run(IndexGenerationManager.State observed) throws IOException, InterruptedException;
  }

  @FunctionalInterface
  public interface CheckedLiveCutover {
    IndexGenerationManager.State promote() throws IOException, InterruptedException;

    default boolean recorded() { return false; }

    default RecordedIngestionLifecycle.GapDecision gapDecision() {
      return RecordedIngestionLifecycle.GapDecision.NONE;
    }

    default boolean enterGapWait() throws IOException, InterruptedException { return false; }

    default boolean resumeAcceptedGapBuild() throws IOException, InterruptedException { return false; }

    default void transition(String point) throws IOException, InterruptedException {}

    /**
     * Writes FAILED, before any queue or enumeration wait, for a candidate that can never
     * certify. Returns true when it did; its obligations stay for the refusal cleanup.
     */
    default boolean refuseUncertifiable() { return false; }
  }

  public record CutoverContext(
      IndexGenerationManager indexGenerationManager,
      JobQueue jobQueue,
      BooleanSupplier runningSupplier,
      BooleanSupplier migrationEnumeratorDoneSupplier,
      Supplier<Throwable> migrationEnumeratorFailureSupplier,
      long migrationSwitchingQueueDepthThreshold,
      long migrationSwitchingMaxDurationMs,
      int migrationCutoverMaxFailedJobs,
      Supplier<LuceneRuntime> ingestLifecycleSupplier,
      // Tempdoc 598 review Fix E: deterministically finalize the embedding rebuild (flip the ECC to
      // COMPATIBLE iff the green is fully embedded) BEFORE the COMPLETE commit, so that commit stamps
      // the embedding fingerprint deterministically rather than racing the indexing-loop thread.
      BooleanSupplier finalizeEmbeddingRebuildAction,
      Runnable drainSwitchBufferAction,
      Logger log,
      CheckedSwitchingTransition switchingTransition,
      CheckedLiveCutover liveCutover) {
    public CutoverContext {
      Objects.requireNonNull(liveCutover, "liveCutover");
    }
  }

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
      BooleanSupplier vduReplayAllowed,
      Logger log,
      long deadlineNanos,
      String replayGeneration,
      java.util.function.Predicate<String> projectionSourceReady,
      Set<SwitchBufferCapableQueue.SwitchBufferOp> approvedGapVersions,
      // The live Worker whose indexing loop may write while this replay runs, or null when none
      // exists yet (the synchronous boot replay runs before any application service is built).
      // Replayed prunes take its root authority and its file-mutation fence.
      WorkerIngestService publisher,
      List<ResolvedConfig.FileSource> migrationRoots,
      io.justsearch.indexerworker.services.NativePolicyReplay nativePolicy,
      AtomicBoolean excludedRetirementLogged) {
    public DrainSwitchBufferContext {
      approvedGapVersions = Set.copyOf(approvedGapVersions);
      migrationRoots = migrationRoots == null ? null : List.copyOf(migrationRoots);
    }
    public DrainSwitchBufferContext(JobQueue jobQueue, RunningRuntime ingestLifecycle,
        WorkerSignalBus signalBus, IndexingPacing indexingPacing, Path indexBasePath,
        Path activeIndexPath, ObjectMapper json, BooleanSupplier chunkSpladeEnabledSupplier,
        BooleanSupplier vduReplayAllowed, Logger log, long deadlineNanos,
        String replayGeneration, java.util.function.Predicate<String> projectionSourceReady,
        Set<SwitchBufferCapableQueue.SwitchBufferOp> approvedGapVersions, WorkerIngestService publisher,
        List<ResolvedConfig.FileSource> migrationRoots,
        io.justsearch.indexerworker.services.NativePolicyReplay nativePolicy) {
      this(jobQueue, ingestLifecycle, signalBus, indexingPacing, indexBasePath, activeIndexPath,
          json, chunkSpladeEnabledSupplier, vduReplayAllowed, log, deadlineNanos, replayGeneration,
          projectionSourceReady, approvedGapVersions, publisher, migrationRoots, nativePolicy,
          new AtomicBoolean());
    }
    public DrainSwitchBufferContext(JobQueue jobQueue, RunningRuntime ingestLifecycle,
        WorkerSignalBus signalBus, IndexingPacing indexingPacing, Path indexBasePath,
        Path activeIndexPath, ObjectMapper json, BooleanSupplier chunkSpladeEnabledSupplier,
        BooleanSupplier vduReplayAllowed, Logger log, long deadlineNanos,
        String replayGeneration, java.util.function.Predicate<String> projectionSourceReady,
        Set<SwitchBufferCapableQueue.SwitchBufferOp> approvedGapVersions, WorkerIngestService publisher,
        List<ResolvedConfig.FileSource> migrationRoots) {
      this(jobQueue, ingestLifecycle, signalBus, indexingPacing, indexBasePath, activeIndexPath,
          json, chunkSpladeEnabledSupplier, vduReplayAllowed, log, deadlineNanos, replayGeneration,
          projectionSourceReady, approvedGapVersions, publisher, migrationRoots, null);
    }
    public DrainSwitchBufferContext withNativePolicy(
        io.justsearch.indexerworker.services.NativePolicyReplay policy) {
      if (policy != null) policy.useDeadline(deadlineNanos);
      return new DrainSwitchBufferContext(jobQueue, ingestLifecycle, signalBus, indexingPacing,
          indexBasePath, activeIndexPath, json, chunkSpladeEnabledSupplier, vduReplayAllowed, log,
          deadlineNanos, replayGeneration, projectionSourceReady, approvedGapVersions, publisher,
          migrationRoots, policy, excludedRetirementLogged);
    }
    public DrainSwitchBufferContext(JobQueue jobQueue, RunningRuntime ingestLifecycle,
        WorkerSignalBus signalBus, IndexingPacing indexingPacing, Path indexBasePath,
        Path activeIndexPath, ObjectMapper json, BooleanSupplier chunkSpladeEnabledSupplier,
        BooleanSupplier vduReplayAllowed, Logger log, long deadlineNanos,
        String replayGeneration, java.util.function.Predicate<String> projectionSourceReady,
        Set<SwitchBufferCapableQueue.SwitchBufferOp> approvedGapVersions, WorkerIngestService publisher) {
      this(jobQueue, ingestLifecycle, signalBus, indexingPacing, indexBasePath, activeIndexPath,
          json, chunkSpladeEnabledSupplier, vduReplayAllowed, log, deadlineNanos, replayGeneration,
          projectionSourceReady, approvedGapVersions, publisher, null);
    }
    public DrainSwitchBufferContext withMigrationRoots(List<ResolvedConfig.FileSource> roots) {
      return new DrainSwitchBufferContext(jobQueue, ingestLifecycle, signalBus, indexingPacing,
          indexBasePath, activeIndexPath, json, chunkSpladeEnabledSupplier, vduReplayAllowed, log,
          deadlineNanos, replayGeneration, projectionSourceReady, approvedGapVersions, publisher,
          List.copyOf(roots), nativePolicy, excludedRetirementLogged);
    }

    public DrainSwitchBufferContext(JobQueue jobQueue, RunningRuntime ingestLifecycle,
        WorkerSignalBus signalBus, IndexingPacing indexingPacing, Path indexBasePath,
        Path activeIndexPath, ObjectMapper json, BooleanSupplier chunkSpladeEnabledSupplier,
        BooleanSupplier vduReplayAllowed, Logger log, long deadlineNanos,
        String replayGeneration, java.util.function.Predicate<String> projectionSourceReady,
        Set<SwitchBufferCapableQueue.SwitchBufferOp> approvedGapVersions) {
      this(jobQueue, ingestLifecycle, signalBus, indexingPacing, indexBasePath,
          activeIndexPath, json, chunkSpladeEnabledSupplier, vduReplayAllowed, log,
          deadlineNanos, replayGeneration, projectionSourceReady, approvedGapVersions, null);
    }
    /** This context, with {@code publisher} as the live Worker whose writers the replay fences. */
    public DrainSwitchBufferContext withPublisher(WorkerIngestService publisher) {
      return new DrainSwitchBufferContext(jobQueue, ingestLifecycle, signalBus, indexingPacing,
          indexBasePath, activeIndexPath, json, chunkSpladeEnabledSupplier, vduReplayAllowed, log,
          deadlineNanos, replayGeneration, projectionSourceReady, approvedGapVersions, publisher, migrationRoots, nativePolicy, excludedRetirementLogged);
    }
    public DrainSwitchBufferContext(JobQueue jobQueue, RunningRuntime ingestLifecycle,
        WorkerSignalBus signalBus, IndexingPacing indexingPacing, Path indexBasePath,
        Path activeIndexPath, ObjectMapper json, BooleanSupplier chunkSpladeEnabledSupplier,
        BooleanSupplier vduReplayAllowed, Logger log, long deadlineNanos,
        String replayGeneration, java.util.function.Predicate<String> projectionSourceReady) {
      this(jobQueue, ingestLifecycle, signalBus, indexingPacing, indexBasePath,
          activeIndexPath, json, chunkSpladeEnabledSupplier, vduReplayAllowed, log,
          deadlineNanos, replayGeneration, projectionSourceReady, Set.of());
    }
    public DrainSwitchBufferContext(JobQueue jobQueue, RunningRuntime ingestLifecycle,
        WorkerSignalBus signalBus, IndexingPacing indexingPacing, Path indexBasePath,
        Path activeIndexPath, ObjectMapper json, BooleanSupplier chunkSpladeEnabledSupplier,
        BooleanSupplier vduReplayAllowed, Logger log, long deadlineNanos,
        String replayGeneration) {
      this(jobQueue, ingestLifecycle, signalBus, indexingPacing, indexBasePath,
          activeIndexPath, json, chunkSpladeEnabledSupplier, vduReplayAllowed, log,
          deadlineNanos, replayGeneration, ignored -> false);
    }
    public DrainSwitchBufferContext(JobQueue jobQueue, RunningRuntime ingestLifecycle,
        WorkerSignalBus signalBus, IndexingPacing indexingPacing, Path indexBasePath,
        Path activeIndexPath, ObjectMapper json, BooleanSupplier chunkSpladeEnabledSupplier,
        BooleanSupplier vduReplayAllowed, Logger log, long deadlineNanos) {
      this(jobQueue, ingestLifecycle, signalBus, indexingPacing, indexBasePath,
          activeIndexPath, json, chunkSpladeEnabledSupplier, vduReplayAllowed, log,
          deadlineNanos, null);
    }
    public DrainSwitchBufferContext(JobQueue jobQueue, RunningRuntime ingestLifecycle,
        WorkerSignalBus signalBus, IndexingPacing indexingPacing, Path indexBasePath,
        Path activeIndexPath, ObjectMapper json, BooleanSupplier chunkSpladeEnabledSupplier,
        BooleanSupplier vduReplayAllowed, Logger log) {
      this(jobQueue, ingestLifecycle, signalBus, indexingPacing, indexBasePath,
          activeIndexPath, json, chunkSpladeEnabledSupplier, vduReplayAllowed, log,
          Long.MAX_VALUE);
    }
  }

  public record EnqueueContext(
      List<ResolvedConfig.FileSource> roots,
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
        Throwable enumerationFailure = context.migrationEnumeratorFailureSupplier().get();
        if (enumerationFailure != null) {
          context.log().warn("Migration enumeration incomplete; keeping Blue active", enumerationFailure);
          context.indexGenerationManager().updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
          context.drainSwitchBufferAction().run();
          return;
        }
        IndexGenerationManager.State state = context.indexGenerationManager().readStateBestEffort();
        IndexGenerationManager.MigrationState ms =
            parseMigrationState(state == null ? null : state.migration_state());
        if (ms == IndexGenerationManager.MigrationState.IDLE
            || ms == IndexGenerationManager.MigrationState.FAILED) {
          return;
        }

        if (state != null && Boolean.TRUE.equals(state.migration_paused())) {
          Thread.sleep(1_000);
          continue;
        }

        if (ms == IndexGenerationManager.MigrationState.AWAITING_ACCEPTANCE) {
          if (!context.liveCutover().enterGapWait()) {
            Thread.sleep(1_000);
            continue;
          }
          if (context.liveCutover().gapDecision()
                  == RecordedIngestionLifecycle.GapDecision.ACCEPTED) {
            context.liveCutover().resumeAcceptedGapBuild();
          } else {
            Thread.sleep(1_000);
          }
          continue;
        }

        // A candidate that can never certify gains nothing from waiting for its enumeration or
        // queue: that wait grows with the corpus. Refuse it now; pending rows stay durable work.
        if ((ms == IndexGenerationManager.MigrationState.MIGRATING
                || ms == IndexGenerationManager.MigrationState.SWITCHING)
            && context.liveCutover().refuseUncertifiable()) {
          return;
        }

        if (!context.migrationEnumeratorDoneSupplier().getAsBoolean()) {
          Thread.sleep(1_000);
          continue;
        }

        JobQueue.JobStateCounts independent = !context.liveCutover().recorded()
            && context.jobQueue() instanceof SqliteJobQueue sqlite ? sqlite.nativeCutoverCountsStrict() : null;
        JobQueue.JobStateCounts effective = independent == null
            ? context.jobQueue().jobStateCountsStrict() : independent;
        long depth = effective.pendingCount() + effective.processingCount();
        if (ms == IndexGenerationManager.MigrationState.MIGRATING) {
          if (depth > context.migrationSwitchingQueueDepthThreshold()) {
            Thread.sleep(1_000);
            continue;
          }
          context.log().info(
              "Migration nearing completion (queueDepth={}). Entering SWITCHING cutover fence...",
              depth);
          context.liveCutover().transition("migration-before-switching");
          context.switchingTransition().run(state);
          Thread.sleep(250);
          continue;
        }

        if (ms != IndexGenerationManager.MigrationState.SWITCHING) {
          return;
        }

        long now = System.currentTimeMillis();
        long switchingAgeMs = state == null ? 0L : Math.max(0L, now - state.updated_at_ms());
        if (switchingAgeMs > context.migrationSwitchingMaxDurationMs()
            && !hasWatcherReconciliationDebt(context.jobQueue())) {
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
          Thread.sleep(500);
          continue;
        }

        context.log().info(
            "Migration drain criteria met in SWITCHING (queueDepth={}). Finalizing cutover...",
            depth);

        if (!context.liveCutover().recorded()
            && !hasWatcherReconciliationDebt(context.jobQueue())) {
          long failedJobs;
          try {
            // U12: terminal pre-boundary rows are retained, but cannot veto this cutover.
            // Newly admitted migration work has a boundary and still counts against the limit.
            failedJobs = Math.max(0L, (independent == null ? context.jobQueue().failureSummary().failedCount()
                : independent.failedCount()) - context.jobQueue().unownedFailureCount());
          } catch (Exception e) {
            context.log().warn(
                "Migration cutover blocked: failed jobs count is unreadable (keeping Blue active): {}",
                e.getMessage());
            context
                .indexGenerationManager()
                .updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
            context.drainSwitchBufferAction().run();
            return;
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

        IndexGenerationManager.State promoted = context.liveCutover().promote();
        if (promoted == null) {
          if (context.liveCutover().gapDecision()
              == RecordedIngestionLifecycle.GapDecision.AWAITING_ACCEPTANCE) {
            context.liveCutover().enterGapWait();
          }
          Thread.sleep(250);
          continue;
        }
        context.log().info("Migration promoted generation {} without restarting the Engine",
            promoted.active_generation());
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

  /** Recoverable root debt remains fenced and retryable beyond an individual replay budget. */
  public static boolean hasWatcherReconciliationDebt(JobQueue queue) {
    return queue instanceof SwitchBufferCapableQueue journal && journal.listSwitchBufferOpsStrict()
        .stream().anyMatch(op -> WatcherRootReconciliation.OPERATION.equals(op.op()));
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
    return verifyGreenCommitMetadataBestEffort(ingestLifecycle, null, expectedEmbeddingFp, log);
  }

  /** A recorded Green verifies against its frozen candidate target, not A's global providers. */
  public static boolean verifyGreenCommitMetadataBestEffort(
      LuceneRuntime ingestLifecycle, String expectedIndexFp, String expectedEmbeddingFp, Logger log) {
    return verifyGreenCommitMetadataBestEffort(ingestLifecycle, expectedIndexFp, expectedEmbeddingFp, false, log);
  }

  /** Only a freshly fenced installer-empty proof may omit a missing embedding stamp. */
  public static boolean verifyGreenCommitMetadataBestEffort(
      LuceneRuntime ingestLifecycle, String expectedIndexFp, String expectedEmbeddingFp,
      boolean installerEmpty, Logger log) {
    try {
      if (ingestLifecycle == null) {
        return false;
      }
      if (!ingestLifecycle.commitMetadataEnabled()) {
        if (installerEmpty) return false; // Empty admission still requires COMPLETE and its exact target.
        log.warn("Green verification skipped because commit metadata is disabled");
        return true;
      }
      return verifyGreenMetadata(
          ingestLifecycle.latestCommitUserDataBestEffort(), expectedIndexFp, expectedEmbeddingFp, installerEmpty, log);
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
  static boolean verifyGreenMetadata(Map<String, String> ud, String expectedIndexFp,
      String expectedEmbeddingFp, boolean installerEmpty, Logger log) {
    String buildState = ud.get("build_state");
    if (!"COMPLETE".equalsIgnoreCase(buildState)) {
      log.warn("Green verification failed: build_state={} (expected COMPLETE)", buildState);
      return false;
    }
    String committedFingerprint = ud.get(IndexFingerprint.COMMIT_META_KEY);
    Object expectedRaw = expectedIndexFp == null
        ? new SsotCommitMetadataSource().build().get(IndexFingerprint.COMMIT_META_KEY)
        : expectedIndexFp;
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
        if (installerEmpty) return true; // Structural proof grants no permission to write a stamp.
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

  public static void drainSwitchBufferBestEffort(DrainSwitchBufferContext context) {
    // A candidate may need this revision after a crash or an aborted promotion. In particular,
    // a no-file DELETE is the only retained ordering witness for a delayed older UPSERT.
    drainSwitchBuffer(context, false, context.replayGeneration() == null);
  }

  /** Record the registered source before streaming its complete set into the existing journal. */
  public static void seedProjectionSource(SwitchBufferCapableQueue queue, String generation,
      io.justsearch.app.api.indexing.ProjectionSeedSource source) throws IOException {
    String sourceId = Objects.requireNonNull(source.sourceId(), "sourceId");
    if (sourceId.isBlank() || sourceId.length() > 256 || generation == null
        || generation.isBlank()) {
      throw new IOException("Projection seed source or candidate identity is invalid");
    }
    String marker = "projection-source:" + sourceId.length() + ":" + sourceId;
    if (!queue.putSwitchBufferForGeneration(generation, marker, "PROJECTION_SOURCE", sourceId)) {
      throw new IOException("Projection source marker was not durable: " + sourceId);
    }
    try {
      source.enumerate(projection -> {
        try {
          if (!sourceId.equals(projection.sourceId())) {
            throw new IllegalArgumentException("Projection seed crossed source ownership");
          }
          var admission = queue.admitProjectionForGeneration(generation, projection.encode());
          if (admission == SwitchBufferCapableQueue.ProjectionAdmission.CONFLICT) {
            throw new IllegalStateException("Projection seed conflicts with an accepted revision");
          }
        } catch (RuntimeException admissionFailure) {
          throw new ProjectionAdmissionFailure(admissionFailure);
        }
      });
    } catch (ProjectionAdmissionFailure fatal) {
      throw new IOException("Projection seed admission failed: " + sourceId, fatal.getCause());
    } catch (IOException | RuntimeException incomplete) {
      throw new ProjectionSeedIncompleteException(sourceId, incomplete);
    }
  }

  private static final class ProjectionAdmissionFailure extends RuntimeException {
    private ProjectionAdmissionFailure(Throwable cause) { super(cause); }
  }

  /** Marker was durable, but the source could not certify its complete enumeration. */
  public static final class ProjectionSeedIncompleteException extends IOException {
    public ProjectionSeedIncompleteException(String sourceId, Throwable cause) {
      super("Projection source enumeration incomplete: " + sourceId, cause);
    }
  }

  /** The final fence must observe an exact, wholly applied replay before it may promote. */
  public static boolean drainSwitchBufferStrict(DrainSwitchBufferContext context) {
    return drainSwitchBuffer(context, true, true).applied();
  }

  /**
   * A refused pre-pointer candidate must settle only its own accepted mutations on surviving A.
   * Source completeness belongs to B's seed witness; an unreadable source cannot prevent
   * abandonment once exact journal versions are committed and verified on A.
   */
  public static boolean drainRefusedCandidateOnSource(DrainSwitchBufferContext context) {
    return drainRefusedCandidateOnSource(context, () -> {});
  }

  /** As above, with a harness cut after A's commit and verification, before the journal ack. */
  public static boolean drainRefusedCandidateOnSource(DrainSwitchBufferContext context,
      Runnable sourceCommittedBeforeAck) {
    if (context.replayGeneration() == null || context.replayGeneration().isBlank()) return false;
    return drainSwitchBuffer(context, true, true, false,
        Objects.requireNonNull(sourceCommittedBeforeAck, "sourceCommittedBeforeAck"), true).applied();
  }

  private static boolean release030Retirement(DrainSwitchBufferContext context) {
    return context.jobQueue() instanceof SwitchBufferCapableQueue queue
        && context.replayGeneration() != null
        && context.replayGeneration().equals(queue.release030RetirementGeneration());
  }

  public record Release030Roots(Map<Path, String> currentRoots,
      java.util.function.BiFunction<SwitchBufferCapableQueue.SwitchBufferOp,
          Supplier<SyncDirectoryResponse>, SyncDirectoryResponse> replay,
      BooleanSupplier cancelled, Release030RecoveryState checkpoint) {}

  /** Same ordered source replay as refusal, with no uncertainty waiver and with legacy receipts. */
  public static boolean drainRelease030OnSource(DrainSwitchBufferContext context, boolean acknowledge,
      Runnable sourceBeforeCommit, Runnable committedBeforeAck, Release030Roots recoveryRoots) {
    if (!release030Retirement(context)) return false;
    return drainSwitchBuffer(context, true, acknowledge, true, committedBeforeAck, true,
        () -> {}, sourceBeforeCommit, recoveryRoots).applied() && acknowledge;
  }

  public static boolean reconcileRelease030Path(DrainSwitchBufferContext context, Path file,
      Path boundary, String collection) throws IOException {
    return reconcileRelease030Path(context,file,boundary,collection,
        context.jobQueue() instanceof SqliteJobQueue sqlite ? sqlite.retirementReplayOps() : List.of());
  }

  public static boolean reconcileRelease030Path(DrainSwitchBufferContext context, Path file,
      Path boundary, String collection, List<SwitchBufferCapableQueue.SwitchBufferOp> journal) throws IOException {
    if (!(context.jobQueue() instanceof SwitchBufferCapableQueue queue)) return false;
    return reconcileRelease030Path(context,file,collection,releaseRootIntents(journal),queue.retirementRootFiles());
  }

  private static boolean reconcileRelease030Path(DrainSwitchBufferContext context, Path file, String collection,
      Map<SwitchBufferCapableQueue.SwitchBufferOp,io.justsearch.indexerworker.queue.SwitchBufferSyncRoot> roots,
      java.util.function.Function<String,SwitchBufferCapableQueue.RetirementRootFile> rootFiles) throws IOException {
    Boolean policyOutcome = retirementPolicyOutcome(context, file, file);
    if (policyOutcome != null) return policyOutcome;
    var queue=(SwitchBufferCapableQueue)context.jobQueue();
    String key=io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file);
    var owner = applicableReleaseRoot(context,roots,key,0,rootFiles);
    var upsert = owner == null ? new SwitchBufferUpsert(key,releaseCollection(collection),
        null,null,null,file) : releaseRootScope(queue,owner,key,rootFiles);
    if (owner == null) {
      var row = queue.watcherReconciliationRow(key);
      if (row != null && key.equals(row.get("ingestion_root"))
          && releaseCollection(collection).equals(releaseCollection(row.get("collection")))) {
        var provenance = row.get("originator") == null && row.get("transport") == null ? null
            : new JobQueue.EnqueueProvenance(row.get("originator"),row.get("transport"));
        upsert = new SwitchBufferUpsert(key,row.get("collection"),provenance,null,null,file);
      }
    }
    return sourceHoldsAdmission(context, queue, owner, upsert,
        context.ingestLifecycle().documentFieldOps().getDocumentFieldOrThrow(
            key, SchemaFields.SOURCE_SHA256));
  }

  /** A conditional DELETE owns its current-path outcome, without selecting a historical creator. */
  private static boolean settleRelease030ConditionalDelete(DrainSwitchBufferContext context,
      SwitchBufferCapableQueue.SwitchBufferOp receipt,SwitchBufferDelete deletion,
      Path file,Path boundary,String collection,ReplayDeleteOrder order) throws IOException {
    Boolean policyOutcome = retirementPolicyOutcome(context, file, boundary);
    if (policyOutcome != null) return policyOutcome;
    var queue=(SqliteJobQueue)context.jobQueue();
    var observedRow=queue.watcherReconciliationRow(deletion.path());
    var source=observeForCleanup(context.publisher(),file,boundary);
    if(source.kind()==CleanupPathKind.UNCERTAIN)return false;
    var fields=context.ingestLifecycle().documentFieldOps();
    String indexed=fields.getDocumentFieldOrThrow(deletion.path(),SchemaFields.SOURCE_SHA256);
    String namespace=fields.getDocumentFieldOrThrow(deletion.path(),SchemaFields.COLLECTION);
    boolean matches=source.kind()==CleanupPathKind.REGULAR
        ? source.sha256()!=null && source.sha256().equals(indexed)
            && deletion.path().equals(fields.getDocumentFieldOrThrow(deletion.path(),SchemaFields.DOC_ID))
            && deletion.path().equals(fields.getDocumentFieldOrThrow(deletion.path(),SchemaFields.PATH))
            && namespace!=null && !namespace.isBlank() && (collection==null || collection.equals(namespace))
        : indexed==null && context.ingestLifecycle().indexCountOps().countByIdAndChunksStrict(deletion.path())==0;
    String knownCollection=source.kind()==CleanupPathKind.REGULAR?namespace:collection;
    if(knownCollection==null && observedRow!=null)knownCollection=observedRow.get("collection");
    if(matches && queue.matchesRetirementConditionalDelete(receipt,observedRow,knownCollection))return true;
    if (source.kind()==CleanupPathKind.REGULAR && knownCollection!=null
        && deletion.path().equals(fields.getDocumentFieldOrThrow(deletion.path(),SchemaFields.DOC_ID))
        && deletion.path().equals(fields.getDocumentFieldOrThrow(deletion.path(),SchemaFields.PATH))) {
      // The native conditional operation owns only reconciliation of its exact current path.
      var scope=new SwitchBufferUpsert(deletion.path(),knownCollection,null,null,null,file);
      if (sourceHoldsAdmission(context,queue,receipt,scope,indexed)) return true;
    }
    var fileOwner = order.positions().keySet().stream()
        .filter(op -> "UPSERT".equals(op.op()) && op.key().equals("path:"+deletion.path())
            && !context.approvedGapVersions().contains(op))
        .max(java.util.Comparator.comparingLong(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder)).orElse(null);
    if (fileOwner != null && queue.retirementFileOwnsCurrentRow(fileOwner)
        && sourceHoldsAdmission(context,queue,fileOwner,SwitchBufferUpsert.decode(fileOwner.payload()),indexed)) return true;
    return reconcileRelease030Path(context,file,Objects.toString(collection,""),order.rootIntents(),order.rootFiles());
  }

  private static SwitchBufferUpsert releaseRootScope(SwitchBufferCapableQueue queue,
      SwitchBufferCapableQueue.SwitchBufferOp owner, String path,
      java.util.function.Function<String,SwitchBufferCapableQueue.RetirementRootFile> rootFiles) throws IOException {
    String collection = queue.retirementRootCollection(owner);
    if (!queue.retirementRootPolicyKnown(owner)) throw new IOException("Current ROOT creator namespace is unknown");
    var root = io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(owner.payload());
    if (collection == null || root.provenance() == null) {
      var member=rootFiles.apply(path);
      boolean administrative=owner.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX);
      if(member==null || !administrative && !owner.equals(member.owner())
          || administrative && member.owner().acceptedOrder()>owner.acceptedOrder())
        throw new IOException("ROOT member metadata is unavailable");
      return member.scope();
    }
    return new SwitchBufferUpsert(path,collection,root.provenance(),null,null,Path.of(root.rootPath()));
  }

  private static Map<SwitchBufferCapableQueue.SwitchBufferOp,io.justsearch.indexerworker.queue.SwitchBufferSyncRoot>
      releaseRootIntents(List<SwitchBufferCapableQueue.SwitchBufferOp> journal) {
    var roots=new LinkedHashMap<SwitchBufferCapableQueue.SwitchBufferOp,io.justsearch.indexerworker.queue.SwitchBufferSyncRoot>();
    for(var op:journal) if("SYNC_ROOT".equals(op.op())
        && !op.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX))
      roots.put(op,io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(op.payload()));
    return java.util.Collections.unmodifiableMap(roots);
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp applicableReleaseRoot(DrainSwitchBufferContext context,
      Map<SwitchBufferCapableQueue.SwitchBufferOp,io.justsearch.indexerworker.queue.SwitchBufferSyncRoot> roots, String path, long after,
      java.util.function.Function<String,SwitchBufferCapableQueue.RetirementRootFile> rootFiles) {
    SwitchBufferCapableQueue.SwitchBufferOp result = null;
    for (var entry : roots.entrySet()) {
      var op=entry.getKey();var root=entry.getValue();
      if(op.acceptedOrder()<=after)continue;
      if (context.publisher().prefixScope(root.rootPath()).contains(path)
          && (result == null || result.acceptedOrder() < op.acceptedOrder())) result = op;
    }
    if (result!=null && !roots.get(result).force()
        && context.jobQueue() instanceof SwitchBufferCapableQueue queue) {
      var member=rootFiles.apply(path);var actual=member==null?null:member.owner();
      if(actual==null || actual.acceptedOrder()<result.acceptedOrder())return actual;
    }
    return result;
  }

  /** Replay remains durable until the generation pointer commits, so abandonment can replay on A. */
  public record StrictReplay(List<SwitchBufferCapableQueue.SwitchBufferOp> versions,
      io.justsearch.indexerworker.services.NativePolicyReplay nativePolicy) {
    public StrictReplay(List<SwitchBufferCapableQueue.SwitchBufferOp> versions) { this(versions, null); }
    public StrictReplay { versions = List.copyOf(versions); }
  }

  private record ReplayOutcome(boolean applied, List<SwitchBufferCapableQueue.SwitchBufferOp> versions) {}

  public static java.util.Optional<StrictReplay> prepareSwitchReplayForPromotion(
      DrainSwitchBufferContext context) {
    return prepareSwitchReplayForPromotion(context, () -> {});
  }

  /** Only the candidate promotion caller supplies a harness cut inside its ordered replay. */
  public static java.util.Optional<StrictReplay> prepareSwitchReplayForPromotion(
      DrainSwitchBufferContext context, Runnable firstCandidateProjectionApplied) {
    return prepareSwitchReplayForPromotion(context, firstCandidateProjectionApplied, () -> {});
  }

  /**
   * As {@link #prepareSwitchReplayForPromotion(DrainSwitchBufferContext, Runnable)}, with a second
   * harness cut at a replayed prune's first removal, before anything of it is committed.
   */
  public static java.util.Optional<StrictReplay> prepareSwitchReplayForPromotion(
      DrainSwitchBufferContext context, Runnable firstCandidateProjectionApplied,
      Runnable firstPruneRemoval) {
    var result = drainSwitchBuffer(context, true, false, true,
        Objects.requireNonNull(firstCandidateProjectionApplied, "firstCandidateProjectionApplied"),
        false, Objects.requireNonNull(firstPruneRemoval, "firstPruneRemoval"));
    return result.applied() ? java.util.Optional.of(new StrictReplay(result.versions(), context.nativePolicy()))
        : java.util.Optional.empty();
  }

  /** Remove only the exact applied versions after B is durably committed. */
  public static boolean finishPromotedSwitchReplay(JobQueue queue, StrictReplay replay) {
    if (!(queue instanceof SwitchBufferCapableQueue sbq)) return false;
    if (replay.versions().isEmpty()) return true;
    try { return sbq.removeReplayedSwitchBufferOps(replay.versions()) == replay.versions().size(); }
    catch (RuntimeException unavailable) { return false; }
  }

  public static boolean promotionReplaySnapshotUnchanged(JobQueue queue, String generation,
      StrictReplay replay) {
    return queue instanceof SwitchBufferCapableQueue journal
        && strictReplayOps(journal, generation).equals(replay.versions());
  }

  /**
   * A strict PROMOTED boot proves the pre-pointer replay and Green commit already completed.
   * Admission stayed fenced until cleanup was certified, so retained versions need only exact
   * deletion; re-enqueueing them would create new, untracked work after the recorded settlement.
   */
  public static boolean finishCommittedBootSwitchReplay(JobQueue queue) {
    return finishCommittedBootSwitchReplay(queue, null);
  }

  public static boolean finishCommittedBootSwitchReplay(JobQueue queue, String generation) {
    if (!(queue instanceof SwitchBufferCapableQueue sbq)) return false;
    try {
      var committed = strictReplayOps(sbq, generation);
      // A pointer certifies the earlier replay, not a root failure retained after that commit.
      if (committed.stream().anyMatch(op -> WatcherRootReconciliation.OPERATION.equals(op.op()))) return false;
      return finishPromotedSwitchReplay(queue, new StrictReplay(committed))
          && switchBufferEmptyStrict(queue, generation);
    } catch (RuntimeException unreadable) {
      return false;
    }
  }

  /**
   * A native pointer-before-publication cut can leave scoped file mutations after B is committed.
   * Settle the accepted UPSERT receipts against their exact queue revision and B source identity;
   * replay exact DELETEs on B in accepted order, then durably commit and verify their absence.
   * Mixed projection and broad-delete snapshots certify B's final accepted effects without
   * replaying destructive mutations. Conditional removal retains concurrent replacement rows and
   * acknowledges the selected receipts together or not at all.
   * A SYNC_ROOT was replayed onto B before its pointer moved: its exact receipt is validated and
   * acknowledged with the others in accepted order, never walked again. On either path, a file
   * receipt a later SYNC_ROOT covers is checked as a receipt only, unless a current owner's
   * exclusion policy excludes its source: that receipt is certified as an uncovered one is.
   */
  public static boolean settleCommittedNativeFileWitnesses(
      JobQueue queue, RunningRuntime active, String generation, List<String> ownedSources, Logger log) {
    return settleCommittedNativeFileWitnesses(queue, active, generation, ownedSources, log, null);
  }

  public static boolean settleCommittedNativeFileWitnesses(
      JobQueue queue, RunningRuntime active, String generation, List<String> ownedSources, Logger log,
      io.justsearch.indexerworker.services.NativePolicyReplay policy) {
    if (!(queue instanceof SwitchBufferCapableQueue scoped) || active == null
        || generation == null || generation.isBlank() || ownedSources == null) return false;
    try {
      scopeLegacyPrefixes(scoped, active, null);
      if (policy != null && !policy.freeze(strictReplayOps(scoped, generation))) return false;
      List<SwitchBufferCapableQueue.SwitchBufferOp> selected = scoped.listSwitchBufferOpsStrict()
          .stream().filter(op -> generation.equals(op.generation())).toList();
      if (selected.stream().allMatch(op -> "UPSERT".equals(op.op()) || "DELETE".equals(op.op())
          || "SYNC_ROOT".equals(op.op()))) {
        return settleCommittedNativeFiles(queue, active, generation, log, selected, policy);
      }
      // Native fallback can reopen a recorded generation whose promotion approved gaps. It does
      // not thereby acquire the native pre-pointer source-completeness authority used below.
      if (IndexGenerationManager.isRecordedGenerationIdentity(generation)) return false;
      Set<String> sources = Set.copyOf(IndexGenerationManager.checkedProjectionSourceIds(ownedSources));
      List<CommittedNativeEffect> effects = new ArrayList<>();
      for (var op : selected) effects.add(decodeCommittedNativeEffect(op, sources));
      active.commitOps().maybeRefreshBlocking();
      for (int i = 0; i < effects.size(); i++) {
        effects.set(i, observeCommittedProjection(active, effects.get(i)));
      }
      // B's commit holds a later root sync's walk over these paths; only their receipts are checked.
      Set<SwitchBufferCapableQueue.SwitchBufferOp> superseded = supersededByRootSync(selected);
      for (var op : selected) {
        if ("DELETE".equals(op.op()) && !superseded.contains(op)) {
          var deletion = SwitchBufferDelete.decode(op.payload());
          if (deletion.origin() != SwitchBufferDelete.Origin.EXPLICIT
              && !settleConditionalNativeDelete(queue, active, deletion)) return false;
        }
      }
      Set<CommittedNativeEffect> survivors = acceptedSurvivors(effects);
      if (policy != null) survivors.removeIf(effect -> effect.file() != null && policy.certify(effect.op()));
      List<Integer> deleteIndexes = new ArrayList<>();
      for (int i = 0; i < effects.size(); i++) {
        var effect = effects.get(i);
        if ("DELETE_PREFIX".equals(effect.op().op()) || "DELETE_COLLECTION".equals(effect.op().op())
            || (effect.id() != null && !effect.upsert())) {
          deleteIndexes.add(i);
        }
      }
      Set<String> survivorIds = new java.util.HashSet<>();
      for (var survivor : survivors) survivorIds.add(survivor.id());
      // Complete every reader/queue proof before conditionally acknowledging any receipt.
      for (int i = 0; i < effects.size(); i++) {
        var effect = effects.get(i);
        // A superseded survivor stays an accepted exception to the broad deletes before it.
        if (superseded.contains(effect.op()) && !policyExcludes(policy, effect.op())) continue;
        if (survivors.contains(effect)) {
          if (!certifyCommittedSurvivor(scoped, active, effects, deleteIndexes, i, policy)) return false;
        } else if (effect.id() != null
            && !survivorIds.contains(effect.id())
            && active.indexCountOps().countByIdAndChunksStrict(effect.id()) != 0) {
          return false;
        }
        if ("DELETE_PREFIX".equals(effect.op().op())
            || "DELETE_COLLECTION".equals(effect.op().op())) {
          if (!certifyCommittedDeleteScope(queue, active, effects, survivors, i, List.of())) return false;
          if (policy != null && !policy.omittedPaths().isEmpty()
              && !certifyReplayPrefixQueue(queue, scoped, active, effects, survivors, i, List.of(), policy)) return false;
        }
      }
      if (policy != null && !policy.confirmCommittedAcknowledgement(selected)) return false;
      if (!acknowledgeEvery(scoped, selected)) return false;
      return scoped.listSwitchBufferOpsStrict().stream()
          .noneMatch(op -> generation.equals(op.generation()));
    } catch (IOException | RuntimeException unavailable) {
      log.warn("Committed native mutation witnesses remain unresolved", unavailable);
      return false;
    }
  }

  /** A transient projection of the existing ordered journal, never a second persisted authority. */
  private record CommittedNativeEffect(SwitchBufferCapableQueue.SwitchBufferOp op,
      SwitchBufferUpsert file, AcceptedProjection projection, String id, String path,
      String collection, boolean newerProjection) {
    boolean upsert() {
      return file != null || (projection != null
          && (newerProjection || projection.kind() == AcceptedProjection.Kind.UPSERT));
    }
  }

  private static CommittedNativeEffect decodeCommittedNativeEffect(
      SwitchBufferCapableQueue.SwitchBufferOp op, Set<String> sources) {
    if (op.key() == null || op.payload() == null || op.payload().isBlank()
        || op.revision() == null || op.revision().isBlank()) {
      throw new IllegalArgumentException("Incomplete committed journal receipt");
    }
    String id = null;
    String path = null;
    String collection = null;
    SwitchBufferUpsert file = null;
    AcceptedProjection projection = null;
    String expectedKey;
    switch (op.op()) {
      case "UPSERT" -> {
        file = SwitchBufferUpsert.decode(op.payload());
        if (file.sourceSha256() == null) throw new IllegalArgumentException("Missing file witness");
        id = file.path();
        path = file.path();
        collection = file.collection() == null || file.collection().isBlank() ? null : file.collection();
        expectedKey = "path:" + id;
      }
      case "DELETE" -> {
        var deletion = SwitchBufferDelete.decode(op.payload());
        // Conditional filesystem receipts cannot authorise another admission's removal.
        id = deletion.origin() == SwitchBufferDelete.Origin.EXPLICIT ? deletion.path() : null;
        expectedKey = "path:" + deletion.path();
      }
      case "DELETE_PREFIX" -> {
        var scope = PathPrefixScope.decode(op.payload());
        if (!op.key().startsWith("prefix:")
            || !new PathPrefixScope(op.key().substring(7), List.of()).prefix().equals(scope.prefix())) {
          throw new IllegalArgumentException("Prefix receipt has an invalid key");
        }
        expectedKey = op.key();
      }
      case "DELETE_COLLECTION" -> {
        if (!IngestCollectionPolicy.isDeletable(op.payload())) {
          throw new IllegalArgumentException("Protected collection cannot certify deletion");
        }
        expectedKey = "collection:" + op.payload();
      }
      case "SYNC_ROOT" -> expectedKey = "sync_root:"
          + io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(op.payload()).rootPath();
      case "PROJECTION_SOURCE" -> {
        if (!sources.contains(op.payload())) throw new IllegalArgumentException("Unowned projection source");
        expectedKey = "projection-source:" + op.payload().length() + ":" + op.payload();
      }
      case "PROJECTION" -> {
        projection = AcceptedProjection.decode(op.payload());
        if (!sources.contains(projection.sourceId())) throw new IllegalArgumentException("Unowned projection");
        id = projection.indexId();
        expectedKey = projection.journalKey();
        if (projection.kind() == AcceptedProjection.Kind.UPSERT) {
          var fields = ProjectionDocumentMapper.toIndexDocument(projection).fields();
          path = projectedTerm(fields.get(SchemaFields.PATH));
          collection = projectedTerm(fields.get(SchemaFields.COLLECTION));
        }
      }
      default -> throw new IllegalArgumentException("Unsupported committed mutation: " + op.op());
    }
    if (!expectedKey.equals(op.key())) throw new IllegalArgumentException("Committed journal key mismatch");
    return new CommittedNativeEffect(op, file, projection, id, path, collection, false);
  }

  private static String projectedTerm(Object field) {
    return field == null ? null : String.valueOf(field);
  }

  private static CommittedNativeEffect observeCommittedProjection(
      RunningRuntime active, CommittedNativeEffect effect) throws IOException {
    if (effect.projection() == null) return effect;
    var fields = active.documentFieldOps();
    String id = fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.DOC_ID);
    if (id == null) return effect;
    String source = fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.PROJECTION_SOURCE_ID);
    String revision = fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.PROJECTION_SOURCE_REVISION);
    if (!effect.id().equals(id) || !effect.projection().sourceId().equals(source) || revision == null) {
      throw new IOException("Committed projection has unowned identity");
    }
    long observedRevision = Long.parseLong(revision);
    if (observedRevision < 0 || !Long.toString(observedRevision).equals(revision)) {
      throw new IOException("Committed projection has noncanonical revision evidence");
    }
    if (observedRevision <= effect.projection().sourceRevision()) return effect;
    if (!JobQueue.IngestionLedgerTransition.isSha256(fields.getDocumentFieldOrThrow(
        effect.id(), SchemaFields.PROJECTION_DIGEST))) {
      throw new IOException("Committed newer projection has incomplete digest evidence");
    }
    // A newer source seed dominates this retained revision. Its actual scope is authoritative:
    // an earlier broad delete cannot use this newer document as a later accepted exception.
    return new CommittedNativeEffect(effect.op(), null, effect.projection(), effect.id(),
        fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.PATH),
        fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.COLLECTION), true);
  }

  private static boolean nativeDeleteMatches(CommittedNativeEffect deletion,
      CommittedNativeEffect document) {
    return switch (deletion.op().op()) {
      case "DELETE" -> deletion.id() != null && deletion.id().equals(document.id());
      case "PROJECTION" -> !deletion.upsert() && deletion.id().equals(document.id());
      case "DELETE_PREFIX" -> PathPrefixScope.decode(deletion.op().payload()).contains(document.path());
      case "DELETE_COLLECTION" -> deletion.op().payload().equals(document.collection());
      default -> false;
    };
  }

  private static Set<CommittedNativeEffect> acceptedSurvivors(List<CommittedNativeEffect> effects) {
    Set<CommittedNativeEffect> survivors = new LinkedHashSet<>();
    Set<String> laterIds = new java.util.HashSet<>();
    List<CommittedNativeEffect> laterBroadDeletes = new ArrayList<>();
    for (int i = effects.size() - 1; i >= 0; i--) {
      var effect = effects.get(i);
      if (effect.upsert() && !laterIds.contains(effect.id())
          && laterBroadDeletes.stream().noneMatch(deletion -> nativeDeleteMatches(deletion, effect))) {
        survivors.add(effect);
      }
      if (effect.id() != null) laterIds.add(effect.id());
      if ("DELETE_PREFIX".equals(effect.op().op()) || "DELETE_COLLECTION".equals(effect.op().op())) {
        laterBroadDeletes.add(effect);
      }
    }
    return survivors;
  }

  private static boolean certifyCommittedSurvivor(SwitchBufferCapableQueue queue,
      RunningRuntime active, List<CommittedNativeEffect> effects, List<Integer> deleteIndexes,
      int index, io.justsearch.indexerworker.services.NativePolicyReplay policy) throws IOException {
    var effect = effects.get(index);
    if (effect.file() != null && policy != null && policy.certify(effect.op())) return true;
    var fields = active.documentFieldOps();
    if (!effect.id().equals(fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.DOC_ID))) return false;
    if (effect.file() != null) {
      if (!Objects.equals(effect.path(), fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.PATH))
          || !Objects.equals(effect.collection(), fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.COLLECTION))) {
        return false;
      }
      return queue.matchesAcceptedFileProjection(effect.id(), effect.file().unitRevision(),
          effect.file().sourceSha256(), fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.SOURCE_SHA256));
    }
    if (effect.newerProjection()) {
      for (int previous : deleteIndexes) {
        if (previous >= index) continue;
        if (nativeDeleteMatches(effects.get(previous), effect)) return false;
      }
      return true; // Strict source/identity/revision already verified by the observation above.
    }
    var projection = effect.projection();
    return Long.toString(projection.sourceRevision()).equals(fields.getDocumentFieldOrThrow(
            effect.id(), SchemaFields.PROJECTION_SOURCE_REVISION))
        && projection.sourceId().equals(fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.PROJECTION_SOURCE_ID))
        && projection.fieldsDigest().equals(fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.PROJECTION_DIGEST))
        && Objects.equals(effect.path(), fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.PATH))
        && Objects.equals(effect.collection(), fields.getDocumentFieldOrThrow(effect.id(), SchemaFields.COLLECTION));
  }

  private static boolean certifyCommittedDeleteScope(JobQueue queue, RunningRuntime active,
      List<CommittedNativeEffect> effects, Set<CommittedNativeEffect> survivors, int index,
      List<String> reconciledFiles) throws IOException {
    var deletion = effects.get(index);
    List<String> files = new ArrayList<>(reconciledFiles);
    List<String> projections = new ArrayList<>();
    for (int next = index + 1; next < effects.size(); next++) {
      var survivor = effects.get(next);
      if (!survivors.contains(survivor) || !nativeDeleteMatches(deletion, survivor)) continue;
      if (survivor.file() != null) files.add(survivor.id());
      else projections.add(survivor.id());
    }
    String scope = deletion.op().payload();
    if ("DELETE_PREFIX".equals(deletion.op().op())) {
      var prefixScope = PathPrefixScope.decode(scope);
      if (prefixScope.excludedSubtrees().isEmpty()) {
        String prefix = PathPrefixScope.isLegacy(scope) ? scope : prefixScope.prefix();
        return !queue.hasNonterminalJobsByPathPrefixStrict(prefix)
            && active.indexCountOps().countPathPrefixExcludingAcceptedSurvivorsStrict(
                prefix, files, projections) == 0;
      }
      return !queue.hasNonterminalJobsByPathPrefixStrict(prefixScope.prefix(), prefixScope.excludedSubtrees())
          && active.indexCountOps().countPathPrefixExcludingAcceptedSurvivorsStrict(
              prefixScope.prefix(), files, projections, prefixScope.excludedSubtrees()) == 0;
    }
    return !queue.hasNonterminalJobsByCollectionStrict(scope)
        && active.indexCountOps().countCollectionExcludingAcceptedSurvivorsStrict(scope, files, projections) == 0;
  }

  private static boolean settleConditionalNativeDelete(JobQueue queue, RunningRuntime active,
      SwitchBufferDelete deletion) {
    Path indexPath = active.openedIndexPath();
    if (indexPath == null || indexPath.getParent() == null
        || indexPath.getParent().getParent() == null) return false;
    var authority = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        indexPath.getParent().getParent(), indexPath, active, null, null, 0L);
    // A path that cannot be confirmed beneath a vouching root keeps its receipt, as an
    // unproved root does.
    if (uncertainBeneathItsRoot(authority, deletion)) return false;
    return authority.replayBufferedFileDelete(active, deletion)
        == WorkerIngestService.BufferedDeleteReplay.SETTLED;
  }

  private static boolean settleCommittedNativeFiles(JobQueue queue, RunningRuntime active,
      String generation, Logger log, List<SwitchBufferCapableQueue.SwitchBufferOp> selected,
      io.justsearch.indexerworker.services.NativePolicyReplay policy) {
    if (!(queue instanceof SwitchBufferCapableQueue scoped) || active == null
        || generation == null || generation.isBlank()) return false;
    try {
      // Refuse unsupported kinds before applying any partial replay on this boot attempt.
      if (selected.stream().anyMatch(op -> !"UPSERT".equals(op.op())
          && !"DELETE".equals(op.op()) && !"SYNC_ROOT".equals(op.op()))) return false;
      if (selected.stream().anyMatch(op -> "UPSERT".equals(op.op()))) {
        active.commitOps().maybeRefreshBlocking();
      }
      // Validate every receipt before touching B. A later unresolved UPSERT must not leave an
      // earlier DELETE half-applied while the boot attempt waits for its exact queue revision.
      Set<SwitchBufferCapableQueue.SwitchBufferOp> superseded = supersededByRootSync(selected);
      for (var op : selected) {
        if (op.payload() == null || op.payload().isBlank()) return false;
        if ("SYNC_ROOT".equals(op.op()) || superseded.contains(op) && !policyExcludes(policy, op)) {
          // B's commit holds the later root sync's walk over this path; only the receipt is checked.
          // A retained SYNC_ROOT is in the snapshot B committed: promoteServingSuccessor holds
          // beginFinalFence from before B's commit until finishPromotedSwitchReplay removes the
          // receipts, refusing when promotionReplaySnapshotUnchanged fails, and once the pointer
          // moves buildingGenerationForFileAdmission has no generation to admit B-scoped rows for.
          decodeCommittedNativeEffect(op, Set.of());
        } else if ("UPSERT".equals(op.op())) {
          var upsert = SwitchBufferUpsert.decode(op.payload());
          String indexed = active.documentFieldOps()
              .getDocumentFieldOrThrow(upsert.path(), SchemaFields.SOURCE_SHA256);
          if (!op.key().equals("path:" + upsert.path())
              || upsert.sourceSha256() == null
              || !(scoped.matchesAcceptedFileProjection(upsert.path(), upsert.unitRevision(),
                  upsert.sourceSha256(), indexed) || policy != null && policy.certify(op))) return false;
        } else if (!op.key().equals("path:" + SwitchBufferDelete.decode(op.payload()).path())) {
          return false;
        }
      }
      boolean deleted = false;
      for (var op : selected) {
        if ("DELETE".equals(op.op()) && !superseded.contains(op)) {
          var deletion = SwitchBufferDelete.decode(op.payload());
          if (deletion.origin() == SwitchBufferDelete.Origin.EXPLICIT) {
            active.indexingCoordinator().deleteByIdAndChunks(deletion.path());
            if (queue.deleteByExactPath(deletion.path()) < 0) return false;
            deleted = true;
          } else {
            if (!settleConditionalNativeDelete(queue, active, deletion)) return false;
          }
        }
      }
      if (deleted) {
        active.commitOps().commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
        active.commitOps().maybeRefreshBlocking();
        for (var op : selected) {
          if ("DELETE".equals(op.op()) && !superseded.contains(op)) {
            var deletion = SwitchBufferDelete.decode(op.payload());
            if (deletion.origin() == SwitchBufferDelete.Origin.EXPLICIT
                && active.indexCountOps().countByIdAndChunksStrict(deletion.path()) != 0) return false;
          }
        }
      }
      if (policy != null && !policy.confirmCommittedAcknowledgement(selected)) return false;
      if (!selected.isEmpty() && !acknowledgeEvery(scoped, selected)) return false;
      return switchBufferEmptyStrict(queue, generation);
    } catch (IOException | RuntimeException unavailable) {
      log.warn("Committed native file witnesses remain unresolved", unavailable);
      return false;
    }
  }

  /**
   * Acknowledges B's selected receipts together or not at all. Supersession reads the later
   * receipt from the retained snapshot, so a partial removal could let a retry replay an earlier
   * receipt alone; the retry instead sees the whole snapshot again.
   */
  private static boolean acknowledgeEvery(SwitchBufferCapableQueue scoped,
      List<SwitchBufferCapableQueue.SwitchBufferOp> selected) {
    return scoped instanceof SqliteJobQueue sqlite ? sqlite.removeEveryReplayedSwitchBufferOp(selected)
        : scoped.removeReplayedSwitchBufferOps(selected) == selected.size();
  }

  /**
   * A later root walk skips an excluded source, so only the policy's omission proof, confirmed by
   * the final owner, settles that source's receipt. The current owner judges, not the binding the
   * receipt records: a root declared since B committed may own and exclude the source.
   */
  private static boolean policyExcludes(io.justsearch.indexerworker.services.NativePolicyReplay policy,
      SwitchBufferCapableQueue.SwitchBufferOp op) {
    if (policy == null || !"UPSERT".equals(op.op())) return false;
    var file = SwitchBufferUpsert.decode(op.payload());
    return policy.excludesUnderCurrentOwner(Path.of(file.path()), file.ingestionRoot(), file.collection());
  }

  /** File receipts whose path a SYNC_ROOT accepted after them walked again before B committed. */
  private static Set<SwitchBufferCapableQueue.SwitchBufferOp> supersededByRootSync(
      List<SwitchBufferCapableQueue.SwitchBufferOp> selected) {
    Set<SwitchBufferCapableQueue.SwitchBufferOp> superseded = new java.util.HashSet<>();
    List<PathPrefixScope> laterRoots = new ArrayList<>();
    for (int i = selected.size() - 1; i >= 0; i--) {
      var op = selected.get(i);
      if ("SYNC_ROOT".equals(op.op())) {
        laterRoots.add(new PathPrefixScope(
            io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(op.payload()).rootPath(), List.of()));
      } else if (!laterRoots.isEmpty() && ("UPSERT".equals(op.op()) || "DELETE".equals(op.op()))) {
        String path = "UPSERT".equals(op.op()) ? SwitchBufferUpsert.decode(op.payload()).path()
            : SwitchBufferDelete.decode(op.payload()).path();
        if (laterRoots.stream().anyMatch(root -> root.contains(path))) superseded.add(op);
      }
    }
    return superseded;
  }

  /** No post-snapshot versions may remain while the final mutation admission fence is held. */
  public static boolean switchBufferEmptyStrict(JobQueue queue) {
    return switchBufferEmptyStrict(queue, null);
  }

  public static boolean switchBufferEmptyStrict(JobQueue queue, String generation) {
    if (!(queue instanceof SwitchBufferCapableQueue sbq)) return false;
    try { return strictReplayOps(sbq, generation).isEmpty(); }
    catch (RuntimeException unreadable) { return false; }
  }

  private static void scopeLegacyPrefixes(SwitchBufferCapableQueue queue, RunningRuntime runtime,
      WorkerIngestService publisher) {
    var declarations = new AtomicReference<Set<Path>>();
    queue.scopeLegacyPrefixReceipts(prefix -> {
      if (publisher != null) return publisher.prefixScope(prefix);
      if (declarations.get() == null) {
        var configuration = runtime == null ? null : runtime.resolvedConfig();
        if (configuration == null) {
          throw new IllegalStateException("Legacy prefix conversion requires watched-root declarations");
        }
        try {
          declarations.set(WatchedRootsFormat.declaredPaths(
              configuration.paths().dataDir()));
        } catch (IOException unreadable) {
          throw new java.io.UncheckedIOException(unreadable);
        }
      }
      return scopeFromDeclarations(prefix, declarations.get());
    });
  }

  private static PathPrefixScope scopeFromDeclarations(String prefix, java.util.Collection<Path> roots) {
    String normalized = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix(prefix);
    return new PathPrefixScope(prefix, roots.stream().map(Path::toString)
        .filter(root -> {
          String child = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix(root);
          return !child.equals(normalized) && child.startsWith(normalized);
        }).toList());
  }

  private static List<SwitchBufferCapableQueue.SwitchBufferOp> strictReplayOps(
      SwitchBufferCapableQueue queue, String generation) {
    var snapshot = queue.listSwitchBufferOpsStrict();
    if (snapshot.stream().anyMatch(op -> WatcherRootReconciliation.OPERATION.equals(op.op())
        && op.generation() != null && !op.generation().isBlank())) {
      throw new IllegalArgumentException("Watcher reconciliation debt must be unscoped");
    }
    return snapshot.stream()
        .filter(op -> op.generation() == null || op.generation().isEmpty()
            || (generation != null && generation.equals(op.generation()))).toList();
  }

  private static ReplayOutcome drainSwitchBuffer(DrainSwitchBufferContext context,
      boolean exactRead, boolean removeAfterReplay) {
    return drainSwitchBuffer(context, exactRead, removeAfterReplay, true);
  }

  private static ReplayOutcome drainSwitchBuffer(DrainSwitchBufferContext context,
      boolean exactRead, boolean removeAfterReplay, boolean includeUnscoped) {
    return drainSwitchBuffer(context, exactRead, removeAfterReplay, includeUnscoped, () -> {});
  }

  private static ReplayOutcome drainSwitchBuffer(DrainSwitchBufferContext context,
      boolean exactRead, boolean removeAfterReplay, boolean includeUnscoped,
      Runnable firstCandidateProjectionApplied) {
    return drainSwitchBuffer(context, exactRead, removeAfterReplay, includeUnscoped,
        firstCandidateProjectionApplied, false);
  }

  private static ReplayOutcome drainSwitchBuffer(DrainSwitchBufferContext context,
      boolean exactRead, boolean removeAfterReplay, boolean includeUnscoped,
      Runnable firstCandidateProjectionApplied, boolean refusedSourceRecovery) {
    return drainSwitchBuffer(context, exactRead, removeAfterReplay, includeUnscoped,
        firstCandidateProjectionApplied, refusedSourceRecovery, () -> {});
  }

  private static ReplayOutcome drainSwitchBuffer(DrainSwitchBufferContext context,
      boolean exactRead, boolean removeAfterReplay, boolean includeUnscoped,
      Runnable firstCandidateProjectionApplied, boolean refusedSourceRecovery,
      Runnable firstPruneRemoval) {
    return drainSwitchBuffer(context, exactRead, removeAfterReplay, includeUnscoped,
        firstCandidateProjectionApplied, refusedSourceRecovery, firstPruneRemoval, () -> {});
  }

  private static ReplayOutcome drainSwitchBuffer(DrainSwitchBufferContext context,
      boolean exactRead, boolean removeAfterReplay, boolean includeUnscoped,
      Runnable firstCandidateProjectionApplied, boolean refusedSourceRecovery,
      Runnable firstPruneRemoval, Runnable sourceBeforeCommit) {
    return drainSwitchBuffer(context, exactRead, removeAfterReplay, includeUnscoped,
        firstCandidateProjectionApplied, refusedSourceRecovery, firstPruneRemoval, sourceBeforeCommit, null);
  }

  private static ReplayOutcome drainSwitchBuffer(DrainSwitchBufferContext context,
      boolean exactRead, boolean removeAfterReplay, boolean includeUnscoped,
      Runnable firstCandidateProjectionApplied, boolean refusedSourceRecovery,
      Runnable firstPruneRemoval, Runnable sourceBeforeCommit, Release030Roots recoveryRoots) {
    if (!(context.jobQueue() instanceof SwitchBufferCapableQueue sbq)) {
      return new ReplayOutcome(false, List.of());
    }
    if (recoveryRoots != null) {
      try {
        scopeLegacyPrefixes(sbq,context.ingestLifecycle(),context.publisher());
        ((SqliteJobQueue)context.jobQueue()).retirementReplayOps();
      } catch (RuntimeException invalid) { context.log().warn("Retirement receipt union is unavailable",invalid); return new ReplayOutcome(false,List.of()); }
    }
    if (recoveryRoots != null && !enumerateRetirementMembership(context,recoveryRoots)) {
      return new ReplayOutcome(false,List.of());
    }
    boolean allowVdu = context.vduReplayAllowed().getAsBoolean();
    List<SwitchBufferCapableQueue.SwitchBufferOp> allOps;
    try {
      scopeLegacyPrefixes(sbq, context.ingestLifecycle(), context.publisher());
      allOps = recoveryRoots != null ? ((SqliteJobQueue)context.jobQueue()).retirementReplayOps().stream()
          .filter(op -> includeUnscoped && op.generation().isBlank() || context.replayGeneration().equals(op.generation())).toList()
          : exactRead ? strictReplayOps(sbq, context.replayGeneration()).stream()
              .filter(op -> includeUnscoped || context.replayGeneration().equals(op.generation()))
              .toList()
          : sbq.listSwitchBufferOps().stream()
              .filter(op -> (includeUnscoped && (op.generation() == null || op.generation().isEmpty()))
                  || (context.replayGeneration() != null
                      && context.replayGeneration().equals(op.generation()))).toList();
    } catch (RuntimeException unreadable) {
      context.log().warn("Switch buffer cannot certify final replay", unreadable);
      if (context.publisher() != null) context.publisher().watcherReplayRefusal(unreadable.getMessage());
      return new ReplayOutcome(false, List.of());
    }
    if (context.nativePolicy() != null && !context.nativePolicy().freeze(allOps)) {
      return new ReplayOutcome(false, List.of());
    }
    // Conversion replaces a receipt revision. An approval to skip the old exact version
    // cannot silently turn into permission to apply its replacement's destructive effect.
    if (!context.approvedGapVersions().isEmpty()
        && !new java.util.HashSet<>(allOps).containsAll(context.approvedGapVersions())) {
      context.log().warn("Approved gap receipts changed before replay; retaining snapshot");
      return new ReplayOutcome(false, List.of());
    }
    boolean deferredVdu = !allowVdu && allOps.stream().anyMatch(op -> isVduBufferKind(op.op()));
    List<SwitchBufferCapableQueue.SwitchBufferOp> ops = allOps.stream()
        .filter(op -> allowVdu || !isVduBufferKind(op.op())).toList();
    if (ops.isEmpty() && recoveryRoots == null) {
      return new ReplayOutcome(!deferredVdu, List.of());
    }
    context.log().info("Draining {} buffered ops from durable switch buffer...", ops.size());

    Set<SwitchBufferCapableQueue.SwitchBufferOp> fileAuthorizingDeletes;
    Set<SwitchBufferCapableQueue.SwitchBufferOp> deferredSurvivorDeletes = new LinkedHashSet<>();
    Map<SwitchBufferCapableQueue.SwitchBufferOp, List<String>> fileSurvivorExclusions;
    ReplayDeleteOrder deleteOrder;
    Set<SwitchBufferCapableQueue.SwitchBufferOp> supersededLegacyFiles;
    Set<SwitchBufferCapableQueue.SwitchBufferOp> supersededProjections;
    try {
      deleteOrder = replayDeleteOrder(context, ops, exactRead, recoveryRoots);
      supersededLegacyFiles = supersededLegacyFiles(context, ops);
      supersededProjections = supersededRetirementProjections(context,ops);
      fileAuthorizingDeletes = exactRead ? fileRelatedBroadDeletes(context, ops, deleteOrder) : Set.of();

    } catch (RuntimeException invalid) {
      context.log().warn("Buffered file/delete receipts cannot certify replay", invalid);
      return new ReplayOutcome(false, List.of());
    }

    Map<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> reconciliations = new LinkedHashMap<>();
    try {
      List<String> admittedPaths = ops.stream()
          .filter(op -> "UPSERT".equals(op.op()) || "DELETE".equals(op.op()))
          .map(op -> "UPSERT".equals(op.op()) ? SwitchBufferUpsert.decode(op.payload()).path()
              : SwitchBufferDelete.decode(op.payload()).path()).toList();
      for (var op : ops) {
        if (!WatcherRootReconciliation.OPERATION.equals(op.op())) continue;
        var debt = WatcherRootReconciliation.decode(op.payload());
        if (!debt.key().equals(op.key()) || op.generation() != null && !op.generation().isBlank()
            || op.revision() == null || op.revision().isBlank()) {
          throw new IOException("Invalid watcher reconciliation key, generation or revision");
        }
        var reconciliation = new WatcherReplay(debt, context.ingestLifecycle(), context.jobQueue(),
            context.publisher(), context.indexingPacing(), context.migrationRoots(), admittedPaths,
            () -> Thread.currentThread().isInterrupted() || System.nanoTime() >= context.deadlineNanos(),
            context.nativePolicy());
        reconciliation.bind(context.replayGeneration(), allOps, op);
        reconciliations.put(op, reconciliation);
      }
      var physicalDeletes = recoveryRoots == null ? fileAuthorizingDeletes
          : fileAuthorizingDeletes.stream().filter(op -> !recoveryRoots.checkpoint().deletionQueueComplete(op))
              .collect(java.util.stream.Collectors.toSet());
      fileSurvivorExclusions = replayFileSurvivorExclusions(context, ops, physicalDeletes,
          deleteOrder, reconciliations, recoveryRoots == null ? null : deferredSurvivorDeletes);
      if (recoveryRoots != null) fileSurvivorExclusions = ordinaryRootSurvivors(context, ops,
          fileSurvivorExclusions, deferredSurvivorDeletes);
      for (var entry : reconciliations.entrySet()) {
        entry.getValue().prepare(context.replayGeneration(), allOps, entry.getKey());
      }
    } catch (IOException | RuntimeException incomplete) {
      closeReconciliations(reconciliations, context.log());
      context.log().warn("Watcher reconciliation preflight keeps cutover fenced: {}", incomplete.getMessage());
      if (context.publisher() != null) context.publisher().watcherReplayRefusal(incomplete.getMessage());
      return new ReplayOutcome(false, List.of());
    }
    try {
    ArrayList<SwitchBufferCapableQueue.SwitchBufferOp> toEnqueue = new ArrayList<>();
    boolean mutatedLucene = false;
    boolean allApplied = true;
    boolean releaseWriterPending = false;
    boolean releaseDeferred = false;
    List<SwitchBufferCapableQueue.SwitchBufferOp> blockedReleaseScopes = new ArrayList<>();
    boolean firstCandidateProjectionObserved = false;
    AtomicReference<RuntimeException> pruneCutFailure = new AtomicReference<>();

    for (SwitchBufferCapableQueue.SwitchBufferOp op : ops) {
      if (supersededLegacyFiles.contains(op) || supersededProjections.contains(op)) continue;
      if (recoveryRoots != null && recoveryRoots.checkpoint().deletionQueueComplete(op)) continue;
      if (recoveryRoots != null && deferredSurvivorDeletes.contains(op)) {
        blockedReleaseScopes.add(op);
        releaseDeferred = true;
        continue;
      }
      if (recoveryRoots != null && blockedReleaseScopes.stream()
          .anyMatch(earlier -> releaseScopesOverlap(context, recoveryRoots, earlier, op))) {
        // The deferred operation also orders later overlapping scopes (for example a
        // collection delete connecting two otherwise independent roots).
        blockedReleaseScopes.add(op);
        releaseDeferred = true;
        continue;
      }
      if (op.op() == null || op.payload() == null) {
        allApplied = false;
        continue;
      }
      String kind = op.op().trim().toUpperCase(Locale.ROOT);
      String payload = op.payload();
      // Enqueue a preceding UPSERT before applying a later delete or prefix mutation. The
      // switch buffer is version ordered; delaying every UPSERT until the end resurrects paths
      // that a later DELETE_PREFIX removed.
      if (!"UPSERT".equals(kind) && !toEnqueue.isEmpty()) {
        if (!enqueueBufferedUpserts(context, toEnqueue)
            || (exactRead && (!awaitQueuedUpserts(context, refusedSourceRecovery, reconciliations)
                || !verifyBufferedUpserts(context, toEnqueue, ops, deleteOrder, false,
                    refusedSourceRecovery, reconciliations, fileSurvivorExclusions)))) {
          context.log().warn("Buffered UPSERT did not settle before a later {}", kind);
          return new ReplayOutcome(false, List.of());
        }
        toEnqueue.clear();
      }
      // These exact row revisions and gap reasons were accepted from the current full Green
      // witness under the final fence. Keep their versions for post-pointer deletion only.
      if (context.approvedGapVersions().contains(op)) continue;
      if (payload.isBlank() || (!"UPSERT".equals(kind) && context.ingestLifecycle() == null)) {
        allApplied = false;
        continue;
      }
      boolean appliedProjection = false;
      if(recoveryRoots!=null && List.of("DELETE","DELETE_PREFIX","DELETE_COLLECTION").contains(kind)
          && recoveryRoots.checkpoint().deletionCommitted(op)) {
        // Only queue-owned full-row reconciliation can renew this proof. Broad scope
        // removal here could erase an unknown owner and can never repeat A's effect.
        releaseDeferred=true;blockedReleaseScopes.add(op);continue;
      }
      switch (kind) {
        case WatcherRootReconciliation.OPERATION -> {
          var reconciliation = reconciliations.get(op);
          try {
            if (reconciliation == null) throw new IOException("Watcher reconciliation has no fresh preflight");
            reconciliation.apply();
            if (!awaitQueuedUpserts(context, refusedSourceRecovery, reconciliations)) {
              throw new IOException("Watcher reconciliation writer has not drained");
            }
            reconciliation.commitAndVerify();
            mutatedLucene = false; // The root commit includes every preceding target write.

          } catch (IOException | RuntimeException incomplete) {
            context.log().warn("Watcher reconciliation keeps cutover fenced: {}", incomplete.getMessage());
            if (context.publisher() != null) context.publisher().watcherReplayRefusal(incomplete.getMessage());
            return new ReplayOutcome(false, List.of());
          }
        }
        case "PROJECTION_SOURCE" -> {
          String marker = "projection-source:" + payload.length() + ":" + payload;
          if (op.generation() == null || op.generation().isBlank()
              || !marker.equals(op.key())
              || (!refusedSourceRecovery && !context.projectionSourceReady().test(payload))) {
            allApplied = false;
            context.log().warn("Projection source is missing or incomplete: {}", payload);
          }
        }
        case "PROJECTION" -> {
          try {
            var projection = AcceptedProjection.decode(payload);
            if (op.generation() == null || op.generation().isBlank()
                || !op.key().equals(projection.journalKey())
                || context.ingestLifecycle() == null) {
              throw new IllegalStateException("Projection replay lacks its exact candidate or key");
            }
            var fields = context.ingestLifecycle().documentFieldOps();
            context.ingestLifecycle().commitOps().maybeRefreshBlocking();
            String currentId = fields.getDocumentFieldOrThrow(projection.indexId(), SchemaFields.DOC_ID);
            String currentSource = fields.getDocumentFieldOrThrow(
                projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID);
            String currentRevision = fields.getDocumentFieldOrThrow(
                projection.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION);
            if (currentId == null && (currentSource != null || currentRevision != null
                || context.ingestLifecycle().indexCountOps()
                    .countByIdAndChunksStrict(projection.indexId()) != 0)) {
              throw new IllegalStateException("Projection replay cannot certify candidate absence");
            }
            if (currentId != null && (!projection.indexId().equals(currentId)
                || !projection.sourceId().equals(currentSource) || currentRevision == null)) {
              throw new IllegalStateException("Projection replay found an unowned candidate document");
            }
            long candidateRevision = currentRevision == null ? -1 : Long.parseLong(currentRevision);
            if (currentId != null && (candidateRevision < 0
                || !Long.toString(candidateRevision).equals(currentRevision))) {
              throw new IllegalStateException("Projection replay found noncanonical revision evidence");
            }
            if (candidateRevision > projection.sourceRevision()) {
              if (!JobQueue.IngestionLedgerTransition.isSha256(fields.getDocumentFieldOrThrow(
                  projection.indexId(), SchemaFields.PROJECTION_DIGEST))) {
                throw new IllegalStateException("Newer projection lacks a complete digest witness");
              }
              break;
            }
            if (candidateRevision == projection.sourceRevision()
                && projection.kind() == AcceptedProjection.Kind.UPSERT) {
              String digest = fields.getDocumentFieldOrThrow(
                  projection.indexId(), SchemaFields.PROJECTION_DIGEST);
              if (!projection.fieldsDigest().equals(digest)) {
                throw new IllegalStateException("Equal projection revision has different fields");
              }
              break;
            }
            if (projection.kind() == AcceptedProjection.Kind.DELETE) {
              context.ingestLifecycle().indexingCoordinator()
                  .deleteByIdAndChunks(projection.indexId());
            } else {
              context.ingestLifecycle().indexingCoordinator().indexSingle(
                  ProjectionDocumentMapper
                      .toIndexDocument(projection));
            }
            mutatedLucene = true;
            // A delete of an already-absent document has no physical write to witness.
            appliedProjection = projection.kind()
                == AcceptedProjection.Kind.UPSERT;
          } catch (Exception failure) {
            allApplied = false;
            context.log().warn("Buffered projection replay failed; retaining candidate journal key={}",
                op.key(), failure);
          }
        }
        case "UPSERT" -> {
          if (!payload.isBlank()) {
            try {
              var upsert = SwitchBufferUpsert.decode(payload);
              if (op.generation() != null && !op.generation().isEmpty()
                  && (upsert.sourceSha256() == null
                      || !op.key().equals("path:" + upsert.path())
                      || !Path.of(upsert.path()).toAbsolutePath().normalize().toString().equals(upsert.path()))) {
                throw new IllegalArgumentException("Scoped UPSERT has no exact source witness");
              }
              if (recoveryRoots != null) {
                allApplied = verifyBufferedUpserts(context, List.of(op), ops, deleteOrder, false, true, reconciliations, fileSurvivorExclusions);
              } else {
                toEnqueue.add(op);
              }
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
              var deletion = SwitchBufferDelete.decode(payload);
              if (!op.key().equals("path:" + deletion.path())) {
                throw new IllegalArgumentException("Buffered DELETE key mismatch");
              }
              boolean reconciledDelete = false;
              for (var root : reconciliations.entrySet()) {
                if (!root.getValue().coversDelete(deletion)) continue;
                if (deleteOrder.positions().get(root.getKey()) < deleteOrder.positions().get(op)
                    && !root.getValue().verifies(deletion.path(), deletion.collection())) {
                  throw new IOException("Watcher DELETE lost its committed current-path outcome");
                }
                // A conditional event is debt, not an explicit removal. Its fresh path outcome
                // either already committed or must commit at the later root receipt position.
                reconciledDelete = true;
              }
              if (reconciledDelete) break;
              if (deletion.origin() == SwitchBufferDelete.Origin.EXPLICIT) {
                context.ingestLifecycle().indexingCoordinator().deleteByIdAndChunks(deletion.path());
                if (context.jobQueue().deleteByExactPath(deletion.path()) < 0) {
                  throw new IllegalStateException("Buffered exact queue deletion failed");
                }
                mutatedLucene = true;
              } else {
                WorkerIngestService authority = context.publisher() != null
                    ? context.publisher() : replayService(context);
                if (release030Retirement(context)) {
                  Path file = Path.of(deletion.path());
                  Path boundary = authority.bufferedDeleteRoot(deletion);
                  if (context.jobQueue() instanceof SqliteJobQueue sqlite && sqlite.isRelease030RetirementFile(file))
                    boundary = file;
                  String collection = deletion.collection();
                  if (collection == null) collection = context.ingestLifecycle().documentFieldOps()
                      .getDocumentFieldOrThrow(deletion.path(), SchemaFields.COLLECTION);
                  allApplied &= settleRelease030ConditionalDelete(context,op,deletion,file,boundary,collection,deleteOrder);
                } else if (uncertainBeneathItsRoot(authority, deletion)) {
                  // The root vouches, but the path's own state cannot be confirmed (a folder on
                  // the way cannot be listed): nothing is admitted or removed on the target, and
                  // the receipt stays until the path can be judged.
                  allApplied = false;
                  context.log().warn("Buffered DELETE kept: its path cannot be confirmed "
                      + "beneath its root: key={}", op.key());
                } else {
                  var replayed =
                      authority.replayBufferedFileDelete(context.ingestLifecycle(), deletion);
                  if (replayed == WorkerIngestService.BufferedDeleteReplay.UNCERTAIN
                      && refusedSourceRecovery && !release030Retirement(context)) {
                    // Nothing was changed on surviving A. Its own root reconciliation removes the
                    // file's rows once the root returns and proves the file absent.
                    context.log().info("Refused candidate leaves an unproved DELETE to root "
                        + "reconciliation on the surviving generation: key={}", op.key());
                  } else if (replayed != WorkerIngestService.BufferedDeleteReplay.SETTLED) {
                    allApplied = false;
                  }
                }
              }
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
              List<String> survivors = fileSurvivorExclusions.getOrDefault(op, List.of());
              var scope = PathPrefixScope.decode(payload);
              String prefix = PathPrefixScope.isLegacy(payload) ? payload : scope.prefix();
              var queueSurvivors = new LinkedHashSet<>(survivors);
              if (context.nativePolicy() != null) queueSurvivors.addAll(context.nativePolicy().omittedPaths());
              // Policy omission proves zero index rows. It is only an exact terminal queue
              // allowance, never an exception to the index deletion.
              if (scope.excludedSubtrees().isEmpty() && survivors.isEmpty()) {
                context.ingestLifecycle().indexingCoordinator().deleteByPathPrefix(prefix);
              } else if (scope.excludedSubtrees().isEmpty()) {
                context.ingestLifecycle().indexingCoordinator()
                    .deleteByPathPrefixExcludingAcceptedSurvivors(prefix, survivors);
              } else {
                context.ingestLifecycle().indexingCoordinator().deleteByPathPrefixExcludingAcceptedSurvivors(
                    prefix, survivors, scope.excludedSubtrees());
              }
              if (scope.excludedSubtrees().isEmpty() && queueSurvivors.isEmpty()) {
                if (context.jobQueue().deleteByPathPrefix(prefix) < 0) {
                  throw new IllegalStateException("Buffered prefix queue deletion failed");
                }
              } else {
                context.jobQueue().deleteByPathPrefixExcludingAcceptedSurvivors(
                    prefix, List.copyOf(queueSurvivors), scope.excludedSubtrees());
              }
              mutatedLucene = true;
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
        case "DELETE_COLLECTION" -> {
          try {
            List<String> survivors = fileSurvivorExclusions.getOrDefault(op, List.of());
            if (survivors.isEmpty()) {
              context.ingestLifecycle().indexingCoordinator().deleteByCollection(payload);
            } else {
              context.ingestLifecycle().indexingCoordinator()
                  .deleteByCollectionExcludingAcceptedSurvivors(payload, survivors);
            }
            if(recoveryRoots!=null)removeRetirementQueueScope(context,op,survivors);
            mutatedLucene = true;
          } catch (Exception e) {
            allApplied = false;
            context.log().warn("Failed to replay buffered DELETE_COLLECTION: key={} err={}",
                op.key(), e.getMessage());
          }
        }
        case "VDU_MARK_PROCESSING" -> {
          if (context.ingestLifecycle() != null && !payload.isBlank()) {
            try {
              var node = context.json().readTree(payload);
              String docId = node.path("doc_id").asText();
              if (docId.isBlank()) throw new IllegalArgumentException("Buffered VDU mark has no document id");
              var retry = node.path("retry_count");
              if (!retry.isIntegralNumber() || !retry.canConvertToInt() || retry.asInt() <= 0) {
                throw new IllegalArgumentException("Buffered VDU mark has no positive retry count");
              }
              int retryCount = retry.asInt();
              Map<String, Object> updates = new HashMap<>();
              updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
              updates.put(SchemaFields.VDU_RETRY_COUNT, String.valueOf(retryCount));
              boolean updated = context.ingestLifecycle().indexingCoordinator().updateDocument(docId, updates);
              if (!updated) {
                allApplied = false;
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
              if (docId.isBlank()) throw new IllegalArgumentException("Buffered VDU mark has no document id");
              Map<String, Object> updates = new HashMap<>();
              updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_FAILED);
              updates.put(SchemaFields.VDU_ENRICHMENT, "{\"error\": \"Max retries exceeded\"}");
              boolean updated = context.ingestLifecycle().indexingCoordinator().updateDocument(docId, updates);
              if (!updated) {
                allApplied = false;
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
              if (!context.json().readTree(payload).isObject()) {
                throw new IllegalArgumentException("Buffered VDU recovery payload is not an object");
              }
              WorkerIngestService tmp =
                  new WorkerIngestService(
                      context.jobQueue(),
                      null,
                      context.signalBus(),
                      context.indexingPacing(),
                      context.indexBasePath(),
                      context.activeIndexPath(),
                      context.ingestLifecycle(),
                      context.ingestLifecycle(),
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
              if (resp == null) throw new IllegalStateException("Buffered VDU recovery has no outcome");
              int recovered = resp.getRecoveredCount();
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

              var request = io.justsearch.ipc.UpdateVduResultRequest.newBuilder()
                  .setDocId(docId).setVduStatus(vduStatus).setVduEnrichment(enrichment)
                  .setPageCount(pageCount).setOutcomeValue(outcomeNum);
              if (hasExtracted && extracted != null) request.setExtractedContent(extracted);
              boolean updated = replayVduResult(context, request.build());
              if (updated) {
                mutatedLucene = true;
                context.log().debug("Replayed buffered VDU_UPDATE: docId={}", docId);
              } else {
                allApplied = false;
                context.log().warn("Buffered VDU_UPDATE: document not found: {}", docId);
              }
            } catch (Exception e) {
              allApplied = false;
              context
                  .log()
                  .warn(
                      "Failed to replay buffered VDU_UPDATE: key={} err={}",
                      op.key(),
                      e.getMessage());
            }
          } else {
            allApplied = false;
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

              WorkerIngestService tmp = replayService(context);
              // The sync's prune is vouched for and fenced by the live Worker, whose loop may be
              // writing files under the root while the sync runs.
              if (context.publisher() != null) tmp.prunesUnder(context.publisher());
              SyncDirectoryRequest req =
                  SyncDirectoryRequest.newBuilder().setRootPath(rootPath).setForce(force).build();

              SyncDirectoryResponse r;
              try {
                Supplier<SyncDirectoryResponse> walk = () -> {
                  if (release030Retirement(context)
                      && op.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX)) {
                    Path root = Path.of(rootPath).toAbsolutePath().normalize();
                    return recoveryRoots.currentRoots().containsKey(root)
                        ? context.publisher().recoverRelease030Root(root, recoveryRoots.currentRoots().get(root),
                            () -> recoveryRoots.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted(),
                            op)
                        : SyncDirectoryResponse.getDefaultInstance(); // A removed root authorizes no walk.
                  }
                  if (release030Retirement(context)) return tmp.syncDirectoryForRelease030Replay(req,provenance,op);
                  return exactRead ? tmp.syncDirectoryForFinalCutoverReplay(req, provenance)
                      : tmp.syncDirectoryForReplay(req, provenance);
                };
                r = release030Retirement(context) ? recoveryRoots.replay().apply(op, walk) : walk.get();
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

              if (release030Retirement(context) && r != null
                  && r.getError().contains(WorkerIngestService.RETIREMENT_EXCLUDED_INDEXED))
                logExcludedRetirementWait(context);
              if (r == null || !r.getError().isBlank()
                  || (release030Retirement(context) && (r.getDeleteDetectionUnverified() || r.getSkipped()))) {
                allApplied = false;
                context
                    .log()
                    .warn(
                        "Buffered SYNC_ROOT replay returned error (will retry later): key={} error={}",
                        op.key(),
                        r == null ? "no response" : r.getError());
              } else {
                context.log().info("Replayed buffered SYNC_ROOT: root={} force={}", rootPath, force);
                if (release030Retirement(context)
                    && context.jobQueue().hasNonterminalJobsByPathPrefixStrict(rootPath)) releaseWriterPending = true;
                // Enumeration may enqueue a file that the Green loop has already claimed. A
                // subsequent direct DELETE_PREFIX must wait for that writer, not merely remove its
                // queue row while it can still publish a late Lucene write.
                if (exactRead && !awaitQueuedUpserts(context, refusedSourceRecovery, reconciliations)) {
                  return new ReplayOutcome(false, List.of());
                }
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
              // #803: replay prunes only confirmed absences. The root holding the
              // prefix must be present, listable, the folder a watch registered and on the volume
              // recorded for it, re-checked every batch; each batch is applied inside the live
              // Worker's file-mutation fence. A prune that could not finish stays unapplied and is
              // retried, exactly like a SYNC_ROOT replay whose root is gone.
              WorkerIngestService authority =
                  context.publisher() != null ? context.publisher() : replayService(context);
              var replayed = authority.replayBufferedPrune(
                  context.ingestLifecycle(), payload, () -> {
                    try {
                      firstPruneRemoval.run();
                    } catch (RuntimeException barrierFailure) {
                      pruneCutFailure.set(barrierFailure);
                      throw barrierFailure;
                    }
                  });
              var result = replayed.result();
              int pruned = result.pruned();
              mutatedLucene = mutatedLucene || pruned > 0;
              if (!result.complete()) {
                allApplied = false;
                context
                    .log()
                    .warn(
                        "Buffered PRUNE_PREFIX replay did not complete ({}), will retry later:"
                            + " prefix={} pruned={}",
                        result.completion(),
                        payload,
                        pruned);
              } else {
                context
                    .log()
                    .info("Replayed buffered PRUNE_PREFIX: prefix={} pruned={}", payload, pruned);
                // A removed file found present again was queued; a later direct delete must wait
                // for its writer, as after a replayed SYNC_ROOT.
                if (exactRead && replayed.requeued() > 0
                    && !awaitQueuedUpserts(context, refusedSourceRecovery, reconciliations)) {
                  return new ReplayOutcome(false, List.of());
                }
              }
            } catch (Exception e) {
              // A harness cut that could not record its marker aborts the whole attempt, as the
              // projection cut does; it is not a replay gap.
              if (pruneCutFailure.get() != null) throw pruneCutFailure.get();
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
        default -> {
          allApplied = false;
          context.log().warn("Unknown switch buffer op '{}': key={}", kind, op.key());
        }
      }
      if (recoveryRoots != null) {
        // An unresolved receipt orders only overlapping scopes. Other roots can recover and
        // their ordinary writer runs between attempts while the earlier source remains uncertain.
        if (!allApplied) {
          blockedReleaseScopes.add(op);
          releaseDeferred = true;
          allApplied = true;
          continue;
        }
        if ("DELETE".equals(kind) || "DELETE_PREFIX".equals(kind) || "DELETE_COLLECTION".equals(kind)) {
          try {
            if (mutatedLucene) sourceBeforeCommit.run();
            context.ingestLifecycle().commitOps().commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
            context.ingestLifecycle().commitOps().maybeRefreshBlocking();
            if (!certifyReleaseDeletion(context, op, fileSurvivorExclusions.getOrDefault(op, List.of()))) {
              blockedReleaseScopes.add(op);
              releaseDeferred = true;
              continue;
            }
            recoveryRoots.checkpoint().markDeletionCommitted(op);
            mutatedLucene = false;
          } catch (IOException | RuntimeException failure) {
            context.log().warn("Release delete checkpoint remains unresolved: key={}", op.key(), failure);
            return new ReplayOutcome(false, List.of());
          }
        }
      }
      if (appliedProjection && exactRead && !removeAfterReplay
          && !firstCandidateProjectionObserved) {
        firstCandidateProjectionObserved = true;
        // The harness cut lives after one physical write but before the remaining exact
        // versions, commit, verification and journal cleanup. Ordinary runs invoke a no-op.
        firstCandidateProjectionApplied.run();
      }
    }

    if (!toEnqueue.isEmpty()) {
      allApplied &= enqueueBufferedUpserts(context, toEnqueue)
          && (!exactRead || awaitQueuedUpserts(context, refusedSourceRecovery, reconciliations));
    }
    allApplied &= !releaseWriterPending && !releaseDeferred;

    // Refused recovery also judges file admissions by A's writer, whose batches A's reader sees
    // before they are durable: commit A so the journal is acknowledged only for committed effects.
    if ((mutatedLucene || refusedSourceRecovery) && context.ingestLifecycle() != null) {
      try {
        sourceBeforeCommit.run();
        // Tempdoc 912 item 2: this used to call the low-level CommitOps.commit(), which skips the
        // per-reason counter, the pendingDocs reset, the commit telemetry and the
        // CommitCompletedListener that keeps EmbeddingCompatibilityController's fingerprint in
        // sync. The low-level primitive is package-private now, so this bypass is a compile error
        // rather than an allowlist entry.
        context
            .ingestLifecycle()
            .commitOps()
            .commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
        if (release030Retirement(context)) context.ingestLifecycle().commitOps().maybeRefreshBlocking();
      } catch (Exception e) {
        allApplied = false;
        context.log().warn("Failed to commit buffered DELETE ops (will retry later): {}", e.getMessage());
      }
    }

    if (allApplied && exactRead) {
      allApplied = verifyBufferedUpserts(context, ops.stream()
          .filter(op -> "UPSERT".equalsIgnoreCase(op.op())
              && !context.approvedGapVersions().contains(op)).toList(), ops, deleteOrder, true,
          refusedSourceRecovery, reconciliations, fileSurvivorExclusions);
    }
    if (allApplied) {
      allApplied = verifyBufferedProjections(context, ops, deleteOrder);
    }

    if (allApplied && ops.stream().anyMatch(op -> isVduBufferKind(op.op()))
        && !context.vduReplayAllowed().getAsBoolean()) {
      allApplied = false;
      context.log().warn("Serving generation changed during VDU replay; retaining snapshot for retry");
    }
    boolean removed = false;
    if (allApplied && removeAfterReplay && refusedSourceRecovery) {
      // Refused recovery never takes the candidate projection cut above; its runnable is the
      // harness cut between A's committed, verified replay and the journal acknowledgement.
      firstCandidateProjectionApplied.run();
    }
    if (allApplied && removeAfterReplay) {
      try {
        // Pause preparation and the final read-only decision precede the root uncertainty
        // monitor. A writer completing root uncertainty must not wait on that monitor while
        // its caller waits for the same writer to pause.
        if (context.nativePolicy() != null && !context.nativePolicy().confirmCommittedAcknowledgement(ops)) {
          return new ReplayOutcome(false, List.of());
        }
        BooleanSupplier acknowledge = () -> {
          if (recoveryRoots != null) {
            var legacyFiles = new HashMap<String,SwitchBufferUpsert>();
            try {
              for (var op : ops) if ("UPSERT".equals(op.op()) && !op.payload().stripLeading().startsWith("{")
                  && !supersededLegacyFiles.contains(op))
                legacyFiles.put(op.retirementUuid(), resolveReleaseUpsert(context,op));
            } catch (IOException unavailable) { throw new IllegalStateException("Legacy replay expectation unavailable",unavailable); }
            return ((SqliteJobQueue)context.jobQueue()).acknowledgeRetirementReplay(ops,legacyFiles);
          }
          int cleared = sbq.removeReplayedSwitchBufferOps(ops);
          context.log().info("Removed {} replayed buffer versions; later admissions remain", cleared);
          return cleared == ops.size();
        };
        removed = reconciliations.isEmpty() || context.publisher() == null
            ? acknowledge.getAsBoolean() : context.publisher().acknowledgeWatcherReplay(acknowledge);
      } catch (IllegalStateException failure) {
        context.log().warn("Failed to remove committed buffer versions; retaining for retry", failure);
      }
    } else if (!allApplied) {
      context.log().warn("Not clearing switch buffer because one or more buffered ops failed to replay");
    }
    return new ReplayOutcome(allApplied && (removed || !removeAfterReplay) && !deferredVdu,
        allApplied ? ops : List.of());
    } finally { closeReconciliations(reconciliations, context.log()); }

  }

  private static void closeReconciliations(
      Map<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> reconciliations, Logger log) {
    for (var reconciliation : reconciliations.values()) {
      try { reconciliation.close(); }
      catch (IOException failure) { log.warn("Cannot close watcher replay root lease", failure); }
    }
  }

  /**
   * A buffered VDU result, applied inside the live Worker's file-mutation fence when there is one,
   * as a live VDU result is: a prune of its file then runs wholly before it (and the result finds
   * no parent) or wholly after it, never in between its chunk writes.
   */
  private static boolean replayVduResult(
      DrainSwitchBufferContext context, io.justsearch.ipc.UpdateVduResultRequest request)
      throws IOException {
    io.justsearch.indexerworker.services.VduResultWriter.validate(request);
    // This reader is only for legacy journal rows. They never recorded source/attempt proof,
    // so neither TEXT nor EMPTY is authorised against the current parent. Retire the exact
    // journal revision after arranging the same targeted refresh as a hashless live claim.
    var service = replayService(context);
    if (context.publisher() == null) {
      service.refreshLegacyVduSource(request.getDocId());
    } else {
      try {
        context.publisher().underFileMutationFence(() -> {
          try { service.refreshLegacyVduSource(request.getDocId()); return true; }
          catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
      } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
    }
    context.log().info("Retired unverifiable legacy VDU result: {}", request.getDocId());
    return true;
  }

  /**
   * A service over the replay target with no indexing loop of its own. Its prunes are unfenced
   * and vouched for without a watch registration unless {@link WorkerIngestService#prunesUnder}
   * hands them to the live publisher.
   */
  private static WorkerIngestService replayService(DrainSwitchBufferContext context) {
    var service = new WorkerIngestService(
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
    if (context.publisher() != null) service.prunesUnder(context.publisher());
    else if (context.ingestLifecycle() != null && context.ingestLifecycle().resolvedConfig() != null) {
      service.setDeclaredRootsDirectory(context.ingestLifecycle().resolvedConfig().paths().dataDir());
    }
    return service;
  }

  private static boolean enumerateRetirementMembership(DrainSwitchBufferContext context,Release030Roots roots) {
    if(!(context.jobQueue() instanceof SqliteJobQueue queue)||context.publisher()==null)return false;
    try {
      for(var receipt:roots.checkpoint().rootReceipts()) {
        if(roots.checkpoint().rootEnumerated(receipt))continue;
        var op=receipt;boolean automatic=op.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX);
        Path root=Path.of(io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(op.payload()).rootPath()).toAbsolutePath().normalize();
        if(automatic&&!roots.currentRoots().containsKey(root))continue;
        var response=context.publisher().enumerateRetirementRoot(receipt,automatic?roots.currentRoots().get(root):null,
            automatic,()->roots.cancelled().getAsBoolean()||Thread.currentThread().isInterrupted());
        if (response.getError().contains(WorkerIngestService.RETIREMENT_EXCLUDED_INDEXED))
          logExcludedRetirementWait(context);
        if(response.getError().isBlank()&&!response.getSkipped()&&!response.getDeleteDetectionUnverified())
          roots.checkpoint().markRootEnumerated(receipt);
      }
      return true; // An uncertain root holds its own later scopes, never unrelated roots.
    }catch(IOException|RuntimeException invalid){context.log().warn("ROOT inventory cannot authorize membership replay",invalid);return false;}
  }

  private static Map<SwitchBufferCapableQueue.SwitchBufferOp,List<String>> ordinaryRootSurvivors(
      DrainSwitchBufferContext context,List<SwitchBufferCapableQueue.SwitchBufferOp> ops,
      Map<SwitchBufferCapableQueue.SwitchBufferOp,List<String>> existing,
      Set<SwitchBufferCapableQueue.SwitchBufferOp> deferred) throws IOException {
    var queue=(SqliteJobQueue)context.jobQueue();var result=new HashMap<SwitchBufferCapableQueue.SwitchBufferOp,List<String>>(existing);
    var deleteOrders = queue.retirementDeleteOrders();
    String cursor="";
    while(true) {
      var page=queue.rootMembersAfter(cursor);if(page.isEmpty())break;
      for(var member:page) {
        for(var deletion:ops) {
          if(!List.of("DELETE","DELETE_PREFIX","DELETE_COLLECTION").contains(deletion.op()))continue;
          var ordered=deletion;
          if(ordered.acceptedOrder()>=member.root().acceptedOrder()
              ||deleteOrders.applyAsLong(member.path(),member.collection())>member.root().acceptedOrder())continue;
          boolean matches=switch(deletion.op()) {
            case "DELETE" -> SwitchBufferDelete.decode(deletion.payload()).path().equals(member.path());
            case "DELETE_PREFIX" -> PathPrefixScope.decode(deletion.payload()).contains(member.path());
            case "DELETE_COLLECTION" -> releaseCollection(member.collection()).equals(releaseCollection(deletion.payload()));
            default -> false;
          };
          if(!matches)continue;
          var active=context.ingestLifecycle();var fields=active.documentFieldOps();
          String indexed=fields.getDocumentFieldOrThrow(member.path(),SchemaFields.SOURCE_SHA256);
          boolean correct=member.path().equals(fields.getDocumentFieldOrThrow(member.path(),SchemaFields.DOC_ID))
              &&member.path().equals(fields.getDocumentFieldOrThrow(member.path(),SchemaFields.PATH))
              &&releaseCollection(member.collection()).equals(releaseCollection(fields.getDocumentFieldOrThrow(member.path(),SchemaFields.COLLECTION)));
          if(!"DONE".equals(member.state())||member.boundary()==null){deferred.add(deletion);continue;}
          Boolean policyOutcome = retirementPolicyOutcome(context, Path.of(member.path()), Path.of(member.boundary()));
          if (policyOutcome != null) {
            if (!policyOutcome) deferred.add(deletion);
            continue;
          }
          var source=observeForCleanup(context.publisher(),Path.of(member.path()),Path.of(member.boundary()));
          if(source.kind()==CleanupPathKind.UNCERTAIN){deferred.add(deletion);continue;}
          if(source.kind()==CleanupPathKind.REGULAR) {
            if(!correct||source.sha256()==null||!source.sha256().equals(indexed)){deferred.add(deletion);continue;}
            var files=new ArrayList<>(result.getOrDefault(deletion,List.of()));files.add(member.path());result.put(deletion,List.copyOf(files));
          }
        }
      }
      cursor=page.getLast().path();
    }
    return Map.copyOf(result);
  }

  private static void removeRetirementQueueScope(DrainSwitchBufferContext context,
      SwitchBufferCapableQueue.SwitchBufferOp op,List<String> survivors) throws IOException {
    switch(op.op()) {
      case "DELETE" -> {var d=SwitchBufferDelete.decode(op.payload());if(!survivors.contains(d.path())&&context.jobQueue().deleteByExactPath(d.path())<0)throw new IOException("Exact Q removal failed");}
      case "DELETE_PREFIX" -> {var scope=PathPrefixScope.decode(op.payload());context.jobQueue().deleteByPathPrefixExcludingAcceptedSurvivors(scope.prefix(),survivors,scope.excludedSubtrees());}
      case "DELETE_COLLECTION" -> {
        if(context.jobQueue() instanceof SqliteJobQueue queue)queue.removeRetirementCollection(op.payload(),survivors);
      }
      default -> throw new IOException("Unknown Q removal scope");
    }
  }

  /** Verify the durable effect at its ordered position, before a later walk may restore files. */
  private static boolean certifyReleaseDeletion(DrainSwitchBufferContext context,
      SwitchBufferCapableQueue.SwitchBufferOp op, List<String> survivors) throws IOException {
    var active = context.ingestLifecycle();
    return switch (op.op()) {
      case "DELETE_PREFIX" -> {
        var scope = PathPrefixScope.decode(op.payload());
        yield !context.jobQueue().hasNonterminalJobsByPathPrefixStrict(scope.prefix(), scope.excludedSubtrees())
            && active.indexCountOps().countPathPrefixExcludingAcceptedSurvivorsStrict(
                scope.prefix(), survivors, List.of(), scope.excludedSubtrees()) == 0;
      }
      case "DELETE_COLLECTION" -> !context.jobQueue().hasNonterminalJobsByCollectionStrict(op.payload())
          && active.indexCountOps().countCollectionExcludingAcceptedSurvivorsStrict(
              op.payload(), survivors, List.of()) == 0;
      case "DELETE" -> {
        var deletion = SwitchBufferDelete.decode(op.payload());
        // Conditional deletes already proved the current source's projection or its absence.
        yield deletion.origin() != SwitchBufferDelete.Origin.EXPLICIT
            || active.indexCountOps().countByIdAndChunksStrict(deletion.path()) == 0
                && context.jobQueue() instanceof SqliteJobQueue queue
                && !queue.hasUnfinishedFileJob(deletion.path());
      }
      default -> false;
    };
  }

  private record ReleaseReplayScope(String file, PathPrefixScope directory, String collection,
      boolean collectionDelete) {}

  private static ReleaseReplayScope releaseScope(DrainSwitchBufferContext context,
      Release030Roots recovery, SwitchBufferCapableQueue.SwitchBufferOp op) {
    return switch (op.op()) {
      case "UPSERT" -> {
        var file = SwitchBufferUpsert.decode(op.payload());
        yield new ReleaseReplayScope(file.path(), null, Objects.toString(file.collection(), IngestCollectionPolicy.DEFAULT_COLLECTION), false);
      }
      case "DELETE" -> {
        var file = SwitchBufferDelete.decode(op.payload());
        yield new ReleaseReplayScope(file.path(), null, file.collection(), false);
      }
      case "DELETE_PREFIX", "PRUNE_PREFIX" ->
          new ReleaseReplayScope(null, PathPrefixScope.decode(op.payload()), null, false);
      case "SYNC_ROOT" -> {
        String root = io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(op.payload()).rootPath();
        yield new ReleaseReplayScope(null, context.publisher().prefixScope(root),
            recovery.currentRoots().containsKey(Path.of(root))
                ? Objects.toString(recovery.currentRoots().get(Path.of(root)), IngestCollectionPolicy.DEFAULT_COLLECTION) : null, false);
      }
      case "DELETE_COLLECTION" -> new ReleaseReplayScope(null, null, op.payload(), true);
      default -> new ReleaseReplayScope(null, null, null, false);
    };
  }

  private static boolean releaseScopesOverlap(DrainSwitchBufferContext context,
      Release030Roots recovery, SwitchBufferCapableQueue.SwitchBufferOp first,
      SwitchBufferCapableQueue.SwitchBufferOp second) {
    var a = releaseScope(context, recovery, first);
    var b = releaseScope(context, recovery, second);
    if (a.collectionDelete() || b.collectionDelete()) {
      if(context.jobQueue() instanceof SqliteJobQueue queue) {
        var root="SYNC_ROOT".equals(first.op())?first:"SYNC_ROOT".equals(second.op())?second:null;
        String collection=a.collectionDelete()?a.collection():b.collection();
        if(root!=null){var actual=queue.retirementRootCollections(root);return actual.isEmpty()||actual.contains(collection);}
      }
      return a.collection() == null || b.collection() == null || a.collection().equals(b.collection());
    }
    if (a.file() != null && b.file() != null) return a.file().equals(b.file());
    if (a.directory() != null && b.file() != null) return a.directory().contains(b.file());
    if (b.directory() != null && a.file() != null) return b.directory().contains(a.file());
    if (a.directory() != null && b.directory() != null) {
      return a.directory().contains(b.directory().prefix()) || b.directory().contains(a.directory().prefix());
    }
    return true; // An unidentifiable scope cannot authorize reordering.
  }

  /** A later direct Lucene delete cannot overtake a claimed UPSERT still writing on Green. */
  private static boolean awaitQueuedUpserts(DrainSwitchBufferContext context,
      boolean refusedSourceRecovery,
      Map<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> reconciliations) {
    // All replay barriers consume the same SWITCHING budget. Returning early would re-enqueue
    // retained UPSERT versions on the next attempt, possibly resetting a claimed write.
    long deadline = context.deadlineNanos();
    try {
      while (true) {
        for (var reconciliation : reconciliations.values()) reconciliation.prepare();
        JobQueue.JobStateCounts counts = context.jobQueue().jobStateCountsStrict();
        // Refused-candidate recovery runs with its writer paused and proved idle, so pending rows
        // cannot move here; they stay durable queue work that surviving A's writer runs between
        // attempts. Only a claimed row could still publish a late write.
        if (refusedSourceRecovery) return counts.processingCount() == 0;
        long pending = counts.pendingCount();
        if (context.replayGeneration() != null
            && !IndexGenerationManager.isRecordedGenerationIdentity(context.replayGeneration())
            && context.jobQueue() instanceof SqliteJobQueue queue) {
          pending = queue.nativeCutoverCountsStrict().pendingCount();
        }
        // Keep the full in-flight barrier: no direct delete may overtake a writing debt job.
        // Only original never-indexed exhausted work waiting in the queue is independent.
        if (counts.processingCount() == 0 && pending == 0) return true;
        if (System.nanoTime() >= deadline) return false;
        Thread.sleep(50);
      }
    } catch (IOException | RuntimeException unavailable) {
      context.log().warn("Cannot certify buffered UPSERT settlement", unavailable);
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static Set<SwitchBufferCapableQueue.SwitchBufferOp> supersededRetirementProjections(
      DrainSwitchBufferContext context,List<SwitchBufferCapableQueue.SwitchBufferOp> ops) {
    if (!release030Retirement(context)) return Set.of();
    var latest = new HashMap<String,AcceptedProjection>();
    var superseded = new java.util.HashSet<SwitchBufferCapableQueue.SwitchBufferOp>();
    for (var op : ops.reversed()) if ("PROJECTION".equals(op.op())) {
      var projection = AcceptedProjection.decode(op.payload());
      if (!op.key().equals(projection.journalKey())) throw new IllegalStateException("Projection identity mismatch");
      var newer = latest.putIfAbsent(op.generation()+"\0"+op.key(),projection);
      if (newer != null) {
        if (!newer.sourceId().equals(projection.sourceId()) || !newer.indexId().equals(projection.indexId())
            || newer.sourceRevision()<projection.sourceRevision()
            || newer.sourceRevision()==projection.sourceRevision() && !newer.sameEffect(projection))
          throw new IllegalStateException("Conflicting retained projection succession");
        superseded.add(op);
      }
    }
    return Set.copyOf(superseded);
  }

  private static SwitchBufferUpsert resolveReleaseUpsert(DrainSwitchBufferContext context,
      SwitchBufferCapableQueue.SwitchBufferOp op) throws IOException {
    var queue = (SqliteJobQueue)context.jobQueue();
    var checkpoint = queue.release030RecoveryState();
    var frozen = checkpoint.legacyExpectation(op);
    if (frozen != null) return frozen;
    var upsert = SwitchBufferUpsert.decode(op.payload());
    if (upsert.sourceSha256()==null && !op.payload().stripLeading().startsWith("{")) {
      // Only initial original-Q inventory can supply the missing legacy namespace.
      // A later row/configuration or the A image under verification cannot reconstruct it.
      throw new IOException("Legacy admission lacks its original collection/boundary evidence");
    }
    if (upsert.ingestionRoot()==null) {
      Path file=Path.of(upsert.path());
      Path boundary=context.publisher().bufferedDeleteRoot(new SwitchBufferDelete(upsert.path(),SwitchBufferDelete.Origin.LEGACY,null));
      if (queue.isRelease030RetirementFile(file)) boundary=file;
      upsert=new SwitchBufferUpsert(upsert.path(),upsert.collection(),upsert.provenance(),upsert.unitRevision(),upsert.sourceSha256(),boundary);
    }
    return upsert;
  }

  /** Older unscoped work must not renew the revision of a later accepted file admission. */
  private static Set<SwitchBufferCapableQueue.SwitchBufferOp> supersededLegacyFiles(
      DrainSwitchBufferContext context, List<SwitchBufferCapableQueue.SwitchBufferOp> ops) {
    Set<String> laterAccepted = new java.util.HashSet<>();
    Set<SwitchBufferCapableQueue.SwitchBufferOp> superseded = new java.util.HashSet<>();
    for (var op : ops.reversed()) {
      if (context.approvedGapVersions().contains(op)) continue;
      boolean legacy = op.generation() == null || op.generation().isBlank();
      if ("UPSERT".equalsIgnoreCase(op.op())) {
        var upsert = SwitchBufferUpsert.decode(op.payload());
        if (upsert.sourceSha256() != null && !legacy
            && op.generation().equals(context.replayGeneration())
            && op.key().equals("path:" + upsert.path())
            && Path.of(upsert.path()).normalize().toString().equals(upsert.path())) {
          if (!laterAccepted.add(upsert.path()) && release030Retirement(context)) superseded.add(op);
        } else if ((release030Retirement(context) || upsert.sourceSha256() == null && legacy)
            && laterAccepted.contains(upsert.path())) {
          superseded.add(op);
        }
      } else if ("DELETE".equalsIgnoreCase(op.op())) {
        var deletion = SwitchBufferDelete.decode(op.payload());
        if (release030Retirement(context) && deletion.origin()==SwitchBufferDelete.Origin.EXPLICIT)
          laterAccepted.add(deletion.path());
        if (deletion.origin() == SwitchBufferDelete.Origin.LEGACY
            && laterAccepted.contains(deletion.path())) superseded.add(op);
      }
    }
    return superseded;
  }

  /** Only a surviving exact native file effect may replace its own terminal policy skip. */
  public static boolean readmitNativePolicySkippedAdmissions(DrainSwitchBufferContext context)
      throws IOException {
    if (context.nativePolicy() == null
        || !(context.jobQueue() instanceof SwitchBufferCapableQueue queue)) return false;
    var journal = strictReplayOps(queue, context.replayGeneration());
    var effects = replayAcceptedEffects(context, journal);
    var survivors = acceptedSurvivors(effects);
    List<SwitchBufferCapableQueue.SwitchBufferOp> files = new ArrayList<>();
    for (var effect : survivors) {
      if (effect.file() == null || !context.replayGeneration().equals(effect.op().generation())) continue;
      boolean rootOwnsLater = journal.stream().anyMatch(op -> {
        if (!WatcherRootReconciliation.OPERATION.equals(op.op())
            || op.acceptedOrder() <= effect.op().acceptedOrder()) return false;
        var debt = WatcherRootReconciliation.decode(op.payload());
        return Path.of(effect.path()).startsWith(Path.of(debt.root()))
            && Objects.equals(normalizeReplayCollection(effect.collection()), normalizeReplayCollection(debt.collection()));
      });
      if (!rootOwnsLater) files.add(effect.op());
    }
    return context.nativePolicy().readmit(journal, files);
  }

  private static String normalizeReplayCollection(String collection) {
    return collection == null || collection.isBlank()
        || IngestCollectionPolicy.DEFAULT_COLLECTION.equals(collection)
        ? null : collection;
  }

  private static boolean enqueueBufferedUpserts(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> upserts) {
    boolean complete = true;
    int enqueued = 0;
    for (var op : upserts) {
      try {
        if (release030Retirement(context)) continue; // Current-source proof owns stable admissions.
        var upsert = SwitchBufferUpsert.decode(op.payload());
        if (upsert.sourceSha256() != null) {
          // A scoped file admission already owns a durable queue row and an exact source
          // witness. Re-enqueueing would mint a new revision and read later filesystem bytes.
          // The caller waits for that row and verifies Green's indexed source witness.
          continue;
        }
        // Keep an admitted unfinished row before inspecting its target: it can finish while
        // replay reads the index, and must not become a fresh admission in that interval.
        if ((op.generation() == null || op.generation().isBlank())
            && (context.replayGeneration() == null
                || !IndexGenerationManager.isRecordedGenerationIdentity(context.replayGeneration()))
            && context.jobQueue() instanceof SqliteJobQueue original
            && original.hasRelease030ReplayAdmission(Path.of(upsert.path()))) continue;
        var fields = context.ingestLifecycle() == null ? null : context.ingestLifecycle().documentFieldOps();
        if (fields != null && context.jobQueue() instanceof SwitchBufferCapableQueue receipts) {
          String file = io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(Path.of(upsert.path()));
          String indexed = fields.getDocumentFieldOrThrow(file, SchemaFields.SOURCE_SHA256);
          // An applied receipt (original or later admission) settles this historical effect.
          // DONE alone is insufficient: Green must hold the same file, collection and bytes.
          var work = receipts.sourceFileWork(file, indexed);
          if (work == SwitchBufferCapableQueue.SourceFileWork.POLICY
              && context.ingestLifecycle().indexCountOps().countByIdAndChunksStrict(file) == 0) continue;
          String collection = upsert.collection() == null || upsert.collection().isBlank() ? null : upsert.collection();
          String indexedCollection = fields.getDocumentFieldOrThrow(file, SchemaFields.COLLECTION);
          boolean sameCollection = Objects.equals(collection, indexedCollection);
          boolean settled = work == SwitchBufferCapableQueue.SourceFileWork.COMMITTED;
          if (context.jobQueue() instanceof SqliteJobQueue sqlite) {
            settled = sqlite.matchesAppliedFileProjection(file, indexed,
                fields.getDocumentFieldOrThrow(file, SchemaFields.MODIFIED_AT), indexedCollection);
            // A raw 0.3.0 path carries no collection. Preserve the completed admission's
            // collection, proved against Green above, rather than inventing a new one.
            sameCollection = !op.payload().stripLeading().startsWith("{") || sameCollection;
          }
          if (settled && file.equals(fields.getDocumentFieldOrThrow(file, SchemaFields.DOC_ID))
              && file.equals(fields.getDocumentFieldOrThrow(file, SchemaFields.PATH)) && sameCollection) continue;
        }
        int accepted = context.jobQueue().enqueueEntries(List.of(upsert.entry()), upsert.collection());
        enqueued += accepted;
        if (accepted != 1) complete = false;
      } catch (IOException | java.sql.SQLException | RuntimeException unavailable) {
        complete = false;
        context.log().warn("Buffered UPSERT enqueue failed; retaining replay versions", unavailable);
      }
    }
    context.log().info("Enqueued {} buffered UPSERT ops back into the job queue", enqueued);
    return complete;
  }

  private static boolean verifyBufferedUpserts(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> upserts,
      List<SwitchBufferCapableQueue.SwitchBufferOp> orderedOps, ReplayDeleteOrder deleteOrder,
      boolean finalEffects, boolean refusedSourceRecovery,
      Map<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> reconciliations,
      Map<SwitchBufferCapableQueue.SwitchBufferOp,List<String>> fileSurvivorExclusions) {
    if (upserts.isEmpty()) return true;
    if (context.ingestLifecycle() == null
        || !(context.jobQueue() instanceof SwitchBufferCapableQueue queue)) return false;
    try {
      context.ingestLifecycle().commitOps().maybeRefreshBlocking();
    } catch (RuntimeException unavailable) {
      context.log().warn("Buffered UPSERT reader could not refresh", unavailable);
      if (context.publisher() != null) context.publisher().watcherReplayRefusal(
          "Buffered UPSERT reader could not refresh: " + unavailable.getMessage());
      return false;
    }
    var sourceObservations = new ArrayList<SwitchBufferCapableQueue.RetirementSourceObservation>();
    int awaited = 0;
    String firstAwaited = null;
    try {
      var superseded = supersededLegacyFiles(context, orderedOps);
      for (var op : upserts) {
        if (superseded.contains(op)) continue;
        boolean retirement = release030Retirement(context);
        var upsert = retirement ? resolveReleaseUpsert(context,op) : SwitchBufferUpsert.decode(op.payload());
        if (upsert.sourceSha256() == null && !retirement) continue;
        if (retirement) {
          Boolean policyOutcome = retirementPolicyOutcome(context, Path.of(upsert.path()), upsert.ingestionRoot());
          if (policyOutcome != null) {
            if (!policyOutcome && awaited++ == 0) firstAwaited = upsert.path();
            continue;
          }
        }
        boolean scoped = op.generation() != null && !op.generation().isBlank()
            && op.generation().equals(context.replayGeneration());
        // A declared forced ROOT can establish the namespace of its ordered deletion
        // without inventing the provenance of a member already removed from Q.
        var deletionOwner = retirement && upsert.unitRevision() == null
            ? declaredReleaseDeletionOwner(context, orderedOps, upsert.path(), op.acceptedOrder()) : null;
        if (deletionOwner != null) {
          String namespace = queue.retirementRootCollection(deletionOwner);
          int ownerPosition = deleteOrder.positions().get(deletionOwner);
          var namespaceDeletion=lastMatchingReleaseDelete(context,orderedOps,deleteOrder,ownerPosition,upsert.path(),namespace);
          if (namespaceDeletion!=null && "DELETE_COLLECTION".equals(namespaceDeletion.op())
              && hasLaterAcceptedDelete(context,deleteOrder,ownerPosition,upsert.path(),upsert.path(),
                  namespace,context.replayGeneration())) {
            var namespaceOnly = new SwitchBufferUpsert(upsert.path(),namespace,null,null,null,null);
            if (finalEffects && !verifyDeletedReleaseFile(context,orderedOps,deleteOrder,ownerPosition,
                namespaceOnly,reconciliations,fileSurvivorExclusions)) return false;
            continue;
          }
        }
        var laterSync = latestConfirmedReleaseSync(deleteOrder, upsert.path(),
            deleteOrder.positions().get(op));
        if (laterSync != null && upsert.unitRevision() == null) {
          Path root = Path.of(io.justsearch.indexerworker.queue.SwitchBufferSyncRoot
              .decode(laterSync.payload()).rootPath()).toAbsolutePath().normalize();
          String collection = ((SqliteJobQueue)context.jobQueue()).retirementRootCollection(laterSync);
          if (collection == null) collection = releaseRootScope(queue,laterSync,upsert.path(),deleteOrder.rootFiles()).collection();
          upsert = new SwitchBufferUpsert(upsert.path(), collection,
              upsert.provenance(), upsert.unitRevision(), upsert.sourceSha256(), root);
        }
        var rootReplay = laterWatcherReplay(upsert.path(), upsert.collection(),
            deleteOrder.positions().get(op), deleteOrder, reconciliations);
        if (rootReplay != null) {
          WatcherReplay reconciled = rootReplay.getValue();
          int reconciliationPosition = deleteOrder.positions().get(rootReplay.getKey());
          if (finalEffects) {
            if (hasLaterAcceptedDelete(context, deleteOrder, reconciliationPosition, upsert.path(),
                upsert.path(), upsert.collection(), op.generation())) {
              if (!verifyDeletedReleaseFile(context, orderedOps, deleteOrder, reconciliationPosition, upsert, reconciliations, fileSurvivorExclusions)) return false;
            } else if (!reconciled.verifies(upsert.path(), upsert.collection())) return false;
          }
          continue; // Earlier UPSERT evidence waits for the root receipt's committed path outcomes.
        }
        if ((scoped || retirement) && hasLaterAcceptedDelete(context, deleteOrder, deleteOrder.positions().get(op),
            upsert.path(), upsert.path(), upsert.collection(), retirement ? context.replayGeneration() : op.generation())) {
          // Live broad deletion may already have removed this exact admission on B. Await
          // the queue before crossing the boundary, then prove final absence after all writes.
          if (finalEffects && !verifyDeletedReleaseFile(context, orderedOps, deleteOrder,
              deleteOrder.positions().get(op), upsert, reconciliations, fileSurvivorExclusions)) {
            context.log().warn("Superseded file still has an indexed image: path={} expectedCollection={} indexedCollection={} laterSync={}",
                upsert.path(),upsert.collection(),context.ingestLifecycle().documentFieldOps().getDocumentFieldOrThrow(upsert.path(),SchemaFields.COLLECTION),laterSync);
            return false;
          }
          continue;
        }
        String indexed = context.ingestLifecycle().documentFieldOps()
            .getDocumentFieldOrThrow(upsert.path(), SchemaFields.SOURCE_SHA256);
        if (refusedSourceRecovery) {
          // Judge every admission, so each one A lacks gets its A job in this attempt.
          var owner = upsert.unitRevision() == null
              ? applicableReleaseRoot(context,deleteOrder.rootIntents(),upsert.path(),op.acceptedOrder(),deleteOrder.rootFiles()) : null;
          if (owner != null) upsert = releaseRootScope(queue,owner,upsert.path(),deleteOrder.rootFiles());
          else owner = op;
          boolean settled = release030Retirement(context)
              ? retirementSourceHoldsAdmission(context,queue,owner,upsert,indexed,sourceObservations)
              : sourceHoldsAdmission(context,queue,owner,upsert,indexed);
          if (!settled) {
            if (awaited++ == 0) firstAwaited = upsert.path();
          }
          continue;
        }
        boolean settled = queue.matchesAcceptedFileProjection(
            upsert.path(), upsert.unitRevision(), upsert.sourceSha256(), indexed);
        if (!settled && context.nativePolicy() != null) settled = context.nativePolicy().certify(op);
        if (!settled) {
          context.log().warn("Buffered UPSERT lacks its accepted target projection: {}", upsert.path());
          if (context.publisher() != null) context.publisher().watcherReplayRefusal(
              "Buffered UPSERT lacks its accepted target projection: " + upsert.path());
          return false;
        }
      }
      if (!sourceObservations.isEmpty()) {
        var results = queue.correctRetirementSources(sourceObservations,()->Thread.currentThread().isInterrupted());
        for (int i = 0; i < results.size(); i++)
          if (applyRetirementSourceResult(context,sourceObservations.get(i),results.get(i))) awaited--;
      }
      if (awaited != 0) {
        context.log().warn("Refused candidate awaits A's effect on {} accepted file(s), first: {}",
            awaited, firstAwaited);
        return false;
      }
      return !finalEffects || certifyReplayFileDeleteScopes(context, orderedOps, deleteOrder, reconciliations);
    } catch (IOException | RuntimeException unavailable) {
      context.log().warn("Buffered UPSERT projection evidence is unreadable", unavailable);
      if (context.publisher() != null) context.publisher().watcherReplayRefusal(
          "Buffered UPSERT projection evidence is unreadable: " + unavailable.getMessage());
      return false;
    }
  }

  /** {@link #currentSource} of a path that is not on disk. */
  private static final String SOURCE_GONE = "gone";

  /** The file's SHA-256 now, {@link #SOURCE_GONE} when it is absent, or null when unreadable. */
  private static String currentSource(String path) {
    try {
      return io.justsearch.indexerworker.loop.SourceContentHash.sha256(Path.of(path));
    } catch (NoSuchFileException absent) {
      return SOURCE_GONE;
    } catch (IOException | RuntimeException unreadable) {
      return null;
    }
  }

  /**
   * Refusal cleanup only: whether A's committed effect settles an accepted admission, judged
   * from A's index against the path as {@link #observeForCleanup} confirms it now, never from a
   * queue row about earlier bytes. A regular file settles when A holds its current bytes, or when
   * the cleanup's own admission of this file state ended by A's normal policy (excluded, too
   * large). Unreadable bytes settle nothing. A path confirmed to be no longer a regular file
   * (gone, a folder, a link) settles once A no longer holds it: A's normal policy retires such a
   * source's text, and its retirement reaches A while a candidate exists. An uncertain path
   * settles nothing and admits nothing. A pending or claimed A job only waits.
   *
   * <p>Otherwise the Engine may have retired the admission's row unwritten, so nothing would ever
   * bring A to the path: queue one ordinary A job for it, once per file state. The job's unit
   * revision names the state, so the queue row itself records the admission and a restart keeps
   * the same job and its retry deadline. A path that changes may admit it again; the same state
   * is not admitted twice while its row stands. A failed row (terminal, or retrying after a
   * back-off) is the outcome for the state its writer ran on, so it never holds back the
   * admission of a state this cleanup has not admitted.
   */
  private static boolean sourceHoldsAdmission(DrainSwitchBufferContext context,
      SwitchBufferCapableQueue queue, SwitchBufferCapableQueue.SwitchBufferOp owner,
      SwitchBufferUpsert upsert, String indexed) throws IOException {
    if (release030Retirement(context)) return retirementSourceHoldsAdmission(context,queue,owner,upsert,indexed);
    CleanupPathState now = observeForCleanup(context.publisher(), Path.of(upsert.path()),
        upsert.ingestionRoot());
    switch (now.kind()) {
      case UNCERTAIN -> {
        return false; // Fail-closed: Green and the journal stay until the path can be judged.
      }
      case ABSENT, NON_REGULAR -> {
        if (indexed == null
            && context.ingestLifecycle().indexCountOps().countByIdAndChunksStrict(upsert.path()) == 0) {
          return true; // A shows the retirement.
        }
      }
      case REGULAR -> {
        if (now.sha256() != null && now.sha256().equals(indexed)) return true;
      }
    }
    String admission = cleanupAdmission(context.replayGeneration(), upsert.path(), now.fileState());
    var work = queue.sourceFileWork(upsert.path(), indexed);
    boolean admittedNow = queue.holdsRefusalCleanupAdmission(upsert.path(), admission);
    // The policy outcome must be the writer's answer to this file state, which only an
    // admission of that state that it finished proves.
    if (now.kind() == CleanupPathKind.REGULAR && now.sha256() != null
        && work == SwitchBufferCapableQueue.SourceFileWork.POLICY && admittedNow) {
      return true;
    }
    if (work == SwitchBufferCapableQueue.SourceFileWork.OUTSTANDING || admittedNow) return false;
    Path file = Path.of(upsert.path());
    var entry = new JobQueue.EnqueueEntry(file, JobQueue.EnqueueEntry.stat(file).sizeBytes(),
        upsert.provenance(), null, upsert.ingestionRoot());
    if (queue.enqueueRefusalCleanupAdmission(entry, upsert.collection(), admission) == 1) {
      context.log().info("Refused candidate queued its unapplied admission on the source: {}",
          upsert.path());
    }
    return false;
  }

  private static void logExcludedRetirementWait(DrainSwitchBufferContext context) {
    if (context.excludedRetirementLogged().compareAndSet(false, true))
      context.log().warn(WorkerIngestService.RETIREMENT_EXCLUDED_INDEXED);
  }

  /** Null continues ordinary replay; excluded absence settles, while unknown policy keeps B. */
  private static Boolean retirementPolicyOutcome(DrainSwitchBufferContext context, Path file, Path root)
      throws IOException {
    if (!release030Retirement(context)) return null;
    if (context.publisher() == null) return false;
    try {
      if (!context.publisher().isUserExcludedForRetirement(file, root)) return null;
    } catch (RuntimeException unreadablePolicy) {
      return false;
    }
    String key = io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file);
    boolean absent = context.ingestLifecycle().indexCountOps().countByIdAndChunksStrict(key) == 0;
    if (!absent) logExcludedRetirementWait(context);
    return absent;
  }

  private static boolean retirementSourceHoldsAdmission(DrainSwitchBufferContext context,
      SwitchBufferCapableQueue queue, SwitchBufferCapableQueue.SwitchBufferOp owner,
      SwitchBufferUpsert scope, String indexed) throws IOException {
    return retirementSourceHoldsAdmission(context,queue,owner,scope,indexed,null);
  }

  private static boolean retirementSourceHoldsAdmission(DrainSwitchBufferContext context,
      SwitchBufferCapableQueue queue, SwitchBufferCapableQueue.SwitchBufferOp owner,
      SwitchBufferUpsert scope, String indexed,
      List<SwitchBufferCapableQueue.RetirementSourceObservation> observations) throws IOException {
    var sqlite = (SqliteJobQueue)queue;
    Path file = Path.of(scope.path());
    Boolean policyOutcome = retirementPolicyOutcome(context, file, scope.ingestionRoot());
    if (policyOutcome != null) return policyOutcome;
    if (sqlite.hasUnownedModernAdmission(file)) return false;
    var observedRow = sqlite.watcherReconciliationRow(scope.path());
    if (Thread.currentThread().isInterrupted()) throw new IOException("Retirement source proof cancelled");
    var source = observeForCleanup(context.publisher(),file,scope.ingestionRoot());
    if (Thread.currentThread().isInterrupted()) throw new IOException("Retirement source proof cancelled");
    if (source.kind() == CleanupPathKind.UNCERTAIN
        || source.kind() == CleanupPathKind.REGULAR && source.sha256() == null) return false;
    // Modern exact receipts keep the real accepted source witness. Automatic repair cannot
    // renew their acceptance or reinterpret a worker's policy outcome.
    if (scope.unitRevision() != null) {
      if (source.kind() != CleanupPathKind.REGULAR || !scope.sourceSha256().equals(source.sha256())) return false;
      if (observedRow == null || !scope.unitRevision().equals(observedRow.get("unit_revision"))) {
        sqlite.recoverRetirementExact(scope);
        return false;
      }
      return queue.matchesAcceptedFileProjection(scope.path(),scope.unitRevision(),scope.sourceSha256(),indexed)
          && releaseCollection(scope.collection()).equals(releaseCollection(context.ingestLifecycle()
              .documentFieldOps().getDocumentFieldOrThrow(scope.path(),SchemaFields.COLLECTION)));
    }
    boolean targetMatches;boolean indexedParentMatches=false;
    if (source.kind() == CleanupPathKind.REGULAR) {
      var fields = context.ingestLifecycle().documentFieldOps();
      String namespace=fields.getDocumentFieldOrThrow(scope.path(),SchemaFields.COLLECTION);
      boolean knownNamespace=scope.collection()!=null
          || owner!=null && "SYNC_ROOT".equals(owner.op())
          || owner==null && observedRow!=null && scope.path().equals(observedRow.get("ingestion_root"));
      indexedParentMatches = knownNamespace
          && scope.path().equals(fields.getDocumentFieldOrThrow(scope.path(),SchemaFields.DOC_ID))
          && scope.path().equals(fields.getDocumentFieldOrThrow(scope.path(),SchemaFields.PATH))
          && releaseCollection(scope.collection()).equals(releaseCollection(
              namespace));
      targetMatches=source.sha256().equals(indexed) && indexedParentMatches;
    } else targetMatches = indexed == null
        && context.ingestLifecycle().indexCountOps().countByIdAndChunksStrict(scope.path()) == 0;
    var observation = new SwitchBufferCapableQueue.RetirementSourceObservation(
        owner,scope,observedRow,source.fileState(),source.sha256(),JobQueue.EnqueueEntry.stat(file).sizeBytes(),targetMatches,indexedParentMatches);
    if (observations != null) {
      observations.add(observation);
      return false; // The caller batches the already observed transitions under one Q reservation.
    }
    int result = sqlite.correctRetirementSources(List.of(observation),
        () -> Thread.currentThread().isInterrupted()).getFirst();
    return applyRetirementSourceResult(context,observation,result);
  }

  private static boolean applyRetirementSourceResult(DrainSwitchBufferContext context,
      SwitchBufferCapableQueue.RetirementSourceObservation observation,int result) throws IOException {
    Boolean policyOutcome = retirementPolicyOutcome(context, Path.of(observation.scope().path()),
        observation.scope().ingestionRoot());
    if (policyOutcome != null) return policyOutcome;
    if (result == 3) {
      if (Thread.currentThread().isInterrupted()) throw new IOException("Retirement removal cancelled");
      var scope = observation.scope();
      var current = observeForCleanup(context.publisher(),Path.of(scope.path()),scope.ingestionRoot());
      if (Thread.currentThread().isInterrupted()) throw new IOException("Retirement removal cancelled");
      if (current.kind() != CleanupPathKind.UNCERTAIN && current.kind() != CleanupPathKind.REGULAR
          && observation.sourceState().equals(current.fileState()))
        context.ingestLifecycle().indexingCoordinator().deleteByIdAndChunks(scope.path());
      // Retirement replay commits A even with an empty journal. Only the next committed,
      // freshly observed absence can settle this obligation.
    }
    return result == 2;
  }

  /** Released rows use both a null tag and the explicit default tag for the same collection. */
  private static String releaseCollection(String collection) {
    return collection == null || collection.isBlank() ? IngestCollectionPolicy.DEFAULT_COLLECTION : collection;
  }

  /** The unit revision refusal cleanup gives its admission of one file state of a path. */
  static String cleanupAdmission(String generation, String path, String fileState) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
          (generation + '\u0000' + path + '\u0000' + fileState)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest, 0, 16);
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  /** What refusal cleanup may say about an accepted path now. */
  enum CleanupPathKind {
    /** A regular file; its bytes when they could be read. */
    REGULAR,
    /** Something other than a regular file: a folder, a link or a special file. */
    NON_REGULAR,
    /** Confirmed absent. */
    ABSENT,
    /** Nothing can be confirmed: settle nothing, admit nothing. */
    UNCERTAIN
  }

  /**
   * A path's state for refusal cleanup: its kind, a string naming the state (kind, size, modified
   * time, file key and bytes), and the SHA-256 of a regular file's bytes, null when unreadable.
   */
  record CleanupPathState(CleanupPathKind kind, String fileState, String sha256) {
    static final CleanupPathState UNCERTAIN =
        new CleanupPathState(CleanupPathKind.UNCERTAIN, null, null);
  }

  /**
   * The one answer refusal cleanup acts on about an accepted path's current state; only a
   * confirmed answer can settle, acknowledge or let Green go. An answer is confirmed only while
   * the folder that answers for the path (its watched root, or an explicit file's own folder)
   * vouches through the live Worker's root authority: present, listable, and the same folder on
   * its recorded volume. Every folder from it down to the path must also be listable, and the
   * path must look the same before and after those checks. Anything else, including no root
   * authority at all, is {@link CleanupPathKind#UNCERTAIN}.
   */
  static CleanupPathState observeForCleanup(WorkerIngestService publisher, Path path, Path root) {
    if (publisher == null || path == null) return CleanupPathState.UNCERTAIN;
    try {
      if (publisher.isUserExcluded(path, root)) return CleanupPathState.UNCERTAIN;
    } catch (RuntimeException unreadablePolicy) {
      return CleanupPathState.UNCERTAIN;
    }
    var rootCheck = publisher.rootCheck();
    if (rootCheck == null) return CleanupPathState.UNCERTAIN;
    Path file = path.toAbsolutePath().normalize();
    String before = observedKind(file);
    if (before == null) return CleanupPathState.UNCERTAIN;
    boolean asRoot = io.justsearch.indexerworker.loop.SourcePresence.answersAsRoot(file, root);
    Path folder = asRoot ? root.toAbsolutePath().normalize() : file.getParent();
    boolean legacyExact = file.equals(root) && before.startsWith("file/")
        && rootCheck.isRelease030ExactFile(file);
    if (folder == null
        || (!(asRoot ? rootCheck.vouches(folder) : rootCheck.vouchesForFile(folder)) && !legacyExact)) {
      return CleanupPathState.UNCERTAIN;
    }
    for (Path dir = file.getParent(); dir != null && !dir.equals(folder) && dir.startsWith(folder);
        dir = dir.getParent()) {
      var presence = io.justsearch.adapters.lucene.runtime.PathPresence.probe(dir);
      if (presence == io.justsearch.adapters.lucene.runtime.PathPresence.UNKNOWN
          || (presence == io.justsearch.adapters.lucene.runtime.PathPresence.PRESENT
              && !listableFolder(dir))) {
        return CleanupPathState.UNCERTAIN;
      }
    }
    if (SOURCE_GONE.equals(before)) {
      // Not found, re-probed beneath its vouching folder: the deletion authority's own answer.
      return io.justsearch.indexerworker.loop.SourcePresence.classify(file, root, rootCheck)
              == io.justsearch.indexerworker.loop.SourcePresence.Verdict.ABSENT
          ? new CleanupPathState(CleanupPathKind.ABSENT, SOURCE_GONE, null)
          : CleanupPathState.UNCERTAIN;
    }
    if (!before.startsWith("file/")) {
      return before.equals(observedKind(file))
          ? new CleanupPathState(CleanupPathKind.NON_REGULAR, before, null)
          : CleanupPathState.UNCERTAIN;
    }
    String sha = currentSource(file.toString());
    if (SOURCE_GONE.equals(sha) || !before.equals(observedKind(file))) {
      return CleanupPathState.UNCERTAIN; // It changed while it was being read.
    }
    return new CleanupPathState(CleanupPathKind.REGULAR,
        before + '/' + (sha == null ? "unreadable" : sha), sha);
  }

  /**
   * Whether a conditional (watcher or legacy) DELETE's path is {@link CleanupPathKind#UNCERTAIN}
   * to {@link #observeForCleanup} while the folder that answers for it, the root its replay judges
   * it under, still vouches through {@code authority}. Every replay route then keeps the receipt
   * and touches nothing. A withdrawn or uncertain root is not this case: its replay keeps its own
   * handling. The path can still change between this check and the replay; a filesystem is not
   * fenced.
   */
  private static boolean uncertainBeneathItsRoot(WorkerIngestService authority,
      SwitchBufferDelete deletion) {
    if (authority == null || authority.rootCheck() == null) return false;
    Path file = Path.of(deletion.path()).toAbsolutePath().normalize();
    Path root = authority.bufferedDeleteRoot(deletion);
    if (observeForCleanup(authority, file, root).kind() != CleanupPathKind.UNCERTAIN) return false;
    boolean asRoot = io.justsearch.indexerworker.loop.SourcePresence.answersAsRoot(file, root);
    Path folder = asRoot ? root.toAbsolutePath().normalize() : file.getParent();
    return folder != null && (asRoot ? authority.rootCheck().vouches(folder)
        : authority.rootCheck().vouchesForFile(folder));
  }

  /**
   * The path's kind, size, modified time and file key without following links: {@link
   * #SOURCE_GONE} when not found, null when it cannot be observed.
   */
  private static String observedKind(Path file) {
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException absent) {
      return SOURCE_GONE;
    } catch (IOException | RuntimeException unknown) {
      return null;
    }
    String kind = attributes.isRegularFile() ? "file" : attributes.isSymbolicLink() ? "link"
        : attributes.isDirectory() ? "directory" : "other";
    return kind + '/' + attributes.size() + '/' + attributes.lastModifiedTime().toMillis() + '/'
        + attributes.fileKey();
  }

  /** A folder on the way to the path whose entries can be listed (it is traversed as the OS does). */
  private static boolean listableFolder(Path dir) {
    if (!Files.isDirectory(dir)) return false;
    try (var entries = Files.newDirectoryStream(dir)) {
      // Reading the first entry is what proves the folder can be listed; empty is fine.
      boolean firstEntryRead = entries.iterator().hasNext();
      return firstEntryRead || Files.isDirectory(dir);
    } catch (IOException | RuntimeException unreadable) {
      return false;
    }
  }

  /** A broad receipt used to supersede a file witness must prove its complete final scope. */
  private static boolean certifyReplayFileDeleteScopes(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> orderedOps, ReplayDeleteOrder deleteOrder,
      Map<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> reconciliations) throws IOException {
    String generation = context.replayGeneration();
    if (generation == null || generation.isBlank()) return true;
    Set<SwitchBufferCapableQueue.SwitchBufferOp> authorizingDeletes =
        fileRelatedBroadDeletes(context, orderedOps, deleteOrder);
    if (authorizingDeletes.isEmpty()) return true;
    if (deleteOrder.releaseRecovery() != null) {
      // These exact deletes were committed and verified at their ordered positions. Later
      // confirmed walks may restore files, whose current source/writer proofs are checked above.
      return authorizingDeletes.stream().allMatch(deleteOrder.releaseRecovery().checkpoint()::deletionQueueComplete);
    }
    List<CommittedNativeEffect> effects = replayAcceptedEffects(context, orderedOps);
    var active = context.ingestLifecycle();
    active.commitOps().maybeRefreshBlocking();
    List<Integer> deleteIndexes = new ArrayList<>();
    for (int index = 0; index < effects.size(); index++) {
      var observed = observeCommittedProjection(active, effects.get(index));
      effects.set(index, observed);
      if ("DELETE_PREFIX".equals(observed.op().op())
          || "DELETE_COLLECTION".equals(observed.op().op())
          || (observed.id() != null && !observed.upsert())) deleteIndexes.add(index);
    }
    Set<CommittedNativeEffect> survivors = acceptedSurvivors(effects);
    if (context.nativePolicy() != null) survivors.removeIf(effect -> effect.file() != null
        && context.nativePolicy().certify(effect.op()));
    // A later root receipt supersedes only the old file effects it freshly proved per path.
    survivors.removeIf(effect -> effect.file() != null && laterWatcherReplay(effect.path(),
        effect.collection(), deleteOrder.positions().get(effect.op()), deleteOrder, reconciliations) != null);
    if (!(context.jobQueue() instanceof SwitchBufferCapableQueue queue)) return false;
    for (int index = 0; index < effects.size(); index++) {
      var deletion = effects.get(index);
      if (!authorizingDeletes.contains(deletion.op())) continue;
      for (int next = index + 1; next < effects.size(); next++) {
        var survivor = effects.get(next);
        if (survivors.contains(survivor) && nativeDeleteMatches(deletion, survivor)
            && !certifyCommittedSurvivor(queue, active, effects, deleteIndexes, next, context.nativePolicy())) return false;
      }
      List<String> reconciledFiles = reconciledSurvivorsAfterDelete(
          context, orderedOps, deleteOrder, deletion.op(), reconciliations);
      if (!certifyCommittedDeleteScope(context.jobQueue(), active, effects, survivors, index,
          reconciledFiles)) return false;
      if (!certifyReplayPrefixQueue(context.jobQueue(), queue, active, effects, survivors,
          index, reconciledFiles, context.nativePolicy())) return false;
    }
    return true;
  }

  /** Terminal policy allowances never enter the independent index-survivor lists. */
  private static boolean certifyReplayPrefixQueue(JobQueue jobQueue, SwitchBufferCapableQueue queue,
      RunningRuntime active, List<CommittedNativeEffect> effects, Set<CommittedNativeEffect> survivors,
      int index, List<String> reconciledFiles,
      io.justsearch.indexerworker.services.NativePolicyReplay policy) throws IOException {
    var deletion = effects.get(index);
    if (!"DELETE_PREFIX".equals(deletion.op().op())) return true;
    List<String> certifiedPaths = new ArrayList<>(reconciledFiles);
    if (policy != null) certifiedPaths.addAll(policy.omittedPaths());
    for (int next = index + 1; next < effects.size(); next++) {
      var survivor = effects.get(next);
      if (survivor.file() != null && survivors.contains(survivor)
          && nativeDeleteMatches(deletion, survivor)) certifiedPaths.add(survivor.id());
    }
    // Captured settlement is acknowledged only after promotion. Its retained DONE row
    // may witness an earlier accepted file that this deletion has strictly removed.
    for (int previous = 0; previous < index; previous++) {
      var removed = effects.get(previous);
      if (removed.file() != null && nativeDeleteMatches(deletion, removed)
          && active.indexCountOps().countByIdAndChunksStrict(removed.id()) == 0
          && queue.matchesAcceptedCapturedFileDeletion(removed.id(),
              removed.file().unitRevision(), removed.file().sourceSha256())) {
        certifiedPaths.add(removed.id());
      }
    }
    var scope = PathPrefixScope.decode(deletion.op().payload());
    boolean unexpected = scope.excludedSubtrees().isEmpty()
        ? jobQueue.hasJobsByPathPrefixOutsideCertifiedReplayPaths(
            PathPrefixScope.isLegacy(deletion.op().payload()) ? deletion.op().payload() : scope.prefix(), certifiedPaths)
        : jobQueue.hasJobsByPathPrefixOutsideCertifiedReplayPaths(
            scope.prefix(), certifiedPaths, scope.excludedSubtrees());
    return !unexpected;
  }

  private static Map.Entry<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> laterWatcherReplay(
      String path, String collection, int earlierPosition, ReplayDeleteOrder order,
      Map<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> reconciliations) {
    Map.Entry<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> selected = null;
    for (var root : reconciliations.entrySet()) {
      if (order.positions().get(root.getKey()) > earlierPosition
          && root.getValue().covers(path, collection)) selected = root;
    }
    return selected;
  }

  private static List<String> reconciledSurvivorsAfterDelete(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> ops, ReplayDeleteOrder order,
      SwitchBufferCapableQueue.SwitchBufferOp deletion,
      Map<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> reconciliations) throws IOException {
    List<String> paths = new ArrayList<>();
    for (var root : reconciliations.entrySet()) {
      int rootPosition = order.positions().get(root.getKey());
      if (rootPosition <= order.positions().get(deletion)) continue;
      String collection = WatcherRootReconciliation.decode(root.getKey().payload()).collection();
      for (String path : root.getValue().presentPaths()) {
        boolean matches = "DELETE_PREFIX".equals(deletion.op())
            ? PathPrefixScope.decode(deletion.payload()).contains(path)
            : deletion.payload().equals(collection);
        if (!matches || hasLaterAcceptedDelete(context, order, rootPosition,
            path, path, collection, context.replayGeneration())) continue;
        boolean laterFile = ops.stream().anyMatch(op -> "UPSERT".equals(op.op())
            && order.positions().get(op) > rootPosition && ("path:" + path).equals(op.key()));
        if (laterFile) continue; // That admission must supply its own exact projection proof.
        if (!root.getValue().verifies(path, collection)) {
          throw new IOException("Later watcher outcome cannot certify a broad delete survivor: " + path);
        }
        paths.add(path);
      }
    }
    return paths;
  }

  private static List<CommittedNativeEffect> replayAcceptedEffects(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> orderedOps) {
    var superseded = supersededLegacyFiles(context,orderedOps);
    var supersededProjection = supersededRetirementProjections(context,orderedOps);
    var scopedOps = orderedOps.stream()
        .filter(op -> !superseded.contains(op) && !supersededProjection.contains(op))
        .filter(op -> context.replayGeneration().equals(op.generation())
            && !context.approvedGapVersions().contains(op)).toList();
    // Pre-pointer source ownership comes from validated ready markers in this exact snapshot.
    Set<String> sources = new LinkedHashSet<>();
    for (var op : scopedOps) {
      if ("PROJECTION_SOURCE".equals(op.op())
          && context.projectionSourceReady().test(op.payload())) sources.add(op.payload());
    }
    List<CommittedNativeEffect> effects = new ArrayList<>();
    for (var op : scopedOps) {
      switch (op.op()) {
        case "UPSERT", "DELETE", "DELETE_PREFIX", "DELETE_COLLECTION", "PROJECTION_SOURCE", "PROJECTION" ->
            effects.add(decodeCommittedNativeEffect(op, sources));
        default -> { } // Other mutations never supply a scope exception.
      }
    }
    return effects;
  }

  private static Map<SwitchBufferCapableQueue.SwitchBufferOp, List<String>> replayFileSurvivorExclusions(
      DrainSwitchBufferContext context, List<SwitchBufferCapableQueue.SwitchBufferOp> orderedOps,
      Set<SwitchBufferCapableQueue.SwitchBufferOp> authorizingDeletes, ReplayDeleteOrder deleteOrder,
      Map<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> reconciliations,
      Set<SwitchBufferCapableQueue.SwitchBufferOp> deferredRetirementDeletes) throws IOException {
    if (authorizingDeletes.isEmpty()) return Map.of();
    if (context.ingestLifecycle() == null
        || !(context.jobQueue() instanceof SwitchBufferCapableQueue queue)) {
      throw new IOException("Accepted survivor owner is unavailable");
    }
    var active = context.ingestLifecycle();
    active.commitOps().maybeRefreshBlocking();
    var effects = replayAcceptedEffects(context, orderedOps);
    for (int index = 0; index < effects.size(); index++) {
      effects.set(index, observeCommittedProjection(active, effects.get(index)));
    }
    var survivors = acceptedSurvivors(effects);
    Map<SwitchBufferCapableQueue.SwitchBufferOp, List<String>> exclusions = new HashMap<>();
    for (int index = 0; index < effects.size(); index++) {
      var deletion = effects.get(index);
      if (!authorizingDeletes.contains(deletion.op())) continue;
      List<String> files = new ArrayList<>();
      for (int next = 0; next < effects.size(); next++) {
        var effect = effects.get(next);
        if (!nativeDeleteMatches(deletion, effect)) continue;
        if (effect.newerProjection()) {
          throw new IOException("Broad replay cannot reconstruct a newer projection");
        }
        if (effect.projection() != null
            && effect.projection().kind() == AcceptedProjection.Kind.UPSERT
            && Long.toString(effect.projection().sourceRevision()).equals(active.documentFieldOps()
                .getDocumentFieldOrThrow(effect.id(), SchemaFields.PROJECTION_SOURCE_REVISION))
            && !certifyCommittedSurvivor(queue, active, effects, List.of(), next, context.nativePolicy())) {
          throw new IOException("Broad replay found an uncertified projection survivor");
        }
        if (next > index && effect.file() != null && survivors.contains(effect)) {
          if (laterWatcherReplay(effect.path(), effect.collection(),
                  deleteOrder.positions().get(effect.op()), deleteOrder, reconciliations) == null
              && !certifyCommittedSurvivor(queue, active, effects, List.of(), next, context.nativePolicy())) {
            if (deferredRetirementDeletes != null && effect.file().unitRevision() != null
                && Path.of(effect.path()).toAbsolutePath().normalize().toString().equals(effect.path())) {
              // An identifiable pending/uncertain survivor authorizes no exclusion or delete.
              // Hold this destructive scope; the ordered loop propagates only its overlaps.
              deferredRetirementDeletes.add(deletion.op());
              break;
            }
            throw new IOException("Later file survivor lacks its exact accepted certificate");
          }
          if (context.nativePolicy() == null || !context.nativePolicy().certify(effect.op())) {
            files.add(effect.id());
          }
        }
      }
      if (deferredRetirementDeletes == null || !deferredRetirementDeletes.contains(deletion.op())) {
        exclusions.put(deletion.op(), List.copyOf(files));
      }
    }
    return Map.copyOf(exclusions);
  }

  private static Set<SwitchBufferCapableQueue.SwitchBufferOp> fileRelatedBroadDeletes(
      DrainSwitchBufferContext context, List<SwitchBufferCapableQueue.SwitchBufferOp> orderedOps,
      ReplayDeleteOrder deleteOrder) {
    String generation = context.replayGeneration();
    if (generation == null || generation.isBlank()) return Set.of();
    if (deleteOrder.deletes().keySet().stream().noneMatch(op -> generation.equals(op.generation())
        && ("DELETE_PREFIX".equals(op.op()) || "DELETE_COLLECTION".equals(op.op())))) return Set.of();
    List<SwitchBufferCapableQueue.SwitchBufferOp> scopedOps = orderedOps.stream()
        .filter(op -> generation.equals(op.generation())
            && !context.approvedGapVersions().contains(op)).toList();
    Set<SwitchBufferCapableQueue.SwitchBufferOp> authorizingDeletes = new LinkedHashSet<>();
    for (int index = 0; index < scopedOps.size(); index++) {
      var fileOp = scopedOps.get(index);
      if (!"UPSERT".equals(fileOp.op())) continue;
      var file = decodeCommittedNativeEffect(fileOp, Set.of());
      int fileIndex = deleteOrder.positions().get(fileOp);
      boolean finalSurvivor = !hasLaterAcceptedDelete(context, deleteOrder, fileIndex,
          file.id(), file.path(), file.collection(), generation);
      for (var indexedDeletion : deleteOrder.deletes().entrySet()) {
        var deletion = indexedDeletion.getKey();
        if (!generation.equals(deletion.generation())) continue;
        if (!("DELETE_PREFIX".equals(deletion.op())
            || "DELETE_COLLECTION".equals(deletion.op()))) continue;
        if ((indexedDeletion.getValue() > fileIndex || finalSurvivor)
            && nativeDeleteMatches(decodeCommittedNativeEffect(deletion, Set.of()), file)) {
          authorizingDeletes.add(deletion);
        }
      }
    }
    return authorizingDeletes;
  }

  /** Candidate journal rows clear only after exact reader-visible projection or absence. */
  private static boolean verifyBufferedProjections(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> ops, ReplayDeleteOrder deleteOrder) {
    if (ops.stream().noneMatch(op -> "PROJECTION".equalsIgnoreCase(op.op())
        && !context.approvedGapVersions().contains(op))) return true;
    if (context.ingestLifecycle() == null) return false;
    try {
      context.ingestLifecycle().commitOps().maybeRefreshBlocking();
      var fields = context.ingestLifecycle().documentFieldOps();
      var superseded = supersededRetirementProjections(context,ops);
      for (int index = 0; index < ops.size(); index++) {
        var op = ops.get(index);
        if (!"PROJECTION".equalsIgnoreCase(op.op())) continue;
        if (context.approvedGapVersions().contains(op) || superseded.contains(op)) continue;
        var projection = AcceptedProjection.decode(op.payload());
        if (!projection.journalKey().equals(op.key())) return false;
        String currentId = fields.getDocumentFieldOrThrow(projection.indexId(), SchemaFields.DOC_ID);
        if (currentId == null) {
          if (projection.kind() == AcceptedProjection.Kind.UPSERT) {
            var mapped = ProjectionDocumentMapper.toIndexDocument(projection).fields();
            String path = projectedTerm(mapped.get(SchemaFields.PATH));
            String collection = projectedTerm(mapped.get(SchemaFields.COLLECTION));
            if (!hasLaterAcceptedDelete(context, deleteOrder, index, projection.indexId(), path, collection,
                op.generation())) {
              return false;
            }
          }
          if (context.ingestLifecycle().indexCountOps()
              .countByIdAndChunksStrict(projection.indexId()) != 0) return false;
          continue;
        }
        String currentSource = fields.getDocumentFieldOrThrow(
            projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID);
        String currentRevision = fields.getDocumentFieldOrThrow(
            projection.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION);
        String currentDigest = fields.getDocumentFieldOrThrow(
            projection.indexId(), SchemaFields.PROJECTION_DIGEST);
        if (!projection.indexId().equals(currentId) || !projection.sourceId().equals(currentSource)
            || currentRevision == null) return false;
        long observedRevision = Long.parseLong(currentRevision);
        if (observedRevision < 0 || !Long.toString(observedRevision).equals(currentRevision)
            || !JobQueue.IngestionLedgerTransition.isSha256(currentDigest)) return false;
        String path = fields.getDocumentFieldOrThrow(projection.indexId(), SchemaFields.PATH);
        String collection = fields.getDocumentFieldOrThrow(projection.indexId(), SchemaFields.COLLECTION);
        if (hasLaterAcceptedDelete(context, deleteOrder, index, projection.indexId(), path, collection,
            op.generation())) return false;
        if (observedRevision > projection.sourceRevision()) continue;
        if (projection.kind() == AcceptedProjection.Kind.DELETE
            || observedRevision != projection.sourceRevision()
            || !projection.fieldsDigest().equals(currentDigest)) return false;
        var mapped = ProjectionDocumentMapper.toIndexDocument(projection).fields();
        if (!Objects.equals(projectedTerm(mapped.get(SchemaFields.PATH)), path)
            || !Objects.equals(projectedTerm(mapped.get(SchemaFields.COLLECTION)), collection)) return false;
      }
      return true;
    } catch (IOException | RuntimeException unreadable) {
      context.log().warn("Buffered projection evidence is unreadable; retaining replay versions",
          unreadable);
      return false;
    }
  }

  /** Position maps project one frozen snapshot; they carry no independent receipt authority. */
  private record ReplayDeleteOrder(
      Map<SwitchBufferCapableQueue.SwitchBufferOp, Integer> positions,
      Map<SwitchBufferCapableQueue.SwitchBufferOp, Integer> deletes,
      Map<String, Map<String, Integer>> exactDeletes,
      Release030Roots releaseRecovery,
      Map<SwitchBufferCapableQueue.SwitchBufferOp,io.justsearch.indexerworker.queue.SwitchBufferSyncRoot> rootIntents,
      java.util.function.Function<String,SwitchBufferCapableQueue.RetirementRootFile> rootFiles) {}

  private static ReplayDeleteOrder replayDeleteOrder(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> ops, boolean exactRead, Release030Roots recovery) {
    Map<SwitchBufferCapableQueue.SwitchBufferOp, Integer> positions = new HashMap<>();
    Map<SwitchBufferCapableQueue.SwitchBufferOp, Integer> deletes = new LinkedHashMap<>();
    Map<String, Map<String, Integer>> exactDeletes = new HashMap<>();
    for (int index = 0; index < ops.size(); index++) {
      var op = ops.get(index);
      positions.put(op, index);
      if (context.approvedGapVersions().contains(op) || op.op() == null) continue;
      switch (op.op()) {
        case "DELETE", "DELETE_PREFIX", "DELETE_COLLECTION" -> {
          if (exactRead && op.generation() != null && !op.generation().isBlank()
              && op.generation().equals(context.replayGeneration())) {
            decodeCommittedNativeEffect(op, Set.of());
          }
          if ("DELETE".equals(op.op())) {
            String generation = op.generation() == null ? "" : op.generation();
            var deletion = SwitchBufferDelete.decode(op.payload());
            if (deletion.origin() == SwitchBufferDelete.Origin.EXPLICIT) {
              exactDeletes.computeIfAbsent(generation, ignored -> new HashMap<>())
                  .put(deletion.path(), index);
            }
          } else {
            deletes.put(op, index);
          }
        }
        default -> { }
      }
    }
    exactDeletes.replaceAll((generation, indexes) -> Map.copyOf(indexes));
    return new ReplayDeleteOrder(Map.copyOf(positions), java.util.Collections.unmodifiableMap(deletes),
        Map.copyOf(exactDeletes), recovery, recovery == null ? Map.of() : releaseRootIntents(ops.stream()
            .filter(op -> !context.approvedGapVersions().contains(op)).toList()),
        recovery!=null && context.jobQueue() instanceof SwitchBufferCapableQueue queue
            ? queue.retirementRootFiles() : path->null);
  }

  /** Only a completed exact walk receipt can supersede an earlier overlapping effect. */
  private static SwitchBufferCapableQueue.SwitchBufferOp latestConfirmedReleaseSync(
      ReplayDeleteOrder order, String file, int afterIndex) {
    if (order.releaseRecovery() == null || file == null) return null;
    SwitchBufferCapableQueue.SwitchBufferOp latest = null;
    int position = afterIndex;
    for (var entry : order.positions().entrySet()) {
      var op = entry.getKey();
      if (entry.getValue() <= position || !"SYNC_ROOT".equals(op.op())
          || !order.releaseRecovery().checkpoint().rootWalked(op)) continue;
      String root = io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(op.payload()).rootPath();
      if (op.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX)
          && !order.releaseRecovery().currentRoots().containsKey(Path.of(root))) continue;
      var frozen=order.releaseRecovery().checkpoint().rootScope(op);
      if (frozen==null || !frozen.contains(file)) continue;
      // Administrative discovery and force=false cannot supersede indexed work by
      // declaration alone. Require this exact ROOT's actual validated current admission.
      if (op.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX)
          || !io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(op.payload()).force()) {
        var actual=order.rootFiles().apply(file);
        if(actual==null || !actual.owner().equals(op))continue;
      }
      latest = op;
      position = entry.getValue();
    }
    return latest;
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp lastMatchingReleaseDelete(
      DrainSwitchBufferContext context,List<SwitchBufferCapableQueue.SwitchBufferOp> ops,ReplayDeleteOrder order,
      int earlier,String path,String collection) {
    for(var op:ops.reversed()) {
      if(order.positions().get(op)<=earlier || !Objects.equals(context.replayGeneration(),op.generation())
          || context.approvedGapVersions().contains(op))continue;
      if("DELETE".equals(op.op()) && SwitchBufferDelete.decode(op.payload()).path().equals(path)
          || "DELETE_PREFIX".equals(op.op()) && PathPrefixScope.decode(op.payload()).contains(path)
          || "DELETE_COLLECTION".equals(op.op()) && op.payload().equals(collection))return op;
    }
    return null;
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp declaredReleaseDeletionOwner(
      DrainSwitchBufferContext context,List<SwitchBufferCapableQueue.SwitchBufferOp> ops,
      String path,long after) {
    if (!(context.jobQueue() instanceof SqliteJobQueue queue)) return null;
    var cp=queue.release030RecoveryState();
    return ops.stream().filter(op -> "SYNC_ROOT".equals(op.op()) && op.acceptedOrder()>after
        && !op.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX))
        .filter(op -> {
          var intent=io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(op.payload());
          var scope=cp.rootScope(op);String policy=cp.rootPolicy(op);
          return intent.force() && policy!=null && policy.startsWith("DECLARED:")
              && scope!=null && scope.contains(path) && (cp.rootEnumerated(op)||cp.rootWalked(op))
              && !io.justsearch.indexerworker.ingest.IngestionSkipPolicy.shouldSkipWithinRoot(Path.of(path),Path.of(intent.rootPath()));
        }).max(java.util.Comparator.comparingLong(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder)).orElse(null);
  }

  private static boolean verifyDeletedReleaseFile(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> ops, ReplayDeleteOrder order, int earlier,
      SwitchBufferUpsert file, Map<SwitchBufferCapableQueue.SwitchBufferOp, WatcherReplay> reconciliations,
      Map<SwitchBufferCapableQueue.SwitchBufferOp,List<String>> fileSurvivorExclusions)
      throws IOException {
    if (release030Retirement(context)) {
      // Only the last matching deletion determines whether absence is namespace-scoped.
      for (var deletion : ops.reversed()) {
        if (order.positions().get(deletion) <= earlier
            || !Objects.equals(context.replayGeneration(), deletion.generation())
            || context.approvedGapVersions().contains(deletion)) continue;
        if ("DELETE".equals(deletion.op())
            && SwitchBufferDelete.decode(deletion.payload()).path().equals(file.path())) break;
        if ("DELETE_PREFIX".equals(deletion.op())
            && PathPrefixScope.decode(deletion.payload()).contains(file.path())) break;
        if (!"DELETE_COLLECTION".equals(deletion.op()) || !deletion.payload().equals(file.collection())) continue;
        var survivors = new ArrayList<>(reconciledSurvivorsAfterDelete(context, ops, order, deletion, reconciliations));
        survivors.addAll(fileSurvivorExclusions.getOrDefault(deletion,List.of()));
        for (var later : ops) if ("UPSERT".equals(later.op())
            && order.positions().get(later) > order.positions().get(deletion)) {
          var accepted = resolveReleaseUpsert(context,later);
          if (deletion.payload().equals(accepted.collection())
              && !hasLaterAcceptedDelete(context,order,order.positions().get(later),accepted.path(),
                  accepted.path(),accepted.collection(),context.replayGeneration())) survivors.add(accepted.path());
        }
        return order.releaseRecovery().checkpoint().deletionQueueComplete(deletion)
            && certifyReleaseDeletion(context,deletion,survivors);
      }
    }
    return context.ingestLifecycle().indexCountOps().countByIdAndChunksStrict(file.path()) == 0;
  }

  /** Only later, applied accepted deletes can explain the final absence of an admission. */
  private static boolean hasLaterAcceptedDelete(DrainSwitchBufferContext context,
      ReplayDeleteOrder deleteOrder, int projectionIndex,
      String id, String path, String collection, String generation) {
    int exactIndex = deleteOrder.exactDeletes().getOrDefault(generation == null ? "" : generation,
        Map.of()).getOrDefault(id, -1);
    int lastDelete = exactIndex > projectionIndex ? exactIndex : -1;
    for (var indexedDeletion : deleteOrder.deletes().entrySet()) {
      if (indexedDeletion.getValue() <= projectionIndex) continue;
      var deletion = indexedDeletion.getKey();
      if (context.approvedGapVersions().contains(deletion)) continue;
      if (!Objects.equals(generation, deletion.generation())) continue;
      if (deletion.revision() == null || deletion.revision().isBlank()) {
        throw new IllegalArgumentException("Buffered delete has no exact revision");
      }
      String payload = deletion.payload();
      switch (deletion.op().toUpperCase(Locale.ROOT)) {
        case "DELETE_PREFIX" -> {
          var scope = PathPrefixScope.decode(payload);
          if (!deletion.key().startsWith("prefix:")
              || !new PathPrefixScope(deletion.key().substring(7), List.of()).prefix().equals(scope.prefix())) {
            throw new IllegalArgumentException("Buffered prefix delete has an invalid key");
          }
          if (scope.contains(path)) lastDelete = Math.max(lastDelete, indexedDeletion.getValue());
        }
        case "DELETE_COLLECTION" -> {
          if (!IngestCollectionPolicy.isDeletable(payload)
              || !("collection:" + payload).equals(deletion.key())) {
            throw new IllegalArgumentException("Buffered collection delete has an invalid scope");
          }
          if (payload.equals(collection)) lastDelete = Math.max(lastDelete, indexedDeletion.getValue());
        }
        default -> { }
      }
    }
    return lastDelete >= 0 && latestConfirmedReleaseSync(deleteOrder, path, lastDelete) == null;
  }

  private static boolean isVduBufferKind(String kind) {
    return kind != null && kind.trim().toUpperCase(Locale.ROOT).startsWith("VDU_");
  }

  /** Loads complete declared coverage; an unreadable source cannot certify an empty migration. */
  public static List<ResolvedConfig.FileSource> loadMigrationRoots(
      Path dataDir, ResolvedConfig.Collections collections, ObjectMapper json) throws IOException {
    var roots = readMigrationRootDeclarations(dataDir, collections, json);
    for (Path root : roots.keySet()) requireMigrationRoot(root);
    return withMigrationHelpRoot(roots, collections);
  }

  /** Freeze authored ownership separately from the walk's availability checks. */
  public static List<ResolvedConfig.FileSource> loadMigrationRootDeclarations(
      Path dataDir, ResolvedConfig.Collections collections, ObjectMapper json) throws IOException {
    return withMigrationHelpRoot(readMigrationRootDeclarations(dataDir, collections, json), collections);
  }

  public static List<ResolvedConfig.FileSource> loadMigrationRootDeclarations(Path dataDir,
      ResolvedConfig.Collections collections, ObjectMapper json, List<IngestCollectionPolicy.RootBinding> official)
      throws IOException {
    return withMigrationHelpRoot(readMigrationRootDeclarations(dataDir, collections, json, Objects.requireNonNull(official)), collections);
  }

  private static Map<Path, ResolvedConfig.FileSource> readMigrationRootDeclarations(
      Path dataDir, ResolvedConfig.Collections collections, ObjectMapper json) throws IOException {
    return readMigrationRootDeclarations(dataDir, collections, json, null);
  }

  private static Map<Path, ResolvedConfig.FileSource> readMigrationRootDeclarations(
      Path dataDir, ResolvedConfig.Collections collections, ObjectMapper json,
      List<IngestCollectionPolicy.RootBinding> official) throws IOException {
    Map<Path, ResolvedConfig.FileSource> roots = new LinkedHashMap<>();
    Path rootsFile = dataDir.resolve("watched_roots.json");
    String content;
    try {
      content = Files.readString(rootsFile);
    } catch (NoSuchFileException absent) {
      // A missing registry is valid only when the path itself is absent, not a dangling link.
      if (!Files.notExists(rootsFile, LinkOption.NOFOLLOW_LINKS)) throw absent;
      content = null;
    }
    if (content != null) {
      JsonNode document;
      try {
        document = json.readTree(content);
      } catch (JacksonException malformed) {
        throw new IOException("Invalid watched-roots JSON", malformed);
      }
      boolean object = document != null && document.isObject();
      if (object) WatchedRootsFormat.requireReadableObject(document);
      JsonNode entries = object ? document.get("roots") : document;
      if (entries == null || !entries.isArray()) throw new IOException("Invalid watched-roots array");
      for (JsonNode entry : entries) {
        JsonNode path = object ? entry.get("path") : entry;
        if (path == null || !path.isString() || path.asText().isBlank()) {
          throw new IOException("Invalid watched-roots path");
        }
        String collection = object ? entry.path("collection").asText(null) : null;
        var source = new ResolvedConfig.FileSource(Path.of(path.asText()), collection);
        roots.put(source.path(), source);
      }
    }
    if (official != null) {
      Map<Path, ResolvedConfig.FileSource> expected = new LinkedHashMap<>();
      for (var binding : official) {
        Path path = binding.path().toAbsolutePath().normalize();
        var source = new ResolvedConfig.FileSource(path, normalizeReplayCollection(binding.collection()));
        if (expected.put(path, source) != null) throw new IOException("Duplicate official watched root");
      }
      Map<Path, ResolvedConfig.FileSource> actual = new LinkedHashMap<>();
      for (var source : roots.values()) actual.put(source.path(),
          new ResolvedConfig.FileSource(source.path(), normalizeReplayCollection(source.collection())));
      if (!expected.equals(actual)) throw new IOException("Current watched declarations differ from the official root owner");
    }
    if (collections != null) {
      for (ResolvedConfig.CollectionCfg collection : collections.items()) {
        for (Path root : collection.roots()) {
          if (root == null) throw new IOException("Null configured migration root");
          var source = new ResolvedConfig.FileSource(root, collection.name());
          // A watched binding wins even when null denotes the default collection.
          roots.putIfAbsent(source.path(), source);
        }
      }
    }
    return roots;
  }

  private static List<ResolvedConfig.FileSource> withMigrationHelpRoot(
      Map<Path, ResolvedConfig.FileSource> roots, ResolvedConfig.Collections collections) {
    // Startup's version marker is not a migration coverage authority.
    var help = collections == null ? null : collections.bundledHelp();
    if (!EnvRegistry.EVAL_MODE.getBoolean(false)
        && help != null && Files.isDirectory(help.path())) roots.put(help.path(), help);
    return List.copyOf(roots.values());
  }

  private static void requireMigrationRoot(Path root) throws IOException {
    if (root == null) throw new IOException("Null migration root");
    BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if ((!attributes.isDirectory() && !attributes.isRegularFile()) || !Files.isReadable(root)) {
      throw new IOException("Unreadable migration root: " + root);
    }
  }

  private static void requireEnumerationRunning(EnqueueContext context) throws IOException {
    if (Thread.currentThread().isInterrupted() || !context.runningSupplier().getAsBoolean()) {
      throw new IOException("Migration enumeration stopped before complete coverage");
    }
  }

  private static int acceptMigrationBatch(EnqueueContext context, List<JobQueue.EnqueueEntry> batch,
      String collection)
      throws IOException {
    requireEnumerationRunning(context);
    IndexGenerationManager manager = context.indexGenerationManagerSupplier().get();
    int accepted;
    if (manager == null) {
      // Lightweight enumeration callers without a generation owner retain the ordinary queue
      // seam. A live migration always supplies its manager and must record the exact candidate.
      accepted = collection == null ? context.jobQueue().enqueueEntries(batch)
          : context.jobQueue().enqueueEntries(batch, collection);
    } else {
      IndexGenerationManager.State state = manager.readStateBestEffort();
      if (state == null || state.building_generation() == null
          || !(context.jobQueue() instanceof SwitchBufferCapableQueue scoped)) {
        throw new IOException("Migration enumeration has no atomic candidate admission");
      }
      accepted = scoped.enqueueEnumeratedFilesForGeneration(state.building_generation(), batch, collection);
    }
    context.migrationEnumeratorFilesEnqueued().addAndGet(accepted);
    if (accepted != batch.size()) throw new IOException("Incomplete migration batch admission");
    batch.clear();
    return accepted;
  }

  public static int enqueueAllFilesUnderRoots(EnqueueContext context) throws IOException {
    return enqueueAllFilesUnderRoots(context, null);
  }

  /** Scan roots may omit nested declarations; freeze legacy receipts using replay's authority. */
  public static int enqueueAllFilesUnderRoots(EnqueueContext context, WorkerIngestService publisher)
      throws IOException {
    requireEnumerationRunning(context);
    if (context.jobQueue() == null || context.roots() == null) {
      throw new IOException("Missing migration queue or roots");
    }
    // The loaded roots are the ownership authority for this entire walk. Do not consult live
    // watcher declarations between roots: they may change while enumeration is in progress.
    List<ResolvedConfig.FileSource> roots = List.copyOf(context.roots());
    List<Path> declared = roots.stream()
        .map(source -> source.path().toAbsolutePath().normalize()).toList();
    Map<Path, PathPrefixScope> scopes = new HashMap<>();
    for (Path root : declared) {
      scopes.put(root, new PathPrefixScope(root.toString(), declared.stream()
          .filter(path -> !path.equals(root) && path.startsWith(root))
          .map(Path::toString).toList()));
    }
    if (context.jobQueue() instanceof SwitchBufferCapableQueue queue) {
      scopeLegacyPrefixes(queue, null, publisher);
    }
    int total = 0;
    int batchSize = 2_000;
    ArrayList<JobQueue.EnqueueEntry> batch = new ArrayList<>(batchSize);
    long lastPersistMs = 0L;

    for (ResolvedConfig.FileSource source : roots) {
      Path root = source.path();
      PathPrefixScope scope = scopes.get(root.toAbsolutePath().normalize());
      boolean help = io.justsearch.configuration.InternalCollections.HELP.equals(source.collection());
      requireEnumerationRunning(context);
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
      requireEnumerationRunning(context);
      requireMigrationRoot(root);
      context.log().info("Migration enumerator scanning root: {}", root);
      try (Stream<Path> walk = Files.walk(root, help ? 1 : Integer.MAX_VALUE)) {
        var iterator = walk.iterator();
        while (iterator.hasNext()) {
          Path path = iterator.next();
          requireEnumerationRunning(context);
          if (scope.excludes(path.toAbsolutePath().normalize().toString())) continue;
          BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (attributes.isDirectory()) continue;
          if (help && !path.toString().endsWith(".md")) continue;
          if (!attributes.isRegularFile() || !Files.isReadable(path)) {
            throw new IOException("Unreadable migration file: " + path);
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

          requireEnumerationRunning(context);
          batch.add(new JobQueue.EnqueueEntry(path, attributes.size()).withinRoot(root));
          if (batch.size() >= batchSize) {
            total += acceptMigrationBatch(context, batch, source.collection());
          }
        }
      }
      if (!batch.isEmpty()) total += acceptMigrationBatch(context, batch, source.collection());
      requireEnumerationRunning(context);
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
    requireEnumerationRunning(context);
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
