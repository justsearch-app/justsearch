---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-run-domain
---

# Run owner preservation checks

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve run dispatch, activity, decisions, steering outcomes and identity, reattachment, approval, Halt, synchronous first notifications, duplicate terminals, acknowledgement latching, consecutive held gates and reconnect. The owner probes run without mounting components against AgentSessionController's injected HaltBackend transport. The baseline fallback invokes the original view-owned handlers with identical assertions; it is test scaffolding only. Both files pass on 9538356c46 with unchanged production sources. The new file is explicitly covered because existing Ask tests read sibling test sources.",
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
          "choice": "Pin dispatch notification ordering, terminal idempotence, acknowledgement latching, held-gate reset, reconnect and SW-RUN-01 through SW-RUN-11 against unchanged base behavior, then redirect the moved private update handler without changing its assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-run-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts#opens the delegated turn before a synchronous first notification and settles duplicate terminals once",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts#keeps acknowledgement latched when the server evidence disappears",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts#resets budget choice count and latch after a run ends with its gate still held",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts#reconnects the retained window without another adoption or stopping delegated work",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-01 opens its turn before dispatch and settles duplicate terminals once",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-02 projects actual tool activity and leaves an unreported step count unknown",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-03 routes a held budget choice through the issuance seam",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-04 concurrent steering stays on one run and opens no competing turn",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-05 applied queued not applied and unconfirmed directions remain distinct",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-06 an unrelated report cannot settle either of two identical lost sends",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-07 a lost steer note survives terminal until its own report arrives",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-08 absence creates no run and reload adopts a matching live run once without navigation",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-09 duplicate approval requests share one lookup and stale details cannot prompt a replacement",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-10 a failed approval remains an approval error in the owner feed",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-11 Stop keeps both windows observing output until the backend terminal"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split"
      ],
      "whatMoved": "HO8 a held real controller keeps its decision and reopens from the sidebar: call runDomain.onAgentUpdate instead of the moved SearchV3View.onAgentUpdate; inputs, observation and assertions are unchanged.",
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
          "choice": "Pin dispatch notification ordering, terminal idempotence, acknowledgement latching, held-gate reset, reconnect and SW-RUN-01 through SW-RUN-11 against unchanged base behavior, then redirect the moved private update handler without changing its assertions.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
