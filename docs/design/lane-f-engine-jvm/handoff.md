# Lane F handoff: from the design orchestrator to the implementation orchestrator

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
