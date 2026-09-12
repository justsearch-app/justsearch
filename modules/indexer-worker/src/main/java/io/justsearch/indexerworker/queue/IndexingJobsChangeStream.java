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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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

  /** rowId → pathHash mapping; required for DELETE notifications (the row is gone post-commit). */
  private final ConcurrentHashMap<Long, String> rowIdToPathHash = new ConcurrentHashMap<>();

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

  private record Subscriber(long afterSequence, Consumer<Delta> consumer) {}

  /**
   * Attaches update + commit hooks to {@code conn}. Eagerly populates the
   * rowId→pathHash cache from the current jobs table so DELETE notifications
   * carry the right primary key.
   *
   * <p>The connection MUST be the same connection that mutates the jobs table.
   * Per {@link SqliteJobQueue} architecture (single-connection model verified
   * checkpoint {@code 044b21ab3}), attaching once captures every mutation.
   */
  IndexingJobsChangeStream(Connection conn, ReentrantLock ownerLock) throws SQLException {
    this.ownerLock = Objects.requireNonNull(ownerLock, "ownerLock");
    this.conn = Objects.requireNonNull(conn, "conn");
    this.sqliteConn = conn.unwrap(SQLiteConnection.class);
    populateRowIdCache();
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
      long snapshotSeq = seq.get();
      List<JobRow> rows = readAllRows();
      return new SnapshotAndSubscription(snapshotSeq, rows, addSubscriber(subscriber));
    } finally {
      ownerLock.unlock();
    }
  }

  @Override
  public Subscription subscribe(Consumer<Delta> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber");
    ownerLock.lock();
    try {
      return addSubscriber(subscriber);
    } finally {
      ownerLock.unlock();
    }
  }

  private Subscription addSubscriber(Consumer<Delta> consumer) {
    Subscriber subscriber = new Subscriber(seq.get(), consumer);
    subscribers.add(subscriber);
    return () -> subscribers.remove(subscriber);
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
      subscribers.clear();
      rowIdToPathHash.clear();
      committed.clear();
      discardPending();
    } finally {
      ownerLock.unlock();
    }
  }

  // ---- internal ----

  private void populateRowIdCache() throws SQLException {
    rowIdToPathHash.clear();
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT rowid, path FROM jobs")) {
      while (rs.next()) {
        long rowId = rs.getLong(1);
        String path = rs.getString(2);
        if (path != null) {
          rowIdToPathHash.put(rowId, sha256(path));
        }
      }
    }
  }

  private List<JobRow> readAllRows() throws SQLException {
    List<JobRow> result = new ArrayList<>();
    String sql =
        "SELECT path, state, attempts, last_updated, error_message, retry_after, collection, scan_id "
            + "FROM jobs";
    try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        result.add(rowFromResultSet(rs));
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
    if (closed) return;
    synchronized (pendingLock) {
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
    // Freeze rows now: a later committed chunk or a reentrant subscriber may replace/delete them.
    for (PendingChange change : snapshot) {
      Delta delta = materialize(change);
      if (delta != null) committed.add(new CommittedDelta(seq.incrementAndGet(), delta));
    }
  }

  /** Called under the queue lock after the outermost queue operation's claim bookkeeping. */
  void drainCommitted() {
    if (closed || !draining.compareAndSet(false, true)) return;
    try {
      while (!committed.isEmpty() && !closed) {
        List<CommittedDelta> batch = List.copyOf(committed);
        committed.clear();
        for (CommittedDelta change : batch) {
          for (Subscriber subscriber : subscribers) {
            if (change.sequence() <= subscriber.afterSequence()) continue;
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
    }
  }

  private Delta materialize(PendingChange change) {
    long rowId = change.rowId;
    return switch (change.type) {
      case INSERT -> {
        JobRow row = readRowByRowId(rowId);
        if (row == null) yield null;
        rowIdToPathHash.put(rowId, row.pathHash());
        yield new Delta.Insert(row);
      }
      case UPDATE -> {
        JobRow row = readRowByRowId(rowId);
        if (row == null) yield null;
        rowIdToPathHash.put(rowId, row.pathHash());
        yield new Delta.Update(row);
      }
      case DELETE -> {
        String pathHash = rowIdToPathHash.remove(rowId);
        if (pathHash == null) yield null;
        yield new Delta.Delete(pathHash);
      }
    };
  }

  private JobRow readRowByRowId(long rowId) {
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
    } catch (SQLException e) {
      log.warn("readRowByRowId({}) failed: {}", rowId, e.getMessage());
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
