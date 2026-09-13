/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionRetryLadder;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.telemetry.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed durable job queue for file ingestion.
 *
 * <p>This queue persists pending file paths to disk, ensuring durability across
 * worker restarts. All database access is serialized using a lock to prevent
 * concurrent write conflicts.
 *
 * <p>Job states:
 * <ul>
 *   <li>PENDING - Awaiting processing</li>
 *   <li>PROCESSING - Currently being indexed</li>
 *   <li>DONE - Successfully indexed</li>
 *   <li>FAILED - Failed after max attempts</li>
 * </ul>
 */
public final class SqliteJobQueue implements SwitchBufferCapableQueue {
  private static final Logger log = LoggerFactory.getLogger(SqliteJobQueue.class);

  /**
   * Maximum untyped-failure attempts before marking a job {@code FAILED}.
   *
   * <p>Tempdoc 885 item 21d: ONE home. {@code KnowledgeServer} used to pass a bare literal {@code 3}
   * at the single construction site, so the cap existed in two places that agreed by coincidence.
   * It is also no longer the terminal signal for a classified transient outcome — see {@link
   * #markFailedWithOutcome}; it governs only the untyped {@link #markFailed(Path, String)} path.
   */
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  /** Job states on the {@code jobs} table. The vocabulary the change-feed projects onto the wire. */
  static final String STATE_PENDING = "PENDING";

  static final String STATE_PROCESSING = "PROCESSING";

  static final String STATE_DONE = "DONE";

  static final String STATE_FAILED = "FAILED";

  /**
   * Tempdoc 885 item 21b — a transient failure run that outlived the seven-day retry window. A
   * VISIBLE terminal state, distinct from {@code FAILED} (which means "this file cannot be
   * parsed"): {@code RETRY_EXHAUSTED} means "we kept trying for a week and never got to read it".
   * Reset by anything that re-enqueues the path (a rescan, a watcher event on an mtime/size
   * change), because the enqueue statement is {@code INSERT OR REPLACE}.
   */
  static final String STATE_RETRY_EXHAUSTED = "RETRY_EXHAUSTED";

  /** SQLite busy timeout in milliseconds. */
  private static final int BUSY_TIMEOUT_MS = 5000;

  /**
   * Minimum free disk space (50 MB) required to accept new enqueues. Below this threshold the
   * queue refuses enqueue() to prevent SQLite write-during-full-disk corruption. Failure mode
   * surfaces today as a SQLITE_IOERR_FULL caught and logged, but pre-checking is cheaper and
   * fail-closed.
   */
  private static final long MIN_FREE_DISK_BYTES = 50L * 1024 * 1024;

  private final Path dbPath;
  private final ReentrantLock lock = new ReentrantLock();
  private final int maxAttempts;
  private Connection connection;
  private Throwable connectionFailure;
  // Only live processing capabilities: object identity prevents an older commit from finishing
  // a replacement claim for the same path. Dead JVMs cannot deliver callbacks after restart.
  private final Map<String, IndexJob> activeClaims = new HashMap<>();
  private boolean existedBeforeOpen;
  private boolean forceIntegrityCheck;

  /**
   * Slice 445 producer scaffolding. Captures per-row INSERT/UPDATE/DELETE on the
   * {@code jobs} table via SQLite update + commit hooks and broadcasts typed
   * {@link IndexingJobsChangeStream.Delta} events to subscribers (e.g., the
   * subscription that backs the {@code core.indexing-jobs} TABULAR Resource — a
   * server-streaming RPC until lane F stage A item A7, a bounded in-process
   * hand-off since). Lazily attached in {@link #open()}; detached in {@link #close()}.
   */
  private IndexingJobsChangeStream changeStream;

  // ==================== Health Tracking ====================

  // QueueDbHealthSnapshot record moved to JobQueue interface (Step 3 module split).

  private volatile boolean dbHealthy = true;
  private volatile long lastQuickCheckAtMs;
  private volatile boolean lastQuickCheckOk;
  private volatile long lastBackupAtMs;
  private volatile long lastDbErrorAtMs;

  /**
   * Hook called after each migration step (for testing migration rollback).
   * If this throws, the migration should roll back.
   */
  @FunctionalInterface
  interface MigrationStepHook {
    void afterStep(int version) throws SQLException;
  }

  private final MigrationStepHook migrationStepHook;
  private final SqliteQueueSwitchBufferOps switchBufferOps;

  /** Tempdoc 885 item 21e — enqueue/dequeue rates and lock-wait, RISK-002's instrument. */
  private final QueueThroughputMeters meters = new QueueThroughputMeters();

  /** Tempdoc 885 item 21e — late-bound per-outcome counter sink; null until the catalog exists. */
  private volatile java.util.function.Consumer<String> outcomeObserver;

  /**
   * Creates a SqliteJobQueue backed by the specified SQLite database file.
   *
   * @param dbPath Path to the jobs.db file
   */
  public SqliteJobQueue(Path dbPath) {
    this(dbPath, DEFAULT_MAX_ATTEMPTS, null, null);
  }

  /**
   * Creates a SqliteJobQueue with a custom max retry count.
   *
   * @param dbPath Path to the jobs.db file
   * @param maxAttempts Maximum retry attempts before failing a job
   */
  public SqliteJobQueue(Path dbPath, int maxAttempts) {
    this(dbPath, maxAttempts, null, null);
  }

  /**
   * Creates a SqliteJobQueue with a custom max retry count and a write-failure callback.
   *
   * @param dbPath Path to the jobs.db file
   * @param maxAttempts Maximum retry attempts before failing a job
   * @param onSwitchBufferWriteFailure Optional callback invoked once per switch-buffer write
   *     failure (may be null). Tempdoc 417 Phase 3c: replaces the legacy
   *     {@code Telemetry.Counter} parameter to decouple this layer from the soon-to-be-retired
   *     {@code Telemetry} interface.
   */
  public SqliteJobQueue(Path dbPath, int maxAttempts, Runnable onSwitchBufferWriteFailure) {
    this(dbPath, maxAttempts, onSwitchBufferWriteFailure, null);
  }

  /**
   * Creates a SqliteJobQueue with all options (including test hook).
   *
   * <p>The migrationStepHook is package-private for testing migration rollback scenarios.
   */
  SqliteJobQueue(Path dbPath, int maxAttempts, Runnable onSwitchBufferWriteFailure,
                 MigrationStepHook migrationStepHook) {
    this.dbPath = dbPath;
    this.maxAttempts = maxAttempts;
    this.migrationStepHook = migrationStepHook;
    this.switchBufferOps =
        new SqliteQueueSwitchBufferOps(
            lock, this::ensureOpenAndGetConnection, onSwitchBufferWriteFailure,
            this::recordDbError);
  }

  private Connection ensureOpenAndGetConnection() {
    ensureOpen();
    return connection;
  }

  /**
   * Tempdoc 885 item 21e — the throughput + lock-wait instrument RISK-002 has been "monitoring"
   * without since tempdoc 269. Owned by the queue (it is the only thing that knows when a row is
   * admitted or claimed) and read by {@code WorkerOpsMetricCatalog}'s suppliers at flush time.
   */
  public QueueThroughputMeters throughputMeters() {
    return meters;
  }

  /**
   * Late-bound per-outcome counter sink, wired by {@code KnowledgeServer} once the telemetry
   * registry exists. Mirrors {@code onSwitchBufferWriteFailure}: the queue is constructed long
   * before the catalog, so the sink no-ops until it is set rather than the queue holding a
   * half-built catalog.
   */
  public void setOutcomeObserver(java.util.function.Consumer<String> observer) {
    this.outcomeObserver = observer;
  }

  private void recordOutcomeMetric(IngestionOutcome outcome) {
    var observer = this.outcomeObserver;
    if (observer == null) {
      return;
    }
    try {
      // NOT outcomeClassName(): that returns the literal "null" for logging, which would reach the
      // metric as a tag VALUE "null" instead of the schema's UNKNOWN.
      observer.accept(outcome != null ? outcome.outcomeClass().name() : null);
    } catch (RuntimeException e) {
      log.debug("Queue outcome observer failed: {}", e.getMessage());
    }
  }

  /**
   * Acquires the queue's single write lock, recording how long the caller waited. Used on the two
   * paths RISK-002 is actually about — the one dequeue caller and the many enqueue callers — so the
   * measurement covers the contention that matters without instrumenting forty read paths whose
   * wait is the same fact measured again.
   */
  private void lockTimed() {
    if (lock.tryLock()) {
      meters.recordLockWaitMs(0L);
      return;
    }
    long start = meters.nowMs();
    lock.lock();
    meters.recordLockWaitMs(Math.max(0L, meters.nowMs() - start));
  }

