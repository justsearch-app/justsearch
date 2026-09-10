---
title: "Local main realignment — 297 stranded commits, and why worktrees now branch from origin"
type: tempdocs
status: "DONE 2026-09-06 — main reset to origin/main (backup branch kept); baseRef head→fresh; hook flags a main ahead of origin; PRs 696/644 closed as superseded, 697 merged, 698 (908) and the 932 rebuild opened"
created: 2026-09-06
updated: 2026-09-06
lane: agent workflow / branch safety
related:
  - 618-agent-developer-velocity-friction     # chose baseRef:"head" when local merges were the publish path
  - 727-session-transcript-friction-mining     # F-3: uncommitted-changes-on-main check in the same hook
  - 829-publish-workflow-velocity              # R4 merge queue: main became PR-only
  - 939-codex-sandbox-verifier                 # the PR whose 134-file inflation exposed this
---

# 940 — Local main realignment

## What was found

On 2026-09-06 the shared checkout's `main` was **297 commits ahead of and 45 behind
`origin/main`** (merge base: PR #642, 2026-09-04; content difference 1 283 files). Since
ADR-0045 / 829 R4 made `main` PR-only, every commit made directly on local `main` is stranded
by construction, and `git merge origin/main` on top keeps the divergence alive instead of
resolving it. The 297 broke down as ~200 merge commits plus 90 real ones: observation shards
(July–August, store since retired by 872), six `docs(908)` commits, and two local merges of the
lane D bundle (2026-09-05).

`worktree.baseRef: "head"` (chosen by 618 §1 when local fast-forward merges *were* the publish
path) then seeded every new worktree from that tip. PR #696 (939, one commit) showed 134 changed
files; PR #653 (932, one commit) showed 189. Fourteen branches descended from the polluted tip.

## What was unpublished, really

Content check, not ancestry (`squash-merge-verify-content-not-ancestry`):

| Content | Verdict |
|---|---|
| Lane D bundle (915), 173 files | Obsolete — landed as #645/#646/#647/#657/#660/#684; origin's version ~10 800 lines richer. Its three follow-up commits (boot-import polling, `entity_boost` removal, worker-core test-to-code changeset) are present in origin's reworked files. #644 closed. |
| Tempdoc 908 + 4 implementation commits | Real. Rebuilt as `worktree-908-token-efficiency-trend` → PR #698 (one README conflict, reconciled against #651). |
| Uncommitted 903 inference-runtime edits (5 files) | Older than `worktree-903-takeover` (3affd35c5, §12 verdict). Snapshot in `tmp/main-wip-2026-09-06.patch`. |
| Uncommitted settings / compaction note | Local preference; the compaction note is refuted by 908. Same snapshot. |
| Untracked: tempdocs 935, 919 (newer than branch copy), blast-radius skill | Only copies. Parked on `rescue/main-checkout-untracked-2026-09-06`; left in place as untracked. |

## What changed

1. `main` reset to `origin/main` after `backup/local-main-2026-09-06` was created at the old
   tip (8da7a24d9). Untracked files survived; the tracked WIP is in the patch above.
2. `.claude/settings.json` `worktree.baseRef`: `"head"` → `"fresh"` (harness fetches
   `origin/HEAD` when >24h stale, 5s cap; `"fresh"`/`"head"` are the only values — a branch
   name is not accepted). 618's reason for `"head"` no longer exists.
3. `worktree-base-hint` hook: compares the new worktree with `origin/main` (not the main
   checkout's HEAD) and emits a STOP note when local `main` is ahead of `origin/main`.
   Unit-tested; manifest note updated.
4. `branch-safety.md`: `never-commit-on-local-main` rule; `verify-worktree-base` reworded.

## Open items

- 932: PR #653 (189 files) is superseded by the rebuilt branch `worktree-932-pin-retirement-v2`
  (cherry-pick of c210b4147 onto origin/main); close #653 once its replacement PR is open.
- Branch triage (content-checked per own commit, 2026-09-06; 6 worktrees under
  `.claude/worktrees/` could not be status-checked from this session):
  - **DONE, deletable** (content on origin): `codex/906-{ingestion,mcp,search,takeover,publish}`
    (#688), `codex/906-runtime-verification` (core via #688), `codex/897-current-main` (#694),
    `codex/921-permanent-review-record` (#632), `codex/937-{agent-model-routing,publication-record}`
    (#689/#690), `codex/lane-d-pr-b` (#647), `codex/lane-d-pr-c1`, `worktree-lane-d-pr-a` (#644
    closed). `codex/900-static-analysis-concurrency` is EMPTY (one uncommitted tempdoc edit).
  - **Unlanded, substantial — rebuild by cherry-pick onto origin/main, never by merge:**
    `codex/919-takeover` (3ac143703, 77 files: NativeSessionHandle/SessionHandle/SpladeEncoder +
    jcstress); `codex/926-hook-architecture-derisk` (10 commits, ~30 files, hook policy layer;
    note it deletes `repeat-guard`, still live on origin); `codex/888-takeover` and its subsets
    `888-{hooks,python,rust}` (46 commits, SpotBugs/Rust/Python remediation, `rust-toolchain.toml`
    absent on origin); the `codex/899-*` family (registry/diagnostics landed via #634/#640; the
    Node SDK piece `SdkOpenApiProjection.java` did not).
  - **Unlanded, docs-only, one file each:** `codex/892-takeover`, `codex/901-takeover`,
    `worktree-903-takeover` (889 lines of 903 incl. the §12 verdict).
  - **Dirty worktrees, do not delete:** `codex/919-{checker,lifetime-tests,splade}` carry
    uncommitted ORT/SPLADE work beyond their branches.
  - Open PRs unaffected by the pollution: #622 (lane E part 1), #695 (938).
- `models/onnx/reranker-minilm-backup/` (219 MB) and `docs/observations.d/` in the main
  checkout are untracked residue; move out of the tree.
