package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InferenceLifecycleManagerPropsInsightsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void updateFromPropsBestEffort_populatesExternalDiagnosticsForMismatchedModel() throws Exception {
    InferenceLifecycleManager manager = newManager(Path.of("models", "configured.gguf"), 4096);
    try {
      setUsingExternalServer(manager, true);
      JsonNode root =
          MAPPER.readTree(
              "{\"model_alias\":\"external\",\"model_path\":\"/models/external.gguf\",\"n_ctx\":2048}");
      invokeUpdateFromPropsBestEffort(manager, root);

      InferenceLifecycleManager.ExternalServerDiagnostics diagnostics = manager.externalServerDiagnostics();
      assertTrue(diagnostics.usingExternalLlamaServer());
      assertTrue(diagnostics.verified());
      assertEquals("external", diagnostics.modelId());
      assertEquals(2048, diagnostics.contextTokens());
      assertTrue(diagnostics.modelMismatch());
      assertTrue(diagnostics.contextTooSmall());
      assertTrue(diagnostics.adoptedAtMs() > 0);
      assertEquals(2048, manager.lastKnownContextTokens());
    } finally {
      manager.close();
    }
  }

  @Test
  void updateFromPropsBestEffort_usesModelPathFilenameAndCaseInsensitiveComparison() throws Exception {
    InferenceLifecycleManager manager = newManager(Path.of("models", "Configured.GGUF"), 4096);
    try {
      setUsingExternalServer(manager, true);
      JsonNode root = MAPPER.readTree("{\"model_path\":\"/tmp/configured.gguf\",\"n_ctx\":8192}");
      invokeUpdateFromPropsBestEffort(manager, root);

      InferenceLifecycleManager.ExternalServerDiagnostics diagnostics = manager.externalServerDiagnostics();
      assertTrue(diagnostics.usingExternalLlamaServer());
      assertTrue(diagnostics.verified());
      assertEquals("configured.gguf", diagnostics.modelId());
      assertEquals(8192, diagnostics.contextTokens());
      assertFalse(diagnostics.modelMismatch());
      assertFalse(diagnostics.contextTooSmall());
      assertEquals(8192, manager.lastKnownContextTokens());
    } finally {
      manager.close();
    }
  }

  @Test
  void updateFromPropsBestEffort_surfacesExpectedVsActualServerBuild(@TempDir Path binDir)
      throws Exception {
    // Tempdoc 682 Item 2: the staging pin marker next to the configured exe is the expected
    // build; /props build_info is the actual. Both must surface via the ILM accessors so the
    // runtime manifest can carry the expected-vs-actual pair.
    Files.writeString(
        binDir.resolve("runtime-version.txt"), "llama.cpp b8571 win-cuda-12.4-x64\n");
    InferenceLifecycleManager manager =
        newManager(binDir.resolve("llama-server.exe"), Path.of("models", "m.gguf"), 4096);
    try {
      assertEquals("b8571", manager.expectedLlamaServerBuild());
      assertEquals(null, manager.actualLlamaServerBuild(), "no /props observed yet");

      invokeUpdateFromPropsBestEffort(
          manager, MAPPER.readTree("{\"build_info\":\"b8600-0abc123\",\"n_ctx\":4096}"));
      assertEquals("b8600", manager.actualLlamaServerBuild());
      assertEquals("b8571", manager.expectedLlamaServerBuild());
    } finally {
      manager.close();
    }
  }

  @Test
  void missingMarkerMeansExpectedUnknown_actualStillRecorded() throws Exception {
    // Externally-staged binary (no runtime-version.txt): expected stays unknown — a supported
    // state, never a failure — while the actual build is still recorded from /props.
    InferenceLifecycleManager manager =
        newManager(Path.of("bin", "llama-server.exe"), Path.of("models", "m.gguf"), 4096);
    try {
      assertEquals(null, manager.expectedLlamaServerBuild());
      invokeUpdateFromPropsBestEffort(
          manager, MAPPER.readTree("{\"build_info\":\"b8571-0abc123\",\"n_ctx\":4096}"));
      assertEquals("b8571", manager.actualLlamaServerBuild());
      assertEquals(null, manager.expectedLlamaServerBuild());
    } finally {
      manager.close();
    }
  }

  private static InferenceLifecycleManager newManager(Path modelPath, int contextSize) {
    return newManager(Path.of("bin", "llama-server.exe"), modelPath, contextSize);
  }

  private static InferenceLifecycleManager newManager(
      Path serverExecutable, Path modelPath, int contextSize) {
    InferenceConfig config =
        new InferenceConfig(
            serverExecutable,
            modelPath,
            null,
            8080,
            contextSize,
            0,
            false);
    return new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), config);
  }

  private static void setUsingExternalServer(InferenceLifecycleManager manager, boolean value) {
    manager.setUsingExternalServerForTest(value);
  }

  private static void invokeUpdateFromPropsBestEffort(
      InferenceLifecycleManager manager, JsonNode root) {
    manager.updateFromPropsBestEffort(root);
  }
}
