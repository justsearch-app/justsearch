---
schema: test-intent.v1
task: t-20261024-bc37c1
author-role: builder
author-session: 01a172ec-9b03-7340-a0e9-1df7ff297ae9
---

# Test-intent entries

The owner delegation below is quoted verbatim. The task scenarios, acceptance checks and design
are orchestrator text, not owner words or owner-adopted scenarios. The heap setting is an agent
choice within the delegated technical work; it changes no test expectation. The independent
acceptor reviews this choice and its execution evidence.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/build.gradle.kts"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261024-bc37c1 request",
          "delegationScope": "Technical diagnosis and repair of the UI unit-test heap failure, preserving existing test expectations and selection.",
          "choice": "Increase only UI test and ciUnitTest* executor heaps from 384m to 512m after a baseline OOM dump identifies the live ArchUnit import as the heap consumer.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Allow the unchanged settings-publication architecture rule to build its application class graph without exhausting the UI unit-test executor heap. This changes only maxHeapSize in the existing test/ciUnitTest* task matcher.",
        "callersAndStateOwners": "modules/ui/build.gradle.kts already shares unit configuration between test and ciUnitTest*; conventions/JvmBaseConventionsPlugin.kt supplies the 384m default for other Test tasks. The failing objects belong to ArchUnit ClassFileImportRecord and ClassGraphCreator stack frames while SettingsWriterArchitectureTest imports io.justsearch, rather than product state retained by previous tests. No production or test sources change.",
        "indirectBehaviours": "The UI unit executor may use 128 MiB more Java heap. Test selection, filters, tags, assertions, retries, timeouts, forks and integration heaps retain their existing settings. Extra memory can affect GC and timings, so real full-suite and shard execution and configured heap comparisons are required.",
        "evidence": "Baseline 58216932223d79d2660f2c4064a4618bbc44ef73: complete UI test at 384m fails inside ArchUnit ReverseDependencies (baseline-full-2); ciUnitTest2 reproduces Arrays.copyOf(Arrays.java:3478) with actual -Xmx384m (baseline-shards). OOM heap summaries identify a single active import, 6657 JavaClass, 206070 JavaMethodCall and 129949 JavaFieldAccess instances, no completed JavaClasses graph, and no instantiated product objects beyond three test helpers. The isolated settings architecture class passes all 4 checks before and after. Candidate full run at 512m completes 2174 tests in 501.025s without OOM, but is RED: 2171 passed, 2 failed, 1 skipped. Failures: NvidiaSmiAvailabilityHttpTest.anInstalledNvidiaSmiIsAvailableWhenNvmlSuppliesTheMemory (expected true, got false) and StatusLifecycleHandlerManifestFallbackTest.servedLifecycleAndManifestAgreeAfterSamplingForEveryInputCombination (30s timeout). The NVIDIA class passes 2/2 checks alone on both baseline and candidate. A comparison of 73 configured Test tasks changes only UI test/ciUnitTest1/ciUnitTest2 from 384m to 512m, with forks unchanged; the real candidate test executor uses -Xmx512m. Baseline ciUnitTest1 passes all 1242 checks in 276.478s. Reproducible commands, original XML, class/retention summaries and actual executor logs are under tmp/ui-heap-evidence; see README.md, heap-scope-comparison.json and per-run xml-summary.json. No tests were added or rewritten; these are existing execution and diagnostic observations, not new checks requiring D4 evidence. Driver-environment retry (new gradle-home-driver registry, unchanged copied machine settings): baseline lifecycle alone passes 1/1 in 19.810s; candidate NVIDIA+lifecycle pair passes 3/3, lifecycle 22.855s. Candidate full run executes the identical 2174 case IDs, 2172 pass, 1 fails, 1 skips, no OOM, test-run 515.404s and Gradle exit 1 after 8m37s. The NVIDIA full-context checks now both pass; the remaining lifecycle check again exceeds its original 30s timeout (31.005s class elapsed). See driver-baseline-lifecycle, driver-candidate-pair, driver-candidate-full-1 and driver-retry-comparison.json under tmp/ui-heap-evidence. Each driver command, numeric exit code and start/end UTC is captured through Python subprocess; earlier outputs remain intact.",
        "unresolvedRisks": "This remains a provisional candidate, not accepted work. In the driver-environment retry, baseline lifecycle alone and candidate NVIDIA+lifecycle pair pass with original timeouts, but complete candidate execution again times out in StatusLifecycleHandlerManifestFallbackTest.servedLifecycleAndManifestAgreeAfterSamplingForEveryInputCombination after 30s. Its relationship to full-suite memory/other state is unresolved; no product/test/timeout edits or further heap changes were authorized. The fresh daemon clears the previously observed NVIDIA full-context assertion failure. The runner stopped after the first red complete run, so two consecutive clean complete runs and candidate shards/wall-time preservation remain unverified. PR CI publication and independent acceptance belong to the orchestrator/verifier. Earlier attempt failures, including the post-Gradle evidence-shell quoting error, remain preserved and disclosed; new driver exit capture uses Python subprocess. Heap-library byte estimates are not actual JVM occupancy with compact headers."
      }
    }
  ]
}
```

## Acceptance records
