---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b79-executor-owner
---

# Health delivery runs only on an executor its owner supplies (C1-5/C1-16)

SseStreamChannel no longer creates an executor. The lazy Executors.newVirtualThreadPerTaskExecutor() fallback in HandoffListener.drainAsync (introduced in bc2965b52, reached by every Health channel built without an owner after e331c571b) broke ExecutorArchitectureTest's two C1-5/C1-16 cases. Interruptible and asynchronous delivery now require the executor the constructing owner supplies; production already passes the registry registration head.health-event-delivery (HealthSubstrateInit, unchanged). ExecutorArchitectureTest itself is unchanged: it failed by assertion on 2e1196050 in both cases (rawExecutorFactoriesHaveNamedOwners, rawExecutorFactoriesStayInsideTheExactOwnerAllowlist) and passes on this change; the passing run is part of the evidence file below.

Fail-before for the New entry was recorded without --before, by run-evidence.mjs run from a separate detached worktree of 2e1196050 with only SseStreamChannelDeliveryOwnerTest.java copied in (no node_modules link): the two refusal checks fail by AssertionFailedError ("Expected java.lang.IllegalStateException to be thrown, but nothing was thrown"); the owner-thread and shutdown check passes there too (preserved behaviour). That before-state evidence file is kept outside the repository with the builder report (F:/agent-sandbox/design-b6d536/codex/b79-executor-owner/evidence-new-test-before-2e1196050.json; the ExecutorArchitectureTest fail-before on the unchanged 2e1196050 tree is evidence-before-2e1196050.json beside it), so the entry is not marked repaired.

