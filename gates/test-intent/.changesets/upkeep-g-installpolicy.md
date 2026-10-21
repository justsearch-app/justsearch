---
schema: test-intent.v1
task: t-20261019-9d870e
author-role: builder
author-session: installpolicy-builder
---

# Install AI enterprise policy

New isolated test files are exempt at this base, so the skeleton command wrote no file. This explicit changeset is supplied as requested. The policy document is unchanged by this PR; its promises predate the change. Exact deferred documentation replacements are in ignored tmp/docs-pending.md. The real-API CPU-preview check is repaired behaviour; its other three integration cases are preserved and certified in the preservation evidence, alongside the null-service compatibility case. Windows-native execution and acceptance belong to separate sessions; the builder records neither as passed.

Follow-up 3 also pins fail-closed explicit activation and absent-service compatibility, and strengthens the Windows class-execution requirement. The preserved activation check also passed on the unchanged production base; the producer record is `gates/test-intent/evidence/upkeep-g-installpolicy-activation-preserved-base.json`. The earlier acceptance record is retained and stale for the final acceptor.

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
      "behaviour": "S1–S10: admission/background download checks fail closed; GPU-off and failed GPU reads produce CPU plans and policy-specific exclusions, suppress automatic CUDA choices and retain installed/resumable CUDA data. HTTP refusals retain 403 DOWNLOADS_DISABLED.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261019-9d870e request",
          "quote": "nothing on the machine or network is exposed by accident"
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/15-enterprise-policy.md:17",
          "quote": "**GPU acceleration disabled** (force CPU-only even if GPU packs are present)"
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
          "location": "task t-20261019-9d870e request",
          "quote": "nothing on the machine or network is exposed by accident"
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/15-enterprise-policy.md:17",
          "quote": "**GPU acceleration disabled** (force CPU-only even if GPU packs are present)"
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
          "location": "docs/explanation/15-enterprise-policy.md:32",
          "quote": "Offline pack import allowlisting by manifest digest and file SHA-256"
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
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java"
      ],
      "class": "New",
      "behaviour": "Explicit runtime activation fails closed with the GPU-policy denial message and code when a configured policy service throws or returns null.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "docs/explanation/15-enterprise-policy.md:17",
          "quote": "**GPU acceleration disabled** (force CPU-only even if GPU packs are present)"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-9d870e request",
          "quote": "nothing on the machine or network is exposed by accident"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e user turn to session justsearch-8b, 2026-10-19",
          "delegationScope": "the remaining upkeep work, including the sweep follow-ups the orchestrator decides",
          "choice": "A configured runtime activation policy service that throws or returns null blocks explicit activation with the existing GPU-policy denial message and POLICY_GPU_DISABLED result. An absent policy service remains permissive.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upkeep-g-installpolicy-activation-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java#activationBlockedWhenPolicySnapshotThrows",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java#activationBlockedWhenPolicySnapshotIsNull"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve permissive explicit runtime activation policy checks when no policy service is configured.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e user turn to session justsearch-8b, 2026-10-19",
          "delegationScope": "the remaining upkeep work, including the sweep follow-ups the orchestrator decides",
          "choice": "A configured runtime activation policy service that throws or returns null blocks explicit activation with the existing GPU-policy denial message and POLICY_GPU_DISABLED result. An absent policy service remains permissive.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upkeep-g-installpolicy-activation-preserved.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java#absentPolicyServiceKeepsActivationPermissive"
      ]
    },
    {
      "items": [
        "scripts/ci/unit-test-shard-policy.v1.json"
      ],
      "class": "Structural rule kept",
      "rule": "The Windows-native attribution check requires each listed class to execute and rejects missing classes or unlisted skips.",
      "whySurvives": "Retain every existing required class, allowed skip and Gradle task; strengthen the list with the two CUDA policy regression classes selected by the existing Windows coverage step.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e user turn to session justsearch-8b, 2026-10-19",
          "delegationScope": "the remaining upkeep work, including the sweep follow-ups the orchestrator decides",
          "choice": "Require Windows-native attribution to observe AiInstallServiceCudaPolicyTest and HeadlessAppCudaPolicyTest executing without unlisted skips in the existing lane that runs their modules.",
          "label": "agent choice"
        }
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
  "session": "5eb8db57-8693-4915-98dd-390ec7fb3c02",
  "verdict": "accept",
  "digest": "sha256:68de27018c5899f1b8bcc98dd538f7b4d57e68039f64f5580dbec5fb7e01fb41",
  "note": "Sources verbatim at base; all 20 new checks fail on base by assertion for the reason under test, pass on candidate; preserved checks pass on both; delegation quote not checked against task record (verifier may not read it)"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "ce1cf9b3-bd9a-45a8-9ce5-5d44a43d73a6",
  "verdict": "accept",
  "digest": "sha256:766b595997a91a60e55c1c1970ab81c40bd7ff8d4b0349bb4425dd42cf1aa88c",
  "note": "Delta 225fc3a43: owner-words verbatim at base; 2 repaired activation checks fail on d0500402b by assertion (no ISE thrown), pass on candidate; absent-service check passes on both; shard-policy change stricter only; delegation quote not checked against task record (verifier may not read it)"
}
```
