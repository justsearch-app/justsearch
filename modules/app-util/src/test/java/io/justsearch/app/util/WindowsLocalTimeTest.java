/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class WindowsLocalTimeTest {
  @Test
  void sundayMappingAndMinuteSnapshotAreNativeLocalValues() {
    assertEquals(new WindowsLocalTime.Snapshot(7, 1439), WindowsLocalTime.fromWindows(0, 23, 59));
    assertEquals(new WindowsLocalTime.Snapshot(1, 0), WindowsLocalTime.fromWindows(1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> WindowsLocalTime.fromWindows(7, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> WindowsLocalTime.fromWindows(0, 24, 0));
  }

  @Test
  void nativeFailureFallsBackAndNextReadTriesNativeAgain() {
    var fail = new AtomicBoolean(true);
    java.util.function.Supplier<WindowsLocalTime.Snapshot> nativeRead = () -> {
      if (fail.get()) throw new IllegalStateException("native binding/invocation unavailable");
      return WindowsLocalTime.fromWindows(1, 10, 0);
    };
    var fallback = new WindowsLocalTime.Snapshot(7, 500);
    assertEquals(fallback, WindowsLocalTime.read(true, nativeRead, () -> fallback));
    fail.set(false);
    assertEquals(new WindowsLocalTime.Snapshot(1, 600), WindowsLocalTime.read(true, nativeRead, () -> fallback));
    assertEquals(fallback, WindowsLocalTime.read(false, () -> { fail("native read off Windows"); return null; }, () -> fallback));
  }
}
