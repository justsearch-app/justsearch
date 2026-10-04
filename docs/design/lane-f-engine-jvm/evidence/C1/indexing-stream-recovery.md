# C1 indexing-stream opening refusal

2026-09-09. Root-owned correction; no new retry state or executor.

`RemoteIndexingJobsBridge.openStream` previously routed asynchronous stream errors through its
existing bounded resubscription policy, but a synchronous `IndexingJobsSource.subscribe` refusal
escaped `start()` and left `started` set. The one startup caller logs the exception and does not
call start again. Engine admission and bounded stream-executor refusal can both take this path.
Catch `RuntimeException` only around subscription creation and feed the same error callback.
The initial future remains exceptionally completed; the existing retry budget/backoff owns recovery.

Run213 fails the new regression with the original code: `EngineAdmissionException` escapes,
10 represented cases,1 failure. Raw log `tmp/c1-ledger-refusal-before-213.txt` and preserved XML in
`tmp/c1-ledger-refusal-before-213/`. Run214 repeats that failure and separately exposes two mocks
missing the GPU gauge required by the concurrent GPU wiring correction; those mocks were repaired.
Run215 passes the bridge's4 tests (both refusal arms execute inside the new regression), GPU
wiring2, HeadAssembly18 and6 automatically included guard tests. It also passes the isolated
`IndexingLedgerCoherenceTest` using the real Engine in lite mode. Strict test-source checking is on.
Command/log: `tmp/c1-ledger-gpu-restored-215.txt`; XML: `tmp/c1-restored-215/`.

This is **not yet the established cause of hosted ledger failure**. Hosted runs34342537664
(1c7fcfff9) and34345647291 (c7c1eb17e) fail the new-index-event assertion on every retry. The latter
reports overall CI success because integration is advisory, but the failure remains a C1 proof
obligation. Its failed-job output is `tmp/c1-hosted-integration-failure-216.txt`.
Normal standard-model boot205 independently produced469 index ledger entries
(`tmp/c1-clean-ledger-observation-209.json`), and local isolated215 passes. No successful local
result substitutes for explaining the hosted failure.

The isolated fixture's existing log collector now accepts test-body failures, and the ledger
liveness assertion invokes it before teardown deletes the backend directory. This preserves the
actual failing instance's application/stdout/crash evidence in the already-uploaded report path;
logs from unrelated writer-recovery tests cannot diagnose this fixture.
