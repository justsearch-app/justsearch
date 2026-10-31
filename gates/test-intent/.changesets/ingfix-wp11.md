---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: wp11-test-intent-builder-rq94om1r
---

# WP11 test intent

Base: `5875daba6c`; reviewed candidate: `ae32f3f158`. The CLI ran successfully
and flagged 24 changed files. The four isolated new test files are exempt and
are not entered here. S-D118/S-D119 remain deferred.

No acceptance record is written by this builder.

Every `newChecks` id below names a repair check, with evidence at
`gates/test-intent/evidence/ingfix-wp11.json`. Parameterized JVM names retain the
method name so evidence selection cannot silently miss their invocations.
The compatibility patch adds data/accessor signatures and a legacy implementation
of the new parent-count/CUDA overload signatures, without the repaired decisions.

Explicit test-support overlays: `modules/app-inference/build.gradle.kts` and
`modules/app-inference/gradle.lockfile` (the added Logback test dependency and
its test classpath lock entries only). Test files named in check ids are copied into the before clone.
Frontend dependencies are copied into that clone, not shared for writes.

The following are preservation checks; they belong to the corresponding file's
entry and must pass before and after. They deliberately do not appear in
`newChecks`:

- `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#nullExecutable`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#noGgmlCudaDll`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#largeStaticDll`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#smallDllWithCudart`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#smallDllWithoutCudart`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#followsSearchServingReader`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#healthyReaderReportsScopedCount`
- `modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java#indexedRootViewSchema`
- `modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java#baselineCarriesCoverageFields`
- `modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#idleCounterIgnoresMeasurementChanges`
- `modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#failedActionWithMeasurementOnlyDiffCarriesNoNewerCounter`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountWithADifferentFingerprintIsNeverCompatible`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountWithNoRecordedFingerprintIsUnknownNotCompatible`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountNeverShowsZeroDocuments`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnparseableStoredFingerprintIsUnknownNotRebuildRequired`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#unreadableStoredMetadataIsUnknownNotLegacy`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#aReadableDifferentFingerprintWithDocumentsIsStillAMismatch`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aCountThatFailsFromTheFirstReadIsReportedUnknown`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aReadableCountIsNotReportedUnknown`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aMismatchWithAnUnreadableBrakeIsReportedBrakedNamingTheUnreadableBrake`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aMismatchWithNoBrakeRecordedIsStillAPlainMismatch`
- `modules/ui-web/src/shell-v0/state/aiStateStore.test.ts#aiStateStore — system-health verdict (595) clears ETA memory when enrichment becomes unknown`
- `modules/ui-web/src/shell-v0/state/aiStateStore.test.ts#aiStateStore — system-health verdict (595) clears ETA memory when enrichment becomes failed`
- `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not warn for an intentionally disabled stage`
- `modules/ui-web/src/api/contract.test.ts#Cross-language contract: StatusResponse vs generated statusResponseSchema Java-serialized StatusResponse passes Zod validation`
- `modules/ui-web/src/api/generated/schema-types/status-response.test.ts#generated statusResponseSchema (564 faithfulness) validates the real captured status wire fixture with no contract drift`

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Linux CUDA probing must not issue a Windows-DLL warning. Existing Windows/null oracles are preserved using an explicit Windows target; the missing-DLL check additionally observes its existing warning. Preservation checks, passing before and after: `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#nullExecutable`, `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#noGgmlCudaDll`, `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#largeStaticDll`, `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#smallDllWithCudart`, `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#smallDllWithoutCudart`.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D137 (Linux CUDA detection searches for a Windows DLL) Run detection for a valid Linux CUDA runtime and require no missing-Windows-DLL warning.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C34. **Windows CUDA checks continue working** — preserve missing-library diagnostics, small dynamic DLLs with/without runtime, large static DLLs, and null/invalid executable paths; a platform guard must not silently disable the existing Windows checks.",
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
          "choice": "S-D137 (Linux CUDA detection searches for a Windows DLL) Run detection for a valid Linux CUDA runtime and require no missing-Windows-DLL warning.",
          "label": "agent choice"
        },
        "reproduction": "Run the Linux-target fixture with libggml-cuda.so and libcudart.so.12 under both host names; the Windows-only before implementation logs ggml-cuda.dll not found."
      },
      "reproduction": "Run the Linux-target fixture with libggml-cuda.so and libcudart.so.12 under both host names; the Windows-only before implementation logs ggml-cuda.dll not found.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#linuxRuntimeDoesNotLogMissingWindowsDll"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Rebuild-brake transitions clear the preceding unavailable condition, unknown enrichment has its own condition, and unfinished chunk vectors assert/update/clear a structured condition. The old expectation that chunk degradation is silent is replaced under S-D121.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D120 (Health adapters omit the rebuild brake’s stopped-ingestion state) Feed rebuild-brake snapshots through both adapters and require an actionable stopped-ingestion condition.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D121 (Chunk-vector degradation produces no health condition) Require a structured condition for a nonempty applicable corpus with incomplete vectors.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
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
          "choice": "S-D120 (Health adapters omit the rebuild brake’s stopped-ingestion state) Feed rebuild-brake snapshots through both adapters and require an actionable stopped-ingestion condition.",
          "label": "agent choice"
        },
        "reproduction": "Feed starting, braked, unknown enrichment and chunk-degraded lifecycle snapshots, then repeat and recover; the before mappings retain the old serving condition or omit the chunk/enrichment condition."
      },
      "reproduction": "Feed starting, braked, unknown enrichment and chunk-degraded lifecycle snapshots, then repeat and recover; the before mappings retain the old serving condition or omit the chunk/enrichment condition.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java#rebuildBrakeTransitionsCarryRecoveryAndClearOnRecovery",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java#unreadableEnrichmentClearsTheServingGateOnlyWithFreshEvidence",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java#chunkDegradationUpdatesAndClearsItsOwnCondition",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java#chunkDegradationDoesNotChangeTheUnconfiguredModelMapping"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/WorkerSnapshotTapTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "The worker compatibility adapter replaces an existing legacy condition with an actionable stopped-ingestion rebuild-brake condition and clears it on recovery.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D120 (Health adapters omit the rebuild brake’s stopped-ingestion state) Feed rebuild-brake snapshots through both adapters and require an actionable stopped-ingestion condition.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P4 Existing health conditions (legacy/mismatch blocking states) keep their mappings.",
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
          "choice": "S-D120 (Health adapters omit the rebuild brake’s stopped-ingestion state) Feed rebuild-brake snapshots through both adapters and require an actionable stopped-ingestion condition.",
          "label": "agent choice"
        },
        "reproduction": "Feed BLOCKED_LEGACY then BLOCKED_REBUILD_BRAKE through the worker tap; the before adapter removes or fails to replace the schema.blocked condition."
      },
      "reproduction": "Feed BLOCKED_LEGACY then BLOCKED_REBUILD_BRAKE through the worker tap; the before adapter removes or fails to replace the schema.blocked condition.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/WorkerSnapshotTapTest.java#rebuildBrakeHasActionableStoppedIngestionCondition"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusCacheContextTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "The alternate status path preserves unknown stage counts, the original stale observation time and absent-versus-explicit-zero applicable chunk SPLADE totals.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D112 (Last-known counts lose their stale provenance) Change an index after a count, fail later reads and require consumers to identify fallback age.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C16. **Chunk SPLADE has its own applicable population** — its counter excludes chunks lacking `splade_status`, but the published chunk total counts all chunks; mix applicable and status-less chunks and verify any displayed fraction uses a faithful denominator. Preserve absent/false enabled-flag behavior.",
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
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
          "label": "agent choice"
        },
        "reproduction": "Map a worker reply with negative counts and a stale observation, and an older worker without splade_doc_count; the before cache drops provenance and the applicable total."
      },
      "reproduction": "Map a worker reply with negative counts and a stale observation, and an older worker without splade_doc_count; the before cache drops provenance and the applicable total.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusCacheContextTest.java#alternateStatusPreservesAnOlderWorkersAbsentSpladeTotal",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusCacheContextTest.java#alternateStatusKeepsUnknownEnrichmentAndTheOriginalCountAge"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "The application status mapper preserves stage uncertainty, stale observation time and the absence of older-worker chunk SPLADE totals.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D112 (Last-known counts lose their stale provenance) Change an index after a count, fail later reads and require consumers to identify fallback age.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C16. **Chunk SPLADE has its own applicable population** — its counter excludes chunks lacking `splade_status`, but the published chunk total counts all chunks; mix applicable and status-less chunks and verify any displayed fraction uses a faithful denominator. Preserve absent/false enabled-flag behavior.",
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
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
          "label": "agent choice"
        },
        "reproduction": "Map worker replies with unknown enrichment, stale count observations and optional chunk SPLADE totals; before-state mapping omits these observations and fields."
      },
      "reproduction": "Map worker replies with unknown enrichment, stale count observations and optional chunk SPLADE totals; before-state mapping omits these observations and fields.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java#olderWorkerCannotPublishAnAbsentApplicableSpladeTotalAsZero",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java#unknownEnrichmentAndCountAgeReachTheStatusView"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Worker RPC enrichment is mapped through readiness and lifecycle health without false completion for unknown/failed/missing work; zero-chunk declarations and disabled stages remain distinguishable; rebuild brakes reconcile existing conditions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D114 (Terminal enrichment failure can display “fully searchable”) Feed drained status with one failed/missing vector and require full-searchability wording withheld.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D116 (Fully processed short-only libraries remain degraded) Finish one short source’s applicable enrichment and require no unfinished-passage warning.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D120 (Health adapters omit the rebuild brake’s stopped-ingestion state) Feed rebuild-brake snapshots through both adapters and require an actionable stopped-ingestion condition.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D121 (Chunk-vector degradation produces no health condition) Require a structured condition for a nonempty applicable corpus with incomplete vectors.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. **Actual zero work remains distinguishable from unreadable work** — empty indexes, disabled stages, unwired services and reader failures currently share zero-shaped values; check each separately, including a successful retry that clears uncertainty.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C12. **Missing artifacts remain incomplete even with no failures** — `COMPLETED` without a dense vector is different from terminal failure; seed parent and chunk examples and check the headline, progress details and health output, including a corpus large enough for percentage rounding to hide one missing artifact.",
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
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
          "label": "agent choice"
        },
        "reproduction": "Drive real mapper/handler/health tap paths with failed count reads, terminal failures, rounded missing artifacts, zero chunks and rebuild brakes. Before-state decisions either certify readiness or retain/omit the wrong health condition."
      },
      "reproduction": "Drive real mapper/handler/health tap paths with failed count reads, terminal failures, rounded missing artifacts, zero chunks and rebuild brakes. Before-state decisions either certify readiness or retain/omit the wrong health condition.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#servingBrakeTransitionsThroughHandlerAndHealthTap",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#independentUnreadableEnrichmentSurvivesMapperAndReadiness",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#unsuccessfulEnrichmentDegradesRetrievalThroughMapperAndHealth",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#incompleteVectorsHaveAHealthConditionRegardlessOfRetrievalEligibility",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#disabledEmbeddingDoesNotAssertUnfinishedPassages",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#completedShortOnlyLibraryHasNoUnfinishedPassageWarning"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/observability/GpuSaturationMonitorTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "GPU monitoring retains bounded recent evidence under frequent continuous polling, resets only for a real gap, and keeps its inverse activity gate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D134 (Frequent polling prevents throughput windows from maturing) Feed one-second samples beyond 180 seconds and require a verdict.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D135 (Continuous history is mistaken for a sampling gap) Continue ten-second samples across 600 seconds and require the established window retained.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C32. **Shared window changes preserve GPU monitoring** — `GpuSaturationMonitor` also reads `RollingMonitorWindow` and has the same history-span gap logic; check continuous sampling, real gaps, bounded retention and its inverse activity gate if shared behavior changes.",
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
          "choice": "S-D134 (Frequent polling prevents throughput windows from maturing) Feed one-second samples beyond 180 seconds and require a verdict.",
          "label": "agent choice"
        },
        "reproduction": "Poll every 100ms across 900 seconds with a rate change and then a real gap. Before-state bounded samples never mature the verdict horizon."
      },
      "reproduction": "Poll every 100ms across 900 seconds with a rate change and then a real gap. Before-state bounded samples never mature the verdict horizon.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/observability/GpuSaturationMonitorTest.java#continuousPollingRetainsRecentEvidence"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Canonical zero-chunk declarations produced below the threshold or by a single split qualify before and after reopen.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D116 (Fully processed short-only libraries remain degraded) Finish one short source’s applicable enrichment and require no unfinished-passage warning.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C22. **Zero chunks do not prove chunking was unnecessary** — `ChunkDocumentWriter.plan()` legitimately produces zero below the threshold or for a single split; distinguish those declarations from expected chunks that are missing, unknown counts and orphan chunks. Check fresh ingest and reopened indexes.",
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
          "choice": "S-D116 (Fully processed short-only libraries remain degraded) Finish one short source’s applicable enrichment and require no unfinished-passage warning.",
          "label": "agent choice"
        },
        "reproduction": "Ingest two legitimate zero-chunk source plans, query coverage and reopen; before-state coverage has no chunkingNotRequired proof."
      },
      "reproduction": "Ingest two legitimate zero-chunk source plans, query coverage and reopen; before-state coverage has no chunkingNotRequired proof.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java#canonicalZeroChunkDeclarationsQualifyFreshAndReopenedLibraries"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "An unreadable filtered search population is unknown, rather than borrowing an unfiltered count. Parent counts lease one snapshot and finished short-only libraries report their enrichment after reopen. Existing serving-reader and scoped-count expectations are unchanged. Preservation checks, passing before and after: `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#followsSearchServingReader`, `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#healthyReaderReportsScopedCount`.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D112 (Last-known counts lose their stale provenance) Change an index after a count, fail later reads and require consumers to identify fallback age.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D130 (Parent counts subtract values from different readers) Expose new chunks between the reads and require one-snapshot parent counting.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D116 (Fully processed short-only libraries remain degraded) Finish one short source’s applicable enrichment and require no unfinished-passage warning.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C9. **Freshness reaches migration and searchable counts too** — active counts feed `migration.activeIndexedDocuments`, building counts feed rebuild progress, and searchable counts use a separate filtered read; fail each path and require consumers to identify exactly which observation is unavailable or stale.",
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
          "choice": "S-D112 (Last-known counts lose their stale provenance) Change an index after a count, fail later reads and require consumers to identify fallback age.",
          "label": "agent choice"
        },
        "reproduction": "Fail the filtered count with ten readable parents; force inconsistent legacy total/chunk reads; reopen a finished short source. Before reports a substituted filtered count, subtracts independent reads, or omits serving enrichment."
      },
      "reproduction": "Fail the filtered count with ten readable parents; force inconsistent legacy total/chunk reads; reopen a finished short source. Before reports a substituted filtered count, subtracts independent reads, or omits serving enrichment.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#ioExceptionFallsBackInsteadOfReportingZero",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#parentCountsDoNotSubtractIndependentReaderObservations",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#aReadOnlyServingReaderReportsFinishedEnrichmentAfterReopen"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Schema equality and coverage-field predicates remain unchanged.",
      "whySurvives": "The map-schema generator gains the standard map-values option for the newly published per-root defect maps; no expected schema comparison is weakened. Preservation checks, passing before and after: `modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java#indexedRootViewSchema`, `modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java#baselineCarriesCoverageFields`.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C13. **Folder wording cannot certify failed work** — `computeRootCoverage()` counts `FAILED` as settled, and `folderStatus.rootCoverage()` equates settled completion with “fully searchable”; seed a drained folder containing failed enrichment and require truthful folder wording too.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Measurement/time-only changes do not advance the status state counter.",
      "whySurvives": "The existing changing-measurement fixture now varies count observation timestamps too; the existing counter equality assertions stay unchanged. Preservation checks, passing before and after: `modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#idleCounterIgnoresMeasurementChanges`, `modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#failedActionWithMeasurementOnlyDiffCarriesNoNewerCounter`.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D112 (Last-known counts lose their stale provenance) Change an index after a count, fail later reads and require consumers to identify fallback age.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The same injected reader failure also stubs parentDocCountOrThrow after parent counting moved from independent total/chunk reads to one snapshot. Inputs, failure toggle and every existing assertion remain unchanged. Preservation checks, passing before and after: `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountWithADifferentFingerprintIsNeverCompatible`, `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountWithNoRecordedFingerprintIsUnknownNotCompatible`, `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountNeverShowsZeroDocuments`, `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnparseableStoredFingerprintIsUnknownNotRebuildRequired`, `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#unreadableStoredMetadataIsUnknownNotLegacy`, `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#aReadableDifferentFingerprintWithDocumentsIsStillAMismatch`.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D130 (Parent counts subtract values from different readers) Expose new chunks between the reads and require one-snapshot parent counting.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P4 Existing health conditions (legacy/mismatch blocking states) keep their mappings.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The same injected reader failure also stubs parentDocCountOrThrow after parent counting moved from independent total/chunk reads to one snapshot. Inputs, failure toggle and every existing assertion remain unchanged. Preservation checks, passing before and after: `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aCountThatFailsFromTheFirstReadIsReportedUnknown`, `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aReadableCountIsNotReportedUnknown`, `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aMismatchWithAnUnreadableBrakeIsReportedBrakedNamingTheUnreadableBrake`, `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aMismatchWithNoBrakeRecordedIsStillAPlainMismatch`.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D130 (Parent counts subtract values from different readers) Expose new chunks between the reads and require one-snapshot parent counting.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P4 Existing health conditions (legacy/mismatch blocking states) keep their mappings.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts"
      ],
      "class": "Defect pin",
      "behaviour": "The rendered deck explains enabled embedding unavailability even after the file queue drains.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D117 (Unavailable enrichment is described as still improving) Supply enabled/unavailable pending embedding and require an unavailability explanation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C17. **Every progress consumer shares the corrected decision** — `selectIndexingPhase`, `selectIndexingProgress`, `enrichSettledSum`, ETA memory and `TaskList.headline()` consume or override progress; test transitions through pending, blocked, failed and complete states, including rendered output and rate-memory reset.",
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
          "choice": "S-D117 (Unavailable enrichment is described as still improving) Supply enabled/unavailable pending embedding and require an unavailability explanation.",
          "label": "agent choice"
        },
        "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario."
      },
      "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts#keeps unavailable enrichment visible when the file queue is empty"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts"
      ],
      "class": "Defect pin",
      "behaviour": "Rendered task headlines, completion windows and stage detail respect failed, missing, unknown and unavailable enrichment while retaining other runnable-stage progress.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D114 (Terminal enrichment failure can display “fully searchable”) Feed drained status with one failed/missing vector and require full-searchability wording withheld.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D117 (Unavailable enrichment is described as still improving) Supply enabled/unavailable pending embedding and require an unavailability explanation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C17. **Every progress consumer shares the corrected decision** — `selectIndexingPhase`, `selectIndexingProgress`, `enrichSettledSum`, ETA memory and `TaskList.headline()` consume or override progress; test transitions through pending, blocked, failed and complete states, including rendered output and rate-memory reset.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C20. **Other runnable stages do not imply embedding is improving** — with embedding unavailable and SPLADE/NER progressing, require accurate semantic wording while still showing their real work; preserve the existing initially absent embedding-service guard.",
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
          "choice": "S-D114 (Terminal enrichment failure can display “fully searchable”) Feed drained status with one failed/missing vector and require full-searchability wording withheld.",
          "label": "agent choice"
        },
        "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario."
      },
      "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#does not override the failed or missing enrichment headline with a completion claim: {\"embeddingDocCount\":2,\"embeddingCompletedCount\":1,\"embeddingFailedCount\":1}",
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#does not override the failed or missing enrichment headline with a completion claim: {\"spladeDocCount\":2,\"spladeCompletedCount\":1,\"spladeFailedCount\":1}",
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#does not override the failed or missing enrichment headline with a completion claim: {\"completedNerCount\":1,\"failedNerCount\":1}",
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#does not override the failed or missing enrichment headline with a completion claim: {\"chunk\":{\"chunkDocCount\":2,\"chunkEmbeddingCompletedCount\":1,\"chunkEmbeddingFailedCount\":1}}",
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#does not override the failed or missing enrichment headline with a completion claim: {\"embeddingDocCount\":10000,\"embeddingCompletedCount\":10000,\"completeness\":[{\"stageId\":\"embed\",\"expected\":10000,\"present\":9999,\"missing\":1,\"failed\":0}]}",
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#withdraws the completion window when the next count cannot be read",
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#describes a producer lost after startup without an AI-install claim in the completion window",
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#keeps unavailable embedding and other real enrichment work visible after the completion window"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/aiStateStore.test.ts"
      ],
      "class": "Defect pin",
      "behaviour": "ETA memory clears when a live enrichment producer becomes blocked; unknown/failed resets are preservation checks. Preservation checks, passing before and after: `modules/ui-web/src/shell-v0/state/aiStateStore.test.ts#aiStateStore — system-health verdict (595) clears ETA memory when enrichment becomes unknown`, `modules/ui-web/src/shell-v0/state/aiStateStore.test.ts#aiStateStore — system-health verdict (595) clears ETA memory when enrichment becomes failed`.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D117 (Unavailable enrichment is described as still improving) Supply enabled/unavailable pending embedding and require an unavailability explanation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C17. **Every progress consumer shares the corrected decision** — `selectIndexingPhase`, `selectIndexingProgress`, `enrichSettledSum`, ETA memory and `TaskList.headline()` consume or override progress; test transitions through pending, blocked, failed and complete states, including rendered output and rate-memory reset.",
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
          "choice": "S-D117 (Unavailable enrichment is described as still improving) Supply enabled/unavailable pending embedding and require an unavailability explanation.",
          "label": "agent choice"
        },
        "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario."
      },
      "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/aiStateStore.test.ts#aiStateStore — system-health verdict (595) clears ETA memory when enrichment becomes blocked"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/availability.test.ts"
      ],
      "class": "Defect pin",
      "behaviour": "Document availability explains failed/missing semantic matches while retaining keyword search and an actionable Health link.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D114 (Terminal enrichment failure can display “fully searchable”) Feed drained status with one failed/missing vector and require full-searchability wording withheld.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C12. **Missing artifacts remain incomplete even with no failures** — `COMPLETED` without a dense vector is different from terminal failure; seed parent and chunk examples and check the headline, progress details and health output, including a corpus large enough for percentage rounding to hide one missing artifact.",
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
          "choice": "S-D114 (Terminal enrichment failure can display “fully searchable”) Feed drained status with one failed/missing vector and require full-searchability wording withheld.",
          "label": "agent choice"
        },
        "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario."
      },
      "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/availability.test.ts#projectAvailability (tempdoc 596) enrichment.failed keeps document availability truthful about incomplete semantic matches",
        "modules/ui-web/src/shell-v0/state/availability.test.ts#projectAvailability (tempdoc 596) enrichment.missing keeps document availability truthful about incomplete semantic matches"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts"
      ],
      "class": "Defect pin",
      "behaviour": "Folder wording withholds completion for failed/missing artifacts, preserves unknown coverage and describes producer unavailability; intentionally disabled-stage completion is preserved. Preservation checks, passing before and after: `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not warn for an intentionally disabled stage`.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D114 (Terminal enrichment failure can display “fully searchable”) Feed drained status with one failed/missing vector and require full-searchability wording withheld.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D117 (Unavailable enrichment is described as still improving) Supply enabled/unavailable pending embedding and require an unavailability explanation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C13. **Folder wording cannot certify failed work** — `computeRootCoverage()` counts `FAILED` as settled, and `folderStatus.rootCoverage()` equates settled completion with “fully searchable”; seed a drained folder containing failed enrichment and require truthful folder wording too.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C12. **Missing artifacts remain incomplete even with no failures** — `COMPLETED` without a dense vector is different from terminal failure; seed parent and chunk examples and check the headline, progress details and health output, including a corpus large enough for percentage rounding to hide one missing artifact.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 A library whose applicable enrichment is genuinely complete still shows the ready state (same wording as today).",
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
          "choice": "S-D114 (Terminal enrichment failure can display “fully searchable”) Feed drained status with one failed/missing vector and require full-searchability wording withheld.",
          "label": "agent choice"
        },
        "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario."
      },
      "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not certify fully searchable for failed embedding",
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not certify fully searchable for failed splade",
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not certify fully searchable for failed ner",
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not certify fully searchable for failed chunkVectors",
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts#uses producer unavailability for an unfinished folder while completed folders stay complete",
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts#withholds folder completion when completed embedding has no artifact",
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts#withholds folder completion when completed chunkVectors has no artifact",
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts#retains unknown root coverage instead of claiming improvement"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts"
      ],
      "class": "Defect pin",
      "behaviour": "The existing user-pause explanation and live embedding-unavailability explanation coexist without an ETA.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D117 (Unavailable enrichment is described as still improving) Supply enabled/unavailable pending embedding and require an unavailability explanation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C19. **Unavailable and intentionally deferred work remain distinguishable** — scheduler availability is separate from user pause, schedule, battery, GPU and energy holds; combine these states and verify existing pause explanations and actionable unavailability survive with sensible precedence.",
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
          "choice": "S-D117 (Unavailable enrichment is described as still improving) Supply enabled/unavailable pending embedding and require an unavailability explanation.",
          "label": "agent choice"
        },
        "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario."
      },
      "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts#WP3.7 status-owned pause projection preserves the pause cause and embedding unavailability together"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts"
      ],
      "class": "Defect pin",
      "behaviour": "Selectors retain unreadable counts, refuse false completion for failed/missing work, show applicable chunk SPLADE progress independently, explain producer unavailability and require a current zero-chunk declaration.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D114 (Terminal enrichment failure can display “fully searchable”) Feed drained status with one failed/missing vector and require full-searchability wording withheld.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D115 (Chunk SPLADE backlog is absent from progress phase) Supply only enabled chunk-SPLADE pending work and require a non-ready phase.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D117 (Unavailable enrichment is described as still improving) Supply enabled/unavailable pending embedding and require an unavailability explanation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C12. **Missing artifacts remain incomplete even with no failures** — `COMPLETED` without a dense vector is different from terminal failure; seed parent and chunk examples and check the headline, progress details and health output, including a corpus large enough for percentage rounding to hide one missing artifact.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C16. **Chunk SPLADE has its own applicable population** — its counter excludes chunks lacking `splade_status`, but the published chunk total counts all chunks; mix applicable and status-less chunks and verify any displayed fraction uses a faithful denominator. Preserve absent/false enabled-flag behavior.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C22. **Zero chunks do not prove chunking was unnecessary** — `ChunkDocumentWriter.plan()` legitimately produces zero below the threshold or for a single split; distinguish those declarations from expected chunks that are missing, unknown counts and orphan chunks. Check fresh ingest and reopened indexes.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C17. **Every progress consumer shares the corrected decision** — `selectIndexingPhase`, `selectIndexingProgress`, `enrichSettledSum`, ETA memory and `TaskList.headline()` consume or override progress; test transitions through pending, blocked, failed and complete states, including rendered output and rate-memory reset.",
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
          "choice": "S-D110 (Enrichment read errors become zero outstanding work) Fail enrichment counting with real pending work and require unknown status.",
          "label": "agent choice"
        },
        "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario."
      },
      "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness keeps unreadable embeddingPendingCount unknown through both selectors",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness keeps unreadable spladePendingCount unknown through both selectors",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness keeps unreadable pendingNerCount unknown through both selectors",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness withholds completion for terminal embeddingFailedCount",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness withholds completion for terminal spladeFailedCount",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness withholds completion for terminal failedNerCount",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness includes enabled chunk SPLADE independently of embedding and its status-less siblings",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness blocks unavailable embedding with chunkOnly=false",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness blocks unavailable embedding with chunkOnly=true",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness does not round one missing artifact into a completion claim",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#independent count failures and sparse population withholds completion when only the completeness audit is unreadable",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#independent count failures and sparse population keeps independently unreadable chunkEmbeddingPendingCount unknown",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#independent count failures and sparse population keeps independently unreadable chunkVectorCoveragePercent unknown",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#independent count failures and sparse population keeps independently unreadable chunkDocCount unknown",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#independent count failures and sparse population shows chunk SPLADE failures over its own population",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#live enrichment producer availability blocks unavailable enabled splade work",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#live enrichment producer availability blocks unavailable enabled ner work",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#live enrichment producer availability blocks unavailable enabled chunkSplade work",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#requires a zero-chunk declaration before certifying a nonempty library",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#does not infer zero passage work from a unknown parent count",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#does not infer zero passage work from a stale parent count",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#shows older-worker chunk SPLADE backlog with total undefined",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#shows older-worker chunk SPLADE backlog with total -1",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#shows older-worker chunk SPLADE backlog with total 0"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts"
      ],
      "class": "Defect pin",
      "behaviour": "Degraded readiness notices identify failed/missing enrichment and its semantic-search consequence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D114 (Terminal enrichment failure can display “fully searchable”) Feed drained status with one failed/missing vector and require full-searchability wording withheld.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C12. **Missing artifacts remain incomplete even with no failures** — `COMPLETED` without a dense vector is different from terminal failure; seed parent and chunk examples and check the headline, progress details and health output, including a corpus large enough for percentage rounding to hide one missing artifact.",
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
          "choice": "S-D114 (Terminal enrichment failure can display “fully searchable”) Feed drained status with one failed/missing vector and require full-searchability wording withheld.",
          "label": "agent choice"
        },
        "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario."
      },
      "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#names enrichment.failed in the degraded search notice",
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#names enrichment.missing in the degraded search notice"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts"
      ],
      "class": "Defect pin",
      "behaviour": "The state store and file facts retain count age and distinguish whole-index, filtered and stale-zero populations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D112 (Last-known counts lose their stale provenance) Change an index after a count, fail later reads and require consumers to identify fallback age.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C9. **Freshness reaches migration and searchable counts too** — active counts feed `migration.activeIndexedDocuments`, building counts feed rebuild progress, and searchable counts use a separate filtered read; fail each path and require consumers to identify exactly which observation is unavailable or stale.",
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
          "choice": "S-D112 (Last-known counts lose their stale provenance) Change an index after a count, fail later reads and require consumers to identify fallback age.",
          "label": "agent choice"
        },
        "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario."
      },
      "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#count observation provenance keeps fallback counts visibly last known with their original observation time",
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#count observation provenance distinguishes a failed filtered count from the fresh whole-index count",
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#count observation provenance never treats a last-known zero as proof the current index is empty"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-honesty.test.ts"
      ],
      "class": "Defect pin",
      "behaviour": "Search corpus projection does not certify an empty corpus from a retained filtered zero.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D112 (Last-known counts lose their stale provenance) Change an index after a count, fail later reads and require consumers to identify fallback age.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C9. **Freshness reaches migration and searchable counts too** — active counts feed `migration.activeIndexedDocuments`, building counts feed rebuild progress, and searchable counts use a separate filtered read; fail each path and require consumers to identify exactly which observation is unavailable or stale.",
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
          "choice": "S-D112 (Last-known counts lose their stale provenance) Change an index after a count, fail later reads and require consumers to identify fallback age.",
          "label": "agent choice"
        },
        "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario."
      },
      "reproduction": "Run the named checks with the candidate test file over 5875daba6c production. Each named failure is an assertion on the observable status/wording or progress state; the before decisions violate the cited scenario.",
      "evidence": "gates/test-intent/evidence/ingfix-wp11.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-honesty.test.ts#does not present a retained zero as the current search corpus when unknown",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-honesty.test.ts#does not present a retained zero as the current search corpus when stale"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/__fixtures__/status-response-live.json"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The captured status input adds only the additive chunkingNotRequired=false, chunkSpladeDocCount=0, chunkSpladeFailedCount=0 and completenessKnown=null fields. Existing core counts, completion values, operational state and all existing fixture values stay unchanged. Consumer contract and polling checks exercise the old input and candidate encoding. Preservation checks, passing before and after: `modules/ui-web/src/api/contract.test.ts#Cross-language contract: StatusResponse vs generated statusResponseSchema Java-serialized StatusResponse passes Zod validation`, `modules/ui-web/src/api/generated/schema-types/status-response.test.ts#generated statusResponseSchema (564 faithfulness) validates the real captured status wire fixture with no contract drift`.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C22. **Zero chunks do not prove chunking was unnecessary** — `ChunkDocumentWriter.plan()` legitimately produces zero below the threshold or for a single split; distinguish those declarations from expected chunks that are missing, unknown counts and orphan chunks. Check fresh ingest and reopened indexes.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C16. **Chunk SPLADE has its own applicable population** — its counter excludes chunks lacking `splade_status`, but the published chunk total counts all chunks; mix applicable and status-less chunks and verify any displayed fraction uses a faithful denominator. Preserve absent/false enabled-flag behavior.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. **Actual zero work remains distinguishable from unreadable work** — empty indexes, disabled stages, unwired services and reader failures currently share zero-shaped values; check each separately, including a successful retry that clears uncertainty.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Execution report

