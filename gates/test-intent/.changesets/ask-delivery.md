---
schema: test-intent.v1
task: t-20261031-unsent-work
author-role: builder
author-session: builder-3
---

# Test-intent entries

Stage 1 only. The unfinished snapshot projection and its existing test stay unchanged.
All new tests are durable. Fixture-only edits preserve the prior behavioral oracles.
Execution evidence was produced in an isolated shared candidate clone; the evidence runner's
own before worktree belongs to that clone, never to the source repository.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.modelStructured.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Mock saved rows for newly dispatched turns now carry the request turnId, as the Engine does. Inputs, actions, counts, rendered outcomes, refusal rules, thinking and context assertions are unchanged; legacy cold fixtures remain unstamped.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261031-unsent-work request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261031-unsent-work request, 2026-10-31",
          "delegationScope": "fix the Search window's false and stale delivery-uncertainty notices without changing New's behaviour",
          "choice": "Retain a fresh pre-dispatch UUID through recovery and saved question/outcome rows, and clear uncertainty only after bounded reads find that exact saved terminal outcome.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.preparationAck.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The expected new recovery write uses payload version 3; envelope version 1, exact-byte readback, write counts, storage-fault callbacks, draft preservation and preparation assertions remain unchanged. Existing version 1 and 2 inputs are still tested.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261031-unsent-work request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261031-unsent-work request, 2026-10-31",
          "delegationScope": "fix the Search window's false and stale delivery-uncertainty notices without changing New's behaviour",
          "choice": "Retain a fresh pre-dispatch UUID through recovery and saved question/outcome rows, and clear uncertainty only after bounded reads find that exact saved terminal outcome.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-recovery.test.ts"
      ],
      "class": "New",
      "behaviour": "The strict recovery reader admits the version 3 extension and rejects unknown version 4 while retaining its earlier payload checks.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261031-unsent-work request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261031-unsent-work request, 2026-10-31",
          "delegationScope": "fix the Search window's false and stale delivery-uncertainty notices without changing New's behaviour",
          "choice": "Retain a fresh pre-dispatch UUID through recovery and saved question/outcome rows, and clear uncertainty only after bounded reads find that exact saved terminal outcome.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ask-delivery-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-recovery.test.ts#reads the strict original text-only payload and strict new data-only proposal"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delivery.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-dispatch-record.test.ts"
      ],
      "class": "New",
      "behaviour": "Live ownership suppresses false uncertainty; exact saved outcomes retire only their retained attempt with storage ACK and preserved draft review. Correlation separates interleaved writers, forks, legacy rows, synthetic incomplete errors, history mapping, export and companion context. Read timeouts and budgets preserve uncertainty, support explicit rearming and isolate navigation/deletion.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261031-unsent-work request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261031-unsent-work request, 2026-10-31",
          "delegationScope": "fix the Search window's false and stale delivery-uncertainty notices without changing New's behaviour",
          "choice": "Retain a fresh pre-dispatch UUID through recovery and saved question/outcome rows, and clear uncertainty only after bounded reads find that exact saved terminal outcome.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ask-delivery-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delivery.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-dispatch-record.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/DispatchCorrelationHttpTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/DispatchCorrelationTest.java"
      ],
      "class": "New",
      "behaviour": "The current prompt and its saved successful/error/stopped outcome share the dispatch turnId across store reopen, branch inheritance and the real history/thread HTTP readers. Forwarded prompts and LLM input remain unstamped; intermediate invalid extraction output remains unaccepted. Existing Stop contracts remain unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261031-unsent-work request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261031-unsent-work request, 2026-10-31",
          "delegationScope": "fix the Search window's false and stale delivery-uncertainty notices without changing New's behaviour",
          "choice": "Retain a fresh pre-dispatch UUID through recovery and saved question/outcome rows, and clear uncertainty only after bounded reads find that exact saved terminal outcome.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ask-delivery-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/DispatchCorrelationHttpTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/DispatchCorrelationTest.java"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
