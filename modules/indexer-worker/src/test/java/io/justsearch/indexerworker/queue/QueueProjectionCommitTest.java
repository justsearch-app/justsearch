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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The projection observes committed SQL and completed claim bookkeeping, never native hooks. */
@Timeout(20)
final class QueueProjectionCommitTest {
  @TempDir Path temp;

  @Test
  void independentConnectionSeesCommittedStateBeforeSubscriberRuns() throws Exception {
    Path db = temp.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(db);
        var observer = DriverManager.getConnection("jdbc:sqlite:" + db)) {
      queue.open();
      List<String> observed = new ArrayList<>();
      List<Boolean> callbackInsideCommitHook = new ArrayList<>();
      queue.changeStream().subscribe(delta -> {
        boolean insideCommitHook = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
            frame.getClassName().equals("org.sqlite.core.DB") && frame.getMethodName().equals("onCommit")));
        callbackInsideCommitHook.add(insideCommitHook);
        try (var query = observer.createStatement();
            var rows = query.executeQuery("SELECT state FROM jobs")) {
          while (rows.next()) observed.add(rows.getString(1));
        } catch (java.sql.SQLException e) {
          throw new AssertionError(e);
        }
      });
      queue.enqueue(List.of(temp.resolve("document.txt")));
      queue.markDone(temp.resolve("document.txt"));
      assertEquals(List.of(false, false), callbackInsideCommitHook,
          "subscriber must not run inside SQLite's native commit callback");
      assertEquals(List.of("PENDING", "DONE"), observed,
          "another connection must see each effect before its delta is delivered");
    }
  }

  @Test
  void rollbackDropsProvisionalDeltasButPreservesEarlierCommittedChunks() throws Exception {
    Path db = temp.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      List<Path> paths = new ArrayList<>();
      for (int i = 0; i < 501; i++) paths.add(temp.resolve("document-" + i + ".txt"));
      queue.enqueue(paths);
      List<IndexingJobChangeFeed.Delta> observed = new ArrayList<>();
      queue.changeStream().subscribe(observed::add);
      execute(db, "CREATE TRIGGER refuse_last BEFORE UPDATE ON jobs "
          + "WHEN NEW.path LIKE '%document-500.txt' BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      queue.markDoneBatch(paths);
      assertEquals(499, observed.size(), "first chunk committed; partial second chunk rolled back");
      assertTrue(observed.stream().allMatch(delta -> delta instanceof IndexingJobChangeFeed.Delta.Update u
          && u.row().state().equals("DONE")));
      var snapshot = queue.changeStream().subscribeWithSnapshot(delta -> observed.add(delta));
      assertEquals(499L, snapshot.items().stream().filter(row -> row.state().equals("DONE")).count());
      assertEquals(2L, snapshot.items().stream().filter(row -> row.state().equals("PENDING")).count());
      snapshot.subscription().close();
      execute(db, "DROP TRIGGER refuse_last");
      queue.markDoneBatch(paths.subList(499, 501));
      assertEquals(501, observed.size(), "rolled-back deltas must not leak into the next commit");
    }
  }

  @Test
  void reentrantMutationDeliversOriginalBatchToEverySubscriberFirst() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      AtomicBoolean once = new AtomicBoolean();
      List<String> first = new ArrayList<>();
      List<String> second = new ArrayList<>();
      queue.changeStream().subscribe(delta -> {
        first.add(kind(delta));
        if (once.compareAndSet(false, true)) queue.clearAll();
      });
      queue.changeStream().subscribe(delta -> second.add(kind(delta)));
      queue.enqueue(List.of(temp.resolve("a.txt"), temp.resolve("b.txt")));
      assertEquals(List.of("insert", "insert", "delete", "delete"), first);
      assertEquals(first, second, "subscriber-triggered deletion cannot overtake the insert batch");
    }
  }

  @Test
  void reentrantSnapshotExcludesAlreadyCommittedQueuedDeltas() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      AtomicBoolean once = new AtomicBoolean();
      List<IndexingJobChangeFeed.Delta> observed = new ArrayList<>();
      AtomicReference<IndexingJobChangeFeed.SnapshotAndSubscription> snapshot = new AtomicReference<>();
      queue.changeStream().subscribe(delta -> {
        if (!once.compareAndSet(false, true)) return;
        queue.enqueue(List.of(temp.resolve("b.txt")));
        try {
          snapshot.set(queue.changeStream().subscribeWithSnapshot(observed::add));
        } catch (java.sql.SQLException e) {
          throw new AssertionError(e);
        }
      });
      queue.enqueue(List.of(temp.resolve("a.txt")));
      assertEquals(2, snapshot.get().items().size());
      assertEquals(2, snapshot.get().snapshotSeq());
      assertTrue(observed.isEmpty(), "snapshot already includes both committed writes");
      queue.clearAll();
      assertEquals(2, observed.size());
      assertTrue(observed.stream().allMatch(delta -> delta instanceof IndexingJobChangeFeed.Delta.Delete));
      snapshot.get().subscription().close();
    }
  }

  @Test
  void subscriberClaimSurvivesEnqueueAndCompletionBookkeeping() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      AtomicReference<JobQueue.IndexJob> claimed = new AtomicReference<>();
      queue.changeStream().subscribe(delta -> {
        if (delta instanceof IndexingJobChangeFeed.Delta.Insert) {
          claimed.set(queue.pollPending(1).getFirst());
        }
      });
      queue.enqueue(List.of(temp.resolve("document.txt")));
      var first = claimed.get();
      assertTrue(queue.markClaimDone(first, success(), null),
          "outer enqueue must finish invalidating old claims before subscriber acquires a new one");
      AtomicBoolean once = new AtomicBoolean();
      queue.changeStream().subscribe(delta -> {
        if (delta instanceof IndexingJobChangeFeed.Delta.Update u && u.row().state().equals("DONE")
            && once.compareAndSet(false, true)) {
          queue.enqueue(List.of(temp.resolve("document.txt")));
        }
      });
      queue.enqueue(List.of(temp.resolve("document.txt")));
      assertTrue(queue.markClaimDone(claimed.get(), success(), null));
      assertTrue(queue.markClaimDone(claimed.get(), success(), null),
          "outer finishClaim must not erase a replacement claim created by its subscriber");
      assertFalse(queue.markClaimDone(first, success(), null));
    }
  }

  @Test
  void concurrentSnapshotWaitsForConnectionOwner() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"));
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      queue.open();
      CountDownLatch delivering = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      queue.changeStream().subscribe(delta -> {
        delivering.countDown();
        try {
          assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
      });
      var writer = tasks.submit(() -> queue.enqueue(List.of(temp.resolve("document.txt"))));
      try {
        assertTrue(delivering.await(5, TimeUnit.SECONDS));
        CountDownLatch reading = new CountDownLatch(1);
        var reader = tasks.submit(() -> {
          reading.countDown();
          return queue.changeStream().subscribeWithSnapshot(delta -> {});
        });
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> reader.get(200, TimeUnit.MILLISECONDS));
        release.countDown();
        var snapshot = reader.get(5, TimeUnit.SECONDS);
        assertEquals(1, snapshot.items().size());
        assertEquals(1, snapshot.snapshotSeq());
        snapshot.subscription().close();
        assertEquals(1, writer.get(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
  }

  private static String kind(IndexingJobChangeFeed.Delta delta) {
    return delta instanceof IndexingJobChangeFeed.Delta.Insert ? "insert" : "delete";
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
