<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. -->

# Parallel Agent Worktree Guide

## Worktree Lifecycle

Multiple agent sessions run in parallel, each in its own **git worktree**.
The main checkout (`F:\JustSearch`) stays on `main` and is never switched.

### Creating a worktree

**Within a session** — `EnterWorktree { name: "feature-name" }` creates
`.claude/worktrees/<name>/` on branch `worktree-<name>` based on local `HEAD`
(`worktree.baseRef: "head"` — carries your unpushed/just-merged commits,
tempdoc 618 §1). Make it dev-ready from inside:
`node scripts/dev/prepare-worktree.cjs` (`--no-dist` for FE-only) — it also
seeds the gitignored `.mcp.json` / `settings.local.json` from their `.example`
files. Shared models and the shared cuda12 llama-server resolve from the
**main** checkout automatically (GPU-only by design — no CPU fallback;
inference fails CLOSED without cuda12). Full mechanics — config-seeding
caveats, cuda staging, env vars for backends started OUTSIDE the dev-runner —
in `docs/reference/contributing/common-workflows.md` §Worktree mechanics
(relocated, tempdoc 681).

**New terminal session** — launch Claude with the `--worktree` flag:
```bash
claude --worktree feature-name
```
Same mechanics as `EnterWorktree` but starts a fresh session in the worktree.

**Subagent isolation** — use `isolation: "worktree"` on the Agent tool:
```
Agent { prompt: "...", isolation: "worktree" }
```
The subagent gets its own temporary worktree. Auto-cleaned if no changes;
preserved (with path/branch returned) if changes were made.

### Leaving a worktree

**Within a session** — use the `ExitWorktree` tool to return to the main
checkout without ending the session. Useful when worktree work is done but
the session continues (e.g., merging from main).

### Cleanup

- **`EnterWorktree` / `--worktree`**: On session exit, Claude prompts whether
  to keep or remove the worktree.
- **`ExitWorktree`**: Returns to main checkout; worktree is preserved for
  later re-entry or manual cleanup.
- **Subagent worktrees**: Auto-cleaned if unchanged; returned path if changed.
- **After merge**: GitHub deletes merged source branches; delete local
  branches only after verifying they were merged.

## Hard Rules

1. **Never share a worktree** between two agent sessions. <!-- rule:never-share-worktree -->
2. **After compaction**, verify your worktree and branch. <!-- rule:after-compaction-verify -->
   The `compact-restore` hook emits a one-shot **Current worktree** block (dir + branch) only
   when it verifies the saved session, worktree, and branch — confirm it matches; on a
   non-compaction session start or omitted snapshot, check directly:
   ```bash
   pwd
   git branch --show-current
   ```
   If either doesn't match expectations, investigate before editing.
3. **Never run destructive git commands in the main worktree.** The main
   checkout stays on `main` and may contain uncommitted work from other agents.
   Destructive commands destroy that work silently. <!-- rule:never-destructive-git-in-main -->
4. **Never delete, move, or restore files in the main worktree that you
   didn't create.** Untracked or modified files may belong to another
   agent's in-progress work. If they block your build, ask the user —
   do not remove them unless the user explicitly approves. <!-- rule:never-delete-untracked-in-main -->
5. **Always verify a new worktree's base contains the work you expect**
   before writing code. `worktree.baseRef:"head"` (in `.claude/settings.json`)
   makes `EnterWorktree`/`--worktree`/subagent worktrees branch from local
   `HEAD` by construction, but a manual `git worktree add` ignores it and the
   setting has had harness-version bugs — so assert the base directly:
   `git log -1 --oneline -- <a file your task depends on>` or grep a known
   symbol. This converts the silent "building on a stale base" trap (tempdoc
   618 §1 — local `main` can be dozens of commits ahead of `origin`) into an
   immediate, legible failure. <!-- rule:verify-worktree-base -->

## Enforced by native `permissions.deny`

