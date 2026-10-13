/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.scheduling.BulkWorkPolicy;
import io.justsearch.ort.EncoderRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GpuBudgetAlignmentTest {
  private static final Map<EncoderRole, Double> WEIGHTS = Map.of(
      EncoderRole.EMBEDDING, 598.3, EncoderRole.SPLADE, 474.2,
      EncoderRole.NER, 257.2, EncoderRole.RERANKER, 600.3);

  @Test
  void customOnlyGpuModelStillReservesChatMemory(@TempDir Path dir) throws Exception {
    Path model = Files.writeString(dir.resolve("custom.gguf"), "custom");
    var cfg = config(Map.of("justsearch.models.dir", dir.resolve("empty").toString(),
        "justsearch.llm.model_path", model.toString(), "justsearch.gpu.layers", "99"));
    assertEquals(model, InferenceConfig.fromResolvedConfig(cfg, dir).modelPath());
    var plan = GpuProfileResolver.planner(() -> device(), ignored -> WEIGHTS).plan(cfg)
        .plan().orElseThrow();
    assertTrue(plan.chat().footprintMiB() > 0, "the launchable custom artifact must reserve chat memory");
    assertTrue(plan.chat().extrapolated(), "a custom artifact cannot claim a measured footprint");
  }

  @Test
  void customOverrideDoesNotBorrowInstalledModelMeasurement(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve(ChatModelProfile.STANDARD.modelFile()), "standard");
    Path model = Files.writeString(dir.resolve("custom.gguf"), "custom");
    var cfg = config(Map.of("justsearch.models.dir", dir.toString(),
        "justsearch.llm.model_path", model.toString()));
    var plan = GpuProfileResolver.planner(() -> device(), ignored -> WEIGHTS).plan(cfg)
        .plan().orElseThrow();
    assertEquals(model, InferenceConfig.fromResolvedConfig(cfg, dir).modelPath());
    assertTrue(plan.chat().extrapolated(), "an installed sibling is not the launched model");
  }

  @Test
  void higherPrecisionKvReducesEncoderBudget() {
    var standard = GpuProfileResolver.resolve(config(Map.of()), device(), WEIGHTS,
        Set.of(ChatModelProfile.STANDARD)).plan().orElseThrow();
    var f16 = GpuProfileResolver.resolve(config(Map.of("justsearch.llm.kv_type", "f16")),
        device(), WEIGHTS, Set.of(ChatModelProfile.STANDARD)).plan().orElseThrow();
    assertTrue(f16.chat().footprintMiB() > standard.chat().footprintMiB(),
        "f16 KV must reserve more than the measured quantized cache");
    assertTrue(f16.chat().extrapolated());
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "splitDevicePlacementNeverAppliesAnotherAdaptersReducedCaps({0})")
  @org.junit.jupiter.params.provider.CsvSource({
      "justsearch.embed.gpu.device_id, EMBEDDING", "justsearch.splade.gpu_device_id, SPLADE",
      "justsearch.ner.gpu_device_id, NER", "justsearch.rerank.gpu.device_id, RERANKER",
      "justsearch.bgem3.gpu_device_id, BGE_M3"})
  void splitDevicePlacementNeverAppliesAnotherAdaptersReducedCaps(String key, EncoderRole role) {
    var cfg = config(Map.of(key, " 1 ", "justsearch.bgem3.gpu_enabled",
        Boolean.toString(role == EncoderRole.BGE_M3)));
    var weights = new java.util.EnumMap<>(WEIGHTS);
    weights.putIfAbsent(role, 600.0);
    var other = new CudaDevice(1, java.util.Optional.empty(), java.util.Optional.empty(),
        java.util.Optional.empty(), 8L * 1024 * 1024 * 1024);
    var pinned = device().pinned().orElseThrow();
    var devices = new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080,
        java.util.List.of(pinned, other), java.util.Optional.of(pinned));
    var resolution = GpuProfileResolver.resolve(cfg, devices, weights, Set.of(ChatModelProfile.STANDARD));
    var values = GpuProfileResolver.contributions(resolution, true);
    assertFalse(values.containsKey("justsearch.embed.gpu_mem_mb"),
        "device 1 has no matching packing plan and cannot receive device 0's reduced cap");
    assertTrue(resolution.unmetSettings().contains("encoder_memory"));
  }

  @Test
  void explicitGpuEncodersKeepPressureProtectionWithMasterOff() {
    var cfg = config(Map.of("justsearch.gpu.enabled", "false", "justsearch.embed.gpu_enabled", "true"));
    var plan = GpuProfileResolver.resolve(cfg, device(), Map.of(EncoderRole.EMBEDDING, 598.3), Set.of());
    assertTrue(BulkWorkPolicy.decide(plan.gateProfile(), false, true, false, false).held());
    assertTrue(BulkWorkPolicy.decide(plan.gateProfile(), false, false, true, false).held());
  }

  private static DeviceFacts device() {
    return GpuProfileResolverTest.DevicePlanTest.device(12_282);
  }

  private static ResolvedConfig config(Map<String, String> values) {
    var builder = ResolvedConfig.builder();
    values.forEach(builder::putSettings);
    return builder.build();
  }
}
