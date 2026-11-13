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
        "purpose": "Shared test fixture for the new checks VisionCrashResumeTest and LoadedVduDeferralResumeTest: it wires the real VDU sampler, breaker, batch processor and chat controller to a fake llama-server process that can be killed (SIGKILL) while READY, plus a controllable clock, activity and energy source. It contains no assertions of its own.",
        "callersAndStateOwners": "Only the two new test classes in the same package use it. It owns only test-local state: a temporary directory, the fake server processes it starts (stopped in close()), and an in-memory document store fake. No production code references it; it changes no production behavior.",
        "indirectBehaviours": "The checks built on it decide scenarios S1-S7 and P1/P4 of task t-20261113-vduresume: after a crash of the VDU-mode server, chat is not latched FAILED and picture reading resumes without user action; repeated crashes are paced by the automatic VDU breaker; the in-flight document is not charged a retry; with chat loaded, a pass deferred by energy or holds without user activity is retried; user activity still defers. If the fixture failed to deliver a real crash or a real deferral, those checks would pass vacuously on the before-state; they do not.",
        "evidence": "On the before-state a1df12e125 the checks using this fixture fail for the reason under test (17 failures: CRASH_LOOP latched, crashed document charged, no retry after deferral) and on candidate 1535bd3659 they pass; preservation checks pass on both. Shown by an independent verifier session (task t-20261113-vduresume results verifier-1) and by a real-app check on the dev stack in which killing the vision server led to an automatic pass 24 s later on the candidate and none on the before-state.",
        "unresolvedRisks": "Virtual time shifts sampler fields that hold wall-clock times; a later change to those fields could make the fixture's clock handling stale. The late-exit scenario reaches a private crash handler by reflection. The GPU device-loss branch for vision mode is not exercised."
      }
    }
  ]
}
```

## Acceptance records
