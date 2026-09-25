/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatchPlan;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionDurability;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.app.api.operations.BulkReindexProgress;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.executor.RecordedBulkPlanResolver;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.BulkReindexHandler;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real Engine and SQLite proof for prepared bulk with live final promotion. */
@Timeout(360)
final class RecordedBulkEngineRestartTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final long WAIT_MS = 180_000L;

  @TempDir Path temporaryDirectory;

  @Test
  void preparedBulkSurvivesBuildRestartAndActivatesInProcess()
      throws Exception {
    Path dataDirectory = Files.createDirectories(temporaryDirectory.resolve("data"));
    Path watchedRoot = Files.createDirectories(temporaryDirectory.resolve("watched"));
    Path modelsDirectory = Files.createDirectories(temporaryDirectory.resolve("empty-models"));
    String marker = "recordedbulkrestart" + System.nanoTime();
    Files.writeString(watchedRoot.resolve("bulk.txt"), "searchable " + marker);
    writeWatchedRoots(dataDirectory, watchedRoot);

    CountDownLatch firstRestart = new CountDownLatch(1);
    String operationKey;
    String sourceGeneration;
    String targetGeneration;
    RecordedBulkPlan capturedPlan;

    try (EngineEpoch first = openEpoch(dataDirectory, modelsDirectory, firstRestart)) {
      var client = first.client();
      var expectedTarget = client.captureIndexTarget(TestEngineContexts.BACKGROUND);
      sourceGeneration = client.captureServingGeneration(TestEngineContexts.BACKGROUND);

      Operation operation = new CoreOperationCatalog()
          .findByIdValue(CoreOperationCatalog.BULK_REINDEX.value()).orElseThrow();
      var handlers = new HandlerRegistry();
      handlers.register(CoreOperationCatalog.BULK_REINDEX,
          new BulkReindexHandler(RecordedBulkPlan.Profile.USER_BULK,
              first.root().recordedIngestion(),
              ignored -> List.of(new RootBinding(watchedRoot, "documents")),
              first::client,
              List::of));
      var authority = first.root().authority();
      var executor = new OperationExecutorImpl(first.root().operationAttempts(),
          first.root().admission(), handlers, null, Map.of(), CLOCK,
          authority.trust(), authority.sources(), null, authority.capsules());
      EngineContext origin = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW,
          "recorded-bulk-engine-restart-test", Optional.of("bulk-restart-session"),
          Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE,
          EngineContext.Urgency.BACKGROUND);
      var provenance = EngineProvenance.invocation(origin, ExecutorTag.UI,
          Instant.now(CLOCK), Optional.empty());
      String arguments = "{\"corpusIds\":[\"documents\"]}";
      operationKey = OperationKeys.generate(CLOCK);

      var prepared = (OperationDispatchPlan.Ready) executor.prepare(operation, arguments,
          provenance, origin, operationKey, true);
      String approval = authority.capsules().mintPrepared(operation.id().value(), arguments,
          SourceTier.valueOf(origin.sourceTier()), operationKey, prepared.preparationNonce());
      OperationResult accepted = executor.dispatch(operation, arguments, provenance,
          Optional.of(approval), origin, operationKey, prepared.preparationNonce());
      assertTrue(accepted.success());
      assertEquals(operationKey, accepted.structuredData().get("operationKey"));

      var acceptedRow = first.operations().find(operationKey).orElseThrow();
      capturedPlan = new RecordedBulkPlanResolver()
          .resolve(acceptedRow, first.operations().acceptedPreparation(acceptedRow.id()).orElseThrow());
      assertEquals(sourceGeneration, capturedPlan.scope().generation());
      assertEquals(List.of(watchedRoot.toAbsolutePath().normalize()),
          capturedPlan.scope().roots().stream().map(root -> root.path()).toList());
      assertEquals(expectedTarget, capturedPlan.target());

      assertTrue(firstRestart.await(WAIT_MS, TimeUnit.MILLISECONDS),
          "captured bulk never requested its first Engine restart");
      var running = first.operations().find(operationKey).orElseThrow();
      assertEquals(OperationState.RUNNING, running.state());
      BulkReindexProgress building = first.operations().bulkReindexProgress(running.id()).orElseThrow();
      targetGeneration = "g-" + operationKey;
      assertEquals(BulkReindexProgress.Phase.BUILDING, building.phase());
      assertEquals(targetGeneration, building.generationId());
      assertEquals(capturedPlan.target(), building.target());
      assertNotNull(building.capture());
      assertEquals(1, building.capture().plannedUnits());
      var generationState = new IndexGenerationManager(dataDirectory.resolve("index"))
          .readStateBestEffort();
      assertNotNull(generationState);
      assertEquals(sourceGeneration, generationState.active_generation());
      assertEquals(targetGeneration, generationState.building_generation());
      assertThrows(IllegalStateException.class, first.root()::close,
          "direct index close must retain a live durable owner for ordered shutdown");
      first.requestedRestartHandoff();
    }

    CountDownLatch unexpectedCutoverRestart = new CountDownLatch(1);
    try (EngineEpoch second = openEpoch(dataDirectory, modelsDirectory, unexpectedCutoverRestart)) {
      assertTrue(await(() -> second.operations().find(operationKey)
              .map(row -> row.state() == OperationState.COMPLETE).orElse(false), WAIT_MS),
          "the live promoted successor never wrote terminal success");
      var complete = second.operations().find(operationKey).orElseThrow();
      assertEquals(OperationState.COMPLETE, complete.state());
      assertNotNull(complete.receipt());
      assertEquals("SUCCESS", complete.receipt().code());
      BulkReindexProgress settled = second.operations().bulkReindexProgress(complete.id()).orElseThrow();
      assertEquals(BulkReindexProgress.Phase.SETTLED, settled.phase());
      assertEquals(targetGeneration, settled.generationId());
      assertEquals(capturedPlan.target(), settled.target());
      assertNotNull(settled.settlement());
      assertTrue(settled.settlement().gaps().isEmpty());
      assertEquals(0, settled.settlement().failedEvents());
      var generationState = new IndexGenerationManager(dataDirectory.resolve("index"))
          .readStateBestEffort();
      assertNotNull(generationState);
      assertEquals(targetGeneration, generationState.active_generation());
      assertTrue(generationState.building_generation() == null
          || generationState.building_generation().isBlank());
      assertTrue(awaitSearchable(second.client(), marker, WAIT_MS),
          "the live Engine did not serve the captured document after promotion");
      assertEquals(targetGeneration,
          second.client().getStatus(TestEngineContexts.BACKGROUND)
              .getMigration().getServingSearchGenerationId());
      assertFalse(unexpectedCutoverRestart.await(250, TimeUnit.MILLISECONDS),
          "live final promotion requested an Engine restart");
    }

    CountDownLatch unexpectedRestart = new CountDownLatch(1);
    try (EngineEpoch third = openEpoch(dataDirectory, modelsDirectory, unexpectedRestart)) {
      assertTrue(await(() -> third.operations().find(operationKey)
              .map(row -> row.state() == OperationState.COMPLETE).orElse(false), WAIT_MS),
          "the promoted successor did not retain terminal success after reboot");
      var complete = third.operations().find(operationKey).orElseThrow();
      assertEquals(OperationState.COMPLETE, complete.state());
      assertNotNull(complete.receipt());
      assertEquals("SUCCESS", complete.receipt().code());
      assertTrue(awaitSearchable(third.client(), marker, WAIT_MS),
          "the reopened target generation did not serve the captured document");
      assertEquals(targetGeneration,
          third.client().getStatus(TestEngineContexts.BACKGROUND)
              .getMigration().getServingSearchGenerationId());
      assertFalse(unexpectedRestart.await(250, TimeUnit.MILLISECONDS),
          "terminal reconciliation requested an unexpected third restart");
    }

    try (var queue = new SqliteJobQueue(dataDirectory.resolve("jobs.db"))) {
      queue.open();
      var acknowledged = queue.recordedWalk(operationKey).orElseThrow();
      assertNotNull(acknowledged.sealedAt());
      assertEquals(acknowledged.revision(), acknowledged.acknowledgedRevision(),
          "terminal success must acknowledge the exact immutable queue settlement");
    }
  }

  @Test
  void registeredNoFileSourceReplaysNewerUpdateDeleteAndAdditionAcrossBuildRestart()
      throws Exception {
    Path dataDirectory = Files.createDirectories(temporaryDirectory.resolve("projection-data"));
    Path watchedRoot = Files.createDirectories(temporaryDirectory.resolve("projection-watched"));
    Path modelsDirectory = Files.createDirectories(temporaryDirectory.resolve("projection-models"));
    Files.writeString(watchedRoot.resolve("file.txt"), "ordinary file in candidate");
    writeWatchedRoots(dataDirectory, watchedRoot);
    String marker = "recordedprojection" + System.nanoTime();
    var oldUpdated = projection("updated", 1, marker + "updatedold");
    var newUpdated = projection("updated", 2, marker + "updatednew");
    var oldDeleted = projection("deleted", 1, marker + "deleted");
    var deleted = new AcceptedProjection("fixture-memory", "deleted", 2,
        AcceptedProjection.Kind.DELETE, null);
    var added = projection("added", 1, marker + "added");
    var source = new HeldProjectionSource(List.of(oldUpdated, oldDeleted));
    CountDownLatch requestedRestart = new CountDownLatch(1);
    String operationKey;

    try (EngineEpoch first = openEpoch(dataDirectory, modelsDirectory, requestedRestart, source)) {
      Operation operation = new CoreOperationCatalog()
          .findByIdValue(CoreOperationCatalog.BULK_REINDEX.value()).orElseThrow();
      var handlers = new HandlerRegistry();
      handlers.register(CoreOperationCatalog.BULK_REINDEX,
          new BulkReindexHandler(RecordedBulkPlan.Profile.USER_BULK,
              first.root().recordedIngestion(),
              ignored -> List.of(new RootBinding(watchedRoot, "documents")),
              first::client, List::of));
      var authority = first.root().authority();
      var executor = new OperationExecutorImpl(first.root().operationAttempts(),
          first.root().admission(), handlers, null, Map.of(), CLOCK,
          authority.trust(), authority.sources(), null, authority.capsules());
      EngineContext origin = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW,
          "recorded-projection-restart-test", Optional.of("projection-session"),
          Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE,
          EngineContext.Urgency.BACKGROUND);
      var provenance = EngineProvenance.invocation(origin, ExecutorTag.UI,
          Instant.now(CLOCK), Optional.empty());
      String arguments = "{\"corpusIds\":[\"documents\"]}";
      operationKey = OperationKeys.generate(CLOCK);
      var prepared = (OperationDispatchPlan.Ready) executor.prepare(operation, arguments,
          provenance, origin, operationKey, true);
      String approval = authority.capsules().mintPrepared(operation.id().value(), arguments,
          SourceTier.valueOf(origin.sourceTier()), operationKey, prepared.preparationNonce());
      assertTrue(executor.dispatch(operation, arguments, provenance, Optional.of(approval),
          origin, operationKey, prepared.preparationNonce()).success());
      assertTrue(requestedRestart.await(WAIT_MS, TimeUnit.MILLISECONDS));
      var row = first.operations().find(operationKey).orElseThrow();
      var plan = new RecordedBulkPlanResolver().resolve(row,
          first.operations().acceptedPreparation(row.id()).orElseThrow());
      assertEquals(List.of("fixture-memory"), plan.projectionSourceIds());
      first.requestedRestartHandoff();
    }

    source.holdNextEnumeration();
    try (EngineEpoch second = openEpoch(dataDirectory, modelsDirectory,
        new CountDownLatch(1), source)) {
      assertTrue(source.awaitHeld(WAIT_MS), "the resumed candidate never reached source enumeration");
      source.setRows(List.of(newUpdated, deleted, added));
      second.client().indexAndReturn(newUpdated, ProjectionDurability.NRT,
          TestEngineContexts.BACKGROUND);
      second.client().deleteAndAcknowledge(deleted, ProjectionDurability.NRT,
          TestEngineContexts.BACKGROUND);
      second.client().indexAndReturn(added, ProjectionDurability.NRT,
          TestEngineContexts.BACKGROUND);
      assertTrue(awaitSearchable(second.client(), marker + "updatednew", WAIT_MS),
          "A must show the newer accepted projection while B enumerates");
      assertTrue(awaitSearchable(second.client(), marker + "added", WAIT_MS));
      assertFalse(second.client().search(marker + "deleted", 10,
          TestEngineContexts.FOREGROUND).getResultsCount() > 0);
      source.releaseEnumeration();
      assertTrue(await(() -> second.operations().find(operationKey)
          .map(row -> row.state() == OperationState.COMPLETE).orElse(false), WAIT_MS),
          "the candidate never promoted after exact no-file replay");
      assertEquals("g-" + operationKey, new IndexGenerationManager(dataDirectory.resolve("index"))
          .readStateBestEffort().active_generation());
      assertTrue(awaitSearchable(second.client(), marker + "updatednew", WAIT_MS));
      assertTrue(awaitSearchable(second.client(), marker + "added", WAIT_MS));
      assertEquals(0, second.client().search(marker + "updatedold", 10,
          TestEngineContexts.FOREGROUND).getResultsCount());
      assertEquals(0, second.client().search(marker + "deleted", 10,
          TestEngineContexts.FOREGROUND).getResultsCount());
    } finally {
      source.releaseEnumeration();
    }
    try (EngineEpoch reopened = openEpoch(dataDirectory, modelsDirectory,
        new CountDownLatch(1), source)) {
      assertEquals(OperationState.COMPLETE,
          reopened.operations().find(operationKey).orElseThrow().state());
      assertTrue(awaitSearchable(reopened.client(), marker + "updatednew", WAIT_MS));
      assertTrue(awaitSearchable(reopened.client(), marker + "added", WAIT_MS));
      assertEquals(0, reopened.client().search(marker + "deleted", 10,
          TestEngineContexts.FOREGROUND).getResultsCount());
    }
  }

  private static AcceptedProjection projection(String documentId, long revision, String content) {
    return new AcceptedProjection("fixture-memory", documentId, revision,
        AcceptedProjection.Kind.UPSERT,
        "{\"content\":\"" + content + "\",\"title\":\"Projection fixture\"}");
  }

  private static final class HeldProjectionSource implements ProjectionSeedSource {
    private final AtomicReference<List<AcceptedProjection>> rows;
    private volatile CountDownLatch entered;
    private volatile CountDownLatch release;

    HeldProjectionSource(List<AcceptedProjection> initial) {
      rows = new AtomicReference<>(List.copyOf(initial));
    }

    @Override public String sourceId() { return "fixture-memory"; }

    void setRows(List<AcceptedProjection> next) { rows.set(List.copyOf(next)); }

    void holdNextEnumeration() {
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
    }

    boolean awaitHeld(long timeoutMillis) throws InterruptedException {
      return entered.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    void releaseEnumeration() {
      CountDownLatch pending = release;
      if (pending != null) pending.countDown();
    }

    @Override public void enumerate(Consumer<AcceptedProjection> sink) throws java.io.IOException {
      List<AcceptedProjection> snapshot = rows.get();
      CountDownLatch pending = release;
      if (pending != null) {
        entered.countDown();
        try {
          if (!pending.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw new java.io.IOException("Projection enumeration hold expired");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new java.io.IOException("Projection enumeration interrupted", interrupted);
        }
      }
      snapshot.forEach(sink);
    }
  }

  private EngineEpoch openEpoch(Path dataDirectory, Path modelsDirectory,
      CountDownLatch requestedRestart) throws Exception {
    return openEpoch(dataDirectory, modelsDirectory, requestedRestart, null);
  }

  private EngineEpoch openEpoch(Path dataDirectory, Path modelsDirectory,
      CountDownLatch requestedRestart, ProjectionSeedSource projectionSource) throws Exception {
    EngineTestHarness.publishConfig(dataDirectory, dataDirectory.resolve("index"),
        Map.of("justsearch.models.dir", modelsDirectory.toAbsolutePath().toString()));
    var operations = new SqliteOperationStore(dataDirectory.resolve("operations.db"));
    var attempts = new OperationAttemptRunnerImpl(operations, CLOCK,
        Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null,
        new RecordedIngestPlanResolver());
    var authority = OperationAuthority.load(dataDirectory);
    var root = new EngineRoot(operations, attempts,
        (gauge, executors, ingestion, indexComponent, encoderComponent) -> new KnowledgeServer(executors,
            WorkerConfig.load(), new InProcessWorkerSignalBus(gauge),
            io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), ingestion, indexComponent, encoderComponent),
        30_000L, 5_000,
        code -> { throw new AssertionError("unexpected terminal writer exit " + code); },
        requestedRestart::countDown, authority);
    try {
      if (projectionSource != null) root.registerProjectionSeedSource(projectionSource);
      KnowledgeClient client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      return new EngineEpoch(root, operations, client);
    } catch (Throwable failure) {
      root.close();
      root.executors().close();
      operations.close();
      throw failure;
    }
  }

  private static void writeWatchedRoots(Path dataDirectory, Path watchedRoot) throws Exception {
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    Files.writeString(dataDirectory.resolve("watched_roots.json"), mapper.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "roots", List.of(Map.of("path", watchedRoot.toAbsolutePath().normalize().toString(),
            "collection", "documents")))));
  }

  private static boolean await(BooleanSupplier condition, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(100);
    }
    return condition.getAsBoolean();
  }

  private static boolean awaitSearchable(KnowledgeClient client, String marker,
      long timeoutMillis) throws InterruptedException {
    return await(() -> {
      try {
        return client.search(marker, 10, TestEngineContexts.FOREGROUND).getResultsCount() > 0;
      } catch (RuntimeException stillOpening) {
        return false;
      }
    }, timeoutMillis);
  }

  private record EngineEpoch(EngineRoot root, SqliteOperationStore operations,
      KnowledgeClient client) implements AutoCloseable {
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

    @Override public void close() throws java.io.IOException {
      try {
        root.close();
      } finally {
        try {
          root.executors().close();
        } finally {
          operations.close();
        }
      }
    }
  }

}
