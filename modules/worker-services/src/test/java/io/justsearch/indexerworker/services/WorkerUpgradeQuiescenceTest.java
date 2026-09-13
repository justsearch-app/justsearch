/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.queue.JobQueue;
import org.junit.jupiter.api.Test;

final class WorkerUpgradeQuiescenceTest {

  @Test
  void prepareDrainsLoopAndCheckpointsQueueBeforeReady() {
    JobQueue queue = mock(JobQueue.class);
    IndexingLoop loop = mock(IndexingLoop.class);
    when(loop.isRunning()).thenReturn(true);
    when(loop.quiesceForUpgrade(10_000)).thenReturn(true);
    when(queue.checkpointWal()).thenReturn(true);
    var barrier = new WorkerUpgradeQuiescence(queue, loop, null);

    var prepared = barrier.prepare("prep-1");

    assertTrue(prepared.getReady());
    assertTrue(prepared.getLoopQuiesced());
    assertTrue(prepared.getQueueCheckpointed());
    assertEquals("IDLE", prepared.getMigrationState());
    verify(loop).quiesceForUpgrade(10_000);
    verify(queue).checkpointWal();
  }

  @Test
  void busyCheckpointBlocksAndCancellationResumesLoop() {
    JobQueue queue = mock(JobQueue.class);
    IndexingLoop loop = mock(IndexingLoop.class);
    when(loop.isRunning()).thenReturn(true);
    when(loop.quiesceForUpgrade(10_000)).thenReturn(true);
    when(queue.checkpointWal()).thenReturn(false);
    var barrier = new WorkerUpgradeQuiescence(queue, loop, null);

    var prepared = barrier.prepare("prep-1");

    assertFalse(prepared.getReady());
    assertEquals(1, prepared.getBlockersCount());
    assertTrue(prepared.getBlockers(0).contains("checkpoint"));

    var cancelled = barrier.cancel("prep-1");
    assertFalse(cancelled.getReady());
    verify(loop).resumeAfterUpgradePreparation();
    assertThrows(IllegalArgumentException.class, () -> barrier.status("prep-1"));
  }

  @Test
  void statusRetriesAnOwnedPreparation() {
    JobQueue queue = mock(JobQueue.class);
    IndexingLoop loop = mock(IndexingLoop.class);
    when(loop.isRunning()).thenReturn(true);
    when(loop.quiesceForUpgrade(10_000)).thenReturn(true);
    when(queue.checkpointWal()).thenReturn(false, true);
    var barrier = new WorkerUpgradeQuiescence(queue, loop, null);

    assertFalse(barrier.prepare("prep-1").getReady());
    assertTrue(barrier.status("prep-1").getReady());
  }

  @Test
  void cancellationDoesNotStartALoopThatWasAlreadyStopped() {
    IndexingLoop loop = mock(IndexingLoop.class);
    when(loop.isRunning()).thenReturn(false);
    when(loop.quiesceForUpgrade(10_000)).thenReturn(true);
    var barrier = new WorkerUpgradeQuiescence(null, loop, null);

    assertTrue(barrier.prepare("prep-1").getReady());
    barrier.cancel("prep-1");

    verify(loop, org.mockito.Mockito.never()).resumeAfterUpgradePreparation();
  }

  @Test
  void barrierIsOwnedByOnePreparationId() {
    var barrier = new WorkerUpgradeQuiescence(null, null, null);
    assertTrue(barrier.prepare("prep-1").getReady());
    assertFalse(barrier.prepare("prep-2").getReady());
    assertThrows(IllegalArgumentException.class, () -> barrier.cancel("prep-2"));
  }
}
