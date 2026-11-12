---
schema: test-intent.v1
task: t-20261111-bottlenecks
author-role: builder
author-session: 6c60652f-7191-4ab0-b22d-2fd75572d4a7
---

# Test-intent entries

Moving the frontend lockdown run from app-ui-ui to its sibling job app-ui-ui-2.

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
          "choice": "Move the frontend lockdown step and its Chromium install from job app-ui-ui to app-ui-ui-2, and give app-ui-ui-2 the 40-minute job limit app-ui-ui has. The search-worker rebalance named in the same sentence was tried on two hosted runs and dropped; those jobs are unchanged.",
          "label": "agent choice"
        }
      ],
      "rule": "Every npm test command and every Gradle test task of the hosted unit-test lanes runs exactly once per CI run and stays blocking under the same job names, required checks, `needs`, `if` conditions and classifier lanes; the shard policy names for each lane exactly the commands and tasks its job runs; no assertion, test selection or skip changes.",
      "whySurvives": "The change only moves work between the two sibling jobs of the required check Unit tests (app-ui). The lockdown command and its Chromium install step are deleted from app-ui-ui and inserted unchanged into app-ui-ui-2; both jobs are gated by the same `ui` classifier output. No Gradle invocation and no shard assignment changes. The multiset of npm test commands (6) and Gradle test tasks (61 occurrences) across all jobs is identical before and after, and verify-unit-test-shard-policy still requires the policy to equal the workflow. One limit is looser: app-ui-ui-2's job timeout rises from 25 to 40 minutes, the value app-ui-ui has on main, because with a cold Java compile app-ui-ui-2 has taken 20.6 minutes without the lockdown run (hosted run 43094662574) and the lockdown run adds about 8.6 minutes; a job timeout bounds a hang and is not a test expectation. On two hosted runs of the move (43113951688, 43119363223) the lockdown step passed in app-ui-ui-2."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-1-ci-lane-balance-20261111",
  "verdict": "accept",
  "digest": "sha256:edfa5d4517a62d9696f8a6223652bb6c5ae4f6930262248c88120e3ea9a5ef4e",
  "note": "Class Structural rule kept holds: on 718e761989 vs 579e52b2e5 the parsed ci.yml has the same 29 jobs, names, needs, if, runs-on and env; 6 npm test lines and 61 Gradle test-task occurrences on both sides; only timeout change is unit-tests-search-worker-2 25 to 35 min (declared, named in the brief, not in the owner's quoted words). Gradle dry run: indexer-worker ciUnitTest1+ciUnitTest2 are disjoint and equal test (371 classes, 2009 tests) on both sides; SyncRootPromotionCrashRecoveryTest moved shard 1 to 2 and its 8 tests executed and passed in ciUnitTest2. Policy verifier passes and rejects old policy with new workflow. Delegation quote checked against the brief only, not the task record. Not confirmed: the 28.1-minute sibling figure in whySurvives."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-2-ci-lane-balance-20261111",
  "verdict": "accept",
  "digest": "sha256:32d0ff083d05150849624eed255b154f985005571f38845924bb935475a81bb5",
  "note": "Class Structural rule kept holds on 89103fbdfc vs 718e761989. Parsed ci.yml: same 29 jobs, names, needs, if, runs-on, env; the only differences are the two lockdown steps (byte-identical, unconditional) moved from app-ui-ui to app-ui-ui-2 and unit-tests-search-worker-2 timeout 25 to 35 min (declared; named in the brief, not in the owner's words). 6 npm test commands and 61 Gradle test-task occurrences on both sides; none runs twice in the unit-test lanes. Shard predicate and policy verifier unchanged; old policy with new workflow and new policy with old workflow both fail. Static mirror of the predicate over 371 indexer-worker classes: only SyncRootPromotionCrashRecoveryTest changes shard (1 to 2). Its Gradle execution in ciUnitTest2 is relied on from the check of 579e52b2e5 (identical inputs), not re-run. Hosted run 43094849517: the class took 240 s in ciUnitTest1; search-worker-1 24.7 min, search-worker-2 17.7 min. Delegation quote checked against the brief only, not the task record. No decision record conflicts. Not run: the lockdown tests in their new job, and any hosted run of this candidate."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-3-ci-lane-balance-20261112",
  "verdict": "reject",
  "digest": "sha256:1a65bc98cd59afcb50740b35b73b2be699e4967114707f20435aa7525d0c0808",
  "note": "Class Structural rule kept and the rule hold on 1df6ac5e27 vs 5358c3952a: parsed ci.yml equal apart from the two lockdown steps moved unchanged from app-ui-ui to app-ui-ui-2 and that job's timeout 25 to 40; 6 npm test commands, 61 Gradle test-task occurrences, each once in the unit-test lanes; policy verifier passes and fails on either mixed state; runs 43113951688 and 43119363223 show the lockdown step passing in app-ui-ui-2 (34 files) and 13.2/14.4 and 19.5/16.7 min. Rejected for the reason given for the one looser limit, which the cited run contradicts. Run 43094662574 app-ui-ui-2: 20.6 min = Gradle 8m27s with 29 of 89 tasks from cache (not cold) plus an 11.3 min failed artifact upload (Failed to CreateArtifact after 5 attempts). The fully cold case with the lockdown run is run 43113951688: 89 of 89 tasks executed, 19.5 min, under 25. Must change: restate in whySurvives why 40 minutes is needed from numbers that hold, or keep 25; and correct the same claim where the candidate repeats it (ci.yml comment on app-ui-ui-2 'exceed 25 minutes whenever the test task cannot come from the build cache'; docs/explanation/09-testing-strategy.md 'cold Java compile alone can take about 18 minutes': measured cold compiles are 3.8 to 5.0 min). Delegation quote checked against the brief only, not the task record. Not run: the lockdown tests locally, and any hosted run of this candidate."
}
```
