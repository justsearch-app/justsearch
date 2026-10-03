/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.MigrationStartRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(180)
final class KnowledgeServerLiveMigrationTest {
  @Test
  void twoCompleteLiveMigrationsReuseTheServerAndMigrationThreads(@TempDir Path tempDir)
      throws Exception {
    var layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    var server = org.mockito.Mockito.spy(new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null));
    org.mockito.Mockito.doNothing().when(server)
        .startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    var restarts = new AtomicInteger();
    setField(server, "migrationRestartAction", (Runnable) restarts::incrementAndGet);
    var enumerations = new AtomicInteger();
    server.installProjectionSeedSources(List.of(new ProjectionSeedSource() {
      @Override public String sourceId() { return "memory"; }
      @Override public void enumerate(java.util.function.Consumer<AcceptedProjection> sink) {
        int revision = enumerations.incrementAndGet();
        sink.accept(new AcceptedProjection("memory", "note", revision,
            AcceptedProjection.Kind.UPSERT, "{\"content\":\"revision " + revision + "\"}"));
      }
    }));
    try {
      server.start();
      ((CountDownLatch) getField(server, "modelReadyLatch")).countDown();
      var manager = (IndexGenerationManager) getField(server, "indexGenerationManager");
      String active = manager.readStateBestEffort().active_generation();
      Thread previousEnumerator = null;
      Thread previousMonitor = null;
      for (int migration = 1; migration <= 2; migration++) {
        var started = server.appServices().ingestService().startMigration(
            MigrationStartRequest.newBuilder().setReason("manual")
                .setProjectionSourceIdsPresent(true).addProjectionSourceIds("memory").build(),
            CallContext.none());
        assertTrue(started.getAccepted(), started::getError);
        assertTrue(server.beginUnrecordedBuildingLiveAsync(
            started.getBuildingGenerationId(), restarts::incrementAndGet).get(30, TimeUnit.SECONDS),
            "migration " + migration + " must open live in the same server");
        Thread enumerator = (Thread) getField(server, "migrationEnumeratorThread");
        Thread monitor = (Thread) getField(server, "migrationCutoverThread");
        assertNotNull(enumerator);
        assertNotNull(monitor);
        assertNotEquals(previousEnumerator, enumerator);
        assertNotEquals(previousMonitor, monitor);
        enumerator.join(30_000);
        monitor.join(30_000);
        assertFalse(enumerator.isAlive(), "each candidate must finish its own enumeration");
        assertFalse(monitor.isAlive(), "each candidate must finish live promotion");
        assertEquals(started.getBuildingGenerationId(), manager.readStateBestEffort().active_generation());
        assertEquals(migration, enumerations.get());
        try (var serving = server.captureServingView()) {
          assertEquals("revision " + migration, serving.searchRuntime().documentFieldOps()
              .getDocumentField(new AcceptedProjection("memory", "note", migration,
                  AcceptedProjection.Kind.UPSERT, "{}").indexId(), SchemaFields.CONTENT));
        }
        var retirement = KnowledgeServer.class.getDeclaredMethod("retryCommittedGenerationRetirement");
        retirement.setAccessible(true);
        retirement.invoke(server);
        assertFalse(Files.exists(manager.resolveGenerationPathStrict(active)),
            "the completed predecessor must release capacity before another migration");
        assertEquals(0, restarts.get());
        active = started.getBuildingGenerationId();
        previousEnumerator = enumerator;
        previousMonitor = monitor;
      }
    } finally {
      server.close();
    }
  }

  @Test
  void unconfiguredServerStartsWithZeroFailedJobBudget(@TempDir Path tempDir) throws Exception {
    var layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    try (var server = new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null)) {
      assertEquals(0, getField(server, "migrationCutoverMaxFailedJobs"));
    }
  }

  private static Object getField(KnowledgeServer server, String name) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(server);
  }

  private static void setField(KnowledgeServer server, String name, Object value) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(server, value);
  }
}