`.claude/settings.json` carries `Bash(git push --force*)` / `Bash(git push -f*)`: the harness
refuses them everywhere, including under `bypassPermissions`. Prefix-match, per
compound-command segment. The refspec form `git push origin +main:main` is not expressible
as a prefix rule and is **not** blocked — don't use it. <!-- rule:never-force-push -->

**Destructive git in the main worktree is a prose rule, not a block** (930 row 4: the hook
had 0 true positives and 11 false over 30 days, and deny can't be scoped to one worktree).
In the main checkout do not run `git checkout <branch>`, `git switch`, `git reset --hard`,
`git clean -f`, `git restore .`, or whole-tree `git checkout -- .` — they destroy other
agents' work. Single-file `git checkout -- <path>` is fine. Check `pwd` first.

**Allowed in the main worktree:** `git status`, `git log`, `git diff`,
`git add`, `git commit`, `git push`, `git merge`, `git worktree`,
`git fetch`, `git pull`, `git stash`. Branch protection can reject a direct
`git push` to `main` (confirmed 2026-07, tempdoc 695) — route the change
through a worktree + PR instead. `git commit` on `main` stays possible
regardless, so a rejected push can strand local commits ahead of `origin`.

**Warning — `git stash` with staged changes:** Never use `git stash` (especially
`--keep-index`) to inspect the staging area. Use `git diff --cached --stat`
instead. Stash + pop silently drops unstaged modifications when combined with
staged renames. To inspect staged vs unstaged state, use read-only commands:
- `git diff --cached --stat` — what's staged
- `git diff --stat` — what's unstaged
- `git status` — overview of both

**Allowed in worktrees:** All git commands except force-push. Worktrees are
isolated — destructive operations only affect the agent's own work.

## Shared Dev Stack

Only one dev stack runs at a time (memory/port). **Multi-agent safety:** before starting, call
`quick_health`; if another session holds it, get user approval before starting your own or taking it
over (`OWNER_CONFLICT` / `ownership.verdict: CONTENTION`). A `force` takeover requires explicit user
direction. The tools return `ownership.verdict` + `recommendedAction` telling you what to do; stop the
stack when you finish so other agents can use it. Long measurement campaigns should declare
`leaseDurationSec` (30-7200s) at start (tempdoc 735 G6) so the shared lease holds through minutes of
busy-but-session-silent work instead of lapsing on the default 30s passive-expiry window. Convention:
one Gradle build runs at a time across agents — concurrent Gradle invocations can corrupt shared
caches (observed 2026-07-14).

The full dev-stack contention model moved to `/dev-stack`; load it before live
backend work.

**Agent-spawned helpers (ui-shot's Vite, `serve-worktree-fe`) are reaped automatically** —
session start/end + worktree teardown, via the `tmp/dev-runner/agent-spawns/` registry. Never
hand-`taskkill` one; run `node scripts/dev/agent-spawn-sweep.cjs` first (tempdoc 861).
<!-- rule:agent-spawn-session-end-reap -->

**A build-blocking agent-spawn holder is named, never auto-killed.** A `gradlew`/`npm`
invocation about to write a path a registered spawn holds gets a PreToolUse hint naming it +
a remedy — advisory only (861 [A4]). <!-- rule:agent-spawn-build-hint -->

## Merge Workflow

**Never merge or publish a PR without an explicit, per-action go-ahead.** <!-- rule:no-merge-without-authorization -->
Authorization to *do the work* (implement a design, open a PR) is not authorization
to merge it — merging is a separate, consequential action. Take the PR to
green-and-ready, then stop and wait for an explicit "merge it"; the one exception is
when the user names the merge in the same instruction. Predictable evasion: treating
an upstream "do X" as covering the whole downstream merge/publish chain.

1. **Branch verification (required):** In your worktree, run <!-- rule:pre-merge-gradle-build -->
   `./gradlew.bat build -x test` before marking a PR ready.
2. Open/update a PR; title/body, review, CI are the durable record.
3. `node scripts/dev/run-gh.mjs enqueue <N>` revalidates the live squash and
   managed review records, then enqueues once checks pass; the queue runs
   `merge-group` CI and squash-merges. Direct `gh pr merge` bypasses that proof
   and is blocked by the shared agent hook. A rejection means CI failed —
   investigate before retrying. Keep checkpoint/retry commits off `main`;
   use the PR title/body.
4. After merge, update local `main` and run `./gradlew.bat build -x test`.
5. After verifying the merge, keep the shell outside the target and, from the
   owning repository root, preview its exact registration:
   `node scripts/dev/remove-worktree.cjs <registered-path> --dry-run`.
   Ignored paths need `--allow-ignored`; local branch deletion needs
   `--delete-branch`. The tool preserves junction targets and
   removes only that exact Git registration. Merge attribution requires an
   explicit known `--session-id`; omission or `unknown` skips the merged-PR
   lookup and telemetry writer. Full mechanics:
   `docs/reference/contributing/common-workflows.md`.

### Publishing docs-only changes (history granularity) <!-- rule:docs-ride-along -->

Public `main` is a curated narrative, not a working log. ADR-0045 already makes
the merge *squash* a branch into one commit; this rule governs the complementary
question of whether a change should be its **own** public PR at all (tempdoc 653
"axis 2").

- A **tempdoc** edit (`docs/tempdocs/**`) is dated working history. Do not open a
  standalone PR for a tempdoc-only change. Ride it along in the same PR as the code
  it documents, or batch several tempdoc edits into one periodic `docs(tempdocs): …` PR.
- A **canonical-doc** update (`docs/{explanation,reference,how-to,decisions}`) is
  durable current truth and may stand alone as its own PR/commit.
- A branch mixing docs with code is already a ride-along — publish it normally.
- **A prior standalone tempdoc PR is not a precedent.** Predictable evasion (653
  follow-up): citing an earlier `docs(NNN)` PR as licence chains one non-ideal PR
  into a series — and the cited precedent often is not comparable (the observed
  chain's root PR carried canonical docs, which this rule lets stand alone).
  Re-qualify each push on this rule's own terms.

The trigger to self-check is a branch whose whole diff is exactly ONE working-history
file. Multi-file batches (a fold, several tempdoc edits) are what this rule asks for.
Rationale and the worked example live in
`docs/reference/contributing/agent-guide.md` (History publication).

### Verifying whether squash-merged work already landed <!-- rule:squash-merge-verify-content-not-ancestry -->

Since ADR-0045 squash-merges every PR into one commit, a squashed branch's original commits
never appear in `main`'s history. **Do not conclude a branch's work is "unmerged" from
`git log` / branch-ancestry alone** — that is exactly the signal squash-merging invalidates.
The reliable check is content, not ancestry: `git diff <branch> main -- <paths>` (empty output
= that content already landed under some other, differently-titled squashed commit). Verify
this way before a conclusion like "X isn't on main yet" feeds a user-facing decision.

### Working on shared `main` safely (multi-agent)

The main checkout routinely holds other agents' uncommitted WIP. Keep PR
publication and cleanup scoped to your branch:

- Do not use local merge/fast-forward as the normal public path; publish by PR
  squash, then update `main`.
- Stage your own files explicitly (`git add <paths>`), not `git add -A`.
- Keep diffs scoped to your task: untouched-code reformatting conflicts with
  other worktrees.
- There is no shared inbox file to append to (the observations store was retired,
  tempdoc 872): route out-of-scope findings per CLAUDE.md `log-pre-existing-issues`.

## Recovery

If you find yourself on the wrong branch or in the wrong directory:
- Run `git branch --show-current` and `pwd` to orient.
- If commits landed on the wrong branch, cherry-pick them to the correct one.
- If uncommitted work is in a stash, verify which branch it belongs to before
  popping (`git stash show stash@{N}`).
