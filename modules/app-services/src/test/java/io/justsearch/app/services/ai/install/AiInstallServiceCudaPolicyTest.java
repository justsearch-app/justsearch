/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.NativeTargets;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Filesystem-only Windows selector fixtures, selected in Windows-native CI as well as Linux. */
@Tag("cross-platform")
final class AiInstallServiceCudaPolicyTest {
  @TempDir Path home;

  private static EffectivePolicy gpuOff() {
    return new EffectivePolicy(true, true, true, false, false, List.of(), List.of(),
        "test", false, null, null, false);
  }

  @Test
  void gpuOffDoesNotCommitAnInstalledCudaExecutable() throws Exception {
    assertNoSelection(() -> gpuOff(), "applyCudaServerExe");
  }

  @Test
  void failedPolicyReadDoesNotCommitAnInstalledCudaExecutable() throws Exception {
    assertNoSelection(() -> { throw new IllegalStateException("policy unavailable"); }, "applyCudaServerExe");
    assertNoSelection(() -> null, "applyCudaServerExe");
  }

  @Test
  void gpuOffDoesNotPublishCudaNativePath() throws Exception {
    assertNoSelection(() -> gpuOff(), "applyOrtNativePath");
  }

  @Test
  void failedPolicyReadDoesNotPublishCudaNativePath() throws Exception {
    assertNoSelection(() -> { throw new IllegalStateException("policy unavailable"); }, "applyOrtNativePath");
    assertNoSelection(() -> null, "applyOrtNativePath");
  }

  private void assertNoSelection(EnterprisePolicyService policy, String name) throws Exception {
    Path cuda = Files.createDirectories(home.resolve("native-bin/llama-server/variants/cuda12"));
    for (String file : List.of("llama-server.exe", "cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll")) {
      Files.writeString(cuda.resolve(file), "retained CUDA bytes");
    }
    String previous = System.getProperty("justsearch.onnxruntime.native_path");
    UiSettingsStore settings = mock(UiSettingsStore.class);
    when(settings.inspect()).thenAnswer(ignored -> {
      fail("GPU policy must block automatic selection before settings are inspected");
      return null;
    });
    SettingsService owner = mock(SettingsService.class);
    var service = new AiInstallService(null, settings, null, policy, home, null, owner);
    NativeTargets windows = NativeTargets.forTarget("windows-x86_64");
    try (var targets = mockStatic(NativeTargets.class, CALLS_REAL_METHODS);
         var platform = mockStatic(PlatformPaths.class, CALLS_REAL_METHODS)) {
      targets.when(NativeTargets::current).thenReturn(windows);
      platform.when(PlatformPaths::isWindows).thenReturn(true);
      System.clearProperty("justsearch.onnxruntime.native_path");
      Method method = AiInstallService.class.getDeclaredMethod(name);
      method.setAccessible(true);
      assertDoesNotThrow(() -> assertEquals(false, method.invoke(service)));
      verifyNoInteractions(settings, owner);
      assertNull(System.getProperty("justsearch.onnxruntime.native_path"));
      assertEquals("retained CUDA bytes", Files.readString(cuda.resolve("llama-server.exe")));
    } finally {
      if (previous == null) System.clearProperty("justsearch.onnxruntime.native_path");
      else System.setProperty("justsearch.onnxruntime.native_path", previous);
    }
  }
}
