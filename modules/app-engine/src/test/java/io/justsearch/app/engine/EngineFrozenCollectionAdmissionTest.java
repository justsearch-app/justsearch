/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.OperationKeys;
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
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.MigrationTransitionBarrier;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@Timeout(600)
final class EngineFrozenCollectionAdmissionTest {
  @Test
  void unseenLiveFileUsesFrozenDefaultUntilCutoverThenScanRelabels(@TempDir Path data) throws Exception {
    String previousData = System.getProperty("justsearch.data.dir");
    String previousHome = System.getProperty("user.home");
    var previousConfig = ConfigStore.globalOrNull();
    var opening = new MigrationTransitionBarrier.Controlled("migration-before-live-green-open");
    var switching = new MigrationTransitionBarrier.Controlled("migration-before-switching");
    var activated = new CountDownLatch(1);
    var server = new AtomicReference<KnowledgeServer>();
    try {
      System.setProperty("justsearch.data.dir", data.toString());
      System.setProperty("user.home", data.resolve("home").toString());
      Path folder = Files.createDirectory(data.resolve("source"));
      AtomicFileWrites.replace(data.resolve("watched_roots.json"), new ObjectMapper().writeValueAsBytes(
          Map.of("schemaVersion", 1, "roots", List.of(Map.of("path", folder.toString())))));
      Path models = Files.createDirectory(data.resolve("empty-models"));
      EngineTestHarness.publishConfig(data, data.resolve("index"), Map.of("justsearch.models.dir", models.toString()));
      Clock clock = Clock.systemUTC();
      try (var operations = new SqliteOperationStore(data.resolve("operations.db"))) {
        var attempts = new OperationAttemptRunnerImpl(operations, clock,
            Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null,
            new RecordedIngestPlanResolver());
        var authority = OperationAuthority.load(data);
        var engine = new EngineRoot(operations, attempts,
            (gauge, executors, ingestion, indexComponent, encoderComponent) -> {
              var worker = new KnowledgeServer(executors, WorkerConfig.load(), new InProcessWorkerSignalBus(gauge),
                  io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), ingestion, indexComponent, encoderComponent);
              try {
                var hook = KnowledgeServer.class.getDeclaredMethod("installMigrationBarrierForTests", MigrationTransitionBarrier.Hook.class);
                hook.setAccessible(true);
                hook.invoke(worker, (MigrationTransitionBarrier.Hook) transition -> {
                  if ("migration-after-live-activation".equals(transition.point())) activated.countDown();
                  opening.await(transition);
                  switching.await(transition);
                });
              } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
              server.set(worker);
              return worker;
            }, 30_000L, 5_000, code -> { throw new AssertionError("unexpected fatal exit " + code); },
            () -> { throw new AssertionError("recorded live migration requested a restart"); }, authority);
        try (engine) {
          try {
            var client = engine.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
            EngineTestHarness.addRootAsThePerson(engine, folder);
            var operation = new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.BULK_REINDEX.value()).orElseThrow();
            var handlers = new HandlerRegistry();
            handlers.register(CoreOperationCatalog.BULK_REINDEX, new BulkReindexHandler(RecordedBulkPlan.Profile.USER_BULK,
                engine.recordedIngestion(), ignored -> List.of(new RootBinding(folder, null)), () -> client, List::of));
            var executor = new OperationExecutorImpl(engine.operationAttempts(), engine.admission(), handlers, null, Map.of(), clock,
                authority.trust(), authority.sources(), null, authority.capsules());
            var origin = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "frozen-collection-test",
                Optional.of("frozen-collection-session"), Optional.empty(), TransportTag.BUTTON,
                EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
            var provenance = EngineProvenance.invocation(origin, ExecutorTag.UI, Instant.now(clock), Optional.empty());
            String arguments = "{\"corpusIds\":[\"default\"]}";
            String operationKey = OperationKeys.generate(clock);
            var prepared = (OperationDispatchPlan.Ready) executor.prepare(operation, arguments, provenance, origin, operationKey, true);
            String approval = authority.capsules().mintPrepared(operation.id().value(), arguments,
                SourceTier.valueOf(origin.sourceTier()), operationKey, prepared.preparationNonce());
            var accepted = executor.dispatch(operation, arguments, provenance, Optional.of(approval), origin,
                operationKey, prepared.preparationNonce());
            assertTrue(accepted.success(), accepted.toString());
            var row = operations.find(operationKey).orElseThrow();
            var frozen = new RecordedBulkPlanResolver().resolve(row, operations.acceptedPreparation(row.id()).orElseThrow()).scope();
            assertEquals(folder, frozen.roots().getFirst().path());
            assertNull(frozen.roots().getFirst().collection(), "the actual accepted plan freezes default");
            assertTrue(opening.awaitReached(120, TimeUnit.SECONDS));
            assertTrue(client.pauseMigration("frozen_default", TestEngineContexts.FOREGROUND));
            AtomicFileWrites.replace(data.resolve("watched_roots.json"), new ObjectMapper().writeValueAsBytes(
                Map.of("schemaVersion", 1, "roots", List.of(Map.of("path", folder.toString(), "collection", "C")))));
            opening.release();
            assertTrue(await(() -> {
              try (var view = server.get().captureServingView()) {
                return !view.ingestRuntime().openedIndexPath().equals(view.searchRuntime().openedIndexPath());
              }
            }));
            Path file = Files.writeString(folder.resolve("unseen.txt"), EngineTestHarness.chunkedContent("frozendefaultmarker"));
            assertEquals(1, client.submitBatch(List.of(file), TestEngineContexts.FOREGROUND).getAcceptedCount());
            assertTrue(await(() -> {
              try (var view = server.get().captureServingView()) {
                return view.ingestRuntime().indexCountOps().countByIdAndChunksStrict(file.toString()) >= 3;
              }
            }));
            String replayLabel;
            Path legacyData = Files.createDirectory(data.resolve("legacy"));
            try (var legacy = new SqliteJobQueue(legacyData.resolve("jobs.db"))) {
              legacy.open();
              assertTrue(legacy.enqueueAndBufferFileForGeneration("green", JobQueue.EnqueueEntry.stat(file), null));
              legacy.recoverLegacyCollections("green", List.of(new RootBinding(folder, "C")), frozen);
              replayLabel = legacy.pollPending(1).getFirst().collection();
            }
            assertNull(replayLabel);
            try (var view = server.get().captureServingView()) {
              assertEquals(replayLabel, view.ingestRuntime().documentFieldOps().getDocumentField(file.toString(), SchemaFields.COLLECTION),
                  "live and legacy replay must both obey the frozen default for a file absent from the plan");
            }
            assertTrue(client.deleteDocsByCollection("C", TestEngineContexts.FOREGROUND) >= 0);
            try (var view = server.get().captureServingView()) {
              assertTrue(view.ingestRuntime().indexCountOps().countByIdAndChunksStrict(file.toString()) >= 3,
                  "deleting today's declaration label must leave the frozen-default parent and chunks");
            }
            assertTrue(client.resumeMigration(TestEngineContexts.FOREGROUND));
            assertTrue(switching.awaitReached(180, TimeUnit.SECONDS));
            switching.release();
            assertTrue(activated.await(180, TimeUnit.SECONDS));
            try (var view = server.get().captureServingView()) {
              assertTrue(view.searchRuntime().indexCountOps().countByIdAndChunksStrict(file.toString()) >= 3,
                  "the parent and chunks must survive ordered replay and cutover before any new scan");
            }
            var scan = client.syncDirectory(folder.toString(), false, TestEngineContexts.FOREGROUND);
            assertEquals("", scan.getError());
            assertTrue(await(() -> {
              try (var view = server.get().captureServingView()) {
                return "C".equals(view.searchRuntime().documentFieldOps().getDocumentField(file.toString(), SchemaFields.COLLECTION));
              }
            }), "after cutover, the scan must assign today's collection C");
          } finally {
            opening.release();
            switching.release();
            // Durable operation bodies must drain before the fixture closes its Engine.
            engine.admission().beginClosing();
            engine.operationAttempts().beginClosing();
            engine.admission().cancelInteractive("test shutdown");
            engine.quiesceProducers();
          }
        } finally {
          engine.executors().close();
        }
      }
    } finally {
      opening.release();
      switching.release();
      if (previousData == null) System.clearProperty("justsearch.data.dir"); else System.setProperty("justsearch.data.dir", previousData);
      if (previousHome == null) System.clearProperty("user.home"); else System.setProperty("user.home", previousHome);
      var installed = ConfigStore.globalOrNull();
      if (installed != null) ConfigStore.restoreGlobal(installed, previousConfig);
    }
  }

  private static boolean await(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
    while (System.nanoTime() < deadline) {
      if (condition.call()) return true;
      Thread.sleep(50);
    }
    return condition.call();
  }
}
