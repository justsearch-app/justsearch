# C2-2 VDU replay retention

September12,2026; source is the replay-retention diff atop e784a97f3, identified by
the commit containing this record. Windows/Java25. Replay keeps the existing SQLite
switch buffer when its parent is missing, chunk replacement fails, the runtime is
unavailable, the payload is blank or its covering commit fails. Chunks precede the
terminal parent update. No new journal or persistence representation is added.

Focused677 passes29 cases/five suites, zero failures/errors/skips, plus indexer-worker
main/test PMD. The tests use a real SQLite queue, reopen it around retries and inject
index failures. The existing real-Lucene chunk-regeneration case remains selected.

```text
gradlew.bat :modules:indexer-worker:test --tests '*VduReplayFailureRetentionTest'
  --tests '*WorkerIngestServiceChunkRegenerationTest' --tests '*SwitchBufferDurabilityTest'
  --tests '*SyncRootReplayProvenanceTest'
  :modules:indexer-worker:pmdMain :modules:indexer-worker:pmdTest
  -PtestParallelism=1 --max-workers=4 --console=plain
```

Before negative678, explicitly stub the parent update as successful in both chunk
failure fixtures so the old parent-first implementation reaches the injected chunk
failure. Restore the original e784a97f3 migration source and select only
VduReplayFailureRetentionTest:13 cases/two suites, exactly five intended failures
(missing parent, deletion, second chunk insertion, unavailable runtime, blank payload).
The six mandatory guardrail cases and two commit-order/retry cases remain green.
After restoring exact intended source, final679 executes the full command above:
29 cases/five suites, zero failures/errors/skips; test and test PMD execute, main PMD
reuses unchanged input. No assertion or validation was weakened.
Independent refute-first review finds no blocker to this bounded claim, independently
parses final679/negative678 XML and verifies restored source bytes. The new replay
tests inspect SQLite durability and mocked Lucene ordering; they do not inspect a
real committed Lucene directory after replay.

The canonical migration explanation is updated; llms/skills regeneration and their
checks, canonical links, dependency graph and runtime-config matrix checks pass.
No embedded skill source changes result; both harness skill trees were checked for
this source. git diff --check and docs-validate pass.

Evidence: tmp/c2-2-vdu-replay-{677,679}.txt with matching -xml/-counts.json;
tmp/c2-2-vdu-replay-negative-678.txt and -678-xml; intended-source restore copy
tmp/c2-2-vdu-replay-MigrationOps-restored.java. Keep through lane acceptance plus30 days
and export before worktree release. C2-2 remains open: shared live/replay outcome
rules, typed deferred completion, generation-race closure, operation terminalization
and integrated/hosted/installed proof are still required. Retention alone does not
establish successful index effects or a safe generation activation.
