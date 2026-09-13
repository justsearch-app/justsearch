# C2-4 continuation plan: durable history and keyed outcomes

Status: active design/implementation cut, 2026-09-13, grounded at13b6bd680.
No C2-4 acceptance is claimed. C2-3 consumer placement is recorded in section0
and the owning C2-6/8/10 checklists. Merge placement remains F/PR1.

## Acceptance order and commit cuts

1. **C2-4 wire correction.** R6 explicitly requires UNDONE in the retained proto
   and an enum-values conformance assertion. First run that assertion against the
   existing proto, then add the additive value and its wire changeset. Preserve
   existing field numbers/values. Run the focused services wire test, PMD, UI
   integration compilation and wire gate. Register the history lineage with the
   durable swap, when its producer/consumer guards describe the new authority.
2. **C2-4 keyed query and durable swap.** Start with the named six-state acceptance
   test, including row/store cross-checks and failed units against jobs. One
   committed-row-plus-history-fence read serves HTTP and MCP. Add the existing
   controller's missing handleGet coverage, restart durability, row-first lookup,
   CANCELLED/COMPLETE_WITH_GAPS projection and executionId refusal. Preserve the
   existing recent-history route/resource and bound recent() to terminal SQL rows.
   The receipt exposes bounded metadata only; prepared payloads never enter it.
3. **C2-4 completion projection.** Add post-commit subscription plus bounded
   terminal catch-up on the same store owner; subscribe before catch-up and use
   row identity for deduplication, never a completion high-water mark. Prove a
   non-dispatched memory completion and a crash before ledger append recover one
   event. Wire memory/note through the existing fan-in without duplicating agent
   run events.955 owns its product-specific ActionEvent.Memory variant.
4. **C2-4 reconnect and integration.** Capture the stream boundary before querying
   the durable snapshot, atomically subscribe/replay, and reset/repeat on overflow.
   No SQLite snapshot executes under the channel lock. Prove completion during
   reconnect and restart; update the resource retention declaration, schemas,
   operation-surface/register guards and canonical docs with the implementation.
   Run the item gates, live HTTP/MCP capture and one independent batch review.
   The current [SSE investigation and owning proof obligations](C2-4-sse-plan.md)
   include the multiplexed product path, token incarnation and frontend keyed merge.

Every cut compiles independently, has a substantive commit body and retained
proof inventory, and is pushed immediately. Full suite at the coherent batch
boundary; focused checks during the cuts. No extra installed/model tier is added.

## Source findings that the durable swap must resolve

Verified at13b6bd680; these are required design inputs, not accepted mechanisms:

- OperationHistoryStore.java:28-123 is a 200-entry deque; append listeners run
  outside its lock. OperationHistoryEntry.java:58-66 has an operation reference,
  but no invocation row identity. Keep the listener fan-in, replace deque truth.
- OperationExecutorImpl's completion callback emits STORAGE_FAILED even when the
  terminal SQLite write failed. R1 requires this failure observation; it must not
  make the durable query claim a terminal row that was never committed. Preserve
  the sticky health signal and distinguish the observation from committed history.
- AuditPolicy.NONE suppresses history, not acceptance. Historical visibility
  cannot safely be reconstructed from today's catalog, especially for removed
  operations and non-dispatched producers. Persist the acceptance-time policy
  separately from public identity; its exact migration cut precedes that code.
- SqliteOperationStore.java:102 prunes during construction. Acceptance also prunes
  under its transaction; HeadAssembly's timer prunes hourly.955 requires retained
  completion replay before startup pruning. Settle ordering for both production
  assembly paths before changing retention tests; retaining every row forever is
  not an alternative to the existing 30-day/100000-row bound.
- ActionLedgerChangeRegistry.java:143-153 puts the event in its ring before the
  journal append; ActionEventJournal.java:166-184 logs an append failure without
  reporting it and remembers the tail before writing. Ring deduplication therefore
  blocks retry after an append failure, and does not survive restart. The existing
  journal's 8x4MB retention is a separate window from the operations row window.
  The completion cut must resolve retry/deduplication across both windows before
  choosing additional persistent state; do not silently use row id as a completion
  watermark or invent another intent journal.
- SseEnvelopeWriter.java:224 onward snapshots before subscribing on a fresh attach.
  SseStreamChannel's existing subscribeAndReplay covers only the resume branch.
  Reuse its bounded replay and outside-lock delivery; handle an empty frame window
  and expiry during snapshot explicitly rather than looping forever.

