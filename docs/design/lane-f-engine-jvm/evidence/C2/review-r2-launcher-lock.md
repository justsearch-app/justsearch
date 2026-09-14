# R2: launcher exclusion before operations-store access

September13, 5a10b9dc9 plus this item. LauncherEnvironment now acquires the existing
AppInstanceLock unconditionally before SqliteOperationStore construction/inspection
and before the runner boot sweep. An existing owner in the same JVM does not permit
another launcher to bypass the lock. No alternate lock registry is introduced.

Successful shutdown releases the lock after operations.close. A refused Head drain
or operations close retains it for a later close retry. Failed setup closes the
acquired lock after closing any created operations store; if that close fails the
lock is retained instead of permitting another owner over an unresolved store.

Named acceptance: LauncherEnvironmentCloseTest.secondLauncherCannotSweepTheFirstLaunchersLiveOperation
constructs a real first runner/store with a RUNNING interactive row. A second launcher
must throw AppInstanceLockException before constructing its runner, preserve that row,
and allow lock reacquisition after first-owner close. Additional regressions prove
failed assembly construction releases the lock and failed store close retains it
until a successful retry. The existing failed-drain regression remains intact.

Negative793 fails because the second launch is not refused. Initial791/792 are invalid
negative evidence: fixture telemetry lacked its registry/catalog, corrected before793.
Run794 passes all45 tests but fails one unused-resource PMD check; the fixture now
also asserts the first assembly exists. Final795 executes45 cases/17 suites with
zero failures/errors/skips; launcher main/test PMD and UI integration-test compilation
pass (unchanged UI inputs reused). This is the requested in-process two-runner test,
not an installed-process recovery claim.

```text
gradlew.bat :modules:app-launcher:test --tests *LauncherEnvironmentCloseTest
  --tests *LauncherEnvironmentResolveTest :modules:ui:compileIntegrationTestJava
  :modules:app-launcher:pmdMain :modules:app-launcher:pmdTest
  -PtestParallelism=1 --max-workers=4 --console=plain
```

Negative793 selects only the named test on :modules:app-launcher:test, with the same
parallelism flags. Logs: `tmp/c2-review-r2-negative-793.txt`, `tmp/c2-review-r2-794.txt`,
`tmp/c2-review-r2-795.txt`; matching -counts.json and -xml/ siblings are retained through
lane acceptance plus30 days. [Committed summary](review-r2-verification.json).
The raw batch inventory will be refreshed at batch closure. Final batch review remains
open; general launcher failure cleanup is still the separate R8 item and must preserve
the proven unfinished-procedure drain barrier.
