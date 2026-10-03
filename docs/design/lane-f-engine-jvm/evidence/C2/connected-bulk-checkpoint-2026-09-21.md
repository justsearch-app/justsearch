# Historical connected bulk checkpoint, 2026-09-21

Superseded current-state snapshot; use [handoff](../../handoff.md) for next actions.

## Current state (2026-09-21)

Continue autonomously in `.claude/worktrees/lane-f-pr1-verify`, branch
`codex/lane-f-pr1`; main contains unrelated work. The user resumed this lane and
retained checkpoint publication/merge authorization. No routine owner approval is
pending. Merge remains at stage F; C2 and D1/D2/E/F are open.

[Working contract and transcript lessons](evidence/C2/resume-2026-09-21.md) is the
context-reset entry point. Do not reread historical amendments to reconstruct the
current task. Root owns production lifecycle, shared Gradle and the dev stack;
subagents may own bounded tests or read-only investigation/review.

The connected bulk checkpoint carries this current-state record. Its pushed
predecessor is `de1d2520143456aa149c75d6266cb4c70fac25a6`, captured traversal under
retained admission. Local focused 2174 passes
76 cases; full worker/indexer 2175 passes 2,092 cases with 17 existing skips;
negative 2177 fails both intended guards and restores production bytes.
Hosted CI 35573710291 fails only the app-ui unreferenced-code predicate because
`EngineKnowledgeClient.enumerateCapturedRoots` had no consumer. This is a real
connection gap, not a baseline exception. Evidence is in `tmp/2180-captured-hosted`.
Other hosted jobs pass; 84 relevant captured/foundation cases execute successfully.
Five downstream app-services suites did not run after the launcher failure.

The connected checkpoint connects strict recorded boot ownership, recovery-disabled
Lucene opens, cutover owner hooks, the prepared REST migration alias, and the bulk
consumer within RecordedIngestionCoordinator. Boot/REST focused 2182 passes all
168 cases across 13 suites with no failures/errors/skips, all five test tasks fresh.
That result predates the bulk consumer and expected-source control-port changes.
Main compilation 2183 passes for app-engine and ui (including dependencies). One
new precedence warning is corrected afterward; inherited warnings remain visible
in its log. Later consumer verification is recorded below.

The consumer captures and closes the frozen walk before exact source-bound start,
checkpoints BUILDING before restart, authorizes claims only for the actual matching
Green writer, checkpoints immutable SETTLED before promotion, and requires a
post-promotion boot/writer witness before terminal completion and exact queue ACK.
The consolidated consumer review is implemented: durable first-wins refusal before
queue retirement, typed existing-attempt recovery checkpoint, cancellation/promotion
barrier, exact target promotion, paged ACK completion, exact terminal/refusal matrix,
and one retryable post-terminal refusal restart per attachment. Refused targets stay
fenced as designed; no generation abandonment or additional journal. Final reviewer
messages used an older snapshot for live refusal/restart findings; current
bulkRefusalReason reads cancellationReason and typed policy receipts, and
restartAfterBulkRefusal owns the requested restart. Adversarial proof remains owed.

2186 executes 85 storage/queue/boot cases with zero failures/errors and one existing
Windows symlink skip; app-engine test compilation failed on two new fixture mistakes.
2187/2188 each run 52 Engine cases with one new-fixture failure (missing rebuild
mock source, then a successful unit incorrectly expected in the failed/superseded
history sample). These are corrected without changing existing production contracts.
2189 passes 71 cases across four suites, zero failures/errors, one existing Windows
symlink skip: real SQLite operation+queue coordinator happy lifecycle/ACK repair,
ordinary coordinator controls, restart dispatch, and exact generation promotion.
Both test tasks execute fresh. Full storage 2190 passes 1,238 cases/227 suites,
zero failures/errors, seven existing skips (vocab 1, late-chunking model 5,
Windows symlink privilege 1); all six PMD tasks pass. Full worker-services,
indexer-worker and adapters-lucene 2191 passes 2,823 cases/465 suites with zero
failures/errors and 17 existing skips. Its one PMD failure was an unnecessary
ArrayList qualifier; the correction passes indexer-worker pmdMain in 2193.
The other five PMD tasks pass in 2191. No dev stack or Gradle build is active.
Root exclusively owns Gradle. 2196 passes 58 Engine cases including actual
EngineRoot/KnowledgeServer two-restart and five crash/cancellation cases. It fixes
refusal restart retry to occur after the finite pump loop, once per maintain call.
Test-only PMD qualifiers are corrected and pass 2197. Full service/API 2198 passes
4,285 cases/636 suites, zero failures/errors, four existing skips, all four PMD tasks.
2200 rebuilds ui:installDist and passes 38 launcher cases including the previously
failing unreferenced-code predicates. Installed 2201 passes prepared REST migration,
both restarts, exact promoted serving, COMPLETE/SUCCESS and sealed revision-6 ACK,
then rollback; actual GPU embedding runs. Owned stop closes ports and post-run health
is ABSENT. This is not chat-model proof. MAX-attempt and 257-entry inventory tests
pass in full Engine 2203. That run has 340 cases/59 suites and one existing pacing
teardown failure: IndexingLoop.close interrupts active extraction, then final commit
can invalidate Lucene's native write-lock channel. Root is fixing that proven
shutdown defect; a passing focused 2204 rerun (ten cases) does not waive it.
All four negative guards fail as intended in 2199 and sources restore byte-exact.
Whole build/static 2206 passes after indexer-worker formatting; no active stack or
Gradle process. A frozen read-only map locates the three installed kill seams:
durable partial captured progress, before BUILDING checkpoint after exact start,
and after promotion before terminal receipt. They are not implemented proof yet.
Canonical storage/REST docs are updated; regeneration
and canonical link validation pass. No claim of complete C2 acceptance.

Next: correct cooperative indexing shutdown with deterministic regression and
unchanged pacing/real-runtime proof; preserve the connected checkpoint and hosted
XML evidence; implement/run the three installed bulk fault cuts and
required final full stress. C2, D1/D2/E/F remain open. Full 2064 stress had a
subsequently fixed SystemAccessFunnel failure; focused 2075 is not full-stress proof.

Prior checkpoint evidence remains in the owning documents rather than needing
historical transcript replay: [lock/recovery acceptance](evidence/C2/hostile-lock-acceptance.md)
and [bulk design/evidence](evidence/C2/bulk-reindex-connection.md). Pushed sequence:
85154fed1 (lock/recovery), 3caf90cbc (queue capture/settlement), 29071724c
(exact generation), 65afa81fc (preparation/progress), d69763186 (prepared continuation),
de1d25201 (captured producer). All acceptance gaps remain part of this lane.

