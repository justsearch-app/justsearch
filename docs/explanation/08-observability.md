---
title: Observability & Telemetry
type: explanation
status: stable
description: "Telemetry, NDJSON logs, and Distributed Tracing."
---

# Observability & Telemetry

Because JustSearch runs locally on user machines, we cannot rely on cloud logging (Splunk/Datadog). Instead, we embed a "Black Box" recorder inside the application to allow for post-mortem debugging and real-time introspection.

## The observed-happening register (observability-authority tier)

Everything below — metrics, NDJSON logs, traces, health conditions, the action lifecycle, indexing-job rows, advisories — is a member of one family: an **observed happening**, anything the system records about *what it did or what is true of it*. That family is governed at the authority tier by a single register, [`governance/observed-happening.v1.json`](../../governance/observed-happening.v1.json) (tempdoc 575). Each stream **declares** its one canonical source, its KIND (the primitive that owns it — a `Resource` shape vs a `DiagnosticChannel` vs a metric), its governed **projections** (the read-view surfaces that render it), and — when it is stateful/in-flight — its **liveness owner**.

This is the same **projection spine** the per-domain sections of this doc already instantiate, lifted one tier up so it holds *family-wide*: **one authority → a typed declaration → a governed projection → a coverage gate that fails the build on drift**. The MetricCatalog/[ADR-0027](../decisions/0027-metric-catalog-as-telemetry-contract.md) stack (below) is the metrics member; the health "named-question" pattern is the health-conditions member; ADR-0036 fixes the Resource-vs-channel axis. The register's gate (`observed-happening`) then makes the recurring failures *unrepresentable*: a new undeclared stream, a stream fragmented across two sources, a primitive-misclassification, or a stateful stream with no liveness owner each fail the build.

One concrete invariant the register single-sources is the in-flight **liveness window** (tempdoc 575 §15): a job is shown RUNNING only while its heartbeat is fresh. The three constants that bound this — the worker heartbeat interval, the FE display-freshness window, and the reaper window — are declared **once** in the register's `action-lifecycle.liveness` block, and the `observed-happening/liveness-window-coherent` gate fails the build if the real worker/FE constants drift from them or violate the ordering invariant (`heartbeatMs < displayStaleMs ≤ reaperStaleMs`, with `displayStaleMs ≥ 3× heartbeatMs`).

The full design — the facet→primitive derivation, the reverse-coverage rule, and the scope bound (Resource/DiagnosticChannel-backed streams; the boot/scan/search-trace family stays on its own spines) — lives in tempdoc 575.

## The Telemetry Stack (`modules/telemetry`)

We use a lightweight, local-first implementation of **OpenTelemetry metrics**, adapted for desktop use:
- **Producer**: `LocalTelemetry` (Head + Worker) wires per-metric `View`s from typed `MetricCatalog` declarations
- **Exporter**: `NdjsonMetricExporter` writes **append-only NDJSON** locally (no collector required)

Key property: every metric is declared in a typed `MetricCatalog` (per [ADR-0027](../decisions/0027-metric-catalog-as-telemetry-contract.md)). The catalog's `MetricDefinition` carries the metric name, tag schema, bucket bounds, exemplar policy, RRD archive flag, and (optionally) the API status record field name it surfaces at. The SDK applies these as per-View `setAttributeFilter` + `setBucketBoundaries` before any reader sees data — so high-cardinality attributes (paths, doc IDs, raw queries, UUIDs) are stripped at the source, not at the exporter.

### Metric catalog ownership

Every emit path flows through a typed catalog. A non-exhaustive map of which catalog owns which namespace:

| Namespace | Catalog | Module |
|-----------|---------|--------|
| `api.*` | `HeadApiMetricCatalog` | `modules/app-services/.../observability` |
| `head.http.*` | `HeadHttpInflightMetricCatalog` | `modules/app-services/.../observability` |
| `gpu.*` | `HeadGpuMetricCatalog` | `modules/app-services/.../observability` |
| `head.jvm.*`, `worker.jvm.*`, `launcher.jvm.*` | `JvmMetricCatalog` (prefix-parameterised) | `modules/telemetry` |
| `jvm.uptime_ms` | `BaselineMetricCatalog` (auto-registered by every `LocalTelemetry`) | `modules/telemetry` |
| `index.runtime.*` | `IndexRuntimeMetricCatalog` | `modules/worker-services` |
| `pipeline.*` | `IndexingPipelineMetricCatalog` | `modules/worker-services` |
| `extraction.*` | `ExtractionMetricCatalog` | `modules/worker-services` |
| `embedding.runtime.*` | `EmbeddingMetricCatalog` | `modules/worker-services` |
| `worker.*` (ops) | `WorkerOpsMetricCatalog` | `modules/worker-services` |
| `ort.session.*` | `OrtSessionMetricCatalog` | `modules/worker-services` |
| `index.watcher.*` | `WatcherMetricCatalog` | `modules/app-indexing` |
| `ocr.*` | `OcrMetricCatalog` | `modules/worker-services` |
| `vdu.*` | `VduMetricCatalog` | `modules/app-services` |
| `ipc.*` | `IpcMetricCatalog` | `modules/app-services` |
| `rag.*` | `RagMetricCatalog` | `modules/app-services` |
| `search.*` (paging) | `SearchPagingMetricCatalog` | `modules/app-search` |
| `agent.*`, `gen_ai.*` | `AgentMetricCatalog`, `GenAiMetricCatalog` | `modules/app-agent` |
| `inference.*` | `InferenceMetricCatalog` (tempdoc 412) | `modules/app-services` |

The bootstrap callsite (`HeadlessApp.java`, `KnowledgeServer.start()`,
`LauncherEnvironment`) passes the catalog `DEFINITIONS` lists to the
`LocalTelemetry` constructor. Each consumer (controller, service) then
constructs the typed catalog against `LocalTelemetry.registry()` and emits
via the catalog's `public final` instrument fields. Drift between the
catalog and the API status surface is caught by
`MetricSurfaceContractTest` (an ArchUnit-style rule). Drift between the
catalog and the RRD curated set is impossible by construction —
`RrdMetricStore` derives the curated set from `MetricDefinition.rrdArchive()`
non-null flags at boot.

### Catalog lifecycle (declaration → boot → wireup → emission)

1. **Declaration (module load).** Each module owning metrics defines a
   `MetricCatalog` implementation with a static `DEFINITIONS` list:

   ```java
   public final class HeadApiMetricCatalog implements MetricCatalog {
     public static final String NAMESPACE = "api";
     public static final String REQUEST_MS = "api.request_ms";
     public static final String ERROR_TOTAL = "api.error.total";

     public static final List<MetricDefinition> DEFINITIONS = List.of(
         MetricDefinition.histogram(REQUEST_MS)
             .unit(Unit.MILLISECONDS)
             .tagKeys(HeadApiTags.REQUEST_KEYS)
             .buckets(List.of(50L, 100L, 250L, 500L, 1_000L, 5_000L))
             .build(),
         MetricDefinition.counter(ERROR_TOTAL)
             .unit(Unit.COUNT)
             .tagKeys(HeadApiTags.ERROR_KEYS)
             .build());
     // typed instrument fields ...
   }
   ```

2. **Boot (`LocalTelemetry` construction).** The bootstrap callsite passes
   the catalog's static `DEFINITIONS` to the `LocalTelemetry` constructor,
   which builds OTel `View`s per metric (tag schemas, bucket bounds,
   exemplar policy, RRD archive flag) before the `SdkMeterProvider` is
   built:

   ```java
   var telemetry = new LocalTelemetry(
       dataDir, flushMs, "justsearch-headless", profile, "metrics.ndjson",
       List.of(
           MetricCatalog.of(HeadApiMetricCatalog.NAMESPACE, HeadApiMetricCatalog.DEFINITIONS),
           // … one entry per catalog
           JvmMetricCatalog.catalogFor("head")));
   ```

   `BaselineMetricCatalog` is appended automatically by the
   `LocalTelemetry` constructor — every process gets `jvm.uptime_ms` for free.

3. **Wireup (typed instrument construction).** After `LocalTelemetry`
   exists, the consumer constructs the typed catalog against
   `LocalTelemetry.registry()`:

   ```java
   var apiCatalog = new HeadApiMetricCatalog(telemetry.registry());
   ```

   The catalog constructor populates `public final` typed instrument fields
   (`Counter<ApiErrorTags> errorTotal`, etc.).

4. **Emission (callsite).** Callsites call typed methods on the instrument
   fields. Tag types are checked at compile time:

   ```java
   apiCatalog.errorTotal.increment(
       new HeadApiTags.ApiErrorTags(code, code.errorClass(), route));
   ```

   Passing a wrong tag type fails at `javac`. There is no string-keyed
   metric name lookup at the emit site — the catalog's typed fields are
   the only path.

### Wire-format guarantees enforced by the substrate

- **Tag-key filter.** `MetricDefinition.allowedTagKeys()` is wired as the
  per-View `setAttributeFilter`. Keys outside the schema are stripped by
  the SDK (not the exporter) before reaching any reader. The historic
  `NdjsonMetricExporter.ALLOWED_TAG_KEYS` set is gone.
- **Bucket bounds.** `MetricDefinition.bucketBoundaries()` is the single
  source of truth, applied via per-View `setAggregation`. Per-callsite
  `HistogramConfig.sloMsBuckets` lists are gone.
- **Cardinality limits.** `MetricDefinition.cardinalityLimit(int)` caps
  the number of distinct attribute combinations a metric tracks. Defaults
  to OTel's 2000 unless the metric carries open `String` tag values
  (route paths, status codes), in which case the catalog tightens it.
- **Exemplar policy.** Per-metric `Exemplars` (NEVER, TRACE_BASED,
  ALWAYS_ON) is declared on the definition and applied by the exporter at
  write time.
