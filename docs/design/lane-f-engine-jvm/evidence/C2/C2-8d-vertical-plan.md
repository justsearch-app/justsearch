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
   retention). Within d.2b, commit admission/terminal accounting as d.2b.1 before
   d.2b.2 administrative closure and immutable sealing. These are reviewable cuts within the same d.2 acceptance item.
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

## C2-8d.2b identity and admission detail (2026-09-14)

Explicit recorded enqueue validates the current open epoch, marks rediscovered members
seen without resetting state, and refuses to steal another unsealed walk's member. The
root owner still refuses overlapping roots before admission; this queue check is defensive.
A nullable membership epoch also rides the issued IndexJob alongside its existing scan key
and admission revision. This is a projection of the existing v18 column, not a new marker
store: an issued recorded callback must remain identifiable if its current jobs row is
replaced or missing. Missing progress cannot silently turn it into an unrecorded completion.
Claimless diagnostic ledger appends never create terminal coverage; indexed coverage needs
an actual issued claim and committed source hash. Administrative path completion/deletion
can establish only explicit skip coverage, never an indexed effect or D2 delete acknowledgement.


The d.2b.1 review requires a non-null membership epoch before treating matching scan_id
as recorded re-enumeration. Legacy collisions get a fresh admission revision; a recorded
member whose projection disappeared refuses adoption by another walk. Coverage and typed
outcomes must agree inside the outcome transaction. Raw untyped completion/failure cannot
terminalize a recorded member. Superseded exact claims retain terminal SKIPPED/FAILED
history without mutating their replacement. A superseded retryable failure is diagnostic
only: the replacement owns its new retry window, so the old callback neither spends its
attempts nor invents an exhausted old unit from the replacement's state.


## C2-8d.2b.2 closure transaction detail (2026-09-14)

Use the existing DONE state with typed SKIPPED_POLICY or STALE_SOURCE plus SKIPPED ledger
coverage for administrative completion. Do not add another jobs state. Generic untyped
completion remains refused for recorded members. Root cancellation must state a skip rather
than the existing misleading SUCCESS_PARTIAL outcome. Source deletion preserves the member
and clears its current hash; an already issued old effect may still append historical INDEXED
coverage. Clear-failed and profiling clear use an administrative skip reason rather than
claiming index absence. Retention cleanup never silently turns a live walk into a skip.

Sealing is an explicit queue-owner transaction invoked by the producer/progress projection
and its existing reconciliation cadence. This is simpler than attempting to seal every walk
inside every jobs mutation and passing temporary claim-release sets through all writers.
It observes committed enumeration closure, no unfinished current members and no still-issued
claim for that key. It validates current terminal coverage against the existing ledger,
then freezes the versioned JSON receipt and advances its projection revision. A callback
whose outcome committed but whose actual claim has not yet been released cannot seal it.
Before maintenance replaces an existing member, it attempts the same seal for that one
walk under the queue lock. If the closed walk is already terminal and has no issued claims,
it seals first and the new maintenance admission is outside it. This preserves the governing
closed-plus-terminal boundary without scanning every walk on every jobs transaction.
Duplicate sealing returns the stored receipt.

The receipt carries version1, revision, final completedUnits/failedUnits, currentFailedUnits,
currentSkippedUnits, enumerationOutcome and at most100 failed path hashes with a truncation
flag. Counters describe history; current failures and enumeration outcome decide success.
No raw path or copied scope enters the receipt. A missing terminal coverage row is a gap,
not proof of a successful zero-unit walk. Completed re-enumeration retires unseen members
as source-removal skips; failed/inaccessible enumeration does not label them deleted.
Failed/cancelled closure stops new polling and drains issued owners before sealing.

Administrative deletion preserves unsealed members as terminal skips and retains sealed
unacknowledged rows. Jobs and ledger age cleanup requires either unrecorded membership or
an existing sealed progress row acknowledged at its exact final revision; missing progress
never satisfies that predicate. The next d.2c cut connects notifications after lock release
and acknowledgement only after the matching outer operations transaction.

### Refute review decisions (2026-09-14)

