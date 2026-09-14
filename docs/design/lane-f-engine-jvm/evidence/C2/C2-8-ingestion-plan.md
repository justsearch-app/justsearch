# C2-8/9: recorded root walks and operation-scoped recovery

2026-09-14, mechanism selected against 07813a14a after independent source review.
Root owns implementation and acceptance. No owner approval is pending.
[Grounded constraints and rejected alternatives](ingestion-ownership-investigation.md).

## Scope and lifecycle

Retain the locked one-row-per-walk model. An ad-hoc parent owns one child for each frozen
root, including a single file represented as a single-file root. Source changes during
the walk are ordinary input. Generation, declared scan policy and grant changes invalidate
replay. Periodic sync and watcher updates remain maintenance rather than new operation rows.
The existing operation row remains the only acceptance, authority, operation-attempt budget
and client-visible terminal history.

A walk ends after enumeration closes and all of its currently admitted units have durable
terminal outcomes. Later maintenance is outside that walk. Enumeration cancellation/error
must be explicit and cannot masquerade as successful completion. Mixed terminal file
failures preserve successful counts and fail the containing walk; benign source-removal or
policy skips establish no index-write claim. A failed historical attempt that is repaired
before closure remains history, while closure evaluates the latest admitted units.

Overlapping recorded walks are serialized by the root owner. A different request conflicts
with an active overlapping root; an identical keyed retry observes its existing operation.
Disjoint roots may proceed concurrently. Root partitioning still uses the held immutable
plan's exact-duplicate collapse and deterministic nested-root exclusions. Maintenance must
never be silently dropped to enforce this rule.

## Selected representation: one queue-owned walk progress projection

The current path-primary-key jobs table remains the unit execution substrate. Do not create
a second immutable per-version unit table. Use one per-walk progress row
in jobs.db. Its execution facts are the accepted operation key, prepared-plan identity
witness, enumeration epoch/closure, monotonic progress, projection revision and sealed
receipt. Current membership remains in jobs; the existing ingestion ledger retains
terminal receipts needed to deduplicate progress. It is created only after the operation
row was accepted, by the existing queue owner before the first admitted file.

The projection is not another operation authority: it cannot grant permission, choose
inputs, mint an operation key, retry the parent, or publish client completion. It supplies
the durable evidence that the operations owner checkpoints and completes from. Begin/reopen
compares the same key and prepared witness; acceptance in operations.db and begin in jobs.db
remain separate ordered steps with an explicit recovery window.

Extend the existing ingestion ledger with operation attribution and observed/committed unit
identity where required. Terminal outcome, committed hash and progress advance in the same
queue transaction. Preserve the existing exact claim check and post-Lucene-commit journal
boundary. The operations checkpoint callback runs after the jobs lock is released and may
read the latest durable progress; missed delivery is reconstructed at startup. Avoid a
callback into operations.db while holding the queue lock.

### Identity and counters

The effect key remains `(normalized path, source SHA-256)` within the prepared representation
generation. Add an opaque queue admission revision to the existing jobs row and carry it in
IndexJob. A fresh enqueue/replacement mints a revision; claim, retry, deferral and restart
recovery preserve it. It is a stale-write and failure-accounting witness, not an alternative
content identity or a grant. Exact process-local claim ownership remains required too.

Persist the revision in ledger transitions along with the walk key and nullable source hash.
Within one walk, completedUnits counts distinct successful `(path_hash, content_hash)` receipts;
A→B→A counts two distinct successful units. A receipt for A does not permit skipping a current
index at B: recovery must still compare actual committed index contents. FailedUnits counts
terminally exhausted admissions, once per revision; transient attempts do not increment it.
An unreadable admission has a revision but no invented content hash. Repair/replacement can
leave a historical failure in the monotonic counter while resolving the current path.

The seal separately derives current failed and skipped paths from retained jobs rows. These
current outcomes determine terminal success/failure; historical failure counters do not.
Hash-null benign skips never increase indexed completedUnits. The result reports their count
separately. A callback retry reads the latest projection rather than applying a delta twice.

### Exact projection and ownership

The planned `ingestion_walk_progress` row contains `operation_key` (primary key), `plan_hash`,
`enumeration_epoch`, nullable `enumeration_closed_at`, `completed_units`, `failed_units`,
`revision`, nullable `sealed_at`, nullable `receipt_json`, and `acknowledged_revision`.
All counters start at zero and only advance; a seal and its receipt are immutable. There is no
parallel WAITING/RUNNING/FAILED operation state machine. Enumeration closure and sealing are
execution facts; accepted/running/terminal authority stays in operations.db.

