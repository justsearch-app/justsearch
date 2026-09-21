# C2 recovery fixtures and hostile-lock acceptance, 2026-09-21

## Contract and observed failures

Design section16 requires the original boot5-thread/10ms and ingest3-thread/50ms
attacks, 100 accepted documents, actual shared/exclusive acquisitions and the same
180-second search bound. Healthy runs remain under attack. A counted exit at or
after the submitting incarnation releases locks, then requires an owned healthy
successor. Do not exclude control files, retry arbitrary HTTP failures, or confuse
one search hit with the durable outcome of the accepted operation.

Hosted35548954444 at1a94 and35552397005 at0a23 have retained failed artifacts under
`tmp/2066-*` and `tmp/2078-*`. Current hosted integration reports107 invocations,
eight failures and42 skips. All seven keyed fault-boundary/disconnect cases pass;
the eighth OperationResume case fails its PROCESSING/child-entry precondition on
three invocations before any intentional kill. The current writer fails three
times with a proven RECOVERY_SCOPE_REFUSED parent; locks have control-file failures.

The writer and lock corpora were outside persisted recovery authority. The fixture
now seeds only their corpus as watched before boot and puts writer documents under
that root. This preserves strict recovery validation and the injected Lucene fault.
Direct2082 passes at4f276a plus that fixture change: seeded FileAlreadyExistsException,
fatal exit1, one counted restart into incarnation2, both documents searchable and
owned stop/closed ports. Read-only SQL confirms both original keyed parent/child
pairs COMPLETE with one completed unit each and zero failed units. Evidence:
`tmp/2082-writer-rooted.txt`, `tmp/2082-writer-operations.json`, and fixture
`tmp/lane-f-takeover/writer-live-1789957613513/`.

Installed2083 runs all five EngineSupervisedRecoveryE2ETest cases: writer, migration,
lock-ingest and processing pass; lock-boot fails before discovery. XML/counts/log are
preserved under `tmp/2083-rooted-supervised*`. Its actual failure is a valid watched
roots file temporarily held by the intruder. Files.readString throws generic
java.io.IOException from FileDispatcherImpl.read0; WatchedRootsStore:165 labels it
CorruptDurableStoreException. Exact stack and acquisitions live under
`tmp/lane-f-takeover/writer-junit-255ead3c-5156-44a1-9d43-afac5a3b07ab/`, run
`fea7191b-c16f-4ca8-bf89-5251999582d7`. A FileSystemException-only classifier would
miss this observed Windows failure. It is not malformed JSON.

## Root-owned correction design

1. Keep unavailable storage distinct from malformed persisted content. Shared
   configuration.persistence is the existing home for durable file I/O. Compare
   bounded shared-read-lock acquisition for the two demonstrated small authority
   files (watched roots and strict generation state) with message-matching retries:
   the former obtains an OS lock before reading; the latter relies on localized
   generic IOException text. No cached/backup state or missing-file fallback may
   authorize a mutation. Parse only successfully read bytes and release any file
   lock before parsing or calling another durable owner. Validate same-JVM overlap,
   external Windows locks, interruption, bounded exhaustion, replacement during
   wait, missing and malformed files. The helper is implemented provisionally;
   Windows2084 refutes writer compatibility (see below), so it is not accepted.
2. Operations SQLite has startup BUSY retries but runtime acceptance fails directly
   on contention. Extend only the proven acceptance seam, using numeric SQLite
   lock errors, a monotonic bound and phase-aware rollback evidence. Generic I/O,
   corruption and uncertain commit/rollback are not retry permission. Do not add a
   universal retry wrapper around every store write. Compare runtime reopen/key
   reconciliation explicitly if it becomes necessary; it is a lifecycle change.
3. A transient generation preflight read is not failed enumeration. An archived
   accepted lock-boot row was permanently failed before any queue membership. Fix
   that classification with a typed pre-effect unavailable outcome and cancellation/
   liveness owned by the existing bounded producer or Engine scheduling owner.
   The proven structural defect is actionable now; shorter read retries alone do
   not establish its correctness. Keep true post-start enumeration failures failed.
