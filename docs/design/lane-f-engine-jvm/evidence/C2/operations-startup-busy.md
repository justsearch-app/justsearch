# Operations-store startup BUSY retry

2026-09-14, based on30acebfd9. Targeted installed1591 supplies the exact failure:
SqliteOperationStore's PRAGMA journal_mode=WAL returns SQLITE_BUSY despite the existing
five-second busy timeout. The Engine exits before discovery. Lock errors are not corruption.

Keep retry in the existing store initializer, before it publishes the usable store to the
composition root. Compatibility inspection and preservation of an already corrupt store run
once. Retrying the whole constructor was rejected: a failed fresh attempt can leave WAL
sidecars beside an empty main file, which the one-time recovery preflight would otherwise
misinterpret as interrupted preservation. No host restart policy is changed.

The connection/WAL/schema/pruning attempt retries only SQLite primary BUSY code5, including
its extended variants, directly or wrapped in OperationStoreException STORAGE_FAILED. Every
failed connection must close successfully first. Other SQL errors, IO/future-version failures,
uncertain close and interruption do not retry. Existing initialization and pruning transactions
remain responsible for rollback; none of these attempts has exposed acceptance to callers.

A monotonic five-second window bounds admission of retries; the native five-second busy
timeout remains. Sleep is at most50ms and at most the remaining window. Deadline checks after
close and after sleep prevent another attempt after scheduling or IO consumes the window.
An already admitted native call retains its own bound. Interrupted retry restores the flag
and fails with the preceding storage failure preserved as suppressed evidence.

## Verification

-1592 disables the retry classifier:five selected cases, exactly three expected failures for
Windows contention, wrapped BUSY rollback and interrupted retry. The always-on diagnostic
architecture check passes.
-1593 positive plus deadline test:six cases, one failure in the newly authored Windows fixture.
It held a whole-file lock until the retry callback, but the native wait itself consumed5.293s,
so the five-second retry window correctly refused another attempt. This circular fixture
cannot prove transient recovery and must not motivate extending the product deadline.
-1594 corrects that fixture to prove bounded BUSY refusal while locked, no quarantine, and
successful reopen after release. Wrapped transient BUSY still proves retry/rollback; other
errors and interruption still refuse. All SqliteOperationStore tests execute25 cases, no
skips/failures/errors, PMD/Spotless pass, including existing schema/retention/future-version
and preservation tests.
-1595 removes only the remaining-time and post-sleep admission guards:two selected cases,
exactly one expected failure because a second connection attempt is admitted after the
window. The exact positive source is restored byte-for-byte before integrated1596.
Integrated1596 executes both app-observability and app-services: 3,453 cases across
516 suites, three existing skips, no failures/errors. Four PMD tasks and Spotless pass.
Installed1597 executes the real initialBootstrapSurvivesHostileLocks fixture: one case,
no failures/skips, 7.805 seconds. The full installed recovery group1598 executes six cases across two suites, no failures/skips:
writer crash, migration cutover with real models, lock-ingest, processing recovery, hostile-lock
initial bootstrap and operation outcome resume. Quick-health1599 confirms ABSENT, no foreign
runs or inference orphan after fixture cleanup. Store-recoverability, three operation/execution/
register gates, canonical links/runtime matrix and documentation regeneration pass.

Hosted preceding30acebfd9 CI34825957735 passes twelve jobs, including integration and
Windows-native tests; only license-report dependency download fails with Maven Central HTTP403
for com.ethlo.time:itu:1.14.0. CLA34825954970 passes. A failed-job rerun is requested; no hosted
pass for this retry diff is claimed. Earlier52f0845f7/e55455ff4 CI runs were cancelled, not passes.

Independent final review finds no surviving correctness/security issue. It independently
checks the positive source hash against the saved source and the executed1594–1597 evidence.
The held-lock unit fixture proves bounded refusal; installed1597 proves the short-lock workload
can recover before discovery. No injected JDBC close-failure or exact prune-site BUSY fixture
is claimed: close-failure refusal is structurally reviewed, and a wrapped schema BUSY exercises
the same classifier/rollback owner used by pruneHistory. Hosted proof for this revision remains open. Logs/XML/counts are accessible under
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/operations-startup*.
Retain through final lane reconciliation plus30 days, at least2026-10-14.
This completes the startup retry item locally; stage C2 and lane acceptance remain open.

## Hosted reconciliation

2026-09-14: final startup checkpoint518f13d62 CI34827632310 passes all13 jobs,
including integration, Windows-native, Linux unit suites and license/notices.
CLA34827630282 passes. The preceding30acebfd9 failed-job rerun was cancelled by the newer
push; it is not a successful retry. These results close hosted proof for the startup repair,
not for subsequent enumeration changes. Job metadata is retained in tmp/enumeration-hosted1604.json.
