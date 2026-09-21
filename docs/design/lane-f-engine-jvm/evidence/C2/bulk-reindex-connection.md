# C2-10 bulk reindex connection

2026-09-21 design settled against `0a23b0a4f`. Implementation remains open. The installed
INGEST/SETTINGS fault proof does not discharge it. Current source and the owning
operations-store design agree on the gap; no later amendment transfers C2's row,
plan, resume or processing-history/gaps obligations to D1.

## Scope and existing owners

`core.reindex` now uses the C2-8d streaming recorded-root path. Its both-store
reopen test preserves parent/child identity and advances the stored operation.
Do not restore the old full document/hash pre-walk on that path. `core.bulk-reindex`
is different: its former immediate startMigration response and dispatch lease have
been replaced in the current sequence3 worktree by a shared prepared bulk/rebuild
handler and REINDEX/DURABLE catalog profiles. The application continuation consumer
is still unconnected and this work is not complete. C2-10 still requires
the bulk row, frozen plan, resume and bounded history/full gaps; D1 owns journal,
replay, live activation and gap refusal.

The existing OperationAttemptRunner remains the sole attempt/terminal writer;
SqliteOperationStore remains the durable owner. EngineRoot composes the application
and index owners. Do not add another execution journal or terminal writer.
RecordedIngestionCoordinator currently reconciles all REINDEX rows through its
root-plan resolver. Bulk routing must distinguish operation reference within the
shared kind recovery pass; a second competing REINDEX reconciler is incorrect.

## Exact source map for continuation

| Owner | Current behavior / required seam |
| --- | --- |
| app-services `CoreOperationCatalog.bulkReindex`, `BulkReindexHandler` | Sequence3 freezes bulk/rebuild scope and target before approval; durable consumer connection remains open |
| app-engine `EngineKnowledgeClient.startMigration` | Requests restart on accepted + restartRequired, before asynchronous rebuild |
| app-services `worker/MigrationOps`, app-api `IndexingService.MigrationOutcome` | Pushed29071724c preserves active/building/state witnesses and provides deferred recorded start |
| worker-services `services/MigrationControlOps` | Start response already exposes building generation id |
| worker-core `index/IndexGenerationManager` | Persists active/building/previous and lifecycle; start creates Green/MIGRATING; promotion sets active=Green, clears building and returns IDLE |
| indexer-worker `server/ops/KnowledgeServerMigrationOps` | Promotion precedes a second restart request; boot enumeration reloads current roots and streams path/size, not an acceptance-frozen hash plan |
| app-observability `operations/OperationSchema` | Existing building_generation_id, target_settings_json, gaps_json and processing_history columns |
| app-api `operations/OperationStore`, `OperationAttemptRunner` | Sequence3 adds typed immutable bulk projection and issued-handle checkpoint; ordinary row reads avoid large evidence payloads |
| app-engine `RecordedIngestionCoordinator.pump` | Shared REINDEX pass must preserve root-plan ownership and route bulk explicitly |

The building id must be durable before the first restart; successor comparison must
use active generation after promotion because building generation is cleared.
Two actual restart boundaries matter: migration start and post-promotion. Existing
BulkReindexHandlerTest, IndexGenerationManagerRestartTest and
EngineMigrationLifecycleTest are component evidence, not the bulk operation proof.

## Preparation size and source-revision constraints

The full per-file plan cannot be an unbounded prepared JSON value: the existing
replay JSON limit is200,000 bytes, envelope limit524,288 bytes and stored payload
limit750,000 bytes. Arbitrarily
refusing larger corpora solely to fit that representation is not the selected scope.
The serving index alone is insufficient: content_sha256 hashes extracted stored
content, not source-file bytes, and excludes unindexed/new files under captured roots.
Migration currently enqueues path/size only, without operation membership. The closest
existing durable substrate is jobs/recorded-walk/ingestion-ledger membership, and pushed3caf90cbc adds frozen H1, captured manifest and immutable settlement.
The application still must connect that substrate to actual capture and migration.
Using it requires an explicit capture-before-claim contract and preserved H1 evidence;
the current root-plan digest must not be relabeled as a captured-document manifest.

A deterministic `g-<accepted UUIDv7 operation key>` passes current generation path
safety validation and removes the response-to-row-binding crash gap. The selected
exact-id API extends IndexGenerationManager; legacy automatic migrations retain its
time-based allocator. The accepted execution handle carries the key; prepare does not.
The captured prepared payload is accepted before any migration effect.

## Selected design and acceptance

1. Accept one REINDEX/DURABLE bulk row and its prepared plan before effects; preserve
   exact caller identity across retry and restart without starting another migration.
