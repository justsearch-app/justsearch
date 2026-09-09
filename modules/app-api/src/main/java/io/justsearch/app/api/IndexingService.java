/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.core.context.EngineContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Index management surface exposed to desktop clients.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Enumerate active watch roots.</li>
 *   <li>Add and remove watched roots at runtime.</li>
 * </ul>
 */
public interface IndexingService {
  /**
   * Immutable representation of a watched root with optional timestamp and walk error.
   *
   * @param walkCompleted whether the folder's filesystem walk has terminated at least once
   *     (tempdoc 599 Fix 1). Distinguishes "walked, nothing to index" (walkCompleted=true, no
   *     lastIndexed, no walkError → the UI's "empty" state) from "walk in progress / never walked"
   *     (walkCompleted=false → "scanning"). The convenience constructors default it from the
   *     presence of a timestamp/error so existing callers keep their meaning.
   */
  record WatchedRoot(
      String collection,
      Path path,
      Instant lastIndexed,
      String walkError,
      boolean walkCompleted,
      boolean deleteDetectionUnverified,
      int driftOrphanCount,
      long driftOrphanAtMs,
      Instant lastVerifiedAt) {
    /**
     * Constructor without the §Recency last-verified timestamp (tempdoc 626). Defaults to {@code null}
     * (never verified); used by pre-Recency call sites.
     */
    public WatchedRoot(
        String collection,
        Path path,
        Instant lastIndexed,
        String walkError,
        boolean walkCompleted,
        boolean deleteDetectionUnverified,
        int driftOrphanCount,
        long driftOrphanAtMs) {
      this(
          collection,
          path,
          lastIndexed,
          walkError,
          walkCompleted,
          deleteDetectionUnverified,
          driftOrphanCount,
          driftOrphanAtMs,
          null);
    }
    /**
     * Constructor without the drift-corrected orphan-prune signal (tempdoc 626 §Axis-C). Defaults to
     * no recorded prune ({@code 0, 0}).
     */
    public WatchedRoot(
        String collection,
        Path path,
        Instant lastIndexed,
        String walkError,
        boolean walkCompleted,
        boolean deleteDetectionUnverified) {
      this(collection, path, lastIndexed, walkError, walkCompleted, deleteDetectionUnverified, 0, 0L);
    }
    /**
     * Constructor without the delete-detection flag (tempdoc 626 §Axis-C). Defaults to verified
     * ({@code false}); used by every pre-626 call site.
     */
    public WatchedRoot(
        String collection, Path path, Instant lastIndexed, String walkError, boolean walkCompleted) {
      this(collection, path, lastIndexed, walkError, walkCompleted, false);
    }
    /** Constructor without the explicit walk-completed flag (derived from lastIndexed/walkError). */
    public WatchedRoot(String collection, Path path, Instant lastIndexed, String walkError) {
      this(collection, path, lastIndexed, walkError, lastIndexed != null || walkError != null);
    }
    /** Constructor without walk error. */
    public WatchedRoot(String collection, Path path, Instant lastIndexed) {
      this(collection, path, lastIndexed, null, lastIndexed != null);
    }
    /** Constructor without timestamp or walk error for backward compatibility. */
    public WatchedRoot(String collection, Path path) {
      this(collection, path, null, null, false);
    }
  }

  /** List of paths currently watched for indexing. */
  List<Path> getWatchedPaths(EngineContext engineContext);

  /** List of watched roots with collection metadata. */
  default List<WatchedRoot> getWatchedRoots(EngineContext engineContext) {
    // Fallback for implementations that only support primary collection.
    return getWatchedPaths(engineContext).stream().map(p -> new WatchedRoot(null, p)).toList();
  }

  /** Add a new watch root (primary collection). */
  void addWatchedPath(Path path, EngineContext engineContext);

  /** Add a new watch root for a specific collection. */
  default void addWatchedRoot(String collection, Path path, EngineContext engineContext) {
    addWatchedPath(path, engineContext);
  }

  /**
   * Stop watching the given root and delete indexed documents.
   *
   * <p>Implementations may reject removal of static/configured roots.
   *
   * @param path the path to stop watching
   * @return number of deleted jobs, or -1 on error
   */
  int removeWatchedPath(Path path, EngineContext engineContext);

