# C2-7: committed unit checkpoints, bounded cadence, ordered shutdown

2026-09-14; implementing atop `347187073`. C2 remains open.

## Grounding and mechanism

`OperationAttemptRunnerImpl.Control.checkpoint` already commits each reported unit through
`SqliteOperationStore.checkpoint` under its single connection lock. There is no pending
progress buffer. Preserve this ownership: no second cursor or dirty-row registry.
Re-reading then rewriting a cursor could overwrite a newer cursor even at equal counts.

Add `OperationStore.checkpointDurableOperations()`: one atomic SQL update re-checkpoints
current cursor/completed/failed values and stamps updated_at with the non-regressing store
clock for RUNNING/COMPLETE_WITH_GAPS DURABLE rows. Current row state is the only authority;
no caller snapshot is applied. Interactive, accepted-without-start, and terminal rows are
untouched. updated_at includes this periodic attestation of the same committed position
(a durable heartbeat when no unit advanced); it is not a dedicated checkpoint clock and
must not be interpreted as reduced replay work or greater durability. Reuse the existing
mutation timestamp rather than add a second timestamp with no consumer. This is an operation
checkpoint transaction, not a WAL maintenance operation.

The fixed30-second rule bounds checkpoint cadence. It does not assert that a unit finishes
within30 seconds or invent sub-unit progress. If no further unit committed, the checkpoint
has the same resumable position; an interrupted unit still repeats under its idempotent unit
key. Every completed checkpointable unit is reported immediately, including small file
batches/pages. C2-8/9/10 own delivery of those producer checkpoints and replay effects.

Retain Q6 WAL synchronous=NORMAL and process-crash durability. Existing final-close FULL
WAL consolidation remains best-effort, with a pinned reader leaving replayable sidecars.
[SQLite documents application-crash durability and WAL checkpoint semantics](https://www.sqlite.org/pragma.html#pragma_synchronous).
A physical WAL flush alone was rejected in independent review: it neither checkpoints an
operation row nor improves Q6, and strict FULL would stall writes for an old reader and
incorrectly mark replayable shutdowns unclean.

Expand the existing Head-owned retention scheduler into operations maintenance. Preserve
its registered single-thread owner, acquisition rollback and retryable termination barrier.
Schedule the logical checkpoint at fixed rate, first at30 seconds and every30 thereafter;
retain hourly pruning as a separate scheduled task. Do not use fixed-delay30, which would
add callback runtime to each interval. JVM pauses and blocked/failing storage cannot be
made hard real-time: immediate per-unit commits remain independently process-crash durable.
Runtime checkpoint failures are logged at ERROR and retry next tick; Errors propagate.

Add one named `durable-operations-checkpoint` action immediately before INDEX_HALF_STEP
in HeadlessApp's existing sequence. It runs even if an earlier step throws, and a failed
operation checkpoint is reported under its own name. Store close stays after index drain,
which may report additional units. No second shutdown path or early store close.

## Per-item commit and acceptance

One C2-7 commit includes production, tests, design, evidence and B section10 row2 retirement;
push immediately. Root owns lifecycle edits and builds.

- Real SQLite: unit1 and unit2 visible independently before any timer; periodic checkpoint
  persists the latest cursor/counts and its timestamp. Same-count newer cursor survives;
  clock regression cannot move timestamp back. Terminal/interactive/accepted rows unchanged.
  SQL failure rolls back atomically; closed store refuses. Resume after reopening uses that row.
- Controlled scheduler clock: no checkpoint before30s, checkpoint at30s and60s without a new
  unit; hourly retention still fires. Failure logs and later tick retries; both tasks cancel,
  with retryable termination/registration ownership. Link callback to real row in a timed test.
- Real ordered shutdown: in-flight durable row, earlier failing step, named checkpoint before
  index, reopen and resume original row. Checkpoint failure names its own action, still runs
  index drain, and prevents clean receipt. Later drain checkpoints survive final store close.
- Focused/integrated affected-module Java tests/PMD/format, negative cadence and shutdown
  wiring mutations, independent review, source hashes and retained XML evidence.
- Hosted CI checked separately at pushed revision. Live ingest/reindex restart proof remains
  C2-8/9/10/11; this item establishes the mechanism without claiming those producer proofs.
