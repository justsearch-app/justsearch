---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: redesign-o1-builder
---

# Test-intent entries

Execution evidence and independent acceptance are left to the orchestrator/verifier, as requested.
The web check is not executed in this sandbox. Preservation checks are the existing module suites,
including IndexRebuildBrakeTest, recorded-operation boot/recovery bindings, recovery policies,
retention and active/building/previous protection. Isolated new files are supplied in the extra
check list at handoff; they restore static mocks and use only their temporary resources.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/LifecycleDurabilityTest.java",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RecoveryIntegrationTest.java",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SchemaMismatchPolicyBranchTest.java",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationGcFailureTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/SchemaMismatchStatusContractTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Recovery and GC fixtures now carry the historical full v1 sentinel. Recovery fixtures use indices/<id> under their existing configured or fallback base. Corruption, policies, payloads, observations, fault injection and expected recovery outcomes remain the same, including unequal native sentinel/manifest timestamps. Authentic release fixture path re-encoding now builds its temporary Lucene copy outside the managed indices namespace, then moves it back with its unchanged released ownership metadata; the archived bytes, catalog, fingerprints, cohort and all oracles remain unchanged. The HTTP schema-mismatch contract fixture now seeds the full historical v1 sentinel before opening its native generation; its mismatched fingerprint, document, operator escape, HTTP assertions, tags and conditions are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "P7 Genuine corrupt generation: policy-controlled backup and rebuild (`auto_recovery`, `FAIL_CLOSED`, `BACKUP_ONLY`, brake); absent active recovered from a validated exact backup.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "P3 Valid pointers and owned generations: normal start; upgrade from existing generation layout; state v1 normalization (inspection runs before normalization).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/__fixtures__/status-response-live.json"
      ],
      "class": "Structural rule kept",
      "rule": "The fixture equals the complete Java StatusResponse serialization and retains every existing value.",
      "whySurvives": "The generated fixture adds only the empty cleanupWarnings lists to the migration groups, following their additive wire signatures. The serialization-equality and schema checks remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S17 Valid active generation plus an unproven `.del-` directory (or metadata-less marked predecessor, or an original with only `DELETEME`): no refusal; ordinary start, explicit rebuild, automatic corruption rebuild and recorded rebuild all work (excluded from retained counts, journal admission, fallback ambiguity, build capacity); left untouched and listed in cleanup warnings. Behaviour change vs base: base deleted pointer-bound metadata-less marked predecessors.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java"
      ],
      "class": "New",
      "behaviour": "Unproven marked predecessors and refused recorded targets remain untouched, are reported as cleanup warnings, and release logical retirement/build capacity. Every other retirement check is preserved. The pre-existing retained-capacity fixture is re-encoded with a full historical v1 sentinel and an exact deletion name so it continues to test genuine retained generation capacity.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S17 Valid active generation plus an unproven `.del-` directory (or metadata-less marked predecessor, or an original with only `DELETEME`): no refusal; ordinary start, explicit rebuild, automatic corruption rebuild and recorded rebuild all work (excluded from retained counts, journal admission, fallback ambiguity, build capacity); left untouched and listed in cleanup warnings. Behaviour change vs base: base deleted pointer-bound metadata-less marked predecessors.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "P5 Retirement, GC, retention and capacity release of genuine generations (valid sentinel); active/building/previous protection; native unequal sentinel/manifest timestamps.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-o1.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java#refusedRecordedTargetLeavesUnprovenMarkedPayloadAndReleasesCapacity",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java#retryLeavesAndReportsMarkedPredecessorWithoutOwnershipMetadata",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java#pointerBoundPartialDeleteLeavesAndReportsForeignOwnershipEvidence"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerWorkerDownCodeTest.java"
      ],
      "class": "New",
      "behaviour": "Ownership refusal path and evidence detail survive marker consumption and retry transitions and clear only at validated READY. Existing worker-down reason checks remain unchanged.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S16 The refusal reaches the user: HTTP status/readiness carries `index.ownership_unproven` with the rejected path and evidence class, sticky across retries, cleared after a validated start; UI notice says search is unavailable (not keyword-fallback wording), shows path and instruction, offers no Rebuild.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-o1.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerWorkerDownCodeTest.java#ownershipRefusalPreservesPathAndEvidenceAcrossRetriesUntilValidatedReady"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts"
      ],
      "class": "New",
      "behaviour": "Ownership refusal presents unavailable search with the rejected folder and manual recovery instruction and no Rebuild action or keyword fallback. Existing notice checks remain unchanged.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S16 The refusal reaches the user: HTTP status/readiness carries `index.ownership_unproven` with the rejected path and evidence class, sticky across retries, cleared after a validated start; UI notice says search is unavailable (not keyword-fallback wording), shows path and instruction, offers no Rebuild.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-o1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#S16: shows unavailable search, the rejected folder and manual remedy without Rebuild"
      ]
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationManagerRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Unproven marked payload is left and excluded from retained counts and build capacity; foreign non-marked payload refuses further startup without being changed. Other restart/pointer checks remain unchanged.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S17 Valid active generation plus an unproven `.del-` directory (or metadata-less marked predecessor, or an original with only `DELETEME`): no refusal; ordinary start, explicit rebuild, automatic corruption rebuild and recorded rebuild all work (excluded from retained counts, journal admission, fallback ambiguity, build capacity); left untouched and listed in cleanup warnings. Behaviour change vs base: base deleted pointer-bound metadata-less marked predecessors.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S1 Plain foreign folder: no pointers, `indices/` holds one non-empty directory without sentinel. Base: adopted, sentinel/manifest minted, Lucene files created, later retired. After: startup refused with `index.ownership_unproven`, directory byte-for-byte unchanged, no state written.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-o1.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationManagerRestartTest.java#unprovenMarkedRepresentationDoesNotConsumeCapacityAndForeignPayloadRefusesStart"
      ]
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerDurabilityTest.java"
      ],
      "class": "New",
      "behaviour": "A numeric state-temporary filename alone no longer authorizes deletion. An incomplete/unproven file is kept; a fully parsed state snapshot naming an independently owned generation is still cleaned up only after logical boot authority is established. Process-kill and pointer preservation checks remain unchanged.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S10 Control path `<id>.rebuild-pending` occupied by a symlink, junction or directory: refused, external bytes unchanged; oversized regular marker: read bounded, rebuild obligation kept, reported malformed.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "Ownership rule. Names, Lucene files, `DELETEME` entries and pointers prove nothing.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-o1.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerDurabilityTest.java#bootKeepsUnprovenScratchAndRemovesOnlyValidatedStateTemps"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java"
      ],
      "class": "New",
      "behaviour": "Worker refusal precedes journal mutation and retains the rejected path/evidence in its fatal marker. Existing unproven recovery targets, including empty target collisions, remain untouched. Malformed backup ownership uses the ownership refusal code. The conflicting-backups preservation fixture now leaves active absent so it still reaches the same genuine backup ambiguity; sentinel-only owned recovery still restores only the missing manifest.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S16 The refusal reaches the user: HTTP status/readiness carries `index.ownership_unproven` with the rejected path and evidence class, sticky across retries, cleared after a validated start; UI notice says search is unavailable (not keyword-fallback wording), shows path and instruction, offers no Rebuild.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S14 Publication collision: `indices/<id>` already exists as empty dir, non-empty dir, regular file or link: refused, both kept.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S3 Wrong, malformed, oversized, duplicate-field or unknown-field sentinel/manifest; sentinel naming another identity: refused before any write.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-o1.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java#foreignDirectoryRefusalPrecedesJournalMutationAndCarriesPathToFatalMarker",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java#interruptedBackupWithEmptyActiveRefusesExistingUnprovenTarget",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java#recoveryOwnershipRefusesInvalidBackupManifest"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementBudgetTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCompletedBuildTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The invalid building-sentinel trigger is replaced with a temporary denial from the committed-compatibility certificate, leaving all ownership evidence valid and every existing cutover and retirement assertion unchanged. S3 makes an invalid building sentinel a startup refusal, so the old trigger no longer reaches the behaviour under test. The thread-scoped static mock is reset after admission and closed with the boot fixture.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "S3 Wrong, malformed, oversized, duplicate-field or unknown-field sentinel/manifest; sentinel naming another identity: refused before any write.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```


