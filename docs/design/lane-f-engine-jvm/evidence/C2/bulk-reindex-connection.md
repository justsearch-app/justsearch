# C2-10 bulk reindex connection

2026-09-21 current state: the prepared bulk/rebuild consumer and manual REST alias
are connected in pushed `0f1bec7e3`; cooperative shutdown/reload correction is
pushed `9598c7e15`. Real Engine two-restart, controlled refusal/cancellation,
MAX-attempt, ACK paging and negative guard proofs pass. Installed requested-restart
migration2201 and corrected shutdown2219 pass. Hosted35583668414 instead exposes a
setup-walk ownership collision escaping the producer future to the fatal handler;
that separate correction and the three installed crash cuts are in progress.
Final integrated stress and corrected hosted proof remain open. Earlier entries
below are historical evidence, not the current execution queue; the
[handoff](../../handoff.md) owns current next actions. Installed INGEST/SETTINGS
proof does not discharge bulk acceptance. No amendment transfers C2's row, plan,
resume or processing-history/gaps obligations to D1.

## Installed bulk crash cuts: selected ownership and proof

Use the existing runner fault hook and selected UI OperationFaultBarrier; no new
journal, marker format, scheduler or authority channel. The runner validates its
live REINDEX capability before observing the two physical boundaries. Its existing
checkpoint method owns the third boundary immediately before BUILDING persistence.
Normal composition uses the exact no-op hook. Only the installed supervisor harness
can select the closed REINDEX fault phases.

For partial capture, reuse committed keyed queue notifications rather than widen
the capture producer API. SqliteJobQueue delivers them synchronously after its
outermost unlock; the captured scanner cannot finish its current root until
`enqueueRecordedEntries` returns. The coordinator observes the live attachment,
unfinished producer future and not-yet-started generation. Initial begin-walk
notification has no producer future and cannot stop the test. The cooldown database
snapshot must prove first-member durability and second-root absence; event timing
alone is not proof. This avoids another callback threaded through scan contracts.

| Cut | Authoritative state after identity-verified death, before successor | Required recovery |
| --- | --- | --- |
| `bulk-partial-capture` | RUNNING/CAPTURING, open capture epoch, first root member/H1 retained, second absent, no Green | Same accepted preparation/key; re-enumerate remaining scope and finish exact target. One counted crash plus two requested restarts, final incarnation4. |
| `bulk-before-building-checkpoint` | RUNNING/CAPTURING, COMPLETE closed manifest, exact g-key generation already MIGRATING | Bind existing Green without another allocation. One counted crash plus one requested promotion restart, final incarnation3. |
| `bulk-after-promotion` | RUNNING/SETTLED, exact target active/IDLE, sealed but unacknowledged queue | Actual target successor boot before SUCCESS and ACK. First requested restart plus one counted crash, final incarnation3. |

All three require one operation identity/preparation, exact g-key, actual target
search for both captured members, zero failed units, sealed exact acknowledgement,
and unchanged same-key retry. Restart accounting must recognize that a crash can
supply the replacement otherwise requested by the current process. The selected
harness disables automatic root producers using the existing isolation hook; real
embedding remains enabled when its model is locally resolvable. Windows-only
identity verification is explicit in installed test selection. Implementation and
syntax checks are not executed proof; successful runs are still owed.

The preceding requested-restart scenario keeps its actual watcher. Its Blue-only
setup uses an explicitly approved out-of-root document. It must wait for recorded
setup owners to seal/ACK before bulk capture: search visibility alone is not that
barrier. Producer futures retain nonfatal task/cleanup failures, while Error remains
fatal; no queue collision check is removed and no failed operation becomes success.

### Empty-generation crash recovery and producer failure ownership

Installed2225 reached the exact partial-capture cut and killed its admitted Engine,
but successor incarnation2 could not reopen native Blue read-only: the fresh writer
had never committed a Lucene index. The run is failed evidence, not a timing waiver.
The fix belongs in ComponentsFactory's writable bootstrap: after acquiring the
writer and consuming the clean marker, commit a neutral empty index only if none
exists, before publishing readers. Seeding the fixture would hide the valid empty
source case. A capture-port commit would add cross-module policy and could sweep
unrelated writes; creating an index during strict read-only open would weaken the
recorded ownership fence. Existing zero-doc parity rules permit this bare structural
commit; the first real CommitOps commit owns model/build metadata.

Focused2226 passes27 adapter cases and PMD; negative2227 removes only the initial
commit and fails the live durable-index assertion, then restores original bytes.
Full adapter2228 passes729 cases/102 suites with no failures/errors/skips. The
regression also adds an uncommitted document, rolls the writer back and reopens
strictly read-only with zero documents. Missing-index read-only refusal remains.

