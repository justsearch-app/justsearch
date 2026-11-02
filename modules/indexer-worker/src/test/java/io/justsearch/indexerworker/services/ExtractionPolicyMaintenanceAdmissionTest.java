/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ExtractionPolicyMaintenanceAdmissionTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @Test
  void routineReconciliationReadmitsCompletedOlderExtractionAfterRestart() throws Exception {
    check("completed");
  }

  @ParameterizedTest
  @ValueSource(strings = {"terminal", "future-retry"})
  void staleExtractionDoesNotResetProtectedFailureState(String state) throws Exception {
    check(state);
  }

  private void check(String state) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("file.txt"), "unchanged source");
    String id = PathNormalizer.normalizeKey(file);
    Path db = temporary.resolve("queue.db");
    try (var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "uid", SchemaFields.PATH, id,
          SchemaFields.CONTENT, "old extraction", SchemaFields.SIZE_BYTES, Files.size(file),
          SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis(),
          SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file),
          SchemaFields.EXTRACTION_POLICY_ID, "tika-default-v7")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      Map<String, String> saved;
      try (var queue = new SqliteJobQueue(db)) {
        queue.open();
        assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root))));
        if (state.equals("completed")) queue.markDone(file);
        else queue.markFailed(file, IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED, "fixture.io",
            state.equals("terminal") ? IngestionRetryPolicy.NONE : IngestionRetryPolicy.RETRY_WITH_BACKOFF));
        saved = queue.watcherReconciliationRow(id);
      }
      try (var queue = new SqliteJobQueue(db)) {
        queue.open();
        var sync = new SyncDirectoryOps(runtime.readPathOps(), null, null, queue, IndexingPacing.unthrottled());
        var result = sync.execute(root.toString(), false, null, () -> false, null,
            SyncDirectoryOps.AdmissionMode.MAINTENANCE);
        assertEquals("", result.getError());
        if (state.equals("completed")) {
          assertEquals(1, result.getFilesAdded(), "routine reconciliation must re-admit an unchanged completed older extraction");
          var jobs = queue.pollPending(2);
          assertEquals(1, jobs.size());
          assertEquals(file, jobs.getFirst().path());
          assertFalse(jobs.getFirst().forceReindex(), "repair uses ordinary maintenance admission");
        } else {
          assertEquals(0, result.getFilesAdded());
          assertEquals(saved, queue.watcherReconciliationRow(id), "policy repair preserves the existing retry owner and wait");
        }
      }
    }
  }
}
