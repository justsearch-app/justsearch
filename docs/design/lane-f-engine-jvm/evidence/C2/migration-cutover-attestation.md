# Migration cutover attestation barrier

2026-09-14, based on pushed043b05bc0. Installed1568 migration fixture
writer-junit-957c6d5f-a488-49f6-a71c-ca60be78f96f reaches a FAILED migration:
the COMPLETE commit lacks embedding_model_sha256. The successor starts with a fresh
COMPATIBLE Green. Persisted-root replay indexes A before embedding readiness; the later
migration enumeration sees unchanged source and skips extraction. Backfill subsequently
completes, but the idle-loop fresh-stamp reconciliation races after the final commit.

The old cutover barrier ignored checkRebuildCompletion's result. That method only
transitions REBUILDING, so it cannot earn the first stamp for fresh COMPATIBLE Green.
The minimum correction reuses reconcileStampEvidence and fingerprintToStamp before the
COMPLETE commit. It requires the captured current fingerprint to be offered by the existing
controller; no new state, marker, forced-source pass or evidence waiver is necessary.
Reconciliation stays outside the IO-free overlay. Pending or unreadable counts, absent
success evidence and failed rebuilds cannot pass. Already certified indexes still pass.
Keyword-only behavior and post-commit metadata verification are preserved.

## Verification

-1573 runs the new regressions against the old barrier:38 cases/14 suites, exactly three
expected failures (missing fresh stamp, missing success evidence, failed rebuild).
-1574 with the fix:38 cases/14 suites, no failures/errors/skips. PMD flags three old fully
qualified references made redundant by the test imports; those references are shortened.
-1575 integrated tests pass:528 indexer cases EXECUTED plus342 unchanged worker-core
cases UP-TO-DATE,18 existing skips, zero test failures/errors. PMD still finds the same
three references because the first edit missed method-reference syntax; no validation is waived.
-1576 final PMD (four tasks) and whole Spotless pass; both test tasks are UP-TO-DATE
against the successful suites (870 represented cases/174 suites,18 existing skips).
Documentation regeneration, canonical links and runtime-config checks pass.
-1579 adds the independent review's exact-model mismatch negative and real REBUILDING
success positive:40 cases/14 suites EXECUTED, no skips/failures/errors, PMD/Spotless pass.
Installed1578 executes all six scenarios:five pass, one fails, no skips. Migration passes
with the real locally resolvable model; writer, lock-ingest, processing and operation also
pass. Lock-boot reproduces the hosted initial-discovery failure, so no full-suite pass is
claimed. quick1579 reports ABSENT, no foreign runs or inference orphan.

Independent review accepts the barrier mechanism and identifies an inherited follow-on:
the corruption-recovery zero-evidence waiver is boot-local. A resumed empty Green may
re-derive it only from exact durable corruption-recovery provenance and a trustworthy
zero document count. Provenance alone cannot authorize stamping a nonempty Green after
a model change. This is active repair work in the next per-item cut; the old barrier also
failed postcommit verification in that case, so it is not a new permissive regression.

Logs, XML and task execution counts are under
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/migration-attestation*.
Retain through final lane reconciliation plus30 days, at least2026-10-14.
The full installed suite and hosted initial-discovery fault remain open until separately
proved; this correction does not claim C2 completion.
