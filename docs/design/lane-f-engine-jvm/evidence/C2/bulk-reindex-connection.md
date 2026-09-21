# C2-10 bulk reindex connection

2026-09-21 source audit at `e923824af`. This item remains open. The installed
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

## Acceptance and design questions still to resolve

1. Accept one REINDEX/DURABLE bulk row and its prepared plan before effects; preserve
   exact caller identity across retry and restart without starting another migration.
2. Advance the same row across both restart boundaries using the persisted generation
   witness. C2's sentence about completion before requested restart and its successor
   advancement clause need an explicit crash-safe ordering decision, not an inferred waiver.
3. Capture H1, observe H2, report superseded processing history without blocking, and
   persist the required history/gaps projection. Keep D1 activation/gap refusal separate.
4. Compare extending the existing migration/generation owner against any proposed new
   durable binding or metadata mechanism before adding one. The current gap requires a
   connection; it does not justify the first proposed mechanism's full scope.

No implementation or execution proof is claimed by this audit. Root must settle the
prepared-plan/generation binding and recovery routing design before assigning edits.
