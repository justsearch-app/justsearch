# R5: bounded history before further producers

September13, b9acc26e8 plus this item. C2-5's store mechanism is implemented:
30-day completion-time retention, 100000-row cap, no open-row eviction, atomic
key-time fence advancement, and typed capacity refusal. Existing rows win before
expiry/cap checks. An ahead-of-clock fence reports its remaining retry delay.
Pruning runs at open and hourly through head.operations-retention; new acceptance
reserves room under the same store lock. A failed pruning transaction rolls back
both deletion and fence. Timer failures log ERROR and retry; close cancels and
awaits termination before releasing its registration, with a retry on failed await.

Schema v2 rebuilds the unchanged columns with UTF-8 byte CHECKs (identity 262144,
checkpoint 4096). The frozen v1 SQL fixture comes from b9acc26e8. Migration preserves
rows, fence and AUTOINCREMENT high-water mark, including an empty table after
deletion. Invalid legacy payloads refuse migration and remain intact. Compatibility
inspection still copies a quiescent database/WAL privately, and now uses quick_check;
no online snapshot or original-SHM mutation is introduced.

Retention exposed a terminal-publication race. The existing finish/refusal methods
now return the SQL row snapshot while holding the store lock. The runner publishes
that snapshot and uses its completed future for later live observations. Repeated
reconciliation can skip an already-evicted terminal boot-snapshot entry. This
replaces the earlier boolean-plus-SELECT contract documented by R3; it does not
add a pin registry, grace window, second history store or terminal writer.

## Named proof

- OperationRetentionTest: aged key is absent and explicitly OPERATION_EXPIRED,
  exact 30-day/key-time boundaries, monotonic fence, older open row and open gaps,
  actual 100000-row capacity, admitted future-key eviction, atomic fence rollback,
  quarantine clock crossing with retry delay and no duplicate, SQL byte bounds,
  v1 migration and interruption with ordering preservation.
- OperationAttemptRunnerTest.evictionAfterTerminalWriteCannotEraseLiveCompletion:
  a port wrapper prunes immediately after the actual SQL terminal/refusal write;
  both cases must still publish their outcome, and late refusal remains harmless.
- OperationDescriptorPreparationTest.identityBoundCountsUtf8BytesRatherThanJavaCharacters:
  the Java input bound agrees with SQL for multibyte text.
- OperationsRetentionTimerTest and HeadAssembly/OrchestrationHandles tests: timer
  registration, hourly scheduling, call, ERROR/retry, ownership and retryable teardown.

Negative801 executes6 cases and fails all five initial retention assertions for the
intended defects. Negative806 executes3 cases and fails both terminal/eviction cases.
Final810 passes228 cases in32 suites, zero failures/errors/skips; all four selected
test tasks execute. Focused811 then executes111 app-services cases after strengthening
the timer test to invoke the captured scheduled callback (production unchanged).
UI integration-test compilation and affected PMD pass. Exact
command, revision and task counts are in [the summary](review-r5-verification.json).
Store-recoverability, its self-test and engine-port gates pass; canonical regeneration
and links pass. One bounded timer worker received one substantive correction round;
root read the diff and fixed the subsequent PMD findings. Final independent review
is the single batch review, still pending.

Intermediate failures are retained honestly:802 caught expiry/cap precedence;
803 had three legacy-fixture parser failures (corrected semicolon in its comment);
805 was a missing test dependency compile failure, replaced with the existing JDK
proxy and a test clock instead of adding a dependency;807/808 caught PMD qualifiers.
They are not additional negative proofs.809 passed227 cases before the final boundary
assertion and repeated-observation correction.

Raw logs and XML live at worktree tmp/c2-review-r5-*.txt and matching -xml/ and
-counts.json siblings; store/port gate and self-test logs use the same prefix.
Retain through lane acceptance plus30 days and export before worktree release.
The outcome HTTP/MCP projection is still C2-4; this item proves the store boundary.
Full batch verification and independent review remain required for closure.
