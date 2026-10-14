/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Saved paths reach the real settings rebuild without a launcher profile overriding them. */
final class StoredChatBudgetTest {
  private static final Map<EncoderRole, Double> WEIGHTS = Map.of(
      EncoderRole.EMBEDDING, 598.3, EncoderRole.SPLADE, 474.2,
      EncoderRole.NER, 257.2, EncoderRole.RERANKER, 600.3);
  @TempDir Path models;

  @AfterEach void restore() {
    ConfigStoreRebuilder.rememberDevicePlanner(null);
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
  }

  @ParameterizedTest(name = "aSmallCustomGpuModelKeepsTheEncoderPlan({0})")
  @ValueSource(ints = {12282, 24564})
  void aSmallCustomGpuModelKeepsTheEncoderPlan(int mib) throws Exception {
    var cfg = rebuild(mib, customModel(), 99);
    var resolution = ConfigStoreRebuilder.planOf(cfg).orElseThrow();
    assertEncodersPlanned(cfg);
    var chat = resolution.plan().orElseThrow().chat();
    double expected = 1024 + GpuArenaPlan.UNMEASURED_CHAT_OVERHEAD_MIB
        + GpuArenaPlan.CHAT_KV_MIB_PER_1K_TOKENS * cfg.ai().contextSize() / 1024.0;
    assertEquals(expected, chat.footprintMiB(), 0.001,
        "reserve the file and configured KV, never the whole device");
    assertTrue(chat.extrapolated());
    assertTrue(resolution.diagnosticReasons().contains("chat_model_unmeasured"));
    assertFalse(resolution.unmetSettings().contains("encoder_memory"));
  }

  @Test void cpuChatDoesNotSpendDeviceMemory() throws Exception {
    var cfg = rebuild(24564, customModel(), 0);
    assertEncodersPlanned(cfg);
    assertEquals(0, ConfigStoreRebuilder.planOf(cfg).orElseThrow().plan().orElseThrow()
        .chat().footprintMiB(), "CPU chat has no device allocation");
  }

  @Test void aMissingSavedChatPathKeepsEncodersPlannedAndNamesTheFile() {
    Path absent = models.resolve("missing-saved-chat.gguf");
    var cfg = rebuild(12282, absent, 99);
    assertEncodersPlanned(cfg);
    var resolution = ConfigStoreRebuilder.planOf(cfg).orElseThrow();
    assertEquals(0, resolution.plan().orElseThrow().chat().footprintMiB());
    assertTrue(resolution.diagnosticReasons().stream().anyMatch(reason ->
        reason.startsWith("chat_model_missing:") && reason.contains(absent.getFileName().toString())),
        "missing chat availability must identify the missing file");
    assertFalse(resolution.unmetSettings().contains("encoder_memory"));
  }

  @Test void aMissingRelativeManagedPathNamesTheUnavailableFile() {
    Path missing = models.resolve(io.justsearch.configuration.model.ChatModelProfile.STANDARD.modelFile());
    Path relative = models.getFileName().resolve(missing.getFileName());
    String previousBase = System.getProperty(EnvRegistry.DATA_DIR.sysProp());
    io.justsearch.configuration.resolved.ResolvedConfig cfg;
    try {
      System.setProperty(EnvRegistry.DATA_DIR.sysProp(), models.getParent().toString());
      cfg = rebuild(12282, relative, 99);
    } finally {
      if (previousBase == null) System.clearProperty(EnvRegistry.DATA_DIR.sysProp());
      else System.setProperty(EnvRegistry.DATA_DIR.sysProp(), previousBase);
    }
    assertEncodersPlanned(cfg);
    var resolution = ConfigStoreRebuilder.planOf(cfg).orElseThrow();
    assertEquals(0, resolution.plan().orElseThrow().chat().footprintMiB());
    assertTrue(resolution.diagnosticReasons().contains("chat_model_missing:" + missing),
        "managed relative identity must not hide an unavailable saved file");
    assertFalse(resolution.unmetSettings().contains("encoder_memory"));
  }

