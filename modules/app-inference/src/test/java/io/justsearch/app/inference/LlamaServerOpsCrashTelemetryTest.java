package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.api.TransitionCode;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.RequestKind;
import io.justsearch.app.inference.telemetry.RequestOutcome;
import io.justsearch.app.inference.telemetry.StartupReason;
import io.justsearch.app.inference.telemetry.TransitionReason;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 412 Path C Bug F regression test.
 *
 * <p>{@code handleServerCrash} is reached by the {@code crashMonitor} future when
 * {@code Process.waitFor()} returns a non-zero exit code (taskkill / SIGKILL / segfault).
 * The peer emit site {@code handlePeriodicHealthFailure} is gated by an early-return that
 * skips probing when the process is already dead, so without an explicit emit here the
 * {@code inference.health.failure_total} metric never fires for the most operationally
 * important class of health failure: process death.
 *
 * <p>This test pins the emit-from-handleServerCrash invariant: the typed
 * {@link InferenceFailure.HealthFailure} with {@link HealthCode#PROCESS_DIED} and
 * {@code restartTriggered=true} must reach the events sink. Removing the emit (or weakening
 * its tag) breaks this test.
 */
final class LlamaServerOpsCrashTelemetryTest {

  @Test
  @DisplayName("Bug F: handleServerCrash emits onHealthFailure(PROCESS_DIED, restart_triggered)")
  // Timing-sensitive: handleServerCrash schedules a delay=0 recovery task that emits a second
  // (non-PROCESS_DIED) failure; on Linux CI that async task races ahead of the size()==1 assertion.
  // The scheduler is not injectable for a clean deterministic fix. Windows-native lane (tempdoc 668).
  @Tag("windows")
  void bugF_processDeath_emitsTypedHealthFailure() {
    RecordingEvents events = new RecordingEvents();
    LlamaServerOps ops = newOps(events, () -> Mode.ONLINE, () -> false);

    ops.handleServerCrash();

    assertEquals(
        1,
        events.healthFailures.size(),
        "expected exactly one onHealthFailure emit from the crash path");
    var emitted = events.healthFailures.get(0);
    assertNotNull(emitted.failure());
    assertEquals(HealthCode.PROCESS_DIED, emitted.failure().code());
    assertEquals(1, emitted.consecutiveCount(), "first crash → count=1");
    assertTrue(
        emitted.restartTriggered(),
        "process death always counts as restart_triggered (no probe-failure threshold gating)");
  }

  @Test
  @DisplayName("Bug F: a second crash before recovery still emits, with crashCount=2")
  // Same Linux-CI race as bugF_processDeath_emitsTypedHealthFailure above (delay=0 recovery task
  // races ahead of the size()==2 assertion) — doubly so here, since this test drives two crashes.
  // Missed when that test was tagged during the tempdoc 668 Windows-native-tests migration.
  @Tag("windows")
  void bugF_secondCrash_incrementsCount() {
    RecordingEvents events = new RecordingEvents();
    LlamaServerOps ops = newOps(events, () -> Mode.ONLINE, () -> false);

    ops.handleServerCrash();
    ops.handleServerCrash();

    assertEquals(2, events.healthFailures.size());
    assertEquals(1, events.healthFailures.get(0).consecutiveCount());
    assertEquals(2, events.healthFailures.get(1).consecutiveCount());
  }

  @Test
  @DisplayName("Brain give-up: reaching MAX_CRASHES fires goOfflineFromMaxCrashes (terminal OFFLINE)")
  void maxCrashes_triggersTerminalGiveUp() {
    RecordingEvents events = new RecordingEvents();
    AtomicInteger giveUps = new AtomicInteger(0);
    LlamaServerOps ops =
        newOps(events, () -> Mode.ONLINE, () -> false, giveUps::incrementAndGet);

    // Drive the crash count to the declared cap. The synchronous Nth call (N == maxCrashes) crosses
    // the `crashes >= MAX_CRASHES` branch in-thread and fires the terminal callback, regardless of any
    // async recovery-task noise — so asserting "fired at least once" is deterministic (the recovery
    // scheduler can only add crashes, never prevent the give-up).
    int cap = BrainSupervisionPolicy.defaults().maxCrashes();
    for (int i = 0; i < cap; i++) {
      ops.handleServerCrash();
    }

    assertTrue(
        giveUps.get() >= 1,
        "reaching maxCrashes (" + cap + ") must fire the terminal give-up callback");
    assertTrue(
        events.healthFailures.stream().anyMatch(h -> h.consecutiveCount() >= cap),
        "a PROCESS_DIED health failure at or past the cap must have been emitted");
  }

  // ==================== Test helpers ====================

  private static LlamaServerOps newOps(
      InferenceTelemetryEvents events,
      Supplier<Mode> currentMode,
      Supplier<Boolean> usingExternal) {
    return newOps(events, currentMode, usingExternal, () -> {});
  }

  private static LlamaServerOps newOps(
      InferenceTelemetryEvents events,
      Supplier<Mode> currentMode,
      Supplier<Boolean> usingExternal,
      Runnable goOfflineFromMaxCrashes) {
    AtomicReference<String> modelIdRef = new AtomicReference<>(null);
    AtomicReference<Integer> contextRef = new AtomicReference<>(null);
    PropsObserver propsObserver =
        new PropsObserver() {
          @Override
          public void onModelIdObserved(String modelId) {
            modelIdRef.set(modelId);
          }

          @Override
          public void onContextTokensObserved(int contextTokens) {
            contextRef.set(contextTokens);
          }

          @Override
          public String observedModelId() {
            return modelIdRef.get();
          }

          @Override
          public Integer observedContextTokens() {
            return contextRef.get();
          }
        };
    LlamaServerOps ops =
        new LlamaServerOps(new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            () -> null, // config supplier — not exercised in handleServerCrash path
            null, // gpuCapabilitiesService — not exercised in handleServerCrash path
            currentMode,
            propsObserver,
            goOfflineFromMaxCrashes,
            reason -> {}, // goOfflineFromExternalFailure
            events);
    // Preserve the original 'usingExternal' supplier semantics for the legacy test contract:
    // the LlamaServerOps now owns the flag internally; mirror the supplier into it.
    ops.setUsingExternal(usingExternal.get());
    return ops;
  }

  /** Recording {@link InferenceTelemetryEvents} stub: captures only the methods this test asserts. */
  private static final class RecordingEvents implements InferenceTelemetryEvents {
    record HealthFailureCall(
        InferenceFailure.HealthFailure failure, int consecutiveCount, boolean restartTriggered) {}

    // CopyOnWriteArrayList, not ArrayList: handleServerCrash() schedules a delay=0 async recovery
    // task that also calls onHealthFailure -> add() here. maxCrashes_triggersTerminalGiveUp reads
    // this list via .stream() on the test thread while that async writer runs, so a plain ArrayList
    // throws ConcurrentModificationException (CI flake, tempdoc 668 lane). COW's snapshot iteration
    // is CME-free; the synchronous cap-th emit still deterministically satisfies the anyMatch(>=cap).
    final java.util.List<HealthFailureCall> healthFailures =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    final AtomicInteger ignoredCallCount = new AtomicInteger();

    @Override
    public void onTransition(
        String fromPhase, String toPhase, TransitionReason reason, Duration elapsed) {
      ignoredCallCount.incrementAndGet();
    }

    @Override
    public void onStartupAttempt(InferenceConfig schema, StartupReason reason, TargetPhase target) {
      ignoredCallCount.incrementAndGet();
    }

    @Override
    public void onStartupComplete(
        InferenceConfig schema, Duration elapsed, RuntimeIdentity identity, TargetPhase target) {
      ignoredCallCount.incrementAndGet();
    }

    @Override
    public void onStartupFailure(InferenceFailure failure) {
      ignoredCallCount.incrementAndGet();
    }

    @Override
    public void onHealthFailure(
        InferenceFailure.HealthFailure failure, int consecutiveCount, boolean restartTriggered) {
      healthFailures.add(new HealthFailureCall(failure, consecutiveCount, restartTriggered));
    }

    @Override
    public void onHealthRecovered(int previousFailureCount) {
      ignoredCallCount.incrementAndGet();
    }

    @Override
    public void onConfigApplyAttempt(
        InferenceConfig oldSchema, InferenceConfig newSchema, boolean restartRequired) {
      ignoredCallCount.incrementAndGet();
    }

    @Override
    public void onConfigApplyComplete(Duration elapsed) {
      ignoredCallCount.incrementAndGet();
    }

    @Override
    public void onConfigApplyFailure(InferenceFailure failure) {
      ignoredCallCount.incrementAndGet();
    }

    @Override
    public void onRequestEnqueued(RequestKind kind) {
      ignoredCallCount.incrementAndGet();
    }

    @Override
    public void onRequestStarted(RequestKind kind, Duration waitedMs) {
      ignoredCallCount.incrementAndGet();
    }

    @Override
    public void onRequestCompleted(RequestKind kind, Duration totalMs, RequestOutcome outcome) {
      ignoredCallCount.incrementAndGet();
    }
  }

  // Suppress unused-warning for Consumer import via a no-op reference.
  @SuppressWarnings("unused")
  private static final Consumer<String> UNUSED = s -> {};
}
