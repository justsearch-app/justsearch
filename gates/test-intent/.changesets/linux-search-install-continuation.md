---
schema: test-intent.v1
task: linux-search-only-install
author-role: builder
author-session: codex-builder-round3
---

# Test-intent entries

The delegated release repair supersedes the earlier completed-with-required-failures expectation;
per-file acquisition isolation and optional-only gaps remain covered.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallRequiredFailureTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceCompletionTruthTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePackageStateTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/model/NativeRuntimePlannerTest.java",
        "modules/ui-web/src/shell-v0/views/BrainSurface.chat-intent.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installRefresh.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Required download failures end as failed with an actionable error, preserving successful files and optional-only completion. Linux uses CPU model files until its CUDA dependencies are supplied. A plan without chat disables Start AI using the existing unavailable reason. Tests stub the actual hardware detection seam. The standing plan refreshes after installation and activation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, its in-app updater and AI installation on Linux",
          "choice": "Required download failures end as failed with an actionable error, preserving successful files and optional-only completion. Linux uses CPU model files until its CUDA dependencies are supplied. A plan without chat disables Start AI using the existing unavailable reason. Tests stub the actual hardware detection seam. The standing plan refreshes after installation and activation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-search-install-continuation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallRequiredFailureTest.java#allDownloadsFailedWithPlatformSkipsReportsFailureOnBothPlatforms",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallRequiredFailureTest.java#partialFailureKeepsAcquiredFilesAndNamesTheFailureOnBothPlatforms",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePackageStateTest.java#partialFailure_reportsFailed_andHonestCount",
        "modules/configuration/src/test/java/io/justsearch/configuration/model/NativeRuntimePlannerTest.java#shippedLinuxRegistryUsesCpuModelsAndOmitsForeignRuntime",
        "modules/ui-web/src/shell-v0/views/BrainSurface.chat-intent.test.ts#disables Start AI with the plan reason when chat is unavailable",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installRefresh.test.ts#refreshes the standing plan when installation or activation finishes",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceCompletionTruthTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePackageStateTest.java#partialFailure_reportsCompletedNotFailed_andHonestCount",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceCompletionTruthTest.java#perAssetIsolation_oneFailedPackageOfThree",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceCompletionTruthTest.java#cleanRunContradictedByDisk_readsRed"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, its in-app updater and AI installation on Linux",
          "choice": "Retire the expectation that an install whose required package files failed ends `completed`: it now ends failed with an actionable reason naming the failed packages, keeping downloaded files; optional-only gaps still complete.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: docs/tempdocs/824-f1-install-reliability-design.md records the earlier choice that a run whose required package failed ends `completed` with a counting message (section on INS-005 and completion truth); this delegated choice supersedes it. docs/tempdocs/375-sandbox-validation.md and 840-model-download-restructure.md describe that outcome historically. docs/explanation/12 section 6.1 is updated in this change. No other decision record, executable assertion or UI string asserts the old outcome."
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/model/NativeRuntimePlannerTest.java#shippedRegistryKeepsCudaModelsAndOmitsForeignRuntime",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyPreservationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, its in-app updater and AI installation on Linux",
          "choice": "Retire the expectation that a Linux machine with an NVIDIA GPU gets the CUDA (GPU_FULL) model files: the shipped Linux ONNX Runtime CUDA provider needs CUDA libraries the package neither ships nor declares, so Linux uses CPU model files until a Linux CUDA pack exists. The two policy fixtures keep their three-profile expectations on the Windows target, where they still hold.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: docs/decisions (0024 and 0049 mention CUDA; neither requires a Linux GPU machine to select CUDA model files); docs/tempdocs/761-linux-build-cost-estimate.md and 772-installer-payload-composition.md describe Linux CUDA runtime staging as open work. None conflicting."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-93q2qotz",
  "verdict": "accept",
  "digest": "sha256:1049e3071b7299b847f916123ce649e4defbe2c4082d73eeefb58896e002bc57",
  "note": "Classifications and repository-wide conflict search independently reviewed. Prior independent baseline/candidate behaviour verification retained as expressly permitted by the brief."
}
```
