---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b46-seat-authority-20261009
---

# Return foreground seats through authoritative queue state

```json
{
  "entries": [
    {
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Make the seat accounting correct for every way a task can leave the pool",
          "location": "task t-20261005-b6d536 user turn 2026-10-09 b46 builder request"
        }
      ],
      "items": ["modules/app-engine/src/test/java/io/justsearch/app/engine/ReservedSeatQueueTest.java"],
      "class": "New",
      "newChecks": ["modules/app-engine/src/test/java/io/justsearch/app/engine/ReservedSeatQueueTest.java"],
      "evidence": "gates/test-intent/evidence/b46-seat-authority.json",
      "behaviour": "Queue removal immediately restores admission and a settled pool accepts its full next burst. Cover purge, direct remove/removeIf/clear/drainTo/poll/take, iterator removal, bulk collection removal, duplicate Runnable occurrences, and removal by the running task. Three thousand concurrent rounds mix arrival, cancellation, purge and removal; held workers expose any over-admission and all removed seats must be reusable, including after thread-factory replacement. Direct queue insertion consumes capacity. Failed worker creation after buffering discards the rejected occurrence and rechecks termination if shutdown races it. Original pool remove and cancelled-Future no-op are preservation controls. The before-state runs 21 cases with 19 assertion failures and two preservation passes; the candidate passes all 21. Existing FP1-FP13 expectations are unchanged.",
      "repaired": true
    }
  ]
}
```

Independent verifier acceptance is pending. Builder evidence does not supply that verdict.