The unmodified repository runner generated the evidence in a disposable shared
candidate clone under `$TMPDIR`. It then created its own before checkout there;
the separate manual before-state comparison used `$TMPDIR/before`, a shared
clone of `5875daba6c`. All test trees built into their own build folders.
The runner used `VITEST_MAX_WORKERS=4` and ran under `js-gradle-slot` with
`DISPLAY` and `WAYLAND_DISPLAY` unset. No shared dev stack or GPU was used.
Shared resources touched: Gradle's user cache/daemons and the two-slot queue.

Candidate: 99 selected IDs / 132 executed cases, all passed, none skipped.
Before: all 73 repair IDs failed by assertion; all 26 preservation IDs passed.
Parameterized repair IDs include their passing control invocations as well as
failing repair cases. The evidence script exits 1 because the intentional
preservation checks pass before; every changeset `newChecks` ID satisfies its
assertion-fail-before requirement.

Two observations use `assertDoesNotThrow` to turn an absent chunk-health
condition and a missing completeness-known value into assertion failures;
their expected values, inputs and fault fixtures are unchanged.

Overlay list (also recorded in evidence):

- `modules/app-inference/build.gradle.kts`
- `modules/app-inference/gradle.lockfile`

Compatibility patch: `gates/test-intent/compatibility/ingfix-wp11-before.patch`.
It adds only wire/data/accessor signatures, a legacy two-read parent count and
a CUDA overload delegating to the original Windows-only implementation.
The first manual before build could not resolve the new Logback test dependency
until its matching test-classpath lock entries were included in the overlay;
that setup failure is not counted as fail-before evidence.

