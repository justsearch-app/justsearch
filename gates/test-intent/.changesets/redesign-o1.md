---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: redesign-o1-builder
---

# Test-intent entries

Execution evidence and independent acceptance are left to the orchestrator/verifier, as requested.
The original web check was not executed in this sandbox; the real-system repair below records its executed frontend checks. Preservation checks are the existing module suites,
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
      "behaviour": "Ownership refusal presents unavailable search with the rejected folder and manual recovery instruction and no Rebuild action or keyword fallback. Empty or whitespace detail keeps the manual recovery fallback. The existing no-Rebuild assertion now requires the exact Open Health remedy (also resolves its invalid union-member property access); all other notice checks remain unchanged.",
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
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#S16: shows unavailable search, the rejected folder and manual remedy without Rebuild",
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#S16: an empty ownership detail keeps the manual recovery instruction"
      ]
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationManagerRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Unproven marked payload is left and excluded from retained counts and build capacity; foreign non-marked payload refuses further startup without being changed. The existing assertion that diagnostic backups are excluded from the retained count is kept; its backup now carries the full historical v1 sentinel and a manifest, so the exclusion is tested on a genuine owned backup rather than on a folder the ownership rule would exclude anyway. Other restart/pointer checks remain unchanged.",
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

- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java` — S1-S10, S12-S14, S17, U1-U3; P1-P3, P5, P8-P9 — durable.
- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationPublicationTest.java` — S12, S14 — durable.
- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationCleanupAuthorityTest.java` — S15, P4, P10; explicit GC only for unreferenced recorded empty remnants after IDLE/no-building; boot and automatic cleanup keep the orphan fence — durable.
- Tests added or rewritten: `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipDeletionTest.java` — S11 — durable.
- Tests added or rewritten: `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndexOwnershipRuntimeAdmissionTest.java` — S5, S13 — durable.
- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationGcFailureTest.java` — P5, P10; full historical v1 sentinel input — durable.
- Tests added or rewritten: `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexGenerationManagerRestartTest.java` — S1, S17; diagnostic-backup exclusion from the retained count kept — durable.
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

## Repair round after verifier-1 (2026-11-02)

Agent choices (U1-U3 and the narrowed S7) quoted from the decision that followed the first verification:

- S7 narrowed: "a valid exact backup plus a non-empty active replacement is refused only when the
  active directory holds anything other than Lucene index files (strict allowlist of Lucene
  file-name patterns, real regular files, no links, no subdirectories)."
- U1: "the v0.3.0-and-earlier recovered layout (state pointer names `<id>`; `<id>` holds only
  Lucene files; `<id>.bak-<ts>` holds a valid sentinel/manifest for `<id>`) starts, with metadata
  restored from the backup, in every state format where the base accepts it. A foreign file (for
  example `foreign.txt`) beside the Lucene files in that same layout: refused, unchanged (S7)."
- U2: "an active directory whose sentinel is a zero-length regular file and whose manifest is fully
  valid for the directory's own id (pointer names it) is owned: the sentinel is rewritten
  atomically from the manifest's identity before anything else writes. A zero-length sentinel
  without a valid manifest stays refused (S3)."
- U3: "the index base reached through a symbolic link anywhere above or at the base (data
  directory moved and linked) starts, fresh and existing."

Every released state pointer (v0.1.0 to v0.3.x) uses state format 2, so U1 is checked with the
v0.1.0 writer's state and manifest bytes and with a current-format state.

IndexOwnershipAdmissionTest is a new isolated file ([exempt]); its added checks are listed here.

Regression/preservation checks against the base (pass on 344bdaf03c, fail on the branch head
63d01df3f9 by assertion inside `assertDoesNotThrow`, pass on the candidate):

- `IndexOwnershipAdmissionTest#release030InPlaceRecoveredActiveStartsWithMetadataFromBackup` (U1)
- `IndexOwnershipAdmissionTest#inPlaceRecoveredActiveWithCurrentStateStartsThroughBoot` (U1)
- `IndexOwnershipAdmissionTest#emptySentinelBesideValidManifestStillStarts` (U2)
- `IndexOwnershipAdmissionTest#indexBaseBelowOrAtASymlinkStartsFreshAndExisting` (U3)

New checks (fail on 344bdaf03c by assertion, pass on the head and the candidate except where noted):

- `IndexOwnershipAdmissionTest#inPlaceRecoveredLayoutWithForeignFileIsRefusedUnchanged` (S7 narrowed;
  base restores metadata into the foreign layout)
- `IndexOwnershipAdmissionTest#emptySentinelWithoutManifestProofStaysRefused` (S3; base starts all three shapes)
- `IndexOwnershipAdmissionTest#emptySentinelIsRewrittenFromTheManifestIdentity` (U2; base leaves the
  sentinel empty; the head also fails it, by refusing)

`IndexGenerationManagerRestartTest#unprovenMarkedRepresentationDoesNotConsumeCapacityAndForeignPayloadRefusesStart`
again asserts "Admission excludes diagnostic backups" (on a genuine owned backup). On the base the
restored assertion passes and the check still fails by assertion at the later `.del-` count
(expected 1, was 2).

