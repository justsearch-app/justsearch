---
schema: test-intent.v1
task: redesign-e4c
author-role: builder
author-session: redesign-e4c-builder
---

# Test-intent entries

Source location: task redesign-e4c request. This is E4c deletion and transfer.
All production enrichment callers now use bounded lane operations. The combined sparse/NER
phases, combined outcome/signature, full embedding batches, full-batch entry points, inert dense
share supplier, ID caches, batch counter and per-operation commit fields are removed. Dense
pooling, authority/revision guards, receipts and scheduler settings/metric names are unchanged.

Settled E4b decisions quoted by the assignment: "The combined bundled write no longer exists
for separate providers, so storage attribution is per lane write"; the dense-share supplier is
inert because "the three-second dense reservation" is gone ("The dense reservation became a
ledger assertion"). The third decision restores the terminal SPLADE diagnostic. The lane also
keeps the per-stage "Enrichment dispositions" log formerly emitted by the combined path, and restores its `splade.attempts` observation immediately before native sparse work. Sparse/NER native work also inherits the existing `enrichment.batch` tracing scope formerly supplied by the combined path.

The verification compatibility patch adds only constructor forwarding overloads to HEAD. It
restores no diagnostic or behavior, and lets the same transferred tests compile against the
original production operations in a shared clone with its own build folders. Preservation runs
must pass on both sides; the diagnostic repair must fail before by assertion. The builder leaves
evidence JSON and acceptance records to the independent verifier, as requested.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/CombinedChunkPublicationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BackfillSnapshotTestSupport.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BlankChunkReadinessIntegrationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPacePolicyUseTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPaceStretchTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentCycleBudgetTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationReconciliationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentInternalStopTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPublicationBenchmarkTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentQuarantineProgressTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LaneBackfillTestSupport.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeOptInResumeTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "split",
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "The surviving assertions run the production processDenseUnit, processParentBackfill/processChunkBackfill, and processNerUnit operations. Test-only fixtures retain their input maps and state between native turns, take immutable copies of submitted updates before scratch maps are cleared, and supply explicit pending status/chunk metadata. Source-replacement hooks run at getEnrichmentInputPage or at the retained fallback native call, rather than at a retired batch call. Assertions on stored fields, retry limits, receipts, unavailable providers, no stale/excluded publication, reconstructed text, pooling, and hard stops are retained. Separate native turns and an explicit reader refresh observe unaffected siblings. The separate Obsolete entries below account for the exact retired contracts; no such assertion is claimed as an adaptation.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4c request",
          "quote": "delete production code left with only test callers after the fair scheduler landed, and move its tests to the lane operations."
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentInternalStopTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/CombinedChunkPublicationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPublicationBenchmarkTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4c request",
          "quote": "an assertion on a contract only the combined op had (progressed(), bundling, share supplier) may be retired as Obsolete when the outcome it protects is kept in a lane test."
        },
        {
          "kind": "removal",
          "removed": [
            "modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java#processCombinedBackfill",
            "modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java#CombinedOutcome"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. combined shape and continuation: Retired CombinedOutcome.progressed()/recordTiming() assertions, the complete-batch completion-count assertion, combined selection of the unadmitted NER tail, and single merged/bundled-write assertions. CombinedChunkPublicationTest still requires 50 accepted chunk writes in total and exactly 50 encodes with and without refresh; the benchmark measures all bounded unit writes after four warm-up passes. Blank-content retry counts, terminal FAILED, no reselection, accepted-prefix receipts, and retained owner release remain asserted on lanes. Native unit selection can leave an unstarted tail CANCELLED when a provider retires; all selected rows must remain uncharged and unpublished."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4c request",
          "quote": "an assertion on a contract only the combined op had (progressed(), bundling, share supplier) may be retired as Obsolete when the outcome it protects is kept in a lane test."
        },
        {
          "kind": "removal",
          "removed": [
            "modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java#embedShareExhaustedSupplier"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. dense reservation: Retired exhaustedEmbeddingShareDoesNotForceAFirstDenseUnit and the two reservation/starvation control methods in CombinedEnrichmentLongDocumentTest. The share supplier is inert because \"the three-second dense reservation\" is gone: \"The dense reservation became a ledger assertion\". Lane admission replaces the share supplier in realPendingSpladeAllowsDeferralButNeverWritesThePreservationOnlyUpdate; its sparse publication and unchanged withheld dense artifact remain asserted. Independent NER/peer service under dense holds is checked by EnrichmentLaneDecompositionTest#acquisitionFailureHoldsProviderWithoutRetryChargeAndPeersContinue and FairEnrichmentSchedulerTest#D5b_oneProviderHoldCoversEveryLaneItServesThroughBothCallers. FairEnrichmentSchedulerTest#D1_continuingIngestionServesEveryContinuouslyEligibleLane and #D3_unequalCostsShareChargedServiceNotTurnCounts protect the scheduling outcome."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPacePolicyUseTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentCycleBudgetTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4c request",
          "quote": "an assertion on a contract only the combined op had (progressed(), bundling, share supplier) may be retired as Obsolete when the outcome it protects is kept in a lane test."
        },
        {
          "kind": "removal",
          "removed": [
            "modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java#processCombinedBackfill",
            "modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java#CombinedOutcome"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. per-batch scheduling and duplicate combined cases: Combined priority-chunk ordering and once-per-combined-call classification/phase limits no longer select service. manyLateChunkedParentsDoNotSpendTheCycleOnClassification, slowOverLimitProbeDoesNotStarveChunks, and slowSuccessfulSinglePassDoesNotStarveChunks are superseded by FairEnrichmentSchedulerTest#P1095_chunksAdvanceWithinThreeCyclesUnderTheOriginalLongDocumentCosts, with deadline/arena-OOM route retention also checked by EnrichmentUnitBoundaryTest#deadlineSpentByNullOrArenaOomClassificationRetainsItsRoute. The chunk-only tail stays in chunksAdvanceDuringResumableParentBacklogAndThroughTheChunkOnlyTail; normalEncoderSequenceMatchesRoundOne keeps its 830ms modeled work and 18 publications, retiring only the cross-lane order. Combined variants of EnrichmentPreservationTest keyset/budget cases duplicate their DENSE_PARENT, DENSE_CHUNK, SPARSE_PARENT, SPARSE_CHUNK and NER variants and are omitted; its poison batch returns aligned bad-row output so a bounded turn tests the same cursor oracle without inline fallback. COMBINED geometry/revision proof-race variants duplicate CHUNK and SPLADE in EnrichmentRevisionRaceTest#legacyProofRaceCannotPublishFailure. A completed unit need not have selected a later NER/sparse unit to cancel: call counts and absence of retry charges remain checked by EnrichmentUnitBoundaryTest#hardStopAfterOneUnitPreservesItsResultAndLeavesTheRestCancelled."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOps.java#processEmbeddingBackfill",
            "modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOps.java#commitAfterBatch",
            "modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/NerBackfillOps.java#commitOps"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Retired per-operation commit expectations together with the removed batch operations and inert commit fields. The lane unit assertions require no commit; FairEnrichmentSchedulerTest#D9_smallTurnsUseTheThresholdsRetryAFailedCommitAndDrainAtIdle retains scheduler-owned durability, threshold, retry and idle-drain outcomes."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOpsTest.java"
      ],
      "class": "New",
      "behaviour": "One terminal SPLADE warning follows an accepted FAILED publication, including acceptance followed by result-delivery failure, and never a rejected write. It names the document and occurs only once in that operation.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4c request",
          "quote": "Move the \"reached terminal SPLADE FAILED\" warning into the production SPLADE lane path, emitted once and only after the terminal failure is published, and keep terminalSpladeWarningRequiresPublication as a lane test."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4c-terminal-warning.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOpsTest.java#terminalSpladeWarningRequiresPublication"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java"
      ],
      "class": "New",
      "behaviour": "The production sparse lane reports the existing splade.attempts observation when native sparse encoding is attempted; untouched completed sparse work still produces no sparse attempt.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4c request",
          "quote": "A diagnostic, log or metric only the combined path had moves to the lane path the same way; a contract only the combined op had is retired under the earlier rule."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4c-native-attempt.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#receiptSeparatesComputedAcceptedEmptyFailedAndStale"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentLaneTracingTest.java"
      ],
      "class": "New",
      "behaviour": "Sparse and NER native work inherit the existing enrichment.batch tracing scope, and both lane operations restore their caller context after publication.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4c request",
          "quote": "A diagnostic, log or metric only the combined path had moves to the lane path the same way; a contract only the combined op had is retired under the earlier rule."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4c-tracing.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentLaneTracingTest.java#sparseNativeWorkInheritsTheEnrichmentSpan",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentLaneTracingTest.java#nerNativeWorkInheritsTheEnrichmentSpan"
      ],
      "repaired": true
    }
  ]
}
```

## Retired assertions and equivalent checks

Each row is Obsolete; retained or equivalent checks are named explicitly. Test class names in
this table are citations, not method selectors for the evidence runner.

| Old assertion/check | Retained outcome / equivalent check |
| --- | --- |
| `CombinedEnrichmentBackfillOpsTest#blankContentEscalation_progressedOnlyOnTheTerminalCycle` | Only progressed() is retired. Retry count, terminal FAILED and no reselection remain in this lane check and CombinedEnrichmentCycleBudgetTest#realWindowFailureStillReachesTheRetryLimit. |
| `CombinedEnrichmentBackfillOpsTest#embedBatchSizeMismatch_fallsBackToIndividualAttempts` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentBackfillOpsTest#completedParentSplade_deadlineAfterEmbedding_preservesAndPublishes` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentBackfillOpsTest#completedParentSplade_preservationFollowsNerOutcome` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentBackfillOpsTest#completedParentPreservationDoesNotDeferItsOwnPendingEmbedding` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentBackfillOpsTest#realPendingSpladeAllowsDeferralButNeverWritesThePreservationOnlyUpdate` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentBackfillOpsTest#acceptedPartialPublicationReleasesOnlyItsOwnerBesideWithheldPeer` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentBackfillOpsTest#completedWindowPublishesWithoutPreservationInference` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentBackfillOpsTest#chunkSpladeOff_repeatedCycles_neverEscalateChunkToFailed` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentFailureAccountingTest#completedSparseIsNotEncodedOrCountedAsNewWork` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentFailureAccountingTest#completionCreditRequiresSuccessfulPublication` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `EnrichmentAttemptAccountingTest#publicationFailureStillReportsTheAcceptedPrefix` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `EnrichmentAttemptAccountingTest#selectionCancellationIsTimedWithoutChangingTheRoutingSignal` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `EnrichmentInternalStopTest#anInternalDenseStopKeepsEarlierValidSlicesAndTheirActivePacingCredit` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentCycleBudgetTest#realWindowFailureStillReachesTheRetryLimit` | Combined continuation/routing signal; stored-state, retry and receipt assertions remain. |
| `CombinedEnrichmentBackfillOpsTest#allThreeEncoders_embeddingFailsOthersSucceed_singleMergedBatchedWrite` | One merged write retired; independent failed dense retry plus successful SPLADE/NER artifacts remain. |
| `CombinedEnrichmentBackfillOpsTest#lateChunking_flagOn_chunkedParent_singlePassVectorBundledWithSpladeAndNer` | One merged vector/sparse/NER write retired; single-pass vector, all stage outputs and untouched chunks remain. |
| `CombinedEnrichmentBackfillOpsTest#lateChunking_flagOn_embedWithSpansThrowsNonArenaOom_escalatesParentOnly_spladeStillBundled` | Cross-provider bundling retired; parent retry and independent sparse artifact remain. |
| `CombinedChunkPublicationTest#completedChunksAreNotEncodedAgain` | One 50-row write retired; 50 accepted rows and 50 encodes remain with/without refresh. |
| `EnrichmentUnitBoundaryTest#exhaustedEmbeddingShareDoesNotForceAFirstDenseUnit` | Obsolete share supplier; peer NER service under dense holds is covered by acquisitionFailureHoldsProviderWithoutRetryChargeAndPeersContinue and D5b_oneProviderHoldCoversEveryLaneItServesThroughBothCallers. |
| `CombinedEnrichmentLongDocumentTest#embedStageCannotConsumeTheWholeCycleWhileSpladeAndNerHaveWork` | Obsolete reservation; D1_continuingIngestionServesEveryContinuouslyEligibleLane and D3_unequalCostsShareChargedServiceNotTurnCounts retain fairness. |
| `CombinedEnrichmentLongDocumentTest#withoutTheReservationEmbedStarvesSpladeAndNer` | Obsolete absence-of-reservation control; same fair-scheduler lane checks retain nonstarvation. |
| `CombinedEnrichmentCycleBudgetTest#manyLateChunkedParentsDoNotSpendTheCycleOnClassification` | Duplicate scheduling check: P1095_chunksAdvanceWithinThreeCyclesUnderTheOriginalLongDocumentCosts; deadlineSpentByNullOrArenaOomClassificationRetainsItsRoute keeps both fallback classifications. |
| `CombinedEnrichmentCycleBudgetTest#slowOverLimitProbeDoesNotStarveChunks` | Duplicate: P1095_chunksAdvanceWithinThreeCyclesUnderTheOriginalLongDocumentCosts (4900,false). |
| `CombinedEnrichmentCycleBudgetTest#slowSuccessfulSinglePassDoesNotStarveChunks` | Duplicate: P1095_chunksAdvanceWithinThreeCyclesUnderTheOriginalLongDocumentCosts (4900,true). |
| `CombinedEnrichmentCycleBudgetTest#chunksAdvanceDuringResumableParentBacklogAndThroughTheChunkOnlyTail` | Mixed fixed-order priority limits retired/covered by P1095; the 50-chunk tail remains in this lane check. |
| `CombinedEnrichmentCycleBudgetTest#normalEncoderSequenceMatchesRoundOne` | Fixed cross-lane order retired; 830ms modeled work, 18 published vectors and no abort remain. |
| `BulkPacePolicyUseTest#eachPhaseIsSlicedAtTheSizeThePolicyNames` | Combined-only injected phase policy retired; production native-unit counts and phase separation remain. BulkPaceStretchTest keeps the one-second pacing bound on the surviving lanes. |
| `EnrichmentAttemptAccountingTest#cancelledFallbackPartitionsEverySelectedStageOnce` | Combined NER-tail enrollment retired; cancellation partitions every row actually selected by dense/sparse units and the one-document NER unit, with no retry charge. |
| `EnrichmentAttemptAccountingTest#computedOutputsSurviveProviderRetirementInBatchAndIndividualFallback` | Combined started-tail disposition contract retired; all selected rows remain deferred/cancelled, uncharged and unpublished, with computed output retained in accounting. |
| `EnrichmentAttemptAccountingTest#individualNerReportsAcceptedEmptyAndRejectedComputedOutput` | Batch-owned commit retired; accepted-empty and stale receipts remain per NER unit; D9_smallTurnsUseTheThresholdsRetryAFailedCommitAndDrainAtIdle covers durability. |
| `EnrichmentAttemptAccountingTest#individualSpladeRetainsComputedPrefixWhenALaterSliceIsCancelled` | Batch-owned commit retired; accepted prefix, cancellation and no systemic backoff remain; same D9 scheduler durability check. |
| `EnrichmentMapperDispositionTest#individualFallbackReportsRejectedOutputWithoutChangingCommitOrRetry` | Batch-owned commit retired; rejected output, unchanged retry and no false completion remain; same D9 durability check. |
| `EnrichmentUnitBoundaryTest#hardStopAfterOneUnitPreservesItsResultAndLeavesTheRestCancelled` | Unselected sparse/NER tail need not have cancellation tickets. One native call, published completed unit and no retry charge remain. |
| `EnrichmentPreservationTest#keysetTurnsPassPoisonSurviveRefreshDeletionAndIdleThenWrap and #budgetDeferredSuffixIsNextTurnWithoutBlankRetriesOrCharges` | Duplicate COMBINED variants removed; identical named checks run on each actual dense/sparse/NER lane. |
| `EnrichmentRevisionRaceTest#legacyProofRaceCannotPublishFailure` | Duplicate COMBINED proof-race variants removed; CHUNK and SPLADE variants retain geometry/revision race outcomes. |

