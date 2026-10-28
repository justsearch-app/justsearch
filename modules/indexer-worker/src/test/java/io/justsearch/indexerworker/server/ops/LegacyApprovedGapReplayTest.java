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

  @Test void exactApprovedMalformedRootPreservesUnrelatedDelete() throws Exception { checkMalformedRootApproval(true, false); }
  @Test void unapprovedMalformedRootIsHeld() throws Exception { checkMalformedRootApproval(false, false); }
  @Test void changedRevisionCannotReuseApproval() throws Exception { checkMalformedRootApproval(true, true); }

  private void checkMalformedRootApproval(boolean approve, boolean replace) throws Exception {
    Path parent = Files.createDirectories(directory.resolve("parent"));
    String path = PathNormalizer.normalizeKey(parent.resolve("remove.txt"));
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"));
        var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, path, SchemaFields.DOC_UID, "removed-file",
          SchemaFields.PATH, path, SchemaFields.CONTENT, "remove")));
      runtime.commitOps().commitAndTrack();
      assertTrue(queue.putSwitchBufferForGeneration("green", "root:malformed", "SYNC_ROOT", "malformed"));
      var approved = queue.listSwitchBufferOpsStrictForGeneration("green").getFirst();
      if (replace) {
        assertTrue(queue.putSwitchBufferForGeneration("green", approved.key(), "SYNC_ROOT", "malformed-replacement"));
        var changed = queue.listSwitchBufferOpsStrictForGeneration("green").getFirst();
        assertEquals(approved.key(), changed.key());
        assertNotEquals(approved.revision(), changed.revision());
      }
      String deletion = new io.justsearch.indexerworker.queue.SwitchBufferDelete(
          path, io.justsearch.indexerworker.queue.SwitchBufferDelete.Origin.EXPLICIT, null).encode();
      if (approve && !replace) assertTrue(queue.putSwitchBufferForGeneration("green", "path:" + path, "DELETE", deletion));
      var before = queue.listSwitchBufferOpsStrictForGeneration("green");
      var context = context(queue, runtime, approve ? Set.of(approved) : Set.of());
      var prepared = assertDoesNotThrow(() -> KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(context),
          "public replay must handle exact gap approval without eager malformed ROOT decode");
      runtime.commitOps().maybeRefreshBlocking();
      if (approve && !replace) {
        assertTrue(prepared.isPresent(), "approved exact ROOT must permit unrelated valid DELETE");
        assertEquals(before, prepared.orElseThrow().versions());
        assertNull(runtime.documentFieldOps().getDocumentField(path, SchemaFields.CONTENT));
        assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, prepared.orElseThrow()));
        assertTrue(queue.listSwitchBufferOpsStrictForGeneration("green").isEmpty());
      } else {
        assertTrue(prepared.isEmpty(), "malformed ROOT without exact approval must remain held");
        assertEquals(before, queue.listSwitchBufferOpsStrictForGeneration("green"));
        assertEquals("remove", runtime.documentFieldOps().getDocumentField(path, SchemaFields.CONTENT));
      }
    }
  }

  private static KnowledgeServerMigrationOps.DrainSwitchBufferContext context(SqliteJobQueue queue,
      RunningRuntime runtime, SwitchBufferCapableQueue.SwitchBufferOp approved) {
    return context(queue, runtime, Set.of(approved));
  }

  private static KnowledgeServerMigrationOps.DrainSwitchBufferContext context(SqliteJobQueue queue,
      RunningRuntime runtime, Set<SwitchBufferCapableQueue.SwitchBufferOp> approvals) {
    return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
        IndexingPacing.unthrottled(), runtime.openedIndexPath(), runtime.openedIndexPath(),
        new ObjectMapper(), () -> false, () -> true, LoggerFactory.getLogger(LegacyApprovedGapReplayTest.class),
        Long.MAX_VALUE, "green", ignored -> true, approvals);
  }
}