Extra verifier arguments for this round (the overlays above are unchanged):

```text
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#inPlaceRecoveredLayoutWithForeignFileIsRefusedUnchanged
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#emptySentinelWithoutManifestProofStaysRefused
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexOwnershipAdmissionTest.java#emptySentinelIsRewrittenFromTheManifestIdentity
```

Implementation summary: the namespace gate checks only the base and the entries below it without
following links, after one resolution of the base to its real path (the nearest existing ancestor's
real path plus any missing tail). It receives the active identity from the current-format state
pointer, bounded and read without following links, and admits only that directory in two
narrow cases: Lucene files only with agreeing owned exact backups (restored in place by
`reconcileActiveRecoveryOwnership`, rebuild obligation first, then sentinel and manifest by atomic
replace), or an empty regular sentinel with a valid manifest for the same identity (sentinel
rewritten atomically by the first initializer that may write; read-only guards never write).
Other initializers refuse an unrestored in-place layout. The runtime gate (no pointer) admits neither case.

Observed in this round (builder observations, not evidence): on 344bdaf03c with the unchanged
compatibility patch, the four regression checks passed, the three new checks failed by
AssertionFailedError, and the restart check failed at the later `.del-` count. On 63d01df3f9 the four
regression checks and the U2 rewrite check failed by AssertionFailedError (an unexpected
`index.ownership_unproven` inside `assertDoesNotThrow`). On the candidate, all 30 admission checks
and 7 restart checks passed. Final full module run (exit 1, 28m08s): worker-core 637/0,
adapters-lucene 960/0, indexer-worker 2500/1, app-services 5030/0, UI 2374/0, ipc-common 4/0. The
one failure, `ExtractionInfrastructureTest#snapshotStorage(readonly-child)`, was a 5000 ms
extraction-sandbox timeout in the fixture; focused replays passed on 344bdaf03c (8/0) and on the
candidate (8/0). An earlier full run of this round found that reading the pointer through the
lock-retrying reader delayed state publication under a held lock
(`IndexGenerationVduEligibilityTest#strictReadReopensAfterLockWaitAndRefusesAStateChangedByTheGenerationOwner`);
the pointer is now read lazily and without lock waits, and that check passes.
`node scripts/dev/java-prepush.mjs --unreferenced` passed (exit 0).

## Repair round after real-system review (2026-11-02)

S16 web repair: Search reads `readiness.engineComponents.index.evidence`, the field serialized by
status, and Health uses the same readiness-notice detail. During ownership refusal Health renders
the rejected folder and manual recovery instruction instead of recommended repair operations,
hides Reindex/Force Rebuild and indexing controls, does not poll failed jobs on the unavailable
index, and suppresses an already-observed/racing failed-job error. The keyword capability row
reads the ownership refusal from the same verdict. Other quick actions remain available.
The extra web file is an isolated new source ([exempt]); its five checks use local status inputs,
mock fetch, and remove mounted elements and restore global fetch/store state after every case.
All checks below are durable; no acceptance record or generated evidence was written by the builder.

`npx vitest run` with the two overlays below against afa1fdcec6: all six repair checks failed by
AssertionError (57 other checks passed). On the candidate, all six repair checks and the reached
notice/search/Health/capability suites passed (147 tests, six files). They also failed by assertion
on original main 344bdaf03c with no compatibility patch (the earlier S16 check also failed there;
56 preservation checks passed). TypeScript no-emit and ESLint on all six changed TypeScript files passed.
The initial candidate check found a missing OPEN_HEALTH import in the new test; corrected before
these final outcomes. The typecheck also exposed the earlier S16 test accessing `label` on the
operation-or-navigation union; its no-Rebuild assertion now requires the exact Open Health remedy.
The test-intent CLI ran against 344bdaf03c. It reports the expected stale evidence for this changed
notice file and the prior repair's restart test, plus missing independent acceptance. The builder
has not weakened or changed those gates or authored their acceptance/evidence.

Orphan-marker finding: the temporary `OrphanCleanShutdownProbeTest` initialized a real manager,
seeded an old generation with the full historical v1 sentinel plus payload and sibling clean-shutdown
marker, ran `gcBestEffort(0, false)`, asserted one generation pruned, and observed that the sibling
still contained `clean\n`. It passed on 344bdaf03c (24 seconds) and afa1fdcec6 (33 seconds), each
in its own shared temporary clone/build folder. This is pre-existing behavior; no marker cleanup
change is made. The probe is scaffolding in the temporary clones only, outside the product suite.

The fatal-index latch itself already recognizes ownership (bootstrap), retains it (LifecycleReasonCode),
and carries its detailed marker (WorkerFatalReasonMarker); HeadlessApp already forwards that detail.
Java restart policy remains a decision: `KnowledgeServerHealthMonitor.immediateEscalation` and
`ComponentRecoveryMonitorTest#essentialIndexFatalReasonsEscalateOnFirstTickWithoutAdmittingRecovery`
explicitly require schema/corrupt failures to restart an essential index's Engine. Applying that
policy exactly to ownership contradicts S16's requirement that the API stay available. The builder
asked whether to suppress automatic restart for ownership only or for all fatal-index refusals;
no Java policy change is made pending that answer. This follows the builder's come-back rule for
a design/scenario contradiction, rather than changing the existing schema/corruption contract.

