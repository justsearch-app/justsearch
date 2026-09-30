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
repository instructions. Root keeps Gradle, the dev stack, integration and commits.

## Current state (2026-09-30)

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
6. D1 acceptance reconciliation for the implemented-unproven items, then the D1-17 sweep and D1 close.
7. Contract-change verification (above; Luna check running), E values (owner sets the E4
   soak duration), E1-E7, F.
8. AOT cache fidelity (needs Gradle): `modules/ui/build.gradle.kts:1070-1083` trains and
   assembles the cache with neither the collector nor `UseCompactObjectHeaders`, while both
   spawn sites run `-XX:+UseCompactObjectHeaders` and now `-XX:+UseG1GC`. A header-mode
   mismatch can make the JVM ignore the cache. Verify with `-Xlog:aot` on an installed dist
   whether the cache loads, and align the training flags with the spawn set.

Running Codex sessions (resume with `codex exec resume <id>` from the worktree if cut off):
reconfigure in-place `01a0f2e5-978c-7492-9ab9-42adaeb5abbc` (`lane-f-reconf`, holds
`tmp/grants/gradle-reconf`); WP2 2b `01a0f2ed-988d-73d1-94c5-f4dcc6fd9214` (`lane-f-wp2b`,
Gradle gated on `tmp/grants/gradle-wp2b`, issued after reconfigure releases).

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
