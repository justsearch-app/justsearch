/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** S36.6/7: the hold precedes the activation gate but permits transition coordination. */
final class IndexingLoopUserPauseTest {
  private IndexingLoop loop(JobQueue queue, WorkerSignalBus signals, CommitOps commits) {
    return new IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
        mock(IndexingCoordinator.class), commits, mock(DocumentFieldOps.class),
        mock(IndexCountOps.class), () -> null, signals, IndexingPacing.unthrottled(),
        null, null, null, null, null, null, null);
  }

  @Test
  void startsWithoutPollingAndCutoverAndUpgradeStillComplete() throws Exception {
    Object hold = assertDoesNotThrow(() -> Class.forName("io.justsearch.core.scheduling.IndexingHold")
        .getConstructor().newInstance(), "startup requires the persisted user hold");
    hold.getClass().getMethod("pause", Long.class, String.class).invoke(hold, null, "USER_PAUSED");
    var gpuControl = new CountDownLatch(1);
    var signals = mock(WorkerSignalBus.class, invocation -> switch (invocation.getMethod().getName()) {
      case "indexingHold" -> hold;
      case "isIndexingHeld" -> hold.getClass().getMethod("held").invoke(hold);
      case "isMainGpuActive" -> {
        if (Thread.currentThread().getName().equals("indexing-loop")) gpuControl.countDown();
        yield false;
      }
      default -> RETURNS_DEFAULTS.answer(invocation);
    });
    var queue = mock(JobQueue.class);
    var polled = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> { polled.countDown(); return List.of(); });
    var loop = loop(queue, signals, mock(CommitOps.class));
    try {
      loop.prepareStart();
      loop.activatePreparedStart();
      assertTrue(gpuControl.await(5, TimeUnit.SECONDS),
          "GPU handoff coordination must remain live for search and Ask while paused");
      assertFalse(polled.await(200, TimeUnit.MILLISECONDS), "paused startup must process no file");
      assertTrue(loop.pauseForCutover(5_000), "cutover acknowledgment remains reachable");
      loop.resumeAfterCutover();
      assertTrue(loop.quiesceForUpgrade(5_000), "upgrade must not wait for Resume");
      loop.resumeAfterUpgradePreparation();
      assertFalse(polled.await(200, TimeUnit.MILLISECONDS), "upgrade resume retains the user hold");
      hold.getClass().getMethod("resume").invoke(hold);
      assertTrue(polled.await(5, TimeUnit.SECONDS), "Resume drains the existing queue");
    } finally {
      loop.resumeAfterCutover();
      loop.close();
    }
  }

  @Test
  void quietWaitsForTheReadersPublicationAndNoFurtherQueuePollStarts() throws Exception {
    Object hold = assertDoesNotThrow(() -> Class.forName("io.justsearch.core.scheduling.IndexingHold")
        .getConstructor().newInstance(), "quiet needs a processing acknowledgment");
    var signals = mock(WorkerSignalBus.class, invocation -> switch (invocation.getMethod().getName()) {
      case "indexingHold" -> hold;
      case "isIndexingHeld" -> hold.getClass().getMethod("held").invoke(hold);
      default -> RETURNS_DEFAULTS.answer(invocation);
    });
    var queue = mock(JobQueue.class);
    var polls = new AtomicInteger();
    var polled = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      polls.incrementAndGet();
      polled.countDown();
      return List.of();
    });
    var commits = mock(CommitOps.class);
    var publishing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(ignored -> {
      publishing.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return null;
    }).when(commits).maybeRefreshBlocking();
    var loop = loop(queue, signals, commits);
    try {
      loop.start();
      assertTrue(polled.await(5, TimeUnit.SECONDS));
      hold.getClass().getMethod("pause", Long.class, String.class).invoke(hold, null, "USER_PAUSED");
      assertTrue(publishing.await(5, TimeUnit.SECONDS));
      Object snapshot = hold.getClass().getMethod("snapshot").invoke(hold);
      assertEquals("PAUSING", snapshot.getClass().getMethod("state").invoke(snapshot).toString(),
          "a completed batch is not quiet while its reader publication is pending");
      release.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      do {
        snapshot = hold.getClass().getMethod("snapshot").invoke(hold);
        if ("PAUSED".equals(snapshot.getClass().getMethod("state").invoke(snapshot).toString())) break;
        Thread.sleep(10);
      } while (System.nanoTime() < deadline);
      assertEquals("PAUSED", snapshot.getClass().getMethod("state").invoke(snapshot).toString());
      int acknowledged = polls.get();
      Thread.sleep(150);
      assertEquals(acknowledged, polls.get());
    } finally {
      release.countDown();
      loop.close();
    }
  }
}
