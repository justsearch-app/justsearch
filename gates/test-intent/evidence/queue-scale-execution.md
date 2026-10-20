# Queue-scale execution protocol

Task t-20261020-540095, builder session `01a1562b-b2e9-7510-832f-04ea6ad6da28`.
Changeset: `gates/test-intent/.changesets/queue-scale.md`. No acceptance record.

All receipts are written by the unchanged
`scripts/governance/gates/test-intent/run-evidence.mjs`, never hand-edited. Its entire lifecycle,
including candidate Gradle/Vitest runs, before-worktree creation, overlay, checks and cleanup,
runs under `js-gradle-slot`. The original-base receipt uses a shared clone with unchanged tests;
the other before checkouts are the runner's own disposable worktrees. All temporary paths and
throwaway indexes are under the task TMPDIR, not `/tmp`.

## Environment and runner selection

- TMPDIR: checkout `tmp/builder/test-intent`.
- GRADLE_USER_HOME: its `gradle-home`; caches/wrapper/jdks reuse installed Gradle artifacts;
  daemon registry and init script are private to this run.
- JAVA_TOOL_OPTIONS: `-Djava.io.tmpdir=<TMPDIR>`; Test JVMs also receive that property.
- JUSTSEARCH_HOME and JUSTSEARCH_DATA_DIR: distinct task-local home/data directories. No
  installed Engine is launched for this metadata task.
- The committed runner groups Java checks by module and invokes `./gradlew ...:test --tests ...
  --rerun -PskipWebBuild=true --console=plain`; browser checks use its Vitest JSON reporter.
  No governance tooling or wrapper script is modified.
- A private Gradle init script limits Gradle workers to two. Normal runs keep the default evidence
  tag exclusion. For million-row runs only, `QUEUE_SCALE_EVIDENCE=1` removes that exclusion from
  indexer-worker's selected `test` task, adds the same agent/heap/opens/properties as the committed
  `queueScaleEvidence` task, and forces one fork after project configuration. This is necessary
  because the evidence producer maps source-set `test` to the `test` task, not a custom task.
  It does not change repository build configuration or the default test task's normal selection.

The private init script (recreate under GRADLE_USER_HOME/init.d for a rerun):

```groovy
gradle.startParameter.maxWorkerCount = 2
allprojects {
  tasks.withType(Test).configureEach {
    systemProperty 'java.io.tmpdir', System.getenv('TMPDIR')
    maxParallelForks = 1
  }
}
gradle.projectsEvaluated {
  if (System.getenv('QUEUE_SCALE_EVIDENCE') == '1') {
    def worker = gradle.rootProject.findProject(':modules:indexer-worker')
    if (worker != null) worker.tasks.named('test', Test).configure {
      useJUnitPlatform { excludeTags.remove('evidence') }
      maxHeapSize = '1g'
      maxParallelForks = 1
      dependsOn(worker.tasks.named('queueScaleHeapAgent'))
      jvmArgs '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.util=ALL-UNNAMED', '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED'
      jvmArgs "-javaagent:${worker.tasks.named('queueScaleHeapAgent').get().archiveFile.get().asFile.absolutePath}"
      systemProperty 'queue.scale.output', worker.layout.buildDirectory.dir('queue-scale-intent').get().asFile.absolutePath
      systemProperty 'queue.scale.classpath', classpath.asPath
    }
  }
}
```

## Receipts and before-state scope

| Receipt | Before revision / purpose |
|---|---|
| queue-scale-current.json | Final candidate checks for every runnable flagged default test file; all original assertions plus added default checks execute. |
| queue-scale-original-base.json | Unmodified c00a0cb24 originals: StatusStateProducersTest; RemoteIndexingJobsBridgeTest.statusCounterTracksJobsAndNotReadOnlyReattachment; QueueProjectionCommitTest.subscriberClaimSurvivesEnqueueAndCompletionBookkeeping. These are the unchanged-oracle counterparts of the Adaptation entries. |
| queue-scale-repairs-e890.json | e8903c54b: cursor/count/backoff/overflow/observer isolation/rail/Resource/cache repairs. Reopen reconciliation and terminal no-replay are preservation checks and pass on both revisions. |
| queue-scale-repairs-base.json | c00a0cb24: window/cache/churn, state-presence, coalescing barrier, query-plan/readiness/claim-budget, worker wire and browser reducer repairs. The snapshotCapturesPriorState counterpart is preservation under its eligible-row inputs. |
| queue-scale-harness-current.json | Final candidate million-row heap/latency/convergence/migration/crash/reopen checks plus 100k random operations and default count/migration instrumentation. |
| queue-scale-harness-before.json | c00a0cb24: Q1–Q6 named measurements; assertion fail-before against the old full-table mirror and query/claim implementations. |

