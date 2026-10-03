# C2-2 direct VDU commits

September 12, 2026. Source: bounded diff atop6b1d6db4f, identified by the commit
containing this record. Windows/Temurin25. Direct VDU results cover parent/chunk
effects with CommitOps.VDU_UPDATE before returning. Processing/retry-exhaustion
writes use VDU_MARK_PROCESSING and successful recovery resets use VDU_RECOVERY.
Reader refresh follows commit. Chunk deletion propagates failure; recovery's strict
reader or commit failure propagates through the actual Engine client instead of zero.
The existing per-document recovery loop still attempts later documents after failure;
its returned count covers only successful resets committed together. An all-failed
reset selection throws instead of returning zero. Retry count reads are strict, so
I/O failure cannot default the poison-document budget back to zero. Chunk replacement
precedes the terminal parent update: a failure plus a later unrelated commit retains
the previous recoverable parent rather than publishing terminal partial content.

This is a prerequisite, not C2-2 completion. Buffered SWITCHING acceptance is still
distinct from an index effect; typed deferred receipts, replay completion, generation
boundary, actual procedure completion/context and explicit partial outcomes remain
owed. The immediately following per-item change repairs replay's missing-document
and swallowed chunk-regeneration failures. No global drain or atomic multi-document
transaction is claimed by these commits.

## Proof

Focused670 executes13 cases/two suites (VduMutationCommitTest and
ChunkThresholdCharacterizationTest), zero failures/errors/skips, plus worker main/test
and adapter main PMD. Durable assertions open DirectoryReader from FSDirectory before
runtime close, with the periodic commit timer stopped: successful reads cannot come
from NRT, a later periodic commit or graceful shutdown. Parent revision and chunk
revisions share the committed index. Controlled commit failures prevent acknowledgements.

Broader671 executes157 cases/36 suites, zero failures/errors/skips. All four test tasks
execute, including actual Engine recovery and Worker-to-Engine failure translation.
Command:

```text
gradlew.bat :modules:worker-services:test --tests '*VduMutationCommitTest'
  --tests '*Chunk*Test' --tests '*EnrichmentBacklogReadTest'
  :modules:indexer-worker:test --tests '*WorkerIngestServiceVduHardeningTest'
  --tests '*WorkerIngestServiceChunkRegenerationTest'
  :modules:app-services:test --tests '*OfflineCoordinator*Test*' --tests '*VduBacklogReadTest'
  :modules:app-engine:test --tests '*EngineEnrichmentBacklogFailureTest' --tests '*EngineVduRecoveryTest'
  :modules:worker-services:pmdMain :modules:worker-services:pmdTest
  :modules:indexer-worker:pmdTest :modules:app-services:pmdMain :modules:app-services:pmdTest
  :modules:app-engine:pmdTest :modules:adapters-lucene:pmdMain
  -PtestParallelism=1 --max-workers=4 --console=plain
```

Negative672 removes only the four VDU commit calls and restores suppressed chunk
deletion failures. Running the two670 test selections yields exactly10 failures in13
cases: nine commit regressions and one deletion regression. The three unaffected
cases pass. Restoring exact source bytes and repeating671 as673 passes157 cases with
worker tests FROM-CACHE and the other test tasks UP-TO-DATE. This is restored-input
reuse, not another execution. Tests and validation were not weakened.

Independent review found three issues in that first diff: terminal parent update
preceded chunk replacement, an all-failed reset selection returned zero, and retry
counts still used a best-effort read. The corrected source addresses all three and
adds service-level deletion/second-chunk failure followed by an unrelated commit,
all-reset failure, and retry-reader failure regressions. The pre-correction671/673
results above remain historical. Corrected674 repeats671 plus
`:modules:adapters-lucene:test --tests '*DocumentFieldOpsStrictQueryTest'` and
`:modules:adapters-lucene:pmdTest`:162 cases/37 suites, zero failures/errors/skips.
Adapter, worker, indexer-worker and Engine test tasks execute; app-services reuses
unchanged results UP-TO-DATE. All selected PMD tasks pass.

Negative675 restores the three review defects. Exactly four of14 service cases fail:
both failed replacements publish COMPLETED rather than PROCESSING, all resets failing
returns a false zero, and the retry read error is ignored. Restore exact source and
repeat674 as676:162 green cases with worker tests FROM-CACHE and the remaining four
test tasks UP-TO-DATE. This confirms restored inputs;674 is the corrected execution.
Independent refute-first re-review finds no remaining actionable issue in this
bounded prerequisite after the one consolidated correction round. It independently
checks corrected source,674 counts and the exact four negative675 failures.

No full suite, hosted run or process-kill durability scenario is claimed for this
item. These remain required at the integrated/installed producer boundary. Compilation
retains existing warnings in untouched Lucene, worker enrichment and HeadAssembly
code; no new warning or suppression comes from the edited lines.

## Evidence access

tmp/c2-2-vdu-commit-{670,671,673,674,676}.txt, matching -xml trees and -counts.json retain
the selected results before subsequent filters. Negative evidence is
tmp/c2-2-vdu-commit-negative-{672,675}.txt and their -xml trees. Keep through lane acceptance plus
30 days; export before worktree release. Exact-source copies used for restoration
are tmp/c2-2-vdu-commit-{WorkerIngestService,ChunkDocumentWriter}-restored.java for672
and tmp/c2-2-vdu-commit-674-WorkerIngestService-restored.java for675.
