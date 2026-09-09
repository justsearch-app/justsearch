/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import io.justsearch.indexerworker.ingest.IngestionOutcome;

/**
 * Interface for job queue operations supporting file ingestion.
 *
 * <p>The job queue persists pending file paths for indexing, supporting:
 * <ul>
 *   <li>Durable persistence across worker restarts</li>
 *   <li>Retry logic for failed jobs</li>
 *   <li>Job state tracking (PENDING, PROCESSING, DONE, FAILED)</li>
 * </ul>
 *
 * <p>The production implementation is SQLite-backed; tests use scoped recording queues.
 */
public interface JobQueue extends Closeable {

  /**
   * An atomically claimed job with its path, collection and admission attribution snapshot.
   *
   * @param path the file path to index
   * @param collection collection tag for the indexed document, or null for default
   * @param provenance admission attribution, or null for an unknown legacy origin
   */
  record IndexJob(Path path, String collection, EnqueueProvenance provenance) {
    /** Legacy/internal fixture with unknown admission attribution. */
    public IndexJob(Path path, String collection) {
      this(path, collection, null);
    }
  }

  /** Sentinel for {@link EnqueueEntry#sizeBytes()} when the file's byte size could not be read. */
  long UNKNOWN_SIZE_BYTES = -1L;

  /** Persistable attribution projected by the Engine bridge; never an authorization input. */
  record EnqueueProvenance(String originator, String transport) {
    public EnqueueProvenance {
      if (originator == null || !List.of("user", "agent", "system").contains(originator)) {
        throw new IllegalArgumentException("Unknown ingestion originator");
      }
      if (transport == null || transport.isBlank() || transport.length() > 256
          || transport.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Invalid ingestion transport");
      }
    }
  }

  /**
   * A path to enqueue plus the byte size observed at enqueue time (tempdoc 813 Slice B).
   *
   * <p>Size rides the enqueue <em>signature</em> rather than a side-channel write on purpose: the
   * jobs-table insert is {@code INSERT OR REPLACE} ({@code SqliteJobQueue.enqueueEntries}), so any
   * column not supplied by the enqueue call is reset on every re-enqueue of the same path. Carrying
   * it in the entry makes "the enqueuer states the size" the only way the column is ever written.
   *
   * @param path the file path to index
   * @param sizeBytes byte size at enqueue time, or {@link #UNKNOWN_SIZE_BYTES} when unknown
   */
  record EnqueueEntry(Path path, long sizeBytes, EnqueueProvenance provenance) {

    /** Maintenance without new attribution preserves the existing durable job attribution. */
    public EnqueueEntry(Path path, long sizeBytes) {
      this(path, sizeBytes, null);
    }

    /** An entry whose size is not known (persisted as NULL, excluded from pending-bytes sums). */
    public static EnqueueEntry ofUnknownSize(Path path) {
      return new EnqueueEntry(path, UNKNOWN_SIZE_BYTES);
    }

    /**
     * Stats {@code path} for its size, degrading to {@link #ofUnknownSize(Path)} when the stat
     * fails. Callers that already hold {@link java.nio.file.attribute.BasicFileAttributes} (the
     * bulk walk paths) should construct directly from {@code attrs.size()} instead — a stat here
     * would be a second, redundant filesystem round-trip.
     */
    public static EnqueueEntry stat(Path path) {
      if (path == null) {
        return ofUnknownSize(null);
      }
      try {
        return new EnqueueEntry(path, java.nio.file.Files.size(path));
      } catch (IOException | RuntimeException e) {
        return ofUnknownSize(path);
      }
    }

    /** Stats a newly admitted path while retaining the caller's explicit attribution. */
    public static EnqueueEntry stat(Path path, EnqueueProvenance provenance) {
      return new EnqueueEntry(path, stat(path).sizeBytes(), provenance);
    }

    /** Wraps each path with an unknown size. */
    public static List<EnqueueEntry> ofUnknownSizes(List<Path> paths) {
      if (paths == null) return List.of();
      List<EnqueueEntry> entries = new java.util.ArrayList<>(paths.size());
      for (Path p : paths) {
        entries.add(ofUnknownSize(p));
      }
      return entries;
    }

