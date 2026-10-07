/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.chat.DeviceLostRecovery;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.app.services.chat.ChatServerControllerImpl;
import io.justsearch.ort.DeviceHealth;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A step 10 with Spec E §6.2 item 6, wired as the Engine composes it: an encoder run that
 * finds its CUDA device lost reaches the chat controller the Engine's inference factory builds,
 * which reads the restart path the composition root bound and, within the limit, stops chat with
 * DEVICE_LOST and asks for the Engine restart. CUDA device 73 is used by no other test here.
 */
@Timeout(60)
final class EngineDeviceLossWitnessTest {

  @Test
  void anEncoderDeviceLossRestartsTheEngineThroughTheBoundPath(@TempDir Path tempDir)
      throws Exception {
    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        (gpu, executors, ingestion, indexComponent, encoderComponent) -> {
          throw new AssertionError("the index half is not composed in this test");
        }, 30_000, 5_000);
    var reads = new AtomicInteger();
    var restarted = new CountDownLatch(1);
    root.bindDeviceLostRecovery(new DeviceLostRecovery() {
      @Override
      public List<Instant> priorRestarts() {
        reads.incrementAndGet();
        return List.of();
      }

      @Override
      public void requestRestart() {
        restarted.countDown();
      }
    });
    try (var manager = BootstrapInferenceFactory.createInferenceManager(root.executors(), true,
        inferenceConfig(tempDir), tempDir.toString(), null,
        org.slf4j.LoggerFactory.getLogger(EngineDeviceLossWitnessTest.class))) {
      var routerField = io.justsearch.app.inference.InferenceLifecycleManager.class
          .getDeclaredField("actuationRouter");
      routerField.setAccessible(true);
      var controller = (ChatServerControllerImpl) routerField.get(manager);

      DeviceHealth.forDevice(73).reportLost(719);

      assertTrue(restarted.await(10, TimeUnit.SECONDS), "the bound Engine restart is requested");
      assertEquals(1, reads.get(), "the limit is read from the bound path");
      assertEquals(StopReason.DEVICE_LOST, controller.desiredInputs().terminal());
    } finally {
      root.close();
      root.processResources().close();
    }
  }

  private static io.justsearch.configuration.resolved.ResolvedConfig inferenceConfig(Path tempDir)
      throws Exception {
    var config = new io.justsearch.configuration.resolved.ResolvedConfigBuilder();
    config.putDefault("justsearch.home", tempDir.toString());
    config.putDefault("justsearch.server.exe",
        Files.writeString(tempDir.resolve("llama-server.exe"), "fixture").toString());
    config.put("justsearch.llm.model_path", 400, "env_var", "test",
        Files.writeString(tempDir.resolve("model.gguf"), "fixture").toString());
    config.putDefault("justsearch.inference.external_endpoint", "http://127.0.0.1:9");
    config.putDefault("justsearch.context.size", "4096");
    config.putDefault("justsearch.gpu.layers", "0");
    config.putDefault("justsearch.ai.disabled", "false");
    config.putDefault("justsearch.llm.enabled", "true");
    return config.build();
  }
}
