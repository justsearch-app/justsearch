/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * #758 through the migration-replay caller: a buffered
 * {@code PRUNE_PREFIX} replayed while its folder is not there removes nothing and stays buffered,
 * to be retried once the folder answers again. The folder is really renamed away.
 */
final class PrunePrefixReplayUncertainRootTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;

  @Test
  void aBufferedPruneOfAMissingFolderRemovesNothingAndStaysBuffered() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("shared-drive"));
    Path file = Files.writeString(root.resolve("minutes.txt"), "meeting minutes");
    String id = PathNormalizer.normalizePath(file.toAbsolutePath().toString());
    String prefix = root.toString();
    try (RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(Files.createDirectory(tempDir.resolve("index")))
            .withExecutorRegistrations(testLuceneExecutors())
            .open();
        SqliteJobQueue queue = new SqliteJobQueue(tempDir.resolve("jobs.db"))) {
      queue.open();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id,
          SchemaFields.DOC_UID, id + "#0",
          SchemaFields.PATH, id,
          SchemaFields.CONTENT, "meeting minutes")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertTrue(queue.putSwitchBuffer("prune_prefix:" + prefix, "PRUNE_PREFIX", prefix));
      Files.move(root, tempDir.resolve("shared-drive-disconnected"));

      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
              queue, runtime, null, IndexingPacing.unthrottled(), tempDir.resolve("base"),
              tempDir.resolve("active"), new ObjectMapper(), () -> false, () -> true,
              LoggerFactory.getLogger(getClass())));

      runtime.commitOps().maybeRefreshBlocking();
      assertNotNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT),
          "a replayed prune must not remove documents of a folder that is not there");
      assertEquals(1, queue.switchBufferDepth(), "the prune stays buffered and is retried later");
    }
  }

  @Test
  void aBufferedPruneOfAPresentFolderRemovesTheDeletedFile() throws Exception {
    // P1 (must not break).
    Path root = Files.createDirectory(tempDir.resolve("present"));
    Path gone = Files.writeString(root.resolve("gone.txt"), "obsolete");
    String id = PathNormalizer.normalizePath(gone.toAbsolutePath().toString());
    try (RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(Files.createDirectory(tempDir.resolve("index")))
            .withExecutorRegistrations(testLuceneExecutors())
            .open();
        SqliteJobQueue queue = new SqliteJobQueue(tempDir.resolve("jobs.db"))) {
      queue.open();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id,
          SchemaFields.DOC_UID, id + "#0",
          SchemaFields.PATH, id,
          SchemaFields.CONTENT, "obsolete")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertTrue(queue.putSwitchBuffer("prune_prefix:" + root, "PRUNE_PREFIX", root.toString()));
      addedByThePerson(tempDir.resolve("base"), root);
      Files.delete(gone);

      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
              queue, runtime, null, IndexingPacing.unthrottled(), tempDir.resolve("base"),
              tempDir.resolve("active"), new ObjectMapper(), () -> false, () -> true,
              LoggerFactory.getLogger(getClass())));

      runtime.commitOps().maybeRefreshBlocking();
      assertNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT));
      assertEquals(0, queue.switchBufferDepth());
    }
  }

  /**
   * The person's add of {@code root}, which records the root's volume under {@code
   * indexBasePath}: a replayed prune is vouched for as a direct one is, and only the add records a
   * root's volume (#803).
   */
  private static void addedByThePerson(Path indexBasePath, Path root) {
    var adding = new io.justsearch.indexerworker.services.WorkerIngestService(null, null, null,
        IndexingPacing.unthrottled(), indexBasePath, null, null, null, null, 0L);
    var added = adding.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
            .setRootPath(root.toString()).setRootAdded(true).build(),
        io.justsearch.indexerworker.services.CallContext.none());
    assertTrue(added.getWatching(),
        "precondition: the person adds " + root + ": " + added.getErrorMessage());
  }
}
