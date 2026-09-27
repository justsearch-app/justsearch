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

  @Test
  void retiresTheSourceOnlyWhenItsReleaseCanMakeTheCandidateFit() {
    var line = new DeviceMemoryLine(8_000L, 1_000L);
    // Fits beside regardless of what the source holds.
    assertEquals(ComposeEvidence.Mode.BESIDE, line.decision(900L, 0L).mode());
    // Shortfall 3_000 is covered by the source's release.
    var inPlace = line.decision(4_000L, 3_000L);
    assertEquals(ComposeEvidence.Mode.IN_PLACE, inPlace.mode());
    assertEquals("candidate_fits_after_source_release", inPlace.reason());
    // Shortfall 3_000 exceeds what the source can release: never pause A for nothing.
    var refused = line.decision(4_000L, 2_999L);
    assertEquals(ComposeEvidence.Mode.REFUSED, refused.mode());
    assertEquals("candidate_exceeds_releasable_device_memory", refused.reason());
    assertEquals(4_000L, refused.footprintBytes());
    assertEquals(1_000L, refused.freeBytes());
    // A CPU-only source releases nothing.
    assertEquals(ComposeEvidence.Mode.REFUSED, line.decision(4_000L, 0L).mode());
  }

  @Test
  void unknownFreeMemoryNeverRetiresASourceThatHoldsNoDeviceMemory() {
    var unknown = new DeviceMemoryLine(null, null);
    var beside = unknown.decision(4_000L, 0L);
    assertEquals(ComposeEvidence.Mode.BESIDE, beside.mode());
    assertEquals("source_holds_no_device_memory", beside.reason());
    assertEquals(ComposeEvidence.Mode.IN_PLACE, unknown.decision(4_000L, 1L).mode());
    // Unknown source release keeps the pre-2026-09-27 unconditional fallback.
    assertEquals(ComposeEvidence.Mode.IN_PLACE,
        new DeviceMemoryLine(8_000L, 1_000L).decision(4_000L, null).mode());
    assertThrows(IllegalArgumentException.class, () -> unknown.decision(1L, -1L));
  }
}
