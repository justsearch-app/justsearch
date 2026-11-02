/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.ipc.ResetIndexRequest;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResetQueueFailureTest {
  @TempDir Path temp;

  @Test
  void sqlClearFailureFailsResetBeforeIndexDeletion() throws Exception {
    Path database = temp.resolve("jobs.db");
    try (var metrics = mockStatic(io.justsearch.indexerworker.metrics.OperationalMetrics.class);
        var queue = new SqliteJobQueue(database);
        var raw = DriverManager.getConnection("jdbc:sqlite:" + database);
        var sql = raw.createStatement()) {
      metrics.when(io.justsearch.indexerworker.metrics.OperationalMetrics::getInstance)
          .thenReturn(mock(io.justsearch.indexerworker.metrics.OperationalMetrics.class));
      queue.open();
      queue.enqueue(List.of(temp.resolve("pending.txt")));
      sql.execute("CREATE TRIGGER refuse_clear BEFORE DELETE ON jobs BEGIN SELECT RAISE(ABORT, 'clear refused'); END");
      IndexingLoop loop = mock();
      doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(loop).resetForProfiling(any());
      RunningRuntime runtime = mock();
      IndexingCoordinator indexing = mock();
      when(runtime.indexingCoordinator()).thenReturn(indexing);
      when(runtime.commitOps()).thenReturn(mock(io.justsearch.adapters.lucene.runtime.CommitOps.class));
      var service = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(),
          temp, temp.resolve("index"), runtime, null, null, 0L);
      assertFalse(service.resetIndex(ResetIndexRequest.getDefaultInstance(), CallContext.none()).getSuccess());
      verify(indexing, never()).deleteAll();
      assertEquals(1, queue.queueDepth(), "the failed transaction retains pending work");
      sql.execute("DROP TRIGGER refuse_clear");
      assertEquals(1, queue.clearAll());
      assertEquals(0, queue.clearAll(), "a legitimate empty clear is successful");
    }
  }
}