4. Distinguish socket-close ambiguity from explicit failed acceptance: resolve the
   exact supplied operation key read-only after the counted exit, never repost an
   unknown outcome. Require terminal/full-corpus evidence and the higher owned
   successor on the fatal branch. Preserve healthy under-attack behavior.
5. Establish the required issued-claim behavior under producer restatement. The independent
   hosted2078 audit establishes the race before teardown: in fixture29960,
   untagged watcher enqueue occurs02:08:57.631, claim.636, processing.637,
   child spawn.645, then the recorded walk replaces it with PENDING at.687.
   Three other failures replace the row8–43ms after spawn. The replacement
   timestamp precedes teardown by about30seconds. SqliteIngestionWalkOps uses
   INSERT OR REPLACE; SqliteJobQueue subsequently rejects the old claim's
   completion because its scan key/revision no longer matches. Local2083 wins
   the opposite schedule (recorded enumeration before claim). Exact witness
   normalization matches; longer waits cannot fix the joint predicate. Keep
   the processing precondition strict. The final mechanism depends on existing
   supersession semantics: a rejected old completion may correctly protect the
   new revision. A new durable displaced-claim table is not justified merely by
   the fault fixture's joint predicate. Root is comparing the actual required
   receipt/exit invariant against targeted single-producer fixture isolation.
   Refutation resolves this in favor of harness-only isolation: latest-revision
   replacement is intentional, and R0 must not complete R1. No contract requires
   replaying obsolete untagged R0 after death. Extend the existing operation-fault
   automatic-producer isolation to processing/operation fixtures, retain persisted
   root authority, and strengthen the SQL predicate to exact recorded child and
   unit revision before/after death. Separately, markDoneTransitions drops a live
   superseded untagged claim's already-committed effect because both rows and receipt
   are zero/null. Its exact-issued-claim check permits truthful general ledger
   history without changing R1 or granting recorded coverage. Preserve that effect
   as the single-claim path already does; no new durable representation is needed.

## Focused read-lock proof and refutation

2084 at4f276a plus provisional read-lock/fixture changes ran configuration,
app-services and worker-core focused tests, their PMD tasks and spotlessCheck.
The build failed: six configuration cases ran, five passed and atomic writer/
reader concurrency failed with AccessDeniedException during replacement. The
external shared/exclusive locks, same-JVM overlap, interruption and missing-file
cases passed. Worker-core tests did not compile due to a missing assertion import;
two test lock variables also triggered PMD. Preserve the original failed output
and XML under `tmp/2084-contended-reads*`; unexecuted owners have no proof yet.

The writer failure is a design blocker, not an assertion to weaken. Compare
plain open read handles with shared locks on the actual Windows/JDK runtime to
separate existing handle sharing behavior from a new locking regression. Keep
the original writer success and whole-version assertions. A successful lock read
alone does not establish compatibility with the owning atomic writer.

Windows/JDK25 probe2085 (`tmp/2085-read-replace-probe-01/`) isolates the cause:
plain open READ handles and shared-locked handles both block atomic replacement;
plain Files.readAllBytes also reproduces it. The failed move leaves exact old
target/new temp bytes intact and succeeds after the reader closes. Non-atomic
replacement works but is rejected as a design alternative. Existing generation
rotation to an unoccupied name followed by atomic publication also works under
the old held handle. Multiple store/manager instances are intentional, so instance
synchronization cannot solve this; a global keyed lock registry is unnecessary.

Selected correction: AtomicFileWrites retries only AccessDeniedException from
the atomic rename for at most2seconds with10ms interruptible waits, retaining the
same completed temp bytes. No generic write/force/I/O retry or new non-atomic
fallback. Permanent permission denial remains the original exception after the
bound; interruption preserves its flag, and existing failure cleanup preserves
the target. The unchanged real concurrent replacement test and injected denial/
interruption/non-denial tests verify the seam. This is pending proof.

