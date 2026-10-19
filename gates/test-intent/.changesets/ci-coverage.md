---
schema: test-intent.v1
task: ci-coverage
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# CI coverage and test lifetime cleanup

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessBoundTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessReserveTest.java",
        "modules/app-util/src/test/java/io/justsearch/app/util/AppInstanceLockExclusionTest.java",
        "modules/app-util/src/test/java/io/justsearch/app/util/AppInstanceLockFailureTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/PlatformPathsTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/AtomicFileWritesTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingDifferentVolumeTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxCommandTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Add the explicitly named cross-platform/windows class tags from CC4. All methods, assertions, OS guards, assumptions, prerequisites, timeouts and fixtures remain byte-identical. Portable classes retain their normal Linux coverage; native-only classes keep their OS-specific prerequisites. Windows scheduling expands to configuration and indexer-worker.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "No test removed or weakened; Linux selections unchanged except CC1\u2019s added steps.",
          "location": "task ci-coverage request"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/contract/contractEventsBridge.test.ts",
        "modules/ui-web/src/shell-v0/aggregate-substrate/components/JfResource.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts",
        "modules/ui-web/src/shell-v0/components/ResourceView.render.test.ts",
        "modules/ui-web/src/shell-v0/components/RetrospectivePanel.test.ts",
        "modules/ui-web/src/shell-v0/components/SourcesPane.test.ts",
        "modules/ui-web/src/shell-v0/state/searchState.scope.test.ts",
        "modules/ui-web/src/shell-v0/state/searchState.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installProgressAggregate.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.pausedInstall.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.skipReason.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sparkline.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.telemetry-mode.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthLitView.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.chatWidth.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.contextWindow.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.highContrast.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.extractResult.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Palette.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Pane.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Retain all test bodies and assertions except lifecycle cleanup additions: explicit unavailable-backend fetch fixtures replace accidental real HTTP requests in render/event tests; per-test transport overrides still win. Cancel pending search work before restoring transport fixtures, restore real timers, and await the Shell click persistence queue before its fixture is removed. No tests, expected values, fault injections or selection filters are removed.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "No test removed or weakened; Linux selections unchanged except CC1\u2019s added steps.",
          "location": "task ci-coverage request"
        }
      ]
    },
    {
      "items": [
        ".github/workflows/ci.yml",
        "scripts/ci/unit-test-shard-policy.v1.json"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Schedule the complete existing ui-web Vitest and lockdown suites as blocking steps in app-ui-ui. Add configuration/indexer-worker Windows test tasks and match the task inventory; Linux Gradle task lists and all timeouts stay unchanged. Failure-only E2E collection replaces whole fixture-tree uploads with bounded Engine, embedded-worker, supervisor and fixture output. The preflight reproduction inventory includes both frontend commands.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "No test removed or weakened; Linux selections unchanged except CC1\u2019s added steps.",
          "location": "task ci-coverage request"
        }
      ]
    }
  ]
}
```

## Builder execution evidence

- Baseline: detached worktree at 485fe86b8. Default full suite: 529 files / 6,886 tests pass,
  exit 0. Four workers: the same counts pass, but exit 1 with 15 EnvironmentTeardownError
  rejections. The supplied earlier before-vitest log records four such errors.
- CC2 already uses the repository fixture on this baseline. The unchanged focused test passes;
  a production mutation making successful execution events start a fresh status read fails its
  zero-fresh-reads assertion (exit 1). The mutation is restored in the detached worktree.
- First corrected full suite: 529 files / 6,886 tests, exit 0; no network errors or teardown errors.
  Twenty consecutive full runs passed: ten with four workers and ten with defaults, each with
  529 files / 6,886 tests. No teardown or network errors. A further full run tracing every
  Socket.connect also passes with zero connections. The frontend source digest stayed unchanged
  throughout the twenty runs (766d8a4637319ff7d9b3d8de0bc032b003c6dd93c95159705cd6757470c90d43).
- Network tracing identified incidental conversation/live-run, feedback/settings, condition-index,
  schema, source-content and session reads in render tests, plus pending search timers and a queued
  Shell mode write. Explicit transport fixtures and cleanup keep their existing unavailable-backend
  path without opening sockets. The selection registry is loaded with selectionState, preserving
  deferred projection with a microtask instead of a late dynamic import.
- Fourteen CI inventory/guard/reporter tests and checks pass. Collector tests pass 2/2, covering
  Engine/embedded-worker, supervisor, fixture and isolated boot logs; tail and total size bounds;
  omitted files; and symlink/junction exclusion. No separate Worker process exists on this baseline.
- Scratch commands, complete logs, mutation results and repeat-run ledger: tmp/ci-coverage/.
  Draft PR: https://github.com/justsearch-app/justsearch/pull/790.
  Candidate run: https://github.com/justsearch-app/justsearch/actions/runs/39245357387.
- CC1: both added steps passed on GitHub: full frontend 132 seconds, lockdown 2 seconds.
  The UI job passed in 12:43. The source fixture was already repository-relative at the baseline;
  an additional replay-only production mutation fails with exactly one fresh read instead of zero.
- CC4: AppInstanceLockExclusionTest ran four passing cases. AppInstanceLockFailureTest has
  three distinct cases, two failing, repeated across three attempts (nine physical executions,
  six failures, no skips). Both failures encounter a null channel in the Windows native-lock path.
  No assertion was changed. Gradle stopped there: the remaining eleven requested classes and
  the second-volume prerequisite are unverified. Full XML and logs: tmp/ci-coverage/windows-results/
  and tmp/ci-coverage/logs/windows-contracts.log. The Windows job failed in 11:33, leaving 13:27
  of its unchanged 25-minute timeout; this is not a successful full-coverage timing.
- CC5: the first forced-failure probe produced real Engine, embedded-worker, supervisor and fixture
  output for both Windows tiers. Lifecycle: 30 files / 2,404,968 bytes, successful collection.
  Integration hit the bounded entry scan inside corpus content. The collector now visits only
  the harness log directories and top-level output; a corpus-exclusion regression fails before
  that correction and passes after. The same temporary probe PR is rerun for final evidence.
  Both existing jobs are Windows-only; adding Linux E2E jobs contradicts CC6's selection limit
  and awaits an orchestrator decision. The collector itself is tested on Linux.
- CC7: fourteen CI guard/reporter checks and repository-wide actionlint pass. Local tagged Java:
  128 cases, 24 skips, zero failures (Linux only). Public claims detects five existing unused
  registry exports after the static import makes their usage visible; disposition is outside this
  brief. No dead-code baseline is changed. The test-intent entries validate; the only remaining
  test-intent finding is the deliberately absent independent acceptance record.

Independent verification remains outstanding. No acceptance record is authored here.
