/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.OnnxDiscoveredModel;
import io.justsearch.ort.OnnxSessionCache;
import io.justsearch.reranker.WorkerModelDiscovery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Health service for the Knowledge Server.
 *
 * <p>Provides deep health checks that verify:
 * <ul>
 *   <li>JobQueue (SQLite) connectivity</li>
 *   <li>Lucene index readability</li>
 *   <li>EmbeddingProvider availability (optional)</li>
 * </ul>
 *
 * <p>Also exposes worker state for system tests:
 * <ul>
 *   <li>RUNNING - Actively indexing</li>
 *   <li>PAUSED - Yielding to foreground load (a duty-cycle yield, not a stop)</li>
 *   <li>IDLE - No pending work</li>
 * </ul>
 */
public final class WorkerHealthService {
  private static final Logger log = LoggerFactory.getLogger(WorkerHealthService.class);

  private final String version;
  private final JobQueue jobQueue;
  private final IndexCountOps indexCountOps;
  private volatile EmbeddingProvider embeddingProvider;
  private volatile io.justsearch.indexerworker.bgem3.BgeM3Encoder bgeM3Encoder;
  private final Supplier<String> workerStateSupplier;
  private final List<WorkerModelDiscovery.DiscoveredModel> discoveredModels;
  private final Map<String, Supplier<Boolean>> modelActiveSuppliers = new ConcurrentHashMap<>();

  /**
   * Tempdoc 374 alpha.25 R13-A defect #2 deliberate-trigger hook (test-only).
   *
   * <p>The R13-A retry loop in {@code KnowledgeServerBootstrap.start()} polls
   * {@code client.isHealthy()} until budget elapses, recovering automatically
   * from transient SearcherManager warmup races. Round-13 reproduced the race
   * 1-of-3 cycles naturally; round-14 0-of-3. To make the test deterministic
   * (Rule 17), set env var {@code JUSTSEARCH_WORKER_HEALTH_SYNTHETIC_DELAY_MS}
   * to N — for the first N ms after this service starts, every {@code check()}
   * call adds {@code "Synthetic warmup delay (test hook)"} to the issues list
   * and sets {@code serving=false}. After N ms, normal probes resume.
   *
   * <p>Default 0 (off). Documented as a test-only hook — the literal "test hook"
   * appears in the response message string so any user observing it knows it's
   * an artifact of the deliberate-trigger setup, not a real failure.
   */
  private final long startupAtMs = System.currentTimeMillis();
  private final long syntheticDelayMs;

  /**
   * Creates a new WorkerHealthService with shallow checks only.
   *
   * @param version The service version string
   */
  public WorkerHealthService(String version) {
    this(version, null, null, null, null);
  }

  /**
   * Creates a new WorkerHealthService with deep health check support.
   *
   * @param version The service version string
   * @param jobQueue The job queue to check (may be null)
   * @param indexCountOps The index count ops to check (may be null)
   * @param embeddingProvider The embedding provider to check (may be null)
   */
  public WorkerHealthService(
      String version,
      JobQueue jobQueue,
      IndexCountOps indexCountOps,
      EmbeddingProvider embeddingProvider) {
    this(version, jobQueue, indexCountOps, embeddingProvider, null);
  }

  /**
   * Creates a new WorkerHealthService with full state tracking for system tests.
   *
   * @param version The service version string
   * @param jobQueue The job queue to check (may be null)
   * @param indexCountOps The index count ops to check (may be null)
   * @param embeddingProvider The embedding provider to check (may be null)
   * @param workerStateSupplier Supplier for current worker state (RUNNING/PAUSED/IDLE)
   */
  public WorkerHealthService(
      String version,
      JobQueue jobQueue,
      IndexCountOps indexCountOps,
      EmbeddingProvider embeddingProvider,
      Supplier<String> workerStateSupplier) {
    this(version, jobQueue, indexCountOps, embeddingProvider, workerStateSupplier, null);
  }

