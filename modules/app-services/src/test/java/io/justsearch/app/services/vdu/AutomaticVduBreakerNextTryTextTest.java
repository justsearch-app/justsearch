/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * b100: the pause's status text says when automatic picture reading tries again, as of the time
 * status is read. It never names a time that has passed and never names a time earlier than the
 * real deadline. Both the monotonic clock and the wall clock are driven by the test.
 */
final class AutomaticVduBreakerNextTryTextTest {
  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private final MovableClock wall = new MovableClock(Instant.parse("2026-10-14T14:00:00Z"));
  private final VduCapabilityState status = new VduCapabilityState();
  private final AutomaticVduBreaker breaker = new AutomaticVduBreaker(status, nanos::get, wall);

  @Test void aPauseReadAfterItsDeadlineWithNoProbeNamesNoPastTime() {
    open();
    String atOpen = detail();
    assertTrue(atOpen.contains("Next try at 14:05"), "the pause names its real probe time: " + atOpen);

    advance(Duration.ofMinutes(30));   // the person kept working: no idle tick probed
    String later = detail();
    assertFalse(later.contains("14:05"), "no past time is named: " + later);
    assertFalse(later.contains("Next try at"), "no clock time at all once the deadline passed: " + later);
    assertTrue(later.contains("next idle"), "the next try is when the computer is next idle: " + later);
    assertEquals(VduCapabilityState.REASON_BREAKER_OPEN_REFUSED, status.snapshot().blockedReason(),
        "still paused");
  }

  @Test void aDeadlineBetweenMinutesIsShownRoundedUpSoItIsNeverEarly() {
    wall.set(Instant.parse("2026-10-14T14:00:40Z"));
    open();   // the five-minute back-off ends at 14:05:40
    String detail = detail();
    assertTrue(detail.contains("Next try at 14:06"), "14:05:40 is shown as 14:06: " + detail);
    assertFalse(detail.contains("14:05"), "never a minute before the real deadline: " + detail);
  }

  private void open() {
    for (int i = 0; i < AutomaticVduBreaker.OPEN_AFTER; i++) {
      assertTrue(breaker.tryStartAutomatic(), "closed: automatic pass " + (i + 1) + " may start");
      breaker.passEnded(new AutomaticVduBreaker.Result.Counted(
          AutomaticVduBreaker.Cause.REFUSED, "generation refused: settings recovery"));
    }
    assertFalse(breaker.tryStartAutomatic(), "three counted passes open the breaker");
  }

  private String detail() {
    String detail = status.snapshot().detail();
    assertNotNull(detail, "a paused breaker shows a detail");
    return detail;
  }

  private void advance(Duration by) {
    nanos.addAndGet(by.toNanos());
    wall.set(wall.instant().plus(by));
  }

  private static final class MovableClock extends Clock {
    private volatile Instant now;

    MovableClock(Instant start) {
      now = start;
    }

    void set(Instant instant) {
      now = instant;
    }

    @Override public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException("fixed to UTC");
    }

    @Override public Instant instant() {
      return now;
    }
  }
}
