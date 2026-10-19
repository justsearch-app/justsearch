/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatDrainPolicy;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.ChatHolds.Kind;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec A steps 5 and 6 on the chat controller: holds for VDU, running and freeze and the self-test
 * session (reconciliation F11, F17; Spec A §2.2 precedence, T37-T39).
 */
final class ChatHoldsTest {

  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final HoldActuator actuator = new HoldActuator();
  private final List<String> runs = Collections.synchronizedList(new ArrayList<>());
  private ChatServerControllerImpl controller;

  @AfterEach
  void tearDown() {
    actuator.releaseDrain();
    if (controller != null) controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
  }

  private ChatServerControllerImpl controller(Duration drainBound) {
    controller = new ChatServerControllerImpl(executors, actuator, drainBound, spec -> {
      // The candidate runs on the controller thread, with the main server stopped (Spec A T38).
      runs.add(Thread.currentThread().getName() + (actuator.running.get() ? ":main-up" : ":main-down"));
      return SelfTestRun.passed();
    });
    return controller;
  }

  private ChatServerControllerImpl controller() {
    return controller(ChatActuationRouter.DRAIN_BOUND);
  }

  @Test
  void equivalentRunningHoldsWaitForTheActualStart() throws Exception {
    equivalentHolds(false);
  }

  @Test
  void equivalentRunningHoldsBothObserveStartFailure() throws Exception {
    equivalentHolds(true);
  }

  private void equivalentHolds(boolean fail) throws Exception {
    var c = controller();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var blocker = CompletableFuture.runAsync(() -> {
      try { c.route(ChatActuation.of(ChatActuation.Kind.RESUME_PROBE, ignored -> {
        entered.countDown(); release.await(); return null;
      })); } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
    });
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    actuator.failStart = fail;
    var first = CompletableFuture.supplyAsync(() -> takeRunning(c, "first"));
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (c.desiredInputs().version() < 2 && System.nanoTime() < end) Thread.sleep(5);
    var second = CompletableFuture.supplyAsync(() -> takeRunning(c, "second"));
    try {
      end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (c.desiredInputs().version() < 3 && System.nanoTime() < end) Thread.sleep(5);
      Thread.sleep(100);
      assertFalse(first.isDone(), "supersession is not startup completion");
      assertFalse(second.isDone());
    } finally { release.countDown(); }
    blocker.get(5, TimeUnit.SECONDS);
    if (fail) {
      assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
      assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
    } else {
      first.get(5, TimeUnit.SECONDS).close(); second.get(5, TimeUnit.SECONDS).close();
      assertTrue(actuator.running.get());
    }
  }

  private static io.justsearch.app.api.chat.Hold takeRunning(ChatServerControllerImpl c, String owner) {
    try { return c.holdRunning(owner); }
    catch (ChatRefusal e) { throw new java.util.concurrent.CompletionException(e); }
  }

  // ==================== admission: every hold kind in every state ====================

  @Test
  void admissionFollowsThePrecedenceForEveryKindInEveryState() {
    int states = 0;
    for (boolean terminal : new boolean[] {false, true}) {
      for (boolean latched : new boolean[] {false, true}) {
        for (Set<Kind> open : subsets()) {
          var state = new ChatServerControllerImpl.HoldState(terminal, open, latched);
          for (Kind requested : Kind.values()) {
            states++;
            RefusalReason actual = ChatServerControllerImpl.admission(requested, state);
            String where = requested + " in " + state;
            if (requested == Kind.FREEZE) {
              assertNull(actual, "a freeze is always admitted and never acts: " + where);
            } else if (terminal) {
              assertEquals(RefusalReason.ENGINE_STOPPING, actual, "terminal comes first: " + where);
            } else if (open.contains(Kind.SELF_TEST)) {
              assertEquals(requested == Kind.SELF_TEST ? RefusalReason.BUSY : RefusalReason.SELF_TEST,
                  actual, "a self-test outranks VDU and running holds: " + where);
            } else if (requested == Kind.SELF_TEST) {
              assertEquals(open.contains(Kind.VDU) || open.contains(Kind.RUNNING)
                  ? RefusalReason.BUSY : null, actual,
                  "a self-test needs every actuating hold closed; a freeze does not stop it: " + where);
            } else if (latched) {
              assertEquals(RefusalReason.FAILED, actual,
                  "F11: the failure latch is read before the VDU and running holds: " + where);
            } else if (requested == Kind.VDU) {
              assertEquals(open.contains(Kind.VDU) ? RefusalReason.BUSY : null, actual,
                  "one VDU hold at a time: " + where);
            } else {
              assertNull(actual, "running holds share the running server: " + where);
            }
          }
        }
      }
    }
    assertEquals(2 * 2 * 16 * Kind.values().length, states);
  }

