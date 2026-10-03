# C2-2 switch-buffer replay replacement identity

September12, base a4691fc6d. Bounded prerequisite; C2-2 remains open.

## Mechanism

Replay captured a snapshot, applied/committed it and then cleared the entire table.
A producer admitted after capture could insert a new key or replace the captured key;
that accepted write disappeared without being replayed. The deterministic fixture puts
that admission inside the covering commit callback, after replay has applied its snapshot.

The existing switch-buffer row now carries an opaque revision, freshly minted on every
put and returned with the snapshot. After the covering commit, the queue removes only
matching key/revision pairs in its existing lock and transaction. Whole-table clear is
retired. New arrivals survive even when key, payload and millisecond timestamp match;
removal/reinsertion cannot make an old snapshot match a new row. Content/timestamp and
SQLite rowid comparisons do not establish replacement identity. Holding the queue lock
through replay would block producers and couple index/scan work to queue ownership.

Jobs schema16 adds and backfills the revision in the existing transactional version
ladder. Existing payloads and timestamps stay unchanged. Migration failure rolls back
DDL and version together; removal failure rolls back all snapshot deletions. The store
register records version16 and readable version15. D1 widens this same journal and retains
conditional removal; this does not add an operation-attempt ledger.

## Verification

Raw artifacts are in the lane worktree's `tmp/`, retained until lane acceptance plus
30 days and exported before worktree release. Each completed run has a `.txt` log,
`-xml` directory and `-counts.json` at the indicated prefix.

- `c2-2-buffer-version-negative-686`: old a469 production plus the new concurrent replay
  fixture:9 cases/2 suites,3 intended failures,0 errors/skips. All three scenarios lose
  the late admission (expected depth1, actual0); six mandatory test guardrails pass.
- `c2-2-buffer-version-687`: revision implementation,75 cases/9 suites,3 failures from
  existing hard-coded migration target/future-version fixtures. New races and V16 cases
  pass. Run688 repeats that result because a local UTF-8 editing failure left those
  literals unchanged; it is not a separate defect or proof. The fixtures now derive the
  unsupported version from TARGET_VERSION+1 and inject failure at TARGET_VERSION's
  version write, preserving refusal/no-original-byte-change and DDL rollback assertions.
- `c2-2-buffer-version-689`:75 cases/9 suites,0 failures/errors/skips; test task executed.
  Indexer main/test and worker-core main PMD pass (unchanged main tasks reused).

Focused687/688/689 command:

```text
gradlew.bat :modules:indexer-worker:test --tests '*SwitchBufferConcurrentReplayTest' --tests '*SwitchBufferVersionTest' --tests '*SwitchBufferDurabilityTest' --tests '*JobQueueMigrationTest' --tests '*IngestionProvenancePersistenceTest' --tests '*VduReplayFailureRetentionTest' --tests '*VduResultReplayParityTest' --tests '*SyncRootReplayProvenanceTest' :modules:indexer-worker:pmdMain :modules:indexer-worker:pmdTest :modules:worker-core:pmdMain -PtestParallelism=1 --max-workers=4 --console=plain
```

Negative686 command:

```text
gradlew.bat :modules:indexer-worker:test --tests '*SwitchBufferConcurrentReplayTest' -PtestParallelism=1 --max-workers=4 --console=plain
```

Negative690 removes the revision predicate/binding from DELETE while keeping key
matching. It fails3 of13 cases/3 suites exactly at both same-key replacement witnesses
and stale-snapshot reinsertion; new-key isolation, migration/rollback and mandatory
guardrails pass. The script restores original source bytes in finally. Command:

```text
gradlew.bat :modules:indexer-worker:test --tests '*SwitchBufferConcurrentReplayTest' --tests '*SwitchBufferVersionTest' -PtestParallelism=1 --max-workers=4 --console=plain
```

Restored691 uses the focused command and passes75/9,0 failures/errors/skips; the test
task reuses the unchanged689 cache and main PMD executes. It is restored-source cache
proof, not fresh execution. Raw prefixes are `c2-2-buffer-version-negative-690` and
`c2-2-buffer-version-691`; the mutation script is beside the negative log.

Docs index/skill regeneration and checks, canonical links, module graph, runtime
configuration matrix, store-recoverability (including75 gate assertions), surface-altitude
and diff whitespace pass. No generated content change is needed. Independent review
found no production blocker in replacement identity, transaction ownership or migration.

## Limits

This proves snapshot-removal isolation and SQLite migration/rollback behavior. Buffered
VDU acceptance still lacks actual-effect completion, and resumed migration enumeration
can overwrite a replayed VDU result. Generation-boundary and offline-owner completion
work remains in C2-2. D1 owns accepted-write replay through generation activation;
C2-3/C2-4 still owe keyed prepared invocations and durable completion projections for955.
Integrated/stress and hosted proof must cover the final implementation boundary.