- **Producer/consumer wiring.** `archivedTo(RrdArchive)` auto-includes the
  metric in `RrdMetricStore`'s curated set. `surfacedAt(StatusEndpoint,
  fieldName)` is validated at build time by `MetricSurfaceContractTest`
  against the corresponding API record's components — drift fails CI.

### Test patterns for new catalogs

- **`TestMetricRegistry`** (in `modules/telemetry/src/testFixtures`) backs
  catalogs in unit tests without the OTel SDK overhead. Assertions on
  counter values / histogram counts / gauge readings are directly against
  the registry.
- **`NoopMetricRegistry`** (production class) is the no-op factory for
  catalogs constructed without a `LocalTelemetry`. Each catalog typically
  exposes a `Foo.noop()` static factory that returns a cached singleton
  whose instruments emit no-ops.
- **Smoke tests** for each catalog confirm definitions cover the declared
  metric names, the typed fields are non-null, and emission through the
  catalog produces the expected wire format. See `JvmMetricCatalogSmokeTest`
  and `HeadApiMetricCatalogSmokeTest` for the canonical pattern.
- **Wire-format regression tests** (e.g.,
  `IndexRuntimeWireFormatRegressionTest`) construct a real
  `LocalTelemetry`, emit a fixed sequence of metrics, parse the resulting
  NDJSON, and assert structural equivalence — catches regressions in
  tag-key ordering, bucket bounds, exemplar suppression.
- **`MetricSurfaceContractTest`** (ArchUnit-style reflective rule) walks
  every catalog at build time and asserts each `surfacedAt(...)` declared
  field name matches an actual record component on the corresponding API
  record. Drift fails CI. Worker-side catalogs are validated by the test in
  `modules/worker-services`; head-side catalogs (e.g., `HeadGpuMetricCatalog`)
  are validated by the parallel `HeadMetricSurfaceContractTest` in
  `modules/app-services`.
- **Emit-path tests for events-interface seams.** When a catalog is
  consumed via a zero-dep events interface (e.g.,
  `EmbeddingTelemetryEvents` in `worker-core`, with the
  `EmbeddingTelemetry` façade implementing it in `worker-services`), the
  catalog smoke + wire-format tests prove the catalog and façade
  emit correctly — but they don't prove the *production caller* invokes
  the interface at the right code paths. Cover that gap with a
  recording-events test: implement the events interface as a recording
  list, drive the production class (e.g., `EmbeddingService`) with a
  mock backend, and assert the recorded calls match the expected
  sequence and tag values. See `EmbeddingServiceTelemetryEmitTest` and
  `IndexingLoopUnloadTelemetryEmitTest` for the canonical pattern. The
  recording sink also lets ordering tests assert "emit happened before
  side-effect X" via timestamp comparison.

### Health explanations (named-question pattern)

Per ADR-0027 and tempdoc 419 C3, the catalog substrate's curated metrics
back targeted Health explanations, not a generic dashboard. The intended
pattern:

1. The frontend's `deriveHealthEvents.ts` produces named `HealthEvent` IDs
   from the existing `SystemStatus` shape — e.g., `index-throughput-stalled`,
   `memory-pressure`, `queue-db-unhealthy`. The taxonomy is the source of
   truth and is **not** forked into a backend `/api/health/explanations`
   endpoint.
2. `/api/status` exposes per-event evidence as additional fields on the
   existing records:
   - `worker.core.recentJobQueueDepth: long[]` — 30-min trend of
     `worker.job_queue.depth` (curated RRD metric). Backs
     `index-throughput-stalled` and `index-throughput-degraded`.
   - `gpu.recentUtilizationPercent: double[]`,
     `gpu.recentMemoryUtilizationPercent: double[]` — 30-min trends of
     `gpu.utilization.percent` and `gpu.memory.utilization.percent`.
     Available to future GPU-related events.
   - `telemetryHealth.flushFailureCount: long`,
     `telemetryHealth.gaugeCallbackFailureCount: long` — counters from
     `TelemetryHealthState`. Available to a future `telemetry-degraded`
     event.
   - **Embedding lifecycle metrics (tempdoc 413, archived to RRD):**
     `embedding.runtime.invoke_failure_total{operation,reason}`,
     `embedding.runtime.cache_miss_total`, `embedding.runtime.cache_size`,
     and `embedding.runtime.unload_total{reason}`. Back the future
     refinement of today's binary `embedding-not-ready` event into typed
     events:
     - `embedding-paused-for-chat` when a recent `unload_total{reason=GPU_HANDOFF}` indicates hybrid-inference VRAM handoff;
     - `embedding-failing` when `invoke_failure_total{reason=BACKEND_EXCEPTION}` is incrementing;
     - existing `embedding-not-ready` retained for the cold/init failure case.

     The catalog declares `archivedTo(RrdArchive.STANDARD)`, so the
     metrics auto-derive into the **worker's** `RrdMetricStore` curated
     set and are bundled in `/api/diagnostics/export`. They are NOT
     directly queryable via the head-side `/api/debug/metrics/timeseries`
     endpoint today: that endpoint queries the head's `RrdMetricStore`,
     which only sees catalogs registered in `HeadlessApp.java`'s
     `LocalTelemetry` constructor (not the worker's catalogs registered
     in `KnowledgeServer.java`). Wiring worker-archived metrics into the
     head-side timeseries endpoint requires either (a) a worker-side
     timeseries endpoint that the head proxies to, or (b) head-side
     registration of the namespace so the head is at least aware of the
     names. WP3's sparkline-on-event UX would need one of those.
     Renaming today's binary event into the typed events is the WP3 work
     described in tempdoc 419 §WP3 — see also tempdoc 413's
     "Critical-analysis followup" section for the metric semantics.

   Each backend-evidence field is wired through the same `surfacedAt(...)`
   contract test as Phase 3b's runtime gauges, so renaming a metric
   without updating the record (or vice versa) fails CI.
3. Frontend renders the trend arrays as sparklines next to the
   corresponding HealthEvent only when the event is firing. No sparklines
   on healthy events. No standalone metrics dashboard.
4. The "Export diagnostics" action wires the existing
   `POST /api/diagnostics/export` endpoint into the Health view as a
   support escalation. The endpoint already produces a privacy-redacted
   ZIP including status snapshots, logs, telemetry NDJSON, and policy
   snapshots.

For a pasteable bug-report aid, Help & Support also exposes
`core.copy-diagnostic-summary`. This is deliberately not derived from the ZIP: the app-services
composer emits only a deterministic typed allowlist (build and Runtime Contract versions, platform,
lifecycle states and known reason codes, safe GPU capability, and parseable crash
timestamp/process/exception type), sanitizes and caps values, and caps the whole UTF-8 payload at
8 KiB. It reads no logs, stack traces, exception messages, environment dump, debug state, or runtime
manifest. The UI copies the transient operation result directly and renders only fixed receipt text;
the `METADATA_ONLY` operation audit records no summary payload.

V2 (2026-04-28) extends the pattern with three named-question events
consuming the same substrate, all following the existing
`OperationalMetrics.ThroughputMonitor` discipline (rolling-window state
machine, hardcoded sensible defaults, backend computes / frontend renders):

- **`telemetry-degraded`** — surfaces the existing `TelemetryHealthController`
  classification (5min stale / <0.9 success rate / disk-low) on the
  `/api/status` readiness envelope's new `TELEMETRY` dim. The shared
  `TelemetryHealthClassifier` static helper feeds both surfaces; threshold
  changes happen in one place.
- **`recentDocsPerSec` rate trend** — new
  `worker.documents.indexed.rate_per_sec` gauge curated for RRD archive,
  surfaced as `worker.core.recentDocsPerSec`. Renders as a complementary
  sparkline next to V1's `recentJobQueueDepth` for the same firing throughput
  events. Depth answers "is the backlog draining," rate answers "is the
  indexer making progress."
- **`gpu-saturated`** — new head-side `GpuSaturationMonitor` (180s rolling
  window mirroring `ThroughputMonitor`'s shape exactly) plus a daemon-thread
  `GpuSaturationSampler` (15s cadence; short-circuits on NVML-unavailable
  machines). Activity gate composed of `engineMonitor.queueDepth() +
  processingJobsCount + GPL_RUNNING + onlineAi.isAvailable()`. Fires when
  sustained > 80% utilization with zero gate signals; the
  `onlineAi.isAvailable()` term suppresses false positives during normal
  llama-server background residency.

Backend pattern reused across V2: each new windowed-state metric mirrors
`OperationalMetrics.ThroughputMonitor`'s shape (synchronized ArrayDeque with
`WINDOW_MS`/`MAX_GAP_MS`/`MAX_SAMPLES` constants, `compute(activityGate)`
returning `Result(double avgValue, String state)`). Adding future
sustained-state events follows the same template.

Out of scope (419 C3 explicit non-goals):

- A generic time-series explorer / metrics dashboard.
- Backend-side derivation of HealthEvents (the frontend's
  `deriveHealthEvents.ts` is the source of truth for the taxonomy).
- New HealthEvents added without a corresponding named user/agent
  question.
- Trend-windowed events with new hysteresis logic — `ThroughputMonitor`
  already provides sustained-state windowing via the existing pattern;
  tuning a constant is config, not feature work.

### Why not other approaches

When designing the catalog substrate, four alternatives were considered
and rejected:

1. **Stay with the legacy `Telemetry.counter/timer/histogram/gauge` surface
   and add an `ALLOWED_TAGS` registry per metric.** Rejected: string-keyed
   tag allowlists drift silently. The compile-time signal ("wrong tag type
   for this metric") is the value the catalog adds.
2. **Sealed `MetricCatalog` interface with `ServiceLoader`-style
   discovery.** Rejected: discovery without declaration produces hidden
   side-effects at module load. The current pattern — each catalog has a
   static `DEFINITIONS` list and the bootstrap callsite passes the list
   explicitly — keeps the wiring visible and grep-able.
3. **Per-module `XTelemetry` thin façade pattern (pre-refactor).**
   Rejected: every module invented its own conventions for tag handling
   and metric declaration; there was no central type tying metric
   declarations to API surface (`surfacedAt`) or RRD archive policy
   (`archivedTo`). The catalog substrate is the central type the façade
   pattern lacked.
4. **Delete `Telemetry` entirely and replace every parameter with
   `LocalTelemetry` or `MetricRegistry`.** Considered. Defaulted to the
   marker-interface variant because cross-module APIs that take a
   `Telemetry telemetry` parameter (test fixtures, bootstrap factories)
   don't need to depend on `LocalTelemetry`. Marking `Telemetry` as a
   marker keeps the parameter signatures stable while stripping the emit
   surface.

### Privacy-Safe Logging

Search queries are privacy-sensitive and should not appear in production logs. The codebase uses a `SensitiveQuery` wrapper pattern:

```java
private record SensitiveQuery(String value) {}
private static String redact(Object x) {
  return (x instanceof SensitiveQuery) ? "[REDACTED]" : String.valueOf(x);
}
```

Files implementing this pattern:
- `KnowledgeSearchController.java` (UI API layer)
- `SearchOrchestrator.java` (Worker search orchestrator)

### 1. NDJSON Exporters
Instead of sending metrics to a collector, we write them to local files in **NDJSON** (Newline Delimited JSON) format.
*   **Path (runtime)**:
    - Head: `<dataDir>/telemetry/metrics.ndjson`
    - Worker: `<dataDir>/telemetry/metrics-worker.ndjson`
*   **Why NDJSON?** It's append-only (fast) and machine-readable.
*   **Example Output:**

    ```json
    {"t":"2026-01-02T10:00:01Z","name":"api.request_ms","type":"histogram","p50":200,"p95":400,"bounds":[10,20,50,100,200,400,800,1500,3000],"buckets":[0,0,0,1,8,2,0,0,0,0],"tags":{"route":"/api/knowledge/search","http_method":"POST","http_status":"200","http_status_class":"2xx"}}
    {"t":"2026-01-02T10:00:02Z","name":"head.jvm.threads.live","type":"gauge","value":42,"tags":{}}
    ```

#### High-signal metrics (current)

Not an exhaustive list, but the metrics below are intentionally low-cardinality and are used in perf evidence + regression diffs:

- **HTTP route latency (Head)**:
  - `api.request_ms` (histogram; tags include `route`, `http_method`, `http_status_class`)
  - `api.stream.ttft_ms` (histogram; streaming time-to-first-token where applicable)
  - `head.http.inflight_requests` (gauge)
- **JVM saturation (Head + Worker)**:
  - `head.jvm.threads.*`, `head.jvm.memory.heap.*`
  - `worker.jvm.threads.*`, `worker.jvm.memory.heap.*`
  - `jvm.uptime_ms` (always-on gauge registered by `LocalTelemetry`)
- **Worker pipeline + queue health (Worker)**:
  - `worker.job_queue.*` (depth + pending/processing/backoff counts)
  - `worker.switch_buffer.depth` (durable cutover buffer depth during `SWITCHING`)
  - `worker.switch_buffer.write_failures` (counter; incremented on `putSwitchBuffer` SQL errors)
  - `worker.index.pending_embeddings`, `worker.index.pending_vdu` (backlog gauges)
  - `worker.indexing.paced_intervals_total` (counter; indexing/backfill intervals that actually yielded to foreground load), `worker.indexing.duty_pct` (gauge, 0-100; observed share of paced wall time spent working, 100 when nothing is throttled) and `worker.indexing.foreground_in_flight` (gauge; in-flight search-family RPCs). The attribution trio for the indexing duty cycle (tempdoc 885 item 3) — the breath-hold pause they replaced was TRACE/DEBUG-only under a package the Worker pins to INFO, so no field run could count one. `worker.indexing.paused` reads 1 only for the duration of a yield.
  - `worker.job_queue.enqueue_rate_per_min` / `worker.job_queue.dequeue_rate_per_min` (gauges; rows admitted / claimed in the trailing minute, counted over sixty one-second buckets rather than decayed, so two runs of different lengths stay comparable), `worker.job_queue.lock_wait_max_ms` / `worker.job_queue.lock_wait_avg_ms` (gauges; wait to acquire the queue's single write lock, sampled on the enqueue and dequeue paths) and `worker.job_queue.outcome.total` (counter, tag `outcome_class` — the 14 `IngestionOutcomeClass` values plus `UNKNOWN`). RISK-002's instrument (tempdoc 885 item 21e): the queue previously exposed only `worker.job_queue.depth`, and depth is a level — a queue draining at 2 docs/s and one draining at 200 read identically at the same depth, so the risk's ">2x throughput regression" trigger had nothing to compare.
  - `extraction.timeout_total` (counter; content extraction timeouts)
  - `extraction.sandbox_restart_total` (counter, tag `reason` ∈ `timeout` | `crash` | `oom` | `request_budget` | `protocol` | `interrupted` | `probe_failed`; extraction child JVM recycled, or the startup probe rejected the child command)
  - `extraction.sandbox_spawn_total` (counter; extraction child JVMs started)

- **Lucene runtime substrate (Worker, `index.runtime.*` namespace, tempdoc 406)**:
  Emitted by `WorkerLuceneTelemetryAdapter` (`modules/worker-services/.../services/WorkerLuceneTelemetryAdapter.java`) which bridges the `LuceneRuntimeTypes.TelemetryEvents` interface into `IndexRuntimeMetricCatalog`'s typed instruments. All names share the `index.runtime.*` prefix; the `reason` tag is declared on the catalog `MetricDefinition.tagKeys(...)` and is a bounded enum (~5 commit reasons, ~5 swap reasons, ~6 validation reasons).

  | Metric | Type | Tags | When fires |
  | :--- | :--- | :--- | :--- |
  | `index.runtime.commit_ms` | Histogram | `reason` (commit trigger: `drain` / `timer` / `indexing-loop/time` / `grpc/deleteByPath` / `unknown`) | Every `CommitOps.commit` — i.e. every IndexWriter commit |
  | `index.runtime.swap_duration_ms` | Histogram | `reason` (swap trigger: `admin_triggered` / `config_reload` / `blue_green_cutover` / `deferred_upgrade` / `unknown`) | Per `RunningRuntime.drainAndClose` — measured end-to-end across drain + commit + close |
  | `index.runtime.write_barrier_wait_us` | Histogram | none | **Every** `IndexingCoordinator` readLock acquire (high-volume; 50K+ samples per minute under ingest). Spikes only when the writeBarrier is held — i.e. during a swap |
  | `index.runtime.hard_delete_total` | Counter | none | Per hard-delete (`onHardDelete(count)` adds the count) |
  | `index.runtime.soft_delete_total` | Counter | none | Per soft-delete (count > 0) |
  | `index.runtime.backpressure_total` | Counter | none | When the IndexingLoop applies backpressure |
  | `index.runtime.validation_failure_total` | Counter | `reason` (bounded enum, ~6 values) | Per document validation failure |
  | `index.runtime.vector_dropped_total` | Counter | none | Per dense-vector FIELD dropped because the value could not be normalized (zero-magnitude or non-finite embedding, tempdoc 931 §C.3). The document is still written, minus that field, with its embedding status set to `FAILED` — so this is an encoder-health signal, not an indexing-failure one |
  | `index.runtime.chunk_revision_mismatch_total` | Counter | none | Per chunk READ whose text could not be reconstructed because the parent is no longer at the revision the chunk's offsets address (tempdoc 931 §E item 5). The read returns no text for that chunk — empty excerpt, omitted RAG passage — rather than a slice of the newer revision, so this counts reads landing inside the parent-rewrite / chunk-regeneration window; a sustained non-zero trend means regeneration is not keeping up with parent rewrites |
  | `index.runtime.swap_started_total` | Counter | `reason` (same enum as `swap_duration_ms`) | At the start of every `drainAndClose` — pairs with `swap_duration_ms` |
  | `index.runtime.drain_timeout_total` | Counter | none | When `drainAndClose` writeLock acquire times out before in-flight writes complete (rare; signals the drain timeout was too tight or the queue too deep) |

  The `lucene_runtime_telemetry` jseval projection (`scripts/jseval/jseval/projections/lucene_runtime_telemetry.py`) aggregates these into per-(name, tag) summaries and a top-level signal block (`swap_count_total`, `drain_timeout_total`, `commit_ms_p99_max`, `write_barrier_wait_us_p95_max`) consumed by `compare-runs` and the nightly eval pipeline.

- **Embedding service runtime (Worker, `embedding.runtime.*` namespace, tempdoc 413)**:
  Emitted by `EmbeddingService` (`modules/worker-core/.../embed/EmbeddingService.java`) and `IndexingLoop.unloadEmbeddingService` (`modules/worker-services/.../loop/IndexingLoop.java:1541`) through the zero-dep `EmbeddingTelemetryEvents` seam, implemented by `EmbeddingTelemetry` over `EmbeddingMetricCatalog` (`modules/worker-services/.../embed/`). Covers what `EmbeddingService` and its lifecycle wrappers actually own — per-call BackendException failures, query-cache hit/miss/size, hot-unload events, and the chunked-embedding branch. Cold-load lifecycle (assemble, GPU↔CPU fallback) is out of scope and belongs to tempdoc 414's `ort.session.*` namespace.

  | Metric | Type | Tags | When fires |
  | :--- | :--- | :--- | :--- |
  | `embedding.runtime.invoke_failure_total` | Counter | `operation` (`SINGLE` / `BATCH`), `reason` (`BACKEND_EXCEPTION` / `CLOSED` / `NULL_TEXT`) | `embedWithChunks` and `embedDocumentBatch` BackendException catches; defensive guards for closed-service and null/blank input |
  | `embedding.runtime.cache_hit_total` | Counter | none | Cache-hit branch in `embedWithChunks` (5s TTL query cache) |
  | `embedding.runtime.cache_miss_total` | Counter | none | Cache-miss branch in `embedWithChunks` (just before backend inference) |
  | `embedding.runtime.cache_size` | Gauge | none | Async callback over `embeddingCache.size()`; reads through a deferred supplier so it tolerates `embeddingService==null` at boot |
  | `embedding.runtime.unload_total` | Counter | `reason` (`GPU_HANDOFF` / `SHUTDOWN`) | `IndexingLoop.unloadEmbeddingService` (hybrid-inference VRAM handoff) and `KnowledgeServer.close` (worker shutdown). The SHUTDOWN emit calls `LocalTelemetry.flush()` immediately to bypass the close-time NDJSON write race. |
  | `embedding.runtime.chunk_count` | Histogram | none | One sample per chunked text on either path (`embedWithChunks` or `embedDocumentBatch` per-result loop) when the backend returned chunked vectors (text exceeded the model's context window). Bucket layout `[2, 4, 8, 16, 32, 64, 128]`; carries the actual chunk count rather than just an event flag. |

  Tag values are sealed Java enums (`Operation`, `InvokeFailureReason`, `UnloadReason`) declared on `EmbeddingTelemetryEvents`; cardinality is finite at compile time. The catalog is registered with the worker `LocalTelemetry` at `KnowledgeServer.java:243` alongside the other worker-side catalogs. `cache_size`, `cache_hit_total`, `cache_miss_total`, `unload_total`, and `invoke_failure_total` declare `archivedTo(RrdArchive.STANDARD)` for trend-over-time analysis via `RrdMetricStore`.

- **AI orchestration outcomes (Head/app-services)**:
  - `rag.retrieval_total` (counter; tags include `mode=rag|fallback|error`)
  - `vdu.outcome_total` (counter; tags include `outcome=completed|empty|failed|skipped`)
  - `vdu.timeout_total` (counter; incremented when VDU LLM operations exceed timeout)
  - `vdu.pass1.duration_ms` (timer; Pass 1 vision extraction latency; tags: `component=vdu`)
  - `vdu.pass2.duration_ms` (timer; Pass 2 enrichment latency; tags: `component=vdu`)
  - `vdu.total.duration_ms` (timer; total VDU pipeline latency; tags: `component=vdu`)

- **Inference runtime substrate (Head, `inference.*` namespace, tempdoc 412)**:
  Emitted by `InferenceTelemetryAdapter` (`modules/app-services/.../inference/InferenceTelemetryAdapter.java`)
  which bridges the `InferenceTelemetryEvents` interface (in `app-inference`, no telemetry
  dep) into `InferenceMetricCatalog`'s typed instruments. All names share the `inference.*`
  prefix; tag enums are bounded — `TransitionReason`, `StartupReason`, `RequestKind`,
  `RequestOutcome`, plus the four typed failure-code enums (`StartupCode`, `HealthCode`,
  `ConfigCode`, `TransitionCode`) whose `wireValue()` is the canonical metric tag value.

  | Metric | Type | Tags | Source code path |
  | :--- | :--- | :--- | :--- |
  | `inference.transition.total` | Counter | `from_phase`, `to_phase`, `reason` (TransitionReason) | `InferenceLifecycleManager` — fires once per logical mode change (the intermediate `TRANSITIONING` half is suppressed by the holder) |
  | `inference.transition.duration_ms` | Histogram | `from_phase`, `to_phase`, `reason` | Same; elapsed measured wall-clock from each transition method's entry. `Exemplars.TRACE_BASED` carries the admin request's trace id when present. |
  | `inference.startup.attempt_total` | Counter | `phase`, `reason` (StartupReason) | `switchToOnlineMode` / `switchToIndexingMode` entry |
  | `inference.startup.duration_ms` | Histogram | `phase` | `switchToOnlineMode` / `switchToIndexingMode` success path |
  | `inference.startup.failure_total` | Counter | `phase`, `code` (StartupCode wireValue) | Each catch of a startup-side `ModeTransitionException` (mapped via `mapFailure`) |
  | `inference.config.apply_total` | Counter | `restart_required` (boolean) | `applyConfig` entry |
  | `inference.config.apply_failure_total` | Counter | `code` (ConfigCode + TransitionCode) | Each catch of an `applyConfig`-side `ModeTransitionException` |
  | `inference.health.failure_total` | Counter | `code` (HealthCode), `severity` (`single` / `restart_triggered`) | `LlamaServerOps.handlePeriodicHealthFailure` |
  | `inference.health.recovered_total` | Counter | none | `LlamaServerOps` periodic-probe success path after ≥1 failure |
  | `inference.request.queue_wait_ms` | Histogram | `kind` (`chat`/`stream`/`vision`/`summary`/`vdu`) | `OnlineModeOps` — measured between request enqueue and lock acquisition for all four lock-acquiring methods |
  | `inference.request.duration_ms` | Histogram | `kind`, `outcome` (`ok`/`error`/`cancelled`/`timeout`) | `OnlineModeOps` — end-to-end per request (streaming methods fire from wrapped onComplete/onError callbacks) |

  Status surface: `/api/status` returns an `inference` field (`InferenceRuntimeView`)
  carrying the current phase, identity (generationId/modelId/port/loadedAtEpochMs),
  `usingExternal` flag, last typed failure (if any), and lifecycle counters. Replaces
  the prior `LlmStatusView` + `OnlineAiView` pair (Phase 0 finding: the `EngineMonitor`
  that was supposed to populate `LlmStatusView`'s queue/active-slots/tokens-per-second
  fields was dead code; setters never called in production).

  *Queue / generation sub-records on the status view were dropped in the tempdoc 412
  follow-up* — no scraper consumes the llama-server Prometheus `/metrics` endpoint yet
  (the `--metrics` flag is enabled on the launch). When a `LlamaServerMetricsScraper`
  is wired in a future tempdoc, the queue/generation fields return alongside matching
  `inference.queue.*` / `inference.generation.*` metric definitions.

  Admin trigger: `POST /api/admin/inference/reload` (operator-only; loopback-bound) calls
  `OnlineAiRuntimeControl.reloadRuntime()` which delegates to
  `applyRuntimeOverrides(null, null, null, RESTART_IF_ONLINE)` — a no-op when the runtime
  is offline; otherwise restarts with the current config. Returns
  `{transitionDurationMs, phase, generationId, reason}`. Mirrors the 406 admin index
  reload endpoint.

  ArchUnit contract: `InferenceObservabilityArchTest` enforces (a) no class outside
  `io.justsearch.app.services.inference..` (plus `BootstrapInferenceFactory`) depends
  on `InferenceMetricCatalog` directly, and (b) `app-inference` does not depend on
  `io.justsearch.telemetry.catalog..`. Future commits that try to bypass the
  events-interface idiom fail CI.

  Holder rewrite remains deferred: `InferenceLifecycleManager` retains its current
  internal structure (Mode enum, ModeStateMachine, listeners, periodic health, crash
  recovery, external-server adoption). The dead-code phase-typed-runtime scaffold from
  the original tempdoc 412 was deleted in the follow-up; observability and admin-endpoint
  goals are achievable on the existing internals via events emission + snapshot API. The
  holder rewrite is a focused follow-up tempdoc with a smaller blast radius.

- **IPC metrics (Head `ipc.*` namespace)**:
  These metrics track Worker process lifecycle and gRPC communication health:

  | Metric | Type | Description |
  | :--- | :--- | :--- |
  | `ipc.port_discovery_ms` | Timer | Port discovery latency |
  | `ipc.port_discovery.timeout` | Counter | Port discovery timeouts |
  | `ipc.worker.restart` | Counter | Worker restarts (tags: `outcome=success\|failed`) |
  | `ipc.worker.restart_limit_exceeded` | Counter | Restart limit reached |
  | `ipc.worker.pid_mismatch` | Counter | PID validation mismatches |
  | `ipc.worker.stability_reset` | Counter | Restart counter resets after stable operation |
  | `ipc.shutdown.timeout` | Counter | Shutdown timeouts |
  | `ipc.shutdown.forcible_kill` | Counter | Forcible process kills |
  | `ipc.grpc.reconnect` | Counter | gRPC reconnections |
  | `ipc.circuit_breaker.state_change` | Counter | Circuit breaker state transitions (tags: `from`, `to`) |
  | `ipc.circuit_breaker.rejected` | Counter | Requests rejected by open circuit |
  | `ipc.status.poll_ms` | Timer | Status polling latency |
  | `ipc.status.response_bytes` | Histogram | Status response size |

#### Worker-side OperationalMetrics (dual-system rationale)

The Worker maintains a separate `OperationalMetrics` LongAdder-based singleton alongside OpenTelemetry. This is architecturally intentional: OTel counters are write-only by design (no `get()` or `value()` method), but the gRPC status response path needs readable counter values. `ObservableLongCounter` callbacks bridge the two — LongAdder fields are the source of truth for gRPC reads, and registered OTel callbacks pull from those same fields during each periodic flush (5s) for NDJSON export.

Key OperationalMetrics fields exposed via gRPC → `/api/status`:
- Counters: `documentsIndexed`, `searchesTotal`, `searchesZeroResultTotal`, `searchesFailedTotal`, `batchesSubmitted`, `batchesRejected`
- Maps: `failedByFileKind` (per-MIME-type failure counts, ~10 buckets: pdf, office, code, text, etc.)
- Gauges: `queueDepth`, `lastSearchLatencyMs`, `lastIndexLatencyMs`
- Enrichment doc counts (354): map-based `enrichmentCompleted`, keyed by stage name (embed, splade, ner). Cumulative docs processed per enrichment stage.
- Batch timing (354): map-based `batchTiming` with `batchCount` and `totalMs` sub-maps, keyed by phase name (embed, splade, ner, fetch, write, total). Per-stage timing (embed/splade/ner) only accumulates when the stage processed > 0 docs, so `totalMs / batchCount` gives meaningful per-batch averages. Whole-batch phases (fetch/write/total) accumulate unconditionally.

#### GPU utilization metrics

When NVML is available (NVIDIA GPUs on Windows), the Head process polls `nvmlDeviceGetUtilizationRates()` via FFM and registers OTel gauges with 5-second caching:

- `gpu.utilization.percent` — GPU core utilization (0-100%)
- `gpu.memory.utilization.percent` — VRAM utilization (0-100%)

These are stored in the RRD time-series (see §Time-Series Storage below) for trend visualization.

#### ORT session lifecycle metrics

The Worker hosts six `NativeSessionHandle` instances (one per encoder: `embed`, `splade`, `ner`, `reranker`, `citation`, `bgem3`). Each handle's lifecycle transitions emit through `OrtSessionTelemetryEvents` (a dep-free interface in `modules/ort-common`) to `OrtSessionTelemetryAdapter` (in `modules/worker-services`), which routes events into `OrtSessionMetricCatalog`. The metric set is derived from the sealed `TransitionReason` permits — adding a transition reason fails the compile until every consumer (the adapter's exhaustive switch) handles it.

| Metric | Type | Tags | Source event |
|---|---|---|---|
| `ort.session.semaphore_wait_us` | Histogram (microseconds) | `consumer` | per-GPU-acquire semaphore wait (GPU path only — CPU acquires don't take the semaphore) |
| `ort.session.gpu_init_total` | Counter | `consumer`, `outcome` (`success`/`failure`) | `TransitionReason.GpuInitialized` / `GpuInitFailed` |
| `ort.session.gpu_init_failure_total` | Counter | `consumer`, `cause` (`oom`/`cuda_unavailable`/`driver_error`/`unknown`) | `TransitionReason.GpuInitFailed` (typed `FailureCause` — paired with `gpu_init_total{outcome=failure}`) |
| `ort.session.fallback_total` | Counter | `consumer` (implicit `from=cuda,to=cpu`) | `TransitionReason.GpuFallbackTaken` — the silent line-260 case made first-class |
| `ort.session.recovery_total` | Counter | `consumer`, `cause` (`bfc_arena_failure`/`reported_failure`/`unknown`) | `TransitionReason.CpuSessionRecreated` (typed `CpuRecreateCause` plumbed through `SessionHandle.reportCpuSessionFailure(cause)`; F-009 recovery) |
| `ort.session.release_total` | Counter | `consumer`, `outcome` (`success`/`failure`) | `TransitionReason.GpuReleaseCompleted` / `GpuReleaseFailed` |
| `ort.session.retry_total` | Counter | `consumer` | `TransitionReason.GpuRetryAttempted` (60-second retry interval) |
| `ort.session.retry_interval_ms` | Histogram | `consumer` | `TransitionReason.GpuRetryAttempted.sinceFailureMs` — distribution of "time-to-retry" gives operators a tuning signal for the retry interval |
| `ort.session.assembler_failure_total` | Counter | `consumer`, `kind` (`null_variant`/etc.) | `AssemblerEvent.Failed` — separate `onAssemblerEvent` channel (assembler-time events aren't handle transitions). Fires before any NPE that would propagate from `OrtSessionAssembler.buildManager` |

**Cardinality.** ~480 series total across all 9 metrics (6 consumers × ≤3 tag values per metric). Well within OTel's 2000-series default cap.

**Type-safety contract.** Per ADR-0027, every metric carries a typed `TagSchema` record. Cause-tagged metrics are type-discriminated: `GpuInitFailureTags(consumer, FailureCause)` and `RecoveryTags(consumer, CpuRecreateCause)` are distinct types — passing the wrong cause enum to the wrong metric fails at javac. Permit drift on `TransitionReason` or `AssemblerEvent` fails the compile via the adapter's exhaustive `switch`.

**Status surface.** Per-encoder current state remains on `/api/status` via `OrtCudaView` (`attempted`, `available`, `configured`, `failureReason`, `missingDlls`, `nativePath`, `variantId`). The metrics above complement that point-in-time snapshot with aggregable transition history.

**`assembler_failure_total{kind=null_variant}` framing.** Today this metric only fires from the policy-covered stress lane (`NativeSessionHandleConcurrentStressTest.stressTenThreads`) — `ModelSessionPolicy.forFallback(...)` is the only producer of `variant=null` and has zero production callsites (verified via grep across all main source sets). The metric is an invariant guard for that test path; production never exercises it.

**Consumer name source of truth.** The `consumer` tag values come from `EncoderRole.consumerName()` (in `modules/ort-common`). `OrtSessionTelemetryAdapter.forAllRoles(catalog)` derives the cache from `EncoderRole.values()`, so adding a new encoder role to the enum automatically extends the adapter cache — no parallel hardcoded list to drift.

Pattern reference: ADR-0027 (telemetry catalog substrate), tempdoc 414. Adapter precedent: `WorkerLuceneTelemetryAdapter` for `index.runtime.*`.

### 2. Distributed Tracing (TraceId)
A single user action (e.g., "Search for 'Invoice'") traverses multiple boundaries:
1.  **Frontend:** User clicks button.
2.  **API:** `LocalApiServer` receives request.
3.  **AppFacade:** Business logic.
4.  **IPC:** gRPC call to Worker (metadata: `x-trace-id`).
5.  **Worker:** Lucene query.

We use a `TraceId` to link these disconnected events together in the logs.

#### Worker Indexing Spans (OTel)

The indexing pipeline emits OTel spans from a `TracingBootstrap` initialized in `KnowledgeServer.start()` before service construction, controlled by `JUSTSEARCH_INDEX_TRACING_LEVEL`:

| Level | Behavior |
|-------|----------|
| `none` (default) | No spans, no `TracingBootstrap`. Zero overhead — `Span.getInvalid()` is a singleton no-op. |
| `sample` | 1% ratio sampling via `Sampler.traceIdRatioBased(0.01)`. Safe for bulk indexing. |
| `detailed` | 100% spans. For investigation sessions (1K–5K docs). High volume at scale. |

Span tree per indexing batch:

```text
indexing.batch  {batch.polled=16, batch.extracted=16,       [~90ms deferred, ~1650ms inline]
                 commit.schema_fp=..., commit.field_catalog_hash=...,
                 commit.synonyms_hash=..., commit.grammar_hash=...,
                 commit.similarity_fp=..., commit.boosts_fp=...,
                 commit.index_fingerprint=...}
