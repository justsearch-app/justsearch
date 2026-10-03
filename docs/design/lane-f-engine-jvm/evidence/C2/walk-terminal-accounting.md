# C2-8d.2b.1 recorded admission and terminal accounting

2026-09-14, based on pushed d892321c6. WIP prerequisite only: administrative closure,
immutable seal, acknowledgement retention, notifications, real producer and recovery
activation remain open in the [per-item plan](C2-8d-vertical-plan.md).

The existing jobs owner admits a recorded enumeration only at its exact open epoch.
Same-walk observation preserves admission revision, retry state, source hash and
attribution. Maintenance changes assign a new revision while retaining unsealed membership.
The issued claim carries its existing membership epoch so missing/replaced current rows
cannot silently downgrade historical accounting to a legacy diagnostic.

INDEXED/FAILED/SKIPPED coverage extends the existing ledger insert, in the same transaction
as job state and monotonic counters. Successful counters deduplicate path plus committed
content hash within the walk; A→B→A counts two. Failed counters count terminal revisions,
not transient retries. A repaired path does not erase its historical failure count.
No index effect is inferred from a diagnostic ledger append. Coverage/outcome mismatch
rolls back and retains the issued claim. Superseded committed effects and terminal skips
or failures retain their original attribution without completing the replacement row.
A superseded retryable failure is diagnostic only and leaves replacement attempts intact.

## Verification

-1561 integrated:2,220 cases/428 suites across worker-core, indexer-worker and
worker-services, all three EXECUTED,20 existing skips, zero failures/errors. Six PMD tasks
and whole Spotless pass. This precedes the review corrections below.
-1559 first focused run:15 cases, one fixture assertion failure from assuming revision
only advances at enumeration. Corrected the assertion to measure the increment from the
preceding durable revision; admission and terminal accounting also advance revision.
-1560 corrected focused:15 cases, zero failures/errors/skips; PMD/format pass.
-1562 negative:remove distinct-hash deduplication;15 cases, exactly A→B→A fails.
-Independent refute review found legacy scan-key collision/orphan adoption, contradictory
coverage classes, lost superseded non-indexed receipts, and untyped completion bypasses.
Root fixed all four and added runnable real-SQLite regressions.
-1563 reviewed focused:22 cases/3 suites, zero failures/errors/skips, PMD/format pass.
-1564 negative:remove membership witness, coverage validation and superseded receipt;
19 cases, exactly three targeted regressions fail. Restored source semantics afterwards.
-1565 restoration used CRLF backup bytes and failed Spotless before queue tests ran;
worker-core/services were reused. This is not a final queue verification pass.

-1566 final restored and formatted integrated run:2,224 cases/428 suites,20 existing
skips, zero failures/errors; indexer-worker521 EXECUTED, worker-core342 and
worker-services1,361 UP-TO-DATE from1561. Six PMD and whole Spotless pass.

-1567 adds the independent review's superseded-branch rollback proof:full queue522
cases/88 suites EXECUTED,12 existing skips, no failures/errors, PMD/Spotless pass.
An injected ledger refusal rolls back counters and keeps replacement polling blocked;
retry appends exactly once and releases the old claim. Worker-core342/services1,361
from1561 remain applicable (production unchanged since1566):2,225 represented cases,
20 existing skips, no failures/errors. Independent correction review found no remaining
source defect. Register/execution/operation gates, store-recoverability and canonical
document checks pass; no new authority registration is introduced by this slice.

Raw logs, copied XML and counts are retained under
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/walk-terminal*.
The machine-readable companion records task reuse and failure counts. Retain these through
lane F final hosted/installed reconciliation (at least2026-09-20); do not replace access
with a hash. No live model, installed kill, real producer or sealed receipt proof is claimed.
