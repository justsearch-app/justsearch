# C2-6 physical settings owner

Local owner-foundation proof at eec44cad1 plus the four source files pinned in
[the verification manifest](settings-owner-verification.json), 2026-09-13. This is a bounded
implementation slice, not C2-6 completion. The governing protocol is
[operations-store-design section1.8](operations-store-design.md#18-the-accepted-settings-revision-port-amended-2026-09-12).

`SettingsCommitCoordinator` in app-services owns the one settings-file/config transaction.
It reserves and validates a readable durable witness before the runner arms SQL; prepares
private settings bytes, an immutable resolved config and the response before replacement;
classifies a reported move failure against exact prior/new file witnesses; and retains its
attempt fence until the runner durably terminalizes. Config and recovery notifications run
after releasing the physical mutex while the logical fence still refuses another mutation.

The owner receives the complete open settings set before producers start. It blocks multiple
armed rows, reconciles a single exact new witness as complete or a valid unchanged revision
as precommit failure, and leaves corrupt/contradictory/in-memory evidence unresolved. Its
bounded sticky recovery signal publishes outside the mutex. Ordered restart is deduplicated
for the matching retained transaction, while unresolved boot evidence causes no restart loop.
There is no SQL access, extra journal, producer terminal writer or cross-file rollback here.

## Verification and corrected failures

Owner1282 compiled and executed56 cases/8 suites, zero failures/errors/skips. The command
failed on two PMD test violations: an unused reservation local and an unused control field.
Root removed those unused bindings without changing the assertions. Its retained XML and
log remain failed-run evidence, not an accepted green check. Raw paths are
`tmp/c2-6-owner1282.txt`, `tmp/c2-6-owner1282-counts.json` and
`tmp/c2-6-owner1282-xml.zip` in the lane worktree.

Command Owner1282 (Windows PowerShell,0c7ca5954 plus initial owner diff):

```powershell
./gradlew.bat :modules:app-api:spotlessApply :modules:app-observability:spotlessApply :modules:app-services:spotlessApply :modules:app-services:test --tests '*SettingsCommitCoordinatorTest' --tests '*UiSettingsStoreRevisionTest' :modules:app-observability:test --tests '*OperationSettingsRunnerTest' :modules:app-api:spotlessCheck :modules:app-services:spotlessCheck :modules:app-services:pmdMain :modules:app-services:pmdTest --console=plain
```

Owner1283 passed97 represented cases/23 suites (68 executed,29 unchanged reused), PMD and
format checks. Root added the real delayed-retry/advanced-current-settings case. Negative1284
then executed9 cases and failed at exactly three intended assertions: stale revision refusal,
notification outside the physical mutex, and recovery callback outside that mutex. The source
was restored byte-for-byte. That negative run predates the following cleanup correction.

Owner1285 exposed a production defect in the new concurrent caller regression: a contender
was refused promptly by reserve, but `releaseAfterTerminal` blocked its result publication on
the first writer's mutex despite its unrelated id. The run represented100 cases, with one
failure in31 executed cases;69 were reused. Independent review confirmed the root cause.
The owner now checks the volatile fence identity before taking that mutex, then rechecks a
matching identity under lock. This preserves prompt refusal through terminal publication.
Lock probes now call side-effect-free `reconcile` on a real null-marker row; an unrelated
cleanup call would no longer prove physical-lock release after this correction.

## Final proof

Owner1286 passes100 cases/23 suites:71 executed (31 app-services,40 app-launcher),29 runner
cases reused, zero failures/errors/skips. PMD main/test and explicit format checks pass.
The concrete owner suite contains17 tests; the task also runs UiSettingsStoreRevisionTest and
inherited architecture guards. Independent Sol/high source review is clear after the cleanup
fix and stronger fresh-reservation/multiple-armed refusal assertions.

Negative1287 executes10 cases/5 suites with four intended assertion failures, zero errors/skips:
stale revision comparison disabled; config notification moved under the physical mutex;
recovery callback moved under that mutex; unrelated cleanup made blocking again. The remaining
six inherited cases pass. All four source mutations are listed in the retained script/manifest.
Production is restored byte-for-byte; Owner1288 passes the same100 cases entirely through
unchanged/cache reuse. Both final source manifests are identical, with owner SHA-256
`29645471ca4c7dbdfde0ddd22c9cd42f2f3f74f70ca722de8581a7f475b8bcfd`.
No fresh execution claim is made for1288. The negative proof establishes these four guards;
it does not independently mutate every crash branch.

Owner1283/1285/1286 command:

```powershell
./gradlew.bat :modules:app-api:spotlessApply :modules:app-observability:spotlessApply :modules:app-services:spotlessApply :modules:app-services:test --tests '*SettingsCommitCoordinatorTest' --tests '*UiSettingsStoreRevisionTest' :modules:app-observability:test --tests '*OperationSettingsRunnerTest' :modules:app-launcher:test --tests '*OperationStoreArchitectureTest' :modules:app-api:spotlessCheck :modules:app-services:spotlessCheck :modules:app-services:pmdMain :modules:app-services:pmdTest --console=plain
```

Owner1288 restoration command:

```powershell
./gradlew.bat :modules:app-api:spotlessCheck :modules:app-services:spotlessCheck :modules:app-services:test --tests '*SettingsCommitCoordinatorTest' --tests '*UiSettingsStoreRevisionTest' :modules:app-observability:test --tests '*OperationSettingsRunnerTest' :modules:app-launcher:test --tests '*OperationStoreArchitectureTest' :modules:app-services:pmdMain :modules:app-services:pmdTest --console=plain
```

Negative1287 command (1284 omits the concurrent contender selector):

```powershell
./gradlew.bat :modules:app-services:test --tests '*SettingsCommitCoordinatorTest.occupiedAndStaleReservationsRefuseBeforeSqlArm' --tests '*SettingsCommitCoordinatorTest.reentrantPreparationAndCrossThreadNotificationCallsAreRefused' --tests '*SettingsCommitCoordinatorTest.recoveryIssueSubscriberCanCallOwnerAfterMutexIsReleased' --tests '*SettingsCommitCoordinatorTest.concurrentContenderIsRefusedWhilePreparationHoldsPhysicalMutex' --console=plain
```

[The manifest](settings-owner-verification.json) carries every command, revision scope, exit,
represented/executed task distinction and artifact hash/size. Raw logs, counts and XML archives
are under `F:/justsearch-public/.claude/worktrees/lane-F-A/tmp/c2-6-{owner,negative}1282..1288-*`;
individual paths are enumerated there. Real temporary settings/SQLite tests cover preparation,
reported replacement failures, exact/new/third witnesses, fatal before/after replacement with
reopened stores, SQL arm/terminal failures, actual concurrent prompt refusal, notification locks,
foreign/retired tokens, and delayed same-key receipt after a later settings commit.

## Hosted register correction

[CI34774278228](https://github.com/justsearch-app/justsearch/actions/runs/34774278228) ran at
eec44cad1 after runner availability recovered. CLA34774277424 succeeded. Twelve CI jobs passed;
Public claims failed at operation-surface because the new SettingsCommitOwner port was not
registered. Local1289 reproduced the finding and additionally caught the uncommitted concrete
coordinator. Both now declare consumer lineage from the existing operation row, with executable
architecture/coordinator guards; neither adds an operation-state authority. Local1290 passes
operation-surface, execution-surface and register-guard-resolution, zero findings. No gate or
baseline was weakened. The retained SARIF/logs and register are hashed in the manifest.

This hosted run predates the concrete owner and does not prove that source. Windows-native
passed its listed contract/native tests; it did not run the separately owed installed-v5 recovery
proof. A successful next hosted run and the installed acceptance remain required.

## Remaining C2-6 cuts

This owner is not yet composed in production. The following migration must wire one owner
before autostart and propagate its recovery signal into existing lifecycle Health. All raw
settings writers, settings merge/reset ownership, compensation comparison, native-property
consumers, public outcome schemas and frontend callers remain in the owning C2-6 plan.
The [reviewed recovery amendment](C2-6-plan.md#2026-09-13-settings-history-recovery) now owns
typed witness comparison and confirmed reset with frozen quarantine evidence. This slice still
compares a scalar expected revision; it is not wired into production and cannot satisfy that
amended all-producer contract yet. No implementation/proof of recovery genesis is claimed here.
Full/live/installed/hosted proof remains required; process-restart tests do not establish
physical power-loss durability. Temporary artifacts must be retained through lane acceptance
plus30 days and exported before releasing the worktree.
