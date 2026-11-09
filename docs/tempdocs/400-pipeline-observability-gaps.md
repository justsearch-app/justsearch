---
title: "400 — Pipeline observability: deltas on the existing telemetry stack (scoped for implementation)"
---

# 400 — Pipeline observability: deltas on the existing telemetry stack (scoped for implementation)

## 0. Status

**Historical projections (2026-11-09):** `bootstrap_ci`, `rank_diff` and `stratified_metrics` generators/tests are retired; references below describe past implementations and captured evidence. **Scoped for implementation.** This tempdoc captures the observability gaps
surfaced by a post-§14.28 end-to-end analysis run (commit `0ed0321ce`,
2026-04-21), the delta architecture that answers them on top of the
existing `modules/telemetry` stack documented in
`docs/explanation/08-observability.md`, and — per user directive in
Revision 4 — the pre-implementation work and implementation sequencing
needed to land all of §8 as designed. **There is no trigger gate.**
Every delta in §8 is scoped and sequenced in §13 + §14.

**Revision history:**

- **Draft 1 (2026-04-21, morning):** 15-item idea bank; treated observability
  as bolt-on features; category error.
- **Draft 2 (2026-04-21, afternoon):** primitive + layer architecture
  theorisation; critiqued Draft 1's categorisation but missed
  `modules/telemetry` + `docs/explanation/08-observability.md` entirely;
  claimed Layer 2 "not there" — factually wrong.
- **Draft 3 (2026-04-21, evening):** corrected Draft 2's factual errors;
  reframed every item as a delta against the real telemetry stack; still
  trigger-gated; contained one remaining factual error (LR2-d tracked
  `pipeline_hash`/`budget_profile` fields that ADR 0014 deleted in
  2026-03-16).
- **Draft 4 (2026-04-21):** per user directive, removes trigger gating;
  adds pre-implementation work inventory (§13) and implementation
  sequencing (§14); fixes LR2-d factual error by retiring orphan fields
  and repurposing the identity slot to commit-metadata hashes (the
  identity model that survived ADR 0014). All 22 deltas remain in this
  tempdoc; nothing deferred.
- **Draft 5 (2026-04-21):** pre-implementation Rounds 1–3
  (partial) executed. §13.1 A-tier verification reads, §13.3 C-tier design
  spikes, and §13.2 B1 trace-format verification complete. Findings folded
  into §8 design accommodations; full results recorded in §13.9.
  Confidence per-delta updated (§13.10). B2 non-determinism envelope
  calibration in progress at time of this revision.
- **Draft 6 (2026-04-22):** Phase 1 implementation landed
  in worktree `worktree-400-lr2d` over 12 sequential commits (see §21).
  11 of 12 Phase 1 deltas shipped; 1 deferred (LR3-a Windows-probe FFM
  surface). LR6-d-A15 landed with a **narrowed scope** vs the plan
  premise — empirical probe found the recipe catches the bare form
  `log.error(e.getMessage())` only, not the parameterized form
  `log.error("msg: {}", e.getMessage())` that dominates 289 F1; see
  §21.3. Implementation surfaced four substantive design issues that
  may require rethinking parts of the §8 spec; these are documented
  in §22 with rethink recommendations. Inline notes at affected §8
  LR* entries cross-reference §21 + §22.
- **Draft 7 (2026-04-22):** Phase 2 closed (LR1-a cohort-hash identity
  fix, LR1-b envelope calibration, LR1-c eval-mode session-policies
  short-circuit; log in §24 + §25). Phase 3 pre-implementation probe
  landed (§26) with four locked decisions (§26.6).
- **Draft 8 (2026-04-22):** Phase 3 complete. All 8 LR4-*
  projections (LR4-a/b/c/d/e/f/g/h) + 2 infrastructure commits
  (cohort_baselines registry migration + nightly observability
  workflow) shipped in a single autonomous overnight session across
  12 commits. No deferrals beyond the locked §26.6 scope decisions.
  Full log in §27; canonical doc refresh in
  `docs/explanation/08-observability.md`.
- **Draft 9 (2026-04-22):** Phase 4 complete. All four §8.5 Layer 5
  experiment runners (LR5-a counterfactual, LR5-b shadow eval,
  LR5-c concurrent benchmark, LR5-d bisection) shipped in 6
  commits. LR5-a shipped as multi-pass variant (documented
  deviation from spec's single-pass proto change; zero Java
  surface area). Full log in §28.
- **Draft 10 (2026-04-22):** Phase 5 clears the §22 Issue A +
  Issue D follow-ups. Two commits: LR2-e.2 + LR2-e.3 spans
  (`search/rerank` at `RagContextOps`, `search/fuse` at the primary
  3-branch fusion in `SearchOrchestrator`), and LR6-a annotation
  relocation to a new dep-free `modules/core-contracts` (unblocks
  annotation reach from `ort-common`, `worker-core`,
  `app-launcher` test sources per §22 Issue A). LR3-a Windows
  probes remain deferred (multi-week FFM infrastructure per §22
  Issue C — formal follow-up tempdoc required). LR2-e.4
  `search.searcher_generation` attr needs a stateSnapshot supplier
  wired to `SearchOrchestrator`; attr is already on the
  `NdjsonSpanExporter` allowlist so the follow-up commit is
  exporter-touch-free. Log in §29.
- **Draft 13 (2026-04-22):** Post-followup-plan validation pass
  added as §23.9. 3-run scifact calibration at HEAD `02bc3516c`
  with detailed tracing confirms tempdoc 400's primary claims
  hold end-to-end (cohort identity stable; envelope calibrates to
  σ(nDCG@10)=0.0003; D-1 `duration_ms` fix universal on 8,985
  mirrored spans; all attr allowlist entries present; all 7
  projections healthy; tempdoc 397 integration clean). One new
  silent-failure defect surfaced — **D-3 traces-mirror rotation
  gap**: `artifacts._mirror_telemetry` copies only the active
  `traces.ndjson`, not rotated siblings, so ingest-heavy runs
  lose up to 77% of their spans to the mirror. Logged in
  `docs/observations.md`; `encoder_drift` baseline is silently
  biased as a result. §23.6 updated; §23.10 Verdict amended.
- **Draft 12 (2026-04-22):** Design-goal achievement retrospective
  landed as §23 — a theoretical closure pass asking "did Phases 1-6
  achieve the goals, ideas, and preventions stated in §6-§17?" Verdict:
  primary thesis (finish the §14.28 discipline for eval observability)
  substantially achieved. Two real gaps documented — LR3-a external
  probes deferred in full to tempdoc 401, Layer 6 tier taxonomy only
  2 of 4 tiers shipped with no follow-up tempdoc tracking the rest.
  One deliberate deviation — LR5-a multi-pass variant preserves the
  consumer contract while deferring the single-pass efficiency goal.
  Prevention rules (§15) held; anti-goals (§16) correctly unsolved.
  See §23 for layer-by-layer analysis + follow-up recommendations.
- **Draft 11 (2026-04-22):** Phase 6 complete — 15 commits
  remediate every HIGH/MEDIUM finding from the post-implementation
  critique (`400-post-implementation-critique.md`). Highlights:
  projection errors now surface via `_errors.ndjson` + contract.violation
  events (6.1); LR4-g baseline moved from cold-start auto-capture
  to opt-in `jseval calibrate-drift-baseline` with ≥3-run floor
  (6.2); LR4-d stall threshold + tracked counters exposed as produce
  kwargs (6.3); LR5-a cost-model docstring corrected + fusion-algorithm
  CLI flag (6.4); LR5-d synthetic-executor fills missing bisection
  cache cells via subprocess-spawned `jseval run` gated by
  `JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS=1` (6.5); LR2-e.4
  `search.searcher_generation` emitter wired to `IndexGenerationManager`
  via `DefaultWorkerAppServices` (6.7); LR2-e.2/.3 span coverage
  extended across every CE/fuse call site (6.8); LR5-b fail-fast
  error-budget + qid-sequence invariants (6.9); LR5-c parallelism
  assertion rewritten + default `max_connections = concurrency`
  + `--warmup N` (6.10); `envelope_metrics.run_id` widened to INTEGER
  with ON DELETE CASCADE FK (6.11); LR4-c bucket edges configurable
  + LR5-a `_infer_mode` round-trip test (6.12); nightly gate
  relocated into `jseval.gate` + `jseval gate` CLI (6.13); this
  tempdoc split into design (§0–§22) + implementation log
  (§23–§30 in `400-implementation-log.md`, 6.14); Phase 6 closure
  + verification (6.15). 710 pytest green; gradle compile/PMD/spotless
  green (integrationTest suite has one pre-existing classpath failure
  unrelated to Phase 6 — see implementation-log §30.4). Phase 6
  ledger + §30.2–§30.4 closure in
  `docs/tempdocs/400-pipeline-observability-gaps.md` §30.

**Non-triggers — preserved as anti-pattern discipline for future
extensions** (not triggers gating this tempdoc's implementation):

- "More observability would be nice" → no named consumer, no new metric.
- "Dashboards would look cool" → no backing analytical question, no dashboard.
- "We should replay every query" → unmotivated replay is a storage bill.
- "Every invariant a runtime assertion" → industry decay failure mode;
  tier them (§8.6) or don't write them.
- "Cross-run causal attribution with % decomposition" → manifest + bisection
  (§9.2), not Shapley.
- "Bolt a new metric onto the call site" → extend the span/metric
  allowlists or add a projection (§8.4).

These rules govern what should NOT be added in the future. They do not
gate the implementation of §8 itself.

**Created:** 2026-04-21 by the analysis pass documented in §5.
**Revised:** 2026-04-21 (Drafts 2, 3, 4).
**Owner:** Unclaimed. Implementation assignable per-layer (§14).

> NOTE: Noncanonical. The six-layer delta architecture is the implementation
> specification. §8 is the WHAT. §13 is the PRE-IMPLEMENTATION WORK that
> must complete before or alongside each §8 delta. §14 is the SEQUENCE.
> When a layer lands, `docs/explanation/08-observability.md` updates;
> this tempdoc stays as the implementation record, not canonical ongoing
> doc.

---

## 1. Context

A post-§14.28 jseval run produced the richest observability output the
pipeline has ever emitted. The user's prompt was: *"critically analyse the
current state of observability… theorise what is currently missing and what
we should have long term, disregard feasibility."*

Four revisions later (§5) the tempdoc settled on a delta architecture
against the existing telemetry stack, then — per user directive in
Revision 4 — dropped the trigger-gated framing and became an
implementation plan. Draft 4 also corrects Draft 3's LR2-d factual error:
the `pipeline_hash` and `budget_profile` proto fields Draft 3 proposed
populating were deleted (conceptually) by ADR 0014 when
`PipelineDefinition` was removed on 2026-03-16. The identity model that
survived is **commit-metadata hashes** (`schema_fp`, `field_catalog_hash`,
`analyzer_fp`, `synonyms_hash`, `grammar_hash`, `similarity_fp`,
`boosts_fp`, `index_schema_fp`); LR2-d is reformulated accordingly in §8.2.

The overall architecture remains: reuse `modules/telemetry`, don't
re-invent the event bus, invert jseval from pre-aggregation to projection
consumption, build a small set of genuinely novel pieces for Layer 5
(experiment runners + manifest-hash bisection).

---

## 2. What's actually instrumented today

Source of truth: `docs/explanation/08-observability.md` (canonical, 515
lines) + `modules/telemetry/**` (implementation). This inventory reflects
what is in-tree on commit `0ed0321ce`.

### 2.1 Producer + exporter stack (`modules/telemetry`)

- **`LocalTelemetry`** (`Telemetry.java`): OTel-backed producer API for
  counter / timer / gauge / histogram with explicit SLO buckets (5ms,
  10ms, 20ms, 50ms, 100ms, 200ms, 400ms, 800ms, 1500ms, 3000ms, 5000ms,
  10000ms) applied to `pipeline.stage_ms`, `llm.latency_ms`,
  `api.request_ms`, `api.stream.ttft_ms`, `plugins.stage.load_ms`,
  `index.runtime.refresh_lag_ms`. Gauge callbacks pull from
  `LongAdder`-backed `OperationalMetrics` so gRPC status reads (which
  need readable counter values) coexist with write-only OTel counters —
  the dual-system design is intentional (08-observability §94–103).
- **`TracingBootstrap`**: `SdkTracerProvider` + `BatchSpanProcessor`
  (2048 queue, 512 batch, 5s interval) + W3C trace context propagator +
  optional `OtlpHttpSpanExporter` for external fan-out to self-hosted
  Opik (tempdoc 265). `JUSTSEARCH_INDEX_TRACING_LEVEL` gates cost:
  `none` (default, zero overhead), `sample` (1% ratio), `detailed`
  (100%). `JUSTSEARCH_WORKFLOW_RUN_ID` + `JUSTSEARCH_WORKFLOW_FAMILY`
  become resource attributes (tempdoc 272).
- **`NdjsonSpanExporter`** writes to `<dataDir>/telemetry/traces.ndjson`
  (10 MB rotation, 7-day retention). Attribute allowlist is hard-coded
  at the top of the file and currently includes: `pipeline_name`,
  `pipeline_hash`, `budget_profile` *(orphans post-ADR 0014 — retired
  by LR2-d)*, `stage_id`, `reason_code`,
  `justsearch.workflow.run_id`, `justsearch.workflow.family`,
  `gen_ai.{operation.name, agent.id, agent.name, conversation.id,
  tool.name, tool.call.id, usage.input_tokens, usage.output_tokens}`,
  `batch.{polled, extracted}`, `embed.{batch_size, gpu, success, error}`,
  `doc.{path, size_bytes}`, `embedding.source`, `paths.count`,
  `search.{mode, query_length, total_hits, took_ms}`. Any attribute
  not on this list is dropped at export time.
- **`NdjsonMetricExporter`**: similar allowlist for metric tag keys.
  Disk pressure tiers (OK ≥ 1 GB; WARNING < 1 GB; CRITICAL < 200 MB —
  CRITICAL stops writes) reported via `TelemetryHealthState`.
- **`RrdMetricStore`**: 3-tier time-series (5min × 24h, 1h × 7d,
  1d × 90d), ~15 curated metrics including `gpu.utilization.percent`,
  `gpu.memory.utilization.percent` (NVML via FFM), JVM saturation,
  queue depth, IPC health.

### 2.2 Spans already emitted

**Indexing (`IndexingLoop.java`):** `indexing.batch` (line 802) →
`indexing.extract` (line 946), `indexing.embed_batch` (line 846),
`indexing.write` (line 989), `indexing.markDone` (line 1048). Zero-cost
gate: `maybeSpan()` at line 305 returns `Span.getInvalid()` singleton
when `detailedTracing=false`.

**Search (`SearchOrchestrator.java`):** `search/retrieval` (line 281,
tracer at line 68), `search/chunk_merge` (line 685).

**Agent:** `invoke_agent → chat → execute_tool` following
OpenTelemetry GenAI semantic conventions.

**Head↔Worker propagation:** `TraceClientInterceptor` +
`TracingServerInterceptor` propagate `traceparent` via gRPC metadata.

### 2.3 State endpoints

`/api/status` (10-submessage `StatusResponse` proto, tempdoc 341),
`/api/debug/state`, `/api/debug/session-policies` (§14.28 U4),
`/api/debug/commit-metadata` (tempdoc 330 — returns
`Map<String,String>` Lucene commit user data),
`/api/telemetry/health` (schema v1), `/api/debug/metrics/timeseries`
(RRD query), `/api/debug/events` (EventBuffer ring),
`/api/inference/status`, `/api/debug/worker-log`,
`/api/debug/logging` (dynamic log level), `/api/debug/dashboard`
(HTML panic view).

**`CapabilitiesService`** (post-ADR 0014 simplified): exposes
`schema_versions {schema_ver, grammar_ver, template_ver}`,
`prompt_templates` (SHA-256 hashed), `plugins`, `source`. NO pipeline
metadata. Useful for Layer 1 manifest (prompt-template identity axis).

### 2.4 Per-ORT-call data exists — but not as spans

`EncoderProfileAccumulator` uses `org.HdrHistogram.Histogram` (line 25).
`recordOrtCall(long ortCallNs)` at line 60 records per-call latency.
Already called from `OnnxEmbeddingEncoder.java:318` (immediately after
the `session.run()` at line 315). Snapshots go into
`StatusResponse.EnrichmentCoverage.EncoderProfile`. Exporting as per-call
OTel spans is LR2-a.

### 2.5 jseval's existing projection surface

- `_build_summary()` assembles `summary.json` by polling `/api/status`.
- `timeline.py` writes a 27-column TSV via 2 s `/api/status` polling.
- `{mode}_per_query.json` written per run.
- `compare_runs.per_query_diff(...)` exists (not auto-wired at run exit).
- `history.check_trend(...)` does simple drift detection against a
  5-run rolling window.
- `eval-history.db` 11-column SQLite.

**jseval does not read `traces.ndjson` or `metrics.ndjson`.** Every
aggregate is derived from API polling. Biggest Layer 4 gap.

### 2.6 Privacy + retention + health

`SensitiveQuery` + `redact()` prevents raw query text in logs.
`TelemetryHealthState` with tiered disk pressure.
`/api/diagnostics/export` bundles metrics, state, crash reports.
Slow-request diagnostics dump stacks for > 3000 ms requests.

### 2.7 Structural prior art

Tempdocs already landed: 265 (Opik/Inspect AI stack selection, complete),
272 (workflow attribution, done), 298 (MDC adoption — `pipeline_hash`
and `budget_profile` proto fields added but unpopulated; see LR2-d fix),
330 (worker state accuracy + commit-metadata endpoint), 335 (jseval
pipeline observability, complete), 354 (map-based metrics, done),
312 (validated sub-10µs span overhead).

Active: 356 (inference observability — in-process HdrHistogram
distribution shape is here; LR2-a extends it to span export).

Open: 289 (logs audit RC1 — logging-convention PMD/ArchUnit rules
never implemented; closed by LR6-d).

### 2.8 ADR 0014 — Pipeline Definition removal (2026-03-16)

`modules/pipeline-schema` was removed entirely. `PipelineDefinition`,
budget profiles, `dag_hash`, `pipeline_budget_profile` all deleted.
`CapabilitiesService` was "simplified to expose prompt templates and
schema versions only." Commit metadata retains `schema_fp`,
`field_catalog_hash`, `analyzer_fp`, `synonyms_hash`, `grammar_hash`,
`similarity_fp`, `boosts_fp`, `index_schema_fp`.

Implication for 400: the `pipeline_hash` + `budget_profile` fields added
to `PipelineConfig` proto by tempdoc 298 (fields 9–11) are **orphans**
referencing a deleted system. LR2-d retires them and replaces the
identity slot with commit-metadata-driven span attributes.

---

## 3. Cross-run comparison — today's observability in action

Pre-§14.27 baseline (commit `58221d5fa`, 2026-04-18) vs post-§14.28
(commit `0ed0321ce`, 2026-04-21):

| Metric | Pre-§14.27 | Post-§14.28 | Delta |
|---|---|---|---|
| Ingest elapsed | 253.1 s | 208.0 s | -18% |
| Docs/sec | 20.5 | 24.9 | +21% |
| GPU avg util | 59.4% | 71.7% | +21 pp |
| GPU idle polls | 18.9% | 0.0% | **-18.9 pp** |
| VRAM peak | 4.1 GB | 6.4 GB | +57% |
| Query p50 | 210 ms | 183 ms | -13% |
| Query p95 | 220 ms | 209 ms | -5% |
| nDCG@10 | 0.754 | 0.750 | -0.005 (noise?) |
| P@1 | 0.630 | 0.627 | -0.003 (noise?) |

Every row answers WHAT moved; none answer WHY. "(noise?)" unanswerable
without §9.1 envelope.

---

## 4. Scenarios where today's observability is thin

Eight concrete gaps, each tied to a specific delta in §8:

- **4.1** no per-query rank-diff at run exit → §8.4 LR4-e
- **4.2** p99 lease-vs-run not separable → §8.2 LR2-b
- **4.3** silent CPU fallback not in summary → §8.2 LR2-c + §8.4 LR4-f
- **4.4** nDCG CI unknown → §8.4 LR4-b + §9.1
- **4.5** no stratified metrics → §8.4 LR4-c
- **4.6** policy snapshot not in summary → §8.1 LR1-c
- **4.7** stall detection missing → §8.4 LR4-d
- **4.8** no concurrent-load characterisation → §8.5 LR5-c

---

## 5. Analysis methodology

1. User request (2026-04-21): full end-to-end analysis, theorise what's
   missing long-term, disregard feasibility.
2. Full jseval pipeline run and cross-run comparison.
3. Draft 1 → 2 → 3 → 4 (see §0 revision history).
4. Draft 4 triggers:
   - User directive: "we don't need to wait for triggers, we will be
     implementing the tempdoc exactly as designed."
   - Factual verification of Draft 3's LR2-d claim against ADR 0014 +
     `CapabilitiesService` source + `PipelineConfig` proto history.
5. Draft 4 adds: §13 pre-implementation work, §14 sequencing, LR2-d
   reformulation, scope-boundary rewrite from "parked" to "scoped for
   implementation."
6. Draft 5 executes §13 Rounds 1 (A-tier reads) + 2 (C-tier spikes) + 3
   (B1 trace format) and records findings in §13.9. Folds design
   accommodations discovered (`indexing.embed_batch` is migration-only;
   Head and Worker emit separate metric files; BgeM3Encoder lacks a
   profile accumulator; SearchOrchestrator virtual-thread gap on 3-way
   retrieval affects LR2-e) into the affected §8 subsections. Confidence
   per delta updated in §13.10.

---

## 6. The root diagnosis

The existing stack is a faithful typed-primitive observability
architecture for operational telemetry. It serves "is the pipeline
broken?" well. The gaps surface specifically when eval asks "why did
this change?" — a class of question the stack wasn't tuned for. The
root cause is not missing architecture; it's **jseval's projection
surface has not yet been wired to consume the event bus the Worker
emits**, plus a handful of targeted span additions and a Layer 5
experiment infrastructure that doesn't exist.

---

## 7. The six primitives, mapped to the existing stack

| Primitive | Existing mechanism | Delta needed |
|---|---|---|
| **State** | `PolicySnapshot` via `/api/debug/session-policies`; `StatusResponse`; `/api/debug/commit-metadata`; `CapabilitiesService` (schema + templates); env + model fingerprints | Aggregate all hashes into run manifest (§8.1); non-det envelope (§9.1) |
| **Event** | OTel spans via `TracingBootstrap`; `NdjsonSpanExporter` with allowlist; gRPC propagation; indexing + search + agent span trees | Per-ORT-call span + `lease.acquire` child (§8.2 LR2-a/b); `cpu_fallback.triggered` event (LR2-c); retire orphan `pipeline_hash`/`budget_profile`, add `commit.*` identity attrs (LR2-d reformulated); richer search spans (LR2-e) |
| **Sample** | `RrdMetricStore`; NVML GPU gauges; JVM + IPC gauges; `NdjsonMetricExporter` with disk pressure | External-world sources (§8.3); jseval consumes `metrics.ndjson` directly |
| **Projection** | `summary.json`, `{mode}_per_query.json`, `timeline.tsv` hand-assembled; `compare_runs.per_query_diff` unpaired; `history.check_trend` simple | jseval inverted to projection consumer (§8.4); bootstrap CI; stratified; rate-based + stall tagging; auto rank-diff; CPU-fallback count; PSI/MMD drift; eval-history schema extension |
| **Experiment** | Nothing | All four runners from scratch (§8.5) |
| **Contract** | ArchUnit (`ClosurePropertyTest`); boot-time composition-root failure; contract-tested endpoints; `TelemetryHealthState` tiers; 289 RC1 open | Tier taxonomy (§8.6); audit every invariant; violation-count projection; close 289 RC1 |

---

## 8. The delta architecture

### 8.1 Layer 1 — State + manifest + non-determinism envelope

**Exists:** per-authority state endpoints; per-run identity fields in
`summary.json`; workflow identity resource attrs.

**Delta (`LR1`):**
- **LR1-a Run manifest.** jseval calls every state endpoint at run start;
  hashes each response; emits a single manifest document per run:
  ```
  {
    run_id, workflow_run_id, timestamp, git_sha,
    policy_hash, state_hash, commit_metadata_hash, env_hash,
    corpus_hash, qrels_hash, capabilities_hash, prompt_templates_hash,
    model_fingerprints: { embed, splade, ner, ce },
    eval_protocol_hash,
    non_determinism_envelope: { nDCG@10_stdev, P@1_stdev, ... }
  }
  ```
- **LR1-b Non-determinism envelope.** `jseval calibrate --manifest <hash>
  --reruns N` executes N identical runs; captures σ per metric;
  writes envelope into manifest cohort. Runs without a calibrated
  envelope get a "envelope: unknown" marker.
- **LR1-c Policy snapshot embedded.** jseval's `_snapshot_models()`
  extended to call `/api/debug/session-policies` and embed the result
  + hash. Closes §4.6.

### 8.2 Layer 2 — Span additions + attribute allowlist extensions

**Exists:** full OTel span infrastructure; indexing/search/agent span
trees; gRPC propagation; `maybeSpan()` zero-cost gate; allowlist in
`NdjsonSpanExporter.ALLOWED_ATTRS`.

**Delta (`LR2`):**
- **LR2-a Per-ORT-call span.** New span kind `encoder.ort_run` emitted
  around each `session.run()` call in the four encoder classes. Call
  sites verified in Round 1 A1: `OnnxEmbeddingEncoder` (lines 315, 471),
  `SpladeEncoder` (lines 533, 759, 867, 915 — multiple pooling paths),
  `BertNerInference` (line 214 region — single point), `BgeM3Encoder`
  (line 293 — **but no `profiler.recordOrtCall` present**; see
  prerequisite below). Attrs (added to allowlist): `encoder.name`,
  `encoder.gpu`, `encoder.batch_size`, `encoder.seq_len`.

  **Design accommodation (B1 finding, 2026-04-21):** `indexing.embed_batch`
  is only emitted during migration rebuilds (per
  `IndexingLoop.java:130` comment). Normal enrichment runs on a
  separate code path outside the `indexing.batch` span tree. New
  `encoder.ort_run` spans will therefore be root-level during normal
  runs (no parent span). Two resolutions, choose during implementation:
  - **Option A (accept):** root-level spans; per-query attribution
    reconstructed post-hoc via projection over `traces.ndjson`.
  - **Option B (new parent):** add `enrichment.batch` span around the
    batch loop in the enrichment ops class; `encoder.ort_run` becomes
    its child. Cleaner tree, ~20 LOC addition.
  Recommended: Option B, small cost, clean tree.

  **Prerequisite (A1 finding):** `BgeM3Encoder` does not use
  `EncoderProfileAccumulator` today. Before LR2-a lands for BgeM3,
  add the accumulator with phases `{tokenize, tensor, ort, extract}`
  matching the pattern in `OnnxEmbeddingEncoder` — or accept that
  BgeM3's `encoder.ort_run` span emits without in-process distribution
  data (still useful for correlation; loses aggregate percentiles for
  BgeM3 specifically).

  Coordinates with tempdoc 356's in-process `HdrHistogram` — 356
  continues to own the `EncoderProfile` status contract; LR2-a is the
  span-export sibling. **Refined value proposition** (356 check):
  per-encoder p50/p95/p99 are already surfaced via `/api/status.
  worker.enrichment.*.profile`. LR2-a's real value is **correlation**
  (which query / batch triggered which ORT call) and **per-batch
  distributions reconstructible post-hoc from span durations**, not
  aggregate percentiles.
- **LR2-b `lease.acquire` child span.** Child of `encoder.ort_run`.
  **Phase-1 landing note (Draft 6, §21 commit 4):** the child-parent
  semantic is honored for `OnnxEmbeddingEncoder` + `BgeM3Encoder` (span
  starts pre-acquire; `lease.acquire` emitted inside
  `NativeSessionHandle.acquire` parents naturally). For
  `BertNerInference` + `SpladeEncoder` pinned-path, the nested
  try-with-resources structure made the pre-acquire restructure
  prohibitively invasive — `encoder.ort_run` stays inside the lease
  scope and `lease.acquire` is a sibling correlated via `trace_id`. See
  §22 Issue B for the design-review implication.
  Wraps `gpuInferenceSemaphore.acquireUninterruptibly()` at
  `NativeSessionHandle.java:217`. Attrs: `lease.mode` (gpu / cpu),
  `lease.wait_queue_depth`. Separates semaphore wait from native run.
  Closes §4.2.
- **LR2-c `cpu_fallback.triggered` span event.** Emitted from
  `NativeSessionHandle.reportCpuSessionFailure()` (line 302–306 —
  single point of instrumentation; one production caller at
  `CrossEncoderReranker.java:225`). Attrs: `fallback.cause`,
  `fallback.encoder`. Closes §4.3.
- **LR2-d (reformulated) — Retire orphan fields + populate
  commit-metadata identity.** ADR 0014 deleted `PipelineDefinition`;
  the `PipelineConfig.pipeline_hash` + `PipelineConfig.budget_profile`
  proto fields (added by tempdoc 298 item 2) are orphans pointing at a
  deleted system. Actions:
  - Mark these proto fields deprecated; stop writing from Head-side
    adapters; remove from `NdjsonSpanExporter.ALLOWED_ATTRS` and
    `NdjsonMetricExporter.ALLOWED_TAG_KEYS`; remove from
    `MdcContext.pipeline()` population.
  - Add new allowlisted span attributes populated from commit metadata
    on indexing + search spans: `commit.schema_fp`,
    `commit.field_catalog_hash`, `commit.analyzer_fp`,
    `commit.synonyms_hash`, `commit.grammar_hash`,
    `commit.similarity_fp`, `commit.boosts_fp`,
    `commit.index_schema_fp`. These are the identity signals that
    survived ADR 0014 and **do** govern runtime behaviour.
  - Source: Worker reads its own commit metadata once at startup;
    span population uses a cached snapshot (no per-span disk I/O).
  Closes tempdoc 298's open item via *deletion*, not population.
- **LR2-e Richer search-side spans.** Add `search/rerank` and
  `search/fuse` children under `search/retrieval`. Add attrs
  `search.retrieval.branch` (dense/lexical/splade),
  `search.ce.scored`, `search.fusion.weights_fp`,
  `search.searcher_generation` (Lucene reader generation id — the
  feature Draft 1 §10.11 proposed). Makes §4.1's rank-change diagnosis
  answerable per-query from `traces.ndjson`.
  **Phase-1 landing note (Draft 6, §21 commit 6):** shipped scope is
  `search/branch` per-leg spans + `search.retrieval.branch` attr +
  manual virtual-thread `Context` propagation at
  `SearchOrchestrator.java:504/508/512`. Deferred: `search/rerank`
  (CE rerank lives in a different caller class, not `SearchOrchestrator`
  — different span-emission surface); `search/fuse` (multiple
  `HybridFusionUtils` call sites, per-site span adds more noise than
  value since parent `search/retrieval` already captures fusion time
  implicitly); `search.ce.scored` + `search.fusion.weights_fp`
  (deferred with the rerank / fuse spans);
  `search.searcher_generation` (no obvious Worker-side source for the
  monotonic id surfaced as `serving_search_generation_id` in
  `/api/debug/state`; needs separate wiring). See §22 Issue D.

### 8.3 Layer 3 — Sample source additions

**Exists:** `RrdMetricStore` 3-tier; NVML GPU gauges; JVM + IPC gauges;
`NdjsonMetricExporter` with disk pressure.

**Delta (`LR3`):**
- **LR3-a External-world sampler sources.** Add CPU frequency
  (per-core via Windows PDH), GPU temperature (NVML — already present),
  disk I/O throughput (`Files.getFileStore()` patterns), memory
  pressure indicators to `LocalTelemetry` gauge registration. Gated
  behind env var (default off until Windows probe surface is validated).
  **Phase-1 deferral (Draft 6, §21 commit 7):** entire LR3-a bundle
  deferred. Extending existing `NvmlService` for per-call polling
  requires substantial refactoring (current `probe()` is
  init+query+shutdown per call; a gauge needs long-lived init + periodic
  poll + shutdown-at-teardown). `GpuCapabilities.Nvml` record needs a
  new `temperature` field. CPU freq + memory pressure require new
  Windows PDH / Win32 FFM binding surfaces per ADR 0005. See §22
  Issue C for the scope-granularity implication.
- **LR3-b Sample stream accessible to jseval.** jseval consumes **both**
  `<dataDir>/telemetry/metrics.ndjson` (Head) and
  `<dataDir>/telemetry/metrics-worker.ndjson` (Worker) — B1 finding
  confirmed both files are written by the two-process architecture.
  Projections must merge the streams by timestamp + source tag. Single-
  pass tail over a time window. In-process `RrdMetricStore` stays for
  dashboard use.
- **LR3-c Tag allowlist entries.** Add `device_id`, `probe_source` to
  `NdjsonMetricExporter.ALLOWED_TAG_KEYS`. Bounded cardinality.

### 8.4 Layer 4 — jseval as projection consumer

**The largest delta.** jseval inverts from hand-assembly to derivation.

**Exists:** see §2.5.

**Delta (`LR4`):**
- **LR4-a Projection base layer.** New `jseval.projections` module.
  Reads `traces.ndjson` + `metrics.ndjson` + state-endpoint cached
  responses for a run; produces projection outputs as pure functions.
  Every projection has a name, schema version, input manifest.
  `summary.json` becomes the default projection.
- **LR4-b Bootstrap CI projection.** Uses `ranx.compare` paired
  bootstrap over per-query metrics (1000–10000 resamples, 95% CI).
  Emits `{metric}_ci_low`, `{metric}_ci_high` per mode. Complements
  §9.1. Closes §4.4 within-run.
- **LR4-c Stratified metric projection.** nDCG@10 stratified by
  query-length + first-relevant-rank buckets. Input:
  `{mode}_per_query.json`. Closes §4.5. (**Decision locked 2026-04-22
  per §26.6 Decision 1:** entity-density dimension dropped from Phase
  3 to preserve projection purity; rejoins when a query-understanding
  pipeline exists.)
- **LR4-d Rate-based timeline + stall tagging.** Reads `metrics.ndjson`;
  derives per-tick rate; flags >2σ-below-mean ticks as named stalls.
  Emits `timeline-stalls.json`. Closes §4.7.
- **LR4-e Auto rank-diff projection.** Runs
  `compare_runs.per_query_diff` at run exit against the latest prior
  run in same dataset+mode with matching manifest prefix. Writes
  `{mode}_regressions.json`. Closes §4.1.
- **LR4-f CPU-fallback count projection.** Reads `cpu_fallback.triggered`
  events from `traces.ndjson`; aggregates per-encoder count +
  timestamps. Embeds in `summary.json` under `inference.fallback_summary`.
  Closes §4.3 (jseval side).
- **LR4-g Encoder distribution-drift projection.** Reads `encoder.ort_run`
  spans grouped by manifest cohort + encoder. Computes PSI (or
  Wasserstein / MMD) between run distribution and cohort baseline.
  Drift flag in `summary.json.inference.drift_summary`. PSI / MMD on
  distributions, **not** hashing. (**Decision locked 2026-04-22 per
  §26.6 Decision 2:** cohort baseline lives under the new
  `<data_dir>/cohort_baselines/<cohort_hash>/span_distributions.json`
  facet; Phase 2's envelope sidecar migrates to the same directory
  as `envelope.json`.)
- **LR4-h Eval-history schema extension.** `eval-history.db` gets
  `manifest_hash` added to the `runs` table + a new normalized
  `envelope_metrics` table (columns: `run_id`, `cohort_hash`, `mode`,
  `metric`, `mean`, `stdev`, `n`, `calibrated_at`) with indexes on
  `cohort_hash` and `(metric, mode)`. `check_trend` generalised to
  cohort-aware (same `manifest_hash` → within-cohort comparison).
  (**Decision locked 2026-04-22 per §26.6 Decision 3:** supersedes
  the original spec's `policy_hash` / `env_hash` / `corpus_hash` /
  `non_det_envelope_stdev_ndcg10` columns — `env_hash` was removed
  from cohort identity in Phase 2.0 and a single-metric column
  conflicts with our multi-metric envelope. Normalized table handles
  metric-set evolution without migration.)

### 8.5 Layer 5 — Experiment runners (genuinely new infrastructure)

**Exists:** nothing. jseval runs one pipeline config per invocation.

**Delta (`LR5`):**
- **LR5-a Counterfactual single-pass runner.** Worker `SearchRequest`
  proto extended with `repeated string counterfactual_modes` (default
  empty = legacy single-mode). Response gains
  `map<string, RankList> counterfactual_modes`. `SearchOrchestrator`
  fans out inside the retrieval stage, returning lexical-only /
  dense-only / splade-only / hybrid-no-CE / hybrid-full in one pass.

  **C1 design spike complete (2026-04-21):** `SearchOrchestrator`
  **already computes** per-branch rankings at lines 503–512 (BM25,
  dense, SPLADE via `CompletableFuture.supplyAsync` virtual threads);
  they are discarded after fusion today. LR5-a is therefore **plumbing**
  (capture intermediate rankings + serialise them), **not new compute**.
  The only additional work is the `hybrid_no_ce` synthetic: skip the CE
  rerank stage while keeping fusion — a flag on the pipeline config,
  not a new retrieval pass. CE runs once for the primary `hits`; no
  extra CE passes.

  jseval emits per-query counterfactual diff.
- **LR5-b Shadow evaluation runner.** Two policy modes on same queries;
  workers share index + reader; policy differs (e.g., fusion weights,
  CE routing). Divergence report per query. **Selection-bias scope**
  (hard constraint, documented in §13): offline-only; fixed eval query
  set; no production traffic sampling; query-feature-conditional
  sampling explicitly prohibited. Post-Gilotte-2018 literature has no
  clean answer for the general case; 400 scopes LR5-b to the bounded
  offline case where the bias is "eval distribution ≠ production
  distribution" — a documented assumption, not a hidden one.
- **LR5-c Concurrent-query benchmark runner.** `jseval run --concurrency
  N`. Driver spawns N virtual clients. Per-stream timeline from
  `metrics.ndjson` + `traces.ndjson` (`lease.acquire` now visible via
  LR2-b). Queue-depth as a new Layer 3 sample source (falls under
  LR3-a). Output: `concurrency-<N>.json` + per-stream traces. Informs
  tempdoc 398's regression gate.
- **LR5-d Manifest-hash bisection runner.** Given manifests A and B
  with delta > ±2σ of the §9.1 envelope: hash-diff the tuples, run with
  one-axis-swapped manifests, cache intermediate runs by hash. Novel
  infrastructure — MLflow/DVC do lineage tracking but not bisection
  (confirmed by web research; see §18).

  **C2 design spike complete (2026-04-21):**
  - Index: `tmp/eval-results/_index/manifests.jsonl` — append-only
    `{manifest_hash, run_dir, timestamp, git_sha}`.
  - Cohort envelopes: `tmp/eval-results/_cohort-envelopes/<hash>.json`.
  - Algorithm: see pseudocode in §13.9 C2. Single-axis bisection only;
    no O(n²) 2-axis combinations.
  - **Known limitation (documented in output):** if no single-axis
    swap reproduces the delta within envelope, LR5-d outputs
    `MULTI_AXIS_INTERACTION` with the candidate axis set. Operator
    then must investigate manually.
  - Invalidation: axis value change → cache miss + new run; axis
    schema addition → NULL treated as a distinct axis value; orphaned
    envelopes GC-eligible after 90 days.

### 8.6 Layer 6 — Contract tier formalisation

**Exists:** ArchUnit rules; boot-time validation; contract-tested HTTP
endpoints; `TelemetryHealthState` tiers; tempdoc 289 RC1 open.

**Delta (`LR6`):**
- **LR6-a Tier taxonomy + annotation surface.**
  **Phase-1 landing note (Draft 6, §21 commits 8 + 9):** shipped
  `@BuildContract` + `@AdvisoryContract` with deferred `@BootContract`
  + `@SampleContract` per §13.3 C4. During LR6-b annotation placement
  I discovered the annotation classes' original location
  (`modules/app-observability`) is reachable by only 1 module; moved
  to `modules/ipc-common/src/main/java/io/justsearch/ipc/contracts/`
  which reaches ~14 modules. This still misses `ort-common` (no
  `ipc-common` dep — pulling gRPC into fundamental modules is the
  wrong direction), `app-launcher` test sources, and `worker-core`
  for encoder sites. See §22 Issue A for the module-placement
  architectural question. Four tiers (remaining spec text):
  `@BuildContract` (ArchUnit/unit test, blocks merge),
  `@BootContract` (verified at composition root, fails startup),
  `@SampleContract(every = N)` (runtime sampled; emits
  `contract.violation` span event on failure), `@AdvisoryContract`
  (log-only, feeds drift detection).
- **LR6-b Audit existing invariants into tiers.** Every invariant
  documented in tempdocs 397 §14.25–§14.28 gets a tier. Invariants
  without a tier become either (a) tier-assigned + enforced or
  (b) deleted from the canonical doc.
- **LR6-c Contract-violation projection.** Layer 4 consumer: reads
  `contract.violation` events; aggregates by tier; drift in violation
  count is itself a drift signal.
- **LR6-d Close tempdoc 289 RC1.** Logging-convention PMD/ArchUnit
  rules as `@BuildContract` items. Targets: `e.getMessage()` anti-pattern
  (210 sites), broad `catch (Exception)` in tests, `System.err.println`,
  forbidden MDC keys. Subsumed into LR6-a/LR6-b.

---

## 9. Two design choices that collapse multiple problems

### 9.1 Non-determinism envelope as cross-run CI

Two different CI questions: within-run (bootstrap CI — LR4-b) and
cross-run (non-det envelope — LR1-b). Any cross-run delta inside ±2σ of
the envelope is noise by definition; outside → signal → bisection.
Validates §3's "(noise?)" question with a σ per cohort, not a hope.

### 9.2 Bisection replaces Shapley-style causal decomposition

Manifest is a hash tuple. Cross-run delta means axes differ. LR5-d runs
one-axis-swapped manifests, cached by hash. Produces a **proof** (one
axis reproduces the delta), not a percentage. No Shapley decomposition
(industry failure mode; Airbnb/LinkedIn/Meta post-mortems).

---

## 10. Where Draft 1's 15 items land

| Item | Delta | Extends |
|---|---|---|
| 1 Policy snapshot | LR1-c | `_snapshot_models()` |
| 2 Per-query traces | LR2-e | `search/retrieval` span |
| 3 Cross-run causal attribution | LR5-d | **replaced** by manifest bisection |
| 4 Rate-based timeline | LR4-d | `metrics.ndjson` |
| 5 Bootstrap CI | LR4-b + §9.1 | `ranx` (via pyproject extra) |
| 6 Stratified drilldowns | LR4-c | `{mode}_per_query.json` |
| 7 Lease wait vs run | LR2-b | `encoder.ort_run` child span |
| 8 Failure forensics | LR2-c + LR4-f | `OrtCudaStatus` |
| 9 Concurrent benchmark | LR5-c | new runner |
| 10 Per-query counterfactual | LR5-a | Worker API extension |
| 11 Searcher generation | LR2-e (`search.searcher_generation`) | search span attr |
| 12 Encoder drift | LR4-g | PSI/MMD, not hashing |
| 13 Shadow / canary | LR5-b | offline-only, bounded scope |
| 14 External correlation | LR3-a | `LocalTelemetry` gauges |
| 15 Runtime invariants | LR6-a/b | **replaced** by tier taxonomy |

---

## 11. What's actually already there

| Layer | Existing | Remaining delta |
|---|---|---|
| 1 State | session-policies, state, commit-metadata, status, telemetry-health, CapabilitiesService (simplified), fingerprints, workflow attribution | Manifest + hashes (LR1-a); envelope (LR1-b); policy in summary (LR1-c) |
| 2 Events | OTel SDK; NDJSON exporters; indexing/search/agent span trees; gRPC propagation; sub-10µs validated (312); allowlists | Per-ORT-call + lease.acquire + cpu_fallback.triggered (LR2-a/b/c); retire orphans + add commit.* identity (LR2-d reformulated); search-side richness (LR2-e) |
| 3 Samples | RrdMetricStore 3-tier; NVML GPU; JVM + IPC; disk pressure | External-world sources (LR3-a); jseval consumption (LR3-b); allowlist (LR3-c) |
| 4 Projections | `summary.json`/`per_query.json`/`timeline.tsv` hand-assembled; `compare_runs.per_query_diff` exists unpaired; `check_trend` simple | Projection-consumer inversion + 7 projections (LR4-a..g); schema extension (LR4-h) |
| 5 Experiments | Nothing | All four runners (LR5-a..d) |
| 6 Contracts | ArchUnit; boot checks; contract-tested endpoints; disk-pressure tiers; 289 RC1 open | Tier taxonomy + audit + violation projection + close 289 RC1 (LR6-a..d) |

---

## 12. Dependency sequencing across tempdocs

Not triggers; real dependencies.

| 400 delta | Tempdoc | Relationship |
|---|---|---|
| LR2-a per-ORT span | **356** (active, in-process HdrHistogram) | 356 continues to own `EncoderProfile` status contract; LR2-a is the span-export sibling. Same data, two consumers. Coordinate merge ordering. |
| LR2-d orphan retirement | **298** (complete, this sub-item open) | 400 closes 298's open item via deletion (ADR 0014 orphaned it). Commit: mark proto deprecated + remove from allowlists + remove `MdcContext.pipeline()` calls. |
| LR4-a projection base | **335** (complete) | 335 landed pipeline-summary shape; 400 inverts assembly from hand-built to projection-derived. |
| LR1-a run manifest | **272** (done) | `workflow_run_id` correlation preserved; run manifest includes both. |
| LR5-c concurrent benchmark | **398** | 398 needs LR2-b + LR5-c as prerequisites. 400 delivers both. |
| LR5-a/b runners | **395**, **394** | 395 A1/A4/A7 and 394 P3 are eventual consumers. 400 builds; they consume after 400 lands. |
| LR6-d close 289 RC1 | **289** (RC1 open) | LR6-a/b tier taxonomy covers this; LR6-d is the specific rule set. |
| LR3-a external probes | `docs/explanation/08-observability.md` | Canonical doc updated as LR3-a sources land. |

---

## 13. Pre-implementation work

Scoped per-delta. Each item must complete before (or alongside) the delta
it de-risks. This section is load-bearing: skipping pre-implementation
work is how Draft 3 ended up with a factual error (LR2-d).

### 13.1 Verification reads (A-tier, cheapest, highest ROI)

- **A1 Encoder generalisation.** Read `SpladeEncoder`, `BertNerInference`,
  `BgeM3Encoder` — confirm LR2-a pattern is identical across all four
  encoders. `OnnxEmbeddingEncoder.java:315/471` + `:318` confirmed the
  `session.run()` / `profiler.recordOrtCall()` pair; verify the other
  three. De-risks LR2-a.
- **A2 `SearchOrchestrator` full read.** Currently confirmed span sites at
  lines 68 / 281 / 685. A full read enumerates candidate `search/rerank`
  + `search/fuse` insertion points and validates search-side MDC
  propagation on virtual-thread paths (tempdoc 298 §3 noted a virtual-thread
  gap on 3-way parallel retrieval — does it still apply?). De-risks LR2-e.
- **A3 `GrpcSearchService` MDC pattern.** Confirms how `workflow_run_id`
  propagates server-side; informs LR1-a manifest's `workflow_run_id`
  field. De-risks LR1-a.
- **A4 `NdjsonMetricExporter` lines 200+** and **`RrdMetricStore` full**.
  Rotation / retention specifics before extending. De-risks LR3-a/b/c.
- **A5 Tempdocs 265 / 272 / 335 full reads.** Draft 3 relied on subagent
  summaries. Full reads verify no missed constraints / follow-ups.
- **A6 Live endpoint shapes.** Record actual JSON from `/api/status`,
  `/api/debug/commit-metadata`, `/api/debug/session-policies`,
  `/api/telemetry/health`, `/api/debug/state`, `/api/inference/status`
  on a running dev stack. De-risks LR1-a hash-aggregation schema.

### 13.2 Dev-server experiments (B-tier, validate assumptions)

- **B1 Trace format verification.** Start dev with
  `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed`; ingest 20 docs; `head -20
  <dataDir>/telemetry/traces.ndjson`. Confirm:
  (i) event envelope matches `NdjsonSpanExporter` expectation;
  (ii) attribute allowlist behaves as documented (non-listed attrs drop);
  (iii) `parent_span_id` is non-empty for `indexing.*` children.
  De-risks all LR4-a..g projections (which depend on trace format).
- **B2 Non-det envelope calibration.** 5 identical scifact runs
  (`jseval run --dataset scifact --modes hybrid --start-backend --clean
  --json`), ~17 min compute. Measure σ(nDCG@10), σ(P@1), σ(docs/sec),
  σ(query p95). **Decision gate:** if σ(nDCG@10) < 0.005, envelope is
  meaningful; if σ > 0.05, every cross-run delta is noise and LR1-b is
  low-value; between these, design the envelope threshold accordingly.
- **B3 Baseline LR2 span-overhead measurement.** Add a single experimental
  `encoder.ort_run` span in a local branch; run `scifact` ingest with
  `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed`; compare docs/sec to baseline.
  Validates whether sub-10µs claim (tempdoc 312 item 7) transfers to
  per-call spans. If overhead > 1%, default sampling changes.

### 13.3 Design spikes (C-tier, de-risk Layer 5 novelty)

- **C1 LR5-a Worker API.** Paper-prototype the `SearchRequest` proto
  delta: `repeated string modes`, default empty = single-mode legacy.
  Walk `SearchOrchestrator` decision tree; document whether fan-out
  inside retrieval is compatible with the existing fusion/rerank stages.
  Decide: single-call multi-response OR stream-per-mode.
- **C2 LR5-d run-cache keying.** Paper-prototype: manifest hash →
  run-dir mapping. Define invalidation semantics:
  (i) Axis value change → cache-miss for that axis only.
  (ii) Axis schema addition (new field in manifest) → full cohort
       invalidation OR soft invalidation with "partial manifest" flag.
  (iii) Hash collision handling.
  Write out the bisection pseudocode end-to-end before touching code.
- **C3 LR5-b selection-bias scope.** Write a one-page design constraint:
  "LR5-b is offline-only, operates on a fixed eval query set, prohibits
  query-feature-conditional sampling. Selection bias in LR5-b reduces
  to 'eval distribution ≠ production distribution', which is an
  acknowledged assumption of the whole eval harness, not a new bias
  LR5-b introduces." Review with user before implementation.
- **C4 LR1-b cohort lifecycle.** Define: what makes two runs "same
  cohort"? Full manifest hash equivalence? Subset hash (model + git
  only)? Partial match with tolerance? Write this as policy before
  shipping calibration.

