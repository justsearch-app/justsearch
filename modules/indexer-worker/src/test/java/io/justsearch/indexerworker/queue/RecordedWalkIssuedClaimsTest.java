/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite coverage for process-local recorded claim ownership. */
final class RecordedWalkIssuedClaimsTest {
  private static final String KEY = "01994180-0000-7000-8000-000000000121";
  private static final String OTHER_KEY = "01994180-0000-7000-8000-000000000122";
  private static final String LEGACY_KEY = "01994180-0000-7000-8000-000000000123";
  private static final String PLAN = "a".repeat(64);

  @TempDir Path temp;

  @Test
  void pendingRowsAreNotOwnersAndOnlyRecordedEpochClaimsCount() throws Exception {
    Path db = temp.resolve("pending.db");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var firstWalk = queue.beginRecordedWalk(KEY, PLAN, true);
      var secondWalk = queue.beginRecordedWalk(OTHER_KEY, PLAN, true);
      Path firstPath = temp.resolve("a-recorded.txt");
      Path secondPath = temp.resolve("b-recorded.txt");
      Path legacyPath = temp.resolve("c-legacy.txt");
      queue.enqueueRecordedEntries(KEY, firstWalk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(firstPath)), null);
      queue.enqueueRecordedEntries(OTHER_KEY, secondWalk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(secondPath)), null);
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(legacyPath)), null, LEGACY_KEY);

      assertFalse(queue.hasIssuedRecordedClaims(KEY));
      assertFalse(queue.hasIssuedRecordedClaims(OTHER_KEY));
      assertFalse(queue.hasIssuedRecordedClaims(LEGACY_KEY));

      List<JobQueue.IndexJob> claims = queue.pollPending(3);
      assertEquals(3, claims.size());
      assertTrue(claims.stream().anyMatch(claim -> KEY.equals(claim.scanId()) && claim.walkEpoch() != null));
      assertTrue(claims.stream().anyMatch(claim -> OTHER_KEY.equals(claim.scanId()) && claim.walkEpoch() != null));
      assertTrue(claims.stream().anyMatch(claim -> LEGACY_KEY.equals(claim.scanId()) && claim.walkEpoch() == null));
      assertTrue(queue.hasIssuedRecordedClaims(KEY));
      assertTrue(queue.hasIssuedRecordedClaims(OTHER_KEY));
      assertFalse(queue.hasIssuedRecordedClaims(LEGACY_KEY));
      assertFalse(queue.hasIssuedRecordedClaims("01994180-0000-7000-8000-000000000124"));

      queue.returnUnfinishedClaims(claims);
      assertFalse(queue.hasIssuedRecordedClaims(KEY));
      assertFalse(queue.hasIssuedRecordedClaims(OTHER_KEY));
    }
  }

  @Test
  void revocationRaceRetainsTheClaimCapturedBeforeRevocation() throws Exception {
    Path db = temp.resolve("race.db");
    AtomicBoolean allowed = new AtomicBoolean(true);
    CountDownLatch predicateEntered = new CountDownLatch(1);
    CountDownLatch releasePredicate = new CountDownLatch(1);
    CountDownLatch ownershipReadEntered = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try (var queue = new SqliteJobQueue(db, ignored -> {
      boolean captured = allowed.get();
      predicateEntered.countDown();
      try {
        if (!releasePredicate.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("recorded predicate was not released");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("recorded predicate interrupted", interrupted);
      }
      return captured;
    })) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      Path path = temp.resolve("race.txt");
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(path)), null);

      Future<List<JobQueue.IndexJob>> poll = workers.submit(() -> queue.pollPending(1));
      assertTrue(predicateEntered.await(5, TimeUnit.SECONDS));
      allowed.set(false);
      Future<Boolean> ownerRead = workers.submit(() -> {
        ownershipReadEntered.countDown();
        return queue.hasIssuedRecordedClaims(KEY);
      });
      assertTrue(ownershipReadEntered.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> ownerRead.get(100, TimeUnit.MILLISECONDS),
          "ownership read must wait for the in-flight claim transaction");
      releasePredicate.countDown();

      List<JobQueue.IndexJob> claims = poll.get(5, TimeUnit.SECONDS);
      assertEquals(1, claims.size());
      assertTrue(ownerRead.get(5, TimeUnit.SECONDS), "claim captured before revocation remains owned");
      assertTrue(queue.hasIssuedRecordedClaims(KEY));
      queue.returnUnfinishedClaims(claims);
      assertFalse(queue.hasIssuedRecordedClaims(KEY));
      assertTrue(queue.pollPending(1).isEmpty(), "revocation blocks claiming the returned pending row");
      assertFalse(queue.hasIssuedRecordedClaims(KEY));
    } finally {
      releasePredicate.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void commitAndReturnReleaseOnlyTheExactIssuedOwner() throws Exception {
    Path db = temp.resolve("release.db");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      Path firstPath = temp.resolve("first.txt");
      Path secondPath = temp.resolve("second.txt");
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(firstPath),
              JobQueue.EnqueueEntry.ofUnknownSize(secondPath)), null);
      List<JobQueue.IndexJob> claims = queue.pollPending(2);
      assertEquals(2, claims.size());

      JobQueue.IndexJob first = claims.get(0);
      JobQueue.IndexJob second = claims.get(1);
      assertTrue(queue.markClaimDone(first, skipped(), null));
      assertTrue(queue.hasIssuedRecordedClaims(KEY), "the other exact owner remains");

      JobQueue.IndexJob forged = new JobQueue.IndexJob(second.path(), second.collection(),
          second.provenance(), second.scanId(), second.unitRevision(), second.walkEpoch());
      assertFalse(queue.markClaimDone(forged, skipped(), null));
      assertTrue(queue.hasIssuedRecordedClaims(KEY), "an equal-valued but unissued object cannot release ownership");

      queue.returnUnfinishedClaims(List.of(second));
      assertFalse(queue.hasIssuedRecordedClaims(KEY));
    }
  }

  @Test
  void failedTerminalTransactionRetainsTheIssuedOwnerUntilCommitSucceeds() throws Exception {
    Path db = temp.resolve("failed-rollback.db");
    Path path = temp.resolve("failed.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(path)), null);
      JobQueue.IndexJob claim = queue.pollPending(1).getFirst();
      execute(db, "CREATE TRIGGER refuse_issued_claim_failure BEFORE INSERT ON ingestion_ledger "
          + "BEGIN SELECT RAISE(ABORT, 'fixture rollback'); END");
      try {
        assertThrows(OutcomeWriteException.class,
            () -> queue.markClaimFailed(claim, terminalFailure(), null));
        assertTrue(queue.hasIssuedRecordedClaims(KEY));
        assertEquals("PROCESSING", string(db, "SELECT state FROM jobs"));
      } finally {
        execute(db, "DROP TRIGGER IF EXISTS refuse_issued_claim_failure");
      }
      assertTrue(queue.markClaimFailed(claim, terminalFailure(), null));
      assertFalse(queue.hasIssuedRecordedClaims(KEY));
    }
  }

  @Test
  void reopenedProcessingRowsHaveNoProcessLocalIssuedOwner() throws Exception {
    Path db = temp.resolve("reopen.db");
    Path path = temp.resolve("reopen.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(path)), null);
      queue.pollPending(1);
      assertTrue(queue.hasIssuedRecordedClaims(KEY));
    }
    try (var reopened = new SqliteJobQueue(db, ignored -> true)) {
      reopened.open();
      assertFalse(reopened.hasIssuedRecordedClaims(KEY));
      assertEquals("PROCESSING", string(db, "SELECT state FROM jobs"));
    }
  }

  @Test
  void missingProgressDoesNotEraseAStillIssuedOwner() throws Exception {
    Path db = temp.resolve("missing-progress.db");
    Path path = temp.resolve("missing-progress.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(path)), null);
      queue.pollPending(1);
      execute(db, "DELETE FROM ingestion_walk_progress");

      assertTrue(queue.hasIssuedRecordedClaims(KEY));
    }
  }

  @Test
  void closedQueueRejectsTheOwnershipRead() throws Exception {
    Path db = temp.resolve("closed.db");
    var queue = new SqliteJobQueue(db, ignored -> true);
    queue.open();
    queue.close();

    assertThrows(IllegalStateException.class, () -> queue.hasIssuedRecordedClaims(KEY));
  }

  private static IngestionOutcome skipped() {
    return IngestionOutcome.of(IngestionOutcomeClass.SKIPPED_POLICY, "SKIPPED_POLICY", IngestionRetryPolicy.NONE);
  }

  private static IngestionOutcome terminalFailure() {
    return IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "PARSER_FAILED", IngestionRetryPolicy.NONE);
  }

  private static void execute(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      statement.execute(sql);
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
