---
schema: test-intent.v1
task: t-20261019-9d870e
author-role: builder
author-session: installpolicy-builder
---

# Install AI enterprise policy

New isolated test files are exempt at this base, so the skeleton command wrote no file. This explicit changeset is supplied as requested. The policy document is unchanged by this PR; its promises predate the change. Exact deferred documentation replacements are in ignored tmp/docs-pending.md. The real-API CPU-preview check is repaired behaviour; its other three integration cases are preserved and certified in the preservation evidence, alongside the null-service compatibility case. Windows-native execution and acceptance belong to separate sessions; the builder records neither as passed.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceCudaPolicyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppCudaPolicyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/AiInstallPolicyApiTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/AiInstallPolicyIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "S1\u2013S10: admission/background download checks fail closed; GPU-off and failed GPU reads produce CPU plans and policy-specific exclusions, suppress automatic CUDA choices and retain installed/resumable CUDA data. HTTP refusals retain 403 DOWNLOADS_DISABLED.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "docs/explanation/15-enterprise-policy.md:15",
          "quote": "**downloads disabled** (force offline/import-only)"
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/15-enterprise-policy.md:17",
          "quote": "**GPU acceleration disabled** (force CPU-only even if GPU packs are present)"
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/15-enterprise-policy.md:24",
          "quote": "fail closed on mismatches (hash/policy/pack allowlist)."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e user turn to session justsearch-8b, 2026-10-19",
          "delegationScope": "the remaining upkeep work, including the sweep follow-ups the orchestrator decides",
          "choice": "Treat configured snapshot exceptions/null as denial; mask GPU hardware for CPU planning, explain only policy-caused exclusions, guard automatic CUDA executable/native-path publication at install and boot, and retain CUDA jobs, partial bytes, files and saved preferences. Keep null-service and no-policy compatibility. Run portable Windows-target selectors under cross-platform lane tags.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upkeep-g-installpolicy-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java#startAndRepairRejectSnapshotExceptionBeforeWrites",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java#startAndRepairRejectNullSnapshotBeforeWrites",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java#backgroundSnapshotExceptionFailsBeforeAcquisition",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java#configuredSnapshotFailureMakesCapableHardwareCpuOnly",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java#gpuOffPreviewAndExecutionExcludeCudaAndRecordCpuContract",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java#gpuOffLitePreviewKeepsTheOriginalVramReasonForChat",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java#gpuOffExecutionRecordsCpuContractWithoutCudaAcquisition",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java#gpuOffRetainsCudaResumeJobsAndPartialBytesOutsideThePlan",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceCudaPolicyTest.java#gpuOffDoesNotCommitAnInstalledCudaExecutable",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceCudaPolicyTest.java#failedPolicyReadDoesNotCommitAnInstalledCudaExecutable",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceCudaPolicyTest.java#gpuOffDoesNotPublishCudaNativePath",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceCudaPolicyTest.java#failedPolicyReadDoesNotPublishCudaNativePath",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppCudaPolicyTest.java#gpuPolicyVetoStopsCudaSelectionDespitePositiveLayers",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppCudaPolicyTest.java#bootSnapshotExceptionStopsCudaSelectionWithoutAPriorVeto",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppCudaPolicyTest.java#bootNullSnapshotPublishesGpuVeto",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppCudaPolicyTest.java#bootSnapshotExceptionPublishesGpuVeto",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppCudaPolicyTest.java#gpuPolicyVetoStopsNativePathPublication",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppCudaPolicyTest.java#bootSnapshotExceptionStopsNativePathPublication",
        "modules/ui/src/test/java/io/justsearch/ui/api/AiInstallPolicyApiTest.java#snapshotExceptionRefusesStartAndRepairOnTheWire",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/AiInstallPolicyIntegrationTest.java#gpuOffCpuPreviewAndDownloadDenialUseTheRealApi"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyPreservationTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve no-policy CPU/GPU_FULL/GPU_LITE previews and normalized contracts, saved CUDA executable/layer preferences with CPU launch flags/environment, missing-policy permissiveness, corrupt detected machine-policy denial and allowlisted offline imports with downloads disabled. The preserved API cases check missing-policy defaults and allowlisted offline import; the Windows-reader fixture checks corrupt machine-policy denial.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "docs/explanation/15-enterprise-policy.md:15",
          "quote": "**downloads disabled** (force offline/import-only)"
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/15-enterprise-policy.md:17",
          "quote": "**GPU acceleration disabled** (force CPU-only even if GPU packs are present)"
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/15-enterprise-policy.md:24",
          "quote": "fail closed on mismatches (hash/policy/pack allowlist)."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e user turn to session justsearch-8b, 2026-10-19",
          "delegationScope": "the remaining upkeep work, including the sweep follow-ups the orchestrator decides",
          "choice": "Treat configured snapshot exceptions/null as denial; mask GPU hardware for CPU planning, explain only policy-caused exclusions, guard automatic CUDA executable/native-path publication at install and boot, and retain CUDA jobs, partial bytes, files and saved preferences. Keep null-service and no-policy compatibility. Run portable Windows-target selectors under cross-platform lane tags.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/15-enterprise-policy.md:15",
          "quote": "force offline/import-only"
        }
      ],
      "evidence": "gates/test-intent/evidence/upkeep-g-installpolicy-preserved.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyPreservationTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java#absentServiceAndPermissiveServiceKeepAllThreePlans",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/AiInstallPolicyIntegrationTest.java#missingPolicyKeepsPermissiveDefaults",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/AiInstallPolicyIntegrationTest.java#allowlistedOfflineImportStillWorksWithDownloadsDisabled",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/AiInstallPolicyIntegrationTest.java#corruptDetectedMachinePolicyKeepsFailClosedDefaults"
      ]
    }
  ]
}
```

## Acceptance records
