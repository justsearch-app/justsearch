# Lane F handoff: from the design orchestrator to the implementation orchestrator

**Current authority (owner, 2026-09-08): merges are delegated to the orchestrator.**
The earlier per-PR approval requirements below are historical and superseded.
The orchestrator verifies, reviews and merges autonomously through the repository
queue; no owner reply is needed for #708 or #717.

Latest state: [Publication and recovery checkpoint](#publication-and-recovery-checkpoint-2026-09-08).

Two handoffs live in this file. The first (2026-09-07) is from the design orchestrator to the
first implementation orchestrator and is kept as written. The second, **"Implementation
orchestrator handoff (2026-09-08)"** at the end, is from that orchestrator to its successor and
is the one to read first if you are taking the lane over mid-stage-B: it records the state of
every branch and PR, the in-flight work, the review findings that are decided but not yet fixed,
and the working practices that were never written down anywhere else.

Written 2026-09-07 by the agent that orchestrated the design through seven reviews and the
lock. The design is the contract; this file is what that agent knows and the contract does
not say. Read `design.md` sections 0, 15, 16 and 17 first, then this file, then
`verified-facts.md` when a stage touches the code it cites.

## What exists, and where

- `design.md`: the locked contract (status line, section 0 lock paragraph, 15 last bullet).
- `verified-facts.md`: code facts with `file:line` citations, verified at `b96cd999`.
- `docs/tempdocs/936-lane-f-engine-jvm-design.md`: a pointer, kept so cross-references resolve.
- All three are **untracked and uncommitted** in the `lane-F` worktree on branch
  `worktree-lane-F`, which has zero commits of its own and can fast-forward to `main`.
- Nothing has been implemented. No PR exists. No baseline exists.

## Rules the owner set, still binding

1. **Nothing merges without an explicit, per-PR go-ahead** from the owner. PR 0 and PR 1 each
   need their own. Between stages, the owner's "continue" authorises the next stage and nothing
   else (17.6).
2. **The design is locked.** A change inside a decided line (a mechanism detail, a citation)
   proceeds with a dated line in section 0 naming the stage that found the error. A change to a
   decision (15), a gate row (16) or the stage table (17.3) waits for the owner's word before
   the stage continues (17.6). Do not re-litigate 15 or 17.7; every value there is the owner's.
3. **All other development is halted** while the branch is open. 17.8 names what re-cuts the
   sequencing if that stops being true.
4. **Starting values are not design facts.** 17.7's numeric starting values (p95 plus ten
   percent, two hours' soak, thirty days' retention) are instantiated from the PR 0 baseline
   and confirmed by the gate run. Do not invent a number where 17.7 says the baseline supplies
   it, and add no allowed-difference class after a diff is seen.

## The order of work

1. **Bring the design files onto a branch.** From inside the `lane-F` worktree,
   `git merge --ff-only main` (the branch has no commits; the untracked files survive), then
   `git add` the three files explicitly and commit. Add one line for `docs/design/` to
   `docs/llms.txt`; no gate covers the directory today and nothing indexes it.
2. **PR 0 (17.2)** on that branch: launch flags at both spawn sites, the workflow fixture and
   its mode-agnostic runner, the design files riding along. Measure the flag change before and
   after on the 917 Derisk 1 procedure. Ordinary review, ordinary go-ahead.
3. **Baseline capture on `main` after PR 0 merges**, into
   `docs/design/lane-f-engine-jvm/evidence/baseline/`: jseval search-quality baselines, the
   fixture's output, the brief v2 performance list, request-time encoder latencies, the Worker
   restart rate from `worker.log`. Stage A does not begin until this exists.
4. **PR 1** in a **fresh worktree cut from post-PR 0 `main`**, not from `lane-F`. Stages A to F
   per 17.3, one agent at a time in that worktree (never-share-worktree), sessions handing off
   through `stages/<letter>.md` and `evidence/<letter>/`. Commits per checklist item.
   Independent review of each stage's commit range by an agent other than the implementer,
   recorded in the managed review record, findings fixed before the go-ahead is requested.
5. **Stage E runs, it does not build.** `/jseval` and `/dev-stack` under a campaign lease.
6. **Stage F** ends with the report-back (19) per `00-program-overview.md`.

## Things the contract does not say, or says quietly

- **`main` has moved 12 commits past the verified base** (`b96cd999` to `59ae966d` at the
  time of writing; 316 files, mostly 941 sandbox rounds and the 0.3.0 release). Seven files the
  design cites changed: `IndexingService.java`, `DocumentService.java`,
  `GplJobCoordinator.java`, `IndexingLoop.java`, `BackfillScheduler.java`, `indexing.proto`,
  `SqliteJobQueue.java`. Spot-checked 2026-09-07: the cited facts hold with shifted line
  numbers (the `supplyAsync` site in `DocumentService` moved from 74 to 85; the
  `commit` then `drainPending()` precedent in `IndexingLoop` sits at 723 rather than 705 to
  722). 17.6 already requires re-verification at each stage start; these seven are where to
  look first. `contracts/wire/knowledge.proto` and `status.proto` gained one line each.
- **The design is not a tempdoc.** No size cap, no append-only convention, and no tempdoc
  gate runs on it. `docs-validate` does cover `docs/design/` (it walks `docs/**` and exempts
  only tempdocs; corrected at PR 0), but only for front matter, headings and links. The prose
  sweep at stage F is what keeps the content honest; nothing automated does.
- **Two registers must be loaded before the work and updated before the lane closes**:
  `/search-quality` and `/inference-runtime` (CLAUDE.md, Skills). Stage D2's request-time
  paths and stage E's measurements touch both.
