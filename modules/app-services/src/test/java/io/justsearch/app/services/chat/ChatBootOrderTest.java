/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.chat.LoadingCause;
import io.justsearch.app.api.chat.UnloadReason;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.core.component.TestEngineComponents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/*
 * Spec A §9.2 U15 (the controller's status) and U16 (the boot-order gate, §6.2 T2/T3) on the chat
 * controller, with a stand-in actuator.
 */
final class ChatBootOrderTest {

  private static final ChatActuationRouter.Activation UP =
      new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START);
  private static final ChatActuationRouter.Activation DOWN =
      new ChatActuationRouter.Activation(false, TransitionReason.USER_SWITCH);

  private final io.justsearch.core.execution.TestEngineExecutors executors =
      new io.justsearch.core.execution.TestEngineExecutors();
  private final StatusActuator actuator = new StatusActuator();
  private final AtomicReference<ChatActuationRouter.Activation> intent = new AtomicReference<>();
  private final AtomicInteger replays = new AtomicInteger();
  private ChatServerControllerImpl controller;

  @AfterEach
  void tearDown() {
    if (controller != null) controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
  }

  private ChatServerControllerImpl controller(Duration gateTimeout) {
    controller = new ChatServerControllerImpl(executors, actuator, ChatActuationRouter.DRAIN_BOUND,
        spec -> SelfTestRun.passed(), gateTimeout);
    return controller;
  }

  /**
   * The forwarder's replay: schedule sending the current intent again, as the reconciler does. It
   * only schedules, since it may run on the controller thread (the gate's expiry).
   */
  private void replay() {
    replays.incrementAndGet();
    ChatActuationRouter.Activation current = intent.get();
    ChatServerControllerImpl target = controller;
    if (current != null) CompletableFuture.runAsync(() -> target.setActivation(current));
  }

  private CompletableFuture<Void> send(ChatActuationRouter.Activation activation) {
    intent.set(activation);
    return controller.setActivation(activation).toCompletableFuture();
  }

  // ==================== U16: the boot-order gate ====================

  @Test
  void aGpuStartWaitsForTheEncodersAndStartsWhenTheyAreReady() throws Exception {
    var c = controller(Duration.ofMinutes(5));
    actuator.gpu.set(true);
    c.armBootOrderGate(this::replay);

    assertSuperseded(send(UP));
    assertEquals(List.of(), actuator.log, "T2: the first GPU start waits (BOOT_WAIT)");
    var waiting = c.status();
    assertEquals(ChatState.LOADING, waiting.state());
    assertEquals(LoadingCause.BOOT_ORDER, waiting.loadingCause().orElseThrow());

    c.reportQueryEncodersReady();
    assertTrue(await(() -> actuator.log.equals(List.of("up:AUTO_START"))),
        "T3: the encoders' readiness sends the intent again and chat starts: " + actuator.log);
    assertEquals(1, replays.get());
    c.reportQueryEncodersReady();
    assertEquals(1, replays.get(), "the gate opens once");
  }

  @Test
  void theGateExpiresAfterItsBoundAndTheStartGoesAhead() throws Exception {
    var c = controller(Duration.ofMillis(300));
    actuator.gpu.set(true);
    c.armBootOrderGate(this::replay);
    long start = System.nanoTime();
    assertSuperseded(send(UP));

    assertTrue(await(() -> actuator.log.equals(List.of("up:AUTO_START"))),
        "T3: the start goes ahead when the gate expires: " + actuator.log);
    assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(250),
        "not before the bound");
    assertEquals(1, replays.get());
  }

  @Test
  void aCpuStartAndAnUnarmedControllerNeverWait() throws Exception {
    var cpu = controller(Duration.ofMinutes(5));
    cpu.armBootOrderGate(this::replay);
    send(UP).get(5, TimeUnit.SECONDS);
    assertEquals(List.of("up:AUTO_START"), actuator.log, "CPU chat skips the gate");
    cpu.shutdown(StopReason.QUIT, Duration.ofSeconds(5));

    actuator.log.clear();
    actuator.gpu.set(true);
    controller(Duration.ofMinutes(5));
    send(UP).get(5, TimeUnit.SECONDS);
    assertEquals(List.of("up:AUTO_START"), actuator.log, "without the hook nothing waits");
    assertEquals(0, replays.get());
  }

  @Test
  void encodersReadyBeforeTheGateIsArmedLeaveItOpen() throws Exception {
    var c = controller(Duration.ofMinutes(5));
    actuator.gpu.set(true);
    c.reportQueryEncodersReady();
    c.armBootOrderGate(this::replay);
    send(UP).get(5, TimeUnit.SECONDS);
    assertEquals(List.of("up:AUTO_START"), actuator.log);
  }

  @Test
  void chatSwitchedOffDuringTheWaitIsNotStartedWhenTheGateOpens() throws Exception {
    var c = controller(Duration.ofMinutes(5));
    actuator.gpu.set(true);
    c.armBootOrderGate(this::replay);
    assertSuperseded(send(UP));
    send(DOWN).get(5, TimeUnit.SECONDS);
    assertEquals(ChatState.OFF, c.status().state(), "switching chat off ends the wait");

    c.reportQueryEncodersReady();
    assertEquals(0, replays.get(), "nothing waits, so nothing is sent again");
    assertEquals(List.of("down:USER_SWITCH"), actuator.log, "chat is not started");
  }

  @Test
  void activationTogglesNeverTouchTheEncoders() throws Exception {
    var c = controller(Duration.ofMinutes(5));
    actuator.gpu.set(true);
    CountingRegistry registry = new CountingRegistry();
    try (var hook = QueryEncoderReadiness.whenReady(registry, c::reportQueryEncodersReady)) {
      c.armBootOrderGate(this::replay);
      assertSuperseded(send(UP));
      registry.delegate.handle("encoders").transition(ComponentState.READY, null, "query up");
      assertTrue(await(() -> actuator.log.size() == 1), actuator.log.toString());
      int reads = registry.reads.get();

      for (int i = 0; i < 5; i++) {
        send(DOWN).get(5, TimeUnit.SECONDS);
        send(UP).get(5, TimeUnit.SECONDS);
      }

      assertEquals(11, actuator.log.size(), actuator.log.toString());
      assertEquals(reads, registry.reads.get(), "toggles never read the encoder side");
      assertEquals(0, registry.writes.get(), "the hook never writes to the registry");
      assertEquals(ComponentState.READY,
          registry.delegate.handle("encoders").snapshot().state(), "the encoders stay up");
    } finally {
      registry.delegate.close();
    }
  }

  // ==================== U15: the controller's status ====================

  @Test
  void statusFollowsTheObservedServerAndTheControllersOwnState() throws Exception {
    var c = controller(Duration.ofMinutes(5));
    assertEquals(ChatState.OFF, c.status().state());

    actuator.mode.set(Mode.TRANSITIONING);
    var loading = c.status();
    assertEquals(ChatState.LOADING, loading.state());
    assertEquals(LoadingCause.COLD_START, loading.loadingCause().orElseThrow());
    assertEquals(Mode.TRANSITIONING, ChatStatusProjection.mode(loading));

    actuator.mode.set(Mode.ONLINE);
    actuator.inFlight.set(2);
    var serving = c.status();
    assertEquals(ChatState.READY, serving.state());
    assertTrue(serving.generating());
    assertEquals(2, serving.inFlight());
    assertTrue(serving.managed());

    try (var session = c.beginSelfTest("activation")) {
      var selfTest = c.status();
      assertEquals(ChatState.UNLOADED, selfTest.state());
      assertEquals(UnloadReason.SELF_TEST, selfTest.unloadReason().orElseThrow());
    }

    actuator.mode.set(Mode.OFFLINE);
    actuator.inFlight.set(0);
    actuator.latch = FailedCode.RESOURCE_EXHAUSTED;
    var failed = c.status();
    assertEquals(ChatState.FAILED, failed.state());
    assertEquals(FailedCode.RESOURCE_EXHAUSTED, failed.failedCode().orElseThrow());
    assertEquals(new ChatStatusProjection.Component(ComponentState.FAILED, "inference.out_of_memory"),
        ChatStatusProjection.component(failed, ComponentState.READY));

    actuator.mode.set(Mode.INDEXING);
    actuator.latch = null;
    assertEquals(Mode.OFFLINE, ChatStatusProjection.mode(c.status()),
        "F4: a chat that is not running never projects to INDEXING");
  }

  @Test
  void versionMovesOnEveryChangeAndSinceOnlyWhenTheStateChanges() throws Exception {
    var c = controller(Duration.ofMinutes(5));
    actuator.mode.set(Mode.ONLINE);
    var first = c.status();
    assertEquals(first, c.status(), "an unchanged server yields the same snapshot");

    TimeUnit.MILLISECONDS.sleep(5);
    actuator.queued.set(3);
    var busier = c.status();
    assertEquals(first.version() + 1, busier.version());
    assertEquals(first.since(), busier.since(), "activity alone does not move since");
    assertEquals(3, busier.queued());

    TimeUnit.MILLISECONDS.sleep(5);
    actuator.mode.set(Mode.OFFLINE);
    var off = c.status();
    assertEquals(busier.version() + 1, off.version());
    assertTrue(off.since().isAfter(busier.since()), "a new state moves since");

    actuator.external.set(true);
    assertFalse(c.status().managed(), "an external server is not managed");
  }

  // ==================== helpers ====================

  private static void assertSuperseded(CompletableFuture<?> call) {
    var failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
    assertInstanceOf(ChatActuationRouter.SupersededException.class, failure.getCause());
  }

  private static boolean await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      TimeUnit.MILLISECONDS.sleep(10);
    }
    return condition.getAsBoolean();
  }

  /** A registry that counts what the hook does to it. */
  private static final class CountingRegistry implements EngineComponentRegistry {
    final TestEngineComponents delegate = TestEngineComponents.fourComponents();
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger writes = new AtomicInteger();

    @Override public io.justsearch.core.component.ComponentHandle register(
        io.justsearch.core.component.ComponentSpec spec) {
      writes.incrementAndGet();
      return delegate.register(spec);
    }

    @Override public EngineComponentSnapshot snapshot() {
      reads.incrementAndGet();
      return delegate.snapshot();
    }

    @Override public PreparedBatch prepareBatch(
        java.util.Map<String, EngineComponentSnapshot.Component> replacements) {
      writes.incrementAndGet();
      return delegate.prepareBatch(replacements);
    }

    @Override public Subscription subscribe(Consumer<EngineComponentSnapshot> listener) {
      reads.incrementAndGet();
      return delegate.subscribe(snapshot -> {
        reads.incrementAndGet();
        listener.accept(snapshot);
      });
    }

    @Override public ApplyAttempt tryApply() {
      writes.incrementAndGet();
      return delegate.tryApply();
    }

    @Override public void close() {
      delegate.close();
    }
  }

  /** A chat server stand-in whose observed state the test sets. */
  private static final class StatusActuator implements ChatActuator {
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OFFLINE);
    final AtomicBoolean external = new AtomicBoolean();
    final AtomicBoolean gpu = new AtomicBoolean();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger queued = new AtomicInteger();
    volatile FailedCode latch;

    @Override public DrainHold drain(Duration bound) {
      return () -> { };
    }

    @Override public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
      return ChatActuation.<Void>coalescing(
          activation.active() ? ChatActuation.Kind.ACTIVATE : ChatActuation.Kind.DEACTIVATE,
          "mode", null, null,
          drained -> {
            log.add((activation.active() ? "up:" : "down:") + activation.reason());
            return null;
          });
    }

    @Override public ChatActuation<Void> selfTestStop() {
      return ChatActuation.<Void>of(ChatActuation.Kind.SELF_TEST, drained -> null);
    }

    @Override public ChatActuation<Void> selfTestEnd() {
      return ChatActuation.<Void>of(ChatActuation.Kind.SELF_TEST_END, drained -> null);
    }

    @Override public FailedCode failureLatch() {
      return latch;
    }

    @Override public Observed observed() {
      return new Observed(mode.get(), external.get(), inFlight.get(), queued.get());
    }

    @Override public boolean gpuChat() {
      return gpu.get();
    }

    @Override public <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception {
      try {
        return step.run(drained);
      } finally {
        if (drained != null) drained.close();
      }
    }

    @Override public void terminalStop(Duration timeout) {}
  }
}
