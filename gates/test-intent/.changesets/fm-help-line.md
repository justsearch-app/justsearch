---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: codex-builder-fm-s5b
---

# Test-intent entries

The user's rule and fm-04 picture note govern the quiet Help line. G1-G4/H1-H9 are delegated choices, not attributed to the owner as their wording; H amendments win. The action uses normal text colour at medium weight; information is grey. Text uses the existing first-minutes size tokens and no new element style attributes.

New isolated checks cover runtime exclusions before text/dense/SPLADE/chunk budgets, worker rows/counts/facets/filter intersections, count failure omission, HTTP/API/IPC round trips and malformed requests, quick/refined request construction and accessible Help navigation. They change no shared fixture or global process state. The new Java fixture needed a document UID; the HTTP fixture uses the existing borrowed ConfigStore and unavailable AI service seam. No scenario was changed. Fail-before evidence for the five new files is gates/test-intent/evidence/fm-help-line.json (run-evidence --before 94fdd08cc1; each fails by assertion). The UI check lives in views/search-v3/first-minutes/ResultsHelp.test.ts so that no existing source scanner reads it (an earlier location next to SearchV3View.ask.test.ts was read by its scanners).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpAppliedFiltersTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpDeliveryGovernorTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpEntityCarriageMetricTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpEntityCarriageTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpEvidenceProjectionTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpFramingRenderSnapshotTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpProtocolHandlerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpResourcesReadTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpSearchResponseFormatTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpSearchTraceLegibilityTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpSemanticBudgetNoteTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpTierEquivalenceGoldenTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpTierEquivalenceTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/PrivateHistoryMcpScopeTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The response record gained an optional helpMatches component. Existing MCP fixture constructors pass null for it; all prior inputs, expected results, observations, fault injections, assertions, tags and scenarios remain unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "JustSearch's own help pages are not result rows.",
          "location": "task t-20261113-fmimpl request, adopted rule supplied in slice 5b brief"
        },
        {
          "kind": "owner-words",
          "quote": "JustSearch's own help pages are one quiet line at the end, never rows.",
          "location": "task t-20261113-fmimpl request, fm-04 picture note"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G1-G4 as amended by H1-H9: ordinary quick/refined Search requests exclude justsearch-help using an optional top-level request field. No shared default or Ask restriction change. Counts/facets/candidate budgets exclude Help. Same response carries an independent lexical helpMatches count with every same predicate; exact/capped/failure are distinguished. A quiet Help action opens existing HelpSurface, including below nothing-found. Explicit collection scope and other callers retain their existing populations; no new addressable Help results view. Keyboard, screen reader, light/dark and 600 px behavior remain required.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/KnowledgeSearchResponseContractTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/KnowledgeWireContractConformanceTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Every response record component is mapped by the HTTP controller and described by knowledge.proto, including nested record fields.",
      "whySurvives": "The controller maps helpMatches only when available. The mapping set adds that component and the nested-record conformance check now checks HelpMatches too. Existing predicates and every prior field remain.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "JustSearch's own help pages are not result rows.",
          "location": "task t-20261113-fmimpl request, adopted rule supplied in slice 5b brief"
        },
        {
          "kind": "owner-words",
          "quote": "JustSearch's own help pages are one quiet line at the end, never rows.",
          "location": "task t-20261113-fmimpl request, fm-04 picture note"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G1-G4 as amended by H1-H9: ordinary quick/refined Search requests exclude justsearch-help using an optional top-level request field. No shared default or Ask restriction change. Counts/facets/candidate budgets exclude Help. Same response carries an independent lexical helpMatches count with every same predicate; exact/capped/failure are distinguished. A quiet Help action opens existing HelpSurface, including below nothing-found. Explicit collection scope and other callers retain their existing populations; no new addressable Help results view. Keyboard, screen reader, light/dark and 600 px behavior remain required.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/searchState.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "JustSearch's own help pages are not result rows.",
          "location": "task t-20261113-fmimpl request, adopted rule supplied in slice 5b brief"
        },
        {
          "kind": "owner-words",
          "quote": "JustSearch's own help pages are one quiet line at the end, never rows.",
          "location": "task t-20261113-fmimpl request, fm-04 picture note"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G1-G4 as amended by H1-H9: ordinary quick/refined Search requests exclude justsearch-help using an optional top-level request field. No shared default or Ask restriction change. Counts/facets/candidate budgets exclude Help. Same response carries an independent lexical helpMatches count with every same predicate; exact/capped/failure are distinguished. A quiet Help action opens existing HelpSurface, including below nothing-found. Explicit collection scope and other callers retain their existing populations; no new addressable Help results view. Keyboard, screen reader, light/dark and 600 px behavior remain required.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Checked the delegated G1-G4 and H1-H9 in the brief: H2 replaces the old ordinary-request body with an opt-in Help exclusion; H1 replaces G3 and forbids a new Help-scoped results view. Ask restrictions and explicit collection scopes are unchanged. Only the old exact ordinary-request bodies conflict. All pipeline, excerpt, facet and omitted-filter assertions survive."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1dc09-9d09-7761-a321-949c61bcafd9",
  "verdict": "reject",
  "digest": "sha256:91c3fec6ffea1a6604e11f002bee8eb06ed3f04061d779a3c714234a859b5d39",
  "note": "Sv3Results.help.test.ts is not isolated: existing SearchV3View.ask.test.ts reads it as source input at lines 264 and 278. Runtime readFileSync tracing confirmed both reads. Test-intent rules prohibit exempt new tests from being read by existing tests. Supply a New entry with evidence and acceptance, or isolate the test without weakening the scanner. All 14 new cases pass on the candidate; no MCP/VDU assertion was weakened."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1dc13-6b29-7803-a804-433e2f11c582",
  "verdict": "reject",
  "digest": "sha256:91c3fec6ffea1a6604e11f002bee8eb06ed3f04061d779a3c714234a859b5d39",
  "note": "Hold: the current digest retains the earlier rejection and the gate fails. UI isolation is repaired; the intent sources and preserved oracles are sound. Independent Java reruns are blocked by the read-only /home/elias/.gradle wrapper lock."
}
```