The changed retirement expectations are exactly:

- `refusedRecordedTargetRetiresAfterPointerClearAndPartialMarkedDelete` →
  `refusedRecordedTargetLeavesUnprovenMarkedPayloadAndReleasesCapacity`: the metadata-less marked
  target remains, is reported, and releases logical capacity.
- `retryUsesExactPointerBindingAfterPartialDeleteRemovedOwnershipMetadata` →
  `retryLeavesAndReportsMarkedPredecessorWithoutOwnershipMetadata`: the metadata-less predecessor
  remains and is reported; its retired pointer is released.
- `pointerBoundPartialDeleteStillRefusesSurvivingForeignOwnershipEvidence` →
  `pointerBoundPartialDeleteLeavesAndReportsForeignOwnershipEvidence`: foreign marked ownership
  evidence remains unchanged and is reported, while logical retirement finishes (S17).

## Tests added or rewritten

- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java` — S1-S10, S12-S14, S17; P1-P3, P5, P8-P9 — durable.
- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java` — S12, S14 — durable.
- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationCleanupAuthorityTest.java` — S15, P4, P10; explicit GC only for unreferenced recorded empty remnants after IDLE/no-building; boot and automatic cleanup keep the orphan fence — durable.
- Tests added or rewritten: `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipDeletionTest.java` — S11 — durable.
- Tests added or rewritten: `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipRuntimeAdmissionTest.java` — S5, S13 — durable.
- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationGcFailureTest.java` — P5, P10; full historical v1 sentinel input — durable.
- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationManagerRestartTest.java` — S1, S17 — durable.
- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java` — S17; P5 owned retained-capacity fixture — durable.
- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerDurabilityTest.java` — control-file ownership; pointer/process-kill preservation — durable.
- Tests added or rewritten: `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/LifecycleDurabilityTest.java` — P7; owned recovery fixture — durable.
- Tests added or rewritten: `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RecoveryIntegrationTest.java` — P7; owned recovery fixtures — durable.
- Tests added or rewritten: `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SchemaMismatchPolicyBranchTest.java` — P7; owned recovery fixtures — durable.
- Tests added or rewritten: `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java` — S1, S3, S5, S14, S16; P1, P7 — durable.
- Tests added or rewritten: `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java` — P3, P4; authentic release path re-encoding — durable.
- Tests added or rewritten: `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerWorkerDownCodeTest.java` — S16 — durable.
- Tests added or rewritten: `modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerGcOwnershipTest.java` — S15, S17 HTTP GC fields — durable.
- Tests added or rewritten: `modules/ui-web/src/shell-v0/state/readinessNotice.test.ts` — S16; not executed in the builder sandbox — durable.
- Tests added or rewritten: `modules/ui-web/src/api/__fixtures__/status-response-live.json` — additive status cleanup warnings — durable.

- Tests added or rewritten: `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java` — P3-P5, P7; temporary compatibility-certificate denial replaces invalid sentinel — durable.
- Tests added or rewritten: `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementBudgetTest.java` — P3-P5, P7; temporary compatibility-certificate denial replaces invalid sentinel — durable.
- Tests added or rewritten: `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCompletedBuildTest.java` — P3-P5, P7; temporary compatibility-certificate denial replaces invalid sentinel — durable.

- Tests added or rewritten: `modules/ui/src/integrationTest/java/io/justsearch/ui/api/SchemaMismatchStatusContractTest.java` — P1, P3, P7; owned historical native fixture for existing schema HTTP contract — durable.

## Before-state and verifier invocation inputs

The builder did not run run-evidence.mjs or write evidence/acceptance records. The designated
formal evidence path remains `gates/test-intent/evidence/redesign-o1.json` for the verifier.
The compatibility patch adds only source signatures with neutral/base behavior: cleanup warning
accessors, the extended GC-outcome constructor, and the ignored frontend detail argument.

Arguments for the verifier (preservation cases are described above, not included as repair checks):

```text
--before 344bdaf03c
--before-patch gates/test-intent/compatibility/redesign-o1-before.patch
--overlay modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java
--overlay modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java
--overlay modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationCleanupAuthorityTest.java
--overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipDeletionTest.java
--overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipRuntimeAdmissionTest.java
--overlay modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationGcFailureTest.java
--overlay modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationManagerRestartTest.java
--overlay modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java
--overlay modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerDurabilityTest.java
--overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/LifecycleDurabilityTest.java
--overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RecoveryIntegrationTest.java
--overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SchemaMismatchPolicyBranchTest.java
--overlay modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java
--overlay modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java
--overlay modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java
--overlay modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementBudgetTest.java
--overlay modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCompletedBuildTest.java
--overlay modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerWorkerDownCodeTest.java
--overlay modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerGcOwnershipTest.java
--overlay modules/ui/src/integrationTest/java/io/justsearch/ui/api/SchemaMismatchStatusContractTest.java
--overlay modules/ui-web/src/shell-v0/state/readinessNotice.test.ts
--overlay modules/ui-web/src/api/__fixtures__/status-response-live.json
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java#refusedRecordedTargetLeavesUnprovenMarkedPayloadAndReleasesCapacity
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java#retryLeavesAndReportsMarkedPredecessorWithoutOwnershipMetadata
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java#pointerBoundPartialDeleteLeavesAndReportsForeignOwnershipEvidence
--check modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerWorkerDownCodeTest.java#ownershipRefusalPreservesPathAndEvidenceAcrossRetriesUntilValidatedReady
--check modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#S16: shows unavailable search, the rejected folder and manual remedy without Rebuild
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationManagerRestartTest.java#unprovenMarkedRepresentationDoesNotConsumeCapacityAndForeignPayloadRefusesStart
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerDurabilityTest.java#bootKeepsUnprovenScratchAndRemovesOnlyValidatedStateTemps
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java#foreignDirectoryRefusalPrecedesJournalMutationAndCarriesPathToFatalMarker
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java#interruptedBackupWithEmptyActiveRefusesExistingUnprovenTarget
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java#recoveryOwnershipRefusesInvalidBackupManifest
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#plainForeignFolderIsNeverAdopted
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#foreignLuceneShapeIsNeverAdoptedOrCollected
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#malformedSentinelsNeverAuthorizeAdoption
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#malformedManifestsNeverAuthorizeAdoption
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#linkedDirectoryAndMetadataAreRefused
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#everyNativeInitializerRefusesBeforeWriting
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#validPointerCannotClaimForeignActiveReplacement
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#validBackupCannotClaimForeignReplacement
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#flatLegacyIsNeverMoved
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#gcNeverTrustsSentinelPresenceAlone
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#nonRegularRecoveryControlRefusesWithoutFollowing
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#oversizedRecoveryMarkerKeepsObligation
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#absentActiveRecoversOwnedEvidenceWithRebuildObligation
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#unprovenMarkedFoldersDoNotConsumeBuildCapacity
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#everyRebuildPathIgnoresEveryUnprovenMarkedRepresentation
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#recordedLineEndingsRemainCompatibleAndTimestampBindingRemainsStrict
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#backupRepresentationIsNeverOpenedByAServingPointer
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#freshCreationNeverExposesIncompleteOwnership
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#nativeMigrationNeverExposesIncompleteOwnership
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#recordedCreationNeverExposesIncompleteOwnership
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#recoveredCreationNeverExposesIncompleteOwnership
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#publicationCollisionKeepsEmptyDirectory
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#publicationCollisionKeepsNonEmptyDirectory
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#publicationCollisionKeepsLinkTarget
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationCleanupAuthorityTest.java#emptyRemnantAfterFinalRemovalFailureIsReclaimedSeparately
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipDeletionTest.java#failedRenameNeverWritesThroughSymlinkDeleteMarker
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipDeletionTest.java#failedRenameReplacesHardLinkDeleteMarkerWithoutChangingExternalBytes
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipRuntimeAdmissionTest.java#everyManagedOpenInspectsTheWholeNamespaceBeforeLucene
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipRuntimeAdmissionTest.java#recoveryObligationIsDurableBeforeBackupAndReplacement
--check modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerGcOwnershipTest.java#gcResponseSeparatesEmptyRemnantsAndListsUnprovenCleanupPaths
```

Additional S15 repair checks (the migration/boot/previous-pointer exclusions in the same class are preservation checks):

```text
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationCleanupAuthorityTest.java#emptyRecordedFallbackRemnantIsReclaimedAfterFinalRemovalFailure
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationCleanupAuthorityTest.java#explicitGcReclaimsRecordedEmptyRemnantAfterBuildingPointerWasCleared
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#invalidStagingIdentityIsLeftAndReported
```

A regular-file publication collision already refused on the base; that case is preservation,
as are genuine sentinel-only adoption, native unequal timestamps, diagnostic backup GC,
foreign marked-directory survival in both GC modes, nested-link unlinking and retained payload
authority on deletion failure. The new publication checks observe the directory-creation boundary;
process-kill checks already present in GenerationPointerDurabilityTest remain unchanged.

## Continuation decisions and scope

- Windows uses forced files plus atomic moves, and directory forcing only on POSIX. The one
  comment at `IndexOwnership.forceDirectory` names the `AtomicFileWrites` power-loss limit.
- Only explicit GC admits the empty-recorded-remnant exception, with current migration IDLE,
  no current building generation, and no active/previous reference in either state file. Automatic
  cleanup and boot keep the pristine-recorded-orphan fence. A previous building pointer alone
  no longer protects a remnant after current state clears that build.
- Release030RetirementSafetyTest, Release030RetirementBudgetTest and
  Release030RetirementCompletedBuildTest keep valid historical ownership and temporarily deny
  the committed-compatibility certificate instead. Every existing cutover and retirement assertion
  is unchanged (Adaptation).
- Staging cleanup now also requires the identity component to parse as a canonical generation;
  invalid/marked identity names with a syntactically complete sentinel remain untouched and reported.

Additional interfaces/adapters/clients on the assigned call path: `contracts/wire/status.proto`,
app-api `IndexingService`, `MigrationGenerationView`, `MigrationStatusGroup`, status-response schema;
app-services `IndexGcHandler`, `MigrationOps`, `WorkerStatusMapper`; configuration
`WindowsOwnerOnlyDirectory`; worker-services `IndexStatusOps`, `MigrationControlOps`; UI
`StatusFieldClassification`; ui-web generated status-response types and `sv3-degradation.ts`.
`CleanShutdownMarker.java` is the additional adapters-lucene control-record caller on the runtime
open/close path. These expose ownership warnings and empty-leftover counts or preserve the refusal detail; there is
no additional persisted structure. No web tests, model/GPU inference, shared dev stack or user apps
are used by the builder. The shared Gradle slots and Gradle dependency caches are the shared
resources touched; each checkout owns its build folder and test resources. The existing Nvidia
availability HTTP check reads the host GPU telemetry/driver/tool availability; it performs no GPU
inference. That read-only shared resource is also named here.

## Observed check outcomes at the builder handoff

These are observations, not the formal evidence file or an acceptance verdict. The frontend is
not run by instruction. Pending checks in the prior handoff were replayed on latest source and
on 344bdaf03c in a fresh shared clone using the compatibility patch and listed overlays.

| Check id | Before | Candidate |
| --- | --- | --- |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java#refusedRecordedTargetLeavesUnprovenMarkedPayloadAndReleasesCapacity` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java#retryLeavesAndReportsMarkedPredecessorWithoutOwnershipMetadata` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationRetirementTest.java#pointerBoundPartialDeleteLeavesAndReportsForeignOwnershipEvidence` | assertion failure (fresh shared-clone replay) | passed (latest-source focused run) |
| `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerWorkerDownCodeTest.java#ownershipRefusalPreservesPathAndEvidenceAcrossRetriesUntilValidatedReady` | assertion failure | passed |
| `modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#S16: shows unavailable search, the rejected folder and manual remedy without Rebuild` | not run (explicit sandbox instruction) | not run (explicit sandbox instruction) |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationManagerRestartTest.java#unprovenMarkedRepresentationDoesNotConsumeCapacityAndForeignPayloadRefusesStart` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/GenerationPointerDurabilityTest.java#bootKeepsUnprovenScratchAndRemovesOnlyValidatedStateTemps` | assertion failure (fresh shared-clone replay) | passed (latest-source focused run) |
| `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java#foreignDirectoryRefusalPrecedesJournalMutationAndCarriesPathToFatalMarker` | assertion failure (fresh shared-clone replay) | passed (latest-source focused run) |
| `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java#interruptedBackupWithEmptyActiveRefusesExistingUnprovenTarget` | assertion failure | passed |
| `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java#recoveryOwnershipRefusesInvalidBackupManifest` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#plainForeignFolderIsNeverAdopted` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#foreignLuceneShapeIsNeverAdoptedOrCollected` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#malformedSentinelsNeverAuthorizeAdoption` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#malformedManifestsNeverAuthorizeAdoption` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#linkedDirectoryAndMetadataAreRefused` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#everyNativeInitializerRefusesBeforeWriting` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#validPointerCannotClaimForeignActiveReplacement` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#validBackupCannotClaimForeignReplacement` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#flatLegacyIsNeverMoved` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#gcNeverTrustsSentinelPresenceAlone` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#nonRegularRecoveryControlRefusesWithoutFollowing` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#oversizedRecoveryMarkerKeepsObligation` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#absentActiveRecoversOwnedEvidenceWithRebuildObligation` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#unprovenMarkedFoldersDoNotConsumeBuildCapacity` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#everyRebuildPathIgnoresEveryUnprovenMarkedRepresentation` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#recordedLineEndingsRemainCompatibleAndTimestampBindingRemainsStrict` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#backupRepresentationIsNeverOpenedByAServingPointer` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#freshCreationNeverExposesIncompleteOwnership` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#nativeMigrationNeverExposesIncompleteOwnership` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#recordedCreationNeverExposesIncompleteOwnership` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#recoveredCreationNeverExposesIncompleteOwnership` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#publicationCollisionKeepsEmptyDirectory` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#publicationCollisionKeepsNonEmptyDirectory` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java#publicationCollisionKeepsLinkTarget` | assertion failure | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationCleanupAuthorityTest.java#emptyRemnantAfterFinalRemovalFailureIsReclaimedSeparately` | assertion failure | passed |
| `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipDeletionTest.java#failedRenameNeverWritesThroughSymlinkDeleteMarker` | assertion failure | passed |
| `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipDeletionTest.java#failedRenameReplacesHardLinkDeleteMarkerWithoutChangingExternalBytes` | assertion failure | passed |
| `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipRuntimeAdmissionTest.java#everyManagedOpenInspectsTheWholeNamespaceBeforeLucene` | assertion failure (fresh shared-clone replay) | passed (latest-source focused run) |
| `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipRuntimeAdmissionTest.java#recoveryObligationIsDurableBeforeBackupAndReplacement` | assertion failure (fresh shared-clone replay) | passed (latest-source focused run) |

