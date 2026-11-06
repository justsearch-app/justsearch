/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.engine.DefaultEngineComponentRegistry;
import io.justsearch.app.services.GenerativeSettingsComponentOwner;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.chat.ManagedChatSeam;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.CudaDevice;
import io.justsearch.app.services.gpu.DeviceFacts;
import io.justsearch.app.services.gpu.GpuProfileResolver;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.FixedSettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ConfigApplyScopes;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.ort.EncoderRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ordinary settings saves after "Remove files" took the answer model, through the composition the
 * Engine runs: the real settings owner and GPU memory planner, the real generative owner and
 * lifecycle manager, and a real removal in a temporary models folder. The planner reads which chat
 * models are on disk, so a save after the removal re-resolves generative values and reaches the
 * generative owner even when the change has nothing to do with answers. On a 4 GiB device that
 * happens with these small files; the real 5.9 GB model does it on devices up to 10 GiB.
 */
final class AnswerRemovalSettingsSaveTest {
  private static final Map<EncoderRole, Double> WEIGHTS = Map.of(
      EncoderRole.EMBEDDING, 598.3,
      EncoderRole.RERANKER, 600.3);

  @TempDir Path tmp;
  private ManagedChatSeam seam;
  private ConfigStore previous;
  private ConfigStore config;
  private RemovalModelsFixture files;
  private UiSettingsStore store;
  private RuntimeIntentTestFixture fixture;
  private DefaultEngineComponentRegistry components;
  private AiInstallService owner;
  private SettingsServiceImpl settings;
  private Path model;
  private Path projector;

  @BeforeEach
  void compose() throws Exception {
    previous = ConfigStore.globalOrNull();
    seam = new ManagedChatSeam(tmp.resolve("managed"), 0);
    Path home = tmp.resolve("home");
    files = new RemovalModelsFixture(home, json ->
        json.replace("answer.gguf", ChatModelProfile.STANDARD.modelFile()));
    model = files.models.resolve(ChatModelProfile.STANDARD.modelFile());
    projector = files.models.resolve(ChatModelProfile.STANDARD.mmprojFile());
    Files.write(model, RemovalModelsFixture.CHAT);
    Files.write(projector, RemovalModelsFixture.PROJECTOR);

    ConfigStoreRebuilder.rememberAutoDetected(Map.of(
        EnvRegistry.MODELS_DIR.configKey(), files.models.toString(),
        EnvRegistry.GPU_ENABLED.configKey(), "true",
        EnvRegistry.GPU_LAYERS.configKey(), "99"));
    var device = new CudaDevice(0, Optional.empty(), Optional.empty(),
        Optional.empty(), 4L << 30);
    var facts = new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080, List.of(device),
        Optional.of(device));
    ConfigStoreRebuilder.rememberDevicePlanner(GpuProfileResolver.planner(() -> facts,
        ignored -> WEIGHTS));

