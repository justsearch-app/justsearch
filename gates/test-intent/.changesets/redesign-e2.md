---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: redesign-e2-builder-continuation
---

# Test-intent entries

Existing assertions expecting reset-to-PENDING, absent-status healing, regenerated postings,
preservation attempts/retries or withheld dense/window outcomes are replaced by the settled
preservation contract. Other expectations remain preservation checks, including vector/chunk/NER
retention, failure escalation for genuinely pending SPLADE, conditional revision rejection,
selection, long-document window resumption and complete-batch metric aggregation. Removing obsolete
SPLADE stubs in the partial aggregate-window test adapts its instrumentation; its oracle is unchanged.

The isolated new SparseRmwPreservationTest covers E2-1, E2-3 and E2-4 using a temporary real Lucene
index, doc-value-only sparse companions, encoded posting frequencies, reopen and an injected storage
read error. EnrichmentStorageDispositionTest covers E2-3 E1 accounting. Neither changes global state.
Their repair checks are listed below even though the files are exempt. The embedding-only method
is a P1 preservation check, excluded from newChecks because it passes before and after.
The supplied-sparse/content check also exercises P2 no healing: base actually heals an absent sparse
status on an unrelated rewrite, so this aspect cannot satisfy the brief's pass-before label.

SparsePreservationBenchmarkTest remains isolated, opt-in (`experiment`, `-PincludeExperiment=true`),
and temporary-index-only. It extends the previous harness to 5,000/50,000 documents, at least ten
segments, and batches of 1/8/32/128, with two warmup and five measured passes, reporting raw timing,
terms and features. Its ten-minute timeout accommodates the larger measurement matrix; timings are
observations, not acceptance assertions. It does not establish foreground latency or model throughput.

The compatibility patch adds only the new receipt constructor/accessor (neutral empty storage map)
and searcher-taking publication signature (forwards to the base publication path). It preserves base
behavior. No acceptance records or run-evidence output are authored by this builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RmwFieldPreservationTest.java",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RemovalAndRenameDurabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Partial and metadata rewrites carry existing sparse artifacts and status/retry values without enrolling absent stages.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E2-1 Completed sparse postings (feature terms and weights), SPLADE status (COMPLETED or COMPLETED_EMPTY) and SPLADE retry count survive an NER-only update, a metadata-only update (source/extraction metadata patch through the normal RMW path), a dense update that is not embedding-only (for example dense plus NER fields), and a recovery rewrite, with the SPLADE encoder absent, unavailable or failing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "P2 A caller that supplies SPLADE fields itself (SPLADE backfill, isolation pass) still writes exactly its own values; a document whose SPLADE is PENDING, FAILED or absent stays as it is (no healing, no invented COMPLETED).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e2.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java#rmwPreservesCompletedSpladeStatusAndRetry",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java#rmwKeepsMissingSpladeStatusAbsent",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java#rmwBatchPreservesCompletedSpladeStatus",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RmwFieldPreservationTest.java#spladePostingsSurvivePartialUpdate",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RmwFieldPreservationTest.java#chunkSpladeRemainsSearchableAfterRmw",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RemovalAndRenameDurabilityTest.java#sparseStatesAndRetriesSurviveMetadataUpdate",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RemovalAndRenameDurabilityTest.java#sparseAbsenceSurvivesMetadataUpdateAfterChunkConfigurationChange"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerPersistenceTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "class": "New",
      "behaviour": "Completed SPLADE work is not re-encoded or charged; dense/NER outcomes publish across deadlines, encoder failure, absence and backoff. Sparse computed/accepted counts now receive no preservation-only outputs; their aggregation rules remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E2-2 No preservation inference: a combined cycle that publishes dense or NER output for a document whose SPLADE is already COMPLETED makes zero SPLADE encoder calls for that document and publishes its earned outcome even when the SPLADE encoder is absent or failing.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_withPendingEmbedding_isPreservedWithoutReencoding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_withPendingNer_isPreservedWithoutReencoding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_deadlineAfterEmbedding_preservesAndPublishes",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_preservationFollowsNerOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentPreservationDoesNotDeferItsOwnPendingEmbedding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedWindowPublishesWithoutPreservationInference",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_encoderFailure_doesNotChargeRetryOrReplaceArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#chunkSpladeOn_chunkLane_preservesCompletedSpladeWithoutReencoding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java#completedSparseIsNotEncodedOrCountedAsNewWork",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#pendingEmbedding_preservesCompletedParentSplade_withoutEncoderCall",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#pendingNer_preservesCompletedParentSplade_withoutEncoderCall",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#absentSpladeEncoderPublishesNerAndRetainsCompletedSplade",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#failingSpladeEncoderIsNotCalledAndEmbeddingPublishes",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#untouchedSparseWorkIsNeitherComputedNorPublishedAgain",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#unusedFailingSparseEncoderDoesNotCreateAnAttemptOrWithholdEmbedding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerPersistenceTest.java#deadlineAfterEmbeddingPublishesWithoutSparseRederivation",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#failingSparseEncoderCannotAffectCompletedSparseDocumentsAcrossCycles",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#completedSparseBatchPublishesWithoutCallingFailingEncoder",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#completedSparseBatchDoesNotCallSlowFailingEncoder",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#costlySparseEncoderIsNotCalledForFinishedWorkWhileDenseAndNerAdvance",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#completedSparsePublishesEmbeddingDuringSparseBackoffOrEncoderRetirement"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/DoubleFieldIntegrationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingWindows1252ExtractionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "input re-encoding"
      ],
      "whatMoved": "The original catalog fingerprint preservation oracle retains its literal baseline digest and every legacy field shape. Its input excludes only the authorized new optional splade_payload field; the test name now identifies the legacy shape. Full-catalog additive compatibility is separately asserted in SparsePayloadPreservationTest. No numeric extraction expectation changes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E2-9 Compatibility: an index written by main 049e79a46f opens with the candidate; the added stored-only binary field is accepted through the extended CompatibleFieldAdditions predicate (ParityDiagnostics both paths); mixed legacy and new leaves read correctly after reopen and after a merge.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

