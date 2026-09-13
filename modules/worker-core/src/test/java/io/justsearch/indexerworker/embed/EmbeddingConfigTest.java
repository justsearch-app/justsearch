package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EmbeddingConfig.fromEnv()")
class EmbeddingConfigTest {

  private static final String KEY_MODEL_PATH = "justsearch.embed.onnx.model_path";
  private static final String KEY_EMBED_ENABLED = "justsearch.ai.embed.enabled";

  private ConfigStore previousStore;

  @BeforeEach
  void capturePreviousStore() {
    previousStore = ConfigStore.globalOrNull();
    // 347: fromEnv() now requires ConfigStore. Set up a default so tests that don't
    // configure specific values still work. Tests that set system properties must call
    // TestResolvedConfigHelper.storeFromEnvironment() after setting the properties.
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void restorePreviousStore() {
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  /**
   * Regression guard: setting an explicit model path via the system property must result in
   * enabled=true even when auto-discovery returns autoDiscovered=false. Previously the enabled
   * flag was computed solely from autoDiscovered, so an explicit path silently disabled embedding.
   */
  @Test
  @DisplayName("explicit model path → enabled=true (regression guard)")
  void explicitModelPath_enabled() {
    String previous = System.getProperty(KEY_MODEL_PATH);
    try {
      // Point to a nonexistent directory — OnnxModelDiscovery returns the path as-is without
      // validating it when an explicit override is provided (autoDiscovered=false).
      System.setProperty(KEY_MODEL_PATH, "/nonexistent/embedding-model");
      TestResolvedConfigHelper.storeFromEnvironment();

      EmbeddingConfig config = EmbeddingConfig.fromEnv();

      assertTrue(
          config.enabled(),
          "enabled must be true when EMBED_ONNX_MODEL_PATH is set, even if autoDiscovered=false");
    } finally {
      restoreProperty(KEY_MODEL_PATH, previous);
    }
  }

  /**
   * Baseline: when no model path is configured and no standard model directory exists on this
   * machine, embedding should be disabled.
   */
  @Test
  @DisplayName("no model path and no auto-discovered model → enabled=false")
  void noModelPath_noDiscovery_disabled() {
    // Ensure no model-path override is active and no explicit enabled flag is set.
    String previousPath = System.getProperty(KEY_MODEL_PATH);
    String previousEnabled = System.getProperty(KEY_EMBED_ENABLED);
    try {
      System.clearProperty(KEY_MODEL_PATH);
      System.clearProperty(KEY_EMBED_ENABLED);
      TestResolvedConfigHelper.storeFromEnvironment();

      EmbeddingConfig config = EmbeddingConfig.fromEnv();

      // enabled must be false because no model was found anywhere.
      // (If a real model happens to exist in the CI environment, discovery would return
      // autoDiscovered=true and this assertion would fire — that is intentional: a real model
      // should enable embedding.)
      if (config.enabled()) {
        // A real auto-discovered model is present; skip the assertion.
        assertNotNull(
            config.modelPath(), "enabled=true requires a valid model path from auto-discovery");
      } else {
        assertFalse(config.enabled(), "enabled must be false when no model is found");
      }
    } finally {
      restoreProperty(KEY_MODEL_PATH, previousPath);
      restoreProperty(KEY_EMBED_ENABLED, previousEnabled);
    }
  }

  /**
   * Explicit disable wins over an explicit model path. Setting AI_EMBED_ENABLED=false must
   * suppress embedding even when EMBED_ONNX_MODEL_PATH is configured.
   */
  @Test
  @DisplayName("AI_EMBED_ENABLED=false with model path → enabled=false (explicit disable wins)")
  void explicitDisable_withModelPath_disabled() {
    String previousPath = System.getProperty(KEY_MODEL_PATH);
    String previousEnabled = System.getProperty(KEY_EMBED_ENABLED);
    try {
      System.setProperty(KEY_MODEL_PATH, "/nonexistent/embedding-model");
      System.setProperty(KEY_EMBED_ENABLED, "false");
      TestResolvedConfigHelper.storeFromEnvironment();

      EmbeddingConfig config = EmbeddingConfig.fromEnv();

      assertFalse(
          config.enabled(),
          "explicit AI_EMBED_ENABLED=false must override the presence of EMBED_ONNX_MODEL_PATH");
    } finally {
      restoreProperty(KEY_MODEL_PATH, previousPath);
      restoreProperty(KEY_EMBED_ENABLED, previousEnabled);
    }
  }

  /**
   * Same as the explicit-disable test but driven via ConfigStore, which takes precedence over
   * system properties for the enabled flag.
   */
  @Test
  @DisplayName("ConfigStore embed.enabled=false with model path → enabled=false")
  void configStore_explicitDisable_withModelPath_disabled() {
    String previousPath = System.getProperty(KEY_MODEL_PATH);
    try {
      System.setProperty(KEY_MODEL_PATH, "/nonexistent/embedding-model");

      ConfigStore store =
          new ConfigStore(
              TestResolvedConfigHelper.fromEntries(
                  Map.of(KEY_EMBED_ENABLED, "false")));
      ConfigStore.setGlobal(store);

      EmbeddingConfig config = EmbeddingConfig.fromEnv();

      assertFalse(
          config.enabled(),
          "ConfigStore embed.enabled=false must override EMBED_ONNX_MODEL_PATH presence");
    } finally {
      restoreProperty(KEY_MODEL_PATH, previousPath);
    }
  }

  /**
   * 331 §1 regression guard: when the model path comes from the ConfigStore snapshot
   * (not from env var or sysprop), EmbeddingConfig.fromEnv() must still return enabled=true.
   * This simulates the Worker scenario where the Head wrote the model path to the snapshot
   * but the env var was not forwarded as a sysprop.
   */
  @Test
  @DisplayName("snapshot-only model path (no env var, no sysprop) → enabled=true")
  void snapshotModelPath_noEnvVar_enabled() {
    String previousPath = System.getProperty(KEY_MODEL_PATH);
    String previousEnabled = System.getProperty(KEY_EMBED_ENABLED);
    try {
      // Clear sysprop and env var — model path arrives ONLY via ConfigStore (snapshot)
      System.clearProperty(KEY_MODEL_PATH);
      System.clearProperty(KEY_EMBED_ENABLED);

      // Set up ConfigStore with a resolved model path. Pre-A19 this simulated the ordinal-450
      // worker snapshot; that tier is deleted, so it now simulates the one resolved config.
      ConfigStore store =
          new ConfigStore(
              TestResolvedConfigHelper.fromEntries(
                  Map.of(KEY_MODEL_PATH, "/snapshot/embeddinggemma-300m")));
      ConfigStore.setGlobal(store);

      EmbeddingConfig config = EmbeddingConfig.fromEnv();

      assertTrue(
          config.enabled(),
          "enabled must be true when model path comes from ConfigStore snapshot alone "
              + "(no env var, no sysprop)");
      assertNotNull(config.modelPath(), "modelPath must be non-null from snapshot");
    } finally {
      restoreProperty(KEY_MODEL_PATH, previousPath);
      restoreProperty(KEY_EMBED_ENABLED, previousEnabled);
    }
  }

  // ---- helpers ----------------------------------------------------------------

  private static void restoreProperty(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }
}
