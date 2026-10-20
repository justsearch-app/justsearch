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
      "behaviour": "R1/R2 pointer publication and validated previous-pointer repair, with counterexamples for stale scratch paths, torn recovery, conflicting receipts, orphan generations and missing metadata; R3 preservation cases remain exercised.",
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
      "behaviour": "An interrupted quarantine or partially written restore uses its recorded backup before fresh tables can be opened, preserves its original quarantine, and keeps constructor-bound claim authority. Existing ownership fixtures explicitly seed intact journals for their healthy unfinished migrations; their assertions are unchanged.",
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
      "evidence": "gates/test-intent/evidence/restart-worker-queue.json",
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
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineSupervisedRecoveryE2ETest.java",
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/GenerationPointerPublicationE2ETest.java",
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/MigrationJournalLossE2ETest.java"
      ],
      "class": "New",
      "behaviour": "Installed Engine checks use identity-bound barriers and process-kill cuts for first publication, replacement and recovery. Real worker API calls delete a file, prefix, collection and projection during migration; HTTP search must return 503 before readiness and after the restarted worker refuses the damaged journal, with queue evidence unchanged.",
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
      "repaired": true
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
    }
  ]
}
```

## Acceptance records

An independent verifier supplies acceptance after checking the final candidate and generated evidence.