├── indexing.extract  {doc=report.pdf}                       [2.5ms]
├── indexing.embed_batch {size=16, gpu=true}                 [726ms, only during migration]
├── indexing.write {doc=report.pdf}                          [2.5ms]
└── indexing.markDone {count=16}                             [0.5ms]
```

Span tree per enrichment backfill batch (tempdoc 400 LR2-a/b):

```text
enrichment.batch                                         [root per backfill pass]
├── encoder.ort_run  {encoder.name=embed|splade|ner|bgem3,  [per ORT session.run()]
│                     encoder.gpu=true|false,
│                     encoder.batch_size=N, encoder.seq_len=N}
│   │
│   │ event: cpu_fallback.triggered  {fallback.cause=..., fallback.encoder=...}
│   │   └── fires on GPU→CPU fallback (BFC OOM, native session failure)
│   │
└── lease.acquire  {lease.mode=gpu|cpu, lease.wait_queue_depth=N}
    └── wraps the gpuInferenceSemaphore wait inside NativeSessionHandle.acquire()
```

Note: `lease.acquire` is a **child** of `encoder.ort_run` in `OnnxEmbeddingEncoder` + `BgeM3Encoder`; it is a **trace-id-correlated sibling** (not a strict child) in `BertNerInference` + `SpladeEncoder` pinned path because hoisting the span-start past nested try-with-resources was prohibitively invasive. Layer 4 projections should join by `trace_id` rather than walking parent pointers; see tempdoc 400 §22.2 Issue B for the spec softening rationale.

Span tree per search query (tempdoc 400 LR2-e, updated Phase 5/6):

```text
search/retrieval  {search.mode=TEXT|HYBRID|VECTOR|SPLADE,    [parent; always emitted]
                   search.took_ms=N,
                   search.searcher_generation=g-<ts>,
                   commit.schema_fp=..., commit.field_catalog_hash=...,
                   commit.synonyms_hash=..., commit.grammar_hash=...,
                   commit.similarity_fp=..., commit.boosts_fp=...,
                   commit.index_fingerprint=...}
