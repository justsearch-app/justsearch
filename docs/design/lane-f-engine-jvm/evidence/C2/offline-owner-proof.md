# C2-2 offline procedure ownership proof

September12, Windows/Temurin Java25. The implementation is one item after
1c419e91a, governed by [the result/shutdown cut](offline-owner-cut.md).
Focused, full build/PMD and hosted proof now pass at82e0e185d. Actual-model/API
and installed-path checks remain required. This item does not close C2-2.

## Implemented boundary

The coordinator owns one registered procedure executor for manual and automatic
triggers. The sampler retains only its timer. BrainRuntimeService carries the
admitted context and an actual completion stage; the recorded handler checkpoints
OfflineProcessingOutcome and the shared runner alone writes terminal state.
Single-flight and admission survive mode cleanup and actual task exit. The result
contains only captured counts, bounded reasons and embedding handoff metadata.
Only acknowledged index results advance counts; no-text/rejected/failed results
are failed units. A false or throwing write leaves that unit unfinished.

HeadAssembly drains the repeatable coordinator barrier before its closed CAS.
Headless and launcher cleanup retain dependent resources after a failed drain.
The test registry offers explicit awaited termination for the offline fixtures,
avoiding a fixture-only race at the production termination check while preserving
its default nonblocking behavior for deadline/retained-cleanup tests. Copied batch/coordinator
test implementations are retired in favour of calls to the real owners.

## Focused and negative checks

| Run | Result |
| --- | --- |
| 725–726 | Affected main compilation/PMD passes. New API Javadoc and future-composition warnings were corrected in726. Other existing compiler warnings remain in their original subjects. |
| 727–729 | Fixture correction runs: worker tests had constructor/checked-exception issues, a mixed Mockito matcher, and a non-waiting test registry. PMD also rejected redundant qualifiers. These are recorded red attempts, not proof. |
| 730 | PASS150 cases/36 suites, zero failures/errors/skips; affected test PMD. Services85 and UI25 execute; launcher40 reuses729. Architecture suites are included. |
| 731 | RED new integration fixture's close omitted the checked IOException declaration. Root also corrected its row comparison: durable attribution intentionally excludes process-local workId. |
| 732 | PASS13 cases/7 suites: real contention and recorded lifecycle fixtures plus included architecture cases; affected main/test PMD. |
| 733 | Intentional early-handler-completion mutation: all3 recorded lifecycle cases fail because completion publishes during held cleanup. |
| 734 | Intentional replacement of caller admission with internal backlog attribution: all3 lifecycle cases fail with ENGINE_LIMIT while the dispatch holds the only slot. Exact work linkage is necessary. |
| 735 | Intentional swallowed false index acknowledgement:4 cases fail for lost write refusal, advanced no-text/rejection counts, and displaced primary error. |
| 736 | Restored intended code: PASS156 cases/38 suites, zero failures/errors/skips. Services87, UI29 and launcher40 all execute; affected test PMD passes. Includes the real registry timeout/retry test. |
| 737 | RED full build:2,126 cases/293 suites observed,2 failures in EngineWorkCancellationTest. The newly waiting default test registry conflicted with tests that deliberately hold cleanup through close. No full pass is claimed. |
| 738 | Operation-surface and guard-resolution pass before explicit offline declarations. Their import scan does not discover every per-pass projection; registration is still required. |
| 739–740 | New mode-metadata regression setup:739 has a test generic-inference compile error (fixed);740 fails both new integration cases, including an absent-cursor NPE. Root adds an explicit non-null checkpoint assertion for a decisive witness. Narrowed registry deadline tests pass. |
| 741 | Negative before mode-metadata fix:33 cases/9 suites,6 intended failures (missing preselection cursor, postselection reason NONE, absent checkpoint-error preservation). The7 deadline cases reuse the narrowed-fixture pass. |
| 742 | PASS129 cases/23 suites, all freshly executed: services91, UI31, Engine7. Zero failures/errors/skips; affected main/test and core test-fixture PMD pass. Covers the mode-metadata and opt-in registry corrections. |
| 743 | Explicit offline producer/projection declarations pass operation-surface and register-guard-resolution. |
| 744 | PASS full build and PMD at82e0e185d:10,047 cases/1,636 suites, zero failures/errors,35 skips. Production and test inputs remained unchanged between configuration and commit. Captured XML and task execution/reuse ledger are retained before any focused rerun. |
| 745 | PASS named Lucene/ORT stress selection:2 cases/2 suites, zero failures/errors/skips. Lucene executes; ORT reuses its unchanged-input cache. This is the existing concurrency gate, not additional offline-owner coverage. |