  // ==================== holds gate activations and replan on release ====================

  @Test
  void whileAHoldIsOpenActivationsAreNotActedOnAndTheLastCloseTellsTheForwarder() throws Exception {
    var c = controller();
    var edges = Collections.synchronizedList(new ArrayList<String>());
    c.addListener(new ChatHolds.Listener() {
      @Override public void kindOpened(ChatHolds.OpenHold first) {
        edges.add("open:" + first.kind() + ":" + first.owner());
      }
      @Override public void kindClosed(Kind kind, boolean noneOpen) {
        edges.add("close:" + kind + ":" + noneOpen);
      }
    });
    var freeze = c.freezeConvergence("acquisition");
    var smoke = c.holdRunning("smoke-test");
    assertEquals(List.of("up:VDU_ENTER"), actuator.log, "a running hold inside a freeze still acts");

    var down = c.setActivation(new ChatActuationRouter.Activation(false, TransitionReason.USER_SWITCH))
        .toCompletableFuture();
    assertSuperseded(down);
    assertEquals(List.of("up:VDU_ENTER"), actuator.log, "the held server is not stopped");

    smoke.close();
    smoke.close(); // idempotent
    freeze.close();
    assertEquals(List.of("open:FREEZE:acquisition", "open:RUNNING:smoke-test",
        "close:RUNNING:false", "close:FREEZE:true"), edges);
    assertEquals(List.of(), c.openHolds());

    // With nothing held the forwarder's resend acts again.
    c.setActivation(new ChatActuationRouter.Activation(false, TransitionReason.USER_SWITCH))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(List.of("up:VDU_ENTER", "down:USER_SWITCH"), actuator.log);
  }

  @Test
  void aVduHoldStartsTheServerEntersVduAndExitsBeforeItReleases() throws Exception {
    var c = controller();
    var released = new AtomicReference<List<String>>();
    c.addListener(new ChatHolds.Listener() {
      @Override public void kindOpened(ChatHolds.OpenHold first) {}
      @Override public void kindClosed(Kind kind, boolean noneOpen) {
        released.set(List.copyOf(actuator.log));
      }
    });
    try (var hold = c.holdVdu("vdu-batch")) {
      assertEquals(List.of("up:VDU_ENTER", "vdu-enter"), actuator.log);
      assertFalse(hold.interruptRequested());
      var second = assertThrows(ChatRefusal.class, () -> c.holdVdu("another-batch"));
      assertEquals(RefusalReason.BUSY, second.reason());
      assertFalse(c.vduMayStart(), "a held server never starts another pass");
    }
    assertEquals(List.of("up:VDU_ENTER", "vdu-enter", "vdu-exit"), released.get(),
        "the VDU exit runs before the hold is released to the forwarder");
  }

  @Test
  void aFailedVduEntryReturnsTheServerToItsConfigurationAndReleasesTheHold() throws Exception {
    var c = controller();
    actuator.failVduEnter.set(true);
    var refused = assertThrows(ChatRefusal.class, () -> c.holdVdu("vdu-batch"));
    assertEquals(RefusalReason.FAILED, refused.reason());
    assertInstanceOf(ModeTransitionException.class, refused.getCause());
    assertEquals(List.of("up:VDU_ENTER", "vdu-exit"), actuator.log);
    assertEquals(List.of(), c.openHolds());
  }

  // ==================== F11: the failure latch ====================

