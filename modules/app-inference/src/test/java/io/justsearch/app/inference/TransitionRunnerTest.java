package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.api.TransitionCode;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.RequestKind;
import io.justsearch.app.inference.telemetry.RequestOutcome;
import io.justsearch.app.inference.telemetry.StartupReason;
import io.justsearch.app.inference.telemetry.TransitionReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 518 fix G — pin the {@link TransitionRunner}'s envelope contract with regression
 * tests that exercise the three update-tier semantics for the view atom, generation-counter
 * bumping on every transition (fix C), and mergeProps preservation across transition
 * completion (fix A).
 */
@DisplayName("TransitionRunner — envelope + view atom regression tests")
final class TransitionRunnerTest {

  private Object lock;
  private ModeStateMachine modeState;
  private RecordingEvents events;
  private TransitionRunner runner;

  @BeforeEach
  void setUp() {
    lock = new Object();
    modeState = new ModeStateMachine();
    events = new RecordingEvents();
    runner = new TransitionRunner(lock, modeState, events);
  }

  // ==================== Initial state ====================

  @Test
  @DisplayName("initial view is OFFLINE with empty props + generation 0")
  void initialViewIsEmpty() {
    InferenceRuntimeView view = runner.view();
    assertEquals(Mode.OFFLINE, view.phase());
    assertNull(view.identity());
    assertNull(view.lastFailure());
    assertFalse(view.usingExternalLlamaServer());
    assertNull(view.lastKnownModelId());
    assertNull(view.lastKnownContextTokens());
    assertEquals(-1L, view.lastStartupDurationMs());
    assertEquals(0L, runner.generation());
  }

  // ==================== Success path ====================

  @Test
  @DisplayName("successful transition: view installed, listeners notified, onTransition fired, generation bumped")
  void successfulTransitionInstallsViewAndEmitsEvents() throws Exception {
    RecordingListener listener = new RecordingListener();
    runner.addListener(listener);

    InferenceRuntimeView priorView = runner.view();
    Mode result =
        runner.run(
            TransitionReason.USER_SWITCH,
            null,
            view -> {
              assertSame(priorView, view, "body receives prior view at entry");
              InferenceRuntimeView next = view.withPhase(Mode.ONLINE).withStartupDuration(123L);
              return TransitionOutcome.success(Mode.ONLINE, next);
            });

    assertEquals(Mode.ONLINE, result);
    assertEquals(Mode.ONLINE, runner.currentMode());
    InferenceRuntimeView installed = runner.view();
    assertEquals(Mode.ONLINE, installed.phase());
    assertEquals(123L, installed.lastStartupDurationMs());
    assertNotNull(installed.identity());
    assertEquals(1L, runner.generation());

    assertEquals(2, listener.calls.size(), "two notifications: prev→TRANSITIONING, TRANSITIONING→ONLINE");
    assertEquals(Mode.OFFLINE, listener.calls.get(0)[0]);
    assertEquals(Mode.TRANSITIONING, listener.calls.get(0)[1]);
    assertEquals(Mode.TRANSITIONING, listener.calls.get(1)[0]);
    assertEquals(Mode.ONLINE, listener.calls.get(1)[1]);

    assertEquals(1, events.transitions.size());
    assertEquals("OFFLINE", events.transitions.get(0).from);
    assertEquals("ONLINE", events.transitions.get(0).to);
    assertEquals(TransitionReason.USER_SWITCH, events.transitions.get(0).reason);
  }

  // ==================== Fix C: generation bumps on same-phase transitions ====================

