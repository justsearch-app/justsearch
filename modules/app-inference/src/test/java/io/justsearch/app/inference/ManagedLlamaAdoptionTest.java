/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Ownership of the managed llama-server under ADR-0051 decision 2 (scenario S14): JustSearch never
 * adopts a llama-server it did not start in this Engine. Every managed launch binds a fresh loopback
 * port under a port-free declared configuration (ruling CH2), a lost bind race is retried once at
 * the same context rung (CH3), and the user's own server is used only through the explicit
 * {@code justsearch.inference.external_endpoint} setting and never stopped (CH4).
 *
 * <p>The launch tests run a fake llama-server: a script that records its argv and, once registered,
 * exits 1 after printing llama-server's bind-failure line, so each launch is observable without a
 * model.
 */
final class ManagedLlamaAdoptionTest {
  private static final String EXTERNAL_ENDPOINT_KEY = "justsearch.inference.external_endpoint";

  @Test
  void failedRegistrationRollbackRetainsHandleForRootCleanup(@TempDir Path tmp) throws Exception {
    RetryKillProcess process = new RetryKillProcess();
    LlamaServerOps ops =
        new LlamaServerOps(new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
            HttpClient.newHttpClient(), new ObjectMapper(), null, () -> Mode.OFFLINE,
            new NoopPropsObserver(), ignored -> {}, (ignored, guard) -> {},
            NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop());
    try {
      ops.rollbackFailedRegistration(process, new IOException("disk full"));
      assertTrue(process.isAlive());
      assertEquals(1, process.destroyCalls);

      ops.stopLlamaServer();
      assertFalse(process.isAlive());
      assertEquals(2, process.destroyCalls);
    } finally {
      ops.shutdown();
    }
  }

  @Test
  void repeatedRollbackTerminationFailureMakesCloseFailAndRetainsCleanup(@TempDir Path tmp) {
    RetryKillProcess process = new RetryKillProcess();
    process.killAfter = Integer.MAX_VALUE;
    InferenceConfig config =
        new InferenceConfig(tmp.resolve("llama.exe"), tmp.resolve("model.gguf"), null, 1, 1, 0, false);
    LlamaServerOps ops = new LlamaServerOps(new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(), new ObjectMapper(), null, () -> Mode.OFFLINE,
        new NoopPropsObserver(), ignored -> {}, (ignored, guard) -> {},
        NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop());
    try {
      ops.rollbackFailedRegistration(process, new IOException("disk full"));
      assertThrows(IllegalStateException.class, ops::stopLlamaServer);
      assertTrue(process.isAlive());
      assertEquals(2, process.destroyCalls);
      var refused =
          assertThrows(
              IllegalStateException.class,
              () ->
                  ops.startLlamaServer(
                      new LlamaServerOps.StartRequest(
                          context(config),
                          LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL)));
      assertTrue(refused.getMessage().contains("survived terminal cleanup"));
      assertEquals(3, process.destroyCalls);
      assertThrows(IllegalStateException.class, ops::closeUnregisteredChild);
      assertTrue(process.isAlive());
      process.killAfter = process.destroyCalls + 1;
      ops.closeUnregisteredChild();
      assertFalse(process.isAlive());
    } finally {
      process.killAfter = 0;
      ops.closeUnregisteredChild();
      ops.shutdown();
    }
  }

  private static LlamaServerConfigContext context(InferenceConfig config) {
    ResolvedConfig resolved = mock(ResolvedConfig.class);
    ResolvedConfig.Ai ai = mock(ResolvedConfig.Ai.class);
    when(resolved.ai()).thenReturn(ai);
    when(ai.gpuAccelerationAllowed()).thenReturn(true);
    return new LlamaServerConfigContext(config, resolved);
  }

  /**
   * CH4: a healthy llama-server answering on the configured port is not ours to use. Without the
   * explicit endpoint setting the Engine launches its own on another port and leaves that server
   * running.
   */
  @Test
  void healthyServerOnConfiguredPortIsNotAdoptedWithoutExternalEndpoint(@TempDir Path tmp)
      throws Exception {
    try (LlamaHttpStub other = new LlamaHttpStub()) {
      FakeLlamaServer fake = FakeLlamaServer.create(tmp);
      Registry registry = fake.registry();
      LlamaServerOps ops = ops(registry);
      try {
        LlamaServerOps.StartResult start =
            ops.startLlamaServer(request(tmp, fake, other.port(), Map.of()));

        assertEquals(
            LlamaServerOps.StartDisposition.LAUNCHED_MANAGED,
            start.disposition(),
            "a server on the configured port must not be adopted without " + EXTERNAL_ENDPOINT_KEY);
        List<List<String>> launches = fake.awaitLaunches(1);
        assertNotEquals(
            other.port(), flag(launches.get(0), "--port"),
            "the managed server must not be launched onto the configured port");

        ops.stopLlamaServer();
        ops.stopServerForTerminalShutdown(Duration.ofSeconds(5));
        assertTrue(other.answersHealth(), "the server JustSearch did not start is left alone");
      } finally {
        ops.shutdown();
      }
    }
  }

