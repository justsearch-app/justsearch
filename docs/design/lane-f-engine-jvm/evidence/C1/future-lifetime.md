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

## Interruptible waits (C1-5, 2026-09-09)

`EngineFutures.await` uses interruptible `get`, requests task cancellation, preserves the
waiter's interrupt flag and exposes the original interruption as the completion cause.
Cancellation still releases the task's owner only at actual exit. A supplier that ignores
interruption proves that prompt caller return does not prematurely release ownership.

Tested above `e021b8828` on Windows/Temurin 25: run44 passed eight EngineFutures tests
(plus three core architecture tests). Replacing the entire await body with `join()` compiled
and failed both the core and inference interruption regressions with their expected three-second
prompt-return TimeoutException (run45). Restoration passed the same source/test inputs from
Gradle's valid run44 cache (run46). Raw logs are `tmp/c1-batch4-interrupt-tests-44.txt`,
`tmp/c1-batch4-interrupt-mutant-45.txt`, and `tmp/c1-batch4-interrupt-restored-46.txt`;
preserved XML is under `tmp/c1-batch4-interrupt-green-44` and
`tmp/c1-batch4-interrupt-mutant-45`. This is focused local proof, not C1 closure.