2. Advance the same row across both restart boundaries using the persisted generation
   witness. The successor terminalizes after promotion and the second restart. This
   supersedes C2's contradictory completion-before-restart sentence and preserves its
   explicit successor-advancement requirement, including a kill before terminal write.
3. Capture H1, observe H2, report superseded processing history without blocking, and
   persist the required history/gaps projection. Keep D1 activation/gap refusal separate.
4. Extend the existing queue, generation manager and runner rather than add a second
   execution journal or terminal writer. The specific evidence projection below is
   required because sealed queue paths can be reused before outer acknowledgement.

### Capture and immutable settlement

The bounded prepared payload freezes roots, exclusions, target settings, serving
generation and normalized public arguments. After durable acceptance, the RUNNING
operation captures regular source files in the existing recorded walk, before Green
is created or claims are allowed. Capture is a finite enumeration interval, not an
atomic filesystem snapshot. A restarted open enumeration preserves each previously
captured H1, adds newly seen files, and retains disappeared members as gaps. Partial
or inaccessible root coverage cannot close COMPLETE or create Green; a genuinely
empty captured scope can. There is no corpus-size cap imposed by prepared JSON.

Recovery rebuild preparation uses a separate strict read-only idle-active witness.
The advertised rebuild-brake remedy has searchable Blue but no ingest runtime;
requiring the ordinary writable-serving witness would make its remedy unreachable.
USER_BULK retains the ordinary preparation requirement; RECOVERY_REBUILD uses
captureRebuildGeneration before and after target capture. Both read current state
without fallback or repair and require the captured serving path to remain active
in IDLE with no building generation. Physical-target metadata is configuration
owned and can be read with a serving runtime even when the ingest runtime is absent.
This does not authorize normal writes. Captured traversal/boot capability checks
must likewise distinguish readable rebuild-source readiness from writable Green.

Traversal remains WorkerScanOps, extended with a closed captured-plan mode carried
through the Java-only RecordedRootScan/ScanRequest. Reuse SourceContentHash through
a narrow visibility change. Captured mode hashes admitted regular files and keeps
2,000-entry enqueue batches, but bypasses both queue-depth waits: claims cannot
reduce the 90,000-job high watermark before COMPLETE, so ordinary backpressure would
deadlock large captures. Other scan modes retain their current pacing. One bulk
parent key/epoch spans sequential roots; only completion of every root and actual
producer/progress exit closes the capture. A captured cloud-only placeholder is
incomplete source coverage: refuse COMPLETE/Green creation without hydrating it.
Preserve unreadable-member refusal, current-generation checks before traversal and
each batch, and a final IDLE/source-generation check before exact Green creation.
A physical restart cancels and drains the producer but leaves interrupted capture
recoverable with first H1 retained; actual operation cancellation remains terminal.

Add raw-source `planned_source_sha256` H1 to live jobs and append-only ingestion
ledger evidence. Make the existing SourceContentHash authority reusable. H2 already
travels from stable pre/post extraction raw-byte hashing through ExtractedJob and
the committed queue transition (SourceContentHash:12-32, JobBatchExtractor:252-301,
JobBatchWriter:128-164, SqliteJobQueue:1204-1245,1764-1804). It is not Lucene's
extracted-text CONTENT_SHA256. Force claims use the existing recordedForce path.

Live jobs alone are insufficient: SqliteIngestionWalkOps:306-319 releases ownership
at seal and permits later path replacement before operation ACK. Latest-ledger-row
selection is also insufficient after superseded claims. Add
`ingestion_walk_sealed_units(operation_key, sealed_revision, path_hash,
unit_revision, ledger_id)` under the existing queue connection/lock, with primary
key `(operation_key,path_hash)` and unique ledger_id. It stores immutable selection
references, not outcomes or another journal. In the seal transaction, validate each
current member's exact terminal ledger evidence, insert its reference, hash the
canonical ordered joined evidence, and persist hash/counts in the bounded receipt.
Post-seal reads join these references to the ledger, never mutable jobs. Retention
must protect referenced evidence until the outer matching terminal receipt is
durable and exact walk acknowledgement permits pruning.

INDEXED with H1=H2 is stable; INDEXED with H1!=H2 is superseded, successful and
nonblocking. Required-source FAILED/SKIPPED is a gap with its durable reason.
Unstable extraction cannot claim success. The settlement exposes full gaps, full
failed/superseded counts and a deterministic processing-history sample capped at
200, together with sealed revision and canonical settlement hash. No current gap
is hidden by the history cap. Current gaps come from selected final members;
processing history also includes earlier failed/superseded terminal events for
this operation, even when the member eventually succeeds. Hash that ordered
operation-ledger history with the selected final evidence. Issued-owner drain
before seal and retention through ACK keep both immutable. Do not collapse
historical failures into current gaps or erase them after a successful retry.

