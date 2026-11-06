package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SpladeConfig")
class SpladeConfigTest {

  @Nested
  @DisplayName("DISABLED")
  class Disabled {

    @Test
    @DisplayName("isReady returns false")
    void isReadyReturnsFalse() {
      assertFalse(SpladeConfig.DISABLED.isReady());
    }

    @Test
    @DisplayName("has null model path")
    void hasNullModelPath() {
      assertNull(SpladeConfig.DISABLED.modelPath());
    }

    @Test
    @DisplayName("has sensible defaults")
    void hasSensibleDefaults() {
      assertFalse(SpladeConfig.DISABLED.enabled());
      assertEquals(512, SpladeConfig.DISABLED.maxSequenceLength());
      assertFalse(SpladeConfig.DISABLED.gpuEnabled());
    }
  }

  @Nested
  @DisplayName("isReady()")
  class IsReady {

    @Test
    @DisplayName("returns true when enabled and model path set")
    void returnsTrueWhenEnabledAndPathSet() {
      SpladeConfig config =
          new SpladeConfig(true, java.nio.file.Path.of("/some/path"), 256, false, 0, 0, "onnx", "log1p");
      assertTrue(config.isReady());
    }

    @Test
    @DisplayName("returns false when enabled but no model path")
    void returnsFalseWhenEnabledButNoPath() {
      SpladeConfig config = new SpladeConfig(true, null, 256, false, 0, 0, "onnx", "log1p");
      assertFalse(config.isReady());
    }

    @Test
    @DisplayName("returns false when disabled even with model path")
    void returnsFalseWhenDisabledEvenWithPath() {
      SpladeConfig config =
          new SpladeConfig(false, java.nio.file.Path.of("/some/path"), 256, false, 0, 0, "onnx", "log1p");
      assertFalse(config.isReady());
    }
  }

  @Nested
  @DisplayName("queryMode")
  class QueryMode {

    @Test
    @DisplayName("DISABLED defaults to onnx query mode")
    void disabledDefaultsToOnnx() {
      assertEquals("onnx", SpladeConfig.DISABLED.queryMode());
      assertFalse(SpladeConfig.DISABLED.isIdfQueryMode());
    }

    @Test
    @DisplayName("isIdfQueryMode returns true for idf")
    void idfModeDetected() {
      SpladeConfig config =
          new SpladeConfig(true, java.nio.file.Path.of("/path"), 256, false, 0, 0, "idf", "log1p");
      assertTrue(config.isIdfQueryMode());
    }

    @Test
    @DisplayName("isIdfQueryMode is case-insensitive")
    void idfModeCaseInsensitive() {
      SpladeConfig config =
          new SpladeConfig(true, java.nio.file.Path.of("/path"), 256, false, 0, 0, "IDF", "log1p");
      assertTrue(config.isIdfQueryMode());
    }

    @Test
    @DisplayName("isIdfQueryMode returns false for onnx")
    void onnxModeNotIdf() {
      SpladeConfig config =
          new SpladeConfig(true, java.nio.file.Path.of("/path"), 256, false, 0, 0, "onnx", "log1p");
      assertFalse(config.isIdfQueryMode());
    }
  }

  @Nested
  @DisplayName("activation")
  class Activation {

    @Test
    @DisplayName("DISABLED defaults to log1p activation")
    void disabledDefaultsToLog1p() {
      assertEquals("log1p", SpladeConfig.DISABLED.activation());
      assertFalse(SpladeConfig.DISABLED.isDoubleLogActivation());
    }

    @Test
    @DisplayName("isDoubleLogActivation returns true for double_log1p")
    void doubleLogDetected() {
      SpladeConfig config =
          new SpladeConfig(
              true, java.nio.file.Path.of("/path"), 256, false, 0, 0, "onnx", "double_log1p");
      assertTrue(config.isDoubleLogActivation());
    }

    @Test
    @DisplayName("isDoubleLogActivation is case-insensitive")
    void doubleLogCaseInsensitive() {
      SpladeConfig config =
          new SpladeConfig(
              true, java.nio.file.Path.of("/path"), 256, false, 0, 0, "onnx", "DOUBLE_LOG1P");
      assertTrue(config.isDoubleLogActivation());
    }

