/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  @Test void R6UnchangedEligibleFilesRemainCoverageEvidenceWithoutAdmission() throws Exception {
    Path file = Files.writeString(directory.resolve("eligible.txt"), "text");
    var reads = mock(ReadPathOps.class);
    var fields = new java.util.HashMap<String, String>();
    fields.put(io.justsearch.indexing.SchemaFields.PATH, file.toString());
    fields.put(io.justsearch.indexing.SchemaFields.SIZE_BYTES, Long.toString(Files.size(file)));
    fields.put(io.justsearch.indexing.SchemaFields.MODIFIED_AT, Long.toString(Files.getLastModifiedTime(file).toMillis()));
    fields.put(io.justsearch.indexing.SchemaFields.SOURCE_SHA256,
        io.justsearch.indexerworker.loop.SourceContentHash.sha256(file));
    fields.put(io.justsearch.indexing.SchemaFields.EXTRACTION_POLICY_ID,
        io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults().policyId());
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(List.of(
            new LuceneRuntimeTypes.SearchHit(file.toString(), 1f, fields)), 1, 0));
    var reuse = mock(io.justsearch.adapters.lucene.runtime.DocumentFieldOps.class);
    when(reuse.isUnmodified(anyList(), any())).thenReturn(java.util.Set.of(file.toString()));
    var queue = mock(JobQueue.class);
    var ops = new SyncDirectoryOps(reads, null, null, queue, IndexingPacing.unthrottled());
    ops.setDocumentFieldOps(reuse);
    var response = ops.execute(directory.toString(), false, null, () -> false, null);
    assertEquals("", response.getError()); assertFalse(response.getSkipped());
    assertFalse(response.getDeleteDetectionUnverified()); assertEquals(0, response.getFilesAdded());
    // Reflection keeps the before-state runnable; absence is an assertion failure about missing evidence.
    var getter = assertDoesNotThrow(() -> response.getClass().getMethod("getEligibleFiles"));
    assertEquals(1L, getter.invoke(response));
    verify(queue, never()).enqueueEntries(anyList());
    verify(queue, never()).enqueueEntries(anyList(), nullable(String.class));
  }
}
