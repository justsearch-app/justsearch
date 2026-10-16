/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.UnknownReason;
import io.justsearch.app.api.gpu.GpuMemorySource;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The native sources on this machine. Windows only (skipped elsewhere). Reads counters and lists
 * adapters; creates no device and does no GPU work.
 */
@EnabledOnOs(OS.WINDOWS)
@DisplayName("GPU memory reader on this machine")
final class WindowsGpuMemoryReaderMachineTest {

  @Test
  @DisplayName("a reading is PDH-known on one NVIDIA adapter, otherwise a named UNKNOWN; never throws")
  void readsThisMachine() {
    List<AdapterSource.Adapter> adapters;
    try (DxgiAdapterSource dxgi = new DxgiAdapterSource()) {
      adapters = dxgi.adapters();
    }
    assertFalse(adapters.isEmpty(), "DXGI lists at least the Basic Render Driver");
    long nvidia = adapters.stream().filter(AdapterSource.Adapter::nvidiaHardware).count();

    try (WindowsGpuMemoryReader reader = WindowsGpuMemoryReader.open(Optional.empty(), Clock.systemUTC())) {
      GpuMemorySample first = reader.sampleNow();
      GpuMemorySample second = reader.sampleNow();
      if (nvidia == 0) {
        assertEquals(Optional.of(UnknownReason.NO_NVIDIA_ADAPTER), first.unknownReason());
      } else if (nvidia > 1) {
        assertEquals(Optional.of(UnknownReason.UNMAPPED_MULTI_GPU), first.unknownReason());
      } else {
        assertEquals(GpuMemorySource.PDH, first.source(), () -> "first: " + first);
        assertEquals(GpuMemorySource.PDH, second.source(), "the kept query reads again");
        assertTrue(first.adapterTotalBytes().getAsLong() > 0);
        assertTrue(first.adapterUsedBytes().getAsLong() <= first.adapterTotalBytes().getAsLong());
      }
      reader.close();
      assertEquals(Optional.of(UnknownReason.CLOSED), reader.sampleNow().unknownReason());
    }
  }

  @Test
  @DisplayName("PDH instance names carry the DXGI LUIDs (case-insensitively)")
  void pdhMatchesDxgi() throws Exception {
    Set<String> luids;
    try (DxgiAdapterSource dxgi = new DxgiAdapterSource()) {
      luids =
          dxgi.adapters().stream()
              .filter(a -> !a.software())
              .map(AdapterSource.Adapter::luid)
              .collect(java.util.stream.Collectors.toSet());
    }
    assumeTrue(!luids.isEmpty(), "no hardware adapter on this machine");
    try (PdhCounterSource pdh = new PdhCounterSource()) {
      CounterSource.Counters counters = pdh.collect();
      assertTrue(
          counters.adapterDedicated().keySet().stream()
              .anyMatch(name -> luids.stream().anyMatch(l -> name.toLowerCase(java.util.Locale.ROOT).startsWith(l))),
          () -> "PDH adapters " + counters.adapterDedicated().keySet() + " vs DXGI " + luids);
    }
  }

  @Test
  @DisplayName("the NVML fallback reads the single NVIDIA adapter when PDH is unavailable")
  void nvmlFallbackOnThisMachine() {
    List<AdapterSource.Adapter> nvidia;
    try (DxgiAdapterSource dxgi = new DxgiAdapterSource()) {
      nvidia = dxgi.adapters().stream().filter(AdapterSource.Adapter::nvidiaHardware).toList();
    }
    assumeTrue(nvidia.size() == 1, "needs exactly one NVIDIA adapter");
    CounterSource failing =
        new CounterSource() {
          @Override
          public Counters collect() {
            throw new IllegalStateException("PDH disabled for this test");
          }

          @Override
          public void close() {}
        };
    try (WindowsGpuMemoryReader reader =
        new WindowsGpuMemoryReader(
            true, new DxgiAdapterSource(), failing, new NvmlMemorySource(), Optional.empty(), Clock.systemUTC())) {
      GpuMemorySample sample = reader.sampleNow();
      assertEquals(GpuMemorySource.NVML, sample.source(), () -> "sample: " + sample);
      assertTrue(sample.adapterTotalBytes().getAsLong() > 0);
      assertTrue(sample.processes().isEmpty());
    }
  }
}
