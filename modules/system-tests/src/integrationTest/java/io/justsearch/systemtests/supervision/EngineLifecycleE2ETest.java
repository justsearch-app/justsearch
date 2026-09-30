/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;

/** D1-16 installed lifecycle scenarios; pending clauses are data, never disabled tests. */
@Timeout(7 * 60)
final class EngineLifecycleE2ETest {
  private record PendingScenario(String name, String owner, boolean requiresAi, String proof) {}

  private static final List<PendingScenario> PENDING = List.of(
      new PendingScenario("generation-mutation-gap-cuts", "D1-8/D1-9", false,
          "edit, removal, addition, supersession, gap decision and both pointer cuts"),
      new PendingScenario("reconfigure-beside-in-place", "D1-4/D1-12/D1-14", true,
          "beside and in-place reconfigure under the device ceiling"));

  @Test
  void migrationStartsLiveInTheInstalledEngine() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("migration");
    System.out.println("LIFECYCLE_HARNESS_BASELINE_PASS §16 live generation transition");
  }

  @Tag("ai")
  @Test
  void stuckIndexRecoversLocallyWithoutRestart() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("lock-index-release");
    System.out.println("LIFECYCLE_STUCK_INDEX_LOCAL_RECOVERY_PASS §16 stuck component");
  }

  @Test
  void stuckIndexExhaustionEscalatesOnceWithoutModels() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("lock-index-exhaustion-no-ai");
    System.out.println("LIFECYCLE_STUCK_INDEX_EXIT_5_PASS §16 stuck component");
  }

  @Test
  void recordedLiveStartRecoversAfterBuildingCheckpointBeforeGreenOpen() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("bulk-live-before-green-open");
  }

  @Test
  void recordedLiveStartRecoversAfterGreenOpenBeforePublication() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("bulk-live-after-green-open");
  }

  @Test
  void refusedRecordedLiveStartUsesOneFreeRestartAndSettles() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("bulk-live-refused-before-green-open");
  }

  @Test
  void capturedEditReplaysTheOriginalUnitAfterTheBuildingCheckpointCrash() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("bulk-captured-edit-before-building-checkpoint");
    System.out.println("LIFECYCLE_CAPTURED_H2_REPLAY_PASS §16 generation transition");
  }

  @Tag("ai")
  @Test
  void semanticAvailabilitySamplesAnInstalledBesideGenerationTransition() throws Exception {
    EngineSupervisedRecoveryE2ETest.runSeededBesideSemanticTransition();
  }

  @Tag("ai")
  @Test
  void issuedASearchCompletesAfterBesideBServes() throws Exception {
    EngineSupervisedRecoveryE2ETest.runSeededBesideIssuedSearch();
  }

  @Tag("ai")
  @Test
  void semanticAvailabilitySamplesAnInstalledInPlaceGenerationTransition() throws Exception {
    EngineSupervisedRecoveryE2ETest.runSeededInPlaceSemanticTransition();
  }

  @Tag("ai")
  @Test
  void acceptedWriteDuringInPlaceBuildKeepsTheSameEngine() throws Exception {
    EngineSupervisedRecoveryE2ETest.runSeededInPlaceAcceptedWriteDuringBuild();
  }

  @Tag("ai")
  @Test
  @Timeout(14 * 60)
  void watcherAddDeleteReplayDuringInPlaceBuildKeepsAAndPromotesB() throws Exception {
    EngineSupervisedRecoveryE2ETest.runSeededInPlaceWatcherDeleteReplay();
  }

  @Tag("ai")
  @Test
  void refusedInPlaceGapRestoresAThenApprovesAndPromotesB() throws Exception {
    EngineSupervisedRecoveryE2ETest.runSeededInPlaceGapRestoration();
  }

  @Tag("ai")
  @Test
  @Timeout(14 * 60)
  void failedInPlaceARecomposeCancelsBAndRestoresA() throws Exception {
    EngineSupervisedRecoveryE2ETest.runSeededInPlaceRecomposeFailureCancellation();
  }

  @Tag("ai")
  @Test
  @Timeout(14 * 60)
  void lowMemoryInPlaceChangingInputRecoversBeforePointer() throws Exception {
    EngineSupervisedRecoveryE2ETest.runSeededInPlaceCombinedMaintenance(
        "model-live-a-b-low-memory-before-pointer", "installer-before-pointer");
  }

  @Tag("ai")
  @Test
  @Timeout(14 * 60)
  void lowMemoryInPlaceChangingInputRecoversAfterPointerBeforeSettings() throws Exception {
    EngineSupervisedRecoveryE2ETest.runSeededInPlaceCombinedMaintenance(
        "model-live-a-b-low-memory-pointer-before-settings", "installer-pointer-before-settings");
  }

  @Tag("ai")
  @Test
  void generativeSecondOwnerFailureAbortsQueryCandidateAndKeepsA() throws Exception {
    EngineSupervisedRecoveryE2ETest.runQueryAndGenerativeActualOwnerRollback();
  }

  @Tag("ai")
  @Test
  @Timeout(14 * 60)
  void committedPointerBootReconcilesPersistedRootChangesBeforePublication() throws Exception {
    EngineSupervisedRecoveryE2ETest.runSeededInPlaceCombinedMaintenance(
        "model-live-a-b-low-memory-pointer-before-settings", "installer-pointer-before-settings", true);
  }

  @Test
  void pendingFeatureScenariosAreNamedAndReported() {
    var names = new HashSet<String>();
    for (PendingScenario scenario : PENDING) {
      assertFalse(scenario.name().isBlank());
      assertFalse(scenario.owner().isBlank());
      assertFalse(scenario.proof().isBlank());
      assertTrue(names.add(scenario.name()), "Duplicate pending scenario " + scenario.name());
      System.out.println("LIFECYCLE_PENDING " + scenario.name() + " owner=" + scenario.owner()
          + " ai=" + scenario.requiresAi() + " proof=" + scenario.proof());
    }
    // Reduce this count only when an actual exercise*(c) scenario replaces a pending entry.
    assertEquals(2, names.size());
  }
}
