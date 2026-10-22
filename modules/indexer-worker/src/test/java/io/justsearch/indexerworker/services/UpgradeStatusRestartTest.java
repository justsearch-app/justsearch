/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UpgradeStatusRestartTest {
  @TempDir Path directory;

  @Test
  void reopenedQueueReportsWaitingAndDueWorkThenProcessingAndCompletion() throws Exception {
    Path database = directory.resolve("jobs.db");
    long later = System.currentTimeMillis() + 3_600_000;
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      queue.enqueue(List.of(directory.resolve("one.txt"), directory.resolve("two.txt")));
      update(database, "UPDATE jobs SET retry_after=" + later);
      assertEquals("WAITING", status(queue).getCore().getState());
    }
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      StatusResponse waiting = status(queue);
      assertEquals("WAITING", waiting.getCore().getState());
      assertEquals(2, waiting.getCore().getQueueDepth());
      assertEquals(later, waiting.getFailure().getNextRetryAtMs());
      // retry_after equal to now is due, including with a second job still waiting.
      StatusResponse ready = statusAtRetryDeadline(queue, database);
      assertEquals(1, ready.getMigration().getPendingReadyJobsCount());
      assertEquals("INDEXING", ready.getCore().getState());
      var claims = queue.pollPending(1);
      assertEquals(1, claims.size());
      assertEquals(1, status(queue).getMigration().getProcessingJobsCount());
      assertEquals("INDEXING", status(queue).getCore().getState());
      queue.markDone(claims.get(0).path());
      assertEquals("WAITING", status(queue).getCore().getState());
      update(database, "UPDATE jobs SET state='DONE',retry_after=NULL");
      assertEquals("IDLE", status(queue).getCore().getState());
    }
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      assertEquals("IDLE", status(queue).getCore().getState());
      update(database, "UPDATE jobs SET state='RETRY_EXHAUSTED' WHERE path LIKE '%two.txt'");
      assertEquals("ERROR", status(queue).getCore().getState());
    }
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      assertEquals("ERROR", status(queue).getCore().getState());
      assertEquals(0, status(queue).getCore().getQueueDepth());
    }
  }

  /** Set one deadline to the exact time bound by the production readiness query, without sleeping. */
  private StatusResponse statusAtRetryDeadline(SqliteJobQueue queue, Path database) throws Exception {
    var field = SqliteJobQueue.class.getDeclaredField("connection");
    field.setAccessible(true);
    var connection = (java.sql.Connection) field.get(queue);
    var observed = new java.util.concurrent.atomic.AtomicLong();
    var intercept = mock(java.sql.Connection.class, delegatesTo(connection));
    doAnswer(call -> {
      String sql = call.getArgument(0);
      var statement = connection.prepareStatement(sql);
      if (!sql.contains("AS pending_ready_count")) return statement;
      var bound = mock(java.sql.PreparedStatement.class, delegatesTo(statement));
      doAnswer(bind -> {
        long now = bind.getArgument(1);
        observed.set(now);
        update(database, "UPDATE jobs SET retry_after=" + now + " WHERE path LIKE '%one.txt'");
        statement.setLong(1, now);
        return null;
      }).when(bound).setLong(eq(1), anyLong());
      return bound;
    }).when(intercept).prepareStatement(anyString());
    field.set(queue, intercept);
    try {
      StatusResponse response = status(queue);
      assertEquals(observed.get(), response.getFailure().getNextRetryAtMs(),
          "the row deadline is exactly the query's observation time");
      assertTrue(observed.get() > 0);
      return response;
    } finally {
      field.set(queue, connection);
    }
  }

  private StatusResponse status(SqliteJobQueue queue) {
    var service = new WorkerIngestService(queue, mock(IndexingLoop.class), mock(WorkerSignalBus.class),
        IndexingPacing.unthrottled(), directory.resolve("base"), directory.resolve("index"),
        null, null, null, 0L);
    return service.indexStatus(StatusRequest.getDefaultInstance(), CallContext.none());
  }

  private static void update(Path database, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }
}