Persist a captured-plan mode and a canonical `(path hash,H1)` manifest digest/count
at COMPLETE enumeration under the queue owner. This distinguishes a genuinely
empty captured plan from a streaming walk and denies claims during capture even
if the outer policy is permissive. H1 rides the issued claim so a superseded
claim's ledger event cannot borrow H1 from its replacement. Maintenance preserves
H1 while membership is unsealed; replacements after seal have no such membership.

### Generation binding and recovery

Exact start uses `g-<operation key>`: same building is idempotent, same active in
IDLE means already promoted, another building conflicts. A pristine orphan may be
adopted only when exact target manifest/sentinel proves ownership and compatible
configuration; foreign, corrupt or non-pristine targets refuse. Same-target FAILED
does not allocate another generation. Never use a suffix to escape the identity.
Disable the immediate start restart only for this recorded path until the runner
persists the binding. Recovery derives and verifies the same target if state.json
was written before that witness. Then request the existing first restart.

Use existing operations columns through typed, runner-owned metadata writes:
target generation/settings, phase, cursor/counts, full gaps and bounded history.
C2 phases are capturing/building/settled; D1 owns replay and activation phases.
RecordedIngestionCoordinator keeps one REINDEX recovery pass, dispatching by
operation ref; streaming core.reindex retains its current owner. Claims require
validated accepted preparation, COMPLETE enumeration, current authority and exact
Green write-generation binding. Unknown or contradictory ownership never claims.

Before promotion, read immutable queue settlement and release its lock, then
checkpoint the projection through the runner and release the operations lock.
The cutover callback runs outside queue transactions. No callback holds both
SQLite owners' locks. Keep queue evidence through promotion and restart. Successor
completion requires exact target active, migration IDLE, empty gaps and a matching
sealed projection. Migration failure retains gaps; a target promoted with gaps
before D1's refusal exists fails PROMOTED_WITH_GAPS. C2 never emits
COMPLETE_WITH_GAPS. After durable terminal write, ACK the exact sealed revision;
a crash before ACK is repaired from the terminal row and matching settlement hash.

### Real producers and retained scope

Route core.bulk-reindex (USER_BULK), core.rebuild-index (RECOVERY_REBUILD), and manual
REST migration start through this same owner. IndexingController:386-415 and
RebuildIndexHandler:49-80 currently bypass durable dispatch and must change in the
same item. Preserve normalized MigrationSource in public identity. REST mints a
UUIDv7 key when absent and returns it in its 202 response. Automatic boot schema/
embedding migrations remain worker-native. Retire the two short dispatch leases,
ordinary immediate-completion bulk adapter, and stale Worker/gRPC comments when
their replacements are connected; preserve real trust-boundary validation.

REST bridging should extend OperationsController's existing closed alias forms and
admissionOperation route classification, alongside INGEST/REINDEX. Map migration/start
to RECOVERY_REBUILD with normalized reason carried as source, retain the alias's
idempotencyKey/confirmationToken/preparationNonce handling, and register it beside
the other operation aliases in ResourceApiModule. Retire IndexingRoutes' old raw
handler route and IndexingController's immediate-start lease path. This reuses the
existing prepared confirmation/pending-authorization handling instead of injecting
a second dispatcher flow into IndexingController. A request without required consent
must return the existing confirmation response; the mutation token alone is not a
HIGH-risk capsule. Successful accepted pending work returns202 with the stable key.

The captured plan covers regular source files. Buffered deletes, SYNC_ROOT, VDU
updates and synthetic non-file mutations remain D1 journal/replay/live-activation
obligations. C2's file-plan proof does not certify full migration convergence.

## Implementation sequence and proof

Root owns shared queue/schema/generation/lifecycle edits, integration and the single
Gradle/stack lane. Bounded test fixtures and independent review can be delegated
after their source contract is fixed. Do not edit build inputs during an active run.

1. Extend queue capture and immutable settlement. Parameterized queue tests cover
   interrupted epochs/H1 preservation, new/missing files, stable/superseded H2,
   unstable extraction, failed/skipped gaps, path reuse after seal, >200 histories
   with full gaps, and retention/ACK. Preserve existing streaming-walk behavior.
2. Add exact-generation start and response witness. State-table tests cover absent,
   same building, same active, foreign building, owned pristine orphan, corrupt/
   foreign orphan and same-target failure. No automatic-migration behavior change.
3. Connect prepared handlers, typed metadata and single recovery routing, then the
   two restart/cutover callbacks. Owner fault-point tests cover acceptance/capture,
   start/binding, first restart, settlement, promotion, second restart, terminal/ACK.
4. Bridge REST/bulk/rebuild producers. Compatibility tests prove durable real REST
   acceptance, profile identity, retained retries, unchanged streaming reindex and
   automatic boot migration. Verify the real UI if its user-visible flow changes.