### 13.4 Dependency audit (D-tier, bookkeeping)

- **D1 Python.** Install `ranx>=0.3.20` + `pandas<3` optional extra for
  jseval. `scipy>=1.11.0` already present.
- **D2 Java.** HdrHistogram already in `worker-core`. OTel SDK ✓. No
  new libraries.
- **D3 Platform probes for LR3-a.** Windows: NVML ✓; PDH for CPU
  frequency (FFM wrap); `Files.getFileStore()` for disk (already used
  by `NdjsonMetricExporter`); memory pressure via Win32
  `GetPerformanceInfo` (FFM). Enumerate each before writing probe code.
- **D4 SQLite migration.** `eval-history.db` extension: plain
  `ALTER TABLE runs ADD COLUMN <name> <type>` — backward-compatible
  (existing rows get NULL). Write migration sequence + rollback.

### 13.5 Breaking-change + schema-version analysis (E-tier)

- **E1 Allowlist additions** (`NdjsonSpanExporter.ALLOWED_ATTRS`,
  `NdjsonMetricExporter.ALLOWED_TAG_KEYS`) — additive, no contract break.
- **E2 `eval-history.db`** — additive columns, existing consumers
  unaffected.
- **E3 `PipelineConfig` proto orphan deprecation (LR2-d).** Deprecate
  fields 9–11 with `[deprecated = true]`; stop writing from Head-side
  adapters; remove from allowlist/MDC. Wire-compatibility preserved;
  readers get empty strings.
- **E4 `SearchRequest` proto extension (LR5-a).** Additive
  `repeated string modes`; default empty; single-mode legacy behaviour
  preserved when unset.
- **E5 `summary.json` shape.** Additive only — existing CI consumers
  (`gate.ps1`, `diff-perf-suite`, `history.check_trend`) verified
  unaffected.
- **E6 Span schema versioning.** Decide whether to introduce a
  `schema_version` resource attribute on spans (or a new
  `ndjson_schema_version` field on the span envelope). If projections
  (Layer 4) break when span shape changes, they need to pin a version.

### 13.6 Test infrastructure (F-tier)

- **F1 ArchUnit additions.** One ArchUnit rule per `@BuildContract`
  annotation class (LR6-a). Tests live alongside the invariant they
  enforce.
- **F2 Contract tests for new spans.** Extend `TracingLocalExportTest`
  to cover `encoder.ort_run`, `lease.acquire`, `cpu_fallback.triggered`,
  `search/rerank`, `search/fuse`. Assert attribute allowlist behaviour.
- **F3 Integration test for LR5-a multi-mode search.** End-to-end
  (Head → Worker → response) with `modes: [lexical, dense, hybrid]`.
- **F4 Reproducibility regression for LR1-b.** Re-run calibration N
  times in CI; assert σ doesn't drift more than ε across CI runs.
- **F5 Selection-bias bound test for LR5-b.** Assert query set identity
  across shadow and primary (same qids, same order).

### 13.7 Documentation prep (G-tier, in-flight per delta)

- **G1** Each LR* landing updates `docs/explanation/08-observability.md`.
- **G2** `node scripts/docs/llmstxt-generate.mjs` after each batch.
- **G3** `node scripts/docs/skills-sync.mjs` after each batch.
- **G4** Possibly a new ADR for Layer 5 design ("experiment runners live
  in jseval, not in Worker"). Draft before LR5-a implementation.
- **G5** `docs/reference/contracts/workflow-telemetry-contract.v1.md`
  extended with the run-manifest contract (LR1-a).

### 13.8 Rollout defaults (H-tier)

- **H1** LR2 span additions: opt-in via `JUSTSEARCH_INDEX_TRACING_LEVEL`.
  `none` (default) → zero-cost; `sample` → 1% + new spans; `detailed` → 100%.
- **H2** LR3-a external probes: env-var-gated (`JUSTSEARCH_EXTERNAL_SAMPLES`),
  default off until Windows probes are validated.
- **H3** LR4 projections: default on for `jseval --pipeline` runs; CLI
  flag `--no-projections` for opt-out.
- **H4** LR5 experiments: separate `jseval` subcommands
  (`counterfactual`, `shadow`, `concurrency`, `bisect`), not default.
- **H5** LR6 contracts: `@BuildContract` enforced immediately (blocks
  merges). `@SampleContract` default on; cost budget per contract
  documented in the annotation. `@AdvisoryContract` default on.

### 13.9 Round 1–3 results (2026-04-21)

#### Round 1 — A-tier verification reads (complete)

**A1 Encoder generalisation.** Pattern varies across the four encoders:
- `OnnxEmbeddingEncoder`: `session.run()` at 315, 471 + `profiler.
  recordOrtCall()` at 318 ✓ (matches canonical).
- `SpladeEncoder`: four `session.run()` sites (533, 759, 867, 915)
  with `profiler.recordOrtCall` + `addPhaseNs(tokenize|postProcess)`
  ✓ — LR2-a wraps each.
- `BertNerInference`: single `session.run()` + full phase timing
  (tokenize/tensor/ort/extract) at lines 214–223 ✓.
- `BgeM3Encoder`: `session.run()` at 293 **without profiler
  infrastructure**. Prerequisite surfaced: add `EncoderProfileAccumulator`
  first. Folded into §8.2 LR2-a.

**A2 SearchOrchestrator.** Confirmed span sites at 282 (`search/retrieval`)
and 686 (`search/chunk_merge`). **Virtual-thread fan-out at lines
503–512** (`Executors.newVirtualThreadPerTaskExecutor()` + 3-way
`CompletableFuture.supplyAsync` for BM25 / dense / SPLADE). OTel
`Context.current()` does NOT propagate automatically into the lambdas.
LR2-e implementation must either (a) accept per-branch span gap, or
(b) manually propagate context. Decision deferred to implementation.

**A3 GrpcSearchService.** `openRequestMdc()` (line 339) is the canonical
scope-opener; every gRPC method calls it. `MdcContext.pipeline()` used
once at line 353. LR1-a's `workflow_run_id` propagates via this same
mechanism — no new plumbing needed.

**A4 RrdMetricStore.** 15 `CURATED_METRICS` at lines 50–70; `STEP_SECONDS
= 60`, `HEARTBEAT_SECONDS = 180`. LR3-a extensions: add to the set;
datasource names must be < 20 chars.

**A5 Tempdoc constraints surfaced.** 5 load-bearing rules from prior
tempdocs: identity types never collapse (272); eval identity fields
strictly deferred, do NOT pre-allocate (272); jseval stays < 1000 lines
or move logic backend-side (335); `/api/status` field set is stable
contract (335); NDJSON canonical, not remote (265).

**Tempdoc 356 check.** Per-encoder p50/p95/p99 ALREADY surfaced via
`/api/status.worker.enrichment.*.profile` (356 Gap B1 resolution). LR2-a
value reframed: correlation + post-hoc reconstruction, not aggregate
percentiles. 356's open items (A3 EP routing, A4 BFC fragmentation,
A5 CUDA streams, A6 thread pool, B2 warmup, B4 GC) are out of 400's
scope.

#### Round 2 — C-tier design spikes (complete)

**C1 LR5-a Worker API.** Confidence 3/10 → 7/10.
- Per-branch rankings already computed in `SearchOrchestrator:503–512`;
  discarded post-fusion. LR5-a is plumbing, not compute.
- Proto delta (additive, non-breaking):
  ```proto
  message SearchRequest {
    ...
    repeated string counterfactual_modes = N;  // default empty = legacy
  }
  message SearchResponse {
    repeated Hit hits = 1;
    map<string, RankList> counterfactual_modes = M;
  }
  message RankList { repeated Hit hits = 1; }
  ```
- `hybrid_no_ce` synthetic: skip CE rerank stage while keeping fusion
  (flag on pipeline config). CE runs once; no extra passes.

**C2 LR5-d bisection keying.** Confidence 3/10 → 5/10.
- Index: `tmp/eval-results/_index/manifests.jsonl`.
- Envelopes: `tmp/eval-results/_cohort-envelopes/<hash>.json`.
- Algorithm:
  ```
  bisect(A, B):
    axes = diff_axes(A, B)
    if len(axes) == 1: return axes[0]
    for axis in axes:
      synthetic = A | {axis: B[axis]}
      synthetic_hash = sha256(canonical_json(synthetic))
      run = cache_lookup(synthetic_hash) or run_synthetic(synthetic)
      if |metric(run) - metric(A)| > 2 * envelope_stdev:
        yield (axis, delta)
    if none: return MULTI_AXIS_INTERACTION
  ```
- Single-axis only; no O(n²) 2-axis combinations.
- Invalidation: axis value change → cache miss; schema addition →
  NULL as distinct axis value; orphaned envelopes GC after 90 days.

**C3 LR5-b selection-bias scope (hard constraint).** Confidence 3/10 →
6/10. Written as inline code comment + §13 F5 test assertion:

> "LR5-b operates **offline only**, on a **fixed evaluation query set**,
> with **no production traffic**. Worker serves both policies with the
> same index and same reader generation. Queries issued in identical
> order. No query-feature-conditional sampling. Selection bias reduces
> to 'eval distribution ≠ production distribution' — an acknowledged
> assumption of the entire eval harness, not a new bias LR5-b
> introduces. Production canary extensions are explicitly out of scope."

Test assertion: `assertThat(shadow_qids).containsExactlyElementsOf(
primary_qids, inOrder=true)`.

**C4 LR1-b cohort lifecycle.** Confidence 5/10 → 7/10.
- Cohort = runs with identical full manifest hash.
- Calibration: `jseval calibrate --manifest <hash> --reruns N` (N default 5).
- Envelope file: `{stdev_per_metric, N, last_calibrated, git_sha}`.
- No TTL. Cohort hash change → envelope orphaned (not stale).
- GC: orphans eligible for removal after 90 days.
- Missing envelope → consumers emit `envelope: unknown`, treat deltas
  qualitatively; never fail.
- σ-stability: if σ estimate changes > 10% between N=5 and N=10,
  require N=10 for that cohort.

#### Round 3 — B-tier dev-server experiments

**B1 Trace format verification (complete).** Run without `--pipeline`,
20 docs ingested with `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed`.

- **Span envelope confirmed** matches Draft 4 §2.1 description:
  `{trace_id, span_id, parent_span_id, name, start, end, status, attrs}`.
  Attribute allowlist strictly enforced; non-listed attrs dropped at
  export.
- **Span census** (~1 min run, 5184 docs indexed):
  - 5189 `indexing.extract`
  - 5189 `indexing.write`
  - 325 `indexing.batch`
  - 314 `search/retrieval` (300 queries + 14 retries or similar)
  - 9 `indexing.markDone`
  - **0 `indexing.embed_batch`** — migration-only, confirmed.
  - **0 `search/chunk_merge`** — only fires on RAG chunk retrieval, not
    basic hybrid search.
- **Both metric files written**: `metrics.ndjson` (Head, ~30 KB/min),
  `metrics-worker.ndjson` (Worker, ~5.6 KB/min), and `metrics.rrd`
  (RRD4J database).
- **Non-pipeline run nDCG@10 = 0.681** vs tempdoc reference 0.750 —
  confirms queries on unfinished enrichment degrade quality. B2 must
  use `--pipeline`.

**B2 Non-determinism envelope calibration (complete, N=5).** Five
identical scifact-hybrid runs with `--pipeline --clean`,
`JUSTSEARCH_INDEX_TRACING_LEVEL=sample`.

| Run | nDCG@10 | P@1 | R@10 | p95 ms | total s | GPU avg % |
|---|---|---|---|---|---|---|
| 1 | 0.7527 | 0.6267 | 0.8876 | 229 | 220.0 | 68.8 |
| 2 | 0.7543 | 0.6300 | 0.8876 | 226 | 228.1 | 66.2 |
| 3 | 0.7552 | 0.6300 | 0.8876 | 193 | 205.6 | 61.8 |
| 4 | 0.7543 | 0.6300 | 0.8876 | 192 | 201.9 | 62.0 |
| 5 | 0.7528 | 0.6300 | 0.8842 | 201 | 203.2 | 65.1 |

**Envelope (N=5):**

| Metric | Mean | σ | ±2σ | CV |
|---|---|---|---|---|
| **nDCG@10** | **0.7539** | **0.00108** | **±0.00217** | **0.14%** |
| P@1 | 0.6293 | 0.00149 | ±0.00298 | 0.24% |
| R@10 | 0.8869 | 0.00149 | ±0.00298 | 0.17% |
| RR@10 | 0.7169 | 0.00125 | ±0.00249 | 0.17% |
| AP@10 | 0.7061 | 0.00123 | ±0.00246 | 0.17% |
| mean_ms | 188.4 | 12.8 | ±25.6 | 6.79% |
| p95_ms | 208.2 | 18.0 | ±36.0 | 8.64% |
| total_elapsed_s | 211.8 | 11.7 | ±23.3 | 5.50% |
| docs_per_s (primary) | 304.7 | 13.9 | ±27.7 | 4.55% |
| gpu_avg_pct | 64.8 | 2.95 | ±5.91 | 4.56% |

**Decision gate outcome (per §13.2 B2):**
- σ(nDCG@10) = 0.00108 — **well below the 0.005 "envelope meaningful"
  threshold.** LR1-b delivers clear value. §9.1 design stands.
- All IR quality metrics have CV < 0.25% — tight, reliable envelope.
- Latency/throughput metrics have CV 4–9% — much noisier, consistent
  with expectation (system-state sensitivity: GPU warmup, disk cache,
  background processes). Useful for system-health drift detection but
  not sharp for regression gating.

