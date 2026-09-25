/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.ipc.MigrationCutoverRequest;
import io.justsearch.ipc.MigrationRollbackRequest;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import java.nio.file.Files;
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
  private static final String OPERATION_KEY = "01994180-0000-7000-8000-000000000121";
  private static final String TARGET_FINGERPRINT = "a".repeat(64);

  private static MigrationControlOps opsOver(Path indexBase) {
    return new MigrationControlOps(new IndexGenerationManager(indexBase));
  }

  @Test
  void recordedRequestBindsExplicitSourceSetAndRefusesAbsentReplay(@TempDir Path tempDir)
      throws Exception {
    Path indexBase = tempDir.resolve("source-set-index");
    var manager = new IndexGenerationManager(indexBase);
    String source = manager.initializeOrLoad().state().active_generation();
    var explicit = MigrationStartRequest.newBuilder()
        .setReason("bulk_reindex")
        .setRecordedOperationKey(OPERATION_KEY)
        .setTargetIndexFingerprint(TARGET_FINGERPRINT)
        .setExpectedSourceGeneration(source)
        .addProjectionSourceIds("authority")
        .setProjectionSourceIdsPresent(true).build();
    assertTrue(opsOver(indexBase).startMigration(explicit).getAccepted());
    assertEquals(java.util.List.of("authority"), manager.manifestForOwnedPath(
        manager.resolveGenerationPathStrict("g-" + OPERATION_KEY)).projection_source_ids());
    assertFalse(opsOver(indexBase).startMigration(explicit.toBuilder()
        .clearProjectionSourceIds().setProjectionSourceIdsPresent(false).build()).getAccepted());
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
  void secondCandidateIsRefusedWithoutChangingTheRetainedGeneration(@TempDir Path tempDir)
      throws Exception {
    Path indexBase = tempDir.resolve("index");
    IndexGenerationManager manager = new IndexGenerationManager(indexBase);
    manager.initializeOrLoad();
    MigrationControlOps ops = opsOver(indexBase);
    MigrationStartRequest request = MigrationStartRequest.newBuilder().setReason("manual").build();
    MigrationStartResponse first = ops.startMigration(request);
    assertTrue(first.getAccepted());

    MigrationStartResponse second = ops.startMigration(request);
    assertFalse(second.getAccepted());
    assertTrue(second.getError().contains("retained"));
    assertEquals(first.getBuildingGenerationId(), manager.readStateBestEffort().building_generation());

    manager.updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
    MigrationStartResponse failedCandidate = ops.startMigration(request);
    assertFalse(failedCandidate.getAccepted());
    assertTrue(failedCandidate.getError().contains("retained"));
    assertEquals(first.getBuildingGenerationId(), manager.readStateBestEffort().building_generation());
  }

  @Test
  @DisplayName("cutover reports the reopen requirement only when there is a generation to promote")
  void cutoverRequirementComesFromTheGenerationState(@TempDir Path tempDir) throws Exception {
    Path indexBase = tempDir.resolve("index");
    IndexGenerationManager seed = new IndexGenerationManager(indexBase);
    seed.initializeOrLoad();
    MigrationControlOps ops = opsOver(indexBase);
    var idle = ops.requestCutover(MigrationCutoverRequest.newBuilder().build());
    assertTrue(idle.getAccepted());
    assertFalse(idle.getRestartRequired(), "an idle no-op has no promotion to reopen");
    assertTrue(ops.startMigration(MigrationStartRequest.newBuilder()
        .setReason("checkpoint_test").setRestartWorker(false).build()).getAccepted());
    var cutover = ops.requestCutover(MigrationCutoverRequest.newBuilder()
        .setForceSwitching(true).build());
    assertTrue(cutover.getAccepted());
    assertTrue(cutover.getRestartRequired(),
        "promotion needs reopen even though start did not request a restart");
  }

  @Test
  @DisplayName("directory rollback is refused after a published generation")
  void rollbackCannotRewriteThePublishedPointer(@TempDir Path tempDir) throws Exception {
    Path indexBase = tempDir.resolve("index");
    IndexGenerationManager seed = new IndexGenerationManager(indexBase);
    seed.initializeOrLoad();
    MigrationControlOps ops = opsOver(indexBase);
    ops.startMigration(
        MigrationStartRequest.newBuilder().setReason("checkpoint_test").setRestartWorker(false).build());

    seed.promoteBuildingGenerationToActive();

    MigrationRollbackResponse response =
        ops.rollbackMigration(MigrationRollbackRequest.newBuilder().setRestartWorker(true).build());

    assertFalse(response.getAccepted(), "a live serving view cannot be reverted by pointer only");
    assertFalse(response.getRestartRequired());
    assertEquals(seed.readStateBestEffort().active_generation(),
        new IndexGenerationManager(indexBase).readStateBestEffort().active_generation());
  }

  @Test
  @DisplayName("recorded start keeps its exact target through retry and promotion")
  void recordedStartIsIdempotentAndAlreadyPromotedNeedsNoRestart(@TempDir Path tempDir)
      throws Exception {
    Path indexBase = tempDir.resolve("recorded-index");
    IndexGenerationManager seed = new IndexGenerationManager(indexBase);
    var initial = seed.initializeOrLoad().state();
    MigrationStartRequest request = MigrationStartRequest.newBuilder()
        .setReason("bulk_reindex")
        .setRestartWorker(true)
        .setRecordedOperationKey(OPERATION_KEY)
        .setTargetIndexFingerprint(TARGET_FINGERPRINT)
        .setExpectedSourceGeneration(initial.active_generation())
        .build();

    MigrationStartResponse first = opsOver(indexBase).startMigration(request);
    MigrationStartResponse retry = opsOver(indexBase).startMigration(request);
    String target = "g-" + OPERATION_KEY;
    assertTrue(first.getAccepted(), first.getError());
    assertTrue(first.getRestartRequired());
    assertEquals(initial.active_generation(), first.getActiveGenerationId());
    assertEquals(target, first.getBuildingGenerationId());
    assertEquals("MIGRATING", first.getMigrationState());
    assertTrue(retry.getAccepted(), retry.getError());
    assertEquals(target, retry.getBuildingGenerationId(), "retry must reuse the exact generation id");
    assertEquals(first.getMigrationState(), retry.getMigrationState());
    try (var generations = Files.list(indexBase.resolve("indices"))) {
      assertEquals(2L, generations.count(), "exact retry must not allocate a suffixed generation");
    }

    IndexGenerationManager promoter = new IndexGenerationManager(indexBase);
    assertEquals(target, promoter.promoteBuildingGenerationToActive().active_generation());
    MigrationStartResponse afterPromotion = opsOver(indexBase).startMigration(request);
    assertTrue(afterPromotion.getAccepted(), afterPromotion.getError());
    assertFalse(afterPromotion.getRestartRequired(), "an already active target has no start restart");
    assertEquals(target, afterPromotion.getActiveGenerationId());
    assertEquals("", afterPromotion.getBuildingGenerationId());
    assertEquals("IDLE", afterPromotion.getMigrationState());

    String manifest = Files.readString(
        indexBase.resolve("indices").resolve(target).resolve(".justsearch-index-generation.json"));
    assertTrue(
        manifest.contains(TARGET_FINGERPRINT),
        "the exact target fingerprint is bound to its generation");
  }

  @Test
  @DisplayName("partial recorded identity refuses without creating a legacy generation")
  void partialRecordedInputsDoNotFallBackToLegacyStart(@TempDir Path tempDir) throws Exception {
    Path indexBase = tempDir.resolve("partial-recorded-index");
    IndexGenerationManager seed = new IndexGenerationManager(indexBase);
    var initial = seed.initializeOrLoad().state();
    MigrationControlOps ops = opsOver(indexBase);

    MigrationStartResponse keyOnly = ops.startMigration(MigrationStartRequest.newBuilder()
        .setReason("bulk_reindex").setRestartWorker(true)
        .setRecordedOperationKey(OPERATION_KEY).build());
    MigrationStartResponse fingerprintOnly = ops.startMigration(MigrationStartRequest.newBuilder()
        .setReason("bulk_reindex").setRestartWorker(true)
        .setTargetIndexFingerprint(TARGET_FINGERPRINT).build());
    MigrationStartResponse missingSource = ops.startMigration(MigrationStartRequest.newBuilder()
        .setReason("bulk_reindex").setRestartWorker(true)
        .setRecordedOperationKey(OPERATION_KEY)
        .setTargetIndexFingerprint(TARGET_FINGERPRINT).build());

    assertFalse(keyOnly.getAccepted());
    assertFalse(fingerprintOnly.getAccepted());
    assertFalse(missingSource.getAccepted());
    assertTrue(missingSource.getError().contains("source generation"));
    var unchanged = new IndexGenerationManager(indexBase).readStateBestEffort();
    assertEquals(initial.active_generation(), unchanged.active_generation());
    assertEquals(initial.building_generation(), unchanged.building_generation());
    assertEquals("IDLE", unchanged.migration_state());
    try (var generations = Files.list(indexBase.resolve("indices"))) {
      assertEquals(1L, generations.count(),
          "neither incomplete request may fall back to time-based legacy allocation");
    }
  }
}