The root read the negative XML failure messages and verified exact source bytes
were restored after each mutation. Each mutation is narrow; none modifies tests.

## Decisive fixtures

- OfflineProcessingOperationLifecycleIntegrationTest composes the real SQLite
  store, runner, admission controller, handler, Brain runtime, coordinator and
  batch processor. The initial dispatch admits work before acceptance and releases
  only its outer reference after starting. While endProcedure is held, the row
  remains RUNNING, admission stays occupied, and acknowledged checkpoints persist.
  Success, incomplete remainder and cancellation terminalize only after cleanup.
  Model/GPU/index ports are controlled doubles; this is not an actual-model proof.
- OfflineCoordinatorOwnershipTest covers queued/running cancellation, once-only
  ownership release, exact context, submission refusal and cleanup errors.
- VduBatchAcknowledgedOutcomeTest covers acknowledgement-only progress, partial
  failure, negative processing refusal, cancellation, mode-exit error preservation
  and circuit remainder. VduProcessorCancellationTest interrupts each model wait
  stage and checks rendered PDF cleanup, original cause and no later model call.
- VduConcurrentTriggerTest contends five real callers, refuses100 rapid triggers
  while the body is held, and admits the next pass immediately after completion.
- VduProcedureExecutorLifetimeTest uses the production registry and admission
  controller. Its actual two-second close timeout leaves the executor registered,
  admission occupied and completion pending; release plus retry terminates it.
- HeadAssemblyTest, HeadlessAppShutdownWiringTest and LauncherEnvironmentCloseTest
  prove dependency retention, close retry and the existing unclean-exit authority.

## Review corrections

Independent implementation review found one metadata gap: mode-entry failure
preserved its cause but omitted an explicit blocked checkpoint. The implementation
now emits AI_OFFLINE with zero selected before selection or the captured selection
afterward, then propagates the transition failure. Ordinary checkpoint-delivery
failure is suppressed on that primary; Error remains fatal with the mode failure
suppressed. Two real runner/store cases verify metadata and no document effects,
and four component cases verify failure/cause priority. Negative741 refutes the
uncorrected code;742 passes the correction.

Full737 also caught the root's overly broad test-registry change. Its default
nonblocking close is restored; explicit awaitingTermination is used only in the
exercised offline bootstrap and contention fixtures. No deadline test was weakened.
The7 EngineWorkCancellationTest cases pass freshly in742. Final independent
reread found no remaining source defect and independently inspected negative741,
positive742 and gate743 evidence. Full744 passes both corrections. Hosted
[CI34715474339](https://github.com/justsearch-app/justsearch/actions/runs/34715474339)
also passes at82e0e185d, including Windows-native and system integration jobs.
Named stress745 also passes. Actual-model/API and installed acceptance remain open.

## Evidence access

Retained worktree artifacts are `tmp/c2-2-offline-owner-*`: compile725/726,
focused727–730/736/742, integration731/732, negative733–735, full737,
mode-negative739–741, surface/guard738/743, full744 and stress745. Completed
test runs have copied `-xml/` and `-counts.json` beside their `.txt` build log.
Read the log with counts: passing test XML cannot excuse a compilation/PMD failure.
`tmp/c2-2-offline-negative.py` records each mutation and restores source in finally;
`tmp/c2-2-capture-generation.py` captures task execution/cache status and XML.
Retain through lane acceptance plus30 days; export before releasing the worktree.