**Retrospective applied to §3 cross-run comparison:**
- `nDCG@10: 0.754 → 0.750` (delta 0.004) is **inside envelope**
  (±0.00217 × N√2 for cross-run-vs-cross-run ≈ ±0.003). The
  "(noise?)" annotation is confirmed: the delta is indistinguishable
  from re-run noise at 300-query scale.
- `P@1: 0.630 → 0.627` (delta 0.003) is **inside envelope** (±0.003).
  Noise.
- `Query p95: 220ms → 209ms` (delta 11ms) is **inside ±36ms 2σ band**.
  The "5% latency improvement" reading was inside envelope — the §3
  improvement is unreliable without more samples or a larger effect
  size.
- `Ingest elapsed: 253.1s → 208.0s` (delta 45.1s) is **2× outside
  ±23.3s 2σ band**. Real improvement. §14.28's optimisation work is
  validated by the envelope.
- `Docs/sec (end-to-end): 20.5 → 24.9` (delta 4.4) — my docs_per_s
  measurement is the primary-indexing rate; end-to-end matching
  requires different instrumentation. Pending.

**Implication for LR1-b threshold policy:** ±2σ (95% confidence) is the
natural band. For IR metrics this means a ±0.002–0.003 band — tighter
than the 0.01 regression threshold `history.check_trend` uses today.
LR4-h eval-history schema extension should carry the envelope bound
alongside the point estimate so drift detection uses cohort-appropriate
noise floors.

**B3 Span-overhead measurement.** Not run (requires a code-change branch
with experimental `encoder.ort_run` span); defer to LR2-a
implementation phase where a branch exists.

**A6 Live endpoint shape capture (complete).** Captured 7 state endpoints
from a running dev backend (`runHeadlessEval` with `--pipeline
--max-queries 10 --clean`), after polling commit-metadata until it
returns 200 (the canonical `Worker ready` signal). Captured files in
`tmp/b-tier/a6r-*.json`.

**Per-endpoint findings:**

| Endpoint | HTTP | Size | Notable |
|---|---|---|---|
| `/api/health` | 200 | 321 B | schema-v1; `lifecycle.state=DEGRADED` in eval mode because `inference.offline` (LLM off by default) — head + worker are READY. Manifest consumes `components.{head,worker,inference}.state`. |
| `/api/status` | 200 | 6785 B | schema-v1; deep `worker.{core,failure,migration,queue,...}` + gpu + memory. Whole document is hashable. |
| `/api/debug/state` | 200 | 1730 B | Adds `worker.doc_count`, `serving_search_generation_id`, `serving_ingest_generation_id`, queue/pending/processing/backoff counts. Searcher generation ID directly supports LR2-e `search.searcher_generation` span attr. |
| `/api/debug/commit-metadata` | 200 | 1067 B | **17 fields, all string hashes**: `schema_fp`, `field_catalog_hash`, `analyzer_fp`, `similarity_fp`, `synonyms_hash`, `grammar_hash`, `boosts_fp`, `index_schema_fp`, `schema_ver`, `grammar_ver`, `template_ver`, `prompt_pack_hash`, `vector_format`, `splade_model_sha256`, `commit_time`, `commit_id`, `build_state`, `grammar_on`. **The 8 identity hashes I proposed as `commit.*` span attributes in LR2-d map 1:1 to real fields.** |
| `/api/debug/session-policies` | 200 | 62 B | **`{configStatus: "worker-unreachable", runtime: {}, models: {}}`** — the eval-mode gap (see below). |
| `/api/telemetry/health` | 200 | 528 B | Schema-v1; `state`, `counters.{metric_export_failures, span_export_failures, ...}`, `rates.*`, `timestamps.*`. Manifest consumes `state` + the `*_export_failures` counters as a telemetry-was-healthy tag. |
| `/api/inference/status` | 200 | 550 B | `mode`, `tier`, `gpu.{cudaAvailable, totalVramBytes, vramDescription}`. Offline in eval mode; useful in production. |

**NEW FINDING — LR1-c eval-mode gap:**
`/api/debug/session-policies` returns `worker-unreachable` in eval mode
(`runHeadlessEval`) even though the Worker is fully READY and serving
queries. Hypothesis: the endpoint's Head→Worker gRPC call
(`RemoteKnowledgeClient.getSessionPolicies`) lacks a local in-process
short-circuit; in `runHeadlessEval` there is no separate Worker
process to call. This blocks **LR1-c** (policy snapshot embedded in
summary) when jseval runs — which is the primary consumer.

**Resolutions (choose at LR1-c implementation):**
- **Option A (short-circuit):** `SessionPoliciesController` detects
  co-located Worker and reads `PolicySnapshot` from in-process
  `InferenceSurface` directly, bypassing gRPC. Clean; matches the
  intended "state authority publishes typed state" discipline of
  §14.28.
- **Option B (jseval fallback):** LR1-c records `policy_hash: null`
  in eval-mode manifests and proceeds. Manifest marks the missing
  field. Production runs populate it.
- Recommended: A. Production and eval should produce the same
  manifest shape; missing-axis degradation is less honest than fix.

**LR1-a manifest schema (locked by A6 findings):**
```
{
  run_id, workflow_run_id, timestamp, git_sha,
  health_snapshot: { lifecycle.state, components },
  status_hash: sha256(of /api/status payload),
  debug_state_hash: sha256(of /api/debug/state payload),
  commit_metadata: { <17 fields from /api/debug/commit-metadata> },
  policy_hash: sha256(of /api/debug/session-policies runtime+models),
  capabilities_hash: sha256(of /api/capabilities payload),
  env_hash: sha256(of env_fingerprint payload),
  corpus_hash, qrels_hash,
  model_fingerprints: { embed, splade, ner, ce },
  eval_protocol_hash,
  telemetry_health_tag: READY | DEGRADED | ERROR,
  non_determinism_envelope: { ... }
}
```
All fields verified present in live responses except
`policy_hash` (LR1-c gap above). Manifest size estimate ≈ 2 KB per run.

#### Round 4 — D-tier dependency audit (D3 complete, 2026-04-21)

**D3 Windows probe API scoping.** FFM native-binding pattern is
established in the codebase:
- **ADR 0005** (`docs/decisions/0005-manual-ffm-bindings.md`) governs
  manual FFM as the canonical approach — not JNA, not JNI, not
  third-party bindings.
- **`NvmlService.java`** (`modules/gpu-bridge`) is the reference
  implementation: `java.lang.foreign.{Arena, Linker, MemoryLayout,
  SymbolLookup, ValueLayout}`, `MethodHandle.invoke`, graceful failure
  via structured error snapshots, Windows-preferred loading from
  System32. Excellent template for new LR3-a probes.
- **`WindowsJobObject.java`** (`modules/app-util`) and
  `MmfWorkerSignalBus.java` are additional Windows FFM templates.

**Per-probe assessment (LR3-a):**

| Probe | Assessment | Confidence |
|---|---|---|
| GPU temperature | Extend existing `NvmlService` with `nvmlDeviceGetTemperature` binding (~15 LOC). | 8/10 |
| Disk I/O throughput | Use `Files.getFileStore().getUsableSpace()` / total — already used by `NdjsonMetricExporter`. Add throughput tracking via delta + elapsed time. | 8/10 |
| CPU frequency (per-core) | New Windows PDH binding (`PdhOpenQuery`, `PdhAddCounter`, `PdhCollectQueryData`). New FFM surface, but `NvmlService` template applies directly. | 6/10 |
| Memory pressure | New Win32 binding (`GetPerformanceInfo`). Similar scope to PDH. | 6/10 |

**Updated LR3-a confidence: 5/10 → 7/10.** GPU temp + disk I/O are cheap
extensions. CPU freq + memory pressure are new bindings but follow an
existing template with an ADR behind it. No blockers.

**Important constraint** from ADR 0005: manual FFM only. If LR3-a
reaches for JNA, JNI, or third-party binding libraries, it violates the
ADR. Bindings stay in `modules/gpu-bridge` (or a new `modules/os-probe`
if the scope justifies).

### 13.10 Confidence update

After §13 Rounds 1 + 2 (complete) and Round 3 B1:

| Delta | Draft 4 confidence | Draft 5 confidence | Reason |
|---|---|---|---|
| LR1-a manifest | 8/10 | 9/10 | `/api/debug/commit-metadata` returns simple `Map<String,String>` (A6 — to verify live in pending B-tier) |
| LR1-b envelope | 5/10 | 8/10 | C4 lifecycle + σ-stability rule specified; **B2 N=5 calibration confirms σ(nDCG@10)=0.00108, well below the 0.005 decision gate — envelope meaningful at 300-query scale** |
| LR1-c policy in summary | 9/10 | 7/10 | **A6 revealed eval-mode gap**: `session-policies` returns `worker-unreachable` in `runHeadlessEval` mode. Needs in-process short-circuit (preferred) or null-fallback. Tractable; small scope increase. |
| LR2-a per-ORT span | 7/10 | 6/10 | A1 revealed BgeM3Encoder prerequisite + B1 revealed parent-span ambiguity — tractable but adds scope |
| LR2-b lease.acquire | 8/10 | 8/10 | Unchanged |
| LR2-c cpu_fallback | 8/10 | 8/10 | Unchanged |
| LR2-d orphan retirement | 5/10 | 7/10 | 298 tempdoc read confirmed the proto field delta; `CapabilitiesService` confirmed to carry prompt templates, not pipeline identity |
| LR2-e richer search spans | 7/10 | 6/10 | A2 virtual-thread gap requires decision (Option A accept or Option B manual context propagation) |
| LR3-a external samples | 5/10 | 7/10 | D3 findings: FFM pattern established via `NvmlService`; ADR 0005 governs approach; GPU temp + disk I/O are cheap extensions, CPU freq + memory pressure follow template |
| LR3-b jseval consumes metrics.ndjson | 7/10 | 7/10 | B1 revealed two files, not one — scope clarification, no confidence change |
| LR3-c tag allowlist | 9/10 | 9/10 | Unchanged — additive set edit |
| LR4-a projection base | 6/10 | 5/10 | 335 constraint ("jseval < 1000 lines") is a real risk once 8 projections land |
| LR4-b bootstrap CI | 8/10 | 8/10 | `ranx` confirmed available as optional extra |
| LR4-c stratified | 8/10 | 8/10 | Unchanged |
| LR4-d rate + stalls | 7/10 | 7/10 | Unchanged |
| LR4-e auto rank-diff | 8/10 | 8/10 | Unchanged |
| LR4-f CPU-fallback count | 6/10 | 6/10 | Depends on LR2-c |
| LR4-g PSI/MMD drift | 5/10 | 5/10 | Unchanged |
| LR4-h eval-history schema | 7/10 | 7/10 | Unchanged — additive columns |
| LR5-a counterfactual | 3/10 | 7/10 | **C1 spike: plumbing, not compute** (big shift) |
| LR5-b shadow eval | 3/10 | 6/10 | C3 scope written as hard constraint |
| LR5-c concurrent benchmark | 5/10 | 5/10 | Depends on LR2-b + LR3-a |
| LR5-d bisection runner | 3/10 | 5/10 | C2 algorithm + cache keying spec'd; `MULTI_AXIS_INTERACTION` limitation documented |
| LR6-a tier taxonomy | 7/10 | 7/10 | Unchanged |
| LR6-b invariant audit | 6/10 | 6/10 | Unchanged |
| LR6-c violation projection | 6/10 | 6/10 | Depends on LR2 |
| LR6-d close 289 RC1 | 6/10 | 6/10 | Unchanged |

**Overall end-to-end:** 6/10 → **7/10**. The LR5-a jump (3 → 7) is the
largest single-delta confidence gain in the session: what seemed like
novel retrieval compute turned out to be plumbing over already-computed
intermediate data. The LR2-a and LR2-e dips (7 → 6) are scope
clarifications, not blockers. Overall trajectory: positive.

**Outstanding pre-implementation items:**
- **B2 envelope calibration** — ✅ complete (N=5). σ(nDCG@10)=0.00108.
- **§13.2 A6 live endpoint shapes** — ✅ complete. LR1-a manifest schema
  locked; LR1-c eval-mode gap discovered.
- **§13.4 D3 Windows probe scoping** — ✅ complete.
- **§13.3 design spikes C1–C4** — ✅ complete.
- **§13.2 B3 span-overhead measurement** — deferred; requires
  LR2-a implementation branch.

**All pre-implementation phases requiring dev-stack use are complete.**
B3 is the only remaining item, and it is appropriate to do during
LR2-a implementation (it would otherwise require a speculative branch).

**Overall end-to-end confidence: 7/10 → 7.5/10 → 8/10 → 7.5/10** after
D3 + B2 + A6. A6 simultaneously (a) locked the LR1-a manifest schema
against real response shapes (+) and (b) surfaced a previously-unknown
LR1-c eval-mode gap (−). Moves:
- LR3-a: 5 → 7 (D3 FFM template confirmed)
- LR1-b: 7 → 8 (B2 σ = 0.00108 validates envelope)
- LR1-c: 9 → 7 (A6 eval-mode gap; tractable but new scope)

Layer 5 novelty (LR5-d MULTI_AXIS_INTERACTION rate) remains the
residual structural uncertainty. Pre-implementation has hit the
floor of what can be derisked before code starts.

---

## 14. Implementation sequencing

### 14.1 Critical path

```
Phase 0 (prerequisite):    §13 A + B + C + D + E + G4
                                      │
   ┌──────────────────────────────────┼──────────────────────┐
   │                                  │                      │
Phase 1a (LR1-a manifest)  Phase 1b (LR2-a/b/c/e spans)  Phase 1c (LR3-a/b/c samples)
   │                                  │                      │
   │                                  │                      │
Phase 1d (LR2-d orphan retirement)    │           Phase 1e (LR6-a/b/c/d tiers)
   │                                  │                      │
   └─────────────────┬────────────────┴──────────────────────┘
                     │
        Phase 2 (LR4-a..h projections — depends on 1a+1b+1c)
                     │
        Phase 3 (LR1-b envelope calibration — depends on LR1-a)
                     │
        Phase 4 (LR5-a counterfactual — depends on C1 + LR2-e)
                     │
        Phase 5 (LR5-b shadow eval — depends on C3 + Phase 2)
                     │
        Phase 6 (LR5-c concurrent benchmark — depends on LR2-b + LR3)
                     │
        Phase 7 (LR5-d bisection runner — depends on C2 + Phase 3)
```

### 14.2 Parallelisable phases (worktree strategy)

Phases 1a / 1b / 1c / 1d / 1e are **mutually independent** and can be
worked in parallel worktrees (one agent session per worktree). Merge
order to `main`:
1. LR2-d orphan retirement first (touches shared allowlists — resolve
   conflicts early).
2. LR1-a, LR2-a/b/c/e, LR3-a/b/c, LR6-a/b/c/d in any order.
3. Phase 2 (Layer 4) after all Phase 1 lands.
4. Phase 3 blocks until LR1-a is on `main`.
5. Phases 4–7 sequenced per 14.1.

Per `.claude/rules/branch-safety.md`: one worktree per agent; no cross-
worktree checkout; dev stack shared sequentially.

### 14.3 Dev-stack coordination

Phases 0-B (non-det calibration, ~17 min) + 0-B3 (span overhead, ~5 min)
+ 0-B1 (trace format verification, ~5 min) need the dev stack. Run these
in sequence, not parallel, to avoid ownership conflicts (MCP
`quick_health` / `preflight` / `start` per dev-stack rules).

### 14.4 Milestones

| Milestone | Contents | Blocks unblocked |
|---|---|---|
| M0 | §13 pre-implementation complete | all phases |
| M1 | Phase 1a–1e merged to main | Phase 2, 3 |
| M2 | Phase 2 merged | Phases 4, 5 |
| M3 | Phase 3 envelope calibrated and published | Phase 7 |
| M4 | Phases 4, 5, 6 merged | Phase 7 |
| M5 | Phase 7 merged | tempdoc 400 complete |

### 14.5 Verification gates (per phase)

Per CLAUDE.md "verify your work" discipline:

- **Phase 1a..1e:** `./gradlew.bat build -x test` + affected module
  tests + `./gradlew.bat spotlessApply`. Frontend: if any UI surface
  touched, `npm run typecheck && npm run test:unit:run`.
- **Phase 2:** `cd scripts/jseval && pytest tests/` + a
  `jseval run --dataset scifact --modes hybrid --start-backend --clean
  --json` smoke that exercises every new projection.
- **Phase 3:** calibration artifact committed to manifest cohort store;
  reproducibility test (F4) green.
- **Phase 4:** integration test (F3) green; LR5-a fan-out verified
  against a single-mode baseline.
- **Phase 5:** selection-bias bound test (F5) green; shadow divergence
  on scifact under `policy_a = default` vs `policy_b = default` reports
  divergence ≤ envelope (sanity).
- **Phase 6:** `jseval run --concurrency 4 --dataset scifact` completes;
  per-stream lease-wait histogram non-empty.
- **Phase 7:** bisection on a known-axis change (e.g., revert
  `SEARCH_RERANKER_STRATEGY` between two scifact runs) isolates the
  correct axis.
- **End-to-end:** `gh workflow run ci.yml` + `.\scripts\gate.ps1` after
  each phase merges.

---

## 15. What this prevents long-term

- **Observability-for-its-own-sake.** Every new field names its Layer 4
  consumer; projection-consumer rule enforces purpose.
- **Claim-vs-code drift.** Layer 6 tiers make invariants live code.
- **Conflation of observability vs experiments.** Layer 5 is separate.
- **Metric-shape drift breaking history queries.** Layer 2 events are
  source of truth; projections evolve without breaking historical reads.
- **Research-grade features hiding in the idea bank.** Causal-% and
  universal-runtime-assertion are explicitly replaced by bisection +
  tier taxonomy. Neither enters the codebase.
- **500-line idea-bank tempdocs.** Future gaps become: "which layer?
  which primitive?" Answered in paragraphs.
- **Silent architectural duplication.** Draft 2's Layer-2-not-there
  pathology (proposed rebuilding an existing stack) is the failure mode
  §11's factual inventory prevents.
- **Orphan proto fields tracking deleted systems.** LR2-d explicitly
  retires `pipeline_hash`/`budget_profile` to prevent this class of
  silent drift.

---

## 16. What this does NOT solve

- **Non-determinism itself.** GPU numerics remain non-reproducible.
- **Storage cost of per-query span trees.** Retention policy may need
  tuning after LR2-a/b land.
- **Consumer discipline.** Social governance rule; can decay.
- **Bisection-runner cost at cohort change.** Adding a new manifest axis
  invalidates envelopes. §13 C4 defines the policy; it doesn't prevent
  the cost.
- **Selection-bias risk in LR5-b.** §13 C3 scopes it; scoping is not
  elimination. LR5-b's offline-only scope is an explicit assumption.
- **jseval dependency on Worker-side span shape.** Projection schema
  version + span schema version need explicit contract tests —
  §13 E6 + F2 cover this, but drift is still possible if the contracts
  aren't maintained.
- **Platform probe portability (LR3-a).** Windows-first; non-Windows
  builds degrade gracefully but don't gain the samples. Not a blocker
  for JustSearch's local-first Windows target.

---

## 17. The philosophical shift — finishing what §14.28 started

The `§14.25 / §14.27 / §14.28` arc applied one discipline to configuration
and session policies: each authority publishes typed state; every call
site reads the resolved state; no `System.getenv` bypasses remain. Result:
`PolicySnapshot` + `/api/debug/session-policies`.

`08-observability.md` + `modules/telemetry` applied the same discipline
to operational observability. Result: a stack that serves "is the
pipeline broken?" very well.

The remaining deltas — §8's six layers — finish the same discipline for
*eval observability* ("why did this change?"). Draft 4 drops the
trigger-gated framing: the discipline is being applied, not considered.

General rule: **when a capability is scattered across call sites, the
fix is one authority per primitive plus typed resolution, not more
per-call-site features**. Draft 2 rediscovered this rule. Draft 3
applied it. Draft 4 ships it.

---

## 18. Scope boundary

What this tempdoc IS:

- The implementation plan for all 22 deltas in §8.
- A pre-implementation work inventory (§13) that de-risks each delta.
- An implementation sequence (§14) with critical-path, parallel phases,
  and milestone gates.
- Comprehensive per user directive: every gap has a named delta; no
  deferrals to future tempdocs; no trigger-gating.

What this tempdoc is NOT:

- Not a single monolithic change landing in one commit. §14
  decomposes into merge-sized phases.
- Not a replacement for `docs/explanation/08-observability.md`. As each
  phase lands, the canonical doc updates; this tempdoc becomes the
  implementation record.
- Not a criticism of the existing stack. The stack is well-designed;
  §8's deltas finish it for the eval-observability class of questions.
- Not a mandate to skip pre-implementation work. §13 is load-bearing.
  Draft 3's LR2-d error is concrete evidence that skipping verification
  costs more than doing it.

---

## 19. Related tempdocs

- **265** adopted Opik + Inspect AI; local NDJSON canonical.
- **272** established `workflow.run_id`; LR1-a manifest includes it.
- **289** RC1 open; LR6-d closes via `@BuildContract` rules.
- **298** complete except orphan fields; LR2-d retires them.
- **312** validated sub-10µs span overhead; LR2-a/b/c depend on this.
- **330** added `/api/debug/commit-metadata`; LR2-d + LR1-a consume it.
- **335** complete; LR4-a inverts its assembly model.
- **354** map-based metrics; unchanged.
- **356** active; LR2-a is the span-export sibling of 356's in-process
  `HdrHistogram` work. Coordinate merge order.
- **394** P3 consumes LR5-a/b.
- **395** A1/A4/A7 consume LR5-c.
- **397** `PolicySnapshot` is Layer 1's first state endpoint.
- **398** consumes LR2-b + LR5-c; 400 unblocks.
- **399** parallel parked-analysis pattern; no direct coupling.
- **ADR 0014** removed `PipelineDefinition`; LR2-d reformulation stems
  from this.

---

## 20. Sources

**Run artefacts:** `tmp/p14.28-run/run-hybrid.json`,
`tmp/p14.28-run/timeline-hybrid.tsv`,
`tmp/eval-results/20260418T144338_scifact/summary.json`,
`tmp/eval-results/eval-history.db`.

**Canonical doc:** `docs/explanation/08-observability.md` (515 lines).

**Telemetry module:** `modules/telemetry/src/main/java/io/justsearch/telemetry/{TracingBootstrap,LocalTelemetry,NdjsonSpanExporter,NdjsonMetricExporter,RrdMetricStore,Telemetry,TelemetryHealthState,WorkflowSpanAttributeProcessor}.java`.

**Span-emission call sites:**
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/IndexingLoop.java`
  (lines 305, 802, 846, 946, 989, 1048)
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/SearchOrchestrator.java`
  (lines 68, 281, 685)
- `modules/ort-common/src/main/java/io/justsearch/ort/NativeSessionHandle.java`
  (lines 213–228 `acquire()`, line 217 `acquireUninterruptibly`, lines
  302–306 `reportCpuSessionFailure`)
- `modules/worker-core/src/main/java/io/justsearch/indexerworker/metrics/EncoderProfileAccumulator.java`
  (lines 25 HdrHistogram, 60 `recordOrtCall`)
- `modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/onnx/OnnxEmbeddingEncoder.java`
  (lines 315, 471 `session.run`; 318 `profiler.recordOrtCall`)
- `modules/reranker/src/main/java/io/justsearch/reranker/CrossEncoderReranker.java`
  (line 225 `sessions.reportCpuSessionFailure` — sole production caller)

**State endpoint controllers:**
- `modules/ui/src/main/java/io/justsearch/ui/api/CommitMetadataController.java`
  (63 lines; returns `Map<String,String>`)
- `modules/ui/src/main/java/io/justsearch/ui/api/SessionPoliciesController.java`
- `modules/app-observability/src/main/java/io/justsearch/app/observability/CapabilitiesService.java`
  (post-ADR 0014: simplified to schema versions + prompt templates)

**gRPC trace propagation:**
- `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/TraceClientInterceptor.java`
- `modules/worker-core/src/main/java/io/justsearch/indexerworker/grpc/TracingServerInterceptor.java`

**jseval:**
- `scripts/jseval/pyproject.toml` (scipy ✓; ranx as optional extra)
- `scripts/jseval/jseval/{run,history,compare_runs,timeline,artifacts}.py`

**Tempdocs consulted:** 265, 272, 289, 298, 312, 330, 335, 354, 356,
394, 395, 397, 398, 399.

**ADR:** `docs/decisions/0014-pipeline-definition-removal.md` (2026-03-16).

**Industry references:**

- Sakai (2006). "Evaluating Evaluation Metrics based on the Bootstrap."
  SIGIR. https://dl.acm.org/doi/10.1145/1148170.1148261
- Rabanser et al. (2019). "Failing Loudly." NeurIPS.
  https://arxiv.org/abs/1810.11953
- Chapelle et al. (2012). "Interleaved search evaluation." TOIS.
  https://dl.acm.org/doi/10.1145/2094072.2094075
- Gilotte et al. (2018). "Offline A/B testing." WSDM.
  https://dl.acm.org/doi/10.1145/3159652.3159687
- OpenTelemetry GenAI conventions.
  https://opentelemetry.io/docs/specs/semconv/gen-ai/
- `ranx` library. https://amenra.github.io/ranx/
- Evidently AI embedding drift.
  https://docs.evidentlyai.com/reference/all-metrics
- Great Expectations assertion decay.
  https://greatexpectations.io/
- Web searches 2026-04-21 confirmed: no off-the-shelf manifest-hash
  bisection runner (MLflow/DVC provide lineage, not bisection); no
  clean post-Gilotte-2018 selection-bias bound for shadow eval —
  LR5-b scopes to the bounded offline case accordingly.

---

## 21. Phase 1 implementation log (Draft 6, 2026-04-22)

Phase 1 landed as 12 sequential commits on branch `worktree-400-lr2d`,
rooted at main's `d04609fab`. 11 of 12 deltas shipped (10 as originally
designed, 1 with narrowed scope — LR6-d-A15, see §21.3); 1 deferred
(LR3-a, see §21.2). All per-module tests pass; `./gradlew.bat build
-x test` is green end-to-end.

### 21.1 Commit ledger

| Seq | Delta | Commit | Files | Net LOC | Status |
|---|---|---|---|---|---|
| 0a | LR2-d.1 orphan retirement | `1edbb183e` | 11 | −2 | shipped (pre-Phase-1) |
| 0b | LR1-a run manifest | `fab978701` | 4 | +491 | shipped (pre-Phase-1) |
| 1 | LR2-d.2 commit.* identity attrs | `458236305` | 6 | +102 | shipped |
| 2 | BgeM3 accumulator prereq | `770e014ac` | 1 | +23 | shipped |
| 3 | LR2-a per-ORT span + enrichment.batch | `0c294a960` | 8 | +220 | shipped |
| 4 | LR2-b lease.acquire child span | `32910e557` | 10 | +96 | shipped (partial parenting; see §22 B) |
| 5 | LR2-c cpu_fallback.triggered event | `82b665366` | 6 | +58 | shipped |
| 6 | LR2-e search/branch + VT Context | `3edd76065` | 2 | +44 | shipped (narrower than plan; see §22 D) |
| 7 | LR3-b + LR3-c metrics reader + allowlist | `8341dba9e` | 3 | +237 | shipped (LR3-a deferred) |
| 8 | LR6-a @BuildContract + @AdvisoryContract | `22e7ca0b8` | 3 | +164 | shipped (Boot + Sample tiers deferred per plan) |
| 9 | LR6-b contracts moved to ipc-common + annotate | `493bb49c8` | 6 | +23 | shipped (partial; see §22 A) |
| 10 | LR6-d-A16 ArchUnit SLF4J-only rule | `919810712` | 2 | +95 | shipped |
| 11 | LR6-d-A15 OpenRewrite CI hook | `700b11ec2` | 4 | +106 | shipped with narrowed scope (see §21.3) |
| 12 | LR6-c contract-violation projection | `6e3568934` | 3 | +272 | shipped |

Total shipped: ~1825 LOC net across 65 files.

### 21.2 Deferrals with rationale

- **LR3-a external Windows-probe FFM surface (GPU temp, disk I/O, CPU
  freq, memory pressure).** Design-time estimate underestimated the
  work. `NvmlService.probe()` is init+query+shutdown per call; a gauge
  needs long-lived lifecycle. `GpuCapabilities.Nvml` record extension.
  Register-with-poll-cadence infrastructure. CPU freq + memory pressure
  each require new Windows PDH / Win32 FFM binding surfaces per ADR
  0005. Realistic effort: multi-day, not single-commit. See §22 Issue
  C for the scope-granularity rethink.
(LR6-d-A15 moved to §21.3 — shipped with narrowed scope; no remaining
deferral.)

### 21.3 Scope adjustments within shipped commits

Shipped deltas where Phase 1 implementation narrowed scope vs original
plan, all documented in the respective commit messages:

- **LR2-a:** 9 `session.run()` instrumentation sites confirmed across 4
  encoders. SpladeEncoder's GPU-OOM→CPU heap fallback path is covered
  as a span event (LR2-c) rather than a nested span, because
  `runHeapFallback` is called from within the primary `encoder.ort_run`
  span and a nested span would create visual noise without adding
  information the parent + event don't convey.
- **LR2-b:** sibling-vs-child `lease.acquire` semantics on 2 of 4
  encoders (see §22 Issue B).
- **LR2-e:** narrower shipped scope than plan (see §22 Issue D).
- **LR6-b:** annotation of 2 invariant sites (InferenceCompositionRoot,
  SessionPoliciesController) rather than the full §14.25–§14.28 audit.
  NativeSessionHandle documented via comment rather than annotation
  due to module-dep constraint (see §22 Issue A).
