/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static io.justsearch.ui.api.StatusProbeCadenceTest.PERIOD_MS;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * b82 L1, the bound: allowing a lane's recent probe duration in its cadence must not hide a lane
 * that stops producing. A hung probe still goes stale: within three periods for a lane whose
 * probes were fast or longer than the allowance (three periods), and within the capped cadence
 * (3 x (period + 3 periods)) for a lane whose probes were just inside it.
 */
final class StatusProbeHangTest {
  /** The lane's recent probes that its effective cadence considers (the design's last few). */
  private static final int RECENT_PROBES = 4;
  /** The longest probe the design counts as slow, and so the largest allowance: three periods. */
  private static final long CAP_ALLOWANCE_MS = 3L * PERIOD_MS;

  @Test
  @Timeout(60)
  void aLaneWhoseProbeHangsAfterSlowProbesStillGoesStaleWithinTheCap() throws Exception {
    // Probes just inside the allowance (2.9 periods) give the longest cadence a lane can earn.
    assertHungLaneStaleBy(29L * PERIOD_MS / 10L,
        StatusLifecycleHandler.SAMPLE_STALE_PERIODS * (PERIOD_MS + CAP_ALLOWANCE_MS) + 1L);
  }

  @Test
  @Timeout(60)
  void probesLongerThanTheAllowanceEarnNoCadenceSoAHungProbeIsStaleAfterThreePeriods()
      throws Exception {
    // Five-period probes are hung, not slow: uncapped they would stretch the limit to 36 s.
    assertHungLaneStaleBy(5L * PERIOD_MS,
        StatusLifecycleHandler.SAMPLE_STALE_PERIODS * PERIOD_MS + 1L);
  }

  private static void assertHungLaneStaleBy(long probeMs, long ageMs) throws Exception {
    var lane = new StatusProbeCadenceTest.Lane();
    long takenAt = 0L;
    for (int reading = 0; reading <= RECENT_PROBES; reading++) {
      takenAt = reading * (PERIOD_MS + probeMs);
      lane.probe(takenAt, probeMs);
    }
    long lastObservedAt = takenAt;
    var hung = lane.start(takenAt + probeMs + PERIOD_MS);
    lane.clock.set(lastObservedAt + ageMs);
    assertTrue(lane.stale(), "after probes of " + probeMs + " ms a hung probe is stale by age "
        + ageMs + " ms (age now " + lane.observation.age(lane.clock.get()) + " ms)");
    hung.release.countDown();
  }

  @Test
  @Timeout(60)
  void aLaneWhoseFastProbeHangsGoesStaleAfterThreeOfItsPeriods() throws Exception {
    var lane = new StatusProbeCadenceTest.Lane();
    for (int reading = 0; reading < RECENT_PROBES; reading++) {
      lane.probe(reading * PERIOD_MS, 0L);
    }
    long lastObservedAt = (RECENT_PROBES - 1) * PERIOD_MS;
    var hung = lane.start(lastObservedAt + PERIOD_MS);
    lane.clock.set(lastObservedAt + StatusLifecycleHandler.SAMPLE_STALE_PERIODS * PERIOD_MS - 1L);
    assertFalse(lane.stale(), "within three periods of a fast lane's last reading");
    lane.clock.set(lastObservedAt + StatusLifecycleHandler.SAMPLE_STALE_PERIODS * PERIOD_MS + 1L);
    assertTrue(lane.stale(), "a fast lane that stops producing goes stale after three periods");
    hung.release.countDown();
  }
}