The acceptor's execution inputs are:

```text
--before 049e79a46f
--before-patch gates/test-intent/compatibility/redesign-e2-before.patch
--overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java
--overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RmwFieldPreservationTest.java
--overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RemovalAndRenameDurabilityTest.java
--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java
--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java
--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java
--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java
--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerPersistenceTest.java
--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java
--overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java
--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentStorageDispositionTest.java
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java#rmwPreservesCompletedSpladeStatusAndRetry
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java#rmwKeepsMissingSpladeStatusAbsent
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java#rmwBatchPreservesCompletedSpladeStatus
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RmwFieldPreservationTest.java#spladePostingsSurvivePartialUpdate
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RmwFieldPreservationTest.java#chunkSpladeRemainsSearchableAfterRmw
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RemovalAndRenameDurabilityTest.java#sparseStatesAndRetriesSurviveMetadataUpdate
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RemovalAndRenameDurabilityTest.java#sparseAbsenceSurvivesMetadataUpdateAfterChunkConfigurationChange
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_withPendingEmbedding_isPreservedWithoutReencoding
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_withPendingNer_isPreservedWithoutReencoding
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_deadlineAfterEmbedding_preservesAndPublishes
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_preservationFollowsNerOutcome
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentPreservationDoesNotDeferItsOwnPendingEmbedding
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedWindowPublishesWithoutPreservationInference
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_encoderFailure_doesNotChargeRetryOrReplaceArtifact
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#chunkSpladeOn_chunkLane_preservesCompletedSpladeWithoutReencoding
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java#completedSparseIsNotEncodedOrCountedAsNewWork
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#pendingEmbedding_preservesCompletedParentSplade_withoutEncoderCall
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#pendingNer_preservesCompletedParentSplade_withoutEncoderCall
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#absentSpladeEncoderPublishesNerAndRetainsCompletedSplade
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#failingSpladeEncoderIsNotCalledAndEmbeddingPublishes
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#untouchedSparseWorkIsNeitherComputedNorPublishedAgain
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#unusedFailingSparseEncoderDoesNotCreateAnAttemptOrWithholdEmbedding
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerPersistenceTest.java#deadlineAfterEmbeddingPublishesWithoutSparseRederivation
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#failingSparseEncoderCannotAffectCompletedSparseDocumentsAcrossCycles
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#completedSparseBatchPublishesWithoutCallingFailingEncoder
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#completedSparseBatchDoesNotCallSlowFailingEncoder
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#costlySparseEncoderIsNotCalledForFinishedWorkWhileDenseAndNerAdvance
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#completedSparsePublishesEmbeddingDuringSparseBackoffOrEncoderRetirement
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java#partialUpdatesRetainPostingsStatusAndRetryAfterReopen
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java#batchTraversesSparseDictionaryOncePerLeaf
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java#unreadableSparseDocumentIsWithheldWhileOtherDocumentsPublish
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java#suppliedSparseAndContentReplacementKeepTheirOwnSemantics
--check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentStorageDispositionTest.java#unreadableArtifactDefersEarnedOutcomeWithoutRetryCharge
--overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#mapperDerivesAllAcceptedFeaturesAndRewritesKeepFrequencies
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#legacyScanRunsOnceAndEmptyOrFailedRowsNeverDecodeOldPayload
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#malformedPayloadsWithholdOnlyTheirRowsWithoutChangingStoredWork
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#storedPayloadReadFailureIsIsolatedInConditionalPublication
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#suppliedSparseWinsWithReplacementContentAndStatusOnlyFailureDropsBoth
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#chunkPayloadSurvivesBatchPublicationReopenAndMerge
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#payloadIsDerivedAndCannotCertifyAnInvalidStatus
--check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#payloadAdditionAcceptedByBothParityPaths
```

