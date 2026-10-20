---
title: Indexing Throughput Reference
type: reference
status: stable
description: "Throughput-governing parameters, measured rates, and bottleneck analysis for the indexing pipeline"
---

# Indexing Throughput Reference

Current parameter values governing indexing throughput, measured rates
under tested configurations, and the bottleneck structure that explains
them. For how to run benchmarks see `docs/explanation/20-benchmarking-architecture.md` and `python -m jseval` (the prior `scripts/perf/` harness was removed — tempdoc 638);
for indexing loop architecture see `docs/explanation/03-knowledge-server.md`.

## Throughput-Governing Parameters

The loop policies live in `modules/worker-services`; the embedding and SPLADE
encoders live in `modules/worker-core`. Property-key rows are configuration
defaults resolved through `EnvRegistry` / `ResolvedConfig` and can be overridden.
The remaining named constants are compiled into the indicated class.

### Loop Pacing

| Parameter | Value | Class | Effect |
| :--- | :--- | :--- | :--- |
| **Primary indexing** | | | |
| `justsearch.backfill.poll_batch_size` | 16 | `EnvRegistry` | Max jobs dequeued per loop iteration |
| `IDLE_SLEEP_MS` | 1000 ms | `LoopPacingPolicy` | Sleep between iterations when queue empty and no recent activity |
| `ACTIVE_IDLE_SLEEP_MS` | 100 ms | `LoopPacingPolicy` | Sleep between iterations when recently active |
| `justsearch.indexing.foreground_duty_pct` | 20 | `IndexingPacing` | Minimum share of wall time indexing keeps while foreground search RPCs are in flight (a duty cycle; replaced the `BREATH_HOLD_MS` pause) |
| `justsearch.indexing.foreground_cooldown_ms` | 500 ms | `IndexingPacing` | How long after the last foreground RPC the Worker still counts as contended |
| **Commit policy** | | | |
| `justsearch.backfill.commit_interval_ms` | 10000 ms | `EnvRegistry` | Time-based Lucene commit trigger for primary indexing |
| `justsearch.backfill.max_docs_before_commit` | 1000 | `EnvRegistry` | Count-based Lucene commit trigger for primary indexing |
| **Backfill pacing** | | | |
| `justsearch.backfill.embedding_batch_size` | 100 | `EnvRegistry` | Doc and chunk embedding backfill batch |
| `justsearch.backfill.ner_batch_size` | 100 | `EnvRegistry` | NER extraction backfill batch |
| `justsearch.backfill.disambiguation_batch_size` | 500 | `EnvRegistry` | Entity disambiguation backfill batch |
| `justsearch.backfill.splade_batch_size` | 200 | `EnvRegistry` | SPLADE backfill batch (idle path) |
| `justsearch.backfill.splade_interleave_batch_size` | 10 | `EnvRegistry` | SPLADE batch during primary indexing |
| `justsearch.backfill.splade_interleave_interval_ms` | 5000 ms | `EnvRegistry` | Min interval between SPLADE interleaves |

### Embedding Inference

| Parameter | Value | Class | Effect |
| :--- | :--- | :--- | :--- |
| `MAX_ORT_BATCH_SIZE` | 8 | `OnnxEmbeddingEncoder` | ORT session sub-batch ceiling; the device-memory plan can choose a smaller batch |
| `chunkSize` | min(512, maxSeqLen) | `OnnxEmbeddingEncoder` | Sliding-window chunk width in tokens |
| `chunkOverlap` | 128 | `OnnxEmbeddingEncoder` | Overlap between adjacent chunks in tokens |
| `justsearch.embed.gpu_mem_mb` | 6144 MB | `ResolvedConfigBuilder` | GPU arena limit for embedding sessions |

### SPLADE Inference

| Parameter | Value | Class | Effect |
| :--- | :--- | :--- | :--- |
| `MAX_SPLADE_BATCH_SIZE_CPU` | 4 | `SpladeEncoder` | Max texts per ORT call on CPU (batch=4 optimal, 2.28x; batch=8 regresses) |
| `MAX_SPLADE_BATCH_SIZE_GPU` | 4 | `SpladeEncoder` | Max texts per ORT call on GPU |
| `SEQ_LEN_BUCKETS` | {128, 256, 384, 512} | `SpladeEncoder` | Fixed tensor shapes to prevent BFCArena fragmentation |
| `maxSequenceLength` | 512 | `SpladeConfig` | Token truncation ceiling (default, env-overridable) |
| `gpuMemLimitBytes` | 4096 MB | `SpladeConfig` | GPU arena for SPLADE sessions (default, env-overridable) |
| `justsearch.splade.gpu_enabled` | Per-model override, else master switch, else false | `ResolvedConfigBuilder` | Per-model override via `JUSTSEARCH_SPLADE_GPU_ENABLED`; device availability and enterprise policy can disable GPU use |

## Measured Throughput

Current release rates are projected from `scripts/jseval/release.v1.json` into
the [engine-quality scorecard](../benchmarks/scorecard.md), with separate indexing
and enrichment rates for each corpus. Use that source for current comparisons.

