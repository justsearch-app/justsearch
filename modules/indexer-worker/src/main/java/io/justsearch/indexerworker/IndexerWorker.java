/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker;

import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.contracts.BootContractRunner;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the indexer worker process (Knowledge Server).
 *
 * <p>This process runs in isolation from the main UI process and owns:
 * <ul>
 *   <li>Lucene index (exclusive R/W access)</li>
 *   <li>SQLite job queue (exclusive R/W access)</li>
 *   <li>the search and ingestion services (in-process since lane F stage A item A9)</li>
 * </ul>
 *
 * <p>Item A9 deleted the gRPC server, so this entry point no longer binds an ephemeral port nor
 * publishes one to the memory-mapped file for discovery. It survives only as the standalone
 * escape hatch until item A13 collapses the two spawn paths; the live path is the Engine
 * composition root ({@code io.justsearch.app.engine.EngineRoot}), which builds this same
 * {@code KnowledgeServer} inside the Head JVM.
 */
public final class IndexerWorker {
  private static final Logger log = LoggerFactory.getLogger(IndexerWorker.class);

  @FunctionalInterface
  interface WorkerConfigSupplier {
    WorkerConfig load() throws Exception;
  }

  @FunctionalInterface
  interface ServerFactory {
    KnowledgeServer create(WorkerConfig config) throws Exception;
  }

  private static final WorkerConfigSupplier DEFAULT_CONFIG_SUPPLIER = WorkerConfig::load;
  private static final ServerFactory DEFAULT_SERVER_FACTORY = KnowledgeServer::new;

  private static volatile WorkerConfigSupplier configSupplier = DEFAULT_CONFIG_SUPPLIER;
  private static volatile ServerFactory serverFactory = DEFAULT_SERVER_FACTORY;
  private IndexerWorker() {}

