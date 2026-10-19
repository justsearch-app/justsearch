/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** GPU-off must also cover llama-server's projector and automatic operation offload. */
final class LlamaProjectorPolicyTest {
  private static final InferenceConfig VISION = new InferenceConfig(
      Path.of("llama-server.exe"), Path.of("model.gguf"), Path.of("mmproj.gguf"),
      8081, 8192, 99, true);

  private static ResolvedConfig resolved(boolean allowed) {
    var builder = ResolvedConfig.builder();
    builder.putDefault("policy.gpu_acceleration_enabled", Boolean.toString(allowed));
    return builder.build();
  }

  @Test
  void policyOffPlacesTheProjectorOnCpuDespiteTheDefaultOffloadPreference() {
    var rc = resolved(false);
    assertTrue(rc.ai().mmprojOffload(), "exercise the default projector preference");
    var argv = LlamaServerOps.buildLaunchCommand(
        VISION, rc, 0, ContextWindowPolicy.auto(false, null));
    assertTrue(argv.contains("--no-mmproj-offload"), "GPU-off must keep the projector on CPU: " + argv);
    assertTrue(argv.contains("--no-op-offload"), "zero layers alone permits CUDA operation offload: " + argv);
    assertTrue(java.util.Collections.indexOfSubList(argv, java.util.List.of("--device", "none")) >= 0,
        "GPU-off must exclude CUDA devices from the owned launch: " + argv);
  }

  @Test
  void policyOffHasADifferentDeclaredIdentityEvenWhenBothLaunchesHaveZeroLayers() {
    assertNotEquals(
        ManagedLlamaConfigIdentity.declaredHash(VISION, resolved(true), 0),
        ManagedLlamaConfigIdentity.declaredHash(VISION, resolved(false), 0),
        "the projector and operation placement change requires a different managed identity");
  }
}
