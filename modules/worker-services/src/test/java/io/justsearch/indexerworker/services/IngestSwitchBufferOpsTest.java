/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import org.junit.jupiter.api.Test;

final class IngestSwitchBufferOpsTest {

  @Test
  void unreadableMigrationStateCannotRouteMutationDirectly() {
    var generations = mock(IndexGenerationManager.class);
    when(generations.readStateBestEffort()).thenThrow(new IllegalStateException("state unavailable"));
    var switching = new IngestSwitchBufferOps(mock(JobQueue.class), generations,
        mock(OperationalMetrics.class));

    assertThrows(WorkerServiceException.class, switching::isSwitching);
  }
}
