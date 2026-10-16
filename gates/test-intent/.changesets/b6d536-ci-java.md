---
schema: test-intent.v1
task: b6d536-part1-ci-java
author-role: builder
author-session: codex-ci-java-20261016
---

# CI test execution

The Windows-native job in run 38847760494 reached its 15-minute job limit during
the auto-discovered dev-runner suite, before any native JVM tests ran. Its old
comment described a handful of tests; discovery now includes 45 dev-runner files,
with real process identities, stop census and recovery rounds. The job budget is
35 minutes, with verbose file start/completion diagnostics. app-api joins the
existing Windows-tagged Gradle selection so Windows prerequisites declared by the
helper tests still receive hosted coverage. Every Gradle invocation skips the web
build. The three Ubuntu unit-shard task lists and their assertions are retained.
The stop-census file alone passed in 604,166 ms locally: 81 cases including 5,250
generated ledgers/histories, so the cancellation is a job-budget failure rather
than evidence that a native JVM test hung.
All 44 original dev-runner files passed (953,432 ms summed file time); the new
temporary-root alias regression passed separately and failed by assertion on
31bbdf349. The installed lifecycle failure also reproduced on 31bbdf349 and
passed after waiting for the successor manifest's API port before capturing it.
The 35-module Windows unit run passed in 16m27s; saved JUnit reports contain
15,221 tests, zero failures and 39 skips (app-api-tck is NO-SOURCE).
A separate full app-api rerun passed all 448 tests with zero skips. The affected
replay classes, availability class and four native helper classes all execute on
this Windows host. WSL is not installed, so Linux execution remains for hosted CI.
The exact updated nine-module Windows-only Gradle command passed in 2m16s with
131 tests, zero failures and zero skips, including all 13 selected app-api tests.
All nine original evidence files were regenerated using their original declared
before-states (and the existing chunk-replay compatibility patch). All 27 named
checks pass on the candidate. The before-state classifications match the original
evidence exactly: 23 assertion failures and four preservation passes. Mixed
preservation runs return exit 1 from run-evidence by design; they are not repairs.

The existing five changesets covering edited Java tests describe their adjusted
fixtures and prerequisites; their original before-state evidence is regenerated.
No builder acceptance is recorded.

```json
{
  "schema": "test-intent.v1",
  "task": "b6d536-part1-ci-java",
  "authorRole": "builder",
  "authorSession": "codex-ci-java-20261016",
  "entries": [
    {
      "items": [".github/workflows/ci.yml"],
      "class": "Structural rule kept",
      "rule": "Every portable unit check remains selected on Ubuntu; Windows-native helper checks remain selected on Windows with all original assertions and prerequisite-specific execution.",
      "whySurvives": "Add app-api to the existing Windows-tagged task list and declare skipWebBuild explicitly. Round 4 also adds ui:test so the successful native predecessor-handle exit-fact assertion in ManagedChildBootFallbackTest executes on hosted Windows under its explicit Windows prerequisite. The portable scripted ownership checks and an unavailable-facts preservation check stay selected on app-ui. The dev-runner suite keeps dynamic discovery and verbose diagnostics. Run 38871877988 took 31m30 before adding native UI compilation and coverage, so the enclosing Windows job receives 40 minutes for the added module and hosted cold-start variance, with all per-test budgets unchanged. Round 4 also installs the root esbuild and UI zod locked dependency sets for the existing real frontend HTTP probes on app-ui. That shard alone receives a 35-minute whole-job cap after hosted run 38867896142 exhausted 25 minutes during its final UI task; the other unit shards retain 25 minutes and every per-test budget stays intact. No assertion, test, retry or unit-shard selection is removed."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS6a-20261016",
  "verdict": "accept",
  "digest": "sha256:6a887fd7835604ee1f9a877bdd9b8b01cfd1ebbac44d9c16853dadd5cbff58a7",
  "note": "Reviewed the CI repairs, preserved assertions, honest prerequisites and required Windows task selection; the exact Windows-native task list and all changed Java classes execute successfully on Windows. The Rust library passes 149 tests, including the finite terminal-accounting allowance regression; the unchanged default Cargo binary compilation failure is an observation, not a library-test pass."
}
```
