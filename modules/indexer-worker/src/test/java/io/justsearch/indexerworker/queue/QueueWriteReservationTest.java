/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The preservation snapshot must follow acquisition of the SQLite write reservation. */
final class QueueWriteReservationTest {
  @TempDir Path temp;

  @Test
  void writeReservationDoesNotPublishPhantomJobChanges() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("projection.db"))) {
      queue.open();
      queue.enqueue(List.of(temp.resolve("pending.txt")));
      var feed = queue.changeStream();
      long before = feed.currentSeq();
      var changes = new java.util.ArrayList<IndexingJobChangeFeed.Delta>();
      try (var ignored = feed.subscribe(changes::add)) {
        String key = "01994180-0000-7000-8000-000000000201";
        var progress = queue.beginRecordedWalk(key, "a".repeat(64), true);
        queue.closeRecordedWalkEnumeration(key, progress.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
        assertEquals(before, feed.currentSeq());
        assertTrue(changes.isEmpty());
        assertEquals(1, queue.queueDepth());
      }
    }
  }

  @Test
  void enqueueWaitsForPriorWriterBeforeReadingPreservedAttribution() throws Exception {
    Path db = temp.resolve("jobs.db");
    Path file = temp.resolve("document.txt");
    try (var queue = new SqliteJobQueue(db); var executor = Executors.newSingleThreadExecutor()) {
      queue.open();
      queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(file, 1,
          new JobQueue.EnqueueProvenance("user", "old"))), "old", "old-scan");
      var field = SqliteJobQueue.class.getDeclaredField("connection");
      field.setAccessible(true);
      Connection original = (Connection) field.get(queue);
      Connection intercepted = org.mockito.Mockito.mock(Connection.class,
          org.mockito.AdditionalAnswers.delegatesTo(original));
      CountDownLatch entered = new CountDownLatch(1);
      org.mockito.Mockito.doAnswer(call -> {
        original.setAutoCommit(false);
        entered.countDown();
        return null;
      }).when(intercepted).setAutoCommit(false);
      field.set(queue, intercepted);
      try (var writer = DriverManager.getConnection("jdbc:sqlite:" + db)) {
        writer.setAutoCommit(false);
        try (var update = writer.createStatement()) {
          update.executeUpdate("UPDATE jobs SET collection='new', scan_id='new-scan', "
              + "originator='system', transport='prior-writer'");
        }
        var enqueue = executor.submit(() -> queue.enqueueEntries(
            List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null));
        try {
          assertTrue(entered.await(10, TimeUnit.SECONDS));
          assertThrows(TimeoutException.class, () -> enqueue.get(200, TimeUnit.MILLISECONDS),
              "the queue must wait for the existing writer instead of failing a read-to-write upgrade");
        } finally {
          writer.commit();
        }
        assertEquals(1, enqueue.get(10, TimeUnit.SECONDS));
        var claimed = queue.pollPending(1).getFirst();
        assertEquals("new-scan", claimed.scanId());
        assertEquals("new", claimed.collection());
        assertEquals("prior-writer", claimed.provenance().transport());
        queue.returnUnfinishedClaims(List.of(claimed));
      } finally {
        field.set(queue, original);
      }
    }
  }
}
