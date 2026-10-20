---
title: Reliability Budget
type: reference
status: stable
description: "Dormant reliability hotspot report: no repository producer currently writes its local gate summary inputs."
---

# Reliability Budget

The reliability budget report is **dormant**: no current repository command produces its
`local-agent-gate-*.json` inputs. Without externally supplied summaries, it analyzes an empty
window; that is not evidence of a reliable run. The command remains available to analyze
compatible historical or externally supplied summaries by failure frequency.

## What It Uses

- Source artifacts: `tmp/agent-evidence/_summaries/local-agent-gate-*.json`
- Optional log parsing: Gradle stdout/stderr paths referenced by each summary
- Output artifacts:
  - `tmp/reliability-budget/latest.json`
  - `tmp/reliability-budget/latest.md`

## Command

```bash
node scripts/ci/report-reliability-budget.mjs
```

Optional flags:

- `--lookback-runs <n>`: analyze latest `n` runs (default `30`)
- `--summaries-dir <path>`: override input directory
- `--out-json <path>` / `--out-md <path>`: override output paths
- `--fail-on-threshold --threshold <0..1>`: optional strict mode (not used by default flow)

## Interpretation

### Lane failures

The lane table ranks lanes by:

1. failure count (descending)
2. last failure timestamp (most recent first)

`fail_rate` is computed as `failures / ran_count` for that lane.

If a gate run aborts before any lane executes (for example, dev-runner startup failure), it is tracked separately as `early_abort_runs` in the run window and emitted as a warning.

### Failed tests

The test table is parsed from Gradle logs and ranked by:

1. failure count (descending)
2. last seen failure timestamp (most recent first)

`flaky_candidate=true` means the same test failed in at least 2 runs with at least one pass (non-consecutive failures) between them in the selected window.

## Triage Policy

Prioritize fixes in this order:

1. highest-frequency lane failures
2. highest-frequency failed tests
3. flaky candidates in high-impact suites

This keeps the reliability ratchet focused on recurring breakpoints rather than one-off noise.

## Default exit policy

The standalone report is warn-only by default: hotspots do not cause a nonzero exit unless
`--fail-on-threshold` is supplied. It is not wired into a local gate or CI. Any future enforcement
would require a summary producer and an explicit policy decision.
