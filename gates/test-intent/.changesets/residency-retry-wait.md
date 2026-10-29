---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: orchestrator
author-session: 8c330fb4-0ab8-439d-8de2-6a42f0669e2b
---

# Residency Retry check waits for the interval instead of a fixed sleep

Hosted run 40844473376 (job 123348632605) failed "ordinary Retry becomes available after its
interval but the timer never submits" with `expected true to be false`: the test slept a real
100 ms for an 80 ms backoff, and a loaded runner delivered the interval late. The test now waits
(up to 5 s) until Retry is enabled, then waits a further 100 ms and still requires exactly one
dispatch and no load, then clicks Retry and requires the second dispatch.

Local evidence with the fixture's interval raised to 150 ms to simulate late delivery: the old
test fails with the same `expected true to be false`; the new one passes. With the fixture as
committed, 118 of 118 residency tests pass three times in a row under `TZ=UTC`.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts"],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Only the wait in 'ordinary Retry becomes available after its interval but the timer never submits': a fixed 100 ms sleep becomes a bounded wait until Retry is enabled followed by the same 100 ms sleep. Inputs (RELOAD_BACKOFF refusal, 80 ms, forceAllowed false), the initial disabled assertion, the no-auto-submit assertions (one dispatch, no load), the enabled assertion and the manual Retry dispatch are unchanged; a Retry that never enables now fails by timeout instead of by assertion."
    }
  ]
}
```

## Acceptance records
