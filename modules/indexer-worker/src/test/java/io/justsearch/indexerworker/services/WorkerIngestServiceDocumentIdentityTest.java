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
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
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
