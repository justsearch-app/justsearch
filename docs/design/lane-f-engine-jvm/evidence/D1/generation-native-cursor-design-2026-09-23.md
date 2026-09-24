# Generation, native-session and cursor lifetime decisions

Status: selected design at runtime baseline22800c842; implementation and required
production proof remain open. Read with [publication design](publication-and-lifetime-design-2026-09-23.md).
This settles cross-item ownership for D1-8 through14 and D2-6; existing stage
requirements remain binding where not explicitly amended here.

## 1. One serving generation view

KnowledgeServer owns the serving view: runtime, encoder set/model identity,
WorkerAppServices and generation identity. Publish that view under the shared
process publication write lock. Capture once and retain its lifetime under the
read lock. No query may independently resolve a runtime supplier for one leg and
an encoder supplier for another. Thread-local capture is insufficient for async
search/RAG work. Stable Head facades acquire this bound worker view; pointers
inside a controller are not independently rebuilt after publication.

`KnowledgeServer.runtimeSwapLock` continues to serialize physical generation work.
Take it before publication write, never from read capture. New acquisitions use
the published view and its short lease monitor, not the long swap lock. Retired
views reject new ordinary queries but remain usable by already-issued leases and
authorized retained cursors. The read-only Blue runtime and already-open Green
runtime are reused; no same-directory second Lucene open at activation.

The existing generation manager remains the durable pointer owner:
`modules/worker-core/src/main/java/io/justsearch/indexerworker/index/IndexGenerationManager.java`
(`promoteRecordedGenerationToActive`, `promoteBuildingGenerationToActive`).
Keep C2's strict operation-key/source/target-fingerprint binding. Unconditional
promotion is not the recorded-operation route. `KnowledgeServer`'s separate
searchLifecycle/appServices fields must cease to be independently read authorities.

## 2. Activation commitment precedes only prepared installation

The old D1-8 order promoted the durable pointer before fallible service construction
and then proposed pointer rollback if re-pointing failed. Replace that order:

1. Finish bulk plan, replay and candidate readiness. Build/wire the target services
   and validate model identity before promotion. Establish runnable producer
   ownership before the commitment point; prefer reusing Green's existing loop.
   If a successor task is necessary, prepare/claim it through the existing runner
   and hold its effects behind activation. No fallible executor submit/start may
   be required after durable promotion. Do not park a candidate on an executor
   thread needed for the incumbent or final replay to finish.
2. Enter the mutation-routing owner's final cutover fence, stopping new effects
   from crossing the old/new target boundary. Drain accepted in-flight mutations
   into the existing queue/journal and finish an exact-version replay. Queries
   continue on Blue. Use the existing queue/migration owner; do not create a new
   durable journal or freeze all Engine work. Every filesystem and no-file
   projection upsert/delete front must join the same mutation boundary.
3. With intake fenced, verify final document revisions/hashes and gaps. There is
   no admission window between that check and promotion. Candidate construction,
   required Green commit/refresh and successor-row preparation happen before the
   durable pointer change. A final Green marker is not evidence it is already
   the serving generation. Failure before promotion leaves Blue authoritative.
   Cancel/release a prepared successor through its existing runner before retiring
   candidate resources; it must not start Green effects after precommit abandonment.
4. Acquire the existing generation-manager state guard before publication write,
   after runtimeSwapLock; do not wait for mutation completion while holding
   publication. The current strict promotion method acquires STATE_CONTROL
   internally: refactor its owner-only prepared promotion seam so the order is
   runtimeSwapLock → STATE_CONTROL → publication, never the inverse. Keep the
   guard through strict revalidation, durable promotion and prepared installation;
   serving captures read the published view and never acquire STATE_CONTROL.
   Do not expose an unbound promotion or arbitrary handler callback. Revalidate exact C2 binding and all
   owner tokens; promote via the strict recorded manager path. This pointer is
   the durable activation witness. Resolve ambiguous writes by rereading the exact
   binding. Do not treat an exception after a committed move as no effect.
5. Install only prepared serving/service/registry references, then release
   publication and mutation intake. Subsequent accepted effects route to Green.
   The already-prepared successor continues; candidate/private status disappears.