2087 exercises ten worker-core strict-read cases: nine pass, changed-while-locked
fails its expected MIGRATING observation. Preserve its XML and diagnose whether
the real generation writer established that state before changing the test.
2088 runs17 watched-root cases across five suites successfully, including malformed
and future state, both unavailable loaders, and interruption. Its only failure is
two redundant assertion qualifiers in worker-core test PMD. Artifacts are under
`tmp/2087-authority-reads*` and `tmp/2088-roots-and-static*`.

2089 passes all281 configuration cases in33 suites plus configuration main/test
PMD, worker-core test PMD and format. Real Windows concurrent replacement and all
bounded-denial/cleanup/interruption cases execute. Retain `tmp/2089-atomic-read-compatibility*`.

The generation diagnostic (`tmp/GenerationLockDiagnostic.java` and `.out.txt`)
explains2087: the cold writer interpreted unavailable state as absent, adopted the
existing generation and published intermediate IDLE before writing MIGRATING.
The waiting reader honestly observed that intermediate authority. Fix the owner:
loadStateBestEffort now propagates unavailable byte reads to initializeOrLoad,
which cannot adopt/restore on a lock failure. Malformed parsed state retains its
existing backup recovery behavior; strict eligibility still refuses backups.
The replacement test primes the writer cache to establish its intended single
replacement, and a separate cold-cache regression requires refusal without any
state/generation/backup rewrite, then successful transition after lock release.
2090 passes the full worker-core module (including revised owner tests), watched
roots, harness and access funnel; all changed-owner PMD and format pass. Across
419 cases/98 suites it has one queue-test failure and six qualified skips. The
failed legacy-collision test expected zero general ledger events from an old real
effect. Its intended membership isolation remains correct: update the assertion
to require one general-history event with no operation key or terminal coverage,
zero completed recorded units and unchanged fresh PENDING work. This preserves
the test's intent rather than allowing an old claim to complete the new revision.

Review also found that new String(bytes, UTF8) silently replaces malformed input.
The loader now decodes with REPORT outside the byte-read failure classifier.
Both loaders must reject JSON-shaped invalid UTF-8 as corrupt without changing
bytes. Reviewer withdrew a proposed extra-reopen requirement: a strict read is
an observation during its invocation, explicitly not a freshness-at-return lease.
The diagnosed intermediate IDLE publication, not a stale-handle guess, explains
the original failing generation test.

2093 passes65 cases/10 suites and affected PMD/format after those corrections.
2094 then independently restores the three defects temporarily: unavailable
generation adoption, UTF-8 replacement decoding and lost superseded ledger effect.
All three target regressions fail for their intended assertions (15 cases total,
three failures, no errors). The exact fixed source bytes are restored in finally.
Logs/XML are retained under `tmp/2093-review-corrections*` and
`tmp/2094-negative-controls*`; diagnostic mutation script/backups remain in tmp.

Root2095 passes restored focused proof plus all13 installed EngineSupervised/
OperationResume scenarios in4m12s:89 cases/13 suites, zero failures/errors/skips.
Worker-core11 and installed13 execute; indexer-worker34 and app-services31 reuse
matching2093 cache entries after the negative sources were restored. Preflight and
post-stop health are ABSENT, with no foreign runs or inference orphan. The replay fixture proves parent/child RUNNING attempt1,
exact recorded child and unit revision before death, unchanged pair during cooldown,
and the same rows COMPLETE after one Resume each. Both hostile-lock attacks pass
their original parameters. Exact eight-operation and five-supervised fixture
manifests are `tmp/2095-installed-recovery-installed-artifacts.json` and
`tmp/2095-installed-recovery-supervised-artifacts.json`; every fixture confirms
owned stop with closed ports. Output/XML/counts share the2095-installed-recovery
prefix. Independent review reports no remaining actionable defect in this batch.

Configuration2091 passes with eight informational findings; store2092 passes all46
authorities. Canonical storage behavior is updated, including removal of the stale
three-process Lucene ownership claim. Docs index/skill/link/config checks pass2097;
the regenerated config matrix has no semantic changes.

