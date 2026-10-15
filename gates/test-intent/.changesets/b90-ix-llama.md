---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b90-ix-llama
---

# Test-intent entries

Round 3 repairs start at 1f8ce3e01; round 2 starts at 7de3976a1. The whole-change reference and final gate base remain 61ff9b955. Historical script-produced evidence is retained for unchanged checks; affected round-2 file digests are refreshed against 7de3976a1, while new round-3 failures and preservation run against 1f8ce3e01. Delegation is the owner turn at 2026-10-11T16:05Z. Every design choice cites that delegation rather than presenting reviewer words as owner words. Evidence paths below are produced by the unchanged canonical runner. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "behaviour": "VDU ownership refuses preparation before drain and across entry/prepared transaction windows; external self-tests name the external owner; split-device placement does not apply another adapter's reduced caps.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "VDU ownership refuses preparation before drain and across entry/prepared transaction windows; external self-tests name the external owner; split-device placement does not apply another adapter's reduced caps.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#anExternalServerRefusalNamesTheExternalServer",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#heldVduRefusesPreparationBeforeAnyDrain",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#pendingVduEntryRefusesPreparationBetweenItsTwoSteps",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#aPreparedConfigurationRetainsOwnershipUntilCommitOrAbort",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java#splitDevicePlacementNeverAppliesAnotherAdaptersReducedCaps"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-app-services.json"
    },
    {
      "behaviour": "Installed profile paths retain verified profile identity and measured co-residence, with automatic compact selection for installer paths and honest operator locks; registered GPU-off keys disable every automatic role when chat truly exhausts the device budget; foreign devices retain scoped unplanned policies; planner failures degrade visibly and unchanged results do not repeat INFO logs.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; preserve round-2 B1/M1/M2/S-a repairs under the round-3 actual-allocation decision. The M1 fixture now uses context 1,048,576 to produce a real encoder shortfall while preserving its registered-key CPU-off expectations.",
          "choice": "Installed profile paths retain verified profile identity and measured co-residence, with automatic compact selection for installer paths and honest operator locks; registered GPU-off keys disable every automatic role when chat truly exhausts the device budget; foreign devices retain scoped unplanned policies; planner failures degrade visibly and unchanged results do not repeat INFO logs.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#installedProfilePathKeepsItsMeasuredPlan",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#customChatDisablesEveryAutomaticGpuRoleThroughPrepare",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#aDeviceOverrideLeavesTheOtherRolesAndChatPlanned",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#aForeignRoleWithoutAWeightObservationKeepsItsUnplannedPolicy",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#aChatOnlyPlannerFailureDoesNotAbortConfigurationPreparation",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#unchangedPlanningDoesNotRepeatInfoLogs",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#anOperatorLockedProfileIsBudgetedAsTheActualLaunch",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#anInstallerStandardPathFollowsTheCompactDevicePlan"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-r2-app-services-refresh.json"
    },
    {
      "behaviour": "Replacement admission credits the realized GPU artifact weights (including an actual FP32 fallback), not zero or unused cap ceilings; unknown free memory beside resident GPU sources refuses with free_device_memory_unknown. Index and query owners contribute only their retained GPU weights.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Replacement admission credits the realized GPU artifact weights (including an actual FP32 fallback), not zero or unused cap ceilings; unknown free memory beside resident GPU sources refuses with free_device_memory_unknown. Index and query owners contribute only their retained GPU weights.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java#sourceEstimateUsesRealizedGpuHandleAndPolicy",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java#unknownFreeMemoryNeverAdmitsBesideAResidentGpuSource",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java#indexSourceCountsOwnedGpuAssembliesAndRetainedPolicies",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java#sourceCreditsTheArtifactActuallyLoadedByTheGpuHandle"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-r2-indexer-refresh.json"
    },
    {
      "behaviour": "One item remains runnable through CPU when GPU weights or padding cannot fit. Planned non-FP16 CPU placement carries its reason and logs once per model.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandlePlannedFp32Test.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaExplicitSettingsTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "One item remains runnable through CPU when GPU weights or padding cannot fit. Planned non-FP16 CPU placement carries its reason and logs once per model.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandlePlannedFp32Test.java#aCpuSelectedHandleDoesNotApplyItsGpuPackingBudget",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandlePlannedFp32Test.java#nonFp16CpuPlacementHasAStatusReasonAndLogsOnce",
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaExplicitSettingsTest.java#anInfeasibleSingleItemRemainsAvailableForCpuDispatch"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-ort-common.json"
    },
    {
      "behaviour": "The real SPLADE batch entry produces CPU output for one 512-token text when its unused GPU cap is infeasible.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeCpuPackingTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "The real SPLADE batch entry produces CPU output for one 512-token text when its unused GPU cap is infeasible.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeCpuPackingTest.java#one512TokenTextReturnsCpuOutputUnderAnInfeasibleGpuCap"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-worker-core.json"
    },
    {
      "behaviour": "The real reranker returns CPU scores for one 512-token pair under an infeasible GPU cap, and retained GPU leases keep their packing snapshot when the device plan is withdrawn.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/RerankerCpuPackingTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "The real reranker returns CPU scores for one 512-token pair under an infeasible GPU cap, and retained GPU leases keep their packing snapshot when the device plan is withdrawn.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/RerankerCpuPackingTest.java#one512TokenPairReturnsCpuScoresUnderAnInfeasibleGpuCap",
        "modules/reranker/src/test/java/io/justsearch/reranker/RerankerCpuPackingTest.java#gpuLeaseKeepsItsPackingPlanWhenTheDevicePlanIsWithdrawn"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-reranker.json"
    },
    {
      "behaviour": "Environment-only and boolean-alias evaluation resolve consistently across system mode, debug endpoints and prompt cache; external-server refusal has its named wire reason.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/EvaluationModeAgreementTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatRefusalWireTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Environment-only and boolean-alias evaluation resolve consistently across system mode, debug endpoints and prompt cache; external-server refusal has its named wire reason.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/EvaluationModeAgreementTest.java#environmentEvaluationAgreesAcrossBootstrapDebugAndCache",
        "modules/ui/src/test/java/io/justsearch/ui/api/EvaluationModeAgreementTest.java#booleanAliasAgreesAcrossBootstrapDebugAndCache",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatRefusalWireTest.java#externalServerRefusalKeepsItsNamedWireReason"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-ui.json"
    },
    {
      "behaviour": "Preserve explicit encoder pressure protection with the master GPU setting off, exercising the registered keys through preparation.",
      "repaired": false,
      "class": "New",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Preserve explicit encoder pressure protection with the master GPU setting off, exercising the registered keys through preparation.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java#explicitGpuEncodersKeepPressureProtectionWithMasterOff"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-app-services.json"
    },
    {
      "behaviour": "Preserve CPU-source P12 replacement admission and prevent unused allocator ceilings from becoming source release credit.",
      "repaired": false,
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Preserve CPU-source P12 replacement admission and prevent unused allocator ceilings from becoming source release credit.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java#plannedP12ReplacementFromCpuSourceFitsFiveGiBFree",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java#retirementDoesNotCreditAnUnusedArenaCeiling"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-r2-indexer-refresh.json"
    },
    {
      "behaviour": "Preserve CPU placement when the live arbiter changes after an unbounded CPU packing shape is chosen.",
      "repaired": false,
      "class": "New",
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandlePlannedFp32Test.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Preserve CPU placement when the live arbiter changes after an unbounded CPU packing shape is chosen.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandlePlannedFp32Test.java#aChangingArbiterCannotPromoteAnUnboundedCpuShapeToGpu"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-ort-common.json"
    },
    {
      "behaviour": "Preserve every existing chat refusal wire code and require the additive external-server reason to conform to the public schema.",
      "repaired": false,
      "class": "New",
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatRefusalWireTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Preserve every existing chat refusal wire code and require the additive external-server reason to conform to the public schema.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatRefusalWireTest.java#everyRefusalReasonMapsToItsWireCodeAndTheBodyConformsToTheSchema"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-ui.json"
    },
    {
      "behaviour": "Evaluation mode resolves registered environment and boolean aliases and forces zero prompt cache; explicit JVM false retains precedence. Child cleanup verifies the recorded creation time.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/PromptCacheEnvironmentVariableTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Owner items 5 (GPU co-residence and a VRAM-budget-aware policy) and 6 (process ownership and exit accounting); assigned llama findings.",
          "choice": "Evaluation mode resolves registered environment and boolean aliases and forces zero prompt cache; explicit JVM false retains precedence. Child cleanup verifies the recorded creation time.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/PromptCacheEnvironmentVariableTest.java#evaluationEnvironmentDisablesDefaultCache",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/PromptCacheEnvironmentVariableTest.java#evaluationEnvironmentDisablesOverriddenCache",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/PromptCacheEnvironmentVariableTest.java#evaluationBooleanAliasDisablesCache"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-app-inference.json"
    },
    {
      "behaviour": "A held VDU configuration refuses both enabled and disabled ordinary settings preparation without replacing its server; an adopted external server refuses self-test before candidate launch.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Owner items 5 (GPU co-residence and a VRAM-budget-aware policy) and 6 (process ownership and exit accounting); assigned llama findings.",
          "choice": "A held VDU configuration refuses both enabled and disabled ordinary settings preparation without replacing its server; an adopted external server refuses self-test before candidate launch.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#settingsPreparationCannotReplaceAHeldVduServer",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#adoptedExternalServerRefusesSelfTestBeforeAnyCandidateCanStart"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-app-inference.json"
    },
    {
      "behaviour": "The real settings owner refuses changes during a held picture-reading batch and leaves the recording child and held configuration intact for either chat-enabled candidate.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/GenerativeSettingsComponentOwnerTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Owner items 5 (GPU co-residence and a VRAM-budget-aware policy) and 6 (process ownership and exit accounting); assigned llama findings.",
          "choice": "The real settings owner refuses changes during a held picture-reading batch and leaves the recording child and held configuration intact for either chat-enabled candidate.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/GenerativeSettingsComponentOwnerTest.java#heldPictureBatchRefusesSettingsWithoutReplacingItsChild"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-app-services.json"
    },
    {
      "behaviour": "A native GPU candidate returning after device loss is neither published READY nor leased nor closed through the lost driver.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDeviceLostTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Owner items 5 (GPU co-residence and a VRAM-budget-aware policy) and 6 (process ownership and exit accounting); assigned llama findings.",
          "choice": "A native GPU candidate returning after device loss is neither published READY nor leased nor closed through the lost driver.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDeviceLostTest.java#lossDuringCreationCannotPublishOrCloseTheReturningGpuCandidate"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-ort-common.json"
    },
    {
      "behaviour": "Status preserves sampler-time ownership after a child exits and keeps empty process attribution unknown.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProjectionTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Owner items 5 (GPU co-residence and a VRAM-budget-aware policy) and 6 (process ownership and exit accounting); assigned llama findings.",
          "choice": "Status preserves sampler-time ownership after a child exits and keeps empty process attribution unknown.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProjectionTest.java#aDepartedChildKeepsTheOwnershipCapturedWithItsMemorySample",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProjectionTest.java#pdhWithoutProcessFiguresPreservesUnknownAttribution"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-ui.json"
    },
    {
      "behaviour": "An explicit false evaluation system property wins over a true environment value and preserves an explicit positive cache bound. Preservation rerun: explicitFalseEvaluationPropertyWins passes on 61ff9b955 (the declared whole-change before-state) and on candidate 628bb419f, with no production overlays or compatibility patches. Both child JVMs report CACHE_RAM=256 with environment evaluation true and system-property evaluation false.",
      "class": "New",
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/PromptCacheEnvironmentVariableTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Assigned evaluation-mode finding; preserve existing supported precedence.",
          "choice": "Preserve registered system-property-over-environment precedence.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/PromptCacheEnvironmentVariableTest.java#explicitFalseEvaluationPropertyWins"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-preservation.json"
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaPlanTest.java"
      ],
      "whatMoved": "Facts now captures the immutable configured KV representation. Add kvType to the record-signature assertion; the rule forbidding free-memory, pressure, lifecycle or start-order inputs and the deterministic-equality assertion remain intact.",
      "moved": [
        "signature"
      ],
      "class": "Adaptation"
    },
    {
      "behaviour": "Preserve the exact existing chat vocabulary and append the external-server refusal reason required by S-c.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatVocabularyTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Preserve the exact existing chat vocabulary and append the external-server refusal reason required by S-c.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatVocabularyTest.java#exactMembers"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-app-api.json"
    },
    {
      "behaviour": "Register the additive derived per-role device-planning key in the encoder apply scope and preserve packaged register identity.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Register the additive derived per-role device-planning key in the encoder apply scope and preserve packaged register identity.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java#packagedRegisterIsTheRepositoryRegister"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-configuration.json"
    },
    {
      "behaviour": "A planned non-FP16 encoder whose captured policy disables GPU prepares, validates and serves CPU query results; the target manifest EP alone must not reject its intentional placement.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedCpuCompositionTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "A planned non-FP16 encoder whose captured policy disables GPU prepares, validates and serves CPU query results; the target manifest EP alone must not reject its intentional placement.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedCpuCompositionTest.java#plannedFp32QueryPreparesAndValidatesItsIntentionalCpuSession"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-indexer-worker.json"
    },
    {
      "behaviour": "Preserve captured variant identity through CPU composition and strict CUDA warmup/publication whenever policy is unknown or has a positive GPU cap.",
      "repaired": false,
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedCpuCompositionTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Preserve captured variant identity through CPU composition and strict CUDA warmup/publication whenever policy is unknown or has a positive GPU cap.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedCpuCompositionTest.java#capturedNonFp16ModelRemainsComposableOnCpu",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedCpuCompositionTest.java#cudaWarmupRemainsStrictForUnknownOrPositivePolicy",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedCpuCompositionTest.java#cudaPublicationRemainsStrictForUnknownOrPositivePolicy"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-indexer-worker.json"
    },
    {
      "class": "New",
      "repaired": true,
      "behaviour": "The real prepared GPU plan admits a standard-to-compact chat switch on 12, 16 and 24 GiB and same-plan query re-preparation on 16 and 24 GiB while the old chat remains loaded; planned query estimates and source retirement credit count retained weights rather than demanding the shared activation allowance twice.",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedQueryReplacementAdmissionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java"
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedQueryReplacementAdmissionTest.java#chatModelSwitchIsAdmittedWithTheOldChatLoaded",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedQueryReplacementAdmissionTest.java#samePlanRepreparationIsAdmittedWithChatLoaded",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java#sourceEstimateUsesRealizedGpuHandleAndPolicy"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-indexer-worker.json",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; round-3 P7 preservation repairs and consistent retained-memory admission.",
          "choice": "Keep planned query candidate and retiring-source estimates on retained weights, preserve headroom and unplanned behavior, and admit the plan's own supported replacements while the old chat is resident.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "New",
      "repaired": false,
      "behaviour": "A genuinely insufficient free-memory line plus realized source weights still refuses a query replacement with candidate_exceeds_releasable_device_memory. An ONNX graph whose tensors are stored externally keeps the conservative arena estimate because its graph-file size cannot establish its retained GPU weights.",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedQueryReplacementAdmissionTest.java"
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedQueryReplacementAdmissionTest.java#aRealRetainedWeightShortfallStillRefusesReplacement",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedQueryReplacementAdmissionTest.java#externalTensorWeightsKeepTheConservativeArenaEstimate"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-indexer-worker.json",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner item 5; preserve refusal of a real device-memory shortfall and conservative admission for externally stored ONNX tensors during the P7 repair.",
          "choice": "Do not authorize replacement when candidate retained weights and headroom exceed actual free memory plus demonstrably retained source weights. Retain the existing arena estimate when external tensor storage prevents the graph-file size from proving retained weights.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PlannedQueryReplacementAdmissionTest.java"
      ],
      "whatMoved": "Restore the existing same-policy admission oracle with positive activation: the FP16 fixture holds 1 MiB weights under a 3 MiB cap instead of padding weights to the entire cap. Use the trusted prepared-plan marker and the real query estimator as the candidate observation; retained-weight source credit and expected IN_PLACE admission remain explicit. The larger-candidate refusal oracle is kept. Synthetic model fixtures additionally receive syntactically inspectable ONNX headers for the embedded-versus-external tensor distinction; their allocated byte sizes, retained-weight observations and admission outcomes are unchanged by this header instrumentation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Round-3 instruction to restore same-policy admission with activation above zero and record it as an Adaptation.",
          "choice": "Restore the nonzero-activation precondition and observe real estimator admission without weakening the original successful-replacement or real-shortfall outcomes.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "New",
      "repaired": true,
      "behaviour": "Stored unmeasured GPU chat paths reserve file plus projector, configured-context KV and 770 MiB rather than the whole device; CPU chat and missing saved files, including relative managed paths, reserve no chat GPU memory. Small custom, CPU and missing chat configurations keep all four measured encoders planned, with named chat diagnostics and no false encoder_memory shortage.",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/StoredChatBudgetTest.java"
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/StoredChatBudgetTest.java#aSmallCustomGpuModelKeepsTheEncoderPlan",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/StoredChatBudgetTest.java#cpuChatDoesNotSpendDeviceMemory",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/StoredChatBudgetTest.java#aMissingSavedChatPathKeepsEncodersPlannedAndNamesTheFile",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/StoredChatBudgetTest.java#aMissingRelativeManagedPathNamesTheUnavailableFile"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-app-services.json",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner item 5; round-3 P4 decisions for custom, CPU-run and missing saved chat paths through the real settings rebuild.",
          "choice": "Budget the actual possible chat allocation, reserve zero for CPU or unavailable chat, keep encoders in the remaining budget and expose the actual chat availability or measurement reason.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "Adaptation",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/StoredChatBudgetTest.java"
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The relative managed missing-file fixture explicitly sets DATA_DIR to its own models directory's parent around the real settings rebuild, then restores the previous JVM property in finally. This isolates the fixture from a DATA_DIR property left by an unrelated earlier test. The relative saved path, absent file, real preparation boundary, encoder placement and exact missing-file diagnostic assertions remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner item 5; round-3 stored relative-path availability check through the real settings rebuild, with independent fixture state and unchanged placement expectations.",
          "choice": "Give the relative saved-path fixture its intended base directory at the registered JVM source, restore the prior value after preparation and retain every existing outcome assertion.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "New",
      "repaired": true,
      "behaviour": "Synchronous configuration preparation reads at most bounded model identity metadata and does not stream the chat model for a SHA-256.",
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceConfigArtifactIdentityTest.java"
      ],
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceConfigArtifactIdentityTest.java#configurationPreparationDoesNotStreamTheChatModel"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-app-inference.json",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Round-3 boot-path decision: identify installed artifacts cheaply and move any necessary full hash off synchronous boot.",
          "choice": "Count reads through the model input seam and reject a boot-time full-file hash with a deterministic byte-bound assertion rather than a timing threshold.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "New",
      "repaired": true,
      "behaviour": "Operating status reached through the real stored-path rebuild reports a missing saved chat as unavailable with its filename and reports unmeasured chat placement without inventing an encoder memory shortage.",
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingChatModelDiagnosticTest.java"
      ],
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingChatModelDiagnosticTest.java#missingSavedChatIsUnavailableAndNamesItsMissingFile",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingChatModelDiagnosticTest.java#unmeasuredSavedChatExplainsItsPlacementWithoutAnEncoderShortage"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-ui.json",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; round-3 placement-reason and missing-file availability decisions.",
          "choice": "Project the planner's named chat diagnostic through operating status and keep encoder_memory exclusive to actual encoder memory shortage.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "New",
      "repaired": false,
      "behaviour": "A missing saved candidate model does not replace the observed READY state, reason code or timestamp of the existing serving chat.",
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingChatModelDiagnosticTest.java"
      ],
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingChatModelDiagnosticTest.java#missingCandidateDoesNotReplaceTheReadyServersObservedState"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-ui.json",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner item 6; preserve observed runtime ownership while adding desired-model diagnostics.",
          "choice": "Only report the missing model as unavailable when there is no READY serving chat; preserve an already observed serving child and its status evidence.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "New",
      "repaired": false,
      "behaviour": "A single padded item that exceeds its GPU packing budget acquires the CPU lease and CPU session, never the GPU lease; unmeasured-role packing remains unchanged.",
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuCallPackerTest.java"
      ],
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuCallPackerTest.java#anInfeasibleSingleItemStillRunsOnCpuAndUnmeasuredRolesAreNotPacked"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-ort-common-preservation.json",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Round-3 request to make the existing CPU-dispatch check assert dispatch rather than only a one-item count.",
          "choice": "Observe the selected CPU lease/session and verify GPU acquisition never occurs; classify the existing working behavior as preservation against the round-3 starting revision.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "New",
      "repaired": true,
      "behaviour": "The actual installer verifies and promotes its local partial file, then the synchronous stored-path settings rebuild reads no whole model content and preserves the verified STANDARD 6549 MiB plan.",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/StoredChatBudgetTest.java"
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/StoredChatBudgetTest.java#bootSettingsRebuildDoesNotStreamTheInstalledModel"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-app-services.json",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Round-3 boot-read decision and stored-path boundary requirement; retain the budgeted unmeasured fallback until cheap or persisted file identity confirms a measured model.",
          "choice": "Count model reads after real installer verification and promotion, and preserve the measured shipped-model identity through the boot settings rebuild without another full SHA-256.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "New",
      "repaired": false,
      "behaviour": "Preserve shipped profile identity, exact measured chat footprints and projector placement on 8, 12, 16 and 24 GiB; preserve operator-locked and installer compact selection, registered GPU-off keys under a true shortfall, and custom attribution for changed content at the shipped filename without demanding that its encoder plan be removed. A local external chat server retains at least its 6,549 MiB reservation despite unused local CPU or missing-model settings, without a false local missing-file diagnostic.",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java"
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#installedProfilePathKeepsItsMeasuredPlan",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#customChatDisablesEveryAutomaticGpuRoleThroughPrepare",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#anOperatorLockedProfileIsBudgetedAsTheActualLaunch",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#anInstallerStandardPathFollowsTheCompactDevicePlan",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#aChangedArtifactAtTheShippedPathIsStillCustom",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#externalChatReservationIgnoresUnusedLocalSettings"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r3-profile-preservation.json",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; round-3 requirements to preserve all default plans, retain M1 registered-key behavior, distinguish genuinely custom content from installed identity and preserve round-1 external-server ownership when local chat settings select CPU or a missing file.",
          "choice": "Keep the existing shipped-profile, operator precedence and real-shortfall outcomes; changed content keeps an empty measured-model identity and an extrapolated footprint while the separate stored-path checks establish its revised allocation policy. Budget a selected local external server independently of unused local launch settings, keeping its existing GPU reservation and excluding false local missing-file diagnostics.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "Obsolete",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java"
      ],
      "behaviour": "Remove the expectation that any custom chat model, however small, disables all GPU encoders. Keep the M1 registered-key CPU-off assertions with a genuinely oversized context, and cover small custom models with the new stored-path budget checks.",
      "conflictSearch": "decision records searched for a conflict: none; reviewed ADR-0004's co-residence amendment, ADR-0047 and ADR-0050. None requires blanket whole-device reservation or CPU placement for every custom chat file; the round-3 allocation decision specifically replaces that rule.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Round-3 P4 decision for custom chat allocations and preservation of M1's actual registered-key failure under a genuine shortfall.",
          "choice": "A custom GPU model spends its file, projector, configured-context KV and 770 MiB, so only a real remaining-budget shortfall disables encoders. Supersede the old blanket-custom CPU policy instead of describing its changed input as mere re-encoding.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "Obsolete",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java"
      ],
      "behaviour": "Remove only the assertion that changed content at a shipped filename must have an unplanned encoder set. Retain and strengthen its custom-identity observation: no measured chat model is attributed and the footprint is extrapolated.",
      "conflictSearch": "decision records searched for a conflict: none; reviewed ADR-0004's co-residence amendment, ADR-0047 and ADR-0050. None requires blanket whole-device reservation or CPU placement for every custom chat file; the round-3 allocation decision specifically replaces that rule.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Round-3 allocation decision replaces the whole-device reservation for unmeasured chat while preserving round-2 protection against lending a profile measurement to different content.",
          "choice": "A changed artifact remains custom but may coexist with planned encoders when the specification's unmeasured allocation fits; file identity and co-residence admission are distinct facts.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "Adaptation",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java"
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Fixture creation still writes the same profile artifacts and declares their same manifest sizes and digests. It additionally records the verified write's path/size/mtime identity receipt through the real helper, reflecting its optional presence so original before revisions retain their own verification path. This instruments the off-boot installer verification boundary; it does not overlay repaired production into before worktrees or alter measured-plan assertions. The added 24 GiB case is covered separately as preservation; obsolete custom-placement expectations are recorded separately.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Round-3 boot-path decision to persist verified path/size/modification-time identity and keep default shipped plans unchanged.",
          "choice": "Seed the same identity an installer records after verifying its output so profile tests exercise cheap persisted identity rather than relying on a boot-time full hash.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Merge adaptations (b6d536/ix-llama 8a100008a into b6d536/integration feb02dd98)

