/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

final class GenerativeSettingsComponentOwnerTest {
  @TempDir Path directory;

  @Test
  void transientProfilePreparesTheModelAndProjectorPairWithoutChangingResolvedSettings()
      throws Exception {
    Path models = directory.resolve("models");
    Path model = models.resolve(ChatModelProfile.COMPACT.modelFile());
    Path projector = models.resolve(ChatModelProfile.COMPACT.mmprojFile());
    Files.createDirectories(model.getParent());
    Files.createDirectories(projector.getParent());
    Files.writeString(model, "model");
    Files.writeString(projector, "projector");
    Path executable = directory.resolve("llama-server.exe");
    Files.writeString(executable, "server");
    ResolvedConfig desired = ResolvedConfig.builder()
        .putDefault("justsearch.models.dir", models.toString())
        .putDefault("justsearch.server.exe", executable.toString())
        .putDefault("justsearch.gpu.layers", "7")
        .putDefault("justsearch.context.size", "8192")
        .build();
    var manager = mock(InferenceLifecycleManager.class);
    var prepared = mock(InferenceLifecycleManager.PreparedConfigApply.class);
    when(manager.prepareResolvedConfig(any(), eq(desired), eq(true), eq(false))).thenReturn(prepared);
    when(prepared.targetsOnline()).thenReturn(true);
    var handle = mock(ComponentHandle.class);
    var spec = new ComponentSpec("generative", false, Set.of(),
        ComponentSpec.ComposeCapability.IN_PLACE, Duration.ofMinutes(3), 1);
    var before = new EngineComponentSnapshot.Component(spec, ComponentState.READY, null,
        Instant.parse("2026-09-23T00:00:00Z"), 1L, "A", "A", null, 0, "A");
    var staging = new EngineComponentSnapshot.Component(spec, ComponentState.RELOADING, null,
        before.stateSince(), before.stateSinceMonotonicNanos(), "A", "A", null, 0, "preparing");
    when(handle.snapshot()).thenReturn(before, staging);
    when(handle.transitionIfUnchanged(eq(before), eq(ComponentState.RELOADING),
        any(), any())).thenReturn(true);
    var owner = new GenerativeSettingsComponentOwner(manager, handle, directory, false);
    var settings = new UiSettings();
    settings.setChatEnabled(true);

    owner.prepare(settings, desired, Set.of("chatProfile"),
        new SettingsCandidateContext(ChatModelProfile.COMPACT));

    var config = ArgumentCaptor.forClass(InferenceConfig.class);
    verify(manager).prepareResolvedConfig(config.capture(), eq(desired), eq(true), eq(false));
    assertEquals(model, config.getValue().modelPath());
    assertEquals(projector, config.getValue().mmprojPath());
    assertEquals(ChatModelProfile.COMPACT.id(), config.getValue().chatProfileId());
    assertEquals(executable, config.getValue().serverExecutable());
    assertEquals(7, config.getValue().gpuLayers());
    assertEquals(8192, config.getValue().contextSize());
    assertEquals("standard", desired.ai().chatProfile(),
        "the transient target does not change the resolved default profile");
  }

  @Test
  void transientProfileCannotOverrideAnOperatorResolvedModel() {
    Path operator = directory.resolve("operator.gguf");
    var desired = ResolvedConfig.builder()
        .put("justsearch.llm.model_path", 500, "jvm_arg", null, operator.toString())
        .build();
    var manager = mock(InferenceLifecycleManager.class);
    var owner = new GenerativeSettingsComponentOwner(manager, mock(ComponentHandle.class),
        directory, false);
    var refusal = assertThrows(SettingsCommitOwner.Refused.class,
        () -> owner.prepare(new UiSettings(), desired, Set.of("chatProfile"),
            new SettingsCandidateContext(ChatModelProfile.COMPACT)));
    assertEquals("GENERATIVE_PREPARATION_REFUSED", refusal.response().errorCode().orElseThrow());
    org.mockito.Mockito.verifyNoInteractions(manager);
  }

  @Test
  void refreshWhileOfflineRetainsConfigurationWithoutStartingManagedServer() throws Exception {
    var desired = ResolvedConfig.builder().putDefault("justsearch.llm.enabled", "true").build();
    var manager = mock(InferenceLifecycleManager.class);
    var prepared = mock(InferenceLifecycleManager.PreparedConfigApply.class);
    when(manager.prepareResolvedConfig(any(), eq(desired), eq(true), eq(true))).thenReturn(prepared);
    var handle = mock(ComponentHandle.class);
    var spec = new ComponentSpec("generative", false, Set.of(),
        ComponentSpec.ComposeCapability.IN_PLACE, Duration.ofMinutes(3), 1);
    var before = new EngineComponentSnapshot.Component(spec, ComponentState.ABSENT, null,
        Instant.parse("2026-09-23T00:00:00Z"), 1L, "A", "A", null, 0, "offline");
    var staging = new EngineComponentSnapshot.Component(spec, ComponentState.STARTING, null,
        before.stateSince(), before.stateSinceMonotonicNanos(), "A", "A", null, 0, "preparing");
    when(handle.snapshot()).thenReturn(before, staging);
    when(handle.transitionIfUnchanged(eq(before), eq(ComponentState.STARTING),
        any(), any())).thenReturn(true);
    var settings = new UiSettings();
    settings.setChatEnabled(true);
    org.junit.jupiter.api.Assertions.assertTrue(
        io.justsearch.app.services.bootstrap.phases.InferenceDecision.decideInferenceConfigured(desired, false));

    var candidate = new GenerativeSettingsComponentOwner(manager, handle, directory, false)
        .prepare(settings, desired, Set.of("modelRefresh"), new SettingsCandidateContext(null, true));

    verify(manager).prepareResolvedConfig(any(), eq(desired), eq(true), eq(true));
    assertEquals(ComponentState.ABSENT, candidate.observation().state());
    assertEquals("A", candidate.observation().appliedVersion());
    org.junit.jupiter.api.Assertions.assertNotEquals("A", candidate.observation().desiredVersion());
  }

  @Test
  void unresolvedBootRecoveryFencesPhysicalManagerAndPublishesUnavailable() {
    var manager = mock(InferenceLifecycleManager.class);
    var handle = mock(ComponentHandle.class);
    var owner = new GenerativeSettingsComponentOwner(manager, handle, directory, false);

    owner.markRecoveryUnavailable();

    verify(manager).fenceForSettingsRecovery();
    verify(handle).transition(eq(ComponentState.UNAVAILABLE),
        eq(io.justsearch.app.api.lifecycle.LifecycleReasonCode.SETTINGS_RECOVERY_REQUIRED.code()),
        any());
  }
}
