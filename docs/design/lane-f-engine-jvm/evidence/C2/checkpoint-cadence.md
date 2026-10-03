# C2-7 checkpoint cadence and ordered shutdown

2026-09-14. Base347187073 plus the source hashes in [the manifest](checkpoint-cadence.json).
[Mechanism and trade-off](C2-7-plan.md). C2 remains open.

## Implemented and locally verified

Each reported unit commits immediately through the existing record handle. The registered
operations maintenance owner also checkpoints every open durable row at a30-second fixed-rate
cadence. Its one SQL statement preserves the current cursor and counts while advancing updated_at
without clock regression. Same-position checkpoints are a durable heartbeat when no unit advanced;
they do not reduce replay work or broaden Q6's process-crash guarantee. No second progress buffer,
cursor, timer-written snapshot or timestamp column was added. Hourly retention shares the existing
owner, and both tasks have cancellation and retryable termination coverage.

HeadlessApp's named durable-operations-checkpoint step runs immediately before index-half,
including after an earlier step fails. It leaves the store open for final index-drain unit writes;
operations-store still closes afterward. A logical checkpoint failure names its own action in an
unclean receipt while later teardown runs. WAL NORMAL and best-effort physical close are unchanged.

Focused1511 passes32 represented cases/11 suites. Integrated1513 executes all five affected module
test tasks: app-api, app-observability, app-services, ui and app-launcher, representing4,963 cases.
Review requested explicit coverage of the COMPLETE_WITH_GAPS arm. Final1514 adds that case and
passes4,964 cases/769 suites, four existing skips, zero failures/errors. app-observability executes
again; the other four unchanged-source module results are reused from1513. Relevant PMD, whole-tree
spotlessCheck and UI integration compilation pass. Existing compiler/deprecation/native-library
advisories are retained in raw logs; no warning suppression was added.

The real SQLite tests prove independent visibility after each unit, same-count newer cursor
preservation, monotonic checkpoint timestamp, untouched accepted/interactive/terminal rows,
COMPLETE_WITH_GAPS preservation, a pinned reader without FULL-checkpoint stalls, atomic rollback
on a failing SQL trigger, and closed-store refusal. Controlled scheduler time proves no checkpoint
at29 seconds and real row updates at30 and60 seconds, plus hourly retention, ERROR/retry behavior,
fatal Error propagation, both cancellations and retryable owner termination.

The combined production shutdown test starts a real durable row, injects an earlier manifest
failure, verifies the checkpoint through an independent JDBC connection inside index close,
commits another unit during drain, reopens, and resumes the same row to completion. A separate
failure test asserts the checkpoint step's own error and subsequent index/store closure.
These are lifecycle mechanism proofs, not the forthcoming real ingestion eligibility policy.

## Wrong-reason controls and review

Negative1512 removes both production checkpoint callbacks while retaining their schedule and
named step. It fails6 of17 represented cases: timer invocation/retry/real-row timestamp and both
shutdown assertions. The real shutdown case fails before index-drain work because the prior
checkpoint timestamp was never committed. Both callbacks were restored exactly before1513.
This distinguishes actual logical checkpoint delivery from task registration or final-close effects.

Independent review rejected physical WAL consolidation as the cadence proof and identified its
pinned-reader stall/false-unclean-shutdown regressions. The final design uses atomic logical row
checkpoints and explicitly records same-position timestamp semantics. The reviewer-requested gaps
case is included. Final evidence audit is recorded in the manifest.

## Evidence access and remaining work

Raw logs, counts and copied JUnit XML directories are under the active worktree tmp, with exact
paths/hashes/task reuse in the manifest. Retain them through lane acceptance plus30 days and export
before worktree release. Hosted proof is tracked separately after push. C2-8/9/10/11 still own actual
ingest/reindex unit delivery, resume eligibility, idempotent effects and installed forced-kill proof;
live/model, final-head and later D1/D2/E/F obligations remain. No stage or merge placement changed.