    /** Extracts the paths, dropping sizes. */
    public static List<Path> paths(List<EnqueueEntry> entries) {
      if (entries == null) return List.of();
      List<Path> paths = new java.util.ArrayList<>(entries.size());
      for (EnqueueEntry e : entries) {
        if (e != null && e.path() != null) paths.add(e.path());
      }
      return paths;
    }
  }

  /**
   * Aggregate byte weight of remaining indexing work (tempdoc 813 Slice B).
   *
   * <p>{@code knownBytes} sums {@code size_bytes} over PENDING + PROCESSING rows that recorded a
   * size; {@code unknownSizeJobs} counts the PENDING + PROCESSING rows that did not, so a consumer
   * can tell "no work left" from "work left whose weight is unknown" instead of silently treating
   * an unknown as zero.
   *
   * @param knownBytes summed byte size of remaining jobs with a recorded size
   * @param unknownSizeJobs remaining jobs with no recorded size; {@code -1} when the aggregate
   *     could not be computed at all (see {@link #UNAVAILABLE})
   */
  record PendingBytes(long knownBytes, long unknownSizeJobs) {
    /** Nothing known and nothing counted — the default for queues that don't track sizes. */
    public static final PendingBytes EMPTY = new PendingBytes(0L, 0L);

    /**
     * The query FAILED, so nothing may be asserted about remaining weight. Distinct from {@link
     * #EMPTY}, which is the positive claim "nothing is pending": a failed aggregate that returned
     * EMPTY would render as "0 bytes remaining" mid-backlog. The {@code -1} marker keeps that
     * distinction on the wire, where a consumer that only checks {@code knownBytes > 0} still
     * renders nothing.
     */
    public static final PendingBytes UNAVAILABLE = new PendingBytes(0L, -1L);
  }

  /**
   * Summary of recent failures in the queue.
   *
   * <p>Used for surfacing "indexing attempted but failed" signals in status endpoints without
   * requiring log inspection.
   *
   * @param failedCount number of jobs in FAILED state
   * @param lastFailedPath most recently failed job path (normalized), or null if none
   * @param lastFailedErrorMessage last error message for the most recently failed job, or null
   * @param lastFailedAtMs epoch millis of the most recent FAILED transition, or null if unknown
   * @param nextRetryAtMs earliest retry time (epoch millis) among pending jobs with backoff, or null if none
   */
  record FailureSummary(
      long failedCount,
      String lastFailedPath,
      String lastFailedErrorMessage,
      Long lastFailedAtMs,
      Long nextRetryAtMs) {}

  /**
   * Opens the job queue and initializes any required resources.
   *
   * @throws SQLException if database initialization fails
   * @throws IOException if file operations fail
   */
  void open() throws SQLException, IOException;

  /**
   * Opens the queue and forces an integrity check regardless of creation state.
   * Use after restoring from a backup to validate the restored database.
   * Default implementation delegates to {@link #open()}.
   */
  default void openWithIntegrityCheck() throws SQLException, IOException {
    open();
  }

  /**
   * Enqueues file paths for indexing with no collection tag.
   *
   * @param paths List of file paths to index
   * @return Number of jobs accepted
   */
  default int enqueue(List<Path> paths) {
    return enqueue(paths, null);
  }

  /**
   * Enqueues file paths for indexing with an optional collection tag.
   *
   * @param paths List of file paths to index
   * @param collection collection tag for the indexed documents, or null for default
   * @return Number of jobs accepted
   */
  int enqueue(List<Path> paths, String collection);

  /**
   * Enqueues entries carrying the byte size observed at enqueue time (tempdoc 813 Slice B).
   *
   * <p>Default implementation drops the sizes and delegates to {@link #enqueue(List, String)}, so
   * queue implementations that do not persist sizes (in-memory test fakes) keep working unchanged.
   *
   * @param entries paths plus their sizes ({@link #UNKNOWN_SIZE_BYTES} where unknown)
   * @param collection collection tag for the indexed documents, or null for default
   * @return number of jobs accepted
   */
  default int enqueueEntries(List<EnqueueEntry> entries, String collection) {
    return enqueue(EnqueueEntry.paths(entries), collection);
  }