| `modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerGcOwnershipTest.java#gcResponseSeparatesEmptyRemnantsAndListsUnprovenCleanupPaths` | assertion failure in both accepted/rejected cases (expected empty-remnant count 3, observed 0; fresh replay) | passed (both cases, full latest-source UI run) |


| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationCleanupAuthorityTest.java#emptyRecordedFallbackRemnantIsReclaimedAfterFinalRemovalFailure` | assertion failure (empty remnant remained) | passed |
| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationCleanupAuthorityTest.java#explicitGcReclaimsRecordedEmptyRemnantAfterBuildingPointerWasCleared` | assertion failure (empty remnant remained) | passed |

| `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#invalidStagingIdentityIsLeftAndReported` | assertion failure (no cleanup warning) | passed (full latest-source worker-core run) |

The latest focused candidate run passed (exit 0, 2m34s): worker-core 38 checks; adapters-lucene
16 checks (one platform-conditional skipped); indexer-worker 48 checks including guardrails.
The adapted release fixtures passed on both base and candidate: Safety 10, Budget 7,
CompletedBuild 4. Recorded-empty-remnant preservation checks (automatic cleanup and boot fence,
current migration or building, active/previous bindings in previous state) passed before and after.
The focused before-state run exited 1 as expected: every pending repair check failed by
AssertionFailedError for the guarded behaviour, not compilation or an unrelated exception.
Raw logs and copied JUnit XML for the focused runs are in the builder's temporary folder under
`o1-focused.log`, `o1-before-focused.log`, `o1-candidate-focused-results/`,
`o1-before-focused-results/` (removed when the session ends).


