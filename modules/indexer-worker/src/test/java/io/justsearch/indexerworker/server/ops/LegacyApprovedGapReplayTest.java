/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.PathPrefixScope;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

final class LegacyApprovedGapReplayTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test
  void upgradingAnApprovedGapCannotTurnItsSkippedDeleteIntoAnAppliedDelete() throws Exception {
    Path parent = Files.createDirectories(directory.resolve("parent"));
    String path = PathNormalizer.normalizeKey(parent.resolve("keep.txt"));
    String prefix = PathNormalizer.normalizePathPrefix(parent.toString());
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"));
        var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, path, SchemaFields.DOC_UID, "kept-file",
          SchemaFields.PATH, path, SchemaFields.CONTENT, "keep")));
      runtime.commitOps().commitAndTrack();
      assertTrue(queue.putSwitchBufferForGeneration("green", "prefix:" + prefix, "DELETE_PREFIX", prefix));
      var approved = queue.listSwitchBufferOpsStrictForGeneration("green").getFirst();

      var prepared = KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(context(queue, runtime, approved));

      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("keep", runtime.documentFieldOps().getDocumentField(path, SchemaFields.CONTENT),
          "conversion must not apply a delete whose exact version was approved to be skipped");
      assertTrue(prepared.isEmpty(), "a changed receipt requires a fresh approval");
      var upgraded = queue.listSwitchBufferOpsStrictForGeneration("green").getFirst();
      assertEquals(approved.key(), upgraded.key());
      assertEquals(approved.generation(), upgraded.generation());
      assertEquals(approved.lastUpdatedMs(), upgraded.lastUpdatedMs());
      assertNotEquals(approved.revision(), upgraded.revision());
      assertEquals(new PathPrefixScope(prefix, java.util.List.of()), PathPrefixScope.decode(upgraded.payload()));

      var fresh = KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(context(queue, runtime, upgraded));
      assertTrue(fresh.isPresent());
      assertEquals(java.util.List.of(upgraded), fresh.orElseThrow().versions());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, fresh.orElseThrow()));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration("green").isEmpty());
      assertEquals("keep", runtime.documentFieldOps().getDocumentField(path, SchemaFields.CONTENT));
    }
  }

  private static KnowledgeServerMigrationOps.DrainSwitchBufferContext context(SqliteJobQueue queue,
      RunningRuntime runtime, SwitchBufferCapableQueue.SwitchBufferOp approved) {
    return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
        IndexingPacing.unthrottled(), runtime.openedIndexPath(), runtime.openedIndexPath(),
        new ObjectMapper(), () -> false, () -> true, LoggerFactory.getLogger(LegacyApprovedGapReplayTest.class),
        Long.MAX_VALUE, "green", ignored -> true, Set.of(approved));
  }
}
