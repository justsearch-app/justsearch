/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.core.execution.TestEngineExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec A step 2, tests U1-U4: the chat controller runs every actuation on its one thread, coalesces
 * waiting steps, refuses reentrant requests and ends terminal on shutdown with no live child.
 */
final class ChatServerControllerImplTest {

  private static final String KEY = "mode";

  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final FakeActuator actuator = new FakeActuator();
  private ChatServerControllerImpl controller;

  @AfterEach
  void tearDown() {
    actuator.releaseAll();
    if (controller != null) controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
  }

  private ChatServerControllerImpl controller() {
    controller = new ChatServerControllerImpl(executors, actuator, Duration.ofSeconds(5));
    return controller;
  }

  // ==================== U1: one thread, one step at a time ====================

  @Test
  void stepsRunOneAtATimeOnTheControllerThreadAndEachRequestPublishesAVersion() throws Exception {
    var c = controller();
    var running = new AtomicInteger();
    var maxRunning = new AtomicInteger();
    var threads = Collections.synchronizedList(new ArrayList<String>());
    List<CompletableFuture<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      int n = i;
      calls.add(CompletableFuture.supplyAsync(() -> {
        try {
          return c.route(ChatActuation.of(ChatActuation.Kind.APPLY, drained -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            threads.add(Thread.currentThread().getName());
            Thread.sleep(20);
            running.decrementAndGet();
            return n;
          }));
        } catch (Exception failure) {
          throw new IllegalStateException(failure);
        }
      }));
    }
    for (int i = 0; i < calls.size(); i++) {
      assertEquals(i, calls.get(i).get(10, TimeUnit.SECONDS), "each caller gets its own result");
    }
    assertEquals(1, maxRunning.get(), "steps never overlap");
    assertEquals(8, threads.size());
    assertTrue(threads.stream().allMatch(ChatServerControllerImpl.THREAD_NAME::equals),
        "every step runs on the controller thread: " + threads);
    assertEquals(8, c.desiredInputs().version(), "every accepted request publishes a version");
    assertNull(c.desiredInputs().terminal());
  }

  @Test
  void aStepFailureReachesItsCallerAndTheControllerKeepsServing() throws Exception {
    var c = controller();
    var failure = assertThrows(IllegalArgumentException.class,
        () -> c.route(ChatActuation.of(ChatActuation.Kind.APPLY, drained -> {
          throw new IllegalArgumentException("bad config");
        })));
    assertEquals("bad config", failure.getMessage());
    assertEquals("next", c.route(ChatActuation.of(ChatActuation.Kind.APPLY, drained -> "next")));
  }

  // ==================== U2: coalescing ====================

  @Test
  void aToBToCDuringADrainLaunchesCOnceAndTheOthersReportSuperseded() throws Exception {
    var c = controller();
    actuator.blockDrain();
    var launches = Collections.synchronizedList(new ArrayList<String>());
    var holds = Collections.synchronizedList(new ArrayList<ChatActuator.DrainHold>());

    CompletableFuture<String> a = request(c, drainingLaunch("A", launches, holds));
    assertTrue(actuator.drainEntered.await(5, TimeUnit.SECONDS), "A is draining");
    CompletableFuture<String> b = request(c, drainingLaunch("B", launches, holds));
    awaitVersion(c, 2);
    CompletableFuture<String> cStep = request(c, drainingLaunch("C", launches, holds));
    awaitVersion(c, 3);

    actuator.releaseDrain();

    assertEquals("C", cStep.get(5, TimeUnit.SECONDS));
    assertSuperseded(a);
    assertSuperseded(b);
    assertEquals(List.of("C"), launches, "only the final target launches, once");
    assertEquals(1, actuator.drains.get(), "one drain serves the coalesced chain");
    assertEquals(1, holds.size());
    assertSame(actuator.lastHold.get(), holds.getFirst(), "C inherits A's drained admission");
  }

  @Test
  void stepsWaitingBehindARunningStepCoalesceIntoTheLatest() throws Exception {
    var c = controller();
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    var launches = Collections.synchronizedList(new ArrayList<String>());

    CompletableFuture<String> x = request(c, ChatActuation.of(ChatActuation.Kind.APPLY, drained -> {
      started.countDown();
      release.await();
      launches.add("X");
      return "X";
    }));
    assertTrue(started.await(5, TimeUnit.SECONDS));
    CompletableFuture<String> a = request(c, coalescingLaunch("A", launches));
    awaitVersion(c, 2);
    CompletableFuture<String> b = request(c, coalescingLaunch("B", launches));
    awaitVersion(c, 3);
    CompletableFuture<String> cStep = request(c, coalescingLaunch("C", launches));
    awaitVersion(c, 4);
    release.countDown();

    assertEquals("X", x.get(5, TimeUnit.SECONDS));
    assertEquals("C", cStep.get(5, TimeUnit.SECONDS));
    assertSuperseded(a);
    assertSuperseded(b);
    assertEquals(List.of("X", "C"), launches);
  }

  @Test
  void theAbsorbFunctionMergesWhatTheSupersededStepAskedFor() throws Exception {
    var c = controller();
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    var ran = new AtomicReference<String>();
    request(c, ChatActuation.of(ChatActuation.Kind.APPLY, drained -> {
      started.countDown();
      release.await();
      return "busy";
    }));
    assertTrue(started.await(5, TimeUnit.SECONDS));
    CompletableFuture<String> first = request(c, merging("restart", ran));
    awaitVersion(c, 2);
    CompletableFuture<String> second = request(c, merging("apply-only", ran));
    awaitVersion(c, 3);
    release.countDown();

    assertEquals("apply-only+restart", second.get(5, TimeUnit.SECONDS));
    assertEquals("apply-only+restart", ran.get(), "the merged step keeps the superseded request");
    assertSuperseded(first);
  }

  @Test
  void setActivationReturnsAtOnceAndOnlyTheLatestWaitingActivationRuns() throws Exception {
    var c = controller();
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    request(c, ChatActuation.of(ChatActuation.Kind.APPLY, drained -> {
      started.countDown();
      release.await();
      return "busy";
    }));
    assertTrue(started.await(5, TimeUnit.SECONDS));

    // Each call returns while the controller is still busy with the step above.
    var up = c.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture();
    var down = c.setActivation(
        new ChatActuationRouter.Activation(false, TransitionReason.USER_SWITCH)).toCompletableFuture();
    var upAgain = c.setActivation(
        new ChatActuationRouter.Activation(true, TransitionReason.USER_SWITCH)).toCompletableFuture();
    // A waiting activation is told at once that a later one replaced it.
    assertSuperseded(up);
    assertSuperseded(down);
    assertFalse(upAgain.isDone(), "the latest activation waits behind the running step");
    assertEquals(List.of(), actuator.activations);
    release.countDown();

    assertNull(upAgain.get(5, TimeUnit.SECONDS));
    assertEquals(List.of("up:USER_SWITCH"), actuator.activations, "only the latest activation ran");
    assertTrue(actuator.liveChild.get());
  }

  @Test
  void setActivationAfterShutdownCompletesRefusedWithoutRunning() throws Exception {
    var c = controller();
    c.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    var late = c.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture();
    assertRefused(late);
    assertEquals(List.of(), actuator.activations);
  }

  // ==================== U3: quit at awkward moments ====================

  @Test
  void quitWhileStartingEndsTerminalWithNoLiveChild() throws Exception {
    var c = controller();
    var launched = new CountDownLatch(1);
    CompletableFuture<String> start = request(c, ChatActuation.of(ChatActuation.Kind.ACTIVATE,
        drained -> {
          actuator.launch();
          launched.countDown();
          actuator.awaitHealth(); // a slow health wait: only an interrupt ends it
          return "online";
        }));
    assertTrue(launched.await(5, TimeUnit.SECONDS));
    CompletableFuture<String> waiting = request(c, coalescingLaunch("later", new ArrayList<>()));
    awaitVersion(c, 2);

    c.shutdown(StopReason.QUIT, Duration.ofSeconds(5));

    assertTerminal(c);
    assertRefused(waiting);
    assertFailed(start);
    assertFalse(actuator.liveChild.get(), "no live child after quit");
    assertEquals(1, actuator.terminalStops.get());
  }

  @Test
  void quitWhileDrainingEndsTerminalWithoutRunningTheStep() throws Exception {
    var c = controller();
    actuator.blockDrain();
    var launches = Collections.synchronizedList(new ArrayList<String>());
    var holds = Collections.synchronizedList(new ArrayList<ChatActuator.DrainHold>());
    CompletableFuture<String> relaunch = request(c, drainingLaunch("relaunch", launches, holds));
    assertTrue(actuator.drainEntered.await(5, TimeUnit.SECONDS));

    c.shutdown(StopReason.QUIT, Duration.ofSeconds(5));

    assertTerminal(c);
    assertFailed(relaunch);
    assertEquals(List.of(), launches, "a drain cut short by quit never relaunches");
    assertFalse(actuator.liveChild.get());
  }

  @Test
  void quitWhileStoppingEndsTerminalWithNoLiveChild() throws Exception {
    var c = controller();
    c.route(ChatActuation.of(ChatActuation.Kind.ACTIVATE, drained -> {
      actuator.launch();
      return null;
    }));
    assertTrue(actuator.liveChild.get());
    var stopping = new CountDownLatch(1);
    CompletableFuture<Object> stop = request(c, ChatActuation.of(ChatActuation.Kind.DEACTIVATE,
        drained -> {
          stopping.countDown();
          actuator.awaitHealth(); // a slow graceful stop
          actuator.liveChild.set(false);
          return null;
        }));
    assertTrue(stopping.await(5, TimeUnit.SECONDS));

    c.shutdown(StopReason.QUIT, Duration.ofSeconds(5));

    assertTerminal(c);
    assertFailed(stop);
    assertFalse(actuator.liveChild.get());
  }

  @Test
  void aStepThatRelaunchesAfterQuitCannotLeaveAChild() throws Exception {
    var c = controller();
    var inStep = new CountDownLatch(1);
    CompletableFuture<Object> relaunch = request(c, ChatActuation.of(ChatActuation.Kind.APPLY,
        drained -> {
          inStep.countDown();
          // A step that ignores the interrupt and relaunches once quit has begun.
          while (actuator.terminalStops.get() == 0) Thread.onSpinWait();
          actuator.launch();
          return null;
        }));
    assertTrue(inStep.await(5, TimeUnit.SECONDS));
    c.shutdown(StopReason.QUIT, Duration.ofSeconds(5));

    assertTerminal(c);
    try {
      relaunch.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException expected) {
      // the fence refuses the late launch
    }
    assertFalse(actuator.liveChild.get(), "the terminal fence refuses a launch after quit");
  }

  @Test
  void shutdownIsIdempotentAndRefusesEveryLaterRequest() throws Exception {
    var c = controller();
    c.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    long version = c.desiredInputs().version();
    c.shutdown(StopReason.UPGRADE, Duration.ofSeconds(5));
    assertEquals(StopReason.QUIT, c.desiredInputs().terminal(), "the first terminal reason sticks");
    assertEquals(version, c.desiredInputs().version());
    assertThrows(ChatActuationRouter.RefusedException.class,
        () -> c.route(ChatActuation.of(ChatActuation.Kind.ACTIVATE, drained -> "late")));
  }

  // ==================== U4: no reentrancy, no shared lock ====================

  @Test
  void aRequestFromTheControllerThreadThrows() throws Exception {
    var c = controller();
    var nested = c.route(ChatActuation.of(ChatActuation.Kind.APPLY, drained -> {
      try {
        c.route(ChatActuation.of(ChatActuation.Kind.ACTIVATE, inner -> "never"));
        return null;
      } catch (IllegalStateException expected) {
        return expected;
      }
    }));
    assertInstanceOf(IllegalStateException.class, nested);
  }

  @Test
  void theControllerHoldsNoLockACallerOrObserverNeedsWhileItDrains() throws Exception {
    var c = controller();
    actuator.blockDrain();
    Object settingsLock = new Object();
    var held = new CountDownLatch(1);
    CompletableFuture<String> withLock = CompletableFuture.supplyAsync(() -> {
      synchronized (settingsLock) {
        held.countDown();
        try {
          return c.route(ChatActuation.draining(ChatActuation.Kind.PREPARE,
              failure -> "drain failed",
              drained -> {
                // The step runs on the controller thread and never needs the caller's lock...
                assertFalse(Thread.holdsLock(settingsLock));
                drained.close();
                return "prepared";
              }));
        } catch (Exception failure) {
          throw new IllegalStateException(failure);
        }
      }
    });
    assertTrue(held.await(5, TimeUnit.SECONDS));
    assertTrue(actuator.drainEntered.await(5, TimeUnit.SECONDS));
    // ...and while it drains, observers still read its state at once.
    var observed = CompletableFuture.supplyAsync(c::desiredInputs);
    assertEquals(1, observed.get(1, TimeUnit.SECONDS).version(),
        "no controller lock is held during the drain");
    actuator.releaseDrain();
    assertEquals("prepared", withLock.get(5, TimeUnit.SECONDS));
  }

  @Test
  void aCallerInterruptReachesTheStepItWaitsFor() throws Exception {
    var c = controller();
    var started = new CountDownLatch(1);
    var interruptedInStep = new AtomicBoolean();
    var outcome = new CompletableFuture<Exception>();
    Thread caller = Thread.ofPlatform().daemon().start(() -> {
      try {
        c.route(ChatActuation.of(ChatActuation.Kind.ACTIVATE, drained -> {
          started.countDown();
          try {
            Thread.sleep(10_000);
          } catch (InterruptedException interrupted) {
            interruptedInStep.set(true);
            throw interrupted;
          }
          return null;
        }));
        outcome.complete(null);
      } catch (Exception failure) {
        outcome.complete(failure);
      }
    });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    caller.interrupt();
    assertInstanceOf(InterruptedException.class, outcome.get(5, TimeUnit.SECONDS));
    assertTrue(interruptedInStep.get());
    // The forwarded interrupt does not leak into the next step.
    assertEquals("clean", c.route(ChatActuation.of(ChatActuation.Kind.APPLY,
        drained -> Thread.currentThread().isInterrupted() ? "leaked" : "clean")));
  }

  // ==================== helpers ====================

  private static <T> CompletableFuture<T> request(ChatServerControllerImpl c, ChatActuation<T> step) {
    var future = new CompletableFuture<T>();
    Thread.ofPlatform().daemon().start(() -> {
      try {
        future.complete(c.route(step));
      } catch (Throwable failure) {
        future.completeExceptionally(failure);
      }
    });
    return future;
  }

  private static ChatActuation<String> drainingLaunch(
      String target, List<String> launches, List<ChatActuator.DrainHold> holds) {
    return ChatActuation.coalescingAfterDrain(ChatActuation.Kind.APPLY, KEY, target, null,
        failure -> {
          throw failure;
        },
        drained -> {
          holds.add(drained);
          drained.close();
          launches.add(target);
          return target;
        });
  }

  private static ChatActuation<String> coalescingLaunch(String target, List<String> launches) {
    return ChatActuation.coalescing(ChatActuation.Kind.ACTIVATE, KEY, target, null, drained -> {
      launches.add(target);
      return target;
    });
  }

  private static ChatActuation<String> merging(String request, AtomicReference<String> ran) {
    return ChatActuation.coalescing(ChatActuation.Kind.APPLY, KEY, request,
        (superseded, latest) -> merging(
            latest.coalesceState() + "+" + superseded.coalesceState(), ran),
        drained -> {
          ran.set(request);
          return request;
        });
  }

  private static void awaitVersion(ChatServerControllerImpl c, long version) throws Exception {
    await(() -> c.desiredInputs().version() >= version, "version " + version);
  }

  private static void await(BooleanSupplier condition, String what) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
      Thread.sleep(5);
    }
  }

  private static void assertSuperseded(CompletableFuture<?> call) throws Exception {
    var failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
    assertInstanceOf(ChatActuationRouter.SupersededException.class, failure.getCause());
  }

  private static void assertRefused(CompletableFuture<?> call) throws Exception {
    var failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
    assertInstanceOf(ChatActuationRouter.RefusedException.class, failure.getCause());
  }

  private static void assertFailed(CompletableFuture<?> call) {
    assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS),
        "the step in progress at quit does not report success");
  }

  private void assertTerminal(ChatServerControllerImpl c) throws Exception {
    assertEquals(StopReason.QUIT, c.desiredInputs().terminal());
    assertEquals(1, actuator.terminalStops.get(), "quit stops the server through the fence once");
    await(() -> actuator.controllerThread.get() == null
        || !actuator.controllerThread.get().isAlive(), "the controller thread to end");
  }

  /**
   * A chat server stand-in with the real fence's contract: after {@link #terminalStop} no launch
   * can leave a live child.
   */
  private static final class FakeActuator implements ChatActuator {
    final AtomicBoolean liveChild = new AtomicBoolean();
    final AtomicBoolean fenced = new AtomicBoolean();
    final AtomicInteger terminalStops = new AtomicInteger();
    final AtomicInteger drains = new AtomicInteger();
    final AtomicReference<DrainHold> lastHold = new AtomicReference<>();
    final AtomicReference<Thread> controllerThread = new AtomicReference<>();
    final CountDownLatch drainEntered = new CountDownLatch(1);
    final List<String> activations = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch healthNeverComes = new CountDownLatch(1);
    private volatile CountDownLatch drainGate;

    void blockDrain() {
      drainGate = new CountDownLatch(1);
    }

    void releaseDrain() {
      CountDownLatch gate = drainGate;
      if (gate != null) gate.countDown();
    }

    void releaseAll() {
      releaseDrain();
      healthNeverComes.countDown();
    }

    void launch() {
      if (fenced.get()) throw new IllegalStateException("terminal fence: launch refused");
      liveChild.set(true);
    }

    void awaitHealth() throws InterruptedException {
      healthNeverComes.await();
    }

    @Override
    public DrainHold drain(Duration bound) throws InterruptedException {
      controllerThread.set(Thread.currentThread());
      drains.incrementAndGet();
      drainEntered.countDown();
      CountDownLatch gate = drainGate;
      if (gate != null && !gate.await(bound.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("replies did not finish within " + bound);
      }
      DrainHold hold = () -> { };
      lastHold.set(hold);
      return hold;
    }

    @Override
    public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
      return ChatActuation.<Void>coalescing(
          activation.active() ? ChatActuation.Kind.ACTIVATE : ChatActuation.Kind.DEACTIVATE,
          KEY, null, null,
          drained -> {
            activations.add((activation.active() ? "up:" : "down:") + activation.reason());
            if (activation.active()) {
              launch();
            } else {
              liveChild.set(false);
            }
            return null;
          });
    }

    @Override
    public <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception {
      controllerThread.set(Thread.currentThread());
      return step.run(drained);
    }

    @Override
    public void terminalStop(Duration timeout) {
      terminalStops.incrementAndGet();
      fenced.set(true);
      liveChild.set(false);
    }
  }
}