  /**
   * Enqueues sized entries tagged with the directory scan that admitted them (tempdoc 812 D2).
   *
   * <p>The scan id is the same {@code ScanRootProgress.scan_id} the Head reads to subscribe to live
   * scan progress, so a job row remembers which scan produced it and the Head can roll the
   * per-document terminal outcomes up into ONE durable scan-completion audit record.
   *
   * <p>It rides THIS signature — the single jobs-table write path (813 Slice B) — rather than a
   * parallel enqueue overload, for the same reason the size does: the insert is
   * {@code INSERT OR REPLACE}, so any column not supplied by the enqueue call is reset on every
   * re-enqueue of the same path.
   *
   * <p>Tempdoc 941 round 19 (F2): {@code collection} and {@code scanId} are exempt from that reset.
   * A null for either means "nothing to say", NOT "clear it" — an implementation that persists them
   * must carry the row's existing value forward, and overwrite only when the caller states one.
   * The maintenance re-enqueues (the periodic {@code syncDirectory} walk, a watcher event, the
   * retry RPC) do not know which scan admitted a path or which collection owns it, so treating
   * their silence as an erasure blanked both facts on every sync cycle for any file that stays
   * unindexed — which is exactly what a permanently failed file is.
   *
   * <p>Default implementation drops the scan id and delegates to {@link #enqueueEntries(List,
   * String)} — NOT straight to {@link #enqueue(List, String)}: implementations that record sized
   * entries by overriding the two-arg form (the size-asserting test queues) must still see the
   * entries when a scan enqueues through this overload. Queues without a {@code scan_id} column
   * keep the pre-812 behaviour; their rows are keyless and fall back to adjacency grouping.
   *
   * @param entries paths plus their sizes ({@link #UNKNOWN_SIZE_BYTES} where unknown)
   * @param collection collection tag for the indexed documents, or null to keep whatever an
   *     existing row already carries (default collection when there is no row)
   * @param scanId the enqueueing scan's id, or null when not part of a directory scan — an existing
   *     row keeps the scan it already recorded
   * @return number of jobs accepted
   */
  default int enqueueEntries(List<EnqueueEntry> entries, String collection, String scanId) {
    return enqueueEntries(entries, collection);
  }

  /** Enqueues sized entries with no collection tag. */
  default int enqueueEntries(List<EnqueueEntry> entries) {
    return enqueueEntries(entries, null);
  }

  /**
   * Outcome of {@link #reenqueue(EnqueueEntry)} (tempdoc 885 §UD open item 1).
   *
   * @param accepted number of rows the re-enqueue accepted (0 or 1)
   * @param previousState the state the row held immediately before the re-enqueue overwrote it, or
   *     {@code null} when the queue has no row-state authority to report one (there was no row, or
   *     the implementation does not track states). {@code null} means "cannot say" — it is NOT a
   *     state name, so a caller must not print it as one.
   */
  record ReenqueueResult(int accepted, String previousState) {}

  /**
   * Re-enqueues ONE path and reports the state it was in immediately before.
   *
   * <p>Exists because the retry RPC used to state the previous state as a literal {@code "FAILED"}
   * without ever reading the row — already wrong for a {@code PENDING}-in-backoff job and wrong
   * again for {@code RETRY_EXHAUSTED} (tempdoc 885 §UD open item 1). The read has to happen where
   * the write happens: {@code INSERT OR REPLACE} destroys the old state, so a separate read before
   * the call is a race, not an answer.
   *
   * <p>Default implementation performs the re-enqueue and reports {@code null} — a queue that does
   * not expose row states must say "cannot say" rather than guess.
   */
  default ReenqueueResult reenqueue(EnqueueEntry entry) {
    if (entry == null || entry.path() == null) {
      return new ReenqueueResult(0, null);
    }
    return new ReenqueueResult(enqueueEntries(List.of(entry)), null);
  }

  /**
   * Returns the aggregate byte weight of remaining (PENDING + PROCESSING) work.
   *
   * <p>Default is {@link PendingBytes#EMPTY} for implementations that do not record sizes.
   */
  default PendingBytes pendingBytes() {
    return PendingBytes.EMPTY;
  }

  /**
   * Polls for pending jobs and marks them as PROCESSING.
   *
   * @param limit Maximum number of jobs to return
   * @return List of pending index jobs
   */
  List<IndexJob> pollPending(int limit);

