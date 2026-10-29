---
title: "264: Agent Workflow Quality Measurement and Evidence"
type: tempdoc
status: done
created: 2026-03-06
updated: 2026-03-07
---

> NOTE: Child tempdoc split out of tempdoc 262. This is the evidence-facing document for workflow quality measurement, coverage, and empirical findings.

# 264: Agent Workflow Quality Measurement and Evidence

## Purpose

Capture how workflow quality should be measured in this repo, what the current evidence sources can
and cannot tell us, and the highest-signal empirical findings from the existing agent-analytics
pipeline.

This doc preserves the measurement and evidence material that originally accumulated inside
tempdoc 262.

## Closure update (2026-03-07)

The scoped workflow-quality measurement contract in this doc is now implemented in repo code.

Implemented workflow-run metrics now include:

- `readyHttpMs`
- `indexServingMs`
- `portsClosed`
- `orphanProcessReaped`
- `staleArtifactCount`
- `parameterBindingErrorCount`
- `circuitBreakerDuringBaseline`
- `manualForensicsCount`
- `silentWindowMaxMs`
- `silentWindowCount`

Current comparison tooling and saved closure artifacts:

- comparison CLI: `scripts/bench/compare-workflow-runs.mjs`
- closure orchestrator: `scripts/bench/run-workflow-quality-closure.mjs`
- saved closure summary:
  - `tmp/workflow-quality-closure/2026-03-07T02-06-23-016Z/workflow-quality-closure-summary.json`

Important interpretation rule:

- `manualForensicsCount` is only populated when a run carries `session_id` and matching
  `tmp/agent-telemetry` events exist for the run window; otherwise it is `null`, not implicitly `0`

## Promoted canonical docs (2026-03-07)

The stable measurement outputs from this tempdoc now live in:

- workflow telemetry contract:
  `docs/reference/contracts/workflow-telemetry-contract.v1.md`
- operator runbook:
  `docs/how-to/validate-workflow-quality.md`
- complementary session-centric analytics explanation:
  `docs/explanation/21-agent-analytics-pipeline.md`

This tempdoc now remains as historical evidence/rationale for the scoped measurement program.

## Relationship to tempdoc 262

- Tempdoc `262-agent-workflow-quality-architecture.md` is the umbrella architecture/index doc.
- This tempdoc is the measurement/evidence child doc.

## How tempdoc 118 can be used here

### What it is good for

- It provides an existing agent telemetry pipeline (`scripts/agent-analytics/`) with event capture,
  per-session reports, cost estimation, and optional LLM-as-judge outcome labeling.
- It already tracks several friction patterns that overlap with this doc:
  large unbounded reads, bash file-op misuse, repeated tool calls, build loops, tool failures,
  compaction rereads, and session cost.
- Its `Agent Self-Critique` section adds repo-specific qualitative findings that are directly
  relevant here:
  context-window pressure, edit uniqueness friction, Windows shell mismatches, `spotlessApply`
  invalidating read state, and slow build loops.
- It contains an important strategic conclusion we should reuse: the highest-ROI layer is
  mechanical intervention on clearly bad patterns, not more elaborate composite scoring.

### What it is not good for

- It is not a reliable ranking function for "good workflow" because tempdoc 118 itself shows that
  PHI and its component signals do not predict task success.
- It is Claude Code-centric. The hook pipeline, transcript availability assumptions, and subagent
  coverage model do not automatically transfer to Codex or to other agent runtimes.
- It does not directly model the repo issues at the center of this work:
  stale `api-port.txt`, detached supervisor drift, preserved-state contamination, dynamic-port
  discovery, or backend/full-stack lifecycle family selection.
- Some of the current workflow pain sits below the hook layer entirely:
  background task identity loss, timeout cleanup failures, and supervisor state divergence are not
  first-class analytics dimensions in the 118 pipeline.

### Practical conclusion

- Yes, tempdoc 118 and the associated analytics pipeline should be used as one evidence source for
  workflow improvement.
- No, it should not be the primary decision framework for this effort.
- The right use is:
  1. use 118-style telemetry and outcome/cost analysis to quantify repeated agent behaviors
  2. use direct transcript review and repo code inspection to understand lifecycle/tooling failures
  3. keep process-hygiene metrics separate from workflow-correctness and success metrics
  4. prefer boolean flags, targeted counters, and interventions over any new composite workflow score

