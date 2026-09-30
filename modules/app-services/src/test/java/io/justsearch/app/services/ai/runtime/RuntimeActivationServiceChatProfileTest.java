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
 *       {@code applyChatProfile} instead, and must never reach the bare-path apply.
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

  @TempDir Path tmp;

  private final Map<String, String> prevProps = new HashMap<>();
  private ConfigStore prevStore;

  @AfterEach
  void restore() {
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
  @DisplayName("profile activation applies the pair, self-tests the profile model, leaves settings alone")
  void profileActivationAppliesPairWithoutWritingSettings() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    Path compact = createCompactModel();

    Path operatorModel = tmp.resolve("operator-9b.gguf");
    Files.writeString(operatorModel, "gguf", StandardCharsets.UTF_8);
    UiSettingsStore store = settingsStore();
    UiSettings s = store.load();
    s.setLlmModelPath(operatorModel.toAbsolutePath().toString());
    store.save(s);

    RecordingAiControl control = new RecordingAiControl();
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
        "a profile activation must go through applyChatProfile so the projector rides along");
    assertEquals(
        List.of(OnlineAiRuntimeControl.RestartPolicy.RESTART_ALWAYS),
        control.profilePolicies,
        "same restart policy the pre-842 settings path used");
    assertTrue(
        control.barePathApplies.isEmpty(),
        "the bare-path apply clears the profile claim and nulls the mmproj, so it must not be"
            + " reached: "
            + control.barePathApplies);
    assertEquals(
        operatorModel.toAbsolutePath().toString(),
        store.load().getLlmModelPath(),
        "a profile choice is not a stored user path; llmModelPath must survive untouched");
    assertEquals(
        "compact",
        System.getProperty(CHAT_PROFILE_PROP),
        "a later same-JVM config rebuild must resolve the same pair the engine just switched to");
  }

  @Test
  @DisplayName("apply failure rolls the chat-profile sysprop back to absent")
  void applyFailureRollsBackChatProfileProp() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    assertNull(System.getProperty(CHAT_PROFILE_PROP), "precondition: no profile claim exists");

    RecordingAiControl control = new RecordingAiControl();
    control.failOnApplyProfile = true;
    RuntimeActivationService svc = newService(control);
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("failed", st.state);
    assertEquals("RUNTIME_ACTIVATION_FAILED", st.errorCode, "message=" + st.message);
    assertNull(
        System.getProperty(CHAT_PROFILE_PROP),
        "rollback must restore ABSENCE, not a blank claim: a blank value resolves to the default"
            + " profile and would leave the JVM half-switched");
  }

  @Test
  @DisplayName("apply failure restores a pre-existing chat-profile sysprop value")
  void applyFailureRestoresPreviousChatProfileProp() throws Exception {
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
    prevProps.putIfAbsent(CHAT_PROFILE_PROP, System.getProperty(CHAT_PROFILE_PROP));
    System.clearProperty(CHAT_PROFILE_PROP);
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

  private RuntimeActivationService newService(OnlineAiService onlineAi) {
    return newService(onlineAi, settingsStore());
  }

  private RuntimeActivationService newService(OnlineAiService onlineAi, UiSettingsStore store) {
    return new RuntimeActivationService(processExecutors, onlineAi, store, null, null);
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
   * {@code applyChatProfile} carries (model, mmproj, profile-id) as one unit, while
   * {@code applyRuntimeOverrides} takes a bare path and defensively nulls the projector.
   */
  private static final class RecordingAiControl implements OnlineAiService, OnlineAiRuntimeControl {
    final List<ChatModelProfile> profilesApplied = new ArrayList<>();
    final List<OnlineAiRuntimeControl.RestartPolicy> profilePolicies = new ArrayList<>();
    final List<String> barePathApplies = new ArrayList<>();
    boolean failOnApplyProfile;

    @Override
    public void applyChatProfile(ChatModelProfile profile, RestartPolicy restartPolicy) {
      profilesApplied.add(profile);
      profilePolicies.add(restartPolicy);
      if (failOnApplyProfile) {
        throw new IllegalStateException("simulated engine restart failure");
      }
    }

    @Override
    public void applyRuntimeOverrides(
        String llmModelPath, Integer contextLength, Integer gpuLayers, RestartPolicy restartPolicy) {
      barePathApplies.add(llmModelPath);
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
