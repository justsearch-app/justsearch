# C2-8d: connect recorded finite root walks

2026-09-14, follows pushed128a0c945. This is the implementation map for the selected
[ingestion mechanism](C2-8-ingestion-plan.md). C2-8d/C2-9 remain open. Intermediate value,
queue and acceptance commits are WIP until the real producer and boot path are connected.
Root owns migrations, shared-state changes, composition and all Gradle runs.

## Per-item commits and integration boundary

1. **C2-8d.1, frozen root value and child binding.** Adapt the held immutable root plan to
   the current separate prepared payload; strict schema, deterministic partitions and bounded
   UTF-8 metadata. Reinstate child acceptance against a runner-issued parent capability and
   the exact accepted preparation witness. Preserve raw context/provenance by copying the
   parent inside the operations transaction. No implicit child-completion registry.
2. **C2-8d.2, queue walk receipts.** Jobs v18 adds walk progress, membership epoch and ledger
   attribution/terminal coverage. Connect every relevant queue transition, source-removal
   accounting, retention, seal and acknowledgement in the existing owner. A primitive alone
   does not activate root ingestion. Root writes schema/transaction changes and their tests.
   Commit d.2a (WIP schema/epochs and actual batch-exit claim return) before d.2b
   (membership, ledger coverage/counters and seal) and d.2c (notification/acknowledgement
   retention). These are reviewable cuts within the same d.2 acceptance item.
3. **C2-8d.3, actual producer.** The existing IngestTool becomes prepared/record-aware; REST
   dispatches through the same catalog operation and MCP retains its existing keyed dispatch.
   Frozen single files and directories each use one child. Carry the accepted key through the
   Java index seam, never mint a replacement scan id in the worker for recorded work. Parent
   completion composes durable child completions, not enumeration responses or quiet timers.
4. **C2-9, pre-poll recovery.** Install the generation-ready synchronous EngineRoot seam,
   re-use the sole grant authority, gate both polling and recovery, and preserve orphan behavior.
   Follow the [server-built authorization basis and shared roots contract](ingestion-authorization-recovery.md).
   Add committed-index effect probes and the four-condition/kill tests. Retire the recorded
   path's global recovery, timestamp-only effect inference and unrecorded public ingress.

Commit each item separately and push immediately; keep WIP at least hourly. Do not merge or
claim C2 complete until producer, recovery and all required proof tiers pass. The split is a
review/commit boundary, not an authorized deferral or a stage-placement change.

## Reverified producer seams

The current IngestTool is synchronous and returns after submitting files or scanning directories.
OperationHandler's default prepared execution therefore finishes its accepted row too early.
KnowledgeSearchController.handleIngest bypasses the runner. MCP already forwards its public
operationKey to the dispatcher. KnowledgeClient/EngineKnowledgeClient own a Java streaming
scan seam; WorkerIngestService currently generates the random scan id before calling the
existing WorkerScanOps.ScanRequest, which already carries scanId. Single-file submitBatch
currently drops scan ownership. The recorded path must cover both shapes and retire both bypasses.

Keep path resolution and collection policy in their existing owners. Pure preparation freezes
resolved absolute roots, generation and effective policy before acceptance; replay uses that
persisted plan and does not repeat current path/binding selection. The root value is filesystem
free. Key-first lookup/public argument digest remains the dispatcher's responsibility.

## Child preparation across the module boundary

The held acceptIngestChild code decoded a plan from identity_json. That representation is
superseded. The current PreparedInvocationCodec in app-services owns envelope binding and
metadata-only early recovery; app-observability cannot import that module.

Use a root-composed, trusted preparation resolver for the runner, not a callback supplied by a
handler. It decodes the actual accepted metadata envelope and returns the strict root plan
outside the operations SQLite lock. The runner validates its parent handle, actual producer
identity and exact root membership. The store transaction then compares the inspected
preparation nonce/payload, requires the parent still RUNNING for insertion, and copies parent
context/provenance. This avoids moving the envelope/cipher/provenance owners or parsing the
same format independently in the store.

A child has a strict internal descriptor with parent key, selected root identity/digest and
schema version. Its separately persisted metadata is the one-root plan; the descriptor must
not contain the plan itself. Existing children are found by that exact canonical internal
identity, including terminal outcomes; lookup cannot remint a child or rerun its body. The
Terminal children remain retained while their parent is open, so capacity/age pruning cannot
turn a completed root into a new effect on parent replay. The
child recovery owner validates the descriptor, prepared plan and actual parent relation before
resuming. Contributed recordKind=INGEST alone never authorizes either child creation or replay.

## Projection notifications and cadence

A source re-read corrected an important investigation assumption: IndexingJobsChangeStream.
drainCommitted is called under the jobs owner lock and invokes subscribers there. It is safe
for the existing queued display projections, but cannot directly checkpoint operations.db.
Do not reuse it as an operations callback or silently change its ordering contract.

