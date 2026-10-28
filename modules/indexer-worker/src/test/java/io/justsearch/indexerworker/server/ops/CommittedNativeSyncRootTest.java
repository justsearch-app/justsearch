/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.PathPrefixScope;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.configuration.FieldCatalogDef;
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
 * A native boot after a pointer-before-acknowledgement stop settles B's retained SYNC_ROOT
 * receipt with the rest of B's snapshot, through the real settlement entry point and a real
 * journal: acknowledged exactly, never walked again, and never a reason to accept anything the
 * existing certification refuses.
 */
final class CommittedNativeSyncRootTest extends LuceneExecutorTestBase {
  private static final String B = "g-20261028-000000";
  private static final String OTHER = "g-20261028-000001";
  private static final String RECORDED = "g-018f0000-0000-7000-8000-000000000001";
  private static final String SOURCE = "memory";

  @TempDir Path temp;

  // ---- S4b: an interrupted settlement keeps retryable evidence ----

  @Test
  void failedDeleteCommitKeepsEveryReceiptAndTheRetrySettlesThem() throws Exception {
    try (var queue = queue()) {
      String gone = file("gone.txt");
      syncRoot(queue, B, temp, true);
      delete(queue, B, gone);
      var retained = queue.listSwitchBufferOpsStrict();
      var runtime = fileRuntime();
      var commits = runtime.commitOps();
      doThrow(new IllegalStateException("injected commit failure")).doNothing()
          .when(commits).commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);

      assertFalse(settle(queue, runtime, B), "a failed commit cannot acknowledge B's receipts");
      assertEquals(retained, queue.listSwitchBufferOpsStrict(), "every receipt stays for the retry");

      assertTrue(settle(queue, runtime, B), "the retry settles the same receipts");
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
      verify(runtime.indexingCoordinator(), org.mockito.Mockito.times(2)).deleteByIdAndChunks(gone);
    }
  }

  @Test
  void failedReceiptRemovalKeepsEveryReceiptAndTheRetrySettlesThem() throws Exception {
    try (var real = queue()) {
      var queue = spy(real);
      syncRoot(queue, B, temp, false);
      delete(queue, B, file("gone.txt"));
      var retained = queue.listSwitchBufferOpsStrict();
      doThrow(new IllegalStateException("injected removal failure")).doCallRealMethod()
          .when(queue).removeReplayedSwitchBufferOps(anyList());

      assertFalse(settle(queue, fileRuntime(), B));
      assertEquals(retained, queue.listSwitchBufferOpsStrict());

      assertTrue(settle(queue, fileRuntime(), B));
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void replacementRootRevisionAcceptedDuringRemovalSurvivesAndSettlesNext() throws Exception {
    try (var real = queue()) {
      var queue = spy(real);
      syncRoot(queue, B, temp, false);
      String unrelated = file("unrelated-backlog.txt");
      Files.writeString(Path.of(unrelated), "pending");
      assertEquals(1, queue.enqueue(List.of(Path.of(unrelated))), "unrelated backlog stays pending");
      var original = queue.listSwitchBufferOpsStrict().get(0);
      doAnswer(invocation -> {
        syncRoot(real, B, temp, true);
        return invocation.callRealMethod();
      }).doCallRealMethod().when(queue).removeReplayedSwitchBufferOps(anyList());

      assertFalse(settle(queue, fileRuntime(), B), "a newer revision is not acknowledged unseen");
      var remaining = queue.listSwitchBufferOpsStrict();
      assertEquals(1, remaining.size());
      assertEquals(original.key(), remaining.get(0).key());
      assertFalse(original.revision().equals(remaining.get(0).revision()),
          "the replacement revision survives the exact cleanup");
      assertTrue(SwitchBufferSyncRoot.decode(remaining.get(0).payload()).force());

      assertTrue(settle(queue, fileRuntime(), B), "the next attempt settles the replacement");
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
    }
  }

  // ---- S7: mixed certification still owns projection and broad-delete receipts ----

  @Test
  void syncRootBesideProjectionAndBroadDeletesCertifiesWithoutDestructiveReplay() throws Exception {
    String prefix = QueryFilterBuilder.normalizePathPrefix(temp.resolve("removed-root").toString());
    // Accepted after both broad deletes and inside both scopes: replaying either would remove it.
    var later = projectionAt("later", 3, "archive", prefix + "later.md");
    try (var queue = queue(); var runtime = luceneRuntime()) {
      index(runtime, later);
      queue.putSwitchBufferForGeneration(B, sourceKey(), "PROJECTION_SOURCE", SOURCE);
      prefixDelete(queue, prefix);
      syncRoot(queue, B, temp.resolve("watched"), true);
      queue.putSwitchBufferForGeneration(B, "collection:archive", "DELETE_COLLECTION", "archive");
      queue.admitProjectionForGeneration(B, later.encode());
      assertEquals(List.of("PROJECTION_SOURCE", "DELETE_PREFIX", "SYNC_ROOT", "DELETE_COLLECTION",
          "PROJECTION"), queue.listSwitchBufferOpsStrict().stream().map(op -> op.op()).toList());

      assertTrue(settle(queue, runtime, B, List.of(SOURCE)));
      assertEquals(List.of(), queue.listSwitchBufferOpsStrict());
      assertEquals(later.indexId(), runtime.documentFieldOps()
          .getDocumentFieldOrThrow(later.indexId(), SchemaFields.DOC_ID),
          "the later accepted projection is certified, not deleted by a replayed broad delete");
    }
  }

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
    String normalized = QueryFilterBuilder.normalizePathPrefix(root.toAbsolutePath().toString());
    assertTrue(queue.putSyncRootForGeneration(generation, "sync_root:" + normalized,
        new SwitchBufferSyncRoot(normalized, force, null)));
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

  private static AcceptedProjection projectionAt(String id, long revision, String collection,
      String path) {
    return new AcceptedProjection(SOURCE, id, revision, AcceptedProjection.Kind.UPSERT,
        "{\"collection\":\"" + collection + "\",\"content\":\"projected body\",\"path\":\""
            + path.replace("\\", "\\\\") + "\"}");
  }

  private static String sourceKey() {
    return "projection-source:" + SOURCE.length() + ":" + SOURCE;
  }
}
