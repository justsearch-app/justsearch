/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.observability.diagnostic.DiagnosticChannelAppenderInstaller;
import io.justsearch.app.services.bootstrap.OrchestrationHandles;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import io.justsearch.app.services.observability.metrics.DocumentsIndexedRateMetricProducer;
import io.justsearch.app.services.observability.metrics.GpuMemoryUtilizationMetricProducer;
import io.justsearch.app.services.observability.metrics.GpuUtilizationMetricProducer;
import io.justsearch.app.services.observability.metrics.JobQueueDepthMetricProducer;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §10 final-push: extracted from {@code HeadAssembly.buildOrchestrationHandles}
 * (~70 LOC of LIFO-teardown lambda assembly). The bootstrap now calls
 * {@link #build} with the relevant collaborators; this static helper owns the lambda
 * wrapping (thread interrupt+join, gRPC shutdown+awaitTermination, listener
 * removal coupled to manager close, etc.).
 *
 * <p>All parameters may be null; {@link OrchestrationHandles} records null fields for
 * non-applicable items and {@link OrchestrationHandles#close()} iterates LIFO over
 * non-null handles.
 */
public final class OrchestrationAssembly {

  private static final Logger log = LoggerFactory.getLogger(OrchestrationAssembly.class);

  private OrchestrationAssembly() {}

  /** Builder for the OrchestrationHandles record. All inputs nullable. */
  public static OrchestrationHandles build(
      Thread gplThread,
      LambdaMartReranker reranker,
      JobQueueDepthMetricProducer jqdProducer,
      DocumentsIndexedRateMetricProducer dirProducer,
      GpuUtilizationMetricProducer guProducer,
      GpuMemoryUtilizationMetricProducer gmProducer,
      InferenceLifecycleManager manager,
      io.justsearch.app.api.ModeChangeListener gpuListener,
      io.justsearch.app.services.runtimestate.RuntimeReconciler runtimeReconciler,
      IndexingService indexing,
      DocumentService documents,
      DiagnosticChannelAppenderInstaller appender,
      AutoCloseable bridgeSubscription,
      KnowledgeHttpApiAdapter agentSearchAdapter,
      AutoCloseable indexingJobsBridge,
      AutoCloseable agentToolHandlers) {
    return new OrchestrationHandles(
        gplThread == null ? null : (AutoCloseable) () -> stopThread(gplThread),
        reranker == null ? null : (AutoCloseable) reranker::close,
        jqdProducer == null ? null : (AutoCloseable) jqdProducer::stop,
        dirProducer == null ? null : (AutoCloseable) dirProducer::stop,
        guProducer == null ? null : (AutoCloseable) guProducer::stop,
        gmProducer == null ? null : (AutoCloseable) gmProducer::stop,
        manager == null
            ? null
            : (AutoCloseable) () -> stopManager(manager, gpuListener, runtimeReconciler),
        (indexing instanceof AutoCloseable ic) ? ic : null,
        (documents instanceof AutoCloseable dc) ? dc : null,
        appender == null ? null : (AutoCloseable) appender::detach,
        bridgeSubscription,
        agentSearchAdapter == null ? null : (AutoCloseable) agentSearchAdapter::closeReranker,
        indexingJobsBridge,
        agentToolHandlers);
  }

  private static void stopThread(Thread t) {
    t.interrupt();
    try {
      t.join(5_000);
      if (t.isAlive()) {
        log.warn("Thread {} did not terminate within 5s", t.getName());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void stopManager(
      InferenceLifecycleManager manager,
      io.justsearch.app.api.ModeChangeListener listener,
      io.justsearch.app.services.runtimestate.RuntimeReconciler runtimeReconciler) {
    // Tempdoc 737: stop the reconciler thread first so it cannot drive a transition while the
    // manager is closing.
    if (runtimeReconciler != null) {
      try {
        runtimeReconciler.close();
      } catch (RuntimeException e) {
        log.warn("RuntimeReconciler close failed: {}", e.getMessage());
      }
    }
    if (listener != null) {
      manager.removeModeChangeListener(listener);
    }
    manager.close();
  }
}
