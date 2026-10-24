package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.ModelPathSource;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InferenceConfigFromEnvironmentTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("LLM_MODEL_PATH override uses the explicit model path and resolves mmproj relative to the model directory")
  void llmModelPathOverrideWinsAndRelativizesAuxModelsToModelDir() throws Exception {
    Path modelDir = tempDir.resolve("custom-models");
    Files.createDirectories(modelDir);
    Path model = modelDir.resolve("my-model.gguf");
    Path mmproj = modelDir.resolve("my-mmproj.gguf");
    Files.writeString(model, "x");
    Files.writeString(mmproj, "x");

    try (var ignored =
        new SysProps()
            .set("justsearch.llm.model_path", model.toString())
            .set("justsearch.models.dir", tempDir.resolve("default-models").toString())
            .set("justsearch.vlm.model", "SHOULD_NOT_BE_USED.gguf")
            .set("justsearch.mmproj.model", mmproj.getFileName().toString())
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);
      assertEquals(model, cfg.modelPath());
      assertEquals(mmproj, cfg.mmprojPath());
      assertNull(cfg.chatProfileId(), "an operator path makes no profile claim");
    }
  }

  @Test
  @DisplayName("Relative LLM_MODEL_PATH override is resolved against baseDir")
  void relativeLlmModelPathResolvesAgainstBaseDir() throws Exception {
    Path modelDir = tempDir.resolve("rel");
    Files.createDirectories(modelDir);
    Path model = modelDir.resolve("rel-model.gguf");
    Files.writeString(model, "x");

    try (var ignored =
        new SysProps()
            .set("justsearch.llm.model_path", "rel/rel-model.gguf")
            .set("justsearch.mmproj.model", "none")
            // Exercise the baseDir argument with no configured base in this fixture snapshot.
            .source(EnvRegistry.HOME.configKey(), ResolvedConfigBuilder.ORDINAL_JVM_ARG,
                "test_fixture", null)
            .source(EnvRegistry.HOME.configKey(), ResolvedConfigBuilder.ORDINAL_ENV_VAR,
                "test_fixture", null)
            .source(EnvRegistry.DATA_DIR.configKey(), ResolvedConfigBuilder.ORDINAL_JVM_ARG,
                "test_fixture", null)
            .source(EnvRegistry.DATA_DIR.configKey(), ResolvedConfigBuilder.ORDINAL_ENV_VAR,
                "test_fixture", null)
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);
      assertEquals(model, cfg.modelPath());
      assertNull(cfg.mmprojPath());
    }
  }

  @Test
  @DisplayName("mmproj can be disabled by setting justsearch.mmproj.model=none")
  void mmprojNoneDisablesMmprojPath() throws Exception {
    Path modelDir = tempDir.resolve("custom2");
    Files.createDirectories(modelDir);
    Path model = modelDir.resolve("m.gguf");
    Files.writeString(model, "x");

    try (var ignored =
        new SysProps()
            .set("justsearch.llm.model_path", model.toString())
            .set("justsearch.mmproj.model", "none")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);
      assertEquals(model, cfg.modelPath());
      assertNull(cfg.mmprojPath());
    }
  }

  @Test
  @DisplayName("justsearch.models.dir is used when baseDir is isolated")
  void modelsDirOverrideResolvesModelFromSharedRoot() throws Exception {
    Path baseDir = tempDir.resolve("isolated-base");
    Files.createDirectories(baseDir);
    Path sharedModels = tempDir.resolve("shared-models");
    Files.createDirectories(sharedModels);
    Path model = sharedModels.resolve("Qwen_Qwen3.5-9B-Q4_K_M.gguf");
    Files.writeString(model, "x");

    try (var ignored =
        new SysProps()
            .set("justsearch.models.dir", sharedModels.toString())
            .set("justsearch.mmproj.model", "none")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(baseDir);
      assertEquals(model, cfg.modelPath());
      assertNull(cfg.mmprojPath());
    }
  }

  @Test
  @DisplayName("explicit repo root provides fallback model discovery")
  void repoRootProvidesFallbackModelsDir() throws Exception {
    Path baseDir = tempDir.resolve("isolated-base");
    Files.createDirectories(baseDir);
    Path repoRoot = tempDir.resolve("repo-root");
    Path modelsDir = repoRoot.resolve("models");
    Files.createDirectories(modelsDir);
    Path model = modelsDir.resolve("Qwen_Qwen3.5-9B-Q4_K_M.gguf");
    Files.writeString(model, "x");

    try (var ignored =
        new SysProps()
            .set("justsearch.repo.root", repoRoot.toString())
            .set("justsearch.models.dir", modelsDir.toString())
            .set("justsearch.mmproj.model", "none")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(baseDir);
      assertEquals(model, cfg.modelPath());
      assertNull(cfg.mmprojPath());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tempdoc 842 §2.3 — chat model profile resolution and the override precedence around it.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("nothing set → the STANDARD pair, mmproj carried, profile claimed")
  void defaultSelectsTheStandardPairAndClaimsIt() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.STANDARD);

    try (var ignored = new SysProps().baseline().set("justsearch.models.dir", modelsDir.toString()).initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      assertEquals(modelsDir.resolve(ChatModelProfile.STANDARD.modelFile()), cfg.modelPath());
      // The pre-842 defect was the projector going missing without a word; pin that it is carried.
      assertEquals(modelsDir.resolve(ChatModelProfile.STANDARD.mmprojFile()), cfg.mmprojPath());
      assertEquals("standard", cfg.chatProfileId());
    }
  }

  @Test
  @DisplayName("chat.profile=compact → the compact pair under its models-dir subdirectory")
  void explicitCompactProfileSelectsTheCompactPair() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.COMPACT);

    try (var ignored =
        new SysProps()
            .baseline()
            .set("justsearch.models.dir", modelsDir.toString())
            .set(EnvRegistry.CHAT_PROFILE.sysProp(), "compact")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      // "compact/..." is a registry targetDir-relative name — a flat resolve would miss the subdir.
      assertEquals(modelsDir.resolve("compact/Qwen3.5-4B-Q4_K_M.gguf"), cfg.modelPath());
      assertEquals(modelsDir.resolve("compact/mmproj-F16.gguf"), cfg.mmprojPath());
      assertEquals("compact", cfg.chatProfileId());
    }
  }

  /**
   * The landmine this whole precedence rule exists for: every installed and dev data dir already
   * stores a 9B {@code llm.model_path}, promoted into a system property at boot with the
   * {@code ui_settings} marker. Under the pre-842 "any stored path wins" rule an explicit compact
   * profile is silently inert — the stack keeps loading the 9B and nothing anywhere says why.
   */
  @Test
  @DisplayName("explicit profile supersedes a SYSTEM-OWNED stored model path")
  void explicitProfileSupersedesSystemOwnedStoredModelPath() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.COMPACT);
    Path storedStandard = modelsDir.resolve(ChatModelProfile.STANDARD.modelFile());
    Files.writeString(storedStandard, "x");

    try (var ignored =
        new SysProps()
            .baseline()
            .set("justsearch.models.dir", modelsDir.toString())
            .set(EnvRegistry.LLM_MODEL_PATH.sysProp(), storedStandard.toString())
            .set(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH, ModelPathSource.UI_SETTINGS)
            .set(EnvRegistry.CHAT_PROFILE.sysProp(), "compact")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      assertEquals(modelsDir.resolve(ChatModelProfile.COMPACT.modelFile()), cfg.modelPath());
      assertEquals(modelsDir.resolve(ChatModelProfile.COMPACT.mmprojFile()), cfg.mmprojPath());
      assertEquals("compact", cfg.chatProfileId());
    }
  }

  @Test
  @DisplayName("operator ENV VAR model path beats an explicit profile and drops the projector")
  void operatorEnvVarModelPathBeatsExplicitProfile() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.COMPACT);
    Path operatorDir = tempDir.resolve("operator");
    Files.createDirectories(operatorDir);
    Path operatorModel = operatorDir.resolve("operator-choice.gguf");
    Files.writeString(operatorModel, "x");

    try (var ignored =
        new SysProps()
            .baseline()
            .set("justsearch.models.dir", modelsDir.toString())
            .set(EnvRegistry.CHAT_PROFILE.sysProp(), "compact")
            // No sysprop for the model path: it arrives from the environment ordinal, which is
            // always an operator lock regardless of any marker.
            .env(EnvRegistry.LLM_MODEL_PATH, operatorModel.toString())
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      assertEquals(operatorModel, cfg.modelPath());
      assertNull(cfg.mmprojPath(), "a bare operator path has no known projector");
      assertNull(cfg.chatProfileId(), "an operator path makes no profile claim");
    }
  }

  @Test
  @DisplayName("operator ENV VAR model path keeps the projector when MMPROJ_MODEL is set")
  void operatorEnvVarModelPathKeepsExplicitlyConfiguredMmproj() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.COMPACT);
    Path operatorDir = tempDir.resolve("operator2");
    Files.createDirectories(operatorDir);
    Path operatorModel = operatorDir.resolve("operator-choice.gguf");
    Path operatorMmproj = operatorDir.resolve("operator-mmproj.gguf");
    Files.writeString(operatorModel, "x");
    Files.writeString(operatorMmproj, "x");

    try (var ignored =
        new SysProps()
            .baseline()
            .set("justsearch.models.dir", modelsDir.toString())
            .set(EnvRegistry.CHAT_PROFILE.sysProp(), "compact")
            .set(EnvRegistry.MMPROJ_MODEL.sysProp(), operatorMmproj.getFileName().toString())
            .env(EnvRegistry.LLM_MODEL_PATH, operatorModel.toString())
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      assertEquals(operatorModel, cfg.modelPath());
      assertEquals(operatorMmproj, cfg.mmprojPath());
      assertNull(cfg.chatProfileId());
    }
  }

  @Test
  @DisplayName("UNMARKED sysprop model path beats an explicit profile (a human named that file)")
  void unmarkedSyspropModelPathBeatsExplicitProfile() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.COMPACT);
    Path operatorDir = tempDir.resolve("operator3");
    Files.createDirectories(operatorDir);
    Path operatorModel = operatorDir.resolve("hand-picked.gguf");
    Files.writeString(operatorModel, "x");

    try (var ignored =
        new SysProps()
            .baseline()
            .set("justsearch.models.dir", modelsDir.toString())
            .set(EnvRegistry.LLM_MODEL_PATH.sysProp(), operatorModel.toString())
            // No .source marker at all → operator-owned, sacred.
            .set(EnvRegistry.CHAT_PROFILE.sysProp(), "compact")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      assertEquals(operatorModel, cfg.modelPath());
      assertNull(cfg.mmprojPath());
      assertNull(cfg.chatProfileId());
    }
  }

  @Test
  @DisplayName("status quo: a system-owned stored path still wins when NO profile is explicit")
  void storedModelPathWinsWhenNoProfileIsExplicit() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.STANDARD);
    Path storedDir = tempDir.resolve("stored");
    Files.createDirectories(storedDir);
    Path storedModel = storedDir.resolve("stored-model.gguf");
    Files.writeString(storedModel, "x");

    try (var ignored =
        new SysProps()
            .baseline()
            .set("justsearch.models.dir", modelsDir.toString())
            .set(EnvRegistry.LLM_MODEL_PATH.sysProp(), storedModel.toString())
            .set(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH, ModelPathSource.UI_SETTINGS)
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      // Nobody named a profile, so nothing changes for an existing install.
      assertEquals(storedModel, cfg.modelPath());
      assertNull(cfg.mmprojPath());
      assertNull(cfg.chatProfileId());
    }
  }

  @Test
  @DisplayName("legacy justsearch.vlm.profile=paddle-ocr-vl still selects the paddle pair")
  void legacyVlmProfileStillSelectsThePaddlePair() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.PADDLE_OCR_VL);

    try (var ignored =
        new SysProps()
            .baseline()
            .set("justsearch.models.dir", modelsDir.toString())
            .set(EnvRegistry.VLM_PROFILE.sysProp(), "paddle-ocr-vl")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      assertEquals(modelsDir.resolve(ChatModelProfile.PADDLE_OCR_VL.modelFile()), cfg.modelPath());
      assertEquals(modelsDir.resolve(ChatModelProfile.PADDLE_OCR_VL.mmprojFile()), cfg.mmprojPath());
      assertEquals("paddle-ocr-vl", cfg.chatProfileId());
    }
  }

  @Test
  @DisplayName("settings.json-borne model path is superseded by an explicit profile")
  void settingsBorneModelPathIsSupersededByAnExplicitProfile() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.COMPACT);
    Path storedDir = tempDir.resolve("settings-borne");
    Files.createDirectories(storedDir);
    Path storedModel = storedDir.resolve("from-settings.gguf");
    Files.writeString(storedModel, "x");

    try (var ignored =
        new SysProps()
            .baseline()
            .set("justsearch.models.dir", modelsDir.toString())
            .set(EnvRegistry.CHAT_PROFILE.sysProp(), "compact")
            // Arrives at the settings.json ordinal with no marker anywhere — re-derivable, so the
            // profile governs.
            .source(
                EnvRegistry.LLM_MODEL_PATH.configKey(),
                ResolvedConfigBuilder.ORDINAL_SETTINGS_JSON,
                "settings.json",
                storedModel.toString())
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      assertEquals(modelsDir.resolve(ChatModelProfile.COMPACT.modelFile()), cfg.modelPath());
      assertEquals(modelsDir.resolve(ChatModelProfile.COMPACT.mmprojFile()), cfg.mmprojPath());
      assertEquals("compact", cfg.chatProfileId());
    }
  }

  @Test
  @DisplayName("config.yaml model path is operator-authored and beats an explicit profile")
  void yamlBorneModelPathBeatsAnExplicitProfile() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.COMPACT);
    Path yamlDir = tempDir.resolve("yaml-choice");
    Files.createDirectories(yamlDir);
    Path yamlModel = yamlDir.resolve("from-yaml.gguf");
    Files.writeString(yamlModel, "x");

    try (var ignored =
        new SysProps()
            .baseline()
            .set("justsearch.models.dir", modelsDir.toString())
            .set(EnvRegistry.CHAT_PROFILE.sysProp(), "compact")
            // Nothing but a human writes config.yaml, so a path there is an operator lock.
            .source(
                EnvRegistry.LLM_MODEL_PATH.configKey(),
                ResolvedConfigBuilder.ORDINAL_YAML,
                "yaml",
                yamlModel.toString())
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      assertEquals(yamlModel, cfg.modelPath());
      assertNull(cfg.mmprojPath());
      assertNull(cfg.chatProfileId());
    }
  }

  @Test
  @DisplayName("a YAML-borne chat.profile counts as explicit (the chain is read, not just env)")
  void yamlBorneChatProfileIsExplicit() throws Exception {
    Path modelsDir = installProfile(ChatModelProfile.COMPACT);
    Path storedStandard = modelsDir.resolve(ChatModelProfile.STANDARD.modelFile());
    Files.createDirectories(storedStandard.getParent());
    Files.writeString(storedStandard, "x");

    try (var ignored =
        new SysProps()
            .baseline()
            .set("justsearch.models.dir", modelsDir.toString())
            .set(EnvRegistry.LLM_MODEL_PATH.sysProp(), storedStandard.toString())
            .set(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH, ModelPathSource.UI_SETTINGS)
            .source(EnvRegistry.CHAT_PROFILE.configKey(), ResolvedConfigBuilder.ORDINAL_YAML, "yaml", "compact")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);

      assertEquals(modelsDir.resolve(ChatModelProfile.COMPACT.modelFile()), cfg.modelPath());
      assertEquals("compact", cfg.chatProfileId());
    }
  }

  /** Materializes a profile's pair under a fresh models dir so mmproj existence checks pass. */
  private Path installProfile(ChatModelProfile profile) throws Exception {
    Path modelsDir = tempDir.resolve("models-" + profile.id());
    for (String file : new String[] {profile.modelFile(), profile.mmprojFile()}) {
      Path target = modelsDir.resolve(file);
      Files.createDirectories(target.getParent());
      Files.writeString(target, "x");
    }
    return modelsDir;
  }

  /** Minimal sysprop helper that restores previous values on close and sets up ConfigStore. */
  private static final class SysProps implements AutoCloseable {
    /**
     * Keys every profile-precedence test must start from a known state: an ambient value left by
     * another test (or by the developer's own shell) would silently decide the case under test.
     */
    private static final String[] BASELINE_KEYS = {
      "justsearch.llm.model_path",
      ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH,
      "justsearch.chat.profile",
      "justsearch.vlm.profile",
      "justsearch.vlm.model",
      "justsearch.mmproj.model",
      "justsearch.models.dir",
      "justsearch.repo.root",
    };

    /** key -> [ordinal, sourceName, value] contributions layered on top of the env/sysprop sweep. */
    private final java.util.List<Object[]> simulatedSources = new java.util.ArrayList<>();

    private final java.util.Map<String, String> prev = new java.util.HashMap<>();
    private final ConfigStore prevStore = ConfigStore.globalOrNull();
    private boolean storeInitialized;

    SysProps set(String key, String value) {
      if (!prev.containsKey(key)) {
        prev.put(key, System.getProperty(key));
      }
      if (value == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, value);
      }
      return this;
    }

    /** Clears every key the profile precedence reads, so each test starts from a blank slate. */
    SysProps baseline() {
      for (String key : BASELINE_KEYS) {
        set(key, null);
      }
      return this;
    }

    /**
     * Simulates an environment variable for {@code key} by contributing it at the env-var ordinal.
     *
     * <p>{@code System.getenv} is immutable in-process, so the env-var branch is exercised where
     * the production code actually reads provenance: the resolution trace. This is not a
     * shortcut — {@code InferenceConfig} classifies ownership from
     * {@code ResolvedConfig.resolution(...).sourceName()}, so an ordinal-400 contribution is
     * exactly the shape a real {@code JUSTSEARCH_LLM_MODEL_PATH} produces.
     */
    SysProps env(EnvRegistry key, String value) {
      return source(
          key.configKey(), ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var", value);
    }

    /**
     * Contributes {@code value} for {@code key} at an arbitrary ordinal/source name, so tests can
     * reach the settings.json and YAML branches of the ownership classification without a real
     * settings file or config.yaml on disk.
     */
    SysProps source(String key, int ordinal, String sourceName, String value) {
      simulatedSources.add(new Object[] {key, ordinal, sourceName, value});
      return this;
    }

    /** Initialize ConfigStore from current system properties. Call after all set() calls. */
    SysProps initStore() {
      if (simulatedSources.isEmpty()) {
        TestResolvedConfigHelper.storeFromEnvironment();
      } else {
        ResolvedConfigBuilder b = ResolvedConfig.builder();
        b.contributeEnvRegistry();
        for (Object[] entry : simulatedSources) {
          b.put(
              (String) entry[0],
              (Integer) entry[1],
              (String) entry[2],
              "simulated",
              (String) entry[3]);
        }
        ConfigStore.setGlobal(new ConfigStore(b.build()));
      }
      storeInitialized = true;
      return this;
    }

    @Override
    public void close() {
      for (var e : prev.entrySet()) {
        if (e.getValue() == null) {
          System.clearProperty(e.getKey());
        } else {
          System.setProperty(e.getKey(), e.getValue());
        }
      }
      if (storeInitialized) {
        TestResolvedConfigHelper.restoreGlobal(prevStore);
      }
    }
  }
}
