/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec A §9.2 U9, U10 (STARTING), I4 and I5 against a real launch: the managed launch path of
 * {@link LlamaServerOps} starts a separate llama-server stand-in process ({@link
 * HealthyFakeLlamaServer} with the F1 failure options), reads its exit and its log, and decides
 * whether to relaunch. The window is GPU-derived (ladder 32768, 16384, 8192, ...) unless the test
 * sets an override.
 */
final class LlamaServerStartFailureTest {
  private static final int GPU_LAYERS = 99;

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

  /**
   * I4 (preservation, reconciliation F15): a window that does not fit at the top rung costs one
   * rung, not inference. One step-down to 16384, and the server there is healthy.
   */
  @Test
  void aWindowThatDoesNotFitStepsDownOneRungAndServes() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake-llama"), "--max-ctx", "16384");
    ops = ops();
    LlamaServerOps.StartResult start = ops.startLlamaServer(request(executable, Map.of()));

    ops.waitForServerHealth(start);

    List<List<String>> launches = HealthyFakeLlamaServer.launches(tmp.resolve("fake-llama"));
    assertEquals(List.of(32768, 16384), windows(launches), "exactly one step-down: " + launches);
    assertEquals(16384, ops.contextWindow().rung());
    assertEquals("stepped-from:32768", ops.contextWindow().reason());
    assertTrue(ops.activeManagedCandidateAlive(start), "the server at 16384 is serving");
  }

  /**
   * U9 / T6: the rung a RESOURCE exit stepped down to is sticky for this Engine. The next start of
   * the same configuration begins at 16384 instead of failing at 32768 again.
   */
  @Test
  void theSteppedDownRungIsWhereTheNextStartOfThatConfigurationBegins() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake-llama"), "--max-ctx", "16384");
    ops = ops();
    LlamaServerOps.StartResult first = ops.startLlamaServer(request(executable, Map.of()));
    ops.waitForServerHealth(first);
    assertEquals(16384, ops.contextWindow().rung());
    ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);

    LlamaServerOps.StartResult again = ops.startLlamaServer(request(executable, Map.of()));
    ops.waitForServerHealth(again);

    List<List<String>> launches = HealthyFakeLlamaServer.launches(tmp.resolve("fake-llama"));
    assertEquals(List.of(32768, 16384, 16384), windows(launches),
        "the second start begins at the rung the first one reached: " + launches);
    assertEquals(16384, ops.contextWindow().rung());
    assertEquals("stepped-from:32768", ops.contextWindow().reason());
  }

  /**
   * U9 / T7 and P4: an operator's explicit window is never reduced. The RESOURCE exit fails the
   * start after exactly one launch, with resource_exhausted.
   */
  @Test
  void anOverrideThatDoesNotFitFailsWithoutAStepDown() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake-llama"), "--max-ctx", "16384");
    ops = ops();
    LlamaServerOps.StartResult start =
        ops.startLlamaServer(request(executable, Map.of("justsearch.context.size", "32768")));

    ModeTransitionException failure =
        assertThrows(ModeTransitionException.class, () -> ops.waitForServerHealth(start));

    assertEquals(ModeTransitionException.Reason.PROCESS_EXITED, failure.reason());
    List<List<String>> launches = HealthyFakeLlamaServer.launches(tmp.resolve("fake-llama"));
    assertEquals(List.of(32768), windows(launches), "an override is not stepped down: " + launches);
    assertEquals("RESOURCE_EXHAUSTED", failedCode(failure));
  }

  /**
   * I5 / T9: a model the build cannot load fails at every rung, so it fails after exactly one
   * launch with model_load_failed instead of walking the ladder. The line is the real b8571
   * refusal (tempdoc 346).
   */
  @Test
  void anUnknownArchitectureFailsAfterExactlyOneLaunch() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake-llama"),
        "--log-line", "llama_model_load: error loading model: error loading model architecture:"
            + " unknown model architecture: 'qwen35'",
        "--exit-code", "1");
    ops = ops();
    LlamaServerOps.StartResult start = ops.startLlamaServer(request(executable, Map.of()));

    ModeTransitionException failure =
        assertThrows(ModeTransitionException.class, () -> ops.waitForServerHealth(start));

    List<List<String>> launches = HealthyFakeLlamaServer.launches(tmp.resolve("fake-llama"));
    assertEquals(1, launches.size(), "an unknown architecture is not retried: " + launches);
    assertEquals("MODEL_LOAD_FAILED", failedCode(failure));
    assertTrue(failure.getMessage().contains("unknown model architecture"), failure.getMessage());
  }

  /** U10 / T10: an unclassified exit while starting fails at once; no rung, no same-config retry. */
  @Test
  void anUnclassifiedExitWhileStartingFailsAtOnce() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake-llama"),
        "--log-line", "main: exiting due to an unexpected error", "--exit-code", "1");
    ops = ops();
    LlamaServerOps.StartResult start = ops.startLlamaServer(request(executable, Map.of()));

    ModeTransitionException failure =
        assertThrows(ModeTransitionException.class, () -> ops.waitForServerHealth(start));

    List<List<String>> launches = HealthyFakeLlamaServer.launches(tmp.resolve("fake-llama"));
    assertEquals(1, launches.size(), "an unclassified exit is not retried: " + launches);
    assertEquals("START_FAILED", failedCode(failure));
  }

  // ==================== helpers ====================

  /**
   * The chat FAILED code the start failure carries, or "none" on a tree whose failures carry none.
   * Reflective so this file compiles on the tree before Spec A step 4.
   */
  private static String failedCode(Throwable failure) throws Exception {
    Method code;
    try {
      code = failure.getClass().getMethod("failedCode");
    } catch (NoSuchMethodException none) {
      return "none";
    }
    Object value = code.invoke(failure);
    assertNotNull(value, "a classified start failure has a code");
    return value.toString();
  }

  private static List<Integer> windows(List<List<String>> launches) {
    return launches.stream().map(argv -> Integer.parseInt(argv.get(argv.indexOf("-c") + 1))).toList();
  }

  private LlamaServerOps ops() {
    GpuCapabilitiesService gpu = Mockito.mock(GpuCapabilitiesService.class);
    return new LlamaServerOps(
        new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(), new ObjectMapper(), gpu, () -> Mode.OFFLINE,
        new NoopPropsObserver(), ignored -> {}, (ignored, guard) -> {},
        NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop());
  }

  private LlamaServerOps.StartRequest request(Path executable, Map<String, String> settings)
      throws Exception {
    Path model = tmp.resolve("model.gguf");
    if (!Files.exists(model)) Files.createFile(model);
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putDefault("justsearch.data.dir", tmp.resolve("data").toString());
    String cache = System.getenv("JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR");
    if (cache != null && !cache.isBlank()) {
      builder.putDefault("justsearch.ort.optimized_cache_dir", cache);
    }
    settings.forEach(builder::putSettings);
    ResolvedConfig resolved = builder.build();
    InferenceConfig config =
        new InferenceConfig(executable, model, null, freePort(), 4096, GPU_LAYERS, false);
    return new LlamaServerOps.StartRequest(
        new LlamaServerConfigContext(config, resolved),
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
