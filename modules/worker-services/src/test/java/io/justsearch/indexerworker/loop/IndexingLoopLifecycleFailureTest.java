/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.OutcomeWriteException;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.UpgradeQuiescenceRequest;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class IndexingLoopLifecycleFailureTest {
  @org.junit.jupiter.api.io.TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"commit", "outcome", "refresh"})
  void cutoverTimeoutReturnsWhileBoundaryPublicationIsHeld(String stage) throws Exception {
    JobQueue queue = mock();
    CommitOps commits = mock();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var polled = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(call -> { polled.countDown(); return List.of(); });
    WorkerSignalBus bus = mock();
    var starting = new CountDownLatch(1);
    var releaseStart = new CountDownLatch(1);
    doAnswer(call -> { starting.countDown(); releaseStart.await(); return null; })
        .when(bus).setPendingIngestProbe(any());
    var loop = loop(queue, commits, bus);
    if (stage.equals("commit")) {
      set(loop, "indexedSinceCommit", 1);
      doAnswer(call -> { entered.countDown(); release.await(); return null; })
          .when(commits).commitAndTrack(any(CommitReason.class));
    } else if (stage.equals("outcome")) {
      loop.getJournal().enqueueTransition(new JobQueue.IngestionLedgerTransition(
          new JobQueue.IndexJob(temporary.resolve("pending.txt"), null), null));
      doAnswer(call -> { entered.countDown(); release.await(); return null; })
          .when(queue).markDoneTransitions(anyCollection(), any());
    } else {
      doAnswer(call -> { entered.countDown(); release.await(); return null; })
          .when(commits).maybeRefreshBlocking();
    }
    var result = new CompletableFuture<Boolean>();
    var requester = new Thread(() -> result.complete(loop.pauseForCutover(5_000)));
    loop.start();
    try {
      assertTrue(starting.await(10, TimeUnit.SECONDS));
      requester.start();
      await(() -> requested(loop));
      releaseStart.countDown();
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      assertDoesNotThrow(() -> assertFalse(result.get(15, TimeUnit.SECONDS)),
          "the timeout must finish before boundary publication is released");
      release.countDown();
      assertTrue(polled.await(10, TimeUnit.SECONDS), "late boundary completion must not park the loop");
      assertTrue(loop.pauseForCutover(10_000), "a later request can still pause");
      loop.resumeAfterCutover();
    } finally {
      release.countDown();
      releaseStart.countDown();
      loop.activatePreparedStart();
      loop.resumeAfterCutover();
      requester.join(15_000);
      loop.close();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ingest", "lexical", "stamp-lexical", "outcome", "return"})
  void failedFinalPublicationBlocksUpgradeAndCanRetry(String stage) throws Exception {
    JobQueue queue = mock();
    CommitOps commits = mock();
    var polling = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var fail = new AtomicBoolean(true);
    when(queue.pollPending(anyInt())).thenAnswer(call -> {
      polling.countDown(); release.await(); return List.of();
    });
    when(queue.checkpointWal()).thenReturn(true);
    var loop = loop(queue, commits);
    if (stage.equals("lexical") || stage.equals("stamp-lexical")) {
      RunningRuntime source = mock();
      CommitOps lexical = mock();
      when(source.commitOps()).thenReturn(lexical);
      doAnswer(call -> { if (fail.get()) throw new IllegalStateException("lexical commit failed"); return null; })
          .when(lexical).commitAndTrack(any(CommitReason.class));
      loop.wireActiveLexicalSource(source);
      if (stage.equals("stamp-lexical")) {
        var embedding = mock(EmbeddingProviderLifecycle.class);
        when(embedding.tryFinalizeFreshCompatibleStamp()).thenReturn(true);
        set(loop, "embeddingLifecycle", embedding);
      }
    } else if (stage.equals("ingest")) {
      doAnswer(call -> { if (fail.get()) throw new IllegalStateException("ingest commit failed"); return null; })
          .when(commits).commitAndTrack(any(CommitReason.class));
    } else if (stage.equals("outcome")) {
      doAnswer(call -> { if (fail.get()) throw new OutcomeWriteException("SQL publication failed", null); return null; })
          .when(queue).markDoneTransitions(anyCollection(), any());
    } else {
      doAnswer(call -> { if (fail.get()) throw new OutcomeWriteException("claim return failed", null); return null; })
          .when(queue).returnUnfinishedClaims(anyCollection());
    }
    var service = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(),
        temporary, temporary.resolve("index"), null, null, null, 0L);
    var request = UpgradeQuiescenceRequest.newBuilder().setPreparationId("publication").build();
    loop.start();
    var result = new CompletableFuture<io.justsearch.ipc.UpgradeQuiescenceResponse>();
    var preparer = new Thread(() -> result.complete(service.prepareUpgrade(request, CallContext.none())));
    try {
      assertTrue(polling.await(10, TimeUnit.SECONDS));
      if (!stage.equals("return")) set(loop, "indexedSinceCommit", 1);
      if (stage.equals("outcome")) loop.getJournal().enqueueTransition(
          new JobQueue.IngestionLedgerTransition(new JobQueue.IndexJob(temporary.resolve("written.txt"), null), null));
      if (stage.equals("return")) loop.getJournal().returnBatchClaims(
          List.of(new JobQueue.IndexJob(temporary.resolve("unvisited.txt"), null)));
      preparer.start();
      await(() -> !loop.isRunning());
      release.countDown();
      var response = result.get(15, TimeUnit.SECONDS);
      assertFalse(response.getReady(), "failed final publication must block readiness even with a successful WAL checkpoint");
      assertTrue(response.getBlockersCount() > 0);
      if (stage.equals("outcome")) {
        verify(commits, atLeastOnce()).commitAndTrack(any(CommitReason.class));
        clearInvocations(commits);
      }
      fail.set(false);
      assertTrue(service.upgradeStatus(request, CallContext.none()).getReady(),
          "publication retries without another document");
      if (stage.equals("outcome")) verify(commits, never()).commitAndTrack(any(CommitReason.class));
      service.cancelUpgrade(request, CallContext.none());
      await(loop::isRunning);
    } finally {
      release.countDown();
      fail.set(false);
      preparer.join(15_000);
      loop.close();
    }
  }

  @Test
  void stopTimeoutDuringFinalCommitCanResumePolling() throws Exception {
    JobQueue queue = mock();
    CommitOps commits = mock();
    var polling = new CountDownLatch(1);
    var releasePoll = new CountDownLatch(1);
    var finalCommit = new CountDownLatch(1);
    var releaseCommit = new CountDownLatch(1);
    var resumed = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    when(queue.pollPending(anyInt())).thenAnswer(call -> {
      if (first.getAndSet(false)) { polling.countDown(); releasePoll.await(); }
      else resumed.countDown();
      return List.of();
    });
    var loop = loop(queue, commits);
    doAnswer(call -> {
      finalCommit.countDown(); releaseCommit.await(); return null;
    }).when(commits).commitAndTrack(CommitReason.INDEXING_LOOP_SHUTDOWN);
    loop.start();
    var stopped = new CompletableFuture<Boolean>();
    var stopper = new Thread(() -> stopped.complete(loop.quiesceForUpgrade(5_000)));
    try {
      assertTrue(polling.await(10, TimeUnit.SECONDS));
      stopper.start();
      await(() -> !loop.isRunning());
      // Idle publication must leave this effect for the shutdown commit.
      doAnswer(call -> { throw new IllegalStateException("defer idle commit"); })
          .when(commits).commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
      set(loop, "indexedSinceCommit", 1);
      releasePoll.countDown();
      assertTrue(finalCommit.await(10, TimeUnit.SECONDS));
      assertFalse(stopped.get(15, TimeUnit.SECONDS));
      releaseCommit.countDown();
      Thread owner = (Thread) get(loop, "loopThread");
      owner.join(10_000);
      assertFalse(owner.isAlive());
      loop.resumeAfterUpgradePreparation();
      assertTrue(resumed.await(10, TimeUnit.SECONDS));
      assertTrue(loop.isRunning());
    } finally {
      releasePoll.countDown(); releaseCommit.countDown();
      stopper.join(15_000);
      loop.close();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"timeout", "publication"})
  void cancellationResumesAnIncompletePreparation(String failure) throws Exception {
    JobQueue queue = mock();
    CommitOps commits = mock();
    var polling = new CountDownLatch(1);
    var releasePoll = new CountDownLatch(1);
    var committing = new CountDownLatch(1);
    var releaseCommit = new CountDownLatch(1);
    var resumed = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    var failed = new AtomicBoolean(true);
    when(queue.pollPending(anyInt())).thenAnswer(call -> {
      if (first.getAndSet(false)) { polling.countDown(); releasePoll.await(); }
      else resumed.countDown();
      return List.of();
    });
    when(queue.checkpointWal()).thenReturn(true);
    doAnswer(call -> {
      if (failed.get()) throw new IllegalStateException("idle publication unavailable");
      return null;
    }).when(commits).commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
    doAnswer(call -> {
      committing.countDown();
      if (failure.equals("timeout")) releaseCommit.await();
      else if (failed.get()) throw new IllegalStateException("final publication unavailable");
      return null;
    }).when(commits).commitAndTrack(CommitReason.INDEXING_LOOP_SHUTDOWN);
    var loop = loop(queue, commits);
    var service = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(),
        temporary, temporary.resolve("index"), null, null, null, 0L);
    var request = UpgradeQuiescenceRequest.newBuilder().setPreparationId("cancel-incomplete").build();
    var prepared = new CompletableFuture<io.justsearch.ipc.UpgradeQuiescenceResponse>();
    var preparer = new Thread(() -> prepared.complete(service.prepareUpgrade(request, CallContext.none())));
    loop.start();
    try {
      assertTrue(polling.await(10, TimeUnit.SECONDS));
      set(loop, "indexedSinceCommit", 1);
      preparer.start();
      await(() -> !loop.isRunning());
      releasePoll.countDown();
      assertTrue(committing.await(10, TimeUnit.SECONDS));
      assertFalse(prepared.get(20, TimeUnit.SECONDS).getReady());
      failed.set(false);
      service.cancelUpgrade(request, CallContext.none());
      releaseCommit.countDown();
      assertTrue(resumed.await(10, TimeUnit.SECONDS), "cancelling incomplete preparation must resume its original producer");
      assertTrue(loop.isRunning());
    } finally {
      failed.set(false); releasePoll.countDown(); releaseCommit.countDown();
      preparer.join(20_000); loop.close();
    }
  }

  @Test
  void preparedButGatedProducerQuiescesWithoutBeingActivatedByCancellation() throws Exception {
    JobQueue queue = mock();
    CommitOps commits = mock();
    when(queue.checkpointWal()).thenReturn(true);
    var loop = loop(queue, commits);
    var service = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(),
        temporary, temporary.resolve("index"), null, null, null, 0L);
    var request = UpgradeQuiescenceRequest.newBuilder().setPreparationId("gated").build();
    loop.prepareStart();
    try {
      assertTrue(service.prepareUpgrade(request, CallContext.none()).getReady());
      service.cancelUpgrade(request, CallContext.none());
      assertFalse(loop.isRunning());
      verify(queue, never()).pollPending(anyInt());
      assertFalse(((Thread) get(loop, "loopThread")).isAlive());
    } finally { loop.close(); }
  }

  @Test
  void interruptedStopRetainsOneOwnerAndCanResume() throws Exception {
    JobQueue queue = mock();
    var polling = new CountDownLatch(1);
    var releasePoll = new CountDownLatch(1);
    var resumed = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    when(queue.pollPending(anyInt())).thenAnswer(call -> {
      if (first.getAndSet(false)) { polling.countDown(); releasePoll.await(); }
      else resumed.countDown();
      return List.of();
    });
    var loop = loop(queue, mock(CommitOps.class));
    var stopped = new CompletableFuture<Boolean>();
    var interruptRestored = new AtomicBoolean();
    var stopper = new Thread(() -> {
      stopped.complete(loop.quiesceForUpgrade(10_000));
      interruptRestored.set(Thread.currentThread().isInterrupted());
    });
    loop.start();
    try {
      assertTrue(polling.await(10, TimeUnit.SECONDS));
      var owner = (Thread) get(loop, "loopThread");
      stopper.start();
      await(() -> !loop.isRunning());
      stopper.interrupt();
      assertFalse(stopped.get(10, TimeUnit.SECONDS));
      stopper.join(10_000);
      assertTrue(interruptRestored.get());
      loop.resumeAfterUpgradePreparation();
      assertSame(owner, get(loop, "loopThread"), "a live owner must exit or resume before another thread may poll");
      releasePoll.countDown();
      assertTrue(resumed.await(10, TimeUnit.SECONDS));
      assertTrue(loop.isRunning());
    } finally {
      releasePoll.countDown(); stopper.join(15_000); loop.close();
    }
  }

  @Test
  void interruptedResetRefusesCleanupAndRetainsOneOwner() throws Exception {
    JobQueue queue = mock();
    var polling = new CountDownLatch(1);
    var releasePoll = new CountDownLatch(1);
    var resumed = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    when(queue.pollPending(anyInt())).thenAnswer(call -> {
      if (first.getAndSet(false)) { polling.countDown(); releasePoll.await(); }
      else resumed.countDown();
      return List.of();
    });
    var loop = loop(queue, mock(CommitOps.class));
    RunningRuntime runtime = mock();
    IndexingCoordinator indexing = mock();
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    var service = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(),
        temporary, temporary.resolve("index"), runtime, null, null, 0L);
    var result = new CompletableFuture<Boolean>();
    var interruptRestored = new AtomicBoolean();
    var resetter = new Thread(() -> {
      result.complete(service.resetIndex(io.justsearch.ipc.ResetIndexRequest.getDefaultInstance(),
          CallContext.none()).getSuccess());
      interruptRestored.set(Thread.currentThread().isInterrupted());
    });
    loop.start();
    try {
      assertTrue(polling.await(10, TimeUnit.SECONDS));
      var owner = (Thread) get(loop, "loopThread");
      resetter.start();
      await(() -> !loop.isRunning());
      resetter.interrupt();
      assertFalse(result.get(10, TimeUnit.SECONDS));
      resetter.join(10_000);
      assertTrue(interruptRestored.get());
      verify(queue, never()).clearAll();
      verify(indexing, never()).deleteAll();
      assertSame(owner, get(loop, "loopThread"), "interrupted reset must not create a second live producer");
      releasePoll.countDown();
      assertTrue(resumed.await(10, TimeUnit.SECONDS));
      assertTrue(loop.isRunning());
    } finally {
      releasePoll.countDown(); resetter.join(15_000); loop.close();
    }
  }

  @Test
  void durablyStoppedFailedLoopRemainsUpgradeable() throws Exception {
    JobQueue queue = mock();
    when(queue.checkpointWal()).thenReturn(true);
    var polling = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(call -> { polling.countDown(); return List.of(); });
    var loop = loop(queue, mock(CommitOps.class));
    loop.start();
    try {
      assertTrue(polling.await(10, TimeUnit.SECONDS));
      assertTrue(loop.quiesceForUpgrade(10_000));
      set(loop, "currentState", IndexingLoop.LoopState.FAILED);
      assertTrue(loop.hasFailed());
      var service = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(),
          temporary, temporary.resolve("index"), null, null, null, 0L);
      var request = UpgradeQuiescenceRequest.newBuilder().setPreparationId("failed-but-durable").build();
      assertTrue(service.prepareUpgrade(request, CallContext.none()).getReady(),
          "a failed loop with completed durable publication can recover by upgrading");
      service.cancelUpgrade(request, CallContext.none());
      assertFalse(loop.isRunning());
    } finally { loop.close(); }
  }

  private static IndexingLoop loop(JobQueue queue, CommitOps commits) {
    return loop(queue, commits, mock(WorkerSignalBus.class));
  }

  private static IndexingLoop loop(JobQueue queue, CommitOps commits, WorkerSignalBus bus) {
    try (var metrics = mockStatic(io.justsearch.indexerworker.metrics.OperationalMetrics.class)) {
      metrics.when(io.justsearch.indexerworker.metrics.OperationalMetrics::getInstance)
          .thenReturn(mock(io.justsearch.indexerworker.metrics.OperationalMetrics.class));
      return new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
          io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
          mock(IndexingCoordinator.class), commits, mock(DocumentFieldOps.class), mock(IndexCountOps.class),
          () -> null, bus);
    }
  }

  private static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.yield();
    assertTrue(condition.getAsBoolean(), "synchronization boundary was not reached");
  }

  private static boolean requested(IndexingLoop loop) {
    try {
      synchronized (get(loop, "cutoverPauseMonitor")) { return (boolean) get(loop, "cutoverPauseRequested"); }
    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
  }

  private static Object get(IndexingLoop loop, String name) throws ReflectiveOperationException {
    Field field = IndexingLoop.class.getDeclaredField(name); field.setAccessible(true); return field.get(loop);
  }

  private static void set(IndexingLoop loop, String name, Object value) throws ReflectiveOperationException {
    Field field = IndexingLoop.class.getDeclaredField(name); field.setAccessible(true); field.set(loop, value);
  }
}
