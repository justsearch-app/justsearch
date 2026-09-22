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
 * {@code restartTriggered=true} must reach the events sink. Removing the emit (or weakening
 * its tag) breaks this test.
 */
final class LlamaServerOpsCrashTelemetryTest {
  private final java.util.List<LlamaServerOps> owned = new java.util.ArrayList<>();

  @AfterEach
  void closeOwnedSchedulers() {
    owned.forEach(LlamaServerOps::shutdown);
  }

  @Test
  @DisplayName("Bug F: handleServerCrash emits onHealthFailure(PROCESS_DIED, restart_triggered)")
  void bugF_processDeath_emitsTypedHealthFailure() throws Exception {
    RecordingEvents events = new RecordingEvents();
    LlamaServerOps ops = newOps(events, () -> Mode.ONLINE, () -> false);

    LlamaServerTestAccess.crashCurrent(ops);

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
  void bugF_secondCrash_incrementsCount() throws Exception {
    RecordingEvents events = new RecordingEvents();
    LlamaServerOps ops = newOps(events, () -> Mode.ONLINE, () -> false);

    LlamaServerTestAccess.crashCurrent(ops);
    LlamaServerTestAccess.crashCurrent(ops);

    assertEquals(2, events.healthFailures.size());
    assertEquals(1, events.healthFailures.get(0).consecutiveCount());
    assertEquals(2, events.healthFailures.get(1).consecutiveCount());
  }

  @Test
  @DisplayName("Brain give-up: reaching MAX_CRASHES fires goOfflineFromMaxCrashes (terminal OFFLINE)")
  void maxCrashes_triggersTerminalGiveUp() throws Exception {
    RecordingEvents events = new RecordingEvents();
    AtomicInteger giveUps = new AtomicInteger(0);
    LlamaServerOps ops =
        newOps(events, () -> Mode.ONLINE, () -> false, giveUps::incrementAndGet);

    // Recovery callbacks are no-ops; each owned crash emits synchronously and only the
    // cap schedules terminal OFFLINE. Await that callback without inducing extra crashes.
    int cap = BrainSupervisionPolicy.defaults().maxCrashes();
    for (int i = 0; i < cap; i++) {
      LlamaServerTestAccess.crashCurrent(ops);
    }

    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    while (giveUps.get() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }

    assertEquals(1, giveUps.get(),
        "reaching maxCrashes (" + cap + ") must fire one terminal give-up callback");
    assertTrue(
        events.healthFailures.stream().anyMatch(h -> h.consecutiveCount() >= cap),
        "a PROCESS_DIED health failure at or past the cap must have been emitted");
  }

  // ==================== Test helpers ====================

  private LlamaServerOps newOps(
      InferenceTelemetryEvents events,
      Supplier<Mode> currentMode,
      Supplier<Boolean> usingExternal) throws Exception {
    return newOps(events, currentMode, usingExternal, () -> {});
  }

  private LlamaServerOps newOps(
      InferenceTelemetryEvents events,
      Supplier<Mode> currentMode,
      Supplier<Boolean> usingExternal,
      Runnable goOfflineFromMaxCrashes) throws Exception {
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
            ignored -> {},
            ignored -> goOfflineFromMaxCrashes.run(),
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

    // Keep event recording safe for callbacks delivered by the recovery scheduler.
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
