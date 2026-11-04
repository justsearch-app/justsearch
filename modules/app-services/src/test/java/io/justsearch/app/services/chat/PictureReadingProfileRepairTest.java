/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.services.GenerativeSettingsComponentOwner;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.ai.install.DownloadExecutor;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelRegistryLoader;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.model.SupportingFile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

final class PictureReadingProfileRepairTest {
  @TempDir Path directory;
  private String priorModels;

  @BeforeEach
  void isolateModels() {
    priorModels = System.getProperty("justsearch.models.dir");
    System.setProperty("justsearch.models.dir", directory.resolve("models").toString());
  }

  @AfterEach
  void restoreModels() {
    if (priorModels == null) System.clearProperty("justsearch.models.dir");
    else System.setProperty("justsearch.models.dir", priorModels);
  }

  @Test
  void profileActivationCannotReopenSavedOff() {
    var settings = settings(false);
    assertOff(ConfigStoreRebuilder.prepare(settings, ChatModelProfile.COMPACT, null));
  }

  @Test
  void reconfigureRetainingAnActivatedProfileCannotReopenSavedOff() {
    var serving = ConfigStoreRebuilder.prepare(settings(true), ChatModelProfile.COMPACT, null);
    var refreshed = ConfigStoreRebuilder.prepare(settings(false), null, serving);
    assertEquals("compact", refreshed.ai().chatProfile());
    assertOff(refreshed);
  }

  @Test
  void switchesBothWaysAfterActivationAndRetainedProfilePreparation() throws Exception {
    var profile = ChatModelProfile.COMPACT;
    var models = Files.createDirectories(directory.resolve("models"));
    var model = models.resolve(profile.modelFile());
    Files.createDirectories(model.getParent());
    Files.writeString(model, "model fixture");
    var projector = Files.writeString(models.resolve(profile.mmprojFile()), "projector fixture");
    var pkg = mock(ModelPackage.class);
    var variant = mock(ModelVariant.class);
    when(variant.filename()).thenReturn(model.getFileName().toString());
    when(pkg.variants()).thenReturn(List.of(variant));
    when(pkg.supportingFiles()).thenReturn(List.of(new SupportingFile(projector.getFileName().toString(),
        DownloadExecutor.sha256(projector), Files.size(projector), "https://example.invalid/projector")));
    var registry = new ModelRegistry(2, "verified local fixture", List.of(pkg));
    try (var loader = mockStatic(ModelRegistryLoader.class)) {
      loader.when(() -> ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json")).thenReturn(registry);
      var settings = settings(true);
      var activated = ConfigStoreRebuilder.prepare(settings, profile, null);
      var retained = ConfigStoreRebuilder.prepare(settings, null, activated);
      for (var serving : List.of(activated, retained)) {
        var owner = new GenerativeSettingsComponentOwner(null, observation(), directory, false);
        var enabled = assertDoesNotThrow(() -> owner.prepare(settings, serving, Set.of("pictureReadingEnabled")));
        enabled.abort();
        var offSettings = settings(false);
        var off = ConfigStoreRebuilder.prepare(offSettings, null, serving);
        var disabled = assertDoesNotThrow(() -> owner.prepare(offSettings, off, Set.of("pictureReadingEnabled")));
        assertOff(off);
        disabled.abort();
      }
    }
  }

  @Test
  void operatorProjectorKeepsItsPathAndRefusesSwitchesAfterProfilePreparation() throws Exception {
    String key = "justsearch.mmproj.model";
    String prior = System.getProperty(key);
    var custom = directory.resolve("custom-projector.gguf").toString();
    Files.writeString(Path.of(custom), "operator projector");
    var model = directory.resolve("models").resolve(ChatModelProfile.COMPACT.modelFile());
    Files.createDirectories(model.getParent());
    Files.writeString(model, "model fixture");
    try {
      System.setProperty(key, custom);
      var activated = ConfigStoreRebuilder.prepare(settings(false), ChatModelProfile.COMPACT, null);
      var retained = ConfigStoreRebuilder.prepare(settings(false), null, activated);
      for (var desired : List.of(activated, retained)) {
        assertEquals(custom, desired.ai().mmprojModel());
        assertEquals("jvm_arg", desired.resolution(key).sourceName());
        for (boolean enabled : List.of(false, true)) {
          var failure = assertThrows(SettingsCommitOwner.Refused.class, () ->
              new GenerativeSettingsComponentOwner(null, observation(), directory, false)
                  .prepare(settings(enabled), desired, Set.of("pictureReadingEnabled")));
          assertEquals("PICTURE_READING_OVERRIDDEN", failure.response().errorCode().orElseThrow());
        }
      }
      var manager = mock(InferenceLifecycleManager.class);
      when(manager.prepareResolvedConfig(any(), eq(activated), anyBoolean(), anyBoolean()))
          .thenReturn(mock(InferenceLifecycleManager.PreparedConfigApply.class));
      var handle = observation();
      when(handle.transitionIfUnchanged(any(EngineComponentSnapshot.Component.class), any(), any(), any())).thenReturn(true);
      var prepared = new GenerativeSettingsComponentOwner(manager, handle, directory, false)
          .prepare(settings(false), activated, Set.of("chatProfile"), new SettingsCandidateContext(ChatModelProfile.COMPACT));
      verify(manager).prepareResolvedConfig(argThat(config -> Path.of(custom).equals(config.mmprojPath())),
          eq(activated), anyBoolean(), anyBoolean());
      prepared.abort();
    } finally {
      if (prior == null) System.clearProperty(key); else System.setProperty(key, prior);
    }
  }

  @Test
  void environmentProjectorSourceRefusesSwitchesRegardlessOfItsOrdinal() {
    var desired = ResolvedConfig.builder()
        .put("justsearch.mmproj.model", 200, "env_var", "JUSTSEARCH_MMPROJ_MODEL", "/custom/projector.gguf").build();
    var failure = assertThrows(SettingsCommitOwner.Refused.class, () ->
        new GenerativeSettingsComponentOwner(null, observation(), directory, false)
            .prepare(settings(false), desired, Set.of("pictureReadingEnabled")));
    assertEquals("PICTURE_READING_OVERRIDDEN", failure.response().errorCode().orElseThrow());
  }

  private UiSettings settings(boolean enabled) {
    var settings = new UiSettings();
    settings.setChatEnabled(false);
    settings.setPictureReadingEnabled(enabled);
    // Keep discovery and the resolved profile pair in this test's own directory.
    settings.setLlmModelPath(directory.resolve("models").resolve(ChatModelProfile.COMPACT.modelFile()).toString());
    return settings;
  }

  private void assertOff(ResolvedConfig config) {
    assertEquals("none", config.ai().mmprojModel());
    assertFalse(InferenceConfig.pictureReadingEnabled(config), "the coordinator's admission predicate must stay closed");
    var inference = new InferenceConfig(directory.resolve("server"), directory.resolve("model"),
        directory.resolve("projector"), 12345, 4096, 0, false);
    assertNull(inference.withPictureReading(config).mmprojPath());
  }

  private ComponentHandle observation() {
    var handle = mock(ComponentHandle.class);
    var spec = new ComponentSpec("generative", false, Set.of(),
        ComponentSpec.ComposeCapability.IN_PLACE, Duration.ofMinutes(3), 1);
    when(handle.snapshot()).thenReturn(new EngineComponentSnapshot.Component(spec, ComponentState.ABSENT,
        null, Instant.EPOCH, 1L, "prior", "prior", null, 0, "offline"));
    return handle;
  }
}
