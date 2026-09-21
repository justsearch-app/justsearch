# C2-10 bulk reindex connection

2026-09-21 design settled against `0a23b0a4f`. Implementation remains open. The installed
INGEST/SETTINGS fault proof does not discharge it. Current source and the owning
operations-store design agree on the gap; no later amendment transfers C2's row,
plan, resume or processing-history/gaps obligations to D1.

## Scope and existing owners

`core.reindex` now uses the C2-8d streaming recorded-root path. Its both-store
reopen test preserves parent/child identity and advances the stored operation.
Do not restore the old full document/hash pre-walk on that path. `core.bulk-reindex`
is different: CoreOperationCatalog.bulkReindex has no durable record kind, and
BulkReindexHandler returns after startMigration and a dispatch lease. The default
recorded adapter would terminalize that response immediately. C2-10 still requires
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
| app-services `CoreOperationCatalog.bulkReindex`, `BulkReindexHandler` | Ordinary dispatch, short lease, immediate started response; needs prepared/durable owner connection |
| app-engine `EngineKnowledgeClient.startMigration` | Requests restart on accepted + restartRequired, before asynchronous rebuild |
| app-services `worker/MigrationOps`, app-api `IndexingService.MigrationOutcome` | Drops the building generation id from the start response |
| worker-services `services/MigrationControlOps` | Start response already exposes building generation id |
| worker-core `index/IndexGenerationManager` | Persists active/building/previous and lifecycle; start creates Green/MIGRATING; promotion sets active=Green, clears building and returns IDLE |
| indexer-worker `server/ops/KnowledgeServerMigrationOps` | Promotion precedes a second restart request; boot enumeration reloads current roots and streams path/size, not an acceptance-frozen hash plan |
| app-observability `operations/OperationSchema` | Existing building_generation_id, target_settings_json, gaps_json and processing_history columns |
| app-api `operations/OperationRecord`, `OperationStore` | Do not yet expose typed bulk metadata or writes; generic checkpoint/finish cannot truthfully imply that connection |
| app-engine `RecordedIngestionCoordinator.pump` | Shared REINDEX pass must preserve root-plan ownership and route bulk explicitly |

The building id must be durable before the first restart; successor comparison must
use active generation after promotion because building generation is cleared.
Two actual restart boundaries matter: migration start and post-promotion. Existing
BulkReindexHandlerTest, IndexGenerationManagerRestartTest and
EngineMigrationLifecycleTest are component evidence, not the bulk operation proof.

## Preparation size and source-revision constraints

The full per-file plan cannot be an unbounded prepared JSON value: the existing
envelope limit is524,288 bytes and stored payload limit750,000 bytes. Arbitrarily
refusing larger corpora solely to fit that representation is not the selected scope.
The serving index alone is insufficient: content_sha256 hashes extracted stored
content, not source-file bytes, and excludes unindexed/new files under captured roots.
Migration currently enqueues path/size only, without operation membership. The closest
existing durable substrate is jobs/recorded-walk/ingestion-ledger membership, but its
content hash is currently written at terminal effect, not frozen at plan capture.
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
