/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByCollectionRequest;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.PathMapping;
import io.justsearch.ipc.UpdatePathsRequest;
import io.justsearch.ipc.UpdatePathsResponse;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WorkerIngestService document identity")
final class WorkerIngestServiceDocumentIdentityTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;

  private SqliteJobQueue jobQueue;
  private SqliteDocumentIdentityStore identityStore;
  private RunningRuntime runtime;

  @Test
  @DisplayName("watched and direct deletions during a build remove serving documents")
  void deletionsDuringMigrationRemoveServingDocuments() throws Exception {
    Path base = tempDir.resolve("generation-base");
    var manager = new IndexGenerationManager(base);
    String activeId = manager.initializeOrLoad().activeGenerationId();
    String buildingId = manager.startMigration("watcher-delete-regression").building_generation();
    jobQueue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    jobQueue.open();
    var schema = io.justsearch.adapters.lucene.runtime.IndexSchema
        .fromCatalog(FieldCatalogDef.forChunkTesting(0));
    runtime = schema.atPath(manager.resolveGenerationPathStrict(activeId))
        .withExecutorRegistrations(testLuceneExecutors()).open();
    try (RunningRuntime candidate = schema.atPath(manager.resolveGenerationPathStrict(buildingId))
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      String path = PathNormalizer.normalizeKey(tempDir.resolve("removed.txt"));
      String directPath = PathNormalizer.normalizeKey(tempDir.resolve("directly-removed.txt"));
      String prefix = PathNormalizer.normalizeKey(tempDir.resolve("prefix-delete"));
      String prefixedPath = PathNormalizer.normalizeKey(
          tempDir.resolve("prefix-delete").resolve("removed.txt"));
      String collectionPath = PathNormalizer.normalizeKey(tempDir.resolve("collection-removed.txt"));
      IndexDocument document = new IndexDocument(Map.of(
          SchemaFields.DOC_ID, path, SchemaFields.DOC_UID,
          "00000000-0000-4000-8000-000000000078", SchemaFields.PATH, path,
          SchemaFields.CONTENT, "watcherdeletebeforepromotion"));
      IndexDocument directDocument = new IndexDocument(Map.of(
          SchemaFields.DOC_ID, directPath, SchemaFields.DOC_UID,
          "00000000-0000-4000-8000-000000000079", SchemaFields.PATH, directPath,
          SchemaFields.CONTENT, "directdeletebeforepromotion"));
      IndexDocument prefixedDocument = new IndexDocument(Map.of(
          SchemaFields.DOC_ID, prefixedPath, SchemaFields.DOC_UID,
          "00000000-0000-4000-8000-000000000080", SchemaFields.PATH, prefixedPath,
          SchemaFields.CONTENT, "prefixdeletebeforepromotion"));
      IndexDocument collectionDocument = new IndexDocument(Map.of(
          SchemaFields.DOC_ID, collectionPath, SchemaFields.DOC_UID,
          "00000000-0000-4000-8000-000000000082", SchemaFields.PATH, collectionPath,
          SchemaFields.CONTENT, "collectiondeletebeforepromotion",
          SchemaFields.COLLECTION, "migration-collection"));
      runtime.indexingCoordinator().indexSingle(document);
      runtime.indexingCoordinator().indexSingle(directDocument);
      runtime.indexingCoordinator().indexSingle(prefixedDocument);
      runtime.indexingCoordinator().indexSingle(collectionDocument);
      candidate.indexingCoordinator().indexSingle(document);
      candidate.indexingCoordinator().indexSingle(directDocument);
      candidate.indexingCoordinator().indexSingle(prefixedDocument);
      candidate.indexingCoordinator().indexSingle(collectionDocument);
      runtime.commitOps().commitAndTrack();
      candidate.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("watcherdeletebeforepromotion",
          runtime.documentFieldOps().getDocumentField(path, SchemaFields.CONTENT));

      var service = new WorkerIngestService(
          jobQueue, null, null, IndexingPacing.unthrottled(), base,
          manager.resolveGenerationPathStrict(buildingId), candidate, runtime, null, 0L);
      service.acceptWatcherDelete(path,
          () -> candidate.indexingCoordinator().deleteByIdAndChunks(path));
      DeleteByIdResponse directResult = service.deleteById(
          DeleteByIdRequest.newBuilder().setDocId(directPath).build(), CallContext.none());
      assertTrue(directResult.getSuccess(), directResult.getError());
      var prefixResult = service.deleteByPath(
          DeleteByPathRequest.newBuilder().setPath(prefix).build(), CallContext.none());
      assertTrue(prefixResult.getError().isEmpty(), prefixResult.getError());
      var collectionResult = service.deleteByCollection(
          DeleteByCollectionRequest.newBuilder().setCollection("migration-collection").build(),
          CallContext.none());
      assertTrue(collectionResult.getError().isEmpty(), collectionResult.getError());

      runtime.commitOps().maybeRefreshBlocking();
      assertNull(runtime.documentFieldOps().getDocumentField(path, SchemaFields.CONTENT),
          "the accepted watcher deletion must remove serving A before B is promoted");
      assertNull(runtime.documentFieldOps().getDocumentField(directPath, SchemaFields.CONTENT),
          "the accepted direct deletion must remove serving A before B is promoted");
      assertNull(runtime.documentFieldOps().getDocumentField(prefixedPath, SchemaFields.CONTENT),
          "the accepted prefix deletion must remove serving A before B is promoted");
      assertNull(runtime.documentFieldOps().getDocumentField(collectionPath, SchemaFields.CONTENT),
          "the accepted collection deletion must remove serving A before B is promoted");
      var journal = jobQueue.listSwitchBufferOpsStrictForGeneration(buildingId);
      assertEquals(4, journal.size(), "all candidate replay records must remain durable");
      assertEquals(Set.of(path, directPath, IngestResponses.resolveNormalizedPathPrefix(prefix),
          "migration-collection"),
          journal.stream()
          .map(io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp::payload)
          .collect(java.util.stream.Collectors.toSet()));
      assertEquals(Set.of("DELETE", "DELETE_PREFIX", "DELETE_COLLECTION"), journal.stream()
          .map(io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp::op)
          .collect(java.util.stream.Collectors.toSet()));

      String refusedPath = PathNormalizer.normalizeKey(tempDir.resolve("refused-delete.txt"));
      IndexDocument refusedDocument = new IndexDocument(Map.of(
          SchemaFields.DOC_ID, refusedPath, SchemaFields.DOC_UID,
          "00000000-0000-4000-8000-000000000081", SchemaFields.PATH, refusedPath,
          SchemaFields.CONTENT, "retainedafterjournalfailure"));
      runtime.indexingCoordinator().indexSingle(refusedDocument);
      candidate.indexingCoordinator().indexSingle(refusedDocument);
      runtime.commitOps().commitAndTrack();
      candidate.commitOps().commitAndTrack();
      try (var db = java.sql.DriverManager.getConnection(
          "jdbc:sqlite:" + tempDir.resolve("jobs.db")); var statement = db.createStatement()) {
        statement.execute("DROP TABLE switch_buffer");
      }
      assertThrows(WorkerServiceException.class, () -> service.deleteById(
          DeleteByIdRequest.newBuilder().setDocId(refusedPath).build(), CallContext.none()));
      runtime.commitOps().maybeRefreshBlocking();
      candidate.commitOps().maybeRefreshBlocking();
      assertEquals("retainedafterjournalfailure",
          runtime.documentFieldOps().getDocumentField(refusedPath, SchemaFields.CONTENT));
      assertEquals("retainedafterjournalfailure",
          candidate.documentFieldOps().getDocumentField(refusedPath, SchemaFields.CONTENT));
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    if (identityStore != null) {
      identityStore.close();
    }
    if (runtime != null) {
      runtime.close();
    }
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  @DisplayName("rename rekeys the store and preserves parent and chunk uids")
  void renameRekeysStoreAndPreservesEveryUid() throws Exception {
    openStoresAndRuntime();

    String oldPath = PathNormalizer.normalizeKey(tempDir.resolve("old-report.pdf"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("renamed-report.pdf"));
    String oldHash = DocumentIdentityStore.pathHash(oldPath);
    String newHash = DocumentIdentityStore.pathHash(newPath);
    String parentUid = "00000000-0000-4000-8000-000000000077";
    String parentContent = "alpha beta";
    identityStore.importExisting(oldHash, parentUid, 10L);

    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID,
                    oldPath,
                    SchemaFields.DOC_UID,
                    parentUid,
                    SchemaFields.PATH,
                    oldPath,
                    SchemaFields.FILENAME,
                    "old-report.pdf",
                    SchemaFields.CONTENT,
                    parentContent)));
    int[] chunkStarts = {0, 6};
    int[] chunkEnds = {5, parentContent.length()};
    for (int i = 0; i < 2; i++) {
      Map<String, Object> chunk = new java.util.HashMap<>();
      chunk.put(SchemaFields.DOC_ID, "chunk:test-" + i);
      chunk.put(SchemaFields.DOC_UID, parentUid + "#" + i);
      chunk.put(SchemaFields.PATH, oldPath);
      chunk.put(SchemaFields.IS_CHUNK, "true");
      chunk.put(SchemaFields.PARENT_DOC_ID, oldPath);
      chunk.put(SchemaFields.CHUNK_INDEX, i);
      chunk.put(SchemaFields.CHUNK_TOTAL, 2);
      chunk.put(SchemaFields.CHUNK_START_CHAR, chunkStarts[i]);
      chunk.put(SchemaFields.CHUNK_END_CHAR, chunkEnds[i]);
      chunk.put(SchemaFields.CHUNK_CONTENT, parentContent.substring(chunkStarts[i], chunkEnds[i]));
      // Tempdoc 931 §C.1 — the rename RMWs each chunk, and the chunk_content re-slice refuses any
      // parent revision but the one the chunk was cut from. ChunkDocumentWriter stamps this in
      // production; this fixture hand-builds its chunks, so it stamps it too.
      chunk.put(
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256,
          io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(parentContent));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    WorkerIngestService service =
        new WorkerIngestService(
            jobQueue,
            null,
            null,
            IndexingPacing.unthrottled(),
            tempDir.resolve("index-base"),
            tempDir.resolve("index"),
            runtime,
            runtime,
            null,
            0L);
    service.setDocumentIdentityStore(identityStore);

    UpdatePathsResponse result = rename(service, oldPath, newPath);

    assertNotNull(result);
    assertEquals(1, result.getUpdatedCount());
    assertTrue(result.getFailedPathsList().isEmpty());
    assertTrue(identityStore.lookup(oldHash).isEmpty());
    DocumentIdentityStore.Identity moved = identityStore.lookup(newHash).orElseThrow();
    assertEquals(parentUid, moved.docUid());
    assertEquals(10L, moved.firstSeenAtMs());

    runtime.commitOps().maybeRefreshBlocking();
    var searchResult =
        runtime
            .readPathOps()
            .search(
                new MatchAllDocsQuery(),
                10,
                Set.of(
                    SchemaFields.DOC_UID,
                    SchemaFields.PATH,
                    SchemaFields.PARENT_DOC_ID,
                    SchemaFields.IS_CHUNK),
                LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
                null);
    assertEquals(3, searchResult.hits().size());
    ArrayList<String> chunkUids = new ArrayList<>();
    for (var hit : searchResult.hits()) {
      assertEquals(newPath, hit.fields().get(SchemaFields.PATH));
      if ("true".equals(hit.fields().get(SchemaFields.IS_CHUNK))) {
        assertEquals(newPath, hit.fields().get(SchemaFields.PARENT_DOC_ID));
        chunkUids.add(hit.fields().get(SchemaFields.DOC_UID));
      } else {
        assertEquals(newPath, hit.docId());
        assertEquals(parentUid, hit.fields().get(SchemaFields.DOC_UID));
      }
    }
    assertEquals(Set.of(parentUid + "#0", parentUid + "#1"), Set.copyOf(chunkUids));
    assertFalse(chunkUids.isEmpty());

    UpdatePathsResponse replay = rename(service, oldPath, newPath);
    assertNotNull(replay);
    assertEquals(1, replay.getUpdatedCount(), "a lost-response retry must converge");
    assertTrue(replay.getFailedPathsList().isEmpty());
    assertEquals(parentUid, identityStore.lookup(newHash).orElseThrow().docUid());
  }

  @Test
  @DisplayName("delete followed by same-path reindex preserves the uid")
  void deleteAndReindexPreservesUid() throws Exception {
    openStoresAndRuntime();
    String path = PathNormalizer.normalizeKey(tempDir.resolve("delete-reindex.txt"));
    String hash = DocumentIdentityStore.pathHash(path);

    DocumentIdentityStore.Identity first = identityStore.resolve(hash, 20L);
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID,
                    path,
                    SchemaFields.DOC_UID,
                    first.docUid(),
                    SchemaFields.PATH,
                    path,
                    SchemaFields.CONTENT,
                    "first version")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    DeleteByIdResponse deleted = delete(service(runtime), path);
    assertNotNull(deleted);
    assertTrue(deleted.getSuccess(), deleted.getError());
    runtime.commitOps().maybeRefreshBlocking();
    assertTrue(
        runtime
            .readPathOps()
            .search(
                new MatchAllDocsQuery(),
                10,
                Set.of(SchemaFields.DOC_UID),
                LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
                null)
            .hits()
            .isEmpty());

    // Tempdoc 931 §C.6 — the deleteById RPC is an EXCLUDE-rule / policy removal, not evidence that
    // the file is gone (ExcludesServiceImpl is its only caller), so it must not start the deletion
    // grace clock. If it did, an exclude-then-unexclude cycle across the window would silently
    // re-mint the uid and orphan the document's feedback.
    assertNull(
        identityStore.lookup(hash).orElseThrow().deletedAtMs(),
        "a policy-driven index removal must not establish a confirmed deletion");

    DocumentIdentityStore.Identity afterDelete = identityStore.resolve(hash, 30L);
    assertEquals(first.docUid(), afterDelete.docUid());
    assertEquals(first.firstSeenAtMs(), afterDelete.firstSeenAtMs());
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID,
                    path,
                    SchemaFields.DOC_UID,
                    afterDelete.docUid(),
                    SchemaFields.PATH,
                    path,
                    SchemaFields.CONTENT,
                    "second version")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var reindexed =
        runtime
            .readPathOps()
            .search(
                new MatchAllDocsQuery(),
                10,
                Set.of(SchemaFields.DOC_UID),
                LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
                null);
    assertEquals(1, reindexed.hits().size());
    assertEquals(first.docUid(), reindexed.hits().getFirst().fields().get(SchemaFields.DOC_UID));
  }

  @Test
  @DisplayName("an identity-only move succeeds while Green has no Lucene document")
  void identityOnlyMoveIsReportedAsSuccess() throws Exception {
    openStoresAndRuntime();
    String oldPath = PathNormalizer.normalizeKey(tempDir.resolve("blue-only.txt"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("blue-only-renamed.txt"));
    String uid = "00000000-0000-4000-8000-000000000099";
    identityStore.importExisting(DocumentIdentityStore.pathHash(oldPath), uid, 40L);
    WorkerIngestService service = service(runtime);

    UpdatePathsResponse result = rename(service, oldPath, newPath);

    assertNotNull(result);
    assertEquals(1, result.getUpdatedCount());
    assertTrue(result.getFailedPathsList().isEmpty());
    assertTrue(identityStore.lookup(DocumentIdentityStore.pathHash(oldPath)).isEmpty());
    assertEquals(
        uid,
        identityStore.lookup(DocumentIdentityStore.pathHash(newPath)).orElseThrow().docUid());
  }

  @Test
  @DisplayName("blank rename paths fail without mutating identity authority")
  void blankRenamePathsFailBeforeNormalization() throws Exception {
    openStoresAndRuntime();
    String oldPath = PathNormalizer.normalizeKey(tempDir.resolve("blank-guard-old.txt"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("blank-guard-new.txt"));
    String uid = "00000000-0000-4000-8000-000000000101";
    String oldHash = DocumentIdentityStore.pathHash(oldPath);
    identityStore.importExisting(oldHash, uid, 60L);
    WorkerIngestService service = service(runtime);

    UpdatePathsResponse blankOld = rename(service, "   ", newPath);
    assertEquals(0, blankOld.getUpdatedCount());
    assertEquals(Set.of("   "), Set.copyOf(blankOld.getFailedPathsList()));

    UpdatePathsResponse blankNew = rename(service, oldPath, "   ");
    assertEquals(0, blankNew.getUpdatedCount());
    assertEquals(Set.of(oldPath), Set.copyOf(blankNew.getFailedPathsList()));
    assertEquals(uid, identityStore.lookup(oldHash).orElseThrow().docUid());
    assertTrue(identityStore.lookup(DocumentIdentityStore.pathHash(newPath)).isEmpty());
  }

  @Test
  @DisplayName("a Lucene failure is reported while the store keeps the non-reminted identity")
  void luceneFailureLeavesStoreReadyForRetry() throws Exception {
    openStoresAndRuntime();
    String oldPath = PathNormalizer.normalizeKey(tempDir.resolve("retry-old.txt"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("retry-new.txt"));
    String uid = "00000000-0000-4000-8000-000000000100";
    identityStore.importExisting(DocumentIdentityStore.pathHash(oldPath), uid, 50L);
    WorkerIngestService service = service(runtime);
    runtime.close();
    runtime = null;

    WorkerServiceException error = renameExpectingError(service, oldPath, newPath);

    assertEquals(
        WorkerServiceException.Status.INTERNAL,
        error.status(),
        "the RPC must report the incomplete Lucene half");
    assertTrue(
        error.getMessage().startsWith("Failed to update document paths: "),
        "unexpected failure message: " + error.getMessage());
    assertTrue(identityStore.lookup(DocumentIdentityStore.pathHash(oldPath)).isEmpty());
    assertEquals(
        uid,
        identityStore.lookup(DocumentIdentityStore.pathHash(newPath)).orElseThrow().docUid());
  }

  @Test
  @DisplayName("rename is refused during cutover before either identity or Lucene mutates")
  void renameFailsClosedDuringSwitchingBeforeMutation() throws Exception {
    openStoresAndRuntime();
    String oldPath = PathNormalizer.normalizeKey(tempDir.resolve("switching-old.txt"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("switching-new.txt"));
    String oldHash = DocumentIdentityStore.pathHash(oldPath);
    String newHash = DocumentIdentityStore.pathHash(newPath);
    String uid = "00000000-0000-4000-8000-000000000102";
    identityStore.importExisting(oldHash, uid, 70L);
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID,
                    oldPath,
                    SchemaFields.DOC_UID,
                    uid,
                    SchemaFields.PATH,
                    oldPath,
                    SchemaFields.CONTENT,
                    "cutover-safe rename")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    WorkerIngestService service = switchingService(runtime);
    WorkerServiceException error = renameExpectingError(service, oldPath, newPath);

    assertEquals(WorkerServiceException.Status.UNAVAILABLE, error.status());
    assertEquals("Migration is switching; retry shortly", error.getMessage());
    assertEquals(uid, identityStore.lookup(oldHash).orElseThrow().docUid());
    assertTrue(identityStore.lookup(newHash).isEmpty());
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(uid, runtime.documentFieldOps().getDocumentField(oldPath, SchemaFields.DOC_UID));
    assertEquals(null, runtime.documentFieldOps().getDocumentField(newPath, SchemaFields.DOC_UID));
  }

  private void openStoresAndRuntime() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
    identityStore = new SqliteDocumentIdentityStore(dbPath);
    runtime =
        io.justsearch.adapters.lucene.runtime.IndexSchema
            .fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(tempDir.resolve("lucene")).withExecutorRegistrations(testLuceneExecutors())
            .open();
  }

  private WorkerIngestService service(RunningRuntime activeRuntime) {
    WorkerIngestService service =
        new WorkerIngestService(
            jobQueue,
            null,
            null,
            IndexingPacing.unthrottled(),
            tempDir.resolve("index-base"),
            tempDir.resolve("index"),
            activeRuntime,
            activeRuntime,
            null,
            0L);
    service.setDocumentIdentityStore(identityStore);
    return service;
  }

  private WorkerIngestService switchingService(RunningRuntime activeRuntime) throws Exception {
    Path indexBase = Files.createDirectories(tempDir.resolve("switching-index-base"));
    Files.writeString(
        indexBase.resolve("state.json"),
        """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-building",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """
            .formatted(System.currentTimeMillis()));
    Path ingestPath = Files.createDirectories(indexBase.resolve("indices").resolve("g-building"));
    WorkerIngestService service =
        new WorkerIngestService(
            jobQueue,
            null,
            null,
            IndexingPacing.unthrottled(),
            indexBase,
            ingestPath,
            activeRuntime,
            activeRuntime,
            null,
            0L);
    service.setDocumentIdentityStore(identityStore);
    return service;
  }

  private static UpdatePathsRequest renameRequest(String oldPath, String newPath) {
    return UpdatePathsRequest.newBuilder()
        .addMappings(PathMapping.newBuilder().setOldPath(oldPath).setNewPath(newPath).build())
        .build();
  }

  private static UpdatePathsResponse rename(
      WorkerIngestService service, String oldPath, String newPath) {
    return service.updateDocumentPaths(renameRequest(oldPath, newPath), CallContext.none());
  }

  private static WorkerServiceException renameExpectingError(
      WorkerIngestService service, String oldPath, String newPath) {
    return assertThrows(
        WorkerServiceException.class,
        () -> service.updateDocumentPaths(renameRequest(oldPath, newPath), CallContext.none()));
  }

  private static DeleteByIdResponse delete(WorkerIngestService service, String docId) {
    return service.deleteById(
        DeleteByIdRequest.newBuilder().setDocId(docId).build(), CallContext.none());
  }
}
