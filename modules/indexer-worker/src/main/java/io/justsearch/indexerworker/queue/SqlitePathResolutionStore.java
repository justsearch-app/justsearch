/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite implementation of {@link PathResolutionStore} (ADR-0028, tempdoc 419 T5.1).
 *
 * <p>The single persistent home for raw paths after admission. Backs the
 * {@code POST /api/library/resolve-hash} endpoint that lets the local UI answer "which file
 * is this hash?" for files still under a watched root within retention. <strong>Never
 * marshaled into {@code IngestionEventView}.</strong> An ArchUnit pin
 * ({@code LibraryResolveHashOnlyCallerPin}, T5.4) enforces that no class in the diagnostic
 * export call tree transitively depends on the interface.
 *
 * <p><strong>Lifecycle (ADR-0028 §"Decision"):</strong>
 *
 * <ul>
 *   <li>{@link #record(String, String, long)} — insert/update on every successful or partial
 *       admission. Sets {@code last_seen_at = now}, clears {@code removed_at} (re-resurrects a
 *       previously-removed entry).
 *   <li>{@link #markRemoved(String, long)} — observed file deletion. Sets {@code removed_at =
 *       now}; row stays for retention.
 *   <li>{@link #pruneByRootPrefix(String)} — root unwatched. Immediate delete of every row
 *       under that prefix regardless of retention.
 *   <li>{@link #pruneOldRemoved(long)} — periodic. Deletes rows where {@code removed_at <
 *       cutoffMs}. The cutoff is "now − retention window"; default retention is 90 days
 *       configurable via {@code JUSTSEARCH_PATH_RESOLUTION_RETENTION_DAYS}.
 * </ul>
 *
 * <p><strong>Invariant: schema migration must have run before construction.</strong> The
 * {@code path_resolution} table is added by V6→V7 in {@link SqliteSchema}. Construct
 * {@link PathResolutionStore} only after the same {@code jobs.db} has been opened and
 * migrated by {@link SqliteJobQueue}.
 *
 * <p><strong>Concurrency:</strong> opens its own SQLite connection to the WAL-mode
 * {@code jobs.db}. Internal {@link ReentrantLock} serialises in-process operations; SQLite
 * busy-timeout handles cross-connection contention with the {@link SqliteJobQueue}.
 */
public final class SqlitePathResolutionStore implements PathResolutionStore, Closeable {
  private static final Logger log = LoggerFactory.getLogger(SqlitePathResolutionStore.class);

  private static final int BUSY_TIMEOUT_MS = 5000;

  private final Path dbPath;
  private final ReentrantLock lock = new ReentrantLock();
  private Connection connection;
  private boolean closed;

  public SqlitePathResolutionStore(Path dbPath) {
    this.dbPath = Objects.requireNonNull(dbPath, "dbPath");
    open();
  }

  private void open() {
    String url = "jdbc:sqlite:" + dbPath.toString().replace('\\', '/');
    try {
      Connection conn = DriverManager.getConnection(url);
      try (var stmt = conn.createStatement()) {
        stmt.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
        // WAL mode is set by SqliteJobQueue; this connection inherits the file-level mode.
      }
      this.connection = conn;
    } catch (SQLException e) {
      throw SqliteServiceAvailability.failure("Failed to open PathResolutionStore at " + dbPath, e);
    }
  }

  /**
   * Records an observed file path. UPSERT semantics: an existing row with the same
   * {@code pathHash} has its {@code last_seen_at} bumped and its {@code removed_at} cleared
   * (the file came back).
   */
  @Override
  public void probe() {
    lock.lock();
    try {
      Connection conn = openConnection();
      boolean autoCommit = conn.getAutoCommit();
      if (autoCommit) conn.setAutoCommit(false);
      var savepoint = conn.setSavepoint();
      try {
        String hash = DocumentIdentityStore.pathHash("justsearch-ingestion-canary");
        record(hash, "justsearch-ingestion-canary", System.currentTimeMillis());
        if (lookup(hash).isEmpty()) throw new SQLException("Ingestion health round trip returned no row");
      } finally {
        try {
          conn.rollback(savepoint);
          conn.releaseSavepoint(savepoint);
          if (autoCommit) conn.setAutoCommit(true);
        } catch (SQLException cleanup) {
          closed = true;
          try { conn.close(); } catch (SQLException closeFailure) { cleanup.addSuppressed(closeFailure); }
          throw cleanup;
        }
      }
    } catch (SQLException e) {
      throw SqliteServiceAvailability.failure("Ingestion health round trip failed", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void record(String pathHash, String normalizedPath, long nowMs) {
    Objects.requireNonNull(pathHash, "pathHash");
    Objects.requireNonNull(normalizedPath, "normalizedPath");
    lock.lock();
    try {
      String sql =
          """
          INSERT INTO path_resolution (path_hash, normalized_path, last_seen_at, removed_at)
          VALUES (?, ?, ?, NULL)
          ON CONFLICT(path_hash) DO UPDATE SET
            normalized_path = excluded.normalized_path,
            last_seen_at = excluded.last_seen_at,
            removed_at = NULL
          """;
      try (PreparedStatement stmt = openConnection().prepareStatement(sql)) {
        stmt.setString(1, pathHash);
        stmt.setString(2, normalizedPath);
        stmt.setLong(3, nowMs);
        stmt.executeUpdate();
      }
    } catch (SQLException e) {
      throw SqliteServiceAvailability.failure("Path resolution record failed", e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Marks a path as removed. The row stays in the table until the retention window elapses
   * (see {@link #pruneOldRemoved(long)}); the activity panel can still display "this file was
   * deleted on X" for recently-deleted entries.
   */
  @Override
  public void markRemoved(String pathHash, long nowMs) {
    Objects.requireNonNull(pathHash, "pathHash");
    lock.lock();
    try {
      String sql =
          "UPDATE path_resolution SET removed_at = ? WHERE path_hash = ? AND removed_at IS NULL";
      try (PreparedStatement stmt = openConnection().prepareStatement(sql)) {
        stmt.setLong(1, nowMs);
        stmt.setString(2, pathHash);
        stmt.executeUpdate();
      }
    } catch (SQLException e) {
      if (SqliteServiceAvailability.unavailable(e)) {
        throw SqliteServiceAvailability.failure("Path resolution service unavailable", e);
      }
      log.warn(
          "PathResolutionStore.markRemoved failed for hash {}: {}", pathHash, e.getMessage());
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the resolution for the given hash, or empty if the hash was never recorded.
   * Callers (the {@code POST /api/library/resolve-hash} handler) layer their own watched-root
   * check on top — this method does not enforce that policy itself.
   */
  @Override
  public Optional<Resolution> lookup(String pathHash) {
    Objects.requireNonNull(pathHash, "pathHash");
    lock.lock();
    try {
      String sql =
          "SELECT path_hash, normalized_path, last_seen_at, removed_at"
              + " FROM path_resolution WHERE path_hash = ?";
      try (PreparedStatement stmt = openConnection().prepareStatement(sql)) {
        stmt.setString(1, pathHash);
        try (ResultSet rs = stmt.executeQuery()) {
          if (!rs.next()) {
            return Optional.empty();
          }
          long removedAt = rs.getLong("removed_at");
          Long removedAtBoxed = rs.wasNull() ? null : removedAt;
          return Optional.of(
              new Resolution(
                  rs.getString("path_hash"),
                  rs.getString("normalized_path"),
                  rs.getLong("last_seen_at"),
                  removedAtBoxed));
        }
      }
    } catch (SQLException e) {
      if (SqliteServiceAvailability.unavailable(e)) {
        throw SqliteServiceAvailability.failure("Path resolution service unavailable", e);
      }
      log.warn("PathResolutionStore.lookup failed for hash {}: {}", pathHash, e.getMessage());
      return Optional.empty();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Deletes every row whose {@code normalized_path} starts with {@code rootPrefix}. Called
   * when a watched root is unwatched: ADR-0028 specifies immediate prune so the resolver
   * can no longer return paths under that (now out-of-scope) root.
   *
   * @return the number of rows deleted
   */
  @Override
  public int pruneByRootPrefix(String rootPrefix) {
    Objects.requireNonNull(rootPrefix, "rootPrefix");
    String prefix = rootPrefix.endsWith("/") ? rootPrefix : rootPrefix + "/";
    lock.lock();
    try {
      String sql = "DELETE FROM path_resolution WHERE normalized_path LIKE ? ESCAPE '\\'"
          + " AND substr(normalized_path, 1, length(?)) = ? COLLATE BINARY";
      try (PreparedStatement stmt = openConnection().prepareStatement(sql)) {
        stmt.setString(1, prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        stmt.setString(2, prefix);
        stmt.setString(3, prefix);
        return stmt.executeUpdate();
      }
    } catch (SQLException e) {
      if (SqliteServiceAvailability.unavailable(e)) {
        throw SqliteServiceAvailability.failure("Path resolution service unavailable", e);
      }
      log.warn(
          "PathResolutionStore.pruneByRootPrefix failed for prefix {}: {}",
          rootPrefix,
          e.getMessage());
      return 0;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Deletes rows where {@code removed_at} is non-null and older than {@code cutoffMs}. Called
   * by a periodic cleanup task. A row with {@code removed_at = null} (file still believed
   * present) is never pruned by this method.
   *
   * @param cutoffMs rows with {@code removed_at < cutoffMs} are pruned
   * @return the number of rows deleted
   */
  @Override
  public int pruneOldRemoved(long cutoffMs) {
    lock.lock();
    try {
      String sql = "DELETE FROM path_resolution WHERE removed_at IS NOT NULL AND removed_at < ?";
      try (PreparedStatement stmt = openConnection().prepareStatement(sql)) {
        stmt.setLong(1, cutoffMs);
        return stmt.executeUpdate();
      }
    } catch (SQLException e) {
      if (SqliteServiceAvailability.unavailable(e)) {
        throw SqliteServiceAvailability.failure("Path resolution service unavailable", e);
      }
      log.warn("PathResolutionStore.pruneOldRemoved failed: {}", e.getMessage());
      return 0;
    } finally {
      lock.unlock();
    }
  }

  private Connection openConnection() throws SQLException {
    return SqliteServiceAvailability.requireOpen(connection, closed);
  }

  @Override
  public void close() throws IOException {
    lock.lock();
    try {
      closed = true;
      if (connection != null) {
        connection.close();
        connection = null;
      }
    } catch (SQLException e) {
      throw new IOException("Failed to close PathResolutionStore", e);
    } finally {
      lock.unlock();
    }
  }
}
