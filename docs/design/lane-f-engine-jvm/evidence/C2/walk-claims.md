# C2-8d.2a: walk schema and actual batch-exit ownership

2026-09-14. WIP prerequisite inside C2-8d.2, based on pushed0123adb1f. This does
not complete C2-8d.2 or activate recorded ingestion. The owning next cuts are
[the vertical plan](C2-8d-vertical-plan.md).

## Implemented boundary

Jobs schema v18 adds a finite-walk progress projection, a membership epoch column,
and nullable ledger operation/revision/hash/coverage fields. The existing queue
borrows its own connection into SqliteIngestionWalkOps under its existing lock and
transaction. Begin/read/enumeration-close/acknowledgement primitives fail closed on
missing recovery state, changed plan, stale epoch or invalid closure. Closing
an enumeration is not sealing a receipt. Migration and raw-path ledger privacy
repair preserve attribution and recorded fields; no additional store is introduced.

Issued queue claims now outlive replacement/deletion until the exact owner reports
an outcome or returns after synchronous batch exit. Poll skips issued paths before
applying the limit. Both orphan-recovery variants exclude live claims. A return
updates only the exact current PROCESSING admission, preserving attempts/backoff;
replacement, terminal and absent rows are untouched. SQL rollback retains ownership.

Stopped batches return unvisited/unwritten claims; the existing journal retains
failed returns and retries before polling. Written effects remain in pendingMarkDone
until their index commit and queue outcome. Observer failures after journal handoff
cannot mark that effect failed. Non-VM extractor/writer errors follow bounded per-unit
failure handling; batch-embedding errors use the existing fallback. VM errors propagate.
Profiling reset retains failed returns and moves discarded written claims to the same
return set after actual loop exit and intentional index/queue cleanup.

## Verification and corrections

- focused1541: compile failed on an unqualified nested SHA256 constant; no tests ran.
  The corrected reference passed focused1542:9 cases/2 suites, including3 projection
  cases and6 always-on guardrails. This was an early schema-only result.
- focused1543:93 cases/17 suites, both selected tasks EXECUTED, no failures/errors/skips.
- integrated1544:2,204 cases/426 suites, all three tasks EXECUTED,9 failures and20
  existing skips. Three historical fixtures stamped V10–V12 lacked V9 scan_id and
  other historical objects. Six fixtures assumed replacement could overtake a live
  claim, or simulated owner death only by aging timestamps / using path-only outcomes.
  Worker-core and worker-services tests passed. PMD found2 redundant test qualifiers.
- corrections1545:100 cases/6 suites EXECUTED, no failures/errors/skips; worker-services
  PMD passed. Faithful historical V10 objects plus V11/V12 additions now migrate to
  the same table/column/index shape as a fresh target. Claim tests preserve their
  original admission/hash/retry assertions, using actual claim completion or reopen
  to model owner loss. No production migration repair hides malformed version stamps.
- negative1546:14 cases/3 suites, exactly3 assertion failures when the active-path
  filter and batch return were removed. A replacement overtook its owner; stopped
  batches returned0 instead of2/1 unwritten claims. Both production files were restored
  byte-for-byte from the archived manifest before the next run.
- integrated1547:2,204 cases/426 suites, zero failures/errors and20 existing skips.
  Indexer-worker503 EXECUTED; worker-core342 UP-TO-DATE and worker-services1,359
  FROM-CACHE match their successful1544 results. All6 PMD tasks and whole Spotless pass.
- Independent review then found profiling reset could erase retained claim returns.
  Root also covered still-pending written claims. reset1548 passes89 cases/15 suites,
  both tasks EXECUTED, zero failures/errors/skips, relevant PMD and whole Spotless.
  Review refuted the corrected ownership trace without another material finding.
- Final integrated1549 passes2,206 cases/426 suites, zero failures/errors and20 existing
  skips. Indexer-worker504 and worker-services1,360 EXECUTED; worker-core342 UP-TO-DATE
  from1544. All6 PMD tasks and whole Spotless pass. The accompanying JSON records task status.

The real SQLite cases cover return rollback/retry, forged identity refusal, replacement
blocking, poll-limit-one fairness, runtime reaper vs reopen recovery, and clear/re-enqueue
waiting for the old identity. Journal and real loop cases cover stopped batches, write
failure, fatal propagation, embedding fallback, observer failure after commit handoff,
and reset preservation. No live model or installed end-to-end proof is claimed here.
Existing compiler/JDK/deprecation warnings and20 pre-existing skips remain visible.

Store recoverability, operation/execution surface and guard-resolution checks pass locally.
Canonical docs were regenerated and link/runtime-matrix checks pass with no derived drift.

## Hosted state

At84f70806c, CI34812687837 failed the undeclared RecordedIngestPlanResolver consumer.
Commit0123adb1f adds the declaration; CI34815355461 passes Public claims and11 other
jobs, but Windows-native tests fail WindowsParserContainmentTest's recycle case with
ExtractionTimeoutException. CLA34815354415 passes. The timeout is being investigated;
it is not treated as successful hosted proof or waived. This WIP checkpoint requires
its own hosted run after push.

## Remaining acceptance and evidence access

Next d.2b connects recorded membership, every terminal queue transition, distinct
committed-hash/failed-admission counters and transactional seal. d.2c supplies unlocked
notifications, operation-completion acknowledgement and retention. Source-removal accounting,
stale issued committed coverage, cancellation, rollback/seal and receipt-loss proofs remain
owed in those cuts. d.3 real producer and C2-9 authorization/pre-poll recovery are still open.
Installed shutdown/kill proof must establish actual exit; queue close does not certify seal.
The review's failed-open connection leak is fixed in the immediate queue-owner follow-up
below; it does not change the next unit-accounting/seal cut.

Logs, archived XML generations and *-counts.json files use the labels above in
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/. Negative backups and hashes
are in tmp/claim-negative1546-backups.json. Hosted output is in
 tmp/binding-hosted1543.json, tmp/binding-hosted-failed1543.txt,
 tmp/resolver-register-hosted1547.json and tmp/resolver-register-hosted-failed1549.txt.
Retain these accessible artifacts until2026-10-14 or30 days after lane acceptance,
whichever is later. The committed JSON records counts/statuses; hashes alone are not access.

## Failed-open resource correction (2026-09-14)

Queue open now closes its newly acquired connection on setup failure without checkpointing
or restoring auto-commit. A double open refuses before acquiring another connection. Failed
cleanup retains the existing connectionFailure guard and original setup failure. No usable
queue or walk completion is published from a failed open.

Initial1550 failed because its test hook targeted V2, which fresh bootstrap detection skips;
no cleanup fault was injected. Corrected1551 injects at the final migration step and passes
29 cases/2 suites. The test verifies the actual acquired connection is closed, no change feed
is published, the same instance can retry, and double-open refusal preserves its live claim.
Negative1552 removes failed-open close:7 cases/2 suites include exactly one failure at the
actual connection-isClosed assertion. Production was restored byte-for-byte (hash in
 tmp/open-negative1552-sha.txt) before final1553:505 cases/86 suites,12 existing skips,
zero failures/errors, test task EXECUTED; both PMD and whole Spotless pass. Independent
review found no further defect. Close-failure suppression ordering on this new branch is
code-reviewed, not fault-injected by this test; shared failed-normal-close behavior already
has separate regression coverage. Raw labels and retention follow this document's policy.
