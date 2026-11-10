---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: c2-retry-test-intent-builder
---

# Test-intent entries

Branch c2-retry implements #1098: a watched file whose extraction failed under an older effective
policy gets one attempt under the current policy, bound to the attempt by a queue schema v29
job-level field. The owner agreed "#1098 (re-extract after extractor or limit changes): yes."; the
scenarios C1 to C9 and N1 to N8 are the orchestrator's settled choices under the task's delegation.

Schema-literal edits (28 to SqliteSchema.TARGET_VERSION, or 29) are not repairs; their evidence is
a candidate run only. Repaired checks carry a fail-before run on origin/main.

Test edits made only so that a fail-before is an assertion, as the rules require:
ExtractionAttemptPolicyMigrationTest wraps its two "ALTER TABLE jobs DROP COLUMN
extraction_policy_id" set-up statements in assertDoesNotThrow (origin/main has no such column);
Release030UpgradeCharacterizationTest wraps its DONE/policy-id row count in assertDoesNotThrow (on
origin/main that query names a missing column and would throw), keeping the assertion order; and
returnedRootReplayRetriesRealExtractionFailureOnceAfterPolicyChange uses the display-name convention
"{displayName} [{index}] {arguments}" so the evidence can select it.

The new isolated files WatchedExtractionPolicyRecoveryTest, ExtractionAttemptPolicyQueueTest,
WatchedExtractionFailureAdmissionPathsTest and WatchedExtractionFailurePolicyTest are exempt; the
verifier checks them.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ImportStoreMixedUpgradeTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C9: policy evidence is bound to the attempt (queue schema v29 job-level field), never the latest ledger event.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N5: the schema upgrade keeps every row, and the previous queue schema version (v28) stays readable for release upgrades.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0048 (bounded ladder; RETRY_EXHAUSTED reset by a rescan or file change) is compatible: C6 excludes transient-retry exhaustion and N2 keeps same-policy backoff. ADR-0028 and ADR-0007 only add jobs.db tables; no decision fixes the queue schema at v28 or forbids a policy-change retry.",
      "oldToNew": [
        {
          "test": "ImportStoreMixedUpgradeTest (the jobs.db version after the independent queue migration, after the injected operations-store failure, and after reopen)",
          "old": "assertEquals(28, version(jobs)) three times",
          "new": "assertEquals(SqliteSchema.TARGET_VERSION, version(jobs)), which is 29 (C9). The operations-first branch (operations at 7, an injected queue rollback leaving jobs.db at 27), the injected operations-store IOException, the operations-store versions 6 and 7, empty recovery and queue depth 1 are unchanged."
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-import-store-upgrade.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ImportStoreMixedUpgradeTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ImportOriginBindingMigrationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C9: policy evidence is bound to the attempt (queue schema v29 job-level field), never the latest ledger event.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N5: the schema upgrade keeps every row, and the previous queue schema version (v28) stays readable for release upgrades.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0048 (bounded ladder; RETRY_EXHAUSTED reset by a rescan or file change) is compatible: C6 excludes transient-retry exhaustion and N2 keeps same-policy backoff. ADR-0028 and ADR-0007 only add jobs.db tables; no decision fixes the queue schema at v28 or forbids a policy-change retry.",
      "oldToNew": [
        {
          "test": "ImportOriginBindingMigrationTest (user_version after opening an older queue)",
          "old": "assertEquals(28, user_version, \"origin restart bindings require v28\")",
          "new": "assertEquals(SqliteSchema.TARGET_VERSION, user_version) with the same message: v29 still includes the v28 restart-binding columns, whose count of 4 is still asserted. Inputs and every other assertion are unchanged."
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-import-origin-binding.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ImportOriginBindingMigrationTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C9: policy evidence is bound to the attempt (queue schema v29 job-level field), never the latest ledger event.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N5: the schema upgrade keeps every row, and the previous queue schema version (v28) stays readable for release upgrades.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0048 (bounded ladder; RETRY_EXHAUSTED reset by a rescan or file change) is compatible: C6 excludes transient-retry exhaustion and N2 keeps same-policy backoff. ADR-0028 and ADR-0007 only add jobs.db tables; no decision fixes the queue schema at v28 or forbids a policy-change retry.",
      "oldToNew": [
        {
          "test": "JobCountsMigrationTest (user_version after open and reopen, in both tests)",
          "old": "assertEquals(28, version(queue))",
          "new": "assertEquals(SqliteSchema.TARGET_VERSION, version(queue)), which is 29. Saved fields, exact counts and switch-buffer admission counts are unchanged."
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-job-counts-migration.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C9: policy evidence is bound to the attempt (queue schema v29 job-level field), never the latest ledger event.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N5: the schema upgrade keeps every row, and the previous queue schema version (v28) stays readable for release upgrades.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0048 (bounded ladder; RETRY_EXHAUSTED reset by a rescan or file change) is compatible: C6 excludes transient-retry exhaustion and N2 keeps same-policy backoff. ADR-0028 and ADR-0007 only add jobs.db tables; no decision fixes the queue schema at v28 or forbids a policy-change retry.",
      "oldToNew": [
        {
          "test": "JobQueueMigrationTest (user_version after migrating each older version; the TARGET_VERSION pin)",
          "old": "assertEquals(28, user_version) and assertEquals(28, SqliteSchema.TARGET_VERSION)",
          "new": "assertEquals(SqliteSchema.TARGET_VERSION, user_version) and assertEquals(29, SqliteSchema.TARGET_VERSION). The migrated tables, columns, switch-buffer inserts and messages are unchanged."
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-job-queue-migration.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C9: policy evidence is bound to the attempt (queue schema v29 job-level field), never the latest ledger event.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N5: the schema upgrade keeps every row, and the previous queue schema version (v28) stays readable for release upgrades.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0048 (bounded ladder; RETRY_EXHAUSTED reset by a rescan or file change) is compatible: C6 excludes transient-retry exhaustion and N2 keeps same-policy backoff. ADR-0028 and ADR-0007 only add jobs.db tables; no decision fixes the queue schema at v28 or forbids a policy-change retry.",
      "oldToNew": [
        {
          "test": "SourceOriginLookupTest (user_version after lookup)",
          "old": "assertEquals(28, user_version)",
          "new": "assertEquals(SqliteSchema.TARGET_VERSION, user_version), which is 29. The lookup results and the source_origin row count of 3 are unchanged."
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-source-origin-lookup.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C9: policy evidence is bound to the attempt (queue schema v29 job-level field), never the latest ledger event.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N5: the schema upgrade keeps every row, and the previous queue schema version (v28) stays readable for release upgrades.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0048 (bounded ladder; RETRY_EXHAUSTED reset by a rescan or file change) is compatible: C6 excludes transient-retry exhaustion and N2 keeps same-policy backoff. ADR-0028 and ADR-0007 only add jobs.db tables; no decision fixes the queue schema at v28 or forbids a policy-change retry.",
      "oldToNew": [
        {
          "test": "SourceOriginMigrationTest (user_version after migration)",
          "old": "assertEquals(28, user_version)",
          "new": "assertEquals(SqliteSchema.TARGET_VERSION, user_version), which is 29. Durable source_origin storage and preserved queue depth are unchanged."
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-source-origin-migration.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C1: a watched file that failed extraction under an older effective policy, bytes unchanged, gets exactly one new attempt under the current policy (reusing a valid result already indexed under the current policy counts).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C7: every automatic admission path, including returned-root replay, except replays during a legacy-format transition.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N4: successful files are not re-extracted beyond #1098.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0048 (bounded ladder; RETRY_EXHAUSTED reset by a rescan or file change) is compatible: C6 excludes transient-retry exhaustion and N2 keeps same-policy backoff. ADR-0028 and ADR-0007 only add jobs.db tables; no decision fixes the queue schema at v28 or forbids a policy-change retry.",
      "oldToNew": [
        {
          "test": "unchangedThousandFileSyncMeasuresIndexReadBudget",
          "old": "verifyNoInteractions(queue): an unchanged 1,000-file sync touches the queue not at all",
          "new": "verify(queue, times(batches.size())).extractionPolicyRetryPaths(anyCollection(), anyString()); verifyNoMoreInteractions(queue). Every automatic sync batch now asks the queue which of its unchanged paths hold a changed-policy extraction failure (C1, C7); with none, nothing is admitted, and no other queue call is allowed. The zero files added, census and family read budgets are unchanged."
        },
        {
          "test": "the unchanged-stamp/size/mtime candidate-difference test (CANDIDATE branch)",
          "old": "verifyNoInteractions(queue)",
          "new": "verify(queue, atMostOnce()).extractionPolicyRetryPaths(anyCollection(), anyString()); verifyNoMoreInteractions(queue). Candidate discovery may make the same read-only retry lookup at most once; no admission or other queue call is allowed (N4). Empty additions and deletions and the root identity check are unchanged."
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-sync-changed-stamp.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncExtractionPolicyTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C1: a watched file that failed extraction under an older effective policy, bytes unchanged, gets exactly one new attempt under the current policy (reusing a valid result already indexed under the current policy counts).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C7: every automatic admission path, including returned-root replay, except replays during a legacy-format transition.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N4: successful files are not re-extracted beyond #1098.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0048 (bounded ladder; RETRY_EXHAUSTED reset by a rescan or file change) is compatible: C6 excludes transient-retry exhaustion and N2 keeps same-policy backoff. ADR-0028 and ADR-0007 only add jobs.db tables; no decision fixes the queue schema at v28 or forbids a policy-change retry.",
      "oldToNew": [
        {
          "test": "currentEffectivePolicyKeepsUnchangedFilesSkipped",
          "old": "verifyNoInteractions(queue)",
          "new": "verify(queue, atLeastOnce()).extractionPolicyRetryPaths(anyCollection(), eq(policy.policyId())); verifyNoMoreInteractions(queue). Sync asks, with the current policy id, whether an unchanged file holds a changed-policy extraction failure (C1, C7); the mock answers none, so admitted stays empty and files added stays 0 in DEFAULT and MAINTENANCE, and candidate additions stay empty (N4). No other queue call is allowed."
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-sync-extraction-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncExtractionPolicyTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ExtractionAttemptPolicyMigrationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Opening a v28 queue upgrades it to v29: every job row of each state keeps its 14 saved columns unchanged and gains extraction_policy_id = NULL when no attempt evidence exists (N5, C3). The upgrade backfills a job's policy only from exactly one terminal extraction ledger event of the same operation and unit revision (\"old\"), never from a later unrelated event (\"new\"); another revision, a duplicated event or pruned history leave it NULL, so C3 applies (C9).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C3: failed rows without a recorded policy get one attempt, recorded durably.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C9: policy evidence is bound to the attempt (queue schema v29 job-level field), never the latest ledger event.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N5: the schema upgrade keeps every row, and the previous queue schema version (v28) stays readable for release upgrades.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-attempt-policy-migration-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ExtractionAttemptPolicyMigrationTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExtractionPolicyMaintenanceAdmissionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "olderTerminalExtractionFailureIsReadmitted: an unchanged file under a declared root whose terminal PARSER_FAILED (retry policy NONE) was recorded under the older policy tika-default-v7 is re-admitted once by maintenance reconciliation under the current policy: one file added, one pending job (C1, C6, C7).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C1: a watched file that failed extraction under an older effective policy, bytes unchanged, gets exactly one new attempt under the current policy (reusing a valid result already indexed under the current policy counts).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C6: any policy change counts; terminal extraction failures count, except failures that became terminal only by exhausting retries for a transient cause.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C7: every automatic admission path, including returned-root replay, except replays during a legacy-format transition.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-maintenance-admission-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExtractionPolicyMaintenanceAdmissionTest.java#olderTerminalExtractionFailureIsReadmitted"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExtractionPolicyMaintenanceAdmissionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Both queue openings in check() now declare the test root through useDeclaredCollectionRoots, because the changed-policy retry is limited to declared roots (N7). The existing cases keep their inputs, observations and expected outcomes: the completed older extraction is still re-admitted; terminal and future-retry IO_FAILED rows still keep their protected failure state (N1, N2). Declaring the root only makes the file more eligible, so the protected-state assertions are at least as strict. The evidence runs the whole file on the candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N1: non-extraction failures are not revived.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N2: same-policy backoff is unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N7: scope guards are kept.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-maintenance-admission.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExtractionPolicyMaintenanceAdmissionTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "returnedRootReplayRetriesRealExtractionFailureOnceAfterPolicyChange (direct and captured-rebuild admission): a real extraction failure (BUDGET_EXCEEDED under a 128-character limit) is recorded with its policy id and no indexed content. A returned-root replay under the same policy leaves the failed row unchanged (C2). After the policy changes, the replay readmits it as PENDING with its scan, originator and transport kept (C7, N6); extraction then succeeds under the current policy, the term is searchable and the stored content holds it, the ledger keeps the old failure plus the success (C8), and a sealed captured walk stays as it was. Before-state reason: on origin/main both cases fail by assertion at the per-job policy read (expected the limited policy id, was null, because main has no job-level extraction_policy_id), before the replay steps; the fail-before therefore shows the missing C9 binding, not the returned-root admission itself.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C1: a watched file that failed extraction under an older effective policy, bytes unchanged, gets exactly one new attempt under the current policy (reusing a valid result already indexed under the current policy counts).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C2: a repeated failure under the same policy settles: no more attempts.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C7: every automatic admission path, including returned-root replay, except replays during a legacy-format transition.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C8: success shows in search and details after commit and restart, with history kept.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C9: policy evidence is bound to the attempt (queue schema v29 job-level field), never the latest ledger event.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N6: attribution and receipts are kept.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-returned-root-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java#returnedRootReplayRetriesRealExtractionFailureOnceAfterPolicyChange"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The Fixture gains an overload taking an extraction policy (a recorded-claim-allowing queue, the policy-driven Tika extractor and the publisher policy) and a returnRoot() helper; the no-argument constructor still builds the same queue and the same text provider, so the existing tests keep their inputs, fault injection, observations and expectations. The evidence runs the whole file on the candidate.",
      "evidence": "gates/test-intent/evidence/c2-retry-returned-root.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "issue739LegacyJobsAreReadAndSelectiveWriteRepairSurvivesRestart, U12 replaced: the authentic terminal extraction failure copied under a watched root has no recorded policy, so after the release-0.3.0 upgrade it gets one durable current-policy attempt: its row is DONE with a policy id and its text is searchable (C3, C8). The unwatched original stays FAILED (N7). All other upgrade expectations and their order are unchanged; the DONE/policy-id count is wrapped in assertDoesNotThrow so that origin/main, which lacks the column, fails by assertion there.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "D1098",
          "quote": "#1098 (re-extract after extractor or limit changes): yes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C3: failed rows without a recorded policy get one attempt, recorded durably.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "C8: success shows in search and details after commit and restart, with history kept.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "N7: scope guards are kept.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/c2-retry-release030-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java#issue739LegacyJobsAreReadAndSelectiveWriteRepairSurvivesRestart"
      ]
    }
  ]
}
```

## Acceptance records