Hosted35583668414's separate setup collision escaped OwnedStreamTask's exceptional
future into the fatal process handler. Ordinary producer/cleanup exceptions now
remain with that future; Error still escapes. Review caught a later cleanup Error
being suppressed beneath an earlier RuntimeException: failure combination now
promotes that Error and retains the earlier exception as suppressed. Focused2229
passes11 producer cases plus PMD/format; negative2230 disables only Error promotion
and fails the exact fatal-cause assertion, then restores bytes. Restored2231 reuses
2229's matching test result and rebuilds the installed distribution. Full Engine
and corrected hosted integration proof remain required.

Raw commands/output and copied XML/counts are retained under `tmp/2225*` through
`tmp/2231*`, with Java/proto source inventory in `tmp/2231-bulk-fixes-restored-build-sources.json`.
Installed fault runs now save `bulk-cut.json` before successor assertions and
`bulk-final.json`/`bulk-after-retry.json` before their respective assertions; these
preserve predecessor evidence that the live databases later replace.

Installed2232 passes the former empty-index failure but remains red. Incarnation2
re-enumerates the open captured walk and starts exact Green at engine.log:2034.
Its operations row terminalizes `BULK_GENERATION_REFUSED` 62ms after state creation,
before its asynchronous shutdown: the process reuses its initial CAPTURING boot
witness after writing MIGRATING and sees FENCED. Both captured members are wrongly
retired as RECOVERY_REFUSED; incarnation3 correctly honors that durable refusal.
The fix must preserve strict boot capture validation and instead use the existing
accepted local start/restart handoff state until the physical attachment changes.
Cancellation and authority refusal still precede waiting for replacement. A failed
restart callback may retry; a failed durable checkpoint invalidates its runner
capability and must remain unresolved rather than becoming generation refusal.
Run `9de2983c-9234-41c3-aad4-98d2cdba6487`, key
`01a0c389-b585-792b-a0dc-6131615856ac`; output and complete retained runtime are under
`tmp/2232-installed-bulk-partial*`. Owned STOP reports portsClosed:true, and subsequent
quick_health is ABSENT without foreign runs or inference orphan. The driver now
fails promptly and saves `bulk-failed.json` on terminal failure during successor
waiting; the earlier timeout remains retained as failed evidence.

Full2233 executes2241 cases/347 suites: Engine343, observability600 and UI1298,
zero failures/errors, one existing optional McpEntityCarriageMetric dataset skip.
All six PMD tasks and installed-test compilation pass. Full XML/counts/source and
skip inventories are retained under `tmp/2233-bulk-fault-full*`. Governance2234
passes engine-port (one informational finding), operation-surface and store
recoverability (six stores,46 authorities,27 policies). Hosted35586999590 at9598
again reports aggregate success while migration fails all three integration retries;
`tmp/2235-hosted-checkpoint-failed.txt` retains the job output. This checkpoint
is verified infrastructure and two corrected defects, not completed bulk recovery.

### Accepted-start handoff correction and installed proof

Checkpoint `cfd83ca07` preserves the preceding verified batch and failed2232.
The correction stays in RecordedIngestionCoordinator: after cancellation and
authority refusal checks, existing started/restartRequested flags represent an
accepted local start awaiting physical replacement. Do not reinterpret the old
CAPTURING boot witness as current generation authority. Prearm the restart flag
before BUILDING persistence; a thrown checkpoint has invalidated its runner handle
and must remain unresolved. After a successful checkpoint, dispatch the restart
at the maintenance-call epilogue, once rather than once per internal progress pass.
A failed callback resets only dispatch state for the next maintenance call; it does
not repeat physical migration or checkpoint through the accepted runner handle.
Attachment replacement resets both flags and re-enters strict boot/runtime checks.
No boot fence, durable state representation or recovery authority is weakened.

Installed build2237 has stamp `21fe2a307f658204`; its source inventory is
`tmp/2237-bulk-handoff-installed-sources.json`. The three direct Windows scenarios
all pass against that distribution, with real embedding and identity-verified kill:

| Run | Key | Final physical incarnation / attempts | Sealed and ACK revision |
| --- | --- | --- | --- |
| 2238 partial capture | `01a0c397-23d7-75a7-bdc7-b00ced74de2d` | 4 / 3 | 9 |
| 2239 state before binding | `01a0c398-1860-7c8d-b73c-c88831eec037` | 3 / 2 | 7 |
| 2240 promotion before terminal | `01a0c398-f2ae-7285-87cc-2c546a03ba21` | 3 / 2 | 7 |

