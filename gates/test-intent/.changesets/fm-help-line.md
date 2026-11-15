---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: codex-builder-fm-s5b
---

# Test-intent entries

The user's rule and fm-04 picture note govern the quiet Help line. G1-G4/H1-H9 are delegated choices, not attributed to the owner as their wording; H amendments win. The action uses normal text colour at medium weight; information is grey. Text uses the existing first-minutes size tokens and no new element style attributes.

New isolated checks cover runtime exclusions before text/dense/SPLADE/chunk budgets, worker rows/counts/facets/filter intersections, count failure omission, HTTP/API/IPC round trips and malformed requests, quick/refined request construction and accessible Help navigation. They change no shared fixture or global process state. The new Java fixture needed a document UID; the HTTP fixture uses the existing borrowed ConfigStore and unavailable AI service seam. No scenario was changed. Fail-before evidence: the five feature checks in gates/test-intent/evidence/fm-help-line.json (run-evidence --before 94fdd08cc1); the six review-repair checks (multi-source merge, decision syntax, typo-corrected query, cursor pages, trimmed and bounded exclusions, refine without flicker, legacy untagged Help) in gates/test-intent/evidence/fm-help-line-repairs.json (--before d2c718ea87, the pre-repair candidate). Each fails by assertion. The review repairs follow the orchestrator's delegated decisions J1-J8 (task t-20261113-fmimpl, 2026-11-15, within the delegation "You are supposed to be the implementation session"), which amend H3/H5: J1 a per-source (multi-source) search clears the children's Help counts, which were filtered by one source each; it uses the unfiltered backfill's count when the backfill ran, and otherwise carries no Help count (no line) rather than an extra head-side count request. J2 the Help count parses with the main count's syntax. J3 after a zero-hit typo retry it counts the corrected query. J5 a refine behind shown results keeps the previous line; a failed refine keeps the quick-pass line. J6 exclusions are trimmed; more than 16 are a 400. J7 no Help count on cursor pages. J8 untagged Help from older versions is excluded by Help identity. The UI check lives in views/search-v3/first-minutes/ResultsHelp.test.ts so that no existing source scanner reads it (an earlier location next to SearchV3View.ask.test.ts was read by its scanners).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KnowledgeSearchHelpBoundaryTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Malformed excludedCollections produce exactly one 400 response before worker admission.",
      "whySurvives": "Remove only the redundant org.mockito.Mockito qualifier from atLeast(0); the existing static import resolves the same method. Every assertion, input and verification remains identical. PMD reports UnnecessaryFullyQualifiedName at line 87 on both the untouched d2c718ea87 shared clone and the repaired tree."
    },
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1dc1b-6a7f-7040-9d01-5ac96d6dd90e",
  "verdict": "accept",
  "digest": "sha256:d0c0311975360b37b51f9df7d5e1895c48a90c27c34502ad788112c622753c04"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1dc49-4aaf-70e0-bbb0-b04a2fe42ca1",
  "verdict": "reject",
  "digest": "sha256:8f1627a3b23a13756de667d4ee2349f080683d35b141df2dfa165ce303fa18ca",
  "note": "SearchPerSourceHelpMatchesTest.fullSourceWindowOmitsSourceScopedHelpCount asserts that the entire helpMatches field is absent when the source window is full, without a count failure. H3 requires the same-query, same-predicate Help count when Help is excluded; H5 permits omission on count failure. No supplied source adopts omission merely because backfill was unnecessary. This assertion rejects an accurate base-scope count and pins a weaker outcome. Reframe the check to reject child-scoped counts while allowing an accurate base-scope count, or supply an adopted change to H3."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-72qfop32",
  "verdict": "accept",
  "digest": "sha256:ec898a0bb09fbc9065da69e04e29f55cd1296ba5c4630f4d4185945ee9675cce"
}
```
