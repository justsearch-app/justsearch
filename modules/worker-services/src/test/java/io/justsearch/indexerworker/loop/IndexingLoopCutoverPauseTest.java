/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexingLoopCutoverPauseTest {

  @Test
  void pauseWaitsForAnAlreadyClaimedBatch(@TempDir Path tempDir) throws Exception {
    JobQueue queue = mock(JobQueue.class);
    JobQueue.IndexJob claim =
        new JobQueue.IndexJob(tempDir.resolve("missing-cutover-pause-test-document"), null);
    when(queue.pollPending(anyInt())).thenReturn(List.of(claim), List.of());
    when(queue.queueDepth()).thenReturn(0L);
    CountDownLatch outcomeWriteEntered = new CountDownLatch(1);
    CountDownLatch releaseOutcomeWrite = new CountDownLatch(1);
    doAnswer(ignored -> {
      outcomeWriteEntered.countDown();
      assertTrue(releaseOutcomeWrite.await(5, TimeUnit.SECONDS));
      return true;
    }).when(queue).markClaimDone(eq(claim), any(), any());

    IndexingLoop loop = newLoop(queue);
    loop.start();
    AtomicBoolean pauseResult = new AtomicBoolean(false);
    Thread requester = new Thread(
        () -> pauseResult.set(loop.pauseForCutover(5_000L)), "cutover-pause-requester");
    try {
      assertTrue(outcomeWriteEntered.await(5, TimeUnit.SECONDS));
      requester.start();
      awaitWaiting(requester, 5_000L);
      assertTrue(requester.isAlive(), "pause must wait while the claimed batch is still active");

      releaseOutcomeWrite.countDown();
      requester.join(5_000L);
      assertFalse(requester.isAlive());
      assertTrue(pauseResult.get());
      verify(queue).markClaimDone(eq(claim), any(), any());
    } finally {
      releaseOutcomeWrite.countDown();
      requester.interrupt();
      loop.resumeAfterCutover();
      loop.close();
    }
  }

  @Test
  void successfulPauseStopsPollingAndResumeReusesTheSameThread() throws Exception {
    JobQueue queue = mock(JobQueue.class);
    AtomicInteger polls = new AtomicInteger();
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      polls.incrementAndGet();
      return List.of();
    });
    when(queue.queueDepth()).thenReturn(0L);

    IndexingLoop loop = newLoop(queue);
    loop.start();
    try {
      awaitAtLeast(polls, 1, 5_000L);
      Thread originalThread = loopThread(loop);

      assertTrue(loop.pauseForCutover(5_000L));
      int pollsAtPause = polls.get();
      Thread.sleep(200L);
      assertEquals(pollsAtPause, polls.get(), "a parked loop must not poll the queue");
      assertTrue(originalThread.isAlive(), "pause parks rather than terminates the loop thread");

      loop.resumeAfterCutover();
      awaitAtLeast(polls, pollsAtPause + 1, 5_000L);
      assertSame(originalThread, loopThread(loop), "resume must reuse the existing thread");
    } finally {
      loop.close();
    }
  }

  @Test
  void timeoutWithdrawsPauseRequest() throws Exception {
    JobQueue queue = mock(JobQueue.class);
    AtomicInteger polls = new AtomicInteger();
    CountDownLatch firstPollEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstPoll = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      if (polls.incrementAndGet() == 1) {
        firstPollEntered.countDown();
        assertTrue(releaseFirstPoll.await(5, TimeUnit.SECONDS));
      }
      return List.of();
    });
    when(queue.queueDepth()).thenReturn(0L);

    IndexingLoop loop = newLoop(queue);
    loop.start();
    try {
      assertTrue(firstPollEntered.await(5, TimeUnit.SECONDS));
      assertFalse(loop.pauseForCutover(25L));
      releaseFirstPoll.countDown();
      awaitAtLeast(polls, 2, 5_000L);
    } finally {
      releaseFirstPoll.countDown();
      loop.close();
    }
  }

  @Test
  void interruptionWithdrawsPauseRequest() throws Exception {
    JobQueue queue = mock(JobQueue.class);
    AtomicInteger polls = new AtomicInteger();
    CountDownLatch firstPollEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstPoll = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      if (polls.incrementAndGet() == 1) {
        firstPollEntered.countDown();
        assertTrue(releaseFirstPoll.await(5, TimeUnit.SECONDS));
      }
      return List.of();
    });
    when(queue.queueDepth()).thenReturn(0L);

    IndexingLoop loop = newLoop(queue);
    loop.start();
    AtomicBoolean pauseResult = new AtomicBoolean(true);
    AtomicBoolean interruptRestored = new AtomicBoolean(false);
    Thread requester = new Thread(() -> {
      pauseResult.set(loop.pauseForCutover(5_000L));
      interruptRestored.set(Thread.currentThread().isInterrupted());
    }, "cutover-pause-requester");
    try {
      assertTrue(firstPollEntered.await(5, TimeUnit.SECONDS));
      requester.start();
      awaitWaiting(requester, 5_000L);
      requester.interrupt();
      requester.join(5_000L);
      assertFalse(requester.isAlive());
      assertFalse(pauseResult.get());
      assertTrue(interruptRestored.get(), "the caller's interrupt status must be restored");

      releaseFirstPoll.countDown();
      awaitAtLeast(polls, 2, 5_000L);
    } finally {
      releaseFirstPoll.countDown();
      requester.interrupt();
      loop.close();
    }
  }

  @Test
  void closeWakesParkedLoop() throws Exception {
    JobQueue queue = mock(JobQueue.class);
    AtomicInteger polls = new AtomicInteger();
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      polls.incrementAndGet();
      return List.of();
    });
    when(queue.queueDepth()).thenReturn(0L);

    IndexingLoop loop = newLoop(queue);
    loop.start();
    awaitAtLeast(polls, 1, 5_000L);
    assertTrue(loop.pauseForCutover(5_000L));
    Thread parkedThread = loopThread(loop);

    loop.close();

    assertFalse(parkedThread.isAlive(), "close must notify and join the parked loop");
    assertFalse(loop.isRunning());
  }

  private static IndexingLoop newLoop(JobQueue queue) {
    return new IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
        queue,
        mock(IndexingCoordinator.class),
        mock(CommitOps.class),
        mock(DocumentFieldOps.class),
        mock(IndexCountOps.class),
        () -> null,
        mock(WorkerSignalBus.class),
        IndexingPacing.unthrottled(),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static Thread loopThread(IndexingLoop loop) throws Exception {
    Field field = IndexingLoop.class.getDeclaredField("loopThread");
    field.setAccessible(true);
    return (Thread) field.get(loop);
  }

  private static void awaitAtLeast(AtomicInteger value, int expected, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (value.get() < expected && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    assertTrue(value.get() >= expected, "timed out waiting for value >= " + expected);
  }

  private static void awaitWaiting(Thread thread, long timeoutMs) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (System.nanoTime() < deadline) {
      Thread.State state = thread.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) return;
      Thread.sleep(5L);
    }
    fail("requester never waited for the loop to park");
  }
}
