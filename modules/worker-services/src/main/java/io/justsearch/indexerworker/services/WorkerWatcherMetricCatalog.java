/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/**
 * Catalog for {@code index.watcher.*} metrics emitted by the Worker-side
 * {@link WorkerMethvinWatcher}. Tempdoc 418 Phase B3 introduced this watcher as a replacement
 * for the Head-side {@code MethvinWatcherStrategy}; this catalog is the typed entry point for
 * the metric on the index component.
 *
 * <p>The Head-side {@code WatcherMetricCatalog} (in {@code app-indexing}) emits the same metric
 * name. The two catalogs differ in tag schema: Head emits only {@code kind}; Worker emits
 * {@code component=worker_watcher} + {@code kind}. Each process registers its own
 * {@code LocalTelemetry}, so the per-View attribute filters apply per-process.
 */
public final class WorkerWatcherMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "index.watcher";

  public static final String EVENTS_TOTAL = "index.watcher.events_total";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(EVENTS_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(WorkerWatcherEventTags.KEYS)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "WorkerWatcherMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final CounterMetric<WorkerWatcherEventTags> eventsTotal;

  public WorkerWatcherMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.eventsTotal = registry.buildCounter(EVENTS_TOTAL);
  }

  /** Cached no-op singleton. */
  private static final WorkerWatcherMetricCatalog NOOP =
      new WorkerWatcherMetricCatalog(new NoopMetricRegistry(DEFINITIONS));

  /** No-op catalog for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static WorkerWatcherMetricCatalog noop() {
    return NOOP;
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<MetricDefinition> definitions() {
    return DEFINITIONS;
  }
}
