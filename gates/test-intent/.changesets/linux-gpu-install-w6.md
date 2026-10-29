---
schema: test-intent.v1
task: W6
author-role: builder
author-session: codex-w6-test-intent-20261029
---

# Test-intent entries

S12 is an agent choice under the quoted release-plan delegation, not owner-authored scenario text.
Each entry cites the settled S12 text verbatim. The independent verification is
[/tmp/w6v3/report.md](/tmp/w6v3/report.md), PASS V1-V7, on the pre-rebase candidate;
its per-entry citations identify the relevant observations and limits. It excluded this changeset.
All 30 W6 changed paths are byte-identical between the verified candidate 5fa8dec6d4 and
the rebased candidate b84dce56e8; unrelated changes in the new base are outside that comparison.

D4 evidence was generated on 1b24571df8a26c5101dfcd2783add5302182f4e9 against base 9149f53ba6 with
`scripts/governance/gates/test-intent/run-evidence.mjs` in a temporary shared clone.
The candidate run exited 0: all 11 check identifiers, comprising 35 cases, passed.
The repaired run names each new or repaired check, including both parameterized tokenizer
methods (display names now start with the method name, dcad0900c3) and the two checks whose
before-state threw IllegalStateException uncaught; those calls are now wrapped in
assertDoesNotThrow so the base fails by an assertion for the reason under test. Every
repaired identifier passes on the candidate and fails by assertion on base 9149f53ba6.

The runner used `gates/test-intent/evidence/linux-gpu-install-w6-runner.patch`, applied
only in the temporary clone: each Gradle call uses `js-gradle-slot -w 5400`, and before-state
creation/removal uses an isolated shared clone rather than a registered worktree.
Calls ran sequentially with two workers, CUDA devices hidden and no GPU pack input.
The repaired run overlays only the Linux registry test fixture on the base. Shared
resources touched were the Gradle slot queue, Gradle cache and host CPU/memory; no GPU,
dev stack or VM was used. The original product, test and runner code remain unchanged.

The delegation location uses the orchestrator-supplied task-record form below. The two
rejection records below are kept as history; the parameterized naming mismatch they and the
earlier prose describe is resolved (dcad0900c3), and the two formerly uncaught checks now have
their own repaired entry citing the repaired evidence.

