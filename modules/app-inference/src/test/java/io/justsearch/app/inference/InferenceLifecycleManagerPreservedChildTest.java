/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves that an ordered restart preserves one managed child for strict hash re-adoption. */
final class InferenceLifecycleManagerPreservedChildTest {

  @Test
  void preservedManagedChildIsStrictlyReadoptedByNextManager(@TempDir Path tempDir)
      throws Exception {
    ConfigStore previousStore = ConfigStore.globalOrNull();
    ResolvedConfig captured =
        TestResolvedConfigHelper.fromEntries(Map.of("justsearch.context.size", "4096", "justsearch.data.dir", tempDir.resolve("captured").toString()));
    // A contradictory ambient snapshot makes a fallback/global recapture fail the hash match.
    ConfigStore.setGlobal(
        new ConfigStore(
            TestResolvedConfigHelper.fromEntries(
                Map.of("justsearch.context.size", "8192", "justsearch.data.dir", tempDir.resolve("ambient").toString()))));

    Process child = null;
    HttpServer server = null;
    InferenceLifecycleManager managerA = null;
    InferenceLifecycleManager managerB = null;
    try {
      child = sleepingJvm();
      Path executable = Path.of(child.info().command().orElseThrow());
      Path model = Files.createFile(tempDir.resolve("model.gguf"));
      server = healthyLlamaFixture();
      int port = server.getAddress().getPort();
      InferenceConfig configuration =
          new InferenceConfig(executable, model, null, port, 4096, 0, false);
      String declaredHash =
          ManagedLlamaConfigIdentity.declaredHash(configuration, captured, 0);

      RecordingRegistry registry = new RecordingRegistry();
      ManagedChild retained =
          ManagedChild.fromProcess(
              child,
              ManagedChild.Kind.LLAMA_SERVER,
              "http://127.0.0.1:" + port,
              model.toString(),
              declaredHash,
              "fixture-argv-hash");
      registry.register(retained);

      managerA = manager(configuration, captured, registry);
      managerA.switchToOnlineMode();
      assertTrue(managerA.isOnline());
      assertFalse(managerA.isUsingExternalLlamaServer());
      assertEquals(1, registry.registrationCount());

      managerA.setStopServerOnClose(false);
      managerA.close();
      managerA = null;

      assertTrue(child.isAlive(), "restart close must leave the managed child running");
      assertEquals(List.of(retained), registry.snapshot(), "restart close must retain its registry row");

      managerB = manager(configuration, captured, registry);
      InferenceLifecycleManager.ConfigApplyResult result =
          managerB.applyResolvedConfig(
              configuration, captured, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(InferenceLifecycleManager.ConfigApplyDisposition.APPLIED, result.disposition());
      assertNull(result.failure());
      assertEquals(declaredHash, result.declaredConfigHash());
      assertTrue(managerB.isOnline());
      assertFalse(managerB.isUsingExternalLlamaServer());
      assertTrue(child.isAlive());
      assertEquals(
          1,
          registry.registrationCount(),
          "strict re-adoption must not launch and register a replacement process");
      assertEquals(List.of(retained), registry.snapshot());

      managerB.close();
      managerB = null;
      assertTrue(child.waitFor(5, TimeUnit.SECONDS), "ordinary close must stop the adopted child");
      assertFalse(child.isAlive());
      assertTrue(registry.snapshot().isEmpty(), "ordinary close must retire the managed-child row");
    } finally {
      try {
        if (managerB != null) managerB.close();
      } finally {
        try {
          if (managerA != null) managerA.close();
        } finally {
          try {
            if (server != null) server.stop(0);
          } finally {
            try {
              if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
              }
            } finally {
              TestResolvedConfigHelper.restoreGlobal(previousStore);
            }
          }
        }
      }
    }
  }

  private static InferenceLifecycleManager manager(
      InferenceConfig configuration, ResolvedConfig captured, ManagedChildRegistry registry) {
    return new InferenceLifecycleManager(
        new io.justsearch.core.execution.TestEngineExecutors(),
        configuration,
        NoopInferenceTelemetryEvents.INSTANCE,
        registry,
        captured);
  }

  private static Process sleepingJvm() throws java.io.IOException {
    Path java =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    return new ProcessBuilder(
            java.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            SleepingChild.class.getName())
        .start();
  }

  private static HttpServer healthyLlamaFixture() throws java.io.IOException {
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
              "{\"model_alias\":\"model.gguf\",\"n_ctx\":4096}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static final class RecordingRegistry implements ManagedChildRegistry {
    private final List<ManagedChild> children = new ArrayList<>();
    private int registrations;

    @Override
    public synchronized List<ManagedChild> snapshot() {
      return List.copyOf(children);
    }

    @Override
    public synchronized void register(ManagedChild child) {
      registrations++;
      children.removeIf(existing -> existing.id().equals(child.id()));
      children.add(child);
    }

    @Override
    public synchronized void remove(String childId) {
      children.removeIf(child -> child.id().equals(childId));
    }

    synchronized int registrationCount() {
      return registrations;
    }
  }

  public static final class SleepingChild {
    private SleepingChild() {}

    public static void main(String[] args) throws InterruptedException {
      Thread.sleep(TimeUnit.MINUTES.toMillis(2));
    }
  }
}
