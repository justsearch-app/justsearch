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
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServicePolicyPreservationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The two no-policy three-profile preservation fixtures explicitly select the Windows native target. Linux now intentionally selects CPU files without its CUDA pack. Every assertion and expected value stays unchanged; static mocking closes per test.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, its in-app updater and AI installation on Linux",
          "choice": "Keep Windows CPU/GPU plans unchanged; make platform-specific preservation fixtures explicit instead of inheriting the Linux host target.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