Fail-before replay options for this repair (no `--before-patch` needed on afa1fdcec6 or 344bdaf03c):

```text
--before afa1fdcec6
--overlay modules/ui-web/src/shell-v0/state/readinessNotice.test.ts
--overlay modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts
--check modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#S16: an empty ownership detail keeps the manual recovery instruction
--check modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: the search notice reads the rejected path from status evidence
--check modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health shows the folder and manual recovery instruction
--check modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health never offers Reindex, Force Rebuild or Retry for an unproven folder
--check modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health does not claim keyword search is available during refusal
--check modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health suppresses refused-index fetch errors while preserving the folder notice
```

| Check | Before afa1fdcec6 | Candidate |
| --- | --- | --- |
| `modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#S16: an empty ownership detail keeps the manual recovery instruction` | assertion: whitespace body omitted manual recovery | passed |
| `modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: the search notice reads the rejected path from status evidence` | assertion: generic body omitted rejected path/detail | passed |
| `modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health shows the folder and manual recovery instruction` | assertion: rejected path absent | passed |
| `modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health never offers Reindex, Force Rebuild or Retry for an unproven folder` | assertion: core.reindex was offered | passed |
| `modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health does not claim keyword search is available during refusal` | assertion: keyword row claimed Available | passed |
| `modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health suppresses refused-index fetch errors while preserving the folder notice` | assertion: raw Failed to fetch was rendered | passed |

Final check commands for the real-system repair:

```sh
# In modules/ui-web:
npx vitest run src/shell-v0/views/OwnershipRefusal.test.ts src/shell-v0/state/readinessNotice.test.ts src/shell-v0/views/HealthSurface.render.test.ts src/shell-v0/views/HealthSurface.recovery.test.ts src/shell-v0/components/CapabilityMap.test.ts src/shell-v0/views/search-v3/SearchV3View.degradation.test.ts --maxWorkers=2
npx tsc -p tsconfig.json --noEmit --incremental false
npx eslint src/shell-v0/components/CapabilityMap.ts src/shell-v0/state/readinessNotice.ts src/shell-v0/state/readinessNotice.test.ts src/shell-v0/views/HealthSurface.ts src/shell-v0/views/OwnershipRefusal.test.ts src/shell-v0/views/search-v3/sv3-degradation.ts --no-cache
# In the repository root:
env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true --continue :modules:worker-core:test :modules:adapters-lucene:test :modules:indexer-worker:test :modules:app-services:test :modules:ui:test :modules:ipc-common:test
```

Prepush completed successfully (8 seconds, 246 tasks up-to-date and five from cache; cached outcomes
are identified here, not described as fresh executions). The combined module command finished with exit 1 in 26m59s: indexer-worker freshly ran
2,500 tests, one failure and 39 skipped. Worker-core, adapters-lucene, app-services, UI and ipc-common
test tasks were UP-TO-DATE. Skipped cases are not claimed as passes. The single failure and focused
baseline outcomes are recorded below. No Java source was edited in this repair; the runtime-restart
policy remains unresolved.
One failure was reported during the combined module run:
`ReturnedRootCurrentByteReplayTest#provenAbsentUnavailablePredecessorWakesWithoutARegularSourceHash`,
line 176: the replay Optional was absent, violating the existing "proven absence must retain the
retry-only wake path" assertion. Focused replays passed on both original main 344bdaf03c and
repair baseline afa1fdcec6 (17 seconds each). All Java sources in this session's candidate equal
afa1fdcec6, so this is not attributed to the web repair, but the combined suite is not claimed green
or the failure claimed environmental. No oracle, fixture, bound, selector or product code was
changed for it. Raw logs are temporary `o1-returned-root-base.log`, `o1-returned-root-before.log`
and `o1-realsys-modules.log`.

Shared resources: the prescribed Gradle slots and Gradle dependency/build cache only. Every baseline
clone has its own build folders and copied frontend dependencies. No running owner app, dev stack,
GPU, unrelated service or other session's dependency tree was used. No `.agents/preflight` exists.

## Ownership recovery policy repair (2026-11-02)

The final S16 decision supersedes the unresolved restart-policy question above: ownership refusal
suppresses both automatic component recovery and Engine restart, and only a validated open clears
its code, path and evidence class. Corruption and schema refusals retain their existing policy.
The UI draft was reviewed and retained; Health also hides the pre-existing index recovery action
while ownership is unproven. The orphan clean-shutdown probe is absent from this checkout and the
new temporary clone; its earlier before/after finding stands, and no marker cleanup is changed.

Implementation: `KnowledgeServerHealthMonitor` excludes an ownership-refused index from recovery
admission and retained-owner escalation (including an exhausted budget and pending initial owner).
`KnowledgeServerBootstrap` ends its startup retry arc on this refusal, rejects physical component
recovery while it is latched, and preserves the original fatal detail even if another fatal marker
arrives. A direct start can still validate the index after the user's manual repair; a healthy
physical observation clears the fatal latch. HeadlessApp keeps publishing the bootstrap detail and
no longer claims boot recovery is armed for every startup refusal. No schema/corruption policy,
persisted value, scenario or ownership validator changes.

