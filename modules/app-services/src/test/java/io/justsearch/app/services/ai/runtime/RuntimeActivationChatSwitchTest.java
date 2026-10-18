/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.api.AiRuntimeActivationStatus;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A §2.2 (rule 3: an open self-test session means NOT_RUNNING; inputs are evaluated on every
 * change) and T39 (session closed: replan): turning chat on or off is a desired-state input, so a
 * switch made while the activation self-test runs is deferred and applied after it, and never fails
 * the activation. The switch goes through the same settings owner the mode route uses
 * ({@code RuntimeSpecStore}); the self-test is stubbed at the package-private seam.
 */
final class RuntimeActivationChatSwitchTest {
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


  @TempDir Path tmp;

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();
  private final Map<String, String> previousProps = new HashMap<>();
  private ConfigStore previousStore;
  private RuntimeIntentTestFixture intent;

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (intent != null) intent.close();
    } finally {
      previousProps.forEach((key, value) -> {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
      });
      TestResolvedConfigHelper.restoreGlobal(previousStore);
      processExecutors.close();
    }
  }

  @Test
  void chatTurnedOnDuringTheSelfTestIsKeptAndTheActivationCompletes() throws Exception {
    Fixture f = fixture(false);
    f.service.setSelfTestOverrideForTest((exe, model) -> {
      intent.spec().setChatEnabled(true); // the user's POST mode=online
      return passingSelfTest();
    });

    f.service.startActivate("cuda12");
    AiRuntimeActivationStatus status = awaitDone(f.service);

    assertEquals("completed", status.state, "a chat switch never fails the activation; code="
        + status.errorCode + " message=" + status.message);
    UiSettings after = f.store.load();
    assertEquals(Boolean.TRUE, after.getChatEnabled());
    assertEquals(f.variantExe.toAbsolutePath().toString(), after.getServerExecutablePath(),
        "the activated runtime is committed");
    assertNull(after.configuredGpuLayers(), "activation preserves automatic profile selection");
  }

  @Test
  void chatTurnedOffDuringTheSelfTestStaysOffAndTheRuntimeIsStillActivated() throws Exception {
    Fixture f = fixture(true);
    f.service.setSelfTestOverrideForTest((exe, model) -> {
      intent.spec().setChatEnabled(false);
      return passingSelfTest();
    });

    f.service.startActivate("cuda12");
    AiRuntimeActivationStatus status = awaitDone(f.service);

    assertEquals("completed", status.state, "code=" + status.errorCode
        + " message=" + status.message);
    UiSettings after = f.store.load();
    assertEquals(Boolean.FALSE, after.getChatEnabled(),
        "the switch was made later, so it is applied after the activation");
    assertEquals(f.variantExe.toAbsolutePath().toString(), after.getServerExecutablePath());
  }

  // Preservation: any other change still refuses, because the self-test validated what it read.
  @Test
  void aContextChangeDuringTheSelfTestStillRefusesTheActivation() throws Exception {
    Fixture f = fixture(false);
    String exeBefore = f.store.load().getServerExecutablePath();
    f.service.setSelfTestOverrideForTest((exe, model) -> {
      intent.spec().setChatEnabled(true);
      var snapshot = f.store.inspect();
      snapshot.settings().setContextLength(12288);
      var result = new SettingsServiceImpl(f.store, intent.runner())
          .applyInternal(snapshot.settings(), snapshot.witness(), TestEngineContexts.internal());
      if (!result.response().success()) fail("the competing write did not commit");
      return passingSelfTest();
    });

    f.service.startActivate("cuda12");
    AiRuntimeActivationStatus status = awaitDone(f.service);

    assertEquals("failed", status.state);
    assertEquals("RUNTIME_ACTIVATION_FAILED", status.errorCode);
    UiSettings after = f.store.load();
    assertEquals(12288, after.getContextLength(), "the newer settings survive");
    assertEquals(Boolean.TRUE, after.getChatEnabled());
    assertEquals(exeBefore, after.getServerExecutablePath(),
        "nothing of the stale candidate lands");
  }

  private record Fixture(UiSettingsStore store, RuntimeActivationService service, Path variantExe) {}

  private Fixture fixture(boolean chatEnabled) throws Exception {
    setProp("justsearch.home", tmp.toAbsolutePath().toString());
    setProp("justsearch.data.dir", tmp.toAbsolutePath().toString());
    clearProp("justsearch.chat.profile");
    clearProp("justsearch.server.exe");
    clearProp("justsearch.server.exe.source");
    previousStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeFromEnvironment();

    Path variantDir = tmp.resolve("native-bin/llama-server/variants/cuda12");
    Files.createDirectories(variantDir);
    Path variantExe = variantDir.resolve("llama-server.exe");
    Files.writeString(variantExe, "not-a-real-exe", StandardCharsets.UTF_8);
    Path model = tmp.resolve("chosen.gguf");
    Files.writeString(model, "gguf", StandardCharsets.UTF_8);

    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    UiSettings initial = store.load();
    initial.setChatEnabled(chatEnabled);
    initial.setLlmModelPath(model.toAbsolutePath().toString());
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));

    ConfigStore.setGlobal(new ConfigStore(ConfigStoreRebuilder.prepare(store.load())));
    intent = new RuntimeIntentTestFixture(tmp.resolve("intent"), store, ConfigStore.globalOrNull());
    var settingsService = new SettingsServiceImpl(store, intent.runner());
    var service = new RuntimeActivationService(processExecutors, OnlineAiService.unavailable(),
        store, null, null, null, null, null, null, null, settingsService);
    return new Fixture(store, service, variantExe);
  }

  private static RuntimeActivationService.SelfTestResult passingSelfTest() {
    return new RuntimeActivationService.SelfTestResult("passed", 65001, 0L,
        128L * 1024 * 1024, 128L * 1024 * 1024, List.of(), "12gb_plus", "nvml");
  }

  private static AiRuntimeActivationStatus awaitDone(RuntimeActivationService svc)
      throws Exception {
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline) {
      AiRuntimeActivationStatus status = svc.getActivationStatus();
      if (!"running".equalsIgnoreCase(status.state)) return status;
      Thread.sleep(25);
    }
    fail("timed out waiting for the activation to finish");
    return svc.getActivationStatus();
  }

  private void setProp(String key, String value) {
    previousProps.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private void clearProp(String key) {
    previousProps.putIfAbsent(key, System.getProperty(key));
    System.clearProperty(key);
  }
}