## Verification commands

The slot wrapper also accepts scripts that launch Gradle; run these commands one at a time,
in the background. Whole-class selectors cover display-named and parameterized checks.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4c-preservation.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/CombinedChunkPublicationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BlankChunkReadinessIntegrationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPacePolicyUseTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPaceStretchTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentCycleBudgetTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationReconciliationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentInternalStopTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPublicationBenchmarkTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentQuarantineProgressTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerBackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeOptInResumeTest.java
```

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4c-terminal-warning.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOpsTest.java#terminalSpladeWarningRequiresPublication \
  --before cad41077bf813880cc36f2ed699b8fc3760c9af6 \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LaneBackfillTestSupport.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BackfillSnapshotTestSupport.java \
  --before-patch gates/test-intent/.changesets/redesign-e4c-before.patch
```

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4c-native-attempt.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#receiptSeparatesComputedAcceptedEmptyFailedAndStale \
  --before cad41077bf813880cc36f2ed699b8fc3760c9af6 \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LaneBackfillTestSupport.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BackfillSnapshotTestSupport.java \
  --before-patch gates/test-intent/.changesets/redesign-e4c-before.patch
```

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4c-tracing.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentLaneTracingTest.java#sparseNativeWorkInheritsTheEnrichmentSpan \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentLaneTracingTest.java#nerNativeWorkInheritsTheEnrichmentSpan \
  --before cad41077bf813880cc36f2ed699b8fc3760c9af6 \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BackfillSnapshotTestSupport.java \
  --before-patch gates/test-intent/.changesets/redesign-e4c-before.patch
```

