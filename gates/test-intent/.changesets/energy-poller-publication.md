---
schema: test-intent.v1
task: energy-poller-test
author-role: builder
author-session: 01a16d0b-5288-7e62-a22f-a292cbcb80ed
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The startup/restart latch now signals after a spy invokes the real gauge setter with true, instead of inside the probe before it returns. Both starts still use the real scheduled poller and the same reduced-state input. The real gauge write and read, assertions, ten-second awaits, load-sensitive tag, idempotent second start, retained-state check after close, restart and cleanup remain. The wait now orders the gauge assertion after the write it observes; it also requires publication after restart.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Fix the test so it waits for the outcome it asserts (or drives the poll deterministically) without weakening what it checks; change no production code unless the race is in production code, and if so, stop and report. No sleeps as synchronization.",
          "location": "task energy-poller-test request"
        }
      ]
    }
  ]
}
```

## Acceptance records
