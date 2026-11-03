/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.UiSettingsStore;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Best match first is off and keeps the identities of the installed reranker it served. An upgrade
 * or Repair then installs new verified bytes for that same installed model (same file, same
 * variant). The installer's activation candidate, saved by the settings owner, must leave Off as
 * it is and let switching on restore the installed model: the Worker's query preparation verifies
 * the kept identities and, on f111a68d66, refused the sanctioned replacement as changed files. A
 * replacement this installation does not explain is still refused.
 */
final class RerankerSwitchAfterRepairTest {
  private static final QueryRoleSelection DISABLED = new QueryRoleSelection(
      QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled());
  @TempDir Path home;

  @Test
  void anUpgradedInstalledRerankerStaysOffAndSwitchesOnWithItsNewFiles() throws Exception {
    Path root = home.resolve("models");
    var before = reranker("rerank-model-v1", "rerank-tokenizer-v1");
    Path model = stage(root, before, "rerank-model-v1", "rerank-tokenizer-v1");
    var kept = retained(model);
    var store = storeOff(kept);

    // The sanctioned upgrade: the registry and the contract name new bytes for the same file.
    var after = reranker("rerank-model-v2", "rerank-tokenizer-v2");
    stage(root, after, "rerank-model-v2", "rerank-tokenizer-v2");
    assertNotEquals(kept.model(), witness(model), "the upgrade replaced the kept bytes");

    UiSettings saved = activate(store, after);

    assertEquals(Boolean.FALSE, saved.getRerankerEnabled(), "Off stays off");
    var on = assertDoesNotThrow(() -> switchOn(saved, model),
        "switching on is not refused for the sanctioned replacement");
    assertEquals(witness(model), on.model(), "On restores the upgraded installed model");
    assertEquals(witness(model.resolveSibling("tokenizer.json")), on.tokenizer());
    assertEquals("model.onnx", on.variantId());
    assertEquals(ExecutionProvider.CPU, on.targetEp());
  }

  @Test
  void anUnexplainedReplacementIsStillRefused() throws Exception {
    Path root = home.resolve("models");
    var installed = reranker("rerank-model-v1", "rerank-tokenizer-v1");
    Path model = stage(root, installed, "rerank-model-v1", "rerank-tokenizer-v1");
    var kept = retained(model);
    var store = storeOff(kept);
    // Bytes no registry or contract names: not a sanctioned upgrade or Repair.
    Files.writeString(model, "rerank-model-tampered", StandardCharsets.UTF_8);

    UiSettings saved = activate(store, installed);

    assertEquals(Boolean.FALSE, saved.getRerankerEnabled());
    assertEquals(kept, saved.getRerankerRestoreSelection(), "nothing explains the new bytes");
    var refused = assertThrows(java.io.IOException.class, () -> switchOn(saved, model));
    assertTrue(refused.getMessage().contains("kept Best match first files changed"),
        refused.getMessage());
  }

  @Test
  void aRetainedCustomModelIsNotReplacedByTheInstalledOne() throws Exception {
    Path root = home.resolve("models");
    stage(root, reranker("rerank-model-v1", "rerank-tokenizer-v1"), "rerank-model-v1",
        "rerank-tokenizer-v1");
    Path custom = Files.createDirectories(home.resolve("custom-ranker"));
    Path customModel = Files.writeString(custom.resolve("model.onnx"), "custom-model");
    Files.writeString(custom.resolve("tokenizer.json"), "custom-tokenizer");
    var kept = retained(customModel);
    var store = storeOff(kept);
    var after = reranker("rerank-model-v2", "rerank-tokenizer-v2");
    stage(root, after, "rerank-model-v2", "rerank-tokenizer-v2");

    UiSettings saved = activate(store, after);

    assertEquals(kept, saved.getRerankerRestoreSelection(), "the custom choice is kept");
    assertEquals(kept, switchOn(saved, customModel), "On restores the custom files");
  }

  /** Off with the given kept selection, saved by the settings owner's store. */
  private UiSettingsStore storeOff(QueryRoleSelection.Role kept) throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        home.resolve("settings.json"));
    var off = new UiSettings();
    off.setRerankerEnabled(false);
    off.setRerankerRestoreSelection(kept);
    store.replacePrepared(store.prepare(off, new SettingsWitness(0, null), DISABLED));
    return store;
  }

  /**
   * The installer's activation after the files changed: its candidate is saved as the completion
   * saves a query-only candidate; without a candidate the saved settings stay as they were.
   */
  private UiSettings activate(UiSettingsStore store, ModelPackage installed) throws Exception {
    var prior = ConfigStore.globalOrNull();
    var current = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    ConfigStore.setGlobal(current);
    try {
      var service = spy(new AiInstallService(null, store, null, null, home));
      doReturn(new ModelRegistry(2, "test", List.of(installed))).when(service).getManifest();
      var candidate = service.prepareInstalledGenerationCandidate();
      if (candidate.isPresent()) {
        assertTrue(candidate.get().generationBoundKeys().isEmpty(), "a query-only candidate");
        store.replacePrepared(store.prepare(candidate.get().candidateSettings(),
            candidate.get().settingsWitness(), DISABLED));
      }
      assertEquals(DISABLED, store.inspect().queryRoles(), "the served roles stay disabled");
      return store.load();
    } finally {
      ConfigStore.restoreGlobal(current, prior);
    }
  }

  /** Switching on as the Worker's query preparation selects the reranker. */
  private static QueryRoleSelection.Role switchOn(UiSettings saved, Path model) throws Exception {
    var on = saved;
    on.setRerankerEnabled(true);
    return QueryRoleSelectionResolver.selectReranker(on, false, model.getParent(),
        QueryRoleSelection.Role.disabled(), null, false, false);
  }

  private static QueryRoleSelection.Role retained(Path model) throws Exception {
    return QueryRoleSelection.Role.selected("model.onnx", witness(model),
        witness(model.resolveSibling("tokenizer.json")), ModelPrecision.FP32,
        ExecutionProvider.CPU);
  }

  private static ModelPackage reranker(String model, String tokenizer) throws Exception {
    return new ModelPackage("reranker", "reranker", "reranker", "onnx/reranker",
        List.of(new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
            sha(model), model.length(), "https://example.invalid/model.onnx")),
        List.of(new SupportingFile("tokenizer.json", sha(tokenizer), tokenizer.length(),
            "https://example.invalid/tokenizer.json")), 0L, null);
  }

  /** Installs {@code pkg}'s files in its directory and records them in the install contract. */
  private Path stage(Path root, ModelPackage pkg, String model, String tokenizer)
      throws Exception {
    Path directory = Files.createDirectories(root.resolve(pkg.targetDir()));
    Path file = Files.writeString(directory.resolve("model.onnx"), model, StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("tokenizer.json"), tokenizer, StandardCharsets.UTF_8);
    ModelVariant variant = pkg.variants().get(0);
    InstallContractIO.write(new InstallContract(2, 1L, HardwareProfile.cpuOnly(),
        DownloadProfile.CPU, Map.of("reranker", new InstallContract.InstalledModel("reranker",
            variant.filename(), variant.precision(), variant.targetEP(), pkg.targetDir(),
            variant.sha256(), List.of("model.onnx", "tokenizer.json"), false, null)), root, null),
        home);
    return file.toAbsolutePath().normalize();
  }

  private static ModelFile witness(Path path) throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    return new ModelFile(path.toAbsolutePath().normalize(), HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length);
  }

  private static String sha(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
