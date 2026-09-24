/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("InferenceCompositionRoot candidate footprint (D1-14)")
class InferenceCompositionRootFootprintTest {

  private static final long MIB = 1024L * 1024L;
  private static final HardwareProfile CUDA_HOST =
      new HardwareProfile(true, true, 12L * 1024L * MIB);

  @Test
  @DisplayName("sums only resolved CUDA role policies and adds exact ten-percent headroom")
  void sumsCudaPoliciesWithoutOpeningSessions(@TempDir Path temp) throws IOException {
    Path embedding =
        createModel(temp.resolve("embedding"), "model.onnx", "model_fp16.onnx", "tokenizer.json");
    Path ner = createModel(temp.resolve("ner"), "model.onnx", "tokenizer.json");
    Path citation = createModel(temp.resolve("citation"), "model.onnx", "tokenizer.json");
    Map<String, String> entries = disabledRoles();
    entries.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), embedding.toString());
    entries.put(EnvRegistry.EMBED_GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.EMBED_GPU_MEM_MB.configKey(), "101");
    entries.put(EnvRegistry.NER_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.NER_MODEL_PATH.configKey(), ner.toString());
    entries.put(EnvRegistry.NER_GPU_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.NER_GPU_MEM_MB.configKey(), "700");
    entries.put(EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.CITATION_SCORER_MODEL_PATH.configKey(), citation.toString());

    long footprint =
        InferenceCompositionRoot.estimateCandidateFootprintBytes(
            TestResolvedConfigHelper.fromEntries(entries), CUDA_HOST, null, null);

    assertEquals(withHeadroom(101L * MIB), footprint);
  }

  @Test
  @DisplayName("BGE-M3 reserves the larger mutually-exclusive SPLADE fallback policy")
  void selectedBgeUsesLargerFallbackArena(@TempDir Path temp) throws IOException {
    Path bge =
        createModel(
            temp.resolve("bge"),
            "model.onnx",
            "model_fp16.onnx",
            "model_fp16_with_sparse.onnx",
            "tokenizer.json");
    Path splade =
        createModel(
            temp.resolve("splade"),
            "model.onnx",
            "model_fp16.onnx",
            "tokenizer.json",
            "vocab.txt");
    Map<String, String> entries = disabledRoles();
    entries.put(EnvRegistry.SPARSE_MODEL.configKey(), "bge-m3");
    entries.put(EnvRegistry.BGE_M3_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.BGE_M3_MODEL_PATH.configKey(), bge.toString());
    entries.put(EnvRegistry.BGE_M3_GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.BGE_M3_GPU_MEM_MB.configKey(), "300");
    entries.put(EnvRegistry.SPLADE_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.SPLADE_MODEL_PATH.configKey(), splade.toString());
    entries.put(EnvRegistry.SPLADE_GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.SPLADE_GPU_MEM_MB.configKey(), "500");

    long footprint =
        InferenceCompositionRoot.estimateCandidateFootprintBytes(
            TestResolvedConfigHelper.fromEntries(entries), CUDA_HOST, null, null);

    assertEquals(withHeadroom(500L * MIB), footprint);
  }

  @Test
  @DisplayName("ordinary embedding plus SPLADE roles are additive")
  void nonBgeSparseRoleAddsToEmbedding(@TempDir Path temp) throws IOException {
    Path embedding =
        createModel(temp.resolve("embedding"), "model.onnx", "model_fp16.onnx", "tokenizer.json");
    Path splade =
        createModel(
            temp.resolve("splade"),
            "model.onnx",
            "model_fp16.onnx",
            "tokenizer.json",
            "vocab.txt");
    Map<String, String> entries = disabledRoles();
    entries.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), embedding.toString());
    entries.put(EnvRegistry.EMBED_GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.EMBED_GPU_MEM_MB.configKey(), "101");
    entries.put(EnvRegistry.SPLADE_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.SPLADE_MODEL_PATH.configKey(), splade.toString());
    entries.put(EnvRegistry.SPLADE_GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.SPLADE_GPU_MEM_MB.configKey(), "40");

    long footprint =
        InferenceCompositionRoot.estimateCandidateFootprintBytes(
            TestResolvedConfigHelper.fromEntries(entries), CUDA_HOST, null, null);

    assertEquals(withHeadroom(141L * MIB), footprint);
  }

  @Test
  @DisplayName("exact candidate probing does not mark generation selection unavailable")
  void exactCandidateProbeDoesNotMutateGenerationSelection(@TempDir Path temp) {
    Path absent = temp.resolve("candidate/model.onnx").toAbsolutePath().normalize();
    GenerationModelSelection selection =
        GenerationModelSelection.accepted(
            Map.of("embedding", new ModelArtifact(absent.toString(), "a".repeat(64))),
            "splade",
            768);
    Map<String, String> entries = disabledRoles();
    entries.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.EMBED_GPU_ENABLED.configKey(), "true");
    var cfg = TestResolvedConfigHelper.fromEntries(entries);

    long footprint =
        InferenceCompositionRoot.estimateCandidateFootprintBytes(
            EncoderConfigurationProjection.from(cfg, selection),
            CUDA_HOST,
            null,
            null,
            selection);

    assertEquals(0L, footprint);
    assertFalse(selection.hasUnavailableModel());
  }

  private static Map<String, String> disabledRoles() {
    Map<String, String> entries = new HashMap<>();
    entries.put(EnvRegistry.GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.POLICY_GPU_ACCELERATION_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.SPLADE_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.NER_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.BGE_M3_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.RERANK_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false");
    return entries;
  }

  private static Path createModel(Path directory, String... fileNames) throws IOException {
    Files.createDirectories(directory);
    for (String fileName : fileNames) {
      Files.writeString(directory.resolve(fileName), "not-an-onnx-session");
    }
    return directory;
  }

  private static long withHeadroom(long arenaCapBytes) {
    return arenaCapBytes
        + arenaCapBytes / 10L
        + (arenaCapBytes % 10L == 0L ? 0L : 1L);
  }
}
