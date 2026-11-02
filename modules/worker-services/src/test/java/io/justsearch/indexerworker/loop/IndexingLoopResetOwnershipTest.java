/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.queue.JobQueue;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class IndexingLoopResetOwnershipTest {
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void concurrentResetsNeverCleanUnderRestartedOwner(boolean failFirst) throws Exception {
    JobQueue queue = mock();
    when(queue.pollPending(anyInt())).thenReturn(List.of());
    IndexingLoop loop;
    try (var metrics = mockStatic(io.justsearch.indexerworker.metrics.OperationalMetrics.class)) {
      metrics.when(io.justsearch.indexerworker.metrics.OperationalMetrics::getInstance)
          .thenReturn(mock(io.justsearch.indexerworker.metrics.OperationalMetrics.class));
      loop = new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
          io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
          mock(IndexingCoordinator.class), mock(CommitOps.class), mock(DocumentFieldOps.class),
          mock(IndexCountOps.class), () -> null, mock(WorkerSignalBus.class));
    }
    var inA = new CountDownLatch(1);
    var releaseA = new CountDownLatch(1);
    var inB = new CountDownLatch(1);
    var releaseB = new CountDownLatch(1);
    var unsafe = new AtomicBoolean();
    var doneA = new CompletableFuture<Void>();
    var doneB = new CompletableFuture<Void>();
    var a = new Thread(() -> {
      try {
        loop.resetForProfiling(() -> {
          inA.countDown(); await(releaseA);
          if (failFirst) throw new IllegalStateException("first cleanup failed");
        });
        doneA.complete(null);
      } catch (Exception e) { doneA.completeExceptionally(e); }
    });
    var b = new Thread(() -> {
      try {
        loop.resetForProfiling(() -> {
          inB.countDown(); await(releaseB); unsafe.set(loop.isRunning());
        });
        doneB.complete(null);
      } catch (Exception e) { doneB.completeExceptionally(e); }
    });
    try {
      a.start();
      assertTrue(inA.await(10, TimeUnit.SECONDS));
      b.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (inB.getCount() != 0 && b.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
      assertTrue(inB.getCount() == 0 || b.getState() == Thread.State.BLOCKED);
      releaseA.countDown();
      if (failFirst) assertThrows(java.util.concurrent.ExecutionException.class, () -> doneA.get(15, TimeUnit.SECONDS));
      else doneA.get(15, TimeUnit.SECONDS);
      releaseB.countDown();
      doneB.get(15, TimeUnit.SECONDS);
      assertFalse(unsafe.get(), "the second reset must stop the first reset's restarted loop before cleanup");
      assertTrue(loop.isRunning());
    } finally {
      releaseA.countDown(); releaseB.countDown();
      a.join(15_000); b.join(15_000); loop.close();
    }
  }

  private static void await(CountDownLatch latch) {
    try { assertTrue(latch.await(20, TimeUnit.SECONDS)); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
  }
}