Hosted checkpoint4f276 reconciliation2096: CI35554192203 passes Public claims and
platform contracts, confirming the previous governance correction. It fails the
Windows-native dev-runner supervisor test (AbortError after ordered-transition
proof); integration is CANCELLED and supplies no passing proof. The exact job/log
is retained under `tmp/2096-*`; bounded source triage is active. This checkpoint
does not close prolonged post-accept preflight classification or SQLite acceptance
ambiguity, nor C2 bulk work, final full-stress/hosted proof or D1/D2/E/F.

The reviewed authority/replay batch is committed and pushed as77a2693e8.
Hosted Windows triage identifies the exact failure at the PowerShell helper's
first readiness line, before either publication assertion. Listener registration
is synchronous, helper failure has a different branch, and clean cleanup about
three seconds after the ten-second bound establishes slow cold startup. A prior
hosted failure has the same symptom. Only the fixture startup allowance increases
to30seconds, with elapsed/PID/stderr diagnostics; the product's one-second rename
deadline and the five-second publication watchdog remain unchanged. No new generic
readiness abstraction or product retry policy is introduced.

Direct2099 passes both actual Windows reader handles, EPERM under the held handle,
release-success and1021ms bounded exhaustion. Full2100 dev-runner discovery passes
11/11 test files. Logs are `tmp/2099-supervisor-readiness.txt` and
`tmp/2100-dev-runner-suite.txt`. Corrected hosted Windows proof is still required.

## Accepted initial preflight: bounded owner, no false enumeration failure

The helper-startup correction is pushed as7b2c31555. The next selected correction
keeps the initial recorded-generation preflight inside the existing admitted Engine
root-walk producer and its LONG_RUNNING cancellation alarm. Retry only a strict read's
typed FileReadContendedException or NoSuchFileException, before scanRoot starts.
The latter is a real interval in writeState's current→prev→completed-temp sequence.
Every retry rereads current state; neither cache nor backup supplies authority.
The lock helper already waits up to2seconds; missing-file observations wait10ms to
avoid a busy loop. No timer, coordinator state machine or durable marker is added.

The alternatives were an Engine-scheduled retry contract and coordinator wakeups;
those require new pre-effect result/lifetime state because maintain is event-driven.
The existing producer already owns cancellation, deadline and actual walker/delivery
exit, so it is the smaller correct owner. A permanently missing/contended pointer
remains unavailable until that existing cancellation/deadline, then returns through
the Engine's ordinary CANCELLED terminal. This is not successful enumeration and
cannot admit a path. Already-cancelled entry preserves its existing refusal. Missing
state still fails immediately in standalone strict capture; malformed bytes, non-IDLE
state and changed generation are never retried. Post-start batch validation and
enumeration failure behavior remain unchanged; no completed batch is re-executed.

Root owns WorkerIngestService implementation. Bounded worker tests cover real missing
and locked files, unchanged refusal cases and cancellation; separate Engine tests
must exercise the real service path and actual-exit/cancellation composition. Required
proof is pending. SQLite acceptance ambiguity and the bulk/later-stage work remain.

Independent review found an additional deadline-before-service-entry race: the
service's existing CANCELLED exception bypassed Engine's ordinary deadline terminal
and the coordinator saw FAILED because its separate child token was not cancelled.
Engine now catches only recorded-call CANCELLED when its own deadline has expired,
then retains normal actual-exit, frame-drain and delivery-error handling before
projecting CANCELLED. A real-service test holds entry until that deadline expires.

Focused2102 executes40 cases in4 suites:38 pass, two new Engine deadline fixtures
fail because they supplied the base timeout without accounting for LONG_RUNNING's
multiplier. Production compile/main PMD and format pass; test PMD reports six local
qualifier/unused-lock violations. Original output, XML and counts are preserved at
`tmp/2102-recorded-preflight*`; fixture inputs and style are being corrected without
weakening assertions. No installed proof of this batch has run yet.