Known side effect: several of the adapted files are new checks of earlier changesets (b62, b65, b77, batch1-repair, batch1-root, containment, f1-classified-cause); their recorded evidence digests no longer match and need re-recording and re-acceptance.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/DataVersionMarkerTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineContainmentNoticeTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeConcurrentPublicationTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeCurrentStateTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeMappingFailureTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeObserverLifetimeTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeSubscriberRecoveryTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/HealthProducerInterruptTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthEventChangeRegistryTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthPublisherInterruptTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/stream/SseEnvelopeContractTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeChatCauseTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeRecoveryTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeReplayTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/AtRestHealthTapTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/BootRecoveryEmitterTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/ConversationProtectionHealthTapTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/HeadHealthEventsEmitterTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/IndexDriftHealthTapTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/ReadinessReconciledWithoutRequestTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/WorkerSnapshotTapTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/rules/RuleRunnerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/operations/OperationRecoveryNoticeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/executor/OperationExecutorImplTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsRecoveryNoticeTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/SseAdmissionShutdownTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/HealthEventStreamControllerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ReadinessTriggerCompositionTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/SseTransportOwnerShutdownTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/WorkerStatusSamplerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The no-argument HealthEventChangeRegistry() constructor is removed, because the only executor behind it was one the channel created lazily for itself (Executors.newVirtualThreadPerTaskExecutor in SseStreamChannel), which C1-5/C1-16 forbid outside the executor registry. Each call site now passes that same kind of executor itself: new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()) (fully qualified in OperationExecutorImplTest), plus the import where it was missing. Executor kind (virtual thread per task) and lifetime (shut down by the channel on close) are the ones the removed constructor had; it is now created eagerly by the test instead of lazily by the channel, and an idle virtual-thread-per-task executor holds no thread. No input, precondition, observation, fault injection, expected value, tag or timeout changed; no assertion was touched. Production keeps its registry-owned executor (HealthSubstrateInit, unchanged).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b79 builder brief under this delegation. The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 6: \"Process ownership and exit accounting (Q15, S8, SUP).\" (task request).",
          "choice": "make the owned Health delivery conform to the executor-ownership rule at its cause, without weakening the rule: the channel obtains its drainer executor from the owner that constructs it instead of creating one, and every guarantee of e331c571b stays.",
          "label": "agent choice (orchestrator decision in the b79 builder brief)",
          "provenance": "delegationQuote was 'process ownership and exit accounting' at 'task t-20261005-b6d536 request', which is item wording, not a delegation"
        }
      ],
      "conflictSearch": "docs/design/lane-f-engine-jvm/stages/C1.md (C1-5, C1-16) and docs/design/lane-f-engine-jvm/evidence/C1/executor-guards.md require every production executor to come from the registry or the named child OCR method; ExecutorArchitectureTest pins it and is unchanged. HealthPublisherInterruptTest, HealthProducerInterruptTest, SseStreamChannelPublisherInterruptTest, CapabilityHealthBridgeObserverLifetimeTest, SseAdmissionShutdownTest, SseTransportOwnerShutdownTest and LocalApiServerTransportShutdownTest keep their expectations; decision records under docs/decisions/ searched for executor or Health delivery ownership: none conflicting."
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/stream/SseStreamChannelDeliveryOwnerTest.java"
      ],
      "class": "New",
      "behaviour": "A channel never creates its own executor: asking for interruptible delivery, or building a Health registry, on a channel with no owner-supplied delivery executor is refused with IllegalStateException; an asynchronous publication on such a channel is refused before any sequence is allocated or frame retained; and on a Health channel built with an owner's executor, synchronous and asynchronous publications reach the listener only on that owner's threads, and closing the channel shuts the executor down, lets every delivery thread end and runs the owner's release hook exactly once.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b79 builder brief under this delegation. The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 6: \"Process ownership and exit accounting (Q15, S8, SUP).\" (task request).",
          "choice": "make the owned Health delivery conform to the executor-ownership rule at its cause, without weakening the rule (raw executor constructors and factories are confined to the registry and the one child OCR method, C1-5/C1-16): the channel obtains its drainer executor from the owner that constructs it and refuses to run owned delivery without one.",
          "label": "agent choice (orchestrator decision in the b79 builder brief)",
          "provenance": "delegationQuote was 'process ownership and exit accounting' at 'task t-20261005-b6d536 request', which is item wording, not a delegation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b79 builder brief under this delegation. The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 6: \"Process ownership and exit accounting (Q15, S8, SUP).\" (task request).",
          "choice": "executors or threads the owner creates are shut down with their owner (no leak across bridge or producer-owner shutdown).",
          "label": "agent choice (orchestrator decision in the b79 builder brief)",
          "provenance": "delegationQuote was 'process ownership and exit accounting' at 'task t-20261005-b6d536 request', which is item wording, not a delegation"
        }
      ],
      "conflictSearch": "docs/design/lane-f-engine-jvm/stages/C1.md (C1-5, C1-16) and docs/design/lane-f-engine-jvm/evidence/C1/executor-guards.md require every production executor to come from the registry or the named child OCR method; ExecutorArchitectureTest pins it and is unchanged. HealthPublisherInterruptTest, HealthProducerInterruptTest, SseStreamChannelPublisherInterruptTest, CapabilityHealthBridgeObserverLifetimeTest, SseAdmissionShutdownTest, SseTransportOwnerShutdownTest and LocalApiServerTransportShutdownTest keep their expectations; decision records under docs/decisions/ searched for executor or Health delivery ownership: none conflicting.",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/stream/SseStreamChannelDeliveryOwnerTest.java#interruptibleDeliveryWithoutAnOwnerSuppliedExecutorIsRefused",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/stream/SseStreamChannelDeliveryOwnerTest.java#asynchronousPublicationWithoutAnOwnerSuppliedExecutorIsRefusedBeforeSequencing",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/stream/SseStreamChannelDeliveryOwnerTest.java#healthDeliveryRunsOnTheOwnersThreadsAndEndsWithTheChannel"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b79-executor-owner.json"
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch7-20261013",
  "verdict": "accept",
  "digest": "sha256:a788d63f0a9636aa95b19a8f16fcc4770e9d668464f720063139e56bc9cf1123",
  "note": "verify-batch7 on ee3c86b19: every Architecture/ArchUnit test class (9 classes, plus UnreferencedCodeTest and dead-code-audit) passes on the candidate; on 2e1196050 only ExecutorArchitectureTest fails, 2/4, naming SseStreamChannel calling Executors.newVirtualThreadPerTaskExecutor; the rule, its allowlist and archunit stores are unchanged since 2f42b4d50. SseStreamChannelDeliveryOwnerTest: 2 refusal checks fail on 2e1196050 by assertion (no IllegalStateException), owner-thread/close check passes on both; all 3 pass on the candidate. The 31 modified tests differ from 2e1196050 only by the constructor argument. Production wiring (HealthSubstrateInit on DefaultEngineExecutorRegistry: virtual thread per task, no concurrency limit, maxInstances=1 instance cap): previous cases H1-H3, O1, O8, O10, V1, V2, V5, V6, B5-H1, B5-H2, B6-P 48/48, B6-D, B6-Q pass on both revisions with the production-wired channel (H4 fails on both, probe design); own B7-W 16/16 (drainers named head.health-event-delivery-N, no publisher interrupt in 300 unsubscribe/close races, registration and threads gone after channel close and registry close, 2,000 subscribers with 500 stalled). Suites app-observability 642, app-services 3655, app-engine 606, ui 1624 pass (48 process fixtures excluded); PMD 1 vs 1 against 2f42b4d50, none added; governance: same 6 failing gates, test-intent differences are this acceptance and earlier changesets made stale by the constructor change. Live: ee3c86b19 stopped normally with 3 Health SSE + 2 other streams open and a walk in flight: Engine exit 0, clean ordered shutdown, no kills, ports closed."
}
```
