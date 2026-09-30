/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.services.ProjectionDocumentMapper;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Physical-reader regressions for post-pointer native projection receipt certification. */
final class CommittedNativeProjectionEffectsTest extends LuceneExecutorTestBase {
  private static final String GENERATION = "green";
  private static final String SOURCE = "memory";
  private static final String UUID_V7_GENERATION = "g-018f0000-0000-7000-8000-000000000001";
  private static final String LOGGER_NAME = CommittedNativeProjectionEffectsTest.class.getName();

  @Test
  void ownedSourceMarkerUsesExactMembershipAndCleansItsProjectionReceipt() throws Exception {
    var projection = projection(SOURCE, "marker", 4, "notes", "marker body");
    try (var runtime = runtime()) {
      index(runtime, projection);
      assertProjection(runtime, projection);
      var marker = op(sourceKey(SOURCE), "PROJECTION_SOURCE", SOURCE, 1);
      var row = projectionOp(projection, 2);
      var queue = queue(List.of(marker, row));

      assertTrue(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      assertProjection(runtime, projection);
      verify(queue).removeReplayedSwitchBufferOps(List.of(marker, row));
    }
  }

  @Test
  void missingRetainedSourceMarkerIsAllowedForAnOwnedCommittedSource() throws Exception {
    var projection = projection(SOURCE, "marker-missing", 5, "notes", "already committed");
    try (var runtime = runtime()) {
      index(runtime, projection);
      assertProjection(runtime, projection);
      var row = projectionOp(projection, 1);
      var queue = queue(List.of(row));

      assertTrue(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      assertProjection(runtime, projection);
      verify(queue).removeReplayedSwitchBufferOps(List.of(row));
    }
  }

  @Test
  void wrongSourceOrMarkerKeyRefusesTheWholeSnapshot() throws Exception {
    var projection = projection(SOURCE, "wrong-marker", 1, "notes", "body");
    try (var runtime = runtime()) {
      index(runtime, projection);
      assertProjection(runtime, projection);
      var wrongSource = op(sourceKey("other"), "PROJECTION_SOURCE", SOURCE, 1);
      var queue = queue(List.of(wrongSource));
      assertFalse(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      verify(queue, never()).removeReplayedSwitchBufferOps(List.of(wrongSource));

      var wrongKey = op("projection-source:999:" + SOURCE, "PROJECTION_SOURCE", SOURCE, 1);
      var secondQueue = queue(List.of(wrongKey));
      assertFalse(settle(secondQueue, runtime, GENERATION, List.of(SOURCE)));
      verify(secondQueue, never()).removeReplayedSwitchBufferOps(List.of(wrongKey));
    }
  }

  @Test
  void exactDeleteFollowedByProjectionUpsertPreservesTheLaterProjection() throws Exception {
    var projection = projection(SOURCE, "delete-then-upsert", 6, "notes", "later projection");
    try (var runtime = runtime()) {
      index(runtime, projection);
      assertProjection(runtime, projection);
      var delete = op("path:" + projection.indexId(), "DELETE", projection.indexId(), 1);
      var upsert = projectionOp(projection, 2);
      var queue = queue(List.of(delete, upsert));

      assertTrue(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      assertProjection(runtime, projection);
      verify(queue).removeReplayedSwitchBufferOps(List.of(delete, upsert));
    }
  }

  @Test
  void projectionThenExactDeleteAcceptsAbsenceWithoutAFieldWitness() throws Exception {
    var projection = projection(SOURCE, "projection-delete", 7, "notes", "deleted");
    try (var runtime = runtime()) {
      var projectionRow = projectionOp(projection, 1);
      var delete = op("path:" + projection.indexId(), "DELETE", projection.indexId(), 2);
      var queue = queue(List.of(projectionRow, delete));

      assertTrue(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      assertFalse(hasDocument(runtime, projection.indexId()));
      verify(queue).removeReplayedSwitchBufferOps(List.of(projectionRow, delete));
    }
  }

  @Test
  void collectionDeleteThenLaterProjectionSurvivesWithExactMappedWitnesses() throws Exception {
    var indexed = projection(SOURCE, "collection-later", 12, "notes", "collection survivor");
    try (var runtime = runtime()) {
      index(runtime, indexed);
      assertProjection(runtime, indexed);
      var collectionDelete = op("collection:notes", "DELETE_COLLECTION", "notes", 1);
      var upsert = projectionOp(indexed, 2);
      var queue = queue(List.of(collectionDelete, upsert));

      assertTrue(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      assertProjection(runtime, indexed);
      assertTrue(runtime.documentFieldOps().queryDocIdsByFieldOrThrow(
          SchemaFields.COLLECTION, "notes", 10).contains(indexed.indexId()));
      verify(queue).removeReplayedSwitchBufferOps(List.of(collectionDelete, upsert));
    }
  }

  @Test
  void projectionThenCollectionDeleteAcceptsAnEmptyReader() throws Exception {
    var projection = projection(SOURCE, "collection-empty", 9, "notes", "gone");
    try (var runtime = runtime()) {
      var projectionRow = projectionOp(projection, 1);
      var collectionDelete = op("collection:notes", "DELETE_COLLECTION", "notes", 2);
      var queue = queue(List.of(projectionRow, collectionDelete));

      assertTrue(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      assertFalse(runtime.documentFieldOps().queryDocIdsByFieldOrThrow(
          SchemaFields.COLLECTION, "notes", 10).contains(projection.indexId()));
      verify(queue).removeReplayedSwitchBufferOps(List.of(projectionRow, collectionDelete));
    }
  }

  @Test
  void broadDeleteCannotUseAPathOrCollectionMismatchAsProof() throws Exception {
    String actualPath = tempPath("mapped-path");
    var projection = projectionWithPath(SOURCE, "mismatch", 10, "notes", actualPath,
        "unexpected survivor");
    try (var runtime = runtime()) {
      var mapped = new HashMap<>(ProjectionDocumentMapper.toIndexDocument(projection).fields());
      mapped.put(SchemaFields.COLLECTION, "other");
      runtime.commitOps().stopCommitTimer();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(mapped));
      runtime.commitOps().maybeRefreshBlocking();
      assertProjection(runtime, projection);
      assertTrue(runtime.documentFieldOps().queryDocIdsByFieldOrThrow(
          SchemaFields.PATH, actualPath, 10).contains(projection.indexId()));
      assertTrue(runtime.documentFieldOps().queryDocIdsByFieldOrThrow(
          SchemaFields.COLLECTION, "other", 10).contains(projection.indexId()));
      var collectionDelete = op("collection:notes", "DELETE_COLLECTION", "notes", 1);
      var upsert = projectionOp(projection, 2);
      var queue = queue(List.of(collectionDelete, upsert));

      assertFalse(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      verify(queue, never()).removeReplayedSwitchBufferOps(List.of(collectionDelete, upsert));
    }
  }

  @Test
  void newerProjectionRevisionIsAllowedOnlyForTheOwnedSourceOutsideBroadFence()
      throws Exception {
    var indexed = projection(SOURCE, "owned-revision", 11, "notes", "owned revision");
    var retained = projection(SOURCE, "owned-revision", 10, "notes", "older receipt");
    try (var runtime = runtime()) {
      index(runtime, indexed);
      assertProjection(runtime, indexed);
      var row = projectionOp(retained, 1);
      var queue = queue(List.of(row));

      assertTrue(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      assertProjection(runtime, indexed);
      verify(queue).removeReplayedSwitchBufferOps(List.of(row));
    }
  }

  @Test
  void newerProjectionRevisionCannotExcuseAProjectionSurvivorAfterBroadDelete() throws Exception {
    var indexed = projection(SOURCE, "broad-after-projection", 12, "notes", "must be gone");
    var retained = projection(SOURCE, "broad-after-projection", 11, "notes", "older receipt");
    try (var runtime = runtime()) {
      index(runtime, indexed);
      assertProjection(runtime, indexed);
      var collectionDelete = op("collection:notes", "DELETE_COLLECTION", "notes", 1);
      var projectionRow = projectionOp(retained, 2);
      var queue = queue(List.of(collectionDelete, projectionRow));

      assertFalse(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      assertProjection(runtime, indexed);
      verify(queue, never()).removeReplayedSwitchBufferOps(
          List.of(collectionDelete, projectionRow));
    }
  }

  @Test
  void recordedUuidV7GenerationFencesBroadNativeCertification() throws Exception {
    assertTrue(IndexGenerationManager.isRecordedGenerationIdentity(UUID_V7_GENERATION));
    try (var runtime = runtime()) {
      var delete = op(UUID_V7_GENERATION, "collection:notes", "DELETE_COLLECTION", "notes", 1);
      var queue = queue(List.of(delete));

      assertFalse(settle(queue, runtime, UUID_V7_GENERATION, List.of(SOURCE)));
      verify(queue, never()).removeReplayedSwitchBufferOps(List.of(delete));
    }
  }

  @Test
  void unsupportedVduReceiptRemainsFenced() throws Exception {
    try (var runtime = runtime()) {
      var vdu = op("vdu:projection", "VDU_MARK_PROCESSING",
          "{\"doc_id\":\"projection:6:memory\"}", 1);
      var queue = queue(List.of(vdu));

      assertFalse(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      verify(queue, never()).removeReplayedSwitchBufferOps(List.of(vdu));
    }
  }

  @Test
  void unsupportedPrunePrefixReceiptRemainsFencedWithOwnedProjectionEvidence() throws Exception {
    String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
        .normalizePathPrefix(tempPath("owned-prune-prefix"));
    var projection = projectionWithPath(SOURCE, "prune-prefix-owned", 4, "notes",
        prefix + "owned.md", "owned projection");
    var marker = op(sourceKey(SOURCE), "PROJECTION_SOURCE", SOURCE, 1);
    var row = projectionOp(projection, 2);
    var prune = op("prune_prefix:" + prefix, "PRUNE_PREFIX", prefix, 3);
    try (var runtime = runtime()) {
      index(runtime, projection);
      assertProjection(runtime, projection);
      var queue = queue(List.of(marker, row, prune));

      assertFalse(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      assertProjection(runtime, projection);
      verify(queue, never()).removeReplayedSwitchBufferOps(List.of(marker, row, prune));
    }
  }

  @Test
  void higherProjectionRevisionRequiresACompleteDigestWitness() throws Exception {
    var indexed = projection(SOURCE, "incomplete-newer", 12, "notes", "new seed");
    var retained = projection(SOURCE, "incomplete-newer", 11, "notes", "old receipt");
    for (String invalid : java.util.Arrays.asList(null, "invalid-digest")) {
      try (var runtime = runtime()) {
        var mapped = new HashMap<>(ProjectionDocumentMapper.toIndexDocument(indexed).fields());
        if (invalid == null) mapped.remove(SchemaFields.PROJECTION_DIGEST);
        else mapped.put(SchemaFields.PROJECTION_DIGEST, invalid);
        runtime.commitOps().stopCommitTimer();
        runtime.indexingCoordinator().indexSingle(new IndexDocument(mapped));
        runtime.commitOps().maybeRefreshBlocking();
        assertEquals(indexed.indexId(), runtime.documentFieldOps().getDocumentFieldOrThrow(
            indexed.indexId(), SchemaFields.DOC_ID));
        assertEquals("12", runtime.documentFieldOps().getDocumentFieldOrThrow(
            indexed.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
        assertEquals(invalid, runtime.documentFieldOps().getDocumentFieldOrThrow(
            indexed.indexId(), SchemaFields.PROJECTION_DIGEST));
        var row = projectionOp(retained, 1);
        var queue = queue(List.of(row));
        assertFalse(settle(queue, runtime, GENERATION, List.of(SOURCE)));
        verify(queue, never()).removeReplayedSwitchBufferOps(List.of(row));
      }
    }
  }

  @Test
  void protectedDefaultCollectionCannotCertifyAnUntaggedSurvivorAsDeleted() throws Exception {
    var projection = new AcceptedProjection(SOURCE, "untagged", 1,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"default bucket survives\"}");
    try (var runtime = runtime()) {
      index(runtime, projection);
      assertProjection(runtime, projection);
      assertEquals(null, runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.COLLECTION));
      var deletion = op("collection:default", "DELETE_COLLECTION", "default", 1);
      var queue = queue(List.of(deletion));
      assertFalse(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      assertProjection(runtime, projection);
      verify(queue, never()).removeReplayedSwitchBufferOps(List.of(deletion));
    }
  }

  @Test
  void consistentUnownedSourceAndProjectionReceiptsRefuseCleanup() throws Exception {
    var unowned = projection("other", "unowned", 1, "notes", "not in manifest");
    try (var runtime = runtime()) {
      index(runtime, unowned);
      assertProjection(runtime, unowned);
      var marker = op(sourceKey("other"), "PROJECTION_SOURCE", "other", 1);
      var row = projectionOp(unowned, 2);
      var queue = queue(List.of(marker, row));
      assertFalse(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      verify(queue, never()).removeReplayedSwitchBufferOps(List.of(marker, row));
      var projectionOnly = queue(List.of(row));
      assertFalse(settle(projectionOnly, runtime, GENERATION, List.of(SOURCE)));
      verify(projectionOnly, never()).removeReplayedSwitchBufferOps(List.of(row));
    }
  }

  @Test
  void prefixSurvivorRequiresItsExactMappedPathEvenWhenItsDigestMatches() throws Exception {
    String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
        .normalizePathPrefix(tempPath("accepted-path"));
    var projection = projectionWithPath(SOURCE, "path-mismatch", 10, "notes",
        prefix + "document.md", "expected scope");
    try (var runtime = runtime()) {
      var mapped = new HashMap<>(ProjectionDocumentMapper.toIndexDocument(projection).fields());
      String different = tempPath("different-scope/document.md");
      mapped.put(SchemaFields.PATH, different);
      runtime.commitOps().stopCommitTimer();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(mapped));
      runtime.commitOps().maybeRefreshBlocking();
      assertProjection(runtime, projection);
      assertEquals(different, runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.PATH));
      var deletion = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
      var row = projectionOp(projection, 2);
      var queue = queue(List.of(deletion, row));
      assertFalse(settle(queue, runtime, GENERATION, List.of(SOURCE)));
      verify(queue, never()).removeReplayedSwitchBufferOps(List.of(deletion, row));
    }
  }

  @Test
  void higherProjectionRevisionRequiresCanonicalDecimalEvidence() throws Exception {
    var indexed = projection(SOURCE, "noncanonical-newer", 12, "notes", "new seed");
    var retained = projection(SOURCE, "noncanonical-newer", 11, "notes", "old receipt");
    for (String invalid : List.of("+12", "012")) {
      try (var runtime = runtime()) {
        var mapped = new HashMap<>(ProjectionDocumentMapper.toIndexDocument(indexed).fields());
        mapped.put(SchemaFields.PROJECTION_SOURCE_REVISION, invalid);
        runtime.commitOps().stopCommitTimer();
        runtime.indexingCoordinator().indexSingle(new IndexDocument(mapped));
        runtime.commitOps().maybeRefreshBlocking();
        assertEquals(indexed.fieldsDigest(), runtime.documentFieldOps().getDocumentFieldOrThrow(
            indexed.indexId(), SchemaFields.PROJECTION_DIGEST));
        assertEquals(invalid, runtime.documentFieldOps().getDocumentFieldOrThrow(
            indexed.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
        var row = projectionOp(retained, 1);
        var queue = queue(List.of(row));
        assertFalse(settle(queue, runtime, GENERATION, List.of(SOURCE)));
        verify(queue, never()).removeReplayedSwitchBufferOps(List.of(row));
      }
    }
  }

  private RunningRuntime runtime() {
    return withTestExecutors(IndexSchema.fromCatalog(projectionCatalog())
        .ephemeral()).open();
  }

  private static FieldCatalogDef projectionCatalog() {
    var fields = new ArrayList<>(FieldCatalogDef.forTesting(4).fields());
    fields.add(FieldCatalogDef.forChunkTesting(4).field(SchemaFields.COLLECTION));
    return new FieldCatalogDef("projection-test", fields);
  }

  private static void index(RunningRuntime runtime, AcceptedProjection projection) {
    runtime.commitOps().stopCommitTimer();
    runtime.indexingCoordinator().indexSingle(ProjectionDocumentMapper.toIndexDocument(projection));
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static void assertProjection(RunningRuntime runtime, AcceptedProjection projection)
      throws IOException {
    var fields = runtime.documentFieldOps();
    assertEquals(projection.indexId(), fields.getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.DOC_ID));
    assertEquals(projection.sourceId(), fields.getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
    assertEquals(Long.toString(projection.sourceRevision()), fields.getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
    assertEquals(projection.fieldsDigest(), fields.getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PROJECTION_DIGEST));
  }

  private static boolean hasDocument(RunningRuntime runtime, String id) throws IOException {
    return runtime.documentFieldOps().queryDocIdsByFieldOrThrow(SchemaFields.DOC_ID, id, 10)
        .contains(id);
  }

  private static boolean settle(SwitchBufferCapableQueue queue, RunningRuntime runtime,
      String generation, List<String> ownedSources) {
    return KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(
        queue, runtime, generation, ownedSources, LoggerFactory.getLogger(LOGGER_NAME));
  }

  private static SwitchBufferCapableQueue queue(
      List<SwitchBufferCapableQueue.SwitchBufferOp> retained) {
    var queue = mock(SwitchBufferCapableQueue.class);
    when(queue.listSwitchBufferOpsStrict()).thenReturn(retained, List.of());
    when(queue.removeReplayedSwitchBufferOps(retained)).thenReturn(retained.size());
    when(queue.hasNonterminalJobsByPathPrefixStrict(anyString())).thenReturn(false);
    when(queue.hasNonterminalJobsByCollectionStrict(anyString())).thenReturn(false);
    return queue;
  }

  private static AcceptedProjection projection(String source, String id, long revision,
      String collection, String content) {
    return new AcceptedProjection(source, id, revision, AcceptedProjection.Kind.UPSERT,
        "{\"collection\":\"" + collection + "\",\"content\":\"" + content + "\"}");
  }

  private static AcceptedProjection projectionWithPath(String source, String id, long revision,
      String collection, String path, String content) {
    return new AcceptedProjection(source, id, revision, AcceptedProjection.Kind.UPSERT,
        "{\"collection\":\"" + collection + "\",\"content\":\"" + content
            + "\",\"path\":\"" + path.replace("\\", "\\\\") + "\"}");
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp projectionOp(
      AcceptedProjection projection, long sequence) {
    return op(projection.journalKey(), "PROJECTION", projection.encode(), sequence);
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp op(
      String key, String kind, String payload, long sequence) {
    return op(GENERATION, key, kind, payload, sequence);
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp op(
      String generation, String key, String kind, String payload, long sequence) {
    return new SwitchBufferCapableQueue.SwitchBufferOp(
        generation, key, kind, payload, sequence, "v" + sequence);
  }

  private static String sourceKey(String source) {
    return "projection-source:" + source.length() + ":" + source;
  }

  private static String tempPath(String leaf) {
    return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), leaf)
        .toAbsolutePath().toString();
  }
}