├── search/branch  {search.retrieval.branch=lexical}         [3-leg fan-out only]
├── search/branch  {search.retrieval.branch=dense}           [when sparse+dense+splade
├── search/branch  {search.retrieval.branch=splade}           all available]
├── search/fuse    {search.fusion.algorithm=cc|rrf,           [every HybridFusionUtils
│                   search.fusion.branch_count=N}              call site — Phase 6/6.8
│                                                              all-site coverage:
│                                                              primary 3-branch +
│                                                              whole×chunk branch-merge +
│                                                              chunk 3-way + nested-RRF]
└── search/rerank  {search.ce.scored=N}                       [inside
                                                               CrossEncoderReranker.rerank,
                                                               covers every caller:
                                                               chunk + search + document]
```

Per-leg `search/branch` spans emit only when all three retrieval legs are
available (`canSparse && canDense && canSplade`). Virtual-thread context
propagation requires `Context.current().with(retrievalSpan)` at capture
(not bare `Context.current()`) — see
`SearchOrchestratorVirtualThreadContextRegressionTest` for the regression
guard and tempdoc 400 §23.2 for the defect history.

`search/rerank` was moved into `CrossEncoderReranker.rerank` itself in
Phase 6 / 6.8 so every caller (chunk rerank + primary search + document
pipeline) emits uniform spans; previously only `RagContextOps.chunkRerank`
was instrumented. `search/fuse` was similarly extended to wrap every
`HybridFusionUtils` call site (primary 3-branch fusion, branch-merge
whole×chunk fusion, chunk 3-way, nested-RRF multi-leg) — each span's
`search.fusion.algorithm` + `search.fusion.branch_count` attrs let
Layer-4 projections filter by call-site shape.

**`search.searcher_generation`** is the Lucene IndexSearcher generation
id at search time, sourced from `IndexGenerationManager.readStateBestEffort()
.active_generation()` via `SearchOrchestrator.setActiveGenerationSupplier`
(wired by `DefaultWorkerAppServices` in Phase 6 / 6.7). Lets Layer-4
projections detect cross-commit drift: two runs with the same
`commit.*` identity but different `searcher_generation` saw different
Lucene readers even within the same commit (typically due to background
merge). Format: `g-<ISO-ms-timestamp>` matching the id surfaced on
`/api/debug/state` as `serving_search_generation_id`.

**Identity attrs on parent spans** — all `commit.*` fields are sourced from the
Worker's cached commit-metadata snapshot (`KnowledgeServer`'s startup read of
`client().getCommitMetadata()`) and govern runtime index identity. A missing attr
means the batch fired before the first commit was visible (expected on fresh
`--clean` ingest; self-corrects after the first commit). The 7 fields (`schema_fp`,
`field_catalog_hash`, `synonyms_hash`, `grammar_hash`, `similarity_fp`,
`boosts_fp`, `index_fingerprint`) replace the retired `pipeline_hash` / `budget_profile`
slot (ADR 0014). Tempdoc 915 folded the old `schema_ver` / `index_schema_fp` /
`analyzer_fp` parity keys into the single `index_fingerprint`; only `index_fingerprint`
and `boosts_fp` are actually compared for parity (`docs/explanation/11-index-schema-migration.md`)
— the other five are observability-only.

Spans are exported via `NdjsonSpanExporter` to `<dataDir>/telemetry/traces.ndjson` (10 MB rotation, 7-day retention). `BatchSpanProcessor` defaults: 2048 queue, 512 batch, 5s interval. Only attrs listed in `NdjsonSpanExporter.ALLOWED_ATTRS` survive export — unlisted attrs are silently dropped.

**Structural span fields** (always present on every emitted span, outside
the `attrs` object): `trace_id`, `span_id`, `parent_span_id`, `name`,
`start`, `end` (ISO-8601 millisecond-precision), `status`, and
`duration_ms` (double, nanosecond-sourced). The `duration_ms` field was
added by tempdoc 400 §23.8 D-1 specifically so span consumers (notably
the `encoder_latency` summary block) can read span durations without
re-parsing ISO timestamps — start/end are ms-truncated at export and
lossy for sub-ms encoder calls. Consumers should prefer `duration_ms` and fall
back to `(end − start)` only for legacy `traces.ndjson` files produced
before D-1 landed.

**One SDK per JVM (lane F stage A).** `GlobalOpenTelemetry` can be registered once, and since
item A6 the Head and the index half are one JVM, so the two levels above are no longer independent.
The Head's bootstrap runs first (its API phase precedes `KnowledgeServer.start()`), so
`JUSTSEARCH_HEAD_TRACING_LEVEL` governs both halves; `JUSTSEARCH_INDEX_TRACING_LEVEL` takes effect
only when the head level is `none`. `KnowledgeServer` logs the skip at INFO naming the
consequence rather than swallowing it. Collapsing the two keys into one belongs with stage B's
re-cut of the worker projection, not to stage A.

Application-level gating (`maybeSpan()` returning `Span.getInvalid()`) provides true zero-cost when off. The OTel sampler acts as a safety net, not the primary gate. Validated overhead: sub-10µs per batch (tempdoc 312 item 7). End-to-end verification (tempdoc 400 §23) measured no indexing throughput regression (22.5 → 23.2 d/s across 3 runs) with detailed tracing enabled.

#### Local trace viewer (`otel-desktop-viewer`)

Tempdoc 518 Appendix G W4.2 activated head-side tracing (via
`JUSTSEARCH_HEAD_TRACING_LEVEL`); cross-process tracing was already
wired through a client/server interceptor pair. Lane F stage A item A9
deleted the server half with the gRPC server: inside one JVM the caller's
OTel context is already current on the callee's thread, so there is
nothing to extract.
Combined with the existing OTLP fan-out support in
`TracingBootstrap.buildOptionalOtlpExporter` (reads
`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` or `OTEL_EXPORTER_OTLP_ENDPOINT`),
this gives a complete in-process trace pipeline. The recommended dev
viewer is **[`otel-desktop-viewer`](https://github.com/CtrlSpice/otel-desktop-viewer)**
— a single binary that listens on OTLP and opens a browser tab with a
Jaeger-quality waterfall UI. No backend storage; ephemeral
in-memory. Ideal for "do a thing, see the trace tree" debugging.

**Setup (Windows):**

1. Download the latest `otel-desktop-viewer_windows_amd64.zip` from
   the project's GitHub releases page; extract anywhere on PATH.
2. Run `otel-desktop-viewer` from a terminal. By default it listens
   on `localhost:4317` (gRPC) and `localhost:4318` (HTTP), and opens
   a browser tab at `localhost:8000`.
3. Start the JustSearch dev stack with two env vars:
   - `JUSTSEARCH_HEAD_TRACING_LEVEL=detailed` (activates the head's
     `TracingBootstrap` via `HeadlessApp.setupInfra`).
   - `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces`
     (routes the BatchSpanProcessor fan-out to the viewer).
4. Do a thing in the UI (search, chat, mode switch). Spans appear in
   the viewer's browser tab. Head and index spans are one tree by
   construction now rather than by stitching: item A9 deleted the
   TraceContext interceptor pair, because the caller's context is
   already current on the callee's thread inside one JVM. The one
   place that is not automatic is a thread hand-off — a per-source
   federated search fans out onto virtual threads, so
   `SearchPerSourceExecutor` wraps its executor with
   `Context.taskWrapping`; without that the sub-queries would each
   start a new trace.

**Why the viewer is ephemeral**: the canonical persistent store is
still `<dataDir>/telemetry/traces.ndjson` (rotated locally). The
desktop viewer is an additive visibility layer for the live dev loop
— production environments use OTLP to a real collector
(Jaeger/Tempo/Honeycomb).

#### Contract Tiers (tempdoc 400 LR6-a/b)

Two annotation classes in `io.justsearch.contracts` (module
`modules/core-contracts`, dep-free leaf per tempdoc 400 §22 Issue A
Phase 5 refactor; previously `io.justsearch.ipc.contracts` when they
lived in `ipc-common` and transitively pulled in gRPC) codify
invariants that must survive refactors:

- **`@BuildContract(description, tempdoc, enforcer)`** — invariants that an ArchUnit rule or other compile-time/CI check actively enforces. Violations fail the build. Use for invariants that can be expressed as a static-analysis rule.
- **`@AdvisoryContract(description, tempdoc, signal)`** — invariants surfaced via a runtime signal (log threshold, metric gauge, span event). Violations are observable but do not fail the build. Use for invariants that require runtime context.

A third tier, `contract.violation` span event (consumed by `scripts/jseval/jseval/projections/contract_violations.py`), aggregates runtime violations by `{contract.tempdoc, contract.tier}` for drift detection. The event emitter (`@SampleContract(every=N)` + `@BootContract`) is deferred from Phase 1 per LR6-a scope reduction; the projection returns empty aggregates until those tiers ship.

#### Run Manifest + Non-Determinism Envelope (tempdoc 400 LR1-a + LR1-b + LR1-c)

Each `jseval run` writes `<runDir>/manifest.json` aggregating a
per-cohort identity tuple + informational runtime snapshots + the
calibrated non-determinism envelope for this cohort (if one exists).

**Cohort-identity fields (enter `manifest_hash`):**

- `git_sha` (full SHA at run start)
- `dataset`, `doc_count`, `query_count`
- `commit_metadata` — 7 identity fields filtered from
  `/api/debug/commit-metadata` (`schema_fp`, `field_catalog_hash`,
  `synonyms_hash`, `grammar_hash`, `similarity_fp`,
  `boosts_fp`, `index_fingerprint`). Mirrors `NdjsonSpanExporter`'s `commit.*` span
  attrs — same set, single source of truth. Tempdoc 915 folded the retired
  `schema_ver` / `index_schema_fp` / `analyzer_fp` parity keys into `index_fingerprint`.
- `corpus_identity` — profile_id + signature from environment.
- `model_fingerprints` — from `/api/status` model snapshot.
- `policy_hash` — hash of `/api/debug/session-policies` response
  (now populated correctly in eval mode post-Phase 2.1).
- `eval_protocol_hash` — hash of the metric-contract dict.

**Informational-only fields (`_VOLATILE_FIELDS`, excluded from hash):**

`run_id`, `timestamp`, `workflow_run_id`, `manifest_hash` itself,
`status_snapshot`, `debug_state_snapshot`,
`inference_status_snapshot`, `env_fingerprint`, `telemetry_health_tag`.
These carry runtime state (uptime, queue depths, searcher generation,
per-commit UUIDs, captured_at timestamps) that varies per run and
would destabilize the cohort hash — preserved on the manifest
document for operator inspection but not hashed. See tempdoc 400 §24
for the Q1 finding that drove this separation.

**Cohort-hash stability** across identical reruns is guarded by
`scripts/jseval/regression/manifest_hash_stability.py` (runs 3
identical smokes and asserts matching hashes — not in PR gate
because of the ~12-min cost; invoke manually after changes to
`manifest.py` or state-endpoint shapes).

**Run-to-run variation (tempdoc 930 §18.1 row 7).** There is no
calibrated cohort envelope: the `jseval calibrate` /
`calibrate-drift-baseline` machinery (LR1-b + LR4-g), the
`cohort_baselines/` facet registry, and the `encoder_drift` PSI
projection were removed after producing no artefact on any machine in
their lifetime — the cohort hash changes with any input, so a baseline
of N warm runs went stale faster than it could be captured. Variation
is now reported *per run* instead of calibrated *per cohort*: each
`summary.json` carries `per_mode.<mode>.latency_stats` (p50/p95/p99/max
over that run's queries) and an `encoder_latency.encoders.<name>` block
with absolute p50/p95 ms per ONNX encoder, read from the same
`encoder.ort_run` spans the PSI projection consumed. A silently slower
encoder path shows up as a moved absolute number plus the product's own
`cpu_fallback.triggered` event; nothing thresholds or gates on it.

**Layer-4 projections (Phase 3).** Every `jseval run` now invokes
every registered projection against the run artifacts via
`jseval.projections.run_all_discovered`. Outputs land under
`<runDir>/projections/<name>.json`:

| Name | Commit | Purpose |
|---|---|---|
| `contract_violations` | LR6-c | Aggregate `contract.violation` events by tempdoc + tier (Phase 1) |
| `bootstrap_ci` | LR4-b | 95% CI per mode × metric (1000-resample bootstrap) |
| `rate_timeline` | LR4-d | Per-tick rate for 4 worker counters + stall tagging |
| `rank_diff` | LR4-e | Auto rank-diff vs latest prior in-cohort sibling run |
| `cpu_fallback_counts` | LR4-f | `cpu_fallback.triggered` aggregation by encoder + cause |
| `stratified_metrics` | LR4-c | 2-dim stratified metrics (query-length × first-relevant-rank) |

Projections are pure functions from `run_dir` to a JSON document;
failures are quarantined so a broken projection cannot abort
siblings. Add a new projection by dropping a module into
`jseval/projections/` with a module-level `PROJECTION = Projection(...)`
export + adding its filename to `_PROJECTION_MODULE_NAMES` in
`jseval/projections/__init__.py`.

**Eval-history schema (LR4-h).** Each run appends to
`<outDir>/eval-history.db`:
- `runs` table gains a `manifest_hash` column (indexed).
- `check_trend(dataset, mode, ..., manifest_hash=<hash>)` filters the
  regression window to in-cohort runs when the kwarg is supplied
  (§26.6 Decision 3).

**Projection-presence gate (manual).** `jseval run` + `jseval gate` asserts the
latest run directory has a readable `manifest.json` and the four required LR4
projection outputs. (Phase 6 / 6.13: relocated from
`scripts/ci/phase3_observability_gate.py` into the jseval package; tempdoc 930
§18.1 row 7 removed its σ(nDCG@10) band arm along with the envelope it read.)
This was previously wrapped by
`.github/workflows/phase-3-observability-nightly.yml`, a scheduled-cron
workflow; per ADR-0026 (self-hosted runner-availability policy) the cron
trigger was removed before it ever fired, and the workflow was deleted
2026-07-07 having never run automatically in its history. The gate remains
available as a manual CLI sequence; no automated wrapper currently invokes
it.

**Files:** `scripts/jseval/jseval/manifest.py` (cohort identity),
`scripts/jseval/jseval/encoder_latency.py` (per-encoder p50/p95 summary block),
`scripts/jseval/jseval/history.py` (schema + check_trend),
`scripts/jseval/jseval/projections/` (Layer-4 projections),
`scripts/jseval/jseval/gate.py` (projection-presence gate), CLI entry
`jseval gate`.

**Layer-5 experiment runners (Phase 4).** Four runner subcommands
cover cross-run causal analysis (LR5-d), concurrency behaviour
(LR5-c), policy comparison (LR5-b), and counterfactual ranking
attribution (LR5-a):

| Subcommand | Delta | Purpose |
|---|---|---|
| `jseval bench-concurrency --dataset d --concurrency N` | LR5-c | ThreadPoolExecutor-driven concurrent query benchmark; output `<run_dir>/concurrency-<N>.json` with aggregate p50/p95/p99 + per-stream timelines |
| `jseval bisect --run-a A --run-b B` | LR5-d | Single-axis manifest-hash bisection per §13.9 C2; cache-only analysis from `<output_dir>/_index/manifests.jsonl`; single-axis / multi-axis / MULTI_AXIS_INTERACTION / no-cached-runs statuses |
| `jseval shadow-eval --policy-a A.json --policy-b B.json` | LR5-b | Sequential two-policy eval on identical query set + same Worker/reader; §13.9 C3 selection-bias invariant enforced (post-run `a_qids == b_qids` assertion); top-K Jaccard + Kendall-tau per-query |
| `jseval counterfactual --dataset d` | LR5-a | Multi-pass counterfactual runner (deviation from spec's single-pass proto change — documented in `jseval/counterfactual.py`); 5 canonical modes (`lexical_only` / `dense_only` / `splade_only` / `hybrid_no_ce` / `hybrid_full`); pairwise Jaccard divergence matrix |

The manifest index `<output_dir>/_index/manifests.jsonl` is
populated by `jseval run` at end-of-run so `jseval bisect` can
look up runs by cohort hash later. Entries are idempotent on
`(manifest_hash, run_dir)` pairs.

**Files:** `scripts/jseval/jseval/bench_concurrency.py` (LR5-c
driver), `scripts/jseval/jseval/bisection.py` (LR5-d analyzer + run
index), `scripts/jseval/jseval/shadow_eval.py` (LR5-b runner),
`scripts/jseval/jseval/counterfactual.py` (LR5-a multi-pass runner).

### 3. Workflow-Run Telemetry

> **Removed (tempdoc 638):** the run-centric workflow-telemetry subsystem (`scripts/lib/workflow-telemetry.mjs` + the bench/*workflow* scripts + the `tmp/workflow-telemetry/runs/` evidence store) was deleted. The `docs/reference/contracts/workflow-telemetry-contract.v1.md` contract is retained only as historical design context. Runtime metrics, traces, and agent telemetry (below) are unaffected.

Optional external overlay (runtime traces):

- Java runtime traces can fan out through OTLP to self-hosted Opik
- this is an additive visibility layer, not a replacement for local `traces.ndjson`

### 4. Agent Telemetry

The agent loop (`AgentLoopService`) emits via the typed `AgentTelemetry` façade over `AgentMetricCatalog`:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `agent.error.total` | counter | `error_code`, `error_class` | Every agent error emission (TRANSIENT/PERMANENT/SAFETY) |
| `agent.retry.total` | counter | `error_code`, `attempt` | Each retry attempt (LLM transient, empty response, tool transient) |
| `agent.retry.exhausted.total` | counter | `error_code` | Retry budget exhausted without recovery |
| `agent.loop.blocked.total` | counter | *(none)* | Consecutive identical tool call blocked by loop guard |
| `agent.budget_edge_finalize.total` | counter | `success` | Budget-edge graceful finalize attempt (true/false) |
| `agent.session.start_total` | counter | *(none)* | One emit per session creation (tempdoc 415) |
| `agent.session.duration_ms` | histogram | *(none)* | One sample per session end (tempdoc 415) |
| `agent.session.terminate_total` | counter | `disposition`, `error_code`?, `cancel_trigger`? | One emit per session end; cross-product tag schema (tempdoc 415) |
| `agent.session.context_size_bytes_at_end` | histogram | *(none)* | UTF-8 byte sum of message JSON at session end (tempdoc 415) |
| `agent.session.iterations_at_end` | histogram | *(none)* | Iterations consumed at session end (tempdoc 415) |
| `agent.session.tool_calls_at_end` | histogram | *(none)* | Successful tool calls at session end (tempdoc 415) |
| `agent.session.tool_call_total` | counter | `tool_name` | Per executed tool call (post-approval-gate, pre-execute) — tempdoc 415 |
| `agent.session.tool_failure_total` | counter | `tool_name` | Executed tool call returned `!success()` after policy retries (tempdoc 415) |
| `agent.session.active_count` | gauge | *(none)* | Currently-running sessions; surfaced on `/api/status` as `agentSessions.activeCount` (tempdoc 415) |

**Tempdoc 415 design notes:**

- `agent.session.terminate_total` uses a cross-product tag schema. `disposition` is always present (one of `COMPLETED / MAX_ITERATIONS / BUDGET_EDGE_FINALIZE / ERRORED / CANCELLED`). `error_code` is conditionally emitted only when `disposition == ERRORED`, carrying the existing `AgentErrorCode`. `cancel_trigger` is conditionally emitted only when `disposition == CANCELLED`, one of `USER / BUDGET / TOOL_LOOP`. The cross-product reuses `AgentErrorCode` rather than inventing a parallel enum.
- `agent.session.tool_call_total`'s emit point is **post-approval-gate, pre-execute** in `AgentStepRunner.executeIteration` (after `AgentToolDispatcher.handleSafetyGate` returns approved and after the loop guard's `wouldExceedLoopThreshold` check). The metric represents calls that actually entered `AgentToolDispatcher.executeOperationWithPolicy`, sharing a denominator with `tool_failure_total` so `tool_failure_total / tool_call_total` is a clean failure rate. Calls that are user-rejected at the safety gate, loop-guard-blocked, or fail tool resolution are NOT counted here (they're observable via SSE rejected events, the `agent.loop.blocked.total` counter, and `terminate_total{disposition=ERRORED, error_code=UNKNOWN_TOOL}` respectively). Handoff calls (`handoff_to_<agentId>`) branch out at `AgentHandoff.isHandoffCall(call)` before reaching this point, so `tool_name` stays bounded to the 4 registered tools.
- Termination state is captured by the loop via `AgentSession.markTerminated(disposition, errorCode, cancelTrigger)` at each terminal return site (14 sites + the max-iterations fall-through + the catch-block path). The metric family and the typed `terminationReason` field on `AgentRunStore`'s `meta.json` (schema v3) are both written from the loop's `finally{}` block (which delegates to `AgentSessionFinalizer.emitSessionEnd`; tempdoc 240 W5-epilogue), collapsing the emit sites into one.

Cardinality is bounded: `error_code` has ~13 enum values, `error_class` has ~6, `attempt` is 1-3, `success` is boolean, `disposition` has 5 values, `cancel_trigger` has 3 values, `tool_name` has 4 values.

#### Agent Trace Identity Envelope

Every agent event carries a `TraceContext` record with 7 fields, emitted across SSE payloads, persisted event logs, and MCP transcript artifacts:

| Field | Semantics | Example |
|-------|-----------|---------|
| `traceId` | Run-level ID (= session ID) | `session_abc123` |
| `stepId` | Phase identifier with iteration grouping | `iter:1:tool:call_42:completed` |
| `spanId` | Unique per-event span | `span-000042` |
| `parentSpanId` | Previous event's spanId (linear chain) | `span-000041` |
| `agentId` | Agent role identifier | `primary` |
| `toolCallId` | Tool call UUID (when applicable) | `call_42` |
| `iteration` | LLM loop iteration number | `1` |

The `EventTraceSequencer` generates a **linear sequential span chain** — each event's `parentSpanId` points to the immediately preceding event's `spanId`. This is sufficient for timeline ordering and replay but does not provide hierarchical nesting (run > iteration > tool). The `stepId` format (`iter:N:tool:CALLID:PHASE`) encodes grouping semantics that enable iteration-level queries without tree reconstruction.

#### Agent OTel Span Tree

In addition to the linear `TraceContext` chain, `AgentLoopService` emits **hierarchical OTel spans** following the [OpenTelemetry GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/). These spans are exported to `traces.ndjson` via `NdjsonSpanExporter` and are visible to any OTel-compatible trace viewer.

**Span hierarchy** (auto-parented via OTel `Scope`):

```text
invoke_agent primary                     [INTERNAL, root]
  +-- chat                               [CLIENT, per LLM call]
  +-- execute_tool search_index          [INTERNAL, per tool]
  +-- chat                               [CLIENT, next iteration]
  +-- execute_tool browse_folders        [INTERNAL]
  ...
