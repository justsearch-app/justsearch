# C2-12 residue sweep evidence — 2026-09-21

**Checkout:** `codex/lane-f-pr1`, commit `ba1440624b2fd0b4d0cc1dd4254c87dfe6d29001`, with this
worktree's uncommitted scoped edits. **Environment:** Windows PowerShell, Node.js `v24.12.0`.

| C2-12 residue | Current owner evidence and disposition |
|---|---|
| Operation key deferred claims | `modules/app-api/src/main/java/io/justsearch/app/api/registry/OperationInvocationRequest.java:15-17` documents UUIDv7 lookup; `modules/ui/src/main/java/io/justsearch/ui/api/OperationsController.java:222-228` forwards the key; `modules/app-observability/src/main/java/io/justsearch/app/observability/operations/SqliteOperationStore.java:379-404` looks up an existing row before insert. The obsolete deferral claim is removed from the current C2-12 checklist. |
| Operation history future-slice wording | `modules/app-observability/src/main/java/io/justsearch/app/observability/operations/OperationHistoryStore.java:8-10,49-55` is a bounded projection over `OperationStore.recentHistory`. The future-slice wording is absent from the source. |
| Stage B checkpoint measurability | `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:1525-1539` checkpoints durable operations before closing the index half. `docs/design/lane-f-engine-jvm/stages/B.md:1010-1014` records the local checkpoint proof; producer and installed-kill proof remain open, and `:807-813` correctly leaves the process row unpassed. Old line references `529-532` and `714-717` did not locate those claims at this revision. |
| jobs-db path, encryption, and recovery notes | `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:589` resolves `dataDir/jobs.db`; sidecars derive from `dbPath` at `:2982-2985`; `.bak` paths derive from `dbPath` in `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteJobQueue.java:2768-2769` and `KnowledgeServer.java:2972-2975`. `modules/app-agent-api/src/main/java/io/justsearch/agent/api/encryption/StoreCatalog.java:25-37` keeps the DERIVED/OPAQUE OS-disk-encryption classification. `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteSchema.java:152-166,273-305` shows raw paths in queue/path-resolution data and hashed path identity. The corrected `corruptionNote` references the boot call/order (`KnowledgeServer.java:992-999,394-413`), bounded batches (`DocumentIdentityBootImport.java:30-31,50-114`), and transactional batch import (`SqliteDocumentIdentityStore.java:158-187`). The register's owner, encryption classification, status, format, and versions were not changed. |
| Bulk reindex Worker prose | `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/handlers/BulkReindexHandler.java:24-25,79-84` freezes the plan and delegates to `RecordedIngestionService`; the cited Worker `MIGRATING` and upgrade-quiescence prose is absent. |
| Version drift and I3 test facts | jobs-db schema/register both report 19 (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteSchema.java:43`; register `:619-622`); operations-db schema/register both report 5 (`modules/app-observability/src/main/java/io/justsearch/app/observability/operations/OperationSchema.java:7-9`; register `:1532-1548`). `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java:899-981,1369-1374` covers V11-to-current equivalence and future-version refusal; V14 is still a historical migration fixture (`:1386-1398`). |
| Inherited C2 claims | `docs/design/lane-f-engine-jvm/stages/C2.md` R4, I3, and I9 now distinguish the historical pre-C1 drift / shutdown gap from current versions and the C2-7 checkpoint implementation. |

**Executed checks:** `node scripts/ci/check-store-recoverability.mjs` passed and reported six catalog
stores plus 46 durable authorities. The scoped phrase search in C2-12 returned no matches across
`modules/`, `governance/`, and canonical documentation roots. A no-match `rg` run exits 1 by design.

**Parent verification:** full stress2264 on production revision
`ba1440624b2fd0b4d0cc1dd4254c87dfe6d29001` completed successfully in 23m53s:
11,329 represented cases / 1,766 suites, zero failures/errors, 31 skips. Eleven
unchanged module tasks were UP-TO-DATE; task reuse is explicit in
`tmp/2264-full-stress-counts.json`. Full output and copied XML are retained in
`tmp/2264-full-stress.txt` and `tmp/2264-full-stress-xml/`.
`WholeProgramDeadCodeTest.no_new_whole_program_dead_classes` executed and passed
(one case, no skips), rather than inferring this from another architecture test.

**Parent residue correction:** I10 now labels the in-memory outcome store as the
pre-C2 baseline and points to the current durable owner. Stage B producer and
installed-kill proof remains open in its own acceptance register; this sweep does
not mark those scenarios passed. Installed and current-model proof are recorded in
[the current C2 verification record](verification-2026-09-21.md).
