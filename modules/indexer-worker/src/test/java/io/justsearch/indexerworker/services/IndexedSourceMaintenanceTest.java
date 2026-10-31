/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.DeleteByCollectionRequest;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.extension.ExtendWith(NoVolumeIdentityExtension.class)
final class IndexedSourceMaintenanceTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  private RunningRuntime open(Path path) {
    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(path)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer(); return runtime;
  }

  private WorkerIngestService service(SqliteJobQueue queue, RunningRuntime runtime, Path base,
      DocumentIdentityStore identities) {
    var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null, IndexingPacing.unthrottled(),
        base, runtime.openedIndexPath(), runtime, runtime, null, 0L);
    service.setDocumentIdentityStore(identities); return service;
  }

  private String seed(RunningRuntime runtime, Path file, String uid) {
    String key = PathNormalizer.normalizeKey(file);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, key,
        SchemaFields.DOC_UID, uid, SchemaFields.PATH, key, SchemaFields.CONTENT, "source",
        SchemaFields.COLLECTION, "docs")));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, key + "#child",
        SchemaFields.DOC_UID, uid + "#0", SchemaFields.PATH, key, SchemaFields.PARENT_DOC_ID, key,
        SchemaFields.IS_CHUNK, "true", SchemaFields.COLLECTION, "docs")));
    runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking(); return key;
  }

  private PruneResponse reconcile(WorkerIngestService service) throws Exception {
    try { return (PruneResponse) WorkerIngestService.class.getMethod("reconcileIndexedSources", CallContext.class)
        .invoke(service, CallContext.none()); }
    catch (NoSuchMethodException missing) {
      // The baseline's ordinary maintenance has no corpus arm when no roots are watched.
      return PruneResponse.getDefaultInstance();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void D15CompletedAdHocDeletionAndExternalRenameReconcileAfterRestartWithNoWatchedRoots(boolean externalRename) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path file = Files.writeString(directory.resolve("source.txt"), "source");
    String key;
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      ((RootWitness) service.rootCheck()).recordDeliberateAdd(file, true);
      key = seed(runtime, file, "uid");
      if (externalRename) Files.move(file, directory.resolve("externally-renamed.txt"));
      else Files.delete(file);
    }
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      var response = reconcile(service);
      assertTrue(response.getError().isEmpty(), response.getError());
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(key));
    }
    try (var runtime = open(layout.activeGenerationPath())) {
      assertEquals(0, runtime.indexCountOps().docCount(), "removal is durable and the new path was not discovered implicitly");
    }
  }

  @Test void D15MaintenanceFinishesADeletionWhoseParentWasAlreadyRemoved() throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path file = Files.writeString(directory.resolve("source.txt"), "source");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      ((RootWitness) service.rootCheck()).recordDeliberateAdd(file, true);
      String key = seed(runtime, file, "uid"); Files.delete(file);
      runtime.indexingCoordinator().deleteById(key);
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(key));
      assertTrue(reconcile(service).getError().isEmpty());
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(key));
    }
  }

  @Test void D15UnprovenOrMissingParentFoldersKeepTheEntireFamily() throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path folder = Files.createDirectory(directory.resolve("folder"));
    Path file = Files.writeString(folder.resolve("source.txt"), "source");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      ((RootWitness) service.rootCheck()).recordDeliberateAdd(file, true);
      String key = seed(runtime, file, "uid"); Files.delete(file); Files.delete(folder);
      reconcile(service);
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(key));
      Files.createDirectory(folder); Files.writeString(file, "returned");
      reconcile(service); assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(key));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void D37CollectionRemovalCancelsPendingAndClaimedWorkPreservesOtherOwnersAndAllowsFreshIngestion(boolean overlap) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path claimed = Files.writeString(directory.resolve("claimed.txt"), "a");
    Path pending = Files.writeString(directory.resolve("pending.txt"), "b");
    Path other = overlap ? claimed : Files.writeString(directory.resolve("other.txt"), "c");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      String key = seed(runtime, claimed, "uid");
      queue.enqueue(List.of(claimed), "docs"); var claim = queue.pollPending(1).getFirst();
      queue.enqueue(List.of(pending), "docs"); queue.enqueue(List.of(other), "other");
      var result = service.deleteByCollection(DeleteByCollectionRequest.newBuilder().setCollection("docs").build(), CallContext.none());
      assertTrue(result.getError().isEmpty(), result.getError()); assertEquals(2, result.getDeletedDocs());
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(key));
      assertFalse(queue.ownsClaimForPublication(claim));
      var otherClaims = queue.pollPending(10);
      assertEquals(List.of(other), otherClaims.stream().map(JobQueue.IndexJob::path).toList());
      assertTrue(queue.ownsClaimForPublication(otherClaims.getFirst()));
      if (overlap) queue.returnUnfinishedClaims(otherClaims);
      queue.enqueue(List.of(claimed), "docs");
      assertTrue(queue.ownsClaimForPublication(queue.pollPending(1).getFirst()), "deliberate later work has fresh authority");
    }
  }

  @Test void D37RemovalCancelsAnAlreadyAcceptedEnumeratorButLaterExplicitScanCanPublish() throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path root = Files.createDirectory(directory.resolve("root"));
    for (int i = 0; i < 110; i++) Files.writeString(root.resolve("source-" + i + ".txt"), "text");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      var request = io.justsearch.ipc.ScanRootRequest.newBuilder().setRootPath(root.toString())
          .setRootAdded(true).setCollection("docs").build();
      var removed = new java.util.concurrent.atomic.AtomicBoolean();
      service.scanRoot(request, progress -> {
        if (!progress.getComplete() && removed.compareAndSet(false, true)) {
          var result = service.deleteByCollection(DeleteByCollectionRequest.newBuilder().setCollection("docs").build(), CallContext.none());
          assertTrue(result.getError().isEmpty(), result.getError());
        }
      }, CallContext.none());
      assertTrue(removed.get());
      assertEquals(0, queue.queueDepth(), "the already accepted walk cannot admit its buffered tail after removal");
      service.scanRoot(request, progress -> {}, CallContext.none());
      assertEquals(110, queue.queueDepth());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {999, 1000, 1001})
  void D39PruneStartsPersistentIdentityGraceWithoutResettingIt(int elapsed) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path root = Files.createDirectory(directory.resolve("root"));
    Path file = Files.writeString(root.resolve("gone.txt"), "source");
    String hash = DocumentIdentityStore.pathHash(PathNormalizer.normalizeKey(file)); String uid;
    long deletedAt;
    Path database = directory.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database); var identities = new SqliteDocumentIdentityStore(database, 1000);
        var runtime = open(layout.activeGenerationPath())) {
      queue.open(); uid = identities.resolve(hash, System.currentTimeMillis()).docUid();
      var service = service(queue, runtime, base, identities);
      ((RootWitness) service.rootCheck()).recordDeliberateAdd(root, false);
      seed(runtime, file, uid); Files.delete(file);
      var result = service.pruneMissing(PruneRequest.newBuilder().setPathPrefix(root.toString()).build(), CallContext.none());
      assertTrue(result.getError().isEmpty(), result.getError());
      assertNotNull(identities.lookup(hash).orElseThrow().deletedAtMs());
      deletedAt = identities.lookup(hash).orElseThrow().deletedAtMs();
      service.pruneMissing(PruneRequest.newBuilder().setPathPrefix(root.toString()).build(), CallContext.none());
      assertEquals(deletedAt, identities.lookup(hash).orElseThrow().deletedAtMs());
    }
    try (var identities = new SqliteDocumentIdentityStore(database, 1000)) {
      String replacement = identities.resolve(hash, deletedAt + elapsed).docUid();
      if (elapsed <= 1000) assertEquals(uid, replacement);
      else assertNotEquals(uid, replacement);
    }
  }

  @Test void C24FolderForgetRefusesProtectedLegacyOwnership() throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path target = Files.createDirectory(directory.resolve("ordinary"));
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      service.setResolvedConfigSupplier(() -> io.justsearch.configuration.resolved.ResolvedConfig.builder()
          .putDefault("justsearch.data.dir", directory.resolve("app-data").toString()).build());
      String key = PathNormalizer.normalizeKey(target.resolve("legacy"));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, key,
          SchemaFields.DOC_UID, "protected", SchemaFields.CONTENT, "managed", SchemaFields.COLLECTION, "agent-history")));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, "protected-child",
          SchemaFields.DOC_UID, "protected-child", SchemaFields.PARENT_DOC_ID, key,
          SchemaFields.IS_CHUNK, "true", SchemaFields.COLLECTION, "agent-history")));
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      var failure = assertThrows(WorkerServiceException.class, () -> service.validateForgetTarget(target,
          io.justsearch.configuration.excludes.LiteralExclusion.Scope.FOLDER));
      assertEquals(WorkerServiceException.Status.INVALID_ARGUMENT, failure.status());
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(key));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"collection", "exact", "prefix", "present"})
  void C26RemovalWithoutConfirmedAbsenceDoesNotStartIdentityGrace(String operation) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path file = Files.writeString(directory.resolve("source.txt"), "source");
    Path database = directory.resolve("jobs.db");
    String hash = DocumentIdentityStore.pathHash(PathNormalizer.normalizeKey(file));
    try (var queue = new SqliteJobQueue(database); var identities = new SqliteDocumentIdentityStore(database, 1000);
        var runtime = open(layout.activeGenerationPath())) {
      queue.open(); var identity = identities.resolve(hash, System.currentTimeMillis());
      var service = service(queue, runtime, base, identities); seed(runtime, file, identity.docUid());
      switch (operation) {
        case "collection" -> assertTrue(service.deleteByCollection(DeleteByCollectionRequest.newBuilder()
            .setCollection("docs").build(), CallContext.none()).getError().isEmpty());
        case "exact" -> assertTrue(service.deleteById(io.justsearch.ipc.DeleteByIdRequest.newBuilder()
            .setDocId(PathNormalizer.normalizeKey(file)).build(), CallContext.none()).getError().isEmpty());
        case "prefix" -> assertTrue(service.deleteByPath(io.justsearch.ipc.DeleteByPathRequest.newBuilder()
            .setPath(directory.toString()).build(), CallContext.none()).getError().isEmpty());
        case "present" -> assertTrue(reconcile(service).getError().isEmpty());
        default -> throw new AssertionError(operation);
      }
      assertNull(identities.lookup(hash).orElseThrow().deletedAtMs());
    }
    try (var identities = new SqliteDocumentIdentityStore(database, 1000)) {
      assertNull(identities.lookup(hash).orElseThrow().deletedAtMs());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void C11MaintenanceRechecksReturningOrRepublishedSourcesUnderTheFence(boolean republish) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path file = Files.writeString(directory.resolve("source.txt"), "source");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); var loop = mock(IndexingLoop.class);
      var service = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(),
          base, runtime.openedIndexPath(), runtime, runtime, null, 0L);
      Object owner = new Object(); service.setMutationAdmission(new WorkerMutationAdmission(owner), owner);
      ((RootWitness) service.rootCheck()).recordDeliberateAdd(file, true);
      String key = seed(runtime, file, "old"); Files.delete(file);
      doAnswer(call -> {
        if (republish) seed(runtime, file, "new");
        else Files.writeString(file, "returned");
        return ((java.util.function.Supplier<?>) call.getArgument(0)).get();
      }).when(loop).withFileMutationFence(any());
      assertTrue(reconcile(service).getError().isEmpty());
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(key));
      assertEquals(republish ? "new" : "old", runtime.documentFieldOps().getDocumentField(key, SchemaFields.DOC_UID));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"reader", "count", "commit"})
  void C37CollectionReadCountOrCommitFailureIsReportedAndRetryConverges(String failure) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path file = Files.writeString(directory.resolve("source.txt"), "source");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); seed(runtime, file, "uid");
      var observed = spy(runtime); var commits = spy(runtime.commitOps());
      doReturn(commits).when(observed).commitOps();
      var service = service(queue, observed, base, DocumentIdentityStore.UNAVAILABLE);
      var request = DeleteByCollectionRequest.newBuilder().setCollection("docs").build();
      if (failure.equals("reader")) {
        try (var readers = mockStatic(org.apache.lucene.index.DirectoryReader.class)) {
          readers.when(() -> org.apache.lucene.index.DirectoryReader.open(any(org.apache.lucene.index.IndexWriter.class)))
              .thenThrow(new java.io.IOException("injected count reader failure"));
          assertFalse(service.deleteByCollection(request, CallContext.none()).getError().isEmpty());
          readers.verify(() -> org.apache.lucene.index.DirectoryReader.open(any(org.apache.lucene.index.IndexWriter.class)));
        }
        assertEquals(2, runtime.indexCountOps().docCount());
      } else if (failure.equals("count")) {
        try (var searchers = mockConstruction(org.apache.lucene.search.IndexSearcher.class,
            (searcher, construction) -> when(searcher.count(any(org.apache.lucene.search.Query.class)))
                .thenThrow(new java.io.IOException("injected collection count failure")))) {
          assertFalse(service.deleteByCollection(request, CallContext.none()).getError().isEmpty());
          verify(searchers.constructed().getFirst()).count(any(org.apache.lucene.search.Query.class));
        }
        assertEquals(2, runtime.indexCountOps().docCount());
      } else {
        doThrow(new IllegalStateException("injected commit failure")).when(commits)
            .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.GRPC_DELETE_BY_COLLECTION);
        assertFalse(service.deleteByCollection(request, CallContext.none()).getError().isEmpty());
        doCallRealMethod().when(commits).commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.GRPC_DELETE_BY_COLLECTION);
      }
      assertTrue(service.deleteByCollection(request, CallContext.none()).getError().isEmpty());
      assertEquals(0, runtime.indexCountOps().docCount());
    }
  }


  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void C17RenameReplaysAfterIdentityRekeyAndReplacesDestinationPublishedBeforeTheFence(boolean failAfterRekey) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path source = Files.writeString(directory.resolve("source.txt"), "source");
    Path destination = directory.resolve("destination.txt");
    Path database = directory.resolve("jobs.db");
    String oldKey = PathNormalizer.normalizeKey(source), newKey = PathNormalizer.normalizeKey(destination);
    String oldHash = DocumentIdentityStore.pathHash(oldKey), newHash = DocumentIdentityStore.pathHash(newKey);
    String uid;
    try (var queue = new SqliteJobQueue(database); var identities = new SqliteDocumentIdentityStore(database, 1000);
        var runtime = open(layout.activeGenerationPath())) {
      queue.open(); uid = identities.resolve(oldHash, System.currentTimeMillis()).docUid();
      String key = seed(runtime, source, uid);
      runtime.indexingCoordinator().updateDocument(key + "#child", Map.of(SchemaFields.CHUNK_CONTENT, "source",
          SchemaFields.CHUNK_START_CHAR, "0", SchemaFields.CHUNK_END_CHAR, "6",
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256, io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex("source")));
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      var observed = spy(identities);
      if (failAfterRekey) doAnswer(call -> {
        call.callRealMethod(); throw new IllegalStateException("injected after durable identity rekey");
      }).when(observed).rekey(eq(oldHash), eq(newHash), anyLong());
      var loop = mock(IndexingLoop.class);
      doAnswer(call -> {
        // A different producer publishes the historic destination while the move waits to enter.
        seed(runtime, destination, "destination");
        return ((java.util.function.Supplier<?>) call.getArgument(0)).get();
      }).when(loop).withFileMutationFence(any());
      var service = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(),
          base, runtime.openedIndexPath(), runtime, runtime, null, 0L);
      service.setDocumentIdentityStore(observed);
      Object owner = new Object(); service.setMutationAdmission(new WorkerMutationAdmission(owner), owner);
      Files.move(source, destination);
      var request = io.justsearch.ipc.UpdatePathsRequest.newBuilder().addMappings(io.justsearch.ipc.PathMapping.newBuilder()
          .setOldPath(oldKey).setNewPath(newKey)).build();
      if (failAfterRekey) assertThrows(WorkerServiceException.class, () -> service.updateDocumentPaths(request, CallContext.none()));
      else {
        assertEquals(1, service.updateDocumentPaths(request, CallContext.none()).getUpdatedCount());
        assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(newKey));
        assertEquals(uid, runtime.documentFieldOps().getDocumentField(newKey, SchemaFields.DOC_UID));
      }
    }
    try (var queue = new SqliteJobQueue(database); var identities = new SqliteDocumentIdentityStore(database, 1000);
        var runtime = open(layout.activeGenerationPath())) {
      queue.open(); var service = service(queue, runtime, base, identities);
      var request = io.justsearch.ipc.UpdatePathsRequest.newBuilder().addMappings(io.justsearch.ipc.PathMapping.newBuilder()
          .setOldPath(oldKey).setNewPath(newKey)).build();
      var response = service.updateDocumentPaths(request, CallContext.none());
      if (failAfterRekey) assertEquals(1, response.getUpdatedCount());
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(oldKey));
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(newKey));
      assertEquals(uid, identities.lookup(newHash).orElseThrow().docUid());
      assertEquals(uid, runtime.documentFieldOps().getDocumentField(newKey, SchemaFields.DOC_UID));
      assertEquals("source", Files.readString(destination));
      assertEquals("source", runtime.documentFieldOps().getEnrichmentInputs(List.of(oldKey + "#child")).get(oldKey + "#child").content());
    }
  }

  @Test void C5CollectionRemovalSurvivesBufferedReplayAndQueueReopen() throws Exception {
    Path base = directory.resolve("base"); var manager = new IndexGenerationManager(base);
    var layout = manager.initializeOrLoad();
    String generation = manager.startMigration("collection-replay").building_generation();
    Path candidatePath = manager.resolveGenerationPathStrict(generation);
    Path file = Files.writeString(directory.resolve("source.txt"), "source");
    Path database = directory.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database); var a = open(layout.activeGenerationPath()); var b = open(candidatePath)) {
      queue.open(); seed(a, file, "serving"); seed(b, file, "candidate");
      for (var runtime : List.of(a, b)) {
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, "late-parent",
            SchemaFields.DOC_UID, "late", SchemaFields.PATH, file.toString(), SchemaFields.COLLECTION, "docs")));
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, "late-child",
            SchemaFields.DOC_UID, "late-child", SchemaFields.PARENT_DOC_ID, "late-parent",
            SchemaFields.IS_CHUNK, "true", SchemaFields.COLLECTION, "docs")));
      }
      assertTrue(queue.enqueueAndBufferFileForGeneration(generation, JobQueue.EnqueueEntry.stat(file), "docs", null));
      b.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, "candidate-only",
          SchemaFields.DOC_UID, "candidate-only", SchemaFields.COLLECTION, "docs")));
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, candidatePath, b, a, null, 0L);
      var response = service.deleteByCollection(DeleteByCollectionRequest.newBuilder().setCollection("docs").build(), CallContext.none());
      assertTrue(response.getError().isEmpty(), response.getError()); assertEquals(4, response.getDeletedDocs());
      assertEquals(0, a.indexCountOps().docCount()); assertEquals(0, b.indexCountOps().docCount());
      var repeated = service.deleteByCollection(DeleteByCollectionRequest.newBuilder().setCollection("docs").build(), CallContext.none());
      assertTrue(repeated.getError().isEmpty(), repeated.getError()); assertEquals(0, repeated.getDeletedDocs());
    }
    try (var queue = new SqliteJobQueue(database); var runtime = open(candidatePath)) {
      queue.open();
      var context = new io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.DrainSwitchBufferContext(
          queue, runtime, null, IndexingPacing.unthrottled(), base, candidatePath, new tools.jackson.databind.ObjectMapper(),
          () -> false, () -> false, org.slf4j.LoggerFactory.getLogger(getClass()), Long.MAX_VALUE, generation)
          .withPublisher(service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE));
      assertTrue(io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.drainSwitchBufferStrict(context));
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(0, runtime.indexCountOps().docCount());
      assertEquals(0, queue.queueDepth());
    }
  }


  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @org.junit.jupiter.params.provider.ValueSource(strings = {"unproven", "changed-volume", "unknown-volume", "unreadable-folder"})
  void C10UncertainStorageNeverAuthorizesAdHocFamilyDeletion(String condition) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path folder = Files.createDirectory(directory.resolve("folder"));
    Path file = Files.writeString(folder.resolve("source.txt"), "source");
    var permissions = Files.getPosixFilePermissions(folder);
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); String key = seed(runtime, file, "uid");
      if (condition.equals("unreadable-folder")) Files.setPosixFilePermissions(folder, java.util.Set.of());
      else Files.delete(file);
      if (condition.equals("changed-volume")) RootVolumeLedger.under(base).recordFileFolder(folder, "unix:deadbeef");
      var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      if (condition.equals("unknown-volume")) {
        ((RootWitness) service.rootCheck()).recordDeliberateAdd(file, true);
        assertTrue(service.rootCheck().vouchesForFile(folder), "prime previously valid deletion evidence");
        try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(false)) {
          reconcile(service);
        }
      } else reconcile(service);
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(key));
      runtime.commitOps().commitAndTrack();
    } finally { Files.setPosixFilePermissions(folder, permissions); }
    try (var runtime = open(layout.activeGenerationPath())) { assertEquals(2, runtime.indexCountOps().docCount()); }
  }


  @Test void C10BufferedNonregularSourceWithoutVolumeProofCannotRetireIndexedContent() throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path root = Files.createDirectory(directory.resolve("root"));
    Path file = Files.writeString(root.resolve("source.txt"), "source");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); String key = seed(runtime, file, "uid");
      Files.delete(file); Files.createDirectory(file);
      var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      var deletion = io.justsearch.indexerworker.queue.SwitchBufferDelete.decode(key);
      try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(false)) {
        assertEquals(WorkerIngestService.BufferedDeleteReplay.UNCERTAIN,
            service.replayBufferedFileDelete(runtime, deletion));
      }
      assertEquals(0, queue.queueDepth(), "uncertain retirement must not be admitted as a policy job");
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(key));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"docs", "", "other"})
  void C5BatchAcceptedBeforeCollectionRemovalCannotReadmitItsTail(String collection) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path file = Files.writeString(directory.resolve("source.txt"), "source");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db")); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); seed(runtime, file, "uid"); var observed = spy(queue);
      var service = service(observed, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      var removed = new java.util.concurrent.atomic.AtomicBoolean();
      doAnswer(call -> {
        if (removed.compareAndSet(false, true)) {
          assertTrue(service.deleteByCollection(DeleteByCollectionRequest.newBuilder()
              .setCollection("docs").build(), CallContext.none()).getError().isEmpty());
        }
        return call.callRealMethod();
      }).when(observed).queueDepth();
      var request = io.justsearch.ipc.BatchRequest.newBuilder().addFilePaths(file.toString()).setTargetCollection(collection).build();
      int accepted = collection.equals("other") ? 1 : 0;
      assertEquals(accepted, service.submitBatch(request, CallContext.none()).getAcceptedCount());
      assertEquals(accepted, queue.queueDepth()); assertEquals(0, runtime.indexCountOps().docCount());
      assertEquals(1, service.submitBatch(request, CallContext.none()).getAcceptedCount());
      assertEquals(1, queue.queueDepth());
    }
  }


  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"cancelled", "normalized-noop", "missing"})
  void C20RefusedOrEqualNormalizedMappingLeavesDestinationAndIdentityUntouched(String kind) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path source = Files.writeString(directory.resolve("source.txt"), "source");
    Path destination = Files.writeString(directory.resolve("destination.txt"), "destination");
    Path database = directory.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database); var identities = new SqliteDocumentIdentityStore(database, 1000);
        var runtime = open(layout.activeGenerationPath())) {
      queue.open();
      String oldHash = DocumentIdentityStore.pathHash(PathNormalizer.normalizeKey(source));
      String newHash = DocumentIdentityStore.pathHash(PathNormalizer.normalizeKey(destination));
      var oldIdentity = identities.resolve(oldHash, System.currentTimeMillis());
      var newIdentity = identities.resolve(newHash, System.currentTimeMillis());
      seed(runtime, source, oldIdentity.docUid()); seed(runtime, destination, newIdentity.docUid());
      var service = service(queue, runtime, base, identities);
      var maintenance = CallContext.none();
      var context = kind.equals("cancelled") ? new CallContext(null, null, () -> true,
          maintenance.engineContext(), maintenance.provenance(), maintenance.childLifetime()) : maintenance;
      String from = kind.equals("missing") ? directory.resolve("missing.txt").toString() : source.toString();
      String to = kind.equals("normalized-noop") ? directory.resolve("./source.txt").toString() : destination.toString();
      var response = service.updateDocumentPaths(io.justsearch.ipc.UpdatePathsRequest.newBuilder()
          .addMappings(io.justsearch.ipc.PathMapping.newBuilder().setOldPath(from).setNewPath(to)).build(), context);
      assertEquals(kind.equals("cancelled") ? 0 : 1, response.getUpdatedCount());
      assertEquals(oldIdentity, identities.lookup(oldHash).orElseThrow());
      assertEquals(newIdentity, identities.lookup(newHash).orElseThrow());
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(source.toString()));
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(destination.toString()));
    }
  }


  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "blank"})
  void C6LegacyDefaultCollectionClaimsCancelWithoutTouchingAnotherOwner(String legacy) throws Exception {
    Path base = directory.resolve("base"); var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path first = Files.writeString(directory.resolve("first.txt"), "a");
    Path second = Files.writeString(directory.resolve("second.txt"), "b");
    Path other = Files.writeString(directory.resolve("other.txt"), "c");
    Path database = directory.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database); var runtime = open(layout.activeGenerationPath())) {
      queue.open(); queue.enqueue(List.of(first, second));
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
          var update = connection.prepareStatement("UPDATE jobs SET collection=? WHERE path IN (?,?)")) {
        update.setString(1, legacy.equals("null") ? null : " ");
        update.setString(2, first.toString()); update.setString(3, second.toString());
        assertEquals(2, update.executeUpdate());
      }
      var claim = queue.pollPending(1).getFirst();
      assertNotEquals(other, claim.path()); assertTrue(queue.ownsClaimForPublication(claim));
      queue.enqueue(List.of(other), " default ");
      var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      assertTrue(service.deleteByCollection(DeleteByCollectionRequest.newBuilder().setCollection("default").build(),
          CallContext.none()).getError().isEmpty());
      assertFalse(queue.ownsClaimForPublication(claim));
      assertEquals(List.of(other), queue.pollPending(10).stream().map(JobQueue.IndexJob::path).toList());
      queue.enqueue(List.of(claim.path()));
      assertTrue(queue.ownsClaimForPublication(queue.pollPending(1).getFirst()));
    }
  }

}