  /**
   * CH4: the explicit endpoint is the one way to use the user's own llama-server. It is used
   * instead of launching one, and neither stop nor terminal shutdown ends it.
   */
  @Test
  void explicitExternalEndpointIsUsedAndNeverStopped(@TempDir Path tmp) throws Exception {
    try (LlamaHttpStub own = new LlamaHttpStub()) {
      FakeLlamaServer fake = FakeLlamaServer.create(tmp);
      Registry registry = fake.registry();
      LlamaServerOps ops = ops(registry);
      try {
        LlamaServerOps.StartResult start =
            ops.startLlamaServer(
                request(
                    tmp, fake, freePort(),
                    Map.of(EXTERNAL_ENDPOINT_KEY, "http://127.0.0.1:" + own.port())));

        assertEquals(LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL, start.disposition());
        ops.waitForServerHealth(start);
        assertTrue(ops.isExternalServerActive());

        ops.stopLlamaServer();
        ops.stopServerForTerminalShutdown(Duration.ofSeconds(5));
        assertTrue(own.answersHealth(), "the user's own server is never stopped");
        assertTrue(fake.launches().isEmpty(), "nothing is launched while the endpoint is used");
        assertTrue(registry.history().isEmpty(), "the user's server is never registered as ours");
      } finally {
        ops.shutdown();
      }
    }
  }

  /**
   * CH2 + ADR-0051 decision 2: each managed launch binds its own fresh loopback port, never the
   * configured one, and the declared configuration hash does not depend on any port, so two
   * launches that differ only in the configured port have the same hash.
   */
  @Test
  void twoLaunchesUseDifferentFreshPortsAndTheSameDeclaredHash(@TempDir Path tmp)
      throws Exception {
    FakeLlamaServer fake = FakeLlamaServer.create(tmp);
    Registry registry = fake.registry();
    LlamaServerOps ops = ops(registry);
    int configuredFirst = freePort();
    int configuredSecond = freePort();
    try {
      ops.startLlamaServer(request(tmp, fake, configuredFirst, Map.of()));
      int firstPort = flag(fake.awaitLaunches(1).get(0), "--port");
      ops.stopLlamaServer();
      Files.deleteIfExists(fake.registered()); // in case the stop ended the fake before it did
      // Hold the first launch's port, so the second launch cannot be handed it again.
      try (ServerSocket held = new ServerSocket(firstPort, 1, InetAddress.getLoopbackAddress())) {
        ops.startLlamaServer(request(tmp, fake, configuredSecond, Map.of()));
        int secondPort = flag(fake.awaitLaunches(2).get(1), "--port");
        ops.stopLlamaServer();

        assertNotEquals(firstPort, secondPort, "every launch gets its own port");
        assertNotEquals(configuredFirst, firstPort, "a launch never binds the configured port");
        assertNotEquals(configuredSecond, secondPort, "a launch never binds the configured port");
        List<ManagedChild> launched = registry.history();
        assertEquals(2, launched.size());
        assertEquals("http://127.0.0.1:" + firstPort, launched.get(0).endpoint());
        assertEquals("http://127.0.0.1:" + secondPort, launched.get(1).endpoint());
        assertEquals(
            launched.get(0).declaredConfigHash(), launched.get(1).declaredConfigHash(),
            "the declared configuration excludes the port");
      }
    } finally {
      ops.shutdown();
    }
  }

