# Resumed empty corruption-recovery attestation

2026-09-14, based on8b87da3f9 (subsequent050d9c85e changes only fixture evidence).
Independent cutover review found the corruption-recovery stamp waiver was a boot-local
boolean. A restart after creating Green lost it; the prior final metadata check already
refused the resulting unstamped Green, so this is an inherited recovery defect.

At controller initialization, re-derive this existing waiver only for the active MIGRATING
or SWITCHING building generation whose resolved path equals the actually opened Green,
whose persisted source is exactly corrupt_index_rebuild, and whose authoritative document
count is exactly zero. Unknown/unreadable provenance, a failed/nonbuilding generation,
path mismatch, nonempty or unreadable Green cannot earn it. A nonempty generation could
contain vectors from a model loaded before restart, so source alone is not sufficient.
The same-boot recovery flag retains its existing semantics; no new store or stamp waiver
is added. A matching existing committed fingerprint already supplies its own evidence.

## Verification

The tests use a real generation manager, create the migration, reopen a new manager from
its persisted state/manifest, initialize the actual controller, and invoke the cutover
barrier. Empty corruption recovery passes; normal migration, nonempty/unreadable Green
and a different opened generation refuse. Forced-rebuild transition in the refusal cases
prevents a wrong-reason pass from merely remaining BLOCKED_LEGACY.

-1580 original boot-only implementation:45 cases/14 suites, exactly one expected failure
for resumed empty corruption recovery.
-1581 initial fix:45 cases pass, PMD/Spotless pass. Subsequent review strengthens the
nonempty/unreadable cases with the real forced-rebuild transition described above.
-1582 removes only the authoritative empty witness:45 cases, exactly two expected failures
(nonempty and unreadable Green). The production source is restored byte-for-byte.
-1583 full indexer/core test boundary and PMD/Spotless pass; per-task execution/reuse and
case totals are preserved in tmp/corruption-resume1583-counts.json.

Logs/XML/counts are accessible under
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/corruption-resume*.
Retain through final lane reconciliation plus30 days, at least2026-10-14. This is local
restart-state proof, not an installed crash-between-generation-create-and-cutover test.
The separate zero-root enumeration and installed lock-boot startup defects remain active
repair items. C2 and the later lane stages remain open.