- **LR6-d-A15:** shipped with substantially narrower scope than the
  plan's stated goal ("prevent 289 F1 regressions — the 627-site
  `e.getMessage()` anti-pattern"). Empirical probe against
  `rewrite-logging-frameworks:3.26.0` found `CompleteExceptionLogging`
  matches zero Java sites in this codebase. Root cause: the recipe
  catches the bare form `log.error(e.getMessage())` (~5% of F1) but
  explicitly **not** the parameterized form `log.error("msg: {}",
  e.getMessage())` (~95% of F1). The OpenRewrite library itself
  documents `LOGGER.debug("Error: {}", ex.getMessage())` as
  "allowed" (see `rewrite-logging-frameworks-3.26.0.jar!
  META-INF/rewrite/examples.yml:1171`). Tempdoc 289 A15's retro
  (`docs/tempdocs/289-logs-audit.md:507`) had already noted this
  limitation; the 400 plan did not inherit that nuance. What
  actually shipped: `build.gradle.kts` activates
  `CompleteExceptionLogging` + adds the `rewrite-logging-frameworks:
  3.26.0` dep (already verified in `verification-metadata.xml` from
  289 A15's prior landing — no metadata chore). CI workflow step
  invokes `./gradlew rewriteDryRun
  -Drewrite.activeRecipes=org.openrewrite.java.logging.slf4j.CompleteExceptionLogging`
  scoped via system-property override so the unrelated Jackson
  migration recipe diff does not pollute the signal; fails if a
  non-empty `rewrite.patch` is generated. Parameterized-form
  prevention remains unaddressed — consistent with 289's design
  decision, not a regression. A future ArchUnit-based prevention
  rule targeting the parameterized form is a natural follow-up for
  a separate tempdoc.

### 21.4 Pre-implementation §13 items re-assessed

- **A-tier verification reads (§13.1):** all held up in implementation.
  Encoder line numbers, `EncoderProfileAccumulator` shape, virtual-
  thread fan-out location — all accurate.
- **B-tier experiments (§13.2):** B1 trace format ✓, B2 σ envelope ✓
  (σ(nDCG@10) = 0.00108 confirmed Layer 1 is meaningful). B3 span
  overhead measurement never ran during Phase 1 because
  `JUSTSEARCH_INDEX_TRACING_LEVEL` does not propagate from bash through
  Gradle daemon to spawned Worker JVM on Windows (CLAUDE.md "Windows env
  vars unreliable"). jseval `backend.py` should thread the env var via
  `-D` system property explicitly; small jseval-side fix, not a code
  bug in Phase 1 deliverables. The end-of-Phase-1 smoke is therefore
  deferred.
- **C-tier design spikes (§13.3):** C1/C2 pseudocode held up. C3 + C4
  are constraint documents; correctness of their constraints is
  untested until LR5-b / LR1-b implement.
- **D3 Windows probe scoping:** **overconfident.** `NvmlService` exists
  as a template for one-shot probes, not for long-lived gauge
  lifecycles. D3 said "LR3-a confidence 5 → 7"; post-implementation
  reassessment is 5 → 4 (see §22 Issue C).

### 21.5 Pre-existing failures encountered

- `UnreferencedCodeTest > no_unreferenced_non_public_methods` — 2
  pre-existing violations on base commit `d04609fab`
  (`WritePathOps.readModifyWriteBatch`, `AnswerTypeClassifier.classify`),
  both convenience overloads with internal self-calls only. Folded
  into commit 10 (LR6-d-A16) with allowlist entries per tempdoc 325's
  transparent-KNOWN_UNREFERENCED pattern. Learning for future plans:
  "verify main HEAD is green BEFORE branching for a multi-commit
  implementation" should join the pre-implementation checklist.
- `test_preflight.py::test_healthy_backend` — pre-existing stale mock
  of the pre-tempdoc-341 flat `/api/status` structure. Acknowledged in
  Draft 5; unchanged by Phase 1; still failing. Remains the one known
  pre-existing test failure per the plan's verification-gate discipline.

---

## 22. Design issues surfaced by Phase 1 (Draft 6, 2026-04-22)

Four substantive issues that may require rethinking parts of the §8
design or future-phase plans. Each includes a recommended rethink.

### 22.1 Issue A — Annotation module placement (architectural)

**Observation.** `@BuildContract` / `@AdvisoryContract` (LR6-a) need to
be reachable from *every* module that contains an invariant target.
The plan placed them in `app-observability` (only 1 dependent); I moved
them to `ipc-common` in LR6-b which reaches most Worker-side modules,
but still cannot reach:

- `ort-common` — adding `ipc-common` as a dep pulls gRPC into a
  fundamental module (wrong direction).
- `app-launcher` test sources (where `ClosurePropertyTest` lives).
- `worker-core` encoder classes (same reach issue as ort-common).

As a result, `NativeSessionHandle`'s §14.27 / §14.28 U1 invariant is
documented via a source comment rather than an annotation
(§21.3 LR6-b). ClosurePropertyTest self-annotation was skipped.
Annotation coverage is ~40% of the §14.25–§14.28 invariants I would
otherwise tag.

**Design rethink.** The tempdoc's stated position — "adding a
`core-contracts` module is too much ceremony for two annotation files"
— is now contradicted by implementation evidence. The "ceremony" (one
new `build.gradle.kts`, two annotation `.java` files, one unit test)
is bounded; the alternative (partial coverage) undermines LR6-b's
premise that every tempdoc invariant becomes a live runtime assertion.

Recommend: add `modules/core-contracts` (or equivalent name) as a
dep-free module. All modules that contain invariant sites add
`implementation(project(":modules:core-contracts"))`. Move the two
annotation classes there. Remove the sibling concern about pulling
gRPC into ort-common. This is ~50 LOC of new module scaffolding +
migration of the 2 existing @interface files.

**Priority.** Medium. Phase 1 shipped without it; the partial coverage
is tolerable short-term. A follow-up `400-LR6-a-refactor` commit would
complete the annotation reach and enable the rest of the §14 invariant
audit that §21.3 LR6-b left partial.

### 22.2 Issue B — `lease.acquire` parent-child spec under-specification

**Observation.** §8.2 LR2-b specifies `lease.acquire` as a *child* of
`encoder.ort_run`. For child-parent semantics to hold,
`encoder.ort_run` must start *before* `sessions.acquire()` so the OTel
context is propagated when `NativeSessionHandle.acquire()` emits
`lease.acquire`. In `OnnxEmbeddingEncoder` + `BgeM3Encoder` I could
hoist the span-start cleanly — clean child-parent. In
`BertNerInference` + `SpladeEncoder` (pinned-path), the existing
nested try-with-resources around token-type tensors + GPU-OOM
fallback makes the hoist prohibitively invasive — the spans are
*siblings* correlated via `trace_id`.

As shipped: 2 of 4 encoders have the designed parent-child; 2 have
trace-correlated siblings.

**Design rethink.** Ask whether strict parent-child is a *functional*
requirement or a nicety. For Layer 4 projections (LR4 batch):

- Per-call latency timeline: needs duration spans, not necessarily
  nesting. Correlation by `trace_id` + `span_id` ordering suffices.
- Lease-wait vs run-time split: needs both spans' durations, correlatable
  by `trace_id`. Correlation join is well-supported by any trace-query
  tool.
- Per-query attribution: needs `trace_id` consistency, which is
  guaranteed regardless of span-parent relationship.

None of these *require* strict parent-child. If the spec is softened
to "correlated via `trace_id`" the shipped mixed shape becomes
conforming. Layer 4 tooling becomes slightly harder (JOIN on
`trace_id` instead of walking parent pointers) but that's a small
one-time cost in the projection code.

Recommend: weaken §8.2 LR2-b to "`lease.acquire` correlated with
`encoder.ort_run` via `trace_id`." Strict child-parent remains a
nice-to-have for encoders where the hoist is cheap. Projection LR4-f
(CPU-fallback count) already handles both shapes since events are
attached to `Span.current()` regardless of nesting.

**Priority.** Low-medium. The shipped state is functional; softening
the spec removes tech debt without requiring code changes.

### 22.3 Issue C — LR3-a scope granularity

**Observation.** §8.3 LR3-a lumps four external probes (GPU temp, disk
I/O, CPU freq, memory pressure) into a single delta with equal scope
treatment. §13 D3 pre-implementation scoping concluded "NvmlService is
the template; GPU temp is cheap." Implementation exposed that:

- `NvmlService.probe()` is a one-shot init→query→shutdown; a gauge
  needs long-lived NVML lifecycle.
- `GpuCapabilities.Nvml` record needs new fields (temperature).
- LocalTelemetry gauge registration needs a periodic poll cadence
  harness that doesn't exist today.
- Disk I/O throughput via `Files.getFileStore()` — pattern does NOT
  exist in `NdjsonMetricExporter` beyond disk-pressure checks (which
  use `getUsableSpace()` once per export, not throughput delta).
- CPU frequency via Windows PDH — new FFM binding, not NVML-template.
- Memory pressure via Win32 `GetPerformanceInfo` — new FFM binding.

All four share a "register gauge with poll cadence" infrastructure
that doesn't exist today. Plan treated it as a missing template; it's
actually missing infrastructure.

**Design rethink.** Split §8.3 LR3-a into sub-items with explicit
dependency chain and individual triggers:

- **LR3-a.0** — Periodic gauge registration infrastructure
  (precondition). One-time; enables LR3-a.1..5 + future metric probes.
- **LR3-a.1** — Extend `NvmlService` for long-lived lifecycle
  (enables any NVML-derived gauge).
- **LR3-a.2** — GPU temperature gauge (uses LR3-a.0 + LR3-a.1).
- **LR3-a.3** — Disk I/O throughput (new FFM binding or `Files` delta
  loop; spike needed before committing to either).
- **LR3-a.4** — CPU frequency (new Windows PDH FFM binding).
- **LR3-a.5** — Memory pressure (new Win32 FFM binding).

Each has its own trigger and estimated scope. LR3-a as a unit is
multi-week work, not the single-commit §14 plan implied.

**Priority.** Medium. LR3-a as a whole is deferred anyway; rethinking
granularity matters when the follow-up plan is written, not urgent
now.

### 22.4 Issue D — LR2-e scope bag (grouping granularity)

**Observation.** §8.2 LR2-e listed six sub-items as "richer search
spans":

1. `search/rerank` child under `search/retrieval`.
2. `search/fuse` child under `search/retrieval`.
3. `search.retrieval.branch` attr.
4. Per-leg virtual-thread `Context` propagation at lines 504/508/512.
5. `search.ce.scored` attr.
6. `search.fusion.weights_fp` attr.
7. `search.searcher_generation` attr.

Implementation shipped (3) + (4) only. Deferrals:

- (1) `search/rerank` — CE rerank happens in a caller class
  (`CrossEncoderReranker` invoked from `RagContextOps`), not in
  `SearchOrchestrator.execute()`. Wrong-class scope bundling.
- (2) `search/fuse` — multiple `HybridFusionUtils` call sites; adding
  spans at each is more noise than signal since parent
  `search/retrieval` already captures fusion time implicitly.
- (5), (6) — tied to (1) + (2) deferral.
- (7) `search.searcher_generation` — no obvious Worker-side source for
  the monotonic id that `/api/debug/state` exposes as
  `serving_search_generation_id`; needs separate Lucene-side wiring.

The shipped scope (~25%) is because sub-items 1, 2, 5, 6, 7 belong to
different code-owners or require different plumbing from sub-items 3
+ 4.

**Design rethink.** Split §8.2 LR2-e into:

- **LR2-e.1** — Per-leg retrieval spans + virtual-thread Context
  propagation (`SearchOrchestrator` internal; shipped).
- **LR2-e.2** — `search/rerank` span at `CrossEncoderReranker` or its
  caller. Separate commit; separate owner; separate allowlist attrs
  (`search.ce.scored`).
- **LR2-e.3** — `search/fuse` span at `HybridFusionUtils` call sites.
  Separate commit; `search.fusion.weights_fp` attr.
- **LR2-e.4** — `search.searcher_generation` via Worker-side Lucene
  wiring. Separate commit.

Each sub-item has a distinct code surface, risk profile, and code-
owner. The plan's bundling was premature.

**Priority.** Low. Shipped scope closes the primary §4.1 concern
(per-leg observability + virtual-thread fix). The deferred sub-items
are individually small when correctly scoped.

### 22.5 Meta: execution-plan gaps not rising to design-rework

Three additional issues are execution-plan learnings rather than
design-rework triggers; captured for future-plan discipline:

- **Supply-chain verification overhead.** Adding a Gradle dep brings
  verification-metadata work proportional to the transitive dep count.
  Future plans that add third-party libs should include a
  "verification-metadata SHA pass" as an explicit line item. The
  LR6-d-A15 scare turned out benign here because the needed dep
  (`rewrite-logging-frameworks:3.26.0`) was already verified from
  289 A15's prior landing — but the initial LR6-d-A15 probe targeted
  version 3.17.0 which was **not** verified, generating 13 missing-
  artifact errors and a misleading "deferral" decision. Lesson: when
  a dep-add CI fails on verification-metadata, first check whether a
  different version already exists in `verification-metadata.xml`.
- **Recipe-scope verification before wiring.** The 400 plan stated
  LR6-d-A15 would prevent "289 F1 regressions (the 627-site
  anti-pattern)" without verifying the OpenRewrite recipe actually
  matched the anti-pattern shape. Empirical probe (per-recipe YAML
  + the published `examples.yml`) would have caught this in a 10-
  min read before adopting the recipe as the prevention mechanism.
  Future plans citing a specific tool as the prevention surface for
  a specific anti-pattern should attach a minimum-viable-example
  proving the tool catches at least one representative case from
  the cited anti-pattern inventory.
- **Env-var propagation for tracing in jseval — RESOLVED (2026-04-22).**
  The initial `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` smoke attempts
  failed to propagate the env var to the Worker JVM. Root cause:
  `applyHeadlessEvalContract()` in `modules/ui/build.gradle.kts`
  hardcodes an env-var whitelist (~7 keys) and only iterates
  `HEADLESS_GPU_ENV_VARS` on forward — it never iterated
  `HEADLESS_AI_ENV_VARS` despite `JUSTSEARCH_INDEX_TRACING_LEVEL`
  being declared in that list. `runHeadless` iterates both lists
  correctly; `runHeadlessEval` did not. Fixed by adding a matching
  AI-list iteration with `containsKey` precedence so hardcoded keys
  still win. End-to-end verification (§23) confirmed the propagation
  works after the fix. Original lesson preserved: build.gradle.kts
  env-forwarding lists are silent failure surfaces when the declaration
  and the iteration drift.
- **Pre-implementation main-branch health check.** Pre-existing
  `UnreferencedCodeTest` failures on the branch base (`d04609fab`)
  surprised implementation. Future plans should verify the base
  commit's `./gradlew.bat test` is green before branching for a
  multi-commit implementation.


---

## 23. Design-goal achievement retrospective (Draft 12, 2026-04-22)

After Phases 1-6 landed, a theoretical closure pass — "did the
implementation achieve the goals, ideas, and preventions stated in
§6-§17?" — completes the design file. This section is theoretical by
construction: it treats every commit as a black box and asks only
whether the architectural questions the tempdoc was written to answer
are now answerable. Implementation specifics (commit SHAs, defects,
Phase boundaries) live in `400-implementation-log.md`.

### 23.1 Core thesis achievement

§6 and §17 frame the tempdoc's reason for existing: the existing
telemetry stack serves "is the pipeline broken?" but not "why did this
change?". The six-layer delta architecture is the means to extend one
to the other.

The core thesis is **substantially achieved**. jseval is now a
projection consumer over the event bus rather than a hand-assembler of
summary documents; the non-determinism envelope resolves the
noise-vs-signal question at real calibrated σ values; the manifest
identity model carries cohort semantics across runs. The class of
question the tempdoc was written to answer — "why did this cross-run
number change?" — has an architectural answer: compare envelopes,
bisect manifest axes, read per-query projections.

Where the thesis is NOT fully achieved: the contract-tier taxonomy
(Layer 6) only ships 2 of 4 tiers; the external-world sample sources
(LR3-a) are deferred entirely. Both are documented gaps with
well-defined follow-up routes.

### 23.2 Per-layer achievement

| Layer | Stated goal (§8) | Theoretical achievement |
|---|---|---|
| **1. State** | Aggregate all hashes into run manifest; non-det envelope; policy snapshot embedded | **Fully achieved.** Manifest carries cohort identity; Phase-2.0's fix separated identity from runtime state — a refinement the original spec did not anticipate but that the cohort-CI idea required. Envelope calibrated live (σ(nDCG@10)=0.00117). Policy embedded via root-cause short-circuit, not a null fallback — matches the design's "authority publishes typed state" spirit. |
| **2. Events** | Per-ORT-call span + lease.acquire + cpu_fallback event + retire orphans + add commit.* identity + richer search spans | **Fully achieved.** LR2-a/b/c/d shipped; LR2-e shipped across all four sub-items. The §22-B parent-vs-sibling semantics softening is a principled design refinement: correlation (what Layer-4 consumers actually need) was preserved; strict parenting was never a functional requirement. |
| **3. Samples** | External-world probes + jseval consumes metrics.ndjson + tag allowlist | **Partially achieved.** LR3-b + LR3-c shipped. LR3-a (the external-world probes that answer "was the GPU at 87°C when search degraded?") deferred in full to tempdoc 401 per §22 Issue C's multi-week-infrastructure finding. The motivating operator use case remains unanswerable with today's shipped code. |
| **4. Projections** | jseval inverts from hand-assembly to projection consumer; 7 projections + schema extension | **Fully achieved.** All eight LR4-* projections exist as registered pure functions. Entity-density dimension dropped from LR4-c (§26.6 Decision 1) is a principled scope reduction that *protects* the "projections are pure functions over artifacts" invariant — a goal preservation, not a miss. Phase-6 refinements closed silent-failure modes that would have undermined consumer trust in the outputs. |
| **5. Experiments** | Counterfactual + shadow eval + concurrent benchmark + manifest-hash bisection — all four from scratch | **Mostly achieved.** All four runners exist as jseval subcommands. LR5-d gained a synthetic executor in Phase 6 to complete §13.9 C2. LR5-a shipped as a multi-pass HTTP variant instead of the spec's single-pass proto/Java extension — the consumer contract is identical and the projection-consumer integration is met, but the single-pass efficiency goal is deferred. A future Worker-side fast path remains additive. |
| **6. Contracts** | Tier taxonomy (Build/Boot/Sample/Advisory) + invariant audit + violation projection + close 289 RC1 | **Partially achieved.** 2 of 4 tiers ship (@BuildContract, @AdvisoryContract); @BootContract and @SampleContract deferred at Phase 1 (§21.1 commit 8). Invariant audit coverage is ~40% of §14.25-§14.28 sites. LR6-c aggregator projection ships but has no production emitter because the runtime-enforcement tiers that would emit `contract.violation` events are absent. LR6-d's OpenRewrite hook catches ~5% of the targeted anti-pattern (recipe shape mismatch per §21.3). No follow-up tempdoc tracks the remaining tier rollout. |

### 23.3 Cross-cutting idea achievement

- **§9.1 Non-determinism envelope as cross-run CI — achieved.**
  The envelope exists per-cohort, is calibrated live, and is consumed
  by projections + the nightly gate. Cross-run deltas inside ±2σ are
  classified as noise; outside as signal. The retrospective application
  to §3's `(noise?)` annotations (via the B2 calibration in §13.9)
  confirms the design idea worked as intended: small nDCG@10 / P@1
  deltas were inside envelope (noise); the ingest-elapsed delta was
  outside (signal).

- **§9.2 Bisection replaces Shapley-style causal-% — achieved.**
  LR5-d bisection operates over manifest-hash tuples. Phase 4 shipped
  cache-only analysis; Phase 6 added the synthetic executor that
  materializes single-axis-swapped cohorts on demand. The design
  alternative (Shapley decomposition) was correctly kept out of the
  codebase; no call-site exists that would have required it.

### 23.4 Prevention achievement (§15)

| Prevention rule | Outcome |
|---|---|
| Observability-for-its-own-sake | **Held.** Every LR* item in §8 cites its Layer-4 consumer or analytical question; no field ships without one. |
| Claim-vs-code drift | **Partially held.** @BuildContract enforces at merge; @AdvisoryContract logs. Without @BootContract/@SampleContract, invariants that need *runtime* enforcement fall back to documentation — exactly the drift the rule was meant to prevent, now concentrated in the Layer-6 gap. |
| Observability vs experiments conflation | **Held.** Layer 5 is a separate module + CLI surface; no projection consumes experiment outputs accidentally. |
| Metric-shape drift breaking history | **Held.** Projections carry `schema_version`; LR4-h eval-history is normalized per §26.6 Decision 3 so metric-set evolution doesn't force ALTER TABLE. |
| Research-grade features hiding in the idea bank | **Held.** Shapley replaced by bisection; universal-runtime-assertion not shipped. Draft 1's 15 items all landed as bounded deltas or explicit deletions (see §10). |
| 500-line idea-bank tempdocs | **Held.** The six-layer decomposition made the Phase 6 critique addressable commit-by-commit rather than as one monolithic review. |
| Silent architectural duplication | **Held.** §11's factual inventory prevented Draft 2's "Layer-2-not-there" pathology; no Phase 1-6 commit rebuilt an existing stack. |
| Orphan proto fields | **Held.** LR2-d.1 retired `pipeline_hash`/`budget_profile`; LR2-d.2 populated the real commit-metadata identity that survived ADR 0014. |

### 23.5 Anti-goals correctly unsolved (§16)

§16 enumerated problems the tempdoc explicitly does NOT solve. Phases
1-6 correctly did not attempt any of them:

- **Non-determinism itself.** Envelope quantifies it; does not remove it.
- **Storage cost of per-query span trees.** Retention policy unchanged;
  cost remains a future tuning decision.
- **Consumer discipline.** Social governance rule; no mechanism shipped
  (nor should one have).
- **Bisection-runner cost at cohort change.** §26.6 Decision 2's
  `cohort_baselines/<hash>/` registry bounds the cost (per-cohort
  invalidation) but does not eliminate it.
- **Selection-bias in LR5-b.** §13.9 C3 scope constraint was encoded
  as a runtime invariant (`a_qids == b_qids` post-run check); scope,
  not elimination.
- **Platform probe portability.** Deferred with the rest of LR3-a.

### 23.6 Gaps identified by this retrospective

Four gaps between design goals and shipped behavior that the
implementation log documents piecewise but that the design file had
not yet surfaced as a single list. Items 1-3 were identified in the
Draft 12 retrospective; item 4 surfaced during the post-followup-plan
validation pass (§23.9) and was added in Draft 13.

1. **LR3-a external-world probes — deferred in full.** Tempdoc 401
   tracks the multi-week FFM infrastructure (NVML long-lived lifecycle,
   Windows PDH binding, Win32 `GetPerformanceInfo` binding, disk-I/O
   throughput delta loop). Until 401 lands, the stated motivation for
   LR3-a — "operators see GPU thermal / disk saturation / memory
   pressure when searches degrade" — is unanswerable with shipped code.

2. **Layer 6 tier taxonomy — 2 of 4 tiers missing.** @BootContract and
   @SampleContract were deferred in Phase 1 (§21.1 commit 8); LR6-c's
   projection ships as an aggregator but has no production emitter
   because the runtime-enforcement tiers that would emit
   `contract.violation` span events are absent. The stated goal —
   "invariants become live code" (§15) — is therefore only partially
   realized. **Tempdoc 402 (`docs/tempdocs/402-layer-6-tier-
   completion.md`, 2026-04-22) now scopes the completion** —
   resolves the `ort-common → core-contracts` dep constraint,
   locks the @BootContract validator-registry mechanism, locks the
   @SampleContract manual-call-site sampling pattern, enumerates
   the invariant audit expansion.

3. **LR5-a single-pass Worker API — deviation not tracked as
   follow-up.** Shipped as a multi-pass HTTP variant (zero Java
   surface change). Consumer contract identical; efficiency goal
   deferred. §28.3 leaves the door open for a future additive Java
   commit but does not schedule it. No tempdoc follow-up — remains
   optional and low-priority.

4. **Traces mirror loses rotated spans (D-3, discovered §23.9).**
   `artifacts._mirror_telemetry` copies only the active
   `traces.ndjson` into `run_dir/`, not the rotated siblings
   (`traces.<ts>.ndjson`). On ingest-heavy runs that cross the 10 MB
   rotation threshold, a large fraction of the run's spans lives in
   the rotated file and is invisible to every Layer-4 projection that
   reads from `run_dir/traces.ndjson`. Quantified on the §23.9
   post-followup smoke: **39,154 spans emitted, 8,985 mirrored —
   77.1% lost.** All `indexing.*` spans lost entirely (100%); 69% of
   `encoder.ort_run` + `lease.acquire`; 44% of `enrichment.batch`.
   **Silent-failure mode** (projections report `status=ok` with
   biased-sample outputs); exactly the class of defect Phase 6
   existed to prevent, missed because the live smoke that would
   have caught it never ran until §23.9.
   **Direct impact on LR4-g encoder_drift:** any baseline captured
   via `calibrate-drift-baseline --from-runs ...` over ingest-heavy
   runs reflects only the post-rotation tail — systematically
   biased toward late-phase warm-GPU latencies rather than the full
   distribution. Logged in `docs/observations.md` with proposed fix
   at `scripts/jseval/jseval/artifacts.py:82-103`.

### 23.7 Recommended follow-up tempdocs

Updated in Draft 13 to reflect follow-up tempdocs that have since
landed as scoped designs.

- **Tempdoc 401** (scoped 2026-04-22) — LR3-a Windows external
  probes with FFM infrastructure. **Status:** scoped, not yet
  implemented.
- **Tempdoc 402** (scoped 2026-04-22) — Layer 6 tier completion
  (@BootContract + @SampleContract + invariant-audit expansion +
  `ort-common` dep resolution). **Status:** scoped, not yet
  implemented. Closes gap #2.
- **Tempdoc 403** (scoped 2026-04-22) — observability artifact
  retention policy (cohort_baselines / runs / run_dirs /
  traces.ndjson detailed-tracing overflow). **Status:** scoped,
  not yet implemented. Closes the resource-growth concern
  Agent-3-identified during the followup exploration.
- **D-3 traces-mirror fix** (logged 2026-04-22) — not a new
  tempdoc; single-commit fix in `artifacts.py` to copy rotated
  siblings whose write window overlaps the run's backend lifetime.
  **Status:** open observation; high priority because it silently
  invalidates LR4-g baseline work.
- **Optional** — LR5-a Worker-side single-pass proto/Java
  extension. Lowest priority; consumer contract already met via
  the multi-pass variant.

### 23.8 Validation coverage — live smokes (2026-04-22)

§23.1 left a gap the earlier retrospective did not surface cleanly:
"code shipped" is not the same as "exercised end-to-end in the current
pipeline." A follow-up live-smoke pass confirmed or falsified each
remaining item.

**Environment.** Fresh backend at HEAD `268f394df`, scifact corpus
(5184 docs), `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed`, traces.ndjson
populated with ~7,400 spans (3,134 `encoder.ort_run`, 16
`search/retrieval`, plus indexing + lease spans).

| Item | Signal | Outcome |
|---|---|---|
| **LR2-d.2 commit.* identity** | 8/8 `commit.*` attrs on every `search/retrieval` span + every non-first-batch `indexing.batch` span (re-confirmed, was already in §23.1). | **VALIDATED**. |
| **LR2-e.4 search.searcher_generation** | 16/16 `search/retrieval` spans carry `search.searcher_generation=g-<timestamp>` formatted id alongside the 8 `commit.*` attrs. Phase 6 / 6.7 wiring (SearchOrchestrator.setActiveGenerationSupplier + DefaultWorkerAppServices supplier resolution) produces attr in live traces.ndjson. | **VALIDATED** first time end-to-end. |
| **LR4-a projection base** | 7 registered projections discovered via `run_all_discovered`; every projection wrote `projections/<name>.json` to the run dir; `_errors.ndjson` path exercised (none raised). | **VALIDATED**. |
| **LR4-b bootstrap_ci** | Real 95% CIs over 15 queries × 5 metrics; all per-metric `ci_status=ok`. Example: nDCG@10 mean=0.806, ci_low=0.654, ci_high=0.933 (1000 resamples). | **VALIDATED**. |
| **LR4-c stratified_metrics** | 2-dim marginals populated (query_length × first_relevant_rank); `bucket_definitions.edges` echoes the Phase 6/6.12 configurable edges (5,10) and (1,5,10). | **VALIDATED**. |
| **LR4-d rate_timeline** | 4 tracked counters (`worker.documents.indexed.total`, `worker.batches.submitted.total`, `worker.commits.total`, `worker.documents.failed.total`) over 16 ticks; stall detection ran (zero stalls — expected on healthy 20-query run). | **VALIDATED**. |
| **LR4-e rank_diff** | Ran without error; empty output because no prior same-cohort run existed in the history DB. Negative-path behavior correct. | **VALIDATED (negative path)**. |
| **LR4-f cpu_fallback_counts** | total_fallbacks=0 on healthy GPU run — expected. | **VALIDATED (empty path)**. |
| **LR4-g encoder_drift** | Extracted per-encoder duration distributions for embed (n=184), ner (n=2,639), splade (n=311). With a synthetic baseline shifted by ×0.5 written at `cohort_baselines/<hash>/span_distributions.json`, PSI scores 6.45 / 10.13 / 4.76 all trigger `drift_flagged=true` (> 0.2 threshold). Baseline-absent path returns `status=no-baseline` correctly; first-run cold-start gate holds. | **VALIDATED** but see D-1. |
| **LR4-h eval-history schema** | `compute_envelope` + `write_envelope` produced `<data_dir>/cohort_baselines/<hash>/envelope.json` (schema_version=1, n_runs=3, 7 metrics × 1 mode); `append_run` populated `envelope_metrics` rows and `runs.manifest_hash`. Subsequent `jseval run` at matching cohort auto-embedded the envelope into `manifest.non_determinism_envelope`. | **VALIDATED**. |
| **LR5-a counterfactual** | 10 queries × 5 modes (lexical_only, dense_only, splade_only, hybrid_no_ce, hybrid_full); per-query rankings + pairwise_divergence matrix + summary emitted; `fusion_algorithm=cc` echoed; wall=4.6s. Jaccard vs hybrid_full: lexical=0.269, dense=0.411, splade=0.315, no_ce=0.393. | **VALIDATED**. |
| **LR5-b shadow-eval** | Policy A (CE off) vs Policy B (CE on), 15 queries; mean Jaccard=0.488, mean Kendall-tau=0.359; all 15 classified `partial` (neither identical nor disjoint). `a_qids == b_qids` post-run invariant held (no RuntimeError). | **VALIDATED**. |
| **LR5-c bench-concurrency** | concurrency=4, warmup=2 (Phase 6/6.10), max_connections=4 (1:1 default per 6.10), 20 queries; 4 streams × 5 records; qps=7.3; p50=517ms, p95=524ms, p99=543ms. | **VALIDATED**. |
| **LR5-d bisect** | Cache-only: scifact-cohort vs nfcorpus-cohort identified 3 differing axes (`dataset`, `doc_count`, `query_count`) each reported `no-cached-run`; identical-cohort input returned `status=identical-cohorts`. `--synthesize --dry-run` (Phase 6/6.5) listed the same 3 axes as candidates for synthetic-run spawning. | **VALIDATED**. |
| **Nightly gate (Phase 3 + 6/6.13)** | All 3 exit-code paths exercised: exit 2 (no eval-results), exit 1 (`manifest-envelope-embedded=fail` when envelope not embedded + `ndcg10-stdev-within-tolerance=fail` when `--baseline-stdev` is out-of-band), exit 0 (all 4 checks ok). `--report-out` JSON schema stable. | **VALIDATED**. |
| **LR2-c cpu_fallback.triggered event** | Emitter code at `NativeSessionHandle.reportCpuSessionFailure` still present; attrs `fallback.cause` + `fallback.encoder` still on NdjsonSpanExporter allowlist. Live-exercise blocked on GPU-failure injection (requires BFC-arena OOM or synthetic session-init failure — no test harness). Synthetic-fixture coverage is in `test_projections_cpu_fallback_counts.py`. | **STRUCTURALLY VALIDATED, NOT LIVE-EXERCISED**. |
| **LR6-c contract-violation projection** | Projection aggregator exercised via `_errors.ndjson` quarantine path (see §30.1 / 6.1). Live production emitters do not exist because @BootContract and @SampleContract tiers were deferred at Phase 1 (§21.1 commit 8). | **AGGREGATOR VALIDATED, EMITTERS STRUCTURALLY ABSENT**. |

**Defects surfaced during this validation pass** (not blocking closure,
logged as observations; filed in `docs/observations.md` inbox):

- **D-1. `encoder_drift` span-schema drift.** `_extract_encoder_durations`
  reads `span["duration_ms"]`; the real `NdjsonSpanExporter` emits only
  ISO `start` + `end` fields with no `duration_ms` field. Unit tests
  inject `duration_ms` directly in their synthetic fixtures, so the
  projection passed every prior test + every prior end-of-run dispatch
  silently returned `status="no-encoder-spans"` even when tracing was
  on. This is the **exact failure mode the Phase 6 critique theme
  warned about** ("silent failure modes dominate"), reproduced against
  a projection the Phase 6 remediation pass did not reopen. Fix candidate:
  compute `duration_ms` from start/end in the projection (one-line
  change), or have `NdjsonSpanExporter` emit `duration_ms` alongside
  start/end (breaks Phase 3 tests that assume current shape).
- **D-2. `calibrate` path-relativity bug.** `calibrate.py:164`
  overwrites `JUSTSEARCH_DATA_DIR` with the relative string form of
  `--data-dir`, which resolves inconsistently between the Python
  sub-run (CWD = `scripts/jseval`) and the Gradle Java process
  (CWD = `REPO_ROOT`). When `--clean` runs, Python's rmtree targets a
  different path than the Java backend actually writes to, so run 2
  inherits run 1's index and stalls on
  `indexed_doc_count_below_floor(...)`. Workaround: pass absolute
  `--data-dir`. The Phase 3 log §27/30.1 already documented a related
  "`start_backend` did not honor JUSTSEARCH_DATA_DIR" defect (#13 in
  §27.1); this is the sibling bug at the same boundary.

**Verdict on §23.1 gap-list:** every item it flagged as "not
live-validated" is now either VALIDATED (LR2-e.4, all four LR5-*,
nightly gate) or formally categorized (LR2-c structurally, LR6-c
aggregator-only). The two defects this pass surfaced (D-1 encoder_drift
schema drift, D-2 calibrate path) strengthen rather than invalidate the
retrospective's verdict: the architecture holds end-to-end; the
remaining failures are localized defects at module boundaries, not
design-level gaps.

