---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: 429de09b-ce0d-474d-8a0c-918054af76df
---

# Test-intent entries

Production now publishes a parent and its chunk family through one new call,
`IndexingCoordinator.replaceTextFamily`, instead of making the per-row `indexSingle`,
observer and `deleteChunksForParentDocId` calls itself. Every flagged existing file only adapts
its coordinator test double to answer that call with those same per-row calls, so the tests'
stubs, fault scripts, counters and verifications still see every row. No assertion, input,
fault, timeout or tag changed, and no test was repaired. The two new test classes
(`TextArtifactReuseTest`, `ReextractionArtifactReuseTest`) are exempt and pin the new behaviour.

Execution evidence: `gates/test-intent/evidence/e5-reuse-publication-fixtures.json` (class-wide
preservation checks, candidate and before-state `5cc78ebcb4`, both passing). Independent
acceptance is intentionally absent.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/testFixtures/java/io/justsearch/adapters/lucene/runtime/CoordinatorPublicationFixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/PipelineTestFixture.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "The enrichment redesign the owner agreed on 2026-11-02, whose recommendations include \"#1098 (re-extract after extractor or limit changes): yes.\" and \"Enrichment redesign priorities, in order: (1) always finishes, never stuck or starved; (2) search stays quick while it runs (ADR-0048: slow down, not pause); (3) honest progress; (4) raw speed last, as tuning.\"",
          "choice": "Settled scenarios M1 \"changed text and new files as today\", M2 \"retries and states truthful\" and M5 \"publication, fence and journal ordering unchanged\": the existing loop tests that pin those outcomes keep every input, fault script and expected outcome; only their IndexingCoordinator doubles answer the new family-replacement call with the per-row writes those tests already script and observe.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/e5-reuse-publication-fixtures.json",
      "assessment": {
        "purpose": "Give producer-side IndexingCoordinator doubles one shared answer for replaceTextFamily, the call through which JobBatchWriter.write now publishes a parent and its chunk family (it replaced the producer's own indexSingle, observer and ChunkDocumentWriter delete-and-index calls). CoordinatorPublicationFixture.publish (new, adapters-lucene testFixtures) makes those per-row calls on the double itself, so stubs, fault scripts, counters, captors and verifications on indexSingle and deleteChunksForParentDocId see every row. PipelineTestFixture adds coordinator() (RETURNS_DEFAULTS plus that answer), publicationAnswer(call) and coordinatorDelegate(actual) (the answer plus delegatesTo(actual) for everything else), and init() now builds its default writer with coordinator() instead of mock(IndexingCoordinator.class). No assertion, input, helper or other default of either file changed.",
        "callersAndStateOwners": "CoordinatorPublicationFixture.publish: PipelineTestFixture.publicationAnswer, and inline answers in worker-services IndexingLoopTest, IndexingLoopRestartTest and AdversarialCorpusIngestionTest. PipelineTestFixture's new methods: the 24 indexer-worker loop tests in the other two entries. Its init() default writer also reaches the unchanged AdmissionLookupLivenessTest, OriginBoundaryAdmissionTest, VanishedCanonicalSourceTest, VerifierGeneralCasesTest, VerifierIdentityLimitTest, VerifierInterleavedPoisonTest and VerifierMissingChildSupportTest. Production reached: JobBatchWriter.write (4-argument replaceTextFamily on the loop's coordinator, 3-argument on a wired lexical source) and IndexingLoop's migration batch (canReuseDocumentEmbedding, not routed). State: the doubles hold none; rows go wherever the double's other answers send them (nowhere for RETURNS_DEFAULTS, the test's own LuceneRuntime under @TempDir for delegating doubles). The testFixtures source set was already on the indexer-worker and worker-services test classpaths; no build file changed.",
        "indirectBehaviours": "Answered by a double, replaceTextFamily never runs the production method body: no TextArtifactReuse.attach (nothing is carried, whatever allowReuse says), no validate pass over all rows before the parent write, and no single read-lock and dispatch-lock region (a delegating double takes the real coordinator's locks per row instead). The answer keeps the production method's order and results: indexSingle(parent), then the parent-published observer with that result (only the 4-argument form has one), then deleteChunksForParentDocId(parent DOC_ID), then indexSingle per child, returning the parent's result. A throwing parent write propagates before the observer runs, a throwing child write after it, as in production. The rows passed in are the ones production built, so captured-document assertions still observe production output. canReuseDocumentEmbedding (migration batches only) is not routed: a RETURNS_DEFAULTS double answers false (embed as before), a delegating double forwards it to the real coordinator. On the before-state, which has no replaceTextFamily, the routing branch is never reached and the doubles behave exactly as the baseline mock(...) or delegatesTo(...) did.",
        "evidence": "gates/test-intent/evidence/e5-reuse-publication-fixtures.json, written by run-evidence.mjs: the 34 class-wide checks (the 27 flagged test classes and the 7 unchanged PipelineTestFixture.init() consumers) executed 130 cases on candidate 6049dc10b6, all passed, none skipped; with --before 5cc78ebcb4 (the gate's merge-base) and the candidate PipelineTestFixture and CoordinatorPublicationFixture overlaid, every one of the same candidate check files also passed there (classification 'passed'). So the adapted doubles change no outcome against the old producer, and the unchanged assertions, fault scripts and verifications (doThrow on indexSingle, write and probe counters, captured documents, inOrder lexical-before-candidate, verifyNoMoreInteractions) hold against the new producer. The script exits 1 because a preservation check is not a repair; no fail-before is claimed.",
        "unresolvedRisks": "These tests no longer exercise the production replaceTextFamily body, so they say nothing about artifact reuse, whole-family validation or the lock scope under their outage, fault and retry scenarios; that path is pinned only by the new exempt TextArtifactReuseTest and ReextractionArtifactReuseTest, which the change's verifier judges. A later change to the production method's row order or observer timing would not be seen by these doubles, because the answer restates that order. Each double now also records a replaceTextFamily interaction; no flagged test verifies its absence except through UnavailableIdentityBatchTest's verifyNoMoreInteractions, which stays as strict."
      }
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/CanaryWritePathCoverageTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/DocValuesServiceOutageTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ExistingSchemaHealthTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/InvalidTokenPositionOutageTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/LiveWriterLockHealthTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/PersistentByteIoSourceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/PipelineHealthProofTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SharedMappingLinkageTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SharedNormOutageTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VerifierReadOnlyWriterTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WriterAliasRetargetTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WriterDocumentIoTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WriterLegacyRetargetSourceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WriterLegacySourceStreamTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WriterUnreadableAliasTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WriterVanishedAliasTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "The enrichment redesign the owner agreed on 2026-11-02, whose recommendations include \"#1098 (re-extract after extractor or limit changes): yes.\" and \"Enrichment redesign priorities, in order: (1) always finishes, never stuck or starved; (2) search stays quick while it runs (ADR-0048: slow down, not pause); (3) honest progress; (4) raw speed last, as tuning.\"",
          "choice": "Settled scenarios M1 \"changed text and new files as today\", M2 \"retries and states truthful\" and M5 \"publication, fence and journal ordering unchanged\": the existing loop tests that pin those outcomes keep every input, fault script and expected outcome; only their IndexingCoordinator doubles answer the new family-replacement call with the per-row writes those tests already script and observe.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/e5-reuse-publication-fixtures.json",
      "assessment": {
        "purpose": "Keep each test's scripted coordinator double reaching the same per-row calls. Fourteen files add one first line to the double's answer lambda, routing replaceTextFamily to PipelineTestFixture.publicationAnswer; every other call still goes to the same reflective delegation to the test's real runtime coordinator, with the same fault capture, probe counting and fault switches on indexSingle and probeIngestion (PipelineHealthProofTest's double counts and faults without delegating; same routing). VerifierReadOnlyWriterTest and WriterDocumentIoTest replace mock(IndexingCoordinator.class, delegatesTo(runtime coordinator)) with PipelineTestFixture.coordinatorDelegate(runtime coordinator). Without the routing, production's replaceTextFamily would make its row writes inside the real coordinator, where these lambdas cannot see or fault them. No assertion, input, fault, timeout or tag changed.",
        "callersAndStateOwners": "Each double is built inside its own test method and handed to the IndexingLoop under test as its writer (h.writer / scenario.writer / s.writer); JobBatchWriter.write is the caller of replaceTextFamily. State: each test's own LuceneRuntime and SQLite job queue under @TempDir, plus the PipelineTestFixture harness; nothing shared across tests.",
        "indirectBehaviours": "Answered by a double, replaceTextFamily never runs the production method body: no TextArtifactReuse.attach (nothing is carried, whatever allowReuse says), no validate pass over all rows before the parent write, and no single read-lock and dispatch-lock region (a delegating double takes the real coordinator's locks per row instead). The answer keeps the production method's order and results: indexSingle(parent), then the parent-published observer with that result (only the 4-argument form has one), then deleteChunksForParentDocId(parent DOC_ID), then indexSingle per child, returning the parent's result. A throwing parent write propagates before the observer runs, a throwing child write after it, as in production. The rows passed in are the ones production built, so captured-document assertions still observe production output. canReuseDocumentEmbedding (migration batches only) is not routed: a RETURNS_DEFAULTS double answers false (embed as before), a delegating double forwards it to the real coordinator. On the before-state, which has no replaceTextFamily, the routing branch is never reached and the doubles behave exactly as the baseline mock(...) or delegatesTo(...) did. For these doubles the real coordinator still serves every other call, including canReuseDocumentEmbedding on migration batches.",
        "evidence": "gates/test-intent/evidence/e5-reuse-publication-fixtures.json, written by run-evidence.mjs: the 34 class-wide checks (the 27 flagged test classes and the 7 unchanged PipelineTestFixture.init() consumers) executed 130 cases on candidate 6049dc10b6, all passed, none skipped; with --before 5cc78ebcb4 (the gate's merge-base) and the candidate PipelineTestFixture and CoordinatorPublicationFixture overlaid, every one of the same candidate check files also passed there (classification 'passed'). So the adapted doubles change no outcome against the old producer, and the unchanged assertions, fault scripts and verifications (doThrow on indexSingle, write and probe counters, captured documents, inOrder lexical-before-candidate, verifyNoMoreInteractions) hold against the new producer. The script exits 1 because a preservation check is not a repair; no fail-before is claimed.",
        "unresolvedRisks": "These tests no longer exercise the production replaceTextFamily body, so they say nothing about artifact reuse, whole-family validation or the lock scope under their outage, fault and retry scenarios; that path is pinned only by the new exempt TextArtifactReuseTest and ReextractionArtifactReuseTest, which the change's verifier judges. A later change to the production method's row order or observer timing would not be seen by these doubles, because the answer restates that order. Each double now also records a replaceTextFamily interaction; no flagged test verifies its absence except through UnavailableIdentityBatchTest's verifyNoMoreInteractions, which stays as strict."
      }
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ExtractionInfrastructureTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OutageLifecycleTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OutageWaitingStatusTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ServiceOutageBoundaryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SharedServiceAvailabilityTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnavailableIdentityBatchTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VerifierCappedBacklogStatusTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopRestartTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "The enrichment redesign the owner agreed on 2026-11-02, whose recommendations include \"#1098 (re-extract after extractor or limit changes): yes.\" and \"Enrichment redesign priorities, in order: (1) always finishes, never stuck or starved; (2) search stays quick while it runs (ADR-0048: slow down, not pause); (3) honest progress; (4) raw speed last, as tuning.\"",
          "choice": "Settled scenarios M1 \"changed text and new files as today\", M2 \"retries and states truthful\" and M5 \"publication, fence and journal ordering unchanged\": the existing loop tests that pin those outcomes keep every input, fault script and expected outcome; only their IndexingCoordinator doubles answer the new family-replacement call with the per-row writes those tests already script and observe.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/e5-reuse-publication-fixtures.json",
      "assessment": {
        "purpose": "Keep each test's plain IndexingCoordinator mock observing the same per-row calls. Every changed line replaces mock(IndexingCoordinator.class) with an equivalent RETURNS_DEFAULTS mock whose only addition is the replaceTextFamily answer: PipelineTestFixture.coordinator() in the indexer-worker files, a private coordinatorMock() in IndexingLoopTest, and the same inline answer in IndexingLoopRestartTest and AdversarialCorpusIngestionTest. Stubs such as doThrow(...).when(x).indexSingle(any()), captors, verify(...).indexSingle, the inOrder lexical-before-candidate check in IndexingLoopTest and verifyNoMoreInteractions in UnavailableIdentityBatchTest are unchanged and now see the rows the answer writes. Without it, the bare mock would answer replaceTextFamily with false and write no row, so those stubs and verifications would no longer be reached. No assertion, input, fault, timeout or tag changed.",
        "callersAndStateOwners": "Each mock is created in its test or in the file's own loop builders (IndexingLoopTest.newLoop, wireActive, the active-lexical-source cases; StaleSourceHandler cases, which never call replaceTextFamily) and passed to IndexingLoop, StaleSourceHandler or a RunningRuntime mock as its coordinator; JobBatchWriter.write is the caller of replaceTextFamily. State: SQLite job queues and files under @TempDir or temp files; the mocks write nowhere.",
        "indirectBehaviours": "Answered by a double, replaceTextFamily never runs the production method body: no TextArtifactReuse.attach (nothing is carried, whatever allowReuse says), no validate pass over all rows before the parent write, and no single read-lock and dispatch-lock region (a delegating double takes the real coordinator's locks per row instead). The answer keeps the production method's order and results: indexSingle(parent), then the parent-published observer with that result (only the 4-argument form has one), then deleteChunksForParentDocId(parent DOC_ID), then indexSingle per child, returning the parent's result. A throwing parent write propagates before the observer runs, a throwing child write after it, as in production. The rows passed in are the ones production built, so captured-document assertions still observe production output. canReuseDocumentEmbedding (migration batches only) is not routed: a RETURNS_DEFAULTS double answers false (embed as before), a delegating double forwards it to the real coordinator. On the before-state, which has no replaceTextFamily, the routing branch is never reached and the doubles behave exactly as the baseline mock(...) or delegatesTo(...) did.",
        "evidence": "gates/test-intent/evidence/e5-reuse-publication-fixtures.json, written by run-evidence.mjs: the 34 class-wide checks (the 27 flagged test classes and the 7 unchanged PipelineTestFixture.init() consumers) executed 130 cases on candidate 6049dc10b6, all passed, none skipped; with --before 5cc78ebcb4 (the gate's merge-base) and the candidate PipelineTestFixture and CoordinatorPublicationFixture overlaid, every one of the same candidate check files also passed there (classification 'passed'). So the adapted doubles change no outcome against the old producer, and the unchanged assertions, fault scripts and verifications (doThrow on indexSingle, write and probe counters, captured documents, inOrder lexical-before-candidate, verifyNoMoreInteractions) hold against the new producer. The script exits 1 because a preservation check is not a repair; no fail-before is claimed.",
        "unresolvedRisks": "These tests no longer exercise the production replaceTextFamily body, so they say nothing about artifact reuse, whole-family validation or the lock scope under their outage, fault and retry scenarios; that path is pinned only by the new exempt TextArtifactReuseTest and ReextractionArtifactReuseTest, which the change's verifier judges. A later change to the production method's row order or observer timing would not be seen by these doubles, because the answer restates that order. Each double now also records a replaceTextFamily interaction; no flagged test verifies its absence except through UnavailableIdentityBatchTest's verifyNoMoreInteractions, which stays as strict."
      }
    }
  ]
}
```

## Acceptance records
