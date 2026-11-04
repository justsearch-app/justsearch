---
schema: test-intent.v1
task: fu-4
author-role: builder
author-session: 01a1a735-c9b4-7a60-bfc5-917e3e5c0704
---

# Test-intent entries

Base: `3689ad8bf6e1aa86339dbd538a0bff49daa0fe80` (this assignment's worktree HEAD).
The source is the orchestrator's owner-adopted “Revision after the challenger” in task fu-4.
The builder leaves execution evidence and acceptance to the orchestrator, as assigned.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RetirementMaintenanceProcessTest.java"
      ],
      "class": "New",
      "behaviour": "FUD1 through FUD4: retirement maintenance retains valid pending/claimed ROOT and exact obligations, attribution, retry history and receipts across fresh ROOT journals, reconciliation and reopen. Real timeboxed Tika extraction and Lucene publication complete once per file, including later queued work. The running upgraded application uses a real delayed process child and real maintenance service calls 60 seconds apart. Preservation checks retain changed-source, different-obligation, forced/live, recorded, retry, deletion, recovery and ordinary-path rules.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-4",
          "scenario": "FUD1",
          "quote": "Claimed ROOT work: an unindexed, unrecorded file with a valid ROOT owner stays owned across one and several retirement-maintenance admissions, each with the newly journalled ROOT receipt; its original extraction publishes once",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-4",
          "scenario": "FUD2",
          "quote": "Ready pending work: the same admissions keep a valid ready PENDING unit, its attribution and retry history; after reopen and retirement reconciliation it is still claimable under its retained owner.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-4",
          "scenario": "FUD3",
          "quote": "Alternate retirement admission: the same preservation through enqueueRelease030LiveSyncEntries, including an existing exact receipt, which maintenance does not replace (nor its source witness).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-4",
          "scenario": "FUD4",
          "quote": "Batch progress: several successful sub-minute extractions keep one batch outstanding across several admissions (arranged so main re-admits before the batch writes); every file publishes with one extraction each, and a later batch progresses.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-4",
          "scenario": "P1",
          "quote": "Changed sources (incl. same-size, restored-mtime changes during extraction), different collection/root obligations and explicit live or forced admissions keep their semantics.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-4",
          "scenario": "P2",
          "quote": "Protected retry waits (PARSER_TIMEOUT, IO_FAILED), FAILED rows, recorded walks, unknown or mismatched owners, later deletes and exact admissions, crash recovery and shutdown ownership keep their rules; retained older ROOT owners survive later ROOT receipts; replay and retirement completion do not redo published files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-4",
          "scenario": "P3",
          "quote": "Ordinary periodic maintenance keeps unchanged claims and indexes newly discovered missing files.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/fu-4.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#claimedRootKeepsOriginalExtractionAcrossFreshReceipts",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#readyPendingRetainsHistoryAndCanBeClaimedAfterReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#alternateMaintenanceRetainsRootAndExactReceiptWitness",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#legacyRootWithoutSourceIdentityKeepsOriginalExtraction",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#hintlessAlternateMaintenanceRetainsReservedExactOwner",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#outstandingBatchPublishesOnceAndLaterBatchProgresses",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RetirementMaintenanceProcessTest.java#retirementProcessBatchSurvivesTwoRealIntervalSyncs"
      ]
    },
    {
      "items": [
        "scripts/ci/stress-suite-policy.v1.json"
      ],
      "class": "Structural rule kept",
      "rule": "Every Java @Tag(\"stress\") test under modules/**/src/test is registered in the explicit stress-suite policy with its module task, owner and reason.",
      "whySurvives": "Register the existing RetirementMaintenanceProcessTest under modules/indexer-worker, as named in the brief. Its real upgrade boot, delayed extraction child and two real 60-second maintenance sync intervals remain stress-tagged. No test, expectation, tag or execution condition changes; no current CI job runs the listed tasks, so registration adds no CI load.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "yea you can go for all fice",
          "location": "task fu-4 user turn approving the adopted scenarios"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-4",
          "scenario": "FUD4",
          "quote": "Batch progress: several successful sub-minute extractions keep one batch outstanding across several admissions (arranged so main re-admits before the batch writes); every file publishes with one extraction each, and a later batch progresses.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-4",
          "scenario": "P3",
          "quote": "Ordinary periodic maintenance keeps unchanged claims and indexes newly discovered missing files.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    }
  ]
}
```

## Verification commands

Repair evidence (the component checks must fail by assertion on the base; the retirement
process check also crosses the real upgrade, parser-child and publication boundary):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY ORG_GRADLE_PROJECT_includeStress=true \
  js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/fu-4.json \
  --before 3689ad8bf6e1aa86339dbd538a0bff49daa0fe80 \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#claimedRootKeepsOriginalExtractionAcrossFreshReceipts \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#readyPendingRetainsHistoryAndCanBeClaimedAfterReopen \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#alternateMaintenanceRetainsRootAndExactReceiptWitness \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#legacyRootWithoutSourceIdentityKeepsOriginalExtraction \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#hintlessAlternateMaintenanceRetainsReservedExactOwner \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#outstandingBatchPublishesOnceAndLaterBatchProgresses \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RetirementMaintenanceProcessTest.java#retirementProcessBatchSurvivesTwoRealIntervalSyncs
```

