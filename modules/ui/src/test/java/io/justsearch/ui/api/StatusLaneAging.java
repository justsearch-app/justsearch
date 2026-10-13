/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * b82: a held status lane is stale by its own age, older than {@link
 * StatusLifecycleHandler#SAMPLE_STALE_PERIODS} of its effective cadence, never by how long a reader
 * waited. Tests that need a held lane to be reported stale move the status clock past the longest
 * limit any lane can have instead of sleeping past a reader's deadline; one sampler pass then keeps
 * every lane that is not held current, so only the held lane ages.
 */
final class StatusLaneAging {
  /**
   * Beyond the capped limit of an idle lane (120 s): three effective cadences of at most the idle
   * period plus the allowance of {@link StatusLifecycleHandler#PROBE_ALLOWANCE_PERIODS} periods. A
   * lane's earlier probes (a slow real disk-encryption probe, a cached answer) may have earned it
   * part of that allowance, so three bare periods are not always enough.
   */
  static final long BEYOND_STALE_MS = StatusLifecycleHandler.SAMPLER_IDLE_PERIOD_MS
      * StatusLifecycleHandler.SAMPLE_STALE_PERIODS
      * (1L + StatusLifecycleHandler.PROBE_ALLOWANCE_PERIODS) + 1_001L;

  private StatusLaneAging() {}

  static void ageHeldLanes(StatusLifecycleHandler handler) throws InterruptedException {
    handler.setClockForTesting(() -> System.currentTimeMillis() + BEYOND_STALE_MS);
    Thread pass = Thread.ofPlatform().daemon().start(handler::sampleAndBuildStatusSnapshot);
    pass.join(10_000L);
    assertFalse(pass.isAlive(), "the next sampler pass is bounded while a lane is held");
  }
}
