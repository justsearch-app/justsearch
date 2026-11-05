/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentHandle;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PictureReadingOwnerRefusalTest {
  @TempDir Path directory;

  @Test
  void operatorProjectorOverrideRefusesWithoutTouchingTheManagerOrCustomPath() {
    var manager = mock(InferenceLifecycleManager.class);
    var handle = mock(ComponentHandle.class);
    var custom = directory.resolve("custom-projector.gguf").toString();
    var desired = ResolvedConfig.builder().put("justsearch.mmproj.model", 500, "jvm_arg", null, custom).build();
    var owner = new GenerativeSettingsComponentOwner(manager, handle, directory, false);
    var failure = assertThrows(SettingsCommitOwner.Refused.class,
        () -> owner.prepare(new UiSettings(), desired, Set.of("pictureReadingEnabled")));
    assertEquals("PICTURE_READING_OVERRIDDEN", failure.response().errorCode().orElseThrow());
    verifyNoInteractions(manager, handle);
  }

  @Test
  void externalServerRefusesBeforeAnyStopOrPreparation() {
    var manager = mock(InferenceLifecycleManager.class);
    when(manager.isUsingExternalLlamaServer()).thenReturn(true);
    var handle = mock(ComponentHandle.class);
    var owner = new GenerativeSettingsComponentOwner(manager, handle, directory, false);
    var failure = assertThrows(SettingsCommitOwner.Refused.class,
        () -> owner.prepare(new UiSettings(), ResolvedConfig.builder().build(), Set.of("pictureReadingEnabled")));
    assertEquals("PICTURE_READING_EXTERNAL_SERVER", failure.response().errorCode().orElseThrow());
    verify(manager).isUsingExternalLlamaServer();
    verifyNoMoreInteractions(manager);
    verifyNoInteractions(handle);
  }

  @Test
  void missingProjectorRefusesEnableBeforeSettingsPublication() throws Exception {
    var manager = mock(InferenceLifecycleManager.class);
    var handle = mock(ComponentHandle.class);
    var owner = new GenerativeSettingsComponentOwner(manager, handle, directory, false);
    var candidate = new UiSettings();
    candidate.setPictureReadingEnabled(true);
    var desired = ResolvedConfig.builder()
        .putDefault("justsearch.models.dir", directory.resolve("no-models").toString()).build();
    var failure = assertThrows(SettingsCommitOwner.Refused.class,
        () -> owner.prepare(candidate, desired, Set.of("pictureReadingEnabled")));
    assertEquals("PICTURE_READING_FILES_MISSING", failure.response().errorCode().orElseThrow());
    verifyNoInteractions(handle);
    verify(manager, never()).prepareResolvedConfig(any(), any(), anyBoolean(), anyBoolean());
  }

  @Test
  void offlinePictureSwitchConfiguresTheNextStartWithoutRequestingALoad() throws Exception {
    var desired = ResolvedConfig.builder().putDefault("justsearch.llm.enabled", "true")
        .putDefault("justsearch.mmproj.model", "none").build();
    var manager = mock(InferenceLifecycleManager.class);
    var prepared = mock(InferenceLifecycleManager.PreparedConfigApply.class);
    when(manager.prepareResolvedConfig(any(), eq(desired), eq(true), eq(true))).thenReturn(prepared);
    var handle = mock(ComponentHandle.class);
    var spec = new io.justsearch.core.component.ComponentSpec("generative", false, Set.of(),
        io.justsearch.core.component.ComponentSpec.ComposeCapability.IN_PLACE, java.time.Duration.ofMinutes(3), 1);
    var before = new io.justsearch.core.component.EngineComponentSnapshot.Component(spec,
        io.justsearch.core.component.ComponentState.ABSENT, null, java.time.Instant.EPOCH,
        1L, "prior", "prior", null, 0, "offline");
    var staging = new io.justsearch.core.component.EngineComponentSnapshot.Component(spec,
        io.justsearch.core.component.ComponentState.STARTING, null, java.time.Instant.EPOCH,
        1L, "prior", "prior", null, 0, "preparing");
    when(handle.snapshot()).thenReturn(before, staging);
    when(handle.transitionIfUnchanged(eq(before), eq(io.justsearch.core.component.ComponentState.STARTING), any(), any()))
        .thenReturn(true);
    when(handle.transitionIfUnchanged(eq(staging), eq(before.state()), eq(before.reasonCode()), eq(before.evidence())))
        .thenReturn(true);
    var settings = new UiSettings();
    settings.setChatEnabled(true);
    settings.setPictureReadingEnabled(false);
    var candidate = new GenerativeSettingsComponentOwner(manager, handle, directory, false)
        .prepare(settings, desired, Set.of("pictureReadingEnabled"));
    verify(manager).prepareResolvedConfig(any(), eq(desired), eq(true), eq(true));
    assertEquals(io.justsearch.core.component.ComponentState.ABSENT, candidate.observation().state());
    candidate.abort();
  }

}
