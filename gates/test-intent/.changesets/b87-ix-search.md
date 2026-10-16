---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b87-ix-search
---

# Test-intent entries

Design choices are delegated choices, not owner-authored requirements. Independent acceptance remains with the verifier.

Named limit: synchronous VECTOR-only session creation remains permitted because that request has no lexical fallback. Native construction cannot be interrupted by its caller deadline. General asynchronous session construction remains open; default hybrid/MCP search and Ask use prewarmed sessions. See docs/reference/contracts/search-and-rag-reason-codes.md.

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
      "behaviour": "A cancelled search promptly leaves the models-ready wait. Unified BGE-M3 semantic-budget failures retain their original dense and sparse reason/cause while lexical hits remain available. Build cleanup uses unnamed lifetime-only resources in WorkerSearchServiceSemanticBudgetTest, retaining original inputs, resource lifetimes and every existing assertion.",
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
      "evidence": "gates/test-intent/evidence/b6d536-build-green-61ff9b955-b3b625c872.json",
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
      "behaviour": "Internal RAG reranking returns available lexical context by the retrieval request deadline, with native ownership retained until execution returns. Its local budget is a pre-inference check. BM25 MMR uses its semantic budget and position diversification on expiry. Empty keyword fallback retains the semantic reason and cause. The held-run fixture waits through the request deadline, rather than requiring the earlier local-budget cutoff. Build cleanup uses unnamed lifetime-only resources in WorkerSearchServiceRagBudgetTest, retaining original inputs, resource lifetimes and every existing assertion.",
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
      "evidence": "gates/test-intent/evidence/b6d536-build-green-61ff9b955-b3b625c872.json",
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
      "class": "Adaptation",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "The four existing exact-one-wait oracles now count models-ready supplier entries (logical waits), independently of 10 ms latch slices. A second logical wait is caught again. Loading fallback, no encoding, recovery and zero-await queued expiry assertions are unchanged. All four pass on 61ff9b955, 8bde08e9a and the candidate."
        }
      ],
      "evidence": "gates/test-intent/evidence/b87-ix-search-r3-waits.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#timelyHybridWithUnavailableProviderKeepsLoadingCause",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#timelyAutoWithUnavailableProviderKeepsLoadingCause",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#timelySpladeOnlyLoadingReturnsKeywordsAndLoadingReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedLoadingTest.java#seatedHybridWithModelsLoadingKeepsLoadingCause"
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The four existing exact-one-wait oracles now count models-ready supplier entries (logical waits), independently of 10 ms latch slices. A second logical wait is caught again. Loading fallback, no encoding, recovery and zero-await queued expiry assertions are unchanged. All four pass on 61ff9b955, 8bde08e9a and the candidate."
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
      "evidence": "gates/test-intent/evidence/b87-ix-search-r3-r2-record.json",
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
      "behaviour": "A failed BGE-M3 encoder does not replace an explicitly supplied vector at capture, including a vector whose length differs from the index dimension. Explicit vectors remain successes at this boundary; their downstream validation stays authoritative. Build cleanup uses unnamed lifetime-only resources in WorkerSearchServiceSemanticBudgetTest, retaining original inputs, resource lifetimes and every existing assertion.",
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
      "evidence": "gates/test-intent/evidence/b6d536-build-green-ac6820757-123a03a4ec.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#aFailedBgeEncoderDoesNotReplaceAnExplicitVector"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRerankSessionCreationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerPrewarmSelectionTest.java"
      ],
      "class": "New",
      "behaviour": "Default hybrid/MCP search, Ask retrieval/rerank and citation matching use existing native sessions and never create or repair sessions on request threads, including short deadlines without a semantic sub-budget. Prewarm owns cold creation and GPU retry for separately leased query roles; a successful retry lets the next request score on the ready GPU. A cold reranker skips with MODEL_LOADING, preserving fused results.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Default hybrid/MCP search, Ask retrieval/rerank and citation matching use existing native sessions and never create or repair sessions on request threads, including short deadlines without a semantic sub-budget. Prewarm owns cold creation and GPU retry for separately leased query roles; a successful retry lets the next request score on the ready GPU. A cold reranker skips with MODEL_LOADING, preserving fused results."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r3-java.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRerankSessionCreationTest.java#requestRerankDoesNotRetryGpuOrRefreshItsFailure",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRerankSessionCreationTest.java#coldRequestRerankSkipsWithLoadingWithoutCreatingAnySession",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRerankSessionCreationTest.java#askCitationAcquisitionDoesNotCreateOrRetrySessions",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRerankSessionCreationTest.java#shortDeadlineFallbackQueriesStillCannotCreateSessions",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerPrewarmSelectionTest.java#prewarmRetriesTheServingRerankerAndTheNextRequestUsesItsGpu",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerPrewarmSelectionTest.java#prewarmIncludesAnIndependentlyOwnedColdCitationScorer"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/InteractionThreadControllerTest.java"
      ],
      "class": "New",
      "behaviour": "A terminal Ask error following retrieval metadata records its clean question, error and optional mode/reason/cause. The canonical thread projects the error and metadata, so reload preserves the live explanation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "A terminal Ask error following retrieval metadata records its clean question, error and optional mode/reason/cause. The canonical thread projects the error and metadata, so reload preserves the live explanation."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r3-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#terminalRetrievalErrorIsRecordedWithItsQuestionAndExplanation",
        "modules/ui/src/test/java/io/justsearch/ui/api/InteractionThreadControllerTest.java#recordedTerminalRetrievalErrorReachesTheThreadAsAnErrorWithMetadata",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#failedAnswerGenerationRecordsTheRetrievalExplanationAndError"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeRunAbandonmentReasonTest.java"
      ],
      "class": "New",
      "behaviour": "A run abandoned before native entry reports that state and releases its lease; only a run actually executing continues detached.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "A run abandoned before native entry reports that state and releases its lease; only a run actually executing continues detached."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r3-java.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeRunAbandonmentReasonTest.java#pendingAbandonmentSaysNativeWorkNeverEntered"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/CrossEncoderSkipReasonTest.java"
      ],
      "class": "New",
      "behaviour": "A cold reranker reports MODEL_LOADING as a ranking degradation, rather than a deadline miss, inference failure or unknown reason.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "A cold reranker reports MODEL_LOADING as a ranking degradation, rather than a deadline miss, inference failure or unknown reason."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r3-java.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/CrossEncoderSkipReasonTest.java#aColdRerankIsWordedAsLoadingRatherThanUnknown"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/RerankLoadingReason.test.ts",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts",
        "modules/ui-web/src/shell-v0/views/AskTerminalReload.test.ts"
      ],
      "class": "New",
      "behaviour": "Reloaded Search Ask and Unified Chat show the question, terminal retrieval error and explanation from the record. Document fallback retains its document-too-small wording. MODEL_LOADING is worded consistently with the closed rerank degradation vocabulary.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Reloaded Search Ask and Unified Chat show the question, terminal retrieval error and explanation from the record. Document fallback retains its document-too-small wording. MODEL_LOADING is worded consistently with the closed rerank degradation vocabulary."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r3-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/AskTerminalReload.test.ts#reloaded Search Ask keeps the terminal error and its retrieval explanation",
        "modules/ui-web/src/shell-v0/views/AskTerminalReload.test.ts#reloaded Unified Chat keeps the terminal error and its retrieval explanation",
        "modules/ui-web/src/shell-v0/views/AskTerminalReload.test.ts#document fallback still explains why chunk retrieval was unavailable",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/RerankLoadingReason.test.ts#a loading reranker is explained without a raw reason code",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts#F-052 — CROSS_ENCODER_SKIP_WORDING covers the drop-class vocabulary exactly"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionCreationRaceTest.java"
      ],
      "class": "New",
      "behaviour": "Native CPU ownership rechecks construction permission under its owner lock when readiness disappears. A request without creation permission reports LOADING without constructing a replacement. This alleged additional race was refuted on 8bde08e9a; the check preserves its existing guard.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Design and implement search contention, semantic budgets, retrieval degradation and native ownership repairs for owner items 5 and 6.",
          "label": "agent choice",
          "choice": "Native CPU ownership rechecks construction permission under its owner lock when readiness disappears. A request without creation permission reports LOADING without constructing a replacement. This alleged additional race was refuted on 8bde08e9a; the check preserves its existing guard."
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/b87-ix-search-r3-java.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionCreationRaceTest.java#losingTheReadyCpuSessionCannotReenableConstruction"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-search-20261014",
  "verdict": "accept",
  "digest": "sha256:76690f2d64736720361e20a009d5f22f7d1f51c2d84d312dd90e8a40d0c0c32a",
  "note": "VC-search (independent Claude verifier) over three rounds on 6aee4817e vs 61ff9b955 (rejected 8bde08e9a for synchronous GPU session creation on the request-path rerank): its own blocking check fails on 8bde08e9a and passes; default hybrid (dense on/auto) and Ask (position, MMR) at 3000 and 600 ms never permit session creation (600 ms cases fail on 8bde08e9a); cold rerank skips with loading, prewarm covers reranker and citation scorer; terminal-error Ask keeps question, error and retrieval explanation through reload and backend restarts (live, AI_OFFLINE); wording and logical-wait checks restored. Live on the dev stack and GPU: first cold hybrid search 14.4 s under the 15 s worker deadline with semantic leg skipped for loading, later searches 0.05-0.27 s, every native session created by startup or prewarm and none by a request thread; warm MCP search 0.50 s. Suites show only baseline failures; every ArchUnit class passes; PMD no additions in 9 modules; frontend 6858 and typecheck pass. Named limit: VECTOR-only requests still create sessions synchronously. Unverified: cold MCP search this round, idle p95. Digest bound with --base 61ff9b955, the lane's base."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "accept",
  "digest": "sha256:27d361dca5b4165f7e0b47150018d2fc943fccd3b57a43fc4629613730632242",
  "note": "re-bound to main; no change since 8063e3bbc"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5b-20261016",
  "verdict": "reject",
  "digest": "sha256:95dd5f3f21dd802ec759e23f4a399bf57977f6b3b32cc6f30d1f484ae72ff165",
  "note": "REJECT under the mandatory before-patch rule: b87-dispatch-fixture.patch rewrites executed DetachableRun.runWithin statements at 61ff9b955, replacing the original run body with delegation and the RUN_THREADS factory with an injected factory. NativeSessionHandleDetachedRunTest.abandonedPendingDispatchDoesNotStartNativeWork reaches the patched overload; this is beyond add-only declarations, a stated abstract-to-default change, or an unreached neutral stub."
}
```
