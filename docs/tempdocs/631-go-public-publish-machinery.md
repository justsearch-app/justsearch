---
title: "Go-public (Option C) — publish the agent/governance machinery, public-safe: tempdoc scrub + content audit, the publish/exclude split (machinery-code public, runtime config/data private), sidecar-gate decoupling (skip-when-absent), and the public CI runner-split (fork-PRs → hosted lane only; GPU/perf suite → maintainer pushes). Workstream 1 of 4."
type: tempdoc
status: "implemented + committed to `main` (2026-06-24) — agent-side complete: the tempdoc scrub/scan, the publish/exclude split, the guards-only public `settings.json` template (`gen-agent-hooks-wiring --emit-public-template`), MAINTAINING.md + the signpost READMEs. The flip-gated remainder (the live `settings.json` swap, the public-CI re-architecture, the `CLAUDE.md` slim, the snapshot include-list closure, the pre-snapshot founder decisions) is the single registry in tempdoc 634. Strategic charter + the Option-C work program: the private sidecar (legal/go-public-readiness.md)."
created: 2026-06-22
updated: 2026-06-24
related:
  - 632-go-public-licensing-legal       # parallel; the license files the snapshot needs
  - 633-go-public-launch-content         # parallel; the public-facing assets
  - 634-go-public-cutover-transition     # the execution; gates on this
---

# Go-public (Option C): publish the agent/governance machinery, public-safe

## Context
Under **Option C (radical transparency)** JustSearch develops *in the open*, with the agent/governance
machinery published — both because develop-in-public **needs** it present (no guardrails otherwise) and because
the AI-agent-discipline system is itself a **credibility showcase**. This workstream makes that meta-layer
public-safe. (Why Option C, and the A/B/C deliberation: the private business sidecar.)