| Check ID | Before | Candidate |
| --- | --- | --- |
| `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#linuxRuntimeDoesNotLogMissingWindowsDll` | assertion | passed (2 cases) |
| `modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java#rebuildBrakeTransitionsCarryRecoveryAndClearOnRecovery` | assertion | passed (1 case) |
| `modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java#unreadableEnrichmentClearsTheServingGateOnlyWithFreshEvidence` | assertion | passed (1 case) |
| `modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java#chunkDegradationUpdatesAndClearsItsOwnCondition` | assertion | passed (1 case) |
| `modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java#chunkDegradationDoesNotChangeTheUnconfiguredModelMapping` | assertion | passed (1 case) |
| `modules/app-services/src/test/java/io/justsearch/app/services/observability/health/WorkerSnapshotTapTest.java#rebuildBrakeHasActionableStoppedIngestionCondition` | assertion | passed (1 case) |
| `modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusCacheContextTest.java#alternateStatusPreservesAnOlderWorkersAbsentSpladeTotal` | assertion | passed (1 case) |
| `modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusCacheContextTest.java#alternateStatusKeepsUnknownEnrichmentAndTheOriginalCountAge` | assertion | passed (1 case) |
| `modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java#olderWorkerCannotPublishAnAbsentApplicableSpladeTotalAsZero` | assertion | passed (1 case) |
| `modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java#unknownEnrichmentAndCountAgeReachTheStatusView` | assertion | passed (1 case) |
| `modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#servingBrakeTransitionsThroughHandlerAndHealthTap` | assertion | passed (3 cases) |
| `modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#independentUnreadableEnrichmentSurvivesMapperAndReadiness` | assertion | passed (10 cases) |
| `modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#unsuccessfulEnrichmentDegradesRetrievalThroughMapperAndHealth` | assertion | passed (17 cases) |
| `modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#incompleteVectorsHaveAHealthConditionRegardlessOfRetrievalEligibility` | assertion | passed (6 cases) |
| `modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#disabledEmbeddingDoesNotAssertUnfinishedPassages` | assertion | passed (1 case) |
| `modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#completedShortOnlyLibraryHasNoUnfinishedPassageWarning` | assertion | passed (1 case) |
| `modules/ui/src/test/java/io/justsearch/ui/observability/GpuSaturationMonitorTest.java#continuousPollingRetainsRecentEvidence` | assertion | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java#canonicalZeroChunkDeclarationsQualifyFreshAndReopenedLibraries` | assertion | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#ioExceptionFallsBackInsteadOfReportingZero` | assertion | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#parentCountsDoNotSubtractIndependentReaderObservations` | assertion | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#aReadOnlyServingReaderReportsFinishedEnrichmentAfterReopen` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/components/StatusDeck.test.ts#keeps unavailable enrichment visible when the file queue is empty` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/components/TaskList.test.ts#does not override the failed or missing enrichment headline with a completion claim: {"embeddingDocCount":2,"embeddingCompletedCount":1,"embeddingFailedCount":1}` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/components/TaskList.test.ts#does not override the failed or missing enrichment headline with a completion claim: {"spladeDocCount":2,"spladeCompletedCount":1,"spladeFailedCount":1}` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/components/TaskList.test.ts#does not override the failed or missing enrichment headline with a completion claim: {"completedNerCount":1,"failedNerCount":1}` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/components/TaskList.test.ts#does not override the failed or missing enrichment headline with a completion claim: {"chunk":{"chunkDocCount":2,"chunkEmbeddingCompletedCount":1,"chunkEmbeddingFailedCount":1}}` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/components/TaskList.test.ts#does not override the failed or missing enrichment headline with a completion claim: {"embeddingDocCount":10000,"embeddingCompletedCount":10000,"completeness":[{"stageId":"embed","expected":10000,"present":9999,"missing":1,"failed":0}]}` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/components/TaskList.test.ts#withdraws the completion window when the next count cannot be read` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/components/TaskList.test.ts#describes a producer lost after startup without an AI-install claim in the completion window` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/components/TaskList.test.ts#keeps unavailable embedding and other real enrichment work visible after the completion window` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/aiStateStore.test.ts#aiStateStore — system-health verdict (595) clears ETA memory when enrichment becomes blocked` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/availability.test.ts#projectAvailability (tempdoc 596) enrichment.failed keeps document availability truthful about incomplete semantic matches` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/availability.test.ts#projectAvailability (tempdoc 596) enrichment.missing keeps document availability truthful about incomplete semantic matches` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not certify fully searchable for failed embedding` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not certify fully searchable for failed splade` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not certify fully searchable for failed ner` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not certify fully searchable for failed chunkVectors` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#uses producer unavailability for an unfinished folder while completed folders stay complete` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#withholds folder completion when completed embedding has no artifact` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#withholds folder completion when completed chunkVectors has no artifact` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#retains unknown root coverage instead of claiming improvement` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts#WP3.7 status-owned pause projection preserves the pause cause and embedding unavailability together` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness keeps unreadable embeddingPendingCount unknown through both selectors` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness keeps unreadable spladePendingCount unknown through both selectors` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness keeps unreadable pendingNerCount unknown through both selectors` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness withholds completion for terminal embeddingFailedCount` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness withholds completion for terminal spladeFailedCount` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness withholds completion for terminal failedNerCount` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness includes enabled chunk SPLADE independently of embedding and its status-less siblings` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness blocks unavailable embedding with chunkOnly=false` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness blocks unavailable embedding with chunkOnly=true` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#enrichment status truthfulness does not round one missing artifact into a completion claim` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#independent count failures and sparse population withholds completion when only the completeness audit is unreadable` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#independent count failures and sparse population keeps independently unreadable chunkEmbeddingPendingCount unknown` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#independent count failures and sparse population keeps independently unreadable chunkVectorCoveragePercent unknown` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#independent count failures and sparse population keeps independently unreadable chunkDocCount unknown` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#independent count failures and sparse population shows chunk SPLADE failures over its own population` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#live enrichment producer availability blocks unavailable enabled splade work` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#live enrichment producer availability blocks unavailable enabled ner work` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#live enrichment producer availability blocks unavailable enabled chunkSplade work` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#requires a zero-chunk declaration before certifying a nonempty library` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#does not infer zero passage work from a unknown parent count` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#does not infer zero passage work from a stale parent count` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#shows older-worker chunk SPLADE backlog with total undefined` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#shows older-worker chunk SPLADE backlog with total -1` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#shows older-worker chunk SPLADE backlog with total 0` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#names enrichment.failed in the degraded search notice` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#names enrichment.missing in the degraded search notice` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#count observation provenance keeps fallback counts visibly last known with their original observation time` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#count observation provenance distinguishes a failed filtered count from the fresh whole-index count` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#count observation provenance never treats a last-known zero as proof the current index is empty` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/views/search-v3/sv3-honesty.test.ts#does not present a retained zero as the current search corpus when unknown` | assertion | passed (1 case) |
| `modules/ui-web/src/shell-v0/views/search-v3/sv3-honesty.test.ts#does not present a retained zero as the current search corpus when stale` | assertion | passed (1 case) |
| `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#nullExecutable` | passed | passed (1 case) |
| `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#noGgmlCudaDll` | passed | passed (1 case) |
| `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#largeStaticDll` | passed | passed (1 case) |
| `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#smallDllWithCudart` | passed | passed (1 case) |
| `modules/app-inference/src/test/java/io/justsearch/app/inference/CudaRuntimeDetectionTest.java#smallDllWithoutCudart` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#followsSearchServingReader` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java#healthyReaderReportsScopedCount` | passed | passed (1 case) |
| `modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java#indexedRootViewSchema` | passed | passed (1 case) |
| `modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java#baselineCarriesCoverageFields` | passed | passed (1 case) |
| `modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#idleCounterIgnoresMeasurementChanges` | passed | passed (1 case) |
| `modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#failedActionWithMeasurementOnlyDiffCarriesNoNewerCounter` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountWithADifferentFingerprintIsNeverCompatible` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountWithNoRecordedFingerprintIsUnknownNotCompatible` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountNeverShowsZeroDocuments` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnparseableStoredFingerprintIsUnknownNotRebuildRequired` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#unreadableStoredMetadataIsUnknownNotLegacy` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#aReadableDifferentFingerprintWithDocumentsIsStillAMismatch` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aCountThatFailsFromTheFirstReadIsReportedUnknown` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aReadableCountIsNotReportedUnknown` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aMismatchWithAnUnreadableBrakeIsReportedBrakedNamingTheUnreadableBrake` | passed | passed (1 case) |
| `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aMismatchWithNoBrakeRecordedIsStillAPlainMismatch` | passed | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/aiStateStore.test.ts#aiStateStore — system-health verdict (595) clears ETA memory when enrichment becomes unknown` | passed | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/aiStateStore.test.ts#aiStateStore — system-health verdict (595) clears ETA memory when enrichment becomes failed` | passed | passed (1 case) |
| `modules/ui-web/src/shell-v0/state/folderStatus.test.ts#terminal enrichment failure does not warn for an intentionally disabled stage` | passed | passed (1 case) |
| `modules/ui-web/src/api/contract.test.ts#Cross-language contract: StatusResponse vs generated statusResponseSchema Java-serialized StatusResponse passes Zod validation` | passed | passed (1 case) |
| `modules/ui-web/src/api/generated/schema-types/status-response.test.ts#generated statusResponseSchema (564 faithfulness) validates the real captured status wire fixture with no contract drift` | passed | passed (1 case) |


