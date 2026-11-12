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

  @Test
  void C10CommittedBootKeepsAbsentRowsWithoutVolumeProofAndRetriesWhenProven() throws Exception {
    Path base = directory.resolve("base");
    var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path root = Files.createDirectory(directory.resolve("root"));
    Path missing = root.resolve("missing.txt");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"));
        var runtime = open(layout.activeGenerationPath())) {
      queue.open();
      String key = seed(runtime, missing, "original");
      var binding = new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root, "docs");
      var registry = new RootWatcherRegistry();
      var service = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
      service.setRootWatcherRegistry(registry);
      try {
        try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(false)) {
          assertThrows(java.io.IOException.class, () -> service.reconcileCommittedBootRoot(binding),
              "an incomplete absence reconciliation must be retried, never certified");
          runtime.commitOps().commitAndTrack();
          runtime.commitOps().maybeRefreshBlocking();
          assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(key),
              "unknown current and recorded volumes preserve the whole family");
        }
        assertTrue(((RootWitness) service.rootCheck()).recordAdded(root));
        // A fresh service is the next boot attempt, without the negative witness cache.
        var retry = service(queue, runtime, base, DocumentIdentityStore.UNAVAILABLE);
        registry.unwatch(root.toString());
        retry.setRootWatcherRegistry(registry);
        assertTrue(retry.reconcileCommittedBootRoot(binding));
        runtime.commitOps().commitAndTrack();
        runtime.commitOps().maybeRefreshBlocking();
        assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(key));
      } finally {
        registry.unwatch(root.toString());
      }
    }
    try (var runtime = open(layout.activeGenerationPath())) {
      assertEquals(0, runtime.indexCountOps().docCount(), "the proven retry is durable");
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

  private static final class Mounts implements StorageObservationSnapshot.Evidence {
    final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
    List<String> lines = List.of("1 0 8:1 / / rw - ext4 /dev/root rw");
    public List<String> mountInfo() { reads.incrementAndGet(); return lines; }
    public Map<String, String> uuidDevices() { return Map.of("8:1", "aaaa-1111", "8:2", "bbbb-2222", "8:3", "cccc-3333"); }
    public String sourceDevice(LinuxRootIdentityLease.Mount mount) { return mount.device(); }
  }

  private static void upkeepAuthority(SqliteJobQueue queue, boolean inactive, boolean refresh) throws Exception {
    try {
      var eligibility = Class.forName(JobQueue.class.getName() + "$ImportUpkeepEligibility");
      Object verdict = eligibility.getConstructor(boolean.class, boolean.class).newInstance(inactive, refresh);
      JobQueue.class.getMethod("useImportUpkeepAuthority", java.util.function.Function.class)
          .invoke(queue, (java.util.function.Function<Object, Object>) ignored -> verdict);
    } catch (ClassNotFoundException | NoSuchMethodException baseline) {
      // The before-state has no upkeep bridge; maintenance behavior below is the oracle.
    }
  }

  private io.justsearch.app.api.indexing.SourceOrigin origin(Path root, boolean singleFile) {
    var approval = new io.justsearch.app.api.indexing.SourceOrigin.Approval(
        new io.justsearch.app.api.operations.OperationAuthorizationBasis.StructuralAuto().encode(),
        "SYSTEM_INTERNAL", "TRUSTED", "CLI", "core.ingest-files", "import-parent",
        io.justsearch.app.api.indexing.SourceOrigin.ApprovalClass.DURABLE,
        io.justsearch.app.api.indexing.SourceOrigin.EvidenceSource.GATE);
    return new io.justsearch.app.api.indexing.SourceOrigin(java.util.UUID.randomUUID().toString(),
        io.justsearch.app.api.indexing.SourceOrigin.Kind.IMPORT,
        new io.justsearch.app.api.operations.RecordedRootPlan.Root(root, "docs", false, singleFile, List.of(), List.of()),
        approval, 1L, new io.justsearch.app.api.indexing.SourceOrigin.RestartBinding(1,
            java.util.UUID.randomUUID().toString(), "a".repeat(64), null));
  }

  private final class ImportFixture implements AutoCloseable {
    final Path base = directory.resolve("base");
    final Path root = Files.createDirectories(directory.resolve("import"));
    final Path database = directory.resolve("imports.db");
    final Mounts mounts = new Mounts();
    final org.mockito.MockedStatic<RootIdentity> storage;
    final SqliteJobQueue queue = new SqliteJobQueue(database);
    final SqliteDocumentIdentityStore identities;
    final RunningRuntime runtime;
    final WorkerIngestService service;
    final io.justsearch.app.api.indexing.SourceOrigin origin;
    boolean failExtraction;

    ImportFixture(boolean singleFile) throws Exception {
      var layout = new IndexGenerationManager(base).initializeOrLoad();
      runtime = IndexSchema.fromCatalog(new io.justsearch.configuration.JustSearchConfigurationLoader().loadFieldCatalog())
          .atPath(layout.activeGenerationPath()).withExecutorRegistrations(testLuceneExecutors()).open();
      runtime.commitOps().stopCommitTimer();
      queue.open(); identities = new SqliteDocumentIdentityStore(database);
      origin = origin(singleFile ? root.resolve("file.txt") : root, singleFile);
      queue.recordSourceOrigins(List.of(origin)); upkeepAuthority(queue, true, true);
      service = service(queue, runtime, base, identities);
      storage = mockStatic(RootIdentity.class, CALLS_REAL_METHODS);
      storage.when(RootIdentity::storageSnapshot).thenAnswer(call -> StorageObservationSnapshot.linux(mounts));
    }

    Path member(String relative) throws Exception {
      Path file = root.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, "source");
      ((RootWitness) service.rootCheck()).recordDeliberateAdd(file, true);
      String uid = identities.resolve(DocumentIdentityStore.pathHash(PathNormalizer.normalizeKey(file)),
          System.currentTimeMillis()).docUid();
      String key = PathNormalizer.normalizeKey(file);
      var parent = new java.util.HashMap<String, Object>(StorageObservationSnapshot.capture().envelope(file, origin.id()).fields());
      parent.putAll(Map.of(SchemaFields.DOC_ID, key, SchemaFields.DOC_UID, uid, SchemaFields.PATH, key,
          SchemaFields.CONTENT, "source", SchemaFields.COLLECTION, "docs"));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(parent));
      var child = new java.util.HashMap<>(parent);
      child.remove(SchemaFields.CONTENT);
      child.putAll(Map.of(SchemaFields.DOC_ID, key + "#child", SchemaFields.DOC_UID, uid + "#0",
          SchemaFields.PARENT_DOC_ID, key, SchemaFields.IS_CHUNK, "true", SchemaFields.CHUNK_CONTENT, "source",
          SchemaFields.CHUNK_START_CHAR, "0", SchemaFields.CHUNK_END_CHAR, "6",
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256, io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex("source")));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(child));
      commit(); return file;
    }

    void commit() { runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking(); }
    int rows(Path file) throws java.io.IOException { return runtime.indexCountOps().countByIdAndChunksStrict(PathNormalizer.normalizeKey(file)); }
    void reconcile() throws Exception { reconcile(false); }
    void reconcile(boolean incomplete) throws Exception {
      var result = IndexedSourceMaintenanceTest.this.reconcile(service);
      if (incomplete) assertTrue(result.getError().startsWith("prune_incomplete:"), result.getError());
      else assertTrue(result.getError().isEmpty(), result.getError());
    }
    @Override public void close() throws Exception { storage.close(); runtime.close(); identities.close(); queue.close(); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void U2U3MissingImportAncestorsRetireTheWholeFamily(boolean wholeRoot) throws Exception {
    try (var f = new ImportFixture(false)) {
      Path first = f.member("inner/first.txt"), second = f.member("inner/second.txt");
      Files.delete(first); Files.delete(second); Files.delete(first.getParent());
      if (wholeRoot) Files.delete(f.root);
      f.mounts.reads.set(0); f.reconcile();
      assertEquals(0, f.rows(first)); assertEquals(0, f.rows(second));
      assertEquals(2, f.mounts.reads.get(), "one observation per pass and one per retirement batch");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"unplugged", "replaced", "overmounted", "between-enumeration-and-fence"})
  void U9ChildMountChangesNeverAuthorizeRetirement(String topology) throws Exception {
    try (var f = new ImportFixture(false)) {
      Path child = Files.createDirectory(f.root.resolve("child"));
      String disk = "2 1 8:2 / " + child + " rw - ext4 /dev/child rw";
      f.mounts.lines = List.of(f.mounts.lines.getFirst(), disk);
      Path file = f.member("child/file.txt"); Files.delete(file);
      if (topology.equals("between-enumeration-and-fence")) {
        var observed = spy(f.runtime); var fields = spy(f.runtime.documentFieldOps());
        doReturn(fields).when(observed).documentFieldOps();
        doAnswer(call -> {
          f.mounts.lines = List.of(f.mounts.lines.getFirst());
          return call.callRealMethod();
        }).when(fields).getDocumentFieldsBatchOrThrow(anyList(), anySet());
        var service = service(f.queue, observed, f.base, DocumentIdentityStore.UNAVAILABLE);
        assertTrue(reconcile(service).getError().startsWith("prune_incomplete:"));
      } else {
        String replacement = "3 1 8:3 / " + child + " rw - ext4 /dev/replacement rw";
        f.mounts.lines = switch (topology) {
          case "unplugged" -> List.of(f.mounts.lines.getFirst());
          case "overmounted" -> List.of(f.mounts.lines.getFirst(), disk, replacement);
          default -> List.of(f.mounts.lines.getFirst(), replacement);
        };
        f.reconcile(true);
      }
      assertEquals(2, f.rows(file)); f.commit();
    }
  }

  @Test void U10OpenImportProducerIsNeitherRetiredNorRefreshed() throws Exception {
    try (var f = new ImportFixture(false)) {
      Path missing = f.member("missing.txt"), changed = f.member("changed.txt");
      f.queue.beginRecordedWalk("import-parent", "a".repeat(64), true);
      Files.delete(missing); Files.writeString(changed, "changed source");
      f.reconcile();
      assertEquals(2, f.rows(missing)); assertEquals(0, f.queue.queueDepth());
      assertNull(f.queue.recordedWalk("import-parent").orElseThrow().enumerationClosedAt());
    }
  }

  @Test void U10InterruptedImportOwnerIsNeitherRetiredNorRefreshed() throws Exception {
    try (var f = new ImportFixture(false)) {
      Path missing = f.member("missing.txt"), changed = f.member("changed.txt");
      upkeepAuthority(f.queue, false, false);
      Files.delete(missing); Files.writeString(changed, "changed source"); f.reconcile();
      assertEquals(2, f.rows(missing)); assertEquals(0, f.queue.queueDepth());
    }
  }

  @Test void U4U11ChangedMembersQueueDurableOriginBearingRefreshWithoutDiscoveringNewFiles() throws Exception {
    try (var f = new ImportFixture(false)) {
      Path changed = f.member("changed.txt"); Files.writeString(changed, "new source text");
      Files.writeString(f.root.resolve("new.txt"), "never discovered"); f.reconcile();
      assertEquals(1, f.queue.queueDepth());
      var claims = f.queue.pollPending(10); assertEquals(1, claims.size());
      var claim = claims.getFirst(); assertEquals(changed, claim.path()); assertEquals("docs", claim.collection());
      assertEquals(f.origin.root().path(), claim.ingestionRoot());
      assertEquals(f.origin.id(), f.queue.sourceOriginId(claim).orElseThrow());
      f.queue.returnUnfinishedClaims(claims);
    }
    try (var queue = new SqliteJobQueue(directory.resolve("imports.db"))) {
      queue.open(); upkeepAuthority(queue, true, true); var claim = queue.pollPending(1).getFirst();
      assertEquals("docs", claim.collection()); assertTrue(claim.unitRevision().startsWith("upkeep/"));
      assertTrue(queue.sourceOriginId(claim).isPresent());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"legacy", "revoked", "session", "excluded"})
  void U6U7U8N5UnavailableApprovalOrExclusionNeverQueuesRefresh(String reason) throws Exception {
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    try (var f = new ImportFixture(false)) {
      Path file = f.member("inner/changed.txt"); Files.writeString(file, "new source text");
      if (reason.equals("legacy")) {
        var fields = new java.util.HashMap<String, Object>(); fields.put(SchemaFields.ORIGIN_ID, null);
        f.runtime.indexingCoordinator().updateDocument(file.toString(), fields); f.commit();
      } else if (reason.equals("excluded")) {
        io.justsearch.configuration.resolved.ConfigStore.setGlobal(new io.justsearch.configuration.resolved.ConfigStore(
            io.justsearch.configuration.resolved.ResolvedConfig.builder()
                .putDefault("justsearch.ui.exclude_patterns", "[\"inner/**\"]").build()));
      } else upkeepAuthority(f.queue, true, false);
      f.reconcile(); assertEquals(0, f.queue.queueDepth()); assertEquals(2, f.rows(file));
    } finally { io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  private void processUpkeep(ImportFixture f, Runnable afterExtraction) throws Exception {
    var parser = mock(io.justsearch.indexerworker.extract.TimeboxedContentExtractor.class);
    var policy = io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults();
    when(parser.extractionPolicy()).thenReturn(policy);
    when(parser.extractArtifact(any())).thenAnswer(call -> {
      if (f.failExtraction) throw new java.io.IOException("injected extraction failure");
      return io.justsearch.indexerworker.extract.ExtractionArtifact.full(
          new io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult(
              Files.readString(call.getArgument(0)), null, "text/plain"), policy, "upkeep-test", false);
    });
    try (var identities = new SqliteDocumentIdentityStore(f.database);
        var loop = new IndexingLoop(null, null, f.queue, f.runtime.indexingCoordinator(), f.runtime.commitOps(),
            f.runtime.documentFieldOps(), f.runtime.indexCountOps(), () -> null,
            mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class), IndexingPacing.unthrottled(),
            null, null, null, null, parser, null,
            new io.justsearch.indexerworker.loop.IndexingLoopOptions(false, null, identities, null, null, null))) {
      loop.useRootCheck(f.service.rootCheck());
      loop.useFileExclusions(f.service::isUserExcluded);
      var running = IndexingLoop.class.getDeclaredField("running"); running.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicBoolean) running.get(loop)).set(true);
      var extractorMethod = IndexingLoop.class.getDeclaredMethod("getExtractor"); extractorMethod.setAccessible(true);
      var writerMethod = IndexingLoop.class.getDeclaredMethod("getWriter"); writerMethod.setAccessible(true);
      var journalMethod = IndexingLoop.class.getDeclaredMethod("getJournal"); journalMethod.setAccessible(true);
      var extractor = (io.justsearch.indexerworker.loop.JobBatchExtractor) extractorMethod.invoke(loop);
      var writer = (io.justsearch.indexerworker.loop.JobBatchWriter) writerMethod.invoke(loop);
      var journal = (io.justsearch.indexerworker.loop.IngestionOutcomeJournal) journalMethod.invoke(loop);
      var claims = f.queue.pollPending(10);
      var beginBatch = writer.getClass().getDeclaredMethod("beginBatch"); beginBatch.setAccessible(true);
      beginBatch.invoke(writer); var jobs = extractor.extractAll(claims); afterExtraction.run();
      for (var job : jobs) writer.write(job, null);
      f.commit(); journal.drainPending(); journal.returnBatchClaims(claims);
    }
  }

  @Test void U4U11TwoSuccessiveEditsPublishNewTextAndPreserveOwnershipAcrossQueueRestart() throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("file.txt");
      assertTrue(((RootWitness) f.service.rootCheck()).recordAdded(f.root));
      String uid = f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.DOC_UID);
      for (String text : List.of("first revision uniqueupkeepalpha", "second revision uniqueupkeepbeta with additional words")) {
        Files.writeString(file, text); f.reconcile(); assertEquals(1, f.queue.queueDepth());
        processUpkeep(f, () -> {});
        assertEquals(text, f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.CONTENT));
        assertTrue(searchTerm(f, text.contains("alpha") ? "uniqueupkeepalpha" : "uniqueupkeepbeta") > 0);
        if (text.contains("beta")) assertEquals(0, searchTerm(f, "uniqueupkeepalpha"));
        assertEquals(f.origin.id(), f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.ORIGIN_ID));
        assertEquals("known", f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.STORAGE_STATUS));
        assertEquals("aaaa-1111:ext4:/", f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.STORAGE_KEY));
        assertEquals("docs", f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.COLLECTION));
        assertEquals(uid, f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.DOC_UID));
        assertEquals(0, f.queue.queueDepth());
        f.queue.close(); f.queue.open(); upkeepAuthority(f.queue, true, true);
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing-before-extraction", "missing-after-extraction", "storage-before-extraction", "storage-after-extraction"})
  void U11RefreshNeverDeletesRowsWhenTheSourceOrStorageDisappears(String when) throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("file.txt");
      assertTrue(((RootWitness) f.service.rootCheck()).recordAdded(f.root));
      Files.writeString(file, "edited source should not replace old rows"); f.reconcile();
      if (f.queue.queueDepth() == 0) {
        // Before-state fixture: isolate the extraction race from the then-missing upkeep producer.
        assertEquals(1, f.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(f.root)), "docs"));
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
            var update = db.prepareStatement("UPDATE jobs SET unit_revision=? WHERE path=?")) {
          update.setString(1, "upkeep/" + f.origin.id() + "/" + java.util.UUID.randomUUID());
          update.setString(2, file.toString()); assertEquals(1, update.executeUpdate());
        }
      }
      assertEquals(1, f.queue.queueDepth());
      Runnable disappear = () -> {
        try {
          if (when.startsWith("missing")) Files.delete(file);
          else f.mounts.lines = List.of("1 0 8:3 / / rw - ext4 /dev/replacement rw");
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
      };
      if (when.endsWith("before-extraction")) disappear.run();
      processUpkeep(f, when.endsWith("after-extraction") ? disappear : () -> {});
      assertEquals(2, f.rows(file));
      assertEquals("source", f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.CONTENT));
    }
  }

  @Test void U12ExclusionRemovalSkipsChangedOwnerAndCancelsAnAlreadyQueuedRefresh() throws Exception {
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    try (var f = new ImportFixture(false)) {
      Path file = f.member("inner/file.txt");
      Files.writeString(file, "changed content"); f.reconcile(); assertEquals(1, f.queue.queueDepth());
      var next = origin(f.root, false); f.queue.recordSourceOrigins(List.of(next));
      f.runtime.indexingCoordinator().updateDocument(file.toString(), Map.of(SchemaFields.ORIGIN_ID, next.id())); f.commit();
      io.justsearch.configuration.resolved.ConfigStore.setGlobal(new io.justsearch.configuration.resolved.ConfigStore(
          io.justsearch.configuration.resolved.ResolvedConfig.builder()
              .putDefault("justsearch.ui.exclude_patterns", "[\"inner/**\"]").build()));
      conditionalExclude(f.service, file, f.origin.id()); assertEquals(2, f.rows(file));
      conditionalExclude(f.service, file, next.id()); assertEquals(0, f.rows(file));
      assertEquals(0, f.queue.queueDepth()); processUpkeep(f, () -> {}); assertEquals(0, f.rows(file));
    } finally { io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  private static void conditionalExclude(WorkerIngestService service, Path file, String origin) throws Exception {
    try {
      WorkerIngestService.class.getMethod("deleteExcludedImportMember", String.class, String.class, CallContext.class)
          .invoke(service, file.toString(), origin, CallContext.none());
    } catch (NoSuchMethodException before) {
      service.deleteById(io.justsearch.ipc.DeleteByIdRequest.newBuilder().setDocId(file.toString()).build(), CallContext.none());
    }
  }

  @Test void U14RecreatedMemberAfterRetirementCommitIsReadmittedUnderItsOrigin() throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("file.txt"); Files.delete(file);
      var runtime = spy(f.runtime); var commits = spy(f.runtime.commitOps()); doReturn(commits).when(runtime).commitOps();
      doAnswer(call -> { Object result = call.callRealMethod(); Files.writeString(file, "recreated content"); return result; })
          .when(commits).commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.PRUNE);
      var service = service(f.queue, runtime, f.base, DocumentIdentityStore.UNAVAILABLE);
      var response = reconcile(service); assertTrue(response.getError().isEmpty(), response.getError());
      assertEquals(1, f.queue.queueDepth()); assertEquals(0, f.rows(file));
      assertEquals(f.origin.id(), f.queue.sourceOriginId(f.queue.pollPending(1).getFirst()).orElseThrow());
    }
  }

  @Test void U13SingleFileImportRefreshesAndThenRetiresItsDeletedMember() throws Exception {
    try (var f = new ImportFixture(true)) {
      Path file = f.member("file.txt"); Files.writeString(file, "edited single import source"); f.reconcile();
      assertEquals(1, f.queue.queueDepth()); processUpkeep(f, () -> {});
      assertEquals("edited single import source", f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.CONTENT));
      Files.delete(file); f.reconcile(); assertEquals(0, f.rows(file));
    }
  }

  @Test void U13ExternalRenameRetiresOnlyTheIndexedPathAndNeverDiscoversItsReplacement() throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("inner/file.txt"), destination = f.root.resolve("renamed.txt");
      Files.move(file, destination); Files.delete(file.getParent()); f.reconcile();
      assertEquals(0, f.rows(file)); assertEquals(0, f.rows(destination)); assertEquals(0, f.queue.queueDepth());
    }
  }

  @Test void U13AppMediatedMoveRetainsTheOriginAndItsRefreshScope() throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("file.txt"), destination = f.root.resolve("moved.txt"); Files.move(file, destination);
      var response = f.service.updateDocumentPaths(io.justsearch.ipc.UpdatePathsRequest.newBuilder()
          .addMappings(io.justsearch.ipc.PathMapping.newBuilder().setOldPath(file.toString()).setNewPath(destination.toString()))
          .build(), CallContext.none());
      assertEquals(1, response.getUpdatedCount());
      assertEquals(f.origin.id(), f.runtime.documentFieldOps().getDocumentField(destination.toString(), SchemaFields.ORIGIN_ID));
      Files.writeString(destination, "changed after app move"); f.reconcile(); assertEquals(1, f.queue.queueDepth());
      assertEquals(destination, f.queue.pollPending(1).getFirst().path());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"revoked", "session"})
  void U7U8MissingImportAncestorsRetireEvenWhenRefreshApprovalIsUnavailable(String approval) throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("inner/file.txt"); upkeepAuthority(f.queue, true, false);
      Files.delete(file); Files.delete(file.getParent()); Files.delete(f.root); f.reconcile();
      assertEquals(0, f.rows(file)); assertEquals(0, f.queue.queueDepth(), approval);
    }
  }

  @Test void N2UnmountedImportStorageKeepsRowsUntilTheSameMountReturns() throws Exception {
    try (var f = new ImportFixture(false)) {
      String mounted = "2 1 8:2 / " + f.root + " rw - ext4 /dev/drive rw";
      f.mounts.lines = List.of(f.mounts.lines.getFirst(), mounted);
      Path file = f.member("file.txt"); Files.delete(file);
      f.mounts.lines = List.of(f.mounts.lines.getFirst());
      try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(false)) { f.reconcile(true); }
      assertEquals(2, f.rows(file));
      f.mounts.lines = List.of(f.mounts.lines.getFirst(), mounted);
      assertTrue(reconcile(service(f.queue, f.runtime, f.base, DocumentIdentityStore.UNAVAILABLE)).getError().isEmpty());
      assertEquals(0, f.rows(file));
    }
  }

  @Test void N4UnknownStorageKeepsTheExistingParentWitnessRule() throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("inner/file.txt");
      var unknown = new io.justsearch.indexing.SourceEnvelope(f.origin.id(),
          io.justsearch.indexing.SourceEnvelope.StorageStatus.UNKNOWN, null, null).patch();
      f.runtime.indexingCoordinator().updateDocument(file.toString(), unknown);
      f.runtime.indexingCoordinator().updateDocument(file + "#child", unknown); f.commit();
      Files.delete(file); Files.delete(file.getParent()); f.reconcile(true); assertEquals(2, f.rows(file));
    }
  }

  @Test void U11ChunkFamilyGrowthAndShrinkReplaceEveryOldMember() throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("file.txt"); assertTrue(((RootWitness) f.service.rootCheck()).recordAdded(f.root));
      String longText = "Long import content, with different words in each sentence.\n".repeat(800);
      Files.writeString(file, longText); f.reconcile(); assertEquals(1, f.queue.queueDepth()); processUpkeep(f, () -> {});
      int longFamily = f.rows(file); assertTrue(longFamily > 2);
      Files.writeString(file, "short replacement content"); f.reconcile(); assertEquals(1, f.queue.queueDepth());
      processUpkeep(f, () -> {}); assertTrue(f.rows(file) < longFamily);
      assertEquals("short replacement content", f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.CONTENT));
      assertEquals(f.origin.id(), f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.ORIGIN_ID));
    }
  }


  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"extraction-failure", "unreadable", "placeholder"})
  void U11FailedUnreadableOrPlaceholderRefreshPreservesTheExistingFamily(String failure) throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("file.txt"); assertTrue(((RootWitness) f.service.rootCheck()).recordAdded(f.root));
      Files.writeString(file, "changed source awaiting extraction"); f.reconcile(); assertEquals(1, f.queue.queueDepth());
      var permissions = Files.getPosixFilePermissions(file);
      try (var cloud = mockStatic(io.justsearch.indexerworker.ingest.CloudPlaceholderDetector.class, CALLS_REAL_METHODS)) {
        if (failure.equals("placeholder")) cloud.when(() ->
            io.justsearch.indexerworker.ingest.CloudPlaceholderDetector.isCloudPlaceholder(file)).thenReturn(true);
        if (failure.equals("unreadable")) Files.setPosixFilePermissions(file, java.util.Set.of());
        f.failExtraction = failure.equals("extraction-failure"); processUpkeep(f, () -> {});
        assertEquals(2, f.rows(file));
        assertEquals("source", f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.CONTENT));
      } finally { Files.setPosixFilePermissions(file, permissions); }
    }
  }

  private static int searchTerm(ImportFixture fixture, String term) throws Exception {
    try (var directory = org.apache.lucene.store.FSDirectory.open(fixture.runtime.openedIndexPath());
        var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
      var query = new org.apache.lucene.search.BooleanQuery.Builder();
      for (String field : List.of(SchemaFields.CONTENT, SchemaFields.CHUNK_CONTENT)) {
        query.add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(field, term)),
            org.apache.lucene.search.BooleanClause.Occur.SHOULD);
      }
      return new org.apache.lucene.search.IndexSearcher(reader).count(query.build());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void U4FailedUpkeepRetainsItsRetryBudgetUntilTheMemberOrPolicyChanges(boolean policyChange) throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("file.txt"); assertTrue(((RootWitness) f.service.rootCheck()).recordAdded(f.root));
      f.service.setExtractionPolicy(io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults());
      Files.writeString(file, "failed source revision"); f.reconcile(); assertEquals(1, f.queue.queueDepth());
      try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
          var failure = db.prepareStatement("UPDATE jobs SET state='FAILED',attempts=3,retry_after=NULL,last_outcome_class='PARSER_FAILED',last_retry_policy='NONE' WHERE path=?")) {
        failure.setString(1, file.toString()); assertEquals(1, failure.executeUpdate());
      }
      f.reconcile(); assertEquals(0, f.queue.queueDepth(), "unchanged failure retains its exhausted budget");
      if (policyChange) {
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
            var policy = db.prepareStatement("UPDATE jobs SET extraction_policy_id='old-policy' WHERE path=?")) {
          policy.setString(1, file.toString()); assertEquals(1, policy.executeUpdate());
        }
      } else Files.writeString(file, "fixed source revision with new text");
      f.reconcile();
      assertEquals(1, f.queue.queueDepth(), "changed bytes or extraction policy acquire fresh upkeep");
      processUpkeep(f, () -> {});
      assertEquals(policyChange ? "failed source revision" : "fixed source revision with new text",
          f.runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.CONTENT));
    }
  }

  @Test void U7QueuedRefreshDefersWithoutOpeningOrSpendingAttemptsWhenApprovalIsLost() throws Exception {
    try (var f = new ImportFixture(false)) {
      Path file = f.member("file.txt"); Files.writeString(file, "edited while approved");
      f.reconcile(); assertEquals(1, f.queue.queueDepth()); upkeepAuthority(f.queue, true, false);
      try (var hashing = mockStatic(io.justsearch.indexerworker.loop.SourceContentHash.class, CALLS_REAL_METHODS)) {
        processUpkeep(f, () -> {}); hashing.verifyNoInteractions();
      }
      assertEquals(2, f.rows(file));
      try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
          var query = db.prepareStatement("SELECT attempts,last_updated,retry_after,last_retry_policy FROM jobs WHERE path=?")) {
        query.setString(1, file.toString());
        try (var row = query.executeQuery()) {
          assertTrue(row.next()); assertEquals(0, row.getInt(1));
          assertTrue(row.getLong(3) > row.getLong(2));
          assertEquals("DEFER_WITHOUT_ATTEMPT", row.getString(4));
        }
      }
    }
  }
}