The new walk receipt remains the durable source. Queue-owned walk mutations collect affected
keys inside their transaction; only a successful commit promotes them to notifications. The
outermost queue call releases its lock before notifying the recorded-walk owner. Notify keys,
then read the latest durable receipt, so reordered/coalesced notifications cannot apply stale
counter deltas. Observer failure cannot roll back committed jobs or discard the receipt.
A missed projection retries through the existing30-second operations maintenance callback
before that callback re-checkpoints operations rows. Shutdown performs the same flush before
its existing logical checkpoint. No new timer, executor, persistent notification store or
operation authority is needed. Snapshot/acknowledgement uses the same receipt revision.

## Enumeration and failure details

Only explicit enumerator admissions add walk members. Maintenance replaces already admitted
members under that walk until its transactional seal, and new watcher paths may remain rowless.
Re-enumeration marks existing members seen without resetting pending/processing attempts or
backoff. Recovery compares committed successful members with current source hashes and the
committed index view; a different current source is a new admission, not a duplicate effect.
A terminal failure is not reopened merely because enumeration is retried. Explicit source
change admission or a deliberate fresh retry retains the established reset semantics.

Persist enumeration's terminal outcome as well as its closure timestamp: an error/cancellation
must survive while already admitted processing units drain. Stop new claims for the cancelled
walk, terminate unstarted members without claiming effects, and wait for issued owners before
sealing failure/cancellation. An inaccessible root is not evidence that every descendant was
deleted. Source removal is a terminal skip with no durable deletion claim; D2 still owns that.

Jobs.db retains its declared DERIVED identity. Loss/rebuild of a walk receipt cannot be called
successful resume or used to invent covered-unit history. Fail the affected accepted operation
with a bounded unit-state-unavailable outcome and preserve its last operations checkpoint;
only a fresh accepted walk can rebuild derived work. Process-kill recovery with retained jobs
is the stronger C2 promise. Do not pretend a corrupt/missing receipt is a legacy orphan.

## Required proof

Real REST/MCP public key -> frozen parent -> accepted child -> queue scan id -> durable unit
outcome -> final checkpoint -> durable child completion -> parent completion. Include single
files, mixed collections/nested roots, key conflict and matching retry after generation change,
failed enumeration, source removal, maintenance replacement, no reset on resume, receipt seal
and both cross-store crash windows. Boot tests must establish the fence before the first poll,
validate producer identity/generation/grant, advance past an already committed effect, and run
only uncovered units while preserving true orphan recovery. Required installed kill and live/
model evidence remain C2-11/12; this map is not their proof.


## C2-8d.2 transaction and issued-claim cut (2026-09-14)

The v18 schema and begin/read/enumeration-close/acknowledgement primitives are being
implemented in the existing queue. SqliteIngestionWalkOps borrows its connection only
under the queue's lock/transaction; it owns no connection, thread or independent writer.
Keeping these projection queries in a package-private helper avoids adding all SQL to
the already large queue without changing ownership. Three real projection/migration tests
plus six always-on worker guardrails pass in focused1542. The initial1541 compile failure
was a misplaced nested SHA256 reference and is retained; no test ran in that failed build.
The actual unit transitions, seal, notifications and producer integration remain owed.

An interrupted enumeration advances its durable epoch without resetting progress; a closed
enumeration is not silently reopened. Late callbacks carrying an earlier epoch must fail.
Missing progress can be created only on first execution of newly accepted work; recovery
cannot recreate a lost derived receipt. The v18 migration also preserves originator,
transport and recorded unit fields when the existing raw-path privacy repair rebuilds
its ledger; the previous repair discarded attribution.

Queue inspection found that replacement, reaper and deletion currently release or lose
issued claims before their workers actually exit. The existing activeClaims map will
become actual-exit ownership: keep its exact object identity through replacement, skip
issued paths before poll LIMIT, and exclude live claims from runtime stale-job recovery.
The durable row revision decides whether a callback may update the current jobs row;
issued identity separately decides whether its committed historical effect is genuine.
Release a claim only after its outcome transaction commits; rollback retains it for retry.
No second durable claim/admission table is needed.

For recorded members, administrative deletion/cancellation retains a terminal SKIPPED
member until seal. It does not claim durable index absence (D2). An already issued old
write may still commit and append INDEXED coverage without rewriting the current skipped
or replacement row. Ledger deduplication is per(walk,path,admission revision,coverage class),
not one outcome class for the entire admission: explicit skip and an already issued
committed effect can both be truthful. Failed counters still count each failed admission
once, successful counters count distinct(path,content hash), and current jobs remains
closure's final-status authority. A later maintenance replacement receives a new revision
while retaining an active walk's membership and enumeration epoch.

This requires fixing existing mid-batch abandonment first. Once polled, every claim must
reach terminal/deferred handling, the pending commit journal, or explicit exceptional-batch
return after actual extraction/write exit. Keep the extractor/writer running-flag breaks,
but return their unvisited/unwritten remainder in processBatch's finally after synchronous
exit. Exclude pending commit transitions by exact identity. Retry a failed SQL return in
the existing journal before another poll; no new standalone lifecycle owner is needed.
Non-VM per-unit errors use existing bounded failure handling; a batch embedding error uses
its existing fallback, while VM errors propagate without claiming safe exit.
Prove quiesce after poll, quiesce during write, recoverable failure at the first/middle
unit, oldest issued path with poll limit1, stale committed callback, rollback/retry and
runtime reaper with a blocked live owner. These are part of C2-8d.2, not a new owner gate.
