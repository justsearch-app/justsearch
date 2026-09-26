/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real SQLite coverage for policy retirement of refused recorded walks. */
final class RecordedWalkRefusalRetirementTest {
  private static final String PLAN = "a".repeat(64);
  private static final String WRONG_PLAN = "b".repeat(64);
  private static final String INDEXED_HASH = "1".repeat(64);
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @Test
  void retirementRequiresAnExistingClosedCompleteWalkAndItsExactPlanHash() throws Exception {
    Path db = temp.resolve("eligibility.db");
    String key = key(31);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();

      assertThrows(JobQueue.RecordedWalkGapException.class,
          () -> queue.retireRefusedRecordedWalk(key, PLAN), "missing progress is not evidence");

      var open = queue.beginRecordedWalk(key, PLAN, true);
      assertThrows(JobQueue.RecordedWalkGapException.class,
          () -> queue.retireRefusedRecordedWalk(key, PLAN), "an open enumeration is not retireable");
      assertEquals(open, queue.recordedWalk(key).orElseThrow());

      var complete = queue.closeRecordedWalkEnumeration(key, open.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertThrows(JobQueue.RecordedWalkGapException.class,
          () -> queue.retireRefusedRecordedWalk(key, WRONG_PLAN), "a different plan cannot retire this walk");
      assertEquals(complete, queue.recordedWalk(key).orElseThrow());

      String failedKey = key(32);
      var failedOpen = queue.beginRecordedWalk(failedKey, PLAN, true);
      var failed = queue.closeRecordedWalkEnumeration(failedKey, failedOpen.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.FAILED);
      assertThrows(JobQueue.RecordedWalkGapException.class,
          () -> queue.retireRefusedRecordedWalk(failedKey, PLAN), "only COMPLETE enumeration is retireable");
      assertEquals(failed, queue.recordedWalk(failedKey).orElseThrow());
    }
  }

  @Test
  void issuedClaimBlocksEveryRetirementUntilReturnedThenOnlyUnfinishedMembersAreSkipped() throws Exception {
    String key = key(33);
    Path db = temp.resolve("issued-claim.db");
    Path activePath = temp.resolve("active.txt");
    Path indexedPath = temp.resolve("indexed.txt");
    Path pendingPath = temp.resolve("pending.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, opened.enumerationEpoch(), List.of(
          JobQueue.EnqueueEntry.ofUnknownSize(activePath),
          JobQueue.EnqueueEntry.ofUnknownSize(indexedPath),
          JobQueue.EnqueueEntry.ofUnknownSize(pendingPath)), null);

