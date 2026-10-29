/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.power;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.util.EnergyState;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.core.scheduling.IndexingHold;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** S38.9: a native probe from a closed activation never overwrites a fresh observation. */
final class EnergyStateActivationTest {
  private static EnergyState state(EnergyState.Source source) {
    return new EnergyState(EnergyState.Intent.FULL, source);
  }

  @Test
  void closedActivationCannotOverwriteNewReadingInEitherDirection() throws Exception {
    for (var old : java.util.List.of(EnergyState.Source.AC, EnergyState.Source.BATTERY)) {
      var current = old == EnergyState.Source.AC ? EnergyState.Source.BATTERY : EnergyState.Source.AC;
      try (var executors = new TestEngineExecutors()) {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var poller = new EnergyStatePoller(executors, new GpuSchedulingGauge(), () -> {
          if (calls.incrementAndGet() == 1) {
            entered.countDown();
            boolean done = false;
            while (!done) {
              try { release.await(); done = true; }
              catch (InterruptedException ignored) { /* simulate an uncancellable native call */ }
            }
            returned.countDown();
            return state(old);
          }
          return state(current);
        })) {
          poller.start();
          assertTrue(entered.await(2, TimeUnit.SECONDS));
          poller.close();
          poller.start();
          awaitSource(poller, current);
          release.countDown();
          assertTrue(returned.await(2, TimeUnit.SECONDS));
          Thread.sleep(100);
          assertEquals(current, poller.energyState().source(), "stale " + old + " must not overwrite " + current);
        } finally { release.countDown(); }
      }
    }
  }

  private static void awaitSource(EnergyStatePoller poller, EnergyState.Source expected) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (poller.energyState().source() != expected && System.nanoTime() < until) Thread.sleep(5);
    assertEquals(expected, poller.energyState().source());
  }
}
