/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.foreign.*;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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
    Thread producer = null;
    try {
      witness.attach((type, fields) -> {
        appends.incrementAndGet();
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return true;
      }, new EngineIdentity(1, Optional.empty(), "fence-reserve"));
      producer = Thread.ofPlatform().daemon().start(() -> {
        try {
          Thread.sleep(40); // Just before the 50 ms drain deadline in a 200 ms allowance.
          for (int i = 0; i < 3; i++) enqueue.invoke(witness, LedgerRecordType.LEDGER_GAP,
              Map.of("reason", "WRITE_FAILED", "count", 1, "since", java.time.Instant.now()));
        } catch (Exception e) { throw new AssertionError(e); }
      });
      assertDoesNotThrow(() -> witness.finish(Duration.ofMillis(200)),
          "a 100 ms append already in flight gets the reserved fence time; pending writes stop");
      assertEquals(1, appends.get(), "only the append in flight may finish after the drain deadline");
    } finally {
      if (producer != null) producer.join(2000);
      witness.close();
      int ignored = (int) close.invokeExact(job);
    }
  }
}
