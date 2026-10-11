/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.api.AiRuntimeActivationStatus;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.chat.SelfTestSession;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * The activation self-test candidate answers one tiny request and never resumes a conversation, so
 * it launches with the host prompt cache disabled in every mode. Without an explicit
 * {@code --cache-ram}, llama.cpp enables its 8192 MiB default for the candidate.
 *
 * <p>The candidate's argv is observed where the controller session receives it; the session
 * declines to launch it, so no process starts.
 */
final class RuntimeActivationSelfTestPromptCacheTest {

  private static final long GIB = 1024L * 1024 * 1024;

  @TempDir Path tmp;

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();
  private final Map<String, String> previousProps = new HashMap<>();
  private ConfigStore previousStore;
  private RuntimeActivationService service;

  @AfterEach
  void restore() {
    try {
      if (service != null) service.close();
    } finally {
      previousProps.forEach((key, value) -> {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
      });
      TestResolvedConfigHelper.restoreGlobal(previousStore);
      processExecutors.close();
    }
  }

  @ParameterizedTest(name = "evaluation mode {0}")
  @ValueSource(booleans = {false, true})
  void theSelfTestCandidateRunsWithTheHostPromptCacheDisabled(boolean evaluationMode)
      throws Exception {
    setProp("justsearch.home", tmp.toAbsolutePath().toString());
    setProp("justsearch.data.dir", tmp.toAbsolutePath().toString());
    setProp("justsearch.eval.mode", Boolean.toString(evaluationMode));
    clearProp("justsearch.llm.prompt_cache_mib");
    clearProp("justsearch.chat.profile");
    clearProp("justsearch.server.exe");
    clearProp("justsearch.server.exe.source");
    previousStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeFromEnvironment();

    Path variant = tmp.resolve("native-bin/llama-server/variants/cuda12/llama-server.exe");
    Files.createDirectories(variant.getParent());
    Files.writeString(variant, "not runnable; the session never launches it", StandardCharsets.UTF_8);
    Path model = tmp.resolve("models").resolve("chat.gguf");
    Files.createDirectories(model.getParent());
    Files.writeString(model, "gguf-bytes", StandardCharsets.UTF_8);

    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    UiSettings initial = store.load();
    initial.setLlmModelPath(model.toString());
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));

    AtomicReference<SelfTestSpec> candidate = new AtomicReference<>();
    ChatHolds holds = Mockito.mock(ChatHolds.class);
    Mockito.when(holds.beginSelfTest("runtime-variant-activation")).thenAnswer(call ->
        new SelfTestSession<SelfTestSpec, SelfTestRun>() {
          @Override public SelfTestRun run(SelfTestSpec spec) {
            candidate.set(spec);
            return SelfTestRun.failed("observed only; not launched");
          }

          @Override public void close() {}
        });

    service = new RuntimeActivationService(processExecutors, OnlineAiService.unavailable(), store,
        gpuWithNvml(), null, null, null, null, holds, null, null);
    service.startActivate("cuda12");
    AiRuntimeActivationStatus status = awaitDone(service);

    SelfTestSpec spec = candidate.get();
    assertNotNull(spec, "the activation must hand its candidate to the controller session; state="
        + status.state + " code=" + status.errorCode + " message=" + status.message);
    List<String> argv = spec.command();
    assertEquals(1, argv.stream().filter("--cache-ram"::equals).count(),
        "the candidate must name its host prompt cache explicitly: " + argv);
    assertEquals("0", argv.get(argv.indexOf("--cache-ram") + 1),
        "the candidate never resumes a conversation, so its host prompt cache is off: " + argv);
  }

  private static GpuCapabilitiesService gpuWithNvml() {
    GpuCapabilities.Nvml nvml = new GpuCapabilities.Nvml(true, null, null, null, "580.00", 580, 0,
        1, 12 * GIB, 11 * GIB, 1 * GIB, 0, 0);
    GpuCapabilities.Effective effective = new GpuCapabilities.Effective(true, "nvml",
        GpuCapabilities.Confidence.HIGH, "580.00", 580, 0, 1, 12 * GIB, 11 * GIB, 1 * GIB,
        GpuCapabilities.Cuda.unknown());
    GpuCapabilitiesService gpu = Mockito.mock(GpuCapabilitiesService.class);
    Mockito.when(gpu.snapshot()).thenReturn(new GpuCapabilities(nvml, null, effective));
    return gpu;
  }

  private static AiRuntimeActivationStatus awaitDone(RuntimeActivationService svc)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline) {
      AiRuntimeActivationStatus status = svc.getActivationStatus();
      if (!"running".equalsIgnoreCase(status.state)) return status;
      Thread.sleep(25);
    }
    fail("Timed out waiting for runtime activation to finish");
    return svc.getActivationStatus();
  }

  private void setProp(String key, String value) {
    previousProps.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private void clearProp(String key) {
    previousProps.putIfAbsent(key, System.getProperty(key));
    System.clearProperty(key);
  }
}