The prepared scope is persisted only by the operations owner. At begin/resume the owner
compares the durable plan hash before enabling the accepted key. Queue membership is explicit
admission through scan_id, not inferred from a copied root scope. No scope copy grants
authority at boot.
The receipt schema is versioned and contains the projection revision, final monotonic counts,
current failed/skipped counts and terminal enumeration outcome. No raw paths enter the public
receipt; bounded failure identities use the existing path hash representation.

Add `unit_revision` and `walk_seen_epoch` to current jobs. The ledger adds `operation_key`,
`unit_revision`, nullable `content_hash`, and a nullable terminal coverage class distinguishing
indexed success, exhausted failure and skip from ordinary attempt diagnostics. Index those
identities for transactional deduplication. Current-open and current-terminal membership are
SQL queries over retained jobs, not another aggregate that every update must keep in sync.

Jobs cleanup and ledger retention exclude unacknowledged walks. After a checkpoint or terminal
operations transaction returns, acknowledge only that exact projection revision. Terminal
release requires a sealed receipt and acknowledgement of its final revision. A crash after
operations completion but before acknowledgement replays the same result idempotently. An
absent, expired or quarantined outer row fails reconciliation; it never authorizes recreating
acceptance or silently releasing work. No wall-clock timeout releases an open receipt.

A synchronous operations callback inside the jobs transaction was rejected: it cannot make two
databases atomic and introduces failure/lock-order coupling. Versioning jobs would modify all
path-based consumers and create stronger history than the finite-walk contract needs. The
selected progress row supplies closure and cross-store receipts while preserving the existing
path queue owner, claim enforcement and ledger. It is not an immutable per-version inventory.

## Queue admission, replacement and sealing

Reuse jobs.scan_id as the accepted child key, with actual recording identified by the
corresponding walk projection. Legacy and rowless work retain current orphan behavior.

- A fresh recorded admission creates/updates its unit under the accepted walk. Explicit
  same-operation resume must preserve pending/processing retry metadata and prior committed
  evidence rather than INSERT OR REPLACE over the whole root.
- Maintenance changes to an active walk's already admitted path remain real changes. They
  invalidate the prior current hash and preserve the walk attribution; they are never ignored.
  Explicit retry and actual source change must be distinguished from replay so recovery does
  not reset the per-file attempt/backoff ladder.
- The queue seals an enumerated walk only in a transaction that observes no unfinished owned
  units and captures its final durable progress receipt. Subsequent maintenance is rowless;
  it may change current jobs without changing the sealed receipt. This receipt survives a
  crash before operations.db receives its final checkpoint/completion.
- Deletion/root removal must preserve or terminate active unit accounting. The current
  direct delete paths cannot simply erase a live row and leave the walk waiting forever.
  C2 must not label a source-removal skip as D2's durable index-delete acknowledgement.
- Queue and ledger cleanup preserve every open walk's required evidence. Release/pruning
  follows known operations completion or a provable expired terminal receipt, not wall-clock
  age of a still-open job alone. Crash between outer completion and release is reconciled.

Enumeration recovery increments an epoch and re-walks the frozen scope. Rediscovered members
are marked seen without replacing their retry state. A previously admitted path no longer
present becomes a source-removal skip; a root that is inaccessible fails enumeration rather
than treating every descendant as deleted. The final batch must commit before closure, ideally
in the same queue call. A crash before closure leaves enumeration open and requires re-walk.

Maintenance admission and seal both hold the existing jobs lock. Source updates to an already
admitted path before the seal retain its walk; after sealing they are rowless and cannot alter
its receipt. A new watcher path may remain rowless maintenance: it is processed normally but
is not silently adopted into the finite walk. Only explicit enumerator admission adds members.
Recorded overlapping roots refuse a different active request before it can admit units.
Deletion preserves active membership as a terminal source-removal skip and invalidates its
claim; it does not assert that Lucene deletion committed. Durable delete acknowledgement and
index convergence at activation remain D2/D1 obligations. Explicit root removal cancels the
walk and prevents remaining claims from being certified under that operation.

