/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EngineTaskGroupTest {
  @Test
  void emptyGroupReleasesInitialLeaseExactlyOnce() {
    var releases = new AtomicInteger();
    var group = EngineTaskGroup.open(Executors::newSingleThreadExecutor,
        () -> releases::incrementAndGet);

    group.close();
    group.close();

    assertEquals(1, releases.get());
  }

  @Test
  void oneAcceptedTaskReleasesAfterActualExit() throws Exception {
    var released = new CountDownLatch(1);
    var taskEntered = new CountDownLatch(1);
    var finishTask = new CountDownLatch(1);
    var group = EngineTaskGroup.open(Executors::newSingleThreadExecutor,
        () -> released::countDown);
    try {
      var future = group.submit(() -> {
        taskEntered.countDown();
        awaitIgnoringInterrupt(finishTask);
        return 7;
      });
      assertTrue(taskEntered.await(5, TimeUnit.SECONDS));
      finishTask.countDown();
      assertEquals(7, future.get(5, TimeUnit.SECONDS));
      assertEquals(1L, released.getCount());
    } finally {
      finishTask.countDown();
      group.close();
    }
    assertTrue(released.await(5, TimeUnit.SECONDS));
  }

  @Test
  void multipleAcceptedTasksReleaseAfterEveryActualExit() throws Exception {
    var taskCount = 6;
    var released = new CountDownLatch(1);
    var allStarted = new CountDownLatch(taskCount);
    var finishTasks = java.util.stream.IntStream.range(0, taskCount)
        .mapToObj(index -> new CountDownLatch(1)).toList();
    var actualExits = java.util.stream.IntStream.range(0, taskCount)
        .mapToObj(index -> new CountDownLatch(1)).toList();
    var group = EngineTaskGroup.open(() -> Executors.newFixedThreadPool(taskCount),
        () -> released::countDown);
    try {
      var futures = java.util.stream.IntStream.range(0, taskCount)
          .mapToObj(index -> group.submit(() -> {
            allStarted.countDown();
            try {
              awaitIgnoringInterrupt(finishTasks.get(index));
              return index;
            } finally {
              actualExits.get(index).countDown();
            }
          }))
          .toList();
      assertTrue(allStarted.await(5, TimeUnit.SECONDS));
      for (var index = 0; index < taskCount - 1; index++) {
        finishTasks.get(index).countDown();
        assertTrue(actualExits.get(index).await(5, TimeUnit.SECONDS));
        assertEquals(index, futures.get(index).get(5, TimeUnit.SECONDS));
      }
      group.close();
      assertEquals(1L, released.getCount());
      finishTasks.get(taskCount - 1).countDown();
      assertTrue(actualExits.get(taskCount - 1).await(5, TimeUnit.SECONDS));
    } finally {
      finishTasks.forEach(CountDownLatch::countDown);
      group.close();
    }
    assertTrue(released.await(5, TimeUnit.SECONDS));
  }

  @Test
  void submittingAfterCloseIsProgrammerMisuse() {
    var group = EngineTaskGroup.open(Executors::newSingleThreadExecutor, EngineTaskLifetime.NONE);
    group.close();

    assertThrows(IllegalStateException.class,
        () -> group.submit(() -> 1));
  }

  @Test
  void executorRejectionRemainsSynchronousAndPreservesIdentity() throws Exception {
    var refusal = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "test", 1);
    var executor = new RejectAfterFirstExecutor(refusal);
    var released = new CountDownLatch(1);
    var taskEntered = new CountDownLatch(1);
    var finishTask = new CountDownLatch(1);
    var group = EngineTaskGroup.open(() -> executor, () -> released::countDown);
    try {
      group.submit(() -> {
        taskEntered.countDown();
        awaitIgnoringInterrupt(finishTask);
        return 1;
      });
      assertTrue(taskEntered.await(5, TimeUnit.SECONDS));

      var thrown = assertThrows(EngineExecutorRejectedException.class,
          () -> group.submit(() -> 2));

      assertSame(refusal, thrown);
      group.close();
      assertEquals(1L, released.getCount());
      finishTask.countDown();
      assertTrue(released.await(5, TimeUnit.SECONDS));
    } finally {
      finishTask.countDown();
      group.close();
    }
  }

  @Test
  void nullSupplierIsRejectedBeforeChildAdmission() {
    var releases = new AtomicInteger();
    var group = EngineTaskGroup.open(Executors::newSingleThreadExecutor,
        () -> releases::incrementAndGet);
    try {
      assertThrows(NullPointerException.class, () -> group.submit(null));
    } finally {
      group.close();
    }
    assertEquals(1, releases.get());
  }

  @Test
  void closeCancelsQueuedTaskBeforeItCanExecute() throws Exception {
    var workerEntered = new CountDownLatch(1);
    var releaseWorker = new CountDownLatch(1);
    var invocations = new AtomicInteger();
    var group = EngineTaskGroup.open(Executors::newSingleThreadExecutor,
        EngineTaskLifetime.NONE);
    try {
      group.submit(() -> {
        workerEntered.countDown();
        awaitIgnoringInterrupt(releaseWorker);
        return null;
      });
      assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
      var queued = group.submit(() -> {
        invocations.incrementAndGet();
        return 1;
      });

      group.close();

      assertThrows(CancellationException.class, queued::get);
      assertEquals(0, invocations.get());
    } finally {
      releaseWorker.countDown();
      group.close();
    }
  }

  @Test
  void runningTaskIgnoringInterruptRetainsGroupUntilActualExit() throws Exception {
    var taskEntered = new CountDownLatch(1);
    var finishTask = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var group = EngineTaskGroup.open(Executors::newSingleThreadExecutor,
        () -> released::countDown);
    try {
      var future = group.submit(() -> {
        taskEntered.countDown();
        awaitIgnoringInterrupt(finishTask);
        return 1;
      });
      assertTrue(taskEntered.await(5, TimeUnit.SECONDS));

      group.close();

      assertEquals(1L, released.getCount());
      finishTask.countDown();
      assertTrue(released.await(5, TimeUnit.SECONDS));
      assertThrows(CancellationException.class, future::get);
    } finally {
      finishTask.countDown();
      group.close();
    }
  }

  @Test
  void openingFailureReleasesRetainedOwnerAndPreservesFailureIdentity() {
    var releases = new AtomicInteger();
    var refusal = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.INSTANCE_LIMIT, "test", 1);

    var thrown = assertThrows(EngineExecutorRejectedException.class,
        () -> EngineTaskGroup.open(() -> { throw refusal; },
            () -> releases::incrementAndGet));

    assertSame(refusal, thrown);
    assertEquals(1, releases.get());
  }

  private static void awaitIgnoringInterrupt(CountDownLatch latch) {
    var interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static final class RejectAfterFirstExecutor extends AbstractExecutorService {
    private final ExecutorService delegate = Executors.newSingleThreadExecutor();
    private final EngineExecutorRejectedException refusal;
    private final AtomicInteger submissions = new AtomicInteger();

    private RejectAfterFirstExecutor(EngineExecutorRejectedException refusal) {
      this.refusal = refusal;
    }

    @Override
    public void execute(Runnable command) {
      if (submissions.getAndIncrement() == 0) {
        delegate.execute(command);
      } else {
        throw refusal;
      }
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }
  }
}