The hostile-lock acceptance fixture now requires the original durable operation's
SUCCESS with zero failed units and all100 expected paths searchable, not merely one
hit. It submits exactly once. An actual socket loss permits only read-only resolution
of that supplied key after a counted owned exit; unknown/expired/failed outcomes
cannot pass. The original attack intensity and180second bound remain; healthy Engine
runs retain the intruder, and only the previously permitted counted exit releases it.
The qualifying successor must have a higher incarnation. Deterministic fixture tests
and installed proof remain pending; these stronger assertions may expose further
product defects rather than discharge the unresolved SQLite ambiguity by assumption.

Corrected2103 passes40 cases/4 suites, zero failures/errors/skips, affected PMD and
format; indexer-worker23 reuses matching2102 results, Engine17 executes. Negative2104
backs out the initial wait and Engine deadline catch: all three selected new tests
fail for their intended reason (missing/current-lock refuses instead of waiting,
deadline-before-entry completes exceptionally instead of CANCELLED). Six auxiliary
cases pass. Both production files are restored byte-for-byte in a finally block;
output/XML/counts and source backups are retained under `tmp/2104-*`.

Node2106 passes all8 hostile-fixture tests, including explicit500/no replay,
socket-loss/exact-key successor resolution, UNKNOWN/FAILED refusal, stale-exit
non-release and99-of100 rejection. Review withdraws a proposed completedUnits=100
check: SqliteIngestionWalkOps counts only novel INDEXED path/content pairs, so
watcher-first unchanged skips correctly yield fewer completed units. Parent SUCCESS
requires all frozen roots' sealed and acknowledged children; exact100 searchable
paths supplies the corpus-effect proof without mislabeling that counter.

Hosted2105 at7b2c31555 (CI35557544658) passes Windows-native, including the helper
startup correction. Search-worker fails CommittedContentHashTest's stale/equal-valued
claim assertion at68 on all three attempts. Integration job reports SUCCESS but
its test log reports100 cases, one lock-ingest failure,42 skips; it is not green
integration proof. The failed fixture returns explicitHTTP500 OPERATION_STORAGE_FAILED,
so no socket-loss recovery may hide it. Artifacts/logs are `tmp/2105-*`; the exact
failed fixture suffix is a2528441-d20a-4f56-b043-a1c1c08a29df. Read-only source/numeric
SQLite triage is active. Restored focused tests plus the13 installed cases are now
running as2107 with the strengthened fixture. No result is claimed yet.

Final2107 finishes in5m48s:53 cases/6 suites, zero failures/errors/skips. The13
installed cases execute; the40 unchanged focused cases reuse matching cache results.
Both original lock attacks pass with100 exact paths, original-key COMPLETE/SUCCESS,
zero failures, one counted exit and a higher successor. Each reports one completed
unit because automatic ingestion already handled other members; this confirms the
counter distinction above. All13 owned stops report portsClosed=true and final health
is ABSENT with no foreign run/orphan. Exact fixture manifests are
`tmp/2107-installed-preflight-installed-artifacts.json` (eight) and
`tmp/2107-installed-preflight-supervised-artifacts.json` (five, including both proofs).

The hosted hash test's intent remains unchanged: stale, forged and duplicate claims
cannot publish or replace a job hash. Its old zero/one history assertions predated
77a's preservation of an exact issued obsolete claim's general effect. Updated checks
compare unchanged histories across forged/duplicate callbacks, retain every job-hash
assertion and verify operation/unit/hash/coverage are null in those general events.
Full indexer-worker2109 passes670 cases/102 suites with15 qualified skips:12 unavailable
ONNX embedding-model checks and three filesystem/privilege checks. No test result
is reused; affected test PMD and format pass. Skips are retained in
`tmp/2109-indexer-ledger-skips.json`. Final Node2110 passes9 cases, preserving the
original HTTP200 success:false refusal case as well as new HTTP500/no-replay proof.
Canonical index/skill/link regeneration checks pass; no shared skill content changes.

