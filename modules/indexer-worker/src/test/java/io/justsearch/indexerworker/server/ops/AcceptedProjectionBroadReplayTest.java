/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.services.ProjectionDocumentMapper;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Real SQLite and Lucene proofs for ordered broad projection replay. */
final class AcceptedProjectionBroadReplayTest extends LuceneExecutorTestBase {
  private static final String GENERATION = "green";
  private static final String SOURCE = "memory";

  @TempDir Path tempDir;

  @Test
  void projectionThenCollectionDeleteIsAppliedAndDeletedBeforeExactCleanup() throws Exception {
    var projection = projection("collection-delete", 1, "notes", null, "collection body");
    var collectionDelete = op("collection:notes", "DELETE_COLLECTION", "notes");

    try (var queue = openQueue("collection-forward.db"); var runtime = runtime()) {
      assertTrue(put(queue, projectionOp(projection)));
      assertTrue(put(queue, collectionDelete));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "PROJECTION", "DELETE_COLLECTION");
      var prepared = prepare(queue, runtime);
      assertTrue(prepared.isPresent());
      var replay = prepared.orElseThrow();

      assertFalse(hasDocument(runtime, projection.indexId()));
      assertEquals(expected, replay.versions());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void projectionThenPrefixDeleteIsAppliedAndDeletedBeforeExactCleanup() throws Exception {
    String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
        .normalizePathPrefix(tempDir.resolve("prefix-forward").toString());
    String path = prefix + "document.md";
    var projection = projection("prefix-delete", 2, "notes", path, "prefix body");
    var prefixDelete = op("prefix:" + prefix, "DELETE_PREFIX", prefix);

    try (var queue = openQueue("prefix-forward.db"); var runtime = runtime()) {
      assertTrue(put(queue, projectionOp(projection)));
      assertTrue(put(queue, prefixDelete));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "PROJECTION", "DELETE_PREFIX");
      var prepared = prepare(queue, runtime);
      assertTrue(prepared.isPresent());
      var replay = prepared.orElseThrow();

      assertFalse(hasDocument(runtime, projection.indexId()));
      assertEquals(expected, replay.versions());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void collectionDeleteThenLaterProjectionRemainsVisibleWithStrictWitnesses() throws Exception {
    var projection = projection("collection-inverse", 3, "notes", null, "later collection body");
    var collectionDelete = op("collection:notes", "DELETE_COLLECTION", "notes");
    var laterProjection = projectionOp(projection);

    try (var queue = openQueue("collection-inverse.db"); var runtime = runtime()) {
      assertTrue(put(queue, collectionDelete));
      assertTrue(put(queue, laterProjection));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "DELETE_COLLECTION", "PROJECTION");
      var prepared = prepare(queue, runtime);
      assertTrue(prepared.isPresent());
      var replay = prepared.orElseThrow();

      assertEquals(projection.indexId(), runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.DOC_ID));
      assertEquals(SOURCE, runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
      assertEquals("3", runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
      assertEquals(projection.fieldsDigest(), runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.PROJECTION_DIGEST));
      assertEquals(expected, replay.versions());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void prefixDeleteThenLaterProjectionRemainsVisibleWithStrictWitnesses() throws Exception {
    String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
        .normalizePathPrefix(tempDir.resolve("prefix-inverse").toString());
    String path = prefix + "document.md";
    var projection = projection("prefix-inverse", 4, "notes", path, "later prefix body");
    var prefixDelete = op("prefix:" + prefix, "DELETE_PREFIX", prefix);
    var laterProjection = projectionOp(projection);

    try (var queue = openQueue("prefix-inverse.db"); var runtime = runtime()) {
      assertTrue(put(queue, prefixDelete));
      assertTrue(put(queue, laterProjection));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "DELETE_PREFIX", "PROJECTION");
      var prepared = prepare(queue, runtime);
      assertTrue(prepared.isPresent());
      var replay = prepared.orElseThrow();

      assertEquals(projection.indexId(), runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.DOC_ID));
      assertEquals(SOURCE, runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
      assertEquals("4", runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
      assertEquals(projection.fieldsDigest(), runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.PROJECTION_DIGEST));
      assertEquals(expected, replay.versions());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void projectionThenExactDeleteIsAppliedAndDeletedBeforeExactCleanup() throws Exception {
    var projection = projection("exact-forward", 5, "notes", null, "exact body");
    var projectionRow = projectionOp(projection);
    var delete = op("path:" + projection.indexId(), "DELETE", projection.indexId());

    try (var queue = openQueue("exact-forward.db"); var runtime = runtime()) {
      assertTrue(put(queue, projectionRow));
      assertTrue(put(queue, delete));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "PROJECTION", "DELETE");
      var prepared = prepare(queue, runtime);
      assertTrue(prepared.isPresent());
      var replay = prepared.orElseThrow();

      assertFalse(hasDocument(runtime, projection.indexId()));
      assertEquals(expected, replay.versions());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void exactDeleteThenLaterProjectionPreservesItsExactWitness() throws Exception {
    var projection = projection("exact-inverse", 6, "notes", null, "later exact body");
    var delete = op("path:" + projection.indexId(), "DELETE", projection.indexId());
    var projectionRow = projectionOp(projection);

    try (var queue = openQueue("exact-inverse.db"); var runtime = runtime()) {
      assertTrue(put(queue, delete));
      assertTrue(put(queue, projectionRow));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "DELETE", "PROJECTION");
      var prepared = prepare(queue, runtime);
      assertTrue(prepared.isPresent());
      var replay = prepared.orElseThrow();

      assertProjectionWitness(runtime, projection);
      assertEquals(expected, replay.versions());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void nativeCertificateMatchesLaterBroadDeleteAndRemovesExactRows() throws Exception {
    var projection = projection("strict-fields", 7, "notes", null, "strict body");
    var collectionDelete = op("collection:notes", "DELETE_COLLECTION", "notes");
    var projectionRow = projectionOp(projection);

    try (var queue = openQueue("strict-fields.db"); var runtime = runtime()) {
      index(runtime, projection);
      assertTrue(put(queue, collectionDelete));
      assertTrue(put(queue, projectionRow));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "DELETE_COLLECTION", "PROJECTION");

      assertTrue(KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(
          queue, runtime, GENERATION, List.of(SOURCE),
          LoggerFactory.getLogger(AcceptedProjectionBroadReplayTest.class)));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
      assertProjectionWitness(runtime, projection);
    }
  }

  @Test
  void genericPrepareRetainsAllRowsWhenStrictProjectionReadFails() throws Exception {
    var projection = projection("strict-fields-failure", 8, "notes", null, "strict failure body");
    var collectionDelete = op("collection:notes", "DELETE_COLLECTION", "notes");
    var projectionRow = projectionOp(projection);

    try (var queue = openQueue("strict-fields-failure.db"); var original = runtime()) {
      assertTrue(put(queue, projectionRow));
      assertTrue(put(queue, collectionDelete));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "PROJECTION", "DELETE_COLLECTION");

      var fields = spy(original.documentFieldOps());
      doThrow(new IOException("strict projection reader unavailable"))
          .when(fields).getDocumentFieldOrThrow(anyString(), anyString());
      var runtime = spy(original);
      doReturn(fields).when(runtime).documentFieldOps();

      var prepared = prepare(queue, runtime);
      assertTrue(prepared.isEmpty());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  @Test
  void genericPrepareRetainsNewerPhysicalProjectionWhenStrictPreflightReadFails()
      throws Exception {
    var indexed = projection("newer-physical", 12, "notes", null, "newer physical body");
    var retained = projection("newer-physical", 11, "notes", null, "older receipt");

    try (var queue = openQueue("newer-physical.db"); var original = runtime()) {
      index(original, indexed);
      assertTrue(put(queue, projectionOp(retained)));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "PROJECTION");

      var fields = spy(original.documentFieldOps());
      doThrow(new IOException("strict projection preflight unavailable"))
          .when(fields).getDocumentFieldOrThrow(anyString(), anyString());
      var runtime = spy(original);
      doReturn(fields).when(runtime).documentFieldOps();

      var prepared = prepare(queue, runtime);
      assertTrue(prepared.isEmpty());
      assertEquals("12", original.documentFieldOps().getDocumentFieldOrThrow(
          indexed.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
      assertEquals(indexed.fieldsDigest(), original.documentFieldOps().getDocumentFieldOrThrow(
          indexed.indexId(), SchemaFields.PROJECTION_DIGEST));
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  @Test
  void malformedLaterExactPrefixAndCollectionReceiptsRetainRowsAfterPhysicalDeletion()
      throws Exception {
    assertMalformedLaterReceipt("malformed-delete.db", "DELETE", "path:not-the-projection-id",
        null);
    String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
        .normalizePathPrefix(tempDir.resolve("malformed-prefix").toString());
    assertMalformedLaterReceipt("malformed-prefix.db", "DELETE_PREFIX", "prefix:not-the-prefix",
        prefix);
    assertMalformedLaterReceipt("malformed-collection.db", "DELETE_COLLECTION",
        "collection:not-notes", "notes");
  }

  @Test
  void missingStoredIdentityCannotAuthorizeOverwritingAnIndexedProjection() throws Exception {
    var indexed = projection("missing-stored-identity", 12, "notes", null, "newer body");
    var retained = projection("missing-stored-identity", 11, "notes", null, "older receipt");
    for (boolean hideAllIdentityFields : List.of(false, true)) {
      try (var queue = openQueue("missing-stored-identity-" + hideAllIdentityFields + ".db");
          var original = runtime()) {
        index(original, indexed);
        assertEquals(1, original.indexCountOps().countByIdAndChunksStrict(indexed.indexId()));
        assertTrue(put(queue, projectionOp(retained)));
        var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
        assertKinds(expected, "PROJECTION");
        var fields = spy(original.documentFieldOps());
        doReturn(null).when(fields).getDocumentFieldOrThrow(indexed.indexId(), SchemaFields.DOC_ID);
        if (hideAllIdentityFields) {
          doReturn(null).when(fields).getDocumentFieldOrThrow(
              indexed.indexId(), SchemaFields.PROJECTION_SOURCE_ID);
          doReturn(null).when(fields).getDocumentFieldOrThrow(
              indexed.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION);
        }
        var runtime = spy(original);
        doReturn(fields).when(runtime).documentFieldOps();
        assertTrue(prepare(queue, runtime).isEmpty());
        assertProjectionWitness(original, indexed);
        assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
      }
    }
  }

  @Test
  void fileOnlyExactDeleteRetainsReceiptWhenStrictAbsenceCountIsUnreadable() throws Exception {
    var projection = projection("strict-exact-absence", 13, "notes", null, "deleted body");
    var deletion = op("path:" + projection.indexId(), "DELETE", projection.indexId());
    try (var queue = openQueue("strict-exact-absence.db"); var original = runtime()) {
      index(original, projection);
      assertProjectionWitness(original, projection);
      assertTrue(put(queue, deletion));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "DELETE");
      var counts = spy(original.indexCountOps());
      doThrow(new IOException("strict absence query unavailable"))
          .when(counts).countByIdAndChunksStrict(projection.indexId());
      var runtime = spy(original);
      doReturn(counts).when(runtime).indexCountOps();
      assertFalse(KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(
          queue, runtime, GENERATION, List.of(),
          LoggerFactory.getLogger(AcceptedProjectionBroadReplayTest.class)));
      assertFalse(hasDocument(original, projection.indexId()),
          "the exact delete applied, but unreadable strict absence cannot clear its receipt");
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
      assertEquals(0, original.indexCountOps().countByIdAndChunksStrict(projection.indexId()));
      assertTrue(KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(
          queue, original, GENERATION, List.of(),
          LoggerFactory.getLogger(AcceptedProjectionBroadReplayTest.class)));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void approvedLaterCollectionDeleteCannotExplainAbsentProjection() throws Exception {
    var projection = projection("approved-absent", 9, "notes", null, "missing body");
    var collectionDelete = op("collection:notes", "DELETE_COLLECTION", "notes");

    try (var queue = openQueue("approved-absent.db"); var original = runtime()) {
      var indexing = spy(original.indexingCoordinator());
      doNothing().when(indexing).indexSingle(org.mockito.ArgumentMatchers.any(
          io.justsearch.indexing.api.IndexDocument.class));
      var runtime = spy(original);
      doReturn(indexing).when(runtime).indexingCoordinator();
      assertTrue(put(queue, projectionOp(projection)));
      assertTrue(put(queue, collectionDelete));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "PROJECTION", "DELETE_COLLECTION");
      var prepared = prepare(queue, runtime, Set.of(expected.get(1)));
      assertTrue(prepared.isEmpty());

      assertFalse(hasDocument(runtime, projection.indexId()));
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  @Test
  void approvedLaterCollectionDeleteCannotInvalidatePresentProjection() throws Exception {
    var projection = projection("approved-present", 10, "notes", null, "approved body");
    var collectionDelete = op("collection:notes", "DELETE_COLLECTION", "notes");

    try (var queue = openQueue("approved-present.db"); var runtime = runtime()) {
      assertTrue(put(queue, projectionOp(projection)));
      assertTrue(put(queue, collectionDelete));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "PROJECTION", "DELETE_COLLECTION");
      var prepared = prepare(queue, runtime, Set.of(expected.get(1)));
      assertTrue(prepared.isPresent());
      var replay = prepared.orElseThrow();

      assertProjectionWitness(runtime, projection);
      assertEquals(expected, replay.versions());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  private SqliteJobQueue openQueue(String name) throws Exception {
    var queue = new SqliteJobQueue(tempDir.resolve(name));
    queue.open();
    return queue;
  }

  private RunningRuntime runtime() {
    return withTestExecutors(IndexSchema.fromCatalog(projectionCatalog()).ephemeral()).open();
  }

  private static FieldCatalogDef projectionCatalog() {
    var fields = new ArrayList<>(FieldCatalogDef.forTesting(4).fields());
    fields.add(FieldCatalogDef.forChunkTesting(4).field(SchemaFields.COLLECTION));
    return new FieldCatalogDef("projection-broad-replay-test", fields);
  }

  private static boolean put(SqliteJobQueue queue, SwitchBufferCapableQueue.SwitchBufferOp op) {
    return queue.putSwitchBufferForGeneration(
        GENERATION, op.key(), op.op(), op.payload());
  }

  private static Optional<KnowledgeServerMigrationOps.StrictReplay> prepare(
      SqliteJobQueue queue, RunningRuntime runtime) {
    return prepare(queue, runtime, Set.of());
  }

  private static Optional<KnowledgeServerMigrationOps.StrictReplay> prepare(
      SqliteJobQueue queue, RunningRuntime runtime,
      Set<SwitchBufferCapableQueue.SwitchBufferOp> approvedGapVersions) {
    var context = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
        queue, runtime, null, IndexingPacing.unthrottled(), Path.of("."), Path.of("."),
        new ObjectMapper(), () -> false, () -> true,
        LoggerFactory.getLogger(AcceptedProjectionBroadReplayTest.class), Long.MAX_VALUE,
        GENERATION, ignored -> true, approvedGapVersions);
    return KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(context);
  }

  private void assertMalformedLaterReceipt(String database, String kind, String key,
      String payload) throws Exception {
    String path = payload == null ? null : payload + "document.md";
    var projection = projection("malformed-" + kind, 11, "notes", path, "protected body");
    var malformed = op(key, kind, payload == null ? projection.indexId() : payload);

    try (var queue = openQueue(database); var runtime = runtime()) {
      index(runtime, projection);
      assertProjectionWitness(runtime, projection);
      assertTrue(put(queue, projectionOp(projection)));
      assertTrue(put(queue, malformed));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "PROJECTION", kind);

      var prepared = prepare(queue, runtime);
      assertTrue(prepared.isEmpty());
      assertFalse(hasDocument(runtime, projection.indexId()),
          "replay applied the payload, but an invalid receipt key cannot certify its effect");
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  private static void assertKinds(
      List<SwitchBufferCapableQueue.SwitchBufferOp> rows, String... expected) {
    assertEquals(List.of(expected), rows.stream()
        .map(SwitchBufferCapableQueue.SwitchBufferOp::op).toList());
  }

  private static void index(RunningRuntime runtime, AcceptedProjection projection) {
    runtime.commitOps().stopCommitTimer();
    runtime.indexingCoordinator().indexSingle(ProjectionDocumentMapper.toIndexDocument(projection));
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static void assertProjectionWitness(RunningRuntime runtime,
      AcceptedProjection projection) throws IOException {
    assertEquals(projection.indexId(), runtime.documentFieldOps().getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.DOC_ID));
    assertEquals(SOURCE, runtime.documentFieldOps().getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
    assertEquals(Long.toString(projection.sourceRevision()),
        runtime.documentFieldOps().getDocumentFieldOrThrow(
            projection.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
    assertEquals(projection.fieldsDigest(), runtime.documentFieldOps().getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PROJECTION_DIGEST));
  }

  private static AcceptedProjection projection(String id, long revision, String collection,
      String path, String content) {
    String pathField = path == null ? "" : ",\"path\":\"" + json(path) + "\"";
    return new AcceptedProjection(SOURCE, id, revision, AcceptedProjection.Kind.UPSERT,
        "{\"collection\":\"" + json(collection) + "\",\"content\":\""
            + json(content) + "\"" + pathField + "}");
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp projectionOp(
      AcceptedProjection projection) {
    return op(projection.journalKey(), "PROJECTION", projection.encode());
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp op(
      String key, String kind, String payload) {
    return new SwitchBufferCapableQueue.SwitchBufferOp(
        GENERATION, key, kind, payload, System.currentTimeMillis(),
        Long.toString(System.nanoTime()));
  }

  private static boolean hasDocument(RunningRuntime runtime, String documentId) throws IOException {
    return documentId.equals(runtime.documentFieldOps().getDocumentFieldOrThrow(
        documentId, SchemaFields.DOC_ID));
  }

  private static String json(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
