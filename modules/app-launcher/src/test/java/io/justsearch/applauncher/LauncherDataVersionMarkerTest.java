/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.EngineProcessResources;
import io.justsearch.app.util.AppInstanceLock;
import io.justsearch.app.util.RepoPaths;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.telemetry.LocalTelemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

/** Real CLI environment and Engine SPI, without starting application services. */
class LauncherDataVersionMarkerTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path dir;
  private final String previousDataDir = System.getProperty(EnvRegistry.DATA_DIR.sysProp());
  private final String previousAppVersion = System.getProperty(EnvRegistry.APP_VERSION.sysProp());
  private final AtomicReference<Optional<String>> notice = new AtomicReference<>(Optional.empty());

  @AfterEach
  void restore() {
    LauncherEnvironment.resetFactories();
    Launcher.resetFactories();
    SmokeDriver.resetFactories();
    restoreProperty(EnvRegistry.DATA_DIR.sysProp(), previousDataDir);
    restoreProperty(EnvRegistry.APP_VERSION.sysProp(), previousAppVersion);
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) System.clearProperty(key); else System.setProperty(key, value);
  }

  private void configure(boolean failAssembly) throws Exception {
    System.setProperty(EnvRegistry.DATA_DIR.sysProp(), dir.toString());
    System.setProperty(EnvRegistry.APP_VERSION.sysProp(), "0.3.0");
    LauncherEnvironment.installFactories(
        () -> Mockito.mock(io.justsearch.app.config.ConfigManagerBootstrap.class),
        () -> {
          // Load the production provider through the same permitted seam as the launcher.
          var resources = Mockito.spy(LauncherEnvironment.loadProcessResources(
              ServiceLoader.load(EngineProcessResources.class).stream().toList()));
          Mockito.doAnswer(invocation -> {
            assertTrue(AppInstanceLock.isHeldByThisJvm(dir));
            assertFalse(Files.exists(dir.resolve("operations.db")), "marker must precede SQLite open");
            @SuppressWarnings("unchecked")
            var result = (Optional<String>) invocation.callRealMethod();
            notice.set(result);
            return result;
          }).when(resources).recordDataVersions(dir);
          return resources;
        },
        (executors, dataDir, profile) -> new LocalTelemetry(executors, dataDir, 1_000,
            "launcher-marker-test", profile, "metrics.ndjson",
            List.of(io.justsearch.telemetry.JvmMetricCatalog.catalogFor("launcher"))),
        (resources, telemetry, config, operations) -> {
          assertTrue(Files.isRegularFile(dir.resolve("data-version.json")), "CLI must raise the marker");
          assertTrue(Files.isRegularFile(dir.resolve("operations.db")));
          if (failAssembly) throw new IllegalStateException("test assembly failure");
          return null;
        });
  }

  @Test
  void everyStoreOpeningCliCommandReachesTheRealPreStoreHook() throws Exception {
    Path root = dir;
    for (String command : List.of("seed", "reindex", "verify", "snapshot", "smoke")) {
      dir = Files.createDirectory(root.resolve(command));
      configure(false);
      var runner = Mockito.mock(Launcher.CommandRunner.class);
      var success = LauncherCommands.CommandResult.success(List.of("test command"));
      Mockito.when(runner.seed(Mockito.any(Path.class))).thenReturn(success);
      Mockito.when(runner.reindex()).thenReturn(success);
      Mockito.when(runner.verify()).thenReturn(success);
      Mockito.when(runner.snapshot()).thenReturn(success);
      Launcher.installFactories(null, environment -> runner, null);
      SmokeDriver.installCommandRunnerFactory(environment -> runner);
      // With no Head services assembled, smoke may report search unavailable; it must still
      // open the real CLI environment and mark data before its SQLite migration.
      assertDoesNotThrow(() -> new Launcher().execute(new String[] {command}));
      assertTrue(Files.isRegularFile(dir.resolve("data-version.json")), command);
      assertTrue(Files.isRegularFile(dir.resolve("operations.db")), command);
    }
  }

  @Test
  void cliBootRecordsAllBuildVersionsBeforeOpeningOperations() throws Exception {
    configure(false);
    try (var environment = LauncherEnvironment.create("smoke")) {
      assertNotNull(environment);
      var marker = JSON.readTree(dir.resolve("data-version.json").toFile());
      assertEquals("0.3.0", marker.path("appVersion").asText());
      Map<String, Integer> expected = new TreeMap<>();
      for (var row : JSON.readTree(RepoPaths.findRepoRoot()
          .resolve("governance/store-recoverability.v1.json").toFile()).path("durableStores")) {
        if (row.has("versionSource")) expected.put(row.path("id").asText(), row.path("currentVersion").asInt());
      }
      Map<String, Integer> actual = new TreeMap<>();
      marker.path("storeVersions").properties().forEach(entry -> actual.put(entry.getKey(), entry.getValue().asInt()));
      assertEquals(expected, actual);
      assertTrue(notice.get().isEmpty());
    }
  }

  @Test
  void newerCliMarkerWarnsAndBootContinuesWithoutLoweringIt() throws Exception {
    Files.writeString(dir.resolve("data-version.json"),
        "{\"appVersion\":\"9.0.0\",\"storeVersions\":{\"operations-db\":999}}");
    configure(false);
    try (var environment = LauncherEnvironment.create("smoke")) {
      assertNotNull(environment);
      assertTrue(notice.get().orElseThrow().contains("app 9.0.0"));
      assertTrue(notice.get().orElseThrow().contains("operations-db v999"));
      var marker = JSON.readTree(dir.resolve("data-version.json").toFile());
      assertEquals("9.0.0", marker.path("appVersion").asText());
      assertEquals(999, marker.path("storeVersions").path("operations-db").asInt());
    }
  }

  @Test
  void failedCliAssemblyStillLeavesTheMarkerForAnyPartialMigration() throws Exception {
    configure(true);
    var failure = assertThrows(IllegalStateException.class, () -> LauncherEnvironment.create("smoke"));
    assertEquals("test assembly failure", failure.getMessage());
    assertEquals("0.3.0", JSON.readTree(dir.resolve("data-version.json").toFile()).path("appVersion").asText());
    try (var lock = new AppInstanceLock(dir)) {
      lock.acquire();
      assertTrue(lock.isHeld());
    }
  }
}