  /** Stop watching the given root for a specific collection. */
  default int removeWatchedRoot(String collection, Path path, EngineContext engineContext) {
    return removeWatchedPath(path, engineContext);
  }

  // =========================================================================
  // Delete operations (best-effort; used by explicit maintenance actions)
  // =========================================================================

  /**
   * Deletes all documents whose doc_id/path starts with the given prefix.
   *
   * <p>Implementations must delegate deletion to the Worker/index layer (do not touch Lucene/queue DB directly).
   *
   * @param pathPrefix absolute path prefix (directory) to delete
   * @return number of deleted jobs (best-effort), or 0 on failure
   */
  default int deleteDocsByPathPrefix(Path pathPrefix, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Deletes a single document by exact doc_id (normalized absolute path string).
   *
   * <p>Implementations must delegate deletion to the Worker/index layer (do not touch Lucene/queue DB directly).
   *
   * @param docId document id (normalized absolute path)
   * @return true if the Worker accepted the delete request
   */
  default boolean deleteDocById(String docId, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Tempdoc 811 (C-2a) — deletes every document carrying the given collection tag. This is the
   * removal route for ad-hoc ingests: {@link #deleteDocsByPathPrefix} is watched-root-prefix driven
   * and can never reach a document ingested from a path under no watched root.
   *
   * <p>Callers must gate on {@code IngestCollectionPolicy.isDeletable} first — this method deletes
   * exactly what it is told to.
   *
   * @param collection the collection tag
   * @return number of documents deleted, or -1 on error
   */
  default int deleteDocsByCollection(String collection, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Re-index all currently watched roots (best-effort).
   *
   * <p>Implementations should scan existing files under each watched root and submit batches to the
   * indexing pipeline. May be expensive for large trees.
   */
  default void reindex(EngineContext engineContext) {
    reindexWatchedRoots(engineContext);
  }

  /**
   * Re-index all currently watched roots (best-effort).
   *
   * <p>Implementations should scan existing files under each watched root and submit batches to the
   * indexing pipeline. May be expensive for large trees.
   */
  default void reindexWatchedRoots(EngineContext engineContext) {
    reindexWatchedRoots(false, engineContext);
  }

  /**
   * Re-index all currently watched roots with optional force flag.
   *
   * <p>When force=true, bypasses the "file unchanged" optimization and re-extracts
   * all documents even if file modification time hasn't changed. Use this after
   * schema changes to ensure all documents are re-indexed with the new schema.
   *
   * @param force if true, bypass unchanged check and force re-extraction
   */
  default void reindexWatchedRoots(boolean force, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Tempdoc 626 §Recency (Move C) — verify/reconcile a SINGLE watched root identified by its {@code
   * pathHash} (the privacy-safe wire identifier — ADR-0028; raw paths never cross the wire). The
   * implementation resolves the hash to the real path Head-side and runs a {@code force} reconcile
   * (re-prune orphans + re-walk), which re-converges the root and refreshes its per-root verification
   * state (clears {@code deleteDetectionUnverified}, stamps {@code lastVerifiedAt}). This is the
   * granularity-matched recovery for the {@code index.drift-unknown} condition — scoped to one folder
   * instead of a corpus-wide reindex.
   *
   * @param pathHash SHA-256 hex of the watched root's absolute path
   * @param force when true (the recovery default), bypass the mtime fast-path and fully re-converge
   * @return true if a matching root was found and the reconcile was dispatched
   */
  default boolean reconcileRoot(String pathHash, boolean force, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  // =========================================================================
  // Migration controls (Phase H)
  // =========================================================================

  /** Projection of the migration response; the protocol response remains the result authority. */
  record MigrationOutcome(boolean accepted, boolean restartRequired) {}

  /** Starts a Blue/Green migration and reports whether an Engine restart is required. */
  default MigrationOutcome startMigration(String reason, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Requests cutover (best-effort). Implementations may no-op if migration is not in progress. */
  default MigrationOutcome requestCutover(boolean forceSwitching, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Rolls back to the previous generation and reports its Engine restart requirement. */
  default MigrationOutcome rollbackMigration(EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Pauses migration orchestration (enumerator + cutover monitor). */
  default boolean pauseMigration(String reason, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Resumes migration orchestration. */
  default boolean resumeMigration(EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Outcome of an index GC invocation. Carries the worker-side counts so the
   * Operation handler's structured-output map (and the REST controller's response
   * body) can surface marked / pruned deltas — both are already populated by the
   * worker's gRPC response (see {@code MigrationOps.runIndexGc}); the prior
   * {@code boolean} return shape was dropping them at the service boundary.
   *
   * <p>Per slice 484 §3.6 / observations.md `core.index-gc` closure.
   *
   * @param accepted whether the worker accepted the GC request (false = rejected)
   * @param markedCount number of generations newly marked for deletion (0 if rejected)
   * @param prunedCount number of marked generations physically removed (0 if rejected)
   * @param error worker error message when {@code accepted=false}; empty string on success
   */
  record IndexGcOutcome(boolean accepted, int markedCount, int prunedCount, String error) {
    public IndexGcOutcome {
      java.util.Objects.requireNonNull(error, "error");
    }

    /** Sentinel for the unavailable-service code path. */
    public static IndexGcOutcome unavailable() {
      return new IndexGcOutcome(false, 0, 0, "Indexing service unavailable");
    }
  }

  /**
   * Runs best-effort index GC (mark + prune) for old generations/backups. Returns
   * the worker's structured outcome so callers can surface marked / pruned counts
   * (handler's structured-output map; REST response body).
   */
  default IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Outcome of a settle (purge deleted-but-unmerged documents from the active index).
   *
   * <p>Tempdoc 931 §E item 10. The before/after counts are the point of the call, not decoration:
   * a paired evaluation records them to show that both arms queried an index with the same merge
   * state, since tombstones stay in the BM25 collection statistics.
   *
   * @param accepted whether the worker performed the settle (false = refused)
   * @param maxDocBefore maxDoc (live + tombstones) before the settle
   * @param numDocsBefore numDocs (live only) before the settle
   * @param maxDocAfter maxDoc after the settle
   * @param numDocsAfter numDocs after the settle
   * @param segmentsAfter leaf-segment count after the settle
   * @param elapsedMs wall time the worker spent merging and committing
   * @param error worker error message when {@code accepted=false}; empty string on success
   */
  record SettleIndexOutcome(
      boolean accepted,
      long maxDocBefore,
      long numDocsBefore,
      long maxDocAfter,
      long numDocsAfter,
      int segmentsAfter,
      long elapsedMs,
      String error) {
    public SettleIndexOutcome {
      java.util.Objects.requireNonNull(error, "error");
    }

    /** Sentinel for a refusal, carrying no counts. */
    public static SettleIndexOutcome refused(String error) {
      return new SettleIndexOutcome(false, 0L, 0L, 0L, 0L, 0, 0L, error);
    }
  }

  /**
   * Purges deleted-but-unmerged documents from the ACTIVE index and returns the before/after
   * document counts.
   *
   * @param expungeDeletesOnly when true (the default caller shape) only expunge deletes; when
   *     false, also force-merge down to {@code maxSegments} segments
   * @param maxSegments target segment count for the force-merge branch (0 = worker default of 1)
   */
  default SettleIndexOutcome settleIndex(boolean expungeDeletesOnly, int maxSegments, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  // =========================================================================
  // Failed job inspection
  // =========================================================================

  /**
   * A permanently failed job with its error details.
   *
   * @param path normalized file path
   * @param errorMessage last error message, or null
   * @param attempts total attempts made
   * @param lastUpdatedMs epoch millis of last state transition
   * @param collection collection tag, or null for default
   * @param state terminal state: {@code FAILED} or {@code RETRY_EXHAUSTED} (tempdoc 885 item 21b)
   * @param scanId the directory scan that enqueued this job, or {@code ""}/null for a single-file
   *     ingest, a watcher event, or a pre-{@code scan_id} row — the same vocabulary {@link
   *     io.justsearch.app.api.indexing.IndexingJobView#scanId()} defines, so the failed-jobs
   *     projection reports a checked value instead of a placeholder (tempdoc 911 §F)
   */
  record FailedJobInfo(
      String path,
      String errorMessage,
      int attempts,
      long lastUpdatedMs,
      String collection,
      String state,
      String scanId) {}

  /** Lists jobs in FAILED state, ordered by most recent failure first. */
  default List<FailedJobInfo> listFailedJobs(int limit, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Lists FAILED jobs under a watched-root path prefix (tempdoc 599 §16/B1) — the per-folder
   * "failed files" drill-down. Returns empty when the Worker is unavailable.
   */
  default List<FailedJobInfo> listFailedJobsByPathPrefix(Path pathPrefix, int limit, EngineContext engineContext) {
    return List.of();
  }

  /** Deletes all jobs in FAILED state. */
  default int clearFailedJobs(EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Per-folder indexing-job counts under a watched root's path prefix (tempdoc 599 §9.2). The
   * folder-status projection folds {@code inFlight} (PENDING+PROCESSING) and {@code failed} into the
   * Library row, so a folder can report a truthful "indexing · N remaining → searchable" state
   * derived from job drain rather than the walk-completion timestamp.
   *
   * @param inFlight PENDING + PROCESSING jobs under the prefix (in-flight; reaper-bounded)
   * @param failed permanently FAILED jobs under the prefix
   * @param coverage per-root enrichment coverage from the index (tempdoc 813 §1c)
   */
  record JobCounts(long inFlight, long failed, RootCoverage coverage) {
    public JobCounts {
      coverage = coverage == null ? RootCoverage.zero() : coverage;
    }

    /** All-zero sentinel for the unavailable / empty-prefix path. */
    public static JobCounts zero() {
      return new JobCounts(0L, 0L, RootCoverage.zero());
    }
  }

  /**
   * Per-watched-root enrichment coverage counted from the index (tempdoc 813 §1c/§13). Lets a
   * Library folder row say "keyword search ready · semantic search still catching up · N%" instead
   * of only reporting the index-wide number. Queue drain ({@link JobCounts#inFlight()}) covers the
   * FIRST phase (a file becomes keyword-searchable); these counts cover the SECOND (enrichment
   * backfill), which carries no per-root job rows at all — the backfill selects index-wide by status.
   *
   * <p>Denominator discipline: parent-stage totals exclude chunk documents, and each stage counts
   * only the documents that CARRY its status field — an absent status field means the stage does
   * not apply to that document (post-798), and since the backfill selects by status value, such a
   * document would otherwise sit in a denominator nothing can ever drain. The chunk tier has its
   * own denominator and must never be presented as "N of M files". Numerator discipline: "settled"
   * = terminal state (COMPLETED + COMPLETED_EMPTY where defined + FAILED), not success — otherwise
   * a permanently failed document pins the folder below 100% forever.
   *
   * @param parentDocsTotalEmbedding parent docs under the prefix carrying an embedding status
   * @param parentDocsSettledEmbedding parent docs whose embedding stage is terminal
   * @param parentDocsTotalSplade parent docs under the prefix carrying a SPLADE status
   * @param parentDocsSettledSplade parent docs whose SPLADE stage is terminal
   * @param parentDocsTotalNer parent docs under the prefix carrying a NER status
   * @param parentDocsSettledNer parent docs whose NER stage is terminal
   * @param chunkDocsTotal chunk docs under the prefix carrying a chunk-embedding status
   * @param chunkDocsSettled chunk docs whose chunk-embedding stage is terminal
   */
  record RootCoverage(
      long parentDocsTotalEmbedding,
      long parentDocsSettledEmbedding,
      long parentDocsTotalSplade,
      long parentDocsSettledSplade,
      long parentDocsTotalNer,
      long parentDocsSettledNer,
      long chunkDocsTotal,
      long chunkDocsSettled) {

    private static final RootCoverage ZERO = new RootCoverage(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

    /** All-zero sentinel for the unavailable / empty-prefix path. */
    public static RootCoverage zero() {
      return ZERO;
    }
  }

  /**
   * Counts in-flight and failed indexing jobs under the given watched-root path prefix. Returns
   * {@link JobCounts#zero()} when the Worker is unavailable (a degraded folder row, never a throw).
   */
  default JobCounts countJobsByPathPrefix(Path pathPrefix, EngineContext engineContext) {
    return JobCounts.zero();
  }

  /**
   * Privacy-safe ingestion ledger events from the Worker (tempdoc 410 §12). Each row carries a
   * SHA-256 path-hash, never the raw path. Implementations that don't support the ledger may
   * return an empty list rather than throwing.
   */
  default List<java.util.Map<String, Object>> recentIngestionEvents(int limit, EngineContext engineContext) {
    return List.of();
  }

  /**
   * Aggregated ingestion outcome counts since {@code sinceMs} (epoch ms; 0 = all retained).
   * See {@link #recentIngestionEvents(int, EngineContext)}.
   */
  default List<java.util.Map<String, Object>> ingestionOutcomeSummary(long sinceMs, EngineContext engineContext) {
    return List.of();
  }

  /**
   * Scoped reverse-lookup of {@code pathHash → path} (ADR-0028, tempdoc 419 T5.3). Returns a
   * map with keys {@code found} (boolean), {@code path} (string, present iff found),
   * {@code lastSeenAtMs} (long), {@code removedAtMs} (long, 0 when still present). Backs the
   * single-purpose endpoint {@code POST /api/library/resolve-hash}; diagnostic export endpoints
   * MUST NOT call this method.
   */
  default java.util.Map<String, Object> resolvePathHash(String pathHash, EngineContext engineContext) {
    return java.util.Map.of("found", false);
  }

  /**
   * Slice 445: cancel an in-flight indexing job by its {@code pathHash} (SHA-256 hex of the
   * absolute normalized path). The worker resolves the hash, marks the row terminal with a
   * {@code CANCELLED} outcome, and the change-feed emits an UPDATE delta. Returns a map with
   * {@code cancelled} (boolean) and {@code previousState} (string, diagnostic).
   */
  default java.util.Map<String, Object> cancelIndexingJob(String pathHash, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Slice 445: retry a FAILED indexing job by its {@code pathHash}. The worker re-enqueues the
   * row as PENDING, replacing any existing FAILED entry. Returns a map with {@code retried}
   * (boolean) and {@code previousState} (string, diagnostic).
   */
  default java.util.Map<String, Object> retryIndexingJob(String pathHash, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Clears all watched roots (stops watchers, clears state, persists). Used by profiling reset. */
  default void clearAllRoots(EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Resets index state on the Worker via gRPC. Used by profiling reset. Returns true on success. */
  default boolean resetIndex(EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Tempdoc 406 — admin-triggered runtime swap. Drains current ingest runtime, opens
   * a fresh one on the same path. Returns swap duration in ms.
   *
   * @param reason low-cardinality telemetry tag (e.g., "admin_triggered")
   */
  default long reloadRuntime(String reason, EngineContext engineContext) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Flush pending indexing work (best effort). Implementations may no-op or throw if unavailable. */
  void flush(EngineContext engineContext);

  /**
   * Null Object for environments where the Worker isn't connected. Returns empty
   * collections from read methods and throws {@code UnsupportedOperationException} from
   * mutating methods. Used by {@code LocalApiServer} Builder fallback and by test fixtures
   * that need a non-null IndexingService. Tempdoc 519 F2 (refined per §22): kept as the Null
   * Object pattern.
   */
  static IndexingService unavailable() {
    return new IndexingService() {
      @Override
      public List<Path> getWatchedPaths(EngineContext engineContext) {
        return List.of();
      }

      @Override
      public void addWatchedPath(Path path, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public void addWatchedRoot(String collection, Path path, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public int removeWatchedPath(Path path, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public int removeWatchedRoot(String collection, Path path, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public int deleteDocsByPathPrefix(Path pathPrefix, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public boolean deleteDocById(String docId, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public void reindexWatchedRoots(boolean force, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public MigrationOutcome startMigration(String reason, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public MigrationOutcome requestCutover(boolean forceSwitching, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public MigrationOutcome rollbackMigration(EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public boolean pauseMigration(String reason, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public boolean resumeMigration(EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public SettleIndexOutcome settleIndex(boolean expungeDeletesOnly, int maxSegments, EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public void flush(EngineContext engineContext) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }
    };
  }
}