Each passes original row/preparation identity, exact target, both searchable members,
zero failed units and unchanged retry row/queue. Each spends exactly one crash
restart; requested restarts remain free. Partial capture retains the original first
member revision/H1 while extending enumeration from epoch1 to2. Before-binding
retains its closed manifest; after-promotion retains its sealed settlement. Raw
output/runtime and cut/final/retry JSON are under `tmp/2238-installed-bulk-partial*`,
`tmp/2239-installed-bulk-before-binding*`, `tmp/2240-installed-bulk-after-promotion*`;
compact extracted summaries are adjacent. All report owned STOP/portsClosed:true,
with no foreign/orphan process at subsequent quick_health. These are direct driver
executions. JUnit matrix2247 subsequently executes11 cases: all three bulk cuts
and seven other cases pass; ingest-before-accept fails through the deferred-service
race below. Its wrapper explicitly checks BULK_FAULT_PASS and all three retained
snapshot files for bulk cases.

Corrected migration2241 also passes actual prepared REST dispatch, both promotion
restarts, target search, SUCCESS with one unit/zero failed, revision6 sealed/ACK and
rollback. Key `01a0c399-fa6e-76c4-8653-d7ce3a80958e`, run
`61dd5a5f-eb02-4d6a-b457-8562974c97a9`; evidence `tmp/2241-installed-migration-corrected*`.
This validates the setup-owner wait locally; corrected hosted proof remains required.

Live2243-2246 reopens the retained onramp corpus with the same installed distribution.
Health reports Head/Worker/inference READY. jseval tier2-eval2245 asks the existing
one-question fixture and standard Qwen_Qwen3.5-9B-Q4_K_M.gguf answers Captain Mortimer
Flux, zero errors/exact accuracy1.0. This is functionality, not a quality benchmark.
Commands: `python -m jseval --json tier2-eval --queries ../../tmp/1925-live-query.json
--base-url http://127.0.0.1:58136 --llm-url http://127.0.0.1:8082 --max-queries 1
--output-dir ../../tmp/2245-model-query` from scripts/jseval. Start/health/stop JSON
and model output are retained2243-2246; owned stop closes ports. The first MCP start
2242 duplicated the worktree prefix in an absolute dataDir and was stopped before
querying; worktree-relative dataDir corrected it. The main-checkout MCP preflight
still probes the retired Worker distribution, while the selected lane's dev runner
successfully starts its sole Engine; do not claim that obsolete preflight passed.

### Deferred-service publication defect and correction

Installed matrix2247 exposes a valid successor race, not a bad fault cut. Before
acceptance the original process has no operation/jobs/ledger. Its successor durably
accepts parent `01a0c39f-32d8-7596-9682-ad36a54c22d0` and child
`01a0c39f-7676-73fc-aa07-054648061de1`, then both fail INGEST_ENUMERATION_FAILED
before traversal. Runtime `tmp/lane-f-takeover/writer-junit-809eab78-655e-4ae0-9e41-98fc33fbf54a`
retains engine.log: reconstruction starts at1991, API readiness precedes producer
failure at2062, and the replacement loop starts only at2069. Copied logs/XML/counts
are `tmp/2247-installed-junit-matrix*`.

DeferredRuntime upgrade publishes RunningRuntime while old deferred-backed
appServices remains visible during incumbent close. Recorded generation observation
combined new runtime globals with that old service, so EngineKnowledgeClient resolved
an immutable service lacking a serving writer. Empty bootstrap durability made this
existing-index boot path reproducible; bypassing deferred mode for empty indexes
would leave the same populated-index race.

KnowledgeServer now requires the currently published WorkerAppServices to report its
started writer loop before returning a serving-generation witness. This is a typed
projection of existing loop ownership, not another lifecycle state machine. Read-only
worker availability remains independent because bulk CAPTURING requires it. After
initial service startup or replacement publication, the current attachment receives
servicesPublished and re-drives coordinator maintenance. An accepted child waits
without beginning its physical walk, then resumes the same child/attempt on publication.
Stale/stopping attachment notifications are ignored. No producer replay or extra
retry timer is introduced. Independent review also found the existing running-runtime
swap and development reload publication paths. The existing runtimeSwapLock fences
serving-generation observations during drain/reconstruction; successful swap notifies
after unlock. RunningRuntime projects its existing draining/closing state so failed
drain or failed replacement cannot become writable merely because the lock releases.
This avoids a duplicate transition flag and preserves failed owners for cleanup.
Development reload notifies after its replacement loop starts. Generation remains
an observation, not a newly invented lease across future runtime changes.

