---
schema: test-intent.v1
task: t-20261111-bottlenecks
author-role: builder
author-session: 6c60652f-7191-4ab0-b22d-2fd75572d4a7
---

# Test-intent entries

Balancing the two longest hosted unit-test jobs: pure moves of test work between sibling jobs.

```json
{
  "entries": [
    {
      "items": [
        ".github/workflows/ci.yml",
        "scripts/ci/unit-test-shard-policy.v1.json"
      ],
      "change": "modified",
      "class": "Structural rule kept",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly then, i wont upgrade to the paid plan yet",
          "delegationLocation": "task t-20261111-bottlenecks request",
          "delegationScope": "The free CI changes the orchestrator had listed in the turn before, the second of which read: \"Move the browser-based frontend tests to the neighbouring job. One job runs frontend tests and Java tests back to back and takes 24 minutes, while its sibling takes 9. Moving the 8-minute browser part across makes both about 16-17 minutes and adds no new job. The other 24-minute job is one long Java test run, so I'd rebalance its split with its 16-minute sibling using fresh timings.\"",
          "choice": "Move the frontend lockdown step and its Chromium install from job app-ui-ui to app-ui-ui-2; move :modules:adapters-lucene:test from unit-tests-search-worker-1 to unit-tests-search-worker-2 and assign SyncRootPromotionCrashRecoveryTest to indexer-worker's second shard; give unit-tests-search-worker-2 the 35-minute job limit its sibling already has.",
          "label": "agent choice"
        }
      ],
      "rule": "Every npm test command and every Gradle test task of the hosted unit-test lanes runs exactly once per CI run and stays blocking under the same job names, required checks, `needs`, `if` conditions and classifier lanes; the shard policy names for each lane exactly the commands and tasks its job runs; no assertion, test selection or skip changes.",
      "whySurvives": "The change only moves work between sibling jobs of the same required check. The lockdown command and its Chromium install step are deleted from app-ui-ui and inserted unchanged into app-ui-ui-2 (both gated by the same `ui` classifier output and both required by Unit tests (app-ui)). :modules:adapters-lucene:test leaves the search-worker-1 Gradle invocation and joins the search-worker-2 invocation (both required by Unit tests (search-worker)). The class-shard file moves one class from the hash default to an explicit second-shard assignment; the two shard tasks stay disjoint and complete by construction (one include predicate per bucket over the same class set). The multiset of npm test commands (6) and Gradle test tasks (61 occurrences) across all jobs is identical before and after, and verify-unit-test-shard-policy still requires the policy to equal the workflow. One limit is looser: unit-tests-search-worker-2's job timeout rises from 25 to 35 minutes, the value unit-tests-search-worker-1 already has, because the job now carries about 340 s more summed test-class time and its sibling reached 28.1 minutes at a comparable load; a job timeout bounds a hang and is not a test expectation."
    }
  ]
}
```

## Acceptance records
