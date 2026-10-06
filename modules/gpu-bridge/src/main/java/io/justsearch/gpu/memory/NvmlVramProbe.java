/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.runtime.child.VramProbe;
import io.justsearch.configuration.PlatformPaths;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The child-release VRAM probe of Spec B §2.6 step 2: adapter used bytes of NVIDIA device 0 through
 * NVML. NVML names no LUID, so a reading is given only when NVML sees exactly one device and the
 * caller's CUDA ordinal, when known, is 0. Anything else, and any failure, is no reading. NVML is
 * loaded on the first read.
 */
public final class NvmlVramProbe implements VramProbe {
  private final NvmlSource nvml;

  NvmlVramProbe(NvmlSource nvml) {
    this.nvml = Objects.requireNonNull(nvml, "nvml");
  }

  /** NVML on Windows; {@link VramProbe#none()} elsewhere. */
  public static VramProbe forCurrentOs() {
    return PlatformPaths.isWindows() ? new NvmlVramProbe(new NvmlMemorySource()) : VramProbe.none();
  }

  @Override
  public GpuMemorySource source() {
    return GpuMemorySource.NVML;
  }

  @Override
  public OptionalLong adapterUsedBytes(Optional<GpuDevice> device) {
    OptionalInt cuda = device.map(GpuDevice::cudaDevice).orElse(OptionalInt.empty());
    if (cuda.isPresent() && cuda.getAsInt() != 0) return OptionalLong.empty(); // NVML reads device 0
    try {
      NvmlSource.Reading reading = nvml.read();
      if (reading.deviceCount() != 1 || reading.usedBytes() < 0) return OptionalLong.empty();
      return OptionalLong.of(reading.usedBytes());
    } catch (Exception | LinkageError unreadable) {
      return OptionalLong.empty();
    }
  }
}