  public static void main(String[] args) throws Exception {
    // Install crash reporter before anything else — catches uncaught exceptions on any thread.
    // Uses CrashReporter.defaultCrashDir() to avoid System.getProperty in this module (guardrail).
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, throwable) -> {
          io.justsearch.telemetry.CrashReporter.writeCrashReport(
              io.justsearch.telemetry.CrashReporter.defaultCrashDir(),
              "worker",
              thread,
              throwable);
          System.exit(1);
        });
    io.justsearch.telemetry.CrashReporter.pruneOldCrashReports(
        io.justsearch.telemetry.CrashReporter.defaultCrashDir(), 30);

    log.info("Starting Knowledge Server (indexer-worker)...");

    // 347: Auto-detect GPU at ordinal 150. In snapshot mode (launched by Head), the Head's
    // resolved config at ordinal 450 already contains auto-detected values, so ordinal 150
    // is a no-op (higher ordinal wins). In standalone mode, this is the primary detection path.
    Map<String, String> gpuDetected = io.justsearch.ort.GpuAutoDetection.probe(
        io.justsearch.configuration.RepoRootLocator.findRepoRootOrNull());

    // Load the config snapshot written by HeadlessApp (if available).
    // This gives the Worker access to resolved configuration at ordinal 450,
    // combined with env vars (400) and JVM args (500).
    ResolvedConfig workerConfig = ResolvedConfigBuilder.loadWorkerSnapshotFromSysprop(gpuDetected);
    if (workerConfig != null) {
      ConfigStore.setGlobal(new ConfigStore(workerConfig));
      // Validate that the Worker's resolved config agrees with the snapshot on critical keys.
      // Divergence here means a JVM arg (ordinal 500) overrode the snapshot (ordinal 450),
      // which is intentional for operator overrides but should be visible in logs.
      validateSnapshotAgreement(ConfigStore.global().get());
    } else {
      // Standalone mode (no Head): bootstrap config from the standard base sources (env + YAML)
      // plus auto-detected hardware. Routing through contributeBaseSources() — the same shared
      // composition the Head and every other boot site use — is what makes it impossible for this
      // path to silently omit YAML again (the tempdoc 628 defect: recovery config was defaulting).
      // settings.json keys (model paths etc.) remain Head-only by module boundary (the worker does
      // not depend on app-services), which is acceptable for this dev escape-hatch.
      ResolvedConfig standaloneConfig = new ResolvedConfigBuilder()
          .contributeAutoDetected(gpuDetected)
          .contributeBaseSources()
          .build();
      ConfigStore.setGlobal(new ConfigStore(standaloneConfig));
      log.info("Standalone mode: config bootstrapped from base sources (env + YAML) + auto-detected (no Head snapshot)");
    }

    // Tempdoc 772 §J item 2: the win-x64 ORT CUDA execution-provider DLL is trimmed from the
    // shipped jar and relocated into the consent-gated cuda-runtime pack. If that pack delivered a
    // complete, version-matched ORT native set, point ORT at it by setting onnxruntime.native.path
    // BEFORE the first OrtEnvironment.getEnvironment() in this process — ORT captures the property
    // once at class-init. This is the earliest deterministic pre-ORT point: GpuAutoDetection.probe
    // (above) and config loading do not touch ORT, and every ORT session is created later, under
    // the KnowledgeServer built below. The pack dir is justsearch.onnxruntime.native_path (the
    // cuda12 dir the CUDA dependency DLLs already extract into); null on CPU-only installs, in
    // which case the property stays unset and ORT serves the CPU path from the jar's core natives.
    io.justsearch.ort.OrtCudaHelper.applyOrtNativePackProperty(
        ConfigStore.global().get().paths().ortNativePath());

    WorkerConfig config = configSupplier.load();
    log.info("Configuration loaded: dataDir={}", config.dataDir());

    try (KnowledgeServer server = serverFactory.create(config)) {
      // Verify composition-root invariants before starting services.
      // tempdoc 402 §3.2 — fail fast on any registered boot contract.
      BootContractRunner.validateAll();

      server.start();

      // Lane F stage A item A9: there is no port. This standalone entry point survives only
      // until item A13 collapses the two spawn paths into one; logging -1 would be a lie, so it
      // says what is true instead.
      log.info("Knowledge Server started (in-process services; no network listener)");

      // Add shutdown hook for graceful termination
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    log.info("Shutdown hook triggered");
                    try {
                      server.close();
                    } catch (Exception e) {
                      log.warn("Shutdown hook failed", e);
                    }
                  },
                  "knowledge-server-shutdown"));

      // Block until server terminates (via signal bus or shutdown)
      server.blockUntilShutdown();

      log.info("Knowledge Server terminated");
    }
  }

  /**
   * Validates that the Worker's resolved config agrees with the raw Head snapshot on critical keys.
   *
   * <p>Logs WARN for any key where the resolved value differs from the snapshot value. Divergence
   * means a JVM arg (ordinal 500) or env var (ordinal 400) overrode the snapshot (ordinal 450).
   * This is intentional for operator overrides but must be visible in logs so operators can
   * confirm the override was deliberate (tempdoc 331 item 4).
   *
   * <p>If no snapshot is configured (standalone mode), this method is not called.
   *
   * @param resolved the fully resolved Worker config
   */
  private static void validateSnapshotAgreement(ResolvedConfig resolved) {
    Map<String, String> snapshot = ResolvedConfigBuilder.loadRawWorkerSnapshotFromSysprop();
    if (snapshot.isEmpty()) return;

    List<String> criticalKeys =
        List.of(
            "justsearch.embed.onnx.model_path",
            "justsearch.onnxruntime.native_path",
            "justsearch.models.dir",
            "justsearch.splade.model_path",
            "justsearch.index.base_path");

    for (String key : criticalKeys) {
      String snapshotValue = snapshot.get(key);
      if (snapshotValue == null) continue; // key not in snapshot — no expectation to validate
      ConfigResolution resolution = resolved.resolution(key);
      String resolvedValue = resolution != null ? resolution.value() : null;
      if (!snapshotValue.equals(resolvedValue)) {
        String source =
            resolution != null
                ? resolution.sourceName()
                    + " (ordinal="
                    + resolution.sourceOrdinal()
                    + ")"
                : "<unresolved>";
        log.warn(
            "Config divergence: {} snapshot={} resolved={} (winning source: {})",
            key,
            snapshotValue,
            resolvedValue,
            source);
      }
    }
  }

  static void installTestHooks(WorkerConfigSupplier supplier, ServerFactory factory) {
    configSupplier = supplier == null ? DEFAULT_CONFIG_SUPPLIER : supplier;
    serverFactory = factory == null ? DEFAULT_SERVER_FACTORY : factory;
  }

  static void resetTestHooks() {
    configSupplier = DEFAULT_CONFIG_SUPPLIER;
    serverFactory = DEFAULT_SERVER_FACTORY;
  }
}
