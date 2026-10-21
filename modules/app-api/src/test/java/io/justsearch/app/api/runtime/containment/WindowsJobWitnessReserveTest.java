/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.foreign.*;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
@Tag("windows")
final class WindowsJobWitnessReserveTest {
  @Test void anAppendInFlightAtTheDrainDeadlineCanFinishWithinTheFenceReserve() throws Throwable {
    var kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
    var linker = Linker.nativeLinker();
    var create = linker.downcallHandle(kernel.find("CreateJobObjectW").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    var close = linker.downcallHandle(kernel.find("CloseHandle").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    MemorySegment job = (MemorySegment) create.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
    assertNotEquals(0, job.address()); // Empty job; no process is joined or terminated.
    var witness = WindowsJobWitness.associate(job);
    var appends = new java.util.concurrent.atomic.AtomicInteger();
    var enqueue = WindowsJobWitness.class.getDeclaredMethod("enqueue", LedgerRecordType.class, Map.class);
    enqueue.setAccessible(true);
    var writing = WindowsJobWitness.class.getDeclaredField("writing");
    writing.setAccessible(true);
    var pending = WindowsJobWitness.class.getDeclaredField("pending");
    pending.setAccessible(true);
    var recordingGate = WindowsJobWitness.class.getDeclaredField("recordingGate");
    recordingGate.setAccessible(true);
    var appendStarted = new CountDownLatch(1);
    var releaseAppend = new CountDownLatch(1);
    var finishStarted = new AtomicLong();
    var fenced = new AtomicBoolean();
    var allowance = Duration.ofMillis(150);
    // Observe the real fence call, after finish has closed admission and cleared pending work.
    // Release storage here instead of racing a producer against the drain deadline.
    recordingGate.set(witness, new ReentrantLock() {
      @Override public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        assertTrue(timeout >= 0 && unit.toNanos(timeout) <= allowance.toNanos(),
            "the fence must stay within the total allowance");
        if (timeout == 0) {
          assertTrue(System.nanoTime() - finishStarted.get() >= allowance.toNanos(),
              "the in-flight append must receive the reserve until the allowance expires");
        }
        releaseAppend.countDown();
        boolean acquired = super.tryLock(timeout, unit);
        fenced.set(acquired);
        return acquired;
      }
    });
    try {
      witness.attach((type, fields) -> {
        appends.incrementAndGet();
        appendStarted.countDown();
        try { releaseAppend.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return true;
      }, new EngineIdentity(1, Optional.empty(), "fence-reserve"));
      enqueue.invoke(witness, LedgerRecordType.LEDGER_GAP, Map.of("reason", "WRITE_FAILED"));
      assertTrue(appendStarted.await(5, TimeUnit.SECONDS), "the first append must be in flight");
      for (int i = 0; i < 2; i++) enqueue.invoke(witness, LedgerRecordType.LEDGER_GAP,
          Map.of("reason", "WRITE_FAILED"));
      // The whole allowance is the 150 ms reserve, making the drain deadline immediate.
      finishStarted.set(System.nanoTime());
      boolean finished;
      try {
        witness.finish(allowance);
        finished = true;
      } catch (IllegalStateException refused) {
        assertEquals("Exit witness storage is still active; retain the instance lock",
            refused.getMessage());
        finished = false;
      }
      // A cold or descheduled Windows runner can consume the allowance before the fence.
      // Success still requires the real storage lock; otherwise ownership must be retained.
      assertEquals(fenced.get(), finished,
          "finish may succeed only after the in-flight append has been fenced");
      assertEquals(0, releaseAppend.getCount(), "finish must reach the storage fence");
      // Wait for the writer to settle, so a forbidden pending append cannot escape the count.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      synchronized (witness) {
        while (writing.getBoolean(witness) || !((ArrayDeque<?>) pending.get(witness)).isEmpty()) {
          long left = deadline - System.nanoTime();
          assertTrue(left > 0, "the writer must finish processing the queue");
          TimeUnit.NANOSECONDS.timedWait(witness, left);
        }
      }
      assertEquals(1, appends.get(), "only the append in flight may finish after the drain deadline");
    } finally {
      releaseAppend.countDown();
      witness.close();
      int ignored = (int) close.invokeExact(job);
    }
  }
}
