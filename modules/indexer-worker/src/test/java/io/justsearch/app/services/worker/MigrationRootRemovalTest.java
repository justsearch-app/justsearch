/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.DefaultWorkerAppServices;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.NoVolumeIdentityExtension;
import io.justsearch.indexerworker.services.RootWatcherRegistry;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerMutationAdmission;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.UnwatchRootRequest;
import io.justsearch.ipc.WatchRootRequest;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Engine root lifecycle through Worker admission, the SQLite journal and both Lucene writers. */
@ExtendWith(NoVolumeIdentityExtension.class)
final class MigrationRootRemovalTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"MIGRATING", "AWAITING_ACCEPTANCE", "ENUMERATING", "PLAIN"})
  void explicitRemovalCertifiesWithoutLosingRetainedRoots(String phase) throws Exception {
    try (var f = new Fixture(phase)) {
      long removalMark = f.worker.rootRemovalMark();
      assertEquals(1, f.ops.removeWatchedPath(f.parent, context()));
      assertTrue(f.admission.runtimeReplayCertain(),
          "an explicit journalled removal must not trip the unrecorded-watcher latch");
      assertTrue(f.worker.rootRemovedSince(f.parent, removalMark));
      assertFalse(f.worker.rootRemovedSince(f.child, removalMark));
      assertEquals(0, f.ops.removeWatchedPath(f.parent, context()), "duplicate removal is harmless");
      f.assertSurvivors();
      assertEquals(0, f.active.indexCountOps().countByIdAndChunksStrict(key(f.own)));
      List<ResolvedConfig.FileSource> current = assertDoesNotThrow(f::promotionRoots,
          "a completed scoped removal must pass the enumeration declaration check");
      assertFalse(current.stream().anyMatch(root -> root.path().equals(f.parent)));
      f.manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
      try (var fence = f.admission.beginFinalFence(f.writer, 1_000)) {
        assertNotNull(fence);
        assertTrue(f.admission.replayCertain());
        var replay = KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
            new KnowledgeServerMigrationOps.DrainSwitchBufferContext(f.queue, f.candidate, null,
                IndexingPacing.unthrottled(), f.base, f.candidate.openedIndexPath(), new ObjectMapper(),
                () -> false, () -> false, LoggerFactory.getLogger(getClass()), Long.MAX_VALUE, f.green)
                .withPublisher(f.worker).withMigrationRoots(current));
        assertTrue(replay.isPresent(), "the recorded scoped deletion must replay on the candidate");
        try (var promotion = f.manager.beginNativePromotion(f.blue, f.green)) {
          assertEquals(f.green, promotion.promote().active_generation());
        }
        assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay.orElseThrow()));
      }
      f.assertRetained();
    }
  }

  @Test
  void ordinaryUnwatchStillLatchesAndExplicitRemovalCannotClearIt() throws Exception {
    try (var f = new Fixture("MIGRATING")) {
      f.worker.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(f.parent.toString()).build(), CallContext.none());
      assertFalse(f.admission.runtimeReplayCertain());
      assertEquals(1, f.ops.removeWatchedPath(f.parent, context()));
      assertFalse(f.admission.runtimeReplayCertain(), "a later receipt cannot clear existing uncertainty");
      f.assertRetained();
    }
  }

  @Test
  void persistedIntentBeforeWorkerEntryRefusesSafelyAndKeepsRemovalRetryable() throws Exception {
    try (var f = new Fixture("MIGRATING")) {
      f.state.beginRemoval(f.parent);
      // Reconstruct the Worker and authored roots, then run the real bootstrap catch-up path.
      var restarted = new WorkerIngestService(f.queue, null, null, IndexingPacing.unthrottled(), f.base,
          f.writer.openedIndexPath(), f.writer, f.active, null, 0L);
      restarted.setDeclaredRootsDirectory(f.data);
      var restartedAdmission = new WorkerMutationAdmission(f.writer);
      restarted.setMutationAdmission(restartedAdmission, f.writer);
      var restoredRoots = new ConcurrentHashMap<Path, Instant>();
      var restoredState = new WatchedRootsState(restoredRoots,
          new WatchedRootsStore(f.data.resolve("watched_roots.json"), null));
      restoredState.loadPersistedRoots();
      var retry = operations(restoredRoots, restoredState, restarted, new AtomicBoolean());
      retry.reindexPersistedRoots(context());
      var producer = mock(DefaultWorkerAppServices.class);
      when(producer.mutationAdmission()).thenReturn(restartedAdmission);
      when(producer.mutationOwnerToken()).thenReturn(f.writer);
      when(producer.ingestService()).thenReturn(restarted);
      f.field("appServices", producer);
      try {
        f.enterSwitching();
      } catch (IOException safeRefusal) {
        assertFalse(restartedAdmission.runtimeReplayCertain());
      }
      assertTrue(List.of("MIGRATING", "FAILED").contains(f.manager.readStateBestEffort().migration_state()),
          "persisted removal intent without a receipt must not enter cutover after restart");
      assertTrue(f.queue.listSwitchBufferOpsStrictForGeneration(f.green).isEmpty());
      assertEquals(1, retry.removeWatchedPath(f.parent, context()));
      f.roots.remove(f.parent);
      assertThrows(IOException.class, f::enterSwitching,
          "bootstrap catch-up retains its existing permanent replay-uncertain latch");
      assertEquals("FAILED", f.manager.readStateBestEffort().migration_state());
      assertFalse(restoredRoots.containsKey(f.parent));
      assertFalse(restoredState.isRemovalIncomplete(f.parent));
      assertEquals(f.blue, f.manager.readStateBestEffort().active_generation());
      f.assertRetained();
      f.field("appServices", null);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"journal", "commit", "queue", "response"})
  void failureKeepsRemovalRetryableAndRetainedRootsIntact(String point) throws Exception {
    try (var f = new Fixture("MIGRATING")) {
      if (point.equals("journal")) {
        doReturn(false).when(f.queue).putSwitchBufferForGeneration(anyString(), anyString(), eq("DELETE_PREFIX"), anyString());
      } else if (point.equals("commit")) {
        doThrow(new IllegalStateException("commit cut")).when(f.candidateCommits).commitAndTrack(CommitReason.GRPC_DELETE_BY_PATH);
      } else if (point.equals("queue")) {
        doReturn(-1).when(f.queue).deleteByPathPrefixExcludingAcceptedSurvivors(anyString(), anyList(), anyList());
      } else {
        f.loseResponse.set(true);
      }
      assertEquals(-1, f.ops.removeWatchedPath(f.parent, context()));
      assertTrue(f.roots.containsKey(f.parent));
      assertTrue(f.state.isRemovalIncomplete(f.parent));
      assertTrue(f.admission.runtimeReplayCertain(), "a retry must not inherit a permanent watcher latch");
      f.assertSurvivors();
      doCallRealMethod().when(f.queue).putSwitchBufferForGeneration(anyString(), anyString(), anyString(), anyString());
      doCallRealMethod().when(f.queue).deleteByPathPrefixExcludingAcceptedSurvivors(anyString(), anyList(), anyList());
      doCallRealMethod().when(f.candidateCommits).commitAndTrack(CommitReason.GRPC_DELETE_BY_PATH);
      assertTrue(f.ops.removeWatchedPath(f.parent, context()) >= 0);
      assertDoesNotThrow(f::promotionRoots);
      f.assertRetained();
    }
  }

  @Test
  void delayedCallbacksAndEnumerationCannotResurrectRemovedRoot() throws Exception {
    try (var f = new Fixture("ENUMERATING")) {
      var stale = f.subscription(f.parent);
      long mark = f.worker.rootRemovalMark();
      assertEquals(1, f.ops.removeWatchedPath(f.parent, context()));
      var receipts = f.queue.listSwitchBufferOpsStrict();
      f.worker.acceptWatcherEvent(stale, () -> fail("retired callback must not execute"));
      assertFalse(f.worker.reconcileReturnedBootRoot(new RootBinding(f.parent, "parent"), mark, () -> false));
      // The return value counts covered entries, including superseded enumeration. Observe
      // the queue and journal instead: neither may resurrect this removed file.
      assertEquals(1, f.queue.enqueueEnumeratedFilesForGeneration(f.green,
          List.of(JobQueue.EnqueueEntry.stat(f.own).withinRoot(f.parent)), "parent"));
      assertNull(f.queue.watcherReconciliationRow(key(f.own)));
      assertEquals(f.survivors.size(), f.queue.queueDepth());
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(f.queue, f.candidate, null,
              IndexingPacing.unthrottled(), f.base, f.candidate.openedIndexPath(), new ObjectMapper(),
              () -> false, () -> false, LoggerFactory.getLogger(getClass()), Long.MAX_VALUE, f.green)
              .withPublisher(f.worker));
      f.assertRetained();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"MIGRATING", "ENUMERATING", "AWAITING_ACCEPTANCE"})
  void replayAndRetiredCallbacksPreserveLaterLegitimateReaddition(String phase) throws Exception {
    try (var f = new Fixture(phase)) {
      var stale = f.subscription(f.parent);
      assertEquals(1, f.ops.removeWatchedPath(f.parent, context()));
      f.register(f.parent, "parent");
      f.state.persist();
      var current = f.subscription(f.parent);
      f.worker.acceptWatcherEvent(current, () -> f.worker.acceptWatcherUpsert(current, "parent", f.own));
      for (var runtime : List.of(f.active, f.candidate)) {
        index(runtime, f.own, "parent");
        runtime.commitOps().commitAndTrack();
      }
      f.queue.markDone(f.own, IngestionOutcome.of(
          IngestionOutcomeClass.SUCCESS_FULL, "INDEXED", IngestionRetryPolicy.NONE));
      var receipts = f.queue.listSwitchBufferOpsStrict();
      f.worker.acceptWatcherEvent(stale, () -> fail("retired callback cannot erase a later registration"));
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(f.queue, f.candidate, null,
              IndexingPacing.unthrottled(), f.base, f.candidate.openedIndexPath(), new ObjectMapper(),
              () -> false, () -> false, LoggerFactory.getLogger(getClass()), Long.MAX_VALUE, f.green)
              .withPublisher(f.worker));
      var producer = mock(DefaultWorkerAppServices.class);
      when(producer.mutationAdmission()).thenReturn(f.admission);
      when(producer.mutationOwnerToken()).thenReturn(f.writer);
      when(producer.ingestService()).thenReturn(f.worker);
      f.field("appServices", producer);
      assertThrows(IOException.class, f::enterSwitching,
          "a removal pending replay must not erase a later registration through cutover");
      assertEquals("FAILED", f.manager.readStateBestEffort().migration_state());
      assertEquals(f.blue, f.manager.readStateBestEffort().active_generation());
      f.active.commitOps().maybeRefreshBlocking();
      assertEquals(2, f.active.indexCountOps().countByIdAndChunksStrict(key(f.own)));
      assertEquals("parent", f.active.documentFieldOps().getDocumentField(key(f.own), SchemaFields.COLLECTION));
      assertTrue(f.queue.matchesExpectedCollection(f.own, "parent"));
      assertNotNull(f.subscription(f.parent));
      f.assertSurvivors();
    }
  }

  @Test
  void removalCannotSpendTheRemainingSwitchingDeadline() throws Exception {
    try (var f = new Fixture("SWITCHING")) {
      // An older incomplete attempt must remain fail-closed even after a zero-effect refusal.
      f.state.beginRemoval(f.parent);
      doAnswer(invocation -> {
        // A deterministic cut during removal's commit models the deadline expiring while
        // promotion is fenced out. No wall-clock wait or deadline extension is used.
        Path stateFile = f.base.resolve("state.json");
        var json = new ObjectMapper();
        var state = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readString(stateFile));
        // Zero is a legacy timestamp that the reader upgrades to the current time.
        state.put("updated_at_ms", 1L);
        Files.writeString(stateFile, json.writeValueAsString(state));
        KnowledgeServerMigrationOps.runMigrationCutoverLoop(
            new KnowledgeServerMigrationOps.CutoverContext(f.manager, f.queue, () -> true,
                () -> true, () -> null, 100, 30L * 60_000L, -1, () -> f.candidate,
                () -> true, () -> {}, LoggerFactory.getLogger(getClass()), ignored -> {},
                () -> { throw new AssertionError("expired cutover must not publish"); }));
        return invocation.callRealMethod();
      }).when(f.candidateCommits).commitAndTrack(CommitReason.GRPC_DELETE_BY_PATH);
      var refusal = assertThrows(io.justsearch.app.api.knowledge.KnowledgeClientException.class,
          () -> f.ops.removeWatchedPath(f.parent, context()));
      assertEquals(io.justsearch.app.api.knowledge.KnowledgeClientException.Status.UNAVAILABLE, refusal.status());
      assertEquals("Removal did not complete because the index is rebuilding. "
          + "Retry Remove when the rebuild finishes.", refusal.getMessage());
      assertNotEquals("FAILED", f.manager.readStateBestEffort().migration_state(),
          "removal must complete or be deferred before it can consume the SWITCHING deadline");
      verify(f.candidateCommits, never()).commitAndTrack(CommitReason.GRPC_DELETE_BY_PATH);
      assertTrue(f.roots.containsKey(f.parent));
      assertTrue(f.state.isRemovalIncomplete(f.parent));
      assertEquals(WatchedRootsState.REMOVAL_INCOMPLETE, new WatchedRootsStore(
          f.data.resolve("watched_roots.json"), null).loadPersistedRootsWithErrors().walkErrors().get(f.parent));
      assertNotNull(f.subscription(f.parent));
      f.assertSurvivors();
    }
  }

  @Test
  void refusedSwitchingRemovalAllowsCutoverAndPostSwitchRetry() throws Exception {
    try (var f = new Fixture("SWITCHING")) {
      var watcher = f.subscription(f.parent);
      var receipts = f.queue.listSwitchBufferOpsStrict();
      var refusal = assertThrows(io.justsearch.app.api.knowledge.KnowledgeClientException.class,
          () -> f.ops.removeWatchedPath(f.parent, context()));
      assertEquals(io.justsearch.app.api.knowledge.KnowledgeClientException.Status.UNAVAILABLE, refusal.status());
      assertEquals("Removal did not complete because the index is rebuilding. "
          + "Retry Remove when the rebuild finishes.", refusal.getMessage());
      assertFalse(f.state.isRemovalIncomplete(f.parent), "a refused fresh attempt must not block promotion");
      assertFalse(new WatchedRootsStore(f.data.resolve("watched_roots.json"), null)
          .loadPersistedRootsWithErrors().walkErrors().containsValue(WatchedRootsState.REMOVAL_INCOMPLETE),
          "no durable removal intent may remain after the acknowledged zero-effect refusal");
      assertTrue(f.roots.containsKey(f.parent));
      assertSame(watcher, f.subscription(f.parent));
      assertTrue(f.admission.runtimeReplayCertain());
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict(), "refusal must not journal a deletion");
      verify(f.candidateCommits, never()).commitAndTrack(CommitReason.GRPC_DELETE_BY_PATH);
      for (var runtime : List.of(f.active, f.candidate)) {
        runtime.commitOps().maybeRefreshBlocking();
        assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(key(f.own)));
      }
      f.assertSurvivors();
      var current = assertDoesNotThrow(f::promotionRoots);
      try (var fence = f.admission.beginFinalFence(f.writer, 1_000)) {
        assertNotNull(fence);
        assertTrue(f.admission.replayCertain());
        var replay = KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
            new KnowledgeServerMigrationOps.DrainSwitchBufferContext(f.queue, f.candidate, null,
                IndexingPacing.unthrottled(), f.base, f.candidate.openedIndexPath(), new ObjectMapper(),
                () -> false, () -> false, LoggerFactory.getLogger(getClass()), Long.MAX_VALUE, f.green)
                .withPublisher(f.worker).withMigrationRoots(current));
        assertTrue(replay.isPresent());
        try (var promotion = f.manager.beginNativePromotion(f.blue, f.green)) {
          assertEquals(f.green, promotion.promote().active_generation());
        }
        assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay.orElseThrow()));
      }
      assertEquals("IDLE", f.manager.readStateBestEffort().migration_state());
      assertTrue(f.roots.containsKey(f.parent));
      assertSame(watcher, f.subscription(f.parent));
      // The successor serves and writes the promoted runtime, retaining the same watcher registry.
      var successor = new WorkerIngestService(f.queue, null, null, IndexingPacing.unthrottled(), f.base,
          f.candidate.openedIndexPath(), f.candidate, f.candidate, null, 0L);
      successor.setDeclaredRootsDirectory(f.data);
      successor.setRootWatcherRegistry(f.registry);
      successor.setMutationAdmission(new WorkerMutationAdmission(f.candidate), f.candidate);
      var retry = operations(f.roots, f.state, successor, new AtomicBoolean());
      assertEquals(1, retry.removeWatchedPath(f.parent, context()));
      assertFalse(f.roots.containsKey(f.parent));
      assertNull(f.subscription(f.parent));
      assertNotNull(f.subscription(f.child));
      assertEquals(0, f.candidate.indexCountOps().countByIdAndChunksStrict(key(f.own)));
      f.assertSurvivors();
      assertEquals(f.survivors.size(), f.queue.queueDepth());
    }
  }

  private static EngineContext context() {
    return EngineProvenance.internal("migration-removal-test", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }

  private final class Fixture implements AutoCloseable {
    final Path data = Files.createDirectories(directory.resolve("data"));
    final Path base = data.resolve("index");
    final Path parent = Files.createDirectories(directory.resolve("parent"));
    final Path child = Files.createDirectories(parent.resolve("child"));
    final Path sibling = Files.createDirectories(directory.resolve("parent2"));
    final Path sameCollection = Files.createDirectories(directory.resolve("same-collection"));
    final Path own = Files.writeString(parent.resolve("own.txt"), "removed");
    final List<Path> survivors;
    final IndexGenerationManager manager = new IndexGenerationManager(base);
    final String blue = manager.initializeOrLoad().activeGenerationId();
    final String green = manager.startMigration("root-removal").building_generation();
    final RunningRuntime active = spy(open(manager.resolveGenerationPathStrict(blue)));
    final RunningRuntime candidate = spy(open(manager.resolveGenerationPathStrict(green)));
    final CommitOps candidateCommits = spy(candidate.commitOps());
    final RunningRuntime writer;
    final SqliteJobQueue queue;
    final Map<Path, Instant> roots = new ConcurrentHashMap<>();
    final WatchedRootsState state = new WatchedRootsState(roots,
        new WatchedRootsStore(data.resolve("watched_roots.json"), null));
    final RootWatcherRegistry registry = new RootWatcherRegistry();
    final WorkerIngestService worker;
    final WorkerMutationAdmission admission;
    final RootLifecycleOps ops;
    final ResolvedConfig configuration = new ResolvedConfigBuilder()
        .putDefault("justsearch.data.dir", data.toString()).build();
    final TestEngineExecutors serverExecutors = new TestEngineExecutors();
    final KnowledgeServer server = new KnowledgeServer(serverExecutors,
        new WorkerConfig(data, 60_000, "test", Map.of(), "test", 500));
    final AtomicBoolean loseResponse = new AtomicBoolean();

    Fixture(String phase) throws Exception {
      var openedQueue = new SqliteJobQueue(data.resolve("jobs.db"));
      openedQueue.open();
      queue = spy(openedQueue);
      doReturn(candidateCommits).when(candidate).commitOps();
      writer = phase.equals("ENUMERATING") ? active : candidate;
      worker = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), base,
          writer.openedIndexPath(), writer, active, null, 0L);
      worker.setRootWatcherRegistry(registry);
      worker.setDeclaredRootsDirectory(data);
      var retained = new ArrayList<Path>();
      if (!phase.equals("PLAIN")) retained.add(child);
      retained.add(sibling);
      retained.add(sameCollection);
      register(parent, "parent");
      for (Path root : retained) register(root, root.equals(sameCollection) ? "parent" : root.getFileName().toString());
      state.persist();
      admission = new WorkerMutationAdmission(writer);
      worker.setMutationAdmission(admission, writer);
      survivors = new ArrayList<>();
      for (Path root : retained) {
        Path file = Files.writeString(root.resolve("kept.txt"), "kept");
        survivors.add(file);
        queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)), state.getCollection(root));
      }
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(own)), "parent");
      for (var runtime : List.of(active, candidate)) {
        index(runtime, own, "parent");
        for (Path file : survivors) index(runtime, file, state.getCollection(file.getParent()));
        runtime.commitOps().commitAndTrack();
      }
      field("indexGenerationManager", manager);
      field("jobQueue", queue);
      field("migrationRootSnapshot", KnowledgeServerMigrationOps.loadMigrationRootDeclarations(
          data, configuration.collections(), new ObjectMapper()));
      if (phase.equals("SWITCHING")) manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
      if (phase.equals("AWAITING_ACCEPTANCE")) {
        Path file = base.resolve("state.json");
        Files.writeString(file, Files.readString(file).replace("MIGRATING", "AWAITING_ACCEPTANCE"));
      }
      ops = operations(roots, state, worker, loseResponse);
    }

    void register(Path root, String collection) {
      state.register(root, collection, false);
      assertTrue(worker.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString())
          .setCollection(collection).setRootAdded(true).build(), CallContext.none()).getWatching());
    }

    RootWatcherRegistry.Subscription subscription(Path root) throws Exception {
      var method = RootWatcherRegistry.class.getDeclaredMethod("subscription", Path.class);
      method.setAccessible(true);
      return (RootWatcherRegistry.Subscription) method.invoke(registry, root);
    }

    void field(String name, Object value) throws Exception {
      var field = KnowledgeServer.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(server, value);
    }

    @SuppressWarnings("unchecked")
    List<ResolvedConfig.FileSource> promotionRoots() throws Exception {
      var method = KnowledgeServer.class.getDeclaredMethod("nativePolicyMigrationRoots", ResolvedConfig.class, List.class);
      method.setAccessible(true);
      var official = roots.keySet().stream().map(root -> new RootBinding(root, state.getCollection(root))).toList();
      try { return (List<ResolvedConfig.FileSource>) method.invoke(server, configuration, official); }
      catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }

    void enterSwitching() throws Exception {
      var method = KnowledgeServer.class.getDeclaredMethod("enterSwitchingWithMutationAdmission", IndexGenerationManager.State.class);
      method.setAccessible(true);
      try { method.invoke(server, manager.readStateBestEffort()); }
      catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }

    void assertSurvivors() throws Exception {
      for (var runtime : List.of(active, candidate)) {
        runtime.commitOps().maybeRefreshBlocking();
        for (Path file : survivors) {
          assertEquals("kept", runtime.documentFieldOps().getDocumentField(key(file), SchemaFields.CONTENT));
          assertEquals(state.getCollection(file.getParent()),
              runtime.documentFieldOps().getDocumentField(key(file), SchemaFields.COLLECTION));
          assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(key(file)));
          assertTrue(queue.matchesExpectedCollection(file, state.getCollection(file.getParent())));
        }
      }
    }

    void assertRetained() throws Exception {
      assertSurvivors();
      for (var runtime : List.of(active, candidate)) {
        assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(key(own)));
      }
      assertEquals(survivors.size(), queue.queueDepth());
    }

    @Override public void close() throws Exception {
      field("jobQueue", null);
      field("appServices", null);
      server.close();
      serverExecutors.close();
      queue.close();
      candidate.close();
      active.close();
    }
  }

  private static RootLifecycleOps operations(Map<Path, Instant> roots, WatchedRootsState state,
      WorkerIngestService worker, AtomicBoolean loseResponse) {
    return new RootLifecycleOps(roots, state, ExcludeMatcher::empty,
          (path, collection, mode, globs, progress, ctx) -> null,
          new RootLifecycleOps.WorkerWatchFn() {
            @Override public void watch(String path, String collection, EngineContext ctx) {
              worker.watchRoot(WatchRootRequest.newBuilder().setRootPath(path).setCollection(collection)
                  .setRootAdded(true).build(), CallContext.none());
            }
            @Override public void unwatch(String path, EngineContext ctx) {
              worker.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(path).build(), CallContext.none());
            }
          }, (path, ctx) -> {
            var response = worker.deleteByPath(DeleteByPathRequest.newBuilder().setPath(path.toString()).build(), CallContext.none());
            if (loseResponse.getAndSet(false)) throw new IllegalStateException("lost removal response");
            return response;
          }, (id, ctx) -> null, mock(SyncOps.class), mock(ExecutorService.class), (body, ctx) -> {});
  }

  private RunningRuntime open(Path path) throws IOException {
    var catalog = FieldCatalogDef.forChunkTesting(0);
    var fields = new ArrayList<>(catalog.fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256,
        "keyword", true, false, List.of(), null, null, false));
    return IndexSchema.fromCatalog(new FieldCatalogDef(catalog.version(), fields)).atPath(path)
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static String key(Path file) { return PathNormalizer.normalizeKey(file); }

  private static void index(RunningRuntime runtime, Path file, String collection) throws IOException {
    String content = file.getFileName().toString().equals("own.txt") ? "removed" : "kept";
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, key(file), SchemaFields.DOC_UID, key(file) + "-uid",
        SchemaFields.PATH, key(file), SchemaFields.CONTENT, content, SchemaFields.COLLECTION, collection,
        SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file))));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "chunk:" + key(file), SchemaFields.DOC_UID, key(file) + "-uid#0",
        SchemaFields.PATH, key(file), SchemaFields.PARENT_DOC_ID, key(file), SchemaFields.IS_CHUNK, "true",
        SchemaFields.CHUNK_INDEX, 0, SchemaFields.CHUNK_TOTAL, 1, SchemaFields.CHUNK_CONTENT, content)));
  }
}
