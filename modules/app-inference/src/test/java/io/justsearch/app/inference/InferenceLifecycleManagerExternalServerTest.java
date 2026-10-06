package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The user's own llama-server (ADR-0051 decision 2, ruling CH4).
 *
 * <p>A server left on the configured port, whether the user's or a crashed Engine's unregistered
 * one, is never adopted: the Engine launches its own on a fresh loopback port and reports the
 * ignored server with the note {@code external_server_ignored}. The user's own server is used only
 * when {@code justsearch.inference.external_endpoint} names it.
 */
class InferenceLifecycleManagerExternalServerTest {

  private ConfigStore previousStore;

  @BeforeEach
  void installResolvedConfig() {
    previousStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeFromEnvironment();
  }

  @AfterEach
  void restoreResolvedConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @TempDir Path tempDir;

  /** Names {@code port} as the user's own llama-server, the one way to use it (CH4). */
  private static void useExternalEndpoint(int port) {
    ConfigStore.setGlobal(
        new ConfigStore(
            TestResolvedConfigHelper.fromEntries(
                java.util.Map.of(
                    "justsearch.inference.external_endpoint", "http://127.0.0.1:" + port))));
  }

  @Test
  void startLlamaServerDoesNotAdoptHealthyServerOnConfiguredPortWithoutEndpoint()
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/health",
        exchange -> {
           exchange.sendResponseHeaders(200, -1);
           exchange.close();
         });
    server.createContext(
        "/props",
        exchange -> {
          byte[] body = "{\"model_alias\":\"external\",\"n_ctx\":4096}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    int port = server.getAddress().getPort();

    InferenceConfig config =
        new InferenceConfig(
            // Intentionally invalid executable path: the Engine's own launch is what fails here.
            Path.of("Z:\\definitely-not-a-real-path\\llama-server.exe"),
            Path.of("Z:\\definitely-not-a-real-path\\model.gguf"),
            null,
            port,
            4096,
            99,
            false);

    InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), config);
    try {
      ModeTransitionException launchedOwn =
          assertThrows(ModeTransitionException.class, manager::startLlamaServer);

      assertEquals(
          ModeTransitionException.Reason.EXECUTABLE_NOT_FOUND,
          launchedOwn.reason(),
          "without justsearch.inference.external_endpoint the Engine launches its own server");
      assertFalse(
          manager.isUsingExternalServer(),
          "a healthy server on the configured port must not be adopted");
      assertEquals(
          "external_server_ignored",
          manager.externalServerDiagnostics().note(),
          "the ignored server is reported");
    } finally {
      try {
        manager.close();
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void startLlamaServerFailsFastWhenHealthUpButPropsMissing() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/health",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    int port = server.getAddress().getPort();

    InferenceConfig config =
        new InferenceConfig(
            Path.of("Z:\\definitely-not-a-real-path\\llama-server.exe"),
            Path.of("Z:\\definitely-not-a-real-path\\model.gguf"),
            null,
            port,
            4096,
            0,
            false);

    InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), config);
    try {
      try {
        manager.startLlamaServer();
        fail("Expected startLlamaServer to fail when /props is missing and health-only adoption is disabled");
      } catch (Exception e) {
        // ok - expected
      }

      assertEquals(false, manager.isUsingExternalServer(), "Manager must not adopt a server that lacks /props by default");
    } finally {
      try {
        manager.close();
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void healthOnlyUseNeedsTheExplicitEndpointEvenWhenEnabled() throws Exception {
    String prev = System.getProperty("justsearch.inference.external.allow_health_only_adoption");
    System.setProperty("justsearch.inference.external.allow_health_only_adoption", "true");
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/health",
          exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      server.start();

      int port = server.getAddress().getPort();

      InferenceConfig config =
          new InferenceConfig(
              Path.of("Z:\\definitely-not-a-real-path\\llama-server.exe"),
              Path.of("Z:\\definitely-not-a-real-path\\model.gguf"),
              null,
              port,
              4096,
              0,
              false);

      InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), config);
      try {
        // On the configured port alone it is not adopted, health-only switch or not.
        ModeTransitionException launchedOwn =
            assertThrows(ModeTransitionException.class, manager::startLlamaServer);
        assertEquals(ModeTransitionException.Reason.EXECUTABLE_NOT_FOUND, launchedOwn.reason());
        assertFalse(
            manager.isUsingExternalServer(),
            "health-only use does not make the configured port an adoption path");
        assertEquals("external_server_ignored", manager.externalServerDiagnostics().note());
      } finally {
        manager.close();
      }

      useExternalEndpoint(port);
      InferenceLifecycleManager named = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), config);
      try {
        named.startLlamaServer();

        assertTrue(
            named.isUsingExternalServer(),
            "named by the endpoint, a health-only server is used when health-only use is enabled");
      } finally {
        try {
          named.close();
        } finally {
          server.stop(0);
        }
      }
    } finally {
      if (prev == null) System.clearProperty("justsearch.inference.external.allow_health_only_adoption");
      else System.setProperty("justsearch.inference.external.allow_health_only_adoption", prev);
    }
  }

  @Test
  void startLlamaServerDoesNotAdoptExternalServerWhenPolicyDisallows() throws Exception {
    String prev = System.getProperty("justsearch.policy.disallowExternalInferenceServers");
    System.setProperty("justsearch.policy.disallowExternalInferenceServers", "true");
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/health",
          exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      server.start();

      int port = server.getAddress().getPort();

      InferenceConfig config =
          new InferenceConfig(
              // Intentionally invalid executable path: if we attempt to start a process, the test should fail.
              Path.of("Z:\\definitely-not-a-real-path\\llama-server.exe"),
              Path.of("Z:\\definitely-not-a-real-path\\model.gguf"),
              null,
              port,
              4096,
              99,
              false);

      InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), config);
      try {
        try {
          manager.startLlamaServer();
          fail("Expected startLlamaServer to fail when policy disallows external servers");
        } catch (Exception e) {
          // ok - expected
        }
      } finally {
        try {
          manager.close();
        } finally {
          server.stop(0);
        }
      }
    } finally {
      if (prev == null) System.clearProperty("justsearch.policy.disallowExternalInferenceServers");
      else System.setProperty("justsearch.policy.disallowExternalInferenceServers", prev);
    }
  }

  @Test
  void adoptedExternalServerTransitionsOfflineAfterRepeatedHealthFailures() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/health",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/props",
        exchange -> {
          byte[] body = "{\"model_path\":\"C:/models/external.gguf\",\"n_ctx\":4096}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    int port = server.getAddress().getPort();
    Path fakeExe = Files.createFile(tempDir.resolve("llama-server.exe"));
    Path fakeModel = Files.createFile(tempDir.resolve("model.gguf"));

    InferenceConfig config =
        new InferenceConfig(
            fakeExe,
            fakeModel,
            null,
            port,
            4096,
            0,
            false);
    useExternalEndpoint(port);

    InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), config);
    try {
      manager.switchToOnlineMode();
      assertEquals(io.justsearch.app.api.Mode.ONLINE, manager.getCurrentMode());

      // Simulate the external server crashing.
      server.stop(0);

      manager.handlePeriodicHealthFailure("Simulated failure", true);
      manager.handlePeriodicHealthFailure("Simulated failure", true);
      manager.handlePeriodicHealthFailure("Simulated failure", true);

      assertEquals(io.justsearch.app.api.Mode.OFFLINE, manager.getCurrentMode());
    } finally {
      manager.close();
    }
  }

  @Test
  void failedDetachReadoptsCapturedExternalOwnerAndRemainsOnline() throws Exception {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/health",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/props",
        exchange -> {
          byte[] body =
              "{\"model_path\":\"C:/models/external.gguf\",\"n_ctx\":4096}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    Path unusableExecutable = Files.createFile(tempDir.resolve("not-an-executable"));
    Path model = Files.createFile(tempDir.resolve("model.gguf"));
    InferenceConfig config =
        new InferenceConfig(
            unusableExecutable,
            model,
            null,
            server.getAddress().getPort(),
            4096,
            0,
            false);
    useExternalEndpoint(server.getAddress().getPort());
    InferenceLifecycleManager manager =
        new InferenceLifecycleManager(
            new io.justsearch.core.execution.TestEngineExecutors(), config);
    try {
      manager.switchToOnlineMode();
      assertTrue(manager.isUsingExternalServer());

      try {
        manager.detachExternalServer();
        fail("Expected failed managed replacement to preserve and throw its original failure");
      } catch (io.justsearch.app.api.ModeTransitionException expected) {
        assertEquals(
            io.justsearch.app.api.ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
            expected.reason());
      }
      assertEquals(io.justsearch.app.api.Mode.ONLINE, manager.getCurrentMode());
      assertTrue(manager.isUsingExternalServer());
      assertEquals(server.getAddress().getPort(), manager.currentConfig().serverPort());
    } finally {
      manager.close();
      server.stop(0);
    }
  }

  @Test
  void applyConfigRestartRecordsStartupDuration_tempdoc601() throws Exception {
    // Tempdoc 601 regression: the GPU-runtime-activate and reload paths reach Online through
    // applyConfig(RESTART_ALWAYS / RESTART_IF_ONLINE), NOT switchToOnlineMode(). Before the fix the
    // applyConfig success view omitted withStartupDuration, so lastStartupDurationMs stayed -1 on
    // exactly the paths users hit and the FE "usually ready in ~Ns" estimate never rendered.
    // Driving applyConfig(RESTART_ALWAYS) from a fresh (OFFLINE) manager reaches Online WITHOUT ever
    // calling switchToOnlineMode, so a non-negative duration isolates THIS fix.
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/health",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/props",
        exchange -> {
          byte[] body =
              "{\"model_path\":\"C:/models/external.gguf\",\"n_ctx\":4096}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      int port = server.getAddress().getPort();
      Path fakeExe = Files.createFile(tempDir.resolve("llama-server.exe"));
      Path fakeModel = Files.createFile(tempDir.resolve("model.gguf"));
      InferenceConfig config = new InferenceConfig(fakeExe, fakeModel, null, port, 4096, 0, false);
      useExternalEndpoint(port);

      InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), config);
      try {
        // Precondition: a fresh manager has recorded no startup yet (the -1 sentinel).
        assertTrue(
            manager.getLastStartupDurationMs() < 0, "precondition: no startup recorded yet");

        manager.applyConfig(config, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

        assertEquals(io.justsearch.app.api.Mode.ONLINE, manager.getCurrentMode());
        assertTrue(
            manager.getLastStartupDurationMs() >= 0,
            "applyConfig restart must record lastStartupDurationMs (tempdoc 601); was "
                + manager.getLastStartupDurationMs());
      } finally {
        manager.close();
      }
    } finally {
      server.stop(0);
    }
  }
}