- **Runtime files.** `supervisor.v1.json` next to the port manifest is a new `<dataDir>/runtime/`
  file; `check-runtime-manifest-closure` must know it, and the design asks that the check's
  `SKIP_PATHS` be narrowed so the supervisor file's writers are checked (stage B).
  `dev-runner.cjs` and `lib.rs` are skipped today (`:73`, `:80`).
- **Stage A is the risk.** The unplug deletes the wire, the MMF bus, `WorkerSpawner`,
  `SupervisionPolicy`, `WorkerProcessManager` and its 20 tests, and supersedes ADR-0001 and
  ADR-0002 in one change. 17.8's stop rule applies: if stage A's checklist outgrows the 917
  consumer audit, stop and re-read 3 and 6 before more code. Time-to-complete is an
  architecture signal (`agent-lessons.md`).
- **The 17.4 code inventory is the starting point for D1**: `IndexGenerationManager` has
  generational directories and an atomic `state.json` swap; the cutover restarts the Worker
  today (`KnowledgeServerMigrationOps.java:258-266` at the base); `max_failed_jobs` defaults
  to -1 and the design flips it to 0; there is no journal, no live activation, no reader
  pinning. Do not rebuild what exists.
- **The supervisor budget is re-cut, not ported** (7.1, lock). The conformance harness's fake
  engine must exit under each of the three classes, and the hang poll interval and count are
  placeholders until stage E sets them with the collector.
- **Same-dir double open is a documented handle leak** (`KnowledgeServer.java:645-651`,
  `swapRuntime` at 1236 at the base). The beside mode composes a second generation directory,
  never a second reader over the same one.
- **The generic MCP-client recovery harness** (7.6, lock) is new work with no code today; it
  belongs to C2 or D2, whichever the implementer's checklist places it in, and the 16
  recovery row exercises it.
- **Windows discipline.** Edit/Write or node UTF-8 scripts for multi-file edits, never
  PowerShell `Get-/Set-Content`; check the diff for stray non-ASCII and NUL bytes
  (`agent-lessons.md`, `utf8-bulk-edits`). `git merge` to catch up a pushed branch, never
  rebase. A piped command reports the pipe's exit code.
- **Delegation.** The orchestrator writes briefs and judges evidence; stage implementation can
  be delegated in bounded, self-verifying chunks with an explicit `model` on every spawn
  (sonnet floor, opus where quality is in doubt). Reviewer is never the implementer.
  Fire-and-forget dev-stack delegation is not allowed.
- **The main checkout holds other agents' untracked work** and there are roughly forty
  registered worktrees, many from Codex sessions. Leave them alone; the halt condition (17.8)
  is about new merges to `main`, not about cleaning those up.

## What the design orchestrator would watch for

- A stage that passes its checkpoint proof but whose "branch state after" is redder than the
  table allows. That is a defect of the stage, not a note for the next one.
- A green that depends on an environment precondition (`green-masked-destructive`): the
  in-place reconfigure path is forced by capping free device memory, the stuck-component row
  by a held lock. Test the adverse precondition, not only the happy one.
- Line-number citations trusted across a moved base. The seven files above are the known
  cases; grep the symbol, not the line.
- Any sentence that starts "the owner probably meant". 17.7 records what the owner meant.

---

## Implementation orchestrator handoff (2026-09-08)

Written by the agent that orchestrated PR 0, PR 0b and stages A and B1 to B7 (session
`9b397ac4-f652-4d85-ab86-880c86ef4c16`, branch `worktree-lane-F-A`), for the agent taking the
orchestration over mid-stage-B. Everything below is either not written anywhere else or is
scattered across commit bodies; `design.md` section 0 (dated paragraphs), `stages/A.md`,
`stages/B.md` and the `evidence/` directories are the durable record and win on conflict.

## 1. Where every branch and PR stands

| worktree (`.claude/worktrees/`) | branch | contents | state |
|---|---|---|---|
| `lane-F` | `worktree-lane-F` | PR 0: Head launch flags, workflow fixture, baseline, design files | **PR #708**, base `main`, CLEAN, green; review record refreshed; awaiting the owner's merge go-ahead |
| `lane-F-0b` | `worktree-lane-F-0b` | PR 0b: determinism pins (chunk tie-break, exhaustive kNN switch, `sampling` request override, fixture noise-pair gate, retaken split-side baseline) | **PR #717**, base `worktree-lane-F`, CLEAN, green; **retarget the base to `main` after #708 merges** (`gh pr edit 717 --base main`), then refresh the review record; awaiting go-ahead |
| `lane-F-A` | `worktree-lane-F-A` | PR 1: stages A to F, one branch | no PR yet (opened at stage F per 17.6); stage A checkpoint complete; stage B in progress; `origin/main` last merged at the A/B boundary |

Both PR bodies live at `tmp/pr0-body.md` (lane-F) and `tmp/pr0b-body.md` (lane-F-0b), the
managed review records at `tmp/pr0-review-record.md` and `tmp/pr0b-review-record.md`; `tmp/` is
untracked, so regenerate from those files if they are gone. The squash-message gate
(`node scripts/ci/preview-squash-message.mjs <N>`) rejects a body over 2000 characters or 32
lines and any banner or session URL; the accepted form ends with the single line
`Session-Id: <session uuid>`. `node scripts/ci/pr-review-record.mjs check <N>` must pass on the
PR head before enqueue; every push to a PR branch invalidates it, so refresh last.

**Merges are the owner's per-PR call and were never given.** Everything else in the lane was
delegated to the orchestrator on 2026-09-07 (design section 0, "Decision authority"): no
"owner item" category exists any more; decide, record the decision dated in section 0 with its
reasoning, and continue. The one other standing restriction is the owner's: **no benchmark,
eval, soak or capture that takes more than an hour**; stage E's design must be cut to fit that
(three captures per side under the pins already fits).

