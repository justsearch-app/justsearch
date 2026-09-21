/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot-recovery ARC, driven through a real {@link KnowledgeServerBootstrap} and a real
 * {@link KnowledgeServerHealthMonitor} (tempdoc 825 §D4, middle rung of the ladder).
 *
 * <p>The default fixture supplies {@link WorkerHost#unavailable()}, so every attempted composition
 * fails deterministically. Tests of recovery completion replace it with a controlled host and
 * client at the same physical assembly boundary.
 *
 * <p>{@code tick()} is called directly rather than via {@code start()}: the schedule is the
 * executor's business, the decision is the arm's, and driving it explicitly keeps the test bounded
 * and deterministic instead of sleeping past a poll interval.
 */
@DisplayName("boot recovery: the arc, over a real bootstrap")
final class KnowledgeServerBootRecoveryTest {

  private final List<KnowledgeServerBootstrapTestFixture> fixtures = new ArrayList<>();
  private final List<AutoCloseable> recoveryResources = new ArrayList<>();
  private final Map<KnowledgeServerBootstrap, KnowledgeServerBootstrapTestFixture> fixtureByBootstrap =
      new IdentityHashMap<>();

  /** Mirrors {@code KnowledgeServerBootstrapRestartabilityTest.configFor}, with a tighter budget. */
  private static KnowledgeServerConfig configFor(Path dir) {
    return new KnowledgeServerConfig(
        false, dir, dir, dir,
        5_000L, 1_000L, 3, 1_000L, 1_000L, 300_000L, 100, 0L, 0);
  }

  /** No backoff: the arc's attempt schedule is pinned by the pure decision test, not by waiting. */
  private static final BootRecoveryPolicy NO_WAIT = new BootRecoveryPolicy(2, 0, 0);

  private static final String SPAWN_FAILED = LifecycleReasonCode.WORKER_SPAWN_FAILED.code();
  private static final String RECOVERING = LifecycleReasonCode.WORKER_RECOVERING.code();
  private static final String RECOVERY_EXHAUSTED =
      LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code();

  /** A bootstrap in the post-boot bricked state: start attempted, failed, capability pinned. */
  private KnowledgeServerBootstrap newBootstrap(Path tempDir) {
    return newBootstrap(tempDir, WorkerHost.unavailable());
  }

  private KnowledgeServerBootstrap newBootstrap(Path tempDir, WorkerHost host) {
    var fixture = KnowledgeServerBootstrapTestFixture.create(configFor(tempDir), host);
    fixtures.add(fixture);
    fixtureByBootstrap.put(fixture.bootstrap(), fixture);
    return fixture.bootstrap();
  }

  private KnowledgeServerBootstrap bricked(Path tempDir) {
    var bootstrap = newBootstrap(tempDir);
    assertThrows(Exception.class, () -> bootstrap.startWithRetry(1, 0));
    assertFalse(bootstrap.hasClient(), "the fixture must leave no client bound");
    assertEquals(
        SPAWN_FAILED,
        bootstrap.workerCapability().pendingReason(),
        "precondition: the boot failure pins worker.spawn.failed (the 821 §O.4 state)");
    return bootstrap;
  }

  private List<String> recordTransitions(KnowledgeServerBootstrap bootstrap) {
    return fixtureByBootstrap.get(bootstrap).recordIndexTransitions();
  }

  private KnowledgeServerHealthMonitor newMonitor(KnowledgeServerBootstrap bootstrap) {
    var executors = new io.justsearch.core.execution.TestEngineExecutors();
    var monitor =
        new KnowledgeServerHealthMonitor(
            executors, bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);
    recoveryResources.add(executors);
    recoveryResources.add(monitor);
    return monitor;
  }

  @AfterEach
  void closeFixtures() throws Exception {
    for (int i = recoveryResources.size() - 1; i >= 0; i--) recoveryResources.get(i).close();
    recoveryResources.clear();
    for (int i = fixtures.size() - 1; i >= 0; i--) fixtures.get(i).close();
    fixtures.clear();
    fixtureByBootstrap.clear();
  }

  @Test
  @Timeout(180)
  @DisplayName("the pinned state is re-attempted, and the recovery narration is NOT swallowed")
  void firstTickReAttemptsAndNarratesRecovering(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor = newMonitor(bootstrap);

    monitor.tick();

    // The ReasonRetention trap (825 §D2 mechanism 4): worker.recovering is TRANSIENT and the held
    // worker.spawn.failed is a FAULT, so without the recovery-supersedes arm this write is silently
    // dropped and pendingReason — published raw on the runtime manifest and the 503 body — keeps
    // saying "failed to start" while the Head is actively re-attempting.
    assertEquals(
        RECOVERING,
        bootstrap.workerCapability().pendingReason(),
        "the in-flight recovery must reach the reason slot, not be latched out by the pin");
    assertEquals(CapabilityHealth.PENDING, bootstrap.workerCapability().health());
  }

  @Test
  @Timeout(180)
  @DisplayName("a failing arc gives up exactly once, on the terminal code, after the budget")
  void arcGivesUpOnceAfterTheBudget(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor = newMonitor(bootstrap);
    List<String> seen = recordTransitions(bootstrap);

    for (int i = 0; i < NO_WAIT.maxAttempts(); i++) {
      monitor.tick();
    }
    assertFalse(
        seen.stream().anyMatch(t -> t.contains(RECOVERY_EXHAUSTED)),
        "the terminal code must not land while attempts remain: " + seen);

    monitor.tick(); // budget spent
    assertEquals(
        RECOVERY_EXHAUSTED,
        bootstrap.workerCapability().pendingReason(),
        "a spent boot-recovery budget is its own terminal code, not worker.spawn.failed");
    assertEquals(CapabilityHealth.DEGRADED, bootstrap.workerCapability().health());

    long terminalWrites = seen.stream().filter(t -> t.contains(RECOVERY_EXHAUSTED)).count();
    assertEquals(1, terminalWrites, "narrated exactly once: " + seen);

    // Further ticks are silent — no re-narration, no further spawns.
    int transitionsAtGiveUp = seen.size();
    monitor.tick();
    monitor.tick();
    assertEquals(transitionsAtGiveUp, seen.size(), "a terminal arc stays quiet: " + seen);
  }

  @Test
  @Timeout(180)
  @DisplayName("no flapping: a whole failing arc narrates no OFFLINE and no per-attempt spawn-failed")
  void arcDoesNotFlap(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor = newMonitor(bootstrap);
    List<String> seen = recordTransitions(bootstrap);

    for (int i = 0; i <= NO_WAIT.maxAttempts(); i++) {
      monitor.tick();
    }

    assertFalse(
        seen.stream().anyMatch(t -> t.startsWith("OFFLINE")),
        "closeForUpgrade between attempts must not narrate an orderly shutdown: " + seen);
    assertFalse(
        seen.stream().anyMatch(t -> t.contains(SPAWN_FAILED)),
        "the per-attempt spawn-failed pin belongs to the arc's owner, not to each cycle: " + seen);
    // One PENDING (physical recovery startup, entered once and held) + one terminal DEGRADED.
    // Anything more is the flap the acceptance criterion forbids: with N attempts, an
    // unsuppressed arc would emit
    // RECOVERING/PENDING/DEGRADED/OFFLINE per cycle.
    assertEquals(
        2,
        seen.size(),
        "an arc of " + NO_WAIT.maxAttempts() + " attempts must narrate 2 transitions, got: " + seen);
    assertTrue(seen.get(0).startsWith("PENDING"), "first: " + seen);
    assertTrue(seen.get(1).startsWith("DEGRADED"), "last: " + seen);
  }

  @Test
  @Timeout(180)
  @DisplayName("a predecessor supervisor record cannot veto local recovery or change its budget")
  void predecessorSupervisorRecordDoesNotOwnLocalRecovery(@TempDir Path tempDir) throws Exception {
    Path record = tempDir.resolve("runtime/supervisor.v1.json");
    Files.createDirectories(record.getParent());
    String exhausted = """
        {"schemaVersion":1,"kind":"engine-supervisor-state.v1","supervisor":"tauri",
         "state":"exhausted","incarnation":8,"restartCount":3,"maxRestartAttempts":3,
         "reason":"ENGINE_RESTART_EXHAUSTED:out_of_memory"}
        """;
    Files.writeString(record, exhausted);
    var bootstrap = bricked(tempDir);
    try (var monitor = newMonitor(bootstrap)) {
      List<String> seen = recordTransitions(bootstrap);
      monitor.tick();
      assertEquals(RECOVERING, bootstrap.workerCapability().pendingReason());
      for (int i = 1; i <= NO_WAIT.maxAttempts(); i++) monitor.tick();
      assertEquals(RECOVERY_EXHAUSTED, bootstrap.workerCapability().pendingReason());
      assertEquals(1, seen.stream().filter(t -> t.contains(RECOVERY_EXHAUSTED)).count());
      assertEquals(exhausted, Files.readString(record), "Java does not rewrite host-owned state");
    } finally {
      bootstrap.close();
    }
  }

  @Test
  @Timeout(180)
  @DisplayName("a stale legacy reason cannot suppress this Engine's actual boot failure")
  void staleLegacyReasonCannotSuppressCurrentBootFailure(@TempDir Path tempDir) {
    var bootstrap = newBootstrap(tempDir);
    try {
      // The literal deliberately represents retired input, not a new production reason code.
      bootstrap
          .indexComponent()
          .transition(ComponentState.FAILED, "worker.restart_exhausted", "predecessor verdict");
      // Exercise the funnel before STARTING can clear this now-unknown legacy code.
      bootstrap.transitionWorkerDown(LifecycleReasonCode.WORKER_SPAWN_FAILED, "current boot failed");
      assertEquals(SPAWN_FAILED, bootstrap.workerCapability().pendingReason());
      assertThrows(Exception.class, () -> bootstrap.startWithRetry(1, 0));
      assertEquals(SPAWN_FAILED, bootstrap.workerCapability().pendingReason());
      try (var monitor = newMonitor(bootstrap)) {
        monitor.tick();
        assertEquals(RECOVERING, bootstrap.workerCapability().pendingReason());
      }
    } finally {
      bootstrap.close();
    }
  }

  @Test
  @Timeout(180)
  @DisplayName("the manual path shares the authority: it is accepted, then vetoed once terminal")
  void manualRequestSharesTheSameAuthority(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor = newMonitor(bootstrap);

    assertEquals(
        WorkerRecoveryAuthority.Verdict.ACCEPTED,
        monitor.requestRecoveryNow(),
        "POST /api/worker/restart in the null-worker state must reach the recovery loop");

    // Drive the budget to its end through the periodic arm. The manual request runs on the monitor's
    // own executor and HOLDS the single attempt slot while it does (review F5), so a tick landing in
    // that window is a deliberate no-op — the loop is bounded by the terminal state, not by a count.
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline
        && !RECOVERY_EXHAUSTED.equals(bootstrap.workerCapability().pendingReason())) {
      monitor.tick();
      Thread.onSpinWait();
    }
    assertEquals(
        RECOVERY_EXHAUSTED,
        bootstrap.workerCapability().pendingReason(),
        "precondition: the arc is terminal");
    assertEquals(
        WorkerRecoveryAuthority.Verdict.EXHAUSTED,
        monitor.requestRecoveryNow(),
        "the manual path may not out-spend the declared budget");
  }

  @Test
  @Timeout(180)
  @DisplayName("F7: every worker-down site shares ONE suppression rule, in the funnel")
  void workerDownFunnelOwnsTheSuppressionRule(@TempDir Path tempDir) {
    // Review F7 found the guard applied at three of the four worker-down sites: the
    // health-budget-elapsed branch was missed, and it is reachable DURING a recovery arc (the
    // attempt's worker spawns and answers gRPC but never becomes healthy), flapping the arc out of
    // RECOVERING. Provoking a half-alive worker needs a real process — the live leg's territory —
    // so what is pinned here is the property that makes the site-by-site question moot: the rule
    // lives in transitionWorkerDown, which every site calls, including any added later.
    var bootstrap = bricked(tempDir);
    bootstrap
        .indexComponent()
        .transition(ComponentState.STARTING, RECOVERING, "attempt 1 of 2");
    List<String> seen = recordTransitions(bootstrap);

    // Outside an arc the funnel narrates, as it always has.
    bootstrap.transitionWorkerDown(
        LifecycleReasonCode.WORKER_SPAWN_FAILED, "Health check failed after 30000ms");
    assertEquals(
        SPAWN_FAILED,
        bootstrap.workerCapability().pendingReason(),
        "the funnel must still narrate when no arc owns the narration");

    // Inside one it does not — and every site inherits that, because they all come through here.
    bootstrap
        .indexComponent()
        .transition(ComponentState.STARTING, RECOVERING, "attempt 2");
    seen.clear();
    assertThrows(Exception.class, bootstrap::startForRecovery);

    assertTrue(
        seen.stream().noneMatch(t -> t.contains(SPAWN_FAILED)),
        "no worker-down site may narrate inside a recovery arc: " + seen);
    assertEquals(
        RECOVERING,
        bootstrap.workerCapability().pendingReason(),
        "the arc keeps the capability at RECOVERING for its whole duration");
  }

  @Test
  @Timeout(180)
  @DisplayName("F4: a closed monitor never spawns — the shutdown race cannot orphan a worker JVM")
  void closedMonitorNeverSpawns(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor = newMonitor(bootstrap);
    List<String> seen = recordTransitions(bootstrap);
    var occurrences = new CopyOnWriteArrayList<RecoveryOccurrence>();
    monitor.onRecoveryOccurrence(occurrences::add);

    monitor.close();
    // A tick already in the executor's hands when close() lands: without the closed flag this
    // spawns a Worker JVM after performOrderedShutdown has walked past the monitor, and nothing
    // owns the resulting process.
    monitor.tick();
    monitor.tick();

    assertTrue(seen.isEmpty(), "a closed monitor must not narrate or attempt anything: " + seen);
    assertTrue(
        occurrences.isEmpty(), "close before the attempt gate must emit no recovery occurrence");
    assertFalse(bootstrap.hasClient(), "…and certainly must not bind a new worker");
    assertEquals(
        WorkerRecoveryAuthority.Verdict.NOT_APPLICABLE,
        monitor.requestRecoveryNow(),
        "the manual path must not resurrect a closed monitor either");
  }

  @Test
  @Timeout(20)
  void recoveryFinishingAfterCloseCannotCallClosedHeadOwners(@TempDir Path tempDir)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var client = mock(KnowledgeClient.class);
    when(client.isHealthy(any())).thenReturn(true);
    var host = mock(WorkerHost.class);
    when(host.start(any(), any())).thenAnswer(ignored -> {
      entered.countDown();
      boolean released = false;
      while (!released) {
        try {
          released = release.await(15, TimeUnit.SECONDS);
          assertTrue(released, "test must release the blocked host");
        } catch (InterruptedException expected) {
          // Deliberately model a native start that outlives shutdown's interruption and await.
          interrupted.set(true);
        }
      }
      return client;
    });
    var bootstrap = newBootstrap(tempDir, host);
    bootstrap.transitionWorkerDown(
        LifecycleReasonCode.WORKER_SPAWN_FAILED, "initial composition failed");
    var monitor = newMonitor(bootstrap);
    var occurrences = new CopyOnWriteArrayList<RecoveryOccurrence>();
    var handedOver = new AtomicBoolean();
    monitor.onRecoveryOccurrence(occurrences::add);
    monitor.onRecoveryConnected(ignored -> handedOver.set(true));

    try {
      assertEquals(WorkerRecoveryAuthority.Verdict.ACCEPTED, monitor.requestRecoveryNow());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      monitor.close();
      assertTrue(interrupted.get(), "the host must survive shutdown interruption");
      assertTrue(monitor.recoveryAttemptRunningForTest(), "close returned with start still blocked");
    } finally {
      release.countDown();
      // Await the actual admitted task; assertions below cannot race its completion.
      monitor.close();
    }

    assertFalse(monitor.recoveryAttemptRunningForTest());
    assertTrue(bootstrap.hasClient(), "the late successful start must really have completed");
    assertEquals(List.of(RecoveryOccurrence.Kind.ATTEMPTED), kinds(occurrences));
    assertFalse(handedOver.get(), "a late client must not be bound into closed Head owners");
  }

  @Test
  @Timeout(180)
  @DisplayName("F5: a burst of manual requests cannot out-spend the declared budget")
  void manualBurstCannotOutspendTheBudget(@TempDir Path tempDir) throws Exception {
    var bootstrap = bricked(tempDir);
    var monitor = newMonitor(bootstrap);
    try {
      // Ten operator requests, each waiting only for the executor to be free — which is how a real
      // burst behaves once the ALREADY_RUNNING short-circuit stops queueing duplicates. An ACCEPTED
      // verdict is one spawn, so the count of them IS the spawn count. Pre-review nothing re-checked
      // the state on the executor thread, so every request spawned: ten worker processes against a
      // declared budget of two, and the arc never reached its terminal state at all.
      List<String> seen = recordTransitions(bootstrap);
      List<String> verdicts = new ArrayList<>();
      int accepted = 0;
      long deadline = System.currentTimeMillis() + 90_000;
      for (int i = 0; i < 10 && System.currentTimeMillis() < deadline; i++) {
        WorkerRecoveryAuthority.Verdict verdict = monitor.requestRecoveryNow();
        while (verdict == WorkerRecoveryAuthority.Verdict.ALREADY_RUNNING
            && System.currentTimeMillis() < deadline) {
          Thread.onSpinWait();
          verdict = monitor.requestRecoveryNow();
        }
        verdicts.add(String.valueOf(verdict));
        if (verdict == WorkerRecoveryAuthority.Verdict.ACCEPTED) {
          accepted++;
        }
      }
      while (System.currentTimeMillis() < deadline
          && !RECOVERY_EXHAUSTED.equals(bootstrap.workerCapability().pendingReason())) {
        Thread.onSpinWait();
      }

      assertEquals(
          RECOVERY_EXHAUSTED,
          bootstrap.workerCapability().pendingReason(),
          "ten requests against a budget of "
              + NO_WAIT.maxAttempts()
              + " must still converge on the ONE terminal state; verdicts="
              + verdicts
              + " transitions="
              + seen);
      assertTrue(
          accepted <= NO_WAIT.maxAttempts(),
          "an operator's requests may make an attempt sooner, never more often: accepted "
              + accepted
              + " of a budgeted "
              + NO_WAIT.maxAttempts());
      assertTrue(
          monitor.recoveryAttemptsMadeForTest() <= NO_WAIT.maxAttempts(),
          "the admitted-attempt counter may not exceed the declared budget; got "
              + monitor.recoveryAttemptsMadeForTest());
    } finally {
      monitor.close();
    }
  }

  @Test
  void boundButUnhealthyRecoveryCompletesOnlyAfterAHealthyObservation(@TempDir Path tempDir)
      throws Exception {
    var healthy = new AtomicBoolean();
    var client = mock(KnowledgeClient.class);
    when(client.isHealthy(any())).thenAnswer(ignored -> healthy.get());
    var host = mock(WorkerHost.class);
    when(host.start(any(), any())).thenReturn(client);
    var bootstrap = newBootstrap(tempDir, host);
    bootstrap.transitionWorkerDown(
        LifecycleReasonCode.WORKER_SPAWN_FAILED, "initial composition failed");
    var monitor = newMonitor(bootstrap);
    var occurrences = new CopyOnWriteArrayList<RecoveryOccurrence>();
    monitor.onRecoveryOccurrence(occurrences::add);

    monitor.tick();

    assertTrue(bootstrap.hasClient(), "the host bound even though its health check failed");
    assertEquals(List.of(RecoveryOccurrence.Kind.ATTEMPTED), kinds(occurrences));

    healthy.set(true);
    monitor.tick();

    assertEquals(
        List.of(RecoveryOccurrence.Kind.ATTEMPTED, RecoveryOccurrence.Kind.RECOVERED),
        kinds(occurrences));
    assertEquals(occurrences.get(0).context(), occurrences.get(1).context());
  }

  @Test
  void failedAttemptsEmitOneAttemptedAndRecoveryCarriesLatestAttempt(@TempDir Path tempDir)
      throws Exception {
    var client = mock(KnowledgeClient.class);
    when(client.isHealthy(any())).thenReturn(true);
    var host = mock(WorkerHost.class);
    when(host.start(any(), any()))
        .thenThrow(new IOException("first recovery failed"))
        .thenReturn(client);
    var bootstrap = newBootstrap(tempDir, host);
    bootstrap.transitionWorkerDown(
        LifecycleReasonCode.WORKER_SPAWN_FAILED, "initial composition failed");
    var monitor = newMonitor(bootstrap);
    var occurrences = new CopyOnWriteArrayList<RecoveryOccurrence>();
    monitor.onRecoveryOccurrence(occurrences::add);

    monitor.tick();
    monitor.tick();

    assertEquals(
        List.of(RecoveryOccurrence.Kind.ATTEMPTED, RecoveryOccurrence.Kind.RECOVERED),
        kinds(occurrences));
    assertEquals(1, occurrences.get(0).context().attempt());
    assertEquals(2, occurrences.get(1).context().attempt());
    assertEquals("boot", occurrences.get(0).context().faultKind());
    assertEquals("boot", occurrences.get(1).context().faultKind());
    assertEquals(0, occurrences.get(0).context().backoffMs());
    assertEquals(0, occurrences.get(1).context().backoffMs());
  }

  @Test
  void recoveryOccurrenceCallbackFailureDoesNotKillTheAttempt(@TempDir Path tempDir) {
    var bootstrap = newBootstrap(tempDir);
    bootstrap.transitionWorkerDown(
        LifecycleReasonCode.WORKER_SPAWN_FAILED, "initial composition failed");
    var monitor = newMonitor(bootstrap);
    monitor.onRecoveryOccurrence(ignored -> { throw new IllegalStateException("sink failed"); });

    assertDoesNotThrow(monitor::tick);
    assertEquals(1, monitor.recoveryAttemptsMadeForTest());
  }

  private static List<RecoveryOccurrence.Kind> kinds(List<RecoveryOccurrence> occurrences) {
    return occurrences.stream().map(RecoveryOccurrence::kind).toList();
  }

  // BootstrapPhysicalInitializationTest proves close/restart initialization through real
  // bootstrap calls and client effects; the retired generation counter is not an authority.
}
