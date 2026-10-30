/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import org.sqlite.Function;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteCommitListener;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteUpdateListener;

/**
 * Projects committed jobs-table changes into privacy-safe deltas. SQLite hooks only collect
 * provisional row identities: the native commit callback precedes commit completion and must
 * not execute SQL or invoke subscribers. The queue calls {@link #commitSucceeded()} after JDBC
 * commit returns, then {@link #drainCommitted()} after its outermost claim bookkeeping finishes.
 *
 * <p>Snapshot reads and subscriptions share the queue's connection lock. Committed deltas carry
 * an internal sequence so a subscriber created during reentrant delivery cannot receive changes
 * already included in its snapshot. Delivery is serialized, including subscriber-triggered writes.
 * This is a projection of queue state, not proof that a Lucene effect has committed.
 *
 * <p>The wire {@link JobRow} carries a SHA-256 path hash, never a raw path (ADR-0028).
 */
public final class IndexingJobsChangeStream implements IndexingJobChangeFeed, Closeable {

  private static final Logger log = LoggerFactory.getLogger(IndexingJobsChangeStream.class);
  private static final String JOBS_TABLE = "jobs";

  // Delta, JobRow, Subscription, SnapshotAndSubscription are inherited from
  // IndexingJobChangeFeed (worker-core). The shared types let worker-services
  // depend only on the feed abstraction without dragging in indexer-worker
  // (and Xerial SQLite specifics).

  private final Connection conn;
  private final SQLiteConnection sqliteConn;
  private final ReentrantLock ownerLock;
  private final Runnable ensureOwnerOpen;

  /** rowId → pathHash mapping; required for DELETE notifications (the row is gone post-commit). */
  private final Map<Long, String> rowIdToPathHash = new HashMap<>();

  /** Failure identities in emission order; PROCESSING identities are never evicted. */
  private final LinkedHashMap<Long, String> failedRows = new LinkedHashMap<>();
  private boolean pendingResync;
  private boolean resyncRequired;
  private Set<String> presentStates = Set.of();
  private final Set<String> enteredStates = new HashSet<>();
  private final Set<String> leftStates = new HashSet<>();
  static final String STATE_EXISTS_SQL =
      "SELECT 1 FROM jobs INDEXED BY idx_jobs_state_updated WHERE state=? LIMIT 1";
  private static final List<String> JOB_STATES =
      List.of("PENDING", "PROCESSING", "DONE", "FAILED", "RETRY_EXHAUSTED");

  /** Current row per path hash; replaces entries hidden by SQLite's REPLACE update-hook behavior. */
  private final Map<String, Long> pathHashToRowId = new HashMap<>();

  /** Provisional changes, discarded on rollback and materialized only after JDBC commit returns. */
  private final List<PendingChange> pending = new ArrayList<>();
  private final Object pendingLock = new Object();
  private final List<CommittedDelta> committed = new ArrayList<>();
  private final AtomicBoolean draining = new AtomicBoolean();

  /** Subscribers; copy-on-write to allow safe iteration during emit. */
  private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

  /**
   * Monotonic sequence counter. Bumps on every materialized committed delta. Used by FE consumers
   * to detect stale snapshots / reconcile resume tokens.
   */
  private final AtomicLong seq = new AtomicLong(0);

  /** Owns the SQLiteCommitListener registration so close() can detach it. */
  private final SQLiteCommitListener commitListener;
  private final SQLiteUpdateListener updateListener;
  private volatile boolean closed = false;

  private record PendingChange(SQLiteUpdateListener.Type type, long rowId) {}

  private record CommittedDelta(long sequence, Delta delta) {}

  private record Subscriber(long afterSequence, Consumer<Delta> consumer, AtomicBoolean active) {}