  @Test
  @DisplayName("fix C: generation bumps on same-phase ONLINE → ONLINE transition (e.g., applyConfig restart)")
  void generationBumpsOnSamePhaseTransition() throws Exception {
    // First transition: OFFLINE → ONLINE.
    runner.run(
        TransitionReason.USER_SWITCH,
        null,
        view -> TransitionOutcome.success(Mode.ONLINE, view.withPhase(Mode.ONLINE)));
    long firstGeneration = runner.generation();
    assertEquals(1L, firstGeneration);

    // Second transition: ONLINE → ONLINE (simulating applyConfig with restart).
    runner.run(
        TransitionReason.CONFIG_APPLY,
        null,
        view -> TransitionOutcome.success(Mode.ONLINE, view.withPhase(Mode.ONLINE)));

    assertEquals(2L, runner.generation(), "generation MUST bump on same-phase transitions too");
    assertEquals(Mode.ONLINE, runner.currentMode());
  }

  // ==================== Failure path ====================

  @Test
  @DisplayName("returned failure: rollback, view records lastFailure, sink invoked, transition emitted")
  void returnedFailureRollsBack() {
    AtomicReference<InferenceFailure> sinkCapture = new AtomicReference<>();
    InferenceFailure failure =
        new InferenceFailure.StartupFailure(StartupCode.INSUFFICIENT_VRAM, "too low", null);

    ModeTransitionException thrown =
        assertThrows(
            ModeTransitionException.class,
            () ->
                runner.run(
                    TransitionReason.USER_SWITCH,
                    sinkCapture::set,
                    view -> TransitionOutcome.failure(failure, view)));

    assertEquals(ModeTransitionException.Reason.INSUFFICIENT_VRAM, thrown.reason());
    assertSame(failure, sinkCapture.get(), "sink received the typed failure");

    InferenceRuntimeView view = runner.view();
    assertEquals(Mode.OFFLINE, view.phase(), "rolled back to prior phase");
    assertSame(failure, view.lastFailure(), "view records the failure");
    assertEquals(1L, runner.generation(), "failure also bumps generation");
    assertEquals(1, events.transitions.size(), "onTransition emitted even on failure");
  }

  @Test
  @DisplayName("ordinary failure from ONLINE restores ONLINE")
  void ordinaryFailureFromOnlineRestoresOnline() throws Exception {
    RecordingListener listener = new RecordingListener();
    runner.addListener(listener);
    runner.run(
        TransitionReason.USER_SWITCH,
        null,
        view -> TransitionOutcome.success(Mode.ONLINE, view.withPhase(Mode.ONLINE)));
    listener.calls.clear();
    events.transitions.clear();

    InferenceFailure failure =
        new InferenceFailure.ConfigFailure(ConfigCode.INVALID_CONFIG, "invalid candidate");

    assertThrows(
        ModeTransitionException.class,
        () ->
            runner.run(
                TransitionReason.CONFIG_APPLY,
                null,
                view -> TransitionOutcome.failure(failure, view)));

    assertEquals(Mode.ONLINE, runner.currentMode());
    assertEquals(Mode.ONLINE, runner.view().phase());
    assertSame(failure, runner.view().lastFailure());
    assertEquals(2, listener.calls.size());
    assertEquals(Mode.ONLINE, listener.calls.get(0)[0]);
    assertEquals(Mode.TRANSITIONING, listener.calls.get(0)[1]);
    assertEquals(Mode.TRANSITIONING, listener.calls.get(1)[0]);
    assertEquals(Mode.ONLINE, listener.calls.get(1)[1]);
    assertEquals(1, events.transitions.size());
    assertEquals("ONLINE", events.transitions.get(0).from);
    assertEquals("ONLINE", events.transitions.get(0).to);
  }

