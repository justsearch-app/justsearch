---
schema: test-intent.v1
task: t-20261022-f9aafb
author-role: builder
author-session: 01a16b11-7dde-7782-a173-32f8fb9e2ec9
---

# Test-intent entries

These entries cover the six modified existing tests relative to the merge-base with
origin/main (8b7517f869ec48da7ed2bdcc57de0bbb643be2c1), on candidate 9e62d16d4d.
They distinguish added lifecycle oracles from fixture adaptations. The one new measurement
method has two entries of the same class, one per opt-in execution mode, so both D4 files are
bound into the independent acceptance digest. No existing assertion is weakened or replaced; the old/new observations below explicitly identify the added oracles.

The only owner words used as expectation authority are “the memory issue needs a different
fix in general” (task t-20261022-f9aafb, user turn 2026-10-22). The later “takeover and proceed
with item 2,3 and 4” (2026-10-23) records continuation; this changeset does not infer a new
contract from the unspecified numbered items. B-S1..B-S8 and B-P1..B-P11 are proposals drafted
by the orchestrator and a challenger and told to the owner, without explicit owner adoption.
They are not owner words or owner-adopted sources here. The independent acceptor must judge
the expectation warrants against the limited owner request and reproduced resource defects,
not treat the implementation design as a source of intent.

The two lifecycle entries are New mechanism coverage, not Defect pins: their mocked wrappers
make an old missing close callback insufficient by itself to reproduce the external defect.
The D4 files retain that observed fail-before result without overstating what it proves.