```

**Attributes** (all registered in the `NdjsonSpanExporter` allowlist):

| Attribute | Span | Cardinality |
|-----------|------|-------------|
| `gen_ai.operation.name` | all | 3 values: `invoke_agent`, `chat`, `execute_tool` |
| `gen_ai.agent.id` | `invoke_agent` | 1 value: `primary` |
| `gen_ai.agent.name` | `invoke_agent` | 1 value: `justsearch-agent` |
| `gen_ai.conversation.id` | `invoke_agent` | 1 per session (same cardinality as `trace_id`) |
| `gen_ai.tool.name` | `execute_tool` | ~4-6 tool names |
| `gen_ai.tool.call.id` | `execute_tool` | 1 per tool call (UUID) |
| `gen_ai.usage.input_tokens` | `chat` | numeric |
| `gen_ai.usage.output_tokens` | `chat` | numeric |

**GenAI metrics** (recorded via `AgentTelemetry`, exported by `NdjsonMetricExporter`):

| Metric | Type | Tags |
|--------|------|------|
| `gen_ai.client.operation.duration` | histogram | `gen_ai.operation.name` |
| `gen_ai.client.token.usage` | histogram | `gen_ai.token.type` (`input` or `output`) |

The OTel span tree is **additive** — it coexists with the `EventTraceSequencer` linear chain. The linear chain serves SSE event ordering and persisted replay; the OTel spans provide hierarchical duration analysis.

## Real-Time Insights

### The Ring Buffer (`EventBuffer`)
We maintain a circular buffer of the last 50 significant events in memory.
*   **Endpoint:** `/api/debug/events`
*   **Usage:** The UI polling this endpoint to show the "Activity Feed" in the Health tab.
*   **Performance:** Zero allocation (reuses objects) to avoid GC pressure.

### Status as the primary health signal (`/api/status`)

The frontend (and dev tooling) treats `/api/status` as the canonical “what’s running?” signal. It is explicitly designed to avoid Head-side filesystem probing (no direct Lucene access in the Head).

#### Health sampling is internal, not request-driven

`/api/status` is a **read of a sample**, not an observation. The Worker's `IndexStatus` unary is performed by an internal sampler on the Head, and the request thread never calls the Worker.

*   **Where the sampler runs.** `KnowledgeServerHealthMonitor`'s existing schedule (a single daemon thread; no executor was added for this). Its per-tick callback drives `ReadinessReconciliationTrigger`, whose thunk is `StatusLifecycleHandler.sampleAndBuildStatusSnapshot` — the one method that performs the Worker RPC and reconciles every health tap (`LifecycleSnapshotTap`, `WorkerSnapshotTap`, `IndexDriftHealthTap`, `AtRestHealthTap`, the conversation-protection tap and the worker-metrics publisher). The trigger also fires on every worker/inference capability transition, and self-seeds one sample when the composition root attaches it.
*   **Sampling period.** 10 s while idle, 2 s while the Worker has index work in flight or the inference runtime is activating (`StatusLifecycleHandler.samplingPeriodMs()`, derived from the **last** sample so the decision costs no RPC). The monitor re-arms itself with that value, clamped to `[1 s, pollIntervalMs]` — the health poll's own configured interval is the ceiling. Resume detection still measures its inter-tick gap against the configured interval, not the actual delay, so a faster tick can only make it more conservative.
*   **The fast arm engages one period late, and not at all for a short ingest.** "In progress" is derived from the LAST sample, so the sampler must already have observed in-flight work before it can shorten its period — and an ingest that starts and drains inside one idle period is never observed at all. Measured: an 822-file ingest produced 17 consecutive ~2 s intervals; a 30-file ingest produced none, because it had drained before the next 10 s tick. This is the design's cost, not a defect — deciding to sample faster requires a sample — and it is bounded: the work a 2 s cadence exists for (a long backfill, a large scan) is exactly the work that outlives a period.
*   **What a request does.** `GET /api/status` builds the response from the cached sample and runs **no** taps. The one exception is the boot window: if no sample has ever been taken, the first request takes one synchronously. That can happen at most once per process.
*   **Freshness semantics.** `meta.workerRpcAtMs` is the sample's observation time (stamped immediately before the call), so a consumer's age is `now - workerRpcAtMs`. `meta.workerRpcStale` is `true` when the last sample failed **or** when it is older than three sampling periods — a wedged sampler surfaces as "contact lost" rather than as a frozen snapshot served as fresh. Per-dimension `stale`/`stalenessMs` derive from the same fact (see the health/readiness contract).
*   **Forcing a sample.** `GET /api/status?fresh=true` performs exactly one synchronous sample (RPC + tap reconciliation) for debug tooling.

Consequence: the browser's ~10 s poll is no longer the reason the condition store looks correct, and the health SSE stream advances with no client polling at all.

**Proto structure (tempdoc 341):** `StatusResponse` uses 10 nested sub-messages instead of flat fields. Each sub-message owns its own field number space. The Java view (`WorkerOperationalView`) mirrors this with 11 sub-records. JSON output currently emits both grouped sub-objects and flat `@JsonUnwrapped` fields for backward compatibility.

Key sub-messages and their fields (current):

- **`CoreStatus`**: `indexState`, `indexAvailable`, `indexHealthy`, `knowledgeServerStartError`. A fatal indexing-loop event reports `FAILED` and `indexHealthy=false`, taking precedence over queued work and ordinary document `ERROR`. This does not imply that search serving has stopped. Consumers must check Worker-RPC freshness before treating the state as terminal evidence.
- **`CompatibilityStatus`**: `reindexRequired`, `reindexRequiredReason`, `indexSchemaFpStored`, `indexSchemaFpCurrent`, `indexSchemaCompatState`, `embeddingCompatState`, `embeddingCompatReason`, `embeddingFingerprintStored`, `embeddingFingerprintCurrent`
- **`EnrichmentCoverage`**: embedding/SPLADE/NER/chunk coverage percentages and counts, plus `EncoderProfile` sub-messages for embed/splade/ner (ORT call counts, sub-phase timing, latency percentiles p50/p95/p99)
- **`MigrationStatus`**: `indexBasePath`, `activeGenerationId`, `buildingGenerationId`, `previousGenerationId`, `migrationState`, per-generation serving, cutover buffering, enumerator progress
- **`FailureStatus`**: `failedJobs`, `lastFailedPath`, `lastFailedErrorMessage`, `pendingJobsCount`, `processingJobsCount`, `pendingReadyJobsCount`, `pendingBackoffJobsCount`, `throughputDocsPerSec`, `throughputWindowState`
- **`QueueDbHealth`**: `queueDbHealthy`, `queueDbLastBackupAtMs`, `queueDbLastQuickCheckAtMs`, `queueDbLastQuickCheckOk`, `queueDbLastErrorAtMs`
- **`GpuDiagnostics`**: CUDA probe results, VRAM usage, GPU session status
- **`VectorQuantization`**: vector format and quantization state
- **`TelemetryStatus`**: telemetry pipeline health

### Debug State Snapshots
For quick "what's running?" introspection, the backend also exposes:
* **`GET /api/debug/state`**: compact state dump (worker + inference + config snapshot pointers). The `llm` section includes:
  - `use_thinking`: `boolean` (from `JUSTSEARCH_USE_THINKING` env var, default `true`)
  - Model paths, context size, GPU layers
  - Runtime mode and availability
* **`GET /api/debug/worker-log`**: last worker log tail (best-effort)
* **`GET /api/inference/status`**: inference mode + effective runtime model/context when available (plus external server adoption diagnostics when applicable)

### Telemetry health monitoring (`/api/telemetry/health`)

For meta-observability of the telemetry stack itself:

* **Endpoint:** `GET /api/telemetry/health`
* **Contract test:** `TelemetryHealthContractTest.java`
* **Schema version:** 1

Response structure:

```json
{
  "schema_version": 1,
  "observed_at": "2026-01-18T00:00:00Z",
  "state": "READY|DEGRADED|ERROR",
  "reason_code": "telemetry.metrics.stale|...|null",
  "counters": {
    "metric_export_failures": 0,
    "metric_export_successes": 10,
    "span_export_failures": 0,
    "span_export_successes": 5
  },
  "rates": {
    "metric_export_success_rate": 1.0,
    "span_export_success_rate": 1.0
  },
  "timestamps": {
    "last_successful_metric_export": "2026-01-18T00:00:00Z"
  }
}
```

State transitions:

| Condition | State | Reason Code |
|-----------|-------|-------------|
| All healthy | READY | null |
| Last export > 5 min ago | DEGRADED | telemetry.metrics.stale |
| Failure rate > 10% | DEGRADED | telemetry.metrics.high_failure_rate |
| Disk space low events > 0 | DEGRADED | telemetry.disk_space_low |
| Telemetry unavailable | ERROR | telemetry.unavailable |

HTTP semantics: always returns `200` (meta-endpoint reports its own health, not a lifecycle gate).

### Disk pressure monitoring

`NdjsonMetricExporter.hasSufficientDiskSpace()` uses tiered thresholds to detect disk pressure before it causes data loss:

| Level | Threshold | Behavior |
|-------|-----------|----------|
| OK | >= 1 GB usable | Normal operation |
| WARNING | < 1 GB usable | Logs warning, sets `TelemetryHealthState.diskPressureLevel` to WARNING, allows writes |
| CRITICAL | < 200 MB usable | Logs error, sets level to CRITICAL, stops telemetry writes |

`DiskPressureLevel` enum (`OK`, `WARNING`, `CRITICAL`) and volatile field live in `TelemetryHealthState`. Level resets to OK on each check cycle when space is sufficient.

### Inference status (`/api/inference/status`)

This endpoint is the UI-facing snapshot of the inference runtime (primarily `InferenceLifecycleManager`).

Top-level fields (current):

- `mode`: `ONLINE` | `INDEXING` | `TRANSITIONING` | `OFFLINE`
- `available`: `boolean`
- `starting`: `boolean`
- `lastStartupDurationMs`: `number` (time from process launch to health 200; near-zero when adopting an existing server)
- `llmContextTokens`: `number` (best-effort from `llama-server` `GET /props`, when available)
- `configuredContextTokens`: `number` (the configured target context window)
- `embeddingQueueSize`: `number`
- `vduQueueSize`: `number`
- `gpu`: best-effort hardware snapshot (`cudaAvailable`, `totalVramBytes`, `vramDescription`)
- `tier`: `"cpu_only"` | `"gpu_unknown"` | `"gpu_lt_8gb"` | `"gpu_8gb"` | `"gpu_12gb_plus"`
- `hasVisionCapability`: `boolean` — Runtime vision capability (from `GET /props` `modalities.vision` when available, or config-level `mmprojPath != null` check when `/props` unavailable). See `docs/explanation/05-ai-architecture.md` §Vision Capability Detection for architecture details.
- `activeModelId`: `string | null` — Friendly model identifier (alias from `/props` or filename with quantization suffix stripped, e.g., "Qwen_Qwen3.5-9B" from "Qwen_Qwen3.5-9B-Q4_K_M.gguf"). `null` when offline or unavailable.

External server adoption diagnostics (`externalServer`):

- Present when the Online AI runtime supports introspection (current default). Mostly relevant when `usingExternalLlamaServer=true`.
- Fields:
  - `usingExternalLlamaServer`: `true` when JustSearch is using a `llama-server` it did not spawn (no process handle)
  - `verified`: `true` when `GET /props` looked like `llama-server`
  - `verificationError`: short reason when unverified (e.g., `HTTP 404`, `ConnectException: ...`)
  - `modelId`: best-effort model identifier (alias or filename); may be `null`
  - `contextTokens`: best-effort `n_ctx`; may be `null`
  - `modelMismatch`: best-effort model mismatch flag (only reliable when `model_path` is present in `/props`)
  - `contextTooSmall`: `true` when `contextTokens < configuredContextTokens`
  - `adoptedAtMs`: epoch millis (0 when never adopted)
  - `lastHealthOkAtMs`: epoch millis of the last successful periodic `/health` check (0 when never)
  - `lastHealthError`: last periodic `/health` failure reason (`null` when healthy)
  - `consecutiveHealthFailures`: current failure streak

Health monitoring timing:

- The periodic `/health` check runs every **30s** and switches inference to **Offline** after **3** consecutive failures, so external-death detection can lag by about **60–90s** (requests may fail before the mode flips).

Example (adopted external server):

```powershell
Invoke-RestMethod -Uri http://localhost:33221/api/inference/status | ConvertTo-Json -Depth 6
```

```json
{
  "mode": "ONLINE",
  "available": true,
  "starting": false,
  "llmContextTokens": 4096,
  "configuredContextTokens": 4096,
  "embeddingQueueSize": 0,
  "vduQueueSize": 0,
  "externalServer": {
    "usingExternalLlamaServer": true,
    "verified": true,
    "verificationError": null,
    "modelId": "external",
    "contextTokens": 4096,
    "modelMismatch": false,
    "contextTooSmall": false,
    "adoptedAtMs": 1700000000000,
    "lastHealthOkAtMs": 1700000005000,
    "lastHealthError": null,
    "consecutiveHealthFailures": 0
  },
  "gpu": {
    "cudaAvailable": true,
    "totalVramBytes": 8589934592,
    "vramDescription": "NVIDIA GeForce RTX ..."
  },
  "tier": "gpu_8gb"
}
```

### The Debug Dashboard
*   **Endpoint:** `/api/debug/dashboard` (`LocalApiServer.handleDebugDashboard`)
*   **Mechanism:** Returns a raw string HTML page.
*   **Purpose:** A "Panic Button" view. If the Lit frontend is broken (White Screen of Death), developers can hit `http://localhost:33221/api/debug/dashboard` to see:
    *   Is the backend alive?
    *   Is the Worker connected?
    *   What is the memory usage?

