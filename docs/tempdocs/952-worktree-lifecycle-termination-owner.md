---
title: Worktree and branch lifecycle — a durable termination owner
status: PHASE 1 IMPLEMENTED, AWAITING PUBLICATION (2026-09-10) — P1-P9 done at d273b17ea; A1-A4, A6, A9 proven locally; A5/A7/A8/A10 partially (phase-2 scheduler, Codex live parity, post-merge metrics deferred, see §7); publish only with owner go-ahead and after PR #699
related:
  - 936-registered-worktree-removal-safety   # made remove-worktree.cjs usable; this tempdoc gives it an owner
  - 940-local-main-realignment               # lives only on branch worktree-940-worktree-base-fresh (PR #699, unmerged); baseRef head→fresh is a prerequisite
  - 938-release-consolidation-before-architecture-change  # §D consolidation ledger; item 11 executed 2026-09-10
  - 861-agent-process-registry               # agent-spawn registry, reap occasions, process identity: the reconciler's substrate
  - 886-agent-token-efficiency-review        # harness-neutral session ledger (lastActivityAt), PR #608: the heartbeat signal
  - 618-agent-developer-velocity-friction    # chose baseRef "head" when local fast-forwards were the publish path
---

# 952: Worktree and branch lifecycle — a durable termination owner

## 1. Problem statement

Measured on 2026-09-10 (evidence: `952-evidence/sprawl-audit-2026-09-10.md`): 175 local
branches, 52 registered worktrees (~84 GB, all created in 8 days), 94 branches whose PR had
merged a median 22 days earlier, 37 worktrees torn down with the branch kept, 26 worktrees
created by hand outside the harness directory, 33 worktrees already labelled STRANDED-FINISHED
by `world-state.mjs` with nobody acting on it.

**Cause (one sentence):** every workflow mandates creating a worktree and branch, but no
workflow step, tool default, automated process, or rule owns ending one, and the safety rule
"another session might own it" converts uncertainty into indefinite retention, so the default
terminal state of every worktree and branch is "kept".

Contributing mechanisms, in order of weight (audit addendum 3): local branch deletion never in
the loop (squash merges defeat `--merged`; `--delete-branch` opt-in); PR #691 (2026-09-06)
rewrote merge step 5 from "remove the worktree, delete the branch" to "preview with
`--dry-run`" and the publish skill's cleanup step became conditional; Codex adoption
(tempdoc 920) brought worktrees with no lifecycle; fan-out per tempdoc (sub-lanes, separate
publication-record branches, worktree reuse with a fresh branch per PR); the stranded-main
episode (940) explicitly deferred hygiene; detection exists without remediation.

An isolated researcher confirmed the cause from the brief alone
(`952-evidence/researcher-recommendation-2026-09-10.md` §1) and recommended a
**repository-owned lifecycle controller**: sessions own exclusive use; the controller owns
eventual termination; merge and exit events accelerate reconciliation but are not required
for correctness. The central reframing: *make every creation incur a durable termination
obligation that survives the agent.*

## 2. What already exists (the substrate to extend, not duplicate)

| Need in the recommendation | Existing owner | Gap |
|---|---|---|
| Safe removal on Windows (junctions, long paths, locked/dirty refusal, attribution) | `scripts/dev/remove-worktree.cjs` (936) | No archive path for dirty/untracked/ignored state; branch deletion opt-in; no obligation record |
| Census + verdicts (ACTIVE / STRANDED-FINISHED / STALE-CANDIDATE / DIRTY-IDLE) | `scripts/agent-analytics/world-state.mjs` | Classification only; 3-day commit-age threshold; no ledger of obligations; no branch census independent of directories |
| Session heartbeat | `tmp/dev-runner/sessions/<id>.json` `lastActivityAt`, written by hook dispatch (886) | Tool-call driven, not a launcher heartbeat; Codex parity via `codex-hook-adapter` unverified for this field |
| Process identity, leases, six reap occasions incl. an unattended `session-start` sweep with prune | `scripts/dev/lib/agent-spawn-sweep.cjs`, `agent-spawn-reaper.cjs`, `ownership-verdict.cjs` (861) | Scoped to spawned helper processes and the dev stack, not to worktrees/branches |
| Landing receipts | `tmp/agent-telemetry/session-merges.ndjson` (`source: teardown`, merge commit, session) | Written only when teardown runs; keyed by session, not by exact head `H`; no content-verified receipt |
| Creation base | `.claude/settings.json` `worktree.baseRef` | Still `"head"`; PR #699 flips to `"fresh"` — unmerged |
| Rules | `.claude/rules/branch-safety.md` merge step 5; publish skill step 4 (both harnesses) | Dry-run wording; conditional cleanup; no janitor authorization |
| Hooks | `governance/agent-hooks.v1.json` (EnterWorktree matcher → `worktree-base-hint`; SessionStart/End) | No registration/release signal for worktrees; Claude `WorktreeRemove` cannot veto deletion (researcher, official docs) |

## 3. Decisions (taken 2026-09-10, delegated by the owner to the auditing session)

