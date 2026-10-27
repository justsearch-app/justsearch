---
schema: test-intent.v1
task: linux-search-only-install
author-role: builder
author-session: codex-builder-linux-ai-continuation
---

# Test-intent entries

Repair checks and Windows preservation checks have separate execution evidence: only
repair checks must fail before. The lease fixture keeps every original assertion.
The new files are isolated against assignment baseline 4bf3524394; entries also cover
the combined branch, whose earlier Linux desktop changes disable that exemption.
The activation-boundary assertions retain the pre-existing acquisition contract:
[Install AI generation activation](../../../docs/design/lane-f-engine-jvm/evidence/D1/generation-native-cursor-design-2026-09-23.md#7-installer-produced-generation-candidate-2026-09-23-amendment)
says acquisition reports “Downloaded — activation required” and requires a distinct
approved activation. This assignment preserves that design.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxSearchOnlyInstallTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxPreflightCandidateTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Linux CPU acquisition downloads the selected ONNX model and supporting files over HTTP, records their retained candidate location, completes without llama-server, exposes the existing generation activation obligation, and reconstructs that candidate after a new service instance. Its acquisition lease is registered on the caller and released exactly once by the install owner. Linux GPU preview and preflight exclude Windows runtime artifacts and their chat consumer; the native-target inclusion predicate agrees with acquisition. A later Linux-targeted CUDA runtime supplier can satisfy both planned and already-installed runtime prerequisites. Preflight sees retained search candidates and never combines a retained model with supporting files from an older flat directory.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, its in-app updater and AI installation on Linux",
          "choice": "Install AI on a machine whose plan includes no chat (CPU on Linux; also a Linux GPU machine for now, since no Linux GPU runtime package exists yet) installs the search models of that plan, completes, and readiness reports search ready and chat unavailable with a reason the UI already knows how to show. No chat executable is required for that.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, its in-app updater and AI installation on Linux",
          "choice": "the prerequisite must reflect the runtime the selected plan actually supplies, so a later Linux GPU plan that supplies a runtime package passes it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, its in-app updater and AI installation on Linux",
          "choice": "preview, install and readiness agree on what the plan includes (isIncludedByPlan native-target gate)",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, its in-app updater and AI installation on Linux",
          "choice": "Explain \"preflight misses the retained candidate directories\" and fix it if it is part of this outcome.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-search-only-install-repairs.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxSearchOnlyInstallTest.java#cpuInstallDownloadsSearchAndCompletesWithoutChatExecutable",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxSearchOnlyInstallTest.java#linuxGpuPreviewAndPreflightExcludeForeignRuntimeAndChat",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxSearchOnlyInstallTest.java#linuxNativeTargetPredicateMatchesPlanner",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxSearchOnlyInstallTest.java#laterLinuxCudaRuntimePackageCanSupplyTheRuntime",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxPreflightCandidateTest.java#retainedSearchModelAndSupportingFilesAreCompleteWithoutChatRuntime",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxPreflightCandidateTest.java#supportingFilesInOldDirectoryCannotCompleteRetainedCandidate"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxSearchOnlyInstallTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: Windows CPU, GPU_LITE and GPU_FULL retain their package choices and bundled runtime requirement, including rejecting a missing bundled runtime despite a planned CUDA supplement; the install restore seam is still called for each Windows profile.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, its in-app updater and AI installation on Linux",
          "choice": "Windows unchanged: the existing Windows plans still require and restore their runtime as today.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-search-only-install-windows.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxSearchOnlyInstallTest.java#windowsInstallStillRestoresAndRefusesAMissingBundledRuntime",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxSearchOnlyInstallTest.java#windowsPlansRetainTheirPackagesAndRuntimePrecondition"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceUpgradeLeaseTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The fast-failure fixture now throws deterministically from the manifest seam before acquisition. Null collaborators no longer force Linux installation to fail: search-only acquisition intentionally has no chat-runtime prerequisite and otherwise starts the production 2.79 GB download. Every lease oracle is unchanged: register synchronously on the caller, release exactly once, release from the install owner rather than caller. No selection, timing bound, assertion or expected value is weakened.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, its in-app updater and AI installation on Linux",
          "choice": "Fix or justify the lease-test difference: a test that passed on the baseline and fails now is a regression until shown otherwise; do not weaken it.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