  /**
   * Creates a new WorkerHealthService with ONNX model discovery results.
   *
   * @param version The service version string
   * @param jobQueue The job queue to check (may be null)
   * @param indexCountOps The index count ops to check (may be null)
   * @param embeddingProvider The embedding provider to check (may be null)
   * @param workerStateSupplier Supplier for current worker state (RUNNING/PAUSED/IDLE)
   * @param discoveredModels Startup-time ONNX discovery results (may be null)
   */
  public WorkerHealthService(
      String version,
      JobQueue jobQueue,
      IndexCountOps indexCountOps,
      EmbeddingProvider embeddingProvider,
      Supplier<String> workerStateSupplier,
      List<WorkerModelDiscovery.DiscoveredModel> discoveredModels) {
    this.version = version != null ? version : "unknown";
    this.jobQueue = jobQueue;
    this.indexCountOps = indexCountOps;
    this.embeddingProvider =
        embeddingProvider != null ? embeddingProvider : NoOpEmbeddingProvider.INSTANCE;
    this.workerStateSupplier = workerStateSupplier;
    this.discoveredModels = discoveredModels != null ? discoveredModels : List.of();
    // Tempdoc 374 alpha.25 R13-A defect #2 deliberate-trigger hook. Default 0 (off).
    this.syntheticDelayMs = parseLongEnv("JUSTSEARCH_WORKER_HEALTH_SYNTHETIC_DELAY_MS", 0L);
    if (this.syntheticDelayMs > 0) {
      log.info(
          "WorkerHealthService synthetic warmup delay enabled: {}ms (test hook for R13-A retry-loop verification)",
          this.syntheticDelayMs);
    }
  }

