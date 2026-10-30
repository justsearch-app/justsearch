/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.ui.HeadlessApp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.power.EnergyStatePoller;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.util.EnergyState;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** BP38: replace only the existing constructor's host-observation supplier, then run real main. */
final class IndexingRulesBootstrapIntegrationTest {
  @TempDir Path directory;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  @Timeout(240)
  void fullMainHoldsFreshBatteryAndTimesOutUnknownWithDurablyQueuedWork() throws Exception {
    Path corpus = Files.createDirectories(directory.resolve("corpus"));
    Path file = Files.writeString(corpus.resolve("proof.txt"), "bp38uniquecorpus proof of real queued extraction");
    long deadline;
    // Work is queued through the actual operation route and survives the ordinary process stop.
    try (var seed = new ChildProcess("seed", "open")) {
      seed.awaitAvailable();
      seed.operation("core.indexing-pause", Map.of());
      var rules = new java.util.HashMap<String, Object>(); rules.put("schedule", null); rules.put("pauseOnBattery", true);
      seed.operation("core.set-indexing-rules", rules);
      seed.operation("core.ingest-files", Map.of("paths", List.of(file.toString()), "collection", "default"));
      seed.awaitStatus(s -> s.path("operating").path("indexing").path("pendingJobs").asLong() > 0);
      deadline = System.currentTimeMillis() + 10_000;
      seed.operation("core.indexing-pause", Map.of("untilEpochMs", deadline));
    }
    while (System.currentTimeMillis() <= deadline) Thread.sleep(25);
    try (var battery = new ChildProcess("battery", "blocked")) {
      battery.awaitLog("BP38_TEST_ONLY_SUPPLIER_ENTER");
      var waiting = battery.status();
      assertFalse(waiting.path("indexAvailable").asBoolean(), "worker composition waits for its first reading");
      assertFalse(waiting.path("operating").path("indexing").path("reasons").toString().contains("on_battery"),
          "unknown is never advertised as battery");
      battery.control("RELEASE BATTERY");
      var held = battery.awaitStatus(s -> s.path("indexAvailable").asBoolean()
          && s.path("operating").path("indexing").path("reasons").toString().contains("on_battery"));
      assertTrue(held.path("operating").path("indexing").path("pendingJobs").asLong() > 0,
          "fresh BATTERY before timeout starts with the persisted queue held");
      assertEquals("PAUSED", held.path("operating").path("indexing").path("state").asText());
    }
    try (var timeout = new ChildProcess("timeout", "blocked")) {
      timeout.awaitLog("BP38_TEST_ONLY_SUPPLIER_ENTER");
      var running = timeout.awaitStatus(s -> s.path("indexAvailable").asBoolean()
          && s.path("operating").path("indexing").path("pendingJobs").asLong(-1) == 0);
      assertFalse(running.path("operating").path("indexing").path("reasons").toString().contains("on_battery"));
      timeout.control("RELEASE BATTERY");
      timeout.awaitStatus(s -> s.path("operating").path("indexing").path("reasons").toString().contains("on_battery"));
      long started = System.nanoTime();
      timeout.control("SET AC");
      timeout.awaitStatus(s -> !s.path("operating").path("indexing").path("reasons").toString().contains("on_battery"));
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(16), "D8: real 15s cadence publishes AC within 16s");
      timeout.control("SET BATTERY");
      timeout.awaitStatus(s -> s.path("operating").path("indexing").path("reasons").toString().contains("on_battery"));
      timeout.control("SET UNKNOWN");
      timeout.awaitStatus(s -> "UNKNOWN".equals(s.path("power").path("source").asText())
          && !s.path("operating").path("indexing").path("reasons").toString().contains("on_battery"));
      assertTrue(JSON.readTree(Files.readString(directory.resolve("home/ui/settings.json")))
          .path("settings").path("indexingPause").path("pauseOnBattery").booleanValue(),
          "UNKNOWN never disables or discards the preference");
      assertTrue(Files.readString(timeout.log).contains("BP38_TEST_ONLY_CONSTRUCTOR_SUPPLIER"), "constructor advice executed");
    }
  }

  @Test
  @Timeout(90)
  void sameBootstrapCloseStartRearmsTheFreshReadingBarrier() throws Exception {
    try (var lifecycle = new ChildProcess("lifecycle", "lifecycle")) {
      assertTrue(lifecycle.process.waitFor(60, TimeUnit.SECONDS), "owned lifecycle child exits");
      assertEquals(0, lifecycle.process.exitValue(), Files.readString(lifecycle.log));
      assertTrue(Files.readString(lifecycle.log).contains("BP38_SAME_BOOTSTRAP_REARMED"));
    }
  }

  private final class ChildProcess implements AutoCloseable {
    private final Path log;
    private final Process process;
    private final java.time.Instant birth;
    private final HttpClient http = HttpClient.newHttpClient();
    private int port;

    ChildProcess(String name, String mode) throws Exception {
      Path home = Files.createDirectories(directory.resolve("home"));
      Path data = Files.createDirectories(directory.resolve("data"));
      Files.createDirectories(home.resolve("ui"));
      if (!Files.exists(home.resolve("ui/settings.json"))) {
        var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, home.resolve("ui/settings.json"));
        var initial = new UiSettings(); initial.setChatEnabled(false);
        store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
      }
      log = directory.resolve(name + ".log");
      Path arguments = directory.resolve(name + ".args");
      var options = new java.util.ArrayList<>(List.of("--enable-native-access=ALL-UNNAMED", "-XX:+EnableDynamicAgentLoading",
          "-Dnet.bytebuddy.experimental=true", "-Xmx384m", "-Djustsearch.prod=false",
          "-Duser.home=" + home, "-Djustsearch.home=" + home, "-Djustsearch.data.dir=" + data,
          "-Djustsearch.index.base_path=" + data.resolve("index/default"), "-Djustsearch.api.port=0"));
      for (var key : List.of("justsearch.gpu.enabled", "justsearch.ai.embed.enabled", "justsearch.splade.enabled",
          "justsearch.ner.enabled", "justsearch.bgem3.enabled", "justsearch.rerank.enabled", "justsearch.citation.scorer.enabled")) {
        options.add("-D" + key + "=false");
      }
      options.addAll(List.of("-cp", System.getProperty("java.class.path"), Child.class.getName(), mode, directory.toString()));
      Files.writeString(arguments, options.stream().map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
          .collect(java.util.stream.Collectors.joining("\n")));
      var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
          .redirectErrorStream(true).redirectOutput(log.toFile());
      var env = builder.environment();
      env.keySet().removeIf(k -> k.startsWith("JUSTSEARCH_") || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS",
          "_JAVA_OPTIONS", "DISPLAY", "WAYLAND_DISPLAY").contains(k));
      env.put("CUDA_VISIBLE_DEVICES", "-1"); env.put("HOME", home.toString());
      process = builder.start(); birth = process.info().startInstant().orElseThrow();
      System.out.println("BP38 child " + name + " pid=" + process.pid() + " birth=" + birth);
    }

    void control(String command) throws Exception {
      process.getOutputStream().write((command + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      process.getOutputStream().flush();
      System.out.println("BP38 control " + command + " at " + java.time.Instant.now());
    }

    void awaitLog(String marker) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
      while (process.isAlive() && !Files.readString(log).contains(marker) && System.nanoTime() < until) Thread.sleep(10);
      assertTrue(Files.readString(log).contains(marker), Files.readString(log));
    }

    int port() throws Exception {
      if (port != 0) return port;
      var manifest = directory.resolve("data/runtime/manifest.json");
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
      while (process.isAlive() && System.nanoTime() < until) {
        if (Files.exists(manifest)) {
          try { port = JSON.readTree(Files.readString(manifest)).path("head").path("apiPort").asInt(); }
          catch (RuntimeException incomplete) { /* atomic publisher may still be preparing */ }
          if (port > 0) return port;
        }
        Thread.sleep(10);
      }
      fail("no actual API port: " + Files.readString(log)); return 0;
    }

    JsonNode status() throws Exception {
      var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/api/status?fresh=true"))
          .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      return JSON.readTree(response.body());
    }

    JsonNode awaitStatus(java.util.function.Predicate<JsonNode> matches) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      JsonNode latest;
      do {
        latest = status();
        if (matches.test(latest)) { System.out.println("BP38 status " + latest); return latest; }
        Thread.sleep(50);
      } while (process.isAlive() && System.nanoTime() < until);
      fail("status condition not reached: " + latest + "\n" + Files.readString(log)); return latest;
    }

    void awaitAvailable() throws Exception { awaitStatus(s -> s.path("indexAvailable").asBoolean()); }

    void operation(String id, Map<String, Object> args) throws Exception {
      var body = JSON.writeValueAsString(Map.of("args", args, "idempotencyKey", OperationKeys.generate(Clock.systemUTC())));
      var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/api/operations/" + id + "/invoke"))
          .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
      System.out.println("BP38 POST " + id + " " + body + " -> " + response.statusCode() + " " + response.body());
      assertEquals(200, response.statusCode(), response.body());
      assertTrue(JSON.readTree(response.body()).path("success").asBoolean(), response.body());
    }

    @Override public void close() throws Exception {
      try {
        if (process.isAlive()) {
          assertEquals(birth, process.info().startInstant().orElseThrow());
          process.destroy();
          if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
        }
        http.close();
        if (port > 0) {
          assertThrows(java.io.IOException.class, () -> {
            try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 200); }
          }, "owned Engine port is closed");
        }
      } finally { System.out.println("BP38 " + log.getFileName() + "\n" + Files.readString(log)); }
    }
  }

  /** Test-source process only. No production selector or route exists. */
  public static final class Child {
    private Child() {}
    public static void main(String[] args) throws Exception {
      Control.gate = new CountDownLatch("open".equals(args[0]) || "lifecycle".equals(args[0]) ? 0 : 1);
      new net.bytebuddy.agent.builder.AgentBuilder.Default().disableClassFormatChanges()
          .with(net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .type(net.bytebuddy.matcher.ElementMatchers.named("io.justsearch.app.services.power.EnergyStatePoller"))
          .transform((builder, type, loader, module, domain) -> builder.visit(net.bytebuddy.asm.Advice.to(ConstructorAdvice.class)
              .on(net.bytebuddy.matcher.ElementMatchers.isConstructor().and(net.bytebuddy.matcher.ElementMatchers.takesArguments(3)))))
          .installOn(net.bytebuddy.agent.ByteBuddyAgent.install());
      if ("lifecycle".equals(args[0])) { lifecycle(Path.of(args[1])); return; }
      Thread.ofPlatform().daemon().name("bp38-parent-control").start(() -> {
        try (var input = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
          String command;
          while ((command = input.readLine()) != null) {
            String[] words = command.split(" ");
            Control.source.set(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.valueOf(words[1])));
            if ("RELEASE".equals(words[0])) Control.gate.countDown();
          }
        } catch (Exception failure) { failure.printStackTrace(); Runtime.getRuntime().halt(72); }
      });
      HeadlessApp.main(new String[0]);
    }

    private static void lifecycle(Path directory) throws Exception {
      var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
      var builder = io.justsearch.configuration.resolved.ResolvedConfig.builder();
      builder.putDefault("justsearch.data.dir", directory.resolve("data").toString());
      builder.putDefault("justsearch.index.base_path", directory.resolve("data/index").toString());
      builder.putDefault("justsearch.extraction.sandbox.mode", "in_process");
      for (var key : new io.justsearch.configuration.EnvRegistry[] {
          io.justsearch.configuration.EnvRegistry.AI_EMBED_ENABLED, io.justsearch.configuration.EnvRegistry.SPLADE_ENABLED,
          io.justsearch.configuration.EnvRegistry.NER_ENABLED, io.justsearch.configuration.EnvRegistry.BGE_M3_ENABLED,
          io.justsearch.configuration.EnvRegistry.RERANK_ENABLED, io.justsearch.configuration.EnvRegistry.CITATION_SCORER_ENABLED}) {
        builder.putDefault(key.configKey(), "false");
      }
      var config = builder.build();
      var installed = new io.justsearch.configuration.resolved.ConfigStore(config);
      io.justsearch.configuration.resolved.ConfigStore.setGlobal(installed);
      var opens = new AtomicInteger();
      var root = new EngineRoot(mock(io.justsearch.app.api.operations.OperationStore.class),
          mock(io.justsearch.app.api.operations.OperationAttemptRunner.class), (gauge, executors, ingestion, index, encoders) -> {
          opens.incrementAndGet();
          return new io.justsearch.indexerworker.server.KnowledgeServer(executors, io.justsearch.indexerworker.WorkerConfig.load(config),
              new io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus(gauge),
              io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), ingestion, index, encoders, config);
          },
          5000, 100, ignored -> {}, () -> {}, io.justsearch.app.services.bootstrap.OperationAuthority.inMemory());
      var ksConfig = new io.justsearch.app.services.worker.KnowledgeServerConfig(false, directory.resolve("data"), directory, directory,
          5000L, 5000L, 1, 5000L, 1000L, 300000L, 100, 5000L, 0);
      var bootstrap = new io.justsearch.app.services.worker.KnowledgeServerBootstrap(root.executors(), ksConfig, null,
          root.components(), root.indexComponent(), root, false, root.publicationLock());
      try {
        root.indexingHold().setPolicy(false, null, null, null, true);
        bootstrap.start();
        assertFalse(root.indexingHold().held());
        bootstrap.close();
        Control.gate = new CountDownLatch(1);
        int calls = Control.calls.get();
        var restarted = java.util.concurrent.CompletableFuture.runAsync(() -> {
          try { bootstrap.start(); } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (Control.calls.get() == calls && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(Control.calls.get() > calls);
        assertFalse(restarted.isDone(), "cached AC cannot satisfy the second activation");
        assertEquals(1, opens.get(), "the worker host must not open before its fresh reading");
        assertFalse(root.indexingHold().held(), "unobserved BATTERY is not advertised");
        Control.source.set(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.BATTERY));
        Control.gate.countDown();
        restarted.get(15, TimeUnit.SECONDS);
        assertTrue(root.indexingHold().held());
        assertEquals(1, Control.installations.get(), "same poller and bootstrap, no reconstruction");
        System.out.println("BP38_SAME_BOOTSTRAP_REARMED");
      } finally {
        Control.gate.countDown(); bootstrap.close(); root.close(); root.processResources().close();
        io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(installed, previous);
      }
    }
  }

  public static final class ConstructorAdvice {
    private ConstructorAdvice() {}
    @net.bytebuddy.asm.Advice.OnMethodEnter
    public static void enter(@net.bytebuddy.asm.Advice.Argument(value = 2, readOnly = false) Supplier<EnergyState> supplier) {
      java.util.Objects.requireNonNull(supplier, "host observation supplier");
      supplier = Control.INSTANCE;
      Control.installations.incrementAndGet();
      System.out.println("BP38_TEST_ONLY_CONSTRUCTOR_SUPPLIER " + supplier.getClass().getName());
    }
  }

  public static final class Control implements Supplier<EnergyState> {
    public static final Control INSTANCE = new Control();
    public static final AtomicReference<EnergyState> source = new AtomicReference<>(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC));
    public static final AtomicInteger calls = new AtomicInteger();
    public static final AtomicInteger installations = new AtomicInteger();
    public static volatile CountDownLatch gate = new CountDownLatch(1);
    private Control() {}
    @Override public EnergyState get() {
      calls.incrementAndGet(); System.out.println("BP38_TEST_ONLY_SUPPLIER_ENTER " + System.nanoTime());
      var current = gate;
      try { current.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
      return source.get();
    }
  }
}