The previous-session combined round exited 1. Its ownership implementation regressions and
invalid-sentinel release-fixture conflict are resolved by the latest focused/full runs above.
The previous-session Nvidia HTTP failure did not recur in either fresh candidate or baseline.
There is no `.agents/preflight` in this checkout.

## Latest full acceptance outcome

The complete requested module run plus configuration/app-api/worker-services finished with exit 1
in 30m41s. JUnit XML reports worker-core 630/0 failures, adapters-lucene 960/0,
indexer-worker 2500/1, configuration 370/0, app-api 480/0, app-services 5030/0,
UI 2374/1, ipc-common 4/0, worker-services 3283/4 (24 skipped in XML; the worker-services
console reports 3282 checks with 23 skipped).
All requested Java ownership repair checks, both HTTP GC parameter cases and the adapted release
fixtures passed. Platform-conditional skipped checks are not claimed as passes.

Failures, without edits to their checks:

- `LinuxRootMountInfoTest#missingDanglingAndAmbiguousUuidMappingsAreUnknown`:
  `NoSuchFileException: /dev/disk/by-uuid`. Reproduced on 344bdaf03c.
- `WatcherUnavailabilityScopeTest#aNewerUnavailableIncarnationCannotBeClearedByAnOlderReconciliation`:
  assertion `expected true but was false`. Reproduced on 344bdaf03c.
