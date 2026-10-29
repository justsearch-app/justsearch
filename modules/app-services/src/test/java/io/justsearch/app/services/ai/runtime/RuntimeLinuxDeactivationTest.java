package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class RuntimeLinuxDeactivationTest {
  @TempDir Path root;

  @Test void noFallbackDisablesChatAndRetainsRuntimeAndGpuSettings() throws Exception {
    String previousHome = System.getProperty("justsearch.home");
    String previousExe = System.getProperty("justsearch.server.exe");
    ConfigStore previous = ConfigStore.globalOrNull();
    Path exe = Files.writeString(root.resolve("operator-server"), "fixture");
    assertTrue(exe.toFile().setExecutable(true, true));
    System.setProperty("justsearch.home", root.toString());
    System.setProperty("justsearch.server.exe", exe.toString());
    try (var executors = new TestEngineExecutors()) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, root.resolve("settings.json"));
      var initial = settings.load();
      initial.setServerExecutablePath(exe.toString());
      initial.setGpuLayers(99);
      initial.setChatEnabled(true);
      settings.replacePrepared(settings.prepare(initial, new SettingsWitness(0, null)));
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(initial));
      ConfigStore.setGlobal(config);
      try (var fixture = new RuntimeIntentTestFixture(root.resolve("intent"), settings, config);
          var owner = new RuntimeActivationService(executors, OnlineAiService.unavailable(), settings,
              null, null, null, null, null, null, new SettingsServiceImpl(settings, fixture.runner()))) {
        assertTrue(owner.getStatus().installedVariants().stream()
            .anyMatch(v -> v.variantId().equals("default") && v.exePath().equals(exe.toString())));
        var terminal = owner.startDeactivate().completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("completed", terminal.state, terminal.errorCode + ": " + terminal.message);
        assertTrue(terminal.message.contains("unavailable"), terminal.message);
        assertTrue(terminal.message.contains("no fallback"), terminal.message);
        var saved = settings.load();
        assertFalse(saved.getChatEnabled());
        assertEquals(exe.toString(), saved.getServerExecutablePath());
        assertEquals(99, saved.getGpuLayers());
        assertEquals(exe, config.get().ai().serverExe());
      }
    } finally {
      if (previousHome == null) System.clearProperty("justsearch.home"); else System.setProperty("justsearch.home", previousHome);
      if (previousExe == null) System.clearProperty("justsearch.server.exe"); else System.setProperty("justsearch.server.exe", previousExe);
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }
}
