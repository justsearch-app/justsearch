/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Runs the complete existing scenarios under controlled host evidence and contention. */
@Timeout(240)
final class EngineForgetInProcessTestHostAssumptionsTest {
  @TempDir Path directory;

  @Test
  void returnedRootReplaySuppliesVolumeEvidenceWhenHostHasNone() throws Exception {
    try (var ignored = missingVolume()) {
      scenario().S55CompletedForgetAllowsReturnedRootReplayWithoutPublishingExcludedSource();
    }
  }

  @Test
  void hotSaveReadmissionSuppliesVolumeEvidenceThroughReopenWhenHostHasNone() throws Exception {
    try (var ignored = missingVolume()) {
      scenario().R9HotPublicSaveAfterNativePolicySkipReadmitsCurrentBytesAndKeepsCompletedForgetReceipt();
    }
  }

  @Test
  void hotSaveObserverWaitsForRetryAfterBusyEligibilityRefusal() throws Exception {
    // Isolate the timing regression from host evidence in the before-state too.
    try (var ignored = EngineTestHarness.knownVolume()) {
      var scenario = scenario();
      scenario.busyEligibility = true;
      scenario.R9HotPublicSaveAfterNativePolicySkipReadmitsCurrentBytesAndKeepsCompletedForgetReceipt();
    }
  }

  private EngineForgetInProcessTest scenario() {
    var scenario = new EngineForgetInProcessTest();
    scenario.directory = directory;
    return scenario;
  }

  private AutoCloseable missingVolume() throws Exception {
    var identity = Class.forName("io.justsearch.indexerworker.services.RootIdentity");
    var lookup = identity.getDeclaredField("VOLUME_IDENTITY_LOOKUP");
    lookup.setAccessible(true);
    Object previous = lookup.get(null);
    Function<Path, String> absent = ignored -> null;
    lookup.set(null, absent);
    try {
      var capture = identity.getDeclaredMethod("capture", Path.class);
      capture.setAccessible(true);
      var volume = identity.getDeclaredMethod("volume");
      volume.setAccessible(true);
      Path root = Files.createDirectory(directory.resolve("no-host-volume"));
      assertNull(volume.invoke(capture.invoke(null, root)), "native directory capture has no volume evidence");
    } catch (Exception | Error failure) {
      lookup.set(null, previous);
      throw failure;
    }
    return () -> {
      try { assertSame(absent, lookup.get(null), "scenario must restore the scoped volume lookup"); }
      finally { lookup.set(null, previous); }
    };
  }

  /** Delegates every lock operation to the real queue lock; one eligibility read finds it busy. */
  static final class BusyEligibility implements AutoCloseable {
    private final KnowledgeServer server;
    private final SqliteJobQueue queue;
    private final String target;
    private final Field lockField;
    private final ReentrantLock actual;
    private final Field runtimeLockField;
    private final ReentrantLock runtimeLock;
    private final AtomicInteger checks = new AtomicInteger();
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch observed = new CountDownLatch(1);
    private final Thread holder;
    private volatile int targetOrdinal;
    private volatile boolean busy;

    private BusyEligibility(KnowledgeServer server, SqliteJobQueue queue, Path target) throws Exception {
      this.server = server;
      this.queue = queue;
      this.target = EngineForgetInProcessTest.key(target);
      lockField = SqliteJobQueue.class.getDeclaredField("lock");
      lockField.setAccessible(true);
      actual = (ReentrantLock) lockField.get(queue);
      holder = new Thread(() -> {
        actual.lock();
        try {
          held.countDown();
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        } finally { actual.unlock(); }
      }, "r9-busy-eligibility");
      holder.setDaemon(true);
      var instrumented = mock(ReentrantLock.class, delegatesTo(actual));
      doAnswer(ignored -> tryLock()).when(instrumented).tryLock();
      runtimeLockField = KnowledgeServer.class.getDeclaredField("runtimeSwapLock");
      runtimeLockField.setAccessible(true);
      runtimeLock = (ReentrantLock) runtimeLockField.get(server);
      var runtimeBarrier = mock(ReentrantLock.class, delegatesTo(runtimeLock));
      doAnswer(ignored -> {
        // Wait before taking runtime ownership, so the ordinary observer can still read status.
        if (busy && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
            frame.getClassName().equals(KnowledgeServer.class.getName())
                && frame.getMethodName().equals("promoteServingSuccessorUnderRoots")))) {
          assertTrue(observed.await(30, TimeUnit.SECONDS), "observer must see the refusal before retry proceeds");
        }
        runtimeLock.lockInterruptibly();
        return null;
      }).when(runtimeBarrier).lockInterruptibly();
      lockField.set(queue, instrumented);
      runtimeLockField.set(server, runtimeBarrier);
    }

    static BusyEligibility install(KnowledgeServer server, SqliteJobQueue queue, Path target) throws Exception {
      return new BusyEligibility(server, queue, target);
    }

    void arm() {
      // Readmission visits surviving admissions newest first. The real target admission was
      // created after the sibling, and neither has any later mutation in this fixture.
      var upserts = queue.listSwitchBufferOpsStrict().stream().filter(op -> "UPSERT".equals(op.op())).toList();
      assertEquals(2, upserts.size(), "SETUP: only the two actual fixture file admissions survive");
      assertEquals("path:" + target, upserts.getLast().key(), "SETUP: target eligibility is visited first");
      targetOrdinal = 1;
    }

    private boolean tryLock() throws InterruptedException {
      boolean eligibility = targetOrdinal > 0 && StackWalker.getInstance().walk(frames -> frames.anyMatch(
          frame -> frame.getClassName().equals(SqliteJobQueue.class.getName())
              && frame.getMethodName().equals("tryMatchesNativePolicySkippedAdmission")));
      if (!eligibility) return actual.tryLock();
      int ordinal = checks.incrementAndGet();
      if (ordinal == targetOrdinal) {
        holder.start();
        try {
          assertTrue(held.await(10, TimeUnit.SECONDS), "SETUP: another thread owns the actual queue lock");
          boolean acquired = actual.tryLock();
          if (acquired) actual.unlock();
          assertFalse(acquired, "SETUP: real nonblocking eligibility check finds the queue busy");
          busy = true;
          return false;
        } finally {
          release.countDown();
          holder.join(10_000);
          assertFalse(holder.isAlive(), "SETUP: contention ends before projection verification");
        }
      }
      return actual.tryLock();
    }

    void observe(Object refusal) {
      if (("Buffered UPSERT lacks its accepted target projection: " + target).equals(refusal)) observed.countDown();
    }

    void assertObserved() {
      assertTrue(busy, "SETUP: forced one real busy eligibility check");
      assertEquals(0, observed.getCount(), "observer saw the exact first refusal before allowing retry");
      assertTrue(checks.get() > 2, "production retried eligibility after the observed refusal");
    }

    @Override public void close() throws Exception {
      observed.countDown();
      release.countDown();
      holder.join(10_000);
      assertFalse(holder.isAlive(), "contention thread must end");
      lockField.set(queue, actual);
      runtimeLockField.set(server, runtimeLock);
    }
  }
}
