/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** K6-K8: real child launches with an authentication-enforcing fake, no credentials on disk. */
final class ManagedLlamaAuthenticationTest {
  @TempDir Path tmp;
  private final List<ChildSpec> launches = new CopyOnWriteArrayList<>();
  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final ChildControl control = spy(DefaultChildControl.unrecorded(ManagedChildRegistry.noop()));
  private InferenceLifecycleManager manager;

  @BeforeEach
  void captureLaunches() throws Exception {
    doAnswer(call -> {
      launches.add(call.getArgument(0));
      return call.callRealMethod();
    }).when(control).start(any(), any());
  }

  @AfterEach
  void cleanup() throws Exception {
    try { if (manager != null) manager.close(); }
    finally {
      control.close();
      executors.close();
      HealthyFakeLlamaServer.endAll(tmp.resolve("fake"));
    }
  }

  @Test
  void allEngineExchangesAuthenticateAndEveryRestartRotatesTheKey() throws Exception {
    create("--require-api-key");
    manager.switchToOnlineMode();
    assertWorking();
    String first = key(0);
    int firstPort = manager.servingPort();
    assertEquals(401, status(firstPort, "/props", null));
    assertEquals(401, status(firstPort, "/props", "wrong"));
    assertEquals(200, status(firstPort, "/health", null));
    assertEquals(200, status(firstPort, "/props", first));
    assertFalse(String.join(" ", launches.getFirst().argv()).contains(first));
    assertEquals(ChatActuator.ResumeOutcome.PASSED,
        manager.chatActuator().execute(manager.chatActuator().resumeProbe(), null).outcome());

    manager.switchToIndexingMode();
    manager.switchToOnlineMode();
    assertWorking();
    String second = key(1);
    assertFalse(first.equals(second), "each launch must mint a different key");
    assertEquals(401, status(manager.servingPort(), "/props", first));
    assertEquals(200, status(manager.servingPort(), "/props", second));
  }

  @Test
  void aBinaryThatRefusesTheEnvironmentKeyFailsAndIsCleanedUp() throws Exception {
    create("--reject-api-key");
    assertThrows(ModeTransitionException.class, manager::switchToOnlineMode);
    assertFalse(manager.isOnline());
    assertEquals(1, launches.size(), "no unauthenticated retry");
    key(0);
    assertTrue(control.list().isEmpty(), "failed child was cleaned up");
    String log = Files.readString(tmp.resolve("data/logs/llama-server.log"));
    assertTrue(log.contains("LLAMA_API_KEY rejected"),
        "binary refusal is observable without exposing the key");
    assertFalse(log.contains(key(0)), "a failed launch must not log its key");
    assertFalse(launches.getFirst().argv().toString().contains(key(0)));
  }

  @Test
  void anEnvironmentRejectedByTheLauncherFailsBeforeSpawning() throws Exception {
    create("--require-api-key");
    doAnswer(call -> {
      ChildSpec spec = call.getArgument(0);
      String key = spec.envOverrides().get("LLAMA_API_KEY");
      assertTrue(key != null && key.length() == 43, "managed launch must supply its private key");
      throw new IOException("LLAMA_API_KEY environment rejected by launcher");
    }).when(control).start(any(), any());
    var failure = assertThrows(ModeTransitionException.class, manager::switchToOnlineMode);
    assertTrue(failure.getMessage().contains("LLAMA_API_KEY environment rejected"));
    assertFalse(manager.isOnline());
    assertTrue(control.list().isEmpty());
    assertTrue(HealthyFakeLlamaServer.launches(tmp.resolve("fake")).isEmpty());
  }

