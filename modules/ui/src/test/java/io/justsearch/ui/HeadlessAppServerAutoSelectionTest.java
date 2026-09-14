/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessAppServerAutoSelectionTest {
  @TempDir Path directory;

  @Test
  void eligibleCudaDiscoveryReturnsAPathWithoutClaimingJvmAuthority() throws Exception {
    Path baseline = directory.resolve("llama-server.exe");
    Files.writeString(baseline, "fixture");
    Path cuda = Files.createDirectories(directory.resolve("variants/cuda12"));
    for (String file : new String[] {"llama-server.exe", "ggml-cuda.dll", "cudart64_12.dll",
        "cublas64_12.dll", "cublasLt64_12.dll"}) Files.writeString(cuda.resolve(file), "fixture");
    var builder = ResolvedConfig.builder();
    builder.putDefault("justsearch.server.exe", baseline.toString());
    builder.putDefault("justsearch.gpu.layers", "99");
    var config = new ConfigStore(builder.build());
    ConfigStore previous = ConfigStore.globalOrNull();
    String priorExe = System.getProperty("justsearch.server.exe");
    String priorSource = System.getProperty("justsearch.server.exe.source");
    try {
      ConfigStore.setGlobal(config);
      assertEquals(cuda.resolve("llama-server.exe").toAbsolutePath(),
          HeadlessApp.maybeAutoSelectCuda12Variant(new UiSettings(), config));
      assertEquals(priorExe, System.getProperty("justsearch.server.exe"));
      assertEquals(priorSource, System.getProperty("justsearch.server.exe.source"));
    } finally {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void settingsExecutablePreventsAutomaticSelection() {
    var builder = ResolvedConfig.builder();
    builder.putSettings("justsearch.server.exe", directory.resolve("chosen.exe").toString());
    builder.putDefault("justsearch.gpu.layers", "99");
    assertNull(HeadlessApp.maybeAutoSelectCuda12Variant(new UiSettings(), new ConfigStore(builder.build())));
  }

  @Test
  void resolvedCpuPolicyWinsOverPositiveSettingsDuringDiscovery() throws Exception {
    Path baseline = directory.resolve("llama-server.exe");
    Files.writeString(baseline, "fixture");
    Path cuda = Files.createDirectories(directory.resolve("variants/cuda12"));
    for (String file : new String[] {"llama-server.exe", "ggml-cuda.dll", "cudart64_12.dll",
        "cublas64_12.dll", "cublasLt64_12.dll"}) Files.writeString(cuda.resolve(file), "fixture");
    var settings = new UiSettings();
    settings.setGpuLayers(20);
    var builder = ResolvedConfig.builder();
    builder.putDefault("justsearch.server.exe", baseline.toString());
    builder.put("justsearch.gpu.layers", 500, "jvm_arg", "justsearch.gpu.layers", "0");
    var config = new ConfigStore(builder.build());
    var previous = ConfigStore.globalOrNull();
    try {
      ConfigStore.setGlobal(config);
      assertNull(HeadlessApp.maybeAutoSelectCuda12Variant(settings, config));
    } finally {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }
}
