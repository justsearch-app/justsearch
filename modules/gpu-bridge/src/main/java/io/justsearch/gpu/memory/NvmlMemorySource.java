/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Reads device 0's memory through NVML. NVML is initialized on the first read and shut down on
 * {@link #close()}. Unlike {@code NvmlService}, which probes once at startup, this is a sampler.
 */
final class NvmlMemorySource implements NvmlSource {

  private Arena library;
  private MethodHandle shutdown;
  private MethodHandle deviceCount;
  private MethodHandle handleByIndex;
  private MethodHandle memoryInfo;
  private boolean closed;

  @Override
  public synchronized Reading read() {
    if (closed) throw new IllegalStateException("NVML source closed");
    try (Arena arena = Arena.ofConfined()) {
      if (library == null) initialize();
      MemorySegment countOut = arena.allocate(JAVA_INT);
      int status = (int) deviceCount.invokeExact(countOut);
      if (status != 0) throw NativeCalls.status("nvmlDeviceGetCount_v2", status);
      int count = countOut.get(JAVA_INT, 0);
      if (count <= 0) return new Reading(count, 0, 0);
      MemorySegment deviceOut = arena.allocate(ADDRESS);
      status = (int) handleByIndex.invokeExact(0, deviceOut);
      if (status != 0) throw NativeCalls.status("nvmlDeviceGetHandleByIndex_v2", status);
      // nvmlMemory_t: unsigned long long total, free, used.
      MemorySegment memory = arena.allocate(3 * JAVA_LONG.byteSize(), 8);
      status = (int) memoryInfo.invokeExact(deviceOut.get(ADDRESS, 0), memory);
      if (status != 0) throw NativeCalls.status("nvmlDeviceGetMemoryInfo", status);
      return new Reading(count, memory.get(JAVA_LONG, 0), memory.get(JAVA_LONG, 16));
    } catch (Throwable t) {
      throw NativeCalls.failure("NVML read", t);
    }
  }

  private void initialize() throws Throwable {
    Arena loaded = Arena.ofShared();
    try {
      SymbolLookup nvml = NativeCalls.systemLibrary("nvml.dll", loaded);
      MethodHandle init = NativeCalls.downcall(nvml, "nvmlInit_v2", FunctionDescriptor.of(JAVA_INT));
      MethodHandle nvmlShutdown = NativeCalls.downcall(nvml, "nvmlShutdown", FunctionDescriptor.of(JAVA_INT));
      MethodHandle count =
          NativeCalls.downcall(nvml, "nvmlDeviceGetCount_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS));
      MethodHandle handle =
          NativeCalls.downcall(
              nvml, "nvmlDeviceGetHandleByIndex_v2", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
      MethodHandle memory =
          NativeCalls.downcall(
              nvml, "nvmlDeviceGetMemoryInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      int status = (int) init.invokeExact();
      if (status != 0) throw NativeCalls.status("nvmlInit_v2", status);
      shutdown = nvmlShutdown;
      deviceCount = count;
      handleByIndex = handle;
      memoryInfo = memory;
      library = loaded;
    } catch (Throwable t) {
      loaded.close();
      throw t;
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (library == null) return;
    try {
      int ignored = (int) shutdown.invokeExact();
    } catch (Throwable t) {
      if (t instanceof VirtualMachineError error) throw error;
    } finally {
      library.close();
      library = null;
    }
  }
}