- `WatcherUnavailabilityScopeTest#endedUnavailableIncarnationIsRecoverableOnlyByItsOwnReconciliation`:
  assertion that attributed root loss must not set the runtime-wide latch. Reproduced on 344bdaf03c.
- `WatcherUnavailabilityScopeTest#inaccessibleRootWhoseAbsenceIsUnknownIsStillRootScoped`:
  assertion that an unreadable root must retain root-scoped uncertainty. Reproduced on 344bdaf03c.
- `VerifierGeneralCasesTest#unknownSharedProtocolFailureUsesCanaryAndSummarizesLogs`:
  expected DONE on protocol restoration, observed PENDING with attempts=1/RETRY_WITH_BACKOFF.
  Focused replay passed on both 344bdaf03c and candidate (no oracle or extraction code change).
- `StatusIsolationHttpTest#repeatedFreshDuringHangDoesNotPileUpSamplerThreads`:
  `status must be cheap; latency=526` versus its existing 500 ms bound. Focused replay passed on both
  344bdaf03c and candidate (no oracle or status-sampling code change).

Raw full-run log: temporary `o1-acceptance.log`; copied XML: `o1-candidate-acceptance-results/`.
The fresh additional before replays exited 1 as intended: invalid staging identity produced no
warning; both accepted and rejected HTTP GC cases omitted emptyLeftoversRemoved (observed 0
versus expected 3). They failed by assertion, not compilation or unrelated exceptions. Their
raw logs/XML are under `o1-before-environment*` and `o1-before-http*` in the temporary folder.

