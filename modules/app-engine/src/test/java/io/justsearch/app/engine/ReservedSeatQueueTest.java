/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(60)
class ReservedSeatQueueTest {
  enum Removal { PURGE, REMOVE, REMOVE_IF, CLEAR, DRAIN, DRAIN_LIMITED, POLL, TIMED_POLL,
    TAKE, ITERATOR, REMOVE_ALL, RETAIN_ALL, REMOVE_HEAD, POOL_REMOVE }

  private static DefaultEngineExecutorRegistry registry() {
    var policy = new HashMap<String, Integer>();
    String[] keys = {"perContextLimit", "aggregateLimit", "retryAfterSeconds", "foregroundThreads",
        "foregroundQueue", "backgroundThreads", "backgroundQueue", "timerRegistrations", "directMemoryMiB"};
    int[] values = {32, 64, 1, 8, 8, 8, 8, 64, 1};
    for (int i = 0; i < keys.length; i++) policy.put(keys[i], values[i]);
    return new DefaultEngineExecutorRegistry(
        new EngineResourcePolicy(policy, new RetainedStateBudget()), Duration.ofSeconds(2));
  }

  private static ThreadPoolExecutor pool(DefaultEngineExecutorRegistry registry, int threads, int waiting) {
    return (ThreadPoolExecutor) registry.register(new EngineExecutorSpec("seats",
        EngineExecutorSpec.Kind.FOREGROUND, EngineExecutorSpec.Mode.PLATFORM, threads, waiting, 1))
        .openWithReservedSeats(Thread.ofPlatform().daemon().factory());
  }

  private static Runnable hold(CountDownLatch entered, CountDownLatch release) {
    return () -> {
      entered.countDown();
      try { release.await(); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    };
  }

  private static void idle(ThreadPoolExecutor pool) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (pool.getActiveCount() != 0 || !pool.getQueue().isEmpty()) {
      assertTrue(System.nanoTime() < deadline, "pool did not settle");
      Thread.sleep(1);
    }
  }

  private static void remove(ThreadPoolExecutor pool, Runnable task, Removal removal) throws Exception {
    var queue = pool.getQueue();
    switch (removal) {
      case PURGE -> pool.purge();
      case REMOVE -> assertTrue(queue.remove(task));
      case REMOVE_IF -> assertTrue(queue.removeIf(t -> t == task));
      case CLEAR -> queue.clear();
      case DRAIN -> assertEquals(1, queue.drainTo(new ArrayList<>()));
      case DRAIN_LIMITED -> assertEquals(1, queue.drainTo(new ArrayList<>(), 1));
      case POLL -> assertSame(task, queue.poll());
      case TIMED_POLL -> assertSame(task, queue.poll(1, TimeUnit.SECONDS));
      case TAKE -> assertSame(task, queue.take());
      case ITERATOR -> { var iterator = queue.iterator(); assertSame(task, iterator.next()); iterator.remove(); }
      case REMOVE_ALL -> assertTrue(queue.removeAll(List.of(task)));
      case RETAIN_ALL -> assertTrue(queue.retainAll(List.of()));
      case REMOVE_HEAD -> assertSame(task, queue.remove());
      case POOL_REMOVE -> assertTrue(pool.remove(task));
    }
    assertTrue(queue.isEmpty());
  }

  @ParameterizedTest
  @EnumSource(Removal.class)
  void everyQueueRemovalReturnsCapacityImmediatelyAndAfterSettling(Removal removal) throws Exception {
    try (var registry = registry()) {
      var pool = pool(registry, 1, 1);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var ran = new AtomicInteger();
      try {
        pool.execute(hold(entered, release));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var queued = new FutureTask<>(ran::incrementAndGet);
        pool.execute(queued);
        assertTrue(queued.cancel(false));
        remove(pool, queued, removal);
        // This assertion fails on the old pool: an empty queue still consumes its only waiting seat.
        assertDoesNotThrow(() -> pool.execute(() -> {}), "queue removal lost a seat: " + removal);
        release.countDown();
        idle(pool);
        assertEquals(0, ran.get());
        var againEntered = new CountDownLatch(1);
        var againRelease = new CountDownLatch(1);
        try {
          pool.execute(hold(againEntered, againRelease));
          assertTrue(againEntered.await(2, TimeUnit.SECONDS));
          assertDoesNotThrow(() -> pool.execute(() -> {}), "settled pool lost capacity");
          assertEquals(EngineExecutorRejectedException.Reason.QUEUE_LIMIT,
              assertThrows(EngineExecutorRejectedException.class, () -> pool.execute(() -> {})).reason());
        } finally { againRelease.countDown(); }
      } finally { release.countDown(); }
    }
  }