### Diagnostics Export (`POST /api/diagnostics/export`)

Creates a ZIP bundle for bug reports and support diagnostics:

- `install-state.json` — AI runtime installation state
- `settings.json` — current user settings
- `policy/` — enterprise policy files (if applicable)
- `gpu/capabilities.json` — NVIDIA GPU snapshot
- `logs/` — application log files
- `telemetry/metrics.ndjson`, `telemetry/metrics-worker.ndjson` — telemetry data (size-capped to last 5MB per file)
- `crashes/` — crash report JSON files
- `runtime/debug-state.json` — `/api/debug/state` snapshot at export time
- `runtime/status.json` — `/api/status` snapshot at export time

The tag allowlist already excludes file paths, query text, and doc IDs, so PII risk in NDJSON tag values is low.

### Slow Request Diagnostics

When API requests exceed 3000ms, the backend automatically captures `Thread.getAllStackTraces()` for later diagnostic use:

- **Storage:** `{dataDir}/slowapi/slow-{pid}-{epochMs}.json`
- **Rate limiting:** Maximum 1 dump per 30 seconds (prevents flood under sustained slow requests)
- **Capture:** Async via daemon executor (non-blocking to the request path)

### Runtime Log Level Control (`/api/debug/logging`)

Dynamic log level adjustment without restart:

