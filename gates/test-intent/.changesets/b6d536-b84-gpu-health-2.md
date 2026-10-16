---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b84-gpu-health-2
---

# GPU retry delivery and Health stall coverage

The builder leaves acceptance to the independent verifier. Existing test expectations remain.
The sampler/controller fixture now uses the real lifecycle manager's drain-refusal mapping;
the server start/stop is still a stand-in, and no GPU workload is launched.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java"
      ],
      "class": "New",
      "behaviour": "Stable external memory pressure causes exactly one unload after the last lease closes even if callback submission is rejected, including rejection during registration on the controller thread. A recovery-fenced admission with zero leases causes one refused drain and no callback/retry loop. The production manager maps drain failures; only an open-lease refusal permits a no-lease retry. Notifications can fall back to the closing or registering thread but only signal the controller loop, which rechecks current pressure and performs the actuation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Autonomous design and implementation for task t-20261005-b6d536, including GPU co-residence and Health publication liveness.",
          "choice": "Stable external memory pressure causes exactly one unload after the last lease closes even if callback submission is rejected, including rejection during registration on the controller thread. A recovery-fenced admission with zero leases causes one refused drain and no callback/retry loop. The production manager maps drain failures; only an open-lease refusal permits a no-lease retry. Notifications can fall back to the closing or registering thread but only signal the controller loop, which rechecks current pressure and performs the actuation.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#stablePressureRetriesAfterNoLeaseDeliveryRejectedOnce",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#rejectionAfterLeaseClosesDuringRefusalStillRetriesFromTheControllerThread",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#fencedAdmissionDoesNotSpinResidencyRetries"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b84-sampler.json",
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java"
      ],
      "class": "New",
      "behaviour": "The eval pressure route accepts case-insensitive GAME_AND_EXTERNAL_MEMORY, round-trips the canonical signal and requested profile, and lists all four accepted signals in its invalid-signal response.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Autonomous design and implementation for task t-20261005-b6d536, including GPU co-residence and Health publication liveness.",
          "choice": "The eval pressure route accepts case-insensitive GAME_AND_EXTERNAL_MEMORY, round-trips the canonical signal and requested profile, and lists all four accepted signals in its invalid-signal response.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#combinedPressureIsAcceptedRoundTrippedAndListedInValidation"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b84-route.json",
      "repaired": true
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthStalledObserverTest.java"
      ],
      "class": "New",
      "behaviour": "After a Health observer drains its entire backlog, publishing waits for its next blocked frame again. That second stall has a fresh bounded wait, later frames skip the stall promptly, publishers are neither failed nor interrupted, and all four frames are delivered in order. An executor barrier proves backlog-drain completion before the second stall is introduced; a held callback and timed future observation prove that the producer actually waits.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Autonomous design and implementation for task t-20261005-b6d536, including GPU co-residence and Health publication liveness.",
          "choice": "After a Health observer drains its entire backlog, publishing waits for its next blocked frame again. That second stall has a fresh bounded wait, later frames skip the stall promptly, publishers are neither failed nor interrupted, and all four frames are delivered in order. An executor barrier proves backlog-drain completion before the second stall is introduced; a held callback and timed future observation prove that the producer actually waits.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/health/HealthStalledObserverTest.java#observerWaitResumesAfterBacklogDrainsAndSecondStallIsBounded"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b84-health.json"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve lease and pressure semantics: a sampler-produced combined signal is presented before the controller makes any unload decision; P8/P12 unload with game_mode, P16_PLUS/NO_GPU/UNRESOLVED with gpu_busy. Reopened leases defer again, overlapping AUX/ASSIST/VDU leases yield one wakeup only after the last closes, and shutdown makes a queued callback inert. Pressure clearing on either side of lease close prevents an unload; a later pressure episode still unloads. Both pressure sources can arrive and clear in either order. After one source clears, the remaining source and current profile decide whether and why chat unloads.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Autonomous design and implementation for task t-20261005-b6d536, including GPU co-residence and Health publication liveness.",
          "choice": "Preserve lease and pressure semantics: a sampler-produced combined signal is presented before the controller makes any unload decision; P8/P12 unload with game_mode, P16_PLUS/NO_GPU/UNRESOLVED with gpu_busy. Reopened leases defer again, overlapping AUX/ASSIST/VDU leases yield one wakeup only after the last closes, and shutdown makes a queued callback inert. Pressure clearing on either side of lease close prevents an unload; a later pressure episode still unloads. Both pressure sources can arrive and clear in either order. After one source clears, the remaining source and current profile decide whether and why chat unloads.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#combinedPressureUsesProfileSpecificReason",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#aLeaseReopenedBeforeDeliveryDefersAgainUntilItCloses",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#overlappingLeasesResumeOnlyAfterTheLastClose",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#shutdownBeforeCallbackDeliveryCannotQueueAnotherUnload",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#pressureClearingOnEitherSideOfLeaseCloseCancelsTheDeferredUnload",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#bothPressureSourcesCanArriveAndClearInEitherOrder",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#theRemainingPressureSourceDecidesAfterTheOtherClears"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b84-sampler.json"
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-gpu3-20261013",
  "verdict": "accept",
  "digest": "sha256:c5d51191c1584785969a0186731284e9c61e2a85cfeb5343d0b975a57d2f195d",
  "note": "VC-gpu3 (independent Claude verifier) on b6d536/integration-gpu vs 217804ac8: accepted b48ef2fcc (GPU lane merged with integration) and the delta c55059360. Live on the real GPU and device plan (P12): external memory pressure unloads idle chat in 0.4 s with gpu_busy, a person's next chat reloads with loading and answers, MCP gets a typed 503 GPU_BUSY with no force and load-anyway 403 REFUSED, a person's load-anyway holds through pressure returning in the 15 s window, indexing shows paused for external GPU use; 20 of its 44 own cases fail on the base for the reason under test; health-stall observer bounded; one unload after short holds; merge kept both sides. Delta: one fixed nextReloadNotBefore per wait and retry-after counting down that wait (both fail on b48ef2fcc). Suites, ArchUnit and PMD show only the base's failures. Evidence refreshed after the merge (dc5facc0c compat before-patches add only missing symbols; before-assertion messages match the original records). Unverified: live repeat of the delta, game plus memory pressure live, residency debug route live (eval-only). Named limit: after sleep or resume a running pressure wait's published instant may be up to 30 s in the past. Digest bound with --base 217804ac8, the combined branch base."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "accept",
  "digest": "sha256:4c9efed57736f7edc61bad2887340cc0fe6d8c7788152f217643cf410d42b5c4",
  "note": "re-bound to main; no change since 0c2bd975c"
}
```
