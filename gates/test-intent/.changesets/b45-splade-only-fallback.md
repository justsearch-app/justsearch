---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b45-splade-only-20261009
---

# SPLADE-only skip fallback

The existing tests and expectations are unchanged. New checks enter the real worker with a
nonblank SPLADE-only request; HTTP and MCP checks also enter the production preset mapper.
Only native encoders or the client transport are replaced. No Engine or parser subprocess boots.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOnlyBoundaryFallbackTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "SPLADE-only queued semantic expiry returns keyword hits, SEMANTIC_BUDGET_EXCEEDED/contended, no encoder call and no models wait, for bound and starting encoders, deprecated worker mode, bare HTTP mode and bare MCP mode. MCP names the SPLADE budget miss and cause in its degradation note. Timely loading retains loading; null encoders and encoding failures retain the existing NO_EMBEDDING_SERVICE and EMBEDDING_GENERATION_FAILED producers with no false budget cause, while returning keyword hits. Each of these nine checks fails by assertion on 9fde46683 for the lost reason or note and passes on the candidate.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 agreement",
          "quote": "a search that starts after its semantic deadline has passed in the queue takes the existing Spec D outcome at once without running inference: hybrid or SPLADE searches answer keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 request",
          "quote": "If the existing behaviour for other SPLADE-only skips also drops results and reason, show it and fix them by the same rule only where the existing reason producers already define the outcome; do not invent new reason codes."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 request",
          "quote": "with the existing producers and fields (splade_skip_reason / splade_skip_cause, retrieval_mode_reason, McpToolSurface degradation note): keyword-only results with SEMANTIC_BUDGET_EXCEEDED and cause contended, and no encoder call."
        }
      ],
      "evidence": "gates/test-intent/evidence/b45-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#queuedSpladeOnlyWithBoundEncoderReturnsKeywordsAndContendedReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#queuedSpladeOnlyWithStartingEncoderReturnsKeywordsWithoutWaiting",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#queuedDeprecatedSpladeModeReturnsKeywordsAndContendedReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#timelySpladeOnlyLoadingReturnsKeywordsAndLoadingReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#absentSpladeOnlyReturnsKeywordsWithItsExistingNoServiceReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#queuedPermanentlyAbsentSpladeRetainsNoServiceReasonWithoutFalseBudgetCause",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#unavailableSpladeEncoderReturnsKeywordsWithItsExistingGenerationFailure",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOnlyBoundaryFallbackTest.java#bareHttpSpladeModeReturnsKeywordHitsWithBudgetAndContention",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOnlyBoundaryFallbackTest.java#bareMcpSpladeModeReturnsKeywordHitsAndDegradationNote"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation controls pass before and after: sparse plus SPLADE queued expiry, successful SPLADE encoding, successful empty weights, and blank query. Successful SPLADE retains the existing TEXT effective-mode label, the SPLADE-only plan and no sparse retrieval. Empty weights do not add keyword fallback. No existing assertion or fixture is changed.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 request",
          "quote": "Preserve: SPLADE plus sparse, hybrid, AUTO, VECTOR typed 504, RAG, timely-seat loading cause, a permanently absent SPLADE model, and SPLADE searches whose deadline has not expired."
        }
      ],
      "evidence": "gates/test-intent/evidence/b45-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#sparsePlusSpladePreservesQueuedFallback",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#timelySpladeOnlyStillRunsOnlySplade",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#successfulEmptySpladeWeightsDoNotBecomeAKeywordFallback",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#blankQueryStillHasTheEmptyQueryDecision"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance. The builder cannot accept its own checks.
