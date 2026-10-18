/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.api.chat.SelfTestSession;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
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
  private String nativeFixtureOs;

  @org.junit.jupiter.api.BeforeEach
  void selectWindowsNativeFixture() {
    nativeFixtureOs = System.getProperty("os.name");
    System.setProperty("os.name", "Windows 11");
  }

  @org.junit.jupiter.api.AfterEach
  void restoreNativeFixtureOs() {
    if (nativeFixtureOs == null) System.clearProperty("os.name");
    else System.setProperty("os.name", nativeFixtureOs);
  }


  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  private static final String CHAT_PROFILE_PROP = "justsearch.chat.profile";
  private static final String MODELS_DIR_PROP = "justsearch.models.dir";
  private static final String SERVER_EXE_PROP = "justsearch.server.exe";
  private static final String SERVER_EXE_SOURCE_PROP = "justsearch.server.exe.source";

  @TempDir Path tmp;

  private final Map<String, String> prevProps = new HashMap<>();
  private ConfigStore prevStore;
  private final List<io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture> intentFixtures = new ArrayList<>();
  private boolean planningInstalled;

  @AfterEach
  void restore() throws Exception {
    for (var fixture : intentFixtures) fixture.close();
    if (planningInstalled) {
      ConfigStoreRebuilder.rememberDevicePlanner(null);
      ConfigStoreRebuilder.rememberAutoDetected(Map.of());
    }
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
  void compactToStandardActivationReplansEncoderCaps() throws Exception {
    assertActivationCaps(ChatModelProfile.COMPACT, ChatModelProfile.STANDARD);
  }

  @Test
  void standardToCompactActivationReplansEncoderCaps() throws Exception {
    assertActivationCaps(ChatModelProfile.STANDARD, ChatModelProfile.COMPACT);
  }

  @Test
  void unchangedProfileActivationDoesNotRecomposeEncoders() throws Exception {
    setUpGpuPlanning(ChatModelProfile.COMPACT);
    var store = settingsStore();
    var components = new RecordingComponents(store);
    var service = newService(OnlineAiService.unavailable(), store, components);
    var before = encoderCaps(ConfigStore.global().get());
    service.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    service.startActivate("cuda12", "compact");

    assertEquals("completed", awaitDone(service).state);
    assertEquals(before, encoderCaps(ConfigStore.global().get()));
    assertTrue(components.encoderCandidates.isEmpty(),
        "unchanged profile activation must not recompose encoders");
  }

  @Test
  void committedProfileRecoveryReplansEncoderCaps() throws Exception {
    setUpGpuPlanning(ChatModelProfile.COMPACT);
    var clock = java.time.Clock.systemUTC();
    var store = settingsStore();
    var db = tmp.resolve("interrupted-profile.db");
    var descriptor = io.justsearch.app.api.operations.OperationDescriptor.invocation(
        io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
        "settings.apply-internal-candidate", "{}", false);
    String key = io.justsearch.app.api.operations.OperationKeys.generate(clock);
    try (var operations = new io.justsearch.app.observability.operations.SqliteOperationStore(db)) {
      var preparation = new io.justsearch.app.api.operations.OperationStore.Preparation(
          java.util.UUID.randomUUID(), new io.justsearch.app.api.operations.OperationPreparedPayload(false,
              "{\"schema\":\"settings-candidate-v1\",\"chatProfileId\":\"standard\",\"forceGenerativeRefresh\":false}"));
      operations.savePreparation(key, descriptor, preparation);
      var row = operations.acceptPrepared(key, descriptor, TestEngineContexts.internal(),
          null, preparation.nonce()).record();
      assertTrue(operations.start(row.id()));
      assertTrue(operations.armSettingsRevision(row.id(), 0));
      var settings = new UiSettings();
      settings.setServerExecutablePath(createVariantExe("cuda12").toString());
      store.replacePrepared(store.prepare(settings, new SettingsWitness(1, key)));
    }
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    var expected = standardCaps();
    assertNotEquals(expected, encoderCaps(config.get()));
    var components = new RecordingComponents(store);
    try (var operations = new io.justsearch.app.observability.operations.SqliteOperationStore(db)) {
      var owner = new io.justsearch.app.services.settings.SettingsCommitCoordinator(store, config,
          () -> fail("recovery must not restart the application"),
          candidate -> io.justsearch.agent.api.registry.OperationResult.success("recovered"),
          () -> false, components);
      var runner = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(
          operations, clock, java.util.Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
              io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
      assertTrue(runner.reconcileSettingsAfterComposition());
      assertEquals(expected, encoderCaps(config.get()),
          "committed profile recovery must publish the selected profile's encoder caps");
      assertEquals(List.of(expected), components.encoderCandidates,
          "recovery must recompose changed encoder caps through the settings owner");
    }
  }

  @Test
  void settingsChatModelSelectionReplansEncoderCaps() throws Exception {
    setUpGpuPlanning(ChatModelProfile.COMPACT);
    clearProp(CHAT_PROFILE_PROP);
    var store = settingsStore();
    var settings = store.load();
    settings.setLlmModelPath(modelsDir().resolve(ChatModelProfile.COMPACT.modelFile()).toString());
    store.replacePrepared(store.prepare(settings, new SettingsWitness(0, null)));
    var components = new RecordingComponents(store);
    newService(OnlineAiService.unavailable(), store, components);
    assertNotEquals(standardCaps(), encoderCaps(ConfigStore.global().get()));

    var snapshot = store.inspect();
    snapshot.settings().setLlmModelPath(
        modelsDir().resolve(ChatModelProfile.STANDARD.modelFile()).toString());
    commitSettings(store, snapshot.settings(), snapshot.witness());

    assertEquals(standardCaps(), encoderCaps(ConfigStore.global().get()),
        "a settings chat model selection must install the standard encoder caps");
    assertEquals(1, components.encoderCandidates.size());
  }

  private void assertActivationCaps(ChatModelProfile from, ChatModelProfile to) throws Exception {
    setUpGpuPlanning(from);
    var store = settingsStore();
    var components = new RecordingComponents(store);
    var service = newService(OnlineAiService.unavailable(), store, components);
    var before = encoderCaps(ConfigStore.global().get());
    // A steady start with the target profile is the independent comparison, with the same device
    // and installed encoders. The activation keeps the launcher's original profile in place.
    setProp(CHAT_PROFILE_PROP, to.id());
    var expected = encoderCaps(ConfigStoreRebuilder.prepare(store.load()));
    setProp(CHAT_PROFILE_PROP, from.id());
    assertNotEquals(before, expected, "the two profiles reserve different chat footprints");
    service.setSelfTestOverrideForTest((exe, model) -> passingSelfTest());

    service.startActivate("cuda12", to.id());

    var status = awaitDone(service);
    assertEquals("completed", status.state, status.message);
    assertEquals(expected, encoderCaps(ConfigStore.global().get()),
        from.id() + " to " + to.id() + " activation must install the " + to.id() + " encoder caps");
    assertEquals(List.of(expected), components.encoderCandidates,
        "changed caps must reach encoder preparation through the settings owner");
    var snapshot = store.inspect();
    snapshot.settings().setExcludePatterns(List.of("*.unrelated"));
    commitSettings(store, snapshot.settings(), snapshot.witness());
    assertEquals(expected, encoderCaps(ConfigStore.global().get()),
        "an unrelated settings commit must retain the activated profile's plan");
    assertEquals(1, components.encoderCandidates.size(),
        "an equal plan must not recompose encoders");
  }

  private void setUpGpuPlanning(ChatModelProfile initial) throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    var packages = new ArrayList<io.justsearch.configuration.model.ModelPackage>();
    for (var profile : List.of(ChatModelProfile.STANDARD, ChatModelProfile.COMPACT)) {
      var model = modelsDir().resolve(profile.modelFile());
      Files.createDirectories(model.getParent());
      Files.writeString(model, "fixture");
      Files.writeString(modelsDir().resolve(profile.mmprojFile()), "projector");
      String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest
          .getInstance("SHA-256").digest(Files.readAllBytes(model)));
      io.justsearch.app.inference.ChatArtifactIdentity.rememberWritten(model, Files.size(model), digest);
      var variant = new io.justsearch.configuration.model.ModelVariant(model.getFileName().toString(),
          io.justsearch.configuration.model.ModelPrecision.GGUF,
          io.justsearch.configuration.model.ExecutionProvider.LLAMA_SERVER, digest, Files.size(model), null);
      packages.add(new io.justsearch.configuration.model.ModelPackage(profile.id(), "fixture", "fixture",
          modelsDir().relativize(model.getParent()).toString(), List.of(variant), List.of(), 0, null));
    }
    setProp(CHAT_PROFILE_PROP, initial.id());
    setProp("justsearch.context.size", "32768");
    ConfigStoreRebuilder.rememberAutoDetected(Map.of("justsearch.gpu.layers", "99",
        "justsearch.gpu.enabled", "true"));
    planningInstalled = true;
    var device = new io.justsearch.app.services.gpu.CudaDevice(0, java.util.Optional.empty(),
        java.util.Optional.empty(), java.util.Optional.empty(), 12_282L * 1024 * 1024);
    var facts = new io.justsearch.app.services.gpu.DeviceFacts(
        io.justsearch.app.services.gpu.DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080,
        List.of(device), java.util.Optional.of(device));
    var weights = Map.of(io.justsearch.ort.EncoderRole.EMBEDDING, 598.3,
        io.justsearch.ort.EncoderRole.SPLADE, 474.2, io.justsearch.ort.EncoderRole.NER, 257.2,
        io.justsearch.ort.EncoderRole.RERANKER, 600.3);
    var manifest = new io.justsearch.configuration.model.ModelRegistry(2, "fixture identities", packages);
    var planner = io.justsearch.app.services.gpu.GpuProfileResolver.planner(() -> facts, ignored -> weights);
    ConfigStoreRebuilder.rememberDevicePlanner(config -> {
      try (var registry = org.mockito.Mockito.mockStatic(
          io.justsearch.configuration.model.ModelRegistryLoader.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
        registry.when(() -> io.justsearch.configuration.model.ModelRegistryLoader
            .loadFromClasspath("ai/model-registry.v2.json")).thenReturn(manifest);
        return planner.plan(config);
      }
    });
  }

  private static List<Integer> encoderCaps(ResolvedConfig config) {
    return List.of(config.ai().embedding().gpuMemMb(), config.ai().splade().gpuMemMb(),
        config.ai().reranker().gpuMemMb());
  }

  private List<Integer> standardCaps() {
    String previous = System.getProperty(CHAT_PROFILE_PROP);
    setProp(CHAT_PROFILE_PROP, "standard");
    try { return encoderCaps(ConfigStoreRebuilder.prepare(new UiSettings())); }
    finally {
      if (previous == null) clearProp(CHAT_PROFILE_PROP);
      else setProp(CHAT_PROFILE_PROP, previous);
    }
  }

  private void commitSettings(UiSettingsStore store, UiSettings settings, SettingsWitness witness) {
    var result = new SettingsServiceImpl(store, intentFixtures.getLast().runner())
        .applyInternal(settings, witness, TestEngineContexts.internal());
    assertTrue(result.response().success(), "settings commit: " + result.response());
  }

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

  // ---------------------------------------------------------------- the controller session

  // Spec A step 5: the self-test and the commit run inside one controller session.
  @Test
  void selfTestAndCommitRunInsideOneControllerSession() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    UiSettingsStore store = settingsStore();
    RecordingComponents components = new RecordingComponents(store);
    List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
    ChatHolds holds = org.mockito.Mockito.mock(ChatHolds.class);
    org.mockito.Mockito.when(holds.beginSelfTest("runtime-variant-activation")).thenAnswer(call -> {
      events.add("session-begun");
      return new SelfTestSession<SelfTestSpec, SelfTestRun>() {
        @Override public SelfTestRun run(SelfTestSpec spec) {
          throw new AssertionError("the override stands in for the candidate here");
        }
        @Override public void close() {
          events.add("session-closed after " + components.installedProfiles.size() + " commit");
        }
      };
    });
    RuntimeActivationService svc = newService(OnlineAiService.unavailable(), store, components, holds);
    svc.setSelfTestOverrideForTest((exe, model) -> {
      events.add("self-test");
      return passingSelfTest();
    });

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("completed", st.state, "message=" + st.message);
    assertEquals(List.of("session-begun", "self-test", "session-closed after 1 commit"), events,
        "the main server is stopped before the self-test and stays stopped through the commit");
  }

  // Spec A step 5: a refused session fails the activation before any self-test or commit.
  @Test
  void aRefusedSessionFailsTheActivationBeforeAnySelfTestOrCommit() throws Exception {
    setUpEnvironment();
    createVariantExe("cuda12");
    createCompactModel();
    UiSettingsStore store = settingsStore();
    RecordingComponents components = new RecordingComponents(store);
    ChatHolds holds = org.mockito.Mockito.mock(ChatHolds.class);
    org.mockito.Mockito.when(holds.beginSelfTest("runtime-variant-activation"))
        .thenThrow(new ChatRefusal(RefusalReason.BUSY, java.util.Optional.empty(), false));
    RuntimeActivationService svc = newService(OnlineAiService.unavailable(), store, components, holds);
    svc.setSelfTestOverrideForTest((exe, model) -> {
      fail("no self-test without a session");
      return null;
    });

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertEquals("failed", st.state);
    assertEquals("SELF_TEST_FAILED", st.errorCode);
    assertTrue(st.message.contains("BUSY"), st.message);
    assertEquals(List.of(), components.installedProfiles, "nothing is committed");
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
    assertEquals(List.of(0), components.preparedGpuLayers);
    assertNull(store.load().configuredGpuLayers(), "activation keeps the automatic preference");
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
    assertEquals(List.of(0), components.preparedGpuLayers);
    assertNull(store.load().configuredGpuLayers(), "operator source does not become a stored preference");
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
    return newService(onlineAi, store, requestedComponents, null);
  }

  private RuntimeActivationService newService(OnlineAiService onlineAi, UiSettingsStore store,
      RecordingComponents requestedComponents, ChatHolds holds) throws Exception {
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
        null, null, null, holds, null, settingsService);
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
    if (!prevProps.containsKey(key)) prevProps.put(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private void clearProp(String key) {
    if (!prevProps.containsKey(key)) prevProps.put(key, System.getProperty(key));
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
    final List<List<Integer>> encoderCandidates = new ArrayList<>();
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
      if (affected.containsKey("encoders")) encoderCandidates.add(encoderCaps(desired));
      return new Prepared() {
        @Override public java.util.Optional<io.justsearch.app.api.settings.QueryRoleSelection> queryRoleSelection() {
          if (!affected.containsKey("encoders")) return java.util.Optional.empty();
          var disabled = io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled();
          return java.util.Optional.of(new io.justsearch.app.api.settings.QueryRoleSelection(disabled, disabled));
        }
        @Override public void includeObservation(
            io.justsearch.core.component.EngineComponentSnapshot.Component observation) {
          throw new UnsupportedOperationException("Chat-profile fixture has no generation projection");
        }
        @Override public void withOwnerLocks(Runnable publication) { publication.run(); }
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
