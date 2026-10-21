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

Every number below comes from a **single sweep** of the **production-default `hybrid` pipeline** across
the full corpus set, at one published `git_sha`, on stated hardware — the reproducibility bar a hostile
reviewer asks for. No per-corpus config cherry-picking: where a non-default config is shown (e.g. `full`
for long legal documents), it is labelled as an **ablation alongside** the default, never *as* the
headline. The release object publishes the hardware (GPU, VRAM, driver, ORT version), the model identity,
and per-corpus confidence tiers + caveats. See the cohort/hardware block in `release.v1.json`.

## How to read the comparison: system vs. component

The external baselines below are **cited published numbers**, not results we re-ran (`self_reproduced:false`
everywhere) — and they are **not directly apples-to-apples**. Read them honestly:

- **System vs. component.** Our SciFact number is a **full hybrid (BM25 + dense + learned-sparse) +
  cross-encoder rerank pipeline**. The cited ColBERTv2 / SPLADE++ baselines are **single-model
  retrievers**. A hybrid+rerank *system* is *expected* to exceed a single *component* — so the right
  reading is "a reproducible offline hybrid system lands in the range of strong published retrievers,"
  not "we beat ColBERT." The per-row caveats carry this comparison class.
- **Split mismatch.** MIRACL published baselines are on the **dev** split; our axis is **test**. German
  is a MIRACL *surprise* language with no canonical baseline row (the only published number is BGE-M3's
  own non-canonical reproduction). These rows are footnoted accordingly — do not mix splits.
- **No test-set tuning.** Fusion weights / thresholds were not fit on the eval queries.

## Results

<!-- generated:start — do not edit between markers; run: node scripts/docs/gen-public-benchmark.mjs -->

*Default mode `hybrid`, commit `32d6a0a0e`, NVIDIA GeForce RTX 4070, 12 GiB VRAM, ORT 1.24.3. nDCG@10. External baselines are cited published numbers (not re-run by us) — see the comparison-class note above.*

| Corpus | Ours (mode) | nDCG@10 | Ablation | Published baselines (cited) |
|---|---|---|---|---|
| beir/scifact | hybrid | **0.757** | 0.662 (lexical); 0.734 (vector); 0.487 (splade) | BM25 (multifield) 0.665; SPLADE++ EnsembleDistil 0.710; ColBERTv2 0.693 |
| mixed/enron-qa | hybrid | **0.796** | 0.825 (lexical); 0.591 (vector); 0.140 (splade) | — |
| mixed/legal-clerc-200 | hybrid | **0.578** | 0.685 (lexical); 0.624 (vector); 0.045 (splade) | BM25 0.054; Contriever-MSMarco (zero-shot dense) 0.042 |
| mixed/miracl-de-2k | hybrid | **0.857** | 0.701 (lexical); 0.851 (vector); 0.776 (splade) | BGE-M3 Dense 0.567 (dev) |
| mixed/miracl-fr-2k | hybrid | **0.884** | 0.703 (lexical); 0.891 (vector); 0.858 (splade) | BM25 0.183 (dev); mDPR (zero-shot) 0.435 (dev); Hybrid (BM25+mDPR) 0.523 (dev) |

**Engine performance** (relative-ratchet guarded — tempdoc 640; lower latency / higher throughput / lower footprint better):

| Corpus | CE p50 (ms) | Index docs/s | Enrich docs/s | Resident (GB) |
|---|---|---|---|---|
| beir/scifact | 142 | 76.1 | 1.3 | 2.02 |
| mixed/enron-qa | 147 | 63.8 | 3.6 | 2.02 |
| mixed/legal-clerc-200 | 165 | 18.6 | 0.8 | 2.02 |
| mixed/miracl-de-2k | 133 | 125.6 | 34.1 | 2.02 |
| mixed/miracl-fr-2k | 143 | 151.7 | 46.6 | 2.02 |

<!-- generated:end -->

Per-corpus nDCG@10 floors are checked against a pinned baseline at release-composition time
(`python -m jseval relevance-gate`, `scripts/jseval/relevance-ratchet-baselines.v1.json`); that is a
local gate run when a release is composed, not an automated CI job. The README table itself is checked
against the release object in CI (`scripts/ci/check-readme-benchmark-numbers.mjs`). The internal
search-quality register carries the full per-config ablation log (`docs/reference/search-quality-register.md`).

## Agent-utility publication

The paired-agent methodology, evidence contract, policy semantics, and current publication state are
documented in [`agent-utility.md`](agent-utility.md).

## Reproduce it

From `scripts/jseval` (Windows; a CUDA GPU accelerates inference, CPU also works):

```bash
python -m jseval run --start-backend --dataset beir/scifact --modes hybrid
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