## 2. Stage B: what is landed, in flight, and decided-but-unfixed

**Landed and pushed** (`e692b86ef` = B1 to B6 plus the sweep; `c7e8e0302` = B7). Full suite at
B6: 9331 tests, 0 failures (XML counts, `cleanTest --no-build-cache`); kernel 30 gates, 0 fail.

**B8 to B10 landed (same day, one implementer, 111 minutes), pushed with this handoff:**
`f97511113` B8 (the dev-runner supervisor, `scripts/dev/lib/engine-supervisor.cjs`),
`664da18d4` B9 (`scripts/dev/run-dev-runner-tests.mjs`, nine files discovered, CI step in
`windows-native-tests` because `dev-runner.cjs` is Windows-first; `public-claims` runs the
harness `--self-test`), `63826830e` B10 (`modules/shell/src-tauri/src/supervisor.rs`, the
`[[bin]] supervisor-conformance` target, the `shell-rust-tests` step, the `supervisor-state`
FE consumer), `c05216f6a` and `1242fd288` (critical-analysis fixes: `force_kill` reaped the
handle so `poll_exit` never fired again; a 50 ms resurrection window in the shutdown check;
the same start/death race in the dev-runner), `b5e3a5ce2` (B.md section 0.1 corrections,
`status: B1-B10 landed`). Implementer's verification: harness self-test 31/31, dev-runner
adapter 10/10, Tauri adapter 10/10 with real children; `cargo test --lib --locked` 56 passed
(Smart App Control did **not** block cargo here; `modules/shell/src-tauri/resources/headless/.ci-placeholder`
must exist locally, gitignored); `run-dev-runner-tests` 9/9; every kernel gate green;
`check-runtime-manifest-closure` with **both writers unskipped**; ui-web typecheck, 6451 unit
tests, `run-ui-web-gates` 27/27.

**Two things the successor must resolve before trusting that suite run.** (1) The full
`cleanTest test --no-build-cache` reported **1433 XML files, 9001 tests, 1 failure** against
9331 tests and 1514 files at B6: 81 result files and 330 tests are missing, which means at
least one module's test task did not complete (the failing task likely aborted the rest of its
module). Re-run the full suite and reconcile the count before the stage-end claim; a total that
is smaller than the previous one is not "one failure". (2) The one failure,
`OnnxEmbeddingEncoderLongDocForensicTest.longDocEmbedWithSpansMatchesBaseEmbed`, ran 68 s
against its own 30 s `@Timeout`, deterministically and alone on an idle machine; the branch
does not touch `embed/onnx/` (`git diff origin/main..HEAD` on that path is empty). The
implementer reads it as a CPU-fallback encoder on this machine (tempdoc 710's question), not a
branch defect. Verify that reading (does the same test pass on `main` in a clean worktree on
this machine? does the ORT provider log say CUDA?) before either widening the timeout or
recording it as an environment red; do not widen the number to make it green.

**B7 to B10 have not been independently reviewed.** Run the same refute-first, read-only opus
review used for B1 to B6 (brief shape: findings ranked by severity with file:line, a concrete
failure scenario and the smallest fix; then a verified-sound list), and fold its fixes into the
B1 to B6 fix batch below. The implementer's own attack list, in its order: `ShellActuator` in
`supervisor.rs` is the half CI cannot reach (the loop is shared, the bindings are not, and both
critical-analysis findings lived there); `dev-runner.cjs` grew about 780 lines on the path every
agent's stack uses (the port-wait no longer short-circuits on an explicit `--api-port`; the
engine and frontend spawn commands are overridable through `JUSTSEARCH_DEV_RUNNER_{ENGINE,FRONTEND}_COMMAND`,
both gated behind `JUSTSEARCH_SUPERVISOR_HARNESS=1` — verify the gate, not the variable);
timing-shaped conformance cases (`hang-soft` at a 1.5 s graceful deadline was flaky before the
re-entrancy guard; margins are harness-tuned); the `watch_manifest` thread accumulates one per
Tauri restart (pre-existing shape, newly reachable; duplicate `backend-restart` emits are
idempotent, the threads are not reclaimed). Premises that did not survive, recorded in B.md
section 0.1: `SupervisionContractTest` cannot hold `enginePolicyMatchesCode()` (edge
`app-engine -> app-services`), so the engine row names its drift check in a `driftCheck`
field and guard resolution now accepts repo-relative paths; the closure check's Rust glob had
never matched `src/lib.rs` (fixed, and the planted-artifact falsification now reds at
`lib.rs:1080`); `maxCooldownMs: 5000` is inert at three attempts (ramp tops out at 3 s; stated
in the register, one case raises the budget to reach it); the dev-runner's first incarnation
cannot be supervised because `start` is synchronous for its caller, so `startDeadlineMs`
governs restarts there and first boot on Tauri; Q5's mirror is a sibling
`runtime/instances/supervisor-history.v1.jsonl` because the manifest mirror is keyed by an
instance id the exhausted case does not have; no conformance case covers "`exhausted` kills
registered children" until B11/B12 exist (a red placeholder was refused, correctly).

**The B1 to B6 independent review (opus, read-only, 2026-09-08) returned 14 findings; the four
highest were re-verified at the call sites by the orchestrator and all hold.** None is fixed
yet because the fix set overlaps the files B8 to B10 edit (`HeadlessApp.java` is safe;
`lib.rs`, the closure check and the recoverability register are not). Run the fix batch as one
implementer brief immediately after B10 lands, one commit per finding group, then a second
independent review of the fix range. Decisions per finding:

