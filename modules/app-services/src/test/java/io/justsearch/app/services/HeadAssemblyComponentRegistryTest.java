/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec.ComposeCapability;
import io.justsearch.core.component.ComponentState;
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
        EnvRegistry.SERVER_EXE.configKey(),
        EnvRegistry.LLM_MODEL_PATH.configKey(),
        EnvRegistry.MMPROJ_MODEL.configKey(),
        EnvRegistry.SERVER_PORT.configKey(),
        EnvRegistry.CONTEXT_SIZE.configKey(),
        EnvRegistry.GPU_LAYERS.configKey(),
        EnvRegistry.CHAT_PROFILE.configKey()), spec.dependencyKeys());
  }

  @Test
  void digestUsesActualManagerConfigButDoesNotMisclassifyRuntimeVduModeAsConfiguration() {
    var applied = config(4096, false);
    var vduProcedure = config(4096, true);
    var changedContext = config(8192, false);

    assertEquals(
        HeadAssembly.generativeAppliedVersion(applied),
        HeadAssembly.generativeAppliedVersion(vduProcedure),
        "VDU is a runtime procedure mode with no EnvRegistry or ConfigKey declaration");
    assertNotEquals(
        HeadAssembly.generativeAppliedVersion(applied),
        HeadAssembly.generativeAppliedVersion(changedContext));
  }

  @Test
  void capabilityBridgePublishesOptionalAbsenceAndPreservesHeldFaultReason() {
    ComponentHandle absentHandle = mock(ComponentHandle.class);
    HeadAssembly.observeGenerativeCapability(absentHandle, new InferenceCapability(false));
    verify(absentHandle).transition(
        ComponentState.ABSENT,
        LifecycleReasonCode.INFERENCE_OFFLINE.code(),
        "Inference not configured");

    ComponentHandle configuredHandle = mock(ComponentHandle.class);
    var capability = new InferenceCapability(true);
    HeadAssembly.observeGenerativeCapability(configuredHandle, capability);
    capability.transition(
        CapabilityHealth.OFFLINE,
        LifecycleReasonCode.INFERENCE_MODEL_NOT_FOUND.code(),
        "model unavailable");
    capability.transition(
        CapabilityHealth.OFFLINE,
        LifecycleReasonCode.INFERENCE_OFFLINE.code(),
        "generic overwrite");

    verify(configuredHandle, times(1)).transition(
        ComponentState.UNAVAILABLE,
        LifecycleReasonCode.INFERENCE_MODEL_NOT_FOUND.code(),
        "model unavailable");
    verify(configuredHandle, never()).transition(
        ComponentState.UNAVAILABLE,
        LifecycleReasonCode.INFERENCE_OFFLINE.code(),
        "generic overwrite");
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