    @Test
    @DisplayName("isDoubleLogActivation returns false for log1p")
    void log1pNotDouble() {
      SpladeConfig config =
          new SpladeConfig(
              true, java.nio.file.Path.of("/path"), 256, false, 0, 0, "onnx", "log1p");
      assertFalse(config.isDoubleLogActivation());
    }
  }

  // E4d supersedes explicit-path auto-enable: installed assets alone are not demand.
  @Test
  @DisplayName("explicit model path alone leaves inference off")
  void explicitModelPath_doesNotEnable() {
    String prevPath = System.getProperty("justsearch.splade.model_path");
    String prevEnabled = System.getProperty("justsearch.splade.enabled");
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.splade.model_path", "/nonexistent/splade-model");
      System.clearProperty("justsearch.splade.enabled");
      TestResolvedConfigHelper.storeFromEnvironment();
      SpladeConfig config = SpladeConfig.fromEnv();
      assertFalse(config.enabled(), "D1: installed files and explicit model paths are not demand");
      assertFalse(config.isReady());
      assertEquals(java.nio.file.Path.of("/nonexistent/splade-model"), config.modelPath());
    } finally {
      restoreProperty("justsearch.splade.model_path", prevPath);
      restoreProperty("justsearch.splade.enabled", prevEnabled);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  /** Defensive: explicit-disable must still win even when an explicit path is present. */
  @Test
  @DisplayName("explicit model path + explicit disable → isReady=false (opt-out preserved)")
  void explicitModelPath_explicitDisable_wins() {
    String prevPath = System.getProperty("justsearch.splade.model_path");
    String prevEnabled = System.getProperty("justsearch.splade.enabled");
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.splade.model_path", "/nonexistent/splade-model");
      System.setProperty("justsearch.splade.enabled", "false");
      TestResolvedConfigHelper.storeFromEnvironment();

      SpladeConfig config = SpladeConfig.fromEnv();

      assertFalse(
          config.enabled(),
          "explicit-disable must override the auto-enable fallback (374 alpha.17 R4 risk row)");
    } finally {
      restoreProperty("justsearch.splade.model_path", prevPath);
      restoreProperty("justsearch.splade.enabled", prevEnabled);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  // ==================== ConfigStore precedence ====================

  @Test
  @DisplayName("fromEnv reads settings from ConfigStore when available")
  void fromEnvReadsFromConfigStore() {
    ConfigStore prevStore = ConfigStore.globalOrNull();
    String oldEnabled = System.getProperty("justsearch.splade.enabled");
    String oldGpu = System.getProperty("justsearch.splade.gpu_enabled");
    String oldSeqLen = System.getProperty("justsearch.splade.max_seq_len");
    String oldQueryMode = System.getProperty("justsearch.splade.query_mode");
    try {
      ConfigStore store =
          new ConfigStore(
              TestResolvedConfigHelper.fromEntries(
                  Map.of(
                      "justsearch.splade.gpu_enabled", "true",
                      "justsearch.splade.max_seq_len", "256",
                      "justsearch.splade.query_mode", "idf")));
      ConfigStore.setGlobal(store);

      // Enable SPLADE via sysprop (enabled flag uses EnvRegistry, not ConfigStore)
      System.setProperty("justsearch.splade.enabled", "true");
      // Clear sysprops for keys we're testing via ConfigStore
      System.clearProperty("justsearch.splade.gpu_enabled");
      System.clearProperty("justsearch.splade.max_seq_len");
      System.clearProperty("justsearch.splade.query_mode");

      SpladeConfig config = SpladeConfig.fromEnv();
      assertTrue(config.gpuEnabled(), "GPU enabled should be read from ConfigStore");
      assertEquals(256, config.maxSequenceLength(), "Max seq len should be read from ConfigStore");
      assertTrue(config.isIdfQueryMode(), "Query mode should be read from ConfigStore");
    } finally {
      restoreProperty("justsearch.splade.enabled", oldEnabled);
      restoreProperty("justsearch.splade.gpu_enabled", oldGpu);
      restoreProperty("justsearch.splade.max_seq_len", oldSeqLen);
      restoreProperty("justsearch.splade.query_mode", oldQueryMode);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  private static void restoreProperty(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }
}