## Continuation commands

All Gradle commands run under `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot`, with
`-PskipWebBuild=true`, in the background. The before clone was created with
`git clone --quiet --shared --no-checkout . "$TMPDIR/before"` and detached checkout of
344bdaf03c, then the compatibility patch and the overlays above (no source stash/revert).

Final candidate acceptance (exit 1; final-source results below):

```sh
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true --continue :modules:worker-core:test :modules:adapters-lucene:test :modules:indexer-worker:test :modules:app-services:test :modules:ui:test :modules:ipc-common:test :modules:configuration:test :modules:app-api:test :modules:worker-services:test
```

Prepush (third/final run passed, exit 0, 5m25s; invokes the shared Gradle slot itself):

```sh
env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced
```

The UI GC check now covers accepted and rejected HTTP responses, with cleanup warnings on both.
The additional baseline replays covered that whole parameterized HTTP check and
`IndexOwnershipAdmissionTest#invalidStagingIdentityIsLeftAndReported`, as well as the three
previously observed environmental-failure classes (`NvidiaSmiAvailabilityHttpTest`,
`LinuxRootMountInfoTest`, `WatcherUnavailabilityScopeTest`).

## Final-source repair round

The first java-prepush run exited 1 in 7m42s: redundant qualifications in IndexOwnership,
IndexingService and IndexGenerationManager; an unused lock-resource local name; an unused private
migration-helper path argument; a redundant qualification of the release-fixture helper; and the
schema-mismatch HTTP integration fixture missing ownership evidence. The unreferenced-code check
passed. Mechanical PMD repairs remove those qualifications/unused argument (no policy change).
The schema HTTP fixture now seeds a full historical v1 sentinel; it retains every existing oracle,
its mismatched committed fingerprint, source document and startup escape. It is classified as
Adaptation. No PMD rule, test selector, expected outcome or bound is weakened.

