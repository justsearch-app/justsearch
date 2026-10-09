/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P4: zero layers alone must not leave compute, KV or the vision projector on CUDA. */
final class LlamaGpuOffPlanTest {
  @Test
  void gpuOffDisablesDevicesAndProjectorEvenWhenOffloadDefaultsToTrue() {
    var rc = ResolvedConfig.builder().build();
    var cfg = new InferenceConfig(Path.of("llama-server.exe"), Path.of("chat.gguf"),
        Path.of("mmproj.gguf"), 8082, 8192, 99, false);
    var command = LlamaServerOps.buildLaunchCommand(cfg, rc, 0,
        ContextWindowPolicy.auto(false, null));
    assertAll(
        () -> assertTrue(command.contains("--no-mmproj-offload"), command.toString()),
        () -> assertTrue(java.util.Collections.indexOfSubList(command,
            List.of("--device", "none")) >= 0, command.toString()),
        () -> assertEquals("0", command.get(command.indexOf("-ngl") + 1)));
  }
}