  @Test
  void aLatchedFailureRefusesVduAndRunningHoldsWithFailedBeforeAnyStep() throws Exception {
    var c = controller();
    actuator.latch = FailedCode.START_FAILED;
    assertEquals(RefusalReason.FAILED,
        assertThrows(ChatRefusal.class, () -> c.holdVdu("vdu-batch")).reason());
    assertEquals(RefusalReason.FAILED,
        assertThrows(ChatRefusal.class, () -> c.holdRunning("smoke-test")).reason());
    assertEquals(List.of(), actuator.log, "a refused hold runs nothing");
    assertFalse(c.vduMayStart(), "a latched failure never starts a VDU pass");
    // An activation self-test is how a user replaces the failed runtime: it is admitted.
    var session = c.beginSelfTest("activation");
    try (session) {
      assertEquals(List.of("self-test-stop"), actuator.log);
    }
  }

  // ==================== the self-test session (T37-T39) ====================

  @Test
  void anExternalServerRefusalNamesTheExternalServer() throws Exception {
    actuator.externalSelfTest = true;
    var refused = assertThrows(ChatRefusal.class, () -> controller().beginSelfTest("activation"));
    assertEquals("EXTERNAL_SERVER", refused.reason().name());
    assertEquals(List.of(), runs);
    assertFalse(actuator.log.contains("self-test-stop"));
  }

  @Test
  void heldVduRefusesPreparationBeforeAnyDrain() throws Exception {
    var c = controller();
    try (var _ = c.holdVdu("pictures")) {
      actuator.policies.clear();
      var body = new AtomicBoolean();
      assertThrows(ModeTransitionException.class, () -> c.route(ChatActuation.draining(
          ChatActuation.Kind.PREPARE, failure -> null, drained -> { body.set(true); return null; })));
      assertEquals(List.of(), actuator.policies, "VDU refusal must precede the generative drain");
      assertFalse(body.get());
    }
  }

  @Test
  void pendingVduEntryRefusesPreparationBetweenItsTwoSteps() throws Exception {
    var c = controller();
    actuator.activationGate = new CountDownLatch(1);
    var vdu = CompletableFuture.supplyAsync(() -> {
      try { return c.holdVdu("pictures"); }
      catch (ChatRefusal refused) { throw new IllegalStateException(refused); }
    });
    try {
      assertTrue(actuator.activationEntered.await(5, TimeUnit.SECONDS));
      var preparation = c.submit(ChatActuation.draining(ChatActuation.Kind.PREPARE,
          failure -> null, drained -> null)).toCompletableFuture();
      assertTrue(preparation.isCompletedExceptionally(),
          "opening VDU ownership must refuse preparation even before its overlay step");
      var failure = assertThrows(ExecutionException.class, () -> preparation.get(5, TimeUnit.SECONDS));
      assertInstanceOf(ModeTransitionException.class, failure.getCause());
    } finally {
      actuator.activationGate.countDown();
      try (var _ = vdu.get(5, TimeUnit.SECONDS)) { }
    }
  }

  @Test
  void aPreparedConfigurationRetainsOwnershipUntilCommitOrAbort() throws Exception {
    var c = controller();
    actuator.configurationPrepared = true;
    try {
      var refusal = assertThrows(ChatRefusal.class, () -> {
        try (var ignored = c.holdVdu("pictures")) { }
      });
      assertEquals(RefusalReason.BUSY, refusal.reason());
      assertEquals(List.of(), actuator.log);
      assertEquals(List.of(), actuator.policies);
    } finally {
      actuator.configurationPrepared = false;
    }
    try (var ignored = c.holdVdu("pictures")) {
      assertTrue(actuator.running.get());
    }
  }