The isolated new LinuxGpuPlanTest, WindowsPlanGoldenTest and LinuxOrtAfterCpuTest need no entries
of their own. Checks in the first two also establish the warrants for their gated fixture files.
The assembler's tests under scripts/ are out of scope for this gate; S12 and report V6 still cover them.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxGpuInstallTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeNativeResolutionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeLinuxDeactivationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppServerAutoSelectionTest.java"
      ],
      "class": "New",
      "behaviour": "Install AI retains the downloaded Linux executable for Repair, selects it through the settings owner, requires a supported NVIDIA driver (570 or newer), and round-trips the existing install contract and activation settings without migration. Explicit and default activation resolve the downloaded cuda12 executable; an unconfigured flat CPU executable is not discovered. The status still exposes an explicit operator executable as the default variant, and deactivation retains its executable and GPU settings. Engine restart auto-selects the downloaded executable while respecting an explicit operator executable. The whole installer class also executes the two repair checks accounted for separately below.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "implement the release plan W0-W8",
          "choice": "Linux with a supported NVIDIA GPU and a registry that lists Linux packs: Install AI fetches the packs and chat answers with sources on the GPU; preview, install and readiness agree on what the plan includes. Activation, Engine restart and Repair use the downloaded executable; an explicit operator executable override keeps precedence. Every NVIDIA CUDA/cuDNN library the Engine and llama-server load resolves inside the installed pack. The shipped registry has no Linux pack entries yet, so Linux behaves as before: CPU search, chat unavailable. Windows plans are unchanged. Saved state from the base reads the same after upgrade. The pack assembler verifies closure, RUNPATH, glibc floor and SONAME file names. The tokenizer uses its bundled CPU native, never a runtime download."
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-gpu-install-w6-candidate.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxGpuInstallTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeNativeResolutionTest.java#linuxDownloadedPackIsUsedForExplicitAndDefaultActivation",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeNativeResolutionTest.java#linuxNeverDiscoversAnUnconfiguredFlatCpuFallback",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeLinuxDeactivationTest.java#noFallbackDisablesChatAndRetainsRuntimeAndGpuSettings",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppServerAutoSelectionTest.java#linuxRestartFindsDownloadedRuntimeAndRespectsOperatorSelection"
      ],
      "verification": "/tmp/w6v3/report.md V1 (Install AI, sourced GPU chat, activation, Engine restart, Repair and operator override), V3 (no Linux CPU chat fallback), V5 (saved-state compatibility). These committed tests use temporary files, mocked GPU capabilities and the managed chat seam; the report supplies the independent real-system evidence. Windows runtime and release-pack ABI compatibility are not certified by that report."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxGpuInstallTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeNativeResolutionTest.java"
      ],
      "class": "New",
      "behaviour": "Repair after the downloaded Linux executable was deleted acquires the planned packs before requiring that executable, and activation resolves the downloaded pack executable for the explicit, empty and default variants. On the base both operations threw IllegalStateException; the checks assert they do not throw and then assert the intended result.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "implement the release plan W0-W8",
          "choice": "Linux with a supported NVIDIA GPU and a registry that lists Linux packs: Install AI fetches the packs and chat answers with sources on the GPU; preview, install and readiness agree on what the plan includes. Activation, Engine restart and Repair use the downloaded executable; an explicit operator executable override keeps precedence. Every NVIDIA CUDA/cuDNN library the Engine and llama-server load resolves inside the installed pack. The shipped registry has no Linux pack entries yet, so Linux behaves as before: CPU search, chat unavailable. Windows plans are unchanged. Saved state from the base reads the same after upgrade. The pack assembler verifies closure, RUNPATH, glibc floor and SONAME file names. The tokenizer uses its bundled CPU native, never a runtime download."
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-gpu-install-w6-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxGpuInstallTest.java#missingDownloadedExecutableDoesNotBlockPlannedPackRepair",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeNativeResolutionTest.java#linuxDownloadedPackIsUsedForExplicitAndDefaultActivation"
      ],
      "verification": "/tmp/w6v3/report.md V1 (Repair after deleting the downloaded executable; activation with the downloaded executable). The generated repaired D4 evidence records both checks passing on the candidate and failing by assertion on base 9149f53ba6.",
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxGpuInstallTest.java"
      ],
      "class": "New",
      "behaviour": "A core acquisition stage with a downloaded executable but no chat model must not commit an incomplete chat configuration or change the settings witness. The real settings owner still refuses that incomplete candidate; after the model arrives the chat stage commits the executable and activates the model. Installing Linux CUDA dependencies must not mutate the restart-required justsearch.onnxruntime.native_path process source.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "implement the release plan W0-W8",
          "choice": "Linux with a supported NVIDIA GPU and a registry that lists Linux packs: Install AI fetches the packs and chat answers with sources on the GPU; preview, install and readiness agree on what the plan includes. Activation, Engine restart and Repair use the downloaded executable; an explicit operator executable override keeps precedence. Every NVIDIA CUDA/cuDNN library the Engine and llama-server load resolves inside the installed pack. The shipped registry has no Linux pack entries yet, so Linux behaves as before: CPU search, chat unavailable. Windows plans are unchanged. Saved state from the base reads the same after upgrade. The pack assembler verifies closure, RUNPATH, glibc floor and SONAME file names. The tokenizer uses its bundled CPU native, never a runtime download."
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-gpu-install-w6-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxGpuInstallTest.java#coreStageWaitsForChatModelBeforeCommittingDownloadedExecutable",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxGpuInstallTest.java#linuxRuntimeAcquisitionDoesNotMutateRestartRequiredNativePath"
      ],
      "verification": "/tmp/w6v3/report.md V1 (completed install and Repair) and V2 (running Engine promotion without restart). The generated repaired D4 evidence records both settings-owner and install-stage checks passing on the rebased candidate and failing with assertions on base 9149f53ba6.",
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxSearchOnlyInstallTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/model/NativeRuntimePlannerTest.java",
        "modules/app-services/src/test/resources/ai/linux-gpu-registry.fixture.json",
        "modules/configuration/src/test/resources/ai/linux-gpu-registry.fixture.json"
      ],
      "class": "New",
      "behaviour": "The test-only published Linux pack fixtures give cuda-runtime-linux the Linux native target and replacement relationship for cuda-runtime. Their registry resolves the chat/search dependencies to the Linux runtime: planned downloads and already-installed Linux packs satisfy the runtime prerequisite. Across CPU, constrained/full GPU inputs and install intents, preview inclusion agrees with the plan, and a published supported Linux runtime permits GPU chat. Unpublished fixture packs and the shipped registry keep CPU search and omit chat and foreign runtime packs. The shipped registry must contain no cuda-runtime-linux entry. The fixture URLs and placeholder digests are test data, not release coordinates.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "implement the release plan W0-W8",
          "choice": "Linux with a supported NVIDIA GPU and a registry that lists Linux packs: Install AI fetches the packs and chat answers with sources on the GPU; preview, install and readiness agree on what the plan includes. Activation, Engine restart and Repair use the downloaded executable; an explicit operator executable override keeps precedence. Every NVIDIA CUDA/cuDNN library the Engine and llama-server load resolves inside the installed pack. The shipped registry has no Linux pack entries yet, so Linux behaves as before: CPU search, chat unavailable. Windows plans are unchanged. Saved state from the base reads the same after upgrade. The pack assembler verifies closure, RUNPATH, glibc floor and SONAME file names. The tokenizer uses its bundled CPU native, never a runtime download."
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-gpu-install-w6-candidate.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/LinuxSearchOnlyInstallTest.java#laterLinuxCudaRuntimePackageCanSupplyTheRuntime",
        "modules/configuration/src/test/java/io/justsearch/configuration/model/NativeRuntimePlannerTest.java#shippedLinuxRegistryUsesCpuModelsAndOmitsForeignRuntime",
        "modules/configuration/src/test/java/io/justsearch/configuration/model/LinuxGpuPlanTest.java"
      ],
      "verification": "/tmp/w6v3/report.md V1 (published fixture packs through real Install AI) and V3 (shipped registry: CPU vector search, unavailable chat, no runtime pack required). The assembler verification in V6 does not turn these fixtures into published release packs."
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/model/ModelRegistryLoaderTest.java",
        "modules/configuration/src/test/resources/ai/windows-install-plans-9dd16cf565.json"
      ],
      "class": "New",
      "behaviour": "Preserve the existing Windows registry and install-plan contract. The loader tests explicitly select Windows and restore os.name after each case, retaining all existing registry assertions (including the eight packages, model variants, runtime archives and metadata). Linux filtering is checked separately by NativeRuntimePlannerTest and LinuxGpuPlanTest. The golden fixture pins Windows plans against baseline output; unchanged Windows behavior, rather than candidate-generated output, is the warrant for its expected bytes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "implement the release plan W0-W8",
          "choice": "Linux with a supported NVIDIA GPU and a registry that lists Linux packs: Install AI fetches the packs and chat answers with sources on the GPU; preview, install and readiness agree on what the plan includes. Activation, Engine restart and Repair use the downloaded executable; an explicit operator executable override keeps precedence. Every NVIDIA CUDA/cuDNN library the Engine and llama-server load resolves inside the installed pack. The shipped registry has no Linux pack entries yet, so Linux behaves as before: CPU search, chat unavailable. Windows plans are unchanged. Saved state from the base reads the same after upgrade. The pack assembler verifies closure, RUNPATH, glibc floor and SONAME file names. The tokenizer uses its bundled CPU native, never a runtime download."
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-gpu-install-w6-candidate.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/model/ModelRegistryLoaderTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/model/WindowsPlanGoldenTest.java"
      ],
      "verification": "/tmp/w6v3/report.md V4: 480 before/candidate Windows-input plans identical, including inputs outside the committed golden. V5 also preserves Windows-input saved records. The verification used Windows inputs on Linux, not a Windows runtime."
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/OrtCudaHelperTest.java"
      ],
      "class": "New",
      "behaviour": "Linux CUDA dependency preload order places libnvJitLink.so.12 before libcusparse.so.12 and libcusolver.so.11, so the pack dependency closure can load without resolving those NVIDIA libraries from a system installation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "implement the release plan W0-W8",
          "choice": "Linux with a supported NVIDIA GPU and a registry that lists Linux packs: Install AI fetches the packs and chat answers with sources on the GPU; preview, install and readiness agree on what the plan includes. Activation, Engine restart and Repair use the downloaded executable; an explicit operator executable override keeps precedence. Every NVIDIA CUDA/cuDNN library the Engine and llama-server load resolves inside the installed pack. The shipped registry has no Linux pack entries yet, so Linux behaves as before: CPU search, chat unavailable. Windows plans are unchanged. Saved state from the base reads the same after upgrade. The pack assembler verifies closure, RUNPATH, glibc floor and SONAME file names. The tokenizer uses its bundled CPU native, never a runtime download."
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-gpu-install-w6-candidate.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/OrtCudaHelperTest.java#linuxJitLinkIsPreloadedBeforeSparseAndSolver"
      ],
      "verification": "/tmp/w6v3/report.md V1/V2: Engine and llama-server loaded NVIDIA libraries from the installed pack, including CPU then CUDA in the same JVM. V6 independently checks pack closure, RUNPATH, glibc floor and regular SONAME entries; the committed assembler checks under scripts/ are outside this gate."
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/OrtCudaHelperTest.java"
      ],
      "class": "New",
      "behaviour": "After Linux boots without a GPU pack, ORT native lookup initially uses its fallback, then discovers a subsequently installed cuda12 pack without changing the boot configuration or restart-required process sources. An explicit operator ORT native path retains precedence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "implement the release plan W0-W8",
          "choice": "Linux with a supported NVIDIA GPU and a registry that lists Linux packs: Install AI fetches the packs and chat answers with sources on the GPU; preview, install and readiness agree on what the plan includes. Activation, Engine restart and Repair use the downloaded executable; an explicit operator executable override keeps precedence. Every NVIDIA CUDA/cuDNN library the Engine and llama-server load resolves inside the installed pack. The shipped registry has no Linux pack entries yet, so Linux behaves as before: CPU search, chat unavailable. Windows plans are unchanged. Saved state from the base reads the same after upgrade. The pack assembler verifies closure, RUNPATH, glibc floor and SONAME file names. The tokenizer uses its bundled CPU native, never a runtime download."
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-gpu-install-w6-repaired.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/OrtCudaHelperTest.java#linuxResolvesPackInstalledAfterBootWithoutChangingProcessSources"
      ],
      "verification": "/tmp/w6v3/report.md V2: CPU then CUDA in one JVM and running Engine promotion without restart; the before-state resolved libcudart outside the pack. The generated repaired D4 evidence records the late pack discovery check passing on the candidate and failing with an assertion on base 9149f53ba6.",
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppTokenizerPrivacyTest.java"
      ],
      "class": "New",
      "behaviour": "Headless startup sets CPU tokenizer flavor and keeps CUDA discovery in a child process while retaining the existing privacy/offline defaults and honoring explicit privacy/offline settings. A fresh JVM with an empty cache and HTTP/HTTPS denied loads and runs the bundled CPU tokenizer without loading CUDA libraries. Environment overrides selecting CUDA tokenizer flavor or an external tokenizer native are refused with the explicit startup reason. Every parameterized privacy case and override-refusal case must execute.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "implement the release plan W0-W8",
          "choice": "Linux with a supported NVIDIA GPU and a registry that lists Linux packs: Install AI fetches the packs and chat answers with sources on the GPU; preview, install and readiness agree on what the plan includes. Activation, Engine restart and Repair use the downloaded executable; an explicit operator executable override keeps precedence. Every NVIDIA CUDA/cuDNN library the Engine and llama-server load resolves inside the installed pack. The shipped registry has no Linux pack entries yet, so Linux behaves as before: CPU search, chat unavailable. Windows plans are unchanged. Saved state from the base reads the same after upgrade. The pack assembler verifies closure, RUNPATH, glibc floor and SONAME file names. The tokenizer uses its bundled CPU native, never a runtime download."
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-gpu-install-w6-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppTokenizerPrivacyTest.java#entryDefaultsDjlToPrivateOfflineLoading",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppTokenizerPrivacyTest.java#entryLoadsBundledCpuTokenizerWithoutNetwork",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppTokenizerPrivacyTest.java#entryRefusesOverridesThatBypassCpuFlavor"
      ],
      "verification": "/tmp/w6v3/report.md V7: committed privacy probes fail before and pass after; empty-cache, network-denied loading passed. V1 independently audits Engine NVIDIA mappings, and the additional system-property override and standalone benchmark checks pass. The generated repaired D4 evidence records all three tokenizer methods (six cases) passing on the candidate and failing by assertion on base 9149f53ba6.",
      "repaired": true
    },
    {
      "items": [
        "modules/ui/build.gradle.kts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Declare the existing opt-in JUSTSEARCH_LINUX_GPU_PACK_DIR value and directory as Gradle Test task inputs, so cached test results track the supplied pack bytes. The change adds only inputs.property and inputs.dir declarations inside the existing Test configuration. It changes no test selector, JVM argument, assertion, tag, skip condition or execution predicate; LinuxOrtAfterCpuTest still owns its existing environment-dependent precondition.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "implement the release plan W0-W8",
          "choice": "Linux with a supported NVIDIA GPU and a registry that lists Linux packs: Install AI fetches the packs and chat answers with sources on the GPU; preview, install and readiness agree on what the plan includes. Activation, Engine restart and Repair use the downloaded executable; an explicit operator executable override keeps precedence. Every NVIDIA CUDA/cuDNN library the Engine and llama-server load resolves inside the installed pack. The shipped registry has no Linux pack entries yet, so Linux behaves as before: CPU search, chat unavailable. Windows plans are unchanged. Saved state from the base reads the same after upgrade. The pack assembler verifies closure, RUNPATH, glibc floor and SONAME file names. The tokenizer uses its bundled CPU native, never a runtime download."
        }
      ],
      "verification": "/tmp/w6v3/report.md V2 exercises the supplied pack through the CPU-then-CUDA check. V7 exercises the unrelated privacy checks. The candidate D4 evidence passed; this documentation task did not execute the optional GPU check."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-w6-acceptor-20261029T141703Z",
  "verdict": "reject",
  "digest": "sha256:d5f5557bd20c46ccbedfd19af0791dbf48c09323ad5949681181dcdd85650b5d",
  "note": "Entry 1 lacks assertion fail-before for two new checks: LinuxGpuInstallTest#missingDownloadedExecutableDoesNotBlockPlannedPackRepair and RuntimeNativeResolutionTest#linuxDownloadedPackIsUsedForExplicitAndDefaultActivation throw uncaught IllegalStateException on 9149f53ba6. Both pass on 667157a671. Wrap the throwing operations in assertion-based checks and regenerate baseline/candidate evidence; identify repaired behavior explicitly. Correct the stale tokenizer evidence prose, which contradicts the now-valid six-identifier repaired evidence. See /tmp/w6-accept/report.md and case-results.json."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-w6-acceptor2-20261029T153743Z",
  "verdict": "reject",
  "digest": "sha256:b512bc1c8cd86c80c5503f90aae1b399e9505e8fbf1f70cec0a9b5bc6d20faab",
  "note": "Both wrapped checks now fail by AssertionFailedError on 9149f53ba6 for the intended missing-executable reasons and pass on 0908068a1c; wrappers preserve expected values and all evidence digests match. Two issues remain: (1) Neither missingDownloadedExecutableDoesNotBlockPlannedPackRepair nor linuxDownloadedPackIsUsedForExplicitAndDefaultActivation is named by any repaired:true entry. Entry 1 still cites candidate-only evidence. Add both fully qualified IDs to a repaired:true entry citing linux-gpu-install-w6-repaired.json, updating its items, behaviour and verification as needed (for example, extend entry 2). (2) Correct the contradictory prose at lines 17 and 34-37: both regenerated evidence files name revision 1b24571df8a26c5101dfcd2783add5302182f4e9, acceptance records are not empty, and the parameterized tokenizer mismatch is resolved. Remove or replace the stale completion instructions and generation provenance. Keep the previous rejection. Existing D4 evidence can be reused if test files remain unchanged. See /tmp/w6-accept2/report.md, case-results.json, evidence-audit.jsonl and wiring-audit.json."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-w6-acceptor3-20261029T154251Z",
  "verdict": "accept",
  "digest": "sha256:caba1526e5738582e70eb7c2be75d459d169fd7858afb3642cb7bdb151ea79fc"
}
```
