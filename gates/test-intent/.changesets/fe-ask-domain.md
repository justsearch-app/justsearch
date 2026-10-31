---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-ask-domain
---

# Ask owner preservation

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.modelStructured.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.retirement.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split"
      ],
      "whatMoved": "runAsk and structuredInputs moved from SearchV3View into askDomain. Only accesses, their structural casts and one type import changed. All existing inputs, fault injection, assertions, expected values and conditions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261029-fs993c request, 2026-10-30",
          "quote": "proceed with step 4"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with step 4",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-30",
          "delegationScope": "Step 4 of the agreed frontend restructuring: move the Search view's areas out one at a time, nothing the user sees changes, under the same safety net of scenario checks",
          "choice": "Move the complete Ask lifecycle into one browser-free owner, adapt only moved test member paths, and add preservation checks of actual transcript DOM order, unsuccessful Structured follow-up context and invalid schema retention.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-ask-domain.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.askPreservation.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve interleaved question A / answer A / question B / partial answer B in the actual mounted transcript DOM, with each streamed delta once. Preserve rejected Structured output omission both on screen and in the next ordinary Ask request after CANCELLED or interruption; retain instruction, explicitly retire unknown interrupted delivery and preserve conversation identity. Reject malformed, null and array schemas without dispatch or draft clearing. All six cases pass on the unchanged 9233210d09 production base in a shared clone, and on the extracted candidate. This file is named because SearchV3View.ask.test.ts reads its directory. No catalog observation or claim about an installed application or real backend is added.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261029-fs993c request, 2026-10-30",
          "quote": "proceed with step 4"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with step 4",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-30",
          "delegationScope": "Step 4 of the agreed frontend restructuring: move the Search view's areas out one at a time, nothing the user sees changes, under the same safety net of scenario checks",
          "choice": "Move the complete Ask lifecycle into one browser-free owner, adapt only moved test member paths, and add preservation checks of actual transcript DOM order, unsuccessful Structured follow-up context and invalid schema retention.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-ask-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.askPreservation.test.ts"
      ]
    }
  ]
}
```

## Acceptance records

Independent acceptance is left to the verifier.

Tests added or rewritten: all four named files are durable product checks. The three adaptations retain their existing oracles; the new cases use controlled HTTP through the production composer and stream reader. Their mock transport and drain helpers are local test scaffolding. No test-only helper file or controller catalog observation was added.
