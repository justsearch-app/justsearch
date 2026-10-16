/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec E step E6 against a real launch ({@link HealthyFakeLlamaServer}): a GPU launch names the
 * pinned device only when one is handed to llama-server. With none (nothing chosen, or a driver
 * too old for the runtime) the launch line is the unpinned one from before the pin.
 */
final class LlamaServerDeviceLaunchTest {
  private static final String UUID = "GPU-8b20006f-755d-8a37-9762-7b385907e813";

  @TempDir Path tmp;
  private LlamaServerOps ops;

  @AfterEach
  void stopEverything() throws Exception {
    try {
      if (ops != null) {
        ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
        ops.shutdown();
      }
    } finally {
      HealthyFakeLlamaServer.endAll(tmp.resolve("fake-llama"));
    }
  }

  @Test
  void aPinnedGpuLaunchNamesTheDevice() throws Exception {
    List<String> argv = launchWith(Optional.of(UUID));

    assertTrue(
        Collections.indexOfSubList(argv, LlamaServerOps.PINNED_DEVICE_FLAGS) >= 0, argv.toString());
  }

  @Test
  void anUnpinnedGpuLaunchKeepsTheLineFromBeforeThePin() throws Exception {
    List<String> argv = launchWith(Optional.empty());

    assertFalse(argv.contains("--device"), argv.toString());
    assertFalse(argv.contains("-sm"), argv.toString());
    assertEquals("99", argv.get(argv.indexOf("-ngl") + 1), "the GPU layers are unchanged");
    assertEquals("-np", argv.get(argv.indexOf("-ngl") + 2), "the memory-plan block follows -ngl");
  }

  private List<String> launchWith(Optional<String> pinnedUuid) throws Exception {
    Path fake = tmp.resolve("fake-llama");
    Path executable = HealthyFakeLlamaServer.install(fake);
    GpuCapabilitiesService gpu = Mockito.mock(GpuCapabilitiesService.class);
    ops =
        new LlamaServerOps(
            new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
            HttpClient.newHttpClient(), new ObjectMapper(), gpu, () -> Mode.OFFLINE,
            new NoopPropsObserver(), ignored -> {}, (ignored, guard) -> {},
            NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop(), () -> pinnedUuid);
    LlamaServerOps.StartResult start = ops.startLlamaServer(request(executable));
    ops.waitForServerHealth(start);
    List<List<String>> launches = HealthyFakeLlamaServer.launches(fake);
    assertEquals(1, launches.size(), launches.toString());
    return launches.get(0);
  }

  private LlamaServerOps.StartRequest request(Path executable) throws Exception {
    Path model = tmp.resolve("model.gguf");
    if (!Files.exists(model)) Files.createFile(model);
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putDefault("justsearch.data.dir", tmp.resolve("data").toString());
    InferenceConfig config = new InferenceConfig(executable, model, null, freePort(), 4096, 99, false);
    return new LlamaServerOps.StartRequest(
        new LlamaServerConfigContext(config, builder.build()),
        LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL);
  }

  private static int freePort() throws java.io.IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static final class NoopPropsObserver implements PropsObserver {
    @Override
    public void onModelIdObserved(String modelId, LlamaServerConfigContext context) {}

    @Override
    public void onContextTokensObserved(int contextTokens) {}

    @Override
    public String observedModelId() {
      return null;
    }

    @Override
    public Integer observedContextTokens() {
      return null;
    }
  }
}