Focused2252 passes11 bulk cases/2 suites, including callback failure under an old
FENCED witness and H1/H2 supersession retained in persisted settlement without a
current gap. New publication batch2253 executes88 cases with two test-fixture failures:
a parent already creates its durable child before physical readiness (the assertion
incorrectly expected no child), and a shallow replacement mock failed wiring before
the intended blocked close. Both fixtures were corrected to preserve the required
same-child/no-walk and publication-order assertions.2254 passes88 cases/6 suites
and six PMD tasks. Review then identified running-swap/reload publication gaps,
corrected as above.2256 passes101 cases/9 suites with zero skips/failures and eight
PMD tasks.2257 removes six guards independently; every negative fails its intended
assertion (old bulk witness terminalizes, stale service grants generation, missing
publication notification, missing owner maintenance, swap grant during drain, failed
drain grant after unlock). Sources restore byte-exact in finally; outputs/XML and
summary are `tmp/2257-negative-*`.

Restored2258 represents the identical101 passing cases (Engine/indexer FROM-CACHE,
adapter UP-TO-DATE from2256), builds the installed distribution with stamp
`e98118e565edd1b9`, compiles installed tests and passes formatting. Source inventory
is `tmp/2258-publication-restored-install-sources.json`; test artifacts are adjacent.
Governance2259 passes engine-port (one informational finding), operation-surface and
store recoverability (6 catalog stores/46 authorities/27 policies). Agent projection
and canonical link/index/skill checks pass. Full installed matrix, full stress/dead-code
and corrected hosted test-level success remain required; these focused results do
not close C2. The independent final reread matches all14 current source hashes to2256
and2258, confirms the six intended negative assertions, and reports no remaining
actionable defect within the reviewed publication/restart scope. Installed matrix2260
is running against2258.

## Scope and existing owners

`core.reindex` now uses the C2-8d streaming recorded-root path. Its both-store
reopen test preserves parent/child identity and advances the stored operation.
Do not restore the old full document/hash pre-walk on that path. `core.bulk-reindex`
is different: its former immediate startMigration response and dispatch lease have
been replaced by a shared prepared bulk/rebuild handler, REINDEX/DURABLE catalog
profiles and the RecordedIngestionCoordinator bulk consumer. C2-10 requires
the bulk row, frozen plan, resume and bounded history/full gaps; D1 owns journal,
replay, live activation and gap refusal.

The existing OperationAttemptRunner remains the sole attempt/terminal writer;
SqliteOperationStore remains the durable owner. EngineRoot composes the application
and index owners. Do not add another execution journal or terminal writer.
RecordedIngestionCoordinator routes bulk by its closed operation references within
the shared REINDEX recovery pass; streaming root plans retain their resolver.
A second competing REINDEX reconciler is incorrect.

## Exact source map for continuation

| Owner | Current behavior / required seam |
| --- | --- |
| app-services `CoreOperationCatalog.bulkReindex`, `BulkReindexHandler` | Freezes bulk/rebuild scope and target before approval; dispatches to the shared durable consumer |
| app-engine `EngineKnowledgeClient.startMigration` | Requests restart on accepted + restartRequired, before asynchronous rebuild |
| app-services `worker/MigrationOps`, app-api `IndexingService.MigrationOutcome` | Pushed29071724c preserves active/building/state witnesses and provides deferred recorded start |
| worker-services `services/MigrationControlOps` | Start response already exposes building generation id |
| worker-core `index/IndexGenerationManager` | Persists active/building/previous and lifecycle; start creates Green/MIGRATING; promotion sets active=Green, clears building and returns IDLE |
| indexer-worker `server/ops/KnowledgeServerMigrationOps` | Recorded cutover invokes the owner settlement/promotion barrier; native migration retains current-root enumeration |
| app-observability `operations/OperationSchema` | Existing building_generation_id, target_settings_json, gaps_json and processing_history columns |
| app-api `operations/OperationStore`, `OperationAttemptRunner` | Sequence3 adds typed immutable bulk projection and issued-handle checkpoint; ordinary row reads avoid large evidence payloads |
| app-engine `RecordedIngestionCoordinator.pump` | One REINDEX pass dispatches bulk explicitly and preserves root-plan ownership |

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
The application consumer connects that substrate to actual capture and migration.
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
available while a recorded target is fenced, provided strict current state proves
Blue's identity. If current state is missing or corrupt, pending/fenced recorded
startup fails without restoring or reading authority from .prev.

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