Preservation evidence (candidate; the builder separately runs the same selected component
preservation checks on a shared base clone, with its own build directory):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY ORG_GRADLE_PROJECT_includeStress=true \
  js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/fu-4-preservation.json \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#differentObligationStillReplacesTheClaim \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RetirementMaintenanceAdmissionTest.java#changedDuringExtractionNeverPublishesObsoleteBytes \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RetirementMaintenanceProcessTest.java#ordinaryProcessBatchSurvivesTwoRealIntervalSyncs \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/MaintenanceSyncClaimTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RetirementClaimOwnershipTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030LiveSyncAdmissionTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RootMemberIdentityTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/BatchClaimExitTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSyncPolicyTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RecoveryShutdownTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ReplayRetentionTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/Release030ForgetRootReplayTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030CorrectionHistoryTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPublicSyncTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#reconciliationDuringSlowPublicationPreservesOnlyTheSameSource \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopLifecycleFailureTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopCutoverPauseTest.java
```

The process checks are stress-tagged because each runs six 25-second parses through a real child.
The parent gates each parse over a loopback socket and drives the real maintenance service at
60-second intervals. This confirms the running worker application and extraction boundary; the
app-services scheduler itself is covered by the unchanged SyncOps preservation tests. The tests
use the authentic `switching` upgrade fixture in both trials, allowing retirement to complete
before the ordinary trial and retaining it through its real replay barrier in the retirement trial.
No shared dev stack or existing service is used.

Scenario mapping: FUD1 -> claimedRootKeepsOriginalExtractionAcrossFreshReceipts; FUD2 ->
readyPendingRetainsHistoryAndCanBeClaimedAfterReopen; FUD3 ->
alternateMaintenanceRetainsRootAndExactReceiptWitness; FUD4 ->
outstandingBatchPublishesOnceAndLaterBatchProgresses. Each compares stable queue fields rather
than last_updated. Legacy ROOTs without saved source identities exercise the ordinary metadata
fallback only after full receipt/claim authority validation; changed bytes still cross the real
extractor's before/after hash fence. The changed legacy source's real STALE_SOURCE deferral
is made due through its isolated SQLite row, without sleeping or resetting IO/parser waits. Hintless exact admissions keep reserved internal collections,
as native enqueue already requires. The process retirement check confirms the original owner publishes once.

P1 uses differentObligationStillReplacesTheClaim and changedDuringExtractionNeverPublishesObsoleteBytes for both retirement entry points and unchanged
RediscoveryAdmissionTest/Release030RetirementPublicSyncTest for ordinary and live/forced behavior.
P2 reuses Release030RetirementClaimOwnershipTest, Release030RootMemberIdentityTest,
Release030LiveSyncAdmissionTest, Release030RetirementSyncPolicyTest, BatchClaimExitTest,
Release030RecoveryShutdownTest, Release030ReplayRetentionTest, IndexingLoopLifecycleFailureTest and IndexingLoopCutoverPauseTest. P3 reuses
RediscoveryAdmissionTest, MaintenanceSyncClaimTest and ordinaryProcessBatchSurvivesTwoRealIntervalSyncs.
No Deliver item was moved to preservation.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a7ed-df61-7ff0-b1ec-ee351d71e4aa",
  "verdict": "accept",
  "digest": "sha256:9246473b0dfc753a56574ae64602ee92b5d8b59568537c7b20c05d4c813199ee",
  "note": "Codex acceptor on 685963f28a: entry 1 New accept; FUD1-FUD4 and P1-P3 citations match the settled scenarios; both evidence files valid against current sources. Component verification: Codex verifier-1 01a1a7bf on e3b90f7b04 (FUD1-4 fail before / pass after; P1-P3 pass both)."
}
```
