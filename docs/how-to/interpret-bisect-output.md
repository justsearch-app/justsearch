---
title: Interpret `jseval bisect` Output (LR5-d)
type: how-to
status: stable
description: "Reading the manifest-hash bisection report — single-axis, multi-axis, MULTI_AXIS_INTERACTION, no-cached-run, and identical-cohort paths."
---

# Interpret `jseval bisect` Output (LR5-d)

`jseval bisect` (tempdoc 400 LR5-d) attributes a metric delta between
two runs to the specific cohort-identity axis (or axes) that changed.
It walks the manifest diff, synthesizes single-axis-swapped cohorts,
and reports per-axis deltas against a fixed threshold.

This guide covers how to read each of the possible report shapes.

## Mental model

- **Cohort identity** is a hash tuple over `{git_sha, dataset,
  doc_count, query_count, commit_metadata, corpus_identity,
  model_fingerprints, policy_hash, eval_protocol_hash}`.
- **Bisection** finds which single axis (or subset) flipped to cause
  the metric delta.
- **Cache-only mode** answers only from already-observed runs. If no
  cached run exists for a given axis-swap cohort, the axis is
  `no-cached-run`.
- **Synthesize mode** (`--synthesize`) spawns a new `jseval run` for
  each missing cohort, gated by `JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS=1`.

## Report shapes

### 1. `status=identical-cohorts`

```json
{
  "status": "identical-cohorts",
  "metric": "nDCG@10",
  "metric_a": 0.8291,
  "metric_b": 0.8291,
  "axes_diff": []
}
```

**Meaning:** the two runs share the same manifest_hash; no bisection
was possible. Any metric delta is within-cohort run-to-run noise.

**What to do:** re-run the arm a couple of times and compare the
spread against the observed delta. Nothing to bisect.

### 2. `status=single-axis`

```json
{
  "status": "single-axis",
  "axis": "policy_hash",
  "metric_a": 0.8056,
  "metric_b": 0.7821,
  "delta": -0.0235,
  "threshold": 0.02
}
```

**Meaning:** exactly one axis differs between A and B, and the
absolute delta exceeds `--threshold` (default
`bisection.DEFAULT_THRESHOLD` = 0.02).

**What to do:** that axis is the proximate cause. Read the manifest
fields for both runs at the named axis to see the concrete change
(e.g. `policy_hash` changed because CE was toggled on, or
`model_fingerprints.embed` changed because the encoder model was
swapped).

### 3. `status=multi-axis`

```json
{
  "status": "multi-axis",
  "axes": [
    {"axis": "dataset", "cached_metric": 0.0000},
    {"axis": "query_count", "cached_metric": 0.8291}
  ],
  "metric_a": 0.8056,
  "metric_b": 0.0000
}
```

**Meaning:** multiple axes differ; at least one axis-swap cohort was
cache-hit and produced an intermediate metric, so the delta can be
decomposed axis-by-axis.

**What to do:** the per-axis `cached_metric` tells you the marginal
contribution of that axis. If one axis alone reproduces most of the
delta, it's the dominant cause. If multiple axes each produce
partial deltas, the effect is compositional — see
`MULTI_AXIS_INTERACTION` below.

### 4. `status=MULTI_AXIS_INTERACTION`

```json
{
  "status": "MULTI_AXIS_INTERACTION",
  "axes_diff": ["axis_a", "axis_b"],
  "axis_details": [
    {"axis": "axis_a", "delta": -0.01, "threshold": 0.02},
    {"axis": "axis_b", "delta": -0.01, "threshold": 0.02}
  ],
  "metric_a": 0.83, "metric_b": 0.79
}
```

**Meaning:** the tempdoc 400 §13.9 C2 documented limitation. Multiple
axes differ, but no *single-axis-swap* delta clears the threshold —
the axes interact. The bisection algorithm cannot
decompose them further (would require O(n²) 2-axis swaps).

**What to do:** investigate manually by:
1. Hand-constructing the 2-axis synthetic cohorts the operator
   suspects are interacting.
2. Running `jseval run` against each with
   `JUSTSEARCH_MANIFEST_OVERRIDE` (+`DANGEROUS=1`) to forge the
   intermediate cohorts.
3. Comparing the resulting metrics back to A and B.

Alternatively, accept that the two changes shipped together and treat
the pair as the cause.

### 5. `status=no-cached-runs`

```json
{
  "status": "no-cached-runs",
  "axes_diff": ["dataset", "doc_count", "query_count"],
  "axis_details": {
    "dataset": "no-cached-run",
    "doc_count": "no-cached-run",
    "query_count": "no-cached-run"
  }
}
```

**Meaning:** the axes are diagnosed, but no cached run exists at any
of the axis-swap cohorts. Cache-only mode has nothing to report.

**What to do:** either (a) wait for enough runs to accumulate in the
manifest index that one of the axis-swap cohorts gets hit naturally,
or (b) run `jseval bisect --synthesize` to spawn the missing runs on
demand.

### 6. `status=dry-run` (with `--synthesize --dry-run`)

```json
{
  "status": "dry-run",
  "axes": [
    {"axis": "axis_a", "status": null, "synthetic_cohort": "abc..."},
    {"axis": "axis_b", "status": null, "synthetic_cohort": "def..."}
  ]
}
```

**Meaning:** `--dry-run` surfaces the synthetic cohorts that
`--synthesize` *would* spawn, without actually running them.

**What to do:** confirm the axis set is what you expected; drop
`--dry-run` to execute. Each synthetic cohort run takes as long as
a normal `jseval run` (scifact full = ~4 min).

## Using `--synthesize` safely

`--synthesize` requires both `--dataset` + `--modes` and
`JUSTSEARCH_DATA_DIR` set. Each spawned sub-run carries a forged
manifest marked `"synthetic": true` so downstream consumers can
filter them out. Without that filter the synthetic cohort's metrics
would mix with real runs.

The `JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS=1` safety gate exists
because forging a manifest invalidates cohort identity. Never set
this in production.

## Related

- Tempdoc 400 §8.5 LR5-d — design.
- Tempdoc 400 §13.9 C2 — bisection algorithm + MULTI_AXIS_INTERACTION.
- Tempdoc 400 post-implementation critique C-1.5.1 — the
  cache-only-executor gap that Phase 6 / 6.5 closed via
  `--synthesize`.
- `docs/reference/configuration/environment-variables.md` —
  `JUSTSEARCH_MANIFEST_OVERRIDE` + `_DANGEROUS` semantics.
- `scripts/jseval/jseval/commands/analysis.py::cmd_bisect` — tool.
- `scripts/jseval/jseval/bisection.py` — implementation.