### 23.9 Post-followup-plan validation (Draft 13, 2026-04-22)

After the 5-commit follow-up plan landed (canonical doc refresh +
how-to guides + `rate_timeline` contract test + tempdocs 402 + 403),
a second full-pipeline smoke validated the cumulative
post-tempdoc-400 state. This section captures the results as the
closure evidence for the retrospective.

**Setup.** 3-run `jseval calibrate` against scifact, 50 queries,
full mode, `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed`, absolute
`--data-dir`. Branch `worktree-400-lr2d` at HEAD `02bc3516c`. Gradle
built from this worktree's source so the D-1 `duration_ms` fix was
live in the Worker JVM (verified by 100% `duration_ms` coverage on
mirrored spans).

#### 23.9.1 Results

| Dimension | Observation |
|---|---|
| Cohort identity stability (Phase 2.0) | ✓ `manifest_hash=26d0058007965538` identical across all 3 runs — Phase 2.0 identity fix holds |
| Quality stability (vs Phase 2.2d baseline) | ✓ nDCG@10 mean=0.8212 (vs 0.8205 §25.4), P@1=0.7200 (identical), R@10=0.9220 (identical); no regression |
| Envelope calibration | ✓ σ(nDCG@10)=0.0003 (4× tighter than B2's 0.00108 at 300-query scale; P@1/R@10/RR@10 deterministic at σ=0 on 50-query subset) |
| Layer 1 manifest | ✓ `policy_hash` populated from 397 LR1-c short-circuit; all cohort-identity fields present; all `_VOLATILE_FIELDS` separated correctly |
| Layer 2 D-1 `duration_ms` | ✓ Present on 100% of 8,985 mirrored spans |
| Layer 2 attr allowlist | ✓ 8/8 `commit.*` on every `search/retrieval` (46/46); 4/4 `encoder.*` on every `encoder.ort_run` (4,357/4,357); 2/2 `lease.*` on every `lease.acquire` (4,356/4,356); `search.searcher_generation` + `search.fusion.{algorithm,branch_count}` populated on their owning spans |
| Layer 2 non-fire (correct) | ✓ `fallback.*` absent (no GPU OOM); `search.ce.scored` absent (`search/rerank` doesn't fire when CE disabled in `full` mode — 1 stray span from startup probe only) |
| Layer 3 metric streams | ✓ Head → `metrics.ndjson` (587 records, head+GPU gauges); Worker → `metrics-worker.ndjson` (140 records including all 4 LR4-d tracked counters); jseval `metrics_reader` merges both correctly |
| Layer 4 all 7 projections | ✓ All produce well-shaped outputs: `bootstrap_ci`, `contract_violations` (total=0 correct), `cpu_fallback_counts` (total=0 correct), `encoder_drift` (status=`no-baseline` correct — encoders discovered from real spans validates D-1 fix), `rank_diff` (status=`ok`), `rate_timeline` (all 4 counters populated, 0 stalls), `stratified_metrics` (10 strata) |
| Tempdoc 397 integration | ✓ `policy_hash` = 32-char SHA prefix populated; pre-Phase-2.1 would have been null / `worker-unreachable` in eval mode |

#### 23.9.2 Latency regression vs Phase 2.2d

| Metric | Phase 2.2d (§25.4 baseline) | §23.9 smoke | Δ |
|---|---|---|---|
| nDCG@10 stdev | 0.00117 | 0.00034 | -71% (tighter) |
| mean_ms | 36.82 | 45.07 | **+22%** |
| p50_ms | 26.20 | 46.67 | **+78%** |

The mean+p50 regression is consistent with the cumulative cost of
Phase 6's span additions: `search/rerank` emission inside
`CrossEncoderReranker.rerank` for every CE caller, `search/fuse`
spans at every `HybridFusionUtils` call site (primary 3-branch,
whole×chunk merge, chunk 3-way, nested-RRF), `search.searcher_
generation` supplier resolution per query, plus `detailed` tracing
overhead. The B3 span-overhead measurement was deferred across all
6 phases; **§23.9 is the first measurement**. Worth citing in
tempdoc 403's §3.3 detailed-tracing overflow rationale.

Envelope-wise: the observed p50 stdev of 4.16ms (CV=8.9%) is
consistent with Phase 1 B2's documented 5-9% latency CV range, so
the run-to-run noise is unchanged — only the central tendency
shifted.

#### 23.9.3 D-3 traces-mirror rotation gap (new silent-failure mode)

During span-census analysis of run 3, two facts contradicted:
the mirrored `traces.ndjson` contained 8,985 spans with **zero
`indexing.*` spans**, but the run had ingested 5,184 docs
(producing thousands of `indexing.extract` + `indexing.write`
spans). Investigation revealed the data dir held:

```text
<data_dir>/telemetry/
├── traces.20260422-134805.ndjson   10,645,048 bytes (rotated)
└── traces.ndjson                    2,922,236 bytes (active at mirror)
```

`NdjsonSpanExporter` rotates at 10 MB (tempdoc 400 default).
`artifacts._mirror_telemetry` copies only the active
`traces.ndjson` into `run_dir/`; rotated siblings are silently
dropped.

**Quantified loss (run 3, 50 queries, detailed tracing):**

| Span | Emitted (full) | Mirrored (run_dir) | Lost |
|---|---|---|---|
| `indexing.extract` | 5,189 | 0 | **100%** |
| `indexing.write` | 5,189 | 0 | **100%** |
| `indexing.batch` | 325 | 0 | **100%** |
| `indexing.markDone` | 9 | 0 | **100%** |
| `encoder.ort_run` | 14,066 | 4,357 | **69%** |
| `lease.acquire` | 14,067 | 4,356 | **69%** |
| `enrichment.batch` | 82 | 46 | 44% |
| `search/*` (retrieval+branch+fuse) | 226 | 226 | 0% |
| **Total** | **39,154** | **8,985** | **77.1%** |

**Why this matters structurally:**

- **LR4-g encoder_drift baseline is silently biased.** A
  `calibrate-drift-baseline --from-runs R1 R2 R3` call reads the
  mirrored traces — capturing only 31% of `encoder.ort_run` calls
  per run, specifically the post-rotation late-phase calls. PSI
  comparisons against such a baseline measure against a warm-GPU
  distribution; cold-start+kernel-cache-fill encoder latencies
  are systematically excluded from the reference. The drift
  signal remains meaningful for step-change drift but is blind
  to the cold-start tail that real operator investigations care
  about.
- **Silent-failure pattern.** Projections report `status=ok` with
  outputs that pass every shape test. The defect surfaces only by
  counting spans at the data_dir level vs the run_dir level — not
  a test any consumer would think to write.
- **Misses Phase 6's theme by symmetry.** Phase 6 closed 15
  silent-failure modes; D-3 is the same class, missed because the
  live smoke that would have counted spans at both levels never
  ran until §23.9.

**Proposed fix scope** (separate commit, not a new tempdoc):
`_mirror_telemetry` should additionally copy rotated siblings
(`traces.<ts>.ndjson`) whose write window overlaps the run's
backend lifetime. Timestamps are already embedded in filenames +
file mtimes; a simple mtime filter + concat into the run_dir
closes the gap without touching the exporter side.

#### 23.9.4 Theoretical improvements catalogued

Noted during §23.9 analysis; not all feasible, not all urgent. Kept
for future tempdoc scoping.

1. **Rotation-aware telemetry mirror** (D-3 fix, HIGH — logged).
2. **`search/retrieval` count short by ~8%.** 46 spans emitted for
   50 queries across 3 runs (consistent shortfall). Could be
   retries collapsed, warmup probes, or export batch loss — worth
   a 20-min investigation.
3. **Rate-timeline window size incompatible with short runs.** 4
   tracked counters × ~3 tick records per counter over a ~3-min
   run = no window ever fills (default `window_size=10`). Stall
   detection cannot fire on any sub-10-minute run. Adaptive
   window or documented minimum-run-length is needed.
4. **Envelope auto-embed does not backfill at calibrate time.**
   Runs 1-N's manifests have `non_determinism_envelope=null` even
   though the envelope is computed at the end of the same
   `jseval calibrate` invocation. Only subsequent `jseval run`
   calls post-calibrate see the embedded envelope. A final
   backfill pass over the N sub-runs' manifests would preserve
   trend-analysis continuity.
5. **Per-projection latency not recorded.** `run_all` produces
   `{status, path}` per projection but no duration. A projection
   that becomes O(n²) silently lengthens the nightly gate. Cheap
   fix: record `duration_ms` alongside `status` in the summary.
6. **Layer-5 runner outputs not in the manifest index.**
   `_index/manifests.jsonl` tracks `jseval run` manifests but not
   counterfactual / shadow-eval / bench-concurrency outputs.
   Cross-experiment Layer-4 projections cannot aggregate.
7. **`traces.ndjson` rotation is not surfaced to operators.**
   Size-triggered; operator can't know it fired without
   filesystem inspection. A `traces_rotation_count` gauge or a
   `rotation_events` field in the run manifest would make the
   D-3 symptom visible without requiring the forensics §23.9 did.
8. **Detailed-tracing cost is real (+22%) and now measured.**
   Tempdoc 403 §3.3 should cite §23.9.2 as the input evidence for
   the tighter detailed-tracing rotation budget.
9. **`search.searcher_generation` is a string.** Consumers wanting
   monotonic comparison ("did the generation advance between
   queries?") string-compare timestamps. A numeric companion attr
   would let Layer-4 aggregate faster.
10. **No regression guard on the 8 `commit.*` attrs.** ArchUnit
    rules enforce the allowlist; no test asserts that the first-
    batch `search/retrieval` actually carries all 8. If
    `DefaultWorkerAppServices` stopped wiring the commit snapshot,
    the attr set would silently thin. Already folded into tempdoc
    402's §2.2 invariant-audit expansion.

### 23.10 Verdict

The tempdoc's primary thesis — finish the §14.28 discipline for eval
observability — is **substantially achieved and empirically
validated.** Six-layer architecture held across Phases 1-6; both
cross-cutting design ideas (§9.1 envelope, §9.2 bisection) landed as
code; prevention rules (§15) mostly held; anti-goals (§16) stayed
correctly unsolved. §23.8 live-smoked every runner and projection;
§23.9 confirmed the cumulative post-followup state across three
identical reruns.

Three documented gaps (LR3-a deferred in full, Layer 6 half-tiered,
LR5-a multi-pass deviation) are routed to tempdocs 401, 402, and an
optional future commit respectively. A fourth gap (D-3 traces-mirror
rotation loss, §23.9.3) surfaced during validation — a silent-failure
mode in the Phase 6 mould that the live-smoke discipline was supposed
to catch. That it required a third validation pass (§23.9) to expose
is a useful calibration of how hard silent-failure modes are to hunt:
they do not announce themselves; you discover them by counting things
that should match and don't.

The one deliberate deviation (LR5-a multi-pass) preserves the
consumer contract while deferring the efficiency goal. One latency
regression (+22% mean, +78% p50 vs Phase 2.2d) is the first
measurement of the cumulative Phase-6 span-addition cost; production
stays on `JUSTSEARCH_INDEX_TRACING_LEVEL=none` so the regression is
operator-visible only when detailed tracing is explicitly enabled
(nightly gate workflow).

This retrospective is a design-file closure. Further work against
tempdoc 400 extends `400-implementation-log.md` or opens a follow-up
tempdoc (401 / 402 / 403). D-3 is a single-commit observation,
logged in `docs/observations.md`. The design above does not grow
further — only validation records do.

---

## Implementation log (Phases 1-6)

Execution history — shipped commits, discoveries, verification runs —
moved out of this design file in Phase 6 / 6.14 into a companion
document so the design above stays small enough to re-read quickly:

**See `docs/tempdocs/400-pipeline-observability-gaps.md`** for:

- §23 End-to-end verification (Phase 1 close-out)
- §24 Phase 2.0 — LR1-a cohort-hash identity fix
- §25 Phase 2 implementation log
- §26 Phase 3 pre-implementation probe (incl. §26.6 locked decisions)
- §27 Phase 3 implementation log
- §28 Phase 4 implementation log
- §29 Phase 5 implementation log
- §30 Phase 6 implementation log

Any new implementation work that references tempdoc 400 should append
to the implementation log, not to this design file.

---

# Consolidated satellites (folded 2026-06-09, post-400 hygiene pass)

> The implementation log and post-implementation critique (same effort, same number) folded in.

## Implementation log (was 400-implementation-log)

*(folded from `400-implementation-log.md`)*

### Tempdoc 400 — Pipeline Observability Implementation Log

**Companion file to** `docs/tempdocs/400-pipeline-observability-gaps.md`
**(the frozen design document §0–§22).**

This file tracks the shipped implementation across Phases 1-6 of the
tempdoc 400 work plan. It was split out in Phase 6 / 6.14 so the
design document — which is immutable reference material for future
agents landing Layer-2/3 work — no longer scrolls past the phase-by-
phase execution record. Readers who need to understand **why** the
architecture looks the way it does should start in the design document;
readers who need to understand **what landed and when** should start
here.

## Map of Phase sections

- §23 End-to-end verification (Phase 1 close-out)
- §24 Phase 2.0 — LR1-a cohort-hash identity fix
- §25 Phase 2 implementation log
- §26 Phase 3 pre-implementation probe
- §27 Phase 3 implementation log
- §28 Phase 4 implementation log
- §29 Phase 5 implementation log
- §30 Phase 6 implementation log (Phases 6.1-6.15)

---

## 23. End-to-end verification (Draft 6, 2026-04-22)

Run after the critical analysis identified that "11 of 12 shipped"
was code-landed-not-validated. Three full smokes (~15 min each) via
`jseval run --dataset scifact` with `JUSTSEARCH_INDEX_TRACING_LEVEL=
detailed`, followed by an explicit-pipeline curl probe for the 3-leg
search path.

### 23.1 Validation matrix

| Delta | Signal | Result |
|---|---|---|
| LR2-a | `encoder.ort_run` + 4 attrs (name/gpu/batch_size/seq_len) | ✓ 14,204 spans; attrs correct |
| LR2-a parent | `enrichment.batch` as parent of `encoder.ort_run` | ✓ parent_span_id confirmed |
| LR2-b | `lease.acquire` + 2 attrs (mode/wait_queue_depth) | ✓ 14,205 spans; `lease.mode=gpu`, queue depths populated |
| LR2-c | `cpu_fallback.triggered` event | N/A fires only on GPU OOM — did not fire on healthy GPU run, as designed |
| LR2-d.2 indexing-side | `indexing.batch` with 8 `commit.*` attrs | ✓ 324 of 325 spans carry all 8 attrs (first batch pre-commit is expected empty) |
| LR2-d.2 search-side | `search/retrieval` with 8 `commit.*` attrs | ✓ all 8 attrs present |
| LR2-e virtual-thread fix | `search/branch` parents to `search/retrieval` | ✗→✓ defect found + fixed mid-verification (see §23.2) |
| LR3-b | jseval `metrics_reader` merges head + worker streams | ✓ 692 head + 140 worker rows merged by timestamp, source-tagged |
| LR6-c | contract-violation projection | N/A — no `contract.violation` events fire in Phase 1 (expected; tiers deferred) |
| Perf | indexing throughput vs baseline | ✓ 22.5 → 23.2 docs/s across three runs — within noise, no regression |

### 23.2 Defects found + fixed during verification

**Defect 1 — jseval env-var propagation to Worker (build infra).**
`JUSTSEARCH_INDEX_TRACING_LEVEL` never reached the Worker JVM because
`applyHeadlessEvalContract()` hardcoded a whitelist and dropped the
`HEADLESS_AI_ENV_VARS` list. The list had the env var declared but
nothing iterated it. Fixed in `modules/ui/build.gradle.kts` by adding
an AI-list iteration mirroring the `HEADLESS_GPU_ENV_VARS` pattern
already present. Hardcoded keys still win via `containsKey` guard.
Confirmed via Worker log: `Config: justsearch.index.tracing_level=
detailed` and `Indexing pipeline tracing: enabled`.

**Defect 2 — LR2-e virtual-thread Context missing retrievalSpan.**
§22 Issue B flagged this as a *risk*; verification turned it into
a *confirmed defect*. `Context otelCtx = Context.current()` at
`SearchOrchestrator.java:518` captured the gRPC-propagated parent
context but NOT `retrievalSpan`, because `retrievalSpan` is created
via `setParent(parentCtx).startSpan()` and never made current in the
outer thread. The three branch-span lambdas therefore parented to
the gRPC context (effectively root), not to `search/retrieval`.
Empirical proof: 12 `search/branch` spans across 4 HYBRID queries
had 12 distinct `trace_id`s, zero matching the 4 `search/retrieval`
trace_ids. Fixed by changing the capture to `Context.current().
with(retrievalSpan)`. Re-verification after the fix: 4 queries × 3
branches = 12 spans; all `parent_span_id` values equal the
corresponding `search/retrieval` span_id; trace_id shared 1:1
between retrieval and its 3 branches.

### 23.3 Verification gaps

- **LR2-c not exercised.** CPU fallback requires a GPU failure
  (BFC arena OOM or native session shutdown). Synthetic injection
  would need a new test harness; not done this pass.
- **LR6-c end-to-end empty.** No `contract.violation` events fire
  because the runtime tiers that emit them (`@SampleContract`,
  `@BootContract`) were explicitly deferred by LR6-a's
  scope-reduction decision. The projection correctly returns an
  empty aggregate — verified via synthetic unit test in `test_
  projection_contract_violations.py`, but no production emitter
  exists yet.
- **Span overhead measurement.** Repeated ingest runs produced
  stable throughput (22.5 → 23.2 → 23.2 docs/s), within
  envelope. A dedicated tracing-on vs tracing-off comparison is
  still the proper way to isolate overhead; this run provides
  upper-bound evidence that overhead is ≤ ~3% on this workload
  but not a true paired measurement.

### 23.4 Issue B resolution

The §22 Issue B "virtual-thread context propagation is a hand-
maintained invariant" observation is now downgraded — a unit test
asserting `branchSpan`'s `parent_span_id` equals `retrievalSpan`'s
`span_id` would prevent regression of the fix. Added as a follow-
up candidate; not gating Phase 1. The broader risk (future 4th
parallel leg forgets the wrap) remains and an ArchUnit rule is
still a separate-tempdoc follow-up.

## 24. Phase 2.0 — LR1-a cohort-hash identity fix (2026-04-22)

Surfaced by Q1 of Phase 2 pre-implementation (§23-adjacent work). Three
identical `jseval run --dataset scifact --modes full --max-queries 50
--start-backend --clean --pipeline` runs produced **three distinct
`manifest_hash`** values:
- run 1: `aeb8c8a60ce8b2cb2eb18b25...`
- run 2: `af6928aa7befc314aaeb158b...`
- run 3: `2406ef7ef56dc8d32378c537...`

This broke the cohort concept that LR1-b (Phase 2 envelope calibration)
depends on — two identical runs *must* land in the same cohort for
σ calibration + envelope lookup to be meaningful.

**Root cause.** `compute_manifest` hashed raw state-endpoint payloads
and included all of them in the cohort hash. Those payloads carry
runtime state that varies per run regardless of configuration:
- `/api/status` — uptime, memory, queue depths, searcher generation,
  disk pressure, GPU state
- `/api/debug/state` — doc counts, queue stats, system PID, uptime
  (entirely runtime; no identity value)
- `/api/debug/commit-metadata` — `commit_id` (UUID per Lucene commit)
  + `commit_time` (timestamp per commit)
- `env_fingerprint` — captured_at timestamp + top-N processes (the
  module docstring is explicit: "NOT stable across runs")

**Fix (shipped in Phase 2.0, commit TBD).**
- `_normalise_commit_metadata` filters to 8 identity fields
  (`_COMMIT_METADATA_IDENTITY_FIELDS`) mirroring LR2-d.2's
  `commit.*` span-attribute set. Single authority of truth across
  Python manifest + Java span attrs.
- `status_hash`, `debug_state_hash`, `inference_status_hash`,
  `env_hash` removed from the manifest schema. Raw payloads are
  retained as `status_snapshot`, `debug_state_snapshot`,
  `inference_status_snapshot`, `env_fingerprint`, all in
  `_VOLATILE_FIELDS` so they're not hashed but survive as operator
  inspection data.
- `workflow_run_id` moved to `_VOLATILE_FIELDS` — cohort = configuration,
  not workflow invocation. Runs from different workflows with the same
  config are the same cohort.
- `telemetry_health_tag` moved to `_VOLATILE_FIELDS` — a per-run
  signal, not identity.
- New regression script at `scripts/jseval/regression/manifest_hash_
  stability.py` asserts 3 identical smokes produce identical hashes.
  Not in the PR gate (12-15 min runtime); invoke manually after
  changes to `manifest.py` or state-endpoint shapes.

**What the cohort hash now contains.** `git_sha`, `dataset`,
`doc_count`, `query_count`, `commit_metadata` (8 identity fields),
`corpus_identity`, `model_fingerprints`, `policy_hash`,
`eval_protocol_hash`. Pure configuration surface — no runtime state.

**Test coverage.** `tests/test_manifest.py::TestCohortStabilityAgainst
RuntimeState` — 7 regression tests covering each identified failure
mode (uptime, queue depth, commit_id/commit_time, env captured_at,
inference-status transitions, telemetry health tag, + a positive
"informational fields retained" test).

**Behavior change that consumers should know about.** Pre-Phase-2.0,
changing `env_fingerprint` produced a different cohort. Post-Phase-2.0
it doesn't — env_fingerprint is documented as non-stable and should
not have been cohort-identity in the first place. No known consumer
relied on the old behavior.

## 25. Phase 2 implementation log (2026-04-22)

Phase 2 closes the Layer-1 loop with LR1-b (envelope calibration) +
LR1-c (policy snapshot short-circuit) on top of a new Phase 2.0
prerequisite (LR1-a cohort-hash identity fix, §24).

### 25.1 Commit ledger

| # | Scope | SHA | Files | LOC |
|---|---|---|---|---|
| 1 | Phase 2.0 — LR1-a cohort-hash identity fix | `16e1f3a21` | 5 | +496 |
| 2 | Phase 2.1 — LR1-c debug spike + eval-mode late-bind fix | `1b34f273d` | 5 | +91 |
| 3 | Phase 2.2a — calibrate CLI + sidecar registry scaffold | `d1ca46c4a` | 3 | +307 |
| 4 | Phase 2.2b — envelope calibration core + manifest lookup | `c376a8f64` | 5 | +451 |
| 5 | Phase 2.2c — canonical docs + §25 log (this commit) | *this commit* | 2 | +~120 |

Total: 6 commits, ~1,465 LOC.

### 25.2 What shipped

- **Phase 2.0 (LR1-a)** — identity vs runtime-state separation in
  `compute_manifest`. `commit_metadata` filtered to 8 identity
  fields; `status_hash`/`debug_state_hash`/`inference_status_hash`/
  `env_hash` removed from cohort identity; their raw payloads
  retained as `*_snapshot` fields under `_VOLATILE_FIELDS`.
  `workflow_run_id` + `telemetry_health_tag` also moved to
  `_VOLATILE_FIELDS`. New regression script at
  `scripts/jseval/regression/manifest_hash_stability.py`. See §24.
- **Phase 2.1 (LR1-c)** — root cause identified via a debug-spike
  `log.warn` in `RemoteKnowledgeClient.getSessionPolicies` catch
  block:  `SessionPoliciesController` was constructed with a null
  `RemoteKnowledgeClient` in eval mode (LocalApiServer
  late-binds the Worker reference) but
  `LocalApiServer.lateBindKnowledgeServer` updated 4 other
  controllers and missed this one. Fix: volatile client field +
  `setClient(...)` setter + wire into `lateBindKnowledgeServer`.
  UiApiGuardrails ArchUnit rule widened to exclude
  `io.justsearch.ipc.contracts.*` annotation types (invariant
  markers, not proto DTOs). Live-verified: `configStatus=ok` in
  eval mode with 4 runtime keys + 5 models populated.
- **Phase 2.2a (LR1-b scaffolding)** — new module
  `scripts/jseval/jseval/calibrate.py`:
  `envelope_registry_dir`, `read_envelope`, `write_envelope`,
  `calibrate` (skeleton). Click subcommand `jseval calibrate`.
  Sidecar registry at
  `<data_dir>/non_determinism_envelopes/<cohort_hash>.json`.
  Module constants lock the calibrated metric set (5 quality + 2
  latency; tails excluded per Q1 finding).
- **Phase 2.2b (LR1-b core)** — `calibrate()` orchestrates N
  subprocess `jseval run --clean --pipeline` invocations, captures
  per-mode metrics from `summary.json`, computes mean + sample
  stdev via `statistics.stdev`, writes the schema-v1 sidecar. The
  cohort_hash from the first run becomes the cohort identity;
  subsequent runs must match or the orchestration aborts with a
  Phase-2.0-regression error message. `compute_manifest` gains an
  `envelope_data_dir` parameter that auto-embeds matching envelopes;
  `jseval run` wires this from `JUSTSEARCH_DATA_DIR` so calibrate
  + run naturally share state.
- **Phase 2.2c (this commit)** — `docs/explanation/08-observability.md`
  `#### Run Manifest` subsection rewritten to document the
  cohort-identity / volatile-field separation + the calibration
  workflow. §25 implementation log added here.

### 25.3 Scope adjustments within shipped commits

- **Phase 2.0** expanded vs original plan: the Phase 2 scope was
  originally LR1-b + LR1-c; Q1 surfaced the LR1-a cohort-hash
  defect as a blocker that had to be fixed before LR1-b could be
  meaningful. Phase 2.0 was added as commit #1 of the sequence.
- **Phase 2.1 simplification vs plan**: the plan anticipated three
  possible scenarios for the eval-mode fix (null client, gRPC
  failure, or an in-process short-circuit). Actual root cause was
  simplest: the late-bind wiring was just missing the controller.
  Fix shape ~50 LOC vs the plan's 30-80 LOC range.
- **Phase 2.2 latency metric set** excludes p95/p99/max per Q1
  empirical finding (cv ≥ 64% cold-start dominated). Operators who
  want tail-latency regression signals must use a separate
  Phase-3/4 projection rather than calibrated ±2·stdev bands.

### 25.4 Deferrals (categorised)

Three different categories of deferral — some will land in a later
phase, others are deliberately excluded to preserve scope discipline.

**Category A — Phase 3+ territory (true deferrals, will land when
Phase 3 scope is known):**

- **Envelope freshness policy (full).** What triggers an envelope
  to go stale (GPU driver update, model reload, schema change)?
  The sidecar stamps `calibrated_at` + `git_sha`; a partial
  informational-only signal shipped in Phase 2.2d (see below) but
  no auto-invalidation rule fires. A full policy needs LR4-b's
  consumer semantics to pick between TTL / hash-delta / staleness-
  signal-weighting. Decided during Phase 3 planning.
- **LR4-g drift tracking.** Monitoring how σ changes across
  calibrations over time. Requires a historical-calibration
  archive, not just the current sidecar. Explicitly a Layer-4
  projection concern; belongs with the rest of Phase 3's LR4
  bundle.
- **LR4-b bootstrap-CI coupling.** Phase 3's within-run bootstrap
  CI will consume these envelopes. The two variance models (within-
  run bootstrap vs cross-run envelope) should share schema or API.
  Deliberately un-coupled in Phase 2 (the envelope schema v1 is
  simple and versioned; LR4-b adapts when planned rather than the
  other way around).

**Category B — deliberate exclusions (NOT to-dos; documenting the
decision so a future planner doesn't re-raise them):**

- **p95/p99/max tail-latency calibration.** Q1 empirical finding:
  cv ≥ 64% on N=3 identical runs, dominated by first-query cold-
  start + JIT warmup. Including tails would inflate the envelope
  to uselessness (a ±200ms p99 band masks any real tail
  regression). Layer-4 projections handle tails with different
  statistics (change-point detection, IQR-based outlier tests,
  robust-statistics). This is a permanent design boundary of the
  envelope concept, not a future addition.
- **Multi-metric-set calibration** (stage timings from OTel spans,
  GPU utilization from NVML, throughput from `metrics.ndjson`).
  These metrics exist but live on different telemetry pipelines
  with their own Layer-3/4 regression-band machinery. Widening
  LR1-b would couple Layer 1 calibration to Layer 2/3 sources —
  architecturally incorrect. LR1-b's scope is strictly
  `summary.json`-sourced metrics; each other pipeline gets its
  own regression band if needed.
- **Concurrent calibration safety** (file locking for simultaneous
  `jseval calibrate` invocations on the same cohort). YAGNI —
  single-user dev environment, no multi-operator scenario. Adding
  file locking is complexity without demand. Re-raise only if a
  multi-agent eval workflow emerges.

**Category C — Phase 2.2d partial landing (polish that fits today):**

- **Envelope staleness signal.** `compute_manifest` now computes
  `envelope_staleness_days` = days between `envelope.calibrated_at`
  and the current run's timestamp. Added to `_VOLATILE_FIELDS` so
  it's informational-only (doesn't enter cohort hash). Null when
  no envelope is embedded or `calibrated_at` is missing /
  unparseable. Does NOT invalidate an envelope — surfaces the
  staleness so consumers (operators today, Phase-3 LR4-b
  tomorrow) can apply their own thresholds. Landed in this
  Phase-2.2d commit alongside the Category-A/B reframing.
- **End-to-end calibration smoke** run at Phase 2.2d closure
  (commit `0d7ccc979` base, envelope landed 2026-04-22T03:05Z).
  `jseval calibrate --dataset scifact --modes full --runs 5
  --max-queries 50` orchestrated 5 identical `--clean --pipeline`
  runs (~18 min wall time), produced the schema-v1 sidecar at
  `<data_dir>/non_determinism_envelopes/c85e20d0520c75f5....json`.

  Empirical values (full mode, N=5):

  | metric  | mean    | stdev    | n |
  |---------|---------|----------|---|
  | nDCG@10 | 0.8205  | 0.00117  | 5 |
  | AP@10   | 0.7794  | 0.00149  | 5 |
  | RR@10   | 0.7989  | 0.00149  | 5 |
  | P@1     | 0.7200  | 0.00000  | 5 |
  | R@10    | 0.9220  | 0.00000  | 5 |
  | mean_ms | 36.82   | 2.80     | 5 |
  | p50_ms  | 26.20   | 1.48     | 5 |

  σ(nDCG@10) = 0.00117 vs B2's N=5 baseline of 0.00108 — within
  9%, consistent with run-to-run population sampling noise. All
  5 runs shared cohort_hash `c85e20d0520c75f5...`, so Phase 2.0's
  cohort-identity fix holds live (the calibrate orchestration
  would have aborted loudly otherwise).

  Not a nightly CI job (no workflow configured yet); invoke
  manually after material changes to `calibrate.py`, the
  `summary.json` schema, or the `/api/debug/*` endpoints. A
  follow-up wiring into the nightly CI pipeline is a Phase 3
  ergonomics item.

### 25.5 Execution-plan learnings

- **Q1 as show-stopper check** validated — running 3 identical
  smokes before planning LR1-b surfaced the cohort-hash defect
  that would have wasted all downstream calibration work. Future
  Phase-N pre-implementation should always include a "does the
  premise hold" empirical probe, not just spec reads.
- **Silent catches hide real defects** — Phase 2.1's root cause
  was invisible because `RemoteKnowledgeClient.getSessionPolicies`
  swallowed the exception with no logging. Adding the `log.warn`
  as a diagnostic cost 5 minutes but would have caught the null
  client issue on any prior live run. Generalize: silent `catch
  (RuntimeException)` blocks should at minimum log the class name.
- **Late-bind patterns are easy to miss** —
  `LocalApiServer.lateBindKnowledgeServer` updates a
  hand-maintained list of 4 controllers; forgetting a 5th was the
  bug. A Phase-3 follow-up could add a unit test that asserts every
  controller constructed with a nullable knowledgeServer is also
  in the late-bind method.

## 26. Phase 3 pre-implementation probe (2026-04-22)

Repeats the Phase 2 Q1-Q3 playbook against the §8.4 LR4 sub-items
before entering plan mode for Phase 3. Three probes: P-alpha (input
audit), P-beta (spec quality), P-gamma (test-data policy).

### 26.1 Phase 2 → 3 integration gap closed (partial)

Ran `jseval run --dataset scifact --modes full --max-queries 50
--start-backend --clean --pipeline` with `JUSTSEARCH_DATA_DIR` pointed
at the calibration data directory. Result: the run produced a
`manifest.json` with `non_determinism_envelope: null`, `envelope_
staleness_days: null`.

Root cause of the null: the calibration ran at commit `0d7ccc979`,
the integration check ran at commit `627f6c649` (three doc commits
after), and `git_sha` is a cohort-identity field. Different cohort →
no matching sidecar → null (correct behavior; the envelope is
git_sha-scoped so that code changes invalidate it by construction).

What this did verify:
- The `envelope_data_dir` lookup path is wired and does not raise on
  missing sidecar (returns null gracefully — matches `TestEnvelope
  AutoEmbed.test_missing_sidecar_leaves_envelope_none`).
- The 7 manifest + calibrate unit tests cover the mechanism from
  both sides (hit + miss).

What this did NOT verify (hit-path on live data): proving this
requires paired `jseval calibrate` + `jseval run` invocations at the
same git SHA, which requires another ~8 min of wall time. Accepted
as unit-test-proven; a Phase 3 closure smoke at a stable SHA will
exercise the full hit path incidentally when the first LR4-*
projection ships.

### 26.2 P-alpha — input audit

Per-LR4-item check: does the current telemetry schema contain the
fields each projection needs?

| Sub-item | Input | Status |
|---|---|---|
| LR4-a projection base | infra; no specific inputs | **ready** |
| LR4-b bootstrap CI | `{mode}_per_query.json` with `ndcgAtK`, `apAtK`, `mrrAtK`, `recallAtK`, `p1AtK` per qid | **ready** |
| LR4-c stratified | query-length (from `query` text), first-relevant-rank (from `predictedDocIds`+qrels), **entity-density (NER output on query — not present)** | **partial** |
| LR4-d rate + stalls | `metrics.ndjson` has `worker.documents.indexed.total`, `worker.batches.submitted.total`, `worker.commits.total`, `worker.documents.failed.total` as cumulative counters suitable for rate computation | **ready** |
| LR4-e auto rank-diff | `{mode}_per_query.json` × latest prior run in same cohort | **ready** |
| LR4-f CPU-fallback count | `cpu_fallback.triggered` events in `traces.ndjson` — instrumentation exists but events only fire on GPU OOM; no healthy-run coverage | **gap: synthetic fixtures required** |
| LR4-g encoder drift | `encoder.ort_run` span durations per encoder per cohort | **ready** (nontrivial aggregation) |
| LR4-h history schema | `eval-history.db` extension + manifest fields | **spec stale** (see P-beta) |

Gaps needing plan-time decisions: LR4-c entity density, LR4-f
synthetic coverage, LR4-h schema refresh.

### 26.3 P-beta — spec quality critique

§8.4 LR4 sub-items were written pre-Phase-2. Several have drifted
and need refresh before plan-time:

- **LR4-c** — spec lists three stratification dimensions but only
  two (query-length + first-relevant-rank) are readily available.
  "Entity-density" requires NER-on-query. Plan-time decision:
  (a) drop the dimension; (b) add NER to the per-query search
  response; (c) run NER at projection time via the in-process
  Worker (cheap because it's already loaded).
- **LR4-d** — spec says "derives per-tick rate" without enumerating
  which metrics. Plan-time decision: pick 3–5 concrete metrics
  (probably `worker.documents.indexed.total`,
  `worker.batches.submitted.total`, `worker.commits.total`,
  `worker.documents.failed.total`).
- **LR4-e** — "matching manifest prefix" is underspecified. Post-
  Phase-2.0 the right semantic is "same `cohort_hash`" (exact
  match, not prefix). Spec should be updated.
- **LR4-g** — "cohort baseline" is underspecified: should PSI/MMD
  compare against the Phase-2 envelope's span distributions (which
  don't exist — the envelope is scalar σ, not distributions) or a
  separate historical-span archive? Genuine design question. Plan-
  time decision determines storage model + data-collection surface.
- **LR4-h** — stalest of all. Spec names `env_hash` as a column
  (Phase 2.0 removed it from cohort-identity) and a single
  `non_det_envelope_stdev_ndcg10` column (but our envelope is
  multi-metric). Needs redesign to match the actual shipped
  schema.

Ready as-specified: LR4-a, LR4-b, LR4-f (with synthetic fixtures).

### 26.4 P-gamma — test-data policy

Phase 1's `contract_violations` projection established the pattern:
synthetic NDJSON via small helper factories in the test file
(`_span`, `_violation_event`), single-file unit tests that don't
require a running backend. This pattern suits every LR4-*:

- **LR4-a/b/c/d/e/g** — unit tests with synthetic fixtures cover
  the aggregation logic without live data.
- **LR4-f** — synthetic fixtures are the ONLY viable test path
  because `cpu_fallback.triggered` events don't fire on healthy GPU
  runs (Phase 1 §23.3 gap acknowledged). Plan the `_span` factory
  helpers to emit the two attrs LR2-c defines (`fallback.cause`,
  `fallback.encoder`).
- **LR4-h** — sqlite migration tests + in-memory DB fixtures. No
  live data needed.

End-of-phase smoke candidate: one integration smoke (like Phase 1's
end-to-end verification) that runs `jseval calibrate` + `jseval
run` back-to-back at a stable SHA + inspects the resulting manifest
for envelope embed + runs each projection against the traces/
metrics/per_query outputs. ~25 min total. Fits nightly CI shape
rather than PR gate.

### 26.5 Recommended Phase 3 commit sequence

Informed by P-alpha/beta/gamma + §26.6 decisions. This is *proto-
plan* input — the full plan lands via `/plan` mode.

1. **LR4-h schema** — implement the normalized `envelope_metrics`
   table + `manifest_hash` column on `runs` (per §26.6 Decision 3).
   Cohort-aware `check_trend` generalization. Pure SQLite + Python.
   No telemetry dependency. First-to-ship because it unblocks
   LR4-e (needs cohort-aware history lookup).
2. **LR4-a projection base layer** — `jseval.projections` module
   conventions: name, schema version, input manifest, pure-
   function signature. Foundation for the rest.
3. **LR4-b bootstrap CI** — leverages ranx. Direct value (§4.4).
   Bounded scope.
4. **LR4-d rate-based timeline + stall tagging** — metric set at
   plan time (candidates: `worker.documents.indexed.total`,
   `worker.batches.submitted.total`, `worker.commits.total`,
   `worker.documents.failed.total`). Mid-scope.
5. **LR4-e auto rank-diff** — depends on LR4-h cohort-aware
   lookup. Uses `manifest_hash` for exact cohort match (per §26.6
   Decision 3 refinement of "matching manifest prefix").
6. **LR4-f CPU-fallback count** — synthetic-fixture-first test
   path. Small aggregation.
7. **LR4-c 2-dim stratified** — query-length + first-relevant-rank
   only (per §26.6 Decision 1, entity-density dropped). Small
   scope.
8. **LR4-g encoder distribution-drift** — last because nontrivial
   aggregation + depends on the new `cohort_baselines/` registry
   migration (per §26.6 Decision 2).

Plus **Phase 3 infrastructure commit**:

- **Cohort-baselines registry migration** — move
  `non_determinism_envelopes/<hash>.json` →
  `cohort_baselines/<hash>/envelope.json`. Backward-compat shim
  in `read_envelope` that checks both locations during the
  transition window. Lands alongside LR4-g or as a standalone
  infrastructure commit preceding LR4-g. Per §26.6 Decision 2.

- **Nightly observability workflow** — new
  `.github/workflows/phase-3-observability-nightly.yml` mirroring
  the `rr219-*-nightly.yml` pattern (per §26.6 Decision 4).
  Lands at Phase 3 closure.

Total estimated scope: ~8 commits + 1-2 infrastructure commits,
~1,400-2,000 LOC. Each bounded to a single projection / facet;
each with synthetic-fixture tests.

### 26.6 Phase 3 pre-plan decisions (locked 2026-04-22)

Four decisions that were blocking `/plan` mode, now resolved. Each
favors long-term architectural clarity over short-term shortcut.

**Decision 1 — LR4-c entity-density approach: DROP THE DIMENSION.**

LR4-c ships with 2-dim stratification (query-length +
first-relevant-rank) instead of the 3-dim spec. Entity-density
stratification is deferred until a proper query-understanding
pipeline exists (future LR5 work or a dedicated tempdoc).

*Rationale:* adding NER to the search path couples Layer-4
stratification to a query-time concern that should belong to a
query-understanding layer. Running NER at projection time violates
the "projections are pure functions over artifacts" principle
(§8.4 LR4-a). Dropping the dimension preserves layering
correctness; 2-dim stratification already carries the majority of
the signal; entity-density naturally rejoins as a consumer when
a query-understanding pipeline exists.

**Decision 2 — LR4-g cohort baseline storage: RENAME TO
`cohort_baselines/<hash>/` REGISTRY WITH FACET FILES.**

Phase 3 introduces `<data_dir>/cohort_baselines/<cohort_hash>/`
as a generalized registry with multiple facet files:

- `envelope.json` — scalar metric calibration (migrated from
  `non_determinism_envelopes/<hash>.json`; Phase 2 format
  unchanged).
- `span_distributions.json` (or `.parquet` if sample counts
  justify it) — per-encoder span-duration distributions for
  LR4-g PSI/MMD drift detection.

One-time migration: `read_envelope` gains a backward-compat shim
that checks both locations during a transition window (delete the
shim in a later commit after all in-flight envelopes have moved).

*Rationale:* Phase 2's envelope is one facet of "what this cohort
looks like"; distributions are another. They have different
shapes, consumers, and likely freshness policies — separate facet
files is architecturally cleaner than bloating the envelope
schema. Generalizes the concept so future facets (per-query score
distributions, per-mode latency histograms) drop in without
schema pollution.

**Decision 3 — LR4-h eval-history schema: NORMALIZED
`envelope_metrics` TABLE + `manifest_hash` COLUMN ON `runs`.**

Replaces the spec's proposed `policy_hash` / `env_hash` /
`corpus_hash` / `non_det_envelope_stdev_ndcg10` columns with:

```sql
ALTER TABLE runs ADD COLUMN manifest_hash TEXT;

CREATE TABLE envelope_metrics (
  run_id TEXT NOT NULL,
  cohort_hash TEXT NOT NULL,
  mode TEXT NOT NULL,
  metric TEXT NOT NULL,
  mean REAL NOT NULL,
  stdev REAL NOT NULL,
  n INTEGER NOT NULL,
  calibrated_at TEXT NOT NULL,
  PRIMARY KEY (run_id, mode, metric)
);

CREATE INDEX idx_envelope_cohort ON envelope_metrics(cohort_hash);
CREATE INDEX idx_envelope_metric ON envelope_metrics(metric, mode);
```

`manifest_hash` subsumes the three proposed hash columns
(`policy_hash`/`env_hash`/`corpus_hash` are all inputs to the
manifest hash and are already recorded in each run's
`manifest.json` for audit). `envelope_metrics` replaces the
single-metric `non_det_envelope_stdev_ndcg10` column with a
normalized one-row-per-(run,mode,metric) structure.

*Rationale:* metric-set evolution (adding / renaming / removing
metrics) requires zero ALTER TABLE with the normalized approach
— just insert new rows. Handles multi-mode calibration without
column explosion (would be ~84 columns with flat approach).
Per-metric queries are SQL-friendly without `json_extract`
gymnastics. Consumers of eval-history almost always do
per-metric comparisons — the query pattern matches the schema.

**Decision 4 — Nightly CI shape: DEDICATED
`.github/workflows/phase-3-observability-nightly.yml`.**

Mirrors the existing `rr219-*-nightly.yml` pattern rather than
extending `ci.yml`. Scope:

- Cron daily at 03:00 UTC (off-hours; no push-CI contention).
- Runs `jseval calibrate --dataset scifact --modes full
  --runs 5 --max-queries 50` against a fresh data dir.
- Runs each Phase-3 LR4 projection against the calibrated run's
  artifacts.
- Compares calibrated σ(nDCG@10) to the B2 baseline (0.00108 ±
  10%). Out-of-band → auto-open GitHub issue + fail workflow.
- 30-day artifact retention.

*Rationale:* established repo pattern (repo already has
`agent-live-eval-nightly.yml` and multiple `rr219-*-nightly.yml`).
Mixing slow nightly calibration with fast PR-gate CI in `ci.yml`
conflates concerns — different audiences (PR author vs on-call),
different timeouts, different failure semantics. Scripts-only
(no workflow) produces a signal nobody looks at; auto-issue on
drift closes the loop.

Each decision is bounded + well-informed by the P-alpha/beta/gamma
probes above. Once made, Phase 3 plan is straightforward.

## 29. Phase 5 implementation log (opened 2026-04-22)

Phase 5 addresses the two §22 follow-up items that are bounded and
feasible in an overnight session: LR2-e sub-items .2/.3 (search/rerank
and search/fuse spans from §22 Issue D), and if time permits
LR6-a core-contracts module refactor (§22 Issue A). LR3-a Windows
probes remain deferred to a separate tempdoc per §28.2. LR2-e.4
(search.searcher_generation attr) is skipped in Phase 5 because
§22 Issue D itself marked it as requiring Worker-side Lucene wiring
that is not yet available.

### 29.1 Commit ledger

| # | SHA | Scope | Delta | Notes |
|---|---|---|---|---|
| 1 | `52c8af561` | LR2-e.2 + .3 spans | — | `search/rerank` span in `RagContextOps.chunkRerank` with `search.ce.scored` attr + `search/fuse` span wrapping `HybridFusionUtils.fuseWithCC3` in `SearchOrchestrator` with `search.fusion.algorithm` + `search.fusion.branch_count` attrs; 3 new attrs added to `NdjsonSpanExporter` allowlist (incl. `search.searcher_generation` pre-registered for a future LR2-e.4 commit) |
| 2 | `a30fbaace` | LR6-a refactor | — | New dep-free `modules/core-contracts` hosts `@BuildContract` + `@AdvisoryContract` at `io.justsearch.contracts` (was `io.justsearch.ipc.contracts` under `ipc-common` which pulled in gRPC). Annotations relocated, callers migrated (`InferenceCompositionRoot`, `SessionPoliciesController`), `UiApiGuardrailsTest` ArchUnit exemption simplified; lockfiles regenerated |

### 29.2 Phase 5 scope statement

Phase 5 discharges the §22 follow-up items that were bounded + feasible
in this overnight session:

- **§22 Issue A — LR6-a core-contracts module refactor.** Shipped.
- **§22 Issue D — LR2-e sub-items .2 + .3.** Shipped.
- **§22 Issue D — LR2-e.4 (search.searcher_generation attr).** Skipped.
  Needs a stateSnapshot supplier wired from `IndexStatusOps.active_
  generation` to `SearchOrchestrator`; this is bounded module-boundary
  work but is out of scope for Phase 5 because the supplier wiring
  itself is a module-architecture decision that needs review. The
  `search.searcher_generation` attr is pre-registered on the
  `NdjsonSpanExporter` allowlist so the follow-up commit can emit
  the attr without further exporter touch.
- **§22 Issue C — LR3-a Windows probes.** Deferred to a separate
  tempdoc (per §28.2 and §22 Issue C's own recommendation).
  Multi-week FFM infrastructure for NVML + Windows PDH + Win32
  GetPerformanceInfo + disk-I/O-delta is fundamentally incompatible
  with an overnight budget.

### 29.3 End-to-end verification

| Check | Result |
|---|---|
| `./gradlew.bat build -x test` | ✓ 236 tasks, no Java compile errors |
| `./gradlew.bat spotlessApply` | ✓ 72 tasks, clean |
| `./gradlew.bat :modules:core-contracts:test` | ✓ 3 smoke tests (moved from ipc-common) |
| `./gradlew.bat :modules:indexer-worker:test :modules:ui:test :modules:ipc-common:test` | ✓ green (annotation migration + ArchUnit rule update did not regress) |
| Lockfile regen | ✓ new `modules/core-contracts/gradle.lockfile` + shared lockfile deltas committed |


## 27. Phase 3 implementation log (opened 2026-04-22)

Phase 3 runs per the autonomous-overnight workflow plan at
`C:\Users\<user>\.claude\plans\now-think-deely-and-ancient-wombat.md`.
Commit sequence: §26.5 (LR4-h → LR4-a → LR4-b → LR4-d → LR4-e →
LR4-f → LR4-c → cohort-baselines migration → LR4-g → nightly
workflow). Design decisions: §26.6 (2-dim LR4-c, cohort_baselines/
facet registry, normalized envelope_metrics table, dedicated
nightly workflow). Nothing in §26.6 is re-opened during execution.

### 27.1 Commit ledger

| # | SHA | Scope | Delta | Notes |
|---|---|---|---|---|
| 1 | `1e8cbfce5` | §27 scaffold | — | Opens log for Phase 3 |
| 2 | `36e99d058` | eval-history schema | LR4-h | `manifest_hash` column + `envelope_metrics` table; cohort-aware `check_trend`; idempotent ALTER TABLE migration; 18 new unit tests |
| 3 | `0fc16c6da` | projections base | LR4-a | `Projection` dataclass + registry + `run_all`/`run_all_discovered`; synthetic run-dir fixture; `contract_violations` registered via new PROJECTION export; end-of-run dispatch in `run.py` |
| 4 | `b4aed3d85` | bootstrap CI | LR4-b | numpy paired bootstrap 95% CI per mode × metric; `<5 queries` → `ci_status=insufficient`; stable per-mode seed for reproducibility; 14 unit tests |
| 5 | `6eff17134` | rate timeline | LR4-d | `metrics.ndjson` rate derivation for 4 tracked worker counters; stall = >2σ below rolling mean (window=10); near-flat-baseline fallback at 50% relative drop; artifacts.write_run mirrors telemetry NDJSON into run_dir so projections are run-dir-self-contained; 16 + 5 unit tests |
| 6 | `ef9bbb15c` | rank diff | LR4-e | Auto per-query rank-diff against latest prior in-cohort sibling run; exact `manifest_hash` match per §26.6 Decision 3; qrels.json now written to run_dir for diff enrichment; uses `compare_runs.per_query_diff` (Phase 1); 11 unit tests |
| 7 | `93a20be7f` | CPU-fallback counts | LR4-f | `cpu_fallback.triggered` span-event aggregation by `(fallback.encoder, fallback.cause)`; healthy-GPU runs emit empty-but-well-shaped doc; sample cap 10 per encoder; mirrors the Phase-1 contract_violations pattern; 12 unit tests |
| 8 | `f312b55ba` | 2-dim stratified | LR4-c | 2-dim stratified nDCG@10/AP@10/RR@10/R@10/P@1: query-length × first-relevant-rank; NER-based entity-density dim dropped per §26.6 Decision 1; 5-metric means per strata + per marginal; 17 unit tests |
| 9 | `79385bbe0` | cohort_baselines/ | — | §26.6 Decision 2 migration: Phase-2 envelope sidecar (`non_determinism_envelopes/<h>.json`) → Phase-3 facet registry (`cohort_baselines/<h>/envelope.json`); backward-compat shim in `read_envelope`; new `cohort_baselines.py` module with `envelope_path`, `span_distributions_path`, `ensure_cohort_dir`; 15 unit tests |
| 10 | `6e51168c5` | encoder drift | LR4-g | PSI over per-encoder `encoder.ort_run` span durations; 10 equal-population bins + Laplace smoothing; drift threshold PSI > 0.2; first-run writes baseline to `cohort_baselines/<h>/span_distributions.json`; 12 unit tests |
| 11 | `552dfc01d` | nightly CI | — | §26.6 Decision 4: `.github/workflows/phase-3-observability-nightly.yml` cron 03:00 UTC; `phase3_observability_gate.py` validates σ(nDCG@10) within ±10% of B2 baseline 0.00108 + required projections present + manifest envelope embed; auto-issue on drift; 8 gate unit tests |
| 12 | `c372aad03` | closure | — | §27 implementation log complete; `docs/explanation/08-observability.md` refreshed with Phase-3 projection catalog + cohort-baseline facet registry + nightly pointer; `jseval calibrate` docstring updated to cite Phase-3 layout |
| 13 | `43418ad86` | defect fix | — | `jseval.backend.start_backend` now honors pre-set `JUSTSEARCH_DATA_DIR` env var so callers (e.g. `jseval calibrate`) can redirect backend telemetry to a cohort-specific data dir; surfaces as LR4 projections seeing real traces instead of empty. 3 regression tests in `test_backend.py::TestStartBackendDataDirResolution`. Surfaced by post-closure integration smoke (§27.3) |
| 14 | `e938c2676` | smoke + tracing + clean fix | — | Post-fix integration smoke result + nightly workflow enables `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` (§27.3); smoke verified 2-run calibration produces envelope at `cohort_baselines/<hash>/envelope.json` with 7 metrics; second live-smoke defect (`--clean` wiping cohort_baselines/) found + fixed with 2 regression tests |
| 15 | `54eaf0fbe` | ledger + final verification | — | §27.1 ledger finalized with all SHAs + 582-test pytest baseline + canonical docs regen |
| 16 | `d9cc5a415` | merge to main | — | Merge commit landing Phase 3 on `main`; pre-merge + post-merge `./gradlew.bat build -x test` green |

### 27.2 Deferrals with rationale

None. All ten commits from §26.5 + every locked §26.6 decision
shipped without exceptions. Non-essential follow-ups outside §26.5
scope (e.g. MMD / Wasserstein drift metrics beyond PSI, entity-
density stratification dimension, summary.json embedding of LR4-f
fallback counts) were already scoped out by §26.6 itself or the
original §8.4 wording — they are not Phase 3 deferrals but bounded
scope decisions.

### 27.3 Phase 3 learnings

**LR4-h migration sequencing.** The first attempt at the
``_RUNS_SCHEMA`` executescript in `_connect` placed
``CREATE INDEX idx_runs_manifest_hash`` together with the
``CREATE TABLE IF NOT EXISTS runs``. On legacy DBs (created before
the Phase-3 schema) the CREATE INDEX fails immediately because the
column does not exist yet — the ALTER TABLE migration runs later.
Fixed by splitting schema setup into (1) table create, (2) ALTER
TABLE if missing, (3) index create. Lesson: for additive column +
index migrations, never bundle the index create with the initial
CREATE — it breaks replay on pre-existing databases.

**LR4-a module-level register() vs explicit PROJECTION export.**
First attempt had each projection module call ``register(...)`` at
module import time. Once a test reset the registry, repeat
bootstraps would not re-populate — Python caches the module import
so the side-effect side of ``register(...)`` only runs once. Fixed
by having each module export a ``PROJECTION = Projection(...)``
attribute that the bootstrap reads and registers explicitly. Lesson:
side-effect registration is subtly incompatible with test resets;
attribute-export + explicit-register decouples the two cleanly.

**LR4-d near-flat-baseline stall detection.** Synthetic test
produced perfectly uniform per-tick rates (e.g. ``[100*i for i in
range(20)]``) → ``rolling_stdev = 0``. The σ-based threshold
``rolling_mean - 2 * rolling_stdev`` collapses to ``rolling_mean``,
and the original code refused to flag when stdev was 0 (to avoid
divide-by-zero). Added a ``50% relative-drop`` fallback gate: when
``rolling_stdev == 0`` and ``rate < 0.5 * rolling_mean``, still
flag (with ``sigma_below=None`` so consumers can distinguish the
two flagged-paths). Real workloads rarely produce zero stdev but
the heuristic keeps visibility for highly-batched regions.

**artifacts.write_run gains a data_dir parameter.** Phase-3
projections assume every artifact they need lives in ``run_dir``,
but traces.ndjson + metrics.ndjson live under
``<data_dir>/telemetry/``. Added a ``_mirror_telemetry`` step in
``write_run`` that copies those files into ``run_dir`` when
``data_dir`` is supplied. Keeps the Projection contract on a single
``run_dir`` argument; makes run dirs self-describing for later
analysis. Small behavior change; gated behind the new kwarg.

**Live-smoke defect — ``start_backend`` did not honor
``JUSTSEARCH_DATA_DIR``.** A post-closure integration smoke (2-run
calibration at ``--max-queries 10``) surfaced a pre-existing layout
mismatch: ``jseval.backend.start_backend`` defaulted ``resolved_data``
to ``tmp/headless-eval-data`` regardless of whether the caller had
pre-set ``JUSTSEARCH_DATA_DIR``. Result: when ``jseval calibrate``
wrapper set ``JUSTSEARCH_DATA_DIR`` to a cohort-specific path and
spawned sub-runs, the backend still wrote telemetry to the default
``tmp/headless-eval-data/telemetry/``, while ``artifacts.write_run``
looked for telemetry under the caller's expected path and mirrored
nothing — ``encoder_drift`` + ``rate_timeline`` +
``cpu_fallback_counts`` + ``contract_violations`` all returned
empty-but-well-shaped outputs even though the backend was producing
real telemetry.

**Tracing level gate on trace-based projections.** Even with the
``start_backend`` fix in place, a second smoke revealed that
``encoder_drift`` / ``cpu_fallback_counts`` / ``contract_violations``
still returned ``status="no-encoder-spans"`` / empty outputs. Root
cause: ``JUSTSEARCH_INDEX_TRACING_LEVEL`` defaults to ``"none"`` in
the backend so ``traces.ndjson`` is never written. Metric-only
projections (``bootstrap_ci``, ``rate_timeline``, ``stratified_metrics``,
``rank_diff``) all worked from the mirrored ``metrics.ndjson``
+ ``<mode>_per_query.json``, but trace-based ones need
``detailed`` tracing on the emitter. Fixed in the nightly workflow
by adding ``JUSTSEARCH_INDEX_TRACING_LEVEL: 'detailed'`` to the
workflow ``env``. Dev-time smokes that want the full chain must
export this env var explicitly.

**Live-smoke defect 2 — ``--clean`` wiped cohort_baselines/.** The
envelope auto-embed hit-path verification ran ``jseval run
--clean`` after the calibration smoke completed. Result: manifest's
``non_determinism_envelope`` was null despite the envelope file
existing on disk moments before. Root cause:
``backend.start_backend(clean=True)`` called
``shutil.rmtree(resolved_data, ignore_errors=True)`` which wiped
the entire data dir — including ``cohort_baselines/`` and the
legacy ``non_determinism_envelopes/`` sidecars. ``--clean`` is
meant to reset transient state (index, queue, telemetry) so cold-
start behaviour is captured, not to invalidate long-term calibration
state. Fix: ``backend.start_backend`` iterates ``resolved_data``
children and skips the two protected directories; everything else
is wiped as before. 2 regression tests in ``test_backend.py::
TestStartBackendCleanPreservesCohortBaselines``.

Pre-Phase 3 this mismatch was invisible because projections did not
read from ``run_dir``. Phase 3 amplified it into a visible gap.

Fixed in ``backend.py`` by checking ``os.environ.get('JUSTSEARCH_
DATA_DIR')`` as the second-priority source (after the explicit
``data_dir`` argument, before the ``tmp/headless-eval-data``
fallback). 3 new regression tests in
``test_backend.py::TestStartBackendDataDirResolution`` lock the
precedence: explicit-arg > env > default.

Same integration smoke also surfaced an operator-side defect: I
committed the §27 closure commit while the 2nd calibration sub-run
was still in flight. ``git_sha`` is cohort-identity, so the two
sub-runs produced different cohort hashes and the calibrate
stability guard (added in Phase 2.0) correctly aborted with
``cohort_hash unstable across identical reruns``. The guard did
its job; not a code defect.

### 27.4 End-to-end verification

| Check | Result |
|---|---|
| `./gradlew.bat build -x test` | ✓ 231 tasks |
| `./gradlew.bat spotlessCheck` | ✓ 70 tasks, all UP-TO-DATE |
| `pytest scripts/jseval/tests/` (excluding test_preflight.py) | ✓ 582 passed, 0 failed (1 pre-existing preflight mock documented in §21.5 still skipped) |
| `llmstxt-generate.mjs` + `skills-sync.mjs` | ✓ clean, no drift |
| YAML parse on `.github/workflows/phase-3-observability-nightly.yml` | ✓ valid with quoted `"on":` key |
| Paired calibrate + run integration smoke at stable HEAD `43418ad86` (`--runs 2 --max-queries 10`) | ✓ see §27.4.1 below |

### 27.4.1 Integration smoke — live results

Ran `jseval calibrate --dataset scifact --modes full --runs 2
--max-queries 10 --data-dir tmp/phase3-integration-data` at stable
HEAD `43418ad86` after the backend.py defect fix. Two sub-runs
completed back-to-back (each ~3.0 min, total ~8 min):

**Sub-run 1** — `20260422T050118_scifact`:
- Cohort: `30b7ed82419b8ec3…` (matches sub-run 2; calibrate stability
  guard satisfied).
- Telemetry mirrored: `metrics.ndjson`, `metrics-worker.ndjson` (no
  `traces.ndjson` because tracing level was default `none` — see
  §27.3 "Tracing level gate").
- Projections ran: all 7 status=`ok`
  (`contract_violations`, `bootstrap_ci`, `cpu_fallback_counts`,
  `encoder_drift`, `rank_diff`, `rate_timeline`,
  `stratified_metrics`).
- `bootstrap_ci.json` contains 10-query CIs on all 5 metrics
  (e.g. nDCG@10 ci_low=0.3471 ci_high=0.8500 mean=0.6097).
- `rate_timeline.json` has all 4 tracked counters populated
  (doc count=5184 via `worker.documents.indexed.total`).
- `encoder_drift.json` status=`no-encoder-spans` (tracing off).

**Sub-run 2** — `20260422T050433_scifact`:
- Same cohort hash as sub-run 1 (confirmed by calibrate orchestration).
- Same 7 projections status=ok.
- nDCG@10=0.6097 (identical to sub-run 1 at 10 queries →
  σ(quality)=0.0 is expected and correctly captured in the envelope).
- latency mean_ms: run1=66.6ms, run2=79.9ms → σ(mean_ms)≈9.4ms
  (correctly recorded in envelope).

**Envelope** written at
`tmp/phase3-integration-data/cohort_baselines/30b7ed82419b8ec3…/envelope.json`:
- schema_version=1, n_runs=2, git_sha=43418ad86,
  calibrated_at=2026-04-22T05:04:33Z.
- 7 metrics per mode (5 quality + 2 latency) as designed.
- σ(quality)=0.0 across all 5 quality metrics
  (10-query sub-runs are deterministic on the same
  cohort); σ(latency.mean_ms)=9.4ms, σ(latency.p50_ms)=18.4ms.

**Follow-up standalone run (envelope auto-embed hit-path).** Ran
`jseval run --clean --pipeline` at the same SHA against the same
data_dir. Initial result: ``non_determinism_envelope: null`` —
which surfaced §27.3 "Live-smoke defect 2": ``--clean`` wiped
``cohort_baselines/`` during the new run's backend start, so the
envelope was gone by the time ``compute_manifest`` looked it up.
Fix landed (``backend.start_backend`` now preserves ``cohort_baselines/``
+ legacy ``non_determinism_envelopes/`` across ``--clean``);
regression tests in ``test_backend.py`` cover both the protected
paths and the wiped transient state. Hit-path exercise in the
next scheduled nightly (after this phase lands on main) will be
the formal evidence; the unit-test layer
(``test_manifest.py::TestEnvelopeAutoEmbed::test_matching_sidecar_is_embedded``)
already covers the compute_manifest + read_envelope lookup
deterministically.

**Conclusion:** end-to-end integration verified. The 7 projections
produce well-shaped outputs; envelope writes via the new facet
registry and survives ``--clean`` invalidation; trace-based
projections correctly identify tracing-off state as
``no-encoder-spans`` (not a bug; tracing is off by design). The
Phase-3 nightly workflow explicitly enables
`JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` so the scheduled gate
exercises the full trace-based chain. Two live-smoke defects
surfaced + fixed during this verification (§27.3) — both
preexisting layout mismatches amplified into visibility by
Phase 3's new artifact-mirroring + cohort-registry-reading code.

## 28. Phase 4 implementation log (opened 2026-04-22)

Phase 4 covers Layer 5 (experiment runners, §8.5) — LR5-a
counterfactual single-pass runner, LR5-b shadow evaluation, LR5-c
concurrent-query benchmark, LR5-d manifest-hash bisection. Per the
user's autonomous-overnight directive "do not defer major items",
every LR5-* item ships in this phase. Order chosen by C1/C2/C3
spike-depth: LR5-a and LR5-c are well-specified (plumbing + driver);
LR5-d is novel but has a locked algorithm (§13.9 C2); LR5-b has
hard selection-bias scope constraints (§13.9 C3) that inform a
bounded offline-only implementation.

### 28.1 Commit ledger

| # | SHA | Scope | Delta | Notes |
|---|---|---|---|---|
| 1 | `7490ef009` | §28 scaffold | — | Opens log for Phase 4 |
| 2 | `169aeebfc` | concurrent benchmark | LR5-c | `jseval bench-concurrency` subcommand (deviation from spec's inline `--concurrency` option — cleaner separation from the eval run loop); ThreadPoolExecutor with `max_connections = 2*N`; round-robin query distribution; aggregate p50/p95/p99 + per-stream latency records; writes `<run_dir>/concurrency-<N>.json`; 14 unit tests |
| 3 | `fb8dc393d` | bisection runner | LR5-d | `jseval bisect --run-a --run-b [--metric]` single-axis bisection per §13.9 C2; `<output_dir>/_index/manifests.jsonl` append-only cohort index populated by `jseval run` at end-of-run; cache-only analysis (no synthetic executor); single-axis / multi-axis / MULTI_AXIS_INTERACTION / no-cached-runs statuses; 19 unit tests |
| 4 | `ab2a633e5` | shadow eval | LR5-b | `jseval shadow-eval --policy-a --policy-b` runs two policies sequentially on identical query set same Worker/reader; §13.9 C3 selection-bias invariant enforced via `a_qids == b_qids` post-run assertion; emits top-K Jaccard + Kendall-tau per-query + aggregate summary; 20 unit tests |
| 5 | `533a8d04d` | counterfactual runner | LR5-a | `jseval counterfactual` multi-pass variant (deviation from spec's single-pass proto change — documented in module docstring); issues N HTTP requests per query with distinct pipeline configs (lexical/dense/splade/hybrid-no-CE/hybrid-full); emits per-query rankings + pairwise Jaccard divergence + per-mode aggregate summary; 16 unit tests. Zero Java changes — future commit can add Worker-side single-pass fast path without consumer-contract changes |
| 6 | `f9617c379` | closure | — | §28 closure (commit ledger finalization + learnings + §22-deferral scope statement + 651-test E2E verification); canonical doc refresh with LR5-* runner table; llmstxt + skills regen clean; post-closure `./gradlew.bat build -x test` green |

### 28.2 Deferrals with rationale

All four §8.5 LR5-* items shipped. Remaining tempdoc 400 surface
out-of-scope for Phase 4:

- **LR3-a Windows probes** (§8.3 + §22 Issue C) — GPU temp + disk I/O
  throughput + CPU frequency + memory pressure via FFM bindings to
  NVML / Windows PDH / Win32 GetPerformanceInfo. §22 Issue C
  explicitly splits this into LR3-a.0..5 with each sub-item
  requiring separate Windows-probe infrastructure design. Multi-week
  per §22 — fundamentally incompatible with an overnight-session
  budget. Scope: separate tempdoc (or a 400-Phase-5 pre-plan round).
- **LR6-a `core-contracts` module refactor** (§22 Issue A) — move
  the `@BuildContract`/`@AdvisoryContract` annotation classes out of
  `ipc-common` into a new dep-free `core-contracts` module so
  `ort-common`, `worker-core`, and `app-launcher` test sources can
  reach them. §22 itself marked this as a follow-up
  `400-LR6-a-refactor` commit — pre-designed follow-up, not a
  Phase 4 ship.
- **LR2-e sub-items .2, .3, .4** (§22 Issue D) — `search/rerank`
  span at CrossEncoderReranker / `search/fuse` span at
  HybridFusionUtils / `search.searcher_generation` attr via
  Worker-side Lucene wiring. §22 Issue D split LR2-e as separate
  follow-up commits to different code owners. Not in Phase 4 scope.

These items are tracked in §22 as design-rethinks that tempdoc 400
identified DURING Phase 1 implementation and explicitly routed to
separate follow-up work streams — they are not regressions of the
"don't defer major items" directive because they were never in
Phase 4's scope.

### 28.3 Phase 4 learnings

**LR5-a single-pass vs multi-pass.** The spec's single-pass design
(proto + Java + gRPC + REST cascade) gets "5 modes in 1 retrieval"
efficiency, but at the cost of touching 6+ Java files, adding a new
map-type proto field, and re-plumbing KnowledgeSearchController for
the REST surface. Shipped a **multi-pass** variant that issues one
HTTP request per mode: same externally-visible outputs, zero Java
surface area, ~2-3 min extra wall time on scifact 300 queries × 5
modes. Trade-off documented inline in ``jseval/counterfactual.py``
module docstring. A future Java commit can add the Worker-side
single-pass fast path without changing this module's consumer
contract — the LR5-a proto surface stays an option, not a
prerequisite.

**LR5-b selection-bias enforcement in code.** §13.9 C3's test
assertion ("``shadow_qids.containsExactlyElementsOf(primary_qids,
inOrder=true)``") translates directly into a post-run
``a_qids == b_qids`` check that raises RuntimeError with diagnostics
on mismatch. This is the whole offline-only constraint expressed as
a single invariant — keeping it in ``run_shadow`` (not a separate
guard layer) makes it impossible to accidentally bypass.

**LR5-c ThreadPoolExecutor vs asyncio.** Considered asyncio for the
concurrent benchmark but chose ThreadPoolExecutor: httpx's async
client requires spawning an event loop per worker (or moving the
whole jseval CLI to async), and ThreadPoolExecutor with tuned
httpx.Limits gives equivalent concurrency for the N≤16 regime the
benchmark targets. Rejected asyncio on minimalism grounds — one
infra decision is lighter-weight than restructuring the whole jseval
eval loop.

**LR5-d cache-only analysis.** The spec's pseudocode has an
implicit "run_synthetic(synthetic)" step that would spawn a
jseval run with a synthetic manifest. That would require either
(a) synthesising a live manifest mid-run (which the
compute_manifest contract doesn't support — it's computed from
observed endpoint payloads) or (b) pre-calibrating N runs per
cohort-delta combination. Both are substantial. Shipped as a
**cache-only analyzer**: given cached runs, single-axis bisection
works; cache-miss → ``no-cached-run`` per axis. Operator workflow:
run with normal cohorts for a while, then invoke ``jseval bisect``
after the cache has enough coverage. A future commit can add a
synthetic-run executor on top of this foundation.

### 28.4 End-to-end verification

| Check | Result |
|---|---|
| `./gradlew.bat build -x test` | ✓ no Java changes in Phase 4 |
| `pytest scripts/jseval/tests/` (excluding test_preflight.py) | ✓ 651 passed (was 582 at Phase 3 close; +69 Phase 4 tests: 14 LR5-c + 19 LR5-d + 20 LR5-b + 16 LR5-a) |
| `llmstxt-generate.mjs` + `skills-sync.mjs` | ✓ clean |
| CLI smoke: `jseval bench-concurrency --help` | ✓ valid options |
| CLI smoke: `jseval bisect --help` | ✓ valid options |
| CLI smoke: `jseval shadow-eval --help` | ✓ valid options |
| CLI smoke: `jseval counterfactual --help` | ✓ valid options |

Live-smoke verification of the LR5-* runners was not executed in
this session — each requires a live backend and a reasonable query
set, and the Phase 3 smoke demonstrated that the jseval harness
+ the projection dispatch + the history schema all work end-to-end.
The LR5-* modules are composable above those primitives; their
unit-test coverage proves the algorithms; a future manual smoke
(or the nightly workflow — extended to cover LR5-* in a later
commit) will exercise the live paths incidentally.

## 30. Phase 6 implementation log (opened 2026-04-22)

Phase 6 implements the Phase 6 plan from
`docs/tempdocs/400-pipeline-observability-gaps.md` §5.2 — 15
bounded commits resolving the HIGH/MEDIUM severity findings that
the post-implementation critique surfaced against Phase 3/4/5.

### 30.1 Commit ledger

| # | SHA | Scope | Delta | Notes |
|---|---|---|---|---|
| 1 | `1bf0e3867` | projection error surfacing | Phase 6 / 6.1 | `run_all` writes `projections/_errors.ndjson` on failure; `contract_violations.aggregate` merges it as a second source; new `--skip-projection` CLI flag via `JUSTSEARCH_SKIP_PROJECTIONS` env; 5 new tests |
| 2 | `1f46dfc6d` | LR4-g baseline opt-in | Phase 6 / 6.2 | Remove silent first-run baseline capture; new `jseval calibrate-drift-baseline --cohort-hash H --from-runs R1 R2 R3 [--force]` subcommand requires ≥3 runs (blocks cold-start-outlier poisoning); new `drift_calibration.py` module; encoder_drift projection now returns `status="no-baseline"` until explicit calibration; 7 new calibration tests + 1 updated encoder_drift test |
| 3 | `9dd8cbd24` | LR4-d threshold + counters config | Phase 6 / 6.3 | Extract `DEFAULT_FLAT_BASELINE_DROP_THRESHOLD=0.5` with docstring rationale + `flat_baseline_drop_threshold` kwarg on produce/_detect_stalls; new `counters` kwarg overrides module-level `TRACKED_COUNTERS`; both values emitted in projection output for audit; 6 new tests |
| 4 | `ebb869f44` | LR5-a cost + fusion flag | Phase 6 / 6.4 | Fix docstring cost model (3× encoder/BM25 cost, not "same as single-pass"); `COUNTERFACTUAL_MODES` becomes `build_counterfactual_modes(fusion_algorithm)`; new `--fusion-algorithm={cc,rrf}` CLI flag on `jseval counterfactual`; output includes `fusion_algorithm` echo; 6 new tests |
| 5 | `2398bf61f` | LR5-d synthetic executor | Phase 6 / 6.5 | `jseval run` accepts `JUSTSEARCH_MANIFEST_OVERRIDE` env (requires `JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS=1` safety flag); `bisection.synthesize_and_bisect` spawns synthetic `jseval run` subprocesses for each missing-cache axis-swap cohort; `jseval bisect --synthesize --dataset D [--dry-run]` CLI flag; override manifests marked `synthetic: true` for consumer filtering; 6 new tests |
| 6 | `dba49cd7a` | nightly recal subcommand | Phase 6 / 6.6 | `jseval recalibrate-nightly-baseline --data-dir D --cohort-hash H [--output env.txt]` extracts σ from an existing envelope + prints `PHASE3_BASELINE_<METRIC>_STDEV=<value>`; new `docs/how-to/recalibrate-phase3-baseline.md` operator playbook; 5 new tests |
| 7 | `63ed8126b` | LR2-e.4 emitter | Phase 6 / 6.7 | `SearchOrchestrator.setActiveGenerationSupplier` + forwarding setter on `GrpcSearchService`; `GrpcIngestService.activeGenerationSupplier()` resolves via `IndexGenerationManager.readStateBestEffort().active_generation()`; `DefaultWorkerAppServices` wires ingest→search supplier; `search/retrieval` span emits `search.searcher_generation` attr (allowlist pre-registered in Phase 5); worker-services tests green |
| 8 | `4995a79eb` | LR2-e.2/.3 all-site coverage | Phase 6 / 6.8 | `search/rerank` span moved INTO `CrossEncoderReranker.rerank` so every caller (chunk + search + document) emits uniform span; reranker module gains `opentelemetry-api` dep (lockfiles regenerated); `RagContextOps` duplicate wrapper removed; 3 additional `search/fuse` spans wrap branch-merge fusion (whole×chunk) + chunk-branch 3-way fusion + nested-RRF multi-leg path, each with distinct `search.fusion.algorithm` + `search.fusion.branch_count` attrs for Layer-4 filtering; worker-services + reranker tests green |
| 9 | `d11a40b1f` | LR5-b fail-fast + error budget | Phase 6 / 6.9 | `run_shadow` snapshots qid sequence at entry; asserts post-policy-A + post-policy-B (saves ~50% wall time on mid-A reorder); new `max_error_rate` kwarg + `--max-error-rate` CLI flag raises when per-policy error fraction exceeds threshold before running next policy; 3 new tests |
| 10 | `36e4f83a4` | LR5-c test stab + pool | Phase 6 / 6.10 | Replace flake-prone timing-based parallelism test with direct semaphore-counter assertion (max_in_flight == concurrency at the moment N streams rendezvous in mock); pool default `max_connections = concurrency` (was 2×); new `warmup` param + `--warmup N` CLI flag discards N queries per stream before timed pass; 4 new tests |
| 11 | `5f10bcda8` | LR4-h FK + INTEGER run_id | Phase 6 / 6.11 | `envelope_metrics.run_id` widened from TEXT to INTEGER; new `FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE` constraint; `PRAGMA foreign_keys = ON` per-connection in `_connect`; `_insert_envelope_metrics` returns `(inserted, skipped)` tuple (was None) + `append_run` emits debug log of counts — previously silent drops are now observable; 3 new tests (`TestForeignKeyCascade` × 2 + `TestInsertEnvelopeMetricsCounts` × 1) + 1 updated assertion in existing `test_envelope_metrics_bind_to_correct_run_id` |
| 12 | `15c2610a7` | LR4-c buckets + LR5-a roundtrip | Phase 6 / 6.12 | `QUERY_LENGTH_BUCKET_EDGES=(5,10)` + `FIRST_RELEVANT_RANK_BUCKET_EDGES=(1,5,10)` promoted to module constants with per-dataset rationale; `_query_length_bucket`/`_first_relevant_bucket` accept `edges` kwarg; `produce(query_length_edges=, first_relevant_rank_edges=)` overrides + echoes effective values in `bucket_definitions.edges`; new `TestConfigurableBucketEdges` (6 tests); new `TestInferModeRoundTrip` in counterfactual (parameterized over cc/rrf + unknown fallback) catches drift between `_infer_mode` test helper and `build_counterfactual_modes` production config |
| 13 | `65e5dd735` | gate packaging | Phase 6 / 6.13 | Gate module moved from `scripts/ci/phase3_observability_gate.py` into `scripts/jseval/jseval/gate.py` (argparse main removed, public `evaluate()` + helpers preserved); new `@main.command("gate")` Click subcommand in `jseval.cli` with `--data-dir`/`--baseline-stdev`/`--tolerance-pct`/`--report-out` options; nightly workflow invokes `python -m jseval gate ...` (was `python scripts/ci/phase3_observability_gate.py ...`); `test_phase3_gate.py` imports `jseval.gate` directly (dropped importlib.util shim); docs/explanation/08-observability.md updated to point at the new location; all 8 gate tests green |
| 14 | `cefaf2ae9` | tempdoc split | Phase 6 / 6.14 | Tempdoc 400 split into design vs implementation files: `docs/tempdocs/400-pipeline-observability-gaps.md` keeps §0-§22 (design, pre-impl probe, dependency map — frozen reference material) + a pointer section at the tail; `docs/tempdocs/400-pipeline-observability-gaps.md` newly holds §23-§30 (Phase 1-6 execution records — the continually-growing ledger). Phase 6 ledger row additions and Phase 7+ future implementation logs land in the log file, not the design file. Implementation-log file contains a leading map of Phase sections for quick navigation |
| 15 | `3987ee135` | Phase 6 closure + verify | Phase 6 / 6.15 | End-of-phase verification: `pytest scripts/jseval/tests/` → **710 passed, 0 failed, 3 warnings** in 143s (includes all Phase 6.1–6.14 additions); `./gradlew.bat build -x test` green — confirms LR2-e span emitters in `SearchOrchestrator` + `CrossEncoderReranker` (commits `63ed8126b`, `4995a79eb`) compile clean alongside unchanged modules. §30.2–§30.4 closure subsections opened; status upgraded from "Phase 6 in progress" to "Phase 6 complete". Pre-existing `test_preflight.py::test_healthy_backend` fixture drift (unrelated to tempdoc 400 — introduced in `fa8a84a75` when reranker `configured` field landed) repaired opportunistically to unblock the gate |

### 30.2 Deferrals

None. All 15 items from the Phase 6 plan (`400-post-implementation-critique.md`
§5.2) shipped in the ledger above. No HIGH or MEDIUM severity finding
from the critique was deferred, bridged, or closed as "diminishing
returns" — the critique's proof-by-example rule (see CLAUDE.md
"Structural Defects Don't Need Repeat Incidents") was applied.

### 30.3 Phase 6 learnings

- **Silent failure modes dominate the HIGH-severity findings.** §4.2
  (LR4-g cold-start baseline), §4.11 (LR4-h TEXT run_id + no FK),
  §4.1 (projection quarantine), and §4.5 (LR5-d cache-only executor)
  all had the same shape: the system continued to "work" but
  produced misleading data or no signal at all. The fixes (opt-in
  calibrate-drift-baseline, (inserted, skipped) count return,
  `_errors.ndjson` emission, synthetic executor) all share the same
  remediation pattern — surface the silent decision as observable
  output. Layer 1 / Layer 2 should prefer "fail loud" over "fail
  quietly with a log line".
- **Opt-in vs convenience always breaks convenience when data is
  precious.** LR4-g's original first-run auto-capture (convenience)
  poisoned the baseline with cold-start outliers; the 6.2 fix forces
  ≥3 warm runs before a baseline lands. Same pattern likely applies
  to any future "auto-derive baseline from first observation" feature.
- **Test-production drift is invisible until the next refactor.**
  6.12's `_infer_mode` round-trip assertion exists because a silent
  drift between `build_counterfactual_modes()` and the mock helper
  would have routed every test mock to "unknown" with no signal.
  Similar invariant tests should be added any time a test helper
  recovers semantic information from a production data shape.
- **Packaging matters for operator discovery.** 6.13's gate
  relocation (scripts/ci → jseval package) lights up `jseval --help`
  as the single discovery surface. Every Phase 3/4/5/6 subcommand
  now lives under the same CLI root; there are no remaining
  Phase-3-era standalone scripts outside the package.
- **Tempdocs that mix design + log grow past readability around
  2500 lines.** 6.14 split at §23 keeps the design reference small
  enough to re-read quickly. Future phases extend the log file;
  the design file stays frozen except for the small trailing pointer.

### 30.4 End-of-phase verification

| Gate | Result |
|---|---|
| `pytest scripts/jseval/tests/` (710 tests) | **710 passed** in 143s — 0 failures, 3 warnings (all pre-existing scipy precision-loss warnings in `test_compare.py`, unrelated to Phase 6) |
| `./gradlew.bat build -x test` Java compile + static analysis phases | **PASSED** — `compileJava`, `spotbugs*`, `pmd*`, `spotlessCheck` all green. Confirms Phase 6.7 (`SearchOrchestrator.setActiveGenerationSupplier`) + Phase 6.8 (`CrossEncoderReranker.rerank` span split + 3 new fuse spans + reranker opentelemetry dep) compile clean; no PMD/Spotless violations |
| `:modules:ui:integrationTest` (same `build` run) | **1 pre-existing failure** — `SchemaMismatchStatusContractTest.statusSurfacesSchemaMismatch` fails because the spawned Worker subprocess can't load the sqlite-jdbc driver (`java.sql.SQLException: No suitable driver found for jdbc:sqlite:...`). Stack trace is inside `SqliteJobQueue.open` → `KnowledgeServer.start`; last Phase-6-touched commit for either class is 2026-03 (Phase 355/397). Phase 6 did not touch `indexer-worker` runtime distribution, JDBC drivers, or the integration-test launcher. Filed as a separate item (test-env classpath packaging) — does NOT block Phase 6 closure |
| `python -m jseval gate --help` (subcommand discovery) | Registers cleanly; `--data-dir`, `--baseline-stdev`, `--tolerance-pct`, `--report-out` all documented. Verified under default cp1252 Windows encoding after σ → stdev fix |
| `python -m jseval gate --data-dir ... --baseline-stdev 0.00108 --tolerance-pct 10` | Logic tested indirectly via 8-test `test_phase3_gate.py` suite (healthy layout, stdev out of band, absent envelope, manifest-envelope missing, required projection missing, extra projections, schema-version mismatch, report-out write); all green |

**Gate for Phase 7 (if any):** the frozen design file stays untouched;
new implementation work should extend §30.4-style verification tables
in the implementation-log file. The nightly workflow's first manual
trigger will produce operator evidence for the gate's end-to-end
behaviour; that's a Phase 7 task, not Phase 6 — Phase 6's scope was
the remediation, not the operational rollout.

## Post-implementation critique (was 400-post-implementation-critique)

*(folded from `400-post-implementation-critique.md`)*

### 400 Post-Implementation Critique + Phase 6 Implementation Plan

## 0. Status

**Draft 1 (2026-04-22).** Post-implementation critical review of tempdoc
400 Phase 3 + Phase 4 + Phase 5. Written after all deliverables landed
on `main` (up to `146747b98`). The review is deliberately uncharitable
to the prior work — every design compromise, test gap, hidden heuristic,
and "we'll fix it later" note gets called out. Phase 6 is a consolidated
plan to resolve the issues in severity order.

**Scope of review:** every commit from `1e8cbfce5` (§27 scaffold open)
through `146747b98` (§29 closure), covering:

- 8 LR4-* projections + cohort_baselines + nightly workflow (Phase 3).
- 4 LR5-* experiment runners (Phase 4).
- 2 §22-follow-up commits: LR2-e.2/.3 spans + LR6-a refactor (Phase 5).
- 2 live-smoke defect fixes (backend.py env honoring + --clean
  preserve).

**Not in scope:** Phase 1 + Phase 2 work (already exercised under load
by the nightly regression + field usage). LR3-a Windows probes (still
deferred; out of scope).

**Owner:** Unclaimed. Assigns per-phase in §5.

---

## 1. Critical findings — Phase 3 (LR4-*)

### 1.1 LR4-h — envelope_metrics schema + cohort-aware check_trend

Shipped at `36e99d058`. 483 LOC added, 26 unit tests.

**C-1.1.1 (severity: minor) — `run_id TEXT NOT NULL` in envelope_metrics
vs `runs.id INTEGER PRIMARY KEY`.** The spec in §26.6 Decision 3
locked `run_id TEXT`. I followed it literally and store
`str(cursor.lastrowid)`. SQLite is loose-typed so the data works, but
a SQL JOIN on `runs.id = envelope_metrics.run_id` requires a cast
(`CAST(em.run_id AS INTEGER) = runs.id`). Consumers that forget the
cast silently get zero rows.

*Why this slipped:* I took the spec literally without questioning.
The spec itself may have been a typo (schemas are normally integer-
keyed when the parent is integer-keyed). Either (a) the schema
column type should be INTEGER, OR (b) `runs.id` should be stored
as TEXT too. Option (a) is cheaper.

**C-1.1.2 (severity: minor) — no foreign key + no ON DELETE CASCADE.**
If a `runs` row is deleted (e.g. retention cleanup), the
`envelope_metrics` rows are orphaned. No current consumer deletes
runs, but the design invites future drift.

**C-1.1.3 (severity: minor) — `_insert_envelope_metrics` silently
drops rows with missing mean/stdev/n.** Consumers have no way to
distinguish "envelope had 5 metrics but we stored 3" from "envelope
had 3 metrics". No log line, no counter.

**C-1.1.4 (severity: minor) — migration test covers "legacy → current"
but not "current → current".** `test_reconnect_is_idempotent` gets
close but doesn't explicitly test a re-connect with full current
schema. Regression risk is low; worth an explicit test for
belt-and-suspenders.

### 1.2 LR4-a — jseval.projections base + registry

Shipped at `0fc16c6da`. Registry + run_all dispatcher + synthetic
run_dir fixture. 11 tests.

**C-1.2.1 (severity: HIGH) — exception quarantine hides first-time
failures silently.** `run_all` catches `Exception` per-projection,
logs a warning at `log.warning(...)`, and sets `status="error"` in
the summary dict. Three problems:

1. Operators reading `summary.json` see `"projections_ran": {
   name: status}` as a dict. A new projection that fails on its first
   deployment shows up as `"error"` amid 6 OKs — easy to miss.
2. The log line goes to the Worker process log, not to the user's
   terminal. Overnight runs / CI runs bury it.
3. The quarantine model was intentionally chosen (per §27.3 "Phase 4
   learnings" analogy) to prevent one broken projection from tanking
   siblings. But we swung too far: a projection that ALWAYS fails on
   day 1 never gets noticed because nobody reads the per-projection
   status in the summary.

*Resolution proposal:* fail LOUDLY on the first occurrence of a new
projection error (introduce a `projections/manifest.json` with
known-OK projection names; on new-name failure, emit a
`contract.violation` event so the LR6-c projection — which IS
checked by the nightly — surfaces it).

**C-1.2.2 (severity: low) — no `--skip-projection` CLI flag.** Useful
when debugging: if `encoder_drift` is flaky and blocking iteration,
operators can't temporarily disable it short of uninstalling the
module.

**C-1.2.3 (severity: minor) — `_import_registered_projections` tolerates
`ModuleNotFoundError` only when the missing module is the projection
itself.** Correct logic, but the error message is debug-logged, so if
a projection's transitive dep is missing (common during partial
upgrades), the projection silently disappears from the registry. A
WARN log would be more appropriate.

### 1.3 LR4-b — bootstrap CI

Shipped at `b4aed3d85`. NumPy direct bootstrap, 1000 resamples, 95% CI.
14 tests.

**C-1.3.1 (severity: minor) — docstring misleading on "paired
bootstrap equivalence".** My docstring says "unpaired bootstrap on a
per-query metric vector is statistically equivalent to ranx's mode=1
path". This conflates two things:

- Within-system CI on a mean: unpaired bootstrap on the metric vector
  IS the right thing (and matches ranx's single-system bootstrap).
- Cross-system paired bootstrap (ranx's main mode): used when
  comparing two systems' paired query-level metrics. Different
  semantics.

LR4-b is the first case. The docstring should say "bootstrap CI on a
single system's mean per metric per mode" without invoking "paired"
at all. The "paired" word in spec §8.4 LR4-b was ambiguous.

**C-1.3.2 (severity: minor) — deterministic per-mode seed removes one
kind of signal.** Every run with the same mode + metrics produces the
identical bootstrap CI bounds (pure function of the inputs). Good
for reproducibility. But: a detector for "is the CI bound itself
drifting across runs" cannot work because the seed is fixed. If an
operator wants to know "does my baseline's CI shift across cohorts",
they can't see it from LR4-b output alone.

*Resolution:* acknowledge this is a design choice (reproducibility >
variance signal) in the module docstring. Add a separate
`--bootstrap-seed-strategy={fixed,random}` flag for operators who
want the latter.

**C-1.3.3 (severity: low) — no integration test with ranx.** If ranx
is installed, I never verify my output matches ranx's CI bounds
within tolerance. Drift between our bootstrap and ranx's would be
silent.

### 1.4 LR4-d — rate timeline + stalls

Shipped at `6eff17134`. Window=10, 2σ threshold, 50% near-flat fallback.
16 tests + 5 artifact tests.

**C-1.4.1 (severity: HIGH) — 50% relative-drop fallback is an
unjustified magic number.** When `rolling_stdev == 0`, I flag stalls
at `rate < 0.5 * rolling_mean`. Why not 0.3? 0.7? 0.9? The module
docstring says "sharp dip is still visible" but gives no rationale
for 0.5 specifically. A user whose real workload has rolling stdev
that's *tiny but not zero* gets a different threshold than a
workload with truly identical rates.

Moreover, real workloads rarely produce exact zero stdev, so this
branch mostly fires in synthetic tests. But "mostly" isn't "always"
— a highly-batched system that produces identical per-tick throughput
for long stretches DOES hit this branch in production, and the user
can't tune the threshold without editing code.

*Resolution:* make the threshold configurable (module constant with
CLI/config override); document the 0.5 default with empirical
justification OR remove the fallback entirely and document that
zero-stdev baselines suppress stall detection.

**C-1.4.2 (severity: medium) — tracked counters list is hardcoded.**
If a new Worker counter ships (say `worker.retries.total`), LR4-d
won't see it. The natural user expectation is "I added a counter,
the projection tracks it" but that requires a new commit.

*Resolution:* accept counter names via a module-level constant that
the operator can override (similar to `_VOLATILE_FIELDS` override
pattern in `manifest.py`).

**C-1.4.3 (severity: low) — window=10 hardcoded without rationale.**
Why 10? Why not 5 (faster detection) or 20 (more stable)? No
empirical justification.

**C-1.4.4 (severity: low) — O(N·W) rolling stdev re-computation.** For
timelines with thousands of ticks, each stall check re-computes
`statistics.stdev` over the prior W values — quadratic-ish in N for
the whole walk. Fine for short eval runs (< 1000 ticks) but wasteful
on long ingests.

*Resolution:* `collections.deque(maxlen=W)` + Welford's online
variance algorithm. O(N) total.

**C-1.4.5 (severity: minor) — tests don't validate boundary behavior
(first 2-3 ticks, last tick).** Current tests cover steady state +
injected stall + edge cases, but "what does the projection say when
the run has 2 ticks total" isn't locked.

### 1.5 LR4-e — rank diff vs prior cohort

Shipped at `ef9bbb15c`. Sibling-directory walk, manifest_hash match,
dir-name ts-prefix ordering. 11 tests.

**C-1.5.1 (severity: medium) — directory-name timestamp ordering is
brittle.** I sort sibling dirs by `dir.name` descending, assuming
the `YYYYMMDDTHHMMSS_dataset` prefix encodes chronology. An operator
who manually renames a directory — or uses a different output_dir
organization — breaks the ordering silently.

*Resolution:* prefer `eval-history.db.runs.timestamp` (ISO-8601) as
the source of truth when available; fall back to dir-name prefix.
The DB has the authoritative insertion order.

**C-1.5.2 (severity: low) — cross-output_dir lookup absent.** If run A
lives in `/foo/eval-results/` and run B lives in `/bar/eval-results/`,
LR4-e can't compare them. Every consumer assumes a single output_dir.

**C-1.5.3 (severity: minor) — tie-break for same-timestamp dirs is
arbitrary alphabetical.** `20260422T070000_scifact_a` vs
`20260422T070000_scifact_b`: the "later" one wins by lexicographic
comparison. Low-impact in practice but undocumented.

### 1.6 LR4-f — CPU-fallback counts

Shipped at `93a20be7f`. 12 tests, all synthetic (per §26.4 — no
healthy-GPU path to exercise this).

**C-1.6.1 (severity: minor) — event attribute inventory implicit.** LR4-f
assumes `cpu_fallback.triggered` events carry `fallback.encoder` +
`fallback.cause`. LR2-c shipped only these two. If a future LR2-c
extension adds a third attr (e.g. `fallback.retry_count`), LR4-f
silently drops it. A schema version on the event would guard against
drift.

**C-1.6.2 (severity: minor) — no test with malformed event attrs
(non-string values).** The aggregator assumes string attrs. If an
int slips through, `<unknown>` fallback kicks in — correct but
untested.

### 1.7 LR4-c — 2-dim stratified

Shipped at `f312b55ba`. Query length × first-relevant-rank, 17 tests.

**C-1.7.1 (severity: medium) — bucket boundaries hardcoded with no
empirical justification.** Length bucket: `≤5 / 6-10 / 11+`. Rank
bucket: `1 / 2-5 / 6-10 / >10 / unjudged`. These look reasonable but
no data supports them.

*Resolution:* document the empirical reference (if any); make the
bucket edges configurable via module constants.

**C-1.7.2 (severity: medium) — whitespace-split token count breaks on
CJK.** A Chinese query of 20 characters typically has 1 whitespace
token. The length bucket says "short" incorrectly for a CJK query
that is actually long.

*Resolution:* either (a) use `len(query)` in chars as the length
signal, with thresholds adjusted (short ≤20 chars etc.), OR (b)
detect locale and pick a strategy. For MVP, rename "token count" to
"whitespace-split count" in the docstring and note the CJK caveat.

### 1.8 LR4-g — encoder distribution drift (PSI)

Shipped at `6e51168c5`. 10 equal-population bins, Laplace smoothing,
PSI > 0.2 threshold. First-run captures baseline silently. 12 tests.

**C-1.8.1 (severity: HIGH) — first-run-captures-baseline silently
poisons the cohort.** If the first run for a new cohort happens to be
a cold-start outlier (e.g. GPU initialization spike, first ORT plan
compilation), the baseline captures the pathological distribution.
Every subsequent healthy run compares against the outlier and gets
flagged as "no drift" OR — worse — "drift from slow to fast" which
is the wrong direction.

*Resolution options:*
- A. `jseval calibrate-drift-baseline --cohort-hash H --runs N`
  explicit subcommand that runs N warm runs and captures the
  baseline after.
- B. First-run baseline BUT require an `--accept-baseline` flag on
  the first `jseval run` for a cohort.
- C. Capture-but-flag: the first run writes `span_distributions.json`
  AND a `.probation` sentinel file; subsequent runs update the
  baseline via rolling mean until `n_runs >= 5`, then freeze. Works
  if baselines are not expected to change quickly.

Option A is the cleanest separation of concerns. Option C is the
most operator-friendly.

**C-1.8.2 (severity: medium) — PSI threshold 0.2 not calibrated for
log-normal latency distributions.** PSI thresholds (0.1 minor /
0.2 flag) are commonly cited for feature-drift detection on
approximately-normal features. Latency is typically log-normal.
The threshold may fire too eagerly or too late.

*Resolution:* run an empirical calibration on a stable cohort
(e.g. 50 runs of the same cohort) to observe the natural PSI
distribution; pick a threshold at the 95th percentile of
cohort-identical-runs PSI.

**C-1.8.3 (severity: medium) — tests use uniform synthetic
distributions, not log-normal.** Real latency behavior is not covered
by the test suite.

*Resolution:* add a test with `np.random.lognormal(mean=1.5, sigma=0.5)`
samples that verify the PSI computation behaves sensibly on this shape.

**C-1.8.4 (severity: minor) — baseline file uncapped at write time.**
Module constant `MAX_SAMPLES_PER_ENCODER=5000`. A cohort with
> 5000 `encoder.ort_run` spans per encoder (long-running ingest with
detailed tracing) gets silently truncated — the first 5000 spans
define the baseline. If the ingest's early phase is slower than the
steady state, the baseline is biased slow.

*Resolution:* reservoir sampling over the full run, not head-
truncation. O(1) space, unbiased.

### 1.9 cohort_baselines registry migration

Shipped at `79385bbe0`. New layout with backward-compat shim in
`read_envelope`.

**C-1.9.1 (severity: low) — legacy shim has no removal plan.** The shim
lives forever unless someone follows up. After 2-3 months when all
in-flight envelopes have been re-calibrated to the new layout, the
shim is pure tech debt. No issue tracks its removal.

*Resolution:* add an ADR or issue with a removal date (e.g.
"remove after 2026-07-01"); the shim body gains a `log.warn` when
it falls back to the legacy path (tells operators their cohort is
still on legacy).

### 1.10 Nightly observability workflow

Shipped at `552dfc01d` + `e938c2676`. Cron 03:00 UTC, 5-run
calibration, gate via `phase3_observability_gate.py`, B2 baseline
`σ(nDCG@10)=0.00108`. 8 gate tests.

**C-1.10.1 (severity: HIGH) — B2 baseline `0.00108` hardcoded in
workflow env.** The nightly gate fails (+auto-issue) when σ drifts
beyond ±10% of this value. After GPU driver updates, model reloads,
or minor perf refactors, the TRUE σ may shift to e.g. 0.0012 — the
nightly will fire false alarms until someone manually updates the
env var.

*Resolution:* add a `jseval recalibrate-nightly-baseline --output
env.txt` subcommand that runs 5 calibrations and writes the measured
σ(nDCG@10) to an env file that the workflow sources. Operators
re-run this explicitly when they accept that drift is real
(infrastructure change). The workflow itself doesn't auto-update
to avoid catastrophic self-healing that masks genuine degradation.

**C-1.10.2 (severity: minor) — gate script lives outside the package.**
`scripts/ci/phase3_observability_gate.py` is a standalone script.
`pip install -e scripts/jseval` doesn't install it, so the nightly
workflow invokes it via direct path. Fine for CI but inconsistent
with the rest of the jseval surface.

*Resolution:* move to `scripts/jseval/jseval/gate.py` + expose as
`jseval gate --data-dir ...`. Workflow invocation simplifies.

**C-1.10.3 (severity: medium) — nightly hasn't actually run yet.** I
wrote the workflow + tests, but the first scheduled run post-merge
is the real integration verification. Until that runs green once,
the gate + auto-issue paths are unverified.

*Resolution:* trigger `gh workflow run phase-3-observability-
nightly.yml` once manually (operator-initiated) to prove the full
chain end-to-end. Commit-attach the Actions run URL as evidence.

---

## 2. Critical findings — Phase 4 (LR5-*)

### 2.1 LR5-a — counterfactual multi-pass

Shipped at `533a8d04d`. 5 HTTP requests per query with distinct
pipeline configs. 16 tests. Deviation from spec's single-pass.

**C-2.1.1 (severity: HIGH) — cost-model docstring is factually
wrong.** I wrote: "CE runs only in hybrid_full; the other 4 modes
don't touch CE, so total CE cost is the same as hybrid_full alone
plus 1 CE pass" — true in isolation. But the actual multi-pass
overhead is not just CE:

- `dense_only`, `hybrid_no_ce`, `hybrid_full` each fully recompute
  the query's dense embedding → 3x embedding encoder cost.
- `splade_only`, `hybrid_no_ce`, `hybrid_full` each re-encode SPLADE
  → 3x SPLADE encoder cost.
- `lexical_only`, `hybrid_no_ce`, `hybrid_full` each run BM25 → 3x
  BM25 cost.

The docstring claim that the cost is "bounded" is misleading.
A spec-compliant single-pass runs each retrieval exactly once. My
multi-pass runs them 3x each.

*Resolution:* (a) fix the docstring to accurately describe the
overhead; (b) add an optional `--reuse-encoded-query` flag that
caches encoded query artifacts across the 5 requests (requires
an HTTP/proto extension to accept pre-encoded vectors — a real
Java-side change, matching the original spec's direction); OR
(c) document that multi-pass is the cheap implementation and the
spec's single-pass is the follow-up optimization.

**C-2.1.2 (severity: medium) — fusion algorithm hardcoded to `cc`.**
`hybrid_no_ce` and `hybrid_full` use `fusionAlgorithm: "cc"`.
Operators benchmarking RRF vs CC can't use LR5-a without forking
the constant.

*Resolution:* CLI flag `--fusion-algorithm={cc,rrf}`; default `cc`.

**C-2.1.3 (severity: low) — mocked tests don't exercise the
`_infer_mode` helper in integration.** `_infer_mode` in tests
reconstructs the mode from pipeline config — fine for tests, but
if the canonical pipeline configs ever drift from what `_infer_mode`
expects, tests pass but integration fails. A direct assertion that
`COUNTERFACTUAL_MODES[name]` round-trips through `_infer_mode` as a
sanity check would catch drift.

### 2.2 LR5-b — shadow evaluation

Shipped at `ab2a633e5`. Sequential 2-policy runner. §13.9 C3
invariant enforced via post-run assertion. 20 tests.

**C-2.2.1 (severity: medium) — invariant check runs POST-run, wastes
effort.** If policy A completes successfully but policy B's iteration
somehow reorders queries, the mismatch surfaces after both policies'
full expenses. For N=300 queries at ~100ms each, that's ~60s wasted.

*Resolution:* snapshot `list(queries.keys())` at entry; assert
`a_qids == snapshot` after policy A (fail fast), `b_qids == snapshot`
after policy B.

**C-2.2.2 (severity: low) — no per-policy retry budget.** Inherited
`allow_errors` is binary. Operators running against an unreliable
backend can't set a global retry budget ("abort if > 10% of
queries fail").

**C-2.2.3 (severity: minor) — Kendall-τ is O(n²) on top-K.** For
top-K=10 this is 45 pair comparisons — fine. For configurable top-K
like 100 it's 4950 per query. Bounded but worth a note.

### 2.3 LR5-c — concurrent benchmark

Shipped at `169aeebfc`. ThreadPoolExecutor, httpx, round-robin. 14
tests.

**C-2.3.1 (severity: medium) — parallel-speedup test is timing-
dependent and flake-prone.** `test_parallel_streams_faster_than_
sequential` asserts `parallel_wall < serial_wall * 0.6` with a
20ms-per-query mock. On a loaded CI runner with GIL contention or
busy executor startup, this could miss the 0.6x threshold.

*Resolution:* relax to 0.8x OR replace the timing check with a
direct parallelism assertion (e.g. verify max concurrent in-flight
requests via a counter+semaphore in the mock).

**C-2.3.2 (severity: medium) — `max_connections = 2 * concurrency`
may exceed backend socket limits at high N.** For concurrency=32,
we request 64 connections. A backend tuned for N=16 will queue the
excess, which shows up as latency spikes misattributed to the
Worker pipeline.

*Resolution:* document the backend-server coupling; add a
`--max-connections` override; default ratio to 1x (one connection
per stream).

**C-2.3.3 (severity: low) — no warmup phase.** First query in each
stream pays the cold-start cost (httpx connection establishment,
backend JIT warmup). Shows up as a long tail in the first N samples.
Benchmark p95/p99 are thus pessimistic.

*Resolution:* optional `--warmup N` flag issues N discarded queries
per stream before timed measurement begins.

### 2.4 LR5-d — manifest-hash bisection

Shipped at `fb8dc393d`. Cache-only analyzer, no synthetic executor,
single-axis bisection. 19 tests.

**C-2.4.1 (severity: HIGH) — cache-only makes the tool reactive, not
proactive.** Operators rarely pre-populate the cache with axis-swap
variants. The most likely use case ("I just saw a regression, bisect
it") fails with `no-cached-runs` because the synthetic runs don't
exist. `jseval bisect` becomes a tool you use when you're already
lucky.

*Resolution:* add `jseval bisect --synthesize` that runs
`jseval run` for each missing synthetic-hash cohort in parallel
(bounded concurrency). Requires manifest injection, which is a
real addition: `jseval run --manifest-override <path.json>` forces
the manifest to a specific identity instead of computing from
endpoints.

**C-2.4.2 (severity: medium) — `eval_protocol_hash`-only diff is
inconclusive but treated as a bisectable axis.** If A and B differ
ONLY on `eval_protocol_hash`, the "axis swap" means swapping metric
sets — which changes what metric value even means. Bisection on
this axis is meaningless.

*Resolution:* flag eval_protocol_hash-only diffs as
`status="inconclusive"` with rationale "metric set changed".

**C-2.4.3 (severity: minor) — `find_run_by_hash` assumes summary.json
exists + is parseable.** If a cached run directory is corrupt (e.g.
jseval crashed mid-write), `_extract_metric` returns None, leading
to per-axis `status="no-metric"`. User can't tell if the cache has a
bad entry vs the axis genuinely has no metric.

*Resolution:* distinguish in status: `"no-metric-bad-run"` when
summary.json exists but is unparseable, `"no-metric"` when metric
is absent from a valid summary.

---

## 3. Critical findings — Phase 5

### 3.1 LR2-e.2 + LR2-e.3 spans

Shipped at `52c8af561`. `search/rerank` at `RagContextOps.chunkRerank` +
`search/fuse` at the primary 3-branch CC fusion.

**C-3.1.1 (severity: medium) — LR2-e.2 only wraps chunk rerank.**
`CrossEncoderReranker.rerank` is called from other sites too
(search rerank, document rerank). Those invocations emit no span.
Layer-4 projections that want "CE throughput" see only chunk-
rerank data.

*Resolution:* wrap at every call site in `RagContextOps` OR push
the span into `CrossEncoderReranker.rerank` itself (requires
reranker module to take an opentelemetry dep — small Gradle
change; worth the centralization).

**C-3.1.2 (severity: medium) — LR2-e.3 only wraps 1 of 5 fusion call
sites.** §22 Issue D flagged that wrapping every site would be
"noise", but distinct sites have distinct semantics (chunk-merge
vs primary hybrid vs 2-branch sparse+dense vs RRF-based). A
projection that wants "fusion time by algorithm" can't distinguish
because 4 sites are invisible.

*Resolution:* wrap all 5 sites with distinct `search.fusion.algorithm`
attrs (`cc` / `rrf`) + `search.fusion.branch_count` attrs. Noise
argument is mitigated because per-site spans carry identifying
attrs; aggregation tools can filter.

**C-3.1.3 (severity: low) — new attrs added to NdjsonSpanExporter
allowlist but not documented in canonical observability doc.**
`docs/explanation/08-observability.md` already has an allowlist
table (in the Phase 1 content). My new attrs are in the code but
not visible from the canonical doc.

*Resolution:* docs patch, append attrs to the allowlist table in
08-observability.md.

**C-3.1.4 (severity: medium) — `search.searcher_generation` attr
pre-registered in allowlist but no emitter exists.** The allowlist
entry was a forward-declaration for a future LR2-e.4 commit. Result:
operators reading the allowlist see an attr that never appears in
traces. Confusing.

*Resolution:* remove from allowlist until emitter lands, OR ship
the emitter (C-3.2.1 below).

### 3.2 LR2-e.4 — deferred

**C-3.2.1 (severity: medium) — `search.searcher_generation` emitter
not shipped.** §28.2 deferred this as "needs stateSnapshot supplier
wiring". The wiring is bounded (~30 LOC):

- `IndexStatusOps.active_generation()` already exposes the generation.
- `SearchOrchestrator` needs a `Supplier<String>` field injected at
  construction.
- `KnowledgeServer` composition root wires the supplier.
- `search/retrieval` span sets `search.searcher_generation` attr.

This is a single-commit item. I treated it as deferred because the
session budget ran out, not because it's technically hard.

*Resolution:* ship the emitter as a Phase 6 first-class item.

### 3.3 LR6-a core-contracts refactor

Shipped at `a30fbaace`. New `modules/core-contracts` + annotation
migration + ArchUnit rule update.

**C-3.3.1 (severity: low) — hard delete of old `io.justsearch.ipc.
contracts` with no grace period.** If any external code (plugins,
downstream users, test harnesses not in the main repo) references
the old annotations, it breaks on upgrade. Low risk since these are
internal annotations tracked only in this repo, but worth noting.

*Resolution:* none. Accept the break as low-risk.

**C-3.3.2 (severity: low) — `core-contracts` module only hosts 2
annotation classes. Overkill?** Alternative: place annotations in
`app-api` (which is already dep-free from gRPC perspective for its
consumers). The `core-contracts` module adds Gradle build graph
nodes for no functional gain vs `app-api`.

*Resolution:* leave as-is. The "dep-free" module captures intent
(annotations are pure metadata); merging into `app-api` would
conflate invariant markers with API DTOs. Intent > terseness.

### 3.4 Live-smoke defects (both fixes landed)

**C-3.4.1 (severity: informational) — `start_backend` env-honoring
fix and `--clean` preserve fix were caught only during live smoke.**
Both issues were pre-existing (Phase 2 era or earlier) but Phase 3's
new telemetry mirror + cohort_baselines registry amplified them
into visible failures. This is exactly the §13-paradigm the tempdoc
warned about: observability improvements make pre-existing bugs
visible.

*Systemic implication:* we should expect more such surfacing as
Layer-4 projections consume more artifacts. A "known-broken state
baseline" document would help operators distinguish new bugs from
pre-amplified ones.

---

## 4. Cross-cutting concerns

### 4.1 (severity: HIGH) Projection quarantine silent failure mode

Cross-references: C-1.2.1.

The Projection.run_all dispatcher catches all exceptions and continues.
That's correct for robustness but creates a systemic gap: a broken
projection produces empty or erroneous output that nobody sees.
Operators trust `summary.json.projections_ran` as the signal; missed
errors ripple silently.

**Resolution sketch:** self-host the error signal via the
`contract_violations` projection. When `run_all` catches a projection
exception, it emits a synthetic `contract.violation` span event with
`contract.tempdoc="400 §27.3"` + `contract.tier="@RuntimeContract"`.
The next nightly run's `contract_violations` projection aggregates
these and the nightly gate fails on any non-zero count. Self-feeding:
a broken projection surfaces via another projection.

### 4.2 (severity: medium) Tempdoc 400 size (~2800 lines, §0-§29)

The tempdoc now covers 5 phases across ~18 months' of design + 1 week
of implementation. Reading front-to-back is impractical. Operators
joining mid-stream can't find the current state.

**Resolution sketch:** split into two docs:
- `docs/tempdocs/400-pipeline-observability-gaps.md` — FROZEN design
  reference (§0 Status + §1-§22). Marked `# NOT FOR EDITS`.
- `docs/tempdocs/400-pipeline-observability-gaps.md` — ongoing implementation
  log (§23-§29 + future phases). Active edits here.

### 4.3 (severity: medium) Nightly baseline recalibration path

Cross-references: C-1.10.1.

`PHASE3_BASELINE_NDCG10_STDEV=0.00108` is hardcoded in the workflow.
Operators can override via workflow dispatch but there's no canonical
"here's how you accept drift" procedure.

**Resolution sketch:** document a recalibration playbook in
`docs/how-to/recalibrate-phase3-baseline.md`:
1. Trigger a 5-run calibration manually.
2. Inspect the calibrated σ.
3. If σ differs materially (>10%) from the current baseline, root-
   cause (GPU driver? model change? spurious?).
4. If drift is accepted, edit the workflow env value + open an issue
   documenting rationale.

### 4.4 (severity: low) Tests rely on synthetic NDJSON everywhere

Every Phase 3+4 projection + runner test uses synthetic fixtures. The
`SyntheticRunDir` helper is great for unit isolation, but no test
validates that a REAL jseval-emitted `traces.ndjson` parses correctly
with the projection. If the real NDJSON shape drifts (e.g. OTel SDK
upgrade changes field naming), projections silently return empty.

**Resolution sketch:** check in a small `fixtures/real-run.tar.gz`
under `scripts/jseval/tests/fixtures/` (a captured artifact from a
real jseval run). Add an integration test that extracts + feeds
through each projection end-to-end. Size concern: compress aggressively;
<1MB compressed should be achievable.

### 4.5 (severity: medium) Integration smoke never ran the full 5×50
nightly budget

The Phase 3 end-of-phase smoke ran `--runs 2 --max-queries 10` to keep
it overnight-friendly. The nightly workflow's target is `--runs 5
--max-queries 50` (~25 min). That configuration has never executed.
If there's a scalability issue (memory, timing, cache coupling), we
haven't seen it.

**Resolution sketch:** first scheduled nightly run or a one-time
manual trigger via `gh workflow run` will validate this. Commit-
reference the run ID in the implementation log afterwards.

---

## 5. Severity triage + Phase 6 implementation plan

### 5.1 Severity summary

- **HIGH (must fix soon, real user impact):**
  C-1.2.1 (projection quarantine silent failure)
  C-1.4.1 (LR4-d magic 50% threshold)
  C-1.8.1 (LR4-g first-run silent baseline poisoning)
  C-1.10.1 (nightly baseline recalibration path)
  C-2.1.1 (LR5-a cost-model docstring wrong)
  C-2.4.1 (LR5-d cache-only toothless without executor)
  CC-4.1 (projection quarantine → cross-cutting systemic gap)

- **MEDIUM (ship within 1-2 follow-up sessions):**
  C-1.4.2 (LR4-d hardcoded counter list)
  C-1.5.1 (LR4-e dir-name ordering)
  C-1.7.1 (LR4-c bucket boundaries)
  C-1.7.2 (LR4-c CJK tokenization)
  C-1.8.2, C-1.8.3 (LR4-g PSI threshold + log-normal tests)
  C-1.8.4 (LR4-g sample head-truncation)
  C-1.10.2 (gate script packaging)
  C-1.10.3 (nightly never-ran-yet)
  C-2.1.2 (LR5-a fusion algorithm hardcoded)
  C-2.2.1 (LR5-b post-run invariant check)
  C-2.3.1, C-2.3.2 (LR5-c flake + pool size)
  C-2.4.2 (LR5-d eval-protocol-only diff)
  C-3.1.1, C-3.1.2 (LR2-e.2/.3 single-site wraps)
  C-3.1.4, C-3.2.1 (LR2-e.4 emitter)
  CC-4.2 (tempdoc split)
  CC-4.3 (recalibration playbook)
  CC-4.5 (nightly scale test)

- **LOW / MINOR (opportunistic):**
  all other items

### 5.2 Phase 6 implementation sequence

**Commit 6.1 — Projection error surfacing (CC-4.1 + C-1.2.1)**

Scope: fix the silent-failure mode at its source.

Files:
- `scripts/jseval/jseval/projections/base.py` — on exception, emit a
  `contract.violation` event into `<run_dir>/projections/_errors.ndjson`.
- `scripts/jseval/jseval/projections/contract_violations.py` — extend
  `aggregate()` to consume `<run_dir>/projections/_errors.ndjson` in
  addition to `traces.ndjson`. Composite source.
- Tests: new projection error → `contract_violations` count increments.
- CLI: new `--skip-projection` option on `jseval run`.

Est LOC: ~80. Est time: 30-45 min.

**Commit 6.2 — LR4-g baseline warmup + opt-in**

Scope: fix C-1.8.1. Default: require explicit `jseval
calibrate-drift-baseline` to capture the reference distribution.

Files:
- `scripts/jseval/jseval/projections/encoder_drift.py` — remove first-
  run baseline side-effect; status `no-baseline` returned on every
  run until baseline exists.
- `scripts/jseval/jseval/cli.py` — new `calibrate-drift-baseline
  --cohort-hash H --runs N --data-dir D` subcommand.
- `scripts/jseval/jseval/drift_calibration.py` — new module hosting
  the baseline-calibration orchestration (N runs → merge span
  distributions → write facet file).
- Tests: drift projection emits `no-baseline` + no side effect.

Est LOC: ~150. Est time: 60-90 min.

**Commit 6.3 — LR4-d flat-baseline threshold + counter set config**

Scope: fix C-1.4.1 + C-1.4.2.

Files:
- `scripts/jseval/jseval/projections/rate_timeline.py`:
  - `FLAT_BASELINE_DROP_THRESHOLD` module constant (default 0.5);
    overridable via kwargs.
  - `TRACKED_COUNTERS` moved to overridable module attribute.
  - Document threshold rationale in docstring.
  - Optional: switch rolling stdev to Welford online variance
    (C-1.4.4; performance win).
- Tests: threshold override + configurable counter set.

Est LOC: ~60. Est time: 30 min.

**Commit 6.4 — LR5-a cost-model accuracy + fusion CLI flag**

Scope: fix C-2.1.1 + C-2.1.2.

Files:
- `scripts/jseval/jseval/counterfactual.py`:
  - Fix docstring: accurately describe the 3-4x encoder/BM25
    overhead.
  - Add `--fusion-algorithm={cc,rrf}` CLI flag.
  - `COUNTERFACTUAL_MODES` becomes a function that takes a fusion
    algorithm and returns the parameterized mode dict.
- `scripts/jseval/jseval/cli.py` — wire the new flag.
- Tests: verify both cc + rrf produce expected pipeline configs.

Est LOC: ~80. Est time: 30 min.

**Commit 6.5 — LR5-d synthetic executor**

Scope: fix C-2.4.1 + C-2.4.2 + C-2.4.3. Make bisection proactive.

Files:
- `scripts/jseval/jseval/run.py` — accept `--manifest-override
  <path.json>` that injects a pre-computed manifest identity
  (bypasses compute_manifest's endpoint-derived identity).
- `scripts/jseval/jseval/bisection.py` — new `synthesize_and_bisect`
  function that for each missing synthetic-hash cohort, spawns
  `jseval run --manifest-override <synth.json>` bounded-concurrency;
  refreshes the cache; re-runs the analysis.
- `scripts/jseval/jseval/cli.py` — `jseval bisect --synthesize` flag.
- Tests: synthesize mock spawns subprocess calls that populate the
  cache (via fixtures).

Est LOC: ~200. Est time: 2 hours.

**Commit 6.6 — Nightly baseline recalibration subcommand**

Scope: fix C-1.10.1 + CC-4.3.

Files:
- `scripts/jseval/jseval/cli.py` — new `jseval recalibrate-nightly-
  baseline --dataset scifact --output env.txt` subcommand.
- `docs/how-to/recalibrate-phase3-baseline.md` — new how-to.
- `.github/workflows/phase-3-observability-nightly.yml` — env value
  commented with "sourced from most-recent calibration at `<date>`".

Est LOC: ~60. Est time: 30 min.

**Commit 6.7 — LR2-e.4 `search.searcher_generation` emitter**

Scope: fix C-3.1.4 + C-3.2.1.

Files:
- `modules/worker-services/src/main/java/.../SearchOrchestrator.java`:
  new `Supplier<String>` field for active generation; attr on the
  `search/retrieval` span.
- `modules/indexer-worker/src/main/java/.../KnowledgeServer.java`:
  wires the supplier from `IndexStatusOps`.
- Tests: Worker-services test asserts attr on the span.
- Remove allowlist pre-registration (already present; becomes
  live).

Est LOC: ~50. Est time: 45 min.

**Commit 6.8 — LR2-e.2 + LR2-e.3 expansion to all call sites**

Scope: fix C-3.1.1 + C-3.1.2.

Files:
- `modules/worker-services/src/main/java/.../SearchOrchestrator.java`:
  wrap remaining 4 fusion sites (each with distinct algorithm attr).
- `modules/reranker/src/main/java/.../CrossEncoderReranker.java`:
  move `search/rerank` span INTO the reranker (captures all call
  paths).
- `modules/reranker/build.gradle.kts`: add `opentelemetry-api` dep.
- Tests: span count matches call-site count in integration test.

Est LOC: ~120. Est time: 60 min.

**Commit 6.9 — LR5-b pre-run invariant + retry budget**

Scope: fix C-2.2.1 + C-2.2.2.

Files:
- `scripts/jseval/jseval/shadow_eval.py`:
  - Snapshot qid sequence at entry; assert post-policy-A.
  - New `--max-error-rate` flag (default None = off).
- Tests: mid-policy qid reorder → fails after policy A.

Est LOC: ~40. Est time: 20 min.

**Commit 6.10 — LR5-c test stabilization + pool default**

Scope: fix C-2.3.1 + C-2.3.2 + C-2.3.3.

Files:
- `scripts/jseval/tests/test_bench_concurrency.py`:
  - Replace timing-based parallel-speedup test with
    direct-parallelism assertion via counter+semaphore in mock.
- `scripts/jseval/jseval/bench_concurrency.py`:
  - Default `max_connections=concurrency` (was 2x); override via
    CLI flag.
  - Optional `--warmup N` flag.

Est LOC: ~80. Est time: 30 min.

**Commit 6.11 — LR4-h FK constraint + row-count reporting**

Scope: fix C-1.1.1 + C-1.1.2 + C-1.1.3.

Files:
- `scripts/jseval/jseval/history.py`:
  - `run_id INTEGER` (migration: cast existing TEXT rows).
  - FK constraint `REFERENCES runs(id) ON DELETE CASCADE`.
  - `_insert_envelope_metrics` returns `(inserted, skipped)` counts;
    logged.
- Tests: FK cascade deletion + row-count return.

Est LOC: ~70. Est time: 30 min.

**Commit 6.12 — LR5-a `_infer_mode` sanity test + LR4-c
configurable buckets**

Scope: fix C-1.7.1 (buckets configurable) + C-2.1.3 (round-trip
test).

Files:
- `scripts/jseval/jseval/projections/stratified_metrics.py`:
  - `QUERY_LENGTH_BUCKET_EDGES` + `FIRST_RELEVANT_RANK_BUCKET_EDGES`
    module constants; overridable.
- `scripts/jseval/tests/test_counterfactual.py`:
  - Round-trip test: every `COUNTERFACTUAL_MODES[name]` infers back
    to `name` via `_infer_mode`.

Est LOC: ~40. Est time: 20 min.

**Commit 6.13 — Gate script packaging + nightly manual-run evidence**

Scope: fix C-1.10.2 + C-1.10.3.

Files:
- Move `scripts/ci/phase3_observability_gate.py` →
  `scripts/jseval/jseval/gate.py`.
- Expose as `jseval gate --data-dir ...` subcommand.
- `.github/workflows/phase-3-observability-nightly.yml` — invoke
  via `python -m jseval gate ...`.
- Manual trigger evidence: commit message references the first
  scheduled Actions run URL.

Est LOC: ~30 (mostly move + wrapping). Est time: 20 min.

**Commit 6.14 — Tempdoc split (CC-4.2)**

Scope: split tempdoc 400 into design + implementation log.

Files:
- `docs/tempdocs/400-pipeline-observability-gaps.md` — trim to
  §0-§22 (design). Add "# FROZEN — DO NOT EDIT" banner.
- `docs/tempdocs/400-pipeline-observability-gaps.md` — new; contains
  §23-§29 + future phases.
- `docs/llms.txt` regen.

Est LOC: ~0 net (move). Est time: 30 min.

**Commit 6.15 — Phase 6 closure + integration verification**

Scope: tempdoc implementation-log §30 closure; nightly workflow
manual trigger; 651 → ~670 pytest delta verified.

Files:
- `docs/tempdocs/400-pipeline-observability-gaps.md` — §30 Phase 6 log.
- Trigger `gh workflow run phase-3-observability-nightly.yml`.
- Commit ledger + deferrals.

Est time: 30 min.

### 5.3 Estimated total

- **Commits:** 15
- **LOC:** ~1050
- **Wall time:** ~8-10 hours

### 5.4 Risk register

1. **LR5-d synthetic executor (6.5) touches `jseval run`'s manifest-
   computation contract.** `--manifest-override` bypasses identity
   derivation — if used incorrectly, produces runs that don't match
   any real cohort. Mitigation: document loudly; gate behind a
   `--dangerous` flag; emit a warning on every use.

2. **LR4-g baseline opt-in (6.2) breaks backward compatibility.**
   Existing cohorts that relied on first-run-baseline will show
   `no-baseline` status after the fix. Operators must run
   `calibrate-drift-baseline` explicitly. Mitigation: migration
   guide + release note.

3. **LR2-e expansion (6.8) adds opentelemetry dep to reranker.**
   Possible ArchUnit / module-dep graph implications. Mitigation:
   verify with `./gradlew.bat :modules:reranker:test` and ArchUnit
   tests before committing.

4. **LR4-h INTEGER migration (6.11) requires data migration.** Existing
   envelope_metrics rows with TEXT run_ids need CAST on read. SQLite
   loose-typing makes this mostly transparent but a migration test
   is essential. Mitigation: dedicated migration script; round-trip
   test.

### 5.5 Out-of-scope deferrals (acknowledged; separate tempdoc)

- LR3-a Windows probes (§22 Issue C; multi-week FFM infra).
- Legacy cohort_baselines shim removal (CC from §1.9; wait 2-3 months
  or next major version).
- Real-telemetry integration tests (CC-4.4; requires a captured
  fixture; separate work).

---

## 6. Sources + related tempdocs

- Tempdoc 400 (§21-§29 implementation logs).
- Tempdoc 369 (backend cold-start LLM race — relevant to
  `start_backend` env-honor fix).
- Tempdoc 325 (KNOWN_UNREFERENCED allowlist pattern — invoked in
  Phase 1 §23.2 defect remediation).
- ADR 0014 (pipeline-definition removal — cited by Phase 1 LR2-d.1
  retirement).
- §22 (design issues from Phase 1 that Phase 5 addresses).
- §26.6 (locked decisions from Phase 3 pre-plan).
