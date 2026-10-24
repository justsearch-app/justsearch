/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.UnwatchRootRequest;
import io.justsearch.ipc.WatchRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClearAllNestedRootsTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test
  void clearingAllIncludesWorkerOnlyNestedAndHeldDeclarations() throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path base = temp.resolve("index");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"));
        var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(base.resolve("active")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, base.resolve("active"), runtime, runtime, null, 0L);
      var registry = new RootWatcherRegistry();
      service.setRootWatcherRegistry(registry);
      for (Path root : List.of(parent, child)) {
        assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString())
            .setRootAdded(true).build(), CallContext.none()).getWatching());
        Path file = Files.writeString(root.resolve("file.txt"), "content");
        String id = PathNormalizer.normalizeKey(file);
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, java.util.UUID.randomUUID().toString(),
            SchemaFields.PATH, id, SchemaFields.CONTENT, "content")));
        queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)));
      }
      registry.watchWhenVolumeReturns(child, "nested");
      runtime.commitOps().commitAndTrack();

      var response = service.unwatchRoot(UnwatchRootRequest.parseFrom(new byte[] {16, 1}) /* field 2: clear_all_roots */,
          CallContext.none());
      assertTrue(response.getUnwatched());

      assertTrue(registry.watchedRoots().isEmpty());
      assertFalse(registry.awaitsVolume(child));
      assertEquals(0, queue.queueDepth());
      runtime.commitOps().maybeRefreshBlocking();
      for (Path root : List.of(parent, child)) {
        assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(
            PathNormalizer.normalizeKey(root.resolve("file.txt"))));
        assertTrue(RootVolumeLedger.under(base).recorded(root).isEmpty());
      }
    }
  }
}
