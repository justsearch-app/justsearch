---
schema: test-intent.v1
task: t-20261022-f9aafb
author-role: builder
author-session: 01a16b11-7dde-7782-a173-32f8fb9e2ec9
---

# Test-intent entries

The original entries cover the six modified existing tests relative to the merge-base with
origin/main (8b7517f869ec48da7ed2bdcc57de0bbb643be2c1), on candidate 9e62d16d4d.
They distinguish added lifecycle oracles from fixture adaptations. The one new measurement
method has two entries of the same class, one per opt-in execution mode, so both D4 files are
bound into the independent acceptance digest. No existing assertion is weakened or replaced; the old/new observations below explicitly identify the added oracles.

For the original entries, the owner words used as expectation authority are “the memory issue needs a different
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
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/StandaloneCloseCompatibilityTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Standalone CitationScorer and CrossEncoderReranker close remains quiet for null, ACTIVE, RETIRING and REFUSED dispositions returned after a normal handle close. The tokenizer is retained, then released exactly once after a subsequent confirmed retirement. A separate preservation check requires strict assembly.close to keep throwing on refusal and release only after retirement.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "A shutdown path or caller that used to close quietly must not start failing, and B-P5 still requires that a refused or unconfirmed retirement keeps tokenizer ownership for a retry.",
          "location": "task t-20261022-f9aafb user turn 2026-10-24"
        },
        {
          "kind": "defect-source",
          "stronger": {
            "kind": "owner-words",
            "quote": "A shutdown path or caller that used to close quietly must not start failing, and B-P5 still requires that a refused or unconfirmed retirement keeps tokenizer ownership for a retry.",
            "location": "task t-20261022-f9aafb user turn 2026-10-24"
          },
          "reproduction": "On 0fe343c55d, use a SessionHandle whose close returns normally and whose retirementStatus remains REFUSED (or null, as the 72 numeric-citation fixtures do). Close a standalone wrapper. Its private assembly throws IllegalStateException instead of returning quietly as on main. The new assertDoesNotThrow is the failing assertion; the tokenizer must remain unreleased until a later successful retirement. NerService additionally propagates a native close exception that main catches; EmbeddingService wraps a checked BackendException that main logs. Both need quiet retries with ownership retained."
        }
      ],
      "evidence": "gates/test-intent/evidence/tokenizer-ownership-close-compatibility.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/StandaloneCloseCompatibilityTest.java"
      ],
      "oracleChanges": [
        {
          "old": "No standalone quiet-close/refusal/retry regression checks in this new file.",
          "new": "Standalone CitationScorer and CrossEncoderReranker close remains quiet for null, ACTIVE, RETIRING and REFUSED dispositions returned after a normal handle close. The tokenizer is retained, then released exactly once after a subsequent confirmed retirement. A separate preservation check requires strict assembly.close to keep throwing on refusal and release only after retirement."
        }
      ],
      "expectationWarrant": "This follow-up has explicit owner words for both quiet observable close and retained retry ownership. These checks derive from that instruction, not from the assembly implementation or adoption of the earlier B-S/B-P scenario set."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/StandaloneCloseCompatibilityTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Standalone embedding, BGE-M3, SPLADE, BERT NER, NerService and EmbeddingService close remains quiet when a handle returns normally with null, ACTIVE, RETIRING or REFUSED status. Each retains its tokenizer owner for retry and releases it exactly once only after confirmed retirement. NerService also preserves main's non-fatal handling of a thrown native close exception and retries that owner.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "A shutdown path or caller that used to close quietly must not start failing, and B-P5 still requires that a refused or unconfirmed retirement keeps tokenizer ownership for a retry.",
          "location": "task t-20261022-f9aafb user turn 2026-10-24"
        },
        {
          "kind": "defect-source",
          "stronger": {
            "kind": "owner-words",
            "quote": "A shutdown path or caller that used to close quietly must not start failing, and B-P5 still requires that a refused or unconfirmed retirement keeps tokenizer ownership for a retry.",
            "location": "task t-20261022-f9aafb user turn 2026-10-24"
          },
          "reproduction": "On 0fe343c55d, use a SessionHandle whose close returns normally and whose retirementStatus remains REFUSED (or null, as the 72 numeric-citation fixtures do). Close a standalone wrapper. Its private assembly throws IllegalStateException instead of returning quietly as on main. The new assertDoesNotThrow is the failing assertion; the tokenizer must remain unreleased until a later successful retirement. NerService additionally propagates a native close exception that main catches; EmbeddingService wraps a checked BackendException that main logs. Both need quiet retries with ownership retained."
        }
      ],
      "evidence": "gates/test-intent/evidence/tokenizer-ownership-close-compatibility.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/StandaloneCloseCompatibilityTest.java"
      ],
      "oracleChanges": [
        {
          "old": "No standalone quiet-close/refusal/retry regression checks in this new file.",
          "new": "Standalone embedding, BGE-M3, SPLADE, BERT NER, NerService and EmbeddingService close remains quiet when a handle returns normally with null, ACTIVE, RETIRING or REFUSED status. Each retains its tokenizer owner for retry and releases it exactly once only after confirmed retirement. NerService also preserves main's non-fatal handling of a thrown native close exception and retries that owner."
        }
      ],
      "expectationWarrant": "This follow-up has explicit owner words for both quiet observable close and retained retry ownership. These checks derive from that instruction, not from the assembly implementation or adoption of the earlier B-S/B-P scenario set."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/WrapperCloseRetryTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-words",
          "quote": "A shutdown path or caller that used to close quietly must not start failing, and B-P5 still requires that a refused or unconfirmed retirement keeps tokenizer ownership for a retry.",
          "location": "task t-20261022-f9aafb user turn 2026-10-24"
        },
        "reproduction": "On 0fe343c55d, use a SessionHandle whose close returns normally and whose retirementStatus remains REFUSED (or null, as the 72 numeric-citation fixtures do). Close a standalone wrapper. Its private assembly throws IllegalStateException instead of returning quietly as on main. The new assertDoesNotThrow is the failing assertion; the tokenizer must remain unreleased until a later successful retirement. NerService additionally propagates a native close exception that main catches; EmbeddingService wraps a checked BackendException that main logs. Both need quiet retries with ownership retained."
      },
      "reproduction": "Transplant this updated test file onto 0fe343c55d. checkedBackendFailureRetainsBackendForRetry now asserts a quiet first close after a checked BackendException, and the NER case of failedCloseCanBeRetriedWithoutLeakingTheTokenizer now asserts a quiet first close after an injected native exception. Both fail by AssertionFailedError on the branch's newly throwing close behavior. Main catches these exceptions; this follow-up restores that observable behavior while preserving retry ownership.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "A shutdown path or caller that used to close quietly must not start failing, and B-P5 still requires that a refused or unconfirmed retirement keeps tokenizer ownership for a retry.",
          "location": "task t-20261022-f9aafb user turn 2026-10-24"
        }
      ],
      "evidence": "gates/test-intent/evidence/tokenizer-ownership-close-compatibility.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/WrapperCloseRetryTest.java"
      ],
      "oracleChanges": [
        {
          "old": "checkedBackendFailureRetainsBackendForRetry: assertThrows(IllegalStateException.class, service::close);",
          "new": "assertDoesNotThrow(service::close); The existing unavailable-after-close, successful retry, repeated-close and one tokenizer release assertions remain."
        },
        {
          "old": "NER case in failedCloseCanBeRetriedWithoutLeakingTheTokenizer allows either a normal return or IllegalStateException through try/catch.",
          "new": "assertDoesNotThrow(wrapper::close); The non-NER thrown-native-failure assertions and all retry/release assertions remain unchanged."
        }
      ],
      "expectationWarrant": "This is an oracle change, not Adaptation. The earlier branch test incorrectly required a new exception for a checked backend close failure; the owner now explicitly requires preserving main's quiet close. The changed NER assertion also strengthens the prior permissive observation to the explicitly requested quiet behavior. Other native session/tokenizer exceptions continue propagating from wrappers where they propagated on main."
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

