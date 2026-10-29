---
schema: test-intent.v1
task: retrieval-module-tier
author-role: builder
author-session: uiux-fix-retrieval-it-builder
---

# Retrieval integration expectations after WP2.3

Agent choices under the original design delegation; not owner words.
Production is unchanged. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/mcp/MmrFailureInterfacesIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "The invalid-query MMR check keeps its direct Worker query assertion, full positional passage/citation assertions and zero passage-inference requirement. REST/MCP retain their real open retrieval and folder restriction: their scripted provider now returns a valid vector to each hybrid pre-search and NaN to each rank-only MMR call (four acquisitions total). D7 intentionally makes a restricted pre-search failure terminal, so the old globally invalid provider tested the wrong failure stage at these interfaces. This pins preserved MMR behavior, not a product repair.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Search/Ask folder, modified-date and size restriction design; preserving unrestricted recovery while failing closed for restricted retrieval.",
          "choice": "Settled scenarios revision 3 D7/F3 forbid unrestricted fetch after restricted pre-search or rich-retrieval failure; P1 preserves unrestricted recovery and chosen-file scopes. Isolate the invalid MMR vector from the hybrid pre-search with a scripted valid pre-search vector followed by an invalid MMR vector, retaining the real open REST/MCP paths.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/retrieval-module-tier/java.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/mcp/MmrFailureInterfacesIntegrationTest.java#invalidQueryGoesStraightToPositionWithoutPassageInference"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/mcp/RetrievalRequestEndInterfacesIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "D7/F3: folder-restricted chosen-file deadline returns REST TIMEOUT/504 and MCP TIMEOUT, and restricted ordinary rich-port failure returns an error, never NO_CONTENT or batch fetch. P1: the same failures without folder/date/size restrictions retain successful FULLTEXT_FALLBACK, successful MCP, and existing saved-conversation chosen-file recovery/no-content distinctions. Existing healthy, empty, local-wait and history assertions remain. Against the required main baseline 63cce47daa, these are preservation checks: production already implements D7/F3/P1. The older dc296275ac diagnostics establish why the original expectations became stale, but are not fail-before evidence against main. No production behavior changes in this repair. A separate real hybrid pre-search NaN failure now checks REST failure/500 and MCP INTERNAL_ERROR with no context, outside-file material, rich retrieval or batch fetch under the folder restriction. The same provider without restrictions recovers through BM25 with K passages/citations and no batch fetch; query counts distinguish the pre-search and MMR stages. A scratch-only unsafe unrestricted-search mutation must fail this new assertion on both interfaces.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Search/Ask folder, modified-date and size restriction design; preserving unrestricted recovery while failing closed for restricted retrieval.",
          "choice": "Settled scenarios revision 3 D7/F3 forbid unrestricted fetch after restricted pre-search or rich-retrieval failure; P1 preserves unrestricted recovery and chosen-file scopes. Isolate the invalid MMR vector from the hybrid pre-search with a scripted valid pre-search vector followed by an invalid MMR vector, retaining the real open REST/MCP paths.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/retrieval-module-tier/java.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/mcp/RetrievalRequestEndInterfacesIntegrationTest.java#chosenFileDeadlineKeepsWholeDocumentFallbackThroughInterfaces",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/mcp/RetrievalRequestEndInterfacesIntegrationTest.java#successfulEmptyAndOrdinaryFailureOutcomesStayDifferentThroughInterfaces",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/mcp/RetrievalRequestEndInterfacesIntegrationTest.java#preSearchFailureFailsClosedWhenRestrictedAndRecoversWhenUnrestricted"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records
