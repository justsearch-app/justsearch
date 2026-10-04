# Migration enumeration completeness

2026-09-14, based on518f13d62. The current empty-root early return never sets enumeration
done, so cutover waits forever before its switching deadline. Treating every empty result as
complete is unsafe: the loader and walk currently suppress malformed/inaccessible roots,
walk errors, interruption and short queue admission.

## Selected mechanism

The existing migration producer must complete a strict snapshot of its declared roots. Absent
or explicitly empty roots with no configured roots is a valid zero-file enumeration. Directory
and single-file roots are supported. Malformed/future registry formats, invalid entries,
missing/inaccessible declared roots, failed file attributes/walks, stopped/interrupted work and
short queue admission fail enumeration. Successful queue admission can remain durable after a
later failure; it never certifies complete source coverage. Root completion increments only
after its final batch is fully accepted. No new embedding waiver is introduced.

The watched-roots header/version check moves to configuration as one shared format authority
used by the existing app-services store and migration reader. This is shared validation of the
same authored file, not a second root registry or persisted representation. Strict migration
coverage supersedes the old tests expecting malformed roots and absent directories to become
successful empty/partial scans; those assertions must now require refusal and retain the file.

Keep the existing done flag for successful enumeration and add only a volatile failure cause
for its running instance. The existing cutover monitor consumes that cause before pause/done
checks, writes the existing FAILED migration state and preserves Blue. If that write fails, its
existing retry loop tries again; the latched failure prevents promotion throughout. A restart
reruns enumeration from the persisted migration state. No second durable failure marker or
state machine is needed. Interrupted shutdown keeps the interrupt flag and never sets done.

Verification must distinguish genuine zero-root completion, partial refusal, stopped work,
failed-state persistence retry, Blue retention and current-model attestation refusal. This item
remains under implementation and does not complete C2-10's recorded reindex producer.

## Review and verification (in progress)

Independent review found that following attributes with a no-follow walk can certify a symlink
root as empty. Root corrected both attribute reads to NOFOLLOW_LINKS: symbolic/special entries
refuse coverage, and two regressions exercise symbolic directory roots and nested file links.
This preserves the admission posture without enabling recursive link following or a cycle policy.

1600 first focused run executes85 cases, one fixture failure (mocked server lacked its model
latch), one POSIX permission skip; two PMD qualifiers fail. The root-authored fixture is corrected
and qualifiers removed. 1601 executes72 cases with one skip/no failures; PMD/Spotless pass.
1602 includes the no-follow correction:74 cases, three skips, no failures; PMD/Spotless pass.
The extra two skips are Windows symbolic-link creation privilege, not successful symlink proof.

1603 intentionally restores the empty-root early return, disables monitor failure consumption,
removes short-admission equality and omits NOFOLLOW. It executes11 selected/always-on cases:
exactly three failures at empty completion, short admission and paused failed-state persistence.
The two symlink tests are skipped by this Windows environment; their negative branches are not
claimed as executed. Positive sources are restored byte-for-byte from
`tmp/enumeration1602-KnowledgeServer.java` and `tmp/enumeration1602-KnowledgeServerMigrationOps.java`.
Full integrated1604 executes all four test tasks:4,050 cases/638 suites,24 skips
(21 existing and the three filesystem fixtures), no failures/errors. Six PMD tasks and
Spotless pass. Store-recoverability and operation/execution/register gates pass. Installed1605 executes all six recovery cases across two suites, no skips/failures,
including real-model migration. Quick1606 confirms ABSENT/no foreign runs/no inference orphan.
Linux-hosted permission/symlink proof remains required. The actual search-worker
matrix runs ubuntu-latest, irrespective of the stale preceding OS comment in ci.yml.

Logs/counts/XML and immutable positive snapshots live under the active worktree's
`tmp/migration-enumeration160*` and `tmp/enumeration160*`. Retain through final lane reconciliation
plus30 days, at least2026-10-14. No stage completion or hosted proof is claimed by these local runs.

Final review also found the new failure latch was consumed after best-effort state loading:
a null read could be classified as IDLE and terminate the monitor before recording FAILED.
The failure branch now precedes that read and classification. Focused1606 executes75 cases,
three environment skips, no failures/errors; PMD/Spotless pass. The persistence regression
covers both a normal first read and a transient null, with the migration paused and a failed
first state write. Negative1607 restores the incorrect ordering: eight selected/always-on cases, exactly one
failure in the transient-null case; the positive source is restored byte-for-byte from
`tmp/enumeration1606-KnowledgeServerMigrationOps.java`. Final affected-module1608 executes561 indexer cases/92 suites with15 skips and no
failures/errors; PMD/Spotless pass. The unchanged configuration272, app-services2,876 and
worker-core342 results from1604 remain applicable:4,051 represented cases/638 suites,
24 skips, zero failures. Only indexer-worker is claimed executed by1608. Final independent
review has no surviving code finding after the no-follow and early failure-consumption fixes.
It identifies direct pause-metadata assertions as unperformed; the unchanged manager copies
those fields, while executed regression proves paused failure persistence and Blue retention.
This final change affects failure consumption, not the normal installed-success path exercised
by1605; final-head failure-path proof is recorded separately from that installed run.


## Hosted reconciliation after reboot (2026-09-14)

[CI34830819900](https://github.com/justsearch-app/justsearch/actions/runs/34830819900)
tested pushed revision `13207a960993e11fe5dad1c15e446adbf8c1e2dd`: all13 jobs
succeeded, including the advisory integration job. The downloaded
`unit-test-attribution-search-worker` artifact identifies Ubuntu24. Its
MigrationEnumerationCompletenessTest XML has23 executed cases, zero skips/failures/errors,
including the POSIX unreadable-root case and both root/nested symbolic-link cases.
CutoverRestartEvidenceTest has6 executed cases, zero skips/failures/errors. This closes
the three Windows filesystem proof limits for the committed migration correction.

Accessible XML and attribution are retained in
`F:/justsearch-public/tmp/resume1611-hosted-artifact/`, under the artifact's
`modules/indexer-worker/build/test-results/test/` and `build/ci/` paths. Run metadata is
`F:/justsearch-public/tmp/resume1611-hosted-run.json`. Retain through final lane reconciliation
plus30 days, at least2026-10-14. Subsequent dirty closure/sealing changes are not covered.