  /**
   * Attaches update + commit hooks to {@code conn}. Row identities are retained only for
   * the emitted window; an unknown deletion requests a bounded replacement snapshot.
   *
   * <p>The connection MUST be the same connection that mutates the jobs table.
   * Per {@link SqliteJobQueue} architecture (single-connection model verified
   * checkpoint {@code 044b21ab3}), attaching once captures every mutation.
   */
  IndexingJobsChangeStream(Connection conn, ReentrantLock ownerLock, Runnable ensureOwnerOpen) throws SQLException {
    this.ownerLock = Objects.requireNonNull(ownerLock, "ownerLock");
    this.ensureOwnerOpen = Objects.requireNonNull(ensureOwnerOpen, "ensureOwnerOpen");
    this.conn = Objects.requireNonNull(conn, "conn");
    this.sqliteConn = conn.unwrap(SQLiteConnection.class);
    this.updateListener = this::onRowChange;
    this.commitListener =
        new SQLiteCommitListener() {
          @Override
          public void onCommit() {
            // SQLite has not committed yet. Only the JDBC owner can confirm success.
          }

          @Override
          public void onRollback() {
            discardPending();
          }
        };
    // Connection-local triggers observe OLD.state, which SQLite's update hook does not expose.
    // They only collect provisional state names; rollback discards these with the row hooks.
    Function.create(sqliteConn, "indexing_jobs_state_transition", new Function() {
      @Override protected void xFunc() throws SQLException {
        if (!closed && !subscribers.isEmpty()) {
          String before = value_text(0);
          String after = value_text(1);
          if (before != null) leftStates.add(before);
          if (after != null) enteredStates.add(after);
        }
        result();
      }
    });
    try (Statement stmt = conn.createStatement()) {
      // REPLACE's implicit deletion does not fire DELETE triggers by default. A prior row
      // at this key must therefore be rechecked even when an INSERT is ignored on conflict.
      stmt.execute("""
          CREATE TEMP TRIGGER indexing_jobs_before_insert BEFORE INSERT ON main.jobs
          WHEN EXISTS(SELECT 1 FROM jobs WHERE path=NEW.path)
          BEGIN SELECT indexing_jobs_state_transition(
            (SELECT state FROM jobs WHERE path=NEW.path), NULL); END
          """);
      stmt.execute("""
          CREATE TEMP TRIGGER indexing_jobs_after_insert AFTER INSERT ON main.jobs
          BEGIN SELECT indexing_jobs_state_transition(NULL, NEW.state); END
          """);
      stmt.execute("""
          CREATE TEMP TRIGGER indexing_jobs_after_update AFTER UPDATE OF state ON main.jobs
          WHEN OLD.state IS NOT NEW.state
          BEGIN SELECT indexing_jobs_state_transition(OLD.state, NEW.state); END
          """);
      stmt.execute("""
          CREATE TEMP TRIGGER indexing_jobs_after_delete AFTER DELETE ON main.jobs
          BEGIN SELECT indexing_jobs_state_transition(OLD.state, NULL); END
          """);
    }
    sqliteConn.addUpdateListener(updateListener);
    sqliteConn.addCommitListener(commitListener);
  }

  /** Current monotonic seq. 0 before any deltas. */
  @Override
  public long currentSeq() {
    return seq.get();
  }

  @Override
  public SnapshotAndSubscription subscribeWithSnapshot(Consumer<Delta> subscriber)
      throws SQLException {
    Objects.requireNonNull(subscriber, "subscriber");
    ownerLock.lock();
    try {
      ensureOwnerOpen.run();
      if (closed) throw new IllegalStateException("Indexing jobs feed is closed");
      long snapshotSeq = seq.get();
      List<JobRow> rows = readWindow();
      presentStates = readPresentStates();
      return new SnapshotAndSubscription(snapshotSeq, rows, presentStates, addSubscriber(subscriber));
    } finally {
      ownerLock.unlock();
    }
  }

  @Override
  public Subscription subscribe(Consumer<Delta> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber");
    ownerLock.lock();
    try {
      ensureOwnerOpen.run();
      if (closed) throw new IllegalStateException("Indexing jobs feed is closed");
      if (subscribers.isEmpty()) {
        try { presentStates = readPresentStates(); }
        catch (SQLException failure) { throw new IllegalStateException("Cannot read queue state presence", failure); }
      }
      return addSubscriber(subscriber);
    } finally {
      ownerLock.unlock();
    }
  }

