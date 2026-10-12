/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.engine.DevicePlanTestSupport;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Actual profile contribution through the Engine, then the serving launch's device policy. */
final class LlamaProfileDevicePlanTest {
  @AfterEach
  void clearPlanner() throws Exception {
    DevicePlanTestSupport.uninstall();
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
  }

  @ParameterizedTest
  @CsvSource({"16384,P16_PLUS,false", "12282,P12,true", "8192,P8,true"})
  void gpuProfilesKeepTheirPlacement(double memory, String profile, boolean cpuProjector)
      throws Exception {
    DevicePlanTestSupport.uninstall();
    ConfigStoreRebuilder.rememberAutoDetected(Map.of("justsearch.gpu.layers", "99"));
    UiSettings settings = new UiSettings();
    settings.setChatEnabled(true);
    ConfigStore store = new ConfigStore(ConfigStoreRebuilder.prepare(settings));
    DevicePlanTestSupport.install(store, DevicePlanTestSupport.device(memory),
        DevicePlanTestSupport.DEFAULT_WEIGHTS, Set.of(ChatModelProfile.STANDARD, ChatModelProfile.COMPACT));
    ConfigStoreRebuilder.rebuild(store, settings);
    var config = store.get();
    assertEquals(profile, DevicePlanTestSupport.statusProfileOf(config).orElseThrow());
    var cfg = new InferenceConfig(Path.of("llama-server.exe"), Path.of("chat.gguf"),
        Path.of("mmproj.gguf"), 8082, config.ai().contextSize(), config.ai().gpuLayers(), false);
    var command = LlamaServerOps.buildLaunchCommand(cfg, config, config.ai().gpuLayers(),
        ContextWindowPolicy.auto(true, null));
    assertEquals("99", command.get(command.indexOf("-ngl") + 1));
    assertEquals(Map.of("CUDA_VISIBLE_DEVICES", "GPU-pinned"), LlamaServerOps.pinnedDeviceEnvironment(Optional.of("GPU-pinned")));
    assertEquals(List.of("--device", "CUDA0", "-sm", "none"), command.subList(command.indexOf("--device"), command.indexOf("--device") + 4));
    assertEquals(cpuProjector, command.contains("--no-mmproj-offload"));
  }
}
