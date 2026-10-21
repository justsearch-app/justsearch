---
schema: test-intent.v1
task: t-20261019-91c3ab
author-role: builder
author-session: 01a1566a-a034-78e2-81ac-cb210fd2ee9a
---

# Test-intent entries

The scenarios are delegated orchestrator choices, not owner words. Linux and Windows execution evidence is separate. The compatibility patch adds only kill observations, a pre-queue barrier, native-pointer fault injection, and interface scaffolding to the before-state; the original two-move writer and queue triage remain.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerDurabilityTest.java",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerPreviousRepairTest.java"
      ],
      "class": "New",
      "behaviour": "R1/R2 pointer publication and validated previous-pointer repair, with counterexamples for stale scratch paths, torn recovery, conflicting receipts, orphan generations and missing metadata; R3 preservation cases remain exercised. Startup reaps only numeric state.json writer temps after a pointer is read or repaired; refusal preserves potential sole copies. M05 separately tests effective fingerprint disagreement with otherwise matching receipts and metadata.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R1: a killed pointer publication leaves the old or new valid authority, including first publication, replacement, recovery writes and stale temporary files. R2: repair a missing current pointer only when operation, source, target and generation metadata match recorded receipts; conflicting receipts, orphan generations or missing metadata keep the refusal. R3: retain ordinary .prev recovery and strict recorded fencing.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Publish a replacement state with the original current-to-prev rotation; halt between its two moves, then attempt strict recorded boot. Also repair a torn current and observe whether its valid previous pointer was overwritten."
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-generation.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerDurabilityTest.java",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerPreviousRepairTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerWindowsSharingTest.java"
      ],
      "class": "New",
      "behaviour": "R3b on Windows: hold state.json open without delete sharing, observe the writer in its bounded atomic replacement retry, release the reader, and require successful publication.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R3b: the pointer writer retries a transient Windows sharing denial on rename, as AtomicFileWrites does.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R11: keep platform evidence separate and preserve prior authority through interrupted quarantine or restore and unsupported atomic replacement. R12: process-kill guarantee only; reuse existing fsync behavior without adding power-loss machinery.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no crash",
          "reproduction": "Hold state.json without delete sharing while publishing a replacement; the original pointer writer fails immediately rather than retrying."
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-windows.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerWindowsSharingTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/MigrationJournalDurabilityBootTest.java"
      ],
      "class": "New",
      "behaviour": "R9/R9b/R9c: lost or stale journals refuse before serving and preserve queue files across repeated refusals in each unfinished phase, including post-schema corruption. External witness failures cannot ACK direct or transactional admissions. Healthy legacy migrations without a witness still resume.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Start an unfinished migration, then remove, empty, corrupt or restore an older valid jobs.db; the old worker opens/replaces the journal and resumes. Compete for an already-held index root and inspect whether queue triage ran before lock refusal."
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-queue.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/MigrationJournalDurabilityBootTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java"
      ],
      "class": "New",
      "behaviour": "An interrupted quarantine or partially written restore uses its recorded backup before fresh tables can be opened, preserves its original quarantine, and keeps constructor-bound claim authority. Existing ownership fixtures explicitly seed intact journals for their healthy unfinished migrations; their assertions are unchanged. M22 proves a second completed triage preserves its predecessor. M23 interrupts the backup-copy I/O after a partial output and requires the prior live target, backup and quarantine to remain intact.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R11: keep platform evidence separate and preserve prior authority through interrupted quarantine or restore and unsupported atomic replacement. R12: process-kill guarantee only; reuse existing fsync behavior without adding power-loss machinery.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Leave the database quarantined with a valid backup and an incomplete triage receipt; the old worker opens fresh tables and loses the old processing row."
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-queue.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java"
      ],
      "class": "New",
      "behaviour": "The current schema pin follows the additive V23 admission counter; all existing migration, row-preservation, future-version and identity assertions remain. Historical schema fixtures remove the V23 admission trigger and table before reconstructing an older format.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-schema-pin.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java#migratesV11ToCurrentPreservingQueueAndIdentityRowsAndRefusesTheNextVersion"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedBulkIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "The operation owner exposes unfinished generation authority before opening a queue. Only an exact BUILDING capture can prove that a previous source pointer is recoverable; SETTLED remains ambiguous because settlement precedes promotion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R1: a killed pointer publication leaves the old or new valid authority, including first publication, replacement, recovery writes and stale temporary files. R2: repair a missing current pointer only when operation, source, target and generation metadata match recorded receipts; conflicting receipts, orphan generations or missing metadata keep the refusal. R3: retain ordinary .prev recovery and strict recorded fencing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "With an exact BUILDING capture and a missing pointer, the original owner provides no proof for repair. With a pending generation and an unopened journal, it provides no pre-queue authority observation."
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-owner.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedBulkIngestionCoordinatorTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerWorkerDownCodeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java"
      ],
      "class": "New",
      "behaviour": "The journal refusal survives generic failures and asserts/clears both Health dimensions, carrying its named cause and repair detail to the UI.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Present the migration-journal fatal marker and NOT_READY reason to the original bootstrap/Health tap; the original UI path reports a generic failure or omits the Health condition."
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-owner.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerWorkerDownCodeTest.java#migrationJournalRefusalSurvivesLaterGenericFailure",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/migrationJournalNotice.test.ts"
      ],
      "class": "New",
      "behaviour": "The journal refusal is search-unavailable with error severity, names the lost journal and retained repair files, and offers Open Health.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Project index.migration_journal_unavailable through the original readiness notice: consequence is unknown and the journal repair cause is absent."
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/migrationJournalNotice.test.ts#journal refusal names the lost migration journal and offers Health while search is unavailable"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/GenerationPointerPublicationE2ETest.java",
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/MigrationJournalLossE2ETest.java"
      ],
      "class": "New",
      "behaviour": "Installed R1 repair checks kill replacement publication after backup and a recovery write before backup, then require an authoritative pointer and completion of the original recorded migration. R9 checks issue real file, prefix, collection and projection deletions during migration; HTTP search must return 503 before readiness and after the restarted worker refuses the damaged journal, with queue evidence unchanged. Passing-before pointer cuts and ordinary supervised recovery are recorded separately as preservation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R1: a killed pointer publication leaves the old or new valid authority, including first publication, replacement, recovery writes and stale temporary files. R2: repair a missing current pointer only when operation, source, target and generation metadata match recorded receipts; conflicting receipts, orphan generations or missing metadata keep the refusal. R3: retain ordinary .prev recovery and strict recorded fencing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R11: keep platform evidence separate and preserve prior authority through interrupted quarantine or restore and unsupported atomic replacement. R12: process-kill guarantee only; reuse existing fsync behavior without adding power-loss machinery.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Kill the real original writer between pointer moves, or replace its unfinished-migration queue with corrupt/missing/empty/stale data. The installed worker respectively cannot reopen strict authority or accepts a lost journal."
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-installed.json",
      "newChecks": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/GenerationPointerPublicationE2ETest.java",
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/MigrationJournalLossE2ETest.java"
      ],
      "repaired": true,
      "unresolvedRisks": "Replacement pointer fixtures explicitly ingest indexed content once without registering a persisted watched root: a persisted watched root during a crashed migration makes cutover refuse; tracked separately. The original watched-root after-publication reproduction is retained uncommitted under tmp/restart/a5fbcb/ and compared with f9bd104d2. All R1 assertions and timeouts, including recorded-owner completion within 120 seconds, remain unchanged."
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineSupervisedRecoveryE2ETest.java",
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/GenerationPointerPublicationPreservationE2ETest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: all seven pointer cuts that pass on f9bd104d2 retain their original scenario calls, assertions and timeouts. Writer, migration, lock-ingest and PROCESSING replay plus deterministic intruder cleanup also pass on both product versions with the same complete harness. Separate baseline evidence is gates/test-intent/evidence/restart-worker-installed-preservation-before.json.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R1: a killed pointer publication leaves the old or new valid authority, including first publication, replacement, recovery writes and stale temporary files. R2: repair a missing current pointer only when operation, source, target and generation metadata match recorded receipts; conflicting receipts, orphan generations or missing metadata keep the refusal. R3: retain ordinary .prev recovery and strict recorded fencing.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "Cases expected to pass before, if any, go in a preservation file instead.",
          "location": "task t-20261019-91c3ab request (Builder A, run 8, decision 3)"
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-installed-preservation.json",
      "newChecks": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/GenerationPointerPublicationPreservationE2ETest.java",
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineSupervisedRecoveryE2ETest.java"
      ],
      "repaired": false,
      "unresolvedRisks": "initialBootstrapSurvivesHostileLocks is moved unchanged to EngineBootstrapWindowsLocksE2ETest so its Windows-only skip is outside the portable evidence; it is not counted as passed. The original Linux comparison skipped it on both states. Replacement pointer fixtures explicitly ingest indexed content once without registering a persisted watched root: a persisted watched root during a crashed migration makes cutover refuse; tracked separately. The original watched-root after-publication reproduction is retained uncommitted under tmp/restart/a5fbcb/ and compared with f9bd104d2. All R1 assertions and timeouts, including recorded-owner completion within 120 seconds, remain unchanged."
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineBootstrapWindowsLocksE2ETest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Mandatory bootstrap file-lock contention is exercised only on Windows, with the original 420-second class bound.",
      "whySurvives": "Pure move of initialBootstrapSurvivesHostileLocks from EngineSupervisedRecoveryE2ETest: same runScenario(\"lock-boot\") call, platform assumption, assertions, fault/cleanup paths and timeout. The new class remains discovered by integrationTest; no repository runner filters on the old class. Linux evidence reports the earlier skip as unverified, separate from the portable passing cases.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Cases expected to pass before, if any, go in a preservation file instead.",
          "location": "task t-20261019-91c3ab request (Builder A, run 8, decision 3)"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/WorkerBootFixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SwitchBufferVersionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Healthy in-flight-generation fixtures explicitly create their intact job journal. Historical switch-buffer fixtures remove the V23 admission trigger and table before constructing the older schema. The mismatch, brake, migration, replay and import assertions are unchanged; missing journals are separately tested as R9b refusals.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/system-tests/build.gradle.kts"
      ],
      "class": "Structural rule kept",
      "rule": "Integration test caching includes the scripts exercised by its selected scenarios.",
      "whySurvives": "Only inputs.file declarations for the two new scenario scripts are added. Test classes, filters, tags, classpath and timeouts are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R1: a killed pointer publication leaves the old or new valid authority, including first publication, replacement, recovery writes and stale temporary files. R2: repair a missing current pointer only when operation, source, target and generation metadata match recorded receipts; conflicting receipts, orphan generations or missing metadata keep the refusal. R3: retain ordinary .prev recovery and strict recorded fencing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts"
      ],
      "class": "New",
      "behaviour": "M09/M26: the rendered search notice names the missing migration journal, retains the refusal through repeated renders and status updates, and clears when readiness resolves. Mutation checks remove the rendered wording or clear the notice after its first render.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Project index.migration_journal_unavailable through the original readiness notice: consequence is unknown and the journal repair cause is absent."
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-rendered-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts#M09 names the migration journal refusal in the rendered user notice",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts#M26 keeps the journal refusal visible across renders and repeated status updates until resolved"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRebuildPreparationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Create and close the real intact SQLite job journal before seeding the unfinished migration. The fixture represents healthy migration/brake recovery, while missing journals are separate R9 refusal cases. The Engine boot, read-only brake, frozen target/plan, byte preservation, queue depth, migration state and no-ingestion assertions and 180-second timeout are unchanged. CI #39411554019 previously refused this incomplete fixture with index.migration_journal_unavailable.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Fix anything that is yours.",
          "location": "task t-20261019-91c3ab request (Builder A, round 9)"
        }
      ]
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationVduEligibilityTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "A warm generation owner may atomically publish while a strict reader waits on the old pointer; the reader must reopen and observe MIGRATING rather than cached IDLE. Cold owners still refuse unavailable input.",
      "whySurvives": "Only wrap the existing updateMigrationState call in assertDoesNotThrow to make its already-required successful publication explicit. The held real file lock, 100ms blocked-reader precondition, owner API call, successor read, refusal assertion, timeouts and cleanup remain unchanged. CI #39411554019 fails from writeState acquiring a reader lock for its backup snapshot. The writer now snapshots immutable old pointer bytes under its generation control and still validates JSON before replacing the backup; strict readers retain ContendedFileReads. Focused before/after and known-good results are retained under tmp/ci-822/round9/.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Fix anything that is yours.",
          "location": "task t-20261019-91c3ab request (Builder A, round 9)"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java"
      ],
      "class": "New",
      "behaviour": "Replaying V23 after user_version rewinds to 20 or 22 tolerates already-applied admission structure, preserves the monotonic count when no buffer rows remain, recreates the trigger after V21 table reconstruction, and increments exactly once for each later write across repeated reopens. The reopen is asserted not to throw so the bfe0a9cba before-state fails an assertion for the duplicate-table defect.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Every other migration step tolerates already-applied structure; V23 should too, without ever lowering an existing counter. The counter is monotonic, and a rollback that rewinds user_version must keep it.",
          "location": "task t-20261019-91c3ab request (restart-a builder CI repair brief, 2026-10-21)"
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-v23-replay.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java#replayingV23PreservesAdmissionsAndCountsNewWritesOnce"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The interruptedBackupWithAbsentActiveReconcilesAndStartsSourceRebuild fixture now creates and closes its real jobs.db before moving the active generation into its backup. It represents healthy interrupted recovery, which retains its journal; omitting jobs.db contradicts the R9 refusal obligation. The real server boot, recovery fault, document counts, migration phase/source, assertions, tags and timeouts are unchanged. Missing-journal refusal remains covered by MigrationJournalDurabilityBootTest.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 user turn (owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19)",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R9 and R9b-v3: refuse missing, zero-byte, corrupt, triage-replaced or witness-stale migration journals without replacing queue evidence; show a named refusal in the UI. Future switch-buffer acknowledgements need a durable monotonic admission witness outside jobs.db. Existing unfinished migrations without a witness resume as today, with their historical completeness gap recorded. R9c: index-root lock and authority precede triage. R10: healthy legacy migrations, import, path readers and conditional replay removal remain intact.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "Fix production code, not the existing tests, unless a test asserts something the agreed design contradicts. If so, say which and why.",
          "location": "task t-20261019-91c3ab request (restart-a builder CI repair brief, 2026-10-21)"
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-backup-fixture.json",
      "unresolvedRisks": "No new product expectation. Evidence covers the repaired healthy-recovery setup on Linux, not Windows."
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessReserveTest.java"
      ],
      "class": "New",
      "behaviour": "At the drain deadline an append already holding the real recording lock completes at virtual 100 ms inside the 150 ms reserve, leaving a positive 50 ms for the real timed fence before finish returns. Pending writes cannot append afterward. A scripted monotonic clock makes expiry independent of CPU scheduling: missing reserve spends it in the drain and then fails the positive-reserve assertion. A package-private recording-only constructor avoids Windows native observation; production retains System.nanoTime and its own ReentrantLock. The check now runs in the cross-platform lane on Linux and Windows.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Never weaken the property it protects (an append in flight at the deadline finishes within the reserve, or the stop is refused).",
          "location": "task t-20261019-91c3ab request (restart-a builder CI repair brief, 2026-10-21)"
        },
        {
          "kind": "owner-words",
          "quote": "Make the time it sees controllable: an injectable clock or deadline seam in the witness, if one already exists or can be added minimally. Then an append in flight at the drain deadline must receive a positive reserve, and must finish within it.",
          "location": "task t-20261019-91c3ab request (orchestrator Windows verifier-rejection follow-up, 2026-10-21)"
        },
        {
          "kind": "owner-words",
          "quote": "The test is Windows-only; if its logic can run on Linux with the Windows parts faked, run it here. Otherwise say plainly that it is unverified until hosted Windows.",
          "location": "task t-20261019-91c3ab request (orchestrator Windows verifier-rejection follow-up, 2026-10-21)"
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-witness-no-reserve.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessReserveTest.java#anAppendInFlightAtTheDrainDeadlineCanFinishWithinTheFenceReserve"
      ],
      "repaired": false,
      "mutationProof": "The evidence before run is b77d770d2 plus restart-worker-witness-no-reserve.patch: only the recording test seam and removal of the existing drain reserve. Candidate must pass and the no-reserve mutant must fail an assertion. This tests sensitivity, not a claim that b77d770d2 production lacks its reserve.",
      "unresolvedRisks": "Only process observation is disabled in the harness; the production writer, admission closure, pending queue, finish algorithm and storage lock execute. The clock waits for the real writer unlock before advancing to virtual 100 ms; the real timed tryLock then acquires uncontended storage, so success does not race CPU scheduling against the remaining 50 ms. Native Windows job observation remains unverified by these portable checks; its existing Windows-only tests remain unchanged. The prior skipped evidence restart-worker-witness-reserve-unverified.json is retained as historical unverified evidence."
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessReserveTest.java"
      ],
      "class": "New",
      "behaviour": "When virtual time reaches the full allowance before the fence and an append still owns storage, finish throws the retain-instance-lock instruction. The real recording lock remains held, an immediate retry also refuses, and only releasing and settling the append allows finish to return. Pending writes remain prohibited. The witness does not own or expose app.lock: the assertion concerns what its caller is told to do, not a claim that this class retains a caller-owned lock.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Keep a separate check for the refusal path, where the reserve really expires before the fence: the stop is refused and the instance lock is kept, asserted on the lock itself if the witness exposes it, or else on what the caller is told to do.",
          "location": "task t-20261019-91c3ab request (orchestrator Windows verifier-rejection follow-up, 2026-10-21)"
        },
        {
          "kind": "owner-words",
          "quote": "The test is Windows-only; if its logic can run on Linux with the Windows parts faked, run it here. Otherwise say plainly that it is unverified until hosted Windows.",
          "location": "task t-20261019-91c3ab request (orchestrator Windows verifier-rejection follow-up, 2026-10-21)"
        }
      ],
      "evidence": "gates/test-intent/evidence/restart-worker-witness-no-fence.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessReserveTest.java#anAppendInFlightAtTheDrainDeadlineCanFinishWithinTheFenceReserve",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessReserveTest.java#anExpiredFenceRefusesStopUntilStorageSettlesAndTellsTheCallerToRetainItsLock"
      ],
      "repaired": false,
      "mutationProof": "The evidence before run is b77d770d2 plus restart-worker-witness-no-fence.patch: only the recording test seam and removal of the storage fence. Both checks must fail assertions: missing fence observation in the success case and missing refusal in the expired case. This is an injected sensitivity check, not a production regression at b77d770d2.",
      "unresolvedRisks": "Native Windows process observation and end-to-end caller instance-lock ownership are outside this witness harness. Refusal, active storage ownership, refusal on retry and recovery after storage settles are directly exercised on Linux without timing assumptions."
    }
  ]
}
```

## Acceptance records

An independent verifier supplies acceptance after checking the final candidate and generated evidence.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-822b-codex",
  "verdict": "accept",
  "digest": "sha256:465745458cdc10a06b439c6614a3824f8c8fa700a1065253ac7621825f36ef0c",
  "note": "Verified Linux part A on 09060a67a vs f9bd104d2 with fresh installed, SIGKILL, refusal, preservation and mutation checks. Windows unverified; independence is a separate Codex session."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-822b-codex",
  "verdict": "accept",
  "digest": "sha256:c6dc28cdb214190d25aebd1576a70a2560339a5b767a04b27dfd970b67259d29",
  "note": "Delta 5dae1007b..28c2d7508 verified: Linux PMD, store gate, four CI classes, 13 paired installed cases, SIGKILL/temp cleanup and rollback checks. Windows contracts independent; native candidate unverified. Separate Codex session only."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-822d-round2",
  "verdict": "accept",
  "digest": "sha256:7182cd5f7c4618d81b4787c235b688bc9f4580ac709a3cae4ab9bfca50685a7f"
}
```
