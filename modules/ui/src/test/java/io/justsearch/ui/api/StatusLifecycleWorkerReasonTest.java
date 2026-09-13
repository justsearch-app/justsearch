/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV1;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.contract.wire.LifecycleState;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 837 §3.2 — the worker consumer forwards whichever cause the producer set, instead of
 * collapsing everything but one hardcoded code onto {@code worker.spawn.failed}.
 *
 * <p>Each case drives the capability exactly as its production call site does and asserts what
 * {@code /api/status} / {@code /api/health} publish as {@code components.worker.reason_code} — the
 * wire value, not an internal one.
 */
final class StatusLifecycleWorkerReasonTest {

  private static StatusLifecycleHandler handlerFor(WorkerCapability worker) {
    return new StatusLifecycleHandler(
        mock(io.justsearch.app.api.OnlineAiService.class),
        mock(io.justsearch.agent.api.AgentService.class),
        () -> null,
        null,
        null,
        null,
        Instant.now(),
        () -> "OK",
        null,
        null,
        null,
        worker,
        // A real capability, not a mock: computeLifecycleSnapshot switches on health(), and a mock's
        // null health would NPE before reaching the worker arm under test.
        new io.justsearch.app.services.lifecycle.InferenceCapability(false));
  }

  private static LifecycleSnapshotV1.Component workerComponent(WorkerCapability cap) {
    return handlerFor(cap).computeLifecycleSnapshot().components().worker();
  }

  @Test
  @DisplayName("a lost worker reports worker.lost, not worker.spawn.failed")
  void lostWorkerReportsLost() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.READY, null);
    cap.transition(
        CapabilityHealth.DEGRADED, LifecycleReasonCode.WORKER_LOST.code(), "Health check failed");

    LifecycleSnapshotV1.Component c = workerComponent(cap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_ERROR, c.state());
    assertEquals(LifecycleReasonCode.WORKER_LOST.code(), c.reason_code());
  }

  @Test
  @DisplayName("a corrupt index reports worker.index_corrupt (the cause with a remedy)")
  void corruptIndexReportsCorrupt() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(
        CapabilityHealth.DEGRADED,
        LifecycleReasonCode.WORKER_INDEX_CORRUPT.code(),
        "Set index.recovery.policy=BACKUP_REBUILD ...");

    LifecycleSnapshotV1.Component c = workerComponent(cap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_ERROR, c.state());
    assertEquals(LifecycleReasonCode.WORKER_INDEX_CORRUPT.code(), c.reason_code());
  }

  @Test
  @DisplayName("915 R1: a refused schema mismatch reaches /api/health as the cause, through the ladder")
  void schemaMismatchSurvivesTheLadderOntoTheWire() {
    WorkerCapability cap = new WorkerCapability();
    // The producer's own call, then the ladder's writes in the order live arm 2 observed them. The
    // failure this pins is not the mapping (KnowledgeServerWorkerDownCodeTest owns that) but the
    // SEQUENCE: /api/health reported worker.spawn_recovery_exhausted and the user was told to
    // restart the application, for an index that needed one policy change.
    cap.transition(
        CapabilityHealth.DEGRADED,
        LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH.code(),
        "Set index.schema_mismatch.policy=BLUE_GREEN_MIGRATE ...");
    cap.transition(
        CapabilityHealth.RECOVERING, LifecycleReasonCode.WORKER_RECOVERING.code(), "attempt 1");
    cap.transition(
        CapabilityHealth.DEGRADED,
        LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code(),
        "recovery attempts did not bring it up");

    LifecycleSnapshotV1.Component c = workerComponent(cap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_ERROR, c.state());
    assertEquals(LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH.code(), c.reason_code());
  }

  @Test
  @DisplayName("worker.spawn.failed still reports itself — it is now TRUE when it fires")
  void neverStartedStillReportsSpawnFailed() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(
        CapabilityHealth.DEGRADED,
        LifecycleReasonCode.WORKER_SPAWN_FAILED.code(),
        "Start failed: no such file");

    assertEquals(
        LifecycleReasonCode.WORKER_SPAWN_FAILED.code(), workerComponent(cap).reason_code());
  }

  @Test
  @DisplayName("825: the boot-recovery give-up reaches the wire as its own terminal code")
  void bootRecoveryExhaustedPassesThrough() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(
        CapabilityHealth.DEGRADED,
        LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code(),
        "4 recovery attempts did not bring it up");

    LifecycleSnapshotV1.Component c = workerComponent(cap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_ERROR, c.state());
    // Unlike worker.spawn.failed, this terminal verdict says no local retry remains.
    assertEquals(LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code(), c.reason_code());
  }

  @Test
  @DisplayName("an unrecognized reason falls back to the generic code, per capability state")
  void unknownReasonFallsBack() {
    WorkerCapability degraded = new WorkerCapability();
    degraded.transition(CapabilityHealth.DEGRADED, "some prose nobody swept");
    assertEquals(
        LifecycleReasonCode.WORKER_SPAWN_FAILED.code(), workerComponent(degraded).reason_code());

    WorkerCapability offline = new WorkerCapability();
    offline.transition(CapabilityHealth.OFFLINE, "some prose nobody swept");
    assertEquals(
        LifecycleReasonCode.WORKER_NOT_CONFIGURED.code(), workerComponent(offline).reason_code());
  }

  @Test
  @DisplayName("OFFLINE distinguishes an orderly shutdown from a worker that was never configured")
  void offlineDistinguishesShutdownFromNotConfigured() {
    WorkerCapability shutDown = new WorkerCapability();
    shutDown.transition(
        CapabilityHealth.OFFLINE, LifecycleReasonCode.WORKER_SHUT_DOWN.code(), "Worker shut down");
    LifecycleSnapshotV1.Component c = workerComponent(shutDown);
    assertEquals(LifecycleState.LIFECYCLE_STATE_DEGRADED, c.state());
    assertEquals(LifecycleReasonCode.WORKER_SHUT_DOWN.code(), c.reason_code());

    WorkerCapability notConfigured = new WorkerCapability();
    notConfigured.transition(
        CapabilityHealth.OFFLINE,
        LifecycleReasonCode.WORKER_NOT_CONFIGURED.code(),
        "Worker not configured");
    assertEquals(
        LifecycleReasonCode.WORKER_NOT_CONFIGURED.code(),
        workerComponent(notConfigured).reason_code());
  }

  @Test
  @DisplayName("RECOVERING keeps its calmer state and code (a routine self-heal is not an error)")
  void recoveringKeepsItsCalmMapping() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(
        CapabilityHealth.RECOVERING, LifecycleReasonCode.WORKER_RECOVERING.code(), "attempt 1");

    LifecycleSnapshotV1.Component c = workerComponent(cap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_DEGRADED, c.state());
    assertEquals(LifecycleReasonCode.WORKER_RECOVERING.code(), c.reason_code());
  }

  @Test
  @DisplayName("READY publishes no reason at all")
  void readyPublishesNoReason() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.READY, null);

    LifecycleSnapshotV1.Component c = workerComponent(cap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_READY, c.state());
    assertNull(c.reason_code());
  }
}
