/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.foreign.*;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
final class WindowsJobWitnessBoundTest {
  @Test void blockedStorageCannotOutliveFinishAllowance() throws Throwable {
    var kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
    var linker = Linker.nativeLinker();
    var create = linker.downcallHandle(kernel.find("CreateJobObjectW").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    var close = linker.downcallHandle(kernel.find("CloseHandle").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    MemorySegment job = (MemorySegment) create.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
    assertNotEquals(0, job.address()); // Empty job: never join or terminate any process.
    WindowsJobWitness witness = WindowsJobWitness.associate(job);
    CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
    CompletableFuture<Boolean> finished = new CompletableFuture<>();
    Thread finisher = null;
    try {
      witness.attach((type, fields) -> {
        writing.countDown();
        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return true;
      }, new EngineIdentity(1, Optional.empty(), "blocked-writer"));
      var enqueue = WindowsJobWitness.class.getDeclaredMethod("enqueue", LedgerRecordType.class, Map.class);
      enqueue.setAccessible(true);
      enqueue.invoke(witness, LedgerRecordType.LEDGER_GAP, Map.of("reason", "WRITE_FAILED"));
      assertTrue(writing.await(2, TimeUnit.SECONDS));
      finisher = Thread.ofPlatform().daemon().start(() -> {
        try { witness.finish(Duration.ofMillis(10)); finished.complete(false); }
        catch (IllegalStateException refused) { finished.complete(true); }
      });
      assertTrue(assertDoesNotThrow(() -> finished.get(750, TimeUnit.MILLISECONDS),
          "finish must report failure while the disk writer is still blocked"));
    } finally {
      release.countDown();
      if (finisher != null) finisher.join(2000);
      witness.close();
      int ignored = (int) close.invokeExact(job);
    }
  }
}
