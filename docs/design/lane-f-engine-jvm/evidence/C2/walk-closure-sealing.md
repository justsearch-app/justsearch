# C2-8d.2b.2: recorded walk closure and sealing

Date: 2026-09-14. Owning plan: [vertical cut](C2-8d-vertical-plan.md).
Closure and sealing are implemented and locally verified; this is not stage completion.

Closure retires COMPLETE enumeration's unseen members inside the closure transaction.
FAILED/CANCELLED stops polling and skips unissued work; actual issued owners drain.
Returns, orphan recovery, deferred callbacks and retryable failures finish stopped work
as typed skips rather than recreating pending work that cannot run. Administrative clear
and source removal preserve current skip evidence and actual issued ownership; a late
committed effect remains historical and cannot overwrite that skip. Existing jobs DONE
and the ledger supply this mechanism; no new state or persistent authority is introduced.

The queue owner seals only closed, terminal, unissued walks after checking each current
member's typed outcome and exact ledger operation/path hash/admission/coverage/content hash.
A missing projection or incompatible current coverage is a dedicated receipt gap. The
versioned immutable receipt separates historical completed/failed counts from current
failure/skip counts, includes enumeration outcome and at most100 sorted failure path hashes.
Duplicate seal returns the stored receipt. Maintenance preflight seals the affected ready
walk before replacement. Stopped walks with issued owners refuse replacement until retry
can seal and detach it. Age cleanup and administrative deletion retain unacknowledged
recorded evidence; exact final acknowledgement permits normal retention cleanup.

Notification delivery, projection into operations.db, progress pruning and actual recorded
producer/recovery activation remain subsequent per-item cuts. Queue primitives alone do not
satisfy C2-8/9's public, restart or end-to-end acceptance.

## Verification record

1612 executes45 focused cases/6 suites, zero skips/failures/errors. Compilation succeeds;
PMD fails one unnecessary initializer, corrected before the next run. This run is not
reported as an integrated pass. Review exposed a wrong-reason missing-ledger fixture,
which must reach the matching-ledger check with a valid typed current outcome.

Logs, count summaries and copied XML are under the active worktree
`F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/1612*`. Retain through final
lane reconciliation plus30 days, at least2026-10-14.

1613 executes both full affected suites: indexer-worker573 cases/93 suites/15 skips and
worker-core342 cases/84 suites/6 skips, totaling915 cases/177 suites/21 skips, zero failures
or errors. PMD and Spotless pass. This executes the production code plus source-removal
exact/prefix/clear rollback, late callback ownership, and exact-ack cleanup fixtures.

Review corrected the missing-ledger fixture to supply a compatible typed current outcome,
and added independent path hash, admission revision, coverage and content hash mismatch
fixtures. 1614 executes18 cases/2 suites (12 sealing plus6 always-on), zero skips/failures;
PMD/Spotless pass. 1613 does not claim these later fixture corrections.

Negative1615 temporarily bypasses the issued-owner and matching-ledger guards. Exactly3
of18 cases fail: missing current coverage, mismatching ledger identity/coverage/content,
and administrative skip with a still-issued owner. The failure messages show that the
invalid seal succeeded, so these are discriminating behavioral regressions. Production
source is restored byte-for-byte from `tmp/seal1614-SqliteIngestionWalkOps.java`.
1616 succeeds with the18-case test result FROM-CACHE from the identical1614 inputs;
PMD/Spotless pass. It is reused proof, not another executed test run. All logs, counts and
copied XML for1613–1616 use the same retained tmp location and retention policy above.

Independent refute review found no surviving code defect; its proof gaps were corrected
and checked against1614/1615 artifacts. Notification delivery, external receipt consumption,
actual producer activation and end-to-end recovery are not claimed here.


## Review scope decision

The prior intermediate v18 primitive could persist FAILED/CANCELLED closure without retiring
unissued rows. Repository caller search confirms that closeRecordedWalkEnumeration has only
test callers besides its API/implementation; recorded producer activation is still owed and
this lane merges only at F. There is no shipped production state requiring migration from
that temporary primitive. The current closure is atomic; supported restart recovery handles
its retained issued PROCESSING members. No old-fixture reconciliation mechanism is added.
Legacy nonrecorded queue rows retain their existing compatibility behavior.
