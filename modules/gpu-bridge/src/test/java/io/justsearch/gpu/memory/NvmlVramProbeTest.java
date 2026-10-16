/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemorySource;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** Spec B §2.6: the NVML probe reads device 0 only when nothing else could be meant. */
final class NvmlVramProbeTest {
  private static final long GIB = 1024L * 1024 * 1024;
  private static final GpuDevice DEVICE_0 =
      new GpuDevice(GpuDevice.luidOf(0, 0x1234), OptionalInt.of(0));

  @Test
  void readsAdapterUsedBytesOfTheOneDevice() {
    var probe = new NvmlVramProbe(
        new WindowsGpuMemoryReaderTest.FakeNvml(new NvmlSource.Reading(1, 12 * GIB, 9 * GIB)));
    assertEquals(GpuMemorySource.NVML, probe.source());
    assertEquals(OptionalLong.of(9 * GIB), probe.adapterUsedBytes(Optional.of(DEVICE_0)));
    assertEquals(OptionalLong.of(9 * GIB), probe.adapterUsedBytes(Optional.empty()));
  }

  @Test
  void anythingNvmlCannotMapIsNoReading() {
    var two = new NvmlVramProbe(
        new WindowsGpuMemoryReaderTest.FakeNvml(new NvmlSource.Reading(2, 12 * GIB, GIB)));
    assertEquals(OptionalLong.empty(), two.adapterUsedBytes(Optional.empty()));

    var one = new NvmlVramProbe(
        new WindowsGpuMemoryReaderTest.FakeNvml(new NvmlSource.Reading(1, 12 * GIB, GIB)));
    assertEquals(OptionalLong.empty(), one.adapterUsedBytes(Optional.of(
        new GpuDevice(GpuDevice.luidOf(0, 0x1234), OptionalInt.of(1)))));

    var failing = new NvmlVramProbe(new WindowsGpuMemoryReaderTest.FakeNvml(null));
    assertEquals(OptionalLong.empty(), failing.adapterUsedBytes(Optional.of(DEVICE_0)));
  }
}
