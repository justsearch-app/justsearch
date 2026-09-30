---
title: Workflow Telemetry Contract v1
type: contract
status: stable
updated: 2026-03-10
description: Run-centric workflow evidence, join rules, derived metrics, and comparison constraints.
---

# Workflow Telemetry Contract v1

> **Removed (tempdoc 638):** the workflow-telemetry subsystem (scripts/lib/workflow-telemetry.mjs + the bench/*workflow* scripts) was deleted; this document is retained as historical design context only.

## Scope

This contract defines the repo-owned workflow-run evidence model used by workflow wrappers, DAG
runners, and workflow comparison tooling.

It complements, but does not replace:

- runtime readiness contracts such as [health-readiness-contract.v1.md](health-readiness-contract.v1.md)
- search-eval artifacts governed by the benchmark/eval harness (see `docs/explanation/20-benchmarking-architecture.md`)
- session-centric agent analytics under `tmp/agent-telemetry/`

## Canonical Store

Workflow-run evidence lives under:

```text
tmp/workflow-telemetry/runs/<workflowRunId>/
  meta.json
  events.ndjson
```

Local workflow evidence is canonical. External backends may mirror or enrich it, but they do not
replace the repo-owned files as the source of truth.

## Canonical Files

### `meta.json`

`meta.json` is a single workflow-run document with additive evolution.

Required core fields:

- `kind = workflow-run-meta.v1`
- `schemaVersion = 1`
- `workflowRunId`
- `runId`
- `workflowFamily`
- `lifecycleEngine`
- `source`
- `status`
- `startedAt`
- `updatedAt`

Optional join and evidence fields:

- `session_id`
- `trace_id`
- `finishedAt`
- `durationMs`
- `artifacts`
- `tags`
- `stats`

Compatibility rule:

- `runId` is the compatibility alias for `workflowRunId`
- for v1 they are emitted with the same value

### `events.ndjson`

`events.ndjson` is append-only and contains one JSON document per workflow event.

Required envelope fields:

- `kind = workflow-run-event.v1`
- `schemaVersion = 1`
- `workflowRunId`
- `runId`
- `seq`
- `ts`
- `event`

Optional context fields:

- `workflowFamily`
- `lifecycleEngine`
- `stepId`
- `status`
- `detail`

## Join Model

### Canonical join key

- `workflowRunId`

### Contextual join keys when available

- `workflowFamily`
- `lifecycleEngine`
- `dataDir`
- `apiPort`
- `dagRunId`
- `dagStepId`

### Session and trace correlation

When available, the workflow layer may also carry:

- `session_id`
- `trace_id`

Non-equivalence rule:

- `workflowRunId`
- `runId`
- `session_id`
- runtime `TraceContext.traceId`
- distributed-trace `trace_id`

must not be assumed identical by convention. The relationship is explicit when present, not implied
by equality.

`manualForensicsCount` uses `session_id` plus workflow time bounds when matching
`tmp/agent-telemetry/` events. If no matching session telemetry exists, the value is `null`, not
implicitly `0`.

### Agent-driven attribution semantics

Phase-1 attribution keeps `workflowRunId` canonical and treats `session_id` as an optional
contextual join key.

- maintained workflow entrypoints may resolve `session_id` from `JUSTSEARCH_AGENT_SESSION_ID`
- maintained workflow entrypoints may also accept explicit `--session-id <id>` overrides
- precedence is explicit `--session-id`, then `JUSTSEARCH_AGENT_SESSION_ID`, then `null`
- agent-driven workflow runs are expected to carry `session_id`
- manual shell runs and CI runs may legitimately remain unattributed

The repo-local attribution report is `scripts/bench/report-workflow-attribution.mjs`. It classifies
workflow runs as:

- `attributed`
- `orphaned_session_ref`
- `unattributed`

Before classification, the attribution report auto-reconciles stale
`status = running` workflow metas older than 24 hours when no strong live
ownership signal exists.

Repair semantics:

- reconciled stale runs are written back to the canonical local workflow store
- the reconciled terminal status is `failed`
- the repair appends a `run_reconciled` event
- report output additively includes `summary.reconciledRuns` and a top-level
  `reconciledRuns` array

## Implemented Workflow Events

Common lifecycle and orchestration events include:

- `started`
- `workflow_lock_acquired`
- `workflow_lock_conflict`
- `stale_lock_recovered`
- `port_discovered`
- `http_ready`
- `index_serving`
- `workload_quiescent`
- `stale_artifact_detected`
- `cleanup_result`
- `stopped`
- `workflow_lock_released`
- `parameter_binding_error`
- `circuit_breaker_during_baseline`
- `step_started`
- `output_heartbeat` (historical identifier; no producer is present in this checkout)
- `step_finished`
- `run_finished`
- `run_reconciled`

## Derived Metrics

Current derived metrics from `scripts/lib/workflow-telemetry.mjs` include:

- `durationMs`
- `readyHttpMs`
- `indexServingMs`
- `workloadQuiescentMs`
- `fallbackReadinessUsed`
- `staleArtifactCount`
- `parameterBindingErrorCount`
- `circuitBreakerDuringBaseline`
- `portsClosed`
- `orphanProcessReaped`
- `manualForensicsCount`
- `silentWindowMaxMs`
- `silentWindowCount`

Attached evidence summaries may also expose:

- `manifest.decision_gate_status`
- `manifest.final_exit_code`
- summary metadata such as `kind`, `step`, and `dataset`

## Artifact Attachment Rules

When available, workflows should attach evidence paths in `meta.json.artifacts`.

Current supported evidence paths include:

- `manifest`
- `summaryFile`
- `outDir`
- `outBaseDir`
- `progressFile`
- `metricsFile`
- `metricsV2File`
- `resultDirs`
- workflow stdout/stderr logs

Paths should remain repo-relative when they are under the repo root. Absolute paths are allowed only
when the artifact is outside the repo root.

Lock conflicts are surfaced as structured workflow failures. Canonical wrappers should emit
`workflow_lock_conflict` and terminate the run, rather than silently retrying in the background.

## Workflow-Family SLOs

SLO thresholds are defined in `scripts/lib/workflow-telemetry.mjs` as `WORKFLOW_SLOS` and
evaluated by `evaluateWorkflowSLOs()`. The closure runner (`run-workflow-quality-closure.mjs`)
exits non-zero when any SLO is violated.

### Timing SLOs (per engine)

| Metric | Direct Engine | Legacy Engine | Basis |
|--------|--------------|---------------|-------|
| `readyHttpMs` | 30,000 ms | 120,000 ms | ~3x p95 from N=173 runs |
| `indexServingMs` | 45,000 ms | 180,000 ms | headroom above readyHttpMs |

### Boolean and Count SLOs (all engines)

| Metric | Expected | Meaning |
|--------|----------|---------|
| `portsClosed` | `true` | Ports must be released after stop |
| `orphanProcessReaped` | `false` | No orphan processes should need reaping |
| `staleArtifactCount` | `0` | No stale runtime artifacts on clean start |
| `parameterBindingErrorCount` | `0` | No parameter binding errors |
| `circuitBreakerDuringBaseline` | `false` | No circuit breaker during baseline eval |

### Recalibration policy

SLO thresholds should be updated when the underlying data population grows materially (e.g., after
a major lifecycle refactor or engine change). The basis column records what data was used to set
each threshold.

## Comparison Constraints

`scripts/bench/compare-workflow-runs.mjs` is the canonical comparison surface.

Comparison rules:

- compare runs only when they are from the same workflow family and the same lifecycle engine
- compare only runs from the same workflow family
- compare only runs with the same lifecycle engine
- use repo-owned workflow evidence plus attached artifacts
- do not recompute evaluation outputs during comparison
- local evidence remains canonical even when external trace backends exist

## Externalization Policy

External systems may mirror workflow evidence, but must map from this contract rather than replace
it.

Current supported optional overlays:

- self-hosted Opik for shared trace visibility
- Inspect AI with Claude Code for comparative external harness evaluation

Canonical policy for those overlays is recorded in
`docs/decisions/0010-local-first-workflow-quality-observability.md`.
