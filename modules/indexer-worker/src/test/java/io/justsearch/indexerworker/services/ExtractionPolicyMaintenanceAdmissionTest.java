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
    check("completed", false);
  }

  @Test
  void olderTerminalExtractionFailureIsReadmitted() throws Exception {
    check("extraction-terminal", false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"terminal", "future-retry"})
  void staleExtractionDoesNotResetProtectedFailureState(String state) throws Exception {
    check(state, false);
  }

  @Test
  void completedMissingChunkIsReadmittedWithoutForce() throws Exception {
    check("completed", true);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"terminal", "future-retry"})
  void missingChunkRepairPreservesProtectedFailureOwner(String state) throws Exception {
    check(state, true);
  }

  private void check(String state, boolean missingChunk) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("file.txt"), "unchanged source");
    String id = PathNormalizer.normalizeKey(file);
    Path db = temporary.resolve("queue.db");
    try (var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      String content = missingChunk ? "alpha beta gamma delta ".repeat(400) : "old extraction";
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "uid", SchemaFields.PATH, id,
          SchemaFields.CONTENT, content, SchemaFields.SIZE_BYTES, Files.size(file),
          SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis(),
          SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file),
          SchemaFields.EXTRACTION_POLICY_ID, missingChunk
              ? io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults().policyId() : "tika-default-v7")));
      if (missingChunk) {
        int total = io.justsearch.indexing.chunking.ChunkSplitter.splitWithMetadata(content,
            io.justsearch.indexing.chunking.ChunkSplitter.DEFAULT_CHUNK_TOKENS,
            io.justsearch.indexing.chunking.ChunkSplitter.DEFAULT_OVERLAP_TOKENS).size();
        assertTrue(total > 1);
        String revision = io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(content);
        for (int child = 0; child < total; child++) {
          runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
              SchemaFields.DOC_ID, id + "#" + child, SchemaFields.DOC_UID, "uid#" + child,
              SchemaFields.PATH, id, SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, id,
              SchemaFields.CHUNK_INDEX, child, SchemaFields.CHUNK_TOTAL, total,
              SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision)));
        }
        runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.CHUNK_TOTAL, total,
            SchemaFields.CONTENT_SHA256, revision, SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision));
        runtime.commitOps().commitAndTrack();
        assertTrue(runtime.documentFieldOps().isUnmodified(id, Files.getLastModifiedTime(file).toMillis(),
            Files.size(file), SourceContentHash.sha256(file),
            io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults().policyId()));
        runtime.indexingCoordinator().deleteById(id + "#1");
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      Map<String, String> saved;
      try (var queue = new SqliteJobQueue(db)) {
        queue.open();
        queue.useDeclaredCollectionRoots(() -> List.of(
            new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root, null)));
        assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root))));
        if (state.equals("completed")) queue.markDone(file);
        else if (state.equals("extraction-terminal")) queue.markFailed(file,
            IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "PARSER_FAILED", IngestionRetryPolicy.NONE),
            new JobQueue.IngestionLedgerEntry(null, null, Files.size(file), Files.getLastModifiedTime(file).toMillis(),
                "FILE", "FAILED", "tika-default-v7", "fixture"));
        else queue.markFailed(file, IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED, "fixture.io",
            state.equals("terminal") ? IngestionRetryPolicy.NONE : IngestionRetryPolicy.RETRY_WITH_BACKOFF));
        saved = queue.watcherReconciliationRow(id);
      }
      try (var queue = new SqliteJobQueue(db)) {
        queue.open();
        queue.useDeclaredCollectionRoots(() -> List.of(
            new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root, null)));
        var sync = new SyncDirectoryOps(runtime.readPathOps(), null, null, queue, IndexingPacing.unthrottled());
        sync.setDocumentFieldOps(runtime.documentFieldOps());
        var result = sync.execute(root.toString(), false, null, () -> false, null,
            SyncDirectoryOps.AdmissionMode.MAINTENANCE);
        assertEquals("", result.getError());
        if (state.equals("completed") || state.equals("extraction-terminal")) {
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
