# Lane F handoff: implementation orchestrator

Start with the [2026-09-26 takeover](takeover-2026-09-26.md), then the
[continuation brief](continuation-brief.md) for ordering. This handoff owns
the current queue, revision, lease, and blocking decisions; D1 owns design and
acceptance evidence. The brief does not narrow the remaining lane scope.

## Orchestrator takeover (2026-09-30, Claude root)

The owner stopped the previous root and handed the lane to a Claude root with
this instruction: proceed autonomously with the re-plan (move the merge boundary
to D1 close so D2 becomes follow-on work on main, triage D1 into merge-critical
versus enabled-capability items, tier proof so installed runs happen at item
closure rather than per correction, and compact this handoff). Do not report to
the owner unless asked or genuinely blocked on an owner decision. The concrete
re-plan is recorded in the section below once the D1/D2 status audit returns.

Delegation: Codex is reached directly with
`codex exec -C <worktree> -s read-only|workspace-write -m <model> -c model_reasoning_effort=<e> -o <out> - < <brief>`.
Luna is `gpt-6-luna` (cheap, weaker: bounded exploration, mechanical tests,
artifact re-reads). Sol is `gpt-6.1-sol` (near-root quality, much cheaper than
root: design refutation, reviews, non-trivial implementation). Owner-specified
2026-09-30; always pass `-m` explicitly, because the repository role files
still pin the older `gpt-5.6-*` slugs. Each call carries about 20k tokens of
repository instructions. A Codex worker survives the death of the shell that
launched it; a killed wrapper (30-minute auto-background cap, or Claude Code's
memory-pressure reaper, now disabled in user settings from the next Claude Code
start) does not stop the work. Resume with `codex exec resume <session id>` only
after its log has stopped growing, never while the original process still writes.
Root keeps Gradle, the dev stack, integration and commits.

### Codex cost rules (owner, 2026-09-30; binding for every launch and resume)

Measured: about $114 of Codex spend in the first four hours, of which about $90 came from
sub-agents the Sol workers spawned under the stale `gpt-5.6-*` role pins. About 70% of spend
was cached-input re-reads while workers polled for a Gradle grant.

1. **No nested spawning.** Every `codex exec` and `codex exec resume` carries
   `-c agents.enabled=false`, and every brief says "Do not spawn sub-agents; do the work
   yourself." Probe on 2026-09-30: with the override Codex reported spawning unavailable and
   created no child session (`01a0f3ad-4bae-...`); the control without it created one
   (`01a0f3ad-7b8c-...` -> child `01a0f3ad-97e2-...`).
3. **No idle waiting.** A worker never polls for a Gradle grant. Build-bound work is phased:
   the worker edits, then ends with `READY FOR BUILD: <exact commands>` and exits. When Gradle
   is free, root issues the grant and runs `codex exec resume <id> -c agents.enabled=false`
   with "You hold the Gradle grant; run: ...". Launch a worker only while it has non-build work.
   Brief template: `C:/Users/Elias/AppData/Local/Temp/cx/brief-rules.md` (copy its lines into
   every brief).
5. **Visibility and budget.** Every status report counts actual Codex sessions, children
   included (`node C:/Users/Elias/AppData/Local/Temp/cx/codex-status.js`). After each worker
   finishes, record its API-equivalent cost below from the session's final
   `total_token_usage` at $/1M (uncached in / cached in / out): gpt-6.1-sol 2 / 0.10 / 10,
   gpt-6-luna 0.10 / 0.01 / 0.50, gpt-5.6-sol 4 / 0.40 / 20, gpt-5.6-luna 0.20 / 0.02 / 1.20.
   Note the Codex weekly `used_percent`; tell the owner if it passes 50% before the week resets.
6. **Model tiering.** Luna (`gpt-6-luna`) for every read-only inventory, contract check, doc
   edit and mechanical change. Sol (`gpt-6.1-sol`) only for design, non-trivial implementation
   and refute-first review.

Role pins: PR #735 moves `.codex/agents/*.toml` and `default_subagent_model` to
`gpt-6.1-sol` / `gpt-6-luna`; owner merges it. Until then the override in rule 1 is what
prevents stale-pin children.

Per-worker cost ledger (self + children, API-equivalent):

