/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MaintenanceSyncClaimTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @Test
  void syncAgainstAnEarlierEmptyIndexDoesNotReplacePendingOrClaimedWork() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("source.txt"), "source waiting for publication");
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).ephemeral()
            .withExecutorRegistrations(testLuceneExecutors()).open();
        var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root))));
      var sync = new SyncDirectoryOps(runtime.readPathOps(), runtime.pruneOps(), runtime.commitOps(),
          queue, IndexingPacing.unthrottled());
      assertEquals("", sync.execute(root.toString(), false, null, () -> false, null,
          SyncDirectoryOps.AdmissionMode.MAINTENANCE).getError());
      var claim = queue.pollPending(1).getFirst();
      assertEquals("", sync.execute(root.toString(), false, null, () -> false, null,
          SyncDirectoryOps.AdmissionMode.MAINTENANCE).getError());
      assertTrue(queue.ownsClaimForPublication(claim), "sync must preserve the current publication owner");
      assertEquals(1, queue.queueDepth());
    }
  }
}
