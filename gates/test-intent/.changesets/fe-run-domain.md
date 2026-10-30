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
      "behaviour": "Preserve run dispatch, activity, decisions, steering outcomes and identity, reattachment, approval, Halt, synchronous first notifications, duplicate terminals, acknowledgement latching, consecutive held gates and reconnect. The owner probes construct Sv3RunDomain directly with a recording fake host, ComposerPort, injected clock and a real AgentSessionController using HaltBackend; no Search element or fallback is loaded. Settlement and terminal record refresh are counted after duplicate notifications. Matching current approval details must prompt and POST before a delayed old lookup is released during a replacement run. The view budget check exhausts one run's automatic allowance and exercises a fresh choice and full allowance on a replacement whose first notification already holds a gate. SW-RUN-11 asserts buffered prose in the stopping owner before a tool commits it, then asserts the arriving prose and completed tool result in both owners while stopping and after CANCELLED. SW-RUN-10 asserts the approval error flag, Error label and failed-approval message, rather than accepting any note mentioning approval. In an isolated shared clone of eaeddcf30a, the original tests pass when stopping prose is discarded or approval errors are mislabeled. The strengthened tests fail on those mutations and on dropped stopping tools, omitted buffered-prose projection and discarded output at CANCELLED; the restored owner suite passes. Earlier targeted mutations covered the other rewritten owner probes, terminal wiring, and the budget count and latch independently. Regenerated evidence covers both suites with unchanged production sources; acceptance remains with the verifier. The new file is explicitly covered because existing Ask tests read sibling test sources.",
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
          "choice": "Pin dispatch notification ordering, counted terminal idempotence, acknowledgement latching, renewed automatic continuation at an immediately held replacement gate, reconnect and SW-RUN-01 through SW-RUN-11. Drive the extracted owner directly; keep component wiring checks in the view suite and prove rewritten assertions detect targeted regressions.",
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
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-02 projects actual tool activity and leaves an unreported step count unknown",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-03 routes a held budget choice through the issuance seam",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-04 concurrent steering stays on one run and opens no competing turn",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-05 applied queued not applied and unconfirmed directions remain distinct",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run-domain.test.ts#SW-RUN-06 an unrelated report cannot settle either of two identical lost sends",
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
          "choice": "Pin dispatch notification ordering, counted terminal idempotence, acknowledgement latching, renewed automatic continuation at an immediately held replacement gate, reconnect and SW-RUN-01 through SW-RUN-11. Drive the extracted owner directly; keep component wiring checks in the view suite and prove rewritten assertions detect targeted regressions.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
