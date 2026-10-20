/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.NativeTargets;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Windows filesystem selector fixtures also run in the Windows-native coverage lane. */
@Tag("cross-platform")
final class HeadlessAppCudaPolicyTest {
  @TempDir Path home;

  @Test
  void gpuPolicyVetoStopsCudaSelectionDespitePositiveLayers() throws Exception {
    assertNoSelection(false, true);
  }

  @Test
  void bootSnapshotExceptionStopsCudaSelectionWithoutAPriorVeto() throws Exception {
    assertNoSelection(true, true);
  }

  @Test
  void bootNullSnapshotPublishesGpuVeto() {
    assertFailedLoadVeto(false);
  }

  @Test
  void bootSnapshotExceptionPublishesGpuVeto() {
    assertFailedLoadVeto(true);
  }

  private void assertFailedLoadVeto(boolean throwsException) {
    String prior = System.getProperty("policy.gpu_acceleration_enabled");
    try (var policy = mockConstruction(EnterprisePolicyServiceImpl.class, (mock, context) -> {
      if (throwsException) when(mock.snapshot()).thenThrow(new IllegalStateException("load failed"));
      else when(mock.snapshot()).thenReturn(null);
    })) {
      System.clearProperty("policy.gpu_acceleration_enabled");
      HeadlessApp.loadPolicySources();
      var builder = ResolvedConfig.builder();
      builder.contributeEnvRegistry();
      assertFalse(builder.build().ai().gpuAccelerationAllowed());
    } finally { restore("policy.gpu_acceleration_enabled", prior); }
  }

  @Test
  void gpuPolicyVetoStopsNativePathPublication() throws Exception {
    assertNoSelection(false, false);
  }

  @Test
  void bootSnapshotExceptionStopsNativePathPublication() throws Exception {
    assertNoSelection(true, false);
  }

  private void assertNoSelection(boolean loadFails, boolean selectServer) throws Exception {
    Path baseline = home.resolve("llama-server.exe");
    Files.writeString(baseline, "baseline");
    Path cuda = Files.createDirectories(home.resolve("variants/cuda12"));
    Path nativeCuda = Files.createDirectories(home.resolve("native-bin/llama-server/variants/cuda12"));
    for (String file : List.of("llama-server.exe", "ggml-cuda.dll", "cudart64_12.dll",
        "cublas64_12.dll", "cublasLt64_12.dll")) {
      Files.writeString(cuda.resolve(file), "CUDA fixture");
      Files.writeString(nativeCuda.resolve(file), "CUDA fixture");
    }
    String priorPolicy = System.getProperty("policy.gpu_acceleration_enabled");
    String priorNative = System.getProperty("justsearch.onnxruntime.native_path");
    ConfigStore previous = ConfigStore.globalOrNull();
    NativeTargets windows = NativeTargets.forTarget("windows-x86_64");
    try (var targets = mockStatic(NativeTargets.class, CALLS_REAL_METHODS);
         var platform = mockStatic(PlatformPaths.class, CALLS_REAL_METHODS);
         var policy = mockConstruction(EnterprisePolicyServiceImpl.class,
             (mock, context) -> when(mock.snapshot()).thenThrow(new IllegalStateException("boot failed")))) {
      targets.when(NativeTargets::current).thenReturn(windows);
      platform.when(PlatformPaths::isWindows).thenReturn(true);
      platform.when(PlatformPaths::resolveDataDir).thenReturn(home);
      System.clearProperty("justsearch.onnxruntime.native_path");
      var builder = ResolvedConfig.builder();
      if (loadFails) {
        System.clearProperty("policy.gpu_acceleration_enabled");
        HeadlessApp.loadPolicySources();
        builder.contributeEnvRegistry();
      } else builder.putDefault("policy.gpu_acceleration_enabled", "false");
      builder.putDefault("justsearch.server.exe", baseline.toString());
      builder.putDefault("justsearch.gpu.layers", "99");
      var config = new ConfigStore(builder.build());
      ConfigStore.setGlobal(config);
      var settings = new UiSettings();
      settings.setGpuLayers(99);
      if (selectServer) assertNull(HeadlessApp.maybeAutoSelectCuda12Variant(settings, config));
      var mirror = HeadlessApp.class.getDeclaredMethod("maybeMirrorOrtNativePath", ConfigStore.class);
      mirror.setAccessible(true);
      assertDoesNotThrow(() -> mirror.invoke(null, config));
      assertNull(System.getProperty("justsearch.onnxruntime.native_path"));
      assertEquals(99, settings.getGpuLayers());
      assertEquals("CUDA fixture", Files.readString(cuda.resolve("llama-server.exe")));
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
      restore("policy.gpu_acceleration_enabled", priorPolicy);
      restore("justsearch.onnxruntime.native_path", priorNative);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key); else System.setProperty(key, value);
  }
}
