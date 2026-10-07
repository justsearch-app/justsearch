/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.services.chat.ChatServerControllerImpl;
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

  /** Optional composition capability carried by the process-owned executor dependency. */
  public interface GenerationLifetimeSource {
    /** Begin at producer entry; the returned single-use release belongs to actual producer exit. */
    java.util.function.Function<io.justsearch.app.api.EngineWorkHandle, Runnable> generationLifetime();

    /**
     * Spec E step E2: receives the chat server's generation signal once the manager exists, so
     * chat generation can pace indexing through Switch A. Fixtures without a gate ignore it.
     */
    default void bindGenerationActivity(io.justsearch.app.api.chat.GenerationActivity activity) {}

    /**
     * Spec E step E3: receives the chat controller's pressure input once the controller exists, so
     * the Engine's GPU residency sampler can report external memory pressure to it (section 3.4).
     * Fixtures without a sampler ignore it.
     */
    default void bindPressureReceiver(
        java.util.function.Consumer<io.justsearch.app.api.chat.PressureSignal> receiver) {}

    /**
     * Spec E step E7b: receives the chat controller's profile input once the controller exists, so
     * the device-memory plan's profile reaches chat co-residence. Fixtures without a plan ignore it.
     */
    default void bindProfileReceiver(
        java.util.function.Consumer<io.justsearch.app.api.status.operating.Profile> receiver) {}
  }

  /** Fixtures without an index half have no indexing pacing consumer. */
  public static java.util.function.Function<io.justsearch.app.api.EngineWorkHandle, Runnable>
      generationLifetime(io.justsearch.core.execution.EngineExecutorRegistry executors) {
    return executors instanceof GenerationLifetimeSource source
        ? source.generationLifetime() : ignored -> () -> {};
  }

  /**
   * Creates the InferenceLifecycleManager from captured configuration. Tempdoc 412 Phase 4
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
    java.util.Objects.requireNonNull(childRegistry, "childRegistry");
    return create(aiEnabled, resolvedConfig, userDir, telemetry, log,
        (config, events) -> new InferenceLifecycleManager(executors, config, events,
            childRegistry, resolvedConfig, generationLifetime(executors), pinnedCudaUuid()),
        executors);
  }

  /** Spec E step E6: llama-server sees only the pinned GPU, the one the encoders use. */
  private static java.util.function.Supplier<java.util.Optional<String>> pinnedCudaUuid() {
    return () -> io.justsearch.app.services.gpu.DeviceFactsSource.process().facts().pinnedUuid();
  }

  /**
   * The Engine's composition: every llama-server child, the self-test candidate included, is
   * started and stopped through {@code childControl} (Spec B steps 5 and 6), which the caller owns
   * and closes after the manager.
   */
  public static InferenceLifecycleManager createInferenceManager(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      boolean aiEnabled,
      ResolvedConfig resolvedConfig,
      String userDir,
      Telemetry telemetry,
      Logger log,
      io.justsearch.app.api.runtime.child.ChildControl childControl) {
    java.util.Objects.requireNonNull(childControl, "childControl");
    return create(aiEnabled, resolvedConfig, userDir, telemetry, log,
        (config, events) -> new InferenceLifecycleManager(executors, config, events,
            childControl, resolvedConfig, generationLifetime(executors), pinnedCudaUuid()),
        executors);
  }

  private static InferenceLifecycleManager create(
      boolean aiEnabled,
      ResolvedConfig resolvedConfig,
      String userDir,
      Telemetry telemetry,
      Logger log,
      java.util.function.BiFunction<InferenceConfig, InferenceTelemetryEvents,
          InferenceLifecycleManager> managerOf,
      io.justsearch.core.execution.EngineExecutorRegistry executors) {
    if (!aiEnabled) {
      log.info("AI features disabled via configuration");
      return null;
    }

    try {
      Path baseDir = resolveBaseDir(resolvedConfig, userDir);
      InferenceConfig config = InferenceConfig.fromResolvedConfig(resolvedConfig, baseDir);

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
      var manager = managerOf.apply(config, events);
      // Spec A step 2: the chat controller owns every start, stop and relaunch from here on. The
      // manager's close() shuts it down (terminal first), so it has no separate owner.
      try {
        var controller = new ChatServerControllerImpl(
            executors, manager.chatActuator(), manager.selfTestLauncher());
        manager.installActuationRouter(controller);
        // Spec E step E2: chat generation reaches indexing pacing through the Engine's bulk gate.
        if (executors instanceof GenerationLifetimeSource source) {
          source.bindGenerationActivity(manager.generationActivity());
          // Spec E step E3: the Engine's residency sampler reports GPU pressure to the controller.
          source.bindPressureReceiver(controller::reportPressure);
          // Spec E step E7b: the device-memory plan's profile reaches the controller.
          source.bindProfileReceiver(controller::setProfile);
        }
      } catch (RuntimeException | Error failure) {
        try {
          manager.close();
        } catch (RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
      return manager;

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
