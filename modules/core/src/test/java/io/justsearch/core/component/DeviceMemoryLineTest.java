/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class DeviceMemoryLineTest {
  @Test
  void choosesBesideOnlyFromMeasuredFreeMemoryAndAppliesBothCeilingAxes() {
    var measured = new DeviceMemoryLine(12_000_000_000L, 8_000_000_000L);
    assertEquals(ComposeEvidence.Mode.BESIDE, measured.decision(7_000_000_000L).mode());

    var capped = measured.withCeilingMb(1024L);
    assertEquals(1024L * 1024L * 1024L, capped.totalBytes());
    assertEquals(1024L * 1024L * 1024L, capped.freeBytes());
    assertEquals(ComposeEvidence.Mode.IN_PLACE, capped.decision(7_000_000_000L).mode());
    assertEquals(7_000_000_000L, capped.decision(7_000_000_000L).footprintBytes());
    assertEquals(measured, measured.withCeilingMb(Long.MAX_VALUE));
  }

  @Test
  void unknownOrZeroFreeMemoryDoesNotAuthorizeCoResidentComposition() {
    assertEquals("free_device_memory_unknown",
        new DeviceMemoryLine(null, null).decision(1).reason());
    assertEquals(ComposeEvidence.Mode.BESIDE,
        new DeviceMemoryLine(null, null).decision(0).mode());
    assertEquals(ComposeEvidence.Mode.IN_PLACE,
        new DeviceMemoryLine(8_000_000_000L, 0L).decision(1).mode());
    assertThrows(IllegalArgumentException.class, () -> new DeviceMemoryLine(-1L, 0L));
    assertThrows(IllegalArgumentException.class,
        () -> new DeviceMemoryLine(1L, 1L).withCeilingMb(-1L));
  }
}
