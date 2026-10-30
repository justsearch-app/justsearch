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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3ComposerPort.test.ts"
      ],
      "class": "New",
      "behaviour": "Contract checks for the view-side composer adapter: a replacement composer element with equal text and equal draft version makes an earlier capture stale; the same element reused across reconnect keeps an unedited capture valid; a capture taken before first render follows the view's pre-render seed contract. Removing the adapter's identity check makes these checks fail. The file is not isolated because an existing Ask test in the same folder reads every sibling test source, so it is listed here instead of being exempt.",
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
          "choice": "The agent adds direct checks of the composer adapter's identity rules (replacement element, reconnect of the same element, capture before first render), which an independent review found were only covered indirectly.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/composer-port-adapter.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3ComposerPort.test.ts"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-mmo8jwuu-0605675",
  "verdict": "accept",
  "digest": "sha256:7c2fdb33b6b502c6d05f0fb56cabd4eda001527d0046b5155e900f6aabdb38b1"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a18c66-41d4-7113-a60e-d07c17c8179c",
  "verdict": "accept",
  "digest": "sha256:dd3b91176409a196e5618c2edb305c948422342fb8ed77c4cd131b65582db5a3"
}
```
