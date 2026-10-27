---
schema: test-intent.v1
task: capped-backlog-observation
author-role: builder
author-session: 01a17bd0-633a-7bc3-bb15-cc2a60fbb641
---

# Observe capped backlog within the retry pause

The existing oracle is unchanged; only the observation becomes consistent.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VerifierCappedBacklogStatusTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "In previouslyDeferredBacklogIsNotReadyDuringLaterCappedPause, the observation uses pause sample A, rows snapshot 1, the status call, pause sample B, and rows snapshot 2. An observation is accepted only when both pause samples are true and the two ordered row snapshots are equal, including paths, states, retry deadlines, attempts, policies, first-failure timestamps, outcome classes and last-updated timestamps. Two pause samples alone can straddle different batches: snapshot 1 can fall between a later batch deferral and extension of earlier deferrals even though A and B each see a pause. The snapshot comparison rejects that window because extension completes before pause B and snapshot 2 observes the extended deadlines. The capped and PENDING row conditions, expiredDeferred and deferred use snapshot 1 and now is sampled with snapshot 1. The loop thread owns claiming and completes processing, deferral extension and the pause before polling again. Every assertion and message, the 12 s observation window, 6500 ms cutoff, 8 s resume bound, 9 s recovery window, 64 rows, subsequent deadline and recovery checks, inputs and test selection remain unchanged. The other test and production code are unchanged. Source: main CI run 40438351182, job \"Unit tests (search-worker-2)\", line 62: \"previously deferred outage rows cannot become claimable while the service still pauses ==> expected: <0> but was: <48>\"; the subsequent diagnostic showed all 64 rows with the same far retry deadline, and the in-job rerun passed. This is the supplied CI report; no deterministic baseline reproduction by this author is claimed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Make the capped-backlog test observation consistent by sampling the running loop retry pause before its first row snapshot and after its status call, then requiring an equal second row snapshot; preserve the complete existing oracle.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```