5. Extend the existing installed supervised matrix for capture, state-before-witness
   and promotion-before-terminal kills. Assert one operation key, one exact target,
   both requested restarts and one terminal outcome. Run focused tests/lint, negative
   controls for material predicates, independent refute-first review, then integrated
   compile/unit/stress and required hosted proof. Record exact revision/environment,
   XML and accessible raw artifacts alongside each completed batch.
6. Finish C2-12 residue/schema/governance/doc reconciliation. Continue D1/D2/E/F;
   this plan does not defer their acceptance work or authorize early merge.

## Reach and retirement

Mutable work queues may execute a finite plan, but an outer durable operation needs
immutable selected evidence before paths can be reused. This is a local extension
of the existing receipt/ACK seam, not a new generalized execution framework. It earns
its keep when a post-seal path replacement leaves bulk history/gaps unchanged across
restart. Candidate reuse is another recorded walk requiring that same property;
do not broaden ordinary streaming walks without need. Retire the selection table
if the queue later preserves exact unit identity through outer terminal ACK without
blocking newer work. No implementation or execution proof is claimed by this design.

## Queue implementation in progress (2026-09-21, after85154fed1)

Root owns the first sequence item. Schema19 extends the existing jobs/ledger rows
with planned_source_sha256 and progress with captured_plan/manifest_sha256/planned_units.
The queue denies captured claims until COMPLETE enumeration, retaining first H1
across interrupted epochs and maintenance admissions. COMPLETE hashes canonical
ordered path-hash/H1 pairs; the manifest includes earlier-epoch disappeared members,
which close as explicit gaps. Failed/cancelled capture never receives a completed
manifest and uses the ordinary version1 refusal receipt. Empty COMPLETE is valid.

SqliteCapturedWalkOps is a projection helper under the existing queue connection,
lock and transaction, not a writer owner or second journal. Its selected ledger IDs
are installed in the seal transaction. Version2 receipts bind exact selected members,
full terminal history, manifest, gap count and uncapped failed/superseded event counts.
Readback uses ledger references rather than live paths, rehashes selection/history
and refuses inconsistent evidence. Full current gaps are returned with a deterministic
200-entry failed/superseded history sample. Exact ACK validates retained settlement
before permitting normal age cleanup to delete selection references with ledger rows.

Migration19 retains streaming receipts as version1; the privacy-repair copy also
preserves H1 and ledger IDs. Tests are being added for actual upgrade/rollback,
interrupted capture, claim gating, missing sources, stable/superseded processing,
post-seal path reuse, history limits and retention. No executed proof is claimed yet.
The application bulk consumer and both restart boundaries remain unimplemented.

Read-only generation inspection confirms the next seam: MigrationControlOps already
returns a building generation ID, but MigrationOps/MigrationOutcome discard it and
EngineKnowledgeClient immediately requests restart. Extend the existing response
projection and expose a narrow recorded-path restart request only after the runner
persists binding. Preserve ordinary automatic migration's allocator and immediate
restart behavior. Accepted OperationRecordHandle.key supplies the exact target;
preparation has no accepted key. The root owns this subsequent lifecycle change.

### Queue verification and consolidated review

The first2127 run compiled and represented138 cases with two failures: the history
fixture polled an older pending guard ahead of replacements, and an existing
migration test still asserted schema18. Formatting also failed. The corrected
fixture claims the three intended current members, completes replacements, then
settles its held guard. It does not relax capture-before-claim or pre-maintenance seal.

One consolidated independent review identified three fail-closed omissions:
validate terminal ledger outcome/retry compatibility and hash retry policy; reject
capture-mode values other than integer0/1; validate completed capture projection and
member H1 before issuing claims. Root implemented these at the existing owners and
added pre-seal corruption, post-seal read/ACK refusal and pre-effect claim regressions.
The first full2129 executes1,036 cases, with only three remaining old schema18
expectations failing and21 existing qualified skips; one PMD redundant qualifier
also failed. Those expectations now assert schema19 without changing preserved-row
or migration-rollback predicates. No product behavior was weakened to pass them.

Negative2131 temporarily removes capture closure gating, first-H1 maintenance
preservation and terminal-outcome validation. Nine represented cases contain exactly
three intended failures: premature ALLOW_FORCE claim, H1 replaced by H2, and missing
pre-seal refusal. Six other represented cases pass. The script restores both
production files byte-for-byte in finally; original sources and XML are retained.

