/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.ort.EncoderRole;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Best match first switched off keeps its files but no device memory: the device-memory plan of
 * the configuration saved while off has no reranker weights, so no reranker budget is reserved
 * while it is off (switching on stages its own plan; see RerankerSwitchOnCudaTest).
 */
final class RerankerOffDevicePlanTest {
  @Test
  void offReleasesTheRerankerBudget(@TempDir Path dir) throws Exception {
    Path models = Files.createDirectories(dir.resolve("custom-reranker"));
    Files.writeString(models.resolve("model_fp16.onnx"), "custom-model");
    Files.writeString(models.resolve("tokenizer.json"), "custom-tokenizer");
    assertTrue(InferenceCompositionRoot.gpuRoleWeightsMiB(saved(dir, models, "true"))
        .containsKey(EncoderRole.RERANKER), "the CUDA variant is planned while on");
    assertFalse(InferenceCompositionRoot.gpuRoleWeightsMiB(saved(dir, models, "false"))
        .containsKey(EncoderRole.RERANKER), "switched off, the reranker reserves no device memory");
  }

  private static ResolvedConfig saved(Path dir, Path models, String enabled) {
    return new ResolvedConfigBuilder().putDefault("justsearch.data.dir", dir.toString())
        .putSettings("justsearch.rerank.enabled", enabled)
        .putSettings("justsearch.rerank.model_path", models.toString()).build();
  }
}
