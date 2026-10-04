# C2-8/9 ingestion ownership: grounded design constraints

2026-09-14; rechecked against07813a14a in lane-f-pr1-verify. C2-8a public scheduling
and C2-8b committed hashes are implemented. This document investigates the next producer
and recovery cut; it is not an implemented-ingestion claim.

## Lifecycle already decided

C2-2's owning checklist explicitly says one row per walk: ad-hoc ingest, forced rescan,
or a boot recovery walk only when no open row exists (stages/C2.md, C2-2 acceptance).
Periodic sync remains maintenance without a new operation row. Thus the ongoing/changing
root in design7.5 means a finite walk observing changing sources, not a permanently live
row for a watched-root registration. A matching boot row resumes under its existing key.
Each parent composes its recorded root children; enumeration alone cannot complete either.

The held root/child packet is archival. Reuse its immutable scope and parent capability
checks only after adapting the now-separate public-input identity and prepared payload.
Do not restore its test-only activation or identity_json/payload coupling.

## Verified structural constraints

| Source at this revision | Consequence |
| --- | --- |
| SqliteJobQueue.enqueueEntries:415-449 and reenqueue:503-555 replace a path-primary-key row, reset retry fields and invalidate its process-local claim. | scan_id identifies the current queue admission, not a durable inventory of every operation that previously admitted that path. |
| WorkerMethvinWatcher.handleUpsert:213-221 ignores the enqueue count. SyncDirectoryOps:325-326 admits only forced or currently unindexed paths. | Refusing a watcher replacement and relying on ordinary periodic sync can permanently lose a change to an already indexed file. |
| WorkerMethvinWatcher.handleDelete:369-383 calls a deletion sink directly. WorkerIngestService root deletion and queue path/prefix deletion are separate mutation paths. | Protecting only enqueue cannot establish unit lifecycle correctness under deletion or root removal. |
| WorkerScanOps:152-183 and249-285 can stop enumeration with previously admitted jobs still alive. | Cancellation and enumeration counts cannot stand in for committed-unit outcomes. |
| JobBatchWriter and IngestionOutcomeJournal now carry the exact claim/hash; SqliteJobQueue.markDoneTransitions commits DONE/hash/ledger atomically. | This is the existing outcome projection boundary to extend. Hash-null skips remain unknown, not hash evidence. |
| ScanRollupLedger closes from an in-memory event stream and quiescence timeout. | It remains a display projection; it cannot authorize operation completion after a crash. |
| Operations and jobs are separate databases by the locked C2-1 decision. | Acceptance precedes queue admission; recovery must reconcile a crash between stores without claiming a cross-database transaction. |

## Alternatives and decisions

**Rejected: protect queue rows by dropping maintenance updates.** The watcher/periodic-sync
counterexample above disproves its convergence assumption. Fixing it would require durable
deferred changes and integrating deletion/cancellation anyway; it is not a smaller correct
solution. Overlap serialization alone also leaves rowless maintenance concurrent.

**Selected after comparison: one per-walk progress receipt in jobs.db, current jobs membership
and the existing terminal ledger, rather than versioning all jobs.** A projection can preserve admitted paths, current processing ownership,
retry facts and committed receipts when the mutable path queue is replaced. It must be
written by the same queue owner/transaction, not by an operations callback that reconstructs
state after the fact. Versioning the jobs table would avoid a parallel unit representation,
but would change every current path-based update, delete, stats and claim query. The [owning mechanism](C2-8-ingestion-plan.md) chooses the narrower progress receipt; it does
not add an immutable per-version unit table.

The operations row remains the sole operation acceptance, authority, attempt budget and
terminal history. Any jobs-side representation must be explicitly a unit substrate or a
projection with named ownership and reconciliation. It must not become a second operation
state machine. Avoid calling a live operations-store callback while holding the queue lock
unless the complete lock order is proved; an unqualified callback is not a settled design.

## Next design decisions and acceptance

The orchestrator has resolved these decisions in the [owning plan](C2-8-ingestion-plan.md);
they remain implementation and verification obligations:

1. Choose the unit representation and sealing boundary. A walk completes only after durable
   enumeration closure and every admitted current unit has a committed terminal outcome;
   completion must remain provable after queue replacement, deletion and process restart.
2. Specify maintenance replacement, explicit retry and source-change behavior. Never drop
   a watcher change. Same-operation recovery preserves per-file attempts/backoff; old hash
   evidence cannot certify a newer source version. Deletion/root removal cannot leave a
   falsely live unit or recreate an obsolete document under an ended operation.
3. Specify concurrent overlapping requests and mixed-failure mapping. Source changes are
   normal ingestion input; dependency/generation/grant failure is not a successful walk.
4. Place owner registration after generation readiness but before recorded jobs can recover
   or execute. The boot path resumes existing rows and starts fresh root walks only where
   the operations owner proves no applicable open row exists.
5. Restore typed root preparation, accepted child identity and key forwarding into the real
   REST/MCP/boot producers, with no test-only activation. Preserve public key-first replay.
6. Prove the four resume conditions, commit-before-checkpoint, exactly the uncovered units
   after kill, unchanged orphan recovery, watcher changes during a walk, overlap, deletion,
   cancellation, mixed failures and injected failure at every cross-store handoff.

The lack of a current unit membership/recovery owner is remaining lane work, not an owner
approval gate. D1 still owns generation journal/replay/activation and D2 durable delete
acknowledgement; this design must state precisely which C2 unit outcomes assert index writes
and must not falsely claim those later guarantees.