- FAILED/CANCELLED closure atomically skips unissued PENDING and unowned PROCESSING members
with typed administrative coverage. Poll excludes both outcomes. Issued objects stay owned;
a later unfinished return or orphan recovery of a closed failed/cancelled member writes a
terminal skip instead of returning it to PENDING. COMPLETE retains normal claim recovery.
- COMPLETE closure retires unseen members in that same transaction after the final admission
batch. It cannot wait until seal: later maintenance may have legitimately observed the file
again. FAILED/CANCELLED never infer source deletion. An issued unseen row becomes currently
DONE/SKIPPED, with null current hash, while its actual object remains in activeClaims.
- Administrative skip invalidates current-row completion, not the actual issued object.
A late committed effect can append historical INDEXED/FAILED coverage without changing the
current skip. Root cancellation makes the operation cancelled and forbids new claims; it
cannot erase an effect already committed. Rollback retains actual ownership and blocks seal.
- trySealRecordedWalk returns the existing WalkProgress: sealedAt absent means NOT_READY
only for open enumeration, unfinished members or still-issued work; present means immutable
SEALED. Missing progress and terminal coverage mismatch throw a dedicated receipt-gap error,
which the outer owner maps to storage/projection loss rather than retrying as ordinary work.
Current outcome controls compatible coverage; a historical row for another revision or
hash cannot repair the current receipt. The failure list is current FAILED path hashes,
sorted lexically, first100, with truncation iff the full count exceeds100.
- Maintenance preflight seals only its affected eligible walk before replacement. This is
simpler than a global seal scan and prevents a ready walk being kept open merely because
maintenance wins the lock before its projection callback. A still-running walk may continue
to absorb updates to its members, as the governing live-input rule requires.
- This cut does not prune progress rows. The next retention cut may remove a progress row
only atomically after exact final acknowledgement and removal of all keyed member/ledger
evidence. Missing progress never grants cleanup permission.

Required regressions add FAILED/CANCELLED poll/return/recovery, source removal with a live
stale writer and rollback, unseen retirement before later maintenance, maintenance preflight
sealing, legitimate zero-unit closure versus missing terminal evidence, mismatched coverage,
duplicate immutable seal, and more than100 current failure hashes. These decisions supersede
the earlier delayed-seal sentence; no implementation or proof is claimed by this design edit.

### Implementation detail: stopped maintenance admission (2026-09-14)

A failed/cancelled walk with an issued owner cannot seal yet. Maintenance must not recreate
PENDING membership in that stopped walk: polling forbids it and would strand the new admission.
Affected-walk preflight seals when ready; while issued work prevents that, the existing queue
admission refuses the replacement and reports no acceptance. Once owners return, a retry seals
the stopped receipt and admits maintenance outside it. This uses existing admission refusal,
not a new pending buffer or fabricated successful skip. Producers must respect refusal.
Deferred/retryable callbacks after unsuccessful closure finish as typed skips inside their
existing outcome transaction. No nested inTransaction is introduced: it would commit early.


## C2-8d.2c notification and retention attachment (2026-09-14)

Use a package-private same-connection walk notification helper. Native update hooks capture
only affected progress row ids, with no SQL or callbacks. The queue's existing transaction
owner discards provisional rows on rollback and materializes keys after confirmed JDBC
commit. The outermost unlock drains a detached key batch only after releasing the lock.
The existing display stream retains its under-lock ordering. Observer runtime failures are
logged without changing committed queue results; fatal errors propagate. Consumers read
the latest receipt and retry missed projection through the existing maintenance/shutdown
owner when the producer is connected. No timer, executor, persistent notification queue or
whole-table revision scan is introduced. Automatic actual-row capture is smaller and safer
than maintaining explicit key marks at every admission/terminal/admin/seal writer.

Duplicate exact acknowledgement returns success without another UPDATE, avoiding a
notification loop when a consumer repeats its durable receipt check. Existing jobs/ledger
age cleanup also prunes old sealed exactly acknowledged progress with no remaining keyed
jobs or ledger references, in the same transaction as its existing cleanup. Deletes never
notify a now-missing receipt. Subscriptions are scoped to the opened queue and close with it.

The queue-only d.2c commit supplies notifications and retention; d.3 connects the actual
recorded owner, matching terminal receipt acknowledgement, and missed-projection cadence
before claiming the overall d.2c acceptance. The distinction is an implementation cut, not
a deferral of external acknowledgement or shutdown proof.

Actual claim release also schedules its key after the successful outcome/return transaction:
an administrative skip may already be committed, so release can make the walk sealable
without another SQL change. Forged, rolled-back or still-owned claims never schedule that
release hint. It shares the same outermost-unlock delivery and durable reread contract.


## C2-8d.3 producer and receipt owner (2026-09-14)

Compose one EngineRoot-bound RecordedIngestionService with accepted-root start and
lifecycle control. Do not widen the common IndexingService. Queue read/seal/ack
stays private to this coordinator. Effects use the sole EngineKnowledgeClient's
bounded admission path; receipt-only bookkeeping never attaches admission or uses
allowWhileFrozen. The coordinator survives client replacement.