Final2133 represents1,036 cases/188 suites, zero failures/errors and21 skips.
Indexer-worker executes690 cases; unchanged worker-core346 results reuse the matching
2129 execution. Skips are18 unavailable model checks and3 filesystem/privilege checks;
none belongs to the new captured-plan cases. Both modules' main/test PMD and format
pass. Store gate2133 passes6 catalogs/46 authorities; canonical index/skills/links pass.

Exact command/revision/result inventories and XML: tmp/2127-captured-queue*,
tmp/2129-captured-queue*, tmp/2131-captured-negative*, tmp/2133-captured-queue*.
Final sources and skip cases have separate2133 JSON inventories. The negative script
and source backups are tmp/2131-captured-negative.py and tmp/2131-original-*.java.
Retain through lane acceptance plus30 days; export before worktree release. No stack
was started for this queue proof. Full stress remains owed at the coherent C2 boundary.

Sequence1's queue capability is implemented and locally verified. It is not the
application bulk consumer or installed bulk restart proof. Next is exact generation
start and response witness, then prepared bulk/rebuild/REST routing and the existing
runner's recovery/cutover connection. D1/D2/E/F remain unchanged obligations.

### Exact-generation target contract selected after queue checkpoint

Queue checkpoint3caf90cbc is pushed. The exact-start compatibility witness is the
existing IndexFingerprint canonical physical-index inputs and digest, including
stored-output model identities, rather than a second full ResolvedConfig/UI-settings
projection. The accepted target_settings_json will retain those canonical inputs;
the generation manifest retains only their digest. Indeterminate fingerprint inputs
cannot be represented as a known compatible target. Roots/exclusions/public profile
remain separate frozen prepared fields. Query-only settings do not invalidate a
physical generation. The recorded manifest becomes version2 with target_index_fingerprint;
ordinary automatic manifests remain version1 and their source display stays unchanged.

Exact start takes the canonical accepted UUIDv7 key, derives g-<key>, and requires
a valid existing current state.json. It never reconstructs a serving state from an
absent/corrupt pointer. Same-target building or already-active IDLE state must retain
matching strict metadata; FAILED or foreign ownership refuses. An orphan may be
adopted only with both matching regular metadata files, no other entries, matching
source/fingerprint and exact target identity. Partial/foreign/non-pristine or symlink
targets refuse; no suffix allocation or destructive cleanup hides that refusal.

Multiple manager instances currently mutate the same pointers; atomic replacement
alone does not serialize read/modify/write or protect orphan creation from GC. Root
selects one process-wide generation-state monitor covering pointer mutation, fallback
repair and GC. Generation control is infrequent and one Engine owns the index tree.
This avoids a persistent lock file, path-alias-sensitive lock registry, lifecycle of
registry entries, or broad constructor/composition rewrite to force one manager.
It conservatively serializes control across collections; it does not guard indexing
or model work. The best-effort state read also takes this monitor because it can
restore/upgrade the authoritative pointer. Existing strict observation remains an
observation, not a lease across future transitions. No cross-process guarantee is
added; the existing Engine/index-root ownership remains required.

The recorded start response propagates exact active/building/state witnesses through
the existing migration projection. Recorded start defers restart; the runner's
composition-owned restart callback executes only after durable metadata binding.
Ordinary automatic/manual legacy start behavior remains until the recorded producer
bridge replaces the designated manual paths. State-written-before-witness recovery
continues to derive and verify the same target across the two stores.

### Sequence2 verification and sequence3 entry

Focused2137 compiles the changed API, protocol, generation and client paths and
represents42 cases across10 suites with no failures/errors. One new symlink case
is skipped because this Windows account lacks the required privilege; Linux hosted
execution remains required for that predicate. Negative2139 removes fingerprint
comparison, pristine-orphan enforcement and response witness projection. Its11
represented cases produce exactly three intended assertion failures. The script
restores both production files byte-for-byte. Evidence lives under
tmp/2137-recorded-generation* and tmp/2139-recorded-generation-negative*; retain
through acceptance plus30 days and export before releasing the worktree.

The consolidated independent review found one semantic state-table omission:
active and building could name the same valid recorded target. Root now refuses
that equality before same-target acceptance, with MIGRATING and SWITCHING regressions
that preserve the pointer and manifest bytes. Strict recorded current-state parsing
also rejects missing phase, duplicate/unknown fields and trailing JSON. Full affected
module verification2141 passes5,280 cases across881 suites, zero failures/errors,
12 skips, in9m27s. All five test tasks execute; app-api236, app-services2,964,
worker-services1,392, worker-core364 and app-engine324. All ten main/test PMD tasks
and six modules' format checks pass. Skips are six unavailable model checks, one
external crash-corpus/model fixture, two filesystem/privilege checks and three
previously deferred composition guardrails. The new recorded symlink case remains
an explicit local platform gap until hosted Linux execution. Logs, copied XML,
counts, exact changed-source hashes and skip inventory are tmp/2141-recorded-generation-full*.
Store-recoverability, generated docs/skills and canonical links pass. The batch proves
the exact-start API capability; installed bulk consumer/restart proof remains open.
Commit preflight2143 classified three synthetic UUID literals as generic API keys.
Root replaced only those test constants with the existing clearly synthetic canonical
UUIDv7 fixture; the unchanged secret scanner passes. Focused2144 reruns all three
affected classes and their format checks successfully. Full2141 still proves the
unchanged production sources;2144 carries the exact final fixture revision.
Queue checkpoint3caf90cbc's hosted CI35563199072 completed all jobs successfully;
tmp/2138-queue-hosted-snapshot.json records the revision and job verdicts. That run
does not prove the current uncommitted generation changes.