  @Test
  @DisplayName("explicit offline failure from ONLINE completes OFFLINE exactly once")
  void explicitOfflineFailureFromOnlineCompletesOffline() throws Exception {
    RecordingListener listener = new RecordingListener();
    runner.addListener(listener);
    runner.run(
        TransitionReason.USER_SWITCH,
        null,
        view -> TransitionOutcome.success(Mode.ONLINE, view.withPhase(Mode.ONLINE)));
    long generationBefore = runner.generation();
    listener.calls.clear();
    events.transitions.clear();

    InferenceFailure failure =
        new InferenceFailure.TransitionFailure(
            TransitionCode.CONFIG_APPLY_FAILED, "rollback failed", null);

    ModeTransitionException thrown =
        assertThrows(
            ModeTransitionException.class,
            () ->
                runner.run(
                    TransitionReason.CONFIG_APPLY,
                    null,
                    view -> TransitionOutcome.failureOffline(failure, view)));

    assertEquals(ModeTransitionException.Reason.CONFIG_APPLY_FAILED, thrown.reason());
    assertEquals(Mode.OFFLINE, runner.currentMode());
    assertEquals(Mode.OFFLINE, runner.view().phase());
    assertSame(failure, runner.view().lastFailure());
    assertEquals(generationBefore + 1, runner.generation());
    assertEquals(2, listener.calls.size());
    assertEquals(Mode.ONLINE, listener.calls.get(0)[0]);
    assertEquals(Mode.TRANSITIONING, listener.calls.get(0)[1]);
    assertEquals(Mode.TRANSITIONING, listener.calls.get(1)[0]);
    assertEquals(Mode.OFFLINE, listener.calls.get(1)[1]);
    assertEquals(1, events.transitions.size());
    assertEquals("ONLINE", events.transitions.get(0).from);
    assertEquals("OFFLINE", events.transitions.get(0).to);
    assertEquals(TransitionReason.CONFIG_APPLY, events.transitions.get(0).reason);
  }

  @Test
  @DisplayName("failure restoration must be non-null")
  void failureRestorationMustBeNonNull() {
    InferenceFailure failure =
        new InferenceFailure.ConfigFailure(ConfigCode.INVALID_CONFIG, "invalid candidate");

    assertThrows(
        NullPointerException.class,
        () -> new TransitionOutcome.Failure(failure, runner.view(), null));
  }

  @Test
  @DisplayName("thrown ModeTransitionException from body: runner maps to typed failure, sink invoked, re-throws")
  void thrownExceptionFromBodyIsMapped() {
    AtomicReference<InferenceFailure> sinkCapture = new AtomicReference<>();
    ModeTransitionException mte =
        new ModeTransitionException(ModeTransitionException.Reason.HEALTH_CHECK_TIMEOUT, "timed out");

    ModeTransitionException thrown =
        assertThrows(
            ModeTransitionException.class,
            () ->
                runner.run(
                    TransitionReason.USER_SWITCH,
                    sinkCapture::set,
                    view -> {
                      throw mte;
                    }));

    assertSame(mte, thrown, "original exception re-thrown");
    InferenceFailure captured = sinkCapture.get();
    assertNotNull(captured);
    assertTrue(captured instanceof InferenceFailure.HealthFailure);
    assertEquals(HealthCode.HEALTH_TIMEOUT, ((InferenceFailure.HealthFailure) captured).code());
  }

  // ==================== Fix A: mergeProps preservation across transition ====================

  @Test
  @DisplayName("fix A: mergeProps during body is preserved on the installed view when body reads runner.view()")
  void mergePropsPreservedAcrossTransition() throws Exception {
    runner.run(
        TransitionReason.USER_SWITCH,
        null,
        view -> {
          // Simulate a /props observation arriving during the body (concurrent with the
          // body's own logic, as ServerPropsOps does via mergeProps callbacks from
          // startLlamaServer).
          runner.mergeProps("Qwen3-9B.gguf", 8192);

          // Body reads runner.view() AFTER the mergeProps to incorporate the latest props.
          InferenceRuntimeView next = runner.view().withPhase(Mode.ONLINE).withStartupDuration(50L);
          return TransitionOutcome.success(Mode.ONLINE, next);
        });

    InferenceRuntimeView installed = runner.view();
    assertEquals(Mode.ONLINE, installed.phase());
    assertEquals("Qwen3-9B.gguf", installed.lastKnownModelId());
    assertEquals(Integer.valueOf(8192), installed.lastKnownContextTokens());
    assertEquals(50L, installed.lastStartupDurationMs());
  }

