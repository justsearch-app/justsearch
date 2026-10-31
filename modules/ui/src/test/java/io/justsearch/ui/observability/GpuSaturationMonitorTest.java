package io.justsearch.ui.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 419 C3 V2 P3 — windowed state-machine cases mirroring {@code
 * OperationalMetricsTest.ThroughputMonitor} structure. Uses an injected clock supplier so
 * tests can advance time deterministically without sleeping.
 */
@DisplayName("GpuSaturationMonitor")
final class GpuSaturationMonitorTest {

  /** Mutable clock for deterministic time-advancement in tests. */
  private final AtomicLong clock = new AtomicLong(0L);

  private GpuSaturationMonitor newMonitor() {
    return new GpuSaturationMonitor(clock::get);
  }

  /** Records a sample and advances the clock by deltaMs. */
  private void recordAt(GpuSaturationMonitor m, long deltaMs, int gpuPercent) {
    m.recordSample(gpuPercent);
    clock.addAndGet(deltaMs);
  }

  @Test
  @DisplayName("UNKNOWN with fewer than 2 samples")
  void unknownWithTooFewSamples() {
    var m = newMonitor();
    assertEquals(GpuSaturationMonitor.STATE_UNKNOWN, m.compute(0).state());
    m.recordSample(95);
    assertEquals(GpuSaturationMonitor.STATE_UNKNOWN, m.compute(0).state());
  }

  @Test
  @DisplayName("UNKNOWN when window has not been reached yet")
  void unknownBeforeWindowFull() {
    var m = newMonitor();
    // Two samples 1s apart — way less than 180s window.
    recordAt(m, 1_000, 95);
    recordAt(m, 1_000, 95);
    assertEquals(GpuSaturationMonitor.STATE_UNKNOWN, m.compute(0).state());
  }

  @Test
  @DisplayName("HEALTHY when activity gate is positive (any signal active)")
  void healthyWhenActivityPresent() {
    var m = newMonitor();
    populateFullWindow(m, 95);
    var result = m.compute(1);
    assertEquals(GpuSaturationMonitor.STATE_HEALTHY, result.state());
    assertTrue(result.avgPercent() > 80);
  }

  @Test
  @DisplayName("SATURATED when activity gate is 0 and avg > threshold over the window")
  void saturatedWhenIdleAndPinned() {
    var m = newMonitor();
    populateFullWindow(m, 95);
    var result = m.compute(0);
    assertEquals(GpuSaturationMonitor.STATE_SATURATED, result.state());
    assertEquals(95.0, result.avgPercent(), 0.001);
  }

  @Test
  @DisplayName("HEALTHY when activity gate is 0 but avg below threshold")
  void healthyWhenIdleAndCool() {
    var m = newMonitor();
    populateFullWindow(m, 50);
    var result = m.compute(0);
    assertEquals(GpuSaturationMonitor.STATE_HEALTHY, result.state());
  }

  @Test
  @DisplayName("Threshold is strict greater-than: 80% exactly is HEALTHY")
  void thresholdBoundaryHealthy() {
    var m = newMonitor();
    populateFullWindow(m, 80);
    assertEquals(GpuSaturationMonitor.STATE_HEALTHY, m.compute(0).state());
  }

  @Test
  @DisplayName("MAX_GAP gap discards old samples and returns to UNKNOWN")
  void maxGapResets() {
    var m = newMonitor();
    populateFullWindow(m, 95);
    // Verify SATURATED first.
    assertEquals(GpuSaturationMonitor.STATE_SATURATED, m.compute(0).state());
    // Advance the clock by > 600s and record one new sample.
    clock.addAndGet(700_000);
    m.recordSample(95);
    // The gap reset clears the buffer; only one sample remains → UNKNOWN.
    assertEquals(GpuSaturationMonitor.STATE_UNKNOWN, m.compute(0).state());
  }

  @Test
  @DisplayName("reset() clears samples and returns to UNKNOWN")
  void resetClears() {
    var m = newMonitor();
    populateFullWindow(m, 95);
    assertEquals(GpuSaturationMonitor.STATE_SATURATED, m.compute(0).state());
    m.reset();
    assertEquals(GpuSaturationMonitor.STATE_UNKNOWN, m.compute(0).state());
  }

  @Test
  void continuousPollingRetainsRecentEvidence() throws Exception {
    var monitor = newMonitor();
    for (int ms = 0; ms <= 900_000; ms += 100) {
      clock.set(ms);
      monitor.recordSample(ms < 600_000 ? 95 : 20);
      if (ms >= 180_000) assertTrue(!monitor.compute(0).state().equals("UNKNOWN"));
    }
    assertEquals("HEALTHY", monitor.compute(0).state());
    var samples = GpuSaturationMonitor.class.getDeclaredField("samples");
    samples.setAccessible(true);
    assertTrue(((java.util.Collection<?>) samples.get(monitor)).size()
        <= io.justsearch.telemetry.RollingMonitorWindow.MAX_SAMPLES);
    clock.addAndGet(700_000);
    monitor.recordSample(95);
    clock.addAndGet(10_000);
    monitor.recordSample(95);
    assertEquals("UNKNOWN", monitor.compute(0).state());
    clock.addAndGet(170_000);
    monitor.recordSample(95);
    assertEquals("SATURATED", monitor.compute(0).state());
    assertEquals("HEALTHY", monitor.compute(1).state());
  }

  /**
   * Records 13 samples spaced 15s apart so total window is 12 * 15s = 180s = WINDOW_MS,
   * mirroring the production sampler cadence.
   */
  private void populateFullWindow(GpuSaturationMonitor m, int gpuPercent) {
    for (int i = 0; i < 13; i++) {
      recordAt(m, 15_000, gpuPercent);
    }
  }
}