## PR 940 follow-up: standalone close compatibility

The follow-up candidate starts at 0fe343c55d. The owner explicitly requires preserving quiet
close behavior and retaining ownership after unconfirmed retirement (quoted in the new entries).
This does not adopt the rest of the earlier agent-drafted B-S/B-P scenarios.

The repair changes production behavior rather than rewriting the four numeric-citation fixtures.
Their 72 teardown failures expose an exception added by assembly.close after a normally returning
handle.close; a real NativeSessionHandle can also return REFUSED after native cleanup failure.
Standalone wrappers now use the assembly's boolean retirement attempt, retain incomplete owners,
and add no exception for that disposition. Strict assembly.close remains the server surface's
retirement signal. Session/tokenizer exceptions still propagate where main propagated them.
NerService and checked embedding-backend failures keep main's non-fatal handling; retries keep
the owner/backend reachable. An embedding service checks completion before discarding its backend.

Production caller audit: KnowledgeServer bootstrap, full replacement/recovery and query-only
settings construct borrowing wrappers; NerService.borrowing constructs a borrowing BERT wrapper.
EncoderSet and QueryRoleSet close the strict surface before their wrappers, so server retirement
still fails and retains owners on refusal. RerankerDeadlineBench is the only production caller of
a raw standalone constructor. Other construction sites are tests/integration checks in reranker,
worker-core, worker-services, indexer-worker, app-engine and system-tests. Benchmarks, app-services
and ort-common are included as consumers in the expanded verification. No numeric scoring oracle,
input, tag, condition or expected score is changed.