The payload checks are isolated additions (E2-5 through E2-9, plus E2-3 stored-field failure injection). The new field is derived exclusively by FieldMapper; the benchmark is opt-in scaffolding for E2-10. No generated execution evidence or acceptance record is written by the builder.

Round decision: the first full candidate check found obsolete intentionally-unused fault stubs, an individual-lane mock without its content response, the displaced unused RMW overload, and two fingerprint checks whose inputs now contain the authorized field addition. Repair those fixtures without changing their outcome predicates; retain the original fingerprint digest over the complete legacy shape. Run the full candidate suites again. Linux filesystem and bootstrap failures require same-test baseline comparison.

Tests added or rewritten (all durable except the opt-in measurement harness):

Tests added or rewritten: modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePreservationBenchmarkTest.java — E2-4 cost measurement — scaffolding

Tests added or rewritten: modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java — E2-1/E2-3/E2-4, P1/P2/P4 — durable

Tests added or rewritten: modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java — E2-1/P2 — durable

Tests added or rewritten: modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RemovalAndRenameDurabilityTest.java — E2-1/P2 — durable

Tests added or rewritten: modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RmwFieldPreservationTest.java — E2-1 — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerPersistenceTest.java — E2-2 — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java — E2-2 — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java — E2-2 — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java — E2-2/P5 — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java — E2-2 — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java — E2-2/P5 — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentStorageDispositionTest.java — E2-3/P5 — durable

Tests added or rewritten: modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java — E2-3/E2-5/E2-6/E2-7/E2-8/E2-9 — durable

Tests added or rewritten: modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/DoubleFieldIntegrationTest.java — E2-9, legacy catalog shape preservation — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingWindows1252ExtractionTest.java — E2-9, legacy catalog shape preservation — durable

## Builder observations

Result: needs decision. The Revision 4 payload is implemented in the uncommitted candidate, but the storage stop condition fired. No payload design changes were made after that measurement.

The opt-in harness completed successfully. Preservation medians (milliseconds per batch, with complete RMW medians in parentheses):

