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
import io.justsearch.app.api.gpu.UserContextReader;
import io.justsearch.app.api.gpu.UserContextSample;
import io.justsearch.app.api.gpu.UserContextSample.NotificationState;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.core.scheduling.BulkWorkGate;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec E step E4 wiring (sections 3.4 and 4.4, CH7): the Engine's residency sampler takes its game
 * boot sample when it starts, before any scheduled tick, so a game already running holds the
 * Engine's bulk work gate for GAME_MODE and the chat controller built afterwards hears GAME before
 * its first start. {@code justsearch.gpu.game_detection = off} in the Engine's configuration keeps
 * the same game undetected, and closing the process resources closes the foreground reader.
 */
@Timeout(180)
final class EngineGameDetectionWitnessTest {

  private static final long GIB = 1024L * 1024L * 1024L;
  private static final long TOTAL = 12 * GIB;
  /** A foreground process that is not ours. */
  private static final long GAME_PID = 4_000_000_001L;

  // CH7: a game at boot is on the Engine's gate before the first tick, and chat hears GAME
  @Test
  void aGameAtBootHoldsBeforeTheFirstTickAndChatHearsIt(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), Map.of());
    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        30_000L, 5_000);
    var contextClosed = new AtomicBoolean();
    var tick = new AtomicReference<Runnable>();
    try {
      root.bulkWork().setProfile(BulkWorkGate.Profile.P12);
      root.startGpuResidency(reader(), userContext(contextClosed), manual(tick), () -> {});

      assertTrue(root.gpuResidency().latest().game(), "the boot sample saw the game");
      assertEquals(List.of(BulkWorkGate.Reason.GAME_MODE), root.bulkWork().verdict().reasons(),
          "the Engine's gate holds for the game before any scheduled tick");
      try (var manager = BootstrapInferenceFactory.createInferenceManager(root.executors(), true,
          inferenceConfig(tempDir), tempDir.toString(), null,
          org.slf4j.LoggerFactory.getLogger(EngineGameDetectionWitnessTest.class))) {
        var routerField = io.justsearch.app.inference.InferenceLifecycleManager.class
            .getDeclaredField("actuationRouter");
        routerField.setAccessible(true);
        var controller = (io.justsearch.app.services.chat.ChatServerControllerImpl)
            routerField.get(manager);
        assertEquals(PressureSignal.GAME, controller.reportedPressure(),
            "a chat controller built after the boot sample hears the game");
      }
    } finally {
      root.close();
      root.processResources().close();
    }
    assertTrue(contextClosed.get(), "the process resources close the foreground reader");
  }

  // justsearch.gpu.game_detection = off in the Engine's configuration: the same game is not one
  @Test
  void detectionOffInTheEngineConfigurationDetectsNoGame(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"),
        Map.of("justsearch.gpu.game_detection", "off"));
    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        30_000L, 5_000);
    var tick = new AtomicReference<Runnable>();
    try {
      root.bulkWork().setProfile(BulkWorkGate.Profile.P12);
      root.startGpuResidency(reader(), userContext(new AtomicBoolean()), manual(tick), () -> {});
      for (int i = 0; i < 3; i++) tick.get().run();
      assertFalse(root.gpuResidency().latest().game());
      assertFalse(root.bulkWork().held(), "no game is detected while detection is off");
    } finally {
      root.close();
      root.processResources().close();
    }
  }

  private static ScheduledExecutorService manual(AtomicReference<Runnable> tick) {
    ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
    when(manual.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
      tick.set(inv.getArgument(0));
      return mock(ScheduledFuture.class);
    });
    return manual;
  }

  private static GpuMemoryReader reader() {
    return new GpuMemoryReader() {
      @Override public GpuMemorySample sampleNow() {
        return new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(),
            Optional.of(new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty())),
            Optional.of("NVIDIA GeForce RTX 4070"), OptionalLong.of(TOTAL),
            OptionalLong.of(4 * GIB),
            Map.of(ProcessHandle.current().pid(), new ProcessMemory(2 * GIB, 0L), GAME_PID,
                new ProcessMemory(2 * GIB, 0L)),
            Instant.now());
      }

      @Override public void close() {}
    };
  }

  private static UserContextReader userContext(AtomicBoolean closed) {
    return new UserContextReader() {
      @Override public UserContextSample sampleNow() {
        return new UserContextSample(NotificationState.RUNNING_D3D_FULL_SCREEN,
            Optional.of(false), OptionalLong.of(GAME_PID), true, false);
      }

      @Override public void close() {
        closed.set(true);
      }
    };
  }

  private static io.justsearch.configuration.resolved.ResolvedConfig inferenceConfig(Path tempDir)
      throws Exception {
    var config = new io.justsearch.configuration.resolved.ResolvedConfigBuilder();
    config.putDefault("justsearch.home", tempDir.toString());
    config.putDefault("justsearch.server.exe", java.nio.file.Files.writeString(
        tempDir.resolve("llama-server.exe"), "fixture").toString());
    config.put("justsearch.llm.model_path", 400, "env_var", "test", java.nio.file.Files.writeString(
        tempDir.resolve("model.gguf"), "fixture").toString());
    config.putDefault("justsearch.inference.external_endpoint", "http://127.0.0.1:9");
    config.putDefault("justsearch.context.size", "4096");
    config.putDefault("justsearch.gpu.layers", "0");
    config.putDefault("justsearch.ai.disabled", "false");
    config.putDefault("justsearch.llm.enabled", "true");
    return config.build();
  }
}
