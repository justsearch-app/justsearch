package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
