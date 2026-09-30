/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionDurability;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.execution.EngineExecutorRegistry;
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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    TwoPhaseBarrier barrier = new TwoPhaseBarrier();
    Epoch first = null;
    try {
      first = open(data, models, source, barrier, restartRequested);
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
      barrier.releaseBeforeSwitching();
      assertTrue(barrier.awaitAfterPointerCommit(WAIT_MS),
          "native cutover did not reach its post-pointer publication barrier");
      IndexGenerationManager.State committed = new IndexGenerationManager(data.resolve("index"))
          .readStateBestEffort();
      assertEquals(buildingB, committed.active_generation());
      assertEquals(activeA, committed.previous_generation());
      assertNull(committed.building_generation());
      assertEquals("IDLE", committed.migration_state());
      assertEquals(beforeRelease, journal(first.server())
          .listSwitchBufferOpsStrictForGeneration(buildingB),
          "post-pointer cancellation must retain the exact B journal snapshot");
      assertProjection(greenBeforeDeletes, retained);
      assertProjection(greenBeforeDeletes, late);
      assertDeletedFiles(greenBeforeDeletes,
          List.of(prefixVictimPath, collectionVictimPath, dualVictimPath));

      // The hook fails after the pointer write. This forces the existing ordered recovery callback;
      // it is a latch-only test owner, so no automatic restart races the explicit close below.
      barrier.cancelAfterPointerCommit();
      assertTrue(restartRequested.await(30, TimeUnit.SECONDS),
          "post-pointer ambiguity must notify the Engine owner exactly once");
      assertTrue(awaitCutoverExit(first.server(), 30, TimeUnit.SECONDS));
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
    runtime.commitOps().maybeRefreshBlocking();
    for (Path path : files) {
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(PathNormalizer.normalizeKey(path)));
    }
  }

  private static void assertProjection(RunningRuntime runtime, AcceptedProjection projection)
      throws IOException {
    var fields = runtime.documentFieldOps();
    assertEquals(projection.indexId(), fields.getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.DOC_ID));
    assertEquals(SOURCE, fields.getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
    assertEquals(Long.toString(projection.sourceRevision()), fields.getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
    assertEquals(projection.fieldsDigest(), fields.getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PROJECTION_DIGEST));
    var json = JsonMapper.builder().build().readTree(projection.fieldsJson());
    assertEquals(json.path("path").stringValue(), fields.getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PATH));
    assertEquals(json.path("collection").stringValue(), fields.getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.COLLECTION));
    assertEquals(1, fields.queryDocIdsByFieldOrThrow(
        SchemaFields.DOC_ID, projection.indexId(), 10).size());
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

  private static void installBarrier(KnowledgeServer server, MigrationTransitionBarrier.Hook hook)
      throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod(
        "installMigrationBarrierForTests", MigrationTransitionBarrier.Hook.class);
    method.setAccessible(true);
    method.invoke(server, hook);
  }

  private static Epoch open(Path data, Path models, ProjectionSeedSource source,
      MigrationTransitionBarrier.Hook barrier, CountDownLatch restartRequested) throws Exception {
    EngineTestHarness.publishConfig(data, data.resolve("index"),
        Map.of("justsearch.models.dir", models.toAbsolutePath().toString()));
    var operations = new SqliteOperationStore(data.resolve("operations.db"));
    var attempts = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
        Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null,
        new RecordedIngestPlanResolver());
    var authority = OperationAuthority.load(data);
    AtomicReference<KnowledgeServer> built = new AtomicReference<>();
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
    EngineRoot root = new EngineRoot(operations, attempts, factory, 30_000L, 5_000,
        code -> { throw new AssertionError("unexpected terminal writer exit " + code); },
        restartRequested::countDown, authority);
    try {
      root.registerProjectionSeedSource(source);
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

  private record Epoch(EngineRoot root, SqliteOperationStore operations,
      KnowledgeClient client, KnowledgeServer server) implements AutoCloseable {
    @Override public void close() throws IOException {
      try { root.close(); }
      finally {
        try { root.executors().close(); }
        finally { operations.close(); }
      }
    }
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
    private volatile boolean cancelAfterPointer;

    @Override public void await(MigrationTransitionBarrier.Transition transition)
        throws IOException, InterruptedException {
      if ("migration-before-switching".equals(transition.point())) {
        beforeSwitchingReached.countDown();
        if (!beforeSwitchingRelease.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
          throw new IOException("before-switching barrier timed out");
        }
        return;
      }
      if ("migration-after-pointer-commit".equals(transition.point())) {
        afterPointerReached.countDown();
        if (!afterPointerRelease.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
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
