---
schema: test-intent.v1
author-role: builder
author-session: composer-port-builder
---

# Composer port preservation checks

The owner authorised step 4; the agent chose CP1b, CP1c, CP1d, CP5, CP8 and CP10 under that delegation.
The sources below distinguish the owner's words from the agent's preservation choices.
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
          "location": "task t-20261029-fs993c request, 2026-10-30",
          "quote": "proceed with step 4"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with step 4",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-30",
          "delegationScope": "Step 4 of the agreed frontend restructuring: move the Search view's areas out one at a time, nothing the user sees changes, under the same safety net of scenario checks",
          "choice": "The agent pins today's behaviour for CP1b (Structured completion clearing and failure or Stop retention), CP1c (Delegate and steering clearing after successful draft writes), CP1d (owning-draft deletion, reconciliation, navigation and storage events), CP5 (failed Ask handoff restoration), CP8 (first-render seeding, retained versus fresh mounts, snapshot ownership, focus and caret) and CP10 (shared-search updates with draft and conversation isolation) with preservation checks that pass on the base before moving the code behind the port.",
          "label": "agent choice"
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
