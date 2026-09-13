/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.telemetry.catalog.MetricRegistry;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Immutable bag of infrastructure references needed to construct application services.
 *
 * <p>Passed to {@link DefaultWorkerAppServices} as a single argument. The lifecycle
 * fields are {@link Supplier suppliers} so consumers re-read on each request and pick
 * up holder swaps (e.g., {@code DeferredRuntime.upgradeWriter()} returning a fresh
 * {@code RunningRuntime}).
 *
 * <p>Catalog instances live in {@code worker-services}, which {@code worker-core} cannot
 * import — instead, {@code metricRegistry} is exposed so consumers can build their own
 * typed catalogs locally. This is the canonical seam for catalog construction in services
 * that compose against an {@code InfraContext}.
 */
public record InfraContext(
    WorkerConfig config,
    JobQueue jobQueue,
    Supplier<LuceneRuntime> searchLifecycleSupplier,
    Supplier<LuceneRuntime> ingestLifecycleSupplier,
    WorkerSignalBus signalBus,
    Telemetry telemetry,
    MetricRegistry metricRegistry,
    Path indexBasePath,
    Path activeIndexPath,
    Supplier<MigrationProgressSnapshot> migrationProgressSupplier,
    long migrationSwitchingMaxDurationMs,
    PathResolutionStore pathResolutionStore,
    DocumentIdentityStore documentIdentityStore) {

  public InfraContext {
    pathResolutionStore =
        pathResolutionStore != null ? pathResolutionStore : PathResolutionStore.NOOP;
    documentIdentityStore =
        documentIdentityStore != null ? documentIdentityStore : DocumentIdentityStore.UNAVAILABLE;
  }

  /** Back-compatible shape for callers that wire path resolution but not document identity. */
  public InfraContext(
      WorkerConfig config,
      JobQueue jobQueue,
      Supplier<LuceneRuntime> searchLifecycleSupplier,
      Supplier<LuceneRuntime> ingestLifecycleSupplier,
      WorkerSignalBus signalBus,
      Telemetry telemetry,
      MetricRegistry metricRegistry,
      Path indexBasePath,
      Path activeIndexPath,
      Supplier<MigrationProgressSnapshot> migrationProgressSupplier,
      long migrationSwitchingMaxDurationMs,
      PathResolutionStore pathResolutionStore) {
    this(
        config,
        jobQueue,
        searchLifecycleSupplier,
        ingestLifecycleSupplier,
        signalBus,
        telemetry,
        metricRegistry,
        indexBasePath,
        activeIndexPath,
        migrationProgressSupplier,
        migrationSwitchingMaxDurationMs,
        pathResolutionStore,
        DocumentIdentityStore.UNAVAILABLE);
  }

  /**
   * Tempdoc 419 / T5.2 — convenience factory for callers that don't yet wire a
   * {@link PathResolutionStore}. Defaults the store to {@link PathResolutionStore#NOOP} so
   * existing callsites compile unchanged; production passes the SqlitePathResolutionStore
   * via the canonical 13-arg constructor.
   */
  public InfraContext(
      WorkerConfig config,
      JobQueue jobQueue,
      Supplier<LuceneRuntime> searchLifecycleSupplier,
      Supplier<LuceneRuntime> ingestLifecycleSupplier,
      WorkerSignalBus signalBus,
      Telemetry telemetry,
      MetricRegistry metricRegistry,
      Path indexBasePath,
      Path activeIndexPath,
      Supplier<MigrationProgressSnapshot> migrationProgressSupplier,
      long migrationSwitchingMaxDurationMs) {
    this(
        config,
        jobQueue,
        searchLifecycleSupplier,
        ingestLifecycleSupplier,
        signalBus,
        telemetry,
        metricRegistry,
        indexBasePath,
        activeIndexPath,
        migrationProgressSupplier,
        migrationSwitchingMaxDurationMs,
        PathResolutionStore.NOOP,
        DocumentIdentityStore.UNAVAILABLE);
  }
}