## Scope (the meta-layer core)
1. **Tempdoc scrub + content audit** `[agent][live]`
   - Codemod `C:\Users\Elias` → `C:\Users\<user>` across the **15 tracked files / 36 occ** (incl. canonical
     `docs/how-to/spawn-isolated-test-backend.md`, `docs/decisions/0028-…`).
   - **Candor/sensitivity pass** over the 388 tempdocs + `observations.md`. **Re-scoped (2026-06-23):** the
     README rewrite (1c, below) frames *every* tempdoc wholesale as dated / outdated-by-design / not-current-truth
     / **not product claims** — so the subjective worries (frank self-critique like "the wire lies"; dated numbers
     like 500's "94%") are now covered by blanket framing and need **no per-doc softening or caveating**. What
     remains is only what framing *cannot* neutralize → an objective, mostly-mechanical scan (the G7 regex sweep,
     now the whole of this item, not just a triage front-end): **(i) real PII/secrets** (founder phone/email,
     tokens — a framing note doesn't make a leaked phone OK), **(ii) third-party-sensitive content** (candid
     statements *about other people/vendors/competitors* — self-critique is framed-safe, statements about others
     are not). Human review only the flagged hits. *(The stale-number caveating I'd proposed survives only for
     non-tempdoc public surfaces — README/benchmark claims — which is 633's domain, not here.)*
   - ~~Add~~ **Rewrite** `docs/tempdocs/README.md` — **✅ DONE (2026-06-23).** Rewritten around the correct
     mental model; delivered the public candor framing; removed the dead `docs/business/` link (G4); and
     incidentally resolves `observations.md` #436/#536/#543 (the stale unenforced status-vocabulary). **Model:** tempdocs are *temporary, dated artifacts, **outdated by design*** — a low number ≈
     early/stale; they are kept **only** as a development timeline / design archaeology (early considerations,
     what was tried, evolution), never maintained, never current. Confirmed: number ≈ creation order (257=Mar-04,
     500=May-16, 588=Jun-15). The existing 140-line README mis-models this as a *living notebook* and is largely
     dead/wrong content for both new devs/agents **and** the public:
     - **Cut** the status-values table (prescribes a 6-value vocab the next paragraph admits the retired validator
       proved nobody uses — `implemented`/`investigated`/`charter` are used freely; 588's status is a prose blob);
       the **staleness "30+ days → review/resume"** ritual (contradicts outdated-by-design, not practiced); the
       `/13/` draft-spec-packet special case (obscure).
     - **Soften** "Promotion **required** at terminal state" → unenforced norm (current truth lives in canonical
       docs; the tempdoc stays as rationale). **Fix** the **dead `docs/business/` link** (line 77 — also a G4 hit).
     - **Keep + tighten** the noncanonical/verify-against-canonical core, cite-by-slug-not-number (+ renumber
       procedure), and multi-part clusters (`249-*`).
     - **The payoff:** a "How to read a tempdoc" section built on this model (not current truth → go to canonical
       docs/code; the date & roughly-chronological number are the context; candid language + dated numbers are
       working-note nature, **not product claims**) **IS** the public candor-framing preamble. So **item 1c
       collapses into this single rewrite** — no separate framing doc; it fixes the internal new-dev/agent
       confusion, removes wrong/dead content, and delivers the public framing in one stroke.
2. **Publish / exclude split** `[agent][live]`
   - **Publish:** `scripts/agent-analytics/hooks/` (code), `governance/`, `gates/`, `.claude/{rules,skills,CLAUDE.md}`,
     `governance/agent-hooks.v1.json` (the hook-wiring manifest).
   - **Exclude:** `git rm --cached .claude/settings.local.json .mcp.json`; ensure `tmp/` + the agent-telemetry
     **data** + `.claude/worktrees/` are gitignored.
   - **Generate a public `.claude/settings.json` *template*** from the wiring manifest — *not* the local
     `settings.local.json` (it advertises `bypassPermissions` / `Bash(*)`).
3. **Sidecar-gate decouple** `[agent][live]` *(read-only audit first — safe to start now)*
   - Audit `governance/registry.v1.json` + `scripts/{ci,governance}/` for any gate that reads `docs/business/`
     or `docs/market-analysis/` → make it **skip-when-absent**.
   - **Design the local-vs-CI duality:** the sidecar is *present locally* (gate runs) but *absent in CI* (gate
     skips). Get this wrong and CI is red on day one, publicly.
4. **Public CI re-architecture** `[agent][live]` + `[founder]` for the repo setting
   — **DEFERRED to the public repo (2026-06-23 founder decision); effectively moves to 634.**
   *Rationale:* the manual-only + self-hosted setup (ADR-0026) exists **because private repos have a capped pool
   of free Actions minutes** while **public repos get unlimited free GitHub-hosted minutes** — so the hosted lane
   only *becomes free* once public, and the **fork-PR-approval setting + the public self-hosted-runner
   registration cannot exist until the public repo does.** Adapting `ci.yml` on the private snapshot would be
   editing blind (untestable pre-flip). So this stays as the *target design* but is **executed in the public repo**
   as part of 634's "public-but-unannounced staging / green-on-both-lanes" gate. The skeleton is already drafted
   (`cutover-package/public-ci.yml`).
   - **Runner split:** fork-PRs run the **hosted lane only**; the **GPU/perf gate suite runs only on
     push-to-`main` + approved maintainers** — never run unapproved fork code on the self-hosted runner.
   - Triggers (push/PR vs. manual), the **fork-PR-approval** repo setting, absent-model-blob tolerance, and the
     sidecar-gate skips from #3. Largely **adapts the existing `ci.yml`** (its trees are public now), not a rewrite.
5. **Secret/sensitive scan of the newly-public machinery** `[agent]` — bigger surface than the earlier code
   scan: agent-analytics, `.claude`, tempdocs, governance. Tokens / internal URLs / paths / candid content.

## Done
The machinery builds and the discipline-gate suite is **green under the public-repo conditions** (sidecar + model
blobs + `settings.local.json` absent, public `settings.json` swapped in); the hook wiring is relocated into a
public `settings.json` template (F3) so the published guards are *live*, not inert; and no secrets / PII /
machine-paths / machine-usernames remain in the published meta-layer.
*(Honesty correction 2026-06-23, independent-review #1: the green-under-public-conditions claim is **reasoned
component-by-component** (no gate reads the sidecar — F2; model-loading tests self-skip; the template builds;
local `--check` byte-identical), **not yet demonstrated by an end-to-end run** in a disposable clone with the
excludes applied + template swapped. That integrated run — and the "green on both **CI lanes**" — is **deferred to
634**, where the template swap + regen-gate repoint actually happen; it cannot be honestly asserted satisfied at
631.)*

## Dependencies
Parallel with 632 + 633. **Blocks 634** (the cutover) — the machinery must be public-safe before the flip.
*(Optional split if 631 proves too big: 631a content-prep [#1–2,5] / 631b CI-&-gates [#3–4].)*
**Split caveat (verified 2026-06-22, §Investigation F3/F4):** that split line fractures the load-bearing coupling
— item #2's public `settings.json` *hook-wiring* relocation is the same work as item #4's regen-gate repoint. If
split, keep #2 **and** #4 together; #3 is near-empty (see F2) so its placement is moot.

---

## Investigation / pre-implementation findings (2026-06-22, takeover pass — verified against `main`)

Read-only audit + experiments against the live tree. Each finding cites primary-source evidence; corrections to
the scope numbers above are noted so the implementation works from real counts, not the planning estimates.
**Two findings (F3) materially change the plan** — flagged ★.

### F1 — Item #1 scrub surface is larger than "15 files / 36 occ", and crosses the doc/script boundary
- `rg "C:\\Users\\Elias"` → **41 occurrences across 18 files** (vs. the tempdoc's 15/36). Of the 18: one is the
  **untracked** sidecar `docs/business/legal/go-public-readiness.md` (won't publish) and one is this tempdoc's own
  meta-reference — so ~**16–17 tracked real-occurrence files** to scrub.
- **New category the codemod scope misses:** `scripts/bench/lhm-log.ps1:1` carries the path — a **PowerShell
  script**, not a doc. The tempdoc frames the codemod as `docs/**` (+2 canonical docs); broaden it to a
  **repo-wide tracked-file** pass (e.g. `git grep -lE 'Users.Elias'` — note `git grep -F` misfired on the literal;
  use the regex form) or the `.ps1` slips through.
- The two named canonical docs are confirmed present: `docs/how-to/spawn-isolated-test-backend.md:139`,
  `docs/decisions/0028-scoped-reverse-path-lookup.md`. Heavy doc hotspots: `374` (×10), `390`/`392` (×5 each).
- **Candor surface correction:** **388 tempdocs** today (not 391) + `docs/observations.md` (tracked, 1 file).

### F2 — Item #3 ("sidecar-gate decouple") is **near-empty** — the readiness doc's R1 fear is overstated
- **No gate, check, or CI step reads `docs/business/` or `docs/market-analysis/`.** Verified across
  `governance/`, `gates/`, `scripts/{ci,governance,agent-analytics}/`, the registry, and `ci.yml`; the only
  `business`/`market-analysis` string hits are **search-corpus query terms** (`scripts/jseval/.../qu_spike.py`,
  `scripts/prod/justsearch-mcp/schemas.mjs`) — false positives.
- The discipline gates read `docs/tempdocs/`, `.claude/rules`, `scripts/agent-analytics/hooks` — **all PUBLISHED
  under Option C**, so they don't break when the sidecar is absent. `docs/llms.txt` and `llmstxt-generate.mjs`
  enumerate only the 5 canonical doc dirs, never the sidecar.
- **Implication:** item #3's "make sidecar-reading gates skip-when-absent" finds essentially nothing to decouple.
  Re-scope #3 to a *one-shot verification* ("prove no gate reads the sidecar; add a defensive test if cheap") and
  redirect the energy to F3, where the real day-one-red risk lives.

### F3 — ★ The real day-one-red risk: **the hook wiring lives in the file item #2 removes**
This is the finding the plan under-weights. The Option-C rationale is "develop-in-public *needs* the machinery
present" — but the machinery is wired in the wrong file:
- **All 30 discipline-hook wirings live only in `.claude/settings.local.json`** (`grep -c '"hooks"'` → 30). That
  file carries `"Bash(*)"` + `"defaultMode":"bypassPermissions"` (lines 5, 16) → item #2 correctly `git rm
  --cached`s it.
- The **tracked, public-safe `.claude/settings.json` exists but has NO hooks block** (worktree/MCP/plugin config
  only; no dangerous perms). So **publishing as-is leaves every discipline hook UNWIRED in the public repo** —
  the guardrails the showcase advertises wouldn't actually fire. Item #2's "generate a public `settings.json`
  template from the wiring manifest" is therefore **load-bearing, not cosmetic** — it's what keeps the published
  machinery *live*.
- **The regen gate breaks too, in two layers.** `check-agent-hooks-wiring-regen` (`ci.yml:294`, **no `if`
  guard**) runs `gen-agent-hooks-wiring.mjs`, which (a) **unconditionally** `readFileSync`s
  `.claude/settings.local.json` at **line 97** → `ENOENT` crash when absent; and (b) even if line 97 is guarded,
  its check (`before = existsSync(SETTINGS) ? … : null` at line 101, then `before !== content` at 102) **still
  fails** — `null` ≠ the rendered hooks block. The tool's `SETTINGS` const (`gen-agent-hooks-wiring.mjs:31`)
  points at `settings.local.json`.
- **Fix direction (couples #2 ↔ #4):** repoint `gen-agent-hooks-wiring.mjs` to **emit + validate the hooks block
  into the public `.claude/settings.json` template** (item #2's deliverable), and let the public-CI regen gate
  check *that* template. A subagent's first instinct ("just guard line 97") is a shallow fix that leaves the
  hooks unwired and the check meaningless — reject it.

### F4 — Item #4 (public CI) scope is real and medium-sized (no shortcut)
- Current `.github/workflows/ci.yml` (712 lines): trigger is **`workflow_dispatch:` only** (manual, ADR-0026);
  **all three jobs** (`full_build`, `stress_tests`, `mutation_tests`) run on `[self-hosted, Windows, X64,
  justsearch-perf]`. There is **no hosted lane and no push/PR trigger today** — so the runner split is genuine
  new work, not a tweak.
- The cutover-package ships a ready hosted-lane skeleton (`docs/business/go-to-market/cutover-package/public-ci.yml`)
  + `gitleaks.toml` + a `pre-commit` sidecar-path guard — adapt these rather than author from scratch.

### F5 — Excludes inventory (item #2) — verified state
- **Tracked, need `git rm --cached`:** `.claude/settings.local.json`, `.mcp.json` (both confirmed tracked).
- **Already gitignored:** `tmp/`, `tmp/ui-*`, `modules/*/tmp/`, `scripts/jseval/tmp/`, `.claude/worktrees/`
  (`.gitignore` lines 80–81,119,122,204,214). Agent-telemetry **data** lives under `tmp/` → covered.
- **NOT gitignored (footgun, per readiness B2):** `docs/business/` (6 tracked) + `docs/market-analysis/` (24
  tracked). The cutover excludes them by *snapshot construction*; post-cutover they must be gitignored locally or
  a `git add -A` re-sweeps them.
- **LFS:** 26 LFS files total. The 9 to exclude = `models/onnx/*` (7) + `models/splade/*` (2). The ~17
  `third_party/llama.cpp/models/ggml-vocab-*.gguf` are **MIT vendored test fixtures — publishable** (don't
  accidentally sweep them into the exclude glob).

### F6 — Meta: these go-public tempdocs (631–634) are themselves **untracked** in `main`
- `docs/tempdocs/631–634` are untracked (same untracked planning cluster as `docs/business/` + the
  cutover-package). They have no git history yet; their publication is governed by the cutover snapshot decision
  ("publish tempdocs after the scrub"). **Consequence:** 631–634 are themselves subject to item #1's candor pass
  before they'd publish — they discuss strategy, funding, and the founder's go-to-market in candid terms.
- Practical note for whoever implements: the tempdoc lives **only in the main checkout's working tree** (a fresh
  `EnterWorktree` cannot see it — untracked files don't propagate to worktrees). Either work the doc-side items in
  main, or `git add` the tempdoc to a feature branch first.

## Proposed scope additions (2026-06-23, takeover pass — evidence-backed; founder to confirm fold-in)

The five items target one path string + CI-green. But the **purpose** is a public-safe *and* credible, *runnable*
machinery showcase — and the audit surfaced gaps the current items don't cover. Each below is tied to the **Done**
criterion ("no secrets/PII/machine-paths remain; the machinery builds + gates green") read at full strength, with
primary-source evidence. **G1, G2, G3, G4** are genuine gaps I'd fold into 631; **G5–G7** sharpen existing items.
Items marked **[founder]** are scope/decision calls, not agent calls.

- **G1 — Generalize the fingerprint scrub from `Elias` to the *pattern* (extends #1 + #5).** The codemod targets
  one literal. Real misses found: a **second username `huann`** (`modules/shell/errors-global-shortcut.txt`) and
  the **founder GitHub slug `eliasjustus/JustSearch`** hardcoded in published machinery
  (`.claude/skills/ci-triage/SKILL.md:15`, `governance/{agent-hooks,registry}.v1.schema.json:3` `$id`s). Scrub the
  *pattern* `C:\Users\<name>` + absolute drive paths + the repo slug (which must become the public org/name) +
  the founder email/phone **across the whole tracked tree**, not just `docs/**`.
- **G2 — `modules/shell/errors-global-shortcut.txt` is a committed verbose build-error log** (a `cargo build
  --verbose` transcript, ~300+ lines, full of `C:\Users\huann\…`). This is junk debug output that almost
  certainly should be **deleted, not scrubbed** — it carries no value to a public repo and leaks a contributor's
  machine layout. **[founder: delete vs keep-and-scrub.]** Belongs to #5 (sensitive scan of the newly-public
  surface), which today is framed narrowly around the agent-analytics/`.claude`/tempdoc surface.
- **G3 — Public `settings.json` hook-wiring *policy* (deepens #2/F3).** The manifest wires **24 hooks**. Some are
  founder-local-analytics, not discipline guards: `otlp-sink-ensure` (spawns a local telemetry sink),
  `mcp-session-inject` (injects into the `justsearch-dev` MCP a contributor lacks), `dispatch` /
  `export-session-env` (agent-analytics plumbing). They fail-open so they won't *break* a clone — but the public
  template should make a deliberate call: **wire the guards-that-showcase** (`bash-guard`, `repeat-guard`,
  `build-counter`, `intervene`, the hint hooks) vs **wire everything**. **[founder]** picks the policy; the regen
  tool + gate (F3) must then validate whatever the template declares.
- **G4 — Dead-reference sweep over the published doc set (new; quality/credibility, not correctness).** **7
  published docs reference the excluded sidecar** (`docs/tempdocs/{README,557,618,623,624}.md`,
  `docs/future-features/{open-source-readiness,indexed-content-browser-archive}.md`) — some are markdown links
  that go **dead** when `docs/business/` + `docs/market-analysis/` are absent. F2 proved no *gate* fails on this,
  but a credibility showcase shouldn't ship dead links. Sweep + rephrase/remove. Note the irony: `tempdocs/README.md`
  — the non-canonical framing doc **#1 itself adds** — is on the list, so author it sidecar-link-free.
- **G5 — "Runs on a stranger's clone" audit of the published hooks/skills (broadens Done).** Done today =
  builds + gates green + no secrets. The showcase's *value* is that the machinery **works**. Audit the published
  hooks/skills for hardcoded absolute paths, dependence on excluded files (`settings.local.json`, `.mcp.json`),
  and dependence on the `justsearch-dev` MCP / dev-stack a contributor doesn't have — and document what's
  local-only. (Boundary: 633 #3 owns the *narrative* "how we build" showcase; 631 owns that it *functions safely*.)
- **G6 — `docs/observations.md` explicit candor scope (sharpens #1).** #1 names it but frames the pass as
  tempdoc-centric. It's an internal issue log with candid bug notes + critiques + `file:line`; give it first-class
  candor treatment. (Good news: `docs/observations.d/` shards are clean — only `.gitkeep` + `README.md` tracked,
  no per-session data leaks.)
- **G7 — Mechanical first-pass to make the 388-tempdoc candor review tractable (method for #1).** 388 docs is the
  dominant-effort, least-mechanizable item. Before human review, run an automated triage sweep (emails, phones,
  names, competitor/vendor names, currency amounts, "internal"/"do not publish"/candid-critique markers) to rank
  which tempdocs need human eyes — rather than reading 388 by hand. De-risks the single biggest item.

**Boundary check (out of 631's scope — noted so they don't get re-litigated here):** SPDX headers on the published
`scripts/` tree (→ 632, whose codemod currently scopes `modules/**/src/main/**` — flag the gap to 632); the
"how we build" showcase narrative (→ 633 #3); the final pre-flip gitleaks pass + the multi-agent cut-line (→ 634).

## Item 1 execution (2026-06-23) — pre-filter + subagent fan-out

**Approach (decided with founder):** subagents *fix-immediately* (not audit-only), in two tiers — **auto-fix** the
mechanical/globally-verifiable class (path/username scrub → `C:\Users\<user>`, redact founder email/phone,
neutralize `docs/business`+`docs/market-analysis` links to plain text); **flag-only** the irreversible/judgment
class (whole-doc-sensitive like a misfiled grant app → move-to-sidecar/exclude; third-party-disparagement; possible
secrets; the `eliasjustus/JustSearch` slug — blocked on the public name; ambiguous dead links). Hard rule: **no
rewording / no claim-or-status updates / no date guessing / assigned files only** — tempdocs are dated archaeology,
not to be "improved." Subagents inherit no CLAUDE.md, so the brief is fully self-contained.

**Pre-filter, not full-corpus.** A doc enters the LLM pass only with a signal (fingerprint path · email/phone ·
secret shape · sidecar link · strategy keyword · repo slug). Result: **29/388 candidates (41,212 of 288,257
lines — 7× reduction)**; recall backstop = the full `gitleaks` scan at cutover (634). The 4 go-public planning
docs (631–634) are excluded from the automated pass (handled directly). Net fan-out: **~24 docs**.

**Batching:** by cumulative line count (~4,000/batch, giants solo — 249 is 5,542 lines), **not** fixed doc-count
(size variance is 600×). ~11 size-balanced batches; disjoint file sets so concurrent edits never collide; **no
worktree isolation** (these docs are untracked / main-only + the origin-branch bug). Date-backfill of the ~107
dateless docs is pulled OUT to a separate deterministic `git log` script (not LLM work — guessing dates falsifies
the archaeology).

**Non-tempdoc 1a residue (separate cleanup, mostly founder-gated):** `scripts/bench/lhm-log.ps1` (functional path
— parameterize, don't blind-scrub), canonical `docs/how-to/spawn-isolated-test-backend.md` + `docs/decisions/0028`
(mechanical), the schema `$id` slugs + `ci-triage` skill slug (→ public name), and `modules/shell/
errors-global-shortcut.txt` (G2 — recommend delete). *(Confirmed by `git grep`: these 4 are the only non-tempdoc
tracked files with a `C:\Users\<name>` fingerprint.)*

### Run result (2026-06-23, workflow `tempdoc-public-safety-scrub`, 11 agents, ~2min, 1.2M subagent tokens)
**24 docs processed → 2 auto-fixed (11 edits), 13 flagged (26 flags), 11 clean.** Verified: edits scoped to exactly
the 2 intended files; literal `<user>`/`<redacted>` (no HTML-entity defect); 409's email gone; **zero real-username
`C:\Users` paths remain** in the scanned set. Agents respected the rules — no slug-guessing, whole-doc-sensitive
left intact-and-flagged, self-critique (512 "doc-lie") correctly distinguished from third-party disparagement.
- **Auto-fixed:** `374` (10× `C:\Users\Elias\Desktop\…` → `<user>`), `409` (founder email → `<redacted>`).
- **★ Pre-filter gap caught by an agent (`interrogate-results`):** my signal set only checked `C:\Users\` — but
  **`D:\code\JustSearch` recurs in 24 tempdocs / 53 occ** (an early-era dev drive) and **`F:\JustSearch` in 31**
  (current repo root, mostly benign examples). Neither leaks a *username*, so lower-sensitivity than `C:\Users\<name>`
  — but the codemod scope (which both the tempdoc and I framed around `C:\Users\Elias`) must broaden to
  **all absolute repo/home paths**, or these slip through. **[founder: normalize `D:`/`F:` repo-root paths, or
  accept as benign drive-layout?]**

### Open decisions surfaced by the run (founder-gated — block the *apply*, not the scan)
1. **Public org/repo name** — unblocks the `eliasjustus/JustSearch` + `eliasjustus/justsearch-releases` slug scrub,
   which the run flagged across `374, 376, 411, 344, 530, 409` (and the non-tempdoc schema `$id`s + `ci-triage` skill).
2. **Whole-doc disposition** — `344` (NLnet funding application — strongest move-to-sidecar), `624` (funding/GTM/
   monetization), `623` (benchmark doc entangled with positioning strategy); `512` flagged review-before-publish
   (technical doc carrying a commercial-strategy thread). `500`'s lone grant sentence was correctly judged *keep*.
3. **Third-party commercial commentary** — `344` (candid verdicts on Deel / Sovereign Tech Agency / Lablab.ai),
   `424` (a library-docs MCP server: cites a patched CVE + a pricing cut, recommends "skip"). Publish or redact?
4. **`modules/shell/errors-global-shortcut.txt`** — the `huann` build-error log (G2): delete (recommended) vs scrub.

*(Non-blocking flags needing no action: `409` `REPLACE_WITH_*` placeholders (not secrets); `500` `slices/` dead
`related:` links; `557`/`362` market-analysis/`D:` mentions already covered above.)*

### Resolution + applied (2026-06-23, founder decisions)
- **Public slug = `eliasjustus/justsearch`.** `eliasjustus` is the confirmed *public* org → the slug is no longer a
  fingerprint. Normalized `eliasjustus/JustSearch` → `eliasjustus/justsearch` in **live machinery only** (`ci-triage`
  skill, `agent-hooks`/`registry` schema `$id`s, `workflow-signal-health.mjs`, `sarif-emitter.mjs`); tempdoc
  references left as dated archaeology (no longer sensitive). Schema `$id` is identifier-only — not `$ref`'d
  anywhere, so safe.
- **Truly-sensitive docs → private sidecar.** `git mv` of `344` (NLnet application), `623`, `624` →
  `docs/business/tmp/`; removed from `docs/tempdocs/`. (`512` kept — review-before-publish, dominantly technical;
  `500` kept — lone grant sentence.) Fixed the one orphaned ref my move created
  (`tempdoc-staleness-apply-manifest.mjs` — an unwired one-shot).
  **⚠ PARTIALLY REVERTED 2026-06-23 (independent-review #2 — see Corrections below):** `623` + `624` were
  **un-moved back to `docs/tempdocs/`** — both are the design-authority docs for *shipped public code* (623: the
  release/relevance-gate machinery, 23 refs incl. canonical `search-quality-register.md` + `ci.yml`; 624: the entire
  `scripts/jseval/**` agent-utility subsystem + its schema, dozens of refs). Moving them private stranded all of it.
  **Only `344` stays private** (standalone funding app, zero code refs). 624's NLnet-grant passages are now a
  **founder-redaction flag**, not a move.
- **`modules/shell/errors-global-shortcut.txt` deleted** (junk cargo-error log leaking `huann`'s machine paths).
- **`D:`/`F:` repo-path class:** accepted as benign (no username leak) per founder — not scrubbed.
- **Auto-fix verified applied:** `374` (10 paths→`<user>`), `409` (email→`<redacted>`); canonical `docs/decisions/
  0028` + `docs/how-to/spawn-isolated-test-backend.md` scrubbed (`<user>`); `scripts/bench/lhm-log.ps1` path
  parameterized → `$env:LOCALAPPDATA` (functional, not placeholder). Final ripgrep: **no `C:\Users\<name>` remains
  in any published file** — the only residual is the untracked sidecar doc that *describes* the scrub.
- **Item 1 status:** scrub/scan (1a/1b) + README (1c) **done** for the tempdoc + canonical + machinery surface.
  Backstop: the full `gitleaks` history scan at cutover (634). Remaining item-1-adjacent: none blocking.

### Recall-gap closure (2026-06-23) — third-party sweep + observations.md
The pre-filter only LLM-reviewed 24/388 docs, leaving a recall gap on *candid third-party commentary in unflagged
docs* (framing covers self-critique wholesale, but not statements about others). Closed it **corpus-wide, not by
sample:**
- **`observations.md`:** scanned directly — **0** PII/secret/path/competitor signals. Clean. (Its self-referential
  bug notes are own-project critique, low publication risk — akin to a public issue tracker.)
- **Competitor/vendor sweep over all 388 tempdocs + observations.md:** built the name list from
  `docs/market-analysis/02-competitive-*` (Cursor, Raycast, Perplexity, Glean, a library-docs MCP server, Ollama, Copilot,
  Algolia, AnythingLLM, NotebookLM, DEVONthink, Khoj, PrivateGPT, Sourcegraph, Danswer, Cody, …). **472 mentions /
  73 files.** A read-only subagent classified tone (calibrated to the 424 archetype): **7 passages across 6 files**
  carry any commercial/competitive judgment beyond neutral technical/UX reference.
- **Assessment (verified by re-reading sources, not the summary):** all 7 are **factual technical comparisons**
  ("pure-vector retrieval is weaker than hybrid"; "AnythingLLM lacks reranking") or citations of **public** events
  (the GPT-5 launch episode; a public Cursor forum bug) — defensible and publishable under the README framing.
  `424` (a library-docs MCP server) re-reads as a **sourced, balanced dev-tool adoption eval** (fits + not-fits, patched
  vuln cited with source) — *not* a competitor and *not* gratuitous.
- **Disposition:** per the rule-set, third-party-sensitive content is **flag-only / founder-decides** — agents do
  not auto-redact it. So these are **surfaced, not actioned.** My recommendation: publishable as-is. The single
  genuine editorial call is whether to keep `424`'s explicit citation of a vendor's (patched, public) CVE + the
  exact `83–92%` pricing figure — a courtesy/relationship judgment, not a safety/PII/secret issue → **[founder]**.
  Full flag list: `249:42/63/347`, `345:343`, `500:390`, `543:57`, `231:332`, `424:355-356`.

### ✅ End-to-end verdict (item 1)
**Agent-side work is complete.** 1a (paths) + 1c (README) done & verified; 1b's *actionable* categories
(PII/secrets/machine-paths auto-fixed; whole-doc strategy/funding moved to sidecar; junk log deleted) done; the
candor pass is now **comprehensive (corpus-wide), not sampled** — the recall gap is closed. The only open items
are **founder editorial calls on flag-only third-party commentary** (8 passages, all assessed publishable) — which
are founder-decides *by design*, not agent work. Net: nothing unsafe (PII/secret/machine-path/whole-doc-strategy)
remains in the published surface; `gitleaks` at 634 is the secret backstop.

## Item 2 plan (2026-06-23) — verified analysis + changes
The original three bullets (publish 3 dirs / `git rm` 2 files / make a template) are **under-specified the same way
item 1's scope was.** Verified against the tree:

- **C1 — the publish set ships broken machinery as written.** Hooks **import `../lib/*`**
  (`hook-base`, `event-writer`, `governed-regions`, `hard-invariants`, `input-summarizer`) → `hooks/` without `lib/`
  is broken. The **36 gate enforcers live in `scripts/governance/gates/`**, CI checks in `scripts/ci/`, the wiring
  generator in `scripts/codegen/` — *none* in the `governance/`+`gates/` config dirs the bullet lists, so the gates
  would have **no code to enforce them.** → Publish the real dependency closure: `scripts/{agent-analytics (incl.
  `lib/`), governance, ci, codegen}/` — matching the cutover-runbook (the tempdoc bullet is stale). **[founder scope
  call]** `scripts/agent-analytics/` also holds founder-*analytics* scripts (`otlp-sink.py`, `otlp-viewer`,
  `analyze/score/cost/outcome-session`, `generate-dashboard`) — publish-all (transparency) vs discipline-core only?

- **C2 — the `settings.json` template is the load-bearing core (F3), and it's "compose," not "add a hooks block."**
  `settings.local.json` has 7 keys (`env`, `permissions`, MCP toggles, `hooks`, `enabledPlugins`, `autoMemoryEnabled`).
  The template must **compose**: current public `settings.json` + manifest-derived `hooks` + a deliberately **safe
  `permissions`** posture, while **dropping the founder-local `env`** and `bypassPermissions`/`Bash(*)`. The current
  public `settings.json` has **no `permissions` block** (defaults to ask-always) → the public posture is an unmade
  decision **[founder]**. Then **repoint `gen-agent-hooks-wiring.mjs`** (today writes `settings.local.json`) at the
  template, and **decide the wire-policy [founder]** — which of the 30 hooks publish (discipline guards) vs
  founder-analytics (`otlp-sink-ensure`, `mcp-session-inject`, `dispatch`, `export-session-env`). **Coupled to #4**
  (the regen gate validates the template in public CI — that half defers with #4).

- **C3 — new sub-item: a "runs in a stranger's clone" audit (G5), scoped down.** Verified `hook-base.mjs` resolves
  `repoRoot` *dynamically* (`path.resolve(scriptDir,'..','..','..')`, not hardcoded) and the `JUSTSEARCH_*` refs are
  kill-switches, not founder paths — so hooks are largely clone-portable. The audit narrows to: which hooks depend on
  the *excluded* files (`settings.local.json`, `.mcp.json`) or the dev-stack MCP, and do they **fail-open cleanly**?
  Goal: published guards actually fire (or no-op gracefully), never ship inert.

- **C4 — `.mcp.json` exclusion needs a public story.** Excluding it is right (per-machine + an `env` block), but the
  published `CLAUDE.md`/skills reference the `justsearch-dev` MCP tools a contributor lacks → add a **sanitized
  `.mcp.json` template** or a docs note on local-only tooling. (Narrative half overlaps 633.)

- **C5 — exclude-set hygiene (the #2 analog of item 1's junk log).** Found tracked **`TelemetrySoftDeletesMergePolicy
  .java.bak` + `.bak2`** — editor-backup junk; delete. (Telemetry *data* exclusion already satisfied — data is under
  gitignored `tmp/`; no tracked data files surfaced.)

**Net reshape:** *publish* → full dependency closure (+ analytics-scripts scope call); *exclude* → the 2 files +
gitignores (done) + delete `.bak` junk + strip local `env`; *template (the core)* → compose-safe + repoint the gen
tool + wire-policy, coupled to #4; *new* → clone-runs audit (C3) + public-MCP story (C4). Highest-leverage piece is
still F3: without relocating the wiring into a composed, safe `settings.json`, publishing either ships the guards
switched-off or turns CI red. **Founder calls before apply:** analytics-scripts scope (C1), public permission
posture + wire-policy (C2).

### Item 2 execution (2026-06-23) — safe parts done; destructive swap is cutover-time
**What's inherently cutover-time (cannot apply in live shared `main` pre-flip, same logic as #4's defer):**
`git rm --cached settings.local.json` untracks per-machine config other live sessions use; and swapping the hooks
template INTO live `.claude/settings.json` would double-load the hooks block (settings.json + settings.local.json
both load) during continued private dev. So the *exclude* + the *live swap* + the regen-gate repoint execute at the
cutover (634), from the prepared artifacts.

**Done now (safe, non-breaking, load-bearing):**
- **F3 template — built + regenerable.** Added `--emit-public-template` mode to `gen-agent-hooks-wiring.mjs`
  (no change to the existing default / `--check` path → the live CI gate still passes, verified). It composes the
  safe public base (`.claude/settings.json` — worktree/MCP/plugins) + the manifest hooks block, **stripping
  `permissions` + `env`**, and writes `docs/business/go-to-market/cutover-package/public-settings.json`. Verified
  output: keys `{worktree, enableAllProjectMcpServers, enabledMcpjsonServers, enabledPlugins, hooks}`, **11 hook
  events, no `permissions`, no `env`.** The cutover swaps this in as `.claude/settings.json`.
- **C5 — deleted** tracked `TelemetrySoftDeletesMergePolicy.java.bak` + `.bak2` (editor junk).
- **C3 clone-runs audit — conclusion:** wiring is clone-portable by construction (`${CLAUDE_PROJECT_DIR}/...`
  exec-form; `hook-base.mjs` resolves `repoRoot` dynamically). The only excluded-file hard dependency that would
  break **public CI** is the regen gate's `--check` reading the (absent) `settings.local.json` — the F3/#4 coupling;
  resolved by pointing public-CI's regen check at the template (deferred to #4/cutover). `mcp-session-inject`
  no-ops without the MCP; `hook-integrity` already `existsSync`-guards settings. No clone-breakers.

**Decisions taken (defaults; founder may override):**
- **Permission posture:** template carries **no `permissions` block** → Claude Code's standard ask-for-permission
  default (safest; a contributor adds their own local allowlist in their own gitignored `settings.local.json`).
- **Wire-policy:** **wire all hooks** from the manifest (all are `${CLAUDE_PROJECT_DIR}`-portable + fail-open).
  Open founder option: drop the 4 founder-analytics hooks (`otlp-sink-ensure`, `mcp-session-inject`, `dispatch`,
  `export-session-env`) — would need a `public:false` tier in the manifest + a filter in the emit mode (not built).
- **C1 publish-closure / C4 public-MCP story:** recorded as snapshot-time include decisions (enforced by the
  cutover include-list), not live mutations.

## Item 5 execution (2026-06-23) — secret/sensitive scan of the newly-public machinery — ✅ CLEAN
Swept the trees Option C newly exposes (`scripts/{agent-analytics,ci,governance,codegen}`, `.claude`, `governance`,
`gates`, `CLAUDE.md`, tempdocs, `.github/workflows`, the new `public-settings.json`) for credential shapes,
secret assignments, internal URLs, and non-loopback IPs:
- **Credential-value shapes** (`sk-`/`gh[pous]_`/`github_pat_`/`AKIA`/`AIza`/`xox`/JWT/`BEGIN PRIVATE KEY`): **none**
  in tracked machinery. (The only hits were vendored llama.cpp *example* placeholders — `sk-no-key-required`, a
  stories-fixture password — inside `.claude/worktrees/` (other agents' worktrees), which is **gitignored** and
  does not publish.)
- **Secret assignments with literal values:** **none.**
- **Internal/private URLs or IPs:** **none** — every URL is a public CDN (`cdn.jsdelivr.net`), a public project site
  (`pmd.sourceforge.net`), or a test fixture (`example.test`, `http://surface`). No non-loopback IPs (the OTLP sink
  is `127.0.0.1:4318`, loopback — fine).
- **`.github/workflows`:** secrets referenced only via `${{ secrets.X }}` — no hardcoded tokens.
- **`public-settings.json` template:** clean (only `${CLAUDE_PROJECT_DIR}` hook paths).

Combined with item 1 (paths/PII/candor) and the `gitleaks` full-history backstop at cutover (634), item 5's
agent-side pass is **complete and clean** — no secrets / tokens / internal URLs / machine paths in the published
meta-layer. (Candid *content* in the machinery — `CLAUDE.md`/rules/skills — is the intended showcase, not a leak.)

## NEW FINDING (2026-06-23): the contributor clone experience — "present-but-opt-in," not imposed

**This is a new, founder-raised finding** — distinct from the takeover-pass F/G findings above. It does **not**
change the Option-C decision; it constrains *how* 631 publishes the machinery. (G3 + G5 turn out to be *facets* of
it; this is the organizing lens + two genuinely new items.)

**Concern.** Under Option C a fresh cloner inherits the founder's *entire* dev apparatus — settings, hooks,
tempdocs, governance — which is heavy/opinionated/mostly-irrelevant to a typical contributor. That's the real UX
cost of publishing the machinery, and we should design around it.

**The organizing insight — separate what *auto-imposes* from what's *inert clutter*** (only the first actually
annoys anyone):
- **Auto-imposes** (small surface = the real problem): (i) **`CLAUDE.md`** auto-loads for anyone who opens the repo
  in Claude Code; (ii) a **committed `.claude/settings.json` that wires hooks** → every Claude-Code contributor
  inherits the founder's guards, some referencing infra they lack. *(This is exactly the F3/#2 template + G3 lever.)*
- **Inert clutter** (large surface, but harmless — cognitive load, not behavior): `scripts/agent-analytics/`,
  `governance/`, `gates/`, the 388 tempdocs. They *do* nothing on clone. **Note: the pre-commit guard is already
  opt-in** — `core.hooksPath` lives in `.git/config` (not committed), so a cloner only gets it if they run setup.

**Principle:** machinery **present + visible** (develop-in-public + showcase) but **opt-in + signposted**, behind a
**clean conventional front door**. Transparency ≠ imposition.

**Refinements to fold into 631's items:**
- **#2 / G3 → wire opt-in + split.** Don't auto-wire the founder's full hook set in the committed `settings.json`.
  Either ship a **`settings.local.json.example` + one-line setup** (founder wires; cloner doesn't), or wire **only
  the universally-safe guards** (`bash-guard`/`repeat-guard`/`build-counter`/`intervene`/hints) in the committed
  `settings.json` and keep the founder-local analytics (`otlp-sink-ensure`, `mcp-session-inject`, `dispatch`,
  `export-session-env` — G3's list) out. The regen tool/gate (F3) validates whatever the template declares.
- **NEW (G8) — `CLAUDE.md` must stay contributor-grade.** It auto-loads, so keep it useful for *any* contributor
  (the hard invariants, architecture, build) and **move the founder-personal ops** (worktree ownership, the dev-stack
  lease, telemetry) into a separate "maintainer setup" doc. *(Not previously scoped — G5 covered hooks/skills, not `CLAUDE.md`.)*
- **NEW (G9) — signpost READMEs** at the roots of `.claude/`, `scripts/agent-analytics/`, `governance/`: *"our
  maintainer dev process — present for transparency; contributors can ignore it."* Mirrors the `docs/tempdocs/README.md`
  rewrite (#1) for the other machinery trees, and keeps the **top-level clean** (the first thing a cloner sees reads
  like a normal project).

**Boundary / cross-ref:** the **clean front door** itself (CONTRIBUTING: clone→build→test→good-first-issue, *no
Claude Code required*) + the "how we build" narrative framing the machinery as optional → **633** (#3 +
contributor-DX, aspect #26). **631 owns the machinery side** (opt-in wiring + signposts); **633 owns the
contributor-path side** (the same finding is mirrored there).

### Present-but-opt-in execution (2026-06-23)
The earlier item-2 template wired **all** hooks — which an alignment re-read flagged as the exact "auto-imposes"
anti-pattern this finding warns against. Corrected + the two new items done:
- **Wire-policy fixed (#2 / G3).** `gen-agent-hooks-wiring.mjs --emit-public-template` now **excludes the 4
  founder-analytics hooks** (`dispatch`, `export-session-env`, `otlp-sink-ensure`, `mcp-session-inject`) via a
  `PUBLIC_EXCLUDED_HOOKS` set (schema is strict → policy lives in the emit mode, not a manifest `public:` field).
  Regenerated `public-settings.json`: the 4 are gone; the universally-safe **guards + hints stay wired** (7 events:
  SessionStart/PreToolUse×7/PostToolUse×11/PreCompact/SubagentStart/Stop/SessionEnd). Local `--check` still
  byte-identical → live wiring + CI gate untouched. So the published guards are **live but not imposing**.
- **G9 — signpost READMEs created** at `.claude/`, `scripts/agent-analytics/`, `governance/`: each frames its tree
  as "maintainer dev-process, published for transparency; contributors can ignore it," pointing to `MAINTAINING.md`.
  Additive, no behavior change. (Mirrors the `tempdocs/README.md` rewrite from #1 for the other machinery trees.)
- **G8 — `MAINTAINING.md` created** (top-level) — the maintainer front-door: frames the machinery as present-but-
  opt-in and routes the maintainer-only ops (parallel-agent worktrees → `branch-safety.md`; the dev-stack lease →
  `/dev-stack`; the analytics hooks; local telemetry) by **reference**, no content duplication. **Deferred to
  cutover:** physically slimming the auto-loaded `CLAUDE.md` (its brief "Parallel Agents" section → a pointer) —
  removing it from the live always-loaded set would degrade the in-flight private multi-agent workflow, so it's a
  snapshot-time edit (same necessity as #2's swap), with `MAINTAINING.md` as the destination now in place.

**Net:** the "present-but-opt-in" design is satisfied agent-side — the imposing surface (committed hook wiring) is
now guards-only; the inert surface is signposted; the maintainer front-door exists. Remaining cutover-time: the
`settings.json` swap (#2) + the `CLAUDE.md` slim (G8) — both prepared.

## Independent-review corrections (2026-06-23)
A second-agent review (the `independent-reviewer-required` / `verdict-is-gate` discipline) caught a real defect my
self-review missed. Actions:

- **#2 — stranded references (REAL miss, corrected).** My post-move orphan check grepped only **path-style**
  `tempdocs/623-…`, missing **numeric `"tempdoc": 623`** and **prose `tempdoc 623`** citations. In fact `623` is the
  design authority for the public release/relevance-gate machinery (23 refs incl. canonical
  `docs/reference/search-quality-register.md`, `relevance-ratchet-baselines.v1.json` `"tempdoc": 623`,
  `release.v1.json`, `ci.yml`), and `624` for the entire `scripts/jseval/**` agent-utility subsystem (dozens of
  source refs + `utility-comparison.v1.schema.json`). **Both un-moved back to `docs/tempdocs/` (public).** Only
  `344` (standalone funding app, no code refs) stays private. **Lesson:** a doc-relocation orphan check must match
  numeric + prose citations, not just path links — folded into the dead-ref discipline. **New founder-redaction
  flag:** 624 candidly discusses the **92% number in the live NLnet grant application** being an "identity-less
  fork"/retracted and links the private grant draft (`:71/100/104/204/610/614/1191`) — since 624 must stay public,
  redact those grant-integrity passages before publish (a targeted scrub, distinct from moving the doc). 623 carries
  only minor "business-research-channel" framing lines — low-stakes.
- **#1 — "Done" overclaim (corrected above).** Reworded: the green-under-public-conditions is reasoned
  component-by-component, not demonstrated end-to-end; the integrated run defers to 634.
- **#3 — ★ attacker's-roadmap lens (NEW open decision, founder).** Item 5 scanned for secrets/PII/URLs — **not**
  "does this design doc hand an adversary an exploitable attack-surface map." For a privacy/security-positioned
  product, publishing 388 candid architecture docs is extra exposure the dated-framing does **not** neutralize
  (the strategy's own U6 "invites adversarial scrutiny"). Partly inherent to open-source (the source ships anyway),
  but candid weakness-discussion is incremental. **[founder]** decide: accept as the transparency tradeoff, or run a
  targeted "would this help an attacker?" pass over the security-relevant tempdocs (loopback model, sandbox,
  extraction, secrets-handling) before the flip. Recommended: a bounded targeted pass, since it's a privacy product.
- **#4 — third-party people (checked, largely clean).** Only third-party emails in the tempdocs are public mailing
  lists + **Enron-corpus** dataset addresses (a standard public IR benchmark) — not private individuals. Emails were
  already corpus-wide-covered by the pre-filter. Residual: nameless person-names (hard to mechanize, low-risk in a
  solo-founder repo).
- **#5 — minors acknowledged:** `PUBLIC_EXCLUDED_HOOKS` is a hardcoded set (schema is strict; documented to mirror
  new hooks); `CLAUDE.md` contributor-grade (G8) is partial + deferred — "aspiration more than achieved";
  analytics-scripts publish scope (C1) stays an open `[founder]` call.