| Documents | Batch 1 | Batch 8 | Batch 32 | Batch 128 |
| --- | --- | --- | --- | --- |
| 5,000 | 0.538 (1.021) | 2.719 (5.931) | 2.301 (6.790) | 6.636 (22.903) |
| 50,000 | 0.081 (0.166) | 0.355 (1.211) | 1.867 (5.696) | 4.775 (19.723) |

Each payload measurement visited zero sparse terms and read exactly 128 features per document. The index had 12–13 leaves at 5,000 documents and 21–22 at 50,000. Each RMW loaded stored fields once per document; the cache prevented another stored-field seek for preservation. At a 32-document batch the decoded payload contributed 72,242 bytes beside 55,798 bytes of stored parent text at 50,000 documents. These are materialized bytes, not a measurement of physical compressed-block I/O.

| Parents + chunks | Before merge, legacy bytes | Before merge, payload bytes | Merged legacy bytes | Merged payload bytes | Merged growth |
| --- | --- | --- | --- | --- | --- |
| 5,000 + 5,000 | 9,094,048 | 15,331,264 | 6,908,863 | 13,128,044 | 90.017% |
| 50,000 + 50,000 | 68,876,868 | 131,971,767 | 54,310,207 | 117,641,621 | 116.611% |

The storage corpus has 128 sparse features per parent and per chunk, 30,000 vocabulary terms and repetitive synthetic text; it contains no dense vectors. After merge the added storage is about 621,918 bytes per 1,000 rows at 5,000 parents, and 633,314 bytes per 1,000 rows at 50,000 parents. Its ratio is not a production N7 measurement. It nevertheless exceeds the specified 25% guard and needs an orchestrator decision.

The harness uses legacy and payload-enabled mapper catalogs over otherwise identical rows; the legacy variant emits the existing FeatureFields without the new stored field. It uses disposable indexes, no inference models and no GPU. No shared dev stack was started. Checks used their own build folders and indexes, plus the shared Gradle slot protocol, Gradle cache and CPU/disk. Initial pre-push integration tests launched their own application children through the existing tests.

The targeted adapter command passed (payload and legacy preservation plus FieldMapper checks). Initial full candidate tests found two intentional catalog additions conflicting with old whole-catalog digest assertions, obsolete strictly-unused encoder fault stubs, and a missing individual content response in a fake index. These test fixtures were repaired while retaining all behavior assertions and the literal legacy digest. The displaced unreferenced RMW overload was deleted. Named repair checks use method-resolvable display names so the evidence runner can find them. Baseline encoder-call verifications are wrapped in assertDoesNotThrow so their proven fail-before is recorded as a JUnit assertion rather than a Mockito exception.

The first baseline overlay run compiled and failed for the requested behavioral reasons: 18 adapter test instances and 25 worker test instances failed, with 51 adapter and 115 worker instances passing. The seven Mockito verification failures were subsequently rerun as JUnit assertion failures (41 checks, nine failed). No compile failure is claimed as fail-before.

N7 real-system primary searchability, foreground p50/p95 and enrichment throughput were not measured: real model/GPU checks belong to the independent verifier. P7 downgrade is verification-only and was not executed. The release-note wording is in CHANGELOG.md. No run-evidence command or acceptance record was written.

## Per-check observations

Each before outcome below was observed in the shared clone of 049e79a46f with the neutral compatibility patch and test overlays. Encoder-call verifications use the follow-up JUnit assertion run. Parameterized repair checks can include preservation cases that pass before; the repaired case fails by assertion. Display-name-only changes make the same methods resolvable by the evidence runner. This table is a builder observation, not generated evidence or acceptance.

