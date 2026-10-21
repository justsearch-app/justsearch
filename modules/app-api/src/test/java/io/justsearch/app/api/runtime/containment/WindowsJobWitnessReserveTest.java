/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The real writer and finish logic, without Windows process observation or wall-clock races. */
@Tag("cross-platform")
final class WindowsJobWitnessReserveTest {
  private static final Duration ALLOWANCE = Duration.ofMillis(150);

  @Test void anAppendInFlightAtTheDrainDeadlineCanFinishWithinTheFenceReserve() throws Exception {
    var releaseAppend = new CountDownLatch(1);
    var appendReturning = new CountDownLatch(1);
    var storageReleased = new CountDownLatch(1);
    var fenceReached = new AtomicBoolean();
    var clockReads = new AtomicInteger();
    var gate = new ReentrantLock() {
      @Override public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        fenceReached.set(true);
        assertEquals(Duration.ofMillis(50).toNanos(), unit.toNanos(timeout),
            "the in-flight append must receive the positive fence reserve");
        return super.tryLock(timeout, unit);
      }

      @Override public void unlock() {
        super.unlock();
        storageReleased.countDown();
      }
    };
    var witness = new WindowsJobWitness(() -> {
      int read = clockReads.incrementAndGet();
      if (read <= 2) return 0; // Start and drain deadline: the append is still blocked.
      if (read == 3) {
        releaseAppend.countDown();
        assertTrue(assertDoesNotThrow(() -> storageReleased.await(5, TimeUnit.SECONDS)),
            "the append must release its real storage lock before virtual time advances");
        return Duration.ofMillis(100).toNanos(); // Completes inside the 150 ms reserve.
      }
      // A drainer that spends the reserve reaches expiry before its fence call.
      return ALLOWANCE.toNanos();
    }, gate);
    var appends = new AtomicInteger();
    try {
      startBlockedAppend(witness, releaseAppend, appendReturning, appends);
      enqueue(witness);
      enqueue(witness);
      assertDoesNotThrow(() -> witness.finish(ALLOWANCE),
          "an append in flight at the drain deadline must finish within its reserve");
      assertTrue(fenceReached.get(), "finish must fence storage before returning");
      assertEquals(0, appendReturning.getCount(), "the append must return before finish succeeds");
      assertEquals(3, clockReads.get(), "finish must reach the fence before virtual expiry");
      awaitIdle(witness);
      assertEquals(1, appends.get(), "pending appends must stop at the drain deadline");
    } finally {
      releaseAppend.countDown();
      witness.close();
    }
  }

  @Test void anExpiredFenceRefusesStopUntilStorageSettlesAndTellsTheCallerToRetainItsLock()
      throws Exception {
    var releaseAppend = new CountDownLatch(1);
    var appendReturning = new CountDownLatch(1);
    var clockReads = new AtomicInteger();
    var fenceReached = new AtomicBoolean();
    var gate = new ReentrantLock() {
      @Override public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        fenceReached.set(true);
        assertEquals(0, timeout, "the reserve has really expired before the fence");
        return super.tryLock(timeout, unit);
      }
    };
    // Time expires between the drain-deadline read and the fence, independently of CPU speed.
    var witness = new WindowsJobWitness(
        () -> clockReads.incrementAndGet() <= 2 ? 0 : ALLOWANCE.toNanos(), gate);
    var appends = new AtomicInteger();
    try {
      startBlockedAppend(witness, releaseAppend, appendReturning, appends);
      enqueue(witness);
      assertRetainLockRefusal(witness, ALLOWANCE);
      assertTrue(fenceReached.get(), "refusal must come from the real storage fence");
      assertFalse(gate.tryLock(), "the append must still own the storage lock on refusal");
      assertEquals(1, releaseAppend.getCount(), "storage must remain blocked during refusal");
      assertEquals(1, appendReturning.getCount(), "the refused append must still be in flight");
      assertRetainLockRefusal(witness, Duration.ZERO);
      releaseAppend.countDown();
      awaitIdle(witness);
      assertDoesNotThrow(() -> witness.finish(Duration.ZERO),
          "only settled storage permits the caller to complete stop and release its lock");
      assertEquals(1, appends.get(), "a refused finish must still prohibit pending appends");
    } finally {
      releaseAppend.countDown();
      witness.close();
    }
  }

  private static void assertRetainLockRefusal(WindowsJobWitness witness, Duration allowance) {
    var refusal = assertThrows(IllegalStateException.class, () -> witness.finish(allowance),
        "stop must be refused while storage is active, including on a retry");
    // app.lock belongs to the caller, not this witness. Assert its instruction, not a claim
    // that this class owns or releases that lock; the real recording lock is checked above.
    assertEquals("Exit witness storage is still active; retain the instance lock", refusal.getMessage());
  }

  private static void startBlockedAppend(WindowsJobWitness witness, CountDownLatch release,
      CountDownLatch returning, AtomicInteger appends) throws Exception {
    var started = new CountDownLatch(1);
    witness.attach((type, fields) -> {
      appends.incrementAndGet();
      started.countDown();
      try {
        release.await();
        return true;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      } finally {
        returning.countDown();
      }
    }, new EngineIdentity(1, Optional.empty(), "fence-reserve"));
    enqueue(witness);
    assertTrue(started.await(5, TimeUnit.SECONDS), "the append must be in flight before finish");
  }

  private static void enqueue(WindowsJobWitness witness) throws Exception {
    var enqueue = WindowsJobWitness.class.getDeclaredMethod("enqueue", LedgerRecordType.class, Map.class);
    enqueue.setAccessible(true);
    enqueue.invoke(witness, LedgerRecordType.LEDGER_GAP, Map.of("reason", "WRITE_FAILED"));
  }

  private static void awaitIdle(WindowsJobWitness witness) throws Exception {
    var writing = WindowsJobWitness.class.getDeclaredField("writing");
    writing.setAccessible(true);
    var pending = WindowsJobWitness.class.getDeclaredField("pending");
    pending.setAccessible(true);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    synchronized (witness) {
      while (writing.getBoolean(witness) || !((ArrayDeque<?>) pending.get(witness)).isEmpty()) {
        long left = deadline - System.nanoTime();
        assertTrue(left > 0, "the writer must finish processing the queue");
        TimeUnit.NANOSECONDS.timedWait(witness, left);
      }
    }
  }
}
