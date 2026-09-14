# C2-8d.2c: queue notifications and receipt retention

2026-09-14. Owning [vertical plan](C2-8d-vertical-plan.md). Queue-only attachment is
implemented and locally verified. Outer operation receipt matching, projection retry
cadence and actual producer/recovery integration remain required in the next vertical cut.

A same-connection SQLite update listener captures changed progress row ids only. The existing
queue transaction confirms commit before resolving keys; rollback discards provisional ids.
The outermost queue call detaches a key batch under its lock and delivers after releasing it.
Subscribers reread durable progress. The existing display stream keeps its established
under-lock delivery contract. Actual issued-claim release also hints its key after a successful
return/outcome, covering an already-committed administrative skip with no further SQL update.

Runtime subscriber failures are logged without changing committed queue outcomes; fatal errors
propagate. Notifications are transient and may be coalesced, reordered or missed on connection
failure; the receipt remains durable. No timer, executor or second persistent authority is added.
The actual owner's existing maintenance/shutdown integration must reconcile missed projection.
Duplicate exact acknowledgement succeeds without UPDATE, preventing acknowledgement loops.
Existing age cleanup removes old sealed exact-ack progress only after all keyed jobs and ledger
references are gone, atomically with its existing cleanup. Missing progress never authorizes
removing otherwise-protected evidence. Progress deletion sends no missing-receipt notification.

## Review and proof

1619 executes62 focused cases/7 suites, zero skips/failures, but PMD flags9 unused subscription
resource locals. Referencing separately declared resources in try-with-resources fixes the
warnings while retaining deterministic close. This is not reported as a full build pass.

Independent review finds a failed-open defect: if new notification setup throws after display
attachment, the old display stream remained exposed on the closed connection. Open failure
now closes and clears both projections before closing JDBC, preserving primary/suppressed
failures. A constructor-failure regression checks no exposed stream and a successful retry.
1620 executes63 cases/7 suites, no failures/skips; PMD/Spotless pass. 1621 adds fatal observer
and confirmed-commit/failed-restore tests:20 cases/2 suites, no failures/skips, PMD/Spotless pass.

Negative1622 removes that failed-open fix and other guards. It does not complete: the native
SQLite test process crashes in NativeDB.set_update_listener -> removeUpdateListener ->
IndexingJobsChangeStream.close during failed-open cleanup. The JVM log is retained as
`tmp/1622-hs_err_pid22472.log`. Its partial11-case/4-failure/1-skip XML is not a completed
negative proof suite. All positive production sources are restored byte-for-byte from
`tmp/notification1621-*.java` before further validation.

Negative1623 retains the failed-open fix and removes post-unlock delivery, rollback discard,
claim-release hints, jobs-reference retention and duplicate-ack suppression. It completes11
cases with5 assertion failures and no skips/errors. Lock order fails with the expected
second-thread read timeout; rollback emits the prior rolled-back key; a superseded claim
return misses notification; progress is pruned while its job remains. The acknowledgement
case is confounded by deliberately under-lock nested delivery, so isolated acknowledgement
proof is recorded separately below rather than being counted as a guard-specific negative.

Final integrated1624 executes both affected test tasks:929 cases/178 suites,21 skips,
zero failures/errors. Indexer-worker587 and worker-core342 cases are executed, with PMD/Spotless
success. Isolated1625 removes only acknowledgement deduplication:7 cases/2 suites, exactly
one expected failure showing3 callbacks instead of2. Positive sources are restored byte-for-byte.
The JobQueue subscription documentation now explicitly states overlapping/reordered callbacks
and requires thread-safe latest-receipt reads; this clarification changes no runtime behavior.
Final1626 executes20 focused cases/2 suites, no failures/skips; PMD/Spotless pass.
The tests run on Windows11 x64 build26100, Temurin OpenJDK25.0.2+10 and SQLite JDBC3.51.2.0.
Governance's operation/execution/register gates, store recoverability and canonical docs checks pass. Logs, counts, XML and snapshots use
`F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/1619*` through `tmp/1626*`.
Retain through final lane reconciliation plus30 days, at least2026-10-14. Hosted proof for
these changes remains unperformed; predecessor64af16c20 CI34833934557 completed all13 jobs successfully.