1. **Controller model, two phases in one tempdoc.** Phase 1 builds the whole mechanism but
   runs the reconciler in *advisory* mode: it registers, tracks obligations, computes receipts,
   archives, quarantines, and reports, and it executes removal only when invoked explicitly by
   a session on its own released resource. Phase 2 flips the scheduled reconciler to
   *execute* mode once two consecutive weeks of phase-1 metrics show 100% ownership coverage
   and zero archive-integrity failures. Rationale: the researcher rejects "defaults only" as
   the owner, but executing automatic deletion before the archive path has Windows evidence
   would violate the preservation rule.
2. **Janitor authorization: yes.** `branch-safety.md` gains a rule authorizing the registered
   janitor (`scripts/dev/worktree-lifecycle.cjs reconcile`) to terminate *released or
   verified-inactive managed* resources after verified preservation. Unknown, foreign-locked,
   unstable, or unregistered resources stay protected. The janitor runs from the main
   checkout's repository root but never modifies the main checkout's working tree, index, or
   `HEAD`; publish and merge approvals are unchanged.
3. **Preservation standard.** Committed tips (including detached heads) are archived as
   `refs/archive/<resource-id>/<short-sha>` in the shared object store. Working-tree state
   (index, unstaged, untracked, ignored files not matched by a declared-cache list) is archived
   as a tar plus a JSON manifest with per-file SHA-256 under `tmp/archive/worktrees/<resource-id>/`
   (gitignored, machine-local), verified by re-hashing before removal proceeds. Declared caches
   excluded: `node_modules/`, `build/`, `.gradle/`, `dist/`, `target/`, `tmp/dev-runner/`.
   Archive failure blocks removal. Archives never auto-expire; `world-state.mjs` reports
   archive count, bytes, and age, and a sampled restore check runs in the reconciler.
4. **Landed-ness = receipt.** For an exact head `H`: anchor `B` (recorded fork SHA, or the
   merge-base with the recorded `origin/main` anchor), integration commit `S` from GitHub's
   `merge_commit_sha` of the merged PR whose head was `H` (via `run-gh.mjs`), test
   `git merge-tree --write-tree --merge-base=B S H` with exit 0 and tree == `S^{tree}` under
   `-c merge.default= -c merge.renormalize=false` and no custom drivers. Fallback without a PR:
   the same test against fetched `origin/main` labelled `REDUNDANT_NOW`. Any other outcome is
   `UNKNOWN` and never authorizes retirement. The audit's diff heuristics are triage only.
5. **Workflow shape.** One worktree per concurrent writer or session; one branch per PR;
   publish the implementation branch (separate publication-record branches are retired as a
   practice; tempdoc-only records ride along or batch, per the docs-ride-along rule); the
   takeover skill ends by releasing its worktree (its verdict lives in the tempdoc); reusing a
   worktree for a new branch must release the previous branch through the lifecycle command;
   read-only investigations use `git show`/`git worktree add --detach` only through the
   lifecycle command so they are registered too.
6. **Both harnesses create through the repository command.** `worktree-lifecycle.cjs create`
   wraps `git worktree add` + `prepare-worktree.cjs` + registration. Claude's `EnterWorktree`
   remains permitted: the existing EnterWorktree hook matcher registers it after the fact
   (registration, not creation, is the invariant). Codex sessions call the command directly;
   the Codex how-to and `justsearch-start` name it. Ad-hoc roots (`F:/justsearch-worktrees`,
   `F:/justsearch-public-worktrees`, `F:/justsearch-public-pr-*`) are retired; the sanctioned
   root is `.claude/worktrees/` for both harnesses.
7. **Thresholds (operating defaults, revisable with data).** Heartbeat = session ledger
   `lastActivityAt` (tool-call driven) plus a launcher renew every 60 s while the lifecycle
   command's own long operations run; SUSPECT after 15 min without heartbeat and no live
   registered process; ORPHANED after verified writer inactivity; 24 h grace before archive +
   finalize; reconciler on session-start, on release, on observed merge, and hourly via Task
   Scheduler.

## 4. Scope

In scope: creation registration, release signal, receipts, archive-before-delete path in
`remove-worktree.cjs`, branch retirement by default, reconciler (advisory in phase 1,
scheduled execute in phase 2), rule and skill text for both harnesses, hook signals,
world-state section and metrics, and retirement of everything §5.8 lists.

