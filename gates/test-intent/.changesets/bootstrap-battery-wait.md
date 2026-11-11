---
schema: test-intent.v1
task: t-20261102-redesign
author-role: orchestrator
author-session: 429de09b-ce0d-474d-8a0c-918054af76df
---

# Battery-hold wait observes the whole held state

`IndexingRulesBootstrapIntegrationTest.fullMainHoldsFreshBatteryAndTimesOutUnknownWithDurablyQueuedWork`
failed intermittently at line 72 on main (runs 42726669131 and 42858577907, first attempts). In
both failing JUnit reports the status body the wait accepted showed the worker queue at 5 jobs while
the separately published operating view still said 0 pending; the passing rerun's accepted body
showed 5 in both. The wait accepted on `indexAvailable` and the `on_battery` reason alone, then
asserted the operating pending count and state, which can lag because the operating view is
published in its own lane. No queue drained in either failure (indexed and searchable documents
and commits stayed 0).

The wait now also requires the operating pending count above zero and the PAUSED state, the two
values the test then asserts. Every assertion, input, child process, deadline and the 20-second
status budget stay as they were. A queue that really drained, or a hold that never arrives, still
fails, now at the wait. No product code changes.

Replaying the three captured accepted bodies against the predicates: the old predicate accepts both
failing bodies (and line 72 then fails), the new one rejects both and keeps waiting, and both accept
the passing body.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/IndexingRulesBootstrapIntegrationTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The status wait before the battery-hold assertions also waits for operating.indexing.pendingJobs > 0 and state PAUSED, which the test then asserts, because the operating view is published in its own lane and lagged the worker's queue count in both failing CI reports (worker pendingJobs 5, operating pendingJobs 0, reason on_battery). Inputs, child processes, assertions, expected values and the existing 20-second status budget are unchanged. Execution evidence: gates/test-intent/evidence/bootstrap-battery-wait.json."
    }
  ]
}
```

## Acceptance records
