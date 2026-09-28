package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The monitor's HEALTH arm. Every case stubs {@code hasClient() == true}: tempdoc 825 gave the
 * monitor a second arm, and a bound client is the precondition that selects this one — without the
 * stub these cases would exercise the boot-recovery arm and the never-verifications would pass for
 * the wrong reason. The boot-recovery arm has its own suite ({@code KnowledgeServerBootRecoveryTest},
 * over a real bootstrap rather than a mock).
 */
final class KnowledgeServerHealthMonitorTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @Test
  void failedBoundIndexReopensThroughItsPhysicalOwnerAndUsesOneSlot() throws Exception {
    try (var components = new TestEngineComponents()) {
      var index = components.register(new ComponentSpec("index", true, Set.of(),
          ComponentSpec.ComposeCapability.BESIDE, Duration.ofSeconds(60), 2));
      index.transition(ComponentState.FAILED, LifecycleReasonCode.WORKER_SPAWN_FAILED.code(),
          "index owner lost");
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      when(bootstrap.hasClient()).thenReturn(true);
      var enteredClose = new CountDownLatch(1);
      var releaseClose = new CountDownLatch(1);
      when(bootstrap.recomposeForRecovery(org.mockito.ArgumentMatchers.any())).thenAnswer(ignored -> {
        enteredClose.countDown();
        assertTrue(releaseClose.await(5, TimeUnit.SECONDS));
        index.transition(ComponentState.READY, null, "same configuration serving");
        return true;
      });
      var handedOver = new CountDownLatch(1);
      try (var monitor = new KnowledgeServerHealthMonitor(processExecutors, bootstrap)) {
        monitor.componentRegistry(components);
        monitor.onRecoveryConnected(ignored -> handedOver.countDown());
        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("index"));
        assertTrue(enteredClose.await(5, TimeUnit.SECONDS));
        assertEquals(ComponentRecoveryAuthority.Outcome.ALREADY_RUNNING,
            monitor.requestComponentRecovery("index"));
        releaseClose.countDown();
        assertTrue(handedOver.await(5, TimeUnit.SECONDS));
        assertEquals(ComponentState.READY, index.snapshot().state());
        assertEquals(1, index.snapshot().recoveryAttempts());
        verify(bootstrap, times(1)).recomposeForRecovery(org.mockito.ArgumentMatchers.any());
      } finally {
        releaseClose.countDown();
      }
    }
  }

  @Test
  void boundRecoveryCountPreservesTheRetainedFatalRemedy() throws Exception {
    try (var components = TestEngineComponents.fourComponents()) {
      var index = new io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle(
          components.handle("index"));
      String remedy = "index.schema_mismatch.policy requires operator repair";
      index.transition(ComponentState.FAILED,
          LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH.code(), remedy);
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      when(bootstrap.hasClient()).thenReturn(true);
      when(bootstrap.indexFatalCode()).thenReturn(LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      when(bootstrap.recomposeForRecovery(org.mockito.ArgumentMatchers.any())).thenAnswer(ignored -> {
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return false;
      });
      try (var monitor = new KnowledgeServerHealthMonitor(processExecutors, bootstrap)) {
        monitor.componentRegistry(components);
        try {
          assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
              monitor.requestComponentRecovery("index"));
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          assertEquals(1, index.snapshot().recoveryAttempts());
          assertEquals(ComponentState.STARTING, index.snapshot().state());
          assertEquals(LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH.code(),
              index.snapshot().reasonCode());
          assertEquals(remedy, index.snapshot().evidence());
        } finally {
          release.countDown();
        }
      }
      verify(bootstrap).recomposeForRecovery(org.mockito.ArgumentMatchers.any());
    }
  }

  @Test
  void overdueInitialIndexOpenFailsWithItsAwaitedResourceWhileApiStaysReady()
      throws Exception {
    try (var components = new TestEngineComponents()) {
      var api = components.register(new ComponentSpec("api", true, Set.of(),
          ComponentSpec.ComposeCapability.IN_PLACE, Duration.ZERO, 2));
      var index = components.register(new ComponentSpec("index", true, Set.of(),
          ComponentSpec.ComposeCapability.BESIDE, Duration.ofMillis(1), 2));
      api.transition(ComponentState.READY, null, "loopback bound");
      index.transition(ComponentState.STARTING, LifecycleReasonCode.WORKER_STARTING.code(),
          "index root lock at test-index");
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      var unfinishedStart = new CompletableFuture<Void>();
      try (var monitor = new KnowledgeServerHealthMonitor(processExecutors, bootstrap)) {
        monitor.observeInitialStartup(unfinishedStart);
        var handovers = new java.util.concurrent.atomic.AtomicInteger();
        var reconciles = new java.util.concurrent.atomic.AtomicInteger();
        monitor.onTick(reconciles::incrementAndGet);
        monitor.onRecoveryConnected(ignored -> handovers.incrementAndGet());
        Thread.sleep(5);
        monitor.tick();
        assertEquals(ComponentState.FAILED, index.snapshot().state());
        assertTrue(index.snapshot().evidence().contains("index root lock at test-index"));
        assertEquals(ComponentState.READY, api.snapshot().state());
        assertFalse(unfinishedStart.isDone());
        assertEquals(1, reconciles.get(), "the pending owner must not skip readiness reconciliation");
        verify(bootstrap, never()).tryCheckHealth();

        unfinishedStart.complete(null);
        when(bootstrap.hasClient()).thenReturn(true);
        when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));
        monitor.tick();
        monitor.tick();
        assertEquals(1, handovers.get(), "late initial success has one handover");
        monitor.close(); // Drain any incorrectly queued replacement before verifying.
        verify(bootstrap, never()).recomposeForRecovery(org.mockito.ArgumentMatchers.any());
        verify(bootstrap, never()).closeForUpgrade();
        verify(bootstrap, never()).startForRecovery();
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void manualRecoveryCannotReplaceInitialOwnerBeforeItsHandover(boolean startCompleted) throws Exception {
    try (var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      index.transition(ComponentState.FAILED, LifecycleReasonCode.WORKER_SPAWN_FAILED.code(),
          "initial deadline elapsed");
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      when(bootstrap.hasClient()).thenReturn(true);
      var initialStart = new CompletableFuture<Void>();
      if (startCompleted) initialStart.complete(null);
      try (var monitor = new KnowledgeServerHealthMonitor(processExecutors, bootstrap)) {
        monitor.componentRegistry(components);
        monitor.observeInitialStartup(initialStart);
        assertEquals(ComponentRecoveryAuthority.Outcome.ALREADY_RUNNING,
            monitor.requestComponentRecovery("index"));
      }
      verify(bootstrap, never()).recomposeForRecovery(org.mockito.ArgumentMatchers.any());
      verify(bootstrap, never()).closeForUpgrade();
      verify(bootstrap, never()).startForRecovery();
    }
  }

  @Test
  void busyPhysicalOwnerSkipsHealthDecisionButStillReconciles() throws Exception {
    try (var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      index.transition(ComponentState.FAILED, LifecycleReasonCode.WORKER_SPAWN_FAILED.code(),
          "prior deadline");
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      when(bootstrap.hasClient()).thenReturn(true);
      when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.empty(), java.util.Optional.of(true));
      var reconciles = new java.util.concurrent.atomic.AtomicInteger();
      try (var monitor = new KnowledgeServerHealthMonitor(processExecutors, bootstrap)) {
        monitor.onTick(reconciles::incrementAndGet);
        monitor.tick();
        monitor.tick();
        assertEquals(2, reconciles.get());
      }
      verify(bootstrap, times(2)).tryCheckHealth();
      verify(bootstrap, never()).recomposeForRecovery(org.mockito.ArgumentMatchers.any());
      assertEquals(0, index.snapshot().recoveryAttempts());
    }
  }

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void healthArmDelegatesPhysicalObservationAndInitializationToBootstrap(boolean healthy) {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(healthy));
    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(processExecutors, bootstrap);
    var reconciles = new java.util.concurrent.atomic.AtomicInteger();
    monitor.onTick(reconciles::incrementAndGet);
    monitor.tick();
    monitor.tick();
    verify(bootstrap, times(2)).tryCheckHealth();
    verify(bootstrap, never()).workerCapability();
    assertEquals(2, reconciles.get());
  }

  @Test
  void tickSwallowsExceptionsAndStillRequestsReadinessReconciliation() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.hasClient()).thenReturn(true);
    doThrow(new RuntimeException("direct health failure")).when(bootstrap).tryCheckHealth();
    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(processExecutors, bootstrap);
    var reconciles = new java.util.concurrent.atomic.AtomicInteger();
    monitor.onTick(reconciles::incrementAndGet);
    assertDoesNotThrow(monitor::tick);
    verify(bootstrap).tryCheckHealth();
    verify(bootstrap, never()).workerCapability();
    assertEquals(1, reconciles.get());
  }

  // ---- Tempdoc 630: resume detection + eager re-validation -------------------------------------

  @Test
  void firstTickSeedsClockAndDoesNotReValidate() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(processExecutors, bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // first tick: no prior wall stamp → never a resume

    verify(bootstrap, never()).captureClient();
    verify(client, never()).reindexPersistedRoots(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void normalCadenceTickDoesNotReValidate() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(processExecutors, bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // seed
    clock[0] += 10_500L; // a normal ~10s tick (with jitter), under the 30s threshold
    monitor.tick();

    // Review S6: a `verify(client, never()).reconnect()` stood here. The method is gone, and the
    // property it asserted is stronger on the line above it — the monitor never even asks the
    // bootstrap for a client, so there is nothing it could have called on one.
    verify(bootstrap, never()).client();
    verify(client, never()).reindexPersistedRoots(org.mockito.ArgumentMatchers.any());
  }

  /**
   * Lane F stage A item A11: the post-resume actuator used to be two calls, a channel reconnect
   * and a watcher re-register + reconcile. There is no channel, so the reconnect is gone.
   *
   * <p>A11 replaced the dropped assertion with its negative, {@code verify(client,
   * never()).reconnect()}. Review S6 then deleted the method itself, which makes that negative
   * unwritable — and unnecessary: {@code verifyNoMoreInteractions} below asserts the same thing
   * over the whole client surface rather than one method of it, so "a resume must do the reconcile
   * and nothing else" survives the deletion in a stronger form than it had.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void largeGapTriggersReconcileAndNoReconnectUnlessFaultFixtureIsolated(boolean isolated) {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient client = mock(KnowledgeClient.class);
    KnowledgeServerBootstrap.ClientLease lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));
    when(bootstrap.captureClient()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    when(lease.withClient(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0))
            .apply(client));
    when(bootstrap.automaticRootProducersSuppressed()).thenReturn(isolated);

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(processExecutors, bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // seed
    clock[0] += 3_600_000L; // a 1-hour gap → suspend/resume
    monitor.tick();

    verify(client, times(isolated ? 0 : 1)).reindexPersistedRoots(org.mockito.ArgumentMatchers.any());
    verifyNoMoreInteractions(client);
  }

  @Test
  void resumeReValidationSurvivesUnavailableClient() {
    // A resume while the worker client is not available (captureClient() throws) must not abort the tick or
    // flip the capability to DEGRADED. Post-825 this is the narrow race rather than the steady state:
    // hasClient() and captureClient() can straddle a concurrent
    // closeForUpgrade can null it between them. The steady "no client at all" state is the
    // boot-recovery arm's, tested over a real bootstrap.
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));
    when(bootstrap.captureClient()).thenThrow(new IllegalStateException("Knowledge Server not started"));

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(processExecutors, bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // seed
    clock[0] += 3_600_000L;
    assertDoesNotThrow(monitor::tick);
    // checkHealth still ran on both ticks; the resume path did not knock the capability to DEGRADED.
    verify(bootstrap, times(2)).tryCheckHealth();
  }

  @Test
  void constructorRejectsNullBootstrap() {
    assertThrows(IllegalArgumentException.class, () -> new KnowledgeServerHealthMonitor(processExecutors, null));
  }

  @Test
  void closeIsIdempotent() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(processExecutors, bootstrap);
    monitor.close();
    assertDoesNotThrow(monitor::close);
  }

  @Test
  void nonPositivePollIntervalFallsBackToDefault() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    assertDoesNotThrow(() -> new KnowledgeServerHealthMonitor(processExecutors, bootstrap, 0L).close());
    assertDoesNotThrow(() -> new KnowledgeServerHealthMonitor(processExecutors, bootstrap, -1L).close());
  }

  /**
   * Tempdoc 885 item 6: the monitor hosts the Worker-status sampler, so its inter-tick delay became
   * variable. The clamp is the part worth a test — an unclamped supplier could return 0 and spin the
   * single monitor thread, or a value larger than the configured interval and silently slow the
   * health poll the monitor exists for.
   */
  @Test
  void tickIntervalSupplierIsClampedToTheConfiguredInterval() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    try (KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(processExecutors, bootstrap, 10_000L)) {
      assertEquals(
          10_000L, monitor.nextTickDelayMs(), "no supplier keeps the configured fixed cadence");

      monitor.tickIntervalSupplier(() -> 2_000L);
      assertEquals(2_000L, monitor.nextTickDelayMs());

      monitor.tickIntervalSupplier(() -> 0L);
      assertEquals(
          KnowledgeServerHealthMonitor.MIN_TICK_INTERVAL_MS,
          monitor.nextTickDelayMs(),
          "a zero request must be floored, never spun");

      monitor.tickIntervalSupplier(() -> 600_000L);
      assertEquals(
          10_000L, monitor.nextTickDelayMs(), "a supplier cannot slow the health poll past its own"
              + " configured interval");

      monitor.tickIntervalSupplier(
          () -> {
            throw new IllegalStateException("supplier blew up");
          });
      assertEquals(
          10_000L, monitor.nextTickDelayMs(), "a throwing supplier falls back, never wedges");
    }
  }
}