  @Test
  void aSelfTestStopsTheMainServerFirstRunsTheCandidateAndEndsOnClose() throws Exception {
    var c = controller();
    c.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(actuator.running.get());

    try (var session = c.beginSelfTest("activation")) {
      assertFalse(actuator.running.get(), "T37: the main server is stopped before the session");
      assertEquals(RefusalReason.SELF_TEST,
          assertThrows(ChatRefusal.class, () -> c.holdVdu("vdu-batch")).reason());
      assertEquals(RefusalReason.SELF_TEST,
          assertThrows(ChatRefusal.class, () -> c.holdRunning("smoke-test")).reason());
      assertEquals(RefusalReason.BUSY,
          assertThrows(ChatRefusal.class, () -> c.beginSelfTest("second")).reason());
      assertSuperseded(c.setActivation(
          new ChatActuationRouter.Activation(true, TransitionReason.USER_SWITCH)).toCompletableFuture());
      assertFalse(c.vduMayStart(), "U13: a server stopped for a self-test never starts a VDU pass");

      SelfTestRun run = session.run(spec());
      assertTrue(run.exercised());
      assertEquals(List.of(ChatServerControllerImpl.THREAD_NAME + ":main-down"), runs,
          "T38: the candidate runs on the controller thread with the main server down");
    }
    assertEquals(List.of("up:AUTO_START", "self-test-stop", "self-test-end"), actuator.log,
        "T39: the session ends; the main server starts only when the forwarder replans");
    assertEquals(List.of(), c.openHolds());
    assertThrows(IllegalStateException.class, () -> {
      var closed = c.beginSelfTest("third");
      closed.close();
      closed.run(spec());
    }, "a closed session runs nothing");
  }

  @Test
  void aSelfTestWaitsForEveryActuatingHoldButNotForAFreeze() throws Exception {
    var c = controller();
    var freeze = c.freezeConvergence("acquisition");
    try (freeze) {
      var session = c.beginSelfTest("activation");
      try (session) {
        assertEquals(List.of("self-test-stop"), actuator.log);
      }
    }
    var running = c.holdRunning("smoke-test");
    try (running) {
      assertEquals(RefusalReason.BUSY,
          assertThrows(ChatRefusal.class, () -> c.beginSelfTest("activation")).reason());
    }
  }

  // ==================== F17: bounded, lock-free, straight in from idle ====================

  @Test
  void aSelfTestFromIdleGoesStraightInAndEveryHoldDrainsWithinThirtySeconds() throws Exception {
    var c = controller();
    actuator.blockDrain(); // would block a drain that waited for replies
    var session = c.beginSelfTest("activation");
    try (session) {
      assertEquals(List.of("self-test-stop"), actuator.log, "no server, so nothing to wait for");
    }
    actuator.releaseDrain();
    var hold = c.holdVdu("vdu-batch");
    try (hold) {
      // the hold's own steps
    }
    // Taking a hold waits at most 30 s; the VDU exit's wait is Spec A's VDU_EXIT row (deferred).
    var entries = actuator.policies.stream()
        .filter(policy -> policy.reason() != StopReason.VDU_EXIT).toList();
    assertEquals(List.of(StopReason.SELF_TEST, StopReason.VDU_ENTER),
        entries.stream().map(ChatDrainPolicy::reason).toList());
    for (ChatDrainPolicy policy : entries) {
      assertEquals(Duration.ofSeconds(30), policy.bound(), "holds block at most 30 s: " + policy);
      assertEquals(ChatDrainPolicy.Expiry.REFUSE_BUSY, policy.onExpiry(), policy.toString());
      assertTrue(policy.onlyWhileServing(), "an idle server is not waited for: " + policy);
    }
  }

