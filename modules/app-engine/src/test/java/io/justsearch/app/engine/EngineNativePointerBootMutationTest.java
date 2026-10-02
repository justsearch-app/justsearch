/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatchPlan;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionDurability;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.BulkReindexProgress;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.BulkReindexHandler;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.MigrationTransitionBarrier;
import io.justsearch.indexerworker.server.RecordedIngestionLifecycle;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connected native-pointer recovery with real watched-file deletions and a later source projection.
 */
@Timeout(360)
final class EngineNativePointerBootMutationTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final long WAIT_MS = 180_000L;
  private static final String SOURCE = "fixture-memory";

  @TempDir Path temporaryDirectory;

  @Test
  void nativePointerCutRetainsMutationsAcrossCancelledPublicationAndReopen() throws Exception {
    Path data = Files.createDirectories(temporaryDirectory.resolve("data"));
    Path models = Files.createDirectories(temporaryDirectory.resolve("models"));
    Path outside = Files.createDirectories(temporaryDirectory.resolve("outside-watched-root"));
    Path watched = Files.createDirectories(temporaryDirectory.resolve("watched"));
    Files.writeString(
        data.resolve("watched_roots.json"),
        JsonMapper.builder().build().writeValueAsString(
            Map.of("schemaVersion", 1,
                "roots", List.of(
                    Map.of("path", watched.resolve("prefix").toAbsolutePath().normalize().toString(),
                        "collection", "keep-collection"),
                    Map.of("path", watched.resolve("collection").toAbsolutePath().normalize().toString(),
                        "collection", "deleted-files")))));

    Path prefix = Files.createDirectories(watched.resolve("prefix")).toAbsolutePath().normalize();
    Path prefixVictimPath = prefix.resolve("prefix-victim.md");
    Path dualVictimPath = prefix.resolve("dual-victim.md");
    Path latePath = prefix.resolve("late-survivor.md");
    Path collectionVictimPath = Files.createDirectories(watched.resolve("collection"))
        .resolve("collection-victim.md");
    Path retainedPath = outside.resolve("retained.md");
    AcceptedProjection retained = projection("retained", 1, "native-retained-marker",
        retainedPath, "keep-collection");
    Files.writeString(prefixVictimPath, "native-prefix-marker");
    Files.writeString(collectionVictimPath, "native-collection-marker");
    Files.writeString(dualVictimPath, "native-dual-marker");
    AcceptedProjection late = projection("late", 2, "native-late-marker", latePath,
        "deleted-files");
    MemoryProjectionSource source = new MemoryProjectionSource(List.of(retained));
    CountDownLatch restartRequested = new CountDownLatch(1);
    List<Path> deletedFiles = List.of(prefixVictimPath, collectionVictimPath, dualVictimPath);
    AtomicReference<Epoch> liveOwner = new AtomicReference<>();
    AtomicReference<RunningRuntime> greenRuntime = new AtomicReference<>();
    AtomicReference<NativePostPointerWitness> postPointerWitness = new AtomicReference<>();
    TwoPhaseBarrier barrier = new TwoPhaseBarrier(transition -> {
      RunningRuntime green = greenRuntime.get();
      postPointerWitness.set(new NativePostPointerWitness(
          new IndexGenerationManager(data.resolve("index")).readStateBestEffort(),
          List.copyOf(journal(liveOwner.get().server()).listSwitchBufferOpsStrictForGeneration(
              green.openedIndexPath().getFileName().toString())),
          projectionWitness(green, retained), projectionWitness(green, late),
          deletedFileCounts(green, deletedFiles)));
    });
    Epoch first = null;
    try {
      first = open(data, models, source, barrier, restartRequested);
      liveOwner.set(first);
      KnowledgeClient client = first.client();
      assertTrue(client.watchRoot(prefix.toString(), "keep-collection",
          TestEngineContexts.BACKGROUND).getWatching());
      assertTrue(client.watchRoot(collectionVictimPath.getParent().toString(), "deleted-files",
          TestEngineContexts.BACKGROUND).getWatching());
      assertEquals(2, client.submitBatch(List.of(prefixVictimPath, dualVictimPath),
          false, "keep-collection", TestEngineContexts.BACKGROUND).getAcceptedCount());
      assertEquals(1, client.submitBatch(List.of(collectionVictimPath),
          false, "deleted-files", TestEngineContexts.BACKGROUND).getAcceptedCount());

      assertTrue(awaitFileSearch(client, prefixVictimPath, "native-prefix-marker", true));
      assertTrue(awaitFileSearch(client, collectionVictimPath, "native-collection-marker", true));
      assertTrue(awaitFileSearch(client, dualVictimPath, "native-dual-marker", true));

      String activeA = client.captureServingGeneration(TestEngineContexts.BACKGROUND);
      assertTrue(activeA.matches("g-\\d{8}-\\d{6}(?:-\\d+)?"), activeA);
      assertNotEquals("", activeA);

      assertEquals(activeA, client.indexAndReturn(retained, ProjectionDurability.NRT,
          TestEngineContexts.BACKGROUND).generationId());
      assertTrue(awaitSearch(client, retained, "native-retained-marker", true));
      assertTrue(awaitFileSearch(client, prefixVictimPath, "native-prefix-marker", true));
      assertTrue(awaitFileSearch(client, collectionVictimPath, "native-collection-marker", true));
      assertTrue(awaitFileSearch(client, dualVictimPath, "native-dual-marker", true));

      var started = client.startMigration("native-pointer-connected", TestEngineContexts.FOREGROUND);
      assertTrue(started.accepted(), started.toString());
      String buildingB = started.buildingGenerationId();
      assertTrue(buildingB.matches("g-\\d{8}-\\d{6}(?:-\\d+)?"), buildingB);
      assertFalse(buildingB.matches("[0-9a-fA-F-]{36}"), "native B must be timestamp-derived");
      assertNotEquals(activeA, buildingB);
      awaitServingIngest(client, buildingB);
      assertTrue(await(() -> client.getDebugWorkerState(TestEngineContexts.BACKGROUND)
          .migrationEnumerator().done(), WAIT_MS), "complete registered source did not settle");

      assertTrue(barrier.awaitBeforeSwitching(WAIT_MS), "migration did not reach unfenced MIGRATING");
      var migrating = new IndexGenerationManager(data.resolve("index")).readStateBestEffort();
      assertEquals("MIGRATING", migrating.migration_state());
      assertEquals(activeA, migrating.active_generation());
      assertEquals(buildingB, migrating.building_generation());
      assertEquals(activeA, client.getStatus(TestEngineContexts.BACKGROUND)
          .getMigration().getActiveGenerationId());

      // The same published view serves A and ingests into B. Release its lease before
      // the publication barrier; the raw B fixture reference remains owned by this epoch.
      RunningRuntime greenBeforeDeletes;
      try (var servingA = first.server().captureServingView()) {
        assertEquals(activeA, servingA.activeGenerationPath().getFileName().toString());
        assertEquals(activeA, servingA.searchRuntime().openedIndexPath().getFileName().toString());
        assertEquals(buildingB, servingA.ingestRuntime().openedIndexPath().getFileName().toString());
        assertProjection((RunningRuntime) servingA.searchRuntime(), retained);
        greenBeforeDeletes = (RunningRuntime) servingA.ingestRuntime();
      }
      assertEquals(buildingB, greenBeforeDeletes.openedIndexPath().getFileName().toString());
      SwitchBufferCapableQueue journal = journal(first.server());
      assertTrue(await(() -> {
        var counts = journal.jobStateCountsStrict();
        return counts.processingCount() == 0 && counts.pendingReadyCount() == 0
            && counts.pendingBackoffCount() == 0;
      }, WAIT_MS), "Green file work must be terminal before broad deletion");
      assertEquals(0, journal.jobStateCountsStrict().failedCount());
      assertTrue(await(() -> filesPresent(greenBeforeDeletes,
          List.of(prefixVictimPath, collectionVictimPath, dualVictimPath)), WAIT_MS),
          "real watched files must be physically indexed in B before deletion");
      assertFile(greenBeforeDeletes, prefixVictimPath, "keep-collection");
      assertFile(greenBeforeDeletes, collectionVictimPath, "deleted-files");
      assertFile(greenBeforeDeletes, dualVictimPath, "keep-collection");
      List<SwitchBufferCapableQueue.SwitchBufferOp> baseline =
          journal.listSwitchBufferOpsStrictForGeneration(buildingB);

      assertTrue(client.deleteDocsByPathPrefix(prefix, TestEngineContexts.BACKGROUND) >= 0);
      assertTrue(awaitFileSearch(client, prefixVictimPath, "native-prefix-marker", false));
      assertTrue(awaitFileSearch(client, dualVictimPath, "native-dual-marker", false));
      greenBeforeDeletes.commitOps().maybeRefreshBlocking();
      assertEquals(0, greenBeforeDeletes.indexCountOps()
          .countPathPrefixExcludingAcceptedSurvivorsStrict(prefix.toString(), List.of(), List.of()));
      assertFile(greenBeforeDeletes, collectionVictimPath, "deleted-files");
      assertTrue(awaitFileSearch(client, collectionVictimPath, "native-collection-marker", true));
      assertTrue(client.deleteDocsByCollection("deleted-files", TestEngineContexts.BACKGROUND) >= 0);
      assertTrue(awaitFileSearch(client, prefixVictimPath, "native-prefix-marker", false));
      assertTrue(awaitFileSearch(client, collectionVictimPath, "native-collection-marker", false));
      assertTrue(awaitFileSearch(client, dualVictimPath, "native-dual-marker", false));
      greenBeforeDeletes.commitOps().maybeRefreshBlocking();
      assertEquals(0, greenBeforeDeletes.indexCountOps()
          .countPathPrefixExcludingAcceptedSurvivorsStrict(prefix.toString(), List.of(), List.of()));
      assertEquals(0, greenBeforeDeletes.indexCountOps()
          .countCollectionExcludingAcceptedSurvivorsStrict("deleted-files", List.of(), List.of()));
      // NRT is acknowledged against the serving A writer; the exact generation-scoped B journal
      // below is the independent witness that the candidate owns the same accepted effect.
      assertEquals(activeA, client.indexAndReturn(late, ProjectionDurability.NRT,
          TestEngineContexts.BACKGROUND).generationId());

      List<SwitchBufferCapableQueue.SwitchBufferOp> beforeRelease =
          journal.listSwitchBufferOpsStrictForGeneration(buildingB);
      assertEquals(baseline.size() + 3, beforeRelease.size(), beforeRelease.toString());
      assertEquals(baseline, beforeRelease.subList(0, baseline.size()));
      var suffix = beforeRelease.subList(baseline.size(), beforeRelease.size());
      assertEquals(List.of("DELETE_PREFIX", "DELETE_COLLECTION", "PROJECTION"),
          suffix.stream().map(SwitchBufferCapableQueue.SwitchBufferOp::op).toList());
      assertEquals(QueryFilterBuilder.normalizePathPrefix(prefix.toString()), suffix.get(0).payload());
      assertEquals("deleted-files", suffix.get(1).payload());
      assertTrue(late.sameEffect(AcceptedProjection.decode(suffix.get(2).payload())));
      assertTrue(suffix.stream().allMatch(EngineNativePointerBootMutationTest::hasDurableRevision));

      source.replace(List.of(retained, late));
      greenRuntime.set(greenBeforeDeletes);
      barrier.releaseBeforeSwitching();
      assertTrue(barrier.awaitAfterPointerCommit(WAIT_MS),
          "native cutover did not reach its post-pointer publication barrier");
      if (barrier.afterPointerObservationFailure() != null) {
        throw new AssertionError("post-pointer same-thread witness failed",
            barrier.afterPointerObservationFailure());
      }
      NativePostPointerWitness witness = postPointerWitness.get();
      assertNotNull(witness);
      IndexGenerationManager.State committed = witness.state();
      assertEquals(buildingB, committed.active_generation());
      assertEquals(activeA, committed.previous_generation());
      assertNull(committed.building_generation());
      assertEquals("IDLE", committed.migration_state());
      assertEquals(beforeRelease, witness.journal(),
          "post-pointer cancellation must retain the exact B journal snapshot");
      assertProjection(witness.retained(), retained);
      assertProjection(witness.late(), late);
      for (int count : witness.deletedFileCounts()) assertEquals(0, count);
      assertNull(barrier.barrierTimeout(), "cutover must not escape through a barrier timeout");

      // The hook fails after the pointer write. This forces the existing ordered recovery callback;
      // it is a latch-only test owner, so no automatic restart races the explicit close below.
      barrier.cancelAfterPointerCommit();
      assertTrue(restartRequested.await(30, TimeUnit.SECONDS),
          "post-pointer ambiguity must notify the Engine owner exactly once");
      assertTrue(awaitCutoverExit(first.server(), 30, TimeUnit.SECONDS));
      assertNull(barrier.barrierTimeout(), "cutover must not escape through a barrier timeout");
      Path aPath = data.resolve("index").resolve("indices").resolve(activeA);
      first.close();
      first = null;
      assertTrue(Files.isDirectory(aPath), "A must remain retained across the first owner close");
      assertEquals(buildingB, new IndexGenerationManager(data.resolve("index"))
          .readStateBestEffort().active_generation());
      // No watcher owns these files now. Their deletion cannot supply later exact receipts
      // that mask the broad effects, and the successor root scan cannot recreate them.
      Files.delete(prefixVictimPath);
      Files.delete(collectionVictimPath);
      Files.delete(dualVictimPath);

      try (Epoch reopened = open(data, models, source, null, new CountDownLatch(1))) {
        KnowledgeClient reopenedClient = reopened.client();
        assertTrue(awaitSearch(reopenedClient, retained, "native-retained-marker", true));
        assertTrue(awaitSearch(reopenedClient, late, "native-late-marker", true));
        assertTrue(awaitFileSearch(reopenedClient, prefixVictimPath, "native-prefix-marker", false));
        assertTrue(awaitFileSearch(reopenedClient, collectionVictimPath,
            "native-collection-marker", false));
        assertTrue(awaitFileSearch(reopenedClient, dualVictimPath, "native-dual-marker", false));

        assertTrue(journal(reopened.server()).listSwitchBufferOpsStrictForGeneration(buildingB).isEmpty(),
            "reopened B must certify and clear the exact committed receipts");
        try (var servingB = reopened.server().captureServingView()) {
          assertEquals(buildingB, servingB.activeGenerationPath().getFileName().toString());
          RunningRuntime runtimeB = (RunningRuntime) servingB.ingestRuntime();
          assertProjection(runtimeB, retained);
          assertProjection(runtimeB, late);
          assertDeletedFiles(runtimeB,
              List.of(prefixVictimPath, collectionVictimPath, dualVictimPath));
          assertEquals(1, runtimeB.indexCountOps()
              .countPathPrefixExcludingAcceptedSurvivorsStrict(prefix.toString(), List.of(), List.of()));
          assertEquals(1, runtimeB.indexCountOps()
              .countCollectionExcludingAcceptedSurvivorsStrict("deleted-files", List.of(), List.of()));
        }

        IndexGenerationManager manager = new IndexGenerationManager(data.resolve("index"));
        assertTrue(await(() -> {
          IndexGenerationManager.State state = manager.readStateBestEffort();
          return state != null && buildingB.equals(state.active_generation())
              && state.previous_generation() == null && !Files.exists(aPath);
        }, WAIT_MS), "reopen must retire A only after B replay is clean");
        var state = manager.readStateBestEffort();
        var manifest = manager.manifestForOwnedPath(manager.resolveGenerationPathStrict(buildingB));
        assertEquals(List.of(SOURCE), manifest.projection_source_ids());
        assertTrue(journal(reopened.server()).listSwitchBufferOpsStrict().isEmpty(),
            "all exact B mutation rows must be consumed after replay");
        assertEquals(buildingB, state.active_generation());
      }
    } finally {
      barrier.cancelAll();
      if (first != null) {
        try { awaitCutoverExit(first.server(), 30, TimeUnit.SECONDS); }
        finally { first.close(); }
      }
    }
  }

  @Test
  void recordedBulkPostPointerStopRecoversAfterWritableQueueAndReplayWitnesses()
      throws Exception {
    Path data = Files.createDirectories(temporaryDirectory.resolve("recorded-data"));
    Path models = Files.createDirectories(temporaryDirectory.resolve("recorded-models"));
    Path watched = Files.createDirectories(temporaryDirectory.resolve("recorded-watched"));
    String marker = "recorded-pointer-cut-" + System.nanoTime();
    Path member = Files.writeString(watched.resolve("bulk.txt"), marker);
    writeWatchedRoots(data, watched);

    AtomicReference<Epoch> liveOwner = new AtomicReference<>();
    AtomicReference<String> recordedKey = new AtomicReference<>();
    AtomicReference<PostPointerWitness> postPointerWitness = new AtomicReference<>();
    TwoPhaseBarrier barrier = new TwoPhaseBarrier(transition -> {
      Epoch owner = liveOwner.get();
      var row = owner.operations().find(recordedKey.get()).orElseThrow();
      var walk = journal(owner.server()).recordedWalk(recordedKey.get()).orElseThrow();
      var progress = owner.operations().bulkReindexProgress(row.id()).orElseThrow();
      postPointerWitness.set(new PostPointerWitness(
          transition.sourceGeneration(), transition.buildingGeneration(),
          row.state(), walk.sealedAt() != null, walk.revision(),
          progress.phase(), progress.settlement().revision()));
    });
    CountDownLatch postPointerRestart = new CountDownLatch(1);
    Epoch live = null;
    try {
      live = open(data, models, null, barrier, postPointerRestart);
      liveOwner.set(live);
      String operationKey = dispatchRecordedBulk(live, watched);
      recordedKey.set(operationKey);
      String targetGeneration = "g-" + operationKey;
      long operationId = live.operations().find(operationKey).orElseThrow().id();
      assertTrue(barrier.awaitBeforeSwitching(WAIT_MS),
          "recorded candidate did not reach the held pre-SWITCHING transition");
      assertEquals(1L, postPointerRestart.getCount(),
          "recorded Green must open live before the forced post-pointer recovery");
      assertEquals(OperationState.RUNNING,
          live.operations().find(operationKey).orElseThrow().state());
      var beforePointerWalk = journal(live.server()).recordedWalk(operationKey).orElseThrow();
      assertNull(beforePointerWalk.sealedAt(),
          "the queue must remain unsealed before the recorded promotion owner runs");
      assertEquals(BulkReindexProgress.Phase.BUILDING,
          live.operations().bulkReindexProgress(
              live.operations().find(operationKey).orElseThrow().id()).orElseThrow().phase());
      assertEquals(targetGeneration, live.client().getStatus(TestEngineContexts.BACKGROUND)
          .getMigration().getServingIngestGenerationId(),
          "the exact writable B must be live before cutover can leave A");

      barrier.releaseBeforeSwitching();
      assertTrue(barrier.awaitAfterPointerCommit(WAIT_MS),
          "recorded cutover did not reach the real post-pointer transition");
      if (barrier.afterPointerObservationFailure() != null) {
        throw new AssertionError("post-pointer same-thread witness failed",
            barrier.afterPointerObservationFailure());
      }
      PostPointerWitness committed = postPointerWitness.get();
      assertNotNull(committed);
      assertEquals(targetGeneration, committed.activeGeneration());
      assertNull(committed.buildingGeneration());
      assertEquals(OperationState.RUNNING, committed.operationState(),
          "the pointer cut precedes replay settlement and the terminal operation row");
      assertTrue(committed.queueSealed());
      assertEquals(BulkReindexProgress.Phase.SETTLED, committed.progressPhase());
      assertEquals(committed.queueRevision(), committed.settlementRevision());

      barrier.cancelAfterPointerCommit();
      assertTrue(postPointerRestart.await(30, TimeUnit.SECONDS),
          "post-pointer ambiguity did not request recovery");
      assertTrue(awaitCutoverExit(live.server(), 30, TimeUnit.SECONDS),
          "the interrupted cutover owner did not exit before reopen");
      assertNull(barrier.barrierTimeout(), "cutover must not escape through a barrier timeout");
      assertEquals(OperationState.RUNNING,
          live.operations().find(operationKey).orElseThrow().state(),
          "the stopped post-pointer owner must leave terminal settlement to recovery");
      live.requestedRestartHandoff();
      live.close();
      live = null;

      var completionTrace = new CopyOnWriteArrayList<String>();
      AtomicReference<Throwable> completionOracleFailure = new AtomicReference<>();
      CompletionOracle completionOracle = (id, server) -> {
        if (id != operationId) return;
        Lock publicationRead = null;
        boolean publicationHeld = false;
        try {
          ReentrantReadWriteLock publicationLock = privateField(server, KnowledgeServer.class,
              "publicationLock", ReentrantReadWriteLock.class);
          assertEquals(0, publicationLock.getWriteHoldCount(),
              "completion must not run inside the publication write section");
          assertFalse(publicationLock.isWriteLocked(),
              "completion must observe a serving view already visible to independent readers");
          publicationRead = publicationLock.readLock();
          assertTrue(publicationRead.tryLock(),
              "completion observation cannot wait for the publication owner");
          publicationHeld = true;
          Object servingView = privateField(server, KnowledgeServer.class,
              "servingView", Object.class);
          RunningRuntime ingestRuntime = privateField(servingView, servingView.getClass(),
              "ingestRuntime", RunningRuntime.class);
          RunningRuntime searchRuntime = privateField(servingView, servingView.getClass(),
              "searchRuntime", RunningRuntime.class);
          assertSame(searchRuntime, ingestRuntime,
              "the preterminal serving view must bind search and writes to the same B runtime");
          assertTrue(ingestRuntime.isAcceptingWrites(),
              "the preterminal B runtime must still own writable admission");
          assertEquals(targetGeneration,
              ingestRuntime.openedIndexPath().getFileName().toString());
          completionTrace.add("writerB");
          CompletionSnapshot snapshot = readCompletionSnapshot(data, id, operationKey,
              targetGeneration);
          assertEquals("RUNNING", snapshot.operationState());
          assertEquals("settled", snapshot.progressPhase());
          assertTrue(snapshot.queueSealed());
          assertEquals(snapshot.queueRevision(), snapshot.settlementRevision());
          completionTrace.add("sealedQueue");
          assertEquals(targetGeneration, replaySettledGeneration(server));
          assertEquals(0, snapshot.replayRows());
          completionTrace.add("replaySettled");
        } catch (Throwable failure) {
          completionOracleFailure.compareAndSet(null, failure);
        } finally {
          if (publicationHeld) publicationRead.unlock();
          completionTrace.add("COMPLETE");
        }
      };
      try (Epoch recovered = open(data, models, null, null, new CountDownLatch(1),
          completionOracle)) {
        assertTrue(await(() -> recovered.operations().find(operationKey)
            .map(row -> row.state() == OperationState.COMPLETE).orElse(false), WAIT_MS),
            "recovered B did not settle replay and write terminal success");
        if (completionOracleFailure.get() != null) {
          throw new AssertionError("preterminal completion oracle failed",
              completionOracleFailure.get());
        }
        assertEquals(List.of("writerB", "sealedQueue", "replaySettled", "COMPLETE"),
            completionTrace);
        assertEquals(targetGeneration, recovered.client().getStatus(TestEngineContexts.BACKGROUND)
            .getMigration().getServingIngestGenerationId());
        assertEquals(targetGeneration, recovered.client().getStatus(TestEngineContexts.BACKGROUND)
            .getMigration().getServingSearchGenerationId());
        assertTrue(awaitFileSearch(recovered.client(), member, marker, true),
            "the recovered B writer must also be the serving search generation");
        assertTrue(journal(recovered.server())
            .listSwitchBufferOpsStrictForGeneration(targetGeneration).isEmpty(),
            "terminal success requires exact B replay settlement");
        var completed = recovered.operations().find(operationKey).orElseThrow();
        assertEquals("SUCCESS", completed.receipt().code());
        var acknowledged = journal(recovered.server()).recordedWalk(operationKey).orElseThrow();
        assertNotNull(acknowledged.sealedAt());
        assertEquals(acknowledged.revision(), acknowledged.acknowledgedRevision());
        assertEquals(acknowledged.revision(), recovered.operations()
            .bulkReindexProgress(completed.id()).orElseThrow().settlement().revision());
      }
    } finally {
      barrier.cancelAll();
      if (live != null) {
        try {
          awaitCutoverExit(live.server(), 30, TimeUnit.SECONDS);
          live.requestedRestartHandoff();
        }
        finally { live.close(); }
      }
    }
  }

  private static String dispatchRecordedBulk(Epoch epoch, Path watchedRoot) throws Exception {
    Operation operation = new CoreOperationCatalog()
        .findByIdValue(CoreOperationCatalog.BULK_REINDEX.value()).orElseThrow();
    var handlers = new HandlerRegistry();
    handlers.register(CoreOperationCatalog.BULK_REINDEX,
        new BulkReindexHandler(RecordedBulkPlan.Profile.USER_BULK,
            epoch.root().recordedIngestion(),
            ignored -> List.of(new RootBinding(watchedRoot, "documents")),
            epoch::client, List::of));
    var authority = epoch.root().authority();
    var executor = new OperationExecutorImpl(epoch.root().operationAttempts(),
        epoch.root().admission(), handlers, null, Map.of(), CLOCK,
        authority.trust(), authority.sources(), null, authority.capsules());
    EngineContext origin = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW,
        "recorded-pointer-cut-test", Optional.of("recorded-pointer-cut-session"),
        Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE,
        EngineContext.Urgency.BACKGROUND);
    var provenance = EngineProvenance.invocation(origin, ExecutorTag.UI,
        Instant.now(CLOCK), Optional.empty());
    String arguments = "{\"corpusIds\":[\"documents\"]}";
    String operationKey = OperationKeys.generate(CLOCK);
    var prepared = (OperationDispatchPlan.Ready) executor.prepare(operation, arguments,
        provenance, origin, operationKey, true);
    String approval = authority.capsules().mintPrepared(operation.id().value(), arguments,
        SourceTier.valueOf(origin.sourceTier()), operationKey, prepared.preparationNonce());
    assertTrue(executor.dispatch(operation, arguments, provenance, Optional.of(approval),
        origin, operationKey, prepared.preparationNonce()).success());
    return operationKey;
  }

  private static void writeWatchedRoots(Path data, Path watchedRoot) throws Exception {
    Files.writeString(data.resolve("watched_roots.json"),
        JsonMapper.builder().build().writeValueAsString(Map.of(
            "schemaVersion", 1,
            "roots", List.of(Map.of(
                "path", watchedRoot.toAbsolutePath().normalize().toString(),
                "collection", "documents")))));
  }

  private static AcceptedProjection projection(String id, long revision, String marker,
      Path path, String collection) {
    return new AcceptedProjection(SOURCE, id, revision, AcceptedProjection.Kind.UPSERT,
        JsonMapper.builder().build().writeValueAsString(Map.of(
            "content", marker, "title", "connected-native",
            "path", PathNormalizer.normalizeKey(path), "collection", collection)));
  }

  private static boolean awaitSearch(KnowledgeClient client, AcceptedProjection projection,
      String marker, boolean expected) throws InterruptedException {
    return await(() -> {
      try {
        return searchHas(client, projection.indexId(), marker) == expected;
      } catch (RuntimeException settling) {
        return false;
      }
    }, WAIT_MS);
  }

  private static boolean awaitFileSearch(KnowledgeClient client, Path path, String marker, boolean expected)
      throws InterruptedException {
    return await(() -> {
      try {
        return searchHas(client, PathNormalizer.normalizeKey(path), marker) == expected;
      } catch (RuntimeException settling) {
        return false;
      }
    }, WAIT_MS);
  }

  private static boolean searchHas(KnowledgeClient client, String expectedId, String marker) {
    var response = client.search(marker, 20, TestEngineContexts.BACKGROUND);
    if (!response.hasSearchTrace() || response.getSearchTrace().getDecisionKind().isBlank()
        || "blocked".equals(response.getSearchTrace().getDecisionKind())
        || "empty_query".equals(response.getSearchTrace().getDecisionKind())) {
      throw new IllegalStateException("Search did not execute: " + response.getSearchTrace());
    }
    return response.getResultsList().stream().anyMatch(hit -> expectedId.equals(hit.getId()));
  }

  private static void awaitServingIngest(KnowledgeClient client, String building)
      throws InterruptedException {
    assertTrue(await(() -> {
      try {
        return building.equals(client.getStatus(TestEngineContexts.BACKGROUND)
            .getMigration().getServingIngestGenerationId());
      } catch (RuntimeException settling) {
        return false;
      }
    }, WAIT_MS), "Green never became the live ingest owner");
  }

  private static boolean filesPresent(RunningRuntime runtime, List<Path> files) {
    try {
      runtime.commitOps().maybeRefreshBlocking();
      for (Path path : files) {
        String id = PathNormalizer.normalizeKey(path);
        if (!id.equals(runtime.documentFieldOps().getDocumentFieldOrThrow(id, SchemaFields.DOC_ID))) {
          return false;
        }
      }
      return true;
    } catch (IOException unreadable) {
      return false;
    }
  }

  private static void assertFile(RunningRuntime runtime, Path path, String collection) throws Exception {
    String id = PathNormalizer.normalizeKey(path);
    var fields = runtime.documentFieldOps();
    assertEquals(id, fields.getDocumentFieldOrThrow(id, SchemaFields.DOC_ID));
    assertEquals(id, fields.getDocumentFieldOrThrow(id, SchemaFields.PATH));
    assertEquals(collection, fields.getDocumentFieldOrThrow(id, SchemaFields.COLLECTION));
    assertEquals(HexFormat.of().formatHex(MessageDigest
        .getInstance("SHA-256").digest(Files.readAllBytes(path))),
        fields.getDocumentFieldOrThrow(id, SchemaFields.SOURCE_SHA256));
  }

  private static void assertDeletedFiles(RunningRuntime runtime, List<Path> files) throws IOException {
    for (int count : deletedFileCounts(runtime, files)) assertEquals(0, count);
  }

  private static List<Integer> deletedFileCounts(RunningRuntime runtime, List<Path> files)
      throws IOException {
    runtime.commitOps().maybeRefreshBlocking();
    List<Integer> counts = new ArrayList<>();
    for (Path path : files) {
      counts.add(runtime.indexCountOps()
          .countByIdAndChunksStrict(PathNormalizer.normalizeKey(path)));
    }
    return List.copyOf(counts);
  }

  private static void assertProjection(RunningRuntime runtime, AcceptedProjection projection)
      throws IOException {
    assertProjection(projectionWitness(runtime, projection), projection);
  }

  private static ProjectionWitness projectionWitness(RunningRuntime runtime,
      AcceptedProjection projection) throws IOException {
    var fields = runtime.documentFieldOps();
    return new ProjectionWitness(
        fields.getDocumentFieldOrThrow(projection.indexId(), SchemaFields.DOC_ID),
        fields.getDocumentFieldOrThrow(projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID),
        fields.getDocumentFieldOrThrow(projection.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION),
        fields.getDocumentFieldOrThrow(projection.indexId(), SchemaFields.PROJECTION_DIGEST),
        fields.getDocumentFieldOrThrow(projection.indexId(), SchemaFields.PATH),
        fields.getDocumentFieldOrThrow(projection.indexId(), SchemaFields.COLLECTION),
        fields.queryDocIdsByFieldOrThrow(SchemaFields.DOC_ID, projection.indexId(), 10).size());
  }

  private static void assertProjection(ProjectionWitness witness, AcceptedProjection projection)
      throws IOException {
    assertEquals(projection.indexId(), witness.id());
    assertEquals(SOURCE, witness.source());
    assertEquals(Long.toString(projection.sourceRevision()), witness.revision());
    assertEquals(projection.fieldsDigest(), witness.digest());
    var json = JsonMapper.builder().build().readTree(projection.fieldsJson());
    assertEquals(json.path("path").stringValue(), witness.path());
    assertEquals(json.path("collection").stringValue(), witness.collection());
    assertEquals(1, witness.matchingDocuments());
  }

  private static boolean hasDurableRevision(SwitchBufferCapableQueue.SwitchBufferOp row) {
    return row.revision() != null && !row.revision().isBlank();
  }

  private static boolean await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(25);
    }
    return condition.getAsBoolean();
  }

  private static SwitchBufferCapableQueue journal(KnowledgeServer server) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("jobQueueForTests");
    method.setAccessible(true);
    return (SwitchBufferCapableQueue) method.invoke(server);
  }

  private static boolean awaitCutoverExit(KnowledgeServer server, long timeout, TimeUnit unit)
      throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod(
        "awaitMigrationCutoverExitForTests", long.class, TimeUnit.class);
    method.setAccessible(true);
    return (boolean) method.invoke(server, timeout, unit);
  }

  private static String replaySettledGeneration(KnowledgeServer server) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField("replaySettledGeneration");
    field.setAccessible(true);
    return (String) field.get(server);
  }

  private static <T> T privateField(Object target, Class<?> owner, String name, Class<T> type)
      throws ReflectiveOperationException {
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(target));
  }

  private static CompletionSnapshot readCompletionSnapshot(Path data, long operationId,
      String operationKey, String targetGeneration) throws Exception {
    String operationState;
    String progressPhase;
    long settlementRevision;
    try (Connection operations = openZeroWaitReadOnly(data.resolve("operations.db"));
        var query = operations.prepareStatement("""
            SELECT state, phase,
              CAST(json_extract(processing_history_counts_json, '$.sealedRevision') AS INTEGER)
            FROM operations WHERE id = ?
            """)) {
      query.setLong(1, operationId);
      try (var row = query.executeQuery()) {
        assertTrue(row.next(), "the completing operation row must remain readable");
        operationState = row.getString(1);
        progressPhase = row.getString(2);
        settlementRevision = row.getLong(3);
        assertFalse(row.wasNull(), "the completing operation must retain a settlement revision");
      }
    }

    boolean queueSealed;
    long queueRevision;
    long replayRows;
    try (Connection jobs = openZeroWaitReadOnly(data.resolve("jobs.db"));
        var walk = jobs.prepareStatement("""
            SELECT sealed_at IS NOT NULL, revision
            FROM ingestion_walk_progress WHERE operation_key = ?
            """);
        var replay = jobs.prepareStatement(
            "SELECT COUNT(*) FROM switch_buffer WHERE generation = ?")) {
      walk.setString(1, operationKey);
      try (var row = walk.executeQuery()) {
        assertTrue(row.next(), "the completing operation must retain its captured queue row");
        queueSealed = row.getBoolean(1);
        queueRevision = row.getLong(2);
      }
      replay.setString(1, targetGeneration);
      try (var row = replay.executeQuery()) {
        assertTrue(row.next());
        replayRows = row.getLong(1);
      }
    }
    return new CompletionSnapshot(operationState, progressPhase, settlementRevision,
        queueSealed, queueRevision, replayRows);
  }

  private static Connection openZeroWaitReadOnly(Path database) throws Exception {
    Connection connection = DriverManager.getConnection(
        "jdbc:sqlite:" + database.toUri() + "?mode=ro&busy_timeout=0");
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA busy_timeout=0");
      statement.execute("PRAGMA query_only=ON");
      return connection;
    } catch (Exception | Error failure) {
      try { connection.close(); }
      catch (Exception cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  private static void installBarrier(KnowledgeServer server, MigrationTransitionBarrier.Hook hook)
      throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod(
        "installMigrationBarrierForTests", MigrationTransitionBarrier.Hook.class);
    method.setAccessible(true);
    method.invoke(server, hook);
  }

  private static Epoch open(Path data, Path models, ProjectionSeedSource source,
      MigrationTransitionBarrier.Hook barrier, CountDownLatch restartRequested) throws Exception {
    return open(data, models, source, barrier, restartRequested, null);
  }

  private static Epoch open(Path data, Path models, ProjectionSeedSource source,
      MigrationTransitionBarrier.Hook barrier, CountDownLatch restartRequested,
      CompletionOracle completionOracle) throws Exception {
    EngineTestHarness.publishConfig(data, data.resolve("index"),
        Map.of("justsearch.models.dir", models.toAbsolutePath().toString()));
    var operations = new SqliteOperationStore(data.resolve("operations.db"));
    AtomicReference<KnowledgeServer> built = new AtomicReference<>();
    OperationStore operationOwner = completionOracle == null ? operations
        : completionOracleStore(operations, built, completionOracle);
    var attempts = new OperationAttemptRunnerImpl(operationOwner, Clock.systemUTC(),
        Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null,
        new RecordedIngestPlanResolver());
    var authority = OperationAuthority.load(data);
    EngineRoot.ServerFactory factory = new EngineRoot.ServerFactory() {
      @Override public KnowledgeServer create(GpuSchedulingGauge gauge,
          EngineExecutorRegistry executors, RecordedIngestionLifecycle ingestion,
          ComponentHandle indexComponent, ComponentHandle encoderComponent) {
        KnowledgeServer server = new KnowledgeServer(executors, WorkerConfig.load(),
            new InProcessWorkerSignalBus(gauge),
            ManagedChildRegistry.noop(), ingestion,
            indexComponent, encoderComponent);
        if (barrier != null) {
          try {
            installBarrier(server, barrier);
          } catch (Exception failure) {
            throw new IllegalStateException("failed to install pre-start migration barrier", failure);
          }
        }
        built.set(server);
        return server;
      }
    };
    EngineRoot root = new EngineRoot(operationOwner, attempts, factory, 30_000L, 5_000,
        code -> { throw new AssertionError("unexpected terminal writer exit " + code); },
        restartRequested::countDown, authority);
    try {
      if (source != null) root.registerProjectionSeedSource(source);
      KnowledgeClient client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      return new Epoch(root, operations, client, built.get());
    } catch (Throwable failure) {
      try { root.close(); }
      finally {
        try { root.executors().close(); }
        finally { operations.close(); }
      }
      throw failure;
    }
  }

  private static OperationStore completionOracleStore(SqliteOperationStore delegate,
      AtomicReference<KnowledgeServer> server, CompletionOracle oracle) {
    return (OperationStore) Proxy.newProxyInstance(OperationStore.class.getClassLoader(),
        new Class<?>[] {OperationStore.class}, (proxy, method, arguments) -> {
          if (method.getName().equals("finish") && arguments[1] == OperationState.COMPLETE) {
            oracle.beforeComplete((Long) arguments[0], server.get());
          }
          try { return method.invoke(delegate, arguments); }
          catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
  }

  private record Epoch(EngineRoot root, SqliteOperationStore operations,
      KnowledgeClient client, KnowledgeServer server) implements AutoCloseable {
    void requestedRestartHandoff() {
      root.admission().beginClosing();
      root.operationAttempts().beginClosing();
      root.admission().cancelInteractive("requested restart");
      root.quiesceProducers();
      assertEquals(0, root.admission().activeWorkCount(),
          "the old Engine must release durable admitted work after producer exit");
      assertTrue(root.operationAttempts().awaitDrained(java.time.Duration.ZERO),
          "pending durable rows must relinquish in-memory runner bodies");
    }

    @Override public void close() throws IOException {
      try { root.close(); }
      finally {
        try { root.executors().close(); }
        finally { operations.close(); }
      }
    }
  }

  @FunctionalInterface
  private interface CompletionOracle {
    void beforeComplete(long id, KnowledgeServer server);
  }

  private record PostPointerWitness(String activeGeneration, String buildingGeneration,
      OperationState operationState, boolean queueSealed, long queueRevision,
      BulkReindexProgress.Phase progressPhase, long settlementRevision) {}

  private record NativePostPointerWitness(IndexGenerationManager.State state,
      List<SwitchBufferCapableQueue.SwitchBufferOp> journal, ProjectionWitness retained,
      ProjectionWitness late, List<Integer> deletedFileCounts) {}

  private record ProjectionWitness(String id, String source, String revision, String digest,
      String path, String collection, int matchingDocuments) {}

  private record CompletionSnapshot(String operationState, String progressPhase,
      long settlementRevision, boolean queueSealed, long queueRevision, long replayRows) {}

  @FunctionalInterface
  private interface CheckedTransitionObserver {
    void observe(MigrationTransitionBarrier.Transition transition) throws Exception;
  }

  private static final class MemoryProjectionSource implements ProjectionSeedSource {
    private volatile List<AcceptedProjection> rows;

    MemoryProjectionSource(List<AcceptedProjection> rows) { replace(rows); }

    @Override public String sourceId() { return SOURCE; }

    void replace(List<AcceptedProjection> next) { rows = List.copyOf(next); }

    @Override public void enumerate(Consumer<AcceptedProjection> sink) {
      rows.forEach(sink);
    }
  }

  private static final class TwoPhaseBarrier implements MigrationTransitionBarrier.Hook {
    private final CountDownLatch beforeSwitchingReached = new CountDownLatch(1);
    private final CountDownLatch beforeSwitchingRelease = new CountDownLatch(1);
    private final CountDownLatch afterPointerReached = new CountDownLatch(1);
    private final CountDownLatch afterPointerRelease = new CountDownLatch(1);
    private final CheckedTransitionObserver afterPointerObserver;
    private final AtomicReference<Throwable> afterPointerObservationFailure = new AtomicReference<>();
    private final AtomicReference<String> barrierTimeout = new AtomicReference<>();
    private volatile boolean cancelAfterPointer;

    TwoPhaseBarrier(CheckedTransitionObserver afterPointerObserver) {
      this.afterPointerObserver = afterPointerObserver;
    }

    @Override public void await(MigrationTransitionBarrier.Transition transition)
        throws IOException, InterruptedException {
      if ("migration-before-switching".equals(transition.point())) {
        beforeSwitchingReached.countDown();
        if (!beforeSwitchingRelease.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
          barrierTimeout.compareAndSet(null, "before-switching");
          throw new IOException("before-switching barrier timed out");
        }
        return;
      }
      if ("migration-after-pointer-commit".equals(transition.point())) {
        try { afterPointerObserver.observe(transition); }
        catch (Throwable failure) { afterPointerObservationFailure.compareAndSet(null, failure); }
        afterPointerReached.countDown();
        if (!afterPointerRelease.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
          barrierTimeout.compareAndSet(null, "after-pointer");
          throw new IOException("after-pointer barrier timed out");
        }
        if (cancelAfterPointer) throw new InterruptedException("cancelled post-pointer cut");
      }
    }

    boolean awaitBeforeSwitching(long timeoutMs) throws InterruptedException {
      return beforeSwitchingReached.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    void releaseBeforeSwitching() { beforeSwitchingRelease.countDown(); }

    boolean awaitAfterPointerCommit(long timeoutMs) throws InterruptedException {
      return afterPointerReached.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    Throwable afterPointerObservationFailure() { return afterPointerObservationFailure.get(); }

    String barrierTimeout() { return barrierTimeout.get(); }

    void cancelAfterPointerCommit() {
      cancelAfterPointer = true;
      afterPointerRelease.countDown();
    }

    void cancelAll() {
      cancelAfterPointer = true;
      beforeSwitchingRelease.countDown();
      afterPointerRelease.countDown();
    }
  }
}
