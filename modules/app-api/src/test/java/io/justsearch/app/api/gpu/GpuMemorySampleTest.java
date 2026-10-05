/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySample.UnknownReason;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("graphics memory sample and device")
final class GpuMemorySampleTest {

  private static final Instant AT = Instant.parse("2026-10-05T10:00:00Z");
  private static final GpuDevice DEVICE =
      new GpuDevice(GpuDevice.luidOf(0, 0x0001_2e3f), OptionalInt.of(0));

  @Test
  @DisplayName("a LUID is formatted and normalized the way PDH names its instances")
  void luidFormat() {
    assertEquals("luid_0x00000000_0x00012e3f", GpuDevice.luidOf(0, 0x12e3f));
    assertEquals("luid_0xffffffff_0x80000000", GpuDevice.luidOf(-1, Integer.MIN_VALUE));
    assertEquals("luid_0x00000000_0x0000abcd",
        new GpuDevice("LUID_0x00000000_0x0000ABCD", OptionalInt.empty()).luid());
    assertThrows(IllegalArgumentException.class,
        () -> new GpuDevice("luid_0x0_0x1", OptionalInt.empty()));
    assertThrows(IllegalArgumentException.class,
        () -> new GpuDevice(GpuDevice.luidOf(0, 1), OptionalInt.of(-1)));
  }

  @Test
  @DisplayName("UNKNOWN carries a reason and no values, and frees nothing")
  void unknownSample() {
    GpuMemorySample unknown = GpuMemorySample.unknown(UnknownReason.UNSUPPORTED_OS, AT);
    assertFalse(unknown.known());
    assertEquals(GpuMemorySource.NONE, unknown.source());
    assertEquals(OptionalLong.empty(), unknown.adapterFreeBytes());
    assertEquals(Optional.empty(), unknown.process(1L));

    assertThrows(IllegalArgumentException.class, () -> new GpuMemorySample(
        GpuMemorySource.NONE, Optional.of(UnknownReason.READ_FAILED), Optional.of(DEVICE),
        Optional.empty(), OptionalLong.of(1L), OptionalLong.of(0L), Map.of(), AT));
    assertThrows(IllegalArgumentException.class, () -> new GpuMemorySample(
        GpuMemorySource.NONE, Optional.empty(), Optional.empty(), Optional.empty(),
        OptionalLong.empty(), OptionalLong.empty(), Map.of(), AT));
  }

  @Test
  @DisplayName("a known sample names device, total and used; free is floored at zero")
  void knownSample() {
    GpuMemorySample pdh = known(GpuMemorySource.PDH, 12L << 30, 4L << 30,
        Map.of(42L, new ProcessMemory(3L << 30, 1L << 20)));
    assertTrue(pdh.known());
    assertEquals(OptionalLong.of(8L << 30), pdh.adapterFreeBytes());
    assertEquals(Optional.of(new ProcessMemory(3L << 30, 1L << 20)), pdh.process(42L));

    GpuMemorySample over = known(GpuMemorySource.NVML, 100L, 120L, Map.of());
    assertEquals(OptionalLong.of(0L), over.adapterFreeBytes());

    assertThrows(IllegalArgumentException.class, () -> new GpuMemorySample(
        GpuMemorySource.PDH, Optional.of(UnknownReason.READ_FAILED), Optional.of(DEVICE),
        Optional.empty(), OptionalLong.of(1L), OptionalLong.of(0L), Map.of(), AT));
    assertThrows(IllegalArgumentException.class, () -> new GpuMemorySample(
        GpuMemorySource.PDH, Optional.empty(), Optional.empty(), Optional.empty(),
        OptionalLong.of(1L), OptionalLong.of(0L), Map.of(), AT));
    assertThrows(IllegalArgumentException.class,
        () -> known(GpuMemorySource.PDH, 0L, 0L, Map.of()));
    assertThrows(IllegalArgumentException.class,
        () -> known(GpuMemorySource.PDH, 10L, -1L, Map.of()));
  }

  @Test
  @DisplayName("per-process values come only from PDH and are never negative")
  void processesOnlyFromPdh() {
    assertThrows(IllegalArgumentException.class, () -> known(GpuMemorySource.NVML, 10L, 1L,
        Map.of(1L, new ProcessMemory(1L, 0L))));
    assertThrows(IllegalArgumentException.class, () -> new ProcessMemory(-1L, 0L));
  }

  private static GpuMemorySample known(
      GpuMemorySource source, long total, long used, Map<Long, ProcessMemory> processes) {
    return new GpuMemorySample(source, Optional.empty(), Optional.of(DEVICE),
        Optional.of("NVIDIA GeForce RTX 4070"), OptionalLong.of(total), OptionalLong.of(used),
        processes, AT);
  }
}
