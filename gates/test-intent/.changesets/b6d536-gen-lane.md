---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b12-gen-lane
---

# Test-intent entries

Sources below quote the binding owner-adopted decisions and scenarios in
F:/agent-sandbox/design-b6d536/gen-admission/design-v2.md, provided in the builder role contract.
All added expectations are New checks pinning repaired behavior, including the changed vision
busy oracle. Existing tests outside these named additions are preserved.

The standard evidence producer runs each of the 24 methods separately on the candidate and on
2fda782ac. Its before-state receives two minimal test-only API carriers: GenerationDeadline
(scoped budget carrier, with no lane scheduling) and ContextExceededException (the typed carrier
used by the agent fake). The tests otherwise use existing transport/lifecycle seams and real
fake HTTP endpoints. No candidate admission, proxy, retry or lifecycle implementation is overlaid.
The producer is adapted to create its detached worktree under the authorised worktrees directory,
to avoid frontend junction creation for JVM-only evidence, and to copy native DLLs to a fresh
private directory per Gradle run. These script changes are governance tooling, outside product
test-intent scope.

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
      "evidence": "gates/test-intent/evidence/b6d536-gen-lane.json",
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
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance. The builder does not accept its own expectations.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gl-20261007",
  "verdict": "accept",
  "digest": "sha256:59d06c01cc7c97d1b84d9d0df31cda1932a14cbe02d4d22f714b51d1457545dc",
  "note": "Independent run: all 24 new checks passed on 2c616def0 and failed by the intended assertion on 2fda782ac, using only the declared test carriers. This accepts the test intent; live outcome verification is separate."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gl-20261007",
  "verdict": "reject",
  "digest": "sha256:59d06c01cc7c97d1b84d9d0df31cda1932a14cbe02d4d22f714b51d1457545dc",
  "note": "Real bundled-server A8 check failed: individual 36030-token input at 32768 returns HTTP400 exceed_context_size_error, internal chat emits LLM_ERROR, and the real agent repeats the 38133-token upstream error three times before LLM_TRANSIENT. Earlier acceptance covered only the 24 automated oracle changes. Full gate also has candidate-added stale evidence. See F:/agent-sandbox/design-b6d536/codex/verify-gl/report.md."
}
```
