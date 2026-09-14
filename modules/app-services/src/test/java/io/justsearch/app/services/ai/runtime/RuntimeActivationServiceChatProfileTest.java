/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.api.AiRuntimeActivationStatus;
import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.inference.RealizedChatIdentity;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 842 sections 2.3-2.5 — the chat-model profile slice of runtime activation.
 *
 * <p>Three properties are pinned here, each of which was a live defect or a near-miss:
 *
 * <ol>
 *   <li><b>A profile switch carries the pair.</b> Activation used to route every model change
 *       through {@code applyRuntimeOverrides(bare path)}, which drops the projector — that is how
 *       dev stacks ended up running silently text-only. A profile activation must call
 *       {@code applyChatProfileWithRuntime} instead, and must never reach the bare-path apply.
 *   <li><b>A profile is not a stored user path.</b> Writing the resolved file into
 *       {@code UiSettings.llmModelPath} would turn one session dev profile into a permanent
 *       operator-looking setting that outlives it.
 *   <li><b>Absent means unchanged.</b> With no {@code chatProfile} in the request the flow is the
 *       pre-842 one, byte for byte.
 * </ol>
 *
 * <p>The self-test is stubbed through the package-private seam: the real one spawns llama-server
 * and gates on an NVML VRAM delta, so on any CI runner it can only return {@code inconclusive} —
 * a verdict the flow refuses to act on, leaving everything this slice adds unreachable.
 */
final class RuntimeActivationServiceChatProfileTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  private static final String CHAT_PROFILE_PROP = "justsearch.chat.profile";
  private static final String MODELS_DIR_PROP = "justsearch.models.dir";
  private static final String SERVER_EXE_PROP = "justsearch.server.exe";
  private static final String SERVER_EXE_SOURCE_PROP = "justsearch.server.exe.source";

  @TempDir Path tmp;

  private final Map<String, String> prevProps = new HashMap<>();
  private ConfigStore prevStore;
  private final List<io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture> intentFixtures = new ArrayList<>();

  @AfterEach
  void restore() throws Exception {
    for (var fixture : intentFixtures) fixture.close();
    for (var e : prevProps.entrySet()) {
      if (e.getValue() == null) {
        System.clearProperty(e.getKey());
      } else {
        System.setProperty(e.getKey(), e.getValue());
      }
    }
    prevProps.clear();
    TestResolvedConfigHelper.restoreGlobal(prevStore);
    prevStore = null;
    processExecutors.close();
  }

  // ---------------------------------------------------------------- resolution + remedy

  @Test
  @DisplayName("compact profile with no model on disk fails MODEL_NOT_FOUND naming the fetch script")
  void missingCompactModelFailsWithFetchScriptRemedy() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    // Deliberately do NOT create compact/Qwen3.5-4B-Q4_K_M.gguf.

    RuntimeActivationService svc = newService(OnlineAiService.unavailable());
    svc.setSelfTestOverrideForTest(
        (exe, model) -> {
          fail("self-test must not run for a model that is not on disk");
          return null;
        });

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("failed", st.state);
    assertEquals("MODEL_NOT_FOUND", st.errorCode);
    assertTrue(
        st.message.contains("node scripts/dev/fetch-compact-model.mjs"),
        "the remedy must name the only thing that puts the compact model on disk; "
            + "Run Install AI would point at a plan that excludes it: "
            + st.message);
    assertTrue(
        st.message.contains(modelsDir().toString()),
        "the profile must resolve against the CONFIGURED models dir: " + st.message);
    assertTrue(
        st.message.contains("Qwen3.5-4B-Q4_K_M.gguf"),
        "the message must name the file it looked for: " + st.message);
  }

  // ---------------------------------------------------------------- the apply seam

  @Test
  @DisplayName("profile activation commits settings before one combined profile/runtime apply")
  void profileActivationAppliesCombinedTargetWithoutPersistingModelPath() throws Exception {
    setUpEnvironment();
    Path variantExe = createVariantExe("cuda12");
    Path compact = createCompactModel();

    Path operatorModel = tmp.resolve("operator-9b.gguf");
    Files.writeString(operatorModel, "gguf", StandardCharsets.UTF_8);
    UiSettingsStore store = settingsStore();
    UiSettings s = store.load();
    s.setLlmModelPath(operatorModel.toAbsolutePath().toString());
    store.save(s);

    RecordingAiControl control = new RecordingAiControl();
    control.observedStore = store;
    RuntimeActivationService svc = newService(control, store);
    List<Path> selfTested = new ArrayList<>();
    svc.setSelfTestOverrideForTest(
        (exe, model) -> {
          selfTested.add(model);
          return passingSelfTest();
        });

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("completed", st.state, "message=" + st.message);
    assertEquals(
        List.of(compact.toAbsolutePath()),
        selfTested,
        "the self-test must validate the model the engine is about to load, not the stored one");
    assertEquals(
        List.of(ChatModelProfile.COMPACT),
        control.profilesApplied,
        "a profile activation must keep the projector pair in the combined apply");
    assertEquals(
        List.of(OnlineAiRuntimeControl.RestartPolicy.RESTART_ALWAYS),
        control.profilePolicies,
        "same restart policy the pre-842 settings path used");
    assertTrue(
        control.barePathApplies.isEmpty(),
        "the bare-path apply clears the profile claim and nulls the mmproj, so it must not be"
            + " reached: "
            + control.barePathApplies);
    assertEquals(List.of(variantExe.toAbsolutePath().toString()), control.serverExecutables);
    assertEquals(List.of(99), control.profileGpuLayers);
    assertEquals(
        List.of(variantExe.toAbsolutePath()),
        control.publishedServerExecutables,
        "the settings owner must publish ConfigStore before inference observes the target");
    assertEquals(List.of(99), control.publishedGpuLayers);
    assertEquals(List.of(Boolean.TRUE), control.publishedChatEnabled);
    assertEquals(
        operatorModel.toAbsolutePath().toString(),
        store.load().getLlmModelPath(),
        "a profile choice is not a stored user path; llmModelPath must survive untouched");
    assertNull(
        System.getProperty(CHAT_PROFILE_PROP),
        "the explicit profile target must not create an unversioned JVM-property writer");
    assertNull(System.getProperty(SERVER_EXE_PROP));
    assertNull(System.getProperty(SERVER_EXE_SOURCE_PROP));
  }

  @Test
  @DisplayName("runtime failure compensates settings without a second inference apply")
  void applyFailureCompensatesSettingsOnce() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    assertNull(System.getProperty(CHAT_PROFILE_PROP), "precondition: no profile claim exists");

    RecordingAiControl control = new RecordingAiControl();
    control.failOnApplyProfile = true;
    UiSettingsStore store = settingsStore();
    UiSettings original = store.load();
    original.setContextLength(4096);
    store.save(original);
    RuntimeActivationService svc = newService(control, store);
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("failed", st.state);
    assertEquals("RUNTIME_ACTIVATION_FAILED", st.errorCode, "message=" + st.message);
    UiSettings restored = store.load();
    assertNull(restored.getChatEnabled());
    assertEquals("", restored.getServerExecutablePath());
    assertEquals(0, restored.getGpuLayers());
    assertEquals(4096, restored.getContextLength());
    assertEquals(1, control.profilesApplied.size(), "compensation must not apply inference again");
    assertNull(ConfigStore.global().get().ai().serverExe());
    assertEquals(0, ConfigStore.global().get().ai().gpuLayers());
    assertEquals(4096, ConfigStore.global().get().ai().contextSize());
    assertNull(
        System.getProperty(CHAT_PROFILE_PROP),
        "compensation must not invent a profile property");
  }

  @Test
  @DisplayName("apply failure leaves a pre-existing operator chat-profile property unchanged")
  void applyFailureLeavesPreviousChatProfilePropUnchanged() throws Exception {
    setUpEnvironment();
    setProp(CHAT_PROFILE_PROP, "standard");
    createVariantExe("cuda12");
    createCompactModel();

    RecordingAiControl control = new RecordingAiControl();
    control.failOnApplyProfile = true;
    RuntimeActivationService svc = newService(control);
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    svc.startActivate("cuda12", "compact");
    awaitDone(svc);

    assertEquals("standard", System.getProperty(CHAT_PROFILE_PROP));
  }

  @Test
  @DisplayName("an intervening self-test settings write refuses activation before runtime effect")
  void selfTestInterveningWriteConflictsBeforeRuntimeApply() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    UiSettingsStore store = settingsStore();
    RecordingAiControl control = new RecordingAiControl();
    RuntimeActivationService svc = newService(control, store);
    svc.setSelfTestOverrideForTest(
        (exe, model) -> {
          commitCompetingSettings(store, 12288);
          return passingSelfTest();
        });

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("failed", st.state);
    assertEquals("RUNTIME_ACTIVATION_FAILED", st.errorCode);
    assertTrue(control.profilesApplied.isEmpty(), "stale candidate must fail before runtime apply");
    assertTrue(control.barePathApplies.isEmpty());
    assertEquals(12288, store.load().getContextLength());
    assertEquals(12288, ConfigStore.global().get().ai().contextSize());
  }

  @Test
  @DisplayName("stale compensation preserves settings accepted during a failing runtime apply")
  void failingRuntimeInterveningWriteRefusesCompensation() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    UiSettingsStore store = settingsStore();
    RecordingAiControl control = new RecordingAiControl();
    control.duringProfileApply = () -> commitCompetingSettings(store, 24576);
    control.failOnApplyProfile = true;
    RuntimeActivationService svc = newService(control, store);
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("failed", st.state);
    assertEquals("RUNTIME_ROLLBACK_FAILED", st.errorCode, "message=" + st.message);
    assertEquals(24576, store.load().getContextLength(), "newer settings must survive");
    assertTrue(Boolean.TRUE.equals(store.load().getChatEnabled()));
    assertEquals(24576, ConfigStore.global().get().ai().contextSize());
    assertEquals(1, control.profilesApplied.size());
  }

  @Test
  @DisplayName("no chatProfile keeps the pre-842 settings path (status-quo pin)")
  void absentProfileKeepsBarePathApply() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    Path chosen = tmp.resolve("chosen.gguf");
    Files.writeString(chosen, "gguf", StandardCharsets.UTF_8);
    UiSettingsStore store = settingsStore();
    UiSettings s = store.load();
    s.setLlmModelPath(chosen.toAbsolutePath().toString());
    store.save(s);

    RecordingAiControl control = new RecordingAiControl();
    RuntimeActivationService svc = newService(control, store);
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    svc.startActivate("cuda12");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("completed", st.state, "message=" + st.message);
    assertTrue(control.profilesApplied.isEmpty(), "no profile was requested, so none may be applied");
    assertEquals(
        List.of(chosen.toAbsolutePath().toString()),
        control.barePathApplies,
        "the settings path must still route through applyRuntimeOverrides exactly as before");
    assertNull(
        System.getProperty(CHAT_PROFILE_PROP),
        "an activation that names no profile must not invent a profile claim");
  }

  // ---------------------------------------------------------------- realized projection

  @Test
  void activationAppliesPublishedOperatorGpuAndContextInsteadOfSettings() throws Exception {
    setUpEnvironment();
    setProp("justsearch.gpu.layers", "0");
    setProp("justsearch.context.size", "16384");
    createVariantExe("cuda12");
    createCompactModel();
    var store = settingsStore();
    var initial = store.load();
    initial.setContextLength(8192);
    store.save(initial);
    var control = new RecordingAiControl();
    var svc = newService(control, store);
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());
    svc.startActivate("cuda12", "compact");
    assertEquals("completed", awaitDone(svc).state);
    assertEquals(List.of(0), control.profileGpuLayers);
    assertEquals(control.publishedGpuLayers, control.profileGpuLayers);
    assertEquals(List.of(16384), control.profileContexts);
    assertEquals(99, store.load().getGpuLayers(), "operator source overrides the stored preference");
  }

  @Test
  void deactivationAppliesThePublishedOperatorGpuOverride() throws Exception {
    setUpEnvironment();
    setProp("justsearch.gpu.layers", "20");
    Path baseline = tmp.resolve("native-bin/llama-server/llama-server.exe");
    Files.createDirectories(baseline.getParent());
    Files.writeString(baseline, "fixture");
    var control = new RecordingAiControl();
    var svc = newService(control);
    svc.startDeactivate();
    assertEquals("completed", awaitDone(svc).state);
    assertEquals(List.of(20), control.bareGpuLayers);
    assertEquals(20, ConfigStore.global().get().ai().gpuLayers());
  }

  @Test
  @DisplayName("status projects the RUNNING engine chat identity when the engine is up")
  void statusProjectsRealizedIdentityWhenOnline() throws Exception {
    setUpEnvironment();
    RuntimeActivationService svc = newService(OnlineAiService.unavailable());
    Path loaded = modelsDir().resolve("compact").resolve("Qwen3.5-4B-Q4_K_M.gguf");
    svc.setRealizedChatIdentitySource(
        () -> RealizedChatIdentity.of("compact", loaded, tmp.resolve("mmproj-F16.gguf")));

    AiRuntimeStatusResponse.ActiveRuntime active = svc.getStatus().active();

    assertEquals("compact", active.chatProfile());
    assertEquals(loaded.toString(), active.modelPath());
    assertEquals(Boolean.TRUE, active.mmprojActive());
  }

  @Test
  @DisplayName("status reports mmprojActive=false when the projector was dropped (the honesty case)")
  void statusReportsProjectorDropped() throws Exception {
    setUpEnvironment();
    RuntimeActivationService svc = newService(OnlineAiService.unavailable());
    svc.setRealizedChatIdentitySource(
        () -> RealizedChatIdentity.of(null, tmp.resolve("bare.gguf"), null));

    AiRuntimeStatusResponse.ActiveRuntime active = svc.getStatus().active();

    assertEquals(Boolean.FALSE, active.mmprojActive(), "a dropped projector must be visible");
    assertNull(
        active.chatProfile(),
        "a bare path carries no profile claim; reporting standard here is the lie this field"
            + " exists to prevent");
    assertNotNull(active.modelPath());
  }

  @Test
  @DisplayName("status omits the realized identity entirely when the engine is offline")
  void statusOmitsRealizedIdentityWhenOffline() throws Exception {
    setUpEnvironment();
    RuntimeActivationService svc = newService(OnlineAiService.unavailable());
    // First pass: no source bound at all (the bootstrap default).
    // Second pass: a source that reports "engine is down".
    for (int i = 0; i < 2; i++) {
      if (i == 1) {
        svc.setRealizedChatIdentitySource(() -> null);
      }
      AiRuntimeStatusResponse.ActiveRuntime active = svc.getStatus().active();
      assertNull(active.chatProfile());
      assertNull(active.modelPath());
      assertNull(
          active.mmprojActive(),
          "offline must be null, never FALSE: no engine and engine-without-vision are different"
              + " facts and one boolean cannot hold both");
    }
  }

  // ---------------------------------------------------------------- fixtures

  private void setUpEnvironment() {
    setProp("justsearch.home", tmp.toAbsolutePath().toString());
    setProp("justsearch.data.dir", tmp.toAbsolutePath().toString());
    setProp(MODELS_DIR_PROP, modelsDir().toString());
    clearProp(CHAT_PROFILE_PROP);
    clearProp(SERVER_EXE_PROP);
    clearProp(SERVER_EXE_SOURCE_PROP);
    prevStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeFromEnvironment();
  }

  private Path modelsDir() {
    return tmp.resolve("models").toAbsolutePath();
  }

  private Path createCompactModel() throws Exception {
    Path model = modelsDir().resolve(ChatModelProfile.COMPACT.modelFile());
    Files.createDirectories(model.getParent());
    Files.writeString(model, "gguf-bytes", StandardCharsets.UTF_8);
    return model;
  }

  private Path createVariantExe(String variantId) throws Exception {
    Path dir =
        tmp.resolve("native-bin").resolve("llama-server").resolve("variants").resolve(variantId);
    Files.createDirectories(dir);
    Path exe = dir.resolve("llama-server.exe");
    Files.writeString(exe, "not-a-real-exe", StandardCharsets.UTF_8);
    return exe;
  }

  private UiSettingsStore settingsStore() {
    return new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
  }

  private RuntimeActivationService newService(OnlineAiService onlineAi) throws Exception {
    return newService(onlineAi, settingsStore());
  }

  private RuntimeActivationService newService(OnlineAiService onlineAi, UiSettingsStore store) throws Exception {
    var fixture = new io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture(
        tmp.resolve("intent-" + intentFixtures.size()), store, ConfigStore.globalOrNull());
    intentFixtures.add(fixture);
    var settingsService = new SettingsServiceImpl(store, fixture.runner());
    return new RuntimeActivationService(processExecutors, onlineAi, store, null, null,
        null, null, null, null, settingsService);
  }

  private void commitCompetingSettings(UiSettingsStore store, int contextLength) {
    var snapshot = store.inspect();
    snapshot.settings().setContextLength(contextLength);
    var result = new SettingsServiceImpl(store, intentFixtures.get(intentFixtures.size() - 1).runner())
        .applyInternal(snapshot.settings(), snapshot.witness(), TestEngineContexts.internal());
    if (!result.response().success()) {
      throw new IllegalStateException("Competing settings write did not commit: " + result.response());
    }
  }

  private static RuntimeActivationService.SelfTestResult passingSelfTest() {
    return new RuntimeActivationService.SelfTestResult(
        "passed",
        65001,
        0L,
        128L * 1024 * 1024,
        128L * 1024 * 1024,
        List.of(),
        "12gb_plus",
        "nvml");
  }

  private void setProp(String key, String value) {
    prevProps.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private void clearProp(String key) {
    prevProps.putIfAbsent(key, System.getProperty(key));
    System.clearProperty(key);
  }

  private static AiRuntimeActivationStatus awaitDone(RuntimeActivationService svc) throws Exception {
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline) {
      AiRuntimeActivationStatus st = svc.getActivationStatus();
      if (!"running".equalsIgnoreCase(st.state)) {
        return st;
      }
      Thread.sleep(25);
    }
    fail("Timed out waiting for runtime activation to finish");
    return svc.getActivationStatus();
  }

  /**
   * Records which apply path activation took. The distinction is the whole point of the slice:
   * {@code applyChatProfileWithRuntime} carries (model, mmproj, profile-id) as one unit, while
   * {@code applyRuntimeOverrides} takes a bare path and defensively nulls the projector.
   */
  private static final class RecordingAiControl implements OnlineAiService, OnlineAiRuntimeControl {
    final List<ChatModelProfile> profilesApplied = new ArrayList<>();
    final List<OnlineAiRuntimeControl.RestartPolicy> profilePolicies = new ArrayList<>();
    final List<String> serverExecutables = new ArrayList<>();
    final List<Integer> profileGpuLayers = new ArrayList<>();
    final List<Integer> profileContexts = new ArrayList<>();
    final List<Integer> bareGpuLayers = new ArrayList<>();
    final List<Path> publishedServerExecutables = new ArrayList<>();
    final List<Integer> publishedGpuLayers = new ArrayList<>();
    final List<Boolean> publishedChatEnabled = new ArrayList<>();
    final List<String> barePathApplies = new ArrayList<>();
    UiSettingsStore observedStore;
    Runnable duringProfileApply;
    boolean failOnApplyProfile;

    @Override
    public void applyChatProfileWithRuntime(
        ChatModelProfile profile,
        String serverExecutable,
        Integer contextLength,
        Integer gpuLayers,
        RestartPolicy restartPolicy) {
      profilesApplied.add(profile);
      profilePolicies.add(restartPolicy);
      serverExecutables.add(serverExecutable);
      profileGpuLayers.add(gpuLayers);
      profileContexts.add(contextLength);
      var published = ConfigStore.global().get().ai();
      publishedServerExecutables.add(published.serverExe());
      publishedGpuLayers.add(published.gpuLayers());
      if (observedStore != null) {
        publishedChatEnabled.add(observedStore.load().getChatEnabled());
      }
      if (duringProfileApply != null) duringProfileApply.run();
      if (failOnApplyProfile) {
        throw new IllegalStateException("simulated engine restart failure");
      }
    }

    @Override
    public void applyRuntimeOverrides(
        String llmModelPath, Integer contextLength, Integer gpuLayers, RestartPolicy restartPolicy) {
      barePathApplies.add(llmModelPath);
      bareGpuLayers.add(gpuLayers);
    }

    @Override
    public DetachExternalServerResult detachExternalServer() {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }
  }
}