| Check ID | Before | Final candidate |
| --- | --- | --- |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java#rmwPreservesCompletedSpladeStatusAndRetry | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java#rmwKeepsMissingSpladeStatusAbsent | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java#rmwBatchPreservesCompletedSpladeStatus | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RmwFieldPreservationTest.java#spladePostingsSurvivePartialUpdate | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RmwFieldPreservationTest.java#chunkSpladeRemainsSearchableAfterRmw | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RemovalAndRenameDurabilityTest.java#sparseStatesAndRetriesSurviveMetadataUpdate | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RemovalAndRenameDurabilityTest.java#sparseAbsenceSurvivesMetadataUpdateAfterChunkConfigurationChange | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_withPendingEmbedding_isPreservedWithoutReencoding | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_withPendingNer_isPreservedWithoutReencoding | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_deadlineAfterEmbedding_preservesAndPublishes | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_preservationFollowsNerOutcome | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentPreservationDoesNotDeferItsOwnPendingEmbedding | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedWindowPublishesWithoutPreservationInference | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_encoderFailure_doesNotChargeRetryOrReplaceArtifact | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#chunkSpladeOn_chunkLane_preservesCompletedSpladeWithoutReencoding | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java#completedSparseIsNotEncodedOrCountedAsNewWork | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#pendingEmbedding_preservesCompletedParentSplade_withoutEncoderCall | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#pendingNer_preservesCompletedParentSplade_withoutEncoderCall | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#absentSpladeEncoderPublishesNerAndRetainsCompletedSplade | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#failingSpladeEncoderIsNotCalledAndEmbeddingPublishes | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#untouchedSparseWorkIsNeitherComputedNorPublishedAgain | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#unusedFailingSparseEncoderDoesNotCreateAnAttemptOrWithholdEmbedding | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerPersistenceTest.java#deadlineAfterEmbeddingPublishesWithoutSparseRederivation | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#failingSparseEncoderCannotAffectCompletedSparseDocumentsAcrossCycles | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#completedSparseBatchPublishesWithoutCallingFailingEncoder | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#completedSparseBatchDoesNotCallSlowFailingEncoder | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#costlySparseEncoderIsNotCalledForFinishedWorkWhileDenseAndNerAdvance | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#completedSparsePublishesEmbeddingDuringSparseBackoffOrEncoderRetirement | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java#partialUpdatesRetainPostingsStatusAndRetryAfterReopen | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java#batchTraversesSparseDictionaryOncePerLeaf | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java#unreadableSparseDocumentIsWithheldWhileOtherDocumentsPublish | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java#suppliedSparseAndContentReplacementKeepTheirOwnSemantics | Assertion failed | Passed, executed |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentStorageDispositionTest.java#unreadableArtifactDefersEarnedOutcomeWithoutRetryCharge | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#mapperDerivesAllAcceptedFeaturesAndRewritesKeepFrequencies | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#legacyScanRunsOnceAndEmptyOrFailedRowsNeverDecodeOldPayload | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#malformedPayloadsWithholdOnlyTheirRowsWithoutChangingStoredWork | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#storedPayloadReadFailureIsIsolatedInConditionalPublication | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#suppliedSparseWinsWithReplacementContentAndStatusOnlyFailureDropsBoth | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#chunkPayloadSurvivesBatchPublicationReopenAndMerge | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#payloadIsDerivedAndCannotCertifyAnInvalidStatus | Assertion failed | Passed, executed |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java#payloadAdditionAcceptedByBothParityPaths | Assertion failed | Passed, executed |

Preservation checks include SparseRmwPreservationTest#embeddingOnlyUpdateKeepsEncodedSparsePostings; DoubleFieldIntegrationTest#fingerprintForLegacyCatalogShapeMatchesBaseline; IndexingWindows1252ExtractionTest#enN3LegacyCatalogFingerprintStillMatchesBaseline; and the unchanged long-document, revision, dense/chunk/NER and accounting tests in the required module suites. They are not repair IDs. No full real-system P1–P6 verification is claimed.

## Changed files

