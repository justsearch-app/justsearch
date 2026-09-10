# Branch / worktree sprawl audit — 2026-09-10

Main checkout `F:\justsearch-public` on `main` @ `da79f8d57`. Read-only audit; nothing deleted, checked out, or pushed.
Method: content, not ancestry (PRs are squash-merged). Raw data: `tmp/branch-audit2.json`, `tmp/branch-audit2.txt`, `tmp/pr-all.json`.

## Totals

| Surface | Count |
|---|---|
| Local branches (excl. main) | 175 |
| Remote branches on origin (excl. main/HEAD) | 35 + stray local ref `refs/remotes/pr549` |
| Registered worktrees (excl. main checkout) | 52 across `.claude/worktrees/`, `F:/justsearch-public-worktrees/`, `F:/justsearch-worktrees/`, `F:/justsearch-public-pr-{b,c1}` |
| Unregistered leftover dirs | `F:/justsearch-public-worktrees/923-ui-naming` (695 MB, no `.git`), 3 empty dirs under `.claude/worktrees/` (`wave3-settle`, `844-dev-surface-honesty`, `819-fingerprint-boot-race`) |
| Worktree disk | ~84 GB total; `lane-F-A` alone 21 GB |
| Open PRs | 16: 6 human (#718 #700 #699 #698 #622 #404) + 10 dependabot |
| Local branches whose upstream is gone | 95; never pushed 53; live upstream 28 |

Root cause of most of the codex/* noise: the stranded local `main` episode (tempdoc 940). ~30 codex branches show 280–317 "ahead" commits that are duplicates of content already on main (presence ≈ 0.93). Their real unique work is 0–25 commits each. `.claude/settings.json` on main still has `worktree.baseRef: "head"`; the fix is PR #699, unmerged.

## 1. Work that does NOT exist on main (keep / decide)

### Active, keep
| Branch | Where | What |
|---|---|---|
| `worktree-lane-F-A` | `.claude/worktrees/lane-F-A`, PR #718 draft, CLEAN/MERGEABLE, 14/14 green at `1465acb7a` | Tempdoc 936 integration branch, stages A/B done, C1 in progress, merge at stage F. Local is 1 docs commit (`ae53ac5fc`) ahead of origin — unpushed. Hygiene flag: `evidence/C1/raw-evidence-sha256.json` is 26 MB / 79% of the PR's insertions. |
| `worktree-inference-host-design` | `.claude/worktrees/inference-host-design`, pushed, no PR | New lane (H): `docs/design/inference-host/` frozen v4 design + register/skill updates, committed today. 0% on main. Needs its own PR. |
| `codex/lane-e-part3-parked-candidate-20260904` | no worktree | Named verbatim in main's tempdoc 916 line 240 as the preservation branch. Do not delete. |
| `rescue/main-checkout-untracked-2026-09-06` | local + origin | Only committed copy of 5 files still untracked in the main checkout: `.agents/skills/blast-radius/*`, `.claude/skills/blast-radius/SKILL.md`, tempdocs 919 (816 l) and 935. Keep until they land. |
| `origin/cla-signatures` | remote | cla-assistant storage. Keep. |

### Mergeable now or after a rebase
| Branch / PR | State | Action |
|---|---|---|
| `worktree-908-token-efficiency-trend` #698 | MERGEABLE/CLEAN, 14 green | Merge. `scripts/agent-analytics/efficiency-trend.mjs` + `trend-snapshot.mjs` + tests (2.6k lines) absent from main. |
| `worktree-940-worktree-base-fresh` #699 | CONFLICTING | Rebase, merge. Flips `baseRef` head→fresh + hook flagging a stranded local main. This is the fix for the sprawl mechanism itself. |
| `worktree-932-pin-retirement-v2` #700 | CONFLICTING | Rebase, merge. One clean commit `1b3e5af6a`; successor of closed #653. |
| `worktree-hook-wiring-repair` (no PR) | 1 commit `4acf8fac4`, 11 behind | Open PR, merge. Adds `hook-integrity/stale-live-command` + `live-command-resolves` rules + 135-line test; not on main, not covered by #719. |

### Large unlanded bodies needing an owner decision
| Branch | Size | Situation |
|---|---|---|
| `codex/888-takeover` (`F:/justsearch-public-worktrees/888-takeover`) | 53 real commits, 676 files (313 = one intentional Ruff reformat) | Whole tempdoc 888 CI-enforcement closure: SpotBugs+FindSecBugs job, FE typecheck/unit/lockdown, ruff, clippy, ~250 Java analysis fixes. Main's `ci.yml` has none of it; tempdoc 888 on main still says CHARTERED. 27 behind; rebase will conflict with #715/#712/#692 on `ci.yml`. Sub-branches `888-python/-rust/-hooks` are subsumed by it. |
| `codex/919-takeover` (`.claude/worktrees/919-takeover`) | 3 commits, 62 files | `NativeSessionHandle` rewrite, `jcstress` + `guardedByCanary` source sets, lifetime/fault tests. Nothing on main. The untracked 919 tempdoc in the main checkout marks §4.5 SUPERSEDED by 930, so scope needs a re-read. |
| `codex/926-hook-architecture-derisk` (`…/926-hook-architecture-derisk`) | 11 commits | Hook-policy half is superseded by 930 (#656). Doc-impact half (`governed-regions.v2.json`, `scripts/governance/gates/doc-impact/**`, tempdoc 924) was never on main. `codex/924-doc-impact-governance` is its strict ancestor. |
| `worktree-828-infra-pair` | 165 ahead, 1.2k lines | `governance/wire-consumer-manifest.v1.json` + gate + 327-line test, absent from main. Main's tempdoc 828 says §B "not started" — doc discrepancy. |
| `worktree-795-measurement-batching` | 98 ahead / 349 behind | `campaign_preflight.py` + 802-line test + 966-line tempdoc, never PR'd. A rebuild, not a rebase. |
| `worktree-818-critical-fixes` #404 | CONFLICTING, open since Aug 11 | 25/29 code files target `search-v2`, deleted by #486. Salvage: `ResultsCard.ts` `elaboration:'on-demand'` mode and the tempdoc record (14 findings, L7/L14 amendments, §5 sunset). Then close. |
| `codex/lane-d-pr-c1` (`F:/justsearch-public-pr-c1`) | 1 commit | int8 codec, deliberately REJECTED (tempdoc 931, #662 closed). Keep as record or delete. |
| `codex/lane-d-pr-b` (`F:/justsearch-public-pr-b`) | 2 commits | PR-B landed (#647) but `LabelStoreSurvivesRebuildTest.java` (88 l) is absent from main with no deletion in history. |
| `codex/lane-e-part1-campaign-draft-20260904` | 14 commits | Part-2 scaffolding intentionally reverted at 916 closeout. Likely delete. |
| `worktree-help-content-accuracy` | 54 lines, Jul 28 | Help-text corrections; every target file since rewritten. Re-derive or drop. |
| `worktree-dsh-mcp-probe` | 1 tempdoc | Tempdoc numbered 843, but main's 843 is a different topic (number collision). Renumber and land, or drop. |
| Takeover-verdict tempdoc branches: `worktree-903-takeover` (889 l), `codex/901-takeover` (305 l), `codex/892-takeover` (192 l), `codex/828-takeover` (119 l) | tempdoc-only | Verdict sections absent from main's copies. Ride-along in a batched `docs(tempdocs)` PR. |
| `backup/local-main-2026-09-06` | 297 commits | Rollback point from 940; contents fully triaged there. Set a retirement date. |

## 2. Uncommitted / untracked state that must be harvested BEFORE any worktree removal

| Location | State | Verdict |
|---|---|---|
| main checkout | 5 untracked files (blast-radius skills, tempdocs 919, 935) + `docs/observations.d/`, `models/onnx/ner/build.json`, `models/onnx/reranker-minilm-backup/`; 3 modified (`.codex/config.toml`, two MCP tests) | 919 tempdoc here (816 l) is newer than every branch copy. Only backup is `rescue/…`. |
| `F:/justsearch-worktrees/919-checker` (32 mod + 6 untracked), `919-lifetime-tests` (19+1), `919-splade` (14+7) | dirty | Mostly identical to `codex/919-takeover@3ac143703`; 3 files differ: `NativeSessionHandleFaultTest.java` (65 l), `JcstressSessionFixture` (7 l), LifetimeTest (2 l). Diff before deleting. |
| `.claude/worktrees/lane-F-b14-local-recovery` (8 mod + 1 untracked) | dirty | `ENGINE_UNRECOVERABLE` stop-evidence draft. Proven superseded by committed lane-F-A work (3 files byte-identical, rest lane-F-A is a superset). Owner confirm, then discard. |
| `F:/justsearch-public-worktrees/900-static-analysis-concurrency` (1 mod) | dirty | 343 uncommitted tempdoc-900 lines, committed nowhere. |
| `.claude/worktrees/951-codex-companion-archivist` (1 untracked) | dirty | `docs/tempdocs/951-codex-companion-archivist-roles.md`, committed nowhere. |

## 3. Delete-safe (content verified on main or deliberately abandoned)

### Local branches, mechanical set (94 landed + 7 empty)
Landed with merged PR, tip predates merge or content present: `campaign-live-4 codex/617-plan codex/893-hygiene-registers-publish codex/897-current-main codex/899-project-operations-publish codex/899-publication-closeout codex/906-publish codex/926-hook-architecture-publish codex/929-publication-outcome codex/929-publish-waste-deep-investigation codex/933-publication-outcome codex/933-publication-realization codex/934-runtime-client-publication-readiness codex/937-agent-model-routing codex/937-publication-record codex/capability-realization-ci-timeout codex/capability-realization-skill codex/905-release-unblock codex/921-permanent-review-record codex/921-queue-proof-record feat-771-entity-carriage fix-719-source-dedup fix-805-verify-capture fix-updater-pubkey fold-obs fold-observations fold-observations-2026-07-31 handoff-755 hero-run-2026-07-28 hygiene-gate-and-ci-instruments hygiene-ui-web-gate-reds maint-batch-2026-07-29 measure-790-real-pdfs numbers-refresh obs-fold-2026-07-28 observations-fold probe-789-2026-07-28 pub-624d pub-751q pub-755 pub-755e pub-756 pub-757 pub-757h pub-758 pub-cme pub-ratify publication-hero-2026-07-28 readme-v020 relaunch-prep relaunch-results round-12-record round-13-record skill-registry-clean worktree-748-g3-results worktree-760-codesigntool-ci worktree-772-installer-payload worktree-798-b4-tasks-reconnect worktree-798-ingest-livelock worktree-798-remaining worktree-801-stale-authority-sweep worktree-819-fingerprint-boot-race worktree-825-impl worktree-840-download-restructure worktree-840-prompt-cache-efficiency worktree-844-closeout-docs worktree-844-dev-surface-honesty worktree-854-w1 worktree-854-w2-record worktree-882-lane0-hygiene worktree-918-kernel-residue worktree-939-codex-sandbox-verifier worktree-948-retire-winget worktree-949-rule-relocation worktree-949-video-followups worktree-ce-skip-mislabel worktree-ce-skip-surfacing worktree-ce-swap-findings worktree-config-surface-advance worktree-conveyor-doc-sweep worktree-d004-register-v2 worktree-f053-bakeoff-record worktree-f053-ext-record worktree-hv-docs worktree-jseval-instrument-fixes worktree-lane-D worktree-lane-E worktree-lane-F worktree-lane-F-0b worktree-q020-syntax-harness worktree-release-sequence worktree-shard-tail worktree-w2-integrity-reconcile worktree-wave1-contracts`
Empty (0 ahead, no PR): `campaign-relaunch codex/lane-f-main-verification worktree-948-done worktree-951-codex-companion-archivist(after harvesting its untracked tempdoc) worktree-agent-a52d150be38d0165b worktree-f1-brand worktree-round14`

### Local branches, verified by subagents
`worktree-lane-F-design` (#720 cherry-picked into lane-F-A: `f463b540d 1304a854d 395078f04`), `codex/lane-f-host-ownership` (only a rustls dep removal lane-F-A deliberately reverses), `codex/888-python -rust -hooks` (⊂ 888-takeover), `codex/919-checker -lifetime-tests -splade` (branches ⊂ 919-takeover; worktrees dirty, see §2), `codex/924-doc-impact-governance` (⊂ 926-derisk), `codex/906-takeover -runtime-verification -search -mcp -ingestion` (#688), `codex/899-project-operations-onboarding -d1-win-bootstrap -d1-devcontainer-proof -d3-mcp-lifecycle -d4-succession -d5-diagnostic-summary` (#634/#640), `codex/897-format-breadth` (#694; one unpublished jseval run artifact `scripts/jseval/897-run-2026-09-04/enron-duplicate-prevalence.v1.json`), `codex/archive/897-realdocs-vdu`, `codex/893-hygiene-registers` (#630, later retired by #661), `codex/894-continuous-fuzzing`, `codex/900-static-analysis-concurrency` (branch only; worktree dirty), `codex/lane-d-pr-a -c0 -c2` (#645/#646/#657), `worktree-lane-d-pr-a` (#644 closed, landed as #645), `worktree-lane-D2`, `codex/archive/lane-E-baseline`, `codex/lane-e-part1-closeout-pre-origin-rebase-20260904`, `worktree-lane-E-part1` (landed as #643; **close stale draft #622 first**), `worktree-822-t3code-window` (#441 → re-published as #444), `worktree-903-non-nvidia-reread`, `worktree-887-improvement-landscape`, `worktree-901-sensitive-content` (all → #610), `worktree-932-pin-retirement` (→ #700), `worktree-agent-a5cf92a1ad3d218b1` (byte-identical to #698; delete after #698 merges), `worktree-agent-a6863b3fac476b013` (#702), `spike-post-sse` (self-labelled throwaway), `worktree-skill-registry-retirement` (#475), `worktree-767-injection-corpus`, `candidate-update`, `worktree-validate-all`, `worktree-round-16-candidate`, `pr305-fix`, `pr306-fix`, `lane-782-investigation`, `codex/927-publish-latency-retro` (tempdoc-only, 0 non-TD).

### Remote branches on origin
Delete: `worktree-wave3-draft` (→ #657), `worktree-wave3-c1` (#662 rejected by decision, evidence canonical on main), `worktree-round-17-candidate` (= candidate-update, #412–#415), `worktree-825-design`, `worktree-854-fusion-charter`, `worktree-resid2-worker-live`, `worktree-round6-preregistration`, `worktree-903-non-nvidia-reread`, `worktree-887-improvement-landscape`, `worktree-901-sensitive-content`, `worktree-lane-D2`, `worktree-lane-d-pr-a`, `worktree-932-pin-retirement`, `worktree-lane-F-design`, `codex/lane-f-b14-local-recovery`, `worktree-818-critical-fixes` (after salvage + close #404), plus the stray local ref `refs/remotes/pr549`.
Keep: `main`, `cla-signatures`, `rescue/…`, `worktree-lane-F-A`, `worktree-inference-host-design`, the three open-PR branches (#698 #699 #700) until merged, `worktree-lane-E-part1` until #622 is closed, dependabot branches (managed by dependabot).

### Worktrees removable once their branch is deleted (clean, branch landed/subsumed)
`.claude/worktrees/`: `897-current-main` (3.5 GB), `906-publish` (1.8 GB), `906-runtime-verification`, `921-publication-record`, `932-pin-retirement` (1.5 GB), `938-release-consolidation` (2.1 GB, on branch 948-retire-winget), `948-parallel-audits`, `949-video-followups`, `agent-a6863b3fac476b013`, `lane-F` (3.2 GB), `lane-F-0b` (2.2 GB), `lane-F-design`, `lane-F-host-ownership` (2.8 GB), `lane-F-main-verification`, `lane-F-review` (detached), `lane-d-pr-a` (1.5 GB), `lane-E` (2.1 GB, after closing #622), `903-takeover` (after publishing its tempdoc), `agent-a5cf92a1ad3d218b1` (after #698), `agent-a52d150be38d0165b` (after #700), `936-codex-sandbox-verifier` (after #699).
`F:/justsearch-public-worktrees/`: `888-python 888-rust 888-hooks` (≈2.4 GB), `897-format-breadth` (3 GB), `899-*` ×6, `937-agent-model-routing`, `937-publication-record`, `892-takeover` / `901-takeover` (after publishing tempdocs), `926-hook-architecture-derisk` (after decision), `900-static-analysis-concurrency` (after harvesting), plus unregistered `923-ui-naming` (695 MB, plain directory).
`F:/justsearch-worktrees/`: `906-takeover 906-mcp 906-search 906-ingestion` (≈3.4 GB), `919-checker 919-lifetime-tests 919-splade` (after diffing the 3 differing files).
`F:/justsearch-public-pr-b`, `-pr-c1` (after the two lane-D decisions).
Estimated reclaim: ~45–55 GB of the 84 GB, without touching lane-F-A, 888-takeover, 919-takeover, inference-host-design.

## 4. Open PR disposition
| PR | Verdict |
|---|---|
| #718 lane-F-A | keep open (draft); not ready, merge at stage F |
| #698 908 trend | merge now |
| #699 940 baseRef | rebase, merge |
| #700 932 v2 | rebase, merge |
| #622 lane-E-part1 | close (superseded by #643) |
| #404 818 critical | salvage 2 items, close |
| #579 setup-java | ready, 14 green |
| #59 #637 #638 | green, BLOCKED by queue only |
| #60 gitleaks | rebase |
| #228 #590 #673 | 1–2 red each |
| #580 gradle-deps, #674 npm-frontend | 7 and 4 red, real work |

## 5. Doc discrepancies surfaced
- Tempdoc 828 on main says §B "not started"; a full implementation exists on `worktree-828-infra-pair`.
- Tempdoc 888 on main says CHARTERED; 53 commits of implementation on `codex/888-takeover`.
- Why PR #720 was closed is recorded only in the PR comment, not in 936 docs.
- Tempdoc number 843 collides (`worktree-dsh-mcp-probe` vs main).
- 936 pointer tempdoc on main is silent on #718 status.

## Procedure note
Removal order per `branch-safety.md`: harvest §2 first, run `node scripts/dev/remove-worktree.cjs <path> --dry-run` from the repo root per worktree, then `--delete-branch`; remote deletions via `git push origin --delete <branch>`; `git worktree prune` for the 3 empty dirs after removing them; `rm -r` the unregistered `923-ui-naming` directly.

---

# Addendum 2026-09-10 (round 2): mergeability + supersession of the unique-content set

Lane F family excluded per owner (active). Mechanical test = `git merge-tree --write-tree --merge-base=<fork point> main <branch>`; for stranded-main-based codex branches the fork point is the merge-base with `backup/local-main-2026-09-06`, so the test squash-applies only the real unique work. Semantic supersession judged against main's tempdocs 930/938/940/948/950, ADR-0044, and shipped code. Main's own consolidation ledger is `docs/tempdocs/938-release-consolidation-before-architecture-change.md` §D (lines 239-285) — it independently agrees on close #404, 919 in flight, lane-D C1 delete.

## A. Merge-ready now (clean, not superseded)

| Item | Merge test | Superseded? | Note |
|---|---|---|---|
| PR #698 `worktree-908-token-efficiency-trend` | CLEAN, CI 14/14 | No; no equivalent on main; no 908 tempdoc on main | Time-critical: transcripts rotate ~30 d, each unmerged week loses history |
| `worktree-hook-wiring-repair` (4acf8fac4, no PR) | CLEAN | No; `hook-integrity` gate still registered (`governance/registry.v1.json:34-46`), rule absent, not covered by #719/#661 | Regression guard for dead `settings.local.json` hook entries; fixture-based test, passes on main by inspection |
| `codex/919-takeover` (3ac143703) | CLEAN (77 files, +4274/-1423) | No; only §4.5 fail-closed GPU superseded (930:287,331,414) and branch already absorbed that. Defect live on main: `NativeSessionHandle.close()` has no borrower check/join, untouched since PR #133 | Needs `build -x test` + ort-common suite; self-reported jcstress evidence unverified. Dirty 919-* worktrees are OLDER than the branch tip (02:48-03:18 vs 05:13) → discard. Untracked 919 tempdoc in main checkout (816 l, DESIGNED) is STALE vs branch copy (739 l, IMPLEMENTED) |
| `worktree-795-measurement-batching` | CLEAN | No; `821-root-cause-debt-charter.md:271` says "Rescue"; no preflight equivalent in jseval | Run the 802-line test file, then land |
| Takeover tempdocs: `worktree-903-takeover` (§8-12), `codex/901-takeover` (§M), `codex/892-takeover`, `codex/828-takeover` (§B part only; §A superseded by 862, branch-protection item by #625) | CLEAN, docs-only | Not on main; 938:257's "byte-identical" claim is about the worktrees, the branches do carry the sections | Batch into one `docs(tempdocs)` PR |
| `worktree-dsh-mcp-probe` tempdoc 843 | conflicts only in 3 unrelated tempdocs (branch-old copies) | Not covered anywhere; number collision with main's 843 | Land as a new number (952 is taken by the untracked 951? — check world-state), docs-only |
| `worktree-help-content-accuracy` | conflicts only README.md | Every corrected statement is still wrong on main | Rebase; drop its `docs/observations.d/` shard (store retired 872); 11 files not 2 |
| Untracked in main checkout: tempdoc 935, `blast-radius` skills ×2 + `agents/openai.yaml` | n/a | Not superseded; 935 cited by 949:12 and 948:244; blast-radius absent on main (747 is a different subject) | Byte-identical to `rescue/…` blobs → commit via a PR |
| Untracked 951 tempdoc (`.claude/worktrees/951-codex-companion-archivist`) | n/a | Active WIP dated today | Owner's live lane |
| Dirty 900 worktree (343 l) | n/a | Re-charter to "Incremental Java nullness enforcement", DERISKED 2026-09-03; 919 branch also edits 900 (+10) | Land 919 first, reconcile, commit |

## B. One trivial conflict each (wanted)

| Item | Conflict | Resolution |
|---|---|---|
| PR #699 `worktree-940-worktree-base-fresh` | `.claude/rules/branch-safety.md` one prose hunk (rule numbering) | Take branch side, renumber; main still `baseRef: "head"`; hook key path still exists in `agent-hooks.v1.json`; 940 tempdoc records the flip as the agreed remaining half |
| PR #700 `worktree-932-pin-retirement-v2` | `.claude/skills/publish/SKILL.md` regen drift from #719 | Take main's side. Pin-file deletions moot (a42d039ac, 534aaf506) and branch already says so; net-new still absent: `run-gh.mjs` `CI_WORKFLOW_NAME` guard, `RemoteKnowledgeClient.close()` walk-executor join + test |

## C. Wanted but NOT mergeable as-is (re-implement / cherry-pick)

| Item | Why not merge | Path |
|---|---|---|
| `codex/888-takeover` | 24 conflicts: 18 are the 313-file ruff reformat vs lane F's #708/#717 jseval edits; +LuceneRuntimeTypes.java, AgentRunStore.java, 4 regen files. Never PR'd. | Scope still wanted: main `ci.yml` has zero spotbugs/ruff/clippy/typecheck; 900:13 and 904:5 block on it; 930 endorses commodity linters. SpotBugs plugin already on main since v0.1.0 but warn-only and unconsumed. Fresh branch from main: cherry-pick `ci.yml` + `check-rust-toolchain-authority.mjs` + plugin hardening; regenerate the reformat with `ruff format`; re-check the ~250 Java fixes file-by-file (unknown applicability) |
| `worktree-828-infra-pair` §B | Branch merge reverts main's registry (`npm-audit`→`github-advisory-baseline`) and re-adds retired gates; 11 conflicts | Cherry-pick `f064e1084` only (8 self-contained files + manifest + 14-line registry add + CLAUDE.md row); main's `wire` gate is buf-breaking only, does not cover field-level consumer blindness; 828 §B still "open CHARTER" |
| `codex/926-hook-architecture-derisk` doc-impact half (= `codex/924-doc-impact-governance`) | 90 conflicts; patches `scripts/governance/{run,lib/*}` that #661 rewrote; registry resurrects deleted gates | Not superseded (930:159 routes the decision to 924; no governed-regions/doc-impact on main) but treat as a design spec and re-implement against the post-#661 kernel, or defer |
| `worktree-818-critical-fixes` #404 | Conflicts on ResultsCard.ts (would clobber F-052 `renderRerankDrop` from #510) | Hand-port only `elaboration:'on-demand'` (product call: search-v3 has its own version at `Sv3Main.ts:2497`, `sv3-honesty.ts:296`); 818 tempdoc record superseded (818:3-9 CLOSED, superseded-by 851); 938:264 says close #404 |

## D. Confirmed superseded / deliberately abandoned (no unique value)

| Item | Record on main |
|---|---|
| `codex/lane-d-pr-c1` (int8 codec) | 931:4 "int8 write default rejected … draft #662 closed, Float32 stays" |
| `codex/lane-d-pr-b` `LabelStoreSurvivesRebuildTest.java` | Shipped in #647 as `LabelStoreRegenerationKeepsUidKeysTest.java`; renamed on purpose (931 §C.4) |
| `codex/lane-e-part1-campaign-draft-20260904` | 916:4 "Part 2 is REFUTED and fully REVERTED" |
| Untracked 919 tempdoc in main checkout | Older than `codex/919-takeover`'s copy |
| Dirty `919-checker/-lifetime-tests/-splade` worktrees | Older than branch tip on all 3 differing files |
| `codex/905-operational-closeout` (named in 938 §D as release-critical) | Landed as PR #692, branch already gone |
| `worktree-inference-host-design` | Owner: lane F family, active |

## Verification gaps (not run, read-only session)
- No Gradle build was run for `codex/919-takeover`; merge is mechanically clean only.
- `worktree-795` and `worktree-hook-wiring-repair` tests were reasoned about, not executed.
- 888's ~250 Java analysis fixes were not checked file-by-file against current main.

---

# Addendum 3: root-cause investigation (2026-09-10)

## Shape of the problem
Two different leaks, not one.
- **Chronic branch leak (July→now):** 94 local branches whose PR merged, median 22 days ago (min 2, max 54). 95 local branches have a deleted upstream. 37 of the 94 had a worktree teardown recorded in `tmp/agent-telemetry/session-merges.ndjson` but the branch was kept; 57 have no teardown record at all.
- **Acute worktree pile-up (Sep 2→10):** every one of the 52 registered worktrees was created in those 8 days (`.git/worktrees/*` birth times). `world-state.mjs` already classifies 33 of them STRANDED-FINISHED and 3 DIRTY-IDLE.
- Teardown coverage over time: 413 of 654 merged PRs (63%) have a teardown record; ISO weeks 36–37 (Sep): 58 teardowns against 116 merges.

## Causes, ranked by contribution

1. **Local branch deletion was never in the loop.** Squash merges (ADR-0045) mean `git branch --merged` never reports anything, GitHub deletes only the remote branch, and `remove-worktree.cjs --delete-branch` is opt-in. 37 branches prove the pattern: worktree removed, branch left. Chronic since July (the `pub-*`, `fold-*`, `round-*` families).

2. **The removal instruction was weakened on Sep 6.** PR #691 (tempdoc 936 removal-safety) rewrote `branch-safety.md` merge step 5 from "Remove the worktree … delete local branches after verifying the merge … `remove-worktree.cjs <path> [--delete-branch]`" to "preview its exact registration: `… --dry-run`". The publish skill's last step (both harness copies) is conditional: "Clean up only worktrees and branches owned by this task, and only when the repository's safety rules permit it." Attribution now requires an explicit `--session-id` or the telemetry writer is skipped. The pile-up window starts the same week.

3. **Codex adoption (tempdoc 920, Sep 3) added worktrees without a lifecycle.** Codex has no EnterWorktree/exit prompt. The how-to says "feature work belongs in a dedicated worktree" and the takeover skill says "make sure you're working in a dedicated worktree", with no creation or removal mechanism, so sessions run `git worktree add` by hand into four roots (`F:/justsearch-public-worktrees` 17, `F:/justsearch-worktrees` 7, `F:/justsearch-public-pr-*` 2, plus 8 `codex/*` branches inside `.claude/worktrees`). 26 of 52 worktrees sit on `codex/*` branches; 38 of 61 `codex/*` branches were never pushed. Claude Code's periodic sweep never touches manually-created worktrees (official docs, worktrees.md "Clean up worktrees"). The takeover skill ends, by design, without implementation and hands the worktree "to the owner" (906 §T): six takeover worktrees (888/892/901/903/906/919) were created on Sep 5 alone and all six remain.

4. **Fan-out patterns multiply branches per tempdoc.** Sub-lane branches (899 ×8, 906 ×6, 888 ×4, 919 ×4, 897 ×3); separate publication branches (`*-publish`, `*-publication-record`, `*-publication-outcome`, `*-publication-closeout`) on top of the implementation branch for 921/926/929/933/937/899/906; and worktree reuse with `checkout -b` per PR: `938-release-consolidation` cycled 944→945→946→947→948 on Sep 7, `948-parallel-audits` 859→950→948-done, each leaving the previous branch. Reuse is why branch count (175) exceeds worktree count (52) even for recent work.

5. **The stranded-main episode (tempdoc 940, Sep 6) froze cleanup instead of triggering it.** `worktree.baseRef: "head"` (chosen in 618 when local fast-forwards were the publish path) let a 297-commit local `main` pollute 14+ branches. The realignment explicitly deferred hygiene: 938 §D "belongs to another session and was not touched", 938 item 11 "Consolidation hygiene, any time"; 936 removal-safety retired 10 checkouts and left "active, dirty, locked, and unaccounted-for worktrees". The prophylactic fix, PR #699 (baseRef → fresh), is still unmerged, so `.claude/settings.json` still reads `"head"`.

6. **Detection exists, remediation does not.** `world-state.mjs` prints STRANDED-FINISHED / STALE-CANDIDATE / DIRTY-IDLE per worktree, but no rule, skill, or hook instructs anyone to act on the verdict, and `branch-safety.md` rules 1 and 4 forbid touching another session's worktree. The safe default for every agent is therefore "leave it". Claude Code itself: `ExitWorktree` keeps the worktree; a named or dirty worktree prompts keep/remove at exit and "keep" is a valid answer; non-interactive runs never prompt (official docs).

## Where the 52 surviving worktrees came from
| Origin | Count | Examples |
|---|---|---|
| Codex manual `git worktree add`, external roots | 26 | 888-*, 899-*, 906-*, 919-checker/lifetime/splade, 892/901-takeover, 926, 937-*, pr-b/pr-c1 |
| Codex sessions using `.claude/worktrees` | 8 | 897-current-main, 906-publish, 906-runtime-verification, 919-takeover, 921-publication-record, lane-F-b14, lane-F-host-ownership, lane-F-main-verification |
| Claude named worktrees kept at exit / ExitWorktree / reused | 15 | 903-takeover, 932-pin-retirement, 936-codex-sandbox-verifier, 938-release-consolidation, 948-parallel-audits, 949-video-followups, lane-d-pr-a, lane-E, lane-F, lane-F-0b, lane-F-A, lane-F-design, lane-F-review, inference-host-design, 951 |
| Preserved subagent worktrees (changed, awaiting `cleanupPeriodDays`) | 3 | agent-a52d150be38d0165b (reused for #700), agent-a5cf92a1ad3d218b1, agent-a6863b3fac476b013 |
| Unregistered leftovers | 4 | 923-ui-naming (695 MB, no `.git`), 3 empty dirs |

## What would close the loop (for the owner to decide, nothing changed)
1. Post-merge branch deletion by content check, default-on: make `--delete-branch` the default in `remove-worktree.cjs`, and add a `prune-landed-branches` command that deletes local branches whose content diff against `origin/main` is empty (the squash-safe test used in this audit).
2. Restore the imperative in `branch-safety.md` step 5 and publish skill step 4 (both harness copies): remove the worktree and delete the branch after the content check, or record a named owner and reason for keeping it.
3. Codex: one sanctioned worktree root and a create/remove skill pair wrapping `prepare-worktree.cjs` / `remove-worktree.cjs`; the takeover skill ends by removing its worktree (the verdict lives in the tempdoc, which is the deliverable) or by naming the successor session.
4. Give `world-state.mjs` a remediation line per STRANDED-FINISHED / STALE-CANDIDATE and make 938 item 11 an owned, dated lane rather than "any time".
5. Merge #699 so new worktrees branch from origin.
6. Stop publication-record branches: fold record updates into the implementation branch or the periodic `docs(tempdocs)` batch the docs-ride-along rule already prescribes.

---

# Addendum 4: cleanup executed (2026-09-10, session 2e2e2351)

Archive before delete: `tmp/branch-archive-2026-09-10.bundle` (97 MB, `git bundle verify` OK) holds every deleted local and remote ref; `tmp/archive/923-ui-naming-tmp-reports-2026-09-10.tgz` holds the orphan directory's `tmp/` + `reports/` evidence. Restore any branch with `git bundle unbundle` / `git fetch tmp/branch-archive-2026-09-10.bundle <ref>`.

| Action | Count | Method |
|---|---|---|
| Worktrees removed (branch deleted with them) | 29 | `remove-worktree.cjs --allow-ignored --delete-branch --session-id …`, dry-run first, all rc=0 |
| Additional local branches deleted | 112 | `git branch -D` (content verified landed/superseded/abandoned-by-decision) |
| Remote branches deleted | 14 | `git push origin --delete`, skipped any with an open PR |
| PR closed | #622 | comment cites #643 and tempdoc 931 |
| Stray ref removed | `refs/remotes/pr549` | `git update-ref -d` |
| Empty dirs removed | 3 | `wave3-settle`, `844-dev-surface-honesty`, `819-fingerprint-boot-race` |
| Orphan directory removed | `F:/justsearch-public-worktrees/923-ui-naming` | after archiving evidence; tempdoc 923 is `status: complete` |

Before → after: local branches 176 → 36; remote branches 37 → 22; registered worktrees 53 → 24 (incl. main).

Untouched by design: lane F family (7 worktrees), `codex/919-takeover` + the three dirty 919 worktrees (pending the 919 merge), `888-takeover`, `926-hook-architecture-derisk`, `892/901/903-takeover` (tempdoc records to publish), `900` (dirty re-charter), `951`, `inference-host-design`, open-PR branches (#698 #699 #700 #718 #404), `worktree-hook-wiring-repair`, `828-infra-pair`, `795-measurement-batching`, `help-content-accuracy`, `dsh-mcp-probe`, `worktree-agent-a5cf92a1ad3d218b1` (until #698 merges), `rescue/…`, `backup/local-main-2026-09-06`, `codex/lane-e-part3-parked-candidate-20260904`, `origin/cla-signatures`, dependabot branches, the main checkout's untracked files.