## Empirical findings from the agent-analytics pipeline

### Refresh run used for this doc

- Re-ran:
  - `node scripts/agent-analytics/analyze-session.mjs --all`
  - `node scripts/agent-analytics/score-session.mjs --all`
  - `node scripts/agent-analytics/generate-index.mjs`
- Reused existing `costs.ndjson` and `outcomes.ndjson`; I did not run `evaluate-session.mjs`
  because that is an on-demand external-judge path rather than a cheap local analysis pass.

### Coverage caveats

- Current score coverage: `103` scored sessions.
- Current usable outcome coverage from existing `outcomes.ndjson`: `60` scored sessions with a
  concrete completion label in the joined analysis; `65` usable outcomes total in the file.
- Missing outcome reasons in `outcomes.ndjson`: `no_transcript=16`, `trivial_session=28`,
  `judge_error=4`.
- Current nonzero cost coverage: `30` sessions.
- Raw command mining is limited by event rotation: command-level Bash analysis here used only the
  retained `events.ndjson` plus `events.ndjson.prev` slice, which currently covers `17`
  bash-active sessions.

### 1. Investigation work is where workflow friction shows up most clearly

- Across scored sessions by task type:
  - `investigation` (27 sessions): avg score `48.7`, avg duration `9.7h`, avg tool calls `616`,
    avg tool-failure rate `0.039`
  - `refactor` (14): avg score `63.6`, failure rate `0.013`
  - `feature` (9): avg score `70.0`, failure rate `0.017`
  - `implementation` (8): avg score `61.1`, failure rate `0.061`
- `55.6%` of investigation sessions score below `50`.
- `25.9%` of investigation sessions are flagged (`WASTEFUL` / `THRASHING` / `CONTEXT_PRESSURE`),
  versus `20.4%` of scored sessions overall.
- This directly supports treating investigation, evaluation, and diagnosis as a distinct workflow
  class for improvement work.

### 2. But the hygiene score still must not be mistaken for success

- Existing labeled outcomes for all scored sessions in the joined slice: `34 complete`,
  `24 partial`, `2 abandoned`, `0 failed`.
- Existing labeled outcomes for investigation sessions specifically: `20 complete`, `6 partial`,
  `1 abandoned`.
- So the pipeline confirms tempdoc 118's core warning: investigation sessions often look messy in
  process metrics while still completing successfully.

### 3. Dev-stack-heavy sessions are a distinct high-friction population

- Using `mcp__justsearch-dev__*` calls as a proxy for repo dev-stack lifecycle usage, and limiting
  comparison to scored non-trivial sessions (`score != null`, `tools >= 100`):
  - with MCP/dev stack usage: `33` sessions
  - without MCP/dev stack usage: `57` sessions
- Session averages for the MCP/dev-stack group:
  - `16.29h` vs `8.48h`
  - `1250` tool calls vs `572`
  - `29.0` failures vs `14.1`
  - `254.7` Bash calls vs `110.0`
  - `48.7` Bash file-op calls vs `20.5`
  - `57.2` subagents vs `24.1`
  - avg score `62.1` vs `66.4`
- Existing labeled outcomes for the subset with outcome labels:
  - with MCP/dev stack usage: `8 complete`, `12 partial`
  - without MCP/dev stack usage: `26 complete`, `12 partial`, `2 abandoned`
- That is the strongest empirical signal in this pass: dev-stack-heavy workflows do not just feel
  worse; they are associated with a materially higher partial rate in the labeled subset.

### 4. Failure burden is concentrated in long, compaction-heavy sessions

- Highest failure and failed-build burden in current reports:
  - `5bcc5324`: `152` failures, `42` failed builds, `13` compactions, `93` compaction rereads
  - `bdbf2360`: `108` failures, `55` failed builds, `4` compactions, `30` rereads
  - `e8a89f89`: `130` failures, `39` failed builds, `9` compactions, `87` rereads
  - `a72cbe2f`: `71` failures, `43` failed builds, `6` compactions, `52` rereads
  - `0ce68479`: `53` failures, `14` failed builds, `11` compactions, `106` rereads
- This reinforces tempdoc 118's self-critique on compaction pressure and build-loop overhead:
  the costly workflow sessions are also the ones repeatedly losing and reconstructing context.