  /**
   * Marks a job as successfully completed.
   *
   * @param path The file path that was indexed
   */
  void markDone(Path path);

  /**
   * Records the latest typed ingestion outcome for a path without changing queue state.
   *
   * <p>Implementations that do not persist outcome metadata may ignore this.
   */
  default void recordOutcome(Path path, IngestionOutcome outcome) {}

  /** Marks a job done while recording its latest typed ingestion outcome. */
  default void markDone(Path path, IngestionOutcome outcome) {
    markDone(path, outcome, null);
  }

  /** Marks a job done while recording its latest typed ingestion outcome and ledger context. */
  default void markDone(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
    recordOutcome(path, outcome);
    markDone(path);
  }

  /**
   * Marks multiple jobs as successfully completed in a single batch operation.
   *
   * <p>Default implementation delegates to {@link #markDone(Path)} per path.
   * Implementations should override for efficiency (e.g., single SQL statement).
   *
   * @param paths The file paths that were indexed
   */
  default void markDoneBatch(java.util.Collection<Path> paths) {
    for (Path p : paths) {
      markDone(p);
    }
  }

  /** Marks multiple jobs done with a shared typed outcome. */
  default void markDoneBatch(java.util.Collection<Path> paths, IngestionOutcome outcome) {
    for (Path p : paths) {
      markDone(p, outcome);
    }
  }

  /** Marks multiple jobs done with typed outcomes and per-path ledger context. */
  default void markDoneTransitions(
      java.util.Collection<IngestionLedgerTransition> transitions, IngestionOutcome outcome) {
    if (transitions == null) return;
    for (IngestionLedgerTransition transition : transitions) {
      if (transition != null) {
        markDone(transition.path(), outcome, transition.entry());
      }
    }
  }

  /**
   * Marks a job as failed. If attempts &lt; maxAttempts, the job may be returned to PENDING for
   * retry.
   *
   * <p>Untyped failure. <b>No production caller remains</b> (tempdoc 885 item 21): every ingestion
   * failure path now carries an {@link IngestionOutcome}, and this overload is reached only from
   * tests and the 13 in-test {@code JobQueue} doubles that implement it. It keeps a second
   * terminal rule alive — the {@code MAX_ATTEMPTS} cap, which the typed transient arm no longer
   * uses — so it is pinned by {@code JobQueueRetryLadderTest.untypedFailurePathKeepsTheAttemptsCap}
   * rather than left to drift. Deleting it is tracked in 885's open items; it is an interface
   * change across those 13 doubles, not a local edit, which is why it is not bundled here.
   *
   * @param path The file path that failed
   * @param errorMessage Optional error message
   */
  void markFailed(Path path, String errorMessage);

  /**
   * Marks a job failed with a typed ingestion outcome.
   *
   * <p>Whether the failure is terminal (FAILED state, no further retries) or retryable
   * (returned to PENDING with backoff) is derived from {@code outcome.retryPolicy()}:
   * {@link io.justsearch.indexerworker.ingest.IngestionRetryPolicy#NONE} is terminal,
   * everything else is retryable. Retryable failures still respect the underlying
   * attempts-vs-maxAttempts cap and become FAILED once the cap is hit.
   */
  default void markFailed(Path path, IngestionOutcome outcome) {
    markFailed(path, outcome, null);
  }

  /**
   * Marks a job failed with a typed outcome and ledger context. Terminal vs. retryable is
   * derived from {@code outcome.retryPolicy()}.
   */
  default void markFailed(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
    throw new UnsupportedOperationException(
        "Typed failure requires an outcome-aware queue implementation");
  }

  /** Requeues/defer a job without incrementing attempts while recording its typed outcome. */
  default void defer(Path path, IngestionOutcome outcome) {
    defer(path, outcome, null);
  }

  /** Requeues/defer a job without incrementing attempts while recording context. */
  default void defer(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
    throw new UnsupportedOperationException(
        "Typed defer requires an outcome-aware queue implementation");
  }

  /**
   * Recovers any jobs that were stuck in PROCESSING state (e.g., after a crash).
   *
   * @return Number of jobs recovered
   */
  int recoverStuckJobs();