Authorization checkpoint d69763186 is pushed; hosted CI 35572035546 is running.
The next uncommitted traversal batch adds STREAMING/CAPTURED to the existing
Java-only recorded scan projection. Captured mode hashes each eligible source
with the existing SourceContentHash routine (narrowly exposed), keeps 2,000-entry
batches, bypasses both depth waits because claims are withheld, and refuses cloud
placeholders before ledger admission. It uses the strict read-only active witness;
ordinary streaming still requires the writer. EngineKnowledgeClient sequences
frozen roots under one retained admission owner and one caller-supplied epoch,
waiting for each actual walk and progress-delivery exit. It stops after failure
or cancellation; the outer consumer remains responsible for closing the epoch.
Captured traversal verification is complete for this bounded checkpoint. Focused
2174 represents 76 cases in six suites with no failures, errors or skips: 22 Engine
cases execute and 54 unchanged scanner/admission cases reuse 2173. Full 2175
executes 2,092 cases in 363 worker-services/indexer-worker suites with zero failures
or errors and 17 existing model/filesystem skips. All five affected PMD and three
format checks pass. Independent read-only review reports no actionable findings.
Negative 2177 executes five cases and fails exactly the two intended guards:
restoring queue-depth waiting prevents capture completion; releasing the retained
owner early refuses the second root with WORK_FINISHED. Both production files are
restored byte-for-byte. Logs, copied XML, source inventories and skip details live
under tmp/2173-*, tmp/2174-*, tmp/2175-* and tmp/2177-*. Full Engine integration is
still owed after the consumer is connected; this evidence does not claim it.

Hosted continuation CI 35572035546 passes all jobs except platform-contracts,
which fails during Gradle configuration on Maven Central HTTP 429 for Kotlin
2.3.21 dependencies. No platform tests execute; WholeProgramDeadCodeTest hosted
closure remains unproven. The failed job is retried after its logs are preserved.

The reviewed boot design uses one early immutable ownership request, observed
before IndexGenerationManager initialization, then reused for the fingerprint and
Green fence. Pending/fenced recorded work requires strict current-state loading
without normalization writes or fallback. Native bootstrap remains available
without pending recorded work; unowned recorded building/fallback evidence is
fenced. A valid current IDLE active recorded generation is not permanently fenced
merely because its completed, ACKed operation row has expired. This distinction
preserves normal startup after successful bulk work without authorizing a pending
target from fallback state.


### Strict boot projection contract (2026-09-21)

IndexGenerationManager owns the ephemeral BootOwnership projection: Native,
Fenced, or Recorded(operationKey, sourceGeneration, source, targetFingerprint,
captureComplete). The application interprets operations and the opened queue once
before manager initialization, then passes this immutable projection. BootLayout
returns layout plus NATIVE/CAPTURING/BUILDING/PROMOTED/FENCED; this is a runtime
observation, not a persisted state machine or second journal. Only BUILDING may
open exact Green, and only after captureComplete and the effective Worker physical
fingerprint match. FENCED/CAPTURING keep strict Blue read-only and suppress native
migration. PROMOTED opens the exact active target but suppresses native replacement
until the operation completes. Native boot retains existing recovery when there is
no unresolved recorded evidence. Unowned recorded building state or recorded
fallback/orphan evidence fences; missing/corrupt current then fails without writes.
A valid IDLE current pointer with no pending owner remains native even when its
active generation is a completed recorded target. Strict recorded loading accepts
only current state format 2; legacy v1 remains supported through native bootstrap.
This avoids changing bytes to manufacture a pending operation's authority.

FENCED always preserves the strict current active serving identity read-only. It
is Blue before promotion; if current state already durably promotes Green, a
missing capture/fingerprint proof must not silently roll back to previous Blue.
It fences writes and successful operation completion on the current active target.


Independent boot review found that ordinary Lucene opens can recover by moving
and replacing even a read-only index. Non-native boot therefore opts out of the
existing runtime recovery wrapper for both serving and writable opens; failures
preserve generation ownership evidence. Native behavior keeps configured recovery.
A second review clarified orphan detection: under a valid current pointer, only
unreferenced pristine recorded directories (no content beyond generation metadata,
including partially written metadata) fence native startup. Completed empty targets
have Lucene commits, and old non-pristine recorded archives can legitimately outlive
both current/previous pointers. Scanning every UUID directory would incorrectly
fence those archives. With damaged current state, any recorded directory/backup
evidence still refuses fallback. This uses existing metadata/file ownership, not
another persistent lifecycle marker.


### Consumer integration worklist after strict boot

Keep bulk ownership subordinate to RecordedIngestionCoordinator's existing lock,
maintenance and single REINDEX reconciliation pass. Route by the closed bulk
operation references before the ordinary root-plan resolver. A separate ephemeral
Bulk value is justified because bulk has one captured walk and two physical runtime
boundaries, while Parent owns sequential derived child operations; forcing bulk into
Parent would manufacture child semantics and duplicate terminal authority.

