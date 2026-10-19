/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import io.justsearch.configuration.NativeTargets;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * v3: Minimal NVML probe (best-effort).
 *
 * <p>Design goals:
 * <ul>
 *   <li>Prefer loading NVML by absolute path (Windows System32).</li>
 *   <li>Never throw to callers; return a structured error snapshot.</li>
 *   <li>Query only what we need for v3 gating and diagnostics.</li>
 * </ul>
 */
public final class NvmlService {
  private static final Logger LOG = LoggerFactory.getLogger(NvmlService.class);

  private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
  private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
  private static final java.lang.foreign.AddressLayout ADDRESS = ValueLayout.ADDRESS;

  private static final MemoryLayout MEMORY_INFO_LAYOUT =
      MemoryLayout.structLayout(
          I64.withName("total"),
          I64.withName("free"),
          I64.withName("used"));

  private static final MemoryLayout UTILIZATION_LAYOUT =
      MemoryLayout.structLayout(
          I32.withName("gpu"),
          I32.withName("memory"));

  public GpuCapabilities.Nvml probe() {
    return probe(null);
  }

  /**
   * Probes the device with this PCI bus id ({@code domain:bus:device.function}, as {@code
   * cuDeviceGetPCIBusId} gives it), so the memory figures are the device the Engine computes on
   * (Spec E section 3.2 / 5.3). Null, or a bus id NVML does not know, reads device 0 as before; on
   * one GPU both are the same device.
   */
  public GpuCapabilities.Nvml probe(String pciBusId) {
    Path preferred = io.justsearch.configuration.PlatformPaths.isWindows() ? preferredNvmlPath() : null;
    String attemptedPath = preferred != null ? preferred.toString() : null;
    String loadedPath = null;

    try {
      if (preferred != null && Files.isRegularFile(preferred)) {
        System.load(preferred.toString());
        loadedPath = preferred.toString();
      } else {
        // Best-effort fallback: rely on DLL search path.
        if (io.justsearch.configuration.PlatformPaths.isWindows()) {
          System.loadLibrary("nvml");
          loadedPath = "nvml";
        } else {
          loadedPath = NativeTargets.current().libraryName("nvml");
          // libraryLookup accepts the SONAME; System.loadLibrary would add lib/.so again.
        }
      }
    } catch (Throwable t) {
      return new GpuCapabilities.Nvml(
          false,
          attemptedPath,
          loadedPath,
          "Failed to load "
              + (io.justsearch.configuration.PlatformPaths.isWindows() ? "nvml.dll" : "NVML")
              + ": " + safeMsg(t),
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    try (Arena arena = Arena.ofConfined()) {
      Linker linker = Linker.nativeLinker();
      SymbolLookup lookup = SymbolLookup.libraryLookup(loadedPath, arena);

      MethodHandle nvmlInit = downcall(linker, lookup, "nvmlInit_v2", FunctionDescriptor.of(I32));
      MethodHandle nvmlShutdown = downcall(linker, lookup, "nvmlShutdown", FunctionDescriptor.of(I32));
      MethodHandle nvmlErrorString =
          downcall(linker, lookup, "nvmlErrorString", FunctionDescriptor.of(ADDRESS, I32));
      MethodHandle getDriverVersion =
          downcall(linker, lookup, "nvmlSystemGetDriverVersion", FunctionDescriptor.of(I32, ADDRESS, I32));
      MethodHandle getCount =
          downcall(linker, lookup, "nvmlDeviceGetCount_v2", FunctionDescriptor.of(I32, ADDRESS));
      MethodHandle getHandle =
          downcall(linker, lookup, "nvmlDeviceGetHandleByIndex_v2", FunctionDescriptor.of(I32, I32, ADDRESS));
      MethodHandle getMemInfo =
          downcall(linker, lookup, "nvmlDeviceGetMemoryInfo", FunctionDescriptor.of(I32, ADDRESS, ADDRESS));

      int initRc;
      try {
        initRc = (int) nvmlInit.invokeExact();
      } catch (Throwable t) {
        return new GpuCapabilities.Nvml(
            false,
            attemptedPath,
            loadedPath,
            "nvmlInit_v2 invocation failed: " + safeMsg(t),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
      }
      if (initRc != 0) {
        return new GpuCapabilities.Nvml(
            false,
            attemptedPath,
            loadedPath,
            "nvmlInit_v2 failed: " + errorStringBestEffort(nvmlErrorString, initRc),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
      }

      String driverVersion = null;
      Integer driverMajor = null;
      Integer driverMinor = null;
      Integer deviceCount = null;
      Long total = null;
      Long free = null;
      Long used = null;
      Integer gpuUtilization = null;
      Integer memoryUtilization = null;

      try {
        // Driver version
        MemorySegment driverBuf = arena.allocate(256);
        int drc = (int) getDriverVersion.invokeExact(driverBuf, 256);
        if (drc == 0) {
          driverVersion = driverBuf.getString(0);
          int[] parsed = DriverVersionParser.parse(driverVersion);
          if (parsed != null) {
            driverMajor = parsed[0];
            driverMinor = parsed[1];
          }
        }

        // Device count
        MemorySegment countOut = arena.allocate(I32);
        int crc = (int) getCount.invokeExact(countOut);
        if (crc == 0) {
          deviceCount = countOut.get(I32, 0);
        }

        // The pinned device's (else the first device's) memory and utilization
        if (deviceCount != null && deviceCount > 0) {
          MemorySegment deviceOut = arena.allocate(ADDRESS);
          int hrc = pciBusId == null ? -1 : handleByBusId(linker, lookup, arena, pciBusId, deviceOut);
          if (hrc != 0) hrc = (int) getHandle.invokeExact(0, deviceOut);
          if (hrc == 0) {
            MemorySegment device = deviceOut.get(ADDRESS, 0);

            // Memory info
            MemorySegment memOut = arena.allocate(MEMORY_INFO_LAYOUT);
            int mrc = (int) getMemInfo.invokeExact(device, memOut);
            if (mrc == 0) {
              total = memOut.get(I64, 0);
              free = memOut.get(I64, 8);
              used = memOut.get(I64, 16);
            }

            // Utilization rates (best-effort: optional API, may fail on older drivers)
            try {
              MethodHandle getUtilization =
                  downcall(linker, lookup, "nvmlDeviceGetUtilizationRates", FunctionDescriptor.of(I32, ADDRESS, ADDRESS));
              MemorySegment utilOut = arena.allocate(UTILIZATION_LAYOUT);
              int urc = (int) getUtilization.invokeExact(device, utilOut);
              if (urc == 0) {
                gpuUtilization = utilOut.get(I32, 0);
                memoryUtilization = utilOut.get(I32, 4);
              }
            } catch (Throwable utilErr) {
              // Best-effort: utilization API may not be available
              LOG.debug("NVML utilization query not available: {}", safeMsg(utilErr));
            }
          }
        }

      } catch (Throwable t) {
        LOG.debug("NVML probe failed after init (best-effort): {}", safeMsg(t));
        return new GpuCapabilities.Nvml(
            false,
            attemptedPath,
            loadedPath,
            "NVML probe failed: " + safeMsg(t),
            driverVersion,
            driverMajor,
            driverMinor,
            deviceCount,
            total,
            free,
            used,
            gpuUtilization,
            memoryUtilization);
      } finally {
        try {
          nvmlShutdown.invokeExact();
        } catch (Throwable t) {
          // best-effort
        }
      }

      return new GpuCapabilities.Nvml(
          true,
          attemptedPath,
          loadedPath,
          null,
          driverVersion,
          driverMajor,
          driverMinor,
          deviceCount,
          total,
          free,
          used,
          gpuUtilization,
          memoryUtilization);
    } catch (Throwable t) {
      return new GpuCapabilities.Nvml(
          false,
          attemptedPath,
          loadedPath,
          "NVML lookup/binding failed: " + safeMsg(t),
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }
  }

  /** {@code nvmlDeviceGetHandleByPciBusId_v2}; non-zero (and a debug line) when it cannot. */
  private static int handleByBusId(
      Linker linker, SymbolLookup lookup, Arena arena, String pciBusId, MemorySegment deviceOut) {
    try {
      MethodHandle byBusId =
          downcall(linker, lookup, "nvmlDeviceGetHandleByPciBusId_v2",
              FunctionDescriptor.of(I32, ADDRESS, ADDRESS));
      int rc = (int) byBusId.invokeExact(arena.allocateFrom(pciBusId), deviceOut);
      if (rc != 0) LOG.debug("NVML has no device at PCI bus id {} (rc={}); reading device 0", pciBusId, rc);
      return rc;
    } catch (Throwable t) {
      LOG.debug("NVML lookup by PCI bus id {} failed; reading device 0: {}", pciBusId, safeMsg(t));
      return -1;
    }
  }

  private static MethodHandle downcall(
      Linker linker, SymbolLookup lookup, String symbol, FunctionDescriptor fd) {
    MemorySegment fn =
        lookup.find(symbol).orElseThrow(() -> new IllegalStateException("NVML symbol missing: " + symbol));
    return linker.downcallHandle(fn, fd);
  }

  private static String errorStringBestEffort(MethodHandle errorStringHandle, int rc) {
    try {
      MemorySegment ptr = (MemorySegment) errorStringHandle.invokeExact(rc);
      if (ptr == null || ptr.address() == 0) {
        return "rc=" + rc;
      }
      // NVML returns an ASCII NUL-terminated string.
      MemorySegment view = ptr.reinterpret(256);
      String msg = view.getString(0);
      if (msg == null || msg.isBlank()) {
        return "rc=" + rc;
      }
      return msg.trim() + " (rc=" + rc + ")";
    } catch (Throwable ignored) {
      return "rc=" + rc;
    }
  }


  private static Path preferredNvmlPath() {
    try {
      String root = System.getenv("SystemRoot");
      if (root == null || root.isBlank()) {
        root = "C:\\Windows";
      }
      return Path.of(root).resolve("System32").resolve(NativeTargets.current().libraryName("nvml"));
    } catch (Exception e) {
      return null;
    }
  }

  private static String safeMsg(Throwable t) {
    if (t == null) return "";
    String m = t.getMessage();
    if (m == null || m.isBlank()) {
      return t.getClass().getSimpleName();
    }
    return m;
  }
}
