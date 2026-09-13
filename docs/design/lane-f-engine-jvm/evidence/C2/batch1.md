# C2 batch1 acceptance reconciliation

Status: historical batch1 proof at6a4059352 on2026-09-12, reopened September13
for R7's installed operations-row witness and R9's later schema reproof. C2 remains
open; [the correction batch](review-correction-september13.md) governs next work.
No owner input or approval is pending. Merge placement stays F/PR1.

R7's [installed816 operations-row proof](review-r7-installed-row.md) now passes,
including a failing installed substrate-bypass control. The six keyed C2-11
scenarios and R9's schema reconciliation are still open; this is not batch closure.

## Implemented boundary

C2-1 now supplies operations.db v2 (R5), process lifetime and recovery, jobs.db v16 and
compatible installed-store upgrade ownership. C2-11 supplies the day-one installed
PROCESSING replay and path-idempotent retry fixture. Its six keyed fault points
remain with C2-2 through C2-10 and the final C2-11 proof; they are not claimed here.
Actual boot acceptance and drain-time operation checkpoint writes require those
producer bindings. The foundation's lifetime tests already verify open/close order
and retention on failed drain.

September13 R9 reopens the migration/compatibility gate for14 → 15 → 16 and
operations1 → 2. The table below is historical proof; current results are in
[R9 reconciliation](review-r9-reconciliation.md). content_hash remains without a
unit-recovery consumer until C2-8.

## Historical required batch1 gates

| Gate | Result, revision and accessible evidence |
| --- | --- |
| Store register and schema policy | 567 passes6 catalog stores/45 durable authorities; new operations-db row and jobs15, not a reclassification of jobs. tmp/c2-batch1-store-567.txt |
| Migration/queue and recovery | 563 passes177 selected tests in28 suites, zero failures/errors/skips. Includes queue50, migrations22, provenance7, store16 with6 actual abrupt JVM halt points and future-schema preservation. tmp/c2-1-focused-563.txt and retained XML/counts |
| Rust updater | 536 passes85 tests, including installed-set successor checks and inherited-baseline expansion to the complete successor register. Rust inputs unchanged since that run. tmp/c2-1-updater-final-536.txt |
| Release descriptor | 539 passes12 tests including baseline-set identity checks. Release sources unchanged since539; tmp/c2-1-release-final-539.txt |
| Installed recovery | 564 passes the unskipped Windows OperationResumeE2ETest in31.611s; full command38s. Real post-death PROCESSING observation, one restart, fresh DONE retry and exactly1 document; registered cleanup portsClosed:true. [C2-11 evidence](C2-11.md) |
| Generated files | 566 passes all7 hermetic sets with --check --except notices. Notices previously pass548 with unchanged inputs. tmp/c2-batch1-regen-566.txt |
| Integrated compile/quality/unit boundary | 565 build -PskipErrorProneTests=false -PtestParallelism=1 --max-workers=4 passes11m35s,366 actionable tasks:25 executed/15 cache/326 up-to-date. tmp/c2-batch1-integrated-565.txt |

The complete retained post565 unit XML represents9809 test cases across1604 suites,
zero failures/errors and25 skips under normal repository test selection. This is
not a claim that every case executed again: Gradle reused unchanged inputs,
including app-engine's207 passing cases from559. The snapshot also contains other
source sets, including the separately run installed fixture and older schema/load/
system outputs; it does not turn those into a new full integration/stress campaign.
C2 batch4's full stress and both supervisor-adapter runs remain required.

Post565 XML: tmp/c2-batch1-suite-565-xml. Per-file timestamp, source set, result count
and SHA256: tmp/c2-batch1-suite-565-counts.json. Raw logs, failure snapshots and
installed data stay in this held worktree through lane acceptance plus30 days;
export before releasing it. Earlier reds remain accessible in C2-1's evidence.
Two unrelated30s timeouts in559 passed563 unchanged at1.718s/2.008s and the final
integrated565 run is green with serialized test tasks. No timeout, assertion,
architecture baseline or corruption rule was relaxed.

Commits396d16e22 and6a4059352 were pushed immediately after creation. They follow
WIP479a19dd7; the current full integrated result covers their source plus docs-only
C2-2 planning edits. No hosted-C2 result is claimed by this local batch closure.
