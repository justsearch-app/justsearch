/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.DeleteByCollectionRequest;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.SyncDirectoryRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

final class WorkerIngestServiceCandidateReconciliationTest {
  @TempDir Path tempDir;

  @Test
  void migratingRootUsesServingDiscoveryAndExactCandidateWatcherRoutes() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("root"));
    Path added = Files.writeString(root.resolve("added.txt"), "added");
    String deleted = PathNormalizer.normalizeKey(root.resolve("deleted.txt"));
    Fixture fixture = fixture(deleted);
    watch(fixture, root);

    var response = fixture.service.syncDirectory(request(root), CallContext.none());

    assertEquals(1, response.getFilesAdded());
    assertEquals(1, response.getFilesDeleted());
    verify(fixture.queue).enqueueAndBufferFileForGeneration(
        eq(fixture.buildingGeneration), any(JobQueue.EnqueueEntry.class), eq("research"));
    verify(fixture.queue).putSwitchBufferForGeneration(
        fixture.buildingGeneration, IngestResponses.switchBufferPathKey(deleted), "DELETE", deleted);
    verify(fixture.servingWriter).deleteByIdAndChunks(deleted);
    verify(fixture.candidateWriter).deleteByIdAndChunks(deleted);
    verify(fixture.servingCommit).commitAndTrack(
        io.justsearch.adapters.lucene.runtime.CommitReason.WATCHER_DELETE);
    assertTrue(fixture.admission.replayCertain());
    assertTrue(Files.exists(added));
    assertFalse(Files.exists(Path.of(deleted)));
  }

  @Test
  void incompleteServingScanLatchesReplayUncertainty() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("failed-root"));
    Fixture fixture = fixture();
    watch(fixture, root);
    when(fixture.reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenThrow(new IllegalStateException("index unavailable"));

    WorkerServiceException failure = assertThrows(
        WorkerServiceException.class,
        () -> fixture.service.syncDirectory(request(root), CallContext.none()));

    assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
    assertFalse(fixture.admission.replayCertain());
  }

  @Test
  void sameRuntimePreGreenDeleteRefusesWhenServingRuntimeIsUnavailable() throws Exception {
    Fixture fixture = sameRuntimeFixture(false);
    String path = PathNormalizer.normalizeKey(tempDir.resolve("unavailable-delete.txt"));

    assertThrows(
        WorkerServiceException.class,
        () -> fixture.service.deleteById(
            DeleteByIdRequest.newBuilder().setDocId(path).build(), CallContext.none()));
    verify(fixture.candidateWriter, never()).deleteByIdAndChunks(any());
    verify(fixture.queue, never()).deleteByExactPath(any());
    verify(fixture.queue, never()).putSwitchBufferForGeneration(any(), any(), any(), any());
    assertTrue(fixture.admission.replayCertain(), "refusal before journal/effect keeps replay certain");
  }

  @Test
  void sameRuntimePreGreenJournalsBeforeExactlyOneServingEffectForEachDelete() throws Exception {
    Fixture fixture = sameRuntimeFixture(true);
    String watched = PathNormalizer.normalizeKey(tempDir.resolve("strict-watcher-delete.txt"));
    String direct = PathNormalizer.normalizeKey(tempDir.resolve("strict-direct-delete.txt"));
    String prefix = PathNormalizer.normalizeKey(tempDir.resolve("strict-prefix-delete"));
    String normalizedPrefix = IngestResponses.resolveNormalizedPathPrefix(prefix);
    String collection = "strict-pre-green";
    when(fixture.candidateWriter.deleteByCollection(collection)).thenReturn(1);

    InOrder order = inOrder(fixture.queue, fixture.candidateWriter, fixture.servingCommit);
    fixture.service.acceptWatcherDelete(watched, () ->
        fixture.candidateWriter.deleteByIdAndChunks(watched));
    var directResponse = fixture.service.deleteById(
        DeleteByIdRequest.newBuilder().setDocId(direct).build(), CallContext.none());
    var prefixResponse = fixture.service.deleteByPath(
        DeleteByPathRequest.newBuilder().setPath(prefix).build(), CallContext.none());
    var collectionResponse = fixture.service.deleteByCollection(
        DeleteByCollectionRequest.newBuilder().setCollection(collection).build(),
        CallContext.none());

    assertTrue(directResponse.getSuccess(), directResponse.getError());
    assertEquals("", prefixResponse.getError());
    assertEquals("", collectionResponse.getError());
    assertEquals(1, collectionResponse.getDeletedDocs());

    order.verify(fixture.queue).putSwitchBufferForGeneration(
        fixture.buildingGeneration, IngestResponses.switchBufferPathKey(watched), "DELETE", watched);
    order.verify(fixture.candidateWriter).deleteByIdAndChunks(watched);
    order.verify(fixture.queue).putSwitchBufferForGeneration(
        fixture.buildingGeneration, IngestResponses.switchBufferPathKey(direct), "DELETE", direct);
    order.verify(fixture.candidateWriter).deleteByIdAndChunks(direct);
    order.verify(fixture.queue).deleteByExactPath(direct);
    order.verify(fixture.servingCommit).commitAndTrack(
        io.justsearch.adapters.lucene.runtime.CommitReason.GRPC_DELETE_BY_ID);
    order.verify(fixture.queue).putSwitchBufferForGeneration(
        fixture.buildingGeneration, IngestResponses.switchBufferPrefixKey(normalizedPrefix),
        "DELETE_PREFIX", normalizedPrefix);
    order.verify(fixture.candidateWriter).deleteByPathPrefix(prefix);
    order.verify(fixture.queue).deleteByPathPrefix(prefix);
    order.verify(fixture.servingCommit).commitAndTrack(
        io.justsearch.adapters.lucene.runtime.CommitReason.GRPC_DELETE_BY_PATH);
    order.verify(fixture.queue).putSwitchBufferForGeneration(
        fixture.buildingGeneration, "collection:" + collection, "DELETE_COLLECTION", collection);
    order.verify(fixture.candidateWriter).deleteByCollection(collection);
    order.verify(fixture.servingCommit).commitAndTrack(
        io.justsearch.adapters.lucene.runtime.CommitReason.GRPC_DELETE_BY_COLLECTION);

    verify(fixture.candidateWriter, times(1)).deleteByIdAndChunks(watched);
    verify(fixture.candidateWriter, times(1)).deleteByIdAndChunks(direct);
    verify(fixture.candidateWriter, times(1)).deleteByPathPrefix(prefix);
    verify(fixture.candidateWriter, times(1)).deleteByCollection(collection);
    verify(fixture.servingCommit, times(3)).commitAndTrack(any());
    verifyNoMoreInteractions(fixture.queue, fixture.candidateWriter, fixture.servingCommit);
  }

  @Test
  void rootRemovedFromWatcherRegistryDuringScanFailsClosed() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("unwatched-root"));
    Fixture fixture = fixture();
    RootWatcherRegistry registry = watch(fixture, root);
    when(fixture.reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenAnswer(ignored -> {
          assertTrue(registry.unwatch(root.toString()));
          return new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0L);
        });

    WorkerServiceException failure = assertThrows(
        WorkerServiceException.class,
        () -> fixture.service.syncDirectory(request(root), CallContext.none()));

    assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
    assertFalse(fixture.admission.replayCertain());
  }

  @Test
  void forceReadmitsAnExistingServingPathToTheScopedCandidateJournal() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("forced-root"));
    Path existing = Files.writeString(root.resolve("existing.txt"), "changed bytes");
    String normalized = PathNormalizer.normalizeKey(existing);

    Fixture ordinary = fixture(normalized);
    watch(ordinary, root);
    assertEquals(0,
        ordinary.service.syncDirectory(request(root, false), CallContext.none()).getFilesAdded());
    verify(ordinary.queue, never()).enqueueAndBufferFileForGeneration(
        eq(ordinary.buildingGeneration), any(JobQueue.EnqueueEntry.class),
        nullable(String.class));

    Fixture forced = fixture(normalized);
    watch(forced, root);
    assertEquals(1,
        forced.service.syncDirectory(request(root, true), CallContext.none()).getFilesAdded());
    verify(forced.queue).enqueueAndBufferFileForGeneration(
        eq(forced.buildingGeneration), any(JobQueue.EnqueueEntry.class),
        nullable(String.class));
  }

  @Test
  void finalFenceCannotBisectCandidateSnapshotAndAdmission() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("held-root"));
    Files.writeString(root.resolve("added.txt"), "added");
    Fixture fixture = fixture();
    watch(fixture, root);
    CountDownLatch scanEntered = new CountDownLatch(1);
    CountDownLatch finishScan = new CountDownLatch(1);
    when(fixture.reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenAnswer(ignored -> {
          scanEntered.countDown();
          assertTrue(finishScan.await(5, TimeUnit.SECONDS));
          return new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0L);
        });

    try (var executor = Executors.newSingleThreadExecutor()) {
      var reconciliation = executor.submit(
          () -> fixture.service.syncDirectory(request(root), CallContext.none()));
      assertTrue(scanEntered.await(5, TimeUnit.SECONDS));
      assertNull(fixture.admission.beginFinalFence(fixture.owner, 25));
      finishScan.countDown();
      assertEquals(1, reconciliation.get(5, TimeUnit.SECONDS).getFilesAdded());
      try (var fence = fixture.admission.beginFinalFence(fixture.owner, 1_000)) {
        assertTrue(fence != null);
      }
    }
  }

  @Test
  @Timeout(10)
  void rewatchThatCrossesMigrationStartInvalidatesReplay() throws Exception {
    registrationChangeThatCrossesMigrationStart(false);
  }

  @Test
  @Timeout(10)
  void unwatchThatCrossesMigrationStartInvalidatesReplay() throws Exception {
    registrationChangeThatCrossesMigrationStart(true);
  }

  private void registrationChangeThatCrossesMigrationStart(boolean unwatch) throws Exception {
    Path root = Files.createDirectory(tempDir.resolve(unwatch ? "late-unwatch" : "late-rewatch"));
    Fixture fixture = fixtureBeforeMigration();
    RootWatcherRegistry registry = watch(fixture, root);
    RootWatcherRegistry.Subscription old = registry.subscription(root);
    CountDownLatch eventEntered = new CountDownLatch(1);
    CountDownLatch releaseEvent = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      try {
      var event = executor.submit(() -> fixture.service.acceptWatcherEvent(old, () -> {
        eventEntered.countDown();
        try {
          assertTrue(releaseEvent.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }));
      assertTrue(eventEntered.await(5, TimeUnit.SECONDS));
      var change = executor.submit(() -> {
        if (unwatch) {
          return fixture.service.unwatchRoot(
              io.justsearch.ipc.UnwatchRootRequest.newBuilder()
                  .setRootPath(root.toString()).build(), CallContext.none()).getUnwatched();
        }
        return fixture.service.watchRoot(
            io.justsearch.ipc.WatchRootRequest.newBuilder()
                .setRootPath(root.toString()).setCollection("research").build(),
            CallContext.none()).getWatching();
      });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (registry.subscription(root) != null && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertNull(registry.subscription(root),
          "registration mutation must pause after its precheck and before its drain");
      fixture.generations.startMigration("registration-change-test");
      releaseEvent.countDown();
      event.get(5, TimeUnit.SECONDS);
      assertTrue(change.get(5, TimeUnit.SECONDS));
      } finally {
        releaseEvent.countDown();
      }
    }
    assertFalse(fixture.admission.replayCertain(),
        "post-mutation state check must see a migration that started during the drain");
  }

  private static RootWatcherRegistry watch(Fixture fixture, Path root) {
    RootWatcherRegistry registry = new RootWatcherRegistry();
    assertTrue(registry.watch(root.toString(), "research").watching());
    fixture.service.setRootWatcherRegistry(registry);
    return registry;
  }

  private Fixture fixture(String... indexedPaths) throws Exception {
    return fixture(true, indexedPaths);
  }

  private Fixture fixtureBeforeMigration() throws Exception {
    return fixture(false);
  }

  private Fixture sameRuntimeFixture(boolean acceptingWrites) throws Exception {
    Path base = tempDir.resolve("same-runtime-unavailable-" + java.util.UUID.randomUUID());
    var generations = new IndexGenerationManager(base);
    var initial = generations.initializeOrLoad();
    String building = generations.startMigration("same-runtime-unavailable-test").building_generation();
    ReadPathOps reads = mock(ReadPathOps.class);
    RunningRuntime runtime = mock(RunningRuntime.class);
    when(runtime.isAcceptingWrites()).thenReturn(acceptingWrites);
    IndexingCoordinator writer = mock(IndexingCoordinator.class);
    when(runtime.indexingCoordinator()).thenReturn(writer);
    CommitOps commit = mock(CommitOps.class);
    when(runtime.commitOps()).thenReturn(commit);
    SwitchBufferCapableQueue queue = mock(SwitchBufferCapableQueue.class);
    when(queue.putSwitchBufferForGeneration(eq(building), any(), any(), any()))
        .thenReturn(true);
    var service = new WorkerIngestService(
        queue, null, null, IndexingPacing.unthrottled(), base,
        generations.resolveGenerationPathStrict(initial.activeGenerationId()),
        runtime, runtime, null, 0L);
    Object owner = new Object();
    var admission = new WorkerMutationAdmission(owner);
    service.setMutationAdmission(admission, owner);
    return new Fixture(
        service, queue, reads, writer, writer, commit, admission, owner, building, generations);
  }

  private Fixture fixture(boolean startMigration, String... indexedPaths) throws Exception {
    Path base = tempDir.resolve("index-" + java.util.UUID.randomUUID());
    var generations = new IndexGenerationManager(base);
    var initial = generations.initializeOrLoad();
    String building = startMigration
        ? generations.startMigration("candidate-root-test").building_generation() : null;
    ReadPathOps reads = mock(ReadPathOps.class);
    List<LuceneRuntimeTypes.SearchHit> hits = java.util.Arrays.stream(indexedPaths)
        .map(path -> new LuceneRuntimeTypes.SearchHit(
            path, 1.0f, Map.of(SchemaFields.PATH, path)))
        .toList();
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(hits, hits.size(), 0L));

    RunningRuntime candidate = mock(RunningRuntime.class);
    RunningRuntime serving = mock(RunningRuntime.class);
    when(candidate.isAcceptingWrites()).thenReturn(true);
    IndexingCoordinator candidateWriter = mock(IndexingCoordinator.class);
    when(candidate.indexingCoordinator()).thenReturn(candidateWriter);
    when(serving.readPathOps()).thenReturn(reads);
    when(serving.isAcceptingWrites()).thenReturn(true);
    IndexingCoordinator servingWriter = mock(IndexingCoordinator.class);
    CommitOps servingCommit = mock(CommitOps.class);
    when(serving.indexingCoordinator()).thenReturn(servingWriter);
    when(serving.commitOps()).thenReturn(servingCommit);
    SwitchBufferCapableQueue queue = mock(SwitchBufferCapableQueue.class);
    when(queue.enqueueAndBufferFileForGeneration(
            eq(building), any(), nullable(String.class)))
        .thenReturn(true);
    when(queue.putSwitchBufferForGeneration(eq(building), any(), eq("DELETE"), any()))
        .thenReturn(true);
    var service = new WorkerIngestService(
        queue, null, null, IndexingPacing.unthrottled(), base,
        generations.resolveGenerationPathStrict(initial.activeGenerationId()),
        candidate, serving, null, 0L);
    Object owner = new Object();
    var admission = new WorkerMutationAdmission(owner);
    service.setMutationAdmission(admission, owner);
    return new Fixture(
        service, queue, reads, candidateWriter, servingWriter, servingCommit, admission, owner,
        building, generations);
  }

  private static SyncDirectoryRequest request(Path root) {
    return request(root, false);
  }

  private static SyncDirectoryRequest request(Path root, boolean force) {
    return SyncDirectoryRequest.newBuilder()
        .setRootPath(root.toString())
        .setForce(force)
        .build();
  }

  private record Fixture(
      WorkerIngestService service,
      SwitchBufferCapableQueue queue,
      ReadPathOps reads,
      IndexingCoordinator candidateWriter,
      IndexingCoordinator servingWriter,
      CommitOps servingCommit,
      WorkerMutationAdmission admission,
      Object owner,
      String buildingGeneration,
      IndexGenerationManager generations) {}
}
