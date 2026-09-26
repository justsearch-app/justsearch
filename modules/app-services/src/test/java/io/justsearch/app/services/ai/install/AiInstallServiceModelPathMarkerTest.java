/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.ModelPathSource;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.Method;
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
 * Tempdoc 842 (S2) reached structurally — tempdoc 883 §C.5c residue, #605 review S1.
 *
 * <p>842's problem was real: {@code applySettings} copied the installed chat-model path into
 * {@code justsearch.llm.model_path}, where it resolved at ordinal 500 and became
 * indistinguishable from an operator {@code -D}. Every reader then classified the product's own
 * path as operator-owned, and 842 §2.3's rule ("system-owned paths are re-derivable and may be
 * superseded by a profile; an operator path is sacred") would have made a compact-profile switch
 * silently inert on exactly the machines that had just run Install AI. 842 fixed it by writing a
 * {@code .source} marker beside the value.
 *
 * <p>883 removes the cause instead of labelling it. The installer saves {@code settings.json} and
 * rebuilds the {@code ConfigStore}; that alone delivers the path at ordinal 300, where it is
 * already, by the ordinal chain, a re-derivable settings value. The sysprop copy — and therefore
 * the marker that existed to correct it — is gone.
 *
 * <p>These tests assert the new mechanism and the OLD intent: the installer's path must never
 * report as {@code jvm_arg}, and an operator's own {@code -D} must survive an install untouched.
 */
final class AiInstallServiceModelPathMarkerTest {

  private static final String MODEL_PATH_PROP = "justsearch.llm.model_path";

  @TempDir Path tmp;

  private final Map<String, String> prevProps = new HashMap<>();
  private RuntimeIntentTestFixture fixture;
  private ConfigStore previousConfigStore;
  private ConfigStore configStore;

  @AfterEach
  void restore() {
    if (fixture != null) {
      fixture.close();
      fixture = null;
    }
    TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
    previousConfigStore = null;
    configStore = null;
    for (var e : prevProps.entrySet()) {
      if (e.getValue() == null) {
        System.clearProperty(e.getKey());
      } else {
        System.setProperty(e.getKey(), e.getValue());
      }
    }
    prevProps.clear();
  }

  @Test
  @DisplayName("applySettings persists the installed path and writes NO system property for it")
  void applySettingsPersistsWithoutPromotingToASysprop() throws Exception {
    clearProp(MODEL_PATH_PROP);
    clearProp(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH);
    clearProp("justsearch.models.dir");

    Path chatModel = tmp.resolve("models").resolve("chat").resolve("model.gguf");
    Files.createDirectories(chatModel.getParent());
    Files.writeString(chatModel, "gguf-bytes", StandardCharsets.UTF_8);

    UiSettingsStore store = writableStore();
    AiInstallService svc = newService(store, null);

    invokeApplySettings(svc, registryWithChatModel("model.gguf"), planFor(DownloadProfile.GPU_FULL));

    String installed = chatModel.toAbsolutePath().toString();
    assertEquals(
        installed,
        store.load().getLlmModelPath(),
        "precondition: the installer still persists the path it just installed");
    assertNull(
        System.getProperty(MODEL_PATH_PROP),
        "the settings row is the whole delivery — a sysprop copy would resolve at ordinal 500 and"
            + " report a product-written value as jvm_arg");
    assertNull(
        System.getProperty(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH),
        "and with no promotion there is nothing for a .source marker to correct");
  }

  @Test
  @DisplayName("the installed path resolves as settings.json at ordinal 300, never jvm_arg")
  void installedPathResolvesAsSettingsJson() throws Exception {
    clearProp(MODEL_PATH_PROP);
    clearProp(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH);
    clearProp("justsearch.models.dir");

    Path chatModel = tmp.resolve("models").resolve("chat").resolve("model.gguf");
    Files.createDirectories(chatModel.getParent());
    Files.writeString(chatModel, "gguf-bytes", StandardCharsets.UTF_8);

    UiSettingsStore store = writableStore();
    AiInstallService svc = newService(store, null);
    invokeApplySettings(svc, registryWithChatModel("model.gguf"), planFor(DownloadProfile.GPU_FULL));

    // What ConfigStoreRebuilder.rebuild does with the row applySettings just saved.
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    ConfigStoreRebuilder.contributeUiSettings(builder, store.load());
    builder.contributeEnvRegistry();
    ConfigResolution resolution = builder.build().resolution(MODEL_PATH_PROP);

    assertEquals(chatModel.toAbsolutePath().toString(), resolution.value());
    assertEquals(
        "settings.json",
        resolution.sourceName(),
        "an installer-written path is a settings value and must say so");
    assertEquals(ResolvedConfigBuilder.ORDINAL_SETTINGS_JSON, resolution.sourceOrdinal());
    assertNotEquals(
        "jvm_arg",
        resolution.sourceName(),
        "reporting jvm_arg is the precedence lie 842 needed a marker to un-tell");
  }

