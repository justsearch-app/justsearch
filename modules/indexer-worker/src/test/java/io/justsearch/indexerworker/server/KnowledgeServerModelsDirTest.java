package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 374 alpha.18 Bug H + alpha.20 Bug M regression coverage for
 * {@link KnowledgeServer#resolveModelsDir(InstallContract, Path)}.
 *
 * <p>Three-tier resolution priority:
 *
 * <ol>
 *   <li>{@code contract.modelsDir()} — alpha.20 (survives cold restart).
 *   <li>{@code ConfigStore.paths().modelsDir()} — alpha.18 (env var bridged at head boot).
 *   <li>{@code aiHome/models} — default flow.
 * </ol>
 *
 * <p>Round-7/8 sandbox: pre-alpha.18 worker hardcoded {@code aiHome/models}, missed env
 * var. Round-10 sandbox: alpha.18-fix worked at first launch but failed on cold restart
 * because the env var didn't inherit across GUI launches. Alpha.20 records the absolute
 * path in the install contract — survives.
 */
@DisplayName("KnowledgeServer.resolveModelsDir (374 alpha.18 Bug H + alpha.20 Bug M)")
class KnowledgeServerModelsDirTest {

  private static final String KEY_MODELS_DIR = "justsearch.models.dir";

  @Test
  void capturedSnapshotWinsOverGlobalChangedDuringComposition() {
    Path capturedModels = tmp.resolve("captured-models");
    var captured = TestResolvedConfigHelper.fromEntries(
        Map.of(KEY_MODELS_DIR, capturedModels.toString()));
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(
        Map.of(KEY_MODELS_DIR, tmp.resolve("new-global-models").toString()))));

    assertEquals(capturedModels,
        KnowledgeServer.resolveModelsDir(null, tmp.resolve("aihome"), captured));
  }

  @TempDir Path tmp;

  private ConfigStore previousStore;
  private String prevModelsDir;

  @BeforeEach
  void capturePrev() {
    previousStore = ConfigStore.globalOrNull();
    prevModelsDir = System.getProperty(KEY_MODELS_DIR);
  }

  @AfterEach
  void restorePrev() {
    if (prevModelsDir == null) {
      System.clearProperty(KEY_MODELS_DIR);
    } else {
      System.setProperty(KEY_MODELS_DIR, prevModelsDir);
    }
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  /**
   * Tempdoc 374 alpha.20 Bug M primary fix: contract's recorded modelsDir takes priority.
   * This is the cold-restart-survivable path.
   */
  @Test
  @DisplayName("contract.modelsDir() set → returns contract value (Bug M fix, primary)")
  void contractModelsDir_takesPriority() {
    System.clearProperty(KEY_MODELS_DIR);
    TestResolvedConfigHelper.storeFromEnvironment();

    Path contractModels = tmp.resolve("contract-staged-models");
    InstallContract contract = new InstallContract(
        2, System.currentTimeMillis(), HardwareProfile.cpuOnly(), DownloadProfile.GPU_FULL,
        Map.of(), contractModels);

    Path aiHome = tmp.resolve("aihome");
    Path resolved = KnowledgeServer.resolveModelsDir(contract, aiHome);

    assertEquals(
        contractModels,
        resolved,
        "contract.modelsDir() must take priority over aiHome/models. Pre-alpha.20 the"
            + " resolver missed the contract field and fell to env-var-or-aiHome, breaking"
            + " cold restart for users who pre-stage models (374 alpha.20 Bug M).");
  }

  /** Contract takes priority even when env var is also set (contract is the more authoritative source). */
  @Test
  @DisplayName("contract.modelsDir() set AND env var set → contract wins")
  void contractModelsDir_winsOverEnvVar() {
    Path envModels = tmp.resolve("env-staged-models");
    System.setProperty(KEY_MODELS_DIR, envModels.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    Path contractModels = tmp.resolve("contract-staged-models");
    InstallContract contract = new InstallContract(
        2, System.currentTimeMillis(), HardwareProfile.cpuOnly(), DownloadProfile.GPU_FULL,
        Map.of(), contractModels);

    Path resolved = KnowledgeServer.resolveModelsDir(contract, tmp.resolve("aihome"));

    assertEquals(
        contractModels,
        resolved,
        "contract is the authoritative source. If user changed JUSTSEARCH_MODELS_DIR but"
            + " didn't re-run Install AI, the contract value wins until the contract is"
            + " rewritten.");
  }

  /**
   * Pre-alpha.20 contracts (no modelsDir field) deserialize with null. Resolver falls
   * through to the alpha.18 env-var path.
   */
  @Test
  @DisplayName("contract null modelsDir + env var set → returns env-var path (alpha.18 fallback)")
  void contractNullModelsDir_envVarSet() {
    Path envModels = tmp.resolve("env-staged-models");
    System.setProperty(KEY_MODELS_DIR, envModels.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    InstallContract contractWithoutModelsDir = new InstallContract(
        2, System.currentTimeMillis(), HardwareProfile.cpuOnly(), DownloadProfile.GPU_FULL,
        Map.of()); // 5-arg constructor → modelsDir defaults to null

    Path resolved = KnowledgeServer.resolveModelsDir(contractWithoutModelsDir, tmp.resolve("aihome"));

    assertEquals(
        envModels.toAbsolutePath().normalize(),
        resolved == null ? null : resolved.toAbsolutePath().normalize(),
        "pre-alpha.20 contracts (modelsDir=null) must fall through to the alpha.18 env-var"
            + " resolution path so existing installs aren't broken until they re-run Install AI.");
  }

  @Test
  @DisplayName("null contract + env var set → returns env-var path (alpha.18 behaviour)")
  void noContract_envVarSet() {
    Path envModels = tmp.resolve("env-staged-models");
    System.setProperty(KEY_MODELS_DIR, envModels.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    Path resolved = KnowledgeServer.resolveModelsDir(null, tmp.resolve("aihome"));

    assertEquals(
        envModels.toAbsolutePath().normalize(),
        resolved == null ? null : resolved.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("null contract + env var unset + aiHome present → returns aiHome/models (default flow)")
  void noContract_envVarUnset() {
    System.clearProperty(KEY_MODELS_DIR);
    TestResolvedConfigHelper.storeFromEnvironment();

    Path aiHome = tmp.resolve("aihome");
    Path resolved = KnowledgeServer.resolveModelsDir(null, aiHome);

    assertEquals(aiHome.resolve("models"), resolved);
  }

  @Test
  @DisplayName("null contract + env var unset + aiHome null → returns null (dev mode)")
  void noContract_envVarUnset_aiHomeNull() {
    System.clearProperty(KEY_MODELS_DIR);
    TestResolvedConfigHelper.storeFromEnvironment();

    Path resolved = KnowledgeServer.resolveModelsDir(null, null);

    assertNull(resolved);
  }
}
