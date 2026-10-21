---
title: Benchmarking Architecture (Claims + Competitor Suite)
type: explanation
status: stable
description: "Retired claim/competitor harnesses and the surviving Java benchmarks and jseval tooling."
---

# Benchmarking Architecture (Claims + Competitor Suite)

JustSearch treats performance numbers as **reviewable artifacts** (JSON + markdown), not console output. This doc explains the *code and logic* behind the benchmarking system so results stay repeatable, diffable, and interpretable.

The PowerShell claim/competitor suite runners, their schema files, corpora, baselines and Node DAG
runner formerly documented here were removed on 2026-03-16. The Java benches remain; the old suite
commands and schema paths are not current tooling.

## Current benchmark surfaces

Java benchmarks live under `modules/benchmarks/src/main/java/io/justsearch/benchmarks/`.

### EngineIndexBench

- Java runner: `modules/benchmarks/src/main/java/io/justsearch/benchmarks/EngineIndexBench.java`
- Measures isolated Lucene runtime throughput (no indexing queue, no extraction).

Input is an NDJSON file (usually from BEIR tooling) and the bench measures Lucene runtime throughput + sentinel validation.

The Java runner still writes `result.json` to `--out-dir` (default `tmp/bench/claim-a/`), including
`time_to_searchable_ms`, throughput and `sentinel_validated`. The deleted suite schema and summary
writers are not required for that output.

### Filtered kNN and quantization gates

- Java runners: `FilteredKnnBench`, `VectorQuantizationGate`, `RerankerDeadlineBench`.

The current evaluation harness is [jseval](../reference/jseval-pipeline-reference.md).
`scripts/bench/` retains separate hardware probes such as `run-B1.ps1`.

## Measurement cautions

The indexing duty cycle means a tight interactive search loop alters the workload — it no longer stops indexing, but it does hold it at the configured minimum duty. Prefer waiting on status endpoints and validating searchability as a single check.

For vector retrieval, report recall with latency and prefer a curve over a single number.
Prefer medians over single runs; record run counts and variance. Be explicit about timing boundaries
(especially server startup, ingestion, indexing and sentinel validation).
