/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.DeviceFacts;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.server.InferenceCompositionRoot;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.ModelSessionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/** Live probe outcomes must reach the exact plan used by native assembly and recovery. */
final class NoDeviceEncoderPlanTest {
  @AfterEach void reset() throws Exception {
    DevicePlanTestSupport.uninstall();
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
  }

  @ParameterizedTest @ValueSource(ints = {0, 35, 100, 803})
  void deviceFactsVetoEveryEncoderEvenWithExplicitGpuPreferences(int code,
      @TempDir Path dir) throws Exception {
    Map<String, String> detected = new HashMap<>(Map.of(
        "justsearch.gpu.enabled", "true", "justsearch.gpu.layers", "99",
        "justsearch.embed.gpu.enabled", "true", "justsearch.splade.gpu_enabled", "true",
        "justsearch.ner.gpu_enabled", "true", "justsearch.bgem3.gpu_enabled", "true"));
    for (String role : List.of("embed.onnx", "splade", "ner", "rerank")) {
      Path model = Files.createDirectories(dir.resolve(role));
      Files.writeString(model.resolve("model.onnx"), "cpu-model");
      Files.writeString(model.resolve("model_fp16.onnx"), "gpu-model");
      Files.writeString(model.resolve("tokenizer.json"), "{}");
      detected.put("justsearch." + role + ".model_path", model.toString());
    }
    detected.put("justsearch.ai.embed.enabled", "true");
    detected.put("justsearch.splade.enabled", "true");
    detected.put("justsearch.ner.enabled", "true");
    detected.put("justsearch.rerank.enabled", "true");
    ConfigStoreRebuilder.rememberAutoDetected(detected);
    var store = new ConfigStore(ConfigStoreRebuilder.prepare(null));
    DevicePlanTestSupport.install(store,
        io.justsearch.app.services.gpu.DevicePinning.facts(
            new io.justsearch.ort.GpuDriverApiProbe.Result(false, 0, 0, "no usable device",
                io.justsearch.ort.GpuDriverApiProbe.Status.NO_DEVICE, code, List.of()), List.of()),
        DevicePlanTestSupport.DEFAULT_WEIGHTS, Set.of(ChatModelProfile.STANDARD));
    ConfigStoreRebuilder.rebuild(store, null);
    var cfg = store.get();
    assertEquals("NO_GPU", DevicePlanTestSupport.statusProfileOf(cfg).orElseThrow());
    assertFalse(cfg.ai().embedding().gpuEnabled(), "embedding must honour actual no-device facts");
    assertFalse(cfg.ai().splade().gpuEnabled());
    assertFalse(cfg.ai().ner().gpuEnabled());
    assertFalse(cfg.ai().bgeM3().gpuEnabled());
    assertFalse(cfg.ai().reranker().gpuEnabled(), "reranker exemption is only an operator switch");
    assertFalse(cfg.ai().reranker().chunks().gpuEnabled());

    Class<?> projectionType = Class.forName(
        "io.justsearch.indexerworker.server.EncoderConfigurationProjection");
    var from = projectionType.getDeclaredMethod("from", ResolvedConfig.class);
    from.setAccessible(true);
    Class<?> selectionType = Class.forName(
        "io.justsearch.indexerworker.server.GenerationModelSelection");
    var capture = InferenceCompositionRoot.class.getDeclaredMethod("captureIndexPlan",
        projectionType, HardwareProfile.class,
        io.justsearch.configuration.model.InstallContract.class, Path.class, selectionType);
    capture.setAccessible(true);
    Object plan = capture.invoke(null, from.invoke(null, cfg), HardwareProfile.gpuFull(12L << 30),
        null, dir, null);
    var planHardware = plan.getClass().getDeclaredMethod("hardware");
    planHardware.setAccessible(true);
    assertEquals(HardwareProfile.cpuOnly(), planHardware.invoke(plan),
        "the captured native plan must discard stale GPU hardware too");
    var roles = plan.getClass().getDeclaredMethod("roles"); roles.setAccessible(true);
    for (Object row : ((Map<?, ?>) roles.invoke(plan)).values()) {
      var policyMethod = row.getClass().getDeclaredMethod("policy"); policyMethod.setAccessible(true);
      ModelSessionPolicy policy = (ModelSessionPolicy) policyMethod.invoke(row);
      assertNotNull(policy, "the CPU artifact must be selected before native startup");
      assertEquals(ExecutionProvider.CPU, policy.variant().executionProvider());
      assertEquals("model.onnx", policy.variant().modelFile().getFileName().toString());
      assertEquals(0, policy.gpu().arenaCapBytes());
      assertFalse(policy.lifecycle().deferCpuSession());
    }
  }
}
