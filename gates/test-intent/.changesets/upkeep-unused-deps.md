---
schema: test-intent.v1
task: upkeep-unused-deps
author-role: builder
author-session: upkeep-f5-builder
---

# Test-intent entries

Dependency cleanup preserves the existing checks and tightens the dead-code baseline.
The source below quotes the task request; the independent acceptor verifies that source.
Incidental preservation evidence is under ignored `tmp/f5-deps/`; no new checks need D4 evidence.

```json
{
  "entries": [
    {
      "items": [
        "gates/dead-code/baseline.txt"
      ],
      "class": "Structural rule kept",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Find and remove what JustSearch carries that is not product and does not pay for itself.",
          "location": "task t-20261019-9d870e request"
        }
      ],
      "rule": "The dead-code ratchet rejects measured unused counts above the pinned per-path baseline and rejects undeclared upward baseline shifts.",
      "whySurvives": "The predicate is unchanged (scripts/governance/gates/dead-code/enforcer.mjs:187-225 and truth-table.mjs:13-33). Only two pins tighten to the measured Knip counts: package.json 5 -> 0 (zero row removed), and src/shell-v0/state/runtimeConformance.ts 2 -> 1. Every other pin remains unchanged. Before and after dead-code gates exit 0; tmp/f5-deps/knip-delta.json and logs/base-governance-dead-code.log / logs/candidate-governance-dead-code.log record those results; log paths are relative to tmp/f5-deps/. No checks, exemptions or guard predicates change."
    },
    {
      "items": [
        "modules/app-services/build.gradle.kts"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Find and remove what JustSearch carries that is not product and does not pay for itself.",
          "location": "task t-20261019-9d870e request"
        }
      ],
      "assessment": {
        "purpose": "Remove the unused java-test-fixtures producer plugin from app-services, preserving its test execution and its consumption of other modules' fixtures.",
        "callersAndStateOwners": "app-services has no testFixtures source files and no consumer selects its test-fixtures variant. Existing consumption of test fixtures from other modules remains unchanged. The regular test suite owns its existing fixtures, mocks and test-local state; production and test sources are unchanged.",
        "indirectBehaviours": "Removing the unused producer configurations/variant must neither hide a test nor break downstream compilation. All-project compileJava/compileTestJava and the same thirteen affected module test tasks execute in one offline Gradle invocation with --continue and the same resource flags as the base. No test filters, tags, assertions or execution settings change.",
        "evidence": "Baseline c00a0cb24b4c6000e090d5d09054c1e7e1d24e2f and candidate both pass all-project compileJava/compileTestJava and the thirteen affected module test tasks. For app-services, both have 566 suites, 3972 tests, 0 failures, 0 errors and 11 skips. Across all thirteen modules both have 9222 tests and 68 skips. tmp/f5-deps/base-gradle-test-counts.json is the saved baseline; candidate-gradle-test-counts.json, gradle-test-comparison.json, followup2-java-evidence.json and logs/followup2-java-comparison.log (all under tmp/f5-deps/) assert identical module counts, skipped check identities and failures. Baseline logs/base-gradle-compile.log and logs/base-gradle-tests-complete.log, and candidate logs/candidate-gradle-compile-tests-followup2.log record the commands and exit 0. No test source file changes. These preservation runs add no new checks; docs/reference/testing/test-intent.md reserves run-evidence.mjs evidence files for new checks, so incidental evidence stays under ignored tmp/.",
        "unresolvedRisks": "No unresolved difference in the requested compile/unit checks. Existing baseline skips remain the same checks. This evidence covers the requested unit suites; integration/system test tasks were not requested or run. Independent acceptance is still required."
      }
    },
    {
      "items": [
        "modules/system-tests/gradle.lockfile"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Find and remove what JustSearch carries that is not product and does not pay for itself.",
          "location": "task t-20261019-9d870e request"
        }
      ],
      "assessment": {
        "purpose": "Remove the unused Guava export from ipc-common and its resulting main compile/runtime classpath memberships in the system-tests dependency lock; preserve the system test oracles and execution.",
        "callersAndStateOwners": "Gradle resolves system-tests main and test classpaths from this lock. The main system-test oracles and their callers remain unchanged; the lock still pins the same versions and keeps every test, integrationTest and systemTest configuration membership.",
        "indirectBehaviours": "Only main compileClasspath/runtimeClasspath memberships for Guava and its annotation/support dependencies leave this lock. No dependency coordinate or test configuration membership is removed from this file. All-project compileJava/compileTestJava checks consumers of the narrowed ipc-common API; the same thirteen module unit suites cover the affected classpaths.",
        "evidence": "Baseline c00a0cb24b4c6000e090d5d09054c1e7e1d24e2f and candidate both pass all-project compileJava/compileTestJava and the thirteen affected module test tasks. For system-tests, both have 29 suites, 96 tests, 0 failures, 0 errors and 0 skips. Across all thirteen modules both have 9222 tests and 68 skips. tmp/f5-deps/base-gradle-test-counts.json is the saved baseline; candidate-gradle-test-counts.json, gradle-test-comparison.json, followup2-java-evidence.json and logs/followup2-java-comparison.log (all under tmp/f5-deps/) assert identical module counts, skipped check identities and failures. Baseline logs/base-gradle-compile.log and logs/base-gradle-tests-complete.log, and candidate logs/candidate-gradle-compile-tests-followup2.log record the commands and exit 0. No test source file changes. These preservation runs add no new checks; docs/reference/testing/test-intent.md reserves run-evidence.mjs evidence files for new checks, so incidental evidence stays under ignored tmp/.",
        "unresolvedRisks": "No unresolved difference in the requested compile/unit checks. Existing baseline skips remain the same checks. This evidence covers the requested unit suites; integration/system test tasks were not requested or run. Independent acceptance is still required."
      }
    },
    {
      "items": [
        "modules/worker-core/build.gradle.kts"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Find and remove what JustSearch carries that is not product and does not pay for itself.",
          "location": "task t-20261019-9d870e request"
        }
      ],
      "assessment": {
        "purpose": "Remove the unused java-test-fixtures producer plugin from worker-core, preserving its test execution and its consumption of other modules' fixtures.",
        "callersAndStateOwners": "worker-core has no testFixtures source files and no consumer selects its test-fixtures variant. Existing consumption of test fixtures from other modules remains unchanged. The regular test suite owns its existing fixtures, mocks and test-local state; production and test sources are unchanged.",
        "indirectBehaviours": "Removing the unused producer configurations/variant must neither hide a test nor break downstream compilation. All-project compileJava/compileTestJava and the same thirteen affected module test tasks execute in one offline Gradle invocation with --continue and the same resource flags as the base. No test filters, tags, assertions or execution settings change.",
        "evidence": "Baseline c00a0cb24b4c6000e090d5d09054c1e7e1d24e2f and candidate both pass all-project compileJava/compileTestJava and the thirteen affected module test tasks. For worker-core, both have 92 suites, 448 tests, 0 failures, 0 errors and 7 skips. Across all thirteen modules both have 9222 tests and 68 skips. tmp/f5-deps/base-gradle-test-counts.json is the saved baseline; candidate-gradle-test-counts.json, gradle-test-comparison.json, followup2-java-evidence.json and logs/followup2-java-comparison.log (all under tmp/f5-deps/) assert identical module counts, skipped check identities and failures. Baseline logs/base-gradle-compile.log and logs/base-gradle-tests-complete.log, and candidate logs/candidate-gradle-compile-tests-followup2.log record the commands and exit 0. No test source file changes. These preservation runs add no new checks; docs/reference/testing/test-intent.md reserves run-evidence.mjs evidence files for new checks, so incidental evidence stays under ignored tmp/.",
        "unresolvedRisks": "No unresolved difference in the requested compile/unit checks. Existing baseline skips remain the same checks. This evidence covers the requested unit suites; integration/system test tasks were not requested or run. Independent acceptance is still required."
      }
    }
  ]
}
```

## Acceptance records
