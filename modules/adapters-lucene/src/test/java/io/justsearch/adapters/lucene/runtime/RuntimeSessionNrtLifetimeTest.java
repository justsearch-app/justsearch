/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.Test;

final class RuntimeSessionNrtLifetimeTest extends RuntimeTestBase {
  @Test
  void failedNrtCloseTaskCanRetryWithoutLosingItsLiveThread() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var runtime = buildSchemaWithDim(4).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      var session = new LifecycleTestAccessor(runtime).session();
      var snapshot = session.snapshot;
      session.crtrt.close();
      var thread = new ControlledRealTimeReopenThread<IndexSearcher>(
          snapshot.writer(), snapshot.searcherManager(), 1.0, 0.1) {
        @Override public synchronized void close() {
          if (calls.incrementAndGet() == 1) throw new IllegalStateException("injected NRT close failure");
          super.close();
        }
      };
      session.crtrt = thread;
      thread.start();
      var failure = assertThrows(IllegalStateException.class, runtime::close);
      assertEquals("injected NRT close failure", failure.getCause().getMessage());
      assertSame(thread, session.crtrt);
      assertTrue(thread.isAlive());
      assertSame(snapshot, session.snapshot);
      assertTrue(snapshot.writer().isOpen());
      runtime.close();
      assertEquals(2, calls.get());
      assertFalse(thread.isAlive());
      assertFalse(snapshot.writer().isOpen());
    }
  }

  @Test
  void nrtCloseDeadlineRetainsOneAttemptAndWriterUntilRetry() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closeCalls = new java.util.concurrent.atomic.AtomicInteger();
    try (var runtime = buildSchemaWithDim(4).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      var session = new LifecycleTestAccessor(runtime).session();
      var snapshot = session.snapshot;
      session.crtrt.close();
      var held = new ControlledRealTimeReopenThread<IndexSearcher>(
          snapshot.writer(), snapshot.searcherManager(), 1.0, 0.1) {
        @Override public void run() {
          entered.countDown();
          while (release.getCount() != 0) {
            try { release.await(); }
            catch (InterruptedException ignored) { /* exit remains the release boundary */ }
          }
        }
        @Override public synchronized void close() {
          closeCalls.incrementAndGet();
          super.close();
        }
      };
      session.crtrt = held;
      try {
        held.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        long closeStarted = System.nanoTime();
        assertThrows(IllegalStateException.class, runtime::close);
        assertTrue(System.nanoTime() - closeStarted < TimeUnit.SECONDS.toNanos(6),
            "the public close path must bound a live NRT owner");
        assertSame(snapshot, session.snapshot);
        assertTrue(snapshot.writer().isOpen());
        assertSame(held, session.crtrt);
        assertThrows(IllegalStateException.class,
            () -> session.stopNrtUntil(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
        assertEquals(1, closeCalls.get(), "retry must await the original close task");
      } finally {
        release.countDown();
        held.join(2_000);
      }
      runtime.close();
      assertEquals(1, closeCalls.get());
      assertFalse(snapshot.writer().isOpen());
      assertNull(session.crtrt);
    }
  }

  @Test
  void interruptedNrtJoinKeepsWriterAliveUntilThreadActuallyExits() throws Exception {
    var entered = new CountDownLatch(1);
    var closingNrt = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CompletableFuture<Void>();
    var writerOpenOnExit = new AtomicBoolean();
    var interruptRestored = new AtomicBoolean();
    try (var runtime = buildSchemaWithDim(4).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      var session = new LifecycleTestAccessor(runtime).session();
      var snap = session.snapshot;
      session.crtrt.close();
      var heldNrt = new ControlledRealTimeReopenThread<IndexSearcher>(
          snap.writer(), snap.searcherManager(), 1.0, 0.1) {
        @Override public void run() {
          entered.countDown();
          while (release.getCount() != 0) {
            try { release.await(); }
            catch (InterruptedException ignored) { /* actual exit owns Lucene release */ }
          }
          writerOpenOnExit.set(snap.writer().isOpen());
        }
        @Override public synchronized void close() {
          closingNrt.countDown();
          super.close();
        }
      };
      session.crtrt = heldNrt;
      var closer = new Thread(() -> {
        try {
          runtime.close();
          interruptRestored.set(Thread.currentThread().isInterrupted());
          finished.complete(null);
        } catch (Throwable failure) { finished.completeExceptionally(failure); }
      }, "interrupted-nrt-close");
      try {
        heldNrt.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        closer.start();
        assertTrue(closingNrt.await(3, TimeUnit.SECONDS));
        var rejected = new CompletableFuture<Throwable>();
        var lateCaller = new Thread(() -> {
          try {
            session.retainTaskLifetime().run();
            rejected.complete(null);
          } catch (Throwable failure) {
            rejected.complete(failure);
          }
        }, "late-search-admission");
        lateCaller.start();
        assertInstanceOf(IllegalStateException.class, rejected.get(250, TimeUnit.MILLISECONDS),
            "a closing runtime must refuse a new search without waiting for NRT shutdown");
        closer.interrupt();
        assertThrows(TimeoutException.class, () -> finished.get(150, TimeUnit.MILLISECONDS));
        assertTrue(snap.writer().isOpen(), "NRT thread may still use the writer");
        assertSame(heldNrt, session.crtrt, "the live thread must remain owned");
        release.countDown();
        finished.get(3, TimeUnit.SECONDS);
        assertTrue(writerOpenOnExit.get());
        assertFalse(heldNrt.isAlive());
        assertFalse(snap.writer().isOpen());
        assertNull(session.crtrt);
        assertTrue(interruptRestored.get());
      } finally {
        release.countDown();
        heldNrt.join(3000);
        if (closer.getState() != Thread.State.NEW) closer.join(3000);
      }
    }
  }

  @Test
  void closeTimesOutWithoutReleasingAnOutstandingGenerationOwnerAndCanRetry() throws Exception {
    try (var runtime = buildSchemaWithDim(4).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      var session = new LifecycleTestAccessor(runtime).session();
      var snapshot = session.snapshot;
      var release = session.retainTaskLifetime();
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      var returned = new CountDownLatch(1);
      var closer = new Thread(() -> {
        try { runtime.close(); }
        catch (Throwable problem) { failure.set(problem); }
        finally { returned.countDown(); }
      }, "bounded-runtime-close");
      try {
        closer.start();
        assertTrue(returned.await(6, TimeUnit.SECONDS), "a held owner cannot park close indefinitely");
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(snapshot.writer().isOpen(), "timeout cannot invalidate live generation owners");
        assertSame(snapshot, session.snapshot, "the failed close must retain ownership for retry");
        assertThrows(IllegalStateException.class, session::retainTaskLifetime);
        session.commitOps.suspendNrtRefresh();
        session.commitOps.resumeNrtRefresh();
        assertNull(session.crtrt, "a late backfill completion cannot restart a retiring runtime");
      } finally {
        release.run();
        closer.join(2_000);
      }
      runtime.close();
      assertFalse(snapshot.writer().isOpen());
      assertNull(session.snapshot);
    }
  }
}