6. Persist reindex completion and predecessor/successor accounting through the
   runner. A crash or row-write error after promotion is committed activation
   recovery, not a failed uncommitted attempt or permission to swap directories
   back. Boot reconstructs serving Green from pointer plus strict manifests and
   reconciles the same rows; it does not create a second successor.
   No durable “in-memory view installed” bit is needed: every boot reconstructs
   the runtime from the committed generation regardless of where the previous
   process died. Keep readiness unavailable until that construction succeeds.
   The existing C2 row/recorded target binding must identify the same successor
   before promotion; missing/corrupt linkage refuses readiness/reconciliation
   rather than inventing a second row. Live publication failure also closes new
   affected captures and enters ordered recovery before releasing mutation routing.
7. Retire Blue after all request/cursor/resource holds end. Its retained state is
   observable but does not make successful Green activation fail. Prune only after
   actual close and guarded predecessor-reference retirement; keep the existing
   manifest/sentinel-based recovery of interrupted deletion.

This supersedes fallible publish-then-rebuild and rollback-after-promotion wording.
If application publication cannot complete after pointer commitment, fail closed
and use ordered recovery from the committed pointer. The API must not report
FAILED with unchanged Blue when disk already names Green. Generic admin rollback
remains outside the recorded activation path; retirement removes only proven
unreferenced predecessor state, never active/building state.

The implementation must prove boot at each cut: before marker, before promotion,
after promotion/before publication, after publication/before terminal receipt,
and before/after predecessor-reference removal. This is not a new persistent
transaction layer: existing generation state/manifests, C2 rows and exact ACKs
remain the recovery authorities. Add bounded linkage fields to those existing
records only if current fields cannot identify the already-prepared successor.

## 3. Replay, gaps and abandonment

Keep existing per-put switch-buffer revisions and delete only exact replayed
versions. File content hashes alone cannot cover no-file projections: use the
accepted projection identity/revision and delete payload already required by955-5.
Final replay observes the fenced accepted set, not a guessed queue-count total.

COMPLETE_WITH_GAPS is nonterminal (OperationState already says so). The accepted
gap decision binds reindex key, target generation and the deterministic current
gap-list hash. Recompute under final cutover fencing. A changed gap set cannot
inherit approval; return GAP_LIST_STALE and refresh the decision surface. An
unrelated MCP context cannot manufacture WEBVIEW authority; preserve the existing
front/mutation-token boundary and context-kind refusal.

Cancel/exhaustion/abandonment must first stop the candidate producer, establish the
runner outcome and exact job acknowledgements, and preserve C2 sealed evidence.
Accepted updates in the building journal must either already be committed to the
surviving active generation or be transferred to its existing durable queue before
their journal revisions may be removed. A cancelled rebuild does not authorize
discarding accepted user writes. Delete only entries owned by this building id,
not a whole table. Retained cleanup failure keeps generation permits and evidence;
boot retries the same cleanup without republishing an obsolete job hash.

## 4. Two-generation capacity includes retired-but-live generations

Serving plus candidate/predecessor is at most two physical representations. A
retained Blue reader, cursor, native resource or refused deletion still consumes
the representation permit. Release only after witnessed directory deletion, as
already required by D1-8; do not release at logical cutover or queue completion.

Consequently, a new rebuild can be refused while Green serves and Blue remains
retained. Use the existing capacity refusal/retry semantics. This is the required
tradeoff for a real two-generation bound; do not create a hidden third generation
or discard valid cursors solely to manufacture capacity. Normal cursor expiry
and defined quota eviction eventually release holds. Surface retained reason and
count in the existing component map.

An abandoned candidate already marked for deletion still consumes its slot until
the next allocation has actually removed it; a locked or ambiguous remnant
refuses allocation. Existing backup-first corruption recovery keeps its damaged
index under a `.bak-*` diagnostic name. That backup is outside the set of
serving/building generations, so it does not prevent rebuilding from source;
the backup still consumes disk space outside this generation permit.

The two-encoder-set limit counts retained A as well as serving B. Count permits
do not prove VRAM fit: retain D1-14's actual device-line/footprint decision and
governed floor override. Boot composes the serving manifest's models, reports
different desired models as pending, and keeps text available when a model is
missing. Do not compare either generation against process-global model identity.

## 5. Native leases bind the exact session instance