  @Test
  @DisplayName("fix A failure mode: body building from priorView loses concurrent mergeProps")
  void priorViewBasedBodyLosesMergeProps() throws Exception {
    // Demonstrates the bug we're fixing: if the body builds from `priorView`, mergeProps
    // observations are LOST when the runner installs the body's nextView. This test pins
    // that anti-pattern so any future regression to "build from priorView" gets caught.
    runner.run(
        TransitionReason.USER_SWITCH,
        null,
        priorView -> {
          runner.mergeProps("model-A.gguf", 4096);
          // ANTI-PATTERN: build from priorView (which doesn't have the props).
          InferenceRuntimeView next = priorView.withPhase(Mode.ONLINE);
          return TransitionOutcome.success(Mode.ONLINE, next);
        });

    InferenceRuntimeView installed = runner.view();
    assertNull(installed.lastKnownModelId(), "priorView-built nextView loses mergeProps");
    assertNull(installed.lastKnownContextTokens(), "priorView-built nextView loses mergeProps");
  }

  // ==================== clearProps ====================

  @Test
  @DisplayName("clearProps wipes lastKnownModelId + lastKnownContextTokens")
  void clearPropsWipesProps() {
    runner.mergeProps("stale.gguf", 2048);
    assertEquals("stale.gguf", runner.view().lastKnownModelId());

    runner.clearProps();

    InferenceRuntimeView view = runner.view();
    assertNull(view.lastKnownModelId());
    assertNull(view.lastKnownContextTokens());
    assertEquals(Mode.OFFLINE, view.phase(), "clearProps does not touch phase");
  }

  // ==================== runForceOffline ====================

  @Test
  @DisplayName("runForceOffline: forces phase to OFFLINE, bumps generation, emits onTransition")
  void runForceOfflineForces() throws Exception {
    // Set up ONLINE state via a normal transition first.
    runner.run(
        TransitionReason.USER_SWITCH,
        null,
        view -> TransitionOutcome.success(Mode.ONLINE, view.withPhase(Mode.ONLINE)));
    long generationBefore = runner.generation();
    assertEquals(Mode.ONLINE, runner.currentMode());

    InferenceFailure failure =
        new InferenceFailure.HealthFailure(HealthCode.PROCESS_DIED, "crashed", null);
    runner.runForceOffline(TransitionReason.CRASH_RECOVERY, failure);

    assertEquals(Mode.OFFLINE, runner.currentMode());
    assertSame(failure, runner.view().lastFailure());
    assertEquals(generationBefore + 1, runner.generation());

    assertEquals(2, events.transitions.size());
    assertEquals(TransitionReason.CRASH_RECOVERY, events.transitions.get(1).reason);
  }

  // ==================== recordFailureOutsideTransition ====================

  @Test
  @DisplayName("recordFailureOutsideTransition: updates lastFailure without phase change or transition event")
  void recordFailureOutsideTransitionUpdatesViewOnly() {
    InferenceFailure failure =
        new InferenceFailure.ConfigFailure(ConfigCode.CONFIG_REQUIRED, "config is null");

    runner.recordFailureOutsideTransition(failure);

    InferenceRuntimeView view = runner.view();
    assertSame(failure, view.lastFailure());
    assertEquals(Mode.OFFLINE, view.phase(), "phase unchanged");
    assertEquals(0L, runner.generation(), "generation unchanged");
    assertEquals(0, events.transitions.size(), "no transition emitted");
  }

  // ==================== Idempotency / re-entry contract (W1.2) ====================

