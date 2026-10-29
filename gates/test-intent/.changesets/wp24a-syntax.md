---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp24a-builder-suo96vr2
---

# Test-intent entries

Agent choices under the quoted design delegation, never owner-agreed words. The orchestrator must generate execution evidence and obtain independent acceptance. The builder did not run run-evidence.mjs.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SearchSyntaxRetrievalTest.java"
      ],
      "class": "New",
      "behaviour": "A1–A3/X1–X4/X6: real indexed contrasting documents establish field-local phrases, document-wide exclusions, required field/phrase composition with optional ranking words, literal punctuation and escapes, and fixed invalid-query reasons.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A1–A3/X1–X4/X6: real indexed contrasting documents establish field-local phrases, document-wide exclusions, required field/phrase composition with optional ranking words, literal punctuation and escapes, and fixed invalid-query reasons.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-retrieval.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SearchSyntaxRetrievalTest.java#x1PhraseStaysWithinOneField",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SearchSyntaxRetrievalTest.java#x2ExclusionsApplyAcrossFieldsAndPhrases",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SearchSyntaxRetrievalTest.java#x3RequiredClausesComposeAcrossFieldsWithOptionalWords",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SearchSyntaxRetrievalTest.java#x4UnsupportedPunctuationIsLiteralInsideAdvancedQueries",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SearchSyntaxRetrievalTest.java#x6InvalidQueriesFailWithSpecificReasons"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SearchSyntaxRetrievalTest.java"
      ],
      "class": "New",
      "behaviour": "A0/X7: plain Search produces exactly the historical SIMPLE query and results over a fixed corpus; raw Lucene phrases and *:* probes keep working.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A0/X7: plain Search produces exactly the historical SIMPLE query and results over a fixed corpus; raw Lucene phrases and *:* probes keep working.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-preservation.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SearchSyntaxRetrievalTest.java#x7PlainSearchEqualsHistoricalSimpleAndRawLuceneStillWorks"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java"
      ],
      "class": "New",
      "behaviour": "A4/X5 and A5/X6: Head forces sparse-only advanced retrieval, turns off semantic/expansion/reranking work, preserves the supplied facet filter rather than normalizing it, and rejects invalid syntax before Worker search.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A4/X5 and A5/X6: Head forces sparse-only advanced retrieval, turns off semantic/expansion/reranking work, preserves the supplied facet filter rather than normalizing it, and rejects invalid syntax before Worker search.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-head.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#x5AdvancedSearchOverridesSemanticPipelineAndKeepsExplicitFilters",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#x6SearchRejectsInvalidSyntaxBeforeWorkerSearch"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java"
      ],
      "class": "New",
      "behaviour": "A0/X7: plain Search and SIMPLE dispatch identical requests and retain expansion, query understanding and reranking eligibility.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A0/X7: plain Search and SIMPLE dispatch identical requests and retain expansion, query understanding and reranking eligibility.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#x7PlainSearchKeepsSimpleDispatchAndExpansionEligibility"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/FacetQuerySyntaxCouplingTest.java"
      ],
      "class": "New",
      "behaviour": "A4/X5: even explicit dense/SPLADE/auto/expansion input at the in-process Worker port resolves to lexical results, matchCount and facets satisfying the advanced phrase, with and without requested facets. This fixture has no model service; model-enabled runtime evidence is separate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A4/X5: even explicit dense/SPLADE/auto/expansion input at the in-process Worker port resolves to lexical results, matchCount and facets satisfying the advanced phrase, with and without requested facets. This fixture has no model service; model-enabled runtime evidence is separate.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-worker.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/FacetQuerySyntaxCouplingTest.java#x5SearchSubsetKeepsResultsCountsAndFacetsLexicalOnBothPasses"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.syntax.test.ts"
      ],
      "class": "New",
      "behaviour": "A0/A4/A5/X5/X6/X8: mounted Search sends the new syntax, displays the Engine reason only on submit while retaining text, suppresses automatic 400 and refinement, clears pending indicators, withholds stale population facts, and retains URL operator text.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A0/A4/A5/X5/X6/X8: mounted Search sends the new syntax, displays the Engine reason only on submit while retaining text, suppresses automatic 400 and refinement, clears pending indicators, withholds stale population facts, and retains URL operator text.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.syntax.test.ts#x6 explicit submit keeps text and renders the Engine reason under the bar without stale rows",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.syntax.test.ts#x6 automatic 400 stays quiet and schedules no refined request then a correction resumes",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.syntax.test.ts#x5 withholds prior counts and facets while the new query awaits its Engine classification",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.syntax.test.ts#x5 advanced quick results keep counts and facets pending until refinement",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.syntax.test.ts#x8 legacy URL restoration keeps operator text and uses Search syntax"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.syntax.test.ts"
      ],
      "class": "New",
      "behaviour": "A5/X6: edits supersede both response and asynchronous error-body reads; valid → incomplete → corrected never adopts an earlier answer.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A5/X6: edits supersede both response and asynchronous error-body reads; valid → incomplete → corrected never adopts an earlier answer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.syntax.test.ts#x6 each edit supersedes an older success and an older error body"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/searchState.test.ts"
      ],
      "class": "New",
      "behaviour": "A0: the existing request-body oracles acquire querySyntax: search on both stages, retaining every existing filter/pipeline/facet expectation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A0: the existing request-body oracles acquire querySyntax: search on both stages, retaining every existing filter/pipeline/facet expectation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-request.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/searchState.test.ts#searchState (slice 463) omits filters key when no active filter (quick pass carries the cheap pipeline)",
        "modules/ui-web/src/shell-v0/state/searchState.test.ts#searchState (slice 463) buildSearchIntent is the one constructor: identical inputs → identical bodies"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "New",
      "behaviour": "A6/X8 preservation: Delegate sends operator characters without creating Search requests or a syntax claim.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A6/X8 preservation: Delegate sends operator characters without creating Search requests or a syntax claim.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#X8 Delegate sends operator characters without a Search syntax claim"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.modelStructured.test.ts"
      ],
      "class": "New",
      "behaviour": "A6/X8 preservation: Ask, Structured and model-only dispatch their original prompt characters and make no querySyntax claim or Search request.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A6/X8 preservation: Ask, Structured and model-only dispatch their original prompt characters and make no querySyntax claim or Search request.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.modelStructured.test.ts#X8 ask sends operator characters as prompt text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.modelStructured.test.ts#X8 structured sends operator characters as prompt text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.modelStructured.test.ts#X8 model-only sends operator characters as prompt text"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "New",
      "behaviour": "A0/A6/X8: a restored quoted and excluded Search draft keeps its text, blocks Enter until the existing explicit review, then sends its unchanged text using Search syntax on the next deliberate submit.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing remaining Search window work, areas 1–5; WP2.4a A0–A6 and X1–X8, scenarios revision 4.",
          "choice": "A0/A6/X8: a restored quoted and excluded Search draft keeps its text, blocks Enter until the existing explicit review, then sends its unchanged text using Search syntax on the next deliberate submit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24a-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#X8 restored quoted Search draft keeps its text and requires the existing review before Search"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