  @Test
  void concurrentArrivalCancellationAndRemovalNeverOverAdmitsAndRestoresFullCapacity() throws Exception {
    try (var registry = registry()) {
      var pool = pool(registry, 4, 4);
      pool.setThreadFactory(Thread.ofPlatform().daemon().factory());
      pool.prestartAllCoreThreads();
      for (int round = 0; round < 3000; round++) {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        var arrivals = new ArrayList<FutureTask<Void>>();
        var tasks = new ArrayList<FutureTask<Void>>();
        for (int i = 0; i < 12; i++) tasks.add(new FutureTask<>(() -> null));
        int seed = round;
        try {
          for (int i = 0; i < 4; i++) {
            pool.execute(hold(entered, release));
            if ((round + i) % 3 == 0) LockSupport.parkNanos(1000);
          }
          assertTrue(entered.await(2, TimeUnit.SECONDS));
          for (int i = 0; i < tasks.size(); i++) {
            var task = tasks.get(i);
            int index = i;
            var arrival = new FutureTask<Void>(() -> {
              start.await();
              LockSupport.parkNanos((seed + index) % 5 * 1000L);
              try { pool.execute(task); }
              catch (EngineExecutorRejectedException e) {
                assertEquals(EngineExecutorRejectedException.Reason.QUEUE_LIMIT, e.reason());
              }
              assertTrue(pool.getQueue().size() <= 4, "over-admission behind four held workers");
              assertTrue(registry.snapshot().registrations().getFirst().queuedTasks() <= 4);
              return null;
            });
            arrivals.add(arrival);
            Thread.ofVirtual().start(arrival);
          }
          var remover = new FutureTask<Void>(() -> {
            start.await();
            var random = new Random(seed);
            for (int i = 0; i < 12; i++) {
              var task = tasks.get(random.nextInt(tasks.size()));
              task.cancel(false);
              switch (random.nextInt(3)) {
                case 0 -> pool.purge();
                case 1 -> pool.getQueue().remove(task);
                default -> pool.getQueue().removeIf(t -> t == task);
              }
              assertTrue(pool.getQueue().size() <= 4, "transient over-admission");
              LockSupport.parkNanos(random.nextInt(5000));
            }
            return null;
          });
          Thread.ofVirtual().start(remover);
          start.countDown();
          for (var arrival : arrivals) assertDoesNotThrow(() -> arrival.get(3, TimeUnit.SECONDS));
          assertDoesNotThrow(() -> remover.get(3, TimeUnit.SECONDS));
          pool.getQueue().clear();
          for (int i = 0; i < 4; i++) {
            assertDoesNotThrow(() -> pool.execute(() -> {}), "removed seats must all be reusable");
          }
          assertThrows(EngineExecutorRejectedException.class, () -> pool.execute(() -> {}));
        } finally { start.countDown(); release.countDown(); }
        idle(pool);
      }
    }
  }

  @Test
  void duplicateRunnableAndStaleIteratorReturnOnlyTheirOwnOccurrence() throws Exception {
    try (var registry = registry()) {
      var pool = pool(registry, 1, 2);
      var release = new CountDownLatch(1);
      var entered = new CountDownLatch(1);
      try {
        pool.execute(hold(entered, release));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Runnable same = () -> {};
        pool.execute(same);
        pool.execute(same);
        var old = pool.getQueue().iterator();
        old.next();
        assertSame(same, pool.getQueue().poll());
        old.remove();
        assertEquals(1, pool.getQueue().size());
        assertDoesNotThrow(() -> pool.execute(same), "stale iterator must not lose an occurrence seat");
        assertThrows(EngineExecutorRejectedException.class, () -> pool.execute(same));
      } finally { release.countDown(); }
    }
  }

