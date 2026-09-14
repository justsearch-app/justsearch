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
 * Tempdoc 588 F-1 — the indexing loop must not die <em>silently</em>.
 *
 * <p>Before this fix the only per-iteration handler in {@link IndexingLoop#runLoop} was {@code
 * catch (Exception)}. An {@link Error} from a batch (a plugin's {@code NoClassDefFoundError}, an
 * {@code AssertionError}, an {@code OutOfMemoryError}) is not an {@code Exception}, so it propagated
 * out of {@code runLoop}, the {@code indexing-loop} thread died, and yet {@code running} stayed
 * {@code true} and {@code loopState()} kept reporting {@code RUNNING} — indexing was permanently
 * dead while the worker advertised itself as healthy.
 *
 * <p>These tests run {@link IndexingLoop#runLoop} on a real thread (the same harness shape as {@link
 * IndexingLoopRestartTest}) and inject the failure through {@link JobQueue#pollPending}, which
 * stands in for any {@code Error} in the iteration body (extraction / embedding / build). They pin
 * two properties: a <em>recoverable</em> {@code Error} is survived (the loop keeps indexing), and a
 * <em>fatal</em> {@link VirtualMachineError} stops the loop but is observable ({@link
 * IndexingLoop#isRunning()} reports {@code false}, not a stale {@code RUNNING}).
 */
@ExtendWith(MockitoExtension.class)
final class IndexingLoopErrorResilienceTest {

  @Test
  @DisplayName("recoverable Error in a batch: loop survives, keeps indexing, reaches IDLE")
  void recoverableErrorInBatch_loopSurvivesAndReachesIdle() throws Exception {
    JobQueue queue = mock(JobQueue.class);
    // First poll throws a (non-VM) Error; subsequent polls return empty. Pre-fix the thread would
    // die on the first throw and never reach IDLE.
    lenient()
        .when(queue.pollPending(anyInt()))
        .thenThrow(new Error("simulated recoverable error in batch"))
        .thenReturn(List.of());
    lenient().when(queue.queueDepth()).thenReturn(0L);

    IndexingLoop loop = newLoop(queue);
    loop.start();
    try {
      // Reaching IDLE proves the loop recovered from the Error and continued polling.
      awaitLoopState(loop, IndexingLoop.LoopState.IDLE, 5_000L);
      verify(queue, timeout(5_000L).atLeast(2)).pollPending(anyInt());

      Thread thread = reflectThread(loop, "loopThread");
      assertNotNull(thread, "loopThread set after start()");
      assertTrue(thread.isAlive(), "loop thread survived the recoverable Error");
      assertTrue(loop.isRunning(), "isRunning() true after recovering from a non-fatal Error");
      assertFalse(loop.hasFailed(), "recoverable errors must not publish terminal failure");
    } finally {
      loop.close();
    }
  }

  @Test
  @DisplayName("fatal VirtualMachineError: loop stops AND isRunning() reports false (honest liveness)")
  void fatalVmError_stopsLoopAndReportsNotRunning() throws Exception {
    JobQueue queue = mock(JobQueue.class);
    // StackOverflowError is a VirtualMachineError — the loop must stop, not swallow-and-spin.
    lenient()
        .when(queue.pollPending(anyInt()))
        .thenThrow(new StackOverflowError("simulated fatal VM error"));
    lenient().when(queue.queueDepth()).thenReturn(0L);

    IndexingLoop loop = newLoop(queue);
    loop.start();
    try {
      Thread thread = reflectThread(loop, "loopThread");
      assertNotNull(thread, "loopThread set after start()");
      awaitThreadDeath(thread, 5_000L);

      assertFalse(thread.isAlive(), "loop thread terminated on the fatal VM error");
      // Discriminating assertion (test-precision): the fix's value on the fatal path is that it
      // flips the raw `running` flag false (via the VirtualMachineError catch AND the uncaught
      // handler). PRE-FIX `running` stays `true` after the thread dies — which also means a later
      // start() can never restart the loop, since start() does compareAndSet(false, true). Asserting
      // on isRunning() alone would pass even pre-fix (it ANDs loopThread.isAlive()), so it would not
      // distinguish fixed from unfixed; the raw flag does.
      assertFalse(
          reflectRunning(loop).get(),
          "`running` flag cleared after a fatal loop death (so the loop is honest AND restartable)");
      assertFalse(loop.isRunning(), "isRunning() also reports false after a fatal loop death");
      assertTrue(loop.hasFailed(), "fatal event must be available to status consumers");
      assertEquals(IndexingLoop.LoopState.FAILED, loop.loopState());
      assertEquals("FAILED", loop.getCurrentState(), "health supplier must not report stale RUNNING");
    } finally {
      loop.close();
    }
  }

  // ---- helpers (mirror IndexingLoopRestartTest's real-thread harness) ----

  @Test
  void intentionalQuiescenceAndCloseAreNotFatalFailure() throws Exception {
    JobQueue queue = mock(JobQueue.class);
    lenient().when(queue.pollPending(anyInt())).thenReturn(List.of());
    IndexingLoop loop = newLoop(queue);
    assertFalse(loop.isRunning());
    assertFalse(loop.hasFailed(), "a not-yet-started loop is not failed");
    loop.start();
    try {
      assertTrue(loop.quiesceForUpgrade(5_000L));
      assertFalse(loop.isRunning());
      assertFalse(loop.hasFailed(), "upgrade quiescence must not become a fatal event");
      loop.resumeAfterUpgradePreparation();
      assertTrue(loop.isRunning());
      assertFalse(loop.hasFailed());
    } finally {
      loop.close();
    }
    assertFalse(loop.isRunning());
    assertFalse(loop.hasFailed(), "ordinary close must not become a fatal event");
  }

  private static IndexingLoop newLoop(JobQueue queue) {
    IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
    CommitOps commitOps = mock(CommitOps.class);
    DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    WorkerSignalBus signalBus = mock(WorkerSignalBus.class);
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
        null, // default-construct EncoderBindings
        null); // default IndexingLoopOptions
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

  private static void awaitThreadDeath(Thread thread, long maxWaitMs) throws Exception {
    long deadline = System.currentTimeMillis() + maxWaitMs;
    while (System.currentTimeMillis() < deadline) {
      if (!thread.isAlive()) return;
      Thread.sleep(20);
    }
    throw new AssertionError("thread '" + thread.getName() + "' never terminated");
  }

  private static Thread reflectThread(IndexingLoop loop, String name) throws Exception {
    Field f = IndexingLoop.class.getDeclaredField(name);
    f.setAccessible(true);
    return (Thread) f.get(loop);
  }

  private static AtomicBoolean reflectRunning(IndexingLoop loop) throws Exception {
    Field f = IndexingLoop.class.getDeclaredField("running");
    f.setAccessible(true);
    return (AtomicBoolean) f.get(loop);
  }
}
