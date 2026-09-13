/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.telemetry.Telemetry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Switch buffer and state-count operations for the SQLite job queue.
 *
 * <p>Manages the durable switch buffer used during index migration (SWITCHING state) and provides
 * aggregate job-state queries for observability. Each method acquires the shared lock and obtains
 * the connection via the supplier, matching the locking pattern of the parent facade.
 */
final class SqliteQueueSwitchBufferOps {
  private static final Logger log = LoggerFactory.getLogger(SqliteQueueSwitchBufferOps.class);

  private final ReentrantLock lock;
  private final Supplier<Connection> connSupplier;
  private final Runnable onWriteFailure;
  private final Runnable errorRecorder;

  /**
   * @param onWriteFailure invoked once per switch-buffer write failure; may be null. Tempdoc 417
   *     Phase 3c: replaces the legacy {@link Telemetry.Counter} so this layer stays decoupled
   *     from the {@code Telemetry} interface (which is being retired).
   */
  SqliteQueueSwitchBufferOps(
      ReentrantLock lock,
      Supplier<Connection> connSupplier,
      Runnable onWriteFailure,
      Runnable errorRecorder) {
    this.lock = lock;
    this.connSupplier = connSupplier;
    this.onWriteFailure = onWriteFailure;
    this.errorRecorder = errorRecorder;
  }

  /** Returns the number of buffered ops currently in the durable switch buffer (best-effort). */
  long depth() {
    lock.lock();
    try {
      Connection conn = connSupplier.get();
      try (Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM switch_buffer")) {
        if (rs.next()) {
          return rs.getLong(1);
        }
        return 0L;
      }
    } catch (SQLException e) {
      errorRecorder.run();
      log.debug("Failed to get switch buffer depth (best-effort): {}", e.getMessage());
      return 0L;
    } finally {
      lock.unlock();
    }
  }

