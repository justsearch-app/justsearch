/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.chat.ChatDeviceLoss;
import io.justsearch.app.api.chat.DeviceLostEvidence;
import io.justsearch.app.api.chat.DeviceLostRecovery;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatDrainPolicy;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.core.execution.TestEngineExecutors;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Spec A step 10, U21: a lost GPU on the chat controller (Spec A §7.3, reconciliation F10). The
 * server stops now with DEVICE_LOST first, and only then is the ledger read. Within the limit (at
 * most one device-lost restart in 10 minutes and three in an hour, counting the new one) the Engine
 * is asked to restart; over it, or when the limit cannot be read, chat is FAILED with
 * device_lost_limit and nothing restarts.
 */
@Timeout(30)
final class ChatDeviceLossTest {

  private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
  private static final DeviceLostEvidence LOST =
      new DeviceLostEvidence(DeviceLostEvidence.Source.ORT_CUDA, "719");

  private final TestEngineExecutors executors = new TestEngineExecutors();
  final LossActuator actuator = new LossActuator();
  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private ChatServerControllerImpl controller;

  @AfterEach
  void tearDown() {
    if (controller != null) controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
  }

  private ChatServerControllerImpl controller(DeviceLostRecovery recovery) {
    controller = new ChatServerControllerImpl(executors, actuator, ChatActuationRouter.DRAIN_BOUND,
        spec -> SelfTestRun.passed(), ChatServerControllerImpl.BOOT_GATE_TIMEOUT, nanos::get,
        recovery, Clock.fixed(NOW, ZoneOffset.UTC));
    return controller;
  }

  @Test
  void aReservationFailureLatchesChatInsteadOfRequestingRestart() throws Exception {
    var requests = new AtomicInteger();
    var c = controller(new DeviceLostRecovery() {
      public List<Instant> priorRestarts() { return List.of(); }
      // No @Override: the identical check compiles before the reservation API exists.
      public void reserveRestart() throws IOException { throw new IOException("disk full"); }
      public void requestRestart() { requests.incrementAndGet(); }
    });
    c.reportDeviceLost(LOST);
    assertTrue(actuator.deviceLostStopped.await(5, TimeUnit.SECONDS),
        "failed persistence must enter the existing recovery-failure state");
    assertEquals(0, requests.get());
    assertEquals(FailedCode.DEVICE_LOST_LIMIT, actuator.latch.get());
  }

  /** A restart path over a fixed history that counts its restart requests. */
  static final class Recovery implements DeviceLostRecovery {
    final List<Instant> prior;
    final IOException unreadable;
    final AtomicInteger restarts = new AtomicInteger();
    final CountDownLatch restarted = new CountDownLatch(1);
    volatile Runnable onRead = () -> { };

    Recovery(List<Instant> prior) {
      this.prior = prior;
      this.unreadable = null;
    }

    Recovery(IOException unreadable) {
      this.prior = List.of();
      this.unreadable = unreadable;
    }

    @Override
    public List<Instant> priorRestarts() throws IOException {
      onRead.run();
      if (unreadable != null) throw unreadable;
      return prior;
    }

    @Override
    public void requestRestart() {
      restarts.incrementAndGet();
      restarted.countDown();
    }

    // No @Override: this fixture also compiles before the commit-handshake API exists.
    public void requestRestart(Runnable accepted, java.util.function.Consumer<RuntimeException> refused) {
      // This in-memory fixture has no commit I/O; it always accepts before signaling the observer.
      accepted.run();
      requestRestart();
    }
  }

  private static void barrier(ChatServerControllerImpl c) throws Exception {
    c.route(ChatActuation.of(ChatActuation.Kind.APPLY, drained -> null));
  }

  private void assertFailedClosed(ChatServerControllerImpl c, Recovery recovery) throws Exception {
    assertTrue(actuator.deviceLostStopped.await(5, TimeUnit.SECONDS),
        "the server is stopped for the lost device and chat is FAILED");
    barrier(c);
    assertEquals(1, actuator.stopRuns.get(), "the server stopped once");
    assertEquals(0, recovery.restarts.get(), "no Engine restart");
    assertEquals(List.of(), actuator.terminalStops, "the controller does not stop for good");
    assertEquals(FailedCode.DEVICE_LOST_LIMIT, actuator.latch.get());
    assertEquals(List.of(StopReason.DEVICE_LOST), actuator.stopDrainReasons(),
        "the stop drains with DEVICE_LOST (NOW)");
    assertEquals(null, c.desiredInputs().terminal(), "chat stays operable for a retry");
  }

  // ==================== within the limit ====================

