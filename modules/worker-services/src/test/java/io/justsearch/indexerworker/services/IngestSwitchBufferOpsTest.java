/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IngestSwitchBufferOpsTest {

  @Test
  void unreadableMigrationStateCannotRouteMutationDirectly() {
    var generations = mock(IndexGenerationManager.class);
    when(generations.readStateBestEffort()).thenThrow(new IllegalStateException("state unavailable"));
    var switching = new IngestSwitchBufferOps(mock(JobQueue.class), generations);

    assertThrows(WorkerServiceException.class, switching::isSwitching);
  }

  @Test
  void acceptedFileWritesKeepTheirCandidateJournalDuringGapWait(@TempDir Path temp)
      throws Exception {
    var generations = new IndexGenerationManager(temp.resolve("index"));
    generations.initializeOrLoad();
    String building = generations.startMigration("gap-wait").building_generation();
    generations.updateMigrationState(IndexGenerationManager.MigrationState.AWAITING_ACCEPTANCE);
    var switching = new IngestSwitchBufferOps(mock(JobQueue.class), generations);

    assertEquals(building, switching.buildingGenerationForFileAdmission());
    assertEquals(building, switching.migratingGeneration());
  }
}