The final-source module acceptance command ran independently of prepush and completed; its
outcome is below. The third/final prepush passed without further source or test changes.
The first full-run failure in the extraction protocol test and the 526 ms status sample did not reproduce in either focused base replay or focused candidate replay. The candidate
diagnostic run passed in 1m15s; base canary replay passed in 45s and base status replay in 36s.

## Third-round decision (2026-11-02, 15:30 UTC)

The second java-prepush completed in 5m51s with only the unchanged
`IndexingRulesBootstrapIntegrationTest#fullMainHoldsFreshBatteryAndTimesOutUnknownWithDurablyQueuedWork`
assertion: fresh BATTERY should leave the persisted queue pending, observed zero pending jobs.
PMD and all three UnreferencedCodeTest checks passed. The schema HTTP fixture passed on both
candidate and base (base replay 28s). The battery test passed in the first candidate prepush and
in its focused base replay (1m57s), so a single unchanged full prepush replay is justified to
separate a one-off loaded-run outcome from a repeated failure. No battery policy, expectation,
selector, timeout or fixture is changed. This is the third and final prepush run; if its failure
repeats, stop that sequence and hand the exact blocker back instead of repairing outside scope.
The final-source full module suite is already running independently of prepush.

## Final-source results

The final full module run completed with exit 1 in 29m33s. It reran worker-core, adapters-lucene,
indexer-worker, UI and worker-services; configuration, app-api, app-services and ipc-common remained
UP-TO-DATE from the preceding successful module outcomes. Full final JUnit XML totals:

