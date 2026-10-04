/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeGaugesSnapshot;
import io.justsearch.indexerworker.services.IndexRuntimeTags.CommitTags;
import io.justsearch.indexerworker.services.IndexRuntimeTags.SwapTags;
import io.justsearch.indexerworker.services.IndexRuntimeTags.ValidationTags;
import io.justsearch.telemetry.catalog.Buckets;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.Exemplars;
import io.justsearch.telemetry.catalog.GaugeMetric;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.RrdArchive;
import io.justsearch.telemetry.catalog.StatusEndpoint;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Tempdoc 417 Phase 1 catalog for {@code index.runtime.*} metrics. Replaces
 * {@code WorkerLuceneTelemetryAdapter}'s pre-cached counter pattern with a typed-instrument
 * catalog backed by {@link MetricRegistry}. The adapter is a thin façade over this catalog.
 *
 * <p>Tempdoc 417 F2 fix: instrument fields are {@code public final}, populated at construction.
 * The two-phase {@code bind()} pattern is gone. Bootstrap order is now: collect static
 * {@link #DEFINITIONS} from each catalog, build {@code LocalTelemetry}, construct catalog
 * instances against {@code telemetry.registry()}.
 */
public final class IndexRuntimeMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "index.runtime";

  // Metric names — public constants for cross-module reference.
  public static final String HARD_DELETE_TOTAL = "index.runtime.hard_delete_total";
  public static final String SOFT_DELETE_TOTAL = "index.runtime.soft_delete_total";
  public static final String BACKPRESSURE_TOTAL = "index.runtime.backpressure_total";
  public static final String DRAIN_TIMEOUT_TOTAL = "index.runtime.drain_timeout_total";
  public static final String COMMIT_MS = "index.runtime.commit_ms";
  public static final String SWAP_STARTED_TOTAL = "index.runtime.swap_started_total";
  public static final String SWAP_DURATION_MS = "index.runtime.swap_duration_ms";
  public static final String WRITE_BARRIER_WAIT_US = "index.runtime.write_barrier_wait_us";
  public static final String VALIDATION_FAILURE_TOTAL = "index.runtime.validation_failure_total";

  /**
   * Dense-vector FIELDS dropped from a write because the value could not be normalized — a
   * zero-magnitude or non-finite embedding (tempdoc 931 §C.3). Distinct from
   * {@link #VALIDATION_FAILURE_TOTAL}, which counts documents a write lane rejected: here the
   * document IS written, minus the one field, with its embedding status set to FAILED. A non-zero
   * trend means the encoder is producing degenerate vectors, not that indexing is failing.
   */
  public static final String VECTOR_DROPPED_TOTAL = "index.runtime.vector_dropped_total";

  /**
   * Chunk reads that returned NO text because the parent document is no longer at the revision the
   * chunk's stored offsets address (tempdoc 931 §E item 5). {@code chunk_content} is not stored, so
   * a chunk's text is re-sliced out of its parent on every read; between a parent rewrite and the
   * chunk regeneration that follows it, that slice would be a piece of the WRONG revision. The read
   * path refuses it — a search hit loses its excerpt, a RAG context omits the passage — and counts
   * it here. A non-zero trend means reads are landing inside that window; a sustained one means
   * chunk regeneration is not keeping up with parent rewrites.
   */
  public static final String CHUNK_REVISION_MISMATCH_TOTAL =
      "index.runtime.chunk_revision_mismatch_total";

  // Tempdoc 417 Phase 3b: 4 status gauges archived to RRD and surfaced on /api/status via
  // CoreIndexView. Suppliers read RuntimeSession atomics + CommitOps.refreshLagMs().
  public static final String WRITER_QUEUE_DEPTH = "index.runtime.writer_queue_depth";
  public static final String WRITER_PENDING_DOCS = "index.runtime.writer_pending_docs";
  public static final String COMMIT_COUNT = "index.runtime.commit_count";
  public static final String REFRESH_LAG_MS = "index.runtime.refresh_lag_ms";

  // Tempdoc 885 item 19: the NRT/commit cadence comparison needs a reopen count and a segment-churn
  // reading next to the commit count that already lives here. They join THIS catalog rather than
  // WorkerOpsMetricCatalog because commit_count above is already the all-paths commit counter read
  // off the same RuntimeGaugesSnapshot; a `worker.index.commit_total` would have been a second
  // authority for it (note that worker.commits.total is a different quantity — it counts only the
  // six IndexingLoop-attributed commits, not the commit timer, in-process port deletes or prune).
  // Tempdoc 912 item 2: COMMIT_TOTAL below is NOT the second authority that sentence rules out.
  // It carries a reason DIMENSION on the same funnel write, and RuntimeSession.commitCount derives
  // its total by summing those same per-reason slots — one authority, two projections of it.
  // Caveat on "all-paths": four durable commits bypass the funnel entirely and are counted by
  // neither (912 §C.4) — RuntimeSession materializeEmptyIndex + writer close, ComponentsFactory's
  // open-failure close, and KnowledgeServerMigrationOps' low-level CommitOps.commit().

  /** Reopens that swapped in a new reader, all paths. Pairs with {@link #COMMIT_COUNT}. */
  public static final String REOPEN_COUNT = "index.runtime.reopen_count";

  /** Segments the writer has created since the last reopen — the next reopen's backlog. */
  public static final String SEGMENTS_SINCE_REOPEN = "index.runtime.segments_since_reopen";

  /**
   * Commits by trigger (tempdoc 912 item 2). 885's live window could measure {@link #COMMIT_COUNT}
   * moving only 17 % under three multiplicative cadence relaxations but could NOT say which trigger
   * held the floor, because no per-trigger count existed. This is that count.
   *
   * <p>It is a counter and not a second authority for {@link #COMMIT_COUNT}: both are written at
   * the one commit funnel ({@code CommitOps.commitAndTrack}) from the same {@code CommitReason}, and
   * the session-side total is the SUM of its per-reason slots by construction ({@code
   * CommitCounters}). It differs from the {@code COMMIT_COUNT} gauge only in lifetime — this
   * accumulates across sessions, the gauge resets with the session (885: 46 vs 114).
   *
   * <p>Reason-tagged rather than surfaced on {@code /api/status}, for the same reason
   * {@link #REOPEN_COUNT} is: the cadence comparison reads its trend off the metrics NDJSON, and a
   * status-wire field would drag a proto change into a measurement knob.
   */
  public static final String COMMIT_TOTAL = "index.runtime.commit_total";

  /**
   * Static definitions. Pass to {@code LocalTelemetry}'s constructor before constructing this
   * catalog so per-metric Views are registered before the {@code SdkMeterProvider} is built.
   */
  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(HARD_DELETE_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.counter(SOFT_DELETE_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.counter(BACKPRESSURE_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.counter(DRAIN_TIMEOUT_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.histogram(COMMIT_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(IndexRuntimeTags.REASON_KEYS)
              .buckets(Buckets.TIME_HISTOGRAM)
              .exemplars(Exemplars.TRACE_BASED)
              .build(),
          MetricDefinition.counter(SWAP_STARTED_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(IndexRuntimeTags.REASON_KEYS)
              .build(),
          MetricDefinition.histogram(SWAP_DURATION_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(IndexRuntimeTags.REASON_KEYS)
              .buckets(Buckets.TIME_HISTOGRAM)
              .exemplars(Exemplars.TRACE_BASED)
              .build(),
          MetricDefinition.histogram(WRITE_BARRIER_WAIT_US)
              .unit(Unit.MICROSECONDS)
              .tagKeys(Set.of())
              .buckets(Buckets.WRITE_BARRIER_HISTOGRAM)
              // Hot path: per-record exemplars would balloon volume; suppress at export time.
              .exemplars(Exemplars.OFF)
              .build(),
          MetricDefinition.counter(VALIDATION_FAILURE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(IndexRuntimeTags.REASON_KEYS)
              .build(),
          MetricDefinition.counter(VECTOR_DROPPED_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.counter(CHUNK_REVISION_MISMATCH_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.counter(COMMIT_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(IndexRuntimeTags.REASON_KEYS)
              .cardinalityLimit(32)
              .build(),
          MetricDefinition.gauge(WRITER_QUEUE_DEPTH)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, "writerQueueDepth")
              .build(),
          MetricDefinition.gauge(WRITER_PENDING_DOCS)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, "writerPendingDocs")
              .build(),
          MetricDefinition.gauge(COMMIT_COUNT)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, "commitCount")
              .build(),
          MetricDefinition.gauge(REFRESH_LAG_MS)
              .unit(Unit.MILLISECONDS)
              .archivedTo(RrdArchive.STANDARD)
              .surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, "refreshLagMs")
              .build(),
          // Archived, not surfaced on /api/status: the cadence comparison reads a trend off the
          // metrics NDJSON, and adding status-wire fields would drag a proto change into a
          // measurement knob.
          MetricDefinition.gauge(REOPEN_COUNT)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .build(),
          MetricDefinition.gauge(SEGMENTS_SINCE_REOPEN)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .build());

  // Static-time validation: every declared metric name starts with the catalog's namespace.
  // Catches drift between metric-name constants and the namespace at class-load time.
  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "IndexRuntimeMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  // Typed instruments — public final, populated by the constructor.
  public final CounterMetric<EmptyTags> hardDeleteTotal;
  public final CounterMetric<EmptyTags> softDeleteTotal;
  public final CounterMetric<EmptyTags> backpressureTotal;
  public final CounterMetric<EmptyTags> drainTimeoutTotal;
  public final HistogramMetric<CommitTags> commitMs;
  public final CounterMetric<SwapTags> swapStartedTotal;
  public final HistogramMetric<SwapTags> swapDurationMs;
  public final HistogramMetric<EmptyTags> writeBarrierWaitUs;
  public final CounterMetric<ValidationTags> validationFailureTotal;
  /** Dropped dense-vector fields — see {@link #VECTOR_DROPPED_TOTAL}. */
  public final CounterMetric<EmptyTags> vectorDroppedTotal;
  /** Refused chunk-text reconstructions — see {@link #CHUNK_REVISION_MISMATCH_TOTAL}. */
  public final CounterMetric<EmptyTags> chunkRevisionMismatchTotal;
  /** Commits by trigger — see {@link #COMMIT_TOTAL}. */
  public final CounterMetric<CommitTags> commitTotal;
  // Phase 3b status gauges — register their async callbacks at catalog construction; the
  // suppliers route through {@code statusSupplier} (a {@code RuntimeGaugesSnapshot} provider).
  public final GaugeMetric<EmptyTags> writerQueueDepth;
  public final GaugeMetric<EmptyTags> writerPendingDocs;
  public final GaugeMetric<EmptyTags> commitCountGauge;
  public final GaugeMetric<EmptyTags> refreshLagMs;
  public final GaugeMetric<EmptyTags> reopenCount;
  public final GaugeMetric<EmptyTags> segmentsSinceReopen;

  /** Default supplier producing {@link RuntimeGaugesSnapshot#EMPTY}; used for tests / startup. */
  public static final Supplier<RuntimeGaugesSnapshot> EMPTY_STATUS =
      () -> RuntimeGaugesSnapshot.EMPTY;

  /**
   * Test/legacy constructor — gauges register against an EMPTY supplier returning zero values.
   * Production callers should use {@link #IndexRuntimeMetricCatalog(MetricRegistry, Supplier)}
   * to wire the live {@code RunningRuntime.runtimeGaugesSnapshot()} source.
   */
  public IndexRuntimeMetricCatalog(MetricRegistry registry) {
    this(registry, EMPTY_STATUS);
  }

  /**
   * Constructs the catalog and registers all typed instruments — including the 4 status gauges
   * whose async callbacks read from the supplied {@code statusSupplier}. The registry must
   * already know about {@link #DEFINITIONS}.
   *
   * <p>The {@code statusSupplier} is invoked at every flush by the OTel SDK; supply a
   * provider that's safe to call before {@code RunningRuntime} is fully initialized (use
   * {@link #EMPTY_STATUS} or null-safe wrapper).
   */
  public IndexRuntimeMetricCatalog(
      MetricRegistry registry, Supplier<RuntimeGaugesSnapshot> statusSupplier) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(statusSupplier, "statusSupplier");
    this.hardDeleteTotal = registry.buildCounter(HARD_DELETE_TOTAL);
    this.softDeleteTotal = registry.buildCounter(SOFT_DELETE_TOTAL);
    this.backpressureTotal = registry.buildCounter(BACKPRESSURE_TOTAL);
    this.drainTimeoutTotal = registry.buildCounter(DRAIN_TIMEOUT_TOTAL);
    this.commitMs = registry.buildHistogram(COMMIT_MS);
    this.swapStartedTotal = registry.buildCounter(SWAP_STARTED_TOTAL);
    this.swapDurationMs = registry.buildHistogram(SWAP_DURATION_MS);
    this.writeBarrierWaitUs = registry.buildHistogram(WRITE_BARRIER_WAIT_US);
    this.validationFailureTotal = registry.buildCounter(VALIDATION_FAILURE_TOTAL);
    this.vectorDroppedTotal = registry.buildCounter(VECTOR_DROPPED_TOTAL);
    this.chunkRevisionMismatchTotal = registry.buildCounter(CHUNK_REVISION_MISMATCH_TOTAL);
    this.commitTotal = registry.buildCounter(COMMIT_TOTAL);
    this.writerQueueDepth =
        registry.buildGauge(
            WRITER_QUEUE_DEPTH,
            EmptyTags.INSTANCE,
            () -> (double) statusSupplier.get().writerQueueDepth());
    this.writerPendingDocs =
        registry.buildGauge(
            WRITER_PENDING_DOCS,
            EmptyTags.INSTANCE,
            () -> (double) statusSupplier.get().writerPendingDocs());
    this.commitCountGauge =
        registry.buildGauge(
            COMMIT_COUNT,
            EmptyTags.INSTANCE,
            () -> (double) statusSupplier.get().commitCount());
    this.refreshLagMs =
        registry.buildGauge(
            REFRESH_LAG_MS,
            EmptyTags.INSTANCE,
            () -> (double) statusSupplier.get().refreshLagMs());
    this.reopenCount =
        registry.buildGauge(
            REOPEN_COUNT, EmptyTags.INSTANCE, () -> (double) statusSupplier.get().reopenCount());
    this.segmentsSinceReopen =
        registry.buildGauge(
            SEGMENTS_SINCE_REOPEN,
            EmptyTags.INSTANCE,
            () -> (double) statusSupplier.get().segmentsSinceReopen());
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
