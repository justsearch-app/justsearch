/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import java.util.List;
import java.util.Objects;

/** Lists the graphics adapters with their LUIDs. The native implementation is DXGI. */
interface AdapterSource extends AutoCloseable {

  /** NVIDIA's PCI vendor id. */
  int NVIDIA_VENDOR_ID = 0x10DE;

  /** Every adapter, hardware and software. Throws on any failure. */
  List<Adapter> adapters() throws Exception;

  @Override
  void close();

  /**
   * One adapter.
   *
   * @param luid the LUID in PDH instance form, {@code luid_0x<high>_0x<low>}, lower case
   * @param dedicatedBytes the adapter's dedicated video memory
   * @param software whether this is a software adapter (the Microsoft Basic Render Driver)
   */
  record Adapter(String luid, int vendorId, String name, long dedicatedBytes, boolean software) {
    public Adapter {
      Objects.requireNonNull(luid, "luid");
      Objects.requireNonNull(name, "name");
    }

    boolean nvidiaHardware() {
      return vendorId == NVIDIA_VENDOR_ID && !software;
    }
  }
}
