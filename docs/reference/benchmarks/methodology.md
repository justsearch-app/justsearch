---
title: Benchmark Methodology
type: reference
status: stable
description: "How JustSearch measures retrieval quality: the reproducible single-config release, honest comparison classes, and how to reproduce it."
---

# Benchmark Methodology

This doc answers: *"What do JustSearch's retrieval-quality numbers mean, how were they produced, and how
do I reproduce them?"* It is the standalone methodology behind the README's benchmark table. The numbers
are a **projection** of one canonical, reproducible release object (`scripts/jseval/release.v1.json`),
never hand-transcribed — so they cannot silently drift (tempdoc 623; 633).

## What we measure (and what we don't)

We measure **retrieval ranking quality** — nDCG@10 over a fixed set of IR corpora. We do **not** measure
document-extraction/OCR quality or end-to-end answer correctness here. All numbers are nDCG@10 unless noted.

## The release: one config, all corpora, one commit

Every current number below comes from a new Linux sweep of the explicit `hybrid` preset across
five collections at one source revision. This preset uses keyword + vector + cross-encoder reranking;
learned-sparse is off (`SearchPipelinePresets.expandPreset`). The lexical, vector and learned-sparse
(`splade`) modes are separate ablations. The operating system, hardware, model identities and source
run hashes are recorded in `release.v1.json`. This is a new measurement, not a rerun of the Windows
release or a claim of identical runtime behavior across operating systems.

The source revision is `c2892448eadbf81d97fc507af1a8b666fd6c7896` with the uncommitted judgment-import
correction applied during measurement; each exact source summary is SHA-256-pinned in the release.
The five runs used a clean data folder per collection and ran sequentially with nice/ionice.
Hardware/runtime: Linux 7.0 (x86_64), NVIDIA RTX 4070 12 GB, driver 580.178.04,
ORT 1.24.3 with host CUDA 12.9/cuDNN 9. Learned-sparse ablation rows changed in part because
#1092 (windowed SPLADE encoding) is included in this measured source revision.

The Windows release `832-rebaseline-2026-08-14` is **superseded**: its MIRACL judgments retained
only the first relevant document and its hybrid description incorrectly included learned-sparse.
Its original numbers remain as qualified history at
[`832-rebaseline-2026-08-14.v1.json`](../../../scripts/jseval/releases/832-rebaseline-2026-08-14.v1.json)
and cannot supply current claims or regression floors. The Linux run retains the same MIRACL document
pools, with every upstream positive and grade for the dev queries; local `qrels/test.tsv` is a layout
filename, not an upstream test split. Historical results that cannot be re-derived are withdrawn as
current evidence, without imputing replacement measurements.

## How to read the comparison: system vs. component

The external baselines below are **cited published numbers**, not results we re-ran (`self_reproduced:false`
everywhere) — and they are **not directly apples-to-apples**. Read them honestly:

- **System vs. component.** Our SciFact number is a **hybrid (keyword + vector) +
  cross-encoder rerank pipeline**. The cited ColBERTv2 / SPLADE++ baselines are **single-model
  retrievers**. A hybrid+rerank *system* is *expected* to exceed a single *component* — so the right
  reading is "a reproducible offline hybrid system lands in the range of strong published retrievers,"
  not "we beat ColBERT." The per-row caveats carry this comparison class.
- **Document-pool mismatch.** Both our MIRACL queries and the published baselines use the **dev** split. Our document pools are much smaller. German
  is a MIRACL *surprise* language with no canonical baseline row (the only published number is BGE-M3's
  own non-canonical reproduction). These rows are footnoted accordingly; sharing a split does not make the pools comparable.
- **No test-set tuning.** Fusion weights / thresholds were not fit on the eval queries.

## Results

<!-- generated:start — do not edit between markers; run: node scripts/docs/gen-public-benchmark.mjs -->

*Release `linux-complete-qrels-2026-11-02`, default mode `hybrid`, commit `c2892448e`, Linux 7.0.0-38-generic x86_64, NVIDIA GeForce RTX 4070, 12 GiB VRAM, ORT 1.24.3. nDCG@10. External baselines are cited published numbers (not re-run by us) — see the comparison-class note above.*

Explicit hybrid mode uses keyword + vector + cross-encoder reranking; learned-sparse is off in this preset. MIRACL uses dev queries and complete positive judgments in the retained sampled document pool. This Linux measurement is a new release, not a rerun of the superseded Windows release.

Legal caveat: legal-clerc-200 has 198 documents, all judged relevant to at least one query, and queries of roughly 260 words. It is a small citation-retrieval pool, not a representative large legal archive.

