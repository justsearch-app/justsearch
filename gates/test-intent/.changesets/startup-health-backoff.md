---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries

The startup health wait of `LlamaServerOps` polls `/health` after a short first pause that doubles
to the old 500 ms cap (25, 50, 100, 200, 400, then 500 ms), anew for each physical launch attempt.
`LlamaServerStartupHealthPollTest` is new. Its first three checks pin the new behaviour and fail on
the before-state with assertion failures; its last two are preservation checks of behaviour the
change must keep (interrupt during a pause, a server that exits just after health), which pass on
the before-state and on the candidate. `HealthyFakeLlamaServer` gains one additive option,
`--exit-after-health MS`, that only the new preservation check uses; every existing option and
default behaviour of the fake is unchanged. No existing test is changed.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java"
      ],
      "class": "New",
      "behaviour": "The startup health wait's pauses between polls start at 25 ms and double to the old 500 ms cap: a server still loading at the first poll and healthy right after is seen one short pause later (second poll arrives under 300 ms after the first; one 25 ms pause); a long load (nine 503 answers) is polled on pauses 25, 50, 100, 200, 400, 500, 500, 500, 500 ms, serially (at most one probe in flight), and once capped the polls arrive no more often than every ~500 ms and no less often than every 1 s; a RESOURCE exit that steps down one rung (32768 to 16384) relaunches once, the server at 16384 serves, and the relaunched attempt's pauses restart at 25 ms instead of continuing from the first attempt's grown pause. The before-state fails these three checks with assertion failures (flat 500 ms gap; no growing pauses recorded).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "the night's testing work, whose accepted design removes waiting without weakening checks",
          "choice": "VH1/VH4/VH5: in the startup health wait only, a short first delay that grows to a 500 ms cap (25, 50, 100, 200, 400, then 500 ms), local to each physical launch attempt; requests stay serial and bounded and the cap holds",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aServerReadyJustAfterTheFirstPollIsSeenAfterOneShortPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aLongLoadIsPolledSeriallyOnAGrowingPauseCappedAtTheOldInterval",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aSteppedDownRelaunchStartsAgainAtTheShortestPause"
      ],
      "evidence": "gates/test-intent/evidence/startup-health-backoff-new.json"
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java"
      ],
      "class": "New",
      "behaviour": "Preservation checks of behaviour the faster polling must keep: an interrupt delivered while the startup health wait sleeps between polls ends the wait with HEALTH_CHECK_INTERRUPTED; a managed server that answers health and then exits 0, 5, 25, 100 or 400 ms later is launched once and its exit is reported exactly once, either as the start's failure or as one crash of the serving server (the managed-server-failed callback), never both and never neither. The fake's new --exit-after-health MS option exits with code 1 MS ms after its first 200 health answer; nothing else in the fake changes. Both checks pass on the before-state (with the fake option overlaid) and on the candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "the night's testing work, whose accepted design removes waiting without weakening checks",
          "choice": "VH3a/VH3c: a server that answers health and then exits soon after is still reported exactly once, under the policy for the phase in which it is observed; interrupt during the sleep raises HEALTH_CHECK_INTERRUPTED",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#anInterruptDuringAPauseEndsTheWaitAsInterrupted",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aServerThatExitsJustAfterHealthIsReportedExactlyOnce"
      ],
      "evidence": "gates/test-intent/evidence/startup-health-backoff-preserve.json"
    }
  ]
}
```

## Acceptance records
