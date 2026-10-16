/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeActivationCpuCommitTest {
  @TempDir Path tmp;

  @ParameterizedTest @ValueSource(ints = {-1, 0, 12})
  void activationKeepsAutomaticOrExplicitLayersAndReportsCpu(int requested) throws Exception {
    var props = new HashMap<String, String>();
    for (String key : List.of("justsearch.home", "justsearch.data.dir", "justsearch.gpu.enabled")) {
      props.put(key, System.getProperty(key));
    }
    var previous = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.home", tmp.toString());
      System.setProperty("justsearch.data.dir", tmp.toString());
      System.setProperty("justsearch.gpu.enabled", "false");
      ConfigStoreRebuilder.rememberAutoDetected(Map.of("justsearch.gpu.layers", "0"));
      Path exe = tmp.resolve("native-bin/llama-server/variants/cuda12/llama-server.exe");
      Files.createDirectories(exe.getParent());
      Files.writeString(exe, "never executed");
      Path model = tmp.resolve("chosen.gguf"); Files.writeString(model, "gguf");
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          tmp.resolve("settings.json"));
      var initial = new UiSettings(); initial.setChatEnabled(true);
      initial.setLlmModelPath(model.toString()); initial.setContextLength(4096);
      if (requested >= 0) initial.setGpuLayers(requested);
      settings.replacePrepared(settings.prepare(initial, new SettingsWitness(0, null)));
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(initial)); ConfigStore.setGlobal(config);
      try (var exec = new TestEngineExecutors();
          var intent = new RuntimeIntentTestFixture(tmp.resolve("intent"), settings, config)) {
        var service = new RuntimeActivationService(exec, OnlineAiService.unavailable(), settings,
            null, null, null, null, null, null, null, new SettingsServiceImpl(settings, intent.runner()));
        service.setSelfTestOverrideForTest((binary, weights) ->
            new RuntimeActivationService.SelfTestResult("passed", 65001, null, null, null,
                List.of(), "cpu", "none"));
        service.startActivate("cuda12").completion().toCompletableFuture().join();
        var saved = settings.load(); var status = service.getActivationStatus();
        assertEquals("completed", status.state, status.message);
        assertAll(() -> assertEquals(requested < 0 ? null : requested, saved.configuredGpuLayers(),
                "activation must not turn an automatic CPU profile into a saved GPU override"),
            () -> assertEquals("CPU runtime activated.", status.message),
            () -> assertNull(status.vramUsedBeforeBytes), () -> assertNull(status.vramUsedAfterBytes),
            () -> assertEquals(4096, saved.getContextLength()));
      }
    } finally {
      ConfigStoreRebuilder.rememberAutoDetected(Map.of());
      ConfigStore.restoreGlobal(ConfigStore.globalOrNull(), previous);
      props.forEach((k,v) -> { if(v == null) System.clearProperty(k); else System.setProperty(k,v); });
    }
  }
}