  /**
   * CH3: when llama-server reports that it could not bind its port, the launch is retried once on
   * another fresh port at the same context window; a second lost bind is the launch failure, and it
   * never steps the context window down.
   */
  @Test
  void lostBindRetriesOnceOnAFreshPortAtTheSameContextRung(@TempDir Path tmp) throws Exception {
    FakeLlamaServer fake = FakeLlamaServer.create(tmp);
    Registry registry = fake.registry();
    LlamaServerOps ops = ops(registry);
    try {
      LlamaServerOps.StartResult start = ops.startLlamaServer(request(tmp, fake, freePort(), Map.of()));

      ModeTransitionException failure =
          assertThrows(ModeTransitionException.class, () -> ops.waitForServerHealth(start));

      assertEquals(ModeTransitionException.Reason.PROCESS_EXITED, failure.reason());
      List<List<String>> launches = fake.launches();
      assertEquals(2, launches.size(), "one launch plus exactly one bind retry: " + launches);
      assertEquals(flag(launches.get(0), "-c"), flag(launches.get(1), "-c"),
          "a lost bind must not step the context window down");
      assertNotEquals(flag(launches.get(0), "--port"), flag(launches.get(1), "--port"),
          "the retry binds a fresh port");
      assertTrue(registry.snapshot().isEmpty(), "both failed launches are released");
    } finally {
      ops.stopLlamaServer();
      ops.shutdown();
    }
  }

  /**
   * CH3 covers every managed launch, so the one-shot recovery wait gets the same single bind retry
   * on a fresh port at the same context window. It still never walks the context ladder.
   */
  @Test
  void lostBindOnRecoveryRetriesOnceOnAFreshPortAtTheSameContextRung(@TempDir Path tmp)
      throws Exception {
    FakeLlamaServer fake = FakeLlamaServer.create(tmp);
    Registry registry = fake.registry();
    LlamaServerOps ops = ops(registry);
    try {
      LlamaServerOps.StartResult start = ops.startLlamaServer(request(tmp, fake, freePort(), Map.of()));

      ModeTransitionException failure =
          assertThrows(ModeTransitionException.class, () -> ops.waitForServerHealthOnce(start));

      assertEquals(ModeTransitionException.Reason.PROCESS_EXITED, failure.reason());
      List<List<String>> launches = fake.launches();
      assertEquals(2, launches.size(),
          "a recovery launch plus exactly one bind retry: " + launches);
      assertEquals(flag(launches.get(0), "-c"), flag(launches.get(1), "-c"),
          "a lost bind must not step the context window down");
      assertNotEquals(flag(launches.get(0), "--port"), flag(launches.get(1), "--port"),
          "the retry binds a fresh port");
      assertTrue(registry.snapshot().isEmpty(), "both failed launches are released");
    } finally {
      ops.stopLlamaServer();
      ops.shutdown();
    }
  }