### 5. Workflow-specific shell forensics are common in the retained raw event slice

- In retained raw events, `10/17` bash-active sessions (`58.8%`) contain at least one of:
  `gradlew`, `beir-eval-win.ps1`, `dev-runner`, or explicit process/port forensics
  (`Get-NetTCPConnection`, `taskkill`, `Stop-Process`, `Get-Process`, `api-port`, `netstat`).
- Across that raw slice there are `1522` such workflow hits in `7326` Bash calls.
- Most workflow-dense sessions in the retained slice:
  - `fb89b1f5`: `246` `gradlew`, `70` `beir-eval`, `100` port-forensics, `137` health polls
  - `34f6d05e`: `114` `gradlew`, `191` `dev-runner`, `147` port-forensics
  - `d7f7ed1b`: `32` `gradlew`, `46` `beir-eval`, `46` `dev-runner`, `40` port-forensics,
    `163` health polls
- This matters because it validates the trigger session for tempdoc 262 as part of a recurring
  behavioral pattern, not just a memorable one-off transcript.

### 6. Cost concentration also points at workflow-heavy sessions

- Nonzero cost coverage is sparse (`30` sessions), so this is directional only.
- Covered sessions total `$1783.96`; the top `10` sessions account for `84.0%` of that total.
- High-cost sessions in the covered set are often also workflow-heavy:
  - `67cd7060` (`feature`): `$323.38`, `166` MCP/dev-stack calls, flagged `THRASHING`
  - `4e320608` (`feature`): `$192.51`, flagged `THRASHING`
  - `4b40c154` (`unknown`): `$186.07`, `553` Bash calls
  - `4a0d7a16` (`investigation`): `$50.53`, `73` MCP/dev-stack calls, flagged `WASTEFUL`
  - `179f84b3` (`unknown`): `$73.13`, `68` MCP/dev-stack calls
- In the non-trivial scored subset with cost coverage, the MCP/dev-stack group averages
  `$158.33` per covered session versus `$35.46` without MCP/dev-stack usage, but note the small
  coverage counts (`8` vs `14`).

## Implementation-oriented measurement contract

### Correlation and join contract

#### Principle

- The measurement lane should treat workflow quality as run-centric first and session-centric
  second.
- A single agent session can drive multiple workflow runs, and a single workflow run can exist
  without any external agent session.

#### Canonical identities

- Required primary join key:
  - `workflowRunId`
- Required contextual keys when present:
  - `workflowFamily`
  - `engine`
  - `dataDir`
  - `dagRunId`
  - `dagStepId`
  - `apiPort`
- Secondary join keys:
  - `session_id` for Claude or Codex session telemetry
  - repo runtime `TraceContext.traceId` for in-app agent event chains
  - OTel `trace_id` for distributed trace correlation

#### Important non-equivalence rule

- These IDs should not be assumed identical:
  - `workflowRunId`
  - external `session_id`
  - runtime-agent `TraceContext.traceId`
  - OTel or W3C `trace_id`
- They may alias in some workflows, but measurement should record the relationship explicitly
  instead of depending on equality by convention.

#### Minimal event envelope for workflow-run telemetry

- Every workflow-run event should carry:
  - `schema_version`
  - `ts`
  - `event`
  - `workflowRunId`
  - `workflowFamily`
  - `engine`
- It should add contextual identifiers only when applicable:
  - `dagRunId`
  - `dagStepId`
  - `session_id`
  - `trace_id`
  - `dataDir`
  - `apiPort`

### Metric classes

#### Gating metrics

- These determine whether a workflow change is acceptable:
  - `workflow.start.ready_http_ms`
  - `workflow.stop.ports_closed`
  - `workflow.runtime.stale_artifact_detected`
  - `workflow.eval.parameter_binding_error`
  - `workflow.eval.circuit_breaker_during_baseline`

#### Descriptive metrics

- These help explain cost and friction but should not block changes on their own:
  - session duration
  - tool-call concentration
  - restart count
  - manual forensics count
  - silent-output windows
  - partial vs complete rate in labeled subsets

#### Exploratory metrics

- These are useful for research and prioritization, but should not be treated as correctness gates:
  - PHI and its component scores
  - compaction count
  - subagent count
  - MCP-usage proxy cohorts
  - Bash file-op counts

### Threats to validity