Parent completion requires this exact barrier: sealed queue receipt, then compact
checkpoint(version/revision/SHA256 of the exact stored UTF-8) and historical counts,
then durable child terminal, then reread matching terminal/current sealed receipt,
then acknowledge that exact revision, then parent completion. OperationReceipt
retains code and null executionId; the latter is not a hash container. Current
failed membership and enumeration outcome decide terminal state; historical failed
units are not a current failure verdict. The queue owner validates receipt shape,
version/revision/counters/enumeration and the bounded sorted failure hashes. Rich
receipts may exceed the4096-byte checkpoint; only the compact identity goes there.

Use the existing30-second maintenance owner to repair missed notifications. Add a
second shutdown flush after indexing drains and before jobs close, since the Head's
precheckpoint flush is too early and the client closes before KnowledgeServer's
index drain. Observer runtime failure is nonfatal; the final flush must recover a
missed late seal. No new timer, journal or notification store is introduced.

A missing/mismatched checkpoint needs the winning runner Resume handle before
finish, within the existing maximum of three attempts. A matching checkpoint may
terminalize directly. Parent reconciliation returns asynchronous composition so
boot child rows can subsequently resume; the captured boot row list is not a new
runtime scan. The open parent anchors boot catch-up including terminal children,
and cannot finish before their exact acknowledgements. Files and directories both
use the child key as scan_id with explicit recorded epoch. Unchanged unkeyed
submitBatch or scanRoot cannot serve this producer. REST uses the same dispatcher.

Activation depends on the C2-9b.3 fence. A winning boot Resume body installs its
runtime permission after the runner's started CAS and returns an asynchronous
execution immediately. Its producer waits by binding its continuation to the later
EngineKnowledgeClient; it never blocks generation-ready startup or bypasses that
client to invoke Worker effects. Failed startup and replacement close that server's
activation and settle its deferred stages. Keep activation through index drain and
final receipt flush, then close it before the queue.

These decisions were independently reviewed against EngineRoot, HeadAssembly,
KnowledgeServer, IndexingLoop, the runner and SQLite owners at7777be3fd and48b38b4ea.
They are a selected implementation contract; producer and lifecycle proof is owed.


## Remaining producer cuts and strict receipt boundary (2026-09-14)

Source inspection atae486f3e4 found that the existing sealed writer's JSON is not
validated on read, duplicate seal or acknowledgement. This is an unimplemented part
of the already-selected d.3 contract above. Complete d.3a (strict sealed receipt
projection) before9b.3b.3 (stable coordinator), then d.3b (actual bounded effect adapters
and public producer wiring). These are per-item commits; they do not change stage or
merge placement and none independently completes C2-8d/C2-9.

Keep SqliteIngestionWalkOps as the single receipt JSON schema/serialization owner.
Add one non-durable JobQueue.SealedWalkReceipt projection and a read method returning
it only for a sealed walk. Missing progress throws RecordedWalkGapException; only a
present unsealed walk returns empty/not-ready. Its fields are version, revision, exact stored UTF-8 SHA256,
historical completed/failed units, current failed units and enumeration outcome. The
Engine needs these fields for checkpoint identity and outcome; it must not parse or
reserialize raw receipt JSON. Reuse SqliteJobQueue's existing exact UTF-8 hash helper;
CanonicalOperationArguments.digest canonicalizes JSON and is therefore wrong here.
This typed view is a projection of the existing immutable row, not another receipt
writer/table or an OperationReceipt extension.

The queue parser validates an exact field set, version1, integral nonnegative counters,
matching row revision/historical counters/enumeration, current failures no greater than
historical failures, and the existing sorted nondecreasing lowercase SHA256 failure list. Duplicate hashes
are retained: the writer sorts one hash per failed path and does not establish collision
uniqueness. The parser must not introduce a new persisted v1 invariant.
The list size is min(currentFailedUnits,100), with truncation iff currentFailedUnits>100.
Reject duplicate JSON fields, coercions, unsupported versions, unknown/missing fields, non-object
JSON and malformed input. Bound receipt decoding to16KiB (the compact current schema
with100 hashes is below8KiB). Valid alternative JSON whitespace/order may change the
exact byte hash; do not normalize it. No hash of a reserialized object is acceptable.

Validate sealed rows on every queue read, duplicate seal and acknowledgement, including
already acknowledged rows. Invalid sealed evidence raises RecordedWalkGapException and
must not change acknowledgement or retention eligibility. Keep the existing revision
CAS, queue lock, transaction and after-unlock notification owners. The parser can be
reused for the typed view; a bounded repeated parse is simpler than a second cached
receipt representation or a parallel schema in the Engine. No format/schema migration
or new persistent data is introduced.

