package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArbiter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link InferenceCompositionRoot#compose} (tempdoc 397 §14.28 U6). Focuses
 * on the graceful-degradation invariant: when models are absent on disk (the typical test
 * environment), {@code compose} returns a surface with every role as {@link java.util.Optional#empty()}
 * — no throws, no NPE, no partial-state mutation.
 *
 * <p>Exhaustive coverage of {@code composeXAssembly} per-encoder branches requires real ONNX
 * files on disk (tokenizer + manifest loads are the critical I/O); that level of coverage
 * lives in the per-encoder integration tests. U6 pins the <em>compose() orchestration shape</em>
 * — which encoders are attempted, which are skipped based on sparseModel selection, how
 * failures propagate, and that the surface invariants hold even under total failure.
 */
@DisplayName("InferenceCompositionRoot.compose (§14.28 U6)")
class InferenceCompositionRootComposeTest {

  @Test
  void indexPlanFreezesContractAInsteadOfConfiguredB(@TempDir Path dir) throws Exception {
    Path contractRoot = dir.resolve("contract");
    Path selectedDir = contractRoot.resolve("onnx/embedding");
    Path configuredDir = dir.resolve("configured-B");
    Files.createDirectories(selectedDir);
    Files.createDirectories(configuredDir);
    Path selected = Files.writeString(selectedDir.resolve("model.onnx"), "selected-A");
    Files.writeString(selectedDir.resolve("tokenizer.json"), "tokenizer-A");
    Files.writeString(configuredDir.resolve("model.onnx"), "configured-B");
    Files.writeString(configuredDir.resolve("tokenizer.json"), "tokenizer-B");
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "true",
        EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), configuredDir.toString(),
        EnvRegistry.SPLADE_ENABLED.configKey(), "false",
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "false",
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));
    var installed = new InstallContract.InstalledModel("embedding", "model.onnx",
        ModelPrecision.FP32, ExecutionProvider.CPU, "onnx/embedding", sha256(selected),
        List.of("model.onnx", "tokenizer.json"), false, null);
    var contract = new InstallContract(2, System.currentTimeMillis(), HardwareProfile.cpuOnly(),
        DownloadProfile.CPU, Map.of("embedding", installed), contractRoot);
    var captured = new AtomicReference<InferenceCompositionRoot.CapturedCompositionPlan>();

    assertThrows(IllegalStateException.class, () -> InferenceCompositionRoot.compose(
        EncoderConfigurationProjection.from(cfg), HardwareProfile.cpuOnly(), contract,
        contractRoot, NO_GPU, io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP,
        null, null, ignored -> {}, plan -> {
          captured.set(plan);
          throw new IllegalStateException("stop-before-native");
        }));

    IndexCompositionPlan.RolePlan embedding =
        captured.get().indexPlan().role(EncoderRole.EMBEDDING);
    assertEquals(selected.toAbsolutePath().normalize(), embedding.variant().modelFile());
    assertEquals(embedding.variant(), embedding.policy().variant());
    assertEquals(selectedDir.toAbsolutePath().normalize(), embedding.metadataDirectory());
  }

  @Test
  void indexCaptureFailureStillPublishesKnownQueryWitness(@TempDir Path dir) throws Exception {
    Path embeddingDir = dir.resolve("embedding");
    Path rerankerDir = dir.resolve("reranker");
    Files.createDirectories(embeddingDir);
    Files.createDirectories(rerankerDir);
    Files.writeString(embeddingDir.resolve("model.onnx"), "embedding-model");
    Files.writeString(embeddingDir.resolve("tokenizer.json"), "embedding-tokenizer");
    Files.writeString(embeddingDir.resolve("model_manifest.json"), "{not-json");
    Files.writeString(rerankerDir.resolve("model.onnx"), "reranker-model");
    Files.writeString(rerankerDir.resolve("tokenizer.json"), "reranker-tokenizer");
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "true",
        EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), embeddingDir.toString(),
        EnvRegistry.SPLADE_ENABLED.configKey(), "false",
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "true",
        EnvRegistry.RERANK_MODEL_PATH.configKey(), rerankerDir.toString(),
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));
    var query = new AtomicReference<InferenceSurface.ComponentObservation>();
    var fullPlan = new AtomicReference<InferenceCompositionRoot.CapturedCompositionPlan>();

    InferenceSurface surface = InferenceCompositionRoot.compose(
        EncoderConfigurationProjection.from(cfg), HardwareProfile.cpuOnly(), null, null, NO_GPU,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, null, null, query::set,
        fullPlan::set);

    assertEquals(QueryRoleSelection.State.SELECTED,
        query.get().querySelection().orElseThrow().reranker().state());
    assertEquals(Set.of(EncoderRole.RERANKER), query.get().requestedRoles());
    assertTrue(query.get().missingRoles().contains(EncoderRole.RERANKER));
    assertTrue(surface.embedding().isEmpty());
    assertTrue(fullPlan.get().indexPlan().role(EncoderRole.EMBEDDING).captureFailed());
    assertThrows(IllegalStateException.class,
        () -> InferenceCompositionRoot.validateCaptured(fullPlan.get().indexPlan()));
  }

  @Test
  void capturedOptionalSidecarAppearanceRefusesBeforeNative(@TempDir Path modelDir)
      throws Exception {
    Path model = Files.writeString(modelDir.resolve("model.onnx"), "model-A");
    Path tokenizer = Files.writeString(modelDir.resolve("tokenizer.json"), "tokenizer-A");
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "true",
        EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), modelDir.toString(),
        EnvRegistry.SPLADE_ENABLED.configKey(), "false",
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "false",
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));
    var captured = new AtomicReference<InferenceCompositionRoot.CapturedCompositionPlan>();
    assertThrows(IllegalStateException.class, () -> InferenceCompositionRoot.compose(
        EncoderConfigurationProjection.from(cfg), HardwareProfile.cpuOnly(), null, null, NO_GPU,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, null, null, ignored -> {},
        plan -> {
          captured.set(plan);
          throw new IllegalStateException("stop-before-native");
        }));
    assertEquals(model.toAbsolutePath().normalize(), captured.get().indexPlan()
        .role(EncoderRole.EMBEDDING).variant().modelFile());

    Files.writeString(tokenizer, "tokenizer-B");
    assertThrows(IllegalStateException.class,
        () -> InferenceCompositionRoot.validateCaptured(captured.get().indexPlan()));
    Files.writeString(tokenizer, "tokenizer-A");
    InferenceCompositionRoot.validateCaptured(captured.get().indexPlan());

    Files.writeString(modelDir.resolve("pooling_config.json"), "{\"pooling_mode\":\"cls\"}");
    assertThrows(IllegalStateException.class,
        () -> InferenceCompositionRoot.validateCaptured(captured.get().indexPlan()));
  }

  @Test
  void bgePlanPreResolvesExactSpladeFallback(@TempDir Path dir) throws Exception {
    Path bgeDir = dir.resolve("bge");
    Path spladeDir = dir.resolve("splade");
    Files.createDirectories(bgeDir);
    Files.createDirectories(spladeDir);
    Path bge = Files.writeString(bgeDir.resolve("model_fp16_with_sparse.onnx"), "bge-A");
    Files.writeString(bgeDir.resolve("tokenizer.json"), "bge-tokenizer");
    Files.writeString(bgeDir.resolve("model_manifest.json"),
        "{\"cpu\":\"model_fp16_with_sparse.onnx\",\"gpu\":\"model_fp16_with_sparse.onnx\"}");
    Path splade = Files.writeString(spladeDir.resolve("model.onnx"), "splade-A");
    Files.writeString(spladeDir.resolve("tokenizer.json"), "splade-tokenizer");
    Files.writeString(spladeDir.resolve("vocab.txt"), "[UNK]\nhello\n");
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.SPARSE_MODEL.configKey(), "bge-m3",
        EnvRegistry.BGE_M3_ENABLED.configKey(), "true",
        EnvRegistry.BGE_M3_MODEL_PATH.configKey(), bgeDir.toString(),
        EnvRegistry.SPLADE_ENABLED.configKey(), "true",
        EnvRegistry.SPLADE_MODEL_PATH.configKey(), spladeDir.toString(),
        EnvRegistry.SPLADE_QUERY_MODE.configKey(), "idf",
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "false",
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "false",
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));
    var captured = new AtomicReference<InferenceCompositionRoot.CapturedCompositionPlan>();
    assertThrows(IllegalStateException.class, () -> InferenceCompositionRoot.compose(
        EncoderConfigurationProjection.from(cfg), HardwareProfile.cpuOnly(), null, null, NO_GPU,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, null, null, ignored -> {},
        plan -> {
          captured.set(plan);
          throw new IllegalStateException("stop-before-native");
        }));

    IndexCompositionPlan plan = captured.get().indexPlan();
    assertTrue(plan.bgeM3Selected());
    assertEquals(bge.toAbsolutePath().normalize(),
        plan.role(EncoderRole.BGE_M3).variant().modelFile());
    assertEquals(splade.toAbsolutePath().normalize(),
        plan.role(EncoderRole.SPLADE).variant().modelFile());

    Files.writeString(bgeDir.resolve("config.json"), "{\"hidden_size\":1024}");
    Files.writeString(spladeDir.resolve("sentence_bert_config.json"),
        "{\"max_seq_length\":512}");
    InferenceCompositionRoot.validateCaptured(plan);
    Files.writeString(spladeDir.resolve("idf.json"), "{\"hello\":1.0}");
    assertThrows(IllegalStateException.class,
        () -> InferenceCompositionRoot.validateCaptured(plan));
  }

  @Test
  void recoveredObservationCombinesIndexAWithCurrentQueryB() {
    Map<String, String> base = Map.of(
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "false",
        EnvRegistry.SPLADE_ENABLED.configKey(), "false",
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "false",
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false");
    var cfgA = TestResolvedConfigHelper.fromEntries(base);
    var cfgBEntries = new java.util.HashMap<>(base);
    cfgBEntries.put(EnvRegistry.RERANK_MAX_SEQ_LEN.configKey(), "384");
    var cfgB = TestResolvedConfigHelper.fromEntries(cfgBEntries);
    var capturedA = new AtomicReference<InferenceCompositionRoot.CapturedCompositionPlan>();
    var capturedB = new AtomicReference<InferenceCompositionRoot.CapturedCompositionPlan>();
    InferenceCompositionRoot.compose(EncoderConfigurationProjection.from(cfgA),
        HardwareProfile.cpuOnly(), null, null, NO_GPU,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, null, null, ignored -> {},
        capturedA::set);
    InferenceCompositionRoot.compose(EncoderConfigurationProjection.from(cfgB),
        HardwareProfile.cpuOnly(), null, null, NO_GPU,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, null, null, ignored -> {},
        capturedB::set);

    InferenceSurface recovered = InferenceCompositionRoot.composeCaptured(
        capturedA.get().indexPlan(), capturedB.get().queryProjection(),
        capturedB.get().queryObservation(), NO_GPU,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP);

    assertNotEquals(capturedA.get().queryProjection().queryDigest(),
        capturedB.get().queryProjection().queryDigest());
    EncoderConfigurationProjection merged = capturedA.get().indexPlan().projection()
        .withQueryFrom(capturedB.get().queryProjection());
    assertEquals(merged.digest(),
        recovered.componentObservation().configurationDigest().orElseThrow());
    InferenceSurface.Partition partition = recovered.partitionQueryRoles(merged);
    assertEquals(capturedA.get().indexPlan().projection().indexDigest(),
        partition.index().componentObservation().configurationDigest().orElseThrow());
    assertEquals(capturedB.get().queryProjection().queryDigest(),
        partition.query().componentObservation().configurationDigest().orElseThrow());
  }

  private ConfigStore originalStore;

  @BeforeEach
  void captureStore() {
    // ConfigStore.global() may already be set by an earlier test; capture + restore.
    originalStore = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.withDefaults()));
  }

  @AfterEach
  void restoreStore() {
    TestResolvedConfigHelper.restoreGlobal(originalStore);
  }

  private static final GpuArbiter NO_GPU = () -> false;

  @Test
  void bootCompositionCapturesContractSelectionInsteadOfConfiguredDirectory(@TempDir Path root)
      throws IOException {
    Path contractRoot = root.resolve("contract-models");
    Path contractDir = contractRoot.resolve("onnx/reranker");
    Path configuredDir = root.resolve("configured-reranker");
    Files.createDirectories(contractDir);
    Files.createDirectories(configuredDir);
    Path model = Files.writeString(contractDir.resolve("model.onnx"), "selected-A");
    Path tokenizer = Files.writeString(contractDir.resolve("tokenizer.json"), "tokenizer-A");
    Files.writeString(configuredDir.resolve("model.onnx"), "unselected-B");
    Files.writeString(configuredDir.resolve("tokenizer.json"), "tokenizer-B");
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "false",
        EnvRegistry.SPLADE_ENABLED.configKey(), "false",
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "true",
        EnvRegistry.RERANK_MODEL_PATH.configKey(), configuredDir.toString(),
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));
    var installed = new InstallContract.InstalledModel("reranker", "model.onnx",
        ModelPrecision.FP32, ExecutionProvider.CPU, "onnx/reranker", sha256(model),
        List.of("model.onnx", "tokenizer.json"), false, null);
    var contract = new InstallContract(2, System.currentTimeMillis(), HardwareProfile.cpuOnly(),
        DownloadProfile.CPU, Map.of("reranker", installed), contractRoot);

    var early = new AtomicReference<InferenceSurface.ComponentObservation>();
    var stopped = assertThrows(IllegalStateException.class, () ->
        InferenceCompositionRoot.compose(EncoderConfigurationProjection.from(cfg),
            HardwareProfile.cpuOnly(), contract, contractRoot, NO_GPU,
            io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, null, null,
            observation -> {
              early.set(observation);
              throw new IllegalStateException("stop-before-native-assembly");
            }));
    assertEquals("stop-before-native-assembly", stopped.getMessage());
    assertEquals(QueryRoleSelection.State.SELECTED,
        early.get().querySelection().orElseThrow().reranker().state());
    assertEquals(QueryRoleSelection.State.DISABLED,
        early.get().querySelection().orElseThrow().citation().state());
    assertEquals(Set.of(EncoderRole.RERANKER), early.get().requestedRoles());
    assertEquals(Set.of(EncoderRole.RERANKER), early.get().missingRoles());

    InferenceSurface first = InferenceCompositionRoot.compose(
        EncoderConfigurationProjection.from(cfg), HardwareProfile.cpuOnly(), contract,
        contractRoot, NO_GPU, io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP);
    QueryRoleSelection captured = first.componentObservation().querySelection().orElseThrow();

    assertEquals(QueryRoleSelection.State.SELECTED, captured.reranker().state());
    assertEquals(model.toAbsolutePath().normalize(), captured.reranker().model().path());
    assertEquals(sha256(model), captured.reranker().model().sha256());
    assertEquals(Files.size(model), captured.reranker().model().sizeBytes());
    assertEquals(tokenizer.toAbsolutePath().normalize(), captured.reranker().tokenizer().path());
    assertEquals(ModelPrecision.FP32, captured.reranker().precision());
    assertEquals(ExecutionProvider.CPU, captured.reranker().targetEp());
    assertTrue(first.reranker().isEmpty(), "invalid ONNX fixture must fail assembly");
    assertEquals(Set.of(EncoderRole.RERANKER),
        first.componentObservation().missingRoles());

    Files.writeString(model, "different-model-bytes-B");
    InferenceSurface recovered = InferenceCompositionRoot.composeQueryRoles(
        EncoderConfigurationProjection.from(cfg), captured, HardwareProfile.cpuOnly(), NO_GPU,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP);

    assertEquals(captured, recovered.componentObservation().querySelection().orElseThrow());
    assertEquals(Set.of(EncoderRole.RERANKER),
        recovered.componentObservation().missingRoles());
  }

  @Test
  void generationQueryRoleDoesNotOverrideDisabledConfiguration(@TempDir Path modelDir)
      throws IOException {
    Path model = Files.writeString(modelDir.resolve("model.onnx"), "generation-model");
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "false",
        EnvRegistry.SPLADE_ENABLED.configKey(), "false",
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "false",
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));
    var generation = GenerationModelSelection.accepted(Map.of(
        "reranker", new ModelArtifact(model.toString(), sha256(model))), "splade", 768);

    InferenceSurface surface = InferenceCompositionRoot.compose(
        EncoderConfigurationProjection.from(cfg), HardwareProfile.cpuOnly(), null,
        null, NO_GPU, io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, generation);

    QueryRoleSelection witness = surface.componentObservation().querySelection().orElseThrow();
    assertEquals(QueryRoleSelection.State.DISABLED, witness.reranker().state());
    assertFalse(surface.componentObservation().requestedRoles().contains(EncoderRole.RERANKER));
  }

  @Test
  void requestedUnresolvedQueryRoleDiffersFromIntentionalDisablement() {
    var requestedCfg = TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "false",
        EnvRegistry.SPLADE_ENABLED.configKey(), "false",
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "true",
        EnvRegistry.RERANK_MODEL_PATH.configKey(), "absent-reranker",
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));
    var disabledCfg = TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "false",
        EnvRegistry.SPLADE_ENABLED.configKey(), "false",
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "false",
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));

    var early = new AtomicReference<InferenceSurface.ComponentObservation>();
    InferenceSurface requested = InferenceCompositionRoot.compose(
        EncoderConfigurationProjection.from(requestedCfg), HardwareProfile.cpuOnly(), null, null,
        NO_GPU, io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, null, null,
        early::set);
    InferenceSurface disabled = InferenceCompositionRoot.compose(disabledCfg,
        HardwareProfile.cpuOnly(), null, null, NO_GPU);

    assertEquals(QueryRoleSelection.State.DISABLED,
        requested.componentObservation().querySelection().orElseThrow().reranker().state());
    assertEquals(Set.of(EncoderRole.RERANKER), requested.componentObservation().requestedRoles());
    assertEquals(Set.of(EncoderRole.RERANKER), requested.componentObservation().missingRoles());
    assertEquals(QueryRoleSelection.State.DISABLED,
        early.get().querySelection().orElseThrow().reranker().state());
    assertEquals(Set.of(EncoderRole.RERANKER), early.get().requestedRoles());
    assertEquals(Set.of(EncoderRole.RERANKER), early.get().missingRoles());
    assertEquals(QueryRoleSelection.State.DISABLED,
        disabled.componentObservation().querySelection().orElseThrow().reranker().state());
    assertTrue(disabled.componentObservation().requestedRoles().isEmpty());
    assertTrue(disabled.componentObservation().missingRoles().isEmpty());
  }

  @Test
  void changedWitnessedTokenizerDisablesOnlyThatQueryRole(@TempDir Path modelDir)
      throws IOException {
    Path model = Files.writeString(modelDir.resolve("model.onnx"), "model");
    Path tokenizer = Files.writeString(modelDir.resolve("tokenizer.json"), "changed");
    var selection = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.selected("model.onnx",
            new ModelFile(model, sha256(model), Files.size(model)),
            new ModelFile(tokenizer, "0".repeat(64), Files.size(tokenizer)),
            ModelPrecision.INT8, ExecutionProvider.CPU));
    var projection = EncoderConfigurationProjection.from(ConfigStore.global().get());
    var surface = InferenceCompositionRoot.composeQueryRoles(projection, selection,
        HardwareProfile.cpuOnly(), NO_GPU,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP);
    assertTrue(surface.handles().isEmpty());
    assertEquals(Set.of(EncoderRole.CITATION),
        surface.componentObservation().missingRoles());
    assertEquals(Set.of(EncoderRole.CITATION),
        surface.componentObservation().requestedRoles());
    assertEquals(projection.queryDigest(),
        surface.componentObservation().configurationDigest().orElseThrow());
    assertEquals(selection, surface.componentObservation().querySelection().orElseThrow());
  }

  @Test
  @DisplayName("component dependencies are stable config keys and immutable")
  void componentDependenciesAreStableAndImmutable() {
    Set<String> dependencies = InferenceCompositionRoot.componentDependencies();

    assertTrue(dependencies.contains(EnvRegistry.AI_EMBED_ENABLED.configKey()));
    assertTrue(dependencies.contains(EnvRegistry.GPU_ENABLED.configKey()));
    assertTrue(dependencies.contains(EnvRegistry.POLICY_GPU_ACCELERATION_ENABLED.configKey()));
    assertTrue(dependencies.contains(EnvRegistry.SPARSE_MODEL.configKey()));
    assertTrue(dependencies.contains(EnvRegistry.ORT_INTRA_OP_THREADS.configKey()));
    assertThrows(UnsupportedOperationException.class, () -> dependencies.add("invented.key"));
  }

  @Test
  @DisplayName("no models on disk → surface has all Optional.empty + empty policies + empty handles")
  void noModelsReturnsEmptySurface() {
    InferenceSurface surface =
        InferenceCompositionRoot.compose(
            ConfigStore.global().get(),
            HardwareProfile.cpuOnly(),
            /* contract= */ null,
            /* modelsDir= */ null,
            NO_GPU);

    assertNotNull(surface);
    assertTrue(surface.embedding().isEmpty());
    assertTrue(surface.ner().isEmpty());
    assertTrue(surface.reranker().isEmpty());
    assertTrue(surface.citation().isEmpty());
    assertTrue(surface.splade().isEmpty());
    assertTrue(surface.bgeM3().isEmpty());
    assertTrue(surface.handles().isEmpty());
    assertTrue(
        surface.policies().models().isEmpty(),
        "policies map excludes roles whose variant didn't resolve");
  }

  @Test
  @DisplayName("compose is idempotent under absent-models — snapshot.runtime is always resolved")
  void runtimePolicyAlwaysPresentRegardlessOfRoles() {
    // Even when every encoder role's variant fails to resolve, RuntimePolicy is process-wide
    // and always computable from (cfg, hardware). The snapshot always carries it.
    InferenceSurface surface =
        InferenceCompositionRoot.compose(
            ConfigStore.global().get(),
            HardwareProfile.cpuOnly(),
            null,
            null,
            NO_GPU);

    assertNotNull(surface.policies().runtime());
    assertNotNull(surface.policies().runtime().arena());
    assertNotNull(surface.policies().runtime().session());
    assertNotNull(surface.policies().runtime().cudaProvider());
    assertNotNull(surface.policies().runtime().profiling());
  }

  @Test
  @DisplayName("compose does not throw on hardware = gpuFull — graceful degradation when GPU absent")
  void gpuHardwareProfileWithNoModelsDoesNotThrow() {
    // Even when hardware claims GPU is available, missing model files should not cause any
    // throw inside compose. Per-encoder try/catch + Optional.empty handles the failure.
    assertDoesNotThrow(
        () ->
            InferenceCompositionRoot.compose(
                ConfigStore.global().get(),
                HardwareProfile.gpuFull(0),
                /* contract= */ null,
                /* modelsDir= */ null,
                NO_GPU));
  }

  @Test
  @DisplayName("surface.close() on empty surface is a no-op")
  void emptySurfaceCloseIsNoop() {
    InferenceSurface surface =
        InferenceCompositionRoot.compose(
            ConfigStore.global().get(),
            HardwareProfile.cpuOnly(),
            null,
            null,
            NO_GPU);
    assertDoesNotThrow(surface::close);
  }

  @Test
  @DisplayName("compose called twice produces independent surfaces")
  void composeIsNotStateful() {
    InferenceSurface s1 =
        InferenceCompositionRoot.compose(
            ConfigStore.global().get(),
            HardwareProfile.cpuOnly(),
            null,
            null,
            NO_GPU);
    InferenceSurface s2 =
        InferenceCompositionRoot.compose(
            ConfigStore.global().get(),
            HardwareProfile.cpuOnly(),
            null,
            null,
            NO_GPU);

    assertNotNull(s1);
    assertNotNull(s2);
    // No shared mutable state — closing s1 doesn't affect s2.
    s1.close();
    assertDoesNotThrow(s2::close);
  }

  @Test
  @DisplayName("explicitly disabled optional roles produce an unavailable lexical-only observation")
  void disabledRolesProduceNoRequestedRoles() {
    var cfg =
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                EnvRegistry.AI_EMBED_ENABLED.configKey(), "false",
                EnvRegistry.SPLADE_ENABLED.configKey(), "false",
                EnvRegistry.NER_ENABLED.configKey(), "false",
                EnvRegistry.RERANK_ENABLED.configKey(), "false",
                EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));

    InferenceSurface surface =
        InferenceCompositionRoot.compose(cfg, HardwareProfile.cpuOnly(), null, null, NO_GPU);

    assertTrue(surface.componentObservation().configurationDigest().isPresent());
    assertTrue(surface.componentObservation().requestedRoles().isEmpty());
    assertFalse(surface.componentObservation().compositionSatisfied());
  }

  @Test
  @DisplayName("enabled role is requested before missing-model readiness failure")
  void enabledMissingRoleIsRequestedAndMissing() {
    var cfg =
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                EnvRegistry.AI_EMBED_ENABLED.configKey(), "true",
                EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), "absent-model",
                EnvRegistry.SPLADE_ENABLED.configKey(), "false",
                EnvRegistry.NER_ENABLED.configKey(), "false",
                EnvRegistry.RERANK_ENABLED.configKey(), "false",
                EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));

    InferenceSurface surface =
        InferenceCompositionRoot.compose(cfg, HardwareProfile.cpuOnly(), null, null, NO_GPU);

    assertEquals(Set.of(EncoderRole.EMBEDDING), surface.componentObservation().requestedRoles());
    assertEquals(Set.of(EncoderRole.EMBEDDING), surface.componentObservation().missingRoles());
    assertFalse(surface.componentObservation().compositionSatisfied());
  }

  @Test
  void missingActiveModelCannotFallThroughToDesiredModel(@TempDir Path temp) throws IOException {
    Path x = temp.resolve("active-X/model.onnx").toAbsolutePath().normalize();
    Path y = temp.resolve("desired-Y/model.onnx").toAbsolutePath().normalize();
    Files.createDirectories(y.getParent());
    Files.createFile(y);
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "true",
        EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), y.getParent().toString(),
        EnvRegistry.SPLADE_ENABLED.configKey(), "false",
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "false",
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));
    var selection = GenerationModelSelection.accepted(Map.of(
        "embedding", new ModelArtifact(x.toString(), "a".repeat(64))), "splade", 768);
    var applied = EncoderConfigurationProjection.from(cfg, selection);

    assertEquals(x.getParent(), applied.embedding().modelPath());
    assertNotEquals(EncoderConfigurationProjection.from(cfg).digest(), applied.digest());
    InferenceSurface surface = InferenceCompositionRoot.compose(applied,
        HardwareProfile.cpuOnly(), null, null, NO_GPU,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, selection);
    assertEquals(Set.of(EncoderRole.EMBEDDING), surface.componentObservation().missingRoles());
    assertFalse(surface.componentObservation().compositionSatisfied());
  }

  @Test
  @DisplayName("failed citation assembly never claims a generation-selected identity")
  void failedCitationAssemblyDoesNotLogGenerationIdentity(@TempDir Path temp) throws IOException {
    Path selectedFile = temp.resolve("generation-X/model.onnx").toAbsolutePath().normalize();
    Path desiredFile = temp.resolve("desired-Y/model.onnx").toAbsolutePath().normalize();
    Files.createDirectories(selectedFile.getParent());
    Files.createDirectories(desiredFile.getParent());
    Files.writeString(selectedFile, "generation-selected-citation-model");
    Files.writeString(desiredFile, "conflicting-desired-citation-model");
    String selectedSha = sha256(selectedFile);
    String desiredSha = sha256(desiredFile);

    var cfg =
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                EnvRegistry.AI_EMBED_ENABLED.configKey(), "false",
                EnvRegistry.SPLADE_ENABLED.configKey(), "false",
                EnvRegistry.NER_ENABLED.configKey(), "false",
                EnvRegistry.RERANK_ENABLED.configKey(), "false",
                EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "true",
                EnvRegistry.CITATION_SCORER_MODEL_PATH.configKey(),
                    desiredFile.getParent().toString()));
    var selection =
        GenerationModelSelection.accepted(
            Map.of("citation-scorer", new ModelArtifact(selectedFile.toString(), selectedSha)),
            "splade",
            768);
    var projection = EncoderConfigurationProjection.from(cfg, selection);
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(InferenceCompositionRoot.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      try (InferenceSurface surface = InferenceCompositionRoot.compose(
          projection,
          HardwareProfile.cpuOnly(),
          null,
          null,
          NO_GPU,
          io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP,
          selection)) {
        assertTrue(surface.citation().isEmpty());
        assertTrue(surface.componentObservation().missingRoles().contains(EncoderRole.CITATION));
      }

      var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertTrue(messages.stream().noneMatch(message -> message.startsWith(
          "Citation scorer generation selected:")),
          () -> "failed citation assembly logged a selected identity in " + messages);
      assertTrue(messages.stream().noneMatch(message -> message.contains(desiredFile.toString())));
      assertTrue(messages.stream().noneMatch(message -> message.contains(desiredSha)));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("BGE selection remains requested when its SPLADE fallback is attempted")
  void selectedBgeFailureRemainsMissingAcrossFallback() {
    var cfg =
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                EnvRegistry.SPARSE_MODEL.configKey(), "bge-m3",
                EnvRegistry.BGE_M3_ENABLED.configKey(), "true",
                EnvRegistry.BGE_M3_MODEL_PATH.configKey(), "absent-bge",
                EnvRegistry.SPLADE_ENABLED.configKey(), "true",
                EnvRegistry.SPLADE_MODEL_PATH.configKey(), "absent-splade",
                EnvRegistry.AI_EMBED_ENABLED.configKey(), "false",
                EnvRegistry.NER_ENABLED.configKey(), "false",
                EnvRegistry.RERANK_ENABLED.configKey(), "false",
                EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));

    InferenceSurface surface =
        InferenceCompositionRoot.compose(cfg, HardwareProfile.cpuOnly(), null, null, NO_GPU);

    assertTrue(surface.componentObservation().requestedRoles().contains(EncoderRole.BGE_M3));
    assertTrue(surface.componentObservation().missingRoles().contains(EncoderRole.BGE_M3));
    assertTrue(surface.componentObservation().requestedRoles().contains(EncoderRole.SPLADE));
  }

  @Test
  @DisplayName("composition and digest use the supplied snapshot rather than ConfigStore.global")
  void suppliedSnapshotOwnsCompositionAndDigest() {
    var global =
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                EnvRegistry.ORT_VERBOSE_LOGGING.configKey(), "false",
                EnvRegistry.AI_EMBED_ENABLED.configKey(), "true",
                EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), "global-only-absent-model"));
    ConfigStore.setGlobal(new ConfigStore(global));
    var supplied =
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                EnvRegistry.ORT_VERBOSE_LOGGING.configKey(), "true",
                EnvRegistry.AI_EMBED_ENABLED.configKey(), "false",
                EnvRegistry.SPLADE_ENABLED.configKey(), "false",
                EnvRegistry.NER_ENABLED.configKey(), "false",
                EnvRegistry.RERANK_ENABLED.configKey(), "false",
                EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));

    InferenceSurface surface =
        InferenceCompositionRoot.compose(supplied, HardwareProfile.cpuOnly(), null, null, NO_GPU);

    assertEquals(
        EncoderConfigurationProjection.from(supplied).digest(),
        surface.componentObservation().configurationDigest().orElseThrow());
    assertTrue(surface.componentObservation().requestedRoles().isEmpty());
    assertFalse(
        EncoderConfigurationProjection.from(global)
             .digest()
            .equals(surface.componentObservation().configurationDigest().orElseThrow()));
  }

  @Test
  @DisplayName("enabled role configs and successful observation stay on the supplied model root")
  void enabledRoleConfigsUseSuppliedSnapshot(@TempDir Path tempDir) throws IOException {
    Path globalRoot = tempDir.resolve("global-models");
    Path suppliedRoot = tempDir.resolve("supplied-models");
    createAllRoleModels(globalRoot);
    createAllRoleModels(suppliedRoot);
    Map<String, String> enabled =
        Map.of(
            EnvRegistry.AI_EMBED_ENABLED.configKey(), "true",
            EnvRegistry.SPLADE_ENABLED.configKey(), "true",
            EnvRegistry.NER_ENABLED.configKey(), "true",
            EnvRegistry.BGE_M3_ENABLED.configKey(), "true",
            EnvRegistry.RERANK_ENABLED.configKey(), "true",
            EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "true");
    var globalEntries = new java.util.HashMap<>(enabled);
    globalEntries.put(EnvRegistry.MODELS_DIR.configKey(), globalRoot.toString());
    ConfigStore.setGlobal(
        new ConfigStore(TestResolvedConfigHelper.fromEntries(globalEntries)));
    var suppliedEntries = new java.util.HashMap<>(enabled);
    suppliedEntries.put(EnvRegistry.MODELS_DIR.configKey(), suppliedRoot.toString());
    var supplied = TestResolvedConfigHelper.fromEntries(suppliedEntries);

    EncoderConfigurationProjection projection = EncoderConfigurationProjection.from(supplied);

    assertEquals(
        suppliedRoot.resolve("onnx/gte-multilingual-base"), projection.embedding().modelPath());
    assertEquals(suppliedRoot.resolve("onnx/splade"), projection.splade().modelPath());
    assertEquals(suppliedRoot.resolve("onnx/ner"), projection.ner().modelPath());
    assertEquals(suppliedRoot.resolve("onnx/bge-m3"), projection.bgeM3().modelPath());
    assertEquals(suppliedRoot.resolve("onnx/reranker"), projection.reranker().modelPath());
    assertEquals(
        suppliedRoot.resolve("onnx/citation-scorer"), projection.citation().modelPath());
    assertTrue(projection.embedding().isReady());
    assertTrue(projection.splade().isReady());
    assertTrue(projection.ner().isReady());
    assertTrue(projection.bgeM3().isReady());
    assertTrue(projection.reranker().isReady());
    assertTrue(projection.citation().isReady());
  }

  @Test
  @DisplayName("applied digest changes only for declared encoder dependencies")
  void appliedDigestTracksDeclaredDependenciesOnly() {
    var baseline =
        TestResolvedConfigHelper.fromEntries(
            Map.of(EnvRegistry.ORT_VERBOSE_LOGGING.configKey(), "false"));
    var declaredChange =
        TestResolvedConfigHelper.fromEntries(
            Map.of(EnvRegistry.ORT_VERBOSE_LOGGING.configKey(), "true"));
    var unrelatedChange =
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                EnvRegistry.ORT_VERBOSE_LOGGING.configKey(), "false",
                EnvRegistry.API_PORT.configKey(), "43210"));

    String baselineDigest = EncoderConfigurationProjection.from(baseline).digest();
    assertNotEquals(
        baselineDigest, EncoderConfigurationProjection.from(declaredChange).digest());
    assertEquals(
        baselineDigest, EncoderConfigurationProjection.from(unrelatedChange).digest());
  }

  @Test
  @DisplayName("shared GPU inputs are typed and change the encoder applied digest")
  void sharedGpuInputsAreTypedDependencies() {
    var gpuOff =
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                EnvRegistry.GPU_ENABLED.configKey(), "false",
                EnvRegistry.POLICY_GPU_ACCELERATION_ENABLED.configKey(), "true"));
    var gpuOn =
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                EnvRegistry.GPU_ENABLED.configKey(), "true",
                EnvRegistry.POLICY_GPU_ACCELERATION_ENABLED.configKey(), "true"));
    var policyOff =
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                EnvRegistry.GPU_ENABLED.configKey(), "true",
                EnvRegistry.POLICY_GPU_ACCELERATION_ENABLED.configKey(), "false"));

    assertFalse(gpuOff.ai().masterGpuEnabled());
    assertTrue(gpuOff.ai().gpuAccelerationAllowed());
    assertTrue(gpuOn.ai().masterGpuEnabled());
    assertFalse(policyOff.ai().gpuAccelerationAllowed());
    assertNotEquals(
        EncoderConfigurationProjection.from(gpuOff).digest(),
        EncoderConfigurationProjection.from(gpuOn).digest());
    assertNotEquals(
        EncoderConfigurationProjection.from(gpuOn).digest(),
        EncoderConfigurationProjection.from(policyOff).digest());
  }

  private static void createAllRoleModels(Path root) throws IOException {
    createModel(root.resolve("onnx/gte-multilingual-base"), "model.onnx", "tokenizer.json");
    createModel(root.resolve("onnx/splade"), "model.onnx", "tokenizer.json", "vocab.txt");
    createModel(root.resolve("onnx/ner"), "model.onnx", "tokenizer.json");
    createModel(
        root.resolve("onnx/bge-m3"), "model_fp16_with_sparse.onnx", "tokenizer.json");
    createModel(root.resolve("onnx/reranker"), "model.onnx", "tokenizer.json");
    createModel(root.resolve("onnx/citation-scorer"), "model.onnx", "tokenizer.json");
  }

  private static void createModel(Path dir, String... files) throws IOException {
    Files.createDirectories(dir);
    for (String file : files) {
      Files.writeString(dir.resolve(file), "stub");
    }
  }

  private static String sha256(Path file) throws IOException {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
