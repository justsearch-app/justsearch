# C2-6 shared settings witness

2026-09-13: app-api now owns `SettingsWitness(acceptedRevision,lastCommittedOperationKey)`.
Storage and compare-and-apply use that one representation. The runner passes the complete
candidate-base witness; the physical owner compares both fields before SQL arming. The
persisted envelope and numeric SQL marker are unchanged. This implements the typed-witness
part of the [recovery amendment](C2-6-plan.md#2026-09-13-settings-history-recovery).
It does not compose production writers or implement confirmed recovery reset.

A same-revision/different-prior-key candidate is refused with VERSION_CONFLICT and cannot
change the file. A retry of an already completed old key still returns its original receipt,
without overwriting later settings. Producers must retain their candidate's original witness;
refreshing only the metadata on an old whole-document candidate is forbidden.

## Proof

Windows/Java25, base15bac5f90 plus the eleven source files pinned in
[the manifest](settings-witness-verification.json); intervening da66f30e7 is an unrelated
ADR/probe/comment correction.

| Run | Result | Execution and limits |
|---|---|---|
|1292|FAILED,152 cases/32 suites, one assertion failure|All three test tasks executed. The stale fixture wrongly refreshed its witness after advancing the file; root captured it before the advance, preserving the intended refusal assertion.|
|1293|PASS,152 cases/32 suites, zero failures/errors/skips|83 app-services cases executed;29 runner and40 architecture cases reused. Format and affected PMD pass.|
|negative1297|Expected failure,7 cases/5 suites, one assertion failure|Temporarily restore revision-only comparison. The same-revision/different-key assertion fails (`expected false but was true`); six inherited guards pass. Production is restored byte-for-byte.|
|1298|PASS,152 cases/32 suites, zero failures/errors/skips|83 from cache and69 up-to-date. This is restored-source reuse, not another execution. Current source hashes equal1293. Format and affected PMD pass.|

Final command:

```powershell
./gradlew.bat :modules:app-api:spotlessCheck :modules:app-observability:spotlessCheck :modules:app-services:spotlessCheck :modules:app-launcher:spotlessCheck :modules:app-services:test --tests "*SettingsCommitCoordinatorTest" --tests "*UiSettingsStore*Test" :modules:app-observability:test --tests "*OperationSettingsRunnerTest" :modules:app-launcher:test --tests "*OperationStoreArchitectureTest" :modules:app-observability:pmdMain :modules:app-services:pmdMain :modules:app-services:pmdTest :modules:app-launcher:pmdTest --console=plain
```

1292/1293 additionally ran spotlessApply for those four modules before the same checks.
The negative command selected only
`SettingsCommitCoordinatorTest.delayedRetryReturnsItsCommittedRevisionWithoutOverwritingLaterSettings`
through `:modules:app-services:test --tests`, with `--console=plain`.

Independent Sol/high production and final test/evidence reviews are clear. The corrected
stale fixture is in SettingsCommitCoordinatorTest; all eleven current source hashes match
both final manifests. The reviewer ran no additional builds.
Logs, copied XML, zipped XML, counts and source bindings are under this worktree's `tmp/`,
listed with hashes in the manifest. Retain through lane acceptance plus30 days and export
before removing the worktree. Failed intermediate runs are retained. No new full-suite,
live, installed or hosted settings proof is claimed; C2-6 and the lane remain open.
