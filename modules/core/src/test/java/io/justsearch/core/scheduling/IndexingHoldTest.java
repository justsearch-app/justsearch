/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** S36.1/S36.6: admission and acknowledgment share a boundary; time uses the wall clock. */
final class IndexingHoldTest {
  // Reflection keeps this behavioral check executable on the pre-feature revision too.
  private Object hold(Clock clock) {
    return assertDoesNotThrow(() -> Class.forName("io.justsearch.core.scheduling.IndexingHold")
        .getConstructor(Clock.class).newInstance(clock), "an acknowledged indexing hold is required");
  }

  private Object call(Object target, String method) {
    return assertDoesNotThrow(() -> target.getClass().getMethod(method).invoke(target));
  }

  private void pause(Object hold, Long until) {
    assertDoesNotThrow(() -> hold.getClass().getMethod("pause", Long.class, String.class)
        .invoke(hold, until, "USER_PAUSED"));
  }

  @Test
  void pausingWaitsForEveryActiveProcessorAndRefusesNewWork() {
    Object hold = hold(Clock.systemUTC());
    Object extraction = call(hold, "tryAcquire");
    Object pictureReading = call(hold, "tryAcquire");
    Object gpl = call(hold, "tryAcquire");
    assertNotNull(extraction);
    assertNotNull(pictureReading);
    assertNotNull(gpl);
    pause(hold, null);
    assertEquals("PAUSING", call(call(hold, "snapshot"), "state").toString());
    assertNull(call(hold, "tryAcquire"), "a requested pause must immediately close admission");
    call(extraction, "close");
    call(pictureReading, "close");
    assertEquals("PAUSING", call(call(hold, "snapshot"), "state").toString(),
        "quiet extraction does not acknowledge GPL");
    call(gpl, "close");
    assertEquals("PAUSED", call(call(hold, "snapshot"), "state").toString());
    call(hold, "resume");
    Object resumed = call(hold, "tryAcquire");
    assertNotNull(resumed);
    call(resumed, "close");
  }

  @Test
  void expiredDeadlineReopensAdmissionWithoutATimerTick() {
    Object hold = hold(Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC));
    pause(hold, 1999L);
    Object resumed = call(hold, "tryAcquire");
    assertNotNull(resumed, "resuming after sleep or time off must use the current wall clock");
    call(resumed, "close");
    pause(hold, 2001L);
    assertNull(call(hold, "tryAcquire"));
    assertEquals(2001L, call(call(hold, "snapshot"), "untilEpochMs"));
    call(hold, "resume");
  }

  @Test
  void wallClockJumpThroughTheDeadlineResumesAnAlreadyHeldProcessor() {
    var now = new AtomicLong(2000L);
    Clock clock = new Clock() {
      @Override public ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(ZoneId zone) { return fixed(instant(), zone); }
      @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    Object hold = hold(clock);
    pause(hold, 3000L);
    assertNull(call(hold, "tryAcquire"));
    now.set(10_000L); // no timer callback ran while the machine was asleep/off
    Object resumed = call(hold, "tryAcquire");
    assertNotNull(resumed);
    assertEquals("RUNNING", call(call(hold, "snapshot"), "state").toString());
    call(resumed, "close");
  }
}
