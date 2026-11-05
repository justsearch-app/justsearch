/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SyncCoverageEvidenceTest {
  @TempDir Path directory;

  @Test void R1EmptyMessageExceptionIsAnExplicitSyncFailure() throws Exception {
    Files.writeString(directory.resolve("eligible.txt"), "text");
    var queue = mock(JobQueue.class);
    when(queue.enqueueEntries(anyList())).thenThrow(new IllegalStateException(""));
    var response = new SyncDirectoryOps(null, null, null, queue, IndexingPacing.unthrottled())
        .execute(directory.toString(), true, null, () -> false, null);
    assertFalse(response.getError().isEmpty(), "an exception with an empty message must not look successful");
  }

  @Test void R1CancelledAndInterruptedWalksNeverCompleteCoverage() throws Exception {
    var ops = new SyncDirectoryOps(null, null, null, mock(JobQueue.class), IndexingPacing.unthrottled());
    assertFalse(ops.execute(directory.toString(), true, null, () -> true, null).getError().isEmpty());
    Files.writeString(directory.resolve("eligible.txt"), "text");
    Thread.currentThread().interrupt();
    try {
      assertFalse(ops.execute(directory.toString(), true, null, () -> false, null).getError().isEmpty());
    } finally { Thread.interrupted(); }
  }

}