| Corpus | Ours (mode) | nDCG@10 | Ablation | Published baselines (cited) |
|---|---|---|---|---|
| beir/scifact | hybrid | **0.759** | 0.658 (lexical); 0.734 (vector); 0.568 (splade) | BM25 (multifield) 0.665; SPLADE++ EnsembleDistil 0.710; ColBERTv2 0.693 |
| mixed/enron-qa | hybrid | **0.806** | 0.830 (lexical); 0.553 (vector); 0.345 (splade) | — |
| mixed/legal-clerc-200 | hybrid | WITHHELD | 0.691 (lexical); 0.617 (vector); 0.388 (splade) | BM25 0.054; Contriever-MSMarco (zero-shot dense) 0.042 |
| mixed/miracl-de-2k | hybrid | **0.986** | 0.786 (lexical); 0.979 (vector); 0.917 (splade) | BGE-M3 Dense 0.567 (dev) |
| mixed/miracl-fr-2k | hybrid | **0.990** | 0.785 (lexical); 0.986 (vector); 0.962 (splade) | BM25 0.183 (dev); mDPR (zero-shot) 0.435 (dev); Hybrid (BM25+mDPR) 0.523 (dev) |

Cross-encoder coverage (applied / all queries; deterministic skips and deadline drops are reported separately):

- beir/scifact: 299/300; 0 deterministic skips; 1 deadline drops; harness verdict `ok`.
- mixed/enron-qa: 277/300; 17 deterministic skips; 6 deadline drops; harness verdict `ok`.
- mixed/miracl-de-2k: 305/305; 0 deterministic skips; 0 deadline drops; harness verdict `ok`.
- mixed/miracl-fr-2k: 343/343; 0 deterministic skips; 0 deadline drops; harness verdict `ok`.

**mixed/legal-clerc-200 hybrid withheld:** Under the default 200 ms reranker budget, the cross-encoder missed its deadline on 25/200 queries (12.5%) of roughly 260 words on this machine; 174/200 were reranked, with one deterministic navigational skip. Harness verdict: degraded-ce, not comparable. An earlier run under heavier load had 60% deadline misses. Hybrid quality and its regression floor are withheld; lexical/vector/splade ablations remain available for this 198-document pool, all documents judged relevant to at least one query.

**Engine performance** (relative-ratchet guarded — tempdoc 640; lower latency / higher throughput / lower footprint better):

| Corpus | CE p50 (ms) | Index docs/s | Enrich docs/s | Resident (GB) |
|---|---|---|---|---|
| beir/scifact | 159 | 36.7 | 11.5 | 2.02 |
| mixed/enron-qa | 237 | 25.8 | 2.7 | 2.02 |
| mixed/miracl-de-2k | 160 | 32.9 | 19.1 | 2.02 |
| mixed/miracl-fr-2k | 173 | 40.8 | 20.0 | 2.02 |

<!-- generated:end -->

Per-corpus nDCG@10 floors are checked against a pinned baseline at release-composition time
(legal hybrid has no floor while withheld; `python -m jseval relevance-gate`, `scripts/jseval/relevance-ratchet-baselines.v1.json`); that is a
local gate run when a release is composed, not an automated CI job. The README table itself is checked
against the release object in CI (`scripts/ci/check-readme-benchmark-numbers.mjs`). The internal
search-quality register carries the full per-config ablation log (`docs/reference/search-quality-register.md`).

## Agent-utility publication

The paired-agent methodology, evidence contract, policy semantics, and current publication state are
documented in [`agent-utility.md`](agent-utility.md).

## Reproduce it

From the repository root on Linux (run one collection at a time at low CPU and I/O priority):

```bash
export PYTHONPATH=scripts/jseval
nice -n 10 ionice -c 2 -n 7 python -m jseval run --start-backend --dataset beir/scifact --modes lexical,vector,splade,hybrid
python -m jseval relevance-gate --dataset beir/scifact
```

Dataset slugs: `beir/scifact`, `mixed/enron-qa`, `mixed/legal-clerc-200`, `mixed/miracl-de-2k`,
`mixed/miracl-fr-2k`. Corpora are fetched from their canonical sources (pointer + checksum), not
redistributed here. Run-to-run variation is reported per run; no envelope calibration is required —
each run's `summary.json` carries its own `per_mode.<mode>.latency_stats` (p50/p95/p99/max over that
run's queries) and `encoder_latency.encoders.<name>` (per-encoder ONNX p50/p95), so a third party
compares their run's spread against the published one directly.

## See also
- `scripts/jseval/release.v1.json` — the canonical release object (the source of truth for every number above).
- `scripts/jseval/external-baselines.v1.json` — the cited external baselines (source + version + caveat per row).
- [`search-quality-register.md`](../search-quality-register.md) — the internal per-config research log.
