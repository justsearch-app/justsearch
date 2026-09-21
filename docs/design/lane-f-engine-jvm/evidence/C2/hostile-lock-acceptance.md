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

Root owns production lifecycle/state changes and the single build/stack. Bounded
tests/review may be delegated once contracts are fixed. Run focused deterministic
regressions, preserve the original red evidence, then the installed five-case
matrix and eight-case operation matrix, followed by integrated/hosted proof.
No scenario failure is waived. C2 bulk design is settled but not implemented;
C2-12 and D1/D2/E/F remain. Retain artifacts through acceptance plus30 days.
