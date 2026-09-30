# C2-2 strict offline backlog reads

September 12, 2026. Tested source is the strict-read diff atop 3b425cebb; the commit
containing this record identifies that diff. Windows 11 build 10.0.26200, Temurin
25.0.2+10. C2-2 remains open.

The VDU control path uses strict Lucene selection/counts and preserves unavailable,
I/O and cancellation failures. Pending embeddings use a Java-only ingest method;
VDU counts reuse the existing totalCount response. Best-effort status projections
and indexing.proto retain their existing contracts. Reader observations establish
neither a covering commit nor asynchronous procedure completion.

## Executed checks

Focused666 runs:

```text
gradlew.bat :modules:adapters-lucene:test --tests '*DocumentFieldOpsStrictQueryTest'
  :modules:worker-services:test --tests '*EnrichmentBacklogReadTest'
  :modules:app-services:test --tests '*VduBacklogReadTest' --tests '*OfflineCoordinator*Test*'
  :modules:app-engine:test --tests '*EngineVduRecoveryTest'
  :modules:adapters-lucene:pmdMain :modules:adapters-lucene:pmdTest
  :modules:worker-services:pmdMain :modules:worker-services:pmdTest
  :modules:app-services:pmdMain :modules:app-services:pmdTest
  :modules:app-engine:pmdMain :modules:app-engine:pmdTest
  -PtestParallelism=1 --max-workers=4 --console=plain
```

Result: 46 cases in14 suites, zero failures/errors/skips, all four modules' main/test
PMD pass. The adapter test reuses its successful665 execution; the other three test
tasks execute. The Engine recovery test exercises real empty reads through the port.

Negative667 restores the false-empty behavior in the strict adapter selection and
VduOps query. Run the adapter and VduBacklogReadTest selections above with --continue:
exactly three intended regressions fail (adapter I/O plus facade I/O/cancellation).
Restore exact source bytes. Restored668 runs the full666 command successfully with
unchanged-result reuse (adapter/app-services FROM-CACHE, worker/Engine UP-TO-DATE).
This is a restored-source confirmation, not another execution of46 tests.

Additional669 executes `:modules:app-engine:test --tests '*EngineEnrichmentBacklogFailureTest'
:modules:app-engine:pmdTest` with the same parallelism flags: four cases, zero
failures/errors/skips and test PMD pass. These use the real Engine budget/executor
and Java adapter with controlled Worker failures; all three reads preserve CANCELLED,
UNAVAILABLE and INTERNAL, their original causes, and release admission. Pre-cancelled
work never enters the Worker. This is a separate four-case invocation.

Earlier664 failed compilation because the adapter fixture assumed an absent Mockito
dependency. The fixture now uses real Lucene searcher classes; no dependency was added.
Earlier665 passed the adapter regression but rejected a Worker fixture with COMPLETED
embedding status and no vector. Correcting that fixture preserved StatusArtifactContract.

Read-only independent review found no actionable correctness/security defect after
two refute-first passes, including the added Engine translation proof. git diff --check
and docs-validate pass. No full suite, live stack, hosted run or restart-durability
claim attaches to this item; those checks remain owed at their owning boundaries.

## Retained evidence

Raw output is in tmp/c2-2-backlog-{664,665,666,668}.txt, matching -xml trees and
-counts.json where produced; tmp/c2-2-backlog-negative-667.txt and -667-xml;
tmp/c2-2-backlog-port-669.txt with -669-xml/-669-counts.json. Counts were captured
before later test filters. Keep these in the lane worktree through acceptance plus
30 days and export before releasing the worktree. Source assertions and this command
record remain in Git. The next item is covering VDU commits and explicit deferred
buffer outcomes under the existing switch/replay owner.
