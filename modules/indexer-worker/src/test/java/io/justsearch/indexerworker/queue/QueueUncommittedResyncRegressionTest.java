/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QueueUncommittedResyncRegressionTest {
  @TempDir Path temp;

  @Test
  void pendingResyncCannotPublishUncommittedStateAfterLedgerAndRollbackFailures() throws Exception {
    Path path = temp.resolve("file");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      queue.enqueue(List.of(path));
      var feed = queue.changeStream();
      var changes = new ArrayList<IndexingJobChangeFeed.Delta>();
      var subscriberRows = new HashMap<String, IndexingJobChangeFeed.JobRow>();
      var snapshot = feed.subscribeWithSnapshot(delta -> {
        changes.add(delta);
        apply(subscriberRows, delta);
      });
      snapshot.items().forEach(row -> subscriberRows.put(row.pathHash(), row));
      feed.subscribe(changes::add);

      Connection actual = JobCountsTest.connection(queue);
      Connection observed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      var failProjection = new AtomicBoolean(true);
      var projectionFailures = new AtomicInteger();
      doAnswer(call -> {
        String sql = call.getArgument(0);
        if (failProjection.get() && sql.equals(IndexingJobsChangeStream.STATE_EXISTS_SQL)) {
          projectionFailures.incrementAndGet();
          throw new SQLException("presence read unavailable");
        }
        if (sql.contains("INSERT INTO ingestion_ledger")) throw new SQLException("ledger insertion unavailable");
        return actual.prepareStatement(sql);
      }).when(observed).prepareStatement(anyString());
      doAnswer(call -> {
        Statement statement = actual.createStatement();
        Statement wrapped = mock(Statement.class, org.mockito.AdditionalAnswers.delegatesTo(statement));
        doAnswer(read -> {
          String sql = read.getArgument(0);
          if (failProjection.get() && sql.contains("FROM jobs WHERE state = 'PROCESSING'")) {
            projectionFailures.incrementAndGet();
            throw new SQLException("window read unavailable");
          }
          return statement.executeQuery(sql);
        }).when(wrapped).executeQuery(anyString());
        return wrapped;
      }).when(observed).createStatement();
      var rollbackFailure = new SQLException("rollback unavailable");
      doThrow(rollbackFailure).when(observed).rollback();
      QueueDefectRegressionTest.connection(queue, observed);
      var connectionField = IndexingJobsChangeStream.class.getDeclaredField("conn");
      connectionField.setAccessible(true);
      connectionField.set(feed, observed);
      var lockField = SqliteJobQueue.class.getDeclaredField("lock");
      lockField.setAccessible(true);
      var lock = (ReentrantLock) lockField.get(queue);
      lock.lock();
      try {
        assertEquals(1, queue.pollPending(1).size());
      } finally {
        lock.unlock();
      }
      assertTrue(changes.isEmpty(), "confirmed PROCESSING delta waits for the outer drain");
      assertEquals(2, projectionFailures.get(), "presence and resync reads both failed");
      failProjection.set(false);
      clearInvocations(observed);

      var outcome = IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "TEST", IngestionRetryPolicy.NONE);
      var failure = assertThrows(OutcomeWriteException.class, () -> queue.markFailed(path, outcome));
      assertSame(rollbackFailure, failure.getCause().getSuppressed()[0]);
      verify(observed).rollback();
      verify(observed).close();
      verify(observed, never()).setAutoCommit(true);
      assertFalse(changes.stream().anyMatch(delta -> Set.of("FAILED").equals(delta.presentStates())
          || rows(delta).stream().anyMatch(row -> row.state().equals("FAILED"))),
          "no subscriber may receive the uncommitted FAILED state: " + changes);
      assertEquals(2, changes.size(), "both subscribers still receive the confirmed delta");
      assertTrue(changes.stream().allMatch(delta -> delta instanceof IndexingJobChangeFeed.Delta.Update update
          && update.row().state().equals("PROCESSING")));
      var resyncField = IndexingJobsChangeStream.class.getDeclaredField("resyncRequired");
      resyncField.setAccessible(true);
      assertTrue(resyncField.getBoolean(feed), "unsafe cleanup must not consume the resync obligation");

      queue.close();
      queue.open();
      var reopened = queue.changeStream().subscribeWithSnapshot(delta -> apply(subscriberRows, delta));
      subscriberRows.clear();
      reopened.items().forEach(row -> subscriberRows.put(row.pathHash(), row));
      assertEquals(List.of("PROCESSING"), subscriberRows.values().stream().map(IndexingJobChangeFeed.JobRow::state).toList());
      assertEquals(Set.of("PROCESSING"), reopened.presentStates());
      assertEquals("PROCESSING", queue.watcherReconciliationRow(path.toString()).get("state"));
      queue.markDone(path);
      assertEquals(List.of("DONE"), subscriberRows.values().stream().map(IndexingJobChangeFeed.JobRow::state).toList());
      reopened.subscription().close();
      snapshot.subscription().close();
    }
  }

  private static List<IndexingJobChangeFeed.JobRow> rows(IndexingJobChangeFeed.Delta delta) {
    return switch (delta) {
      case IndexingJobChangeFeed.Delta.Insert insert -> List.of(insert.row());
      case IndexingJobChangeFeed.Delta.Update update -> List.of(update.row());
      case IndexingJobChangeFeed.Delta.Resync resync -> resync.items();
      case IndexingJobChangeFeed.Delta.Delete ignored -> List.of();
    };
  }

  private static void apply(Map<String, IndexingJobChangeFeed.JobRow> rows, IndexingJobChangeFeed.Delta delta) {
    switch (delta) {
      case IndexingJobChangeFeed.Delta.Insert insert -> rows.put(insert.row().pathHash(), insert.row());
      case IndexingJobChangeFeed.Delta.Update update -> rows.put(update.row().pathHash(), update.row());
      case IndexingJobChangeFeed.Delta.Delete delete -> rows.remove(delete.pathHash());
      case IndexingJobChangeFeed.Delta.Resync resync -> {
        rows.clear();
        resync.items().forEach(row -> rows.put(row.pathHash(), row));
      }
    }
  }
}
