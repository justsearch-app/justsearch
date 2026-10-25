/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** SQLite document-identity authority stored in the existing {@code jobs.db}. */
public final class SqliteDocumentIdentityStore implements DocumentIdentityStore, Closeable {
  private static final int BUSY_TIMEOUT_MS = 5000;

  private final Path dbPath;
  private final long deletionGraceMs;
  private final ReentrantLock lock = new ReentrantLock();
  private Connection connection;
  private boolean closed;

  /** Uses the default 30-day deletion grace (tempdoc 931 §C.6). */
  public SqliteDocumentIdentityStore(Path dbPath) {
    this(dbPath, io.justsearch.configuration.resolved.ResolvedConfig.Index
        .DEFAULT_IDENTITY_DELETION_GRACE_MS);
  }

  /**
   * @param deletionGraceMs how long a confirmed-deleted path keeps its uid; a negative value is
   *     clamped to zero so a misconfiguration cannot make the window run backwards
   */
  public SqliteDocumentIdentityStore(Path dbPath, long deletionGraceMs) {
    this.dbPath = Objects.requireNonNull(dbPath, "dbPath");
    this.deletionGraceMs = Math.max(0L, deletionGraceMs);
    open();
  }

  /** The effective deletion grace window in ms. */
  public long deletionGraceMs() {
    return deletionGraceMs;
  }