Read-only sequence3 exploration confirms the narrow target snapshot owner:
SsotCommitMetadataSource.fingerprintInputs() assembles the same inputs used by
commit metadata, boot compatibility and Green verification. KnowledgeServer installs
Worker model digest and effective-dimension providers before those reads. Add the
read-only snapshot through this authority; do not recreate its input extraction in
the application. Indeterminate configured-model fingerprints must refuse preparation.
The existing captureServingGeneration API returns only the idle active ID and cannot
stand in for this snapshot.

ReindexHandler.prepare and OperationHandlerRegistrations already capture normalized
roots, exclusions and serving generation. OperationExecutorImpl.planInvocation and
PreparedInvocationCodec bind and save their preparation before acceptance.
RecordedIngestPlanResolver validates the accepted context/provenance/descriptor;
OperationAuthority re-resolves grants, policy scope, gate and dependencies. Extend
these existing owners for bulk/rebuild profiles. Route their operation references in
RecordedIngestionCoordinator's one REINDEX recovery pass. Existing operations columns
already hold target generation/settings, gaps and processing history, but their typed
read/write ports do not exist yet. Add runner-owned metadata access there, preserving
terminal immutability and keeping queue and operations transactions disjoint.

### Sequence3 implementation contract at29071724c

Generation checkpoint29071724c is committed and pushed; final fixture2144 has19
cases with no failures/errors/skips. Preparation will call a narrow captureIndexTarget
read through the existing in-JVM IngestServiceCalls path. Worker obtains the digest
and exact canonical input bytes from one existing public SsotCommitMetadataSource.build()
result. This reuses the existing assembly without adding another fingerprint factory.
The application carries an opaque IndexTargetSnapshot and verifies digest/byte binding;
it does not extract or reinterpret model/field configuration. Indeterminate Worker
output refuses. Serving generation capture remains its separate strict observation.

BulkReindexProgress is a typed projection into the already-declared operations
columns, not a new journal: target generation plus IndexTargetSnapshot, the C2 phase,
optional captured manifest/count, and optional immutable settlement. Capturing has
no capture/settlement; building requires capture; settled requires both. Settlement
contains revision/hash, uncapped failed/superseded event counts, full stable-unit gaps
and a maximum200 processing-event sample. The queue remains the evidence authority;
the Engine explicitly maps its immutable settlement to this operation projection.

The runner validates its issued asynchronous handle before checkpointBulkReindex;
the store atomically writes phase, target/settings, gaps/history/counts and terminal-
effect counters under its existing writer transaction. The target must equal g-<row key>,
the row must be RUNNING/DURABLE REINDEX from a designated bulk/rebuild operation,
and existing target/settings/capture/settlement are immutable once bound. Phases move
capturing -> building -> settled without regression; exact repeats are idempotent.
No arbitrary metadata writer or generic callback under a store lock is introduced.
Recovery reads the typed projection separately from ordinary OperationRecord so
unrelated row consumers do not acquire large gap/history payloads.

### Authorization conflict discovered while connecting the real consumer

The existing HIGH-risk bulk/rebuild catalog policies require one-time capsule
confirmation. DurableGrantStore deliberately refuses HIGH-risk allow-always grants;
OperationAuthority deliberately refuses EphemeralCapsule recovery after restart.
Blindly applying the streaming-ingest recovery policy would therefore fail every
ordinary approved bulk operation at its required first restart. Lowering its risk,
permitting HIGH-risk blanket grants, or silently accepting old capsule markers would
weaken the trust boundary and is not selected.

Root is evaluating a separate, narrowly scoped server-built acceptance basis for
the one prepared bulk operation: only after valid prepared capsule consumption,
bind the approval to that operation key and preparation nonce in the existing
grant_ref representation. Recovery must validate the same accepted envelope,
operation profile and frozen arguments, current gate/scope, target fingerprint and
generation evidence. It cannot authorize another key, nonce, changed plan, general
grant, or terminal attempt. Keep ordinary EphemeralCapsule refusal unchanged.
This would intentionally supersede the earlier blanket restart-refusal rule only
for explicitly declared restart-spanning bulk/rebuild profiles; it needs an
independent security review and adversarial regression coverage before adoption.
No authorization production code has been changed for this candidate yet.

