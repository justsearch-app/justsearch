/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spec E check E-S10a (S10): a GPU launch offloads to one device, the pinned one, rather than
 * llama.cpp's default layer split across every CUDA device. Base API only.
 */
final class LlamaServerDevicePinningTest {

  @Test
  void gpuLaunchOffloadsToTheOnePinnedDevice() {
    ResolvedConfigBuilder b = ResolvedConfig.builder();
    b.putDefault("justsearch.llm.slots", "2");
    b.putDefault("justsearch.llm.kv_type", "q8_0");
    b.putDefault("justsearch.llm.use_thinking", "false");
    InferenceConfig cfg = new InferenceConfig(
        Path.of("bin", "llama-server.exe"), Path.of("models", "chat.gguf"), null, 8082, 32768, 99,
        false);

    List<String> command =
        LlamaServerOps.buildLaunchCommand(cfg, b.build(), 99, ContextWindowPolicy.auto(true, null));

    assertTrue(
        Collections.indexOfSubList(command, List.of("--device", "CUDA0", "-sm", "none")) >= 0,
        "a GPU launch must name one device (--device CUDA0 -sm none); without it llama.cpp splits"
            + " the model's layers across every CUDA device, so on a multi-GPU machine chat and the"
            + " encoders do not share the chosen GPU: "
            + command);
  }
}
