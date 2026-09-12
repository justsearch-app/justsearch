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