| Worker (session) | Model | Self | Children | Note |
|---|---|---|---|---|
| reconfigure impl + fixes (`01a0f2e5`) | gpt-6.1-sol | $6.81 | 6, $54.63 | children on gpt-5.6-sol |
| D1 closure (`01a0f35a`) | gpt-6.1-sol | $3.19 | 8, $38.41 | first phase done without Gradle (old polling brief timed out); resumes with the grant under the new flags |
| help source + fixes (`01a0f2bb`) | gpt-6.1-sol | $4.61 | 0 | grant polling heavy |
| WP2 2b + fixes (`01a0f2ed`) | gpt-6.1-sol | $5.60 | 1, $0.23 | done; merged `ac6c26ed9` |
| PMD cleanup (`01a0f3df`) | gpt-6-luna | $0.03 | 0 | edits rejected (dropped try-with-resources); root redid; merged `860cf0fd0` |
| F-1/F-2 docs (`01a0f37a`) | gpt-6.1-sol | $1.63 | 6, $2.82 | |
| F-3/4/5 (`01a0f39e`) | gpt-6.1-sol | $1.88 | 1, $0.55 | done; merged `82b5fa129` |
| D1/D2 audit, reconciliation, 4 reviews | gpt-6.1-sol | $6.25 | 6, $7.57 | read-only |
| E1, WP2 2c, callers, contract, sweep, 2e | mixed | $1.09 | 0 | |
| D1 closure proofs: hosted fixes, harness, pre-walk race, retained state, proofs (`01a0f52b`..`01a0f597`) | gpt-6.1-sol | $13.58 | 0 | 2026-10-01; all merged |
| E driver, readiness, fixture reuse, E2-E3 redesign, per-stage E3, pair identity (`01a0f5ff`, `01a0f61c`, `01a0f62a`, `01a0f662`) | gpt-6.1-sol | $4.23 | 0 | 2026-10-01; merged |
| E4-E6 instruments design + implementation (`01a0f61a`) | gpt-6.1-sol | $3.08 | 0 | 2026-10-01; merged `f360f7b2e` |
| F-1 residue sweep + review fixes (`01a0f673`) | gpt-6.1-sol | $2.76 | 0 | 2026-10-01; merged `6c9c24d5b` |
| F-1 residue refute-first review (`01a0f68e`) | gpt-6.1-sol | $0.78 | 0 | read-only; 5 findings, all fixed |

Codex weekly `used_percent`: 21 at 22:20 on 2026-09-30; 25 at 11:40 on 2026-10-01. Total API-equivalent
since takeover: $175.40 (no live sessions at that reading).

## State at takeover (2026-09-30; the dated sections below supersede it where they differ)

The narrative evidence ledger through `ca12f00e6` moved verbatim to
[handoff-history-2026-09-21-to-30.md](handoff-history-2026-09-21-to-30.md).
Read it only to resolve a specific uncertainty; the facts that still govern are here.

- **Branch:** `codex/lane-f-pr1` at `ca12f00e6`, pushed, draft PR727; `origin/main`
  (`da79f8d57`) is an ancestor. Last runtime checkpoint `ef636fa3a`. Worktree held.
- **Hosted:** run 36700317380 passed 12 jobs. Public claims is red only on the
  Search v3 promotion deadline owned by tempdoc 852 / decision 851, not by lane F.
  Do not move the date or fabricate `governance/window-cutover.done`. If that check
  still gates the merge queue at F, it is an owner decision.
- **Stack:** ABSENT, no foreign runs or inference orphans (quick_health 2026-09-30).
- **Accepted stages:** A, B, C1, C2. D1 open. D2, E, F not started in production.
- **D1-16 harness:** 18 installed scenarios; one pending row,
  `reconfigure-beside-in-place` (D1-4/D1-12/D1-14).
- **Known open D1 defect:** D1-17 help-source omission (startup help is not a
  watched root, so live Green enumeration omits it). Direction: derive the
  immutable `justsearch-help` collection from `ResolvedConfig.Paths.ssotPath()/docs/help`
  and carry its label through Bootstrap and Green admission (archive lines 125-141).
- **D2-5 private drafts** (tmp/lane-f-d2-draft, tmp/lane-f-d2-port-draft,
  tmp/lane-f-d2-port-draft-tests, with evidence JSON) are reviewed and green
  privately. Preserve them; they are not production code. Copied to local-only
  branch `codex/lane-f-d2-drafts` (`039a4bbb2`, under `docs/design/lane-f-engine-jvm/d2-drafts/`,
  committed without hooks; never push it without running them).
