/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only service restart manager for hot-reloading Worker application services.
 *
 * <p>On reload signal (MMF byte at offset 29): quiesces the IndexingLoop, reconstructs
 * {@link DefaultWorkerAppServices} from the same {@link InfraContext}, re-wires models,
 * swaps gRPC delegates, and starts the new indexing loop.
 *
 * <p>This works in tandem with JBR + HotSwapPush (Phase 1): class bytecode is updated by
 * HotSwap, then Phase 2 restarts services so constructors, static initializers, and field
 * defaults are re-evaluated with the new code. For method-body-only changes, HotSwap alone
 * is sufficient; Phase 2 handles the structural changes that require service reconstruction.
 *
 * <p>Gated by {@code -Djustsearch.dev.hotreload=true}. Package-private — instantiated only
 * by {@link KnowledgeServer}.
 *
 * @see <a href="docs/tempdocs/305-hot-reload.md">Tempdoc 305 Phase 2</a>
 */
final class DevReloadManager {
  private static final Logger log = LoggerFactory.getLogger(DevReloadManager.class);
  private static final long DEFERRED_INIT_TIMEOUT_S = 30;

  private final KnowledgeServer server;
  private final AtomicBoolean reloadInProgress = new AtomicBoolean(false);

  DevReloadManager(KnowledgeServer server) {
    this.server = server;
    log.info("DevReloadManager initialized");
  }

  void performReload() {
    if (!reloadInProgress.compareAndSet(false, true)) {
      log.warn("Reload already in progress, ignoring signal");
      return;
    }

    try {
      log.info("=== DEV HOT-RELOAD: starting ===");
      long t0 = System.nanoTime();

      // 1. Clear the signal immediately (so a new compile during reload re-triggers)
      server.signalBus.clearReloadSignal();

      // 2. Await deferred model init completion — get the ModelContext
      ModelContext modelCtx = awaitDeferredInit();

      // 3. Close old application services (stops IndexingLoop, up to 5s join)
      WorkerAppServices oldServices = server.appServices;
      if (oldServices != null) {
        log.info("Closing old application services...");
        oldServices.close();
      }

      // 4. Construct new services from the same InfraContext.
      // Class bytecode was already updated by HotSwap (Phase 1). Reconstruction
      // re-evaluates constructors, static initializers, and field defaults.
      // 516 P3 FINAL CUT: use the KS helper that pre-wires migrationActiveSupplier +
      // embeddingTelemetry at ctor time (replaces the old rewireEmbeddingTelemetry shim
      // and the wireMigrationActiveSupplier post-ctor call).
      WorkerAppServices newServices = server.newAppServices();

      // 5. Re-wire models from ModelContext (typed, no scattered field reads)
      rewireModels(newServices, modelCtx);

      // 6. Swap gRPC delegates (volatile write — atomic for new requests)
      server.searchWrapper.setDelegate(newServices.searchService());
      server.ingestWrapper.setDelegate(newServices.ingestService());
      server.healthWrapper.setDelegate(newServices.healthService());

      // 7. Update KnowledgeServer's appServices reference
      server.appServices = newServices;

      // 8. Start new indexing loop
      newServices.startIndexingLoop();

      // 9. 371: Update build stamp from reload-build-stamp.txt (written by MCP reload tool).
      //    This prevents false-positive "stale JVM" warnings from jseval after a successful reload.
      updateBuildStampFromReloadFile();

      long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
      log.info("=== DEV HOT-RELOAD: complete ({}ms) ===", elapsedMs);

    } catch (Exception e) {
      log.error("DEV HOT-RELOAD failed", e);
    } finally {
      reloadInProgress.set(false);
    }
  }

