/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.CoreStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexStatusOpsLoopFailureTest {
  @TempDir Path indexDirectory;

  @Test
  void fatalLoopOverridesBothQueuedWorkAndOrdinaryDocumentFailures() {
    IndexingLoop loop = mock(IndexingLoop.class);
    when(loop.hasFailed()).thenReturn(true);
    for (long queueDepth : new long[] {0, 4}) {
      for (long failedDocuments : new long[] {0, 1}) {
        CoreStatus core = status(loop, queueDepth, failedDocuments);
        assertEquals("FAILED", core.getState());
        assertFalse(core.getIsHealthy());
      }
    }
  }

  @Test
  void ordinaryDocumentFailureKeepsErrorDisposition() {
    CoreStatus core = status(mock(IndexingLoop.class), 0, 1);
    assertEquals("ERROR", core.getState());
    assertFalse(core.getIsHealthy());
  }

  @Test
  void queuedWorkWithDocumentFailuresKeepsNonfatalIndexingDisposition() {
    CoreStatus core = status(mock(IndexingLoop.class), 4, 1);
    assertEquals("INDEXING", core.getState());
    assertFalse(core.getIsHealthy());
  }

  @Test
  void deferredOrIntentionallyStoppedLoopIsNotFatal() {
    // A mock's isRunning() is false, just as for an intentionally quiesced loop. That alone
    // must never be interpreted as a fatal event; deferred startup has no loop at all.
    for (IndexingLoop loop : new IndexingLoop[] {null, mock(IndexingLoop.class)}) {
      CoreStatus idle = status(loop, 0, 0);
      assertEquals("IDLE", idle.getState());
      assertTrue(idle.getIsHealthy());
      assertEquals("INDEXING", status(loop, 4, 0).getState());
    }
  }

  private CoreStatus status(IndexingLoop loop, long queueDepth, long failedDocuments) {
    JobQueue queue = mock(JobQueue.class);
    when(queue.queueDepth()).thenReturn(queueDepth);
    when(queue.failureSummary())
        .thenReturn(new JobQueue.FailureSummary(failedDocuments, null, null, null, null));
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    IndexStatusOps ops =
        new IndexStatusOps(
            queue, indexDirectory, null, null, null, null, null, null, null, null, null,
            OperationalMetrics.getInstance(), loop, mock(WorkerSignalBus.class), 0L);
    return ops.buildStatusResponse().getCore();
  }
}
