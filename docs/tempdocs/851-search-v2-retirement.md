# 851 — Search v2 retirement: delete the window, sweep its fingerprints, arm the cutover gate

```
status: COMPLETE
created: 2026-08-19
owner-decisions: delete search-v2 un-promoted (owner, 2026-08-19)
closes: 818-search-v2-skeleton.md (superseded — never cut over)
```

## 1. The decision

Three chat/search windows existed at once: the shipped `UnifiedChatView`, `search-v2` (tempdoc
818), and `search-v3` (tempdoc 822). The owner decided on 2026-08-19 to **delete search-v2**.

It never became user-reachable. It was registered `DEVELOPER`/`DEEPLINK` from the day it landed,
and `plugin-api/CorePlugin.ts` said so in prose — *"it is not user-reachable until the §5
cutover"*. That cutover never happened: tempdoc 818 was still `status: ACTIVE` on the day of this
decision, with unchecked parity rows, four months after it was written. 7,652 LOC across 17 files
(9 modules + 8 test files) sat behind a deeplink nobody navigated to, while search-v3 — the
successor built on a new design system — went on being developed beside it.

Search v3 is promoted separately; that program is not this tempdoc's work. This tempdoc is the
deletion, the sweep, and the forcing function that stops the same shape from recurring.

## 2. What was deleted

`modules/ui-web/src/shell-v0/views/search-v2/` in full — 9 source modules (`SearchV2View.ts`,
`records.ts`, `askClient.ts`, `route.ts`, `railSizing.ts`, `deckSizing.ts`, `sessionBuckets.ts`,
`queryTrail.ts`) and their 8 test files. No file outside that directory imported anything from it:
search-v3 deliberately *mined* its patterns rather than importing them (822's charter guardrail),
which is why the delete is a clean cut rather than a refactor.

## 3. The sweep

`retire-with-a-sweep` requires every fingerprint deleted or labelled in the same PR. Patterns
swept: `search-v2`, `search_v2`, `SearchV2`, `sv2` (word-boundary), `jf-search-v2`,
`core.search-v2-surface`, and `Search v2` in prose.

| Hit | Action |
|---|---|
| `views/search-v2/**` (17 files) | **Deleted** |
| `plugin-api/CorePlugin.ts` — `core.search-v2-surface` registration | **Deleted**; a retirement note replaces it so a re-adder reads the decision |
| `views/lazySurfaceRegistry.ts` — `jf-search-v2` loader row | **Deleted** (nothing resolves that tag now) |
| `renderers/component-vocabulary.generated.ts` — `jf-search-v2` (2 hits) | **Regenerated** via `scripts/ci/gen-component-vocabulary.mjs` (141 → 140 components) |
| `messages/registry-surface.en.properties` — `search-v2-surface.{label,description}` | **Deleted** (en is the only locale file) |
| `governance/execution-surfaces.v1.json` — `sv2-window`, `sv2-records`, `sv2-ask-client` | **Deleted** (3 rows) |
| `governance/live-channels.v1.json` — search-v2 `askClient.ts` row | **Deleted**; the sv3 row's note re-worded (it described "three windows") |
| `governance/run-renderers.v1.json` — `SearchV2View.ts` mount site | **Deleted** |
| `governance/steering-surfaces.v1.json` — `SearchV2View.ts` adopter | **Deleted** |
| `governance/sandbox-coverage.v1.json` — `core.search-v2-surface` exempt row | **Deleted** (its own text said "or the surface is swept") |
| `views/search-v3/**` — 14 prose citations, several with dangling `file:line` | **Re-worded**: the design rationale is kept, the dead pointer removed, the window named as retired |
| `SearchV3View.pane.test.ts` — `not.toMatch(/search-v2|UnifiedChatView/)` | **Narrowed to `UnifiedChatView`** — the search-v2 half became vacuous the moment the module went away |
| `SearchV3View.ask.test.ts` — cross-import scan for `search-v2` | **Retargeted at `UnifiedChatView`** — the real guard (no window imports a sibling window) survives with a target that still exists |
| `components/chat/CitationsPanel.{ts,test.ts}`, `MarkdownBlock.test.ts` — "shipped consumers (search-v2, SummarizeView)" (7 hits) | **Corrected** to the actual consumer set (`UnifiedChatView`, `SummarizeView`) |
| `docs/tempdocs/818-*.md` | **Closed, not rewritten** — frontmatter set to `CLOSED — superseded`, plus a dated closure note. Append-only history stands |
| `docs/tempdocs/{734,821,836,838}-*.md`, `docs/observations.md` | **Left** — dated history (`tempdocs-are-dated-history`) |
| `.claude/skills/ui-check/SKILL.md:130` ("removed in the Search v2/v3 rewrite") | **Left, judged out of scope** — an era label about `SearchSurface.ts` / `InspectorPane.ts`, not a reference to this window |
| `model-registry.v2.json`, `docs/explanation/13-*.md` ("JustSearch v2") | **Left** — false positives, unrelated to the window |

Post-sweep grep for all patterns outside `docs/tempdocs/` and `docs/observations*` returns only
lines that explicitly name the window as retired.

## 4. The forcing function

`scripts/ci/check-window-cutover.mjs` (+ `check-window-cutover.test.mjs`, 20 assertions), wired
into `ci.yml`'s `public-claims` job next to its sibling `check-*` gates.

Two conditions:

- **(a) Reappearance.** `views/search-v2/` must stay deleted. Restoring it FAILS at any date. The
  window was retired by owner decision, not shelved.
- **(b) Cutover deadline.** The Search v3 promotion must be complete by **2026-11-30**. Before that
  date, incomplete WARNs (exit 0); on or after it, incomplete FAILS.
  *2026-09-30 owner decision:* the original 2026-09-30 deadline was moved to 2026-11-30. Main was
  frozen for lane F from 2026-09-08, so tempdoc 852 (S4 partial, S5-S11 pending) could not
  progress, and the failing gate blocked every PR through the required `Public claims` check.
  The new date follows the expected lane F merge; the gate still forces finish-or-retire.

"Complete" is structural, not attested — both must hold:

1. `core.search-v3-surface` is registered with `audience: 'USER'` in `CorePlugin.ts` (grep-based,
   with comments stripped first, so a commented-out registration cannot satisfy it), **and**
2. `governance/window-cutover.done` exists — a marker the promotion program creates deliberately,
   so an audience flip made as a side effect does not silently close the gate.

`--now YYYY-MM-DD` (or `JUSTSEARCH_CHECK_NOW`) overrides the clock so the post-deadline branch is
testable today; CI passes neither, so CI always evaluates the real date. Verified live in this
worktree: real clock → WARN + exit 0; `--now 2026-10-01` → FAIL + exit 1.

Why this gate and not a review habit: 818's window cost nothing to leave parked, so it stayed
parked for four months. A dated deadline is the cheapest thing that makes "still not promoted"
visible without anyone remembering to look. Moving the deadline is an owner decision, and the
failure message says so — that is the predictable evasion.

## 5. Verification

- `npm run typecheck` + `npm run test:unit:run` in `modules/ui-web` — green.
- `./gradlew.bat build -x test` — green.
- Governance gates for the registers touched — green (see PR body for the list).
- `node scripts/ci/check-workflow-triggers.mjs` after the `ci.yml` edit — OK.
- `node scripts/ci/check-window-cutover.{mjs,test.mjs}` in both date modes — as above.
