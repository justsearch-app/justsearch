# Queue schema V24 merge evidence

Author: builder Codex session `01a1562b-b2e9-7510-832f-04ea6ad6da28`.

Merge commit: `761fd3d34` (`Merge origin/main into worktree-queue-scale`).
Main parent: `f6d92d768f86d778ea805012b176c3349705018c`.
Only the merge was committed. The compatibility repair, tests, documentation and evidence
below remain uncommitted. No acceptance record was authored or changed.

| Source schema | Meaning and upgrade |
| --- | --- |
| V13–V22 | Existing ladder, then main V23, then V24 |
| Main V23 | Monotonic switch-buffer admission count and restart fencing, unchanged |
| Unreleased queue-branch V23 | Existing count objects retained; V24 ensures the missing main admission objects |
| V24 | Transactional job counts and claim/readiness indexes; current target |

Main's `case 23` block was compared with the main parent and is byte-identical.
V24 calls that idempotent step before creating its own derived objects. It retains a
nonzero admission counter rather than resetting it. The unshipped branch V23 needs
that setup because its number collides with main's V23; the added compatibility check
proves these development databases can upgrade without deleting saved jobs.

The store register retains main's witness, triage, encryption and corruption-policy
entries. Its current version/format is V24 and readable legacy versions include V23.
Historical signed V23 execution receipts are retained verbatim; current migration
evidence has V24 names.

## Executed checks

- Queue/default suites: `:modules:indexer-worker:test --tests 'io.justsearch.indexerworker.queue.*'`
  and `:modules:worker-services:test --tests '*IndexingLoopClaimBudgetTest'`, with `--continue`:
  **387 + 1 tests, zero failures, zero skips**. PMD main/test and module Spotless passed
  in the same Gradle invocation (43 seconds, excluding slot wait).
- Related Vitest: nine queue, rail, Resource, TaskList and label-resolution files,
  `--maxWorkers=4`: **100 tests passed**, 1.82 seconds. Lint and typecheck exited 0.
- `node scripts/ci/check-store-recoverability.mjs`: exited 0, 55 durable authorities,
  28 corruption policies.
- Official [unit evidence](queue-scale-v24-unit.json): all **14 candidate cases passed**.
  Against merge commit `761fd3d34`, the unreleased-V23 case fails an assertion because
  `switch_buffer_admissions` is missing. The other 13 preservation cases pass before
  and after. The evidence producer exits 1 for this intentionally mixed repair/preservation
  run; there is no candidate failure. The parameterized matrix names all eleven cases
  explicitly so the evidence runner can select them.
- Official [million-row evidence](queue-scale-v24-million-migration.json): exited 0.
  Raw JUnit output is retained in [the XML report](queue-scale-v24-migration-output.xml).

| One-million-row measurement | Observed |
| --- | --- |
| Generate F1 at V13, once | 4.186 s |
| V13 → V24 migration transaction | 2.772 s |
| V13 → V24 idempotent reconciliation/reopen | 0.300 s |
| Main V23 → V24 migration transaction | 0.767 s |
| Main V23 → V24 idempotent reconciliation/reopen | 0.322 s |

The test generates one row set in one database. After the first upgrade it removes only
V24's derived table, triggers and indexes, retaining main's actual V23 objects and the
same saved rows, and reuses it as the main-V23 source. For each source it first crashes
a separate JVM after V24 DDL, inside the real migration transaction (exit 74), verifies
the unchanged source version and saved-field digest, then completes the migration.
All pre-existing job columns are streamed into a framed SHA-256 digest. Counts, bytes,
ready counts, strict/non-strict status answers and the same ordered 16 claims match
direct SQL. Main's admission counter remains 41 through upgrade and reopen.

The large-file measurements use the real `SqliteQueueMigrationOps.runMigrations`
transaction with a no-op backup callback. An already-migrated connection is attached to
the real queue for public status/claim checks. This deliberately avoids normal open's
defensive compatibility copy and pre-migration backup under the one-large-file budget;
it does **not** claim to time the complete public startup/copy/backup path. Default
small-fixture tests exercise that public lifecycle for every source V13–V23, and retain
the existing backup and rollback assertions.

For older-binary refusal, the test compiles main's unmodified `SqliteSchema.java` and
`SqliteQueueMigrationOps.java`, loads them ahead of the candidate classes in a separate
JVM, and calls that older migration entry point on the same V24 database. It refuses
before its backup callback with the exact existing message:

```text
Unsupported jobs database schema version 24 (this binary supports through 23)
```

To reproduce the tagged check, create a shared source clone at the main parent, set
`QUEUE_SCALE_MAIN_ROOT` to it, and select `*QueueScaleV24MigrationEvidenceTest` with
`:modules:indexer-worker:queueScaleEvidence`. All Gradle calls in this run used
`js-gradle-slot` with its default wait; official evidence runners ran whole under that slot.

## Test-intent changes

Changed entries: **1, 3, 5, 15, 23, 24, 26, 27**. Forward-looking agent choices now
say V24 while retaining main's V23 meaning. Entry 3 updates the target-pin inventory;
entry 15 retains the unchanged random-count tests and moves migration coverage to the
new entries; entry 23 records opt-in selection of the new harness; entry 24 distinguishes
historical V23 execution from current V24 evidence; entry 27 records the shared fixture
schema helper and its new callers. Entries **38–40** record V24 preservation, the
unreleased-V23 repair and the single-file million-row migration instrumentation.

| Changed assertion or fixture | Old → new; retained coverage |
| --- | --- |
| `JobQueueMigrationTest.migratesV11ToCurrentPreservingQueueAndIdentityRowsAndRefusesTheNextVersion` | Literal target 23 → 24; saved queue/identity fields, import schema and next-version refusal unchanged. Classified Obsolete for the literal version pin, not an unchanged oracle. |
| `JobCountsMigrationTest.failedV23MigrationRollsBackTableTriggersIndexesCountsAndVersion` | Name/step V23 → V24; source and rollback version 22 → 23; absence of count objects now belongs to V24. The table/trigger/three-index rollback checks, `PendingBytes(123,1)` and ready count 2 remain. New branch instrumentation relative to main, explicitly versioned in entry 38. |
| `QueueScaleFixtures` | Shared schema builder supports source V13–V23; original F1/F2 V13/V22 fixture choices remain supported. No original row-generation oracle changed. |

Tests added: the eleven-version preservation matrix and unreleased-V23 regression in
`JobCountsMigrationTest` (durable default tests); `QueueScaleV24MigrationEvidenceTest`
(durable tagged Q12/Q13 evidence).

## Cleanup and gate

The new million-row database and sidecars were deleted in `finally`. The shared main
source clone and producer-owned before worktrees were removed. Cleanup also removed
20 pre-existing generated fixture backups (5,790,851,072 bytes); neither queue-scale
build directory retains a generated database or backup. No Engine was started and
`~/.config/justsearch` is absent.

The final test-intent CLI reports only `test-intent/acceptance-stale`; all execution and
coverage checks pass. Existing acceptance records are byte-identical. Independent
acceptance remains required.