## Final validation

- `node scripts/governance/gates/test-intent/cli.mjs --base 5875daba6c`:
  all 24 flagged items covered, all evidence valid; only independent acceptance
  is missing, as required by this builder assignment.
- `node scripts/dev/java-prepush.mjs --unreferenced` (headless): exited 1 only
  at `:modules:ui:integrationTest`, with
  `RuntimeReloadProducersHttpIntegrationTest#receiptForARootWhoseVolumeReturnsIsAppliedWithoutAWatchCall`.
  Its assertion was `precondition: the person's add recorded the root's volume ==> expected: not <null>`
  at line 386 (fixture helper line 785). This is the sandbox root-volume UI
  precondition failure explicitly identified as environmental in the assignment.
  The task reported 74 cases, one failure and five configured skips; those skips
  are not counted as passes or as execution evidence above. Compilation, PMD and
  the unreferenced-code check completed successfully or were up-to-date.
- Candidate frontend: all 434 cases across the changed test files passed;
  the fixture-consumer files passed on both trees; `npm run lint` passed.
- `git diff --check`: passed. The new compatibility patch also has no trailing
  whitespace in blank context lines and still applies with `git apply`.
- `.agents/preflight`: absent in this checkout.

Tests added or rewritten: none with changed fixtures or expected values.
The following durable checks received selection naming or assertion wrappers:

- Tests added or rewritten: `IndexedRootViewSchemaTest.java` — schema equality / C13 — durable; method display descriptions moved to comments.
- Tests added or rewritten: `CudaRuntimeDetectionTest.java` — S-D137 / C34 — durable; selectable parameterized and ordinary method names.
- Tests added or rewritten: `LifecycleSnapshotTapTest.java` — S-D121 — durable; selectable method name and no-exception assertion around the required condition lookup.
- Tests added or rewritten: `WorkerStatusMapperTest.java` — S-D110 — durable; no-exception assertion around the required completeness-known value.
- Tests added or rewritten: `StatusLifecycleHandlerTest.java` — S-D110 / S-D114 / S-D120 / S-D121 — durable; selectable parameterized method names.
- Tests added or rewritten: `SearchableDocCountReaderSelectionTest.java` — S-D112 / S-D130 — durable; method display descriptions moved to comments.

Production code was not edited. All assignment changes remain uncommitted.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a19329-329e-7762-af25-4fb4620080ca",
  "verdict": "accept",
  "digest": "sha256:a2c11be01fbc4cdfd95d9153e660f65a84fa564c5179f07f08b5c5214b7c5da0",
  "note": "Independent verifier (codex gpt-6.1-sol) on 1c2b6a0bfd: entries 1-24 accepted; JVM repair checks fail before on the compatible overlay and pass on the candidate; frontend 52 named repair failures before, 434/434 after; preservation controls pass on both. Accepts the test oracles and evidence; product verdict recorded separately."
}
```
