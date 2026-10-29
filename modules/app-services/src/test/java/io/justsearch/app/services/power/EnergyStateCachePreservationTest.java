/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.power;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.util.EnergyState;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Must-not-break: the default-off battery preference does not change existing energy-saver behavior. */
final class EnergyStateCachePreservationTest {
  private static EnergyState reduced() {
    return new EnergyState(EnergyState.Intent.REDUCED, EnergyState.Source.BATTERY);
  }

  @Test
  void defaultOffRestartKeepsEnergySaverUntilAFreshReading() throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var gauge = new GpuSchedulingGauge();
      var calls = new AtomicInteger();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      try (var poller = new EnergyStatePoller(executors, gauge, () -> {
        if (calls.incrementAndGet() == 1) return reduced();
        entered.countDown();
        try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC);
      })) {
        poller.start();
        awaitReduced(poller);
        poller.close();
        poller.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(gauge.isEnergyReduced(), "battery pause off must preserve the existing energy-saver actuation");
        assertTrue(poller.energyState().reduced());
        assertFalse(gauge.indexingHold().held());
      } finally { release.countDown(); }
    }
  }

  @Test
  void defaultOffRestartFirstFailureKeepsExistingBestEffortEnergySaver() throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var gauge = new GpuSchedulingGauge();
      var calls = new AtomicInteger();
      var failed = new CountDownLatch(1);
      try (var poller = new EnergyStatePoller(executors, gauge, () -> {
        if (calls.incrementAndGet() == 1) return reduced();
        failed.countDown();
        throw new IllegalStateException("unavailable host observation");
      })) {
        poller.start(); awaitReduced(poller); poller.close(); poller.start();
        assertTrue(failed.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertTrue(gauge.isEnergyReduced());
        assertTrue(poller.energyState().reduced());
      }
    }
  }

  private static void awaitReduced(EnergyStatePoller poller) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!poller.energyState().reduced() && System.nanoTime() < until) Thread.sleep(5);
    assertTrue(poller.energyState().reduced());
  }
}
