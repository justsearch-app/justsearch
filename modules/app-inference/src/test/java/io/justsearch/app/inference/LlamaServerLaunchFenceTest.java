/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

/**
 * Design D4: while the answer model's files are being removed, starting the managed answer server
 * is refused before any process is launched, as a refusal rather than a failed start; an admitted
 * launch stays registered with the removal lock until its process exists.
 */
@Tag("cross-platform")
final class LlamaServerLaunchFenceTest {
  private static final String REMOVING = "Removing files - try again when it finishes";

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
  void aFencedStartLaunchesNothingAndIsARefusal() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake-llama"));
    ops = ops();
    ops.setLaunchFence(() -> {
      throw new IllegalStateException(REMOVING);
    });

    ModeTransitionException refused = assertThrows(ModeTransitionException.class,
        () -> ops.startLlamaServer(request(executable)));

    assertInstanceOf(ManagedLaunchRefusedException.class, refused, "a refusal, not a failed start");
    assertEquals(ModeTransitionException.Reason.ALREADY_TRANSITIONING, refused.reason());
    assertTrue(refused.getMessage().endsWith(REMOVING), refused.getMessage());
    assertEquals(List.of(), HealthyFakeLlamaServer.launches(tmp.resolve("fake-llama")),
        "no llama-server process was started");
  }

  @Test
  void anAdmittedLaunchIsRegisteredUntilItsProcessHasStarted() throws Exception {
    Path fake = tmp.resolve("fake-llama");
    Path executable = HealthyFakeLlamaServer.install(fake);
    ops = ops();
    List<String> seen = new CopyOnWriteArrayList<>();
    ops.setLaunchFence(() -> {
      seen.add("admitted; a managed process is live: " + ops.hasLiveManagedProcess());
      return () -> seen.add("released; a managed process is live: " + ops.hasLiveManagedProcess());
    });

    LlamaServerOps.StartResult start = ops.startLlamaServer(request(executable));
    ops.waitForServerHealth(start);

    assertEquals(List.of("admitted; a managed process is live: false",
            "released; a managed process is live: true"), seen,
        "the registration covers the process start and ends once the process exists");
    assertEquals(1, HealthyFakeLlamaServer.launches(fake).size());
  }

  private LlamaServerOps ops() {
    GpuCapabilitiesService gpu = Mockito.mock(GpuCapabilitiesService.class);
    return new LlamaServerOps(
        new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(), new ObjectMapper(), gpu, () -> Mode.OFFLINE,
        new NoopPropsObserver(), ignored -> {}, (ignored, guard) -> {},
        NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop());
  }

  private LlamaServerOps.StartRequest request(Path executable) throws Exception {
    Path model = tmp.resolve("model.gguf");
    if (!Files.exists(model)) Files.createFile(model);
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putDefault("justsearch.data.dir", tmp.resolve("data").toString());
    String cache = System.getenv("JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR");
    if (cache != null && !cache.isBlank()) {
      builder.putDefault("justsearch.ort.optimized_cache_dir", cache);
    }
    InferenceConfig config = new InferenceConfig(executable, model, null, freePort(), 4096, 0, false);
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
