package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import org.junit.jupiter.api.Test;

/**
 * The monitor's HEALTH arm. Every case stubs {@code hasClient() == true}: tempdoc 825 gave the
 * monitor a second arm, and a bound client is the precondition that selects this one — without the
 * stub these cases would exercise the boot-recovery arm and the never-verifications would pass for
 * the wrong reason. The boot-recovery arm has its own suite ({@code KnowledgeServerBootRecoveryTest},
 * over a real bootstrap rather than a mock).
 */
final class KnowledgeServerHealthMonitorTest {

  @Test
  void tickTriggersInitializationOnErrorToReadyTransition() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, "error");
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.workerCapability()).thenReturn(cap);
    when(bootstrap.checkHealth())
        .thenAnswer(
            inv -> {
              cap.transition(CapabilityHealth.READY, null);
              return true;
            });

    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);
    monitor.tick();

    verify(bootstrap).checkHealth();
    verify(bootstrap, times(1)).completeReadyInitializationFromMonitor();
  }

  @Test
  void tickTriggersInitializationOnPendingToReadyTransition() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.workerCapability()).thenReturn(cap);
    when(bootstrap.checkHealth())
        .thenAnswer(
            inv -> {
              cap.transition(CapabilityHealth.READY, null);
              return true;
            });

    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);
    monitor.tick();

    verify(bootstrap, times(1)).completeReadyInitializationFromMonitor();
  }

  @Test
  void tickDoesNotTriggerInitializationWhenAlreadyReady() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.READY, null);
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.workerCapability()).thenReturn(cap);
    when(bootstrap.checkHealth()).thenReturn(true);

    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);
    monitor.tick();

    verify(bootstrap, never()).completeReadyInitializationFromMonitor();
  }

  @Test
  void tickDoesNotTriggerInitializationWhenStillDegraded() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, "error");
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.workerCapability()).thenReturn(cap);
    when(bootstrap.checkHealth()).thenReturn(false);

    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);
    monitor.tick();

    verify(bootstrap, never()).completeReadyInitializationFromMonitor();
  }

  @Test
  void tickSwallowsExceptionsSoExecutorSurvives() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.workerCapability()).thenReturn(cap);
    doThrow(new RuntimeException("transient gRPC failure")).when(bootstrap).checkHealth();

    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);

    assertDoesNotThrow(monitor::tick);
    verify(bootstrap).checkHealth();
    verify(bootstrap, never()).completeReadyInitializationFromMonitor();
  }

  // ---- Tempdoc 630: resume detection + eager re-validation -------------------------------------

  @Test
  void firstTickSeedsClockAndDoesNotReValidate() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.workerCapability()).thenReturn(new WorkerCapability());
    when(bootstrap.checkHealth()).thenReturn(true);

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // first tick: no prior wall stamp → never a resume

    verify(bootstrap, never()).client();
    verify(client, never()).reindexPersistedRoots();
  }

  @Test
  void normalCadenceTickDoesNotReValidate() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.workerCapability()).thenReturn(new WorkerCapability());
    when(bootstrap.checkHealth()).thenReturn(true);

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // seed
    clock[0] += 10_500L; // a normal ~10s tick (with jitter), under the 30s threshold
    monitor.tick();

    // Review S6: a `verify(client, never()).reconnect()` stood here. The method is gone, and the
    // property it asserted is stronger on the line above it — the monitor never even asks the
    // bootstrap for a client, so there is nothing it could have called on one.
    verify(bootstrap, never()).client();
    verify(client, never()).reindexPersistedRoots();
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
  @Test
  void largeGapTriggersReconcileAndNoReconnect() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.workerCapability()).thenReturn(new WorkerCapability());
    when(bootstrap.checkHealth()).thenReturn(true);
    when(bootstrap.client()).thenReturn(client);

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // seed
    clock[0] += 3_600_000L; // a 1-hour gap → suspend/resume
    monitor.tick();

    verify(client, times(1)).reindexPersistedRoots();
    verifyNoMoreInteractions(client);
  }

  @Test
  void resumeReValidationSurvivesUnavailableClient() {
    // A resume while the worker client is not available (client() throws) must not abort the tick or
    // flip the capability to DEGRADED. Post-825 this is the narrow race rather than the steady state:
    // hasClient() and client() are two reads of the same volatile field, so a concurrent
    // closeForUpgrade can null it between them. The steady "no client at all" state is the
    // boot-recovery arm's, tested over a real bootstrap.
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.READY, null);
    when(bootstrap.hasClient()).thenReturn(true);
    when(bootstrap.workerCapability()).thenReturn(cap);
    when(bootstrap.checkHealth()).thenReturn(true);
    when(bootstrap.client()).thenThrow(new IllegalStateException("Knowledge Server not started"));

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // seed
    clock[0] += 3_600_000L;
    assertDoesNotThrow(monitor::tick);
    // checkHealth still ran on both ticks; the resume path did not knock the capability to DEGRADED.
    verify(bootstrap, times(2)).checkHealth();
  }

  @Test
  void constructorRejectsNullBootstrap() {
    assertThrows(IllegalArgumentException.class, () -> new KnowledgeServerHealthMonitor(null));
  }

  @Test
  void closeIsIdempotent() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);
    monitor.close();
    assertDoesNotThrow(monitor::close);
  }

  @Test
  void nonPositivePollIntervalFallsBackToDefault() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    assertDoesNotThrow(() -> new KnowledgeServerHealthMonitor(bootstrap, 0L).close());
    assertDoesNotThrow(() -> new KnowledgeServerHealthMonitor(bootstrap, -1L).close());
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
        new KnowledgeServerHealthMonitor(bootstrap, 10_000L)) {
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
