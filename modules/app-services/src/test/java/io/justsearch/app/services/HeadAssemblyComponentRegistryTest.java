/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.services.bootstrap.CapabilityGraph;
import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentSpec.ComposeCapability;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HeadAssemblyComponentRegistryTest {
  @TempDir Path tempDir;

  @Test
  void generativeSpecNamesOnlyOwnedConfigurationDeclarations() {
    var spec = HeadAssembly.generativeSpec();
    assertEquals("generative", spec.name());
    assertFalse(spec.essential());
    assertEquals(ComposeCapability.IN_PLACE, spec.composeCapability());
    assertEquals(Duration.ofSeconds(180), spec.startDeadline());
    assertEquals(2, spec.recoveryBudget());
    assertEquals(Set.of(
        EnvRegistry.LLM_ENABLED.configKey(),
        EnvRegistry.AI_DISABLED.configKey(),
        EnvRegistry.LITE_MODE.configKey(),
        EnvRegistry.SERVER_EXE.configKey(),
        EnvRegistry.LLM_MODEL_PATH.configKey(),
        EnvRegistry.MMPROJ_MODEL.configKey(),
        EnvRegistry.SERVER_PORT.configKey(),
        EnvRegistry.CONTEXT_SIZE.configKey(),
        EnvRegistry.GPU_LAYERS.configKey(),
        EnvRegistry.POLICY_GPU_ACCELERATION_ENABLED.configKey(),
        EnvRegistry.LLM_SLOTS.configKey(),
        EnvRegistry.LLM_KV_TYPE.configKey(),
        EnvRegistry.USE_THINKING.configKey(),
        EnvRegistry.REASONING_BUDGET.configKey(),
        EnvRegistry.CHAT_PROFILE.configKey()), spec.dependencyKeys());
  }

  @Test
  void digestUsesActualManagerConfigButDoesNotMisclassifyRuntimeVduModeAsConfiguration() {
    var applied = config(4096, false);
    var vduProcedure = config(4096, true);
    var changedContext = config(8192, false);
    var resolved = ResolvedConfig.builder().build();

    assertEquals(
        HeadAssembly.generativeAppliedVersion(applied, resolved),
        HeadAssembly.generativeAppliedVersion(vduProcedure, resolved),
        "VDU is a runtime procedure mode with no EnvRegistry or ConfigKey declaration");
    assertNotEquals(
        HeadAssembly.generativeAppliedVersion(applied, resolved),
        HeadAssembly.generativeAppliedVersion(changedContext, resolved));
  }

  @Test
  void everyResolvedLaunchControlChangesGenerativeAppliedVersionIndependently() {
    var applied = config(4096, false);
    var before = ResolvedConfig.builder()
        .putDefault("justsearch.llm.slots", "2")
        .putDefault("justsearch.llm.kv_type", "q8_0")
        .putDefault("justsearch.llm.use_thinking", "true")
        .putDefault("justsearch.llm.reasoning_budget", "512")
        .putDefault("policy.gpu_acceleration_enabled", "true")
        .build();
    String version = HeadAssembly.generativeAppliedVersion(applied, before);
    java.util.Map.of(
        "justsearch.llm.slots", "3",
        "justsearch.llm.kv_type", "f16",
        "justsearch.llm.use_thinking", "false",
        "justsearch.llm.reasoning_budget", "256",
        "policy.gpu_acceleration_enabled", "false").forEach((key, value) -> {
          var builder = ResolvedConfig.builder();
          before.resolutions().forEach((name, resolution) ->
              builder.putDefault(name, resolution.value()));
          builder.put(key, 500, "candidate", null, value);
          assertNotEquals(version, HeadAssembly.generativeAppliedVersion(applied, builder.build()), key);
        });
  }

  @Test
  void capabilityGraphReadsOptionalAbsenceAndDecoratedHandleRetainsHeldFault() {
    try (var components = TestEngineComponents.fourComponents()) {
      var capability = CapabilityGraph.fromRegistry(components).inference();
      assertEquals(CapabilityHealth.OFFLINE, capability.health());
      assertFalse(capability.required(), "an optional ABSENT component is not requested");

      var producer = new ReasonRetainingComponentHandle(components.handle("generative"));
      producer.transition(
          ComponentState.UNAVAILABLE,
          LifecycleReasonCode.INFERENCE_MODEL_NOT_FOUND.code(),
          "model unavailable");
      producer.transition(
          ComponentState.UNAVAILABLE,
          LifecycleReasonCode.INFERENCE_OFFLINE.code(),
          "generic overwrite");

      assertEquals(CapabilityHealth.OFFLINE, capability.health());
      assertEquals(LifecycleReasonCode.INFERENCE_MODEL_NOT_FOUND.code(),
          capability.pendingReason());
      assertEquals("model unavailable", capability.pendingDetail());
      assertTrue(capability.required(), "requested unavailable inference is required");
    }
  }

  private InferenceConfig config(int contextSize, boolean vduMode) {
    return new InferenceConfig(
        tempDir.resolve("bin/../bin/llama-server.exe"),
        tempDir.resolve("models/../models/chat.gguf"),
        tempDir.resolve("models/mmproj.gguf"),
        8081,
        contextSize,
        12,
        vduMode,
        "standard");
  }
}
