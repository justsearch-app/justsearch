package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the SqliteJobQueue schema migration system.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>V1 to V2 migration upgrades the schema correctly</li>
 *   <li>Existing data is preserved during migration</li>
 *   <li>Schema version (PRAGMA user_version) is updated</li>
 * </ul>
 */
final class JobQueueMigrationTest {

  @TempDir Path tempDir;

  @Test
  void futureSchemaIsRefusedBeforeDdlAndDatabaseBytesRemainUnchanged() throws Exception {
    Path dbPath = tempDir.resolve("future.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE future_owned (value TEXT NOT NULL)");
      stmt.execute("INSERT INTO future_owned(value) VALUES ('preserve-me')");
      stmt.execute("PRAGMA user_version = " + (SqliteSchema.TARGET_VERSION + 1));
    }
    byte[] before = Files.readAllBytes(dbPath);

    SqliteJobQueue queue = new SqliteJobQueue(dbPath);
    Exception thrown = null;
    try {
      queue.open();
    } catch (Exception error) {
      thrown = error;
    } finally {
      queue.close();
    }

    assertNotNull(thrown, "a future database must be refused");
    assertTrue(
        thrown.getMessage().contains("Unsupported jobs database schema version")
            || (thrown.getCause() != null
                && thrown
                    .getCause()
                    .getMessage()
                    .contains("Unsupported jobs database schema version")),
        "future-version refusal must be observable: " + thrown);
    assertTrue(
        java.util.Arrays.equals(before, Files.readAllBytes(dbPath)),
        "refusal must happen before DDL or other database mutation");
  }

  /**
   * Test that opening SqliteJobQueue on a V1 database correctly upgrades it to V2.
   *
   * <p>Steps:
   * <ol>
   *   <li>Create a V1 database manually (no retry_after column)</li>
   *   <li>Set PRAGMA user_version = 1</li>
   *   <li>Insert sample job</li>
   *   <li>Open SqliteJobQueue (triggers migration)</li>
   *   <li>Verify user_version = 2 and retry_after column exists</li>
   *   <li>Verify existing job data is preserved</li>
   * </ol>
   */
  @Test
  void migratesV1ToV2Successfully() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    // Step 1-3: Create V1 database manually
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      // Create V1 schema (no retry_after column)
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT
          )
          """);

      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");

      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);

      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");

      // Set schema version to 1
      stmt.execute("PRAGMA user_version = 1");

      // Insert sample job
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated)
          VALUES ('/test/file.txt', 'PENDING', 0, 1234567890)
          """);
    }

    // Step 4: Open SqliteJobQueue (should trigger migration)
    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    // Step 5-6: Verify migration results
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      // Verify schema version is now 2
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next(), "user_version query should return a row");
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1),
            "Schema version should be upgraded to TARGET_VERSION");
      }

      // Verify retry_after column exists
      boolean hasRetryAfter = false;
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
        while (rs.next()) {
          if ("retry_after".equals(rs.getString("name"))) {
            hasRetryAfter = true;
            break;
          }
        }
      }
      assertTrue(hasRetryAfter, "retry_after column should exist after migration");

      // Verify existing data is preserved
      try (ResultSet rs = stmt.executeQuery("SELECT path, state FROM jobs WHERE path = '/test/file.txt'")) {
        assertTrue(rs.next(), "Original job should still exist after migration");
        assertEquals("/test/file.txt", rs.getString("path"));
        assertEquals("PENDING", rs.getString("state"));
      }
    } finally {
      jobQueue.close();
    }
  }

  /**
   * Test that a fresh database is created at the target schema version.
   */
  @Test
  void freshDatabaseCreatedAtTargetVersion() throws Exception {
    Path dbPath = tempDir.resolve("fresh.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      // Verify schema version
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1),
            "Fresh database should be at TARGET_VERSION");
      }

      // Verify retry_after column exists (V2 feature)
      boolean hasRetryAfter = false;
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
        while (rs.next()) {
          if ("retry_after".equals(rs.getString("name"))) {
            hasRetryAfter = true;
            break;
          }
        }
      }
      assertTrue(hasRetryAfter, "Fresh database should have retry_after column");
    } finally {
      jobQueue.close();
    }
  }

  /**
   * Test that a legacy database (user_version = 0 but tables exist) is detected and upgraded.
   *
   * <p>This simulates databases created before versioning was introduced.
   */
  @Test
  void upgradesLegacyDatabaseWithoutVersioning() throws Exception {
    Path dbPath = tempDir.resolve("legacy.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    // Create legacy database (tables exist but user_version = 0)
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      // V1 schema (no retry_after)
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT
          )
          """);

      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);

      // Explicitly leave user_version = 0 (default)
      // This simulates a legacy database

      // Insert sample data
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated)
          VALUES ('/legacy/file.txt', 'DONE', 2, 999999999)
          """);
    }

    // Open queue (should detect legacy and upgrade)
    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      // Verify schema version is now at target
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1),
            "Legacy database should be upgraded to TARGET_VERSION");
      }

      // Verify retry_after column was added
      boolean hasRetryAfter = false;
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
        while (rs.next()) {
          if ("retry_after".equals(rs.getString("name"))) {
            hasRetryAfter = true;
            break;
          }
        }
      }
      assertTrue(hasRetryAfter, "Legacy database should gain retry_after column");

      // Verify existing data preserved
      try (ResultSet rs = stmt.executeQuery("SELECT path, state, attempts FROM jobs WHERE path = '/legacy/file.txt'")) {
        assertTrue(rs.next(), "Legacy data should be preserved");
        assertEquals("DONE", rs.getString("state"));
        assertEquals(2, rs.getInt("attempts"));
      }
    } finally {
      jobQueue.close();
    }
  }

  /**
   * A pre-V11 backup restores to a usable queue and can reconstruct document identity.
   *
   * <p>Steps:
   * <ol>
   *   <li>Create a V1 database with a job</li>
   *   <li>Open SqliteJobQueue (triggers backup + migration)</li>
   *   <li>Assert jobs.db.bak exists</li>
   *   <li>Copy jobs.db.bak to a restore path and open a new SqliteJobQueue</li>
   *   <li>Verify the job data is present and queue is usable</li>
   *   <li>Import an active-index identity into the newly migrated V11 table</li>
   * </ol>
   */
  @Test
  void preV11BackupRestoreMigratesAndReconstructsDocumentIdentityFromIndexImport()
      throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    Path bakPath = tempDir.resolve("jobs.db.bak");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    // Step 1: Create V1 database with a job
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT
          )
          """);

      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");

      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);

      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");

      stmt.execute("PRAGMA user_version = 1");

      // Insert a job that will be preserved in backup
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated)
          VALUES ('/backup/test.txt', 'PENDING', 0, 1234567890)
          """);
    }

    // Step 2: Open SqliteJobQueue (triggers backup before migration)
    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    // Step 3: Assert jobs.db.bak exists
    assertTrue(Files.exists(bakPath), "jobs.db.bak should be created before migration");
    assertTrue(Files.size(bakPath) > 0, "Backup file should not be empty");

    jobQueue.close();

    // Step 4: Copy backup to a restore path and open a new SqliteJobQueue
    Path restorePath = tempDir.resolve("restored.db");
    Files.copy(bakPath, restorePath, StandardCopyOption.REPLACE_EXISTING);

    SqliteJobQueue restoredQueue = new SqliteJobQueue(restorePath);
    restoredQueue.open();

    try {
      // Step 5: Verify the queue is usable and job data is present
      // Note: The backup is at V1, so opening it triggers migration
      // The job should still be there after migration
      long depth = restoredQueue.queueDepth();
      assertTrue(depth > 0, "Restored queue should have jobs");

      // Poll the job to verify it's actually present and usable
      var jobs = restoredQueue.pollPending(10);
      assertEquals(1, jobs.size(), "Should have exactly 1 pending job from backup");
      // Path may be normalized differently on Windows; check contains to be robust
      String pathStr = jobs.get(0).path().toString();
      assertTrue(pathStr.contains("backup") && pathStr.contains("test.txt"),
          "Job path should contain expected components: " + pathStr);

      String pathHash = DocumentIdentityStore.pathHash("/backup/test.txt");
      String docUid = "00000000-0000-4000-8000-000000000011";
      try (SqliteDocumentIdentityStore identityStore =
          new SqliteDocumentIdentityStore(restorePath)) {
        assertTrue(
            identityStore.lookup(pathHash).isEmpty(),
            "the pre-V11 backup has no identity authority before active-index import");
        identityStore.importExisting(
            List.of(new DocumentIdentityStore.ImportedIdentity(pathHash, docUid)), 42L);
      }
      try (SqliteDocumentIdentityStore reopened =
          new SqliteDocumentIdentityStore(restorePath)) {
        DocumentIdentityStore.Identity identity = reopened.lookup(pathHash).orElseThrow();
        assertEquals(docUid, identity.docUid());
        assertEquals(42L, identity.firstSeenAtMs());
        assertEquals(42L, identity.lastSeenAtMs());
      }
    } finally {
      restoredQueue.close();
    }
  }

  /**
   * Test that migration failure causes transaction rollback.
   *
   * <p>This test verifies that:
   * <ul>
   *   <li>Migration throws when hook fails</li>
   *   <li>Transaction rolls back</li>
   *   <li>PRAGMA user_version remains at the pre-migration value</li>
   *   <li>No partial schema change (retry_after column absent)</li>
   * </ul>
   */
  @Test
  void migrationFailureRollsBack() throws Exception {
    Path dbPath = tempDir.resolve("rollback-test.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    // Create V1 database with a job
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT
          )
          """);

      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");

      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);

      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");

      stmt.execute("PRAGMA user_version = 1");

      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated)
          VALUES ('/rollback/test.txt', 'PENDING', 0, 1234567890)
          """);
    }

    // Create a hook that throws after V2 migration is applied
    SqliteJobQueue.MigrationStepHook failingHook = version -> {
      if (version == 2) {
        throw new SQLException("Simulated migration failure for testing");
      }
    };

    // Try to open - should throw due to migration failure
    // Note: open() may wrap SQLException in IOException in some cases
    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath, 3, null, failingHook);
    Exception thrown = null;
    try {
      jobQueue.open();
    } catch (Exception e) {
      thrown = e;
    } finally {
      // Close to release SQLite files (even if open failed mid-way)
      try {
        jobQueue.close();
      } catch (Exception ignored) {
        // Best effort cleanup
      }
    }

    // Verify exception was thrown
    assertNotNull(thrown, "Migration should throw when hook fails");
    // Verify it's either SQLException or contains one as cause
    assertTrue(
        thrown instanceof SQLException ||
        (thrown.getCause() != null && thrown.getCause() instanceof SQLException),
        "Expected SQLException or cause to be SQLException, got: " + thrown);

    // Verify rollback occurred: user_version should still be 1
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1), "user_version should remain at 1 after rollback");
      }

      // Verify no partial schema change: retry_after column should NOT exist
      boolean hasRetryAfter = false;
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
        while (rs.next()) {
          if ("retry_after".equals(rs.getString("name"))) {
            hasRetryAfter = true;
            break;
          }
        }
      }
      assertFalse(hasRetryAfter, "retry_after column should NOT exist after rollback");

      // Verify original data is still intact
      try (ResultSet rs = stmt.executeQuery("SELECT path, state FROM jobs WHERE path = '/rollback/test.txt'")) {
        assertTrue(rs.next(), "Original job should still exist after rollback");
        assertEquals("PENDING", rs.getString("state"));
      }
    }
  }

  @Test
  void migratesV4ToV5AddsIngestionOutcomeColumns() throws Exception {
    Path dbPath = tempDir.resolve("v4.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT,
            retry_after INTEGER,
            collection TEXT DEFAULT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");
      stmt.execute("CREATE INDEX idx_jobs_state_updated ON jobs(state, last_updated)");
      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");
      stmt.execute("PRAGMA user_version = 4");
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated, collection)
          VALUES ('/v4/file.txt', 'PENDING', 0, 1234567890, 'docs')
          """);
    }

    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1));
      }

      assertTrue(hasColumn(stmt, "last_outcome_class"));
      assertTrue(hasColumn(stmt, "last_reason_code"));
      assertTrue(hasColumn(stmt, "last_retry_policy"));
      assertTrue(hasColumn(stmt, "last_diagnostic_summary"));
      assertTrue(hasColumn(stmt, "last_outcome_at"));

      try (ResultSet rs =
          stmt.executeQuery("SELECT state, collection FROM jobs WHERE path = '/v4/file.txt'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString("state"));
        assertEquals("docs", rs.getString("collection"));
      }
    } finally {
      jobQueue.close();
    }
  }

  @Test
  void migratesV5ToV6AddsIngestionLedger() throws Exception {
    Path dbPath = tempDir.resolve("v5.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT,
            retry_after INTEGER,
            collection TEXT DEFAULT NULL,
            last_outcome_class TEXT,
            last_reason_code TEXT,
            last_retry_policy TEXT,
            last_diagnostic_summary TEXT,
            last_outcome_at INTEGER
          )
          """);
      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");
      stmt.execute("CREATE INDEX idx_jobs_state_updated ON jobs(state, last_updated)");
      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");
      stmt.execute("PRAGMA user_version = 5");
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated, collection)
          VALUES ('/v5/file.txt', 'PENDING', 0, 1234567890, 'docs')
          """);
    }

    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1));
      }
      assertTrue(hasTable(stmt, "ingestion_ledger"));
      try (ResultSet rs =
          stmt.executeQuery("SELECT state, collection FROM jobs WHERE path = '/v5/file.txt'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString("state"));
        assertEquals("docs", rs.getString("collection"));
      }
    } finally {
      jobQueue.close();
    }
  }

  /**
   * V7 to V8 (tempdoc 813 Slice B): the nullable {@code size_bytes} column is added and existing
   * rows keep their state, collection and a NULL size — a pre-V8 row's byte weight is genuinely
   * unknown and must not be backfilled with a fabricated 0.
   *
   * <p>V8 to V9 (tempdoc 812 D2) is asserted in the SAME walk on purpose. The two slices were
   * developed in parallel and both originally authored a "V7 to V8" step; the merge that renumbered
   * 812's to V9 could just as easily have left one rung shadowing the other, and a database stuck at
   * {@code user_version = 8} with a missing column is silent until a query fails in production.
   * Asserting BOTH columns after one ladder walk is what makes that class of collision loud.
   *
   * <p>V9 to V10 (tempdoc 885 item 21b) rides the same walk for the same reason: {@code
   * first_failed_at} is the origin the seven-day retry bound is measured from, and a database that
   * stopped one rung short would leave every failure run without one.
   */
  @Test
  void migratesV7ThroughV10AddingSizeBytesScanIdAndFirstFailedAtAndPreservingRows()
      throws Exception {
    Path dbPath = tempDir.resolve("v7.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT,
            retry_after INTEGER,
            collection TEXT DEFAULT NULL,
            last_outcome_class TEXT,
            last_reason_code TEXT,
            last_retry_policy TEXT,
            last_diagnostic_summary TEXT,
            last_outcome_at INTEGER
          )
          """);
      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");
      stmt.execute("CREATE INDEX idx_jobs_state_updated ON jobs(state, last_updated)");
      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");
      stmt.execute("""
          CREATE TABLE ingestion_ledger (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            path_hash TEXT NOT NULL,
            collection TEXT,
            outcome_class TEXT NOT NULL,
            reason_code TEXT NOT NULL,
            retry_policy TEXT NOT NULL,
            diagnostic_summary TEXT,
            observed_at INTEGER NOT NULL,
            source_size_bytes INTEGER,
            source_modified_at INTEGER,
            source_kind TEXT,
            artifact_status TEXT,
            policy_id TEXT,
            parser_id TEXT
          )
          """);
      stmt.execute("""
          CREATE TABLE path_resolution (
            path_hash TEXT PRIMARY KEY,
            normalized_path TEXT NOT NULL,
            last_seen_at INTEGER NOT NULL,
            removed_at INTEGER
          )
          """);
      stmt.execute("PRAGMA user_version = 7");
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated, collection)
          VALUES ('/v7/file.txt', 'PENDING', 0, 1234567890, 'docs')
          """);
    }

    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1));
      }
      assertTrue(hasColumn(stmt, "size_bytes"), "V8 should add the size_bytes column");
      assertTrue(hasColumn(stmt, "scan_id"), "V9 should add the scan_id column");
      // Tempdoc 885 item 21b: the same walk now also has to reach V10.
      assertTrue(
          hasColumn(stmt, "first_failed_at"), "V10 should add the first_failed_at column");
      try (ResultSet rs =
          stmt.executeQuery(
              "SELECT state, collection, size_bytes, scan_id, first_failed_at FROM jobs"
                  + " WHERE path = '/v7/file.txt'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString("state"));
        assertEquals("docs", rs.getString("collection"));
        rs.getLong("size_bytes");
        assertTrue(rs.wasNull(), "A pre-V8 row's size is unknown (NULL), not 0");
        assertNull(rs.getString("scan_id"), "A pre-V9 row belongs to no known scan");
        rs.getLong("first_failed_at");
        assertTrue(
            rs.wasNull(),
            "A pre-V10 row has no failure run in progress — NULL, not a fabricated epoch 0 that"
                + " would read as 'first failed in 1970' and exhaust on the next failure");
      }
    } finally {
      jobQueue.close();
    }

    // A migrated unknown-size row is counted, not summed as zero bytes.
    SqliteJobQueue reopened = new SqliteJobQueue(dbPath);
    reopened.open();
    try {
      JobQueue.PendingBytes bytes = reopened.pendingBytes();
      assertEquals(0L, bytes.knownBytes());
      assertEquals(1L, bytes.unknownSizeJobs());
    } finally {
      reopened.close();
    }
  }

  @Test
  void unversionedDbWithCollectionMigratesToV5WithoutDuplicateColumnFailure() throws Exception {
    Path dbPath = tempDir.resolve("unversioned-v4-shape.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT,
            retry_after INTEGER,
            collection TEXT DEFAULT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");
      stmt.execute("CREATE INDEX idx_jobs_state_updated ON jobs(state, last_updated)");
      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");
      stmt.execute("PRAGMA user_version = 0");
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated, collection)
          VALUES ('/legacy/file.txt', 'PENDING', 0, 1234567890, 'docs')
          """);
    }

    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1));
      }
      assertTrue(hasColumn(stmt, "collection"));
      assertTrue(hasColumn(stmt, "last_outcome_class"));
      try (ResultSet rs =
          stmt.executeQuery("SELECT state, collection FROM jobs WHERE path = '/legacy/file.txt'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString("state"));
        assertEquals("docs", rs.getString("collection"));
      }
    } finally {
      jobQueue.close();
    }
  }

  @Test
  void migratesV10ToV11WithPathFreeIdentitySchemaAndPreservesJobs() throws Exception {
    Path dbPath = tempDir.resolve("v10.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      stmt.execute(SqliteSchema.CREATE_JOBS_TABLE);
      // This version already owns the privacy-safe ledger; V14 alters its existing columns.
      stmt.execute(SqliteSchema.CREATE_INGESTION_LEDGER_TABLE);
      stmt.execute("PRAGMA user_version = 10");
      stmt.execute(
          "INSERT INTO jobs(path, state, attempts, last_updated)"
              + " VALUES ('/preserved.txt', 'PENDING', 0, 123)");
    }

    SqliteJobQueue queue = new SqliteJobQueue(dbPath);
    queue.open();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        // The ladder does not stop at 11: a V10 database walks through to TARGET_VERSION.
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1));
      }
      assertTrue(hasTable(stmt, "document_identity"));
      List<String> columns = new java.util.ArrayList<>();
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(document_identity)")) {
        while (rs.next()) columns.add(rs.getString("name"));
      }
      // This walks the WHOLE ladder from V10, so the shape asserted is the current one: V11's four
      // columns plus V13's deletion mark (tempdoc 931 §C.6).
      assertEquals(
          List.of("path_hash", "doc_uid", "first_seen_at", "last_seen_at", "deleted_at"), columns);
      assertFalse(columns.stream().anyMatch(name -> name.contains("path") && !name.equals("path_hash")));
      try (ResultSet rs =
          stmt.executeQuery(
              "SELECT name FROM sqlite_master WHERE type='index'"
                  + " AND name='idx_document_identity_uid'")) {
        assertTrue(rs.next());
      }
      try (ResultSet rs = stmt.executeQuery("SELECT state FROM jobs WHERE path='/preserved.txt'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString(1));
      }
    } finally {
      queue.close();
    }
  }

  @Test
  void v10ToV11FailureRollsBackTheIdentityTableAndVersion() throws Exception {
    Path dbPath = tempDir.resolve("v10-rollback.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      stmt.execute(SqliteSchema.CREATE_JOBS_TABLE);
      stmt.execute("PRAGMA user_version = 10");
    }

    SqliteJobQueue queue =
        new SqliteJobQueue(
            dbPath,
            3,
            null,
            version -> {
              if (version == 11) throw new SQLException("fail V11 after DDL");
            });
    assertThrows(SQLException.class, queue::open);
    queue.close();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(10, rs.getInt(1));
      }
      assertFalse(hasTable(stmt, "document_identity"));
    }
  }

  @Test
  void migratesV11ToCurrentPreservingQueueAndIdentityRowsAndRefusesTheNextVersion() throws Exception {
    Path dbPath = tempDir.resolve("v11.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    String pathHash = DocumentIdentityStore.pathHash("/v11/preserved.txt");
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      stmt.execute(SqliteSchema.CREATE_JOBS_TABLE);
      // This version already owns the privacy-safe ledger; V14 alters its existing columns.
      stmt.execute(SqliteSchema.CREATE_INGESTION_LEDGER_TABLE);
      stmt.execute(SqliteSchema.CREATE_DOCUMENT_IDENTITY_TABLE);
      stmt.execute(SqliteSchema.CREATE_DOCUMENT_IDENTITY_UID_INDEX);
      stmt.execute("PRAGMA user_version = 11");
      stmt.execute(
          "INSERT INTO jobs(path, state, attempts, last_updated)"
              + " VALUES ('/v11/preserved.txt', 'PENDING', 0, 123)");
      stmt.execute(
          "INSERT INTO document_identity(path_hash, doc_uid, first_seen_at, last_seen_at)"
              + " VALUES ('"
              + pathHash
              + "', 'v11-uid', 7, 7)");
    }

    SqliteJobQueue queue = new SqliteJobQueue(dbPath);
    queue.open();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(16, rs.getInt(1));
      }
      assertEquals(16, SqliteSchema.TARGET_VERSION);
      assertTrue(hasTable(stmt, "document_identity_import"));
      List<String> columns = new java.util.ArrayList<>();
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(document_identity_import)")) {
        while (rs.next()) columns.add(rs.getString("name"));
      }
      assertEquals(
          List.of(
              "generation_id",
              "imported_at",
              "parents_seen",
              "parents_imported",
              "parents_skipped"),
          columns);
      try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM document_identity_import")) {
        assertTrue(rs.next());
        assertEquals(
            0,
            rs.getInt(1),
            "a migrated V11 database has no evidence of WHICH generation it imported");
      }
      try (ResultSet rs =
          stmt.executeQuery("SELECT state FROM jobs WHERE path='/v11/preserved.txt'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString(1));
      }
    } finally {
      queue.close();
    }

    try (SqliteDocumentIdentityStore store = new SqliteDocumentIdentityStore(dbPath)) {
      assertEquals("v11-uid", store.lookup(pathHash).orElseThrow().docUid());
      assertEquals(1L, store.identityCount());
    }

    // A future database is refused, not silently downgraded.
    Path futurePath = tempDir.resolve("future.db");
    try (Connection conn =
            DriverManager.getConnection("jdbc:sqlite:" + futurePath.toAbsolutePath());
        Statement stmt = conn.createStatement()) {
      stmt.execute(SqliteSchema.CREATE_JOBS_TABLE);
      stmt.execute("PRAGMA user_version = " + (SqliteSchema.TARGET_VERSION + 1));
    }
    SqliteJobQueue future = new SqliteJobQueue(futurePath);
    try {
      SQLException refusal = assertThrows(SQLException.class, future::open);
      assertTrue(
          refusal.getMessage().contains(Integer.toString(SqliteSchema.TARGET_VERSION + 1)),
          "the refusal must name the unsupported version: " + refusal.getMessage());
    } finally {
      future.close();
    }
  }

  @Test
  void v11ToV12FailureRollsBackTheImportTableAndVersion() throws Exception {
    Path dbPath = tempDir.resolve("v11-rollback.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      stmt.execute(SqliteSchema.CREATE_JOBS_TABLE);
      stmt.execute(SqliteSchema.CREATE_DOCUMENT_IDENTITY_TABLE);
      stmt.execute("PRAGMA user_version = 11");
    }

    SqliteJobQueue queue =
        new SqliteJobQueue(
            dbPath,
            3,
            null,
            version -> {
              if (version == 12) throw new SQLException("fail V12 after DDL");
            });
    assertThrows(SQLException.class, queue::open);
    queue.close();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(11, rs.getInt(1));
      }
      assertFalse(hasTable(stmt, "document_identity_import"));
    }
  }

  @Test
  void migratesV12ToV13AddingDeletedAtWithoutDisturbingExistingIdentityRows() throws Exception {
    Path dbPath = tempDir.resolve("v12.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    String pathHash = DocumentIdentityStore.pathHash("/v12/preserved.txt");
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      stmt.execute(SqliteSchema.CREATE_JOBS_TABLE);
      // This version already owns the privacy-safe ledger; V14 alters its existing columns.
      stmt.execute(SqliteSchema.CREATE_INGESTION_LEDGER_TABLE);
      stmt.execute(SqliteSchema.CREATE_DOCUMENT_IDENTITY_TABLE);
      stmt.execute(SqliteSchema.CREATE_DOCUMENT_IDENTITY_UID_INDEX);
      stmt.execute(SqliteSchema.CREATE_DOCUMENT_IDENTITY_IMPORT_TABLE);
      stmt.execute("PRAGMA user_version = 12");
      stmt.execute(
          "INSERT INTO document_identity(path_hash, doc_uid, first_seen_at, last_seen_at)"
              + " VALUES ('"
              + pathHash
              + "', 'v12-uid', 7, 9)");
    }

    SqliteJobQueue queue = new SqliteJobQueue(dbPath);
    queue.open();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(16, rs.getInt(1));
      }
      List<String> columns = new java.util.ArrayList<>();
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(document_identity)")) {
        while (rs.next()) columns.add(rs.getString("name"));
      }
      assertEquals(
          List.of("path_hash", "doc_uid", "first_seen_at", "last_seen_at", "deleted_at"), columns);
      try (ResultSet rs =
          stmt.executeQuery("SELECT deleted_at FROM document_identity WHERE path_hash='" + pathHash + "'")) {
        assertTrue(rs.next());
        rs.getLong(1);
        assertTrue(
            rs.wasNull(),
            "nothing observed the deletion of a pre-existing row, so nothing may claim it");
      }
    } finally {
      queue.close();
    }

    try (SqliteDocumentIdentityStore store = new SqliteDocumentIdentityStore(dbPath)) {
      var migrated = store.lookup(pathHash).orElseThrow();
      assertEquals("v12-uid", migrated.docUid());
      assertEquals(7L, migrated.firstSeenAtMs());
      assertNull(migrated.deletedAtMs());
      assertEquals(1L, store.identityCount());
      // The migrated row keeps its uid at any clock: an unmarked row never enters the grace path.
      assertEquals("v12-uid", store.resolve(pathHash, Long.MAX_VALUE / 2).docUid());
    }
  }

  @Test
  void v12ToV13FailureRollsBackTheColumnAndVersion() throws Exception {
    Path dbPath = tempDir.resolve("v12-rollback.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      stmt.execute(SqliteSchema.CREATE_JOBS_TABLE);
      stmt.execute(SqliteSchema.CREATE_DOCUMENT_IDENTITY_TABLE);
      stmt.execute(SqliteSchema.CREATE_DOCUMENT_IDENTITY_IMPORT_TABLE);
      stmt.execute("PRAGMA user_version = 12");
    }

    SqliteJobQueue queue =
        new SqliteJobQueue(
            dbPath,
            3,
            null,
            version -> {
              if (version == 13) throw new SQLException("fail V13 after DDL");
            });
    assertThrows(SQLException.class, queue::open);
    queue.close();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(12, rs.getInt(1));
      }
      List<String> columns = new java.util.ArrayList<>();
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(document_identity)")) {
        while (rs.next()) columns.add(rs.getString("name"));
      }
      assertFalse(columns.contains("deleted_at"));
    }
  }

  @Test
  void v14ToCurrentPreservesRowsAndMatchesFreshSchema() throws Exception {
    Path migrated = tempDir.resolve("migrated-v14.db");
    createV14Fixture(migrated);
    try (SqliteJobQueue queue = new SqliteJobQueue(migrated)) {
      queue.open();
    }
    try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + migrated);
        Statement statement = db.createStatement()) {
      try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(16, version.getInt(1));
      }
      assertTrue(hasColumn(statement, "content_hash"));
      try (ResultSet row = statement.executeQuery(
          "SELECT state, content_hash FROM jobs WHERE path = '/c2/preserved.txt'")) {
        assertTrue(row.next());
        assertEquals("DONE", row.getString(1));
        assertNull(row.getString(2), "no pre-C2 content hash may be fabricated");
      }
      try (ResultSet row = statement.executeQuery(
          "SELECT doc_uid FROM document_identity WHERE path_hash = 'c2-preserved-hash'")) {
        assertTrue(row.next());
        assertEquals("c2-preserved-uid", row.getString(1));
      }
    }
    Path fresh = tempDir.resolve("fresh-v15.db");
    try (SqliteJobQueue queue = new SqliteJobQueue(fresh)) { queue.open(); }
    assertEquals(tableColumns(fresh), tableColumns(migrated));
  }

  @Test
  void v14ToV15DdlFailureRollsBackColumnAndVersion() throws Exception {
    Path path = tempDir.resolve("v14-rollback.db");
    createV14Fixture(path);
    try (SqliteJobQueue queue = new SqliteJobQueue(path, 3, null, version -> {
      if (version == 15) throw new SQLException("fail after V15 DDL");
    })) {
      assertThrows(SQLException.class, queue::open);
    }
    assertV14WithoutContentHash(path);
  }

  @Test
  void versionWriteFailureCannotCommitDdlWithoutItsVersion() throws Exception {
    Path path = tempDir.resolve("v14-version-failure.db");
    createV14Fixture(path);
    try (Connection real = DriverManager.getConnection("jdbc:sqlite:" + path)) {
      Connection intercepted = org.mockito.Mockito.mock(Connection.class,
          org.mockito.AdditionalAnswers.delegatesTo(real));
      org.mockito.Mockito.doAnswer(invocation -> {
        Statement statement = org.mockito.Mockito.mock(Statement.class,
            org.mockito.AdditionalAnswers.delegatesTo(real.createStatement()));
        org.mockito.Mockito.doThrow(new SQLException("fail version write")).when(statement)
            .execute("PRAGMA user_version = " + SqliteSchema.TARGET_VERSION);
        return statement;
      }).when(intercepted).createStatement();
      assertThrows(SQLException.class, () -> SqliteQueueMigrationOps.runMigrations(
          intercepted, () -> {}, null));
    }
    assertV14WithoutContentHash(path);
  }

  @Test
  void uncheckedMigrationFailureAlsoRollsBackDdlAndVersion() throws Exception {
    Path path = tempDir.resolve("v14-unchecked.db");
    createV14Fixture(path);
    try (SqliteJobQueue queue = new SqliteJobQueue(path, 3, null, version -> {
      if (version == 15) throw new IllegalStateException("unchecked failure after V15 DDL");
    })) {
      assertThrows(IllegalStateException.class, queue::open);
    }
    assertV14WithoutContentHash(path);
  }

  @Test
  void futureVersionInWalIsRefusedBeforeWritableOpen() throws Exception {
    Path path = tempDir.resolve("future-wal.db");
    try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement statement = db.createStatement()) {
      statement.execute("PRAGMA journal_mode = WAL");
      statement.execute("PRAGMA wal_autocheckpoint = 0");
      statement.execute("CREATE TABLE future_owned(value TEXT)");
      statement.execute("PRAGMA user_version = " + (SqliteSchema.TARGET_VERSION + 1));
      byte[] mainBefore = Files.readAllBytes(path);
      Path wal = path.resolveSibling(path.getFileName() + "-wal");
      byte[] walBefore = Files.readAllBytes(wal);
      try (SqliteJobQueue queue = new SqliteJobQueue(path)) {
        SQLException failure = assertThrows(SQLException.class, queue::open);
        assertTrue(failure.getMessage().contains(Integer.toString(SqliteSchema.TARGET_VERSION + 1)));
        org.junit.jupiter.api.Assertions.assertArrayEquals(mainBefore, Files.readAllBytes(path));
        org.junit.jupiter.api.Assertions.assertArrayEquals(walBefore, Files.readAllBytes(wal));
      }
    }
  }

  @Test
  void currentVersionPrivacyRepairClosesRatherThanCommittingAfterRollbackFailure() throws Exception {
    Path path = tempDir.resolve("current-privacy-rollback.db");
    try (SqliteJobQueue queue = new SqliteJobQueue(path)) { queue.open(); }
    try (Connection real = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement setup = real.createStatement()) {
      setup.execute("ALTER TABLE ingestion_ledger ADD COLUMN job_path TEXT");
      Connection intercepted = org.mockito.Mockito.mock(Connection.class,
          org.mockito.AdditionalAnswers.delegatesTo(real));
      org.mockito.Mockito.doThrow(new SQLException("rollback unavailable")).when(intercepted).rollback();
      org.mockito.Mockito.doAnswer(invocation -> {
        Statement statement = org.mockito.Mockito.mock(Statement.class,
            org.mockito.AdditionalAnswers.delegatesTo(real.createStatement()));
        org.mockito.Mockito.doThrow(new SQLException("fail after ledger rename")).when(statement)
            .execute(SqliteSchema.CREATE_INGESTION_LEDGER_TABLE);
        return statement;
      }).when(intercepted).createStatement();
      assertThrows(SQLException.class, () -> SqliteQueueMigrationOps.runMigrations(intercepted, () -> {}, null));
      assertTrue(real.isClosed(), "uncertain transaction must close instead of enabling auto-commit");
    }
    try (Connection check = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement statement = check.createStatement();
        ResultSet rows = statement.executeQuery("SELECT job_path FROM ingestion_ledger")) {
      assertFalse(rows.next());
    }
  }

  private void createV14Fixture(Path path) throws Exception {
    try (SqliteJobQueue queue = new SqliteJobQueue(path)) { queue.open(); }
    try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement statement = db.createStatement()) {
      if (hasColumn(statement, "content_hash")) {
        statement.execute("ALTER TABLE jobs DROP COLUMN content_hash");
      }
      statement.execute("PRAGMA user_version = 14");
      statement.execute("INSERT INTO jobs(path, state, attempts, last_updated)"
          + " VALUES ('/c2/preserved.txt', 'DONE', 0, 123)");
      statement.execute("INSERT INTO document_identity(path_hash, doc_uid, first_seen_at, last_seen_at)"
          + " VALUES ('c2-preserved-hash', 'c2-preserved-uid', 1, 1)");
    }
  }

  private void assertV14WithoutContentHash(Path path) throws Exception {
    try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement statement = db.createStatement()) {
      try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(14, version.getInt(1));
      }
      assertFalse(hasColumn(statement, "content_hash"));
    }
  }

  private java.util.Set<String> tableColumns(Path path) throws Exception {
    java.util.Set<String> schema = new java.util.TreeSet<>();
    try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement statement = db.createStatement()) {
      List<String> tables = new java.util.ArrayList<>();
      try (ResultSet rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
        while (rows.next()) tables.add(rows.getString(1));
      }
      for (String table : tables) {
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
          while (columns.next()) {
            schema.add(table + ":" + columns.getString("name") + ":" + columns.getString("type")
                + ":" + columns.getInt("notnull") + ":" + columns.getString("dflt_value")
                + ":" + columns.getInt("pk"));
          }
        }
      }
    }
    return schema;
  }

  private static boolean hasColumn(Statement stmt, String columnName) throws SQLException {
    try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
      while (rs.next()) {
        if (columnName.equals(rs.getString("name"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasTable(Statement stmt, String tableName) throws SQLException {
    try (ResultSet rs =
        stmt.executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='" + tableName + "'")) {
      return rs.next();
    }
  }
}
