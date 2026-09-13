/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  @TempDir Path temp;

  @Test
  void anOlderCommittedUnitCannotCompleteAReplacementClaim() throws Exception {
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, "scan-A");
      var firstClaim = queue.pollPending(1).getFirst();
      // A later batch can be claimed before the previous batch's Lucene commit drains its
      // pending ledger transitions. Both claims name the same path but represent different work.
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, "scan-B");
      var secondClaim = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(
          List.of(new JobQueue.IngestionLedgerTransition(firstClaim, null)),
          IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
              IngestionRetryPolicy.NONE));
      assertEquals(1, queue.queueDepth(),
          "the replacement claim must remain open until its own effect commits");
      assertTrue(queue.markClaimDone(secondClaim, success(), null));
      assertEquals(0, queue.queueDepth());
    }
  }

  @Test
  void equalValuedOrAlreadyFinishedClaimsHaveNoAuthority() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      queue.enqueue(List.of(temp.resolve("document.txt")));
      var claim = queue.pollPending(1).getFirst();
      var forged = new JobQueue.IndexJob(claim.path(), claim.collection(), claim.provenance());
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
}