## Reach and retirement

The useful principle is that a projection cannot acknowledge an effect before its
own durable sink accepted it. The present scope is the existing operation history
and action-ledger fan-in. Crash/retry and reconnect tests demonstrate its value;
remove any added projection-only mechanism if the retained source directly serves
that view and the second durable append is retired. Do not generalize this work
into another event bus, completion-sequence ledger or product-specific memory store.

## Keyed query cut

The shared port returns OperationOutcomeView from one SELECT joining the selected
operations row and singleton operations_meta. A present row wins below the fence;
a missing valid key compares its UUIDv7 time with the fence, with no lookup margin.
Malformed/non-v7 and missing far-future keys are typed invalid-key failures.
Timestamps use UTC epoch milliseconds. The typed result contains only code,
executionId and/or a list of Gap(unitId, reason), with bounded identifier/code
components; gaps are read from the reserved gaps_json column, not a handler map.
D1 produces this gap projection with its generation activation mechanism. No
arbitrary content or sealed preparation enters the SELECT's projection columns.

HeadAssembly forwards this narrow read to both the existing history controller
and justsearch_operation_outcome, a seventh curated read-only MCP tool. Querying
never dispatches or accepts an operation. Existing recent-history and SSE remain
until their durable swap cut; this keyed query is not that swap's acceptance.

Use app-engine's existing test access to both halves for the named store cross-check:
real SqliteOperationStore plus real SqliteJobQueue, no new module edge. Cover six
states, units matched to completed/failed jobs, restart, missing-key fence, older
open row below the fence, cancellation, awaiting-acceptance gaps and raw-content
exclusion. HTTP/MCP must serialize the same narrow answer and preserve typed errors.
The new route requires the existing local trust boundary and live capture before
item acceptance; it never requires a mutation token for the read itself.

## Store completion subscription prerequisite

After the keyed-read cut, implement the generic live completion hook before
connecting projection consumers. OperationStore owns subscriptions because direct
non-dispatched producers, pre-start rejection and reconciler completion all commit
through that same owner. Observe only successful state transitions, using the row
snapshot captured with the transition; publish after the SQLite lock is released.
Repeated/refused transitions publish nothing. A throwing observer is logged and
cannot rewrite completion or stop another observer. Unsubscribe/close retire the
listener reference; a notification already iterating its listener snapshot may
still deliver that callback.

This reuses synchronous post-commit notification and bounded retained source rows;
no notification executor, second outbox, acknowledgement marker or completion
sequence is added in this prerequisite. It is live delivery only. The next
projection cut supplies bounded catch-up, row-identity deduplication and startup
replay before pruning. Do not claim the hook alone closes the crash interval.
Test the commit from an independent SQLite reader and the unlocked callback from
another thread reading the same store. Cover non-dispatched MEMORY/NOTE rows,
repeated finish, before-start rejection, listener failure, removal and store close.
The launcher architecture fixture must implement every new port method and stay
in the affected compile/test set after the query-cut omission.

## Journal retry prerequisite

At e47275e59, negative1104 reproduces three sink failures: an unwritten event enters
its durable tail, ring dedup suppresses a persistence retry, and a restart appends
an already-retained id outside the500-entry read tail. Fix these at the existing
journal/registry boundary before attaching source-row replay. Append reports whether
the durable sink retained the event; update its tail only after a successful write.
Attempt the journal before ring dedup, preserving one live delivery while allowing
an already-visible event to retry persistence.

Derive retained-id sets from the existing eight journal generations, once per open,
and maintain them through rotation. The500-entry tail remains the read bound; it
is not the deduplication bound. This cache is a projection of retained files, not a
new persistent identity registry. On uncertain append/rotation failure, rebuild the
cache before a subsequent write. Preserve the existing generation/byte retention,
legacy journal format, disabled-mode behavior and non-durable event kinds. Test
retained ids beyond the tail, rotation, failed-write retry and duplicate live
suppression against real files. Do not claim this bounded sink alone solves
source-row catch-up across the operations/journal retention mismatch; that next
projection decision still precedes its activation.

