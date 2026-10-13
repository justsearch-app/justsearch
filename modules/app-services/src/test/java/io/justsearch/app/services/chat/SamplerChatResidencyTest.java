/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.chat.GenerationActivity;
import io.justsearch.app.api.chat.GenerationLease;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.gpu.UserContextReader;
import io.justsearch.app.api.gpu.UserContextSample;
import io.justsearch.app.api.gpu.UserContextSample.NotificationState;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatDrainPolicy;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.gpu.GpuResidencySampler;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.BulkWorkGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Engine's GPU residency sampler and the chat controller together, bound as production binds
 * them ({@code sampler.bindPressureReceiver(controller::reportPressure)}), over a chat server
 * stand-in whose leases and drains are the real {@link GenerationTracker}'s. A game never hides
 * another application's memory need from chat residency, and an unload refused because a
 * tokenize or query-assist lease was open is tried again once that lease closes, while the
 * pressure stays as it is and the sampler therefore reports nothing new.
 */
@Timeout(60)
final class SamplerChatResidencyTest {

  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * MIB;
  private static final long TOTAL = 12 * GIB;
  private static final long ENGINE = 1_000L;
  private static final long GAME = 4_000L;
  private static final GpuDevice DEVICE =
      new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty());
  private static final Instant T0 = Instant.parse("2026-10-13T12:00:00Z");
  private static final UserContextSample DESKTOP =
      new UserContextSample(NotificationState.ACCEPTS_NOTIFICATIONS, Optional.of(false),
          OptionalLong.of(5_000L), false, false);
  private static final UserContextSample FULL_SCREEN_GAME =
      new UserContextSample(NotificationState.RUNNING_D3D_FULL_SCREEN, Optional.of(false),
          OptionalLong.of(GAME), true, false);

  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final ExecutorService edgeDelivery = Executors.newSingleThreadExecutor();
  private final GenerationTracker tracker = new GenerationTracker(work -> () -> { }, edgeDelivery);
  private final TrackedActuator actuator = new TrackedActuator(tracker);
  private final AtomicReference<GpuMemorySample> reading = new AtomicReference<>(plenty());
  private final AtomicReference<UserContextSample> context = new AtomicReference<>(DESKTOP);
  private final AtomicReference<Runnable> task = new AtomicReference<>();
  private final ManualClock clock = new ManualClock(T0);
  private final BulkWorkGate gate = new BulkWorkGate();
  private ChatServerControllerImpl controller;
  private GpuResidencySampler sampler;

  @AfterEach
  void tearDown() {
    if (sampler != null) sampler.close();
    if (controller != null) controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
    edgeDelivery.shutdownNow();
  }

  /** A controller whose GPU server runs idle, fed by a sampler bound as the Engine binds it. */
  private ChatServerControllerImpl running() throws Exception {
    controller = new ChatServerControllerImpl(executors, actuator, ChatActuationRouter.DRAIN_BOUND,
        spec -> SelfTestRun.passed(), ChatServerControllerImpl.BOOT_GATE_TIMEOUT, System::nanoTime);
    controller.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(actuator.running.get());
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
      task.set(inv.getArgument(0));
      return mock(ScheduledFuture.class);
    });
    GpuMemoryReader reader = new GpuMemoryReader() {
      @Override public GpuMemorySample sampleNow() {
        return reading.get();
      }

      @Override public void close() {
        // nothing to release
      }
    };
    UserContextReader userContext = new UserContextReader() {
      @Override public UserContextSample sampleNow() {
        return context.get();
      }

      @Override public void close() {
        // nothing to release
      }
    };
    sampler = new GpuResidencySampler(reader, userContext, () -> true, () -> Set.of(ENGINE), gate,
        clock, scheduler);
    sampler.bindPressureReceiver(controller::reportPressure);
    sampler.start();
    return controller;
  }

  private void tick(int times) {
    for (int i = 0; i < times; i++) {
      task.get().run();
      clock.advanceSeconds(2);
    }
  }

  /** Waits until every step queued so far, and its completion callbacks, has run. */
  private void barrier() throws Exception {
    controller.route(ChatActuation.of(ChatActuation.Kind.APPLY, drained -> null));
  }

  /** Waits up to five seconds for an unload, then for the steps queued so far. */
  private List<String> unloadsWithin5s() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (actuator.unloads().isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
      barrier();
    }
    barrier();
    return actuator.unloads();
  }

  // ==================== a game never hides another application's memory need ====================

  @Test
  void gameDoesNotMaskMemoryPressureOnP16Plus() throws Exception {
    var c = running();
    c.setProfile(Profile.P16_PLUS);
    context.set(FULL_SCREEN_GAME);
    tick(2);
    barrier();
    assertEquals(List.of(), actuator.unloads(), "a game alone that fits leaves chat loaded (O4)");
    assertTrue(gate.verdict().reasons().contains(BulkWorkGate.Reason.GAME_MODE));

    reading.set(pressured());
    tick(3);
    assertTrue(gate.verdict().reasons().contains(BulkWorkGate.Reason.GPU_BUSY_EXTERNAL),
        "the sampler raised external memory pressure during the game");
    assertEquals(List.of("unload:gpu_busy"), unloadsWithin5s(),
        "another application's memory need unloads idle chat even while a game runs");
    assertEquals(Optional.of(StopDetail.GPU_BUSY), c.unloaded());
  }

  @Test
  void gameDoesNotMaskMemoryPressureWhileTheProfileIsUnresolved() throws Exception {
    var c = running();
    context.set(FULL_SCREEN_GAME);
    tick(2);
    barrier();
    assertEquals(List.of(), actuator.unloads(), "until the profile is known a game alone keeps chat");

    reading.set(pressured());
    tick(3);
    assertEquals(List.of("unload:gpu_busy"), unloadsWithin5s(),
        "another application's memory need unloads idle chat even while a game runs");
    assertEquals(Optional.of(StopDetail.GPU_BUSY), c.unloaded());
  }

  @Test
  void gameWithMemoryPressureBelowSixteenGigabytesUnloadsForTheGame() throws Exception {
    var c = running();
    c.setProfile(Profile.P12);
    reading.set(pressured());
    context.set(FULL_SCREEN_GAME);
    tick(3);
    assertEquals(List.of("unload:game_mode"), unloadsWithin5s(), "the game outranks the reason");
    assertEquals(Optional.of(StopDetail.GAME_MODE), c.unloaded());
  }

  // ============ an unload refused by a tokenize or assist lease is tried again ============

  @Test
  void stablePressureRetriesUnloadAfterAuxCompletes() throws Exception {
    retriesAfterTheLeaseCloses(GenerationTracker.Kind.AUX);
  }

  @Test
  void stablePressureRetriesUnloadAfterAssistCompletes() throws Exception {
    retriesAfterTheLeaseCloses(GenerationTracker.Kind.ASSIST);
  }

  private void retriesAfterTheLeaseCloses(GenerationTracker.Kind kind) throws Exception {
    var c = running();
    GenerationLease lease = tracker.admit(kind, null);
    reading.set(pressured());
    tick(3);
    barrier();
    tick(5);
    barrier();
    assertEquals(List.of(), actuator.unloads(), "no unload while a " + kind + " lease is open");
    assertTrue(actuator.running.get());
    assertTrue(actuator.busyRefusals.get() > 0, "the unload was tried and refused by the lease");

    lease.close();
    tick(3); // the pressure is unchanged, so the sampler reports nothing new
    assertEquals(List.of("unload:gpu_busy"), unloadsWithin5s(),
        "once the " + kind + " lease closes the deferred unload runs with the pressure unchanged");
    assertEquals(Optional.of(StopDetail.GPU_BUSY), c.unloaded());
    tick(10);
    barrier();
    assertEquals(List.of("unload:gpu_busy"), actuator.unloads(), "one unload, no storm");
  }

  // ==================== readings ====================

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

  // ==================== the stand-in ====================

  /** A GPU chat server stand-in whose drains and leases are the real generation tracker's. */
  static final class TrackedActuator implements ChatActuator {
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean running = new AtomicBoolean();
    final java.util.concurrent.atomic.AtomicInteger busyRefusals =
        new java.util.concurrent.atomic.AtomicInteger();
    private final GenerationTracker tracker;

    TrackedActuator(GenerationTracker tracker) {
      this.tracker = tracker;
    }

    List<String> unloads() {
      synchronized (log) {
        return log.stream().filter(entry -> entry.startsWith("unload:")).toList();
      }
    }

    @Override
    public DrainHold drain(Duration bound) {
      throw new UnsupportedOperationException("steps drain by policy");
    }

    @Override
    public DrainHold drain(ChatDrainPolicy policy) throws InterruptedException {
      return tracker.drain(policy);
    }

    @Override
    public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
      return ChatActuation.<Void>coalescing(
          activation.active() ? ChatActuation.Kind.ACTIVATE : ChatActuation.Kind.DEACTIVATE,
          "mode", null, null,
          drained -> {
            running.set(activation.active());
            log.add((activation.active() ? "up:" : "down:") + activation.reason());
            return null;
          });
    }

    @Override
    public ChatActuation<ResidencyUnload> residencyUnload(
        StopDetail detail, BooleanSupplier stillWanted) {
      return ChatActuation.draining(ChatActuation.Kind.RESIDENCY_UNLOAD,
          notIdle -> {
            busyRefusals.incrementAndGet();
            return ResidencyUnload.BUSY;
          },
          drained -> {
            if (!stillWanted.getAsBoolean()) return ResidencyUnload.NOT_WANTED;
            running.set(false);
            log.add("unload:" + detail.token());
            return ResidencyUnload.UNLOADED;
          });
    }

    @Override
    public GenerationActivity generationActivity() {
      return tracker;
    }

    @Override
    public boolean serverRunning() {
      return running.get();
    }

    @Override
    public boolean gpuChat() {
      return true;
    }

    @Override
    public <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception {
      try {
        return step.run(drained);
      } finally {
        if (drained != null) drained.close();
      }
    }

    @Override
    public void terminalStop(StopReason reason, Duration timeout) {
      running.set(false);
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