## Builder check observations

The focused command selects `io.justsearch.indexerworker.loop.ops.*`,
`*CombinedChunkPublicationTest`, `*EnrichmentLaneDecompositionTest`,
`*FairEnrichmentSchedulerTest` and `*BackfillSchedulerModeRecordingTest` from
`:modules:worker-services:test`, with `-PskipWebBuild=true --continue`.
An early focused run exhausted the test JVM heap; after correcting the transfer fixtures,
the final focused run completed: 1,067 checks passed, none failed or skipped. The identical
checks on shared HEAD plus signature-only compatibility forwarding completed with 1,063 passes
and exactly four assertion failures: terminalSpladeWarningRequiresPublication,
receiptSeparatesComputedAcceptedEmptyFailedAndStale (splade.attempts), and the sparse/NER trace
scope methods. These are the intended diagnostic repairs; all preservation checks passed before
and after. The full three-module command completed:
adapters-lucene had 1,019 checks with no failures (3 skipped), indexer-worker had 2,639 with
no failures (39 skipped). Worker-services initially had nine failures: five transfer-fixture
failures subsequently fixed and covered by the focused run, plus the known LinuxRootMountInfoTest
and three WatcherUnavailabilityScopeTest environment failures. No skip is counted as a pass.

The first pre-push run found an unused parameter and dangling links to retired tests; the next
found three unnecessary qualifiers. These were corrected without suppressions or rule changes.
The final `node scripts/dev/java-prepush.mjs --unreferenced` completed successfully in 6m48s,
including PMD, the unreferenced-code check, app-services integration tests and UI integration
tests (the UI suite retained its five configured skips).
The test-intent gate reports only missing evidence/acceptance. Its nonblocking reconsider-rate
finding is 26.1% (12 of 46 audited PRs), above 10%.

Checks used the shared Gradle cache and slot, separate build folders in the shared before clone,
and test-owned temporary resources. No shared dev stack was started; no `.agents/preflight`
exists. The work is uncommitted: this worktree's Git metadata is outside the writable sandbox.
No evidence JSON was written; acceptance is left to the independent verifier.

## Acceptance records