  @Test
  @DisplayName("idempotency: sequential concurrent calls serialize via the monitor; both succeed")
  void sequentialConcurrentCallsSerialize() throws Exception {
    // Two threads call run() back-to-back. The second blocks on the monitor until the first
    // completes; both succeed. Pins the contract that callers do NOT need external locking
    // for the simple sequential case.
    java.util.concurrent.CountDownLatch firstStarted = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch firstMayProceed = new java.util.concurrent.CountDownLatch(1);
    AtomicReference<Throwable> errA = new AtomicReference<>();
    AtomicReference<Throwable> errB = new AtomicReference<>();

    Thread a =
        new Thread(
            () -> {
              try {
                runner.run(
                    TransitionReason.USER_SWITCH,
                    null,
                    priorView -> {
                      firstStarted.countDown();
                      try {
                        firstMayProceed.await();
                      } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                      }
                      return TransitionOutcome.success(Mode.ONLINE, priorView.withPhase(Mode.ONLINE));
                    });
              } catch (Throwable t) {
                errA.set(t);
              }
            });
    Thread b =
        new Thread(
            () -> {
              try {
                firstStarted.await();
                // B starts AFTER A is inside run(); B should block on monitor, not throw.
                runner.run(
                    TransitionReason.USER_SWITCH,
                    null,
                    priorView -> TransitionOutcome.success(Mode.INDEXING, priorView.withPhase(Mode.INDEXING)));
              } catch (Throwable t) {
                errB.set(t);
              }
            });

    a.start();
    b.start();
    firstStarted.await();
    // Give B a moment to attempt acquiring the monitor — it must NOT have completed yet.
    Thread.sleep(50);
    assertNull(errB.get(), "B must not have errored while A holds the lock");
    firstMayProceed.countDown();
    a.join(2000);
    b.join(2000);

