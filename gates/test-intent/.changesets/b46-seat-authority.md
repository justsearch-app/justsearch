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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Make the seat accounting correct for every way a task can leave the pool",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-09 b46 builder request'; the text does not appear in the owner's turns of session 886833dd"
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

Independent verifier acceptance is recorded below. The acceptance judges test intent; the full verification report records gate failures and limits.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-seat-20261009",
  "verdict": "accept",
  "digest": "sha256:14865aa139e6fb3e20f69136907b792580e2a7e8a42cd364054c169f3583bcaa",
  "note": "Accept New/repaired test intent. Independent fresh compilation against 5be13f8e0 produces 21 cases: 19 intended assertion failures and two preservation passes; f91a74d15 passes all 21. Independent real-registry probes pass 36000 varied stress rounds plus removal, duplicate-identity, original-Future, failed-factory and other-pool controls. The candidate passes all 628 safe app-engine cases and 123 core cases. Reduced warm CPU-only live verification passes: HTTP16 twice and MCP16 once have zero refusals; HTTP24 serves 16 and refuses eight with ADMISSION_INFERENCE_BUSY and Retry-After above 16 observed outstanding clients. Text controls take 21.3 to 49.6 ms. This accepts test intent only; the governance gate retains 172 inherited errors and the report records unverified limits. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-seat/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:3f54370edc5292fdb5d1825449e19a57cd90022561b246778f1960f7153697a9",
  "note": "Reviewed the source relabel and queue-removal/capacity oracles; the choice remains within item 5. All 21 ReservedSeatQueueTest cases passed on the candidate; before evidence is unchanged."
}
```
