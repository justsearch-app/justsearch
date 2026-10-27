---
schema: test-intent.v1
task: capped-backlog-observation
author-role: builder
author-session: 01a17b54-975b-7de3-9f9a-b05874f161d0
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
      "whatMoved": "In previouslyDeferredBacklogIsNotReadyDuringLaterCappedPause, the retry-pause observation now brackets the existing rows snapshot and status call: pause sample, rows, status, pause sample. An observation is accepted only when both pause samples are true. The capped and PENDING row conditions, expiredDeferred and deferred use that same rows snapshot. This prevents accepting rows read between processBatch deferring its current batch and extendServiceOutageDeferrals extending earlier batches, before the loop enters sleepAfterError. The loop thread owns claiming and completes that sequence before polling again. Every assertion and message, the 12 s observation window, 6500 ms cutoff, 8 s resume bound, 9 s recovery window, 64 rows, subsequent deadline and recovery checks, inputs and test selection remain unchanged. The other test and production code are unchanged. Source: main CI run 40438351182, job \"Unit tests (search-worker-2)\", line 62: \"previously deferred outage rows cannot become claimable while the service still pauses ==> expected: <0> but was: <48>\"; the subsequent diagnostic showed all 64 rows with the same far retry deadline, and the in-job rerun passed. This is the supplied CI report; no deterministic baseline reproduction is claimed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Make the capped-backlog test observation consistent by sampling the running loop retry pause before its row snapshot and after its status call, preserving the complete existing oracle.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```
