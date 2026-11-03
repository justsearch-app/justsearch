---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: redesign-e3a-builder
---

# Test-intent entries

E3a implements the settled unit-boundary, retained-routing, dense-identity, slice-validation,
batch-only-provider and atomic-publication scenarios in the builder assignment. No evidence or
acceptance record is authored here.

The only replaced #1095 expectation is explicitly authorized by E3a-3. The original test required
a window call after classification exhausted the deadline; it now requires no such call, followed
by useful window work next turn without another classification. Its single-pass classification
count, retained work, and no additional tokenization assertions remain. This is the assignment's
requested Adaptation, with the semantic change called out rather than treated as an unchanged oracle.

The slice checks use the settled revision, not the superseded restart-on-every-rejection draft:
"A pure position mismatch (wrong fromWindow: replay or skip-ahead) rejects the slice and keeps the
retained, valid partial unchanged"; all other malformed slices discard the partial, and repeated
rejections use the existing retry limit. The original out-of-order #1095 pin is unchanged.
Authority-race accounting follows the same revision: "DEFERRED as E1 defines it (no retry charge,
row pending, computed counted, not accepted)." Route-only entries share the existing 256 cap; the window-progress diagnostic counts computed windows rather than routes. This preserves the unchanged #1095 hard-preemption pin (zero windows) while retaining the classification decision. Reissuing the same provider through its lifecycle
is a new publication identity even when its dimension is unchanged.

Deliver-to-check mapping: E3a-1 uses the unit-boundary first/next-unit, interruption and source
invalidation checks; E3a-2/3 use expired deadlines and retained null/OOM classification routes;
E3a-4 uses dense-identity changes and lifecycle republication; E3a-5 uses malformed slices,
unchanged position-mismatch preservation and retry-limit checks; E3a-6 uses the concrete batch-only
provider with default window methods; E3a-7 uses the inference/publication/mutation-lock races and
disambiguation acknowledgement check.

The completed-publication preservation check uses the NaN correction input from the existing E1 EnrichmentMapperDispositionTest; a later provider swap must preserve its REJECTED_OUTPUT disposition, just as it preserves REJECTED_STALE and accepted publication.

The isolated new files use temporary Lucene indexes or local mocks. Interruption tests clear only
the interruption they set. Authority tests bind through the production scheduler/lifecycle and use
the real coordinator/write path, including the boundary after inference and before mutation.
During-inference ordinary dense and failing single-pass/window cases already protected by readiness checks are preservation checks; sparse, NER and successful single-pass/window cases exercise revoked authority. The boundary and mutation-lock cases are repair checks for every parameter. The independent hard-stop supplier after the first unit is a repair:
main either ignores it on dense/NER routes or leaves untouched sparse tickets deferred. Hard-stop disposition adaptations below follow E3a-1; retirement still leaves outcomes DEFERRED as the settled revision requires.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/CombinedChunkPublicationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationReconciliationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/BackfillProbeCommitPersistenceTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "Real-coordinator spies observe the authority-bearing publication overload. Inputs, injected failures, timing boundaries, counted receipts, and every assertion are unchanged. These fixtures are not overlaid for repair fail-before runs; the unmodified base fixtures exercise the base overload."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "E3a-3 replaces only the authorized post-deadline-window expectation: classification retains routing, the first turn starts zero windows, and the next turn starts window zero without classifying or tokenizing again. Other #1095 expectations are unchanged. Its window-capable fixture now mocks EmbeddingService, which declares the window seam; all other #1095 oracles stand unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3a request",
          "quote": "The next turn starts the routed work without classifying again."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3a request",
          "quote": "The replaced #1095 expectation (E3a-3) is an Adaptation with its source quoted."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3a request",
          "quote": "The design (\"deadlines bound starting units\"; \"retain the routing decision ... so the next turn does useful work\") authorizes replacing it; record the reason in test intent."
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentCycleBudgetTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The window-capable fixture mocks EmbeddingService, which declares the optional window seam, rather than a provider inheriting the batch-only defaults. Every scheduling, pooling, publication, and retry assertion is unchanged. A method-declaration capability check must see the fixture capability that its existing window stubs model."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The window-capable fixture mocks EmbeddingService. The exhausted-share test retires the unused first-window stub and explicitly asserts that no window starts; all existing assertions stay. E3a-1 deletes the embedding-share floor, so pending SPLADE can run without an initial dense unit.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3a request",
          "quote": "The unconditional first-unit floor (CombinedEnrichmentBackfillOps ~145) and the embedding budget-share floor (~123) are deleted."
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Only hard-stop dispositions change: untouched NER/SPLADE tickets in cancelledFallbackPartitionsEverySelectedStageOnce and the interrupted partial in partialWindowsAdvanceWithoutCompletingAnEmbedding now expect CANCELLED. Counters, accepted/computed counts, timing, retry policy, provider-retirement DEFERRED expectations and all other assertions stay. The individualNerReportsAcceptedEmptyAndRejectedComputedOutput fixture explicitly returns true from service availability; its oracle is unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3a request",
          "quote": "Retries unchanged, untouched rows stay pending, and every selected document-stage gets its E1 disposition (cancelled)."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3a request",
          "quote": "Provider retirement keeps E1's meaning."
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnavailableIdentityBatchTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The no-interactions oracle explicitly accounts for the scheduler constructor binding the lifecycle authority fence, then still requires no other coordinator interaction. All unavailable-store admission, retry, row-count, state and delay assertions are unchanged. This fixture is not overlaid for repair fail-before; the original test runs on the unmodified baseline.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3a request",
          "quote": "Conditional publication validates that authority under the coordinator lock, together with the source/stage comparison, and provider transition takes the same lock."
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing ready-service fixtures now explicitly return true from NerService.isAvailable(). All content, extraction, retry, status and publication assertions remain unchanged. The repair checks availability before each unit, including the first.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3a request",
          "quote": "add provider (and NER/disambiguation service) availability where that route has a provider."
        }
      ]
    }
  ]
}
```

## Run-evidence arguments

Use `--before fb9b1ffdc0`. No `--before-patch` is required: the new tests use APIs present at
the base, and the changed long-document test compiles there. Overlay only these test files;
no production compatibility patch is needed. Existing window fixtures model a provider that declares the seam, so they use EmbeddingService mocks on both trees:

- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java`
- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java`
- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java`
- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/WindowedEmbedSliceValidationTest.java`
- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationAuthorityTest.java`

- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentCycleBudgetTest.java`
- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java`
- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java`

Repair check selectors (prefix each with `--check`):

- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java#lateChunkNullFallbackRecordsAndResumesAWindowAfterTheProbeSpentTheDeadline`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#expiredDeadlinePreventsTheFirstUnit`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#hardStopPreventsTheFirstUnitAndCancelsTheSelection`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#sourceInvalidationDuringFetchPreventsTheFirstUnit`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#sourceInvalidationWhileTheSchedulerPacesPreventsTheFirstUnit`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#individualSourceInvalidationDuringFetchPreventsTheFirstUnit`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#exhaustedEmbeddingShareDoesNotForceAFirstDenseUnit`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#interruptionPreventsTheFirstUnitAndCancelsTheSelection`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#individualStageInterruptionPreventsTheFirstUnit`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#hardStopAfterOneUnitPreservesItsResultAndLeavesTheRestCancelled`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#identicalTextWithChangedDenseIdentityRestartsWindows`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#deadlineSpentByNullOrArenaOomClassificationRetainsItsRoute`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#hardStopAfterClassificationRetainsItsRouteWithoutStartingWindows`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#batchOnlyLateParentCompletesWithoutWindowsOrRetry`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java#providerReplacementDuringInferenceRevokesSuccessAndFailure`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java#providerReplacementAtCoordinatorBoundaryRevokesTheComputedOutcome`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java#providerReplacementWhilePublicationWaitsForMutationLockIsAtomic`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/WindowedEmbedSliceValidationTest.java#invalidSliceRestartsWithoutBlendingAnyOfItsVectors`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationAuthorityTest.java#providerReplacementAfterClusteringCannotAcknowledgeOrPublishThePage`

- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#rejectedSlicesUseTheExistingRetryLimit`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java#republishingTheSameProviderRestartsItsRetainedWindows`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#realPendingSpladeAllowsDeferralButNeverWritesThePreservationOnlyUpdate`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#cancelledFallbackPartitionsEverySelectedStageOnce`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#partialWindowsAdvanceWithoutCompletingAnEmbedding`

Preservation selectors (candidate and unmodified base, no repair fail-before claim):

- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java#providerReplacementDuringInferencePreservesExistingRejection`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java#completedPublicationKeepsItsDispositionAcrossLaterReplacement`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java#nullAndEmptySlicesKeepExistingRetryAccounting`
- Existing P1-P5 suites in the assignment, with the authorized long-document and E3a-1 adaptations above. The unmodified baseline suites separately establish preservation before overlaying changed expectations.


## Repair round 1 coverage

