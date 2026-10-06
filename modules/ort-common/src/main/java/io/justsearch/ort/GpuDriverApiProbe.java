/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driver-API CUDA capability probe. Loads {@code nvcuda.dll} (Windows) / {@code libcuda.so.1}
 * (Linux) — the system-resident CUDA driver — and asks it whether at least one CUDA device is
 * visible. This answers the actual question "can this machine run any CUDA workload" rather than
 * the proxy questions ({@code nvidia-smi.exe} on PATH, ORT EP DLLs at a dev-tree path, etc.).
 *
 * <p>Companion to {@link io.justsearch.gpu.NvmlService} (NVML — diagnostics, VRAM size). NVML
 * success does not imply CUDA functional (PyTorch docs explicitly warn). The driver API is the
 * canonical "is CUDA available" check used by ggml-cuda, PyTorch, ORT internally.
 *
 * <p>Tempdoc 374 sandbox round 2 finding #4: replaces {@link GpuAutoDetection}'s filesystem
 * sentinel-DLL check (broken for every production install — dev-tree path doesn't exist).
 *
 * <p>Spec E section 5.3 (device pinning): for each ordinal it also reads the device's LUID, UUID,
 * PCI bus id and total memory ({@code cuDeviceGet}, {@code cuDeviceGetLuid}, {@code
 * cuDeviceGetUuid_v2}, {@code cuDeviceGetPCIBusId}, {@code cuDeviceTotalMem_v2}). The ordinals are
 * this process's CUDA ordinals, so they are the ones ORT uses in the same process.
 */
public final class GpuDriverApiProbe {

  private static final Logger LOG = LoggerFactory.getLogger(GpuDriverApiProbe.class);

  private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;

  /** The code reported when the driver library itself is absent (no CUresult exists). */
  public static final int NO_DRIVER_LIBRARY = -1;

  /**
   * How the probe ended.
   *
   * <ul>
   *   <li>{@code OK}: the driver answered with at least one device.
   *   <li>{@code NO_DEVICE}: the driver library is absent ({@link #NO_DRIVER_LIBRARY}), or it
   *       answered with an error code or zero devices; {@link Result#code()} carries the CUresult
   *       (0 for a count of zero).
   *   <li>{@code FAILED}: the probe itself failed (binding or invocation threw).
   * </ul>
   */
  public enum Status {
    OK,
    NO_DEVICE,
    FAILED
  }

  /**
   * One CUDA device, by this process's ordinal. Each identity field is empty when the driver did
   * not give it (no LUID on Linux or for a TCC device, an older driver without the call).
   *
   * @param luid the LUID in PDH instance form, {@code luid_0x<high>_0x<low>}, lower case
   * @param uuid the UUID in nvidia-smi form, {@code GPU-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}
   * @param pciBusId the PCI bus id, {@code domain:bus:device.function}
   * @param totalBytes total device memory, or 0 when unknown
   */
  public record Device(
      int ordinal,
      Optional<String> luid,
      Optional<String> uuid,
      Optional<String> pciBusId,
      long totalBytes) {
    public Device {
      Objects.requireNonNull(luid, "luid");
      Objects.requireNonNull(uuid, "uuid");
      Objects.requireNonNull(pciBusId, "pciBusId");
    }
  }

  /**
   * Probe result. {@code available} is true iff {@code cuInit} succeeded AND
   * {@code cuDeviceGetCount} returned ≥ 1. {@code devices} lists them by ordinal (empty unless
   * available).
   */
  public record Result(
      boolean available,
      int deviceCount,
      int driverVersion,
      String reason,
      Status status,
      int code,
      List<Device> devices) {
    public Result {
      Objects.requireNonNull(status, "status");
      devices = List.copyOf(devices);
    }

    static Result noDevice(int code, int driverVersion, String reason) {
      return new Result(false, 0, driverVersion, reason, Status.NO_DEVICE, code, List.of());
    }

    static Result failed(String reason) {
      return new Result(false, 0, 0, reason, Status.FAILED, 0, List.of());
    }
  }

  private GpuDriverApiProbe() {}

  /**
   * Probes for CUDA driver API availability. Returns a structured result; never throws.
   *
   * <p>On Windows, looks for {@code nvcuda.dll} in the standard DLL search path (System32 when
   * a CUDA-capable NVIDIA driver is installed). On Linux, looks for {@code libcuda.so.1}.
   */
  public static Result probe() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String lib = os.contains("windows") ? "nvcuda" : "cuda";

    SymbolLookup lookup;
    try (Arena arena = Arena.ofConfined()) {
      try {
        lookup = SymbolLookup.libraryLookup(lib, arena);
      } catch (IllegalArgumentException e) {
        LOG.debug("CUDA driver library '{}' not found: {}", lib, e.getMessage());
        return Result.noDevice(NO_DRIVER_LIBRARY, 0, "driver library not found: " + lib);
      } catch (Throwable t) {
        LOG.debug("CUDA driver library '{}' load failed: {}", lib, t.getMessage());
        return Result.failed("driver library load failed: " + safeMsg(t));
      }

      Linker linker = Linker.nativeLinker();
      MethodHandle cuInit;
      MethodHandle cuDeviceGetCount;
      MethodHandle cuDriverGetVersion;
      try {
        // CUresult cuInit(unsigned int Flags);
        cuInit = linker.downcallHandle(
            lookup.find("cuInit").orElseThrow(),
            FunctionDescriptor.of(I32, I32));
        // CUresult cuDeviceGetCount(int *count);
        cuDeviceGetCount = linker.downcallHandle(
            lookup.find("cuDeviceGetCount").orElseThrow(),
            FunctionDescriptor.of(I32, ValueLayout.ADDRESS));
        // CUresult cuDriverGetVersion(int *driverVersion); (best-effort, optional)
        cuDriverGetVersion = lookup.find("cuDriverGetVersion")
            .map(addr -> linker.downcallHandle(addr,
                FunctionDescriptor.of(I32, ValueLayout.ADDRESS)))
            .orElse(null);
      } catch (Throwable t) {
        LOG.debug("Failed to bind CUDA driver-API entrypoints: {}", safeMsg(t));
        return Result.failed("entrypoint binding failed: " + safeMsg(t));
      }

      int initRc;
      try {
        initRc = (int) cuInit.invokeExact(0);
      } catch (Throwable t) {
        return Result.failed("cuInit invocation failed: " + safeMsg(t));
      }
      if (initRc != 0) {
        return Result.noDevice(initRc, 0, "cuInit returned " + initRc);
      }

      int deviceCount;
      try {
        MemorySegment countBuf = arena.allocate(I32);
        int rc = (int) cuDeviceGetCount.invokeExact(countBuf);
        if (rc != 0) {
          return Result.noDevice(rc, 0, "cuDeviceGetCount returned " + rc);
        }
        deviceCount = countBuf.get(I32, 0);
      } catch (Throwable t) {
        return Result.failed("cuDeviceGetCount invocation failed: " + safeMsg(t));
      }

      int driverVersion = 0;
      if (cuDriverGetVersion != null) {
        try {
          MemorySegment verBuf = arena.allocate(I32);
          int rc = (int) cuDriverGetVersion.invokeExact(verBuf);
          if (rc == 0) {
            driverVersion = verBuf.get(I32, 0);
          }
        } catch (Throwable ignored) {
          // best-effort; absence is non-fatal
        }
      }

      if (deviceCount > 0) {
        return new Result(
            true, deviceCount, driverVersion, "ok", Status.OK, 0,
            devices(new DeviceCalls(linker, lookup), arena, deviceCount));
      }
      return Result.noDevice(0, driverVersion, "cuDeviceGetCount returned 0 devices");
    }
  }

  /** Reads every ordinal's identity; a call that is missing or fails leaves its field empty. */
  private static List<Device> devices(DeviceCalls calls, Arena arena, int count) {
    List<Device> out = new ArrayList<>(count);
    for (int ordinal = 0; ordinal < count; ordinal++) {
      out.add(calls.read(arena, ordinal));
    }
    return out;
  }

  /** The per-ordinal driver calls of Spec E section 5.3, each optional. */
  private static final class DeviceCalls {
    private final MethodHandle deviceGet;
    private final MethodHandle getLuid;
    private final MethodHandle getUuid;
    private final MethodHandle totalMem;
    private final MethodHandle pciBusId;

    DeviceCalls(Linker linker, SymbolLookup lookup) {
      // CUresult cuDeviceGet(CUdevice *device, int ordinal);
      deviceGet = bind(linker, lookup, "cuDeviceGet",
          FunctionDescriptor.of(I32, ValueLayout.ADDRESS, I32));
      // CUresult cuDeviceGetLuid(char *luid, unsigned int *deviceNodeMask, CUdevice dev);
      getLuid = bind(linker, lookup, "cuDeviceGetLuid",
          FunctionDescriptor.of(I32, ValueLayout.ADDRESS, ValueLayout.ADDRESS, I32));
      // CUresult cuDeviceGetUuid_v2(CUuuid *uuid, CUdevice dev); older drivers: cuDeviceGetUuid.
      MethodHandle uuidV2 = bind(linker, lookup, "cuDeviceGetUuid_v2",
          FunctionDescriptor.of(I32, ValueLayout.ADDRESS, I32));
      getUuid = uuidV2 != null ? uuidV2 : bind(linker, lookup, "cuDeviceGetUuid",
          FunctionDescriptor.of(I32, ValueLayout.ADDRESS, I32));
      // CUresult cuDeviceTotalMem_v2(size_t *bytes, CUdevice dev);
      totalMem = bind(linker, lookup, "cuDeviceTotalMem_v2",
          FunctionDescriptor.of(I32, ValueLayout.ADDRESS, I32));
      // CUresult cuDeviceGetPCIBusId(char *pciBusId, int len, CUdevice dev);
      pciBusId = bind(linker, lookup, "cuDeviceGetPCIBusId",
          FunctionDescriptor.of(I32, ValueLayout.ADDRESS, I32, I32));
    }

    Device read(Arena arena, int ordinal) {
      Optional<Integer> handle = Optional.empty();
      if (deviceGet != null) {
        try {
          MemorySegment out = arena.allocate(I32);
          if ((int) deviceGet.invokeExact(out, ordinal) == 0) handle = Optional.of(out.get(I32, 0));
        } catch (Throwable t) {
          LOG.debug("cuDeviceGet({}) failed: {}", ordinal, safeMsg(t));
        }
      }
      if (handle.isEmpty()) {
        return new Device(ordinal, Optional.empty(), Optional.empty(), Optional.empty(), 0L);
      }
      int dev = handle.get();
      return new Device(ordinal, luid(arena, dev), uuid(arena, dev), busId(arena, dev),
          total(arena, dev));
    }

    private Optional<String> luid(Arena arena, int dev) {
      if (getLuid == null) return Optional.empty();
      try {
        MemorySegment luid = arena.allocate(8, 8);
        MemorySegment mask = arena.allocate(I32);
        if ((int) getLuid.invokeExact(luid, mask, dev) != 0) return Optional.empty();
        // LUID { DWORD LowPart; LONG HighPart; }
        return Optional.of(luidOf(luid.get(I32, 4), luid.get(I32, 0)));
      } catch (Throwable t) {
        LOG.debug("cuDeviceGetLuid failed: {}", safeMsg(t));
        return Optional.empty();
      }
    }

    private Optional<String> uuid(Arena arena, int dev) {
      if (getUuid == null) return Optional.empty();
      try {
        MemorySegment uuid = arena.allocate(16);
        if ((int) getUuid.invokeExact(uuid, dev) != 0) return Optional.empty();
        return Optional.of(uuidOf(uuid.toArray(ValueLayout.JAVA_BYTE)));
      } catch (Throwable t) {
        LOG.debug("cuDeviceGetUuid failed: {}", safeMsg(t));
        return Optional.empty();
      }
    }

    private Optional<String> busId(Arena arena, int dev) {
      if (pciBusId == null) return Optional.empty();
      try {
        MemorySegment buf = arena.allocate(64);
        if ((int) pciBusId.invokeExact(buf, 64, dev) != 0) return Optional.empty();
        String id = buf.getString(0).trim();
        return id.isEmpty() ? Optional.empty() : Optional.of(id);
      } catch (Throwable t) {
        LOG.debug("cuDeviceGetPCIBusId failed: {}", safeMsg(t));
        return Optional.empty();
      }
    }

    private long total(Arena arena, int dev) {
      if (totalMem == null) return 0L;
      try {
        MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG);
        if ((int) totalMem.invokeExact(out, dev) != 0) return 0L;
        return Math.max(0L, out.get(ValueLayout.JAVA_LONG, 0));
      } catch (Throwable t) {
        LOG.debug("cuDeviceTotalMem_v2 failed: {}", safeMsg(t));
        return 0L;
      }
    }

    private static MethodHandle bind(
        Linker linker, SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
      try {
        return lookup.find(symbol).map(addr -> linker.downcallHandle(addr, descriptor)).orElse(null);
      } catch (RuntimeException e) {
        LOG.debug("CUDA driver entrypoint {} not bound: {}", symbol, e.getMessage());
        return null;
      }
    }
  }

  /** The LUID halves in PDH instance form, the form the GPU memory reader matches on. */
  static String luidOf(int highPart, int lowPart) {
    return String.format(Locale.ROOT, "luid_0x%08x_0x%08x", highPart, lowPart);
  }

  /** A 16-byte CUuuid in nvidia-smi form, the form {@code CUDA_VISIBLE_DEVICES} accepts. */
  static String uuidOf(byte[] bytes) {
    if (bytes.length != 16) throw new IllegalArgumentException("a CUDA UUID has 16 bytes");
    StringBuilder out = new StringBuilder("GPU-");
    for (int i = 0; i < 16; i++) {
      if (i == 4 || i == 6 || i == 8 || i == 10) out.append('-');
      out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
    }
    return out.toString();
  }

  private static String safeMsg(Throwable t) {
    String m = t.getMessage();
    return m != null ? m : t.getClass().getSimpleName();
  }
}
