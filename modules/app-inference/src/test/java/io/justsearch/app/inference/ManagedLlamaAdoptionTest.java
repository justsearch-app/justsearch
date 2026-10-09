/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.io.IOException;
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

  /**
   * A server that survived its stop keeps its port and memory, so no new server starts over it: the
   * next start retries the survivor's stop first and refuses while it is still alive. (A server
   * that survives the rollback of a refused registration is held the same way: the
   * registration-rollback tests below.)
   */
  @Test
  void aSurvivingServerIsRetriedAndBlocksEveryStartUntilItEnds(@TempDir Path tmp) throws Exception {
    var control = new RecordingChildControl();
    LlamaServerOps ops = new LlamaServerOps(
        new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(), new ObjectMapper(), null, () -> Mode.OFFLINE,
        new NoopPropsObserver(), ignored -> {}, (ignored, guard) -> {},
        NoopInferenceTelemetryEvents.INSTANCE, control);
    var survivor = control.child(io.justsearch.app.api.runtime.child.ChildKind.LLAMA_SERVER);
    survivor.onStop = RecordingChildControl.OnStop.SURVIVE;
    var request = request(tmp, FakeLlamaServer.create(tmp), freePort(), Map.of());
    try {
      LlamaServerTestAccess.installChild(ops, new LlamaServerOps.StartResult(
          request.context(), LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL,
          LlamaServerOps.StartDisposition.LAUNCHED_MANAGED, "declared"), survivor);
      assertThrows(IllegalStateException.class,
          () -> ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP));
      assertEquals(io.justsearch.app.api.runtime.child.ChildHandle.State.SURVIVED,
          survivor.state());

      var refused = assertThrows(IllegalStateException.class, () -> ops.startLlamaServer(request));
      assertTrue(refused.getMessage().contains("survived terminal cleanup"));
      assertEquals(2, control.stops.size(), "the start retried the survivor's stop");
      assertTrue(control.started.isEmpty(), "nothing starts over a survivor");

      survivor.onStop = RecordingChildControl.OnStop.EXIT;
      ops.startLlamaServer(request);
      assertEquals(3, control.stops.size());
      assertTrue(survivor.exit().toCompletableFuture().isDone());
      assertEquals(1, control.started.size(), "the start proceeds once the survivor ended");
    } finally {
      survivor.onStop = RecordingChildControl.OnStop.EXIT;
      ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
      ops.shutdown();
    }
  }

  /**
   * A server whose manifest registration was refused, and whose ROLLBACK stop did not end it, is
   * still held: the next stop ends it. Driven through a real {@code DefaultChildControl} whose
   * terminator refuses to kill while {@code survive} is set.
   */
  @Test
  void failedRegistrationRollbackRetainsHandleForRootCleanup(@TempDir Path tmp) throws Exception {
    var fake = FakeLlamaServer.create(tmp);
    var rollback = new RollbackSurvivorControl(fake);
    LlamaServerOps ops = ops(rollback.control);
    try {
      assertThrows(IOException.class,
          () -> ops.startLlamaServer(request(tmp, fake, freePort(), Map.of())));
      assertEquals(1, rollback.terminator.alive().size(), "the rollback did not end the server");
      assertEquals(2, rollback.terminator.terminateCalls.get(), "the rollback's one stop");

      rollback.terminator.survive = false;
      ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
      assertTrue(rollback.terminator.alive().isEmpty(),
          "the next stop ends the retained rollback survivor");
      assertTrue(rollback.control.list().isEmpty());
    } finally {
      rollback.close();
      ops.shutdown();
    }
  }

  /**
   * A rollback survivor that keeps surviving makes every stop (close) fail and blocks every start,
   * each of which retries its stop first; once a retry ends it, the close succeeds.
   */
  @Test
  void repeatedRollbackTerminationFailureMakesCloseFailAndRetainsCleanup(@TempDir Path tmp)
      throws Exception {
    var fake = FakeLlamaServer.create(tmp);
    var rollback = new RollbackSurvivorControl(fake);
    LlamaServerOps ops = ops(rollback.control);
    var request = request(tmp, fake, freePort(), Map.of());
    try {
      assertThrows(IOException.class, () -> ops.startLlamaServer(request));
      assertThrows(IllegalStateException.class,
          () -> ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP),
          "close fails while the rollback survivor is alive");
      assertEquals(1, rollback.terminator.alive().size());
      assertEquals(4, rollback.terminator.terminateCalls.get(), "the close retried its stop");

      var refused = assertThrows(IllegalStateException.class, () -> ops.startLlamaServer(request));
      assertTrue(refused.getMessage().contains("survived terminal cleanup"), refused.getMessage());
      assertEquals(6, rollback.terminator.terminateCalls.get(), "the start retried its stop");
      assertEquals(1, rollback.spawns(), "nothing is launched beside the survivor");
      assertThrows(IllegalStateException.class,
          () -> ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP));
      assertEquals(1, rollback.terminator.alive().size());

      rollback.terminator.survive = false;
      ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
      assertTrue(rollback.terminator.alive().isEmpty());
    } finally {
      rollback.close();
      ops.shutdown();
    }
  }

  /**
   * The verifier's scenario (b56, VerifierRollbackSurvivorTest): right after a start whose
   * registration rollback left the server alive, the next start launches nothing beside it.
   */
  @Test
  void aRegistrationRollbackSurvivorBlocksTheNextStart(@TempDir Path tmp) throws Exception {
    var fake = FakeLlamaServer.create(tmp);
    var rollback = new RollbackSurvivorControl(fake);
    LlamaServerOps ops = ops(rollback.control);
    var request = request(tmp, fake, freePort(), Map.of());
    try {
      assertThrows(IOException.class, () -> ops.startLlamaServer(request), "registration refused");
      assertEquals(1, rollback.terminator.alive().size(), "the rollback child survived");
      Throwable second = null;
      try {
        ops.startLlamaServer(request);
      } catch (Throwable t) {
        second = t;
      }
      assertEquals(1, rollback.spawns(),
          "a second llama-server was launched beside the rollback survivor (second start threw: "
              + second + ")");
    } finally {
      rollback.close();
      ops.shutdown();
    }
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

        ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
        ops.stopServerForTerminalShutdown(LlamaServerTestAccess.TEST_STOP, Duration.ofSeconds(5));
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

        ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
        ops.stopServerForTerminalShutdown(LlamaServerTestAccess.TEST_STOP, Duration.ofSeconds(5));
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
      ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
      Files.deleteIfExists(fake.registered()); // in case the stop ended the fake before it did
      // Hold the first launch's port, so the second launch cannot be handed it again.
      ServerSocket held = new ServerSocket(firstPort, 1, InetAddress.getLoopbackAddress());
      try (held) {
        ops.startLlamaServer(request(tmp, fake, configuredSecond, Map.of()));
        int secondPort = flag(fake.awaitLaunches(2).get(1), "--port");
        ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);

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
      ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
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
      ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
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

  private static LlamaServerOps ops(ChildControl control) {
    return new LlamaServerOps(
        new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(), new ObjectMapper(), null, () -> Mode.OFFLINE,
        new NoopPropsObserver(), ignored -> {}, (ignored, guard) -> {},
        NoopInferenceTelemetryEvents.INSTANCE, control);
  }

  /**
   * A real {@code DefaultChildControl} whose registry refuses the first registration and whose
   * terminator kills nothing until told: a start's ROLLBACK stop leaves the server alive (SURVIVED),
   * as on a machine where the kill fails. Counts the {@code child-spawn} records.
   */
  private static final class RollbackSurvivorControl implements AutoCloseable {
    final SurvivingTerminator terminator = new SurvivingTerminator();
    final List<LedgerRecordType> appended = new java.util.concurrent.CopyOnWriteArrayList<>();
    final DefaultChildControl control;

    RollbackSurvivorControl(FakeLlamaServer fake) {
      terminator.survive = true;
      Registry released = fake.registry();
      var refusals = new java.util.concurrent.atomic.AtomicInteger(1);
      ManagedChildRegistry refusing = new ManagedChildRegistry() {
        @Override
        public List<ManagedChild> snapshot() {
          return released.snapshot();
        }

        @Override
        public void register(ManagedChild child) throws IOException {
          if (refusals.getAndDecrement() > 0) throw new IOException("disk full");
          released.register(child);
        }

        @Override
        public void remove(String childId) {
          released.remove(childId);
        }
      };
      control = DefaultChildControl.of((type, fields) -> appended.add(type), terminator, refusing);
    }

    long spawns() {
      return appended.stream().filter(type -> type == LedgerRecordType.CHILD_SPAWN).count();
    }

    @Override
    public void close() {
      terminator.killAll();
      control.close();
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
}
