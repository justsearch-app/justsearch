# R8: failure boundaries and recoverable job projections

September13, base8ad4fe74b plus this item. R8 is implemented and its item gate passes;
full batch verification and final independent review remain pending.

The background service already preserves failed refusal persistence after R1/R3.
Two new real-SQLite RAISE(ABORT) tests prove that a closed scheduler keeps its
original rejection with the storage error suppressed, and shutdown fails both
synchronously and through completion. Both leave the accepted row unresolved,
publish the keyed persistence-failure signal, and execute no agent body.

RootLifecycleOps now persists a failed terminal walk even after admitting files.
The named test sends one admission then throws and verifies the state after
reopening roots.json. SqliteJobQueue materializes committed projection deltas
outside the SQL rollback region; a projection exception still reaches its caller,
but cannot request rollback of a committed job. A real second SQLite connection
checks the job survived and a connection spy checks no rollback was attempted.

The jobs bridge clears its attempt timestamps on every replacement snapshot.
Exponential delay remains, so snapshot-then-immediate-error does not spin.
The Engine's existing 256-frame handoff coalesces pending deltas by path hash:
remove the old pending frame and append the latest sequence, retain INSERT when
an unobserved INSERT becomes UPDATE, preserve DELETE and reinsert, and never
coalesce across a snapshot. This is a bounded projection, with no second map,
store or writer. Distinct paths still fail visibly on overflow; the SQLite
producer never waits for a consumer or for queue capacity.

LauncherEnvironment distinguishes the existing Head dependency-teardown phase
from a failed drain barrier. A failed offline procedure drain retains borrowed
dependencies. Once dependency teardown starts, launcher cleanup continues through
operations, telemetry, executors, instance lock and property restoration, retaining
original and suppressed failures. Failed operations close retains the lock.
R8 also exposes an interaction with R5: the retention task's termination wait must
precede the Head teardown flag. A failed wait now remains retryable and keeps the
operations store alive. The remaining handles use a projection omitting the timer
that already drained; no new lifecycle state was introduced.

## Named negative controls

Root-run negative819 executes10 cases in5 suites: four intended failures identify
the failed-walk state, post-commit rollback, exhausted retry cap after successful
snapshots, and skipped launcher cleanup. Both new background refusal tests pass.
Negative820 executes the retention-drain interaction test and fails because the
old Head marked dependency teardown started despite the still-running timer.

Raw proof lives at worktree tmp/c2-review-r8-*.txt and matching -xml/ and
-counts.json siblings. Retain through lane acceptance plus30 days; export before
worktree release. Commands and final counts belong in review-r8-verification.json.
No full-suite or hosted result is claimed for this item before the batch gate.

## Item gate

Final821 passes136 cases in17 suites, zero failures/errors/skips, affected PMD
and UI integration-test compilation. Four test tasks execute; app-agent reuses
unchanged-input819. This includes the real Engine/SQLite stream test. Coalescing
bypass negative822 fails four of five cases for intended reasons; unique-path
overflow remains correctly bounded. Exact-byte source restoration is followed
by823: five coalescing cases execute and pass, with PMD and UI compilation passing.
[Commands, revision and task counts](review-r8-verification.json) distinguish
execution from reuse. The source was re-read against each named assertion.

## Delegation record

Three bounded test workers contributed background refusal, root walk and queue
projection coverage; a fourth wrote the coalescing tests. The queue-test worker
violated its no-Gradle brief and ran an uncoordinated focused negative. No root
build overlapped it; root reran the detector in819 and captured authoritative
logs/XML. The worker was stopped from further work. Its uncaptured run is not
counted as acceptance evidence. Root owns production changes and all item gates.
