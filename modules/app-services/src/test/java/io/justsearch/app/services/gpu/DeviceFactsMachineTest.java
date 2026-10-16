/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.gpu.memory.GpuAdapters;
import io.justsearch.gpu.memory.WindowsGpuMemoryReader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Spec E step E6 on this machine's real APIs: the pinned device's LUID (CUDA) is a DXGI NVIDIA
 * adapter, the GPU memory reader opened with it reads that device, and NVML finds it by the PCI bus
 * id CUDA reports. On a machine without a CUDA device nothing is pinned.
 */
@EnabledOnOs(OS.WINDOWS)
final class DeviceFactsMachineTest {

  @Test
  void thePinnedDeviceIsTheSameDeviceAcrossCudaDxgiPdhAndNvml() throws Throwable {
    DeviceFacts facts = DeviceFactsSource.process().facts();
    if (facts.status() != DeviceFacts.ProbeStatus.RAN_OK) {
      assertEquals(Optional.empty(), facts.pinned(), facts.toString());
      return;
    }
    if (facts.devices().size() == 1) assertTrue(facts.pinned().isPresent(), facts.toString());
    CudaDevice pinned = facts.pinned().orElseThrow();
    String luid = pinned.luid().orElseThrow();

    assertTrue(
        GpuAdapters.list().stream().anyMatch(a -> a.nvidiaHardware() && a.luid().equals(luid)),
        "CUDA's LUID must name a DXGI NVIDIA adapter: " + luid);

    try (WindowsGpuMemoryReader reader =
        WindowsGpuMemoryReader.open(facts.pinnedGpuDevice(), Clock.systemUTC())) {
      GpuMemorySample sample = reader.sampleNow();
      assertEquals(Optional.empty(), sample.unknownReason(), sample.toString());
      assertEquals(luid, sample.device().orElseThrow().luid());
      assertEquals(
          OptionalInt.of(pinned.ordinal()), sample.device().orElseThrow().cudaDevice(),
          "the reader reads the pinned device, so it carries its CUDA ordinal");
    }

    String busId = pinned.pciBusId().orElseThrow();
    long[] handles = nvmlHandlesByBusIdAndIndex0(busId);
    assertNotEquals(0L, handles[0]);
    if (facts.devices().size() == 1) {
      assertEquals(handles[1], handles[0],
          "NVML finds the pinned device by CUDA's PCI bus id (one GPU: device 0)");
    }
    GpuCapabilitiesService gpu = new GpuCapabilitiesService();
    assertEquals(
        gpu.snapshot().effective().totalVramBytes(),
        gpu.snapshotOn(busId).effective().totalVramBytes(),
        "admission's NVML read by bus id is the same device on one GPU (P7)");
  }

  /** In one NVML session: the handle by bus id, and device 0's handle. */
  private static long[] nvmlHandlesByBusIdAndIndex0(String busId) throws Throwable {
    return withNvml((linker, lookup, arena) -> {
      MethodHandle byBusId = linker.downcallHandle(
          lookup.find("nvmlDeviceGetHandleByPciBusId_v2").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      MethodHandle byIndex = linker.downcallHandle(
          lookup.find("nvmlDeviceGetHandleByIndex_v2").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
      int rc = (int) byBusId.invokeExact(arena.allocateFrom(busId), out);
      assertEquals(0, rc, "nvmlDeviceGetHandleByPciBusId_v2(" + busId + ")");
      long fromBusId = out.get(ValueLayout.ADDRESS, 0).address();
      assertEquals(0, (int) byIndex.invokeExact(0, out));
      return new long[] {fromBusId, out.get(ValueLayout.ADDRESS, 0).address()};
    });
  }

  private interface NvmlCall {
    long[] run(Linker linker, SymbolLookup lookup, Arena arena) throws Throwable;
  }

  private static long[] withNvml(NvmlCall call) throws Throwable {
    String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup =
          SymbolLookup.libraryLookup(Path.of(root, "System32", "nvml.dll"), arena);
      Linker linker = Linker.nativeLinker();
      MethodHandle init = linker.downcallHandle(
          lookup.find("nvmlInit_v2").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
      MethodHandle shutdown = linker.downcallHandle(
          lookup.find("nvmlShutdown").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
      assertEquals(0, (int) init.invokeExact());
      try {
        return call.run(linker, lookup, arena);
      } finally {
        int ignored = (int) shutdown.invokeExact();
      }
    }
  }
}