  private void open() {
    String url = "jdbc:sqlite:" + dbPath.toString().replace('\\', '/');
    try {
      Connection conn = DriverManager.getConnection(url);
      try (var stmt = conn.createStatement()) {
        stmt.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
      }
      this.connection = conn;
    } catch (SQLException e) {
      throw SqliteServiceAvailability.failure("Failed to open DocumentIdentityStore at " + dbPath, e);
    }
  }

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
        resolve(hash, System.currentTimeMillis());
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
  public Identity resolve(String pathHash, long nowMs) {
    requireHash(pathHash);
    lock.lock();
    try {
      String candidate = UUID.randomUUID().toString();
      try (PreparedStatement stmt =
          openConnection().prepareStatement(
              """
              INSERT INTO document_identity (path_hash, doc_uid, first_seen_at, last_seen_at)
              VALUES (?, ?, ?, ?)
              ON CONFLICT(path_hash) DO UPDATE SET last_seen_at = excluded.last_seen_at
              """)) {
        stmt.setString(1, pathHash);
        stmt.setString(2, candidate);
        stmt.setLong(3, nowMs);
        stmt.setLong(4, nowMs);
        stmt.executeUpdate();
      }
      Identity current = selectRequired(pathHash);
      Long deletedAt = current.deletedAtMs();
      if (deletedAt == null) {
        return current;
      }
      // Tempdoc 931 §C.6. Past the grace window this path's document is gone for good, so the file
      // now standing there is a DIFFERENT document: mint a new uid onto the row (first-seen resets
      // with it) so the replacement cannot inherit the old document's feedback. Inside the window
      // the reappearance is the same document returning; clear the mark and keep the uid.
      if (nowMs - deletedAt > deletionGraceMs) {
        remintLocked(pathHash, UUID.randomUUID().toString(), nowMs);
      } else {
        clearDeletionMarkLocked(pathHash);
      }
      return selectRequired(pathHash);
    } catch (SQLException e) {
      throw failure("resolve", pathHash, e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void markDeleted(String pathHash, long nowMs) {
    requireHash(pathHash);
    lock.lock();
    try (PreparedStatement stmt =
        openConnection().prepareStatement(
            "UPDATE document_identity SET deleted_at = ?"
                + " WHERE path_hash = ? AND deleted_at IS NULL")) {
      stmt.setLong(1, nowMs);
      stmt.setString(2, pathHash);
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw failure("markDeleted", pathHash, e);
    } finally {
      lock.unlock();
    }
  }

  private void remintLocked(String pathHash, String docUid, long nowMs) throws SQLException {
    try (PreparedStatement stmt =
        openConnection().prepareStatement(
            "UPDATE document_identity SET doc_uid = ?, first_seen_at = ?, last_seen_at = ?,"
                + " deleted_at = NULL WHERE path_hash = ?")) {
      stmt.setString(1, docUid);
      stmt.setLong(2, nowMs);
      stmt.setLong(3, nowMs);
      stmt.setString(4, pathHash);
      stmt.executeUpdate();
    }
  }

  private void clearDeletionMarkLocked(String pathHash) throws SQLException {
    try (PreparedStatement stmt =
        openConnection().prepareStatement(
            "UPDATE document_identity SET deleted_at = NULL WHERE path_hash = ?")) {
      stmt.setString(1, pathHash);
      stmt.executeUpdate();
    }
  }

  @Override
  public Identity importExisting(String pathHash, String docUid, long nowMs) {
    requireHash(pathHash);
    requireUid(docUid);
    lock.lock();
    try {
      return importExistingLocked(pathHash, docUid, nowMs).identity();
    } catch (SQLException e) {
      throw failure("importExisting", pathHash, e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int importExisting(Collection<ImportedIdentity> identities, long nowMs) {
    Objects.requireNonNull(identities, "identities");
    lock.lock();
    try {
      boolean wasAutoCommit = openConnection().getAutoCommit();
      openConnection().setAutoCommit(false);
      try {
        int inserted = 0;
        for (ImportedIdentity identity : identities) {
          Objects.requireNonNull(identity, "identity");
          requireHash(identity.pathHash());
          requireUid(identity.docUid());
          if (importExistingLocked(identity.pathHash(), identity.docUid(), nowMs).inserted()) {
            inserted++;
          }
        }
        openConnection().commit();
        return inserted;
      } catch (SQLException | RuntimeException e) {
        openConnection().rollback();
        throw e;
      } finally {
        openConnection().setAutoCommit(wasAutoCommit);
      }
    } catch (SQLException e) {
      throw failure("importExistingBatch", "<active-index-snapshot>", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public long identityCount() {
    lock.lock();
    try (PreparedStatement stmt =
            openConnection().prepareStatement("SELECT COUNT(*) FROM document_identity");
        ResultSet rs = stmt.executeQuery()) {
      return rs.next() ? rs.getLong(1) : 0L;
    } catch (SQLException e) {
      throw failure("identityCount", "<all>", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean hasImportRecord(String generationId) {
    requireGenerationId(generationId);
    lock.lock();
    try (PreparedStatement stmt =
        openConnection().prepareStatement(
            "SELECT 1 FROM document_identity_import WHERE generation_id = ?")) {
      stmt.setString(1, generationId);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw failure("hasImportRecord", generationId, e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void recordImport(ImportRecord record) {
    Objects.requireNonNull(record, "record");
    requireGenerationId(record.generationId());
    lock.lock();
    try (PreparedStatement stmt =
        openConnection().prepareStatement(
            """
            INSERT INTO document_identity_import
              (generation_id, imported_at, parents_seen, parents_imported, parents_skipped)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(generation_id) DO UPDATE SET
              imported_at = excluded.imported_at,
              parents_seen = excluded.parents_seen,
              parents_imported = excluded.parents_imported,
              parents_skipped = excluded.parents_skipped
            """)) {
      stmt.setString(1, record.generationId());
      stmt.setLong(2, record.importedAtMs());
      stmt.setLong(3, record.parentsSeen());
      stmt.setLong(4, record.parentsImported());
      stmt.setLong(5, record.parentsSkipped());
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw failure("recordImport", record.generationId(), e);
    } finally {
      lock.unlock();
    }
  }

  /** One row's outcome: the authoritative identity, and whether this call created it. */
  private record ImportOutcome(Identity identity, boolean inserted) {}

  private ImportOutcome importExistingLocked(String pathHash, String docUid, long nowMs)
      throws SQLException {
    Optional<Identity> byPath = select(pathHash);
    if (byPath.isPresent()) {
      touch(pathHash, nowMs);
      return new ImportOutcome(selectRequired(pathHash), false);
    }
    Optional<Identity> byUid = selectByUid(docUid);
    if (byUid.isPresent()) {
      return new ImportOutcome(byUid.get(), false);
    }
    try (PreparedStatement stmt =
        openConnection().prepareStatement(
            """
            INSERT INTO document_identity (path_hash, doc_uid, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?)
            """)) {
      stmt.setString(1, pathHash);
      stmt.setString(2, docUid);
      stmt.setLong(3, nowMs);
      stmt.setLong(4, nowMs);
      stmt.executeUpdate();
    }
    return new ImportOutcome(selectRequired(pathHash), true);
  }

  @Override
  public RekeyResult rekey(String oldPathHash, String newPathHash, long nowMs) {
    requireHash(oldPathHash);
    requireHash(newPathHash);
    lock.lock();
    try {
      if (oldPathHash.equals(newPathHash)) {
        boolean exists = select(oldPathHash).isPresent();
        if (exists) {
          touch(oldPathHash, nowMs);
          clearDeletionMarkLocked(oldPathHash);
        }
        return exists ? RekeyResult.ALREADY_AT_DESTINATION : RekeyResult.NOT_FOUND;
      }

      Optional<Identity> oldIdentity = select(oldPathHash);
      if (oldIdentity.isEmpty()) {
        // Destination-present is the only convergence evidence available after a response is lost:
        // the old key is gone, and the request carries no uid. Treat it as an idempotent replay.
        return select(newPathHash).isPresent()
            ? RekeyResult.ALREADY_AT_DESTINATION
            : RekeyResult.NOT_FOUND;
      }

      boolean wasAutoCommit = openConnection().getAutoCommit();
      openConnection().setAutoCommit(false);
      try {
        // Identity rows deliberately have no GC. A valid rename onto a historically-used path
        // therefore makes the moving source authoritative and replaces that stale destination.
        try (PreparedStatement delete =
            openConnection().prepareStatement("DELETE FROM document_identity WHERE path_hash = ?")) {
          delete.setString(1, newPathHash);
          delete.executeUpdate();
        }
        // A verified move is proof the document still exists, so it clears any deletion mark the
        // source path carried (tempdoc 931 §C.6) — a rename observed after a confirmed deletion is
        // the document being found again, not a new one.
        try (PreparedStatement stmt =
            openConnection().prepareStatement(
                "UPDATE document_identity SET path_hash = ?, last_seen_at = ?, deleted_at = NULL"
                    + " WHERE path_hash = ?")) {
          stmt.setString(1, newPathHash);
          stmt.setLong(2, nowMs);
          stmt.setString(3, oldPathHash);
          if (stmt.executeUpdate() != 1) {
            throw new SQLException(
                "Document identity rekey did not update exactly one row for " + oldPathHash);
          }
        }
        openConnection().commit();
        return RekeyResult.MOVED;
      } catch (SQLException | RuntimeException e) {
        openConnection().rollback();
        throw e;
      } finally {
        openConnection().setAutoCommit(wasAutoCommit);
      }
    } catch (SQLException e) {
      throw failure("rekey", oldPathHash, e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Optional<Identity> lookup(String pathHash) {
    requireHash(pathHash);
    lock.lock();
    try {
      return select(pathHash);
    } catch (SQLException e) {
      throw failure("lookup", pathHash, e);
    } finally {
      lock.unlock();
    }
  }

  private Optional<Identity> select(String pathHash) throws SQLException {
    try (PreparedStatement stmt =
        openConnection().prepareStatement(
            "SELECT path_hash, doc_uid, first_seen_at, last_seen_at, deleted_at"
                + " FROM document_identity WHERE path_hash = ?")) {
      stmt.setString(1, pathHash);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? Optional.of(readIdentity(rs)) : Optional.empty();
      }
    }
  }

  private Optional<Identity> selectByUid(String docUid) throws SQLException {
    try (PreparedStatement stmt =
        openConnection().prepareStatement(
            "SELECT path_hash, doc_uid, first_seen_at, last_seen_at, deleted_at"
                + " FROM document_identity WHERE doc_uid = ?")) {
      stmt.setString(1, docUid);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? Optional.of(readIdentity(rs)) : Optional.empty();
      }
    }
  }

  private static Identity readIdentity(ResultSet rs) throws SQLException {
    long rawDeletedAt = rs.getLong("deleted_at");
    // wasNull() answers for the MOST RECENT getter, so the flag is captured here and not inlined
    // into the constructor call below, where argument evaluation order would make it answer for
    // last_seen_at instead.
    Long deletedAt = rs.wasNull() ? null : rawDeletedAt;
    return new Identity(
        rs.getString("path_hash"),
        rs.getString("doc_uid"),
        rs.getLong("first_seen_at"),
        rs.getLong("last_seen_at"),
        deletedAt);
  }

  private void touch(String pathHash, long nowMs) throws SQLException {
    try (PreparedStatement stmt =
        openConnection().prepareStatement(
            "UPDATE document_identity SET last_seen_at = ? WHERE path_hash = ?")) {
      stmt.setLong(1, nowMs);
      stmt.setString(2, pathHash);
      stmt.executeUpdate();
    }
  }

  private Identity selectRequired(String pathHash) throws SQLException {
    return select(pathHash)
        .orElseThrow(
            () -> new IllegalStateException("Document identity row disappeared for " + pathHash));
  }

  private static void requireHash(String pathHash) {
    if (pathHash == null || pathHash.isBlank()) {
      throw new IllegalArgumentException("pathHash is required");
    }
  }

  private static void requireGenerationId(String generationId) {
    if (generationId == null || generationId.isBlank()) {
      throw new IllegalArgumentException("generationId is required");
    }
  }

  private static void requireUid(String docUid) {
    if (docUid == null || docUid.isBlank()) {
      throw new IllegalArgumentException("docUid is required");
    }
  }

  private IllegalStateException failure(String operation, String pathHash, SQLException cause) {
    return SqliteServiceAvailability.failure(
        "DocumentIdentityStore." + operation + " failed for path hash " + pathHash, cause);
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
      throw new IOException("Failed to close DocumentIdentityStore", e);
    } finally {
      lock.unlock();
    }
  }
}
