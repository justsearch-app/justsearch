/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** D2/D5: reevaluation at every admission, snapshot and waiter wake uses the same hold. */
final class IndexingRulesHoldTest {
  @Test
  void localMinuteTableIncludesEndpointsOvernightSundayAndDisabledHours() {
    var day = new IndexingSchedule(true, List.of(1, 3), 600, 660);
    for (int weekday = 1; weekday <= 7; weekday++) {
      for (int minute = 0; minute < 1440; minute++) {
        assertEquals((weekday == 1 || weekday == 3) && minute >= 600 && minute < 660,
            day.allows(weekday, minute), weekday + ":" + minute);
      }
    }
    var night = new IndexingSchedule(true, List.of(7), 1320, 360);
    for (int weekday = 1; weekday <= 7; weekday++) {
      for (int minute = 0; minute < 1440; minute++) {
        assertEquals(weekday == 7 && minute >= 1320 || weekday == 1 && minute < 360,
            night.allows(weekday, minute), weekday + ":" + minute);
      }
    }
    assertTrue(new IndexingSchedule(false, List.of(), 0, 1).allows(2, 900));
    assertThrows(IllegalArgumentException.class, () -> new IndexingSchedule(true, List.of(), 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new IndexingSchedule(false, List.of(1), 1, 1));
  }

  @Test
  void dstRepeatedSkippedHoursClockAndZoneChangesAreReevaluatedInTheRealHold() {
    var instant = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-25T00:30:00Z"));
    var zone = new java.util.concurrent.atomic.AtomicReference<>(ZoneId.of("Europe/Berlin"));
    var hold = new IndexingHold();
    hold.setLocalTime(() -> {
      var now = instant.get().atZone(zone.get());
      return new IndexingHold.LocalMinute(now.getDayOfWeek().getValue(), now.getHour() * 60 + now.getMinute());
    });
    hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(7), 150, 180), false);
    assertFalse(hold.held(), "first 02:30 is inside");
    instant.set(Instant.parse("2026-10-25T01:30:00Z"));
    assertFalse(hold.held(), "repeated 02:30 is inside again");
    instant.set(Instant.parse("2026-03-29T00:59:00Z"));
    assertTrue(hold.held());
    instant.set(Instant.parse("2026-03-29T01:00:00Z"));
    assertTrue(hold.held(), "skipped 02:30 never occurs");
    instant.set(Instant.parse("2026-10-25T00:30:00Z"));
    assertFalse(hold.held(), "backward clock jump reopens");
    zone.set(ZoneOffset.UTC);
    assertTrue(hold.held(), "a changed local snapshot applies immediately");
  }

  @Test
  void combinedReasonsKeepAdmissionClosedThroughResumeExpiryAndLeasePublication() throws Exception {
    var now = new AtomicLong(2000);
    var clock = new Clock() {
      @Override public ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(ZoneId zone) { return fixed(instant(), zone); }
      @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    var hold = new IndexingHold(clock);
    hold.setLocalTime(() -> new IndexingHold.LocalMinute(1, 500));
    var hours = new IndexingSchedule(true, List.of(1), 600, 660);
    var lease = hold.tryAcquire();
    hold.setOnBattery(true);
    hold.setPolicy(true, 3000L, "USER_PAUSED", hours, true);
    assertEquals(List.of("USER_PAUSED", "SCHEDULE", "ON_BATTERY"), hold.snapshot().reasons());
    assertEquals(IndexingHold.State.PAUSING, hold.snapshot().state());
    assertNull(hold.tryAcquire());
    hold.resume();
    assertEquals(List.of("SCHEDULE", "ON_BATTERY"), hold.snapshot().reasons());
    assertNull(hold.snapshot().untilEpochMs());
    hold.pause(3000L, "USER_PAUSED");
    now.set(4000);
    assertEquals(List.of("SCHEDULE", "ON_BATTERY"), hold.snapshot().reasons());
    assertNull(hold.snapshot().untilEpochMs(), "rules never inherit an expired manual deadline");
    lease.close();
    assertEquals(IndexingHold.State.PAUSED, hold.snapshot().state());
    hold.pause(null, "SETTINGS_RECOVERY");
    assertEquals(List.of("SETTINGS_RECOVERY", "SCHEDULE", "ON_BATTERY"), hold.snapshot().reasons());
    hold.setPolicy(true, null, "SETTINGS_RECOVERY", null, false);
    assertEquals(List.of("SETTINGS_RECOVERY"), hold.snapshot().reasons());
    hold.resume();
    assertNotNull(hold.tryAcquire());
  }

  @Test
  void queuedWorkAutomaticallyContinuesAndDisabledSchedulesNeverReadLocalTime() throws Exception {
    var minute = new AtomicInteger(599);
    var calls = new AtomicInteger();
    var hold = new IndexingHold();
    hold.setLocalTime(() -> { calls.incrementAndGet(); return new IndexingHold.LocalMinute(1, minute.get()); });
    hold.setPolicy(false, null, null, new IndexingSchedule(false, List.of(1), 600, 660), false);
    assertFalse(hold.held());
    hold.snapshot();
    assertEquals(0, calls.get());
    hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1), 600, 660), false);
    var waiting = CompletableFuture.supplyAsync(() -> {
      try (var lease = hold.acquire()) { assertNotNull(lease); return true; }
      catch (InterruptedException failure) { throw new AssertionError(failure); }
    });
    try {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (hold.snapshot().waiting() == 0 && System.nanoTime() < until) Thread.sleep(5);
      assertEquals(1, hold.snapshot().waiting());
      assertFalse(waiting.isDone());
      minute.set(600);
      assertTrue(waiting.get(2, TimeUnit.SECONDS));
    } finally { hold.setPolicy(false, null, null, null, false); waiting.get(2, TimeUnit.SECONDS); }
  }
}
