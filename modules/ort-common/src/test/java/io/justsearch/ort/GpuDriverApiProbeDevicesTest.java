/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Spec E step E6: the probe's per-ordinal device identity (LUID, UUID, PCI bus id, memory). */
final class GpuDriverApiProbeDevicesTest {

  @Test
  void luidIsFormattedAsPdhNamesItsInstances() {
    assertEquals("luid_0x00000000_0x0000d7a2", GpuDriverApiProbe.luidOf(0, 0xd7a2));
    assertEquals("luid_0xffffffff_0x80000001", GpuDriverApiProbe.luidOf(-1, 0x80000001));
  }

  @Test
  void uuidIsFormattedAsNvidiaSmiAndCudaVisibleDevicesName() {
    byte[] bytes = {
      (byte) 0x8b, 0x20, 0x00, 0x6f, 0x75, 0x5d, (byte) 0x8a, 0x37,
      (byte) 0x97, 0x62, 0x7b, 0x38, 0x59, 0x07, (byte) 0xe8, 0x13
    };
    assertEquals("GPU-8b20006f-755d-8a37-9762-7b385907e813", GpuDriverApiProbe.uuidOf(bytes));
  }

  /**
   * On a machine with a CUDA device every ordinal is listed with its identity; without one, none is
   * listed and the status is not OK.
   */
  @Test
  void everyOrdinalCarriesItsIdentity() {
    GpuDriverApiProbe.Result result = GpuDriverApiProbe.probe();
    if (!result.available()) {
      assertEquals(List.of(), result.devices());
      assertTrue(result.status() != GpuDriverApiProbe.Status.OK, result.toString());
      return;
    }
    assertEquals(GpuDriverApiProbe.Status.OK, result.status());
    assertEquals(result.deviceCount(), result.devices().size());
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    for (int i = 0; i < result.devices().size(); i++) {
      GpuDriverApiProbe.Device d = result.devices().get(i);
      assertEquals(i, d.ordinal());
      assertTrue(d.uuid().orElseThrow().matches(
          "GPU-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), d.toString());
      assertTrue(d.pciBusId().orElseThrow().matches(
          "[0-9A-Fa-f]+:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}\\.[0-9A-Fa-f]"), d.toString());
      assertTrue(d.totalBytes() > 0, d.toString());
      if (windows) {
        assertTrue(d.luid().orElseThrow().matches("luid_0x[0-9a-f]{8}_0x[0-9a-f]{8}"), d.toString());
      }
    }
  }
}
