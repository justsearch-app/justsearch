---
schema: test-intent.v1
task: uiux-found-highlight-excerpt
author-role: builder
author-session: found-builder-2-20261030
---

# Test-intent entries

Round 2 (orchestrator repair after verifier-1): the source card now removes front matter before the card shortens the passage, and only metadata-shaped lines (key: value, indented continuations, list items) between the leading delimiters count as front matter, so ordinary Markdown between two horizontal rules stays visible. Two checks were added: a long closed header (repaired) and horizontal rules around ordinary Markdown (preservation: passes on the base, failed on the round-1 candidate).

Round 3 (orchestrator, after verifier-2): a metadata key needs a space, tab or line end after its colon, so a URL between two rules stays visible (added preservation check). B1 is narrowed: front matter containing `#` comment lines is shown as on main (named limit).

These are agent-adopted scenarios from the bounded builder brief, recorded as delegated choices;
they are not owner-agreed scenarios. All tests are new, isolated files. Existing expectations and
test selection are untouched. Repaired checks fail by assertion on 7959b712fd; preservation checks
pass there and on the candidate. No known-good revision for the repaired behavior was supplied.

The evidence producer runs unchanged in an isolated shared candidate clone under the assigned
TMPDIR. A temporary Node preload launcher translates only its before-worktree creation/removal
into a shared clone with plain npm ci in that clone. This prevents repository metadata writes and
shared dependency trees. No checks or expectations are changed by the launcher.
VITEST_MAX_WORKERS=2 and TZ=UTC apply to all evidence commands. The launcher's source, exact
invocation and raw command output are retained in tmp/verify for independent inspection.
The producer's aggregate exit is 1 because the three preservation checks pass before; per-check
classification distinguishes those passes from the eight repaired assertion failures.

Evidence exercises Lit component DOM with happy-dom, not an installed Engine or browser.
Copy actions and full document text are outside this helper and were not independently exercised.
The unchanged full suite provides narrower regression coverage; no live-system claim is made.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.wordMatches.test.ts"
      ],
      "class": "New",
      "behaviour": "A1/A2/A3: Unicode word-prefix highlights, default English stopwords, excluded words/phrases, field prefixes, Java whitespace and SearchSyntax escapes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Find the code paths; keep each change in one small shared helper next to the existing code, no new options.",
          "delegationLocation": "task uiux-found-highlight-excerpt user turn: builder brief, What to do item 1; scenarios A1-A4/B1-B2 and A3 orchestrator amendment",
          "delegationScope": "Bounded presentation-only assignment: agent-adopted scenarios A1-A4 and B1-B2, including the explicitly authorized A3 term extractor. No owner agreement is claimed; no index, analyzer, request or stored-data changes.",
          "choice": "A1/A2/A3: Unicode word-prefix highlights, default English stopwords, excluded words/phrases, field prefixes, Java whitespace and SearchSyntax escapes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-found-highlight-excerpt.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.wordMatches.test.ts#A1 marks word prefixes, never interior substrings",
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.wordMatches.test.ts#A1 treats Unicode letters and digits as words, case insensitively",
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.wordMatches.test.ts#A2 ignores every default English stopword, including in phrases",
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.wordMatches.test.ts#A3 excludes words and quoted phrases from highlighting",
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.wordMatches.test.ts#A3 separates recognized field prefixes and phrase words",
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.wordMatches.test.ts#A3 follows escaped quotes, escaped exclusions and case-sensitive field names"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.wordMatches.test.ts"
      ],
      "class": "New",
      "behaviour": "A4 preservation: literal snippet text and XSS-safe Lit rendering, unchanged hl class and verbatim empty/short queries.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Find the code paths; keep each change in one small shared helper next to the existing code, no new options.",
          "delegationLocation": "task uiux-found-highlight-excerpt user turn: builder brief, What to do item 1; scenarios A1-A4/B1-B2 and A3 orchestrator amendment",
          "delegationScope": "Bounded presentation-only assignment: agent-adopted scenarios A1-A4 and B1-B2, including the explicitly authorized A3 term extractor. No owner agreement is claimed; no index, analyzer, request or stored-data changes.",
          "choice": "A4 preservation: literal snippet text and XSS-safe Lit rendering, unchanged hl class and verbatim empty/short queries.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-found-highlight-excerpt.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.wordMatches.test.ts#A4 preserves literal text and safely renders untrusted query and snippet text"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/chat/passageExcerptPresentation.test.ts"
      ],
      "class": "New",
      "behaviour": "B1: closed leading front matter is hidden in source cards, expanded previews and citation hover cards; LF/CRLF, blank lines, empty blocks and later separators are covered.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Find the code paths; keep each change in one small shared helper next to the existing code, no new options.",
          "delegationLocation": "task uiux-found-highlight-excerpt user turn: builder brief, What to do item 1; scenarios A1-A4/B1-B2 and A3 orchestrator amendment",
          "delegationScope": "Bounded presentation-only assignment: agent-adopted scenarios A1-A4 and B1-B2, including the explicitly authorized A3 term extractor. No owner agreement is claimed; no index, analyzer, request or stored-data changes.",
          "choice": "B1: closed leading front matter is hidden in source cards, expanded previews and citation hover cards; LF/CRLF, blank lines, empty blocks and later separators are covered.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-found-highlight-excerpt.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/chat/passageExcerptPresentation.test.ts#B1 hides closed leading front matter in the source card, expanded preview and hover",
        "modules/ui-web/src/shell-v0/components/chat/passageExcerptPresentation.test.ts#B1 handles an empty closed block without removing later separators",
        "modules/ui-web/src/shell-v0/components/chat/passageExcerptPresentation.test.ts#B1 removes a closed header longer than the card budget before the card shortens it"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/chat/passageExcerptPresentation.test.ts"
      ],
      "class": "New",
      "behaviour": "B2 preservation: empty, unclosed, non-leading and front-matter-only passages remain verbatim; source identity, original excerpt, verification label and character/line anchors survive source-card activation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Find the code paths; keep each change in one small shared helper next to the existing code, no new options.",
          "delegationLocation": "task uiux-found-highlight-excerpt user turn: builder brief, What to do item 1; scenarios A1-A4/B1-B2 and A3 orchestrator amendment",
          "delegationScope": "Bounded presentation-only assignment: agent-adopted scenarios A1-A4 and B1-B2, including the explicitly authorized A3 term extractor. No owner agreement is claimed; no index, analyzer, request or stored-data changes.",
          "choice": "B2 preservation: empty, unclosed, non-leading and front-matter-only passages remain verbatim; source identity, original excerpt, verification label and character/line anchors survive source-card activation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-found-highlight-excerpt.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/chat/passageExcerptPresentation.test.ts#B2 preserves empty, unclosed, non-leading and front-matter-only passages verbatim",
        "modules/ui-web/src/shell-v0/components/chat/passageExcerptPresentation.test.ts#B2 keeps original evidence, verification, citation identity and character anchors",
        "modules/ui-web/src/shell-v0/components/chat/passageExcerptPresentation.test.ts#B2 keeps ordinary Markdown between two leading horizontal rules",
        "modules/ui-web/src/shell-v0/components/chat/passageExcerptPresentation.test.ts#B2 keeps a URL between two leading horizontal rules"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance; the builder does not accept its own expectations.
