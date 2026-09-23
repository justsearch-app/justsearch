/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.AiInstallService.InstalledGenerationCandidate;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.operations.handlers.ActivateInstalledModelsHandler;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.model.SupportingFile;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import io.justsearch.core.context.EngineContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Read-only installer candidate coverage at the activation boundary. */
class AiInstallOnnxSettingsProducerTest {
  @TempDir Path directory;

  @Test
  void candidateFreezesEligibleFilesWithoutWritingSettings() throws Exception {
    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    ModelPackage embedding = packageWith("embedding", "model.onnx", "tokenizer.json");
    ModelPackage reranker = packageWith("reranker", "reranker.onnx", "reranker.json");
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(embedding, reranker));
    Path root = directory.resolve("models");
    Files.createDirectories(root.resolve(embedding.targetDir()));
    Files.createDirectories(root.resolve(reranker.targetDir()));
    write(root.resolve(embedding.targetDir()).resolve("model.onnx"), "embed-model");
    write(root.resolve(embedding.targetDir()).resolve("tokenizer.json"), "embed-tokenizer");
    write(root.resolve(reranker.targetDir()).resolve("reranker.onnx"), "rerank-model");
    write(root.resolve(reranker.targetDir()).resolve("reranker.json"), "rerank-config");
    writeContract(root, embedding, reranker);

