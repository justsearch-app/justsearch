# R9: reconcile scope, schema and current proof

September13, base41d0ab86d plus this item. R9's item gate passes at835; final
batch verification and independent review remain required.

C2-1 is reopened at operations schema2 and jobs schema16. The current checklist
names14-to15 content_hash and15-to16 switch-buffer replacement identity, plus R5's
operations1-to2 SQL-bound rebuild. JobQueueMigrationTest covers14-to-current and
V15 failure; SwitchBufferVersionTest covers V15 payload preservation, distinct
stable revisions after reopen, and rollback of V16 DDL plus version. R5's
OperationRetentionTest covers both successful and refused v1-to-v2 migrations.
The optional jobs.content_hash still has no unit-recovery producer/consumer;
C2-8 owns it. Rust/store/release compatibility proof is refreshed for current
register versions. Older batch1 results remain historical rather than being
relabeled as schema16/schema2 proof.

C2-2 ends at its dispatcher and background-agent producers. Actual ingest/reindex
activation remains C2-8/C2-10. The held root-plan and ingest-child implementation
had no production handler caller, as verified by source reachability (registered
ReindexHandler and BulkReindexHandler still use their existing paths). Public
app-api rooting in WholeProgramDeadCodeTest could not prove activation. The
[held source packet](held/README.md) preserves the complete prior implementation
and related tests outside compiled sources, with a reversible patch and SHA manifest.
Generic pure preparation, ordinary attempt behavior, UTF-8 identity bounds, atomic
root registration/snapshot and generation capture retain active coverage. A new
named detector proves a handler cannot activate prepared replay before its owner
exists. This replaces the superseded test-only assumption of immediate activation;
the original tests are held with the code, not erased from review history.

C2-3 must store prepared payload separately from public request identity. Lookup
and compare operation/mode/public digest first: changed public input always refuses
OPERATION_KEY_REUSED, even for a completed row. Matching terminal input returns the
recorded outcome without preparing; matching incomplete input uses its persisted
preparation. Only an unknown key prepares. Later generation/exclude changes cannot
change the identity or freeze a second plan for a matching retry. C2-8/C2-10 adapt
and connect the held source only after that boundary exists.

D1 section1 now inherits idleActiveGeneration's safety property and may replace
its state.json predicate under runtimeSwapLock during live activation. C2-12 and
D1 identify23974e424 as a guard for the appServices=null replacement interval from
bad1a7622. It is not a processing-gap refusal. D1's already-decided live re-point
removes that interval.

The September10 lookup-margin row is marked superseded and a new dated row states
the implemented row-first, timestamp-before-history_since rule without another
five-minute lookup margin. Quarantine still includes admitted future skew in its
fence. The original closing sentence of the September12 queue-projection row is
restored from41eed16e6's parent; a new dated row records the snapshot-handoff
implementation. The active per-item section0.1 instruction and prior correction
index are restored without an empty-stage placeholder.

C1 status now distinguishes the old closure from fc67f6ed9's later MCP quota
correction. Its real-session negative599/positive601 evidence is retained; the
current focused gate includes that transport fixture. Current integrated/hosted
coverage is reconciled at R10 rather than attributed to the pre-correction head.

## Verification

- Negative827 catches acceptance of an unactivated replay schema. The final test
  expects the existing preparation-error exception, failed generic row, and no effect.
- Rust824: cargo test --lib --locked,85 passing, no failures or ignored cases.
- Store825 passes6 catalog stores and45 durable authorities; compatibility826
  passes the store-checker self-test and all12 release-descriptor cases (13 Node
  test entries including the self-test file).
- Canonical docs regenerate at828/829; link verification830 passes156 files.
- Initial831 exposed an overbroad retirement edit that removed getWatchedPaths;
  the unchanged accessor was restored.832 exposed an unused child-only owner
  field, removed with its primitive.833 exposed the new fixture expecting a result
  rather than the established thrown preparation-error contract; the test now
  verifies that exception and still checks failed-row/no-effect behavior.

- Final835 represents281 tests/37 suites with zero failures/errors/skips; launcher
  and UI execute, five unchanged-input tasks reuse earlier successful item executions.
  Selected PMD and UI integration-test compilation pass.834 had already passed180
  cases but failed PMD on a redundant assertion qualifier, corrected before835.
- Final store836 and canonical links837 pass; engine-port self-test838 and gate839
  pass. The held patch reverse-application check passes.

Raw logs and XML live at tmp/c2-review-r9-* in the lane worktree. Retain through
lane acceptance plus30 days and export before releasing the worktree. The final
command/count record is review-r9-verification.json. No missing or failed check is
counted as completion; R10/full batch and independent review remain outstanding.