1105 adds a torn-line replay case and reproduces a further append boundary failure:
a valid next record is concatenated onto the killed writer's fragment and disappears
on reopen. Preserve the fragment but terminate its line before the next append.
This is part of the same journal retry cut; no truncation/rewriting of prior events.
The source-row projection decision is still open: the acknowledgement alternative
must preserve pending source rows within the hard row cap and avoid acknowledging
newer rows while an older replay backlog is blocked. The direct-source read
alternative would change the current one-log read authority. Compare both against
955's explicit durable-journal fan-in before choosing; no owner decision is needed.
Numeric database-local IDs can be reused after quarantine/recovery (C2-2 already
records this); the accepted UUIDv7 row key scopes any stable ledger source identity.

## Acceptance-time history policy

Decision, 2026-09-13: first acceptance persists a four-value history mode and the
original provenance instant. The mode is a projection of the dispatcher's existing
audit/undo declaration, not a new operation identity or permission. NONE hides the
row; STANDARD gives ordinary history; UNDOABLE permits the successful receipt's
executionId; UNDO projects a successful undo as UNDONE. Failure always projects
FAILURE when visible. Direct producers use the same explicit mode parameter;
existing background calls default NONE because AgentRun owns their history.
An existing key returns its original mode even if the current catalog changes.
Audited new acceptance requires an operation reference and provenance. The accepted
context/executor/initiator/correlation columns already own attribution; add only the
missing occurredAt, and never a signed intent token or handler content.

The alternative, reconstructing the current registry's audit policy during replay,
would expose formerly NONE operations or omit removed operations. Schema v4 adds
these columns transactionally; v1-v3 migration defaults history to NONE because no
reliable original declaration exists. Preserve old rows, preparation and sequence;
prove rollback and future-version refusal. This is a source prerequisite, not the
durable recent-history swap or a successful installed-v4 recovery claim.

The next source/sink cut keeps the existing one-log ledger authority required by
955 section4. Add only row-owned projection acknowledgement, drain bounded pending
rows in order and stop at failed persistence before acknowledging any newer row.
A pending row must survive startup pruning within the hard admission cap; never
silently drop it or acknowledge without a durable append. This costs backpressure
if the sink remains unavailable, and avoids replaying the full 30-day source into
the much smaller journal on every boot. Direct SQL union was rejected because it
changes the established one-log ledger read and 955 fan-in contract. Final retention
eligibility and ordering tests precede activation; this cut does not add a marker.

The schema cut also owns the operations register projection. The gate previously
validated jobs VERSION text only (TARGET_VERSION), so an operations 3/4 mismatch
passed1118. Extend the same stripped-source literal check to OperationSchema.VERSION;
negative self-tests and a real mismatched-register run precede the corrected v4
row. Preserve all jobs checks. Include current updater, release descriptor and
Java upgrade-lifecycle consumer tests; the installed-v4 recovery campaign remains
required at its existing integrated/hosted tier.

## Durable recent reader and invocation identity

Decision, 2026-09-13, at7b5248442: OperationHistoryStore becomes a read/listener
facade over the existing operations owner. Its bounded SELECT includes only the
metadata needed by OperationHistoryRow; identity JSON and preparation never enter
that projection. Filter NONE and nonterminal states, order by completion time then
row id, select the latest200 and return oldest first. An older accepted row that
completes later must appear; no row-id high-water mark is used. Display capacity
does not delete source rows. The Resource declares DURABLE with30-day retention,
while its bounded live frame window remains five minutes.

OperationHistoryEntry gains optional operationKey, also proto field11 and the
schema. operationId remains the operation declaration; it cannot identify an
invocation or suppress a repeated legitimate dispatch. Committed SQL and live
history use one projection from accepted row metadata, with no signed intent token.
The action-ledger id for new committed entries is operation:<accepted UUIDv7 key>;
legacy and uncommitted failure observations retain their existing fallback id.
R1 STORAGE_FAILED still emits a live observation, but append cannot add a fictitious
terminal row to SQL recent(). Preserve advisory delivery including audit NONE.

Retire the deque and default authority-free constructor. Both production bootstrap
paths pass the one OperationStore; tests seed actual terminal rows or explicitly
mock that read port. Keep addAppendListener/append as the existing live fan-in until
the following completion-source cut replaces dispatcher-only publication. Prove
restart, query bounds/retention, privacy, repeated-operation identities, late older
completion, and HTTP snapshot parity. Wire/schema/register guards and live capture
are owed in this cut. Source acknowledgement/catch-up and atomic SSE reconnect are
not claimed merely because reads are now durable.

## Completion acknowledgement and retention