`NativeSessionHandle` currently has CPU no-op leases and closes/recreates raw
sessions without their actual holders draining. Replace that lifecycle with
per-session-instance accounting inside the existing handle, for BOTH CPU/GPU.
An issued lease retains exactly the session it returns; release is idempotent,
decrements that instance once, and never closes a different replacement session.

Retirement is monotonic for that handle. All paths, including queued GPU waiters,
recheck retirement when handing out a lease. A waiter interrupted or refused
before handoff relinquishes only its own acquired permit. An issued lease keeps
its permit until actual native work exits; cancelling its future is not release.
Retire waits with the existing monotonic five-second budget outside the short
accounting lock; timeout retains sessions and ownership, and a later retire retries.
Native close and native creation occur outside accounting/publication locks, with
one owner retaining the in-progress action and revalidating identity on completion.

Select bounded CPU recreation: mark the failed instance unavailable to new
acquires, retain outstanding leases, and close it only after its last holder exits.
Create its replacement only after confirmed old-instance close. Acquires during
this interval wait within their existing request deadline/cancellation contract
or return the existing unavailable/failure result; they never use a failed session.
No unbounded list of retained CPU epochs, no new background executor, and no
implicit retry of a non-idempotent operation. If creation finishes after retirement,
close/retain that private candidate without issuing it. A close failure retains
the instance and prevents replacement until owner recovery resolves it.

This serial replacement is simpler than side-by-side CPU epochs and preserves
bounded retention. It does not change the existing GPU/CPU failure classification
policy or adopt the unrelated historical919 device-loss design wholesale.
Extend the SessionHandle CPU acquisition seam with the same explicit monotonic
deadline/cancellation input selected for D2 scheduling. Return typed retired or
temporarily unavailable/deadline failure when it cannot acquire safely; the old
zero-argument immediate/no-op lease contract cannot implement this wait. Migrate
all callers rather than retaining an unbounded compatibility path.
Remove raw unleased session-use paths from production callers; deprecated getters
cannot remain a loophole. Run actual GPU/CPU held-call tests and native stress,
not just reference-count unit tests. F-016 requires controlled hard termination
if native work never quiesces; see the publication design's shutdown section.

## 6. Cursors retain a generation binding, not just a Lucene reader

D2-6 uses RuntimeSession's per-generation SearcherLifetimeManager, plus a bounded
cursor registry owned by the same serving-generation lifetime. A cursor token is
bound to generation, pinned searcher, owning client/context and original expiry.
It carries no raw process pointer. Malformed tokens remain CURSOR_INVALID; valid
but evicted/expired/unavailable-generation tokens produce CURSOR_EXPIRED.

Each page atomically validates the token and acquires a page lease while retaining
the pinned reader and exact encoder/service view. Cursor eviction stops future
pages and removes the registry entry, but an already-running page retains its
resources through actual exit. Paging does not extend original expiry indefinitely.
Do not release the pinned-reader permit until the underlying reader is really
released. If oldest eviction cannot free capacity because a page is still active,
refuse/delay the replacement within existing bounds; do not over-admit the cap.

After side-by-side cutover, A-backed cursors may continue to normal expiry while
their actual A resources remain loaded. They hold Blue and encoder-A capacity;
mark encoder A retiring only after these holds end. After an in-place transition
requiring A's native resources to be retired, expire A's cursors first, drain
active pages, then retire A. Never run an A cursor with B encoders or silently
serve Green documents. Text-only cursors retain only their actual dependencies.

Retiring the disk generation requires all reader/page/encoder holds gone and
close/deletion proof. This amends any interpretation that activation immediately
closes Blue or that a pinned reader alone makes old semantic search safe.

## 7. Installer-produced generation candidate (2026-09-23 amendment)

The ordinary settings owner's `GENERATION_BOUND_REQUIRES_REINDEX` refusal remains
binding. Install AI now separates acquisition from activation when its effective
candidate changes generation-bound inputs. Acquisition reports **Downloaded —
activation required**; a distinct `core.activate-installed-models` operation is
HIGH risk, inline-confirmed, DURABLE and REINDEX. It uses the existing recorded
ingestion runner and strict generation owner with a typed `INSTALLER_GENERATION`
bulk profile. The installer row neither grants durable authority nor silently
starts a rebuild. The real front supplies its own provenance and mutation token.
This explicitly supersedes the old automatic ONNX settings write and restart text.
An effective ordinary/no-op candidate stays with the settings owner and does not
force a generation rebuild.

