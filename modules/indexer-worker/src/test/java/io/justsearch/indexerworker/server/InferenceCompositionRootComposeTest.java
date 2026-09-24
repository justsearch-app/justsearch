package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArbiter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    assertTrue(selection.hasUnavailableModel());
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
}
