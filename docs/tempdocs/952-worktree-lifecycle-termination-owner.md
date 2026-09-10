---
title: Worktree and branch lifecycle — a durable termination owner
status: CHARTERED (2026-09-10) — cause confirmed by audit and external research; backlog cleaned; design not started
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

## 3. Decisions to take before design (owner)

1. **Adopt the controller model** (researcher §2) versus the cheaper "defaults + rule text"
   subset (researcher §4 rows 1–2, 5, 7) as phase 1 with the controller as phase 2. Proposed:
   phase 1 now, phase 2 chartered separately once phase-1 measurements exist.
2. **Janitor authorization**: amend `branch-safety.md` rules 1 and 4 to authorize a registered
   janitor to terminate *released or verified-inactive managed* resources after preservation.
   Unknown, foreign-locked, or unstable resources stay protected. No destructive git in the
   main checkout; publish/merge approvals unchanged.
3. **Preservation standard**: archive committed tips under `refs/archive/<resource>/<head>` or
   a verified bundle, plus a separate archive of index/untracked/valuable-ignored state.
   "Clean porcelain" is not "nothing valuable" (researcher §7 experiment 6). Archive failure
   blocks removal. Archive growth is reported, never auto-expired.
4. **Landed-ness test**: content-verified receipt per exact head `H` against the actual
   squash commit `S` with anchor `B` (`git merge-tree --write-tree --merge-base=B S H`, accept
   only exit 0 and tree == `S^{tree}`), `REDUNDANT_NOW` fallback against fetched `origin/main`,
   `UNKNOWN` never authorizes deletion. This audit's file-level content diff and added-line
   presence heuristics are triage, not authorization.
5. **Workflow shape**: one worktree per concurrent writer/session; one branch per PR; publish
   the implementation branch (no separate publication-record branches); takeover skill ends by
   removing its worktree or handing off a ref; worktree reuse must retire the previous branch.
6. **Codex path**: repository-created worktrees for both harnesses via a create/release command
   pair; manual `git worktree add` into ad-hoc roots stops.

## 4. Scope

In scope: creation registration, release signal, reconciler with restartable finalization,
archive-before-delete path in `remove-worktree.cjs`, branch retirement by default, rule and
skill text, hook signals for both harnesses, steady-state metrics, retirement of superseded
text in 936/938/940 and of the dry-run-only wording from PR #691.

Out of scope: the existing backlog (cleaned 2026-09-10, audit addendum 4 — remaining kept
items are enumerated there); merging the unlanded branches the audit found (#698, #699, #700,
`hook-wiring-repair`, 919, 888, 828, 795 and the takeover tempdocs) — each is its own
publication decision.

## 5. Acceptance (contract; each item needs result, revision, environment, evidence)

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

## 6. Verification plan (sketch, to be tightened in `/design` and `/plan`)

Unit: obligation ledger, receipt computation (`merge-tree` acceptance incl. the researcher's
six failure experiments as fixtures), phase machine restartability, conditional ref deletion.
Windows integration: junction preservation, long paths, interrupted removal, `--allow-ignored`
vs archive. Live: create → release → reconcile → removed on a throwaway worktree; crash
simulation (kill the session mid-task, confirm SUSPECT → QUARANTINED, never removed without
verification); Codex non-interactive create/release parity. Gate: `check-tempdoc-numbers`,
`check-tempdoc-size`, `npm run lint:scripts`, hook-integrity, `regen-all --check`.

## 7. Index (dated)

- 2026-09-10 — Audit of 175 branches / 52 worktrees, root-cause investigation, external
  research brief and recommendation, backlog cleanup (176→35 branches, 53→24 worktrees,
  ~34 GB freed, everything archived to a bundle). Evidence sidecars in `952-evidence/`.
  Charter written. Next: owner decisions in §3, then `/design`.