Independent reviewer accepted the narrowly scoped PreparedContinuation design with
strict conditions. Adopt it only for the closed HIGH-risk, Inline-confirmed,
DURABLE REINDEX bulk/rebuild profiles and recorded-bulk-reindex-v1 preparation,
minted after successful prepared capsule consumption. The basis binds canonical
operation key and preparation nonce; accepted envelope remains the plan authority.
Recovery permits only ACCEPTED/RUNNING, validates the exact envelope and current
catalog/source tier/scope/gate, and checks physical target/generation witnesses at
the relevant lifecycle stage. Terminal rows and unrelated ingestion/settings
recovery refuse this basis. Approval preview explicitly names continuation across
restarts until completion or cancellation. Required adversarial tests cover copied
markers, changed key/nonce/profile/schema/arguments, forged caller grant headers,
invalid capsules, changed authority and cancellation races. No HIGH-risk durable
grant or general accepted-row authorization is introduced. This decision supersedes
the candidate status above; implementation and runnable proof remain outstanding.

Hosted2145 confirms CI35565311927 at29071724c is successful. Downloaded Linux
search-worker artifact10623677211 contains the actual RecordedGenerationStartTest
XML:18 tests, zero failures/errors/skips; symlinkedOwnershipMetadataIsNotAnAdoptableOrphan
executes and passes. This closes the local Windows privilege gap for that checkpoint.
Accessible raw XML, run snapshot and concise evidence are tmp/2145-generation-hosted/.

### Sequence3 preparation and metadata proof in progress

Focused2149 passes96 cases across18 suites, no failures/errors/skips. Production
compiled through the actual Engine projection. Initial2147 found three invalid
Mockito mocks of the sealed LuceneRuntime interface; the corrected fixtures use
its permitted concrete RunningRuntime, as nearby tests do.2148 found only an
existing decoder refusal-message contract changed by extraction; the shared helper
now preserves it. No assertion or guard was weakened.

Negative2152 restores all three production files byte-for-byte and fails the three
intended predicates: preparation across a changed generation, a foreign runner's
handle, and absent event counts defaulted to zero.2150/2151 removed only one of
Jackson's two relevant safeguards and the count test correctly remained green;
2152 disables both missing-creator and primitive-null refusal for that mutation.
This establishes the reason for that earlier negative-control pass. All logs/XML
are retained; the full affected-module/PMD/format run is2153. Its catalog wire golden
needs regeneration for the deliberate two-profile source/argument schema change;
the conformance assertion itself remains unchanged. End-to-end bulk recovery,
continuation authority and installed fault proof remain open.

Full2153 executes5,538 cases/893 suites with one failure (the intentional catalog
wire change), zero errors and five existing skips; all ten PMD and five format
checks pass.2154 recaptures the golden through its existing capture mode and
intentionally fails once; inspected diff changes only the two input schemas.
2155 then passes31 cases/seven suites without skips. Independent review identified
the braked read-only recovery precondition and missing catalog policy assertions;
root accepted both. The read-only witness/target correction and real brake tests
are underway and are newer than2153/2155. The metadata/codec proof remains valid;
no full green is claimed for the corrected preparation until its checks run.

The read-only recovery correction now has focused proof: 2158 covers 97 cases in
14 suites, with no failures, errors or skips. The Engine suite executes afresh;
the three unchanged selected suites reuse the successful tests from 2157. The
real Engine fixture enters BLOCKED_REBUILD_BRAKE, rejects ordinary writer-based
generation capture, and prepares the recovery profile against read-only Blue
without changing generation state, queue depth or migration counters. Both
catalog profiles are asserted to be durable REINDEX operations. The initial
2157 run failed only to compile the new fixture's int queue-depth variable;
using the API's long type fixes that error without changing an assertion.
Logs, copied XML, counts and the 33-file source inventory are retained under
tmp/2158-bulk-recovery-focused*. This is composed proof with 2153 and 2155,
not a claim that a fresh full suite ran after the correction.

Static run 2159 passes seven affected PMD checks and six format checks. The
engine-port gate passes in 2160, as do store-recoverability, llmstxt and generated
skill checks. The preparation/storage checkpoint is ready; the end-to-end bulk
consumer and authorization work remain required before claiming bulk completion.

### Boot and promotion ownership decision