D4 evidence is written by run-evidence.mjs in a private shared clone. The committed
[tokenizer-ownership-runner.patch](../evidence/tokenizer-ownership-runner.patch) is applied
only in that scratch clone: it prefixes Gradle with js-gradle-slot, uses an independent shared
clone for the before-state (and prepares its own dependencies), and optionally puts real-model
runs under the unchanged 6144 MiB guard with the explicit measurement init script. It changes
no test selection, assertion, parser, result classification or evidence serialization. The
producer scriptDigest records the patched runner. It is not a product/CI tooling change.
The test sources and production candidate are unmodified in that clone. The runner writes
the evidence JSON directly to the declared output paths in this checkout; no evidence file
is hand-authored or edited after the runner writes it.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java"
      ],
      "class": "New",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the memory issue needs a different fix in general",
          "location": "task t-20261022-f9aafb user turn 2026-10-22"
        },
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "Real defect, separate from these wrapper-mocked transition checks: boot KnowledgeServer with real models in one JVM; pause the real embedding factory after buildAssembly creates its native tokenizer but before composition returns, start close(), then resume composition. At 9c533e8e89 the attach is rejected after close begins, before tokenizer-closing wrappers are registered. Four post-close samples with the 30-second trim flag, taken 35 seconds after close, were 1180.8, 1774.8, 2440.3 and 3107.7 MiB RSS+swap. The added measurement method's during mode reproduces this schedule and records the floor; it intentionally makes no flat-floor assertion."
        }
      ],
      "expectationWarrant": "The owner requests a different general memory fix. Releasing native resources at the end of their model scope, while preserving live users, is the repair objective. The particular assembly-owned, wrapper-independent close observations are the author's checks of the selected repair mechanism, not owner words, an owner-adopted scenario, or a change to model outputs or saved data. The acceptor must judge their warrant within that limited request. No written delegation for an exact assembly layout is claimed.",
      "oracleChanges": [
        {
          "old": "No tokenizer close observation or count in the native stand-ins; existing cap, warm-up, publication and session-retirement assertions only.",
          "new": "tokenizerClose(handle) asserts closed.get(handle).get() before release, and assertEquals(1, releases.incrementAndGet()) on each release. assertNull(tokenizerReleases.put(handle, releases)) is a fixture-integrity precondition: a handle must not silently replace an earlier counted tokenizer."
        },
        {
          "old": "No check connecting a retired/live handle to its tokenizer lifetime after activation, precommit refusal/restoration, or fixture server.close().",
          "new": "assertTokenizerOwners() expects releases == 1 for a closed handle and 0 for a live handle at all three boundaries."
        },
        {
          "old": "CrossEncoderReranker mock construction reads argument 0 as SessionHandle; assembly factories return uncounted tokenizer mocks.",
          "new": "The constructor mock accepts SessionHandle or RerankerAssembly, and factory mocks attach counted close callbacks. This is fixture support inside the defect pin, not a claim that the whole file is Adaptation."
        }
      ],
      "evidence": "gates/test-intent/evidence/tokenizer-ownership-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java#compactToStandardActivationReplacesPhysicalBulkAndQueryCaps",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java#standardToCompactActivationReplacesPhysicalBulkAndQueryCaps",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java#precommitRefusalRestoresSourcePhysicalCapsAndWarmQuery"
      ],
      "repaired": false,
      "behaviour": "Add counted tokenizer lifetime observations to the existing two successful profile changes and precommit-refusal/exact-source-restoration check: a retired handle has one tokenizer release, a live handle has none, and session retirement precedes token release. This is new coverage of the chosen assembly-ownership mechanism, including independence from mocked wrappers; all existing physical cap, warm-up, publication and retirement assertions remain.",
      "failBeforeLimit": "CrossEncoderReranker is mock-constructed and its close() does not reproduce the old wrapper-owned tokenizer release. A missing tokenizer callback on the before-state can therefore be caused by that stand-in. The three assertion failures in the D4 failBefore record are an observed ownership-mechanism difference, not independent proof of a real successful-activation or normal-close leak. repaired is false because this entry adds a mechanism oracle rather than claiming these mocked-wrapper checks directly reproduce the externally reported defect. The separate leak reproduction in sources motivates the repair; it does not select the exact internal mechanism.",
      "observedExecution": "D4: all named methods executed and passed on 9e62d16d4d. The same candidate test files on 9c533e8e89 fail with AssertionFailedError, expected release count 1 but observed 0. As explained in failBeforeLimit, these wrapper-mocked differences are not presented as direct real-native defect reproductions."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecoveryCloseTest.java"
      ],
      "class": "New",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the memory issue needs a different fix in general",
          "location": "task t-20261022-f9aafb user turn 2026-10-22"
        },
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "Real defect, separate from these wrapper-mocked transition checks: boot KnowledgeServer with real models in one JVM; pause the real embedding factory after buildAssembly creates its native tokenizer but before composition returns, start close(), then resume composition. At 9c533e8e89 the attach is rejected after close begins, before tokenizer-closing wrappers are registered. Four post-close samples with the 30-second trim flag, taken 35 seconds after close, were 1180.8, 1774.8, 2440.3 and 3107.7 MiB RSS+swap. The added measurement method's during mode reproduces this schedule and records the floor; it intentionally makes no flat-floor assertion."
        }
      ],
      "expectationWarrant": "The owner requests a different general memory fix. Releasing native resources at the end of their model scope, while preserving live users, is the repair objective. The particular assembly-owned, wrapper-independent close observations are the author's checks of the selected repair mechanism, not owner words, an owner-adopted scenario, or a change to model outputs or saved data. The acceptor must judge their warrant within that limited request. No written delegation for an exact assembly layout is claimed.",
      "oracleChanges": [
        {
          "old": "One recoveredSurface containing mock(NerAssembly.class); no native token or retirement-status observation.",
          "new": "A fresh real NerAssembly per composed surface, with a counted mock tokenizer and a SessionHandle moving ACTIVE to RETIRED on close. The tokenizer close callback asserts RETIRED."
        },
        {
          "old": "No tokenizer release assertion on cancellation, before the next composition, after publication, or after shutdown.",
          "new": "Expect 1 release before the next composition and after cancellation; after successful recovery expect 2 tokens created and 1 released; server.close() must bring releases to 2; Mockito verifies one close per token."
        },
        {
          "old": "The producer stand-in does not retain/release the Runnable passed to replaceProducerModelLease.",
          "new": "The stand-in retains the current lease and releases it on replacement, park, or close, matching the existing producer ownership protocol. No availability/asserted configuration outcome is changed."
        }
      ],
      "evidence": "gates/test-intent/evidence/tokenizer-ownership-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecoveryCloseTest.java#physicalEncoderRecoveryPublishesLexicalThenExactCapturedOwner"
      ],
      "repaired": false,
      "behaviour": "Add counted tokenizer lifetime observations during cancelled recovery, before composing another candidate, after publishing the recovered owner, and at final server close. Use fresh real NerAssembly owners with counted native stand-ins and a producer stand-in that retains and releases its lease. This is new coverage of assembly-owned cleanup independent of the mocked NerService; existing lexical availability and captured-configuration assertions remain.",
      "failBeforeLimit": "NerService is mock-constructed and its close() does not reproduce the old wrapper-owned tokenizer release. The added counter's before-state assertion failure therefore does not alone establish that cancellation of a real wrapped NER owner leaked. It records the new assembly-ownership mechanism under this stand-in. repaired is false because this entry adds a mechanism oracle rather than claiming these mocked-wrapper checks directly reproduce the externally reported defect. The separate leak reproduction in sources motivates the repair; it does not select the exact internal mechanism.",
      "observedExecution": "D4: all named methods executed and passed on 9e62d16d4d. The same candidate test files on 9c533e8e89 fail with AssertionFailedError, expected release count 1 but observed 0. As explained in failBeforeLimit, these wrapper-mocked differences are not presented as direct real-native defect reproductions."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/BrakeExhaustedWorkerServesReadOnlyTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Add an opt-in real-model measurement probe, repeatedBootCloseNativeTokenizerFloor. In after mode require published index/query owners and all five real tokenizer roles; in during mode require a real embedding assembly before initiating close and observe closeStartedSignal before resuming composition. Each of four cycles must complete close, then records VmRSS and VmSwap from its own JVM after GC and 35 seconds. This test has no assertion that memory is flat and passes the old production state too. It instruments the requested memory investigation rather than pinning the repair. Existing brake tests, assertions, conditions and class timeout are unchanged; the new method alone has @Timeout(600) and EnabledIfSystemProperty(tokenizer.memory.mode= during|after).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the memory issue needs a different fix in general",
          "location": "task t-20261022-f9aafb user turn 2026-10-22"
        },
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "Boot and close real models repeatedly in a single JVM at 9c533e8e89 with the 30-second trim flag; sample the same JVM 35 seconds after each close. The earlier during-initialization measurement rose from 1180.8 to 1774.8 to 2440.3 to 3107.7 MiB RSS+swap. This motivates the probe, not an expected memory threshold. Current D4 runs repeat the measurement with safeguards that real resources were created and close completed."
        }
      ],
      "expectationWarrant": "The owner's memory-fix request supports measuring the reported memory issue. Readiness assertions make the measurement falsifiable (a model-free/no-op boot is not evidence); close completion ensures the samples are post-close. Four cycles and 35 seconds are measurement choices by the author, not owner words. Neither the approximate 150 MiB bound from the agent-drafted brief nor any B-S/B-P scenario is installed as a new oracle.",
      "oracleChanges": [
        {
          "old": "No repeatedBootCloseNativeTokenizerFloor method; no real-role, race-boundary, or close-completion checks for this measurement.",
          "new": "after: assertNotNull(indexOwner), assertNotNull(queryOwner), assertTrue(all five model-role optionals are present); during: assertTrue(real embedding-created latch awaits successfully), require closeStartedSignal and closing futures to complete; during also asserts the resume latch completes within 90 seconds so the deliberate pause cannot silently time out; both: assertTrue(actual.awaitClosed(0)) each cycle. No existing assertion is edited."
        }
      ],
      "evidence": "gates/test-intent/evidence/tokenizer-ownership-memory-after.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/BrakeExhaustedWorkerServesReadOnlyTest.java#repeatedBootCloseNativeTokenizerFloor"
      ],
      "executionMode": "after: D4 records this opt-in mode; the companion entry records during. Together they cover the added method and bind both D4 files into the acceptance digest.",
      "observedMeasurement": {
        "rssPlusSwapMiB": [
          1292.0,
          1325.6,
          1349.6,
          1326.7
        ],
        "maxAbsoluteDifferenceFromCycle2MiB": 33.6,
        "guardSamplePeakMiB": 5570,
        "meaning": "Observed samples for this run, not expected values or a new test threshold."
      },
      "measurementArtifacts": [
        {
          "path": "gates/test-intent/evidence/tokenizer-ownership-memory-after-floors.txt",
          "sha256": "83a5009daf43dadb638262bcb2bc35733e51b2dbac0cff3a983d82e95e3c1ca4"
        },
        {
          "path": "gates/test-intent/evidence/tokenizer-ownership-memory-after-guard.txt",
          "sha256": "a708930577f7f42cd7d2fe336f25baa29d25385cb18ace1168421db4a7e21eaa"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/BrakeExhaustedWorkerServesReadOnlyTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Add an opt-in real-model measurement probe, repeatedBootCloseNativeTokenizerFloor. In after mode require published index/query owners and all five real tokenizer roles; in during mode require a real embedding assembly before initiating close and observe closeStartedSignal before resuming composition. Each of four cycles must complete close, then records VmRSS and VmSwap from its own JVM after GC and 35 seconds. This test has no assertion that memory is flat and passes the old production state too. It instruments the requested memory investigation rather than pinning the repair. Existing brake tests, assertions, conditions and class timeout are unchanged; the new method alone has @Timeout(600) and EnabledIfSystemProperty(tokenizer.memory.mode= during|after).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the memory issue needs a different fix in general",
          "location": "task t-20261022-f9aafb user turn 2026-10-22"
        },
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "Boot and close real models repeatedly in a single JVM at 9c533e8e89 with the 30-second trim flag; sample the same JVM 35 seconds after each close. The earlier during-initialization measurement rose from 1180.8 to 1774.8 to 2440.3 to 3107.7 MiB RSS+swap. This motivates the probe, not an expected memory threshold. Current D4 runs repeat the measurement with safeguards that real resources were created and close completed."
        }
      ],
      "expectationWarrant": "The owner's memory-fix request supports measuring the reported memory issue. Readiness assertions make the measurement falsifiable (a model-free/no-op boot is not evidence); close completion ensures the samples are post-close. Four cycles and 35 seconds are measurement choices by the author, not owner words. Neither the approximate 150 MiB bound from the agent-drafted brief nor any B-S/B-P scenario is installed as a new oracle.",
      "oracleChanges": [
        {
          "old": "No repeatedBootCloseNativeTokenizerFloor method; no real-role, race-boundary, or close-completion checks for this measurement.",
          "new": "after: assertNotNull(indexOwner), assertNotNull(queryOwner), assertTrue(all five model-role optionals are present); during: assertTrue(real embedding-created latch awaits successfully), require closeStartedSignal and closing futures to complete; during also asserts the resume latch completes within 90 seconds so the deliberate pause cannot silently time out; both: assertTrue(actual.awaitClosed(0)) each cycle. No existing assertion is edited."
        }
      ],
      "evidence": "gates/test-intent/evidence/tokenizer-ownership-memory-during.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/BrakeExhaustedWorkerServesReadOnlyTest.java#repeatedBootCloseNativeTokenizerFloor"
      ],
      "executionMode": "during: D4 records this opt-in mode; the companion entry records after. Same added method, same source and oracle, independently executed under each condition.",
      "observedMeasurement": {
        "rssPlusSwapMiB": [
          554.3,
          617.0,
          653.5,
          724.8
        ],
        "maxAbsoluteDifferenceFromCycle2MiB": 107.8,
        "guardSamplePeakMiB": 4030,
        "meaning": "Observed samples for this run, not expected values or a new test threshold."
      },
      "measurementArtifacts": [
        {
          "path": "gates/test-intent/evidence/tokenizer-ownership-memory-during-floors.txt",
          "sha256": "06600f589c84c78662af107c8334d5d4a87dc2252a75c09892150d5f670054a7"
        },
        {
          "path": "gates/test-intent/evidence/tokenizer-ownership-memory-during-guard.txt",
          "sha256": "4e48d0d4793b4fc12fc58eda1a40b7c19077c84fe84be46149150e9259601b79"
        }
      ]
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/RerankerCpuPackingTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Both standalone reranker fixture handles now reports RetirementStatus.ACTIVE, and its pre-existing mocked close() action reports RETIRED afterwards. The old unstubbed retirementStatus() returned Mockito null and close() was a no-op. This supplies the existing SessionHandle close protocol to the assembly-owned cleanup path; it does not change acquire() fault injection, CPU/GPU selection, inputs, scores/output observations, expected values, tags or conditions. All original assertions and verifications are byte-for-byte unchanged. No assertion is added or removed.",
      "oracleChanges": {
        "old": "Existing packing/acquisition-failure assertions and verifications.",
        "new": "Identical assertions and verifications; only the retirement-status instrumentation on the fake handle changes."
      }
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/ner/NerLazyAcquisitionFailureTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The failingSessions() helper now reports RetirementStatus.ACTIVE, and its pre-existing mocked close() action reports RETIRED afterwards. The old unstubbed retirementStatus() returned Mockito null and close() was a no-op. This supplies the existing SessionHandle close protocol to the assembly-owned cleanup path; it does not change acquire() fault injection, CPU/GPU selection, inputs, scores/output observations, expected values, tags or conditions. All original assertions and verifications are byte-for-byte unchanged. No assertion is added or removed.",
      "oracleChanges": {
        "old": "Existing packing/acquisition-failure assertions and verifications.",
        "new": "Identical assertions and verifications; only the retirement-status instrumentation on the fake handle changes."
      }
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeCpuPackingTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The standalone SPLADE fixture handle now reports RetirementStatus.ACTIVE, and its pre-existing mocked close() action reports RETIRED afterwards. The old unstubbed retirementStatus() returned Mockito null and close() was a no-op. This supplies the existing SessionHandle close protocol to the assembly-owned cleanup path; it does not change acquire() fault injection, CPU/GPU selection, inputs, scores/output observations, expected values, tags or conditions. All original assertions and verifications are byte-for-byte unchanged. No assertion is added or removed.",
      "oracleChanges": {
        "old": "Existing packing/acquisition-failure assertions and verifications.",
        "new": "Identical assertions and verifications; only the retirement-status instrumentation on the fake handle changes."
      }
    }
  ]
}
```

## Reproducing execution evidence

Use a shared clone of 9e62d16d4d under this worktree's $TMPDIR, apply the runner patch there,
and run `node scripts/dev/prepare-worktree.cjs --no-dist`. Run the D4 runner with the four
method IDs in the lifecycle entries, `--before 9c533e8e89 --before-root "$TMPDIR"`, and
`--out gates/test-intent/evidence/tokenizer-ownership-repair.json`. The runner transplants only the two named candidate test files
into the before-state. It changes no before-state production code.

For each memory mode, link the approved real models as regular-file hardlinks into the scratch
clone's models/onnx and models/splade, use its own optimized-model and DJL caches, and run the
D4 runner for the measurement method with `TOKENIZER_EVIDENCE_MODE=after` or `during`.
The scratch-only init script is retained as `tokenizer-ownership-memory-init.txt` (copy it to
`tmp/memory.gradle` in the scratch clone); the exact guard is
`tokenizer-ownership-mem-guard.sh` (copy it to `tmp/mem-guard.sh`, preserving executable mode).
Remove only the recorded hardlinks afterwards. Each mode's raw
`tokenizer-ownership-memory-<mode>-floors.txt` and `-guard.txt` are retained alongside its D4 JSON;
the JSON proves execution, while those logs record the measurements. Runs are serial and use
their own server, executors, temporary indexes and profiles. Shared resources touched: Gradle/npm caches
and GPU 0 for real-model device initialization; no running application or dev stack is used.

## Acceptance records
