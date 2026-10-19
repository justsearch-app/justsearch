---
title: Capture UI Evidence
type: how-to
status: stable
description: "Screenshot capture for UI verification and agent visual feedback."
---

# Capture UI Evidence

Capture UI screenshots **and a structured `<step>.measure.json` fact-sheet** for verification using the jseval
toolkit (tempdoc 615). The harness drives the **live Lit `shell-v0`** UI.

> **⚠️ Correction (2026-06, tempdoc 615):** the harness targets the live shell-v0 (not the retired React
> stack), and **`?demo=true` mock data is orphaned** — it supplies no data to the shell-v0 boot. So
> view/chrome steps need only a served frontend (the tool auto-serves Vite), but **search/inspector/AI steps
> need the dev stack running** (AI also needs `ai_activate`). Every capture also writes a `.measure.json`
> (accessibility tree + axe + geometry + console) — **judge correctness from those facts, the PNG for
> gestalt.**

## Single-Step Capture (Agent Feedback)

Capture a targeted screenshot during development:

```bash
cd scripts/jseval

# Capture one step (prints the PNG path + a one-line MEASURE fact summary)
python -m jseval ui-shot home
python -m jseval ui-shot search-results

# List all available steps
python -m jseval ui-shot --list

# Capture steps affected by a file edit (live shell-v0 paths)
python -m jseval ui-shot --affected modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.ts
```

Output: PNG path + the measurement fact summary to stdout; `<step>.measure.json` next to the PNG. Read the
JSON for facts; read the PNG for the overall look. `--no-measure` skips the companion.

In a worktree, `ui-shot` auto-starts a Vite dev server on port 5174 (no backend — data steps 502). Pass
`--ui-url http://127.0.0.1:5173` to drive a running dev stack instead.

## Batch Verification (CI Gate)

Capture all steps (`jseval ui-shot --list` for the current count) for pre-merge verification:

```bash
cd scripts/jseval

# Against a served frontend (view/chrome steps); data/AI steps need the dev stack
python -m jseval ui-check --ui-url http://127.0.0.1:5173

# JSON output (per-step measurement summary included)
python -m jseval --json ui-check
```

(`--no-demo` is legacy — the `?demo=true` mock path is orphaned; data comes from the live backend.)

Output: timestamped directory in `tmp/ui-check/<timestamp>/` with all PNGs
and `ui-eval.json` results file.  File-size baseline comparison detects
drift between runs.

## Prerequisites

**Demo mode (default):** Only a Vite dev server:

```bash
cd modules/ui-web && npm run dev:mock
```

**Real-backend mode:** Use jseval to start the backend:

```bash
cd scripts/jseval && python -m jseval dev --clean
```

## Key Files

| File | Purpose |
|------|---------|
| `scripts/jseval/jseval/ui_shot.py` | Single-step capture |
| `scripts/jseval/jseval/ui_check.py` | Batch capture (47 steps) |
| `scripts/jseval/jseval/ui_selectors.py` | Shared testid selectors |
| `scripts/jseval/jseval/ui_step_index.json` | File-to-step mapping |

## Known Limitations

- Playwright renders without compositor -- blur effects appear as flat surfaces
- 1x DPI only -- 2x exceeds Claude API's 2000px limit
- Static screenshots -- cannot evaluate transitions or animations
- `button-active` CDP pseudo-state step times out (marked non-required)
