package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 412 Path C Bug F regression test.
 *
 * <p>{@code handleServerCrash} is reached by the {@code crashMonitor} future when
 * {@code Process.waitFor()} returns after an uncancelled exit (including taskkill / SIGKILL / segfault).
 * The peer emit site {@code handlePeriodicHealthFailure} is gated by an early-return that
 * skips probing when the process is already dead, so without an explicit emit here the
 * {@code inference.health.failure_total} metric never fires for the most operationally
 * important class of health failure: process death.
 *
 * <p>This test pins the emit-from-handleServerCrash invariant: the typed
 * {@link InferenceFailure.HealthFailure} with {@link HealthCode#PROCESS_DIED} and
 * {@code restartTriggered=false} must reach the events sink because the component monitor now owns
 * restart admission. Removing the emit or adding a native retry breaks this test.
 */
final class LlamaServerOpsCrashTelemetryTest {
  private final java.util.List<LlamaServerOps> owned = new java.util.ArrayList<>();

  @AfterEach
  void closeOwnedSchedulers() {
    owned.forEach(LlamaServerOps::shutdown);
  }

  @Test
  @DisplayName("process death emits telemetry and one component failure signal")
  void bugF_processDeath_emitsTypedHealthFailure() throws Exception {
    RecordingEvents events = new RecordingEvents();
    AtomicInteger failures = new AtomicInteger();
    LlamaServerOps ops = newOps(events, () -> Mode.ONLINE, () -> false, failures::incrementAndGet);

    LlamaServerTestAccess.crashCurrent(ops);

    assertEquals(
        1,
        events.healthFailures.size(),
        "expected exactly one onHealthFailure emit from the crash path");
    var emitted = events.healthFailures.get(0);
    assertNotNull(emitted.failure());
    assertEquals(HealthCode.PROCESS_DIED, emitted.failure().code());
    assertEquals(1, emitted.consecutiveCount(), "first crash → count=1");
    assertFalse(emitted.restartTriggered(), "the component monitor owns restart admission");
    assertEquals(1, failures.get());
  }

  @Test
  @DisplayName("one physical owner emits only one component failure signal")
  void duplicateCrashCallbackIsDeduplicatedPerOwner() throws Exception {
    RecordingEvents events = new RecordingEvents();
    AtomicInteger failures = new AtomicInteger();
    LlamaServerOps ops = newOps(events, () -> Mode.ONLINE, () -> false, failures::incrementAndGet);

    LlamaServerTestAccess.crashCurrent(ops);
    LlamaServerTestAccess.crashCurrent(ops);

    assertEquals(1, events.healthFailures.size());
    assertEquals(1, events.healthFailures.get(0).consecutiveCount());
    assertEquals(1, failures.get());
  }

  // ==================== Test helpers ====================

  @Test
  void healthThresholdSignalsOwnerWithoutClaimingRestartAdmission() throws Exception {
    RecordingEvents events = new RecordingEvents();
    AtomicInteger failures = new AtomicInteger();
    LlamaServerOps ops = newOps(events, () -> Mode.ONLINE, () -> false, failures::incrementAndGet);
    java.util.concurrent.ScheduledFuture<?> periodic =
        org.mockito.Mockito.mock(java.util.concurrent.ScheduledFuture.class);
    var taskField = LlamaServerOps.class.getDeclaredField("periodicHealthTask");
    taskField.setAccessible(true);
    taskField.set(ops, periodic);

    for (int attempt = 0; attempt < 3; attempt++) {
      ops.handlePeriodicHealthFailure("health timeout", false);
    }

    assertEquals(3, events.healthFailures.size());
    assertEquals(1, failures.get());
    events.healthFailures.forEach(event -> assertFalse(event.restartTriggered()));
    org.mockito.Mockito.verify(periodic).cancel(false);
    org.junit.jupiter.api.Assertions.assertNull(taskField.get(ops));
  }

  private LlamaServerOps newOps(
      InferenceTelemetryEvents events,
      Supplier<Mode> currentMode,
      Supplier<Boolean> usingExternal,
      Runnable managedFailure) throws Exception {
    AtomicReference<String> modelIdRef = new AtomicReference<>(null);
    AtomicReference<Integer> contextRef = new AtomicReference<>(null);
    PropsObserver propsObserver =
        new PropsObserver() {
          @Override
          public void onModelIdObserved(String modelId, LlamaServerConfigContext context) {
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
            null, // gpuCapabilitiesService — not exercised in handleServerCrash path
            currentMode,
            propsObserver,
            ignored -> managedFailure.run(),
            (reason, guard) -> {}, // goOfflineFromExternalFailure
            events);
    // Preserve the original 'usingExternal' supplier semantics for the legacy test contract:
    // the LlamaServerOps now owns the flag internally; mirror the supplier into it.
    ops.setUsingExternal(usingExternal.get());
    var config = new InferenceConfig(java.nio.file.Path.of("llama-server"),
        java.nio.file.Path.of("model.gguf"), null, 8082, 4096, 0, false);
    var context = new LlamaServerConfigContext(config,
        io.justsearch.configuration.resolved.TestResolvedConfigHelper.fromEntries(java.util.Map.of()));
    LlamaServerTestAccess.installLogicalOwner(ops, new LlamaServerOps.StartResult(context,
        LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
        LlamaServerOps.StartDisposition.LAUNCHED_MANAGED, "telemetry-fixture"));
    owned.add(ops);
    return ops;
  }

  /** Recording {@link InferenceTelemetryEvents} stub: captures only the methods this test asserts. */
  private static final class RecordingEvents implements InferenceTelemetryEvents {
    record HealthFailureCall(
        InferenceFailure.HealthFailure failure, int consecutiveCount, boolean restartTriggered) {}

    // Health probes and process-exit callbacks can emit from different threads.
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
