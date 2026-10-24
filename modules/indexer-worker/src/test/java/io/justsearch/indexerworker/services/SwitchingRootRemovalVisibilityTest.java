/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.DeleteByPathRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** WP3.5 S35.2: preserves incomplete receipts on ordinary and deferred switches. */
final class SwitchingRootRemovalVisibilityTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void switchingWithoutWritableServingTargetNeverConfirmsCompletion(boolean deferred) throws Exception {
    Path root = Files.createDirectory(temp.resolve("switching-folder"));
    Path base = temp.resolve("switching-index");
    var manager = new IndexGenerationManager(base);
    String active = manager.initializeOrLoad().activeGenerationId();
    manager.startMigration("remove-switching");
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    Path activePath = manager.resolveGenerationPathStrict(active);
    // Deferred startup has only a reader; ordinary startup can have a single serving writer.
    if (deferred) {
      try (var seed = open(activePath)) { seed.commitOps().commitAndTrack(); }
    }
    try (var queue = new SqliteJobQueue(temp.resolve("switching-jobs.db"));
        var serving = deferred ? IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(activePath).withExecutorRegistrations(testLuceneExecutors()).openDeferred()
            : open(activePath)) {
      queue.open();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, activePath, deferred ? null : (RunningRuntime) serving, serving, null, 0L);
      var response = service.deleteByPath(DeleteByPathRequest.newBuilder()
          .setPath(root.toString()).build(), CallContext.none());
      assertFalse(response.getError().isEmpty() && !response.getBuffered() && response.getDeletedJobs() >= 0,
          "a buffered or unwritable switch cannot acknowledge removal completion");
      if (deferred) assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
      else {
        assertTrue(response.getBuffered());
        assertEquals(1, queue.listSwitchBufferOpsStrict().size(), "ordinary switch keeps its durable retry receipt");
      }
    }
  }

  private RunningRuntime open(Path path) throws Exception {
    return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(path)
        .withConfig(new io.justsearch.configuration.resolved.ResolvedConfigBuilder()
            .putDefault("index.nrt.target_max_stale_ms", "600000")
            .putDefault("index.nrt.max_stale_ms", "600000").build())
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }
}
