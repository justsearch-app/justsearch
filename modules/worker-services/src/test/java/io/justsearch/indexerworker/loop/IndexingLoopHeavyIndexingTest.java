/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.core.scheduling.IndexingHold;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class IndexingLoopHeavyIndexingTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"unavailable", "writes-disabled", "gpu-held"})
  void backfillOnlyPausesAnswersWhenItCanProgress(String condition) throws Exception {
    var available = new AtomicBoolean(true);
    var allowed = new AtomicBoolean(true);
    var gpuHeld = new AtomicBoolean();
    var provider = mock(io.justsearch.indexerworker.embed.EmbeddingProvider.class);
    when(provider.isAvailable()).thenAnswer(ignored -> available.get());
    when(provider.isUsingGpu()).thenReturn(true);
    var compatibility = mock(io.justsearch.indexerworker.embed.EmbeddingCompatibilityController.class);
    when(compatibility.allowEmbeddingWrites()).thenAnswer(ignored -> allowed.get());
    var signals = mock(WorkerSignalBus.class);
    when(signals.isMainGpuActive()).thenAnswer(ignored -> gpuHeld.get());
    var queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    var counts = mock(IndexCountOps.class);
    when(counts.countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS,
        SchemaFields.EMBEDDING_STATUS_PENDING)).thenReturn(50);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      entered.countDown();
      release.await(30, TimeUnit.SECONDS);
      return List.of();
    });
    var loop = new IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
        mock(IndexingCoordinator.class), mock(CommitOps.class), mock(DocumentFieldOps.class),
        counts, () -> null, signals, IndexingPacing.unthrottled(),
        null, null, null, null, null, null, null);
    loop.getEmbeddingLifecycle().setEmbeddingProvider(provider);
    loop.getEmbeddingLifecycle().setEmbeddingCompatController(compatibility);
    try {
      loop.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(loop.heavyIndexing(), "a runnable retained backlog counts");
      if (condition.equals("unavailable")) available.set(false);
      else if (condition.equals("writes-disabled")) allowed.set(false);
      else gpuHeld.set(true);
      assertFalse(loop.heavyIndexing(), "a retained backlog that cannot run must not pause answers");
      available.set(true); allowed.set(true); gpuHeld.set(false);
      assertTrue(loop.heavyIndexing(), "the same backlog counts again when its provider returns");
    } finally {
      release.countDown();
      loop.close();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.CsvSource({"50,0,true,true", "0,50,true,false", "0,50,false,true"})
  void gpuHoldOnlyExcludesGpuEmbeddingWork(long files, int embeddings, boolean gpu, boolean heavy)
      throws Exception {
    var signals = mock(WorkerSignalBus.class);
    when(signals.isBulkHeld()).thenReturn(true);
    var provider = mock(io.justsearch.indexerworker.embed.EmbeddingProvider.class);
    when(provider.isAvailable()).thenReturn(true);
    when(provider.isUsingGpu()).thenReturn(gpu);
    var queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(files, files, 0, 0, 0));
    var counts = mock(IndexCountOps.class);
    when(counts.countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS,
        SchemaFields.EMBEDDING_STATUS_PENDING)).thenReturn(embeddings);
    var polling = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      polling.countDown();
      assertTrue(release.await(30, TimeUnit.SECONDS));
      return List.of();
    });
    var loop = new IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
        mock(IndexingCoordinator.class), mock(CommitOps.class), mock(DocumentFieldOps.class),
        counts, () -> null, signals, IndexingPacing.unthrottled(),
        null, null, null, null, null, null, null);
    loop.getEmbeddingLifecycle().setEmbeddingProvider(provider);
    try {
      loop.start();
      assertTrue(polling.await(5, TimeUnit.SECONDS));
      assertEquals(heavy, loop.heavyIndexing());
    } finally {
      release.countDown();
      loop.close();
    }
  }

  @Test
  void drainedBacklogThenSmallNewBatchCannotBeOverwrittenByAnOlderSample() throws Exception {
    var files = new AtomicLong(50);
    var queue = mock(JobQueue.class);
    var sampling = new CountDownLatch(1);
    var releaseSample = new CountDownLatch(1);
    var sampler = new java.util.concurrent.atomic.AtomicReference<Thread>();
    when(queue.jobStateCounts()).thenAnswer(ignored -> {
      long captured = files.get();
      if (Thread.currentThread() == sampler.get()) {
        sampling.countDown();
        assertTrue(releaseSample.await(10, TimeUnit.SECONDS));
      }
      return new JobQueue.JobStateCounts(captured, captured, 0, 0, 0);
    });
    var polling = new CountDownLatch(1);
    var releaseLoop = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      polling.countDown();
      assertTrue(releaseLoop.await(30, TimeUnit.SECONDS));
      return List.of();
    });
    var loop = new IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
        mock(IndexingCoordinator.class), mock(CommitOps.class), mock(DocumentFieldOps.class),
        mock(IndexCountOps.class), () -> null, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
        null, null, null, null, null, null, null);
    var stale = new java.util.concurrent.FutureTask<Boolean>(loop::heavyIndexing);
    var oldThread = new Thread(stale, "heavy-indexing-older");
    sampler.set(oldThread);
    var newer = new java.util.concurrent.FutureTask<Boolean>(() -> {
      assertFalse(loop.heavyIndexing(), "drained backlog clears the pause");
      files.set(20);
      return loop.heavyIndexing();
    });
    var newThread = new Thread(newer, "heavy-indexing-newer");
    try {
      loop.start();
      assertTrue(polling.await(5, TimeUnit.SECONDS));
      oldThread.start();
      assertTrue(sampling.await(5, TimeUnit.SECONDS));
      files.set(0);
      newThread.start();
      // With serialization the new read waits; without it both newer reads finish first.
      awaitBlockedOrFinished(newThread);
      releaseSample.countDown();
      stale.get(5, TimeUnit.SECONDS);
      assertFalse(newer.get(5, TimeUnit.SECONDS));
      assertFalse(loop.heavyIndexing(), "20 new files cannot inherit the drained batch's pause");
    } finally {
      releaseSample.countDown();
      releaseLoop.countDown();
      oldThread.join(5000);
      newThread.join(5000);
      loop.close();
    }
  }

  private static void awaitBlockedOrFinished(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED
        && System.nanoTime() < deadline) thread.join(10);
    assertTrue(!thread.isAlive() || thread.getState() == Thread.State.BLOCKED,
        "the competing sample must finish or wait for the sample lock");
  }

  @Test
  void aSampleCapturedBeforeManualPauseCannotRestoreClearedHysteresis() throws Exception {
    var files = new AtomicLong(50);
    var hold = new IndexingHold();
    var signals = mock(WorkerSignalBus.class);
    when(signals.indexingHold()).thenReturn(hold);
    when(signals.isIndexingHeld()).thenAnswer(ignored -> hold.held());
    var queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenAnswer(ignored ->
        new JobQueue.JobStateCounts(files.get(), files.get(), 0, 0, 0));
    var polling = new CountDownLatch(1);
    var releaseLoop = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      polling.countDown();
      assertTrue(releaseLoop.await(30, TimeUnit.SECONDS));
      return List.of();
    });
    var sampling = new CountDownLatch(1);
    var releaseSample = new CountDownLatch(1);
    var sampler = new java.util.concurrent.atomic.AtomicReference<Thread>();
    var counts = mock(IndexCountOps.class);
    when(counts.countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS,
        SchemaFields.EMBEDDING_STATUS_PENDING)).thenAnswer(ignored -> {
      if (Thread.currentThread() == sampler.get()) {
        sampling.countDown();
        assertTrue(releaseSample.await(10, TimeUnit.SECONDS));
      }
      return 0;
    });
    var loop = new IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
        mock(IndexingCoordinator.class), mock(CommitOps.class), mock(DocumentFieldOps.class),
        counts, () -> null, signals, IndexingPacing.unthrottled(),
        null, null, null, null, null, null, null);
    var provider = mock(io.justsearch.indexerworker.embed.EmbeddingProvider.class);
    when(provider.isAvailable()).thenReturn(true);
    loop.getEmbeddingLifecycle().setEmbeddingProvider(provider);
    var stale = new java.util.concurrent.FutureTask<Boolean>(loop::heavyIndexing);
    var thread = new Thread(stale, "heavy-indexing-stale-sample");
    sampler.set(thread);
    try {
      loop.start();
      assertTrue(polling.await(5, TimeUnit.SECONDS));
      thread.start();
      assertTrue(sampling.await(5, TimeUnit.SECONDS), "50 files captured before the embedding read stalls");
      hold.pause(null, "USER_PAUSED");
      var reset = new java.util.concurrent.FutureTask<Boolean>(loop::heavyIndexing);
      var resetThread = new Thread(reset, "heavy-indexing-reset");
      resetThread.start();
      awaitBlockedOrFinished(resetThread);
      releaseSample.countDown();
      assertFalse(stale.get(5, TimeUnit.SECONDS), "a hold observed during the sample must clear it");
      assertFalse(reset.get(5, TimeUnit.SECONDS));
      resetThread.join(5000);
      files.set(20);
      hold.resume();
      assertFalse(loop.heavyIndexing(), "resuming at 20 files must remain below the entry threshold");
      files.set(50);
      assertTrue(loop.heavyIndexing(), "a fresh sample can still enter heavy indexing");
    } finally {
      releaseSample.countDown();
      releaseLoop.countDown();
      thread.join(5000);
      loop.close();
    }
  }

  @Test
  void anOwnerObservedPauseResetsHysteresisWithoutAWindowReadingIt() throws Exception {
    var files = new AtomicLong(50);
    var hold = new IndexingHold();
    var pausedObserved = new CountDownLatch(1);
    var signals = mock(WorkerSignalBus.class, invocation -> switch (invocation.getMethod().getName()) {
      case "indexingHold" -> hold;
      case "isIndexingHeld" -> hold.held();
      case "isMainGpuActive" -> {
        if (hold.held()) pausedObserved.countDown();
        yield false;
      }
      default -> RETURNS_DEFAULTS.answer(invocation);
    });
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenAnswer(ignored ->
        new JobQueue.JobStateCounts(files.get(), files.get(), 0, 0, 0));
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      entered.countDown();
      release.await(10, TimeUnit.SECONDS);
      return List.of();
    });
    var loop = new IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
        mock(IndexingCoordinator.class), mock(CommitOps.class), mock(DocumentFieldOps.class),
        mock(IndexCountOps.class), () -> null, signals, IndexingPacing.unthrottled(),
        null, null, null, null, null, null, null);
    try {
      loop.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(loop.heavyIndexing());
      hold.pause(null, "USER_PAUSED");
      files.set(20);
      release.countDown();
      assertTrue(pausedObserved.await(5, TimeUnit.SECONDS), "the paused owner keeps coordination live");
      hold.resume();
      assertFalse(loop.heavyIndexing(), "a pause releases hysteresis even without a status/admission read");
    } finally {
      release.countDown();
      loop.close();
    }
  }

  @Test
  void ownerReadsLiveCountsAndReleasesOnHoldsFailureStopAndRecovery() throws Exception {
    var files = new AtomicLong(50);
    var embeddings = new AtomicLong();
    var broken = new AtomicBoolean();
    var energy = new AtomicBoolean();
    var hold = new IndexingHold();
    var signals = mock(WorkerSignalBus.class, invocation -> switch (invocation.getMethod().getName()) {
      case "indexingHold" -> hold;
      case "isIndexingHeld" -> hold.held();
      case "isEnergyReduced" -> energy.get();
      default -> RETURNS_DEFAULTS.answer(invocation);
    });
    var queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenAnswer(ignored ->
        new JobQueue.JobStateCounts(files.get(), files.get(), 0, 0, 0));
    var counts = mock(IndexCountOps.class);
    when(counts.countByFieldOrThrow(
        SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING)).thenAnswer(ignored -> {
      if (broken.get()) throw new IOException("index unavailable");
      return Math.toIntExact(embeddings.get());
    });
    // Park only this test's writer at its queue boundary; the owner observation stays live.
    var pollEntered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      pollEntered.countDown();
      release.await(10, TimeUnit.SECONDS);
      return List.of();
    });
    var loop = new IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
        mock(IndexingCoordinator.class), mock(CommitOps.class), mock(DocumentFieldOps.class),
        counts, () -> null, signals, IndexingPacing.unthrottled(),
        null, null, null, null, null, null, null);
    var provider = mock(io.justsearch.indexerworker.embed.EmbeddingProvider.class);
    when(provider.isAvailable()).thenReturn(true);
    loop.getEmbeddingLifecycle().setEmbeddingProvider(provider);
    assertFalse(loop.heavyIndexing());
    try {
      loop.start();
      assertTrue(pollEntered.await(5, TimeUnit.SECONDS));
      assertTrue(loop.heavyIndexing());
      files.set(10);
      assertTrue(loop.heavyIndexing());
      embeddings.set(50); files.set(0);
      assertTrue(loop.heavyIndexing());
      hold.pause(null, "USER_PAUSED");
      assertFalse(loop.heavyIndexing());
      hold.resume();
      assertTrue(loop.heavyIndexing());
      energy.set(true);
      assertFalse(loop.heavyIndexing());
      energy.set(false);
      assertTrue(loop.heavyIndexing());
      broken.set(true);
      assertFalse(loop.heavyIndexing());
      broken.set(false);
      assertTrue(loop.heavyIndexing());
      embeddings.set(9);
      assertFalse(loop.heavyIndexing());
      files.set(1); embeddings.set(1);
      assertFalse(loop.heavyIndexing());
    } finally {
      release.countDown();
      loop.close();
    }
    files.set(100);
    assertFalse(loop.heavyIndexing(), "stopped owners cannot retain an automatic pause");
  }
}
