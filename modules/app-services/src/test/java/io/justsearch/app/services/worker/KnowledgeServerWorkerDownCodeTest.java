/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.ipc.WorkerFatalReasonMarker;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 837 §3.1 — the worker-down verdict is 5 call sites over 2 axes, not 5 independent
 * strings: axis 1 (never-started vs lost) is decided by the CALL SITE, which already knows because
 * it either runs before first-ready or is guarded on {@code current == READY}; axis 2 (normal vs
 * corrupt index) is decided inside the shared helper from the dying worker's fatal-reason marker.
 *
 * <p>Before this, every one of those sites wrote free prose that the {@code /api/status} consumer
 * discarded and replaced with {@code worker.spawn.failed} — so a worker that had been serving for an
 * hour and died was reported as having "failed to start", and the corrupt-index remedy the Head had
 * computed was thrown away.
 */
final class KnowledgeServerWorkerDownCodeTest {

  private static KnowledgeServerConfig configFor(Path dir) {
    return new KnowledgeServerConfig(
        false, dir, dir, dir,
        5_000L, 15_000L, 3, 5_000L, 5_000L, 300_000L, 100, 0L, 0);
  }

  @Test
  @DisplayName("never-started sites pass worker.spawn.failed; the prose becomes the detail")
  void neverStartedYieldsSpawnFailed(@TempDir Path tempDir) {
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), configFor(tempDir));

    bootstrap.transitionWorkerDown(
        LifecycleReasonCode.WORKER_SPAWN_FAILED, "Health check failed after 4200ms");

    var cap = bootstrap.workerCapability();
    assertEquals(CapabilityHealth.DEGRADED, cap.health());
    assertEquals(LifecycleReasonCode.WORKER_SPAWN_FAILED.code(), cap.pendingReason());
    assertEquals(
        "Health check failed after 4200ms",
        cap.pendingDetail(),
        "the sentence still exists — it moved out of the code slot, it was not deleted");
  }

  @Test
  @DisplayName("the was-READY sites pass worker.lost — the distinction the user could not see")
  void lostYieldsWorkerLost(@TempDir Path tempDir) {
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), configFor(tempDir));

    bootstrap.transitionWorkerDown(LifecycleReasonCode.WORKER_LOST, "Health check failed");

    var cap = bootstrap.workerCapability();
    assertEquals(LifecycleReasonCode.WORKER_LOST.code(), cap.pendingReason());
  }

  @Test
  @DisplayName("a refused schema mismatch overrides the generic code too, with its own remedy")
  void schemaMismatchMarkerOverridesTheGenericCode(@TempDir Path tempDir) {
    // Tempdoc 915 (live validation). FAIL_CLOSED did its job - the index was left byte-identical -
    // and the user was told "Worker process crashed (exit code 1)", because the corruption axis was
    // the only fatal reason a dying worker could name. A deliberate refusal is not a crash, and its
    // remedy is a policy that permits a rebuild, not a corruption repair.
    WorkerFatalReasonMarker.write(tempDir, WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH);
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), configFor(tempDir));

    bootstrap.transitionWorkerDown(
        LifecycleReasonCode.WORKER_SPAWN_FAILED,
        "Worker process crashed (exit code 1) before writing port to signal file");

    var cap = bootstrap.workerCapability();
    assertEquals(LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH.code(), cap.pendingReason());
    assertTrue(
        cap.pendingDetail().contains("index.schema_mismatch.policy"),
        "and names the setting that produced the refusal, which the crash message never could");
    assertFalse(
        Files.exists(WorkerFatalReasonMarker.pathFor(tempDir)),
        "readAndClear consumed it, same as its corruption sibling");
  }

  @Test
  @DisplayName("the corruption axis overrides either generic code, and carries the remedy as detail")
  void corruptMarkerOverridesTheGenericCode(@TempDir Path tempDir) {
    WorkerFatalReasonMarker.write(tempDir, WorkerFatalReasonMarker.INDEX_CORRUPT);
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), configFor(tempDir));

    bootstrap.transitionWorkerDown(LifecycleReasonCode.WORKER_LOST, "Health check failed");

    var cap = bootstrap.workerCapability();
    assertEquals(LifecycleReasonCode.WORKER_INDEX_CORRUPT.code(), cap.pendingReason());
    assertTrue(
        cap.pendingDetail().contains("index.recovery.policy=BACKUP_REBUILD"),
        "the concrete remedy the Head already knew now reaches the user instead of being discarded");
    assertFalse(
        Files.exists(WorkerFatalReasonMarker.pathFor(tempDir)),
        "readAndClear consumed the marker — which is exactly why the capability must latch the code");
  }

  @Test
  @DisplayName("an unrelated fatal reason is NOT read as corruption")
  void unrelatedMarkerKeepsTheGenericCode(@TempDir Path tempDir) {
    WorkerFatalReasonMarker.write(tempDir, "out_of_memory");
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), configFor(tempDir));

    bootstrap.transitionWorkerDown(LifecycleReasonCode.WORKER_LOST, "Health check failed");

    assertEquals(
        LifecycleReasonCode.WORKER_LOST.code(),
        bootstrap.workerCapability().pendingReason(),
        "628's fail-loud-with-the-RIGHT-reason thesis: never offer a rebuild for a non-corruption death");
  }

  @Test
  @DisplayName("end-to-end latch: the corrupt cause survives the restart-then-give-up sequence")
  void corruptCauseSurvivesTheSupervisionSequence(@TempDir Path tempDir) {
    WorkerFatalReasonMarker.write(tempDir, WorkerFatalReasonMarker.INDEX_CORRUPT);
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), configFor(tempDir));
    var cap = bootstrap.workerCapability();

    bootstrap.transitionWorkerDown(LifecycleReasonCode.WORKER_LOST, "Health check failed");
    // The supervisor restarts; the index is still corrupt so the restart fails, and the marker is
    // already gone — a second read cannot recover the cause.
    cap.transition(CapabilityHealth.RECOVERING, LifecycleReasonCode.WORKER_RECOVERING.code(), "a1");
    bootstrap.transitionWorkerDown(LifecycleReasonCode.WORKER_SPAWN_FAILED, "Start failed");

    assertEquals(
        LifecycleReasonCode.WORKER_INDEX_CORRUPT.code(),
        cap.pendingReason(),
        "without the latch this reports worker.spawn.failed and the real cause is unrecoverable");
  }
}