Required proof: real queue seal/reopen roundtrip, terminal verdict versus historical
failures, exact stored-byte hash versus canonical hash, missing/unsealed observations,
malformed/duplicate/unknown JSON fields, wrong types/version/revision/counters/enumeration,
negative/overflow values, sorted/hash/truncation limits including100+ failures, and
rejection before duplicate seal/ack with the persisted acknowledgement unchanged.
Corrupted receipts cannot become successful zero-work projections. Independent review
also found that retention bypasses the read path. Before old-job deletion, ledger deletion
or orphan progress pruning, validate exactly that statement's recorded walk candidates
inside the same existing transaction. Reject non-integer SQLite storage for all numeric
walk columns before JDBC coercion; SQLite INTEGER affinity alone permits REAL values.
Read required/nullable integer fields without truncation and surface malformed rows as gaps.
Reuse its WHERE expression for candidate SELECT
and DELETE, stream distinct keys without retaining a new whole-table collection, and
reread through the strict queue parser. A gap aborts and rolls back cleanup; do not
silently delete invalid already-acknowledged evidence. Add regressions for each cleanup
path and progress-only pruning with unchanged evidence/acknowledgement after rejection. Existing seal,
notification, retention and authority suites remain required alongside these cases.

## Stable completion across index replacement (2026-09-14)

The runner captures its interrupted row cohort at construction and never re-enters a
Control after its started CAS. Therefore the stable EngineRoot coordinator must retain
each pending OperationExecution completion and live parent handle across same-process
KnowledgeServer replacement. Attachment close revokes permissions and closes the old
server's producers/subscription; it does not fail an unstarted pending effect or discard
its completion owner. A replacement attachment continues the same stage after strict
persisted binding and restart-policy validation, with no surviving fresh/capsule origin.
Full process restart instead uses the new runner's captured cohort and ordinary Resume.
This clarifies the earlier phrase 'settle deferred stages': settle runtime activity,
not fabricate a terminal operation outcome. No runner rearm API is selected.

acceptIngestChild validates the private runner Control, persisted parent preparation and
RUNNING parent, but does not require its body thread. The sequential coordinator may
therefore accept the next frozen root from the prior child's durable acknowledgement
continuation, after the parent body returned its async stage. Settings' synchronous
body-thread rule must not be copied into ingestion. Retain the admitted parent workId
through the parent acknowledgement barrier; do not independently admit each child.

Serialize coordinator calls to runner.reconcile and its provisional capacity handoff;
a pure Wait creates no retained execution. Any reservation is discarded if no winning
body consumed it, including a CAS loss or thrown reconciliation. Pending admission
refusal is retried by client bind and existing maintenance, never thrown into the
runner's FAILED transition. Same-process replacement proof must show nonterminal pending
ownership, fresh-permission revocation, restart-only revalidation, one workId and later
root acceptance after async acknowledgement. These are selected mechanisms, not executed
proof or permission to bypass the bounded EngineKnowledgeClient effect path.


## Coordinator child observation and refusal ordering (2026-09-14)

The existing acceptIngestChild lookup may create a child and requires a private live
runner handle. Add a read-only findIngestChild(parentKey, oneRootPlan) to the same
operations store, reusing its exact canonical child query, attribution, history-mode
and prepared-payload validation. It creates no row, attempt, admission or authority.
Missing parent or contradictory binding refuses; a valid parent with no child returns
empty. Terminal children remain observable across reopen. This is smaller than a
new child registry or a whole-history scan and lets refusal bookkeeping avoid creating
unknown children. Existing acceptance of an open child additionally requires a RUNNING
parent; terminal child observation remains permitted. A read itself never authorizes replay.

Parent refusal must fence permissions, settle each existing child and acknowledge its
matching terminal receipt before completing the parent. It must not immediately return
Reconciliation.Failed while a child remains open. A winning Resume may own bookkeeping
without admission or effects; unavailable/corrupt projection remains a gap rather than
fabricating successful zero work. COMPLETE_WITH_GAPS remains D1-owned and never grants
permission. The parent-before-child ordering and open-parent receipt anchor remain as
decided. A correctly terminal parent already passed all child acknowledgements; no new
scan of terminal history is needed to repair the designed completion ordering.

