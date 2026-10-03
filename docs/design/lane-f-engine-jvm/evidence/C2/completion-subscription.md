# C2-4 store completion subscription prerequisite

2026-09-13, at8d50555dd plus the source hashes in
[completion-subscription-verification.json](completion-subscription-verification.json).
The generic port now observes successful terminal transitions from any producer.
The existing SQLite owner captures its immutable row with the write, releases the
lock, then invokes subscribers. Pre-start rejection emits only if its update won;
repeated/refused terminal transitions emit nothing. Runtime observer failure is
logged and leaves the row unchanged while allowing other observers to run.

This adds no executor, durable marker, outbox or completion sequence. It is a live
notification, not durable delivery. Catch-up, row-identity deduplication, startup
replay before pruning and actual history/ledger fan-in remain the next C2-4 cuts.
Removing a subscription prevents future notifications; an in-flight listener
snapshot can still deliver it. Close clears subscribers and refuses new ones.

## Verification and refute-first result

- `OperationCompletionSubscriptionTest.directMemoryAndNoteCompletionIsCommittedAndPublishedOutsideTheStoreLock`
  accepts actual MEMORY/NOTE rows without the dispatcher. A separate SQLite
  connection observes the terminal state, and another thread reads through the
  same store before the callback returns. Repeated finish does not notify again;
  closing the subscription removes it from later transitions.
- `rejectionNotifiesOnlyItsWinningTransitionAndObserverFailureDoesNotChangeCompletion`
  observes a winning pre-start refusal, rejects overwriting an already-running
  effect, and proves a throwing listener cannot prevent the healthy listener or
  alter FAILED/CANCELLED rows.
- `failedTerminalWriteDoesNotNotifyAndClosedStoreRejectsSubscriptions` installs an
  aborting SQLite trigger. Both terminal APIs fail without notification and the
  row stays ACCEPTED. A closed owner refuses new subscriptions.
- Negative1099 deliberately reacquires the store lock during finish notification.
  The first test fails with its cross-thread TimeoutException after the independent
  SQLite read passes.4 represented cases,1 expected failure. Exact source bytes
  are restored before final1100.
- Final1100 executes65 cases in20 suites (observability30, launcher35), with zero
  failures/errors/skips. Affected PMD, formatting and UI integration compilation
  pass.1098's30 cases passed but PMD rejected four unused resource bindings; unnamed
  resources preserve try-with-resources cleanup without suppressing validation.
- Registry gates1101 pass for the existing store/port ownership. The launcher
  negative architecture fixture implements the new method and remains executable.

Review confirms both writes return from locked(...) before publishing; no mutation
or callback executes inside a new transaction, and the callback cannot grant
permission to repeat an effect. The separate SQL connection and fault trigger
challenge real storage behavior outside the design's own expectations. No surviving
objection for this prerequisite; full projection/recovery acceptance remains open.
Only Javadoc clarified in-flight delivery after the final test; executable source
and bytecode are unchanged. Exact commands/counts/source and raw hashes are in the
verification JSON. Keep tmp logs/XML through lane acceptance plus30days and export
before worktree release. Hosted allocation remains pending; this is local proof.
