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
import io.justsearch.ipc.StatusResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexStatusOpsWaitingTest {
  @TempDir Path directory;

  @Test
  void retryOnlyBacklogWaitsUntilReadyThenProcessingAndWaitsAgain() {
    JobQueue queue = mock(JobQueue.class);
    IndexingLoop loop = mock(IndexingLoop.class);
    IndexStatusOps ops = ops(queue, loop);
    long retry = System.currentTimeMillis() + 60_000;
    when(queue.failureSummary()).thenReturn(new JobQueue.FailureSummary(0, null, null, null, retry));
    counts(queue, 7, 0, 0, 0, 0);
    StatusResponse waiting = ops.buildStatusResponse();
    assertEquals("WAITING", waiting.getCore().getState());
    assertEquals(7, waiting.getCore().getQueueDepth());
    assertEquals(7, waiting.getMigration().getPendingBackoffJobsCount());
    assertEquals(retry, waiting.getFailure().getNextRetryAtMs());

    // Ready work coexists with the waiting tail; readiness does not require a claim.
    counts(queue, 7, 1, 0, 0, 0);
    assertEquals("INDEXING", ops.buildStatusResponse().getCore().getState());
    counts(queue, 6, 0, 1, 0, 0);
    assertEquals("INDEXING", ops.buildStatusResponse().getCore().getState());
    counts(queue, 7, 0, 0, 0, 0);
    assertEquals("WAITING", ops.buildStatusResponse().getCore().getState());
    counts(queue, 0, 0, 0, 7, 0);
    assertEquals("IDLE", ops.buildStatusResponse().getCore().getState());
  }

  @Test
  void terminalFailuresAndFatalLoopNeverPretendToProcess() {
    JobQueue queue = mock(JobQueue.class);
    IndexingLoop loop = mock(IndexingLoop.class);
    IndexStatusOps ops = ops(queue, loop);
    when(queue.failureSummary()).thenReturn(new JobQueue.FailureSummary(2, null, null, null, null));
    counts(queue, 0, 0, 0, 0, 2);
    assertEquals("ERROR", ops.buildStatusResponse().getCore().getState());
    counts(queue, 1, 0, 0, 0, 2);
    assertEquals("WAITING", ops.buildStatusResponse().getCore().getState());
    counts(queue, 1, 1, 0, 0, 2);
    assertEquals("INDEXING", ops.buildStatusResponse().getCore().getState());
    when(loop.hasFailed()).thenReturn(true);
    for (long ready : new long[] {0, 1}) {
      counts(queue, 1, ready, 0, 0, 2);
      StatusResponse failed = ops.buildStatusResponse();
      assertEquals("FAILED", failed.getCore().getState());
      assertFalse(failed.getCore().getIsHealthy());
    }
  }

  @Test
  void completedQueueRowsAreNotDocumentsWhenNoReaderExists() {
    JobQueue queue = mock(JobQueue.class);
    counts(queue, 0, 0, 0, 91, 0);
    when(queue.completedCount()).thenReturn(91L);
    StatusResponse status = ops(queue, null).buildStatusResponse();
    assertEquals(0, status.getCore().getDocCount());
    assertTrue(status.getCore().getDocCountUnknown());
  }

  private static void counts(JobQueue queue, long pending, long ready, long processing,
      long done, long failed) {
    when(queue.queueDepth()).thenReturn(pending + processing);
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(pending, ready, processing, done, failed));
  }

  private IndexStatusOps ops(JobQueue queue, IndexingLoop loop) {
    return new IndexStatusOps(queue, directory, null, null, null, null, null, null, null,
        null, null, OperationalMetrics.getInstance(), loop, mock(WorkerSignalBus.class), 0L);
  }
}
