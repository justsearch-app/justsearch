/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(180)
final class EngineMigrationRestartDispatchTest {
  @Test
  void realClientDispatchesAcceptedStartAndRollbackButNotCutoverRequest(@TempDir Path dataDir)
      throws Exception {
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), Map.of());
    var restarts = new AtomicInteger();
    try (var root = new EngineRoot(
        gauge -> new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerConfig.load(), new InProcessWorkerSignalBus(gauge)),
        30_000L, 5_000, code -> { throw new AssertionError("unexpected fatal exit " + code); },
        restarts::incrementAndGet)) {
      var client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      assertFalse(client.requestCutover(false, TestEngineContexts.FOREGROUND).restartRequired(), "no building generation exists");
      assertEquals(0, restarts.get(), "an idle cutover cannot restart the Engine");
      var start = client.startMigration("manual", TestEngineContexts.FOREGROUND);
      assertTrue(start.accepted());
      assertTrue(start.restartRequired());
      assertEquals(1, restarts.get(), "the production client consumes the requirement");
      assertTrue(client.requestCutover(true, TestEngineContexts.FOREGROUND).restartRequired());
      assertEquals(1, restarts.get(), "requesting cutover is not promotion");
      // This fixture observes dispatch without terminating its JVM. Seed a genuine rollback
      // target; automatic promotion/reopen is proved separately under a process supervisor.
      new IndexGenerationManager(dataDir.resolve("index")).promoteBuildingGenerationToActive();
      var rollback = client.rollbackMigration(TestEngineContexts.FOREGROUND);
      assertTrue(rollback.accepted());
      assertTrue(rollback.restartRequired());
      assertEquals(2, restarts.get());
    }
  }
}