1. **Blocker: llama-server stop-by-reason has no caller.** `InferenceLifecycleManager.setStopServerOnClose`
   (`:1359`) and `ShutdownRequest.Reason.stopsGenerativeBackend()` (`:85`) are called only from
   tests; the default is `true`, so `restart` and `hang` still stop the server and the restarted
   Engine has nothing to adopt. Fix: the sequence sets `setStopServerOnClose(reason.stopsGenerativeBackend())`
   before the `head-assembly` step closes the manager; replace `GenerativeBackendByReasonTest` with
   one that drives a fake `serverOps` through `InferenceLifecycleManager.close()` for all four
   reasons and asserts `stopLlamaServer` called or not called.
2. **Must-fix: a stale request file shuts the next Engine down at boot.** `deadlineEpochMs` has
   no reader; nothing clears the file at start; the watcher's production predicate is
   `request -> true` (`HeadlessApp.java:1146`). Fix both halves: `pollOnce` drops and clears a
   request whose deadline is past, and `HeadlessApp` calls `ShutdownRequest.clear(runtimeDir)`
   once before `watcher.start()`. Test the adverse precondition (a pre-existing file at boot must
   not fire).
3. **Must-fix: 7.3 step 1 (admission freeze for every reason) is unimplemented** and
   `HeadlessApp.java:1236` says all eight steps are. Fix: a first step calling
   `leases.freezeAdmission(reason.wire())` (verify `OperationLeaseServiceImpl.freezeAdmission` is
   idempotent for the upgrade reason, where prepare already froze it); step 2 (cancel interactive
   turns with a reason code) has no admission front until C1, so record it as deferred in
   `stages/B.md` section 0.1 and section 10 and correct the javadoc.
4. **Medium: `worker_outcome` defaults to `UNKNOWN`** when `knowledgeServer == null`
   (`HeadlessApp.java:1283`, `EngineShutdownSequence.java:146`), which `updater.rs:1044` rejects,
   so an Engine that booted without its index half cannot be upgraded (the old code said
   `GRACEFUL`). Fix: the step returns `GRACEFUL` when there is nothing to close and `FAILED`
   when it throws; two new cases in `EngineShutdownSequenceTest`.
5. **Medium: the B6 end-to-end test asserts a hand-copied duplicate of the production wiring**
   (`UpgradeShutdownViaRequestFileTest.java:36-43` versus `HeadlessApp.java:1119-1156`). Fix:
   extract the writer and dispatcher lambdas into package-visible factories and test those; add
   the nonce-mismatch-writes-no-file case B6's acceptance asked for.
6. **Medium: the shutdown-request row's `futureVersionRefusalTest` names a test with no version
   refusal**, and the wire form has no `schemaVersion`. Fix: add `schemaVersion: 1`, a refusal
   branch for an unknown version, a test, and point the register at it.
7. **Medium: the nonce seam is declared and bypassed.** Fix: the predicate for `UPGRADE` with a
   `preparationId` checks it against the live lease snapshot's preparation; if that is not
   cheaply reachable from `HeadlessApp`, restate the javadoc as "unused until B8" and note it.
8. **Medium: rename residue.** `verified-facts.md:120,191` still cite `checkpointForUpgrade`;
   `KnowledgeServerHealthMonitor.java:120,366,699` and `KnowledgeServerBootRecoveryTest.java:294`
   still name `performOrderedShutdown`. Sweep them.
