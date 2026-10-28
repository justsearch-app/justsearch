/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.PathPrefixScope;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
import io.justsearch.indexerworker.queue.SwitchBufferSyncRoot;
import io.justsearch.indexerworker.services.ProjectionDocumentMapper;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * A retained SYNC_ROOT in B's snapshot, after a pointer-before-acknowledgement stop, is validated
 * and acknowledged with B's other receipts and never walked again; it is never a reason for the
 * real settlement entry point to accept anything the existing certification refuses. What a real
 * boot then settles is covered by the real-process crash-recovery test.
 */
final class CommittedNativeSyncRootTest extends LuceneExecutorTestBase {
  private static final String B = "g-20261028-000000";
  private static final String OTHER = "g-20261028-000001";
  private static final String RECORDED = "g-018f0000-0000-7000-8000-000000000001";
  private static final String SOURCE = "memory";

  @TempDir Path temp;

  // ---- P6: legacy and versioned payloads are acknowledged; their committed effects stand ----

  @Test
  void legacyAndVersionedRootSyncsAreAcknowledgedWithoutAWalk() throws Exception {
    Path legacy = Files.createDirectories(temp.resolve("legacy"));
    Path versioned = Files.createDirectories(temp.resolve("versioned"));
    Files.writeString(legacy.resolve("on-disk.txt"), "never admitted again");
    Files.writeString(versioned.resolve("on-disk.txt"), "never admitted again");
    String legacyRoot = QueryFilterBuilder.normalizePathPrefix(legacy.toString());
    try (var queue = queue()) {
      queue.putSwitchBufferForGeneration(B, "sync_root:" + legacyRoot, "SYNC_ROOT",
          "{\"root_path\":\"" + legacyRoot + "\",\"force\":true}");
      syncRoot(queue, B, versioned, true, new JobQueue.EnqueueProvenance("agent", "MCP"));
      var runtime = fileRuntime();

      assertTrue(settle(queue, runtime, B));
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict(), "both receipts are acknowledged");
      assertFalse(queue.hasNonterminalJobsByPathPrefixStrict(QueryFilterBuilder.normalizePathPrefix(temp.toString())),
          "no file is admitted again");
      verify(runtime.indexingCoordinator(), never()).deleteByIdAndChunks(anyString());
    }
  }

  // ---- P7: unrelated backlog is not a prerequisite ----

  @Test
  void unrelatedQueueBacklogDoesNotHoldRootSyncSettlement() throws Exception {
    try (var queue = queue()) {
      Path unrelated = Files.writeString(temp.resolve("unrelated.txt"), "backlog");
      assertEquals(1, queue.enqueue(List.of(unrelated), null));
      syncRoot(queue, B, Files.createDirectories(temp.resolve("watched")), false);
      delete(queue, B, file("gone.txt"));

      assertTrue(settle(queue, fileRuntime(), B));
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
      assertTrue(queue.hasNonterminalJobsByPathPrefixStrict(
          QueryFilterBuilder.normalizePathPrefix(temp.toString())), "the backlog is left alone");
    }
  }

  // ---- S2: a later SYNC_ROOT supersedes the exact file receipts under its root ----

  @Test
  void fileReceiptsUnderALaterRootSyncAreAcknowledgedWithoutReplay() throws Exception {
    Path watched = Files.createDirectories(temp.resolve("watched"));
    String restored = watched.resolve("restored.txt").toString();
    String outside = file("outside.txt");
    String later = watched.resolve("later.txt").toString();
    try (var queue = queue()) {
      Path changed = Files.writeString(watched.resolve("changed.txt"), "admitted, then rewalked");
      assertTrue(queue.enqueueAndBufferFileForGeneration(B,
          JobQueue.EnqueueEntry.stat(changed).withinRoot(watched), null));
      delete(queue, B, restored);
      delete(queue, B, outside);
      syncRoot(queue, B, watched, true);
      delete(queue, B, later);
      var runtime = fileRuntime();

      assertTrue(settle(queue, runtime, B));
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
      verify(runtime.indexingCoordinator(), never()).deleteByIdAndChunks(restored);
      verify(runtime.indexingCoordinator()).deleteByIdAndChunks(outside);
      verify(runtime.indexingCoordinator()).deleteByIdAndChunks(later);
      verify(runtime, never()).documentFieldOps();
    }
  }

  @Test
  void fileWitnessAcceptedAfterTheRootSyncIsStillRequired() throws Exception {
    Path watched = Files.createDirectories(temp.resolve("watched"));
    try (var queue = queue()) {
      syncRoot(queue, B, watched, true);
      Path after = Files.writeString(watched.resolve("after.txt"), "not yet in B");
      assertTrue(queue.enqueueAndBufferFileForGeneration(B,
          JobQueue.EnqueueEntry.stat(after).withinRoot(watched), null));
      var retained = queue.listSwitchBufferOpsStrict();
      var runtime = fileRuntime();
      var fields = mock(io.justsearch.adapters.lucene.runtime.DocumentFieldOps.class);
      when(runtime.documentFieldOps()).thenReturn(fields);

      assertFalse(settle(queue, runtime, B));
      assertEquals(retained, queue.listSwitchBufferOpsStrict(), "nothing is acknowledged");
    }
  }

  // ---- F1: the mixed path applies the same root supersession, and no other relaxation ----

  @Test
  void mixedSnapshotAcknowledgesADeleteWhoseFileALaterRootSyncRestored() throws Exception {
    Path archive = Files.createDirectories(temp.resolve("archive"));
    String restored = archive.resolve("restored.txt").toAbsolutePath().toString();
    var later = projectionAt("later", 1, "notes", file("notes/later.md"));
    try (var queue = queue(); var runtime = luceneRuntime()) {
      indexFile(runtime, restored);
      index(runtime, later);
      delete(queue, B, restored);
      syncRoot(queue, B, archive, true);
      queue.admitProjectionForGeneration(B, later.encode());

      assertTrue(settle(queue, runtime, B, List.of(SOURCE)));
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
      assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(restored), "B keeps the restored file");
    }
  }

  @Test
  void mixedSnapshotStillRefusesADeleteNoLaterRootSyncCovers() throws Exception {
    Path archive = Files.createDirectories(temp.resolve("archive"));
    String uncovered = file("uncovered.txt");
    var later = projectionAt("later", 1, "notes", file("notes/later.md"));
    try (var queue = queue(); var runtime = luceneRuntime()) {
      indexFile(runtime, uncovered);
      index(runtime, later);
      delete(queue, B, uncovered);
      syncRoot(queue, B, archive, true);
      queue.admitProjectionForGeneration(B, later.encode());
      var retained = queue.listSwitchBufferOpsStrict();

      assertFalse(settle(queue, runtime, B, List.of(SOURCE)));
      assertEquals(retained, queue.listSwitchBufferOpsStrict(), "nothing is acknowledged");
    }
  }

  // ---- B's selected receipts are acknowledged together, so a retry never sees one alone ----

  @Test
  void partialAcknowledgementLeavesEverySelectedReceiptForTheRetry() throws Exception {
    Path archive = Files.createDirectories(temp.resolve("archive"));
    String restored = archive.resolve("restored.txt").toAbsolutePath().toString();
    try (var queue = queue()) {
      delete(queue, B, restored);
      syncRoot(queue, B, archive, true);
      var retained = queue.listSwitchBufferOpsStrict();
      var runtime = fileRuntime();
      refuseDeleteRemoval(true);

      assertFalse(settle(queue, runtime, B), "the DELETE's removal was refused");
      assertEquals(retained, queue.listSwitchBufferOpsStrict(), "the SYNC_ROOT that supersedes it stays too");
      refuseDeleteRemoval(false);
      assertTrue(settle(queue, runtime, B));
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
      verify(runtime.indexingCoordinator(), never()).deleteByIdAndChunks(restored);
    }
  }

  @Test
  void partialMixedAcknowledgementLeavesEverySelectedReceiptForTheRetry() throws Exception {
    Path archive = Files.createDirectories(temp.resolve("archive"));
    String restored = archive.resolve("restored.txt").toAbsolutePath().toString();
    var later = projectionAt("later", 1, "notes", file("notes/later.md"));
    try (var queue = queue(); var runtime = luceneRuntime()) {
      indexFile(runtime, restored);
      index(runtime, later);
      delete(queue, B, restored);
      syncRoot(queue, B, archive, true);
      queue.admitProjectionForGeneration(B, later.encode());
      var retained = queue.listSwitchBufferOpsStrict();
      refuseDeleteRemoval(true);

      assertFalse(settle(queue, runtime, B, List.of(SOURCE)), "the DELETE's removal was refused");
      assertEquals(retained, queue.listSwitchBufferOpsStrict(), "the SYNC_ROOT that supersedes it stays too");
      refuseDeleteRemoval(false);
      assertTrue(settle(queue, runtime, B, List.of(SOURCE)));
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
      assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(restored), "B keeps the restored file");
    }
  }

  @Test
  void partialFileAcknowledgementLeavesEverySelectedReceiptForTheRetry() throws Exception {
    String first = file("first.txt");
    String second = file("second.txt");
    try (var queue = queue()) {
      delete(queue, B, first);
      delete(queue, B, second);
      var retained = queue.listSwitchBufferOpsStrict();
      var runtime = fileRuntime();
      refuseRemoval(true, "OLD.key = 'path:" + second + "'");

      assertFalse(settle(queue, runtime, B), "the second DELETE's removal was refused");
      assertEquals(retained, queue.listSwitchBufferOpsStrict(), "the first DELETE stays too");
      refuseRemoval(false, null);
      assertTrue(settle(queue, runtime, B));
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void partialMixedFileAcknowledgementLeavesEverySelectedReceiptForTheRetry() throws Exception {
    String gone = file("gone.txt");
    var later = projectionAt("later", 1, "notes", file("notes/later.md"));
    try (var queue = queue(); var runtime = luceneRuntime()) {
      index(runtime, later);
      delete(queue, B, gone);
      queue.admitProjectionForGeneration(B, later.encode());
      var retained = queue.listSwitchBufferOpsStrict();
      refuseDeleteRemoval(true);

      assertFalse(settle(queue, runtime, B, List.of(SOURCE)), "the DELETE's removal was refused");
      assertEquals(retained, queue.listSwitchBufferOpsStrict(), "the projection receipt stays too");
      refuseDeleteRemoval(false);
      assertTrue(settle(queue, runtime, B, List.of(SOURCE)));
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
    }
  }

  // ---- S7: a broad-delete snapshot with a SYNC_ROOT is still refused when B disagrees ----

  @Test
  void unexpectedDocumentInDeletedPrefixStillRefusesASnapshotWithSyncRoot() throws Exception {
    String prefix = QueryFilterBuilder.normalizePathPrefix(temp.resolve("removed-root").toString());
    var survivor = projectionAt("survivor", 2, "notes", prefix + "still-here.md");
    try (var queue = queue(); var runtime = luceneRuntime()) {
      index(runtime, survivor);
      syncRoot(queue, B, temp.resolve("watched"), false);
      prefixDelete(queue, prefix);
      var retained = queue.listSwitchBufferOpsStrict();

      assertFalse(settle(queue, runtime, B, List.of(SOURCE)));
      assertEquals(retained, queue.listSwitchBufferOpsStrict(), "nothing is acknowledged");
      assertEquals(survivor.indexId(), runtime.documentFieldOps()
          .getDocumentFieldOrThrow(survivor.indexId(), SchemaFields.DOC_ID), "nothing is deleted");
    }
  }

  // ---- P3: refusals through the real settlement entry point ----

  @Test
  void unknownReceiptKindBesideSyncRootRefusesAndRemovesNothing() throws Exception {
    try (var queue = queue()) {
      syncRoot(queue, B, temp, false);
      queue.putSwitchBufferForGeneration(B, "vdu:doc", "VDU_MARK_PROCESSING", "{\"doc_id\":\"doc\"}");
      var retained = queue.listSwitchBufferOpsStrict();
      var runtime = fileRuntime();

      assertFalse(settle(queue, runtime, B));
      assertEquals(retained, queue.listSwitchBufferOpsStrict());
      verify(runtime.indexingCoordinator(), never()).deleteByIdAndChunks(anyString());
    }
  }

  @Test
  void syncRootKeyThatDoesNotNameItsPayloadRootIsRefused() throws Exception {
    String watched = QueryFilterBuilder.normalizePathPrefix(temp.resolve("watched").toString());
    String elsewhere = QueryFilterBuilder.normalizePathPrefix(temp.resolve("elsewhere").toString());
    try (var queue = queue()) {
      queue.putSwitchBufferForGeneration(B, "sync_root:" + elsewhere, "SYNC_ROOT",
          new SwitchBufferSyncRoot(watched, true, null).encode());
      delete(queue, B, file("gone.txt"));
      var retained = queue.listSwitchBufferOpsStrict();
      var runtime = fileRuntime();

      assertFalse(settle(queue, runtime, B));
      assertEquals(retained, queue.listSwitchBufferOpsStrict());
      verify(runtime.indexingCoordinator(), never()).deleteByIdAndChunks(anyString());
    }
  }

  @Test
  void malformedSyncRootPayloadsAreRefused() throws Exception {
    String root = QueryFilterBuilder.normalizePathPrefix(temp.toString());
    for (String payload : List.of("not json", "{\"root_path\":\"relative/root\"}",
        "{\"version\":2,\"root_path\":\"" + root + "\",\"force\":true,\"originator\":null,\"transport\":null}",
        "{\"root_path\":\"" + root + "\",\"originator\":\"user\",\"transport\":\"ui\"}",
        "{\"root_path\":\"" + root + "\",\"force\":\"yes\"}")) {
      try (var queue = queue(Files.createTempDirectory(temp, "malformed"))) {
        queue.putSwitchBufferForGeneration(B, "sync_root:" + root, "SYNC_ROOT", payload);
        var retained = queue.listSwitchBufferOpsStrict();

        assertFalse(settle(queue, fileRuntime(), B), payload);
        assertEquals(retained, queue.listSwitchBufferOpsStrict(), payload);
      }
    }
  }

  @Test
  void receiptsScopedToAnotherGenerationAreNeitherReplayedNorRemoved() throws Exception {
    try (var queue = queue()) {
      String ownDelete = file("own.txt");
      String otherDelete = file("other.txt");
      delete(queue, B, ownDelete);
      syncRoot(queue, OTHER, temp, true);
      delete(queue, OTHER, otherDelete);
      var other = queue.listSwitchBufferOpsStrict().stream()
          .filter(op -> OTHER.equals(op.generation())).toList();
      var runtime = fileRuntime();

      assertTrue(settle(queue, runtime, B));
      assertEquals(other, queue.listSwitchBufferOpsStrict());
      verify(runtime.indexingCoordinator()).deleteByIdAndChunks(ownDelete);
      verify(runtime.indexingCoordinator(), never()).deleteByIdAndChunks(otherDelete);
    }
  }

  @Test
  void recordedGenerationIdentityStillRefusesTheMixedPathWithSyncRoot() throws Exception {
    try (var queue = queue(); var runtime = luceneRuntime()) {
      syncRoot(queue, RECORDED, temp, true);
      queue.putSwitchBufferForGeneration(RECORDED, "collection:archive", "DELETE_COLLECTION", "archive");
      var retained = queue.listSwitchBufferOpsStrict();

      assertFalse(settle(queue, runtime, RECORDED, List.of(SOURCE)));
      assertEquals(retained, queue.listSwitchBufferOpsStrict());
    }
  }

  // ---- fixtures ----

  private SqliteJobQueue queue() throws Exception {
    return queue(temp);
  }

  private static SqliteJobQueue queue(Path folder) throws Exception {
    var queue = new SqliteJobQueue(folder.resolve("jobs.db"));
    queue.open();
    return queue;
  }

  /** The receipt IngestSwitchBufferOps.bufferSyncDirectoryDuringSwitching journals. */
  private static void syncRoot(SqliteJobQueue queue, String generation, Path root, boolean force) {
    syncRoot(queue, generation, root, force, null);
  }

  private static void syncRoot(SqliteJobQueue queue, String generation, Path root, boolean force,
      JobQueue.EnqueueProvenance provenance) {
    String normalized = QueryFilterBuilder.normalizePathPrefix(root.toAbsolutePath().toString());
    assertTrue(queue.putSyncRootForGeneration(generation, "sync_root:" + normalized,
        new SwitchBufferSyncRoot(normalized, force, provenance)));
  }

  /** The receipt IngestSwitchBufferOps.journalDeletePrefixForGeneration journals. */
  private static void prefixDelete(SqliteJobQueue queue, String prefix) {
    assertTrue(queue.putSwitchBufferForGeneration(B, "prefix:" + prefix, "DELETE_PREFIX",
        new PathPrefixScope(prefix, List.of()).encode()));
  }

  /** The receipt IngestSwitchBufferOps.journalDeleteForGeneration journals. */
  private static void delete(SqliteJobQueue queue, String generation, String path) {
    assertTrue(queue.putSwitchBufferForGeneration(generation, "path:" + path, "DELETE",
        new SwitchBufferDelete(path, SwitchBufferDelete.Origin.EXPLICIT, null).encode()));
  }

  /** While refused, SQLite ignores the removal of any DELETE receipt, as a vanished row would be. */
  private void refuseDeleteRemoval(boolean refused) throws Exception {
    refuseRemoval(refused, "OLD.op = 'DELETE'");
  }

  /** While refused, SQLite ignores the removal of every receipt the condition selects. */
  private void refuseRemoval(boolean refused, String condition) throws Exception {
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
        var statement = connection.createStatement()) {
      statement.execute(refused ? "CREATE TRIGGER refuse_delete_removal BEFORE DELETE ON switch_buffer"
          + " WHEN " + condition + " BEGIN SELECT RAISE(IGNORE); END" : "DROP TRIGGER refuse_delete_removal");
    }
  }

  private String file(String leaf) {
    return temp.resolve(leaf).toAbsolutePath().toString();
  }

  private static boolean settle(SqliteJobQueue queue, RunningRuntime runtime, String generation) {
    return settle(queue, runtime, generation, List.of());
  }

  private static boolean settle(SqliteJobQueue queue, RunningRuntime runtime, String generation,
      List<String> sources) {
    return KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(queue, runtime, generation,
        sources, LoggerFactory.getLogger(CommittedNativeSyncRootTest.class));
  }

  /** Exact file receipts touch B only through deletion, commit and the absence count. */
  private static RunningRuntime fileRuntime() throws Exception {
    var runtime = mock(RunningRuntime.class);
    var commits = mock(CommitOps.class);
    var counts = mock(IndexCountOps.class);
    var indexing = mock(IndexingCoordinator.class);
    when(runtime.commitOps()).thenReturn(commits);
    when(runtime.indexCountOps()).thenReturn(counts);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(counts.countByIdAndChunksStrict(any())).thenReturn(0);
    return runtime;
  }

  private RunningRuntime luceneRuntime() {
    var fields = new ArrayList<>(FieldCatalogDef.forTesting(4).fields());
    fields.add(FieldCatalogDef.forChunkTesting(4).field(SchemaFields.COLLECTION));
    return withTestExecutors(IndexSchema.fromCatalog(new FieldCatalogDef("sync-root-test", fields))
        .ephemeral()).open();
  }

  private static void index(RunningRuntime runtime, AcceptedProjection projection) {
    runtime.commitOps().stopCommitTimer();
    runtime.indexingCoordinator().indexSingle(ProjectionDocumentMapper.toIndexDocument(projection));
    runtime.commitOps().maybeRefreshBlocking();
  }

  /** A file document B holds at {@code path}, as an UPSERT's job would have indexed it. */
  private static void indexFile(RunningRuntime runtime, String path) {
    var fields = new java.util.HashMap<>(ProjectionDocumentMapper.toIndexDocument(
        projectionAt("file", 1, "archive", path)).fields());
    fields.put(SchemaFields.DOC_ID, path);
    runtime.commitOps().stopCommitTimer();
    runtime.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(fields));
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static AcceptedProjection projectionAt(String id, long revision, String collection,
      String path) {
    return new AcceptedProjection(SOURCE, id, revision, AcceptedProjection.Kind.UPSERT,
        "{\"collection\":\"" + collection + "\",\"content\":\"projected body\",\"path\":\""
            + path.replace("\\", "\\\\") + "\"}");
  }
}