Independent SQLite reproduction distinguishes two real failure classes using a copy
of the hosted database and sqlite-jdbc3.51.2.0 on Windows. Shared SHM locking yields
SQLITE_BUSY(5) at acceptance's first prune DELETE, with a successful rollback and
zero operations/one retained preparation. Exclusive WAL locking can instead fail at
commit with IOERR_WRITE(778), followed by rollback reporting no active transaction.
The archived HTTP500 has no retained SQL exception, so neither phase is attributed
to that historical request. A generic retry is rejected; any next correction must
preserve phase and proven rollback/commit outcomes. Diagnostic evidence is being
retained under `tmp/2111-sqlite-acceptance-probe/`. This remains open after this
preflight checkpoint, together with final full-stress/hosted proof and bulk/later stages.

The preflight batch is committed and pushed as03c5d0487. Before selecting an
application retry loop, compare native BEGIN IMMEDIATE ownership with the current
deferred read→write upgrade using the existing5000ms busy timeout and a short held
SHM lock. All four transactional runtime methods write, while ordinary reads are
outside the transaction helper. This could let the existing native busy handler
wait before any acceptance read rather than add a scheduler, marker or retry owner.
No production transaction-mode change is made yet.

Local sqlite-jdbc bytecode inspection adds a necessary constraint: commit()/rollback()
immediately begin another transaction, and setAutoCommit(false) changes its Java flag
before native BEGIN. Globally setting transaction_mode=IMMEDIATE could therefore
report a failed new BEGIN after the actual commit already succeeded. Raw SQL ownership
under JDBC auto-commit is only a candidate until separate-reader visibility and
rollback tests establish atomicity; never infer it from a successful final row alone.
The bounded2113 comparison owns this question. WAL IOERR_WRITE is distinct and must
not be assumed repaired by a BEGIN-mode change. Bytecode evidence is retained in
`tmp/2113-sqlite-connection-bytecode.txt` and `tmp/2113-sqlite-db-bytecode.txt`.

Native2113 confirms the smaller owner: with the same5000ms busy timeout, deferred
read→write upgrade fails BUSY after1ms although the helper releases SHM at250ms.
Raw BEGIN IMMEDIATE waits336ms for release at250ms, then completes acceptance.
A separate reader sees zero operations/one preparation before explicit COMMIT and
one operation/zero preparations afterward; a second transaction remains invisible
and its new row is absent after explicit ROLLBACK. Short250ms exclusive WAL locking
also succeeds through SQLite's existing native write handling; prolonged held WAL
still has the distinct2111 IOERR_WRITE failure. These are isolated actual SQLite
outcomes, not attribution of the historical hosted request.

Selected implementation changes the one runtime transaction helper to explicit
BEGIN IMMEDIATE/COMMIT/ROLLBACK under the existing store lock, leaving JDBC auto-commit
unchanged. Its four callers already write. Failed BEGIN runs no body and leaves a
usable connection; failed work/commit still requires an explicit rollback, and an
uncertain rollback closes the connection as before. Schema startup retains its
existing separate retry/preservation contract. No application retry loop, timer,
configuration, dependency, journal or cross-owner writer is added. SQL failures now
log their numeric code and cause internally, retaining the public failure boundary.
Real-store Windows release/exhaustion/commit-failure regressions and independent
review are in progress; no compiled or installed proof is yet claimed for this edit.

Root2114 executes the full app-observability module:583 cases/88 suites, zero
failures/errors/skips, plus main/test PMD and format. It includes all three real
Windows contention cases and existing prepared-transfer rollback tests. Output,
XML/counts use `tmp/2114-operation-transaction*`. This precedes the cleanup correction
below; installed proof of the transaction change remains pending.

