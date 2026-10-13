/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.chat.GenerationActivity;
import io.justsearch.app.api.chat.GenerationLease;
import io.justsearch.app.api.chat.PressureSignal;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
  private final AtomicBoolean rejectNextDelivery = new AtomicBoolean();
  private final AtomicBoolean pauseDelivery = new AtomicBoolean();
  private final java.util.concurrent.BlockingQueue<Runnable> deliveries =
      new java.util.concurrent.LinkedBlockingQueue<>();
  private final GenerationTracker tracker = new GenerationTracker(work -> () -> { }, command -> {
    if (rejectNextDelivery.getAndSet(false)) {
      throw new java.util.concurrent.RejectedExecutionException("callback queue full");
    }
    if (pauseDelivery.get()) deliveries.add(command);
    else edgeDelivery.execute(command);
  });
  @TempDir java.nio.file.Path temporary;
  private io.justsearch.app.inference.InferenceLifecycleManager refusalOwner;
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
    if (refusalOwner != null) refusalOwner.close();
    executors.close();
    edgeDelivery.shutdownNow();
  }

  /** A controller whose GPU server runs idle, fed by a sampler bound as the Engine binds it. */
  private ChatServerControllerImpl running() throws Exception {
    // The stand-in uses the production manager's drain-refusal mapping, without launching a
    // process. Its real tracker supplies the drain exceptions; only the successful stop is fake.
    var executable = java.nio.file.Files.createFile(temporary.resolve("unused-server.cmd"));
    var model = java.nio.file.Files.createFile(temporary.resolve("unused-model.gguf"));
    var config = io.justsearch.configuration.resolved.TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.data.dir", temporary.resolve("data").toString()));
    refusalOwner = new io.justsearch.app.inference.InferenceLifecycleManager(executors,
        new io.justsearch.app.inference.InferenceConfig(executable, model, null, 8081, 4096, 0, false),
        io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents.INSTANCE,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), config);
    actuator.refusalMapping = refusalOwner.chatActuator();
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
    // A wakeup can queue its recheck behind the first barrier while that barrier is submitted.
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

  @Test
  void stablePressureRetriesAfterNoLeaseDeliveryRejectedOnce() throws Exception {
    running();
    var lease = tracker.admit(GenerationTracker.Kind.AUX, null);
    reading.set(pressured());
    tick(3);
    barrier();
    assertEquals(1, actuator.busyRefusals.get());
    rejectNextDelivery.set(true);
    lease.close();
    assertFalse(rejectNextDelivery.get(), "the callback submission was rejected");
    assertEquals(List.of("unload:gpu_busy"), unloadsWithin5s(),
        "a rejected callback must not lose the only wakeup under unchanged pressure");
    tick(5);
    barrier();
    assertEquals(1, actuator.unloads().size(), "the fallback signals exactly once");
  }

  @Test
  void rejectionAfterLeaseClosesDuringRefusalStillRetriesFromTheControllerThread() throws Exception {
    var c = running();
    var lease = tracker.admit(GenerationTracker.Kind.AUX, null);
    var releaseRefusal = new java.util.concurrent.CountDownLatch(1);
    actuator.beforeRefusal = () -> {
      try {
        assertTrue(releaseRefusal.await(5, TimeUnit.SECONDS));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
      lease.close();
    };
    rejectNextDelivery.set(true);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    // reportPressure has now attached the completion callback. Releasing the refused step
    // guarantees that registration and rejection occur on the controller thread.
    releaseRefusal.countDown();
    assertEquals(List.of("unload:gpu_busy"), unloadsWithin5s(),
        "an immediate registration notification may be rejected on the controller thread");
    assertFalse(rejectNextDelivery.get());
    assertEquals(1, actuator.busyRefusals.get());
  }

  @Test
  void fencedAdmissionDoesNotSpinResidencyRetries() throws Exception {
    var c = running();
    pauseDelivery.set(true);
    tracker.fenceForRecovery();
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier();
    // Drive at most four deliveries: an old unbounded retry loop fails an assertion, never hangs.
    for (int i = 0; i < 4; i++) {
      Runnable delivery = deliveries.poll();
      if (delivery == null) break;
      delivery.run();
      barrier();
    }
    assertEquals(1, actuator.busyRefusals.get(),
        "a recovery fence is not an open lease and supplies no lease-close retry");
    assertEquals(0, deliveries.size());
    assertEquals(List.of(), actuator.unloads());
    assertTrue(actuator.running.get());
  }

  @ParameterizedTest(name = "combinedPressureUsesProfileSpecificReason({arguments})")
  @CsvSource({"P8,game_mode", "P12,game_mode", "P16_PLUS,gpu_busy", "NO_GPU,gpu_busy",
      "UNRESOLVED,gpu_busy"})
  void combinedPressureUsesProfileSpecificReason(Profile profile, String reason) throws Exception {
    var c = running();
    c.setProfile(profile);
    var observed = new AtomicReference<PressureSignal>();
    sampler.bindPressureReceiver(observed::set);
    reading.set(pressured());
    context.set(FULL_SCREEN_GAME);
    tick(3);
    assertEquals(PressureSignal.GAME_AND_EXTERNAL_MEMORY, observed.get());
    assertEquals(0, actuator.busyRefusals.get());
    assertEquals(List.of(), actuator.unloads(), "no earlier game-only unload decision");
    sampler.bindPressureReceiver(c::reportPressure);
    assertEquals(PressureSignal.GAME_AND_EXTERNAL_MEMORY, c.reportedPressure());
    assertEquals(List.of("unload:" + reason), unloadsWithin5s());
    assertEquals(reason, c.unloaded().orElseThrow().token());
  }

  @Test
  void aLeaseReopenedBeforeDeliveryDefersAgainUntilItCloses() throws Exception {
    var c = running();
    pauseDelivery.set(true);
    var first = tracker.admit(GenerationTracker.Kind.AUX, null);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier();
    first.close();
    var reopened = tracker.admit(GenerationTracker.Kind.ASSIST, null);
    deliverOne();
    barrier();
    assertEquals(List.of(), actuator.unloads(), "the reopened exchange keeps its server");
    assertEquals(2, actuator.busyRefusals.get());
    assertEquals(0, deliveries.size(), "the replacement watch waits for the new lease");
    reopened.close();
    deliverOne();
    assertEquals(List.of("unload:gpu_busy"), unloadsWithin5s());
  }

  @Test
  void overlappingLeasesResumeOnlyAfterTheLastClose() throws Exception {
    var c = running();
    pauseDelivery.set(true);
    var aux = tracker.admit(GenerationTracker.Kind.AUX, null);
    var assist = tracker.admit(GenerationTracker.Kind.ASSIST, null);
    var vdu = tracker.admit(GenerationTracker.Kind.VDU, null);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier();
    assist.close();
    aux.close();
    aux.close();
    assertEquals(0, deliveries.size());
    assertEquals(List.of(), actuator.unloads());
    vdu.close();
    assertEquals(1, deliveries.size(), "exactly one watch for the overlapping leases");
    deliverOne();
    assertEquals(List.of("unload:gpu_busy"), unloadsWithin5s());
    assertEquals(0, deliveries.size());
  }

  @Test
  void shutdownBeforeCallbackDeliveryCannotQueueAnotherUnload() throws Exception {
    var c = running();
    pauseDelivery.set(true);
    var lease = tracker.admit(GenerationTracker.Kind.AUX, null);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier();
    lease.close();
    c.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    deliverOne();
    assertEquals(List.of(), actuator.unloads());
    assertEquals(1, actuator.busyRefusals.get());
    assertFalse(actuator.running.get());
    assertEquals(StopReason.QUIT, c.desiredInputs().terminal());
  }

  @ParameterizedTest(name = "pressureClearingOnEitherSideOfLeaseCloseCancelsTheDeferredUnload({arguments})")
  @ValueSource(booleans = {true, false})
  void pressureClearingOnEitherSideOfLeaseCloseCancelsTheDeferredUnload(boolean clearFirst)
      throws Exception {
    var c = running();
    pauseDelivery.set(true);
    var lease = tracker.admit(GenerationTracker.Kind.AUX, null);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier();
    if (clearFirst) c.reportPressure(PressureSignal.NONE);
    lease.close();
    if (!clearFirst) c.reportPressure(PressureSignal.NONE);
    deliverOne();
    barrier();
    assertEquals(List.of(), actuator.unloads());
    assertTrue(actuator.running.get());
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    assertEquals(List.of("unload:gpu_busy"), unloadsWithin5s(), "a later episode still unloads");
  }

  @ParameterizedTest(name = "bothPressureSourcesCanArriveAndClearInEitherOrder({arguments})")
  @CsvSource({"GAME,GAME", "GAME,EXTERNAL_MEMORY", "EXTERNAL_MEMORY,GAME",
      "EXTERNAL_MEMORY,EXTERNAL_MEMORY"})
  void bothPressureSourcesCanArriveAndClearInEitherOrder(
      PressureSignal first, PressureSignal remaining) throws Exception {
    var c = running();
    c.setProfile(Profile.P12);
    var lease = tracker.admit(GenerationTracker.Kind.AUX, null);
    try (lease) {
      c.reportPressure(first);
      barrier();
      c.reportPressure(PressureSignal.GAME_AND_EXTERNAL_MEMORY);
      barrier();
      c.reportPressure(remaining);
      barrier();
      c.reportPressure(PressureSignal.NONE);
    }
    edgeDelivery.submit(() -> {}).get(5, TimeUnit.SECONDS);
    barrier();
    assertEquals(List.of(), actuator.unloads(), "cleared pressure must not survive in a callback");
    assertTrue(actuator.running.get());
    c.reportPressure(remaining);
    String reason = remaining == PressureSignal.GAME ? "game_mode" : "gpu_busy";
    assertEquals(List.of("unload:" + reason), unloadsWithin5s());
  }

  @ParameterizedTest(name = "theRemainingPressureSourceDecidesAfterTheOtherClears({arguments})")
  @CsvSource({"P12,GAME,GAME", "P12,GAME,EXTERNAL_MEMORY", "P12,EXTERNAL_MEMORY,GAME",
      "P12,EXTERNAL_MEMORY,EXTERNAL_MEMORY", "P16_PLUS,GAME,GAME",
      "P16_PLUS,GAME,EXTERNAL_MEMORY", "P16_PLUS,EXTERNAL_MEMORY,GAME",
      "P16_PLUS,EXTERNAL_MEMORY,EXTERNAL_MEMORY"})
  void theRemainingPressureSourceDecidesAfterTheOtherClears(
      Profile profile, PressureSignal first, PressureSignal remaining) throws Exception {
    var c = running();
    c.setProfile(profile);
    pauseDelivery.set(true);
    var lease = tracker.admit(GenerationTracker.Kind.AUX, null);
    c.reportPressure(first);
    barrier();
    c.reportPressure(PressureSignal.GAME_AND_EXTERNAL_MEMORY);
    barrier();
    c.reportPressure(remaining);
    barrier();
    lease.close();
    deliverOne();
    barrier();
    if (profile == Profile.P16_PLUS && remaining == PressureSignal.GAME) {
      assertEquals(List.of(), actuator.unloads(), "a game fits after external memory pressure clears");
      assertTrue(actuator.running.get());
    } else {
      String reason = remaining == PressureSignal.GAME ? "game_mode" : "gpu_busy";
      assertEquals(List.of("unload:" + reason), unloadsWithin5s());
    }
  }

  private void deliverOne() throws Exception {
    Runnable delivery = deliveries.poll(5, TimeUnit.SECONDS);
    assertTrue(delivery != null, "one callback was submitted");
    delivery.run();
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
    private ChatActuator refusalMapping;
    private Runnable beforeRefusal = () -> {};

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
            beforeRefusal.run();
            return refusalMapping.residencyUnload(detail, stillWanted).drainFailed(notIdle);
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