| Module | Tests | Failures | Skipped (not claimed as passes) |
| --- | ---: | ---: | ---: |
| worker-core | 630 | 0 | 15 |
| adapters-lucene | 960 | 0 | 3 |
| indexer-worker | 2500 | 0 | 39 |
| configuration | 370 | 0 | 0 |
| app-api | 480 | 0 | 5 |
| app-services | 5030 | 0 | 11 |
| UI | 2374 | 1 | 2 |
| ipc-common | 4 | 0 | 0 |
| worker-services | 3283 | 4 | 24 |

All requested Java ownership repair checks and preservation fixtures passed on final source.
The canary and status-latency failures from the first full run did not recur. Remaining failures
are the four reproduced baseline mount/watcher failures listed above, plus
`NvidiaSmiAvailabilityHttpTest#anInstalledNvidiaSmiIsAvailableWhenNvmlSuppliesTheMemory`:
NVML supplied memory but the HTTP response reported nvidiaSmiAvailable=false despite the tool
being installed. That Nvidia case passed in the initial fresh baseline replay and again in the
exact focused baseline replay (34s), and passed in the first full candidate run. The prior-session
handoff recorded the same availability failure on the base. No Nvidia code or expectation is
edited; the full suite is explicitly not claimed green.

The third/final `node scripts/dev/java-prepush.mjs --unreferenced` passed (exit 0, 5m25s), including
PMD, the selected integration tasks and all three UnreferencedCodeTest checks (zero skipped).
The battery assertion did not recur, and the adapted schema HTTP preservation fixture passed
before and after. No further retry or source repair followed this pass.

Temporary final logs: `o1-acceptance-final.log`, `o1-prepush-third.log`; copied full-run XML:
`o1-final-acceptance-results/`. These are builder observations, not formal evidence/acceptance.
Web tests remain unrun by instruction. The orchestrator/verifier must create
`gates/test-intent/evidence/redesign-o1.json`, run the listed frontend check, independently verify
and append the acceptance record. No run-evidence invocation or acceptance record was authored
by the builder. No commit, push, publish, dev stack or GPU inference was performed.

## Acceptance records
