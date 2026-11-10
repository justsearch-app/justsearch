---
title: Capture UI Evidence
type: how-to
status: stable
description: "Screenshot capture for UI verification and agent visual feedback."
---

# Capture UI Evidence

Capture UI screenshots **and a structured `<step>.measure.json` fact-sheet** for verification using the jsharness
toolkit (tempdoc 615). The harness drives the **live Lit `shell-v0`** UI.

For deterministic data without a backend, use `ui-shot --fixtures`: the harness intercepts API
requests with fixtures and hides Getting started to preserve established captures. The
`first-use` and `first-use-reading` steps instead leave walkthrough progress unseeded, covering
an empty engine and files partly read. Use `--fixtures --no-demo` for those steps.
`?demo=true` alone supplies no data
to the current shell. Real integration checks need a running dev stack; AI checks also need an active
local model. Every capture writes a `.measure.json` (accessibility tree, axe, geometry, and console)
unless disabled; use those facts alongside the PNG.

## Single-Step Capture (Agent Feedback)

Capture a targeted screenshot during development:

```bash
cd scripts/jsharness

# Capture one step (prints the PNG path + a one-line MEASURE fact summary)
python -m jsharness ui-shot home
python -m jsharness ui-shot search-results --fixtures

# List all available steps
python -m jsharness ui-shot --list

# Capture steps affected by a file edit (live shell-v0 paths)
python -m jsharness ui-shot --affected modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.ts
```

Output: PNG path + the measurement fact summary to stdout; `<step>.measure.json` next to the PNG. Read the
JSON for facts; read the PNG for the overall look. `--no-measure` skips the companion.

In a worktree, `ui-shot` auto-starts a Vite dev server on port 5174 (no backend — data steps 502). Pass
`--fixtures` for data without a backend, or `--ui-url http://127.0.0.1:5173` to drive a running dev stack.

## Batch Verification (CI Gate)

Capture all steps (`jsharness ui-shot --list` for the current count) for pre-merge verification:

```bash
cd scripts/jsharness

# Against a served frontend (view/chrome steps); data/AI steps need the dev stack
python -m jsharness ui-check --ui-url http://127.0.0.1:5173

# JSON output (per-step measurement summary included)
python -m jsharness --json ui-check
```

(`--no-demo` is legacy — the `?demo=true` mock path is orphaned; data comes from the live backend.)

Output: timestamped directory in `tmp/ui-check/<timestamp>/` with all PNGs
and `ui-eval.json` results file.  File-size baseline comparison detects
drift between runs.

## Prerequisites

Use Python 3.11+ with the jsharness package and its `ui` extra installed
(`scripts/jsharness/pyproject.toml`), Playwright's Chromium browser, and the frontend npm dependencies.
From repo root in your Python environment:

```bash
python -m pip install -e 'scripts/jsharness[ui]' -c scripts/jsharness/requirements.lock.txt
python -m playwright install chromium
npm ci --prefix modules/ui-web --audit=false
```


**Layout-only browser work:** Start Vite without a backend:

```bash
cd modules/ui-web && npm run dev
```

**Deterministic data captures:** `python -m jsharness ui-shot search-results --fixtures` from
`scripts/jsharness` auto-serves Vite and installs request fixtures.

**Real-backend mode:** Use jseval to start the backend:

```bash
cd scripts/jseval && python -m jseval dev --clean
```

## Key Files

| File | Purpose |
|------|---------|
| `scripts/jsharness/jsharness/ui_shot.py` | Single-step capture |
| `scripts/jsharness/jsharness/ui_check.py` | Batch capture (`ui-shot --list` for current steps) |
| `scripts/jsharness/jsharness/ui_selectors.py` | Shared testid selectors |
| `scripts/jsharness/jsharness/ui_step_index.json` | File-to-step mapping |

## Known Limitations

- Playwright renders without compositor -- blur effects appear as flat surfaces
- 1x DPI only -- 2x exceeds Claude API's 2000px limit
- Static screenshots -- cannot evaluate transitions or animations
- `button-active` CDP pseudo-state step times out (marked non-required)