  private Subscription addSubscriber(Consumer<Delta> consumer) {
    Subscriber subscriber = new Subscriber(seq.get(), consumer, new AtomicBoolean(true));
    subscribers.add(subscriber);
    return () -> {
      subscriber.active().set(false);
      subscribers.remove(subscriber);
      // Cancellation can hold a downstream emission lock while a writer holds ownerLock.
      // Never wait here. A busy writer's commitSucceeded() clears this bounded cache instead.
      if (subscribers.isEmpty() && ownerLock.tryLock()) {
        try {
          if (subscribers.isEmpty()) clearRowCache();
        } finally {
          ownerLock.unlock();
        }
      }
    };
  }

  @Override
  public void close() {
    ownerLock.lock();
    try {
      if (closed) return;
      closed = true;
      try {
        sqliteConn.removeUpdateListener(updateListener);
      } catch (RuntimeException e) {
        log.warn("removeUpdateListener threw on close", e);
      }
      try {
        sqliteConn.removeCommitListener(commitListener);
      } catch (RuntimeException e) {
        log.warn("removeCommitListener threw on close", e);
      }
      try (Statement stmt = conn.createStatement()) {
        for (String suffix : List.of("before_insert", "after_insert", "after_update", "after_delete")) {
          stmt.execute("DROP TRIGGER IF EXISTS temp.indexing_jobs_" + suffix);
        }
        Function.destroy(sqliteConn, "indexing_jobs_state_transition");
      } catch (SQLException failure) {
        log.warn("Cannot detach queue state-presence tracking", failure);
      }
      subscribers.clear();
      clearRowCache();
      committed.clear();
      discardPending();
    } finally {
      ownerLock.unlock();
    }
  }

  // ---- internal ----

  private void clearRowCache() {
    rowIdToPathHash.clear();
    pathHashToRowId.clear();
    failedRows.clear();
  }

  // Separate limited index walks avoid sorting the full history for an IN(...) query.
  // rowid is SQLite's implicit final key in idx_jobs_state_updated and breaks timestamp ties.
  private static final String WINDOW_SQL = String.format(Locale.ROOT, """
      SELECT * FROM (
        SELECT path, state, attempts, last_updated, error_message, retry_after, collection, scan_id, rowid
        FROM jobs WHERE state = 'PROCESSING'
        UNION ALL
        SELECT * FROM (
          SELECT * FROM (
            SELECT path, state, attempts, last_updated, error_message, retry_after, collection, scan_id, rowid
            FROM jobs WHERE state = 'FAILED' ORDER BY last_updated DESC, rowid DESC LIMIT %d
          )
          UNION ALL
          SELECT * FROM (
            SELECT path, state, attempts, last_updated, error_message, retry_after, collection, scan_id, rowid
            FROM jobs WHERE state = 'RETRY_EXHAUSTED' ORDER BY last_updated DESC, rowid DESC LIMIT %d
          )
          ORDER BY last_updated DESC, rowid DESC LIMIT %d
        )
      ) ORDER BY last_updated DESC, rowid DESC
      """, FAILURE_WINDOW_SIZE, FAILURE_WINDOW_SIZE, FAILURE_WINDOW_SIZE);

