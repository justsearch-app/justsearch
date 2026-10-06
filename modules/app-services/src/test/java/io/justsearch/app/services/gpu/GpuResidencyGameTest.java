/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.gpu.UserContextReader;
import io.justsearch.app.api.gpu.UserContextSample;
import io.justsearch.app.api.gpu.UserContextSample.NotificationState;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec E E-U4, the sampler rows of game detection (section 4, step E4): the residency sampler polls
 * the foreground reading on its tick, applies the rule and the debounce, sets the gate's game input
 * on each edge and reports GAME to the chat controller, GAME outranking EXTERNAL_MEMORY. The boot
 * sample detects a running game at once, before the schedule starts (CH7); turning detection off
 * clears it at once; a failed foreground read is no game.
 */
final class GpuResidencyGameTest {

  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * MIB;
  private static final long TOTAL = 12 * GIB;
  private static final long ENGINE = 1_000L;
  private static final long GAME = 4_000L;
  private static final GpuDevice DEVICE =
      new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty());
  private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");
  private static final UserContextSample DESKTOP =
      new UserContextSample(NotificationState.ACCEPTS_NOTIFICATIONS, Optional.of(false),
          OptionalLong.of(5_000L), false, false);
  private static final UserContextSample FULL_SCREEN_GAME =
      new UserContextSample(NotificationState.RUNNING_D3D_FULL_SCREEN, Optional.of(false),
          OptionalLong.of(GAME), true, false);

  // a game raises after two polls: the gate holds for GAME_MODE and chat hears GAME, then NONE
  @Test
  void gameRaisesHoldsAndReportsThenClears() {
    var rig = new Rig(DESKTOP, plenty());
    rig.start();
    rig.tick(1);
    rig.context.set(FULL_SCREEN_GAME);
    rig.tick(1);
    assertFalse(rig.gate.held(), "one game poll does not raise");
    rig.tick(1);
    assertTrue(rig.gate.held(), "two consecutive game polls raise");
    assertEquals(List.of(BulkWorkGate.Reason.GAME_MODE), rig.gate.verdict().reasons());
    assertEquals(List.of(PressureSignal.GAME), rig.signals);
    assertTrue(rig.sampler.latest().game());

    rig.context.set(DESKTOP);
    rig.tick(4);
    assertTrue(rig.gate.held(), "four polls without a game do not clear");
    rig.tick(1);
    assertFalse(rig.gate.held(), "five polls without a game clear");
    assertEquals(List.of(PressureSignal.GAME, PressureSignal.NONE), rig.signals);
  }

  // section 3.4: GAME outranks EXTERNAL_MEMORY, and pressure still raised is reported after a game
  @Test
  void gameOutranksExternalMemory() {
    var rig = new Rig(DESKTOP, pressured());
    rig.start();
    rig.tick(3);
    assertEquals(List.of(PressureSignal.EXTERNAL_MEMORY), rig.signals);
    rig.context.set(FULL_SCREEN_GAME);
    rig.tick(2);
    assertEquals(List.of(PressureSignal.EXTERNAL_MEMORY, PressureSignal.GAME), rig.signals);
    assertEquals(List.of(BulkWorkGate.Reason.GAME_MODE, BulkWorkGate.Reason.GPU_BUSY_EXTERNAL),
        rig.gate.verdict().reasons());
    rig.context.set(DESKTOP);
    rig.tick(5);
    assertEquals(List.of(PressureSignal.EXTERNAL_MEMORY, PressureSignal.GAME,
        PressureSignal.EXTERNAL_MEMORY), rig.signals);
    assertEquals(List.of(BulkWorkGate.Reason.GPU_BUSY_EXTERNAL), rig.gate.verdict().reasons());
  }

  // CH7: the boot sample runs before the schedule; a game at boot is on the gate at once and a
  // chat controller bound afterwards hears GAME before its first start
  @Test
  void bootSampleDetectsAGameAtOnce() {
    var rig = new Rig(FULL_SCREEN_GAME, plenty());
    rig.sampler.bootSample();
    assertTrue(rig.gate.held(), "a game at boot holds bulk work before any scheduled tick");
    assertEquals(List.of(BulkWorkGate.Reason.GAME_MODE), rig.gate.verdict().reasons());
    var late = new CopyOnWriteArrayList<PressureSignal>();
    rig.sampler.bindPressureReceiver(late::add);
    assertEquals(List.of(PressureSignal.GAME), late);
  }

  // justsearch.gpu.game_detection = off: a detected game clears at once, and none is detected
  @Test
  void detectionOffClearsAtOnceAndDetectsNothing() {
    var rig = new Rig(FULL_SCREEN_GAME, plenty());
    rig.sampler.bootSample();
    rig.start();
    assertTrue(rig.gate.held());
    rig.enabled.set(false);
    int readsBefore = rig.contextReads.get();
    rig.tick(1);
    assertFalse(rig.gate.held(), "turning detection off clears at once");
    assertEquals(List.of(PressureSignal.GAME, PressureSignal.NONE), rig.signals);
    rig.tick(10);
    assertFalse(rig.gate.held(), "no game is detected while detection is off");
    assertEquals(readsBefore, rig.contextReads.get(),
        "the foreground is not read while detection is off");
  }

  // section 4.3: a failed foreground read is never a game, and counts as a poll without one
  @Test
  void aFailedForegroundReadIsNoGame() {
    var rig = new Rig(FULL_SCREEN_GAME, plenty());
    rig.start();
    rig.tick(1);
    assertTrue(rig.gate.held());
    rig.failReads.set(true);
    rig.tick(4);
    assertTrue(rig.gate.held());
    rig.tick(1);
    assertFalse(rig.gate.held(), "five failed reads clear like five polls without a game");
    rig.tick(5);
    assertFalse(rig.gate.held(), "failed reads never raise");
  }

  // close releases the gate's game input and closes the foreground reader with the memory reader
  @Test
  void closeClearsTheGameAndClosesBothReaders() {
    var rig = new Rig(FULL_SCREEN_GAME, plenty());
    rig.sampler.bootSample();
    assertTrue(rig.gate.held());
    rig.sampler.close();
    assertFalse(rig.gate.held());
    assertTrue(rig.contextClosed.get());
    assertTrue(rig.readerClosed.get());
  }

  /** PDH, 8 GiB free; the game holds 2 GiB of it. */
  private static GpuMemorySample plenty() {
    return pdh(8 * GIB, 2 * GIB);
  }

  /** PDH, 300 MiB free with 6 GiB external, the game holding 2 GiB of that. */
  private static GpuMemorySample pressured() {
    return pdh(300 * MIB, 6 * GIB);
  }

  private static GpuMemorySample pdh(long free, long external) {
    long used = TOTAL - free;
    return new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(), Optional.of(DEVICE),
        Optional.of("NVIDIA GeForce RTX 4070"), OptionalLong.of(TOTAL), OptionalLong.of(used),
        Map.of(ENGINE, new ProcessMemory(used - external, 0L), GAME,
            new ProcessMemory(2 * GIB, 0L), 3_000L, new ProcessMemory(external - 2 * GIB, 0L)),
        T0);
  }

  private static final class Rig {
    final BulkWorkGate gate = new BulkWorkGate();
    final AtomicReference<GpuMemorySample> reading;
    final AtomicReference<UserContextSample> context;
    final AtomicBoolean enabled = new AtomicBoolean(true);
    final AtomicBoolean failReads = new AtomicBoolean();
    final java.util.concurrent.atomic.AtomicInteger contextReads =
        new java.util.concurrent.atomic.AtomicInteger();
    final AtomicBoolean readerClosed = new AtomicBoolean();
    final AtomicBoolean contextClosed = new AtomicBoolean();
    final AtomicReference<Runnable> task = new AtomicReference<>();
    final List<PressureSignal> signals = new CopyOnWriteArrayList<>();
    final ManualClock clock = new ManualClock(T0);
    final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    final GpuResidencySampler sampler;

    Rig(UserContextSample firstContext, GpuMemorySample firstReading) {
      reading = new AtomicReference<>(firstReading);
      context = new AtomicReference<>(firstContext);
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
      UserContextReader userContext = new UserContextReader() {
        @Override public UserContextSample sampleNow() {
          contextReads.incrementAndGet();
          if (failReads.get()) throw new IllegalStateException("foreground read failed");
          return context.get();
        }

        @Override public void close() {
          contextClosed.set(true);
        }
      };
      sampler = new GpuResidencySampler(reader, userContext, enabled::get,
          () -> Set.of(ENGINE), gate, clock, scheduler);
    }

    void start() {
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
