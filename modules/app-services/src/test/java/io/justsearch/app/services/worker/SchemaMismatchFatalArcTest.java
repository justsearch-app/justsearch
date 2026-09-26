/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentState;
import io.justsearch.ipc.WorkerFatalReasonMarker;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 R1 — the WHOLE arc a FAIL_CLOSED schema-mismatch refusal travels, from the marker the
 * dying worker writes to what Head readiness finally says.
 *
 * <p>Live validation found the worker half correct and the Head half silent: {@code
 * worker-fatal-reason} held {@code index_schema_mismatch} on disk, and {@code /api/health} still
 * reported {@code NOT_READY worker.spawn.failed} → {@code DEGRADED worker.recovering} → terminal
 * {@code NOT_READY worker.spawn_recovery_exhausted}, with {@code knowledgeServerStartError} reading
 * "Worker process crashed (exit code 1) before writing port to signal file" for an index the worker
 * had deliberately left byte-identical.
 *
 * <p>The mechanism was one ordering: {@code workerDownCode} CONSUMES the one-shot marker before the
 * two narration guards decide whether the verdict is applied, so every suppressed start attempt
 * destroyed the evidence and the one call allowed to narrate found nothing. {@link
 * KnowledgeServerWorkerDownCodeTest} pins the mapping; this pins the arc, which is what actually
 * broke. The fixture uses the explicit unavailable {@link WorkerHost}, so failed composition is
 * deterministic and does not manufacture a second lifecycle authority.
 */
@DisplayName("915 R1: the schema-mismatch refusal survives the whole supervision ladder")
final class SchemaMismatchFatalArcTest {

  private final List<KnowledgeServerBootstrapTestFixture> fixtures = new ArrayList<>();
  private final List<AutoCloseable> recoveryResources = new ArrayList<>();
  private final Map<KnowledgeServerBootstrap, KnowledgeServerBootstrapTestFixture> fixtureByBootstrap =
      new IdentityHashMap<>();

  private static KnowledgeServerConfig configFor(Path dir) {
    return new KnowledgeServerConfig(
        false, dir, dir, dir,
        5_000L, 1_000L, 3, 1_000L, 1_000L, 300_000L, 100, 0L, 0);
  }

  private static final BootRecoveryPolicy NO_WAIT = new BootRecoveryPolicy(2, 0, 0);

  private static final String MISMATCH = LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH.code();
  private static final String SPAWN_FAILED = LifecycleReasonCode.WORKER_SPAWN_FAILED.code();
  private static final String RECOVERY_EXHAUSTED =
      LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code();

  /**
   * The refusal as the Head actually meets it: the worker wrote the marker and exited, and {@code
   * startWithRetry(3, ...)} runs its attempts with per-attempt narration SUPPRESSED — the arc where
   * the marker is consumed by a call that is not allowed to speak.
   */
  private KnowledgeServerBootstrap newBootstrap(Path tempDir) {
    var fixture = KnowledgeServerBootstrapTestFixture.create(configFor(tempDir));
    fixtures.add(fixture);
    fixtureByBootstrap.put(fixture.bootstrap(), fixture);
    return fixture.bootstrap();
  }