Decision, 2026-09-13, at2bcaee280: schema v5 adds one history_pending bit to the
operations owner. Every new visible terminal transition, including pre-start
rejection, sets it in the same SQL statement. Hidden NONE rows owe no history
projection. The single projector reads bounded batches ordered by completion then
id, uses the accepted key as sink identity, and acknowledges only durable append
or the explicit AgentRun ownership exclusion. Memory/note kinds remain eligible
even with AGENT_LOOP provenance. No outcome field or timestamp changes on ack.
Pending rows survive both startup/age pruning and cap pruning; if protected/open
rows exhaust100000 slots, admission refuses rather than losing accepted history.

Earlier schema versions had no source delivery acknowledgement. Migration leaves
already-terminal v4 rows with their prior best-effort ledger guarantee and existing
durable recent-history visibility; it does not replay them into a possibly already
populated legacy-id journal. Such replay cannot distinguish an old collision from
a missing event. Open v4 rows acquire pending delivery on their eventual terminal
transition. v1-v3 visibility remains NONE as previously decided. This explicit
upgrade boundary avoids inventing retroactive exactly-once delivery or duplicating
historical events. Prove the migration and rollback using a frozen v4 fixture.

This bit replaces the rejected completion watermark/full-source boot replay: old
accepted rows may complete late, numeric ids can be reused after quarantine, and
the journal retention is smaller than source retention. The source stays retained
until the sink accepts; sink failure stops a drain before newer acknowledgement.
The subsequent attachment owns retry scheduling, shutdown and publication ordering;
the bit alone is not an active completion consumer or finished C2-4 proof.

## Journal barrier before acknowledgement

Decision,2026-09-13: existing ActionEventJournal append must force its file before
returning accepted. The ordinary atomic-replacement helper owns replacement, not
append/rotation, so reuse this journal owner rather than introducing another writer.
An uncertain force can leave a complete parseable line; a retained retry therefore
forces the actual numbered/active generation before it may discharge pending source
delivery. Injected failure/rotation tests prove ordering and no duplicate. This is
not a claim of physical power-loss testing or a new file-format guarantee.

## Client effect identity boundary

Correction,2026-09-13, independent reader review at2bcaee280: effect ingest accepted
any nonblank id, while the ledger deduplicates globally. A caller could post
operation:<K> before invoking K and hide the actual operation in the ring-first
snapshot and live publication. Reserve the frontend's existing namespace at ingress:
fe-effect followed by a canonical lowercase UUIDv4 for new entries, or the prior
positive numeric journal id (at most16 decimal digits) for persisted legacy entries.
The follow-up review also demonstrated that the local counter restarts at1 in a
fresh client, so a numeric wire id aliases another client's effect. Mint each new
ledgerId once and persist it in the existing journal entry; retain numeric id for
local undo/causation. A per-client persistent epoch or another registry adds ownership
without benefit over this per-entry identity. All three client projections (local
row, backend-copy dedup, ingest) use the same persisted ledgerId. Legacy entries
retain their old wire identity on reload, avoiding duplicate old events; this does
not promise retroactive repair of old-client collisions. No new journal version,
store, or per-kind ledger dedup fork is needed.
Reject before publication, prove forged-effect-first/operation-second and valid
frontend retries, and verify the product endpoint on the current Engine.

## Completion consumer ownership

Decision,2026-09-13, grounded at73b100dec: one OperationHistoryProjector in
app-observability.operations owns pending-row delivery and acknowledgement. It reuses
OperationHistoryProjection, the existing history/listener facade and the one ledger
journal. HeadAssembly attaches it as the final fallible operation in both bootstrap
paths. Earlier attachment followed by acquiredOwners cleanup is insufficient: a
later bootstrap failure could lose the retryable owner after a cleanup timeout.
Starting inside OperationSubstrateInit likewise precedes fallible substrate work.
Final placement avoids that failed-construction ownership gap without a new lifecycle
registry or a two-phase public start API.
OperationSubstrateInit exposes the same wiring for both paths and retires its old
committed dispatcher fan-in. Empty-key STORAGE_FAILED observations and the separate
advisory path remain live; they cannot create a fictitious committed completion.

Subscribe before the initial scheduled pending read. The durable pending bit closes
that race. Each newly committed visible row publishes live history exactly through
this hook, including non-dispatched producers and pre-start refusals. The ledger
gets a live-only operation projection; MEMORY and NOTE are included before applying
the generic AGENT_LOOP exclusion (whose operation ledger owner remains AgentRunStore).
The single drainer uses the same kind decision and explicitly acknowledges excluded
rows. NONE rows never enter this pending projection.