- Agent-vendor bias:
  - current hook telemetry is Claude-centric and does not automatically generalize to every agent
    runtime
- Run vs session mismatch:
  - today many measurements are grouped by `session_id`, while lifecycle reliability is often
    observed per workflow run
- Rotation and survivorship bias:
  - `events.ndjson` plus one `.prev` file is not a durable historical corpus
- Proxy limitations:
  - MCP usage is only an approximation of workflow class
  - cost coverage is incomplete
- Label quality:
  - judge-based outcome labels and transcript-derived costs depend on transcript availability and
    on consistent transcript retention

### Instrumentation budget

- Workflow-quality instrumentation should stay cheap enough that it does not become a new source of
  workflow distortion.
- Initial design constraints:
  - launcher and DAG events: synchronous local NDJSON writes are acceptable
  - runtime HTTP status snapshots: sample at workflow phase boundaries, not continuously
  - remote export: additive fan-out only, never the only path needed for correctness
- If instrumentation materially changes:
  - backend cold-start time
  - log volume
  - disk churn
  - or agent turnaround time
  then measurement must treat that as a regression, not as invisible overhead

### What this lane should measure

- Outcome metrics:
  - complete vs partial vs abandoned
  - session duration
  - tool-call concentration
  - cost concentration
- Workflow-overhead metrics:
  - backend start to `ready_http`
  - backend start to `indexServing` or workflow-specific readiness
  - restart count
  - stop cleanliness (`portsClosed`, orphan follow-up needed, stale runtime files)
  - fallback readiness detection rate
- Investigation-friction metrics:
  - manual process and port forensics frequency
  - quoting and parameter-binding failures
  - silent-output windows for long-running tasks
  - circuit-breaker failures during intended baseline runs

### Repo seams that already exist

- Lifecycle wrappers already emit machine-readable JSON envelopes:
  - `backend-launcher.mjs`
  - `dev-runner-lifecycle.mjs`
  - `eval-backend-lifecycle.mjs`
- Runtime truth already exists at `/api/status` and `/api/health`.
- Claude-side workflow telemetry already exists under `tmp/agent-telemetry/`.
- MCP tooling can already emit NDJSON under `tmp/dev-runner/justsearch-dev-mcp.ndjson`.

### Instrumentation gaps that block better measurement

- A repo-native workflow-run NDJSON stream now exists under `tmp/workflow-telemetry/runs/` and
  joins:
  - lifecycle wrapper events
  - DAG step boundaries
  - optional agent `session_id`
  - workflow artifacts such as manifests and logs
- The agent-analytics pipeline rotates `events.ndjson` to only one `.prev` file, which makes
  historical workflow forensics and stable cohorting fragile.
- The older agent-analytics schema remains session-centric, while the newer workflow layer is
  run-centric. The repo-local workflow layer now joins `runId`, `dataDir`, `engine`, and `apiPort`;
  crosswalking the older session-only analytics into that run-centric layer remains partial.
- Some high-value signals remain narrower than the ideal long-term model:
  `silent-output` windows and stop-cleanliness signals are now first-class fields, while richer
  `/api/status` snapshots and broader contamination signals remain future extensions.

### Historical implementation path for measurement

1. Keep the new workflow-run NDJSON stream as the canonical run-centric layer.
2. Continue using stable join keys:
   - `runId`
   - `dataDir`
   - `engine`
   - `apiPort`
   - DAG step name
   - session ID when the launcher is agent-driven
3. Keep recording phase timestamps rather than only final states.
4. Keep PHI and related scores as descriptive overlays, not as the primary gating metric.
5. Prefer cohort comparisons within workflow families instead of cross-family aggregate averages.

## Implemented measurement baseline in repo head

The following workflow-quality measurement pieces are now implemented:

- workflow-run schemas:
  - `workflow-run-meta.v1`
  - `workflow-run-event.v1`
- workflow event storage and loading in `scripts/lib/workflow-telemetry.mjs`
- DAG-level workflow telemetry emission
- lifecycle-envelope event extraction from adapter JSON logs
- optional MCP dev-server bridge into workflow-run events
- run comparison via `scripts/bench/compare-workflow-runs.mjs`

Current derived workflow metrics include:

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
- manifest decision and final-exit summaries when a manifest artifact is attached
- attached evidence paths for:
  - `manifest`
  - `summaryFile`
  - `outDir`
  - `outBaseDir`
  - metrics files and result dirs