  /**
   * Recovers only jobs stuck in PROCESSING whose {@code last_updated} is older than
   * {@code olderThanMs} — the periodic liveness reaper (tempdoc 550 Thesis II). Unlike the
   * unconditional {@link #recoverStuckJobs()} (run once at startup), this is safe to run
   * periodically *while the loop is draining*: the age bound spares jobs currently being processed
   * and only re-queues genuinely-orphaned PROCESSING rows (a worker that claimed a job then died
   * mid-process without restarting). Re-indexing is idempotent, so a generous threshold makes a
   * false reset harmless. Default impl ignores the age bound (resets all) for non-SQLite queues.
   *
   * @param olderThanMs reset PROCESSING rows whose last update is at least this many ms ago
   * @return number of jobs requeued
   */
  default int recoverStuckJobs(long olderThanMs) {
    return recoverStuckJobs();
  }

  /**
   * Heartbeat: refresh {@code last_updated} on every row currently in PROCESSING, signalling that a
   * live owner (the indexing loop) is still actively draining them (tempdoc 575 §4.3b / 550 Thesis II —
   * the liveness invariant: "in-flight" derives from a live owner, not mere stream membership). The
   * indexing loop calls this at batch phase boundaries + time-gated during the write loop, so a
   * legitimately-long batch stays fresh while a loop that has DIED stops heartbeating and its rows go
   * stale — which lets {@link #recoverStuckJobs(long)} reclaim orphans on a tight liveness window
   * instead of a coarse "claimed long ago" guess. Default impl is a no-op for non-SQLite queues.
   */
  default void heartbeatProcessing() {}

  /**
   * Returns the current queue depth (pending + processing jobs).
   *
   * @return Number of jobs awaiting completion
   */
  long queueDepth();

  /**
   * Returns the total number of completed jobs.
   *
   * @return Number of DONE jobs
   */
  long completedCount();

  /**
   * Cleans up old completed and failed jobs to prevent storage bloat. Does NOT touch the
   * {@code ingestion_ledger} audit trail — see {@link #cleanupOldLedgerEvents(int)}, which
   * accepts an independent retention horizon so the audit trail can outlive queue rows.
   *
   * @param retentionDays Jobs older than this many days will be deleted
   * @return Number of jobs deleted
   */
  int cleanupOldJobs(int retentionDays);

  /**
   * Cleans up old ingestion ledger events. Decoupled from {@link #cleanupOldJobs(int)} so
   * "Why is this file missing from search?" questions can be answered for files processed
   * longer ago than the queue retention horizon (tempdoc 410 §8). Default implementation is a
   * no-op.
   */
  default int cleanupOldLedgerEvents(int retentionDays) {
    return 0;
  }

  /**
   * Returns a best-effort failure summary for observability.
   *
   * <p>Implementations that do not track failures may return zeros/nulls.
   */
  default FailureSummary failureSummary() {
    return new FailureSummary(0L, null, null, null, null);
  }

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
   *     ingest, a watcher event, or a row written before the {@code scan_id} column existed
   *     (tempdoc 911 §F — the queue has always written this column; the failed-jobs projection just
   *     did not read it)
   */
  record FailedJobInfo(
      String path,
      String errorMessage,
      int attempts,
      long lastUpdatedMs,
      String collection,
      String state,
      String scanId) {}

  /**
   * Privacy-safe append-only ingestion event.
   *
   * <p>Implementations should avoid storing extracted text, stack traces, or absolute-path details
   * beyond the existing queue row. The compact constructor caps every string field at {@link
   * #LEDGER_ENTRY_MAX_FIELD_CHARS} to bound storage even when callers pass adversarial values
   * (e.g., a parser that reports an extreme parser-id string).
   */
  record IngestionLedgerEntry(
      String pathHash,
      String collection,
      Long sourceSizeBytes,
      Long sourceModifiedAtMs,
      String sourceKind,
      String artifactStatus,
      String policyId,
      String parserId,
      String originator,
      String transport) {
    /** Explicitly unknown attribution for legacy entries; never replaced by a later admission. */
    public IngestionLedgerEntry(String pathHash, String collection, Long sourceSizeBytes,
        Long sourceModifiedAtMs, String sourceKind, String artifactStatus, String policyId,
        String parserId) {
      this(pathHash, collection, sourceSizeBytes, sourceModifiedAtMs, sourceKind, artifactStatus,
          policyId, parserId, null, null);
    }

    public IngestionLedgerEntry {
      pathHash = capField(pathHash);
      collection = capField(collection);
      sourceKind = capField(sourceKind);
      artifactStatus = capField(artifactStatus);
      policyId = capField(policyId);
      parserId = capField(parserId);
      originator = capField(originator);
      transport = capField(transport);
    }

    private static String capField(String value) {
      if (value == null) return null;
      return value.length() <= LEDGER_ENTRY_MAX_FIELD_CHARS
          ? value
          : value.substring(0, LEDGER_ENTRY_MAX_FIELD_CHARS);
    }
  }

