/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Spec E step E6: the launch environment and flag placement that pin llama-server's device. */
final class LlamaServerPinnedDeviceEnvironmentTest {

  private static final String UUID = "GPU-8b20006f-755d-8a37-9762-7b385907e813";

  private static List<String> command(int gpuLayers) {
    ResolvedConfigBuilder b = ResolvedConfig.builder();
    b.putDefault("justsearch.llm.slots", "2");
    b.putDefault("justsearch.llm.kv_type", "q8_0");
    b.putDefault("justsearch.llm.use_thinking", "false");
    InferenceConfig cfg = new InferenceConfig(
        Path.of("bin", "llama-server.exe"), Path.of("models", "chat.gguf"), null, 8082, 32768,
        gpuLayers, false);
    return LlamaServerOps.buildLaunchCommand(
        cfg, b.build(), gpuLayers, ContextWindowPolicy.auto(gpuLayers > 0, null));
  }

  @Test
  void pinnedUuidBecomesTheOnlyVisibleCudaDevice() {
    assertEquals(
        Map.of("CUDA_VISIBLE_DEVICES", UUID),
        LlamaServerOps.pinnedDeviceEnvironment(Optional.of(UUID)));
  }

  @Test
  void nothingPinnedLeavesTheInheritedEnvironment() {
    assertEquals(Map.of(), LlamaServerOps.pinnedDeviceEnvironment(Optional.empty()));
    assertEquals(Map.of(), LlamaServerOps.pinnedDeviceEnvironment(Optional.of(" ")));
  }

  @Test
  void pinningFlagsFollowTheWindowFlagsOnAGpuLaunch() {
    List<String> command = command(99);
    int ngl = command.indexOf("-ngl");
    assertEquals(
        LlamaServerOps.PINNED_DEVICE_FLAGS,
        command.subList(ngl + 2, ngl + 2 + LlamaServerOps.PINNED_DEVICE_FLAGS.size()));
  }

  @Test
  void cpuLaunchNamesNoDevice() {
    List<String> command = command(0);
    assertFalse(command.contains("--device"), command.toString());
    assertFalse(command.contains("-sm"), command.toString());
  }
}