  @Test
  void queueRemovalByRunningTaskIsNotAWorkerHandoff() throws Exception {
    try (var registry = registry()) {
      var pool = pool(registry, 1, 1);
      var entered = new CountDownLatch(1);
      var ready = new CountDownLatch(1);
      var finished = new CountDownLatch(1);
      var failure = new FutureTask<Void>(() -> {
        entered.countDown();
        ready.await();
        assertNotNull(pool.getQueue().poll());
        assertDoesNotThrow(() -> pool.execute(() -> {}));
        finished.countDown();
        return null;
      });
      try {
        pool.execute(failure);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        pool.execute(() -> {});
        ready.countDown();
        assertDoesNotThrow(() -> failure.get(2, TimeUnit.SECONDS), "worker queue removal must return its seat");
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        idle(pool);
      } finally { ready.countDown(); }
    }
  }

  @Test
  void failedWorkerCreationAfterBufferingRemovesTheRejectedOccurrence() throws Exception {
    try (var registry = registry()) {
      var pool = pool(registry, 1, 1);
      var attempts = new AtomicInteger();
      pool.setThreadFactory(body -> {
        if (attempts.incrementAndGet() == 1) return null;
        throw new IllegalArgumentException("worker creation failed after offer");
      });
      assertThrows(IllegalArgumentException.class, () -> pool.execute(() -> {}));
      assertTrue(pool.getQueue().isEmpty(), "rejected work must not retain a queue seat");
      pool.setThreadFactory(Thread.ofPlatform().daemon().factory());
      assertEquals(42, pool.submit(() -> 42).get(2, TimeUnit.SECONDS));
      idle(pool);
    }
  }

  @Test
  void directQueueInsertionConsumesCapacityAndRemovalReturnsIt() throws Exception {
    try (var registry = registry()) {
      var pool = pool(registry, 1, 1);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      try {
        pool.execute(hold(entered, release));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Runnable direct = () -> {};
        assertTrue(pool.getQueue().offer(direct));
        assertFalse(pool.getQueue().offer(() -> {}), "direct insertion must obey total seats");
        assertThrows(EngineExecutorRejectedException.class, () -> pool.execute(() -> {}));
        assertTrue(pool.getQueue().remove(direct));
        assertDoesNotThrow(() -> pool.execute(direct));
      } finally { release.countDown(); }
      idle(pool);
    }
  }

  @Test
  void cancelledFutureThatRunsAsANoopReturnsItsSeatOnlyOnCompletion() throws Exception {
    try (var registry = registry()) {
      var pool = pool(registry, 1, 1);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var ran = new AtomicInteger();
      try {
        pool.execute(hold(entered, release));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var cancelled = pool.submit(ran::incrementAndGet);
        assertTrue(cancelled.cancel(false));
        assertThrows(EngineExecutorRejectedException.class, () -> pool.execute(() -> {}));
        release.countDown();
        idle(pool);
        assertEquals(0, ran.get());
        assertEquals(42, pool.submit(() -> 42).get(2, TimeUnit.SECONDS));
      } finally { release.countDown(); }
    }
  }

  @Test
  void shutdownDuringFailedWorkerCreationAfterOfferStillTerminates() throws Exception {
    try (var registry = registry()) {
      var pool = pool(registry, 1, 1);
      var attempts = new AtomicInteger();
      pool.setThreadFactory(body -> {
        if (attempts.incrementAndGet() == 1) return null;
        pool.shutdown();
        throw new IllegalArgumentException("shutdown during worker creation");
      });
      assertThrows(IllegalArgumentException.class, () -> pool.execute(() -> {}));
      assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS), "failed offer must recheck termination");
      assertTrue(pool.getQueue().isEmpty());
      assertEquals(EngineExecutorRejectedException.Reason.CLOSED,
          assertThrows(EngineExecutorRejectedException.class, () -> pool.execute(() -> {})).reason());
    }
  }
}
