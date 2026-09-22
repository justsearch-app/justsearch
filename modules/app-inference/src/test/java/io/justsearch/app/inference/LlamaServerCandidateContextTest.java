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
import java.util.ArrayDeque;
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
  void explicitCandidateOwnsGpuArgvHashAndScheduledRecoveryAcrossGlobalChanges(@TempDir Path tmp)
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
  void recoveryContextRelaunchFinalFailureAdvancesSameCrashEpisode(@TempDir Path tmp)
      throws Exception {
    HttpServer server = healthyServer();
    Process child = startSleepingChild();
    List<Process> launchAttempts = new ArrayList<>();
    for (int i = 0; i < 8; i++) launchAttempts.add(startSleepingChild());
    Path executable = Path.of(child.info().command().orElseThrow());
    Path model = Files.createFile(tmp.resolve("recovery.gguf"));
    InferenceConfig inference =
        new InferenceConfig(executable, model, null, server.getAddress().getPort(), 4096, 0, false);
    LlamaServerConfigContext context =
        new LlamaServerConfigContext(inference, resolved(false, true, tmp));
    String hash = ManagedLlamaConfigIdentity.declaredHash(inference, context.resolved(), 0);
    ManagedChild managed = managedChild(child, executable, model, inference.serverPort(), hash);
    KillingRegistry registry = new KillingRegistry(managed, launchAttempts);
    List<List<String>> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
    AtomicReference<LlamaServerOps> opsReference = new AtomicReference<>();
    AtomicInteger recoveryCalls = new AtomicInteger();
    AtomicReference<java.util.function.BooleanSupplier> firstRecoveryGuard =
        new AtomicReference<>();
    CountDownLatch firstRecovery = new CountDownLatch(1);
    CountDownLatch secondRecovery = new CountDownLatch(1);
    LlamaServerOps ops =
        newOps(
            Mode.ONLINE,
            new RecordingObserver(),
            guard -> {
              if (!guard.getAsBoolean()) return;
              if (recoveryCalls.incrementAndGet() == 1) {
                firstRecoveryGuard.set(guard);
                firstRecovery.countDown();
              } else {
                secondRecovery.countDown();
              }
            },
            registry,
            ignored -> false);
    opsReference.set(ops);
    try {
      LlamaServerOps.StartResult started =
          ops.startLlamaServer(
              new LlamaServerOps.StartRequest(
                  context, LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS));
      ops.waitForServerHealth(started);
      server.stop(0);
      LlamaServerTestAccess.crashCurrent(ops);
      assertTrue(firstRecovery.await(5, TimeUnit.SECONDS));
      assertNotNull(firstRecoveryGuard.get());
      assertTrue(firstRecoveryGuard.get().getAsBoolean());

      ArrayDeque<Process> queuedAttempts = new ArrayDeque<>(launchAttempts);
      try (MockedConstruction<ProcessBuilder> ignored =
          Mockito.mockConstruction(
              ProcessBuilder.class,
              (builder, construction) -> {
                @SuppressWarnings("unchecked")
                List<String> command = (List<String>) construction.arguments().get(0);
                commands.add(List.copyOf(command));
                Mockito.when(builder.environment()).thenReturn(new HashMap<>());
                Mockito.when(builder.start()).thenAnswer(call -> queuedAttempts.removeFirst());
              })) {
        // Construction mocking is thread-scoped. Invoke the manager-owned recovery body on this
        // thread after proving the scheduler delivered the captured-owner callback above.
        opsReference.get().recoverActiveServer();

        assertTrue(
            secondRecovery.await(15, TimeUnit.SECONDS),
            "a failed lower-context physical retry must advance the same crash episode");
      }
      assertEquals(2, recoveryCalls.get());
      assertEquals(2, crashCount(ops));
      assertTrue(commands.size() >= 2, "recovery must launch and physically retry");
      assertEquals(commands.size(), registry.registered.size());
      assertTrue(registry.snapshot().isEmpty(), "every dead physical child row must be retired");
      assertTrue(
          commands.stream()
                  .map(command -> command.get(command.indexOf("-c") + 1))
                  .distinct()
                  .count()
              >= 2,
          "the physical retry must use a lower context rung");
      assertEquals(
          registry.registered.size(),
          registry.registered.stream().map(ManagedChild::pid).distinct().count());
      for (int i = 0; i < commands.size(); i++) {
        ManagedChild registered = registry.registered.get(i);
        assertEquals(hash, registered.declaredConfigHash());
        assertEquals(
            ManagedLlamaConfigIdentity.realizedArgvHash(commands.get(i)),
            registered.realizedArgvHash());
      }
      LlamaServerOps.StartResult failed = ops.activeStartResult().orElseThrow();
      assertSame(context, failed.context());
      assertEquals(
          LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
          failed.adoptionPolicy());
    } finally {
      ops.shutdown();
      child.destroyForcibly();
      launchAttempts.forEach(Process::destroyForcibly);
    }
  }

  private static int crashCount(LlamaServerOps ops) throws Exception {
    java.lang.reflect.Field field = LlamaServerOps.class.getDeclaredField("crashCount");
    field.setAccessible(true);
    return ((AtomicInteger) field.get(ops)).get();
  }

  @Test
  void recoveryReasoningFallbackUsesCapturedPathsAndThenLowersContextDespiteGlobalC(
      @TempDir Path tmp) throws Exception {
    Path dataB = Files.createDirectories(tmp.resolve("data-b"));
    Path repoB = Files.createDirectories(tmp.resolve("repo-b"));
    Path runtimeBin = Files.createDirectories(repoB.resolve("runtime").resolve("bin"));
    Path executableB = Files.createDirectories(tmp.resolve("bin-b")).resolve("llama-server.exe");
    Files.createFile(executableB);
    Files.writeString(executableB.getParent().resolve("runtime-version.txt"), "llama.cpp b9999\n");
    Path modelB = Files.createFile(tmp.resolve("reasoning-b.gguf"));
    ResolvedConfig resolvedB = resolved(true, true, dataB, repoB);
    ResolvedConfig globalC = resolved(false, false, tmp.resolve("data-c"), tmp.resolve("repo-c"));
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = new ConfigStore(globalC);
    ConfigStore.setGlobal(installed);

    HttpServer server = healthyServer();
    Process incumbent = startSleepingChild();
    List<Process> attempts = new ArrayList<>();
    for (int i = 0; i < 8; i++) attempts.add(startSleepingChild());
    InferenceConfig inferenceB =
        new InferenceConfig(
            executableB, modelB, null, server.getAddress().getPort(), 4096, 0, false);
    LlamaServerConfigContext contextB = new LlamaServerConfigContext(inferenceB, resolvedB);
    String hashB = ManagedLlamaConfigIdentity.declaredHash(inferenceB, resolvedB, 0);
    LlamaServerOps.StartResult incumbentStart =
        new LlamaServerOps.StartResult(
            contextB,
            LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
            LlamaServerOps.StartDisposition.LAUNCHED_MANAGED,
            hashB);
    Path expectedLog = expectedLogPath(dataB);
    KillingRegistry registry = new KillingRegistry(attempts, expectedLog);
    List<List<String>> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
    List<HashMap<String, String>> environments =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    AtomicReference<java.util.function.BooleanSupplier> recoveryGuard = new AtomicReference<>();
    CountDownLatch recoveryReady = new CountDownLatch(1);
    CountDownLatch failedRecovery = new CountDownLatch(1);
    AtomicInteger callbacks = new AtomicInteger();
    LlamaServerOps ops =
        newOps(
            Mode.ONLINE,
            new RecordingObserver(),
            guard -> {
              if (!guard.getAsBoolean()) return;
              if (callbacks.incrementAndGet() == 1) {
                recoveryGuard.set(guard);
                recoveryReady.countDown();
              } else {
                failedRecovery.countDown();
              }
            },
            registry,
            ignored -> false);
    try {
      installActive(ops, incumbentStart, incumbent);
      ops.waitForServerHealth(incumbentStart);
      server.stop(0);
      LlamaServerTestAccess.crashCurrent(ops);
      assertTrue(recoveryReady.await(5, TimeUnit.SECONDS));
      assertTrue(recoveryGuard.get().getAsBoolean());

      ArrayDeque<Process> queued = new ArrayDeque<>(attempts);
      try (MockedConstruction<ProcessBuilder> construction =
          Mockito.mockConstruction(
              ProcessBuilder.class,
              (builder, invocation) -> {
                @SuppressWarnings("unchecked")
                List<String> command = (List<String>) invocation.arguments().get(0);
                commands.add(List.copyOf(command));
                HashMap<String, String> environment = new HashMap<>();
                environments.add(environment);
                Mockito.when(builder.environment()).thenReturn(environment);
                Mockito.when(builder.start()).thenAnswer(call -> queued.removeFirst());
              })) {
        ops.recoverActiveServer();
        assertTrue(failedRecovery.await(15, TimeUnit.SECONDS));

        assertTrue(commands.size() >= 3);
        assertTrue(commands.get(0).contains("--reasoning-budget"));
        assertFalse(commands.get(1).contains("--reasoning-budget"));
        assertEquals(flagValue(commands.get(0), "-c"), flagValue(commands.get(1), "-c"));
        assertTrue(
            commands.stream()
                    .skip(2)
                    .map(command -> flagValue(command, "-c"))
                    .anyMatch(value -> !value.equals(flagValue(commands.get(1), "-c"))));
        String expectedPathPrefix =
            executableB.getParent().toAbsolutePath().normalize()
                + ";"
                + runtimeBin.toAbsolutePath().normalize();
        environments.forEach(
            environment -> assertTrue(environment.get("Path").startsWith(expectedPathPrefix)));
        construction.constructed().forEach(
            builder -> {
              Mockito.verify(builder)
                  .redirectOutput(
                      Mockito.<ProcessBuilder.Redirect>argThat(redirect -> expectedLog.toFile().equals(redirect.file())));
              Mockito.verify(builder)
                  .redirectError(
                      Mockito.<ProcessBuilder.Redirect>argThat(redirect -> expectedLog.toFile().equals(redirect.file())));
            });
      }

      assertEquals("b9999", new ServerPropsOps(new RecordingObserver(), () -> 0)
          .expectedServerBuild(contextB));
      assertEquals(commands.size(), registry.registered.size());
      assertTrue(registry.snapshot().isEmpty());
      for (int i = 0; i < commands.size(); i++) {
        assertEquals(hashB, registry.registered.get(i).declaredConfigHash());
        assertEquals(
            ManagedLlamaConfigIdentity.realizedArgvHash(commands.get(i)),
            registry.registered.get(i).realizedArgvHash());
      }
      assertEquals(2, crashCount(ops));
    } finally {
      ops.shutdown();
      incumbent.destroyForcibly();
      attempts.forEach(Process::destroyForcibly);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static String flagValue(List<String> command, String flag) {
    return command.get(command.indexOf(flag) + 1);
  }

  private static Path expectedLogPath(Path dataDir) {
    String home = System.getenv("JUSTSEARCH_HOME");
    return home == null || home.isBlank()
        ? dataDir.resolve("logs").resolve("llama-server.log")
        : Path.of(home).resolve("logs").resolve("llama-server.log");
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
  void armedLaunchedChildCleanExitQueuesCapturedRecovery(@TempDir Path tmp) throws Exception {
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

  private static Process startSleepingChild() throws Exception {
    return new ProcessBuilder(
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString(),
            "-cp",
            System.getProperty("java.class.path"),
            ManagedLlamaAdoptionTest.SleepingChild.class.getName())
        .start();
  }

  private static ManagedChild managedChild(
      Process child, Path executable, Path model, int port, String hash) {
    return new ManagedChild(
        "candidate",
        ManagedChild.Kind.LLAMA_SERVER,
        child.pid(),
        child.info().startInstant().orElseThrow().toString(),
        ManagedChild.normalizePath(executable),
        "http://127.0.0.1:" + port,
        model.toString(),
        hash,
        "diagnostic-argv");
  }

  private static HttpServer healthyServer() throws Exception {
    return serverWithProps(new CountDownLatch(0), new CountDownLatch(0), new AtomicInteger());
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
        ignored -> {},
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

    private KillingRegistry(ManagedChild initial, List<Process> attempts) {
      children.add(initial);
      this.attempts = List.copyOf(attempts);
      this.firstLaunchOutput = null;
    }

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
