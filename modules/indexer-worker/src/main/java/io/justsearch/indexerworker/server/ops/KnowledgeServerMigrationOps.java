/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

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
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
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
      WorkerIngestService publisher) {
    public DrainSwitchBufferContext {
      approvedGapVersions = Set.copyOf(approvedGapVersions);
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
          deadlineNanos, replayGeneration, projectionSourceReady, approvedGapVersions, publisher);
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

        long depth = context.jobQueue().queueDepth();
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

        if (!context.liveCutover().recorded()) {
          long failedJobs;
          try {
            // U12: terminal pre-boundary rows are retained, but cannot veto this cutover.
            // Newly admitted migration work has a boundary and still counts against the limit.
            failedJobs = Math.max(0L, context.jobQueue().failureSummary().failedCount()
                - context.jobQueue().unownedFailureCount());
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
    try {
      if (ingestLifecycle == null) {
        return false;
      }
      if (!ingestLifecycle.commitMetadataEnabled()) {
        log.warn("Green verification skipped because commit metadata is disabled");
        return true;
      }
      return verifyGreenMetadata(
          ingestLifecycle.latestCommitUserDataBestEffort(), expectedIndexFp, expectedEmbeddingFp, log);
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
      Map<String, String> ud, String expectedIndexFp, String expectedEmbeddingFp, Logger log) {
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

  /** Replay remains durable until the generation pointer commits, so abandonment can replay on A. */
  public record StrictReplay(List<SwitchBufferCapableQueue.SwitchBufferOp> versions) {
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
    return result.applied() ? java.util.Optional.of(new StrictReplay(result.versions()))
        : java.util.Optional.empty();
  }

  /** Remove only the exact applied versions after B is durably committed. */
  public static boolean finishPromotedSwitchReplay(JobQueue queue, StrictReplay replay) {
    if (!(queue instanceof SwitchBufferCapableQueue sbq)) return false;
    if (replay.versions().isEmpty()) return true;
    try { return sbq.removeReplayedSwitchBufferOps(replay.versions()) == replay.versions().size(); }
    catch (RuntimeException unavailable) { return false; }
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
      return finishPromotedSwitchReplay(queue,
          new StrictReplay(strictReplayOps(sbq, generation)))
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
   * replaying destructive mutations. Conditional removal retains concurrent replacement rows.
   */
  public static boolean settleCommittedNativeFileWitnesses(
      JobQueue queue, RunningRuntime active, String generation, List<String> ownedSources, Logger log) {
    if (!(queue instanceof SwitchBufferCapableQueue scoped) || active == null
        || generation == null || generation.isBlank() || ownedSources == null) return false;
    try {
      List<SwitchBufferCapableQueue.SwitchBufferOp> selected = scoped.listSwitchBufferOpsStrict()
          .stream().filter(op -> generation.equals(op.generation())).toList();
      if (selected.stream().allMatch(op -> "UPSERT".equals(op.op()) || "DELETE".equals(op.op()))) {
        return settleCommittedNativeFiles(queue, active, generation, log, selected);
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
      for (var op : selected) {
        if ("DELETE".equals(op.op())) {
          var deletion = SwitchBufferDelete.decode(op.payload());
          if (deletion.origin() != SwitchBufferDelete.Origin.EXPLICIT
              && !settleConditionalNativeDelete(queue, active, deletion)) return false;
        }
      }
      Set<CommittedNativeEffect> survivors = acceptedSurvivors(effects);
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
        if (survivors.contains(effect)) {
          if (!certifyCommittedSurvivor(scoped, active, effects, deleteIndexes, i)) return false;
        } else if (effect.id() != null
            && !survivorIds.contains(effect.id())
            && active.indexCountOps().countByIdAndChunksStrict(effect.id()) != 0) {
          return false;
        }
        if ("DELETE_PREFIX".equals(effect.op().op())
            || "DELETE_COLLECTION".equals(effect.op().op())) {
          if (!certifyCommittedDeleteScope(queue, active, effects, survivors, i)) return false;
        }
      }
      if (scoped.removeReplayedSwitchBufferOps(selected) != selected.size()) return false;
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
      case "DELETE_PREFIX" -> expectedKey = "prefix:" + op.payload();
      case "DELETE_COLLECTION" -> {
        if (!IngestCollectionPolicy.isDeletable(op.payload())) {
          throw new IllegalArgumentException("Protected collection cannot certify deletion");
        }
        expectedKey = "collection:" + op.payload();
      }
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
      case "DELETE_PREFIX" -> document.path() != null && document.path().startsWith(
          io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix(deletion.op().payload()));
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
      int index) throws IOException {
    var effect = effects.get(index);
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
      List<CommittedNativeEffect> effects, Set<CommittedNativeEffect> survivors, int index) throws IOException {
    var deletion = effects.get(index);
    List<String> files = new ArrayList<>();
    List<String> projections = new ArrayList<>();
    for (int next = index + 1; next < effects.size(); next++) {
      var survivor = effects.get(next);
      if (!survivors.contains(survivor) || !nativeDeleteMatches(deletion, survivor)) continue;
      if (survivor.file() != null) files.add(survivor.id());
      else projections.add(survivor.id());
    }
    String scope = deletion.op().payload();
    if ("DELETE_PREFIX".equals(deletion.op().op())) {
      return !queue.hasNonterminalJobsByPathPrefixStrict(scope)
          && active.indexCountOps().countPathPrefixExcludingAcceptedSurvivorsStrict(scope, files, projections) == 0;
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
      String generation, Logger log, List<SwitchBufferCapableQueue.SwitchBufferOp> selected) {
    if (!(queue instanceof SwitchBufferCapableQueue scoped) || active == null
        || generation == null || generation.isBlank()) return false;
    try {
      // Refuse unsupported kinds before applying any partial replay on this boot attempt.
      if (selected.stream().anyMatch(op -> !"UPSERT".equals(op.op())
          && !"DELETE".equals(op.op()))) return false;
      if (selected.stream().anyMatch(op -> "UPSERT".equals(op.op()))) {
        active.commitOps().maybeRefreshBlocking();
      }
      // Validate every receipt before touching B. A later unresolved UPSERT must not leave an
      // earlier DELETE half-applied while the boot attempt waits for its exact queue revision.
      for (var op : selected) {
        if (op.payload() == null || op.payload().isBlank()) return false;
        if ("UPSERT".equals(op.op())) {
          var upsert = SwitchBufferUpsert.decode(op.payload());
          String indexed = active.documentFieldOps()
              .getDocumentFieldOrThrow(upsert.path(), SchemaFields.SOURCE_SHA256);
          if (!op.key().equals("path:" + upsert.path())
              || upsert.sourceSha256() == null
              || !scoped.matchesAcceptedFileProjection(upsert.path(), upsert.unitRevision(),
                  upsert.sourceSha256(), indexed)) return false;
        } else if (!op.key().equals("path:" + SwitchBufferDelete.decode(op.payload()).path())) {
          return false;
        }
      }
      boolean deleted = false;
      for (var op : selected) {
        if ("DELETE".equals(op.op())) {
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
          if ("DELETE".equals(op.op())) {
            var deletion = SwitchBufferDelete.decode(op.payload());
            if (deletion.origin() == SwitchBufferDelete.Origin.EXPLICIT
                && active.indexCountOps().countByIdAndChunksStrict(deletion.path()) != 0) return false;
          }
        }
      }
      if (!selected.isEmpty() && scoped.removeReplayedSwitchBufferOps(selected) != selected.size()) {
        return false;
      }
      return switchBufferEmptyStrict(queue, generation);
    } catch (IOException | RuntimeException unavailable) {
      log.warn("Committed native file witnesses remain unresolved", unavailable);
      return false;
    }
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

  private static List<SwitchBufferCapableQueue.SwitchBufferOp> strictReplayOps(
      SwitchBufferCapableQueue queue, String generation) {
    return queue.listSwitchBufferOpsStrict().stream()
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
    if (!(context.jobQueue() instanceof SwitchBufferCapableQueue sbq)) {
      return new ReplayOutcome(false, List.of());
    }
    boolean allowVdu = context.vduReplayAllowed().getAsBoolean();
    List<SwitchBufferCapableQueue.SwitchBufferOp> allOps;
    try {
      allOps = exactRead ? strictReplayOps(sbq, context.replayGeneration()).stream()
              .filter(op -> includeUnscoped || context.replayGeneration().equals(op.generation()))
              .toList()
          : sbq.listSwitchBufferOps().stream()
              .filter(op -> (includeUnscoped && (op.generation() == null || op.generation().isEmpty()))
                  || (context.replayGeneration() != null
                      && context.replayGeneration().equals(op.generation()))).toList();
    } catch (RuntimeException unreadable) {
      context.log().warn("Switch buffer cannot certify final replay", unreadable);
      return new ReplayOutcome(false, List.of());
    }
    boolean deferredVdu = !allowVdu && allOps.stream().anyMatch(op -> isVduBufferKind(op.op()));
    List<SwitchBufferCapableQueue.SwitchBufferOp> ops = allOps.stream()
        .filter(op -> allowVdu || !isVduBufferKind(op.op())).toList();
    if (ops.isEmpty()) {
      return new ReplayOutcome(!deferredVdu, List.of());
    }
    context.log().info("Draining {} buffered ops from durable switch buffer...", ops.size());

    Set<SwitchBufferCapableQueue.SwitchBufferOp> fileAuthorizingDeletes;
    Map<SwitchBufferCapableQueue.SwitchBufferOp, List<String>> fileSurvivorExclusions;
    ReplayDeleteOrder deleteOrder;
    Set<SwitchBufferCapableQueue.SwitchBufferOp> supersededLegacyFiles;
    try {
      deleteOrder = replayDeleteOrder(context, ops, exactRead);
      supersededLegacyFiles = supersededLegacyFiles(context, ops);
      fileAuthorizingDeletes = exactRead ? fileRelatedBroadDeletes(context, ops, deleteOrder) : Set.of();
      fileSurvivorExclusions = replayFileSurvivorExclusions(context, ops, fileAuthorizingDeletes);
    } catch (IOException | RuntimeException invalid) {
      context.log().warn("Buffered file/delete receipts cannot certify replay", invalid);
      return new ReplayOutcome(false, List.of());
    }

    ArrayList<SwitchBufferCapableQueue.SwitchBufferOp> toEnqueue = new ArrayList<>();
    boolean mutatedLucene = false;
    boolean allApplied = true;
    boolean firstCandidateProjectionObserved = false;
    AtomicReference<RuntimeException> pruneCutFailure = new AtomicReference<>();

    for (SwitchBufferCapableQueue.SwitchBufferOp op : ops) {
      if (supersededLegacyFiles.contains(op)) continue;
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
            || (exactRead && (!awaitQueuedUpserts(context, refusedSourceRecovery)
                || !verifyBufferedUpserts(context, toEnqueue, ops, deleteOrder, false,
                    refusedSourceRecovery)))) {
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
      switch (kind) {
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
              toEnqueue.add(op);
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
              if (deletion.origin() == SwitchBufferDelete.Origin.EXPLICIT) {
                context.ingestLifecycle().indexingCoordinator().deleteByIdAndChunks(deletion.path());
                if (context.jobQueue().deleteByExactPath(deletion.path()) < 0) {
                  throw new IllegalStateException("Buffered exact queue deletion failed");
                }
                mutatedLucene = true;
              } else {
                WorkerIngestService authority = context.publisher() != null
                    ? context.publisher() : replayService(context);
                if (uncertainBeneathItsRoot(authority, deletion)) {
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
                      && refusedSourceRecovery) {
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
              if (survivors.isEmpty()) {
                context.ingestLifecycle().indexingCoordinator().deleteByPathPrefix(payload);
                if (context.jobQueue().deleteByPathPrefix(payload) < 0) {
                  throw new IllegalStateException("Buffered prefix queue deletion failed");
                }
              } else {
                context.ingestLifecycle().indexingCoordinator()
                    .deleteByPathPrefixExcludingAcceptedSurvivors(payload, survivors);
                context.jobQueue().deleteByPathPrefixExcludingAcceptedSurvivors(payload, survivors);
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
                r = exactRead ? tmp.syncDirectoryForFinalCutoverReplay(req, provenance)
                    : tmp.syncDirectoryForReplay(req, provenance);
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
                // Enumeration may enqueue a file that the Green loop has already claimed. A
                // subsequent direct DELETE_PREFIX must wait for that writer, not merely remove its
                // queue row while it can still publish a late Lucene write.
                if (exactRead && !awaitQueuedUpserts(context, refusedSourceRecovery)) {
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
              // Task t-20261013-ef3c41: replay prunes only confirmed absences. The root holding the
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
                    && !awaitQueuedUpserts(context, refusedSourceRecovery)) {
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
          && (!exactRead || awaitQueuedUpserts(context, refusedSourceRecovery));
    }

    // Refused recovery also judges file admissions by A's writer, whose batches A's reader sees
    // before they are durable: commit A so the journal is acknowledged only for committed effects.
    if ((mutatedLucene || refusedSourceRecovery) && context.ingestLifecycle() != null) {
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

    if (allApplied && exactRead) {
      allApplied = verifyBufferedUpserts(context, ops.stream()
          .filter(op -> "UPSERT".equalsIgnoreCase(op.op())
              && !context.approvedGapVersions().contains(op)).toList(), ops, deleteOrder, true,
          refusedSourceRecovery);
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
        int cleared = sbq.removeReplayedSwitchBufferOps(ops);
        context.log().info("Removed {} replayed buffer versions; later admissions remain", cleared);
        removed = cleared == ops.size();
      } catch (IllegalStateException failure) {
        context.log().warn("Failed to remove committed buffer versions; retaining for retry", failure);
      }
    } else if (!allApplied) {
      context.log().warn("Not clearing switch buffer because one or more buffered ops failed to replay");
    }
    return new ReplayOutcome(allApplied && (removed || !removeAfterReplay) && !deferredVdu,
        allApplied ? ops : List.of());
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
    return new WorkerIngestService(
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
  }

  /** A later direct Lucene delete cannot overtake a claimed UPSERT still writing on Green. */
  private static boolean awaitQueuedUpserts(DrainSwitchBufferContext context,
      boolean refusedSourceRecovery) {
    // All replay barriers consume the same SWITCHING budget. Returning early would re-enqueue
    // retained UPSERT versions on the next attempt, possibly resetting a claimed write.
    long deadline = context.deadlineNanos();
    try {
      while (true) {
        JobQueue.JobStateCounts counts = context.jobQueue().jobStateCountsStrict();
        // Refused-candidate recovery runs with its writer paused and proved idle, so pending rows
        // cannot move here; they stay durable queue work that surviving A's writer runs between
        // attempts. Only a claimed row could still publish a late write.
        if (refusedSourceRecovery) return counts.processingCount() == 0;
        if (counts.processingCount() == 0 && counts.pendingCount() == 0) return true;
        if (System.nanoTime() >= deadline) return false;
        Thread.sleep(50);
      }
    } catch (RuntimeException unavailable) {
      context.log().warn("Cannot certify buffered UPSERT settlement", unavailable);
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
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
          laterAccepted.add(upsert.path());
        } else if (upsert.sourceSha256() == null && legacy
            && laterAccepted.contains(upsert.path())) {
          superseded.add(op);
        }
      } else if (legacy && "DELETE".equalsIgnoreCase(op.op())) {
        var deletion = SwitchBufferDelete.decode(op.payload());
        if (deletion.origin() == SwitchBufferDelete.Origin.LEGACY
            && laterAccepted.contains(deletion.path())) superseded.add(op);
      }
    }
    return superseded;
  }

  private static boolean enqueueBufferedUpserts(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> upserts) {
    boolean complete = true;
    int enqueued = 0;
    for (var op : upserts) {
      try {
        var upsert = SwitchBufferUpsert.decode(op.payload());
        if (upsert.sourceSha256() != null) {
          // A scoped file admission already owns a durable queue row and an exact source
          // witness. Re-enqueueing would mint a new revision and read later filesystem bytes.
          // The caller waits for that row and verifies Green's indexed source witness.
          continue;
        }
        int accepted = context.jobQueue().enqueueEntries(List.of(upsert.entry()), upsert.collection());
        enqueued += accepted;
        if (accepted != 1) complete = false;
      } catch (RuntimeException unavailable) {
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
      boolean finalEffects, boolean refusedSourceRecovery) {
    if (upserts.isEmpty()) return true;
    if (context.ingestLifecycle() == null
        || !(context.jobQueue() instanceof SwitchBufferCapableQueue queue)) return false;
    try {
      context.ingestLifecycle().commitOps().maybeRefreshBlocking();
    } catch (RuntimeException unavailable) {
      context.log().warn("Buffered UPSERT reader could not refresh", unavailable);
      return false;
    }
    int awaited = 0;
    String firstAwaited = null;
    try {
      for (var op : upserts) {
        var upsert = SwitchBufferUpsert.decode(op.payload());
        if (upsert.sourceSha256() == null) continue; // Historical unscoped cutover payload.
        boolean scoped = op.generation() != null && !op.generation().isBlank()
            && op.generation().equals(context.replayGeneration());
        if (scoped && hasLaterAcceptedDelete(context, deleteOrder, deleteOrder.positions().get(op),
            upsert.path(), upsert.path(), upsert.collection(), op.generation())) {
          // Live broad deletion may already have removed this exact admission on B. Await
          // the queue before crossing the boundary, then prove final absence after all writes.
          if (finalEffects && context.ingestLifecycle().indexCountOps()
              .countByIdAndChunksStrict(upsert.path()) != 0) return false;
          continue;
        }
        String indexed = context.ingestLifecycle().documentFieldOps()
            .getDocumentFieldOrThrow(upsert.path(), SchemaFields.SOURCE_SHA256);
        if (refusedSourceRecovery) {
          // Judge every admission, so each one A lacks gets its A job in this attempt.
          if (!sourceHoldsAdmission(context, queue, upsert, indexed)) {
            if (awaited++ == 0) firstAwaited = upsert.path();
          }
          continue;
        }
        boolean settled = queue.matchesAcceptedFileProjection(
            upsert.path(), upsert.unitRevision(), upsert.sourceSha256(), indexed);
        if (!settled) {
          context.log().warn("Buffered UPSERT lacks its accepted target projection: {}", upsert.path());
          return false;
        }
      }
      if (awaited != 0) {
        context.log().warn("Refused candidate awaits A's effect on {} accepted file(s), first: {}",
            awaited, firstAwaited);
        return false;
      }
      return !finalEffects || certifyReplayFileDeleteScopes(context, orderedOps, deleteOrder);
    } catch (IOException | RuntimeException unavailable) {
      context.log().warn("Buffered UPSERT projection evidence is unreadable", unavailable);
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
      SwitchBufferCapableQueue queue, SwitchBufferUpsert upsert, String indexed) throws IOException {
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
    var rootCheck = publisher.rootCheck();
    if (rootCheck == null) return CleanupPathState.UNCERTAIN;
    Path file = path.toAbsolutePath().normalize();
    String before = observedKind(file);
    if (before == null) return CleanupPathState.UNCERTAIN;
    boolean asRoot = io.justsearch.indexerworker.loop.SourcePresence.answersAsRoot(file, root);
    Path folder = asRoot ? root.toAbsolutePath().normalize() : file.getParent();
    if (folder == null
        || !(asRoot ? rootCheck.vouches(folder) : rootCheck.vouchesForFile(folder))) {
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
      List<SwitchBufferCapableQueue.SwitchBufferOp> orderedOps, ReplayDeleteOrder deleteOrder) throws IOException {
    String generation = context.replayGeneration();
    if (generation == null || generation.isBlank()) return true;
    Set<SwitchBufferCapableQueue.SwitchBufferOp> authorizingDeletes =
        fileRelatedBroadDeletes(context, orderedOps, deleteOrder);
    if (authorizingDeletes.isEmpty()) return true;
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
    if (!(context.jobQueue() instanceof SwitchBufferCapableQueue queue)) return false;
    for (int index = 0; index < effects.size(); index++) {
      var deletion = effects.get(index);
      if (!authorizingDeletes.contains(deletion.op())) continue;
      for (int next = index + 1; next < effects.size(); next++) {
        var survivor = effects.get(next);
        if (survivors.contains(survivor) && nativeDeleteMatches(deletion, survivor)
            && !certifyCommittedSurvivor(queue, active, effects, deleteIndexes, next)) return false;
      }
      if (!certifyCommittedDeleteScope(context.jobQueue(), active, effects, survivors, index)) return false;
      if ("DELETE_PREFIX".equals(deletion.op().op())) {
        List<String> certifiedPaths = new ArrayList<>();
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
        if (context.jobQueue().hasJobsByPathPrefixOutsideCertifiedReplayPaths(
            deletion.op().payload(), certifiedPaths)) return false;
      }
    }
    return true;
  }

  private static List<CommittedNativeEffect> replayAcceptedEffects(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> orderedOps) {
    var scopedOps = orderedOps.stream()
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
      Set<SwitchBufferCapableQueue.SwitchBufferOp> authorizingDeletes) throws IOException {
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
            && !certifyCommittedSurvivor(queue, active, effects, List.of(), next)) {
          throw new IOException("Broad replay found an uncertified projection survivor");
        }
        if (next > index && effect.file() != null && survivors.contains(effect)) {
          if (!certifyCommittedSurvivor(queue, active, effects, List.of(), next)) {
            throw new IOException("Later file survivor lacks its exact accepted certificate");
          }
          files.add(effect.id());
        }
      }
      exclusions.put(deletion.op(), List.copyOf(files));
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
      for (int index = 0; index < ops.size(); index++) {
        var op = ops.get(index);
        if (!"PROJECTION".equalsIgnoreCase(op.op())) continue;
        if (context.approvedGapVersions().contains(op)) continue;
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
      Map<String, Map<String, Integer>> exactDeletes) {}

  private static ReplayDeleteOrder replayDeleteOrder(DrainSwitchBufferContext context,
      List<SwitchBufferCapableQueue.SwitchBufferOp> ops, boolean exactRead) {
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
        Map.copyOf(exactDeletes));
  }

  /** Only later, applied accepted deletes can explain the final absence of an admission. */
  private static boolean hasLaterAcceptedDelete(DrainSwitchBufferContext context,
      ReplayDeleteOrder deleteOrder, int projectionIndex,
      String id, String path, String collection, String generation) {
    int exactIndex = deleteOrder.exactDeletes().getOrDefault(generation == null ? "" : generation,
        Map.of()).getOrDefault(id, -1);
    boolean matches = exactIndex > projectionIndex;
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
          if (payload == null || payload.isBlank() || !("prefix:" + payload).equals(deletion.key())) {
            throw new IllegalArgumentException("Buffered prefix delete has an invalid key");
          }
          matches |= path != null && path.startsWith(
              io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix(payload));
        }
        case "DELETE_COLLECTION" -> {
          if (!IngestCollectionPolicy.isDeletable(payload)
              || !("collection:" + payload).equals(deletion.key())) {
            throw new IllegalArgumentException("Buffered collection delete has an invalid scope");
          }
          matches |= payload.equals(collection);
        }
        default -> { }
      }
    }
    return matches;
  }

  private static boolean isVduBufferKind(String kind) {
    return kind != null && kind.trim().toUpperCase(Locale.ROOT).startsWith("VDU_");
  }

  /** Loads complete declared coverage; an unreadable source cannot certify an empty migration. */
  public static List<ResolvedConfig.FileSource> loadMigrationRoots(
      Path dataDir, ResolvedConfig.Collections collections, ObjectMapper json) throws IOException {
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
    for (Path root : roots.keySet()) requireMigrationRoot(root);
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
    requireEnumerationRunning(context);
    if (context.jobQueue() == null || context.roots() == null) {
      throw new IOException("Missing migration queue or roots");
    }
    int total = 0;
    int batchSize = 2_000;
    ArrayList<JobQueue.EnqueueEntry> batch = new ArrayList<>(batchSize);
    long lastPersistMs = 0L;

    for (ResolvedConfig.FileSource source : context.roots()) {
      Path root = source.path();
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