  /**
   * Per-string-field cap on {@link IngestionLedgerEntry}. Bounds the on-disk row size for every
   * unbounded string field (parser id, policy id, source kind, artifact status, collection, path
   * hash). Set generously enough that legitimate values fit unchanged but tightly enough that an
   * adversarial parser cannot drive ledger-row size unbounded.
   */
  int LEDGER_ENTRY_MAX_FIELD_CHARS = 256;

  /** Path plus privacy-safe metadata for an outcome transition. */
  record IngestionLedgerTransition(Path path, IngestionLedgerEntry entry) {}

  /** Export-safe ingestion ledger row. Raw job paths are intentionally omitted. */
  record IngestionEventView(
      long id,
      String pathHash,
      String collection,
      String outcomeClass,
      String reasonCode,
      String retryPolicy,
      String diagnosticSummary,
      long observedAtMs,
      Long sourceSizeBytes,
      Long sourceModifiedAtMs,
      String sourceKind,
      String artifactStatus,
      String policyId,
      String parserId) {}

  /** Aggregated export-safe outcome count. */
  record IngestionOutcomeSummary(
      String outcomeClass, String reasonCode, String retryPolicy, long count, long lastObservedAtMs) {}

  /** Records an ingestion outcome history event without changing queue state. */
  default void recordIngestionEvent(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {}

  /** Returns recent privacy-safe ingestion events, newest first. */
  default List<IngestionEventView> recentIngestionEvents(int limit) {
    return List.of();
  }

  /**
   * Returns true if the ledger already has an event for {@code pathHash} matching
   * {@code reasonCode} observed at-or-after {@code sinceMs}. Used by writers (e.g., cloud
   * placeholder detection) to dedup repeated observations of the same condition for the same
   * source. Default implementation returns false (no dedup).
   */
  default boolean hasRecentLedgerEvent(String pathHash, String reasonCode, long sinceMs) {
    return false;
  }

  /** Returns privacy-safe outcome counts since {@code sinceMs}; 0 means all retained events. */
  default List<IngestionOutcomeSummary> ingestionOutcomeSummary(long sinceMs) {
    return List.of();
  }

  /**
   * Lists jobs in FAILED state, ordered by most recent failure first.
   *
   * @param limit maximum rows to return (capped at 1000; 0 = server default 100)
   * @return list of failed jobs (may be empty)
   */
  default List<FailedJobInfo> listFailedJobs(int limit) {
    return List.of();
  }

  /**
   * Lists FAILED jobs under the given path prefix (tempdoc 599 §16/B1) — the per-folder twin of
   * {@link #listFailedJobs(int)}, backing the folder-scoped "failed files" drill-down. Default
   * falls back to the unscoped list.
   *
   * @param pathPrefix the watched-root path prefix (will be normalized)
   * @param limit maximum rows to return (capped at 1000; 0 = server default 100)
   * @return failed jobs under the prefix (may be empty)
   */
  default List<FailedJobInfo> listFailedJobsByPathPrefix(String pathPrefix, int limit) {
    return listFailedJobs(limit);
  }

  /**
   * Deletes all jobs in FAILED state.
   *
   * @return number of jobs deleted
   */
  default int clearFailedJobs() {
    return 0;
  }

  /**
   * Deletes all jobs from the queue regardless of state. Used by profiling reset.
   *
   * @return number of jobs deleted
   */
  default int clearAll() {
    return 0;
  }

  // --- Fine-grained state counts ---

  /**
   * Breakdown of job counts by sub-state.
   *
   * <p>Provides finer granularity than {@link #queueDepth()} by distinguishing PENDING-ready
   * (eligible for immediate processing) from PENDING-backoff (waiting for retry delay).
   *
   * @param pendingCount total PENDING jobs (ready + backoff)
   * @param pendingReadyCount PENDING jobs eligible for immediate processing
   * @param processingCount jobs currently being processed
   * @param doneCount completed jobs
   * @param failedCount permanently failed jobs
   */
  record JobStateCounts(
      long pendingCount,
      long pendingReadyCount,
      long processingCount,
      long doneCount,
      long failedCount) {
    /** PENDING jobs that are in backoff (waiting for retry delay). */
    public long pendingBackoffCount() {
      return Math.max(0L, pendingCount - pendingReadyCount);
    }
  }

  /**
   * Returns a breakdown of job counts by sub-state.
   *
   * <p>Default implementation provides a best-effort approximation from existing methods.
   * Implementations with richer state tracking should override for accurate counts.
   */
  default JobStateCounts jobStateCounts() {
    long depth = queueDepth();
    long completed = completedCount();
    FailureSummary fs = failureSummary();
    return new JobStateCounts(depth, depth, 0L, completed, fs.failedCount());
  }

  // --- Targeted delete operations ---

  /**
   * Deletes all jobs whose path starts with the given prefix, regardless of state.
   *
   * @param pathPrefix the path prefix to match
   * @return number of jobs deleted
   */
  default int deleteByPathPrefix(String pathPrefix) {
    return 0;
  }

  /**
   * Deletes the job with exactly the given path, regardless of state.
   *
   * @param path the exact path to match
   * @return number of jobs deleted
   */
  default int deleteByExactPath(String path) {
    return 0;
  }

  /**
   * Counts jobs whose path starts with the given prefix, broken down by sub-state. Backs the
   * per-folder indexing-status projection (tempdoc 599 §9.2): the Head folds the in-flight
   * (PENDING+PROCESSING) and FAILED counts under a watched root's path prefix into the folder row.
   *
   * <p>Counts are over the committed jobs table; staleness is bounded by the
   * {@link #recoverStuckJobs(long)} reaper, so a returned PROCESSING count never includes
   * reaper-swept zombie rows (tempdoc 599 §11/U4). Returns all-zero on error or empty prefix.
   *
   * @param pathPrefix the path prefix to match (will be normalized)
   * @return per-state counts under the prefix
   */
  default JobStateCounts countByPathPrefix(String pathPrefix) {
    return new JobStateCounts(0L, 0L, 0L, 0L, 0L);
  }

  /**
   * Snapshot of queue database health for observability surfaces.
   *
   * <p>All timestamps are epoch millis; 0 means "not set / unknown".
   */
  record QueueDbHealthSnapshot(
      boolean healthy,
      long lastQuickCheckAtMs,
      boolean lastQuickCheckOk,
      long lastBackupAtMs,
      long lastDbErrorAtMs) {}

  /**
   * Returns a snapshot of queue DB health, or {@code null} if the implementation does not
   * support health tracking.
   */
  default QueueDbHealthSnapshot queueDbHealthSnapshot() {
    return null;
  }

  /**
   * Flush durable queue state before an application upgrade handoff.
   *
   * <p>Implementations that do not use a write-ahead log may return {@code true}. A false result
   * blocks the handoff.
   */
  /**
   * Drains the write-ahead log into the database file.
   *
   * <p>Renamed off {@code checkpointForUpgrade} at lane F stage B item B5. The old name said WHO
   * called it rather than what it does, and it was load-bearing: the ordinary close did not
   * checkpoint at all, so an Engine that exited any way other than through the upgrade barrier
   * left its WAL for the next start to replay. Design 7.3 step 7 makes the checkpoint part of
   * every ordered close.
   */
  default boolean checkpointWal() {
    return true;
  }

  /**
   * Slice 445: optional change-feed for the indexing-jobs collection. Backs the
   * {@code core.indexing-jobs} TABULAR Resource via SSE_STREAM. Implementations
   * that can't expose per-row mutations (e.g., in-memory test queues) return
   * {@link Optional#empty()} and the gRPC handler degrades to UNIMPLEMENTED.
   */
  default Optional<IndexingJobChangeFeed> indexingJobChangeFeed() {
    return Optional.empty();
  }
}
