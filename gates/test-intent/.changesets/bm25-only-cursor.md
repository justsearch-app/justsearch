---
schema: test-intent.v1
task: qc-cursor
author-role: builder
author-session: 01a17a20-a830-71b1-950d-d350b4408a7e
---

# BM25-only relevance cursor

These expectations are delegated technical choices under the quoted owner delegation; the orchestrator supplied the bounded scenarios and design. No independent acceptance is supplied by this author.

The execution-evidence generator runs in an isolated shared clone with byte-identical candidate production and test files. Its scratch copy adds --no-daemon to every Gradle command and uses retained shared clones for before-states, as required by the assignment. The generator writes the evidence and records its actual script digest; evidence JSON is never hand-edited. The slot wrapper encloses each generator invocation. Launcher adaptations, before/after logs and preservation results remain in tmp/agent-tmp/cursor-builder/.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/app/engine/Bm25OnlyCursorHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Real HTTP/Head/Engine/Worker/Lucene fallback walks 30, 41, 20 and 21 tied short parents with visible and actual Worker retrieval limit 20, ready reranker metadata/topK 20, exact ID union, no repeats/gaps, stable totals and cursor termination. Malformed, sort-incompatible and missing-document cursors give HTTP 400 CURSOR_INVALID just as TEXT does.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task qc-cursor request",
          "delegationScope": "Autonomous investigation and design of query classification fixes; the subsequent owner instruction \"proceed accordingly\" authorizes proceeding with the bounded BM25-only cursor repair assigned by the orchestrator.",
          "choice": "Forward the request relevance cursor only when the planner realizes BM25-only retrieval, through a cursor-bearing TextQueryOps overload that reuses ReadPathOps; preserve existing parser, filters, boosts, fuzzy policy, Head AI eligibility, v1 codec and fused paging behavior.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/bm25-only-cursor-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/app/engine/Bm25OnlyCursorHttpTest.java#hybridFallbackWalksEveryParentOnce",
        "modules/ui/src/test/java/io/justsearch/app/engine/Bm25OnlyCursorHttpTest.java#fallbackRejectsInvalidCursorLikeText"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/app/engine/Bm25OnlyCursorHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve TEXT relevance and field-sort ordering, explicit scope, projection, total and lookahead boundaries; preserve HYBRID first page/empty-cursor parity with TEXT. Relevance cursor pages remain reranker-eligible while query understanding and expansion remain excluded, even with AI available and those features enabled.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task qc-cursor request",
          "delegationScope": "Autonomous investigation and design of query classification fixes; the subsequent owner instruction \"proceed accordingly\" authorizes proceeding with the bounded BM25-only cursor repair assigned by the orchestrator.",
          "choice": "Forward the request relevance cursor only when the planner realizes BM25-only retrieval, through a cursor-bearing TextQueryOps overload that reuses ReadPathOps; preserve existing parser, filters, boosts, fuzzy policy, Head AI eligibility, v1 codec and fused paging behavior.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/bm25-only-cursor-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/app/engine/Bm25OnlyCursorHttpTest.java#textAndFieldSortedPagesPreserveTheirBoundaries",
        "modules/ui/src/test/java/io/justsearch/app/engine/Bm25OnlyCursorHttpTest.java#firstPageAndEmptyCursorKeepTheSameLookahead",
        "modules/ui/src/test/java/io/justsearch/app/engine/Bm25OnlyCursorHttpTest.java#cursorPagesKeepRerankingEligibleAndExcludeUnderstandingAndExpansion",
        "modules/ui/src/test/java/io/justsearch/app/engine/Bm25OnlyCursorHttpTest.java#fieldSortKeepsExplicitScopeAndProjectionAcrossPages"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/Bm25OnlyCursorTest.java"
      ],
      "class": "New",
      "behaviour": "A real capture/planner/Worker/Lucene deliberate no-discriminative-term skip consumes its cursor while retaining its planned skip reason; an absent SPLADE-only encoder falls back to BM25 and consumes its cursor while retaining NO_EMBEDDING_SERVICE. Explicit vectors avoid native encoder work; no unrun native SPLADE branch is claimed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task qc-cursor request",
          "delegationScope": "Autonomous investigation and design of query classification fixes; the subsequent owner instruction \"proceed accordingly\" authorizes proceeding with the bounded BM25-only cursor repair assigned by the orchestrator.",
          "choice": "Forward the request relevance cursor only when the planner realizes BM25-only retrieval, through a cursor-bearing TextQueryOps overload that reuses ReadPathOps; preserve existing parser, filters, boosts, fuzzy policy, Head AI eligibility, v1 codec and fused paging behavior.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/bm25-only-cursor-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/Bm25OnlyCursorTest.java#deliberateDenseSkipConsumesItsCursorAndKeepsItsReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/Bm25OnlyCursorTest.java#absentSpladeKeywordFallbackConsumesItsCursorAndKeepsItsReason"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/Bm25OnlyCursorTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve cursorless real BM25+dense fusion and its existing inbound no-cursor policy; TEXT keeps fuzzy correction while BM25-only fallback does not acquire it; malformed raw LUCENE keeps INVALID_ARGUMENT on both paths.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task qc-cursor request",
          "delegationScope": "Autonomous investigation and design of query classification fixes; the subsequent owner instruction \"proceed accordingly\" authorizes proceeding with the bounded BM25-only cursor repair assigned by the orchestrator.",
          "choice": "Forward the request relevance cursor only when the planner realizes BM25-only retrieval, through a cursor-bearing TextQueryOps overload that reuses ReadPathOps; preserve existing parser, filters, boosts, fuzzy policy, Head AI eligibility, v1 codec and fused paging behavior.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/bm25-only-cursor-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/Bm25OnlyCursorTest.java#genuineDenseFusionStaysCursorlessAndIgnoresInboundCursor",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/Bm25OnlyCursorTest.java#textKeepsFuzzyRetriesWhileBm25FallbackDoesNotGainThem",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/Bm25OnlyCursorTest.java#malformedRawSyntaxRetainsItsExistingInvalidArgument"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/TextQueryOpsCursorCompatibilityTest.java"
      ],
      "class": "New",
      "behaviour": "Historical TextQueryOps signatures preserve raw parsing, hard filters, soft boosts, retrieval limit and outbound v1 relevance token with no inbound cursor. ReadPathOps preserves projected fields, lookahead, totals and historical v1 values.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task qc-cursor request",
          "delegationScope": "Autonomous investigation and design of query classification fixes; the subsequent owner instruction \"proceed accordingly\" authorizes proceeding with the bounded BM25-only cursor repair assigned by the orchestrator.",
          "choice": "Forward the request relevance cursor only when the planner realizes BM25-only retrieval, through a cursor-bearing TextQueryOps overload that reuses ReadPathOps; preserve existing parser, filters, boosts, fuzzy policy, Head AI eligibility, v1 codec and fused paging behavior.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/bm25-only-cursor-preservation.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/TextQueryOpsCursorCompatibilityTest.java#oldSignatureKeepsRawParsingHardFiltersSoftBoostsAndItsOutboundToken",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/TextQueryOpsCursorCompatibilityTest.java#readPathStillOwnsProjectionLimitsAndHistoricalRelevanceTokens"
      ]
    }
  ]
}
```

## Acceptance records