Before activation acceptance, freeze one operation key, exact source generation
and roots, full `SettingsWitness`, detached desired settings candidate, canonical
index target/replay inputs, validated model content identities, staged asset
ownership and acquisition provenance in a versioned recorded preparation. Retain
decoding of prior v1 bulk plans. The approval and continuation bind that exact
candidate; duplicate calls observe one row and changed assets, witness, roots or
target require a refreshed preview. Only installer-owned path fields may be
changed. Prepare the resolved config in memory; do not serialize credentials or
capture the currently serving target through ordinary `BulkReindexHandler`.
For a mixed ONNX/chat acquisition, installer chat selection is explicit in the
accepted v3 plan, including when the desired chat path already equals the
selected installed path. The selected GGUF and required or actually selected
serving companions are validated against registry variant, download profile,
durable contract, allowed install location, digest and size, then recorded as
asset identities before changing candidate settings. A conflicting active or
failed acquisition state refuses; absent transient status after restart is
normal when durable contract, registry and bytes agree. Operator model,
profile and projector overrides keep their precedence and are never annexed as
installer assets. Invalid selected chat is an explicit activation refusal.
An explicit NONE selection may carry inherited chat settings without claiming
their bytes. Legacy v2 plans have unknown chat intent. Rows with no chat path
remain replayable; a v2 chat path has no selection proof and refuses before
pointer commitment or fences committed-B recovery without rollback or a false
terminal receipt.
Current bytes cannot be hashed into approval during recovery.
Assets serving A stay at their original bytes while B builds; replacement bytes
have candidate-owned placement and remain retained through accepted recovery and
issued/retired views. Fresh installation may use the actual bootstrap/recorded
source identity and an empty target without requiring a READY prior model.

`RecordedIngestionCoordinator` owns composite execution and reconciliation;
`OperationAttemptRunner` remains its only terminal writer. The settings owner
supplies a narrow non-terminal prepared projection and logical reservation at
the final boundary, rather than a nested RECONFIGURE. After fenced exact replay,
recheck the full settings witness and model identities, arm the existing runner's
settings marker only for this typed authorized REINDEX preparation, then take
runtime swap → generation state → publication locks. The strict generation
pointer is this transaction's commitment witness. An unchanged A pointer permits
precommit refusal; a proven B pointer requires roll-forward of the accepted
settings bytes/witness and B runtime/Head publication, including after restart.
Pointer or settings ambiguity fails closed until exact witnesses resolve it.
No ordinary settings reconciliation may COMPLETE this row from a successor
settings witness alone. Boot jointly validates preparation, pointer binding,
asset identity, settings witness, settled replay and reconstructed serving B
before the runner completes the row. An unrelated witness or missing asset is a
recovery refusal. The old pointer is never rolled back as ordinary failure.

The approved mixed stage changes eligible paths together at activation;
pending/failed packages and unchanged paths are excluded. Acquisition releases
its lease before activation admission; the accepted activation owns its assets
and restart recovery. Download cancellation does not cancel an accepted
activation, and postcommit cancellation cannot undo the generation. Preserve
earlier completed activations if a later attempt fails. Required proof includes
actual front approval, precedence/conflict/atomicity, no property promotion,
duplicate activation, invalid provenance/continuation, held A queries, fresh
empty index, model drift and settings conflict, plus kill cuts before arming,
before pointer, between pointer/settings, between settings/publication and before
terminal receipt. Errors after either successful move need exact witness rereads.
Local, installed standard-model and completed hosted evidence remain required.

## 8. Acceptance and remaining empirical questions

Keep all stage tests and add mutation-controlled interleavings for: mixed
runtime/encoder captures; post-promotion outcome recovery; accepted writes during
final replay and abandon; gap-list change after approval; a held old CPU lease
during failure/recreation; a waiter acquiring its permit after retirement; an old
cursor across A/B cutover; eviction during an active page; and capacity refusal
while Blue remains retained. Verify the actual composed consumers.

Windows delete-after-close, ORT close behavior, live-query continuity, model
co-residency and latency/throughput budgets require implementation/platform proof.
They are not unresolved product choices and not permission to weaken acceptance.
If an experiment refutes a selected mechanism, preserve its counterexample and
revisit that bounded decision. Do not reopen the entire architecture merely
because a proof has not yet been executed.
