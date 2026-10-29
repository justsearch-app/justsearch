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
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import io.justsearch.app.services.settings.UiSettingsStore;

/** D6/D7/S38.9: source and energy intent stay independent, and observations belong to activation. */
final class IndexingRulesPowerTest {
  @TempDir Path directory;
  private final AtomicInteger policyFiles = new AtomicInteger();

  private void policy(IndexingHold hold, boolean battery, boolean manual) {
    assertDoesNotThrow(() -> {
      var file = directory.resolve("policy-" + policyFiles.incrementAndGet() + ".json");
      Files.writeString(file, "{\"schemaVersion\":6,\"acceptedRevision\":0,\"lastCommittedOperationKey\":null,"
          + "\"settings\":{\"indexingPause\":{\"paused\":" + manual + ",\"untilEpochMs\":null,"
          + "\"reason\":" + (manual ? "\"USER_PAUSED\"" : "null") + ",\"pauseOnBattery\":" + battery + "}}}");
      new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file).bindIndexingHold(hold);
    });
  }

  /** Baseline bootstrap started immediately: represent the absent new wait as no wait. */
  private static void firstReading(EnergyStatePoller poller) throws InterruptedException {
    try { EnergyStatePoller.class.getMethod("awaitFirstReading").invoke(poller); }
    catch (NoSuchMethodException baseline) { /* timing/admission assertions must fail, not compilation */ }
    catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof InterruptedException interrupted) throw interrupted;
      throw new AssertionError(failure.getCause());
    } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
  }
  private static EnergyState state(EnergyState.Source source) {
    return new EnergyState(EnergyState.Intent.FULL, source);
  }

  @Test
  void powerMatrixAppliesThroughPollerAndHoldWithoutChangingEnergySaver() {
    try (var executors = new TestEngineExecutors()) {
      var hold = new IndexingHold();
      policy(hold, true, false);
      var gauge = new GpuSchedulingGauge(hold);
      var source = new AtomicReference<>(EnergyState.unknown());
      try (var poller = new EnergyStatePoller(executors, gauge, source::get)) {
        for (var intent : EnergyState.Intent.values()) {
          for (var power : EnergyState.Source.values()) {
            source.set(new EnergyState(intent, power));
            poller.poll();
            assertEquals(power == EnergyState.Source.BATTERY, hold.held(), source.get().toString());
            assertEquals(intent == EnergyState.Intent.REDUCED, gauge.isEnergyReduced());
            assertEquals(power == EnergyState.Source.BATTERY ? "ON_BATTERY" : null, hold.snapshot().reason());
          }
        }
        source.set(state(EnergyState.Source.BATTERY)); poller.poll();
        hold.pause(null, "USER_PAUSED");
        policy(hold, false, true);
        assertEquals("USER_PAUSED", hold.snapshot().reason());
      }
    }
  }

  @Test
  void firstReadingWaitIsBoundedUnknownUntilKnownAndFreshOnEveryActivation() throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var hold = new IndexingHold();
      policy(hold, true, false);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var source = new AtomicReference<>(state(EnergyState.Source.BATTERY));
      try (var poller = new EnergyStatePoller(executors, new GpuSchedulingGauge(hold), () -> {
        entered.countDown();
        try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return source.get();
      })) {
        poller.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertFalse(hold.held(), "unknown must not be reported as battery");
        long before = System.nanoTime();
        firstReading(poller);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        assertTrue(elapsed >= 2800 && elapsed < 4000, "bounded monotonic wait: " + elapsed);
        assertEquals(EnergyState.Source.UNKNOWN, poller.energyState().source());
        release.countDown();
        awaitSource(poller, EnergyState.Source.BATTERY);
        assertTrue(hold.held(), "a delayed result still applies");
        poller.close();
        source.set(state(EnergyState.Source.AC));
        poller.start();
        firstReading(poller);
        assertFalse(hold.held());
      } finally { release.countDown(); }
    }
  }

  @Test
  void batteryBeforeTimeoutHoldsAndDefaultOffDoesNotWaitForBlockedProbe() throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var hold = new IndexingHold();
      policy(hold, true, false);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      try (var poller = new EnergyStatePoller(executors, new GpuSchedulingGauge(hold), () -> {
        entered.countDown();
        try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return state(EnergyState.Source.BATTERY);
      })) {
        poller.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var waiter = CompletableFuture.runAsync(() -> {
          try { firstReading(poller); }
          catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        Thread.sleep(100);
        assertFalse(waiter.isDone());
        release.countDown();
        waiter.get(2, TimeUnit.SECONDS);
        assertTrue(hold.held());
        policy(hold, false, false);
        long before = System.nanoTime();
        firstReading(poller);
        assertTrue(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(100));
      } finally { release.countDown(); }
    }
  }

  @Test
  void disabledBatteryRuleSkipsFreshBlockedActivationWait() throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var hold = new IndexingHold();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      try (var poller = new EnergyStatePoller(executors, new GpuSchedulingGauge(hold), () -> {
        entered.countDown();
        try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return state(EnergyState.Source.BATTERY);
      })) {
        poller.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        long before = System.nanoTime();
        firstReading(poller);
        assertTrue(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(500),
            "default-off activation must not wait for its blocked first observation");
        assertFalse(hold.held());
      } finally { release.countDown(); }
    }
  }

  @Test
  void firstExceptionCompletesUnknown() throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var hold = new IndexingHold();
      policy(hold, true, false);
      var calls = new AtomicInteger();
      try (var poller = new EnergyStatePoller(executors, new GpuSchedulingGauge(hold), () -> {
        if (calls.incrementAndGet() == 1) return state(EnergyState.Source.BATTERY);
        throw new IllegalStateException("first probe of the new activation failed");
      })) {
        poller.start(); firstReading(poller); awaitSource(poller, EnergyState.Source.BATTERY);
        poller.close(); poller.start();
        long before = System.nanoTime();
        firstReading(poller);
        assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2));
        assertEquals(EnergyState.Source.UNKNOWN, poller.energyState().source());
        assertFalse(hold.held());
      }
    }
  }

  private static void awaitSource(EnergyStatePoller poller, EnergyState.Source expected) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (poller.energyState().source() != expected && System.nanoTime() < until) Thread.sleep(5);
    assertEquals(expected, poller.energyState().source());
  }
}