The new D4 run uses [tokenizer-ownership-close-runner.patch](../evidence/tokenizer-ownership-close-runner.patch)
only in its scratch clone, with before ref 0fe343c55d and all three test files as whole-class
check IDs. It retains the slot/shared-clone isolation changes and batches the independent module
Test tasks into one Gradle invocation per tree, with task-local filters, an explicit --rerun for
each task and --continue so both modules execute despite expected before-state failures. It
changes no assertion, result parser, failure classification or evidence serialization. The JSON
records the actual commands and producer digest. There are no production overlays or before patches.
All pre-existing acceptance records below are preserved unchanged; the verifier must re-accept
the new digest. The builder writes no acceptance record.

### Observed follow-up checks (2026-10-24)

Both baseline lane runs use the untouched 0fe343c55d production code in an isolated shared clone.
The exact CI lane commands use CI=true and JUSTSEARCH_PR_CI=true, as the PR workflow does,
with the same task lists and flags from .github/workflows/ci.yml, prefixed by js-gradle-slot.

| Check | Observed result |
| --- | --- |
| Before search-worker-1 | Exit 1: worker-services reports 1,008 tests, 21 failed, 10 skipped; Nx3b 12 / Nx3d 9 failures. |
| Before search-worker-2 | Exit 1: worker-services reports 1,069 tests, 51 failed, 14 skipped; Window 42 / Nx3c 9 failures. Other lane tasks without results after that failure are unrun. |
| After search-worker-1 | Exit 0: indexer-worker 835 XML cases / 5 skipped, worker-services 994 / 10 skipped, adapters-lucene 799 / 3 skipped; zero failures. |
| After search-worker-2 | Exit 0: indexer-worker 669 XML cases / 28 skipped, worker-services 1,036 / 15 skipped, worker-core 512 / 19 skipped, configuration 348, core 127, indexing 87, core-contracts 38, ipc-common 4; zero failures. |
| After consumer suites | Exit 0: worker-services 2,030 XML cases / 25 skipped, app-services 4,259 / 11 skipped, app-engine 762 / 9 skipped, reranker 117 / 4 skipped, benchmarks 104, system-tests 96, ort-common 378 / 6 skipped; zero failures. Benchmarks classes compiled. |
| After static checks | Exit 0: pmdMain, pmdTest and spotlessCheck for worker-core and reranker. |
| D4 run-evidence | Exit 0: candidate command exits 0 with 40 passing cases and no skips; before command exits 1 with 35 distinct assertion failures (105 failed executions under CI retries). All three class IDs classify as assertion failures before. |
| Local test-intent gate | Exit 1, expected: acceptance-stale only. No author acceptance added. |

Every baseline failure is IllegalStateException in scorer.close() at the fixture's teardown
line 73, from RerankerAssembly.close reporting "Assembly sessions have not retired: null".
The console's before lane-2 count matches CI; its XML aggregate additionally includes one
skipped entry. The first candidate lane-2 attempt failed compilation because the new cross-role
test referenced SPLADE's package-private OutputFormat from another package. The test was moved
to the SPLADE package without changing assertions or production visibility; that failed attempt
is not a passing check.

The full consumer Test tasks ran in one serial slot invocation (with --continue), alongside
benchmarks:classes and the changed modules' static checks. The three compatibility/retry
classes executed all 40 cases with zero failures or skips in the full suites. Model-dependent
and platform-dependent skips are reported above, rather than counted as executed passes.
The constructor callers in system-tests' integration source set need real-model integration
coverage; the reported system-tests result is its unit Test task, not that integration tier.
No running application or dev stack was reused; shared resources were the Gradle slots and
Gradle/npm caches.

The D4 before output reports 25 tests / 24 failures for reranker and 89 / 81 for worker-core.
Retries account for 105 failed executions of 35 distinct failing cases; retries of a parameterized
method also re-execute its passing SPLADE/embedding arguments. The candidate runs 9 reranker
and 31 worker-core cases, all passing with no skips. The generated evidence retains the actual
before failure output, commands, source digests and producer digest. The producer digest matches
the scratch runner made by applying the recorded patch; the repository runner stays unchanged.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a16c4a-9299-71e0-95c2-83cef8157c1d",
  "verdict": "accept",
  "digest": "sha256:3958e2de710918570021f9b4e77ca82c744af93e61db730a0fdef1a6db0516f8"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a16dd5-b321-79d2-bcbc-d2508f07b079",
  "verdict": "accept",
  "digest": "sha256:e9f2fbbaf588bec2490bbed98439e801facd58362dccd45e7403edf2a2a06940"
}
```
