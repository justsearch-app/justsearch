# R3: do not ignore a refused durable transition

September13, 90aea57bb plus this item. A false terminal update now raises STORAGE_FAILED
instead of leaving a completion future pending. The unowned boot sweep and explicit
reconciliation use the same checked transition and R1 observation path. Pre-start
refusal reads the row after its update: false is harmless when the row advanced to
RUNNING/another later state, but a still-ACCEPTED row is a storage failure. Existing
terminal immutability and late-refusal behavior remain intact.

Named regressions in OperationAttemptRunnerTest use SQLite RAISE(IGNORE) triggers:
ignoredTerminalWriteCannotLeaveCompletionPending, ignoredBootSweepRefusesStartup,
and ignoredPreStartRefusalIsNotMistakenForSuccessfulRefusal. Negative796 executes
four cases and fails all three new assertions for the intended ignored-write behavior.
Final797 passes the runner, child-acceptance and dispatcher suites; see the committed
[summary](review-r3-verification.json) for exact counts and revision. Affected PMD
and UI integration-test compilation pass. This item does not change the database
writer count or introduce recovery state.

```text
gradlew.bat :modules:app-observability:test --tests *OperationAttemptRunnerTest
  --tests *OperationChildAcceptanceTest :modules:app-services:test
  --tests *OperationExecutorImplTest :modules:ui:compileIntegrationTestJava
  :modules:app-observability:pmdMain :modules:app-observability:pmdTest
  -PtestParallelism=1 --max-workers=4 --console=plain
```

Negative796 runs only :modules:app-observability:test with
`--tests *OperationAttemptRunnerTest.ignored*` and the same flags. Raw logs are
`tmp/c2-review-r3-negative-796.txt` and `tmp/c2-review-r3-797.txt`, with matching
-counts.json and -xml/ siblings. Retain through lane acceptance plus30 days;
refresh the batch raw inventory at closure. Final independent batch review is pending.