  private ModelContext awaitDeferredInit() {
    CompletableFuture<ModelContext> deferredInit = server.deferredModelInit;
    if (deferredInit == null) {
      return null;
    }
    if (deferredInit.isDone()) {
      try {
        return deferredInit.get();
      } catch (Exception e) {
        log.warn("Deferred model init completed with failure: {}", e.getMessage());
        return null;
      }
    }
    log.info("Waiting for deferred model init to complete before reload...");
    try {
      return deferredInit.get(DEFERRED_INIT_TIMEOUT_S, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      log.error(
          "Deferred model init did not complete within {}s, proceeding anyway",
          DEFERRED_INIT_TIMEOUT_S);
      return null;
    } catch (ExecutionException e) {
      log.warn(
          "Deferred model init failed (proceeding with reload): {}", e.getCause().getMessage());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting for deferred model init");
      return null;
    }
  }

  private void rewireModels(WorkerAppServices newServices, ModelContext modelCtx) {
    if (modelCtx == null) {
      // Fall back to reading scattered fields if ModelContext is unavailable
      rewireModelsFromFields(newServices);
      return;
    }
    if (modelCtx.embeddingService() != null) {
      newServices.wireEmbeddingProvider(modelCtx.embeddingService());
    }
    // 516 P3 FINAL CUT: embeddingTelemetry pre-wired via newAppServices() above.
    if (modelCtx.ecc() != null) {
      newServices.wireEmbeddingCompatController(modelCtx.ecc());
    }
    if (modelCtx.nerService() != null) {
      newServices.wireNerService(modelCtx.nerService());
    }
    if (modelCtx.spladeEncoder() != null) {
      newServices.wireSpladeEncoder(modelCtx.spladeEncoder());
    }
    if (modelCtx.spladeIdfQueryEncoder() != null) {
      newServices.wireSpladeIdfQueryEncoder(modelCtx.spladeIdfQueryEncoder());
    }
    if (modelCtx.bgeM3Encoder() != null) {
      newServices.wireBgeM3Encoder(modelCtx.bgeM3Encoder());
    }
    if (modelCtx.disambiguationService() != null) {
      newServices.wireDisambiguationService(modelCtx.disambiguationService());
    }
    // Rebuild GPU diagnostics from ModelContext instances. The embeddingService
    // is intentionally read live from `server.embeddingService` inside the
    // suppliers below (not captured here) so post-unload nulls propagate.
    var bge = modelCtx.bgeM3Encoder();
    var enc = modelCtx.spladeEncoder();
    var ner = modelCtx.nerService();
    var citation = server.citationScorerInstance;
    // observations.md fix (sibling of KnowledgeServer change): spladeOrtCudaStatus
    // / spladeModelPath are SPLADE-specific. Don't coalesce with bgeM3 — bgeM3 has
    // its own slot below.
    java.util.function.Supplier<io.justsearch.ort.OrtCudaStatus> sparseStatusSupplier =
        enc != null ? enc::getOrtCudaStatus : null;
    java.util.function.Supplier<String> sparseModelPathSupplier =
        enc != null ? enc::resolvedModelPath : null;
    var reranker = server.searchRerankerInstance;
    // observations.md fix (sibling of KnowledgeServer change): re-read
    // `server.embeddingService` per call so post-unload nulls (set by the
    // change-listener registered in KnowledgeServer.boot) propagate through
    // the hot-reloaded diagnostics chain too.
    newServices.wireGpuDiagnostics(
        new GpuDiagnosticSuppliers(
            sparseStatusSupplier,
            sparseModelPathSupplier,
            () -> {
              var live = server.embeddingService;
              return live != null ? live.getOrtCudaStatus() : null;
            },
            () -> {
              var live = server.embeddingService;
              return live != null ? live.resolvedBackendId() : null;
            },
            () -> {
              // gpuLayers consumer auto-unboxes — return 0 (not null)
              // when no embedding service. Mirrors the KnowledgeServer
              // boot-path fix.
              var live = server.embeddingService;
              return live != null ? live.gpuLayers() : 0;
            },
            reranker != null ? reranker::getOrtCudaStatus : null,
            ner != null ? ner::getOrtCudaStatus : null,
            citation != null ? citation::getOrtCudaStatus : null,
            bge != null ? bge::getOrtCudaStatus : null));
  }

  /**
   * 371: Reads the build stamp left by the MCP reload tool and updates the system property.
   * The MCP tool writes the on-disk stamp to {@code <dataDir>/reload-build-stamp.txt} after
   * a successful HotSwapPush. We read it here so the next {@code IndexStatus} RPC reports
   * the correct stamp, preventing false-positive "stale JVM" warnings from jseval.
   */
  private void updateBuildStampFromReloadFile() {
    try {
      java.nio.file.Path stampFile = server.dataDir.resolve("reload-build-stamp.txt");
      if (java.nio.file.Files.exists(stampFile)) {
        String stamp = java.nio.file.Files.readString(stampFile).trim();
        if (!stamp.isEmpty()) {
          System.setProperty(
              io.justsearch.configuration.EnvRegistry.BUILD_STAMP.sysProp(), stamp);
          log.info("Build stamp updated to {}", stamp);
        }
      }
    } catch (Exception e) {
      log.debug("Failed to update build stamp: {}", e.getMessage());
    }
  }

  private void rewireModelsFromFields(WorkerAppServices newServices) {
    if (server.embeddingService != null) {
      newServices.wireEmbeddingProvider(server.embeddingService);
    }
    // 516 P3 FINAL CUT: embeddingTelemetry pre-wired via newAppServices() above.
    if (server.embeddingCompatController != null) {
      newServices.wireEmbeddingCompatController(server.embeddingCompatController);
    }
    if (server.nerServiceInstance != null) {
      newServices.wireNerService(server.nerServiceInstance);
    }
    if (server.spladeEncoderInstance != null) {
      newServices.wireSpladeEncoder(server.spladeEncoderInstance);
    }
    if (server.spladeIdfQueryEncoder != null) {
      newServices.wireSpladeIdfQueryEncoder(server.spladeIdfQueryEncoder);
    }
    if (server.disambiguationService != null) {
      newServices.wireDisambiguationService(server.disambiguationService);
    }
    // Rebuild GPU diagnostics from retained field references.
    // observations.md fix: re-read `server.embeddingService` per call (mirrors
    // the boot-path lambdas in KnowledgeServer); other instances stay
    // captured because they don't have a hot-unload path.
    var enc = server.spladeEncoderInstance;
    var bge = server.bgeM3EncoderInstance;
    var ner = server.nerServiceInstance;
    var citation = server.citationScorerInstance;
    var reranker = server.searchRerankerInstance;
    newServices.wireGpuDiagnostics(
        new GpuDiagnosticSuppliers(
            enc != null ? enc::getOrtCudaStatus : null,
            enc != null ? enc::resolvedModelPath : null,
            () -> {
              var live = server.embeddingService;
              return live != null ? live.getOrtCudaStatus() : null;
            },
            () -> {
              var live = server.embeddingService;
              return live != null ? live.resolvedBackendId() : null;
            },
            () -> {
              // gpuLayers auto-unbox; return 0 (not null) when no
              // embedding service. Same fix-class as the rewire path
              // above + KnowledgeServer boot path.
              var live = server.embeddingService;
              return live != null ? live.gpuLayers() : 0;
            },
            reranker != null ? reranker::getOrtCudaStatus : null,
            ner != null ? ner::getOrtCudaStatus : null,
            citation != null ? citation::getOrtCudaStatus : null,
            bge != null ? bge::getOrtCudaStatus : null));
  }

  // 516 P3 FINAL CUT: rewireEmbeddingTelemetry helper removed — the events sink is now
  // pre-wired via KS.newAppServices() at the IndexingLoop ctor seam, so the post-reload
  // re-wiring path collapses into the standard newAppServices() construction.
}
