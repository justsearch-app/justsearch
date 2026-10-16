/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.core.scheduling.BulkWorkGate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec E E-U3, the sampler rows (sections 3.2-3.4): only PDH readings with per-process figures
 * count, and only external use (adapter used minus our processes' dedicated use) can raise
 * pressure. NVML at 300 MiB free does not raise (F5); nor does our own use filling the card (CH11);
 * nor does a PDH reading without per-process figures. Raised pressure sets the gate's hold and
 * reports EXTERNAL_MEMORY to the chat controller, the clear reports NONE, and a receiver bound
 * after the raise hears it at once. UNKNOWN readings clear raised pressure after 30 s.
 */
final class GpuResidencySamplerTest {

  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * MIB;
  private static final long TOTAL = 12 * GIB;
  private static final long ENGINE = 1_000L;
  private static final long LLAMA = 2_000L;
  private static final GpuDevice DEVICE =
      new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty());
  private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

  // external use raises: the gate holds and the chat controller hears EXTERNAL_MEMORY, then NONE
  @Test
  void externalUseRaisesHoldsAndReportsThenClears() {
    var rig = new Rig(pdh(300 * MIB, 6 * GIB));
    rig.tick(2);
    assertFalse(rig.gate.held());
    rig.tick(1);
    assertTrue(rig.gate.held(), "three low PDH samples raise external pressure");
    assertEquals(List.of(BulkWorkGate.Reason.GPU_BUSY_EXTERNAL), rig.gate.verdict().reasons());
    assertEquals(List.of(PressureSignal.EXTERNAL_MEMORY), rig.signals);
    var latest = rig.sampler.latest();
    assertTrue(latest.externalPressure());
    assertEquals(6 * GIB, latest.externalBytes());
    assertEquals(TOTAL - 300 * MIB - 6 * GIB, latest.ourDedicatedBytes());

    rig.reading.set(pdh(4 * GIB, 6 * GIB));
    rig.tick(5);
    assertFalse(rig.gate.held());
    assertEquals(List.of(PressureSignal.EXTERNAL_MEMORY, PressureSignal.NONE), rig.signals);
  }

  // CH11: our own processes filling the card (300 MiB free, 500 MiB external) never raise
  @Test
  void ourOwnUseCannotRaise() {
    var rig = new Rig(pdh(300 * MIB, 500 * MIB));
    rig.tick(10);
    assertFalse(rig.gate.held());
    assertEquals(List.of(), rig.signals);
    assertEquals(500 * MIB, rig.sampler.latest().externalBytes());
  }

  // F5: an NVML reading at 300 MiB free has no per-process figures, so it does not raise
  @Test
  void nvmlAtThreeHundredMibFreeDoesNotRaise() {
    var rig = new Rig(new GpuMemorySample(GpuMemorySource.NVML, Optional.empty(),
        Optional.of(DEVICE), Optional.of("RTX"), OptionalLong.of(TOTAL),
        OptionalLong.of(TOTAL - 300 * MIB), Map.of(), T0));
    rig.tick(10);
    assertFalse(rig.gate.held());
    assertNull(rig.sampler.latest().externalBytes());
    assertEquals(List.of(), rig.signals);
  }

  // a PDH reading without per-process figures is not known for pressure
  @Test
  void pdhWithoutProcessFiguresDoesNotRaise() {
    var rig = new Rig(new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(),
        Optional.of(DEVICE), Optional.of("RTX"), OptionalLong.of(TOTAL),
        OptionalLong.of(TOTAL - 300 * MIB), Map.of(), T0));
    rig.tick(10);
    assertFalse(rig.gate.held());
  }

  // UNKNOWN rows: raised pressure survives UNKNOWN 29 s after the last known sample, not 31 s
  @Test
  void unknownReadingsClearRaisedPressureAfterThirtySeconds() {
    var rig = new Rig(pdh(300 * MIB, 6 * GIB));
    rig.tick(3); // known samples at T0, T0+2 s and T0+4 s; the clock now reads T0+6 s
    assertTrue(rig.gate.held());
    rig.reading.set(GpuMemorySample.unknown(GpuMemorySample.UnknownReason.UNSUPPORTED_OS, T0));
    rig.clock.advanceSeconds(27);
    rig.tick(1); // T0+33 s: 29 s without a known sample
    assertTrue(rig.gate.held(), "UNKNOWN freezes the machine");
    rig.tick(1); // T0+35 s: 31 s
    assertFalse(rig.gate.held(), "30 s without a known sample clears");
    assertEquals(List.of(PressureSignal.EXTERNAL_MEMORY, PressureSignal.NONE), rig.signals);
  }

  // a receiver bound after the raise hears EXTERNAL_MEMORY at once
  @Test
  void aLateReceiverHearsRaisedPressure() {
    var rig = new Rig(pdh(300 * MIB, 6 * GIB));
    rig.tick(3);
    var late = new CopyOnWriteArrayList<PressureSignal>();
    rig.sampler.bindPressureReceiver(late::add);
    assertEquals(List.of(PressureSignal.EXTERNAL_MEMORY), late);
  }

  // section 3.2: status reads the sampler's reading; its reader view never closes the real reader
  @Test
  void statusReadsTheLatestReadingWithoutOwningTheReader() {
    var rig = new Rig(pdh(300 * MIB, 6 * GIB));
    GpuMemoryReader status = rig.sampler.statusReader();
    assertNull(status.sampleNow(), "nothing before the first tick");
    rig.tick(1);
    assertSame(rig.reading.get(), status.sampleNow());
    status.close();
    assertFalse(rig.readerClosed.get());
    rig.sampler.close();
    assertTrue(rig.readerClosed.get(), "the sampler owns and closes the reader");
  }

  // the sampler runs every 2 s with a fixed delay on the scheduler it is given
  @Test
  void schedulesEveryTwoSecondsWithAFixedDelay() {
    var rig = new Rig(pdh(300 * MIB, 6 * GIB));
    verify(rig.scheduler).scheduleWithFixedDelay(any(), eq(0L), eq(2_000L),
        eq(TimeUnit.MILLISECONDS));
  }

  private static GpuMemorySample pdh(long free, long external) {
    long used = TOTAL - free;
    long ours = used - external;
    return new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(), Optional.of(DEVICE),
        Optional.of("NVIDIA GeForce RTX 4070"), OptionalLong.of(TOTAL), OptionalLong.of(used),
        Map.of(ENGINE, new ProcessMemory(ours / 2, 0L), LLAMA,
            new ProcessMemory(ours - ours / 2, 0L), 3_000L, new ProcessMemory(external, 0L)),
        T0);
  }

  private static final class Rig {
    final BulkWorkGate gate = new BulkWorkGate();
    final AtomicReference<GpuMemorySample> reading;
    final AtomicBoolean readerClosed = new AtomicBoolean();
    final AtomicReference<Runnable> task = new AtomicReference<>();
    final List<PressureSignal> signals = new CopyOnWriteArrayList<>();
    final ManualClock clock = new ManualClock(T0);
    final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    final GpuResidencySampler sampler;

    Rig(GpuMemorySample first) {
      reading = new AtomicReference<>(first);
      gate.setProfile(BulkWorkGate.Profile.P12);
      when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
        task.set(inv.getArgument(0));
        return mock(ScheduledFuture.class);
      });
      GpuMemoryReader reader = new GpuMemoryReader() {
        @Override public GpuMemorySample sampleNow() {
          return reading.get();
        }

        @Override public void close() {
          readerClosed.set(true);
        }
      };
      sampler = new GpuResidencySampler(reader, () -> Set.of(ENGINE, LLAMA), gate, clock, scheduler);
      sampler.bindPressureReceiver(signals::add);
      sampler.start();
    }

    void tick(int times) {
      for (int i = 0; i < times; i++) {
        task.get().run();
        clock.advanceSeconds(2);
      }
    }
  }

  private static final class ManualClock extends Clock {
    private Instant now;

    ManualClock(Instant start) {
      now = start;
    }

    void advanceSeconds(long seconds) {
      now = now.plusSeconds(seconds);
    }

    @Override public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override public Instant instant() {
      return now;
    }
  }
}
