---
schema: test-intent.v1
author-role: builder
author-session: startup-health-backoff-builder-20261031
---

# Test-intent entries

Startup health polling only; the deadline, monitoring and recovery decisions are unchanged.
The archived change is adapted to main's managed API-key verification (#838), retaining all fake options.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java"
      ],
      "class": "New",
      "behaviour": "D1-D4: Startup health polls pause 25, 50, 100, 200, 400, then 500 ms, serially and capped. Each context step-down, reasoning retry, initial/recovery lost-bind retry and later start resets the pause; waitForServerHealthOnce shares it. Actual request timing fails on baseline 30ec14d39b with a flat 500 ms first pause, independent of the observation seam.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "in LlamaServerOps' startup health wait only, the pause between /health polls starts at 25 ms and doubles to the existing 500 ms cap (25, 50, 100, 200, 400, then 500 ms), local to each call of the wait (so each physical launch attempt starts again at 25 ms).",
          "location": "task startup-health-backoff request"
        }
      ],
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aServerReadyJustAfterTheFirstPollIsSeenAfterOneShortPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aLongLoadIsPolledSeriallyOnAGrowingPauseCappedAtTheOldInterval",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aSteppedDownRelaunchStartsAgainAtTheShortestPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aReasoningFlagRetryStartsAgainAtTheShortestPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aLostBindRetryStartsAgainAtTheShortestPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aLaterStartAfterASlowWaitStartsAgainAtTheShortestPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aOneShotRecoverySeesReadinessAfterOneShortPause"
      ],
      "evidence": "gates/test-intent/evidence/startup-health-backoff-new.json"
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java"
      ],
      "class": "New",
      "behaviour": "D5, P1-P2: A first-probe success needs no pause. Interrupt during a pause raises HEALTH_CHECK_INTERRUPTED and retains the thread interrupt flag. A server exiting 0, 5, 25, 100 or 400 ms after its first healthy answer launches once and reports exactly one start failure or crash. The fake gains only the additive --exit-after-health MS option; all main options, including API-key enforcement, remain.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "P1: interrupt during a pause ends the wait with HEALTH_CHECK_INTERRUPTED and keeps the thread's interrupt flag.",
          "location": "task startup-health-backoff request"
        },
        {
          "kind": "owner-words",
          "quote": "P2: a server that answers health then exits 0-400 ms later is launched once and its exit reported exactly once (start failure or one crash), never both, never neither.",
          "location": "task startup-health-backoff request"
        },
        {
          "kind": "owner-words",
          "quote": "D5: a server healthy on the very first probe completes without any sleep.",
          "location": "task startup-health-backoff request"
        }
      ],
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aServerHealthyOnTheFirstProbeNeedsNoPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#anInterruptDuringAPauseEndsTheWaitAsInterrupted",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aServerThatExitsJustAfterHealthIsReportedExactlyOnce"
      ],
      "evidence": "gates/test-intent/evidence/startup-health-backoff-preserve.json"
    }
  ]
}
```

## Baseline execution

The baseline is a shared clone at `30ec14d39b`, with only the new test class and the fake's
additive exit option overlaid. It uses its own build directory. Run-evidence runs there
without `--before` because the sandbox cannot create Git worktree metadata in the parent repository.
The baseline evidence is `gates/test-intent/evidence/startup-health-backoff-before.json`.

## Acceptance records