The Engine's compact cursor is ingest-receipt:<version>:<revision>:<exact UTF-8 SHA256>.
All fields are validated by the queue's typed view; compare the exact cursor and historical
counts, expected terminal state, receipt code and null executionId before acknowledgement.
No JSON reserialization, digest in executionId, or independent receipt parser is introduced.
Cancellation uses the existing runner receipt code cancelled; enumeration failure uses
INGEST_ENUMERATION_FAILED, current member failures INGEST_UNITS_FAILED, otherwise SUCCESS.
Historical failed units alone do not decide the current outcome.

Required proof adds read-only absent-child lookup, terminal child lookup after parent
completion/reopen, malformed inherited binding refusal, open-child acceptance refusal
under a non-running parent, and parent refusal with no new child/effect/admission.
These mechanisms belong to9b.3b.3; no coordinator completion is claimed by this design.


## Unavailable receipt evidence and issued-owner drain (2026-09-14)

The earlier derived-store-loss rule at Enumeration and failure details remains in force.
The normal exact checkpoint/terminal/ack barrier applies to valid sealed COMPLETE, FAILED
and CANCELLED receipts. It cannot override that earlier rule by waiting forever on evidence
that is missing/corrupt or on an exhausted repair attempt. A valid sealed queue receipt with
a stale/missing operation checkpoint remains repairable through a winning Resume. Check the
existing total durable-operation attempts field against the decided limit3 before another
Resume; no separate repair counter and no generic unbounded runner retry are selected.

For unavailable/contradictory final receipt evidence or exhausted repair, revoke permission,
settle the enumeration producer, and prove issued queue owners have drained. Then fail the
child and subsequently its parent with INGEST_UNIT_STATE_UNAVAILABLE (or
INGEST_RECOVERY_ATTEMPTS_EXHAUSTED for exhaustion), null executionId, and the last confirmed
operations checkpoint/counts. Do not fabricate a new successful checkpoint or acknowledge
invalid queue evidence. Retention remains refused for that unacknowledged evidence. This
is FAILED partial-effect reporting under7.5, never COMPLETE_WITH_GAPS acceptance or D1
candidate activation. Independent source review confirmed this resolves an overbroad later
wording without changing the already-decided derived-store loss behavior.

Add only hasIssuedRecordedClaims(operationKey) to the private index-side queue port. It
reads the existing activeClaims map under the same queue lock using the exact existing
seal predicate (non-null walkEpoch and matching scanId). Reuse that predicate inside sealing;
no second activity counter or registry. Read false only after permission revocation: poll
checks permission and publishes each actual claim before releasing that same lock, so the
ordered revoke/read forms the drain proof. Durable PROCESSING state alone proves neither
ownership nor work exit. A closed/unavailable queue must not return a fabricated false.
Enumeration producer exit is a separate required barrier; this read only covers queue claims.
Required proof includes fence/read race, pending outcome persistence, exact returned claim,
and a reopened unowned PROCESSING row. None is claimed by this design entry.


## Coordinator per-item cuts (2026-09-14)

Keep9b.3b.3 reviewable through three commits, pushing each. These refine implementation
placement within the decided stable coordinator; none independently completes that item.

1. Observation foundations: exact child lookup/acceptance guard, pure compact receipt
 projection, and locked issued-claim observation. No activation or producer binding.
 [Evidence](ingestion-coordinator-foundations.md).
2. Receipt-only recovery settlement: after the outer owner revoked permission and actual
 enumeration/issued owners exited, select a runner reconciliation verdict. Exact checkpoint
 finishes without another attempt; stale/missing checkpoint uses a winning Resume within
 the existing durable-operation budget3; decreasing confirmed counters is unavailable
 evidence. Revalidate the sealed receipt inside the winning body, checkpoint before its
 outcome, then independently reread the durable terminal row before exact acknowledgement.
 No admission, effect, direct terminal write, new timer, or persistent authority is added.
 Test the actual runner with both SQLite owners, including failed terminal persistence.
3. Stable parent/child coordinator: compose those mechanisms with bounded parent admission,
 fresh/restart permission, strict parent preparation, refusal ordering, actual producer
 completion, existing maintenance, final drain and same-process replacement. Keep the
 captured runner cohort as recovery authority; no whole-history scan or runner rearm API.

The existing knowledge-client-root-walk executor is the first reuse candidate for d.3b's
async producer. Its queued/running OwnedStreamTask already distinguishes cancellation
before start from actual completion after exit. Preserve that distinction in the recorded
adapter and reuse its bounded queue; do not introduce a notification executor. A unary
caller's deadline response is not proof its effect owner exited. Verify the actual exit
signal before choosing the single-file adapter. This is an integration requirement, not
an assertion that the present adapter already carries child key/epoch or completes the row.
