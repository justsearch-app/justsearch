package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.telemetry.LocalTelemetry;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

final class LauncherEnvironmentCloseTest {

  @TempDir Path tempDir;
  private String originalConfig;
  private String originalEgress;
  private String originalDataDir;

  @BeforeEach
  void captureProperties() {
    originalConfig = System.getProperty("justsearch.config");
    originalEgress = System.getProperty("egress.block_all");
    originalDataDir = System.getProperty("justsearch.data.dir");
  }

  @AfterEach
  void restoreProperties() {
    LauncherEnvironment.resetFactories();
    restoreProperty("justsearch.config", originalConfig);
    restoreProperty("egress.block_all", originalEgress);
    restoreProperty("justsearch.data.dir", originalDataDir);
  }

  @Test
  void secondLauncherCannotSweepTheFirstLaunchersLiveOperation() throws Exception {
    System.setProperty("justsearch.data.dir", tempDir.toString());
    var clock = java.time.Clock.systemUTC();
    var key = io.justsearch.app.api.operations.OperationKeys.generate(clock);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var liveStore = new java.util.concurrent.atomic.AtomicReference<io.justsearch.app.api.operations.OperationStore>();
    LauncherEnvironment.installFactories(
        () -> Mockito.mock(io.justsearch.app.config.ConfigManagerBootstrap.class),
        (executors, dataDir, profile) -> new LocalTelemetry(executors, dataDir, 1_000, "launcher-test", profile,
            "metrics.ndjson", java.util.List.of(io.justsearch.telemetry.JvmMetricCatalog.catalogFor("launcher"))),
        (executors, telemetry, config, operations) -> {
          var runner = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(
              operations, clock, java.util.Set.of());
          if (calls.incrementAndGet() == 1) {
            var request = new io.justsearch.app.api.operations.OperationAttemptRunner.Request(key,
                io.justsearch.app.api.operations.OperationDescriptor.invocation(
                    io.justsearch.agent.api.registry.OperationKind.OPERATION, "core.lock-fixture", "{}", false),
                new io.justsearch.core.context.EngineContext(
                    io.justsearch.core.context.EngineContext.ClientKind.INTERNAL, "lock-fixture",
                    java.util.Optional.empty(), java.util.Optional.empty(), "TRUSTED", "SYSTEM_INTERNAL",
                    io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
                    io.justsearch.core.context.EngineContext.Urgency.BACKGROUND), null);
            var accepted = runner.accept(request);
            runner.start(accepted, handle -> new io.justsearch.agent.api.registry.OperationExecution(
                io.justsearch.agent.api.registry.OperationResult.success("running"),
                new java.util.concurrent.CompletableFuture<>()));
            liveStore.set(operations);
          }
          return Mockito.mock(io.justsearch.app.services.HeadAssembly.class);
        });
    try (var first = LauncherEnvironment.create("smoke")) {
      org.junit.jupiter.api.Assertions.assertNotNull(first.HeadAssembly());
      org.junit.jupiter.api.Assertions.assertThrows(
          io.justsearch.app.util.AppInstanceLock.AppInstanceLockException.class, () -> {
            try (var second = LauncherEnvironment.create("smoke")) {
              org.junit.jupiter.api.Assertions.assertNotNull(second);
            }
          });
      assertEquals(1, calls.get(), "the second launcher must not construct a runner");
      assertEquals(io.justsearch.app.api.operations.OperationState.RUNNING,
          liveStore.get().find(key).orElseThrow().state());
      org.junit.jupiter.api.Assertions.assertTrue(io.justsearch.app.util.AppInstanceLock.isHeldByThisJvm(tempDir));
    }
    try (var reacquired = new io.justsearch.app.util.AppInstanceLock(tempDir)) {
      reacquired.acquire();
      org.junit.jupiter.api.Assertions.assertTrue(reacquired.isHeld());
    }
  }

