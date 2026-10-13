---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b90-ix-llama
---

# Test-intent entries

Round 2 repairs start at 7de3976a1. The whole-change reference is 61ff9b955; round-1 evidence remains archived. Delegation is the owner turn at 2026-10-11T16:05Z. These are delegated choices, not reviewer words presented as owner words. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "behaviour": "Installed profile paths retain manifest-verified profile identity and measured co-residence, with automatic compact selection for an installer path and honest budgeting for operator locks; registry GPU-off keys apply through preparation; foreign encoder devices keep scoped unplanned policies; planner errors degrade visibly with changed-result INFO logs; VDU ownership refuses preparation before drain and across entry/prepared transaction windows; external self-tests name the external owner.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Installed profile paths retain manifest-verified profile identity and measured co-residence, with automatic compact selection for an installer path and honest budgeting for operator locks; registry GPU-off keys apply through preparation; foreign encoder devices keep scoped unplanned policies; planner errors degrade visibly with changed-result INFO logs; VDU ownership refuses preparation before drain and across entry/prepared transaction windows; external self-tests name the external owner.",
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
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#anInstallerStandardPathFollowsTheCompactDevicePlan",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#anExternalServerRefusalNamesTheExternalServer",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#heldVduRefusesPreparationBeforeAnyDrain",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#pendingVduEntryRefusesPreparationBetweenItsTwoSteps",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#aPreparedConfigurationRetainsOwnershipUntilCommitOrAbort",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java#splitDevicePlacementNeverAppliesAnotherAdaptersReducedCaps"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-app-services.json"
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-indexer-worker.json"
    },
    {
      "behaviour": "One item remains runnable through CPU when GPU weights or padding cannot fit. Planned non-FP16 CPU placement carries its reason and logs once per model.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandlePlannedFp32Test.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuCallPackerTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaExplicitSettingsTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "One item remains runnable through CPU when GPU weights or padding cannot fit. Planned non-FP16 CPU placement carries its reason and logs once per model.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandlePlannedFp32Test.java#aCpuSelectedHandleDoesNotApplyItsGpuPackingBudget",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandlePlannedFp32Test.java#nonFp16CpuPlacementHasAStatusReasonAndLogsOnce",
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuCallPackerTest.java#anInfeasibleSingleItemStillRunsOnCpuAndUnmeasuredRolesAreNotPacked",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
      "behaviour": "Preserve conservative custom artifact identity and explicit encoder pressure protection while exercising the registered keys through preparation.",
      "repaired": false,
      "class": "New",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationScope": "Owner items 5 and 6; assigned round-2 B1/B2/M1-M4/S-a-S-d decisions and preservation of the round-1 repairs.",
          "choice": "Preserve conservative custom artifact identity and explicit encoder pressure protection while exercising the registered keys through preparation.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuPrepareRegressionTest.java#aChangedArtifactAtTheShippedPathIsStillCustom",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
      "evidence": "gates/test-intent/evidence/b90-ix-llama-r2-indexer-worker.json"
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
      "behaviour": "An explicit false evaluation system property wins over a true environment value and preserves an explicit positive cache bound.",
      "class": "New",
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/PromptCacheEnvironmentVariableTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
          "delegationLocation": "task t-20261005-b6d536 request",
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
    }
  ]
}
```
