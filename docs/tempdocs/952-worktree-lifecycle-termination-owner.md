---
title: Worktree and branch lifecycle — a durable termination owner
status: DESIGNED (2026-09-10) — cause confirmed by audit and external research; backlog cleaned; decisions taken; design §5 settled on Git-native ownership markers + bounded finalization record; next /derisk, /plan
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

- [ ] A1 `worktree.baseRef` is `"fresh"` on `main` (PR #699 or equivalent merged); the hook
      flags a local `main` ahead of `origin`.
- [ ] A2 `remove-worktree.cjs` retires the branch by default when its exact head is covered by
      a receipt or `REDUNDANT_NOW`; `--keep-branch` requires `--reason`, `--owner`, `--review-by`.
- [ ] A3 Verified-archive path exists for dirty/untracked/ignored state and detached heads;
      Windows regression covers junction preservation, long paths, and interrupted removal
      (resume from recorded phase). No deletion without a verified archive.
- [ ] A4 Durable obligation ledger outside any worktree: resource id, path, refs, session and
      parent ids, process incarnation, lease/generation, fork SHA, `origin/main` anchor,
      retention policy, phase. Census reconciles ledger against `git worktree list` + all local
      refs each sweep; leftovers (directory missing, branch orphaned) are obligations too.
- [ ] A5 Reconciler runs unattended (Windows Task Scheduler, plus session-start and
      post-release/post-merge triggers); expiry means SUSPECT → verify writers stopped → 24 h
      grace → archive → finalize; uncertainty → QUARANTINED with a named decision owner.
- [ ] A6 Rule text: `branch-safety.md` merge step 5 restored to an imperative with the
      archive-then-remove sequence; janitor authorization amendment; publish skill step 4 and
      takeover skill updated in both `.claude/skills` and `.agents/skills`; regenerated where
      derived.
- [ ] A7 Both harnesses create worktrees through the repository command; Codex how-to and
      `justsearch-start` name it; hooks emit registration/release hints only.
- [ ] A8 Metrics in `world-state.mjs` output: ownership coverage (target 100% of new
      resources), termination latency (≥ 99% of released, unheld, preservation-complete
      resources retired within 24 powered-on hours; blocked counts and ages listed
      separately), preservation integrity (0 deletions without verified archive; sampled
      restore checks; archive bytes/age). Post-adoption cohort measured separately from the
      pre-952 backlog.
- [ ] A9 Superseded text retired in the same change: 936 status → closed-into-952; 938 §D item
      11 → done; 940 status → prerequisite landed; PR #691 dry-run-only wording replaced.
- [ ] A10 Token budget: always-loaded bytes do not grow (`check-always-loaded-budget`);
      session-start hook-injected context from lifecycle hooks is 0 bytes when nothing needs a
      decision and at most one line per item otherwise; the SessionEnd release path is proven
      to run without any model turn (hook-integrity bite + a live session in each harness);
      the world-state Worktrees table has the same row count as before for the same worktrees.
      Measured over one week of sessions via the 886 ledger after phase 1 lands.

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

## 9. Index (dated)

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