Read-only investigation confirms that native migration enumeration and cutover
start before recorded lifecycle attachment. Compatibility handling can also
abandon an existing Green. Therefore the existing RecordedIngestionLifecycle
port needs an early immutable boot decision after strict generation state and
effective fingerprint providers are available, before Green opens or any
automatic replacement/enumeration starts. Exact recorded targets retain their
owner and suppress mutable-root enumeration; a recorded-looking target with
missing or conflicting authority is fenced without replacement. Ordinary
automatic migrations retain their current behavior. Read-only Blue remains
available while a recorded target is fenced.

Immediately before promotion, the existing migration loop must obtain the sealed
queue receipt, leave queue locking, and ask the Engine-owned runner to persist
SETTLED. Promotion waits if this checkpoint is temporarily unavailable; permanent
authority refusal terminates the operation and prevents promotion. No callback
may read operations or jobs from the queue's claim-permission lock. ACK remains
after durable terminalization. These changes belong to the single existing
REINDEX reconciliation owner, not a second reconciler or journal. This decision
is source-reviewed but not yet implemented or verified.

The integration must also account for an earlier boot boundary:
IndexGenerationManager.initializeOrLoad calls loadStateBestEffort, which can
restore state.json.prev or allocate after invalid current state. A pending
recorded bulk target cannot acquire its authority through that fallback before
the later boot decision. Establish strict current-state handling for pending
recorded work before that initialization path, while preserving ordinary native
bootstrap behavior. Add a corruption/previous-state regression at the actual
boot boundary; a strict check only after initialization would pass for the wrong
reason because initialization has already rewritten the evidence.

Preparation/storage checkpoint 65afa81fc is pushed; hosted CI 35569918097 is
running. The next uncommitted authorization batch adds PreparedContinuation in
the existing grant_ref encoding, reusing OperationKeys for canonical UUIDv7
validation. Minting is confined to successful prepared capsule consumption and
the closed bulk profile policy. Accepted plan resolution binds its key and nonce;
OperationAuthority's bulk verdict rechecks active operation state, catalog,
executor, transport-derived tier, current gate and root scope. Ordinary ingestion
refuses the new basis in both fresh and recovery policy. The bulk verdict is only
current authorization: the Engine must still prove physical target, runtime and
queue readiness, and observe cancellation before every effect. Tests and security
review are in progress; no execution proof is claimed for this batch yet.

Authorization focused run 2165 executes 92 cases in 16 suites with no failures,
errors or skips, including WholeProgramDeadCodeTest. Independent review found a
producer-confusion defect in the initial eligibility predicate: the public core
operation name and policy shape alone could allow a different handler binding.
The predicate now requires the exact core handler ID and core provenance;
lookalike binding and trusted-plugin regressions pass. Root also corrected a
wrong-reason dispatch fixture: wrong-key and wrong-nonce capsules are minted
after the expiry-clock advance, so only the dedicated expired token is expired.
Same-binding consumption is tested separately from cross-key replay refusal.

Negative run 2166 removes three guards and fails exactly the three intended
tests: alternate producer binding, copied continuation key, and borrowing bulk
continuation for fresh ordinary ingestion. All production files are restored
byte-for-byte. Full affected API/services tests and static checks run as 2168.

Hosted preparation CI 35569918097 exposed two checkpoint gaps: missing operation
surface registration for RecordedBulkPlanResolver, and that resolver being
unreferenced before its consumer existed. The current authority calls the
resolver; local 2165 proves the dead-code gate passes without a baseline change.
The resolver is now registered as a consumer of the existing operation row,
owning no lifecycle or admission authority; operation-surface gate 2167 passes.
Raw hosted logs and actual failure XML are retained under tmp/2162-*.

Full 2168 executes 3,240 tests in 489 suites: one cleanup failure, no errors and
three existing skips. SettingsResetProducerTest's assertions completed, then
JUnit's temporary-directory cleanup failed with Windows "Insufficient system
resources." Root traced this to the lane's orphaned 2145 PowerShell XML collector
(PID 13336, parent Codex PID 13036, exact artifact-parser command), which held
47,336 MiB private memory; Windows had only 5 MiB available. After verifying its
identity, root stopped that owned evidence process. Available physical memory
recovered to about 26 GiB. No test or production behavior was changed for this
failure. Four redundant test qualifiers introduced by new imports also failed
PMD and are corrected. Full 2170 reruns after the resource cause is removed;
2168 XML remains preserved separately. The collector and isolated-negative-test
lessons are added to the context-reset working contract.

Final 2170 passes 3,240 cases in 489 suites, zero failures/errors and three
existing skips. App-services executes afresh; unchanged app-api results reuse
2168. All four PMD and both format checks pass. The source inventory records
13 Java files and preserves the skip details; docs regeneration, canonical links
and operation-surface checks pass. This closes the bounded continuation policy
checkpoint, not the still-required bulk capture, boot and promotion consumer.
