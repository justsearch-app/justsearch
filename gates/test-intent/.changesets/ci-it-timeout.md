---
schema: test-intent.v1
task: t-20261020-5d7302
author-role: builder
author-session: ci-it-timeout-builder
---

# Test-intent entries

Replaces the non-AI, non-agent 30-minute task cap with 50 minutes; the hosted job
budget rises from 35 to 60 minutes. AI, agent and lifecycle budgets are unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/system-tests/build.gradle.kts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Approved direction: slow suites and the combined-tree check on main after merging",
          "choice": "Give the main-only system-tests integration tier a 50-minute task and 60-minute job budget so it completes on hosted Windows runners",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "justsearch-03",
  "verdict": "accept",
  "digest": "sha256:e4df5f92fc0674016e72b89b5bb9ffa89856491ba73929735a008e70105ece50",
  "note": "Checked against the diff and hosted JUnit artifacts: the old 30-minute cap assumed an 8.5-minute tier; the tier now measures 20-26 minutes, so the cap kills it on green runs. 50 minutes keeps the documented triple-boot worst case. Repeated the conflict search: no decision record sets this cap. Within the owner's approved slow suites on main."
}
```
