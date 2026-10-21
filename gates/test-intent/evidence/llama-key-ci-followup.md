# PR #838: CI follow-up after merging main

Author session: `01a15751-d591-7723-a5d5-9aa16695d081`.

Merged `origin/main` (`4f4e20514ff82dd9d698661ef09dc646ee643311`) into
`worktree-llama-key` in merge commit `2b775d981`, with message
`Merge origin/main into worktree-llama-key`. No later commit was made.
This updates the local merge-base to the version integrated by CI rather than
keeping acceptance bound to the previous base `50eafae23`.

## Failed Windows attempt

Source: [CI run 39576025707, Windows lifecycle job 119141641641](https://github.com/justsearch-app/justsearch/actions/runs/39576025707/job/119141641641).
Downloaded `lifecycle-integration-tests-child-logs-attempt-1` and
`lifecycle-integration-test-results` with the requested gh 2.63.0 binary. The
JUnit XML contains both the first failing testcase (208.5 seconds) and the
retry that passed (129.992 seconds). The first failure was an expected-zero
fixture exit assertion in `EngineSupervisedRecoveryE2ETest.runScenario`, with:

> Error: timeout waiting for index FAILED after recovery attempt 2 with refusal evidence

The first attempt's directory is `writer-junit-2193e73f-c8ac-4605-91fc-44fc889dcf1a`,
run ID `80c8727b-cbef-4a87-85b3-809276f1aca2`, first Engine PID 1512.
The retry's distinct directory is `writer-junit-354b6b51-0138-4f78-a603-024854a471ff`;
it must not be used as evidence of what the failed attempt did.

The first incarnation's `engine.log` shows:

- 10:05:40.5099861Z: initial `Failed to acquire index root lock`.
- 10:05:50.6484347Z: recovery 1 failed on that same root lock.
- 10:06:10.494692Z: recovery 2 instead logged `Error closing in-process worker host`,
  `IllegalStateException: Live Engine work retains the index owner`, at
  `EngineRoot.closeIndex:1463`, through `closeForRecovery`,
  `KnowledgeServerBootstrap.closeLocked`, `EngineRoot.recoverIndex`,
  `KnowledgeServerBootstrap.recoverIndex`, and
  `KnowledgeServerHealthMonitor.attemptComponentRecovery`.
- 10:06:10.4976952Z: `component.recovery_failed` was published.
- 10:06:20.5006368Z: `component.recovery_exhausted` was published.
- 10:06:20.5160505Z–10:06:20.6956324Z: ordered restart shutdown completed
  `clean=true, errors=[]`. The runner recorded exit 5 and one counted restart.

The harness's `isCausalIndexLockFailure` specifically requires recovery-2
evidence containing both `index base path is already locked by another process:`
and the exact lock pathname. The guard failure above is a different cause, so
the harness correctly did not accept it as its intended root-lock refusal.
The inference that the timeout follows this different recovery failure is
supported by the exception and the predicate; this is not evidence that the
harness simply missed a brief matching status.

## Does this PR participate?

The Engine instantiates shared child control and uses it for extraction children
in this scenario. Its index itself runs in process. The failure's status says
`generative=ABSENT`, `inference.phase=OFFLINE`, `chatEnabledSpec=false`, and all
inference restart/transition counters are zero. There is no chat launch.

`DefaultChildControl.Held` creates a descendant scope only for `LLAMA_SERVER`
and `LLAMA_SELF_TEST`; the admission barrier is restricted to those same kinds.
The new cleanup within `runStop` additionally requires `!isWindows()`.
`endScope` and its changed member loop are therefore not executed for this
model-free Windows index recovery. The existing Windows root stop implementation
is unchanged. The warning concerns a concurrent map in chat-scope cleanup, not
an exception in this failed job.

`EngineRoot.java`, `modules/app-services/.../services/worker/`,
`EngineLifecycleE2ETest.java`, and `index-lock-recovery-scenario.mjs` have no diff
against merged `origin/main`. In particular, the exact `closeIndex` guard and
the failing recovery path already exist on main. The `EngineRoot` guard, health monitor, and harness file are also byte-identical
to CI main `037c651e1`; the later bootstrap-only main change bumps the help-file
version from v3 to v4. The original branch diff from `50eafae23` to `c7d5af4dc`
does not change any of these four files. The stack contains none of the
changed chat cleanup methods. On this evidence the failure is outside this PR's
changes; no index-recovery behavior or lifecycle expectation was changed here.

## Recent CI comparison

Queried `gh run list -w CI` for the latest 60 runs across branches and the latest
35 main runs, then fetched each relevant run's jobs with `filter=all`. Of the
35 main lifecycle jobs, 24 succeeded and 11 were cancelled; none failed. The
14 jobs inspected on other development branches all succeeded. This does not
establish that main reproduced the failure: no such reproduction was found.
The earlier run of this PR, `39570856288`, also had a successful lifecycle job.

Representative logs:

- [main 037c651e1, run 39574971469](https://github.com/justsearch-app/justsearch/actions/runs/39574971469/job/119138125724): the named exhaustion check passed, build succeeded.
- [main 4f4e20514, run 39579148397](https://github.com/justsearch-app/justsearch/actions/runs/39579148397/job/119152866420): the named exhaustion check passed, lifecycle job succeeded.
- [engine-recovery branch, run 39575869488](https://github.com/justsearch-app/justsearch/actions/runs/39575869488/job/119146417199): the named exhaustion check passed, lifecycle job succeeded.
- [prior key branch, run 39570856288](https://github.com/justsearch-app/justsearch/actions/runs/39570856288/job/119124365016): the named exhaustion check passed, lifecycle job succeeded.

Raw downloaded artifacts, API job metadata, comparison logs, and extracted
first-assertion text are retained under
`/home/elias/.cache/justsearch-llama-key-builder/ci-followup/`, with the requested
child artifact under the sibling `ci-logs/`. No Windows host was available for
a local rerun.

## Collection warning repair

`notEndedMembers` is a `ConcurrentHashMap`, whose iterator permits concurrent
removal. The loop nevertheless removed from the same map it was iterating,
which triggered Error Prone's `ModifyCollectionInEnhancedForLoop`. It now walks
`List.copyOf(held.notEndedMembers.entrySet())`. Conditional `remove(key, handle)`
still protects a replaced entry and still emits the exit note exactly when
that entry was removed. No assertion or test fixture was changed for this repair.

## Validation failure retained

The batched candidate run included the full UI suite. It reported 2,069 cases,
one failure and one skip: `VerifierStatusIsolationTest#repeatedFreshDuringHangDoesNotPileUpSamplerThreads`.
Its exact assertion was `status must be cheap; latency=652 ==> expected: <true>
but was: <false>`, at the shared `get` helper's line 73 (test call at line 141).
The existing limit is 500 ms. The test and its limit are unchanged against main.
It constructs a `StatusLifecycleHandler` with mocked `OnlineAiService` and
`KnowledgeServerBootstrap`; it never starts a chat child or enters `endScope`.
This remains a failed validation result, not a passed check. No retry, timing
relaxation, skip, or unrelated status fix was made. No baseline reproduction of
this separate timing failure is claimed. The original XML is preserved in
`ci-followup/TEST-io.justsearch.ui.api.VerifierStatusIsolationTest.xml`.

## Candidate suite results

Ran one invocation, with `JAVA_TOOL_OPTIONS` unset and `TMPDIR` set to the
assigned builder folder:

```text
js-gradle-slot ./gradlew :modules:app-api:spotlessApply :modules:app-api:test :modules:app-inference:test :modules:app-services:test :modules:ui:test --continue -PskipWebBuild=true --console=plain
```

Actual result: exit 1, `BUILD FAILED in 11m 36s`, solely `:modules:ui:test`.

| Module | Cases | Failures/errors | Skips |
| --- | ---: | ---: | ---: |
| app-api | 463 | 0 / 0 | 5 |
| app-inference | 560 | 0 / 0 | 1 |
| app-services | 4,050 | 0 / 0 | 11 |
| ui | 2,069 | 1 / 0 | 1 |

Total: 7,123 passed, one failed, 18 skipped. The sole failure is detailed above.
The unchanged `ChatLaunchCompatibilityTest` had five cases: three passed and
two platform skips; no failures. `:modules:app-api:compileJava` executed and no
`ModifyCollectionInEnhancedForLoop` warning occurred. Other existing compiler
and runtime warnings remain. Full XML files were archived before the evidence
producer's filtered reruns, under `ci-followup/suite-<module>-xml/`; the JSON
summary is `ci-followup/suite-summary.json`.

## Refreshed cleanup execution evidence

The unchanged official producer ran under one `js-gradle-slot node ...` holder
(as previously authorized), with no nested slot invocation. It exited 0 and
wrote `llama-key-descendant-cleanup.json` at `2026-10-21T11:04:26.159Z`, revision
`2b775d98183c5599c9da6adc435b282cfc0d7603` with the uncommitted snapshot repair.
All nine candidate checks executed and passed, none skipped. All nine checks
on historical parent `0109762bc16244364dacdff4752a95521d8d9275` were classified
as assertion failures. Candidate Gradle exits were `[0, 0, 0]`; before-state
Gradle exits were `[1, 1, 1]`. No production overlay or compatibility patch
was used. These are the existing cleanup expectations; the snapshot-only
warning repair introduces no new test expectation. The producer's sealed JSON
contains the actual failing output.

`git worktree list --porcelain` confirmed no remaining worktree under the
assigned builder folder after producer completion. All prior acceptance
records are retained byte for byte.

## Scoped build and formatting

```text
js-gradle-slot ./gradlew :modules:app-api:spotlessCheck :modules:app-api:build :modules:app-inference:build :modules:app-services:build :modules:ui:build -x test -PskipWebBuild=true --console=plain
```

Actual result: exit 0, `BUILD SUCCESSFUL in 1m 23s`, 121 actionable tasks
(24 executed, 97 up-to-date). The UI integration task also executed successfully.
This does not replace or erase the full UI unit-suite failure above.
`spotlessApply` ran for app-api in the earlier suite batch; `spotlessCheck`
passed here. Both calls used the default slot wait, one call per checkout at
a time, and the build was limited to the changed module and its dependents.
Raw log: `ci-followup/build.log`.

## Gate and cleanup

`node scripts/governance/gates/test-intent/cli.mjs --pr 838` returned exit 1
with exactly one blocking problem, `test-intent/acceptance-stale`. All 24 flagged
items are covered; the three isolated new test classes remain exempt. The
current changeset digest is
`sha256:7cb60c037b28e530e2af53dba14c431a4c0efb13f203d4cc031c7123709efdcc`.
Independent acceptance is the gate's only remaining requirement; the separate
UI unit-suite failure above is still unresolved validation evidence.

The gate also emits its non-blocking measurement finding: one of eight audited
PRs in 28 days needed a third-reviewer tie-break (12.5%, above 10%). This is
reported for the orchestrator's delivery and is not another gate failure.

The final scoped process audit found no remaining JustSearch fixture/server
processes from this checkout or assigned scratch folder. The producer left no
before-worktree, and the acceptance-record section matches the pre-merge
snapshot byte for byte. No process belonging to another task was stopped.
