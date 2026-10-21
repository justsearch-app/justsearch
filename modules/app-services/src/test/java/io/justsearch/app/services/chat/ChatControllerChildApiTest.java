/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.ChildView;
import io.justsearch.app.api.runtime.child.ExitClass;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.api.runtime.child.TerminateResult;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.telemetry.Telemetry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Spec A I8 (step 11) at the controller: the Engine's composition (lifecycle manager and chat
 * controller, {@link BootstrapInferenceFactory}) drives llama-server through an in-memory Spec B
 * child API (§9.1 F2). Every stop the controller's steps make carries the step's own specific
 * reason with {@code NOW}, is asked for before its child ends (intent before terminate) and ends
 * REQUESTED; an exit nobody asked for is UNEXPECTED and has no intent.
 */
final class ChatControllerChildApiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path tmp;
  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final FakeChildApi children = new FakeChildApi();
  private InferenceLifecycleManager manager;

  private final ConfigStore previousStore = ConfigStore.globalOrNull();

  @AfterEach
  void tearDown() {
    try {
      if (manager != null) manager.close();
    } finally {
      children.close();
      executors.close();
      TestResolvedConfigHelper.restoreGlobal(previousStore);
    }
  }

  @Test
  void everyStopAStepMakesCarriesTheStepsReasonAfterItsIntentAndEndsRequested() throws Exception {
    manager = manager();
    manager.switchToOnlineMode();
    InferenceConfig first = manager.currentConfig();

    manager.applyConfig(first, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);
    Path otherModel = Files.writeString(tmp.resolve("other.gguf"), "fixture; never loaded");
    manager.applyConfig(withModel(first, otherModel),
        InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);
    manager.enterVduMode();
    manager.exitVduMode();
    manager.switchToIndexingMode();

    assertEquals(List.of(
            "start", "stop RECONFIGURE NOW", "start", "stop MODEL_SWITCH NOW", "start",
            "stop VDU_ENTER NOW", "start", "stop VDU_EXIT NOW", "start", "stop DEACTIVATE NOW"),
        children.calls(), "start/stop calls in order, each stop with its step's reason");
    for (FakeChildApi.Stop stop : children.stops) {
      assertEquals("chat-controller", stop.request().requestedBy(), stop.toString());
      assertTrue(stop.intentAt() < stop.terminateAt(), "intent before terminate: " + stop);
      ExitOutcome exit = stop.child().exit().toCompletableFuture().getNow(null);
      assertNotNull(exit, "the stopped child ended: " + stop);
      assertEquals(ExitClass.REQUESTED, exit.exitClass(), stop.toString());
      assertEquals(Optional.of(stop.request().reason()), exit.reason(), stop.toString());
    }
    assertEquals(List.of(), children.terminatedWithoutIntent, "nothing ends without an intent");
  }

  @Test
  void anExitNobodyAskedForIsUnexpectedAndHasNoIntent() throws Exception {
    manager = manager();
    manager.switchToOnlineMode();
    FakeChildApi.Child crashed = children.latest();

    crashed.exitOnItsOwn(1);

    // A crash right after READY is T29 (CRASH_LOOP): chat leaves ONLINE and is not relaunched.
    assertTrue(await(() -> !manager.isOnline()), "the crash reached the controller");
    ExitOutcome exit = crashed.exit().toCompletableFuture().getNow(null);
    assertEquals(ExitClass.UNEXPECTED, exit.exitClass());
    assertTrue(exit.reason().isEmpty(), "nobody asked it to stop");
    assertEquals(List.of("start"), children.calls(), "no stop is asked of a server that exited");
    assertEquals(List.of(), children.terminatedWithoutIntent);
  }

  // ==================== the composition ====================

  private InferenceLifecycleManager manager() throws IOException {
    Path root = Files.createDirectories(tmp.resolve("home"));
    Path server = Files.writeString(root.resolve("llama-server.exe"), "fixture; never launched");
    if (!io.justsearch.configuration.PlatformPaths.isWindows()) {
      assertTrue(server.toFile().setExecutable(true, true));
    }
    Path model = Files.writeString(root.resolve("model.gguf"), "fixture; never loaded");
    var builder = new ResolvedConfigBuilder();
    builder.putDefault("justsearch.home", root.toString());
    builder.putDefault("justsearch.data.dir", tmp.resolve("data").toString());
    builder.putDefault("justsearch.server.exe", server.toString());
    builder.put("justsearch.llm.model_path", 400, "env_var", "test", model.toString());
    builder.putDefault("justsearch.context.size", "4096");
    builder.putDefault("justsearch.gpu.layers", "0");
    builder.putDefault("justsearch.ai.disabled", "false");
    builder.putDefault("justsearch.llm.enabled", "true");
    ResolvedConfig resolved = builder.build();
    ConfigStore.setGlobal(new ConfigStore(resolved));
    var created = BootstrapInferenceFactory.createInferenceManager(executors, true, resolved,
        root.toString(), mock(Telemetry.class), LoggerFactory.getLogger(getClass()), children);
    assertNotNull(created);
    return created;
  }

  private static InferenceConfig withModel(InferenceConfig config, Path model) {
    return new InferenceConfig(config.serverExecutable(), model, config.mmprojPath(),
        config.serverPort(), config.contextSize(), config.gpuLayers(), config.vduMode(),
        config.chatProfileId());
  }

  private static boolean await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + BOUND.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      TimeUnit.MILLISECONDS.sleep(20);
    }
    return condition.getAsBoolean();
  }

  /**
   * Spec A §9.1 F2: an in-memory Spec B child API. A started child is an in-process stand-in that
   * answers llama-server's health, props and completion endpoints on the port its argv names. Each
   * {@code start} and {@code stop(reason, mode)} is recorded in order; a stop records its intent,
   * then terminates the stand-in, then ends the child REQUESTED. An exit the test scripts ends it
   * UNEXPECTED with no intent.
   */
  static final class FakeChildApi implements ChildControl {
    record Stop(Child child, StopRequest request, long intentAt, long terminateAt) {}

    private final AtomicLong clock = new AtomicLong();
    private final AtomicLong ids = new AtomicLong(50_000);
    final List<Child> started = new CopyOnWriteArrayList<>();
    final List<Stop> stops = new CopyOnWriteArrayList<>();
    final List<String> terminatedWithoutIntent = new CopyOnWriteArrayList<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    List<String> calls() {
      return List.copyOf(calls);
    }

    Child latest() {
      return started.getLast();
    }

    @Override
    public ChildHandle start(ChildSpec spec, ManifestEntry manifest) throws IOException {
      Child child = new Child(spec);
      started.add(child);
      calls.add("start");
      return child;
    }

    @Override
    public CompletionStage<ExitOutcome> stop(ChildHandle handle, StopRequest request) {
      Child child = (Child) handle;
      long intentAt = clock.incrementAndGet();
      calls.add("stop " + request.reason() + " " + request.mode());
      if (child.exit.isDone()) {
        stops.add(new Stop(child, request, intentAt, Long.MAX_VALUE));
        return child.exit;
      }
      child.intent = request;
      long terminateAt = child.terminate();
      stops.add(new Stop(child, request, intentAt, terminateAt));
      Instant now = Instant.now();
      child.exit.complete(new ExitOutcome(child.childId(), child.kind(), ExitClass.REQUESTED,
          Optional.of(request.reason()), request.detail(), Optional.of(request.mode()),
          OptionalInt.of(0x4A530001), Optional.of(now), now, Optional.of(now), Optional.of(now),
          TerminateResult.OK, false, ExitOutcome.Phase.STOPPING, true));
      return child.exit;
    }

    @Override
    public CompletionStage<List<ExitOutcome>> stopAll(
        Collection<ChildHandle> handles, StopRequest request) {
      List<CompletableFuture<ExitOutcome>> each = new ArrayList<>();
      for (ChildHandle handle : handles) each.add(stop(handle, request).toCompletableFuture());
      return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
          .thenApply(done -> each.stream().map(CompletableFuture::join).toList());
    }

    @Override
    public void note(ChildHandle child, String token) {}

    @Override
    public boolean awaitGpuRelease(Duration max) {
      return true;
    }

    @Override
    public List<ChildView> list() {
      return List.of();
    }

    @Override
    public List<ExitOutcome> recentExits(int max) {
      return List.of();
    }

    @Override
    public void close() {
      for (Child child : started) child.terminate();
    }

    /** A started child: an in-process llama-server stand-in on the port its argv names. */
    final class Child implements ChildHandle {
      private final String id = "c-fake-" + ids.incrementAndGet();
      private final long pid = ids.get();
      private final ChildKind kind;
      private final HttpServer server;
      final CompletableFuture<ExitOutcome> exit = new CompletableFuture<>();
      volatile StopRequest intent;
      volatile long endedAt;

      Child(ChildSpec spec) throws IOException {
        this.kind = spec.kind();
        int port = Integer.parseInt(value(spec.argv(), "--port", "0"));
        String model = value(spec.argv(), "-m", "model.gguf").replace('\\', '/');
        String window = value(spec.argv(), "-c", "4096");
        server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/health", exchange -> answer(exchange, 200, "{\"status\":\"ok\"}"));
        var props = server.createContext("/props", exchange -> answer(exchange, 200,
            "{\"model_path\":\"" + model + "\",\"n_ctx\":" + window + "}"));
        var completion = server.createContext("/completion", exchange -> {
          exchange.getRequestBody().readAllBytes();
          answer(exchange, 200, "{\"content\":\"ok\",\"stop\":true}");
        });
        String key = spec.envOverrides().get("LLAMA_API_KEY");
        if (key != null) {
          for (var context : List.of(props, completion)) {
            context.getFilters().add(new com.sun.net.httpserver.Filter() {
              @Override public String description() { return "llama API authentication"; }
              @Override public void doFilter(com.sun.net.httpserver.HttpExchange exchange,
                  Chain chain) throws IOException {
                if (("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                  chain.doFilter(exchange);
                } else {
                  exchange.sendResponseHeaders(401, -1);
                  exchange.close();
                }
              }
            });
          }
        }
        server.start();
      }

      private static void answer(com.sun.net.httpserver.HttpExchange exchange, int status,
          String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      }

      /** Ends the stand-in; a child ended while no stop asked for it is recorded. */
      synchronized long terminate() {
        if (endedAt != 0) return endedAt;
        if (intent == null && !exit.isDone()) terminatedWithoutIntent.add(id);
        server.stop(0);
        endedAt = clock.incrementAndGet();
        return endedAt;
      }

      /** The stand-in dies on its own with {@code code}: UNEXPECTED, with no intent. */
      void exitOnItsOwn(int code) {
        synchronized (this) {
          server.stop(0);
          endedAt = clock.incrementAndGet();
        }
        Instant now = Instant.now();
        exit.complete(new ExitOutcome(id, kind, ExitClass.UNEXPECTED, Optional.empty(),
            Optional.empty(), Optional.empty(), OptionalInt.of(code), Optional.of(now), now,
            Optional.empty(), Optional.empty(), TerminateResult.NOT_CALLED, false,
            ExitOutcome.Phase.RUNNING, true));
      }

      @Override public String childId() { return id; }
      @Override public ChildKind kind() { return kind; }
      @Override public long pid() { return pid; }
      @Override public Instant processStartedAt() { return Instant.EPOCH; }
      @Override public OutputStream stdin() { return OutputStream.nullOutputStream(); }
      @Override public InputStream stdout() { return InputStream.nullInputStream(); }
      @Override public InputStream stderr() { return InputStream.nullInputStream(); }
      @Override public CompletionStage<ExitOutcome> exit() { return exit; }

      @Override
      public State state() {
        return exit.isDone() ? State.EXITED : State.RUNNING;
      }

      @Override
      public String toString() {
        return id;
      }
    }

    private static String value(List<String> argv, String flag, String fallback) {
      for (int i = 0; i + 1 < argv.size(); i++) {
        if (flag.equals(argv.get(i))) return argv.get(i + 1);
      }
      return fallback;
    }
  }
}
