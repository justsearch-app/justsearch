/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.applauncher;

import io.justsearch.configuration.Faults;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.app.util.RepoPaths;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared launcher environment wiring that aligns with the configured profile and telemetry setup.
 *
 * <p>This helper centralises the profile/property management so both the smoke driver and command
 * handlers execute against the same wiring that the application will use in production.
 */
final class LauncherEnvironment implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(LauncherEnvironment.class);

  private final Path profilePath;
  private final String previousConfigProperty;
  private final String previousEgressProperty;
  private final String previousDataDirProperty;
  private final ConfigStore previousConfigStore;
  private final ConfigStore installedConfigStore;
  private final ConfigManagerBootstrap configManager;
  private final LocalTelemetry telemetry;
  private final io.justsearch.core.execution.EngineExecutorRegistry executors;
  private final HeadAssembly HeadAssembly;
  private static final ConfigManagerFactory DEFAULT_CONFIG_MANAGER_FACTORY =
      ConfigManagerBootstrap::new;
  // Tempdoc 417 Phase 2 + F1 follow-up: register catalogs for every metric the Launcher process
  // emits via the catalog path. Head catalogs (HeadApi/HeadGpu/HeadHttpInflight) live in
  // `app-services/observability` (relocated from `ui` in F1 to satisfy the
  // LayeringEnforcementTest rule) so app-launcher can import their DEFINITIONS without
  // depending on `ui`.
  private static final TelemetryFactory DEFAULT_TELEMETRY_FACTORY =
      (executors, dataDir, profile) ->
          new LocalTelemetry(
              executors, dataDir,
              5_000,
              "justsearch-launcher",
              profile,
              "metrics.ndjson",
              java.util.List.of(
                  // Tempdoc 626 §Axis-A — Head-side file watcher removed; `index.watcher.*` is a
                  // Worker-only metric now (WorkerWatcherMetricCatalog).
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.worker.IpcMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.worker.IpcMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.worker.RagMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.worker.RagMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.vdu.VduMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.vdu.VduMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.agent.AgentMetricCatalog.NAMESPACE,
                      io.justsearch.agent.AgentMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.agent.GenAiMetricCatalog.NAMESPACE,
                      io.justsearch.agent.GenAiMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.observability.HeadApiMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.observability.HeadApiMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.observability.HeadHttpInflightMetricCatalog
                          .NAMESPACE,
                      io.justsearch.app.services.observability.HeadHttpInflightMetricCatalog
                          .DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.observability.HeadGpuMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.observability.HeadGpuMetricCatalog
                          .DEFINITIONS),
                  // Tempdoc 412 Phase 4: register the inference metric catalog (matches HeadlessApp).
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.inference.InferenceMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.inference.InferenceMetricCatalog.DEFINITIONS),
                  // Phase 3d: register the Launcher JVM gauges (separate process from Head /
                  // Worker — its memory/threads do not flow to either of those metric files).
                  io.justsearch.telemetry.JvmMetricCatalog.catalogFor("launcher")));
  private static final AppFacadeFactory DEFAULT_APP_FACADE_FACTORY =
      (executors, telemetry, configManager) -> {
        var admission = loadAdmission(java.util.ServiceLoader.load(
            io.justsearch.app.api.EngineAdmissionService.class).stream().toList());
        if (!(admission instanceof io.justsearch.app.api.OperationLeaseService leases)) {
          throw new IllegalStateException("Engine admission provider must also own operation leases");
        }
        return new HeadAssembly(
            executors, telemetry, configManager, null,
            new io.justsearch.app.services.settings.UiSettingsStore(
                io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY),
            null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), leases, admission);
      };
  private static volatile ConfigManagerFactory configManagerFactory = DEFAULT_CONFIG_MANAGER_FACTORY;
  private static volatile TelemetryFactory telemetryFactory = DEFAULT_TELEMETRY_FACTORY;
  private static volatile AppFacadeFactory appFacadeFactory = DEFAULT_APP_FACADE_FACTORY;

  static LauncherEnvironment create(String profile) throws Exception {
    return new LauncherEnvironment(profile);
  }

  static void installFactories(
      ConfigManagerFactory configFactory,
      TelemetryFactory telemetryFactoryOverride,
      AppFacadeFactory appFacadeFactoryOverride) {
    if (configFactory != null) {
      configManagerFactory = configFactory;
    }
    if (telemetryFactoryOverride != null) {
      telemetryFactory = telemetryFactoryOverride;
    }
    if (appFacadeFactoryOverride != null) {
      appFacadeFactory = appFacadeFactoryOverride;
    }
  }

  static void resetFactories() {
    configManagerFactory = DEFAULT_CONFIG_MANAGER_FACTORY;
    telemetryFactory = DEFAULT_TELEMETRY_FACTORY;
    appFacadeFactory = DEFAULT_APP_FACADE_FACTORY;
  }

  private LauncherEnvironment(String profile) throws Exception {
    this.profilePath = resolveProfilePath(profile);
    this.previousConfigProperty = System.getProperty(EnvRegistry.CONFIG_PATH.sysProp());
    this.previousEgressProperty = System.getProperty("egress.block_all");
    this.previousDataDirProperty = System.getProperty(EnvRegistry.DATA_DIR.sysProp());
    ConfigStore previousStore = ConfigStore.globalOrNull();
    ConfigStore installedStore = null;
    io.justsearch.core.execution.EngineExecutorRegistry createdExecutors = null;
    LocalTelemetry createdTelemetry = null;
    try {
      System.setProperty("justsearch.config", profilePath.toString());
      System.setProperty("egress.block_all", "true");
      ConfigManagerBootstrap createdConfig = configManagerFactory.create();
      ResolvedConfigBuilder rcBuilder = ResolvedConfig.builder();
      rcBuilder.contributeBaseSources();
      if ("smoke".equals(profile)) {
        rcBuilder.putDefault(EnvRegistry.DATA_DIR.sysProp(),
            Path.of(System.getProperty("user.home"), ".justsearch-smoke").toString());
      }
      installedStore = new ConfigStore(rcBuilder.build());
      ConfigStore.setGlobal(installedStore);
      Path resolvedDataDir = installedStore.get().paths().dataDir();
      if (resolvedDataDir != null) {
        // Keep legacy PlatformPaths consumers aligned with this scoped resolved configuration.
        System.setProperty(EnvRegistry.DATA_DIR.sysProp(), resolvedDataDir.toString());
      }
      createdExecutors = loadExecutors(java.util.ServiceLoader.load(
          io.justsearch.core.execution.EngineExecutorRegistry.class).stream().toList());
      createdTelemetry = telemetryFactory.create(
          createdExecutors, PlatformPaths.resolveDataDir(), profile);
      io.justsearch.telemetry.JvmRuntimeGauges.register(createdTelemetry, "launcher");
      this.HeadAssembly = appFacadeFactory.create(createdExecutors, createdTelemetry, createdConfig);
      this.configManager = createdConfig;
      this.executors = createdExecutors;
      this.telemetry = createdTelemetry;
      this.previousConfigStore = previousStore;
      this.installedConfigStore = installedStore;
    } catch (Exception | Error failure) {
      if (createdTelemetry != null) {
        try { createdTelemetry.close(); } catch (RuntimeException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
      if (createdExecutors != null) {
        try { createdExecutors.close(); } catch (RuntimeException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
      if (installedStore != null) ConfigStore.restoreGlobal(installedStore, previousStore);
      restoreProperties();
      throw failure;
    }
  }

  @SuppressWarnings("unused") // Called from LauncherEnvironmentCloseTest
  ConfigManagerBootstrap configManager() {
    return configManager;
  }

  @SuppressWarnings("unused") // Called from LauncherEnvironmentCloseTest, SmokeDriverTest
  Telemetry telemetry() {
    return telemetry;
  }

  @SuppressWarnings("unused") // Called from LauncherEnvironmentCloseTest, SmokeDriverTest
  HeadAssembly HeadAssembly() {
    return HeadAssembly;
  }

  Path profilePath() {
    return profilePath;
  }

  @FunctionalInterface
  interface ConfigManagerFactory {
    ConfigManagerBootstrap create() throws Exception;
  }

  @FunctionalInterface
  interface TelemetryFactory {
    LocalTelemetry create(io.justsearch.core.execution.EngineExecutorRegistry executors,
        Path dataDir, String profile) throws Exception;
  }

  @FunctionalInterface
  interface AppFacadeFactory {
    HeadAssembly create(io.justsearch.core.execution.EngineExecutorRegistry executors,
        LocalTelemetry telemetry, ConfigManagerBootstrap configManager)
        throws Exception;
  }

  private Path resolveProfilePath(String profile) throws IOException {
    Path root = RepoPaths.findRepoRoot();
    Path path = root.resolve("config/profiles").resolve(profile + ".yaml");
    if (!Files.exists(path)) {
      throw new IOException("Profile not found: " + path);
    }
    return path.toAbsolutePath().normalize();
  }

  @Override
  public void close() {
    Faults.debugAndContinue(LOG, "shutdown", () -> HeadAssembly.close());
    try {
      telemetry.close();
    } finally {
      try { executors.close(); } finally { restoreProperties(); }
    }
  }

  static io.justsearch.core.execution.EngineExecutorRegistry loadExecutors(
      java.util.List<java.util.ServiceLoader.Provider<
          io.justsearch.core.execution.EngineExecutorRegistry>> providers) {
    if (providers.size() != 1) {
      throw new IllegalStateException("Expected exactly one Engine executor registry provider, found "
          + providers.size());
    }
    return providers.getFirst().get();
  }

  static io.justsearch.app.api.EngineAdmissionService loadAdmission(
      java.util.List<java.util.ServiceLoader.Provider<io.justsearch.app.api.EngineAdmissionService>> providers) {
    if (providers.size() != 1) {
      throw new IllegalStateException("Expected exactly one Engine admission provider, found " + providers.size());
    }
    return providers.getFirst().get();
  }

  private void restoreProperties() {
    if (installedConfigStore != null) ConfigStore.restoreGlobal(installedConfigStore, previousConfigStore);
    if (previousDataDirProperty == null) System.clearProperty(EnvRegistry.DATA_DIR.sysProp());
    else System.setProperty(EnvRegistry.DATA_DIR.sysProp(), previousDataDirProperty);
    if (previousConfigProperty == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", previousConfigProperty);
    }
    if (previousEgressProperty == null) {
      System.clearProperty("egress.block_all");
    } else {
      System.setProperty("egress.block_all", previousEgressProperty);
    }
  }
}
