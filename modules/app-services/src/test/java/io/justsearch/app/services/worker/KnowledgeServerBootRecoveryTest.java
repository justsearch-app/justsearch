/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot-recovery ARC, driven through a real {@link KnowledgeServerBootstrap} and a real
 * {@link KnowledgeServerHealthMonitor} (tempdoc 825 Â§D4, middle rung of the ladder).
 *
 * <p>Fixture shape borrowed from {@code KnowledgeServerBootstrapRestartabilityTest}: every path
 * points at an empty temp dir, so the spawned worker JVM cannot find its main class and dies before
 * publishing a port. Every attempt therefore FAILS â€” which is precisely what makes the budget, the
 * narration and the terminal state observable here. Convergence (the attempt that succeeds) needs a
 * real worker and lives one rung up, in the isolated-backend integration leg with the countdown
 * fault injector.
 *
 * <p>{@code tick()} is called directly rather than via {@code start()}: the schedule is the
 * executor's business, the decision is the arm's, and driving it explicitly keeps the test bounded
 * and deterministic instead of sleeping past a poll interval.
 */
@DisplayName("boot recovery: the arc, over a real bootstrap")
final class KnowledgeServerBootRecoveryTest {

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
  private static KnowledgeServerBootstrap bricked(Path tempDir) {
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), configFor(tempDir));
    assertThrows(Exception.class, () -> bootstrap.startWithRetry(1, 0));
    assertFalse(bootstrap.hasClient(), "the fixture must leave no client bound");
    assertEquals(
        SPAWN_FAILED,
        bootstrap.workerCapability().pendingReason(),
        "precondition: the boot failure pins worker.spawn.failed (the 821 Â§O.4 state)");
    return bootstrap;
  }

  private static List<String> recordTransitions(KnowledgeServerBootstrap bootstrap) {
    WorkerCapability cap = bootstrap.workerCapability();
    List<String> seen = new ArrayList<>();
    cap.addListener((prev, next) -> seen.add(next + "/" + cap.pendingReason()));
    return seen;
  }

  @Test
  @Timeout(180)
  @DisplayName("the pinned state is re-attempted, and the recovery narration is NOT swallowed")
  void firstTickReAttemptsAndNarratesRecovering(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor =
        new KnowledgeServerHealthMonitor(new io.justsearch.core.execution.TestEngineExecutors(), bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);

    monitor.tick();

    // The ReasonRetention trap (825 Â§D2 mechanism 4): worker.recovering is TRANSIENT and the held
    // worker.spawn.failed is a FAULT, so without the recovery-supersedes arm this write is silently
    // dropped and pendingReason â€” published raw on the runtime manifest and the 503 body â€” keeps
    // saying "failed to start" while the Head is actively re-attempting.
    assertEquals(
        RECOVERING,
        bootstrap.workerCapability().pendingReason(),
        "the in-flight recovery must reach the reason slot, not be latched out by the pin");
    assertEquals(CapabilityHealth.RECOVERING, bootstrap.workerCapability().health());
  }

  @Test
  @Timeout(180)
  @DisplayName("a failing arc gives up exactly once, on the terminal code, after the budget")
  void arcGivesUpOnceAfterTheBudget(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor =
        new KnowledgeServerHealthMonitor(new io.justsearch.core.execution.TestEngineExecutors(), bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);
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

    // Further ticks are silent â€” no re-narration, no further spawns.
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
    var monitor =
        new KnowledgeServerHealthMonitor(new io.justsearch.core.execution.TestEngineExecutors(), bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);
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
    // One RECOVERING (the arc, entered once and held) + one terminal DEGRADED. Anything more is the
    // flap the acceptance criterion forbids: with N attempts, an unsuppressed arc would emit
    // RECOVERING/PENDING/DEGRADED/OFFLINE per cycle.
    assertEquals(
        2,
        seen.size(),
        "an arc of " + NO_WAIT.maxAttempts() + " attempts must narrate 2 transitions, got: " + seen);
    assertTrue(seen.get(0).startsWith("RECOVERING"), "first: " + seen);
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
    try (var monitor = new KnowledgeServerHealthMonitor(new io.justsearch.core.execution.TestEngineExecutors(),
        bootstrap, 10_000, System::currentTimeMillis, NO_WAIT)) {
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
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), configFor(tempDir));
    try {
      // The literal deliberately represents retired input, not a new production reason code.
      bootstrap.workerCapability().transition(
          CapabilityHealth.DEGRADED, "worker.restart_exhausted", "predecessor verdict");
      // Exercise the funnel before STARTING can clear this now-unknown legacy code.
      bootstrap.transitionWorkerDown(LifecycleReasonCode.WORKER_SPAWN_FAILED, "current boot failed");
      assertEquals(SPAWN_FAILED, bootstrap.workerCapability().pendingReason());
      assertThrows(Exception.class, () -> bootstrap.startWithRetry(1, 0));
      assertEquals(SPAWN_FAILED, bootstrap.workerCapability().pendingReason());
      try (var monitor = new KnowledgeServerHealthMonitor(new io.justsearch.core.execution.TestEngineExecutors(),
          bootstrap, 10_000, System::currentTimeMillis, NO_WAIT)) {
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
    var monitor =
        new KnowledgeServerHealthMonitor(new io.justsearch.core.execution.TestEngineExecutors(), bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);

    assertEquals(
        WorkerRecoveryAuthority.Verdict.ACCEPTED,
        monitor.requestRecoveryNow(),
        "POST /api/worker/restart in the null-worker state must reach the recovery loop");

    // Drive the budget to its end through the periodic arm. The manual request runs on the monitor's
    // own executor and HOLDS the single attempt slot while it does (review F5), so a tick landing in
    // that window is a deliberate no-op â€” the loop is bounded by the terminal state, not by a count.
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
    // RECOVERING. Provoking a half-alive worker needs a real process â€” the live leg's territory â€”
    // so what is pinned here is the property that makes the site-by-site question moot: the rule
    // lives in transitionWorkerDown, which every site calls, including any added later.
    var bootstrap = bricked(tempDir);
    bootstrap
        .workerCapability()
        .transition(CapabilityHealth.RECOVERING, RECOVERING, "attempt 1 of 2");
    List<String> seen = recordTransitions(bootstrap);

    // Outside an arc the funnel narrates, as it always has.
    bootstrap.transitionWorkerDown(
        LifecycleReasonCode.WORKER_SPAWN_FAILED, "Health check failed after 30000ms");
    assertEquals(
        SPAWN_FAILED,
        bootstrap.workerCapability().pendingReason(),
        "the funnel must still narrate when no arc owns the narration");

    // Inside one it does not â€” and every site inherits that, because they all come through here.
    bootstrap.workerCapability().transition(CapabilityHealth.RECOVERING, RECOVERING, "attempt 2");
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
  @DisplayName("F4: a closed monitor never spawns â€” the shutdown race cannot orphan a worker JVM")
  void closedMonitorNeverSpawns(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor =
        new KnowledgeServerHealthMonitor(new io.justsearch.core.execution.TestEngineExecutors(), bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);
    List<String> seen = recordTransitions(bootstrap);

    monitor.close();
    // A tick already in the executor's hands when close() lands: without the closed flag this
    // spawns a Worker JVM after performOrderedShutdown has walked past the monitor, and nothing
    // owns the resulting process.
    monitor.tick();
    monitor.tick();

    assertTrue(seen.isEmpty(), "a closed monitor must not narrate or attempt anything: " + seen);
    assertFalse(bootstrap.hasClient(), "â€¦and certainly must not bind a new worker");
    assertEquals(
        WorkerRecoveryAuthority.Verdict.NOT_APPLICABLE,
        monitor.requestRecoveryNow(),
        "the manual path must not resurrect a closed monitor either");
  }

  @Test
  @Timeout(180)
  @DisplayName("F5: a burst of manual requests cannot out-spend the declared budget")
  void manualBurstCannotOutspendTheBudget(@TempDir Path tempDir) throws Exception {
    var bootstrap = bricked(tempDir);
    var monitor =
        new KnowledgeServerHealthMonitor(new io.justsearch.core.execution.TestEngineExecutors(), bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);
    try {
      // Ten operator requests, each waiting only for the executor to be free â€” which is how a real
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
      RecoveryContext last = bootstrap.workerCapability().lastRecoveryContext();
      assertTrue(
          last != null && last.attempt() <= NO_WAIT.maxAttempts(),
          "no attempt may be numbered beyond the budget; highest seen: "
              + (last == null ? "none" : last.attempt()));
    } finally {
      monitor.close();
    }
  }

  @Test
  @Timeout(180)
  @DisplayName("close() resets initGeneration, so a recovered worker still gets its help files")
  void closeResetsInitGeneration(@TempDir Path tempDir) throws Exception {
    // #439 review finding E / charter item 4: initGeneration outlived the connection it described,
    // so the first successful start AFTER any close() took the generation>=1 "recovery" branch of
    // completeReadyInitialization and skipped tryIngestHelpFiles for the rest of the process. Latent
    // before 825 (nothing re-started a closed bootstrap); LIVE the moment a recovery loop exists.
    //
    // Read reflectively because the counter has no consumer that would justify a public accessor,
    // and asserting it through completeReadyInitialization would need a live gRPC client â€” the exact
    // substrate this rung of the ladder is defined to exclude.
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), configFor(tempDir));
    var field = KnowledgeServerBootstrap.class.getDeclaredField("initGeneration");
    field.setAccessible(true);
    var generation = (java.util.concurrent.atomic.AtomicLong) field.get(bootstrap);
    generation.set(3); // as if three connections had completed initialization

    bootstrap.closeForUpgrade();

    assertEquals(
        0L,
        generation.get(),
        "close() drops the client, spawner and signal bus; the generation describes that same"
            + " connection and must go with them, or the next start() skips first-connect init");
    assertFalse(bootstrap.hasClient());
  }
}
