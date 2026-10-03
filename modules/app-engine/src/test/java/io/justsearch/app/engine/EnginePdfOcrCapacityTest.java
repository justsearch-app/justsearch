/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.indexerworker.server.WorkerExecutorRegistrations;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EnginePdfOcrCapacityTest {
  @Test void theProductionOcrOwnerKeepsItsSingleInstanceUntilActualExit() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var factory = Thread.ofPlatform().daemon().name("ocr-capacity-test-", 0).factory();
    try (var registry = new DefaultEngineExecutorRegistry();
        var owners = new WorkerExecutorRegistrations(registry)) {
      var registration = owners.pdfOcr();
      var first = registration.open(factory);
      try {
        var task = first.submit(() -> {
          entered.countDown();
          while (release.getCount() != 0) {
            try { release.await(); }
            catch (InterruptedException expected) { interrupted.countDown(); }
          }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        task.cancel(true);
        first.shutdownNow();
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        assertEquals(EngineExecutorRejectedException.Reason.INSTANCE_LIMIT,
            assertThrows(EngineExecutorRejectedException.class,
                () -> registration.open(factory)).reason());
        release.countDown();
        assertTrue(first.awaitTermination(3, TimeUnit.SECONDS));
        try (var replacement = registration.open(factory)) {
          assertEquals(42, replacement.submit(() -> 42).get(3, TimeUnit.SECONDS));
        }
      } finally {
        release.countDown();
        first.close();
      }
    }
  }
}