  @Test
  @DisplayName("an operator-set model path survives an install untouched, and still wins")
  void operatorPathIsStillRespected() throws Exception {
    clearProp(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH);
    clearProp("justsearch.models.dir");
    String operatorValue = tmp.resolve("operator-choice.gguf").toAbsolutePath().toString();
    setProp(MODEL_PATH_PROP, operatorValue);

    Path chatModel = tmp.resolve("models").resolve("chat").resolve("model.gguf");
    Files.createDirectories(chatModel.getParent());
    Files.writeString(chatModel, "gguf-bytes", StandardCharsets.UTF_8);

    UiSettingsStore store = writableStore();
    AiInstallService svc = newService(store, null);

    invokeApplySettings(svc, registryWithChatModel("model.gguf"), planFor(DownloadProfile.GPU_FULL));

    assertEquals(
        operatorValue,
        System.getProperty(MODEL_PATH_PROP),
        "the installer writes no sysprop at all, so it cannot clobber an operator's -D");
    assertNull(
        System.getProperty(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH),
        "an unmarked value is an operator value and must stay one");

    // And the chain — not a first-writer-wins guard — is what keeps the operator on top.
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    ConfigStoreRebuilder.contributeUiSettings(builder, store.load());
    builder.contributeEnvRegistry();
    ConfigResolution resolution = builder.build().resolution(MODEL_PATH_PROP);
    assertEquals(operatorValue, resolution.value());
    assertEquals("jvm_arg", resolution.sourceName(), "here jvm_arg is the truth: a human set it");
  }

  @Test
  @DisplayName("the install commit publishes the operator target without a second runtime apply")
  void committedSettingsDoNotTriggerPostCommitRuntimeApply() throws Exception {
    clearProp(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH);
    clearProp("justsearch.models.dir");
    String operatorModel = tmp.resolve("operator-choice.gguf").toAbsolutePath().toString();
    setProp(MODEL_PATH_PROP, operatorModel);
    setProp("justsearch.context.size", "12288");
    setProp("justsearch.gpu.layers", "0");

    Path chatModel = tmp.resolve("models").resolve("chat").resolve("model.gguf");
    Files.createDirectories(chatModel.getParent());
    Files.writeString(chatModel, "gguf-bytes", StandardCharsets.UTF_8);

    UiSettingsStore store = writableStore();
    UiSettings settings = store.load();
    settings.setContextLength(8192);
    settings.setGpuLayers(23);
    store.replacePrepared(store.prepare(settings, new SettingsWitness(0, null)));
    OnlineAiService onlineAi = mock(OnlineAiService.class);
    AiInstallService svc = newService(store, onlineAi);
    var before = store.inspect().witness();

    invokeApplySettings(svc, registryWithChatModel("model.gguf"), planFor(DownloadProfile.GPU_FULL));

    verifyNoInteractions(onlineAi);
    assertEquals(operatorModel, configStore.get().ai().llmModelPath().toString());
    assertEquals(12288, configStore.get().ai().contextSize());
    assertEquals(0, configStore.get().ai().gpuLayers());
    assertEquals(before.acceptedRevision() + 1, store.inspect().witness().acceptedRevision());
  }

