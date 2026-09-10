# Research brief: ending the lifecycle of agent-created git worktrees and branches

Self-contained. The researcher has no access to the repository, its docs, or its history. Everything needed is in this brief.

## 1. The cause, in one sentence

**Every workflow in the project mandates creating a git worktree and branch, but no workflow step, tool default, automated process, or rule owns ending one, and the safety rules assign any worktree of uncertain ownership to "another session", so the default terminal state of every worktree and branch is "kept".**

Sprawl is not an accident or a bug. It is the steady-state output of a lifecycle with a required beginning and an unowned end, operated by short-lived agents whose sessions finish before the point at which removal would be safe.

## 2. Environment (facts the solution must fit)

- One git repository on a Windows developer machine. One main checkout stays on `main` and may hold other agents' uncommitted work.
- Work is done by AI coding agents from **two harnesses** (Claude Code and OpenAI Codex), several sessions in parallel, each in its own git worktree on its own branch. Sessions are short-lived (minutes to hours) and are not guaranteed to end cleanly. Some worktrees are also created by subagents for isolated chunks.
- Publication is **PR + squash merge** through a merge queue. Consequences: a branch's commits never appear in `main`'s history, `git branch --merged` reports nothing, and GitHub deletes only the remote branch on merge. Whether work has landed can be judged only by content diff against `main`.
- A local `main` once drifted ~300 commits ahead of `origin/main` because new worktrees branched from local HEAD. Dozens of branches inherited those commits; they now show hundreds of "ahead" commits that are duplicates of landed content. A fix that branches new worktrees from `origin/main` exists but is not yet merged.
- Harness facts (from official Claude Code docs): a worktree with uncommitted work or new commits prompts keep/remove at interactive exit ("keep" is a valid answer); unnamed clean worktrees are removed automatically; non-interactive runs never prompt; the "exit worktree" tool returns to the main checkout and keeps the worktree; a periodic sweep removes only worktrees the harness itself created for subagents or background sessions, never manually created ones; removing via the harness also deletes the branch. Codex has no worktree lifecycle at all; its sessions run `git worktree add` by hand into whatever directory the operator chose.
- A repository script exists for safe removal (handles Windows junction and long-path hazards, refuses dirty, locked, or unrecognised trees, records a session-to-merge attribution). Branch deletion is an opt-in flag. A world-state report classifies each worktree as ACTIVE, STRANDED-FINISHED (clean, unpushed commits, idle), STALE-CANDIDATE (nothing unique), or DIRTY-IDLE, but nothing instructs anyone to act on the verdict.
- Project rules that must survive any solution: never share a worktree between sessions; never delete, move, or restore files in the main checkout you did not create; never run destructive git in the main checkout; treat unmerged content as valuable until proven landed by content; publishing and merging require explicit per-action authorization from the human owner.
- Documented workflows that mandate worktree creation: "take over a tempdoc" (investigate, produce a verdict, deliberately stop before implementing, hand the worktree "to the owner"), "publish" (often from a fresh branch separate from the implementation branch), delegated sub-lanes (one worktree per worker), and subagent isolation.

## 3. Measured state (2026-09-10)

| Measure | Value |
|---|---|
| Local branches | 175 |
| Local branches whose PR merged but branch still exists | 94 (median 22 days since merge) |
| Local branches whose remote was deleted by GitHub | 95 |
| Branches never pushed | 53 |
| Registered worktrees | 52, all created within 8 days, ~84 GB on disk |
| Worktrees the world-state report already flags as finished/stale/dirty-idle | 36 of 52 |
| Merged PRs with a recorded worktree teardown | 413 of 654 (63%); in the last two weeks 58 teardowns for 116 merges |
| Worktrees torn down with the branch kept | 37 |
| Worktrees created by hand outside the harness's directory (Codex) | 26 |
| Branches per busiest tempdoc | 8 (sub-lanes plus separate publish and record branches) |
| Worktrees reused by checking out a new branch per PR, leaving the old branch | at least 5 (one cycled through 5 branches in one day) |

Recent history that matters: on the same day the local-main drift was repaired, the written merge procedure was changed from "remove the worktree and delete the branch after verifying the merge" to "preview the removal with a dry-run", and the publish skill's cleanup step became conditional ("only when the safety rules permit"). The pile-up began that week.

## 4. Research question