- **Gradle ownership:** while a delegated worker holds a Gradle grant (named in
  its brief), root runs no Gradle. Current grant: the reconfigure worker (`codex/lane-f-reconf-inplace`), via the file
  `tmp/grants/gradle-reconf`; root deletes the file to revoke it.
- **WP2:** retirement contract implemented and reviewed. Signed installed
  predecessor-to-target proof needs the external signing/AppControl environment.
- **E:** nothing run. E1 instrument fixes are in progress on child branch
  `codex/lane-f-e1` (worktree `.claude/worktrees/lane-f-e1`, Sol worker).
  E4 needs an owner-set soak duration before it runs.

## Checkpoint 2026-10-01 (lane head `2babb36ce`, pushed to PR727)

Integrated since the takeover and green together: D1-17 help source and its review fixes, settings callers,
memory-aware in-place reconfigure (D1-4/12/14/16) with its review fixes, D1-8/D1-9 closure assertions and
the Blue-lifetime production fix, WP2 2b/2c/2e, E1 instruments, G1 collector, stage F-1..F-5.
Full integrated build and suite at `2babb36ce`: 1,879 suites, 12,482 tests, 0 failures, 0 errors, 33 existing
skips (`tmp/lane-f-batch4-full-suite.log`, counts `tmp/lane-f-batch4-full-suite-counts.json`); 6 test tasks
executed, 28 reused unchanged results. ui-web gates 27/27 at `860cf0fd0`. Earlier red runs and their XML are kept
at `tmp/lane-f-batch2-full-suite-red-xml/` and `tmp/lane-f-batch3-red-xml/`. Independent reviews: batch 1-3 under
`evidence/` (batch 3 left one SHOULD-FIX, fixed in `fcb99659f` line).

D1-8f: satisfied structurally ([disposition](evidence/D1/d1-8f-disposition-2026-10-01.md)).
Open before D1 closes: the installed standard-model round (in progress, groups of at most 30 minutes via
`:modules:system-tests:lifecycleIntegrationTest -PincludeAiTests=true`), D1-6
grep and baseline-shrink receipts, D1-17 legacy teardown, the library-gap-decision ui-check, exact-SHA hosted CI.

## D1 closed (2026-10-01)

D1 is closed at runtime revision `f7b4d5a21`: full suite 12,493 tests green at `51fff0a62`, all installed
lifecycle and supervised recovery scenarios green on current revisions
([installed round](evidence/D1/installed-round-2026-10-01.md)), hosted CI 36820295770 green on `f7b4d5a21`,
independent [closure review](evidence/D1/closure-review-2026-10-01.md) and
[confirmation](evidence/D1/closure-confirmation-2026-10-01.md). Authorized deferrals: D1-6 signed
restart-required proof to E7; D2-owned portions post-merge. Production defects found and fixed while
closing: Blue closed under an issued query, pre-walk ingest recovery race, help source omitted by Green,
CLI boots skipping the data-version marker, reconfigure cleanup Error leaking the A holder.

## Stage E and F state (2026-10-01, lane head `a604f70af`)

**E tooling.** Driver `scripts/jseval/lane-f/e-run.mjs` with E4-E6 instruments (`e456-*.mjs`,
`scripts/supervisor-conformance/{verified-crash,jdwp-fault}.mjs`), bulk load (`scripts/jseval/jseval/bulk_load.py`),
and measurement-based pair identity (`e-pair-identity.mjs`). Main arm: worktree `lane-f-e-main` at `ac1c93bf3`.
Decisions made while running it (each recorded in `stages/E.md` §2):
- E2/E3 measure **during** bulk indexing: one invocation per workload, a fixed 20-minute window (hybrid then
  lexical), the window invalid if indexing finishes inside it. The first design waited for full enrichment and
  passed the 59-minute run limit on main.
- E3 rates each indexing stage (primary, embed, SPLADE, chunk, NER) over its own active interval: main embeds
  whole documents before chunks, so chunk rate alone was order dependent (main recorded 0 chunks/s).
- Pair identity hashes the normalized plan and the bytes of the instruments it executes, not scoring code; every
  checkout path normalizes to one placeholder (the branch arm is the tooling root). `reproject` re-scores a
  record from its raw files without changing its identity.
