# C1-5 future lifetime correction — 2026-09-09

A running FutureTask publishes its result before its final ownership cleanup. The callback could
throw after that publication, escaping and killing the registered platform worker. Retrying the
callback would be unsafe because it may already have partially released its owner. The correction
attempts cleanup once and reports the failure through System.Logger without throwing it out of
the executor task. The already-published result remains unchanged. Queued cancellation retains
its existing synchronous/suppressed cleanup-failure behavior.

Local tested source: two-file correction above `2aa63ce818263a17a13f5f62cbb61a9d95a9e30a`,
Windows, Temurin JDK 25. Tests exercise both successful and timed-out running suppliers. A timeout
interrupts the supplier but releases no ownership until the supplier actually exits. Both cases
assert one cleanup attempt, no uncaught worker failure, and no replacement platform thread.

- `:modules:core:test --tests '*EngineFuturesTest'` passed all seven tests in
  `tmp/c1-batch4-future-cleanup-tests-16.txt` and restored run17
  `tmp/c1-batch4-future-cleanup-restored-17.txt`.
- Removing the catch/log boundary failed both new regressions: worker count expected 1, actual 2.
  `tmp/c1-batch4-future-cleanup-mutant.txt`; XML
  `tmp/c1-batch4-future-cleanup-proof/mutant.xml`. The mutation was restored.
- Green XML: `tmp/c1-batch4-future-cleanup-proof/green16.xml` and `restored17.xml`.
- The initial mutation launcher used an invalid Windows relative executable spelling and never
  ran Gradle. Its output remains `tmp/c1-batch4-future-cleanup-mutant-launch-failure.txt` and is
  explicitly not mutation proof. The subsequent run above inspected both assertion failures.

These are focused local proofs of this correction, not integrated, stress, hosted or final C1
acceptance. Retain raw artifacts through lane completion plus 30 days.