Consolidated review finds no hidden nested transaction or preparation atomicity
defect. It identifies a pre-existing cleanup hole exposed by the uncertain-rollback
contract: a failed connection.close in finally can replace the primary SQL exception
and leave the same field available. Root now nulls the field before closing the
uncertain handle and preserves rollback/close failures as suppressed evidence on
the primary, including unchecked cleanup failures. This follows the existing startup
close pattern and adds no recovery state machine. Successful rollback and failed
BEGIN retain same-store reuse. The real WAL case is being strengthened to prove its
IOERR_WRITE/failed-rollback boundary and retired same-store behavior; a separate
cross-platform injected cleanup-failure test must prove original-cause preservation.
The reviewer withdrew a draft-file lock-order concern after reading the frozen tests:
all lock acquisition precedes task submission. Required final proof is still pending.

Root owns production lifecycle/state changes and the single build/stack. Bounded
tests/review may be delegated once contracts are fixed. Run focused deterministic
regressions, preserve the original red evidence, then the installed five-case
matrix and eight-case operation matrix, followed by integrated/hosted proof.
No scenario failure is waived. C2 bulk design is settled but not implemented;
C2-12 and D1/D2/E/F remain. Retain artifacts through acceptance plus30 days.

## Final SQLite correction proof (2026-09-21)

The reviewed correction preserves the original failure while retiring an uncertain
connection before close. Real WAL failure proves SQL code10/IOERR_WRITE, suppressed
rollback failure and same-store refusal. Cross-platform SQL/runtime/Error cleanup
cases prove primary-cause identity and ordered suppressed causes. The mock intercepts
only the first control statement; body queries use separate real statements.

| Run | Revision and result |
| --- | --- |
| 2115 native negative | Temporarily restores03c's deferred transaction helper:3 cases,2 intended failures (short SHM release and native-wait exhaustion),1 auxiliary pass. Source restored byte-for-byte. |
| 2116 full module | Corrected transaction/cleanup source:586 cases/89 suites,0 failures/errors/skips; main/test PMD and format pass. |
| 2117 cleanup negative | Temporarily removes retirement/close suppression:4 cases,3 intended failures across SQL/runtime/Error cleanup,1 auxiliary pass. Source restored byte-for-byte. |
| 2118 final focused + installed | Final production and narrowed test mock:28 cases/6 suites,0 failures/errors/skips; all test tasks execute,5m51s. All13 installed cases pass; main/test PMD and format pass. |
| 2121 Windows selection | Same production, explicit windows tags:4 cases/2 suites,0 failures/errors/skips under -PwindowsOnly=true; test PMD and format pass. |
| 2122 workflow / 2123 store gate | Workflow triggers pass; operations-db registers all three contention/cleanup test owners;6 catalogs/46 authorities pass. |

Exact command/log/XML/counts are retained under tmp/2115-native-transaction-negative*,
tmp/2116-operation-transaction*, tmp/2117-cleanup-negative*,
tmp/2118-installed-operation-transaction* and tmp/2121-windows-store-discovery*.
Negative scripts and original source backups accompany2115/2117. The2118 installed
manifest names8 operation fixtures; its supervised manifest names5 recovery fixtures.
Both original hostile attacks prove100 exact paths and the original accepted key's
COMPLETE/SUCCESS with zero failed units. All13 owned stops report portsClosed=true;
final health is ABSENT with no foreign run or inference orphan. No build remains active.

Hosted CI35559286589 at03c5d0487 passes all jobs. Integration job106208910312 has96
cases,0 failures/errors and42 explicit model/external-fixture skips; all13 owned
recovery cases pass. Search-worker also passes. Exact logs, artifact XML, counts and
skip inventory are retained under tmp/2119-*. This predates the SQLite correction;
fresh hosted proof remains required. Historical HTTP500's exact interleaving remains
unproven, although2111/2113 establish the independent structural defect and its fix.

The Windows job now includes :modules:app-observability:test. Its windowsOnly property
selects @Tag("windows"), so @EnabledOnOs alone was insufficient: the three acceptance
cases and the existing Windows startup lock case now carry the tag. The cleanup
cases remain cross-platform. Wiring is not hosted execution;2121 proves local discovery.

All artifacts retain the lane-acceptance-plus30-days policy and must be exported
before this worktree is released. Final full stress, fresh hosted proof, C2 bulk and
later stages remain open; this checkpoint is not lane completion.
