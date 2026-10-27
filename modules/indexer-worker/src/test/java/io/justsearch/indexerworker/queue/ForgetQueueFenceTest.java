/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real queue commit hooks, walk delivery and writers. Portable reflection preserves fail-before. */
@Timeout(20)
final class ForgetQueueFenceTest {
  @TempDir Path directory;

  private Object invoke(JobQueue queue, String name) {
    try { return JobQueue.class.getMethod(name).invoke(queue); }
    catch (InvocationTargetException failed) {
      if (failed.getCause() instanceof RuntimeException failure) throw failure;
      if (failed.getCause() instanceof Error fatal) throw fatal;
      throw new AssertionError(failed.getCause());
    } catch (ReflectiveOperationException missing) {
      fail("The queue must expose the bounded nonblocking FORGET fence", missing);
      return null;
    }
  }

  private Optional<?> tryFence(JobQueue queue) { return (Optional<?>) invoke(queue, "tryForgetQueueFence"); }
  private boolean entryAllowed(JobQueue queue) { return (Boolean) invoke(queue, "forgetEntryAllowed"); }
  private AutoCloseable fence(JobQueue queue) { return (AutoCloseable) tryFence(queue).orElseThrow(); }

  @Test
  void Q55BothChannelsWaitForOuterReleaseOtherWriterCannotDrainAndCallbacksCannotReenter() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open();
      var outer = new ReentrantLock();
      var events = new CopyOnWriteArrayList<String>();
      AtomicReference<Throwable> observerFailure = new AtomicReference<>();
      var display = queue.changeStream().subscribe(delta -> {
        try {
          assertFalse(outer.isHeldByCurrentThread(), "display delivery follows all physical owners");
          assertFalse(entryAllowed(queue)); assertTrue(tryFence(queue).isEmpty());
          events.add("display");
        } catch (Throwable failure) { observerFailure.compareAndSet(null, failure); }
      });
      var walks = queue.subscribeRecordedWalks(key -> {
        try {
          assertFalse(outer.isHeldByCurrentThread(), "walk delivery follows all physical owners");
          assertFalse(entryAllowed(queue)); assertTrue(tryFence(queue).isEmpty());
          events.add("walk");
        } catch (Throwable failure) { observerFailure.compareAndSet(null, failure); }
      });
      try (display; walks) {
        AutoCloseable lease = null;
        CompletableFuture<Integer> otherWriter;
        outer.lock();
        try {
          lease = fence(queue);
          queue.enqueue(List.of(directory.resolve("first.txt")));
          queue.beginRecordedWalk("018f51aa-0000-7000-8000-000000000101", "a".repeat(64), true);
          assertTrue(events.isEmpty(), "nested unlocks may not publish either channel");
          var attempted = new CountDownLatch(1);
          otherWriter = CompletableFuture.supplyAsync(() -> {
            attempted.countDown(); return queue.enqueue(List.of(directory.resolve("second.txt")));
          });
          assertTrue(attempted.await(2, TimeUnit.SECONDS));
          var waiting = otherWriter;
          assertThrows(TimeoutException.class, () -> waiting.get(50, TimeUnit.MILLISECONDS));
          assertTrue(events.isEmpty(), "a competing queue writer cannot drain shared committed events");
        } finally {
          outer.unlock();
          if (lease != null) lease.close(); // Deliberately last, as the actual forget owner composes it.
        }
        assertNotNull(otherWriter); assertEquals(1, otherWriter.get(2, TimeUnit.SECONDS));
        assertNull(observerFailure.get());
        assertEquals("display", events.getFirst());
        assertTrue(events.contains("walk"));
        assertTrue(entryAllowed(queue), "callback refusal ends after existing deliveries finish");
      }
    }
  }

  @Test
  void Q55ContendedQueueTryReturnsWithoutWaitingForItsWriter() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("contended.db"))) {
      queue.open();
      // Assert capability before starting a second thread, so baseline absence fails cleanly.
      assertTrue(entryAllowed(queue));
      var held = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var writer = CompletableFuture.runAsync(() -> {
        try (var ignoredLease = fence(queue)) { held.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (Exception failure) { throw new AssertionError(failure); }
      });
      try {
        assertTrue(held.await(2, TimeUnit.SECONDS));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> assertTrue(tryFence(queue).isEmpty()));
      } finally { release.countDown(); writer.get(2, TimeUnit.SECONDS); }
      try (var ignoredLease = fence(queue)) { assertFalse(entryAllowed(queue)); assertTrue(tryFence(queue).isEmpty()); }
    }
  }

  @Test
  void Q55ReentrantWritesAndUnsubscribeKeepExistingOrderWhileForgettingIsRefused() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("reentry.db"))) {
      queue.open(); assertTrue(entryAllowed(queue));
      var first = new AtomicBoolean(true);
      var events = new CopyOnWriteArrayList<IndexingJobChangeFeed.Delta>();
      var snapshot = queue.changeStream().subscribeWithSnapshot(delta -> {
        assertFalse(entryAllowed(queue)); assertTrue(tryFence(queue).isEmpty());
        events.add(delta);
        if (first.compareAndSet(true, false)) queue.enqueue(List.of(directory.resolve("reentrant.txt")));
      });
      try (var ignoredSubscription = snapshot.subscription()) {
        try (var ignoredLease = fence(queue)) { queue.enqueue(List.of(directory.resolve("original.txt"))); assertTrue(events.isEmpty()); }
        assertEquals(2, events.size());
        assertInstanceOf(IndexingJobChangeFeed.Delta.Insert.class, events.getFirst());
        assertTrue(entryAllowed(queue));
      }
      queue.enqueue(List.of(directory.resolve("after-unsubscribe.txt")));
      assertEquals(2, events.size());
    }
  }

  @Test
  void Q55ConfirmedCommitSurvivesRestoreFailureAndLeaseUnwindsTheFailedConnection() throws Exception {
    Path database = directory.resolve("restore.db");
    try (var queue = new SqliteJobQueue(database)) {
      queue.open(); assertTrue(entryAllowed(queue));
      String key = "018f51aa-0000-7000-8000-000000000102";
      var opened = queue.beginRecordedWalk(key, "b".repeat(64), true);
      var field = SqliteJobQueue.class.getDeclaredField("connection"); field.setAccessible(true);
      var original = (Connection) field.get(queue);
      var intercepted = org.mockito.Mockito.mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(original));
      org.mockito.Mockito.doThrow(new java.sql.SQLException("private restore failure")).when(intercepted).setAutoCommit(true);
      field.set(queue, intercepted);
      try (var ignoredLease = fence(queue)) {
        assertThrows(IllegalStateException.class, () -> queue.closeRecordedWalkEnumeration(
            key, opened.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE));
        org.mockito.Mockito.verify(intercepted).commit();
        org.mockito.Mockito.verify(intercepted, org.mockito.Mockito.never()).rollback();
      }
      org.mockito.Mockito.verify(intercepted).close();
      queue.close(); queue.open();
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, queue.recordedWalk(key).orElseThrow().enumerationOutcome());
      assertTrue(entryAllowed(queue));
    }
  }
  @Test
  void Q55CallbackFromAnotherPhysicalQueueAlsoRefusesCurrentQueueForgetAdmission() throws Exception {
    try (var retired = new SqliteJobQueue(directory.resolve("retired.db"));
        var current = new SqliteJobQueue(directory.resolve("current.db"))) {
      retired.open(); current.open();
      var callbacks = new java.util.concurrent.atomic.AtomicInteger();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      try (var ignoredDisplay = retired.changeStream().subscribe(delta -> {
        try {
          assertFalse(entryAllowed(current), "retired-queue display callbacks cannot reenter a different current queue");
          assertTrue(tryFence(current).isEmpty()); callbacks.incrementAndGet();
        } catch (Throwable assertion) { failure.compareAndSet(null, assertion); }
      }); var ignoredWalk = retired.subscribeRecordedWalks(key -> {
        try {
          assertFalse(entryAllowed(current), "retired-queue walk callbacks cannot reenter a different current queue");
          assertTrue(tryFence(current).isEmpty()); callbacks.incrementAndGet();
        } catch (Throwable assertion) { failure.compareAndSet(null, assertion); }
      })) {
        retired.enqueue(List.of(directory.resolve("retired-source.txt")));
        retired.beginRecordedWalk("018f51aa-0000-7000-8000-000000000102", "b".repeat(64), true);
        assertTrue(callbacks.get() >= 2); assertNull(failure.get());
      }
      assertTrue(entryAllowed(current));
      try (var admitted = fence(current)) { assertNotNull(admitted); }
    }
  }

}
