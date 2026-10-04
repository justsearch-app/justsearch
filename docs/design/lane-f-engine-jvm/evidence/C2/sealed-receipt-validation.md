# C2-8d.3a: strict sealed receipt projection

Implemented on d18d140a3 in the lane-f-pr1-verify worktree, Windows x64/Temurin25.
SqliteIngestionWalkOps remains the sole receipt schema/writer. JobQueue exposes a
non-durable typed view with the exact stored UTF-8 digest, revision, historical counts,
current failure count and enumeration outcome. Missing progress is a gap; present
unsealed progress is not ready. No schema migration, second writer or raw JSON port.

Strict reads reject malformed/duplicate/unknown fields, coercion, mismatched row
metadata, unsupported versions and invalid bounded sorted failure hashes. Sorted
repeated hashes remain valid v1 data. The parser bounds decoding at16KiB. Reads,
duplicate seals and acknowledgements share validation. Jobs/ledger/progress retention
validates exactly its candidates within the existing transaction and rolls back on a gap.

Independent review found that SQLite INTEGER affinity still accepts REAL values.
The final reader uses getObject and accepts only Integer/Long for every numeric walk
field; required nulls and invalid record invariants become gaps. Existing app-written
v1 rows remain compatible. No corruption repair is inferred from a successful cast.

## Verification

-1689:41 cases/4 suites, no skips/failures:9 receipt,12 seal,14 notification,6 automatic
 guardrails. Main/test PMD and format pass.
-1690: parser-bypass negative,7 cases/2 suites, exactly1 expected failure at the first
 missing-field typed-read assertion. The loop stops there; this is not a negative proof
 for every later corruption variant. Production restored byte-exact.
-1691: retention-validation bypass,9 cases/2 suites, exactly3 expected cleanup failures
 for old jobs, ledger and progress-only pruning. Production restored byte-exact.
-1692: full indexer617/15 skips plus Worker-core342/6 skips:959 cases/181 suites,
 no failures/errors. Four PMD tasks/format pass. This precedes the final numeric-read fix.
-1693:10 receipt cases plus6 automatic guardrails pass. The added mixed ordinary-job
 deletion followed by invalid orphan progress proves rollback after DELETE, not just
 prevalidation before DELETE.
-1694: commit-instead-of-rollback negative,7 cases, exactly1 expected failure: the
 ordinary job disappeared (expected1, observed0). Production restored byte-exact.
-1695: restores/reuses1693's matching16-case cache; not another test execution.
-1696: seven real SQLite fractional-column corruptions produce seven expected pre-fix
 failures (13 cases including6 passing guardrails). Each fixture verifies typeof=real.
-1697: corrected numeric reader passes38 focused/adjacent cases, no skips/failures.
-1698: final full indexer625 cases/97 suites,15 existing skips, no failures/errors;
 main/test PMD and Spotless pass. Worker-core's unchanged JobQueue source retains1692's
 full342-case proof. The final two-module represented result is967 cases with21 skips,
 not967 new executions in1698.

Root independently checked XML counts and the reviewer findings. Final independent
read-only review found no surviving correctness finding after numeric-read and rollback
corrections. Operation/execution/register-resolution gates and store recoverability pass
in1692 without baseline changes. Hosted proof now passes at759775f6f84cb386e930d7e31bf04e5007d8ef87: all13 jobs in CI34863470535 and CLA34863468188. Saved metadata: tmp/1705-hosted-ci.json.
[Hosted CI](https://github.com/justsearch-app/justsearch/actions/runs/34863470535).

Exact commands/revisions/task counts are in tmp/1689-counts.json through
1698-counts.json; raw logs use the same numeric .txt prefix and copied XML the -xml/
suffix. All are accessible under F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/.
Negative-control original bytes are1690-walk-original.bin,1691-queue-original.bin,
and1694-queue-original.bin. Retain through final lane reconciliation plus30 days,
at least2026-10-14. Git retains this summary; hashes alone are not the evidence.

## Remaining

This receipt projection completes d.3a only. Stable coordinator9b.3b.3, actual bounded
producer adapters d.3b, outer acknowledgement/maintenance/drain integration, and all
other open C2 acceptance remain required. Stage F remains the merge point.
