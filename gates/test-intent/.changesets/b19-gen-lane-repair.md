---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b19-gen-lane-repair
---

# Generation lane repair test intent

The 24 original lane entries continue below with refreshed whole-file proxy evidence.
The original changeset and all earlier evidence remain as history, including the independent
rejection. The five new repair checks fail by assertion on 12da89651 with no production overlays.
The two added preservation checks pass before and after. No builder acceptance is recorded.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/GenerationAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "Expired or cancelled internal exchanges never dispatch later and leave parent work uncancelled; a blocked resume probe is DEFERRED; HTTP and SSE context overflow is typed, unrelated HTTP 500 remains generic; lifecycle closure refuses waiters without counting active GENERATE leases.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V1",
          "quote": "Every managed generation sends to the serving llama-server through one admission lane: internal chat, RAG, agents, ASSIST helpers, VDU, GPL, the post-install smoke, the resume one-token probe and the OpenAI-compatible proxy.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V3",
          "quote": "The lane honours the foreground queue and INFERENCE_BUSY contract from f8c4967ed (merged into this branch), with foreground before background and bounded aging, so a background holder cannot hide a foreground waiter (objection 4). Each exchange carries an absolute deadline captured before submission. The deadline covers the wait in the lane and the upstream headers. Expiry while waiting means no dispatch ever, the lane slot is released, and the caller gets its existing fallback (QU, filter, expansion, rewrite, sufficiency) or INFERENCE_BUSY. Expiry of an optional exchange does not cancel the parent search (C3). Waiters are not counted as active generation leases (keeps v1 D3).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V6",
          "quote": "A probe that could not be admitted in its window returns DEFERRED, not unhealthy. DEFERRED is never treated as a failure and never stops or relaunches the server (C16).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V7",
          "quote": "The server's context-exceeded error is recognised precisely, both as an HTTP 500 body and as an in-stream SSE error envelope (C11). For internal callers it maps to a typed CONTEXT_EXCEEDED error (\"the request is larger than the model's context window\"), not a generic LLM_ERROR. The agent's generic retry does not retry it (C12). The proxy forwards the upstream status and body unchanged, so its clients keep OpenAI-compatible semantics.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "A10",
          "quote": "cancel or disconnect while waiting or streaming. No later answer, and the lane is freed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "A12",
          "quote": "stop, drain, self-test or reload while callers wait. Lifecycle rules apply, and nothing leaks.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-gen-lane.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/GenerationAdmissionTest.java#expiredExchangeNeverDispatchesAndDoesNotCancelParent",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/GenerationAdmissionTest.java#cancelledFutureWhileWaitingNeverDispatchesLater",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/GenerationAdmissionTest.java#probeBlockedPastItsWindowIsDeferredAndNeverDispatched",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/GenerationAdmissionTest.java#contextHttp500IsTypedAndUnrelated500KeepsItsBody",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/GenerationAdmissionTest.java#sseContextErrorAfterRoleContentReasoningAndToolsIsTyped",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/GenerationAdmissionTest.java#lifecycleClosureRefusesWaiterWithoutCountingItAsGenerating"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java"
      ],
      "class": "New",
      "behaviour": "Proxy/proxy and proxy/internal exchanges serialize in both directions; foreground precedes background and aging bounds starvation, including an internal background holder; durable disconnect updates urgency; explicit cancellation and expiry prevent late dispatch; n>1 and id_slot receive 400; upstream errors retain status and bytes; body cancellation and a finite wall deadline free the lane; drain refuses waiting proxies and repeated exchanges leak no active lease.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V1",
          "quote": "Every managed generation sends to the serving llama-server through one admission lane: internal chat, RAG, agents, ASSIST helpers, VDU, GPL, the post-install smoke, the resume one-token probe and the OpenAI-compatible proxy.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V3",
          "quote": "The lane honours the foreground queue and INFERENCE_BUSY contract from f8c4967ed (merged into this branch), with foreground before background and bounded aging, so a background holder cannot hide a foreground waiter (objection 4). Each exchange carries an absolute deadline captured before submission. The deadline covers the wait in the lane and the upstream headers. Expiry while waiting means no dispatch ever, the lane slot is released, and the caller gets its existing fallback (QU, filter, expansion, rewrite, sufficiency) or INFERENCE_BUSY. Expiry of an optional exchange does not cancel the parent search (C3). Waiters are not counted as active generation leases (keeps v1 D3).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V4",
          "quote": "The lane is released when the upstream exchange has ended: completed, aborted with the connection closed, or timed out. A proxy client that reads slowly must not hold the lane after model work ends (C10): either a finite proxy wall deadline including the body, or release once upstream completes while the bytes stay buffered within a bound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V5",
          "quote": "Requests for several completions (n>1) or a pinned id_slot are refused with a clear 400.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V7",
          "quote": "The server's context-exceeded error is recognised precisely, both as an HTTP 500 body and as an in-stream SSE error envelope (C11). For internal callers it maps to a typed CONTEXT_EXCEEDED error (\"the request is larger than the model's context window\"), not a generic LLM_ERROR. The agent's generic retry does not retry it (C12). The proxy forwards the upstream status and body unchanged, so its clients keep OpenAI-compatible semantics.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "A10",
          "quote": "cancel or disconnect while waiting or streaming. No later answer, and the lane is freed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "A12",
          "quote": "stop, drain, self-test or reload while callers wait. Lifecycle rules apply, and nothing leaks.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "A19",
          "quote": "repeated use, with finished and aborted streams, leaks no lane, lease or thread.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "C17",
          "quote": "C17 (a durable foreground run that disconnects becomes background with the same deadline; explicit cancel stops it)",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b19-gen-lane-proxy-refresh.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#twoProxiesNeverOverlapTheirUpstreamExchanges",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#proxyAndInternalChatShareTheSameLaneInBothDirections",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#foregroundProxyPassesBackgroundWaiterBehindBackgroundInternalHolder",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#agedBackgroundPassesNewForegroundProxy",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#durableProxyDisconnectBecomesBackgroundWhileWaiting",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#cancelledProxyWaiterNeverDispatches",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#expiredProxyWaiterIsBusyNeverDispatchedAndDoesNotCancelParent",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#proxyRejectsMultipleSequencesAndSlotPins",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#cancellingHandedOffProxyBodyFreesLaneBeforeDownstreamClose",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#finiteWallDeadlineFreesLaneForSlowProxyReader",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#drainingRefusesProxyWaitersAndRepeatedExchangesLeakNothing"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentContextOverflowTest.java"
      ],
      "class": "New",
      "behaviour": "Context overflow before or after partial output terminates the actual agent LLM caller after one attempt with CONTEXT_EXCEEDED and no generic retry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V7",
          "quote": "The server's context-exceeded error is recognised precisely, both as an HTTP 500 body and as an in-stream SSE error envelope (C11). For internal callers it maps to a typed CONTEXT_EXCEEDED error (\"the request is larger than the model's context window\"), not a generic LLM_ERROR. The agent's generic retry does not retry it (C12). The proxy forwards the upstream status and body unchanged, so its clients keep OpenAI-compatible semantics.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-gen-lane.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentContextOverflowTest.java#oversizedExchangeIsNeverRetriedEvenAfterPartialOutput"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/GenerationHelperDeadlineTest.java"
      ],
      "class": "New",
      "behaviour": "QU and rewrite capture their own absolute submission budget, preserve parent work and return their existing raw-question fallback when inference is refused.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V3",
          "quote": "The lane honours the foreground queue and INFERENCE_BUSY contract from f8c4967ed (merged into this branch), with foreground before background and bounded aging, so a background holder cannot hide a foreground waiter (objection 4). Each exchange carries an absolute deadline captured before submission. The deadline covers the wait in the lane and the upstream headers. Expiry while waiting means no dispatch ever, the lane slot is released, and the caller gets its existing fallback (QU, filter, expansion, rewrite, sufficiency) or INFERENCE_BUSY. Expiry of an optional exchange does not cancel the parent search (C3). Waiters are not counted as active generation leases (keeps v1 D3).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-gen-lane.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/GenerationHelperDeadlineTest.java#queryUnderstandingSubmitsItsOwnBudgetWithoutChangingParent",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/GenerationHelperDeadlineTest.java#rewriteSubmitsItsOwnBudgetAndFallsBackToRawQuestion"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java"
      ],
      "class": "New",
      "behaviour": "A resume probe blocked beyond its admission window returns DEFERRED and does not stop, replace or relaunch the process.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V6",
          "quote": "A probe that could not be admitted in its window returns DEFERRED, not unhealthy. DEFERRED is never treated as a failure and never stops or relaunches the server (C16).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-gen-lane.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#aProbeThatCannotEnterTheLaneIsDeferredWithoutStopOrRelaunch"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/OnlineModeOpsTest.java"
      ],
      "class": "New",
      "behaviour": "The existing held-lane vision timeout assertion now requires the typed INFERENCE_BUSY reason rather than an untyped timeout message; all other existing expectations remain unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V3",
          "quote": "The lane honours the foreground queue and INFERENCE_BUSY contract from f8c4967ed (merged into this branch), with foreground before background and bounded aging, so a background holder cannot hide a foreground waiter (objection 4). Each exchange carries an absolute deadline captured before submission. The deadline covers the wait in the lane and the upstream headers. Expiry while waiting means no dispatch ever, the lane slot is released, and the caller gets its existing fallback (QU, filter, expansion, rewrite, sufficiency) or INFERENCE_BUSY. Expiry of an optional exchange does not cancel the parent search (C3). Waiters are not counted as active generation leases (keeps v1 D3).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-gen-lane.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/OnlineModeOpsTest.java#visionCompletion_timesOutWhenLockHeld"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchProcessorTest.java"
      ],
      "class": "New",
      "behaviour": "INFERENCE_BUSY defers a document, leaves it remaining and does not persist a failure.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V3",
          "quote": "The lane honours the foreground queue and INFERENCE_BUSY contract from f8c4967ed (merged into this branch), with foreground before background and bounded aging, so a background holder cannot hide a foreground waiter (objection 4). Each exchange carries an absolute deadline captured before submission. The deadline covers the wait in the lane and the upstream headers. Expiry while waiting means no dispatch ever, the lane slot is released, and the caller gets its existing fallback (QU, filter, expansion, rewrite, sufficiency) or INFERENCE_BUSY. Expiry of an optional exchange does not cancel the parent search (C3). Waiters are not counted as active generation leases (keeps v1 D3).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "A17",
          "quote": "caller-specific deadlines; VDU does not record transient lane contention as a failed document.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-gen-lane.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchProcessorTest.java#inferenceBusyLeavesDocumentRemainingWithoutPersistingFailure"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplJobCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "INFERENCE_BUSY defers synthetic-query generation instead of returning a failed document.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V3",
          "quote": "The lane honours the foreground queue and INFERENCE_BUSY contract from f8c4967ed (merged into this branch), with foreground before background and bounded aging, so a background holder cannot hide a foreground waiter (objection 4). Each exchange carries an absolute deadline captured before submission. The deadline covers the wait in the lane and the upstream headers. Expiry while waiting means no dispatch ever, the lane slot is released, and the caller gets its existing fallback (QU, filter, expansion, rewrite, sufficiency) or INFERENCE_BUSY. Expiry of an optional exchange does not cancel the parent search (C3). Waiters are not counted as active generation leases (keeps v1 D3).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V1",
          "quote": "Every managed generation sends to the serving llama-server through one admission lane: internal chat, RAG, agents, ASSIST helpers, VDU, GPL, the post-install smoke, the resume one-token probe and the OpenAI-compatible proxy.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-gen-lane.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplJobCoordinatorTest.java#inferenceBusySyntheticQueryDefersInsteadOfReturningFailedDocument"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/NativeContextOverflowTest.java",
        "modules/ui/src/test/java/io/justsearch/agent/NativeContextOverflowTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The native b8571 typed HTTP400 is CONTEXT_EXCEEDED in HTTP and SSE for both prompt-size messages, while unrelated HTTP400/500 retain their body and status handling. Real internal FreeChat gets readable CONTEXT_EXCEEDED and no done. A real agent caller makes one upstream attempt, including the native HTTP path. A disconnected queued proxy socket leaves before the holder releases; no upstream ghost dispatch occurs and the fresh request follows immediately.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b19 builder repair contract",
          "quote": "An individually oversized prompt gets the typed CONTEXT_EXCEEDED outcome from the server's own check (V7), not a pre-check."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b19 builder repair contract",
          "quote": "A waiter whose client is gone must leave the lane without dispatch, as early as the server stack can detect it."
        }
      ],
      "evidence": "gates/test-intent/evidence/b19-gen-lane-repairs.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/NativeContextOverflowTest.java#native400ClassifierRecognizesBothInputShapesAndSse",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/NativeContextOverflowTest.java#native400InternalHttpAndPartialSseAreTyped",
        "modules/ui/src/test/java/io/justsearch/agent/NativeContextOverflowTest.java#native400ReachesInternalChatAsReadableContextExceeded",
        "modules/ui/src/test/java/io/justsearch/agent/NativeContextOverflowTest.java#native400AgentMakesExactlyOneUpstreamAttempt",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#disconnectedSocketWaiterLeavesBeforeHolderEndsAndNeverDispatches"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java"
      ],
      "class": "New",
      "behaviour": "An internal stream whose work subscriber cancels while queued has no upstream dispatch or answer, and releases ownership for the next request. Socket polling preserves live pipelined requests and keep-alive. Both are preservation checks on 12da89651 and the candidate. The evidence producer returns 1 only because these before-state cases pass rather than failing by assertion; neither is classified as a repair.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b19 builder repair contract",
          "quote": "Apply the same to internal streams whose subscriber cancels while queued."
        },
        {
          "kind": "delegated-choice",
          "delegationLocation": "task t-20261005-b6d536 user turn b19 builder repair contract",
          "delegationQuote": "Find how the proxy's HTTP layer exposes a closed client while the handler waits, and check it again just before dispatch.",
          "delegationScope": "Implementing closed-client detection without changing live request handling.",
          "choice": "Keep pipelined bytes in the existing Jetty request buffer, and pin that live pipelined requests keep both responses.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b19-gen-lane-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#cancelledInternalStreamWaiterNeverDispatchesOrAnswers",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#socketProbePreservesPipelinedRequestsAndKeepAlive"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gl2-20261007",
  "verdict": "reject",
  "digest": "sha256:31206cc314daf9bb13b4de25bf69af1cc7f72cc9f8ed85aba89974b0d2c582fc",
  "note": "Five repair assertions fail 12da89651 and pass 88fdaa424; preservation and refreshed evidence validate. Live native overflow and agent one-attempt retry pass; O1 queued FIN and 20 O2 cycles pass their ghost/leak checks. Rejected on real-interface check 3: a write-half-closed valid client receives empty HTTP200 instead of an answer, and a fully closed pipelined client still dispatches its 24016-token request (522 newly prefilled), delaying fresh completion to 8.293s. See F:/agent-sandbox/design-b6d536/codex/verify-gl2/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gl3-20261008",
  "verdict": "accept",
  "digest": "sha256:31206cc314daf9bb13b4de25bf69af1cc7f72cc9f8ed85aba89974b0d2c582fc"
}
```

```json
{
  "kind": "tie-break",
  "role": "verifier",
  "session": "tiebreak-b19-20261008",
  "verdict": "reject",
  "digest": "sha256:31206cc314daf9bb13b4de25bf69af1cc7f72cc9f8ed85aba89974b0d2c582fc",
  "note": "Reject entries 1, 2 and 10: uncancelled-parent mocks stay green after verified cancel calls, and live pipelined response-body loss passes the preservation oracle; 31 candidate checks pass and exactly five repair checks fail 12da89651. See F:/agent-sandbox/design-b6d536/codex/tiebreak-b19/report.md."
}
```