  @Test
  void aSelfTestSurvivingCleanupStaysProtectedAndBlocksAConflictingLaunch() throws Exception {
    create("--require-api-key");
    manager.switchToOnlineMode();
    String mainKey = key(0);
    manager.switchToIndexingMode();
    var terminator = new SurvivingTerminator();
    terminator.survive = true;
    String selfKey = UUID.randomUUID().toString();
    try (var selfControl = DefaultChildControl.of((type, fields) -> true, terminator,
        ManagedChildRegistry.noop())) {
      int port;
      try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
      var main = launches.getFirst();
      List<String> command = new ArrayList<>(main.argv());
      command.set(command.indexOf("--port") + 1, Integer.toString(port));
      var probe = new io.justsearch.app.inference.chat.SelfTestSpec.Probe() {
        @Override public boolean healthy(int candidatePort) {
          try { return status(candidatePort, "/health", null) == 200; }
          catch (Exception notReady) { return false; }
        }
        @Override public void exercise(int candidatePort) throws Exception {
          assertEquals(401, status(candidatePort, "/props", mainKey));
          assertEquals(200, status(candidatePort, "/props", selfKey));
        }
      };
      var spec = new io.justsearch.app.inference.chat.SelfTestSpec(command, main.workingDir(),
          main.pathPrefix(), tmp.resolve("selftest.log"), port, Duration.ofSeconds(10), probe,
          Map.of("LLAMA_API_KEY", selfKey));
      var run = new io.justsearch.app.inference.chat.ChildSelfTestLauncher(selfControl).run(spec);
      assertFalse(run.exercised(), "a survivor must fail the self-test");
      assertTrue(run.failure().contains("did not exit"));
      assertEquals(401, status(port, "/props", null));
      assertEquals(401, status(port, "/props", mainKey));
      assertEquals(200, status(port, "/props", selfKey));
      assertThrows(IOException.class, () -> selfControl.start(main));
      assertEquals(1, selfControl.list().size(), "no launch beside the protected survivor");
      terminator.survive = false;
    } finally { terminator.killAll(); }
    manager.switchToOnlineMode();
    assertWorking();
    // The main launch does not accept the self-test's unrelated credential.
    assertEquals(401, status(manager.servingPort(), "/props", selfKey));
  }

  @Test
  void anExplicitExternalEndpointNeverReceivesTheManagedKeyAndIsLeftRunning() throws Exception {
    create("--require-api-key");
    manager.switchToOnlineMode();
    key(0);
    manager.close();
    var observed = new CopyOnWriteArrayList<String>();
    var server = com.sun.net.httpserver.HttpServer.create(
        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      if (auth != null) observed.add(auth);
      String path = exchange.getRequestURI().getPath();
      String body = switch (path) {
        case "/health" -> "{\"status\":\"ok\"}";
        case "/props" -> "{\"model_path\":\"model.gguf\",\"n_ctx\":4096}";
        case "/tokenize" -> "{\"tokens\":[1,2,3]}";
        case "/apply-template" -> "{\"prompt\":\"user: test\"}";
        default -> "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
      };
      byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    int port = server.getAddress().getPort();
    try {
      var resolved = TestResolvedConfigHelper.fromEntries(Map.of(
          "justsearch.context.size", "4096", "justsearch.data.dir", tmp.resolve("external").toString(),
          "justsearch.inference.external_endpoint", "http://127.0.0.1:" + port));
      manager = new InferenceLifecycleManager(executors,
          new InferenceConfig(Path.of(launches.getFirst().argv().getFirst()), tmp.resolve("model.gguf"),
              null, port, 4096, 0, false),
          NoopInferenceTelemetryEvents.INSTANCE, control, resolved, ignored -> () -> {});
      manager.switchToOnlineMode();
      assertWorking();
      assertTrue(observed.isEmpty(), "no managed credential is sent to an external endpoint");
      assertEquals(1, launches.size(), "no managed launch for an external endpoint");
      manager.close();
      manager = null;
      assertEquals(200, status(port, "/health", null), "unmanaged server stays alive");
    } finally { server.stop(0); }
  }

  @Test
  void contextStepDownRotatesTheKeyAndRejectsTheObsoleteCredential() throws Exception {
    create("--require-api-key", "--max-ctx", "4096");
    manager.switchToOnlineMode();
    assertEquals(2, launches.size(), "derived CPU window steps down once");
    assertWorking();
    assertFalse(key(0).equals(key(1)), "step-down must mint a new key");
    assertEquals(401, status(manager.servingPort(), "/props", key(0)));
  }

  @Test
  void reasoningFlagRetryRotatesTheKeyAndAuthenticates() throws Exception {
    create("--require-api-key", "--reject-reasoning");
    manager.switchToOnlineMode();
    assertEquals(2, launches.size(), "unsupported reasoning flag is retried once");
    assertWorking();
    assertFalse(key(0).equals(key(1)), "reasoning retry must mint a new key");
    assertEquals(401, status(manager.servingPort(), "/props", key(0)));
  }

  @Test
  void stableCrashRecoveryRotatesTheKeyAndAuthenticates() throws Exception {
    create("--require-api-key");
    var field = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    field.setAccessible(true);
    var server = field.get(manager);
    var monotonic = LlamaServerOps.class.getDeclaredField("monotonicNanos");
    monotonic.setAccessible(true);
    var clock = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
    monotonic.set(server, (java.util.function.LongSupplier) clock::get);
    manager.switchToOnlineMode();
    String obsolete = key(0);
    clock.addAndGet(Duration.ofMinutes(11).toNanos());
    HealthyFakeLlamaServer.crashLatest(tmp.resolve("fake"));
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while ((launches.size() < 2 || !manager.isOnline()) && System.nanoTime() < end) Thread.sleep(50);
    assertEquals(2, launches.size(), "stable crash recovers exactly once");
    assertWorking();
    assertFalse(obsolete.equals(key(1)), "crash recovery must mint a new key");
    assertEquals(401, status(manager.servingPort(), "/props", obsolete));
  }

  @Test
  void aWrapperPassesTheKeyToTheServingProcessAndStopsWithIt() throws Exception {
    Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"),
        "explicit shell-wrapper fixture is POSIX; Windows launchers already wrap child JVMs");
    create("--require-api-key", "--wrapper");
    manager.switchToOnlineMode();
    assertWorking();
    long serving = Long.parseLong(Files.readAllLines(tmp.resolve("fake/pids.txt")).getLast().trim());
    assertTrue(control.list().stream().noneMatch(child -> child.pid() == serving),
        "the fixture must serve from a descendant, not the tracked wrapper PID");
    manager.switchToIndexingMode();
    assertTrue(ProcessHandle.of(serving).map(p -> !p.isAlive()).orElse(true),
        "stopping the owned wrapper also ends its serving descendant");
  }

