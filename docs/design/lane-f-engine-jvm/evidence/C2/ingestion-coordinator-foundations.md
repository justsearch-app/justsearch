# C2-9b.3b.3 coordinator foundations — 2026-09-14

Parent-owned implementation in lane-f-pr1-verify on759775f6f plus this diff.
These foundations do not bind a producer or complete the coordinator.

## Changes

OperationStore.findIngestChild observes the existing exact canonical identity query.
SqliteOperationStore shares its validation with acceptance: one row, exact inherited
attribution, NONE history, and accepted one-root payload. Observation never accepts a
child or grants replay. Open-child acceptance additionally requires a RUNNING parent;
terminal-child lookup remains available after parent completion and reopen.

RecordedIngestionReceipt is a pure Engine projection. The queue remains the receipt
schema owner. Cursor equality binds version, revision and exact stored UTF-8 SHA256;
checkpoint equality includes historical completed/failed counts. Terminal equality also
requires the derived state, bounded code and null executionId. Current failed membership,
not historical failures alone, determines the current result.

JobQueue.hasIssuedRecordedClaims reads the existing activeClaims map under the queue
lock and reuses sealing's non-null epoch/matching-key predicate. The outer coordinator
must revoke permission first. A poll that captured permission before revocation must
publish its actual claim before this locked read can observe drain. Durable PROCESSING
rows alone are not process-local owners; a closed queue cannot report false.

## Evidence

-1699 compile failed on missing Optional import; no tests executed. The correction uses
 the existing fully-qualified style, avoiding1700's27 PMD findings from a redundant import.
-1701 focused16 cases/4 suites, zero skips/failures/errors; four PMD tasks and format pass.
-1702 negative control removed the open-child RUNNING-parent guard: two cases including
 one guard, one expected refusal assertion failure. Source restored byte-for-byte.
-1703 full app-api233/46 suites and app-observability581/87 suites:814 cases/133 suites,
 zero skips/failures/errors; four PMD tasks and format pass.
-1703 operation-surface correctly rejected the new unregistered receipt consumer. Registering
 it in the existing consumer catalog restored all three1704 governance gates; store
 recoverability also passes. No baseline changes or new operation schema were added.
-1705 stopped at one PMD redundant Objects qualification; no tests ran.1706 executed42
 cases with one new fixture failure: SUCCESS_FULL through skip completion correctly
 refused. The fixture now uses SKIPPED_POLICY; production validation remains intact.
-1707 focused42 cases/4 suites, zero skips/failures/errors, PMD/format pass. This precedes
 the final explicit lock-blocking assertion in the race test.
-1708 negative control removed the ownership-read lock: seven cases including six
 guardrails, one expected TimeoutException assertion failure. Source restored byte-for-byte.
-1709 integrated indexer632/98 suites (15 existing skips), Engine244/52 suites (no skips),
 Worker342/84 suites (six existing skips):1,218 cases/234 suites,21 existing skips,
 zero failures/errors. All three test tasks executed; six PMD tasks and format pass.
 The combined unchanged API/store1703 and integrated1709 coverage represents2,032 cases,
 not2,032 new executions in1709. Final1710 docs/gates/store checks also pass.

Independent read-only review found no surviving correctness finding in the restored
foundation diff. Root verified failed assertions and preserved each run's XML before
reruns. The tests exercise real SQLite lookup, inherited binding corruption, terminal
reopen, issued/pending/legacy separation, exact claim release, transaction rollback,
reopen PROCESSING without ownership, missing progress with a live owner, closed-queue
refusal, and the permission-capture/read race.

Artifacts use tmp/1701-counts.json through1709-counts.json, numeric .txt logs and -xml/
directories in F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/. Negative originals
are tmp/1702-store-original.bin and tmp/1708-queue-original.bin. Gate outputs are
1704-gates.txt and1704-store.txt. Retain through final lane reconciliation plus30 days,
at least2026-10-14. Foundation13dd53189 hosted CI34868482004 has12 successful jobs
and one app-ui failure: app-launcher's unreferenced-code guard reports the unconnected
RecordedIngestionReceipt class and terminalMatches method (three repeated attempts).
CLA34868477831 passes. Exact metadata/logs/downloaded app-ui XML are tmp/1719-hosted-*
under the same retention rule. Production coordinator wiring remains owed; no exemption.
Receipt-only settlement is now locally verified in [its own proof](ingestion-receipt-settlement.md).

## Remaining

Implement the stable coordinator, including actual revoke-before-drain order, enumeration
exit, attempt-budget enforcement, child checkpoint/terminal/ack sequencing, bounded parent
settlement, startup/replacement recovery and existing maintenance/final-flush wiring.
Then bind finite-file/directory producers through the sole bounded EngineKnowledgeClient.
The reviewed primitives alone do not prove any of those behaviors. C2 stays open; merge at F.
