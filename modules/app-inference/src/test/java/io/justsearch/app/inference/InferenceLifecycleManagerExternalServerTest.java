package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for avoiding llama-server restart loops when a server is already running.
 *
 * <p>On Windows it's common for developers (or forced kills) to leave a llama-server process alive.
 * If the configured port already serves /health, we must not spawn a second process (which would
 * fail to bind) and then enter crash recovery loops.
 */
class InferenceLifecycleManagerExternalServerTest {

  @TempDir Path tempDir;

  @Test
  void startLlamaServerSkipsProcessStartWhenHealthAlreadyUp() throws Exception {
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
      manager.startLlamaServer();

      assertTrue(manager.isUsingExternalServer(), "Manager should detect and use external server");
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
  void startLlamaServerCanAdoptHealthOnlyWhenExplicitlyEnabled() throws Exception {
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
        manager.startLlamaServer();

        assertTrue(manager.isUsingExternalServer(), "Manager should adopt when health-only adoption is enabled");
      } finally {
        try {
          manager.close();
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
