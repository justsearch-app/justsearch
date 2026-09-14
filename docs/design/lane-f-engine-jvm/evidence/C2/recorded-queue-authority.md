# C2-9b.3a recorded queue authority

## Scope and current proof

Base449f56a069dfc28aabd410a809b7a0be4bee0d57, Windows x64, active worktree
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify. This cut supplies only
constructor-bound permission checks in SqliteJobQueue. Generation-ready lifecycle,
actual authority activation and the recorded producer remain C2-9b.3b/.3c under
[the owning plan](ingestion-authorization-recovery.md#c2-9b3-queue-fence-and-startup-sequence-2026-09-14).

All existing no-owner constructors deny recorded membership from queue open.
PENDING filtering checks the actual recorded key per candidate before the batch
limit; PROCESSING-to-PENDING recovery uses the same check. Null epoch remains
legacy, even with a scan key. False/runtime failure denies, while fatal errors
propagate through the existing rollback owner. Issued claim completion/return
retains its existing semantics. No schema, permission table or new lifecycle state.

Independent production refute found an initial design error: permission before
stopped orphan accounting would strand a FAILED/CANCELLED walk after crash. Root
verified the issue and moved permission after the stopped-member skip branch,
while retaining active-owner exclusion before both. The same reviewer cleared
the corrected constructor/filter/recovery paths. Administrative SKIPPED coverage
is not permission to execute. The amendment and regression obligation are in the
owning plan; this was one substantive production correction round.

## Verification record

- 1659 compiled the initial queue diff but PMD rejected a redundant java.util
  qualifier. Root removed only that qualifier.
- 1660 compiled the corrected stopped-orphan implementation and passed indexer PMD.
  This main-only run did not compile the concurrently authored test file or run tests.
- Three governance gates, store recovery and canonical docs checks passed in1660.
- Root inspected the test draft and required normalized Windows SQL paths,
  independent aged/unconditional stopped-orphan fixtures, deterministic aged
  eligibility, a same-queue later-permission test, and within-batch per-unit checks.
  The resulting focused/integrated execution and negative controls are recorded below.

Logs are tmp/1659.txt, tmp/1660.txt, tmp/1660-gates.txt and tmp/1660-docs.txt.
Retain these and subsequent counts/copied XML through final lane reconciliation
plus30 days, at least2026-10-14. Independent review/source inspection is not an
executed claim or startup proof.


1661 executed65 focused cases/8 suites, zero failures/errors/skips; indexer PMD and
formatting passed. Independent test refute found no weakened assertion or material
wrong-reason pass in the new authority tests or four explicit-authority fixture
updates. It noted that false/throwing recovery checks assert zero recovered while
the separate no-owner case also pins retained PROCESSING state.

Negative1662 bypassed the predicate and executed17 cases with nine expected
failures. Isolated1663 gated stopped accounting and executed18 cases with exactly
two expected failures (new stopped authority matrix and existing orphan seal).
Isolated1664 applied the batch limit before eligibility and executed17 cases with
exactly one expected failure in denied-oldest fairness. Root restored queue bytes
exactly to positive1661 before the final integrated1665 run. These tests use real
SQLite; grouped loops test both stopped outcomes and both recovery entry points
without claiming each combination as a separate JUnit case.


1665 executed the full indexer-worker suite:598 cases/95 suites,15 existing skips,
zero failures/errors. Indexer PMD and formatting passed. The same command's
Worker-core suite executed342 cases/84 suites with six skips and one inherited
long-document CPU test timeout; it is not an integrated green claim. The isolated
reproduction and separate method-budget correction are recorded in
[long-doc-test-budget.md](long-doc-test-budget.md). Full corrected Worker-core1668
is running at this queue checkpoint. Queue production is byte-identical to1661;
the original bytes are retained as tmp/1662-queue-original.bin.

C2-9b.3a queue behavior is implemented and locally verified. Startup activation,
per-generation ownership and the actual producer remain .3b/.3c. No recorded walk
can execute through the default constructor before those owners are attached.
Current logs/counts and copied XML are tmp/1661 through tmp/1665 with .txt,
-counts.json and -xml suffixes. Hosted proof remains required after push.
