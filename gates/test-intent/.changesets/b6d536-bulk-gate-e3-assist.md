---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, item 5, Spec E step E2, ASSIST and VDU classification, on b6d536/bulk-gate-2
(before-state f5c7df712, the E2 commit). Reconciliation v2: "Chat generating (HUMAN_CHAT,
AGENT_CHAT, BACKGROUND GENERATE leases; not ASSIST)". The chat server's tracker gains an ASSIST
lease kind: like AUX it keeps a drain waiting and fails fast while admission is closed (it never
waits), and it is not counted in `generating()` or `inFlight()`, so it neither moves the bulk-work
gate to the duty floor nor enters pacing. Query understanding, filter normalization, context
sufficiency, query expansion and the follow-up rewrite declare ASSIST at the call site through
`DemandScope.call(DemandKind.ASSIST, ...)`, which the production OnlineAiService reads on the
calling thread; their `chatCompletion` calls are unchanged. VDU's own vision and enrichment calls
open a VDU lease, declared through `DemandScope.bulk(BulkWorkKind.VDU, ...)`, which keeps a drain
waiting and keeps the work handle like GENERATE but is not counted (Spec E §1.2: "ASSIST and VDU
leases block drains (`closeIfIdle`) but are **not counted** in `generating()`/`inFlight()`";
F19 "VDU excluded from `chat_generating`"); DemandKind, a closed vocabulary, is unchanged. GPL
stays GENERATE and counts, by the coordinator's ruling.

Evidence files (all written by run-evidence.mjs):

- `b6d536-bulk-gate-e3-assist-repaired.json`: fails by assertion on f5c7df712 (verdict DUTY_FLOOR
  [CHAT_GENERATING] while query understanding was open, expected RUN).
- `b6d536-bulk-gate-e3-vdu-repaired.json`: fails by assertion on f5c7df712 (verdict DUTY_FLOOR
  [CHAT_GENERATING] while a VDU vision completion was open, expected RUN).
- `b6d536-bulk-gate-e3-assist-preservation.json`: unchanged checks passing on f5c7df712 and on the
  change: a search during a deactivate drain answers without expansion through the real tracker;
  query understanding, filter normalization and context sufficiency degrade like AI off when the
  draining gate refuses; a chat answer and a background generation still slow indexing (E-S7a,
  E-S7b). It exits 1 by design because it shows no repair.
- `b6d536-bulk-gate-e3-assist-new.json`: the tracker checks use the ASSIST and VDU kinds added
  here, so they have no before-state.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QueryAssistDoesNotSlowIndexingTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Composed as the Engine composes it (Engine root, the inference manager from BootstrapInferenceFactory, the production OnlineAiServiceImpl and QueryUnderstandingService, a stand-in llama-server holding each completion open, and the KnowledgeServerBootstrap whose bulk-work verdict the operating status block reads): a burst of five query-understanding calls with no chat reply keeps the verdict RUN for 1.5 s while a completion is open at the server, and after they finish. Before the change every completion opened a GENERATE lease, so the verdict went to DUTY_FLOOR [CHAT_GENERATING].",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "Short query-assist completions (query understanding, filter normalization, context sufficiency, expansion, follow-up rewrite) are ASSIST and are not chat generating (reconciliation v2 line 43); the caller declares the kind in a scope so the existing chatCompletion signature is unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-assist-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QueryAssistDoesNotSlowIndexingTest.java#queryUnderstandingBurstLeavesIndexingRunning"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/VduGenerationDoesNotSlowIndexingTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Composed as the Engine composes it (Engine root, the inference manager from BootstrapInferenceFactory, the production OnlineAiServiceImpl and VduProcessor, a stand-in llama-server holding the vision completion open, and the KnowledgeServerBootstrap whose bulk-work verdict the operating status block reads): a VDU document processed with a background context and no chat reply keeps the verdict RUN for 1.5 s while its page's vision completion is open at the server; the document then completes. Before the change the VDU completion opened a GENERATE lease, so the verdict went to DUTY_FLOOR [CHAT_GENERATING].",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "VDU's own generation is bulk work, not chat generating (Spec E 1.2 and F19): VduProcessor declares BulkWorkKind.VDU around its vision and enrichment calls and the chat server opens VDU leases that hold drains but are not counted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-vdu-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/VduGenerationDoesNotSlowIndexingTest.java#vduGenerationLeavesIndexingRunning"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/AssistLeaseTest.java"
      ],
      "class": "New",
      "behaviour": "An ASSIST ticket queued with a work handle and admitted is not generating, adds nothing to inFlight, delivers no edge and does not enter pacing; while it is open an idle unload defers (closeIfIdle returns null) and a zero-bound drain times out and reopens admission; once it closes a drain succeeds and a new ASSIST admission fails fast while the hold is open. A VDU ticket keeps its work handle (lifetime entered and released once) like GENERATE, but its lease is not generating, adds nothing to inFlight and delivers no edge, while an idle unload defers and a zero-bound drain times out; a GENERATE lease still counts.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "ASSIST keeps the drain semantics of AUX (reconciliation v2 F13: AUX and ASSIST never wait and fail fast in DRAINING, STARTING and STOPPING; inFlight counts GENERATE leases only).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-assist-new.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/AssistLeaseTest.java#assistLeasesHoldDrainsAndFailFastButNeverGenerateOrPace",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/AssistLeaseTest.java#vduLeasesHoldDrainsButAreNeverCountedAsGenerating"
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
  "session": "a3a46d49-5d40-43d9-a1ad-d9e9e536d01b",
  "verdict": "accept",
  "digest": "sha256:7f661bc7002d6379e8159b8de1309fd6894526cf32d74afcb281827efae9ae80"
}
```