  @Test
  void repliesThatDoNotFinishInTheBoundRefuseTheHoldBusyAndReleaseIt() throws Exception {
    var c = controller(Duration.ofMillis(200));
    c.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    actuator.blockDrain();
    long start = System.nanoTime();
    var refused = assertThrows(ChatRefusal.class, () -> c.beginSelfTest("activation"));
    assertEquals(RefusalReason.BUSY, refused.reason());
    assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "bounded by the drain");
    assertTrue(actuator.running.get(), "a refused self-test leaves the server running");
    assertEquals(List.of(), c.openHolds());
    assertEquals(List.of("up:AUTO_START", "self-test-end"), actuator.log,
        "the refused session still ends, so later starts are not refused");
  }

  @Test
  void aHoldTakenUnderACallerLockNeverNeedsThatLockAndStaysObservable() throws Exception {
    var c = controller();
    c.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    actuator.blockDrain();
    Object settingsLock = new Object();
    actuator.forbiddenLock = settingsLock;
    var held = new CountDownLatch(1);
    var result = new CompletableFuture<String>();
    Thread.ofPlatform().daemon().start(() -> {
      synchronized (settingsLock) {
        held.countDown();
        try (var session = c.beginSelfTest("activation")) {
          result.complete(session.run(spec()).exercised() ? "ran" : "not run");
        } catch (Throwable failure) {
          result.completeExceptionally(failure);
        }
      }
    });
    assertTrue(held.await(5, TimeUnit.SECONDS));
    assertTrue(actuator.drainEntered.await(5, TimeUnit.SECONDS));
    // While the controller drains for the caller, observers read the holds at once.
    var observed = CompletableFuture.supplyAsync(c::openHolds);
    assertEquals(Kind.SELF_TEST, observed.get(1, TimeUnit.SECONDS).getFirst().kind());
    assertFalse(CompletableFuture.supplyAsync(c::vduMayStart).get(1, TimeUnit.SECONDS));
    actuator.releaseDrain();
    assertEquals("ran", result.get(5, TimeUnit.SECONDS));
    assertFalse(actuator.lockSeenOnControllerThread.get(),
        "no controller step ran while holding the caller's lock");
  }

  @Test
  void aHoldRequestedFromTheControllerThreadThrows() throws Exception {
    var c = controller();
    var nested = c.route(ChatActuation.of(ChatActuation.Kind.APPLY, drained -> {
      try {
        c.freezeConvergence("nested");
        return null;
      } catch (IllegalStateException expected) {
        return expected;
      }
    }));
    assertInstanceOf(IllegalStateException.class, nested);
  }

  // ==================== terminal ====================

  @Test
  void afterShutdownHoldsAreRefusedAndAnOpenVduHoldIsAskedToFinish() throws Exception {
    var c = controller();
    var hold = c.holdVdu("vdu-batch");
    assertFalse(hold.interruptRequested());
    c.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    assertTrue(hold.interruptRequested(), "the batch finishes early when the controller stops");
    hold.close(); // the exit is refused as stopping; the hold is still released
    assertEquals(List.of(), c.openHolds());
    assertEquals(RefusalReason.ENGINE_STOPPING,
        assertThrows(ChatRefusal.class, () -> c.holdRunning("smoke-test")).reason());
    assertEquals(RefusalReason.ENGINE_STOPPING,
        assertThrows(ChatRefusal.class, () -> c.beginSelfTest("activation")).reason());
    assertFalse(c.vduMayStart());
  }

  // ==================== helpers ====================

  private static List<Set<Kind>> subsets() {
    List<Set<Kind>> all = new ArrayList<>();
    Kind[] kinds = Kind.values();
    for (int mask = 0; mask < 1 << kinds.length; mask++) {
      EnumSet<Kind> set = EnumSet.noneOf(Kind.class);
      for (int i = 0; i < kinds.length; i++) if ((mask & (1 << i)) != 0) set.add(kinds[i]);
      all.add(set);
    }
    return all;
  }

  private static SelfTestSpec spec() {
    return new SelfTestSpec(List.of("llama-server"), null, List.of(), Path.of("selftest.log"),
        8080, Duration.ofSeconds(1), new SelfTestSpec.Probe() {
          @Override public boolean healthy(int port) {
            return true;
          }
          @Override public void exercise(int port) {}
        });
  }

  private static void assertSuperseded(CompletableFuture<?> call) {
    var failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
    assertInstanceOf(ChatActuationRouter.SupersededException.class, failure.getCause());
  }

  /** A chat server stand-in with the manager's hold steps. */
  private static final class HoldActuator implements ChatActuator {
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final List<ChatDrainPolicy> policies = Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean running = new AtomicBoolean();
    final AtomicBoolean failVduEnter = new AtomicBoolean();
    final AtomicBoolean lockSeenOnControllerThread = new AtomicBoolean();
    final CountDownLatch drainEntered = new CountDownLatch(1);
    volatile boolean failStart;
    volatile FailedCode latch;
    volatile Object forbiddenLock;
    private volatile boolean selfTestHeld;
    private volatile CountDownLatch drainGate;
    volatile boolean externalSelfTest;
    volatile boolean configurationPrepared;
    volatile CountDownLatch activationGate;
    final CountDownLatch activationEntered = new CountDownLatch(1);

    void blockDrain() {
      drainGate = new CountDownLatch(1);
    }

    void releaseDrain() {
      CountDownLatch gate = drainGate;
      if (gate != null) gate.countDown();
    }

    @Override
    public DrainHold drain(Duration bound) {
      throw new UnsupportedOperationException("holds drain by policy");
    }

    @Override
    public DrainHold drain(ChatDrainPolicy policy) throws InterruptedException {
      policies.add(policy);
      checkLock();
      if (policy.onlyWhileServing() && !running.get()) return () -> { };
      drainEntered.countDown();
      CountDownLatch gate = drainGate;
      if (gate != null && policy.bound() == null) {
        gate.await();
      } else if (gate != null && !gate.await(policy.bound().toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("replies did not finish within " + policy.bound());
      }
      return () -> { };
    }

    private void checkLock() {
      Object lock = forbiddenLock;
      if (lock != null && Thread.holdsLock(lock)) lockSeenOnControllerThread.set(true);
    }

    private static <T> T busy(Exception failure) throws ModeTransitionException {
      throw new ModeTransitionException(ModeTransitionException.Reason.ALREADY_TRANSITIONING,
          failure.getMessage());
    }

    @Override
    public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
      return ChatActuation.<Void>coalescing(
          activation.active() ? ChatActuation.Kind.ACTIVATE : ChatActuation.Kind.DEACTIVATE,
          "mode", null, null,
          drained -> {
            if (activationGate != null && activation.active()) {
              activationEntered.countDown();
              activationGate.await();
            }
            if (activation.active() && selfTestHeld) {
              throw new ModeTransitionException(
                  ModeTransitionException.Reason.ALREADY_TRANSITIONING, "self-test");
            }
            if (failStart && activation.active()) throw new ModeTransitionException(
                ModeTransitionException.Reason.ONLINE_START_FAILED, "injected start failure");
            running.set(activation.active());
            log.add((activation.active() ? "up:" : "down:") + activation.reason());
            return null;
          });
    }

    @Override
    public ChatActuation<Void> vduEnter() {
      return ChatActuation.<Void>draining(ChatActuation.Kind.VDU_ENTER, HoldActuator::busy,
          drained -> {
            if (failVduEnter.get()) {
              throw new ModeTransitionException(
                  ModeTransitionException.Reason.ONLINE_START_FAILED, "vision flags rejected");
            }
            log.add("vdu-enter");
            return null;
          });
    }

    @Override
    public ChatActuation<Void> vduExit() {
      return ChatActuation.<Void>draining(ChatActuation.Kind.VDU_EXIT, HoldActuator::busy,
          drained -> {
            log.add("vdu-exit");
            return null;
          });
    }

    @Override
    public ChatActuation<Void> selfTestStop() {
      return ChatActuation.<Void>draining(ChatActuation.Kind.SELF_TEST, HoldActuator::busy,
          drained -> {
            if (externalSelfTest) {
              throw new ModeTransitionException(ModeTransitionException.Reason.EXTERNAL_SERVER_CONFLICT,
                  "external llama-server is active");
            }
            selfTestHeld = true;
            running.set(false);
            log.add("self-test-stop");
            return null;
          });
    }

    @Override
    public ChatActuation<Void> selfTestEnd() {
      return ChatActuation.<Void>of(ChatActuation.Kind.SELF_TEST_END, drained -> {
        selfTestHeld = false;
        log.add("self-test-end");
        return null;
      });
    }

    @Override
    public FailedCode failureLatch() {
      return latch;
    }

    @Override
    public boolean serverRunning() {
      return running.get();
    }

    public boolean configurationPrepared() {
      return configurationPrepared;
    }

    @Override
    public <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception {
      checkLock();
      try {
        return step.run(drained);
      } finally {
        if (drained != null) drained.close();
      }
    }

    @Override
    public void terminalStop(
        StopReason reason, Duration timeout) {
      running.set(false);
    }
  }
}