  @Test void bootSettingsRebuildDoesNotStreamTheInstalledModel() throws Exception {
    byte[] bytes = new byte[64 * 1024];
    var profile = io.justsearch.configuration.model.ChatModelProfile.STANDARD;
    Path target = models.resolve(profile.modelFile());
    Path partial = io.justsearch.configuration.model.InstallPlanner.partialPathFor(target);
    Files.write(partial, bytes);
    String digest = java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var download = new io.justsearch.configuration.model.InstallPlan.PlannedDownload(
        "chat", "https://example.invalid/fixture", profile.modelFile(), digest, bytes.length, true);
    Class<?> placement = Class.forName("io.justsearch.app.services.ai.install.PlacementStage");
    var constructor = placement.getDeclaredConstructor(Path.class);
    constructor.setAccessible(true);
    var place = placement.getDeclaredMethod("place",
        io.justsearch.configuration.model.InstallPlan.PlannedDownload.class);
    place.setAccessible(true);
    assertNull(place.invoke(constructor.newInstance(models), download),
        "the real installer placement must verify and promote the local partial");
    Path model = target.toRealPath();
    var variant = new io.justsearch.configuration.model.ModelVariant(model.getFileName().toString(),
        io.justsearch.configuration.model.ModelPrecision.GGUF,
        io.justsearch.configuration.model.ExecutionProvider.LLAMA_SERVER, digest, bytes.length, null);
    var manifest = new io.justsearch.configuration.model.ModelRegistry(2, "boot identity fixture",
        java.util.List.of(new io.justsearch.configuration.model.ModelPackage("chat", "fixture",
            "fixture", "", java.util.List.of(variant), java.util.List.of(), 0, null)));
    var consumed = new java.util.concurrent.atomic.AtomicLong();
    try (var registry = org.mockito.Mockito.mockStatic(
        io.justsearch.configuration.model.ModelRegistryLoader.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        var _ = org.mockito.Mockito.mockStatic(Files.class, invocation -> {
          if (invocation.getMethod().getName().equals("newInputStream")
              && model.equals(invocation.getArgument(0))) {
            return new java.io.ByteArrayInputStream(bytes) {
              @Override public synchronized int read(byte[] buffer, int offset, int length) {
                int count = super.read(buffer, offset, length);
                if (count > 0) consumed.addAndGet(count);
                return count;
              }
            };
          }
          return invocation.callRealMethod();
        })) {
      registry.when(() -> io.justsearch.configuration.model.ModelRegistryLoader
          .loadFromClasspath("ai/model-registry.v2.json")).thenReturn(manifest);
      var cfg = rebuild(12282, model, 99);
      assertTrue(consumed.get() <= 4096,
          "the synchronous settings rebuild must not hash model content: " + consumed.get());
      assertEncodersPlanned(cfg);
      var chat = ConfigStoreRebuilder.planOf(cfg).orElseThrow().plan().orElseThrow().chat();
      assertEquals(profile, chat.model().orElseThrow(), "verified installation keeps its measured model");
      assertEquals(6549, chat.footprintMiB(), 0.001);
    }
  }

  private Path customModel() throws Exception {
    Path file = models.resolve("custom.gguf");
    try (var output = new java.io.RandomAccessFile(file.toFile(), "rw")) {
      output.setLength(1024L * 1024 * 1024);
    }
    assertTrue(Files.isRegularFile(file));
    return file;
  }

  private io.justsearch.configuration.resolved.ResolvedConfig rebuild(int mib, Path model, int layers) {
    ConfigStoreRebuilder.rememberAutoDetected(Map.of(
        EnvRegistry.MODELS_DIR.configKey(), models.toString(),
        EnvRegistry.DATA_DIR.configKey(), models.getParent().toString(),
        EnvRegistry.GPU_ENABLED.configKey(), "true", EnvRegistry.GPU_LAYERS.configKey(), "99"));
    ConfigStoreRebuilder.rememberDevicePlanner(GpuProfileResolver.planner(
        () -> GpuProfileResolverTest.DevicePlanTest.device(mib), ignored -> WEIGHTS));
    var settings = new UiSettings();
    settings.setLlmModelPath(model.toString());
    settings.setGpuLayers(layers);
    var store = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
    ConfigStoreRebuilder.rebuild(store, settings);
    return store.get();
  }

  private static void assertEncodersPlanned(io.justsearch.configuration.resolved.ResolvedConfig cfg) {
    assertTrue(ConfigStoreRebuilder.planOf(cfg).orElseThrow().plan().orElseThrow().planned(),
        "the remaining GPU budget must continue to plan encoders");
    assertAll(() -> assertTrue(cfg.ai().embedding().gpuEnabled()),
        () -> assertTrue(cfg.ai().splade().gpuEnabled()),
        () -> assertTrue(cfg.ai().ner().gpuEnabled()),
        () -> assertTrue(cfg.ai().reranker().gpuEnabled()));
  }
}
