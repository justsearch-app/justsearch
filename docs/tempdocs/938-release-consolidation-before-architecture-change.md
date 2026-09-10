---
title: "Release consolidation before the architecture change (v0.2.0 → next)"
type: tempdocs
status: ANALYSIS (2026-09-06) — inventory of work since v0.2.0 and the remaining consolidation to cut the next release
created: 2026-09-06
updated: 2026-09-06
related:
  - 905-release-unblock            # release hardening; §O owner actions
  - 887-improvement-landscape-register
  - 892-open-items-collector
  - docs/how-to/cut-a-release.md
---

# 938 — Release consolidation before the architecture change

## Why

The owner intends major architecture changes and wants a release cut first, so the shipped
state is a clean baseline. This tempdoc is the working note for: what landed since v0.2.0,
what is half-landed, and what remains to actually publish the next release.

## Baseline facts (verified 2026-09-06)

- Last release: `v0.2.0`, 2026-08-13, built from a release branch (PRs #405–#415) off
  commit `506fd1fd4` (#400). The release-branch PRs #405–#415 all appear in `origin/main`'s
  first-parent log (verified), so the tag's fixes are in the baseline.
- Since `506fd1fd4`, `origin/main` received **266 squash-merged PRs** (#337..#691).
- `origin/main` CI: green on the last 5 runs (2026-09-06, head `fdf50933`).
- Version fields still say `0.2.0` (`modules/shell/src-tauri/tauri.conf.json:5`,
  `gradle.properties:32`). `CHANGELOG.md` `[Unreleased]` is empty.
- Local `main` checkout is **not** the truth: 297 commits ahead (merge noise, observation
  shards, docs(908)), 42 behind `origin/main`. Analysis runs from a detached checkout of
  `origin/main` at `.claude/worktrees/938-release-consolidation`.
- 14 open PRs (9 dependabot; #653, #644 draft, #622 draft, #404), 32 unmerged remote
  branches, 39 local worktrees.

## Findings



### A. PRs since v0.2.0 by theme

266 PRs (#337..#691), tallied by title with ~9 `git show --stat` disambiguations (counts ±2):

| Theme | ~PRs | Examples |
|---|---|---|
| CI / governance / gates / hooks (process only) | 69 | #649–#691 (930 gate consolidation, one day), #619, #656, #675 |
| UI polish / a11y / settings | 46 | sv3 contrast/focus cluster #418–#567, #527 settings window, #616 failed-files drawer |
| Docs / tempdocs only | 40 | `docs(NNN): record publication outcome` ritual (#639, #640, #642, #677, #690) |
| Chat / agent / RAG (sv3, citations, tools, consent) | 38 | #576/#581/#584 agent-tool consent + offering, #464, #473 |
| Indexing / worker / index identity (lane D, 915/931) | 27 | #620, #645, #646, #647, #657, #660, #664, #683, #684, #686, #612 |
| Dev-mcp / dev-stack tooling | 22 | #691, #636, #618, #683, #513 |
| Search quality / retrieval | 10 | #424, #425, #517, #646, #496 |
| Installer / release / updater / signing | 10 | #483 staged model acquisition, #497, #629 (905 hardening) |
| Production MCP server | 4 | #430 (`GET /mcp` 405), #426 (Origin validation), #420 (`facetsTruncated`) |
| Search v2 (built #401–#403, retired #486 — never reached users) | 3 | — |
| Dependency bumps | 2 | #666, #337 |

About half the PRs are agent-process housekeeping with no product effect. The product story
is: search v3 chat surface (852/857/859 promotion), agent-tool consent/offering/rehoming
(875–880), citation correctness (836/847/867/869), lane D index identity + blue/green
(915/931), worker lifecycle (885), installer staged model acquisition (#483), MCP conformance
fixes, and a large readiness/facet/reranker truthfulness fix cluster (#418–#437).

**Partial landings flagged by title:** #509 (`S4-partial`, delegate-tier UI control — verify
live), #643 (lane E part 1, eval-only), #523/#511 (docs-only parked registers), #620→#645
(lane D phase 1→2, completed by later PRs).

A CHANGELOG draft under Keep-a-Changelog headings is in Appendix A; each line must be
re-verified against the PR before it enters
`CHANGELOG.md` (the draft is title-derived, e.g. "reasoning enabled by default" #464 and
"compact profile dev default" #480 need confirming as *shipped* defaults, not dev defaults).

### B. Tempdoc status 817–937

#### B.1 — 817–875 (subagent audit on `origin/main` @ `fdf50933e`; load-bearing items re-read)

**Headline: Search v3 is not user-reachable.** `core.search-v3-surface` is still
`audience: 'DEVELOPER', placement: 'DEEPLINK'` (`CorePlugin.ts:92-98`, verified). The shipped
chat window remains `views/UnifiedChatView.ts`. Tempdoc 852 (sv3 promotion) stalled after S4
(last code #509, 2026-08); S5–S11 never started; `governance/window-cutover.done` is absent;
`scripts/ci/check-window-cutover.mjs` (`DEADLINE = '2026-09-30'`, `:54`, wired at
`ci.yml:583`) WARNs today and **fails CI on 2026-09-30**. Consequence for the release: every
"sv3" feature in §A's changelog draft (timeline, composer, width presets, pager, settings
window, run-step nav) is invisible to users. What *does* reach the shipped window is the work
on shared components (`MarkdownBlock.ts`, `CitationsPanel.ts`, `ReasoningBlock.ts`,
`ToolCallCard.ts`, `DocumentPane.ts`) — citation marks/species/carve-outs (839/867/869),
prose rhythm (873), reasoning block restyle (870), markdown substrate (846).

Round 17 (2026-08-13) qualified 0.2.0 and closed 817/823/823a-F/824. Ten tempdocs carry stale
status fields (834, 836, 863, 864, 865, 870–874 say design/in-progress for merged work) — do
not scope from frontmatter.

**Release-relevant residue, priority order:**

1. **852 decision**: promote sv3, retire it, or move the deadline — before 2026-09-30 CI goes red.
2. **823a — the 0.2.0 → next upgrade path is unexercised.** Publisher key moved
   `Software\justsearch` → `Software\Elias Justus`; custom install dir not restored; empty
   `_?=` uninstaller handoff. 823a's own adjudication: "no users today" justifies no shim,
   "it does not justify shipping an unexercised upgrade path" (`823a:41-43`). This is exactly
   the path a 0.2.0 user takes.
3. **Shipped-window bug**: opening a past conversation from History while
   `affordance === 'retrieve'` renders the rail but no thread (`UnifiedChatView.ts:2686-2691`,
   routed unfixed at 859 `:272`).
4. **875 consent gaps**: `OperationExecutorImpl.undo()` (`:528`) never calls
   `enforceTrustLattice` (only `execute()` `:324` does); `KnowledgeSearchController.handleIngest`
   is a second uncontained ingest surface (875 `:339-347`); `/api/chat/agent/undo` mojibakes
   non-ASCII (`:472`); out-of-root ingested docs unremovable (`:474`). Inside loopback, but the
   release notes will say "consent-gated file-acting agent".
5. **840 model download**: Phase 0/1 landed (`AcquisitionScheduler.java`); Phases 2–6 (the
   user-visible per-component split, categories, rate/ETA) not shipped.
6. **868**: `POST /api/chat/agent` with a non-empty `tools` selection returns `NO_TOOLS`
   (guard `AgentLoopService.java:568`, 868 `:604`); `DEFAULT_MAX_ITERATIONS=10` truncates
   multi-doc delegate tasks.
7. **826** embedding-fingerprint recovery F1–F3 never started (BLOCKED since 08-14).
8. **835/848/845 compose**: reasoning default-on at budget 512; quick+thinking observed
   returning 0 answer chars (845); such a turn reloads as an empty assistant bubble with a
   reasoning block (848 `:794-801`) — one live pass on the default profile settles it.
9. Lower: 854 fusion residue (W2 parked), 827 three status fields with no FE consumer,
   845 Defect 2 A4 (model denies file access when asked directly), 843 single-thread
   streaming, 855 D1, 847 S6 L3, `jf-control` accessible-name console error every session.

#### B.2 — 876–937 (60 tempdocs; subagent audit on `origin/main` @ `fdf50933e`)

Classes: 22 PROCESS-ONLY (agent workflow / governance / CI — cannot affect the shipped app),
6 COMPLETE (876, 879, 899, 906, 916, 923), 12 PARTIAL (product work landed with residue),
the rest OPEN charters from the 887 register (of its 13 opus lanes only 893 and 899 are
implemented; 888–898, 900–902, 907 are chartered-not-started, 903 §6 not started, 917 lane F
awaiting owner go/no-go). Nothing half-shipped in the OPEN set — they are designs.

**Product residue a user could notice (PARTIAL set), priority order:**

1. Failed-files drawer renders the literal placeholder `SCAN_ID_NOT_PLUMBED` on every row
   (911 `:302-311`, 4-layer plumb unfixed).
2. Agent transcripts written while the Worker is down are never indexed, permanently —
   reconciliation sees a healthy file and skips (909 `:290-297`, `AgentHistoryIndexer.java:265-272`).
3. Braked ingest queue is unbounded and silent; live arm saw `pendingJobs` pinned at 201 for 90 s
   with `searchableDocuments` frozen (915 O11 `:416`). Also 6–8 `Lucene health check failed`
   log lines per deferred-open boot (915 O13 `:427`), and the full blue/green live loop is
   unrun (O3/O10).
4. Agent worker-outage path leaks a raw internal string to the model ("Browse error: No valid
   port in signal bus") instead of the typed WORKER_UNAVAILABLE phrasing (877 `:756-765`).
5. `AgentStepRunner.java:842` routes `vop_*` before the 875 authorization check at `:899`; a
   hallucinated `vop_` name parks the run for the virtual-tool timeout (880 `:810`). Mitigated
   only because the catalog publishes an empty list; ship-or-retract still open.
6. Legal corpus: 39 chunks/run reach terminal SPLADE FAILED `blank-content` (931 `:695`,
   owner tempdoc 717). `rag.chunk_splade.enabled` gates the write side but the query-side leg
   runs regardless (`SearchExecutor.java:653`, 931 `:536`, owner 712).
7. Tauri "Add folder" now needs one extra confirm click and no live desktop run exercised it
   (914 `:377`) — one installer smoke before cutting.
8. `GET /api/chat/agent/history` + three `agent.ts` helpers have zero callers (913 `:634`) —
   wire or retire.
9. Non-NVIDIA users get search only, no chat (903; `InstallPlanner.java:210-229`), and the
   docs still do not say so (`docs/explanation/05-ai-architecture.md:83-85`) — a user-facing
   accuracy problem for the release notes even if the capability stays out.

Nice-to-have, not blocking: 912 commit floor (`CYCLE_BUDGET_MS = 5_000L`), 883 `ContextBudget`
routing / 200k-char `DocAccess` injection, 885 untyped `markFailed`, 910 dead
`src/api/domains/indexing.ts`, 934 npm publish (founder-gated), 878 effort-scaled cap.

**Pins.** `expected-state.v1.json` was retired by 930 (#656): both hidden failures were fixed,
not re-pinned. Facts that outlived the pins, in prose: `ProcessExtractionSandboxTest` fails
under a deep worktree path only (`CreateProcess error=206`, RISK-010) — verify from a short
checkout; `BatchUpdateIntegrationTest.concurrentRmwOnSameDocIdSerializedByCoordinator_402`
is load-flaky under whole-repo `gradlew test` only (912 `:768-790`). (`origin/main`'s
CLAUDE.md / rules no longer reference the pin file — only the stale local `main` checkout's
hooks do; that is drift of the local checkout, not of `origin`.)

Note: this audit read 905's residual list as still open for the environment and descriptor URL;
§C's live `gh` metadata shows both are done (2026-09-03). §C wins.

### C. Release pipeline readiness

Audit of `origin/main` @ `fdf50933e` (subagent, file:line evidence re-checked where load-bearing).

**What a release is today.** `build-installer.yml` is `workflow_dispatch` only (`:3-5`); a tag push
does nothing — the owner dispatches on `--ref v<x.y.z>`. The job runs under
`environment: release-signing` (`:57`), which has a `required_reviewers` rule (created
2026-09-03), so every dispatch **pauses for owner approval** before any step. Fail-closed gates
in order: no qualification build on a `v*` ref (`:71-78`); CHANGELOG has an exact non-empty
`## [<version>]` section (`:173-181`, `scripts/release/release-changelog.mjs`); CodeSignTool
SHA-pinned (`:226-235`); updater trust inputs present and descriptor URL equal to
`https://github.com/<repo>/releases/latest/download/release.v1.json` (`:237-276`); release
sequence derived from published `release.v1.json` (`:361-364`); signing admission
(`scripts/ci/resolve-release-signing.ps1`: tag must equal `gradle.properties` version,
`providerRemainingSignatures` int ≥ 12, `ALLOW_UNTRUSTED` rejected on tags); signed-mirror
pins complete (`:428-437`); publish as draft → upload → re-download → SHA256 verify → undraft
(`:501-589`). `installer_verify` runs **after** publish (`needs:` at `:593`) — it cannot hold a
release in draft.

**Owner trust inputs: all present** (metadata only). Repo secrets for codesign mode/command
(2026-08-12) and both updater/metadata private keys (2026-08-13); env `release-signing` holds
duplicate codesign secrets (2026-09-03, repo copies not yet deleted); repo variable
`JUSTSEARCH_RELEASE_DESCRIPTOR_URL` already points at `justsearch-app/justsearch` (2026-09-03) —
**905 §O.3 is stale/closed**. No live `eliasjustus/justsearch` reference remains outside tempdocs.

**Live red.** Last dispatch, run `33807983478` (main, 2026-09-03): packaging job succeeded,
`installer_verify` failed with `EvidenceBundle capture reported failed status (exit=-1073740791)`
at `scripts/ci/verify-installer-nsis-win.ps1:938` although all three functional legs printed
PASS. `0xC0000409` is the known-benign libuv `UV_HANDLE_CLOSING` teardown assert that
`scripts/sandbox/collect-evidence.ps1:1030,1083` already tolerates; the NSIS verify script does
not. Agent-fixable.

**Version authority.** `gradle.properties:32` is the only hand-edited field;
`scripts/ci/sync-version.ps1` propagates to `tauri.conf.json`, `Cargo.toml`, `package.json`,
`packaging/mcpb/server.json` (`:113-154`). `README.md:35-47,210-211` (link, size, SHA-256) is a
manual post-publish edit.

**617 §9 live updater evidence is entirely unrun** (`617:415-429`, `:791-800` says v0.2.0's
publication does not close it). No workflow gate references it, so a tag dispatch publishes
without it. The exposure: v0.2.0 users' first in-app update to the new release is untested
end-to-end, and `updater.rs` persists `highest_accepted_sequence` with no in-client recovery.
This is an explicit accepted-risk decision for the owner (runbook Known Issues policy,
`cut-a-release.md:159-176`), not something an agent can close.

**eSigner: the trial question is closed.** The unpublished branch
`codex/905-operational-closeout` (4 commits, 2026-09-03/04, never PR'd) records: the credential
was disabled by PIN attempts, recovered and re-enabled; the plan is **Personal ID Code Signing
Tier 1 Annual** with a provider-authoritative balance of **240 unused signings** (read
2026-09-04); the migrated Environment credential was proven by run `33807983478` (8/12
signatures reserved, all verified). The same branch records that the WinGet submission
(`microsoft/winget-pkgs#429017`) passed all validation stages and was **withdrawn on owner
request** — WinGet is off the table, so 905 §O.4 is closed too. The branch also carries the
`installer_verify` fix (`capture-evidence-bundle.mjs`: `process.exit()` → `process.exitCode`,
plus `scripts/release/evidence-capture-exit.test.mjs` wired into CI; CI run `33810680856`
green). **This branch must be published before the release** — it is both the red-lane fix and
the authoritative record of the signing state.

**Runbook drift (canonical doc, fix in the release PR):** (1) `cut-a-release.md:264-268` omits
the environment-approval pause; (2) `:385-389` describes the `release-signing` environment as
not-yet-created; (3) `:153-157` implies the packaged verify lane can hold the release in draft —
it cannot; (4) the `sign` / `sandboxTestMode` / `candidateVersion` dispatch inputs are
undocumented, and the latter two are the mechanism 617 §9 item 2 needs.

**Routing note:** the pin mechanism (`expected-state.v1.json`) was retired by 930 (#656), so
the red installer lane has no pin home — per `development-philosophy.md:12` the rule is now
"fix it", which is item 2 of the checklist below.

### D. Branch / worktree / open-PR consolidation

Method: a subagent inventoried all 39 worktrees, 32 unmerged remote branches and the open PRs;
its three-dot diffs are ancestry-based, so every "pending" verdict was re-derived here by
two-dot content diff of each branch's own changed files against `origin/main` (0 files = landed).

**Local `main` checkout.** Nothing unique except `docs/tempdocs/908-*.md` (687 lines, never
PR'd), observation shards (store retired by 872), and jseval run artifacts under
`scripts/jseval/624-run-*/`. Its "extra" code is pre-930 state that `origin` has since deleted
(tier-register, ci.yml, pins). Its lane D bundle landed via #645/#646/#657. The uncommitted set
(898/900/903 tempdocs, inference-runtime skill + register, `blast-radius` skill, 919/935
tempdocs) belongs to another session and was not touched; the local uncommitted 903 copy is
**newer** than `origin/worktree-903-non-nvidia-reread`.

**Landed — safe to delete (content diff empty):** worktrees `lane-F`, `906-publish`,
`937-agent-model-routing`, `937-publication-record`; remote branches `codex/906-publish`,
`codex/936-worktree-removal` (#691), `codex/937-*`, `worktree-825-design`,
`worktree-854-fusion-charter`, `worktree-887-improvement-landscape`,
`worktree-901-sensitive-content`, `worktree-resid2-worker-live`, `worktree-round6-preregistration`,
`worktree-wave3-draft` (its 931 fixes landed via #684/#685; residue 2 files), `worktree-wave3-c1`
(C1 codec: #662 closed by decision — float32 stays; 931 `:637-650`), `worktree-lane-D2`
(915 docs; code superseded by #620/#645). Worktrees `903-takeover`, `892-takeover`,
`901-takeover`, `900-static-analysis-concurrency` are byte-identical copies of the local-main
baseline (0 unique files).

**Stale — close:** PR #404 + `worktree-818-critical-fixes` (touches `search-v2/*`, deleted by
851); `worktree-round-17-candidate` (#415 landed); `pr549` (old merge artifact);
`worktree-903-non-nvidia-reread` (superseded by the local uncommitted copy).

**Open PRs with real content:** #653 (932 pin retirement, green, ready — but 930 already
retired the pin file on main, so re-check its diff against current `origin/main` before merge);
#644 (lane D PR-A, draft, blocked on eval campaigns per its body; content largely landed via
#645–#657 — probably closable); #622 (lane E part 1, draft, prep-only by design; 916 is
CLOSED, so decide close-or-merge). Nine dependabot PRs; 6 show a mixed FAILURE/SUCCESS rollup
(not triaged).

**Pending, real, no PR (Codex lanes, all committed, several active on 2026-09-06):**

| Branch | What | Product-affecting? | Last commit |
|---|---|---|---|
| `codex/905-operational-closeout` | `installer_verify` exit fix + CI test + the authoritative signing/WinGet record (§C) | **release-critical** | 09-04 |
| `codex/919-takeover` (+ `919-checker/-lifetime-tests/-splade` with 20–38 uncommitted files each) | `NativeSessionHandle` lifetime/concurrency fix in `ort-common`, SPLADE encoder, jcstress fixture; tempdoc 919 only exists locally | yes (inference runtime) — **in flight** | 09-06 |
| `codex/888-takeover` (+`-hooks/-python/-rust`) | 888 CI enforcement: SpotBugs filter (5.7k lines), jseval python test rewrites, LLM benchmark gates, `benchmarks` module | process/CI mostly | 09-06 |
| `codex/897-format-breadth` / `897-current-main` | duplicate-prevalence measurement tooling in jseval + `FolderBrowseEngine`/`DocumentService` fixes | partly | 09-06 |
| `codex/926-hook-architecture-derisk` | hook architecture, doc-impact gate, governed-regions v2 | process | 09-05 |
| `codex/906-takeover` (+5 siblings) | tempdoc 906 investigation branches; 906 itself merged as #688 — residue is 906's own investigation docs | check then delete | 09-0x |
| `codex/899-*` (6 branches) | 899 D1–D6 published as #640; branches carry stale pre-930 baseline + investigation docs | check then delete | 09-0x |
| `codex/921-permanent-review-record` | 921 published as #632; residue is pre-930 baseline (`expected-state.v1.json`, old ci.yml) | delete | 09-04 |
| `worktree-agent-a5cf92a1ad3d218b1` | agent-analytics efficiency-trend tooling (pairs with the local-only 908 tempdoc) | process | 09-02 |
| `codex/lane-d-pr-b` / `lane-d-pr-c1` | codec v2 / quantization (C1) — decided not to ship (#662) | delete | 09-05 |

## Remaining work to cut the release

Ordered. AGENT = an agent can do it in a PR; OWNER = needs the owner's hands or decision.

1. **OWNER: pick the version and the sv3 stance.** `0.2.1` (fixes only, sv3 stays hidden,
   move or satisfy the 2026-09-30 cutover deadline) or `0.3.0` (promote sv3 first). Sizing the
   promotion honestly: S0–S4 all merged on one day (2026-08-19), so the coding velocity was
   high, not "weeks". What remains is (a) an owner decision Q1 (does the `retrieve` tier exist —
   gates S4-rest + S5–S7), (b) the flip itself, which is two lines (audience `USER` +
   `governance/window-cutover.done`), (c) the S8–S11 sweep deleting `UnifiedChatView.ts` and its
   consumers (857 `:737-770`: composition-surfaces register loses its only adopter, 18 ui-shot
   steps remapped, spine modules orphaned, run-renderers register rows), and (d) 859's 13 open
   sv3 live findings (no stop affordance during an answer, negative `budget.remaining`,
   DOM-transition unhandled rejections, an axe serious violation, contrast below 3:1). The real
   cost is shipping a brand-new chat surface with zero soak time in the same release that
   precedes an architecture change — a risk judgment, not a calendar one. The deadline gate
   must be handled either way or CI goes red on 2026-09-30 regardless of the release.
2. **AGENT: publish `codex/905-operational-closeout`** (red-lane fix + signing record). Rebase
   onto current `origin/main`, PR, merge. Then re-dispatch an unsigned `build-installer.yml` on
   `main` to confirm `installer_verify` is green end to end.
3. **AGENT: write the CHANGELOG section** from Appendix A, corrected by §B.1 (no sv3 lines),
   each line verified against its PR; `release-changelog.mjs prepare` must pass. Include a
   Known Issues block (runbook `:159-176`) for whatever from §B stays unfixed.
4. **AGENT: fix the shipped-window bugs that a 0.2.0 user would hit in the first hour** —
   candidates from §B: 859 blank stage on History reopen (`UnifiedChatView.ts:2686`), 911
   `SCAN_ID_NOT_PLUMBED` placeholder, 868 `NO_TOOLS` on a tools selection, 877 raw
   "Browse error" leak. Each is a bounded fix with a test; owner picks the cut line.
5. **AGENT: sweep stale claims for the notes** — docs still don't say "search only without a
   supported GPU" (903, `05-ai-architecture.md:83-85`); 875's undo path is not trust-gated,
   so phrase the agent consent claim precisely.
6. **AGENT: version bump** — edit `gradle.properties:32`, run `scripts/ci/sync-version.ps1`,
   `pack-mcpb.mjs --sync`, `check-mcpb-consistency.mjs --release-version`.
7. **AGENT: runbook drift fixes** (§C: environment-approval pause, environment already exists,
   verify lane runs post-publish, undocumented dispatch inputs). Canonical doc, can ride along.
8. **OWNER: upgrade-path smoke** — 823a's unexercised 0.2.0 → next over-install (publisher key
   moved, custom install dir, `_?=` handoff) and the 914 Tauri Add-folder confirm click. A
   Sandbox `upgrade-from-release` round per runbook `:44-141`. Also the accepted-risk decision
   on 617 §9 (in-app updater N→N+1 never exercised) — recorded as a Known Issue if accepted.
9. **OWNER: dispatch** — `build-installer.yml --ref v<x.y.z>` with `sign=true` and the fresh
   portal balance (≥ 12; last authoritative read 240); approve the `release-signing`
   environment when the run pauses; watch the draft → publish sequence.
10. **AGENT: post-publish** — README asset line/link/SHA (`README.md:35-47`), dispatch
    `update-preserves-models.yml` against v0.2.0 → candidate.
11. **Consolidation hygiene — DONE 2026-09-10 (tempdoc 952 audit: 176→35 local branches, 53→24 worktrees, everything archived to a bundle; #622 closed; #404 left open pending the ResultsCard salvage decision):** delete the landed/stale branches and worktrees listed
    in §D; close #404; decide #644/#622/#653; publish or drop the local-only 908 tempdoc; the
    active Codex lanes (919, 888, 897) are in flight — leave them, they do not gate the release.

## Decisions needed from the owner

- Version: `0.2.1` vs `0.3.0` — this fixes the sv3 question and the tone of the notes.
- Search v3 (852): promote, retire, or move the 2026-09-30 deadline.
- Cut line for §B product residue: which of the shipped-window bugs get fixed in this release
  vs. listed as Known Issues.
- 617 in-app updater evidence: accept the risk (Known Issue) or run the lanes first.
- Disposition of #644, #622, #653 and the 20-odd landed/stale branches.
- Whether the architecture change should wait for the active Codex lanes (919 inference
  runtime is product code; 888/897/926 are tooling).

## Appendix A — title-derived CHANGELOG draft (unverified; re-check each PR before use)

> Correction after §B.1: the first "Added" bullet (Search v3 surface) is **not user-reachable**
> (DEVELOPER/DEEPLINK, `CorePlugin.ts:92-98`). Drop it from user-facing notes unless 852 is
> promoted first. Keep only the shared-component effects (citation marks, prose rhythm,
> reasoning block, markdown substrate) which do reach the shipped window.

### Added
- Search v3: chronological run timeline, floating composer, chat-width presets, context-set controls, branch/version pager with edit/retry/cascade-delete, settings window, run-step keyboard nav, health/activity panel (#533, #529, #573, #503, #505, #527, #516, #514)
- Agent tools: consent boundary (grant risk ceiling, argument scope, undo containment), tool-offering truth, `core_read_document` with declared path prefix, evidence that survives every run terminal (#581, #584, #566, #551)
- Citations/RAG: citation-coverage honesty, literal-passage verification, persisted reasoning history, conversation title authority, bounded model reasoning (#473, #466, #492, #461, #464)
- Indexing: stable document identity across rebuilds with blue/green default, `content_sha256` + stale-label down-weighting on hits, `SettleIndex` RPC / `POST /api/indexing/settle`, enrichment-completeness visibility with real force-reindex (#620, #645, #660, #664, #432)
- Installer: staged, component-level model acquisition — search usable before the full download completes (#483)
- MCP server: `GET /mcp` answers 405 per spec, Origin validation, `facetsTruncated` relayed (#430, #426, #420)
- Health: `enrichment.incomplete` condition (#437)

### Changed
- Chat model profile: compact default with standard opt-in; context budget from the live window (#480, #599, #596) — confirm which is the shipped default
- Worker: contention-based duty cycle, persistent extraction process pool, NRT reopen-on-demand (#595, #598, #602)
- UI naming converged ("Detailed mode"/"Search"), shared markdown substrate, prose rhythm/contrast pass (#678, #489, #572)
- Search: per-language dense-field skip replaced by a field-local DF rule (#646)

### Fixed
- Readiness/facets/reranker truthfulness cluster: optional reranker no longer degrades retrieval, facet truncation truthful, staleness populated, rerank count-preserving, `query_syntax=lucene` honored on multi-leg search (#418–#437)
- Boot/worker: transient worker PID-validation timeout no longer bricks the Head; watcher no longer records a mid-write size of zero; embedding compatibility checked before ingest (#439, #612, #470)
- Extraction: text files whose first bytes matched a binary magic number indexed as empty (#459)
- Chat: streaming-producer wedge; delegate tier discarded the model's tool call (#476, #586)
- Citations: weakly-supported selection no longer hidden; unverified references stripped; no false verdict from a matcher that never ran (#460, #578, #548)
- UI: failed-files drawer layout/chip reachability, failed-jobs wire contract, sv3 contrast/focus/hit-target cluster (#616, #614, #688, #530–#567)
- Installer/updater: honest terminal install state + converging repair, spaced transport retries with BITS tolerance, updater public key pinned, publisher identity + signed uninstaller, `ReleaseSequence` derived from published descriptors (#413, #412, #415, #410, #497)

### Security
- ADR-0046 mutation token fail-closed (#597); `/mcp` Origin validation (#426); agent-tool consent boundary (#581); updater key pinned + signed uninstaller (#415, #410)

### Removed
- Search v2 (dev-deeplink only) built in #401–#403 and retired in #486 — never user-visible; omit or mention as internal

## Appendix B — brief for the Search v3 completion agent (2026-09-06)

Goal: make Search v3 the shipped chat window and delete the old one, so the next release is 0.3.0.
Authority: tempdoc 852 (slices S0-S4 landed 2026-08-19, S4-rest + S5-S11 open), 857 section 8
(sweep obligations), 859 (13 open live findings), scripts/ci/check-window-cutover.mjs (the gate:
audience USER in CorePlugin.ts + governance/window-cutover.done, deadline 2026-09-30).
Work from a fresh worktree off origin/main; load /ui-check before touching modules/ui-web.
Order: (1) get the owner's Q1 answer (does the retrieve tier exist) and finish S4-rest + S5-S7 on
that basis; (2) fix the 859 findings that a user would hit (stop affordance while answering,
negative budget.remaining, DOM-transition unhandled rejections, axe serious target-size, composer
contrast); (3) flip audience + create the marker; (4) sweep UnifiedChatView.ts and its consumers
per 857:737-770, regenerating ui_step_index.json, ui-step-coverage, composition-surfaces and
run-renderers registers in the same PR; (5) live round with ai_activate, screenshots via jseval
ui-shot, axe measured; (6) update 852 status and the CHANGELOG [Unreleased] Added section.
Done means: check-window-cutover passes on the real clock, run-ui-web-gates green, no
search-v3 DEVELOPER/DEEPLINK residue, the old window absent from the tree and every register,
and an independent reviewer (not the implementer) signs the live round.

## Progress (2026-09-06, consolidation branch `worktree-938-release-consolidation`)

Owner decision: version 0.3.0, Search v3 promoted by a dedicated agent (Appendix B).

| Item | State | Where |
|---|---|---|
| 2 publish 905 closeout | done on origin: #692 carries the exit-code fix, run 34027810568 verified `installer_verify` green on that content; #693 closed the record. No re-dispatch needed. | origin/main |
| 3 CHANGELOG | written under `[Unreleased]`, every bullet verified against its PR; 0.2.0-tag PRs, dev-only defaults (#480) and eval plumbing (#664) dropped; sv3 bullets carried under an HTML comment for the sv3 agent to confirm; Known Issues as an h4 (the gate allows only the six Keep-a-Changelog h3s). `release-changelog.mjs check` and `prepare --version 0.3.0` pass. | this branch |
| 4 shipped-window bugs | 868 NO_TOOLS: binding was sound, the availability cause was already closed by 876; the residual (one constant message for every empty-offering cause) now names withheld vs unknown tools. 877: `errorCode`/`retryable` projected on `tool_exec_completed`; signal-bus-down mapped to SERVICE_UNAVAILABLE with the retry guidance. 911: `scan_id` plumbed SQL to proto to `IndexingJobView`, placeholder deleted. 875: `undo()` gated on the trust lattice; JSON responses declare `charset=utf-8` via one after-handler. 909 §E.1: durable `.md.pending` marker, reconciliation re-submits. The undo gate makes every real undo TYPED_CONFIRM, so the FE undo path was routed through the existing authorization broker (follow-up worker). 859 blank-stage stays with the sv3 agent (the window is being deleted). | this branch |
| 5 stale claims | README + 05/13/16 explanation docs now say chat requires a supported NVIDIA GPU, search-only without one (`Necessity.java:27-36`, `InstallPlanner.java:207-224`). | this branch |
| 6 version bump | `gradle.properties` 0.3.0, `sync-version.ps1`, Cargo.lock shell crate, mcpb manifest/server.json, `check-mcpb-consistency` OK, THIRD_PARTY_NOTICES regenerated. | this branch |
| 7 runbook drift | environment-approval pause, environment exists (repo-scoped codesign secret copies still present — owner cleanup), verify lane runs post-publish, dispatch-inputs table. | this branch |
| 8, 9, 10, 11 | owner: sandbox `upgrade-from-release` round, tag dispatch, post-publish README, branch disposition (§D). | open |

Not verified live: none of the backend fixes were exercised against a running stack (dev stack leased elsewhere); unit, controller-level (real Javalin) and full-suite tests only. The sandbox round is the live tier for them.

Follow-up routed here (not release-blocking): `POST /api/chat/agent/undo` (`AgentSessionController`) parses only `{toolName, executionId}`, so it can carry no consent capsule and can now only answer 428 for the one gated undo-capable operation; the FE's `undoToolExecution` had zero callers and now delegates to the shared `POST /api/undo/{id}` path. Retire the route with a sweep (api-contract-map, apiRoutes registry, its charset test moves to a sibling route) in a follow-up PR.