  @Test
  @DisplayName("same-path chat repair forces one prepared generative refresh")
  void samePathChatRepairUsesPreparedRefreshWithoutSecondRuntimeApply() throws Exception {
    clearProp(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH);
    clearProp("justsearch.models.dir");

    Path chatModel = tmp.resolve("models").resolve("chat").resolve("model.gguf");
    Files.createDirectories(chatModel.getParent());
    Files.writeString(chatModel, "repaired-gguf-bytes", StandardCharsets.UTF_8);
    String installed = chatModel.toAbsolutePath().toString();

    UiSettingsStore store = writableStore();
    UiSettings initial = store.load();
    initial.setLlmModelPath(installed);
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));

    RecordingComponents components = new RecordingComponents();
    OnlineAiService onlineAi = mock(OnlineAiService.class);
    AiInstallService svc = newService(store, onlineAi, components);
    var before = store.inspect().witness();

    invokeApplySettings(svc, registryWithChatModel("model.gguf"), planFor(DownloadProfile.GPU_FULL));

    assertEquals(installed, store.load().getLlmModelPath());
    assertEquals(
        before.acceptedRevision() + 1,
        store.inspect().witness().acceptedRevision(),
        "same-path repair still records exactly one accepted settings revision");
    assertEquals(1, components.preparedContexts.size());
    assertEquals(new SettingsCandidateContext(null, true), components.preparedContexts.getFirst());
    assertEquals(
        java.util.Set.of("modelRefresh"),
        components.preparedAffected.getFirst().get("generative"),
        "same-path repair must force the generative owner through the prepared refresh scope");
    assertEquals(1, components.installCount);
    verifyNoInteractions(onlineAi);
  }

  @Test
  @DisplayName("repair of a settings model hidden by an operator choice leaves the operator runtime alone")
  void hiddenChatRepairDoesNotRefreshOperatorModel() throws Exception {
    clearProp(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH);
    clearProp("justsearch.models.dir");
    Path operator = tmp.resolve("operator-choice.gguf");
    setProp(MODEL_PATH_PROP, operator.toString());
    Path chatModel = tmp.resolve("models").resolve("chat").resolve("model.gguf");
    Files.createDirectories(chatModel.getParent());
    Files.writeString(chatModel, "repaired-default");
    UiSettingsStore store = writableStore();
    UiSettings initial = store.load();
    initial.setLlmModelPath(chatModel.toAbsolutePath().toString());
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
    RecordingComponents components = new RecordingComponents();
    OnlineAiService onlineAi = mock(OnlineAiService.class);
    AiInstallService svc = newService(store, onlineAi, components);

    invokeApplySettings(svc, registryWithChatModel("model.gguf"), planFor(DownloadProfile.GPU_FULL));

    assertEquals(1L, store.inspect().witness().acceptedRevision());
    assertEquals(operator.toString(), configStore.get().ai().llmModelPath().toString());
    assertEquals(0, components.installCount);
    assertEquals(List.of(), components.preparedContexts);
    verifyNoInteractions(onlineAi);
  }

  // ---------------------------------------------------------------- fixtures

  private UiSettingsStore writableStore() {
    return new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
  }

  private AiInstallService newService(UiSettingsStore store, OnlineAiService onlineAi)
      throws Exception {
    return newService(store, onlineAi, null);
  }

  private AiInstallService newService(
      UiSettingsStore store, OnlineAiService onlineAi, SettingsComponentComposer components)
      throws Exception {
    previousConfigStore = ConfigStore.globalOrNull();
    configStore = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    ConfigStore.setGlobal(configStore);
    fixture = components == null
        ? new RuntimeIntentTestFixture(tmp.resolve("intent"), store, configStore)
        : new RuntimeIntentTestFixture(tmp.resolve("intent"), store, configStore, components);
    return new AiInstallService(
        onlineAi,
        store,
        null,
        null,
        tmp,
        null,
        new SettingsServiceImpl(store, fixture.runner()));
  }

  private static final class RecordingComponents implements SettingsComponentComposer {
    final List<SettingsCandidateContext> preparedContexts = new ArrayList<>();
    final List<Map<String, java.util.Set<String>>> preparedAffected = new ArrayList<>();
    int installCount;

    @Override
    public Prepared prepare(
        UiSettings candidate, ResolvedConfig desired, Map<String, java.util.Set<String>> affected) {
      preparedAffected.add(Map.copyOf(affected));
      return prepared(SettingsCandidateContext.NONE);
    }

    @Override
    public Prepared prepare(
        UiSettings candidate,
        ResolvedConfig desired,
        Map<String, java.util.Set<String>> affected,
        SettingsCandidateContext context) {
      preparedAffected.add(Map.copyOf(affected));
      return prepared(context);
    }

    private Prepared prepared(SettingsCandidateContext context) {
      preparedContexts.add(context);
      return new Prepared() {
        @Override public void validate() { }
        @Override public void install() { installCount++; }
        @Override public void notifyObservers() { }
        @Override public void retire() { }
        @Override public void abort() { }
      };
    }
  }

  private static void invokeApplySettings(
      AiInstallService svc, ModelRegistry registry, InstallPlan plan) throws Exception {
    Method m =
        AiInstallService.class.getDeclaredMethod(
            "applySettings", ModelRegistry.class, InstallPlan.class);
    m.setAccessible(true);
    m.invoke(svc, registry, plan);
  }

  private static ModelRegistry registryWithChatModel(String filename) {
    ModelVariant variant =
        new ModelVariant(
            filename, ModelPrecision.GGUF, ExecutionProvider.CUDA, "sha", 10L, "https://example/x");
    ModelPackage chat =
        new ModelPackage(
            "chat",
            "Chat",
            "desc",
            "chat",
            List.of(variant),
            List.of(),
            0L,
            null,
            "models",
            null,
            null,
            false);
    return new ModelRegistry(2, "test", List.of(chat));
  }

  private static InstallPlan planFor(DownloadProfile profile) {
    return new InstallPlan(profile, List.of(), List.of(), 0L, List.of());
  }

  private void setProp(String key, String value) {
    prevProps.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private void clearProp(String key) {
    prevProps.putIfAbsent(key, System.getProperty(key));
    System.clearProperty(key);
  }
}
