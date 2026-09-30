---
title: "ADR-0009: Custom DAG Engine for CI Orchestration"
type: decision
status: superseded
description: "Build a custom TypeScript DAG engine for CI script orchestration instead of adopting an off-the-shelf tool. SUPERSEDED 2026-03-16 by commit a9c484f59; retirement completed 2026-05-12 per slice 3a-1-8f §B.14."
date: 2026-02-23
probes: none - superseded 2026-03-16; the custom DAG engine was retired (slice 3a-1-8f §B.14) and nothing it decided survives in code.
last_reviewed: 2026-09-02
---

# ADR-0009: Custom DAG Engine for CI Orchestration

## Status

**SUPERSEDED 2026-03-16.** The custom DAG engine (`scripts/lib/orchestration/`)
and the DAG-runner family it enabled (`scripts/ci/dag-runner-*.mjs`,
`scripts/bench/dag-runner-*.mjs`, etc.) were deleted by commit
`a9c484f59` ("refactor: remove legacy bench, eval, and shared lib
infrastructure"; 239 files, ~87,700 lines). The commit message records the
new posture: "All evaluation and benchmark capabilities are now in
`scripts/jseval/`." Several DAG runners and their wrappers were left
behind in the cleanup; slice 3a-1-8f §B.12–§B.14 (2026-05-12) completed
the retirement.

Original status preserved below as historical record of the original
decision context.

### Original status (2026-02-23)

Accepted

## Context

JustSearch's automated quality gating relies on 9+ PowerShell orchestrator scripts (~5,600 lines total) that share the same structural pattern: an imperative PS1 script hand-stitching a DAG of Node.js subprocess calls via disk-based JSON hand-off. These scripts have no dependency resolution, no parallel execution, no caching, no resume capability, and 3-5× duplication of core primitives (process tree kill, JSON I/O, dev-runner lifecycle, step execution).

The governance pipeline alone (`run-rr219-regression-pack-win.ps1`, 931 lines) executes 36 steps sequentially despite 21 being fully parallelizable. Cross-codebase analysis identified 12 scripts fitting this pattern across CI, benchmarks, resilience, and performance domains.

A detailed tool evaluation (see tempdoc 233 §Evidence-Based Tool Evaluation) assessed Go-Task, Nx, Turborepo, Gradle Worker API, Dagger, and Pants/Buck2 against JustSearch's hard constraints: Windows-native (no WSL, no Docker), local-first, and able to orchestrate predominantly Node.js scripts with occasional Gradle/PowerShell calls.

## Decision

Build a custom TypeScript DAG engine in `scripts/lib/orchestration/` using three npm devDependencies: `koffi` (Win32 Job Objects for crash-safe process tree containment), `p-limit` (concurrency limiter), `toposort` (topological sort with cycle detection). Each migrated script becomes a DAG runner that defines steps as pure data and invokes the shared engine.

The engine provides: parallel execution with configurable concurrency, dependency-ordered scheduling (Kahn's algorithm), continue-on-error with downstream failure propagation, `onStepComplete` callbacks for inter-step data flow, per-step timeout and log capture, graceful shutdown via `AbortSignal`, durable run state with UUID-based run IDs, and `--resume` idempotency via step-definition hashing.

## Consequences

- **~12,300 lines of Node.js** (including ~4,650 lines of tests) replace ~8,220 lines of PS1 (now thin wrappers, ~280 lines remaining). With 1,816 automated test checks where zero existed before.
- **3 new npm devDependencies** (`koffi` ~8MB prebuilt binaries, `p-limit` 2.6KB, `toposort` 2.1KB).
- **13 DAG runners implemented (historical, superseded)**, covering governance, gRPC resilience (soak + smoke), track-g, perf regression, BEIR gate, search-eval-rank, local agent gate, backfill report history, perf suite (inner), overnight benchmark autopilot, overnight RAG-AI queue, and agent live battery.
- **10 shared library modules** across `scripts/lib/orchestration/` and `scripts/lib/bench/`, eliminating 3-5× duplication of core primitives.
- **Maintenance estimated at 5-10 days/year** (Node.js upgrades, new steps, CI environment changes, cache correctness).
- **`onStepComplete` callbacks carry significant orchestration logic** for runners with inter-step data dependencies (artifact resolution, arg patching). This is imperative code inside a declarative framework — a known trade-off.

## Alternatives Considered

### Go-Task (strongest off-the-shelf option)

Runs arbitrary commands, MD5 checksum caching, parallel `deps:`. **Rejected because:** no Windows process tree cleanup (maintainer confirmed on issue #458 — Unix-only fix), POSIX-only shell via `mvdan/sh` (no inline PowerShell), no concurrency limit, v4 breaking changes incoming.

### Gradle Worker API

Already in codebase via `NodeScriptTask`. **Rejected because:** `--parallel` only parallelizes across subprojects, not within a single project (issue #9215, closed "not planned"). Per-step caching requires manual input-hash implementation. Kotlin ↔ Node.js boundary adds complexity. Low cross-codebase ROI — only benefits Gradle-invoked pipelines.

### Nx

`nx:run-commands` runs arbitrary commands. **Rejected because:** adds significant npm dependency weight for limited benefit over a TS engine, no native `onlyIf` predicate, 2024 pricing controversy (reversed) demonstrated vendor risk for self-hosted features.

Full evaluation details: tempdoc 233 §Evidence-Based Tool Evaluation.

## Post-Acceptance Implementation Addendum (2026-02-24)

This section is informational and does not alter the accepted decision text above.

- Terminology note: the accepted decision text uses "TypeScript DAG engine" as design-time wording; the delivered implementation is Node.js ESM (`.mjs`).
- The delivered orchestration implementation is Node.js ESM (`.mjs`) under `scripts/lib/orchestration/` and DAG runner entrypoints under `scripts/**/dag-runner-*.mjs`.
- Current runner inventory (13 total): 9 original pipeline runners (governance, gRPC smoke, gRPC soak, track-g, perf-regression, BEIR gate, search-eval-rank, local-agent-gate, backfill-report-history) plus 4 added post-acceptance: `scripts/perf/dag-runner-perf-suite.mjs` (inner perf suite, invoked by perf-regression step 02 — since removed: the `scripts/perf/` perf lane was retired in favour of `jseval`, tempdoc 638), `scripts/bench/overnight-benchmark-autopilot.mjs` (deadline-driven loop, per-cycle 3-step DAG), `scripts/bench/overnight-rag-ai-queue.mjs` (linear 15-step overnight queue), and `scripts/ci/dag-runner-agent-battery.mjs` (6-step agent live battery: start-backend → configure-model → activate-ai → ingest-corpus → run-scenarios → stop-backend).
- CI workflows for migrated lanes invoke DAG runners directly; legacy PowerShell scripts remain as compatibility wrappers where required.

Operational conventions for these runners follow the DAG-runner discipline described in this ADR; the runner scripts themselves live under `scripts/ci/`, `scripts/perf/`, and `scripts/bench/`.