    assertNull(errA.get(), "A succeeded");
    assertNull(errB.get(), "B succeeded after A completed");
    assertEquals(Mode.INDEXING, runner.currentMode(), "final state is B's target");
    assertEquals(2L, runner.generation(), "generation bumped twice");
  }

  @Test
  @DisplayName("idempotency: re-entry from a listener callback throws IllegalStateException")
  void reentryFromListenerThrows() {
    // A listener that, on every notification, attempts to call back into the runner. The
    // re-entrant run() must throw IllegalStateException from beginTransition (FSM is in
    // TRANSITIONING when the listener fires).
    AtomicReference<Throwable> reentryError =
        new AtomicReference<>();
    ModeChangeListener reentrant =
        (from, to) -> {
          // Only attempt re-entry on the TRANSITIONING notification to avoid infinite recursion
          // attempts on the post-transition listener call.
          if (to == Mode.TRANSITIONING) {
            try {
              runner.run(
                  TransitionReason.USER_SWITCH,
                  null,
                  priorView -> TransitionOutcome.success(Mode.ONLINE, priorView));
            } catch (Throwable t) {
              reentryError.set(t);
            }
          }
        };
    runner.addListener(reentrant);

    // Outer transition succeeds; re-entry attempt inside the listener is captured.
    Mode result =
        assertDoesNotThrow(
            () ->
                runner.run(
                    TransitionReason.USER_SWITCH,
                    null,
                    priorView -> TransitionOutcome.success(Mode.ONLINE, priorView.withPhase(Mode.ONLINE))));
    assertEquals(Mode.ONLINE, result);
    assertNotNull(reentryError.get(), "listener's re-entrant run() must have thrown");
    assertTrue(
        reentryError.get() instanceof IllegalStateException,
        "expected IllegalStateException from beginTransition, got " + reentryError.get().getClass());
    assertTrue(
        reentryError.get().getMessage().toLowerCase().contains("transition"),
        "expected message to reference transition state, got: " + reentryError.get().getMessage());
  }

  // ==================== Failure-history ring buffer (W2.1) ====================

  @Test
  @DisplayName("recentFailures: records from rollback, runForceOffline, and recordFailureOutsideTransition; newest first")
  void recentFailuresRecordsFromAllThreeSites() throws Exception {
    // Site 1: rollback from a failing transition body.
    InferenceFailure startupFail =
        new InferenceFailure.StartupFailure(StartupCode.PROCESS_EXITED, "boom", null);
    assertThrows(
        ModeTransitionException.class,
        () ->
            runner.run(
                TransitionReason.USER_SWITCH,
                null,
                priorView -> TransitionOutcome.failure(startupFail, priorView)));

    // Site 2: runForceOffline with a failure.
    InferenceFailure healthFail =
        new InferenceFailure.HealthFailure(HealthCode.PROCESS_DIED, "crashed", null);
    runner.runForceOffline(TransitionReason.CRASH_RECOVERY, healthFail);

    // Site 3: recordFailureOutsideTransition.
    InferenceFailure configFail =
        new InferenceFailure.ConfigFailure(ConfigCode.CONFIG_REQUIRED, "no config");
    runner.recordFailureOutsideTransition(configFail);

    var snapshot = runner.recentFailures(10);
    assertEquals(3, snapshot.size(), "all three sites recorded");
    // Newest first: configFail recorded last, so it should be at index 0.
    assertEquals("config", snapshot.get(0).category());
    assertEquals(ConfigCode.CONFIG_REQUIRED.wireValue(), snapshot.get(0).wireCode());
    assertEquals("health", snapshot.get(1).category());
    assertEquals("startup", snapshot.get(2).category());
  }

  @Test
  @DisplayName("recentFailures: limit clamped; non-positive returns empty list")
  void recentFailuresLimitClamps() {
    runner.recordFailureOutsideTransition(
        new InferenceFailure.ConfigFailure(ConfigCode.CONFIG_REQUIRED, "x"));
    assertTrue(runner.recentFailures(0).isEmpty(), "limit=0 returns empty");
    assertTrue(runner.recentFailures(-3).isEmpty(), "negative limit returns empty");
    assertEquals(1, runner.recentFailures(100).size(), "limit > buffer size returns buffer size");
  }

  @Test
  @DisplayName("recentFailures: ring buffer caps at 20 entries; oldest evicted")
  void recentFailuresEvictsOldest() {
    // Record 25 failures; oldest 5 should be evicted; buffer holds 20 newest.
    for (int i = 0; i < 25; i++) {
      runner.recordFailureOutsideTransition(
          new InferenceFailure.ConfigFailure(ConfigCode.CONFIG_REQUIRED, "fail-" + i));
    }
    var snapshot = runner.recentFailures(50);
    assertEquals(20, snapshot.size(), "buffer caps at 20");
    assertEquals("fail-24", snapshot.get(0).detail(), "newest first");
    assertEquals("fail-5", snapshot.get(19).detail(), "oldest retained is fail-5 (fail-0..fail-4 evicted)");
  }

  // ==================== Transition-history ring buffer (W3.2) ====================

  @Test
  @DisplayName("recentTransitions: records both success and failure, newest first")
  void recentTransitionsRecordsBothPaths() throws Exception {
    runner.run(
        TransitionReason.USER_SWITCH,
        null,
        priorView -> TransitionOutcome.success(Mode.ONLINE, priorView.withPhase(Mode.ONLINE)));

    InferenceFailure healthFail =
        new InferenceFailure.HealthFailure(HealthCode.PROCESS_DIED, "crashed", null);
    runner.runForceOffline(TransitionReason.CRASH_RECOVERY, healthFail);

    var snapshot = runner.recentTransitions(10);
    assertEquals(2, snapshot.size());
    // Newest first: forced-offline last → at index 0
    assertEquals("OFFLINE", snapshot.get(0).toMode());
    assertFalse(snapshot.get(0).success(), "force-offline-with-failure recorded as not-success");
    assertEquals(HealthCode.PROCESS_DIED.wireValue(), snapshot.get(0).wireCode());
    // Older: the successful switch → at index 1
    assertEquals("ONLINE", snapshot.get(1).toMode());
    assertTrue(snapshot.get(1).success());
    assertNull(snapshot.get(1).wireCode());
  }

  @Test
  @DisplayName("recentTransitions: rolled-back transition recorded as success=false with wireCode")
  void recentTransitionsRollbackHasWireCode() {
    InferenceFailure startupFail =
        new InferenceFailure.StartupFailure(StartupCode.INSUFFICIENT_VRAM, "no vram", null);
    assertThrows(
        ModeTransitionException.class,
        () ->
            runner.run(
                TransitionReason.USER_SWITCH,
                null,
                priorView -> TransitionOutcome.failure(startupFail, priorView)));

    var snapshot = runner.recentTransitions(10);
    assertEquals(1, snapshot.size());
    assertFalse(snapshot.get(0).success());
    assertEquals(StartupCode.INSUFFICIENT_VRAM.wireValue(), snapshot.get(0).wireCode());
    assertEquals("OFFLINE", snapshot.get(0).toMode()); // rolled back to prior
  }

  @Test
  @DisplayName("recentTransitions: caps at 20 entries")
  void recentTransitionsCapsAt20() throws Exception {
    for (int i = 0; i < 25; i++) {
      // Alternate ONLINE/OFFLINE via runForceOffline + a success to keep FSM happy.
      runner.runForceOffline(TransitionReason.CRASH_RECOVERY, null);
    }
    var snapshot = runner.recentTransitions(50);
    assertEquals(20, snapshot.size());
  }

  // ==================== Helpers ====================

  private static final class RecordingListener implements ModeChangeListener {
    final List<Mode[]> calls = new ArrayList<>();

    @Override
    public void onModeChange(Mode from, Mode to) {
      calls.add(new Mode[] {from, to});
    }
  }

  /** Minimal recording impl for the test. Most methods are no-ops; only onTransition is captured. */
  private static final class RecordingEvents implements InferenceTelemetryEvents {
    record TransitionCall(String from, String to, TransitionReason reason, Duration elapsed) {}

    final List<TransitionCall> transitions = new ArrayList<>();

    @Override
    public void onTransition(String fromPhase, String toPhase, TransitionReason reason, Duration elapsed) {
      transitions.add(new TransitionCall(fromPhase, toPhase, reason, elapsed));
    }

    @Override
    public void onStartupAttempt(InferenceConfig schema, StartupReason reason, TargetPhase target) {}

    @Override
    public void onStartupComplete(
        InferenceConfig schema, Duration elapsed, RuntimeIdentity identity, TargetPhase target) {}

    @Override
    public void onStartupFailure(InferenceFailure failure) {}

    @Override
    public void onHealthFailure(
        InferenceFailure.HealthFailure failure, int consecutiveCount, boolean restartTriggered) {}

    @Override
    public void onHealthRecovered(int previousFailureCount) {}

    @Override
    public void onConfigApplyAttempt(InferenceConfig oldSchema, InferenceConfig newSchema, boolean restartRequired) {}

    @Override
    public void onConfigApplyComplete(Duration elapsed) {}

    @Override
    public void onConfigApplyFailure(InferenceFailure failure) {}

    @Override
    public void onRequestEnqueued(RequestKind kind) {}

    @Override
    public void onRequestStarted(RequestKind kind, Duration waitedMs) {}

    @Override
    public void onRequestCompleted(RequestKind kind, Duration totalMs, RequestOutcome outcome) {}
  }

  // Reference to silence "unused import" warnings — they're used in helper class for parity
  // with the InferenceTelemetryEvents interface even though most methods are no-ops here.
  @SuppressWarnings("unused")
  private static final NoopInferenceTelemetryEvents UNUSED_NOOP = NoopInferenceTelemetryEvents.INSTANCE;
}
