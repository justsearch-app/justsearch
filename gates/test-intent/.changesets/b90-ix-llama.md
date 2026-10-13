---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b90-ix-llama
---

# Test-intent entries

The delegation quoted below is the owner turn at 2026-10-11T16:05Z, supplied in this task request. Assigned review claims were independently reproduced on 61ff9b955. These are delegated design choices; no reviewer-authored wording is presented as owner words. Independent acceptance is pending.

```json
{
  "entries": [
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
      "behaviour": "Custom chat launch overrides reserve conservatively and decline measured co-residence; f16 KV reduces encoder allowance and is extrapolated; split device placement gets no caps from another device; explicitly GPU-enabled roles retain pressure holds when chat GPU is disabled.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "Owner items 5 (GPU co-residence and a VRAM-budget-aware policy) and 6 (process ownership and exit accounting); assigned llama findings.",
          "choice": "Custom chat launch overrides reserve conservatively and decline measured co-residence; f16 KV reduces encoder allowance and is extrapolated; split device placement gets no caps from another device; explicitly GPU-enabled roles retain pressure holds when chat GPU is disabled.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java#customOnlyGpuModelStillReservesChatMemory",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java#customOverrideDoesNotBorrowInstalledModelMeasurement",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java#higherPrecisionKvReducesEncoderBudget",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java#splitDevicePlacementNeverAppliesAnotherAdaptersReducedCaps",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuBudgetAlignmentTest.java#explicitGpuEncodersKeepPressureProtectionWithMasterOff"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-app-services.json"
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
      "behaviour": "Planned replacement counts retained artifact weights plus the activation peak allowed by device seats, overhead and headroom; a generation-bound FP32 artifact uses CPU under an FP16 measured plan; retirement never credits unused arena ceilings. Existing source-credit expectations change to zero because no retained-allocation measurement establishes a nonzero guaranteed release.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "Owner items 5 (GPU co-residence and a VRAM-budget-aware policy) and 6 (process ownership and exit accounting); assigned llama findings.",
          "choice": "Planned replacement counts retained artifact weights plus the activation peak allowed by device seats, overhead and headroom; a generation-bound FP32 artifact uses CPU under an FP16 measured plan; retirement never credits unused arena ceilings. Existing source-credit expectations change to zero because no retained-allocation measurement establishes a nonzero guaranteed release.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java#plannedP12ReplacementFromCpuSourceFitsFiveGiBFree",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java#generationBoundFp32CannotUseASiblingsMeasuredGpuPlan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java#retirementDoesNotCreditAnUnusedArenaCeiling"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-indexer-worker.json"
    },
    {
      "behaviour": "An explicit cap insufficient for the longest minimum call is marked encoder_memory unmet; infeasible calls are refused while feasible short inputs still pack.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaExplicitSettingsTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "Owner items 5 (GPU co-residence and a VRAM-budget-aware policy) and 6 (process ownership and exit accounting); assigned llama findings.",
          "choice": "An explicit cap insufficient for the longest minimum call is marked encoder_memory unmet; infeasible calls are refused while feasible short inputs still pack.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaExplicitSettingsTest.java#anExplicitCapTooSmallForTheLongestSingleItemIsReportedUnmet",
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaExplicitSettingsTest.java#anInfeasibleSingleItemIsRefusedBeforeNativeDispatch"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-ort-common.json"
    },
    {
      "behaviour": "A known-infeasible single item is refused before native allocation; unmeasured role behavior remains unchanged. This replaces the erroneous unconditional-one-item oracle.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuCallPackerTest.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "Owner items 5 (GPU co-residence and a VRAM-budget-aware policy) and 6 (process ownership and exit accounting); assigned llama findings.",
          "choice": "A known-infeasible single item is refused before native allocation; unmeasured role behavior remains unchanged. This replaces the erroneous unconditional-one-item oracle.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuCallPackerTest.java#anInfeasibleSingleItemIsRefusedAndUnmeasuredRolesAreNotPacked"
      ],
      "evidence": "gates/test-intent/evidence/b90-ix-llama-ort-common.json"
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
      "behaviour": "A planned cap below retained weights selects CPU before creating a GPU session.",
      "repaired": true,
      "class": "New",
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandlePlannedFp32Test.java"
      ],
      "sources": [
        {
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "Owner items 5 (GPU co-residence and a VRAM-budget-aware policy) and 6 (process ownership and exit accounting); assigned llama findings.",
          "choice": "A planned cap below retained weights selects CPU before creating a GPU session.",
          "label": "agent choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice"
        }
      ],
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandlePlannedFp32Test.java#aCapBelowRetainedWeightsSelectsCpuBeforeGpuCreation"
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
    }
  ]
}
```

## Acceptance records