  /** Parse a long env-var value, returning {@code defaultValue} on null/blank/parse failure. */
  private static long parseLongEnv(String name, long defaultValue) {
    String raw = System.getenv(name);
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /** Late-binds the embedding provider after deferred model initialization. */
  public void setEmbeddingProvider(EmbeddingProvider provider) {
    this.embeddingProvider = provider != null ? provider : NoOpEmbeddingProvider.INSTANCE;
  }

  /** Sets the BGE-M3 encoder (reports embeddingReady when BGE-M3 replaces EmbeddingProvider). */
  public void setBgeM3Encoder(io.justsearch.indexerworker.bgem3.BgeM3Encoder encoder) {
    this.bgeM3Encoder = encoder;
  }

  /** Registers a runtime session-active supplier for a model (e.g., from ORT CUDA status). */
  public void setModelActiveSupplier(String modelName, Supplier<Boolean> supplier) {
    modelActiveSuppliers.put(modelName, supplier);
  }

  /**
   * Runs the deep health check.
   *
   * @param request the (empty) health-check request
   * @return the health-check response; a failed probe is reported in the response, not thrown
   */
  public HealthCheckResponse check(HealthCheckRequest request) {
    log.trace("Health check received");

    List<String> issues = new ArrayList<>();
    boolean serving = true;
    boolean embeddingReady = false;
    boolean aiReady = false;

    // Tempdoc 374 alpha.25 R13-A defect #2 deliberate-trigger hook. When enabled
    // via env var, report DEGRADED for the first N ms after service start so the
    // head's retry loop has a deterministic warmup race to iterate through.
    if (syntheticDelayMs > 0) {
      long elapsed = System.currentTimeMillis() - startupAtMs;
      if (elapsed < syntheticDelayMs) {
        long remaining = syntheticDelayMs - elapsed;
        issues.add("Synthetic warmup delay (test hook): " + remaining + "ms remaining");
        serving = false;
      }
    }

    // Deep check: JobQueue (SQLite)
    if (jobQueue != null) {
      try {
        // Try to read queue depth - this exercises the SQLite connection
        long depth = jobQueue.queueDepth();
        log.trace("JobQueue healthy, depth={}", depth);
      } catch (RuntimeException e) {
        log.warn("JobQueue health check failed: {}", e.getMessage());
        log.debug("JobQueue health check failed (stack trace)", e);
        issues.add("JobQueue: " + e.getMessage());
        serving = false;
      }
    }

    // Deep check: Lucene index
    if (indexCountOps != null) {
      try {
        // Try to get doc count - this exercises the index reader
        long docCount = indexCountOps.docCount();
        log.trace("Lucene index healthy, docCount={}", docCount);
      } catch (RuntimeException e) {
        log.warn("Lucene health check failed: {}", e.getMessage());
        log.debug("Lucene health check failed (stack trace)", e);
        issues.add("Lucene: " + e.getMessage());
        serving = false;
      }
    }

    // Deep check: Embedding/AI service (non-critical for serving health)
    // BGE-M3 replaces EmbeddingProvider when active — report ready if either is available.
    if (bgeM3Encoder != null) {
      embeddingReady = true;
      aiReady = true;
    } else if (embeddingProvider.isAvailable()) {
      embeddingReady = true;
      aiReady = true;
    } else {
      log.debug("EmbeddingProvider not available (non-critical)");
    }

    // Build response
    String versionWithStatus = version;
    if (!issues.isEmpty()) {
      versionWithStatus = version + " [DEGRADED: " + String.join(", ", issues) + "]";
    }

    // Get current PID for zombie/watchdog tests
    long pid = ProcessHandle.current().pid();

    // Get worker state for time-lord tests
    String workerState = determineWorkerState();

    HealthCheckResponse.Builder builder = HealthCheckResponse.newBuilder()
        .setServing(serving)
        .setVersion(versionWithStatus)
        .setPid(pid)
        .setWorkerState(workerState)
        .setAiReady(aiReady)
        .setEmbeddingReady(embeddingReady);

    for (var dm : discoveredModels) {
      var modelBuilder = OnnxDiscoveredModel.newBuilder()
          .setModelName(dm.modelName())
          .setFound(dm.found())
          .setPath(dm.path() != null ? dm.path() : "")
          .setAutoDiscovered(dm.autoDiscovered());
      Supplier<Boolean> activeSupplier = modelActiveSuppliers.get(dm.modelName());
      if (activeSupplier != null) {
        try {
          modelBuilder.setSessionActive(Boolean.TRUE.equals(activeSupplier.get()));
        } catch (RuntimeException e) {
          log.debug("Failed to get session-active state for {}: {}", dm.modelName(), e.getMessage());
        }
      }
      builder.addOnnxModels(modelBuilder.build());
    }

    // Populate effective config for Head→Worker divergence detection (tempdoc 329).
    for (EnvRegistry key : EnvRegistry.CONFIG_DIVERGENCE_CHECK_KEYS) {
      key.get().ifPresent(value -> builder.putEffectiveConfig(key.sysProp(), value));
    }
    // tempdoc 623 U7: surface the ORT library version through the existing effective_config
    // map (no new proto field). The Worker is where ORT is actually initialized, so this is the
    // authoritative version; it reaches the Head's /api/debug/state (retained, un-hashed) for the
    // benchmark-release hardware projection.
    builder.putEffectiveConfig("ort.version", OnnxSessionCache.ortVersion());

    HealthCheckResponse response = builder.build();

    if (!serving) {
      log.warn("Health check failed: {}", issues);
    }

    return response;
  }

  /**
   * Determines the current worker state for system tests.
   *
   * @return Worker state: RUNNING, PAUSED, or IDLE
   */
  private String determineWorkerState() {
    if (workerStateSupplier != null) {
      try {
        String state = workerStateSupplier.get();
        if (state != null && !state.isBlank()) {
          return state;
        }
      } catch (RuntimeException e) {
        log.debug("Failed to get worker state: {}", e.getMessage());
      }
    }

    // Fallback: infer state from queue depth
    if (jobQueue != null) {
      try {
        long depth = jobQueue.queueDepth();
        // No-supplier fallback (unreachable in production — DefaultWorkerAppServices always
        // wires a supplier). Source the wire-stable strings from IndexingLoop.LoopState's
        // .name() output (W1.4) so a future enum rename fails to compile here too rather
        // than silently breaking the fallback.
        return depth > 0
            ? io.justsearch.indexerworker.loop.IndexingLoop.LoopState.RUNNING.name()
            : io.justsearch.indexerworker.loop.IndexingLoop.LoopState.IDLE.name();
      } catch (RuntimeException e) {
        log.debug("Failed to read queue depth for state inference: {}", e.getMessage());
      }
    }

    return io.justsearch.indexerworker.loop.IndexingLoop.LoopState.IDLE.name();
  }
}
