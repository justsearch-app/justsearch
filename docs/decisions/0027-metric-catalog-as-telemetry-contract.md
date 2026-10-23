---
title: "ADR-0027: MetricCatalog as the Telemetry Contract"
type: decision
status: stable
description: "MetricCatalog is the typed telemetry contract; the legacy Telemetry emission surface is retired. Two KnowledgeSearchEngine skip counters still bypass the catalog through GlobalOpenTelemetry, as recorded in the 2026-10-20 amendment."
date: 2026-04-25
probes: none - the retired Telemetry.counter/timer/gauge surface survives only in javadoc that documents its retirement, so a grep-absent probe false-fails on three comment sites (tempdoc 884 §B.3).
last_reviewed: 2026-10-20
---

# ADR-0027: MetricCatalog as the Telemetry Contract

## Status
Accepted

Current implementation corrections are recorded in the 2026-10-20 amendment below.

## The contract (canonical API surface)

```java
// modules/telemetry/.../catalog/
public interface MetricCatalog {
    String namespace();
    List<MetricDefinition> definitions();
    static MetricCatalog of(String namespace, List<MetricDefinition> definitions) { ... }
}

public record MetricDefinition(...) {
    public static Builder counter(String name);
    public static Builder histogram(String name);
    public static Builder gauge(String name);
    public static Builder observableCounter(String name);

    public static final class Builder {
        public Builder unit(Unit unit);
        public Builder tagKeys(Set<String> keys);
        public Builder buckets(List<Long> bucketBoundaries);
        public Builder exemplars(Exemplars policy);
        public Builder cardinalityLimit(int limit);
        public Builder archivedTo(RrdArchive archive);
        public Builder surfacedAt(StatusEndpoint endpoint, String fieldName);
        public MetricDefinition build();
    }
}

public interface TagSchema {
    Set<String> allowedKeys();
    Attributes toAttributes();
}

public interface MetricRegistry {
    <T extends TagSchema> CounterMetric<T> buildCounter(String name);
    <T extends TagSchema> HistogramMetric<T> buildHistogram(String name);
    <T extends TagSchema> GaugeMetric<T> buildGauge(String name, T tags, Supplier<Double> supplier);
    <T extends TagSchema> ObservableCounterMetric<T> buildObservableCounter(
            String name, T tags, LongSupplier supplier);
}

// Sealed instrument hierarchy — typed by tag schema
public final class CounterMetric<T extends TagSchema>     { void increment(T tags); ... }
public final class HistogramMetric<T extends TagSchema>   { void record(long value, T tags); ... }
public final class GaugeMetric<T extends TagSchema>       { ... } // value via supplier
public final class ObservableCounterMetric<T extends TagSchema> { ... }
```

Every metric in the codebase is a `MetricDefinition` registered on a `MetricCatalog`. Emission goes through the typed instrument fields the catalog populates at construction.

## Decision

The legacy `Telemetry.counter/timer/histogram/gauge/meter` surface is retired. `Telemetry` is now an empty marker interface; modules wanting to emit metrics declare a `MetricCatalog` implementation, register its `DEFINITIONS` at `LocalTelemetry` construction, then construct the typed catalog instance against `LocalTelemetry.registry()`.

The catalog substrate enforces structurally what the legacy surface enforced by convention: tag schemas at compile time (typed `T extends TagSchema` parameter on every instrument), bucket bounds via SDK Views (declared on `MetricDefinition`, applied before any reader), per-metric exemplar policy, namespace-prefix invariant (validated at class load + ArchUnit), RRD archive policy (`archivedTo(...)`), and API status surface (`surfacedAt(...)` validated by `MetricSurfaceContractTest`).

This is the same design philosophy tempdoc 406 shipped for runtime lifecycle (sealed `LuceneRuntime` + phase-typed values + consumer-as-holder), applied to telemetry. See `docs/explanation/08-observability.md` for the complete lifecycle, wire-format guarantees, test patterns, and rejected alternatives.

## Consequences