Repaired entries cite only checks with assertion fail-before. A mixed receipt may have producer
exit1 because a deliberately included preservation check passed before; its repaired checks still
must assertion-fail before, and every candidate check must execute and pass. The batch distinguishes
that expected preservation result from compile, environment, exception, missing or skipped checks;
none of those count as a repair proof.

New internal counter/schema/selector instrumentation does not fabricate an implementation of a
previously absent API/table to make it fail. Those entries explicitly state their preservation/new
instrumentation scope; the corresponding observable Resource/rail, performance and raw REPLACE
repairs have separate assertion fail-before receipts. The locale regression guard preserves working
base behavior: the locale formatting risk was introduced and fixed inside this branch.

## Compatibility scaffolding (before states only)

The runner records overlay paths and the exact patch digests. All production repair implementations
remain absent on the before-state:

- c00a0cb24 overlays the current `IndexingJobChangeFeed` and `indexing.proto` shapes. The new
  Resync variant and optional presence fields exist as types; old producers still do not produce
  resync/presence or bounded snapshots. Old three-argument snapshot constructors retain null
  presence. `queue-scale-base-compat.patch` supplies default readiness by the old count read,
  a false budget marker, an unused existence-query constant for plan instrumentation, and an
  empty fallback for the previously nonexistent Resync variant so the sealed switch compiles.
  It adds no bounded mirror, indexed reader, skip cursor, presence tracking or serialization.
- The count-test connection helper is overlaid for tests that reference it. It is test code only;
  the absent old job_counts table is not created as scaffolding.
- Million-row before runs additionally overlay the harness's QueueScale helper classes, original
  v13 SQL fixture and indexer-worker build file to supply the agent/evidence task definition.
  They add test infrastructure, not the v23 schema/trigger/index implementation.
- e8903c54b overlays only the unused StreamOverflowException type and applies
  `queue-scale-e890-compat.patch` for ledger channel constructor injection. Synchronous broadcast,
  ordinary escalating backoff, untyped overflow and cursor/count bugs remain. The injected owned
  ledger channel still waits synchronously up to its existing one-second bound; the new check's
  250ms assertion detects that stall.

## Assertion instrumentation in this metadata preparation

Two branch-new checks now wrap their Future waits in assertDoesNotThrow, as the test-intent
rules require for a no-timeout repair. Cancellation keeps its one-second limit; that wait already succeeds on the base. Its fail-before is the final empty identity-cache assertion, so it proves bounded cache cleanup rather than a new cancellation-deadlock fix. The terminal-ledger
check strengthens its original one-second wait to250ms, below the old channel's one-second
synchronous bound, to remove a boundary race; scan attribution, two accepted outcomes and
snapshot no-replay assertions are unchanged. Both changes are listed under New, not Adaptation.

An intermediate probe restored the original snapshotSeq>=2 assertion. It passed on main but failed
on the candidate because unobserved writes are no longer materialized/emitted. The probe is removed;
that old expectation is explicitly listed as Obsolete, warranted by the delegated no-subscriber
materialization choice. The final coherent snapshotSeq==currentSeq and no-live-replay checks remain.
Raw attempt logs, source comparisons and discarded-attempt receipts stay in ignored task temp;
only runner-produced final receipts are cited by the changeset. Exact unchanged assertion calls for the three Adaptation scopes are recorded in queue-scale-assertions.md; the unmodified shared baseline clone was removed after its successful receipt was written.

## Runner invocation reconstruction

For each receipt, use its `checks[].id` values as repeated `--check` arguments and its path as
`--out`. For repair receipts also pass `--before` with the table's revision and
`--before-root "$TMPDIR"`; add the recorded `failBefore.overlays` and the matching compatibility
patch above with `--overlay` / `--before-patch`. Run the entire command as:

```sh
js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs --out <receipt> --check <id> ...
```

Enable `QUEUE_SCALE_EVIDENCE=1` only for the two harness receipts, with the private init script
above. The original-base receipt was produced by the unchanged runner on a shared clone at
c00a0cb24, with the original three check scopes and no overlays. A before check that is marked
`passed` is a preservation result; it must not be relabelled as a repaired expectation.
