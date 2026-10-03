/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

final class LlamaServerCandidateContextTest {

  @Test
  void explicitCandidateOwnsGpuArgvHashAndFailureSignalAcrossGlobalChanges(@TempDir Path tmp)
      throws Exception {
    ResolvedConfig globalA = resolved(false, false);
    ResolvedConfig candidateB = resolved(false, true);
    ResolvedConfig globalC = resolved(true, false);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = new ConfigStore(globalA);
    ConfigStore.setGlobal(installed);

    HttpServer server = healthyServer();
    Path model = Files.createFile(tmp.resolve("candidate-b.gguf"));
    Process child =
        new ProcessBuilder(
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows")
                            ? "java.exe"
                            : "java")
                    .toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ManagedLlamaAdoptionTest.SleepingChild.class.getName())
            .start();
    Path executable = Path.of(child.info().command().orElseThrow());
    InferenceConfig inference =
        new InferenceConfig(executable, model, null, server.getAddress().getPort(), 4096, 77, false);
    LlamaServerConfigContext context = new LlamaServerConfigContext(inference, candidateB);
    LlamaServerOps.StartRequest request =
        new LlamaServerOps.StartRequest(
            context, LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
    String candidateHash = ManagedLlamaConfigIdentity.declaredHash(inference, candidateB, 77);
    ManagedChild registeredChild =
        new ManagedChild(
            "candidate-b",
            ManagedChild.Kind.LLAMA_SERVER,
            child.pid(),
            child.info().startInstant().orElseThrow().toString(),
            ManagedChild.normalizePath(executable),
            "http://127.0.0.1:" + inference.serverPort(),
            model.toString(),
            candidateHash,
            "diagnostic-argv");
    AtomicReference<LlamaServerOps.StartResult> recoveryOwner = new AtomicReference<>();
    AtomicReference<java.util.function.BooleanSupplier> recoveryGuard = new AtomicReference<>();
    AtomicReference<LlamaServerOps> opsReference = new AtomicReference<>();
    LlamaServerOps ops =
        newOps(
            Mode.ONLINE,
            new RecordingObserver(),
            guard -> {
              recoveryGuard.set(guard);
              recoveryOwner.set(opsReference.get().activeStartResult().orElseThrow());
            },
            new FixedRegistry(registeredChild),
            ignored -> false);
    opsReference.set(ops);
    try {
      installed.update(globalC);
      assertEquals(77, request.effectiveGpuLayers());
      List<String> command =
          LlamaServerOps.buildLaunchCommand(
              inference,
              request.context().resolved(),
              request.effectiveGpuLayers(),
              ContextWindowPolicy.auto(true, null));
      assertEquals("77", command.get(command.indexOf("-ngl") + 1));
      assertEquals(candidateHash, ManagedLlamaConfigIdentity.declaredHash(
          inference, request.context().resolved(), request.effectiveGpuLayers()));
      assertNotEquals(
          candidateHash,
          ManagedLlamaConfigIdentity.declaredHash(inference, globalC, 0));

      LlamaServerOps.StartResult started = ops.startLlamaServer(request);
      ops.waitForServerHealth(started);
      assertEquals(LlamaServerOps.StartDisposition.ADOPTED_MANAGED, started.disposition());
      assertEquals(candidateHash, started.declaredConfigHash());
      assertSame(context, started.context());

      LlamaServerTestAccess.crashCurrent(ops);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (recoveryOwner.get() == null && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertNotNull(recoveryGuard.get());
      assertNotNull(recoveryOwner.get());
      assertTrue(recoveryGuard.get().getAsBoolean());
      assertSame(context, recoveryOwner.get().context());
      assertEquals(
          LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
          recoveryOwner.get().adoptionPolicy());
    } finally {
      ops.shutdown();
      child.destroyForcibly();
      server.stop(0);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void nativeRecoveryEntryPointAndCrashCounterAreRetired() {
    assertThrows(NoSuchMethodException.class,
        () -> LlamaServerOps.class.getDeclaredMethod("recoverActiveServer"));
    assertThrows(NoSuchFieldException.class,
        () -> LlamaServerOps.class.getDeclaredField("crashCount"));
  }

  @Test
  void nativeRecoverySchedulerRegistrationIsRetired() {
    assertThrows(NoSuchFieldException.class,
        () -> InferenceExecutorRegistrations.class.getDeclaredField("llamaRecovery"));
  }

  @Test
  void oneShotRecoveryHealthFailureCannotRelaunchFallbackLadders(@TempDir Path tmp)
      throws Exception {
    Path data = Files.createDirectories(tmp.resolve("data"));
    Path executable = Files.createFile(tmp.resolve("llama-server.exe"));
    Path model = Files.createFile(tmp.resolve("model.gguf"));
    ResolvedConfig resolved = resolved(true, true, data, tmp);
    InferenceConfig inference =
        new InferenceConfig(executable, model, null, 18081, 4096, 0, false);
    var context = new LlamaServerConfigContext(inference, resolved);
    var request = new LlamaServerOps.StartRequest(
        context, LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                    .toString(),
                "-cp", System.getProperty("java.class.path"),
                ManagedLlamaAdoptionTest.SleepingChild.class.getName())
            .start();
    Path expectedLog = expectedLogPath(data);
    var registry = new KillingRegistry(List.of(child), expectedLog);
    LlamaServerOps ops = newOps(
        Mode.ONLINE, new RecordingObserver(), ignored -> {}, registry, ignored -> false);
    try {
      try (MockedConstruction<ProcessBuilder> construction =
          Mockito.mockConstruction(
              ProcessBuilder.class,
              (builder, ignored) -> {
                Mockito.when(builder.environment()).thenReturn(new HashMap<>());
                Mockito.when(builder.start()).thenReturn(child);
              })) {
        LlamaServerOps.StartResult started = ops.startLlamaServer(request);

        ModeTransitionException failure = assertThrows(
            ModeTransitionException.class, () -> ops.waitForServerHealthOnce(started));

        assertEquals(ModeTransitionException.Reason.PROCESS_EXITED, failure.reason());
        assertEquals(1, construction.constructed().size(),
            "one admitted recovery may launch only once");
        assertEquals(1, registry.registered.size());
        assertTrue(registry.snapshot().isEmpty(), "dead managed-child ownership is retired");
        assertSame(context, ops.recoveryStartRequest().orElseThrow().context());
      }
    } finally {
      ops.shutdown();
      child.destroyForcibly();
    }
  }

  @Test
  void strictRequestRefusesUnregisteredServerWhileLegacyRequestAdopts(@TempDir Path tmp)
      throws Exception {
    HttpServer server = healthyServer();
    InferenceConfig inference = inference(tmp, server.getAddress().getPort(), "external");
    LlamaServerConfigContext context = new LlamaServerConfigContext(inference, resolved(false, true));
    LlamaServerOps ops = newOps(Mode.OFFLINE, new RecordingObserver(), ignored -> {});
    try {
      ModeTransitionException refused =
          assertThrows(
              ModeTransitionException.class,
              () ->
                  ops.startLlamaServer(
                      new LlamaServerOps.StartRequest(
                          context,
                          LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS)));
      assertEquals(ModeTransitionException.Reason.EXTERNAL_SERVER_CONFLICT, refused.reason());
      assertFalse(ops.activeStartResult().isPresent());

      LlamaServerOps.StartResult adopted =
          ops.startLlamaServer(
              new LlamaServerOps.StartRequest(
                  context, LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL));
      assertEquals(LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL, adopted.disposition());
      assertSame(adopted, ops.activeStartResult().orElseThrow());
    } finally {
      ops.stopLlamaServer();
      ops.shutdown();
      server.stop(0);
    }
  }

  @Test
  void armedLaunchedChildCleanExitSignalsCapturedOwnerFailure(@TempDir Path tmp) throws Exception {
    HttpServer server = healthyServer();
    Process child =
        new ProcessBuilder(
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows")
                            ? "java.exe"
                            : "java")
                    .toString(),
                "-cp",
                System.getProperty("java.class.path"),
                CleanExitChild.class.getName())
            .start();
    Path executable = Path.of(child.info().command().orElseThrow());
    Path model = Files.createFile(tmp.resolve("clean-exit.gguf"));
    InferenceConfig inference =
        new InferenceConfig(executable, model, null, server.getAddress().getPort(), 4096, 0, false);
    LlamaServerConfigContext context =
        new LlamaServerConfigContext(inference, resolved(false, true, tmp));
    String hash = ManagedLlamaConfigIdentity.declaredHash(inference, context.resolved(), 0);
    LlamaServerOps.StartResult start =
        new LlamaServerOps.StartResult(
            context,
            LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
            LlamaServerOps.StartDisposition.LAUNCHED_MANAGED,
            hash);
    CountDownLatch recovery = new CountDownLatch(1);
    AtomicReference<java.util.function.BooleanSupplier> guard = new AtomicReference<>();
    LlamaServerOps ops =
        newOps(
            Mode.ONLINE,
            new RecordingObserver(),
            stillOwned -> {
              guard.set(stillOwned);
              recovery.countDown();
            });
    try {
      installActive(ops, start, child);
      ops.waitForServerHealth(start);

      assertTrue(child.waitFor(5, TimeUnit.SECONDS));
      assertEquals(0, child.exitValue());
      assertTrue(recovery.await(5, TimeUnit.SECONDS));
      assertNotNull(guard.get());
      assertTrue(guard.get().getAsBoolean());
    } finally {
      ops.shutdown();
      child.destroyForcibly();
      server.stop(0);
    }
  }

  private static void installActive(
      LlamaServerOps ops, LlamaServerOps.StartResult start, Process process) throws Exception {
    java.lang.reflect.Field processField = LlamaServerOps.class.getDeclaredField("process");
    processField.setAccessible(true);
    processField.set(ops, process);
    java.lang.reflect.Method install =
        LlamaServerOps.class.getDeclaredMethod(
            "installActive", LlamaServerOps.StartResult.class, Process.class,
            ProcessHandle.class);
    install.setAccessible(true);
    install.invoke(ops, start, process, null);
  }

  @Test
  void stalePhysicalPropsResponseCannotPublishAfterOwnerReplacement(@TempDir Path tmp)
      throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger propsCalls = new AtomicInteger();
    HttpServer server = serverWithProps(blocked, release, propsCalls);
    InferenceConfig first = inference(tmp, server.getAddress().getPort(), "first");
    InferenceConfig second = inference(tmp, server.getAddress().getPort(), "second");
    RecordingObserver observer = new RecordingObserver();
    LlamaServerOps ops = newOps(Mode.OFFLINE, observer, ignored -> {});
    var executor = Executors.newSingleThreadExecutor();
    try {
      LlamaServerOps.StartResult oldOwner = legacyExternal(ops, first);
      var waiting = executor.submit(() -> {
        ops.waitForServerHealth(oldOwner);
        return null;
      });
      assertTrue(blocked.await(5, TimeUnit.SECONDS));

      LlamaServerOps.StartResult replacement = legacyExternal(ops, second);
      int observationsAfterReplacement = observer.modelIds.size();
      release.countDown();
      var superseded = assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS));
      assertEquals(ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
          assertInstanceOf(ModeTransitionException.class, superseded.getCause()).reason());

      assertSame(replacement, ops.activeStartResult().orElseThrow());
      assertEquals(observationsAfterReplacement, observer.modelIds.size());
    } finally {
      release.countDown();
      executor.shutdownNow();
      ops.stopLlamaServer();
      ops.shutdown();
      server.stop(0);
    }
  }

  @Test
  void stalePhysicalHealthFailureCannotMutateReplacementDiagnostics(@TempDir Path tmp)
      throws Exception {
    assertStaleHealthDoesNotMutateReplacement(tmp, true, "failure");
  }

  @Test
  void stalePhysicalHealthSuccessCannotMutateReplacementDiagnostics(@TempDir Path tmp)
      throws Exception {
    assertStaleHealthDoesNotMutateReplacement(tmp, false, "success");
  }

  private static void assertStaleHealthDoesNotMutateReplacement(
      Path tmp, boolean failBlockedResult, String suffix) throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger healthCalls = new AtomicInteger();
    AtomicBoolean failBlockedHealth = new AtomicBoolean(failBlockedResult);
    HttpServer server = serverWithHealth(blocked, release, healthCalls, failBlockedHealth);
    InferenceConfig first =
        inference(tmp, server.getAddress().getPort(), "first-health-" + suffix);
    InferenceConfig second =
        inference(tmp, server.getAddress().getPort(), "second-health-" + suffix);
    LlamaServerOps ops = newOps(Mode.ONLINE, new RecordingObserver(), ignored -> {});
    var executor = Executors.newSingleThreadExecutor();
    try {
      legacyExternal(ops, first);
      var checking = executor.submit(() -> {
        LlamaServerTestAccess.checkCurrentHealth(ops);
        return null;
      });
      assertTrue(blocked.await(5, TimeUnit.SECONDS));
      LlamaServerOps.StartResult replacement = legacyExternal(ops, second);
      release.countDown();
      checking.get(5, TimeUnit.SECONDS);

      InferenceLifecycleManager.ExternalServerDiagnostics diagnostics =
          ops.buildExternalDiagnostics(true);
      assertEquals(0, diagnostics.consecutiveHealthFailures());
      assertNull(diagnostics.lastHealthError());
      assertEquals(0, diagnostics.lastHealthOkAtMs());
      assertSame(replacement, ops.activeStartResult().orElseThrow());
    } finally {
      release.countDown();
      executor.shutdownNow();
      ops.stopLlamaServer();
      ops.shutdown();
      server.stop(0);
    }
  }

  private static LlamaServerOps.StartResult legacyExternal(
      LlamaServerOps ops, InferenceConfig inference) throws Exception {
    return ops.startLlamaServer(
        new LlamaServerOps.StartRequest(
            new LlamaServerConfigContext(inference, resolved(false, true)),
            LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL));
  }

  private static InferenceConfig inference(Path tmp, int port, String name) throws Exception {
    Path executable = tmp.resolve(name + "-llama.exe");
    Path model = tmp.resolve(name + ".gguf");
    if (!Files.exists(executable)) Files.createFile(executable);
    if (!Files.exists(model)) Files.createFile(model);
    return new InferenceConfig(executable, model, null, port, 4096, 4, false);
  }

  private static ResolvedConfig resolved(boolean thinking, boolean gpuAllowed) {
    return resolved(thinking, gpuAllowed, null);
  }

  private static ResolvedConfig resolved(
      boolean thinking, boolean gpuAllowed, Path dataDir) {
    return resolved(thinking, gpuAllowed, dataDir, null);
  }

  private static ResolvedConfig resolved(
      boolean thinking, boolean gpuAllowed, Path dataDir, Path repoRoot) {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putDefault("justsearch.context.size", "4096");
    builder.putDefault("justsearch.llm.slots", "1");
    builder.putDefault("justsearch.llm.kv_type", "q8_0");
    builder.putDefault("justsearch.llm.use_thinking", String.valueOf(thinking));
    builder.putDefault("justsearch.llm.reasoning_budget", thinking ? "256" : "0");
    builder.putDefault("policy.gpu_acceleration_enabled", String.valueOf(gpuAllowed));
    if (dataDir != null) builder.putDefault("justsearch.data.dir", dataDir.toString());
    if (repoRoot != null) builder.putDefault("justsearch.repo.root", repoRoot.toString());
    return builder.build();
  }

  private static HttpServer healthyServer() throws Exception {
    return serverWithProps(new CountDownLatch(0), new CountDownLatch(0), new AtomicInteger());
  }

  private static Path expectedLogPath(Path dataDir) {
    String home = System.getenv("JUSTSEARCH_HOME");
    return home == null || home.isBlank()
        ? dataDir.resolve("logs").resolve("llama-server.log")
        : Path.of(home).resolve("logs").resolve("llama-server.log");
  }

  private static HttpServer serverWithProps(
      CountDownLatch blocked,
      CountDownLatch release,
      AtomicInteger propsCalls)
      throws Exception {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/health", exchange -> {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.createContext("/props", exchange -> {
      int call = propsCalls.incrementAndGet();
      if (call == 2) {
        blocked.countDown();
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] body = "{\"model_path\":\"stale-a.gguf\",\"n_ctx\":4096}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.setExecutor(Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "candidate-context-props");
      thread.setDaemon(true);
      return thread;
    }));
    server.start();
    return server;
  }

  private static HttpServer serverWithHealth(
      CountDownLatch blocked,
      CountDownLatch release,
      AtomicInteger healthCalls,
      AtomicBoolean failBlocked)
      throws Exception {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/health", exchange -> {
      int call = healthCalls.incrementAndGet();
      int status = 200;
      if (call == 2) {
        blocked.countDown();
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        if (failBlocked.get()) status = 503;
      }
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    });
    server.createContext("/props", exchange -> {
      byte[] body = "{\"model_path\":\"external.gguf\",\"n_ctx\":4096}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.setExecutor(Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "candidate-context-health");
      thread.setDaemon(true);
      return thread;
    }));
    server.start();
    return server;
  }

  private static LlamaServerOps newOps(
      Mode mode,
      PropsObserver observer,
      java.util.function.Consumer<java.util.function.BooleanSupplier> recovery) {
    return newOps(mode, observer, recovery, ManagedChildRegistry.noop(), handle -> true);
  }

  private static LlamaServerOps newOps(
      Mode mode,
      PropsObserver observer,
      java.util.function.Consumer<java.util.function.BooleanSupplier> recovery,
      ManagedChildRegistry registry,
      LlamaServerOps.ManagedHandleTermination termination) {
    return new LlamaServerOps(
        new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        null,
        () -> mode,
        observer,
        recovery,
        (ignored, guard) -> {},
        NoopInferenceTelemetryEvents.INSTANCE,
        registry,
        termination);
  }

  private static final class RecordingObserver implements PropsObserver {
    private final java.util.concurrent.CopyOnWriteArrayList<String> modelIds =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicReference<Integer> context = new AtomicReference<>();

    @Override
    public void onModelIdObserved(String modelId, LlamaServerConfigContext ignored) {
      modelIds.add(modelId);
    }

    @Override
    public void onContextTokensObserved(int contextTokens) {
      context.set(contextTokens);
    }

    @Override
    public String observedModelId() {
      return modelIds.isEmpty() ? null : modelIds.get(modelIds.size() - 1);
    }

    @Override
    public Integer observedContextTokens() {
      return context.get();
    }
  }

  private static final class FixedRegistry implements ManagedChildRegistry {
    private final ManagedChild child;

    private FixedRegistry(ManagedChild child) {
      this.child = child;
    }

    @Override
    public List<ManagedChild> snapshot() {
      return List.of(child);
    }

    @Override
    public void register(ManagedChild ignored) {}

    @Override
    public void remove(String ignored) {}
  }

  private static final class KillingRegistry implements ManagedChildRegistry {
    private final List<ManagedChild> children = new ArrayList<>();
    private final List<Process> attempts;
    private final Path firstLaunchOutput;
    private final List<ManagedChild> registered =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    private KillingRegistry(List<Process> attempts, Path firstLaunchOutput) {
      this.attempts = List.copyOf(attempts);
      this.firstLaunchOutput = firstLaunchOutput;
    }

    @Override
    public synchronized List<ManagedChild> snapshot() {
      return List.copyOf(children);
    }

    @Override
    public synchronized void register(ManagedChild child) throws java.io.IOException {
      children.removeIf(existing -> existing.id().equals(child.id()));
      children.add(child);
      registered.add(child);
      if (registered.size() == 1 && firstLaunchOutput != null) {
        Files.writeString(
            firstLaunchOutput,
            "error while handling argument \"--reasoning-budget\": invalid value\n");
      }
      Process physical =
          attempts.stream()
              .filter(candidate -> candidate.pid() == child.pid())
              .findFirst()
              .orElseThrow(() -> new java.io.IOException("registered unknown physical child"));
      physical.destroyForcibly();
      try {
        if (!physical.waitFor(5, TimeUnit.SECONDS)) {
          throw new java.io.IOException("physical child did not exit");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new java.io.IOException("interrupted waiting for physical child", interrupted);
      }
    }

    @Override
    public synchronized void remove(String childId) {
      children.removeIf(child -> child.id().equals(childId));
    }
  }

  public static final class CleanExitChild {
    public static void main(String[] args) throws Exception {
      Thread.sleep(1_000);
    }
  }
}
