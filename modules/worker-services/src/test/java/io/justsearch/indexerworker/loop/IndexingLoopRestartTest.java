package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractorProvider;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.server.EncoderBindings;
import io.justsearch.indexerworker.ner.NerService;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tempdoc 516 W2.2 — restart-cycle regression test.
 *
 * <p>The 403 Tier C lesson is "an audit said `analyzerRegistry` was the only restart
 * blocker; reality had two more (state machine, indexingCoordinator)." This test
 * exercises start → idle → close → start → idle → close on real {@link IndexingLoop}
 * instances and asserts state-machine + journal-pending + counter resets across the
 * cycle. Catches the bug class that {@code IndexingLoopTest}'s reflection-only
 * style misses: it runs {@link IndexingLoop#runLoop} on a real thread.
 *
 * <p>Mocks {@link JobQueue}/{@link IndexingCoordinator}/{@link CommitOps} so no real
 * Lucene fixture is needed. The loop spins on empty {@link JobQueue#pollPending},
 * reaches {@link IndexingLoop.LoopState#IDLE} on the first idle iteration, and we
 * verify the lifecycle teardown. Per Appendix A.7: this is the Tier-A unit-level
 * gate; Tier-B (multi-process JVM restart via IsolatedBackendFixture) remains
 * deferred.
 */
@ExtendWith(MockitoExtension.class)
final class IndexingLoopRestartTest {

  @Test
  @DisplayName("start → idle → close cycle: state returns to IDLE; loopThread terminates")
  void singleCycleReachesIdleAndShutsDownCleanly() throws Exception {
    IndexingLoop loop = newLoopWithEmptyQueue();

    loop.start();
    awaitLoopState(loop, IndexingLoop.LoopState.IDLE, 5_000L);

    assertTrue(reflectAtomicBoolean(loop, "running").get(), "running should be true while loop is alive");
    Thread thread = reflectThread(loop, "loopThread");
    assertNotNull(thread, "loopThread set after start()");
    assertTrue(thread.isAlive(), "loopThread alive after start()");

    loop.close();

    assertFalse(thread.isAlive(), "loopThread terminated after close()");
    assertFalse(reflectAtomicBoolean(loop, "running").get(), "running cleared after close()");
    assertEquals(IndexingLoop.LoopState.IDLE, loop.loopState(),
        "state stays at IDLE after close (no spurious RUNNING/PAUSED on shutdown)");
    assertEquals(0L, reflectLong(loop, "indexedSinceCommit"),
        "indexedSinceCommit not bumped by a no-work cycle");
    assertEquals(0, loop.getJournal().pendingTransitionsForTest().size(),
        "journal pending queue empty after a no-work cycle");
  }

  @Test
  @DisplayName(
      "start → idle → close → new instance → start → idle → close: "
          + "second instance restarts cleanly (403 Tier C bug class)")
  void twoSequentialInstancesEachReachIdleAndShutDown() throws Exception {
    IndexingLoop first = newLoopWithEmptyQueue();
    first.start();
    awaitLoopState(first, IndexingLoop.LoopState.IDLE, 5_000L);
    first.close();
    assertFalse(reflectThread(first, "loopThread").isAlive(), "first instance thread terminated");

    // A fresh IndexingLoop on the same kind of mocks should start cleanly too. This is the
    // critical case the 403 Tier C audit missed: the static audit said analyzerRegistry was
    // the only blocker; the runtime found two more (state machine + indexingCoordinator).
    // A within-JVM restart with fresh state catches that bug class.
    IndexingLoop second = newLoopWithEmptyQueue();
    second.start();
    awaitLoopState(second, IndexingLoop.LoopState.IDLE, 5_000L);
    second.close();
    assertFalse(reflectThread(second, "loopThread").isAlive(), "second instance thread terminated");
    assertEquals(IndexingLoop.LoopState.IDLE, second.loopState(),
        "second instance also lands at IDLE after close");
  }

  // ---- helpers ----

  @Test void extractorCloseFailureRetainsNerForRetry() throws Exception {
    var extractor = mock(TimeboxedContentExtractor.class);
    var ner = mock(NerService.class);
    var bindings = new EncoderBindings();
    bindings.bindNerService(ner);
    var loop = new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), mock(JobQueue.class),
        mock(IndexingCoordinator.class), mock(CommitOps.class), mock(DocumentFieldOps.class),
        mock(IndexCountOps.class), () -> null, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
        null, null, null, null, extractor, bindings, null);
    doThrow(new IllegalStateException("OCR child still alive")).doNothing().when(extractor).close();
    assertThrows(IOException.class, loop::close);
    verify(ner, never()).close();
    loop.close();
    verify(extractor, times(2)).close();
    verify(ner).close();
  }

  @Test
  void closeWaitsForLuceneOwnerWithoutInterruptAndFinishesShutdownCommitCleanly(@TempDir Path directory) throws Exception {
    Path file = Files.writeString(directory.resolve("indexing-close-owner.txt"), "content");
    JobQueue queue = mock(JobQueue.class);
    IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);
    CommitOps commitOps = mock(CommitOps.class);
    CountDownLatch writeEntered = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    AtomicBoolean ownerObservedInterrupt = new AtomicBoolean();
    AtomicBoolean shutdownCommitObservedInterrupt = new AtomicBoolean(true);
    JobQueue.IndexJob claim = new JobQueue.IndexJob(file, null);
    AtomicBoolean claimIssued = new AtomicBoolean();
    when(queue.pollPending(anyInt())).thenAnswer(call ->
        claimIssued.compareAndSet(false, true) ? List.of(claim) : List.of());
    doAnswer(call -> {
      writeEntered.countDown();
      awaitUninterruptibly(releaseWrite, ownerObservedInterrupt);
      return null;
    }).when(indexingCoordinator).indexSingle(any());
    doAnswer(call -> {
      shutdownCommitObservedInterrupt.set(Thread.currentThread().isInterrupted());
      return null;
    }).when(commitOps).commitAndTrack(CommitReason.INDEXING_LOOP_SHUTDOWN);

    var loop = newLoop(queue, indexingCoordinator, commitOps,
        new TimeboxedContentExtractor(
            io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
            providerReturning("body"), Duration.ofSeconds(5), null),
        new EncoderBindings(), mock(DocumentFieldOps.class),
        mock(IndexCountOps.class), mock(WorkerSignalBus.class), identityStore());
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    CountDownLatch closeFinished = new CountDownLatch(1);
    Thread owner = null;
    Thread closer = null;
    boolean resourcesClosed = false;

    try {
      loop.start();
      assertTrue(writeEntered.await(5, TimeUnit.SECONDS), "owner reached the Lucene write seam");
      owner = reflectThread(loop, "loopThread");
      closer = new Thread(() -> {
        try {
          loop.close();
        } catch (IOException | RuntimeException failure) {
          closeFailure.set(failure);
        } finally {
          closeFinished.countDown();
        }
      }, "indexing-loop-close-test");
      closer.start();

      awaitRunningFlag(loop, false, 2_000L);
      assertTrue(owner.isAlive(), "close must wait for the in-flight owner");
      assertFalse(owner.isInterrupted(), "close must not interrupt the Lucene-owning thread");
      assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS),
          "close cannot release resources while the owner is still writing");

      releaseWrite.countDown();
      assertTrue(closeFinished.await(5, TimeUnit.SECONDS), "close completes after the write is released");
      closer.join(1_000L);
      owner.join(1_000L);
      assertFalse(closer.isAlive());
      assertNull(closeFailure.get(), "cooperative owner exit lets close complete");
      assertFalse(owner.isAlive());
      assertFalse(ownerObservedInterrupt.get());
      assertFalse(shutdownCommitObservedInterrupt.get(),
          "the final Lucene commit runs with a clear owner interrupt flag");
      verify(commitOps).commitAndTrack(CommitReason.INDEXING_LOOP_SHUTDOWN);
      verify(indexingCoordinator).indexSingle(any());
      resourcesClosed = true;
    } finally {
      releaseWrite.countDown();
      if (closer != null) closer.join(6_000L);
      if (owner != null) owner.join(6_000L);
      if (!resourcesClosed && (owner == null || !owner.isAlive())) {
        loop.close();
      }
    }
  }

  @Test
  void interruptedCloserRetainsExtractorAndNerUntilOwnerExitsThenRetryClosesThem() throws Exception {
    CountDownLatch pollEntered = new CountDownLatch(1);
    CountDownLatch releasePoll = new CountDownLatch(1);
    AtomicBoolean ownerObservedInterrupt = new AtomicBoolean();
    JobQueue queue = mock(JobQueue.class);
    when(queue.pollPending(anyInt())).thenAnswer(call -> {
      pollEntered.countDown();
      awaitUninterruptibly(releasePoll, ownerObservedInterrupt);
      return List.of();
    });
    var extractor = mock(TimeboxedContentExtractor.class);
    var ner = mock(NerService.class);
    var bindings = new EncoderBindings();
    bindings.bindNerService(ner);
    var loop = newLoop(queue, mock(IndexingCoordinator.class), mock(CommitOps.class), extractor,
        bindings, mock(DocumentFieldOps.class), mock(IndexCountOps.class),
        mock(WorkerSignalBus.class), DocumentIdentityStore.UNAVAILABLE);
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    AtomicBoolean closerInterruptRestored = new AtomicBoolean();
    CountDownLatch closeFinished = new CountDownLatch(1);
    Thread owner = null;
    Thread closer = null;
    boolean resourcesClosed = false;

    try {
      loop.start();
      assertTrue(pollEntered.await(5, TimeUnit.SECONDS), "owner entered its in-flight queue poll");
      owner = reflectThread(loop, "loopThread");
      closer = new Thread(() -> {
        try {
          loop.close();
        } catch (IOException | RuntimeException failure) {
          closeFailure.set(failure);
          closerInterruptRestored.set(Thread.currentThread().isInterrupted());
        } finally {
          closeFinished.countDown();
        }
      }, "indexing-loop-interrupted-close-test");
      closer.start();
      awaitRunningFlag(loop, false, 2_000L);
      assertTrue(owner.isAlive(), "close is waiting on the owner before the closer is interrupted");

      closer.interrupt();
      assertTrue(closeFinished.await(2, TimeUnit.SECONDS), "interrupted join returns promptly");
      closer.join(1_000L);
      assertInstanceOf(IOException.class, closeFailure.get());
      assertTrue(closerInterruptRestored.get(), "close restores the interrupted caller's status");
      verify(extractor, never()).close();
      verify(ner, never()).close();
      assertTrue(owner.isAlive(), "owner remains live until its own work is released");
      assertFalse(ownerObservedInterrupt.get(), "interrupted close caller must not interrupt owner");

      releasePoll.countDown();
      owner.join(5_000L);
      assertFalse(owner.isAlive(), "owner exits cooperatively after the blocked poll returns");
      assertFalse(ownerObservedInterrupt.get());

      loop.close();
      resourcesClosed = true;
      verify(extractor).close();
      verify(ner).close();
    } finally {
      releasePoll.countDown();
      if (closer != null) closer.join(2_000L);
      if (owner != null) owner.join(5_000L);
      if (!resourcesClosed && (owner == null || !owner.isAlive())) {
        loop.close();
      }
    }
  }

  @Test
  void timedOutCloserRetainsExtractorAndNerUntilOwnerExitsThenRetryClosesThem() throws Exception {
    CountDownLatch pollEntered = new CountDownLatch(1);
    CountDownLatch releasePoll = new CountDownLatch(1);
    AtomicBoolean ownerObservedInterrupt = new AtomicBoolean();
    JobQueue queue = mock(JobQueue.class);
    when(queue.pollPending(anyInt())).thenAnswer(call -> {
      pollEntered.countDown();
      awaitUninterruptibly(releasePoll, ownerObservedInterrupt);
      return List.of();
    });
    var extractor = mock(TimeboxedContentExtractor.class);
    var ner = mock(NerService.class);
    var bindings = new EncoderBindings();
    bindings.bindNerService(ner);
    var loop = newLoop(queue, mock(IndexingCoordinator.class), mock(CommitOps.class), extractor,
        bindings, mock(DocumentFieldOps.class), mock(IndexCountOps.class),
        mock(WorkerSignalBus.class), DocumentIdentityStore.UNAVAILABLE);
    Thread owner = null;
    boolean resourcesClosed = false;

    try {
      loop.start();
      assertTrue(pollEntered.await(5, TimeUnit.SECONDS), "owner entered its in-flight queue poll");
      owner = reflectThread(loop, "loopThread");

      IOException timeout = assertThrows(IOException.class, loop::close);
      assertTrue(timeout.getMessage().contains("resources retained"));
      assertTrue(owner.isAlive(), "timed-out close leaves the owner running");
      assertFalse(owner.isInterrupted(), "timeout does not interrupt the Lucene-owning thread");
      assertFalse(ownerObservedInterrupt.get());
      verify(extractor, never()).close();
      verify(ner, never()).close();

      releasePoll.countDown();
      owner.join(5_000L);
      assertFalse(owner.isAlive(), "owner exits after its blocked work is released");
      assertFalse(ownerObservedInterrupt.get());

      loop.close();
      resourcesClosed = true;
      verify(extractor).close();
      verify(ner).close();
    } finally {
      releasePoll.countDown();
      if (owner != null) owner.join(5_000L);
      if (!resourcesClosed && (owner == null || !owner.isAlive())) {
        loop.close();
      }
    }
  }

  private IndexingLoop newLoop(
      JobQueue queue,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      TimeboxedContentExtractor extractor,
      EncoderBindings bindings,
      DocumentFieldOps documentFieldOps,
      IndexCountOps indexCountOps,
      WorkerSignalBus signalBus,
      DocumentIdentityStore identityStore) {
    return new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue, indexingCoordinator,
        commitOps, documentFieldOps, indexCountOps, () -> null, signalBus, IndexingPacing.unthrottled(),
        null, null, null, null, extractor, bindings,
        new IndexingLoopOptions(false, null, identityStore, null, null, null));
  }

  private static DocumentIdentityStore identityStore() {
    var store = mock(DocumentIdentityStore.class);
    when(store.resolve(anyString(), anyLong())).thenAnswer(call -> {
      String hash = call.getArgument(0);
      long now = call.getArgument(1);
      return new DocumentIdentityStore.Identity(hash, "test-uid-" + hash, now, now);
    });
    return store;
  }

  private static ContentExtractorProvider providerReturning(String content) {
    return new ContentExtractorProvider() {
      @Override
      public ExtractionResult extract(Path file) {
        return new ExtractionResult(content, null, "text/plain");
      }

      @Override
      public String detectMimeType(Path file) {
        return "text/plain";
      }
    };
  }

  private static void awaitRunningFlag(IndexingLoop loop, boolean expected, long maxWaitMs)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
    while (System.nanoTime() < deadline) {
      if (reflectAtomicBoolean(loop, "running").get() == expected) return;
      Thread.sleep(10L);
    }
    fail("running flag did not become " + expected);
  }

  private static void awaitUninterruptibly(CountDownLatch latch, AtomicBoolean interrupted) {
    boolean released = false;
    while (!released) {
      try {
        released = latch.await(25L, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        interrupted.set(true);
      }
    }
  }

  private IndexingLoop newLoopWithEmptyQueue() {
    JobQueue queue = mock(JobQueue.class);
    // Mockito strict mode: only stub what the loop actually reads (pollPending). The other
    // queue methods aren't on the no-work path so leaving them unstubbed avoids
    // UnnecessaryStubbingException.
    lenient().when(queue.pollPending(anyInt())).thenReturn(List.of());
    lenient().when(queue.queueDepth()).thenReturn(0L);
    IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
    CommitOps commitOps = mock(CommitOps.class);
    DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    WorkerSignalBus signalBus = mock(WorkerSignalBus.class);
    // signalBus.isMainGpuActive defaults to false (Mockito boolean default).
    return new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
        queue,
        coordinator,
        commitOps,
        documentFieldOps,
        indexCountOps,
        () -> null,
        signalBus,
        IndexingPacing.unthrottled(),
        null,
        null,
        null,
        null,
        null,
        null, // W7.2 — default-construct EncoderBindings
        null); // W7.2 followup — default IndexingLoopOptions
  }

  private static void awaitLoopState(
      IndexingLoop loop, IndexingLoop.LoopState desired, long maxWaitMs) throws Exception {
    long deadline = System.currentTimeMillis() + maxWaitMs;
    while (System.currentTimeMillis() < deadline) {
      if (loop.loopState() == desired) return;
      Thread.sleep(20);
    }
    throw new AssertionError(
        "loop never reached state " + desired + " (last seen: " + loop.loopState() + ")");
  }

  private static AtomicBoolean reflectAtomicBoolean(IndexingLoop loop, String name)
      throws Exception {
    Field f = IndexingLoop.class.getDeclaredField(name);
    f.setAccessible(true);
    return (AtomicBoolean) f.get(loop);
  }

  private static Thread reflectThread(IndexingLoop loop, String name) throws Exception {
    Field f = IndexingLoop.class.getDeclaredField(name);
    f.setAccessible(true);
    return (Thread) f.get(loop);
  }

  private static long reflectLong(IndexingLoop loop, String name) throws Exception {
    Field f = IndexingLoop.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.getLong(loop);
  }
}