Source of intent (agent choice):

- kind: delegated-choice
- delegationQuote: "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed"
- delegationLocation: "task t-20261102-redesign request"
- delegationScope: "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list"
- choice: "S16 The refusal reaches the user: HTTP status/readiness carries `index.ownership_unproven` with the rejected path and evidence class, sticky across retries, cleared after a validated start; UI notice says search is unavailable (not keyword-fallback wording), shows path and instruction, offers no Rebuild."
- label: "agent choice"

The new `OwnershipRefusalRecoveryTest` is isolated ([exempt]), changes no global state, uses only
its temporary directory and closed executor/registry fixtures, and adds five durable checks.
The injected clock advances 400 seconds without sleeps, beyond the recovery backoffs and the
one-minute physical-owner deadline. These are component/bootstrap checks; the orchestrator's
real-system verification still owns the complete running-app/HTTP boundary proof.

Fail-before replay (builder observations, not formal evidence or acceptance):

```text
--before afa1fdcec6
--overlay modules/app-services/src/test/java/io/justsearch/app/services/worker/OwnershipRefusalRecoveryTest.java
--overlay modules/ui-web/src/shell-v0/state/readinessNotice.test.ts
--overlay modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts
--check modules/app-services/src/test/java/io/justsearch/app/services/worker/OwnershipRefusalRecoveryTest.java#ownershipRefusalDoesNotAdmitAutomaticOrManualRecovery
--check modules/app-services/src/test/java/io/justsearch/app/services/worker/OwnershipRefusalRecoveryTest.java#ownershipRefusalDoesNotRestartEngineAfterBudgetAndOwnerDeadline
--check modules/app-services/src/test/java/io/justsearch/app/services/worker/OwnershipRefusalRecoveryTest.java#ownershipRefusalStopsStartupRetriesAndKeepsStatusUntilValidatedStart
--check modules/app-services/src/test/java/io/justsearch/app/services/worker/OwnershipRefusalRecoveryTest.java#ownershipFatalLatchKeepsPathAndEvidenceUntilAValidatedStart
--check modules/app-services/src/test/java/io/justsearch/app/services/worker/OwnershipRefusalRecoveryTest.java#ownershipLatchRefusesPhysicalComponentRecovery
--check modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#S16: an empty ownership detail keeps the manual recovery instruction
--check modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: the search notice reads the rejected path from status evidence
--check modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health shows the folder and manual recovery instruction
--check modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health never offers Reindex, Force Rebuild or Retry for an unproven folder
--check modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health does not claim keyword search is available during refusal
--check modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts#S16: Health suppresses refused-index fetch errors while preserving the folder notice
```

No `--before-patch` is needed for afa1fdcec6. Original-scenario replay remains
`--before 344bdaf03c --before-patch gates/test-intent/compatibility/redesign-o1-before.patch`
with the earlier overlays/checks in this changeset; those original results were not rerun in this
bounded continuation. No generated evidence or acceptance record was authored.

| Java check (OwnershipRefusalRecoveryTest) | Before afa1fdcec6 | Candidate |
| --- | --- | --- |
| `ownershipRefusalDoesNotAdmitAutomaticOrManualRecovery` | assertion: ACCEPTED instead of NOT_APPLICABLE | passed |
| `ownershipRefusalDoesNotRestartEngineAfterBudgetAndOwnerDeadline` | assertion: one Engine escalation instead of zero | passed |
| `ownershipRefusalStopsStartupRetriesAndKeepsStatusUntilValidatedStart` | assertion: three starts instead of one | passed; detail retained over 400 seconds and cleared after healthy start |
| `ownershipFatalLatchKeepsPathAndEvidenceUntilAValidatedStart` | assertion: schema refusal replaced ownership latch | passed; validated start clears latch |
| `ownershipLatchRefusesPhysicalComponentRecovery` | assertion: physical owner returned SUPERSEDED instead of bootstrap refusing | passed; physical owner never invoked |

The focused command on each tree was:

```sh
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true :modules:app-services:test --tests io.justsearch.app.services.worker.OwnershipRefusalRecoveryTest --tests io.justsearch.app.services.worker.ComponentRecoveryMonitorTest
```

Candidate: 46 tests, zero failures/skips, exit 0 (32 seconds). Before: 46 tests, exactly the five
new assertion failures, zero skips, exit 1 (20 seconds). All 41 unchanged ComponentRecoveryMonitorTest
checks pass on both, including essential corruption/schema escalation and optional local recovery.
The before checkout is a shared clone under `$TMPDIR/o1-sticky-before` with its own build folders.
Frontend dependencies are a separate reflink/copy in that clone, never a write into the candidate's
dependency tree. Candidate Vitest: all 147 tests in the six reached files passed (4.84 seconds).
TypeScript no-emit and ESLint passed. Replaying the two web overlays on afa1fdcec6 failed all six repair checks by assertion (57 preservation checks passed), matching the per-check table above. Final suite/prepush outcomes follow below.
The test-intent CLI ran against 344bdaf03c: all flagged items have entries, but the gate still
requires refreshed independent evidence for readinessNotice and IndexGenerationManagerRestartTest,
and independent acceptance. The builder leaves those to the verifier as instructed.

