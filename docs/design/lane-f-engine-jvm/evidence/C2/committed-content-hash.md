# C2-8b committed content identity and retained-outcome retry

2026-09-14, working diff over2ab49abc4 in lane-f-pr1-verify.
[Mechanism and review correction](C2-8-content-hash-plan.md).
[Source hashes, exact commands, task inventories and retained XML](committed-content-hash.json).

The existing source SHA-256 now follows its exact claimed write through the journal.
After Lucene commit, SQLite commits DONE, content_hash and the ingestion ledger in one
transaction. Invalid/stale/forged claims cannot publish a hash; SQL rollback retains the
claim for retry. Hash-free completion and both enqueue paths clear old evidence.

Independent review identified an existing stall: a successful Lucene commit followed by
both failed SQL attempts left the journal pending with no further drain until another
indexed document. The existing idle and shutdown owners now retry those committed
transitions with zero new writes. Every ordinary counter reset follows a successful
commit; profiling reset clears the journal before zeroing. Failed commits skip drain.
No new timer, persistent marker, queue or transaction owner is introduced.

## Evidence

- Focused1523 executes both tasks: 83 cases across16 suites, no failures/errors/skips.
  Real SQLite tests cover committed hash after reopen, exact-claim ownership, atomic
  ledger failure rollback and retry, both enqueue APIs and unknown-hash completion.
- Integrated1525 executes worker-core, indexer-worker and worker-services suites:
  2,179 cases across423 suites, 20 existing skips, no failures/errors. All relevant
  PMD and whole-tree Spotless pass. Skips are listed in the manifest: existing model,
  corpus and POSIX fixtures; none was added or weakened by this cut.
- Negative1524 removes writer hash delivery, SQL hash binding and idle/shutdown drains.
  Both tasks execute: 77 represented cases, seven failures. Direct evidence covers
  writer/SQL hash and idle retry. The simultaneous idle failure prevents reaching the
  shutdown assertion; a dropped hash also causes the failed-shutdown test to fail.
  Neither collateral failure independently proves shutdown or failed-commit ordering.
- Shutdown-only negative1526 retains all other production behavior: 65 cases, exactly
  one failure, at the assertion after finalizeShutdownCommit leaves the transition
  pending. Final1527 restores the exact source and executes65 passing cases plus
  PMD/format reused from the unchanged integrated1525 source. The source is byte-identical to integrated1525, whose full proof remains
  applicable. Failed-commit positive tests assert no queue transition was attempted.
- Independent source/counter-invariant reviews are clear. The final evidence review
  caught the combined-negative limitation, prompting the isolated shutdown control.
- Canonical knowledge-server documentation and generated docs/skills are reconciled;
  canonical-link and runtime-config-matrix verification pass.

Output lives under the workspace tmp paths in the JSON, retained through2026-10-14.
These are local unit/module proofs. Root membership, operation-scoped recovery,
representation/grant validation and live/model/installed C2-8/9/11 proof remain owed.
The existing timestamp-only UNCHANGED skip is not a hash witness; mutable jobs rows
alone do not establish a containing operation's completion.

Prior C2-8a checkpoint2ab49abc4 passed all13 hosted jobs in CI34805930681 and
CLA34805928429. This cut still needs its own hosted result after push.
