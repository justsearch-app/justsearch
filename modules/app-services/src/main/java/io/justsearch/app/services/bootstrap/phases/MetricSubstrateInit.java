/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.observability.metrics.DocumentsIndexedRateMetricChangeRegistry;
import io.justsearch.app.observability.metrics.DocumentsIndexedRateMetricResourceCatalog;
import io.justsearch.app.observability.metrics.GpuMemoryUtilizationMetricChangeRegistry;
import io.justsearch.app.observability.metrics.GpuMemoryUtilizationMetricResourceCatalog;
import io.justsearch.app.observability.metrics.GpuUtilizationMetricChangeRegistry;
import io.justsearch.app.observability.metrics.GpuUtilizationMetricResourceCatalog;
import io.justsearch.app.observability.metrics.JobQueueDepthMetricChangeRegistry;
import io.justsearch.app.observability.metrics.JobQueueDepthMetricResourceCatalog;
import io.justsearch.app.observability.metrics.TimeseriesSnapshotHolder;
import io.justsearch.app.services.observability.metrics.DocumentsIndexedRateMetricProducer;
import io.justsearch.app.services.observability.metrics.GpuMemoryUtilizationMetricProducer;
import io.justsearch.app.services.observability.metrics.GpuUtilizationMetricProducer;
import io.justsearch.app.services.observability.metrics.JobQueueDepthMetricProducer;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;

/**
 * Tempdoc 519 §7 / Step 7: static phase function that initializes the four timeseries Resource
 * metric sets — job-queue-depth, documents-indexed-rate, gpu-utilization, gpu-memory-utilization.
 * Each set is a ResourceCatalog + ChangeRegistry + SnapshotHolder + a Producer that is non-null
 * only when {@code telemetry instanceof LocalTelemetry}. Replaces
 * {@code HeadAssembly#initTimeseriesMetrics}.
 *
 * <p>Side effect: producers that are non-null are started (their RRD scheduler kicks off). The
 * caller is responsible for calling {@code stop()} on each non-null producer during teardown.
 */
public final class MetricSubstrateInit {

  private MetricSubstrateInit() {}

  /** Bundled output — 16 metric substrate fields (4 catalogs × {catalog, changes, holder, producer}). */
  public record Output(
      JobQueueDepthMetricResourceCatalog jobQueueDepthMetricResourceCatalog,
      JobQueueDepthMetricChangeRegistry jobQueueDepthMetricChangeRegistry,
      TimeseriesSnapshotHolder jobQueueDepthMetricHolder,
      JobQueueDepthMetricProducer jobQueueDepthMetricProducer,
      DocumentsIndexedRateMetricResourceCatalog documentsIndexedRateMetricResourceCatalog,
      DocumentsIndexedRateMetricChangeRegistry documentsIndexedRateMetricChangeRegistry,
      TimeseriesSnapshotHolder documentsIndexedRateMetricHolder,
      DocumentsIndexedRateMetricProducer documentsIndexedRateMetricProducer,
      GpuUtilizationMetricResourceCatalog gpuUtilizationMetricResourceCatalog,
      GpuUtilizationMetricChangeRegistry gpuUtilizationMetricChangeRegistry,
      TimeseriesSnapshotHolder gpuUtilizationMetricHolder,
      GpuUtilizationMetricProducer gpuUtilizationMetricProducer,
      GpuMemoryUtilizationMetricResourceCatalog gpuMemoryUtilizationMetricResourceCatalog,
      GpuMemoryUtilizationMetricChangeRegistry gpuMemoryUtilizationMetricChangeRegistry,
      TimeseriesSnapshotHolder gpuMemoryUtilizationMetricHolder,
      GpuMemoryUtilizationMetricProducer gpuMemoryUtilizationMetricProducer) {}

  public static Output run(io.justsearch.core.execution.EngineExecutorRegistry executors, Telemetry telemetry) {
    JobQueueDepthMetricResourceCatalog jqdCatalog = new JobQueueDepthMetricResourceCatalog();
    JobQueueDepthMetricChangeRegistry jqdChanges = new JobQueueDepthMetricChangeRegistry();
    TimeseriesSnapshotHolder jqdHolder = new TimeseriesSnapshotHolder();
    JobQueueDepthMetricProducer jqdProducer =
        (telemetry instanceof LocalTelemetry ltJqd)
            ? new JobQueueDepthMetricProducer(executors, ltJqd::getRrdStore, jqdHolder, jqdChanges)
            : null;
    if (jqdProducer != null) {
      jqdProducer.start();
    }

    DocumentsIndexedRateMetricResourceCatalog dirCatalog =
        new DocumentsIndexedRateMetricResourceCatalog();
    DocumentsIndexedRateMetricChangeRegistry dirChanges =
        new DocumentsIndexedRateMetricChangeRegistry();
    TimeseriesSnapshotHolder dirHolder = new TimeseriesSnapshotHolder();
    DocumentsIndexedRateMetricProducer dirProducer =
        (telemetry instanceof LocalTelemetry ltDir)
            ? new DocumentsIndexedRateMetricProducer(executors, ltDir::getRrdStore, dirHolder, dirChanges)
            : null;
    if (dirProducer != null) {
      dirProducer.start();
    }

    GpuUtilizationMetricResourceCatalog guCatalog = new GpuUtilizationMetricResourceCatalog();
    GpuUtilizationMetricChangeRegistry guChanges = new GpuUtilizationMetricChangeRegistry();
    TimeseriesSnapshotHolder guHolder = new TimeseriesSnapshotHolder();
    GpuUtilizationMetricProducer guProducer =
        (telemetry instanceof LocalTelemetry ltGu)
            ? new GpuUtilizationMetricProducer(executors, ltGu::getRrdStore, guHolder, guChanges)
            : null;
    if (guProducer != null) {
      guProducer.start();
    }

    GpuMemoryUtilizationMetricResourceCatalog gmCatalog =
        new GpuMemoryUtilizationMetricResourceCatalog();
    GpuMemoryUtilizationMetricChangeRegistry gmChanges =
        new GpuMemoryUtilizationMetricChangeRegistry();
    TimeseriesSnapshotHolder gmHolder = new TimeseriesSnapshotHolder();
    GpuMemoryUtilizationMetricProducer gmProducer =
        (telemetry instanceof LocalTelemetry ltGm)
            ? new GpuMemoryUtilizationMetricProducer(executors, ltGm::getRrdStore, gmHolder, gmChanges)
            : null;
    if (gmProducer != null) {
      gmProducer.start();
    }

    return new Output(
        jqdCatalog, jqdChanges, jqdHolder, jqdProducer,
        dirCatalog, dirChanges, dirHolder, dirProducer,
        guCatalog, guChanges, guHolder, guProducer,
        gmCatalog, gmChanges, gmHolder, gmProducer);
  }
}
