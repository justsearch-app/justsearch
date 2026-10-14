/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Task b6d536 b101: the shared nvidia-smi reading, on a settable clock and a counting runner.
 * At most one probe per five seconds, never two at once; a forced reading still happens and is
 * shared; a start failure stays sticky until invalidated.
 */
final class NvidiaSmiReadingsTest {
  private Instant now = Instant.parse("2026-10-14T10:00:00Z");
  private final Clock clock = new Clock() {
    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  };
  private final AtomicInteger runs = new AtomicInteger();

  private NvidiaSmiReadings readings(String output) {
    return new NvidiaSmiReadings(clock, () -> {
      runs.incrementAndGet();
      return new NvidiaSmiReadings.Outcome(0, output);
    });
  }

  @Test void oneProbeAnswersEveryReaderForFiveSeconds() {
    var readings = readings("12282, 10953, 591.59");
    var detectors = List.of(new VramDetector(readings), new VramDetector(readings));
    for (int second = 0; second < 30; second++) {
      for (var detector : detectors) {
        detector.getTotalVramBytes();
        detector.getAvailableVramBytes();
        detector.getDriverVersion();
      }
      now = now.plusSeconds(1);
    }
    assertEquals(6, runs.get(), "30 seconds of readers: one probe per five seconds");
    assertEquals(12282L << 20, detectors.getFirst().getTotalVramBytes());
    assertEquals(10953L << 20, detectors.getFirst().getAvailableVramBytes());
    assertEquals("591.59", detectors.getFirst().getDriverVersion());
  }

  @Test void probesNeverRunConcurrentlyAndWaitingReadersShareTheAnswer() throws Exception {
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maxRunning = new AtomicInteger();
    CountDownLatch inProbe = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var readings = new NvidiaSmiReadings(clock, () -> {
      runs.incrementAndGet();
      maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
      inProbe.countDown();
      release.await(10, TimeUnit.SECONDS);
      running.decrementAndGet();
      return new NvidiaSmiReadings.Outcome(0, "8192, 4096, 560.10");
    });
    List<Thread> readers = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      readers.add(Thread.ofPlatform().start(readings::read));
    }
    assertTrue(inProbe.await(10, TimeUnit.SECONDS));
    Thread.sleep(200); // the other readers are now waiting on the probe in flight
    release.countDown();
    for (Thread reader : readers) reader.join(10_000);
    assertEquals(1, maxRunning.get(), "never two nvidia-smi processes at once");
    assertEquals(1, runs.get(), "readers waiting on a probe share its answer");
  }

  @Test void aForcedReadingHappensWithinTheIntervalAndIsShared() {
    var readings = readings("12282, 10953, 591.59");
    var detector = new VramDetector(readings);
    detector.getAvailableVramBytes();
    now = now.plusSeconds(1);
    detector.invalidateCache(); // an admission decision asks for a fresh figure
    detector.invalidateCache(); // a second decision at the same moment
    detector.getAvailableVramBytes();
    detector.getAvailableVramBytes();
    assertEquals(2, runs.get(), "one forced fresh reading, shared by the decisions that asked");
  }

  @Test void aStartFailureIsStickyUntilInvalidated() {
    var readings = new NvidiaSmiReadings(clock, () -> {
      runs.incrementAndGet();
      throw new IOException("CreateProcess error=2");
    });
    var detector = new VramDetector(readings);
    for (int i = 0; i < 5; i++) {
      assertEquals(-1, detector.getTotalVramBytes());
      now = now.plusSeconds(10);
    }
    assertEquals(1, runs.get(), "a missing nvidia-smi is not retried on every tick");
    detector.invalidateCache();
    assertEquals(-1, detector.getTotalVramBytes());
    assertEquals(2, runs.get(), "an invalidation retries once");
  }

  @Test void partialAndUnparseableOutputs() {
    var total = NvidiaSmiReadings.parse("12345", now);
    assertTrue(total.available());
    assertEquals(12345L << 20, total.totalBytes());
    assertNull(total.freeBytes());
    var unknown = NvidiaSmiReadings.parse("[N/A], [N/A], [N/A]", now);
    assertFalse(unknown.available());
    assertNull(unknown.driverVersion());
  }

  @Test void snapshotSectionFromASharedReadingNamesNvidiaSmi() {
    var shared = GpuCapabilitiesService.nvidiaSmi(NvidiaSmiReadings.parse("12288, 1024, 591.59", now));
    assertTrue(shared.available());
    assertEquals(591, shared.driverVersionMajor());
    assertEquals("12.0 GB", shared.vramDescription());
    assertEquals(GpuCapabilities.NvidiaSmi.SOURCE_NVIDIA_SMI, shared.source());
    assertEquals(java.util.Map.of(), shared.unavailable());
    var none = GpuCapabilitiesService.nvidiaSmi(null);
    assertFalse(none.available());
    assertEquals(GpuCapabilities.NvidiaSmi.SOURCE_NVIDIA_SMI, none.source());
  }
}
