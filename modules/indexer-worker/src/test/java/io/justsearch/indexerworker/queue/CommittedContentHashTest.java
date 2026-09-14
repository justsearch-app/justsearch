/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The hash belongs to the exact committed claim, not merely to a path-shaped completion. */
final class CommittedContentHashTest {
  @TempDir Path temp;
  private static final String HASH = "a".repeat(64);
  private static final String OTHER_HASH = "b".repeat(64);

  @Test
  void committedHashAndOutcomeSurviveReopenAndReenqueueInvalidatesThem() throws Exception {
    Path db = temp.resolve("jobs.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueue(List.of(file));
      var claim = queue.pollPending(1).getFirst();
      assertEquals(new Stored("PROCESSING", null), stored(db));
      queue.markDoneTransitions(List.of(transition(claim, HASH)), success());
      assertEquals(new Stored("DONE", HASH), stored(db));
      assertEquals(1, queue.recentIngestionEvents(10).size());
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(new Stored("DONE", HASH), stored(db));
      queue.enqueue(List.of(file));
      assertEquals(new Stored("PENDING", null), stored(db));
      assertEquals(1, queue.recentIngestionEvents(10).size(), "history survives re-enqueue");
      queue.markDoneTransitions(List.of(transition(queue.pollPending(1).getFirst(), OTHER_HASH)), success());
      var retried = queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(file));
      assertEquals(1, retried.accepted());
      assertEquals("DONE", retried.previousState());
      assertEquals(new Stored("PENDING", null), stored(db));
      assertEquals(2, queue.recentIngestionEvents(10).size());
    }
  }

  @Test
  void staleAndEqualValuedClaimsCannotPublishOrReplaceAHash() throws Exception {
    Path db = temp.resolve("jobs.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueue(List.of(file));
      var old = queue.pollPending(1).getFirst();
      queue.enqueue(List.of(file));
      assertTrue(queue.pollPending(1).isEmpty());
      queue.markDoneTransitions(List.of(transition(old, HASH)), success());
      assertEquals(new Stored("PENDING", null), stored(db));
      var current = queue.pollPending(1).getFirst();
      var forged = new JobQueue.IndexJob(
          current.path(), current.collection(), current.provenance(), current.scanId(),
          current.unitRevision());
      assertEquals(current, forged);
      queue.markDoneTransitions(List.of(transition(old, HASH), transition(forged, HASH)), success());
      assertEquals(new Stored("PROCESSING", null), stored(db));
      assertTrue(queue.recentIngestionEvents(10).isEmpty());
      queue.markDoneTransitions(List.of(transition(current, OTHER_HASH)), success());
      queue.markDoneTransitions(List.of(transition(current, HASH)), success());
      assertEquals(new Stored("DONE", OTHER_HASH), stored(db));
      assertEquals(1, queue.recentIngestionEvents(10).size());
    }
  }

  @Test
  void ledgerFailureRollsBackHashAndDoneAndRetainsClaimForRetry() throws Exception {
    Path db = temp.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueue(List.of(temp.resolve("document.txt")));
      var claim = queue.pollPending(1).getFirst();
      execute(db, "CREATE TRIGGER refuse_ledger BEFORE INSERT ON ingestion_ledger "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      assertThrows(OutcomeWriteException.class,
          () -> queue.markDoneTransitions(List.of(transition(claim, HASH)), success()));
      assertEquals(new Stored("PROCESSING", null), stored(db));
      assertTrue(queue.recentIngestionEvents(10).isEmpty());
      execute(db, "DROP TRIGGER refuse_ledger");
      queue.markDoneTransitions(List.of(transition(claim, HASH)), success());
      assertEquals(new Stored("DONE", HASH), stored(db));
      assertEquals(1, queue.recentIngestionEvents(10).size());
    }
  }

  @Test
  void hashFreeAdministrativeCompletionDoesNotRetainAnOldDigest() throws Exception {
    Path db = temp.resolve("jobs.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueue(List.of(file));
      queue.markDoneTransitions(List.of(transition(queue.pollPending(1).getFirst(), HASH)), success());
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(file, null)), success());
      assertEquals(new Stored("DONE", null), stored(db));
      execute(db, "UPDATE jobs SET content_hash = '" + HASH + "'");
      queue.markDone(file, success(), null);
      assertEquals(new Stored("DONE", null), stored(db));
      execute(db, "UPDATE jobs SET content_hash = '" + HASH + "'");
      queue.markDone(file);
      assertEquals(new Stored("DONE", null), stored(db));
      execute(db, "UPDATE jobs SET content_hash = '" + HASH + "'");
      queue.markDoneBatch(List.of(file));
      assertEquals(new Stored("DONE", null), stored(db));
    }
  }

  @Test
  void hashMustBeCanonicalAndAccompaniedByAClaim() {
    Path file = temp.resolve("document.txt");
    var claim = new JobQueue.IndexJob(file, null);
    assertThrows(IllegalArgumentException.class,
        () -> new JobQueue.IngestionLedgerTransition(file, null, null, HASH));
    for (String invalid : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
      assertThrows(IllegalArgumentException.class, () -> transition(claim, invalid));
    }
    assertNull(transition(claim, null).committedContentHash());
  }

  private static JobQueue.IngestionLedgerTransition transition(JobQueue.IndexJob claim, String hash) {
    return new JobQueue.IngestionLedgerTransition(claim, null, hash);
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }

  private record Stored(String state, String hash) {}

  private static Stored stored(Path db) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT state, content_hash FROM jobs")) {
      assertTrue(rows.next());
      Stored result = new Stored(rows.getString(1), rows.getString(2));
      assertFalse(rows.next());
      return result;
    }
  }

  private static void execute(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
