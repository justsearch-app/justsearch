/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C2 completion must belong to the claimed unit, even if the same path is enqueued again. */
final class OperationQueueOwnershipTest {
  private static final String RECORDED_KEY = "01994180-0000-7000-8000-000000000151";
  private static final String PLAN = "a".repeat(64);
  private static final String R0_HASH = "0".repeat(64);
  private static final String R1_HASH = "1".repeat(64);

  @TempDir Path temp;

  @Test
  void anOlderCommittedUnitCannotCompleteAReplacementClaim() throws Exception {
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, "scan-A");
      var firstClaim = queue.pollPending(1).getFirst();
      // A replacement is pending while the prior issued effect awaits its commit. It must not
      // acquire another owner or be completed by the old effect.
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, "scan-B");
      assertTrue(queue.pollPending(1).isEmpty());
      queue.markDoneTransitions(
          List.of(new JobQueue.IngestionLedgerTransition(firstClaim, null)),
          IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
              IngestionRetryPolicy.NONE));
      assertEquals(1, queue.queueDepth(),
          "the replacement claim must remain open until its own effect commits");
      var secondClaim = queue.pollPending(1).getFirst();
      assertTrue(queue.markClaimDone(secondClaim, success(), null));
      assertEquals(0, queue.queueDepth());
    }
  }

  @Test
  void supersededUntaggedBatchCompletionRecordsR0WithoutCompletingRecordedR1() throws Exception {
    Path db = temp.resolve("superseded-untagged.db");
    Path file = temp.resolve("same-path.txt");
    var r0Provenance = new JobQueue.EnqueueProvenance("agent", "watcher-r0");
    var r1Provenance = new JobQueue.EnqueueProvenance("agent", "recorded-r1");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      queue.enqueueEntries(
          List.of(new JobQueue.EnqueueEntry(file, JobQueue.UNKNOWN_SIZE_BYTES, r0Provenance)), null, null);
      JobQueue.IndexJob r0 = queue.pollPending(1).getFirst();
      assertNull(r0.scanId());
      assertNull(r0.walkEpoch());

      var opened = queue.beginRecordedWalk(RECORDED_KEY, PLAN, true);
      assertEquals(1, queue.enqueueRecordedEntries(
          RECORDED_KEY,
          opened.enumerationEpoch(),
          List.of(new JobQueue.EnqueueEntry(file, JobQueue.UNKNOWN_SIZE_BYTES, r1Provenance)),
          null));
      String r1Revision = string(db, "SELECT unit_revision FROM jobs WHERE path = '" + normalized(file) + "'");
      assertNotNull(r1Revision);
      assertFalse(r0.unitRevision().equals(r1Revision), "the recorded admission replaces R0's durable row");
      long r1ProgressRevision = queue.recordedWalk(RECORDED_KEY).orElseThrow().revision();
      assertEquals("PENDING", string(db,
          "SELECT state FROM jobs WHERE path = '" + normalized(file) + "'"));
      assertTrue(queue.pollPending(1).isEmpty(), "the issued R0 owner blocks polling its pending R1 replacement");

      var r0Completion = new JobQueue.IngestionLedgerTransition(r0, null, R0_HASH);
      queue.markDoneTransitions(List.of(r0Completion), success());

      assertEquals("PENDING", string(db,
          "SELECT state FROM jobs WHERE path = '" + normalized(file) + "'"));
      assertEquals(r1Revision, string(db,
          "SELECT unit_revision FROM jobs WHERE path = '" + normalized(file) + "'"));
      assertNull(string(db, "SELECT content_hash FROM jobs WHERE path = '" + normalized(file) + "'"));
      assertEquals(r1ProgressRevision, queue.recordedWalk(RECORDED_KEY).orElseThrow().revision());
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger"));
      assertLedgerEntry(db, "watcher-r0", null, null, null, null);

      // A repeated callback for the same issued object is no longer eligible and has no second effect.
      queue.markDoneTransitions(List.of(r0Completion), success());
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger"));
      assertEquals("PENDING", string(db,
          "SELECT state FROM jobs WHERE path = '" + normalized(file) + "'"));
      assertEquals(r1Revision, string(db,
          "SELECT unit_revision FROM jobs WHERE path = '" + normalized(file) + "'"));
      assertEquals(r1ProgressRevision, queue.recordedWalk(RECORDED_KEY).orElseThrow().revision());

      JobQueue.IndexJob r1 = queue.pollPending(1).getFirst();
      assertEquals(RECORDED_KEY, r1.scanId());
      assertEquals(opened.enumerationEpoch(), r1.walkEpoch());
      assertEquals(r1Revision, r1.unitRevision());
      queue.markDoneTransitions(
          List.of(new JobQueue.IngestionLedgerTransition(r1, null, R1_HASH)), success());
      assertEquals("DONE", string(db,
          "SELECT state FROM jobs WHERE path = '" + normalized(file) + "'"));
      assertEquals(R1_HASH, string(db,
          "SELECT content_hash FROM jobs WHERE path = '" + normalized(file) + "'"));

      queue.closeRecordedWalkEnumeration(
          RECORDED_KEY, opened.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      var sealed = queue.trySealRecordedWalk(RECORDED_KEY);
      assertNotNull(sealed.sealedAt());
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, sealed.enumerationOutcome());
      assertEquals(1, sealed.completedUnits());
      assertEquals(0, sealed.failedUnits());
      assertEquals(sealed, queue.trySealRecordedWalk(RECORDED_KEY));
      assertEquals(sealed.receiptJson(), queue.recordedWalk(RECORDED_KEY).orElseThrow().receiptJson());
      var receipt = queue.sealedRecordedWalkReceipt(RECORDED_KEY).orElseThrow();
      assertEquals(sealed.revision(), receipt.revision());
      assertEquals(1, receipt.completedUnits());
      assertEquals(0, receipt.failedUnits());
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, receipt.enumerationOutcome());

      assertEquals(2, count(db, "SELECT count(*) FROM ingestion_ledger"));
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger WHERE terminal_coverage IS NOT NULL"));
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_ledger WHERE operation_key IS NULL "
          + "AND terminal_coverage IS NOT NULL"));
      assertLedgerEntry(db, "recorded-r1", RECORDED_KEY, r1Revision, R1_HASH, "INDEXED");
    }
  }

  @Test
  void equalValuedOrAlreadyFinishedClaimsHaveNoAuthority() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      queue.enqueue(List.of(temp.resolve("document.txt")));
      var claim = queue.pollPending(1).getFirst();
      var forged = new JobQueue.IndexJob(
          claim.path(), claim.collection(), claim.provenance(), claim.scanId(), claim.unitRevision());
      assertEquals(claim, forged, "record value equality is deliberately insufficient");
      assertFalse(queue.markClaimDone(forged, success(), null));
      var transition = new JobQueue.IngestionLedgerTransition(claim, null);
      queue.markDoneTransitions(List.of(transition, transition), success());
      assertFalse(queue.markClaimDone(claim, success(), null));
      assertEquals(1, queue.recentIngestionEvents(10).size());
    }
  }

  @Test
  void staleFailureAndDeferCannotChangeReplacementOrPendingAdmission() throws Exception {
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      queue.enqueue(List.of(file));
      var old = queue.pollPending(1).getFirst();
      queue.enqueue(List.of(file));
      assertFalse(queue.markClaimDone(old, success(), null));
      assertEquals(1, queue.jobStateCounts().pendingCount());
      var replacement = queue.pollPending(1).getFirst();
      assertFalse(queue.markClaimFailed(old, IngestionOutcome.of(
          IngestionOutcomeClass.WRITE_FAILED, "WRITE_FAILED", IngestionRetryPolicy.NONE), null));
      assertFalse(queue.deferClaim(old, IngestionOutcome.of(
          IngestionOutcomeClass.WRITE_UNAVAILABLE_DRAINING, "WRITE_UNAVAILABLE_DRAINING",
          IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT), null));
      assertEquals(1, queue.jobStateCounts().processingCount());
      assertTrue(queue.markClaimDone(replacement, success(), null));
    }
  }

  @Test
  void aRolledBackReplacementBatchPreservesTheOriginalClaim() throws Exception {
    Path db = temp.resolve("jobs.db");
    Path first = temp.resolve("first.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueue(List.of(first));
      var claim = queue.pollPending(1).getFirst();
      execute(db, "CREATE TRIGGER refuse_second BEFORE INSERT ON jobs "
          + "WHEN NEW.path LIKE '%second.txt' BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      assertEquals(0, queue.enqueue(List.of(first, temp.resolve("second.txt"))));
      assertEquals(1, queue.jobStateCounts().processingCount(), "first replacement rolled back too");
      assertTrue(queue.markClaimDone(claim, success(), null));
    }
  }

  @Test
  void aRolledBackOutcomeRetainsItsClaimForRetry() throws Exception {
    Path db = temp.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueue(List.of(temp.resolve("document.txt")));
      var claim = queue.pollPending(1).getFirst();
      execute(db, "CREATE TRIGGER refuse_outcome BEFORE INSERT ON ingestion_ledger "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      assertThrows(OutcomeWriteException.class, () -> queue.markClaimDone(claim, success(), null));
      assertEquals(1, queue.jobStateCounts().processingCount());
      execute(db, "DROP TRIGGER refuse_outcome");
      assertTrue(queue.markClaimDone(claim, success(), null));
    }
  }

  @Test
  void closeAndRecoveryCannotReviveAPreviousLifetimeClaim() throws Exception {
    Path db = temp.resolve("jobs.db");
    JobQueue.IndexJob old;
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueue(List.of(temp.resolve("document.txt")));
      old = queue.pollPending(1).getFirst();
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.recoverStuckJobs();
      var current = queue.pollPending(1).getFirst();
      assertFalse(queue.markClaimDone(old, success(), null));
      assertTrue(queue.markClaimDone(current, success(), null));
    }
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }

  private static void execute(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void assertLedgerEntry(
      Path db,
      String transport,
      String operationKey,
      String unitRevision,
      String contentHash,
      String terminalCoverage) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.prepareStatement("SELECT operation_key, unit_revision, content_hash, "
            + "terminal_coverage, originator, transport FROM ingestion_ledger WHERE transport = ?")) {
      statement.setString(1, transport);
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next(), "expected ledger row for " + transport);
        assertEquals(operationKey, rows.getString("operation_key"));
        assertEquals(unitRevision, rows.getString("unit_revision"));
        assertEquals(contentHash, rows.getString("content_hash"));
        assertEquals(terminalCoverage, rows.getString("terminal_coverage"));
        assertEquals("agent", rows.getString("originator"));
        assertEquals(transport, rows.getString("transport"));
        assertFalse(rows.next(), "ledger attribution must remain unique for " + transport);
      }
    }
  }

  private static long count(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static String string(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }

  private static String normalized(Path path) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizePath(path.toAbsolutePath().toString());
  }
}