`EnrichmentHardStopRepairTest#everyHardStopBoundsEveryRoute` exercises all six stop columns,
before the first unit and after the first unit, for each dense batch (combined/individual),
single-pass, window, chunk batch (combined/individual), sparse parent/chunk
(combined/individual/interleaved), NER (combined/individual), and BGE-M3 parent/chunk route.
Shutdown and hold enter through the route's supplied stop condition; interruption is a real
thread interrupt, source invalidation changes the observed deletion epoch, token revocation uses
the bound production lifecycle, and unavailability uses each route's existing availability seam.
SPLADE and BGE-M3 expose availability by their nullable encoder supplier, rather than an
isAvailable method. The matrix observes calls and writes at the coordinator interface and checks
that no stopped row spends a retry.

The BGE-M3 ops checks supply a live encoder supplier. The two production scheduler paths in
modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/BackfillScheduler.java
currently supply a captured encoder instead. Passing the existing live supplier through those two
arguments is a pending scope decision because that caller is outside the assignment's named files.
Until that wiring changes, BGE-M3 supplier-unavailability cells are verified at the ops boundary,
not through the production scheduler.

`DisambiguationAuthorityTest#hardStopsBoundEveryDisambiguationPage` covers all five applicable
columns before clustering and after clustering, including the final page. The source column is
not applicable. It asserts that no retired/stopped page is acknowledged. The service's own
publication callback also receives the same availability-aware stop predicate.

`EnrichmentHardStopRepairTest#retirementDefersSuccessFailureAndCancellationOnEveryRoute`
checks both forms of retirement for all inference routes and all three returned outcomes;
window calls return their final slice. The real-writer checks in EnrichmentAuthorityRaceTest
also assert persisted PENDING status, unchanged retry counts and no searchable retired sparse
terms. The existing replacement-boundary/mutation-lock races and P1-P5 suites remain selected.

Counterexample mapping: F1 -> individualNerUnavailabilityBoundsEveryDocument;
F2 -> successfulFinalDenseCallCannotPublishAfterUnavailability and
unavailableProviderCannotPublishItsSuccessfulFinalDenseResult (real writer);
F3 -> sparseRetirementWinsOverCancellation and
replacedProviderCancellationStaysDeferredThroughTheRealWriter;
F4 -> bgeSourceInvalidationAndInterruptionPreventTheFirstBatch.
`bgeFallbackCannotStartAnotherUnitAfterAHardStop` exercises the next-unit fence after a failed
unified batch, for both parent and chunk routes and every stop column.

Additional overlays for the existing --before fb9b1ffdc0 arguments:

- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java`
- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerBackfillOpsTest.java`
- `--overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java`

Additional repair selectors (prefix each with `--check`):

- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#individualNerUnavailabilityBoundsEveryDocument`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#successfulFinalDenseCallCannotPublishAfterUnavailability`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#sparseRetirementWinsOverCancellation`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#bgeSourceInvalidationAndInterruptionPreventTheFirstBatch`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#bgeRetirementPreventsTheFirstBatch`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#combinedNerRejectsEveryOutcomeWhenTheServiceBecomesUnavailable`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#sparseUnavailabilityRejectsComputedSuccessAndCancellation`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#individualDenseFallbackUnavailabilityWinsOverCancellation`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#individualDenseBatchUnavailabilityWinsOverCancellation`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#bgeFallbackCannotStartAnotherUnitAfterAHardStop`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java#unavailableProviderCannotPublishItsSuccessfulFinalDenseResult`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java#replacedProviderCancellationStaysDeferredThroughTheRealWriter`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationAuthorityTest.java#serviceUnavailabilityCannotPublishOrAcknowledgeThePage`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java#retiredBgeCannotPublishAnyFinalOutcomeThroughTheRealWriter`

Additional coverage selectors (not an all-parameters fail-before claim):

- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#everyHardStopBoundsEveryRoute`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#retirementDefersSuccessFailureAndCancellationOnEveryRoute`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java#fallbackRetirementDefersEveryOutcome`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationAuthorityTest.java#hardStopsBoundEveryDisambiguationPage`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java#unavailableProviderDefersFailureAndCancellationThroughTheRealWriter`

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a1dc-b298-7523-88ea-cdba89e5a361",
  "verdict": "accept",
  "digest": "sha256:2827deb476c669533d218386306d8b6467c7ff17650874152f0b9b39d00968de",
  "note": "Independent Codex verifier on 8eeb8c101b: entries 1-7 and the five exempt files accepted; 38 repair checks fail before by assertion and pass after; focused run 934 checks per tree."
}
```