Use one registered BACKGROUND scheduled owner, one fixed-delay task (initial0,
one-second retry), at most256 rows per query/arm (512 total during startup). Immediate live publication removes the
need for another wakeup queue or coalescer. The existing hourly retention owner is
too slow and has different shutdown/maintenance responsibilities. Attempt journal
append before acknowledgement, publish the replay into the existing live ring even
when that sink is unavailable, and stop the pass at the first append or ack failure.
The SQL pending row remains the only durable retry authority; disabled persistence never
counts as acknowledgement. Live-only updates preserve the existing IN_MEMORY/disk
failure behavior. A successful source ack can follow only the sink's forced append
or retained-id force barrier, including the uncertain-append/retry path.

Independent design review identified a startup liveness gap: stopping at an old
failed append also hides every newer pending row from a restarted live ledger.
Continuing only the first256 rows still starves a larger disabled-sink backlog.
Add one-shot bounded startup live enumeration independent of the durable arm,
using a transient lexicographic (completed_at,id) cursor over pending rows. The
source exposes the after-cursor metadata query; no new persisted marker or row DTO.
Advance only after a row is live-published or deliberately excluded. Stop enumeration
at the first empty page. Immediately after subscription, capture the source's
maximum accepted row id and apply id <= that ceiling in every startup SQL page.
A completion-time upper bound alone is insufficient when the wall clock regresses:
newly accepted rows could keep filling pages below it. The id ceiling defines a
finite cohort even then, while older accepted rows terminalizing later are covered
by the live hook. Capture after subscribe needs no combined lock: interval rows may
be seen by both paths and use the existing dedup; rows beyond the captured id are
only the live hook's responsibility. Neither id ceiling nor cursor is persisted or
used for acknowledgement/pruning. Retain the subscription for later completions. Those
callbacks cover newer completions even when their timestamp/id sorts behind the
startup cursor. The durable arm always starts at the oldest pending row and must
live-publish before ack, so removal/pruning before startup reaches it cannot lose
live visibility. Run both bounded arms per tick to avoid starving either.

Reject OFFSET (acknowledgement shifts positions), full100k loading (unbounded batch),
and recentHistory200 replay (resurrects already acknowledged rows outside journal
retention). Reject cyclic enumeration: it would continually evict/reinsert a backlog
larger than the500-event ring. The transient startup cursor is enumeration only,
never a completion or durable-delivery watermark. Restart begins enumeration again;
no progress across repeated restarts with an unavailable sink is claimed.

The ring's dedup window remains500 events; durable identities cover retained journal
generations. Do not claim unbounded exactly-once SSE delivery or add another pending
identity set: after ring eviction a replay can publish an UPDATE again, and C2-4's
frontend keyed merge must handle that. The actual typed runtime listener is the
Index-only ScanRollupLedger, which also deduplicates document identities; an operation
double-counting failure was not established. Preserve this limit in the proof.

Close first rejects/quiesces completion callbacks and unsubscribes, then cancels and
awaits the scheduled owner before releasing its executor registration. A timeout
leaves registration and dependency teardown retryable. Head closes this barrier
before its one-shot closed flag and before operations/ledger dependencies. The sole-ack ArchUnit rule lands with this consumer
and a deliberate unauthorized caller proves that it fires.
Creation must also unwind a partially acquired callback/scheduler before throwing:
Head can acquire the returned owner only after creation succeeds. Subscription
close alone is insufficient because an already captured callback may still run.
Hold the delivery gate across construction acquisitions, subscription, accepted-id ceiling
capture and scheduling. Callbacks and the timer's initial handshake wait behind
that gate; failure marks stopping before release and self-unwinds. The timer releases
the handshake before SQL/journal work, preserving immediate producer visibility.

Verify direct producer completion, pre-start failure, NONE/excluded-agent behavior,
memory/note agent fan-in, bounded catch-up after reopen, append-success/ack-failure
retry without duplicate journal bytes, disabled/failed sink live visibility with
pending retained, idle recovery, callback/scheduler shutdown and timeout/retry,
both bootstrap paths, and no duplicate committed dispatcher history. This adds no
schema version, watermark, second durable store or new effect implementation. The
955 typed Memory event adapter remains within this sole projector's ownership.