  private KnowledgeServerBootstrap refusedBoot(Path tempDir) {
    WorkerFatalReasonMarker.write(tempDir, WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH);
    var bootstrap = newBootstrap(tempDir);
    assertThrows(Exception.class, () -> bootstrap.startWithRetry(3, 0));
    assertFalse(bootstrap.hasClient(), "the fixture must leave no client bound");
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
  @DisplayName("the boot narrates the refusal, not the spawn symptom it happened to observe")
  void bootNarratesTheRefusalAcrossSuppressedAttempts(@TempDir Path tempDir) {
    var bootstrap = refusedBoot(tempDir);

    var cap = bootstrap.workerCapability();
    assertEquals(
        MISMATCH,
        cap.pendingReason(),
        "the attempt that consumed the marker was suppressed; without the latch the ONE narrating"
            + " call reports worker.spawn.failed and the cause is unrecoverable");
    assertTrue(
        cap.pendingDetail().contains("index.schema_mismatch.policy"),
        "and it carries the policy remedy, which the crash message never could: "
            + cap.pendingDetail());
    assertEquals(MISMATCH, bootstrap.indexFatalCode().code());
    assertFalse(
        WorkerFatalReasonMarker.pathFor(tempDir).toFile().exists(),
        "the marker is still one-shot — the latch is what makes the observation repeatable");
  }

  @Test
  @Timeout(180)
  @DisplayName("the ladder declines to respawn a worker that refused deterministically")
  void theLadderShortCircuitsInsteadOfSpendingTheBudget(@TempDir Path tempDir) {
    var bootstrap = refusedBoot(tempDir);
    var monitor = newMonitor(bootstrap);
    List<String> seen = recordTransitions(bootstrap);

    // More ticks than the budget: a ladder that ran would have narrated worker.recovering per
    // attempt and then its own terminal code, which is exactly the live sequence.
    for (int i = 0; i < NO_WAIT.maxAttempts() + 2; i++) {
      monitor.tick();
    }

    assertFalse(
        seen.stream().anyMatch(t -> t.contains(LifecycleReasonCode.WORKER_RECOVERING.code())),
        "no attempt may be spent: the refusal is a function of the index directory, and a respawn"
            + " reads the same bytes. Saw: " + seen);
    assertFalse(
        seen.stream().anyMatch(t -> t.contains(RECOVERY_EXHAUSTED)),
        "and the terminal state must be the CAUSE, not this arm's generic give-up: " + seen);
    assertEquals(MISMATCH, bootstrap.workerCapability().pendingReason());
    assertEquals(CapabilityHealth.DEGRADED, bootstrap.workerCapability().health());
  }

  @Test
  @Timeout(180)
  @DisplayName("a ladder that DOES run cannot overwrite the refusal with its own terminal code")
  void theStickyVerdictOutlivesRecoveringAndExhausted(@TempDir Path tempDir) {
    var bootstrap = refusedBoot(tempDir);
    var cap = bootstrap.workerCapability();

    // The ladder's own writes, verbatim, in the order the validator observed them. Even with the
    // short-circuit above these must not be able to erase the cause: the veto reads the bootstrap
    // latch, and a future caller that clears the latch (an operator hatch, a handover) would put
    // this sequence back in play.
    bootstrap
        .indexComponent()
        .transition(
            ComponentState.STARTING,
            LifecycleReasonCode.WORKER_RECOVERING.code(),
            "attempt 1");
    bootstrap
        .indexComponent()
        .transition(
            ComponentState.FAILED, RECOVERY_EXHAUSTED, "2 attempts did not bring it up");

    assertEquals(
        MISMATCH,
        cap.pendingReason(),
        "STICKY means the ladder's narration changes the HEALTH and leaves the cause alone");
    assertTrue(cap.pendingDetail().contains("index.schema_mismatch.policy"));
  }

  @Test
  @Timeout(180)
  @DisplayName("the specific refusal supersedes a generic local-recovery failure")
  void theRefusalOutranksGenericRecoveryFailure(@TempDir Path tempDir) {
    WorkerFatalReasonMarker.write(tempDir, WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH);
    var bootstrap = newBootstrap(tempDir);
    // A generic failure cannot hide the more specific cause discovered on the next attempt.
    bootstrap
        .indexComponent()
        .transition(
            ComponentState.FAILED,
            LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code(),
            "local recovery budget exhausted");
    assertThrows(Exception.class, () -> bootstrap.startWithRetry(3, 0));

    assertEquals(
        MISMATCH,
        bootstrap.workerCapability().pendingReason(),
        "the fatal index cause is more specific than the previous generic failure");
  }

  @Test
  @Timeout(180)
  @DisplayName("a refusal nobody was allowed to narrate is still narrated by the ladder's give-up")
  void theGiveUpNarratesACauseTheBootArcSwallowed(@TempDir Path tempDir) throws Exception {
    WorkerFatalReasonMarker.write(tempDir, WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH);
    var bootstrap = newBootstrap(tempDir);
    // startForRecovery suppresses EVERY transition for the whole arc, so this is the shape where the
    // cause is known to the Head and has never been said out loud. Nothing else will say it.
    assertThrows(Exception.class, bootstrap::startForRecovery);
    assertEquals(
        MISMATCH, bootstrap.indexFatalCode().code(), "precondition: latched but unnarrated");
    assertFalse(
        MISMATCH.equals(bootstrap.workerCapability().pendingReason()),
        "precondition: the suppressed arc narrated nothing, so the wire does not have it yet");

    var monitor = newMonitor(bootstrap);
    monitor.tick();

    assertEquals(MISMATCH, bootstrap.workerCapability().pendingReason());
    assertTrue(
        bootstrap.workerCapability().pendingDetail().contains("index.schema_mismatch.policy"));
  }

  @Test
  @Timeout(180)
  @DisplayName("an operator retry that re-refuses re-latches the cause, not a stuck 'recovering'")
  void anOperatorRetryThatReRefusesReLatchesTheVerdict(@TempDir Path tempDir) throws Exception {
    var bootstrap = refusedBoot(tempDir);
    var monitor = newMonitor(bootstrap);
    monitor.tick();
    assertEquals(MISMATCH, bootstrap.workerCapability().pendingReason(), "precondition: latched");

    // The operator fixes nothing and asks anyway — the case R2 observed live. The refusal repeats,
    // so the worker rewrites the marker and the arm re-latches; what must NOT survive is
    // worker.recovering, which readinessNotice.ts renders as "recovering" for a condition that
    // never recovers on its own. The request withholds the VETO, never the VERDICT.
    WorkerFatalReasonMarker.write(tempDir, WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH);
    assertEquals(
        WorkerRecoveryAuthority.Verdict.ACCEPTED,
        monitor.requestRecoveryNow(),
        "the hatch stays open: an index-fatal give-up is the one terminal state an operator reopens");
    awaitAttemptSettled(monitor);

    assertEquals(1, monitor.recoveryAttemptsMadeForTest(), "exactly one attempt was spent");
    assertEquals(
        MISMATCH,
        bootstrap.workerCapability().pendingReason(),
        "terminal readiness is the cause again, not a transient the user waits out forever");
    assertTrue(
        bootstrap.workerCapability().pendingDetail().contains("index.schema_mismatch.policy"));
    assertEquals(CapabilityHealth.DEGRADED, bootstrap.workerCapability().health());
  }

  @Test
  @Timeout(180)
  @DisplayName("each operator reopen after an index-fatal terminal starts a new recovery episode")
  void operatorReopenEmitsANewAttemptedOccurrence(@TempDir Path tempDir) throws Exception {
    var bootstrap = refusedBoot(tempDir);
    var monitor = newMonitor(bootstrap);
    var occurrences = new CopyOnWriteArrayList<RecoveryOccurrence>();
    monitor.onRecoveryOccurrence(occurrences::add);
    monitor.tick(); // INDEX_FATAL terminal; no physical attempt was admitted.

    assertEquals(WorkerRecoveryAuthority.Verdict.ACCEPTED, monitor.requestRecoveryNow());
    awaitOccurrences(occurrences, 1);
    assertEquals(RecoveryOccurrence.Kind.ATTEMPTED, occurrences.get(0).kind());
    assertEquals(1, occurrences.get(0).context().attempt());
    awaitAttemptSettled(monitor);

    assertEquals(WorkerRecoveryAuthority.Verdict.ACCEPTED, monitor.requestRecoveryNow());
    awaitOccurrences(occurrences, 2);
    assertEquals(RecoveryOccurrence.Kind.ATTEMPTED, occurrences.get(1).kind());
    assertEquals(2, occurrences.get(1).context().attempt());
  }

  private static void awaitOccurrences(
      List<RecoveryOccurrence> occurrences, int expectedCount) throws Exception {
    long deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      if (occurrences.size() >= expectedCount) return;
      Thread.sleep(10);
    }
    throw new AssertionError(
        "expected " + expectedCount + " recovery occurrence(s), got " + occurrences);
  }