  @Test
  void modelSwitchAndEngineRestartUseNewKeysAndRejectThePreviousKeys() throws Exception {
    create("--require-api-key");
    manager.switchToOnlineMode();
    String first = key(0);
    Path next = Files.createFile(tmp.resolve("next.gguf"));
    var applied = manager.applyResolvedConfig(new InferenceConfig(Path.of(launches.getFirst().argv().getFirst()),
        next, null, manager.servingPort(), 4096, 0, false),
        TestResolvedConfigHelper.fromEntries(Map.of("justsearch.context.size", "4096",
            "justsearch.data.dir", tmp.resolve("data").toString())),
        InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE);
    assertNull(applied.failure());
    assertWorking();
    assertEquals(2, launches.size(), "model switch launches exactly once");
    String switched = key(1);
    assertFalse(first.equals(switched));
    assertEquals(401, status(manager.servingPort(), "/props", first));
    manager.close();
    manager = null;
    create("--require-api-key");
    manager.switchToOnlineMode();
    assertWorking();
    assertEquals(3, launches.size(), "new Engine composition starts a fresh managed server");
    String restarted = key(2);
    assertFalse(restarted.equals(first) || restarted.equals(switched));
    assertEquals(401, status(manager.servingPort(), "/props", switched));
  }

  private void assertWorking() throws Exception {
    assertTrue(manager.isOnline());
    assertEquals(Optional.of(3), manager.countTokens("test"));
    assertTrue(manager.supportsTokenize());
    assertTrue(manager.supportsApplyTemplate());
    assertEquals(Optional.of("user: test"), manager.applyTemplate(
        List.of(Map.of("role", "user", "content", "test"))));
    assertEquals("ok", manager.chatCompletion(
        List.of(Map.of("role", "user", "content", "test")), 1).get(10, TimeUnit.SECONDS));
    var window = manager.launchedContextWindow();
    assertEquals(window == null ? 4096 : window.rung(), manager.lastKnownContextTokens());
  }

  private String key(int launch) {
    String value = launches.get(launch).envOverrides().get("LLAMA_API_KEY");
    assertTrue(value != null && value.matches("[A-Za-z0-9_-]{43}"),
        "a launch needs 32 random bytes encoded as unpadded base64url");
    return value;
  }

  private void create(String... behaviour) throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake"), behaviour);
    Path model = tmp.resolve("model.gguf");
    if (!Files.exists(model)) Files.createFile(model);
    int port;
    try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    var resolved = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.context.size", "4096", "justsearch.data.dir", tmp.resolve("data").toString()));
    manager = new InferenceLifecycleManager(executors,
        new InferenceConfig(executable, model, null, port, 4096, 0, false),
        NoopInferenceTelemetryEvents.INSTANCE, control, resolved, ignored -> () -> {});
  }

  private static int status(int port, String path, String key) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(2));
    if (key != null) request.header("Authorization", "Bearer " + key);
    try (var http = HttpClient.newHttpClient()) {
      return http.send(request.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
  }
}
