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
      "behaviour": "The remaining owner tests cover SW-RUN-01 (dispatch and counted settlement and terminal record refresh after duplicate notifications), SW-RUN-07 (an unconfirmed steering notice survives terminal until its own authoritative report), SW-RUN-08 (absence and matching-run reattachment without navigation), SW-RUN-09 (one approval lookup for duplicate requests and stale details cannot prompt a replacement), SW-RUN-10 (approval error flag, Error label and failed-approval message), and SW-RUN-11 (arriving prose and completed tool output in both windows while stopping and after CANCELLED). They construct Sv3RunDomain with a recording host, ComposerPort, injected clock and a real AgentSessionController using HaltBackend. The four view tests added by this change cover synchronous first notifications and duplicate terminals, acknowledgement latching after server evidence disappears, a renewed automatic continuation allowance on an immediately held replacement run, and reconnect without another adoption or stopping delegated work. Evidence covers both suites; acceptance remains with the verifier. The new owner file is explicitly covered because existing Ask tests read sibling test sources.",
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
          "choice": "Retain direct owner checks for SW-RUN-01 and SW-RUN-07 through SW-RUN-11, plus view checks for dispatch notification ordering, counted terminal idempotence, acknowledgement latching, renewed automatic continuation at an immediately held replacement gate and reconnect. Remove the owner checks and their catalog observations for SW-RUN-02 through SW-RUN-06; retain their existing observations in other files and fixtures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-run-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts#opens the delegated turn before a synchronous first notification and settles duplicate terminals once",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts#keeps acknowledgement latched when the server evidence disappears",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts#renews the automatic continuation allowance when a replacement run starts at a held gate",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts#reconnects the retained window without another adoption or stopping delegated work",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-01 dispatches the opened turn and settles duplicate terminals once",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-07 a lost steer note survives terminal until its own report arrives",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-08 absence creates no run and reload adopts a matching live run once without navigation",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-09 duplicate approval requests share one lookup and stale details cannot prompt a replacement",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-10 a failed approval remains an approval error in the owner feed",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-11 Stop preserves arriving prose and tools in both windows through the halted terminal"
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
          "choice": "Retain direct owner checks for SW-RUN-01 and SW-RUN-07 through SW-RUN-11, plus view checks for dispatch notification ordering, counted terminal idempotence, acknowledgement latching, renewed automatic continuation at an immediately held replacement gate and reconnect. Remove the owner checks and their catalog observations for SW-RUN-02 through SW-RUN-06; retain their existing observations in other files and fixtures.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
