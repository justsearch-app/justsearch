/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

final class NestedWatchedRootTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test
  void headDeclarationProtectsANestedRootBeforeItsWatcherInitializes() throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = parent.resolve("awaiting-volume");
    Path nested = child.resolve("nested.txt");
    Path own = parent.resolve("own.txt");
    Path base = temp.resolve("index");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db")); var runtime = open(base.resolve("active"))) {
      queue.open();
      var service = service(queue, base, runtime, runtime);
      service.useExclusionRoots(() -> List.of(child));
      index(runtime, own, "parent");
      index(runtime, nested, "child");
      runtime.commitOps().commitAndTrack();
      queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(nested, 10)), "child");

      assertEquals("", service.deleteByPath(DeleteByPathRequest.newBuilder()
          .setPath(parent.toString()).build(), CallContext.none()).getError());

      runtime.commitOps().maybeRefreshBlocking();
      assertNull(runtime.documentFieldOps().getDocumentField(key(own), SchemaFields.CONTENT));
      assertEquals("child", runtime.documentFieldOps().getDocumentField(key(nested), SchemaFields.CONTENT));
      assertEquals(1, queue.queueDepth());
      assertFalse(Files.exists(child), "an unavailable declaration still partitions P");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void parentDeletionPreservesNestedDocumentsChunksJobsWatcherAndVolume(boolean held) throws Exception {
    Path parent = Files.createDirectories(temp.resolve("notes_%"));
    Path child = Files.createDirectories(parent.resolve("child"));
    String childCollection = held ? "parent" : "child";
    Path sibling = Files.createDirectories(temp.resolve("notes_%2"));
    Path own = Files.writeString(parent.resolve("own.txt"), "parent");
    Path nested = Files.writeString(child.resolve("nested.txt"), "child");
    Path beside = Files.writeString(sibling.resolve("beside.txt"), "sibling");
    Path base = temp.resolve("index");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"));
        var runtime = open(base.resolve("active"))) {
      queue.open();
      var service = service(queue, base, runtime, runtime);
      var registry = new RootWatcherRegistry();
      service.setRootWatcherRegistry(registry);
      watch(service, parent, "parent");
      watch(service, child, childCollection);
      if (held) registry.watchWhenVolumeReturns(child, childCollection);
      var debt = new io.justsearch.indexerworker.queue.WatcherRootReconciliation(
          child.toString(), "child-incarnation", childCollection);
      assertTrue(queue.putSwitchBuffer(debt.key(), debt.OPERATION, debt.encode()));
      index(runtime, own, "parent");
      index(runtime, nested, childCollection);
      index(runtime, beside, "sibling");
      String childId = key(nested);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk:nested", SchemaFields.DOC_UID, "nested-uid#0",
          SchemaFields.PATH, childId, SchemaFields.PARENT_DOC_ID, childId,
          SchemaFields.IS_CHUNK, "true", SchemaFields.CHUNK_INDEX, 0,
          SchemaFields.CHUNK_TOTAL, 1, SchemaFields.CHUNK_CONTENT, "child")));
      runtime.commitOps().commitAndTrack();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(own)), "parent");
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(nested)), childCollection);
      service.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(parent.toString()).build(), CallContext.none());

      var reply = service.deleteByPath(DeleteByPathRequest.newBuilder().setPath(parent.toString()).build(), CallContext.none());

      assertEquals("", reply.getError());
      runtime.commitOps().maybeRefreshBlocking();
      assertNull(runtime.documentFieldOps().getDocumentField(key(own), SchemaFields.CONTENT));
      assertEquals(childCollection, runtime.documentFieldOps().getDocumentField(childId, SchemaFields.CONTENT));
      assertEquals(childCollection, runtime.documentFieldOps().getDocumentField(childId, SchemaFields.COLLECTION));
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(childId));
      assertEquals("sibling", runtime.documentFieldOps().getDocumentField(key(beside), SchemaFields.CONTENT));
      assertEquals(1, queue.queueDepth());
      assertTrue(queue.matchesExpectedCollection(nested, childCollection));
      assertTrue(held ? registry.awaitsVolume(child) : registry.watchedRoots().contains(child));
      assertTrue(RootVolumeLedger.under(base).recorded(child).isPresent());
      assertTrue(RootVolumeLedger.under(base).recorded(parent).isEmpty());
      assertTrue(queue.listSwitchBufferOpsStrict().stream()
          .anyMatch(receipt -> receipt.key().equals(debt.key()) && receipt.payload().equals(debt.encode())));
      Files.writeString(nested, "changed");
      if (held) assertTrue(registry.resumeAwaitingVolume(child));
      service.acceptWatcherUpsert(registry.subscription(child), childCollection, nested);
      assertEquals(1, queue.queueDepth(), "C still admits file changes after P's removal");
      assertTrue(queue.matchesExpectedCollection(nested, childCollection));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void parentSyncNeitherPrunesNorAdmitsNestedFilesButChildStillPrunes(int mode) throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path missing = child.resolve("missing.txt");
    Path fresh = Files.writeString(child.resolve("new.txt"), "new");
    if (mode == 2) Files.delete(fresh);
    Path base = temp.resolve("index");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db")); var runtime = open(base.resolve("active"))) {
      queue.open();
      var service = service(queue, base, runtime, runtime);
      var registry = new RootWatcherRegistry();
      service.setRootWatcherRegistry(registry);
      watch(service, parent, "parent");
      watch(service, child, "child");
      index(runtime, missing, "child");
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      if (mode == 2) {
        registry.watchWhenVolumeReturns(child, "child");
        assertTrue(service.reconcileReturnedBootRoot(
            new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(parent, "parent"),
            registry.removalMark(), () -> false));
      } else if (mode == 1) {
        assertTrue(service.reconcileCommittedBootRoot(
            new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(parent, "parent")));
      } else {
        assertEquals("", service.syncDirectory(SyncDirectoryRequest.newBuilder()
            .setRootPath(parent.toString()).setForce(true).build(), CallContext.none()).getError());
      }
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("child", runtime.documentFieldOps().getDocumentField(key(missing), SchemaFields.CONTENT));
      assertEquals(0, queue.queueDepth(), "P must not admit C's new file or retag its queue work");
      if (mode == 2) Files.writeString(fresh, "returned");
      assertEquals("", service.syncDirectory(SyncDirectoryRequest.newBuilder()
          .setRootPath(child.toString()).build(), CallContext.none()).getError());
      runtime.commitOps().maybeRefreshBlocking();
      assertNull(runtime.documentFieldOps().getDocumentField(key(missing), SchemaFields.CONTENT));
      assertEquals(1, queue.queueDepth());
    }
  }

  @Test
  void legacyPrefixReplayFreezesNestedScopeAndEnumerationKeepsChildAdmissible() throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path own = Files.writeString(parent.resolve("own.txt"), "parent");
    Path nested = Files.writeString(child.resolve("nested.txt"), "child");
    Path base = temp.resolve("index");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db")); var runtime = open(base.resolve("active"))) {
      queue.open();
      var service = service(queue, base, runtime, runtime);
      watch(service, child, "child");
      index(runtime, own, "parent");
      index(runtime, nested, "child");
      runtime.commitOps().commitAndTrack();
      String prefix = PathNormalizer.normalizePathPrefix(parent.toString());
      assertTrue(queue.putSwitchBufferForGeneration("green", "prefix:" + prefix, "DELETE_PREFIX", prefix));
      var context = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
          IndexingPacing.unthrottled(), base, base.resolve("active"), new ObjectMapper(),
          () -> false, () -> false, LoggerFactory.getLogger(NestedWatchedRootTest.class), Long.MAX_VALUE, "green")
          .withPublisher(service);

      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(context);

      runtime.commitOps().maybeRefreshBlocking();
      assertNull(runtime.documentFieldOps().getDocumentField(key(own), SchemaFields.CONTENT));
      assertEquals("child", runtime.documentFieldOps().getDocumentField(key(nested), SchemaFields.CONTENT));
      String frozen = queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload();
      assertTrue(frozen.startsWith("{"), "legacy scope is durably converted before application");
      service.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(child.toString()).build(), CallContext.none());
      queue.close();
      try (var restarted = new SqliteJobQueue(temp.resolve("jobs.db"))) {
        restarted.open();
        var restartedContext = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(restarted, runtime, null,
            IndexingPacing.unthrottled(), base, base.resolve("active"), new ObjectMapper(),
            () -> false, () -> false, LoggerFactory.getLogger(NestedWatchedRootTest.class), Long.MAX_VALUE, "green")
            .withPublisher(service(restarted, base, runtime, runtime));
        KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(restartedContext);
        assertEquals(frozen, restarted.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload());
        runtime.commitOps().maybeRefreshBlocking();
        assertEquals("child", runtime.documentFieldOps().getDocumentField(key(nested), SchemaFields.CONTENT));
        assertEquals(1, restarted.enqueueEnumeratedFilesForGeneration("green",
            List.of(JobQueue.EnqueueEntry.stat(nested).withinRoot(child)), "child"));
        assertEquals(1, restarted.queueDepth(), "a frozen excluded path is not suppressed by P's receipt");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void migrationProjectsScopedRemovalToBothIndexesAndCertifiesWithChildJobs(int mode) throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path own = Files.writeString(parent.resolve("own.txt"), "parent");
    Path nested = Files.writeString(child.resolve("nested.txt"), "child");
    Path base = temp.resolve("index");
    var manager = new IndexGenerationManager(base);
    String blue = manager.initializeOrLoad().activeGenerationId();
    String green = manager.startMigration("nested-roots").building_generation();
    if (mode == 1) {
      Path stateFile = base.resolve("state.json");
      Files.writeString(stateFile, Files.readString(stateFile).replace("MIGRATING", "SWITCHING"));
    }
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"));
        var active = open(manager.resolveGenerationPathStrict(blue));
        var candidate = open(manager.resolveGenerationPathStrict(green))) {
      queue.open();
      var service = service(queue, base, candidate, active);
      watch(service, parent, "parent");
      watch(service, child, "child");
      for (var runtime : List.of(active, candidate)) {
        index(runtime, own, "parent");
        index(runtime, nested, "child");
        runtime.commitOps().commitAndTrack();
      }
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(nested)), "child");
      service.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(parent.toString()).build(), CallContext.none());
      assertEquals("", service.deleteByPath(DeleteByPathRequest.newBuilder()
          .setPath(parent.toString()).build(), CallContext.none()).getError());
      for (var runtime : List.of(active, candidate)) {
        runtime.commitOps().maybeRefreshBlocking();
        assertNull(runtime.documentFieldOps().getDocumentField(key(own), SchemaFields.CONTENT));
        assertEquals("child", runtime.documentFieldOps().getDocumentField(key(nested), SchemaFields.CONTENT));
        assertEquals("child", runtime.documentFieldOps().getDocumentField(key(nested), SchemaFields.COLLECTION));
      }
      assertEquals(1, queue.queueDepth());
      if (mode == 2) {
        var refusal = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, active, null,
            IndexingPacing.unthrottled(), base, active.openedIndexPath(), new ObjectMapper(),
            () -> false, () -> false, LoggerFactory.getLogger(NestedWatchedRootTest.class), Long.MAX_VALUE, green)
            .withPublisher(service);
        assertTrue(KnowledgeServerMigrationOps.drainRefusedCandidateOnSource(refusal));
        active.commitOps().maybeRefreshBlocking();
        assertEquals("child", active.documentFieldOps().getDocumentField(key(nested), SchemaFields.CONTENT));
        assertTrue(queue.matchesExpectedCollection(nested, "child"));
      } else {
        assertTrue(KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(queue,
            candidate, green, List.of(), LoggerFactory.getLogger(NestedWatchedRootTest.class)),
            "C's retained documents and pending jobs must not block P's completion certificate");
      }
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(green).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @org.junit.jupiter.api.Timeout(15)
  void workerOnlyAdditionOrRemovalWaitsUntilTheAcceptedParentDeletionCompletes(boolean removing) throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("new-child"));
    Path file = Files.writeString(child.resolve("file.txt"), "child");
    Path base = temp.resolve("index");
    var deleting = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var adding = new java.util.concurrent.CountDownLatch(1);
    var durableQueue = new SqliteJobQueue(temp.resolve("jobs.db"));
    durableQueue.open();
    try (var queue = org.mockito.Mockito.mock(SqliteJobQueue.class,
        org.mockito.Mockito.withSettings().spiedInstance(durableQueue).defaultAnswer(call -> {
          if (call.getMethod().getName().startsWith("deleteByPathPrefix")) {
            deleting.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
          }
          return call.callRealMethod();
        }));
        var runtime = open(base.resolve("active"))) {
      var service = service(queue, base, runtime, runtime);
      var registry = new RootWatcherRegistry();
      service.setRootWatcherRegistry(registry);
      if (removing) {
        watch(service, child, "child");
        index(runtime, file, "child");
        index(runtime, parent.resolve("own.txt"), "parent");
        runtime.commitOps().commitAndTrack();
      }
      var remove = new java.util.concurrent.FutureTask<>(() -> service.deleteByPath(
          DeleteByPathRequest.newBuilder().setPath(parent.toString()).build(), CallContext.none()));
      var add = new java.util.concurrent.FutureTask<Void>(() -> {
        adding.countDown();
        if (removing) {
          service.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(child.toString()).build(), CallContext.none());
          assertEquals("", service.deleteByPath(DeleteByPathRequest.newBuilder()
              .setPath(child.toString()).build(), CallContext.none()).getError());
        } else {
          watch(service, child, "child");
          service.acceptWatcherUpsert(registry.subscription(child), "child", file);
        }
        return null;
      });
      Thread removeThread = new Thread(remove);
      Thread addThread = new Thread(add);
      try {
        removeThread.start();
        assertTrue(deleting.await(5, java.util.concurrent.TimeUnit.SECONDS));
        addThread.start();
        assertTrue(adding.await(5, java.util.concurrent.TimeUnit.SECONDS));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (addThread.isAlive() && addThread.getState() != Thread.State.BLOCKED
            && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(Thread.State.BLOCKED, addThread.getState(),
            "a worker-only declaration accepted after P's snapshot waits for its deletion");
        assertEquals(0, queue.queueDepth());
        release.countDown();
        assertEquals("", remove.get(5, java.util.concurrent.TimeUnit.SECONDS).getError());
        add.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(removing ? 0 : 1, queue.queueDepth());
        if (removing) {
          runtime.commitOps().maybeRefreshBlocking();
          assertNull(runtime.documentFieldOps().getDocumentField(key(file), SchemaFields.CONTENT));
          assertNull(runtime.documentFieldOps().getDocumentField(key(parent.resolve("own.txt")), SchemaFields.CONTENT));
        } else assertTrue(queue.matchesExpectedCollection(file, "child"));
      } finally {
        release.countDown();
        removeThread.join(2_000);
        addThread.join(2_000);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void removalPreservesBothOrdersUnnestedScopeSiblingBoundaryAndRepeats(int mode) throws Exception {
    Path parent = Files.createDirectories(temp.resolve("notes_%"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path sibling = Files.createDirectories(temp.resolve("notes_%2"));
    Path own = Files.writeString(parent.resolve("own.txt"), "parent");
    Path nested = Files.writeString(child.resolve("nested.txt"), "child");
    Path beside = Files.writeString(sibling.resolve("beside.txt"), "sibling");
    Path base = temp.resolve("index");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db")); var runtime = open(base.resolve("active"))) {
      queue.open();
      var service = service(queue, base, runtime, runtime);
      watch(service, parent, "parent");
      if (mode != 2) watch(service, child, "child");
      for (Path file : List.of(own, nested, beside)) {
        index(runtime, file, "content");
        queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)), "content");
      }
      runtime.commitOps().commitAndTrack();
      if (mode == 3) service.unwatchRoot(UnwatchRootRequest.newBuilder()
          .setRootPath(child.toString()).build(), CallContext.none());
      var order = mode == 0 ? List.of(parent, child)
          : mode == 1 ? List.of(child, parent) : List.of(parent);
      for (Path root : order) {
        service.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());
        assertEquals("", service.deleteByPath(DeleteByPathRequest.newBuilder()
            .setPath(root.toString()).build(), CallContext.none()).getError());
      }
      // A repeated removal has no further document, queue or receipt effect.
      assertEquals(0, service.deleteByPath(DeleteByPathRequest.newBuilder()
          .setPath(parent.toString()).build(), CallContext.none()).getDeletedJobs());
      runtime.commitOps().maybeRefreshBlocking();
      assertNull(runtime.documentFieldOps().getDocumentField(key(own), SchemaFields.CONTENT));
      assertNull(runtime.documentFieldOps().getDocumentField(key(nested), SchemaFields.CONTENT));
      assertEquals("content", runtime.documentFieldOps().getDocumentField(key(beside), SchemaFields.CONTENT));
      assertEquals(1, queue.queueDepth());
      assertTrue(queue.matchesExpectedCollection(beside, "content"));
      assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
    }
  }

  @Test
  void removingParentRetainsHeldChildPendingSeedingAndReconciliation() throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = parent.resolve("away");
    Path base = temp.resolve("index");
    var ledger = RootVolumeLedger.under(base);
    assertTrue(ledger.beginUpgradeSeeding(List.of(parent, child)));
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db")); var runtime = open(base.resolve("active"))) {
      queue.open();
      var service = service(queue, base, runtime, runtime);
      var registry = new RootWatcherRegistry();
      service.setRootWatcherRegistry(registry);
      registry.watchWhenVolumeReturns(child, "child");
      var debt = new io.justsearch.indexerworker.queue.WatcherRootReconciliation(
          child.toString(), "held-incarnation", "child");
      assertTrue(queue.putSwitchBuffer(debt.key(), debt.OPERATION, debt.encode()));

      service.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(parent.toString()).build(), CallContext.none());
      assertEquals("", service.deleteByPath(DeleteByPathRequest.newBuilder()
          .setPath(parent.toString()).build(), CallContext.none()).getError());

      assertTrue(ledger.awaitsSeeding(child));
      assertFalse(ledger.awaitsSeeding(parent));
      assertTrue(registry.awaitsVolume(child));
      assertEquals(debt.encode(), queue.listSwitchBufferOpsStrict().getFirst().payload());
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void windowsIndexAndQueueUseIdenticalLiteralExclusionsForDriveAndUncPaths() throws Exception {
    Path base = temp.resolve("index");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db")); var runtime = open(base.resolve("active"))) {
      queue.open();
      var service = service(queue, base, runtime, runtime);
      for (String root : List.of("C:\\Notes_%", "\\\\server\\share\\Notes_%")) {
        Path parent = Path.of(root);
        Path child = parent.resolve("Child");
        Path own = parent.resolve("own.txt");
        Path nested = child.resolve("file.txt");
        Path sibling = Path.of(root + "2").resolve("sibling.txt");
        service.useExclusionRoots(() -> List.of(child));
        index(runtime, own, "parent");
        index(runtime, nested, "child");
        index(runtime, sibling, "sibling");
        runtime.commitOps().commitAndTrack();
        queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(own, 1)), "parent");
        queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(nested, 1)), "child");
        queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(sibling, 1)), "sibling");

        assertEquals("", service.deleteByPath(DeleteByPathRequest.newBuilder()
            .setPath(root.toUpperCase(java.util.Locale.ROOT).replace('\\', '/') + "//")
            .build(), CallContext.none()).getError());

        runtime.commitOps().maybeRefreshBlocking();
        assertNull(runtime.documentFieldOps().getDocumentField(key(own), SchemaFields.CONTENT));
        assertEquals("child", runtime.documentFieldOps().getDocumentField(key(nested), SchemaFields.CONTENT));
        assertEquals("sibling", runtime.documentFieldOps().getDocumentField(key(sibling), SchemaFields.CONTENT));
        assertFalse(queue.matchesExpectedCollection(own, "parent"));
        assertTrue(queue.matchesExpectedCollection(nested, "child"));
        assertTrue(queue.matchesExpectedCollection(sibling, "sibling"));
      }
    }
  }

  private RunningRuntime open(Path path) throws Exception {
    return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(path)
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static WorkerIngestService service(SqliteJobQueue queue, Path base,
      RunningRuntime writer, RunningRuntime serving) {
    return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), base,
        writer.openedIndexPath(), writer, serving, null, 0L);
  }

  private static void watch(WorkerIngestService service, Path root, String collection) {
    assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString())
        .setCollection(collection).setRootAdded(true).build(), CallContext.none()).getWatching());
  }

  private static String key(Path file) { return PathNormalizer.normalizeKey(file); }

  private static void index(RunningRuntime runtime, Path file, String collection) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, key(file), SchemaFields.DOC_UID, java.util.UUID.randomUUID().toString(),
        SchemaFields.PATH, key(file), SchemaFields.CONTENT, collection, SchemaFields.COLLECTION, collection)));
  }
}
