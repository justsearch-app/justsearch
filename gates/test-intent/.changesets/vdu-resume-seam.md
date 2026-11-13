---
schema: test-intent.v1
task: t-20261113-vduresume
author-role: builder
author-session: f5154131-58b2-4c0e-8a50-dc0fb1bf7d4f
---

# Test-intent entries

Picture reading resumes on its own after a vision-server crash or a resource deferral. The new
checks are three isolated new test files (exempt); this entry covers their shared test fixture.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduResumeSeam.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "Incidental",
      "assessment": {
        "purpose": "Shared test fixture for the new checks VisionCrashResumeTest and LoadedVduDeferralResumeTest. It wires the real VDU sampler, breaker, batch processor and chat controller to a fake llama-server process that can be killed (SIGKILL) while READY, plus a controllable clock, activity and energy source. It makes precondition and synchronisation assertions only (VduResumeSeam.java lines 223, 224, 240, 249, 258, 276, 283, 284: the reconciler settled, chat intent as set up, a model request was reached, the newest server is running and is the picture-reading server, the pass released ownership). These guard that a crash or deferral really happened; the scenario outcomes are asserted in the two test classes.",
        "callersAndStateOwners": "Only the two new test classes in the same package use it; no production code references it. State it owns or changes: a JUnit temporary directory; the fake server processes it starts (stopped in close()); an in-memory document store fake; through ManagedChatSeam, the JVM-global ConfigStore.global and the system property justsearch.lite.mode (set to false), both restored in ManagedChatSeam.close(); through RuntimeIntentTestFixture, a real temporary settings.json and operations.db under the temporary directory. It changes no production behaviour.",
        "indirectBehaviours": "The checks built on it decide scenarios S1-S7 and P1/P4 of task t-20261113-vduresume: after a crash of the VDU-mode server, chat is not latched FAILED and picture reading resumes without user action; repeated crashes are paced by the automatic VDU breaker; the in-flight document is not charged a retry; with chat loaded, a pass deferred by energy or holds without user activity is retried; user activity still defers. If the fixture failed to deliver a real crash or a real deferral, those checks would pass vacuously on the before-state; they do not.",
        "evidence": "Run on both trees: on the before-state a1df12e125 the two classes using this fixture fail 16 of 22 cases for the reason under test (CRASH_LOOP latched after a vision crash; crashed document charged a FAILED outcome; breaker stays CLOSED after a crash; loaded-chat entry failure and energy, bulk, indexing and lease deferrals never resume), and 6 pass on both (preservation, including chat-off vision-start failure recovery). On candidate 1535bd3659 all 22 pass. (The 17 before-failures counted earlier include the separate new ChatFailedOfflineRunReceiptTest.) A real-app check on the dev stack, outside this fixture, saw an automatic pass 24 s after killing the vision server on the candidate and none on the before-state.",
        "unresolvedRisks": "Global state is restored only on normal close: if the fixture's constructor fails part-way, ConfigStore.global or justsearch.lite.mode may stay changed for later tests in the same JVM (inspected, not fault-injected). Virtual time shifts sampler fields that hold wall-clock times; a later change to those fields could make the fixture's clock handling stale. The late-exit scenario reaches a private crash handler by reflection. The GPU device-loss branch for vision mode is not exercised."
      }
    }
  ]
}
```

## Acceptance records