Bind the current EngineKnowledgeClient's captured producer, indexing control port
and existing requested-restart callback alongside the ordinary producer. A fresh
winning handle retains admission and checkpoints CAPTURING. Captured traversal uses
one queue epoch, completes only after actual producer/delivery exit, then validates
source and target again before exact generation start and BUILDING checkpoint.
Generation-start-before-checkpoint recovery uses exact queue capture plus the
operation-derived target; it must not allocate another target. Queue evidence lost
after an interrupted RUNNING attempt is a refusal, never a fabricated empty capture.

On physical replacement, cancel/drain the old producer but retain the durable
operation owner; its new attachment rechecks policy, source/target and cancellation.
Only an exact writable target runtime with complete matching capture installs bulk
claim permission. The permission callback may read immutable owner/preparation and
strict runtime identity, but never operations/jobs under the queue lock. A live
runtime projection must distinguish FAILED/conflicting target from temporary
unavailability so permanent migration failure cannot leave the operation waiting
forever. A promoted-boot witness is distinct from merely observing state promotion
while the old Blue-serving runtime remains alive.

Immediately before promotion, revoke/drain as needed, seal the queue, read its
immutable captured settlement, leave queue locking, checkpoint SETTLED through the
issued runner handle, and recheck policy/cancellation. The successor verifies exact
active IDLE target, actual writable/serving target binding and matching settlement;
only empty gaps succeed. Nonempty gaps fail PROMOTED_WITH_GAPS pending D1 activation
rules. A terminal row precedes exact ACK. Terminal-before-ACK recovery needs a bounded
queue-owned inventory of unacknowledged captured keys (not only open operation rows
or the public 200-row history), followed by exact retained operation/progress/receipt
comparison outside queue locking. No additional journal or terminal writer is needed.

The manual REST alias uses the normal operation invocation response and approval
flow. Successful accepted invocations return 202; confirmation remains 428, and
shared error handling remains authoritative. Legacy reason is translated before
preparation into normalized source (unknown/missing => manual). Unknown extra fields
remain visible to the closed schema and are rejected. The old restartRequired
response and direct short-lived migration lease are retired; outcome lookup uses
the accepted operation key. This is an intentional contract correction, not a second
compatibility execution path.


The consolidated review also fences active-recorded FAILED/unknown phase with no
building identity, delays recorded cutover until lifecycle attachment plus explicit
owner readiness, and suppresses native startup switch-buffer replay and deferred
legacy embedding rescue on non-native boots. Root refined the active-recorded guard:
a completed recorded generation may later be Blue for an ordinary native migration;
a valid native building identity and migration phase must retain native recovery.
The IDLE-only exception applies when there is no building identity, not to that
later native migration. Dedicated controls cover both shapes.


### Consumer refusal and promotion review decision (2026-09-21)

The connected consumer compiles in 2183; boot/REST 2182 passes 168 cases, and
source-bound start plus REST 2184 passes 135 cases with one existing Windows
symlink-privilege skip. These are component proofs, not completed C2 acceptance.
The real-store/queue coordinator integration and new recovery checkpoints are
under focused verification in 2186.

A refusal observed only in memory is insufficient: queue retirement followed by a
crash could otherwise resume under restored policy and promote a plan already
retired for refusal. The existing typed bulk projection now carries an optional,
first-wins refusalCode in the existing operation row. New evidence serialization
is version 2; strict version 1 reads remain supported without rewriting. No new
journal, database column, authority epoch or independent writer is introduced.
The first refusal may be added within a phase without changing capture/settlement;
it cannot be cleared/replaced and is retained through SETTLED. This is preferable
to a new REFUSING phase because capture/build/settlement identity remains unchanged.
A refusal is orthogonal to those physical progress phases.

Live ownership checkpoints this decision through the issued handle before queue
retirement. Interrupted ownership uses the existing runner reconciliation path's
typed CheckpointBulkAndWait to persist the same evidence on a RUNNING row without
spending a resume attempt. After the durable refusal, pending unissued members can
retire and seal; BUILDING evidence advances to matching SETTLED before terminal
failure/cancellation. Boot and claim/promotion ownership must refuse the persisted
witness even if present-day policy later allows the original preparation.
Contradictory or unavailable evidence is retained and is never ACKed as a refusal.

The target remains fenced after refusal, deliberately preserving the earlier boot
contract; automatic abandonment/deletion is not an acceptable cleanup substitute.
ACK of an exact refused operation does not grant target activation. Successful
terminalization requires post-promotion writable-runtime proof; ACK then compares
the immutable terminal receipt/progress, allowing recovery even after later native
lifecycle changes. The bounded captured ACK inventory advances across retained
contradictions until its current scan reaches the end.

