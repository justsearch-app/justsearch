---
name: ui-check
description: >-
  TRIGGER when: editing the Lit `shell-v0` frontend
  (`modules/ui-web/src/shell-v0/**`), modifying UI layout/styles, capturing UI
  screenshots, or doing visual/measurement verification of frontend changes.
  Loads the ui-shot/ui-check reference: the measurement companion
  (`.measure.json`), the live-shell-v0 step registry, server/backend
  requirements, the coverage gate, and worktree auto-serve.
---
# UI Check & Visual + Measurement Feedback

`jseval ui-shot <step>` captures the **live Lit `shell-v0` UI** and writes a PNG **plus** a structured
`<step>.measure.json` fact-sheet (tempdoc 615). The thesis is **measurement over vision**: judge correctness
from the *facts* (accessibility tree, axe violations, geometry, console), and use the PNG only for the overall
look.

> The harness drives the real Lit app. Live data/AI steps need the owned dev stack
> and an online model. Deterministic state steps use explicit `--fixtures` route
> responses; these prove UI rendering, not backend or model behavior. A step with
> a non-default fixture variant (for example `chat-chip-yield`) requires that flag.
> The retired `?demo=true` mode is unrelated and remains inert.
>
> A mount failure naming `504 /node_modules/.vite/deps/...` is a failed Vite
> optimized dependency, not evidence that backend readiness or a UI selector is
> wrong. Use the owned stack lifecycle to restart/reoptimize, verify the current
> served dependency returns200, then repeat the unchanged capture. Preserve the
> failed evidence; do not hide it with fixtures or a longer mount timeout.

## Quick reference

```bash
jseval ui-shot home              # capture one step → prints PNG path + a one-line MEASURE fact summary
jseval ui-shot search-results
jseval ui-shot citation-highlight
jseval ui-shot --list            # list all steps
jseval ui-shot --affected modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.ts
jseval ui-shot --affected modules/ui-web/src/shell-v0/components/StatusDeck.ts --fixtures
jseval ui-check                  # batch-capture all steps (~60s+), diff vs baseline
```

The CLI prints the measurement facts after each capture, e.g.:
```
tmp/ui-shot/home.png
  measure: tmp/ui-shot/home.measure.json
  a11y 63 landmarks · geometry 7 els · axe 1 violations (1 serious) · console 0 errors · overflow none
```
**Read the `.measure.json` for a correctness judgment from facts; `Read` the PNG only for gestalt.** A PNG eats
~1–2k tokens of context; the fact summary is cheaper and more reliable for "is this right."

### The measurement companion (`<step>.measure.json`) — tempdoc 615 §6.2
Default-on (`--no-measure` to skip). Captured from the same page the screenshot comes from:
- **a11y_landmarks** — shadow-piercing accessibility roles/labels/headings (the live perception channel; the
  native `page.accessibility.snapshot()` returns None on this shadow-DOM app, so a deep `shadowRoot` walk is used)
- **axe** — WCAG violations (the same axe-core bundle the e2e harness ships)
- **geometry** — bounding rects + key computed styles for landmarks/stage/rail/inspector + document overflow flags
- **console_errors** — console.error / pageerror collected over the step

### Common options
| Flag | Default | Notes |
|---|---|---|
| `--ui-url` | `http://localhost:5173` | a **non-default** value (e.g. `http://127.0.0.1:5173`) bypasses worktree auto-serve and hits that server directly — use this to target a running dev stack |
| `--no-measure` | off | skip the measurement companion (PNG only) |
| `--timeout-ms` | 30000 | per-step timeout; AI/citation steps need ~200000–320000 (model latency) |
| `--cooldown-ms` | 250 | settle time before each screenshot |
| `--output-dir` | `tmp/ui-shot/` | |

(`--no-demo` is legacy — the `?demo=true` mock-data branch is inert; data always comes from the live backend.)

## The seven instruments — which verb when (tempdoc 615 §11; proportion added by 697)

