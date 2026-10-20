/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.*;
import io.justsearch.app.api.chat.SelfTestSession;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.inference.chat.*;
import io.justsearch.app.services.chat.SlowStartFakeLlamaServer;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.*;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** K5: activation probes authenticate through the session and the isolated child launcher. */
final class RuntimeActivationAuthenticationTest {
  @TempDir Path tmp;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void activationUsesAPrivateFreshEnvironmentKey(boolean throughSession) throws Exception {
    Path home = tmp.resolve("fake");
    Path executable = SlowStartFakeLlamaServer.install(home, 0);
    Files.writeString(home.resolve("require-api-key"), "required");
    Path model = Files.createFile(tmp.resolve("model.gguf"));
    String oldHome = System.getProperty("justsearch.home");
    var oldConfig = ConfigStore.globalOrNull();
    var config = new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.gpu.enabled", "false", "justsearch.gpu.layers", "0")));
    System.setProperty("justsearch.home", tmp.toString());
    ConfigStore.setGlobal(config);
    AtomicReference<String> previousKey = new AtomicReference<>();
    try (var executors = new TestEngineExecutors();
        var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop());
        var service = new RuntimeActivationService(executors, OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
                tmp.resolve("settings.json")), null, null)) {
      SelfTestSession<SelfTestSpec, SelfTestRun> session = new SelfTestSession<>() {
        @Override public SelfTestRun run(SelfTestSpec spec) {
          String key = spec.environment().get("LLAMA_API_KEY");
          assertTrue(key != null && key.matches("[A-Za-z0-9_-]{43}"),
              "self-test needs a private launch credential");
          assertFalse(String.join(" ", spec.command()).contains(key));
          String obsolete = previousKey.getAndSet(key);
          if (obsolete != null) assertFalse(obsolete.equals(key), "self-test key must rotate");
          var probe = new SelfTestSpec.Probe() {
            @Override public boolean healthy(int port) { return spec.probe().healthy(port); }
            @Override public void exercise(int port) throws Exception {
              assertEquals(401, status(port, null));
              assertEquals(401, status(port, obsolete == null ? "wrong" : obsolete));
              assertEquals(200, status(port, key));
              spec.probe().exercise(port);
            }
          };
          return new ChildSelfTestLauncher(control).run(new SelfTestSpec(spec.command(),
              spec.workingDirectory(), spec.pathPrefix(), spec.logFile(), spec.port(),
              spec.healthTimeout(), probe, spec.environment()));
        }
        @Override public void close() {}
      };
      var run = RuntimeActivationService.class.getDeclaredMethod("runSelfTest",
          SelfTestSession.class, Path.class, Path.class, UiSettings.class);
      run.setAccessible(true);
      for (int i = 0; i < 2; i++) {
        var result = (RuntimeActivationService.SelfTestResult) run.invoke(service,
            throughSession ? session : null, executable, model, new UiSettings());
        assertEquals("passed", result.result(), "authenticated activation chat probe must pass");
        assertTrue(SlowStartFakeLlamaServer.noneAlive(home), "self-test child must end");
      }
      assertEquals(2, Files.readAllLines(home.resolve("requests.txt")).size(),
          "each self-test exercised the protected chat endpoint once");
      if (previousKey.get() != null) {
        assertFalse(Files.readString(tmp.resolve("logs/llama-server-selftest.log"))
            .contains(previousKey.get()));
      }
    } finally {
      SlowStartFakeLlamaServer.endAll(home);
      ConfigStore.restoreGlobal(config, oldConfig);
      if (oldHome == null) System.clearProperty("justsearch.home");
      else System.setProperty("justsearch.home", oldHome);
    }
  }

  private static int status(int port, String key) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/props"))
        .timeout(Duration.ofSeconds(2));
    if (key != null) request.header("Authorization", "Bearer " + key);
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
  }
}
