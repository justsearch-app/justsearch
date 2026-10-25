---
schema: test-intent.v1
task: t-20261024-bc37c1
author-role: builder
author-session: 01a172ec-9b03-7340-a0e9-1df7ff297ae9
---

# Test-intent entries

The owner delegation below is quoted verbatim. The task scenarios, acceptance checks and design
are orchestrator text, not owner words or owner-adopted scenarios. The heap setting and fixture execution grouping are technical choices within the delegation.
The grouping preserves matrix product expectations while explicitly changing its aggregate timeout allowance. The independent
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
        "callersAndStateOwners": "modules/ui/build.gradle.kts already shares unit configuration between test and ciUnitTest*; conventions/JvmBaseConventionsPlugin.kt supplies the 384m default for other Test tasks. The failing objects belong to ArchUnit ClassFileImportRecord and ClassGraphCreator stack frames while SettingsWriterArchitectureTest imports io.justsearch, rather than product state retained by previous tests. The heap change itself changes no production or test sources; the separately authorized lifecycle-fixture grouping is accounted for by the Adaptation entry below.",
        "indirectBehaviours": "The UI unit executor may use 128 MiB more Java heap. Test selection, filters, tags, assertions, retries, timeouts, forks and integration heaps retain their existing settings. Extra memory can affect GC and timings, so real full-suite and shard execution and configured heap comparisons are required.",
        "evidence": "Original baseline 58216932223d79d2660f2c4064a4618bbc44ef73: full UI test and ciUnitTest2 fail at 384m while ArchUnit builds ReverseDependencies for SettingsWriterArchitectureTest; shard2 reproduces Arrays.copyOf(Arrays.java:3478), with real -Xmx384m executor flags. The OOM hprof contains one active import/graph builder, 6657 JavaClass, 206070 JavaMethodCall and 129949 JavaFieldAccess instances, no completed JavaClasses graph, and no instantiated product objects beyond three test-rule helpers. Largest retainers are current importer stack roots. The isolated settings rule passes 4/4 before/after. Following the separately accounted C5 grouping, two consecutive fresh complete UI runs each pass 2178 of 2179 cases, with zero failures/errors and the same one pre-existing optional-corpus skip. Test-run times are 505.191s and 502.414s. Candidate shards pass: shard1 1242/1242 in 277.648s; shard2 936 passed plus the same one optional-corpus skip in 238.014s. Their case/status multiset union equals both complete runs. Baseline shard1 1242/1242 took 276.478s; candidate change 0.42%. All 73 configured Test tasks are compared: only UI test/ciUnitTest1/ciUnitTest2 change 384m to 512m, forks unchanged; integration and 69 other configurations remain unchanged. Both actual shard executor command lines carry -Xmx512m. See tmp/ui-heap-evidence/grouped-comparison.json, grouping-preservation.json and per-run original XML/logs/command/exit metadata. Earlier OOM and heap-only/full-timeout attempts remain preserved, with no blind before rerun.",
        "unresolvedRisks": "PR CI is not published/run by this builder and independent acceptance remains pending. The one optional real-corpus metric arm skips unchanged; its synthetic cases run. Linux local execution and real Gradle executors establish the heap scope and successful full/shard runs. Heap-parser byte estimates differ from actual compact-header JVM occupancy; object counts and stack ownership establish the diagnosis. Earlier environment and evidence-capture-shell errors remain disclosed in the evidence README; final grouped checks use robust Python subprocess exit capture."
      }
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerManifestFallbackTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "split"
      ],
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261024-bc37c1 request",
          "delegationScope": "Technical UI test-reliability work preserving the existing lifecycle matrix, its state trajectory and product assertions.",
          "choice": "Group the existing outer API-state axis into six sequential EnumSource invocations sharing one class-scoped fixture, preserving all 1296 combinations and 216 READY-index normalization checks.",
          "label": "agent choice"
        }
      ],
      "whatMoved": "Execution grouping and fixture ownership only. The before-state is committed bb0541a71ca4e13f6f6fea1e10486cf912377016 (512m, original single-method matrix); original heap baseline is 58216932223d79d2660f2c4064a4618bbc44ef73. The outer API-state loop becomes six EnumSource invocations in enum order with SAME_THREAD. PER_CLASS, one BeforeAll and one static class TempDir retain the same components, handler and publisher for all groups; no between-group reset is introduced. The three inner enum loops, per-combination READY resets, requested transitions, sampler calls, captured registry projection, manifest publication and every equality/normalization assertion are unchanged. Cumulative counters move to instance fields and still assert 1296 combinations and 216 normalized READY indexes globally in AfterAll. An entry checkpoint combinations == api.ordinal()*216 verifies original invocation order/continuity. Teardown try-with-resource aliases close publisher then components, in the original order, including assertion failures and partial setup. Generic default/per-invocation timeout values remain 30s and production sampler deadlines are unchanged. Effective aggregate allowance explicitly changes: the matrix now receives six separate 30s invocation budgets (up to 180s for invocation work), rather than one 30s aggregate invocation; setup and teardown are separate lifecycle hooks under existing defaults. No whole-matrix 30s performance contract is claimed. This grouping is orchestrator-settled C5, not owner words or an owner-adopted scenario; only the separately quoted autonomy words are owner text. Retained before full-suite timeout outputs establish the execution-scheduling failure; they are not blindly rerun. tmp/ui-heap-evidence/grouping-preservation.json records identical inner-body/constructor argument tokens and once-only fixture construction. Observed execution: narrow six invocations pass (class 35.238s, each invocation about 5.4-6.0s), including the AfterAll 1296/216 counters. Both complete runs pass all six groups and global counters, and the modified class passes in ciUnitTest2. Complete-run case-ID/status multisets match exactly; outside this named fixture results equal the earlier driver complete run. Exactly one former matrix case becomes six enum cases (+5 IDs), with unchanged skip IDs and shard union. The inner matrix body and handler-constructor arguments are identical to bb0541a71ca4e13f6f6fea1e10486cf912377016 apart from whitespace; once-only fixture construction and order/continuity checks are recorded in grouping-preservation.json. Named-file Spotless hygiene passes. All commands, XML counts/statuses, timings and exits are in grouped-* directories and grouped-comparison.json. This is durable fixture execution grouping; no new product behavior is introduced."
    }
  ]
}
```

## Acceptance records
