/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EngineFuturesTest {
  @Test
  void timeoutCancelsQueuedSupplierBeforeSynchronousDependentsCanBlock() throws Exception {
    var workerEntered = new CountDownLatch(1);
    var releaseWorker = new CountDownLatch(1);
    var dependentEntered = new CountDownLatch(1);
    var releaseDependent = new CountDownLatch(1);
    var invocations = new AtomicInteger();
    try (var executor = Executors.newSingleThreadExecutor()) {
      try {
        executor.execute(() -> {
          workerEntered.countDown();
          await(releaseWorker);
        });
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
        var result = EngineFutures.supplyAsync(invocations::incrementAndGet, executor);
        var dependent = result.whenComplete((value, error) -> {
          dependentEntered.countDown();
          await(releaseDependent);
        });
        result.orTimeout(20, TimeUnit.MILLISECONDS);
        assertTrue(dependentEntered.await(5, TimeUnit.SECONDS));
        releaseWorker.countDown();
        executor.submit(() -> null).get(5, TimeUnit.SECONDS);
        assertEquals(0, invocations.get());
        assertInstanceOf(TimeoutException.class, assertThrows(CompletionException.class, result::join).getCause());
        releaseDependent.countDown();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> dependent.get(5, TimeUnit.SECONDS));
      } finally {
        releaseWorker.countDown();
        releaseDependent.countDown();
      }
    }
  }

  @Test
  void timeoutInterruptsRunningSupplierButDoesNotPretendItHasExited() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var releaseCalls = new AtomicInteger();
    try (var executor = Executors.newSingleThreadExecutor()) {
      try {
        var result = EngineFutures.supplyAsync(() -> {
          entered.countDown();
          try { release.await(); }
          catch (InterruptedException expected) { interrupted.countDown(); await(release); }
          return 1;
        }, executor, () -> { releaseCalls.incrementAndGet(); exited.countDown(); });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        result.orTimeout(20, TimeUnit.MILLISECONDS);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(1, exited.getCount());
        release.countDown();
        assertTrue(exited.await(5, TimeUnit.SECONDS));
        assertEquals(1, releaseCalls.get());
      } finally { release.countDown(); }
    }
  }

  @Test
  void queuedTimeoutPublishesEvenWhenExitCleanupThrowsAndReleasesOnlyOnce() throws Exception {
    var queued = new java.util.concurrent.atomic.AtomicReference<Runnable>();
    var releases = new AtomicInteger();
    var invocations = new AtomicInteger();
    var cleanupFailure = new IllegalStateException("cleanup failed");
    var result = EngineFutures.supplyAsync(invocations::incrementAndGet, queued::set, () -> {
      releases.incrementAndGet();
      throw cleanupFailure;
    });
    result.orTimeout(20, TimeUnit.MILLISECONDS);
    var failure = assertThrows(java.util.concurrent.ExecutionException.class,
        () -> result.get(5, TimeUnit.SECONDS));
    assertInstanceOf(TimeoutException.class, failure.getCause());
    assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getCause().getSuppressed());
    queued.get().run();
    assertEquals(0, invocations.get());
    assertEquals(1, releases.get());
  }

  @Test
  void rejectionAndCancellationBeforeStartReleaseExactlyOnce() {
    var queued = new java.util.concurrent.atomic.AtomicReference<Runnable>();
    var releases = new AtomicInteger();
    var result = EngineFutures.supplyAsync(() -> 1, queued::set, releases::incrementAndGet);
    assertTrue(result.cancel(true));
    queued.get().run();
    result.cancel(true);
    assertEquals(1, releases.get());
    assertThrows(java.util.concurrent.RejectedExecutionException.class,
        () -> EngineFutures.supplyAsync(() -> 1,
            command -> { throw new java.util.concurrent.RejectedExecutionException("full"); },
            releases::incrementAndGet));
    assertEquals(2, releases.get());
  }

  @Test
  void optionalFallbackPreservesExactWrappedExecutorRefusal() {
    var refusal = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "test", 3);
    assertSame(refusal, assertThrows(EngineExecutorRejectedException.class,
        () -> EngineFutures.rethrowExecutorRefusal(
            new CompletionException(new java.util.concurrent.ExecutionException(refusal)))));
    assertDoesNotThrow(() -> EngineFutures.rethrowExecutorRefusal(new IllegalStateException("ordinary failure")));
  }

  @Test
  void runningSuccessCleanupFailureDoesNotKillExecutorWorker() throws Exception {
    assertRunningCleanupFailureIsContained(false);
  }

  @Test
  void runningTimeoutCleanupFailureDoesNotKillExecutorWorker() throws Exception {
    assertRunningCleanupFailureIsContained(true);
  }

  private static void assertRunningCleanupFailureIsContained(boolean timeout) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var cleanupCalls = new AtomicInteger();
    var threadCount = new AtomicInteger();
    var escaped = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    try (var executor = Executors.newSingleThreadExecutor(task -> {
      threadCount.incrementAndGet();
      Thread thread = new Thread(task, "owned-future-cleanup-test");
      thread.setUncaughtExceptionHandler((ignored, failure) -> escaped.set(failure));
      return thread;
    })) {
      try {
        var result = EngineFutures.supplyAsync(() -> {
          entered.countDown();
          try { release.await(); }
          catch (InterruptedException expected) { interrupted.countDown(); await(release); }
          return 42;
        }, executor, () -> {
          cleanupCalls.incrementAndGet();
          throw new IllegalStateException("running cleanup failure");
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        if (timeout) {
          result.orTimeout(20, TimeUnit.MILLISECONDS);
          assertTrue(interrupted.await(5, TimeUnit.SECONDS));
          assertInstanceOf(TimeoutException.class,
              assertThrows(java.util.concurrent.ExecutionException.class,
                  () -> result.get(5, TimeUnit.SECONDS)).getCause());
          assertEquals(0, cleanupCalls.get(), "timeout is not actual exit");
        }
        release.countDown();
        if (!timeout) assertEquals(42, result.get(5, TimeUnit.SECONDS));
        executor.submit(() -> null).get(5, TimeUnit.SECONDS);
        assertEquals(1, cleanupCalls.get());
        assertEquals(1, threadCount.get(), "cleanup must not kill and replace the worker");
        assertNull(escaped.get());
      } finally { release.countDown(); }
    }
  }

  private static void await(CountDownLatch latch) {
    try { latch.await(); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
  }
}