### Full module run in this continuation

The prescribed six-module command (printed above) completed with exit 1 in 27m06s. It freshly
executed app-services, indexer-worker and UI; worker-core, adapters-lucene and ipc-common test tasks
were UP-TO-DATE. Skips are not claimed as passes.

| Module | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| worker-core | 637 | 0 | 15 |
| adapters-lucene | 960 | 0 | 3 |
| indexer-worker | 2500 | 0 | 39 |
| app-services | 5035 | 0 | 11 |
| UI | 2374 | 1 | 2 |
| ipc-common | 4 | 0 | 0 |

The one failure is the unchanged
`NvidiaSmiAvailabilityHttpTest#anInstalledNvidiaSmiIsAvailableWhenNvmlSuppliesTheMemory`, line 83:
the HTTP response reports `nvidiaSmiAvailable=false` where the fixture expects true. No Nvidia
code, fixture, oracle, selector or timeout changed. Original main 344bdaf03c was checked out in a
second shared clone, `$TMPDIR/o1-sticky-main`; the exact class freshly passed there (2 tests,
zero failures/skips, exit 0, 16 seconds after 455 seconds waiting for a slot). This is not proof of
an environmental failure; the full module command is not claimed green.

The focused baseline command was:

```sh
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true :modules:ui:test --tests io.justsearch.ui.api.NvidiaSmiAvailabilityHttpTest
```

Temporary logs for this continuation: `$TMPDIR/o1-sticky-focused.log`,
`o1-sticky-before.log`, `o1-sticky-web.log`, `o1-sticky-before-web.log`, `o1-sticky-tsc.log`,
`o1-sticky-eslint.log`, `o1-sticky-test-intent.log`, `o1-sticky-modules.log`,
`o1-sticky-main-nvidia.log`, `o1-sticky-prepush.log`, and `o1-sticky-candidate-nvidia.log`.
The complete broad-run JUnit XML was copied to `$TMPDIR/o1-sticky-full-results/` before the focused
replay can replace module results. Shared resources touched: the prescribed Gradle slots and
Gradle dependency/build cache. No owner app, shared dev stack, GPU workload or other session's
service or dependency tree was used. No commit, push, publish, generated evidence or acceptance
record was made. No `.agents/preflight` exists. All five new Java checks and all six web repair
checks are durable; no scaffolding probe remains.

### Final prepush and focused replay

`env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced` passed
(exit 0, 5m56s after 1,626 seconds waiting for a shared slot; 23 tasks executed, 10 from cache,
218 up-to-date). PMD and all three UnreferencedCodeTest checks passed. The reached app-services
and UI integration tasks ran; indexing, reranker and app-api integration tasks were UP-TO-DATE.
No repair or retry of prepush was needed in this continuation.

The same focused Nvidia command printed above then freshly passed on the candidate (exit 0,
13 seconds, two tests, zero failures/skips). Thus the one broad-suite Nvidia assertion did not
reproduce in either the original-main or candidate focused replay. It is not relabeled a pass:
the broad module command still exited 1. No further test or product repair followed that replay.
The final diff check passed, and the branch head remains afa1fdcec6; every change is uncommitted.

Tests added or rewritten in this continuation:

- `modules/app-services/src/test/java/io/justsearch/app/services/worker/OwnershipRefusalRecoveryTest.java` — S16 recovery, restart, retention and validated-start clearing (five checks) — durable
- `modules/ui-web/src/shell-v0/state/readinessNotice.test.ts` — S16 empty-detail remedy and exact no-Rebuild remedy — durable
- `modules/ui-web/src/shell-v0/views/OwnershipRefusal.test.ts` — S16 search path, Health notice, no repair/retry action, keyword availability and fetch-error suppression (five checks) — durable

Open for independent verification: the complete running-app/HTTP refusal across the former restart
window; refreshed test-intent execution evidence and acceptance; assessment of the unchanged broad
suite Nvidia failure. The builder does not author the verifier's evidence or acceptance and does
not change GPU detection to resolve an out-of-scope assertion.

## Final repair round: native upgrade admission and pointer protection

This is the third and final repair round. The candidate is narrowed to the verifier's four
findings; creation, retirement, recorded bindings, and the existing S17 unreferenced-leftover
exemption retain their contracts. Admission now reads current state and then the previous pointer
exactly in native boot order, without restoring or normalizing either. It protects active, building
and previous directory bindings, including marked and empty directories, before any initializer
writes. Recovery restores the native v1/.prev historical layout without changing recorded fallback
fences. Lucene 10.4.0's bundled codec extensions were read from the dependency's constants; unknown
extensions and files whose link count cannot be established as one are refused. Windows uses the
existing no-follow native handle and its BY_HANDLE_FILE_INFORMATION link count. The two OS reads
now call the existing configuration funnel; SystemAccessFunnelTest and its allowlist are unchanged.
A genuine foreign Lucene index with an agreeing backup remains an explicitly documented limit.