`ui-shot` is the one you reach for most, but it is one of **seven** verbs. Each answers a different question;
the rot they were built to avoid is being forgotten (§26 discoverability). Pick by the question you have:

| Question you have | Verb | Needs | Output / exit |
|---|---|---|---|
| "Show me / let me verify ONE surface" | `jseval ui-shot <step>` (+`.measure.json`) | served FE (auto-serve); dev stack for data/AI steps | PNG + measure.json + 1-line fact summary |
| "Did I break a11y closure anywhere?" | `jseval ui-a11y-gate` | auto-serve (`--fixtures`) | exit 0 clean · 1 a NEW axe violation vs `governance/ui-a11y-baseline.v1.json` · 2 capture error |
| "Did persistent chrome GROW taller than it was?" | `jseval ui-proportion-gate` | auto-serve (`--fixtures`) | exit 0 clean · 1 a registered element grew beyond `governance/ui-proportion-baseline.v1.json` · 2 capture error / selector not found |
| "Did this change MOVE/REMOVE anything I didn't intend?" | `jseval ui-diff <before.measure.json> <after.measure.json>` | two captures (shoot before, edit, shoot after) | semantic changelog (landmark removed · element moved/resized >4px · new axe rule · overflow flip · real console); exit 0 same · 1 changed |
| "Critique this surface against THIS product's design system" | `jseval ui-critic <step>` | auto-serve (`--fixtures`) | prints a GROUNDED critique **prompt** (facts + `design-reference.v1.json` + rubric) — feed it to a model |
| "Hunt edge-state bugs a human won't patiently click" | `jseval ui-fuzz` | auto-serve (`--fixtures`); ~80s | fuzzes search × {data-variant × viewport × theme}, flags anomalous cells; exit 0 clean · 1 flagged |
| "Trace the interaction trajectory into a step" | `jseval ui-shot <step> --trace` | served FE | per-step trace of the chain leading to `<step>` (limited to existing harness chain steps) |

Rules of thumb: judge correctness from `ui-shot`'s `.measure.json` facts (cheap, reliable), not the PNG;
gate a11y regressions with `ui-a11y-gate` (also runs on PRs as the ADVISORY `Measured axe` job in
`ci.yml` — advisory means non-blocking, so still run it locally); use `ui-diff`
when deliberately iterating on one surface; `ui-critic`/`ui-fuzz` are deeper, situational passes. After
editing a `shell-v0` file, run `jseval ui-shot --affected <file>` to find the contextually-relevant step(s).

## Instrumented assertions vs interactive inspection <!-- rule:harness-for-assertions -->

> **Use the instrumented harness for anything you will assert on. Use the browser for things you are
> only looking at.**

An assertion taken from a screenshot is not reproducible and cannot be gated; `ui-shot`'s
`.measure.json` is both, and `ui-diff`/`ui-a11y-gate`/`ui-proportion-gate` turn it into an exit code.
So "the spacing looks wrong" is a browser observation, but *"the header grew 12px"* is a
`ui-proportion-gate` claim — and only the second one survives review or catches a regression later.

Where the browser is the right tool and this rule does not apply: external design research, an
unfamiliar third-party flow, reading console/network output during a live debug, or anything with no
harness step. Those are genuine uses — the rule steers *local dev-UI verification*, not exploration.

This guidance lives in the UI skill because its trigger (capturing or judging UI
screenshots) fires at the moment the agent must choose between exploratory
inspection and reproducible evidence. Interactive browser or computer-use tools
are appropriate for exploration; claims used for review or regression decisions
must come from the instrumented harness.

## Server & data requirements (there is NO mock data)
| Step kind | Needs | Notes |
|---|---|---|
| Views / chrome — `home`, `library`, `settings`, `health`, `help`, `ai-brain` | a served frontend only | the tool auto-serves Vite; renders chrome + empty surfaces with no backend |
| Search / inspector — `search-results`, `inspector-open`, `multi-select`, `context-menu`, `filters-chips` | dev stack (worker) | search returns real data |
| AI chain — `streaming`, `summarize-done`, `qa-response`, `citation-highlight` | dev stack + `ai_activate` | dev stacks default to the compact chat profile (tempdoc 842) — fine for shape/rendering checks; latency-/GPU-contention-sensitive — may need a retry on a freshly-activated model. Judging answer *quality/wording* (not just that the surface renders) needs `ai_activate {chatProfile:"standard"}` first. |

