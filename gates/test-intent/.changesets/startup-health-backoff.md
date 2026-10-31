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
      "behaviour": "D1-D4: Startup health polls pause 25, 50, then 100 ms for every later pause, serially and with a dedicated startup cap. Each context step-down, reasoning retry, initial/recovery lost-bind retry and later start resets the pause; waitForServerHealthOnce shares it. R1 reads scheduled pauses through startupHealthPauses, without wall-clock gap bounds. R2 holds each tested launch loading until three 503 probes and the pause ladder are observed, releasing failures by a per-port signal. R3 requires at least eight probes inside a 1500 ms loading window and fails on baseline 30ec14d39b independently of the observation seam.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261031-stranded user turn 5",
          "delegationScope": "landing the startup health polling change so a started model is noticed sooner, without changing deadline, recovery, authentication or monitoring behaviour",
          "label": "agent choice",
          "choice": "startup health wait only: pauses 25, 50, then 100 ms (own 100 ms cap), restarting for each call of the wait; probes stay serial; nothing else changes"
        }
      ],
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aServerReadyJustAfterTheFirstPollIsSeenAfterOneShortPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aLongLoadIsPolledSeriallyOnAGrowingPauseCappedAt100Ms",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aSteppedDownRelaunchStartsAgainAtTheShortestPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aReasoningFlagRetryStartsAgainAtTheShortestPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aLostBindRetryStartsAgainAtTheShortestPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aLaterStartAfterASlowWaitStartsAgainAtTheShortestPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aOneShotRecoveryStartsAgainAtTheShortestPause",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java#aLoadingWindowReceivesAtLeastEightHealthProbes"
      ],
      "evidence": "gates/test-intent/evidence/startup-health-backoff-new.json"
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartupHealthPollTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java"
      ],
      "class": "New",
      "behaviour": "D5, P1-P2: A first-probe success needs no pause. Interrupt during a pause raises HEALTH_CHECK_INTERRUPTED and retains the thread interrupt flag. A server exiting 0, 5, 25, 100 or 400 ms after its first healthy answer launches once and reports exactly one start failure or crash. The fake gains additive --exit-after-health MS and --startup-until-signal options; all main options, including API-key enforcement, remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261031-stranded user turn 5",
          "delegationScope": "landing the startup health polling change so a started model is noticed sooner, without changing deadline, recovery, authentication or monitoring behaviour",
          "label": "agent choice",
          "choice": "preserve: interrupt during a pause ends the wait with HEALTH_CHECK_INTERRUPTED and keeps the interrupt flag; a server that answers health then exits 0-400 ms later is launched once and its exit reported exactly once; a server healthy on the first probe completes without any sleep"
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
additive exit and per-port startup signal options overlaid. It uses its own build directory. Run-evidence runs there
without `--before` because the sandbox cannot create Git worktree metadata in the parent repository.
The baseline evidence is `gates/test-intent/evidence/startup-health-backoff-before.json`.
Only R3 and D5/P1/P2 run on the baseline: R1/R2 require the candidate's observation seam.

## R3 loading-window margin

The 1500 ms window begins on the first startup health request. Only arrivals strictly inside
that window count, so delayed delivery of the final healthy response cannot inflate the result.
The candidate's ideal schedule produces approximately 16 probes; its eighth probe is due at
575 ms, leaving 925 ms for cumulative HTTP and scheduler delay before the threshold would fail.
The threshold accepts roughly half the ideal probe count. The old 500 ms schedule permits
at most four probes in 1500 ms (normally three strictly inside the window), so it cannot pass
by landing close to a boundary. All precise ladder assertions use recorded pauses instead.

## Live acceptance (D6)

The former 25-to-500 ms schedule missed readiness by 267-420 ms on real compact-model
loads of 2.5-3.5 s. The dedicated 100 ms startup cap keeps later polls one short pause apart.
D6 (at most approximately 100 ms from llama-server's `main: model loaded` to JustSearch's
`Server healthy`) is checked live by the orchestrator's verifier; the builder does not run
real models or use the GPU. The 10 s progress-log interval remains unchanged.
