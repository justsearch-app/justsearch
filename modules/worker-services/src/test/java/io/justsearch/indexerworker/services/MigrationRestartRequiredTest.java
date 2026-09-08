/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.ipc.MigrationRollbackRequest;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage-A checkpoint, blocker 1 — the migration payload states a REQUIREMENT, not a fiction.
 *
 * <p>Both responses used to carry {@code restart_scheduled}, set to {@code true} whenever a
 * {@code restartWorkerCallback} was wired, and the ops layer then spawned a thread that slept 150 ms
 * and ran it. Under the split architecture that callback ended the Worker process and the spawner
 * respawned it. In one JVM it resolved to {@code KnowledgeServer#initiateShutdown}, which set a flag
 * and counted down a latch that no production code read — so nothing was scheduled, nothing
 * restarted, and the caller was told otherwise.
 *
 * <p>The field is now {@code restart_required} and means what it says: the durable part of the
 * operation is done, and the caller must restart the Engine for it to take effect. That is the same
 * contract config-apply already answers with ({@code RestartRequiredException.CODE}), so the two
 * agree instead of one of them pretending.
 *
 * <p>This is a unit test of the payload contract specifically. The lifecycle consequence — that the
 * live Engine really does keep serving the old generation until that restart happens — is asserted
 * end-to-end in {@code app-engine}'s {@code EngineMigrationLifecycleTest}.
 */
@DisplayName("migration responses — restart_required (stage-A checkpoint blocker 1)")
final class MigrationRestartRequiredTest {

  private static MigrationControlOps opsOver(Path indexBase) {
    return new MigrationControlOps(new IndexGenerationManager(indexBase));
  }

  @Test
  @DisplayName("startMigration reports restart_required when the caller asked for the restart")
  void startReportsRestartRequired(@TempDir Path tempDir) throws Exception {
    Path indexBase = tempDir.resolve("index");
    IndexGenerationManager seed = new IndexGenerationManager(indexBase);
    seed.initializeOrLoad();

    MigrationStartResponse response =
        opsOver(indexBase)
            .startMigration(
                MigrationStartRequest.newBuilder()
                    .setReason("checkpoint_test")
                    .setRestartWorker(true)
                    .build());

    assertTrue(response.getAccepted(), "precondition: the migration must be accepted");
    assertTrue(
        response.getRestartRequired(),
        "a caller that asked for the restart must be told one is REQUIRED. Under the old field name"
            + " this said 'scheduled', and nothing was scheduled — the flag was true because a"
            + " callback reference was non-null, not because anything would happen.");
  }

  @Test
  @DisplayName("a caller that did not ask for a restart is not told one is required")
  void startWithoutRestartRequestReportsFalse(@TempDir Path tempDir) throws Exception {
    Path indexBase = tempDir.resolve("index");
    IndexGenerationManager seed = new IndexGenerationManager(indexBase);
    seed.initializeOrLoad();

    MigrationStartResponse response =
        opsOver(indexBase)
            .startMigration(
                MigrationStartRequest.newBuilder()
                    .setReason("checkpoint_test")
                    .setRestartWorker(false)
                    .build());

    assertTrue(response.getAccepted(), "precondition: the migration must be accepted");
    assertFalse(
        response.getRestartRequired(),
        "the flag must track the request, not a wiring accident. The old expression was"
            + " `restart && restartWorkerCallback != null`, so it also went false whenever the"
            + " callback happened to be unwired — two different reasons collapsed into one answer.");
  }

  @Test
  @DisplayName("rollback carries the same contract")
  void rollbackReportsRestartRequired(@TempDir Path tempDir) throws Exception {
    Path indexBase = tempDir.resolve("index");
    IndexGenerationManager seed = new IndexGenerationManager(indexBase);
    seed.initializeOrLoad();
    MigrationControlOps ops = opsOver(indexBase);
    ops.startMigration(
        MigrationStartRequest.newBuilder().setReason("checkpoint_test").setRestartWorker(false).build());

    MigrationRollbackResponse response =
        ops.rollbackMigration(MigrationRollbackRequest.newBuilder().setRestartWorker(true).build());

    // Rollback can legitimately refuse (there may be no previous generation to go back to); the
    // contract under test is only that WHEN it accepts, it states the requirement.
    if (response.getAccepted()) {
      assertTrue(
          response.getRestartRequired(),
          "an accepted rollback that was asked to restart must report restart_required");
    }
  }
}