  @Test
  void withinTheLimitTheServerStopsNowAndTheEngineRestarts() throws Exception {
    var recovery = new Recovery(List.of(NOW.minus(Duration.ofMinutes(30))));
    var c = controller(recovery);
    var started = new CountDownLatch(1);
    CompletableFuture<Void> waiting = c.submit(ChatActuation.<Void>of(ChatActuation.Kind.APPLY,
        drained -> {
          started.countDown();
          new CountDownLatch(1).await(); // a start waiting on the lost device
          return null;
        })).toCompletableFuture();
    assertTrue(started.await(5, TimeUnit.SECONDS));

    ChatDeviceLoss loss = c;
    loss.reportDeviceLost(LOST);

    assertTrue(recovery.restarted.await(5, TimeUnit.SECONDS), "the Engine restart is requested");
    var failure = assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS),
        "the step in progress is interrupted");
    assertInstanceOf(InterruptedException.class, failure.getCause());
    assertEquals(List.of(StopReason.DEVICE_LOST), actuator.terminalStops,
        "the server stops for good with DEVICE_LOST");
    assertEquals(StopReason.DEVICE_LOST, c.desiredInputs().terminal());
    assertEquals(1, recovery.restarts.get());
    assertEquals(1, actuator.stopRuns.get(), "the server stopped now");
    assertEquals(List.of(StopReason.DEVICE_LOST), actuator.stopDrainReasons(),
        "the stop drains with DEVICE_LOST (NOW)");
    assertEquals(1, actuator.deviceLostStopped.getCount(), "no FAILED latch within the limit");
    assertEquals(null, actuator.latch.get());
  }

  @Test
  void theServerStopsBeforeTheLedgerIsRead() throws Exception {
    var within = new Recovery(List.of());
    within.onRead = () -> actuator.log.add("ledger-read");
    var c = controller(within);
    c.reportDeviceLost(LOST);
    assertTrue(within.restarted.await(5, TimeUnit.SECONDS));
    assertEquals(List.of("server-stopped", "ledger-read"), actuator.log,
        "Spec A §7.3: stop(NOW, DEVICE_LOST), then the ledger");
  }

  @Test
  void overTheLimitTheServerAlsoStopsBeforeTheLedgerIsRead() throws Exception {
    var over = new Recovery(List.of(NOW.minus(Duration.ofMinutes(2))));
    over.onRead = () -> actuator.log.add("ledger-read");
    var c = controller(over);
    c.reportDeviceLost(LOST);
    assertTrue(actuator.deviceLostStopped.await(5, TimeUnit.SECONDS));
    barrier(c);
    assertEquals(List.of("server-stopped", "ledger-read", "device-lost-limit"), actuator.log);
  }

  @Test
  void twoEarlierRestartsInTheHourStillLeaveRoomForAThird() throws Exception {
    var recovery = new Recovery(List.of(NOW.minus(Duration.ofMinutes(50)),
        NOW.minus(Duration.ofMinutes(11)), NOW.minus(Duration.ofMinutes(61))));
    var c = controller(recovery);
    c.reportDeviceLost(LOST);
    assertTrue(recovery.restarted.await(5, TimeUnit.SECONDS));
    assertEquals(List.of(StopReason.DEVICE_LOST), actuator.terminalStops);
  }

  // ==================== over the limit ====================

  @Test
  void aRestartInTheLastTenMinutesFailsChatInsteadOfRestarting() throws Exception {
    var recovery = new Recovery(List.of(NOW.minus(Duration.ofMinutes(5))));
    var c = controller(recovery);
    c.reportDeviceLost(LOST);
    assertFailedClosed(c, recovery);
  }

  @Test
  void threeRestartsInTheLastHourFailChatInsteadOfRestarting() throws Exception {
    var recovery = new Recovery(List.of(NOW.minus(Duration.ofMinutes(55)),
        NOW.minus(Duration.ofMinutes(35)), NOW.minus(Duration.ofMinutes(15))));
    var c = controller(recovery);
    c.reportDeviceLost(new DeviceLostEvidence(DeviceLostEvidence.Source.LLAMA_EXIT, "700"));
    assertFailedClosed(c, recovery);
  }

  // ==================== fail closed ====================

  @Test
  void anUnreadableLedgerFailsClosed() throws Exception {
    var recovery = new Recovery(new IOException("ledger line 3 is corrupt"));
    var c = controller(recovery);
    c.reportDeviceLost(LOST);
    assertFailedClosed(c, recovery);
  }

  @Test
  void noBoundRestartPathFailsClosed() throws Exception {
    controller = new ChatServerControllerImpl(executors, actuator, spec -> SelfTestRun.passed());
    controller.reportDeviceLost(LOST);
    assertTrue(actuator.deviceLostStopped.await(5, TimeUnit.SECONDS));
    barrier(controller);
    assertEquals(List.of(), actuator.terminalStops);
    assertEquals(FailedCode.DEVICE_LOST_LIMIT, actuator.latch.get());
  }

  @Test
  void aSecondReportWhileTheFirstIsHandledIsIgnored() throws Exception {
    var recovery = new Recovery(List.of(NOW.minus(Duration.ofMinutes(1))));
    var c = controller(recovery);
    var inStop = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    actuator.duringStop.set(() -> {
      inStop.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    c.reportDeviceLost(LOST);
    assertTrue(inStop.await(5, TimeUnit.SECONDS));
    c.reportDeviceLost(new DeviceLostEvidence(DeviceLostEvidence.Source.ORT_CUDA, "999"));
    release.countDown();
    barrier(c);
    assertEquals(1, actuator.stopRuns.get(), "one loss, one stop");
  }

  @Test
  void theLimitCountsTheNewRestartAgainstBothWindows() {
    assertTrue(ChatServerControllerImpl.withinDeviceLostLimit(List.of(), NOW));
    assertTrue(ChatServerControllerImpl.withinDeviceLostLimit(
        List.of(NOW.minus(Duration.ofMinutes(10))), NOW), "exactly 10 minutes ago is outside");
    assertFalse(ChatServerControllerImpl.withinDeviceLostLimit(
        List.of(NOW.minus(Duration.ofMinutes(9))), NOW));
    assertFalse(ChatServerControllerImpl.withinDeviceLostLimit(
        List.of(NOW.plus(Duration.ofMinutes(1))), NOW), "a record dated ahead counts");
    assertTrue(ChatServerControllerImpl.withinDeviceLostLimit(List.of(
        NOW.minus(Duration.ofMinutes(20)), NOW.minus(Duration.ofMinutes(40))), NOW));
    assertFalse(ChatServerControllerImpl.withinDeviceLostLimit(List.of(
        NOW.minus(Duration.ofMinutes(20)), NOW.minus(Duration.ofMinutes(40)),
        NOW.minus(Duration.ofMinutes(59))), NOW));
  }

  /** A chat server stand-in with the manager's device-lost stop. */
  static final class LossActuator implements ChatActuator {
    final List<StopReason> terminalStops = Collections.synchronizedList(new ArrayList<>());
    final List<ChatDrainPolicy> policies = Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<FailedCode> latch = new AtomicReference<>();
    /** Counts down when the device-lost step latched FAILED (no Engine restart). */
    final CountDownLatch deviceLostStopped = new CountDownLatch(1);
    /** Counts the server stops of the device-lost step. */
    final AtomicInteger stopRuns = new AtomicInteger();
    final AtomicReference<Runnable> duringStop = new AtomicReference<>();
    final AtomicInteger resumes = new AtomicInteger();
    volatile ResumeProbe probe = ResumeProbe.of(ResumeOutcome.NOT_SERVING);
    volatile boolean gpu;
    final List<String> log = Collections.synchronizedList(new ArrayList<>());

    List<StopReason> stopDrainReasons() {
      List<StopReason> reasons = new ArrayList<>();
      synchronized (policies) {
        for (ChatDrainPolicy policy : policies) reasons.add(policy.reason());
      }
      return reasons;
    }

    @Override
    public DrainHold drain(Duration bound) {
      throw new UnsupportedOperationException("steps drain by policy");
    }

    @Override
    public DrainHold drain(ChatDrainPolicy policy) {
      policies.add(policy);
      return () -> { };
    }

    @Override
    public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
      return ChatActuation.<Void>coalescing(activation.active()
          ? ChatActuation.Kind.ACTIVATE : ChatActuation.Kind.DEACTIVATE, "mode", null, null,
          drained -> {
            log.add(activation.active() ? "up" : "down");
            return null;
          });
    }

    @Override
    public ChatActuation<Boolean> deviceLostStop(BooleanSupplier restartEngine) {
      return ChatActuation.<Boolean>draining(ChatActuation.Kind.DEVICE_LOST,
          notDrained -> Boolean.FALSE,
          drained -> {
            stopRuns.incrementAndGet();
            Runnable hook = duringStop.getAndSet(null);
            if (hook != null) hook.run();
            log.add("server-stopped");
            if (restartEngine.getAsBoolean()) return Boolean.TRUE;
            latch.set(FailedCode.DEVICE_LOST_LIMIT);
            log.add("device-lost-limit");
            deviceLostStopped.countDown();
            return Boolean.FALSE;
          });
    }

    @Override
    public void noteResume() {
      resumes.incrementAndGet();
      latch.set(null);
      log.add("note-resume");
    }

    @Override
    public ChatActuation<ResumeProbe> resumeProbe() {
      return ChatActuation.of(ChatActuation.Kind.RESUME_PROBE, drained -> {
        log.add("probe");
        return probe;
      });
    }

    @Override
    public boolean gpuChat() {
      return gpu;
    }

    @Override
    public FailedCode failureLatch() {
      return latch.get();
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
      terminalStops.add(reason);
    }
  }
}
