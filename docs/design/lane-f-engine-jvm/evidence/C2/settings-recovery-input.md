# C2-6 accepted recovery input

2026-09-13, base2accba28b plus the seven files in [the manifest](settings-recovery-input-verification.json).
The runner first counts lightweight armed rows and reads immutable accepted preparation only
when exactly one exists, beside that settings row before
calling the fixed owner outside SQL locks. Null-marker rows need no payload read. A malformed
stored nonce/payload validation result supplies unavailable preparation, not reset authority;
actual SQL storage failures still propagate. History/public OperationRecord remain unchanged.

PreparedInvocationCodec now has one public metadata-only entrypoint. It refuses sealed input
and reuses its existing envelope/version/key/nonce/public-identity/content validation with a
disabled cipher. Constructor, full envelope and content methods remain package-scoped. This
avoids a second envelope, arbitrary decoder callback and early key-manager dependency.
The [next fixed-reset schema/owner work](C2-6-plan.md#2026-09-13-accepted-recovery-input-cut)
is still required; this foundation does not yet authorize absent-history reset.

## Review correction

Independent review found eager payload loading could exhaust memory on a corrupt multiple-armed
set before the owner published MULTIPLE_ARMED_ROWS. The runner now counts only to two over
lightweight records and reads private preparation only for a sole armed row. The real owner
regression refuses any private-payload read while still requiring Health, retained RUNNING rows
and mutation refusal. A separate regression proves STORAGE_FAILED propagates by exact exception
identity, without substituting empty preparation or calling the owner.

Independent Sol/high review is clear after the bounded-read correction and both negative checks.
The reviewer verified the seven restored sources against1311;1313 subsequently reused those same inputs.

## Verification

- Final1311 executes121 cases/24 suites (36 runner/preparation,45 owner/codec,40 architecture),
  zero failures/errors/skips, affected PMD/format green.
- Negative1312 removes the sole-armed guard and broadens the malformed catch. Nine cases execute;
  precisely the no-payload-read and storage-failure assertions fail, zero errors/skips. Source
  restores byte-for-byte to1311.
- Restored1313 passes121 cases via cache/up-to-date reuse, with PMD/format green. All seven
  source hashes equal1311. This is restored-input proof, not another execution.

Earlier proof and failure evidence remain retained:


- Input1307 executes120 cases/24 suites:35 runner/preparation,45 owner/codec,40 architecture.
  Zero failures/errors/skips; affected format and main/test PMD pass. Tests reopen actual
  SQLite, transfer the exact frozen value, perform a cross-thread SQL read during owner
  inspection, preserve malformed rows as WAIT, and let exact live commitment complete even
  with missing/invalid nonce/invalid envelope. No fake preparation can authorize reset here.
- Root corrected a worker fixture before running: key/nonce variants had accidentally used
  the CONTENT fixture's descriptor instead of their actual METADATA descriptor. Each variant
  now changes only its named binding; a wrong-reason descriptor refusal cannot mask key loss.
- Negative1309 drops the runner's accepted payload and removes the codec's key comparison.
  Twelve cases execute; exactly the valid-payload transfer and key-binding assertions fail,
  zero errors/skips. Nonce/descriptor cases still pass. Both files restore byte-for-byte.
- Restored1310 represents120 passing cases entirely via cache/up-to-date reuse, with PMD/format
  green and all seven source hashes equal1307. This is restored-input proof, not new execution.
- Register1308 passes operation-surface, execution-surface and register-guard-resolution with
  zero findings. The earlier1307 register command used nonexistent gate id store-recoverability;
  it did not evaluate gates and is retained as a command error, not a green result.

Final command:

```powershell
./gradlew.bat :modules:app-services:test --tests *PreparedInvocationCodecTest --tests *SettingsCommitCoordinatorTest :modules:app-observability:test --tests *OperationSettingsRunnerTest --tests *OperationPreparationRunnerTest :modules:app-launcher:test --tests *OperationStoreArchitectureTest :modules:app-api:spotlessCheck :modules:app-observability:spotlessCheck :modules:app-services:spotlessCheck :modules:app-observability:pmdMain :modules:app-observability:pmdTest :modules:app-services:pmdMain :modules:app-services:pmdTest --console=plain
```

1307/1311 additionally run spotlessApply for app-api/app-observability/app-services first.
Negative1309 selects the bootPassesOnlyAcceptedPreparationOutsideSqlLocksAndKeepsMalformedRowsUnresolved
runner test and metadataEntryPointRejectsReboundPreparationWithoutInputLeakage codec test through
their two module test tasks. Exact commands, XML/zips/counts and sources are retained in tmp.

The compile log retains six pre-existing Error Prone warnings in unchanged launcher/UI code
(VariableNameSameAsType, UnusedVariable, BadInstanceof, FutureReturnValueIgnored), plus ordinary
JVM/deprecation notes; no warning was suppressed. No new full/live/installed/hosted recovery
proof is claimed. Retain artifacts through lane acceptance plus30 days and export before
removing the worktree. Negative1312 selects multipleArmedRowsBlockTheWholeSettingsSet and
acceptedPreparationStorageFailureCannotBecomeAnEmptyRecoveryInput through their module tests.
Fixed reset decoding, absent-history reconciliation, confirmation,
Health, producer migration and public wire receipts remain required C2-6 work.
