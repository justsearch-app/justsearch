/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.DeviceLostEvidence;
import io.justsearch.app.api.chat.Demand;
import io.justsearch.app.api.chat.DemandKind;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** S11 regression probes for suspension across deadlines and an exceptional device-loss stop. */
@Timeout(30)
final class ChatResumeRepairTest {
  private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
  private static final DeviceLostEvidence LOST =
      new DeviceLostEvidence(DeviceLostEvidence.Source.ORT_CUDA, "719");
  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private ChatServerControllerImpl controller;

  @AfterEach
  void tearDown() {
    if (controller != null) controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
  }

  private ChatServerControllerImpl controller(ChatActuator actuator,
      ChatDeviceLossTest.Recovery recovery) {
    controller = new ChatServerControllerImpl(executors, actuator, ChatActuationRouter.DRAIN_BOUND,
        spec -> SelfTestRun.passed(), ChatServerControllerImpl.BOOT_GATE_TIMEOUT, nanos::get,
        recovery, Clock.fixed(NOW, ZoneOffset.UTC));
    return controller;
  }

  private ChatServerControllerImpl controller(ChatActuator actuator) {
    return controller(actuator, new ChatDeviceLossTest.Recovery(List.of()));
  }

  private static void barrier(ChatServerControllerImpl c) throws Exception {
    c.route(ChatActuation.of(ChatActuation.Kind.APPLY, drained -> null));
  }

  private static Demand humanChat() {
    return new Demand(DemandKind.HUMAN_CHAT, new EngineContext(EngineContext.ClientKind.WEBVIEW,
        "client-1", Optional.empty(), Optional.empty(), "tier", "http",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND),
        Duration.ofSeconds(30));
  }

  @Test
  void aSuspensionLongerThanTheDwellStillRestartsItInFull() throws Exception {
    var actuator = new ChatResidencyTest.ResidencyActuator();
    var c = controller(actuator);
    c.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertTrue(c.unloaded().isPresent());
    c.reportPressure(PressureSignal.NONE);
    // QPC advances while asleep: the old deadline has passed before resume arrives.
    nanos.addAndGet(Duration.ofSeconds(180).toNanos());

    c.reportResume();

    var refused = assertThrows(ChatRefusal.class, () -> c.demand(humanChat()),
        "S11/U22: a long suspension must not admit chat immediately");
    assertEquals(RefusalReason.RELOAD_BACKOFF, refused.reason());
    assertEquals(Optional.of(Duration.ofMinutes(2)), refused.retryAfter(),
        "resume restarts the full dwell even after its old deadline");
    assertFalse(actuator.running.get());
    nanos.addAndGet(Duration.ofMinutes(2).toNanos());
    c.demand(humanChat()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(actuator.running.get());
  }

  @Test
  void resumeDoesNotCreateADwellAfterTheUnloadedServerWasReloaded() throws Exception {
    var actuator = new ChatResidencyTest.ResidencyActuator();
    var c = controller(actuator);
    c.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    c.reportPressure(PressureSignal.NONE);
    nanos.addAndGet(Duration.ofMinutes(2).toNanos());
    c.demand(humanChat()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    c.reportResume();
    // A boot refusal never unloaded a running server, so it must not inherit the old dwell.
    c.setActivation(new ChatActuationRouter.Activation(false, TransitionReason.USER_SWITCH))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    c.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START));
    barrier(c);
    c.reportPressure(PressureSignal.NONE);
    c.reportResume();
    c.demand(humanChat()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(actuator.running.get());
  }

  @Test
  void aFailedDeviceLostStopLeavesTheControllerServingAnOperatorRetry() throws Exception {
    var recovery = new ChatDeviceLossTest.Recovery(List.of());
    var actuator = new ChatDeviceLossTest.LossActuator();
    var c = controller(actuator, recovery);
    var stopAttempted = new CountDownLatch(1);
    actuator.duringStop.set(() -> {
      // The production actuator retains ownership and latches STOP_FAILED when stop throws.
      actuator.latch.set(FailedCode.STOP_FAILED);
      stopAttempted.countDown();
      throw new IllegalStateException("injected failed device-loss stop");
    });
    c.reportDeviceLost(LOST);
    assertTrue(stopAttempted.await(5, TimeUnit.SECONDS));
    // Barrier waits through completion processing; it fails if that processing killed the loop.
    assertEquals("processed", assertDoesNotThrow(() -> c.route(ChatActuation.of(ChatActuation.Kind.RECOVER, drained -> {
      assertEquals(FailedCode.STOP_FAILED, actuator.latch.get());
      actuator.latch.set(null); // operator retry clears the failed attempt
      return "processed";
    }))), "S11: the controller must process the operator's next retry after stop fails");
    assertEquals(null, c.desiredInputs().terminal());
    assertEquals(0, recovery.restarts.get(), "a failed stop must not request an Engine restart");
    // Its in-progress flag must clear too: a later loss is handled normally.
    c.reportDeviceLost(LOST);
    assertTrue(recovery.restarted.await(5, TimeUnit.SECONDS));
    assertEquals(2, actuator.stopRuns.get());
  }

}
