---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: 01a19334-61ec-70f0-aee5-3723a6300c2b
---

# WP6 test intent

The local gate flags 27 existing files against 30ec14d39b1070b1869ae66d52991e33d79bd1a1.
Six isolated new test files are exempt; the released-failure hash-read regression is
included explicitly so its evidence covers the last repair too. No acceptance is recorded.

More than 20 files are flagged, so fixtures, signatures and instrumentation are grouped
by class under the volume rule: “Structural PRs (more than 20 flagged files) are classified
per class of test against the PR's agreed scenarios in one pass.” New and changed
expectations are separately sourced below. All source choices stay within the quoted delegation.

The compatibility patch adds only overloads or neutral defaults for new signatures;
it adds no storage columns and no repaired admission, freshness or force behaviour.
No extra test-support overlay is required for the checks named below.

Supplementary preservation checks (P1/P2/C7/C8/C15/C20; pass before and after,
excluded from newChecks):

- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#shrinkAndSecondChangeAfterRestartReplaceParentAndChunkSearchResults`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#missingStoredSizeIsReextractedOnce`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#unchangedQueuedFileSkipsExtractionAcrossRestarts`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#unchangedStampWithMissingChunksStillRepairsTheFile`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java#ordinaryScansCarryNoForce`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceForceReindexWiringTest.java#submitBatchWithoutForceReindexMarksNothing`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceForceReindexWiringTest.java#initialScanDoesNotMarkAnything`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueDefectRegressionTest.java#maintenanceNoopPreservesEverySavedFieldAndActualEnqueueCounters`

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OriginBoundaryAdmissionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnreadableAndReplacedSourceQueueTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "Freshness mock stubs and verifications use the four-argument source-proof overload. Existing admission, unreadability, force and identity expectations remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D17 (Equal-size edits with preserved timestamps remain invisible) Rewrite equal-length content, restore its timestamp and require automatic ingestion to publish it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C7. Preserve \u2014 truly unchanged, current-policy documents: stronger freshness checks must retain the existing skip benefit; check matching source, policy, collection, and complete chunks avoids extraction on repeated ingestion and after reopening.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C9. Preserve \u2014 mutation and unreadability during freshness checking: hashing introduces another source-read interval, while extraction already checks source changes before and after parsing; inject mutation, deletion, and read failure and require retry/stale handling rather than a false unchanged acknowledgment.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C15. Preserve \u2014 force authority and lifecycle boundaries: recorded force is frozen in the issued claim, planned-source work already bypasses unchanged detection, and exclusions still precede extraction; check these paths alongside deletion/recreation and successful completion so force neither escapes its owner nor persists forever.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SplitSurrogateQueuedRestartTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Windows1252QueuedRestartTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingDocumentOpsScalarIntegrationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Existing unchanged-source fixtures now store source_sha256 (and the chunk fixture declares the field). Their text, chunks, quality-score and encoding expectations remain; this supplies the stronger unchanged precondition.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C7. Preserve \u2014 truly unchanged, current-policy documents: stronger freshness checks must retain the existing skip benefit; check matching source, policy, collection, and complete chunks avoids extraction on repeated ingestion and after reopening.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. Preserve \u2014 existing reasons to repair unchanged bytes: collection changes, missing stored size, incomplete chunks, and committed deletion/replacement already invalidate admission; check stronger source proof does not bypass these checks, including null/blank default collection equivalence.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueDefectRegressionTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C20. Implied, partly broken \u2014 changed sources and deliberate retries remain admissible: unchanged-work preservation must distinguish changed content from duplicate discovery; the current maintenance predicate does not inspect source identity at all. Check changed bytes\u2014including equal metadata\u2014can be admitted, and explicit retry/force still resets the intended failure run.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D23 (Automatic rediscovery resets unchanged files\u2019 retry ladders) Maintenance-admit an unchanged SANDBOX_FAILED job and require its revision, attempts, deadline and origin to survive.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none",
      "behaviour": "Retire the old expectation that maintenance preserves a failure even when incoming size, collection and root differ. C20 requires changed obligations to remain admissible. The retained same-source retry preservation arm now supplies readable bytes and identical size/collection/root; the explicit retry arm remains."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/resources/upgrade-030/README.md"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Relocated queue-only fixture files recover captured admission timestamps; copied unchanged failed files preserve attributes. The released retry-run preservation oracle stays, while unpacking no longer invents post-admission source edits.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C20. Implied, partly broken \u2014 changed sources and deliberate retries remain admissible: unchanged-work preservation must distinguish changed content from duplicate discovery; the current maintenance predicate does not inspect source identity at all. Check changed bytes\u2014including equal metadata\u2014can be admitted, and explicit retry/force still resets the intended failure run.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P4 Genuinely failing files still follow the retry ladder and seven-day bound; maintenance admission still preserves terminal failures and future failure waits that it preserves today.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleV24MigrationEvidenceTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Every supported persisted queue layout migrates to the current schema atomically, preserving saved jobs, identity, counts and admission fences; failed migrations roll back and older binaries refuse newer layouts.",
      "whySurvives": "V26 replaces V25 as the target of the same migration ladder. A V25 fixture additionally checks retention of revision, attribution and root and the neutral non-force default. Existing scale, crash, rollback and saved-row predicates remain. The older-binary refusal fixture also names V26.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D20 (Ordinary force intent races admission and disappears on restart) Consume immediately after enqueue but before force marking, and separately restart with forced jobs pending.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C15. Preserve \u2014 force authority and lifecycle boundaries: recorded force is frozen in the issued claim, planned-source work already bypasses unchanged detection, and exclusions still precede extraction; check these paths alongside deletion/recreation and successful completion so force neither escapes its owner nor persists forever.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Watcher/candidate maintenance mocks follow the maintenance admission method. Transfer, cancellation, accounting, collection and candidate scope outcomes remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-J3 (Reconciliation re-admission livelocks a migration) Re-admission of an already admitted, unchanged file (same source hash, collection, root, candidate ownership) keeps the existing PENDING/PROCESSING row, revision and journal obligation so an in-flight claim still publishes; genuine changes still supersede; a superseded claim's discard reports lost ownership.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C23. Preserve \u2014 admission accounting: callers require accepted counts to match batch size even when work is already covered; check mixed new/preserved batches report coverage correctly without inflating queue depth, pending bytes, or newly-enqueued metrics.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsRecordedTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Remove the retired no-op force-sink constructor argument; recorded ownership, cancellation, root guards and progress assertions remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C15. Preserve \u2014 force authority and lifecycle boundaries: recorded force is frozen in the issued claim, planned-source work already bypasses unchanged detection, and exclusions still precede extraction; check these paths alongside deletion/recreation and successful completion so force neither escapes its owner nor persists forever.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Admitted source identity must match in addition to stamp/size/chunks; matching identity cannot bypass incomplete-chunk repair.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D17 (Equal-size edits with preserved timestamps remain invisible) Rewrite equal-length content, restore its timestamp and require automatic ingestion to publish it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. Preserve \u2014 existing reasons to repair unchanged bytes: collection changes, missing stored size, incomplete chunks, and committed deletion/replacement already invalidate admission; check stronger source proof does not bypass these checks, including null/blank default collection equivalence.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D17 (Equal-size edits with preserved timestamps remain invisible) Rewrite equal-length content, restore its timestamp and require automatic ingestion to publish it.",
          "label": "agent choice"
        },
        "reproduction": "Seed complete chunks with one source hash, then query unchanged admission with a different hash and identical metadata. The old metadata-only overload certifies it."
      },
      "reproduction": "Seed complete chunks with one source hash, then query unchanged admission with a different hash and identical metadata. The old metadata-only overload certifies it.",
      "evidence": "gates/test-intent/evidence/ingfix-wp6.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java#admittedByteIdentityMustMatchAlongsideSizeTimestampAndCompleteChunks"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Equal-metadata edits and missing source proof publish fresh searchable parent/chunk results. Force survives immediate consumption, retry and restart, including committed publication before durable completion. Reconciliation preserves only identical obligations and reports lost ownership on supersession. Existing fixture sources now supply byte proof. The V25 upgrade watcher exercise covers changed legacy failures.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D17 (Equal-size edits with preserved timestamps remain invisible) Rewrite equal-length content, restore its timestamp and require automatic ingestion to publish it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D20 (Ordinary force intent races admission and disappears on restart) Consume immediately after enqueue but before force marking, and separately restart with forced jobs pending.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D21 (A failed first attempt consumes force intent permanently) Transiently fail the first forced extraction of an unchanged file and require extraction on retry.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C9. Preserve \u2014 mutation and unreadability during freshness checking: hashing introduces another source-read interval, while extraction already checks source changes before and after parsing; inject mutation, deletion, and read failure and require retry/stale handling rather than a false unchanged acknowledgment.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-J3 (Reconciliation re-admission livelocks a migration) Re-admission of an already admitted, unchanged file (same source hash, collection, root, candidate ownership) keeps the existing PENDING/PROCESSING row, revision and journal obligation so an in-flight claim still publishes; genuine changes still supersede; a superseded claim's discard reports lost ownership.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C13. Implied \u2014 force survives commit and completion-record failures: publication and queue completion have separate owners; interrupt before commit and after Lucene commit but before durable queue completion, then verify recovery neither loses the obligation nor falsely completes a newer admission.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C14. Implied \u2014 overlapping force and routine admissions: accepted force must not disappear when routine rediscovery follows, and an older callback must not consume a later force request; interleave both orders around claim, publication, and completion and verify the final accepted revision.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C20. Implied, partly broken \u2014 changed sources and deliberate retries remain admissible: unchanged-work preservation must distinguish changed content from duplicate discovery; the current maintenance predicate does not inspect source identity at all. Check changed bytes\u2014including equal metadata\u2014can be admitted, and explicit retry/force still resets the intended failure run.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C7. Preserve \u2014 truly unchanged, current-policy documents: stronger freshness checks must retain the existing skip benefit; check matching source, policy, collection, and complete chunks avoids extraction on repeated ingestion and after reopening.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. Preserve \u2014 existing reasons to repair unchanged bytes: collection changes, missing stored size, incomplete chunks, and committed deletion/replacement already invalidate admission; check stronger source proof does not bypass these checks, including null/blank default collection equivalence.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D17 (Equal-size edits with preserved timestamps remain invisible) Rewrite equal-length content, restore its timestamp and require automatic ingestion to publish it.",
          "label": "agent choice"
        },
        "reproduction": "Run the queued extraction/publication pipeline across restored timestamps, restart, force failure/retry, admission callbacks and held publication. The before-state skips changed bytes or loses accepted force, and reconciliation replaces live ownership."
      },
      "reproduction": "Run the queued extraction/publication pipeline across restored timestamps, restart, force failure/retry, admission callbacks and held publication. The before-state skips changed bytes or loses accepted force, and reconciliation replaces live ownership.",
      "evidence": "gates/test-intent/evidence/ingfix-wp6.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#missingStoredSourceHashRepairsEqualMetadataEditOnce",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#equalLengthEditsWithRestoredTimestampsPublishAndPersistTwice",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#forcedPendingAndProcessingAdmissionsSurviveRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#firstForcedExtractionFailureDoesNotConsumeForceOnRetry",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#sourceHashFailureRetriesWithoutAcknowledgingUnchangedOrConsumingForce",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#committedIndexBeforeQueueCompletionRetainsForceOnRecovery",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#routineAdmissionCannotWeakenPendingForceAndOldCompletionCannotConsumeNewForce",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#reconciliationDuringSlowPublicationPreservesOnlyTheSameSource",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#immediateConsumptionSeesForceAtTheAdmissionBoundary",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#liveWatcherPublishesTwoEqualLengthEditsWithRestoredTimestamps"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/MigrationEnumerationCompletenessTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Restarted enumeration retains completed jobs and replay witnesses while covering retained and new files accurately.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D25 (Migration restart re-admits unchanged completed files) Repeat identical enumeration after completing a candidate file and require its completed revision to survive.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C28. Preserve \u2014 migration coverage is not extraction count: covered files may be retained rather than newly queued, but enumeration must still close correctly; check empty roots, single-file roots, mixed retained/new files, unreadable sources, short admission, and restart through promotion.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D25 (Migration restart re-admits unchanged completed files) Repeat identical enumeration after completing a candidate file and require its completed revision to survive.",
          "label": "agent choice"
        },
        "reproduction": "Complete an enumerated candidate, enumerate again, then reopen and add a new file. The before-state replaces the completed admission and its replay witness."
      },
      "reproduction": "Complete an enumerated candidate, enumerate again, then reopen and add a new file. The before-state replaces the completed admission and its replay witness.",
      "evidence": "gates/test-intent/evidence/ingfix-wp6.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/MigrationEnumerationCompletenessTest.java#restartedEnumerationRetainsCompletedCoverageAndAdmitsOnlyNewFiles"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Routine sync retains a previously buffered forced traversal and its attribution.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D22 (Buffered routine sync weakens a pending forced sync) Buffer forced then routine sync for the same root and require forced replay.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C17. Preserve \u2014 sync force retains its existing meaning: the narrowed D22 claim concerns traversal/pacing, and forced sync deliberately relies on the extractor\u2019s unchanged shortcut; verify forced replay traverses the root while genuinely unchanged files can still skip extraction.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D22 (Buffered routine sync weakens a pending forced sync) Buffer forced then routine sync for the same root and require forced replay.",
          "label": "agent choice"
        },
        "reproduction": "Buffer forced sync with provenance then routine sync. The before-state replaces force=true with false."
      },
      "reproduction": "Buffer forced sync with provenance then routine sync. The before-state replaces force=true with false.",
      "evidence": "gates/test-intent/evidence/ingfix-wp6.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceTest.java#syncDirectoryBuffersSwitchOpWhenSwitchingAndQueueIsSqlite"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerSelectedForceCompatibilityTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Ordinary selected force belongs to its durable admission; recorded force retains recorded ownership. Selected work still cannot certify untouched vectors or stamp global compatibility.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D20 (Ordinary force intent races admission and disappears on restart) Consume immediately after enqueue but before force marking, and separately restart with forced jobs pending.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C15. Preserve \u2014 force authority and lifecycle boundaries: recorded force is frozen in the issued claim, planned-source work already bypasses unchanged detection, and exclusions still precede extraction; check these paths alongside deletion/recreation and successful completion so force neither escapes its owner nor persists forever.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D20 (Ordinary force intent races admission and disappears on restart) Consume immediately after enqueue but before force marking, and separately restart with forced jobs pending.",
          "label": "agent choice"
        },
        "reproduction": "Submit ordinary force and inspect its claim and marking calls. The before-state uses an after-enqueue in-memory mark instead of a durable force bit."
      },
      "reproduction": "Submit ordinary force and inspect its claim and marking calls. The before-state uses an after-enqueue in-memory mark instead of a durable force bit.",
      "evidence": "gates/test-intent/evidence/ingfix-wp6.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerSelectedForceCompatibilityTest.java#selectedForceCannotAttestToUntouchedVectors"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceForceReindexWiringTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Both ordinary public force entry points admit force atomically before a consumer can claim it. The old markForced callback is retired.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D20 (Ordinary force intent races admission and disappears on restart) Consume immediately after enqueue but before force marking, and separately restart with forced jobs pending.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D20 (Ordinary force intent races admission and disappears on restart) Consume immediately after enqueue but before force marking, and separately restart with forced jobs pending.",
          "label": "agent choice"
        },
        "reproduction": "Record admitted entries at the public scan and batch boundaries. The before-state entries carry no force obligation and rely on a later callback."
      },
      "reproduction": "Record admitted entries at the public scan and batch boundaries. The before-state entries carry no force obligation and rely on a later callback.",
      "evidence": "gates/test-intent/evidence/ingfix-wp6.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceForceReindexWiringTest.java#forceReindexScanAdmitsForceAtomically",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceForceReindexWiringTest.java#submitBatchAdmitsForceAtomically"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Every FORCE_REINDEX scan entry carries force, and routine scans carry none. Constructor calls adapt to removal of the force sink.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D20 (Ordinary force intent races admission and disappears on restart) Consume immediately after enqueue but before force marking, and separately restart with forced jobs pending.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C15. Preserve \u2014 force authority and lifecycle boundaries: recorded force is frozen in the issued claim, planned-source work already bypasses unchanged detection, and exclusions still precede extraction; check these paths alongside deletion/recreation and successful completion so force neither escapes its owner nor persists forever.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D20 (Ordinary force intent races admission and disappears on restart) Consume immediately after enqueue but before force marking, and separately restart with forced jobs pending.",
          "label": "agent choice"
        },
        "reproduction": "Scan two files recursively with FORCE_REINDEX and inspect each admitted entry. The before-state entries have no durable force bit."
      },
      "reproduction": "Scan two files recursively with FORCE_REINDEX and inspect each admitted entry. The before-state entries have no durable force bit.",
      "evidence": "gates/test-intent/evidence/ingfix-wp6.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java#forceReindexScanCarriesForceInEveryAdmission"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerWatcherEventOwnershipTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Watcher maintenance avoids replacing proven unchanged serving work, admits both equal-metadata edits, and excludes cloud placeholders before hashing. The nested-lock fixture follows the maintenance queue seam.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D17 (Equal-size edits with preserved timestamps remain invisible) Rewrite equal-length content, restore its timestamp and require automatic ingestion to publish it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-J3 (Reconciliation re-admission livelocks a migration) Re-admission of an already admitted, unchanged file (same source hash, collection, root, candidate ownership) keeps the existing PENDING/PROCESSING row, revision and journal obligation so an in-flight claim still publishes; genuine changes still supersede; a superseded claim's discard reports lost ownership.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C9. Preserve \u2014 mutation and unreadability during freshness checking: hashing introduces another source-read interval, while extraction already checks source changes before and after parsing; inject mutation, deletion, and read failure and require retry/stale handling rather than a false unchanged acknowledgment.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D17 (Equal-size edits with preserved timestamps remain invisible) Rewrite equal-length content, restore its timestamp and require automatic ingestion to publish it.",
          "label": "agent choice"
        },
        "reproduction": "Deliver repeated watcher upserts with matching metadata/source hash, then changed equal-length bytes twice. The before-state always admits unchanged watcher work through ordinary admission."
      },
      "reproduction": "Deliver repeated watcher upserts with matching metadata/source hash, then changed equal-length bytes twice. The before-state always admits unchanged watcher work through ordinary admission.",
      "evidence": "gates/test-intent/evidence/ingfix-wp6.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerWatcherEventOwnershipTest.java#unchangedServingWatcherEventsDoNotReplaceWorkAndEqualMetadataEditsAreAdmitted"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerWatcherRestoredMtimeTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Methvin duplicate filtering retains equal-size restored-timestamp edits, alongside the existing changed-size arm.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D17 (Equal-size edits with preserved timestamps remain invisible) Rewrite equal-length content, restore its timestamp and require automatic ingestion to publish it.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D17 (Equal-size edits with preserved timestamps remain invisible) Rewrite equal-length content, restore its timestamp and require automatic ingestion to publish it.",
          "label": "agent choice"
        },
        "reproduction": "Drive the real configured Methvin duplicate filter with MODIFY after restoring mtime. The old size/mtime fingerprint suppresses equal-size edits."
      },
      "reproduction": "Drive the real configured Methvin duplicate filter with MODIFY after restoring mtime. The old size/mtime fingerprint suppresses equal-size edits.",
      "evidence": "gates/test-intent/evidence/ingfix-wp6.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerWatcherRestoredMtimeTest.java#methvinModifyDeduplicationKeepsEditsWithRestoredMtime"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReleasedRevisionWatcherAdmissionTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "A watcher maintenance admission whose hash read fails cannot use a readable matching metadata witness as proof that a released FAILED row is unchanged. It becomes pending and claimable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C9. Preserve \u2014 mutation and unreadability during freshness checking: hashing introduces another source-read interval, while extraction already checks source changes before and after parsing; inject mutation, deletion, and read failure and require retry/stale handling rather than a false unchanged acknowledgment.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C20. Implied, partly broken \u2014 changed sources and deliberate retries remain admissible: unchanged-work preservation must distinguish changed content from duplicate discovery; the current maintenance predicate does not inspect source identity at all. Check changed bytes\u2014including equal metadata\u2014can be admitted, and explicit retry/force still resets the intended failure run.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C9. Preserve \u2014 mutation and unreadability during freshness checking: hashing introduces another source-read interval, while extraction already checks source changes before and after parsing; inject mutation, deletion, and read failure and require retry/stale handling rather than a false unchanged acknowledgment.",
          "label": "agent choice"
        },
        "reproduction": "Seed a released-format FAILED revision with no stored source hash and matching metadata, make admissionIdentity return null, and call the real maintenance admission API used by the watcher. d5c51c128c preserves FAILED from the metadata witness alone."
      },
      "reproduction": "Seed a released-format FAILED revision with no stored source hash and matching metadata, make admissionIdentity return null, and call the real maintenance admission API used by the watcher. d5c51c128c preserves FAILED from the metadata witness alone.",
      "evidence": "gates/test-intent/evidence/ingfix-wp6.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReleasedRevisionWatcherAdmissionTest.java#failedAdmissionHashCannotCertifyUnchangedReleasedFailure"
      ]
    }
  ]
}
```

## Acceptance records