**Positive:**
- Compile-time checking on tag schemas — wrong tag type at the emit site fails at `javac`, not at NDJSON-read time.
- Single source of truth: bucket bounds, tag-key allowlists, exemplar policy, RRD archive flag, status-record field name all live on `MetricDefinition`.
- Drift detection: `MetricSurfaceContractTest` (ArchUnit) fails CI when a catalog's `surfacedAt` field name diverges from the API record's components. `RrdMetricStore` derives the curated set from catalog declarations — adding `archivedTo(STANDARD)` automatically populates the RRD; removing it removes the datasource.
- No silent tag-key drops: the SDK applies the per-View attribute filter before any exporter sees the data.
- Adopters can't accidentally diverge: the only emit path is through a `MetricCatalog`. Sibling tempdocs (412–415, future) are constrained by the same type system.

**Negative (accepted):**
- New metrics cost ~4 lines (constant + definition entry + typed field + builder call) plus a `TagSchema` record if the tag layout is novel.
- Adding a metric requires re-running the bootstrap (constructor-time View registration). Runtime metric addition isn't supported — every metric the codebase emits is known at build time.
- The `Telemetry` marker interface adds one indirection (cast to `LocalTelemetry` to get the registry). Considered replacing `Telemetry` parameters with `LocalTelemetry` directly; rejected to avoid leaking the concrete type into module APIs.

## Cross-references

- `docs/explanation/08-observability.md` — full lifecycle, wire-format guarantees, test patterns, alternatives considered, namespace ownership table.
- [ADR-0026](0026-manual-ci-triggering.md) — agent-discipline pattern that the catalog's ArchUnit rule depends on.
- [ADR-0029](0029-telemetry-events-bridge-vs-direct-emit.md) — *how* an adopter reaches the catalog: pick the `TelemetryEvents` bridge or the direct-emit façade by module-dependency structure. This ADR decides the contract; 0029 decides the adoption idiom (kept separate 2026-09-02, tempdoc 884).
- Service lifecycle pattern — tempdoc 406's lifecycle pattern, mirrored here for telemetry.
- Implementation tempdoc: tempdoc 417 (tier4/tier5 followups from 406).
- Tempdoc 419 C3 V1 (2026-04-26) — first non-trivial consumer extending the substrate: 2 new `StatusEndpoint` enum values (`GPU_STATUS_VIEW`, `TELEMETRY_HEALTH_VIEW`), 3 new `surfacedAt(...)` declarations across `WorkerOpsMetricCatalog` (worker side) and `HeadGpuMetricCatalog` (head side), and a parallel `HeadMetricSurfaceContractTest` in `app-services` mirroring the worker-side drift detection. See tempdoc 419 (C3 entry).
- Tempdoc 419 C3 V2 (2026-04-28) — three named-question HealthEvents consuming the V1 substrate: `telemetry-degraded` (extracted `TelemetryHealthClassifier` shared with `/api/telemetry/health`), `recentDocsPerSec` rate trend (new `worker.documents.indexed.rate_per_sec` gauge with `surfacedAt(CORE_INDEX_VIEW, "recentDocsPerSec")`), and `gpu-saturated` (new head-side `GpuSaturationMonitor` mirroring `OperationalMetrics.ThroughputMonitor` discipline + daemon-thread `GpuSaturationSampler`). Two new `ReadinessDimension` enum constants (`TELEMETRY`, `GPU`) and one new `LifecycleReasonCode` (`GPU_SATURATED`).

## Amendment 2026-10-20: catalog authority has two direct-counter exceptions

The catalog remains the intended emission contract. The original statements
that every metric uses it and that bypass is structurally impossible overstate
current enforcement: `KnowledgeSearchEngine.rerankerSkippedCounter` and
`lambdamartSkippedCounter` build `search.reranker_skipped.total` and
`search.lambdamart_skipped.total` directly through `GlobalOpenTelemetry`.
They do not use a typed `MetricCatalog` instrument. This amendment records the
exception, not permission to add another. Their presence alone does not prove
that those instruments export at runtime.
Evidence: `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeSearchEngine.java`.
Moving them into a catalog is a separate implementation task.
