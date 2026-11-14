---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: orchestrator
author-session: a7ab93d3-dd3c-4b69-acee-c8d1dcfbc2c2
---

# Test-intent entries

Registry follow-up to fm-first-launch: one test-only helper added to the test-support list.

```json
{
  "entries": [
    {
      "items": [
        "gates/test-intent/test-support-paths.v1.json"
      ],
      "change": "modified",
      "kind": "watched-baseline",
      "class": "Structural rule kept",
      "rule": "Every frontend src file imported only by tests is registered and remains subject to the test-intent gate, as required by docs/reference/testing/test-intent.md (Test-only frontend helpers).",
      "whySurvives": "Register modules/ui-web/src/shell-v0/controllers/draftKeptHint.ts, which became test-only on this branch when Search stopped emitting \"Draft kept\" as a status (decision D9); the helper file itself is unchanged and existing tests still import it to reset its state. All existing paths remain. The registry predicate and import scanner are unchanged; adding the helper tightens coverage, and the registry unit check (scripts/governance/gates/test-intent/units.test.mjs, test support) fails without this line and passes with it.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "You are supposed to be the implementation session",
          "location": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z"
        },
        {
          "kind": "delegated-choice",
          "decision": "D9",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Draft kept\" no longer emitted as status or status-list entry; drafts themselves still kept.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