  /**
   * Stopping the fake ends the script's shell, not the delay command it started; end those too, so
   * nothing holds the temporary directory open.
   */
  @AfterEach
  void endFakeServerDescendants() {
    List<ProcessHandle> descendants = ProcessHandle.current().descendants().toList();
    descendants.forEach(ProcessHandle::destroyForcibly);
    for (ProcessHandle handle : descendants) {
      try {
        handle.onExit().get(5, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // best-effort cleanup of test helpers
      }
    }
  }

  private static LlamaServerOps ops(ManagedChildRegistry registry) {
    return new LlamaServerOps(
        new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(), new ObjectMapper(), null, () -> Mode.OFFLINE,
        new NoopPropsObserver(), ignored -> {}, (ignored, guard) -> {},
        NoopInferenceTelemetryEvents.INSTANCE, registry);
  }

  private static LlamaServerOps.StartRequest request(
      Path tmp, FakeLlamaServer fake, int configuredPort, Map<String, String> settings)
      throws IOException {
    Path model = tmp.resolve("model.gguf");
    if (!Files.exists(model)) Files.createFile(model);
    Map<String, String> entries = new HashMap<>(settings);
    entries.put("justsearch.data.dir", tmp.resolve("data").toString());
    ResolvedConfig resolved = TestResolvedConfigHelper.fromEntries(entries);
    InferenceConfig config =
        new InferenceConfig(fake.executable(), model, null, configuredPort, 4096, 0, false);
    return new LlamaServerOps.StartRequest(
        new LlamaServerConfigContext(config, resolved),
        LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL);
  }

  private static int flag(List<String> argv, String name) {
    int at = argv.indexOf(name);
    assertTrue(at >= 0 && at + 1 < argv.size(), name + " missing from " + argv);
    return Integer.parseInt(argv.get(at + 1));
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  /**
   * A script standing in for llama-server: records its argv, waits until the Engine has registered
   * it (the {@code registered} file the {@link Registry} writes, so the process is still alive while
   * its identity is captured), then fails to bind like b8571 does. It starts no other process.
   */
  private record FakeLlamaServer(Path executable, Path launchLog, Path registered) {
    static FakeLlamaServer create(Path tmp) throws IOException {
      Path dir = Files.createDirectories(tmp.resolve("fake-llama"));
      boolean windows = System.getProperty("os.name", "").startsWith("Windows");
      Path script = dir.resolve(windows ? "llama-server.cmd" : "llama-server");
      String body =
          windows
              ? "@echo off\r\n"
                  + ">>\"%~dp0launches.txt\" echo %*\r\n"
                  + ":wait\r\n"
                  + "if not exist \"%~dp0registered\" goto wait\r\n"
                  + "del \"%~dp0registered\"\r\n"
                  + "echo main: couldn't bind HTTP server socket, hostname: 127.0.0.1\r\n"
                  + "exit /b 1\r\n"
              : "#!/bin/sh\n"
                  + "dir=\"$(dirname \"$0\")\"\n"
                  + "echo \"$*\" >> \"$dir/launches.txt\"\n"
                  + "while [ ! -e \"$dir/registered\" ]; do :; done\n"
                  + "rm -f \"$dir/registered\"\n"
                  + "echo \"main: couldn't bind HTTP server socket, hostname: 127.0.0.1\"\n"
                  + "exit 1\n";
      Files.writeString(script, body, StandardCharsets.US_ASCII);
      assertTrue(script.toFile().setExecutable(true), "fake llama-server must be executable");
      return new FakeLlamaServer(script, dir.resolve("launches.txt"), dir.resolve("registered"));
    }

    /** The registry that releases the fake once the Engine has registered it. */
    Registry registry() {
      return new Registry(registered);
    }

    List<List<String>> launches() throws IOException {
      if (!Files.exists(launchLog)) return List.of();
      List<List<String>> launches = new ArrayList<>();
      for (String line : Files.readAllLines(launchLog, StandardCharsets.US_ASCII)) {
        if (!line.isBlank()) launches.add(Arrays.asList(line.trim().split("\\s+")));
      }
      return launches;
    }

    List<List<String>> awaitLaunches(int count) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (launches().size() < count && System.nanoTime() < deadline) Thread.sleep(20);
      List<List<String>> launches = launches();
      assertTrue(launches.size() >= count, "expected " + count + " launches, saw " + launches);
      return launches;
    }
  }

  /** A healthy llama-server look-alike: answers /health and a llama-shaped /props. */
  private static final class LlamaHttpStub implements AutoCloseable {
    private final HttpServer server;

    LlamaHttpStub() throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/health",
          exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.createContext(
          "/props",
          exchange -> {
            byte[] body =
                "{\"model_path\":\"model.gguf\",\"n_ctx\":4096}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
    }

    int port() {
      return server.getAddress().getPort();
    }

    boolean answersHealth() throws Exception {
      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/health"))
                      .timeout(Duration.ofSeconds(2))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  /** Records every registration, so a test can compare launches after they are released. */
  private static final class Registry implements ManagedChildRegistry {
    private final List<ManagedChild> children = new ArrayList<>();
    private final List<ManagedChild> history = new ArrayList<>();
    private final Path registeredSignal;

    Registry(Path registeredSignal) {
      this.registeredSignal = registeredSignal;
    }

    @Override
    public synchronized List<ManagedChild> snapshot() {
      return List.copyOf(children);
    }

    @Override
    public synchronized void register(ManagedChild child) throws IOException {
      children.removeIf(existing -> existing.id().equals(child.id()));
      children.add(child);
      history.add(child);
      Files.writeString(registeredSignal, child.id());
    }

    @Override
    public synchronized void remove(String childId) {
      children.removeIf(child -> child.id().equals(childId));
    }

    synchronized List<ManagedChild> history() {
      return List.copyOf(history);
    }
  }

  private static final class NoopPropsObserver implements PropsObserver {
    @Override
    public void onModelIdObserved(String modelId, LlamaServerConfigContext context) {}
    @Override public void onContextTokensObserved(int contextTokens) {}
    @Override public String observedModelId() { return null; }
    @Override public Integer observedContextTokens() { return null; }
  }

  /** A long-lived child process; LlamaServerCandidateContextTest launches it as a stand-in server. */
  public static final class SleepingChild {
    public static void main(String[] args) throws InterruptedException {
      Thread.sleep(30_000);
    }
  }

  private static final class RetryKillProcess extends Process {
    private boolean alive = true;
    private int destroyCalls;
    private int killAfter = 2;

    @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public int waitFor() { alive = false; return 0; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
    @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
    @Override public void destroy() { destroyForcibly(); }
    @Override public Process destroyForcibly() {
      destroyCalls++;
      if (destroyCalls >= killAfter) alive = false;
      return this;
    }
    @Override public boolean isAlive() { return alive; }
    @Override public long pid() { return 424242L; }
  }
}
