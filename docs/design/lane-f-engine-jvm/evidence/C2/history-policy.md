# C2-4 acceptance-time history policy

Implemented 2026-09-13 from7fbc3df8d. First acceptance persists the dispatcher’s
existing audit/undo declaration as NONE/STANDARD/UNDOABLE/UNDO, plus its original
provenance instant. Ordinary, frozen prepared and explicitly audited direct
producer requests share the same store transaction. The stable-key copy and frozen
execution retain the mode. An existing row wins even if a retry changes the current
policy; changed public input still raises OPERATION_KEY_REUSED. No intent token or
handler content is added to history metadata.

Schema v4 adds two columns. Legacy v1-v3 rows remain NONE because their original
audit declaration cannot be reconstructed safely. New audited acceptance requires
provenance and an operation reference. Migration preserves rows, frozen payloads,
sequence and fence; the injected pre-commit failure rolls back DDL and version.
The old source-compatible acceptance overloads retain NONE within the store owner,
not through interface-default lifecycle forwarding. The dispatcher supplies the
mode; background producers with their own ledger retain NONE.955’s MemoryAdmission
must explicitly request STANDARD, as recorded in the consumer contract.

## Verification and corrections

- Initial1113 executes191 cases with two failures: a semicolon inside the frozen
  fixture’s comment broke its simple SQL splitter, and interface-default acceptance
  forwarding violated the launcher’s lifecycle-owner rule. Fix the fixture and move
  forwarding to the concrete store’s internal transaction. Keep the rule unchanged.
- Negative1114 removes only the history mode from the runner’s stable-key copy.
  Seventeen represented cases execute; six intended failures detect NONE in place
  of STANDARD/UNDOABLE/UNDO across ordinary dispatch, prepared restart and the runner.
  Restore the exact saved source bytes before the final run.
- Final1115 executes198 cases across28 suites with zero failures/errors/skips:
  observability49, services114, launcher35. PMD and UI integration compilation pass.
  Format1116 passes. These are real SQLite transaction/reopen/rollback and dispatch
  tests; existing completion subscription and preparation regressions also execute.
- Register1118 unexpectedly passes while code declares4 and the register still3.
  The old source-constant check only covered jobs. Negative1119 adds two failing
  operations authority tests; after extending the existing stripped-source check,
  negative1120 rejects the actual3/4 mismatch. The final v4 register retains readable
  versions1/2/3 and the frozen migration fixture. Register1121 passes77 assertions;
  register1122 passes with45 durable authorities and zero unregistered writes.
- Release1123 passes12 descriptor cases. Rust1124 (`cargo test --lib --locked`,
  modules/shell/src-tauri) passes85 cases, zero failed/ignored. Upgrade1125 executes
  13 Java HTTP reconciliation cases, zero failures/errors/skips, against this register.
- Root’s separate skeptical reread checked first-policy wins, migration rollback,
  source authority stripping, producer defaults and preserved architecture tests.
  No remaining defect was found in this source cut. No independent model review or
  completed batch review is claimed.

[Exact commands, revisions, counts and hashes](history-policy-verification.json)
include the failed and final logs/XML. Raw prefixes are `tmp/c2-4-history1113`,
`tmp/c2-4-history-negative1114`, `tmp/c2-4-history1115`,
`tmp/c2-4-history-format1116`, `tmp/c2-4-history-checks1117`,
`tmp/c2-4-history-register-negative1118/1119/1120`,
`tmp/c2-4-history-register1121/1122`, `tmp/c2-4-history-release1123`,
`tmp/c2-4-history-rust1124` and `tmp/c2-4-history-upgrade1125`.
All are in the lane worktree; retain until lane acceptance plus30days and export
before releasing it. The verification JSON enumerates actual paths, not brace aliases.

## Remaining acceptance

Recent history still uses its current in-memory store. Bounded committed-row reads,
source projection acknowledgement and retry ordering, ledger fan-in, startup replay
before pruning and SSE snapshot/replay remain open in C2-4. No new projection marker
or replay consumer is active in this cut. Installed-v4 recovery, coherent full-suite
and hosted proof remain required; neither historical hosted-v2 success nor these
local tests substitutes for them. Last observed hosted head7fbc3df8d had CI34752086954
pending and CLA34752084973 queued. C2-4, batch2 and the lane remain open; F/PR1 placement
is unchanged.
