---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b81-gpu-health
---

# A stall lasts until the backlog drains; late observer Errors are logged; a game never hides memory pressure; a lease-refused unload is retried

Independent verifier acceptance remains pending. Findings G1 to G4 of the b81 builder brief were each reproduced by a new check that fails by assertion on 44a9e8aa2 and passes on the candidate.

GpuResidencyGameTest#gameOutranksExternalMemory keeps its inputs, ticks and gate-reason assertions. While a game and external memory pressure hold together, the chat receiver now hears GAME_AND_EXTERNAL_MEMORY instead of plain GAME. Plain GAME discarded the memory-pressure fact the chat controller needs. The game still outranks memory pressure in every refusal and unload reason where a game counts. This edit changes the content that b6d536-bulk-gate-e4.md's acceptance covers, so that acceptance is now stale. Its evidence file b6d536-bulk-gate-e4-new.json was re-run on the candidate for the same 23 checks, and all 23 passed. A re-review is needed.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthStalledObserverTest.java"
      ],
      "class": "New",
      "behaviour": "On the Health channel, an observer blocked in frame 1 holds that publisher at most the one-second bound. While the observer then advances one frame at a time with frames still queued, four later publishers each return in under 500 ms, uninterrupted and without an exception. Once the observer has delivered its whole backlog, the next publish waits for delivery again, so the observer has the frame when publish returns. Both observers keep sequence order. An Error that an observer throws after its publisher stopped waiting does not reach the publisher, which returns normally and uninterrupted. The Error is logged at ERROR, with its throwable, on the SseStreamChannel logger. The failed observer is retired, and the next publish still reaches the other subscriber.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "process ownership and exit accounting",
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b81 builder brief under this delegation",
          "choice": "a stalled Health listener holds a publisher at most 1 s once per stall, where a stall ends only when the listener has caught up with its backlog, not when it finishes one frame; it does not stall delivery to others; no publisher is ever interrupted or left with an interrupt it did not have; per-listener order is kept.",
          "label": "agent choice (orchestrator decision in the b81 builder brief)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "process ownership and exit accounting",
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b81 builder brief addition G4 under this delegation",
          "choice": "a listener Error that arrives after the publisher's 1 s wait has returned is made visible to the channel's owner through the existing logging, without interrupting or failing any publisher and without changing the other Health guarantees.",
          "label": "agent choice (orchestrator decision in the b81 builder brief)"
        }
      ],
      "conflictSearch": "HealthPublisherInterruptTest (blockedObserverHoldsPublisherOnlyBrieflyAndOthersKeepOrder included), SseStreamChannelPublisherInterruptTest, SseStreamChannelDeliveryOwnerTest, HealthProducerInterruptTest, CapabilityHealthBridgeObserverLifetimeTest, SseAdmissionShutdownTest and SseTransportOwnerShutdownTest keep their expectations; no decision record governs when a stalled Health observer is waited for or where a late observer Error is reported.",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthStalledObserverTest.java#slowAdvancingObserverIsNotWaitedForUntilBacklogDrains",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthStalledObserverTest.java#observerErrorAfterTheBoundedWaitReachesTheChannelOwnersLog"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b81-gpu-health.json",
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java"
      ],
      "class": "New",
      "behaviour": "The real GPU residency sampler is bound to the real chat controller as the Engine binds them. Chat leases and drains come from the real generation tracker. Running and idle, GPU chat stays loaded while a game alone is detected on P16_PLUS or with the profile unresolved. When external memory pressure then rises during the game, chat unloads with gpu_busy, and unloaded() reports GPU_BUSY. With an AUX or ASSIST lease open under external memory pressure, the unload is tried and refused, and chat stays loaded over further ticks. After the lease closes, with the pressure unchanged and no new pressure report, chat unloads once with gpu_busy. Ten more ticks bring no second unload.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "GPU co-residence, a VRAM-budget-aware policy, a semantic time budget that falls back to lexical with a reason code, and coordination with the inference lane's priority and aging.",
          "location": "task t-20261005-b6d536 request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "GPU co-residence, a VRAM-budget-aware policy, a semantic time budget that falls back to lexical with a reason code, and coordination with the inference lane's priority and aging.",
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "task item 5; the orchestrator decided the b81 builder brief under this delegation",
          "choice": "a game signal does not hide simultaneous external memory pressure: with a game active and enough low-free-memory samples to raise external pressure, chat unloads with the memory-pressure reason on P16_PLUS; an unload deferred by a tokenization or query-assistance lease is retried once the lease closes while pressure stays unchanged; no unload while a lease that must not be interrupted is open, no unload storms, user-visible residency reasons stay correct.",
          "label": "agent choice (orchestrator decision in the b81 builder brief)"
        }
      ],
      "conflictSearch": "docs/decisions/0050-gpu-co-residence-by-prevention.md (chat unloads under external pressure with chat idle; a detected game is also a trigger) agrees. ChatResidencyTest (P3/O4: a game alone on P16_PLUS or unresolved keeps chat; T22 and the lease-at-drain case), GpuResidencySamplerTest, GenerationTrackerTest (U7: an AUX close is no edge) and EngineGpuResidencyWitnessTest keep their expectations.",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#gameDoesNotMaskMemoryPressureOnP16Plus",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#gameDoesNotMaskMemoryPressureWhileTheProfileIsUnresolved",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#stablePressureRetriesUnloadAfterAuxCompletes",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#stablePressureRetriesUnloadAfterAssistCompletes"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b81-gpu-health.json",
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java"
      ],
      "class": "New",
      "behaviour": "Preserved behaviour, passing before and after the change: on P12, a game detected together with external memory pressure unloads idle chat with the game_mode reason, and unloaded() reports GAME_MODE.",
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
          "delegationQuote": "GPU co-residence, a VRAM-budget-aware policy, a semantic time budget that falls back to lexical with a reason code, and coordination with the inference lane's priority and aging.",
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "task item 5; the orchestrator decided the b81 builder brief under this delegation",
          "choice": "user-visible residency reasons stay correct.",
          "label": "agent choice (orchestrator decision in the b81 builder brief)"
        }
      ],
      "conflictSearch": "ChatResidencyTest#aGameUnloadsChatBelowSixteenGigabytesWithTheGameModeDetail keeps its expectation; no decision record conflicts.",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#gameWithMemoryPressureBelowSixteenGigabytesUnloadsForTheGame"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b81-gpu-health-preservation.json"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencyGameTest.java"
      ],
      "class": "Obsolete",
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
          "delegationQuote": "GPU co-residence, a VRAM-budget-aware policy, a semantic time budget that falls back to lexical with a reason code, and coordination with the inference lane's priority and aging.",
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "task item 5; the orchestrator decided the b81 builder brief under this delegation",
          "choice": "a game signal does not hide simultaneous external memory pressure from the chat controller (the sampler no longer emits only GAME, discarding the memory-pressure state); user-visible residency reasons stay correct.",
          "label": "agent choice (orchestrator decision in the b81 builder brief)"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. docs/decisions/0050-gpu-co-residence-by-prevention.md unloads chat under external pressure and names a detected game as a trigger, and no record says a game hides memory pressure. The obsolete expectation was that, with external pressure raised first, the receiver hears plain GAME when the game raises. It now hears GAME_AND_EXTERNAL_MEMORY. The rest of gameOutranksExternalMemory is unchanged: EXTERNAL_MEMORY before the game, EXTERNAL_MEMORY again after it, and the gate reasons GAME_MODE then GPU_BUSY_EXTERNAL. The other cases of the file are unchanged."
    }
  ]
}
```

## Acceptance records
