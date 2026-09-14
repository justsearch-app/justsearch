/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.services.inference.InferenceMetricCatalog;
import io.justsearch.app.services.inference.InferenceTelemetryAdapter;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedPathResolver;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;

public final class BootstrapInferenceFactory {
  private BootstrapInferenceFactory() {}

  // Cached config from willInferenceManagerBeCreated(), reused by createInferenceManager()
  // to avoid duplicate filesystem probes during startup. Both are called sequentially
  // on the same thread during HeadAssembly construction.
  private static volatile InferenceConfig cachedInferenceConfig;

  /**
   * Checks if InferenceLifecycleManager will be created based on same conditions
   * as createInferenceManager(). Used to prevent double model loading.
   */
  public static boolean willInferenceManagerBeCreated(
      boolean aiEnabled, ResolvedConfig resolvedConfig, String userDir) {
    if (!aiEnabled) {
      return false;
    }

    try {
      Path baseDir = resolveBaseDir(resolvedConfig, userDir);
      InferenceConfig config = InferenceConfig.fromEnvironment(baseDir);
      cachedInferenceConfig = config;

      // Mirror createInferenceManager(): we create the manager even if files are missing, so the UI
      // can guide BYO setup without requiring a restart.
      return config != null;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Creates the InferenceLifecycleManager from environment configuration. Tempdoc 412 Phase 4
   * overload: now takes a {@link Telemetry} reference so the catalog adapter can be wired. The
   * adapter is constructed against {@code lt.registry()} when {@code telemetry} is a
   * {@link LocalTelemetry}; otherwise the no-op events sink is used.
   *
   * @return constructed manager, or {@code null} when AI features are disabled or configuration
   *     fails
   */
  public static InferenceLifecycleManager createInferenceManager(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      boolean aiEnabled,
      ResolvedConfig resolvedConfig,
      String userDir,
      Telemetry telemetry,
      Logger log) {
    return createInferenceManager(executors, aiEnabled, resolvedConfig, userDir, telemetry, log,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  public static InferenceLifecycleManager createInferenceManager(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      boolean aiEnabled,
      ResolvedConfig resolvedConfig,
      String userDir,
      Telemetry telemetry,
      Logger log,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    if (!aiEnabled) {
      log.info("AI features disabled via configuration");
      return null;
    }

    try {
      // Reuse config from willInferenceManagerBeCreated() if available
      InferenceConfig config = cachedInferenceConfig;
      cachedInferenceConfig = null; // Clear after use
      if (config == null) {
        Path baseDir = resolveBaseDir(resolvedConfig, userDir);
        config = InferenceConfig.fromEnvironment(baseDir);
      }

      // Do NOT require server/model to exist at startup.
      // BYO AI contract: users may add the files after installation; runtime control must still exist
      // so the UI can apply settings and allow Online mode without a full restart.
      if (!Files.exists(config.serverExecutable())) {
        log.info(
            "llama-server executable not found at startup (expected for BYO installs): {}",
            config.serverExecutable());
      }
      if (!Files.exists(config.modelPath())) {
        log.info("LLM model not found at startup (expected for BYO installs): {}", config.modelPath());
      }

      log.info(
          "Creating InferenceLifecycleManager with port {} (gpuLayers={}, ctx={})",
          config.serverPort(),
          config.gpuLayers(),
          config.contextSize());

      // Tempdoc 412 Phase 4: build the catalog adapter from telemetry when available.
      InferenceTelemetryEvents events = buildEvents(telemetry);
      // Note: the persistent InferenceTransitionLog is installed downstream in the
      // composition root (AppFacadeBootstrap) where the head's dataDir is known.
      return new InferenceLifecycleManager(executors, config, events, childRegistry);

    } catch (Exception e) {
      if (e instanceof io.justsearch.core.execution.EngineExecutorRejectedException refusal) throw refusal;
      log.warn("Failed to create InferenceLifecycleManager; AI features unavailable", e);
      return null;
    }
  }

  /**
   * Back-compat overload (no telemetry). Used by tests and any caller that hasn't been migrated
   * yet. Falls through to the new overload with a noop events sink.
   */
  public static InferenceLifecycleManager createInferenceManager(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      boolean aiEnabled, ResolvedConfig resolvedConfig, String userDir, Logger log) {
    return createInferenceManager(executors, aiEnabled, resolvedConfig, userDir, null, log);
  }

  /**
   * Constructs the {@link InferenceTelemetryAdapter} when a real {@link LocalTelemetry} is
   * provided; returns the noop sink otherwise. Mirrors the pattern from
   * {@code RagMetricCatalog} construction in {@code HeadAssembly}.
   */
  private static InferenceTelemetryEvents buildEvents(Telemetry telemetry) {
    if (telemetry instanceof LocalTelemetry lt) {
      return new InferenceTelemetryAdapter(new InferenceMetricCatalog(lt.registry()));
    }
    return InferenceTelemetryEvents.noop();
  }

  /**
   * Resolves the base directory for InferenceConfig.
   * Uses JUSTSEARCH_HOME environment variable, or falls back to working directory.
   */
  public static Path resolveBaseDir(ResolvedConfig resolvedConfig, String userDir) {
    return ResolvedPathResolver.resolveBaseDir(resolvedConfig, userDir);
  }

  public static Path getJustSearchHome(ResolvedConfig resolvedConfig, String userDir) {
    return ResolvedPathResolver.resolveBaseDir(resolvedConfig, userDir);
  }
}
