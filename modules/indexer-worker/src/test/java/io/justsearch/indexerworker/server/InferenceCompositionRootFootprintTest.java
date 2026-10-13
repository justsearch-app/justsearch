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
  void retirementDoesNotCreditAnUnusedArenaCeiling(@TempDir Path temp) throws Exception {
    Path file = Files.writeString(temp.resolve("model_fp16.onnx"), "small retained weights");
    var cfg = TestResolvedConfigHelper.fromEntries(disabledRoles());
    var policy = ModelSessionPolicyResolver.resolve(EncoderRole.RERANKER, cfg, CUDA_HOST,
        VariantSelection.optimal(file, ModelPrecision.FP16, ExecutionProvider.CUDA));
    var gpu = mock(SessionHandle.class);
    when(gpu.isGpuAvailable()).thenReturn(true);
    var owner = new QueryRoleSet(querySurface(Optional.of(new RerankerAssembly(gpu, null, null)),
        Optional.empty(), new PolicySnapshot(null, Map.of(EncoderRole.RERANKER, policy)), List.of(gpu)));
    assertTrue(InferenceCompositionRoot.sourceQueryReleasableBytes(owner) <= Files.size(file),
        "an allocator ceiling is not a retained allocation and cannot authorize replacement");
  }

  @Test
  void plannedP12ReplacementFromCpuSourceFitsFiveGiBFree(@TempDir Path temp) throws Exception {
    var builder = io.justsearch.configuration.resolved.ResolvedConfig.builder();
    disabledRoles().forEach(builder::putSettings);
    builder.contributeAutoDetected(Map.of("justsearch.gpu.memory_plan", "p12",
        "justsearch.embed.gpu_mem_mb", "2466", "justsearch.splade.gpu_mem_mb", "2342",
        "justsearch.ner.gpu_mem_mb", "2125", "justsearch.rerank.gpu_mem_mb", "2468"));
    String[] roles = {"embed", "splade", "ner", "rerank"};
    long[] weights = {599, 475, 258, 601};
    for (int i = 0; i < roles.length; i++) {
      Path folder = createModel(temp.resolve(roles[i]), "model.onnx", "model_fp16.onnx", "tokenizer.json", "vocab.txt");
      try (var file = new java.io.RandomAccessFile(folder.resolve("model_fp16.onnx").toFile(), "rw")) {
        file.setLength(weights[i] * MIB);
      }
      String role = roles[i];
      builder.putSettings(role.equals("embed") ? "justsearch.ai.embed.enabled" : "justsearch." + role + ".enabled", "true");
      builder.putSettings(role.equals("embed") ? "justsearch.embed.onnx.model_path" : "justsearch." + role + ".model_path", folder.toString());
      builder.putSettings("justsearch." + role + ".gpu_enabled", "true");
    }
    long footprint = InferenceCompositionRoot.estimateCandidateFootprintBytes(builder.build(), CUDA_HOST, null, null);
    assertTrue(footprint > 1_933L * MIB, "precondition: all retained weights are counted");
    assertEquals(ComposeEvidence.Mode.BESIDE,
        new DeviceMemoryLine(12_282L * MIB, 5_000L * MIB).decision(footprint, 0L).mode(),
        "one concurrent activation allowance plus retained weights fits the free device line");
  }

  @Test
  void generationBoundFp32CannotUseASiblingsMeasuredGpuPlan(@TempDir Path temp) throws Exception {
    Path folder = createModel(temp.resolve("embedding"), "model.onnx", "model_fp16.onnx", "tokenizer.json");
    Files.write(folder.resolve("model.onnx"), new byte[4096]);
    var exact = GenerationModelSelection.captureIdentity(folder.resolve("model.onnx"));
    var generation = GenerationModelSelection.accepted(Map.of("embedding",
        new ModelArtifact(exact.path().toString(), exact.sha256())), "splade", 768);
    var entries = disabledRoles();
    entries.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), folder.toString());
    entries.put(EnvRegistry.EMBED_GPU_ENABLED.configKey(), "true");
    var builder = io.justsearch.configuration.resolved.ResolvedConfig.builder();
    entries.forEach(builder::putDefault);
    builder.contributeAutoDetected(Map.of("justsearch.gpu.memory_plan", "p12"));
    var cfg = builder.build();
    assertEquals("p12", cfg.ai().gpuMemoryPlan(), "precondition: an applied memory plan");
    var bound = generation.variant("embedding", true).orElseThrow();
    assertEquals(exact.path(), bound.modelFile());
    var policy = ModelSessionPolicyResolver.resolve(EncoderRole.EMBEDDING, cfg, CUDA_HOST, bound);
    Double budgeted = InferenceCompositionRoot.gpuRoleWeightsMiB(cfg).get(EncoderRole.EMBEDDING);
    assertTrue(policy.gpu().arenaCapBytes() == 0L || budgeted != null && budgeted >= 4096.0 / MIB,
        "the bound FP32 artifact must be budgeted or receive explicit CPU placement");
  }

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

  /**
   * Spec E revision 4, check E-P7c (preservation): the device-memory plan's caps arrive at the
   * auto-detected ordinal (150), so explicit caps equal to today's constants outrank them and the
   * candidate estimate is today's.
   */
  @Test
  void explicitCapsEqualToTodaysGiveTodaysEstimateBesideAPlan(@TempDir Path temp)
      throws IOException {
    Path embedding =
        createModel(temp.resolve("embedding"), "model.onnx", "model_fp16.onnx", "tokenizer.json");
    Path splade =
        createModel(
            temp.resolve("splade"), "model.onnx", "model_fp16.onnx", "tokenizer.json", "vocab.txt");
    Path ner = createModel(temp.resolve("ner"), "model.onnx", "model_fp16.onnx", "tokenizer.json");
    Path reranker =
        createModel(temp.resolve("reranker"), "model.onnx", "model_fp16.onnx", "tokenizer.json");
    var builder = io.justsearch.configuration.resolved.ResolvedConfig.builder();
    Map<String, String> entries = disabledRoles();
    entries.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), embedding.toString());
    entries.put(EnvRegistry.EMBED_GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.SPLADE_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.SPLADE_MODEL_PATH.configKey(), splade.toString());
    entries.put(EnvRegistry.SPLADE_GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.NER_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.NER_MODEL_PATH.configKey(), ner.toString());
    entries.put(EnvRegistry.NER_GPU_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.RERANK_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.RERANK_MODEL_PATH.configKey(), reranker.toString());
    entries.put(EnvRegistry.RERANK_GPU_ENABLED.configKey(), "true");
    entries.forEach(builder::putDefault);
    // The P12 plan's contribution (caps w + A_run, rounded up) at the auto-detected ordinal.
    builder.contributeAutoDetected(Map.of(
        "justsearch.gpu.memory_plan", "p12",
        EnvRegistry.EMBED_GPU_MEM_MB.configKey(), "2466",
        EnvRegistry.SPLADE_GPU_MEM_MB.configKey(), "2342",
        EnvRegistry.NER_GPU_MEM_MB.configKey(), "2125",
        EnvRegistry.RERANK_GPU_MEM_MB.configKey(), "2468"));
    // The user's explicit caps, equal to today's constants (settings.json, ordinal 300).
    builder.putSettings(EnvRegistry.EMBED_GPU_MEM_MB.configKey(), "6144");
    builder.putSettings(EnvRegistry.SPLADE_GPU_MEM_MB.configKey(), "4096");
    builder.putSettings(EnvRegistry.NER_GPU_MEM_MB.configKey(), "2048");
    builder.putSettings(EnvRegistry.RERANK_GPU_MEM_MB.configKey(), "2048");

    long footprint =
        InferenceCompositionRoot.estimateCandidateFootprintBytes(
            builder.build(), CUDA_HOST, null, null);

    assertEquals(withHeadroom((6_144L + 400L) * MIB
        + 3 * Files.size(embedding.resolve("model_fp16.onnx"))), footprint);
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
  @DisplayName("source estimate requires retained allocation evidence rather than an arena ceiling")
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
    assertEquals(0L, releasable);
    assertEquals(ComposeEvidence.Mode.REFUSED,
        new DeviceMemoryLine(12L * 1024L * MIB, MIB)
            .decision(withHeadroom(3L * MIB), releasable).mode(),
        "an unused source ceiling cannot admit the candidate under a 1 MiB line");
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
    for (EncoderRole role : List.of(EncoderRole.EMBEDDING, EncoderRole.NER,
        EncoderRole.BGE_M3, EncoderRole.SPLADE)) {
      var policy = ModelSessionPolicyResolver.resolve(role, cfg, CUDA_HOST,
          VariantSelection.optimal(temp.resolve(role.name() + ".onnx"),
              ModelPrecision.FP16, ExecutionProvider.CUDA));
      policies.put(role, policy);
      var sessions = mock(SessionHandle.class);
      when(sessions.isGpuAvailable()).thenReturn(true);
      handles.add(sessions);
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

    assertEquals(0L,
        InferenceCompositionRoot.sourceGenerationReleasableBytes(owner, null));
    var rerankerPolicy = ModelSessionPolicyResolver.resolve(EncoderRole.RERANKER, cfg, CUDA_HOST,
        VariantSelection.optimal(temp.resolve("reranker.onnx"),
            ModelPrecision.FP16, ExecutionProvider.CUDA));
    var queryGpu = mock(SessionHandle.class);
    when(queryGpu.isGpuAvailable()).thenReturn(true);
    var query = new QueryRoleSet(querySurface(
        Optional.of(new RerankerAssembly(queryGpu, null, null)), Optional.empty(),
        new PolicySnapshot(null, Map.of(EncoderRole.RERANKER, rerankerPolicy)), List.of(queryGpu)));
    assertEquals(0L,
        InferenceCompositionRoot.sourceGenerationReleasableBytes(owner, query));
    when(handles.get(0).isGpuAvailable()).thenReturn(false);
    assertEquals(0L, InferenceCompositionRoot.sourceGenerationReleasableBytes(owner, null));
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