    store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        home.resolve("settings.json"));
    UiSettings initial = new UiSettings();
    initial.setChatEnabled(false);
    initial.setPictureReadingEnabled(false);
    initial.setRerankerEnabled(false);
    initial.setLlmModelPath(model.toAbsolutePath().toString());
    initial.setServerExecutablePath(seam.base().serverExecutable().toString());
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
    config = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    ConfigStore.setGlobal(config);

    var budget = new RetainedStateBudget();
    budget.declare("attempted-configurations", 1, "test");
    components = new DefaultEngineComponentRegistry(budget, config.publicationLock());
    ComponentHandle generative = components.register(spec("generative"));
    ComponentHandle encoders = components.register(spec("encoders"));
    var composer = new FixedSettingsComponentComposer(components);
    composer.register("generative",
        new GenerativeSettingsComponentOwner(seam.manager(), generative, home, false));
    composer.register("encoders", (candidate, desired, keys) -> {
      var prepared = mock(FixedSettingsComponentComposer.QueryRolePreparedOwner.class);
      when(prepared.selection()).thenReturn(new QueryRoleSelection(
          QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled()));
      when(prepared.composition()).thenReturn(Optional.of(new ComposeEvidence(
          ComposeEvidence.Mode.BESIDE, "candidate_fits_free_device_memory", 20L, 10L)));
      when(prepared.observation()).thenReturn(encoders.snapshot());
      org.mockito.Mockito.doAnswer(call -> {
        call.<Runnable>getArgument(0).run();
        return null;
      }).when(prepared).withOwnerLocks(org.mockito.ArgumentMatchers.any());
      return prepared;
    });
    composer.seal();
    fixture = new RuntimeIntentTestFixture(tmp.resolve("intent"), store, config, composer);
    settings = new SettingsServiceImpl(store, fixture.runner());
    var real = new AiInstallService(null, store, null, null, home, null, settings);
    real.setManifestForTest(files.registry);
    store.bindModelFileRemovalFence(real.removalFence());
    owner = org.mockito.Mockito.spy(real);
    org.mockito.Mockito.doReturn(HardwareProfile.cpuOnly()).when(owner).detectHardwareProfile();
    owner.setRemovalUsageProbe(id -> Optional.empty());
  }

  @AfterEach
  void close() throws Exception {
    if (fixture != null) fixture.close();
    if (components != null) components.close();
    if (seam != null) seam.close();
    ConfigStoreRebuilder.rememberDevicePlanner(null);
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
    TestResolvedConfigHelper.restoreGlobal(previous);
  }

  private static ComponentSpec spec(String name) {
    return new ComponentSpec(name, false, Set.of(), ComponentSpec.ComposeCapability.IN_PLACE,
        Duration.ofMinutes(3), 1);
  }

  private void removeAnswers() throws Exception {
    DownloadedFiles.Part part = owner.downloadedFiles().parts.stream()
        .filter(p -> p.id.equals("chat")).findFirst().orElseThrow();
    DownloadedFiles.Removal removal = owner.removeFiles("chat", part.previewDigest).completion()
        .toCompletableFuture().get(30, TimeUnit.SECONDS);
    assertEquals("removed", removal.state, removal.message);
    assertFalse(Files.exists(model), "precondition: the answer model is really gone");
    assertFalse(Files.exists(projector), "precondition: the picture-reading file is really gone");
  }

  private OperationAttemptRunner.Result save(Consumer<UiSettings> change) {
    var snapshot = store.inspect();
    UiSettings candidate = snapshot.settings();
    change.accept(candidate);
    return assertDoesNotThrow(() -> settings.applyInternal(candidate, snapshot.witness(),
        TestEngineContexts.internal()), "the settings owner answers instead of throwing");
  }

  private void assertSaved(OperationAttemptRunner.Result result) {
    assertTrue(result.response().success(), "the save was refused: " + result.response());
  }

  /** What a save would change in the serving configuration, its settings left as they are. */
  private Map<String, Set<String>> pendingComponents() {
    return ConfigApplyScopes.classify(config.get(),
        ConfigStoreRebuilder.prepare(store.load(), null, config.get())).component();
  }

  @Test
  void bestMatchFirstSwitchesOnAfterTheAnswerModelWasRemoved() throws Exception {
    removeAnswers();

    assertSaved(save(s -> s.setRerankerEnabled(true)));

    assertEquals(Boolean.TRUE, store.load().getRerankerEnabled());
    assertTrue(seam.models().isEmpty(), "no answer server was started");
  }

  @Test
  void unrelatedSettingsSaveAfterTheAnswerModelWasRemoved() throws Exception {
    removeAnswers();

    assertSaved(save(s -> s.setTheme("dark")));
    assertSaved(save(s -> s.setPauseAnswersWhileIndexing(Boolean.FALSE)));

    assertEquals("dark", store.load().getTheme());
    assertEquals(Boolean.FALSE, store.load().getPauseAnswersWhileIndexing());
  }

  @Test
  void theRemovalItselfBringsTheRuntimeUpToDateWithWhatIsOnDisk() throws Exception {
    removeAnswers();

    assertEquals(Map.of(), pendingComponents(),
        "the serving configuration already plans for the model being gone, before any save");
    UiSettings saved = store.load();
    assertEquals(List.of("chat"), saved.getDeclinedAiPackages());
    assertEquals(Boolean.FALSE, saved.getChatEnabled(), "the refresh changed no choice");
    assertEquals(Boolean.FALSE, saved.getPictureReadingEnabled(), "the refresh changed no choice");
  }

  @Test
  void aModelDeletedByOtherMeansDoesNotBlockSavesWhileAnswersAreOff() throws Exception {
    Files.delete(model);
    Files.delete(projector);
    assertTrue(pendingComponents().containsKey("generative"),
        "precondition: the plan moved a generative value, so the save reaches its owner: "
            + pendingComponents());

    assertSaved(save(s -> s.setTheme("dark")));

    assertEquals("dark", store.load().getTheme());
    assertEquals(Map.of(), pendingComponents(), "that save brought the runtime up to date");
  }

  @Test
  void answersStillCannotBeSwitchedOnWithoutTheirFiles() throws Exception {
    removeAnswers();

    var refused = save(s -> s.setChatEnabled(true)).response();

    assertFalse(refused.success(), "the missing files go through the consent, not a settings save");
    assertEquals("INVALID_CONFIG", refused.errorDetails().get("reason"));
    assertEquals(Boolean.FALSE, store.load().getChatEnabled());
    assertTrue(seam.models().isEmpty(), "no answer server was started");
  }

  @Test
  void pictureReadingStillCannotBeSwitchedOnWithoutItsFile() throws Exception {
    removeAnswers();

    var refused = save(s -> s.setPictureReadingEnabled(true)).response();

    assertFalse(refused.success());
    assertEquals("PICTURE_READING_FILES_MISSING", refused.errorCode().orElseThrow());
    assertEquals(Boolean.FALSE, store.load().getPictureReadingEnabled());
  }

  @Test
  void answersStartFromTheModelOnceItsFilesAreBack() throws Exception {
    removeAnswers();
    Files.write(model, RemovalModelsFixture.CHAT);
    Files.write(projector, RemovalModelsFixture.PROJECTOR);

    assertSaved(save(s -> s.setChatEnabled(true)));

    assertEquals(List.of(model.getFileName().toString()), seam.models(),
        "the target saved while the model was missing did not stick");
  }
}