  @Override
  public void open() throws SQLException, IOException {
    lock.lock();
    try {
      if (connectionFailure != null) throw new SQLException(
          "Queue connection cleanup requires successful close before open", connectionFailure);
      Files.createDirectories(dbPath.getParent());

      // Capture whether DB existed BEFORE opening (JDBC will create empty file if missing)
      existedBeforeOpen = Files.exists(dbPath) && Files.size(dbPath) > 0;
      if (existedBeforeOpen) {
        SqliteQueueMigrationOps.refuseFutureSchema(dbPath);
      }

      String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
      connection = DriverManager.getConnection(jdbcUrl);

      // Configure SQLite for better concurrency
      try (Statement stmt = connection.createStatement()) {
        stmt.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
        stmt.execute("PRAGMA journal_mode = WAL");
        stmt.execute("PRAGMA synchronous = NORMAL");
        // Enable incremental auto-vacuum. On new databases this takes effect immediately.
        // On existing databases with auto_vacuum=0, this has no effect until a full VACUUM.
        stmt.execute("PRAGMA auto_vacuum = 2");
      }

      initSchema();

      // Run integrity check on existing databases (throws SQLException on corruption)
      performIntegrityCheck();

      // Slice 445: attach change-stream after schema is up so the rowId cache
      // sees the post-migration row set.
      changeStream = new IndexingJobsChangeStream(connection, lock, this::ensureOpen);

      log.info("SqliteJobQueue opened: {}", dbPath);
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Opens the queue and forces an integrity check regardless of whether the database
   * existed before opening. Use this after restoring from a backup to validate the
   * restored database.
   */
  @Override
  public void openWithIntegrityCheck() throws SQLException, IOException {
    this.forceIntegrityCheck = true;
    try {
      open();
    } finally {
      this.forceIntegrityCheck = false;
    }
  }

  /**
   * Initializes the database schema and runs migrations.
   *
   * <p>This method handles both fresh databases and existing databases that need
   * migration. It uses PRAGMA user_version for version tracking and applies
   * migrations sequentially using a ladder pattern.
   *
   * @throws SQLException if schema initialization or migration fails
   * @throws IOException if backup fails before migration
   */
  private void initSchema() throws SQLException, IOException {
    // Create base tables if they don't exist
    try (Statement stmt = connection.createStatement()) {
      stmt.execute(SqliteSchema.CREATE_JOBS_TABLE);
      stmt.execute(SqliteSchema.CREATE_JOBS_STATE_INDEX);
      stmt.execute(SqliteSchema.CREATE_JOBS_STATE_UPDATED_INDEX);
      stmt.execute(SqliteSchema.CREATE_SWITCH_BUFFER_TABLE);
      stmt.execute(SqliteSchema.CREATE_SWITCH_BUFFER_INDEX);
    }

    // Run versioned migrations
    SqliteQueueMigrationOps.runMigrations(connection, this::performBackup, migrationStepHook);
  }

  /** Returns the number of buffered ops currently in the durable switch buffer (best-effort). */
  @Override
  public long switchBufferDepth() { return switchBufferOps.depth(); }

  /** Returns best-effort counts for PENDING/PROCESSING/DONE/FAILED and PENDING runnable subset. */
  @Override
  public JobQueue.JobStateCounts jobStateCounts() { return switchBufferOps.stateCounts(); }

  /** Byte weight of remaining PENDING/PROCESSING work; unknown sizes counted, not summed. */
  @Override
  public JobQueue.PendingBytes pendingBytes() { return switchBufferOps.pendingBytes(); }

  /**
   * Inserts or replaces an operation in the durable switch buffer.
   *
   * <p><b>IMPORTANT:</b> This method is fail-closed. If the write fails, it returns {@code false}
   * and the caller MUST NOT acknowledge the operation as successful.
   */
  @Override
  public boolean putSwitchBuffer(String key, String op, String payload) {
    return switchBufferOps.put(key, op, payload);
  }

  @Override
  public boolean putSyncRoot(String key, SwitchBufferSyncRoot payload) {
    return switchBufferOps.putSyncRoot(key, payload);
  }

  /** Returns all buffered ops, sorted by last_updated ascending (best-effort). */
  @Override
  public List<SwitchBufferCapableQueue.SwitchBufferOp> listSwitchBufferOps() {
    return switchBufferOps.listAll();
  }

  @Override
  public int removeReplayedSwitchBufferOps(List<SwitchBufferCapableQueue.SwitchBufferOp> replayed) {
    var snapshot = List.copyOf(replayed);
    for (var entry : snapshot) {
      if (entry.revision() == null || entry.revision().isBlank()) {
        throw new IllegalArgumentException("Replayed buffer entry has no replacement identity");
      }
    }
    lock.lock();
    try {
      ensureOpen();
      return inTransaction(() -> switchBufferOps.removeReplayedLocked(snapshot));
    } catch (SQLException failure) {
      recordDbError();
      throw new IllegalStateException("Failed to remove replayed switch-buffer versions", failure);
    } finally {
      unlockAfterChanges();
    }
  }


  @Override
  public int enqueue(List<Path> paths, String collection) {
    return enqueueEntries(JobQueue.EnqueueEntry.ofUnknownSizes(paths), collection);
  }

  /**
   * Untagged enqueue of sized entries. Overridden (rather than inherited) because the interface
   * default routes back through {@link #enqueue(List, String)}, which lands here again — this is
   * the one hop that ends the cycle at the real write path.
   */
  @Override
  public int enqueueEntries(List<JobQueue.EnqueueEntry> entries, String collection) {
    return enqueueEntries(entries, collection, null);
  }

  /**
   * Tempdoc 813 Slice B: the single write path for the jobs table. {@code size_bytes} is listed in
   * the INSERT precisely because the statement is {@code INSERT OR REPLACE} — an unlisted column
   * would be reset to its default on every re-enqueue of an already-queued path, so the caller's
   * entry is the sole authority for the recorded size (a re-enqueue restates it, including
   * restating it as unknown). Tempdoc 812 D2 puts {@code scan_id} on the same footing for the same
   * reason: the enqueue call states which scan admitted the row, or the key is lost on re-enqueue.
   */
  @Override
  public int enqueueEntries(
      List<JobQueue.EnqueueEntry> entries, String collection, String scanId) {
    if (entries == null || entries.isEmpty()) {
      return 0;
    }

    if (!hasSufficientDiskSpace()) {
      log.warn("Refusing to enqueue {} jobs: insufficient free disk space (< {} MB)",
          entries.size(), MIN_FREE_DISK_BYTES / 1024 / 1024);
      return 0;
    }

    lockTimed();
    try {
      ensureOpen();

      // Tempdoc 885 item 21b: the unlisted columns are the reset. INSERT OR REPLACE restores every
      // column not named here to its default, so re-enqueueing a path clears attempts, retry_after,
      // error_message, the last-outcome columns AND first_failed_at — which is exactly what makes a
      // rescan (or a watcher event on an mtime/size change) revive a RETRY_EXHAUSTED row with a
      // fresh retry window, with no separate "reset" statement to keep in sync.
      //
      // Tempdoc 941 round 19 (F2): collection and scan_id are the two columns that must NOT ride
      // that reset, so each is carried through the row's own prior value when the caller states
      // nothing. They describe where the file BELONGS and which scan admitted it — facts a
      // maintenance re-enqueue does not know and therefore cannot restate. The periodic
      // syncDirectory walk re-enqueues every file missing from the index (SyncDirectoryOps: the
      // untagged enqueueEntries(batch)), and a permanently FAILED file is missing from the index
      // forever — so a corrupt PDF's collection and owning scan were blanked on every sync cycle,
      // and the failed-files drawer's "Scan <id>" line stopped rendering. The watcher and the
      // retry RPC re-enqueue the same way. NULL now means "I have nothing to say about this
      // column", not "clear it"; a caller that HAS something to say (a real scan) still
      // overwrites. size_bytes deliberately keeps the restate-or-lose-it rule from 813 Slice B:
      // there, the re-enqueue re-stats the file, so its silence really does mean unknown.
      String sql = """
          INSERT OR REPLACE INTO jobs
            (path, state, attempts, last_updated, collection, size_bytes, scan_id, originator, transport)
          VALUES (
            ?, 'PENDING', 0, ?,
            COALESCE(?, (SELECT prior.collection FROM jobs prior WHERE prior.path = ?)),
            ?,
            COALESCE(?, (SELECT prior.scan_id FROM jobs prior WHERE prior.path = ?)),
            COALESCE(?, (SELECT prior.originator FROM jobs prior WHERE prior.path = ?)),
            COALESCE(?, (SELECT prior.transport FROM jobs prior WHERE prior.path = ?)))
          """;

      long now = System.currentTimeMillis();
      String col = (collection != null && !collection.isBlank()) ? collection : null;
      // Tempdoc 812 D2: the enqueueing scan's identity rides the row so the Head can group the
      // per-document terminal outcomes into one durable scan-completion audit record.
      String scan = (scanId != null && !scanId.isBlank()) ? scanId : null;

      int count = inTransaction(() -> {
        int accepted = 0;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
          for (JobQueue.EnqueueEntry entry : entries) {
            if (entry == null || entry.path() == null) {
              continue;
            }
            String normalizedPath =
                PathNormalizer.normalizePath(entry.path().toAbsolutePath().toString());
            stmt.setString(1, normalizedPath);
            stmt.setLong(2, now);
            stmt.setString(3, col);
            stmt.setString(4, normalizedPath); // carry-forward lookup for collection
            if (entry.sizeBytes() >= 0) {
              stmt.setLong(5, entry.sizeBytes());
            } else {
              stmt.setNull(5, java.sql.Types.INTEGER);
            }
            stmt.setString(6, scan);
            stmt.setString(7, normalizedPath); // carry-forward lookup for scan_id
            stmt.setString(8, entry.provenance() == null ? null : entry.provenance().originator());
            stmt.setString(9, normalizedPath);
            stmt.setString(10, entry.provenance() == null ? null : entry.provenance().transport());
            stmt.setString(11, normalizedPath);
            stmt.addBatch();
            accepted++;
          }
          stmt.executeBatch();
        }
        return accepted;
      });
      for (JobQueue.EnqueueEntry entry : entries) {
        if (entry != null && entry.path() != null) activeClaims.remove(normalizePath(entry.path()));
      }

      meters.recordEnqueued(count);
      log.debug("Enqueued {} jobs (collection={}, scanId={})", count, col, scan);
      return count;
    } catch (SQLException e) {
      log.error("Failed to enqueue jobs", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Tempdoc 885 §UD open item 1: the re-enqueue and the state read share ONE transaction, because
   * {@code INSERT OR REPLACE} destroys the state being reported. A read before the call would race
   * the indexing loop claiming the same row.
   */
  @Override
  public JobQueue.ReenqueueResult reenqueue(JobQueue.EnqueueEntry entry) {
    if (entry == null || entry.path() == null) {
      return new JobQueue.ReenqueueResult(0, null);
    }
    if (!hasSufficientDiskSpace()) {
      log.warn("Refusing to re-enqueue {}: insufficient free disk space", entry.path());
      return new JobQueue.ReenqueueResult(0, null);
    }
    lockTimed();
    try {
      ensureOpen();
      String normalizedPath =
          PathNormalizer.normalizePath(entry.path().toAbsolutePath().toString());
      JobQueue.ReenqueueResult result =
          inTransaction(
          () -> {
            String previousState = null;
            try (PreparedStatement read =
                connection.prepareStatement("SELECT state FROM jobs WHERE path = ?")) {
              read.setString(1, normalizedPath);
              try (ResultSet rs = read.executeQuery()) {
                if (rs.next()) {
                  previousState = rs.getString(1);
                }
              }
            }
            // Same statement and same column list as enqueueEntries — the unlisted columns are the
            // reset that revives a RETRY_EXHAUSTED row with a fresh retry window.
            String sql =
                """
                INSERT OR REPLACE INTO jobs
                  (path, state, attempts, last_updated, collection, size_bytes, scan_id)
                VALUES (?, 'PENDING', 0, ?, NULL, ?, NULL)
                """;
            int accepted;
            try (PreparedStatement write = connection.prepareStatement(sql)) {
              write.setString(1, normalizedPath);
              write.setLong(2, System.currentTimeMillis());
              if (entry.sizeBytes() >= 0) {
                write.setLong(3, entry.sizeBytes());
              } else {
                write.setNull(3, java.sql.Types.INTEGER);
              }
              accepted = executeMutation(write::executeUpdate) > 0 ? 1 : 0;
            }
            return new JobQueue.ReenqueueResult(accepted, previousState);
          });
      // After the commit, like enqueueEntries: a meter incremented inside the transaction would
      // still count a row a rollback threw away.
      if (result.accepted() > 0) {
        activeClaims.remove(normalizedPath);
        meters.recordEnqueued(result.accepted());
      }
      log.debug(
          "Re-enqueued {} job (previousState={})", result.accepted(), result.previousState());
      return result;
    } catch (SQLException e) {
      log.error("Failed to re-enqueue {}", entry.path(), e);
      return new JobQueue.ReenqueueResult(0, null);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public List<IndexJob> pollPending(int limit) {
    lockTimed();
    try {
      ensureOpen();

      long now = System.currentTimeMillis();

      // Local carrier for a candidate row selected before any mutation happens.
      record ClaimedRow(String path, String collection, JobQueue.EnqueueProvenance provenance) {}

      // Claim is atomic via an explicit transaction (BEGIN/COMMIT through the existing
      // inTransaction() helper), not a single UPDATE...RETURNING statement: SQLite's RETURNING
      // clause (a) may only reference columns of the table being updated - not a joined CTE - and
      // (b) its row order is documented as unspecified and empirically does NOT follow an
      // ORDER BY used to select the candidate set (verified: it follows the UPDATE's own
      // row-visitation order instead). So the SELECT below - not RETURNING - is the single
      // source of both the claimed set AND its order; the follow-up UPDATE only flips state for
      // exactly those paths. Wrapping both statements in one transaction preserves the original
      // atomicity/crash-safety intent (either the whole claim commits or none of it does; no
      // partial-progress window is visible to another connection).
      //
      // Tempdoc 731 §3.2 / PLAN I2: `last_updated ASC, path ASC` makes claim order deterministic
      // even when a batch shares one enqueue-time timestamp (the fixable determinism gap).
      List<IndexJob> result =
          inTransaction(
              () -> {
                String selectSql = """
                    SELECT path, collection, originator, transport FROM jobs
                    WHERE state = 'PENDING' AND (retry_after IS NULL OR retry_after <= ?)
                    ORDER BY last_updated ASC, path ASC
                    LIMIT ?
                    """;

                List<ClaimedRow> claimedRows = new ArrayList<>();
                try (PreparedStatement selectStmt = connection.prepareStatement(selectSql)) {
                  selectStmt.setLong(1, now);
                  selectStmt.setInt(2, limit);
                  try (ResultSet rs = selectStmt.executeQuery()) {
                    while (rs.next()) {
                      String originator = rs.getString(3);
                      String transport = rs.getString(4);
                      claimedRows.add(new ClaimedRow(rs.getString(1), rs.getString(2),
                          originator == null && transport == null ? null
                              : new JobQueue.EnqueueProvenance(originator, transport)));
                    }
                  }
                }

                if (claimedRows.isEmpty()) {
                  return List.<IndexJob>of();
                }

                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < claimedRows.size(); i++) {
                  if (i > 0) {
                    placeholders.append(',');
                  }
                  placeholders.append('?');
                }
                // Tempdoc 730 review Scope-3 (defense-in-depth): guard the claim UPDATE with
                // `state = 'PENDING'` too, not just the upstream SELECT. Today this connection
                // holds `lock` for the whole SELECT+UPDATE transaction, so there is no real
                // window for another consumer to have moved a claimed path off PENDING between
                // the two statements — but a future multi-consumer/multi-connection change should
                // not silently regress into re-claiming (and thus double-processing) a row a
                // concurrent claim already moved to PROCESSING/DONE/FAILED.
                String updateSql =
                    "UPDATE jobs SET state = 'PROCESSING', last_updated = ? WHERE state = 'PENDING' AND path IN ("
                        + placeholders
                        + ")";
                try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                  updateStmt.setLong(1, now);
                  for (int i = 0; i < claimedRows.size(); i++) {
                    updateStmt.setString(i + 2, claimedRows.get(i).path());
                  }
                  executeMutation(updateStmt::executeUpdate);
                }

                List<IndexJob> claimed = new ArrayList<>(claimedRows.size());
                for (ClaimedRow row : claimedRows) {
                  claimed.add(new IndexJob(Path.of(row.path()), row.collection(), row.provenance()));
                }
                return claimed;
              });

      for (IndexJob claim : result) activeClaims.put(normalizePath(claim.path()), claim);
      if (!result.isEmpty()) {
        meters.recordDequeued(result.size());
        log.debug("Claimed {} jobs for processing", result.size());
      }

      return result;
    } catch (SQLException e) {
      log.error("Failed to poll pending jobs", e);
      return List.of();
    } finally {
      unlockAfterChanges();
    }
  }

  private boolean ownsClaim(IndexJob claim) throws SQLException {
    String path = normalizePath(claim.path());
    if (activeClaims.get(path) != claim) return false;
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT state FROM jobs WHERE path = ?")) {
      query.setString(1, path);
      try (ResultSet result = query.executeQuery()) {
        return result.next() && STATE_PROCESSING.equals(result.getString(1));
      }
    }
  }

  private void releaseClaim(IndexJob claim) {
    String path = normalizePath(claim.path());
    if (activeClaims.get(path) == claim) activeClaims.remove(path);
  }

  private boolean finishClaim(IndexJob claim, Runnable update) {
    lock.lock();
    try {
      ensureOpen();
      if (!ownsClaim(claim)) {
        releaseClaim(claim);
        return false;
      }
      update.run();
      releaseClaim(claim);
      return true;
    } catch (SQLException failure) {
      throw new OutcomeWriteException("Could not verify processing claim", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public boolean markClaimDone(IndexJob claim, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    return finishClaim(claim, () -> markDone(claim.path(), outcome, entry));
  }

  @Override
  public boolean markClaimFailed(IndexJob claim, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    return finishClaim(claim, () -> markFailed(claim.path(), outcome, entry));
  }

  @Override
  public boolean deferClaim(IndexJob claim, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    return finishClaim(claim, () -> defer(claim.path(), outcome, entry));
  }

  @Override
  public void markDone(Path path) {
    lock.lock();
    try {
      ensureOpen();

      String sql = """
          UPDATE jobs SET state = 'DONE', last_updated = ?
          WHERE path = ?
          """;

      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, System.currentTimeMillis());
        stmt.setString(2, PathNormalizer.normalizePath(path.toAbsolutePath().toString()));
        executeMutation(stmt::executeUpdate);
      }

      log.debug("Marked job done: {}", path);
    } catch (SQLException e) {
      log.error("Failed to mark job done: {}", path, e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void markDone(Path path, IngestionOutcome outcome) {
    markDone(path, outcome, null);
  }

  @Override
  public void markDone(Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    lock.lock();
    try {
      ensureOpen();
      String normalizedPath = normalizePath(path);
      int updated =
          inTransaction(
              () -> {
                String sql = """
                    UPDATE jobs
                    SET state = 'DONE', last_updated = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  long now = System.currentTimeMillis();
                  bindOutcomeUpdate(stmt, 1, now, outcome, normalizedPath);
                  int rows = executeMutation(stmt::executeUpdate);
                  if (rows > 0) {
                    insertLedgerEvent(normalizedPath, outcome, entry);
                  }
                  return rows;
                }
              });
      logIfNoRows(updated, "markDone(outcome)", path);
      log.debug("Marked job done with outcome {}: {}", outcomeClassName(outcome), path);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "Outcome-aware markDone failed for " + path + " (completion could not be confirmed)", e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void markDoneBatch(java.util.Collection<Path> paths) {
    if (paths == null || paths.isEmpty()) return;
    lock.lock();
    try {
      ensureOpen();

      long now = System.currentTimeMillis();
      // SQLite has a default SQLITE_MAX_VARIABLE_NUMBER of 999.
      // Chunk into batches of 499 (2 params per row: state timestamp + path).
      int chunkSize = 499;
      var pathList = paths instanceof List<?> ? (List<Path>) paths
          : new ArrayList<>(paths);

      for (int offset = 0; offset < pathList.size(); offset += chunkSize) {
        int end = Math.min(offset + chunkSize, pathList.size());
        var chunk = pathList.subList(offset, end);

        StringBuilder sb = new StringBuilder("UPDATE jobs SET state = 'DONE', last_updated = ? WHERE path IN (");
        for (int i = 0; i < chunk.size(); i++) {
          if (i > 0) sb.append(',');
          sb.append('?');
        }
        sb.append(')');

        try (PreparedStatement stmt = connection.prepareStatement(sb.toString())) {
          stmt.setLong(1, now);
          for (int i = 0; i < chunk.size(); i++) {
            stmt.setString(2 + i, PathNormalizer.normalizePath(
                chunk.get(i).toAbsolutePath().toString()));
          }
          executeMutation(stmt::executeUpdate);
        }
      }

      log.debug("Marked {} jobs done (batch)", paths.size());
    } catch (SQLException e) {
      log.error("Failed to mark {} jobs done (batch)", paths.size(), e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void markDoneBatch(java.util.Collection<Path> paths, IngestionOutcome outcome) {
    if (paths == null || paths.isEmpty()) return;
    List<JobQueue.IngestionLedgerTransition> transitions = new ArrayList<>(paths.size());
    for (Path path : paths) {
      transitions.add(new JobQueue.IngestionLedgerTransition(path, null));
    }
    markDoneTransitions(transitions, outcome);
  }

  @Override
  public void markDoneTransitions(
      java.util.Collection<JobQueue.IngestionLedgerTransition> transitions,
      IngestionOutcome outcome) {
    if (transitions == null || transitions.isEmpty()) return;
    lock.lock();
    try {
      ensureOpen();
      List<JobQueue.IngestionLedgerTransition> eligible = new ArrayList<>();
      java.util.Set<IndexJob> seenClaims = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      for (JobQueue.IngestionLedgerTransition transition : transitions) {
        if (transition == null) continue;
        if (transition.claim() != null && !seenClaims.add(transition.claim())) continue;
        if (transition.claim() == null || ownsClaim(transition.claim())) eligible.add(transition);
        else releaseClaim(transition.claim());
      }
      long now = System.currentTimeMillis();
      int[] updates =
          inTransaction(
              () -> {
                String sql = """
                    UPDATE jobs
                    SET state = 'DONE', last_updated = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                List<Integer> rowCounts = new ArrayList<>(eligible.size());
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  for (JobQueue.IngestionLedgerTransition transition : eligible) {
                    if (transition == null) continue;
                    String normalizedPath = normalizePath(transition.path());
                    bindOutcomeUpdate(stmt, 1, now, outcome, normalizedPath);
                    int rows = executeMutation(stmt::executeUpdate);
                    rowCounts.add(rows);
                    if (rows > 0) {
                      insertLedgerEvent(normalizedPath, outcome, transition.entry());
                    }
                  }
                }
                int[] result = new int[rowCounts.size()];
                for (int i = 0; i < rowCounts.size(); i++) {
                  result[i] = rowCounts.get(i);
                }
                return result;
              });
      for (JobQueue.IngestionLedgerTransition transition : eligible) {
        if (transition.claim() != null) releaseClaim(transition.claim());
      }
      logBatchMisses(updates, "markDoneTransitions(outcome)");
      log.debug("Marked {} jobs done with outcome {}", transitions.size(), outcomeClassName(outcome));
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "markDoneTransitions failed for "
              + transitions.size()
              + " path(s) (completion could not be confirmed)",
          e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void markFailed(Path path, String errorMessage) {
    lock.lock();
    try {
      ensureOpen();

      // First, check current attempts
      String checkSql = "SELECT attempts FROM jobs WHERE path = ?";
      int currentAttempts = 0;

      String normalizedPath = PathNormalizer.normalizePath(path.toAbsolutePath().toString());
      try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
        stmt.setString(1, normalizedPath);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            currentAttempts = rs.getInt(1);
          }
        }
      }

      // Increment attempts on failure (not on claim)
      int newAttempts = currentAttempts + 1;

      String newState = newAttempts >= maxAttempts ? "FAILED" : "PENDING";
      long now = System.currentTimeMillis();

      // Calculate retry_after with exponential backoff + jitter
      // Base backoff: 1s * 2^(newAttempts-1), capped at ~17 minutes
      // Jitter: random value in [0, min(1s, backoff)] to prevent retry storms
      Long retryAfter = null;
      if ("PENDING".equals(newState)) {
        long backoffMs = 1000L * (1L << Math.min(newAttempts - 1, 10));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.min(1000L, backoffMs) + 1);
        retryAfter = now + backoffMs + jitter;
      }

      // Update with incremented attempts
      String updateSql = """
          UPDATE jobs SET state = ?, attempts = ?, last_updated = ?, error_message = ?, retry_after = ?
          WHERE path = ?
          """;

      try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
        stmt.setString(1, newState);
        stmt.setInt(2, newAttempts);
        stmt.setLong(3, now);
        stmt.setString(4, errorMessage);
        if (retryAfter != null) {
          stmt.setLong(5, retryAfter);
        } else {
          stmt.setNull(5, java.sql.Types.INTEGER);
        }
        stmt.setString(6, normalizedPath);
        executeMutation(stmt::executeUpdate);
      }

      if ("FAILED".equals(newState)) {
        log.warn("Job permanently failed after {} attempts: {}", newAttempts, path);
      } else {
        long backoffSeconds = retryAfter != null ? (retryAfter - now) / 1000 : 0;
        log.debug("Job failed (attempt {}), will retry after {}s: {}", newAttempts, backoffSeconds, path);
      }
    } catch (SQLException e) {
      log.error("Failed to mark job failed: {}", path, e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void recordOutcome(Path path, IngestionOutcome outcome) {
    if (outcome == null) return;
    lock.lock();
    try {
      ensureOpen();
      String normalizedPath = normalizePath(path);
      int updated =
          inTransaction(
              () -> {
                String sql = """
                    UPDATE jobs
                    SET last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  bindOutcomeOnly(stmt, 1, outcome, normalizedPath);
                  return executeMutation(stmt::executeUpdate);
                }
              });
      logIfNoRows(updated, "recordOutcome", path);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "recordOutcome failed for " + path + " (completion could not be confirmed)", e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void markFailed(Path path, IngestionOutcome outcome) {
    markFailed(path, outcome, null);
  }

  @Override
  public void markFailed(
      Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    markFailedWithOutcome(path, outcome, entry);
  }

  @Override
  public void defer(Path path, IngestionOutcome outcome) {
    defer(path, outcome, null);
  }

  @Override
  public void defer(Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    lock.lock();
    try {
      ensureOpen();
      long now = System.currentTimeMillis();
      String normalizedPath = normalizePath(path);
      int updated =
          inTransaction(
              () -> {
                String sql = """
                    UPDATE jobs
                    SET state = 'PENDING', last_updated = ?, retry_after = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  stmt.setLong(1, now);
                  stmt.setLong(2, now + 1000L);
                  bindOutcomeOnly(stmt, 3, outcome, normalizedPath);
                  int rows = executeMutation(stmt::executeUpdate);
                  if (rows > 0) {
                    insertLedgerEvent(normalizedPath, outcome, entry);
                  }
                  return rows;
                }
              });
      logIfNoRows(updated, "defer(outcome)", path);
      log.debug("Deferred job without incrementing attempts: {}", path);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "defer failed for " + path + " (completion could not be confirmed)", e);
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Failed-state transition driven by {@code outcome.retryPolicy()}.
   *
   * <p>{@link io.justsearch.indexerworker.ingest.IngestionRetryPolicy#NONE} forces the {@code
   * FAILED} terminal state on the first failure, regardless of attempt count. Outcomes carrying
   * {@link io.justsearch.indexerworker.ingest.IngestionRetryPolicy#DEFER_WITHOUT_ATTEMPT} are
   * rejected here — they belong to {@link #defer(Path, IngestionOutcome,
   * JobQueue.IngestionLedgerEntry)} and routing them through {@code markFailed} would silently
   * mark the job FAILED while incrementing the attempt counter.
   *
   * <p><b>Tempdoc 885 item 21a/21b.</b> {@link
   * io.justsearch.indexerworker.ingest.IngestionRetryPolicy#RETRY_WITH_BACKOFF} no longer consults
   * {@code maxAttempts} at all. Attempt count answers "how many times has this been tried", which
   * is a display fact; it never answered "is this file permanently unindexable", which is what the
   * terminal decision is about — and conflating them turned a twenty-minute network outage into a
   * permanent {@code FAILED}. The classification already carries the answer: a transient outcome
   * retries on {@link io.justsearch.indexerworker.ingest.IngestionRetryLadder}'s schedule until the
   * seven-day bound, then becomes {@code RETRY_EXHAUSTED} — a visible terminal state a rescan or a
   * file change resets. {@code attempts} is still incremented, and still shown.
   */
  private void markFailedWithOutcome(
      Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    if (outcome != null
        && outcome.retryPolicy()
            == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT) {
      throw new IllegalArgumentException(
          "Outcome with retryPolicy=DEFER_WITHOUT_ATTEMPT must be routed through defer(...), not markFailed(...)");
    }
    boolean terminal =
        outcome != null
            && outcome.retryPolicy() == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE;
    boolean transientOutcome =
        outcome != null
            && outcome.retryPolicy()
                == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.RETRY_WITH_BACKOFF;
    lock.lock();
    try {
      ensureOpen();
      String normalizedPath = normalizePath(path);
      FailedTransitionResult result =
          inTransaction(
              () -> {
                FailureRunState run = readFailureRun(normalizedPath);
                int newAttempts = run.attempts() + 1;
                long now = System.currentTimeMillis();
                // The run's origin: the first failure stamps it, later failures inherit it. This is
                // the only thing the seven-day bound can be measured from once attempts stopped
                // being the terminal signal.
                long firstFailedAt = run.firstFailedAtMs() > 0L ? run.firstFailedAtMs() : now;

                String newState;
                if (terminal) {
                  newState = STATE_FAILED;
                } else if (transientOutcome) {
                  newState =
                      IngestionRetryLadder.exhausted(run.firstFailedAtMs(), now)
                          ? STATE_RETRY_EXHAUSTED
                          : STATE_PENDING;
                } else {
                  // outcome == null: the untyped legacy path, which keeps the attempts cap.
                  newState = newAttempts >= maxAttempts ? STATE_FAILED : STATE_PENDING;
                }

                Long retryAfter = null;
                if (STATE_PENDING.equals(newState)) {
                  long backoffMs =
                      transientOutcome
                          ? IngestionRetryLadder.backoffMs(newAttempts)
                          : 1000L * (1L << Math.min(newAttempts - 1, 10));
                  long jitter =
                      ThreadLocalRandom.current().nextLong(0, Math.min(1000L, backoffMs) + 1);
                  retryAfter =
                      transientOutcome
                          ? IngestionRetryLadder.nextRetryAtMs(
                              firstFailedAt, newAttempts, now, jitter)
                          : now + backoffMs + jitter;
                }

                String updateSql = """
                    UPDATE jobs
                    SET state = ?, attempts = ?, last_updated = ?, error_message = ?, retry_after = ?,
                        first_failed_at = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
                  stmt.setString(1, newState);
                  stmt.setInt(2, newAttempts);
                  stmt.setLong(3, now);
                  stmt.setString(4, outcome != null ? outcome.diagnosticSummary() : null);
                  if (retryAfter != null) {
                    stmt.setLong(5, retryAfter);
                  } else {
                    stmt.setNull(5, java.sql.Types.INTEGER);
                  }
                  stmt.setLong(6, firstFailedAt);
                  bindOutcomeOnly(stmt, 7, outcome, normalizedPath);
                  int rows = executeMutation(stmt::executeUpdate);
                  if (rows > 0) {
                    insertLedgerEvent(normalizedPath, outcome, entry);
                  }
                  return new FailedTransitionResult(rows, newAttempts, newState, retryAfter);
                }
              });
      logIfNoRows(result.updated(), terminal ? "markFailed(terminal)" : "markFailed(retryable)", path);
      recordOutcomeMetric(outcome);

      if (STATE_RETRY_EXHAUSTED.equals(result.state())) {
        log.warn(
            "Job retry window exhausted after {} attempts over {} days; marked RETRY_EXHAUSTED: {}",
            result.attempts(),
            IngestionRetryLadder.MAX_RETRY_WINDOW_MS / 86_400_000L,
            path);
      } else if (STATE_FAILED.equals(result.state())) {
        log.warn("Job permanently failed after {} attempts: {}", result.attempts(), path);
      } else {
        long backoffSeconds =
            result.retryAfterMs() != null
                ? Math.max(0L, (result.retryAfterMs() - System.currentTimeMillis()) / 1000)
                : 0;
        log.debug(
            "Job failed with outcome {} (attempt {}), retry after {}s: {}",
            outcomeClassName(outcome),
            result.attempts(),
            backoffSeconds,
            path);
      }
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "markFailed failed for " + path + " (completion could not be confirmed)", e);
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Tempdoc 885 item 21b: the two facts a failure transition needs about the current failure run —
   * how many failures it has seen, and when it started. Read in one statement inside the same
   * transaction as the write, so the seven-day decision can never be made against a row another
   * thread has since reset.
   */
  private FailureRunState readFailureRun(String normalizedPath) throws SQLException {
    String sql = "SELECT attempts, first_failed_at FROM jobs WHERE path = ?";
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.setString(1, normalizedPath);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return new FailureRunState(0, 0L);
        }
        int attempts = rs.getInt(1);
        long firstFailedAt = rs.getLong(2);
        return new FailureRunState(attempts, rs.wasNull() ? 0L : firstFailedAt);
      }
    }
  }

  private record FailureRunState(int attempts, long firstFailedAtMs) {}

  private record FailedTransitionResult(int updated, int attempts, String state, Long retryAfterMs) {}

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }

  /** Preserve each former autocommit statement's boundary while confirming commit in Java. */
  private int executeMutation(SqlWork<Integer> work) throws SQLException {
    return connection.getAutoCommit() ? inTransaction(work) : work.run();
  }

  private void unlockAfterChanges() {
    try {
      if (lock.getHoldCount() == 1 && changeStream != null) {
        try { changeStream.drainCommitted(); }
        catch (RuntimeException | Error deliveryFailure) {
          if (connectionFailure == null) throw deliveryFailure;
          if (connectionFailure != deliveryFailure) connectionFailure.addSuppressed(deliveryFailure);
        } finally {
          if (connectionFailure != null) {
            // Xerial listener removal needs the native connection alive. Freeze/deliver confirmed
            // deltas first, detach capture next, and only then close the failed connection.
            try { changeStream.close(); }
            catch (RuntimeException | Error feedFailure) {
              if (connectionFailure != feedFailure) connectionFailure.addSuppressed(feedFailure);
            } finally {
              changeStream = null;
              closeFailedConnection();
            }
          }
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private <T> T inTransaction(SqlWork<T> work) throws SQLException {
    boolean wasAutoCommit = connection.getAutoCommit();
    boolean ended = false;
    boolean committed = false;
    Throwable failure = null;
    T result = null;
    try {
      connection.setAutoCommit(false);
      result = work.run();
      connection.commit();
      committed = true;
      ended = true;
    } catch (SQLException | RuntimeException | Error primary) {
      failure = primary;
      try { connection.rollback(); ended = true; }
      catch (SQLException | RuntimeException | Error rollbackFailure) {
        if (primary != rollbackFailure) primary.addSuppressed(rollbackFailure);
      }
    }
    if (ended) {
      try { connection.setAutoCommit(wasAutoCommit); }
      catch (SQLException | RuntimeException | Error restoreFailure) {
        if (failure == null) failure = restoreFailure;
        else if (failure != restoreFailure) failure.addSuppressed(restoreFailure);
        connectionFailure = failure;
      }
    } else {
      // Restoring auto-commit here could commit work whose rollback was never confirmed.
      connectionFailure = failure;
    }
    // Materialize confirmed rows while the connection is still available. A reset failure
    // cannot turn a successful commit into rollback or silently omit its projection.
    if (committed && changeStream != null) {
      try { changeStream.commitSucceeded(); }
      catch (RuntimeException | Error projectionFailure) {
        if (failure == null) failure = projectionFailure;
        else if (failure != projectionFailure) failure.addSuppressed(projectionFailure);
      }
    }
    if (connectionFailure != null) {
      recordDbError();
      if (changeStream == null) closeFailedConnection();
    }
    if (failure instanceof SQLException sql) throw sql;
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
    return result;
  }

  private void closeFailedConnection() {
    try { connection.close(); }
    catch (SQLException | RuntimeException | Error closeFailure) {
      if (connectionFailure != closeFailure) connectionFailure.addSuppressed(closeFailure);
    }
  }

  @Override
  public void recordIngestionEvent(
      Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    if (outcome == null) return;
    lock.lock();
    try {
      ensureOpen();
      insertLedgerEvent(normalizePath(path), outcome, entry);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "recordIngestionEvent failed for " + path, e);
    } finally {
      unlockAfterChanges();
    }
  }

  private void insertLedgerEvent(
      String normalizedPath, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry)
      throws SQLException {
    if (outcome == null) return;
    String sql = """
        INSERT INTO ingestion_ledger (
          path_hash, collection, outcome_class, reason_code, retry_policy,
          diagnostic_summary, observed_at, source_size_bytes, source_modified_at,
          source_kind, artifact_status, policy_id, parser_id, originator, transport
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
          CASE WHEN ? THEN ? ELSE (SELECT originator FROM jobs WHERE path = ?) END,
          CASE WHEN ? THEN ? ELSE (SELECT transport FROM jobs WHERE path = ?) END)
        """;
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      JobQueue.IngestionLedgerEntry normalizedEntry = normalizeLedgerEntry(normalizedPath, entry);
      stmt.setString(1, normalizedEntry.pathHash());
      stmt.setString(2, normalizedEntry.collection());
      stmt.setString(3, outcome.outcomeClass().name());
      stmt.setString(4, outcome.reasonCode());
      stmt.setString(5, outcome.retryPolicy().name());
      stmt.setString(6, outcome.diagnosticSummary());
      stmt.setLong(7, outcome.observedAtMs());
      setNullableLong(stmt, 8, normalizedEntry.sourceSizeBytes());
      setNullableLong(stmt, 9, normalizedEntry.sourceModifiedAtMs());
      stmt.setString(10, normalizedEntry.sourceKind());
      stmt.setString(11, normalizedEntry.artifactStatus());
      stmt.setString(12, normalizedEntry.policyId());
      stmt.setString(13, normalizedEntry.parserId());
      // A supplied entry is a claim snapshot, including explicitly unknown legacy attribution.
      // Only a contextless queue operation may consult the current row; a newer admission must
      // never rewrite the origin of an older claim that is finishing now.
      stmt.setBoolean(14, entry != null);
      stmt.setString(15, normalizedEntry.originator());
      stmt.setString(16, normalizedPath);
      stmt.setBoolean(17, entry != null);
      stmt.setString(18, normalizedEntry.transport());
      stmt.setString(19, normalizedPath);
      executeMutation(stmt::executeUpdate);
    }
  }

  private static JobQueue.IngestionLedgerEntry normalizeLedgerEntry(
      String normalizedPath, JobQueue.IngestionLedgerEntry entry) {
    if (entry == null) {
      return new JobQueue.IngestionLedgerEntry(
          sha256(normalizedPath), null, null, null, "UNKNOWN", "NOT_CREATED", "UNKNOWN", "UNKNOWN");
    }
    return new JobQueue.IngestionLedgerEntry(
        entry.pathHash() != null ? entry.pathHash() : sha256(normalizedPath),
        entry.collection(),
        entry.sourceSizeBytes(),
        entry.sourceModifiedAtMs(),
        entry.sourceKind() != null ? entry.sourceKind() : "UNKNOWN",
        entry.artifactStatus() != null ? entry.artifactStatus() : "NOT_CREATED",
        entry.policyId() != null ? entry.policyId() : "UNKNOWN",
        entry.parserId() != null ? entry.parserId() : "UNKNOWN",
        entry.originator(), entry.transport());
  }

  private static void setNullableLong(PreparedStatement stmt, int index, Long value)
      throws SQLException {
    if (value != null) {
      stmt.setLong(index, value);
    } else {
      stmt.setNull(index, java.sql.Types.INTEGER);
    }
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String normalizePath(Path path) {
    return PathNormalizer.normalizePath(path.toAbsolutePath().toString());
  }

  private static String outcomeClassName(IngestionOutcome outcome) {
    return outcome != null ? outcome.outcomeClass().name() : "null";
  }

  private void logIfNoRows(int updated, String operation, Path path) {
    if (updated == 0) {
      log.warn("{} affected no queue rows for {}", operation, path);
    }
  }

  private void logBatchMisses(int[] updates, String operation) {
    int misses = 0;
    for (int updated : updates) {
      if (updated == 0) {
        misses++;
      }
    }
    if (misses > 0) {
      log.warn("{} affected no queue rows for {} path(s)", operation, misses);
    }
  }

  private static void bindOutcomeUpdate(
      PreparedStatement stmt, int startIndex, long now, IngestionOutcome outcome, String path)
      throws SQLException {
    stmt.setLong(startIndex, now);
    bindOutcomeOnly(stmt, startIndex + 1, outcome, path);
  }

  private static void bindOutcomeOnly(
      PreparedStatement stmt, int startIndex, IngestionOutcome outcome, String path)
      throws SQLException {
    if (outcome != null) {
      stmt.setString(startIndex, outcome.outcomeClass().name());
      stmt.setString(startIndex + 1, outcome.reasonCode());
      stmt.setString(startIndex + 2, outcome.retryPolicy().name());
      stmt.setString(startIndex + 3, outcome.diagnosticSummary());
      stmt.setLong(startIndex + 4, outcome.observedAtMs());
    } else {
      stmt.setNull(startIndex, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 1, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 2, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 3, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 4, java.sql.Types.INTEGER);
    }
    stmt.setString(startIndex + 5, path);
  }

  @Override
  public int recoverStuckJobs() {
    lock.lock();
    try {
      ensureOpen();

      String sql = """
          UPDATE jobs SET state = 'PENDING', last_updated = ?
          WHERE state = 'PROCESSING'
          """;

      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, System.currentTimeMillis());
        int count = executeMutation(stmt::executeUpdate);
        if (count > 0) {
          log.info("Recovered {} stuck jobs", count);
        }
        return count;
      }
    } catch (SQLException e) {
      log.error("Failed to recover stuck jobs", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Age-bounded variant for the periodic liveness reaper (tempdoc 550 Thesis II): re-queue only
   * PROCESSING rows whose {@code last_updated} is older than {@code olderThanMs}. A generous
   * threshold spares jobs the loop is actively processing while reclaiming genuinely-orphaned rows
   * (worker claimed then died mid-process); re-indexing is idempotent so a false reset is harmless.
   */
  @Override
  public int recoverStuckJobs(long olderThanMs) {
    lock.lock();
    try {
      ensureOpen();
      long now = System.currentTimeMillis();
      String sql = """
          UPDATE jobs SET state = 'PENDING', last_updated = ?
          WHERE state = 'PROCESSING' AND last_updated < ?
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, now);
        stmt.setLong(2, now - olderThanMs);
        int count = executeMutation(stmt::executeUpdate);
        if (count > 0) {
          log.info("Reaped {} stale PROCESSING jobs (older than {} ms) → PENDING", count, olderThanMs);
        }
        return count;
      }
    } catch (SQLException e) {
      log.error("Failed to reap stale stuck jobs", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Liveness heartbeat (tempdoc 575 §4.3b / 550 Thesis II): refresh {@code last_updated} on every
   * PROCESSING row, so a fresh timestamp means the indexing loop (the live owner) still holds it — the
   * age-bounded {@link #recoverStuckJobs(long)} reaper reclaims only rows whose heartbeat went stale.
   */
  @Override
  public void heartbeatProcessing() {
    lock.lock();
    try {
      ensureOpen();
      String sql = "UPDATE jobs SET last_updated = ? WHERE state = 'PROCESSING'";
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, System.currentTimeMillis());
        executeMutation(stmt::executeUpdate);
      }
    } catch (SQLException e) {
      // Best-effort: a missed beat at worst lets the reaper reclaim a live job (idempotent re-index).
      log.debug("Heartbeat of PROCESSING jobs failed (best-effort): {}", e.getMessage());
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public long queueDepth() {
    lock.lock();
    try {
      ensureOpen();

      String sql = """
          SELECT COUNT(*) FROM jobs
          WHERE state IN ('PENDING', 'PROCESSING')
          """;

      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getLong(1);
        }
        return 0;
      }
    } catch (SQLException e) {
      log.error("Failed to get queue depth", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public long completedCount() {
    lock.lock();
    try {
      ensureOpen();

      String sql = "SELECT COUNT(*) FROM jobs WHERE state = 'DONE'";

      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getLong(1);
        }
        return 0;
      }
    } catch (SQLException e) {
      log.error("Failed to get completed count", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Removes old DONE/FAILED queue rows. The {@code ingestion_ledger} table is NOT touched here
   * — the audit trail outlives queue rows, and ledger pruning is handled by the dedicated
   * {@link #cleanupOldLedgerEvents(int)}.
   */
  @Override
  public int cleanupOldJobs(int retentionDays) {
    lock.lock();
    try {
      ensureOpen();

      long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);

      String jobsSql = """
          DELETE FROM jobs
          WHERE state IN ('DONE', 'FAILED', 'RETRY_EXHAUSTED') AND last_updated < ?
          """;

      int deleted;
      try (PreparedStatement stmt = connection.prepareStatement(jobsSql)) {
        stmt.setLong(1, cutoff);
        deleted = executeMutation(stmt::executeUpdate);
      }
      if (deleted > 0) {
        log.info("Cleaned up {} old completed/failed jobs", deleted);
        checkAndVacuum();
      }
      return deleted;
    } catch (SQLException e) {
      log.error("Failed to cleanup old jobs", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public int cleanupOldLedgerEvents(int retentionDays) {
    lock.lock();
    try {
      ensureOpen();
      long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
      try (PreparedStatement stmt =
          connection.prepareStatement("DELETE FROM ingestion_ledger WHERE observed_at < ?")) {
        stmt.setLong(1, cutoff);
        int deleted = executeMutation(stmt::executeUpdate);
        if (deleted > 0) {
          log.info("Cleaned up {} old ingestion ledger events", deleted);
          checkAndVacuum();
        }
        return deleted;
      }
    } catch (SQLException e) {
      log.error("Failed to cleanup old ledger events", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public boolean hasRecentLedgerEvent(String pathHash, String reasonCode, long sinceMs) {
    if (pathHash == null || reasonCode == null) return false;
    lock.lock();
    try {
      ensureOpen();
      String sql =
          """
          SELECT 1 FROM ingestion_ledger
          WHERE path_hash = ? AND reason_code = ? AND observed_at >= ?
          LIMIT 1
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, pathHash);
        stmt.setString(2, reasonCode);
        stmt.setLong(3, Math.max(0L, sinceMs));
        try (ResultSet rs = stmt.executeQuery()) {
          return rs.next();
        }
      }
    } catch (SQLException e) {
      log.debug("hasRecentLedgerEvent probe failed (treating as 'no recent event'): {}", e.getMessage());
      return false;
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public List<JobQueue.IngestionEventView> recentIngestionEvents(int limit) {
    lock.lock();
    try {
      ensureOpen();
      int cappedLimit = limit <= 0 ? 100 : Math.min(limit, 1000);
      String sql = """
          SELECT id, path_hash, collection, outcome_class, reason_code, retry_policy,
                 diagnostic_summary, observed_at, source_size_bytes, source_modified_at,
                 source_kind, artifact_status, policy_id, parser_id
          FROM ingestion_ledger
          ORDER BY observed_at DESC, id DESC
          LIMIT ?
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setInt(1, cappedLimit);
        try (ResultSet rs = stmt.executeQuery()) {
          List<JobQueue.IngestionEventView> events = new ArrayList<>();
          while (rs.next()) {
            events.add(readIngestionEventView(rs));
          }
          return List.copyOf(events);
        }
      }
    } catch (SQLException e) {
      log.error("Failed to list ingestion ledger events", e);
      return List.of();
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public List<JobQueue.IngestionOutcomeSummary> ingestionOutcomeSummary(long sinceMs) {
    lock.lock();
    try {
      ensureOpen();
      String sql = """
          SELECT outcome_class, reason_code, retry_policy, COUNT(*) AS event_count,
                 MAX(observed_at) AS last_observed_at
          FROM ingestion_ledger
          WHERE observed_at >= ?
          GROUP BY outcome_class, reason_code, retry_policy
          ORDER BY last_observed_at DESC
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, Math.max(0L, sinceMs));
        try (ResultSet rs = stmt.executeQuery()) {
          List<JobQueue.IngestionOutcomeSummary> summaries = new ArrayList<>();
          while (rs.next()) {
            summaries.add(
                new JobQueue.IngestionOutcomeSummary(
                    rs.getString("outcome_class"),
                    rs.getString("reason_code"),
                    rs.getString("retry_policy"),
                    rs.getLong("event_count"),
                    rs.getLong("last_observed_at")));
          }
          return List.copyOf(summaries);
        }
      }
    } catch (SQLException e) {
      log.error("Failed to summarize ingestion ledger outcomes", e);
      return List.of();
    } finally {
      unlockAfterChanges();
    }
  }

  private static JobQueue.IngestionEventView readIngestionEventView(ResultSet rs)
      throws SQLException {
    return new JobQueue.IngestionEventView(
        rs.getLong("id"),
        rs.getString("path_hash"),
        rs.getString("collection"),
        rs.getString("outcome_class"),
        rs.getString("reason_code"),
        rs.getString("retry_policy"),
        rs.getString("diagnostic_summary"),
        rs.getLong("observed_at"),
        nullableLong(rs, "source_size_bytes"),
        nullableLong(rs, "source_modified_at"),
        rs.getString("source_kind"),
        rs.getString("artifact_status"),
        rs.getString("policy_id"),
        rs.getString("parser_id"));
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static final int VACUUM_WASTE_THRESHOLD_PERCENT = 25;
  private static final int VACUUM_PAGES = 500;

  /**
   * Checks the database waste ratio and runs incremental vacuum if above threshold.
   *
   * <p>Requires {@code PRAGMA auto_vacuum = 2} (INCREMENTAL) to be effective. On databases
   * created with {@code auto_vacuum = 0}, this is a safe no-op.
   */
  void checkAndVacuum() {
    try (Statement stmt = connection.createStatement()) {
      try (ResultSet rs =
          stmt.executeQuery(
              "SELECT page_count, freelist_count, page_size FROM pragma_page_count(), "
                  + "pragma_freelist_count(), pragma_page_size()")) {
        if (rs.next()) {
          long pageCount = rs.getLong("page_count");
          long freelistCount = rs.getLong("freelist_count");
          long pageSize = rs.getLong("page_size");
          if (pageCount > 0) {
            long wastePercent = (freelistCount * 100) / pageCount;
            if (wastePercent > VACUUM_WASTE_THRESHOLD_PERCENT) {
              stmt.execute("PRAGMA incremental_vacuum(" + VACUUM_PAGES + ")");
              long reclaimedBytes = Math.min(freelistCount, VACUUM_PAGES) * pageSize;
              log.info(
                  "Incremental vacuum: reclaimed ~{} KB (waste was {}%)",
                  reclaimedBytes / 1024,
                  wastePercent);
            }
          }
        }
      }
    } catch (SQLException e) {
      log.warn("Incremental vacuum check failed", e);
    }
  }

  @Override
  public FailureSummary failureSummary() {
    lock.lock();
    try {
      ensureOpen();

      long failedCount = 0L;
      String lastFailedPath = null;
      String lastFailedError = null;
      Long lastFailedAtMs = null;
      Long nextRetryAtMs = null;

      // 1) Failed count
      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM jobs WHERE state IN ('FAILED', 'RETRY_EXHAUSTED')")) {
        if (rs.next()) {
          failedCount = rs.getLong(1);
        }
      }

      // 2) Last failed job (most recent last_updated)
      String lastFailedSql = """
          SELECT path, error_message, last_updated
          FROM jobs
          WHERE state IN ('FAILED', 'RETRY_EXHAUSTED')
          ORDER BY last_updated DESC
          LIMIT 1
          """;
      try (PreparedStatement stmt = connection.prepareStatement(lastFailedSql);
           ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          lastFailedPath = rs.getString(1);
          lastFailedError = rs.getString(2);
          long ts = rs.getLong(3);
          // last_updated is NOT NULL in schema; treat 0 as unknown defensively
          lastFailedAtMs = ts > 0 ? ts : null;
        }
      }

      // 3) Earliest retry_after for PENDING jobs (best-effort)
      // Note: PROCESSING jobs have retry_after null; we focus on PENDING backoff.
      String nextRetrySql = """
          SELECT MIN(retry_after)
          FROM jobs
          WHERE state = 'PENDING' AND retry_after IS NOT NULL
          """;
      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery(nextRetrySql)) {
        if (rs.next()) {
          long v = rs.getLong(1);
          if (!rs.wasNull() && v > 0) {
            nextRetryAtMs = v;
          }
        }
      }

      return new FailureSummary(failedCount, lastFailedPath, lastFailedError, lastFailedAtMs, nextRetryAtMs);

    } catch (SQLException e) {
      recordDbError();
      log.debug("Failed to compute failure summary (best-effort): {}", e.getMessage());
      return new FailureSummary(0L, null, null, null, null);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public List<FailedJobInfo> listFailedJobs(int limit) {
    lock.lock();
    try {
      ensureOpen();

      int effectiveLimit = limit > 0 ? Math.min(limit, 1000) : 100;

      String sql = """
          SELECT path, error_message, attempts, last_updated, collection, state, scan_id
          FROM jobs WHERE state IN ('FAILED', 'RETRY_EXHAUSTED')
          ORDER BY last_updated DESC
          LIMIT ?
          """;

      List<FailedJobInfo> result = new ArrayList<>();
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setInt(1, effectiveLimit);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            result.add(new FailedJobInfo(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                rs.getLong(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7)));
          }
        }
      }
      return result;

    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to list failed jobs", e);
      return List.of();
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Exclusive upper bound for a half-open prefix range: the normalized prefix with its final
   * character incremented, so {@code path >= lower AND path < upper} selects exactly the paths
   * under {@code lower} without SQL {@code LIKE} wildcard hazards. Callers must pass a non-empty
   * normalized prefix ({@link PathNormalizer#normalizePathPrefix} guarantees a trailing separator,
   * so the last char is well-defined and never a high sentinel).
   */
  private static String upperBoundExclusive(String lower) {
    return lower.substring(0, lower.length() - 1) + (char) (lower.charAt(lower.length() - 1) + 1);
  }

  /**
   * Lists FAILED jobs whose path is under the given prefix (tempdoc 599 §16/B1). Combines the
   * {@link #listFailedJobs(int)} projection with the half-open range predicate from
   * {@link #countByPathPrefix(String)} — same normalization + PK-index-friendly, wildcard-safe
   * bounds. Backs the per-folder "failed files" drill-down. Returns empty on error or empty prefix.
   */
  @Override
  public List<FailedJobInfo> listFailedJobsByPathPrefix(String pathPrefix, int limit) {
    lock.lock();
    try {
      ensureOpen();

      String lower = PathNormalizer.normalizePathPrefix(pathPrefix);
      if (lower == null || lower.isBlank()) {
        log.warn("listFailedJobsByPathPrefix called with empty prefix, refusing");
        return List.of();
      }
      String upper = upperBoundExclusive(lower);
      int effectiveLimit = limit > 0 ? Math.min(limit, 1000) : 100;

      String sql = """
          SELECT path, error_message, attempts, last_updated, collection, state, scan_id
          FROM jobs WHERE state IN ('FAILED', 'RETRY_EXHAUSTED') AND path >= ? AND path < ?
          ORDER BY last_updated DESC
          LIMIT ?
          """;

      List<FailedJobInfo> result = new ArrayList<>();
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, lower);
        stmt.setString(2, upper);
        stmt.setInt(3, effectiveLimit);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            result.add(new FailedJobInfo(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                rs.getLong(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7)));
          }
        }
      }
      return result;
    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to list failed jobs by path prefix: {}", pathPrefix, e);
      return List.of();
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public int clearFailedJobs() {
    lock.lock();
    try {
      ensureOpen();

      String sql = "DELETE FROM jobs WHERE state IN ('FAILED', 'RETRY_EXHAUSTED')";
      try (Statement stmt = connection.createStatement()) {
        int deleted = executeMutation(() -> stmt.executeUpdate(sql));
        if (deleted > 0) {
          log.info("Cleared {} failed jobs", deleted);
          checkAndVacuum();
        }
        return deleted;
      }

    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to clear failed jobs", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public int clearAll() {
    lock.lock();
    try {
      ensureOpen();

      // A WHERE clause disables SQLite truncate optimization, which bypasses row update hooks.
      String sql = "DELETE FROM jobs WHERE 1";
      try (Statement stmt = connection.createStatement()) {
        int deleted = executeMutation(() -> stmt.executeUpdate(sql));
        activeClaims.clear();
        if (deleted > 0) {
          log.info("Cleared all {} jobs (profiling reset)", deleted);
          checkAndVacuum();
        }
        return deleted;
      }

    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to clear all jobs", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Deletes all jobs whose path starts with the given prefix.
   * Deletes ALL states (PENDING, PROCESSING, DONE, FAILED).
   *
   * @param pathPrefix the path prefix (will be normalized)
   * @return number of jobs deleted, or -1 on error
   */
  @Override
  public int deleteByPathPrefix(String pathPrefix) {
    lock.lock();
    try {
      ensureOpen();

      String normalized = PathNormalizer.normalizePathPrefix(pathPrefix);
      if (normalized == null || normalized.isBlank()) {
        log.warn("deleteByPathPrefix called with empty prefix, refusing");
        return 0;
      }

      // Half-open range on the PK-indexed path (path >= lower AND path < upper) instead of
      // LIKE 'prefix%', so `_`/`%` inside a normalized path are treated literally rather than
      // as SQL wildcards (which would over-delete unrelated jobs) — mirrors the wildcard-safe
      // listFailedJobsByPathPrefix/countByPathPrefix range queries.
      String upper = upperBoundExclusive(normalized);
      String sql = "DELETE FROM jobs WHERE path >= ? AND path < ?";

      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, normalized);
        stmt.setString(2, upper);
        int deleted = executeMutation(stmt::executeUpdate);
        activeClaims.keySet().removeIf(path -> path.compareTo(normalized) >= 0 && path.compareTo(upper) < 0);
        if (deleted > 0) {
          log.info("deleteByPathPrefix: deleted {} jobs for prefix: {}", deleted, normalized);
        } else {
          log.debug("deleteByPathPrefix: no jobs found for prefix: {}", normalized);
        }
        return deleted;
      }
    } catch (SQLException e) {
      log.error("Failed to delete jobs by path prefix: {}", pathPrefix, e);
      return -1;
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Counts jobs whose path starts with the given prefix, broken down by sub-state (tempdoc 599
   * §9.2). Uses a half-open RANGE predicate ({@code path >= lower AND path < upper}) rather than
   * {@code LIKE} (tempdoc 599 Fix 2): it (a) uses the {@code path} PRIMARY KEY index — a case-
   * insensitive {@code LIKE} would force a full table scan, and this is called per-folder on every
   * live-status tick — and (b) avoids {@code LIKE} treating {@code _}/{@code %} in a real path as
   * wildcards (e.g. {@code F:\a_b} must not count {@code F:\aXb}). Correctness rests on stored job
   * paths and the prefix sharing the SAME {@link PathNormalizer} normalization (Windows-lowercased,
   * separators normalized) over the BINARY-collation PK. The lower bound ends with a separator (so
   * {@code F:\docs} does not match {@code F:\docs-other}); the upper bound increments its last char.
   * Returns all-zero on error or empty prefix.
   *
   * @param pathPrefix the path prefix (will be normalized)
   * @return per-state counts under the prefix
   */
  @Override
  public JobQueue.JobStateCounts countByPathPrefix(String pathPrefix) {
    lock.lock();
    try {
      ensureOpen();

      String lower = PathNormalizer.normalizePathPrefix(pathPrefix);
      if (lower == null || lower.isBlank()) {
        log.warn("countByPathPrefix called with empty prefix, refusing");
        return new JobQueue.JobStateCounts(0L, 0L, 0L, 0L, 0L);
      }
      String upper = upperBoundExclusive(lower);

      String sql = "SELECT state, COUNT(*) FROM jobs WHERE path >= ? AND path < ? GROUP BY state";

      long pending = 0L;
      long processing = 0L;
      long done = 0L;
      long failed = 0L;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, lower);
        stmt.setString(2, upper);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            String state = rs.getString(1);
            long count = rs.getLong(2);
            switch (state == null ? "" : state) {
              case STATE_PENDING -> pending = count;
              case STATE_PROCESSING -> processing = count;
              case STATE_DONE -> done = count;
              // Tempdoc 885 item 21b: RETRY_EXHAUSTED is a terminal failure for every folder
              // projection. Leaving it in the `default` arm would have silently dropped those rows
              // out of the per-root totals, so a folder with exhausted files would read "100%".
              case STATE_FAILED, STATE_RETRY_EXHAUSTED -> failed += count;
              default -> log.debug("countByPathPrefix: unknown state {} ({} rows)", state, count);
            }
          }
        }
      }
      // pendingReadyCount: the queue's backoff distinction is not needed for the folder projection
      // (in-flight = pending + processing); report pendingReadyCount == pendingCount.
      return new JobQueue.JobStateCounts(pending, pending, processing, done, failed);
    } catch (SQLException e) {
      log.error("Failed to count jobs by path prefix: {}", pathPrefix, e);
      return new JobQueue.JobStateCounts(0L, 0L, 0L, 0L, 0L);
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Deletes a single job by exact path match.
   * Used by file watcher for single file deletions.
   *
   * @param path the exact path (must already be normalized)
   * @return number of jobs deleted (0 or 1), or -1 on error
   */
  @Override
  public int deleteByExactPath(String path) {
    lock.lock();
    try {
      ensureOpen();

      if (path == null || path.isBlank()) {
        log.warn("deleteByExactPath called with empty path, refusing");
        return 0;
      }

      String sql = "DELETE FROM jobs WHERE path = ?";

      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, path);
        int deleted = executeMutation(stmt::executeUpdate);
        activeClaims.remove(path);
        if (deleted > 0) {
          log.debug("deleteByExactPath: deleted job for path: {}", path);
        }
        return deleted;
      }
    } catch (SQLException e) {
      log.error("Failed to delete job by exact path: {}", path, e);
      return -1;
    } finally {
      unlockAfterChanges();
    }
  }

  private void ensureOpen() {
    if (connectionFailure != null) throw new IllegalStateException(
        "SqliteJobQueue connection cleanup remains unresolved", connectionFailure);
    if (connection == null) {
      throw new IllegalStateException("SqliteJobQueue is not open");
    }
  }

  /**
   * Returns true if the filesystem hosting the queue DB has at least {@link #MIN_FREE_DISK_BYTES}
   * of free space. On any IO error during the probe (e.g., file store unavailable) returns true
   * (fail-open) — the disk-space probe must never be the reason a write fails. The actual write
   * still surfaces SQLITE_IOERR_FULL if the probe was wrong.
   */
  private boolean hasSufficientDiskSpace() {
    try {
      Path probePath = dbPath.toAbsolutePath();
      Path parent = probePath.getParent();
      Path target = (parent != null && Files.isDirectory(parent)) ? parent : probePath;
      long usable = Files.getFileStore(target).getUsableSpace();
      return usable >= MIN_FREE_DISK_BYTES;
    } catch (IOException e) {
      log.debug("Disk-space probe failed; allowing enqueue (fail-open): {}", e.getMessage());
      return true;
    }
  }

  // ==================== Backup ====================

  /**
   * Creates a backup of the jobs.db database using VACUUM INTO.
   *
   * <p>This creates a consistent snapshot of the database, even in WAL mode,
   * by using SQLite's VACUUM INTO command which produces a standalone copy.
   *
   * <p>The backup is written to a temp file first, then atomically moved to
   * {@code jobs.db.bak} to ensure we never have a partial backup file.
   *
   * <p><b>Note:</b> This method only creates a backup if the database existed
   * before {@link #open()} was called. Fresh databases don't need backup.
   *
   * @throws SQLException if the VACUUM INTO command fails
   * @throws IOException if the atomic move fails
   */
  public void performBackup() throws SQLException, IOException {
    if (!existedBeforeOpen) {
      log.debug("Skipping backup: database did not exist before open");
      return;
    }

    lock.lock();
    try {
      ensureOpen();

      Path tmp = dbPath.resolveSibling(dbPath.getFileName() + ".bak.tmp");
      Path bak = dbPath.resolveSibling(dbPath.getFileName() + ".bak");

      // Escape single quotes in path for SQL literal
      String escapedPath = tmp.toAbsolutePath().toString().replace("'", "''");

      log.info("Creating backup: {} -> {}", dbPath, bak);

      try (Statement stmt = connection.createStatement()) {
        stmt.execute("VACUUM INTO '" + escapedPath + "'");
      }

      // Atomic move to final location
      Files.move(tmp, bak, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

      lastBackupAtMs = System.currentTimeMillis();
      log.info("Backup created successfully: {}", bak);
    } finally {
      unlockAfterChanges();
    }
  }

  // ==================== Integrity Check ====================

  /**
   * Performs a quick integrity check on the database using PRAGMA quick_check.
   *
   * <p>This method should be called after opening an existing database to detect
   * corruption early. Fresh databases (created during this session) are skipped
   * since they cannot be corrupt.
   *
   * <p>PRAGMA quick_check returns multiple rows; the database is considered healthy
   * only if ALL rows return exactly "ok". Any other result indicates corruption.
   *
   * @return true if the database passes the integrity check
   * @throws SQLException with error code 11 (SQLITE_CORRUPT) if the check fails
   */
  public boolean performIntegrityCheck() throws SQLException {
    if (!existedBeforeOpen && !forceIntegrityCheck) {
      log.debug("Skipping integrity check: database did not exist before open");
      // Record as passed for fresh databases
      lastQuickCheckAtMs = System.currentTimeMillis();
      lastQuickCheckOk = true;
      return true;
    }

    lock.lock();
    try {
      ensureOpen();
      log.info("Running integrity check on jobs.db...");

      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery("PRAGMA quick_check")) {
        StringBuilder errors = new StringBuilder();
        int rowCount = 0;

        while (rs.next()) {
          String result = rs.getString(1);
          rowCount++;
          if (!"ok".equalsIgnoreCase(result)) {
            if (errors.length() > 0) {
              errors.append("; ");
            }
            errors.append(result);
          }
        }

        lastQuickCheckAtMs = System.currentTimeMillis();

        if (errors.length() > 0) {
          lastQuickCheckOk = false; // NOPMD - read by health check accessor
          dbHealthy = false;
          String errorMsg = "Database integrity check failed: " + errors;
          log.error(errorMsg);
          // Use SQLite error code 11 for SQLITE_CORRUPT
          throw new SQLException(errorMsg, "SQLITE_CORRUPT", 11);
        }

        lastQuickCheckOk = true;
        log.info("Integrity check passed ({} rows checked)", rowCount);
        return true;
      }
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void close() throws IOException {
    lock.lock();
    try {
      if (changeStream != null) {
        try {
          changeStream.close();
        } catch (RuntimeException e) {
          log.warn("Failed to close IndexingJobsChangeStream cleanly", e);
        } finally {
          changeStream = null;
        }
      }
      if (connection != null) {
        try {
          // Design 7.3 step 7 / item B5: drain the WAL on EVERY ordered close, not just the
          // upgrade barrier. Before B5 the only caller was WorkerUpgradeQuiescence, so an Engine
          // that exited any other way left frames for the next start to replay.
          checkpointWalBestEffort();
          connection.close();
          connection = null;
          connectionFailure = null;
          activeClaims.clear();
          log.info("SqliteJobQueue closed");
        } catch (SQLException | RuntimeException | Error failure) {
          if (connectionFailure == null) connectionFailure = failure;
          else if (connectionFailure != failure) connectionFailure.addSuppressed(failure);
          recordDbError();
          if (failure instanceof SQLException sql) throw new IOException("Failed to close SqliteJobQueue", sql);
          if (failure instanceof RuntimeException runtime) throw runtime;
          throw (Error) failure;
        }
      }
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Slice 445: accessor for the change-stream so the ingest service
   * ({@code WorkerIngestService.subscribeIndexingJobs}) can subscribe to
   * snapshot+delta frames. Returns {@code null} if the queue is closed or
   * not yet opened.
   */
  public IndexingJobsChangeStream changeStream() {
    return changeStream;
  }

  @Override
  public java.util.Optional<IndexingJobChangeFeed> indexingJobChangeFeed() {
    IndexingJobsChangeStream cs = changeStream;
    return cs == null ? java.util.Optional.empty() : java.util.Optional.of(cs);
  }

  // ==================== Health Observability ====================

  /**
   * Records a database error for health tracking.
   *
   * <p>Called from best-effort status helpers that swallow SQL exceptions.
   * This allows observability surfaces to detect when the queue DB is unhealthy
   * without requiring callers to handle exceptions.
   */
  private void recordDbError() {
    dbHealthy = false;
    lastDbErrorAtMs = System.currentTimeMillis();
  }

  /**
   * Returns a snapshot of the queue database health for observability surfaces.
   *
   * <p>This includes:
   * <ul>
   *   <li>Whether the DB is currently considered healthy</li>
   *   <li>When the last integrity check was run and whether it passed</li>
   *   <li>When the last backup was created</li>
   *   <li>When the last SQL error occurred in a status helper</li>
   * </ul>
   *
   * @return a snapshot of queue DB health
   */
  @Override
  public JobQueue.QueueDbHealthSnapshot queueDbHealthSnapshot() {
    return new JobQueue.QueueDbHealthSnapshot(
        dbHealthy,
        lastQuickCheckAtMs,
        lastQuickCheckOk,
        lastBackupAtMs,
        lastDbErrorAtMs);
  }

  /**
   * The close-path checkpoint. Never throws and never blocks the close.
   *
   * <p>Separate from {@link #checkpointWal()} because the two callers want opposite things on
   * failure: the upgrade barrier must REFUSE to proceed when frames remain (a half-drained WAL
   * under a binary swap is how a queue comes back inconsistent), while a close that cannot drain
   * must still close — the alternative is an Engine that will not shut down because SQLite is busy,
   * and the WAL is replayed on the next open anyway.
   */
  private void checkpointWalBestEffort() {
    try {
      if (!checkpointWal()) {
        log.info("WAL not fully drained on close; the next open replays the remaining frames");
      }
    } catch (RuntimeException e) {
      log.warn("WAL checkpoint on close failed (closing anyway): {}", e.getMessage());
    }
  }

  @Override
  public boolean checkpointWal() {
    lock.lock();
    try {
      ensureOpen();
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(FULL)")) {
        // SQLite returns (busy, log, checkpointed). A non-zero busy count means a writer still
        // owns frames and the upgrade must wait rather than pretending the queue is closed over.
        boolean complete = result.next() && result.getInt(1) == 0;
        if (!complete) {
          log.warn("WAL checkpoint could not drain all frames");
        }
        return complete;
      }
    } catch (SQLException e) {
      recordDbError();
      log.warn("WAL checkpoint failed: {}", e.getMessage());
      return false;
    } finally {
      unlockAfterChanges();
    }
  }
}
