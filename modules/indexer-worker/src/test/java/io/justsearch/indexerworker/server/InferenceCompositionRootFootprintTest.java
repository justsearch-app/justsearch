/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.DeviceMemoryLine;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.ModelSessionPolicyResolver;
import io.justsearch.ort.PolicySnapshot;
import io.justsearch.ort.SessionHandle;
import io.justsearch.reranker.RerankerAssembly;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    assertTrue(selection.availableFingerprint("embedding").isEmpty());
  }

  @Test
  @DisplayName("query estimate uses exact selected policies and excludes retained index roles")
  void queryEstimateCountsOnlySelectedQuerySessions(@TempDir Path temp) throws IOException {
    Path reranker = createModel(temp.resolve("reranker"), "model.onnx", "tokenizer.json");
    Path citation = createModel(temp.resolve("citation"), "model.onnx", "tokenizer.json");
    Path embedding =
        createModel(temp.resolve("embedding"), "model.onnx", "model_fp16.onnx", "tokenizer.json");
    Map<String, String> entries = disabledRoles();
    entries.put(EnvRegistry.RERANK_GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.RERANK_GPU_MEM_MB.configKey(), "73");
    entries.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), embedding.toString());
    entries.put(EnvRegistry.EMBED_GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.EMBED_GPU_MEM_MB.configKey(), "900");
    var projection = EncoderConfigurationProjection.from(
        TestResolvedConfigHelper.fromEntries(entries));
    var selection = new QueryRoleSelection(
        selected(reranker, ModelPrecision.FP16, ExecutionProvider.CUDA),
        selected(citation, ModelPrecision.FP32, ExecutionProvider.CPU));

    long footprint = InferenceCompositionRoot.estimateQueryFootprintBytes(
        projection, selection, CUDA_HOST);

    assertEquals(withHeadroom(73L * MIB), footprint,
        "citation's CPU policy and retained embedding must contribute no device bytes");
  }

  @Test
  @DisplayName("query estimate is zero when the selected reranker falls back to CPU")
  void queryEstimateDoesNotCountCpuFallback(@TempDir Path temp) throws IOException {
    Path reranker = createModel(temp.resolve("reranker"), "model.onnx", "tokenizer.json");
    Map<String, String> entries = disabledRoles();
    entries.put(EnvRegistry.RERANK_GPU_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.RERANK_GPU_MEM_MB.configKey(), "73");
    var selection = new QueryRoleSelection(
        selected(reranker, ModelPrecision.FP32, ExecutionProvider.CPU),
        QueryRoleSelection.Role.disabled());

    assertEquals(0L, InferenceCompositionRoot.estimateQueryFootprintBytes(
        EncoderConfigurationProjection.from(TestResolvedConfigHelper.fromEntries(entries)),
        selection, CUDA_HOST));
  }

  @Test
  @DisplayName("source estimate counts only live GPU query assemblies with symmetric headroom")
  void sourceEstimateUsesRealizedGpuHandleAndPolicy(@TempDir Path temp) throws IOException {
    Path reranker = createModel(temp.resolve("reranker"), "model.onnx", "tokenizer.json")
        .resolve("model.onnx");
    Map<String, String> entries = disabledRoles();
    entries.put(EnvRegistry.RERANK_GPU_MEM_MB.configKey(), "3");
    var cfg = TestResolvedConfigHelper.fromEntries(entries);
    var policy = ModelSessionPolicyResolver.resolve(EncoderRole.RERANKER, cfg, CUDA_HOST,
        VariantSelection.optimal(reranker, ModelPrecision.FP16, ExecutionProvider.CUDA));
    SessionHandle gpu = mock(SessionHandle.class);
    when(gpu.isGpuAvailable()).thenReturn(true);
    SessionHandle cpu = mock(SessionHandle.class);
    when(cpu.isGpuAvailable()).thenReturn(false);
    var surface = querySurface(Optional.of(new RerankerAssembly(gpu, null, null)),
        Optional.of(new RerankerAssembly(cpu, null, null)),
        new PolicySnapshot(null, Map.of(EncoderRole.RERANKER, policy)), List.of(gpu, cpu));

    long releasable = InferenceCompositionRoot.sourceQueryReleasableBytes(
        new QueryRoleSet(surface));
    assertEquals(withHeadroom(3L * MIB), releasable);
    assertEquals(ComposeEvidence.Mode.IN_PLACE,
        new DeviceMemoryLine(12L * 1024L * MIB, MIB)
            .decision(withHeadroom(3L * MIB), releasable).mode(),
        "same-policy candidate remains admissible after source release under a 1 MiB line");
    assertEquals(ComposeEvidence.Mode.REFUSED,
        new DeviceMemoryLine(12L * 1024L * MIB, MIB)
            .decision(withHeadroom(6L * MIB), releasable).mode(),
        "a smaller realized source cannot authorize retiring A for a larger candidate");
  }

  @Test
  @DisplayName("GPU-live source without its resolved policy has unknown releasable bytes")
  void sourceEstimateIsUnknownWithoutGpuPolicy() {
    SessionHandle gpu = mock(SessionHandle.class);
    when(gpu.isGpuAvailable()).thenReturn(true);
    var surface = querySurface(Optional.of(new RerankerAssembly(gpu, null, null)),
        Optional.empty(), new PolicySnapshot(null, Map.of()), List.of(gpu));

    assertNull(InferenceCompositionRoot.sourceQueryReleasableBytes(new QueryRoleSet(surface)));
  }

  @Test
  void indexSourceCountsOwnedGpuAssembliesAndRetainedPolicies(@TempDir Path temp)
      throws IOException {
    var cfg = TestResolvedConfigHelper.fromEntries(disabledRoles());
    var policies = new java.util.EnumMap<EncoderRole, io.justsearch.ort.ModelSessionPolicy>(
        EncoderRole.class);
    var handles = new java.util.ArrayList<SessionHandle>();
    long expectedArena = 0L;
    for (EncoderRole role : List.of(EncoderRole.EMBEDDING, EncoderRole.NER,
        EncoderRole.BGE_M3, EncoderRole.SPLADE)) {
      var policy = ModelSessionPolicyResolver.resolve(role, cfg, CUDA_HOST,
          VariantSelection.optimal(temp.resolve(role.name() + ".onnx"),
              ModelPrecision.FP16, ExecutionProvider.CUDA));
      policies.put(role, policy);
      var sessions = mock(SessionHandle.class);
      when(sessions.isGpuAvailable()).thenReturn(true);
      handles.add(sessions);
      expectedArena += policy.gpu().arenaCapBytes();
    }
    var surface = new InferenceSurface(
        Optional.of(new io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly(
            handles.get(0), null, null, null)),
        Optional.of(new io.justsearch.indexerworker.ner.NerAssembly(
            handles.get(1), null, null, null, null)),
        Optional.empty(), Optional.empty(),
        Optional.of(new io.justsearch.indexerworker.splade.SpladeAssembly(
            handles.get(3), null, null, null, null)),
        Optional.of(new io.justsearch.indexerworker.bgem3.BgeM3Assembly(
            handles.get(2), null, null)),
        new PolicySnapshot(null, policies), handles);
    var absent = io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint
        .notConfigured();
    var owner = new EncoderSet(surface,
        new EncoderSet.ModelIdentity(absent, absent, absent, false, 768));

    assertEquals(withHeadroom(expectedArena),
        InferenceCompositionRoot.sourceGenerationReleasableBytes(owner, null));
    var rerankerPolicy = ModelSessionPolicyResolver.resolve(EncoderRole.RERANKER, cfg, CUDA_HOST,
        VariantSelection.optimal(temp.resolve("reranker.onnx"),
            ModelPrecision.FP16, ExecutionProvider.CUDA));
    var queryGpu = mock(SessionHandle.class);
    when(queryGpu.isGpuAvailable()).thenReturn(true);
    var query = new QueryRoleSet(querySurface(
        Optional.of(new RerankerAssembly(queryGpu, null, null)), Optional.empty(),
        new PolicySnapshot(null, Map.of(EncoderRole.RERANKER, rerankerPolicy)), List.of(queryGpu)));
    assertEquals(withHeadroom(expectedArena + rerankerPolicy.gpu().arenaCapBytes()),
        InferenceCompositionRoot.sourceGenerationReleasableBytes(owner, query));
    when(handles.get(0).isGpuAvailable()).thenReturn(false);
    assertEquals(withHeadroom(expectedArena - policies.get(EncoderRole.EMBEDDING)
        .gpu().arenaCapBytes()), InferenceCompositionRoot.sourceGenerationReleasableBytes(owner, null));
    policies.remove(EncoderRole.NER);
    var incomplete = new InferenceSurface(surface.embedding(), surface.ner(), surface.reranker(),
        surface.citation(), surface.splade(), surface.bgeM3(),
        new PolicySnapshot(null, policies), handles);
    assertNull(InferenceCompositionRoot.sourceGenerationReleasableBytes(new EncoderSet(incomplete,
        owner.modelIdentity()), null));
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

  private static QueryRoleSelection.Role selected(Path directory, ModelPrecision precision,
      ExecutionProvider provider) throws IOException {
    Path model = directory.resolve("model.onnx").toAbsolutePath().normalize();
    Path tokenizer = directory.resolve("tokenizer.json").toAbsolutePath().normalize();
    return QueryRoleSelection.Role.selected("test",
        GenerationModelSelection.captureIdentity(model),
        GenerationModelSelection.captureIdentity(tokenizer), precision, provider);
  }

  private static InferenceSurface querySurface(Optional<RerankerAssembly> reranker,
      Optional<RerankerAssembly> citation, PolicySnapshot policies, List<SessionHandle> handles) {
    return new InferenceSurface(Optional.empty(), Optional.empty(), reranker, citation,
        Optional.empty(), Optional.empty(), policies, handles);
  }

  private static long withHeadroom(long arenaCapBytes) {
    return arenaCapBytes
        + arenaCapBytes / 10L
        + (arenaCapBytes % 10L == 0L ? 0L : 1L);
  }
}