    SettingsService settings = mock(SettingsService.class);
    AiInstallService service = new AiInstallService(null, store, null, null, directory, null,
        settings);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore current = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    ConfigStore.setGlobal(current);
    InstalledGenerationCandidate candidate;
    try {
      candidate = service.prepareInstalledGenerationCandidate(registry).orElseThrow();
    } finally {
      ConfigStore.restoreGlobal(current, previous);
    }
    assertEquals(2, candidate.models().size());
    assertEquals(4, candidate.assets().size(), "model bytes and supporting files are both retained");
    assertTrue(candidate.generationBoundKeys().contains("justsearch.embed.onnx.model_path"));
    assertTrue(candidate.componentKeys().containsKey("index"));
    assertEquals(0L, store.inspect().witness().acceptedRevision());
    assertTrue(store.load().getEmbedOnnxModelPath().isBlank());
    verifyNoInteractions(settings);
  }

  @Test
  void activationCandidateCollectsEligiblePathsAtomicallyAndPreservesEarlierSettings()
      throws Exception {
    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    UiSettings earlier = store.inspect().settings();
    earlier.setChatEnabled(true);
    earlier.setExcludePatterns(List.of("*.tmp"));
    store.replacePrepared(store.prepareExact(earlier, store.inspect().witness()));

    List<ModelPackage> packages = List.of(
        packageWith("embedding", "embedding.onnx", "embedding-tokenizer.json"),
        packageWith("reranker", "reranker.onnx", "reranker-config.json"),
        packageWith("ner", "ner.onnx", "ner-config.json"),
        packageWith("splade", "splade.onnx", "splade-tokenizer.json"),
        packageWith("citation-scorer", "citation.onnx", "citation-config.json"));
    ModelRegistry registry = new ModelRegistry(2, "test", packages);
    Path root = directory.resolve("models");
    for (ModelPackage pkg : packages) stagePackage(root, pkg);
    writeContract(root, packages.toArray(ModelPackage[]::new));

    List<String> keys = List.of(
        "justsearch.embed.onnx.model_path",
        "justsearch.rerank.model_path",
        "justsearch.ner.model_path",
        "justsearch.splade.model_path",
        "justsearch.citation.scorer.model_path");
    List<String> beforeProperties = keys.stream()
        .map(key -> System.getProperty(key, "<absent>"))
        .toList();
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore current = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    ConfigStore.setGlobal(current);
    try {
      InstalledGenerationCandidate candidate = new AiInstallService(null, store, null, null,
          directory).prepareInstalledGenerationCandidate(registry).orElseThrow();

      assertEquals(5, candidate.models().size(), "all eligible models enter one activation candidate");
      assertEquals(10, candidate.assets().size(), "model and supporting assets share one candidate");
      Set<String> generationKeys = Set.of(
          "justsearch.embed.onnx.model_path",
          "justsearch.ner.model_path",
          "justsearch.splade.model_path");
      assertEquals(generationKeys, candidate.generationBoundKeys(),
          "only effective generation-bound paths require recorded activation");
      assertFalse(candidate.generationBoundKeys().contains("justsearch.rerank.model_path"),
          "reranker remains a query-only component update");
      assertFalse(candidate.generationBoundKeys().contains("justsearch.citation.scorer.model_path"),
          "citation scorer remains a query-only component update");
      String json = candidate.encodedSettings().canonicalJson();
      for (ModelPackage pkg : packages) {
        assertEquals(root.resolve(pkg.targetDir()).toAbsolutePath().normalize().toString(),
            settingsPath(candidate.candidateSettings(), pkg.id()),
            "candidate settings must carry " + pkg.id() + " together with the other paths");
      }
      assertTrue(json.contains("\"chatEnabled\":true"),
          "a previously accepted ordinary setting remains in the detached candidate");
      assertEquals(earlier.getChatEnabled(), store.load().getChatEnabled());
      assertTrue(store.load().getEmbedOnnxModelPath().isBlank(),
          "preparing activation cannot publish any ONNX path");
      assertEquals(beforeProperties, keys.stream()
          .map(key -> System.getProperty(key, "<absent>")).toList(),
          "candidate preparation cannot promote paths to JVM properties");
    } finally {
      ConfigStore.restoreGlobal(current, previous);
    }
  }

  @Test
  void approvedActivationPreparationUsesActualCandidateWithoutStartingTheRunner() throws Exception {
    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    ModelPackage embedding = packageWith("embedding", "model.onnx", "tokenizer.json");
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(embedding));
    Path root = directory.resolve("models");
    stagePackage(root, embedding);
    writeContract(root, embedding);

    AiInstallService helper = new AiInstallService(null, store, null, null, directory);
    BrainInstallService install = brainInstall(helper, registry);
    IndexingService indexing = mock(IndexingService.class);
    RecordedIngestionService ingestion = mock(RecordedIngestionService.class);
    EngineContext context = TestEngineContexts.internal();
    InvocationProvenance provenance = EngineProvenance.invocation(
        context, ExecutorTag.UI, Instant.parse("2026-09-23T00:00:00Z"), Optional.empty());
    when(indexing.captureServingGeneration(context)).thenReturn("serving-a");
    when(indexing.captureCandidateIndexTarget(any(), any()))
        .thenReturn(new IndexTargetSnapshot(sha("{}"), "{}"));
    ActivateInstalledModelsHandler handler = new ActivateInstalledModelsHandler(
        () -> install, ingestion,
        ignored -> List.of(new RootBinding(directory.resolve("watched").toAbsolutePath(), "documents")),
        () -> indexing, () -> List.of());

    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore current = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    ConfigStore.setGlobal(current);
    try {
      OperationPreparation prepared = handler.prepare(
          "{\"source\":\"installer_model_activation\"}", provenance, context);
      RecordedInstallerGenerationPlan plan = RecordedInstallerGenerationPlan.fromReplayPayload(
          prepared.replayPayloadJson());

      assertEquals("serving-a", plan.sourceGeneration());
      assertEquals(new SettingsWitness(0L, null), plan.settingsWitness());
      assertEquals(1, plan.models().size());
      assertEquals(2, plan.assets().size());
      assertEquals(1, plan.scope().roots().size());
      verifyNoInteractions(ingestion);

      Files.writeString(root.resolve(embedding.targetDir()).resolve("model.onnx"),
          "tampered-model", StandardCharsets.UTF_8);
      var refused = handler.executePrepared(prepared, provenance, context,
          mock(OperationRecordHandle.class));
      assertEquals("ACTIVATION_CANDIDATE_UNAVAILABLE", refused.response().errorCode().orElseThrow(),
          "a changed staged model must invalidate the accepted preview before ingestion");
      assertEquals(new SettingsWitness(0L, null), store.inspect().witness());
      verifyNoInteractions(ingestion);
    } finally {
      ConfigStore.restoreGlobal(current, previous);
    }
  }

  @Test
  void cancellingAcquisitionDoesNotRevokeAnAcceptedActivation() throws Exception {
    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    ModelPackage embedding = packageWith("embedding", "model.onnx", "tokenizer.json");
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(embedding));
    Path root = directory.resolve("models");
    stagePackage(root, embedding);
    writeContract(root, embedding);
    AiInstallService helper = new AiInstallService(null, store, null, null, directory);
    BrainInstallService install = brainInstall(helper, registry);
    IndexingService indexing = mock(IndexingService.class);
    RecordedIngestionService ingestion = mock(RecordedIngestionService.class);
    EngineContext context = TestEngineContexts.internal();
    InvocationProvenance provenance = EngineProvenance.invocation(
        context, ExecutorTag.UI, Instant.parse("2026-09-23T00:00:00Z"), Optional.empty());
    when(indexing.captureServingGeneration(context)).thenReturn("serving-a");
    when(indexing.captureCandidateIndexTarget(any(), any()))
        .thenReturn(new IndexTargetSnapshot(sha("{}"), "{}"));
    ActivateInstalledModelsHandler handler = new ActivateInstalledModelsHandler(
        () -> install, ingestion,
        ignored -> List.of(new RootBinding(directory.resolve("watched").toAbsolutePath(), "documents")),
        () -> indexing, List::of);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore current = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    ConfigStore.setGlobal(current);
    try {
      OperationPreparation accepted = handler.prepare(
          "{\"source\":\"installer_model_activation\"}", provenance, context);
      helper.cancel();
      OperationRecordHandle row = mock(OperationRecordHandle.class);
      OperationExecution expected = new OperationExecution(OperationResult.success("accepted"),
          new CompletableFuture<OperationResult>());
      when(ingestion.execute(row, context)).thenReturn(expected);

      assertSame(expected, handler.executePrepared(accepted, provenance, context, row));
      verify(ingestion).execute(row, context);
      assertEquals(new SettingsWitness(0L, null), store.inspect().witness(),
          "acquisition cancellation cannot commit model settings");
    } finally {
      ConfigStore.restoreGlobal(current, previous);
    }
  }

  private static BrainInstallService brainInstall(AiInstallService helper, ModelRegistry registry) {
    return new BrainInstallService() {
      @Override
      public Optional<InstalledGenerationCandidate> prepareInstalledGenerationCandidate() {
        return helper.prepareInstalledGenerationCandidate(registry);
      }

      @Override
      public io.justsearch.app.api.AiInstallService.Attempt startInstall(boolean acceptTerms)
          throws Exception {
        return helper.startInstall(acceptTerms);
      }

      @Override
      public Map<String, Object> cancelInstall() {
        helper.cancel();
        return Map.of();
      }

      @Override
      public io.justsearch.app.api.AiInstallService.Attempt repairInstall(boolean acceptTerms)
          throws Exception {
        return helper.repair(acceptTerms);
      }
    };
  }

  @Test
  void effectiveHigherPrecedenceOverrideKeepsDesiredPathOutOfActivationScope() throws Exception {
    String key = "justsearch.embed.onnx.model_path";
    String prior = System.getProperty(key);
    String override = directory.resolve("operator-override").toAbsolutePath().toString();
    System.setProperty(key, override);
    try {
      UiSettingsStore store = new UiSettingsStore(
          UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
      ModelPackage embedding = packageWith("embedding", "model.onnx", "tokenizer.json");
      ModelPackage reranker = packageWith("reranker", "reranker.onnx", "reranker.json");
      ModelRegistry registry = new ModelRegistry(2, "test", List.of(embedding, reranker));
      Path root = directory.resolve("models");
      stagePackage(root, embedding);
      stagePackage(root, reranker);
      writeContract(root, embedding, reranker);
      ConfigStore previous = ConfigStore.globalOrNull();
      ConfigStore current = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
      ConfigStore.setGlobal(current);
      try {
        InstalledGenerationCandidate candidate = new AiInstallService(null, store, null, null,
            directory).prepareInstalledGenerationCandidate(registry).orElseThrow();
        assertTrue(candidate.generationBoundKeys().isEmpty(),
            "the masked embedding path must not force a generation activation");
        assertFalse(candidate.componentKeys().isEmpty(),
            "the unmasked reranker path remains an ordinary component change");
        assertEquals(root.resolve(embedding.targetDir()).toAbsolutePath().normalize().toString(),
            candidate.candidateSettings().getEmbedOnnxModelPath(),
            "the desired embedding path remains frozen for ordinary settings commit despite the override");
      } finally {
        ConfigStore.restoreGlobal(current, previous);
      }
    } finally {
      if (prior == null) System.clearProperty(key); else System.setProperty(key, prior);
    }
  }

  @Test
  void candidateRetainsItsWitnessWhenSettingsAdvanceBeforeActivation() throws Exception {
    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    ModelPackage embedding = packageWith("embedding", "model.onnx", "tokenizer.json");
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(embedding));
    Path root = directory.resolve("models");
    stagePackage(root, embedding);
    writeContract(root, embedding);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore current = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    ConfigStore.setGlobal(current);
    try {
      InstalledGenerationCandidate frozen = new AiInstallService(null, store, null, null,
          directory).prepareInstalledGenerationCandidate(registry).orElseThrow();
      UiSettings changed = store.load();
      changed.setChatEnabled(true);
      store.replacePrepared(store.prepareExact(changed,
          new SettingsWitness(1L, OperationKeys.generate(Clock.systemUTC()))));

      assertEquals(new SettingsWitness(0L, null), frozen.settingsWitness(),
          "activation keeps the witness captured at preparation");
      assertFalse(frozen.encodedSettings().canonicalJson().contains("\"chatEnabled\":true"),
          "a later settings write cannot mutate the accepted candidate in place");
      assertTrue(store.load().getChatEnabled(),
          "the later settings owner remains authoritative until activation acceptance revalidates it");
    } finally {
      ConfigStore.restoreGlobal(current, previous);
    }
  }

  @Test
  void queryOnlyCandidateIsComponentScopedWithoutGenerationRebuild() throws Exception {
    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    ModelPackage reranker = packageWith("reranker", "reranker.onnx", "reranker.json");
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(reranker));
    Path root = directory.resolve("models");
    Files.createDirectories(root.resolve(reranker.targetDir()));
    write(root.resolve(reranker.targetDir()).resolve("reranker.onnx"), "rerank-model");
    write(root.resolve(reranker.targetDir()).resolve("reranker.json"), "rerank-config");
    writeContract(root, reranker);
    AiInstallService service = new AiInstallService(null, store, null, null, directory);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore current = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    ConfigStore.setGlobal(current);
    InstalledGenerationCandidate candidate;
    try {
      candidate = service.prepareInstalledGenerationCandidate(registry).orElseThrow();
    } finally {
      ConfigStore.restoreGlobal(current, previous);
    }
    assertTrue(candidate.componentKeys().containsKey("index"));
    assertTrue(candidate.generationBoundKeys().isEmpty());
  }

  @Test
  void skippedContractEntryCannotCreateCandidate() throws Exception {
    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    ModelPackage embedding = packageWith("embedding", "model.onnx", "tokenizer.json");
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(embedding));
    InstallContract contract = new InstallContract(2, 1L, HardwareProfile.cpuOnly(),
        DownloadProfile.CPU, Map.of("embedding", InstallContract.InstalledModel.skipped(
            "embedding", "failed")), directory.resolve("models"), null);
    InstallContractIO.write(contract, directory);
    AiInstallService service = new AiInstallService(null, store, null, null, directory);
    assertTrue(service.prepareInstalledGenerationCandidate(registry).isEmpty());
  }

  @Test
  void missingRequiredSupportingFileCannotBecomeAnActivationCandidate() throws Exception {
    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    ModelPackage embedding = packageWith("embedding", "model.onnx", "tokenizer.json");
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(embedding));
    Path root = directory.resolve("models");
    Files.createDirectories(root.resolve(embedding.targetDir()));
    write(root.resolve(embedding.targetDir()).resolve("model.onnx"), "embed-model");
    writeContract(root, embedding);
    AiInstallService service = new AiInstallService(null, store, null, null, directory);

    assertTrue(service.prepareInstalledGenerationCandidate(registry).isEmpty());
  }

  private void writeContract(Path root, ModelPackage... packages) throws Exception {
    Map<String, InstallContract.InstalledModel> entries = new java.util.LinkedHashMap<>();
    for (ModelPackage pkg : packages) {
      ModelVariant variant = pkg.variants().get(0);
      List<String> files = new java.util.ArrayList<>();
      files.add(variant.filename());
      files.addAll(pkg.supportingFiles().stream().map(SupportingFile::filename).toList());
      entries.put(pkg.id(), new InstallContract.InstalledModel(pkg.id(), variant.filename(),
          variant.precision(), variant.targetEP(), pkg.targetDir(), variant.sha256(), files, false,
          null));
    }
    InstallContractIO.write(new InstallContract(2, 1L, HardwareProfile.cpuOnly(),
        DownloadProfile.CPU, entries, root, null), directory);
  }

  private static void stagePackage(Path root, ModelPackage pkg) throws Exception {
    ModelVariant variant = pkg.variants().get(0);
    Path directory = root.resolve(pkg.targetDir());
    Files.createDirectories(directory);
    String model = pkg.id().equals("embedding") ? "embed-model"
        : pkg.id().equals("reranker") ? "rerank-model" : pkg.id() + "-model";
    String asset = pkg.id().equals("embedding") ? "embed-tokenizer"
        : pkg.id().equals("reranker") ? "rerank-config" : pkg.id() + "-asset";
    write(directory.resolve(variant.filename()), model);
    write(directory.resolve(pkg.supportingFiles().get(0).filename()), asset);
  }

  private static String settingsPath(UiSettings settings, String packageId) {
    return switch (packageId) {
      case "embedding" -> settings.getEmbedOnnxModelPath();
      case "reranker" -> settings.getRerankerModelPath();
      case "ner" -> settings.getNerModelPath();
      case "splade" -> settings.getSpladeModelPath();
      case "citation-scorer" -> settings.getCitationScorerModelPath();
      default -> throw new IllegalArgumentException("Unsupported test package: " + packageId);
    };
  }

  private static ModelPackage packageWith(String id, String model, String asset) throws Exception {
    String modelValue = id.equals("embedding") ? "embed-model"
        : id.equals("reranker") ? "rerank-model" : id + "-model";
    String assetValue = id.equals("embedding") ? "embed-tokenizer"
        : id.equals("reranker") ? "rerank-config" : id + "-asset";
    ModelVariant variant = new ModelVariant(model, ModelPrecision.FP32, ExecutionProvider.CPU,
        sha(modelValue), modelValue.length(), "https://example.invalid/" + model);
    SupportingFile supporting = new SupportingFile(asset, sha(assetValue), assetValue.length(),
        "https://example.invalid/" + asset);
    return new ModelPackage(id, id, id, "onnx/" + id, List.of(variant), List.of(supporting), 0L,
        null);
  }

  private static void write(Path path, String value) throws Exception {
    Files.writeString(path, value, StandardCharsets.UTF_8);
  }

  private static String sha(String value) throws Exception {
    return java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
