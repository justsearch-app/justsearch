/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import io.justsearch.configuration.PlatformPaths;
import java.util.List;
import java.util.Objects;

/**
 * The machine's graphics adapters as DXGI lists them, for the device pinning choice (Spec E section
 * 5.2: the NVIDIA adapter with the largest dedicated memory, ties in enumeration order). The same
 * DXGI listing the GPU memory reader uses.
 */
public final class GpuAdapters {

  private GpuAdapters() {}

  /**
   * One adapter.
   *
   * @param luid the LUID in PDH instance form, {@code luid_0x<high>_0x<low>}, lower case
   * @param dedicatedBytes the adapter's dedicated video memory
   * @param software whether this is a software adapter (the Microsoft Basic Render Driver)
   */
  public record Adapter(String luid, int vendorId, long dedicatedBytes, boolean software) {
    public Adapter {
      Objects.requireNonNull(luid, "luid");
    }

    /** A hardware adapter of NVIDIA's PCI vendor id (0x10DE). */
    public boolean nvidiaHardware() {
      return vendorId == AdapterSource.NVIDIA_VENDOR_ID && !software;
    }
  }

  /**
   * Every adapter, in DXGI enumeration order. Empty on another OS. Throws when DXGI fails.
   */
  public static List<Adapter> list() throws Exception {
    if (!PlatformPaths.isWindows()) return List.of();
    try (DxgiAdapterSource source = new DxgiAdapterSource()) {
      return source.adapters().stream()
          .map(a -> new Adapter(a.luid(), a.vendorId(), a.dedicatedBytes(), a.software()))
          .toList();
    }
  }
}