Promotion uses a supplied exact-generation action inside the existing coordinator
lock, after sealed SETTLED checkpoint and final authorization/cancellation checks.
Cancellation records its flag and waits through that same effect boundary; the
admission owner invokes callbacks outside its own lock. Generation promotion also
revalidates operation/source/target under existing STATE_CONTROL, preserving the
native promotion path. Cancellation requests maintenance even when no producer or
issued claim remains, so it cannot depend on an unrelated queue notification.

If cancellation overlaps an already-entered promotion action, the physical effect
linearizes first and cancellation waits for that action to exit. The operation then
records CANCELLED with truthful SETTLED counts and its durable refusal; it does not
roll back an already promoted generation. Cancellation before action entry suppresses
promotion. COMPLETE still requires successor writable-runtime proof without refusal.

The review found no reverse STATE_CONTROL-to-queue acquisition. Per-claim policy
canonicalization and strict runtime observation can delay queue locking, but caching
those facts would weaken current root/gate revocation without an existing complete
invalidation carrier. Preserve present authorization while recording this latency
trade-off; do not manufacture a new policy epoch or silently relax scope checks.


2189 verifies the connected controlled coordinator with real SQLite operation/queue
owners: 71 cases/four suites, zero failures/errors, one existing Windows symlink
privilege skip; both test tasks execute fresh. It includes strict generation promotion
source/target rejection and byte-preserving retry. Earlier 2186 preserves 85 cases/
nine suites, zero runtime failures and the same platform skip; app-engine compile
failed on two new fixture mistakes. 2187/2188 preserve the two follow-on fixture
failures, corrected to supply the actual rebuild-generation port and assert success
coverage separately from the bounded failed/superseded history sample.
Artifacts: `tmp/2186-bulk-focused*`, `tmp/2187-bulk-engine-focused*`,
`tmp/2188-bulk-engine-focused*`, `tmp/2189-bulk-engine-focused*`; copied XML,
counts and source inventories retain exact local scope. Crash/refusal/promotion-race
and actual Engine restart evidence remain required. No negative mutations are active.

Refusal terminalization requests a replacement through the existing restart callback,
once per physical attachment, with callback-failure retry through the existing
HeadAssembly operations maintenance cadence. This releases an obsolete CAPTURING
boot with no target or reboots an unowned refused target into FENCED mode. No new
scheduler is needed: HeadAssembly already binds recordedIngestion::maintain to its
operations timer. D1 still owns synchronous untagged-write/journal fencing and the
activation gap decision; a recorded claim predicate does not govern untagged jobs.


Full storage 2190 executes 1,238 cases in 227 suites with zero failures/errors;
all six PMD tasks pass. Seven existing skips: BgeM3VocabLoadTest (1),
OnnxEmbeddingEncoder late-chunking model tests (5), and the Windows symlink
privilege case in RecordedGenerationStartTest (1). All three test tasks are fresh.
`tmp/2190-bulk-storage-full.txt`, copied XML/counts/source/skip inventories preserve
scope. Full index/runtime 2191 executes 2,823 cases/465 suites with zero
failures/errors and 17 existing skips (AdversarialCorpus 1, SpladeCrash 1,
OnnxEmbedding 12, MigrationEnumeration 3). Its one PMD failure was an unnecessary
ArrayList qualifier; removing it passes indexer-worker pmdMain in 2193. The other
five PMD tasks pass in 2191. This syntax-only correction does not require repeating
the unchanged full runtime suites.
The installed migration scenario has been adapted to the prepared approval/operation
contract and exact terminal/ACK checks; Node syntax checks pass, installed execution
remains owed. Blue-only setup is now an explicitly approved out-of-root document
that remains present, while the preboot watched-root scope contains only A. This
avoids relying on mutable roots seeded after preparation or a deletion/watch race.

2194 caught a checked-close exception in the new real Engine test fixture before
test execution; corrected its AutoCloseable declaration. 2195 then runs seven
cases: the real three-epoch/two-restart Engine test and five crash/cancel cases pass,
while refusal restart retry timing fails. The restart callback ran at the end of
every internal pump pass and retried a failed callback in the same maintain call.
Moving it after the finite progress loop uses the existing maintenance cadence
without another marker or scheduler. 2196 passes 58 cases/four suites, no skips,
failures or errors, including ordinary coordinator and restart-dispatch regressions.
Its pmdMain passes; four test-only redundant qualifiers fail pmdTest, then their
removal passes pmdTest/format in 2197. Copied XML, counts and source inventories:
`tmp/2194-bulk-adversarial*`, `tmp/2195-bulk-adversarial*`,
`tmp/2196-bulk-adversarial*`; PMD log `tmp/2197-bulk-test-pmd.txt`.

