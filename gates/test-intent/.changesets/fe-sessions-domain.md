---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-sessions-domain
---

# Session navigation preservation

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessionNavigation.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve the shown conversation, reload pointer, draft and absence of read/send requests when row selection, version opening outside the sidebar or address following cannot acknowledge draft storage (unavailable, unacknowledged, stale ownership and capacity). Address restoration may move the shared pointer while the refused view keeps its identity. Unknown rows do nothing; selecting the active row refreshes both records without reordering or closing its evidence pane. All 14 cases pass on unchanged c678e421e9 in an isolated shared clone and on the extraction. The A-to-B-to-A check establishes file scope through search.prepare, as the existing HO8 outgoing-conversation test does, and asserts the draft, Structured tier, schema and file-scope preconditions before navigation. No existing test is adapted. The file is named because SearchV3View.ask.test.ts walks views/search-v3. No catalog observation is added: these checks do not establish every clause of a catalog entry.",
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
          "choice": "Extract sessions and navigation into one browser-free owner and add preservation checks for refused draft acknowledgements across opening paths and row-selection identity without changing established outcomes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-sessions-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessionNavigation.test.ts"
      ]
    }
  ]
}
```

Independent acceptance is left to the verifier. All 14 preservation checks passed before and after extraction. Tests added or rewritten: SearchV3View.sessionNavigation.test.ts — draft departure and row selection preservation — durable. HTTP responses, storage fault injection and rendering drains are local scaffolding. No test-only helper file was added.
