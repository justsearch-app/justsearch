/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.context;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.UserContextSample;
import io.justsearch.app.api.gpu.UserContextSample.NotificationState;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Spec E E-S7d (section 4.4, step E4): {@code UserContextSignals} on the real Windows APIs. Reads the
 * shell's notification state, the effective power mode notification and the foreground window; it
 * changes nothing on the machine. Whether a game runs is the rule's business (E-U4); this proves the
 * readings arrive, are well formed, and stop after close.
 */
@EnabledOnOs(OS.WINDOWS)
final class UserContextSignalsTest {

  // the real APIs answer: a notification state, the power mode within 5 s, a live foreground owner
  @Test
  void readsTheRealUserContext() throws InterruptedException {
    try (UserContextSignals reader = UserContextSignals.open()) {
      UserContextSample sample = reader.sampleNow();
      assertNotEquals(NotificationState.UNKNOWN, sample.notificationState(),
          () -> "SHQueryUserNotificationState answered: " + sample);
      long deadline = System.nanoTime() + 5_000_000_000L;
      UserContextSample latest = sample;
      while (latest.gameMode().isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(50);
        latest = reader.sampleNow();
      }
      UserContextSample observed = latest;
      assertTrue(observed.gameMode().isPresent(),
          () -> "the effective power mode notification reported within 5 s: " + observed);
      if (observed.foregroundPid().isPresent()) {
        assertTrue(ProcessHandle.of(observed.foregroundPid().getAsLong()).isPresent(),
            () -> "the foreground window's owner is a live process: " + observed);
      } else {
        assertFalse(observed.foregroundCoversMonitor(), "no foreground window covers nothing");
      }
    }
  }

  // after close nothing is read, and a second close is harmless
  @Test
  void aClosedReaderReadsNothing() {
    UserContextSignals reader = UserContextSignals.open();
    reader.close();
    assertEquals(UserContextSample.unknown(), reader.sampleNow());
    reader.close();
  }

  // a window covers its monitor when it reaches every edge (borderless full screen does; a window
  // one pixel short, or on a monitor at a negative origin it does not reach, does not)
  @Test
  void coversNeedsEveryEdge() {
    try (Arena arena = Arena.ofConfined()) {
      assertTrue(UserContextSignals.covers(
          rect(arena, 0, 0, 2560, 1440), rect(arena, 0, 0, 2560, 1440)));
      assertTrue(UserContextSignals.covers(
          rect(arena, -8, -8, 2568, 1448), rect(arena, 0, 0, 2560, 1440)), "overhang past every edge");
      assertFalse(UserContextSignals.covers(
          rect(arena, 0, 0, 2560, 1400), rect(arena, 0, 0, 2560, 1440)), "taskbar left visible");
      assertFalse(UserContextSignals.covers(
          rect(arena, 0, 0, 1920, 1080), rect(arena, -1920, 0, 0, 1080)), "another monitor");
      assertTrue(UserContextSignals.covers(
          rect(arena, -1920, 0, 0, 1080), rect(arena, -1920, 0, 0, 1080)), "left monitor");
    }
  }

  private static MemorySegment rect(Arena arena, int left, int top, int right, int bottom) {
    MemorySegment rect = arena.allocate(16, 4);
    rect.set(JAVA_INT, 0, left);
    rect.set(JAVA_INT, 4, top);
    rect.set(JAVA_INT, 8, right);
    rect.set(JAVA_INT, 12, bottom);
    return rect;
  }
}
