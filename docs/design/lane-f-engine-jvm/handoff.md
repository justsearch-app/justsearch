# Lane F handoff: from the design orchestrator to the implementation orchestrator

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
