# C2-8c: durable queue admission revisions

2026-09-14; implementation against a5f1a0fa8. Locally verified; this is not
root ingestion or scoped boot recovery acceptance. [Source and run manifest](admission-revision.json).

## Change and reason

Jobs schema v17 adds/backfills one opaque unit_revision per current admission. The existing
queue owner mints it inside both enqueue transactions. Polling returns scan_id and revision;
exact claim validation checks durable state, scan and revision as well as live object identity.
Retries, deferral and crash recovery preserve the admission identity. Path plus committed
content hash remains the effect key; the revision is not authority or an input snapshot.

Independent source review found that reenqueue contradicted the existing carry-forward
contract by erasing scan, collection and provenance. The same SQL transaction now preserves
those fields when absent and accepts explicit new provenance, while still resetting the
retry ladder and hash and minting a new revision. A real SQLite regression covers the defect.
No parallel admission registry or new writer was introduced.

The existing schema ladder owns the transactional migration and backup. The jobs register
advances format/version/readable predecessor only; class, owner and corruption policy stay
as declared. The six current-version assertions in migration fixtures advance from16 to17; their
preservation and future-version refusal assertions remain intact.

## Verification status

- Store register gate1529 passes; its Node suite passes77 assertions.
- Canonical documentation regeneration and link/runtime-matrix checks pass without derived drift.
- Java migration, replacement, retry/recovery, forged/stale claims and negative-control proof
  passed focused1530 (40 cases, four suites, zero failures/errors/skips). Negative1531 removes
  the durable revision comparison and breaks retry collection preservation; exactly those two
  assertions fail among seven tests. Source is restored byte-for-byte. Independent review
  confirms the proof, including genuine reopen and v17 rollback boundaries.
- Integrated1532 executed all three affected suites (2,186 cases, 424 suites, 20 existing skips)
  and failed only two stale current-version assertions in switch-buffer/provenance fixtures.
  Their expected current version is corrected to17; historical rollback expectations remain16.
  Final1533 passes all2,186 represented cases/424 suites with20 existing skips and zero
  failures/errors. Indexer-worker test/pmdTest execute; worker-core and worker-services
  tests reuse their executed passing1532 results. Main sources are unchanged between runs.
  All six PMD tasks and whole-repository Spotless checks pass, with exact reuse in the manifest.
- Existing StaleSnapshotResolver Javadoc and deprecated API/JDK warnings remain visible in
  retained logs; no warning suppression or test skip was introduced.

Raw files live under active lane-f-pr1-verify/tmp and are retained through2026-10-14 or lane
acceptance plus30 days, whichever is later. No live/model/installed or final-head proof follows
from this foundation. The [owning ingestion plan](C2-8-ingestion-plan.md) retains C2-8d/C2-9.
