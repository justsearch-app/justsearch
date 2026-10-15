/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Task b6d536 b101: whether nvidia-smi is available is answered without starting it: from the last
 * shared reading when one was taken, otherwise from whether the tool is installed, looked up at
 * most once per five seconds. NVML supplying the memory figures does not change the answer.
 */
final class NvidiaSmiToolAvailabilityTest {
  private Instant now = Instant.parse("2026-10-14T10:00:00Z");
  private final Clock clock = new Clock() {
    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  };
  private final AtomicInteger runs = new AtomicInteger();
  private final AtomicInteger lookups = new AtomicInteger();
  private final AtomicBoolean installed = new AtomicBoolean();

  private NvidiaSmiReadings readings(NvidiaSmiReadings.Runner runner) {
    return new NvidiaSmiReadings(clock, runner, () -> {
      lookups.incrementAndGet();
      return installed.get();
    });
  }

  @Test void withoutAReadingTheInstalledToolDecidesAndNothingStarts() {
    var readings = readings(() -> {
      runs.incrementAndGet();
      return new NvidiaSmiReadings.Outcome(0, "12282, 10953, 591.59");
    });
    var detector = new VramDetector(readings);
    assertFalse(detector.isToolAvailable(), "not installed");
    installed.set(true);
    now = now.plusSeconds(5);
    for (int i = 0; i < 10; i++) assertTrue(detector.isToolAvailable(), "installed");
    assertEquals(0, runs.get(), "answering availability starts no nvidia-smi");
    assertEquals(2, lookups.get(), "the installed tool is looked up at most once per five seconds");
  }

  @Test void aTakenReadingDecides() {
    installed.set(true);
    var failing = readings(() -> {
      runs.incrementAndGet();
      throw new IOException("CreateProcess error=2");
    });
    var detector = new VramDetector(failing);
    detector.getTotalVramBytes();
    assertFalse(detector.isToolAvailable(), "a reading that could not start nvidia-smi says it is unavailable");
    var working = readings(() -> new NvidiaSmiReadings.Outcome(0, "12282, 10953, 591.59"));
    new VramDetector(working).getTotalVramBytes();
    installed.set(false);
    assertTrue(new VramDetector(working).isToolAvailable(), "a reading nvidia-smi answered says it is available");
  }

  @Test void theNvmlSectionDoesNotDecideTheToolsAvailability() {
    var readings = readings(() -> {
      runs.incrementAndGet();
      return new NvidiaSmiReadings.Outcome(0, "12282, 10953, 591.59");
    });
    NvmlService nvml = org.mockito.Mockito.mock(NvmlService.class);
    org.mockito.Mockito.when(nvml.probe()).thenReturn(new GpuCapabilities.Nvml(true, null, "test-NVML", null,
        "591.59", 591, 59, 1, 12L << 30, 10L << 30, 2L << 30, null, null));
    var service = new GpuCapabilitiesService(nvml, new VramDetector(readings));
    var section = service.snapshot().nvidiaSmi();
    assertEquals(GpuCapabilities.NvidiaSmi.SOURCE_NVML, section.source(), "NVML supplies the figures");
    assertFalse(service.nvidiaSmiToolAvailable(), "the tool itself is not installed");
    assertEquals(0, runs.get(), "no nvidia-smi starts where NVML answers");
  }
}