Out of scope: the existing backlog (cleaned 2026-09-10, audit addendum 4); merging the unlanded
branches the audit found (#698, #699, #700, `hook-wiring-repair`, 919, 888, 828, 795, takeover
tempdocs), each its own publication decision; A1 (PR #699) is a prerequisite, not 952 work.

## 5. Design (2026-09-10)

### 5.1 Ownership comparison, before any new mechanism

| Option | Who owns the end | Verdict |
|---|---|---|
| A. Defaults + rule text only (delete branch by default, imperative step 5) | still the creating session | rejected: teardown that never runs owns nothing (researcher §5 rows 1-2); the Sep 6 edit shows prose alone does not survive |
| B. Git-native ownership markers + a bounded finalization record | the reconciler, reading Git's own state | **chosen**: no second authority that drifts from Git (861 §6.1 warning; 936 "no lifecycle database"); Claude Code already writes a worktree lock reason naming session and PID, so half the marker exists |
| C. Full external ledger of every resource, reserved before Git creation (researcher §2) | the ledger | rejected for the steady state: a mirror of `git worktree list` plus refs that must be reconciled against Git forever; kept only for the finalization window, where Git has no place to record phases |

Which acceptance item requires which mechanism: A4 → ownership markers (5.2); A2 → receipts
(5.3); A5 → finalization record + occasions (5.4); A3 → archive path (5.5); A8 → world-state
section (5.7). Nothing else is added.

### 5.2 Ownership markers (steady state, Git-native)

- **Worktree ownership** is the worktree lock reason, grammar
  `<harness> session <session-id> (pid <pid>, start <creationFileTimeUtc>)`. Claude Code
  writes `claude session <name> (pid N)` itself (observed on this worktree); the lifecycle
  command writes the full grammar for Codex and re-stamps Claude locks with the start time on
  registration, so identity is pid AND creation time per 861 §6.2. A lock with a foreign or
  unparseable reason is a **foreign lock** and is never released by the reconciler.
- **Branch ownership** is `git config branch.<name>.justsearch-*`: `resource` (worktree name at
  creation), `session`, `created`, `fork` (SHA the branch was cut from), `anchor` (`origin/main`
  SHA at creation), `hold` (`reason|owner|review-by`, only when a keep was requested). Config
  survives directory loss, so a leftover branch is still an obligation; it dies with the
  branch, so a retired branch leaves nothing to prune.
- **Sanctioned root** is `.claude/worktrees/` for both harnesses. A registration elsewhere is
  reported as unmanaged and protected; the ad-hoc roots are retired from the docs.
- **Registration** happens at creation (`create`), after the fact for `EnterWorktree`
  (PostToolUse hook beside `worktree-base-hint`), or by the reconciler when it meets a worktree
  with a Claude lock and no branch config. It registers what the lock says; it never guesses.

### 5.3 Landed-ness receipts

`tmp/agent-telemetry/landing-receipts.ndjson` (new `telemetry-io` file constant, same NDJSON
discipline as `session-merges`): `{head, base, squash, pr, verdict, ts}`. Computation lives in
`scripts/dev/lib/landing-receipt.cjs`:

1. `pr` and `squash` from `gh pr list --head <branch> --state merged --json number,headRefOid,mergeCommit`
   (the lookup `remove-worktree.cjs` already performs, extended with `headRefOid`); accepted only
   when `headRefOid` equals `head`.
2. `base` is the recorded `fork` when it is an ancestor of the squash parent, else the
   merge-base of `anchor` and `head`.
3. `git -c merge.default= -c merge.renormalize=false merge-tree --write-tree --merge-base=<base> <squash> <head>`;
   verdict `LANDED` only on exit 0 and result tree equal to the squash commit's tree.
4. No PR: the same test against fetched `origin/main` gives `REDUNDANT_NOW`. Anything else is
   `UNKNOWN`, which never authorizes retirement.
5. Receipts are cached per `head` and valid only while `squash` is reachable from fetched
   `origin/main`.

The audit's diff heuristics and the manual diff in `rule:squash-merge-verify-content-not-ancestry`
remain triage; the rule text points at the receipt command.

### 5.4 Lifecycle command and reconciler

One script, `scripts/dev/worktree-lifecycle.cjs`, subcommands `create`, `register`, `release`,
`hold`, `status`, `reconcile`. `remove-worktree.cjs` stays the only deletion primitive and the
reconciler calls it. States are derived from Git and the session ledger, not stored, except
during finalization:

```text
ACTIVE      lock present; owner process verified alive (861 process-identity) or session ledger fresh
RELEASED    no lock, or owner released it; branch config present
SUSPECT     lock present; owner pid dead or unverifiable; ledger stale for more than 15 min
ORPHANED    SUSPECT for more than 24 h and no live registered process holds the path (861 recordHoldsPath)
HELD        branch config hold set (reason, owner, review-by)
QUARANTINED foreign lock, unregistered root, runtime-provenance blocker, or archive failure
FINALIZING  record exists under tmp/dev-runner/worktrees/<resource>.json
```

The finalization record is the only new writer. It uses `process-record.cjs`
(`writeRecordAtomic`, `readRegister` with a worktree validator) as a third sibling scope next
to `agent-spawns/` and `foreign/`: `{resource, path, branch, head, receipt, phase, startedAt, by}`
with phases `claimed → archived → verified → removed → retired → done`. `reconcile` resumes
from the recorded phase; a record older than its lease whose claimant is unreachable is
re-claimed.

Occasions reuse the 861 shape and capability binding: `session-start` (sweep, advisory in
phase 1), `session-closeout` (release the caller's own worktree), `worktree-teardown` (execute
for the caller's own released resource), `orientation` (world-state, read-only), `scheduled`
(phase 2 only, execute). Phase 2 is entered by setting `executeOnSchedule` in
`governance/worktree-lifecycle.v1.json` once the A8 condition has held for two weeks; the
Windows Task Scheduler entry is installed by a documented operator command, never by a hook.

### 5.5 Preservation before removal

- Committed tips (branch head or detached HEAD) go to `refs/archive/<resource>/<short-sha>`
  through a conditional `update-ref` that refuses if the head moved.
- Tracked and untracked non-ignored working state becomes a commit object built with a
  temporary index (`GIT_INDEX_FILE`, add-all with a pathspec excluding declared caches,
  `write-tree`, `commit-tree` with HEAD as parent) at `refs/archive/<resource>/state-<ts>`.
  It is verifiable by tree hash and restorable with a detached worktree.
- Ignored files outside the declared-cache list go to a tar plus a JSON manifest with per-file
  SHA-256 under `tmp/archive/worktrees/<resource>/` (gitignored, machine-local, the practice 936
  used on Sep 6) and are re-hashed before the tree is removed.
- Declared caches, never archived: `node_modules/`, `build/`, `.gradle/`, `dist/`, `target/`,
  `tmp/dev-runner/`. The list lives in `governance/worktree-lifecycle.v1.json`.
- Archive failure means QUARANTINED and no removal. Archives never auto-expire; world-state
  reports count, bytes and age; `reconcile` samples one archive per run and restores it into a
  temporary detached worktree to prove restoreability.
- `remove-worktree.cjs --allow-ignored` changes meaning from "delete ignored files with the
  tree" to "archive, then delete"; the bare flag without an archive becomes an error. Branch
  deletion becomes the default when the receipt says `LANDED` or `REDUNDANT_NOW`;
  `--keep-branch` requires the hold triple.

### 5.6 Rules, skills, hooks, docs (both harnesses)

- `branch-safety.md`: the "Cleanup" subsection and merge step 5 are replaced by one release
  step (run `worktree-lifecycle.cjs release` from the repository root; it archives, retires
  the branch when its receipt says landed, and removes the tree; keeping needs a hold). Rule 1
  gains the janitor clause: the registered reconciler may terminate released or
  verified-inactive managed resources after verified preservation; unknown stays protected.
  Net always-loaded bytes must not grow (949 ratchet); the four Cleanup bullets and the long
  step 5 are the byte source.
- Rule 5 and `never-commit-on-local-main` come from PR #699; 952 rebases onto it.
- Skills, both trees by hand because `.agents/skills` is hand-maintained: takeover ends with
  `release` (the verdict lives in the tempdoc); publish post-merge step 4 becomes `release`;
  session-closeout adds `release`, or `hold` with the triple, after the agent-spawn sweep.
  `skills-sync.mjs` regenerates the Claude generated blocks.
- Hooks (`governance/agent-hooks.v1.json`, then `regen-all --only agent-hooks-wiring,codex-hooks`):
  `worktree-register` on PostToolUse `EnterWorktree` (telemetry role, silent on success, unit
  bite); `worktree-release` on SessionEnd for both harnesses runs
  `worktree-lifecycle.cjs release --own --if-clean`: the session's own, clean, receipt-covered
  worktree is archived and removed with no model involved (SessionEnd is an execute occasion
  in the 861 matrix; its output is never read by a model because the session is over). A dirty
  or unlanded worktree is left RELEASED for the reconciler. No hook runs a kill or removal from
  PreToolUse (861 [A4]); Claude's `WorktreeRemove` cannot veto and is not used as a gate.
- The Codex how-to §Start a task gains the create and release lines and the mapping table
  gains a worktree row; `justsearch-start` names `create`.
- `common-workflows.md` §Worktree mechanics is the canonical command reference;
  `consult-register.v1.json` gains a `worktree-lifecycle` region covering
  `scripts/dev/worktree-lifecycle.cjs`, `scripts/dev/remove-worktree.cjs`,
  `scripts/dev/lib/landing-receipt.cjs`, `governance/worktree-lifecycle.v1.json` and the
  branch-safety release step.

### 5.7 World-state and metrics

`world-state.mjs` gains a lifecycle gatherer (same `{available:false, reason}` convention) but
**no new section**: the existing Worktrees table gains `OWNER` and `LIFECYCLE` columns (state
from 5.4, receipt verdict, hold) and the existing `VERDICT` column is computed from them, so
session-start output does not grow. Archive count, bytes and age, and the three A8 indicators
(computed from branch-config timestamps, receipts and finalization records, pre-952 cohort
without `justsearch-created` reported separately) print only under `--lifecycle` and in the
`status` subcommand; they are for the owner and the metrics run, not for every session.

### 5.9 Token and model-computation budget

The normal path involves no model computation and adds no context:

| Step | Who executes | Model tokens |
|---|---|---|
| create / register | command or silent hook | 0 |
| heartbeat | existing PreToolUse ledger stamp | 0 (already paid) |
| release at session end | SessionEnd hook, own clean worktree | 0 (session over) |
| receipts, archive, removal, reconcile, metrics | script, git, gh | 0 |
| release from a skill (takeover, publish, closeout) | one command, one result line | tens |
| a resource needing a human decision (quarantine, hold expiry) | one line in the Worktrees table | tens per item |

Compared with today's documented path (a dry-run and a real run of `remove-worktree.cjs`, both
read by the model), this is a reduction. Rule-text bytes are net-zero under the 949 ratchet.
Measured via the 886 session ledger and `check-always-loaded-budget`: acceptance A10.

### 5.8 What this design orphans (retired in the same change)

| Orphan | Disposition |
|---|---|
| 936 "no lifecycle database … content/squash verification remains an operator responsibility" | 936 status closed into 952; the finalization record and receipts supersede that sentence within 952's scope; 936's refusal invariants stay |
| `remove-worktree.cjs --allow-ignored` meaning "discard" | redefined as archive-then-remove |
| branch-safety "Cleanup" subsection and merge step 5 (PR #691 wording) | replaced by the release step |
| `rule:squash-merge-verify-content-not-ancestry` manual diff as the test | demoted to triage; points at the receipt |
| Separate publication-record branches (`*-publish`, `*-publication-record`) | retired in the publish skill: publish the implementation branch |
| Ad-hoc worktree roots (`F:/justsearch-worktrees`, `F:/justsearch-public-worktrees`, `F:/justsearch-public-pr-*`) | removed from docs; unmanaged roots are reported, not adopted |
| 938 §D item 11 | done 2026-09-10 |
| 618 §1 rationale for `baseRef: head` | already superseded by 940 / #699 |

## 6. Reach

**An instance of an existing principle.** 861 §6.10 states it for processes: the remedy cannot
live in the dying party's last moments, and an assertion no mechanism consumes is false
authority. 952 applies the same principle to worktrees and branches: creation registers an
obligation and a party other than the creator owns its termination. The dev-stack lease in
`ownership-verdict.cjs` is a third instance. 952 conforms to 861's shape (occasions, identity
is pid AND creation time, absent evidence never licenses destruction, reading never deletes)
rather than inventing a parallel one.

**Where else it applies, not built now.** Tempdocs with `status: active` and no closure owner
(936 stayed "active" for four days after its work landed); the archives this tempdoc produces
(their growth is reported precisely so they do not become the next ownerless resource); jseval
run directories under `tmp/` (938 chunk C retired six of nine by hand).

**A recurring shape worth naming: clean is not empty.** Empty porcelain status licensed the
deletion of an ignored file (researcher §7 experiment 6); an expired lease looked like a dead
process (861); zero ahead-commits looked like landed work under squash merges (this audit). A
deletion path must enumerate what it will destroy from the resource itself, never infer it
from a summary signal.

**Evidence it earns its keep.** A8 termination latency of at least 99 % within 24 powered-on
hours, a STRANDED-FINISHED count of zero for the post-952 cohort over eight weeks, and zero
preservation failures.

**Retirement condition.** When both harnesses ship native lifecycle coverage for named,
repository-rooted worktrees (release on exit including non-interactive runs, archive before
delete) and the scheduled reconciler executes zero removals in eight consecutive weeks while
coverage stays at 100 %, the execute path is removed; receipts and markers remain as the
harness-neutral record.

## 7. Acceptance (contract; each item needs result, revision, environment, evidence)

Reconciled 2026-09-10 at `d273b17ea` (branch `worktree-952-worktree-lifecycle`, Windows 11,
git 2.53, node 24). Evidence: test files named below, `tmp/probe-release.txt` (machine-local).

- [x] A1 `baseRef: "fresh"` — implemented on branch `worktree-940-worktree-base-fresh` (PR #699),
      merged into this branch (`f6e9022de`, one prose conflict resolved). Publication decision
      (2026-09-10): #699 is conflicting against `main` and its branch is checked out in another
      session's leftover worktree, so 952 lands the 940 change itself and #699 is closed as
      superseded after the merge; the 940 tempdoc rides along.
- [x] A2 branch retirement gated on a receipt — `worktree-lifecycle.cjs release` retires only on
      `LANDED`/`REDUNDANT_NOW`; `--keep-branch` requires the triple and is validated before any
      marker write. Local proof: `952-worktree-lifecycle-cli.test.mjs` cases 5, 6, 9 (22/22);
      live probe on this repository retired `worktree-952-live-probe` on `REDUNDANT_NOW`.
      Note: the default lives in the lifecycle command; `remove-worktree.cjs --delete-branch`
      stays explicit (936 invariant) and `release` passes it.
- [x] A3 verified archive — tip ref + temporary-index state commit + hashed manifest; ignored
      cap/valuable/disposable policy; `remove-worktree.cjs` refuses `--allow-ignored` without a
      verifying manifest and lifts the dirty-state blocker only for covered paths.
      Local proof: `952-worktree-archive.test.mjs` (8), `936-remove-worktree-cli.test.mjs`
      (25, incl. junction preservation and the coverage case), CLI case 11 (resume from
      `archived`), live probe archived untracked + ignored state. **Interrupted-removal on a
      real long path not exercised** (936's long-path deletion is unchanged).
- [x] A4 durable markers — per-branch git config (`justsearch-*`) + finalization records in the
      861 register scope; census over worktrees, refs and leftover branches; reading never
      writes. Local proof: `952-worktree-register.test.mjs` (8), CLI case 4.
      Deviation from the charter text: no standalone ledger of every resource (§5.1 option B);
      "process incarnation" is carried by the lock reason (Claude) or our lock grammar (Codex)
      and by the finalization record's `by`.
- [~] A5 reconciler — `reconcile` derives states, prints obligations, is silent when nothing
      needs a decision, and executes only with `--execute` AND `executeOnSchedule: true`
      (phase 1: false). Local proof: CLI case 10. **Deferred to phase 2 (decision §3.1):** the
      Windows Task Scheduler entry and the session-start/post-merge triggers are not installed;
      destination: phase 2 flip after two weeks of A8/A10 data.
- [x] A6 rule and skill text — `branch-safety.md` Cleanup → Lifecycle (janitor clause,
      `rule:lifecycle-release`) and step 5 → release step; publish/takeover/session-closeout in
      both trees; `common-workflows.md` §Worktree mechanics rewritten; `agent-workflow.md`
      closeout sentence. Gates: `check-always-loaded-budget` pass (branch-safety 12329/12431 B),
      `skills-sync --check` OK, `check-codex-agent-parity` OK, `docs-validate` no errors.
- [~] A7 both harnesses — `create`/`register`/`release` are harness-neutral; Codex how-to and
      mapping table updated; `worktree-register` (WorktreeCreate + EnterWorktree) and
      `worktree-release` (SessionEnd, both projections) wired; hook-integrity gate pass.
      **Deferred:** `justsearch-start` skill text not changed (the how-to carries the command);
      a live Codex session running `create`/`release` was not available in this session —
      recorded as an unperformed live check, destination: first Codex session after merge.
- [~] A8 metrics — `world-state.mjs` Worktrees table gains OWNER/LIFECYCLE columns (row count
      unchanged); `--lifecycle` prints coverage, latency and preservation lines. Local proof:
      `world-state.test.mjs` (16) + a live run. **Not yet measurable as a trend:** the
      post-952 cohort starts at merge; the sampled restore check is not implemented (listed
      for phase 2 alongside the scheduler).
- [x] A9 superseded text — 936 status closed into 952; 938 item 11 done; PR #691 wording
      replaced; `rule:squash-merge-verify-content-not-ancestry` still names the manual diff
      (kept: it is triage guidance; the receipt is the authorization test, §5.3).
- [~] A10 token budget — always-loaded bytes flat (an 85 B hooks-reference addition was
      reverted rather than bumped); hooks print nothing on success by construction and by unit
      test (`worktree-register.test.mjs`, `worktree-release.test.mjs`, 6 each); `reconcile`
      silent when idle (CLI case 10). **The one-week ledger measurement is post-merge.**

Not run: `./gradlew.bat build -x test` (no Java, Kotlin, Gradle or proto change in this
branch; the pre-merge build is part of publication). `regen-all --check` passes every set
except `notices`, which needs a Gradle license report this worktree has not built (no
dependency changed).

## 8. Verification plan

Unit: receipt computation with the researcher's six experiments as fixtures (many-to-one
squash, overlapping later edit, post-merge commit, custom merge driver, inherited unlanded
file, ignored file in a clean tree); lock-reason grammar parse and format; branch-config round
trip; finalization phase resume; conditional ref deletion; occasion capability matrix.
Windows integration: junction preservation, long paths, interrupted-removal resume, archive
re-hash. Live: create, work, release, reconcile on a throwaway worktree in both harnesses;
crash simulation (kill the session mid-task: SUSPECT, never removed without verification;
ORPHANED after the grace, archived, removed only in execute mode). Gates:
`check-tempdoc-numbers`, `check-tempdoc-size`, `npm run lint:scripts`, hook-integrity,
`check-codex-agent-parity`, `check-always-loaded-budget`, `regen-all --check`.

## 10. Derisk (2026-09-10)

Probes run in this worktree, read-only against the repository; scripts kept under `tmp/`
(gitignored): `receipt-probe.mjs`, `archive-probe.mjs`, `ignored-size-probe.mjs`.

| # | Uncertainty | Result | Design consequence |
|---|---|---|---|
| D1 | Receipt test on real history | `lane-F-0b` vs squash #717 → LANDED; `lane-F` vs squash #708 (main later edited the same jseval files) → LANDED; `hook-wiring-repair` vs `origin/main` → NOT-EQUAL (refused); `lane-F-0b` vs today's `origin/main` → REDUNDANT_NOW. `gh pr list --json headRefOid,mergeCommit` returns both fields for merged PRs whose remote branch is gone. | §5.3 stands unchanged |
| D2 | Temporary-index archive on Windows | Modified tracked file, untracked file and an ignored file under `tmp/` archived into one commit at `refs/archive/<r>/state` with `node_modules/` excluded by pathspec; restored into a detached worktree; source tree untouched; conditional `update-ref -d` with expected value works | §5.5 mechanism stands |
| D3 | Size of ignored, non-cache content | 15 of 24 worktrees have 0-4 MB; but `lane-F-A` 4.3 GB, `888-takeover` 473 MB, `lane-F` 230 MB, `lane-F-0b` 174 MB, all under `tmp/*` run and evidence data; `.mypy_cache` showed up as non-cache | **Amendment A**: ignored files are archived only up to a per-file cap (default 8 MB) or when matching declared-valuable globs; larger ignored paths are listed with sizes in the manifest and the release REFUSES unless `--discard-ignored` names them or they match declared-disposable globs (`tmp/**` run data). Declared caches gain `.mypy_cache/`, `__pycache__/`, `.pytest_cache/`. The researcher's "exclude only declared caches" is too strict for this repository's evidence habit |
| D4 | Worktree lock as ownership marker | Only the two live Claude sessions hold locks; every kept worktree from an ended session is unlocked, so lock presence = "a harness session holds it". Observed reason `claude session <name> (pid N)` differs from the documented `session-<id>-<user>` grammar | **Amendment B**: lock presence and pid are liveness hints only; ownership identity comes from the repository's own markers (branch config, written at `WorktreeCreate`/`create`); the reconciler never rewrites a harness lock |
| D5 | Harness worktree events | Official docs: `WorktreeCreate` and `WorktreeRemove` hook events exist with `worktree_path`, `worktree_lock_reason`, `git_repo_root`; SessionEnd fires **before** Claude's own worktree removal; SessionEnd input has `cwd` (the worktree) and `end_reason`; a hook removing the worktree before Claude does is undocumented | **Amendment C**: registration on `WorktreeCreate` (silent, exit 0) plus PostToolUse `EnterWorktree` for entering existing trees; on SessionEnd the Claude path runs `release --own --record-only` (marker + receipt, no removal) and lets Claude's exit path remove; `WorktreeRemove` records the removal; the Codex path (no such events, hooks via the adapter with `repoRoot` = the worktree the hook script lives in) runs the full `release --own --if-clean`. Anything left behind is the reconciler's |
| D6 | Per-branch git config with hyphenated keys | set/get/regexp/unset work (`branch.<name>.justsearch-fork`) | §5.2 stands |
| D7 | Substrate APIs | `process-record.cjs` exports `resolveRegisterDir(mainRepoRoot, dirName)`, `readRegister({dir, validateRecord})`, `writeRecordAtomic(path, record)`; `.codex/hooks.json` already carries SessionEnd; GNU tar 1.35 present but not needed (git objects suffice) | third scope `worktrees/` as planned |
| D8 | Always-loaded budget | `branch-safety.md` is 12421 B against a 12431 B ceiling; the Cleanup subsection (450 B) and step 5 (592 B) are the only byte source: 1042 B for the release step plus the janitor clause | A10 is tight but feasible; measure before commit |
| D9 | Overlap with PR #699 | #699 touches `branch-safety.md` hunks at lines 10, 53-66 (rules 1-5), 95, 205; 952 edits Cleanup (44-52, adjacent) and step 5 (~160) | **Amendment D**: merge `origin/worktree-940-worktree-base-fresh` into the 952 branch before editing rules; identical hunks merge clean when #699 lands first; the 952 PR body must say "#699 first" |
| D10 | `remove-worktree.cjs` extension points | `main()` sequence is admission → runtime → helper inspection → dry-run exit → re-probe → consult → final admission → delete → registration → branch → merge link. The archive phase slots between final admission and delete; the receipt slots before branch deletion; `--allow-ignored` is read at admission | plan P5 |

Not probed (accepted, with mitigation): a Codex non-interactive live run of create/release (mitigation: the Codex path is the same script with no harness-specific branch; unit-tested; live parity recorded as a deferred acceptance item until a Codex session is available); a real crash mid-finalization (mitigation: phase resume is unit-tested with a synthetic torn record).

**Confidence for the remaining work: 7/10.** The three mechanisms with genuine novelty (receipt, archive, markers) are proven on this repository's real history and Windows filesystem. The residual risk is integration breadth: two harness trees, a ratcheted rule budget, hook-integrity bites for two new hooks, and the exact Claude exit interaction, which no doc pins down and which the record-only Claude path sidesteps rather than resolves.

**Difficulty and model.** Medium-high in breadth, low-medium in depth. Library pieces (P2-P4) are bounded and test-driven: `opus` workers at medium effort, or `sonnet` for P2 whose fixtures are fully specified. CLI integration into `remove-worktree.cjs` (P5), hooks (P6) and the rule/skill/doc pass (P8) need the parent (this session) because they touch shared surfaces and the budget. Recommended: orchestrator on the current model; P2-P4 delegated to `opus`; P5-P9 in the main loop.

## 11. Plan (phase 1; phase 2 is a config flip after two weeks of A8/A10 data)

| Chunk | Deliverable | Acceptance check | Owner |
|---|---|---|---|
| P1 | `governance/worktree-lifecycle.v1.json`: sanctioned root, thresholds (§3.7), declared caches (+ D3 additions), ignored per-file cap, valuable/disposable globs, `executeOnSchedule: false`; `consult-register.v1.json` region `worktree-lifecycle` | JSON loads; region lists the files of P2-P8; `docs-validate` green | parent |
| P2 | `scripts/dev/lib/landing-receipt.cjs` + `.test.mjs`: PR lookup (`gh pr list --head … --json number,headRefOid,mergeCommit` via `run-gh.mjs` pass-through), base selection, controlled `merge-tree`, verdict, NDJSON cache in `tmp/agent-telemetry/landing-receipts.ndjson` (`telemetry-io` constant), reachability check | six researcher experiments as synthetic-repo fixtures + a "moved head" case; all verdicts exact; `UNKNOWN` on any git failure | delegate (opus) |
| P3 | `scripts/dev/lib/worktree-archive.cjs` + test: tip ref, temp-index state commit, ignored policy (cap, valuable, disposable), manifest with sizes and hashes, verify, restore into detached worktree, cleanup | archive-probe scenarios as fixtures; refusal on oversized ignored without `--discard-ignored`; verify detects a tampered blob | delegate (opus) |
| P4 | `scripts/dev/lib/worktree-register.cjs` + test: branch-config markers, lock parsing as hints, finalization record scope `worktrees/` on `process-record.cjs`, state derivation from `git worktree list --porcelain`, refs, session ledger (`ownership-verdict.readSessionActivity`), `process-identity`; census of leftovers | state matrix fixtures incl. foreign lock, missing directory, unregistered root, stale ledger with live pid; reading never writes | delegate (opus) |
| P5 | `scripts/dev/worktree-lifecycle.cjs` CLI (`create`, `register`, `release [--own] [--if-clean] [--record-only] [--discard-ignored …]`, `hold --reason --owner --review-by`, `status`, `reconcile [--execute]`); `remove-worktree.cjs`: archive phase before delete, receipt-gated branch deletion by default, `--keep-branch`, new `--allow-ignored` semantics, `--session-id` default from `resolveCallerSessionId` | `936-remove-worktree-cli.test.mjs` extended, all 23 existing cases still pass; new CLI test file; `npm run lint:scripts` | parent |
| P6 | Hooks `worktree-register.mjs` (WorktreeCreate + PostToolUse EnterWorktree, telemetry role, unit bite) and `worktree-release.mjs` (SessionEnd, both harnesses, record-only under Claude, full under Codex); manifest entries; `regen-all --only agent-hooks-wiring,codex-hooks` | hook-integrity gate green; `check-codex-agent-parity`; bites run | parent |
| P7 | `world-state.mjs`: lifecycle gatherer, `OWNER`/`LIFECYCLE` columns, verdict from lifecycle state, `--lifecycle` metrics block | existing world-state tests + new gatherer test; row count unchanged | parent |
| P8 | Docs and prompt surfaces: merge #699 branch in; `branch-safety.md` (Cleanup + step 5 → release step + janitor clause, net bytes ≤ 1042); `common-workflows.md` §Worktree mechanics; `agent-workflow.md` release step; Codex how-to; skills takeover/publish/session-closeout in both trees; `skills-sync.mjs`; 936 status → closed into 952; 938 item 11 → done; 861 §6.4 note that worktree-teardown consults receipts | `check-always-loaded-budget`, `docs-validate`, `skills-sync --check`, parity check | parent |
| P9 | Verification: `npm run lint:scripts`, `node scripts/agent-analytics/run-all-tests.mjs`, hook-integrity, `regen-all --check`, live create → release → reconcile in this Claude session on a throwaway worktree, crash simulation via a synthetic torn finalization record; Codex live parity recorded as deferred | acceptance table in §7 reconciled with evidence | parent |

Ordering: P1 → (P2 ∥ P3 ∥ P4) → P5 → P6 → P7 → P8 → P9. Delegated chunks work in this worktree on disjoint new files and make no git writes; the parent reviews, runs tests, commits.

## 12. Index (dated)

- 2026-09-10 — Audit of 175 branches / 52 worktrees, root-cause investigation, external
  research brief and recommendation, backlog cleanup (176→35 branches, 53→24 worktrees,
  ~34 GB freed, everything archived to a bundle). Evidence sidecars in `952-evidence/`.
  Charter written; decisions §3 taken by delegation; design §5 and reach §6 written after
  reading the substrate (remove-worktree.cjs phases, 861 registry grammar and occasions,
  session ledger writer, world-state gatherers, hooks manifest, 936/938/940/949/950 texts).
  Alternatives considered in §5.1. Owner question the same day: token cost per agent. Answer:
  §5.9 and A10 — normal path is zero model computation (SessionEnd hook executes release on
  the session's own clean worktree; registration silent; no new world-state section).
  Next: `/derisk` then `/plan`.
- 2026-09-10 (later) — Derisk §10 (four amendments), plan §11, implementation P1-P9 on this
  branch (12 commits ending `d273b17ea`). Findings during implementation, all fixed and tested:
  a `.gitattributes` merge driver survives `-c merge.default=` (receipt refuses on any driver);
  git's short exclude pathspec dies on `__pycache__` (long form used); Claude's lock names the
  worktree, not the session (attribution accepts either); `remove-worktree.cjs` refused archived
  dirty state (a verified manifest now lifts the blocker for covered paths only); text `status`
  crashed on HELD rows; `--keep-branch` wrote a marker before validation. Live probe on this
  repository: create → archive → verify → remove → retire. Backlog cleaned the same day (audit
  in `952-evidence/`). Remaining: publication (owner), phase-2 scheduler + restore sampling,
  Codex live parity, one-week A8/A10 measurement.
