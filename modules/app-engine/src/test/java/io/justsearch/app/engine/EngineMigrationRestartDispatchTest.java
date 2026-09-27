/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(180)
final class EngineMigrationRestartDispatchTest {
  private static final String OPERATION_KEY = "01994180-0000-7000-8000-000000000121";
  private static final String TARGET_FINGERPRINT = "a".repeat(64);

  @Test
  void recordedStartReturnsBuildingWitnessWithoutDispatchingRestart(@TempDir Path dataDir)
      throws Exception {
    Path indexBase = dataDir.resolve("index");
    EngineTestHarness.publishConfig(dataDir, indexBase, Map.of());
    var restarts = new AtomicInteger();
    try (var root =
        new EngineRoot(
            org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class),
            org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
            (gauge, executors, ingestion, indexComponent, encoderComponent) ->
                new KnowledgeServer(
                    executors,
                    WorkerConfig.load(),
                    new InProcessWorkerSignalBus(gauge),
                    io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
                    ingestion, indexComponent, encoderComponent),
            30_000L,
            5_000,
            code -> { throw new AssertionError("unexpected fatal exit " + code); },
            restarts::incrementAndGet)) {
      var client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      var before = new IndexGenerationManager(indexBase).readStateBestEffort();
      assertNotNull(before);
      assertFalse(before.active_generation().isBlank());
      var outcome =
          client.startRecordedMigration(
              OPERATION_KEY, "bulk_reindex", TARGET_FINGERPRINT, before.active_generation(),
              TestEngineContexts.FOREGROUND);

      assertTrue(outcome.accepted());
      assertFalse(outcome.restartRequired(), "live start carries no restart requirement");
      assertEquals("g-" + OPERATION_KEY, outcome.buildingGenerationId());
      assertEquals(before.active_generation(), outcome.activeGenerationId());
      assertEquals("MIGRATING", outcome.migrationState());
      assertEquals(
          0,
          restarts.get(),
          "recorded start leaves the durable owner to open Green live");
      var persisted = new IndexGenerationManager(indexBase).readStateBestEffort();
      assertEquals(outcome.buildingGenerationId(), persisted.building_generation());
    }
  }

  @Test
  void realClientStartsMigrationWithoutDispatchingRestart(@TempDir Path dataDir)
      throws Exception {
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), Map.of());
    var restarts = new AtomicInteger();
    try (var root = new EngineRoot(org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class), org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
        (gauge, executors, ingestion, indexComponent, encoderComponent) -> new KnowledgeServer(executors, WorkerConfig.load(), new InProcessWorkerSignalBus(gauge), io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), ingestion, indexComponent, encoderComponent),
        30_000L, 5_000, code -> { throw new AssertionError("unexpected fatal exit " + code); },
        restarts::incrementAndGet)) {
      var client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      assertFalse(client.requestCutover(false, TestEngineContexts.FOREGROUND).restartRequired(), "no building generation exists");
      assertEquals(0, restarts.get(), "an idle cutover cannot restart the Engine");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      boolean composed = false;
      while (System.nanoTime() < deadline) {
        var encoder = root.components().snapshot().components().stream()
            .filter(component -> "encoders".equals(component.spec().name()))
            .findFirst().orElseThrow();
        composed = encoder.state() != io.justsearch.core.component.ComponentState.STARTING
            && (encoder.appliedVersion() != null || encoder.evidence() != null
                || encoder.reasonCode() != null);
        if (composed) break;
        Thread.sleep(10);
      }
      assertTrue(composed, "live-start proof requires completed deferred model composition");
      var start = client.startMigration("manual", TestEngineContexts.FOREGROUND);
      assertTrue(start.accepted());
      assertFalse(start.restartRequired());
      assertEquals(0, restarts.get(), "the production client opens Green in this Engine");
      assertTrue(client.requestCutover(true, TestEngineContexts.FOREGROUND).restartRequired());
      assertEquals(0, restarts.get(), "requesting cutover is not promotion");
    }
  }
}
