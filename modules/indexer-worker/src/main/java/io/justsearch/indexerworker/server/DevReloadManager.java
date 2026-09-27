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
 * <p>On reload signal — the existence of {@code <dataDir>/runtime/dev-reload.request}, polled by
 * {@link io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus#isReloadRequested()} and
 * consumed by deleting the file ({@code clearReloadSignal()}); it was a byte in the memory-mapped
 * file until lane F stage A item A10 deleted that bus — quiesces the IndexingLoop, reconstructs
 * {@link DefaultWorkerAppServices} from the same {@link InfraContext}, re-wires models,
 * publishes the new instance, and starts the new indexing loop. Until lane F stage A item A9 the
 * publish step was two: re-point the three {@code Delegating*Service} gRPC delegates, then update
 * the field. The delegates are gone, so the volatile write IS the swap — see
 * {@link #performReload()}.
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
  private volatile boolean closeRetryPending;

  DevReloadManager(KnowledgeServer server) {
    this.server = server;
    log.info("DevReloadManager initialized");
  }

  boolean isReloadRequested() {
    return closeRetryPending || server.signalBus.isReloadRequested();
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

      // 2. Await deferred model init completion before capturing the current generation.
      awaitDeferredInit();

      // The initializer may itself upgrade the runtime. Do not hold runtimeSwapLock while
      // awaiting it; acquire the same owner lock as admin reload and shutdown afterward.
      try (var owner = server.beginDevReplacement()) {
        owner.assertOwned();
        boolean published = false;
        try {
          // The incumbent may have stopped its watcher while its indexing owner drains. Retain
          // this request in the sentinel so a refused close retries without another compile.
          WorkerAppServices oldServices = server.appServices;
          EncoderSet modelOwner = server.captureDevReplacementEncoderSet(oldServices);
          if (oldServices != null) {
            log.info("Closing old application services...");
            closeRetryPending = true;
            server.retireServingView();
            oldServices.close();
          }

          // HotSwap has updated class bytecode. Retain B as an explicit pending owner from
          // construction through model wiring and producer startup, including every failure cut.
          server.closeFailedPendingAppServices();
          WorkerAppServices newServices = server.newAppServices();
          server.retainPendingAppServices(newServices);
          rewireModels(newServices, modelOwner);
          newServices.startIndexingLoop();
          server.publishServingView(newServices);
          server.releasePendingAppServices(newServices);
          published = true;
          closeRetryPending = false;
          server.notifyRecordedServicesPublished();

          // The stamp is diagnostic; failure after publication cannot roll back B.
          updateBuildStampFromReloadFile();
          long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
          log.info("=== DEV HOT-RELOAD: complete ({}ms) ===", elapsedMs);

        } catch (Exception e) {
          if (!published) {
            closeRetryPending = true;
            try {
              server.closeFailedPendingAppServices();
            } catch (RuntimeException cleanup) {
              if (cleanup != e) e.addSuppressed(cleanup);
            }
          }
          throw e;
        }
      }

    } catch (Exception e) {
      log.error("DEV HOT-RELOAD failed", e);
    } finally {
      reloadInProgress.set(false);
    }
  }

  private void awaitDeferredInit() {
    CompletableFuture<Void> deferredInit = server.deferredModelInit;
    if (deferredInit == null) {
      return;
    }
    if (deferredInit.isDone()) {
      try {
        deferredInit.get();
        return;
      } catch (Exception e) {
        log.warn("Deferred model init completed with failure: {}", e.getMessage());
        return;
      }
    }
    log.info("Waiting for deferred model init to complete before reload...");
    try {
      deferredInit.get(DEFERRED_INIT_TIMEOUT_S, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      log.error(
          "Deferred model init did not complete within {}s, proceeding anyway",
          DEFERRED_INIT_TIMEOUT_S);
    } catch (ExecutionException e) {
      log.warn(
          "Deferred model init failed (proceeding with reload): {}", e.getCause().getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting for deferred model init");
    }
  }

  private void rewireModels(WorkerAppServices newServices, EncoderSet owner) {
    // Process-scoped helpers survive a service replacement independently of the model set.
    if (server.embeddingCompatController != null) {
      newServices.wireEmbeddingCompatController(server.embeddingCompatController);
    }
    if (server.disambiguationService != null) {
      newServices.wireDisambiguationService(server.disambiguationService);
    }
    if (owner == null) return;

    var embedding = owner.embedding();
    var ner = owner.ner();
    var splade = owner.splade();
    var idf = owner.spladeIdf();
    var bge = owner.bgeM3();
    var reranker = owner.reranker();
    var citation = owner.citation();
    if (embedding != null) newServices.wireEmbeddingProvider(embedding);
    if (ner != null) newServices.wireNerService(ner);
    if (splade != null) newServices.wireSpladeEncoder(splade);
    if (idf != null) newServices.wireSpladeIdfQueryEncoder(idf);
    if (bge != null) newServices.wireBgeM3Encoder(bge);
    if (reranker != null) newServices.wireSearchReranker(reranker);
    if (citation != null) newServices.wireCitationScorer(citation);
    newServices.wirePolicySnapshotSupplier(() -> owner.surfaceForOwner().policies());
    newServices.wireStageEnabled(embedding != null, splade != null, ner != null);

    // Diagnostics retain this exact generation; only the embedding slot can change on GPU handoff.
    newServices.wireGpuDiagnostics(new GpuDiagnosticSuppliers(
        splade == null ? null : splade::getOrtCudaStatus,
        splade == null ? null : splade::resolvedModelPath,
        () -> owner.embedding() == null ? null : owner.embedding().getOrtCudaStatus(),
        () -> owner.embedding() == null ? null : owner.embedding().resolvedBackendId(),
        () -> owner.embedding() == null ? 0 : owner.embedding().gpuLayers(),
        reranker == null ? null : reranker::getOrtCudaStatus,
        ner == null ? null : ner::getOrtCudaStatus,
        citation == null ? null : citation::getOrtCudaStatus,
        bge == null ? null : bge::getOrtCudaStatus));
  }

  /**
   * 371: Reads the build stamp left by the MCP reload tool and updates the system property.
   * The MCP tool writes the on-disk stamp to {@code <dataDir>/reload-build-stamp.txt} after
   * a successful HotSwapPush. We read it here so the next status projection reports the correct
   * stamp, preventing false-positive "stale JVM" warnings from jseval. (It reached the Head over
   * the {@code IndexStatus} RPC until lane F stage A item A9 deleted the wire; the stamp itself
   * now describes the Engine distribution — item A13 re-homed {@code generateBuildStamp} onto
   * {@code modules/ui/build/install/ui/build-stamp.txt}.)
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

  // 516 P3 FINAL CUT: rewireEmbeddingTelemetry helper removed — the events sink is now
  // pre-wired via KS.newAppServices() at the IndexingLoop ctor seam, so the post-reload
  // re-wiring path collapses into the standard newAppServices() construction.
}
