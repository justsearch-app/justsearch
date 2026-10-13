/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * b82 L1: a lane is stale from its own evidence, measured against its effective cadence. A lane
 * whose probe takes about one period but keeps producing samples on its cadence is not stale.
 * Driven on a controlled clock with the cadence seen live under bulk indexing: busy period 2 s, a
 * reading every 4 s, each published about 2.1 s after it was taken. {@link StatusProbeHangTest}
 * keeps the other half: a lane whose probe hangs still goes stale within a bounded time.
 */
final class StatusProbeCadenceTest {
  static final long PERIOD_MS = StatusLifecycleHandler.SAMPLER_BUSY_PERIOD_MS;
  static final long READING_INTERVAL_MS = 4_000L;
  private static final long PROBE_MS = 2_100L;
  private static final long STEP_MS = 50L;

  @Test
  @Timeout(60)
  void aLaneWhoseProbeTakesAboutOnePeriodStaysFreshWhileItKeepsSamplingOnItsCadence()
      throws Exception {
    var lane = new Lane();
    lane.probe(0L, PROBE_MS);
    for (int reading = 1; reading <= 6; reading++) {
      long takenAt = reading * READING_INTERVAL_MS;
      // Between publications: the last reading ages until the next probe starts.
      lane.assertFreshUntil(takenAt);
      // The next probe is in flight for 2.1 s; the previous reading keeps ageing meanwhile.
      var probe = lane.start(takenAt);
      lane.assertFreshUntil(takenAt + PROBE_MS - 1L);
      probe.publishAt(takenAt + PROBE_MS);
      assertFalse(lane.stale(), "a new reading was just published");
    }
  }

  /** One status lane on a controlled clock, with probes the test starts and publishes. */
  static final class Lane {
    final AtomicLong clock = new AtomicLong();
    final StatusObservation<String> observation = new StatusObservation<>();

    boolean stale() { return observation.stale(clock.get()); }

    /** A probe that starts at {@code takenAt} and publishes {@code durationMs} later. */
    void probe(long takenAt, long durationMs) throws Exception {
      start(takenAt).publishAt(takenAt + durationMs);
    }

    Probe start(long takenAt) throws Exception {
      clock.set(takenAt);
      var probe = new Probe();
      probe.done = observation.refresh("cadence", () -> {
        probe.entered.countDown();
        try {
          if (!probe.release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("unreleased");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
        return "reading@" + takenAt;
      }, clock::get, PERIOD_MS, null, false);
      assertTrue(probe.entered.await(10, TimeUnit.SECONDS), "the probe started");
      return probe;
    }

    void assertFreshUntil(long until) {
      for (long t = clock.get(); t <= until; t += STEP_MS) {
        clock.set(t);
        assertFalse(stale(), "a lane whose probe takes about one period and samples every "
            + READING_INTERVAL_MS + " ms must not read stale: age " + observation.age(t)
            + " ms at " + t + " ms");
      }
      clock.set(until);
    }

    final class Probe {
      final CountDownLatch entered = new CountDownLatch(1);
      final CountDownLatch release = new CountDownLatch(1);
      java.util.concurrent.CompletableFuture<Void> done;

      void publishAt(long at) throws Exception {
        clock.set(at);
        release.countDown();
        done.get(10, TimeUnit.SECONDS);
      }
    }
  }
}
