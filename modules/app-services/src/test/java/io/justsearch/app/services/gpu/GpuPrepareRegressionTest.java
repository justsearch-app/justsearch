/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.ort.EncoderRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class GpuPrepareRegressionTest {
  private static final Map<EncoderRole, Double> WEIGHTS = Map.of(
      EncoderRole.EMBEDDING, 598.3, EncoderRole.SPLADE, 474.2,
      EncoderRole.NER, 257.2, EncoderRole.RERANKER, 600.3);
  @TempDir Path dir;
  private org.mockito.MockedStatic<io.justsearch.configuration.model.ModelRegistryLoader> registry;

  @AfterEach void cleanup() {
    if (registry != null) registry.close();
    System.clearProperty(EnvRegistry.LLM_MODEL_PATH.sysProp());
    ConfigStoreRebuilder.rememberDevicePlanner(null);
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
    System.clearProperty(EnvRegistry.LLM_PROMPT_CACHE_MIB.sysProp());
    for (var key : new EnvRegistry[] {EnvRegistry.EMBED_GPU_DEVICE_ID,
        EnvRegistry.SPLADE_GPU_DEVICE_ID, EnvRegistry.NER_GPU_DEVICE_ID,
        EnvRegistry.RERANK_GPU_DEVICE_ID, EnvRegistry.BGE_M3_GPU_DEVICE_ID,
        EnvRegistry.BGE_M3_GPU_ENABLED}) System.clearProperty(key.sysProp());
  }

  @ParameterizedTest(name = "installedProfilePathKeepsItsMeasuredPlan({0})")
  @CsvSource({"16376, STANDARD, 7675, true", "12282, STANDARD, 6549, false",
      "8178, COMPACT, 3533, false"})
  void installedProfilePathKeepsItsMeasuredPlan(double mib, ChatModelProfile profile,
      double footprint, boolean projector) throws Exception {
    Path file = dir.resolve(profile.modelFile());
    Files.createDirectories(file.getParent());
    Files.writeString(file, "installed profile artifact");
    Files.writeString(dir.resolve(profile.mmprojFile()), "installed projector");
    registerFixtureArtifacts();
    preparePlanner(mib, Map.of(EnvRegistry.MODELS_DIR.configKey(), dir.toString()));
    UiSettings settings = new UiSettings();
    settings.setLlmModelPath(file.toAbsolutePath().toString());
    ResolvedConfig prepared = ConfigStoreRebuilder.prepare(settings);
    var resolution = ConfigStoreRebuilder.planOf(prepared).orElseThrow();
    var plan = resolution.plan().orElseThrow();
    assertTrue(plan.planned(), "the installer's absolute path identifies the shipped profile file");
    assertEquals(footprint, plan.chat().footprintMiB(), 0.001);
    assertEquals(projector, plan.chat().projectorOnGpu());
    assertEquals(profile, plan.chat().model().orElseThrow());
    assertFalse(resolution.unmetSettings().contains("encoder_memory"));
    assertFalse(prepared.ai().gpuMemoryPlan().isBlank());
  }

  @Test void customChatDisablesEveryAutomaticGpuRoleThroughPrepare() throws Exception {
    Path file = Files.writeString(dir.resolve("custom.gguf"), "unmeasured architecture");
    preparePlanner(12_282, Map.of(EnvRegistry.MODELS_DIR.configKey(), dir.toString()));
    var weights = new java.util.EnumMap<>(WEIGHTS);
    weights.put(EncoderRole.BGE_M3, 1_000.0);
    ConfigStoreRebuilder.rememberDevicePlanner(GpuProfileResolver.planner(
        () -> GpuProfileResolverTest.DevicePlanTest.device(12_282), ignored -> weights));
    UiSettings settings = new UiSettings();
    settings.setLlmModelPath(file.toString());
    var cfg = ConfigStoreRebuilder.prepare(settings);
    assertAll("every GPU-enabled key must resolve through the registry",
        () -> assertFalse(cfg.ai().embedding().gpuEnabled()),
        () -> assertFalse(cfg.ai().splade().gpuEnabled()),
        () -> assertFalse(cfg.ai().ner().gpuEnabled()),
        () -> assertFalse(cfg.ai().reranker().gpuEnabled()),
        () -> assertFalse(cfg.ai().bgeM3().gpuEnabled()));
  }

  @ParameterizedTest(name = "aDeviceOverrideLeavesTheOtherRolesAndChatPlanned({0})")
  @CsvSource({"EMBEDDING, justsearch.embed.gpu.device_id", "SPLADE, justsearch.splade.gpu_device_id",
      "NER, justsearch.ner.gpu_device_id", "RERANKER, justsearch.rerank.gpu.device_id",
      "BGE_M3, justsearch.bgem3.gpu_device_id"})
  void aDeviceOverrideLeavesTheOtherRolesAndChatPlanned(EncoderRole role, String key)
      throws Exception {
    Files.writeString(dir.resolve(ChatModelProfile.STANDARD.modelFile()), "standard");
    System.setProperty(key, "1");
    preparePlanner(12_282, Map.of(EnvRegistry.MODELS_DIR.configKey(), dir.toString()));
    if (role == EncoderRole.BGE_M3) {
      System.setProperty(EnvRegistry.BGE_M3_GPU_ENABLED.sysProp(), "true");
      var weights = new java.util.EnumMap<>(WEIGHTS);
      weights.put(EncoderRole.BGE_M3, 1_000.0);
      ConfigStoreRebuilder.rememberDevicePlanner(GpuProfileResolver.planner(
          () -> GpuProfileResolverTest.DevicePlanTest.device(12_282), ignored -> weights));
    }
    var cfg = ConfigStoreRebuilder.prepare(new UiSettings());
    var resolution = ConfigStoreRebuilder.planOf(cfg).orElseThrow();
    var plan = resolution.plan().orElseThrow();
    assertTrue(plan.planned(), "a role on another adapter must not remove the shared plan");
    assertEquals("p12", cfg.ai().gpuMemoryPlan());
    assertEquals(32_768, cfg.ai().contextSize());
    assertTrue(cfg.ai().embedding().gpuEnabled());
    assertTrue(cfg.ai().reranker().gpuEnabled());
    assertTrue(plan.role(role).isEmpty(), "the explicitly moved role uses unplanned behavior");
    assertFalse(resolution.unmetSettings().contains("encoder_memory"));
    assertTrue(resolution.unmetSettings().contains("encoder_device_conflict"));
    var variant = io.justsearch.configuration.model.VariantSelection.optimal(
        dir.resolve("explicit-fp32.onnx"), io.justsearch.configuration.model.ModelPrecision.FP32,
        io.justsearch.configuration.model.ExecutionProvider.CUDA);
    var hardware = new io.justsearch.configuration.model.HardwareProfile(
        true, true, 12_282L * 1024 * 1024);
    var policy = io.justsearch.ort.ModelSessionPolicyResolver.resolve(role, cfg, hardware, variant);
    assertFalse(policy.gpu().plannedProfile(), "the other device retains its unplanned policy");
    assertEquals(1, policy.gpu().cudaDeviceId());
    assertTrue(policy.gpu().arenaCapBytes() > 0, "unplanned FP32 GPU behavior is preserved");
    for (EncoderRole other : WEIGHTS.keySet()) {
      if (other != role) assertTrue(plan.role(other).orElseThrow().capMiB() > 0);
    }
  }

  @Test void aForeignRoleWithoutAWeightObservationKeepsItsUnplannedPolicy() {
    System.setProperty(EnvRegistry.EMBED_GPU_DEVICE_ID.sysProp(), "1");
    preparePlanner(12_282, Map.of());
    var observed = new java.util.EnumMap<>(WEIGHTS);
    observed.remove(EncoderRole.EMBEDDING);
    ConfigStoreRebuilder.rememberDevicePlanner(GpuProfileResolver.planner(
        () -> GpuProfileResolverTest.DevicePlanTest.device(12_282), ignored -> observed));
    var cfg = ConfigStoreRebuilder.prepare(new UiSettings());
    var variant = io.justsearch.configuration.model.VariantSelection.optimal(
        dir.resolve("foreign-fp32.onnx"), io.justsearch.configuration.model.ModelPrecision.FP32,
        io.justsearch.configuration.model.ExecutionProvider.CUDA);
    var policy = io.justsearch.ort.ModelSessionPolicyResolver.resolve(EncoderRole.EMBEDDING, cfg,
        io.justsearch.configuration.model.HardwareProfile.gpuFull(12_282L * 1024 * 1024), variant);
    assertFalse(policy.gpu().plannedProfile(), "a missing weight observation cannot change explicit device ownership");
    assertTrue(policy.gpu().arenaCapBytes() > 0);
    assertEquals(1, policy.gpu().cudaDeviceId());
  }

  @Test void aChatOnlyPlannerFailureDoesNotAbortConfigurationPreparation() {
    preparePlanner(12_282, Map.of());
    System.setProperty(EnvRegistry.LLM_PROMPT_CACHE_MIB.sysProp(), "invalid");
    var cfg = assertDoesNotThrow(() -> ConfigStoreRebuilder.prepare(new UiSettings()),
        "a chat-cache error must degrade planning, not reject the entire configuration");
    assertTrue(cfg.ai().gpuMemoryPlan().isBlank());
    assertTrue(ConfigStoreRebuilder.planOf(cfg).orElseThrow().unmetSettings()
        .contains("gpu_plan_unavailable"), "the degraded plan must carry a visible reason");
  }

  @Test void unchangedPlanningDoesNotRepeatInfoLogs() {
    var context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
    var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.setContext(context);
    appender.start();
    root.addAppender(appender);
    var inferenceLog = context.getLogger(io.justsearch.app.inference.InferenceConfig.class);
    var oldLevel = inferenceLog.getLevel();
    inferenceLog.setLevel(ch.qos.logback.classic.Level.INFO);
    try {
      preparePlanner(12_281, Map.of());
      ConfigStoreRebuilder.prepare(new UiSettings());
      ConfigStoreRebuilder.prepare(new UiSettings());
      long infos = appender.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO)
          .filter(event -> event.getLoggerName().equals(io.justsearch.app.inference.InferenceConfig.class.getName())
              || event.getLoggerName().equals(ConfigStoreRebuilder.class.getName())).count();
      assertEquals(1, infos, "identical planning must publish one INFO result, without repeated launch messages");
    } finally {
      inferenceLog.setLevel(oldLevel);
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test void anOperatorLockedProfileIsBudgetedAsTheActualLaunch() throws Exception {
    Files.writeString(dir.resolve(ChatModelProfile.STANDARD.modelFile()), "standard fixture");
    Files.createDirectories(dir.resolve("compact"));
    Files.writeString(dir.resolve(ChatModelProfile.COMPACT.modelFile()), "compact fixture");
    registerFixtureArtifacts();
    System.setProperty(EnvRegistry.LLM_MODEL_PATH.sysProp(),
        dir.resolve(ChatModelProfile.STANDARD.modelFile()).toString());
    preparePlanner(8_178, Map.of(EnvRegistry.MODELS_DIR.configKey(), dir.toString()));
    var cfg = ConfigStoreRebuilder.prepare(new UiSettings());
    var plan = ConfigStoreRebuilder.planOf(cfg).orElseThrow().plan().orElseThrow();
    var launch = io.justsearch.app.inference.InferenceConfig.fromResolvedConfig(cfg, null);
    assertEquals("standard", launch.chatProfileId(), "the locked path identifies its actual artifact");
    assertEquals(ChatModelProfile.STANDARD, plan.chat().model().orElseThrow(),
        "the planner must not budget for a compact sibling that the locked launch will not use");
    assertFalse(cfg.ai().embedding().gpuEnabled(), "an oversized locked chat keeps automatic encoders on CPU");
  }

  @Test void anInstallerStandardPathFollowsTheCompactDevicePlan() throws Exception {
    Files.writeString(dir.resolve(ChatModelProfile.STANDARD.modelFile()), "standard fixture");
    Files.createDirectories(dir.resolve("compact"));
    Files.writeString(dir.resolve(ChatModelProfile.COMPACT.modelFile()), "compact fixture");
    registerFixtureArtifacts();
    preparePlanner(8_178, Map.of(EnvRegistry.MODELS_DIR.configKey(), dir.toString()));
    var settings = new UiSettings();
    settings.setLlmModelPath(dir.resolve(ChatModelProfile.STANDARD.modelFile()).toString());
    var cfg = ConfigStoreRebuilder.prepare(settings);
    var plan = ConfigStoreRebuilder.planOf(cfg).orElseThrow().plan().orElseThrow();
    assertTrue(plan.planned());
    assertEquals(ChatModelProfile.COMPACT, plan.chat().model().orElseThrow());
    assertEquals(3533, plan.chat().footprintMiB(), 0.001);
    var launch = io.justsearch.app.inference.InferenceConfig.fromResolvedConfig(cfg, null);
    assertEquals("compact", launch.chatProfileId());
    assertEquals(dir.resolve(ChatModelProfile.COMPACT.modelFile()), launch.modelPath());
  }

  @Test void aChangedArtifactAtTheShippedPathIsStillCustom() throws Exception {
    Path file = Files.writeString(dir.resolve(ChatModelProfile.STANDARD.modelFile()), "manifest fixture");
    registerFixtureArtifacts();
    Files.writeString(file, "replaced fixture");
    preparePlanner(12_282, Map.of(EnvRegistry.MODELS_DIR.configKey(), dir.toString()));
    var settings = new UiSettings();
    settings.setLlmModelPath(file.toString());
    var cfg = ConfigStoreRebuilder.prepare(settings);
    var plan = ConfigStoreRebuilder.planOf(cfg).orElseThrow().plan().orElseThrow();
    assertFalse(plan.planned(), "a filename cannot lend a measurement to different content");
    assertTrue(plan.chat().extrapolated());
  }

  private void registerFixtureArtifacts() throws Exception {
    var packages = new java.util.ArrayList<io.justsearch.configuration.model.ModelPackage>();
    for (var profile : ChatModelProfile.values()) {
      Path file = dir.resolve(profile.modelFile());
      if (!Files.isRegularFile(file)) continue;
      String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(Files.readAllBytes(file)));
      var variant = new io.justsearch.configuration.model.ModelVariant(file.getFileName().toString(),
          io.justsearch.configuration.model.ModelPrecision.GGUF,
          io.justsearch.configuration.model.ExecutionProvider.LLAMA_SERVER, digest, Files.size(file), null);
      packages.add(new io.justsearch.configuration.model.ModelPackage(profile.id(), "fixture", "fixture",
          dir.relativize(file.getParent()).toString(), java.util.List.of(variant), java.util.List.of(), 0, null));
    }
    var manifest = new io.justsearch.configuration.model.ModelRegistry(2, "isolated test identities", packages);
    registry = org.mockito.Mockito.mockStatic(io.justsearch.configuration.model.ModelRegistryLoader.class,
        org.mockito.Mockito.CALLS_REAL_METHODS);
    registry.when(() -> io.justsearch.configuration.model.ModelRegistryLoader
        .loadFromClasspath("ai/model-registry.v2.json")).thenReturn(manifest);
  }

  private void preparePlanner(double mib, Map<String, String> values) {
    var auto = new java.util.HashMap<>(values);
    auto.put(EnvRegistry.GPU_ENABLED.configKey(), "true");
    auto.put(EnvRegistry.GPU_LAYERS.configKey(), "99");
    ConfigStoreRebuilder.rememberAutoDetected(auto);
    ConfigStoreRebuilder.rememberDevicePlanner(GpuProfileResolver.planner(
        () -> GpuProfileResolverTest.DevicePlanTest.device(mib), ignored -> WEIGHTS));
  }
}
