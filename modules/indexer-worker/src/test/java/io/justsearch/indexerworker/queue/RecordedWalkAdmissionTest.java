/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite coverage for recorded-walk admission and same-walk re-enumeration. */
final class RecordedWalkAdmissionTest {
  private static final String KEY = "01994180-0000-7000-8000-000000000101";
  private static final String OTHER_KEY = "01994180-0000-7000-8000-000000000102";
  private static final String PLAN = "a".repeat(64);

  @TempDir Path temp;

  @Test
  void admissionRequiresKnownOpenExactEpoch() throws Exception {
    Path db = temp.resolve("guards.db");
    Path path = temp.resolve("guard.txt");
    JobQueue.EnqueueEntry entry = JobQueue.EnqueueEntry.ofUnknownSize(path);

    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertThrows(
          IllegalStateException.class,
          () -> queue.enqueueRecordedEntries(KEY, 1, List.of(entry), "docs"));

      var first = queue.beginRecordedWalk(KEY, PLAN, true);
      assertEquals(1, first.enumerationEpoch());
      assertEquals(
          0,
          queue.enqueueRecordedEntries(KEY, first.enumerationEpoch(), List.of(), "docs"));

      var resumed = queue.beginRecordedWalk(KEY, PLAN, false);
      assertEquals(2, resumed.enumerationEpoch());
      assertThrows(
          IllegalStateException.class,
          () ->
              queue.enqueueRecordedEntries(KEY, first.enumerationEpoch(), List.of(entry), "docs"));

      queue.closeRecordedWalkEnumeration(
          KEY, resumed.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.FAILED);
      assertThrows(
          IllegalStateException.class,
          () ->
              queue.enqueueRecordedEntries(
                  KEY, resumed.enumerationEpoch(), List.of(entry), "docs"));
    }
  }

  @Test
  void freshAdmissionRecordsFactsAndSameWalkReenumerationPreservesThem() throws Exception {
    Path db = temp.resolve("same-walk.db");
    Path retryPath = temp.resolve("retry.txt");
    Path failedPath = temp.resolve("failed.txt");
    JobQueue.EnqueueProvenance original = new JobQueue.EnqueueProvenance("agent", "recorded-test");

    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var first = queue.beginRecordedWalk(KEY, PLAN, true);
      assertEquals(
          2,
          queue.enqueueRecordedEntries(
              KEY,
              first.enumerationEpoch(),
              List.of(
                  new JobQueue.EnqueueEntry(retryPath, 17L, original),
                  new JobQueue.EnqueueEntry(failedPath, 23L, original)),
              "docs"));

      assertFreshFacts(db, retryPath, KEY, 1L, "docs", original, 17L);
      assertFreshFacts(db, failedPath, KEY, 1L, "docs", original, 23L);
      assertNotEquals(
          readFacts(db, retryPath).orElseThrow().unitRevision(),
          readFacts(db, failedPath).orElseThrow().unitRevision());
      var claims = queue.pollPending(2);
      assertEquals(2, claims.size());
      for (JobQueue.IndexJob claim : claims) {
        if (normalize(claim.path()).equals(normalize(retryPath))) {
          assertTrue(queue.markClaimFailed(claim, transientFailure(), null));
        } else {
          assertTrue(queue.markClaimFailed(claim, terminalFailure(), null));
        }
      }

      setContentHash(db, retryPath, "b".repeat(64));
      setContentHash(db, failedPath, "c".repeat(64));
      Facts beforeRetry = readFacts(db, retryPath).orElseThrow();
      Facts beforeFailed = readFacts(db, failedPath).orElseThrow();
      assertEquals("PENDING", beforeRetry.state());
      assertNotNull(beforeRetry.retryAfter());
      assertEquals("FAILED", beforeFailed.state());
      assertNull(beforeFailed.retryAfter());
      assertNotNull(beforeRetry.firstFailedAt());
      assertNotNull(beforeFailed.firstFailedAt());

      long previousRevision = queue.recordedWalk(KEY).orElseThrow().revision();
      var second = queue.beginRecordedWalk(KEY, PLAN, false);
      assertEquals(2, second.enumerationEpoch());
      assertEquals(previousRevision + 1, second.revision());
      JobQueue.EnqueueProvenance replay = new JobQueue.EnqueueProvenance("system", "reenumeration");
      assertEquals(
          2,
          queue.enqueueRecordedEntries(
              KEY,
              second.enumerationEpoch(),
              List.of(
                  new JobQueue.EnqueueEntry(retryPath, 99L, replay),
                  new JobQueue.EnqueueEntry(failedPath, 101L, replay)),
              "changed-collection"));
      assertEquals(second.revision() + 1, queue.recordedWalk(KEY).orElseThrow().revision());

      Facts afterRetry = readFacts(db, retryPath).orElseThrow();
      Facts afterFailed = readFacts(db, failedPath).orElseThrow();
      assertPreservedExceptEpoch(beforeRetry, afterRetry, 2L);
      assertPreservedExceptEpoch(beforeFailed, afterFailed, 2L);
    }
  }

  @Test
  void activeRecordedWalkOwnsAnAdmittedPathAndBatchConflictRollsBackEarlierEntries()
      throws Exception {
    Path db = temp.resolve("ownership.db");
    Path ownedPath = temp.resolve("owned.txt");
    Path firstPath = temp.resolve("first.txt");
    JobQueue.EnqueueEntry owned = JobQueue.EnqueueEntry.ofUnknownSize(ownedPath);

    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var other = queue.beginRecordedWalk(OTHER_KEY, PLAN, true);
      assertEquals(
          1,
          queue.enqueueRecordedEntries(
              OTHER_KEY, other.enumerationEpoch(), List.of(owned), "other"));
      Facts ownedBefore = readFacts(db, ownedPath).orElseThrow();

      var current = queue.beginRecordedWalk(KEY, PLAN, true);
      assertThrows(
          IllegalStateException.class,
          () ->
              queue.enqueueRecordedEntries(
                  KEY, current.enumerationEpoch(), List.of(owned), "stolen"));
      assertEquals(ownedBefore, readFacts(db, ownedPath).orElseThrow());

      Path firstPathBeforeConflict = firstPath;
      assertThrows(
          IllegalStateException.class,
          () ->
              queue.enqueueRecordedEntries(
                  KEY,
                  current.enumerationEpoch(),
                  List.of(JobQueue.EnqueueEntry.ofUnknownSize(firstPathBeforeConflict), owned),
                  "docs"));
      assertTrue(
          readFacts(db, firstPath).isEmpty(),
          "the first batch entry must roll back with the conflict");
      assertEquals(ownedBefore, readFacts(db, ownedPath).orElseThrow());
      assertEquals(1, queue.recordedWalk(KEY).orElseThrow().revision());
    }
  }

  private static void assertFreshFacts(
      Path db,
      Path path,
      String key,
      long epoch,
      String collection,
      JobQueue.EnqueueProvenance provenance,
      long sizeBytes)
      throws Exception {
    Facts facts = readFacts(db, path).orElseThrow();
    assertEquals("PENDING", facts.state());
    assertEquals(0, facts.attempts());
    assertNull(facts.retryAfter());
    assertNull(facts.firstFailedAt());
    assertNull(facts.contentHash());
    assertEquals(collection, facts.collection());
    assertEquals(provenance.originator(), facts.originator());
    assertEquals(provenance.transport(), facts.transport());
    assertEquals(key, facts.scanId());
    assertEquals(epoch, facts.walkSeenEpoch());
    assertEquals(sizeBytes, facts.sizeBytes());
    assertTrue(facts.unitRevision().matches("[0-9a-f]{32}"), facts::unitRevision);
  }

  private static void assertPreservedExceptEpoch(Facts before, Facts after, long epoch) {
    assertEquals(before.state(), after.state());
    assertEquals(before.attempts(), after.attempts());
    assertEquals(before.retryAfter(), after.retryAfter());
    assertEquals(before.firstFailedAt(), after.firstFailedAt());
    assertEquals(before.contentHash(), after.contentHash());
    assertEquals(before.collection(), after.collection());
    assertEquals(before.originator(), after.originator());
    assertEquals(before.transport(), after.transport());
    assertEquals(before.scanId(), after.scanId());
    assertEquals(before.unitRevision(), after.unitRevision());
    assertEquals(before.sizeBytes(), after.sizeBytes());
    assertEquals(epoch, after.walkSeenEpoch());
  }

  private static Optional<Facts> readFacts(Path db, Path path) throws Exception {
    String sql =
        "SELECT state, attempts, retry_after, first_failed_at, content_hash, collection, "
            + "originator, transport, scan_id, unit_revision, walk_seen_epoch, size_bytes "
            + "FROM jobs WHERE path = ?";
    try (Connection connection = connection(db);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, normalize(path));
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new Facts(
                row.getString("state"),
                row.getInt("attempts"),
                nullableLong(row, "retry_after"),
                nullableLong(row, "first_failed_at"),
                row.getString("content_hash"),
                row.getString("collection"),
                row.getString("originator"),
                row.getString("transport"),
                row.getString("scan_id"),
                row.getString("unit_revision"),
                nullableLong(row, "walk_seen_epoch"),
                nullableLong(row, "size_bytes")));
      }
    }
  }

  private static void setContentHash(Path db, Path path, String contentHash) throws Exception {
    try (Connection connection = connection(db);
        PreparedStatement statement =
            connection.prepareStatement("UPDATE jobs SET content_hash = ? WHERE path = ?")) {
      statement.setString(1, contentHash);
      statement.setString(2, normalize(path));
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static Connection connection(Path db) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
  }

  private static Long nullableLong(ResultSet row, String column) throws Exception {
    long value = row.getLong(column);
    return row.wasNull() ? null : value;
  }

  private static String normalize(Path path) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizePath(
        path.toAbsolutePath().toString());
  }

  private static IngestionOutcome transientFailure() {
    return IngestionOutcome.of(
        IngestionOutcomeClass.IO_FAILED,
        IngestionReasonCodes.IO_ERROR,
        IngestionRetryPolicy.RETRY_WITH_BACKOFF);
  }

  private static IngestionOutcome terminalFailure() {
    return IngestionOutcome.of(
        IngestionOutcomeClass.PARSER_FAILED,
        IngestionReasonCodes.PARSER_FAILED,
        IngestionRetryPolicy.NONE);
  }

  private record Facts(
      String state,
      int attempts,
      Long retryAfter,
      Long firstFailedAt,
      String contentHash,
      String collection,
      String originator,
      String transport,
      String scanId,
      String unitRevision,
      Long walkSeenEpoch,
      Long sizeBytes) {}
}
