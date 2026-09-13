# C2-2 shared VDU result writer

September12,2026; e225cbf4b plus the shared-writer diff identified by the commit
containing this record. Windows/Java25. VduResultWriter owns the live and replay
parent/chunk mutation rules using the existing UpdateVduResultRequest. The callers
retain their covering commits: per direct result, or before switch-buffer removal.
No new journal, receipt registry or wire representation is added.

Replay now preserves the live rules for rejected text, extraction/dropout metadata,
legacy status and unspecified content. Invalid SUCCESS_TEXT and unknown typed
outcomes refuse before buffer acceptance; persisted invalid inputs stay queued.
Strict dropout-reason read failure cannot publish an incorrect terminal reason.
Chunks remain before the terminal parent mutation. Existing live compatibility,
including unspecified content with no terminal legacy status, is preserved.

## Focused and negative proof

Compile/main PMD680 passes. Corrected682 passes78 cases/15 suites with zero failures,
errors or skips. Indexer-worker and app-engine tests execute; worker-services reuses
its successful681 execution. All four selected main/test PMD tasks pass or reuse
unchanged successful inputs. The command is:

```text
gradlew.bat :modules:indexer-worker:test --tests '*VduResultReplayParityTest'
  --tests '*VduReplayFailureRetentionTest' --tests '*WorkerIngestServiceVduHardeningTest'
  --tests '*WorkerIngestServiceChunkRegenerationTest'
  :modules:worker-services:test --tests '*VduMutationCommitTest'
  --tests '*ChunkThresholdCharacterizationTest'
  :modules:app-engine:test --tests '*EngineEnrichmentBacklogFailureTest'
  :modules:worker-services:pmdMain :modules:worker-services:pmdTest
  :modules:indexer-worker:pmdMain :modules:indexer-worker:pmdTest
  -PtestParallelism=1 --max-workers=4 --console=plain
```

Nine parity cases inspect real committed Lucene directories before runtime close,
with commit timers stopped and the real SQLite buffer closed/reopened before replay.
They compare parent identity, content/hash, VDU status/processed, extraction method
and dropout reason, enrichment/page count and chunk revisions; explicit expected
values prevent a matching wrong projection from passing. Two invalid cases prove
live refusal and retained replay with unchanged committed parent data. Unknown
outcome999 is a synthetic future-producer payload; current admission refuses it.
Preview/language/embedding status are not individually asserted by this fixture.

Initial681 exposed fixture errors: stored booleans are numeric0/1 (FieldMapper), and
protobuf getNumber refuses an unknown enum before serialization. Correcting those
fixtures preserves the intended assertions. Its log is retained. Its initial XML
was accidentally overwritten during recapture after682 and is explicitly marked
invalid; no681 XML proof is claimed.

Negative683 restores both e225cbf4b caller implementations, keeping the new tests:

```text
gradlew.bat :modules:indexer-worker:test --tests '*VduResultReplayParityTest'
  --tests '*WorkerIngestServiceVduHardeningTest.invalidVduResultsAreRejectedBeforeSwitchBufferAcceptance'
  :modules:worker-services:test
  --tests '*VduMutationCommitTest.dropoutReadFailureCannotPublishAnIncorrectTerminalReason'
  -PtestParallelism=1 --max-workers=4 --console=plain --continue
```

It executes19 cases/four suites: all13 new cases fail for the missing parity,
validation or strict-read behavior; six mandatory guardrail cases pass. Exact
intended caller bytes are restored in finally. Final684 repeats the focused command
and passes78 cases/15 suites using identical cached/unchanged results, not another
fresh test execution. The independent reviewer checks source, XML and restoration.

Evidence: tmp/c2-2-vdu-writer-{680,681,682,684}.txt; valid682/684 -xml and -counts.json;
tmp/c2-2-vdu-writer-negative-683.txt with -xml/-counts.json; intended caller copies
tmp/c2-2-vdu-writer-restored-{WorkerIngestService,KnowledgeServerMigrationOps}.java.
Retain through lane acceptance plus30 days and export before worktree release.

Canonical migration explanation and derived-doc/skill checks are updated. This
bounded proof does not close C2-2: typed deferred completion, current generation
boundary, actual offline operation ownership/outcome and integrated/installed proof
remain required. Hosted CI34704917379 at e225cbf4b and CI34704211058 at e784a97f3
are successful prior-revision runs; neither verifies this newer shared writer.
