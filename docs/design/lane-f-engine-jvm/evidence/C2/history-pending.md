# C2-4 pending completion source and protected retention

Implemented 2026-09-13 from2bcaee280. Schema v5 adds one operations-owned pending
projection bit. Visible terminal transitions and pre-start refusals set it in the
same statement; hidden rows owe no history. The narrow bounded port reads pending
rows by completion time/id, and key-scoped acknowledgement changes only the bit.
Age/startup/cap pruning preserve pending rows. If protected rows fill100000 slots,
admission refuses; acknowledgement permits normal eviction again.

The frozen v4 migration fixture proves transactional rollback, retained recent
history and the explicit upgrade boundary: pre-v5 completed rows retain their prior
best-effort ledger behavior, while open rows acquire pending delivery on future
completion. Replaying ambiguous old legacy ledger ids would duplicate history or
invent a past guarantee. This is a migration rule, not an owner-gated decision.

## Verification

- Initial1146 executes91 cases, with two failures in future-version fixtures:
  literal5 became the current version. Both now exercise current+1 and preserve
  main/WAL/SHM bytes; no refusal assertion or architecture rule was weakened.
- Negative1148 removes age/cap pending guards. Three represented cases execute;
  two intended failures detect the missing restart row and admission that succeeds
  by evicting undelivered history. Restore exact saved source bytes before1150.
- Final1150 executes104 cases across25 suites, zero failures/errors/skips:
  observability56, launcher35 and Java upgrade13. PMD and UI integration compilation
  pass. Six new SQLite tests cover direct memory/note completion, pre-start refusal,
  NONE/repeated completion, acknowledgement faults, terminal-write atomicity,
  startup/age/cap retention, bounded late completion, reopen and frozen-v4 migration.
- Negative1147 rejects register4/code5. Register1149 passes with45 durable owners;
  its self-test passes77 assertions.1151 passes12 release descriptor cases and85
  Rust cases, zero failed/ignored. Surface/register gates pass, zero findings.

[Commands, revision, counts and hashes](history-pending-verification.json) enumerate
raw logs/XML under lane tmp. Retain until lane acceptance plus30days and export
before worktree release. The register now declares v5 and readable versions1-4.

## Remaining acceptance

The pending source is implemented; its completion consumer is the next immediate
cut. Until attachment, visible completions remain pending rather than being lost.
Durable append/ack ordering, idle retry, memory/note fan-in, shutdown and atomic SSE
snapshot/replay still need implementation/proof. No C2-4 or lane closure. Full1059
predates these changes; coherent full, installed-v5 and hosted proof remain owed.
Do not reuse successful installed-v2 evidence for v5. Hosted runners remain an
external allocation gap; F/PR1 merge placement is unchanged.
