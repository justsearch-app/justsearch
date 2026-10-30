---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-context-domain
---

# Record and context owner preservation

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-context.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.retirement.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve ordered Exclude/Restore/Include through overlapping reads and each confirmation; keep an existing unconfirmed-context notice during an overlapping successful open read until the pending write confirms its own result; partial exclusion attempts and the saved result; the existing 1-30 second backoff and online reset, with the exact registered online callback removed on disconnect before reattachment; retained occupancy without usage and runtime-window changes; current API bases and encoded IDs on reads and writes; confirmed-empty 404 history with no floor, summary, exclusions or invented prompt context, ordinary 500 read failure without a locked conversation or locked panel, and 423 lock refusal after typing and attempting Ask; no deleted conversation in the window session cache after either a late thread or history response; mixed-mode message order, blank omission and unsuccessful Structured omission; absent floors and assistant-only floor IDs. All new assertions pass against production at 7d36fb903f in a shared clone; retirement instrumentation there uses the original view members instead of the moved owner. Retirement accesses in this candidate follow the owner move. All prior assertions are retained and no catalog observations are added.",
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
          "choice": "Move record and context handling into a browser-free owner with the existing session store and transports, preserving user-visible behavior. Strengthen preservation tests after the mutation audit with explicit listener removal, empty 404 context, unlocked ordinary read failures, pending-write uncertainty, deleted-cache absence and an attempted Ask while locked; retain every prior assertion. Adapt accesses to moved members only.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-context-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#still attempts the answer exclusion after a refused question and displays the saved partial result",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps Exclude Restore Include in selection order through an overlapping open read and final confirmation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps uncertainty through an overlapping open read until the pending write confirms its own result",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#caps uncertain-history retries at thirty seconds and online resets the cadence without duplicate listeners",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps the previous occupancy when a later terminal omits usage and redraws against the current runtime window",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#uses the current API base and encoded conversation ID for initial and later record and history reads",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps history HTTP 404 empty, uncertain or locked respectively",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps history HTTP 500 empty, uncertain or locked respectively",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps history HTTP 423 empty, uncertain or locked respectively",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-context.test.ts#sends summary then individual nonblank messages in mixed-mode order, omitting unsuccessful Structured exchanges",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-context.test.ts#fails open for an absent floor without putting a divider on a neighbouring turn",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-context.test.ts#uses the saved assistant ID as the floor target when a turn has no saved question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.retirement.test.ts#S2 confirmed deletion cannot be resurrected by a late thread response",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.retirement.test.ts#S2 confirmed deletion cannot be resurrected by a late history response"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split"
      ],
      "whatMoved": "SearchV3View.delegateStop.test.ts reads contextDomain.recordNotice through contextOf instead of the moved view member. Inputs, fault injection, conditions and assertions are unchanged. The retirement cases are classified New above because the preserved assertions now also require absence from the window session cache.",
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
          "choice": "Move record and context handling into a browser-free owner with the existing session store and transports, preserving user-visible behavior. Strengthen preservation tests after the mutation audit with explicit listener removal, empty 404 context, unlocked ordinary read failures, pending-write uncertainty, deleted-cache absence and an attempted Ask while locked; retain every prior assertion. Adapt accesses to moved members only.",
          "label": "agent choice"
        }
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
  "session": "qswwvt36",
  "verdict": "accept",
  "digest": "sha256:f36a2c09964cc54467ca2d2af23dfdbb17b93b38f09ef111059871ae8e1bd5d1"
}
```
