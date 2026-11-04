/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.chat.AnswersPausedException;
import io.justsearch.app.api.chat.Hold;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class AiInstallIndexingPauseTest {
  @TempDir Path tmp;

  @Test
  void pausedAnswersStillAllowTheRealInstallProbeAndCompletion() throws Exception {
    var requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/health", exchange -> {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.createContext("/props", exchange -> {
      byte[] body = "{\"model_path\":\"probe.gguf\",\"n_ctx\":4096}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext("/v1/chat/completions", exchange -> {
      requests.incrementAndGet();
      byte[] body = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    var previous = ConfigStore.globalOrNull();
    server.start();
    try (var executors = new TestEngineExecutors()) {
      ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
          "justsearch.inference.external_endpoint", "http://127.0.0.1:" + server.getAddress().getPort()))));
      var config = new InferenceConfig(Files.createFile(tmp.resolve("unused-server")),
          Files.createFile(tmp.resolve("probe.gguf")),
          null, server.getAddress().getPort(), 4096, 0, false);
      try (var manager = new InferenceLifecycleManager(executors, config)) {
        manager.switchToOnlineMode();
        var heavy = new AtomicBoolean(true);
        var settings = new UiSettings();
        settings.setChatEnabled(true);
        settings.setPauseAnswersWhileIndexing(true);
        manager.setAnswersPaused(() -> Boolean.TRUE.equals(settings.getPauseAnswersWhileIndexing())
            && heavy.get());
        assertTrue(manager.answersPausedWhileIndexing());

        var admission = new EngineAdmissionController(8, 32, 1);
        var ai = new OnlineAiServiceImpl(admission, manager);
        var ordinary = EngineProvenance.internal("ordinary-question",
            EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
        var refusal = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> ai.askQuestion("Question", "Context", ordinary).get(5, TimeUnit.SECONDS));
        assertTrue(AnswersPausedException.causedByPause(refusal));
        assertEquals(0, requests.get());

        var holds = mock(ChatHolds.class);
        var hold = mock(Hold.class);
        when(holds.holdRunning("post-install-smoke-test")).thenReturn(hold);
        var install = new AiInstallService(ai, null, null, null, tmp, holds);
        var smoke = AiInstallService.class.getDeclaredMethod("smokeTestBestEffort");
        smoke.setAccessible(true);
        var statusField = AiInstallService.class.getDeclaredField("status");
        statusField.setAccessible(true);
        var status = (AiInstallStatus) statusField.get(install);
        var installed = new AiInstallStatus.PackageStatus();
        installed.packageId = "chat";
        installed.state = "installed";
        status.packages.add(installed);

        var verdict = new ValidationStage(() -> false, () -> fail("unexpected cancellation"),
            () -> {}, () -> {
              try {
                return (Boolean) smoke.invoke(install);
              } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
              }
            }).run(true);
        assertEquals(ValidationStage.Verdict.PASSED, verdict, status.lastError);
        assertEquals(1, requests.get(), "the internal probe must reach the answer server");
        verify(hold).close();
        install.applyCompletionState(null);
        assertEquals("completed", status.state);
        assertTrue(status.installedFully);
        assertEquals("", status.errorCode);
        assertTrue(manager.isOnline(), "the answer model remains available");

        var stillPaused = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> ai.askQuestion("Question", "Context", ordinary).get(5, TimeUnit.SECONDS));
        assertTrue(AnswersPausedException.causedByPause(stillPaused));
        assertEquals(1, requests.get(), "the probe must not lift the pause for other requests");
        assertEquals(Boolean.TRUE, settings.getChatEnabled());
        heavy.set(false);
        assertEquals("OK", ai.askQuestion("Question", "Context", ordinary).get(5, TimeUnit.SECONDS));
        assertEquals(2, requests.get());
      }
    } finally {
      server.stop(0);
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }
}