The live shell lands on the **chat** surface by default; every view step navigates to its rail surface first,
and the harness's app-ready signal is the **rail** (not `search-input`).

## The agent feedback loop
After editing `modules/ui-web/src/shell-v0/**`, run `jseval ui-shot --affected <file>` to find the
affected steps (from `ui_step_index.json`), then `jseval ui-shot <step>`, then read its `.measure.json`
(facts) and/or the PNG (gestalt).

## Coverage + freshness gate — tempdoc 615 §6.1a
`node scripts/ci/check-ui-step-coverage.mjs` (register `governance/ui-step-coverage.v1.json`; wired in CI and
the shared pre-merge guidance) keeps the harness honest: every source path the step index maps MUST resolve on
disk (a deleted/renamed file is a build failure — this is what stops the index silently rotting back to dead
code, as it did against the retired React stack), and every `placement:'RAIL'` surface in `CORE_SURFACES` must
have a covering view step or a declared exemption. Run it after editing `shell-v0/**` or the harness.

## Worktree auto-serve
In a worktree, `ui-shot` auto-starts its own Vite on :5174 (a `node_modules` junction to the main checkout),
persisted in `tmp/ui-shot-server.json` and recorded in the tempdoc-861 agent-spawn registry
(`tmp/dev-runner/agent-spawns/`) — that registry is the reaping authority, **not** a SessionEnd hook:
the never-wired `ui-shot-cleanup.mjs` (tempdoc 861 §3) was deleted in 861 Phase 7. That
auto-served Vite has **no backend** (data steps will show 502s) — to drive a running dev stack instead, pass
`--ui-url http://127.0.0.1:5173` (a non-`localhost:5173` string bypasses the auto-serve).

## Step registry
53 steps (`ui_check.py`; run `jseval ui-shot --list` for the authoritative set — this count
drifts as steps are added). **Chain** (shared browser, `depends_on`): `search-results` → {`filters-chips`, `inspector-open`,
`multi-select`, `context-menu`}; `inspector-open` → `streaming` → `summarize-done` → `citation-highlight`.
**Isolated** (own browser, parallel): the view steps (dark+light), density/mode variants (`search-results-*`,
`search-*-mode`), CDP pseudo-states (`row-hover`, `input-focus`), and the `shell-v0-demo*` / `presentation-demo*`
standalone demos. Run `jseval ui-shot --list` for the authoritative set.

## File-to-step index (live shell-v0)
`scripts/jseval/jseval/ui_step_index.json` maps **38 source files** to steps (gated by
check-ui-step-coverage). The JSON is the authority — read it rather than trusting this
excerpt; the highest-fan-out entries are:

| File | Steps |
|---|---|
| `shell-v0/components/searchResults/ResultsCard.ts` | search-results, command-mode, zero-results, input-focus, filters-chips, search-simple-mode, search-advanced-mode, … |
| `shell-v0/chrome/Shell.ts` · `shell-v0/plugin-api/CorePlugin.ts` | all view/nav steps (home, library, ai-brain, health, settings, security, help) |
| `shell-v0/views/UnifiedChatView.ts` | chat-mode, qa-response, chat-proportion, chat-bands, chat-composer-small, chat-spine-single, … |
| `shell-v0/views/unifiedChatStyles.ts` · `shell-v0/primitives/compositionLayout.ts` | the chat band/spine/occlusion steps |
| `shell-v0/views/search-v3/{Sv3Main,sv3-tokens.css}.ts` | sv3-citation-selected + the search-v3 surface steps |
| `shell-v0/views/SummarizeView.ts` | streaming, summarize-done, qa-response |
| `shell-v0/components/chat/{MarkdownBlock,CitationsPanel}.ts` | citation-highlight |
| `shell-v0/components/ContextMenu.ts` | context-menu |
| `shell-v0/views/{HealthSurface,LibrarySurface,BrainSurface,SettingsSurface,HelpSurface,SecuritySurface}.ts` | the matching view step (+ its light/variant steps) |

