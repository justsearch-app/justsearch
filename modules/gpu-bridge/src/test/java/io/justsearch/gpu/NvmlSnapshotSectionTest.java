/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Task b6d536 b101: where NVML answers in-process, the snapshot's nvidia-smi section still shows
 * the driver and memory figures, names where they were read, and says which figure NVML did not
 * give. Reads the section as {@code /api/gpu/capabilities} serializes it (record components by
 * name), so it runs unchanged on b599d4d7e, where the section had no source.
 */
final class NvmlSnapshotSectionTest {

  private static GpuCapabilitiesService service(Long freeBytes) {
    NvmlService nvml = Mockito.mock(NvmlService.class);
    var snapshot = new GpuCapabilities.Nvml(true, null, null, null, "591.59", 591, 59, 1,
        12L << 30, freeBytes, freeBytes == null ? null : (12L << 30) - freeBytes, null, null);
    Mockito.when(nvml.probe()).thenReturn(snapshot);
    Mockito.when(nvml.probe(Mockito.anyString())).thenReturn(snapshot);
    return new GpuCapabilitiesService(nvml, new VramDetector());
  }

  /** The section's fields by name, as a JSON serializer of the record sees them. */
  private static Map<String, Object> fields(Object section) throws Exception {
    Map<String, Object> out = new LinkedHashMap<>();
    for (RecordComponent component : section.getClass().getRecordComponents()) {
      out.put(component.getName(), component.getAccessor().invoke(section));
    }
    return out;
  }

  @Test void nvmlFiguresFillTheSectionAndNameTheirSource() throws Exception {
    Map<String, Object> section = fields(service(10L << 30).snapshot().nvidiaSmi());
    assertEquals("read in-process (NVML)", section.get("source"), "the section names where its figures came from: " + section);
    assertEquals(true, section.get("available"), section.toString());
    assertEquals("591.59", section.get("driverVersion"));
    assertEquals(591, section.get("driverVersionMajor"));
    assertEquals(59, section.get("driverVersionMinor"));
    assertEquals(12L << 30, section.get("totalVramBytes"));
    assertEquals(10L << 30, section.get("freeVramBytes"));
    assertEquals("12.0 GB", section.get("vramDescription"));
    assertEquals(Map.of(), section.get("unavailable"));
  }

  @Test void aFigureNvmlDidNotGiveIsNamed() throws Exception {
    Map<String, Object> section = fields(service(null).snapshot().nvidiaSmi());
    assertEquals("read in-process (NVML)", section.get("source"), section.toString());
    assertEquals(null, section.get("freeVramBytes"));
    assertTrue(section.get("unavailable") instanceof Map<?, ?> named && named.containsKey("freeVramBytes")
        && named.size() == 1, "only the missing figure is named: " + section);
  }
}