**What is the correct design for owning the end of a worktree's and branch's life in a multi-harness, multi-session, squash-merge, agent-operated repository, such that the steady state is "removed when landed or abandoned" instead of "kept", without violating the safety rules in §2?**

"Correct" means: it removes the cause in §1 (assigns the end of the lifecycle to a specific owner or mechanism), not just the symptom (a one-time cleanup), and it degrades safely when an agent session dies mid-task.

## 5. Sub-questions the research must answer

1. **Ownership model.** Who or what should own the end of a worktree's life: the creating session, the merge event, a periodic garbage collector, a lease with TTL, the human owner, or the harness? Compare models used by ephemeral CI environments, preview deployments, Kubernetes finalizers or TTL controllers, cloud resource tagging with owner and expiry, and git tooling for squash-merged branch pruning. State the trade-offs when sessions die unexpectedly.
2. **Landed-ness test.** What is a sound, cheap, automatable test that a squash-merged branch's content is fully in `main`, robust to main evolving afterwards? Evaluate: content diff of the branch's touched files against `main`, `git cherry` and patch-id equivalence, `git merge-tree` simulation, GitHub API "PR merged with this head SHA", and combinations. State false-positive and false-negative modes.
3. **Abandonment test.** How should "abandoned" be distinguished from "in flight" for a worktree with unpushed commits and no live session? What signals are trustworthy (session ledger heartbeat, lease expiry, last commit age, lock files, PR state), and what is the safe action for each verdict (remove, archive to a ref, notify, quarantine)?
4. **Harness asymmetry.** Given that one harness has partial automatic cleanup and the other has none, should the solution live in the repository (scripts, hooks, skills that both harnesses run), in each harness's native features, or both? What is the minimum shared contract?
5. **Workflow shape.** Which of the mandated creation points are unnecessary or should be reshaped: separate publication branches, one worktree per investigation that deliberately stops before implementing, worktree reuse with a fresh branch per PR? Is "branch per PR, worktree per session" the right unit, or should it be "worktree per tempdoc with branch reuse"?
6. **Safety compatibility.** How can automatic removal coexist with "never touch another session's worktree"? Candidate answers: ownership metadata at creation, removal only by the owner or by an explicitly authorized janitor, archive-before-delete (a `refs/archive/*` ref or bundle) so removal is reversible, dry-run reports that a human approves in batch.
7. **Failure modes of the obvious fixes.** For each of: "delete branch by default on teardown", "hook on session end", "cron GC", "make agents run world-state and act", "GitHub delete-branch-on-merge plus local prune": what breaks, what is bypassed, what is silently destructive?

## 6. Constraints on the answer

- Must work on Windows with long paths and directory junctions inside worktrees.
- Must not require an agent to run destructive git in the shared main checkout.
- Must be robust to a session ending without any exit hook running.
- Must not treat ancestry as proof of landing (squash merges).
- Must preserve any content not provably landed; irreversibility is acceptable only after an archive step or explicit human approval.
- Must be executable by both harnesses through plain commands (Node scripts, git, PowerShell) since only one harness supports native hooks and neither supports the other's tools.
- One-time cleanup of the current backlog is out of scope; the deliverable is the steady-state design. (The backlog is already classified.)

## 7. Deliverable

A written recommendation, at most ~1,500 words plus tables, containing:

1. **A restated cause** in the researcher's own words, confirming or refuting the one-sentence definition in §1. If refuted, say what the cause is instead and why.
2. **The recommended lifecycle model**: owner of creation, owner of termination, the state machine (states, transitions, who triggers each), and what happens on session death.
3. **The landed-ness and abandonment tests** to use, with their failure modes stated.
4. **The minimum set of changes**, each tagged with where it lives (repository script, rule text, harness setting, GitHub setting, workflow shape) and whether it is a default change or a new mechanism. Prefer defaults over new mechanisms. Flag any change that touches the safety rules in §2 and justify it.
5. **Rejected alternatives**, one line each, with the reason.
6. **How to know it worked**: two or three measurable steady-state indicators (for example, "worktrees older than N days with a landed branch: 0", "merged PRs without a recorded teardown: under X%") and where they would be measured.
7. **Sources**: official documentation and primary references for any tool, harness behaviour, or pattern relied on. Mark anything asserted from blog posts or inference as such.

The researcher should not propose a cleanup script for the existing backlog, should not assume access to the repository, and should ask no clarifying questions: where a fact is missing, state the assumption and proceed.
