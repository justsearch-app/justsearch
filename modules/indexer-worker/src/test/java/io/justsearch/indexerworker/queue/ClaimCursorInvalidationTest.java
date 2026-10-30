/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClaimCursorInvalidationTest {
  @TempDir Path temp;

  @Test void enqueueBeforeFutureCursorClaimsOldestFirst() throws Exception {
    try (var queue = futureQueue()) {
      Path earlier = temp.resolve("new");
      queue.enqueue(List.of(earlier));
      assertEquals(earlier, queue.pollPending(1).getFirst().path());
    }
  }

  @Test void reenqueueLoweringTimestampClaimsOldestFirst() throws Exception {
    try (var queue = futureQueue()) {
      Path earlier = temp.resolve("b");
      queue.reenqueue(new JobQueue.EnqueueEntry(earlier, 5));
      assertEquals(earlier, queue.pollPending(1).getFirst().path());
    }
  }

  @Test void directPendingTimestampChangeInvalidatesCursor() throws Exception {
    try (var queue = futureQueue(); var stmt = JobCountsTest.connection(queue).createStatement()) {
      stmt.executeUpdate("UPDATE jobs SET last_updated=0 WHERE path='" + temp.resolve("c") + "'");
      assertEquals(temp.resolve("c"), queue.pollPending(1).getFirst().path());
    }
  }

  @Test void tiedTimestampPathChangeBeforeCursorClaimsOldestFirst() throws Exception {
    try (var queue = futureQueue(); var stmt = JobCountsTest.connection(queue).createStatement()) {
      stmt.executeUpdate("UPDATE jobs SET path='" + temp.resolve("00") + "' WHERE path='" + temp.resolve("c") + "'");
      assertEquals(temp.resolve("00"), queue.pollPending(1).getFirst().path());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"completion", "batch", "return"})
  void releasingSupersededOwnerExposesOlderReplacement(String release) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("owner.db"))) {
      queue.open();
      Path first = temp.resolve("a");
      queue.enqueue(List.of(first, temp.resolve("b"), temp.resolve("c")));
      try (var stmt = JobCountsTest.connection(queue).createStatement()) {
        stmt.executeUpdate("UPDATE jobs SET last_updated=" + (System.currentTimeMillis() + 600_000));
      }
      var old = queue.pollPending(1).getFirst();
      queue.reenqueue(new JobQueue.EnqueueEntry(first, 5));
      assertEquals(temp.resolve("b"), queue.pollPending(1).getFirst().path(), "active owner fences its replacement");
      var outcome = io.justsearch.indexerworker.ingest.IngestionOutcome.of(
          io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
          io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE);
      switch (release) {
        case "completion" -> assertFalse(queue.markClaimDone(old, outcome, null));
        case "batch" -> queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(old, null)), outcome);
        case "return" -> queue.returnUnfinishedClaims(List.of(old));
        default -> throw new AssertionError(release);
      }
      assertEquals(first, queue.pollPending(1).getFirst().path());
    }
  }

  @Test void newlyReadyRowBeforeCursorIsNotSkipped() throws Exception {
    try (var queue = futureQueue(); var stmt = JobCountsTest.connection(queue).createStatement()) {
      stmt.executeUpdate("INSERT INTO jobs(path,state,last_updated,retry_after) VALUES ('" + temp.resolve("retry") + "','PENDING',0,4000000000000)");
      // Advance beyond this backoff row, then remove its deadline without advancing the clock.
      assertEquals(temp.resolve("b"), queue.pollPending(1).getFirst().path());
      stmt.executeUpdate("UPDATE jobs SET retry_after=NULL WHERE path='" + temp.resolve("retry") + "'");
      assertEquals(temp.resolve("retry"), queue.pollPending(1).getFirst().path());
    }
  }

  @Test void authorityOnlyGrantRevisitsOlderMemberDespiteContinuousNewArrivals() throws Exception {
    var permitted = new java.util.concurrent.atomic.AtomicBoolean();
    try (var queue = new SqliteJobQueue(temp.resolve("grant.db"), key -> permitted.get()
        ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      var walk = queue.beginRecordedWalk("older", "a".repeat(64), true);
      Path oldest = temp.resolve("oldest");
      queue.enqueueRecordedEntries("older", walk.enumerationEpoch(), List.of(new JobQueue.EnqueueEntry(oldest, 1)), null);
      queue.enqueue(List.of(temp.resolve("new-0"), temp.resolve("new-1")));
      try (var stmt = JobCountsTest.connection(queue).createStatement()) {
        stmt.executeUpdate("UPDATE jobs SET last_updated=1 WHERE scan_id='older'");
        stmt.executeUpdate("UPDATE jobs SET last_updated=2 WHERE scan_id IS NULL");
      }
      assertEquals(temp.resolve("new-0"), queue.pollPending(1).getFirst().path());
      permitted.set(true);
      // Keep the regression executable on the baseline, which has no authority notification.
      try { queue.getClass().getMethod("recordedClaimAuthorityChanged", String.class).invoke(queue, "older"); }
      catch (NoSuchMethodException expected) { /* The baseline can only reconsider after a cursor wrap. */ }
      queue.enqueue(List.of(temp.resolve("new-2")));
      assertEquals(oldest, queue.pollPending(1).getFirst().path(), "a grant is reconsidered on the next poll");
    }
  }

  private SqliteJobQueue futureQueue() throws Exception {
    var queue = new SqliteJobQueue(temp.resolve("jobs.db"));
    queue.open();
    queue.enqueue(List.of(temp.resolve("a"), temp.resolve("b"), temp.resolve("c")));
    try (var stmt = JobCountsTest.connection(queue).createStatement()) {
      stmt.executeUpdate("UPDATE jobs SET last_updated=" + (System.currentTimeMillis() + 600_000));
    }
    assertEquals(temp.resolve("a"), queue.pollPending(1).getFirst().path());
    return queue;
  }
}