The measurements below are historical (March–April 2026), from tempdocs 268,
273, 278, 310, 311, 312 and 334 on developer hardware
(20 logical cores, RTX 4070 12 GB VRAM). They used the former Claim A
(engine-only) and Claim B (pipeline) harnesses; those runners are no longer
under `scripts/bench/`.

### Baseline Rates (pre-optimization)

| Configuration | Rate | Dataset | Notes |
| :--- | :--- | :--- | :--- |
| Engine-only (no extraction or embedding) | 2,800–3,600 docs/sec | Claim A suite | Lucene write ceiling |
| Full pipeline (Tika + embed + SPLADE inline) | ~4 docs/sec | Mixed files | Pre-tempdoc-278 baseline |

### Historical combined enrichment (2026-04)

The former “Current Rates” table, stage timeline, and batch breakdown described
the April 2026 EmbeddingGemma configuration (tempdocs 311 and 334). Refer to those
dated records for the experiment; use the [scorecard](../benchmarks/scorecard.md)
for current release figures. End-to-end completion time and the release's
separate indexing/enrichment rates are different metrics.

**Historical note (2026-04-09):** the GPU inference serialization fix
(tempdoc 311, commit `215b72f22`) used a per-session `Semaphore(1)` to prevent
overlapping GPU calls. `NativeSessionHandle` now acquires a per-device
`DeviceEncoderGate` seat for GPU execution; the old semaphore description
does not describe current coordination.

Pre-optimization rates (tempdocs 268, 273, 278):

| Configuration | Rate | Dataset | Notes |
| :--- | :--- | :--- | :--- |
| Primary indexing (inline embed, nomic) | 5.3–5.8 docs/sec | SciFact (5.2K) | Pre-tempdoc-312 baseline |
| Primary indexing (inline embed, nomic, Phase 0+1) | 8.6 docs/sec | SciFact (1K) | After batch markDone + batch queueDepth + skip isUnmodified |

### SPLADE Model Optimization (2026-03-16)

The then-default SPLADE model (`naver/splade-v3`) was the only FP32 model in
the pipeline (embedding and reranker were already INT8). ORT transformer
optimizer (O3) + dynamic INT8 quantization reduces the model from 532 MB
to 134 MB with 1.55x CPU throughput and no quality regression.

| Model variant | Size | CPU rate (early) | vs FP32 | Fusions |
| :--- | :--- | :--- | :--- | :--- |
| FP32 (original) | 532 MB | 1.10 docs/sec | 1.00x | none |
| O3 FP32 (fused) | 532 MB | 1.39 docs/sec | 1.26x | 12 Attn, 24 SkipLN, 13 BiasGelu |
| Raw INT8 | 134 MB | 1.47 docs/sec | 1.34x | none |
| **O3+INT8 (default)** | **134 MB** | **1.71 docs/sec** | **1.55x** | **12 Attn, 24 SkipLN, 13 BiasGelu** |
| O3+FP16 (GPU default) | 266 MB | 1.96 docs/sec | 1.78x | 12 Attn, 24 SkipLN, 13 BiasGelu |

Quality validation (SciFact, 300 queries): SPLADE nDCG@10 delta -0.004
(FP32: 0.590, O3+INT8: 0.586). Lexical unchanged. Zero query errors.

### Why the historical rates differed

This breakdown describes the tempdoc 334 configuration, not the current release.

**Primary indexing** (deferred embedding): With embedding deferred to
backfill (tempdoc 312 item 19), extract and write are ~2.5ms/doc each,
balanced with no single dominant bottleneck. Rate of ~201 docs/sec.
Users get BM25 search immediately; vector search improves progressively
as backfill completes.

**Combined backfill** (single-pass enrichment, tempdoc 334): All three
inference phases (embedding, SPLADE, NER) run in a single read-modify-write
pass per document, eliminating redundant Lucene reads. SPLADE is the
dominant cost at 40% of batch time. GPU acceleration is used for all
three phases. SPLADE is gated on embedding completion to avoid wasted
encode cycles on docs that still need vectors.

**Embedding** (EmbeddingGemma Q4 GPU): 390ms per 100-doc batch (32% of
combined batch time). Q4 variant (188 MB) is 1.9x faster than INT8
(298 MB) on GPU. INT8 is used as CPU fallback.

**SPLADE**: 476ms per 100-doc batch (40% of combined batch time). FP16
GPU variant (266 MB) is preferred; FP32 (508 MB) for CPU fallback.

**NER** (DistilBERT-NER FP16 GPU): 209ms per 100-doc batch (17% of
combined batch time). Lighter model than bert-base-NER with comparable
extraction quality.

### Pipeline Profiling (2026-03-16)

Per-doc time breakdown for GPU SPLADE backfill (O3+FP16, batch=50,
1000-doc SciFact subset, 6 batch samples):

