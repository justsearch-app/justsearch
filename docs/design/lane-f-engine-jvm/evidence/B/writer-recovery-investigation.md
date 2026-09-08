# Closed-writer recovery investigation (2026-09-08)

Read-only investigation at `d8cb63f8f`, independently traced and re-read by the
orchestrator. This extends the preserved failure in
[integrated-verification.md](integrated-verification.md); no recovery fix or new
passing stress result is claimed.

## Detection and replay

The failing commit is classified in `CommitOps.java`:100-113. `IndexingLoop.java`:
708-726 catches the failure and continues using the same runtime. The current
health probe can still see a readable searcher and an accessible queue; it does
not establish that the writer remains usable. Detection must inspect the existing
runtime's writer state. A generic `LOCKED` exception alone is insufficient grounds
to terminate the Engine: a recoverable lock error and an irrecoverably closed
writer are different conditions.

Successfully written documents do not become DONE before commit.
`JobBatchWriter.java`:132-163 writes and enqueues a transition in memory;
`IngestionOutcomeJournal.java`:86-93 and `IndexingLoop.java`:708-724 place DONE
after successful commit. A failed commit leaves those durable queue rows
PROCESSING. `KnowledgeServer.java`:567 invokes the startup reset implemented by
`SqliteJobQueue.java`:1260-1277.
Failed writes attempt a durable PENDING/backoff transition separately. If that
queue transaction fails, `IngestionOutcomeJournal.java`:217-238 retains PROCESSING
for startup recovery. Both states need a replay proof; this trace does not
substitute for running it.

## Recovery alternatives

`KnowledgeServer.swapRuntime()` is an existing component-reopen seam, but closes
the old runtime before opening its replacement (`KnowledgeServer.java`:1261-1283).
Failed open therefore leaves closed resources in the holder, despite the stronger
comment at :1252-1254. Service reconstruction starts the new loop before closing
the old service (:1229-1241). It also does not perform startup's immediate reset of
PROCESSING jobs. Reusing this path needs failed-open, admission, loop ownership
and replay guarantees; it is not a small call-site fix.

Whole-Engine fault escalation is the narrower direction to prove first. Existing
exit code 1 is a budgeted transient fault (`EngineExit.java`:43-58, :86-91), and
the default uncaught handler already exits with it (`HeadlessApp.java`:891-902).
Both supervisors already observe process exit and restart under their budget
(`lib.rs`:1091-1093; `supervisor.rs`:610-652, :807-832;
`dev-runner.cjs`:2719-2758, :2793-2815).
The missing connection is internal writer-failure detection to the Engine's fault
owner; `EngineRoot` currently supplies start and close, not a fatal callback.
This trades a full application outage for reusing established restart and startup
replay. Unsupervised launches would exit and remain stopped, as their shape implies.

## Proof still required

Prove one escalation for a genuinely unusable writer, with no escalation for an
ordinary recoverable lock or intentional close. Exercise the real process exit,
ordered shutdown hook, both supervisor bindings and crash-budget accounting.
After restart, demonstrate replay of accepted-but-uncommitted jobs and both
failed-write queue outcomes. A manually reopened test harness proves only reopening/replay, not automatic
production recovery. Preserve the file-contention assertion and its original red.

Do not import D1's live replacement merely to make this test pass. If whole-Engine
recovery cannot meet the lane's existing property, apply section 17.8 before
changing the stage cut. No new cross-process artifact or general fault framework
is approved by this investigation.

The stress test's historical scheduler explanation (`EngineFileLockContentionTest.java`:
57-70) is now qualified as a hypothesis about the earlier runs, with the proven
closed-writer incident distinguished explicitly. This comment-only correction
changes neither the tag nor the assertion and does not establish recovery.