> The pre-search-v3 entries `views/SearchSurface.ts` and `components/InspectorPane.ts` are
> **gone** — those files were removed in the Search v2/v3 rewrite. Their steps now resolve
> through `ResultsCard.ts` and `DocumentPane.ts`/`SummarizeView.ts` respectively.

## Key files
| File | Purpose |
|---|---|
| `scripts/jseval/jseval/ui_shot.py` | single-step capture, auto-serve, `--affected` |
| `scripts/jseval/jseval/ui_check.py` | batch capture + the step registry |
| `scripts/jseval/jseval/ui_measure.py` | the measurement companion (a11y/axe/geometry/console) |
| `scripts/jseval/jseval/ui_selectors.py` | live shell-v0 selector constants (role/testid/surface-id) |
| `scripts/jseval/jseval/ui_step_index.json` | file→step map (gated) |
| `scripts/ci/check-ui-step-coverage.mjs` + `governance/ui-step-coverage.v1.json` | coverage/freshness gate |

## Known limitations
- **No mock-data mode** — data/AI steps need the live dev stack (+ `ai_activate` for AI).
- **AI legs are latency-/GPU-contention-sensitive** — whichever chat model is active (compact by default in dev, tempdoc 842) can unload under VRAM pressure; re-activate and retry.
- **Glassmorphism**: Playwright renders without a compositor — blur appears flat.
- **DPI**: 1× only (2× exceeds the API's 2000px image cap).
- **Motion**: static frames can't show transitions — assert motion *structurally* (the `--duration-*` / `--ease-*` CSS tokens) rather than watching it.

## Reliable screenshot capture (Playwright script, not manual/extension)

Two methods look plausible and fail silently. Don't use them:

- **Manual "prepare window, ask user to screenshot" workflow is unreliable.** Verified once: ~15 "READY — capture X" requests produced zero saved files. Never trust "I've taken a screenshot" — verify the file exists at the expected path (`find <dir> -newer <marker>` or equivalent) before reporting success.
- **Interactive window resizing is not reliable viewport control.** Window snap, focus contention, or browser chrome can produce misleadingly same-size "wide"/"narrow" pairs. Do not trust it for before/after comparisons.

**Use a standalone Playwright script instead**, written to a scratch dir — do NOT modify the shared `scripts/jseval/jseval/ui_check.py`. Launch headless Chromium, set an exact viewport, and capture full-page:

```python
page.set_viewport_size({"width": 1568, "height": 900})  # wide
# page.set_viewport_size({"width": 840, "height": 900})  # narrow
page.goto(url)
page.screenshot(path="out.png", full_page=True)
```

1568 (wide) and 840 (narrow) are known-good widths for this app — no responsive breakpoint observed between them. Always pass `full_page=True`: the shared `ui_check.py` defaults to `full_page=False` (viewport-only, clips content) — fine for its own step registry but not for ad-hoc captures. This saves directly to a real file, unattended, no user round-trip.

**Gotchas:**
- `data-testid` locators (`page.locator('[data-testid="..."]')`) often return 0 matches against this app's Lit shadow-DOM components, even though Playwright generally pierces open shadow roots. Fall back to tag/role selectors: `page.locator("textarea")`, `page.get_by_role("textbox")`.
- The app persists "last-open Inspector doc" client-side; a same-context `page.goto()` reload can leave a `<jf-document-pane>` overlay intercepting clicks on the next state. Give each captured state its own fresh `browser.new_context()` — don't reuse a page across states.
- The dev stack auto-stops after several minutes of inactivity between tool calls. Check `quick_health` before a long capture batch; restart with `clean: "none"` if it died (preserves index/data).
- Headless vs headed showed no visible backdrop-blur/glass difference on pixel diff for this app — headless is sufficient, don't switch to headed for that reason alone.
