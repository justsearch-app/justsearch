/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class ProtectedInternalReplayTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test
  void retryWitnessUsesThePersistedReservedCollection() throws Exception {
    Path data = Files.createDirectory(temp.resolve("data"));
    Path file = Files.writeString(Files.createDirectory(data.resolve("agent-history")).resolve("run.md"), "history");
    var previous = IngestionSkipPolicy.active();
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      bind(data);
      queue.open();
      assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(file, file),
          "an unscoped retry cannot admit an internal transcript as user content");
      assertEquals(1, queue.enqueueEntries(java.util.List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(file)), "agent-history"));
      assertEquals(1, queue.reenqueue(JobQueue.EnqueueEntry.stat(file)).accepted());
      var claim = queue.pollPending(1).getFirst();
      assertEquals("agent-history", claim.collection());
      var row = queue.watcherReconciliationRow(file.toString());
      assertEquals(SourceContentHash.sha256(file), row.get("admitted_source_sha256"),
          "a retry must retain a real source witness for the reserved internal submission");
    } finally { IngestionSkipPolicy.installResolved(previous); }
  }

  @Test
  void pruneReplayRetainsReservedOwnershipForRecreatedHistoryAndRejectsUserDataCopy() throws Exception {
    Path data = Files.createDirectory(temp.resolve("data"));
    Path root = Files.createDirectory(data.resolve("agent-history"));
    Path transcript = root.resolve("run.md");
    Path userCopy = root.resolve("user-copy.md");
    var previous = IngestionSkipPolicy.active();
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"));
        var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      bind(data);
      queue.open();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, transcript.toString(), SchemaFields.DOC_UID, "history-uid",
          SchemaFields.PATH, transcript.toString(), SchemaFields.CONTENT, "history",
          SchemaFields.COLLECTION, "agent-history")));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, userCopy.toString(), SchemaFields.DOC_UID, "user-uid",
          SchemaFields.PATH, userCopy.toString(), SchemaFields.CONTENT, "user copy")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), temp.resolve("base"), temp.resolve("index"), runtime, runtime, null, 0L);
      assertTrue(service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
          .setRootPath(temp.toString()).setRootAdded(true).build(),
          io.justsearch.indexerworker.services.CallContext.none()).getWatching());
      var result = service.replayBufferedPrune(runtime, root.toString(), () -> {
        try {
          Files.writeString(transcript, "history returned");
          Files.writeString(userCopy, "user copy returned");
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
      });
      assertTrue(result.result().complete());
      assertEquals(1, result.requeued(), "only the app-owned transcript is readmitted");
      var pending = queue.pollPending(10);
      assertEquals(1, pending.size());
      assertEquals(transcript, pending.getFirst().path());
      assertEquals("agent-history", pending.getFirst().collection(), "replay preserves internal ownership");
    } finally { IngestionSkipPolicy.installResolved(previous); }
  }

  private static void bind(Path data) throws Exception {
    try {
      IngestionSkipPolicy.installResolved((IngestionSkipPolicy) IngestionSkipPolicy.class
          .getMethod("withDataDirectory", Path.class).invoke(IngestionSkipPolicy.defaultPolicy(), data));
    } catch (NoSuchMethodException baseline) { /* Exercise old admission, then fail behavior assertions. */ }
  }
}
