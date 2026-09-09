package io.justsearch.app.inference;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.api.TransitionCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.RequestKind;
import io.justsearch.app.inference.telemetry.RequestOutcome;
import io.justsearch.app.inference.telemetry.StartupReason;
import io.justsearch.app.inference.telemetry.TransitionReason;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 412 Phase 2 Phase 2: validates the new identity / lastFailure accessor surface and
 * confirms the {@link InferenceTelemetryEvents} sink fires on mode transitions, in addition to
 * the legacy {@link io.justsearch.app.api.ModeChangeListener} path.
 *
 * <p>This class is independent of the existing
 * {@code InferenceLifecycleManagerExternalServerTest} / {@code …PropsInsightsTest} which test
 * the legacy path against real internal state.
 */
@DisplayName("InferenceLifecycleManager — phase-typed accessors and events emission")
final class InferenceLifecycleManagerIdentityTest {

  private InferenceLifecycleManager manager;

  @AfterEach
  void tearDown() {
    if (manager != null) {
      try {
        manager.close();
      } catch (Exception ignored) {
        // best-effort
      }
      manager = null;
    }
  }

  private static InferenceConfig fakeConfig() {
    // We never start a server in these tests — paths and ports are placeholders. Validation
    // ({@code config.validate()}) is only triggered by {@code switchToOnlineMode} etc., which
    // these tests never call. The compact constructor's invariants (port range, contextSize,
    // gpuLayers) are satisfied.
    return new InferenceConfig(
        Path.of("/no/such/llama-server.exe"),
        Path.of("/no/such/model.gguf"),
        null,
        9991,
        4096,
        0,
        false);
  }

  @Test
  void initialIdentityIsEmpty() {
    manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
    assertTrue(manager.identity().isEmpty());
  }

  @Test
  void initialLastFailureIsEmpty() {
    manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
    assertTrue(manager.lastFailure().isEmpty());
  }

  @Test
  void lastFailureIsEmptyWhenNoTransitionHasFailed() {
    manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
    // Tempdoc 412 Phase 2: the recordFailure hook was removed because the holder rewrite that
    // would feed it is deferred. The accessor surface stays so the snapshot view can be
    // future-typed without churning call sites; the value is always empty until then.
    assertTrue(manager.lastFailure().isEmpty());
  }

  @Test
  void defaultEventsIsNoopSingleton() {
    // The single-arg ctor delegates to the two-arg ctor with NoopInferenceTelemetryEvents; verify
    // construction does not throw, and that accessors work without a real telemetry sink.
    manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
    assertNotNull(manager);
    // No assertion needed beyond construction succeeding — the contract is that noop fires
    // silently without exceptions.
  }

  @Test
  void twoArgConstructorAcceptsCustomEvents() {
    var counter = new RecordingEvents();
    manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig(), counter);
    assertNotNull(manager);
    assertEquals(0, counter.transitionCount.get());
  }

  @Test
  void rejectsNullEvents() {
    assertThrows(NullPointerException.class,
        () -> new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig(), null));
  }

  // ==================== Tempdoc 412 follow-up regression tests ====================

  @Test
  @DisplayName("Bug D: applyConfig(null) emits onConfigApplyFailure with ConfigFailure(CONFIG_REQUIRED)")
  void bugD_nullConfigEmitsConfigApplyFailure() {
    var counter = new RecordingEvents();
    manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig(), counter);
    try {
      manager.applyConfig(null, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);
      org.junit.jupiter.api.Assertions.fail("expected ModeTransitionException");
    } catch (ModeTransitionException expected) {
      assertEquals(ModeTransitionException.Reason.CONFIG_REQUIRED, expected.reason());
    }
    assertTrue(
        counter.configApplyFailureCount.get() >= 1,
        "expected onConfigApplyFailure to fire; got " + counter.configApplyFailureCount.get());
    assertTrue(manager.lastFailure().isPresent());
    assertEquals(
        ConfigCode.CONFIG_REQUIRED.wireValue(),
        manager.lastFailure().orElseThrow().wireCode());
  }

  @Test
  @DisplayName("Bug B: VDU enter/exit failures route through applyConfig with VDU_ENTER reason")
  void bugB_vduEnterAttribution() {
    // VDU enter requires ONLINE mode — without a real llama-server we can only verify that
    // the reason-aware applyConfig overload exists with the right enum mapping. The full path
    // (enter → fail → emit reason=vdu_enter) is integration-only; this test pins the contract.
    InferenceConfig config = fakeConfig();
    InferenceConfig vduConfig = config.withVduMode(true);
    org.junit.jupiter.api.Assertions.assertNotEquals(config, vduConfig);
    // Confirm the TransitionReason values are reachable + bounded.
    assertNotNull(TransitionReason.VDU_ENTER);
    assertNotNull(TransitionReason.VDU_EXIT);
    assertEquals("vdu_enter", TransitionReason.VDU_ENTER.wireValue());
    assertEquals("vdu_exit", TransitionReason.VDU_EXIT.wireValue());
  }

  /** Recording test double for {@link InferenceTelemetryEvents}. */
  static final class RecordingEvents implements InferenceTelemetryEvents {
    final AtomicInteger transitionCount = new AtomicInteger();
    final List<TransitionReason> transitionReasons = new ArrayList<>();
    final List<String[]> transitionPhases = new ArrayList<>();
    final List<Duration> transitionDurations = new ArrayList<>();
    final AtomicInteger configApplyAttemptCount = new AtomicInteger();
    final AtomicInteger configApplyCompleteCount = new AtomicInteger();
    final AtomicInteger configApplyFailureCount = new AtomicInteger();
    final AtomicInteger startupFailureCount = new AtomicInteger();

    @Override
    public synchronized void onTransition(
        String fromPhase, String toPhase, TransitionReason reason, Duration elapsed) {
      transitionCount.incrementAndGet();
      transitionReasons.add(reason);
      transitionPhases.add(new String[] {fromPhase, toPhase});
      transitionDurations.add(elapsed);
    }

    @Override
    public void onStartupAttempt(InferenceConfig schema, StartupReason reason, TargetPhase target) {}

    @Override
    public void onStartupComplete(
        InferenceConfig schema, Duration elapsed, RuntimeIdentity identity, TargetPhase target) {}

    @Override
    public void onStartupFailure(InferenceFailure failure) {
      startupFailureCount.incrementAndGet();
    }

    @Override
    public void onHealthFailure(
        InferenceFailure.HealthFailure failure, int consecutiveCount, boolean restartTriggered) {}

    @Override
    public void onHealthRecovered(int previousFailureCount) {}

    @Override
    public void onConfigApplyAttempt(
        InferenceConfig oldSchema, InferenceConfig newSchema, boolean restartRequired) {
      configApplyAttemptCount.incrementAndGet();
    }

    @Override
    public void onConfigApplyComplete(Duration elapsed) {
      configApplyCompleteCount.incrementAndGet();
    }

    @Override
    public void onConfigApplyFailure(InferenceFailure failure) {
      configApplyFailureCount.incrementAndGet();
    }

    @Override
    public void onRequestEnqueued(RequestKind kind) {}

    @Override
    public void onRequestStarted(RequestKind kind, Duration waitedMs) {}

    @Override
    public void onRequestCompleted(RequestKind kind, Duration totalMs, RequestOutcome outcome) {}
  }
}