- `GET /api/debug/logging` — Returns loggers with explicitly set levels
- `POST /api/debug/logging` — Sets level for a specific logger. Validates against `TRACE|DEBUG|INFO|WARN|ERROR|OFF`. Changes are immediate but non-persistent (reset on restart).

Requires Logback backend (returns 501 with clear error on non-Logback runtimes).

### Time-Series Storage (RRD4J)

For dashboard trend visualization, `RrdMetricStore` provides fixed-size time-series storage using [RRD4J](https://github.com/rrd4j/rrd4j):

**Curated metrics** (~15 high-value counters and gauges from the ~70+ total):
- Worker: `documentsIndexed`, `searchesTotal`, `jobQueueDepth`, `pendingEmbeddings`, `switchBufferDepth`
- Head: `httpInflightRequests`
- JVM: `heapUsedBytes`, `threadsLive`, `gcCollectionCount` (per-process)
- AI/LLM: `intentSuccessTotal`, `summarySuccessTotal`, `llmQueueDepth`
- IPC: `grpcReconnect`, `circuitBreakerRejected`
- GPU: `gpu.utilization.percent`, `gpu.memory.utilization.percent`

**Archives** (3-tier consolidation, fixed total size ~50KB):

| Resolution | Retention | Points | Use case |
|------------|-----------|--------|----------|
| 5 minutes | 24 hours | 288 | Recent activity sparklines |
| 1 hour | 7 days | 168 | Daily pattern analysis |
| 1 day | 90 days | 90 | Long-term trends |

**Query API:**
- `GET /api/debug/metrics/timeseries?metric=X&start=-1h&end=now` — Query time-series data. Supports relative time specs (`-1h`, `-7d`, `-30m`), ISO-8601 timestamps, and `now` keyword.
- `GET /api/debug/metrics/timeseries/available` — List curated metrics.

**Integration:** `NdjsonMetricExporter.export()` writes to RRD after each NDJSON flush (same 5s cycle, same `telemetry-flush` daemon thread). No additional threads required.

## SLO Buckets
`LocalTelemetry` configures explicit histogram buckets for latency tracking so percentile/SLO computations are deterministic and stable across local runs:
*   `5ms, 10ms, 20ms, 50ms` (Fast hits — 5ms added for BM25 visibility)
*   `100ms, 200ms, 400ms` (Standard limits)
*   `800ms, 1500ms, 3000ms, 5000ms, 10000ms` (Slow/Timeout — 5s/10s for degradation detection)

Practical note:

- `/api/health` is now a **contract-tested lifecycle gate** (schema v1) and uses HTTP `200` for `READY|DEGRADED` vs `503` for other states.
- `/api/status` remains the **richer** “what’s running?” payload and includes the stable lifecycle subset for automation plus legacy fields for back-compat.
- readiness now consumes worker throughput in addition to structural state:
  when `indexServing` is structurally ready, jobs are active, and
  `throughputWindowState` falls to `STALLED` or `DEGRADED`, the
  `indexServing` readiness component degrades with stable reason codes
  `worker.throughput_stalled` or `worker.throughput_degraded`
- raw status fields remain additive and unchanged; the policy change is in
  readiness interpretation and UI health warnings, not in telemetry collection

## Perf evidence artifacts (EBv1 suites)

> **Removed (tempdoc 638):** the scripts/perf/ EBv1 perf-suite was deleted.

The perf tooling treats telemetry + scenario outputs as **reviewable artifacts**, not just console logs:

- **EvidenceBundle v1 (per scenario)**: `tmp/agent-evidence/<scenario>/<timestamp>/...`
  - Includes `attachments/perf/perf-report.json` + `attachments/perf/perf-report.md` (schema v1 KPIs/SLOs)
- **Suite summaries**: `tmp/agent-evidence/_summaries/perf-suite-*.{json,md}`

### Standalone benchmark results (Claims A/B)

Separate from EBv1, the benchmarking infrastructure writes results to:

- **Claim A/B results**: `tmp/bench/claim-a/` and `tmp/bench/claim-b/`
- **Suite summaries**: `tmp/bench/_summaries/claim-{a,b}-suite-*.{json,md}`
- **Baselines**: `scripts/bench/baselines/`

Result schema (`bench-result.v1`):
- `time_to_searchable_ms`, `docs_per_s`, `mb_per_s`
- `sentinel_validated` (searchability confirmation)
- `machine_fingerprint`, `git_sha` for reproducibility
- Schema definition: `scripts/bench/schemas/bench-result.v1.schema.json`

See the bench runners under `scripts/bench/` for usage.

### Scenario C outputs (EBv1)

The `scenario_c_ux_indexing` scenario writes:
- `attachments/perf/scenario-c-ux-indexing.json` (time_to_first_hit_ms, keystroke_to_paint_p50/p95)
- `attachments/perf/ui-perf.json` (per-iteration UI timing samples used for percentiles)