  private List<JobRow> readWindow() throws SQLException {
    List<JobRow> result = new ArrayList<>();
    clearRowCache();
    try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(WINDOW_SQL)) {
      while (rs.next()) {
        JobRow row = rowFromResultSet(rs);
        result.add(row);
        long rowId = rs.getLong(9);
        cacheRow(rowId, row);
        // The snapshot arrives newest first; live changes append newest identities at the end.
        if (isFailure(row.state())) failedRows.putFirst(rowId, row.pathHash());
      }
    }
    return result;
  }

  private static JobRow rowFromResultSet(ResultSet rs) throws SQLException {
    String path = rs.getString(1);
    String state = rs.getString(2);
    int attempts = rs.getInt(3);
    long lastUpdated = rs.getLong(4);
    String errorMessage = rs.getString(5);
    long retryAfter = rs.getLong(6);
    if (rs.wasNull()) retryAfter = 0L;
    String collection = rs.getString(7);
    if (collection == null) collection = "default";
    // Tempdoc 812 D2: the enqueueing scan's id; NULL for single-file ingests, watcher-driven
    // enqueues, and rows written before the scan_id column existed.
    String scanId = rs.getString(8);
    return new JobRow(
        sha256(path),
        state == null ? "PENDING" : state,
        attempts,
        lastUpdated,
        errorMessage,
        retryAfter,
        collection,
        scanId);
  }

  // dbName is unused but required by the SQLiteUpdateListener SAM signature (method-ref binding).
  @SuppressWarnings("PMD.UnusedFormalParameter")
  private void onRowChange(
      SQLiteUpdateListener.Type type, String dbName, String tableName, long rowId) {
    if (!JOBS_TABLE.equals(tableName)) return;
    if (closed || subscribers.isEmpty()) return;
    synchronized (pendingLock) {
      // A bulk cleanup can delete a million non-window rows in one statement. Keep a marker,
      // not a million provisional identities. The cache is read under the queue's owner lock.
      if (type == SQLiteUpdateListener.Type.DELETE && !rowIdToPathHash.containsKey(rowId)) {
        pendingResync = true;
        return;
      }
      pending.add(new PendingChange(type, rowId));
    }
  }

  /** Called under the connection lock only after JDBC commit has returned successfully. */
  void commitSucceeded() {
    if (closed) return;
    List<PendingChange> snapshot;
    synchronized (pendingLock) {
      snapshot = List.copyOf(pending);
      pending.clear();
    }
    if (subscribers.isEmpty()) {
      discardPending();
      clearRowCache();
      return;
    }
    Set<String> beforeStates = presentStates;
    if (!enteredStates.isEmpty() || !leftStates.isEmpty()) {
      try {
        var next = new HashSet<>(presentStates);
        next.addAll(enteredStates);
        for (String state : leftStates) {
          if (stateExists(state)) next.add(state);
          else next.remove(state);
        }
        presentStates = Set.copyOf(next);
        enteredStates.clear();
        leftStates.clear();
      } catch (SQLException failure) {
        requireResync(failure);
      }
    }
    int commitStart = committed.size();
    // Freeze rows now: a later committed chunk or a reentrant subscriber may replace/delete them.
    for (PendingChange change : snapshot) {
      try {
        Delta delta = materialize(change);
        if (delta != null) committed.add(new CommittedDelta(seq.incrementAndGet(), delta));
      } catch (SQLException failure) {
        requireResync(failure);
      }
    }
    if (pendingResync) {
      pendingResync = false;
      resyncRequired = true;
    }
    if (resyncRequired) tryResync();
    if (!beforeStates.equals(presentStates) && committed.size() > commitStart) {
      int last = committed.size() - 1;
      CommittedDelta change = committed.get(last);
      Delta delta = switch (change.delta()) {
        case Delta.Insert insert -> new Delta.Insert(insert.row(), presentStates);
        case Delta.Update update -> new Delta.Update(update.row(), presentStates);
        case Delta.Delete delete -> new Delta.Delete(delete.pathHash(), presentStates);
        case Delta.Resync resync -> new Delta.Resync(resync.items(), presentStates);
      };
      committed.set(last, new CommittedDelta(change.sequence(), delta));
    }
  }

  private void requireResync(SQLException failure) {
    resyncRequired = true;
    log.warn("Committed indexing jobs projection requires resync: {}", failure.getMessage());
  }

  private void tryResync() {
    try {
      List<JobRow> rows = readWindow();
      Set<String> states = readPresentStates();
      presentStates = states;
      committed.add(new CommittedDelta(seq.incrementAndGet(), new Delta.Resync(rows, states)));
      resyncRequired = false;
      enteredStates.clear();
      leftStates.clear();
    } catch (SQLException failure) {
      requireResync(failure);
    }
  }

  private Set<String> readPresentStates() throws SQLException {
    var states = new HashSet<String>();
    for (String state : JOB_STATES) if (stateExists(state)) states.add(state);
    return Set.copyOf(states);
  }

  private boolean stateExists(String state) throws SQLException {
    try (PreparedStatement stmt = conn.prepareStatement(STATE_EXISTS_SQL)) {
      stmt.setString(1, state);
      try (ResultSet rs = stmt.executeQuery()) { return rs.next(); }
    }
  }

  /** Called under the queue lock after the outermost queue operation's claim bookkeeping. */
  void drainCommitted() {
    if (closed || !draining.compareAndSet(false, true)) return;
    try {
      // Idle polls and read-only observations also drain; recovery needs no later mutation.
      if (resyncRequired) tryResync();
      while (!committed.isEmpty() && !closed) {
        List<CommittedDelta> batch = List.copyOf(committed);
        committed.clear();
        for (CommittedDelta change : batch) {
          for (Subscriber subscriber : subscribers) {
            if (closed || !subscriber.active().get() || change.sequence() <= subscriber.afterSequence()) continue;
            try {
              subscriber.consumer().accept(change.delta());
            } catch (RuntimeException e) {
              log.warn("Subscriber threw on delta delivery; continuing", e);
            }
          }
        }
      }
    } finally {
      draining.set(false);
    }
  }

  private void discardPending() {
    synchronized (pendingLock) {
      pending.clear();
      pendingResync = false;
      enteredStates.clear();
      leftStates.clear();
    }
  }

  private Delta materialize(PendingChange change) throws SQLException {
    long rowId = change.rowId;
    return switch (change.type) {
      case INSERT -> {
        JobRow row = readRowByRowId(rowId);
        if (row == null) yield null;
        cacheRow(rowId, row);
        yield new Delta.Insert(row);
      }
      case UPDATE -> {
        JobRow row = readRowByRowId(rowId);
        if (row == null) yield null;
        cacheRow(rowId, row);
        yield new Delta.Update(row);
      }
      case DELETE -> {
        String pathHash = rowIdToPathHash.remove(rowId);
        if (pathHash == null) {
          pendingResync = true;
          yield null;
        }
        failedRows.remove(rowId);
        pathHashToRowId.remove(pathHash, rowId);
        pendingResync = true; // Refill a vacancy from older failures, once per commit.
        yield new Delta.Delete(pathHash);
      }
    };
  }

  /** Called under the owner lock, only for rows read from the committed table. */
  private void cacheRow(long rowId, JobRow row) {
    String pathHash = row.pathHash();
    Long previousRowId = pathHashToRowId.remove(pathHash);
    if (previousRowId != null) {
      rowIdToPathHash.remove(previousRowId);
      boolean wasFailed = failedRows.remove(previousRowId) != null;
      if (wasFailed && !isFailure(row.state())) pendingResync = true;
    }
    String previousHash = rowIdToPathHash.remove(rowId);
    if (previousHash != null) pathHashToRowId.remove(previousHash);
    failedRows.remove(rowId);
    if (!"PROCESSING".equals(row.state()) && !isFailure(row.state())) return;
    rowIdToPathHash.put(rowId, pathHash);
    pathHashToRowId.put(pathHash, rowId);
    if (isFailure(row.state())) {
      failedRows.put(rowId, pathHash);
      if (failedRows.size() > FAILURE_WINDOW_SIZE) {
        var oldest = failedRows.pollFirstEntry();
        rowIdToPathHash.remove(oldest.getKey());
        pathHashToRowId.remove(oldest.getValue());
      }
    }
  }

  private static boolean isFailure(String state) {
    return "FAILED".equals(state) || "RETRY_EXHAUSTED".equals(state);
  }

  private JobRow readRowByRowId(long rowId) throws SQLException {
    String sql =
        "SELECT path, state, attempts, last_updated, error_message, retry_after, collection, scan_id "
            + "FROM jobs WHERE rowid = ?";
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setLong(1, rowId);
      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          return rowFromResultSet(rs);
        }
      }
    }
    return null;
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
