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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** WP3.5 S35.2: completion includes visibility through the serving reader. */
final class RootRemovalVisibilityTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completedDeletionIsAbsentFromFreshServingReads(boolean migration) throws Exception {
    Path root = Files.createDirectory(temp.resolve("folder"));
    Path document = Files.writeString(root.resolve("notes.txt"), "quokka");
    Path base = temp.resolve("index");
    var manager = new IndexGenerationManager(base);
    String active = manager.initializeOrLoad().activeGenerationId();
    String building = migration ? manager.startMigration("remove-visibility").building_generation() : active;
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"));
        var serving = open(manager.resolveGenerationPathStrict(active))) {
      queue.open();
      RunningRuntime writer = migration ? open(manager.resolveGenerationPathStrict(building)) : serving;
      try {
        for (var runtime : migration ? java.util.List.of(serving, writer) : java.util.List.of(serving)) {
          runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
              SchemaFields.DOC_ID, PathNormalizer.normalizeKey(document),
              SchemaFields.DOC_UID, "root-removal-visibility",
              SchemaFields.PATH, PathNormalizer.normalizeKey(document), SchemaFields.CONTENT, "quokka")));
          runtime.commitOps().commitAndTrack();
          runtime.commitOps().maybeRefreshBlocking();
          assertEquals(1, runtime.textQueryOps().searchText("quokka", 10, null).totalHits());
        }
        var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
            base, writer.openedIndexPath(), writer, serving, null, 0L);
        var reply = service.deleteByPath(DeleteByPathRequest.newBuilder().setPath(root.toString()).build(), CallContext.none());
        assertEquals("", reply.getError());
        assertFalse(reply.getBuffered());
        assertEquals(0, serving.textQueryOps().searchText("quokka", 10, null).totalHits(),
            "successful removal must be absent from the next serving read without a caller refresh");
        assertEquals(0, writer.textQueryOps().searchText("quokka", 10, null).totalHits(),
            "successful removal must also refresh its future serving target");
      } finally {
        if (writer != serving) writer.close();
      }
    }
  }

  @Test
  void readOnlyServingViewCannotConfirmRemovalAfterFailedMigration() throws Exception {
    Path root = Files.createDirectory(temp.resolve("failed-folder"));
    Path document = Files.writeString(root.resolve("notes.txt"), "quokka");
    Path base = temp.resolve("failed-index");
    var manager = new IndexGenerationManager(base);
    String active = manager.initializeOrLoad().activeGenerationId();
    String building = manager.startMigration("failed-removal-visibility").building_generation();
    Path activePath = manager.resolveGenerationPathStrict(active);
    var indexDocument = new IndexDocument(Map.of(
        SchemaFields.DOC_ID, PathNormalizer.normalizeKey(document),
        SchemaFields.DOC_UID, "failed-root-removal", SchemaFields.PATH,
        PathNormalizer.normalizeKey(document), SchemaFields.CONTENT, "quokka"));
    try (var seed = open(activePath)) {
      seed.indexingCoordinator().indexSingle(indexDocument);
      seed.commitOps().commitAndTrack();
    }
    manager.updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
    try (var queue = new SqliteJobQueue(temp.resolve("failed-jobs.db"));
        var serving = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(activePath).withExecutorRegistrations(testLuceneExecutors()).openDeferred();
        var writer = open(manager.resolveGenerationPathStrict(building))) {
      queue.open();
      writer.indexingCoordinator().indexSingle(indexDocument);
      writer.commitOps().commitAndTrack();
      writer.commitOps().maybeRefreshBlocking();
      assertEquals(1, serving.textQueryOps().searchText("quokka", 10, null).totalHits());
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, writer.openedIndexPath(), writer, serving, null, 0L);
      var response = service.deleteByPath(DeleteByPathRequest.newBuilder()
          .setPath(root.toString()).build(), CallContext.none());
      assertFalse(response.getError().isEmpty() && !response.getBuffered() && response.getDeletedJobs() >= 0,
          "a read-only serving view must not confirm removal after a failed rebuild");
      assertEquals(1, serving.textQueryOps().searchText("quokka", 10, null).totalHits());
      writer.commitOps().maybeRefreshBlocking();
      assertEquals(1, writer.textQueryOps().searchText("quokka", 10, null).totalHits(),
          "refusal must precede the writer effect too");
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
