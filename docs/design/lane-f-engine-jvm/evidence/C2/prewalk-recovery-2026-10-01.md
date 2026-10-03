# C2 pre-walk ingestion recovery correction — 2026-10-01

Status: implemented and locally verified under the orchestrator's Gradle grant. Worktree
`lane-f-prewalk`, branch `codex/lane-f-prewalk`, base `67437bd35`. No commit,
publication, Engine launch, dev stack, or delegated work is authorized in this task.

## Contract and diagnosis

[C2](../../stages/C2.md) requires acceptance before effects, accepted work without
a checkpoint to resume from the start, and an existing running ingestion child to
resume under the original parent rather than be minted again. The
[operations design](operations-store-design.md#15-which-calls-get-a-row) owns the
parent/child binding and runner-owned attempts. The 2026-10-01 installed lock-ingest
diagnosis in the orchestrator's `lane-f-pr1-verify` worktree established that writer
readiness can remain false after both rows start but before a queue walk exists.
Shutdown preserves that child; the old boot reconciler incorrectly fails it for
missing progress. This correction implements that specific recovery gap.

## Implementation and ownership decision

`RecordedIngestionCoordinator.reconcileChild` permits creation only for a missing
walk with no child checkpoint and zero confirmed counters, under the existing
parent binding, authority, generation, admission, sequential-root and attempt
budget checks. Waiting for readiness spends no attempt. A boot resume uses the
runner's existing `resume` transition: one additional attempt for each original
parent and child. Repeated publication/maintenance cannot spend another attempt.

The coordinator checkpoints the existing `ingest-progress:1:<revision>` cursor
after walk creation and before calling the producer. This gives subsequent boots
durable evidence that enumeration began, even if no unit completed. Existing
walks with mismatched plans or decreasing counters fail unavailable before any
producer call. Missing walks with a retained checkpoint also fail unavailable.

`SqliteIngestionWalkOps.begin` refuses missing-walk creation if recorded job
membership, operation-attributed ledger evidence, or sealed-unit evidence exists.
The read and insert share the existing queue transaction. The queue interface's
Javadoc describes the recovered pre-walk exception; no signature changes.

Ownership choice: reuse the operations checkpoint and the queue's existing
transactional creation primitive instead of adding a persistent started marker,
new schema, recovery state machine, terminal writer, or cross-module evidence
representation. No receipt validation or acknowledgement check is removed.
If a walk was created but the Engine died before the new checkpoint, its present
projection still governs recovery; if only its projection disappeared, retained
units/receipts forbid recreation. Whole-store loss with no surviving evidence is
not distinguishable from the accepted pre-walk window by this contract.

## Regression acceptance

The new tests are in `RecordedIngestionCoordinatorTest`:

- `servicePublicationAfterShutdownResumesPreWalkChildOnceAcrossBothStoreReopens`:
  starts parent and child while readiness is false, asserts no walk/checkpoint,
  performs the real admission/runner shutdown handoff and closes both SQLite
  stores. A new runner/coordinator reopens them, waits without spending an
  attempt, then publishes readiness. The original child enumerates epoch 1 once,
  with its walk-start checkpoint durable before the producer, and both original
  rows complete at attempt 2. The receipt is acknowledged and admission drains.
  Repeated publication/maintenance and another full store reopen cannot replay
  enumeration or spend a third attempt.
- `recoveryCannotRecreateOrEnumerateContradictoryChildEvidence`: six real SQLite
  fault cases cover missing checkpointed progress, missing progress with retained
  pending units, missing progress with retained receipts after job deletion,
  mismatched plan, decreasing completed counters and decreasing failed counters.
  Every case requires zero producer calls, the original parent and child to fail
  with `INGEST_UNIT_STATE_UNAVAILABLE`, no recreated walk, and released admission.

Before the grant only source/diff review and `git diff --check` had run. The owner
then granted the listed focused commands, all three PMD tasks, and one final full
`app-engine:test` execution. The orchestrator retains installed lock-ingest replay
and lane-wide acceptance ownership; no installed proof is claimed here.

## Verification environment and artifacts

All requested Gradle commands ran sequentially with `--offline --no-daemon`.
The initial wrapper run was blocked by sandbox network access. A copied cached
Gradle 9.6.1 distribution then exposed undiscovered JDK 21 and compiler access
failures against the shared read-only dependency cache. The remedy is a writable
`tmp/lane-f-prewalk/gradle-home` containing copied cached distribution/dependencies
and `gradle.properties` pointing to the cached JDK 21 and installed JDK 25.
No repository build configuration or shared cache was changed. Initial failures
are preserved in `red.log`, `red-offline.log`, and `red-offline-toolchains.log`.
None reached the regression and none counts as expected red proof.

The first actual red reached the expected assertion, but also had a suppressed
JUnit temp-directory cleanup access failure. Its `red-local-cache.log`, `red-xml`
and `red-counts.json` are preserved. Subsequent commands inherit
`JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<worktree>/tmp/lane-f-prewalk/java-tmp`.
The clean red (`red-owned-temp.log`, `red-owned-temp-xml`,
`red-owned-temp-counts.json`) has one assertion failure at
`RecordedIngestionCoordinatorTest.java:179`, with no suppressed cleanup failure:
the original RUNNING child became FAILED/INGEST_UNIT_STATE_UNAVAILABLE at attempt 1
while readiness was still false. Production was reapplied before every green check.

Artifacts are under this worktree's `tmp/lane-f-prewalk/`. Separate XML snapshots
and `*-counts.json` files preserve each run before a later suite replaces Gradle's
test-results directory. `verified-source/` contains actual tested source copies;
`verified-source.patch` and `source-manifest.json` identify the uncommitted code
over base `67437bd35`. The patch includes the new test; `production.patch` contains
only the three production files and supports the red reversal. Gradle deprecation,
existing compiler and native-library warnings remain visible in the logs.

## Completed local verification

The XML snapshots report the following counts. Focused and full Engine counts
overlap and are separate runs, not an aggregate test total.

| Run | Suites | Tests | Failures | Errors | Skips | Artifact prefix |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Clean red with production reversed | 1 | 1 | 1 expected | 0 | 0 | `red-owned-temp` |
| Focused Engine green | 5 | 100 | 0 | 0 | 0 | `engine-focused` |
| Focused queue green | 4 | 39 | 0 | 0 | 0 | `queue-focused` |
| Whole app-engine green | 72 | 468 | 0 | 0 | 0 | `engine-full` |

The focused Engine run executed Coordinator (58), Settlement (12), Receipt (4),
Bulk Coordinator (22), and Bulk Engine Restart (4) cases. Both Engine runs include
the new pre-walk regression and all six contradictory-evidence cases. The full
`app-engine:test` ran once, without test filters, and succeeded in 22m 5s.

`spotless.log` and `compile.log` record successful formatting and the requested
production/test compilation. `pmd.log`, `pmd-xml/`, and `pmd-counts.json` record
successful `app-engine:pmdTest`, `app-engine:pmdMain`, and
`indexer-worker:pmdMain`: zero violations and zero processing errors in each XML.
The final source hash check matched all four tested Java files in
`source-manifest.json`; documentation status updates do not alter tested source.
All changes remain uncommitted. Installed lock-ingest replay and lane acceptance
remain with the orchestrator.

## Build grant and red/green sequence

The implementation-only patch is available at
`tmp/lane-f-prewalk/production.patch` in this worktree. Retain it and verification
output through 2026-10-31 (or until the orchestrator reconciles this correction,
whichever is later). It contains only these three production files:
`RecordedIngestionCoordinator.java`, `SqliteIngestionWalkOps.java`, and
`worker-core/.../JobQueue.java`; tests remain installed throughout.

1. Check `git apply --reverse --check tmp/lane-f-prewalk/production.patch`, then
   reverse that exact patch with `git apply --reverse`. Run the focused red command
   below. It must fail at the assertion that unready recovery retains the original
   child: the old reconciler terminalizes the absent walk immediately. A compile
   error or unrelated assertion failure is not the expected red result. Preserve
   Gradle output and `modules/app-engine/build/test-results/test` before green.
2. Reapply the exact patch with `git apply tmp/lane-f-prewalk/production.patch`
   even if the red command fails unexpectedly. Do not restore/reset other files.
   Run formatting, compilation and the focused green suites below, one command
   at a time under the explicit Gradle grant. Confirm the green suites execute
   the new cases and preserve their XML/output separately from red.
3. Re-read failures and evidence, correct any defect, and update this record with
   the tested source and results. No commit or push.

```powershell
# Red, with the implementation-only patch reversed:
.\gradlew.bat :modules:app-engine:test --tests "io.justsearch.app.engine.RecordedIngestionCoordinatorTest.servicePublicationAfterShutdownResumesPreWalkChildOnceAcrossBothStoreReopens"
# Green, after reapplying the patch:
.\gradlew.bat :modules:worker-core:spotlessApply :modules:indexer-worker:spotlessApply :modules:app-engine:spotlessApply
.\gradlew.bat :modules:app-engine:compileJava :modules:app-engine:compileTestJava :modules:indexer-worker:compileTestJava
.\gradlew.bat :modules:app-engine:test --tests "io.justsearch.app.engine.RecordedIngestionCoordinatorTest" --tests "io.justsearch.app.engine.RecordedIngestionSettlementTest" --tests "io.justsearch.app.engine.RecordedIngestionReceiptTest" --tests "io.justsearch.app.engine.RecordedBulkIngestionCoordinatorTest" --tests "io.justsearch.app.engine.RecordedBulkEngineRestartTest"
.\gradlew.bat :modules:indexer-worker:test --tests "io.justsearch.indexerworker.queue.RecordedWalkProjectionTest" --tests "io.justsearch.indexerworker.queue.RecordedWalkReceiptValidationTest" --tests "io.justsearch.indexerworker.queue.CapturedWalkPlanTest" --tests "io.justsearch.indexerworker.queue.CapturedWalkSettlementTest"
.\gradlew.bat :modules:app-engine:pmdTest :modules:app-engine:pmdMain :modules:indexer-worker:pmdMain
.\gradlew.bat :modules:app-engine:test
```
