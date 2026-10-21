/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.chat.SelfTestSession;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.inference.LlamaDevicePlan;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeActivationCpuDevicePlanTest {
  private String nativeFixtureOs;

  @org.junit.jupiter.api.BeforeEach
  void selectWindowsNativeFixture() {
    nativeFixtureOs = System.getProperty("os.name");
    System.setProperty("os.name", "Windows 11");
  }

  @org.junit.jupiter.api.AfterEach
  void restoreNativeFixtureOs() {
    if (nativeFixtureOs == null) System.clearProperty("os.name");
    else System.setProperty("os.name", nativeFixtureOs);
  }

  @TempDir Path tmp;

  @Test
  void automaticActivationUsesTheResolvedProfileAndKeepsExplicitOverrides() throws Exception {
    var installed = new ConfigStore(ResolvedConfig.builder()
        .put("justsearch.gpu.layers", 150, "auto_detected", null, "99").build());
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(installed);
    try {
      var method = RuntimeActivationService.class.getDeclaredMethod("selfTestDevicePlan", UiSettings.class);
      method.setAccessible(true);
      UiSettings automatic = new UiSettings();
      assertEquals(99, ((LlamaDevicePlan) method.invoke(null, automatic)).gpuLayers(),
          "activation follows the same resolved GPU profile as the serving launch");
      automatic.setGpuLayers(0);
      assertEquals(0, ((LlamaDevicePlan) method.invoke(null, automatic)).gpuLayers());
      automatic.setGpuLayers(12);
      assertEquals(12, ((LlamaDevicePlan) method.invoke(null, automatic)).gpuLayers());
      var builder = ResolvedConfig.builder();
      builder.put("justsearch.gpu.layers", 400, "env_var", "test", "0");
      installed.update(builder.build());
      assertEquals(0, ((LlamaDevicePlan) method.invoke(null, automatic)).gpuLayers(),
          "the effective environment override also applies to the self-test");
      assertEquals(12, automatic.getGpuLayers());
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void gpuOffSelfTestUsesCpuPlanAndCanPassWithoutNvmlOrAVramIncrease() throws Exception {
    var builder = ResolvedConfig.builder();
    builder.putSettings("justsearch.gpu.enabled", "false");
    var installed = new ConfigStore(builder.build());
    var previous = ConfigStore.globalOrNull();
    String previousHome = System.getProperty("justsearch.home");
    ConfigStore.setGlobal(installed);
    System.setProperty("justsearch.home", tmp.toString());
    try (var executors = new TestEngineExecutors()) {
      var service = new RuntimeActivationService(executors, OnlineAiService.unavailable(),
          new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json")),
          mock(GpuCapabilitiesService.class), null);
      AtomicReference<SelfTestSpec> captured = new AtomicReference<>();
      var session = new SelfTestSession<SelfTestSpec, SelfTestRun>() {
        @Override public SelfTestRun run(SelfTestSpec spec) {
          captured.set(spec);
          return SelfTestRun.passed();
        }
        @Override public void close() {}
      };
      var method = RuntimeActivationService.class.getDeclaredMethod("runSelfTest",
          SelfTestSession.class, Path.class, Path.class, UiSettings.class);
      method.setAccessible(true);
      UiSettings settings = new UiSettings();
      settings.setGpuLayers(99);
      var result = (RuntimeActivationService.SelfTestResult) method.invoke(service, session,
          tmp.resolve("llama-server.exe"), tmp.resolve("chat.gguf"), settings);
      assertEquals("passed", result.result());
      var spec = captured.get();
      assertNotNull(spec);
      assertEquals("0", spec.command().get(spec.command().indexOf("-ngl") + 1));
      assertEquals("none", spec.command().get(spec.command().indexOf("--device") + 1));
      assertTrue(spec.command().contains("--no-mmproj-offload"));
      var environment = new java.util.HashMap<>(spec.environment());
      String privateKey = environment.remove("LLAMA_API_KEY");
      assertTrue(privateKey != null && privateKey.matches("[A-Za-z0-9_-]{43}"));
      assertEquals(Map.of("CUDA_VISIBLE_DEVICES", "-1"), environment);
      assertEquals(99, settings.getGpuLayers(), "probing must not rewrite the saved preference");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
      if (previousHome == null) System.clearProperty("justsearch.home");
      else System.setProperty("justsearch.home", previousHome);
    }
  }
}