  /** Bounded poll: requestRecoveryNow schedules the attempt on the arm's own executor. */
  private static void awaitAttemptSettled(KnowledgeServerHealthMonitor monitor) throws Exception {
    long deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      if (!monitor.recoveryAttemptRunningForTest() && monitor.recoveryAttemptsMadeForTest() > 0) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("the operator-requested attempt never ran");
  }

  @Test
  @Timeout(180)
  @DisplayName("projected READY cannot erase a physical fatal-index verdict")
  void projectedReadyCannotClearThePhysicalLatch(@TempDir Path tempDir) {
    var bootstrap = refusedBoot(tempDir);
    assertEquals(MISMATCH, bootstrap.indexFatalCode().code());
    fixtureByBootstrap.get(bootstrap).publishSamplerReady();
    assertEquals(MISMATCH, bootstrap.indexFatalCode().code(),
        "only a direct healthy client observation can establish that the index opened");
  }

  @Test
  @Timeout(180)
  @DisplayName("a boot with no marker is unaffected — the generic code still means what it says")
  void aPlainSpawnFailureStillNarratesSpawnFailed(@TempDir Path tempDir) {
    var bootstrap = newBootstrap(tempDir);
    assertThrows(Exception.class, () -> bootstrap.startWithRetry(3, 0));

    assertEquals(SPAWN_FAILED, bootstrap.workerCapability().pendingReason());
    assertNull(bootstrap.indexFatalCode(), "nothing to latch, nothing latched");
  }
}