9. **Minor:** orphaned javadoc at `JobQueue.java:707-729`; `HeadShutdownCoordinator.shutdown(preparationId, nonce)`
   has no caller and its `implements` clause is decorative (delete or re-justify); the watcher is
   started and never closed (add it to the sequence's steps); the duplicated OOM comment at
   `lib.rs:804-808` mislabels the prod flag (delete); `check-runtime-manifest-closure.mjs:58`
   describes the Tauri supervisor and the dev-runner as writers in the present tense (say
   "will"); the sequence exits with bare `0`/`1` outside `EngineExit`'s pin, so a requested but
   unclean shutdown reads as a transient crash — add `EngineExit.REQUESTED_UNCLEAN` (a new
   code classified `REQUESTED`), route both exits through the constants, and extend the call-site
   pin to `EngineShutdownSequence`. Because B8's dev-runner classifier and B10's Rust classifier
   read `EngineExit`'s table, adding the code after them must trip their drift tests; if it does
   not, the drift test is the defect.
10. **Verified sound, do not re-litigate:** UTF-8 and NUL clean; flag pins exact-set on both
    spawn sites; no `System.exit` inside the JVM shutdown hook; idempotency, first-reason-wins,
    error accounting and single exit each pinned by a test that reds when its guard is removed;
    the B5 WAL justification is honest and the rename is complete in code; the governance rows
    are shaped like their neighbours. `AotTraining.java:113`'s `System.exit(0)` is a separate
    `main` and out of scope; the extraction child's exit codes are a different namespace.

**Remaining stage B items after the fix batch:** B11 (child registry, manifest schema v2,
`WorkerInfo.grpcPort` removed), B12 (reconciliation by PID plus start instant plus config
identity; extraction children registered, never adopted), B13 (dead-Engine updater path with
the `ENGINE_UNRECOVERABLE` witness phase; host-level proof on the branch, sandbox round deferred
to the first post-merge installer, recorded as a dated gap), B14 (`ENGINE_RESTART_EXHAUSTED`
producer and the rename, `readinessNotice.ts` rows), B15 (`restart_required` on cutover and the
requested-restart consumer), B16 (residue sweep), B17 (stress-suite policy). Then the stage-end
protocol of 17.6: full suite plus ui-web gates; the checkpoint proof run live and recorded under
`evidence/B/` (harness green on both adapters, **a forced kill on the live dev stack recovers
under the budget** — the orchestrator runs this under a dev-stack lease, it is not delegated
fire-and-forget; the death-observability test green in CI); an independent review of the whole
B range; `git merge origin/main` at the boundary; then the owner's "continue".

## 3. Stage C1 and C2 drafts exist; their questions are decided

`stages/C1.md` (863 lines) and `stages/C2.md` (788 lines) were drafted by two opus agents at
base `e692b86ef` while B7 was landing. Their section 0 lists the `verified-facts.md` corrections
found (ten for C1, twelve for C2); **apply those to `verified-facts.md` at the stage start**,
not before, since B may move them again. All eleven open questions are decided in design section
0 ("Stage C1 and C2 checklists drafted during B"). The three items each drafter judged most at
risk: C1 — admission as the `ForegroundLoad` producer (rule 6b bars `ui` from the type, the
gate forbids a second wrap, the front's filter skips GET: the producer must **move**, not be
added), parser confinement (no module boundary exists and VDU already parses PDF in the
Engine), context through the 33-method port (a `withContext` bound view is the decision);
C2 — no journal commit sequence number exists (the operations table's own autoincrement key is
the decision), `jobs.db` re-classification is refused by the installed updater's closed-set rule
(batch every durable-store identity change into one register change, and relax the rule's
successor in the same commit), and `version conflict` has no subject until the global
accepted-settings revision exists (C2 builds that revision only). Note the installed updater
compares against the installed build's **durable** stores, so stage B's `EPHEMERAL` rows do not
trip it; a `DERIVED`/`AUTHORED` identity change does.

## 4. Working practices that were never written down

- **Parallel lanes, one implementer per branch.** Throughput came from running, at once: one
  implementer committing on the branch (sequential items, one Gradle build at a time), one
  read-only reviewer on the previous batch (no Gradle, no cargo, no edits), and drafters for the
  next stage writing new files only. Never two writers on the same worktree. Do not commit your
  own files while an implementer may have paths staged: check `git diff --cached --stat` first,
  and stage by explicit path.
- **Isolated Gradle homes** avoid shared-cache corruption when two worktrees must build: the
  0b worktree used `GRADLE_USER_HOME=F:/gradle-home-0b`. Memory is the real limit: with the
  standard chat model loaded (about 11 GB) a Gradle build or a Rust compile beside it gets
  killed; use the compact profile for plumbing checks and unload before building.
- **Background tasks die at about 60 minutes**; briefs over that size are chunked (B1 to B6 ran
  64 minutes and survived by luck). Subagent reports go to the spawning session only; make
  implementers write their findings into `stages/<letter>.md` section 0.1 and commit bodies so a
  report is never the only copy.
- **Every implementer is told, in the brief:** Edit/Write or node UTF-8 scripts only; the
  NUL and non-ASCII diff checks before each commit; falsify every new assertion once and say how
  in the commit body; one commit per item, green on its own; never `git checkout --`, `stash` or
  `reset` on a dirty tree (the B1 to B6 implementer destroyed its own work twice); the two
  trailer lines; do not push (the orchestrator pushes after review). Bash-tool `node -e` and
  heredocs break on apostrophes and backslashes: the Edit tool for prose with either.
- **Always-loaded budget is at its ceiling.** `AGENTS.md` sits at exactly 8296 of 8296 bytes;
  any merge from `main` that adds a byte reds `check-always-loaded-budget`, and the invariants
  block in `CLAUDE.md` is a generated projection (`node scripts/docs/agent-instructions-sync.mjs`,
  `--check` to verify) that `scripts/ci/check-codex-agent-parity.mjs` runs in CI separately from
  `regen-all`. Trim, regenerate, run `check-always-loaded-budget`, `regen-all --check --except
  notices` and `check-codex-agent-parity`.
- **Live verification.** The dev MCP tools (`quick_health`, `start`, `ai_activate`, `reload`,
  `stop`) drive the stack from the worktree; ingest through `curl` with the worktree's absolute
  paths, not the MCP `ingest` tool, which resolves paths against the main checkout and produced
  a duplicated corpus once. The fixture capture pins live in `scripts/jseval/lane-f/fixture-pair.sh`
  (exhaustive kNN, one LLM slot, rerank deadlines 60000, rerank top_k 40 with 4096 MB, candidate
  limit 5000, collapse multiplier 50, leg arbitration and recall-complete off); `fixture-gate.sh`
  gives the verdict; three captures per side is the accepted noise floor (216 equal, 0
  regressions at PR 0b).
- **Evidence directories** are the checkpoint record: `evidence/pr0/`, `evidence/baseline/`
  (scifact, encoder-idle, fixture, fixture-pr0b), `evidence/A/` (red captures, accepted reds,
  live check, suite-and-gates, deletion counts). Create `evidence/B/` in the same shape.
- **Named reds and gaps carried at handoff**: `worker.restart_exhausted` has no producer until
  B14 (declared `awaitingProducer`, owner lane-F/B, in the readiness register);
  `WorkerBootRecoveryE2ETest` is red from stage A (A.md section 10 row 1c) and B owns it;
  `build-installer.yml` cannot run on a branch ref (`environment: release-signing`), so every
  packaging claim on the branch is "changed, locally reasoned, never executed" until the first
  post-merge run; migration cutover served-generation observability is D1's;
  `LambdaMartBenchmarkTest` is quarantined as load-sensitive on this branch; three other tests
  flake only under concurrent worktree builds and pass isolated (`BatchUpdateIntegrationTest`
  concurrent RMW, a `RuntimeReconcilerTest` temp-file move).
- **Design amendments** go in section 0 as dated paragraphs, newest last; the checklist's
  section 0.1 takes per-item corrections; `verified-facts.md` takes corrected citations at stage
  start. Three places, three purposes; do not collapse them.

### Codex orchestration checkpoint (2026-09-08)

The takeover verified the actual worktree at `.claude/worktrees/lane-F-A`, branch
`worktree-lane-F-A`, starting clean and pushed at `1ffd6cc2d`. The main checkout was
left on main with its unrelated changes intact. A separate, owned
`codex/lane-f-main-verification` worktree at `83b9e5fd5` supplied the main control;
it remains available for comparison, not implementation.

Both initial verification questions are resolved in
`evidence/B/takeover-verification.md`. The forensic test explicitly uses CPU FP32;
fresh isolated main and lane controls passed around 17s without timeout changes.
The old 1433/9001 count was a mixed post-rerun XML inventory, not the preceding
full-suite inventory. A fresh pre-edit full suite passed with 9334 tests, zero
failures/errors and 25 skips, and its XML was preserved before targeted runs.

The first sole-implementer shutdown batch is reviewed through `d23e94bdd` and
released with a clean tree and no active Gradle wrapper. See
`evidence/B/shutdown-review-checkpoint.md` for commits, primary-source evidence,
the 3965-test affected-module inventory, post-final-edit targeted verification,
falsification limits and all remaining findings. Strict boot cleanup's placement
has source/helper evidence, not a live boot-failure proof. Stage B is still open.

The independent B7–B10 findings are in
`evidence/B/b7-b10-independent-review.md`. Dated design section 0 now settles the
B6 acknowledgement/nonce transaction, first-claim-wins request protocol, focused
production host ownership, v2 child ownership handoff, and B13 process hold/evidence/UI
corrections. B section 0.1 records the corresponding per-item amendments. The next
implementation batches are B6 transaction and the coordinated request protocol,
then production supervisor ownership and the remaining lifecycle items. One
implementer owns the branch at a time; reviewers remain read-only.

Before B14/B15 implementation, resolve the source-backed questions in
`evidence/B/remaining-lifecycle-investigation.md`: stale supervisor state is not a
current recovery owner, a supervision veto needs an actual recovery owner, and
promotion response flags currently lose their restart consumer. These questions
are not silently treated as decided by the existing draft.

PR #708 (`62251e459`) and PR #717 (`f0d9e2481`) were rechecked OPEN/CLEAN during
this checkpoint and remain the owner's per-PR merge calls. No merge was performed.
No new benchmark, evaluation or capture longer than one hour was started.

### Reviewed transaction and host ownership checkpoint (2026-09-08)

This supersedes the preceding checkpoint's next-batch list. The primary worktree
remains `.claude/worktrees/lane-F-A` on `worktree-lane-F-A`; main and unrelated
worktrees were not edited. `0ecaa8496` was reviewed and pushed before the next work.

**B6 transaction:** `e18760596`, `975c4f33d`, `fb2b1e967` and evidence commit
`5c1df5992` have independent read-only sign-off. The production writer is installed
before API exposure and persists before success is returned. The controller alone
owns the nonce and OPEN/PERSISTING/ACKNOWLEDGED state. Dispatch follows successful
response flush. Pending requests survive deadline expiry during that flush;
controller-acknowledged requests retain their original deadline. A preparation
reservation protects both the lease-before-nonce and Worker-after-nonce boundaries
against concurrent prepare/cancel/commit, without holding the monitor over I/O.

The full app-engine/UI run passed in 7m7s: 1158 tests, zero failures/errors and one
skip. It predates the final deadline/preparation fixes. Final focused evidence is
33 passing tests with no skips: ten watcher tests executed in the preceding run,
then reused while the final 21s run executed 23 UI tests. Both inventories and the
raw falsification/green logs are under `tmp/lane-f-takeover/`; see
`evidence/B/b6-upgrade-transaction.md`. The servlet failure is injected, not a real
socket-disconnect proof. No post-change full repository suite is claimed.

**Production host ownership:** a separate sole implementer used
`.claude/worktrees/lane-F-host-ownership`, branch `codex/lane-f-host-ownership`,
based on `0ecaa8496`. Code checkpoint `f25dbadfa` has independent sign-off and was
integrated into the primary branch as `5c179b5a0`; `36fb25ed3` and `203d40f67`
integrate the evidence correction and complete raw Cargo log. The
production core serializes real-child spawn admission with monotonic host close,
retains predecessor discovery identity, rejects stale and child-absent manifests,
guards stdout EOF by spawn generation, owns one cancellable/joined watcher, and
publishes initial and replacement launch failures through the real state/event
paths. Only admitted manifests can update the tray, and repeated tooltip text does
not trigger repeated native updates. The library suite passed 59 tests; three
mutations of production event/failure paths failed before restoration. The evidence
in `evidence/B/b7-b10-independent-review.md` reconciles the inherited 56 tests and
states the test-only Tauri resource override. Tauri setup wiring was source-reviewed,
not executed as a packaged application.

The integrated `build -x test` exposed eight PMD violations in the shutdown
changes. `3721395ff` fixes the redundant qualifiers, preserves try-with-resources
cleanup using unnamed resources, and removes an unused test helper. Focused PMD
and shutdown tests passed in 20s; the repository build then passed in 26s (325
tasks). Raw failure, repair and green build logs are under `tmp/lane-f-takeover/`.

**Decisions and remaining work:** newest dated design section 0 paragraphs now
also settle B14/R7. Retire the obsolete Java whole-Worker supervision veto while
preserving local recovery and fatal index/schema refusals. Current host state must
reach the real recovery UI; stale state files are not a live recovery authority.
A bounded valid HTTP 503 response proves liveness and must not trigger a hang
restart; essential readiness separately controls budget reset. This is decided but
unimplemented. `evidence/B/remaining-lifecycle-investigation.md` carries the source
findings, including the fact that B15's actual promotion occurs after the cutover
request response.

The first-claim request protocol is stopped by the newest design section 0
decision. Read `evidence/B/scope-recut.md` before any request-protocol work. The
preferred replacement has one supervisor file writer and local Engine dispatch;
it first needs production proof of response ordering, requested-exit classification,
responsive shutdown stalls and updater timeout ownership. Existing manifest STOPPING
is a candidate signal, with overwrite and premature-deletion hazards still to fix.
Do not implement the suspended marker/retention/claim mechanics from older amendments.
R3 is still open; the B6 transaction does not close it. R6/R7/R8, B11/B12 ownership
handoff, B13 updater hold/recovery UI, B14 implementation, B15's real restart consumer,
B16 residue and B17 verification wiring remain open. Preserve the separate uncounted
intentional-restart and counted-failure paths. Then complete the stage-B compile,
unit, frontend, conformance, live recovery and real-model proof before claiming the
stage checkpoint. The main control worktree and pre-edit full-suite evidence remain
available; no relevant historical or named gap is silently converted to a pass.

PRs #708 and #717 remain the owner's per-PR merge decisions. No merge was performed.
No benchmark, evaluation or capture longer than one hour was started. All implementers
release their branch before orchestration edits or integration; reviewers remain
read-only and do not implement the work they review.

## Scope and integrated verification checkpoint (2026-09-08)

The scope stop is committed as `da62f5201`; the independently reviewed drain
fixture repair is `4349b28f5`. All B6 transaction and host ownership work is now
integrated in the primary branch. The host worktree is clean at `bb0409520`, with
no remaining implementation delta to integrate. No implementer retains branch or
Gradle ownership after this checkpoint.

The full stress-enabled run at `3721395ff` executed 9,364 tests and failed two.
All 1,522 XML files and per-file hashes were captured before targeted reruns.
The drain fixture now passes its full 699-test module; its deliberate wrong-reason
mutation failed. The final repository build with tests excluded also passes.
**The Engine file-locking boot test remains a blocking red.** A locked compound
segment closed the Lucene writer; subsequent work kept retrying that writer.
B14's no-client boot retry is not its recovery path. Read
`evidence/B/integrated-verification.md` for exact commands, counts, raw logs and
the next bounded detection/recovery/replay experiment. Do not turn an isolated
passing rerun into a claim that the writer failure is fixed.

The first-claim/accepted-marker request batch remains stopped. Before approving a
replacement, prove the preferred single-writer cut through production response
ordering, requested exits, responsive shutdown stalls and updater intent ownership
as listed in `evidence/B/scope-recut.md`. If either recovery investigation needs a
later-stage mechanism, apply 17.8 before moving it earlier. No stage-B completion,
live-model/installer proof, PR merge or new PR is implied by this checkpoint.

Closeout found no owned registered helper to reap; the ownerless OTLP sink
(PID 14468) was reported and retained. The extra host branch is intentionally
unpublished because all its reviewed content is integrated in the primary branch;
it is not an outstanding implementation lane. The clean main-control worktree
remains available for future comparisons. Unrelated worktrees and main were left
untouched.

## Publication and recovery checkpoint (2026-09-08)

Both split-baseline PRs have landed under the delegated merge authority:

- #708: `0824e365411960597d2af2f87ba55cf17381426f`, matching reviewed candidate
  `30e027c6105489d49cf5427f5a80188aee0902bb` over the entire tree.
- #717: `f938c4eb2e9b487bd0965859108d239c30e8601f`, matching reviewed candidate
  `a6bda3224274dd768a3ded741b28fca7b43c9de4`. That candidate is byte-identical to
  the fully tested `7c8cc01a50f10c684cfe26b47274bed778c855be`; the merge of the
  #708 squash changed ancestry only. Independent review verified the conflict
  resolutions, merge parents and unchanged 71-path diff before push.

Read [the publication record](evidence/publication.md) for candidate, queue and
main-push CI, all 33 module counts and the original log hashes. The fresh Java
runs passed 9,301 and 9,337 tests respectively; Python passed 3,536 and 3,637.
All original Java XML files were preserved before filtered reruns. These are
split-mode PR results and do not clear the Stage B stress failure.

The primary branch's recovery-documentation checkpoint is `508030d79`. Its only
test-source edit is a Javadoc correction: the old scheduler explanation is scoped
as a hypothesis, and the proven closed-writer failure remains a blocker. The
comment-only edit passed formatting; no full-suite rerun or recovery success is
claimed. The Codex workflow instructions match this branch's own canonical PR 0
reference. **PR 0b is not yet integrated into the primary branch.** The premature
PR 0b instruction copy was reverted before push; carry the published code and
matching instructions together at the lane's main-integration checkpoint.

The next recovery proof is the whole-Engine fatal-fault route, using the existing
transient-exit budget and startup queue replay. Read
[writer-recovery-investigation.md](evidence/B/writer-recovery-investigation.md)
before implementing: generic lock IO is not a fatal-writer diagnosis, failed
queue transitions can leave PROCESSING rows, and automatic production restart
must be exercised. The existing runtime swap is not a proven shortcut. D1 live
replacement has not moved into B.

The shared first-claim/accepted-marker batch stays stopped. The single-writer
shutdown direction still needs the response, exit, stalled-close and updater
proofs in [scope-recut.md](evidence/B/scope-recut.md). B11-B17 and their live,
real-model and installer proof obligations remain open in the stage checklist.

The owned main-control worktree is clean at the published `f938c4eb2` for future
comparisons. The host branch remains clean at `bb0409520`, wholly integrated into
the primary branch and intentionally unpublished separately. No lane implementer
or owned Gradle run remains active. The registered-process sweep found no owned helper
to reap and retained the ownerless telemetry sink (PID 14468). No development
stack or new capture campaign was started for publication. Unrelated worktrees
and the shared main checkout remain untouched.

## Ordered writer-fault repair checkpoint (2026-09-08)

The terminal-writer connection is implemented above `6f38df7e5`. Read the dated
implementation and verification sections of
[writer-recovery-investigation.md](evidence/B/writer-recovery-investigation.md)
before continuing. The production owner is HeadlessApp's complete ordered
shutdown sequence, dispatched by EngineRoot's dedicated thread. RuntimeSession
arbitrates one report against retirement; KnowledgeServer binds before publishing
each writable runtime. NRT failures during intentional close cannot fall back to
raw JVM exit after the runtime snapshot has been cleared. Deferred native model
initialization completes before model teardown. Fatal admission and the upgrade
receipt use the sequence's single exit-code selection.

The deterministic installed-Engine/dev-runner proof passed: one charged fatal
restart, preserved committed document and accepted document searchable. A separate
native-enabled arm exercised real GPU session initialization and reranker warm-up
overlap, followed by graceful stop. It did not execute AI-ranked search, both
durable queue outcomes or packaged Tauri startup. Earlier deadlock/native-crash
evidence is preserved rather than relabelled as unrelated. The integration tier
runs in advisory CI; default `test` excludes that tier and stress tags.

The orchestrator resumed direct implementation ownership after the worker's
handoff; the independent reviewer remains read-only. The final verification
inventory belongs in the linked evidence, not in an assumed green filename.
PRs #708/#717 remain merged. PR 0b still enters this primary branch at the recorded
main-integration checkpoint.

Next implementation batches are B11-B12, B13-B14, then B15-B17, with fixed briefs
and at most two worker review rounds. Within B17, explicitly relocate hostile-lock
survival to a real supervised process,
preserving intruder-before-boot ordering, lock intensity, corpus, acceptance and
180-second searchability assertions. Preserve the original red as the cause for
that move. Prove both durable queue outcomes. Then finish the single-writer
shutdown re-cut's production proofs and B11-B17; D1 live runtime replacement and
the rejected shared request-slot protocol remain outside this repair.

## Integrated B11–B14 and B16 checkpoint (2026-09-08)

Resume in `F:/justsearch-public/.claude/worktrees/lane-F-A`, branch `worktree-lane-F-A`.
The root is sole implementer; reviewers are read-only and have returned signoff.
B11/B12 is `531fa93f1`; the primary then integrated reviewed B14 Java retirement
`03c4e513b`, B16 `a7eb1abdf`, pre-API recovery UI `5c1e933d4`, and R7 `c7a0aae41`.
The following B13 commit completes the exclusive updater hold, real owned-child
stop/reconciliation, tagged no-receipt evidence and one-loop failed-launch resume.
Its evidence is [b13-updater-handoff.md](evidence/B/b13-updater-handoff.md).
The full integrated suite passed 9,405/0 with exact task XML preserved; Rust 76/0,
frontend 6,462/0, both supervisor adapters 11/11 and governance checks passed.
A stale Tauri conformance executable caused an initial failed invocation; rebuild
that binary explicitly before adapter runs. The two B11 stale UI assertions were
corrected to the new public schema and completion ordering and independently reviewed.

B11–B14 and B16 are complete at the permitted branch proof tier. The signed
installer/user-store exercise is assigned to final validation in stage E, registered under
`upgrade-dead-engine-recovery`; the 2026-09-08 final-validation amendment in design section 0
supersedes the earlier first-post-merge scheduling. Stage F carries the gap if main-only signing
requires the actual run to wait for the first eligible installer after the final merge. B15 and remaining B17 work are next:
actual promotion → requested whole-Engine restart → promoted generation served;
hostile-lock survival through real supervision; PROCESSING as well as PENDING replay;
and the single-writer shutdown re-cut's four production proofs. Read `scope-recut.md`
before changing shutdown transport. No shared request-slot/accepted-marker protocol
or D1 live component swap has been approved by this checkpoint.

The previous secondary recovery worktree/branch remains a preserved checkpoint at
`ad272bdd9`, including its earlier uncommitted B13 snapshot; do not resume editing it
or copy its older files over this integrated primary. Raw outputs remain in ignored
`tmp/`; tracked evidence contains summaries and hashes. Main and other worktrees
remain untouched. PRs #708 and #717 are merged; PR 0b still enters at the recorded
main-integration checkpoint. User authorization now permits autonomous merges.


B15 precursor after that push: the existing manifest handoff survives late readiness
writes and finally close, and is cleared by a fresh incarnation. A new publisher
regression proves this, including a failing negative control; 26 publisher tests
and UI test PMD pass. There is no production transport change yet. The independent
review found that a local `restart` handoff also accompanies fatal writer exits:
never copy it into the host-owned requested-reason slot, which makes restarts free.
See the new design section 0 constraint and `scope-recut.md` for the bounded next cut.


B15 transport checkpoint (2026-09-08): clean restart exit 4 is pushed in `86d369c25`.
The following reviewed cut removes Engine-written shutdown requests, dispatches upgrades
locally after response flush, and bounds current-incarnation manifest handoff with each
host's existing Stopping deadline. Both adapters 17/17, Rust 77/77, fresh Java full suite
9405/0; preserved XML and hashes in `evidence/B/b15-requested-restart.md`. Production
Tauri AppHandle binding remains source-reviewed, not claimed as installed proof.
B15 is still open for migration start/rollback/cutover consumers and actual promoted
search after restart, including publication-failure refusal. B17 remains open as above.
The scope-recut's controller and local-host proof obligations are now covered by this cut;
no shared request-slot/accepted-marker mechanism was introduced. Root is sole implementer.
