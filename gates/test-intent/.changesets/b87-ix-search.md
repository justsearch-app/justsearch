---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b87-ix-search
---

# Test-intent entries

Design choices are delegated choices, not owner-authored requirements. Independent acceptance remains with the verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDetachedRunTest.java"
      ],
      "class": "New",
      "behaviour": "Expired or abandoned, undispatched queries must not enter native inference; copied inputs and the native lease/seat are released once. Already executing native calls retain ownership until they finish.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Expired or abandoned, undispatched queries must not enter native inference; copied inputs and the native lease/seat are released once. Already executing native calls retain ownership until they finish."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-java.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDetachedRunTest.java#expiredRunDeadlineDoesNotStartNativeWork",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDetachedRunTest.java#abandonedPendingDispatchDoesNotStartNativeWork"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryLegCreationTest.java"
      ],
      "class": "New",
      "behaviour": "A fallback-capable query encountering a failed CPU session returns LOADING without running native teardown or repair on the request thread.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "A fallback-capable query encountering a failed CPU session returns LOADING without running native teardown or repair on the request thread."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-java.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryLegCreationTest.java#aFallbackQueryDoesNotCloseAFailedCpuSession"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "A cancelled search promptly leaves the models-ready wait. Unified BGE-M3 semantic-budget failures retain their original dense and sparse reason/cause while lexical hits remain available.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "A cancelled search promptly leaves the models-ready wait. Unified BGE-M3 semantic-budget failures retain their original dense and sparse reason/cause while lexical hits remain available."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-worker.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#cancelledSearchLeavesTheModelsReadyWaitPromptly",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#unifiedEncoderBudgetFailureSurvivesBothDerivedLegs",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#sparseOnlyUnifiedEncoderRetainsItsBudgetCause"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "Internal RAG reranking returns available lexical context by the retrieval request deadline, with native ownership retained until execution returns. Its local budget is a pre-inference check. BM25 MMR uses its semantic budget and position diversification on expiry. Empty keyword fallback retains the semantic reason and cause. The held-run fixture waits through the request deadline, rather than requiring the earlier local-budget cutoff.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Internal RAG reranking returns available lexical context by the retrieval request deadline, with native ownership retained until execution returns. Its local budget is a pre-inference check. BM25 MMR uses its semantic budget and position diversification on expiry. Empty keyword fallback retains the semantic reason and cause. The held-run fixture waits through the request deadline, rather than requiring the earlier local-budget cutoff."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-worker.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#internalRerankReleasesRetrievalWhileNativeRunIsHeld",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#emptyKeywordFallbackRetainsSemanticLoadingReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#bm25MmrReleasesBlockedQuestionEncode",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#bm25MmrReleasesBlockedPassageEncode"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPrewarmTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/QueryPrewarmFixture.java"
      ],
      "class": "New",
      "behaviour": "Periodic query prewarm retries a GPU when the native owner says retry is due, even while CPU queries remain ready; hybrid traffic need not create a GPU session. The fixture adds a CPU-ready recoverable GPU state without changing existing fixture outcomes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Periodic query prewarm retries a GPU when the native owner says retry is due, even while CPU queries remain ready; hybrid traffic need not create a GPU session. The fixture adds a CPU-ready recoverable GPU state without changing existing fixture outcomes."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-java.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPrewarmTest.java#cpuReadyEncoderRetriesGpuWithoutIndexingOrVectorOnlyQueries"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGContextTest.java"
      ],
      "class": "New",
      "behaviour": "An empty unscoped Ask emits retrieval metadata with the original semantic failure reason and cause before its existing NO_CONTENT terminal error.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "An empty unscoped Ask emits retrieval metadata with the original semantic failure reason and cause before its existing NO_CONTENT terminal error."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGContextTest.java#emptySemanticFallbackEmitsItsMetadataBeforeTheTerminalError"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/SearchPerSourceCauseMergeTest.java"
      ],
      "class": "New",
      "behaviour": "A contributing unfiltered backfill retains its strongest semantic fallback cause and paired reason in the merged response without double-counting hits. A failed backfill still leaves the original hits and trace available.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "A contributing unfiltered backfill retains its strongest semantic fallback cause and paired reason in the merged response without double-counting hits. A failed backfill still leaves the original hits and trace available."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/SearchPerSourceCauseMergeTest.java#contributingBackfillKeepsItsStrongestCause"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRerankTest.java"
      ],
      "class": "New",
      "behaviour": "Rerank acquisition ends no later than the native result deadline, reserving response time inside the original caller deadline while retaining live cancellation and urgency. The old exact-equality acquisition assertion is replaced with the bounded response-margin obligation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Rerank acquisition ends no later than the native result deadline, reserving response time inside the original caller deadline while retaining live cancellation and urgency. The old exact-equality acquisition assertion is replaced with the bounded response-margin obligation."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-worker.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRerankTest.java#realRerankerPassesLiveCancellationAndEarlierDeadlineToNativeAcquisition"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.test.ts"
      ],
      "class": "New",
      "behaviour": "Ask retains the retrieval fallback reason and cause across metadata, citations and completion; Search Ask displays the existing semantic timeout and cause wording without opening sources, including empty terminal errors; Unified Chat displays it in its retrieval preamble.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Ask retains the retrieval fallback reason and cause across metadata, citations and completion; Search Ask displays the existing semantic timeout and cause wording without opening sources, including empty terminal errors; Unified Chat displays it in its retrieval preamble."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.test.ts#keeps the retrieval fallback reason through citations and completion"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/AskFallbackReason.test.ts"
      ],
      "class": "New",
      "behaviour": "Ask retains the retrieval fallback reason and cause across metadata, citations and completion; Search Ask displays the existing semantic timeout and cause wording without opening sources, including empty terminal errors; Unified Chat displays it in its retrieval preamble.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Ask retains the retrieval fallback reason and cause across metadata, citations and completion; Search Ask displays the existing semantic timeout and cause wording without opening sources, including empty terminal errors; Unified Chat displays it in its retrieval preamble."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/AskFallbackReason.test.ts#Unified Chat includes the fallback cause in its retrieval preamble",
        "modules/ui-web/src/shell-v0/views/AskFallbackReason.test.ts#Search Ask shows its fallback cause without opening sources",
        "modules/ui-web/src/shell-v0/views/AskFallbackReason.test.ts#Search Ask keeps its fallback cause on an empty terminal error"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedLoadingTest.java"
      ],
      "class": "New",
      "behaviour": "A timely request must wait for loading models and retain its loading fallback, zero encoding and recovery outcomes. Cancellation-aware waiting may use several bounded latch awaits, so the former exact-one-await implementation assertion becomes a nonzero-wait assertion. Queued expiry still requires zero awaits.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "A timely request must wait for loading models and retain its loading fallback, zero encoding and recovery outcomes. Cancellation-aware waiting may use several bounded latch awaits, so the former exact-one-await implementation assertion becomes a nonzero-wait assertion. Queued expiry still requires zero awaits."
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/b87-ix-search-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#timelyHybridWithUnavailableProviderKeepsLoadingCause",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#timelyAutoWithUnavailableProviderKeepsLoadingCause",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#timelySpladeOnlyLoadingReturnsKeywordsAndLoadingReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedLoadingTest.java#seatedHybridWithModelsLoadingKeepsLoadingCause"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRerankTest.java"
      ],
      "class": "New",
      "behaviour": "A native rerank slower than its 200ms pre-inference budget keeps its scores inside the search deadline. Acquisition is bounded by its budget and the caller response deadline; the wire skip reason names the bound that actually expired.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "A native rerank slower than its 200ms pre-inference budget keeps its scores inside the search deadline. Acquisition is bounded by its budget and the caller response deadline; the wire skip reason names the bound that actually expired."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r2-java.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRerankTest.java#aRunSlowerThanItsBudgetKeepsScoresInsideTheSearchDeadline",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRerankTest.java#acquisitionExpiryNamesTheResponseDeadlineWhenItBoundsTheWait",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRerankTest.java#nativeEntryAfterTheAcquireBudgetExpiresKeepsItsBudgetReason"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/InteractionThreadControllerTest.java"
      ],
      "class": "New",
      "behaviour": "Completed retrieval answers persist optional retrieval mode, reason and cause from their rag.meta event, and the canonical thread projection carries those fields for reload without inventing absent metadata.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Completed retrieval answers persist optional retrieval mode, reason and cause from their rag.meta event, and the canonical thread projection carries those fields for reload without inventing absent metadata."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r2-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#retrievalExplanationIsRecordedWithTheCompletedAnswer",
        "modules/ui/src/test/java/io/justsearch/ui/api/InteractionThreadControllerTest.java#recordedRetrievalExplanationReachesTheThreadResponse"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/AskFallbackStream.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-retrieval-reload.test.ts"
      ],
      "class": "New",
      "behaviour": "Unified Chat shows retrieval fallback explanations after the real stream completes or terminates with an error, and on its canonical recorded answer. Search Ask reload reads optional reason/cause fields and replaces empty live values with recorded values.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Unified Chat shows retrieval fallback explanations after the real stream completes or terminates with an error, and on its canonical recorded answer. Search Ask reload reads optional reason/cause fields and replaces empty live values with recorded values."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r2-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/AskFallbackStream.test.ts#Unified Chat keeps the retrieval explanation after the stream completes",
        "modules/ui-web/src/shell-v0/views/AskFallbackStream.test.ts#Unified Chat shows the retrieval explanation beside a terminal error",
        "modules/ui-web/src/shell-v0/views/AskFallbackStream.test.ts#Unified Chat renders the retrieval explanation from a recorded answer",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts#reloads the recorded retrieval reason and cause as additive evidence",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-retrieval-reload.test.ts#reload fills empty live retrieval explanations from the record"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-retrieval-reload.test.ts"
      ],
      "class": "New",
      "behaviour": "Legacy records without retrieval metadata retain the same evidence object and empty retrieval mode.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Legacy records without retrieval metadata retain the same evidence object and empty retrieval mode."
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r2-ui-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-retrieval-reload.test.ts#records without retrieval metadata retain their previous evidence"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "A failed BGE-M3 encoder does not replace an explicitly supplied vector at capture, including a vector whose length differs from the index dimension. Explicit vectors remain successes at this boundary; their downstream validation stays authoritative.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "A failed BGE-M3 encoder does not replace an explicitly supplied vector at capture, including a vector whose length differs from the index dimension. Explicit vectors remain successes at this boundary; their downstream validation stays authoritative."
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r2-java.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#aFailedBgeEncoderDoesNotReplaceAnExplicitVector"
      ]
    }
  ]
}
```

## Acceptance records
