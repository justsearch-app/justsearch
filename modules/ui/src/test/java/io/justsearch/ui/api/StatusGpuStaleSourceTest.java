/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static io.justsearch.ui.api.StatusGpuReadingAgeTest.CACHE_WINDOW_MS;
import static io.justsearch.ui.api.StatusGpuReadingAgeTest.PERIOD_MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * b82 L1, the bound on the real status path: counting a GPU reading's age at publish in the
 * gpu-memory lane's cadence must not hide a stale source. A reading already older than the
 * allowance (three periods) when it is published is stale, even after a slow read that earns the
 * lane an allowance.
 */
final class StatusGpuStaleSourceTest {
  @TempDir Path indexBase;

  @Test
  @Timeout(60)
  void aSourceReadingOlderThanTheCapIsStale() throws Exception {
    long cap = StatusLifecycleHandler.SAMPLE_STALE_PERIODS * PERIOD_MS;
    long readingAgeMs = cap + 1_000L;
    var clock = new AtomicLong(System.currentTimeMillis());
    // A slow read (two periods, inside the allowance) of a reading that is already 7 s old.
    var handler = StatusGpuReadingAgeTest.busyHandler(indexBase, clock,
        now -> clock.addAndGet(2L * PERIOD_MS) - readingAgeMs);
    // Past the projection's cache window, so this pass reads the source again.
    Thread.sleep(CACHE_WINDOW_MS + 100L);
    OperatingGpuView gpu = handler.sampleAndBuildStatusSnapshot().operating().gpu();

    assertEquals(GpuMemorySource.PDH, gpu.source());
    assertTrue(gpu.ageMs() > cap, "the published reading is older than the cap: " + gpu.ageMs());
    assertTrue(gpu.stale(), "a GPU reading already " + gpu.ageMs()
        + " ms old when it is published is a stale source, older than the " + cap + " ms cap");
  }
}
