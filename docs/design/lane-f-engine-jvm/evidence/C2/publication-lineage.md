# PR1 publication lineage correction

## Current state (2026-09-14)

Draft [PR727](https://github.com/justsearch-app/justsearch/pull/727) is active on
`codex/lane-f-pr1` in `F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify`.
Initial candidate cfa4a78b8 matched the original pushed8bf81ea2d product/build sources.
Checkpoint29d4c8233 passed all13 jobs in CI34783675186; see
[hosted record](publication-hosted-checkpoint.json). Isolated preflight1358 passed;
[its manifest](publication-preflight-completion.json) retains the earlier dependency-lock
and Rust-selection failures without claiming those invocations succeeded.

Later response-mapper, reset-validator and producer changes have their own focused proof.
They are not covered by the earlier checkpoint's hosted success. The current
[handoff](../../handoff.md) names exact roots, limits and next work. PR718 and the original
checkpoint branch remain preserved. Stage-F merge placement and remaining lane acceptance
are unchanged; this draft is not ready to merge.

## Historical lineage and prior evidence (preserved)

2026-09-13. Origin main b4d972b6b adds the identity/privacy safeguards from PR726.
CI34779298814 passes12 jobs and fails the introduced-identity check on the old PR718 range.
An address-free audit at a4afee776 counts379 introduced commits,365 with a formerly configured
owner identity. Effective author/committer and the latest commits already use the approved
public identity. This is historical metadata, not a newly detected signing credential.

## Decision

Preserve worktree-lane-F-A and PR718's immutable checkpoints/evidence. After the current owner
item is committed, merge current main into that checkpoint, then create a fresh PR1 candidate
from main and squash-apply the reviewed lane content. Do not replay379 commits, rewrite the
existing remote, alter the guard, or claim historical erasure. Continue future per-item commits
on the successor branch in the same lane worktree; retain the old branch as a review source.
A new draft is required because GitHub does not change a pull request's head branch. The final
Engine merge remains at F, and all unfinished C2/D1/D2/E/F acceptance items remain binding.
Independent investigation recommends this path over sanitized replay, which would change every
historical identifier and republish superseded content. The decision preserves ADR0045's
curated public history and the user's checkpoint history.

## Candidate protocol and current evidence

1. Commit/push fixed reset owner (8d83fe1e5), then integrate main without changing its privacy
   policy. The automatic merge has exactly the15 upstream paths; only ci.yml and docs/llms.txt
   overlap lane work and both merge cleanly. Root inspected the complete two-file merge diff:
   new native diagnostic test/privacy job steps and canonical doc entry are retained, as are
   the lane's existing CI jobs. No code conflict resolution was needed.
2. Commit/push the main integration and this placement decision. Metadata pre-commit check,
   preflight inventory, doc index/skill-sync/canonical links pass. Privacy and pinned Gitleaks guard tests pass at1337, including detector positives and
   negative controls; the certificate-free Windows signing diagnostic regression also passes.
3. In the dedicated lane worktree only, create codex/lane-f-pr1 from current origin/main,
   squash-merge the clean checkpoint branch, and compare the entire index/tree with the merged
   checkpoint. Main's shared worktree is untouched. Commit with the approved identity after
   staged secret/privacy checks; no force-push and no deletion of the old branch.
4. Run full compile/tests, required stress and relevant frontend/governance/static checks,
   exact candidate identity range, current-tree/introduced public scans and publication
   preflight. Record source/tree identity when reusing evidence. All earlier hosted evidence
   remains tied to its original head; none grants this candidate a green status.
5. Open the successor draft with the same PR1 scope, durable public body and a fresh managed
   review record bound to the new PR/head/body. Link PR718 as historical evidence. Verify exact
   candidate hosted CI. Resume C2-6 production/Health/wire work with per-item commits and pushes.
   The full F acceptance matrix, including installed successor bootstrap, remains mandatory
   before readiness/merge; a replacement draft does not imply it has passed.

Raw local evidence is retained under lane-F-A/tmp through acceptance plus30 days and exported
before release. No private email values or raw secret-scanner reports are committed here.

1337 retained logs (all pass):

| Path | SHA-256 |
|---|---|
| tmp/c2-main-privacy1337.txt | 485fcba9deeb69f8ad9247ddb6d34ff0ca0b7a7d6bf864c2b93a4f33c392f2d3 |
| tmp/c2-main-gitleaks1337.txt | 243269cca0ff6cac122594a6bc2aa54add65886bc691415493e8622219464e16 |
| tmp/c2-main-signing1337.txt | 256ee656edd5723411703b8b0588799a0d40c35f9a7d5d451c6f7b413746cb01 |

## Fresh candidate proof (2026-09-13)

The old checkpoint branch is pushed through8bf81ea2d. The fresh codex/lane-f-pr1
candidate is rooted at mainb4d972b6b; its initial full index exactly equals8bf81ea2d
(tree46e621d9b8e13e0441214da59055806aa1993ac0). Subsequent changes in this snapshot
are documentation-only: this evidence/handoff and removal of one historical Markdown
trailing-space break. All compiled/product sources remain identical to the tested checkpoint.

- Full1346 passes10,496 cases/1,674 suites:7,869 execute and2,627 reuse unchanged inputs;
  25 skipped, zero failures/errors. All34 observed module test tasks are represented.
- Full build1351 (`build -x test`) passes including PMD/format and integration tasks.
  Integration outputs contain30 cases/10 suites with10 inherited skips.
- The repository-wide filtered stress command1349 fails before tests because modules
  without Stress classes reject that filter. Targeted owner-module run1350 passes726
  cases/104 suites, including both native-session and Lucene lifecycle stress tests;
  zero failures/errors/skips. The Lucene module's full suite also executes because
  the command's trailing filter applies to the ort-common task. No validation was disabled.
- Frontend1347 typecheck and all6,521 tests/485 files pass. Expected fixture diagnostics
  remain in the retained full output; the process exits0.
- Agent1348 passes61/61 files; governance1348 passes31/31 and its self-test gate, including
  expected negative controls. Contract-projection and engine-port self-tests report no
  fixtures; those messages are not counted as successful negative proof.
- Staged Gitleaks1345 scans49,281,770 bytes with no findings. The complete path inventory
  contains lane implementation, design/evidence and governed projections; review-sensitive
  private-approval names are implementation evidence, not private credentials. No raw
  secret-scanner JSON is committed. Doc index, skill sync and157 canonical links pass.

[Manifest and accessible artifact paths](publication-candidate-verification.json).
These proofs are local and bind the above source tree. The clean committed candidate's
publication preflight, identity range, replacement draft/review record and exact-head
hosted CI remain next. F readiness and remaining C2/D1/D2/E/F acceptance remain open.

## Publication preflight isolation and evidence correction (2026-09-13)

Candidatecfa4a78b8 is pushed on codex/lane-f-pr1. Preflight1353 passes the complete
Public claims command group, then fails at frontend npm ci: Windows refuses unlink
of lightningcss.win32-x64-msvc.node. Loaded-module inspection identifies the registered
ui-shot helpers as holders. The repository's orientation with the actual Codex session
id reports other-session/lease-lapsed/owner-unknown for those records. They are not reaped.
The resolver otherwise falls back to an older local session stamp; subsequent commands
supply the existing JUSTSEARCH_AGENT_SESSION_ID/--session-id contract explicitly.

Root created and registered an isolated checkout at
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify, branch codex/lane-f-pr1-verify,
at exactlycfa4a78b8. Preflight1355 is running there with its own dependencies. The main
checkout and registered foreign helpers remain untouched. Original artifacts below retain
F:/justsearch-public/.claude/worktrees/lane-F-A as their root;1355 has the isolated root.
The failed npm ci may have removed unlocked dependency files in lane-F-A, so future builds
must use a verified dependency installation rather than assuming that cache survived.

The user review requires a non-empty item/command/result body on every item commit.
Root's8bf81ea2d andcfa4a78b8 omitted those bodies. Their committed evidence remains valid,
but this was a process miss. This evidence commit records the missing item/proof details;
future commits use a body. Already pushed metadata is preserved without force-push.

-8bf81ea2d: C2-6 settings owner entry correction. Focused1344 passes81 cases with
 PMD/format after byte-exact restoration; negative1343 fails exactly at the unauthorized
 reset-apply assertion. Source hashes and commands are in settings-owner-entry-verification.json.
-cfa4a78b8: PR1 fresh publication snapshot. Full1346 passes10,496 cases (25 skips),
 build1351 passes, frontend1347 passes6,521 tests, and1350 includes both stress tests.
 The [committed full-suite summary](publication-full-suite-summary.json) now includes
 every module task, execution/cache status and count, beside the existing artifact inventory.

The review calibration remains binding: one independent review per implementation batch,
at most two substantive correction rounds, then root takes the diff; named acceptance and
item gates first; focused per-item verification and UI integration compilation, full suite
at the named integrated boundary. This publication boundary's full checks implement the
already-recorded lineage protocol, not a new per-helper proof tier.