Call-path addition: `WindowsOwnerOnlyDirectory#requireSingleLink` exposes that existing native
inspection to the Lucene adapter. No new persisted structure or persisted meaning is introduced.
The unchanged clean-shutdown sibling issue was reproduced by verifier-2 on both main and the
candidate, so no control-file deletion was added.

Both new test files are isolated ([exempt]), durable, use only temporary resources, and do not
change process-global state. The scoped static AtomicFileWrites mock closes after every case.
The release checks read the committed upgrade-030/small.zip without relocation or rewriting any
Lucene bytes, exercise both historical layouts, and assert all 38 documents remain searchable.
Synthetic checks also search a real one-document Lucene index. The marked-pointer matrix covers
active/building/previous, DELETEME/deletion-name/empty shapes, v2/v1 and absent/empty/torn .prev
fallbacks, through seven initializers; whole-namespace snapshots include state.json and .prev.

Sources of intent (agent choices, one per quoted scenario):

- kind: delegated-choice
- delegationQuote: "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed"
- delegationLocation: "task t-20261102-redesign request"
- delegationScope: "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list"
- choice: "U1/U2 completed: the admission repair resolves the active identity exactly as the base's boot does in every state the base accepts: state.json format 2, state.json format 1 (native normalization), and state.json.prev when state.json is absent, empty or torn and the base falls back to it."
- choice: "S5/S6 completed: a directory named by any resolved state pointer (active, building or previous; from v2, v1 or .prev) is never treated as a warning-only marked leftover, whatever it contains (DELETEME, .del- name, missing metadata)."
- choice: "S7 allowlist tightened: only real regular files with a link count of 1 where the platform reports it (on Windows through the existing native-handle checks; if a platform cannot report it, refuse rather than assume), whose names match the Lucene index-file grammar of the bundled Lucene version (segments_N, write.lock, and _<generation>[_<suffix>].<extension> with the extensions that Lucene's codecs in this build actually write); anything else is refused, unchanged."
- label: "agent choice"

Defect safeguards: no data loss (the pointer matrix and link/extension refusals prevent writing or
claiming foreign bytes), no leak across a requested scope (an external hard-link alias keeps its
bytes when the empty sentinel is atomically replaced). Reproductions construct these objects on a
real temporary filesystem and compare returned refusal plus byte snapshots. Compatibility checks
are preservation against original main and regression repairs against the rejected candidate;
they are deliberately not misclassified as original-main assertion failures in `newChecks`.

Repair replay (no compatibility patch is needed on 49486d3eca):

```text
--before 49486d3eca
--overlay modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java
--overlay modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ReleasedOwnershipUpgradeTest.java
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java#recoveredActiveAcceptsV1Pointer
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java#recoveredActiveAcceptsEveryPreviousPointerFallback
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java#emptySentinelAcceptsV1Pointer
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java#emptySentinelAcceptsEveryPreviousPointerFallback
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java#everyPointedUnprovenMarkedDirectoryRefusesEveryInitializerBeforeWrites
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java#backupAuthorityRejectsHardLinkedLuceneFilesUnchanged
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java#backupAuthorityRejectsUnknownLuceneExtensionUnchanged
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ReleasedOwnershipUpgradeTest.java#recoveredReleasedBytesStartWithV1AndEveryPreviousPointerFallback
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ReleasedOwnershipUpgradeTest.java#emptySentinelReleasedBytesStartWithV1AndEveryPreviousPointerFallback
--check modules/dead-code-audit/src/test/java/io/justsearch/deadcode/SystemAccessFunnelTest.java#every_system_access_outside_the_configuration_funnel_is_on_the_allowlist
```

Additional preservation checks on 49486d3eca (same overlays):

```text
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java#everyInitializerRepairsEmptySentinelBeforeOtherWritesWithoutChangingHardLinkAlias
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java#readOnlyGuardsLeaveEmptySentinelAndPointersUntouched
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java#historicalLayoutsKeepCurrentPointerCompatibility
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ReleasedOwnershipUpgradeTest.java#releasedBytesKeepCurrentPointerCompatibility
```

Original-main compatibility replay uses `--before 344bdaf03c` with the same two overlays, no
`--before-patch` (these tests use only signatures already present there), and the first four worker
checks, both historical release checks, and the two current-pointer compatibility checks above.
The U2 first-write oracle requires the new atomic repair and is not an original-main preservation
claim. Original S1-S17 replay remains `--before 344bdaf03c --before-patch
gates/test-intent/compatibility/redesign-o1-before.patch` with the earlier check lists; it was not
repeated in this bounded round. No generated evidence or independent acceptance is authored here.

Initial harness setup is not counted as execution evidence: an overloaded JUnit method reference
failed compilation and was replaced by an explicit lambda. The original-main native boot path does
not guarantee metadata presence after v1/.prev fallback, so preservation observes active identity
and searchable documents. Empty-sentinel preservation uses initializeForBoot directly, as the
verifier's U2 reproduction does; recovery reconciliation is independently covered by the U2
first-write check, where the repair itself is required. No pre-existing check or oracle changed.

Round-3 execution results follow; all launched checks have finished.

### Round-3 execution observations

These are builder observations from JUnit XML and command logs, not formal evidence or acceptance.

