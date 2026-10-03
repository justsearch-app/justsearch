# B14 local recovery retirement (2026-09-08)

Reviewed subset based on `43698eb48a33fb0f5cade976407a72eb3708155d`, in
`codex/lane-f-b14-local-recovery`. This removes the obsolete Java supervisor vetoes,
their phantom `worker.restart_exhausted` reason, and its live UI/register consumers.
The existing local recovery budget, fatal-index hold and operator retry remain.
**B14 is not complete:** host exhaustion presentation before API binding and R7's
liveness/stability corrections remain open. Full Java verification is deferred to
integration with B11–B12; none of the runs below is a full Java suite.

## Independent review and regression proof

Read-only reviewer `review_b14_java` signed off the final source with no surviving
blockers. Review preserved client-first recovery, fatal-before-budget ordering,
the monitor's single recovery slot, operator exemption, and HTTP 202/503 behavior.
Historical Worker policy in the explicitly retired supervision register and
historical process-coordination documentation remains historical.

The final regression calls the production `transitionWorkerDown` funnel before
STARTING can clear the unknown legacy literal. Restoring the removed stale-reason
guard makes `KnowledgeServerBootRecoveryTest.staleLegacyReasonCannotSuppressCurrentBootFailure`
fail at line 195. Restoring final production code passes. This negative control was
repeated **after** the enum and consumer sweep; the earlier pre-sweep result alone
would not prove the final test's efficacy.

## Verification

| check | result |
|---|---|
| Final Java subset, including the corrected regression | 268 tests: app-api 195, selected app-services 56, selected ui 17; zero failures/errors/skips |
| Build excluding tests, all PMD, integration-test compilation | Passed; 1m23s, 358 tasks |
| Final affected PMD and formatting | Passed |
| Frontend typecheck | Passed |
| Frontend unit suite | 6,451 tests, 480 files; passed |
| Frontend gate runner | 27/27 passed |
| Readiness reason closure | 55 emittable, 49 worded; no awaiting-producer exemptions |
| Documentation index, skill sync and canonical links | Passed; 155 canonical links checked |
| Whitespace/error check | Passed |

The build command also named test tasks but used `-x test`, which excludes those
tasks globally. Test evidence comes from the separate final test run. Its XML was
copied to an immutable snapshot before any later targeted rerun. The UI affected-step
lookup selected no screenshot steps; this record makes no screenshot claim. Its
temporary preview helper was stopped through the identity-checked repository sweep.

Raw outputs remain in ignored `tmp/`; only this summary and hashes are tracked.

| artifact under this worktree | SHA-256 |
|---|---|
| `tmp/b14-final-restored.txt` | `461a2dd9392cc51baa46c7507397ed1e774da5fbd3ee34b17179a7630307d54d` |
| `tmp/b14-final-build-static.txt` | `f9e36b0b7f325efb6b1c1422d4e0d60b51bce843c6951f513927da2f0991c70b` |
| `tmp/b14-final-guard-negative.txt` | `611b5f3427123bf4b7e60a1b535c8dde9666ef7a257e4440768bfa1ddc5f241a` |
| `tmp/b14-final-guard-negative.xml` | `722f8296425424e929f10dce2290ebec412c0a8f167b4af1d0f9fabea9a59cf4` |
| `tmp/b14-final-snapshot/summary.json` | `d7941d03e0d98bd2c594e2ebddfcd851ff25c670e3ec924ff6d4ee9e7307b782` |
| `tmp/b14-ui-typecheck.txt` | `596373b383c6e3421b378952b2fbf2d04b8a1bcef36fd19dc1812d88a565e100` |
| `tmp/b14-ui-unit.txt` | `c0a0c5153f384c5cba93c0b11fddb756b00e38cc8f451f80e7c3e72d0a07004e` |
| `tmp/b14-ui-gates.txt` | `33438566d655dd34b27fde2fe690398cd622419953901829848491a1f7a29ddd` |