No test file needed a change. Each conflict keeps both lanes' accepted behaviour:

- ChatActuator: vdu-auto's idleForAutomaticVdu() and this lane's configurationPrepared() are
  independent default methods; both are kept.
- modules/app-services/build.gradle.kts: the integration's test dependency on app-engine (the
  shutdown regression) and this lane's ORT test runtime (policy placement checks) are both kept.
- OperatingStateAssembler class comment: this lane's missing-chat FAILED (MODEL_LOAD_FAILED) wording,
  under which reasonCode and since come from the component, is combined with vdu-auto's statement
  that reload permission and retry time come from the controller's demand-refusal state.

ChatHoldsTest merged without conflict: it now holds this lane's preparation-ownership and
external-server checks next to ix-lifecycle's readiness-barrier checks, so both lanes' evidence for
it names a file that changed after their evidence run. Evidence and acceptance refresh belong to
the records pass.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-llama-20261014",
  "verdict": "accept",
  "digest": "sha256:4068c75e6f2286d096d717ffa2697e7c54a0c1fd8286be9a682ef1eea6090acb",
  "note": "VC-llama (independent Claude verifier) over three rounds, accepting b9abcaf97 vs 61ff9b955 (rejected 7de3976a1-era review findings and 1f8ce3e01 for regressions): a chat model switch 9B to compact and a same-plan encoder re-preparation are admitted with chat loaded on 12/16/24 GB, real shortfalls still refused; a custom 1 GiB model reserves file+KV+770 MiB and keeps encoders on GPU, CPU chat and a missing saved file reserve nothing, the missing file is named; default-model plans identical to base on 8/12/16/24 GB; boot preparation with a stored installer path 161 ms (1f8ce3e01 7735 ms), identity receipt written off the boot path and used on the next boot; start-order and system-access allowlist tests pass; CPU dispatch asserted. All round-3 tests fail on 1f8ce3e01 by assertion. Suites, ArchUnit and PMD show only baseline or load-only failures. Live: default plan P12 measured; boot with a stored path, receipt, next boot measured. Unverified live: encoder re-plan during a chat switch (the runner drains chat first). Named limits: chat_model_unmeasured is listed as an unmet user setting even when nothing is reserved; custom-model KV uses the 4B rate and can be 1.4-2.7 GB low for dense 8B models at 32k; identity-pending state applies at the next rebuild or boot and is only partly visible. Digest bound with --base 61ff9b955, the lane's base."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4b-20261015",
  "verdict": "reject",
  "digest": "sha256:38a7f2fe85bc61895c9882e1cbedb2bfdffba95fc37a08b0decb6b681944c75e",
  "note": "Reject entry 17: b90-ix-llama-preservation.json has failBefore null, and neither its entry nor the preamble explains why explicitFalseEvaluationPropertyWins cannot be executed before the change; calling it preservation supplies no candidate-only reason. All 83 candidate case references pass and the changed round-2 before replay matches, but this declaration omission remains."
}
```