  /** Returns best-effort counts for PENDING/PROCESSING/DONE/FAILED and PENDING runnable subset. */
  JobQueue.JobStateCounts stateCounts() {
    lock.lock();
    try {
      Connection conn = connSupplier.get();
      long now = System.currentTimeMillis();
      String sql =
          """
          SELECT
            COALESCE(SUM(CASE WHEN state = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
            COALESCE(SUM(CASE WHEN state = 'PENDING' AND (retry_after IS NULL OR retry_after <= ?) THEN 1 ELSE 0 END), 0) AS pending_ready_count,
            COALESCE(SUM(CASE WHEN state = 'PROCESSING' THEN 1 ELSE 0 END), 0) AS processing_count,
            COALESCE(SUM(CASE WHEN state = 'DONE' THEN 1 ELSE 0 END), 0) AS done_count,
            COALESCE(SUM(CASE WHEN state IN ('FAILED', 'RETRY_EXHAUSTED') THEN 1 ELSE 0 END), 0) AS failed_count
          FROM jobs
          """;
      try (PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setLong(1, now);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            return new JobQueue.JobStateCounts(
                rs.getLong("pending_count"),
                rs.getLong("pending_ready_count"),
                rs.getLong("processing_count"),
                rs.getLong("done_count"),
                rs.getLong("failed_count"));
          }
          return new JobQueue.JobStateCounts(0L, 0L, 0L, 0L, 0L);
        }
      }
    } catch (Exception e) {
      errorRecorder.run();
      log.debug("Failed to get job state counts (best-effort): {}", e.getMessage());
      return new JobQueue.JobStateCounts(0L, 0L, 0L, 0L, 0L);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the byte weight of remaining (PENDING + PROCESSING) work (tempdoc 813 Slice B).
   *
   * <p>Rows enqueued before the size column existed — or by a caller that could not stat the file —
   * hold NULL; they are excluded from the sum and counted separately so a consumer never reads an
   * unknown size as zero bytes.
   *
   * <p>On failure this returns {@link JobQueue.PendingBytes#UNAVAILABLE}, not {@code EMPTY}: the
   * surrounding idiom here is best-effort + {@code errorRecorder} (see {@code jobStateCounts}),
   * and for a byte weight the all-zero value is the positive claim "nothing pending", which would
   * read as "0 B remaining" in the middle of a backlog.
   */
  JobQueue.PendingBytes pendingBytes() {
    lock.lock();
    try {
      Connection conn = connSupplier.get();
      String sql =
          """
          SELECT
            COALESCE(SUM(CASE WHEN size_bytes IS NOT NULL THEN size_bytes ELSE 0 END), 0) AS known_bytes,
            COALESCE(SUM(CASE WHEN size_bytes IS NULL THEN 1 ELSE 0 END), 0) AS unknown_size_jobs
          FROM jobs
          WHERE state IN ('PENDING', 'PROCESSING')
          """;
      try (PreparedStatement stmt = conn.prepareStatement(sql);
          ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          return new JobQueue.PendingBytes(
              rs.getLong("known_bytes"), rs.getLong("unknown_size_jobs"));
        }
        return JobQueue.PendingBytes.UNAVAILABLE;
      }
    } catch (Exception e) {
      errorRecorder.run();
      log.debug("Failed to get pending job bytes (best-effort): {}", e.getMessage());
      return JobQueue.PendingBytes.UNAVAILABLE;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Inserts or replaces an operation in the durable switch buffer.
   *
   * <p><b>IMPORTANT:</b> This method is fail-closed. If the write fails, it returns {@code false}
   * and the caller MUST NOT acknowledge the operation as successful.
   */
  boolean put(String key, String op, String payload) {
    if (key == null || key.isBlank() || op == null || op.isBlank() || payload == null) {
      log.warn("putSwitchBuffer called with invalid arguments: key={}, op={}", key, op);
      return false;
    }
    lock.lock();
    try {
      Connection conn = connSupplier.get();
      String sql =
          """
          INSERT OR REPLACE INTO switch_buffer (key, op, payload, last_updated, revision)
          VALUES (?, ?, ?, ?, ?)
          """;
      try (PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setString(1, key);
        stmt.setString(2, op);
        stmt.setString(3, payload);
        stmt.setLong(4, System.currentTimeMillis());
        stmt.setString(5, java.util.UUID.randomUUID().toString());
        stmt.executeUpdate();
        return true;
      }
    } catch (SQLException e) {
      if (onWriteFailure != null) {
        onWriteFailure.run();
      }
      log.error(
          "CRITICAL: Failed to write switch buffer op key={} op={} - caller must NOT ACK",
          key,
          op,
          e);
      return false;
    } finally {
      lock.unlock();
    }
  }

  /** The existing queue lock serializes coalescing with every other buffered admission. */
  boolean putSyncRoot(String key, SwitchBufferSyncRoot incoming) {
    lock.lock();
    try {
      return put(key, "SYNC_ROOT", preserveSyncAdmission(connSupplier.get(), key, incoming));
    } catch (SQLException e) {
      if (onWriteFailure != null) onWriteFailure.run();
      log.error("Cannot preserve buffered sync attribution; caller must NOT ACK key={}", key, e);
      return false;
    } finally {
      lock.unlock();
    }
  }

  private String preserveSyncAdmission(Connection conn, String key, SwitchBufferSyncRoot incoming)
      throws SQLException {
    if (incoming.provenance() != null) return incoming.encode();
    try (PreparedStatement prior = conn.prepareStatement(
        "SELECT op, payload FROM switch_buffer WHERE key = ?")) {
      prior.setString(1, key);
      try (ResultSet rows = prior.executeQuery()) {
        if (!rows.next()) return incoming.encode();
        if (!"SYNC_ROOT".equals(rows.getString(1))) {
          throw new SQLException("Cannot coalesce maintenance over a different buffered operation");
        }
        try {
          var previous = SwitchBufferSyncRoot.decode(rows.getString(2));
          return new SwitchBufferSyncRoot(incoming.rootPath(), incoming.force(), previous.provenance()).encode();
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException malformed) {
          // Do not erase an unreadable durable request by acknowledging a maintenance replacement.
          throw new SQLException("Cannot coalesce maintenance over an unreadable SYNC_ROOT", malformed);
        }
      }
    }
  }

  /** Returns all buffered ops, sorted by last_updated ascending (best-effort). */
  List<SwitchBufferCapableQueue.SwitchBufferOp> listAll() {
    lock.lock();
    try {
      Connection conn = connSupplier.get();
      String sql =
          """
          SELECT key, op, payload, last_updated, revision
          FROM switch_buffer
          ORDER BY last_updated ASC
          """;
      List<SwitchBufferCapableQueue.SwitchBufferOp> out = new ArrayList<>();
      try (Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery(sql)) {
        while (rs.next()) {
          out.add(
              new SwitchBufferCapableQueue.SwitchBufferOp(
                  rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)));
        }
      }
      return out;
    } catch (SQLException e) {
      errorRecorder.run();
      log.error("Failed to read switch buffer ops", e);
      return List.of();
    } finally {
      lock.unlock();
    }
  }

  /** Caller holds the queue lock and its existing transaction through commit. */
  int removeReplayedLocked(List<SwitchBufferCapableQueue.SwitchBufferOp> replayed) throws SQLException {
    try (PreparedStatement stmt = connSupplier.get().prepareStatement(
        "DELETE FROM switch_buffer WHERE key = ? AND revision = ?")) {
      int removed = 0;
      for (var entry : replayed) {
        stmt.setString(1, entry.key());
        stmt.setString(2, entry.revision());
        removed += stmt.executeUpdate();
      }
      return removed;
    }
  }
}
