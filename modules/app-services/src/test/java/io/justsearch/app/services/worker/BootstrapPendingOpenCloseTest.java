/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.util.AppInstanceLock;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Close must refuse without releasing resources underneath a still-running physical open. */
final class BootstrapPendingOpenCloseTest {
  @Test
  @Timeout(30)
  void heldOpenRefusesCloseAndRetainsItsOwnerUntilActualCompletion(@TempDir Path dir)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var host = mock(WorkerHost.class);
    var client = mock(KnowledgeClient.class);
    when(host.start(any(), any())).thenAnswer(ignored -> {
      entered.countDown();
      assertTrue(release.await(20, TimeUnit.SECONDS));
      return client;
    });
    var config = new KnowledgeServerConfig(false, dir, dir, dir,
        5_000L, 1_000L, 3, 1_000L, 1_000L, 300_000L, 100, 0L, 0);
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(config, host);
        var tasks = Executors.newSingleThreadExecutor()) {
      var bootstrap = fixture.bootstrap();
      var opening = tasks.submit(() -> { bootstrap.start(); return null; });
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var sampled = java.util.concurrent.CompletableFuture.supplyAsync(bootstrap::tryCheckHealth);
        assertTrue(sampled.get(1, TimeUnit.SECONDS).isEmpty(),
            "health observation must not queue behind a physical open");
        verify(client, never()).isHealthy(any());
        assertEquals(ShutdownOutcome.FAILED, bootstrap.closeForUpgrade());
        assertFalse(opening.isDone(), "a close refusal must not pretend the open has exited");
        assertTrue(AppInstanceLock.isHeldByThisJvm(dir), "the pending owner still owns its store");
        verify(host, never()).close();
        verify(client, never()).close();
      } finally {
        release.countDown();
      }
      opening.get(5, TimeUnit.SECONDS);
      assertEquals(java.util.Optional.of(false), bootstrap.tryCheckHealth());
      assertEquals(ShutdownOutcome.GRACEFUL, bootstrap.closeForUpgrade());
      assertFalse(AppInstanceLock.isHeldByThisJvm(dir));
      verify(host).close();
      verify(client).close();
    }
  }

  @Test
  @Timeout(30)
  void retryBackoffRetainsInitializationOwnerUntilInterrupted(@TempDir Path dir)
      throws Exception {
    var hostEntered = new CountDownLatch(1);
    var host = mock(WorkerHost.class);
    when(host.start(any(), any())).thenAnswer(ignored -> {
      hostEntered.countDown();
      throw new IOException("first bootstrap attempt failed");
    });
    var config = new KnowledgeServerConfig(false, dir, dir, dir,
        5_000L, 1_000L, 3, 1_000L, 1_000L, 300_000L, 100, 0L, 0);
    Thread retryOwner = null;
    ExecutorService closer = Executors.newSingleThreadExecutor();
    var threadFailure = new AtomicReference<Throwable>();
    var interruptedOnExit = new AtomicBoolean();
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(config, host)) {
      var bootstrap = fixture.bootstrap();
      retryOwner = new Thread(() -> {
        try {
          bootstrap.startWithRetry(2, 30_000L);
          threadFailure.set(new AssertionError("retry owner unexpectedly completed"));
        } catch (Throwable failure) {
          threadFailure.set(failure);
        } finally {
          interruptedOnExit.set(Thread.currentThread().isInterrupted());
        }
      }, "bootstrap-retry-backoff-test");
      retryOwner.start();

      assertTrue(hostEntered.await(5, TimeUnit.SECONDS));
      awaitTimedWaiting(retryOwner);

      var closeAttempt = closer.submit(bootstrap::closeForUpgrade);
      assertEquals(ShutdownOutcome.FAILED, closeAttempt.get(8, TimeUnit.SECONDS));
      assertTrue(
          retryOwner.isAlive(), "the retry owner must still own initialization after refusal");
      verify(host, times(1)).start(any(), any());

      retryOwner.interrupt();
      retryOwner.join(5_000L);
      assertFalse(retryOwner.isAlive(), "the interrupted retry owner did not retire");
      Throwable failure = threadFailure.get();
      assertTrue(failure instanceof IOException, "the original startup failure must be retained");
      assertEquals("first bootstrap attempt failed", failure.getMessage());
      assertTrue(interruptedOnExit.get(), "the retry owner must record the interrupted backoff");
      verify(host, times(1)).start(any(), any());

      assertEquals(ShutdownOutcome.GRACEFUL, bootstrap.closeForUpgrade());
      assertFalse(AppInstanceLock.isHeldByThisJvm(dir));
    } finally {
      if (retryOwner != null && retryOwner.isAlive()) {
        retryOwner.interrupt();
        retryOwner.join(5_000L);
      }
      if (retryOwner != null && retryOwner.isAlive()) {
        fail("retry owner did not terminate during bounded cleanup");
      }
      closer.shutdownNow();
      assertTrue(closer.awaitTermination(5, TimeUnit.SECONDS),
          "close executor did not terminate during bounded cleanup");
    }
  }

  private static void awaitTimedWaiting(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == Thread.State.TIMED_WAITING) {
        boolean inRetryLoop = false;
        boolean sleeping = false;
        for (StackTraceElement frame : thread.getStackTrace()) {
          inRetryLoop |= frame.getClassName().equals(KnowledgeServerBootstrap.class.getName())
              && frame.getMethodName().equals("startWithRetryLocked");
          sleeping |= frame.getClassName().equals(Thread.class.getName())
              && frame.getMethodName().equals("sleep");
        }
        if (inRetryLoop && sleeping) return;
      }
      if (!thread.isAlive()) fail("retry owner exited before entering backoff");
      Thread.yield();
    }
    fail("retry owner did not enter timed backoff");
  }
}