| Check (class#method; paths in the replay lists above) | 49486d3eca | Candidate | 344bdaf03c preservation |
| --- | --- | --- | --- |
| `HistoricalOwnershipAdmissionTest#backupAuthorityRejectsHardLinkedLuceneFilesUnchanged` | assertion failure | passed | not selected |
| `HistoricalOwnershipAdmissionTest#backupAuthorityRejectsUnknownLuceneExtensionUnchanged` | assertion failure | passed | not selected |
| `HistoricalOwnershipAdmissionTest#emptySentinelAcceptsEveryPreviousPointerFallback` | assertion failure | passed | passed |
| `HistoricalOwnershipAdmissionTest#emptySentinelAcceptsV1Pointer` | assertion failure | passed | passed |
| `HistoricalOwnershipAdmissionTest#everyInitializerRepairsEmptySentinelBeforeOtherWritesWithoutChangingHardLinkAlias` | passed | passed | not selected |
| `HistoricalOwnershipAdmissionTest#everyPointedUnprovenMarkedDirectoryRefusesEveryInitializerBeforeWrites` | assertion failure | passed | not selected |
| `HistoricalOwnershipAdmissionTest#historicalLayoutsKeepCurrentPointerCompatibility` | passed | passed | passed |
| `HistoricalOwnershipAdmissionTest#readOnlyGuardsLeaveEmptySentinelAndPointersUntouched` | passed | passed | not selected |
| `HistoricalOwnershipAdmissionTest#recoveredActiveAcceptsEveryPreviousPointerFallback` | assertion failure | passed | passed |
| `HistoricalOwnershipAdmissionTest#recoveredActiveAcceptsV1Pointer` | assertion failure | passed | passed |
| `ReleasedOwnershipUpgradeTest#emptySentinelReleasedBytesStartWithV1AndEveryPreviousPointerFallback` | assertion failure | passed | passed |
| `ReleasedOwnershipUpgradeTest#recoveredReleasedBytesStartWithV1AndEveryPreviousPointerFallback` | assertion failure | passed | passed |
| `ReleasedOwnershipUpgradeTest#releasedBytesKeepCurrentPointerCompatibility` | passed | passed | passed |
| `SystemAccessFunnelTest#every_system_access_outside_the_configuration_funnel_is_on_the_allowlist` | assertion failure | passed | not selected |

All before-state failures are AssertionFailedError or MultipleFailuresError, with no skips or
unrelated exceptions. The pointed-directory test reaches 378 combinations; 369 assertions fail on
49486d3eca, and all 378 combinations pass on the repaired candidate. The native v1/.prev
compatibility checks are preservation against main, rather than original-main fail-before claims.

The complete module command exited 1 (27m56s plus 61 seconds waiting for a slot). Its saved XML
is in `$TMPDIR/o1-round3-full-results/`, captured before focused replays could replace it.

```sh
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true --continue :modules:configuration:test :modules:worker-core:test :modules:adapters-lucene:test :modules:indexer-worker:test :modules:app-services:test :modules:ui:test :modules:ipc-common:test
```

| Module | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| configuration | 370 | 0 | 0 |
| worker-core | 647 | 0 | 15 |
| adapters-lucene | 960 | 0 | 3 |
| indexer-worker | 2503 | 1 | 39 |
| app-services | 5035 | 1 | 11 |
| ui | 2374 | 1 | 2 |
| ipc-common | 4 | 0 | 0 |

Exactly three unchanged tests failed in the broad command:

- `ExtractionInfrastructureTest#snapshotStorage(readonly-child)`: the initial healthy-source extraction timed out after 5,000 ms.
- `GplStageReportWriteOnlyTest#no production code reads either stage-3 report back`: Files.walk encountered a removed `modules/indexer-worker/build/tmp/test-jvm/test/junit-*` directory.
- `NvidiaSmiAvailabilityHttpTest#anInstalledNvidiaSmiIsAvailableWhenNvmlSuppliesTheMemory`: nvidiaSmiAvailable was false although the fixture expected true.

Main compatibility replay: exit 0; all eight preservation methods passed (five synthetic and
three released-byte methods). Its worker task reused the identical final-source results freshly
executed in the preceding main replay; the released-byte methods ran freshly. Main's focused
ExtractionInfrastructureTest also freshly passed all eight cases. Main's focused GPL check freshly
passed all three cases. The first main Nvidia result was FROM-CACHE and is not counted as a fresh
environmental replay; the subsequent `--rerun` invocation freshly passed both Nvidia checks
(exit 0, 14 seconds). The broad command is not relabeled green.

Final candidate compatibility/extraction replay: exit 0, 16 selected tests, zero failures or skips.
All 13 new ownership methods and the unchanged system-access guard passed on the candidate.
ExtractionInfrastructureTest freshly passed all eight cases on both main and the candidate; the
five-second warmup failure in the broad run did not reproduce in either focused replay.

Candidate GPL/Nvidia replay: exit 0, 27 seconds, all five checks freshly passed without skips.
All three broad-suite failures therefore passed focused replays on both main and the candidate.
This does not establish that the broad-suite failures were environmental or that the broad
command passed.

`env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced`
passed (exit 0, 7m25s after 770 seconds waiting for a shared slot; 76 tasks executed,
two from cache, 173 up-to-date). Compilation, PMD, all three UnreferencedCodeTest checks and
the reached integration tasks completed without failures; skipped real-drive/model cases are
not claimed as passes. The outer check driver returns exit 1 to preserve the earlier module
suite failure, rather than masking it with the later successful prepush.

`node scripts/governance/gates/test-intent/cli.mjs --base 344bdaf03c` exits 1 with exactly one
problem: missing independent acceptance. All flagged files are covered, and both new test files
are classified [exempt]. The builder has not supplied an acceptance record. `git diff --check`
passes. No tests or product code changed after the reported final checks.

Handback: implementation complete, acceptance blocked by the three broad-suite failures in
unchanged tests. They need independent assessment by the orchestrator; their focused passes are
reported without calling the broad command green. The original S1-S17 fail-before evidence and
compatibility patch remain unchanged; this round adds the final-candidate regression/preservation
observations above.

Shared resources touched: the prescribed Gradle slots and Gradle dependency/build cache, plus
machine CPU. Tests used their own temporary files, child processes and ports. No shared dev stack,
owner application, GPU workload or another checkout's dependency tree was used. Both baseline
checkouts are shared clones inside `$TMPDIR`, with independent build folders. The verifier's
`tmp/verifier2-probes/` was read only. No `.agents/preflight` exists. No commit, push, publication,
generated evidence or independent acceptance was made. Head remains 49486d3eca.

Tests added or rewritten in this round:

- `modules/worker-core/src/test/java/io/justsearch/indexerworker/index/HistoricalOwnershipAdmissionTest.java` — U1/U2, S5/S6 pointed-directory admission, S7 link/codec allowlist and read-only/atomic-repair preservation — durable
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ReleasedOwnershipUpgradeTest.java` — U1/U2 with unchanged released index bytes and 38 searchable documents in every pointer layout — durable

## Narrowed P3 repair: writer-sized pause state

The admission-only 16,384-byte state limit is removed. The regular-file and no-follow
checks, absent/unparsable fallback, and all other production behavior stay unchanged.
The pointer-is-a-regular-file probe remains a named limit: it also fails on original main
344bdaf03c and is outside this repair.

`LongPauseStateAdmissionTest#nativeBootAcceptsLongPauseStateWrittenByManager` is an
isolated new durable preservation check for P3: "Valid pointers and owned generations:
normal start; upgrade from existing generation layout; state v1 normalization
(inspection runs before normalization)." It calls the real state writer with a
20,009-character ASCII pause reason or 10,000 quote characters (JSON escaping makes
the state exceed the former bound), then uses a fresh manager's native initialization
and checks the same active generation, pause flag and exact reason. Both current state
and absent-current/previous-state fallback are exercised. Each invocation uses only its
own `@TempDir`; it changes no global state and uses no external fixtures.

Intent source (delegated-choice):

- delegationQuote: "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed"
- delegationLocation: "task t-20261102-redesign request"
- delegationScope: "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list"
- choice: "P3 Valid pointers and owned generations: normal start; upgrade from existing generation layout; state v1 normalization (inspection runs before normalization)."
- label: "agent choice"

This check is preservation against 344bdaf03c and a regression repair against
6a2b59d5ba. It is therefore supplied as an extra isolated-file check, not misclassified
as an original-main assertion failure in `newChecks`. Existing entries, original S1-S17
evidence, and their compatibility patch are unchanged. No acceptance record or generated
evidence is authored in this round.

Exact regression replay:

```text
--before 6a2b59d5ba
--overlay modules/worker-core/src/test/java/io/justsearch/indexerworker/index/LongPauseStateAdmissionTest.java
--check modules/worker-core/src/test/java/io/justsearch/indexerworker/index/LongPauseStateAdmissionTest.java#nativeBootAcceptsLongPauseStateWrittenByManager
```

Exact original-main preservation replay: use `--before 344bdaf03c` with the same overlay
and check. Neither replay needs `--before-patch` (all used signatures exist on both
before states). Keep the earlier `--before-patch
gates/test-intent/compatibility/redesign-o1-before.patch` only for the original S1-S17
check lists. Execution observations for this round follow after all checks finish.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a19f13-e442-7c13-98b2-b94e53537f73",
  "verdict": "accept",
  "digest": "sha256:0b3f2ccdc8edb64a0036fab30c15dd0efb05842b6e94ae32e48e59f3613ecdad",
  "note": "Transcribed by the orchestrator from verifier-4 (Codex, session 01a19f13-e442-7c13-98b2-b94e53537f73) on 92a6c6e04c: accept E1-E9."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a03e-494a-7d43-af1f-99e90c2e36c7",
  "verdict": "accept",
  "digest": "sha256:1aa21572b351929ee60bb8f5d91ad00fa20ceeeee9bcadb0bd8eb9c208ba4f32",
  "note": "Focused verifier-5 on 005051b428 after the merge of origin/main: entries E1-E9 accepted; entries, checks and evidence unchanged against 1d3284e105; the merged status fixture keeps main's enrichment outcome field and the ownership cleanup-warning fields, no assertion weakened."
}
```
