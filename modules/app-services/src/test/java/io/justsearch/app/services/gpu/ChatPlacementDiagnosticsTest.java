/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelRegistryLoader;
import io.justsearch.configuration.model.ModelVariant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Placement diagnostics describe the allocation and identity without inventing unmet settings. */
final class ChatPlacementDiagnosticsTest {
  @TempDir Path models;

  @AfterEach void restorePlanner() {
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
  }

  @ParameterizedTest(name = "gpu={0}, layers={1}, unmeasured={2}")
  @CsvSource({"false, 12, false", "true, 0, false", "true, 12, true"})
  void onlyACustomGpuReservationReportsUnmeasured(boolean gpu, int layers, boolean diagnostic)
      throws Exception {
    Path custom = Files.writeString(models.resolve("custom.gguf"), "custom fixture");
    var resolution = resolve(custom, gpu, layers);
    assertAll(
        () -> assertEquals(diagnostic, resolution.diagnosticReasons().contains("chat_model_unmeasured"),
            "chat_model_unmeasured requires an actual GPU reservation"),
        () -> assertFalse(resolution.unmetSettings().contains("chat_model_unmeasured"),
            "chat_model_unmeasured is diagnostic, never an unmet user setting"),
        () -> assertEquals(diagnostic, resolution.plan().orElseThrow().chat().footprintMiB() > 0,
            "only the GPU case reserves chat memory"));
  }

  @Test void aManagedArtifactAwaitingVerificationNamesItsPendingIdentity() throws Exception {
    var profile = ChatModelProfile.STANDARD;
    Path model = models.resolve(profile.modelFile());
    Files.createDirectories(model.getParent());
    byte[] bytes = "identity fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(model, bytes);
    String digest = java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var variant = new ModelVariant(profile.modelFile(),
        io.justsearch.configuration.model.ModelPrecision.GGUF,
        io.justsearch.configuration.model.ExecutionProvider.LLAMA_SERVER, digest, bytes.length, null);
    var manifest = new ModelRegistry(2, "pending identity fixture", List.of(new ModelPackage(
        "chat", "fixture", "fixture", "", List.of(variant), List.of(), 0, null)));
    try (var registry = org.mockito.Mockito.mockStatic(ModelRegistryLoader.class,
        org.mockito.Mockito.CALLS_REAL_METHODS)) {
      registry.when(() -> ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json"))
          .thenReturn(manifest);
      var resolution = resolve(model, true, 12);
      assertTrue(resolution.diagnosticReasons().contains("chat_identity_pending"),
          "queued identity verification must report chat_identity_pending");
      assertFalse(resolution.diagnosticReasons().contains("chat_model_unmeasured"),
          "pending managed identity has its own diagnostic");
      assertFalse(resolution.unmetSettings().contains("chat_identity_pending"));
    }
  }

  private GpuProfileResolver.Resolution resolve(Path model, boolean gpu, int layers) {
    ConfigStoreRebuilder.rememberAutoDetected(Map.of(
        EnvRegistry.MODELS_DIR.configKey(), models.toString(),
        EnvRegistry.GPU_ENABLED.configKey(), "true"));
    var settings = new UiSettings();
    settings.setLlmModelPath(model.toString());
    settings.setGpuLayers(layers);
    var cfg = ConfigStoreRebuilder.prepare(settings);
    var facts = gpu ? GpuProfileResolverTest.DevicePlanTest.device(12_282)
        : new DeviceFacts(DeviceFacts.ProbeStatus.RAN_NO_DEVICE, 0, 0, List.of(), Optional.empty());
    return GpuProfileResolver.resolve(cfg, facts, Map.of(), Set.of());
  }
}
