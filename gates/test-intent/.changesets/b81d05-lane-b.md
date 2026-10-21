---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-20261020
---

# Watcher delete and reload safety

The builder does not supply acceptance. New checks include real-file, SQLite/Lucene, child-process crash and real-runtime swap evidence. Windows and unavailable mount cases remain explicitly unverified in the lane report.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteCrashRecoveryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/WatcherReloadRetryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/WatcherJournalFormatTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Watcher deletion admission and all three exact replay routes preserve recreated files and exact newer queue revisions, refuse uncertain/rebound roots, and delete only the confirmed absent file. Linux real-file permission, case and replacement checks retain source rows on uncertainty. Reload debts survive failed opening, failed reconstruction and process death until complete committed reconciliation; root removal cancels debt and collection attribution survives consecutive swaps. Actual child JVM halts cover durable buffering, Lucene deletion, commit and exact journal cleanup while siblings and parent/chunk atomicity survive. V1 populated queues upgrade without losing receipts, order, generations, attempts or exact replacement tokens. Queue version 24 (after main's V23 admission counter) refuses older readers before they can treat versioned DELETE JSON as a raw ID. Baseline enumeration admits a recreated source over a conditional DELETE. The fixed schema-version assertion in the existing V11 upgrade check now pins 24, preserving its queue/identity and future-version refusal assertions. A V22 install and a queue at main's V23 both upgrade to V24, keeping and continuing V23's admission count. Partial chunk replacement belongs to Lane A; watcher replay no longer removes jobs, so there is no watcher queue-removal cut to exercise.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I6",
          "quote": "A watcher DELETE that arrives during SWITCHING without a writable serving index is confirmed absent inside the fence, before it is buffered. Replay applies it to that exact file only after re-checking absence. A file recreated before admission, after buffering, or before a restart keeps its rows and its queued UPSERT.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I6",
          "quote": "The same watcher receipt goes through each route: ordinary replay, candidate refusal, and promoted-pointer recovery (settleCommittedNativeFiles). The file is recreated, or the root proof withdrawn, before replay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I7",
          "quote": "Explicit (user or API) deletes keep their semantics.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I8",
          "quote": "A watcher DELETE settled while the runtime is being swapped is never reported done without its effect. After the swap, the successor reconciles the root and the rows are gone. If the swap fails or is retried, the delete is still applied once a runtime is available. Throwing without a retry is not enough.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I8",
          "quote": "Covers a failed opener, a failed service reconstruction, consecutive swaps, and process death before reconciliation finishes. Obligations are kept until their effects are confirmed. Root removal and collection attribution are respected, and an unwatched root is never revived.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I10",
          "quote": "Populated databases from older versions upgrade with order, generation, payload readers and rollback or retry preserved. There is a fixture for every new persisted format (JobQueueMigrationTest, exact-replay cleanup tests).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I12",
          "quote": "A kill after a partial chunk replacement, durable buffering, Lucene deletion, queue removal or commit, or before journal cleanup, plus a recreate after the final absence probe with a newer queued UPSERT. Each leaves exact revisions and sibling files correct after a reopen.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b81d05-lane-b.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#recreatedBeforeSwitchingAdmissionPreservesExactUpsertReceipt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#legacyDeleteOfRecreatedFilePreservesRowsAndQueuedRevisionAfterReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#watcherReplayDeletesOnlyExactAbsentFileAndRetiresItsReceipt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#unavailableRootRetainsLegacyRowsAndExactReceipt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#promotedPointerRecoverySkipsRecreatedLegacyFile",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#drainedRuntimeRetainsDeleteUntilSuccessorReconcilesRoot",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#watcherReceiptPreservesRecreationAcrossEveryReplayRoute",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#watcherReceiptRetainsUnavailableRootAcrossEveryReplayRoute",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#recreationAfterFinalAbsenceProbeKeepsNewerQueuedRevision",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#reloadReconciliationKeepsCollectionAndConsecutiveDeletes",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#removedRootNeverRevivesDuringReloadReconciliation",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#versionedWatcherReceiptSurvivesReopenWithoutLosingRecreation",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#linuxUnreadableRootKeepsExactDeleteReceiptAndRows",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#linuxUnreadableSourceKeepsRowsAndRefreshWork",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#watcherReceiptRejectsReboundDirectoryIncarnation",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteCrashRecoveryTest.java#recreatedSourceAndNewerQueueRevisionSurviveProcessDeath",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteCrashRecoveryTest.java#reloadDebtSurvivesProcessDeathBeforeReconciliation",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/WatcherReloadRetryTest.java#failedOpenerThenFailedReconstructionRetainsDeleteUntilRetry",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/WatcherJournalFormatTest.java#populatedLegacyDatabasePreservesReceiptsAndRequiresNewReaderAfterUpgrade",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/WatcherJournalFormatTest.java#baselineEnumerationCannotSuppressARecreatedLegacyWatcherFile",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java#migratesV11ToCurrentPreservingQueueAndIdentityRowsAndRefusesTheNextVersion"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/AcceptedProjectionBroadReplayTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/CommittedNativeProjectionEffectsTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/MigrationEnumerationCompletenessTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/SwitchBufferConcurrentReplayTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/SwitchBufferStrictReplayTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduGenerationReplayTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceVduHardeningTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceDocumentIdentityTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootRetirementTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Existing administrative DELETE replay fixtures now encode EXPLICIT origin in the versioned payload; watcher admission observations decode and assert the original path plus WATCHER origin. Unchanged live deletion, prune, projection, journal-order, conditional-cleanup, notification and failure assertions remain. SwitchBufferConcurrentReplayTest decodes the retained payload before comparing its original logical path. VduGenerationReplayTest uses the same canonical path: key as production exact-delete admission, preserving its ineligible-VDU/eligible-delete interleaving and all prior effect assertions. WorkerIngestServiceVduHardeningTest decodes its former raw payload ID assertion and additionally checks explicit origin. WorkerIngestServiceDocumentIdentityTest decodes exact DELETE payloads before its unchanged set comparison. EngineRootRetirementTest opens the real queue before creating its Mockito spy, so constructor-captured journal helpers share the open connection instead of an unopened original owner; all removal, deadline and producer-fence assertions remain. No test, condition, tag, fault injection or scope exclusion is removed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I7",
          "quote": "Explicit (user or API) deletes keep their semantics.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-r5",
  "verdict": "accept",
  "digest": "sha256:7e95d0978edc9e1b25849028c4f0b4c6d9d24dc65c5ce1af7a81b79e480e01b4",
  "note": "Accepted under narrowed scope. The missing-root-sync replay-uncertainty latch and follow-on rebuild HTTP 500 move to migration recovery; the shared help-document drop moves to follow-up. Verification limits remain in tmp/verify-r5/scenario-matrix.json."
}
```
