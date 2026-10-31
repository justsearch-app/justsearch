/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ThroughputWindowTest {
  @Test
  void frequentPollingMaturesAndRetentionStaysBounded() throws Exception {
    AtomicLong clock = new AtomicLong();
    var monitor = new OperationalMetrics.ThroughputMonitor(clock::get);
    for (int ms = 0; ms <= 360_000; ms += 10) {
      clock.set(ms);
      monitor.recordSample(ms / 100);
    }
    assertEquals("HEALTHY", monitor.compute(1).state());
    assertEquals(10.0, monitor.compute(1).docsPerSec(), 0.01);
    var field = monitor.getClass().getDeclaredField("samples");
    field.setAccessible(true);
    assertTrue(((java.util.Collection<?>) field.get(monitor)).size() <= 182);
  }

  @Test
  void continuousHistoryKeepsVerdictsAndRespondsToRecentWork() {
    AtomicLong clock = new AtomicLong();
    var monitor = new OperationalMetrics.ThroughputMonitor(clock::get);
    for (int sec = 0; sec <= 900; sec += 10) {
      clock.set(sec * 1000L);
      monitor.recordSample(Math.min(sec, 600) * 10L);
      if (sec >= 180) assertTrue(!monitor.compute(1).state().equals("UNKNOWN"));
    }
    assertEquals("STALLED", monitor.compute(1).state());
    assertEquals("HEALTHY", monitor.compute(0).state());
  }

  @Test
  void gapResetsEvenWhenSeveralSamplesArriveBeforeCompute() {
    AtomicLong clock = new AtomicLong();
    var monitor = new OperationalMetrics.ThroughputMonitor(clock::get);
    monitor.recordSample(0);
    clock.set(180_000);
    monitor.recordSample(1800);
    assertEquals("HEALTHY", monitor.compute(1).state());
    for (int sec = 900; sec <= 920; sec += 10) {
      clock.set(sec * 1000L);
      monitor.recordSample(1800);
    }
    assertEquals("UNKNOWN", monitor.compute(1).state());
    clock.set(1_080_000);
    monitor.recordSample(1800);
    assertEquals("STALLED", monitor.compute(1).state());
    monitor.reset();
    assertEquals("UNKNOWN", monitor.compute(1).state());
  }
}
