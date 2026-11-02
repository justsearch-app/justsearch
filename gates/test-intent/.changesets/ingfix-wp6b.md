---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: ingfix-wp6b-builder
---

# Test-intent entries

The isolated new tests are exempt: ExtractionPolicyFreshnessTest (C6–C8),
SyncExtractionPolicyTest (C5–C7), UnchangedSourceReadFailureTest (C9/P5), and
ExtractionPolicyMaintenanceAdmissionTest (C5/P4).
They use only temporary files/indexes and scoped mocks, restoring static mock state on close.
Their repair checks are included in the handoff check list. Preservation checks are not newChecks.
Execution evidence and independent acceptance are left to the verifier as requested.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionFidelityIndexingTest.java"
      ],
      "class": "New",
      "behaviour": "Ordinary ingestion repairs complete historical text, preview, chunks and policy metadata for older, missing and changed configured policy identities. Repeated ingestion and reopened current extraction skip; missing source-hash evidence is a preservation case already repaired by baseline ingestion. The old force-only requirement is replaced by S-D18; force preservation remains in JobBatchExtractorForcedPathTest.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D18 (Extraction fixes do not repair unchanged older results) Re-ingest an unchanged older-policy document and require corrected text and policy metadata.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C4. Broken — unchanged source with an older extraction policy: D18 explicitly requires “corrected text and policy metadata,” while `DocumentFieldOps:601–646` never compares policy; seed a complete older-policy document and check corrected content, preview, chunks, and stored policy after ordinary ingestion and restart.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C6. Implied — missing policy/source evidence and configured policies: legacy documents may lack fields, and `TikaExtractionPolicy.fromWorkerLimits` gives configured limits their own persisted identity; check missing policy/hash evidence triggers repair, changed effective policy invalidates old extraction, and matching effective policy becomes stable after repair.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp6b.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionFidelityIndexingTest.java#ordinaryIngestionRepairsHistoricalExtractionAndStaysCurrentAfterReopening"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OriginBoundaryAdmissionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingDocumentOpsScalarIntegrationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionReconciliationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionScanSnapshotTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsCandidateDiscoveryTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerWatcherEventOwnershipTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Unchanged/fresh document fixtures now explicitly carry their existing semantic precondition as the current extraction policy and existing source hash. Extractor and watcher mocks use the policy-aware overload, preserving all existing fault injections, expected outcomes and conditions. Legacy chunk-backfill instrumentation permits the policy accessor only and still prohibits extraction or any other interaction. Collection/declaration/candidate/size/mtime/quality/force tests retain their oracles.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C7. Preserve — truly unchanged, current-policy documents: stronger freshness checks must retain the existing skip benefit; check matching source, policy, collection, and complete chunks avoids extraction on repeated ingestion and after reopening.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. Preserve — existing reasons to repair unchanged bytes: collection changes, missing stored size, incomplete chunks, and committed deletion/replacement already invalidate admission (`DocumentFieldOps:602–648`, `JobBatchExtractor:310–313`); check stronger source proof does not bypass these checks, including null/blank default collection equivalence.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SplitSurrogateQueuedRestartTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Windows1252QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Queued unchanged older-policy Hebrew, Windows-1252 and split-surrogate documents retain all stored state upon opening, then ordinary ingestion repairs text, preview, derived fields and chunks and records the current policy; repaired state and historical ingestion receipts survive another restart. Explicit force still reprocesses the repaired document. Newly indexed current-policy ASCII-first UTF-8 remains skipped. The prior requirement to retain older-policy mojibake or replacement characters through ordinary processing is superseded by S-D18.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D18 (Extraction fixes do not repair unchanged older results) Re-ingest an unchanged older-policy document and require corrected text and policy metadata.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C4. Broken — unchanged source with an older extraction policy: D18 explicitly requires “corrected text and policy metadata,” while `DocumentFieldOps:601–646` never compares policy; seed a complete older-policy document and check corrected content, preview, chunks, and stored policy after ordinary ingestion and restart.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C6. Implied — missing policy/source evidence and configured policies: legacy documents may lack fields, and `TikaExtractionPolicy.fromWorkerLimits` gives configured limits their own persisted identity; check missing policy/hash evidence triggers repair, changed effective policy invalidates old extraction, and matching effective policy becomes stable after repair.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp6b.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#ordinaryQueuedWorkRepairsOlderPolicyAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#ordinaryQueuedWorkRepairsOlderPolicyAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SplitSurrogateQueuedRestartTest.java#ordinaryReprocessingRepairsLegacyReplacementsAndPolicyAcrossRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SplitSurrogateQueuedRestartTest.java#forcedReextractionRepairsStoredContentHashAndPreviewAcrossRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Windows1252QueuedRestartTest.java#queuedRestartRepairsLegacyPolicyThroughOrdinaryAndForcedReextraction"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ExtractionRecoveryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeProbeTest.java"
      ],
      "class": "New",
      "behaviour": "The authentic release upgrade recovery repairs indexed sources whose bytes are unchanged but extraction evidence is older or absent. The real small release checks sandbox execution and the stored current policy. The manual 700k-scale probe shares that contract; its no-extraction assertion is replaced by policy-repair progress, while parser success, bounded drain reporting and owned-thread shutdown checks remain. The manual scale main is not run by the module suite or this assignment.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D18 (Extraction fixes do not repair unchanged older results) Re-ingest an unchanged older-policy document and require corrected text and policy metadata.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C4. Broken — unchanged source with an older extraction policy: D18 explicitly requires “corrected text and policy metadata,” while `DocumentFieldOps:601–646` never compares policy; seed a complete older-policy document and check corrected content, preview, chunks, and stored policy after ordinary ingestion and restart.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C6. Implied — missing policy/source evidence and configured policies: legacy documents may lack fields, and `TikaExtractionPolicy.fromWorkerLimits` gives configured limits their own persisted identity; check missing policy/hash evidence triggers repair, changed effective policy invalidates old extraction, and matching effective policy becomes stable after repair.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp6b.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ExtractionRecoveryTest.java#recoveryWalkExtractsChangedFilesAndRepairsOlderExtractionPolicy"
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
  "session": "01a19aba-7b4d-7580-8755-4a2ac9a9bd77",
  "verdict": "accept",
  "digest": "sha256:e7cdac3b56e37ba7bde5ed662b0713d306584ad17cefad329719b75501e8c9d0",
  "note": "Independent verifier (Codex) accepted all four entries and the four exempt files: 23 repair cases fail before by assertion and pass after, 25 preservation cases pass before and after; policy identity deterministic and unchanged from main; no parent-write path drops identity or source hash."
}
```
