/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.core.scheduling.BulkWorkGate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class GpuResidencyPidCensusTest {
  private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

  @Test
  void unsupportedTicksDoNotEnumerateProcesses() {
    assertNoCensus(GpuMemorySample.unknown(GpuMemorySample.UnknownReason.UNSUPPORTED_OS, NOW));
  }

  @Test
  void nvmlTicksDoNotEnumerateProcesses() {
    assertNoCensus(withoutProcessFigures(GpuMemorySource.NVML));
  }

  @Test
  void pdhTicksWithoutProcessFiguresDoNotEnumerateProcesses() {
    assertNoCensus(withoutProcessFigures(GpuMemorySource.PDH));
  }

  private static void assertNoCensus(GpuMemorySample sample) {
    var reader = mock(GpuMemoryReader.class);
    when(reader.sampleNow()).thenReturn(sample);
    var censusCalls = new AtomicInteger();
    try (var sampler = new GpuResidencySampler(
        reader,
        () -> {
          censusCalls.incrementAndGet();
          return Set.of(1_000L);
        },
        new BulkWorkGate(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        mock(ScheduledExecutorService.class))) {
      for (int tick = 0; tick < 3; tick++) {
        sampler.bootSample();
        assertSame(sample, sampler.latest().sample(), "the tick must publish its reading");
        assertEquals(0, censusCalls.get(), "ticks without PDH process figures need no PID census");
      }
    }
  }

  private static GpuMemorySample withoutProcessFigures(GpuMemorySource source) {
    return new GpuMemorySample(
        source, Optional.empty(),
        Optional.of(new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty())), Optional.of("RTX"),
        OptionalLong.of(12L * 1024 * 1024 * 1024), OptionalLong.of(8L * 1024 * 1024 * 1024),
        Map.of(), NOW);
  }
}
