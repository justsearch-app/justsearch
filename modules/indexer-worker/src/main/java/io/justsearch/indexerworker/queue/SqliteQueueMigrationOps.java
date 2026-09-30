/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migration orchestration for the SQLite job queue schema.
 *
 * <p>Runs the version ladder from current PRAGMA user_version to {@link SqliteSchema#TARGET_VERSION},
 * handling legacy detection, pre-migration backup, transactional DDL, and rollback on failure.
 * All DDL constants live in {@link SqliteSchema}; this class owns the execution flow.
 */
final class SqliteQueueMigrationOps {
  private static final Logger log = LoggerFactory.getLogger(SqliteQueueMigrationOps.class);


  private SqliteQueueMigrationOps() {}

  /** Callback for creating a pre-migration backup. */
  @FunctionalInterface
  interface BackupAction {
    void perform() throws SQLException, IOException;
  }

  /**
   * Refuses a database written by a newer binary before opening a writable SQLite connection.
   *
   * <p>The private snapshot includes the WAL and cannot modify the original SHM on refusal.
   */
  static void refuseFutureSchema(Path dbPath) throws SQLException, IOException {
    try (var snapshot = io.justsearch.configuration.persistence.SqliteStoreSnapshot.open(dbPath)) {
      int version = getSchemaVersion(snapshot.connection());
      if (version > SqliteSchema.TARGET_VERSION) {
        throw new SQLException("Unsupported jobs database schema version " + version
            + " (this binary supports through " + SqliteSchema.TARGET_VERSION + ")");
      }
    }
  }

  /**
   * Runs the migration ladder from current version to TARGET_VERSION.
   *
   * <p>Migration process:
   *
   * <ol>
   *   <li>Detect effective version (handles legacy unversioned databases)
   *   <li>Create backup via {@code backupAction} if database existed before open
   *   <li>Apply migrations sequentially in a transaction
   *   <li>Rollback on failure, leaving database at previous version
   * </ol>
   *
   * @param conn active JDBC connection (caller holds the lock)
   * @param backupAction callback to create a pre-migration backup (handles its own guard)
   * @param stepHook optional test hook invoked after each migration step (may be null)
   * @throws SQLException if migration fails
   * @throws IOException if backup fails
   */
  static void runMigrations(
      Connection conn,
      BackupAction backupAction,
      SqliteJobQueue.MigrationStepHook stepHook)
      throws SQLException, IOException {
    int current = getSchemaVersion(conn);

    // Handle legacy databases: version 0 could mean fresh DB or pre-versioning DB
    if (current == 0) {
      current = SqliteSchema.detectLegacyVersion(conn);
      if (current > 0) {
        // Legacy database detected - set version to match actual state
        log.info(
            "Detected legacy database at effective version {}, setting user_version", current);
        setSchemaVersion(conn, current);
      }
    }

    // Refuse a database owned by a newer binary before any compatibility DDL can mutate it.
    if (current > SqliteSchema.TARGET_VERSION) {
      throw new SQLException(
          "Unsupported jobs database schema version "
              + current
              + " (this binary supports through "
              + SqliteSchema.TARGET_VERSION
              + ")");
    }

    // One transaction owner handles both the version ladder and repairs on a current schema.
    if (current < SqliteSchema.TARGET_VERSION) backupAction.perform();

    log.info("Migrating database from version {} to {}", current, SqliteSchema.TARGET_VERSION);

    // Run migrations in a transaction
    // SQLite user_version participates in this transaction. Commit it with the DDL, otherwise
    // a failed version write can leave a newer schema stamped as an older one.
    boolean wasAutoCommit = conn.getAutoCommit();
    conn.setAutoCommit(false);
    int targetVersion = SqliteSchema.TARGET_VERSION;
    boolean transactionEnded = false;
    try {
      while (current < targetVersion) {
        int nextVersion = current + 1;
        applyMigration(conn, nextVersion);
        current = nextVersion;
        log.info("Applied migration to version {}", nextVersion);

        // Test hook: allow injecting failures to test rollback
        if (stepHook != null) {
          stepHook.afterStep(nextVersion);
        }
      }
      ensureV6LedgerPrivacySchema(conn);
      setSchemaVersion(conn, targetVersion);
      conn.commit();
      transactionEnded = true;
      log.info("Database migration completed successfully (now at version {})", targetVersion);
    } catch (SQLException | RuntimeException | Error failure) {
      try { conn.rollback(); transactionEnded = true; } catch (SQLException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      log.error("Migration failed; transaction cleanup requested", failure);
      throw failure;
    } finally {
      // Restoring auto-commit after a failed rollback could commit the partial DDL.
      if (transactionEnded) conn.setAutoCommit(wasAutoCommit);
      else conn.close();
    }
  }

  /**
   * Applies a specific version migration.
   *
   * @param conn active JDBC connection
   * @param version the version to migrate TO (e.g., 2 means V1→V2)
   * @throws SQLException if the migration fails
   */
  private static void applyMigration(Connection conn, int version) throws SQLException {
    switch (version) {
      case 1 -> {
        // V0 → V1: Initial schema (tables created in initSchema)
        // No additional DDL needed - tables are created with all V1 columns
        log.debug("V0→V1: Initial schema (base tables)");
      }
      case 2 -> {
        // V1 → V2: Add retry_after column (if not present)
        // Note: Fresh databases already have this column from CREATE TABLE,
        // so we only add it if upgrading from V1
        try (Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
          if (!SqliteSchema.hasRetryAfterColumn(rs)) {
            try (Statement alterStmt = conn.createStatement()) {
              alterStmt.execute(SqliteSchema.MIGRATE_V1_TO_V2_ADD_RETRY_AFTER);
              log.info("V1→V2: Added retry_after column");
            }
          } else {
            log.debug("V1→V2: retry_after column already exists");
          }
        }
      }
      case 3 -> {
        // V2 → V3: Add composite index for poll optimization
        // Note: Uses IF NOT EXISTS so safe even if index already exists from initSchema
        try (Statement stmt = conn.createStatement()) {
          stmt.execute(SqliteSchema.MIGRATE_V2_TO_V3_ADD_STATE_UPDATED_INDEX);
          log.info("V2→V3: Added composite index idx_jobs_state_updated");
        }
      }
      case 4 -> {
        // V3 → V4: Add collection column for collection-based tagging
        try (Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
          if (!SqliteSchema.hasColumn(rs, "collection")) {
            try (Statement alterStmt = conn.createStatement()) {
              alterStmt.execute(SqliteSchema.MIGRATE_V3_TO_V4_ADD_COLLECTION);
              log.info("V3→V4: Added collection column to jobs table");
            }
          } else {
            log.debug("V3→V4: collection column already exists");
          }
        }
      }
      case 5 -> {
        addColumnIfMissing(
            conn, "last_outcome_class", "ALTER TABLE jobs ADD COLUMN last_outcome_class TEXT");
        addColumnIfMissing(
            conn, "last_reason_code", "ALTER TABLE jobs ADD COLUMN last_reason_code TEXT");
        addColumnIfMissing(
            conn, "last_retry_policy", "ALTER TABLE jobs ADD COLUMN last_retry_policy TEXT");
        addColumnIfMissing(
            conn,
            "last_diagnostic_summary",
            "ALTER TABLE jobs ADD COLUMN last_diagnostic_summary TEXT");
        addColumnIfMissing(
            conn, "last_outcome_at", "ALTER TABLE jobs ADD COLUMN last_outcome_at INTEGER");
        log.info("V4 to V5: Ensured latest ingestion outcome columns on jobs table");
      }
      case 6 -> {
        try (Statement stmt = conn.createStatement()) {
          for (String sql : SqliteSchema.MIGRATE_V5_TO_V6_ADD_INGESTION_LEDGER) {
            stmt.execute(sql);
          }
          log.info("V5 to V6: Ensured ingestion ledger table and indexes");
        }
      }
      case 7 -> {
        try (Statement stmt = conn.createStatement()) {
          for (String sql : SqliteSchema.MIGRATE_V6_TO_V7_ADD_PATH_RESOLUTION) {
            stmt.execute(sql);
          }
          log.info("V6 to V7: Ensured path_resolution table and indexes (ADR-0028)");
        }
      }
      case 8 -> {
        addColumnIfMissing(conn, "size_bytes", SqliteSchema.MIGRATE_V7_TO_V8_ADD_SIZE_BYTES);
        log.info("V7 to V8: Ensured size_bytes column on jobs table (tempdoc 813)");
      }
      case 9 -> {
        addColumnIfMissing(conn, "scan_id", SqliteSchema.MIGRATE_V8_TO_V9_ADD_SCAN_ID);
        log.info("V8 to V9: Ensured scan_id column on jobs table (tempdoc 812 D2)");
      }
      case 10 -> {
        addColumnIfMissing(
            conn, "first_failed_at", SqliteSchema.MIGRATE_V9_TO_V10_ADD_FIRST_FAILED_AT);
        log.info("V9 to V10: Ensured first_failed_at column on jobs table (tempdoc 885 item 21)");
      }
      case 11 -> {
        try (Statement stmt = conn.createStatement()) {
          for (String sql : SqliteSchema.MIGRATE_V10_TO_V11_ADD_DOCUMENT_IDENTITY) {
            stmt.execute(sql);
          }
          log.info("V10 to V11: Ensured document_identity table and uid index (tempdoc 915)");
        }
      }
      case 12 -> {
        try (Statement stmt = conn.createStatement()) {
          for (String sql : SqliteSchema.MIGRATE_V11_TO_V12_ADD_DOCUMENT_IDENTITY_IMPORT) {
            stmt.execute(sql);
          }
          log.info("V11 to V12: Ensured document_identity_import table (tempdoc 931)");
        }
      }
      case 13 -> {
        if (!columnExists(conn, "document_identity", "deleted_at")) {
          try (Statement stmt = conn.createStatement()) {
            stmt.execute(SqliteSchema.MIGRATE_V12_TO_V13_ADD_DOCUMENT_IDENTITY_DELETED_AT);
          }
        }
        log.info("V12 to V13: Ensured deleted_at column on document_identity (tempdoc 931 §C.6)");
      }
      case 14 -> {
        addColumnIfMissing(conn, "originator", SqliteSchema.MIGRATE_V13_TO_V14_JOBS_ORIGINATOR);
        addColumnIfMissing(conn, "transport", SqliteSchema.MIGRATE_V13_TO_V14_JOBS_TRANSPORT);
        try (Statement stmt = conn.createStatement()) {
          if (!columnExists(conn, "ingestion_ledger", "originator")) {
            stmt.execute(SqliteSchema.MIGRATE_V13_TO_V14_LEDGER_ORIGINATOR);
          }
          if (!columnExists(conn, "ingestion_ledger", "transport")) {
            stmt.execute(SqliteSchema.MIGRATE_V13_TO_V14_LEDGER_TRANSPORT);
          }
        }
        log.info("V13 to V14: Ensured durable ingestion attribution");
      }
      case 15 -> {
        addColumnIfMissing(conn, "content_hash", SqliteSchema.MIGRATE_V14_TO_V15_CONTENT_HASH);
        log.info("V14 to V15: Ensured durable content hash for operation unit recovery");
      }
      case 16 -> {
        try (Statement stmt = conn.createStatement()) {
          if (!columnExists(conn, "switch_buffer", "revision")) {
            stmt.execute(SqliteSchema.MIGRATE_V15_TO_V16_SWITCH_REVISION);
          }
          stmt.execute(SqliteSchema.BACKFILL_SWITCH_REVISIONS);
        }
        log.info("V15 to V16: Identified durable switch-buffer replacements");
      }
      default -> throw new SQLException("Unknown migration version: " + version);
    }
  }

  private static void ensureV6LedgerPrivacySchema(Connection conn) throws SQLException {
    if (!tableExists(conn, "ingestion_ledger") || !columnExists(conn, "ingestion_ledger", "job_path")) {
      return;
    }
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("ALTER TABLE ingestion_ledger RENAME TO ingestion_ledger_with_paths");
      stmt.execute(SqliteSchema.CREATE_INGESTION_LEDGER_TABLE);
      stmt.execute(
          """
          INSERT INTO ingestion_ledger (
            id, path_hash, collection, outcome_class, reason_code, retry_policy,
            diagnostic_summary, observed_at, source_size_bytes, source_modified_at,
            source_kind, artifact_status, policy_id, parser_id
          )
          SELECT id, COALESCE(path_hash, 'UNKNOWN'), collection, outcome_class, reason_code, retry_policy,
                 diagnostic_summary, observed_at, source_size_bytes, source_modified_at,
                 COALESCE(source_kind, 'UNKNOWN'), COALESCE(artifact_status, 'NOT_CREATED'),
                 COALESCE(policy_id, 'UNKNOWN'), COALESCE(parser_id, 'UNKNOWN')
          FROM ingestion_ledger_with_paths
          """);
      stmt.execute("DROP TABLE ingestion_ledger_with_paths");
      stmt.execute(SqliteSchema.CREATE_INGESTION_LEDGER_PATH_TIME_INDEX);
      stmt.execute(SqliteSchema.CREATE_INGESTION_LEDGER_OUTCOME_INDEX);
      log.info("Removed raw job paths from ingestion ledger schema");
    }
  }

  private static boolean tableExists(Connection conn, String tableName) throws SQLException {
    try (java.sql.PreparedStatement stmt =
            conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
      stmt.setString(1, tableName);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static boolean columnExists(Connection conn, String tableName, String columnName)
      throws SQLException {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
      while (rs.next()) {
        if (columnName.equalsIgnoreCase(rs.getString("name"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static void addColumnIfMissing(Connection conn, String columnName, String alterSql)
      throws SQLException {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
      if (SqliteSchema.hasColumn(rs, columnName)) {
        return;
      }
    }
    try (Statement stmt = conn.createStatement()) {
      stmt.execute(alterSql);
    }
  }

  /**
   * Returns the current schema version from PRAGMA user_version.
   *
   * @param conn active JDBC connection
   * @return schema version (0 for unversioned/fresh databases)
   * @throws SQLException if the query fails
   */
  static int getSchemaVersion(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }

  /**
   * Sets the schema version using PRAGMA user_version.
   *
   * @param conn active JDBC connection
   * @param version new schema version
   * @throws SQLException if the update fails
   */
  static void setSchemaVersion(Connection conn, int version) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("PRAGMA user_version = " + version);
    }
  }
}
