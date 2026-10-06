---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5 (Spec A step 3, reconciliation F1, F13 and F15): one
generation tracker (`GenerationTracker`) replaces the generative request gate. Every llama-server
exchange holds a lease: GENERATE for chat, vision and agent replies, in-process and through the
Engine's `/v1` proxy; AUX for tokenize, templates and `/v1/models`. The tracker publishes the
generating signal (edges, epoch, idle time, queued separately) and drains by the Spec A §3.4 row of
each stop reason. Eval scripts that called llama-server directly now default to the proxy. The
runtime reconciler's controller-less constructors have no production caller left; they are kept,
deprecated, so earlier fail-before evidence stays reproducible (Spec A step 12 removes them). This
changeset covers only this slice's files.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProxyStreamAcrossReconfigureTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StreamingFakeLlamaServer.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "I3, aReconfigureDuringAProxiedStreamCompletesTheReplyThenRelaunches: the manager BootstrapInferenceFactory builds, the /v1 proxy wired as CoreApiAssembly wires it, and llama-server as a separate process (StreamingFakeLlamaServer: it records each launch and each completed reply in events.txt and holds every chat reply open after its first event until the test releases it). With chat online and a streamed reply open through the proxy, applyConfig to another model (RESTART_IF_ONLINE) starts; for 5 s nothing relaunches and the apply has not returned; after release the client reads the reply through its last event and [DONE], then the apply completes, the target launches once, the first server's reply completion is recorded before the relaunch, and the manager ends online on the target. On the before-state the proxy's exchange held no lease the drain waited for, so the relaunch cut the open reply.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 5.2 and reconciliation F13 as the builder implements them in step 3: the proxy admits each exchange through the lifecycle manager's generation tracker before it reaches llama-server (GENERATE for chat completions, AUX for /v1/models), keeps the lease until the client's stream closes, and answers 503 when admission is closed; a reconfigure drains these leases like in-process replies, so it waits for an open proxied reply (up to the drain bound) before it relaunches.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step3-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProxyStreamAcrossReconfigureTest.java#aReconfigureDuringAProxiedStreamCompletesTheReplyThenRelaunches"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaExchangeLeaseScanTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "U14 source scan. theExchangeRequestBuilderRequiresALease: InferenceHttpHelpers.buildJsonPostRequest takes the exchange's GenerationLease first and refuses null. everyInferenceExchangePassesItsLeaseAndOnlyProbesGoWithout: in app-inference main, every buildJsonPostRequest call passes lease (at least six exchanges found), no class but InferenceHttpHelpers builds a request, and buildGetRequest is used only for PATH_HEALTH and PATH_PROPS. theProxyAdmitsEachExchangeBeforeItReachesLlamaServer: OpenAiCompatController calls admission.admit before its one client.send, and a chat completion is a GENERATE lease. evalScriptsReachTheChatModelThroughTheProxy: no line in scripts/jseval/jseval names port 8080 together with llm, llama, --port or judge. On the before-state all four failed: the builder took no lease, the proxy had no admission, and five eval defaults pointed at llama-server's own 8080. app-inference's test task declares the scanned sources as inputs so a cached result is not replayed after they change.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Reconciliation F13 as the builder implements it: every llama HTTP call on the proxy, agent and token paths is inside a lease, enforced by making the lease a parameter of the only exchange request builder; lifecycle probes (/health, /props) are the only lease-free requests; the U14 scan covers scripts/jseval, whose direct callers (tier2-eval, judge-ceiling, run_tier2_eval, qu-spike, qu-v3) now default to the Engine's /v1 proxy.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step3-repaired.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaExchangeLeaseScanTest.java#theExchangeRequestBuilderRequiresALease",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaExchangeLeaseScanTest.java#everyInferenceExchangePassesItsLeaseAndOnlyProbesGoWithout",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaExchangeLeaseScanTest.java#theProxyAdmitsEachExchangeBeforeItReachesLlamaServer",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaExchangeLeaseScanTest.java#evalScriptsReachTheChatModelThroughTheProxy"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaExchangeLeaseScanTest.java"
      ],
      "class": "New",
      "behaviour": "U14, noOtherMainClassNamesALlamaServerExchange: every main class under modules/*/src/main/java that names a llama-server exchange path literal (chat and text completions, tokenize, detokenize, apply-template, /v1/models, embeddings, infill, slots, rerank) is one of six accounted for, each with its reason: the two lease-checked in-process owners, the lease-checked proxy, the proxy route registration, the install self-test's own private server and the system-tests chaos client of an external server. This passes on the before-state as well (no other caller existed); it pins that a new caller has to come through a lease.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Reconciliation F13 as the builder implements it: the U14 scan names every main class that may reach a llama-server exchange path and why, so a new direct caller fails the scan until it holds a lease.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step3-unit.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaExchangeLeaseScanTest.java#noOtherMainClassNamesALlamaServerExchange"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/ChatDrainPolicyTest.java"
      ],
      "class": "New",
      "behaviour": "U5, against the new ChatDrainPolicy and GenerationTracker (new classes, so there is no before-state). everyStopReasonHasItsSpecRow: every StopReason maps to its Spec A §3.4 row (wait mode, bound, expiry); a reason without an asserted row fails. nowStopsAtOnceWithAReplyOpen: QUIT closes admission and returns with a reply still open. refuseBusyReopensAdmissionWhenTheBoundRunsOut: MODEL_SWITCH with a 50 ms bound refuses with 'did not drain', admission is open again and the reply keeps running. deactivateProceedsWhenTheBoundRunsOutWithAdmissionStillClosed. residencyPressureNeverStopsWithALeaseOpen: RESIDENCY_PRESSURE and IDLE_UNLOAD refuse with a GENERATE or an AUX lease open and change nothing, and close admission at once when idle. vduExitWaitsWithoutABoundForTheReply: still waiting after 200 ms, returns once the reply closes. whileServingKeepsTheRowAndOnlyMarksIt.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 3.4 as the builder implements it in step 3: the table is one exhaustive switch over StopReason; the controller's stop steps (deactivate, apply, VDU enter and exit) wait only while a server is serving, so a stop during a failed start or recovery closes admission without waiting on a server that is gone; prepare and recovery keep the bounded refuse-when-busy drain they had.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step3-unit.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/ChatDrainPolicyTest.java#everyStopReasonHasItsSpecRow",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/ChatDrainPolicyTest.java#nowStopsAtOnceWithAReplyOpen",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/ChatDrainPolicyTest.java#refuseBusyReopensAdmissionWhenTheBoundRunsOut",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/ChatDrainPolicyTest.java#deactivateProceedsWhenTheBoundRunsOutWithAdmissionStillClosed",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/ChatDrainPolicyTest.java#residencyPressureNeverStopsWithALeaseOpen",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/ChatDrainPolicyTest.java#vduExitWaitsWithoutABoundForTheReply",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/ChatDrainPolicyTest.java#whileServingKeepsTheRowAndOnlyMarksIt"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java"
      ],
      "class": "New",
      "behaviour": "U6 and U7 against the new GenerationTracker (no before-state). U7, generateLeasesDriveOneEdgePairPerBusyPeriodAndAuxNeverGenerates: an AUX lease is not generating, counts in no inFlight and opens no edge; overlapping GENERATE leases make one busy period (epoch 1, idleSince MIN_VALUE while generating); a duplicate close does not end it; the last close sets idleSince from the clock; the next busy period is epoch 2; listeners see true@1, false@1, true@2, false@2 in order. edgesRunOnTheDeliveryExecutorNotTheLeaseHoldersThread. queuedTicketsCountSeparatelyAndADrainDoesNotWaitForThem: a queued ticket counts in queued, not inFlight or generating; a drain returns at once over it and the ticket's admission then fails fast and stops counting. aGenerateTicketWithWorkEntersPacingOnQueueAndReleasesItOnce, and an AUX exchange never paces. U6, closeIfIdleAndAdmissionRaceWithoutEverClosingOverAnOpenLease: 1000 races of admit(GENERATE) against closeIfIdle; each time exactly one wins, an unload hold never coexists with a lease, and admission reopens. nestedHoldsKeepAdmissionClosedUntilTheLastCloses.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 5.2 with reconciliation F1 and F13 as the builder implements them: one monitor guards admission, the lease counts, the queue and the edges; the lease opens after the request lock; inFlight counts GENERATE leases only and AUX is not generating; queued is separate and drains do not wait for it; edges are delivered in order on an executor, never on the lease holder's thread; closeIfIdle checks leases and closes admission in one step, so an idle unload can never take a server with a reply in flight.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step3-unit.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java#generateLeasesDriveOneEdgePairPerBusyPeriodAndAuxNeverGenerates",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java#edgesRunOnTheDeliveryExecutorNotTheLeaseHoldersThread",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java#queuedTicketsCountSeparatelyAndADrainDoesNotWaitForThem",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java#aGenerateTicketWithWorkEntersPacingOnQueueAndReleasesItOnce",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java#closeIfIdleAndAdmissionRaceWithoutEverClosingOverAnOpenLease",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java#nestedHoldsKeepAdmissionClosedUntilTheLastCloses"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/GenerativeRequestGateTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java"
      ],
      "class": "Adaptation",
      "moved": ["rename", "signature"],
      "whatMoved": "GenerativeRequestGate is replaced by GenerationTracker, so its three tests move into GenerationTrackerTest with the same names and oracles: replacementRefusesNewUsersAndWaitsForIssuedLease, drainTimeoutReopensAdmissionWithoutDestroyingHolder, recoveryFenceRemainsClosedAfterAnExistingCandidateHoldCloses. acquire() becomes admit(GENERATE, null); closeAndDrain(bound) becomes drain(the RECONFIGURE row with that bound), the same bounded refuse-when-busy drain; the spin on requireOpen becomes a spin on admissionOpen. drainTimeoutReopens... also asserts the reply is still generating."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/OwnedStreamCancellationTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/TokenEndpointOpsTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerLifecycleSeamTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "Construction and lease access move to the tracker; no assertion changes. OwnedStreamCancellationTest passes its counting lifetime to OnlineModeOps inside a GenerationTracker instead of beside a GenerativeRequestGate. TokenEndpointOpsTest gives TokenEndpointOps its new AUX admission argument (a no-op lease). InferenceLifecycleManagerApplyConfigTest's requestGateLease helper takes the lease through the manager's admitExchange(GENERATE, null) instead of reflecting on the requestGate field. ChatControllerLifecycleSeamTest (U4) holds its in-flight reply the same way and reads 'the relaunch drains' as admission refusing a new AUX exchange instead of reflecting on the gate's reloading flag. Both tests are also earlier changesets' fail-before checks (no-adoption at 6f7895a79, step 2 at f75486245), so they find admitExchange reflectively and, on a tree without it, fall back to the request gate exactly as before; the observation on each tree is the same admission."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerGuardrailsTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "ChatActuator gained drain(ChatDrainPolicy) beside drain(Duration); onlyChatControllerMayCallActuationPrimitives now forbids both overloads outside ChatServerControllerImpl (reconciliation F4). The rule's subject, allowlist and the other forbidden calls are unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "db91afe7-43b9-4236-ab23-1be0c3895de8",
  "verdict": "accept",
  "digest": "sha256:1b884857206e78dfd63a59fed78aeef0b869cfa61d96b41c4c3e5e9720bb1b53",
  "note": "ProxyStreamAcrossReconfigureTest and LlamaExchangeLeaseScanTest (4 of 5) fail on f75486245 for the reason under test; pass on candidate. GenerativeRequestGateTest's 3 cases moved to GenerationTrackerTest with the same names and oracles. Other adaptations are constructor or helper wiring only."
}
```
