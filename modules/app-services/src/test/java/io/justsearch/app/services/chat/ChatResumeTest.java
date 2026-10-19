/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.chat.ChatRefusal;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Spec A step 10, U22: the machine resumed (Spec A §7.4, T33, T34). Suspended time is never
 * counted: the residency dwell and the boot-order deadline restart in full, the server's own timers
 * reset at once, a latched failure is cleared and the intent sent again, and a serving server is
 * probed; a probe that finds the device lost takes the device-lost path.
 */
@Timeout(30)
final class ChatResumeTest {

  private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private ChatServerControllerImpl controller;

  @AfterEach
  void tearDown() {
    if (controller != null) controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
  }

  private ChatServerControllerImpl controller(ChatActuator actuator, Duration bootGateTimeout,
      ChatDeviceLossTest.Recovery recovery) {
    controller = new ChatServerControllerImpl(executors, actuator, ChatActuationRouter.DRAIN_BOUND,
        spec -> SelfTestRun.passed(), bootGateTimeout, nanos::get, recovery,
        Clock.fixed(NOW, ZoneOffset.UTC));
    return controller;
  }

  private ChatServerControllerImpl controller(ChatActuator actuator) {
    return controller(actuator, ChatServerControllerImpl.BOOT_GATE_TIMEOUT,
        new ChatDeviceLossTest.Recovery(List.of()));
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
  void theResidencyDwellRestartsInFullOnResume() throws Exception {
    var actuator = new ChatResidencyTest.ResidencyActuator();
    var c = controller(actuator);
    c.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertTrue(c.unloaded().isPresent());
    c.reportPressure(PressureSignal.NONE);
    nanos.addAndGet(Duration.ofSeconds(60).toNanos());

    c.reportResume();
    nanos.addAndGet(Duration.ofSeconds(60).toNanos());

    var refused = assertThrows(ChatRefusal.class, () -> c.demand(humanChat()),
        "the dwell's suspended part is not counted: 60 s of the restarted dwell are left");
    assertEquals(RefusalReason.RELOAD_BACKOFF, refused.reason());
    assertEquals(Optional.of(Duration.ofSeconds(60)), refused.retryAfter());

    nanos.addAndGet(Duration.ofSeconds(60).toNanos());
    c.demand(humanChat()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(actuator.running.get(), "the restarted dwell over, the demand reloads");
  }

  @Test
  void theServerTimersResetAtOnceAndAServingServerIsProbedAfterTheStepInProgress()
      throws Exception {
    var actuator = new ChatDeviceLossTest.LossActuator();
    var c = controller(actuator);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    CompletableFuture<Void> starting = c.submit(ChatActuation.<Void>of(ChatActuation.Kind.APPLY,
        drained -> {
          started.countDown();
          assertTrue(release.await(10, TimeUnit.SECONDS));
          return null;
        })).toCompletableFuture();
    assertTrue(started.await(5, TimeUnit.SECONDS));

    c.reportResume();

    assertEquals(1, actuator.resumes.get(),
        "the start waiting for health has its deadline restarted now, not after it");
    assertFalse(actuator.log.contains("probe"));
    release.countDown();
    starting.get(5, TimeUnit.SECONDS);
    barrier(c);
    assertEquals(List.of("note-resume", "probe"), actuator.log);
  }

  @Test
  void aProbeThatFindsTheDeviceLostTakesTheDeviceLostPath() throws Exception {
    var actuator = new ChatDeviceLossTest.LossActuator();
    actuator.probe = ChatActuator.ResumeProbe.deviceLost("719");
    var recovery = new ChatDeviceLossTest.Recovery(List.of());
    var c = controller(actuator, ChatServerControllerImpl.BOOT_GATE_TIMEOUT, recovery);

    c.reportResume();

    assertTrue(recovery.restarted.await(5, TimeUnit.SECONDS),
        "within the limit the Engine restarts for the lost device");
    assertEquals(List.of(StopReason.DEVICE_LOST), actuator.terminalStops);
  }

  @Test
  void aPassingProbeChangesNothing() throws Exception {
    var actuator = new ChatDeviceLossTest.LossActuator();
    actuator.probe = ChatActuator.ResumeProbe.of(ChatActuator.ResumeOutcome.PASSED);
    var recovery = new ChatDeviceLossTest.Recovery(List.of());
    var c = controller(actuator, ChatServerControllerImpl.BOOT_GATE_TIMEOUT, recovery);

    c.reportResume();
    barrier(c);

    assertEquals(List.of("note-resume", "probe"), actuator.log);
    assertEquals(List.of(), actuator.terminalStops);
    assertEquals(0, recovery.restarts.get());
    assertEquals(null, c.desiredInputs().terminal());
  }

  @Test
  void theResumeClearsAFailureLatchAndSendsTheIntentAgain() throws Exception {
    var actuator = new ChatDeviceLossTest.LossActuator();
    var c = controller(actuator);
    var replays = new AtomicInteger();
    c.armBootOrderGate(replays::incrementAndGet);
    c.reportQueryEncodersReady();
    assertEquals(0, replays.get());
    actuator.latch.set(FailedCode.CRASH_LOOP);

    c.reportResume();

    assertEquals(null, actuator.latch.get(), "T34: the resume clears FAILED");
    assertEquals(1, replays.get(), "T34: the intent is sent again (replan)");
  }

  @Test
  void withoutALatchTheResumeDoesNotReplan() throws Exception {
    var actuator = new ChatDeviceLossTest.LossActuator();
    var c = controller(actuator);
    var replays = new AtomicInteger();
    c.armBootOrderGate(replays::incrementAndGet);
    c.reportQueryEncodersReady();

    c.reportResume();
    barrier(c);

    assertEquals(0, replays.get());
  }

  @Test
  void theBootOrderDeadlineRestartsInFullOnResume() throws Exception {
    var actuator = new ChatDeviceLossTest.LossActuator();
    actuator.gpu = true;
    var c = controller(actuator, Duration.ofSeconds(3), new ChatDeviceLossTest.Recovery(List.of()));
    var replayed = new CountDownLatch(1);
    c.armBootOrderGate(replayed::countDown);
    long armed = System.nanoTime();
    var waiting = c.setActivation(
        new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START)).toCompletableFuture();
    assertTrue(waiting.isCompletedExceptionally(), "the GPU start waits behind the gate");

    Thread.sleep(2_000);
    c.reportResume();
    long resumed = System.nanoTime();

    long untilPastFirstDeadline = Duration.ofMillis(3_500).toNanos() - (System.nanoTime() - armed);
    assertFalse(replayed.await(Math.max(0, untilPastFirstDeadline), TimeUnit.NANOSECONDS),
        "the gate's suspended time is not counted: it does not expire at the first deadline");
    assertTrue(replayed.await(5, TimeUnit.SECONDS), "the restarted deadline expires");
    assertTrue(System.nanoTime() - resumed >= Duration.ofMillis(2_900).toNanos(),
        "the deadline restarted in full at the resume");
  }

  @Test
  void aResumeAfterTheControllerStoppedDoesNothing() throws Exception {
    var actuator = new ChatDeviceLossTest.LossActuator();
    var c = controller(actuator);
    c.shutdown(StopReason.QUIT, Duration.ofSeconds(5));

    c.reportResume();

    assertEquals(0, actuator.resumes.get());
  }
}