For boot eligibility, the queue starts with no recorded walk authorized to poll or recover.
A runtime set installed by the sole validated recovery owner permits only resumed keys. The
same predicate gates PENDING polling and both PROCESSING recovery paths. A jobs walk row whose
operations record is missing is recorded-but-unreconciled, never a legacy orphan.

## Boot order and sole authority

Current KnowledgeServer.start recovers PROCESSING rows and arms its reaper before the
generation manager opens, then starts polling before Head bootstrap starts persisted-root
walks. HeadlessApp.connectWorker runs after the worker future and is too late to gate this.

Use the existing EngineRoot composition boundary. The narrow index recovery owner belongs
in worker-services, which already depends on app-api and worker-core. Do not add app-api to
worker-core or import app-services into indexer code. A required synchronous callback supplied
by EngineRoot runs inside KnowledgeServer.start after generation/runtime/app-services and
compatibility initialization, immediately before indexing-loop start.

The grant authority is currently created later by OperationSubstrateInit. Compose that same
sole authority before the index fork and pass it into the Head substrate; do not create a
second grant reader. OperationAttemptRunner.reconcile is a one-shot over its boot snapshot,
not a retained registration. A Wait result leaves units blocked until an explicit later pass.

The required ordering is:

1. Open jobs.db without global recovery, reaper or polling.
2. Initialize generation and the writable index runtime.
3. Construct the index-side owner and synchronously reconcile through EngineRoot.
4. Validate actual producer identity/prepared scope, generation/dependencies and current grant.
5. Compare uncovered units against a committed index view; advance past a present matching
   path/hash effect rather than relying on the timestamp-only UNCHANGED fast path. This view
   must prove committed bytes, not merely NRT visibility.
6. Release only authorized uncovered recorded jobs. Recover true orphans separately.
7. Arm the same scoped reaper and start polling; then expose health readiness.
8. Resume applicable persisted-root rows; mint a boot walk only when none applies, then start
   watcher/periodic maintenance through the established bootstrap owner.

Both PENDING polling and PROCESSING recovery require this eligibility rule. A callback that
runs after either path has already consumed recorded work cannot establish the guarantee.

## Implementation order after design refutation

1. **C2-8c, admission revision foundation:** jobs v17 adds/backfills `unit_revision`; enqueue
   and reenqueue mint it, poll returns it, and exact claims validate it. Preserve it through
   retries and recovery. This is immediately active queue behavior, not an unused replay helper.
   Update the existing jobs format/version register without changing owner/class; prove v16
   migration/reopen, replacement, retry preservation and stale/forged completion refusal.
2. **C2-8d, recorded walk vertical feature:** the following schema cut adds the progress/ledger/
   epoch fields above together with its real producer and lifecycle. Keep this one activated
   feature in reviewable per-item commits; do not claim a schema primitive completes ingestion.
3. Adapt the held typed root plan and child acceptance to current prepared-payload persistence.
   Connect real queue progress and owner completion in the same vertical feature; do not
   reactivate the old test-only replay owner or claim an unused primitive completes C2-8.
4. Carry keys and the same prepared invocation through REST, MCP and boot scans. Parent
   completion composes durable child completion; changed public arguments refuse key reuse.
5. Install the generation-ready recovery seam, sole grant authority and polling/reaper fence.
   Retire global recovery for recorded units while preserving orphan recovery and retry state.
6. Run four-condition, overlapping/maintenance, source-change, cancellation, deletion and
   cross-store failure tests, plus the installed three-point kill scenarios. Update every
   checklist item with tested revision/environment/evidence before declaring C2-8/9 complete.

Per-item commits are pushed immediately, with WIP at least hourly. D1 keeps journal/replay,
live activation and gap refusal; D2 keeps index-and-return and durable delete acknowledgement.
The current draft does not move stage or merge placement: merge remains F.

## Independent review disposition

The September14 source review supports the per-walk receipt but initially proposed adopting
every watcher discovery and representing deletion as a committed tombstone unit. The bounded
follow-up re-read the owning stage and withdrew both requirements: finite membership may
retain only explicitly admitted paths, and source removal may close C2 accounting without
asserting D2 durable absence. This plan adopts those narrower semantics. Remaining review
requirements—durable admission revisions, retry-safe enumeration, immutable sealing, retained
membership/evidence and acknowledged cross-store projection—are included above. The review
was read-only against07813a14a, not an execution or recovery proof.
