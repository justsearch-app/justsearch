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
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite coverage for recorded-walk terminal receipts and monotonic counters. */
final class RecordedWalkTerminalTest {
  private static final String KEY = "01994180-0000-7000-8000-000000000101";
  private static final String PLAN = "a".repeat(64);
  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);

  @TempDir Path temp;

  @Test
  void indexedTerminalReceiptCarriesIdentityAndAdvancesOnce() throws Exception {
    Path db = temp.resolve("indexed.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      var transition = new JobQueue.IngestionLedgerTransition(claim, null, HASH_A);

      queue.markDoneTransitions(List.of(transition), success());
      queue.markDoneTransitions(List.of(transition), success());

      var progress = queue.recordedWalk(KEY).orElseThrow();
      assertEquals(1, progress.completedUnits());
      assertEquals(0, progress.failedUnits());
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger"));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var statement = connection.prepareStatement(
              "SELECT operation_key, unit_revision, content_hash, terminal_coverage "
                  + "FROM ingestion_ledger")) {
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(KEY, rows.getString("operation_key"));
          assertEquals(claim.unitRevision(), rows.getString("unit_revision"));
          assertEquals(HASH_A, rows.getString("content_hash"));
          assertEquals("INDEXED", rows.getString("terminal_coverage"));
          assertFalse(rows.next());
        }
      }
    }
  }

  @Test
  void maintenanceAtoBtoAKeepsActiveWalkAndCountsDistinctEffects() throws Exception {
    Path db = temp.resolve("maintenance.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      long epoch = walk.enumerationEpoch();
      queue.enqueueRecordedEntries(KEY, epoch,
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);

      var first = queue.pollPending(1).getFirst();
      assertEquals(KEY, first.scanId());
      assertEquals(epoch, first.walkEpoch());
      queue.markDoneTransitions(List.of(transition(first, HASH_A)), success());

      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var second = queue.pollPending(1).getFirst();
      assertEquals(KEY, second.scanId());
      assertEquals(epoch, second.walkEpoch());
      queue.markDoneTransitions(List.of(transition(second, HASH_B)), success());

      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var third = queue.pollPending(1).getFirst();
      assertEquals(KEY, third.scanId());
      assertEquals(epoch, third.walkEpoch());
      queue.markDoneTransitions(List.of(transition(third, HASH_A)), success());

      var progress = queue.recordedWalk(KEY).orElseThrow();
      assertEquals(2, progress.completedUnits(), "A→B→A has two distinct path/hash effects");
      assertEquals(3, count(db, "SELECT count(*) FROM ingestion_ledger "
          + "WHERE terminal_coverage = 'INDEXED'"));
    }
  }

  @Test
  void transientFailureDoesNotAdvanceThenTerminalFailureCountsOncePerRevision() throws Exception {
    Path db = temp.resolve("failures.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var first = queue.pollPending(1).getFirst();

      assertTrue(queue.markClaimFailed(first, transientFailure(), null));
      assertEquals(0, queue.recordedWalk(KEY).orElseThrow().completedUnits());
      assertEquals(0, queue.recordedWalk(KEY).orElseThrow().failedUnits());
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_ledger "
          + "WHERE terminal_coverage IS NOT NULL"));

      clearRetryAfter(db, first.path());
      var retry = queue.pollPending(1).getFirst();
      assertEquals(first.unitRevision(), retry.unitRevision());
      assertTrue(queue.markClaimFailed(retry, terminalFailure(), null));
      assertFalse(queue.markClaimFailed(retry, terminalFailure(), null));

      var failed = queue.recordedWalk(KEY).orElseThrow();
      assertEquals(0, failed.completedUnits());
      assertEquals(1, failed.failedUnits());
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger "
          + "WHERE terminal_coverage = 'FAILED'"));

      assertEquals(1, queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(file)).accepted());
      var deliberateRetry = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(transition(deliberateRetry, HASH_A)), success());
      var repaired = queue.recordedWalk(KEY).orElseThrow();
      assertEquals(1, repaired.completedUnits());
      assertEquals(1, repaired.failedUnits(), "historical failure remains monotonic");
    }
  }

  @Test
  void skippedPolicyIsTerminalWithoutIndexedCount() throws Exception {
    Path db = temp.resolve("skipped.db");
    Path file = temp.resolve("policy.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();

      assertTrue(queue.markClaimDone(claim, skipped(), null));
      var progress = queue.recordedWalk(KEY).orElseThrow();
      assertEquals(0, progress.completedUnits());
      assertEquals(0, progress.failedUnits());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var statement = connection.createStatement();
          var rows = statement.executeQuery(
              "SELECT operation_key, unit_revision, content_hash, terminal_coverage "
                  + "FROM ingestion_ledger")) {
        assertTrue(rows.next());
        assertEquals(KEY, rows.getString("operation_key"));
        assertEquals(claim.unitRevision(), rows.getString("unit_revision"));
        assertNull(rows.getString("content_hash"));
        assertEquals("SKIPPED", rows.getString("terminal_coverage"));
        assertFalse(rows.next());
      }
    }
  }

  @Test
  void ledgerTriggerRollsBackJobsAndProgressAndRetainsIssuedClaim() throws Exception {
    Path db = temp.resolve("rollback.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      execute(db, "CREATE TRIGGER refuse_recorded_ledger BEFORE INSERT ON ingestion_ledger "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");

      assertThrows(OutcomeWriteException.class,
          () -> queue.markDoneTransitions(List.of(transition(claim, HASH_A)), success()));
      assertEquals("PROCESSING", string(db, "SELECT state FROM jobs"));
      assertNull(string(db, "SELECT content_hash FROM jobs"));
      var rolledBack = queue.recordedWalk(KEY).orElseThrow();
      assertEquals(0, rolledBack.completedUnits());
      assertEquals(0, rolledBack.failedUnits());
      assertNull(rolledBack.sealedAt());
      assertNull(rolledBack.receiptJson());
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_ledger"));

      execute(db, "DROP TRIGGER refuse_recorded_ledger");
      queue.markDoneTransitions(List.of(transition(claim, HASH_A)), success());
      assertEquals(1, queue.recordedWalk(KEY).orElseThrow().completedUnits());
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger"));
    }
  }

  @Test
  void diagnosticSuccessForMatchingPathCannotAdvanceWalkCoverage() throws Exception {
    Path db = temp.resolve("diagnostic.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);

      queue.recordIngestionEvent(file, success(), null);

      var progress = queue.recordedWalk(KEY).orElseThrow();
      assertEquals(0, progress.completedUnits());
      assertEquals(0, progress.failedUnits());
      assertEquals("PENDING", string(db, "SELECT state FROM jobs"));
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger"));
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_ledger "
          + "WHERE terminal_coverage IS NOT NULL"));
      assertNull(string(db, "SELECT operation_key FROM ingestion_ledger"));
      assertNull(string(db, "SELECT unit_revision FROM ingestion_ledger"));
      assertNull(string(db, "SELECT content_hash FROM ingestion_ledger"));
    }
  }

  @Test
  void staleCommittedClaimKeepsItsHistoryWithoutCompletingReplacement() throws Exception {
    Path db = temp.resolve("stale.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(new JobQueue.EnqueueEntry(file, 1, new JobQueue.EnqueueProvenance("agent", "original"))), "old");
      var claim = queue.pollPending(1).getFirst();
      queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(file, 2,
          new JobQueue.EnqueueProvenance("system", "replacement"))), "new", "maintenance");
      assertTrue(queue.pollPending(1).isEmpty());
      queue.markDoneTransitions(List.of(transition(claim, HASH_A)), success());
      assertEquals("PENDING", string(db, "SELECT state FROM jobs"));
      assertEquals(KEY, string(db, "SELECT scan_id FROM jobs"));
      assertEquals("original", string(db, "SELECT transport FROM ingestion_ledger"));
      assertEquals("old", string(db, "SELECT collection FROM ingestion_ledger"));
      assertEquals(1, queue.recordedWalk(KEY).orElseThrow().completedUnits());
      var replacement = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(transition(replacement, HASH_B)), success());
      assertEquals(2, queue.recordedWalk(KEY).orElseThrow().completedUnits());
    }
  }

  @Test
  void missingProjectionCannotDowngradeIssuedCompletionToLegacy() throws Exception {
    Path db = temp.resolve("missing.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      execute(db, "DELETE FROM ingestion_walk_progress");
      assertThrows(OutcomeWriteException.class,
          () -> queue.markDoneTransitions(List.of(transition(claim, HASH_A)), success()));
      assertEquals("PROCESSING", string(db, "SELECT state FROM jobs"));
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_ledger"));
    }
  }

  @Test
  void claimlessTerminalFailureCannotMutateRecordedMember() throws Exception {
    Path db = temp.resolve("claimless.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      assertThrows(OutcomeWriteException.class, () -> queue.markFailed(file, terminalFailure(), null));
      assertThrows(IllegalStateException.class, () -> queue.markFailed(file, "failure"));
      assertEquals("PENDING", string(db, "SELECT state FROM jobs"));
      assertEquals(0, queue.recordedWalk(KEY).orElseThrow().failedUnits());
    }
  }

  @Test
  void outcomeCoverageMismatchRollsBackAndRetainsClaim() throws Exception {
    Path db = temp.resolve("mismatch.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      assertThrows(OutcomeWriteException.class,
          () -> queue.markDoneTransitions(List.of(transition(claim, HASH_A)), terminalFailure()));
      assertThrows(OutcomeWriteException.class, () -> queue.markClaimDone(claim, terminalFailure(), null));
      assertThrows(OutcomeWriteException.class, () -> queue.markClaimFailed(claim, success(), null));
      assertThrows(IllegalStateException.class, () -> queue.markDone(file));
      assertThrows(IllegalStateException.class, () -> queue.markDoneBatch(List.of(file)));
      assertEquals("PROCESSING", string(db, "SELECT state FROM jobs"));
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_ledger"));
      queue.markDoneTransitions(List.of(transition(claim, HASH_A)), success());
      assertEquals(1, queue.recordedWalk(KEY).orElseThrow().completedUnits());
    }
  }

  @Test
  void legacyMatchingScanKeyBecomesFreshRecordedAdmission() throws Exception {
    Path db = temp.resolve("legacy-key.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, KEY);
      var legacy = queue.pollPending(1).getFirst();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      assertFalse(legacy.unitRevision().equals(string(db, "SELECT unit_revision FROM jobs")));
      queue.markDoneTransitions(List.of(transition(legacy, HASH_A)), success());
      assertEquals("PENDING", string(db, "SELECT state FROM jobs"));
      // A real legacy effect remains history, but matching a later scan key cannot manufacture
      // recorded membership or complete the fresh recorded revision.
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger"));
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_ledger WHERE operation_key IS NOT NULL"));
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_ledger WHERE terminal_coverage IS NOT NULL"));
      assertEquals(0, queue.recordedWalk(KEY).orElseThrow().completedUnits());
      var recorded = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(transition(recorded, HASH_A)), success());
      assertEquals(1, queue.recordedWalk(KEY).orElseThrow().completedUnits());
    }
  }

  @Test
  void orphanRecordedMembershipCannotBeStolenByAnotherWalk() throws Exception {
    Path db = temp.resolve("orphan.db");
    Path file = temp.resolve("document.txt");
    String other = "01994180-0000-7000-8000-000000000102";
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      execute(db, "DELETE FROM ingestion_walk_progress");
      var newWalk = queue.beginRecordedWalk(other, PLAN, true);
      assertThrows(IllegalStateException.class, () -> queue.enqueueRecordedEntries(other,
          newWalk.enumerationEpoch(), List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null));
      assertEquals(KEY, string(db, "SELECT scan_id FROM jobs"));
    }
  }

  @Test
  void supersededNonindexedOutcomesKeepHistoryWithoutConsumingReplacementRetry() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      Path db = temp.resolve("superseded-" + kind + ".db");
      Path file = temp.resolve("document.txt");
      try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
        queue.open();
        var walk = queue.beginRecordedWalk(KEY, PLAN, true);
        queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
            List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
        var claim = queue.pollPending(1).getFirst();
        queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(file));
        if (kind == 0) assertFalse(queue.markClaimDone(claim, skipped(), null));
        else assertFalse(queue.markClaimFailed(claim, kind == 1 ? terminalFailure() : transientFailure(), null));
        assertEquals("PENDING", string(db, "SELECT state FROM jobs"));
        assertEquals(0, count(db, "SELECT attempts FROM jobs"));
        assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger"));
        assertEquals(claim.unitRevision(), string(db, "SELECT unit_revision FROM ingestion_ledger"));
        assertEquals(kind == 1 ? 1 : 0, queue.recordedWalk(KEY).orElseThrow().failedUnits());
        assertEquals(kind == 2 ? null : kind == 1 ? "FAILED" : "SKIPPED",
            string(db, "SELECT terminal_coverage FROM ingestion_ledger"));
        assertEquals(1, queue.pollPending(1).size());
      }
    }
  }

  @Test
  void supersededReceiptRollbackRetainsActualOwnerUntilIdempotentRetry() throws Exception {
    Path db = temp.resolve("superseded-rollback.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(file));
      execute(db, "CREATE TRIGGER refuse_receipt BEFORE INSERT ON ingestion_ledger "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      assertThrows(OutcomeWriteException.class, () -> queue.markClaimFailed(claim, terminalFailure(), null));
      assertTrue(queue.pollPending(1).isEmpty(), "replacement must wait for actual old owner exit");
      assertEquals(0, queue.recordedWalk(KEY).orElseThrow().failedUnits());
      execute(db, "DROP TRIGGER refuse_receipt");
      assertFalse(queue.markClaimFailed(claim, terminalFailure(), null));
      assertFalse(queue.markClaimFailed(claim, terminalFailure(), null));
      assertEquals(1, queue.recordedWalk(KEY).orElseThrow().failedUnits());
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger"));
      assertEquals(1, queue.pollPending(1).size());
    }
  }

  private static JobQueue.IngestionLedgerTransition transition(JobQueue.IndexJob claim, String hash) {
    return new JobQueue.IngestionLedgerTransition(claim, null, hash);
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }

  private static IngestionOutcome skipped() {
    return IngestionOutcome.of(IngestionOutcomeClass.SKIPPED_POLICY, "SKIPPED_POLICY",
        IngestionRetryPolicy.NONE);
  }

  private static IngestionOutcome transientFailure() {
    return IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED, "IO_FAILED",
        IngestionRetryPolicy.RETRY_WITH_BACKOFF);
  }

  private static IngestionOutcome terminalFailure() {
    return IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "PARSER_FAILED",
        IngestionRetryPolicy.NONE);
  }

  private static void clearRetryAfter(Path db, Path file) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.prepareStatement("UPDATE jobs SET retry_after = NULL WHERE path = ?")) {
      statement.setString(1, io.justsearch.indexerworker.util.PathNormalizer.normalizePath(
          file.toAbsolutePath().toString()));
      statement.executeUpdate();
    }
  }

  private static void execute(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      statement.execute(sql);
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
}
