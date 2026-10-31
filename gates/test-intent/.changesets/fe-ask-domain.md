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
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.retirement.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split"
      ],
      "whatMoved": "runAsk moved from SearchV3View into askDomain. Only the moved member access, its structural cast and a type import changed; the retirement check keeps its existing inputs, fault injection and assertions.",
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
      "behaviour": "Preserve interleaved question A / answer A / question B / partial answer B in the actual mounted transcript DOM, with each streamed delta once. Preserve rejected Structured output omission both on screen and in the next ordinary Ask request after CANCELLED or interruption; retain instruction, explicitly retire unknown interrupted delivery and preserve conversation identity. Reject malformed, null and array schemas without dispatch or draft clearing, both through Send and through the composer submit-event boundary that reaches the Ask owner independently of composer validation. Keep the original draft and schema in both paths. All nine cases pass on the unchanged 9233210d09 production base in a shared clone, and on the extracted candidate. This file is named because SearchV3View.ask.test.ts reads its directory. No catalog observation or claim about an installed application or real backend is added.",
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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts"
      ],
      "class": "New",
      "behaviour": "Keep the existing accepted-output, interrupted-output, follow-up-context and reload assertions. The ordinary Summarize partial-text case prepares a real Summarize handoff with a file, sends through the composer, asserts the outgoing core.summarize shape, and retains streamed partial prose on an empty terminal; ordinary Ask and model-only cases also assert their outgoing shapes. All cases pass on unchanged 9233210d09 production and the extracted candidate.",
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
          "choice": "Exercise the existing Summarize preservation expectation through a real handoff and Send, and witness its outgoing shape before checking its result.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-ask-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.modelStructured.test.ts"
      ],
      "class": "New",
      "behaviour": "Keep the existing model and Structured request, rendering and cold-retry assertions. The remembered Structured Retry case first sends an extraction with a schema and file scope, then retries its resulting turn and asserts that the second dispatch is core.extract with the original schema and selection. Its HTTP fixture records the sent question and accepted Structured answer with real message IDs for the dispatched conversation, so Retry can fork as it would against an Engine saved record. No private input map is seeded. All cases pass on unchanged 9233210d09 production and the extracted candidate.",
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
          "choice": "Exercise remembered Structured Retry through an actual first send, proving that the Ask owner records and reuses its schema and file scope rather than supplying the private input map in the test.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-ask-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.modelStructured.test.ts"
      ]
    }
  ]
}
```

## Test-path verification

The three changed files execute 55 cases, all passing on baseline `9233210d09` in a shared clone and on candidate `8def52f703` with these test edits. Only moved `askDomain.runAsk` accesses and their structural casts are reversed in the baseline copy.

The baseline trace of the original fresh-send fixture found recorded schema/scope and `canRerun('structured') === true`, but `recordId` and `assistantRecordId` were null, lineage `canEdit` was false, and Retry made no requests. The Structured path returns at its `!lineage.canEdit` guard. The fixture now returns the dispatched conversation's saved question and accepted answer with message IDs through `/history` and `/api/thread`; the test continues to use the real first send and resulting turn.

Each mutation was applied alone in a separate shared clone of the candidate, run against the named case, and restored:

- Remove only the JSON schema object validation block in `Sv3AskDomain.runAsk`: all three owner submit-event cases fail at the assertion for `Enter a JSON schema object.` (3 failed).
- Pass `core.rag-ask` instead of `core.summarize` to `sv3Ask`: the ordinary Summarize case fails its outgoing-shape assertion, expected `core.summarize`, received `core.rag-ask` (1 failed).
- Remove only `structuredInputs.set(ref.turnId, { schema, scope })` at send: remembered Structured Retry fails `expected [] to have a length of 1 but got +0` (1 failed).

Production code remains unchanged by this test-quality round. These are mounted UI checks with controlled HTTP, not claims about an installed Engine. The full test-intent execution evidence is regenerated by `scripts/governance/gates/test-intent/run-evidence.mjs`.

## Acceptance records

Independent acceptance is left to the verifier.

Tests added or rewritten: all four named files are durable product checks. The retirement adaptation retains its oracle. The three strengthened test files preserve existing assertions and add direct witnesses of owner validation, Summarize dispatch and recorded Structured inputs. Controlled HTTP, saved-message fixtures and drain helpers are local scaffolding. No test-only helper file or controller catalog observation was added.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a18fe2-d9ad-7591-a147-14fc3f58b3dc",
  "verdict": "accept",
  "digest": "sha256:1081b01232e2cee7ed8614f80bad4bbf6b9227c56dfd1ca1a6610fd1f667dcfb"
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "nj0686xi-test-intent-audit",
  "verdict": "accept",
  "digest": "sha256:1081b01232e2cee7ed8614f80bad4bbf6b9227c56dfd1ca1a6610fd1f667dcfb"
}
```
