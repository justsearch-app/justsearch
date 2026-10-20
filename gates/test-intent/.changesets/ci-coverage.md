---
schema: test-intent.v1
task: ci-coverage
author-role: builder
author-session: 01a153ce-3f19-7fa0-a7cf-5b554794f7f8
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
      "class": "New",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Add the thirteen named deletion-safety, persistence and process-control classes to Windows CI using cross-platform tags for portable classes and windows tags for native-only classes. Preserve their assertions, fault injections, OS guards, assumptions and timeouts; retain existing Linux execution.",
          "label": "agent choice"
        }
      ],
      "behaviour": "New execution coverage: these thirteen existing classes are selected by Windows CI, without changing their oracles. The Java D4 evidence and newChecks cover the Linux-runnable checks in ten tagged classes; method IDs exclude existing Windows-only assumptions rather than counting skipped cases as passes. The remaining Windows-only checks, including WindowsJobWitnessBoundTest, WindowsJobWitnessReserveTest and PruneMissingDifferentVolumeTest, have real Windows execution proof from hosted run 39339963464 (https://github.com/justsearch-app/justsearch/actions/runs/39339963464), job 118358773145: AtomicFileWritesTest: 12 executed, 0 skipped; ContendedFileReadsTest: 6 executed, 1 skipped; PlatformPathsTest: 7 executed, 0 skipped; AppInstanceLockExclusionTest: 4 executed, 0 skipped; AppInstanceLockFailureTest: 5 executed, 0 skipped; DefaultChildControlTest: 25 executed, 0 skipped; WindowsJobWitnessBoundTest: 1 executed, 0 skipped; WindowsJobWitnessReserveTest: 1 executed, 0 skipped; ExtractionSandboxCommandTest: 13 executed, 0 skipped; RootPresenceGuardTest: 5 executed, 0 skipped; UncertainAbsenceGatesTest: 9 executed, 0 skipped; PruneUncertainAbsenceTest: 5 executed, 0 skipped; PruneMissingDifferentVolumeTest: 3 executed, 0 skipped. The only Windows skip is ContendedFileReadsTest.reopensAfterContentionWhenWriterRotatesThePathBeforePublishingReplacement(), which is @EnabledOnOs(OS.LINUX); it executes in the Linux D4 run. All three PruneMissingDifferentVolumeTest cases executed on Windows, so the second-volume prerequisite was met. No repaired product behaviour or assertion fail-before is claimed. The path-canonicalization fixture adaptation is recorded separately.",
      "repaired": false,
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java#aRootRenamedAwayBeforeThePruneLosesNoDocuments",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java#aRootRenamedAwayMidPruneStopsTheRemovals",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java#aGenuinelyDeletedFileIsStillPrunedInOneRun",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java#aStoredPathThisSystemCannotParseDoesNotHaltGenuineDeletions",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java",
        "modules/app-util/src/test/java/io/justsearch/app/util/AppInstanceLockExclusionTest.java",
        "modules/app-util/src/test/java/io/justsearch/app/util/AppInstanceLockFailureTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/PlatformPathsTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/AtomicFileWritesTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxCommandTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#admissionKeepsTheSourceWhenItsRootWasRenamedAway",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#admissionStillRetiresAGenuinelyDeletedSource",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#aFileReplacedByADirectoryIsRetired",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#theFreshnessCheckKeepsTheDocumentWhenTheRootWasRenamedAway",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#theFreshnessCheckStillRetiresAGenuinelyDeletedSource",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java#aPresentRootVouchesUntilItIsRenamedAway",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java#aRootMissingAtCaptureNeverVouches",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java#aRecordedVolumeIsForgottenWhenTheRootIsRemoved"
      ],
      "evidence": "gates/test-intent/evidence/ci-coverage-java.json"
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
      "whatMoved": "Retain all test bodies and assertions except lifecycle cleanup additions: explicit unavailable-backend fetch fixtures replace accidental real HTTP requests in render/event tests; per-test transport overrides still win. Cancel pending search work, including Shell URL-restore staging, before restoring transport fixtures, restore real timers, and await the Shell click persistence queue before its fixture is removed. No tests, expected values, fault injections or selection filters are removed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "No test removed or weakened; Linux selections unchanged except CC1\u2019s added steps.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        ".github/workflows/ci.yml",
        "scripts/ci/unit-test-shard-policy.v1.json"
      ],
      "class": "Structural rule kept",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Schedule the full frontend suites and thirteen Windows safety classes as required coverage, preserve existing Linux selections and timeouts, retain every original E2E diagnostic upload, and enforce exact task ownership, class execution and the explicit skip list.",
          "label": "agent choice"
        }
      ],
      "rule": "Existing required CI test steps remain blocking, inventories own every command/task, original integration and lifecycle diagnostics retain their exact upload paths and always() conditions, and no existing test assertion, selection or timeout is weakened. Every Windows safety class must execute; only the explicitly named prerequisite skips are allowed.",
      "whySurvives": "The predicates are unchanged or stricter: full ui-web Vitest and lockdown become additional blocking Linux steps with four workers; configuration and indexer-worker are added to the Windows task inventory, with --continue so other modules execute after a failure. Existing Linux Gradle selections and job timeouts remain unchanged. Both original E2E upload steps are preserved exactly from pinned main, including whole writer-junit fixture trees under always(); the bounded child-log collector and upload also run always() as additional artifacts. The pinned upload-list policy forbids removing paths or narrowing conditions. The Windows reporter adds an execution requirement for each of thirteen classes and rejects all unlisted skips, including when allowedSkips is absent; only the named Linux-only ContendedFileReadsTest rotation and the two named missing-second-volume prune cases may skip. The inventories and preflight commands include the added tasks and both frontend suites; no existing constraint is relaxed."
    },
    {
      "items": [
        "modules/ui-web/src/__test-setup__/streamViaHostSuppress.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Shared Vitest cleanup waits for pending dynamic imports with vi.dynamicImportSettled before disposing each test environment. Preserve the production selectionState lazy dependency graph from main instead of making it static. No exception is ignored or filtered and every existing assertion is retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Fix the causes: work still running or importing after a test ends.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/__test-setup__/dynamicImportLifetime.test.ts",
        "modules/ui-web/src/__test-setup__/__fixtures__/delayedImport.ts"
      ],
      "class": "New",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Fix the causes: work still running or importing after a test ends.",
          "label": "agent choice"
        }
      ],
      "behaviour": "A detached dynamic import and its completion callback finish before the test environment is disposed; deliberately delayed module evaluation must complete before the next test starts.",
      "repaired": true,
      "newChecks": [
        "modules/ui-web/src/__test-setup__/dynamicImportLifetime.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/ci-coverage-import-lifetime.json"
    },
    {
      "items": [
        "modules/app-util/src/test/java/io/justsearch/app/util/AppInstanceLockFailureTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Only the fixture path representation moves: the @BeforeEach hook canonicalizes the existing @TempDir with toRealPath(), matching acquire() before FileChannel.open failure injection. This still names the same temporary directory and lock file, including Windows temporary-directory aliases. Each existing test body, assertion, native lock boundary, injected error, expected outcome and prerequisite is retained. The cross-platform class tag is the separate New execution-coverage change in entry 1.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Canonicalize AppInstanceLockFailureTest temporary-directory aliases so its existing FileChannel.open failure injection reaches the same native lock boundary on Windows and Linux without weakening any assertion.",
          "label": "agent choice"
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
  path without opening sockets. The initial candidate loaded the selection registry with selectionState; round four supersedes
  that production change with shared test cleanup, preserving main’s lazy import graph.
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
  five distinct cases, two failing and retried twice (nine physical executions,
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
  Both existing jobs are Windows-only. The round-three decision keeps Linux installed-Engine E2E
  outside this PR; no hosted Linux integration or lifecycle job is added. The collector itself
  is tested on Linux.
- CC7: fourteen CI guard/reporter checks and repository-wide actionlint pass. Local tagged Java:
  128 cases, 24 skips, zero failures (Linux only). Public claims detects five existing unused
  registry exports after the static import makes their usage visible; disposition is outside this
  brief. No dead-code baseline is changed. The test-intent entries validate; the only remaining
  test-intent finding is the deliberately absent independent acceptance record.

## Round three

- Rebased onto origin/main c933b207f before building; wrapper is Gradle 9.8.0.
- The Windows XML shows an unstubbed static FileChannel.open returning null, rather than the
  injected IOException. Production canonicalizes the directory with toRealPath before opening;
  the fixture formerly matched the uncanonicalized @TempDir path. Canonicalize that fixture
  before each test. No product code or assertion changes. Windows Gradle now uses --continue.
- Repository-wide reference search retains registerSelectionAction (coreSelectionActions.ts)
  and onSelectionActionChange (SelectionActionsMenu.ts), both called by source modules. Removed
  unused unregisterSelectionAction and __resetSelectionActionsForTest; made the internally used
  SelectionActionPresentation interface private. No export is used only by tests.
- Linux currently runs system-tests:test in its unit shard and the child-log collector tests.
  Neither installed-Engine E2E Gradle tier has a hosted Linux job. As a scheduling proxy only,
  the preceding Windows run 39248010984 took 24:34 for integration and 6:51 for lifecycle:
  31:25 runner time together; equivalent jobs would reserve up to 35 minutes each (70 total).
  Actual Linux durations remain unmeasured. The user explicitly deferred adding these jobs.
- Rebased frontend verification: vitest run --maxWorkers=4 passes 529 files / 6,886 tests;
  lockdown passes 10 tests. Lint and typecheck pass. Sixteen current CI inventory/guard/reporter
  commands pass; test-report-flake-trend.mjs was removed on main by 99ef79845 (#796).
- Actionlint passes ci.yml. Repository-wide actionlint fails on docs-lint.yml's existing
  justsearch-perf runner label; the detached before worktree produces the same failure.
- Against the merge-base, the dead-code gate reports two remaining unused exports (both retained
  source callers above). The test-intent entries all validate; independent acceptance is absent
  as instructed. Neither gate's baseline nor enforcement is changed.

## Round four

- Knip config is unchanged from the merge-base: explicit mock/script entries plus Vite/Vitest plugin
  entry points; src/** is the project inventory, not a promise to count imports from unused files.
  Both coreSelectionActions.ts and SelectionActionsMenu.ts are whole-file-unused findings on main
  and this branch. Their imports therefore do not consume reachable exports.
- Main’s import(...).then(...) is opaque to installed Knip: imports.js handleImportExpression
  sets IMPORT_FLAGS.OPAQUE, and graph-explorer/operations/is-referenced.js treats that as a
  reference to every export. The static named import removed that opaque reference and exposed
  the two existing unreachable exports. Reports and unchanged config comparisons are retained
  under tmp/ci-coverage/round4/. Main’s dead-code gate exits 0; the preceding candidate exits 1.
- Restore selectionState.ts byte-for-byte to the merge-base, preserving the existing production
  lazy dependency and cycle boundary. Fix the test lifetime at its owner: shared afterEach waits
  for dynamic imports, including nested work, before environment disposal. A delayed-import
  regression fails before that cleanup and passes after. No Knip allowlist, configuration,
  baseline or enforcer change is made.
- Repository-wide base/current searches find no external consumers of unregisterSelectionAction,
  __resetSelectionActionsForTest or SelectionActionPresentation. The first two stay removed;
  SelectionActionPresentation remains an internal interface. Both used value exports are retained.

- The evidence runner records an assertion failure before cleanup and both new lifecycle cases
  passing after it. Its focused runs used VITEST_MAX_WORKERS=4 (the installed Vitest configuration
  maps that environment variable to maxWorkers). A separate deliberate rejected-import probe
  still exits 1 with one unhandled error while its test passes: cleanup does not suppress errors.
- Local typecheck and lint pass. The dead-code gate passes; test-intent has only the expected
  missing independent acceptance record. All flagged changes and new evidence validate.

- The one final local full run exits 0: 530 files / 6,888 tests, zero teardown errors.
  It exposed a staged search timer left by Shell’s URL-restore test: one real socket to
  localhost:3000/api/knowledge/search produced two logged AggregateErrors. Cancel that search
  through resetSearchState in the owning Shell cleanup before restoring fetch. Focused verification after the timer cleanup passes all 23 files / 471 tests with zero socket
  connections, connection errors or teardown errors. The full suite is not repeated locally.

Independent verification remains outstanding. No acceptance record is authored here.

## Round five

- Merged origin/main c00a0cb24 with merge commit 96e79c14d; Gradle remains 9.8.0.
- Restored SelectionActionPresentation, unregisterSelectionAction and
  __resetSelectionActionsForTest exactly as on main. The dead-code gate passes with
  all three restored. Production cleanup is outside this PR.
- Both E2E tiers always upload the curated isolated-backend tree directly. The bounded
  collector additionally retains gzip rotations, crash diagnostics and stop/intruder JSON
  reports. Oversized gzip logs retain a valid recompressed tail. Eight new cases each
  fail against the preceding collector (2 pass / 8 fail); all ten pass after the repair.
- The Windows safety execution step uses the existing attribution reporter and the
  policy's thirteen required classes. It reports each class's executed count and skip
  names. Missing/all-skipped classes fail; the prune class permits only the two named
  second-volume cases with the explicit missing-volume assumption. Its same-volume
  case must execute. Existing Linux-only ContendedFileReadsTest skip remains reported.
- Full command output, tag-removal counterexample, frontend and hosted evidence for
  this round live under tmp/ci-coverage/round5/. Independent acceptance remains absent.

- After the merge, the single full local Vitest run passes 534 files / 6,971 tests
  in 63.06 seconds with zero teardown or connection errors. Both newly merged
  MarkdownBlock.rendering.test.ts and Sv3Main.citations.test.ts also pass a named
  focused run: 30 cases, zero teardown errors. Lockdown passes 10 cases; frontend
  typecheck and lint pass. All seventeen CI guard/reporter/actionlint commands pass.
- The merge-base test-intent run moves both root and ui-web node_modules aside for
  its duration and restores them afterward. Its only error is the expected absent
  independent acceptance record; all changeset entries and existing evidence validate.

## Round six

- Merged origin/main f9bd104d2 via merge commit 905ec372f. No local Gradle runs: the
  shared lock is heavily contended, and these repairs are checked at Node level and in CI.
- Restore the original integration/lifecycle upload steps byte-for-byte from main,
  including the complete writer-junit fixture tree and always() condition. The filtered
  collector is additional evidence only; collection and child-log uploads also run always().
  Its caps no longer replace or limit any existing diagnostic artifact.
- Pin main's upload path lists and conditions at f9bd104d228c80173d58068cfe0f24c6d9ced149
  in scripts/ci/e2e-upload-policy.v1.json. Both preservation cases fail on aae3c6d9f
  for changed/missing paths, pass on pinned main, and pass after restoration.
- Before tightening the guard, inspect the latest Windows XML (run 39334194117).
  Its only skip is ContendedFileReadsTest's named rotation case: @EnabledOnOs(OS.LINUX),
  recorded by Gradle as an empty skipped element. Explicitly name it and document that
  reason. Preserve the two existing missing-second-volume exceptions by name and reason.
  An omitted allowed-skip list now means no skips are allowed. A synthetic AtomicFileWritesTest
  result with one executed case and eleven unlisted skips passes the old guard and fails
  the repaired guard. The repaired guard still passes all actual latest Windows XML.
- Every scenario source formerly quoting the orchestrator as owner words is now an
  agent choice under the owner's quoted delegation. The author-session remains unchanged.
- Complete logs, before/main shared clones, source fixtures and hosted results for this
  round are under tmp/ci-coverage/round6/. Independent acceptance remains absent.

- The owner authorized a valid task-citation prefix for the gate. Each delegationLocation
  begins with task t-20261018-6108b0 request; the complete supplied citation follows verbatim.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "ae1dc1409c1bf4f02",
  "verdict": "accept",
  "digest": "sha256:5bdedb5d976ea25144d5eafad34b75dff5a16ff34276433a0b54e27f6d347473"
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "auditor-ci-coverage-cc6d91b2",
  "verdict": "reject",
  "digest": "sha256:5bdedb5d976ea25144d5eafad34b75dff5a16ff34276433a0b54e27f6d347473",
  "note": "Entries 1 and 3 expand Windows tags and CI execution coverage; Adaptation requires the whole oracle, including tags and conditions, to be kept. Reclassify the new coverage and retain separate adaptations."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "ae1dc1409c1bf4f02",
  "verdict": "accept",
  "digest": "sha256:1c640b967fa01079c80ef38dbcdb226536aede1f0556e2f342022325efee8b24"
}
```
