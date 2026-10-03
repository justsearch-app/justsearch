# Queue write reservation before preservation reads

2026-09-14, based on pushedd245f8427. Installed1568 lock-ingest failed at enqueue:
request10:08:22.423, SQLITE_BUSY10:08:22.430, accepted0. The new standalone membership
read established a deferred read transaction before the INSERT. The earlier preservation
subqueries were inside the INSERT, whose first database access acquired write intent.

[SQLite transaction semantics](https://www.sqlite.org/lang_transaction.html) explain why
a deferred read-to-write upgrade can refuse a competing writer. The busy timeout is not a
promise to wait on every upgrade. Keep the configured five-second timeout and acquire a
write reservation before any mutation-transaction preservation read. A zero-row UPDATE
on the existing jobs table does so without changing rows or publishing change-feed deltas.
Read-only operations remain read-only; no new store, marker or retry loop is introduced.

Global IMMEDIATE JDBC configuration was rejected. The installed Xerial3.51.2.0 driver's
commit()/rollback() starts the next configured transaction before returning. An IMMEDIATE
reacquisition could fail after the preceding COMMIT was already durable and confuse existing
commit-confirmation handling. The zero-row reservation preserves DEFERRED driver mode and
the established JDBC rollback/restore failure protocol. Inspected bytecode is retained at
tmp/sqlite-connection-bytecode1569.txt.

The two-connection regression holds a real writer with changed scan/collection/provenance,
then starts enqueue. The old code returns early with zero; the fix waits for writer release
within the existing timeout and snapshots its newly committed fields. It also proves that
a transaction changing only walk progress produces no phantom jobs delta. Existing rollback,
post-commit restore-failure and recorded terminal-claim tests remain intact.

## Verification

-1569 on the original transaction:seven selected cases, one expected regression failure
at the early-return assertion. No test was weakened.
-1570 with reservation:34 cases/four suites, no skips/failures/errors, PMD/Spotless pass.
-1571 full queue:524 cases/89 suites,12 existing skips, no test failures/errors; PMD flags
only the unused resource variable name in the new subscription test. Renamed to the
repository's ignored-resource convention without changing assertions.

-1572 final full queue:524 cases/89 suites EXECUTED,12 existing skips, zero failures/errors;
PMD and whole Spotless pass.

Independent read-only review confirms the upgrade failure and selected transaction cut.
Logs/XML/counts are retained under
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/write-reservation*.
Retain through final lane reconciliation plus30 days, at least2026-10-14. Installed Windows
lock-ingest verification remains required after this correction; the former six-scenario
run also exposed migration fingerprint and hosted initial-discovery failures, tracked in
[the recovery record](hosted-recovery-correction.md). No full installed pass is claimed.
