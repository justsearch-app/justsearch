/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite coverage for C2-8c durable queue admission revisions. */
final class AdmissionRevisionTest {
  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);

  @TempDir Path temp;

  @Test
  void v16MigrationBackfillsDistinctStableRevisionsAndPreservesRowFacts() throws Exception {
    Path db = temp.resolve("v16.db");
    createCurrentFixtureWithoutRevision(db);
    insertV16Rows(db);

    Map<String, Admission> migrated;
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(SqliteSchema.TARGET_VERSION, schemaVersion(db));
      migrated = readAdmissions(db);
    }

    assertEquals(2, migrated.size());
    assertEquals(2, migrated.values().stream().map(Admission::unitRevision).distinct().count());
    for (Admission row : migrated.values()) {
      assertTrue(row.unitRevision().matches("[0-9a-f]{32}"), row::unitRevision);
    }
    assertEquals(
        new Admission("PROCESSING", 2, 777L, HASH_A, "scan-a", migrated.get("/fixture/a").unitRevision()),
        migrated.get("/fixture/a"));
    assertEquals(
        new Admission("PENDING", 1, 888L, HASH_B, "scan-b", migrated.get("/fixture/b").unitRevision()),
        migrated.get("/fixture/b"));

    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(migrated, readAdmissions(db), "migration identities must survive reopen");
    }
  }

  @Test
  void freshReplacementAndActualReenqueueMintNewRevisions() throws Exception {
    Path db = temp.resolve("replacement.db");
    Path file = temp.resolve("replacement.txt");
    JobQueue.EnqueueProvenance provenance = new JobQueue.EnqueueProvenance("user", "test");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(
          List.of(new JobQueue.EnqueueEntry(file, JobQueue.UNKNOWN_SIZE_BYTES, provenance)),
          "docs", "scan-A");
      JobQueue.IndexJob first = queue.pollPending(1).getFirst();
      assertTrue(first.unitRevision().matches("[0-9a-f]{32}"));
      assertEquals("scan-A", first.scanId());
      assertEquals(provenance, first.provenance());

      queue.enqueueEntries(
          List.of(new JobQueue.EnqueueEntry(file, JobQueue.UNKNOWN_SIZE_BYTES, provenance)),
          "docs", "scan-A");
      queue.returnUnfinishedClaims(List.of(first));
      JobQueue.IndexJob replacement = queue.pollPending(1).getFirst();
      assertNotEquals(first.unitRevision(), replacement.unitRevision());
      assertEquals("scan-A", replacement.scanId());
      assertEquals(provenance, replacement.provenance());

      assertTrue(queue.markClaimFailed(replacement, transientFailure(), null));
      setContentHash(db, file, HASH_A);
      JobQueue.ReenqueueResult result =
          queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(file));
      assertEquals(1, result.accepted());
      assertEquals("PENDING", result.previousState());
      JobQueue.IndexJob retried = queue.pollPending(1).getFirst();
      assertNotEquals(replacement.unitRevision(), retried.unitRevision());
      assertTrue(retried.unitRevision().matches("[0-9a-f]{32}"));
      assertEquals("docs", retried.collection());
      assertEquals("scan-A", retried.scanId());
      assertEquals(provenance, retried.provenance());
      JobQueue.EnqueueProvenance override = new JobQueue.EnqueueProvenance("agent", "retry-api");
      assertEquals(
          1,
          queue.reenqueue(
              new JobQueue.EnqueueEntry(file, JobQueue.UNKNOWN_SIZE_BYTES, override)).accepted());
      queue.returnUnfinishedClaims(List.of(retried));
      JobQueue.IndexJob overridden = queue.pollPending(1).getFirst();
      assertNotEquals(retried.unitRevision(), overridden.unitRevision());
      assertEquals("docs", overridden.collection());
      assertEquals("scan-A", overridden.scanId());
      assertEquals(override, overridden.provenance());
      QueueFacts facts = readQueueFacts(db, file);
      assertEquals("PROCESSING", facts.state());
      assertEquals(overridden.unitRevision(), facts.unitRevision());
      assertEquals(0, facts.attempts());
      assertNull(facts.retryAfter());
      assertNull(facts.firstFailedAt());
      assertNull(facts.contentHash());
    }
  }

  @Test
  void pollRetryDeferAndGlobalRecoveryPreserveTheAdmissionRevision() throws Exception {
    Path db = temp.resolve("lifecycle.db");
    Path file = temp.resolve("lifecycle.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, "scan-lifecycle");

      JobQueue.IndexJob first = queue.pollPending(1).getFirst();
      String revision = first.unitRevision();
      assertEquals("scan-lifecycle", first.scanId());
      assertEquals(revision, durableRevision(db));

      assertTrue(queue.markClaimFailed(first, transientFailure(), null));
      assertEquals(revision, durableRevision(db));
      clearRetryAfter(db, file);
      JobQueue.IndexJob afterRetry = queue.pollPending(1).getFirst();
      assertEquals(revision, afterRetry.unitRevision());

      assertTrue(queue.deferClaim(afterRetry, deferredOutcome(), null));
      assertEquals(revision, durableRevision(db));
      clearRetryAfter(db, file);
      JobQueue.IndexJob afterDefer = queue.pollPending(1).getFirst();
      assertEquals(revision, afterDefer.unitRevision());

      // Simulate actual owner loss, not a runtime reaper stealing a live polled claim.
      queue.close();
      queue.open();
      assertEquals(1, queue.recoverStuckJobs());
      assertEquals(revision, durableRevision(db));
      JobQueue.IndexJob afterRecovery = queue.pollPending(1).getFirst();
      assertEquals(revision, afterRecovery.unitRevision());
      assertTrue(queue.markClaimDone(afterRecovery, success(), null));
    }
  }

  @Test
  void staleAndEqualValuedForgedClaimsCannotCompleteTheCurrentAdmission() throws Exception {
    Path file = temp.resolve("claims.txt");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, "scan-1");
      JobQueue.IndexJob stale = queue.pollPending(1).getFirst();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, "scan-2");
      assertTrue(queue.pollPending(1).isEmpty(), "replacement waits for the old issued owner");
      assertFalse(queue.markClaimDone(stale, success(), null));
      JobQueue.IndexJob current = queue.pollPending(1).getFirst();
      JobQueue.IndexJob forged =
          new JobQueue.IndexJob(
              current.path(), current.collection(), current.provenance(),
              current.scanId(), current.unitRevision());

      assertEquals(current, forged, "value equality must not grant claim authority");
      assertFalse(queue.markClaimDone(forged, success(), null));
      assertFalse(queue.markClaimDone(stale, success(), null));
      assertTrue(queue.markClaimDone(current, success(), null));
    }
  }

  @Test
  void durableRevisionReplacementRefusesTheOriginalProcessingObject() throws Exception {
    Path db = temp.resolve("durable-guard.db");
    Path file = temp.resolve("durable-guard.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueue(List.of(file));
      JobQueue.IndexJob claim = queue.pollPending(1).getFirst();
      String replacement = "c".repeat(32);
      replaceDurableRevision(db, file, replacement);

      assertEquals("PROCESSING", state(db, file));
      assertFalse(
          queue.markClaimDone(claim, success(), null),
          "the in-memory claim is still active, but its durable revision is stale");
      assertEquals("PROCESSING", state(db, file));
    }
  }

  @Test
  void closeReopenRecoveryPreservesRevisionAndRejectsTheOldProcessObject() throws Exception {
    Path db = temp.resolve("reopen.db");
    Path file = temp.resolve("reopen.txt");
    JobQueue.IndexJob oldClaim;
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, "scan-reopen");
      oldClaim = queue.pollPending(1).getFirst();
    }

    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(1, queue.recoverStuckJobs());
      JobQueue.IndexJob current = queue.pollPending(1).getFirst();
      assertEquals(oldClaim.unitRevision(), current.unitRevision());
      assertEquals(oldClaim.scanId(), current.scanId());
      assertFalse(queue.markClaimDone(oldClaim, success(), null));
      assertTrue(queue.markClaimDone(current, success(), null));
    }
  }

  @Test
  void v17MigrationFailureRollsBackVersionColumnAndOriginalRows() throws Exception {
    Path db = temp.resolve("v17-rollback.db");
    createCurrentFixtureWithoutRevision(db);
    insertV16Rows(db);

    try (var queue = new SqliteJobQueue(db, 3, null, version -> {
      if (version == 17) throw new SQLException("fixture migration failure");
    })) {
      assertThrows(SQLException.class, queue::open);
    }

    assertEquals(16, schemaVersion(db));
    assertFalse(hasColumn(db, "unit_revision"));
    assertEquals(
        Map.of(
            "/fixture/a", new LegacyAdmission("PROCESSING", 2, 777L, HASH_A, "scan-a"),
            "/fixture/b", new LegacyAdmission("PENDING", 1, 888L, HASH_B, "scan-b")),
        readLegacyAdmissions(db));
  }

  private void createCurrentFixtureWithoutRevision(Path db) throws Exception {
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
    }
    execute(db, "ALTER TABLE jobs DROP COLUMN unit_revision");
    execute(db, "PRAGMA user_version = 16");
  }

  private void insertV16Rows(Path db) throws Exception {
    String sql =
        "INSERT INTO jobs(path, state, attempts, last_updated, retry_after, scan_id, content_hash)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";
    try (Connection connection = connection(db);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      insert(statement, "/fixture/a", "PROCESSING", 2, 123L, 777L, "scan-a", HASH_A);
      insert(statement, "/fixture/b", "PENDING", 1, 456L, 888L, "scan-b", HASH_B);
    }
  }

  private static void insert(
      PreparedStatement statement,
      String path,
      String state,
      int attempts,
      long updated,
      long retryAfter,
      String scanId,
      String hash)
      throws Exception {
    statement.setString(1, path);
    statement.setString(2, state);
    statement.setInt(3, attempts);
    statement.setLong(4, updated);
    statement.setLong(5, retryAfter);
    statement.setString(6, scanId);
    statement.setString(7, hash);
    statement.executeUpdate();
  }

  private static Map<String, Admission> readAdmissions(Path db) throws Exception {
    Map<String, Admission> rows = new LinkedHashMap<>();
    try (Connection connection = connection(db);
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT path, state, attempts, retry_after, content_hash, scan_id, unit_revision"
                    + " FROM jobs WHERE path LIKE '/fixture/%' ORDER BY path")) {
      while (result.next()) {
        rows.put(
            result.getString(1),
            new Admission(
                result.getString(2),
                result.getInt(3),
                result.getLong(4),
                result.getString(5),
                result.getString(6),
                result.getString(7)));
      }
    }
    return rows;
  }

  private static String durableRevision(Path db) throws Exception {
    try (Connection connection = connection(db);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT unit_revision FROM jobs")) {
      assertTrue(result.next());
      String revision = result.getString(1);
      assertFalse(result.next());
      return revision;
    }
  }

  private static void clearRetryAfter(Path db, Path file) throws Exception {
    try (Connection connection = connection(db);
        PreparedStatement statement =
            connection.prepareStatement("UPDATE jobs SET retry_after = NULL WHERE path = ?")) {
      statement.setString(1, normalized(file));
      statement.executeUpdate();
    }
  }

  private static void setContentHash(Path db, Path file, String hash) throws Exception {
    try (Connection connection = connection(db);
        PreparedStatement statement = connection.prepareStatement(
            "UPDATE jobs SET content_hash = ? WHERE path = ?")) {
      statement.setString(1, hash);
      statement.setString(2, normalized(file));
      statement.executeUpdate();
    }
  }

  private static void replaceDurableRevision(Path db, Path file, String revision)
      throws Exception {
    try (Connection connection = connection(db);
        PreparedStatement statement = connection.prepareStatement(
            "UPDATE jobs SET unit_revision = ?, state = 'PROCESSING' WHERE path = ?")) {
      statement.setString(1, revision);
      statement.setString(2, normalized(file));
      statement.executeUpdate();
    }
  }

  private static QueueFacts readQueueFacts(Path db, Path file) throws Exception {
    try (Connection connection = connection(db);
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT state, attempts, retry_after, first_failed_at, content_hash, unit_revision"
                    + " FROM jobs WHERE path = ?")) {
      statement.setString(1, normalized(file));
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        long retryAfter = result.getLong(3);
        boolean retryAfterWasNull = result.wasNull();
        long firstFailedAt = result.getLong(4);
        boolean firstFailedAtWasNull = result.wasNull();
        return new QueueFacts(
            result.getString(1),
            result.getInt(2),
            retryAfterWasNull ? null : retryAfter,
            firstFailedAtWasNull ? null : firstFailedAt,
            result.getString(5),
            result.getString(6));
      }
    }
  }

  private static Map<String, LegacyAdmission> readLegacyAdmissions(Path db) throws Exception {
    Map<String, LegacyAdmission> rows = new LinkedHashMap<>();
    try (Connection connection = connection(db);
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT path, state, attempts, retry_after, content_hash, scan_id"
                    + " FROM jobs WHERE path LIKE '/fixture/%' ORDER BY path")) {
      while (result.next()) {
        rows.put(
            result.getString(1),
            new LegacyAdmission(
                result.getString(2),
                result.getInt(3),
                result.getLong(4),
                result.getString(5),
                result.getString(6)));
      }
    }
    return rows;
  }

  private static String state(Path db, Path file) throws Exception {
    try (Connection connection = connection(db);
        PreparedStatement statement = connection.prepareStatement("SELECT state FROM jobs WHERE path = ?")) {
      statement.setString(1, normalized(file));
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getString(1);
      }
    }
  }

  private static int schemaVersion(Path db) throws Exception {
    try (Connection connection = connection(db);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }

  private static boolean hasColumn(Path db, String column) throws Exception {
    try (Connection connection = connection(db);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA table_info(jobs)")) {
      while (result.next()) {
        if (column.equals(result.getString("name"))) return true;
      }
      return false;
    }
  }

  private static void execute(Path db, String sql) throws Exception {
    try (Connection connection = connection(db); Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Connection connection(Path db) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
  }

  private static String normalized(Path path) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizePath(
        path.toAbsolutePath().toString());
  }

  private static IngestionOutcome transientFailure() {
    return IngestionOutcome.of(
        IngestionOutcomeClass.IO_FAILED,
        IngestionReasonCodes.IO_ERROR,
        IngestionRetryPolicy.RETRY_WITH_BACKOFF);
  }

  private static IngestionOutcome deferredOutcome() {
    return IngestionOutcome.of(
        IngestionOutcomeClass.WRITE_UNAVAILABLE_DRAINING,
        IngestionReasonCodes.WRITE_UNAVAILABLE_DRAINING,
        IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT);
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(
        IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }

  private record Admission(
      String state, int attempts, long retryAfter, String contentHash, String scanId,
      String unitRevision) {}

  private record QueueFacts(
      String state, int attempts, Long retryAfter, Long firstFailedAt, String contentHash,
      String unitRevision) {}

  private record LegacyAdmission(
      String state, int attempts, long retryAfter, String contentHash, String scanId) {}
}