- CHANGELOG.md
- SSOT/catalogs/fields.v1.json
- SSOT/schemas/indexing/field-catalog.schema.json
- gates/test-intent/.changesets/redesign-e2.md
- gates/test-intent/compatibility/redesign-e2-before.patch
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/commit/CompatibleFieldAdditions.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/FieldMapper.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/LuceneRuntimeTypes.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/SparsePayload.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/WritePathOps.java
- modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json
- modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java
- modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/DoubleFieldIntegrationTest.java
- modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RemovalAndRenameDurabilityTest.java
- modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RmwFieldPreservationTest.java
- modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java
- modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePreservationBenchmarkTest.java
- modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java
- modules/indexing/src/main/java/io/justsearch/indexing/SchemaFields.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/EnrichmentAccounting.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerPersistenceTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingWindows1252ExtractionTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentStorageDispositionTest.java

## Required command results

- No `.agents/preflight` exists in this checkout.
- `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true --continue :modules:adapters-lucene:test :modules:worker-services:test :modules:worker-core:test :modules:indexer-worker:test` — the final command exits 1 solely for the four worker-services failures listed below. Adapters executed 966 passing checks (three skipped). Worker-core previously executed 564 passing checks (26 skipped), and indexer-worker executed 2,457 passing checks (42 skipped); both unchanged tasks are UP-TO-DATE in the final command. The worker-services console reports `3299 tests completed, 4 failed, 23 skipped`. Fresh XML contains all 41 named repair IDs, passed and executed; the evidence runner's read-only ID selector resolved all of them.
- `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true :modules:worker-services:test --tests '*BackfillSchedulerSlowBatchTest' --tests '*LinuxRootMountInfoTest' --tests '*WatcherUnavailabilityScopeTest' --tests '*IndexingWindows1252ExtractionTest' :modules:adapters-lucene:test --tests '*DoubleFieldIntegrationTest'` in the before clone — exits 1: the repaired sparse cases fail by assertions, the four host-dependent failures match, and both adapted legacy-fingerprint checks pass.
- `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true :modules:adapters-lucene:test --tests '*SparsePayloadPreservationTest'` in the before clone — exits 1 with all eight methods failing by assertions.
- `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true -PincludeExperiment=true :modules:adapters-lucene:test --tests '*SparsePreservationBenchmarkTest'` — passes in 43 seconds; cost guard fails on the measured storage growth, not on timing.
- `git diff --check` — passes. Generated adapter catalog matches the SSOT catalog byte-for-byte.
- `node scripts/governance/gates/test-intent/cli.mjs --base 049e79a46f` — exits 1 because generated evidence and independent acceptance are intentionally absent. The Adaptation entries cover the two fingerprint preservation files; New entries remain invalid until their evidence is produced. No other missing classification was reported.

Matched environmental failures (same check, same failure on 049e79a46f and candidate):

- `LinuxRootMountInfoTest#missingDanglingAndAmbiguousUuidMappingsAreUnknown`: `NoSuchFileException` at line 100, `/dev/disk/by-uuid` absent.
- `WatcherUnavailabilityScopeTest#aNewerUnavailableIncarnationCannotBeClearedByAnOlderReconciliation`: assertion at line 63.
- `WatcherUnavailabilityScopeTest#endedUnavailableIncarnationIsRecoverableOnlyByItsOwnReconciliation`: assertion at line 29.
- `WatcherUnavailabilityScopeTest#inaccessibleRootWhoseAbsenceIsUnknownIsStillRootScoped`: assertion at line 49.

Initial source-checkout pre-push exited 1 for the displaced unused three-argument RMW overload and `IndexingRulesBootstrapIntegrationTest#fullMainHoldsFreshBatteryAndTimesOutUnknownWithDurablyQueuedWork` at line 72. The overload was deleted. The bootstrap test passed on the baseline in a separate headless clone; its original failure is not called a baseline-reproduced environmental failure. The final isolated headless pre-push result is recorded below when complete.

## Final pre-push wait

`env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced` was started in the isolated candidate clone. At handoff it is still waiting for the shared Gradle slot, not reported as passed. A live background waiter owns session 76119, reports every completion/error outcome, and reports a stall after a 65-minute deadline. It updates this section when the command exits. The storage guard is the reason for handing the assignment back while that final check is pending.

PREPUSH_RESULT_PENDING

## Acceptance records
