/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.api.AiRuntimeActivationStatus;
import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.api.inference.RealizedChatIdentity;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  @DisplayName("profile activation passes a transient target and installs it after settings commit")
  void profileActivationAppliesCombinedTargetWithoutPersistingModelPath() throws Exception {
    setUpEnvironment();
    Path variantExe = createVariantExe("cuda12");
    Path compact = createCompactModel();

    Path operatorModel = tmp.resolve("operator-9b.gguf");
    Files.writeString(operatorModel, "gguf", StandardCharsets.UTF_8);
    UiSettingsStore store = settingsStore();
    UiSettings s = store.load();
    s.setLlmModelPath(operatorModel.toAbsolutePath().toString());
    store.replacePrepared(store.prepare(s, new SettingsWitness(0, null)));

    RecordingComponents components = new RecordingComponents(store);
    RuntimeActivationService svc = newService(OnlineAiService.unavailable(), store, components);
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
    assertEquals(List.of(ChatModelProfile.COMPACT), components.preparedProfiles);
    assertEquals(List.of(ChatModelProfile.COMPACT), components.installedProfiles);
    assertEquals(
        List.of(new SettingsCandidateContext(ChatModelProfile.COMPACT)),
        components.preparedContexts);
    assertEquals(
        List.of(new SettingsCandidateContext(ChatModelProfile.COMPACT)),
        components.installedContexts);
    assertEquals(List.of(variantExe.toAbsolutePath().toString()), components.preparedExecutables);
    assertEquals(List.of(99), components.preparedGpuLayers);
    assertEquals(
        List.of(Boolean.TRUE), components.chatEnabledAtInstall,
        "the component target is installed only after the durable settings replacement");
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
  @DisplayName("explicit operator model path refuses profile before self-test or settings acceptance")
  void operatorModelPathCannotBeReplacedByTransientProfile() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    Path operatorModel = tmp.resolve("operator-choice.gguf");
    Files.writeString(operatorModel, "operator-model", StandardCharsets.UTF_8);
    setProp("justsearch.llm.model_path", operatorModel.toString());
    UiSettingsStore store = settingsStore();
    var before = store.inspect().witness();
    RuntimeActivationService svc = newService(OnlineAiService.unavailable(), store);
    svc.setSelfTestOverrideForTest((exe, model) -> {
      fail("profile self-test must not override an operator model path");
      return null;
    });

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus status = awaitDone(svc);

    assertEquals("failed", status.state);
    assertEquals("MODEL_OVERRIDE_LOCKED", status.errorCode);
    assertEquals(before, store.inspect().witness());
    assertEquals(operatorModel.toString(), ConfigStore.global().get().ai().llmModelPath().toString());
  }

  @Test
  @DisplayName("an operator executable matching the requested variant permits profile activation")
  void matchingOperatorExecutablePermitsActivation() throws Exception {
    setUpEnvironment();
    Path variantExe = createVariantExe("cuda12");
    createCompactModel();
    setProp(SERVER_EXE_PROP, variantExe.toString());
    UiSettingsStore store = settingsStore();
    RecordingComponents components = new RecordingComponents(store);
    RuntimeActivationService svc = newService(OnlineAiService.unavailable(), store, components);
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus status = awaitDone(svc);

    assertEquals("completed", status.state, "message=" + status.message);
    assertEquals(List.of(ChatModelProfile.COMPACT), components.installedProfiles);
    assertEquals(variantExe.toString(), ConfigStore.global().get().ai().serverExe().toString());
  }

  @Test
  @DisplayName("an operator executable differing from the requested variant blocks activation")
  void differentOperatorExecutableBlocksActivation() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    Path operatorExe = tmp.resolve("operator-server.exe");
    Files.writeString(operatorExe, "operator-server", StandardCharsets.UTF_8);
    setProp(SERVER_EXE_PROP, operatorExe.toString());
    UiSettingsStore store = settingsStore();
    SettingsWitness before = store.inspect().witness();
    RuntimeActivationService svc = newService(OnlineAiService.unavailable(), store);
    svc.setSelfTestOverrideForTest((exe, model) -> {
      fail("self-test must not run when the operator selected another executable");
      return null;
    });

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus status = awaitDone(svc);

    assertEquals("failed", status.state);
    assertTrue(status.message.contains("Server executable override is locked"));
    assertEquals(before, store.inspect().witness());
    assertEquals(operatorExe.toString(), ConfigStore.global().get().ai().serverExe().toString());
  }

  @Test
  @DisplayName("precommit component refusal leaves original settings and config")
  void precommitComponentFailureLeavesOriginalSettingsAndConfig() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    assertNull(System.getProperty(CHAT_PROFILE_PROP), "precondition: no profile claim exists");

    UiSettingsStore store = settingsStore();
    UiSettings original = store.load();
    original.setContextLength(4096);
    store.replacePrepared(store.prepare(original, new SettingsWitness(0, null)));
    var originalSettings = store.load();
    RecordingComponents components = new RecordingComponents(store);
    components.failValidation = true;
    RuntimeActivationService svc = newService(OnlineAiService.unavailable(), store, components);
    var originalConfig = ConfigStore.global().get().ai();
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("failed", st.state);
    assertEquals("RUNTIME_ACTIVATION_FAILED", st.errorCode, "message=" + st.message);
    UiSettings restored = store.load();
    assertEquals(originalSettings.getChatEnabled(), restored.getChatEnabled());
    assertEquals(originalSettings.getServerExecutablePath(), restored.getServerExecutablePath());
    assertEquals(originalSettings.getGpuLayers(), restored.getGpuLayers());
    assertEquals(originalSettings.getContextLength(), restored.getContextLength());
    assertEquals(1, components.preparedProfiles.size());
    assertEquals(1, components.abortedCount, "the refused candidate must be disposed");
    assertEquals(0, components.installedProfiles.size(), "no component installs before commit");
    assertEquals(originalConfig.serverExe(), ConfigStore.global().get().ai().serverExe());
    assertEquals(originalConfig.gpuLayers(), ConfigStore.global().get().ai().gpuLayers());
    assertEquals(originalConfig.contextSize(), ConfigStore.global().get().ai().contextSize());
    assertNull(
        System.getProperty(CHAT_PROFILE_PROP),
        "compensation must not invent a profile property");
  }

  @Test
  @DisplayName("profile activation never promotes its transient target to a JVM property")
  void profileActivationLeavesPreviousChatProfilePropUnchanged() throws Exception {
    setUpEnvironment();
    setProp(CHAT_PROFILE_PROP, "standard");
    createVariantExe("cuda12");
    createCompactModel();

    RuntimeActivationService svc = newService(OnlineAiService.unavailable());
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("completed", st.state, "message=" + st.message);
    assertEquals("standard", System.getProperty(CHAT_PROFILE_PROP));
    assertNull(System.getProperty(SERVER_EXE_PROP));
    assertNull(System.getProperty(SERVER_EXE_SOURCE_PROP));
  }

  @Test
  @DisplayName("an intervening self-test settings write refuses activation before runtime effect")
  void selfTestInterveningWriteConflictsBeforeRuntimeApply() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    UiSettingsStore store = settingsStore();
    RecordingComponents components = new RecordingComponents(store);
    RuntimeActivationService svc = newService(OnlineAiService.unavailable(), store, components);
    svc.setSelfTestOverrideForTest(
        (exe, model) -> {
          commitCompetingSettings(store, 12288);
          return passingSelfTest();
        });

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("failed", st.state);
    assertEquals("RUNTIME_ACTIVATION_FAILED", st.errorCode);
    assertTrue(components.preparedProfiles.isEmpty(), "stale candidate must fail before preparation");
    assertTrue(components.installedProfiles.isEmpty());
    assertEquals(12288, store.load().getContextLength());
    assertEquals(12288, ConfigStore.global().get().ai().contextSize());
  }

  @Test
  @DisplayName("a concurrent settings write remains authoritative while activation is preparing")
  void concurrentSettingsWriteRemainsAuthoritativeDuringPreparation() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    UiSettingsStore store = settingsStore();
    RecordingComponents components = new RecordingComponents(store);
    RuntimeActivationService svc = newService(OnlineAiService.unavailable(), store, components);

    // The settings writer races the activation's witness after self-test but before its one
    // settings attempt. The newer committed value must survive and the profile target must never
    // reach preparation or installation.
    svc.setSelfTestOverrideForTest(
        (exe, model) -> {
          commitCompetingSettings(store, 24576);
          return passingSelfTest();
        });

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("failed", st.state);
    assertEquals("RUNTIME_ACTIVATION_FAILED", st.errorCode, "message=" + st.message);
    assertEquals(24576, store.load().getContextLength(), "newer settings must survive");
    assertEquals(24576, ConfigStore.global().get().ai().contextSize());
    assertTrue(components.preparedProfiles.isEmpty());
    assertTrue(components.installedProfiles.isEmpty());
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
    store.replacePrepared(store.prepare(s, new SettingsWitness(0, null)));

    RecordingComponents components = new RecordingComponents(store);
    RuntimeActivationService svc = newService(OnlineAiService.unavailable(), store, components);
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    svc.startActivate("cuda12");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("completed", st.state, "message=" + st.message);
    assertTrue(
        components.preparedContexts.stream().noneMatch(SettingsCandidateContext::hasChatProfile),
        "an activation without chatProfile must not carry a transient profile target");
    assertTrue(components.installedContexts.stream().noneMatch(SettingsCandidateContext::hasChatProfile));
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
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
    var components = new RecordingComponents(store);
    var svc = newService(OnlineAiService.unavailable(), store, components);
    svc.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());
    svc.startActivate("cuda12", "compact");
    assertEquals("completed", awaitDone(svc).state);
    assertEquals(List.of(0), components.desiredGpuLayers);
    assertEquals(List.of(16384), components.desiredContexts);
    assertEquals(List.of(99), components.preparedGpuLayers);
    assertEquals(99, store.load().getGpuLayers(), "operator source overrides the stored preference");
  }

  @Test
  void deactivationAppliesThePublishedOperatorGpuOverride() throws Exception {
    setUpEnvironment();
    setProp("justsearch.gpu.layers", "20");
    Path baseline = tmp.resolve("native-bin/llama-server/llama-server.exe");
    Files.createDirectories(baseline.getParent());
    Files.writeString(baseline, "fixture");
    var components = new RecordingComponents(settingsStore());
    var svc = newService(OnlineAiService.unavailable(), components.store, components);
    svc.startDeactivate();
    assertEquals("completed", awaitDone(svc).state);
    assertEquals(List.of(20), components.desiredGpuLayers);
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
    return newService(onlineAi, store, null);
  }

  private RuntimeActivationService newService(OnlineAiService onlineAi, UiSettingsStore store,
      RecordingComponents requestedComponents) throws Exception {
    // A running Engine starts from the fully resolved settings snapshot. The environment-only
    // helper omits YAML composites such as search.facets.fields, making the first reconfigure
    // look like an unrelated unknown-key change.
    ConfigStore.setGlobal(new ConfigStore(ConfigStoreRebuilder.prepare(store.load())));
    RecordingComponents components = requestedComponents == null
        ? new RecordingComponents(store) : requestedComponents;
    var fixture = new io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture(
        tmp.resolve("intent-" + intentFixtures.size()), store, ConfigStore.globalOrNull(), components);
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

  /** Records the candidate context and prepared/installed lifecycle without an inference service. */
  private static final class RecordingComponents implements SettingsComponentComposer {
    final UiSettingsStore store;
    final List<SettingsCandidateContext> preparedContexts = new ArrayList<>();
    final List<SettingsCandidateContext> installedContexts = new ArrayList<>();
    final List<ChatModelProfile> preparedProfiles = new ArrayList<>();
    final List<ChatModelProfile> installedProfiles = new ArrayList<>();
    final List<String> preparedExecutables = new ArrayList<>();
    final List<Integer> preparedGpuLayers = new ArrayList<>();
    final List<Integer> desiredGpuLayers = new ArrayList<>();
    final List<Integer> desiredContexts = new ArrayList<>();
    final List<Boolean> chatEnabledAtInstall = new ArrayList<>();
    boolean failValidation;
    int abortedCount;

    RecordingComponents(UiSettingsStore store) {
      this.store = store;
    }

    @Override
    public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
        Map<String, java.util.Set<String>> affected) {
      return prepare(candidate, desired, affected, SettingsCandidateContext.NONE);
    }

    @Override
    public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
        Map<String, java.util.Set<String>> affected, SettingsCandidateContext context) {
      preparedContexts.add(context);
      if (context.hasChatProfile()) preparedProfiles.add(context.chatProfile());
      preparedExecutables.add(candidate.getServerExecutablePath());
      preparedGpuLayers.add(candidate.getGpuLayers());
      desiredGpuLayers.add(desired.ai().gpuLayers());
      desiredContexts.add(desired.ai().contextSize());
      return new Prepared() {
        @Override
        public void validate() {
          if (failValidation) throw new IllegalStateException("simulated precommit refusal");
        }

        @Override
        public void install() {
          installedContexts.add(context);
          if (context.hasChatProfile()) installedProfiles.add(context.chatProfile());
          chatEnabledAtInstall.add(store.load().getChatEnabled());
        }

        @Override public void notifyObservers() {}

        @Override public void retire() {}

        @Override
        public void abort() {
          abortedCount++;
        }
      };
    }
  }
}
