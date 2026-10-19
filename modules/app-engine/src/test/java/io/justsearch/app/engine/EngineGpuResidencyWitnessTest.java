/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec E E-S7w (S7 wiring): the Engine's own residency sampler raises external pressure on the
 * Engine's own bulk work gate, and the worker bus that the Engine composes when it starts the index
 * half (the production server factory) then yields bulk GPU work; after the clear it does not. The
 * chat controller's bound pressure input hears EXTERNAL_MEMORY and then NONE, and closing the
 * process resources closes the sampler's reader.
 */
@Timeout(180)
final class EngineGpuResidencyWitnessTest {

  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * MIB;
  private static final long TOTAL = 12 * GIB;

  // E-S7w: the Engine's sampler holds the composed worker bus, and releases it on the clear
  @Test
  void enginePressureMakesTheComposedBusYield(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), Map.of());
    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        30_000L, 5_000);
    var reading = new AtomicReference<>(sample(300 * MIB, 6 * GIB));
    var readerClosed = new AtomicBoolean();
    var tick = new AtomicReference<Runnable>();
    var signals = new CopyOnWriteArrayList<PressureSignal>();
    try {
      root.bulkWork().setProfile(BulkWorkGate.Profile.P12);
      ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
      when(manual.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
        tick.set(inv.getArgument(0));
        return mock(ScheduledFuture.class);
      });
      root.startGpuResidency(new GpuMemoryReader() {
        @Override public GpuMemorySample sampleNow() {
          return reading.get();
        }

        @Override public void close() {
          readerClosed.set(true);
        }
      }, manual, () -> {});
      ((BootstrapInferenceFactory.GenerationLifetimeSource) root.executors())
          .bindPressureReceiver(signals::add);
      root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      WorkerSignalBus bus = composedBus(root);

      assertFalse(bus.shouldYieldGpuBackfill(), "no pressure yet");
      for (int i = 0; i < 3; i++) tick.get().run();
      assertTrue(root.gpuResidency().latest().externalPressure());
      assertTrue(bus.isBulkHeld(), "the composed bus reads the Engine's own gate");
      assertTrue(bus.shouldYieldGpuBackfill(), "the composed bus yields while external use holds");
      assertEquals(List.of(PressureSignal.EXTERNAL_MEMORY), signals);

      reading.set(sample(4 * GIB, 6 * GIB));
      for (int i = 0; i < 5; i++) tick.get().run();
      assertFalse(bus.shouldYieldGpuBackfill(), "after the clear the composed bus does not yield");
      assertEquals(List.of(PressureSignal.EXTERNAL_MEMORY, PressureSignal.NONE), signals);
    } finally {
      root.close();
      root.processResources().close();
    }
    assertTrue(readerClosed.get(), "the process resources close the sampler's reader");
  }

  // section 3.4: the chat controller the Engine's inference factory builds hears the pressure
  @Test
  void chatControllerHearsExternalMemoryPressure(@TempDir Path tempDir) throws Exception {
    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        (gpu, executors, ingestion, indexComponent, encoderComponent) -> {
          throw new AssertionError("the index half is not composed in this test");
        }, 30_000, 5_000);
    var reading = new AtomicReference<>(sample(300 * MIB, 6 * GIB));
    var tick = new AtomicReference<Runnable>();
    try {
      root.bulkWork().setProfile(BulkWorkGate.Profile.P12);
      ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
      when(manual.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
        tick.set(inv.getArgument(0));
        return mock(ScheduledFuture.class);
      });
      root.startGpuResidency(new GpuMemoryReader() {
        @Override public GpuMemorySample sampleNow() {
          return reading.get();
        }

        @Override public void close() {}
      }, manual, () -> {});
      try (var manager = BootstrapInferenceFactory.createInferenceManager(root.executors(), true,
          inferenceConfig(tempDir), tempDir.toString(), null,
          org.slf4j.LoggerFactory.getLogger(EngineGpuResidencyWitnessTest.class))) {
        var routerField = io.justsearch.app.inference.InferenceLifecycleManager.class
            .getDeclaredField("actuationRouter");
        routerField.setAccessible(true);
        var controller = (io.justsearch.app.services.chat.ChatServerControllerImpl)
            routerField.get(manager);
        assertEquals(PressureSignal.NONE, controller.reportedPressure());
        for (int i = 0; i < 3; i++) tick.get().run();
        assertEquals(PressureSignal.EXTERNAL_MEMORY, controller.reportedPressure());
        reading.set(sample(4 * GIB, 6 * GIB));
        for (int i = 0; i < 5; i++) tick.get().run();
        assertEquals(PressureSignal.NONE, controller.reportedPressure());
      }
    } finally {
      root.close();
      root.processResources().close();
    }
  }

  private static io.justsearch.configuration.resolved.ResolvedConfig inferenceConfig(Path tempDir)
      throws Exception {
    var config = new io.justsearch.configuration.resolved.ResolvedConfigBuilder();
    config.putDefault("justsearch.home", tempDir.toString());
    config.putDefault("justsearch.server.exe", java.nio.file.Files.writeString(
        tempDir.resolve("llama-server.exe"), "fixture").toString());
    if (!io.justsearch.configuration.PlatformPaths.isWindows()) {
      assertTrue(tempDir.resolve("llama-server.exe").toFile().setExecutable(true));
    }
    config.put("justsearch.llm.model_path", 400, "env_var", "test", java.nio.file.Files.writeString(
        tempDir.resolve("model.gguf"), "fixture").toString());
    config.putDefault("justsearch.inference.external_endpoint", "http://127.0.0.1:9");
    config.putDefault("justsearch.context.size", "4096");
    config.putDefault("justsearch.gpu.layers", "0");
    config.putDefault("justsearch.ai.disabled", "false");
    config.putDefault("justsearch.llm.enabled", "true");
    return config.build();
  }

  private static WorkerSignalBus composedBus(EngineRoot root) throws Exception {
    var serverField = EngineRoot.class.getDeclaredField("server");
    serverField.setAccessible(true);
    var server = (KnowledgeServer) serverField.get(root);
    var busField = KnowledgeServer.class.getDeclaredField("signalBus");
    busField.setAccessible(true);
    return (WorkerSignalBus) busField.get(server);
  }

  private static GpuMemorySample sample(long free, long external) {
    long used = TOTAL - free;
    return new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(),
        Optional.of(new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty())),
        Optional.of("NVIDIA GeForce RTX 4070"), OptionalLong.of(TOTAL), OptionalLong.of(used),
        Map.of(ProcessHandle.current().pid(), new ProcessMemory(used - external, 0L)),
        Instant.now());
  }
}
