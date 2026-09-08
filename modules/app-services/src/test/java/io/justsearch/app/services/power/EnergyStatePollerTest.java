/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.util.EnergyState;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Item A5. {@code WorkerSpawner.pollEnergyState()} had no test of its own — the poll's only pins
 * were the MMF offset ({@code MmfWorkerSignalLayoutV1Test}) and the yield composition
 * ({@code WorkerSignalBusEnergyTest}). Moving it off the spawner is where that gap closes: these
 * cases pin what the poll publishes and what it does when the probe fails, which is what stopped
 * item A11's deletion of {@code WorkerSpawner} taking the behaviour with it unnoticed.
 */
final class EnergyStatePollerTest {

  private static EnergyState reduced() {
    return new EnergyState(EnergyState.Intent.REDUCED, EnergyState.Source.BATTERY);
  }

  private static EnergyState full() {
    return new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC);
  }

  @Test
  @DisplayName("a poll writes the in-process gauge, in both directions")
  void pollWritesTheGauge() {
    GpuSchedulingGauge gauge = new GpuSchedulingGauge();
    AtomicReference<EnergyState> probe = new AtomicReference<>(reduced());

    try (EnergyStatePoller poller =
        new EnergyStatePoller(gauge, probe::get)) {
      poller.poll();
      assertTrue(gauge.isEnergyReduced(), "the gauge is the in-process authority");
      assertTrue(gauge.shouldYieldGpuBackfill(), "energy alone is a reason to yield");
      assertEquals(EnergyState.Intent.REDUCED, poller.energyState().intent());

      probe.set(full());
      poller.poll();
      assertFalse(gauge.isEnergyReduced());
      assertFalse(gauge.shouldYieldGpuBackfill());
      assertEquals(EnergyState.Intent.FULL, poller.energyState().intent());
    }
  }

  @Test
  @DisplayName("the gauge's GPU signal is untouched by the energy poll")
  void pollDoesNotDisturbTheGpuSignal() {
    GpuSchedulingGauge gauge = new GpuSchedulingGauge();
    gauge.setMainGpuActive(true);

    try (EnergyStatePoller poller = new EnergyStatePoller(gauge, EnergyStatePollerTest::full)) {
      poller.poll();
    }

    assertTrue(gauge.isMainGpuActive(), "the two reasons are independent writers of one gauge");
    assertFalse(gauge.isEnergyReduced());
  }

  @Test
  @DisplayName("energyState() is UNKNOWN — not null — before the first poll")
  void unknownBeforeFirstPoll() {
    try (EnergyStatePoller poller =
        new EnergyStatePoller(new GpuSchedulingGauge(), EnergyStatePollerTest::full)) {
      assertEquals(EnergyState.Intent.UNKNOWN, poller.energyState().intent());
      assertFalse(poller.energyState().reduced(), "unknown must never read as reduced");
    }
  }

  @Test
  @DisplayName("a failing probe leaves the last polled state and gauge untouched")
  void probeFailureIsBestEffort() {
    GpuSchedulingGauge gauge = new GpuSchedulingGauge();
    AtomicReference<EnergyState> probe = new AtomicReference<>(reduced());

    try (EnergyStatePoller poller =
        new EnergyStatePoller(
            gauge,
            () -> {
              EnergyState next = probe.get();
              if (next == null) {
                throw new IllegalStateException("probe unavailable");
              }
              return next;
            })) {
      poller.poll();
      assertTrue(gauge.isEnergyReduced());

      probe.set(null);
      poller.poll(); // must not throw, must not flip the throttle
      assertTrue(gauge.isEnergyReduced(), "a transient probe failure cannot clear the throttle");
      assertEquals(EnergyState.Intent.REDUCED, poller.energyState().intent());
    }
  }

  @Test
  @DisplayName("the single-argument constructor polls and writes the gauge")
  void singleArgConstructorWritesTheGauge() {
    GpuSchedulingGauge gauge = new GpuSchedulingGauge();
    try (EnergyStatePoller poller =
        new EnergyStatePoller(gauge, EnergyStatePollerTest::reduced)) {
      poller.poll();
    }
    assertTrue(gauge.isEnergyReduced());
  }

  @Test
  @Tag("load-sensitive")
  @DisplayName("start() polls immediately, is idempotent, and is restartable after close()")
  void startPollsImmediatelyAndRestarts() throws InterruptedException {
    GpuSchedulingGauge gauge = new GpuSchedulingGauge();
    CountDownLatch firstRun = new CountDownLatch(1);
    AtomicReference<CountDownLatch> latch = new AtomicReference<>(firstRun);

    EnergyStatePoller poller =
        new EnergyStatePoller(
            gauge,
            () -> {
              latch.get().countDown();
              return reduced();
            });
    try {
      poller.start();
      poller.start(); // idempotent: must not stack a second scheduled poll
      assertTrue(firstRun.await(10, TimeUnit.SECONDS), "start() polls once immediately");
      assertTrue(gauge.isEnergyReduced());

      poller.close();
      assertEquals(
          EnergyState.Intent.REDUCED,
          poller.energyState().intent(),
          "the last polled state survives a stop — the OS intent did not change");

      CountDownLatch afterRestart = new CountDownLatch(1);
      latch.set(afterRestart);
      poller.start();
      assertTrue(
          afterRestart.await(10, TimeUnit.SECONDS),
          "a bootstrap restarted for boot recovery must get its poll back");
    } finally {
      poller.close();
    }
  }

  @Test
  @DisplayName("the cadence is the 15 s the spawner used")
  void cadenceIsUnchanged() {
    assertEquals(15_000L, EnergyStatePoller.POLL_INTERVAL_MS);
  }
}
