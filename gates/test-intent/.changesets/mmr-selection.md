---
schema: test-intent.v1
task: mmr-selection
author-role: builder
author-session: mmr-builder-1-native-source
---

# MMR selection checks

Additive checks only: all pre-existing tests, assertions and fixtures remain.
The original owner delegation quoted below was supplied verbatim in the builder packet;
the detailed expectations are explicitly labelled agent choices under that delegation.
All six delegation locations name the original root Codex thread
`01a17843-527b-7fa2-9889-2013c4de6b4b`, user turn 2026-10-26, whose latest instruction
is quoted verbatim. The settled agent design is not cited as owner words or pre-existing
repository authority. Delegation scope is unchanged; quality projection B remains held.

Execution evidence and independent acceptance are PENDING. The referenced D4 files do not
exist yet and no test execution/pass is claimed. The root native executor owns all Gradle,
baseline and D4 runs; see `tmp/mmr/native-check-request.md`.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexing/src/test/java/io/justsearch/indexing/rag/MmrSelectorTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "S1/S6/P2: stable descending sanitized relevance at lambda 1 and nearby values, retaining the greedy novelty term below 1; existing input/immutability checks are additive.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "root Codex thread 01a17843-527b-7fa2-9889-2013c4de6b4b, user turn 2026-10-26, latest instruction: proceed accordingly, only use codex agents",
          "delegationScope": "Proceed with the independent A1 selection implementation under the settled design and scenarios. A-control and quality projection B remain held; no new outward control contract, configuration, lifecycle or stored-state change.",
          "choice": "S1/S6/P2: stable descending sanitized relevance at lambda 1 and nearby values, retaining the greedy novelty term below 1; existing input/immutability checks are additive. These are agent-selected checks of the settled design, not quotes from the owner or a pre-existing repository authority.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/mmr-selection-repairs.json",
      "newChecks": [
        "modules/indexing/src/test/java/io/justsearch/indexing/rag/MmrSelectorTest.java#relevanceEndpointRanksUnsortedInputs",
        "modules/indexing/src/test/java/io/justsearch/indexing/rag/MmrSelectorTest.java#belowOneRetainsNoveltyEvenNearEndpoint",
        "modules/indexing/src/test/java/io/justsearch/indexing/rag/MmrSelectorTest.java#sanitizesRelevanceAndKeepsOriginalIndexForTies",
        "modules/indexing/src/test/java/io/justsearch/indexing/rag/MmrSelectorTest.java#preservesInputShapeContractsAndDoesNotMutateInputs"
      ]
    },
    {
      "items": [
        "modules/indexing/src/test/java/io/justsearch/indexing/rag/MmrSelectorTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "S6/P2: preserve positive-part cosine, zero-lambda original-index seed, small positive lambda, and valid cosine range at float extremes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "root Codex thread 01a17843-527b-7fa2-9889-2013c4de6b4b, user turn 2026-10-26, latest instruction: proceed accordingly, only use codex agents",
          "delegationScope": "Proceed with the independent A1 selection implementation under the settled design and scenarios. A-control and quality projection B remain held; no new outward control contract, configuration, lifecycle or stored-state change.",
          "choice": "S6/P2: preserve positive-part cosine, zero-lambda original-index seed, small positive lambda, and valid cosine range at float extremes. These are agent-selected checks of the settled design, not quotes from the owner or a pre-existing repository authority.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/mmr-selection-preservation.json",
      "newChecks": [
        "modules/indexing/src/test/java/io/justsearch/indexing/rag/MmrSelectorTest.java#negativeCosineDoesNotBecomeANoveltyReward",
        "modules/indexing/src/test/java/io/justsearch/indexing/rag/MmrSelectorTest.java#validCosinesStayInRangeAtFloatExtremes"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "S1-S5/S8/P6: filter blanks before capping; reject unusable query and passage vectors coherently; require the complete capped prefix; use the full usable text pool for every fallback; rank an all-valid exactly-K prefix; expiry polling is nonthrowing and FALLBACK-only.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "root Codex thread 01a17843-527b-7fa2-9889-2013c4de6b4b, user turn 2026-10-26, latest instruction: proceed accordingly, only use codex agents",
          "delegationScope": "Proceed with the independent A1 selection implementation under the settled design and scenarios. A-control and quality projection B remain held; no new outward control contract, configuration, lifecycle or stored-state change.",
          "choice": "S1-S5/S8/P6: filter blanks before capping; reject unusable query and passage vectors coherently; require the complete capped prefix; use the full usable text pool for every fallback; rank an all-valid exactly-K prefix; expiry polling is nonthrowing and FALLBACK-only. These are agent-selected checks of the settled design, not quotes from the owner or a pre-existing repository authority.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/mmr-selection-repairs.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#anyFailureUsesPositionEvenAfterKOrKPlusOneSuccesses",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#invalidCandidateAtAnyPositionUsesTextPool",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#invalidSuppliedQueryDoesNotEncodeAnything",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#unusableAcquiredQueryStopsBeforePassageInference",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#positiveDeclaredDimensionMustMatchTheQuery",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#validNegativeCosinesRemainUsableSemanticCandidates",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#finiteNonzeroVectorsAtFloatExtremesAreUsable",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#blanksAreRemovedBeforeTheExpensiveCap",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#anAllValidPrefixOfExactlyKIsRankedAndDoesNotTopUpFromItsTail",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#everyFallbackUsesTheFullNonblankPoolBeyondTheCap",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#emptyOrNoSelectionNeededRemovesBlanksWithoutInference",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#normallyReturningProviderCannotStartMoreWorkAfterSemanticExpiry"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "S4-S6/S8/P1/P2/P6: preserve valid supplied-query reuse and one absent-query acquisition, nonpositive undeclared provider dimension, original passage identity and metadata, position/unknown/default mode behavior, and the absence of newly throwing authority checks outside a FALLBACK leg.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "root Codex thread 01a17843-527b-7fa2-9889-2013c4de6b4b, user turn 2026-10-26, latest instruction: proceed accordingly, only use codex agents",
          "delegationScope": "Proceed with the independent A1 selection implementation under the settled design and scenarios. A-control and quality projection B remain held; no new outward control contract, configuration, lifecycle or stored-state change.",
          "choice": "S4-S6/S8/P1/P2/P6: preserve valid supplied-query reuse and one absent-query acquisition, nonpositive undeclared provider dimension, original passage identity and metadata, position/unknown/default mode behavior, and the absence of newly throwing authority checks outside a FALLBACK leg. These are agent-selected checks of the settled design, not quotes from the owner or a pre-existing repository authority.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/mmr-selection-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#validQueryIsReusedOrAcquiredExactlyOnce",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#nonpositiveProviderDimensionIsUndeclared",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#distinctRealAndSyntheticPassagesKeepTheirOriginalIdentityAndMetadata",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#positionAndUnknownModesKeepTheirExistingBlankRowsWithoutInference",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java#newExpiryPollingDoesNotAddThrowingAuthorityChecksOutsideFallback"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "S2/S3/S4/P5: through WorkerSearchService.retrieveContext over a real index, partial or invalid passage embeddings alone do not underfill a three-section context; normal assembly keeps literal excerpts, spans, sections and citations aligned.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "root Codex thread 01a17843-527b-7fa2-9889-2013c4de6b4b, user turn 2026-10-26, latest instruction: proceed accordingly, only use codex agents",
          "delegationScope": "Proceed with the independent A1 selection implementation under the settled design and scenarios. A-control and quality projection B remain held; no new outward control contract, configuration, lifecycle or stored-state change.",
          "choice": "S2/S3/S4/P5: through WorkerSearchService.retrieveContext over a real index, partial or invalid passage embeddings alone do not underfill a three-section context; normal assembly keeps literal excerpts, spans, sections and citations aligned. These are agent-selected checks of the settled design, not quotes from the owner or a pre-existing repository authority.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/mmr-selection-repairs.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#partialMmrEmbeddingsKeepAFullWorkerContext"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "S4/S5/S8/P5/P6: preserve the bounded ordinary dense-query retry, supplied query reuse, real/synthetic same-parent passage spans through selection and assembly (including exact literal source/section linkage and the established citation preview of up to 240 source characters plus a truncation ellipsis), and the worker parent cancellation postcheck when mocked request-aware providers return normally. These are worker-boundary expectations, not an outward cancellation-fix claim.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "root Codex thread 01a17843-527b-7fa2-9889-2013c4de6b4b, user turn 2026-10-26, latest instruction: proceed accordingly, only use codex agents",
          "delegationScope": "Proceed with the independent A1 selection implementation under the settled design and scenarios. A-control and quality projection B remain held; no new outward control contract, configuration, lifecycle or stored-state change.",
          "choice": "S4/S5/S8/P5/P6: preserve the bounded ordinary dense-query retry, supplied query reuse, real/synthetic same-parent passage spans through selection and assembly (including exact literal source/section linkage and the established citation preview of up to 240 source characters plus a truncation ellipsis), and the worker parent cancellation postcheck when mocked request-aware providers return normally. These are worker-boundary expectations, not an outward cancellation-fix claim. These are agent-selected checks of the settled design, not quotes from the owner or a pre-existing repository authority.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/mmr-selection-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#denseQueryFailureAllowsOnlyTheExistingOneMmrRetry",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#workerParentCheckStillObservesCancellationAfterNormallyReturningProviders",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#realAndSameParentSyntheticPassagesKeepTheirSpansThroughMmrAndAssembly"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance after native execution evidence is available.