  @Test
  void failingConfigConstructionRestoresPropertiesBeforeAnyOtherOwnerExists() throws Exception {
    System.setProperty("justsearch.config", "previous-config");
    System.setProperty("egress.block_all", "false");
    var failure = new java.io.IOException("config failure");
    LauncherEnvironment.installFactories(() -> { throw failure; },
        (executors, dataDir, profile) -> { throw new AssertionError("telemetry must not start"); },
        (executors, telemetry, config, operations) -> { throw new AssertionError("assembly must not start"); });
    try {
      org.junit.jupiter.api.Assertions.assertSame(failure,
          org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
              () -> LauncherEnvironment.create("smoke")));
      assertEquals("previous-config", System.getProperty("justsearch.config"));
      assertEquals("false", System.getProperty("egress.block_all"));
    } finally {
      LauncherEnvironment.resetFactories();
    }
  }

  @Test
  void failedAssemblyConstructionReleasesItsInstanceLock() throws Exception {
    System.setProperty("justsearch.data.dir", tempDir.toString());
    var failure = new IllegalStateException("assembly failed");
    LauncherEnvironment.installFactories(
        () -> Mockito.mock(io.justsearch.app.config.ConfigManagerBootstrap.class),
        (executors, dataDir, profile) -> new LocalTelemetry(executors, dataDir, 1_000, "launcher-test", profile,
            "metrics.ndjson", java.util.List.of(io.justsearch.telemetry.JvmMetricCatalog.catalogFor("launcher"))),
        (executors, telemetry, config, operations) -> {
          org.junit.jupiter.api.Assertions.assertTrue(io.justsearch.app.util.AppInstanceLock.isHeldByThisJvm(tempDir));
          throw failure;
        });
    org.junit.jupiter.api.Assertions.assertSame(failure,
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> LauncherEnvironment.create("smoke")));
    try (var reacquired = new io.justsearch.app.util.AppInstanceLock(tempDir)) {
      reacquired.acquire();
      org.junit.jupiter.api.Assertions.assertTrue(reacquired.isHeld());
    }
  }

  @Test
  void failedOperationsCloseRetainsInstanceLockUntilRetry() throws Exception {
    var telemetry = Mockito.mock(LocalTelemetry.class);
    var environment = allocateEnvironment(telemetry, null, null, tempDir.resolve("store-close-retry"));
    var operations = Mockito.mock(io.justsearch.app.api.operations.OperationStore.class);
    var lock = Mockito.mock(io.justsearch.app.util.AppInstanceLock.class);
    setField(environment, "operations", operations);
    setField(environment, "instanceLock", lock);
    Mockito.doThrow(new java.io.IOException("close refused")).doNothing().when(operations).close();
    org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class, environment::close);
    Mockito.verifyNoInteractions(lock, telemetry);
    environment.close();
    var order = Mockito.inOrder(operations, lock);
    order.verify(operations, Mockito.times(2)).close();
    order.verify(lock).close();
  }

  @Test
  void failedHeadCleanupAfterDrainStillClosesDependenciesAndPreservesFailures() throws Exception {
    var telemetry = Mockito.mock(LocalTelemetry.class);
    var environment = allocateEnvironment(telemetry, "previous-config", "false", tempDir.resolve("cleanup"));
    var head = Mockito.mock(io.justsearch.app.services.HeadAssembly.class);
    var operations = Mockito.mock(io.justsearch.app.api.operations.OperationStore.class);
    var executors = Mockito.mock(io.justsearch.core.execution.EngineExecutorRegistry.class);
    var lock = Mockito.mock(io.justsearch.app.util.AppInstanceLock.class);
    setField(environment, "HeadAssembly", head);
    setField(environment, "operations", operations);
    setField(environment, "executors", executors);
    setField(environment, "instanceLock", lock);
    Mockito.when(head.isDependencyTeardownStarted()).thenReturn(true);
    var headFailure = new IllegalStateException("head cleanup failed after drain");
    var telemetryFailure = new IllegalStateException("telemetry cleanup failed");
    Mockito.doThrow(headFailure).when(head).close();
    Mockito.doThrow(telemetryFailure).when(telemetry).close();
    var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, environment::close);
    Mockito.verify(operations).close();
    Mockito.verify(telemetry).close();
    Mockito.verify(executors).close();
    Mockito.verify(lock).close();
    org.junit.jupiter.api.Assertions.assertSame(headFailure, failure);
    org.junit.jupiter.api.Assertions.assertArrayEquals(new Throwable[] {telemetryFailure}, failure.getSuppressed());
    assertEquals("previous-config", System.getProperty("justsearch.config"));
  }

  @Test
  void failedHeadDrainRetainsDependenciesAndASecondCloseRetries() throws Exception {
    System.setProperty("justsearch.config", "active-config");
    System.setProperty("egress.block_all", "true");
    var telemetry = Mockito.mock(LocalTelemetry.class);
    var environment = allocateEnvironment(telemetry, "previous-config", "false", tempDir.resolve("retry"));
    var head = Mockito.mock(io.justsearch.app.services.HeadAssembly.class);
    var operations = Mockito.mock(io.justsearch.app.api.operations.OperationStore.class);
    var executors = Mockito.mock(io.justsearch.core.execution.EngineExecutorRegistry.class);
    setField(environment, "HeadAssembly", head);
    setField(environment, "operations", operations);
    setField(environment, "executors", executors);
    var failure = new IllegalStateException("procedure still running");
    Mockito.doThrow(failure).doNothing().when(head).close();
    org.junit.jupiter.api.Assertions.assertSame(failure,
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, environment::close));
    Mockito.verifyNoInteractions(operations, telemetry, executors);
    assertEquals("active-config", System.getProperty("justsearch.config"));
    assertEquals("true", System.getProperty("egress.block_all"));
    environment.close();
    var order = Mockito.inOrder(head, operations, telemetry, executors);
    order.verify(head, Mockito.times(2)).close();
    order.verify(operations).close();
    order.verify(telemetry).close();
    order.verify(executors).close();
    assertEquals("previous-config", System.getProperty("justsearch.config"));
    assertEquals("false", System.getProperty("egress.block_all"));
  }

  @Test
  void closeClearsSystemPropertiesWhenUnsetPreviously() throws Exception {
    System.setProperty("justsearch.config", "temp-config");
    System.setProperty("egress.block_all", "true");
    LocalTelemetry telemetry = new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tempDir, 1_000, "launcher-test", "close-null");
    LauncherEnvironment environment =
        allocateEnvironment(telemetry, null, null, tempDir.resolve("profile-null"));

    environment.close();

    assertEquals(null, System.getProperty("justsearch.config"));
    assertEquals(null, System.getProperty("egress.block_all"));
  }

  @Test
  void failureAfterConfigPublicationRestoresPriorStoreIncludingUninitializedState() throws Exception {
    var original = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    var originalDataDir = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.data.dir", tempDir.toString());
    try {
      for (var previous : new io.justsearch.configuration.resolved.ConfigStore[] {
          null, Mockito.mock(io.justsearch.configuration.resolved.ConfigStore.class)}) {
        var current = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
        if (current != null) io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(current, previous);
        else if (previous != null) io.justsearch.configuration.resolved.ConfigStore.setGlobal(previous);
        var failure = new IllegalStateException("telemetry construction failed after publication");
        LauncherEnvironment.installFactories(
            () -> Mockito.mock(io.justsearch.app.config.ConfigManagerBootstrap.class),
            (executors, dataDir, profile) -> {
              org.junit.jupiter.api.Assertions.assertNotNull(
                  io.justsearch.configuration.resolved.ConfigStore.globalOrNull());
              org.junit.jupiter.api.Assertions.assertNotSame(previous,
                  io.justsearch.configuration.resolved.ConfigStore.globalOrNull());
              throw failure;
            },
            (executors, telemetry, config, operations) -> { throw new AssertionError("assembly must not start"); });
        org.junit.jupiter.api.Assertions.assertSame(failure,
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> LauncherEnvironment.create("smoke")));
        org.junit.jupiter.api.Assertions.assertSame(previous,
            io.justsearch.configuration.resolved.ConfigStore.globalOrNull());
      }
    } finally {
      restoreProperty("justsearch.data.dir", originalDataDir);
      var current = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
      if (current != null) io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(current, original);
      else if (original != null) io.justsearch.configuration.resolved.ConfigStore.setGlobal(original);
    }
  }

  @Test
  void closeRestoresPreviousSystemProperties() throws Exception {
    System.setProperty("justsearch.config", "temp-config");
    System.setProperty("egress.block_all", "true");
    LocalTelemetry telemetry = new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tempDir, 1_000, "launcher-test", "close-restore");
    LauncherEnvironment environment =
        allocateEnvironment(telemetry, "previous-config", "false", tempDir.resolve("profile-restore"));

    environment.close();

    assertEquals("previous-config", System.getProperty("justsearch.config"));
    assertEquals("false", System.getProperty("egress.block_all"));
  }

  @Test
  void smokeDefaultUsesResolvedPathWithoutLiteralInterpolationAndRestoresItOnFailure() throws Exception {
    System.clearProperty("justsearch.data.dir");
    Path expected = io.justsearch.configuration.EnvRegistry.DATA_DIR.get()
        .map(io.justsearch.configuration.PlatformPaths::expandUserHomePlaceholders)
        .map(Path::of).orElseGet(() -> Path.of(System.getProperty("user.home"), ".justsearch-smoke"));
    var failure = new IllegalStateException("stop before telemetry creates files");
    LauncherEnvironment.installFactories(
        () -> Mockito.mock(io.justsearch.app.config.ConfigManagerBootstrap.class),
        (executors, dataDir, profile) -> {
          assertEquals(expected, dataDir);
          assertEquals(expected, io.justsearch.configuration.resolved.ConfigStore.global().get().paths().dataDir());
          throw failure;
        }, null);
    org.junit.jupiter.api.Assertions.assertSame(failure,
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> LauncherEnvironment.create("smoke")));
    assertEquals(null, System.getProperty("justsearch.data.dir"));
  }

  @Test
  void accessorsReturnAssignedValues() throws Exception {
    LocalTelemetry telemetry = new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tempDir, 1_000, "launcher-test", "accessors");
    Path profile = tempDir.resolve("profile-accessors");
    LauncherEnvironment environment =
        allocateEnvironment(telemetry, null, null, profile);
    setField(environment, "HeadAssembly", null);
    setField(environment, "operations", Mockito.mock(io.justsearch.app.api.operations.OperationStore.class));
    setField(environment, "configManager", null);

    assertEquals(profile, environment.profilePath());
    assertEquals(null, environment.configManager());
    assertEquals(telemetry, environment.telemetry());
    assertEquals(null, environment.HeadAssembly());

    telemetry.close();
  }

  private LauncherEnvironment allocateEnvironment(
      LocalTelemetry telemetry, String previousConfig, String previousEgress, Path profile)
      throws Exception {
    Files.createDirectories(profile);
    LauncherEnvironment environment =
        Mockito.mock(LauncherEnvironment.class, Mockito.CALLS_REAL_METHODS);
    setField(environment, "profilePath", profile);
    setField(environment, "previousConfigProperty", previousConfig);
    setField(environment, "previousEgressProperty", previousEgress);
    setField(environment, "configManager", null);
    setField(environment, "telemetry", telemetry);
    setField(environment, "executors", new io.justsearch.core.execution.TestEngineExecutors());
    setField(environment, "HeadAssembly", null);
    setField(environment, "operations", Mockito.mock(io.justsearch.app.api.operations.OperationStore.class));
    return environment;
  }

  private void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
    for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        // field may be declared on a superclass; keep walking up until we find it or run out
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