| Phase | Avg/doc | % of total |
| :--- | :--- | :--- |
| Query (find pending IDs) | 0 ms | 0% |
| Content fetch (Lucene read) | 0 ms | 0% |
| Encode (tokenize + ORT + postProcess) | 28 ms | 24% |
| **Lucene updateDocument()** | **88 ms** | **74%** |
| Commit | 3 ms | 2% |
| **Total** | **119 ms** | **100%** |

### updateDocument() Sub-Profiling (2026-03-16)

Fine-grained instrumentation of `updateDocument()` internals (1,273 calls,
1000-doc SciFact subset, CPU SPLADE O3+INT8):

| Step | Avg | Median | P95 | Max | % of updateDocument |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **NRT refresh (`maybeRefreshBlocking`)** | **85.1 ms** | **75 ms** | **180 ms** | **712 ms** | **99.0%** |
| search (TermQuery by ID) | 0 ms | 0 ms | 0 ms | 0 ms | ~0% |
| loadFields (StoredFields) | 0 ms | 0 ms | 0 ms | 0 ms | ~0% |
| toDocument (FieldMapper) | 0 ms | 0 ms | 0 ms | 1 ms | ~0% |
| iwUpdate (IndexWriter) | 0.07 ms | 0 ms | 1 ms | 4 ms | ~0.1% |

The dominant bottleneck is `maybeRefreshBlocking()` — the NRT searcher
refresh called per document. The actual read-modify-write (search, load
stored fields, rebuild, IndexWriter update) is sub-millisecond. This
refresh is needed for interactive read-after-write consistency but is
unnecessary during batch backfill.

### Batch Write Optimization (2026-03-16, tempdoc 310)

`updateDocumentsBatch()` eliminates per-doc NRT refresh: one
`maybeRefreshBlocking()` per batch instead of per document. Measured
write cost drops from 88 ms/doc to **0.3 ms/doc** (293x). Pipeline is
now encode-limited.

| Configuration | Write/doc | Pipeline rate | Notes |
| :--- | :--- | :--- | :--- |
| Before (per-doc refresh, batch=50) | 88 ms | 5.8 docs/sec | NRT refresh dominated |
| After (batch RMW, CPU O3+INT8) | 0.3 ms | 10.2 docs/sec | Encode-limited |
| **After (batch RMW, GPU O3+FP16)** | **0.2 ms** | **40.1 docs/sec** | **Encode-limited; 6.9x over pre-batch** |

Batch write also applied to embedding and NER backfill stages, reducing
chunk embedding batch time from 18-25s to 7-13s.

### Historical optimization notes

The completion statuses and opportunities below reflect the March–April 2026 work.

1. ~~Eliminate per-doc NRT refresh~~ — **Done** (tempdoc 310). Write cost
   reduced from 88ms/doc to 0.3ms/doc. Pipeline is now encode-limited.
2. ~~Defer embedding to backfill~~ — **Done** (tempdoc 312 item 19).
   Primary indexing from 5.8 to 201 docs/sec.
3. ~~Batch `markDone` + `queueDepth` + skip `isUnmodified`~~ — **Done**
   (tempdoc 312 items 8–10). +54% on inline embedding path.
4. ~~Q4 embedding model~~ — **Done** (tempdoc 334). EmbeddingGemma Q4
   (188 MB) adopted as GPU default, INT8 (298 MB) as CPU fallback.
5. ~~Combined single-pass enrichment~~ — **Done** (tempdoc 334).
   Embed + SPLADE + NER in one RMW per doc. Eliminated redundant reads.
6. ~~GPU acceleration for all inference phases~~ — **Done** (tempdoc 334).
   Embedding Q4 GPU, SPLADE FP16 GPU, NER FP16 GPU.
7. **Matryoshka dimension reduction** — EmbeddingGemma supports 128–768
   dims; reducing to 256 retains ~95–97% quality. Reduces index size 3x
   and search cost. Does NOT reduce inference time (truncation is
   post-inference). Tempdoc 312 item 31.
8. **Pipeline parallelism** — overlap extract with Lucene writes. Less
   impactful now that both are ~2.5ms/doc with deferred embedding. Est. 1.1x.

## Backfill Ordering

When the job queue is empty, the indexing loop runs backfill stages in
priority order. Embedding, SPLADE, and NER run as combined single-pass
enrichment (one RMW per doc). SPLADE is gated on embedding completion.

### Idle-loop priority

1. **Combined enrichment (embed + SPLADE + NER)** — single-pass backfill via `EmbeddingBackfillOps`; GPU-gated via `shouldRunBackfill()`; SPLADE gated on embedding completion
2. **Chunk vector backfill** — runs when doc embedding queue empty; gated by `chunkVectorsEnabled`
3. **Disambiguation backfill** — runs when NER queue empty

## Related Documents

- `docs/explanation/20-benchmarking-architecture.md` — how to run throughput benchmarks (via `python -m jseval`)
- `docs/explanation/03-knowledge-server.md` — indexing loop architecture
- `docs/explanation/20-benchmarking-architecture.md` — Claims/Lanes framework
- `docs/reference/configuration/environment-variables.md` — SPLADE GPU env vars