      var claims = queue.pollPending(2);
      assertEquals(2, claims.size());
      var active = claims.stream().filter(claim -> claim.path().equals(normalizedPath(activePath)))
          .findFirst().orElseThrow();
      var indexed = claims.stream().filter(claim -> claim.path().equals(normalizedPath(indexedPath)))
          .findFirst().orElseThrow();
      queue.markDoneTransitions(List.of(
          new JobQueue.IngestionLedgerTransition(indexed, null, INDEXED_HASH)), success());
      var closed = queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);

      JobSnapshot indexedBefore = job(db, indexedPath);
      List<LedgerSnapshot> indexedLedgerBefore = indexedLedger(db, key, indexed.unitRevision());
      long skippedBefore = skippedLedgerCount(db, key);
      assertTrue(queue.hasIssuedRecordedClaims(key));
      assertEquals("PROCESSING", job(db, activePath).state());
      assertEquals("PENDING", job(db, pendingPath).state());

      var blocked = queue.retireRefusedRecordedWalk(key, PLAN);
      assertEquals(closed, blocked, "one live claim blocks retirement for every member");
      assertEquals("PROCESSING", job(db, activePath).state());
      assertEquals("PENDING", job(db, pendingPath).state());
      assertEquals(skippedBefore, skippedLedgerCount(db, key));

      queue.returnUnfinishedClaims(List.of(active));
      assertFalse(queue.hasIssuedRecordedClaims(key), "the exact returned claim releases the barrier");
      assertEquals("PENDING", job(db, activePath).state());

      var retired = queue.retireRefusedRecordedWalk(key, PLAN);
      assertEquals(closed.enumerationEpoch(), retired.enumerationEpoch());
      assertEquals(closed.enumerationClosedAt(), retired.enumerationClosedAt());
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, retired.enumerationOutcome());
      assertSkipped(job(db, activePath));
      assertSkipped(job(db, pendingPath));
      assertEquals(2, skippedLedgerCount(db, key) - skippedBefore);
      assertPolicyCoverage(skippedLedger(db, key));

      assertEquals(indexedBefore, job(db, indexedPath), "indexed job evidence remains unchanged");
      assertEquals(indexedLedgerBefore, indexedLedger(db, key, indexed.unitRevision()),
          "indexed ledger evidence remains unchanged");
      assertEquals(3, queue.completedCount());

      List<LedgerSnapshot> skippedAfterFirstRetirement = skippedLedger(db, key);
      var retry = queue.retireRefusedRecordedWalk(key, PLAN);
      assertEquals(retired, retry, "a repeated retirement does not advance the projection");
      assertEquals(skippedAfterFirstRetirement, skippedLedger(db, key),
          "a repeated retirement does not append duplicate coverage");
      assertEquals(indexedBefore, job(db, indexedPath));
      assertEquals(indexedLedgerBefore, indexedLedger(db, key, indexed.unitRevision()));

      var sealed = queue.trySealRecordedWalk(key);
      assertEquals(2, receipt(sealed).path("currentSkippedUnits").asLong());
      assertEquals("COMPLETE", receipt(sealed).path("enumerationOutcome").asText());
      var receiptBeforeRetry = queue.sealedRecordedWalkReceipt(key).orElseThrow();
      var sealedRetry = queue.retireRefusedRecordedWalk(key, PLAN);
      assertEquals(sealed, sealedRetry);
      assertEquals(receiptBeforeRetry, queue.sealedRecordedWalkReceipt(key).orElseThrow(),
          "retirement after sealing leaves the immutable receipt unchanged");
    }
  }

  @Test
  void pendingAndCrashOrphanedProcessingBecomePolicyCoverageUnderDefaultDeny() throws Exception {
    String key = key(34);
    Path db = temp.resolve("orphaned-processing.db");
    Path orphanPath = temp.resolve("orphan.txt");
    Path pendingPath = temp.resolve("pending-under-deny.txt");
    long epoch;
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      epoch = opened.enumerationEpoch();
      queue.enqueueRecordedEntries(key, epoch, List.of(
          JobQueue.EnqueueEntry.ofUnknownSize(orphanPath),
          JobQueue.EnqueueEntry.ofUnknownSize(pendingPath)), null);
      assertEquals(1, queue.pollPending(1).size());
      queue.closeRecordedWalkEnumeration(key, epoch, JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertEquals("PROCESSING", job(db, orphanPath).state());
      assertEquals("PENDING", job(db, pendingPath).state());
    }

    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.pollPending(10).isEmpty(), "the default authority denies a new execution claim");
      assertFalse(queue.hasIssuedRecordedClaims(key), "the prior process no longer owns the orphan");

      var retired = queue.retireRefusedRecordedWalk(key, PLAN);
      assertEquals(epoch, retired.enumerationEpoch());
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, retired.enumerationOutcome());
      assertSkipped(job(db, orphanPath));
      assertSkipped(job(db, pendingPath));
      assertEquals(2, skippedLedgerCount(db, key));
      assertPolicyCoverage(skippedLedger(db, key));
      assertTrue(queue.pollPending(10).isEmpty(), "administrative retirement grants no execution permit");
      assertFalse(queue.hasIssuedRecordedClaims(key));
    }
  }

  private static void assertSkipped(JobSnapshot job) {
    assertEquals("DONE", job.state());
    assertNull(job.contentHash());
    assertEquals("SKIPPED_POLICY", job.outcomeClass());
    assertEquals("RECOVERY_REFUSED", job.reasonCode());
    assertEquals("NONE", job.retryPolicy());
  }

  private static void assertPolicyCoverage(List<LedgerSnapshot> coverage) {
    assertEquals(2, coverage.size());
    assertTrue(coverage.stream().allMatch(row ->
        "SKIPPED_POLICY".equals(row.outcomeClass())
            && "RECOVERY_REFUSED".equals(row.reasonCode())
            && "NONE".equals(row.retryPolicy())
            && "SKIPPED".equals(row.terminalCoverage())
            && row.contentHash() == null));
  }

  private static JsonNode receipt(JobQueue.WalkProgress progress) throws Exception {
    return JSON.readTree(progress.receiptJson());
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }

  private static String key(int suffix) {
    return "01994180-0000-7000-8000-000000000" + String.format("%03d", suffix);
  }

  private static Path normalizedPath(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private static String normalized(Path path) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizePath(path.toAbsolutePath().toString());
  }

  private static JobSnapshot job(Path db, Path path) throws Exception {
    try (Connection connection = connection(db);
        var query = connection.prepareStatement("SELECT state, content_hash, attempts, "
            + "last_outcome_class, last_reason_code, last_retry_policy FROM jobs WHERE path = ?")) {
      query.setString(1, normalized(path));
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next(), "expected job row for " + path);
        return new JobSnapshot(rows.getString(1), rows.getString(2), rows.getInt(3),
            rows.getString(4), rows.getString(5), rows.getString(6));
      }
    }
  }

  private static List<LedgerSnapshot> indexedLedger(Path db, String key, String unitRevision)
      throws Exception {
    return ledger(db, key, unitRevision, "INDEXED");
  }

  private static List<LedgerSnapshot> skippedLedger(Path db, String key) throws Exception {
    return ledger(db, key, null, "SKIPPED");
  }

  private static List<LedgerSnapshot> ledger(Path db, String key, String unitRevision, String coverage)
      throws Exception {
    String sql = "SELECT outcome_class, reason_code, retry_policy, terminal_coverage, content_hash "
        + "FROM ingestion_ledger WHERE operation_key = ? AND terminal_coverage = ?"
        + (unitRevision == null ? " ORDER BY id" : " AND unit_revision = ? ORDER BY id");
    try (Connection connection = connection(db); var query = connection.prepareStatement(sql)) {
      query.setString(1, key);
      query.setString(2, coverage);
      if (unitRevision != null) query.setString(3, unitRevision);
      try (ResultSet rows = query.executeQuery()) {
        var values = new java.util.ArrayList<LedgerSnapshot>();
        while (rows.next()) {
          values.add(new LedgerSnapshot(rows.getString(1), rows.getString(2), rows.getString(3),
              rows.getString(4), rows.getString(5)));
        }
        return List.copyOf(values);
      }
    }
  }

  private static long skippedLedgerCount(Path db, String key) throws Exception {
    try (Connection connection = connection(db);
        var query = connection.prepareStatement("SELECT COUNT(*) FROM ingestion_ledger "
            + "WHERE operation_key = ? AND terminal_coverage = 'SKIPPED'")) {
      query.setString(1, key);
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next());
        return rows.getLong(1);
      }
    }
  }

  private static Connection connection(Path db) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
  }

  private record JobSnapshot(String state, String contentHash, int attempts, String outcomeClass,
      String reasonCode, String retryPolicy) {}

  private record LedgerSnapshot(String outcomeClass, String reasonCode, String retryPolicy,
      String terminalCoverage, String contentHash) {}
}