The real Engine proof accepts a prepared bulk operation, captures its exact target
and roots, observes BUILDING before first restart and SETTLED before second,
reopens the promoted writer, verifies SUCCESS and actual search from the target,
then reopens SQLite to verify exact sealed revision ACK. Controlled fault proofs
cover durable refusal before queue retirement and after settlement but before
terminal persistence, restored allowing policy, no-issued-claim cancellation,
pre-entry promotion suppression, and cancellation waiting for an entered promotion.
Consumer MAX-attempt/paged-inventory proof and negative guards remain next.

Full service/API 2198 passes 4,285 cases/636 suites, zero failures/errors, four
existing skips (three deferred CompositionRootGuardrails cases and one optional
McpEntityCarriageMetric dataset case). Both test tasks execute fresh and all four
PMD tasks pass. 2200 rebuilds ui:installDist and passes launcher architecture
selection: 38 cases/16 suites including all three UnreferencedCodeTest predicates.
The earlier hosted unreferenced captured producer is now connected and locally green.

Installed 2201 passes the actual prepared manual REST migration, both required
Engine restarts, exact target serving and terminal/ACK verification, followed by
rollback reopening the distinct Blue-only document. Key
`01a0c33a-45cd-71ec-ae51-1d6eba52a0ad`, target `g-` plus that key, COMPLETE/SUCCESS,
one captured/completed unit, zero failures and sealed/ACK revision 6. All three
voluntary exits use code 4 and leave the crash restart count at zero. The fixture
prints MIGRATION_PASS and owned STOP with portsClosed:true; post-run quick_health
is ABSENT with no foreign run/inference orphan. Incarnation 2 engine.log proves
gte-multilingual-base loaded on GPU and produced the captured document vector.
This is live embedding/cutover proof, not a chat-model query or quality benchmark.

Command: `JUSTSEARCH_REAL_RECOVERY_SCENARIO=migration`,
`JUSTSEARCH_WRITER_RECOVERY_WORK=<worktree>/tmp/2201-installed-bulk-migration`,
`node scripts/supervisor-conformance/real-writer-recovery.mjs` after 2200 installDist.
Raw output `tmp/2201-installed-bulk-migration.txt`; runtime/store/queue/stop evidence
and incarnation logs under `tmp/2201-installed-bulk-migration/`, run id
`10a7cbe3-e44b-4e36-a313-8a31d88e9435`. Sources and compiled test evidence are in
`tmp/2200-bulk-installed-build*` and `tmp/2198-bulk-services-ui-full*`.
Installed kill cuts remain required; successful requested restarts do not prove them.

Full Engine 2203 executes 340 cases/59 suites, one failure, no errors/skips;
both PMD tasks pass (pmdMain reused 2196). The new MAX-attempt and 257-entry ACK
inventory tests pass. The sole failure is existing EngineForegroundPacingTest
teardown: its assertions pass, then the Lucene write-lock channel is invalid at
writer close, and the owner correctly retains the incomplete close. This is not
a startup failure. Its full XML and extracted output are preserved under
`tmp/2203-bulk-engine-full*` and `tmp/2203-pacing-output.txt`. A thread snapshot
confirms the later long-running suite was progressing through EngineSoakTest.
Root-cause investigation remains open; it is not waived by a passing retry.

2199 disables four guards and observes four intended failures: durable refusal
boot fencing, successor-boot requirement, cancellation/promotion serialization,
and advancing ACK repair past a full contradictory page. The script restores the
production source byte-for-byte in finally. Logs/XML/failure messages are retained
in `tmp/2199-bulk-negative*`; restored inventory is `tmp/2199-bulk-restored-sources.json`.
2204 re-executes the two bulk suites plus pacing: ten cases, zero failures/errors/
skips. It proves restored guards but does not discharge the intermittent close defect.

2205 `build -x test -PskipWebBuild=true` fails only indexer-worker formatting after
the qualifier correction. 2206 applies that module's formatter and passes the same
whole build (including static checks); web asset build is skipped because frontend
sources are unchanged. No negative mutations, Gradle process or dev stack remain.
2202 engine-port and operation-surface gates pass (one informational engine-port
finding), and store recoverability passes six stores/46 authorities/27 policies.

The connected checkpoint is publishable with the close defect explicitly open;
it is not C2 completion. Next: diagnose/fix and re-prove close, then the installed
partial-capture, state-before-BUILDING-checkpoint and promotion-before-terminal
kill cuts. Final integrated stress and hosted test-level evidence remain required.
