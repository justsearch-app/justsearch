# R1: observable asynchronous persistence failure

September13; source at429115fec plus this correction. The supplied independent review
found that failed asynchronous terminal writes only completed an ignored future
exceptionally. The durable row stayed RUNNING without a Health/history failure.

OperationAttemptRunnerImpl now logs ERROR with the key and intended state, retains
the original effect failure as suppressed on an asynchronous storage failure, and
completes one first-persistence-failure signal. HeadAssembly connects that signal to
OperationRecoveryNotice and the sticky operations.persistence_failed Health condition.
A late subscriber still sees the first failure. Later failures all log; there is no
new failure registry, event bus or durable store. Health contains bounded metadata,
not exception text. The row remains unresolved rather than inventing a terminal result.
Both dispatcher completion branches use whenComplete and emit FAILURE on exceptional
completion; audit NONE still suppresses history but does not suppress its advisory.

## Named acceptance and gate

- OperationAttemptRunnerTest.asynchronousEffectAndTerminalWriteFailureRetainsBothCauses:
  real SQLite trigger refuses FAILED after the body fails; assert storage error,
  exact suppressed effect cause, unchanged RUNNING row, key/state signal and ERROR log.
- OperationExecutorImplTest.asynchronousTerminalWriteFailureEmitsFailureHistoryAndHealth:
  actual dispatcher and store, failed COMPLETE write after Started; one FAILURE
  history entry with STORAGE_FAILED, RUNNING row and a late-attached ERROR Health condition.
- synchronousCompletionWriteFailureCannotReturnSuccess preserves its thrown error,
  single effect and RUNNING checks and now requires the review-requested failure history.
- unauditedAsyncFailureStillEmitsFailureAdvisory covers the second completion branch.

Negative788 runs the double-failure regression against the prior source: two cases,
one expected assertion failure because the suppressed cause is absent. Run789 exposes
the now-obsolete no-history assertion; its correction is explicitly authorized by the
new failure-publication requirement. Final790 passes92 cases/8 suites, zero failures,
errors or skips: app-services84 execute; app-observability8 reuse unchanged passing789
inputs. Selected PMD passes; ui:compileIntegrationTestJava compiled on789 and reuses
unchanged inputs on790. The readiness reason-code gate passes.

```text
gradlew.bat :modules:app-observability:test --tests *OperationAttemptRunnerTest
  :modules:app-services:test --tests *OperationExecutorImplTest
  --tests *OperationRecoveryNoticeTest :modules:ui:compileIntegrationTestJava
  :modules:app-api:pmdMain :modules:app-observability:pmdMain
  :modules:app-observability:pmdTest :modules:app-services:pmdMain
  :modules:app-services:pmdTest -PtestParallelism=1 --max-workers=4 --console=plain
node scripts/ci/check-readiness-reason-codes.mjs
```

Artifacts: `tmp/c2-review-r1-negative-788.txt`, `tmp/c2-review-r1-789.txt`,
`tmp/c2-review-r1-790.txt`, with corresponding -counts.json and -xml/ siblings.
The committed [summary](review-r1-verification.json) names the tested source and command.
Retain raw artifacts through lane acceptance plus30 days; export before worktree removal.
The batch's final independent review and integrated gate remain open. R2–R10 retain
their ordered ownership; this item does not close C2-2 or repair other swallowed writes.
