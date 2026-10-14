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

  @org.junit.jupiter.params.ParameterizedTest(name = "heldPictureBatchRefusesSettingsWithoutReplacingItsChild({0})")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void heldPictureBatchRefusesSettingsWithoutReplacingItsChild(boolean enabled) throws Exception {
    try (var seam = new io.justsearch.app.services.chat.ManagedChatSeam(directory, 0);
        var _ = seam.manager().chatHolds().holdVdu("settings-vdu-check")) {
      var desired = io.justsearch.configuration.resolved.ConfigStore.global().get();
      var handle = mock(ComponentHandle.class);
      var spec = new ComponentSpec("generative", false, Set.of(),
          ComponentSpec.ComposeCapability.IN_PLACE, Duration.ofMinutes(3), 1);
      var before = new EngineComponentSnapshot.Component(spec, ComponentState.READY, null,
          Instant.parse("2026-09-23T00:00:00Z"), 1L, "A", "A", null, 0, "VDU");
      var staging = new EngineComponentSnapshot.Component(spec, ComponentState.RELOADING, null,
          before.stateSince(), 1L, "A", "A", null, 0, "preparing");
      when(handle.snapshot()).thenReturn(before, staging);
      when(handle.transitionIfUnchanged(eq(before), eq(ComponentState.RELOADING), any(), any()))
          .thenReturn(true);
      when(handle.transitionIfUnchanged(eq(staging), eq(before.state()), eq(before.reasonCode()),
          eq(before.evidence()))).thenReturn(true);
      var settings = new UiSettings();
      settings.setChatEnabled(enabled);
      var owner = new GenerativeSettingsComponentOwner(seam.manager(), handle, directory, false);
      int launches = seam.models().size();
      assertThrows(SettingsCommitOwner.Refused.class,
          () -> owner.prepare(settings, desired, Set.of("chatEnabled"), SettingsCandidateContext.NONE).abort());
      assertEquals(launches, seam.models().size(), "settings must not replace the held child");
      org.junit.jupiter.api.Assertions.assertTrue(seam.manager().isOnline());
      org.junit.jupiter.api.Assertions.assertTrue(seam.manager().currentConfig().vduMode());
      verify(handle).transitionIfUnchanged(staging, before.state(), before.reasonCode(), before.evidence());
    }
  }

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

  /*
   * Spec A §7.2: a server stepped down to a smaller window serves with the context_reduced notice.
   * The step-down is sticky for an unchanged configuration, so the candidate a settings apply
   * relaunches comes back on the reduced window, and the READY observation the apply publishes
   * must still carry the notice rather than a bare READY.
   */
  @Test
  void aSettingsApplyKeepsTheContextReducedNoticeOfASteppedDownServer() throws Exception {
    var desired = ResolvedConfig.builder().putDefault("justsearch.llm.enabled", "true").build();
    var manager = mock(InferenceLifecycleManager.class);
    var prepared = mock(InferenceLifecycleManager.PreparedConfigApply.class);
    when(manager.prepareResolvedConfig(any(), eq(desired), eq(true), eq(false))).thenReturn(prepared);
    when(prepared.targetsOnline()).thenReturn(true);
    when(manager.launchedContextWindow()).thenReturn(
        new io.justsearch.app.api.OnlineAiRuntimeIntrospection.ContextWindow(
            4096, "stepped-from:8192", null, 1, "q8_0"));
    var handle = mock(ComponentHandle.class);
    var spec = new ComponentSpec("generative", false, Set.of(),
        ComponentSpec.ComposeCapability.IN_PLACE, Duration.ofMinutes(3), 1);
    var before = new EngineComponentSnapshot.Component(spec, ComponentState.READY,
        "inference.context_reduced", Instant.parse("2026-09-23T00:00:00Z"), 1L, "A", "A", null,
        0, "serving");
    var staging = new EngineComponentSnapshot.Component(spec, ComponentState.RELOADING, null,
        before.stateSince(), before.stateSinceMonotonicNanos(), "A", "A", null, 0, "preparing");
    when(handle.snapshot()).thenReturn(before, staging);
    when(handle.transitionIfUnchanged(eq(before), eq(ComponentState.RELOADING),
        any(), any())).thenReturn(true);
    var settings = new UiSettings();
    settings.setChatEnabled(true);

    var candidate = new GenerativeSettingsComponentOwner(manager, handle, directory, false)
        .prepare(settings, desired, Set.of("theme"), SettingsCandidateContext.NONE);

    assertEquals(ComponentState.READY, candidate.observation().state());
    assertEquals("inference.context_reduced", candidate.observation().reasonCode(),
        "the relaunched candidate serves on the reduced window, so the notice stays");
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
