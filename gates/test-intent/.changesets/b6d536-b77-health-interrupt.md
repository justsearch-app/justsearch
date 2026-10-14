---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b77-health-interrupt
---

# Health publishers are never interrupted; PMD additions removed

Independent verifier acceptance remains pending. The private execution adapter changes only process identity guarding, private temporary paths and junction-safe worktree handling; repository governance tooling and existing test assertions are unchanged.

The P1 PMD repair also touches two tests that b6d536-b71-bulk-throughput.md covers as New: BulkWriteChunkDeclarationTest no longer passes the DocumentFieldOps argument the JobBatchWriter constructor dropped with its unused field, and ChunkCompletenessReopenCostTest names its unused mockStatic resource `_`. Their assertions, inputs and expected outcomes are unchanged. Their evidence (gates/test-intent/evidence/b6d536-b71-bulk-throughput.json) was re-recorded by run-evidence.mjs on bc2965b52 with the before-state signature shim gates/test-intent/evidence/b6d536-b77-jobbatchwriter-signature.patch (a constructor overload that passes the session's own DocumentFieldOps, exactly the wiring the old test passed); both checks still fail there by assertion.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthPublisherInterruptTest.java"
      ],
      "class": "New",
      "behaviour": "On the Health channel a publisher is never left interrupted by an unsubscribe or channel close (the verifier OwnInterruptLeak cases with their FileChannel read), keeps an interrupt it had on entry, returns promptly with it when interrupted while waiting, waits at most about one second for a blocked observer while other subscribers still receive every event in order, and an attaching thread leaves without the interrupt its own retirement raised.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b77 builder brief under this delegation. The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 6: \"Process ownership and exit accounting (Q15, S8, SUP).\" (task request).",
          "choice": "a thread that publishes Health (or any event on any channel) never leaves publish with an interrupt it did not have on entry, and is never interrupted because some other subscriber went away; a producer's own interrupt status from before the call is preserved.",
          "label": "agent choice (orchestrator decision in the b77 builder brief)",
          "provenance": "delegationQuote was 'process ownership and exit accounting' at 'task t-20261005-b6d536 request', which is item wording, not a delegation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b77 builder brief under this delegation. The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 6: \"Process ownership and exit accounting (Q15, S8, SUP).\" (task request).",
          "choice": "a blocked, slow, throwing or unsubscribing observer never outlives its subscription, the bridge or the producer owner, and cannot hold a producer or recovery indefinitely.",
          "label": "agent choice (orchestrator decision in the b77 builder brief)",
          "provenance": "delegationQuote was 'process ownership and exit accounting' at 'task t-20261005-b6d536 request', which is item wording, not a delegation"
        }
      ],
      "conflictSearch": "CapabilityHealthBridgeObserverLifetimeTest, SseAdmissionShutdownTest, SseTransportOwnerShutdownTest, LocalApiServerTransportShutdownTest, SseStreamChannel* and HealthEventChangeRegistry* tests and the verifier probes V5, V6 and B5-H2 retain their expectations; no decision record governs which thread delivers Health frames.",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthPublisherInterruptTest.java#slowObserverUnsubscribedElsewhereLeavesProducerUninterrupted",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthPublisherInterruptTest.java#interruptibleObserverUnsubscribedElsewhereLeavesProducerUninterrupted",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthPublisherInterruptTest.java#concurrentUnsubscribeRacesNeverInterruptProducer",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthPublisherInterruptTest.java#channelCloseDuringDeliveryLeavesProducerUninterrupted",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthPublisherInterruptTest.java#producerInterruptedBeforePublishStaysInterrupted",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthPublisherInterruptTest.java#producerInterruptedWhileWaitingReturnsPromptlyWithItsInterrupt",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthPublisherInterruptTest.java#blockedObserverHoldsPublisherOnlyBrieflyAndOthersKeepOrder",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthPublisherInterruptTest.java#attachingThreadLeavesWithoutTheInterruptItsRetirementRaised"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b77-health-interrupt.json",
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/HealthProducerInterruptTest.java"
      ],
      "class": "New",
      "behaviour": "Each production synchronous Health publisher (recovery occurrence, boot recovery, Engine containment, newer data, agent session, at-rest and conversation-protection taps) publishes in every one of 50 races with a subscriber that unsubscribes during delivery, is never left interrupted, and its next FileChannel read succeeds.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b77 builder brief under this delegation. The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 6: \"Process ownership and exit accounting (Q15, S8, SUP).\" (task request).",
          "choice": "a thread that publishes Health (or any event on any channel) never leaves publish with an interrupt it did not have on entry, and is never interrupted because some other subscriber went away; a producer's own interrupt status from before the call is preserved.",
          "label": "agent choice (orchestrator decision in the b77 builder brief)",
          "provenance": "delegationQuote was 'process ownership and exit accounting' at 'task t-20261005-b6d536 request', which is item wording, not a delegation"
        }
      ],
      "conflictSearch": "CapabilityHealthBridgeObserverLifetimeTest, SseAdmissionShutdownTest, SseTransportOwnerShutdownTest, LocalApiServerTransportShutdownTest, SseStreamChannel* and HealthEventChangeRegistry* tests and the verifier probes V5, V6 and B5-H2 retain their expectations; no decision record governs which thread delivers Health frames.",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/HealthProducerInterruptTest.java#subscriberLeavingDuringDeliveryNeverInterruptsTheProducer"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b77-health-interrupt.json",
      "repaired": true
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/stream/SseStreamChannelPublisherInterruptTest.java"
      ],
      "class": "New",
      "behaviour": "Closing a channel not marked interruptible while a publisher delivers on its own thread does not interrupt that publisher.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b77 builder brief under this delegation. The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 6: \"Process ownership and exit accounting (Q15, S8, SUP).\" (task request).",
          "choice": "a thread that publishes Health (or any event on any channel) never leaves publish with an interrupt it did not have on entry, and is never interrupted because some other subscriber went away; a producer's own interrupt status from before the call is preserved.",
          "label": "agent choice (orchestrator decision in the b77 builder brief)",
          "provenance": "delegationQuote was 'process ownership and exit accounting' at 'task t-20261005-b6d536 request', which is item wording, not a delegation"
        }
      ],
      "conflictSearch": "CapabilityHealthBridgeObserverLifetimeTest, SseAdmissionShutdownTest, SseTransportOwnerShutdownTest, LocalApiServerTransportShutdownTest, SseStreamChannel* and HealthEventChangeRegistry* tests and the verifier probes V5, V6 and B5-H2 retain their expectations; no decision record governs which thread delivers Health frames.",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/stream/SseStreamChannelPublisherInterruptTest.java#closingAPlainChannelDuringCallerDeliveryDoesNotInterruptThePublisher"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b77-health-interrupt.json",
      "repaired": true
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/stream/SseStreamChannelPublisherInterruptTest.java"
      ],
      "class": "New",
      "behaviour": "On a channel not marked interruptible, 200 unsubscribe races during caller-thread delivery leave the publisher uninterrupted (preserved behaviour, passes before and after).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b77 builder brief under this delegation. The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 6: \"Process ownership and exit accounting (Q15, S8, SUP).\" (task request).",
          "choice": "a thread that publishes Health (or any event on any channel) never leaves publish with an interrupt it did not have on entry, and is never interrupted because some other subscriber went away; a producer's own interrupt status from before the call is preserved.",
          "label": "agent choice (orchestrator decision in the b77 builder brief)",
          "provenance": "delegationQuote was 'process ownership and exit accounting' at 'task t-20261005-b6d536 request', which is item wording, not a delegation"
        }
      ],
      "conflictSearch": "CapabilityHealthBridgeObserverLifetimeTest, SseAdmissionShutdownTest, SseTransportOwnerShutdownTest, LocalApiServerTransportShutdownTest, SseStreamChannel* and HealthEventChangeRegistry* tests and the verifier probes V5, V6 and B5-H2 retain their expectations; no decision record governs which thread delivers Health frames.",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/stream/SseStreamChannelPublisherInterruptTest.java#unsubscribeRacesOnAPlainChannelNeverInterruptThePublisher"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b77-health-interrupt-preservation.json"
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch6-20261012",
  "verdict": "accept",
  "digest": "sha256:2b87cf970830f2d04f5eb36cefe65edba725aee9567f718823ea440e8e141b0d",
  "note": "verify-batch6 on e331c571b: the listed new checks fail on b639e82c5 by assertion (HealthPublisherInterruptTest 8/8, HealthProducerInterruptTest 7/7 producers 50/50 races, plain-channel close 1/1; the preservation race check passes on both) and pass on the candidate. Own probes: 8 production Health publishers x 6 interrupt scenarios pass (previous fails S1-S4, S6); owned delivery keeps per-listener order, bounds a stalled listener to about 1 s, handles reentrancy and own-frame Errors, 2,000 subscribers with 500 stalled served; plain channels keep publishers uninterrupted. Suites of adapters-lucene, worker-services, app-services, app-engine, app-observability pass; live R1 with Health SSE attach and 8 SSE disconnects during recovery passes; normal stop with 3 open Health SSE attachments and a walk in flight exits 0, no kills. Observations (not blocking): a persistently slow observer costs each synchronous publish up to 1 s; an observer Error after the bounded wait is reported to nobody; an owner interrupt coinciding with channel close during an attach prefix is cleared."
}
```
