# Scripts Directory

Developer tooling, automation, and CI/CD scripts for JustSearch.

## Quick Start

**Most common tasks:**

```powershell
# Start local dev server (backend + frontend)
powershell -ExecutionPolicy Bypass -File scripts/dev/dev-all.ps1

# Check AI prerequisites (models, VRAM, llama-server)
node scripts/verify-prerequisites.mjs

# Run all tests
./gradlew test

# Validate documentation
node scripts/docs/docs-validate.mjs

# Run benchmark or evaluation suites
python -m jseval --help
```

## Choose Your Tool

| What you want to do | Script/Directory |
|---------------------|------------------|
| Start local dev server | `scripts/dev/dev-all.ps1` |
| Start API-only (no frontend) | `scripts/dev/run-headless-api.ps1` |
| Run benchmarks / eval | `python -m jseval` ([scripts/jseval/](jseval/)) |
| Check AI setup | `scripts/verify-prerequisites.mjs` |
| Run CI checks locally | `scripts/ci/` |
| Generate reliability budget report | `node scripts/ci/report-reliability-budget.mjs` |
| Validate documentation | `node scripts/docs/docs-validate.mjs` |
| Bootstrap dev environment | `scripts/setup/preflight.ps1` |
| Verify pre-merge checks | See [Pre-merge Checks](../docs/reference/contributing/pre-merge-checks.md) |

## Directory Guide

### Core Development

| Directory | Purpose |
|-----------|---------|
| `dev/` | Dev server orchestration, MCP server for Claude Code |
| `setup/` | Bootstrap scripts for Node + prerequisites |
| `ci/` | CI/CD automation (build, sign, package, smoke tests) |

### Benchmarking & Evaluation

| Directory | Purpose |
|-----------|---------|
| `jseval/` | Canonical benchmark + eval CLI (Python, Click-based, 45+ subcommands). Supersedes the prior `scripts/bench/` + `scripts/eval/` + `scripts/perf/` infrastructure deleted by commit `a9c484f59` (2026-03-16). |
| `bench/` | Manual hardware-perf utilities (`passmark-*`, `run-B1.ps1`, `ort-perf-probe.py`, etc.) — independent of jseval. |
| `search/` | Data-conversion utilities for BEIR / known-item datasets (the produced corpus files are committed; these scripts run rarely). |

### Quality & Validation

| Directory | Purpose |
|-----------|---------|
| `governance/` | Unified discipline-gate kernel (tempdoc 530): registry-driven gate runner, SARIF emitter, per-gate enforcers under `gates/<id>/`. Wire-evolution lives at `gates/wire/` (migrated from the prior `contract-governance/` kernel in Phase F). |
| `docs/` | Documentation validation, linting, transformation. |
| `evidence/` | EvidenceBundle validation and determinism checks. |
| `architecture/` | Dependency analysis (`module-deps.mjs`), IPC usage snapshot (`ipc-usage.mjs`). |
| `wire-contract/` | Buf workspace + npm-pinned buf binary for the wire protocol (slice 3a-1-8). |
| `agent-analytics/` | Maintainer session telemetry and analytics, session-to-merge links (`Session-Id`), merge recording, the `world-state.mjs` orientation command, and the dev-tool tests. |

### Specialized

| Directory | Purpose |
|-----------|---------|
| `ai/` | AI model packaging (`pack-author.ps1`). |
| `diagnostics/` | Inference diagnostic scenarios (`inference/`). |
| `test-support/` | Test utilities and fixtures. |

### Deployment / Ops

| Directory | Purpose |
|-----------|---------|
| `sandbox/` | Isolated environment setup docs. |
| `models/` | Model packaging and distribution helpers. |
| `prod/` | Production helpers. |

### Root Scripts

| Script | Purpose |
|--------|---------|
| `run-ui-local-llm.ps1` | Run UI with local LLM backend. |
| `verify-prerequisites.mjs` | Verify AI prerequisites (models, VRAM). |

There is no single "canonical gate" wrapper (slice 3a-1-8f §B.12 + §B.14, 2026-05-12). Pre-merge verification is per-subject — see [Pre-merge Checks](../docs/reference/contributing/pre-merge-checks.md).

## Prerequisites

- **Node.js** 24.x (see `scripts/setup/bootstrap-node-win.ps1`)
- **Java** 25 (Gradle will download if missing)
- **PowerShell** 7+ (for Windows scripts)
- **Python** 3.x (for jseval; install via `pip install -e scripts/jseval`)

## MCP Integration

`scripts/dev/justsearch-dev-mcp.mjs` provides Model Context Protocol integration for Claude Code (registered in `.mcp.json` as `justsearch-dev`).

The tool inventory is **not** repeated here — it is in
[docs/reference/contributing/mcp-dev-tools.md](../docs/reference/contributing/mcp-dev-tools.md),
which `scripts/ci/check-dev-mcp-doc-sync.mjs` asserts against the running server. (This list was one
of four forked inventories, all of them stale — tempdoc 844 §6.3.)

## Further Reading

- [jseval CLI surface](jseval/) — canonical benchmark + eval tool
- [Pre-merge Checks](../docs/reference/contributing/pre-merge-checks.md) — which local check to run, by edited subject
- [docs/explanation/09-testing-strategy.md](../docs/explanation/09-testing-strategy.md) — test pyramid + pre-merge checks
