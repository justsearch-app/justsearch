---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: 01a199ec-b86b-70c3-99f0-f26a2f40da52
---

# Test-intent entries

The existing crash harness gains an ordered prefix/collection removal followed by a restoring root
sync. Its existing checks and synchronization remain. The isolated lifecycle fault tests are reported
as exempt by the gate; their repair checks are also included in the verifier's execution list.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SyncRootPromotionCrashRecoveryTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D94 (Ordered delete-then-sync recovery cannot recognize restored survivors) Retain DELETE_PREFIX then restoring SYNC_ROOT across promotion/restart and require settlement with survivors intact.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no hang",
        "reproduction": "Accept a prefix deletion and a collection deletion, each followed by a restoring force SYNC_ROOT; kill the Engine after the durable generation pointer moves but before receipt acknowledgement. Restart and compare publication, searchable survivors and receipt settlement with an uninterrupted migration using the same journal."
      },
      "reproduction": "broadDeleteThenRestoringSyncPublishesAfterPromotionCrash uses the real process-death harness at migration-after-pointer-commit and compares the restarted generation with an uninterrupted control.",
      "evidence": "gates/test-intent/evidence/ingfix-wp10.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SyncRootPromotionCrashRecoveryTest.java#broadDeleteThenRestoringSyncPublishesAfterPromotionCrash"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/TerminalWriterFailureTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D100 (Lucene close loses both errors and retry handles) Fail writer close once and require reported failure plus a second close reaching that writer.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "Mark a real writer terminal, claim its failure notification, then close it while it throws. The old close clears the snapshot before the throw, losing cleanup owners. A second close must finish cleanup and late NRT observations must remain consumed exactly once."
      },
      "reproduction": "claimedRuntimeConsumesLateNrtFailureAcrossRetriedCleanup injects a real terminal writer, observes the close failure, checks the retained snapshot and retries. It preserves both late-NRT routing assertions, adds the retry and checks the cleared snapshot only after successful cleanup.",
      "evidence": "gates/test-intent/evidence/ingfix-wp10.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/TerminalWriterFailureTest.java#claimedRuntimeConsumesLateNrtFailureAcrossRetriedCleanup"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RecordedWalkSealTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D98 (Reset succeeds after queue-clear failure) Inject queue-clear failure with pending work and require reset failure.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "A trigger aborts the administrative clear transaction while a recorded claim is processing. The old clear returns zero, indistinguishable from a successful empty clear, permitting reset to delete the index."
      },
      "reproduction": "administrativeSkipLateIndexedRollbackRetainsOwnerAndBlocksSeal now observes the propagated clear failure for clearAll. Prefix/exact removal return checks and all recorded owner, rollback, late-outcome and seal assertions remain.",
      "evidence": "gates/test-intent/evidence/ingfix-wp10.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RecordedWalkSealTest.java#administrativeSkipLateIndexedRollbackRetainsOwnerAndBlocksSeal"
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
  "session": "b8cc3e05-717a-4896-808f-47e6a0867dc1",
  "verdict": "accept",
  "digest": "sha256:5fcdc10296c9ec7085e38898b6c447ac4cfc4ebdec8b389bada667f071b77ea7",
  "note": "Independent verifier (Claude) accepted all three entries on 3d9d0fd1b3: each fails on main by assertion for the defect under test and passes after, existing assertions retained; 23 repair checks pass; exempt files earlier verified isolated by verifier-1 and verifier-2."
}
```