`scripts/bench/compare-workflow-runs.mjs` now compares and renders the implemented gating metrics
plus the scoped descriptive metrics listed above.

## Practical implication from the empirical slice

- The repo should prioritize workflow improvements for:
  1. investigation and evaluation sessions
  2. dev-stack and MCP-backed sessions
  3. build-loop and compaction-heavy long sessions
- It should not use PHI or process score as the success measure for those improvements.
- The stronger outcome-oriented metrics available today are:
  - partial vs complete rate in labeled subsets
  - cost concentration
  - session length and tool-call concentration
  - frequency of workflow-specific forensics behavior in raw events

## Historical next metrics that were operationalized

- `workflow.start.ready_http_ms`
- `workflow.start.index_serving_ms`
- `workflow.start.direct_detection_used`
- `workflow.stop.ports_closed`
- `workflow.stop.orphan_process_reaped`
- `workflow.runtime.stale_artifact_detected`
- `workflow.eval.parameter_binding_error`
- `workflow.eval.circuit_breaker_during_baseline`
- `workflow.session.manual_forensics_count`
- `workflow.session.silent_window_max_ms`
- `workflow.session.silent_window_count`

All of the [scoped metrics above](#historical-next-metrics-that-were-operationalized) are now
operationalized in the workflow-run store.

## Status for this pass

- Closed in this pass:
  - the planned gating metrics for readiness, stop cleanliness, stale artifacts, parameter
    binding, and circuit-breaker detection
  - the scoped descriptive metrics for manual forensics and silent-output windows
  - run comparison for those gating and scoped descriptive metrics within a workflow family and
    lifecycle engine
  - evidence-path joins from wrapper-produced BEIR and ranking runs
- Still intentionally out of scope for this program:
  - broader descriptive and exploratory metrics beyond the implemented scoped set

## Post-2026-03-06 measurement baseline correction

Recent eval-hardening commits changed what "new measurement work" should mean in this repo.

What is already present in repo head:

- BEIR/search-eval now emits richer metrics and provenance through the hardened `metrics.v2` path.
- Requested-pipeline, qrels-summary, runtime-gate, and metric-contract fields are already captured
  in the BEIR lane.
- Search-eval readiness aggregation already exists in:
  - `scripts/bench/build-search-eval-readiness.mjs`
  - `scripts/bench/lib/search-eval-readiness-core.mjs`

Implication:

- The workflow-quality measurement lane should not try to recreate search-quality evaluation
  measurement from scratch.
- Instead it should add the missing workflow-run layer around the existing eval evidence:
  - launcher/run identity
  - step boundaries
  - cleanup outcomes
  - restart and stale-artifact signals
  - agent/session joins where available

## Critical assessment

### What this doc does well

- It is appropriately skeptical about the Process Hygiene Index and does not overclaim its value.
- It preserves the useful empirical slice from the agent-analytics pipeline without pretending that
  the pipeline alone explains workflow quality.
- The coverage caveats are explicit, which makes the conclusions more defensible than a score-only
  summary would be.

### Current weaknesses

- For current scope, this is no longer just an evidence snapshot; the run-centric measurement
  contract is implemented and exercised by saved direct and legacy closure runs.
- Several conclusions are directionally reasonable but still rest on sparse or proxy-heavy data:
  - MCP/dev-stack calls are only an approximation of workflow class
  - cost coverage is limited
  - raw Bash event mining is based on retained slices, not a full historical corpus
- Historical analytics caveats still apply to the older session-centric evidence, especially for
  retrospective claims derived from tempdoc 118 data.
- The implemented descriptive metrics are intentionally narrow; this doc does not claim exhaustive
  behavioral coverage for every future workflow question.

### What would improve this doc next

- Keep the canonical contract and runbook current as the implementation evolves.
- Expand descriptive metrics only when a concrete workflow program needs them; do not grow the
  metric set speculatively.
- Keep causal claims careful; the implemented comparison tooling supports workflow deltas, but it
  still does not turn historical correlations into proof of root cause.

## Working notes

- This doc is a child of tempdoc 262 and should stay focused on evidence, cohorting, and
  measurement semantics.
- Tactical fixes belong in tempdoc 263.
- External platform selection and long-term stack architecture belong in tempdoc 265.
