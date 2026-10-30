---
schema: test-intent.v1
author-role: builder
author-session: composer-port-builder
---

# Composer port preservation checks

The current user request supplies CP1b, CP1c, CP1d, CP5, CP8 and CP10 verbatim below.
The source reference identifies this request without recording private task metadata.
These are additional preservation oracles, not repairs or adaptations of existing oracles.
The baseline shared clone ran the added checks against unchanged production code.
The new node-environment composerPort.test.ts is an isolated contract test and is exempt
from a changeset under the test-intent rules. The ceiling script self-test is tooling.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.initialLifecycle.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.removalDraft.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structured.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation checks for CP1b/CP1c/CP1d, CP5, CP8 and CP10: pending Structured input, identical assignments, A-B-A edits, composition start, successful and refused draft writes, confirmed and reconciled deletion, storage events, failed handoff restoration, first-render seeds, retained versus fresh mounts, lifecycle snapshot ownership, focus/caret and shared-query isolation. Existing assertions and test titles are unchanged. No repaired behavior is claimed; the added checks pass on baseline 7959b712fd and after the extraction.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task current request",
          "quote": "structured extraction clears only after accepted completion, under the same condition; failure or Stop keeps the input."
        },
        {
          "kind": "owner-words",
          "location": "task current request",
          "quote": "Delegate and steering clear at submission, after the draft write succeeds, as today; no new guard is added (SW-RUN-01, SW-RUN-04)."
        },
        {
          "kind": "owner-words",
          "location": "task current request",
          "quote": "confirmed deletion clears only the owning draft under today's condition, including late reconciliation, navigation and another window's storage events."
        },
        {
          "kind": "owner-words",
          "location": "task current request",
          "quote": "a failed Ask handoff restores its text only under today's conditions."
        },
        {
          "kind": "owner-words",
          "location": "task current request",
          "quote": "several draft writes before first render seed the first composer once, and later updates never overwrite typing; reconnecting a retained view differs from constructing a fresh one as today; lifecycle flushes do not overwrite another window's newer snapshot; focus and caret after assignment as today."
        },
        {
          "kind": "owner-words",
          "location": "task current request",
          "quote": "a shared search from another surface updates an eligible Search draft but leaves Ask, Delegate, restored text and open conversations alone, before first render, while away and on return."
        }
      ],
      "evidence": "gates/test-intent/evidence/composer-port-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.initialLifecycle.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.removalDraft.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structured.test.ts"
      ]
    }
  ]
}
```

## Acceptance records

Independent acceptance is pending.