- GC logging uses a quoted `-Xlog` file name; an escaped drive colon stops JDK 25 from starting.
**Runs.** Records before the pair-identity change do not pair and are kept only as history. E1 main recaptured
(`2026-10-01T08-53-13-317Z-c51decb0`). The full paired queue (`tmp/q-full.txt`: main E2 x2, E4 x3, e0-values,
branch E1, E2 x2, E4 x3, e4-hang-values, E5 and E6 on both arms, table) runs serially from
`tmp/lane-f-e-queue.mjs`; log `tmp/lane-f-e-queue-q-full.log`. To stop it, create
`tmp/lane-f-e-queue-q-full.stop` (it exits between items). **Never kill the runner**: its e-run child dies with
the pipe and leaves an owned stack (stop such a stack with `dev-runner.cjs stop --run <id> --session-id
lane-f-e-<invocation>` from the arm's tree).
**F.** F-1 exits 0 at `a604f70af`: 1,373 hits dispositioned (rewrites, renames, and a reviewed allowlist
`governance/lane-f-residue-allowlist.v1.json` whose entries are exact paths with per-occurrence line anchors),
[dispositions](evidence/F/residue-dispositions-2026-10-01.md), [review fixes](evidence/F/residue-refutation-2026-10-01.md).
F-5 lease holder `WORKER` -> `INDEXING` on `/api/status`. Residue branch verified: build and tests green, ui-web
6,637 unit tests and 27/27 gates, wire gate, docs-validate, lint. Remaining F: F-6, F-7 (needs E's table), F-8;
pruning the 36 stale `io.grpc` rows of `gradle/verification-metadata.xml` (no dependency remains; needs Gradle);
AOT cache training flags lack G1 and compact headers.

## Re-plan (owner-authorized 2026-09-30)

Supersedes the WP3 order in [continuation-brief.md](continuation-brief.md) for everything
after D1. It changes the merge boundary, not any D1 acceptance clause.

**Merge boundary.** The one Engine merge happens after D1 closes, WP2 release safety lands,
E's seven paired groups pass and F's sweep is done. **D2 (D2-1..D2-10) moves to post-merge
work on main**, as ordinary PRs. Basis: the independent Sol audit of 2026-09-30
([evidence/replan-audit-2026-09-30.md](evidence/replan-audit-2026-09-30.md), Sol `gpt-6.1-sol`, read-only, at `ca12f00e6`) found no D2 item required by E1-E7
(`stages/E.md:170-176`) and no existing-user regression caused by omitting D2. D2-2 only
feeds the descriptive verification-boot row of `evidence/E/development-cost.md`, which
records it as not measured. The production NRT projection seam
(`KnowledgeClient.java:533`, used by D1-9) stays; DURABLE stays refused
(`WorkerIngestService.java:275`) until D2-5 lands post-merge from `codex/lane-f-d2-drafts`.
Design section 17.3's D2 row and the §16 rows D2 owns (request-time encoders,
durability, aggregate cursor bound, client re-entry, verification boot) become
post-merge feature acceptance, not merge conditions. Stage F records this as an
authorized deferral with that destination.

**D1 triage.** Every open D1 item is merge-critical; D1-18 is accepted and not critical.
Remaining, by audit status and size:

| Item | State | Remaining | Size |
|---|---|---|---|
| D1-4, D1-12, D1-14, D1-16 | partial | beside/in-place reconfigure under the device ceiling; the one pending harness row `reconfigure-beside-in-place` | L |
| D1-17 | partial | help source in Green enumeration (worker running); legacy cutover teardown; dead-code baseline shrink; full suite | M |
| D1-2, D1-6, D1-7, D1-8, D1-9, D1-10, D1-11, D1-13 | implemented, unproven | acceptance reconciliation against current-revision evidence; D1-6's signed Sandbox proof moves to E7's signed round | S-M each |

**Proof tiering.** Deterministic in-process and harness scenarios gate each correction.
Installed standard-model runs happen once per item at closure. Hosted CI runs at batch
checkpoints, not per commit. Independent Sol review per batch; refute-first review is kept
for genuine design ambiguity.

**Contract changes to verify before F** (audit Q2): `POST /api/settings/v2` now requires an
operation key and witness (`SettingsController.java:112`, `SettingsServiceImpl.java:196`);
`/api/worker/restart` returns 410 (`InferenceHandlers.java:686`); `/api/inference/reload`
is gone. Confirm each is an intended, documented contract change (API contract map,
release note, every first-party client including MCP and CLI) or fix it.

**Queue (root owns order; workers in child worktrees off `ca12f00e6`, integrated by root):**
1. D1-17 help source (Sol, `codex/lane-f-d1-help`, holds the Gradle grant).
2. E1 instrument fixes (Sol, `codex/lane-f-e1`, no Gradle).
3. WP2 2c downgrade sandbox round (Sol, `codex/lane-f-wp2c`, no Gradle).
4. WP2 2b settings backup plus data-version marker (brief ready, needs the Gradle grant next).
5. D1-4/12/14/16 reconfigure beside/in-place. Remaining gap: memory-aware ordinary
   reconfigure of query-only encoders ([design pass](evidence/D1/reconfigure-remaining-design-2026-09-30.md)).
   Decision 2026-09-30: implement the in-place hot swap (Proposal A, about 450-700 lines), not a
   requested-restart fallback, because the fallback drops API connections (design.md:2312),
   loses failed-B-restores-A and violates D1.md:372 and :2524
   ([refutation](evidence/D1/reconfigure-restart-fallback-refutation-2026-09-30.md)).
   Sol is implementing in `codex/lane-f-reconf-inplace`; its Gradle use waits for
   `tmp/grants/gradle-reconf` (root creates it after the help worker finishes).
6. D1 close. [Reconciliation](evidence/D1/acceptance-reconciliation-2026-09-30.md) shows most
   clauses proved; remaining closure work:
   a. New assertions (Sol worker, Gradle): D1-9 fresh-start capacity refusal with the
      retained-state reason (D1.md:1871); D1-8 held-lease timeout branch, generation-count
      trace (<= 2, then 1), per-response generation witness in the swap search loop, manifest
      instance id unchanged, exact cut/writable-B/sealed-queue/settlement ordering, and the
      acquisition/swap fault matrix (D1.md:1620-1653).
   b. D1-13 installed held-native controlled-exit scenario (written by a worker, run by root).
   c. D1-6 retirement grep receipt and a dead-code baseline shrink receipt; D1-17 sweep.
   d. Root installed round after reconfigure and help merge (proof is stale after `1ac1407ef`):
      recovery release/exhaustion pair, `migrationStartsLiveInTheInstalledEngine`,
      `issuedASearchCompletesAfterBesideBServes`, watcher mutation, parser gap, both
      low-memory pointer cuts, the new reconfigure scenario, held-native exit; record
      restoration time against the reconfigure budget; current `library-gap-decision` ui-check.
   e. One exact-SHA hosted CI run; D1-6 signed restart-required proof moves to E7.
7. Contract-change verification (above; Luna check running), E values (owner sets the E4
   soak duration), E1-E7, F.
8. AOT cache fidelity (needs Gradle): `modules/ui/build.gradle.kts:1070-1083` trains and
   assembles the cache with neither the collector nor `UseCompactObjectHeaders`, while both
   spawn sites run `-XX:+UseCompactObjectHeaders` and now `-XX:+UseG1GC`. A header-mode
   mismatch can make the JVM ignore the cache. Verify with `-Xlog:aot` on an installed dist
   whether the cache loads, and align the training flags with the spawn set.

Running Codex sessions (resume with `codex exec resume <id>` from the worktree only after its log stops growing):
help review fixes `01a0f2bb-6611-7850-abea-6ced24298a09` (`lane-f-d1-help`, holds `tmp/grants/gradle-help2`);
WP2 2b `01a0f2ed-988d-73d1-94c5-f4dcc6fd9214` (`lane-f-wp2b`, next grant `gradle-wp2b`);
D1 closure assertions `01a0f35a-ab44-78d0-93b6-3cc776dad953` (`lane-f-d1-close`, then `gradle-d1close`);
reconfigure fixes `01a0f2e5-978c-7492-9ab9-42adaeb5abbc` (`lane-f-reconf`, grant `gradle-reconf2`) for the
[review](evidence/D1/reconfigure-review-2026-09-30.md): two blockers (CUDA realized after commitment;
restoration Error bypasses recovery) and five should-fix; API fields move into a nested `composition`
object. Gradle order: reconf2 (now), wp2b2 (WP2 2b review fixes: CLI boot bypasses the marker;
create-once backup; [batch 2 review](evidence/batch2-review-2026-09-30.md)), d1close. Implementation `5ccfa74a8` is not merged.

Done this session: D1-17 help source in Green (`1ac1407ef`, merged `63b83ffad`; integrated
build green at `tmp/lane-f-help-integrated-compile-r2.log`, focused 212 tests at
`tmp/lane-f-help-focused-integrated.log`); settings callers and API docs (`6dda15e24`); E1 instruments (`9919dde24`), WP2 2c downgrade tooling (`dacecd8ca`),
Engine collector switched to explicit G1 at both spawn sites per design section 8
(`bb399e495`; `test-dev-runner-head-java-opts` passes; Rust compile is hosted-only).

Search v3 deadline: moved to 2026-11-30 by owner decision 2026-09-30; PR #729 merged as
`ac1c93bf3` (with the brace-expansion lockfile fix for main) and merged into this branch at
`f80fd1f69`. The required `Public claims` check is green again on main.

Contract-change check (Luna, 2026-09-30): all three changes are design-authorized. Broken
first-party callers: `scripts/jseval/jseval/utility_judge.py:568` and
`scripts/ci/verify-installer-nsis-win.ps1:826` post settings without witness/key; API docs
and CHANGELOG are stale. A Luna fix is running in `codex/lane-f-settings-callers`.

## Evidence and owner map

| Concern | Governing record |
| --- | --- |
| Predecessor teachings and honest self-audit | [Resumption contract](evidence/C2/resume-2026-09-21.md) |
| In-depth workflow findings, measurements and proposed trial | [Workflow retrospective](evidence/workflow-retrospective-2026-09-22.md) |
| C2 accepted proof, including installed/stress/hosted/real-model | [C2 acceptance](evidence/C2/verification-2026-09-21.md) |
| D1 actual owners and captured configuration | [Owner map](evidence/D1/regrounding-2026-09-21.md), [component plan](evidence/D1/component-plan-2026-09-21.md), [wiring proof](evidence/D1/owner-wiring-2026-09-21.md) |
| Broad affected-module proof2298 at6c95d7989 | [Integrated verification](evidence/D1/owner-integrated-verification-2026-09-21.md) |
| Full-snapshot CAS and stateless reason retention | [Publication seam](evidence/D1/publication-seam-verification-2026-09-21.md) |
| Pure schema2 projection, trigger feedback and tool composition | [Schema/trigger proof](evidence/D1/schema-trigger-verification-2026-09-21.md) |
| Physical-health initialization and close/retry ownership | [Bootstrap proof](evidence/D1/bootstrap-initialization-verification-2026-09-21.md) |
| Runtime readiness and six-state decisions | [Readiness plan](evidence/D1/readiness-plan-2026-09-21.md) |
| Schema and host migration | [Schema consumers](evidence/D1/schema2-consumer-plan-2026-09-21.md), [host plan](evidence/D1/host-readiness-plan-2026-09-21.md) |
| Apply classification and audited missing readers | [Apply-register plan](evidence/D1/apply-register-plan-2026-09-21.md) |

## Working rules to retain

Start with the acceptance path, then reuse actual owners. Delegate bounded files
and deliverables; consolidate review and reassess after two substantive rounds.
Root owns shared state, stack and Gradle. Freeze compiled sources before a build.
Run focused checks while correcting and integrated checks at coherent boundaries.
Full suites use default parallelism; `--max-workers=1` is a diagnosed-contention
rerun only. The 2026-09-28 owner briefing allows frozen-slice review to start
alongside the integrated gate, but reviewed and tested source-tree hashes must match;
collect independent static failures using `spotlessCheck pmdAll --continue`.
Workflow correction `b4d01c1cc` already records this in canonical guidance and both
CI-triage skills; no extra always-loaded policy copy is needed.

Keep compiling checkpoint commits per item, push each checkpoint and preserve WIP
at least hourly. Stage explicit paths; check native exit codes before mutations.
Preserve XML before reruns. Name tested revision, reuse, failures, skips and proof
tier; BUILD SUCCESSFUL or hosted job success alone is not test-level acceptance.
Refute expected-looking passes and reviewer mechanisms against the actual owner.
Do not replace a concurrency defect with an unnecessary marker, store or timer.

Discover paths with `rg --files`; use `-g` for filename globs and bounded excerpts.
Use UTF-8 editing. Root has repeatedly failed to apply the path/output discipline;
more wording is not evidence of improvement. The concrete context correction here
is to keep this handoff current, with history in a separate archive.

Raw logs/XML under this worktree's `tmp/` remain accessible through lane acceptance
plus30days; export before deleting the worktree. Use `--no-ignore` to discover
ignored evidence. Hashes supplement accessible artifacts rather than replace them.

[Historical handoff archive](handoff-history-through-2026-09-21.md) preserves the
prior1850-line record and old decisions. It is background, not a resumption queue;
read only the historical section needed to resolve a specific uncertainty.
