# PR1 publication lineage correction

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
